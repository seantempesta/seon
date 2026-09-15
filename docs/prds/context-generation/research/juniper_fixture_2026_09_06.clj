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
  "Install the ruled order scenario in the explicitly selected cluster.

  The one-argument form keeps provider calls disabled (virtual turns). A
  live provider run passes `#(dissoc % :seon.config.ai/no-provider)`."
  ([cluster-name] (install! cluster-name identity))
  ([cluster-name settings-fn]
   (let [instance (get @seon.operator.runtime/running-instances cluster-name)
         handle (:seon.turn.loop/cluster instance)]
     (seon.schema/call-with-projection-state
      (:seon.sci.eval/projection-state handle)
      (fn []
        ((resolve 'seon.context-blocks-fixture/install-running!)
         handle (:seon.agent/routing instance) settings-fn))))))

(defn prompt
  "Acquire Juniper's current context, including its latest evaluation results."
  [cluster-name]
  (let [handle (:seon.turn.loop/cluster
                (get @seon.operator.runtime/running-instances cluster-name))
        database (seon.db/db (:seon.db/connection handle))]
    (seon.schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       (let [result (seon.render/acquire-context!
                     (merge handle {:seon.db/db database :seon.agent/id "juniper"
                                    :seon.schema/projection
                                    (:seon.schema/projection @(:seon.sci.eval/projection-state handle))
                                    :seon.sci.eval/time-limit-ms
                                    (:seon.config.eval/time-limit-ms handle)}))]
         (or (:seon.cluster.prompt/text result)
             (throw (ex-info "Juniper prompt acquisition failed" result))))))))
