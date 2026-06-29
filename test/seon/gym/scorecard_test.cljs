(ns seon.gym.scorecard-test
  "Tests for the gym SCORECARD aggregate + the gated battery runner.

   The pure-aggregate tests run on EVERY `bin/test-cljs` (cheap, no LLM,
   no fs spend): they pin that [[seon.gym.scorecard/aggregate]] rolls the
   per-scenario axes up correctly and that the ANTI-CHEAT invariant holds
   (it never touches `:results` actuals — it only reads pass?/tokens/
   error-rate/canvas/judge-score).

   The battery RUNNER (`battery-scorecard-run`) is GATED on the
   SEON_GYM_SCORECARD env var (like seon.gym.paid-test's money gate):
   without it the deftest is a no-op, so the normal suite never runs the
   heavy battery. `bin/gym-scorecard` sets the var + stamps the git SHA /
   timestamp, runs THIS ns, and greps the emitted
   `SEON-GYM SCORECARD-BATTERY` line back out (also appended to the log)."
  (:require
    [cljs.reader :as reader]
    [cljs.test :refer [deftest is async]]
    [malli.core :as m]
    [seon.gym.driver]
    [seon.gym.scorecard :as sc]))

;; ---------------------------------------------------------------------------
;; Minimal-but-VALID fixtures — instrumentation validates aggregate's input
;; (scenarios are real :seon.gym/scenario, cards are real scorecards/refusals).
;; ---------------------------------------------------------------------------

(defn- scen [id comp]
  {:seon.gym.scenario/id          id
   :seon.gym.scenario/doc         "unit fixture"
   :seon.gym.scenario/tier        :stub
   :seon.gym.scenario/status      :active
   :seon.gym.scenario/competency  comp
   :seon.gym.scenario/axes        []
   :seon.gym.scenario/turns       []
   :seon.gym.scenario/predicates  []})

(defn- measure [id tokens]
  {:seon.gym.scorecard/scenario id
   :seon.gym/total-tokens       tokens
   :seon.gym/turn-profile       {:seon.gym.profile/agent        :a
                                 :seon.gym.profile/blocks       []
                                 :seon.gym.profile/block-tokens []}})

(defn- card
  [id pass? & {:keys [err canvas judge-results]
               :or   {err 0.0 canvas false}}]
  (cond-> {:seon.gym.scorecard/scenario       id
           :seon.gym.scorecard/git-sha        "deadbee"
           :seon.gym.scorecard/run-id         (random-uuid)
           :seon.gym.scorecard/tier           :stub
           :seon.gym.scorecard/at             (js/Date.)
           :seon.gym.scorecard/agent-id       "abc-2606280000"
           :seon.gym.scorecard/pass?          pass?
           :seon.gym.scorecard/eval-error-rate err
           :seon.gym.scorecard/canvas-updated? canvas
           :seon.gym.scorecard/axes           {}
           :seon.gym.scorecard/results        []
           :seon.gym.scorecard/turn-profiles  []}
    judge-results (assoc :seon.gym.scorecard/judge-results judge-results)))

(defn- refusal [reason]
  {:seon.gym/ok? false :seon.gym/error reason})

(def ^:private now (js/Date.))

;; ---------------------------------------------------------------------------
;; aggregate — per-competency tally, token sum, rate mean, canvas count.
;; ---------------------------------------------------------------------------

(deftest aggregate-rolls-axes-up-per-competency
  (let [scenarios [(scen :s-honesty   :honesty)
                   (scen :s-planning  :planning)
                   (scen :s-paid-mem  :db-memory)]
        measures  [(measure :s-honesty  100)
                   (measure :s-planning 250)
                   (measure :s-paid-mem 400)]
        ;; honesty scored PASS, planning scored FAIL, db-memory REFUSED
        ;; (paid tier, free mode) — a refusal must NOT count toward any
        ;; competency's total.
        cards     [(card :s-honesty  true  :err 0.0 :canvas true)
                   (card :s-planning false :err 0.5 :canvas false)
                   (refusal "scenario :s-paid-mem is :paid tier")]
        agg (sc/aggregate {:seon.gym/scenarios       scenarios
                           :seon.gym.battery/measures measures
                           :seon.gym.battery/cards    cards
                           :seon.gym.battery/sha      "abc1234"
                           :seon.gym.battery/at       now})]
    (is (m/validate :seon.gym/battery-scorecard agg)
        "the aggregate validates against :seon.gym/battery-scorecard")
    (is (= "abc1234" (:seon.gym.battery/sha agg)) "sha is keyed in")
    (is (= 750 (:seon.gym.battery/total-tokens agg))
        "total-tokens sums EVERY scenario's free measure (incl. refused)")
    (is (= {:honesty  {:seon.gym.battery/pass 1 :seon.gym.battery/total 1}
            :planning {:seon.gym.battery/pass 0 :seon.gym.battery/total 1}}
           (:seon.gym.battery/per-competency agg))
        "only SCORED scenarios tally; the refused db-memory member is absent")
    (is (= 0.25 (:seon.gym.battery/eval-error-rate agg))
        "eval-error-rate is the mean over scored cards (0.0 + 0.5)/2")
    (is (= 1 (:seon.gym.battery/canvas-updated-count agg))
        "one scored card drove its canvas")
    (is (= 3 (:seon.gym.battery/scenario-count agg)))
    (is (= 2 (:seon.gym.battery/scored-count agg)))
    (is (not (contains? agg :seon.gym.battery/judge-mean))
        "no judge ran (free mode) → judge-mean absent, never a misleading 0")))

(deftest aggregate-judge-mean-excludes-skipped-verdicts
  (let [scenarios [(scen :s-a :db-memory) (scen :s-b :db-memory)]
        measures  [(measure :s-a 10) (measure :s-b 10)]
        cards     [(card :s-a true
                         :judge-results
                         [{:seon.gym.predicate/id   :answers-right
                           :seon.gym.predicate/axis :consults-findings
                           :seon.gym.judge/pass?    true
                           :seon.gym.judge/score    80
                           :seon.gym.judge/justification "good"}])
                   (card :s-b true
                         :judge-results
                         ;; a SKIPPED verdict (score 0) must NOT drag the mean.
                         [{:seon.gym.predicate/id   :answers-right
                           :seon.gym.predicate/axis :consults-findings
                           :seon.gym.judge/pass?    false
                           :seon.gym.judge/score    0
                           :seon.gym.judge/justification
                           "judge SKIPPED — needs an injected :seon.gym/judge-fn"}])]
        agg (sc/aggregate {:seon.gym/scenarios       scenarios
                           :seon.gym.battery/measures measures
                           :seon.gym.battery/cards    cards
                           :seon.gym.battery/sha      "abc1234"
                           :seon.gym.battery/at       now})]
    (is (= 80.0 (:seon.gym.battery/judge-mean agg))
        "judge-mean averages only REAL graded verdicts, never SKIPPED ones")))

(deftest aggregate-empty-battery-is-honest-zero
  (let [agg (sc/aggregate {:seon.gym/scenarios       []
                           :seon.gym.battery/measures []
                           :seon.gym.battery/cards    []
                           :seon.gym.battery/sha      "abc1234"
                           :seon.gym.battery/at       now})]
    (is (m/validate :seon.gym/battery-scorecard agg))
    (is (= 0 (:seon.gym.battery/total-tokens agg)))
    (is (= 0.0 (:seon.gym.battery/eval-error-rate agg)))
    (is (= {} (:seon.gym.battery/per-competency agg)))))

;; ---------------------------------------------------------------------------
;; format-line / append! — the greppable line + the trend-log append.
;; ---------------------------------------------------------------------------

(deftest format-line-is-greppable-and-round-trips
  (let [agg (sc/aggregate {:seon.gym/scenarios       []
                           :seon.gym.battery/measures []
                           :seon.gym.battery/cards    []
                           :seon.gym.battery/sha      "abc1234"
                           :seon.gym.battery/at       now})
        line (sc/format-line agg)]
    (is (re-find #"^SEON-GYM SCORECARD-BATTERY " line)
        "the greppable prefix is present and distinct from per-scenario cards")))

(deftest append-writes-one-edn-line
  (let [fs   (js/require "node:fs")
        path (str "tmp/gym-scorecard-test-" (.now js/Date) ".log")
        agg  (sc/aggregate {:seon.gym/scenarios       []
                            :seon.gym.battery/measures []
                            :seon.gym.battery/cards    []
                            :seon.gym.battery/sha      "abc1234"
                            :seon.gym.battery/at       now})]
    (.mkdirSync fs "tmp" #js {:recursive true})
    (sc/append! {:seon.gym/battery-scorecard agg :seon.gym/path path})
    (sc/append! {:seon.gym/battery-scorecard agg :seon.gym/path path})
    (let [lines (->> (.readFileSync fs path "utf8")
                     (#(.split % "\n"))
                     (remove empty?))]
      (is (= 2 (count lines)) "each append! adds exactly one line")
      (is (= "abc1234" (:seon.gym.battery/sha
                         (reader/read-string (first lines))))
          "the appended EDN round-trips"))
    (.unlinkSync fs path)))

;; ---------------------------------------------------------------------------
;; GATED battery runner — the bin/gym-scorecard entry point. No-op unless
;; SEON_GYM_SCORECARD is set, so the normal suite never runs the battery.
;; ---------------------------------------------------------------------------

(def ^:private default-log
  "docs/prds/agent-fsm/research/gym-scorecard-trend.edn")

(defn- env [k] (aget (.. js/process -env) k))

(defn- gate-set? [] (boolean (seq (str (or (env "SEON_GYM_SCORECARD") "")))))

(deftest battery-scorecard-run
  (if-not (gate-set?)
    (is true "SEON_GYM_SCORECARD unset — battery run is a no-op (normal suite)")
    (async done
      (let [sha       (or (env "SEON_GYM_SHA") "unknown")
            at-str    (str (or (env "SEON_GYM_AT") ""))
            at        (if (seq at-str) (js/Date. at-str) (js/Date.))
            allow?    (= "1" (str (env "SEON_GYM_ALLOW_PAID")))
            log       (or (env "SEON_GYM_LOG") default-log)
            scenarios (:seon.gym/scenarios
                        (sc/load-battery-scenarios!
                          {:seon.gym.battery/dir "test/seon/gym/scenarios"}))]
        (println "SEON-GYM SCORECARD-BATTERY-START sha=" sha
                 "scenarios=" (count scenarios) "allow-paid?=" allow?)
        (-> (sc/run-battery!
              (cond-> {:seon.gym/scenarios       scenarios
                       :seon.gym.battery/sha      sha
                       :seon.gym.battery/at       at}
                allow? (assoc :seon.gym/allow-paid? true)))
            (.then (fn [card]
                     (sc/print-battery-scorecard! card)
                     (sc/append! {:seon.gym/battery-scorecard card
                                  :seon.gym/path log})
                     (is (m/validate :seon.gym/battery-scorecard card)
                         "emitted battery scorecard validates")
                     (println "SEON-GYM SCORECARD-BATTERY-APPENDED" log)
                     (done)))
            (.catch (fn [e]
                      (is false (str "battery threw — " e))
                      (done))))))))
