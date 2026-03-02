(ns seon.db.datalevin.writer-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [seon.db.datalevin.writer :as writer]
            [seon.test-utils :as tu])
  (:import [java.time Instant]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(def ^:private test-schema
  {:name {:db/valueType :db.type/string}
   :age  {:db/valueType :db.type/long}})

(defn- with-temp-conn
  "Create a temp Datalevin connection using the shared helper."
  [f]
  (tu/with-temp-conn test-schema f))

;;; ---------------------------------------------------------------------------
;;; Describe (0-arity)
;;; ---------------------------------------------------------------------------

(deftest describe-test
  (testing "returns ins, empty outs, and workload"
    (let [desc (writer/db-writer-step)]
      (is (contains? (:ins desc) :in/transact))
      (is (= {} (:outs desc)))
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
      (is (= "unknown" (::writer/db-name state)))))

  (testing "pending-promises stored in state when provided"
    (let [promises (atom {})
          state (writer/db-writer-step {::writer/conn (atom nil)
                                        ::writer/pending-promises promises})]
      (is (= promises (::writer/pending-promises state))))))

;;; ---------------------------------------------------------------------------
;;; Transform — successful write with promise (3-arity)
;;; ---------------------------------------------------------------------------

(deftest transform-write-test
  (testing "writes tx-data and delivers result to promise"
    (with-temp-conn
      (fn [conn]
        (let [cid (random-uuid)
              p (promise)
              promises (atom {cid p})
              state (writer/db-writer-step {::writer/conn conn
                                            ::writer/db-name "test"
                                            ::writer/pending-promises promises})
              tx-msg {::writer/tx-data [{:name "Alice" :age 30}]
                      ::writer/correlation-id cid}
              [state' outputs] (writer/db-writer-step state :in/transact tx-msg)]
          ;; State updated
          (is (= 1 (::writer/total-writes state')))
          (is (= 0 (::writer/total-errors state')))
          (is (instance? Instant (::writer/last-write-at state')))
          ;; No flow outputs
          (is (nil? outputs))
          ;; Promise delivered with :ok status
          (let [result (deref p 1000 :timeout)]
            (is (not= :timeout result))
            (is (= :ok (::writer/status result)))
            (is (some? (::writer/tx-report result)))
            (is (number? (::writer/elapsed-ms result))))
          ;; Promise removed from atom
          (is (empty? @promises))
          ;; Data actually in DB
          (let [results (d/q '[:find ?n ?a
                               :where [?e :name ?n] [?e :age ?a]]
                             @conn)]
            (is (= #{["Alice" 30]} (set results)))))))))

;;; ---------------------------------------------------------------------------
;;; Transform — error with promise (3-arity)
;;; ---------------------------------------------------------------------------

(deftest transform-error-test
  (testing "handles invalid tx-data and delivers error to promise"
    (with-temp-conn
      (fn [conn]
        (let [cid (random-uuid)
              p (promise)
              promises (atom {cid p})
              state (writer/db-writer-step {::writer/conn conn
                                            ::writer/db-name "test"
                                            ::writer/pending-promises promises})
              tx-msg {::writer/tx-data [:not-a-valid-transaction]
                      ::writer/correlation-id cid}
              [state' outputs] (writer/db-writer-step state :in/transact tx-msg)]
          ;; Error count incremented
          (is (= 1 (::writer/total-errors state')))
          (is (= 0 (::writer/total-writes state')))
          ;; No flow outputs
          (is (nil? outputs))
          ;; Promise delivered with :error status
          (let [result (deref p 1000 :timeout)]
            (is (not= :timeout result))
            (is (= :error (::writer/status result)))
            (is (instance? Exception (::writer/error result)))
            (is (number? (::writer/elapsed-ms result))))
          ;; Promise removed from atom
          (is (empty? @promises)))))))

;;; ---------------------------------------------------------------------------
;;; Transform — write without correlation-id (no promise)
;;; ---------------------------------------------------------------------------

(deftest transform-without-promise-test
  (testing "write without correlation-id updates state and DB, no promise needed"
    (with-temp-conn
      (fn [conn]
        (let [promises (atom {})
              state (writer/db-writer-step {::writer/conn conn
                                            ::writer/db-name "test"
                                            ::writer/pending-promises promises})
              tx-msg {::writer/tx-data [{:name "Bob" :age 25}]}
              [state' outputs] (writer/db-writer-step state :in/transact tx-msg)]
          ;; State updated
          (is (= 1 (::writer/total-writes state')))
          (is (instance? Instant (::writer/last-write-at state')))
          ;; No flow outputs
          (is (nil? outputs))
          ;; No promises touched
          (is (empty? @promises))
          ;; Data in DB
          (let [results (d/q '[:find ?n :where [?e :name ?n]] @conn)]
            (is (= #{["Bob"]} (set results)))))))))

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
