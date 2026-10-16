;; Isolated JVM only: on the parent this deliberately reaches OutOfMemoryError.
;; Run with -Xmx128m -XX:+UseSerialGC and a pinned archive's classpath.
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[malli.core :as m]
         '[seon.edit :as edit])
(import '[java.lang.management ManagementFactory MemoryPoolMXBean]
        '[java.util.concurrent CountDownLatch Executors Callable TimeUnit])

(let [k (Long/parseLong (or (first *command-line-args*) "3"))
      ;; Replay the actual owner's input contract with Malli's production seam.
      ;; Output validation is irrelevant to a call that never returns on parent.
      schema [:=> (second (:malli/schema (meta #'edit/text))) :any]
      registry (merge (m/default-schemas)
                      (edn/read-string (slurp (io/resource "seon/schemas/my.edit.edn"))))
      call (m/-instrument {:schema schema :scope #{:input}} edit/text {:registry registry})
      pools (filter #(= java.lang.management.MemoryType/HEAP (.getType ^MemoryPoolMXBean %))
                    (ManagementFactory/getMemoryPoolMXBeans))
      usage (fn [] (into {} (map (fn [^MemoryPoolMXBean pool]
                                  [(.getName pool) (.getUsed (.getUsage pool))])) pools))
      ready (CountDownLatch. k)
      start (CountDownLatch. 1)
      executor (Executors/newFixedThreadPool k)]
  (System/gc)
  (doseq [^MemoryPoolMXBean pool pools] (.resetPeakUsage pool))
  (let [before (usage)
        pending (mapv (fn [_]
                        (.submit executor
                                 ^Callable
                                 (fn []
                                   (.countDown ready)
                                   (.await start)
                                   (try (call "short source" "" "replacement")
                                        :returned
                                        (catch clojure.lang.ExceptionInfo failure
                                          (:type (ex-data failure)))
                                        (catch OutOfMemoryError _ :out-of-memory)))))
                      (range k))]
    (try
      (.await ready)
      (let [began (System/nanoTime)]
        (.countDown start)
        (let [results (mapv #(.get ^java.util.concurrent.Future % 30 TimeUnit/SECONDS) pending)
              ms (/ (- (System/nanoTime) began) 1e6)
              peak (into {} (map (fn [^MemoryPoolMXBean pool]
                                  [(.getName pool) (.getUsed (.getPeakUsage pool))])) pools)]
          (System/gc)
          (prn {:k k :results results :ms ms :before before :peak peak :after-exit (usage)})))
      (finally
        (.shutdown executor)
        (.awaitTermination executor 30 TimeUnit/SECONDS)))))
