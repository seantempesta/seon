(ns seon.db.datalevin.writer-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [seon.db.datalevin.writer :as writer]
            [seon.flow.msg :as msg]
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
  (testing "infra-writer returns ins, outs, and workload"
    (let [desc (writer/infra-writer-step)]
      (is (contains? (:ins desc) :seon.flow.in/request))
      (is (contains? (:outs desc) :seon.flow.out/reply))
      (is (contains? (:outs desc) :seon.flow.out/error))
      (is (= :io (:workload desc))))))

;;; ---------------------------------------------------------------------------
;;; Init (1-arity)
;;; ---------------------------------------------------------------------------

(deftest init-test
  (testing "infra-writer initializes state with zeroed counters"
    (let [cm {:fake "manager"}
          state (writer/infra-writer-step {::writer/connection-manager cm})]
      (is (= cm (::writer/connection-manager state)))
      (is (= {} (::writer/owned-conns state)))
      (is (= 0 (::writer/total-writes state)))
      (is (= 0 (::writer/total-errors state)))
      (is (nil? (::writer/last-write-at state))))))

;;; ---------------------------------------------------------------------------
;;; Transform — successful write via raw conn
;;; ---------------------------------------------------------------------------

(deftest transform-write-test
  (testing "writes tx-data via raw conn and emits reply on :seon.flow.out/reply"
    (with-temp-conn
      (fn [conn]
        (let [request-id (random-uuid)
              state (writer/infra-writer-step {::writer/connection-manager {}})
              request {::msg/id request-id
                       ::msg/version 1
                       ::msg/type :request
                       ::msg/from-ns "test"
                       ::msg/payload {::writer/tx-data [{:name "Alice" :age 30}]
                                      ::writer/conn conn}
                       ::msg/created-at (Instant/now)}
              [state' outputs] (writer/infra-writer-step state :seon.flow.in/request request)]
          ;; State updated
          (is (= 1 (::writer/total-writes state')))
          (is (= 0 (::writer/total-errors state')))
          (is (instance? Instant (::writer/last-write-at state')))
          ;; Reply emitted
          (is (some? outputs))
          (let [reply (first (:seon.flow.out/reply outputs))]
            (is (= :ok (::msg/status reply)))
            (is (= request-id (::msg/id reply))))
          ;; Data in DB
          (let [results (d/q '[:find ?n ?a
                               :where [?e :name ?n] [?e :age ?a]]
                             @conn)]
            (is (= #{["Alice" 30]} (set results)))))))))

;;; ---------------------------------------------------------------------------
;;; Transform — error emits reply with :error status
;;; ---------------------------------------------------------------------------

(deftest transform-error-test
  (testing "handles invalid tx-data and emits error reply"
    (with-temp-conn
      (fn [conn]
        (let [request-id (random-uuid)
              state (writer/infra-writer-step {::writer/connection-manager {}})
              request {::msg/id request-id
                       ::msg/version 1
                       ::msg/type :request
                       ::msg/from-ns "test"
                       ::msg/payload {::writer/tx-data [:not-a-valid-transaction]
                                      ::writer/conn conn}
                       ::msg/created-at (Instant/now)}
              [state' outputs] (writer/infra-writer-step state :seon.flow.in/request request)]
          (is (= 1 (::writer/total-errors state')))
          (is (= 0 (::writer/total-writes state')))
          (let [reply (first (:seon.flow.out/reply outputs))]
            (is (= :error (::msg/status reply)))
            (is (= request-id (::msg/id reply)))))))))

;;; ---------------------------------------------------------------------------
;;; Transform — unknown input
;;; ---------------------------------------------------------------------------

(deftest transform-unknown-input-test
  (testing "unknown input-id returns state unchanged with nil outputs"
    (let [state (writer/infra-writer-step {::writer/connection-manager {}})
          [state' outputs] (writer/infra-writer-step state :in/unknown {:data 1})]
      (is (= state state'))
      (is (nil? outputs)))))

;;; ---------------------------------------------------------------------------
;;; Transitions
;;; ---------------------------------------------------------------------------

(deftest transition-test
  (testing "pause returns state unchanged"
    (let [state (writer/infra-writer-step {::writer/connection-manager {}})
          state' (writer/infra-writer-step state :clojure.core.async.flow/pause)]
      (is (= state state'))))

  (testing "stop closes owned connections and empties map"
    (with-temp-conn
      (fn [conn]
        (let [state (-> (writer/infra-writer-step {::writer/connection-manager {}})
                        (assoc ::writer/owned-conns {:test-db conn}))
              state' (writer/infra-writer-step state :clojure.core.async.flow/stop)]
          (is (= {} (::writer/owned-conns state')))))))

  (testing "resume returns state unchanged"
    (let [state (writer/infra-writer-step {::writer/connection-manager {}})
          state' (writer/infra-writer-step state :clojure.core.async.flow/resume)]
      (is (= state state')))))
