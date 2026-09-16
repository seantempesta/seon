; Dated MCP JVM proof: default PID 53378, 2026-09-16.
; Run forms separately; collect the future only after it has completed.
(require 'seon.operator 'seon.db 'seon.schedule 'seon.test
         'konserve.core 'datahike.gc-guard)

(seon.db/transact!
 (seon.operator/connection "default")
 [[:db/retract
   [:seon.schedule.task/id "root/maintenance/blob-retention"]
   :seon.schedule.task/schedule
   [:seon.schedule/id "root/maintenance/blob-retention-schedule"]]])

(do (def retention-after (future
 (let [c (seon.operator/connection "default") sid (get-in @c [:config :store :id])
       original @#'konserve.core/keys calls (atom 0)
       wrapper (fn [& args]
                 (when (some #(.startsWith (.getClassName ^StackTraceElement %) "seon.schedule$")
                             (.getStackTrace (Thread/currentThread)))
                   (swap! calls inc))
                 (apply original args))
       fires (fn [] (seon.db/q (seon.db/db c)
                    '[:find (count ?f) . :where
                      [?t :seon.schedule.task/id "root/maintenance/blob-retention"]
                      [?f :seon.schedule.fire/task ?t]]))
       before (fires) start (System/nanoTime) until (+ start 90000000000)]
   (alter-var-root #'konserve.core/keys (constantly wrapper))
   (try
     (loop [counts {}]
       (if (< (System/nanoTime) until)
         (let [p (datahike.gc-guard/try-reachability-permit! sid :blob)
               verdict (or (:seon.error/kind p) :admitted)]
           (when (= verdict :admitted) (datahike.gc-guard/release-reachability-permit! p))
           (Thread/sleep 500)
           (recur (update counts verdict (fnil inc 0))))
         {:counts counts :elapsed-ms (/ (- (System/nanoTime) start) 1e6)
          :scheduler-key-enumerations @calls :fires-before before :fires-after (fires)}))
     (finally (alter-var-root #'konserve.core/keys
                (fn [current] (if (identical? current wrapper) original current))))))))
 :started)

(if (realized? retention-after) @retention-after :running)

; Namespace reload uses the canonical loader. During the observed adoption
; delay, reload of the whole schedule-test namespace refused at its unchanged
; config initialization. The changed test was then loaded from its exact
; source form using this same loader (recorded in the landing note).
(#'seon.test/with-test-loader #(require 'seon.schedule-test :reload))
(def retention-seed-after
  (future
    (seon.test/run
     (#'seon.test/resolve-test
      'seon.schedule-test/root-maintenance-seed-is-complete-and-has-no-minute-task)
     (seon.operator/connection "default"))))

(#'seon.test/with-test-loader #(require 'seon.cluster.registry-test :reload))
(def retention-gc-tests
  (future
    (mapv
     (fn [sym]
       (seon.test/run (#'seon.test/resolve-test sym)
                      (seon.operator/connection "default")))
     '[seon.cluster.registry-test/blob-lifetime-follows-schema-derived-history-reachability
       seon.cluster.registry-test/non-temporal-collection-marks-current-blob-references])))

{:seed (if (realized? retention-seed-after) @retention-seed-after :running)
 :gc (if (realized? retention-gc-tests) @retention-gc-tests :running)}

