(ns seon.authority-density-test
  "Real Bun-process proof that one JVM owns database indexes and query work."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.query :as dq]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.writer :as writer])
  (:import [java.io File]
           [java.util.concurrent TimeUnit]))

(def client-artifact "out/authority-density/client.js")

(defn- socket-path [label]
  (let [directory (File. "tmp")]
    (.mkdirs directory)
    (.getAbsolutePath
     (File. directory (str "seon-authority-density-" label "-"
                           (random-uuid) ".sock")))))

(defn- dependencies []
  {::writer/database-initializer (fn [_connection _database-name] nil)
   ::writer/embedding-enabled? false
   ::writer/embedding-entity-ids (fn [_db-value] [])
   ::writer/embedding-inputs-for-eids (fn [_db-value _entity-ids] [])
   ::writer/embedding-assertions (fn [_inputs] [])
   ::writer/revalidate-embedding-assertions (fn [_db-value _assertions] [])
   ::writer/query-vec (fn [_] {:seon.embed/vector [0.0]})
   ::writer/knn (fn [_db-value _vector _k _eids] [])})

(defn- registry-entry [database-name]
  (get-in (registry/snapshot-registry {})
          [::registry/snapshot ::registry/registry (keyword database-name)]))

(defn- transport-count [database-name]
  (count (::registry/transport-connections (registry-entry database-name))))

(defn- wait-until! [description timeout-ms predicate]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (predicate) true
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 10) (recur))
        :else
        (throw (ex-info (str "Timed out waiting for " description ".")
                        {:seon.authority-density/description description}))))))

(defn- start-child! [socket database-name barrier-ms]
  (-> (ProcessBuilder.
       ^java.util.List
       ["bun" client-artifact socket database-name (str barrier-ms)])
      (.directory (File. "."))
      (.redirectErrorStream true)
      (.start)))

(defn- command-output [command]
  (let [process (-> (ProcessBuilder. ^java.util.List command)
                    (.redirectErrorStream true)
                    (.start))
        output (slurp (.getInputStream process))]
    (.waitFor process 5 TimeUnit/SECONDS)
    output))

(defn- rss-kib [^Process process]
  (some-> (command-output ["ps" "-o" "rss=" "-p" (str (.pid process))])
          str/trim
          not-empty
          Long/parseLong))

(defn- descriptors [^Process process]
  (command-output ["lsof" "-Fn" "-p" (str (.pid process))]))

(defn- read-results [^Process process]
  (is (.waitFor process 30 TimeUnit/SECONDS)
      (str "Bun child " (.pid process) " did not terminate"))
  (let [output (slurp (.getInputStream process))
        values (->> (str/split-lines output)
                    (keep (fn [line]
                            (when (str/starts-with? line "{")
                              (try (edn/read-string line)
                                   (catch Throwable _ nil)))))
                    vec)]
    (is (zero? (.exitValue process)) output)
    (is (= [:ready :complete]
           (mapv :seon.authority-density/phase values))
        output)
    (last values)))

