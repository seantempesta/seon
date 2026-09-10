(ns evidence-listens-probe-2026-09-09
  (:require [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.operator]
            [seon.operator.runtime]
            [seon.schema :as schema]))

(defn probe!
  "Observe Juniper's actual mailbox proc on the authorized scratch cluster."
  []
  (let [cluster "evidence-listens"
        instance (get @seon.operator.runtime/running-instances cluster)
        handle (:seon.turn.loop/cluster instance)
        connection (seon.operator/connection cluster)
        entry (get-in @(:seon.agent/routing instance) [:seon.agent/armed "juniper"])
        graph (:seon.flow/graph entry)
        deliveries (fn []
                     (let [ping (flow/ping graph :timeout-ms 1000)
                           count (get-in ping [:seon.agent/mailbox ::flow/count])]
                       (or count (throw (ex-info "Mailbox did not answer its bounded ping" ping)))))
        write! (fn [tx]
                 (let [report (db/transact! connection tx)]
                   (when (:seon.error/kind report)
                     (throw (ex-info "Live probe write refused" report)))
                   report))]
    (schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       (write! [[:db/add [:seon.config/cluster cluster] :seon.config.ai/no-provider true]
                {:seon.config/agent [:seon.agent/id "root"] :seon.config.ai/no-provider true}])
       (assert (true? (d/q '[:find ?v . :where [?a :seon.agent/id "juniper"]
                            [?a :seon.agent/settings ?s] [?s :seon.config.ai/no-provider ?v]]
                          @connection)))
       (write! [{:seon.runtime/agent [:seon.agent/id "juniper"]
                 :seon.runtime/listens [{:seon.listen/attribute :example/amount}]}])
       (let [before (deliveries)
             attempts-before (or (d/q '[:find (count ?a) . :where [?a :seon.ai.attempt/id]]
                                      @connection) 0)
             started (System/nanoTime)
             amount (d/q '[:find ?v . :where [?e :example/order "a1"] [?e :example/amount ?v]]
                         @connection)
             report (write! [[:db/add [:example/order "a1"] :example/amount (inc amount)]])
             deadline (+ started 5000000000)
             after (loop []
                     (let [n (deliveries)]
                       (cond
                         (> n before) n
                         (> (System/nanoTime) deadline)
                         (throw (ex-info "Amount did not wake Juniper within five seconds"
                                         {:before before :after n}))
                         :else (recur))))
             millis (/ (- (System/nanoTime) started) 1000000.0)]
         (write! [[:db/add [:example/order "a1"] :example/customer (str "Evidence probe " amount)]])
         (let [unrelated (deliveries)
               attempts (d/q '[:find (count ?a) . :where [?a :seon.ai.attempt/id]] @connection)]
           (assert (= after unrelated) "Unrelated attribute woke Juniper")
           (assert (= attempts-before (or attempts 0)) "Probe created a provider attempt")
           {:seon.probe/before before :seon.probe/after after
            :seon.probe/after-unrelated unrelated :seon.probe/wake-ms millis
            :seon.probe/amount-t (db/basis-t (:db-after report))
            :seon.probe/provider-attempts-before attempts-before
            :seon.probe/provider-attempts-after (or attempts 0)}))))))
