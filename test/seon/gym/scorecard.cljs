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
(schema/register! :seon.gym/battery-scorecard
  [:map
   [:seon.gym.battery/sha                  :seon.gym.battery/sha]
   [:seon.gym.battery/at                   :seon.gym.battery/at]
   [:seon.gym.battery/per-competency       :seon.gym.battery/per-competency]
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
(schema/register! :seon.gym.battery/cards [:vector :seon.gym/run-response])
(schema/register! :seon.gym.battery/aggregate-request
  [:map
   [:seon.gym/scenarios        :seon.gym/scenarios]
   [:seon.gym.battery/measures  :seon.gym.battery/measures]
   [:seon.gym.battery/cards     :seon.gym.battery/cards]
   [:seon.gym.battery/sha       :seon.gym.battery/sha]
   [:seon.gym.battery/at        :seon.gym.battery/at]])
(schema/register! :seon.gym.battery/request
  [:map
   [:seon.gym/scenarios   :seon.gym/scenarios]
   [:seon.gym.battery/sha :seon.gym.battery/sha]
   [:seon.gym.battery/at  :seon.gym.battery/at]
   [:seon.gym/allow-paid? {:optional true} :seon.gym/allow-paid?]
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

(defn aggregate
  "Roll the per-scenario measures + cards into ONE git-SHA-keyed
   `:seon.gym/battery-scorecard`. PURE (unit-testable with injected
   cards). Reads ONLY axes — pass?, total-tokens, per-block token
   estimates, toolkit-call COUNTS (eval-source scan), eval-error-rate,
   canvas-updated?, judge SCORE — never `:results` actuals or any
   reference answer (the anti-cheat invariant: the new per-block and
   toolkit axes read block token estimates + eval SOURCE, never an answer).

   `scenarios` and `cards` are PARALLEL (run-battery! conj's them in
   lock-step), so a card's competency comes from its scenario by
   position — the card itself carries no competency."
  {:malli/schema [:=> [:cat :seon.gym.battery/aggregate-request] :seon.gym/battery-scorecard]}
  [{scenarios :seon.gym/scenarios
    measures  :seon.gym.battery/measures
    cards     :seon.gym.battery/cards
    sha       :seon.gym.battery/sha
    at        :seon.gym.battery/at}]
  (let [scored       (filterv scorecard? cards)
        per-comp     (->> (map vector scenarios cards)
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
        judge-scores (->> scored
                          (mapcat :seon.gym.scorecard/judge-results)
                          (remove #(str/starts-with?
                                     (str (:seon.gym.judge/justification %))
                                     "judge SKIPPED"))
                          (map :seon.gym.judge/score))
        base {:seon.gym.battery/sha                  sha
              :seon.gym.battery/at                   at
              :seon.gym.battery/per-competency       per-comp
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

(defn ^:async run-battery!
  "Run the WHOLE battery for `:seon.gym/scenarios`, strictly in order, and
   return a Promise of the aggregate `:seon.gym/battery-scorecard` keyed by
   the passed-in `:seon.gym.battery/sha`. Per scenario: measure turn-1
   context size (FREE, every scenario) then drive it (run-scenario! —
   paid/todo members refuse without spend unless `:seon.gym/allow-paid?` +
   the active key make them runnable). `:seon.gym/config` (a named loadout)
   and `:seon.gym/judge-fn` (a mock judge for tests) thread through both."
  {:malli/schema [:=> [:cat :seon.gym.battery/request] :seon.gym/battery-scorecard]}
  [{scenarios   :seon.gym/scenarios
    sha         :seon.gym.battery/sha
    at          :seon.gym.battery/at
    allow-paid? :seon.gym/allow-paid?
    config      :seon.gym/config
    judge-fn    :seon.gym/judge-fn}]
  (loop [ss       scenarios
         measures []
         cards    []]
    (if-let [[s & more] (seq ss)]
      (let [measure (await (driver/measure-context!
                             (cond-> {:seon.gym/scenario s}
                               config (assoc :seon.gym/config config))))
            card    (await (driver/run-scenario!
                             (cond-> {:seon.gym/scenario s}
                               config              (assoc :seon.gym/config config)
                               (some? allow-paid?) (assoc :seon.gym/allow-paid? allow-paid?)
                               judge-fn            (assoc :seon.gym/judge-fn judge-fn))))]
        (recur more (conj measures measure) (conj cards card)))
      (aggregate {:seon.gym/scenarios       scenarios
                  :seon.gym.battery/measures measures
                  :seon.gym.battery/cards    cards
                  :seon.gym.battery/sha      sha
                  :seon.gym.battery/at       at}))))

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
