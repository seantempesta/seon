(ns seon.db.datalevin.writer-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async.flow :as flow]
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
  (testing "writer returns ins, outs with result/error, and workload"
    (let [desc (writer/db-writer-step)]
      (is (contains? (:ins desc) :in/transact))
      (is (contains? (:outs desc) :out/result))
      (is (contains? (:outs desc) :out/error))
      (is (= :io (:workload desc)))))

  (testing "reply-sink returns ins with result/error, empty outs"
    (let [desc (writer/write-reply-step)]
      (is (contains? (:ins desc) :in/result))
      (is (contains? (:ins desc) :in/error))
      (is (= {} (:outs desc)))
      (is (= :io (:workload desc))))))

;;; ---------------------------------------------------------------------------
;;; Init (1-arity)
;;; ---------------------------------------------------------------------------

(deftest init-test
  (testing "writer initializes state with zeroed counters, no promise knowledge"
    (let [conn (atom nil)
          state (writer/db-writer-step {::writer/conn conn
                                         ::writer/db-name "test-db"})]
      (is (= conn (::writer/conn state)))
      (is (= "test-db" (::writer/db-name state)))
      (is (= 0 (::writer/total-writes state)))
      (is (= 0 (::writer/total-errors state)))
      (is (nil? (::writer/last-write-at state)))
      (is (not (contains? state ::writer/pending-promises)))))

  (testing "writer defaults db-name to unknown"
    (let [state (writer/db-writer-step {::writer/conn (atom nil)})]
      (is (= "unknown" (::writer/db-name state)))))

  (testing "reply-sink initializes with pending-promises and counters"
    (let [promises (atom {})
          state (writer/write-reply-step {::writer/pending-promises promises})]
      (is (= promises (::writer/pending-promises state)))
      (is (= 0 (::writer/delivered state)))
      (is (= 0 (::writer/unmatched state))))))

;;; ---------------------------------------------------------------------------
;;; Writer Transform — successful write emits :out/result
;;; ---------------------------------------------------------------------------

