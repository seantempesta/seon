(ns seon.context-blocks-fixture
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.id :as id]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

; The live installer and loop regression share these ordinary declarations.
; The scenario's orders are DURABLE datoms on every cluster that seeds it, so
; these shapes are not a synthetic registry mutation: the agent declares them
; in an ordinary turn, the writer commits one `:seon.schema/key` entity each, and
; the cluster's projection derives from those entities like every other
; declaration (`seon.schema/projection-from-database`). Declared here as data
; so the key set is DERIVED by everything that needs it.
(def schema-declarations
  [[:example/order '[:string {:seon.db/identity true}]]
   [:example/amount :int]
   [:example/customer :string]
   [:example/order-row '[:map {:seon.db/attributes true :seon.program/partition :seon.data}
                         [:example/order :example/order]
                         [:example/amount :example/amount]
                         [:example/customer :example/customer]]]])

(def schema-keys
  "Exactly the declaration keys this scenario adds to a cluster's population."
  (into #{} (map first) schema-declarations))

(def schema-source
  (str/join "\n"
            (map (fn [[schema-key form]]
                   (pr-str (list 'seon.schema/register! schema-key form)))
                 schema-declarations)))

(def orders
  [{:example/order "a1" :example/customer "Ada" :example/amount 60}
   {:example/order "a2" :example/customer "Ada" :example/amount 55}
   {:example/order "b1" :example/customer "Bea" :example/amount 100}
   {:example/order "c1" :example/customer "Cy" :example/amount 40}])

(def instruction
  "Find the customer with the largest order total with a contracted function and a test, add an order of 40 for them, and tell me the customer and both totals.")

(defn- checked [result]
  ;; Debt: db/transact! retains generic error outputs.
  (when (and (:seon.error/at result) (:seon.error/layer result) (:seon.error/operation result))
    (throw (ex-info (str "Juniper fixture operation failed: " (pr-str result)) result)))
  result)

(defn seed!
  "Replace only Juniper's disposable scenario facts after its graph is idle.

  `settings-fn` transforms the seeded settings component: the default keeps
  provider calls disabled; a live provider run passes
  `#(dissoc % :seon.config.ai/no-provider)`."
  ([connection] (seed! connection identity))
  ([connection settings-fn]
  (checked
   (db/transact!
    connection
    [[:db.fn/call
      (fn [database]
        (let [agent-eid (db/q '[:find ?e . :where [?e :seon.agent/id "juniper"]] database)
              messages (db/q '[:find [?e ...] :in $ ?a
                                :where (or [?e :seon.message/to ?a]
                                           [?e :seon.message/from ?a])] database agent-eid)
              faults (db/q '[:find [?e ...] :in $ ?a :where [?e :seon.error/agent ?a]] database agent-eid)
              old-orders (db/q '[:find [?e ...] :where [?e :example/order]] database)
              settings (db/q '[:find ?s . :in $ ?a :where [?a :seon.agent/settings ?s]] database agent-eid)
              seeded-settings (settings-fn
                               {:db/id (or settings "juniper-settings")
                                :seon.config/agent agent-eid
                                :seon.config.ai/no-provider true
                                :seon.config.eval/time-limit-ms 10000
                                :seon.config.run/max-episode-runs 30})]
          (into (mapv #(vector :db.fn/retractEntity %) (concat messages faults old-orders))
                (concat orders
                        ;; Omitting a key leaves an existing value unchanged, so a
                        ;; setting the transform removed is retracted explicitly.
                        (when settings
                          (for [[attribute value] (db/pull database '[*] settings)
                                :when (and (not= :db/id attribute)
                                           (not (contains? seeded-settings attribute)))]
                            [:db/retract settings attribute value]))
                        [{:db/id agent-eid
                          :seon.agent/settings seeded-settings}
                         {:db/id "fixture-root-message" :seon.message/id (id/id (random-uuid) 8) :seon.message/from [:seon.agent/id "root"] :seon.message/to [:seon.agent/id "juniper"] :seon.message/content instruction}]))))]]))
  {:seon.test/orders (count orders)}))

(defn submit!
  "Submit one fixture reply and observe its terminal fact under the config bound."
  [handle routing source]
  (let [connection (:seon.db/connection handle)
        request {:seon.turn.loop/cluster handle
                 :seon.agent/routing routing :seon.agent/id "juniper"}
        bound (:seon.config.agent/turn-completion-backstop-ms
               (checked (config/effective @connection (:seon.cluster/name handle))))
        deadline (+ (System/nanoTime) (* 1000000 bound))]
    (loop []
      (when (> (System/nanoTime) deadline)
        (throw (ex-info "Juniper fixture submission exceeded its bound" {:seon.test/source source})))
      (let [event (async/chan (async/sliding-buffer 1))
            listener (Object.)
            await! (fn []
                     (when-not (= event (second (async/alts!!
                                                [event (async/timeout (max 1 (quot (- deadline (System/nanoTime)) 1000000)))])))
                       (throw (ex-info "Juniper fixture turn did not settle" {:seon.test/source source}))))]
        (d/listen connection listener (fn [report]
                                       (when (some #(and (= :seon.turn/closed-tx (:a %)) (:added %))
                                                   (:tx-data report))
                                         (async/offer! event true))))
        (let [result
              (try
                (let [submitted (agent/evaluate-source! (assoc request :seon.cluster.reply/text source))]
                  (if (contains? #{:seon.turn/agent-already-running :seon.turn/run-exists}
                                 (:seon.turn/rule submitted))
                    (do
                      (when-not (and (= :seon.turn/run-exists (:seon.turn/rule submitted))
                                     (:seon.turn/closed-tx
                                      (db/pull @connection [:seon.turn/closed-tx]
                                               [:seon.turn/id (get-in submitted [:seon.turn/request :seon.turn/id])])))
                        (await!))
                      ::busy)
                    (let [id (:seon.turn/id (checked submitted))]
                      (loop []
                        (when-not (:seon.turn/closed-tx (db/pull @connection [:seon.turn/closed-tx] [:seon.turn/id id]))
                          (await!)
                          (when (> (System/nanoTime) deadline)
                            (throw (ex-info "Juniper submitted turn remained open" {:seon.turn/id id})))
                          (recur)))
                      id)))
                (finally (d/unlisten connection listener) (async/close! event)))]
          (if (= ::busy result) (recur) result))))))

(declare clear-history!)

(defn declared!
  "Refuse unless every scenario key is a FACT of the cluster this installed in.

  The scenario's orders are durable datoms, so its shapes are not a synthetic
  registry mutation: the agent declares them in an ordinary turn, the writer
  commits one `:seon.schema/key` row each, and a cluster's projection DERIVES
  from those rows (`seon.schema/projection-from-database`). This checks the
  authority — the rows, and the projection derived at the cluster's own basis
  — rather than the in-memory mirror a later adoption re-decides. A missing
  key is named; absence is never read as health."
  [handle]
  (let [database (db/db (:seon.db/connection handle))
        derived (get-in (schema/projection-from-database database)
                        [:seon.schema.projection/forms])
        entity-form (fn [schema-key]
                   (:seon.schema/form
                    (db/pull database [:seon.schema/form]
                             [:seon.schema/key schema-key])))
        missing-entities (into (sorted-set)
                           (remove (comp string? entity-form)) schema-keys)
        missing-forms (into (sorted-set)
                            (remove #(contains? derived %)) schema-keys)]
    (when (or (seq missing-entities) (seq missing-forms))
      (let [evidence {:seon.schema/missing-rows missing-entities
                      :seon.schema/missing-projection-keys missing-forms}]
        (throw (ex-info (str "Juniper scenario declarations are not facts: "
                             (pr-str evidence))
                        evidence))))
    {:seon.schema/keys (vec (sort schema-keys))}))

(defn install!
  "Admit schema through the actual agent graph, then replace the scenario."
  ([handle routing] (install! handle routing (fn [] nil)))
  ([handle routing before-clear] (install! handle routing before-clear identity))
  ([handle routing before-clear settings-fn]
  (let [connection (:seon.db/connection handle)
        request {:seon.turn.loop/cluster handle
                 :seon.agent/routing routing :seon.agent/id "juniper"}]
    (try
      (agent/start-flow! request)
      (let [id (submit! handle routing schema-source)
            errors (db/q '[:find [?error ...] :in $ ?id
                           :where [?turn :seon.turn/id ?id]
                                  [?evaluation :seon.cluster.eval/run ?turn]
                                  [?evaluation :seon.cluster.eval/error ?error]] @connection id)]
        (when (seq errors)
          (throw (ex-info (str "Juniper schema declaration failed: " (pr-str errors)) {:seon.test/errors errors}))))
      (agent/stop-flow! request)
      (seed! connection settings-fn)
      (finally (agent/stop-flow! request)))
    (before-clear)
    (agent/stop-flow! request)
    (clear-history! connection)
    {:seon.test/orders (count orders)})))

(defn clear-history!
  "Erase setup history after the fixture agent and earlier arm wakes are idle."
  [connection]
    (checked
     (db/transact!
      connection
      [[:db.fn/call
        (fn [database]
          (let [turns (db/q '[:find [?t ...] :where
                              [?a :seon.agent/id "juniper"]
                              [?t :seon.turn/agent ?a]] database)
                evaluations (db/q '[:find [?e ...] :in $ [?t ...]
                                     :where [?e :seon.cluster.eval/run ?t]] database turns)]
            (mapv #(vector :db.fn/retractEntity %) (concat evaluations turns))))]])))

(defn install-running!
  "Install the shared live scenario and leave its ordinary graph running.

  `settings-fn` reaches `seed!`; the two-argument form keeps provider calls
  disabled."
  ([handle routing] (install-running! handle routing identity))
  ([handle routing settings-fn]
  (let [connection (:seon.db/connection handle)
        cluster-name (:seon.cluster/name handle)]
    ;; Re-seeding deliberately erased the bootstrap turn; existing identity
    ;; is stable, so only a first installation constructs the agent.
    (when-not (:seon.agent/id
               (db/pull @connection [:seon.agent/id] [:seon.agent/id "juniper"]))
      (checked (cluster/ensure-entity!
                connection (:seon.db.process/id handle)
                {:seon.agent/id "juniper" :seon.cluster/name cluster-name
                 :seon.ns/name 'my.agents.juniper}))
      (test-support/assign-task! connection "juniper"))
    (install!
     handle routing
     (fn []
       (let [ack (async/promise-chan)
             settings (merge (config/effective @connection cluster-name)
                             (ai/agent-overlay @connection "juniper"))
             bound (min (:seon.config.eval/time-limit-ms settings)
                        (:seon.config.agent/turn-completion-backstop-ms settings))]
         (try
           (async/put! (:seon.cluster.wake/channel handle) {:seon.agent/quiesce ack})
           (when-not (= :seon.agent/quiesced
                        (first (async/alts!! [ack (async/timeout bound)])))
             (throw (ex-info "Fixture db<->flow barrier did not arrive"
                             {:seon.cluster/name cluster-name})))
           (finally (async/close! ack)))))
     settings-fn)
    (agent/start-flow! {:seon.turn.loop/cluster handle :seon.agent/routing routing
                 :seon.agent/id "juniper"})
    (declared! handle))))
