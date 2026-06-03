(ns seon.flow.domain-integration-test
  "Integration tests for lifting + nutrition domains on real pool JVMs.

   Spawns actual agent JVM processes, loads domain functions via nREPL,
   builds a topology, and runs robustness scenarios: normal latency,
   cross-namespace coordination, timeouts, burst load, error recovery,
   and state persistence.

   Marked as :integration — slower tests (~30s+), require agent classpath."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.flow.harness :as harness]
            [seon.flow.msg :as msg]
            [seon.flow.pool :as pool]
            [seon.flow.topology :as topology]))

;;; ---------------------------------------------------------------------------
;;; Domain code to load into agent JVMs via nREPL
;;; ---------------------------------------------------------------------------

(def ^:private lifting-code
  "(ns seon.test.lifting)

(def workouts (atom []))

(defn log-workout [{:keys [exercise sets reps weight]}]
  (let [w {:exercise exercise :sets sets :reps reps :weight weight
           :timestamp (System/currentTimeMillis)}]
    (swap! workouts conj w)
    {:status :logged :workout w}))

(defn recent-workouts [{:keys [n] :or {n 5}}]
  {:workouts (take-last n @workouts)})

(defn calories-burned [{:keys [exercise sets reps weight metabolic-rate]}]
  (Thread/sleep (+ 50 (rand-int 100)))
  (let [volume (* sets reps weight)
        calories (* volume 0.05 (/ metabolic-rate 1800.0))]
    {:calories (Math/round (double calories)) :exercise exercise}))

(defn slow-analysis [{:keys [delay-ms]}]
  (Thread/sleep (or delay-ms 3000))
  {:result \"done\"})

(defn throwing-fn [_]
  (throw (ex-info \"Lifting system error\" {:code :system-error})))")

(def ^:private nutrition-code
  "(ns seon.test.nutrition)

(def meals (atom []))

(defn log-meal [{:keys [food calories protein carbs fat]}]
  (let [m {:food food :calories calories :protein protein
           :carbs carbs :fat fat :timestamp (System/currentTimeMillis)}]
    (swap! meals conj m)
    {:status :logged :meal m}))

(defn daily-summary [_]
  (let [today @meals]
    {:total-calories (reduce + 0 (map :calories today))
     :total-protein (reduce + 0 (map :protein today))
     :meal-count (count today)}))

(defn metabolic-rate [{:keys [weight height age]}]
  (Thread/sleep (+ 20 (rand-int 50)))
  {:bmr (Math/round (+ 88.362 (* 13.397 weight) (* 4.799 height) (* -5.677 age)))})

(defn slow-calculation [{:keys [delay-ms]}]
  (Thread/sleep (or delay-ms 3000))
  {:result \"calculated\"})")

;;; ---------------------------------------------------------------------------
;;; Pool + topology fixture
;;; ---------------------------------------------------------------------------

(def ^:private test-pool (atom nil))
(def ^:private test-handles (atom nil))  ; {:lifting ns-handle, :nutrition ns-handle}
(def ^:private test-topo (atom nil))

(defn- setup-pool-and-topology!
  "Create pool, acquire JVMs, load domain code, build topology.
   Returns true on success, false if unavailable."
  []
  (try
    (let [p (pool/create-pool! {::pool/size 3 ::pool/base-port 7960})]
      (if-not (pool/await-warm p 30000)
        (do (pool/shutdown! p) false)
        (let [;; Start namespace JVMs
              lifting-handle (harness/start-namespace-jvm!
                               {::harness/pool p
                                ::harness/namespace "seon.test.lifting"})
              nutrition-handle (harness/start-namespace-jvm!
                                 {::harness/pool p
                                  ::harness/namespace "seon.test.nutrition"})
              ;; Load domain code via nREPL
              lifting-port (::pool/port (::harness/jvm lifting-handle))
              nutrition-port (::pool/port (::harness/jvm nutrition-handle))]
          (pool/nrepl-eval! lifting-port lifting-code)
          (pool/nrepl-eval! nutrition-port nutrition-code)
          ;; Build topology
          (let [topo (topology/build-topology!
                       {::topology/namespaces
                        {"seon.test.lifting"
                         {::harness/in-ports (::harness/in-ports lifting-handle)
                          ::harness/out-ports (::harness/out-ports lifting-handle)}
                         "seon.test.nutrition"
                         {::harness/in-ports (::harness/in-ports nutrition-handle)
                          ::harness/out-ports (::harness/out-ports nutrition-handle)}}})]
            (Thread/sleep 300) ; let flow settle
            (reset! test-pool p)
            (reset! test-handles {:lifting lifting-handle :nutrition nutrition-handle})
            (reset! test-topo topo)
            true))))
    (catch Exception e
      (println "SKIP: Domain integration setup failed:" (.getMessage e))
      false)))

(defn- teardown! []
  (when-let [topo @test-topo]
    (topology/stop-topology! topo)
    (reset! test-topo nil))
  (when-let [{:keys [lifting nutrition]} @test-handles]
    (harness/stop-namespace-jvm! lifting)
    (harness/stop-namespace-jvm! nutrition)
    (reset! test-handles nil))
  (when-let [p @test-pool]
    (pool/shutdown! p)
    (reset! test-pool nil)))

(use-fixtures :once
  (fn [f]
    (if (setup-pool-and-topology!)
      (try (f) (finally (teardown!)))
      (println "SKIP: Domain integration tests (pool/JVM unavailable)"))))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn- request!
  "Convenience wrapper for topology/request!."
  [target-ns fn-name args & {:keys [timeout-ms] :or {timeout-ms 10000}}]
  (topology/request!
   {::topology/flow (::topology/flow @test-topo)
    ::topology/target-ns target-ns
    ::topology/fn fn-name
    ::topology/args args
    ::topology/timeout-ms timeout-ms}))

(defmacro timed
  "Execute body, return [result elapsed-ms]."
  [& body]
  `(let [start# (System/nanoTime)
         result# (do ~@body)
         elapsed# (/ (- (System/nanoTime) start#) 1e6)]
     [result# (long elapsed#)]))

;;; ---------------------------------------------------------------------------
;;; Test 1: Normal latency domain workflow
;;; ---------------------------------------------------------------------------

(deftest ^:integration normal-latency-domain-test
  (testing "full workflow with realistic timing on real JVMs"
    (when @test-topo
      ;; Log a meal (fast)
      (let [[meal-result meal-ms] (timed (request! "seon.test.nutrition"
                                                    "seon.test.nutrition/log-meal"
                                                    [{:food "chicken" :calories 300
                                                      :protein 50 :carbs 0 :fat 8}]))]
        (println "  log-meal:" meal-ms "ms")
        (is (= :logged (:status meal-result))))

      ;; Log a workout (fast)
      (let [[workout-result workout-ms]
            (timed (request! "seon.test.lifting"
                             "seon.test.lifting/log-workout"
                             [{:exercise "squat" :sets 5 :reps 5 :weight 225}]))]
        (println "  log-workout:" workout-ms "ms")
        (is (= :logged (:status workout-result))))

      ;; Get metabolic rate (50-70ms computation + network)
      (let [[bmr-result bmr-ms]
            (timed (request! "seon.test.nutrition"
                             "seon.test.nutrition/metabolic-rate"
                             [{:weight 80 :height 180 :age 30}]))]
        (println "  metabolic-rate:" bmr-ms "ms")
        (is (number? (:bmr bmr-result)))
        (is (pos? (:bmr bmr-result))))

      ;; Calculate calories burned (100-200ms computation + network)
      (let [[cal-result cal-ms]
            (timed (request! "seon.test.lifting"
                             "seon.test.lifting/calories-burned"
                             [{:exercise "squat" :sets 5 :reps 5
                               :weight 225 :metabolic-rate 1800}]))]
        (println "  calories-burned:" cal-ms "ms")
        (is (number? (:calories cal-result)))
        (is (pos? (:calories cal-result)))
        (is (= "squat" (:exercise cal-result))))

      ;; Get daily summary (fast)
      (let [[summary summary-ms]
            (timed (request! "seon.test.nutrition"
                             "seon.test.nutrition/daily-summary"
                             [nil]))]
        (println "  daily-summary:" summary-ms "ms")
        (is (>= (:total-calories summary) 300))
        (is (>= (:total-protein summary) 50))
        (is (pos? (:meal-count summary)))))))

;;; ---------------------------------------------------------------------------
;;; Test 2: Cross-namespace on real JVMs
;;; ---------------------------------------------------------------------------

(deftest ^:integration cross-namespace-on-real-jvms-test
  (testing "orchestrator choreographs cross-namespace calls on real JVMs"
    (when @test-topo
      ;; Step 1: Get metabolic rate from nutrition
      (let [bmr-result (request! "seon.test.nutrition"
                                  "seon.test.nutrition/metabolic-rate"
                                  [{:weight 80 :height 180 :age 30}])
            bmr (:bmr bmr-result)]
        (is (number? bmr))
        (is (pos? bmr))

        ;; Step 2: Pass to lifting/calories-burned
        (let [cal-result (request! "seon.test.lifting"
                                    "seon.test.lifting/calories-burned"
                                    [{:exercise "deadlift" :sets 3 :reps 5
                                      :weight 315 :metabolic-rate bmr}])]
          (is (number? (:calories cal-result)))
          (is (pos? (:calories cal-result)))
          (is (= "deadlift" (:exercise cal-result)))
          (println "  BMR:" bmr "-> calories:" (:calories cal-result)))))))

;;; ---------------------------------------------------------------------------
;;; Test 3: Timeout test
;;; ---------------------------------------------------------------------------

(deftest ^:integration timeout-test
  (testing "slow function times out, then namespace recovers"
    (when @test-topo
      ;; Call slow-analysis with 5s delay but 1s timeout
      (let [ex (try
                 (request! "seon.test.lifting"
                           "seon.test.lifting/slow-analysis"
                           [{:delay-ms 5000}]
                           :timeout-ms 1000)
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) "Should have thrown timeout")
        (is (= :timeout (::msg/error-type (ex-data ex)))))

      ;; Wait for the slow call to finish on the JVM side so it doesn't block
      (Thread/sleep 5000)

      ;; Verify recovery: normal call still works
      (let [[result ms] (timed (request! "seon.test.lifting"
                                          "seon.test.lifting/log-workout"
                                          [{:exercise "bench" :sets 5 :reps 5 :weight 185}]))]
        (println "  Recovery call after timeout:" ms "ms")
        (is (= :logged (:status result)))))))

;;; ---------------------------------------------------------------------------
;;; Test 4: Burst load test
;;; ---------------------------------------------------------------------------

(deftest ^:integration burst-load-test
  (testing "10 concurrent requests to same namespace all succeed"
    (when @test-topo
      (let [meals (mapv (fn [i]
                          {:food (str "meal-" i) :calories (* 100 (inc i))
                           :protein (* 10 (inc i)) :carbs 20 :fat 5})
                        (range 10))
            [results total-ms]
            (timed
             (let [futures (mapv (fn [meal]
                                  (future
                                    (request! "seon.test.nutrition"
                                              "seon.test.nutrition/log-meal"
                                              [meal])))
                                meals)]
               (mapv deref futures)))]
        (println "  Total burst (10 meals):" total-ms "ms")
        (is (= 10 (count results)))
        (is (every? #(= :logged (:status %)) results))

        ;; Verify all 10 show up in summary
        (let [summary (request! "seon.test.nutrition"
                                 "seon.test.nutrition/daily-summary"
                                 [nil])]
          ;; Note: there may be meals from previous tests too
          (is (>= (:meal-count summary) 10)))))))

;;; ---------------------------------------------------------------------------
;;; Test 5: Cascading timeout test
;;; ---------------------------------------------------------------------------

(deftest ^:integration cascading-timeout-test
  (testing "orchestrator timeout when calling slow nutrition, then recovery"
    (when @test-topo
      ;; Call nutrition/slow-calculation with 3s delay, but 1s orchestrator timeout
      (let [ex (try
                 (request! "seon.test.nutrition"
                           "seon.test.nutrition/slow-calculation"
                           [{:delay-ms 3000}]
                           :timeout-ms 1000)
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) "Should have thrown timeout")
        (is (= :timeout (::msg/error-type (ex-data ex)))))

      ;; Wait for slow call to clear
      (Thread/sleep 3500)

      ;; Verify nutrition JVM still responsive
      (let [[result ms] (timed (request! "seon.test.nutrition"
                                          "seon.test.nutrition/daily-summary"
                                          [nil]))]
        (println "  Recovery after cascading timeout:" ms "ms")
        (is (map? result))))))

;;; ---------------------------------------------------------------------------
;;; Test 6: Error recovery test
;;; ---------------------------------------------------------------------------

(deftest ^:integration error-recovery-test
  (testing "errors don't break the JVM, normal calls still work"
    (when @test-topo
      ;; Call non-existent function
      (let [ex1 (try
                  (request! "seon.test.lifting"
                            "seon.test.lifting/nonexistent-fn"
                            [{}])
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex1))
        (is (= :not-found (::msg/error-type (ex-data ex1)))))

      ;; Normal call works
      (let [result (request! "seon.test.lifting"
                              "seon.test.lifting/log-workout"
                              [{:exercise "row" :sets 5 :reps 5 :weight 155}])]
        (is (= :logged (:status result))))

      ;; Call function that throws at runtime
      (let [ex2 (try
                  (request! "seon.test.lifting"
                            "seon.test.lifting/throwing-fn"
                            [{}])
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex2))
        (is (= :execution (::msg/error-type (ex-data ex2)))))

      ;; Normal call still works after runtime error
      (let [result (request! "seon.test.lifting"
                              "seon.test.lifting/log-workout"
                              [{:exercise "ohp" :sets 5 :reps 5 :weight 135}])]
        (is (= :logged (:status result)))
        (println "  Error recovery: all normal calls succeeded after errors")))))

;;; ---------------------------------------------------------------------------
;;; Test 7: State persistence across calls
;;; ---------------------------------------------------------------------------

(deftest ^:integration state-persistence-across-calls-test
  (testing "state accumulates correctly across multiple calls"
    (when @test-topo
      ;; Log 3 workouts
      (doseq [exercise ["squat" "bench" "deadlift"]]
        (let [result (request! "seon.test.lifting"
                                "seon.test.lifting/log-workout"
                                [{:exercise exercise :sets 5 :reps 5 :weight 200}])]
          (is (= :logged (:status result)))))

      ;; Verify all 3 present
      (let [result (request! "seon.test.lifting"
                              "seon.test.lifting/recent-workouts"
                              [{:n 10}])]
        ;; May have workouts from other tests too
        (is (>= (count (:workouts result)) 3))
        (println "  Workouts stored:" (count (:workouts result))))

      ;; Log 5 meals
      (doseq [i (range 5)]
        (let [result (request! "seon.test.nutrition"
                                "seon.test.nutrition/log-meal"
                                [{:food (str "food-" i) :calories 200
                                  :protein 20 :carbs 30 :fat 10}])]
          (is (= :logged (:status result)))))

      ;; Verify daily summary
      (let [summary (request! "seon.test.nutrition"
                               "seon.test.nutrition/daily-summary"
                               [nil])]
        (is (>= (:meal-count summary) 5))
        (is (>= (:total-calories summary) 1000))
        (is (>= (:total-protein summary) 100))
        (println "  Meals stored:" (:meal-count summary)
                 "total-cal:" (:total-calories summary))))))
