(ns seon.flow.harness.bridge-test
  (:require [clojure.core.async.flow :as flow]
            [clojure.test :refer [deftest is testing]]
            [seon.flow.harness.bridge :as bridge]
            [seon.flow.msg :as msg]))

;;; ---------------------------------------------------------------------------
;;; Test helper functions (targets for bridge execution)
;;; ---------------------------------------------------------------------------

(defn add-numbers
  "Simple test function that adds two numbers."
  [a b]
  (+ a b))

(defn throw-on-call
  "Test function that always throws."
  [msg]
  (throw (ex-info msg {:test-data true})))

(defn return-unserializable
  "Test function that returns something not EDN-serializable."
  []
  (Object.))

;;; ---------------------------------------------------------------------------
;;; execute-local tests
;;; ---------------------------------------------------------------------------

(defn- make-request
  "Build a minimal request envelope for testing."
  [fn-name args]
  {::msg/id       (random-uuid)
   ::msg/version  1
   ::msg/type     :request
   ::msg/from-ns  "seon.test.caller"
   ::msg/to-ns    "seon.flow.harness.bridge-test"
   ::msg/fn       fn-name
   ::msg/args     args
   ::msg/created-at (java.time.Instant/now)})

(def ^:private test-state
  {::bridge/namespace "seon.flow.harness.bridge-test"})

(deftest execute-local-happy-path-test
  (testing "calls a known function and returns :ok reply"
    (let [req   (make-request "seon.flow.harness.bridge-test/add-numbers" [3 4])
          reply (bridge/execute-local req test-state)]
      (is (= :ok (::msg/status reply)))
      (is (= 7 (::msg/value reply)))
      (is (= (::msg/id req) (::msg/id reply)))
      (is (= 1 (::msg/version reply)))
      (is (= :reply (::msg/type reply)))
      (is (int? (::msg/duration-ms reply)))
      (is (>= (::msg/duration-ms reply) 0)))))

(deftest execute-local-not-found-test
  (testing "returns :not-found for non-existent function"
    (let [req   (make-request "seon.no.such.ns/missing-fn" [])
          reply (bridge/execute-local req test-state)]
      (is (= :error (::msg/status reply)))
      (is (= :not-found (::msg/error-type reply)))
      (is (string? (::msg/error-message reply)))
      (is (int? (::msg/duration-ms reply))))))

(deftest execute-local-execution-error-test
  (testing "returns :execution error when function throws"
    (let [req   (make-request "seon.flow.harness.bridge-test/throw-on-call" ["boom"])
          reply (bridge/execute-local req test-state)]
      (is (= :error (::msg/status reply)))
      (is (= :execution (::msg/error-type reply)))
      (is (= "boom" (::msg/error-message reply)))
      (is (= "clojure.lang.ExceptionInfo" (::msg/error-class reply)))
      (is (= {:test-data true} (::msg/error-data reply)))
      (is (int? (::msg/duration-ms reply))))))

(deftest execute-local-serialization-error-test
  (testing "returns :serialization error for non-EDN-serializable result"
    (let [req   (make-request "seon.flow.harness.bridge-test/return-unserializable" [])
          reply (bridge/execute-local req test-state)]
      (is (= :error (::msg/status reply)))
      (is (= :serialization (::msg/error-type reply)))
      (is (string? (::msg/error-message reply)))
      (is (int? (::msg/duration-ms reply))))))

(deftest execute-local-trace-id-test
  (testing "trace-id is echoed in reply when present"
    (let [trace  (random-uuid)
          req    (assoc (make-request "seon.flow.harness.bridge-test/add-numbers" [1 2])
                        ::msg/trace-id trace)
          reply  (bridge/execute-local req test-state)]
      (is (= trace (::msg/trace-id reply))))))

;;; ---------------------------------------------------------------------------
;;; bridge-step arity tests
;;; ---------------------------------------------------------------------------

(deftest bridge-step-describe-test
  (testing "0-arity returns descriptor with params and workload"
    (let [desc (bridge/bridge-step)]
      (is (map? (:params desc)))
      (is (= :io (:workload desc))))))

(deftest bridge-step-init-test
  (testing "1-arity returns state with namespace"
    (let [state (bridge/bridge-step {::bridge/namespace "seon.test.alpha"
                                     ::bridge/bridge-port 9999})]
      (is (= "seon.test.alpha" (::bridge/namespace state))))))

(deftest bridge-step-transition-test
  (testing "2-arity handles transitions without error"
    (let [state {::bridge/namespace "test"}]
      (is (= state (bridge/bridge-step state ::flow/resume)))
      (is (= state (bridge/bridge-step state ::flow/stop)))
      (is (= state (bridge/bridge-step state ::flow/pause))))))

(deftest bridge-step-transform-test
  (testing "3-arity processes request and returns reply"
    (let [state {::bridge/namespace "seon.flow.harness.bridge-test"}
          req   (make-request "seon.flow.harness.bridge-test/add-numbers" [10 20])
          [new-state output] (bridge/bridge-step state :seon.flow.in/request req)]
      (is (= state new-state))
      (is (vector? (:seon.flow.out/reply output)))
      (let [reply (first (:seon.flow.out/reply output))]
        (is (= :ok (::msg/status reply)))
        (is (= 30 (::msg/value reply))))))

  (testing "3-arity returns nil output for unknown input-id"
    (let [state {::bridge/namespace "test"}
          [new-state output] (bridge/bridge-step state :unknown {:foo 1})]
      (is (= state new-state))
      (is (nil? output)))))

(deftest duration-ms-is-reasonable-test
  (testing "duration-ms is non-negative and reasonable for a fast function"
    (let [req   (make-request "seon.flow.harness.bridge-test/add-numbers" [1 1])
          reply (bridge/execute-local req test-state)]
      (is (>= (::msg/duration-ms reply) 0))
      (is (< (::msg/duration-ms reply) 1000)))))
