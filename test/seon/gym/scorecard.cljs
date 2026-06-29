(ns seon.gym.scorecard
  "The gym SCORECARD — the fitness function for the recursive
   context-improvement loop. ONE command (`bin/gym-scorecard`) runs the
   whole competency battery, collects every axis, and appends ONE
   git-SHA-keyed line to a scorecard log, so any later change can be
   judged \"lifted the WHOLE battery or revert\" cheaply and honestly.

   Two run modes (errors-are-values: paid/todo members refuse without
   spend, so a FREE battery is always safe):

     - FREE (default): every scenario's turn-1 context SIZE via
       [[seon.gym.driver/measure-context!]] (no LLM spend) PLUS the
       mechanical/structural predicates on the runnable stub-tier
       scenarios via [[seon.gym.driver/run-scenario!]]. Cheap enough to
       run every loop iteration.
     - PAID (opt-in `:seon.gym/allow-paid? true` + the active provider's
       key): the same drive path, but the paid-tier scenarios actually
       run — adding live pass-rate, eval-error-rate, canvas-updated, and
       the LLM-judge axis on real drives.

   ANTI-CHEAT (load-bearing): the aggregate reads ONLY the axes
   (pass?/tokens/eval-error-rate/canvas-updated?/judge-score) — NEVER the
   computed ANSWERS. The scenario's expected answers live in its
   predicates' `:expect`/`:reference` (graded INSIDE run-scenario!,
   surfaced only as a boolean pass? and a judge score). Nothing here
   reads `:seon.gym.scorecard/results` actuals or any reference text, so
   it is impossible to 'improve the number' by reading an answer. The
   harness never sees the answers; it sees whether the agent got them."
  (:require
    [clojure.string :as str]
    [seon.gym.driver :as driver]
    [seon.schema :as schema]))

;; ===========================================================================
;; Schemas — the battery-scorecard aggregate. One line per (git sha) keyed
;; entry in the log; the TREND over SHAs is the curation loop's fitness
;; signal. Fully-namespaced keys; each shape references the canonical
;; per-scenario shape it rolls up (shared-shape rule).
;; ===========================================================================

