(ns seon.transact-feedback-probe
  "Reproduce the scratch-cluster evaluation and validation cost evidence."
  (:require [seon.db :as db]
            [seon.cluster.boot :as operator]
            [seon.operator.runtime :as runtime]
            [seon.repl :as repl]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]))

(defn probe!
  "Run after the canonical Juniper fixture is installed with no-provider true."
  [cluster-name]
  (let [instance (get @runtime/running-instances cluster-name)
        handle (:seon.turn.loop/cluster instance)
        connection (operator/connection cluster-name)
        database @connection
        projection (schema/projection-from-database database)
        source "(seon.db/transact! [[:db/add [:example/order \"a1\"] :example/amount \"wrong\"]])"
        transaction (mapv (fn [n] [:db/add (str "feedback/measure-" n)
                                  :my.plan.item/title "A valid title"])
                          (range 50))]
    (schema/call-with-projection
     projection
     (fn []
       (assert (true? (get-in (db/pull database
                                       '[{:seon.agent/settings [:seon.config.ai/no-provider]}]
                                       [:seon.agent/id "juniper"])
                             [:seon.agent/settings :seon.config.ai/no-provider])))
       (assert (nil? (#'db/write-error database projection transaction)))
       (dotimes [_ 100] (#'db/write-error database projection transaction))
       (let [started (System/nanoTime)
             _ (dotimes [_ 1000] (#'db/write-error database projection transaction))
             average-ms (/ (- (System/nanoTime) started) 1e9)
             evaluation
             (sci.eval/evaluate
              {:seon.cluster.eval/source source
               :seon.cluster.eval/ns [:seon.ns/name 'my.agents.juniper]
               :seon.sci.eval/ctx (:seon.sci.eval/ctx instance)
               :seon.sci.admit/caps (:seon.sci.admit/caps handle)
               :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms handle)
               :seon.config/on-core-error :record})
             shown (repl/text (assoc evaluation :seon.cluster.eval/source source
                                               :seon.ns/name 'my.agents.juniper))
             committed (db/transact! connection transaction)]
         (assert (:db-after committed) (pr-str committed))
         {:seon.test/shown shown
          :seon.test/bytes (alength (.getBytes shown "UTF-8"))
          :seon.test/refusal-ai (db/render-rejection-ai (:seon.sci.admit/value evaluation))
          :seon.test/validation-ms average-ms
          :seon.test/input-datoms (count transaction)
          :seon.test/committed-datoms (count (:tx-data committed))
          :seon.test/samples 1000
          :seon.test/amount (db/q '[:find ?v . :where
                                    [?e :example/order "a1"] [?e :example/amount ?v]]
                                  @connection)})))))
