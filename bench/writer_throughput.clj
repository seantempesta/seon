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
            [datahike.api :as d]
            [seon.db.executor :as executor]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.writer :as writer]
            [seon.db.writer-test-support :as writer-test])
  (:import [java.io File]
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
          :a (run-writer-probe! 1 shape concurrency seconds)
          :c (run-writer-probe! 4 shape concurrency seconds))]
    (println (pr-str result))
    (shutdown-agents)))
