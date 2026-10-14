;; Submit the let form via Seon MCP eval_clj, root/default, mode jvm.
;; These namespaces are already loaded on default. Create tmp/gc-allocation first.
(require '[seon.cluster.boot] '[seon.profile] '[seon.maintenance])

(let [conn (seon.cluster.boot/connection "default")
      basis (get-in @conn [:meta :datahike/commit-id])
      recording (jdk.jfr.Recording.)]
  (try
    (doto (.enable recording "jdk.ObjectAllocationSample") (.withStackTrace) (.with "throttle" "300/s"))
    (doto (.enable recording "jdk.ExecutionSample") (.withPeriod (java.time.Duration/ofMillis 10)) (.withStackTrace))
    (.start recording)
    (let [started (str (java.time.Instant/now))
          measured (seon.profile/measure
                     #(seon.maintenance/collect!
                        {:seon.operator/repository-root "/Users/sean/src/seon"
                         :seon.operator/managed-root "/Users/sean/src/seon"
                         :seon.config.maintenance/collect? true
                         :seon.operator.collect/dry-run? true}))]
      (.stop recording)
      (.dump recording (java.nio.file.Paths/get "/Users/sean/src/seon/tmp/gc-allocation/dry-run.jfr" (make-array String 0)))
      {:basis basis :started started :ended (str (java.time.Instant/now))
       :measurement (dissoc measured :seon.profile/called :seon.profile/ranked-by-calls)})
    (finally (.close recording))))
