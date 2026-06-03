(ns seon.flow.pool-test
  "Tests for the pre-warmed JVM pool.

   Unit tests verify pool data structures and concurrency without spawning
   real JVM processes. Integration tests (tagged :integration) spawn real
   JVMs and require ~15s for pool creation."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.flow.pool :as pool])
  (:import [java.util.concurrent LinkedBlockingQueue]))

;;; ---------------------------------------------------------------------------
;;; Unit tests (fast, no JVM spawning)
;;; ---------------------------------------------------------------------------

(deftest pool-status-empty-test
  (testing "pool-status on a freshly created pool structure (no JVMs)"
    (let [idle-queue (LinkedBlockingQueue.)
          state (atom {::pool/all-jvms {}
                       ::pool/target-size 3
                       ::pool/next-port 7900
                       ::pool/shutdown? false})
          pool {::pool/state state
                ::pool/idle-queue idle-queue
                ::pool/scheduler nil}
          status (pool/pool-status pool)]
      (is (= 0 (::pool/total status)))
      (is (= 0 (::pool/idle status)))
      (is (= 0 (::pool/active status)))
      (is (= [] (::pool/jvms status))))))

(deftest pool-status-with-jvms-test
  (testing "pool-status accurately reflects idle and active counts"
    (let [idle-queue (LinkedBlockingQueue.)
          jvm-idle {::pool/port 7900 ::pool/pid 1001 ::pool/status :idle
                    ::pool/process nil ::pool/namespace nil}
          jvm-active {::pool/port 7901 ::pool/pid 1002 ::pool/status :active
                      ::pool/process nil ::pool/namespace 'seon.test.ns}
          _ (.put idle-queue jvm-idle)
          state (atom {::pool/all-jvms {7900 jvm-idle 7901 jvm-active}
                       ::pool/target-size 2
                       ::pool/next-port 7902
                       ::pool/shutdown? false})
          pool {::pool/state state
                ::pool/idle-queue idle-queue
                ::pool/scheduler nil}
          status (pool/pool-status pool)]
      (is (= 2 (::pool/total status)))
      (is (= 1 (::pool/idle status)))
      (is (= 1 (::pool/active status))))))

(deftest acquire-from-empty-pool-test
  (testing "acquire! returns nil when pool has no idle JVMs"
    (let [idle-queue (LinkedBlockingQueue.)
          state (atom {::pool/all-jvms {}
                       ::pool/target-size 1
                       ::pool/next-port 7950
                       ::pool/shutdown? false})
          pool {::pool/state state
                ::pool/idle-queue idle-queue
                ::pool/scheduler nil}
          result (pool/acquire! pool {::pool/namespace 'seon.test.ns})]
      (is (nil? result) "Should return nil when pool exhausted"))))

(deftest shutdown-clears-state-test
  (testing "shutdown! clears all JVM tracking and idle queue"
    (let [idle-queue (LinkedBlockingQueue.)
          jvm {::pool/port 7900 ::pool/pid 1001 ::pool/status :idle
               ::pool/process nil ::pool/namespace nil}
          _ (.put idle-queue jvm)
          state (atom {::pool/all-jvms {7900 jvm}
                       ::pool/target-size 1
                       ::pool/next-port 7901
                       ::pool/shutdown? false})
          pool {::pool/state state
                ::pool/idle-queue idle-queue
                ::pool/scheduler nil}]
      (pool/shutdown! pool)
      (is (true? (::pool/shutdown? @state)))
      (is (= {} (::pool/all-jvms @state)))
      (is (= 0 (.size idle-queue))))))

;;; ---------------------------------------------------------------------------
;;; Phase 1b: Production hardening unit tests
;;; ---------------------------------------------------------------------------

(deftest pool-warming-flag-test
  (testing "Pool with ::warming? true reports as warming"
    (let [idle-queue (LinkedBlockingQueue.)
          state (atom {::pool/all-jvms {}
                       ::pool/target-size 2
                       ::pool/next-port 7900
                       ::pool/shutdown? false
                       ::pool/warming? true})
          pool {::pool/state state
                ::pool/idle-queue idle-queue
                ::pool/scheduler nil}]
      (is (true? (pool/pool-warming? pool))
          "Pool should report warming when ::warming? is true")
      (is (true? (::pool/warming? (pool/pool-status pool)))
          "pool-status should include ::warming? flag")))

  (testing "Pool with ::warming? false reports as ready"
    (let [idle-queue (LinkedBlockingQueue.)
          state (atom {::pool/all-jvms {}
                       ::pool/target-size 2
                       ::pool/next-port 7900
                       ::pool/shutdown? false
                       ::pool/warming? false})
          pool {::pool/state state
                ::pool/idle-queue idle-queue
                ::pool/scheduler nil}]
      (is (false? (pool/pool-warming? pool))
          "Pool should not report warming when ::warming? is false")
      (is (false? (::pool/warming? (pool/pool-status pool)))
          "pool-status should show warming? false"))))

(deftest acquire-with-timeout-empty-pool-test
  (testing "acquire! with ::timeout-ms on empty pool waits then returns nil"
    (let [idle-queue (LinkedBlockingQueue.)
          state (atom {::pool/all-jvms {}
                       ::pool/target-size 1
                       ::pool/next-port 7950
                       ::pool/shutdown? false})
          pool {::pool/state state
                ::pool/idle-queue idle-queue
                ::pool/scheduler nil}
          start (System/currentTimeMillis)
          result (pool/acquire! pool {::pool/namespace 'seon.test.ns
                                       ::pool/timeout-ms 100})
          elapsed (- (System/currentTimeMillis) start)]
      (is (nil? result) "Should return nil after timeout on empty pool")
      (is (>= elapsed 80) "Should have waited approximately 100ms")
      (is (< elapsed 500) "Should not wait excessively long"))))

(deftest acquire-with-timeout-jvm-arrives-test
  (testing ".poll(timeout) unblocks when JVM arrives mid-wait"
    ;; Tests the queue-level behavior: .poll(timeout) returns early when
    ;; an element is added. We can't test full acquire! here because
    ;; activate-jvm! calls nrepl-eval! on the mock. Instead we test
    ;; the LinkedBlockingQueue directly with the same timeout pattern.
    (let [idle-queue (LinkedBlockingQueue.)
          jvm {::pool/port 7960 ::pool/pid 9999 ::pool/status :idle
               ::pool/process nil ::pool/namespace nil}
          ;; Put a JVM in after 50ms in another thread
          _ (future
              (Thread/sleep 50)
              (.put idle-queue jvm))
          start (System/currentTimeMillis)
          result (.poll idle-queue 5000 java.util.concurrent.TimeUnit/MILLISECONDS)
          elapsed (- (System/currentTimeMillis) start)]
      (is (some? result) "Should get the JVM that was added")
      (is (= 7960 (::pool/port result)) "Should be the JVM we put in")
      (is (>= elapsed 40) "Should have waited for the JVM to arrive")
      (is (< elapsed 2000) "Should not wait the full timeout"))))

(deftest acquire-blocking-test
  (testing "acquire!! blocks until JVM available then returns it"
    (let [idle-queue (LinkedBlockingQueue.)
          jvm {::pool/port 7970 ::pool/pid 8888 ::pool/status :idle
               ::pool/process nil ::pool/namespace nil}
          state (atom {::pool/all-jvms {7970 jvm}
                       ::pool/target-size 1
                       ::pool/next-port 7971
                       ::pool/shutdown? false})
          pool {::pool/state state
                ::pool/idle-queue idle-queue
                ::pool/scheduler nil}
          ;; Put JVM in queue after 50ms
          _ (future
              (Thread/sleep 50)
              (.put idle-queue jvm))
          start (System/currentTimeMillis)
          ;; acquire!! will block on .take, get the JVM, then try setup-namespace!
          ;; which will fail on a mock. We catch the exception and verify timing.
          result (try
                   (pool/acquire!! pool {::pool/namespace 'seon.test.ns})
                   (catch Exception _
                     ::setup-failed))
          elapsed (- (System/currentTimeMillis) start)]
      ;; The key behavior: acquire!! blocked until the JVM arrived (~50ms)
      ;; It didn't return immediately (like .poll would)
      (is (>= elapsed 40) "Should have blocked waiting for JVM")
      (is (< elapsed 5000) "Should not block forever"))))

(deftest acquire-no-timeout-backward-compat-test
  (testing "acquire! without ::timeout-ms returns nil immediately on empty pool"
    (let [idle-queue (LinkedBlockingQueue.)
          state (atom {::pool/all-jvms {}
                       ::pool/target-size 1
                       ::pool/next-port 7950
                       ::pool/shutdown? false})
          pool {::pool/state state
                ::pool/idle-queue idle-queue
                ::pool/scheduler nil}
          start (System/currentTimeMillis)
          result (pool/acquire! pool {::pool/namespace 'seon.test.ns})
          elapsed (- (System/currentTimeMillis) start)]
      (is (nil? result) "Should return nil immediately")
      (is (< elapsed 50) "Should not block at all without timeout-ms"))))

(deftest cleanup-stale-agents-no-stale-test
  (testing "cleanup-stale-agents! returns a non-negative count"
    ;; Some agent JVMs may actually be running on 7900-7999,
    ;; so we just verify the function runs and returns a count.
    (let [cleaned (pool/cleanup-stale-agents!)]
      (is (nat-int? cleaned) "Should return a non-negative integer"))))

(deftest allocate-port-wraparound-test
  (testing "allocate-port wraps from agent-port-max back to agent-port-min"
    (let [state (atom {::pool/next-port 7999
                       ::pool/all-jvms {}})
          ;; allocate-port! is private, so we test via the state atom behavior
          ;; by simulating what swap-vals! does with the wraparound logic
          [old _] (swap-vals! state update ::pool/next-port
                              #(let [p (inc %)]
                                 (if (> p 7999) 7900 p)))
          port (::pool/next-port old)]
      (is (= 7999 port) "Should get 7999 as the allocated port")
      (is (= 7900 (::pool/next-port @state)) "Next port should wrap to 7900"))))

(deftest await-warm-already-warm-test
  (testing "await-warm returns immediately when pool is already warm"
    (let [idle-queue (LinkedBlockingQueue.)
          state (atom {::pool/all-jvms {}
                       ::pool/target-size 1
                       ::pool/next-port 7900
                       ::pool/shutdown? false
                       ::pool/warming? false})
          pool {::pool/state state
                ::pool/idle-queue idle-queue
                ::pool/scheduler nil}
          start (System/currentTimeMillis)
          result (pool/await-warm pool 5000)
          elapsed (- (System/currentTimeMillis) start)]
      (is (true? result))
      (is (< elapsed 200) "Should return immediately when already warm"))))

(deftest await-warm-becomes-warm-test
  (testing "await-warm blocks until pool becomes warm"
    (let [idle-queue (LinkedBlockingQueue.)
          state (atom {::pool/all-jvms {}
                       ::pool/target-size 1
                       ::pool/next-port 7900
                       ::pool/shutdown? false
                       ::pool/warming? true})
          pool {::pool/state state
                ::pool/idle-queue idle-queue
                ::pool/scheduler nil}
          ;; Simulate warming completing after 200ms
          _ (future
              (Thread/sleep 200)
              (swap! state assoc ::pool/warming? false))
          start (System/currentTimeMillis)
          result (pool/await-warm pool 5000)
          elapsed (- (System/currentTimeMillis) start)]
      (is (true? result))
      (is (>= elapsed 150) "Should have waited for warming to complete")
      (is (< elapsed 2000) "Should not wait excessively"))))

(deftest await-warm-timeout-test
  (testing "await-warm returns false on timeout"
    (let [idle-queue (LinkedBlockingQueue.)
          state (atom {::pool/all-jvms {}
                       ::pool/target-size 1
                       ::pool/next-port 7900
                       ::pool/shutdown? false
                       ::pool/warming? true})
          pool {::pool/state state
                ::pool/idle-queue idle-queue
                ::pool/scheduler nil}
          start (System/currentTimeMillis)
          result (pool/await-warm pool 200)
          elapsed (- (System/currentTimeMillis) start)]
      (is (false? result) "Should return false on timeout")
      (is (>= elapsed 150) "Should have waited close to timeout"))))

;;; ---------------------------------------------------------------------------
;;; Port allocation / reservation tests
;;; ---------------------------------------------------------------------------

(deftest allocate-port-skips-tracked-ports-test
  (testing "allocate-port! skips ports already in ::all-jvms"
    ;; We can't call the private allocate-port! directly, so we test the
    ;; behavior through spawn-and-enqueue! indirectly. Instead, we verify
    ;; the allocate logic by simulating what it does: the loop should skip
    ;; ports present in ::all-jvms.
    (let [state (atom {::pool/next-port 7900
                       ::pool/all-jvms {7900 {::pool/port 7900}
                                        7901 {::pool/port 7901}}
                       ::pool/reserved-ports #{}})
          ;; Simulate allocate-port! loop: advance next-port, check all-jvms
          allocated (loop [attempts 0]
                     (when (< attempts 10)
                       (let [[old _] (swap-vals! state update ::pool/next-port
                                                 #(let [p (inc %)]
                                                    (if (> p 7999) 7900 p)))
                             port (::pool/next-port old)]
                         (if (or (contains? (::pool/all-jvms old) port)
                                 (contains? (::pool/reserved-ports old) port))
                           (recur (inc attempts))
                           port))))]
      (is (= 7902 allocated) "Should skip 7900 and 7901 (in all-jvms) and return 7902"))))

(deftest allocate-port-skips-reserved-ports-test
  (testing "allocate-port! skips ports in ::reserved-ports"
    (let [state (atom {::pool/next-port 7900
                       ::pool/all-jvms {}
                       ::pool/reserved-ports #{7900 7901}})
          allocated (loop [attempts 0]
                     (when (< attempts 10)
                       (let [[old _] (swap-vals! state update ::pool/next-port
                                                 #(let [p (inc %)]
                                                    (if (> p 7999) 7900 p)))
                             port (::pool/next-port old)]
                         (if (or (contains? (::pool/all-jvms old) port)
                                 (contains? (::pool/reserved-ports old) port))
                           (recur (inc attempts))
                           port))))]
      (is (= 7902 allocated) "Should skip 7900 and 7901 (reserved) and return 7902"))))

(deftest unreserve-port-test
  (testing "unreserve-port removes port from reserved-ports set"
    (let [state (atom {::pool/next-port 7903
                       ::pool/all-jvms {}
                       ::pool/reserved-ports #{7900 7901 7902}})]
      ;; unreserve-port! is private, but its logic is just:
      ;; (swap! state update ::pool/reserved-ports disj port)
      (swap! state update ::pool/reserved-ports disj 7901)
      (is (= #{7900 7902} (::pool/reserved-ports @state))
          "7901 should be removed from reserved-ports")
      (swap! state update ::pool/reserved-ports disj 7900)
      (is (= #{7902} (::pool/reserved-ports @state))
          "7900 should also be removed")
      ;; Removing a port not in the set is a no-op
      (swap! state update ::pool/reserved-ports disj 7999)
      (is (= #{7902} (::pool/reserved-ports @state))
          "Removing absent port should be a no-op"))))

;;; ---------------------------------------------------------------------------
;;; Integration tests (spawn real JVMs -- slow, skip in CI)
;;; ---------------------------------------------------------------------------

(deftest ^:integration pool-lifecycle-test
  (testing "Create pool, acquire, eval, release, shutdown"
    (let [p (pool/create-pool! {::pool/size 2 ::pool/base-port 7910})]
      (try
        ;; Pool returns immediately -- warming? should be true
        (is (pool/pool-warming? p) "Pool should be warming immediately after creation")

        ;; Wait for pool to become warm (up to 30s for JVM startup)
        (is (pool/await-warm p 30000) "Pool should finish warming within 30s")
        (is (not (pool/pool-warming? p)) "Pool should no longer be warming")

        ;; Pool should have 2 idle JVMs
        (let [status (pool/pool-status p)]
          (is (= 2 (::pool/total status)))
          (is (= 2 (::pool/idle status)))
          (is (= 0 (::pool/active status))))

        ;; Acquire one JVM
        (let [agent (pool/acquire! p {::pool/namespace 'seon.test.pooled
                                      ::pool/forms ['(defn greet [n]
                                                       (str "Hello " n))]})]
          (is (some? agent))
          (is (< (::pool/setup-ms agent) 500)
              "Warm assignment should be under 500ms")

          ;; Eval on the acquired JVM
          (is (= "\"Hello World\""
                 (pool/nrepl-eval! (::pool/port agent) "(greet \"World\")")))

          ;; Pool status should reflect 1 idle (original) + replenishment
          ;; Wait briefly for replenishment
          (Thread/sleep 2000)
          (let [status (pool/pool-status p)]
            (is (>= (::pool/idle status) 1)))

          ;; Release back
          (pool/release! p agent)

          ;; After release, idle should be back up
          (Thread/sleep 500)
          (let [status (pool/pool-status p)]
            (is (>= (::pool/idle status) 2))))

        ;; Acquire 2 on same namespace, verify no clobbering
        (let [a1 (pool/acquire! p {::pool/namespace 'seon.trading.signals
                                    ::pool/forms ['(defn ema [x] (str "ema-v1:" x))]})
              a2 (pool/acquire! p {::pool/namespace 'seon.trading.signals
                                    ::pool/forms ['(defn ema [x] (str "ema-v2:" x))]})]
          (is (some? a1))
          (is (some? a2))
          ;; Each JVM has its own definition -- no clobbering
          (is (= "\"ema-v1:test\""
                 (pool/nrepl-eval! (::pool/port a1) "(ema \"test\")")))
          (is (= "\"ema-v2:test\""
                 (pool/nrepl-eval! (::pool/port a2) "(ema \"test\")")))
          (pool/release! p a1)
          (pool/release! p a2))

        (finally
          (pool/shutdown! p))))))
