(ns turn-schema-fresh-probe-2026-09-09
  (:require [seon.cluster] [seon.config] [seon.db]
            [seon.operator.runtime] [seon.schema]))

; Load through MCP JVM mode on the isolated turn-schema root only.
(let [instance (get @seon.operator.runtime/running-instances "turn-schema")
      handle (:seon.turn.loop/cluster instance)
      connection (:seon.db/connection handle)]
  (seon.schema/call-with-projection-state
   (:seon.sci.eval/projection-state handle)
   (fn []
     (assert (true? (:seon.config.ai/no-provider
                     (seon.config/effective @connection "turn-schema"))))
     (let [prepared
           (seon.db/transact!
            connection
            [[:db.fn/call #'seon.cluster/ensure-entity-call
              (:seon.db.process/id handle) (java.util.Date.)
              {:seon.cluster.agent/id "juniper"
               :seon.cluster/name "turn-schema"
               :seon.ns/name 'my.agents.juniper}]
             {:seon.cluster.agent/id "juniper"
              :seon.agent/settings {:seon.config.ai/no-provider true}}])]
       (when (:seon.error/kind prepared)
         (throw (ex-info "Settings preparation refused." prepared)))
       (load-file "/Users/sean/src/seon/docs/prds/context-generation/research/juniper_fixture_2026_09_06.clj")
       (let [result ((resolve 'juniper-fixture-2026-09-06/install!) "turn-schema")
             database @connection]
         {:seon.test/seeded (not (:seon.error/kind result))
          :seon.test/no-provider
          (seon.db/q '[:find ?value . :where
                       [?agent :seon.cluster.agent/id "juniper"]
                       [?agent :seon.agent/settings ?settings]
                       [?settings :seon.config.ai/no-provider ?value]] database)
          :seon.test/attempts
          (count (seon.db/q '[:find [?attempt ...] :where
                              [?attempt :seon.ai.attempt/id]] database))
          :seon.test/faults
          (count (seon.db/q '[:find [?fault ...] :where
                              [?fault :seon.error/id]] database))})))))
