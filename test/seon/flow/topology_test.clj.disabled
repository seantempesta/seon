(ns seon.flow.topology-test
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.flow.msg :as msg]
            [seon.flow.topology :as topology])
  (:import [java.time Instant]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn- make-reply
  "Build a reply envelope for a given request-id."
  [request-id & {:keys [status value error-type error-message from-ns]
                 :or {status :ok value {:result 42} from-ns "seon.test.beta"}}]
  (cond-> {::msg/id request-id
           ::msg/version 1
           ::msg/type :reply
           ::msg/status status
           ::msg/from-ns from-ns
           ::msg/duration-ms 5}
    (= status :ok) (assoc ::msg/value value)
    error-type (assoc ::msg/error-type error-type)
    error-message (assoc ::msg/error-message error-message)))

(defn- clean-promises!
  "Reset pending promises between tests."
  []
  (reset! topology/pending-promises {}))

(use-fixtures :each (fn [f] (clean-promises!) (f) (clean-promises!)))

;;; ---------------------------------------------------------------------------
;;; Reply Router Step — Unit Tests
;;; ---------------------------------------------------------------------------

(deftest reply-router-describe-test
  (testing "describe returns ins and workload"
    (let [desc (topology/reply-router-step)]
      (is (contains? (:ins desc) :seon.flow.in/reply))
      (is (= {} (:outs desc)))
      (is (= :io (:workload desc))))))

(deftest reply-router-init-test
  (testing "init returns zeroed counters"
    (let [state (topology/reply-router-step {})]
      (is (= 0 (::topology/delivered state)))
      (is (= 0 (::topology/unmatched state))))))

