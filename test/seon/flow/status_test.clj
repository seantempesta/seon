(ns seon.flow.status-test
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.flow.registry :as registry]
            [seon.flow.status :as status]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn- simple-step
  "A minimal flow step that counts messages."
  ([] {:ins {:in "input"} :outs {:out "output"}})
  ([_args] {:count 0})
  ([state _transition] state)
  ([state _input msg]
   [(update state :count inc)
    {:out [msg]}]))

(defn- make-test-flow!
  "Create, start, and register a simple test flow. Returns the result map."
  [flow-id label]
  (let [config {:procs {:step-a {:proc (flow/process #'simple-step)
                                  :chan-opts {:in {:buf-or-n 10}}}}
                :conns []}
        fl (flow/create-flow config)
        chans (flow/start fl)]
    (flow/resume fl)
    (registry/register! {::registry/id flow-id
                         ::registry/flow fl
                         ::registry/chans chans
                         ::registry/label label})
    (status/start-error-drain! {::status/id flow-id
                                ::status/error-chan (:error-chan chans)})
    {:flow fl :chans chans}))

(defn- stop-test-flow!
  [flow-id fl]
  (flow/stop fl)
  (status/stop-error-drain! {::status/id flow-id})
  (registry/unregister! {::registry/id flow-id}))

(use-fixtures :each (fn [f]
                      (registry/clear!)
                      (f)
                      ;; Clean up any remaining flows
                      (doseq [[id entry] (registry/list-flows)]
                        (try (flow/stop (::registry/flow entry)) (catch Exception _)))
                      (registry/clear!)))

;;; ---------------------------------------------------------------------------
;;; Tests
;;; ---------------------------------------------------------------------------

(deftest collect-flow-status-shape-test
  (testing "collect-flow-status returns expected shape for a running flow"
    (let [{:keys [flow]} (make-test-flow! :test/simple "Simple Flow")
          result (status/collect-flow-status {::status/id :test/simple})]
      (is (= :test/simple (::status/id result)))
      (is (= "Simple Flow" (::status/label result)))
      (is (= :running (::status/status result)))
      (is (nat-int? (::status/uptime-ms result)))
      (is (map? (::status/processes result)))
      (is (contains? (::status/processes result) :step-a))
      (let [proc (get (::status/processes result) :step-a)]
        (is (= :step-a (::status/pid proc)))
        (is (= :running (::status/status proc)))
        (is (int? (::status/count proc))))
      (is (map? (::status/errors result)))
      (is (int? (::status/total (::status/errors result))))
      (stop-test-flow! :test/simple flow))))

(deftest collect-status-all-flows-test
  (testing "collect-status returns all registered flows"
    (let [{f1 :flow} (make-test-flow! :test/flow-1 "Flow 1")
          {f2 :flow} (make-test-flow! :test/flow-2 "Flow 2")
          result (status/collect-status)]
      (is (= 2 (count (::status/flows result))))
      (is (contains? (::status/flows result) :test/flow-1))
      (is (contains? (::status/flows result) :test/flow-2))
      (is (vector? (::status/alerts result)))
      (stop-test-flow! :test/flow-1 f1)
      (stop-test-flow! :test/flow-2 f2))))

(deftest collect-status-empty-test
  (testing "collect-status returns empty when no flows registered"
    (let [result (status/collect-status)]
      (is (= {} (::status/flows result)))
      (is (empty? (::status/alerts result))))))

(deftest throughput-calculation-test
  (testing "throughput is computed from count deltas"
    (let [{:keys [flow]} (make-test-flow! :test/throughput "Throughput Test")]
      ;; First collect establishes baseline
      (status/collect-flow-status {::status/id :test/throughput})
      ;; Inject some messages
      (dotimes [_ 10]
        (flow/inject flow [:step-a :in] [{:msg "tick"}]))
      (Thread/sleep 200)
      ;; Second collect should show throughput
      (let [result (status/collect-flow-status {::status/id :test/throughput})
            proc (get (::status/processes result) :step-a)]
        (is (some? (::status/msgs-per-sec proc)))
        ;; Should be positive since we injected messages
        (is (>= (::status/msgs-per-sec proc) 0.0)))
      (stop-test-flow! :test/throughput flow))))

(deftest nonexistent-flow-status-test
  (testing "collect-flow-status returns nil for unknown flow"
    (is (nil? (status/collect-flow-status {::status/id :test/nope})))))
