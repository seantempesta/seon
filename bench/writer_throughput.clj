(ns bench.writer-throughput
  "File-backed Datahike and admitted-writer throughput probes.

   This is measurement only. Writer correctness remains in `test/seon/db`.

   Run with the retained writer basis:

     U3_BENCH_MODE=aprime clojure -M:writer:host:writer-test
       -i bench/writer_throughput.clj -e '(bench.writer-throughput/-main)'

     U3_BENCH_MODE=a U3_BENCH_SHAPE=beat U3_BENCH_CONCURRENCY=64
       U3_BENCH_SECONDS=30 clojure -M:writer:host:writer-test
       -i bench/writer_throughput.clj -e '(bench.writer-throughput/-main)'

     U3_BENCH_MODE=c U3_BENCH_CONCURRENCY=64 U3_BENCH_SECONDS=30
       clojure -M:writer:host:writer-test
       -i bench/writer_throughput.clj -e '(bench.writer-throughput/-main)'"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.db.executor :as executor]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.writer :as writer]
            [seon.db.writer-test-support :as writer-test])
  (:import [java.io File]
           [java.lang.management ManagementFactory]
           [java.util.concurrent ConcurrentLinkedQueue CountDownLatch
            Executors TimeUnit]))

(defn- environment-long [name default]
  (Long/parseLong (or (System/getenv name) (str default))))

(defn- delete-tree! [root]
  (when (.exists ^File root)
    (doseq [file (reverse (file-seq root))]
      (.delete ^File file))))

(defn- dependencies []
  {::writer/database-initializer (fn [_connection _database-name] nil)
   ::writer/embedding-enabled? false
   ::writer/embedding-entity-ids (fn [_db-value] [])
   ::writer/embedding-inputs-for-eids (fn [_db-value _entity-ids] [])
   ::writer/embedding-assertions (fn [_inputs] [])
   ::writer/revalidate-embedding-assertions (fn [_db-value _assertions] [])
   ::writer/query-vec (fn [_] {:seon.embed/vector [0.0]})
   ::writer/knn (fn [_db-value _vector _k _eids] [])})

(defn- capacity [maximum-active maximum-queued]
  (-> (executor/capacity 8)
      (assoc-in [::executor/classes :mutation ::executor/maximum-active]
                maximum-active)
      (assoc-in [::executor/classes :mutation ::executor/maximum-queued]
                maximum-queued)
      (assoc-in [::executor/classes :mutation
                 ::executor/maximum-queued-by-database]
                maximum-queued)))

(def schema
  [{:db/ident :writer.bench.run/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :writer.bench.run/epoch
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :writer.bench.run/beat
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :writer.bench.turn/phase
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :writer.bench.attempt/state
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :writer.bench.eval/state
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}])

(defn- ensure-database!
  [runtime database-name database-path request-id]
  (writer/handle-request
   runtime
   (protocol/ensure-database-request
    {::protocol/request-id request-id
     ::protocol/database-name database-name
     ::protocol/backend :file
     ::protocol/database-path database-path})))

(defn- transact!
  [runtime database request-id transaction-data]
  (writer/handle-request
   runtime
   (protocol/transaction-request
    {::protocol/request-id request-id
     :seon.db/db database
     ::protocol/transaction-data transaction-data})))

(defn- initialize-database!
  [runtime database-name database-path driver-count]
  (let [head (:seon.db/db
              (ensure-database! runtime database-name database-path
                                (str "bench/ensure/" database-name)))
        installed
        (transact! runtime head (str "bench/schema/" database-name) schema)
        runs
        (mapv (fn [index]
                {:writer.bench.run/id (str "driver-" index)
                 :writer.bench.run/epoch 1
                 :writer.bench.run/beat 0
                 :writer.bench.turn/phase :idle
                 :writer.bench.attempt/state :idle
                 :writer.bench.eval/state :idle})
              (range driver-count))]
    (:db-after
     (transact! runtime (:db-after installed)
                (str "bench/runs/" database-name) runs))))

(def turn-tail-phases
  [:opened :rendered :attempt-open :attempt-terminal
   :eval-one-open :eval-one-terminal :eval-two-open :eval-two-terminal
   :evaled :published :closed :beat])

(defn- transaction-data
  [shape driver-id ordinal]
  (let [entity [:writer.bench.run/id driver-id]
        fence [:db.fn/cas entity :writer.bench.run/epoch 1 1]]
    (case shape
      :beat
      [fence
       [:db/add entity :writer.bench.run/beat ordinal]]

      :turn-tail
      (let [phase (nth turn-tail-phases
                       (mod ordinal (count turn-tail-phases)))]
        [fence
         [:db/add entity :writer.bench.turn/phase phase]])

      :receipt-pair
      (if (even? ordinal)
        [fence
         [:db.fn/cas entity :writer.bench.eval/state :idle :running]]
        [fence
         [:db.fn/cas entity :writer.bench.eval/state :running :idle]]))))

