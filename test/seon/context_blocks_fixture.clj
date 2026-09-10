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
            [seon.turn :as turn]))

; The live installer and loop regression share these ordinary declarations.
(def schema-source
  (str/join "\n"
            (map pr-str
                 '[(seon.schema/register! :example/order
                                         [:string {:seon.db/identity true}])
                   (seon.schema/register! :example/amount :int)
                   (seon.schema/register! :example/customer :string)
                   (seon.schema/register! :example/order-row
                                         [:map {:seon.db/attributes true}
                                          [:example/order :example/order]
                                          [:example/amount :example/amount]
                                          [:example/customer :example/customer]])])))

(def orders
  [{:example/order "a1" :example/customer "Ada" :example/amount 60}
   {:example/order "a2" :example/customer "Ada" :example/amount 55}
   {:example/order "b1" :example/customer "Bea" :example/amount 100}
   {:example/order "c1" :example/customer "Cy" :example/amount 40}])

(def instruction
  "Find the customer with the largest order total with a contracted function and a test, add an order of 40 for them, and tell me the customer and both totals.")

(def authored-plan
  (let [steps [["read" "Read the orders" "A query over :example/order entities has returned their :example/order ids, :example/customer values, and :example/amount values."]
               ["define" "Define `largest-customer` with a `:malli/schema` contract (rows → `{:customer :total}`)" "A query finds the :seon.fn row for largest-customer with :seon.fn/spec present."]
               ["test" "Write a `deftest` over the fixture data and run it" "After (my.test/run), a query finds the :seon.test row's last result with a positive :seon.test/pass-count, zero :seon.test/fail-count, and zero :seon.test/error-count."]
               ["save" "Run it and save the answer" "A query finds a :my.note entity linked to Juniper whose :my.note/content records the customer and original total returned by largest-customer."]
               ["add" "Add an order of 40 for that customer" "A query finds the new :example/order entity with that :example/customer and :example/amount 40."]
               ["again" "Run it again and record the new total" "A query finds the :my.note entity's :my.note/content carrying the customer and both totals, with the new total verified by calling largest-customer on freshly queried orders."]
               ["report" "Report and finish" "A query finds the :seon.message from Juniper to root containing the customer and both verified totals; after (my.agent/done), session state shows the session closed."]]]
    {:db/id "juniper-plan"
     :my.plan/agent [:seon.agent/id "juniper"]
     :my.plan/objective instruction
     :my.plan/current-step "fixture-juniper/read"
     :my.plan/steps
     (mapv (fn [position [id title criterion]]
             (cond-> {:db/id (str "fixture-juniper/" id)
                      :my.plan.item/id (str "juniper/" id)
                      :my.plan.item/title title
                      :my.plan.item/done-when criterion
                      :my.plan.item/position position}
               (pos? position)
               (assoc :my.plan.item/needs
                      #{(str "fixture-juniper/" (first (nth steps (dec position))))})))
           (range) steps)}))

(defn- checked [result]
  (when (:seon.error/kind result)
    (throw (ex-info (str "Juniper fixture operation failed: " (pr-str result)) result)))
  result)

(defn seed!
  "Replace only Juniper's disposable scenario facts after its graph is idle."
  [connection]
  (checked
   (db/transact!
    connection
    [[:db.fn/call
      (fn [database]
        (let [agent-eid (db/q '[:find ?e . :where [?e :seon.agent/id "juniper"]] database)
              old-plan (db/q '[:find ?p . :in $ ?a :where [?a :seon.agent/plan ?p]] database agent-eid)
              messages (db/q '[:find [?e ...] :in $ ?a
                                :where (or [?e :seon.message/to ?a]
                                           [?e :seon.message/from ?a])] database agent-eid)
              faults (db/q '[:find [?e ...] :in $ ?a :where [?e :seon.error/agent ?a]] database agent-eid)
              old-orders (db/q '[:find [?e ...] :where [?e :example/order]] database)
              settings (db/q '[:find ?s . :in $ ?a :where [?a :seon.agent/settings ?s]] database agent-eid)]
          (into (mapv #(vector :db.fn/retractEntity %) (concat messages faults old-orders (when old-plan [old-plan])))
                (concat orders
                        [{:db/id agent-eid
                          :seon.agent/plan (assoc (update authored-plan :my.plan/steps set) :my.plan/agent agent-eid)
                          :seon.agent/settings
                          {:db/id (or settings "juniper-settings")
                           :seon.config/agent agent-eid
                           :seon.config.ai/no-provider true
                           :seon.config.eval/time-limit-ms 10000
                           :seon.config.run/max-episode-runs 30}}
                         {:seon.message/id (id/id (random-uuid) 8) :seon.message/from [:seon.agent/id "root"] :seon.message/to [:seon.agent/id "juniper"] :seon.message/content instruction :seon.message/inbox [:seon.agent/id "juniper"]}]))))]]))
  {:seon.test/orders (count orders)})

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
                (let [submitted (turn/virtual-turn! (assoc request :seon.cluster.reply/text source))]
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

(defn install!
  "Admit schema through the actual agent graph, then replace the scenario."
  ([handle routing] (install! handle routing (fn [] nil)))
  ([handle routing before-clear]
  (let [connection (:seon.db/connection handle)
        request {:seon.turn.loop/cluster handle
                 :seon.agent/routing routing :seon.agent/id "juniper"}]
    (try
      (agent/arm! request)
      (let [id (submit! handle routing schema-source)
            errors (db/q '[:find [?error ...] :in $ ?id
                           :where [?turn :seon.turn/id ?id]
                                  [?evaluation :seon.cluster.eval/run ?turn]
                                  [?evaluation :seon.cluster.eval/error ?error]] @connection id)]
        (when (seq errors)
          (throw (ex-info (str "Juniper schema declaration failed: " (pr-str errors)) {:seon.test/errors errors}))))
      (agent/disarm! request)
      (seed! connection)
      (finally (agent/disarm! request)))
    (before-clear)
    (agent/disarm! request)
    (clear-history! connection)
    {:seon.test/orders (count orders) :seon.test/plan-items (count (:my.plan/steps authored-plan))})))

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
  "Install the shared live scenario and leave its ordinary graph running."
  [handle routing]
  (let [connection (:seon.db/connection handle)
        cluster-name (:seon.cluster/name handle)]
    ;; Re-seeding deliberately erased the bootstrap turn; existing identity
    ;; is stable, so only a first installation constructs the agent.
    (when-not (:seon.agent/id
               (db/pull @connection [:seon.agent/id] [:seon.agent/id "juniper"]))
      (checked (cluster/ensure-entity!
                connection (:seon.db.process/id handle)
                {:seon.agent/id "juniper" :seon.cluster/name cluster-name
                 :seon.ns/name 'my.agents.juniper})))
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
             (throw (ex-info "Fixture armer barrier did not arrive"
                             {:seon.cluster/name cluster-name})))
           (finally (async/close! ack))))))
    (let [opening (checked (turn/system-turn {:seon.turn.loop/cluster handle
                                              :seon.agent/id "juniper"
                                              :seon.turn/write? true}))]
      (agent/arm! {:seon.turn.loop/cluster handle :seon.agent/routing routing
                   :seon.agent/id "juniper"})
      (select-keys opening [:seon.turn/id :seon.error/kind :seon.error/message]))))
