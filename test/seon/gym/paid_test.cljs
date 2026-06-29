(ns seon.gym.paid-test
  "PAID-TIER gym runs — the live behavioral measurements.

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

(def ^:private operator-ai-env
  "Snapshot of the SEON_AI_* env vars at BUNDLE LOAD — i.e. before any
   deftest runs. seon.ai-test's env-row tests mutate process.env and
   `js-delete` the vars in their finally blocks, deleting the
   OPERATOR's values along with their own — a paid run later in the
   same suite then silently loses its provider steering (observed
   2026-06-12: SEON_AI_PROVIDER=anthropic wiped mid-suite → the todo
   scenario drove DeepSeek). [[restore-operator-ai-env!]] replays this
   snapshot before every paid drive — the general defense against ANY
   env-mutating test earlier in the suite."
  (let [env (.. js/process -env)]
    (into {}
          (keep (fn [k] (when-some [v (aget env k)] [k v])))
          ["SEON_AI_PROVIDER" "SEON_AI_MODEL" "SEON_AI_TEMPERATURE"
           "SEON_AI_MAX_TOKENS" "SEON_AI_THINKING" "SEON_AI_TIMEOUT_MS"])))

(defn- restore-operator-ai-env!
  "Re-assert the load-time SEON_AI_* env snapshot (see
   [[operator-ai-env]]) onto process.env."
  []
  (let [env (.. js/process -env)]
    (doseq [[k v] operator-ai-env]
      (aset env k v))))

(def ^:private paid-scenario-keys
  "Every paid-tier scenario key this ns can drive — the resolved
   enabled-set in the PAID-GATE line is computed against this roster.
   `:calib` is the JUDGE CALIBRATION probe (not a scenario drive — it
   grades a canned good/bad reply pair to prove the judge discriminates);
   the `x*` keys are the cross-session A→B baseline scenarios; the
   `canvas-*` keys are the :ui-competency canvas-as-primary scenarios."
  [:s32 :s21 :s12 :todo :resume :err :calib :x1 :x3 :x12 :xcat :ab
   :canvas-budget :canvas-goal])

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

(def ^:private !paid-in-flight?
  "True while a paid scenario drive is awaiting its scorecard — the
   exit interposer below refuses to let the process die mid-run."
  (atom false))

(defonce ^:private exit-interposer
  ;; PREMATURE-EXIT GUARD + DIAGNOSIS (2026-06-12, Opus measurement
  ;; unit): four paid runs in a row died SILENTLY mid-scenario (turn 8
  ;; / turn 1 / turn 2 — exit 0 or 1, no stack, stdout truncated
  ;; mid-write, scorecard lost) while short paid runs and the free
  ;; suite complete fine. shadow.test.node ends the suite with
  ;; js/process.exit, which DROPS buffered stdout — consistent with
  ;; every symptom IF something signals suite-completion early (the
  ;; S-12 double-done class re-enters cljs.test's continuation; one
  ;; fork reaches the runner's exit while the paid drive is still in
  ;; flight). This interposer (armed ONLY under the paid gate):
  ;;   - logs every process.exit call WITH the caller's stack via a
  ;;     SYNCHRONOUS stderr write (survives the exit),
  ;;   - DEFERS the exit while a paid scenario is in flight, so the
  ;;     run finishes and the scorecard lands.
  ;; If a death happens with no interposer line in stderr, the killer
  ;; is NOT process.exit (signal/OOM) — diagnostic either way.
  (when (seq (gate-value))
    (let [original (.bind (.-exit js/process) js/process)]
      (set! (.-exit js/process)
            (fn guarded-exit [code]
              (let [stack (.-stack (js/Error. "process.exit caller"))]
                (.writeSync (js/require "node:fs") 2
                            (str "SEON-GYM EXIT-INTERPOSER code=" code
                                 " paid-in-flight=" @!paid-in-flight?
                                 "\n" stack "\n")))
              (if @!paid-in-flight?
                (js/console.error
                  "SEON-GYM EXIT-INTERPOSER: paid run in flight — exit DEFERRED")
                (original code))))
      :armed)))

