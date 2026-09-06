;; Run through the selected root's MCP JVM evaluation surface.
;; The named cluster must be freshly forked from the publication being proved.
(require 'seon.operator 'seon.operator.runtime 'seon.sci.eval 'seon.env)

(let [instance (get @seon.operator.runtime/running-instances "juniper-context")
      connection (seon.operator/connection "juniper-context")
      database @connection
      ctx (seon.sci.eval/fork-candidate-ctx
           {:seon.sci.eval/ctx (:seon.sci.eval/ctx instance)
            :seon.db/db database
            :seon.db/connection connection
            :seon.cluster.agent/id "juniper"})
      environment (seon.env/of ctx)
      result
      (seon.sci.eval/evaluate
       {:seon.sci.eval/ctx ctx
        :seon.cluster.agent/id "juniper"
        :seon.cluster.run.form/source
        "[(select-keys (my.plan/plan \"juniper\") [:seon.cluster.agent/id :seon.error/kind]) (select-keys (my.plan/plan) [:seon.cluster.agent/id :seon.error/kind]) (select-keys (my.plan/plan (seon.db/db)) [:seon.cluster.agent/id :seon.error/kind]) (select-keys (my.plan/plan (seon.db/db) \"juniper\") [:seon.cluster.agent/id :seon.error/kind])]"
        :seon.sci.eval/time-limit-ms 10000
        :seon.sci.admit/caps (:seon.sci.admit/caps environment)
        :seon.config/on-core-error :record})]
  {:seon.proof/value (:seon.sci.admit/value result)
   :seon.proof/failed? (contains? result :seon.cluster.eval/error)
   :seon.proof/interrupted? (contains? result :seon.cluster.eval/interrupted-at)})
