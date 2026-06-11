(ns seon.gym.paid-test
  "PAID deepseek-tier gym runs — the live behavioral measurements.

   GATED on the SEON_GYM_PAID env var (comma-separated scenario keys,
   or `all`): without it every test here is a no-op, so `bin/test-cljs`
   never burns money. With it, the named scenarios run against the REAL
   DeepSeek adapter (+ the DeepSeek judge for :llm-judge predicates)
   under {:seon.gym/allow-paid? true}.

   These tests assert ONLY that a validated scorecard came back — they
   deliberately do NOT assert :seon.gym.scorecard/pass?. Honest reds
   ARE the deliverable (the gym pins target behavior, catalog §6); the
   scorecard lines (`SEON-GYM SCORECARD …`, greppable via bin/gym) are
   the measurement.

   Run:  bin/gym --paid=s32,s21,s12   (or SEON_GYM_PAID=all bin/test-cljs)"
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [malli.core :as m]
    [seon.gym.driver :as gym]))

(def ^:private paid-scenario-keys
  "Every deepseek-tier scenario key this ns can drive — the resolved
   enabled-set in the PAID-GATE line is computed against this roster."
  [:s32 :s21 :s12])

(defn- gate-value
  "The raw SEON_GYM_PAID env value (\"\" when unset)."
  []
  (str (or (.. js/process -env -SEON_GYM_PAID) "")))

(defn- enabled?
  "PURE gate decision (gym-upgrade §3.3 — unit-testable, no env read):
   exact-match split of the gate string on commas. \"all\" enables
   everything; \"\" enables nothing; otherwise only exact key names."
  [gate k]
  (boolean (and (seq gate)
                (or (= gate "all")
                    (some #(= % (name k)) (str/split gate #","))))))

;; §3.3 observability pin (the paid-gate anomaly stayed UNCONFIRMED
;; because no sweep log recorded the gate value): one greppable line at
;; suite start with the raw gate + the resolved enabled-scenario set.
;; bin/gym surfaces it; its absence in a future paid log means this
;; regressed.
(println "SEON-GYM PAID-GATE"
         (pr-str {:seon.gym.paid/gate    (gate-value)
                  :seon.gym.paid/enabled (filterv #(enabled? (gate-value) %)
                                                  paid-scenario-keys)}))

(defn- call-once
  "Call-once guard for cljs.test async `done` (gym-upgrade §3.1, the
   S-12 double-spend class): cljs.test runs the REMAINING test
   continuation synchronously inside `done`, so an exception thrown by
   a LATER test propagates back THROUGH the first `(done)` call into
   this chain's `.catch` — which then called `done` again, re-running
   the continuation and driving the next paid scenario twice (two
   scorecards, one key, 51s apart in paid sweep 2). The guard makes
   every continuation fire at most once."
  [f]
  (let [!called (atom false)]
    (fn []
      (when (compare-and-set! !called false true)
        (f)))))

(defn- run-paid! [k path done]
  (let [done (call-once done)]
    (if-not (enabled? (gate-value) k)
      (do (is true (str k " skipped — set SEON_GYM_PAID=" (name k)
                        " (or all) to run the paid tier"))
          (done))
      (-> (gym/run-scenario!
            {:seon.gym/scenario
             (first (:seon.gym/scenarios
                      (gym/load-scenarios! {:seon.gym/path path})))
             :seon.gym/allow-paid? true})
          (.then (fn [resp]
                   (if (false? (:seon.gym/ok? resp))
                     (is false (str path " refused — " (:seon.gym/error resp)))
                     (do (gym/print-scorecard! resp)
                         (is (m/validate :seon.gym/scorecard resp)
                             (str path " produced a valid scorecard "
                                  "(pass/fail NOT asserted — honest reds "
                                  "are the data)"))))
                   (done)))
          (.catch (fn [e] (is false (str path " threw — " e)) (done)))))))

;; ---------------------------------------------------------------------------
;; Harness-integrity unit tests (gym-upgrade §3.1 + §3.3) — free, ungated.
;; ---------------------------------------------------------------------------

(deftest enabled?-is-an-exact-match-split
  ;; §3.3 falsification: the pure gate decision, every documented case.
  (is (true?  (enabled? "s32" :s32))       "\"s32\" enables s32")
  (is (false? (enabled? "s32" :s21))       "\"s32\" enables ONLY s32")
  (is (false? (enabled? "s32" :s12))       "\"s32\" enables ONLY s32")
  (is (true?  (enabled? "s32,s21" :s32))   "\"s32,s21\" enables s32")
  (is (true?  (enabled? "s32,s21" :s21))   "\"s32,s21\" enables s21")
  (is (false? (enabled? "s32,s21" :s12))   "\"s32,s21\" enables EXACTLY those")
  (is (every? #(enabled? "all" %) paid-scenario-keys)
      "\"all\" enables every paid scenario")
  (is (not-any? #(enabled? "" %) paid-scenario-keys)
      "\"\" (unset) enables none")
  (is (false? (enabled? "s3" :s32))
      "a prefix is NOT a match — exact key names only"))

(deftest done-fires-exactly-once-across-then-and-catch
  ;; §3.1 falsification: force a rejection INSIDE the .then body (after
  ;; the continuation fired — the S-12 shape) and assert the guarded
  ;; continuation ran exactly once across the .then + .catch chain.
  (async done
    (let [!fires  (atom 0)
          guarded (call-once #(swap! !fires inc))]
      (-> (js/Promise.resolve :scorecard)
          (.then (fn [_]
                   (guarded)
                   (throw (js/Error. "thrown back through done — S-12 repro"))))
          (.catch (fn [_]
                    (guarded)
                    (is (= 1 @!fires)
                        "done invoked exactly once despite then+catch both firing")
                    (done)))))))

;; ---------------------------------------------------------------------------
;; The paid scenarios — env-gated, never run on a bare bin/test-cljs.
;; ---------------------------------------------------------------------------

(deftest s32-consult-before-research-paid
  (async done
    (run-paid! :s32
               "test/seon/gym/scenarios/s32-consult-before-research.edn"
               done)))

(deftest s21-log-workout-existing-schema-paid
  (async done
    (run-paid! :s21
               "test/seon/gym/scenarios/s21-log-workout-existing-schema.edn"
               done)))

(deftest s12-run8-two-agent-consultation-paid
  (async done
    (run-paid! :s12
               "test/seon/gym/scenarios/consults-findings-run8.edn"
               done)))
