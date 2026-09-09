(ns turn-namespace-probe-2026-09-09
  (:require [seon.cluster.source]
            [seon.db]
            [seon.eval]
            [seon.operator.runtime]))

; Read-only evidence after the settings-first Juniper fixture in the owned root.
(let [instance (get @seon.operator.runtime/running-instances "custody")
      connection (get-in instance [:seon.cluster.loop/cluster :seon.db/connection])
      database @connection
      evaluations (seon.eval/of-agent database "juniper")
      attributes (seon.db/q '[:find [?attribute ...]
                              :where [_ :db/ident ?attribute]] database)]
  {:seon.test/published-source
   (select-keys (seon.cluster.source/current (:seon.store/store instance))
                [:seon.source/commit-id])
   :seon.test/attempts-schema
   (seon.db/pull database [:db/cardinality :db/valueType :db/isComponent]
                 [:db/ident :seon.turn/attempts])
   :seon.test/retired-turn-attributes
   (filterv #(= "seon.cluster.run" (namespace %)) attributes)
   :seon.test/retired-attempt-reference
   (boolean (some #{:seon.ai.attempt/run} attributes))
   :seon.test/evaluations-vector (vector? evaluations)
   :seon.test/evaluation-count (count evaluations)
   :seon.test/provider-usage-rows
   (count (seon.db/q '[:find [?attempt ...]
                       :where [?attempt :seon.ai.attempt/usage-edn]] database))})
