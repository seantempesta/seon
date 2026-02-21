(ns seon.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [seon.db :as db]))

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
