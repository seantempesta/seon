(ns seon.flow.pool-integration-test
  "Integration tests for flow harness with real pool JVMs.

   These tests spawn actual agent JVM processes and communicate via TCP.
   They are slower (~15-20s) and require the agent classpath to be available.

   Marked as :integration metadata so they can be excluded from fast test runs."
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.flow.harness :as harness]
            [seon.flow.harness.channel :as channel]
            [seon.flow.msg :as msg]
            [seon.flow.pool :as pool]
            [seon.flow.topology :as topology]))

;;; ---------------------------------------------------------------------------
;;; Pool fixture — create a small pool, shut down after tests
;;; ---------------------------------------------------------------------------

(def ^:private test-pool (atom nil))

(defn- pool-available?
  "Check if we can create a pool (agent classpath must be available)."
  []
  (try
    (let [p (pool/create-pool! {::pool/size 1 ::pool/base-port 7950})]
      (if (pool/await-warm p 20000)
        (do (reset! test-pool p) true)
        (do (pool/shutdown! p) false)))
    (catch Exception _
      false)))

(use-fixtures :once
  (fn [f]
    (if (pool-available?)
      (try
        (f)
        (finally
          (when-let [p @test-pool]
            (pool/shutdown! p)
            (reset! test-pool nil))))
      (println "SKIP: Pool integration tests (agent JVMs unavailable)"))))

;;; ---------------------------------------------------------------------------
;;; Test 1: start-namespace-jvm! basic round-trip
;;; ---------------------------------------------------------------------------

(deftest ^:integration start-namespace-jvm-basic-test
  (testing "acquire JVM, start TCP bridge, send request, get reply"
    (when-let [p @test-pool]
      (let [ns-handle (harness/start-namespace-jvm!
                        {::harness/pool p
                         ::harness/namespace "seon.flow.pool-integration-test"})]
        (try
          (let [out-ch (get-in ns-handle [::harness/out-ports :seon.flow.out/jvm-request])
                in-ch  (get-in ns-handle [::harness/in-ports :seon.flow.in/jvm-reply])
                ;; Send a request to resolve a core clojure function
                request {::msg/id (random-uuid)
                         ::msg/version 1
                         ::msg/type :request
                         ::msg/from-ns "orchestrator"
                         ::msg/to-ns "seon.flow.pool-integration-test"
                         ::msg/fn "clojure.core/+"
                         ::msg/args [1 2 3]
                         ::msg/created-at (java.time.Instant/now)}]
            ;; Send request through the out-port (to JVM)
            (async/>!! out-ch request)
            ;; Read reply from in-port (from JVM)
            (let [reply (async/alt!!
                          in-ch ([v] v)
                          (async/timeout 10000) :timeout)]
              (is (not= :timeout reply) "Should get reply within 10s")
              (is (= :ok (::msg/status reply)))
              (is (= 6 (::msg/value reply)))
              (is (= (::msg/id request) (::msg/id reply)))))
          (finally
            (harness/stop-namespace-jvm! ns-handle)))))))

;;; ---------------------------------------------------------------------------
;;; Test 2: Error propagation through real JVM
;;; ---------------------------------------------------------------------------

(deftest ^:integration real-jvm-error-propagation-test
  (testing "calling non-existent function returns :not-found error"
    (when-let [p @test-pool]
      (let [ns-handle (harness/start-namespace-jvm!
                        {::harness/pool p
                         ::harness/namespace "seon.flow.pool-integration-test"})]
        (try
          (let [out-ch (get-in ns-handle [::harness/out-ports :seon.flow.out/jvm-request])
                in-ch  (get-in ns-handle [::harness/in-ports :seon.flow.in/jvm-reply])
                request {::msg/id (random-uuid)
                         ::msg/version 1
                         ::msg/type :request
                         ::msg/from-ns "orchestrator"
                         ::msg/to-ns "seon.flow.pool-integration-test"
                         ::msg/fn "no.such.ns/no-fn"
                         ::msg/args []
                         ::msg/created-at (java.time.Instant/now)}]
            (async/>!! out-ch request)
            (let [reply (async/alt!!
                          in-ch ([v] v)
                          (async/timeout 10000) :timeout)]
              (is (not= :timeout reply))
              (is (= :error (::msg/status reply)))
              (is (= :not-found (::msg/error-type reply)))))
          (finally
            (harness/stop-namespace-jvm! ns-handle)))))))

;;; ---------------------------------------------------------------------------
;;; Test 3: Full topology with real JVM
;;; ---------------------------------------------------------------------------

(deftest ^:integration real-jvm-topology-test
  (testing "build-topology! with real JVM, request! round-trip"
    (when-let [p @test-pool]
      (let [ns-handle (harness/start-namespace-jvm!
                        {::harness/pool p
                         ::harness/namespace "seon.flow.pool-integration-test"})
            topo (topology/build-topology!
                   {::topology/namespaces
                    {"seon.flow.pool-integration-test"
                     {::harness/in-ports (::harness/in-ports ns-handle)
                      ::harness/out-ports (::harness/out-ports ns-handle)}}})]
        (try
          (Thread/sleep 200)
          ;; Call clojure.core/+ through the topology
          (let [result (topology/request!
                         {::topology/flow (::topology/flow topo)
                          ::topology/target-ns "seon.flow.pool-integration-test"
                          ::topology/fn "clojure.core/+"
                          ::topology/args [10 20 30]
                          ::topology/timeout-ms 10000})]
            (is (= 60 result)))

          ;; Call clojure.core/str
          (let [result (topology/request!
                         {::topology/flow (::topology/flow topo)
                          ::topology/target-ns "seon.flow.pool-integration-test"
                          ::topology/fn "clojure.core/str"
                          ::topology/args ["hello" " " "world"]
                          ::topology/timeout-ms 10000})]
            (is (= "hello world" result)))

          (finally
            (topology/stop-topology! topo)
            (harness/stop-namespace-jvm! ns-handle)))))))
