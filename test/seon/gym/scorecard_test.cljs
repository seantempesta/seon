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
    [clojure.string :as str]
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

(defn- measure
  "A measure fixture. `:blocks` is an optional [[block-name tokens] …]
   vector that feeds the per-block token axis (and `total-tokens` when
   given — else `tokens` is the flat total with no per-block breakdown)."
  [id tokens & {:keys [blocks] :or {blocks []}}]
  {:seon.gym.scorecard/scenario id
   :seon.gym/total-tokens       tokens
   :seon.gym/turn-profile       {:seon.gym.profile/agent        :a
                                 :seon.gym.profile/blocks       (mapv first blocks)
                                 :seon.gym.profile/block-tokens (mapv vec blocks)}})

(defn- card
  [id pass? & {:keys [err canvas judge-results toolkit]
               :or   {err 0.0 canvas false
                      toolkit {:my.data 0 :my.ui 0 :my.canvas 0}}}]
  (cond-> {:seon.gym.scorecard/scenario       id
           :seon.gym.scorecard/git-sha        "deadbee"
           :seon.gym.scorecard/run-id         (random-uuid)
           :seon.gym.scorecard/tier           :stub
           :seon.gym.scorecard/at             (js/Date.)
           :seon.gym.scorecard/agent-id       "abc-2606280000"
           :seon.gym.scorecard/pass?          pass?
           :seon.gym.scorecard/eval-error-rate err
           :seon.gym.scorecard/canvas-updated? canvas
           :seon.gym.scorecard/toolkit-calls  toolkit
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
        agg (sc/aggregate {:seon.gym/scenarios        scenarios
                           :seon.gym.battery/measures  measures
                           :seon.gym.battery/card-runs (mapv vector cards)
                           :seon.gym.battery/sha       "abc1234"
                           :seon.gym.battery/at        now})]
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
        "no judge ran (free mode) → judge-mean absent, never a misleading 0")
    ;; pass^k at the default k=1: each scenario is one run, so the rate is
    ;; the boolean as a fraction. The refused db-memory member contributes
    ;; NO pass^k entry (nothing scored).
    (is (= 1 (:seon.gym.battery/k agg)) "default battery k is 1")
    (is (= 2 (count (:seon.gym.battery/pass-k agg)))
        "two scored scenarios → two pass^k summaries; the refusal is absent")
    (is (= 0.5 (:seon.gym.battery/pass-rate agg))
        "mean pass-rate across (honesty 1.0, planning 0.0) = 0.5")
    (let [by-id (into {} (map (juxt :seon.gym.scorecard/scenario identity))
                      (:seon.gym.battery/pass-k agg))]
      (is (= 1.0 (:seon.gym.pass-k/rate (:s-honesty by-id))))
      (is (= 1   (:seon.gym.pass-k/k (:s-honesty by-id)))
          "one run scored at k=1")
      (is (= 0.0 (:seon.gym.pass-k/rate (:s-planning by-id)))))))