(deftest reply-router-deliver-test
  (testing "reply is delivered to registered promise"
    (let [request-id (random-uuid)
          p (promise)
          _ (swap! topology/pending-promises assoc request-id p)
          state (topology/reply-router-step {})
          reply (make-reply request-id)
          [state' _outputs] (topology/reply-router-step state :seon.flow.in/reply reply)]
      (is (= 1 (::topology/delivered state')))
      (is (realized? p))
      (is (= :ok (::msg/status @p)))
      ;; Promise should be removed from pending
      (is (nil? (get @topology/pending-promises request-id))))))

(deftest reply-router-unmatched-test
  (testing "unmatched reply increments counter and reports"
    (let [state (topology/reply-router-step {})
          reply (make-reply (random-uuid))
          [state' outputs] (topology/reply-router-step state :seon.flow.in/reply reply)]
      (is (= 1 (::topology/unmatched state')))
      (is (= 0 (::topology/delivered state')))
      (is (some? (::flow/report outputs))))))

;;; ---------------------------------------------------------------------------
;;; Full Topology — Integration Tests
;;; ---------------------------------------------------------------------------

(defn- build-echo-topology!
  "Build a topology where requests are echoed back as replies via mock channels.

   For each namespace, creates async channels that simulate an agent JVM:
   a go-loop reads requests from out-ch and puts replies on in-ch."
  [ns-configs]
  (let [;; For each ns, create mock JVM channels
        mock-jvms
        (into {}
              (map (fn [[ns-str config]]
                     (let [;; Channel harness sends requests TO (the jvm-request out-port)
                           jvm-request-ch (async/chan 32)
                           ;; Channel harness reads replies FROM (the jvm-reply in-port)
                           jvm-reply-ch (async/chan 32)]
                       ;; Echo loop: read request, write reply
                       (async/go-loop []
                         (when-let [req (async/<! jvm-request-ch)]
                           (let [reply {::msg/id (::msg/id req)
                                        ::msg/version 1
                                        ::msg/type :reply
                                        ::msg/status :ok
                                        ::msg/value {:echo (::msg/args req)}
                                        ::msg/from-ns ns-str
                                        ::msg/duration-ms 1}]
                             (async/>! jvm-reply-ch reply))
                           (recur)))
                       [ns-str (merge config
                                      {::jvm-request-ch jvm-request-ch
                                       ::jvm-reply-ch jvm-reply-ch})])))
              ns-configs)

        ;; Build topology with mock channels as in-ports/out-ports
        namespaces
        (into {}
              (map (fn [[ns-str {:keys [::jvm-request-ch ::jvm-reply-ch] :as config}]]
                     [ns-str (merge (dissoc config ::jvm-request-ch ::jvm-reply-ch)
                                    {:seon.flow.harness/in-ports
                                     {:seon.flow.in/jvm-reply jvm-reply-ch}
                                     :seon.flow.harness/out-ports
                                     {:seon.flow.out/jvm-request jvm-request-ch}})]))
              mock-jvms)

        topo (topology/build-topology! {::topology/namespaces namespaces})]
    (assoc topo ::mock-jvms mock-jvms)))

(defn- stop-echo-topology!
  "Stop topology and close mock channels."
  [{::keys [mock-jvms] :as topo}]
  (topology/stop-topology! topo)
  (doseq [[_ {:keys [::jvm-request-ch ::jvm-reply-ch]}] mock-jvms]
    (async/close! jvm-request-ch)
    (async/close! jvm-reply-ch)))

(deftest request-happy-path-test
  (testing "request! returns value through echo topology"
    (let [topo (build-echo-topology! {"seon.test.beta" {}})]
      (try
        ;; Small delay for flow to start
        (Thread/sleep 100)
        (let [result (topology/request!
                      {::topology/flow (::topology/flow topo)
                       ::topology/target-ns "seon.test.beta"
                       ::topology/fn "seon.test.beta/format-name"
                       ::topology/args [{:name "sean"}]
                       ::topology/timeout-ms 5000})]
          (is (= {:echo [{:name "sean"}]} result)))
        (finally
          (stop-echo-topology! topo))))))

(deftest request-timeout-test
  (testing "request! throws on timeout when no reply comes"
    ;; Build topology with a black-hole (no echo loop)
    (let [jvm-request-ch (async/chan 32)
          jvm-reply-ch (async/chan 32)
          ;; No echo loop — requests go in, nothing comes back
          topo (topology/build-topology!
                {::topology/namespaces
                 {"seon.test.slow"
                  {:seon.flow.harness/in-ports
                   {:seon.flow.in/jvm-reply jvm-reply-ch}
                   :seon.flow.harness/out-ports
                   {:seon.flow.out/jvm-request jvm-request-ch}}}})]
      (try
        (Thread/sleep 100)
        (let [ex (try
                   (topology/request!
                    {::topology/flow (::topology/flow topo)
                     ::topology/target-ns "seon.test.slow"
                     ::topology/fn "seon.test.slow/noop"
                     ::topology/args []
                     ::topology/timeout-ms 200})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "Should have thrown")
          (is (= :timeout (::msg/error-type (ex-data ex)))))
        (finally
          (topology/stop-topology! topo)
          (async/close! jvm-request-ch)
          (async/close! jvm-reply-ch))))))

(deftest request-error-reply-test
  (testing "request! throws when reply has error status"
    ;; Build topology with error-returning mock
    (let [jvm-request-ch (async/chan 32)
          jvm-reply-ch (async/chan 32)
          ;; Error echo loop
          _ (async/go-loop []
              (when-let [req (async/<! jvm-request-ch)]
                (async/>! jvm-reply-ch
                          {::msg/id (::msg/id req)
                           ::msg/version 1
                           ::msg/type :reply
                           ::msg/status :error
                           ::msg/error-type :execution
                           ::msg/error-message "Something broke"
                           ::msg/from-ns "seon.test.err"
                           ::msg/duration-ms 2})
                (recur)))
          topo (topology/build-topology!
                {::topology/namespaces
                 {"seon.test.err"
                  {:seon.flow.harness/in-ports
                   {:seon.flow.in/jvm-reply jvm-reply-ch}
                   :seon.flow.harness/out-ports
                   {:seon.flow.out/jvm-request jvm-request-ch}}}})]
      (try
        (Thread/sleep 100)
        (let [ex (try
                   (topology/request!
                    {::topology/flow (::topology/flow topo)
                     ::topology/target-ns "seon.test.err"
                     ::topology/fn "seon.test.err/fail"
                     ::topology/args []
                     ::topology/timeout-ms 5000})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "Should have thrown")
          (is (= :error (::msg/status (ex-data ex))))
          (is (= :execution (::msg/error-type (ex-data ex))))
          (is (= "Something broke" (.getMessage ex))))
        (finally
          (topology/stop-topology! topo)
          (async/close! jvm-request-ch)
          (async/close! jvm-reply-ch))))))

(deftest request-overload-test
  (testing "request! throws overload when queue is full"
    ;; Use queue-cap 1 and a slow mock that never replies
    (let [jvm-request-ch (async/chan 32)
          jvm-reply-ch (async/chan 32)
          ;; No echo loop — requests pile up
          topo (topology/build-topology!
                {::topology/namespaces
                 {"seon.test.full"
                  {:seon.flow.harness/queue-cap 1
                   :seon.flow.harness/in-ports
                   {:seon.flow.in/jvm-reply jvm-reply-ch}
                   :seon.flow.harness/out-ports
                   {:seon.flow.out/jvm-request jvm-request-ch}}}})]
      (try
        (Thread/sleep 100)
        ;; First request fills the queue (pending=1, cap=1)
        ;; It won't get a reply so it'll timeout, but we don't wait for it
        (let [p1 (future
                   (try
                     (topology/request!
                      {::topology/flow (::topology/flow topo)
                       ::topology/target-ns "seon.test.full"
                       ::topology/fn "seon.test.full/slow"
                       ::topology/args []
                       ::topology/timeout-ms 3000})
                     (catch Exception _e :timed-out)))]
          ;; Small delay for first request to be processed
          (Thread/sleep 200)
          ;; Second request should get overload
          (let [ex (try
                     (topology/request!
                      {::topology/flow (::topology/flow topo)
                       ::topology/target-ns "seon.test.full"
                       ::topology/fn "seon.test.full/slow"
                       ::topology/args []
                       ::topology/timeout-ms 2000})
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
            (is (some? ex) "Should have thrown overload")
            (is (= :overload (::msg/error-type (ex-data ex)))))
          ;; Cancel first future
          (future-cancel p1))
        (finally
          (topology/stop-topology! topo)
          (async/close! jvm-request-ch)
          (async/close! jvm-reply-ch))))))

(deftest multiple-namespaces-test
  (testing "topology supports multiple namespaces"
    (let [topo (build-echo-topology!
                {"seon.test.alpha" {}
                 "seon.test.beta" {}})]
      (try
        (Thread/sleep 100)
        (let [r1 (topology/request!
                  {::topology/flow (::topology/flow topo)
                   ::topology/target-ns "seon.test.alpha"
                   ::topology/fn "seon.test.alpha/greet"
                   ::topology/args ["hello"]
                   ::topology/timeout-ms 5000})
              r2 (topology/request!
                  {::topology/flow (::topology/flow topo)
                   ::topology/target-ns "seon.test.beta"
                   ::topology/fn "seon.test.beta/format"
                   ::topology/args ["world"]
                   ::topology/timeout-ms 5000})]
          (is (= {:echo ["hello"]} r1))
          (is (= {:echo ["world"]} r2)))
        (finally
          (stop-echo-topology! topo))))))

;;; ---------------------------------------------------------------------------
;;; Cycle Detection Tests
;;; ---------------------------------------------------------------------------

(deftest detect-cycles-no-cycles-test
  (testing "detect-cycles returns nil when no cycles exist"
    (let [configs {"seon.health.lifting" {}
                   "seon.health.nutrition" {}}]
      (is (nil? (topology/detect-cycles configs))))))

(deftest detect-cycles-direct-cycle-test
  (testing "detect-cycles detects direct cycle (A→B→A)"
    (let [configs {"seon.health.lifting"
                   {::topology/proxy-targets #{"seon.health.nutrition"}}
                   "seon.health.nutrition"
                   {::topology/proxy-targets #{"seon.health.lifting"}}}]
      (let [cycles (topology/detect-cycles configs)]
        (is (some? cycles))
        (is (= 1 (count cycles)))
        ;; Cycle should contain both namespaces (path includes the closing node)
        (let [cycle (first cycles)]
          (is (= 3 (count cycle)))
          (is (contains? (set cycle) "seon.health.lifting"))
          (is (contains? (set cycle) "seon.health.nutrition"))
          ;; First and last should be the same (cycle closure)
          (is (= (first cycle) (last cycle))))))))

(deftest detect-cycles-indirect-cycle-test
  (testing "detect-cycles detects indirect cycle (A→B→C→A)"
    (let [configs {"seon.health.lifting"
                   {::topology/proxy-targets #{"seon.health.nutrition"}}
                   "seon.health.nutrition"
                   {::topology/proxy-targets #{"seon.health.recovery"}}
                   "seon.health.recovery"
                   {::topology/proxy-targets #{"seon.health.lifting"}}}]
      (let [cycles (topology/detect-cycles configs)]
        (is (some? cycles))
        (is (>= (count cycles) 1))
        ;; At least one cycle with 4 elements (3 nodes + closing node)
        (is (some (fn [cycle] (= 4 (count cycle))) cycles))))))

(deftest detect-cycles-no-cycle-in-larger-graph-test
  (testing "detect-cycles finds no cycle in larger acyclic graph"
    (let [configs {"seon.health.lifting" {::topology/proxy-targets #{"seon.health.nutrition"}}
                   "seon.health.nutrition" {::topology/proxy-targets #{"seon.health.recovery"}}
                   "seon.health.recovery" {}
                   "seon.trading.signals" {::topology/proxy-targets #{"seon.trading.execution"}}
                   "seon.trading.execution" {}}]
      (is (nil? (topology/detect-cycles configs))))))

(deftest detect-cycles-missing-proxy-targets-test
  (testing "detect-cycles treats missing ::proxy-targets as empty set"
    (let [configs {"seon.health.lifting" {}
                   "seon.health.nutrition" {}}]
      (is (nil? (topology/detect-cycles configs))))))

(deftest detect-cycles-self-loop-test
  (testing "detect-cycles detects self-referential cycle (A→A)"
    (let [configs {"seon.health.lifting"
                   {::topology/proxy-targets #{"seon.health.lifting"}}}]
      (let [cycles (topology/detect-cycles configs)]
        (is (some? cycles))))))

(deftest build-topology-rejects-cycles-test
  (testing "build-topology! throws when cycles detected"
    (let [jvm-request-ch (async/chan 32)
          jvm-reply-ch (async/chan 32)]
      (try
        (let [ex (try
                   (topology/build-topology!
                    {::topology/namespaces
                     {"seon.health.lifting"
                      {::topology/proxy-targets #{"seon.health.nutrition"}
                       :seon.flow.harness/in-ports
                       {:seon.flow.in/jvm-reply jvm-reply-ch}
                       :seon.flow.harness/out-ports
                       {:seon.flow.out/jvm-request jvm-request-ch}}
                      "seon.health.nutrition"
                      {::topology/proxy-targets #{"seon.health.lifting"}
                       :seon.flow.harness/in-ports
                       {:seon.flow.in/jvm-reply jvm-reply-ch}
                       :seon.flow.harness/out-ports
                       {:seon.flow.out/jvm-request jvm-request-ch}}}})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "Should have thrown")
          (is (= true (get (ex-data ex) ::topology/cycle-detected)))
          (is (some? (get (ex-data ex) ::topology/cycles))))
        (finally
          (async/close! jvm-request-ch)
          (async/close! jvm-reply-ch))))))
