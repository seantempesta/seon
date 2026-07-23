(ns seon.db.writer-mutation-recovery-test
  "Process-death recovery for pipelined file-backed mutations."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.db.protocol :as protocol]
            [seon.db.writer-test-support :as writer-test])
  (:import [java.io File]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn- delete-tree! [root]
  (when (.exists ^File root)
    (doseq [file (reverse (file-seq root))]
      (.delete ^File file))))

(defn- wait-for!
  [predicate timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (cond
        (predicate) true
        (< (System/nanoTime) deadline) (do (Thread/sleep 10) (recur))
        :else false))))

(defn- writer-ready?
  [socket-path]
  (try
    (let [session (writer-test/open-session! socket-path)]
      (writer-test/close-session! session)
      true)
    (catch Throwable _
      false)))

(defn- start-writer-process!
  [database-name database-path socket-path log-path]
  (let [java (io/file (System/getProperty "java.home") "bin" "java")
        command [(str java)
                 "--add-modules" "jdk.incubator.vector"
                 "-cp" (System/getProperty "java.class.path")
                 "clojure.main" "-m" "seon.db.writer-crash-fixture"
                 database-name database-path socket-path]
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.redirectErrorStream true)
                  (.redirectOutput (io/file log-path)))
        process (.start builder)]
    (when-not
     (wait-for! #(and (.exists (io/file socket-path))
                      (writer-ready? socket-path))
                15000)
      (.destroyForcibly process)
      (.waitFor process 5 TimeUnit/SECONDS)
      (throw
       (ex-info "The crash-test writer did not become ready."
                {:log (when (.exists (io/file log-path))
                        (slurp log-path))})))
    process))

(defn- stop-process!
  [^Process process]
  (when (.isAlive process)
    (.destroyForcibly process)
    (.waitFor process 10 TimeUnit/SECONDS)))

(defn- acquire!
  [session database-name request-id]
  (writer-test/call!
   session
   (protocol/acquire-database-request
    {::protocol/request-id request-id
     ::protocol/database-name database-name})))

(defn- transact!
  [session database request-id transaction-data]
  (writer-test/call!
   session
   (protocol/transaction-request
    {::protocol/request-id request-id
     :seon.db/db database
     ::protocol/transaction-data transaction-data})))

(defn- query!
  [session database request-id query-form arguments]
  (writer-test/call!
   session
   (protocol/query-request
    {::protocol/request-id request-id
     :seon.db/db database
     ::protocol/query-form query-form
     ::protocol/arguments arguments})))

(deftest crash-with-pipelined-mutations-replays-each-request-once
  (let [root (doto (io/file "tmp" (str "writer-crash-" (random-uuid)))
               .mkdirs)
        database-path (.getAbsolutePath (io/file root "database"))
        socket-path (.getAbsolutePath (io/file root "writer.sock"))
        first-log (.getAbsolutePath (io/file root "writer-first.log"))
        second-log (.getAbsolutePath (io/file root "writer-second.log"))
        database-name (str "writer-crash-" (random-uuid))
        request-count 64
        request-ids (mapv #(str "crash/mutation/" %) (range request-count))
        started (CountDownLatch. request-count)
        initial-outcomes (atom [])
        first-process
        (start-writer-process! database-name database-path socket-path first-log)]
    (try
      (let [sessions (mapv (fn [_] (writer-test/open-session! socket-path))
                           (range request-count))]
        (try
          (let [acquisitions
                (mapv (fn [index session]
                        (acquire! session database-name
                                  (str "crash/acquire/" index)))
                      (range)
                      sessions)
                database (:seon.db/db (first acquisitions))
                schema-response
                (transact!
                 (first sessions) database "crash/schema"
                 [{:db/ident :writer.crash/id
                   :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one
                   :db/unique :db.unique/identity}])
                database (:db-after schema-response)
                attempts
                (mapv
                 (fn [index request-id]
                   (future
                     (.countDown started)
                     (try
                       (transact!
                        (nth sessions index)
                        database request-id
                        [{:writer.crash/id request-id}])
                       (catch Throwable throwable throwable))))
                 (range request-count)
                 request-ids)]
            (is (::protocol/success? schema-response)
                (pr-str schema-response))
            (is (.await started 10 TimeUnit/SECONDS)
                "every caller entered the ambiguous-delivery window")
            (is (wait-for! #(some realized? attempts) 10000)
                "at least one mutation commits before the crash")
            (let [completed-before-kill (count (filter realized? attempts))]
              (is (< completed-before-kill request-count)
                  "the writer is killed while mutations remain in flight"))
            (stop-process! first-process)
            (reset! initial-outcomes
                    (mapv #(deref % 10000 ::timeout) attempts)))
          (finally
            (doseq [session sessions]
              (try (writer-test/close-session! session)
                   (catch Throwable _)))))
        (.delete (io/file socket-path))
        (let [second-process
              (start-writer-process!
               database-name database-path socket-path second-log)]
          (try
            (let [session (writer-test/open-session! socket-path)]
              (try
                (let [acquired
                      (acquire! session database-name "crash/reacquire")
                      database (:seon.db/db acquired)
                      responses
                      (mapv
                       (fn [request-id]
                         (try
                           (transact!
                            session database request-id
                            [{:writer.crash/id request-id}])
                           (catch Throwable throwable throwable)))
                       request-ids)
                      head (:db-after (last responses))
                      entity-count
                      (:datahike.query/result
                       (query! session head "crash/query/entities"
                               '[:find (count ?entity) .
                                 :where [?entity :writer.crash/id]]
                               []))
                      receipt-count
                      (:datahike.query/result
                       (query! session head "crash/query/receipts"
                               '[:find (count ?tx) .
                                 :in $ [?request-id ...]
                                 :where
                                 [?tx :seon.db.protocol/request-id ?request-id]]
                               [request-ids]))
                      ambiguous-id
                      (first
                       (keep-indexed
                        (fn [index attempt]
                          (when (instance? Throwable attempt)
                            (nth request-ids index)))
                        @initial-outcomes))
                      replay
                      (when ambiguous-id
                        (transact! session head ambiguous-id
                                   [{:writer.crash/id ambiguous-id}]))]
                  (is (every? ::protocol/success? responses)
                      (pr-str (remove ::protocol/success? responses)))
                  (is (= request-count entity-count)
                      "every logical mutation is present exactly once")
                  (is (= request-count receipt-count)
                      "every logical mutation has one durable receipt")
                  (is (some? ambiguous-id)
                      "at least one delivery outcome was ambiguous at death")
                  (is (::protocol/success? replay) (pr-str replay))
                  (is (true? (::protocol/recovered? replay))
                      "an ambiguous same-id retry receives the durable outcome"))
                (finally
                  (writer-test/close-session! session))))
            (finally
              (stop-process! second-process)))))
      (finally
        (stop-process! first-process)
        (delete-tree! root)))))
