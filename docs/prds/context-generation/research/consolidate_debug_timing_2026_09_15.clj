(require 'seon.operator 'seon.operator.runtime 'seon.render.web 'seon.render.transcript)

(let [handle (:seon.turn.loop/cluster
              (get @seon.operator.runtime/running-instances "default"))
      connection (seon.operator/connection "default")
      database (seon.db/db connection)
      request (#'seon.render.web/session-controls
                (#'seon.render.web/debug-turn-request database connection "juniper"
                  (:seon.sci.admit/caps handle) handle))
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      thread-id (.threadId (Thread/currentThread))
      measure (fn [f]
                (let [allocated (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean thread-id)
                      start (System/nanoTime)
                      value (f)]
                  [{:ms (/ (- (System/nanoTime) start) 1e6)
                    :allocated-mb (/ (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean thread-id) allocated) 1e6)} value]))
      ;; Warm the complete ledger at this exact carried basis before measuring.
      _ (seon.render.hiccup/->string (seon.render.transcript/render-ledger request))
      [rows-cost rows] (measure #(#'seon.render.transcript/turn-rows database "juniper"))
      [evaluations-cost acquired] (measure #(#'seon.render.transcript/ledger-acquisition request))
      data (:seon.render.transcript/ledger-data acquired)
      evaluations (:seon.render.transcript/evaluations data)
      body-request (assoc request :seon.render.transcript/calibrations (:seon.render.transcript/calibrations data))
      [summary-cost _] (measure #(#'seon.render.transcript/ledger-rows database rows evaluations))
      rows (:seon.render.transcript/rows data)
      [prefix-cost _] (measure #(#'seon.render.transcript/prefix-problem request rows))
      [directory-cost _] (measure #(#'seon.render.transcript/directory-problem database {} []))
      [budget-cost _] (measure #(#'seon.render.transcript/session-budget database "juniper" rows))
      [panel-cost _] (measure #(#'seon.render.transcript/session-problems request rows evaluations))
      [stories-cost _] (measure #(mapv (partial #'seon.render.transcript/turn-story request evaluations) rows))
      [bodies-cost _] (measure #(mapv (fn [row] (seon.render.hiccup/->string
                                                (#'seon.render.transcript/ledger-turn-body body-request rows evaluations row)))
                                    (take-last 3 rows)))
      [ledger-cost _] (measure #(seon.render.hiccup/->string (seon.render.transcript/render-ledger request)))]
  (println {:basis (:max-tx database) :turns (count rows)
            :rows rows-cost :evaluations evaluations-cost :summaries summary-cost
            :prefix prefix-cost :directory directory-cost :budget budget-cost :problems panel-cost :stories stories-cost
            :bodies bodies-cost :ledger ledger-cost}))
