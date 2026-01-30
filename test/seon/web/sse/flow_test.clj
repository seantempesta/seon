(ns seon.web.sse.flow-test
  "Tests for SSE Flow infrastructure.

  Tests cover:
  1. Schema validation
  2. Step function behavior in isolation
  3. Flow lifecycle (start/stop)
  4. Event injection and propagation
  5. Client registry operations"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [malli.core :as m]
            [seon.web.sse.flow :as sse-flow]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(defn flow-fixture
  "Ensure flow is stopped before and after each test."
  [f]
  (sse-flow/stop!)
  (try
    (f)
    (finally
      (sse-flow/stop!))))

(use-fixtures :each flow-fixture)

;;; ---------------------------------------------------------------------------
;;; Schema Tests
;;; ---------------------------------------------------------------------------

(deftest change-event-schema-test
  (testing "valid ChangeEvent"
    (is (m/validate sse-flow/ChangeEvent
                    {:seon.sse/event-type :namespace-reload
                     :seon.sse/namespace 'seon.trading.signals
                     :seon.sse/timestamp (java.time.Instant/now)})))

  (testing "ChangeEvent with file-path"
    (is (m/validate sse-flow/ChangeEvent
                    {:seon.sse/event-type :file-change
                     :seon.sse/file-path "src/seon/trading/signals.clj"
                     :seon.sse/timestamp (java.time.Instant/now)})))

  (testing "invalid event-type"
    (is (not (m/validate sse-flow/ChangeEvent
                         {:seon.sse/event-type :unknown
                          :seon.sse/timestamp (java.time.Instant/now)})))))

(deftest client-info-schema-test
  (testing "valid ClientInfo"
    (is (m/validate sse-flow/ClientInfo
                    {:seon.sse/client-id (random-uuid)
                     :seon.sse/connected-at (java.time.Instant/now)
                     :seon.sse/page :dashboard
                     :seon.sse/http-channel :mock-channel})))

  (testing "ClientInfo with params"
    (is (m/validate sse-flow/ClientInfo
                    {:seon.sse/client-id (random-uuid)
                     :seon.sse/connected-at (java.time.Instant/now)
                     :seon.sse/page :agent-detail
                     :seon.sse/page-params {:agent-id "a1b2"}
                     :seon.sse/http-channel :mock-channel})))

  (testing "invalid page"
    (is (not (m/validate sse-flow/ClientInfo
                         {:seon.sse/client-id (random-uuid)
                          :seon.sse/connected-at (java.time.Instant/now)
                          :seon.sse/page :invalid-page
                          :seon.sse/http-channel :mock-channel})))))

(deftest aggregated-update-schema-test
  (testing "valid AggregatedUpdate"
    (is (m/validate sse-flow/AggregatedUpdate
                    {:seon.sse/namespaces #{'seon.trading.signals 'seon.trading.execution}
                     :seon.sse/pages #{:dashboard}
                     :seon.sse/timestamp (java.time.Instant/now)})))

  (testing "empty sets are valid"
    (is (m/validate sse-flow/AggregatedUpdate
                    {:seon.sse/namespaces #{}
                     :seon.sse/pages #{}
                     :seon.sse/timestamp (java.time.Instant/now)}))))

;;; ---------------------------------------------------------------------------
;;; Helper Function Tests
;;; ---------------------------------------------------------------------------

(deftest namespace->page-test
  (testing "trading namespaces map to dashboard"
    (is (= :dashboard (sse-flow/namespace->page 'seon.trading.signals)))
    (is (= :dashboard (sse-flow/namespace->page 'seon.trading.execution))))

  (testing "AI namespaces map to agents"
    (is (= :agents (sse-flow/namespace->page 'seon.ai.claude)))
    (is (= :agents (sse-flow/namespace->page 'seon.ai.agent))))

  (testing "web.agents maps to agents"
    (is (= :agents (sse-flow/namespace->page 'seon.web.agents))))

  (testing "health namespaces map to dashboard"
    (is (= :dashboard (sse-flow/namespace->page 'seon.health.workouts))))

  (testing "web namespaces map to dashboard"
    (is (= :dashboard (sse-flow/namespace->page 'seon.web.handlers))))

  (testing "unknown namespaces return nil"
    (is (nil? (sse-flow/namespace->page 'some.random.namespace))))

  (testing "nil input returns nil"
    (is (nil? (sse-flow/namespace->page nil)))))

;;; ---------------------------------------------------------------------------
;;; Step Function Tests (Unit)
;;; ---------------------------------------------------------------------------

(deftest aggregator-step-describe-test
  (testing "describe returns expected structure"
    (let [desc (sse-flow/aggregator-step)]
      (is (contains? desc :ins))
      (is (contains? desc :outs))
      (is (contains? desc :params))
      (is (contains? (:ins desc) :changes))
      (is (contains? (:outs desc) :updates)))))

(deftest aggregator-step-init-test
  (testing "init with defaults"
    (let [state (sse-flow/aggregator-step {})]
      (is (= #{} (:pending-namespaces state)))
      (is (= #{} (:pending-pages state)))
      (is (= 50 (:debounce-ms state)))))

  (testing "init with custom debounce"
    (let [state (sse-flow/aggregator-step {:debounce-ms 100})]
      (is (= 100 (:debounce-ms state))))))

(deftest aggregator-step-transform-test
  (testing "first change after debounce period emits immediately"
    (let [initial-state {:pending-namespaces #{}
                         :pending-pages #{}
                         :last-change-ms 0
                         :debounce-ms 50}
          event {:seon.sse/event-type :namespace-reload
                 :seon.sse/namespace 'seon.trading.signals}
          [new-state output] (sse-flow/aggregator-step initial-state :changes event)]
      ;; Should emit immediately since debounce elapsed from 0
      (is (some? output))
      ;; The emitted update should contain the namespace
      (let [update (first (:updates output))]
        (is (contains? (:seon.sse/namespaces update) 'seon.trading.signals))
        (is (contains? (:seon.sse/pages update) :dashboard)))
      ;; State should be cleared after emit
      (is (= #{} (:pending-namespaces new-state)))
      (is (= #{} (:pending-pages new-state)))))

  (testing "rapid changes accumulate"
    (let [now (System/currentTimeMillis)
          state {:pending-namespaces #{'seon.trading.signals}
                 :pending-pages #{:dashboard}
                 :last-change-ms now
                 :debounce-ms 50}
          event {:seon.sse/event-type :namespace-reload
                 :seon.sse/namespace 'seon.trading.execution}
          [new-state output] (sse-flow/aggregator-step state :changes event)]
      ;; Should accumulate the new namespace
      (is (contains? (:pending-namespaces new-state) 'seon.trading.execution))
      ;; Shouldn't emit yet (within debounce window)
      (is (nil? output)))))

(deftest registry-step-describe-test
  (testing "describe returns expected structure"
    (let [desc (sse-flow/registry-step)]
      (is (contains? desc :ins))
      (is (contains? (:ins desc) :register))
      (is (contains? (:ins desc) :unregister)))))

(deftest registry-step-init-test
  (testing "init creates empty clients map"
    (let [state (sse-flow/registry-step {})]
      (is (= {} (:clients state))))))

(deftest registry-step-register-test
  (testing "register adds client to registry"
    (let [state {:clients {}}
          client-id (random-uuid)
          client {:seon.sse/client-id client-id
                  :seon.sse/page :dashboard
                  :seon.sse/http-channel :mock}
          [new-state output] (sse-flow/registry-step state :register client)]
      (is (= client (get-in new-state [:clients client-id])))
      (is (some? (::flow/report output))))))

(deftest registry-step-unregister-test
  (testing "unregister removes client from registry"
    (let [client-id (random-uuid)
          state {:clients {client-id {:seon.sse/client-id client-id
                                      :seon.sse/page :dashboard}}}
          [new-state output] (sse-flow/registry-step state :unregister {:seon.sse/client-id client-id})]
      (is (not (contains? (:clients new-state) client-id)))
      (is (some? (::flow/report output))))))

(deftest broadcaster-step-test
  (testing "broadcaster tracks broadcasts"
    (let [state {:broadcast-count 0 :last-broadcast-ms 0}
          update {:seon.sse/namespaces #{'seon.trading.signals}
                  :seon.sse/pages #{:dashboard}
                  :seon.sse/timestamp (java.time.Instant/now)}
          [new-state output] (sse-flow/broadcaster-step state :updates update)]
      (is (= 1 (:broadcast-count new-state)))
      (is (> (:last-broadcast-ms new-state) 0))
      (is (some? (:sent output))))))

;;; ---------------------------------------------------------------------------
;;; Flow Lifecycle Tests
;;; ---------------------------------------------------------------------------

(deftest flow-lifecycle-test
  (testing "flow starts and returns channels"
    (let [chans (sse-flow/start!)]
      (is (some? chans))
      (is (contains? chans :report-chan))
      (is (contains? chans :error-chan))
      (is (sse-flow/running?))))

  (testing "flow stops cleanly"
    (sse-flow/start!)
    (sse-flow/stop!)
    (is (not (sse-flow/running?))))

  (testing "multiple stops are safe"
    (sse-flow/start!)
    (sse-flow/stop!)
    (sse-flow/stop!)
    (is (not (sse-flow/running?))))

  (testing "start replaces existing flow"
    (sse-flow/start! :debounce-ms 100)
    (sse-flow/start! :debounce-ms 200)
    (is (sse-flow/running?))))

;;; ---------------------------------------------------------------------------
;;; Flow Integration Tests
;;; ---------------------------------------------------------------------------

(deftest ping-test
  (testing "ping returns process states when running"
    (sse-flow/start!)
    (let [result (sse-flow/ping :timeout-ms 500)]
      (is (some? result))
      (is (contains? result :aggregator))
      (is (contains? result :registry))
      (is (contains? result :broadcaster))))

  (testing "ping returns nil when not running"
    (sse-flow/stop!)
    (is (nil? (sse-flow/ping)))))

(deftest emit-change-test
  (testing "emit-change! returns future when running"
    (sse-flow/start!)
    (let [result (sse-flow/emit-change! {:seon.sse/event-type :namespace-reload
                                         :seon.sse/namespace 'seon.trading.signals})]
      (is (future? result))
      ;; Wait for injection to complete
      @result))

  (testing "emit-change! returns nil when not running"
    (sse-flow/stop!)
    (is (nil? (sse-flow/emit-change! {:seon.sse/event-type :manual-refresh})))))

(deftest client-registration-test
  (testing "register and unregister client"
    (sse-flow/start!)
    (let [client-id (random-uuid)
          client-info {:seon.sse/client-id client-id
                       :seon.sse/connected-at (java.time.Instant/now)
                       :seon.sse/page :dashboard
                       :seon.sse/http-channel :mock}]
      ;; Register
      @(sse-flow/register-client! client-info)
      ;; Give flow time to process
      (Thread/sleep 50)

      ;; Check registry state
      (let [registry-state (sse-flow/connected-clients :timeout-ms 500)]
        (is (some? registry-state))
        ;; ping-proc returns namespaced keys from flow
        (is (contains? (:clojure.core.async.flow/state registry-state) :clients)))

      ;; Unregister
      @(sse-flow/unregister-client! client-id)
      (Thread/sleep 50))))

(deftest connected-clients-test
  (testing "returns nil when not running"
    (sse-flow/stop!)
    (is (nil? (sse-flow/connected-clients))))

  (testing "returns registry state when running"
    (sse-flow/start!)
    (let [result (sse-flow/connected-clients :timeout-ms 500)]
      (is (some? result))
      ;; ping-proc returns namespaced keys from flow
      (is (contains? result :clojure.core.async.flow/state)))))

(deftest get-channels-test
  (testing "returns channels when running"
    (let [chans (sse-flow/start!)]
      (is (= chans (sse-flow/get-channels)))))

  (testing "returns nil when not running"
    (sse-flow/stop!)
    (is (nil? (sse-flow/get-channels)))))

;;; ---------------------------------------------------------------------------
;;; Error Handling Tests
;;; ---------------------------------------------------------------------------

(deftest error-channel-test
  (testing "error channel is available for monitoring"
    (let [{:keys [error-chan]} (sse-flow/start!)]
      (is (some? error-chan))
      ;; Shouldn't have errors immediately
      (let [error (async/poll! error-chan)]
        (is (nil? error))))))
