(require '[seon.operator.runtime]
         '[seon.operator]
         '[seon.schema]
         '[seon.db]
         '[seon.turn]
         '[seon.cluster.wake])

; Load this file through MCP JVM mode on default.
(let [instance (get @seon.operator.runtime/running-instances "default")
      handle (:seon.turn.loop/cluster instance)]
  (seon.schema/call-with-projection-state
   (:seon.sci.eval/projection-state handle)
   (fn []
     (let [database (seon.db/db (seon.operator/connection "default"))
           result (seon.turn/preview-sources
                   (merge handle {:seon.turn.loop/cluster handle
                                  :seon.db/db database
                                  :seon.agent/id "juniper"
                                  :seon.ns/name 'my.agents.juniper
                                  :seon.cluster.reply/text "(seon.plan/plan {})"}))
           evaluation (get-in result [:seon.turn.loop/evaluated-sources 0
                                      :seon.sci.eval/evaluation])
           inert (seon.cluster.wake/inert-attributes database)
           evidence (:seon.cluster.eval/read-evidence evaluation)]
       {:seon.probe/read-count (count evidence)
        :seon.probe/requests (mapv :seon.db/read-request evidence)
        :seon.probe/inert inert
        :seon.probe/fault
        (#'seon.turn/generated-read-fault
         database {:seon.cluster.eval/source "(seon.plan/plan {})"} evaluation)}))))
