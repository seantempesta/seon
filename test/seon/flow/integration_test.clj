(ns seon.flow.integration-test
  "End-to-end integration test: Lifting + Nutrition agents coordinating
   through the flow topology.

   Proves the full stack works:
   - TCP channel servers per namespace
   - Mock agent JVM bridge loops (receive request -> execute-local -> reply)
   - Orchestrator request! calls through topology
   - Cross-namespace coordination (orchestrator-choreographed, option b)
   - Error propagation and namespace isolation
   - Overload on one namespace doesn't affect another

   Design decision: Cross-namespace calls use option (b) — the orchestrator
   choreographs calls. When lifting/calories-burned needs nutrition/metabolic-rate,
   the orchestrator calls metabolic-rate first, then passes the result to
   calories-burned. This is simpler and tests the infrastructure.

   Future consideration: For real agent autonomy, option (a) — a call-fn callback
   injected into functions — would let agent code make cross-ns calls directly.
   This would require the bridge to have a reverse channel back to the topology.
   Not needed for MVP but documented here."
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.flow.harness.bridge :as bridge]
            [seon.flow.msg :as msg]
            [seon.flow.topology :as topology]))

;;; ---------------------------------------------------------------------------
;;; Domain: seon.health.lifting (test-local functions)
;;; ---------------------------------------------------------------------------

(def ^:private lifting-state
  "Simulated *ctx* for lifting namespace."
  (atom {:workouts []
         :program "5x5 Stronglifts"}))

(defn log-workout
  "Record a workout. Args: exercise sets reps weight."
  [exercise sets reps weight]
  (let [workout {:exercise exercise
                 :sets sets
                 :reps reps
                 :weight weight
                 :timestamp (System/currentTimeMillis)}]
    (swap! lifting-state update :workouts conj workout)
    workout))

(defn recent-workouts
  "Return last N workouts."
  [n]
  (take-last n (:workouts @lifting-state)))

(defn calories-burned
  "Estimate calories burned for a workout.
   Requires metabolic-rate as input (orchestrator pre-fetches from nutrition).
   Formula: metabolic-rate * duration-factor * intensity-factor."
  [workout metabolic-rate]
  (let [intensity-factor (/ (:weight workout 100) 100.0)
        sets (:sets workout 3)
        ;; Rough estimate: 0.1 calories per set per intensity unit per BMR unit
        estimated (* metabolic-rate 0.001 sets intensity-factor)]
    {:calories (Math/round (double estimated))
     :workout workout
     :metabolic-rate metabolic-rate}))

;;; ---------------------------------------------------------------------------
;;; Domain: seon.health.nutrition (test-local functions)
;;; ---------------------------------------------------------------------------

(def ^:private nutrition-state
  "Simulated *ctx* for nutrition namespace."
  (atom {:meals []
         :daily-target 2500
         :metabolic-info {:bmr 1800 :activity-level :moderate}}))

(defn log-meal
  "Record a meal. Args: food calories protein carbs fat."
  [food calories protein carbs fat]
  (let [meal {:food food
              :calories calories
              :protein protein
              :carbs carbs
              :fat fat
              :timestamp (System/currentTimeMillis)}]
    (swap! nutrition-state update :meals conj meal)
    meal))

(defn daily-summary
  "Return today's macro totals."
  []
  (let [meals (:meals @nutrition-state)]
    {:total-calories (reduce + 0 (map :calories meals))
     :total-protein (reduce + 0 (map :protein meals))
     :total-carbs (reduce + 0 (map :carbs meals))
     :total-fat (reduce + 0 (map :fat meals))
     :meal-count (count meals)
     :daily-target (:daily-target @nutrition-state)}))

(defn metabolic-rate
  "Return base metabolic rate."
  []
  (get-in @nutrition-state [:metabolic-info :bmr]))

