(require '[clojure.edn :as edn]
         '[seon.operator :as operator])
(let [[root phase] *command-line-args*
      advertisement (edn/read-string (slurp (str root "/data/clusters/head/prepl.edn")))
      form
      `(let [instance# (get @seon.operator.runtime/running-instances "head")
             store# (:seon.store/store instance#)
             connection# (:seon.store/connection-object store#)
             began# (System/nanoTime)
             collected# (when (= ~phase "sweep")
                          (seon.cluster.registry/collect!
                           store# (seon.cluster.registry/retention-cutoff store#) {}))
             keys# (konserve.core/keys (:store @connection#) {:sync? true})]
         {:phase ~phase
          :bytes (:seon.operator.footprint/file-bytes
                  (seon.fs/footprint (:seon.store/dir store#)))
          :logical-keys (count keys#)
          :heap-used-bytes (- (.totalMemory (Runtime/getRuntime))
                              (.freeMemory (Runtime/getRuntime)))
          :swept (:seon.cluster.registry/swept collected#)
          :ms (/ (- (System/nanoTime) began#) 1e6)})]
  (prn (operator/prepl-value! advertisement (pr-str form) 600000)))
