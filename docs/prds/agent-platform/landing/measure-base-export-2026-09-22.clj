;; Run from a HEAD-plus-owned-paths snapshot with -M:test and the canonical
;; -Dseon.test.published-base supplied by bin/test-fast. This is the child's
;; in-process preparation proof, not the orchestrator's cold gate.
(require '[clojure.core.server :as server]
         '[clojure.java.io :as io]
         '[seon.cluster.export]
         '[seon.fs :as fs]
         '[seon.id :as id]
         '[seon.operator :as operator]
         '[seon.schema :as schema]
         '[seon.test.arm :as arm]
         '[seon.test.cache :as cache]
         '[seon.test-support :as support])
(let [root (.getCanonicalPath (io/file "tmp" (str "base-export-proof-" (id/id))))
      destination (str root "/prepared")
      name (str "base-export-proof-" (id/id))
      arming (#'arm/initialize-contracts! "base-export-proof" [])]
  (.mkdirs (io/file root))
  (try
    (schema/call-with-projection
     (:seon.test.runner/projection arming)
     (fn []
       (support/populate-published-operator-root!
        root {:seon.test/fixture-observation
              "Prove the preparation child's real publication/export request on an independent canonical store."})
       (let [socket (server/start-server {:name name :address "127.0.0.1" :port 0
                                          :accept 'clojure.core.server/io-prepl})
             endpoint (merge (operator/identity-of (java.lang.ProcessHandle/current))
                             {:seon.boot/cluster-name "proof"
                              :seon.boot/prepl-host "127.0.0.1"
                              :seon.boot/prepl-port (.getLocalPort socket)})
             advertisement (io/file root "data/clusters/proof/prepl.edn")]
         (io/make-parents advertisement)
         (spit advertisement (pr-str endpoint))
         (try
           (let [started (System/nanoTime)
                 result (cache/prepare-base! root "." destination {})
                 elapsed (/ (- (System/nanoTime) started) 1000000.0)
                 heap (.getHeapMemoryUsage (java.lang.management.ManagementFactory/getMemoryMXBean))]
             (assert (= destination result))
             (assert (.isDirectory (io/file destination "data/store")))
             (assert (.isFile (io/file destination "manifest.edn")))
             (prn {:seon.proof/form '(seon.test.cache/prepare-base! root "." destination {})
                   :seon.proof/root root :seon.proof/elapsed-ms elapsed
                   :seon.proof/heap-used (.getUsed heap)
                   :seon.proof/heap-committed (.getCommitted heap)
                   :seon.proof/result result}))
           (finally (server/stop-server name))))))
    (finally (fs/delete-recursively! root root))))
(shutdown-agents)
