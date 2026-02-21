(ns seon.db.datalevin.backup-test
  "Integration tests for Datalevin backup coordination.

   These tests perform real I/O operations on the filesystem.
   Tagged as ^:integration to allow filtering in test runs."
  {:integration true}
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.db :as db]
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
  "Create a test Datalevin database with some data."
  [data-dir]
  (let [dir (str test-base-dir "/" data-dir)
        schema {:name {:db/valueType :db.type/string}
                :value {:db/valueType :db.type/long}}
        conn (d/get-conn dir schema)]
    ;; Use db/transact! to register a writer flow
    (db/transact! conn [{:name "test" :value 42}])
    conn))

(defn- query-test-data
  "Query the test database for our test data."
  [conn]
  (d/q '[:find ?n ?v
         :where [?e :name ?n] [?e :value ?v]]
       @conn))

;;; ---------------------------------------------------------------------------
;;; backup! Tests
;;; ---------------------------------------------------------------------------

(deftest ^:integration backup-creates-directory-test
  (testing "backup! creates a timestamped directory with files"
    (let [conn (create-test-db! "data")
          data-dir (str test-base-dir "/data")
          backup-dir (str test-base-dir "/backups")
          result (backup/backup! {::backup/data-dir data-dir
                                  ::backup/backup-dir backup-dir})]
      ;; Close conn after backup
      (d/close conn)

      ;; Check result
      (is (= :ok (::backup/status result)))
      (is (string? (::backup/backup-path result)))
      (is (number? (::backup/elapsed-ms result)))

      ;; Check backup directory exists and has content
      (let [backup-path (io/file (::backup/backup-path result))]
        (is (.exists backup-path))
        (is (.isDirectory backup-path))
        (is (pos? (count (.listFiles backup-path))))))))

(deftest ^:integration backup-missing-data-dir-test
  (testing "backup! returns error when data directory doesn't exist"
    (let [result (backup/backup! {::backup/data-dir "nonexistent/path"
                                  ::backup/backup-dir (str test-base-dir "/backups")})]
      (is (= :error (::backup/status result)))
      (is (string? (::backup/error-message result))))))

;;; ---------------------------------------------------------------------------
;;; list-backups Tests
;;; ---------------------------------------------------------------------------

(deftest ^:integration list-backups-returns-sorted-results-test
  (testing "list-backups returns backups sorted newest first"
    (let [conn (create-test-db! "data")
          data-dir (str test-base-dir "/data")
          backup-dir (str test-base-dir "/backups")
          ;; Create multiple backups with small delay
          result1 (backup/backup! {::backup/data-dir data-dir
                                   ::backup/backup-dir backup-dir})
          _ (Thread/sleep 1100)  ; Ensure different timestamps
          result2 (backup/backup! {::backup/data-dir data-dir
                                   ::backup/backup-dir backup-dir})]
      (d/close conn)

      ;; List backups
      (let [backups (backup/list-backups {::backup/backup-dir backup-dir})]
        (is (= 2 (count backups)))
        ;; Newest first
        (is (= (::backup/backup-path result2) (::backup/backup-path (first backups))))
        (is (= (::backup/backup-path result1) (::backup/backup-path (second backups))))
        ;; Each has required keys
        (doseq [b backups]
          (is (string? (::backup/backup-path b)))
          (is (instance? java.time.Instant (::backup/created-at b)))
          (is (number? (::backup/size-bytes b))))))))

(deftest ^:integration list-backups-empty-dir-test
  (testing "list-backups returns empty vector for non-existent directory"
    (let [result (backup/list-backups {::backup/backup-dir "nonexistent/path"})]
      (is (= [] result)))))

;;; ---------------------------------------------------------------------------
;;; prune! Tests
;;; ---------------------------------------------------------------------------

(deftest ^:integration prune-keeps-correct-count-test
  (testing "prune! keeps N most recent backups"
    (let [conn (create-test-db! "data")
          data-dir (str test-base-dir "/data")
          backup-dir (str test-base-dir "/backups")]
      ;; Create 4 backups
      (dotimes [_ 4]
        (backup/backup! {::backup/data-dir data-dir
                         ::backup/backup-dir backup-dir})
        (Thread/sleep 1100))  ; Ensure different timestamps
      (d/close conn)

      ;; Verify we have 4
      (is (= 4 (count (backup/list-backups {::backup/backup-dir backup-dir}))))

      ;; Prune to keep 2
      (let [result (backup/prune! {::backup/backup-dir backup-dir
                                   ::backup/keep 2})]
        (is (= 2 (::backup/pruned result)))
        (is (= 2 (::backup/kept result))))

      ;; Verify only 2 remain
      (is (= 2 (count (backup/list-backups {::backup/backup-dir backup-dir})))))))

(deftest ^:integration prune-no-backups-test
  (testing "prune! handles empty backup directory gracefully"
    (let [result (backup/prune! {::backup/backup-dir (str test-base-dir "/backups")
                                 ::backup/keep 5})]
      (is (= 0 (::backup/pruned result)))
      (is (= 0 (::backup/kept result))))))

;;; ---------------------------------------------------------------------------
;;; restore! Tests
;;; ---------------------------------------------------------------------------

(deftest ^:integration restore-copies-data-test
  (testing "restore! copies backup to data directory"
    (let [;; Create database with data
          conn (create-test-db! "data")
          data-dir (str test-base-dir "/data")
          backup-dir (str test-base-dir "/backups")
          ;; Backup it
          backup-result (backup/backup! {::backup/data-dir data-dir
                                          ::backup/backup-dir backup-dir})]
      (d/close conn)

      ;; Create a new data directory
      (let [restore-dir (str test-base-dir "/restored")]
        (.mkdirs (io/file restore-dir))

        ;; Restore backup
        (let [result (backup/restore! {::backup/backup-path (::backup/backup-path backup-result)
                                       ::backup/data-dir restore-dir})]
          (is (= :ok (::backup/status result)))

          ;; Verify restored directory has content
          (let [restored (io/file restore-dir)]
            (is (.exists restored))
            (is (pos? (count (.listFiles restored))))))))))

(deftest ^:integration restore-missing-backup-test
  (testing "restore! returns error when backup doesn't exist"
    (let [result (backup/restore! {::backup/backup-path "nonexistent/backup"
                                   ::backup/data-dir (str test-base-dir "/data")})]
      (is (= :error (::backup/status result)))
      (is (string? (::backup/error-message result))))))

;;; ---------------------------------------------------------------------------
;;; Writer Flow Integration Tests
;;; ---------------------------------------------------------------------------

(deftest ^:integration backup-pauses-and-resumes-writers-test
  (testing "backup! pauses and resumes writer flows"
    (let [conn (create-test-db! "data")
          data-dir (str test-base-dir "/data")
          backup-dir (str test-base-dir "/backups")]
      ;; Verify writer flow exists (transact! creates it)
      (is (seq (db/all-conns)))

      ;; Backup should pause/resume without errors
      (let [result (backup/backup! {::backup/data-dir data-dir
                                    ::backup/backup-dir backup-dir})]
        (is (= :ok (::backup/status result))))

      ;; Verify we can still transact after backup
      (db/transact! conn [{:name "post-backup" :value 99}])
      (let [results (query-test-data conn)]
        (is (= 2 (count results))))

      (d/close conn))))