(deftest aggregate-surfaces-per-block-tokens-and-toolkit-adoption
  (let [scenarios [(scen :s-ui :ui) (scen :s-mem :db-memory)]
        ;; two measures, each with a per-block breakdown — the per-block
        ;; axis must SUM each block by name (so a block-level trim is
        ;; visible, not buried in total-tokens).
        measures  [(measure :s-ui 300
                            :blocks [[:namespaces 200] [:transcript 50]
                                     [:canvas 50]])
                   (measure :s-mem 220
                            :blocks [[:namespaces 120] [:transcript 100]])]
        ;; s-ui's agent CALLED the toolkit; s-mem hand-rolled (0×) — the
        ;; exact #42 signature: a render-prominence drop reads as 0 calls.
        cards     [(card :s-ui  true  :toolkit {:my.data 3 :my.ui 2 :my.canvas 1})
                   (card :s-mem true  :toolkit {:my.data 0 :my.ui 0 :my.canvas 0})]
        agg (sc/aggregate {:seon.gym/scenarios        scenarios
                           :seon.gym.battery/measures  measures
                           :seon.gym.battery/card-runs (mapv vector cards)
                           :seon.gym.battery/sha       "abc1234"
                           :seon.gym.battery/at        now})]
    (is (m/validate :seon.gym/battery-scorecard agg)
        "the aggregate (with the new axes) still validates")
    (is (= {:namespaces 320 :transcript 150 :canvas 50}
           (:seon.gym.battery/block-tokens agg))
        "per-block tokens SUM each block by name across measures — the
         :namespaces trim would move ON ITS OWN KEY")
    (is (= 520 (:seon.gym.battery/total-tokens agg))
        "total-tokens still sums (300 + 220), kept alongside per-block")
    (is (= {:my.data 3 :my.ui 2 :my.canvas 1}
           (:seon.gym.battery/toolkit-calls agg))
        "toolkit-calls sums across scored cards — a context change that
         drops these toward 0 is the #42 render-prominence regression")))

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
        agg (sc/aggregate {:seon.gym/scenarios        scenarios
                           :seon.gym.battery/measures  measures
                           :seon.gym.battery/card-runs (mapv vector cards)
                           :seon.gym.battery/sha       "abc1234"
                           :seon.gym.battery/at        now})]
    (is (= 80.0 (:seon.gym.battery/judge-mean agg))
        "judge-mean averages only REAL graded verdicts, never SKIPPED ones")))

(defn- judged
  "A graded (non-SKIPPED) judge-result at `score`."
  [score]
  {:seon.gym.predicate/id   :judge-board
   :seon.gym.predicate/axis :replies-honestly
   :seon.gym.judge/pass?    (>= score 50)
   :seon.gym.judge/score    score
   :seon.gym.judge/justification "graded"})

(deftest aggregate-pass-k-rolls-noise-robust-rate
  ;; ONE paid scenario driven 3× — two passes, one flake (the
  ;; canvas-goal-board single-sample miss). The rollup must report a RATE
  ;; (2/3), not a boolean, so model variance can't masquerade as a
  ;; regression; the per-axis distribution (canvas count, toolkit range,
  ;; judge mean) makes the variance visible.
  (let [scenarios [(scen :s-canvas :ui)]
        measures  [(measure :s-canvas 100)]
        runs      [[(card :s-canvas true  :canvas true
                          :toolkit {:my.data 2 :my.ui 0 :my.canvas 0}
                          :judge-results [(judged 90)])
                    (card :s-canvas false :canvas true   ; the flake
                          :toolkit {:my.data 0 :my.ui 0 :my.canvas 0}
                          :judge-results [(judged 40)])
                    (card :s-canvas true  :canvas true
                          :toolkit {:my.data 5 :my.ui 0 :my.canvas 0}
                          :judge-results [(judged 85)])]]
        agg (sc/aggregate {:seon.gym/scenarios        scenarios
                           :seon.gym.battery/measures  measures
                           :seon.gym.battery/card-runs runs
                           :seon.gym.battery/sha       "abc1234"
                           :seon.gym.battery/at        now
                           :seon.gym.battery/k         3})
        pk  (first (:seon.gym.battery/pass-k agg))]
    (is (m/validate :seon.gym/battery-scorecard agg)
        "the k=3 aggregate validates")
    (is (= 3 (:seon.gym.battery/k agg)))
    (is (= 1 (count (:seon.gym.battery/pass-k agg))))
    (is (= :s-canvas (:seon.gym.scorecard/scenario pk)))
    (is (= 3 (:seon.gym.pass-k/k pk)) "all three runs scored")
    (is (= 2 (:seon.gym.pass-k/passes pk)) "two of three passed")
    (is (< 0.66 (:seon.gym.pass-k/rate pk) 0.67)
        "rate is 2/3 — the flake AVERAGED OUT, not read as a regression")
    (is (< 0.66 (:seon.gym.battery/pass-rate agg) 0.67)
        "battery pass-rate is the mean over scenarios (one here)")
    (is (= 3 (:seon.gym.pass-k/canvas-updated-count pk))
        "all three runs drove the canvas — a stable axis across the flake")
    (is (= 0 (:seon.gym.pass-k/toolkit-calls-min pk)))
    (is (= 5 (:seon.gym.pass-k/toolkit-calls-max pk))
        "toolkit-call range spans the k runs")
    (is (= 0.0 (:seon.gym.pass-k/eval-error-rate-mean pk)))
    (is (= (/ (+ 90.0 40 85) 3) (:seon.gym.pass-k/judge-mean pk))
        "judge mean across the k runs"))
  ;; a scenario whose every run REFUSED (paid in free mode) contributes NO
  ;; pass^k summary and doesn't drag the rate.
  (let [scenarios [(scen :s-refused :db-memory)]
        agg (sc/aggregate {:seon.gym/scenarios        scenarios
                           :seon.gym.battery/measures  [(measure :s-refused 10)]
                           :seon.gym.battery/card-runs [[(refusal "paid tier")]]
                           :seon.gym.battery/sha       "abc1234"
                           :seon.gym.battery/at        now
                           :seon.gym.battery/k         3})]
    (is (= [] (:seon.gym.battery/pass-k agg))
        "an all-refused scenario yields no pass^k summary")
    (is (= 0.0 (:seon.gym.battery/pass-rate agg)))))

(deftest aggregate-empty-battery-is-honest-zero
  (let [agg (sc/aggregate {:seon.gym/scenarios        []
                           :seon.gym.battery/measures  []
                           :seon.gym.battery/card-runs []
                           :seon.gym.battery/sha       "abc1234"
                           :seon.gym.battery/at        now})]
    (is (m/validate :seon.gym/battery-scorecard agg))
    (is (= 0 (:seon.gym.battery/total-tokens agg)))
    (is (= {} (:seon.gym.battery/block-tokens agg))
        "no measures → no per-block tokens")
    (is (= {:my.data 0 :my.ui 0 :my.canvas 0}
           (:seon.gym.battery/toolkit-calls agg))
        "empty battery → honest all-zero toolkit-calls, never an absent map")
    (is (= 0.0 (:seon.gym.battery/eval-error-rate agg)))
    (is (= {} (:seon.gym.battery/per-competency agg)))
    (is (= [] (:seon.gym.battery/pass-k agg))
        "empty battery → no pass^k summaries")
    (is (= 0.0 (:seon.gym.battery/pass-rate agg))
        "empty battery → honest 0.0 pass-rate, never an absent key")
    (is (= 1 (:seon.gym.battery/k agg)) "default k surfaces even when empty")))

;; ---------------------------------------------------------------------------
;; format-line / append! — the greppable line + the trend-log append.
;; ---------------------------------------------------------------------------

(deftest format-line-is-greppable-and-round-trips
  (let [agg (sc/aggregate {:seon.gym/scenarios        []
                           :seon.gym.battery/measures  []
                           :seon.gym.battery/card-runs []
                           :seon.gym.battery/sha       "abc1234"
                           :seon.gym.battery/at        now})
        line (sc/format-line agg)]
    (is (re-find #"^SEON-GYM SCORECARD-BATTERY " line)
        "the greppable prefix is present and distinct from per-scenario cards")))

(deftest append-writes-one-edn-line
  (let [fs   (js/require "node:fs")
        path (str "tmp/gym-scorecard-test-" (.now js/Date) ".log")
        agg  (sc/aggregate {:seon.gym/scenarios        []
                            :seon.gym.battery/measures  []
                            :seon.gym.battery/card-runs []
                            :seon.gym.battery/sha       "abc1234"
                            :seon.gym.battery/at        now})]
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
            ;; pass^k: drive each REAL paid scenario k times (default 1) so
            ;; the battery reports a pass-RATE, not a single noisy sample.
            k         (let [n (js/parseInt (str (or (env "SEON_GYM_K") "1")) 10)]
                        (if (and (js/Number.isInteger n) (pos? n)) n 1))
            ;; SEON_GYM_ONLY=id,id — restrict the battery to named scenario
            ;; ids (a FOCUSED paid run, e.g. canvas-goal-board ×k, without
            ;; spending on every paid member). Empty = the whole battery.
            only      (->> (str/split (str (or (env "SEON_GYM_ONLY") "")) #",")
                           (map str/trim) (remove empty?) (map keyword) set)
            log       (or (env "SEON_GYM_LOG") default-log)
            all       (:seon.gym/scenarios
                        (sc/load-battery-scenarios!
                          {:seon.gym.battery/dir "test/seon/gym/scenarios"}))
            scenarios (if (seq only)
                        (filterv #(contains? only (:seon.gym.scenario/id %)) all)
                        all)]
        (println "SEON-GYM SCORECARD-BATTERY-START sha=" sha
                 "scenarios=" (count scenarios) "allow-paid?=" allow? "k=" k)
        (-> (sc/run-battery!
              (cond-> {:seon.gym/scenarios       scenarios
                       :seon.gym.battery/sha      sha
                       :seon.gym.battery/at       at}
                allow?     (assoc :seon.gym/allow-paid? true)
                (> k 1)    (assoc :seon.gym/k k)))
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
