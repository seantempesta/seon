(ns db-read-probe
  (:require [datahike.api :as d]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support]))

(defn run-probe
  "Exercise ambient q, flat pull refusal, and admission-bounded results."
  [& _]
  (test-support/with-database
    (fn [connection]
      (d/transact connection
                  (mapv (fn [index]
                          {:seon.cluster.agent/id (str "audit-query-" index)})
                        (range 20)))
      (let [effective (config/defaults)
            caps (assoc (config/result-caps effective)
                        :seon.config.eval.result/max-collection 4)
            ctx (sci.eval/fork)
            acquired (sci.eval/acquire! {:seon.sci.eval/ctx ctx
                                         :seon.db/db @connection})
            ctx (assoc ctx :seon.schema/projection
                       (:seon.schema/projection acquired))
            evaluation
            (render/call-with-walk-context
             {:seon.store/branch-connection connection
              :seon.cluster.agent/id "audit-prober"
              :seon.sci.admit/caps caps}
             #(sci.eval/evaluate
               {:seon.cluster.run.form/source
                "(seon.db/q '[:find ?id :where [?e :seon.cluster.agent/id ?id]])"
                :seon.cluster.run.form/ns [:seon.ns/name 'user]
                :seon.sci.admit/caps caps
                :seon.sci.eval/ctx ctx
                :seon.sci.eval/time-limit-ms
                (:seon.config.eval/time-limit-ms effective)
                :seon.config/on-core-error :record}))
            refusal (binding [db/*conn* connection]
                      (db/pull [:seon.cluster.agent/id] "not-an-eid"))]
        (prn {:audit/query-error (:seon.cluster.eval/error evaluation)
              :audit/query-capped? (:seon.sci.admit/capped? evaluation)
              :audit/query-result-edn (:seon.cluster.eval/result-edn evaluation)
              :audit/pull-refusal-kind (:seon.error/kind refusal)
              :audit/pull-refusal-data (:seon.error/data refusal)})))))

(apply run-probe *command-line-args*)