(defn- index-roots [db-value]
  (mapv #(get db-value %) [:eavt :aevt :avet]))

(defn- run-wave! [database-name request-path child-count]
  (dq/clear-query-cache!)
  (let [barrier-ms (+ (System/currentTimeMillis) 8000)
        processes (mapv (fn [_]
                          (start-child! request-path database-name barrier-ms))
                        (range child-count))]
    (try
      (wait-until! (str child-count " acquired Bun sessions") 7000
                   #(= child-count (transport-count database-name)))
      (let [entry (registry-entry database-name)
            connection (::registry/conn entry)
            database-value (d/db connection)
            roots (index-roots database-value)
            process-evidence
            (mapv (fn [process]
                    {:seon.authority-density/pid (.pid ^Process process)
                     :seon.authority-density/rss-kib (rss-kib process)
                     :seon.authority-density/descriptors
                     (descriptors process)})
                  processes)
            _ (is (every? pos-int?
                          (map :seon.authority-density/rss-kib process-evidence)))
            _ (is (identical? connection
                              (::registry/conn (registry-entry database-name)))
                  "all physical sessions retain one registry connection")
            _ (is (every? true?
                          (map identical?
                               roots
                               (index-roots
                                (d/db (::registry/conn
                                       (registry-entry database-name))))))
                  "client count does not replace or copy index roots")
            results (mapv read-results processes)
            first-outcomes
            (frequencies
             (map #(get-in % [:seon.authority-density/first
                              :datahike.query/cache-evidence
                              :datahike.cache/outcome])
                  results))
            second-outcomes
            (mapv #(get-in % [:seon.authority-density/second
                              :datahike.query/cache-evidence
                              :datahike.cache/outcome])
                  results)]
        (is (every? #(nil? (get-in % [:seon.authority-density/first
                                      :seon.error/message]))
                    results)
            (pr-str results))
        (is (= 1 (:datahike.cache.outcome/miss-owner first-outcomes 0))
            (pr-str {:outcomes first-outcomes :results results}))
        (is (= (dec child-count)
               (+ (:datahike.cache.outcome/miss-joined first-outcomes 0)
                  (:datahike.cache.outcome/hit first-outcomes 0)))
            (pr-str first-outcomes))
        (is (every? #{:datahike.cache.outcome/hit} second-outcomes))
        (is (every? #(= 400 (count (get-in %
                                          [:seon.authority-density/first
                                           :datahike.query/result])))
                    results))
        (wait-until! "Bun session release" 5000
                     #(zero? (transport-count database-name)))
        (is (zero? (:datahike.single-flight/active-flights
                    (dq/query-cache-evidence))))
        (is (zero? (:datahike.single-flight/active-callers
                    (dq/query-cache-evidence))))
        (let [proof
              {:seon.authority-density/children child-count
               :seon.authority-density/rss-kib
               (mapv :seon.authority-density/rss-kib process-evidence)
               :seon.authority-density/outcomes first-outcomes}]
          (println (pr-str proof))
          proof))
      (finally
        (doseq [^Process process processes]
          (when (.isAlive process) (.destroyForcibly process)))))))

(deftest real-bun-children-share-one-jvm-connection-index-and-query-flight
  (is (.isFile (File. client-artifact))
      (str "Compile the test-only client first: "
           "clj -M:cljs compile authority-density-client"))
  (let [database-name (str "authority-density-" (random-uuid))
        request-path (socket-path "request")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/selected-processors 8
          ::writer/request-socket-path request-path})]
    (try
      (let [head
            (writer/handle-request
             (::writer/runtime server)
             (protocol/resolve-head-request
              {::protocol/request-id "authority-density/head"
               ::protocol/database-name database-name}))
            transaction
            (writer/handle-request
             (::writer/runtime server)
             (protocol/transaction-request
              {::protocol/request-id "authority-density/seed"
               :seon.db/db (:seon.db/db head)
               ::protocol/transaction-data
               (into
                [{:db/ident :seon.authority-density/id
                  :db/valueType :db.type/long
                  :db/cardinality :db.cardinality/one}
                 {:db/ident :seon.authority-density/group
                  :db/valueType :db.type/string
                  :db/cardinality :db.cardinality/one}]
                (map (fn [value]
                       {:seon.authority-density/id value
                        :seon.authority-density/group "shared"}))
                (range 400))}))]
        (is (::protocol/success? head) (pr-str head))
        (is (::protocol/success? transaction) (pr-str transaction)))
      (testing "one child establishes the cold-owner and hit baseline"
        (run-wave! database-name request-path 1))
      (testing "eight independent Bun sessions share that JVM state"
        (let [proof (run-wave! database-name request-path 8)]
          (is (= 8 (:seon.authority-density/children proof)))))
      (finally
        (writer/stop! server)
        (.delete (File. request-path))))))