;; git SHA the battery ran at — PASSED IN (scripts stamp `git rev-parse
;; --short HEAD`; a CLJS fn can't compute it). Reuses the per-scenario
;; sha shape so the keys join.
(schema/register! :seon.gym.battery/sha :seon.gym.scorecard/git-sha)
;; Wall-clock the battery ran — PASSED IN (the script stamps it).
(schema/register! :seon.gym.battery/at  :inst)
;; Per-competency pass/total tally — passes ÷ scored members of that
;; competency. ONLY runnable members count toward `total` (a refusal in
;; FREE mode is "not scored this mode", never a 0/1).
(schema/register! :seon.gym.battery/pass  [:int {:min 0}])
(schema/register! :seon.gym.battery/total [:int {:min 0}])
(schema/register! :seon.gym.battery/tally
  [:map
   [:seon.gym.battery/pass  :seon.gym.battery/pass]
   [:seon.gym.battery/total :seon.gym.battery/total]])
(schema/register! :seon.gym.battery/per-competency
  [:map-of :seon.gym.scenario/competency :seon.gym.battery/tally])
;; Summed turn-1 context size across EVERY scenario (free measure) —
;; tokens, never chars. References the canonical total-tokens shape.
(schema/register! :seon.gym.battery/total-tokens :seon.gym/total-tokens)
;; PER-BLOCK token axis: summed turn-1 context tokens across EVERY
;; scenario's free measure, keyed by context-block NAME (the block name IS
;; the discriminator — no per-block named slot). So a BLOCK-level change
;; (e.g. the #42 namespaces signature-trim, −43.5%) is VISIBLE on its own
;; key, not buried in `total-tokens` (which is confounded by scenario count
;; and dominated by non-namespaces context). Carries :namespaces /
;; :transcript / :live-tile and every other rendered block. Tokens, never
;; chars (the per-block estimates come straight off
;; :seon.gym.profile/block-tokens).
(schema/register! :seon.gym.battery/block-tokens
  [:map-of :seon.agent.ctx/name :int])
;; TOOLKIT-ADOPTION axis: summed `my.*` toolkit references across the
;; SCORED cards (did the battery's agents CALL the taught toolkit, or
;; hand-roll the footgun path?). A context change that drops these toward 0
;; is the render-prominence regression #42 — caught ONLY on a paid drive,
;; missed by the confounded total-tokens. A SIGNAL, never a gate. References
;; the per-scenario shape (shared-shape rule); FREE-mode-blind (stubs don't
;; call tools), fills on --paid.
(schema/register! :seon.gym.battery/toolkit-calls :seon.gym.scorecard/toolkit-calls)
;; Mean whole-run eval-error-rate over the SCORED scenarios (0.0 when
;; none scored). References the canonical rate shape.
(schema/register! :seon.gym.battery/eval-error-rate :seon.gym/eval-error-rate)
;; How many scored scenarios drove their own canvas this run.
(schema/register! :seon.gym.battery/canvas-updated-count [:int {:min 0}])
;; Mean LLM-judge score (0–100) over the REAL (non-skipped) judge
;; verdicts — present iff at least one real judge graded (PAID mode).
(schema/register! :seon.gym.battery/judge-mean [:double {:min 0.0 :max 100.0}])
;; Total scenarios in the battery vs how many actually scored (ran past
;; the refusal guards) — the FREE/PAID coverage denominator.
(schema/register! :seon.gym.battery/scenario-count [:int {:min 0}])
(schema/register! :seon.gym.battery/scored-count   [:int {:min 0}])

;; --- pass^k (noise-robust scoring) ------------------------------------------
;; A single paid sample is a NOISY judge: model variance makes a scenario
;; that mostly-passes randomly score 0 on one draw (the canvas-goal-board
;; flake — single-sample-FAILED in a build where two siblings passed). So a
;; paid run drives each scenario k times and reports the pass-RATE, so
;; variance can't masquerade as a regression (the pass^k pattern —
;; single-run noise is the documented problem). FREE/stub tiers are
;; deterministic, so k there is a no-op (rate 1.0 or 0.0).
;;
;; Canonical pass-RATE shape — a fraction in [0,1], reused by the
;; per-scenario pass^k summary AND the battery aggregate (shared-shape rule).
(schema/register! :seon.gym/pass-rate [:double {:min 0.0 :max 1.0}])
;; Requested k for a battery run (≥1; default 1 = today's single-sample).
(schema/register! :seon.gym/k [:int {:min 1}])
;; Per-scenario pass^k summary axes — the scored-run count, how many of
;; them passed, the rate, plus the per-axis DISTRIBUTION across the k runs
;; (so variance is VISIBLE, not collapsed to a boolean).
(schema/register! :seon.gym.pass-k/k       [:int {:min 1}])
(schema/register! :seon.gym.pass-k/passes  [:int {:min 0}])
(schema/register! :seon.gym.pass-k/rate    :seon.gym/pass-rate)
(schema/register! :seon.gym.pass-k/canvas-updated-count [:int {:min 0}])
(schema/register! :seon.gym.pass-k/toolkit-calls-min    [:int {:min 0}])
(schema/register! :seon.gym.pass-k/toolkit-calls-max    [:int {:min 0}])
(schema/register! :seon.gym.pass-k/eval-error-rate-mean :seon.gym/eval-error-rate)
(schema/register! :seon.gym.pass-k/judge-mean [:double {:min 0.0 :max 100.0}])
;; ONE per-scenario pass^k summary (the scenario id keys it; reuses the
;; per-scenario scenario shape so the keys join).
(schema/register! :seon.gym.scorecard/pass-k
  [:map
   [:seon.gym.scorecard/scenario          :seon.gym.scorecard/scenario]
   [:seon.gym.pass-k/k                     :seon.gym.pass-k/k]
   [:seon.gym.pass-k/passes                :seon.gym.pass-k/passes]
   [:seon.gym.pass-k/rate                  :seon.gym.pass-k/rate]
   [:seon.gym.pass-k/canvas-updated-count  :seon.gym.pass-k/canvas-updated-count]
   [:seon.gym.pass-k/toolkit-calls-min     :seon.gym.pass-k/toolkit-calls-min]
   [:seon.gym.pass-k/toolkit-calls-max     :seon.gym.pass-k/toolkit-calls-max]
   [:seon.gym.pass-k/eval-error-rate-mean  :seon.gym.pass-k/eval-error-rate-mean]
   [:seon.gym.pass-k/judge-mean {:optional true} :seon.gym.pass-k/judge-mean]])
;; The battery's k, one pass^k summary per SCORED scenario, and the mean
;; pass-rate across them (the headline noise-robust number).
(schema/register! :seon.gym.battery/k         :seon.gym/k)
(schema/register! :seon.gym.battery/pass-k    [:vector :seon.gym.scorecard/pass-k])
(schema/register! :seon.gym.battery/pass-rate :seon.gym/pass-rate)

(schema/register! :seon.gym/battery-scorecard
  [:map
   [:seon.gym.battery/sha                  :seon.gym.battery/sha]
   [:seon.gym.battery/at                   :seon.gym.battery/at]
   [:seon.gym.battery/k                    :seon.gym.battery/k]
   [:seon.gym.battery/per-competency       :seon.gym.battery/per-competency]
   [:seon.gym.battery/pass-k               :seon.gym.battery/pass-k]
   [:seon.gym.battery/pass-rate            :seon.gym.battery/pass-rate]
   [:seon.gym.battery/total-tokens         :seon.gym.battery/total-tokens]
   [:seon.gym.battery/block-tokens         :seon.gym.battery/block-tokens]
   [:seon.gym.battery/toolkit-calls        :seon.gym.battery/toolkit-calls]
   [:seon.gym.battery/eval-error-rate      :seon.gym.battery/eval-error-rate]
   [:seon.gym.battery/canvas-updated-count :seon.gym.battery/canvas-updated-count]
   [:seon.gym.battery/scenario-count       :seon.gym.battery/scenario-count]
   [:seon.gym.battery/scored-count         :seon.gym.battery/scored-count]
   [:seon.gym.battery/judge-mean {:optional true} :seon.gym.battery/judge-mean]])

;; --- request/response shapes ------------------------------------------------
(schema/register! :seon.gym.battery/measures [:vector :seon.gym/measure-response])
;; A card is a per-scenario scorecard OR a refusal (errors-are-values).
;; pass^k: each scenario carries a VECTOR of its k run-cards (length 1 for
;; the default single-sample / FREE / stub case — byte-identical to k=1).
(schema/register! :seon.gym.battery/card-runs
  [:vector [:vector :seon.gym/run-response]])
(schema/register! :seon.gym.battery/aggregate-request
  [:map
   [:seon.gym/scenarios         :seon.gym/scenarios]
   [:seon.gym.battery/measures   :seon.gym.battery/measures]
   [:seon.gym.battery/card-runs  :seon.gym.battery/card-runs]
   [:seon.gym.battery/sha        :seon.gym.battery/sha]
   [:seon.gym.battery/at         :seon.gym.battery/at]
   [:seon.gym.battery/k {:optional true} :seon.gym.battery/k]])
(schema/register! :seon.gym.battery/request
  [:map
   [:seon.gym/scenarios   :seon.gym/scenarios]
   [:seon.gym.battery/sha :seon.gym.battery/sha]
   [:seon.gym.battery/at  :seon.gym.battery/at]
   [:seon.gym/allow-paid? {:optional true} :seon.gym/allow-paid?]
   [:seon.gym/k           {:optional true} :seon.gym/k]
   [:seon.gym/config      {:optional true} :seon.gym/config]
   [:seon.gym/judge-fn    {:optional true} :seon.gym/judge-fn]])
(schema/register! :seon.gym.battery/dir :string)
(schema/register! :seon.gym.battery/load-request
  [:map [:seon.gym.battery/dir :seon.gym.battery/dir]])
(schema/register! :seon.gym.battery/append-request
  [:map
   [:seon.gym/battery-scorecard :seon.gym/battery-scorecard]
   [:seon.gym/path              :seon.gym/path]])

;; ===========================================================================
;; Scenario loading — the WHOLE battery (every .edn under the dir).
;; ===========================================================================

(defn load-battery-scenarios!
  "Load + validate EVERY scenario .edn under `:seon.gym.battery/dir` (one
   flat vector, alphabetical by filename), reusing
   [[seon.gym.driver/load-scenarios!]] (so the §3.4 self-bait guard runs
   on each). Invalid EDN fails LOUD."
  {:malli/schema [:=> [:cat :seon.gym.battery/load-request] :seon.gym/load-response]}
  [{dir :seon.gym.battery/dir}]
  (let [fs    (js/require "node:fs")
        files (->> (.readdirSync fs dir)
                   (filter #(str/ends-with? % ".edn"))
                   sort)
        scenarios (vec (mapcat (fn [f]
                                 (:seon.gym/scenarios
                                   (driver/load-scenarios!
                                     {:seon.gym/path (str dir "/" f)})))
                               files))]
    {:seon.gym/scenarios scenarios}))

;; ===========================================================================
;; Aggregation — PURE. Reads ONLY the axes off each card, never an answer.
;; ===========================================================================

(defn- scorecard?
  "A card that actually ran (a per-scenario scorecard), vs a refusal
   value (:todo / :paid budget guard). Refusals carry :seon.gym/ok? false
   and never a :seon.gym.scorecard/pass?."
  [card]
  (contains? card :seon.gym.scorecard/pass?))

(defn- mean [xs]
  (if (seq xs) (/ (reduce + 0.0 xs) (count xs)) 0.0))

(defn- real-judge-scores
  "The REAL (non-SKIPPED) LLM-judge scores on one card — the SKIPPED
   sentinel (score 0, justification 'judge SKIPPED …') must never drag a
   mean."
  [card]
  (->> (:seon.gym.scorecard/judge-results card)
       (remove #(str/starts-with?
                  (str (:seon.gym.judge/justification %)) "judge SKIPPED"))
       (map :seon.gym.judge/score)))

