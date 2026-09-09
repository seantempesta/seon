(ns seon.context-blocks-fixture
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
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
  "Which customer has the largest total? Add an order of 40 for them and tell me the new total.")

(def authored-plan
  (let [steps [["query" "Query the orders" "I have read the order ids, customers, and amounts."]
               ["aggregate" "Find the customer with the largest total" "A grouped sum query identifies the customer and their total."]
               ["transact" "Add an order of 40 for that customer" "The transaction result identifies the new order."]
               ["requery" "Read the customer's new total" "A fresh grouped sum query includes the new order."]
               ["reply" "Tell root the customer and new total" "The sent message contains the customer and verified new total."]
               ["done" "Finish the session" "All preceding plan items are complete."]]]
    {:db/id "juniper-plan"
     :my.plan/objective instruction
     :my.plan/current-step "fixture-juniper/query"
     :my.plan/steps
     (mapv (fn [position [id title criterion]]
             (cond-> {:db/id (str "fixture-juniper/" id)
                      :my.plan.item/id (str "juniper/" id)
                      :my.plan.item/title title
                      :my.plan.item/expected-result criterion
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
                                :where (or [?e :seon.cluster.message/to ?a]
                                           [?e :seon.cluster.message/from ?a])] database agent-eid)
              faults (db/q '[:find [?e ...] :in $ ?a :where [?e :seon.error/agent ?a]] database agent-eid)
              old-orders (db/q '[:find [?e ...] :where [?e :example/order]] database)
              settings (db/q '[:find ?s . :in $ ?a :where [?a :seon.agent/settings ?s]] database agent-eid)]
          (into (mapv #(vector :db.fn/retractEntity %) (concat messages faults old-orders (when old-plan [old-plan])))
                (concat orders
                        [{:db/id agent-eid
                          :seon.agent/plan (update authored-plan :my.plan/steps set)
                          :seon.agent/settings
                          {:db/id (or settings "juniper-settings")
                           :seon.config.ai/no-provider true
                           :seon.config.eval/time-limit-ms 10000
                           :seon.config.run/max-episode-runs 20}}
                         {:seon.cluster.message/id "juniper/largest-customer"
                          :seon.cluster.message/from [:seon.agent/id "root"]
                          :seon.cluster.message/to [:seon.agent/id "juniper"]
                          :seon.cluster.message/at #inst "2026-09-09T12:00:00Z"
                          :seon.cluster.message/content instruction}]))))]]))
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
                                       (when (some #(and (= :seon.turn/closed-at (:a %)) (:added %))
                                                   (:tx-data report))
                                         (async/offer! event true))))
        (let [result
              (try
                (let [submitted (turn/virtual-turn! (assoc request :seon.cluster.reply/text source))]
                  (if (contains? #{:seon.turn/agent-already-running :seon.turn/run-exists}
                                 (:seon.turn/rule submitted))
                    (do
                      (when-not (and (= :seon.turn/run-exists (:seon.turn/rule submitted))
                                     (:seon.turn/closed-at
                                      (db/pull @connection [:seon.turn/closed-at]
                                               [:seon.turn/id (get-in submitted [:seon.turn/request :seon.turn/id])])))
                        (await!))
                      ::busy)
                    (let [id (:seon.turn/id (checked submitted))]
                      (loop []
                        (when-not (:seon.turn/closed-at (db/pull @connection [:seon.turn/closed-at] [:seon.turn/id id]))
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
