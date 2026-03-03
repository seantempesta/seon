(ns seon.db.datalevin.backup-test
  "Integration tests for Datalevin backup coordination.

   These tests perform real I/O operations on the filesystem.
   Tagged as ^:integration to allow filtering in test runs."
  {:integration true}
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.db.datalevin.backup :as backup])
  (:import [java.io File]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(def ^:private test-base-dir "tmp/backup-test")

(defn- clean-test-dirs!
  "Remove all test directories."
  []
  (let [base (io/file test-base-dir)]
    (when (.exists base)
      (letfn [(delete-recursive [^File f]
                (when (.isDirectory f)
                  (doseq [child (.listFiles f)]
                    (delete-recursive child)))
                (.delete f))]
        (delete-recursive base)))))

(defn- setup-test-dirs!
  "Create fresh test directories."
  []
  (clean-test-dirs!)
  (.mkdirs (io/file test-base-dir "data"))
  (.mkdirs (io/file test-base-dir "backups")))

(defn test-fixture [f]
  (setup-test-dirs!)
  (try
    (f)
    (finally
      (clean-test-dirs!))))

(use-fixtures :each test-fixture)

;;; ---------------------------------------------------------------------------
;;; Helper Functions
;;; ---------------------------------------------------------------------------

(defn- create-test-db!
  "Create a test Datalevin database with some data.
   Uses d/transact! directly (not db/transact!) since these are
   integration tests that don't need the infrastructure flow."
  [data-dir]
  (let [dir (str test-base-dir "/" data-dir)
        schema {:name {:db/valueType :db.type/string}
                :value {:db/valueType :db.type/long}}
        conn (d/create-conn dir schema {:kv-opts {:flags #{:nordahead :writemap :mapasync :nosync}}})]
    (d/transact! conn [{:name "test" :value 42}])
    conn))

(defn- query-test-data
  "Query the test database for our test data."
  [conn]
  (d/q '[:find ?n ?v
         :where [?e :name ?n] [?e :value ?v]]
       @conn))

;;; ---------------------------------------------------------------------------
;;; list-backups Tests
;;; ---------------------------------------------------------------------------

(deftest ^:integration list-backups-empty-dir-test
  (testing "list-backups returns empty vector for non-existent directory"
    (let [result (backup/list-backups {::backup/backup-dir "nonexistent/path"})]
      (is (= [] result)))))

;;; ---------------------------------------------------------------------------
;;; prune! Tests
;;; ---------------------------------------------------------------------------

(deftest ^:integration prune-no-backups-test
  (testing "prune! handles empty backup directory gracefully"
    (let [result (backup/prune! {::backup/backup-dir (str test-base-dir "/backups")
                                 ::backup/keep 5})]
      (is (= 0 (::backup/pruned result)))
      (is (= 0 (::backup/kept result))))))

;;; ---------------------------------------------------------------------------
;;; restore! Tests
;;; ---------------------------------------------------------------------------

(deftest ^:integration restore-missing-backup-test
  (testing "restore! returns error when backup doesn't exist"
    (let [result (backup/restore! {::backup/backup-path "nonexistent/backup"
                                   ::backup/data-dir (str test-base-dir "/data")})]
      (is (= :error (::backup/status result)))
      (is (string? (::backup/error-message result))))))
