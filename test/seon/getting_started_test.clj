(ns seon.getting-started-test
  "Tests for seon.getting-started namespace.

   Example tests demonstrating intended usage patterns for:
   - initial-state: returns valid step-1 data
   - advance!/go-back!: step navigation preserves user workouts
   - add-workout!: adds workout entries to ctx
   - send-message!: appends user messages (stub)"
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [seon.getting-started :as gs]
            [seon.render.default-page :as dp]))

;;; ---------------------------------------------------------------------------
;;; initial-state Tests
;;; ---------------------------------------------------------------------------

(deftest initial-state-test
  (testing "returns step-1 data with required keys"
    (let [state (gs/initial-state)]
      (is (= 1 (::gs/current-step state)))
      (is (string? (::dp/narrative state)))
      (is (vector? (::dp/messages state)))
      (is (empty? (::dp/messages state)))
      (is (vector? (::gs/project-ideas state))))))

(deftest initial-state-validates-against-schema
  (testing "initial-state output validates against ::*ctx* schema"
    (let [state (gs/initial-state)]
      (is (m/validate ::gs/*ctx* state)
          "initial-state should produce valid ::*ctx* data"))))

;;; ---------------------------------------------------------------------------
;;; Step Navigation Tests
;;; ---------------------------------------------------------------------------

(deftest advance-test
  (testing "advance! moves from step 1 to step 2"
    (let [ctx-atom (atom (gs/initial-state))]
      (gs/advance! {:seon.reactive/ctx ctx-atom})
      (is (= 2 (::gs/current-step @ctx-atom)))))

  (testing "advance! preserves user-added workouts"
    (let [user-workout {::gs/exercise "Pull-up"
                        ::gs/sets 3
                        ::gs/reps 10
                        ::gs/weight 0}
          ctx-atom (atom (assoc (gs/initial-state)
                                ::gs/workouts [user-workout]))]
      (gs/advance! {:seon.reactive/ctx ctx-atom})
      (is (= [user-workout] (::gs/workouts @ctx-atom))
          "User workouts should be preserved across step transitions")))

  (testing "advance! caps at step 4"
    (let [ctx-atom (atom (assoc (gs/initial-state) ::gs/current-step 4))]
      (gs/advance! {:seon.reactive/ctx ctx-atom})
      (is (= 4 (::gs/current-step @ctx-atom))
          "Should not advance beyond step 4"))))

(deftest go-back-test
  (testing "go-back! moves from step 2 to step 1"
    (let [ctx-atom (atom (assoc (gs/initial-state) ::gs/current-step 2))]
      (gs/go-back! {:seon.reactive/ctx ctx-atom})
      (is (= 1 (::gs/current-step @ctx-atom)))))

  (testing "go-back! preserves user-added workouts"
    (let [user-workout {::gs/exercise "Dip"
                        ::gs/sets 4
                        ::gs/reps 12
                        ::gs/weight 20}
          ctx-atom (atom (-> (gs/initial-state)
                             (assoc ::gs/current-step 3)
                             (assoc ::gs/workouts [user-workout])))]
      (gs/go-back! {:seon.reactive/ctx ctx-atom})
      (is (= [user-workout] (::gs/workouts @ctx-atom))
          "User workouts should be preserved when going back")))

  (testing "go-back! floors at step 1"
    (let [ctx-atom (atom (gs/initial-state))]
      (gs/go-back! {:seon.reactive/ctx ctx-atom})
      (is (= 1 (::gs/current-step @ctx-atom))
          "Should not go back below step 1"))))

;;; ---------------------------------------------------------------------------
;;; add-workout! Tests
;;; ---------------------------------------------------------------------------

(deftest add-workout-test
  (testing "add-workout! adds workout entry to ctx"
    (let [ctx-atom (atom (gs/initial-state))]
      (gs/add-workout! {:seon.reactive/ctx ctx-atom
                        ::gs/exercise "Squat"
                        ::gs/sets "5"
                        ::gs/reps "5"
                        ::gs/weight "100"})
      (let [workouts (::gs/workouts @ctx-atom)]
        (is (= 1 (count workouts)))
        (is (= "Squat" (::gs/exercise (first workouts))))
        (is (= 5 (::gs/sets (first workouts))))
        (is (= 5 (::gs/reps (first workouts))))
        (is (= 100.0 (::gs/weight (first workouts)))))))

  (testing "add-workout! handles missing/blank exercise gracefully"
    (let [ctx-atom (atom (gs/initial-state))]
      (gs/add-workout! {:seon.reactive/ctx ctx-atom
                        ::gs/exercise ""
                        ::gs/sets "3"
                        ::gs/reps "10"
                        ::gs/weight "50"})
      (is (nil? (::gs/workouts @ctx-atom))
          "Should not add workout with blank exercise")))

  (testing "add-workout! uses defaults for missing numeric fields"
    (let [ctx-atom (atom (gs/initial-state))]
      (gs/add-workout! {:seon.reactive/ctx ctx-atom
                        ::gs/exercise "Curl"})
      (let [workout (first (::gs/workouts @ctx-atom))]
        (is (= 3 (::gs/sets workout)) "Default sets is 3")
        (is (= 10 (::gs/reps workout)) "Default reps is 10")
        (is (zero? (::gs/weight workout)) "Default weight is 0")))))

;;; ---------------------------------------------------------------------------
;;; send-message! Tests
;;; ---------------------------------------------------------------------------

(deftest send-message-test
  (testing "send-message! appends user message to history"
    (let [ctx-atom (atom (gs/initial-state))]
      (gs/send-message! {:seon.reactive/ctx ctx-atom
                         ::dp/user-input "Hello, agent!"})
      (let [messages (::dp/messages @ctx-atom)]
        (is (= 1 (count messages)))
        (is (= :user (:role (first messages))))
        (is (= "Hello, agent!" (:content (first messages)))))))

  (testing "send-message! clears user-input after sending"
    (let [ctx-atom (atom (assoc (gs/initial-state) ::dp/user-input "typed text"))]
      (gs/send-message! {:seon.reactive/ctx ctx-atom
                         ::dp/user-input "New message"})
      (is (= "" (::dp/user-input @ctx-atom))
          "User input should be cleared after send")))

  (testing "send-message! ignores blank input"
    (let [ctx-atom (atom (gs/initial-state))]
      (gs/send-message! {:seon.reactive/ctx ctx-atom
                         ::dp/user-input "   "})
      (is (empty? (::dp/messages @ctx-atom))
          "Should not add message for blank input"))))

;;; ---------------------------------------------------------------------------
;;; Step Function Tests
;;; ---------------------------------------------------------------------------

(deftest step-data-test
  (testing "all step functions return valid ::*ctx* data"
    (doseq [[idx step-fn] [[1 gs/step-1] [2 gs/step-2] [3 gs/step-3] [4 gs/step-4]]]
      (let [data (step-fn)]
        (is (= idx (::gs/current-step data))
            (str "Step " idx " should have correct current-step"))
        (is (string? (::dp/narrative data))
            (str "Step " idx " should have narrative"))
        (is (m/validate ::gs/*ctx* data)
            (str "Step " idx " should validate against ::*ctx* schema"))))))

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.getting-started-test)
  nil)
