(ns turn-custody-fresh-probe-2026-09-09
  (:require [seon.cluster]
            [seon.db]
            [seon.operator.runtime]
            [seon.schema]))

; Run only in the isolated custody cluster, booted with the Juniper config.
; Establish the settings component in the creation transaction, before
; installing the sample plan and messages. No credential value is printed.
(let [instance (get @seon.operator.runtime/running-instances "custody")
      handle (:seon.cluster.loop/cluster instance)
      connection (:seon.db/connection handle)
      process (:seon.db.process/id handle)
      quiet-variable "SEON_DESIGN_LAB_NO_CREDENTIAL"]
  (when (System/getenv quiet-variable)
    (throw (ex-info "The scratch credential variable must be unset." {})))
  (seon.schema/call-with-projection-state
   (:seon.sci.eval/projection-state handle)
   (fn []
     (let [prepared
           (seon.db/transact!
            connection
            {:tx-data
             [[:db.fn/call #'seon.cluster/ensure-entity-call
               process (java.util.Date.)
               {:seon.cluster.agent/id "juniper"
                :seon.cluster/name "custody"
                :seon.ns/name 'my.agents.juniper}]
              {:seon.cluster.agent/id "juniper"
               :seon.agent/settings
               {:seon.config.ai/api-key-variable quiet-variable}}]})]
       (when (:seon.error/kind prepared)
         (throw (ex-info "Scratch settings preparation refused." prepared)))
       (load-file "/Users/sean/src/seon/docs/prds/context-generation/research/juniper_fixture_2026_09_06.clj")
       (let [result ((resolve 'juniper-fixture-2026-09-06/install!) "custody")
             database @connection]
         {:seon.test/seeded (not (:seon.error/kind result))
          :seon.test/settings-variable
          (seon.db/q '[:find ?variable . :where
                       [?agent :seon.cluster.agent/id "juniper"]
                       [?agent :seon.agent/settings ?settings]
                       [?settings :seon.config.ai/api-key-variable ?variable]]
                     database)
          :seon.test/provider-usage-rows
          (count (seon.db/q '[:find [?attempt ...] :where
                              [?attempt :seon.ai.attempt/usage-edn]] database))
          :seon.test/plan-present
          (boolean (seon.db/q '[:find ?plan . :where
                                [?agent :seon.cluster.agent/id "juniper"]
                                [?agent :seon.agent/plan ?plan]] database))})))))
