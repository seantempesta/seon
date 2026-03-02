(ns seon.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [seon.db :as db]
            [seon.db.datalevin.conn :as conn]
            [seon.schema :as schema]
            [seon.test-utils :as tu]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

;; Register test attrs so schema enforcement passes
(schema/register! :name :string)
(schema/register! :age :int)

(def ^:private test-schema
  {:name {:db/valueType :db.type/string}
   :age  {:db/valueType :db.type/long}})

;;; ---------------------------------------------------------------------------
;;; transact! creates writer flow lazily
;;; ---------------------------------------------------------------------------

(deftest transact-creates-writer-test
  (testing "transact! writes data and creates writer flow"
    (tu/with-temp-conn test-schema
      (fn [conn]
        (try
          (let [result (db/transact! conn [{:name "Alice" :age 30}])]
            ;; Data written
            (is (some? result))
            (let [results (d/q '[:find ?n :where [?e :name ?n]] @conn)]
              (is (= #{["Alice"]} (set results))))
            ;; Writer flow created
            (is (some? (db/writer-status conn))))
          (finally
            (db/shutdown-writers!)))))))

;;; ---------------------------------------------------------------------------
;;; pause/resume coordination
;;; ---------------------------------------------------------------------------

(deftest pause-resume-test
  (testing "pause and resume don't throw"
    (tu/with-temp-conn test-schema
      (fn [conn]
        (try
          ;; Ensure writer exists
          (db/transact! conn [{:name "Bob" :age 25}])
          ;; Pause triggers flush
          (db/pause-writes! conn)
          ;; Resume restores
          (db/resume-writes! conn)
          ;; Can still write after resume
          (db/transact! conn [{:name "Carol" :age 35}])
          (let [results (d/q '[:find ?n :where [?e :name ?n]] @conn)]
            (is (= 2 (count results))))
          (finally
            (db/shutdown-writers!)))))))

;;; ---------------------------------------------------------------------------
;;; shutdown-writers! cleans up
;;; ---------------------------------------------------------------------------

(deftest shutdown-writers-test
  (testing "shutdown stops all writer flows"
    (tu/with-temp-conn test-schema
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
    (tu/with-temp-conn test-schema
      (fn [conn]
        (try
          (let [before (:total-writes (db/stats))]
            (db/transact! conn [{:name "Eve" :age 28}])
            (is (= (inc before) (:total-writes (db/stats)))))
          (finally
            (db/shutdown-writers!)))))))

;;; ---------------------------------------------------------------------------
;;; Named Convenience API
;;; ---------------------------------------------------------------------------

(defn- make-fake-manager
  "Build a fake conn manager backed by a local Datalevin connection.
   Maps ::master to the given conn."
  [conn]
  {::conn/port 0
   ::conn/ttl-ms 300000
   ::conn/connections (atom {:seon {::conn/connection conn
                                    ::conn/last-accessed (java.time.Instant/now)}})})

(defn- with-named-db
  "Set up a local db, bind it as the :seon named database via *conn-manager*,
   and call f with the raw connection."
  [f]
  (tu/with-temp-conn test-schema
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

;;; ---------------------------------------------------------------------------
;;; Schema enforcement
;;; ---------------------------------------------------------------------------

(deftest transact-rejects-unregistered-attrs-test
  (testing "transact! throws for unregistered attributes"
    (tu/with-temp-conn test-schema
      (fn [conn]
        (try
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Unregistered attributes"
               (db/transact! conn [{:bogus/unregistered "nope"}])))
          (finally
            (db/shutdown-writers!)))))))

(deftest transact-rejects-unregistered-vector-tuple-test
  (testing "transact! throws for unregistered attrs in vector tuples"
    (tu/with-temp-conn test-schema
      (fn [conn]
        (try
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Unregistered attributes"
               (db/transact! conn [[:db/add 1 :bogus/field "val"]])))
          (finally
            (db/shutdown-writers!)))))))

(deftest transact-allows-db-system-attrs-test
  (testing "transact! allows :db/* system attributes without registration"
    (tu/with-temp-conn test-schema
      (fn [conn]
        (try
          ;; :db/id is a system attr, should not require registration
          (let [result (db/transact! conn [{:db/id -1 :name "Sys" :age 1}])]
            (is (some? result)))
          (finally
            (db/shutdown-writers!)))))))

(deftest transact-auto-adds-missing-schema-test
  (testing "transact! auto-adds Datalevin schema for registered attrs not yet in DB"
    ;; Use an empty initial schema — attrs are registered but not in Datalevin yet
    (tu/with-temp-conn {}
      (fn [conn]
        (try
          ;; :name and :age are registered above — transact! should auto-add them
          (db/transact! conn [{:name "Auto" :age 99}])
          (let [results (d/q '[:find ?n :where [?e :name ?n]] @conn)]
            (is (= #{["Auto"]} (set results))))
          ;; Verify schema was added
          (is (contains? (d/schema conn) :name))
          (is (contains? (d/schema conn) :age))
          (finally
            (db/shutdown-writers!)))))))