(defonce ^:private paid-keepalive
  ;; Event-loop ANCHOR (2026-06-12, Opus measurement unit): paid
  ;; agentic runs await promise chains that are not always anchored to
  ;; a live libuv handle — observed THREE TIMES tonight: node exited
  ;; silently MID-TURN (twice ~85s in, once at turn 8/exit 0 right
  ;; after a test-run tx committed) with no error, no test summary, no
  ;; scorecard. A drained event loop is a clean node exit even with a
  ;; pending await. This interval keeps the loop alive for the whole
  ;; paid suite; shadow.test.node process.exit's at suite end, so it
  ;; never outlives the run. Armed only when the paid gate is on —
  ;; free suites are unaffected.
  (when (seq (gate-value))
    (js/setInterval (fn keepalive-tick [] nil) 10000)))

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
      (do
        (restore-operator-ai-env!)
        (reset! !paid-in-flight? true)
        (-> (gym/run-scenario!
              {:seon.gym/scenario
               (first (:seon.gym/scenarios
                        (gym/load-scenarios! {:seon.gym/path path})))
               :seon.gym/allow-paid? true})
            (.then (fn [resp]
                     (reset! !paid-in-flight? false)
                     (if (false? (:seon.gym/ok? resp))
                       (is false (str path " refused — " (:seon.gym/error resp)))
                       (do (gym/print-scorecard! resp)
                           ;; DURABLE card (2026-06-12): process.exit
                           ;; drops buffered stdout — a card that only
                           ;; ever existed as a println was LOST on
                           ;; three paid runs. writeFileSync survives.
                           (.writeFileSync
                             (js/require "node:fs")
                             (str "tmp/gym-paid-card-" (name k) "-"
                                  (:seon.gym.scorecard/run-id resp) ".edn")
                             (pr-str resp))
                           (is (m/validate :seon.gym/scorecard resp)
                               (str path " produced a valid scorecard "
                                    "(pass/fail NOT asserted — honest reds "
                                    "are the data)"))))
                     (done)))
            (.catch (fn [e]
                      (reset! !paid-in-flight? false)
                      (is false (str path " threw — " e)) (done))))))))

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

(deftest todo-multistep-tracking-paid
  (async done
    (run-paid! :todo
               "test/seon/gym/scenarios/todo-multistep-tracking.edn"
               done)))

(deftest plan-resume-across-restart-paid
  (async done
    (run-paid! :resume
               "test/seon/gym/scenarios/plan-resume-across-restart.edn"
               done)))

(deftest err-recovery-unregistered-attr-paid
  (async done
    (run-paid! :err
               "test/seon/gym/scenarios/err-recovery-unregistered-attr.edn"
               done)))

(deftest x1-subscriptions-total-and-max-paid
  (async done
    (run-paid! :x1
               "test/seon/gym/scenarios/x1-subscriptions-total-and-max.edn"
               done)))

(deftest x3-expense-reuse-and-category-total-paid
  (async done
    (run-paid! :x3
               "test/seon/gym/scenarios/x3-expense-reuse-and-category-total.edn"
               done)))

(deftest x12-narrow-question-no-over-retrieval-paid
  (async done
    (run-paid! :x12
               "test/seon/gym/scenarios/x12-narrow-question-no-over-retrieval.edn"
               done)))

(deftest x-category-argmax-paid
  (async done
    (run-paid! :xcat
               "test/seon/gym/scenarios/x-category-argmax.edn"
               done)))

(deftest canvas-budget-breakdown-paid
  (async done
    (run-paid! :canvas-budget
               "test/seon/gym/scenarios/canvas-budget-breakdown.edn"
               done)))

(deftest canvas-goal-board-paid
  (async done
    (run-paid! :canvas-goal
               "test/seon/gym/scenarios/canvas-goal-board.edn"
               done)))

;; ---------------------------------------------------------------------------
;; CONFIG A/B — the context-improvement loop's LIVE proof. Drives ONE
;; memory scenario under :default (full ctx) then under the lean
;; manifest (drops :live-tile, keeps :namespaces), BOTH via the real
;; provider, and prints both scorecards' pass? + turn-1 ctx token totals.
;; pass/fail is NOT asserted (honest reds are the data); the measurement
;; is whether the lean context still passes the memory scenario at a
;; smaller token cost. Gate key :ab. Greppable: `SEON-GYM CONFIG-AB`.
;; ---------------------------------------------------------------------------

(defn- profile-tokens [card]
  (reduce + 0 (mapcat (fn [p] (map second (:seon.gym.profile/block-tokens p)))
                      (take 1 (:seon.gym.scorecard/turn-profiles card)))))

