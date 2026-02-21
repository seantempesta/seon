(ns seon.db.datalevin.writer-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.db.datalevin.writer :as writer])
  (:import [java.time Instant]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(def ^:private test-schema
  {:name {:db/valueType :db.type/string}
   :age  {:db/valueType :db.type/long}})

(defn- with-temp-conn
  "Create a temp Datalevin connection, run f with it, then close."
  [f]
  (let [dir (str "tmp/test-writer-" (System/nanoTime))
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
;;; Describe (0-arity)
;;; ---------------------------------------------------------------------------

(deftest describe-test
  (testing "returns ins, outs, and workload"
    (let [desc (writer/db-writer-step)]
      (is (contains? (:ins desc) :in/transact))
      (is (contains? (:outs desc) :out/result))
      (is (contains? (:outs desc) :out/error))
      (is (= :io (:workload desc))))))

;;; ---------------------------------------------------------------------------
;;; Init (1-arity)
;;; ---------------------------------------------------------------------------

(deftest init-test
  (testing "initializes state with zeroed counters"
    (let [conn (atom nil)
          state (writer/db-writer-step {::writer/conn conn
                                        ::writer/db-name "test-db"})]
      (is (= conn (::writer/conn state)))
      (is (= "test-db" (::writer/db-name state)))
      (is (= 0 (::writer/total-writes state)))
      (is (= 0 (::writer/total-errors state)))
      (is (nil? (::writer/last-write-at state)))))

  (testing "defaults db-name to unknown"
    (let [state (writer/db-writer-step {::writer/conn (atom nil)})]
      (is (= "unknown" (::writer/db-name state))))))

;;; ---------------------------------------------------------------------------
;;; Transform — successful write (3-arity)
;;; ---------------------------------------------------------------------------

(deftest transform-write-test
  (testing "writes tx-data to Datalevin and updates metrics"
    (with-temp-conn
      (fn [conn]
        (let [state (writer/db-writer-step {::writer/conn conn
                                            ::writer/db-name "test"})
              tx-msg {::writer/tx-data [{:name "Alice" :age 30}]}
              [state' outputs] (writer/db-writer-step state :in/transact tx-msg)]
          ;; State updated
          (is (= 1 (::writer/total-writes state')))
          (is (= 0 (::writer/total-errors state')))
          (is (instance? Instant (::writer/last-write-at state')))
          ;; Output sent
          (is (= 1 (count (:out/result outputs))))
          (let [result (first (:out/result outputs))]
            (is (= :ok (::writer/status result)))
            (is (= "test" (::writer/db-name result)))
            (is (= 1 (::writer/tx-count result)))
            (is (number? (::writer/elapsed-ms result))))
          ;; Data actually in DB
          (let [db @conn
                results (d/q '[:find ?n ?a
                                :where [?e :name ?n] [?e :age ?a]]
                              db)]
            (is (= #{["Alice" 30]} (set results)))))))))

;;; ---------------------------------------------------------------------------
;;; Transform — error handling (3-arity)
;;; ---------------------------------------------------------------------------

(deftest transform-error-test
  (testing "handles invalid tx-data gracefully"
    (with-temp-conn
      (fn [conn]
        (let [state (writer/db-writer-step {::writer/conn conn
                                            ::writer/db-name "test"})
              ;; Pass something that will cause a transact error
              tx-msg {::writer/tx-data [:not-a-valid-transaction]}
              [state' outputs] (writer/db-writer-step state :in/transact tx-msg)]
          ;; Error count incremented
          (is (= 1 (::writer/total-errors state')))
          (is (= 0 (::writer/total-writes state')))
          ;; Error output sent
          (is (= 1 (count (:out/error outputs))))
          (let [err (first (:out/error outputs))]
            (is (= :error (::writer/status err)))
            (is (string? (::writer/error err)))))))))

;;; ---------------------------------------------------------------------------
;;; Transform — unknown input (3-arity)
;;; ---------------------------------------------------------------------------

(deftest transform-unknown-input-test
  (testing "unknown input-id returns state unchanged with nil outputs"
    (let [state (writer/db-writer-step {::writer/conn (atom nil)
                                        ::writer/db-name "test"})
          [state' outputs] (writer/db-writer-step state :in/unknown {:data 1})]
      (is (= state state'))
      (is (nil? outputs)))))

;;; ---------------------------------------------------------------------------
;;; Transition — pause flushes (2-arity)
;;; ---------------------------------------------------------------------------

(deftest transition-pause-test
  (testing "pause calls flush (empty transact) without error"
    (with-temp-conn
      (fn [conn]
        (let [state (writer/db-writer-step {::writer/conn conn
                                            ::writer/db-name "test"})
              state' (writer/db-writer-step state :clojure.core.async.flow/pause)]
          (is (= state state'))))))

  (testing "stop calls flush without error"
    (with-temp-conn
      (fn [conn]
        (let [state (writer/db-writer-step {::writer/conn conn
                                            ::writer/db-name "test"})
              state' (writer/db-writer-step state :clojure.core.async.flow/stop)]
          (is (= state state'))))))

  (testing "resume returns state unchanged"
    (let [state (writer/db-writer-step {::writer/conn (atom nil)
                                        ::writer/db-name "test"})
          state' (writer/db-writer-step state :clojure.core.async.flow/resume)]
      (is (= state state')))))

;;; ---------------------------------------------------------------------------
;;; Multiple writes accumulate metrics
;;; ---------------------------------------------------------------------------

(deftest multiple-writes-test
  (testing "multiple successful writes increment counter"
    (with-temp-conn
      (fn [conn]
        (let [state (writer/db-writer-step {::writer/conn conn
                                            ::writer/db-name "test"})
              tx1 {::writer/tx-data [{:name "Alice" :age 30}]}
              tx2 {::writer/tx-data [{:name "Bob" :age 25}]}
              [state1 _] (writer/db-writer-step state :in/transact tx1)
              [state2 _] (writer/db-writer-step state1 :in/transact tx2)]
          (is (= 2 (::writer/total-writes state2)))
          ;; Both records in DB
          (let [results (d/q '[:find ?n :where [?e :name ?n]] @conn)]
            (is (= 2 (count results)))))))))