(deftest transform-write-test
  (testing "writes tx-data and emits result on :out/result with correlation-id pass-through"
    (with-temp-conn
      (fn [conn]
        (let [cid (random-uuid)
              state (writer/db-writer-step {::writer/conn conn
                                             ::writer/db-name "test"})
              tx-msg {::writer/tx-data [{:name "Alice" :age 30}]
                      ::writer/correlation-id cid}
              [state' outputs] (writer/db-writer-step state :in/transact tx-msg)]
          ;; State updated
          (is (= 1 (::writer/total-writes state')))
          (is (= 0 (::writer/total-errors state')))
          (is (instance? Instant (::writer/last-write-at state')))
          ;; Flow output emitted on :out/result
          (is (some? outputs))
          (let [result (first (:out/result outputs))]
            (is (= :ok (::writer/status result)))
            (is (some? (::writer/tx-report result)))
            (is (number? (::writer/elapsed-ms result)))
            (is (= cid (::writer/correlation-id result))))
          ;; Data actually in DB
          (let [results (d/q '[:find ?n ?a
                               :where [?e :name ?n] [?e :age ?a]]
                             @conn)]
            (is (= #{["Alice" 30]} (set results)))))))))

;;; ---------------------------------------------------------------------------
;;; Writer Transform — error emits :out/error
;;; ---------------------------------------------------------------------------

(deftest transform-error-test
  (testing "handles invalid tx-data and emits error on :out/error"
    (with-temp-conn
      (fn [conn]
        (let [cid (random-uuid)
              state (writer/db-writer-step {::writer/conn conn
                                             ::writer/db-name "test"})
              tx-msg {::writer/tx-data [:not-a-valid-transaction]
                      ::writer/correlation-id cid}
              [state' outputs] (writer/db-writer-step state :in/transact tx-msg)]
          ;; Error count incremented
          (is (= 1 (::writer/total-errors state')))
          (is (= 0 (::writer/total-writes state')))
          ;; Flow output emitted on :out/error
          (is (some? outputs))
          (let [error-result (first (:out/error outputs))]
            (is (= :error (::writer/status error-result)))
            (is (instance? Exception (::writer/error error-result)))
            (is (number? (::writer/elapsed-ms error-result)))
            (is (= cid (::writer/correlation-id error-result)))))))))

;;; ---------------------------------------------------------------------------
;;; Writer Transform — write without correlation-id
;;; ---------------------------------------------------------------------------

(deftest transform-without-correlation-id-test
  (testing "write without correlation-id emits result without correlation-id key"
    (with-temp-conn
      (fn [conn]
        (let [state (writer/db-writer-step {::writer/conn conn
                                             ::writer/db-name "test"})
              tx-msg {::writer/tx-data [{:name "Bob" :age 25}]}
              [state' outputs] (writer/db-writer-step state :in/transact tx-msg)]
          ;; State updated
          (is (= 1 (::writer/total-writes state')))
          (is (instance? Instant (::writer/last-write-at state')))
          ;; Output emitted but no correlation-id
          (is (some? outputs))
          (let [result (first (:out/result outputs))]
            (is (= :ok (::writer/status result)))
            (is (not (contains? result ::writer/correlation-id))))
          ;; Data in DB
          (let [results (d/q '[:find ?n :where [?e :name ?n]] @conn)]
            (is (= #{["Bob"]} (set results)))))))))

;;; ---------------------------------------------------------------------------
;;; Writer Transform — unknown input
;;; ---------------------------------------------------------------------------

(deftest transform-unknown-input-test
  (testing "unknown input-id returns state unchanged with nil outputs"
    (let [state (writer/db-writer-step {::writer/conn (atom nil)
                                         ::writer/db-name "test"})
          [state' outputs] (writer/db-writer-step state :in/unknown {:data 1})]
      (is (= state state'))
      (is (nil? outputs)))))

;;; ---------------------------------------------------------------------------
;;; Reply Sink — delivers promise on result
;;; ---------------------------------------------------------------------------

(deftest reply-sink-delivers-test
  (testing "reply-sink delivers result to matching promise"
    (let [cid (random-uuid)
          p (promise)
          promises (atom {cid p})
          state (writer/write-reply-step {::writer/pending-promises promises})
          result-msg {::writer/status :ok
                      ::writer/tx-report {:some "report"}
                      ::writer/elapsed-ms 1.5
                      ::writer/correlation-id cid}
          [state' _] (writer/write-reply-step state :in/result result-msg)]
      ;; Promise delivered
      (let [delivered (deref p 100 :timeout)]
        (is (not= :timeout delivered))
        (is (= :ok (::writer/status delivered))))
      ;; Counters updated
      (is (= 1 (::writer/delivered state')))
      ;; Promise removed from atom
      (is (empty? @promises))))

  (testing "reply-sink delivers error to matching promise"
    (let [cid (random-uuid)
          p (promise)
          promises (atom {cid p})
          state (writer/write-reply-step {::writer/pending-promises promises})
          error-msg {::writer/status :error
                     ::writer/error (Exception. "bad")
                     ::writer/elapsed-ms 2.0
                     ::writer/correlation-id cid}
          [state' _] (writer/write-reply-step state :in/error error-msg)]
      (let [delivered (deref p 100 :timeout)]
        (is (not= :timeout delivered))
        (is (= :error (::writer/status delivered))))
      (is (= 1 (::writer/delivered state'))))))

;;; ---------------------------------------------------------------------------
;;; Reply Sink — no correlation-id (fire-and-forget)
;;; ---------------------------------------------------------------------------

(deftest reply-sink-no-correlation-id-test
  (testing "messages without correlation-id are silently ignored"
    (let [promises (atom {})
          state (writer/write-reply-step {::writer/pending-promises promises})
          msg {::writer/status :ok ::writer/elapsed-ms 1.0}
          [state' outputs] (writer/write-reply-step state :in/result msg)]
      (is (= 0 (::writer/delivered state')))
      (is (= 0 (::writer/unmatched state')))
      (is (nil? outputs)))))

;;; ---------------------------------------------------------------------------
;;; Reply Sink — unmatched correlation-id
;;; ---------------------------------------------------------------------------

(deftest reply-sink-unmatched-test
  (testing "messages with unknown correlation-id increment unmatched counter"
    (let [promises (atom {})
          state (writer/write-reply-step {::writer/pending-promises promises})
          msg {::writer/status :ok
               ::writer/correlation-id (random-uuid)
               ::writer/elapsed-ms 1.0}
          [state' _] (writer/write-reply-step state :in/result msg)]
      (is (= 0 (::writer/delivered state')))
      (is (= 1 (::writer/unmatched state'))))))

;;; ---------------------------------------------------------------------------
;;; Transition — pause flushes
;;; ---------------------------------------------------------------------------

(deftest transition-pause-test
  (testing "pause calls flush (empty transact) without error"
    (with-temp-conn
      (fn [conn]
        (let [state (writer/db-writer-step {::writer/conn conn
                                             ::writer/db-name "test"})
              state' (writer/db-writer-step state :clojure.core.async.flow/pause)]
          (is (= state state'))))))

  (testing "stop returns state unchanged"
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

;;; ---------------------------------------------------------------------------
;;; End-to-end: full flow with promise delivery via reply-sink
;;; ---------------------------------------------------------------------------

(deftest end-to-end-flow-test
  (testing "full flow: inject tx with correlation-id, promise delivered via reply-sink"
    (with-temp-conn
      (fn [conn]
        (let [cid (random-uuid)
              p (promise)
              promises (atom {cid p})
              fl (writer/create-writer-flow {::writer/conn conn
                                              ::writer/db-name "e2e-test"
                                              ::writer/pending-promises promises})
              tx-msg {::writer/tx-data [{:name "Charlie" :age 35}]
                      ::writer/correlation-id cid}]
          (try
            (writer/inject-tx! {::writer/flow fl ::writer/tx-msg tx-msg})
            ;; Wait for promise delivery
            (let [result (deref p 5000 :timeout)]
              (is (not= :timeout result) "Promise should be delivered via reply-sink")
              (is (= :ok (::writer/status result)))
              (is (some? (::writer/tx-report result))))
            ;; Verify data in DB
            (let [results (d/q '[:find ?n ?a
                                 :where [?e :name ?n] [?e :age ?a]]
                               @conn)]
              (is (= #{["Charlie" 35]} (set results))))
            (finally
              (flow/pause fl)
              (flow/ping fl 2000)
              (flow/stop fl)))))))

  (testing "full flow: inject tx without correlation-id still writes data"
    (with-temp-conn
      (fn [conn]
        (let [fl (writer/create-writer-flow {::writer/conn conn
                                              ::writer/db-name "e2e-no-cid"})
              tx-msg {::writer/tx-data [{:name "Dana" :age 28}]}]
          (try
            (writer/inject-tx! {::writer/flow fl ::writer/tx-msg tx-msg})
            (Thread/sleep 500)
            ;; Data should be in DB even without promise
            (let [results (d/q '[:find ?n :where [?e :name ?n]] @conn)]
              (is (= #{["Dana"]} (set results))))
            (finally
              (flow/pause fl)
              (flow/ping fl 2000)
              (flow/stop fl))))))))