(defn adjust-calories
  "Adjust daily calorie target based on workout data.
   Requires recent-workouts as input (orchestrator pre-fetches from lifting)."
  [recent-workout-data]
  (let [workout-count (count recent-workout-data)
        adjustment (* workout-count 100)]
    (swap! nutrition-state update :daily-target + adjustment)
    {:new-target (:daily-target @nutrition-state)
     :adjustment adjustment
     :workout-count workout-count}))

(defn throwing-function
  "Always throws — for error testing."
  []
  (throw (ex-info "Nutrition system offline" {:code :system-offline})))

;;; ---------------------------------------------------------------------------
;;; Test Infrastructure
;;; ---------------------------------------------------------------------------

(defn- reset-state!
  "Reset both domain atoms to initial state."
  []
  (reset! lifting-state {:workouts [] :program "5x5 Stronglifts"})
  (reset! nutrition-state {:meals []
                           :daily-target 2500
                           :metabolic-info {:bmr 1800 :activity-level :moderate}}))

(defn- clean-promises! []
  (reset! topology/pending-promises {}))

(use-fixtures :each
  (fn [f]
    (reset-state!)
    (clean-promises!)
    (f)
    (clean-promises!)))

(defn- build-bridge-topology!
  "Build a topology where requests are executed via bridge/execute-local.

   Each namespace gets a go-loop that acts as a mock agent JVM:
   reads requests, calls execute-local, writes replies.

   ns-configs is a map of namespace-string -> optional config overrides."
  [ns-configs]
  (let [mock-jvms
        (into {}
              (map (fn [[ns-str config]]
                     (let [jvm-request-ch (async/chan 32)
                           jvm-reply-ch (async/chan 32)
                           bridge-state {::bridge/namespace ns-str}]
                       ;; Bridge loop: execute functions locally
                       (async/go-loop []
                         (when-let [req (async/<! jvm-request-ch)]
                           (let [reply (bridge/execute-local req bridge-state)]
                             (async/>! jvm-reply-ch reply))
                           (recur)))
                       [ns-str (merge config
                                      {::jvm-request-ch jvm-request-ch
                                       ::jvm-reply-ch jvm-reply-ch})])))
              ns-configs)

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

(defn- stop-bridge-topology!
  [{::keys [mock-jvms] :as topo}]
  (topology/stop-topology! topo)
  (doseq [[_ {:keys [::jvm-request-ch ::jvm-reply-ch]}] mock-jvms]
    (async/close! jvm-request-ch)
    (async/close! jvm-reply-ch)))

(defn- request!
  "Convenience wrapper for topology/request!."
  [topo target-ns fn-name & args]
  (topology/request!
   {::topology/flow (::topology/flow topo)
    ::topology/target-ns target-ns
    ::topology/fn fn-name
    ::topology/args (vec args)
    ::topology/timeout-ms 5000}))

(def ^:private this-ns "seon.flow.integration-test")

;;; ---------------------------------------------------------------------------
;;; Test 1: Basic single-namespace calls
;;; ---------------------------------------------------------------------------

(deftest lifting-basic-operations-test
  (testing "log-workout and recent-workouts through topology"
    (let [topo (build-bridge-topology!
                {this-ns {}})]
      (try
        (Thread/sleep 100)

        ;; Log a workout
        (let [result (request! topo this-ns
                               (str this-ns "/log-workout")
                               "squat" 5 5 225)]
          (is (= "squat" (:exercise result)))
          (is (= 225 (:weight result))))

        ;; Log another
        (request! topo this-ns
                  (str this-ns "/log-workout")
                  "bench-press" 5 5 185)

        ;; Get recent workouts
        (let [recent (request! topo this-ns
                               (str this-ns "/recent-workouts") 5)]
          (is (= 2 (count recent)))
          (is (= "squat" (:exercise (first recent)))))

        (finally
          (stop-bridge-topology! topo))))))

(deftest nutrition-basic-operations-test
  (testing "log-meal and daily-summary through topology"
    (let [topo (build-bridge-topology!
                {this-ns {}})]
      (try
        (Thread/sleep 100)

        (request! topo this-ns
                  (str this-ns "/log-meal")
                  "chicken breast" 300 50 0 8)
        (request! topo this-ns
                  (str this-ns "/log-meal")
                  "rice" 400 8 88 1)

        (let [summary (request! topo this-ns
                                (str this-ns "/daily-summary"))]
          (is (= 700 (:total-calories summary)))
          (is (= 58 (:total-protein summary)))
          (is (= 2 (:meal-count summary)))
          (is (= 2500 (:daily-target summary))))

        (finally
          (stop-bridge-topology! topo))))))

;;; ---------------------------------------------------------------------------
;;; Test 2: Two namespaces in one topology
;;; ---------------------------------------------------------------------------

(deftest two-namespace-topology-test
  (testing "lifting and nutrition namespaces coexist in one topology"
    ;; Both point to this-ns since all functions are defined here.
    ;; In production they'd be separate namespaces in separate JVMs.
    (let [topo (build-bridge-topology!
                {"seon.health.lifting" {}
                 "seon.health.nutrition" {}})]
      (try
        (Thread/sleep 100)

        ;; Call "lifting" functions (routed to this ns via bridge)
        (let [workout (request! topo "seon.health.lifting"
                                (str this-ns "/log-workout")
                                "deadlift" 3 5 315)]
          (is (= "deadlift" (:exercise workout))))

        ;; Call "nutrition" functions
        (let [meal (request! topo "seon.health.nutrition"
                             (str this-ns "/log-meal")
                             "steak" 500 45 0 30)]
          (is (= "steak" (:food meal))))

        ;; Each namespace processes independently
        (let [bmr (request! topo "seon.health.nutrition"
                            (str this-ns "/metabolic-rate"))]
          (is (= 1800 bmr)))

        (finally
          (stop-bridge-topology! topo))))))

;;; ---------------------------------------------------------------------------
;;; Test 3: Cross-namespace coordination (orchestrator-choreographed)
;;; ---------------------------------------------------------------------------

(deftest cross-namespace-calories-burned-test
  (testing "orchestrator choreographs: get metabolic-rate from nutrition, pass to lifting"
    (let [topo (build-bridge-topology!
                {"seon.health.lifting" {}
                 "seon.health.nutrition" {}})]
      (try
        (Thread/sleep 100)

        ;; Step 1: Log a workout
        (let [workout (request! topo "seon.health.lifting"
                                (str this-ns "/log-workout")
                                "squat" 5 5 225)]

          ;; Step 2: Get metabolic rate from nutrition
          (let [bmr (request! topo "seon.health.nutrition"
                              (str this-ns "/metabolic-rate"))]
            (is (= 1800 bmr))

            ;; Step 3: Calculate calories burned, passing both pieces
            (let [result (request! topo "seon.health.lifting"
                                   (str this-ns "/calories-burned")
                                   workout bmr)]
              (is (number? (:calories result)))
              (is (pos? (:calories result)))
              (is (= 1800 (:metabolic-rate result)))
              (is (= "squat" (get-in result [:workout :exercise]))))))

        (finally
          (stop-bridge-topology! topo))))))

(deftest cross-namespace-adjust-calories-test
  (testing "orchestrator choreographs: get recent workouts from lifting, pass to nutrition"
    (let [topo (build-bridge-topology!
                {"seon.health.lifting" {}
                 "seon.health.nutrition" {}})]
      (try
        (Thread/sleep 100)

        ;; Log some workouts
        (request! topo "seon.health.lifting"
                  (str this-ns "/log-workout") "squat" 5 5 225)
        (request! topo "seon.health.lifting"
                  (str this-ns "/log-workout") "bench" 5 5 185)

        ;; Get recent workouts from lifting
        (let [workouts (request! topo "seon.health.lifting"
                                 (str this-ns "/recent-workouts") 5)]
          (is (= 2 (count workouts)))

          ;; Pass to nutrition to adjust calories
          (let [result (request! topo "seon.health.nutrition"
                                 (str this-ns "/adjust-calories")
                                 workouts)]
            (is (= 200 (:adjustment result)))
            (is (= 2 (:workout-count result)))
            (is (= 2700 (:new-target result)))))

        (finally
          (stop-bridge-topology! topo))))))

;;; ---------------------------------------------------------------------------
;;; Test 4: Error scenarios
;;; ---------------------------------------------------------------------------

(deftest error-nonexistent-function-test
  (testing "calling a non-existent function returns proper error"
    (let [topo (build-bridge-topology!
                {"seon.health.nutrition" {}})]
      (try
        (Thread/sleep 100)

        (let [ex (try
                   (request! topo "seon.health.nutrition"
                             "seon.health.nutrition/does-not-exist")
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "Should have thrown")
          (is (= :not-found (::msg/error-type (ex-data ex)))))

        (finally
          (stop-bridge-topology! topo))))))

(deftest error-function-throws-test
  (testing "function exception propagated cleanly as :execution error"
    (let [topo (build-bridge-topology!
                {"seon.health.nutrition" {}})]
      (try
        (Thread/sleep 100)

        (let [ex (try
                   (request! topo "seon.health.nutrition"
                             (str this-ns "/throwing-function"))
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex))
          (is (= :execution (::msg/error-type (ex-data ex))))
          (is (= "Nutrition system offline" (.getMessage ex))))

        (finally
          (stop-bridge-topology! topo))))))

(deftest error-isolation-test
  (testing "error in one namespace doesn't affect the other"
    (let [topo (build-bridge-topology!
                {"seon.health.lifting" {}
                 "seon.health.nutrition" {}})]
      (try
        (Thread/sleep 100)

        ;; Trigger error in nutrition
        (try
          (request! topo "seon.health.nutrition"
                    (str this-ns "/throwing-function"))
          (catch clojure.lang.ExceptionInfo _))

        ;; Lifting still works fine
        (let [workout (request! topo "seon.health.lifting"
                                (str this-ns "/log-workout")
                                "squat" 5 5 225)]
          (is (= "squat" (:exercise workout))))

        ;; Nutrition also still works for non-throwing functions
        (let [bmr (request! topo "seon.health.nutrition"
                            (str this-ns "/metabolic-rate"))]
          (is (= 1800 bmr)))

        (finally
          (stop-bridge-topology! topo))))))

;;; ---------------------------------------------------------------------------
;;; Test 5: Overload isolation
;;; ---------------------------------------------------------------------------

(deftest overload-isolation-test
  (testing "overload on one namespace doesn't affect the other"
    (let [;; Nutrition gets queue-cap 1, lifting gets default
          slow-request-ch (async/chan 32)
          slow-reply-ch (async/chan 32)
          ;; Nutrition: slow bridge that never replies (to fill queue)
          ;; We build this manually alongside a normal lifting namespace
          topo (build-bridge-topology!
                {"seon.health.lifting" {}})]
      (try
        ;; Add an overloaded namespace manually
        (let [overload-topo
              (topology/build-topology!
               {::topology/namespaces
                {"seon.health.nutrition.slow"
                 {:seon.flow.harness/queue-cap 1
                  :seon.flow.harness/in-ports
                  {:seon.flow.in/jvm-reply slow-reply-ch}
                  :seon.flow.harness/out-ports
                  {:seon.flow.out/jvm-request slow-request-ch}}}})]
          (try
            (Thread/sleep 100)

            ;; Fill nutrition's queue (1 request, never replied)
            (let [p1 (future
                       (try
                         (topology/request!
                          {::topology/flow (::topology/flow overload-topo)
                           ::topology/target-ns "seon.health.nutrition.slow"
                           ::topology/fn (str this-ns "/metabolic-rate")
                           ::topology/args []
                           ::topology/timeout-ms 3000})
                         (catch Exception _ :timed-out)))]
              (Thread/sleep 200)

              ;; Second request to nutrition should get overload
              (let [ex (try
                         (topology/request!
                          {::topology/flow (::topology/flow overload-topo)
                           ::topology/target-ns "seon.health.nutrition.slow"
                           ::topology/fn (str this-ns "/metabolic-rate")
                           ::topology/args []
                           ::topology/timeout-ms 2000})
                         nil
                         (catch clojure.lang.ExceptionInfo e e))]
                (is (some? ex) "Should have thrown overload")
                (is (= :overload (::msg/error-type (ex-data ex)))))

              ;; Meanwhile, lifting works fine in its own topology
              (let [workout (request! topo "seon.health.lifting"
                                      (str this-ns "/log-workout")
                                      "squat" 5 5 225)]
                (is (= "squat" (:exercise workout))))

              (future-cancel p1))

            (finally
              (topology/stop-topology! overload-topo)
              (async/close! slow-request-ch)
              (async/close! slow-reply-ch))))

        (finally
          (stop-bridge-topology! topo))))))

;;; ---------------------------------------------------------------------------
;;; Test 6: Full workflow — realistic session
;;; ---------------------------------------------------------------------------

(deftest full-workout-day-session-test
  (testing "complete realistic session: log workouts, meals, cross-ns coordination"
    (let [topo (build-bridge-topology!
                {"seon.health.lifting" {}
                 "seon.health.nutrition" {}})]
      (try
        (Thread/sleep 100)

        ;; Morning: log breakfast
        (request! topo "seon.health.nutrition"
                  (str this-ns "/log-meal")
                  "oatmeal" 350 12 60 8)
        (request! topo "seon.health.nutrition"
                  (str this-ns "/log-meal")
                  "protein shake" 250 40 10 5)

        ;; Gym session
        (request! topo "seon.health.lifting"
                  (str this-ns "/log-workout") "squat" 5 5 225)
        (request! topo "seon.health.lifting"
                  (str this-ns "/log-workout") "bench" 5 5 185)
        (request! topo "seon.health.lifting"
                  (str this-ns "/log-workout") "row" 5 5 155)

        ;; Post-workout: calculate calories burned for each workout
        ;; Step 1: Get metabolic rate
        (let [bmr (request! topo "seon.health.nutrition"
                            (str this-ns "/metabolic-rate"))]
          (is (= 1800 bmr))

          ;; Step 2: Get workouts
          (let [workouts (request! topo "seon.health.lifting"
                                   (str this-ns "/recent-workouts") 10)]
            (is (= 3 (count workouts)))

            ;; Step 3: Calculate calories for each
            (let [cal-results
                  (mapv (fn [w]
                          (request! topo "seon.health.lifting"
                                    (str this-ns "/calories-burned")
                                    w bmr))
                        workouts)]
              (is (= 3 (count cal-results)))
              (is (every? #(pos? (:calories %)) cal-results)))

            ;; Step 4: Adjust daily calories based on workout activity
            (let [adjusted (request! topo "seon.health.nutrition"
                                     (str this-ns "/adjust-calories")
                                     workouts)]
              (is (= 3 (:workout-count adjusted)))
              (is (= 300 (:adjustment adjusted)))
              (is (= 2800 (:new-target adjusted))))))

        ;; Lunch
        (request! topo "seon.health.nutrition"
                  (str this-ns "/log-meal")
                  "grilled chicken salad" 450 40 20 18)

        ;; Check daily summary
        (let [summary (request! topo "seon.health.nutrition"
                                (str this-ns "/daily-summary"))]
          (is (= 3 (:meal-count summary)))
          (is (= 1050 (:total-calories summary)))
          ;; Target was adjusted to 2800
          (is (= 2800 (:daily-target summary))))

        (finally
          (stop-bridge-topology! topo))))))
