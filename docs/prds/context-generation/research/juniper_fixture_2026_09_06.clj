(ns juniper-fixture-2026-09-06
  (:require [seon.cluster]
            [seon.cluster.agent]
            [seon.config]
            [seon.db]
            [seon.eval]
            [seon.render]
            [seon.operator.runtime]
            [seon.schema]
            [seon.turn]))

; The production JVM deliberately has no test classpath. Load the same data
; and installer the loop regression requires; no separate live fixture exists.
(load-file "test/seon/context_blocks_fixture.clj")

(defn install!
  "Install the ruled order scenario in the explicitly selected cluster."
  [cluster-name]
  (let [instance (get @seon.operator.runtime/running-instances cluster-name)
        handle (:seon.turn.loop/cluster instance)]
    (seon.schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       ((resolve 'seon.context-blocks-fixture/install-running!)
        handle (:seon.agent/routing instance))))))

(defn prompt
  "Acquire the exact provider prompt for Juniper in the selected live cluster."
  [cluster-name]
  (let [handle (:seon.turn.loop/cluster
                (get @seon.operator.runtime/running-instances cluster-name))
        database (seon.db/db (:seon.db/connection handle))
        last-evaluation (last (seon.eval/of-agent database "juniper"))
        turn-id (:seon.turn/id
                 (seon.db/pull database [:seon.turn/id]
                               (get-in last-evaluation [:seon.cluster.eval/run :db/id])))]
    (seon.schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       (let [result (seon.render/acquire-context!
                     (merge handle {:seon.db/db database :seon.agent/id "juniper"
                                    :seon.turn/id turn-id
                                    :seon.sci.eval/time-limit-ms
                                    (:seon.config.eval/time-limit-ms handle)}))]
         (or (:seon.cluster.prompt/text result)
             (throw (ex-info "Juniper prompt acquisition failed" result))))))))
