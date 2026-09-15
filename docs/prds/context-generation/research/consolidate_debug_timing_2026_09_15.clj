(require 'seon.operator.runtime 'seon.sci.kernel 'seon.schema
         'seon.render.web 'seon.render.transcript)

(let [handle (:seon.turn.loop/cluster
              (get @seon.operator.runtime/running-instances "default"))
      projection (seon.sci.kernel/context-projection (:seon.sci.eval/ctx handle))]
 (seon.schema/call-with-projection projection
  (fn []
   (let [handle handle
      connection (:seon.db/connection handle)
      database @connection
      request (#'seon.render.web/session-controls
                (#'seon.render.web/debug-turn-request database connection "juniper"
                  (:seon.sci.admit/caps handle) handle))
      measure (fn [f] (let [start (System/nanoTime) value (f)]
                        [(/ (- (System/nanoTime) start) 1e6) value]))
      [rows-ms rows] (measure #(#'seon.render.transcript/turn-rows database "juniper"))
      [evaluations-ms evaluations] (measure #(#'seon.render.transcript/ledger-evaluations request))
      [summary-ms rows] (measure #(#'seon.render.transcript/ledger-rows database rows evaluations))
      [prefix-ms _] (measure #(#'seon.render.transcript/prefix-problem request rows))
      [panel-ms _] (measure #(#'seon.render.transcript/session-problems request rows evaluations))
      [ledger-ms _] (measure #(seon.render.transcript/render-ledger request))]
  {:rows-ms rows-ms :evaluations-ms evaluations-ms :summary-ms summary-ms
   :prefix-ms prefix-ms :panel-ms panel-ms :ledger-ms ledger-ms}))))
