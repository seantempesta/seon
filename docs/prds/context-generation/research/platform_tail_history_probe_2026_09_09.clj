(ns platform-tail-history-probe-2026-09-09
  (:require [seon.operator.runtime] [seon.schema] [seon.db]
            [seon.render.walk] [seon.config]))

; Read-only MCP JVM probe on the owned scratch cluster.
(let [instance (get @seon.operator.runtime/running-instances "platform-tail")
      handle (:seon.turn.loop/cluster instance)
      connection (:seon.db/connection handle)]
  (seon.schema/call-with-projection-state
   (:seon.sci.eval/projection-state handle)
   (fn []
     (let [database @connection
           agent-row (seon.db/pull database
                                  '[:db/id {:seon.agent/plan [:db/id]}]
                                  [:seon.cluster.agent/id "juniper"])
           agent-eid (:db/id agent-row)
           plan-eid (get-in agent-row [:seon.agent/plan :db/id])
           request {:seon.db/db database
                    :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                    :seon.render.walk/lookup agent-eid
                    :seon.sci.admit/caps (:seon.sci.admit/caps handle)
                    :seon.sci.eval/time-limit-ms 5000
                    :seon.config/on-core-error :record}
           history (seon.render.walk/history request)
           by-ref (seon.render.walk/history
                   (assoc request :seon.render.walk/lookup
                          [:seon.cluster.agent/id "juniper"]))]
       (assert (instance? Long agent-eid))
       (assert (instance? Long plan-eid))
       (assert (vector? history))
       (assert (seq history))
       (assert (= history by-ref))
       {:seon.test/agent-eid agent-eid
        :seon.test/plan-eid plan-eid
        :seon.test/entries (count history)
        :seon.test/same-history true
        :seon.test/utf8-bytes
        (count (.getBytes (apply str (map :seon.render.history/bytes history))
                          java.nio.charset.StandardCharsets/UTF_8))
        :seon.test/no-provider
        (:seon.config.ai/no-provider (seon.config/effective database "platform-tail"))
        :seon.test/provider-attempts
        (count (seon.db/q '[:find [?a ...] :where [?a :seon.ai.attempt/id]] database))}))))
