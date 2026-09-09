; Load through MCP JVM evaluation on default. This is a read-only final
; verification; it does not stop, refork, or restart the cluster.
(let [instance (get @seon.operator.runtime/running-instances "default")
      cluster (:seon.cluster.loop/cluster instance)
      database @(:seon.db/connection cluster)]
  (seon.schema/call-with-projection-state
   (:seon.sci.eval/projection-state cluster)
   (fn []
     {:components.verify/agent-keys
      (sort (keys (seon.db/pull database '[*]
                                [:seon.cluster.agent/id "juniper"])))
      :components.verify/retired-datoms
      (seon.db/q '[:find ?attribute :in $ [?attribute ...]
                   :where [?agent :seon.cluster.agent/id "juniper"]
                          [?agent ?attribute _]]
                 database
                 [:seon.cluster.agent/cluster :seon.cluster.agent/instructions
                  :seon.cluster.agent/run :seon.cluster.agent/toolkit
                  :seon.cluster.agent/context-links])})))
