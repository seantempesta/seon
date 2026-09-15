(ns fixtures-events-probe-2026-09-15
  (:require [clojure.core.async]
            [seon.operator]
            [seon.schema]
            [seon.cluster]
            [seon.turn]
            [seon.config]
            [seon.render]
            [seon.render.value]
            [seon.ai.tokens]
            [seon.oversight]
            [seon.db]))

(let [connection (seon.operator/connection "default")
      database @connection
      projection (seon.schema/projection-from-database database)]
  (seon.schema/call-with-projection
   projection
   (fn []
     (let [requirements (#'seon.cluster/activation-requirements database)
           configuration (seon.config/effective database "default")]
       {:probe/basis (:max-tx database)
        :probe/activation-counts
        (into {} (map (fn [[k v]] [k (count v)]))
              (dissoc requirements :seon.activation/projection))
        :probe/catalog-count (count (:seon.schema.projection/catalog projection))
        :probe/ping-timeout-ms (:seon.config.flow/ping-timeout-ms configuration)
        :probe/missing-ping (seon.oversight/proc-ping :probe/absent nil)
        :probe/message-count
        (seon.db/q '[:find (count ?m) . :where [?m :seon.message/id]] database)
        :probe/attempt-bearing-turns
        (seon.db/q '[:find (count ?turn) .
                     :where [?turn :seon.turn/attempts]] database)
        :probe/idle-report-schema
        (get-in projection [:seon.schema.projection/forms
                            :seon.turn.loop/pass-report])
        :probe/armed-retains-started?
        (boolean
         (some #{:seon.flow/started}
               (tree-seq coll? seq
                         (get-in projection
                                 [:seon.schema.projection/forms
                                  :seon.agent/armed]))))
        :probe/loaded-step-declares-pass-report?
        (boolean
         (some #{:seon.turn.loop/pass-report}
               (tree-seq coll? seq (:malli/schema (meta #'seon.turn/step)))))
        :probe/old-ordinal-installed?
        (boolean (get (:schema database) :seon.cluster.message/ordinal))}))))

(let [connection (seon.operator/connection "default")
      database @connection
      projection (seon.schema/projection-from-database database)]
  (seon.schema/call-with-projection
   projection
   (fn []
     (let [profile (seon.render/agent-render-profile
                    (seon.config/effective database "default"))
           shown (seon.render.value/render-ai
                  {:seon.db/db database
                   :seon.render/value (vec (range 40))
                   :seon.render/profile profile
                   :seon.render.call/id [:fixtures-events/value-probe]
                   :seon.repl/handle 'result/fixture-probe})]
       {:probe/profile profile
        :probe/subject-count 40
        :probe/shown shown
        :probe/tokens (when (string? shown) (seon.ai.tokens/estimate shown))}))))

; Disposable observer only: no default routing entry or database is changed.
(with-open [executor (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)]
  (let [state (atom nil)
        faults (clojure.core.async/chan 1)
        observer (#'seon.turn/arm-turn-completion-backstop!
                  {:seon.agent/executor executor
                   :seon.agent/timeout-ms 20000
                   :seon.agent/backstop-state state
                   :seon.agent/fault-channel faults
                   :seon.agent/agent-id "disposable-cancel-probe"
                   :seon.agent/run-id (atom nil)})
        completion (:seon.agent/failure-channel observer)]
    (try
      (clojure.core.async/offer! (:seon.agent/cancel observer) :seon.agent/completed)
      (let [[value selected]
            (clojure.core.async/alts!!
             [completion (clojure.core.async/timeout 20000)])]
        {:probe/cancel-published? (identical? completion selected)
         :probe/closed-value? (nil? value)
         :probe/observer-cleared? (nil? @state)
         :probe/fault? (some? (clojure.core.async/poll! faults))})
      (finally
        (clojure.core.async/offer! (:seon.agent/cancel observer) :seon.agent/completed)
        (clojure.core.async/close! faults)))))
