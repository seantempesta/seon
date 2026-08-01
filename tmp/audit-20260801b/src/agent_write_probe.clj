(ns agent-write-probe
  (:require [datahike.api :as d]
            [seon.config :as config]
            [seon.render :as render]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support]))

(defn run-probe
  "Evaluate an unrestricted Datahike transaction through the production door."
  [& _]
  (test-support/with-database
    (fn [connection]
      (let [effective (config/defaults)
            caps (config/result-caps effective)
            ctx (sci.eval/fork)
            acquired (sci.eval/acquire! {:seon.sci.eval/ctx ctx
                                         :seon.db/db @connection})
            ctx (assoc ctx :seon.schema/projection
                       (:seon.schema/projection acquired))
            source
            (str "(do\n"
                 " (seon.cluster.store/transact!\n"
                 "  (deref seon.db/*conn*)\n"
                 "  [{:seon.cluster.agent/id \"audit-illicit-agent\"}])\n"
                 " :audit/committed)")
            evaluation
            (render/call-with-walk-context
             {:seon.store/branch-connection connection
              :seon.cluster.agent/id "audit-prober"
              :seon.sci.admit/caps caps}
             #(sci.eval/evaluate
               {:seon.cluster.run.form/source source
                :seon.cluster.run.form/ns [:seon.ns/name 'user]
                :seon.sci.admit/caps caps
                :seon.sci.eval/ctx ctx
                :seon.sci.eval/time-limit-ms
                (:seon.config.eval/time-limit-ms effective)
                :seon.config/on-core-error :record}))
            committed
            (d/pull @connection
                    [:seon.cluster.agent/id]
                    [:seon.cluster.agent/id "audit-illicit-agent"])]
        (prn
         {:audit/source source
          :audit/error (:seon.cluster.eval/error evaluation)
          :audit/admitted-value (:seon.sci.admit/value evaluation)
          :audit/committed committed})))))

(apply run-probe *command-line-args*)
