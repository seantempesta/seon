; Exact successful MCP JVM forms from the retention-sweep session on default.
; Evaluate the startup forms separately and collect only after completion.
; No collector permit or deletion is requested by these observations.

(require 'seon.operator 'seon.db 'seon.blob.retention
         'seon.cluster.registry 'konserve.core 'datahike.gc-guard)

(let [c (seon.operator/connection "default") database (seon.db/db c)]
  {:pid (.pid (java.lang.ProcessHandle/current))
   :blob-attributes
   (seon.db/q database
              '[:find ?a :where [_ :db/ident ?a]
                [(namespace ?a) ?n] [(= ?n "seon.blob")]])
   :budget
   (seon.db/q database
              '[:find ?n . :where [_ :seon.config.blob/max-bytes ?n]])
   :retention-task
   (seon.db/pull database '[*]
                 [:seon.schedule.task/id "root/maintenance/blob-retention"])})

(do
  (def retention-sweep-measurement
    (future
      (let [c (seon.operator/connection "default")
            store (:store @c)
            t (System/nanoTime)
            blobs (#'seon.blob.retention/inventory store)
            inventory-ms (/ (- (System/nanoTime) t) 1e6)
            branches (konserve.core/get store :branches nil {:sync? true})
            t (System/nanoTime)
            refs (seon.cluster.registry/referenced-blobs c branches false)]
        {:inventory-ms inventory-ms
         :blobs (count blobs)
         :bytes (reduce + 0 (map :seon.blob/size blobs))
         :branches branches
         :referenced (count refs)
         :reference-ms (/ (- (System/nanoTime) t) 1e6)})))
  :started)

(do
  (def retention-sweep-gate-sample
    (future
      (let [sid (get-in @(seon.operator/connection "default")
                        [:config :store :id])
            start (System/nanoTime)
            until (+ start 60000000000)]
        (loop [counts {}]
          (if (< (System/nanoTime) until)
            (let [p (datahike.gc-guard/try-reachability-permit! sid :blob)
                  verdict (or (:seon.error/kind p) :admitted)]
              (when (= verdict :admitted)
                (datahike.gc-guard/release-reachability-permit! p))
              (Thread/sleep 500)
              (recur (update counts verdict (fnil inc 0))))
            {:counts counts
             :elapsed-ms (/ (- (System/nanoTime) start) 1e6)})))))
  :started)

{:measurement (if (realized? retention-sweep-measurement)
                @retention-sweep-measurement :running)
 :gate-sample (if (realized? retention-sweep-gate-sample)
                @retention-sweep-gate-sample :running)}