(deftest config-ab-memory-paid
  (async done
    (let [done (call-once done)]
      (if-not (enabled? (gate-value) :ab)
        (do (is true "ab skipped — set SEON_GYM_PAID=ab (or all) to run the config A/B")
            (done))
        (let [s   (first (:seon.gym/scenarios
                           (gym/load-scenarios!
                             {:seon.gym/path
                              "test/seon/gym/scenarios/x1-subscriptions-total-and-max.edn"})))
              run (fn [cfg]
                    (restore-operator-ai-env!)
                    (gym/run-scenario!
                      (cond-> {:seon.gym/scenario s :seon.gym/allow-paid? true}
                        cfg (assoc :seon.gym/config cfg))))]
          (reset! !paid-in-flight? true)
          (-> (run nil)
              (.then (fn [full]
                       (gym/print-scorecard! full)
                       (.then (run {:seon.gym.config/path
                                    "test/seon/gym/configs/lean-no-live-tile.edn"})
                              (fn [lean] [full lean]))))
              (.then (fn [[full lean]]
                       (reset! !paid-in-flight? false)
                       (gym/print-scorecard! lean)
                       (println "SEON-GYM CONFIG-AB"
                                (pr-str {:default/pass? (:seon.gym.scorecard/pass? full)
                                         :default/tokens (profile-tokens full)
                                         :lean/pass? (:seon.gym.scorecard/pass? lean)
                                         :lean/tokens (profile-tokens lean)}))
                       (is (m/validate :seon.gym/scorecard full)
                           "default-config scorecard validates")
                       (is (m/validate :seon.gym/scorecard lean)
                           "lean-config scorecard validates (pass/fail NOT asserted)")
                       (done)))
              (.catch (fn [e]
                        (reset! !paid-in-flight? false)
                        (is false (str "config A/B threw — " e)) (done)))))))))

;; ---------------------------------------------------------------------------
;; JUDGE CALIBRATION — the live DeepSeek discrimination proof (design §3 /
;; the owner's "make the judge a trustworthy signal" steer). Grades a
;; canned GOOD reply (must PASS) and a fabricated BAD reply (must FAIL) for
;; the SAME rubric + reference, through the real judge. discriminates? must
;; be true — a judge that can't tell a fabrication from the truth is noise,
;; not a primary signal. Greppable: `SEON-GYM JUDGE-CALIB`.
;; ---------------------------------------------------------------------------

(deftest judge-calibration-deepseek
  (async done
    (let [done (call-once done)]
      (if-not (enabled? (gate-value) :calib)
        (do (is true "calib skipped — set SEON_GYM_PAID=calib (or all)")
            (done))
        (do
          (restore-operator-ai-env!)
          (reset! !paid-in-flight? true)
          (-> (gym/calibrate-judge!
                {:seon.gym.calib/question
                 "Where does seon validate an entity value's type during a transact, and what comes back when it doesn't conform?"
                 :seon.gym.calib/rubric
                 "PASS only if the reply locates the per-value Malli validation in src/seon/db/internal.cljs (validate-entity-values!) AND states the non-conforming value comes back to the caller as the VALUE {:seon.db/ok? false :seon.db/error ...} (the caller's promise resolves, it does not throw). FAIL on a fabricated file/function or a claim that the caller receives a thrown exception."
                 :seon.gym.calib/reference
                 "validate-entity-values! in src/seon/db/internal.cljs Malli-validates every entity value against its registered attr schema during transact. It throws INTERNALLY, but the public face seon.db/transact! (src/seon/db.cljs) catches every rejection — a non-conforming value resolves to {:seon.db/ok? false :seon.db/error ...}; the caller never sees a throw."
                 :seon.gym.calib/good-reply
                 "seon.db/transact! validates each entity value via validate-entity-values! in src/seon/db/internal.cljs (Malli per-value check). When a value doesn't conform you get the error back as a VALUE — {:seon.db/ok? false :seon.db/error {...}} — the internal validator throws but transact! catches it, so the caller's promise resolves rather than rejecting/throwing."
                 :seon.gym.calib/bad-reply
                 "Value validation lives in src/seon/schema/validator.clj, and on a bad value transact! raises a SchemaValidationException straight to the caller, who must wrap the call in try/catch to recover."
                 :seon.gym/allow-paid? true})
              (.then (fn [{:seon.gym.calib/keys [good bad discriminates?] :as resp}]
                       (reset! !paid-in-flight? false)
                       (println "SEON-GYM JUDGE-CALIB" (pr-str resp))
                       (is (m/validate :seon.gym.calib/response resp))
                       (is (true? (:seon.gym.judge/pass? good))
                           (str "judge PASSES the ground-truth reply — "
                                (:seon.gym.judge/justification good)))
                       (is (false? (:seon.gym.judge/pass? bad))
                           (str "judge FAILS the fabricated reply — "
                                (:seon.gym.judge/justification bad)))
                       (is (true? discriminates?)
                           "the DeepSeek judge discriminates good from bad — trustworthy signal")
                       (done)))
              (.catch (fn [e]
                        (reset! !paid-in-flight? false)
                        (is false (str "calibration threw — " e)) (done)))))))))
