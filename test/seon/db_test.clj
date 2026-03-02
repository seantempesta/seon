(ns seon.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [seon.db :as db]
            [seon.db.datalevin.conn :as conn]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(def ^:private test-schema
  {:name {:db/valueType :db.type/string}
   :age  {:db/valueType :db.type/long}})

(defn- with-temp-conn [f]
  (let [dir (str "tmp/test-db-" (System/nanoTime))
        conn (d/get-conn dir test-schema)]
    (try
      (f conn)
      (finally
        (d/close conn)
        (let [d (java.io.File. dir)]
          (doseq [child (.listFiles d)]
            (.delete child))
          (.delete d))))))

;;; ---------------------------------------------------------------------------
;;; transact! creates writer flow lazily
;;; ---------------------------------------------------------------------------

(deftest transact-creates-writer-test
  (testing "transact! writes data and creates writer flow"
    (with-temp-conn
      (fn [conn]
        (let [result (db/transact! conn [{:name "Alice" :age 30}])]
          ;; Data written
          (is (some? result))
          (let [results (d/q '[:find ?n :where [?e :name ?n]] @conn)]
            (is (= #{["Alice"]} (set results))))
          ;; Writer flow created
          (is (some? (db/writer-status conn))))))))

;;; ---------------------------------------------------------------------------
;;; pause/resume coordination
;;; ---------------------------------------------------------------------------

(deftest pause-resume-test
  (testing "pause and resume don't throw"
    (with-temp-conn
      (fn [conn]
        ;; Ensure writer exists
        (db/transact! conn [{:name "Bob" :age 25}])
        ;; Pause triggers flush
        (db/pause-writes! conn)
        ;; Resume restores
        (db/resume-writes! conn)
        ;; Can still write after resume
        (db/transact! conn [{:name "Carol" :age 35}])
        (let [results (d/q '[:find ?n :where [?e :name ?n]] @conn)]
          (is (= 2 (count results))))))))

;;; ---------------------------------------------------------------------------
;;; shutdown-writers! cleans up
;;; ---------------------------------------------------------------------------

(deftest shutdown-writers-test
  (testing "shutdown stops all writer flows"
    (with-temp-conn
      (fn [conn]
        (db/transact! conn [{:name "Dave" :age 40}])
        (is (some? (db/writer-status conn)))
        (db/shutdown-writers!)
        (is (nil? (db/writer-status conn)))))))

;;; ---------------------------------------------------------------------------
;;; stats tracks writes
;;; ---------------------------------------------------------------------------

(deftest stats-test
  (testing "stats tracks write counts"
    (with-temp-conn
      (fn [conn]
        (let [before (:total-writes (db/stats))]
          (db/transact! conn [{:name "Eve" :age 28}])
          (is (= (inc before) (:total-writes (db/stats)))))))))

;;; ---------------------------------------------------------------------------
;;; Named Convenience API
;;; ---------------------------------------------------------------------------

(defn- make-fake-manager
  "Build a fake conn manager backed by a local Datalevin connection.
   Maps ::master to the given conn."
  [conn]
  {::conn/port 0
   ::conn/ttl-ms 300000
   ::conn/connections (atom {::conn/master {::conn/connection conn
                                             ::conn/last-accessed (java.time.Instant/now)}})})

(defn- with-named-db
  "Set up a local db, bind it as the :seon named database via *conn-manager*,
   and call f with the raw connection."
  [f]
  (with-temp-conn
    (fn [conn]
      (binding [db/*conn-manager* (make-fake-manager conn)]
        (f conn)))))

(deftest query-test
  (testing "query resolves db by name and runs Datalog"
    (with-named-db
      (fn [conn]
        (d/transact! conn [{:name "Alice" :age 30}])
        (let [results (db/query :seon '[:find ?n :where [?e :name ?n]])]
          (is (= #{["Alice"]} (set results))))))))

(deftest query-with-inputs-test
  (testing "query passes additional inputs to d/q"
    (with-named-db
      (fn [conn]
        (d/transact! conn [{:name "Bob" :age 25}
                           {:name "Carol" :age 35}])
        ;; Use an input binding for age threshold
        (let [results (db/query :seon
                                '[:find ?n
                                  :in $ ?min-age
                                  :where
                                  [?e :name ?n]
                                  [?e :age ?a]
                                  [(>= ?a ?min-age)]]
                                30)]
          (is (= #{["Carol"]} (set results))))))))

(deftest pull-by-name-test
  (testing "pull-by-name resolves entity by eid"
    (with-named-db
      (fn [conn]
        (d/transact! conn [{:name "Dave" :age 40}])
        (let [eid (ffirst (d/q '[:find ?e :where [?e :name "Dave"]] @conn))
              result (db/pull-by-name :seon '[:name :age] eid)]
          (is (= "Dave" (:name result)))
          (is (= 40 (:age result))))))))

(deftest pull-many-by-name-test
  (testing "pull-many-by-name resolves multiple entities"
    (with-named-db
      (fn [conn]
        (d/transact! conn [{:name "Eve" :age 28}
                           {:name "Frank" :age 33}])
        (let [eids (mapv first (d/q '[:find ?e :where [?e :name _]] @conn))
              results (db/pull-many-by-name :seon '[:name] eids)]
          (is (= 2 (count results)))
          (is (= #{["Eve"] ["Frank"]}
                 (set (map (comp vector :name) results)))))))))