(defn- percentile [sorted-values fraction]
  (when (seq sorted-values)
    (nth sorted-values
         (min (dec (count sorted-values))
              (long (Math/floor (* fraction (dec (count sorted-values)))))))))

(defn- latency-summary [latencies]
  (let [values (vec (sort (seq latencies)))
        milliseconds #(/ (double %) 1000000.0)]
    {:p50-ms (some-> (percentile values 0.50) milliseconds)
     :p95-ms (some-> (percentile values 0.95) milliseconds)
     :p99-ms (some-> (percentile values 0.99) milliseconds)}))

(defn- run-offered-load!
  [runtime databases shape concurrency-per-database seconds]
  (let [database-count (count databases)
        worker-count (* database-count concurrency-per-database)
        ready (CountDownLatch. worker-count)
        start (CountDownLatch. 1)
        done (CountDownLatch. worker-count)
        accepted (java.util.concurrent.atomic.LongAdder.)
        rejected (java.util.concurrent.atomic.LongAdder.)
        latencies (ConcurrentLinkedQueue.)
        executor-service (Executors/newVirtualThreadPerTaskExecutor)
        deadline (atom nil)]
    (doseq [worker-index (range worker-count)]
      (.submit
       executor-service
       ^Runnable
       (fn []
         (try
           (let [database-index (quot worker-index concurrency-per-database)
                 driver-index (mod worker-index concurrency-per-database)
                 database (nth databases database-index)
                 driver-id (str "driver-" driver-index)]
             (.countDown ready)
             (.await start)
             (loop [ordinal 0]
               (when (< (System/nanoTime) @deadline)
                 (let [request-id
                       (str "bench/" (name shape) "/" database-index "/"
                            driver-index "/" ordinal "/" (random-uuid))
                       started-at (System/nanoTime)
                       response
                       (transact! runtime database request-id
                                  (transaction-data shape driver-id ordinal))]
                   (.add latencies (- (System/nanoTime) started-at))
                   (if (::protocol/success? response)
                     (.increment accepted)
                     (.increment rejected))
                   (recur (inc ordinal))))))
           (finally
             (.countDown done))))))
    (when-not (.await ready 30 TimeUnit/SECONDS)
      (throw (ex-info "The offered-load workers did not become ready."
                      {:worker-count worker-count})))
    (reset! deadline (+ (System/nanoTime) (* seconds 1000000000)))
    (let [started-at (System/nanoTime)]
      (.countDown start)
      (when-not (.await done (+ seconds 60) TimeUnit/SECONDS)
        (throw (ex-info "The offered-load workers did not finish."
                        {:worker-count worker-count :seconds seconds})))
      (let [elapsed-seconds (/ (- (System/nanoTime) started-at) 1.0e9)
            accepted-count (.sum accepted)
            rejected-count (.sum rejected)]
        (.shutdown executor-service)
        (.awaitTermination executor-service 30 TimeUnit/SECONDS)
        (merge
         {:shape shape
          :databases database-count
          :concurrency-per-database concurrency-per-database
          :offered-concurrency worker-count
          :seconds elapsed-seconds
          :accepted accepted-count
          :rejected rejected-count
          :tx-per-second (/ accepted-count elapsed-seconds)
          :executor (executor/evidence (::writer/executor runtime))}
         (latency-summary latencies))))))

(defn- with-writer
  [database-count concurrency-per-database body]
  (let [{::registry/keys [snapshot]} (registry/snapshot-registry {})
        root (doto (io/file "tmp" (str "writer-throughput-" (random-uuid)))
               .mkdirs)
        socket-path (.getAbsolutePath (io/file root "writer.sock"))
        maximum-active (environment-long "U3_BENCH_MAXIMUM_ACTIVE" 32768)
        maximum-queued (environment-long "U3_BENCH_MAXIMUM_QUEUED" 32768)
        names (mapv #(str "writer-throughput-" % "-" (random-uuid))
                    (range database-count))
        paths (mapv #(.getAbsolutePath (io/file root (str "database-" %)))
                    (range database-count))
        server
        (writer-test/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name (first names)
          ::writer/backend :file
          ::writer/database-path (first paths)
          ::executor/capacity (capacity maximum-active maximum-queued)
          ::writer/request-socket-path socket-path})
        runtime (::writer/runtime server)]
    (try
      (let [databases
            (mapv #(initialize-database! runtime %1 %2 concurrency-per-database)
                  names paths)]
        (body runtime databases))
      (finally
        (writer/stop! server)
        (registry/restore-registry! {::registry/snapshot snapshot})
        (delete-tree! root)))))