(defn- pass-k
  "PURE pass^k rollup for ONE scenario's k run-cards — the noise-robust
   fitness signal (a single paid sample is a noisy judge; report the
   RATE). `cards` are the run-responses for this scenario; REFUSALS are
   dropped (a refused scenario isn't scored). Returns nil when nothing
   scored. Reads ONLY axes (anti-cheat): pass?/canvas/toolkit/eval-error/
   judge-score — never an answer. The per-axis distribution (canvas count,
   toolkit-call range, eval-error + judge means) makes the variance VISIBLE
   instead of collapsing it to a boolean."
  [scenario-id cards]
  (let [scored (filterv scorecard? cards)]
    (when (seq scored)
      (let [k        (count scored)
            passes   (count (filter :seon.gym.scorecard/pass? scored))
            tk-tot   (map (fn [c] (reduce + 0 (vals (:seon.gym.scorecard/toolkit-calls c))))
                          scored)
            judges   (mapcat real-judge-scores scored)]
        (cond-> {:seon.gym.scorecard/scenario          scenario-id
                 :seon.gym.pass-k/k                     k
                 :seon.gym.pass-k/passes                passes
                 :seon.gym.pass-k/rate                  (double (/ passes k))
                 :seon.gym.pass-k/canvas-updated-count
                 (count (filter :seon.gym.scorecard/canvas-updated? scored))
                 :seon.gym.pass-k/toolkit-calls-min     (reduce min tk-tot)
                 :seon.gym.pass-k/toolkit-calls-max     (reduce max tk-tot)
                 :seon.gym.pass-k/eval-error-rate-mean
                 (mean (map :seon.gym.scorecard/eval-error-rate scored))}
          (seq judges)
          (assoc :seon.gym.pass-k/judge-mean (double (mean judges))))))))

(defn aggregate
  "Roll the per-scenario measures + cards into ONE git-SHA-keyed
   `:seon.gym/battery-scorecard`. PURE (unit-testable with injected
   cards). Reads ONLY axes — pass?, total-tokens, per-block token
   estimates, toolkit-call COUNTS (eval-source scan), eval-error-rate,
   canvas-updated?, judge SCORE — never `:results` actuals or any
   reference answer (the anti-cheat invariant: the per-block, toolkit, and
   pass^k axes read block token estimates + eval SOURCE + boolean/score
   axes, never an answer).

   `scenarios` and `card-runs` are PARALLEL (run-battery! conj's them in
   lock-step), so a scenario's competency comes from its position — the
   card itself carries no competency. Each `card-runs` entry is the VECTOR
   of that scenario's k run-cards (pass^k); length 1 for the default
   single-sample / FREE / stub case. The non-pass^k axes (per-competency,
   toolkit, canvas count, eval-error, judge mean) aggregate over ALL k
   run-cards — byte-identical to the old flat shape at k=1 (flattening
   single-element run-vectors is the identity) and INTERNALLY CONSISTENT at
   k>1 (the battery judge-mean equals the pass^k judge-mean, never a
   misleading representative-first sample). The per-scenario pass^k summary
   + the battery pass-RATE carry the across-k variance, so model noise
   can't masquerade as a regression."
  {:malli/schema [:=> [:cat :seon.gym.battery/aggregate-request] :seon.gym/battery-scorecard]}
  [{scenarios :seon.gym/scenarios
    measures  :seon.gym.battery/measures
    card-runs :seon.gym.battery/card-runs
    sha       :seon.gym.battery/sha
    at        :seon.gym.battery/at
    k         :seon.gym.battery/k}]
  (let [cards        (into [] cat card-runs)   ; every run-card, k runs flattened
        scored       (filterv scorecard? cards)
        per-comp     (->> (map vector scenarios card-runs)
                          (mapcat (fn [[s runs]] (map (fn [c] [s c]) runs)))
                          (filter (fn [[_ c]] (scorecard? c)))
                          (reduce (fn [m [s c]]
                                    (let [comp  (:seon.gym.scenario/competency s)
                                          tally (get m comp {:seon.gym.battery/pass  0
                                                             :seon.gym.battery/total 0})]
                                      (assoc m comp
                                             {:seon.gym.battery/pass
                                              (+ (:seon.gym.battery/pass tally)
                                                 (if (:seon.gym.scorecard/pass? c) 1 0))
                                              :seon.gym.battery/total
                                              (inc (:seon.gym.battery/total tally))})))
                                  {}))
        total-tokens (reduce + 0 (map :seon.gym/total-tokens measures))
        ;; PER-BLOCK token axis — sum each block's tokens across every
        ;; measure's turn-profile, so a block-level trim shows on its own
        ;; key (the block name IS the key).
        block-tokens (->> measures
                          (mapcat (comp :seon.gym.profile/block-tokens
                                        :seon.gym/turn-profile))
                          (reduce (fn [m [nm t]] (update m nm (fnil + 0) t)) {}))
        ;; TOOLKIT-ADOPTION axis — sum the per-card toolkit reference counts
        ;; across the SCORED cards. Seeded with all three keys so an empty /
        ;; all-stub battery is an honest {:my.data 0 …}, never a misleading
        ;; absent map.
        toolkit-calls (->> (keep :seon.gym.scorecard/toolkit-calls scored)
                           (reduce (fn [m tc]
                                     (reduce-kv (fn [m k v]
                                                  (update m k (fnil + 0) v))
                                                m tc))
                                   {:my.data 0 :my.ui 0 :my.tile 0}))
        eval-err     (mean (map :seon.gym.scorecard/eval-error-rate scored))
        canvas-cnt   (count (filter :seon.gym.scorecard/canvas-updated? scored))
        judge-scores (mapcat real-judge-scores scored)
        ;; pass^k — ONE summary per SCORED scenario (across its k run-cards),
        ;; plus the headline mean pass-RATE across scenarios. A scenario
        ;; whose runs all refused (FREE-mode paid) contributes no pass^k.
        pass-ks      (->> (map vector scenarios card-runs)
                          (keep (fn [[s runs]]
                                  (pass-k (:seon.gym.scenario/id s) runs)))
                          vec)
        pass-rate    (mean (map :seon.gym.pass-k/rate pass-ks))
        base {:seon.gym.battery/sha                  sha
              :seon.gym.battery/at                   at
              :seon.gym.battery/k                    (or k 1)
              :seon.gym.battery/per-competency       per-comp
              :seon.gym.battery/pass-k               pass-ks
              :seon.gym.battery/pass-rate            pass-rate
              :seon.gym.battery/total-tokens         total-tokens
              :seon.gym.battery/block-tokens         block-tokens
              :seon.gym.battery/toolkit-calls        toolkit-calls
              :seon.gym.battery/eval-error-rate      eval-err
              :seon.gym.battery/canvas-updated-count canvas-cnt
              :seon.gym.battery/scenario-count       (count scenarios)
              :seon.gym.battery/scored-count         (count scored)}]
    (cond-> base
      (seq judge-scores)
      (assoc :seon.gym.battery/judge-mean (double (mean judge-scores))))))

;; ===========================================================================
;; The battery driver — sequential by construction (both measure-context!
;; and run-scenario! swap the root seon.db/*conn*, so two can NEVER overlap).
;; ===========================================================================

(defn ^:async ^:private drive-n!
  "Drive ONE scenario `n` times, strictly sequentially (run-scenario! swaps
   the root `seon.db/*conn*`, so two runs can NEVER overlap), and return a
   Promise of the vector of the n run-responses (scorecards/refusals)."
  [scenario n config allow-paid? judge-fn]
  (loop [i n acc []]
    (if (pos? i)
      (let [card (await (driver/run-scenario!
                          (cond-> {:seon.gym/scenario scenario}
                            config              (assoc :seon.gym/config config)
                            (some? allow-paid?) (assoc :seon.gym/allow-paid? allow-paid?)
                            judge-fn            (assoc :seon.gym/judge-fn judge-fn))))]
        (recur (dec i) (conj acc card)))
      acc)))

(defn ^:async run-battery!
  "Run the WHOLE battery for `:seon.gym/scenarios`, strictly in order, and
   return a Promise of the aggregate `:seon.gym/battery-scorecard` keyed by
   the passed-in `:seon.gym.battery/sha`. Per scenario: measure turn-1
   context size (FREE, every scenario) then drive it (run-scenario! —
   paid/todo members refuse without spend unless `:seon.gym/allow-paid?` +
   the active key make them runnable). `:seon.gym/config` (a named loadout)
   and `:seon.gym/judge-fn` (a mock judge for tests) thread through both.

   pass^k (`:seon.gym/k`, default 1): each REAL paid drive runs k times so
   the aggregate can report a pass-RATE instead of a single noisy sample.
   k only multiplies a drive that ACTUALLY spends — a FREE-mode or stub
   scenario is deterministic, so it runs once (k a no-op there); pass^k
   only matters for `--paid` LLM drives."
  {:malli/schema [:=> [:cat :seon.gym.battery/request] :seon.gym/battery-scorecard]}
  [{scenarios   :seon.gym/scenarios
    sha         :seon.gym.battery/sha
    at          :seon.gym.battery/at
    allow-paid? :seon.gym/allow-paid?
    config      :seon.gym/config
    judge-fn    :seon.gym/judge-fn
    k           :seon.gym/k}]
  (let [k (or k 1)]
    (loop [ss        scenarios
           measures  []
           card-runs []]
      (if-let [[s & more] (seq ss)]
        (let [measure (await (driver/measure-context!
                               (cond-> {:seon.gym/scenario s}
                                 config (assoc :seon.gym/config config))))
              ;; k only multiplies a REAL paid drive; FREE-mode + stub tiers
              ;; are deterministic, so they run once (k a no-op there).
              n       (if (and allow-paid?
                               (= :paid (:seon.gym.scenario/tier s)))
                        k 1)
              cards   (await (drive-n! s n config allow-paid? judge-fn))]
          (recur more (conj measures measure) (conj card-runs cards)))
        (aggregate {:seon.gym/scenarios        scenarios
                    :seon.gym.battery/measures measures
                    :seon.gym.battery/card-runs card-runs
                    :seon.gym.battery/sha      sha
                    :seon.gym.battery/at       at
                    :seon.gym.battery/k        k})))))

;; ===========================================================================
;; Reporting — one greppable line + the append-to-log trend record.
;; ===========================================================================

(defn format-line
  "The ONE greppable line for a battery-scorecard — `SEON-GYM
   SCORECARD-BATTERY ` + the EDN map (the `bin/gym-scorecard` wrapper
   surfaces these; distinct prefix from the per-scenario `SEON-GYM
   SCORECARD` lines run-scenario! emits)."
  {:malli/schema [:=> [:cat :seon.gym/battery-scorecard] :string]}
  [card]
  (str "SEON-GYM SCORECARD-BATTERY " (pr-str card)))

(defn print-battery-scorecard!
  "Print [[format-line]] and return the card unchanged."
  {:malli/schema [:=> [:cat :seon.gym/battery-scorecard] :seon.gym/battery-scorecard]}
  [card]
  (println (format-line card))
  card)

(defn append!
  "Append the battery-scorecard as ONE EDN line to the scorecard log at
   `:seon.gym/path` (created if absent). Appending = the TREND over SHAs
   stays visible + greppable across commits. Returns the path."
  {:malli/schema [:=> [:cat :seon.gym.battery/append-request] :seon.gym/path]}
  [{card :seon.gym/battery-scorecard path :seon.gym/path}]
  (let [fs (js/require "node:fs")]
    (.appendFileSync fs path (str (pr-str card) "\n") "utf8")
    path))