(defn run-writer-probe!
  "Measure one admitted-writer workload at the selected concurrency."
  [database-count shape concurrency-per-database seconds]
  (with-writer
    database-count concurrency-per-database
    #(run-offered-load! %1 %2 shape concurrency-per-database seconds)))

(def aprime-schema
  [{:db/ident :writer.aprime/value
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}])

(defn- resource-snapshot []
  (let [runtime (Runtime/getRuntime)
        operating-system
        ^com.sun.management.OperatingSystemMXBean
        (ManagementFactory/getOperatingSystemMXBean)
        garbage-collectors (ManagementFactory/getGarbageCollectorMXBeans)
        threads (ManagementFactory/getThreadMXBean)]
    {:bench.load/process-cpu-ns (.getProcessCpuTime operating-system)
     :bench.load/heap-used-bytes (- (.totalMemory runtime)
                                    (.freeMemory runtime))
     :bench.load/gc-count
     (reduce + (keep #(let [n (.getCollectionCount %)]
                        (when-not (neg? n) n))
                     garbage-collectors))
     :bench.load/gc-ms
     (reduce + (keep #(let [n (.getCollectionTime %)]
                        (when-not (neg? n) n))
                     garbage-collectors))
     :bench.load/platform-thread-count (.getThreadCount threads)}))

(defn- snapshot-delta [before after key]
  (- (get after key) (get before key)))

(defn- median-long [values]
  (let [values (vec (sort values))]
    (nth values (quot (count values) 2))))

(defn- latency-percentiles [latencies]
  (let [values (vec (sort latencies))
        at (fn [fraction]
             (/ (nth values
                     (min (dec (count values))
                          (long (Math/floor
                                 (* fraction (dec (count values)))))))
                1.0e6))]
    {:bench.load/p50-ms (at 0.50)
     :bench.load/p95-ms (at 0.95)
     :bench.load/p99-ms (at 0.99)
     :bench.load/max-ms (/ (peek values) 1.0e6)}))

(defn- run-aprime-saturation-point!
  [concurrency transaction-count]
  (let [root (doto (io/file "tmp" (str "writer-saturation-" (random-uuid)))
               .mkdirs)
        store-path (.getAbsolutePath (io/file root "database"))
        config
        {:store {:backend :file
                 :path store-path
                 :id (random-uuid)}
         :schema-flexibility :write
         :keep-history? true
         :fuse-index-roots? true}]
    (try
      (d/create-database config)
      (let [connection (d/connect config)
            _ @(d/transact! connection {:tx-data aprime-schema})
            ready (CountDownLatch. concurrency)
            start (CountDownLatch. 1)
            done (CountDownLatch. concurrency)
            next-ordinal (java.util.concurrent.atomic.AtomicLong.)
            commit-ids (ConcurrentLinkedQueue.)
            latencies (ConcurrentLinkedQueue.)
            errors (ConcurrentLinkedQueue.)
            executor-service (Executors/newVirtualThreadPerTaskExecutor)
            thread-bean (ManagementFactory/getThreadMXBean)]
        (.resetPeakThreadCount thread-bean)
        (doseq [ordinal (range concurrency)]
          (.submit
           executor-service
           ^Runnable
           (fn []
             (try
               (.countDown ready)
               (.await start)
               (loop []
                 (let [ordinal (.getAndIncrement next-ordinal)]
                   (when (< ordinal transaction-count)
                     (let [started-at (System/nanoTime)
                           report
                           @(d/transact!
                             connection
                             {:tx-data
                              [{:writer.aprime/value (long ordinal)}]})]
                       (.add latencies (- (System/nanoTime) started-at))
                       (.add commit-ids
                             (get-in report [:tx-meta :db/commitId]))
                       (recur)))))
               (catch Throwable error
                 (.add errors error))
               (finally
                 (.countDown done))))))
        (when-not (.await ready 30 TimeUnit/SECONDS)
          (throw
           (ex-info "The saturation workers did not become ready."
                    {:bench.load/concurrency concurrency})))
        (let [before (resource-snapshot)
              started-at (System/nanoTime)]
          (.countDown start)
          (when-not (.await done 120 TimeUnit/SECONDS)
            (throw
             (ex-info "The saturation workers did not finish."
                      {:bench.load/concurrency concurrency})))
          (let [finished-at (System/nanoTime)
                after (resource-snapshot)
                elapsed-ns (- finished-at started-at)
                commit-sizes
                (->> commit-ids
                     frequencies
                     vals
                     sort
                     vec)]
            (.shutdown executor-service)
            (.awaitTermination executor-service 30 TimeUnit/SECONDS)
            (d/release connection)
            (merge
             {:bench.load/concurrency concurrency
              :bench.load/transactions transaction-count
              :bench.load/completed (count commit-ids)
              :bench.load/errors (count errors)
              :bench.load/wall-ms (/ elapsed-ns 1.0e6)
              :bench.load/ms-per-transaction
              (/ elapsed-ns 1.0e6 transaction-count)
              :bench.load/transactions-per-second
              (/ transaction-count (/ elapsed-ns 1.0e9))
              :bench.load/process-cpu-ms
              (/ (snapshot-delta before after
                                 :bench.load/process-cpu-ns)
                 1.0e6)
              :bench.load/average-process-cores
              (/ (snapshot-delta before after
                                 :bench.load/process-cpu-ns)
                 (double elapsed-ns))
              :bench.load/gc-count
              (snapshot-delta before after :bench.load/gc-count)
              :bench.load/gc-ms
              (snapshot-delta before after :bench.load/gc-ms)
              :bench.load/heap-used-delta-bytes
              (snapshot-delta before after :bench.load/heap-used-bytes)
              :bench.load/platform-threads-before
              (:bench.load/platform-thread-count before)
              :bench.load/platform-threads-after
              (:bench.load/platform-thread-count after)
              :bench.load/platform-threads-peak
              (.getPeakThreadCount thread-bean)
              :bench.load/commit-count (count commit-sizes)
              :bench.load/commit-batch-min (first commit-sizes)
              :bench.load/commit-batch-median (median-long commit-sizes)
              :bench.load/commit-batch-max (peek commit-sizes)}
             (latency-percentiles latencies)))))
      (finally
        (delete-tree! root)))))

(defn- saturation-concurrencies []
  (mapv parse-long
        (str/split
         (or (System/getenv "U3_BENCH_CONCURRENCIES")
             "1,10,50,200,400,800,1600,3200")
         #",")))

(defn run-aprime!
  "Measure direct Datahike batching at queue depths 1, 8, and 64."
  [transaction-count]
  (mapv
   (fn [depth]
     (let [root (doto (io/file "tmp" (str "writer-aprime-" (random-uuid)))
                  .mkdirs)
           store-path (.getAbsolutePath (io/file root "database"))
           config
           {:store {:backend :file
                    :path store-path
                    :id (random-uuid)}
            :schema-flexibility :write
            :keep-history? true}]
       (try
         (d/create-database config)
         (let [connection (d/connect config)]
           @(d/transact! connection {:tx-data aprime-schema})
           (let [started-at (System/nanoTime)]
             (doseq [batch (partition-all depth (range transaction-count))]
               (run! deref
                     (mapv
                      #(d/transact!
                        connection
                        {:tx-data [{:writer.aprime/value (long %)}]})
                      batch)))
             (let [elapsed-seconds
                   (/ (- (System/nanoTime) started-at) 1.0e9)]
               (d/release connection)
               {:depth depth
                :transactions transaction-count
                :seconds elapsed-seconds
                :tx-per-second (/ transaction-count elapsed-seconds)})))
         (finally
           (delete-tree! root)))))
   [1 8 64]))

(defn -main
  "Run the selected writer throughput probe and print one EDN result."
  [& _]
  (let [mode (keyword (or (System/getenv "U3_BENCH_MODE") "a"))
        shape (keyword (or (System/getenv "U3_BENCH_SHAPE") "beat"))
        concurrency (environment-long "U3_BENCH_CONCURRENCY" 64)
        seconds (environment-long "U3_BENCH_SECONDS" 30)
        result
        (case mode
          :aprime (run-aprime! (environment-long
                                "U3_BENCH_TRANSACTIONS" 1024))
          :saturation
          (let [transaction-count
                (environment-long "U3_BENCH_TRANSACTIONS" 65536)]
            {:bench.load/conditions
             {:bench.load/jdk (System/getProperty "java.version")
              :bench.load/clojure (clojure-version)
              :bench.load/max-heap-bytes (.maxMemory (Runtime/getRuntime))
              :bench.load/available-processors
              (.availableProcessors (Runtime/getRuntime))
              :bench.load/transactions-per-point transaction-count
              :bench.load/concurrencies (saturation-concurrencies)}
             :bench.load/warmup
             (run-aprime-saturation-point! 64 256)
             :bench.load/curve
             (mapv #(let [point
                          (run-aprime-saturation-point!
                           % transaction-count)]
                      (println (pr-str {:bench.load/point point}))
                      point)
                   (saturation-concurrencies))})
          :a (run-writer-probe! 1 shape concurrency seconds)
          :c (run-writer-probe! 4 shape concurrency seconds))]
    (println (pr-str result))
    (shutdown-agents)))
