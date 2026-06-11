(ns seon.gym.driver
  "AGENT-GYM scenario harness — PRD §7 item 12 (the testing methodology).

   Scenarios are EDN DATA: a question (or several), optional fixtures
   (tx-data seeded before the run), and PASS-PREDICATES — datalog
   queries against the post-run store plus transcript checks — that a
   driver evaluates MECHANICALLY, plus optional LLM-JUDGE predicates
   (rubric + reference facts → graded verdict) recorded on a SEPARATE
   scorecard axis. Section-by-section context iteration becomes
   QUANTIFIED: every defect class from live runs 3–7 is encoded as a
   permanent regression predicate, and the scorecard is keyed
   (scenario × git sha) so a context/prompt change shows up as a moved
   number, not an anecdote.

   Isolation: every run boots FRESH agents on a SCRATCH `:memory` conn
   via `seon.client/open-agent-conn!` (the tests' isolated path) — the
   live cluster store is untouchable by construction. The root
   `seon.db/*conn*` is swapped for the duration of the run and restored
   in a `finally`; schema-registry keys minted during the run (by
   fixtures OR by the agent's own `register!` evals) are removed after.

   Multi-agent sequencing (catalog §7): each turn carries an agent
   DESIGNATOR (`:seon.gym.turn/agent`, default `:a`); designators boot
   lazily on the same scratch store and turns run strictly in order, so
   'boot A → await idle → boot B' falls out of the sequential drive.

   Budget tiers:
     :stub     — FREE. The scenario scripts the LLM responses
                 (`:seon.gym.turn/llm-script`, one text per turn) and
                 the driver drives ONE `run-turn!` per script entry —
                 deliberately NOT the trigger-driven loop, because the
                 stub self-wake bug (PRD §4) burns trigger-driven stub
                 loops to the turn cap. Set `:seon.gym.scenario/llm` to
                 `:scripted-replay` to drive the REAL agentic loop with
                 a replaying llm-fn instead (terminates via the loop's
                 zero-forms stop policy), or `:rejecting` for the
                 simulated-provider-failure fixture.
     :deepseek — costs real money. The driver wires
                 `seon.ai.deepseek/agent-adapter` through
                 `run-agentic-loop!` (awaits the loop's own
                 termination = idle), but REFUSES to run unless the
                 caller passes `:seon.gym/allow-paid? true` AND
                 DEEPSEEK_API_KEY is set. LLM-judge predicates run
                 under the same guard (or with an injected
                 `:seon.gym/judge-fn` — how tests mock the judge).

   Rubric axes (the vocabulary every predicate tags itself with):
     sees-question · searches-first · models-work-directed ·
     reuses-schemas · consults-findings · reuses-functions ·
     writes-tests · replies-honestly · terminates · stores-proactively

   Run a scenario from a REPL:

     (require 'seon.gym.driver)
     (-> (seon.gym.driver/load-scenarios!
           {:seon.gym/path \"test/seon/gym/scenarios/envelope-honesty.edn\"})
         :seon.gym/scenarios first
         (as-> s (seon.gym.driver/run-scenario! {:seon.gym/scenario s}))
         (.then seon.gym.driver/print-scorecard!))

   Driver-feature gaps still TODO (catalog §7 — not needed by the
   top-4 scenarios):
     - mid-scenario pod restart against the same scratch store (S-06).
     - captured return values from driver-level message! calls (S-05).
     - fixture-sources tee into the :seon.fn functions-catalog (the
       fns ARE callable; catalog visibility needs the record-eval! tee
       outside a turn — required before S-31 function-reuse is encoded)."
  (:require
    [cljs.reader :as reader]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [malli.core :as m]
    [seon.agent :as agent]
    [seon.ai.deepseek :as deepseek]
    [seon.client :as client]
    [seon.ctx :as ctx]
    [seon.db :as db]
    ;; World-parity (2026-06-10 deep audit): the :test build has no
    ;; :devtools preload slot, so without this require
    ;; `client/!indexed-test-vars` stays [] and `client/index-tests`
    ;; seeds NOTHING — gym worlds were missing every :seon.test row and
    ;; the test-sibling :seon.ns/source rows, so :exemplars rendered
    ;; 4/7 blocks vs the live pod. Requiring the preload here runs the
    ;; SAME `(reset! client/!indexed-test-vars (deftest-vars))` the pod
    ;; build runs, with the SAME require closure → the same roster.
    [seon.dev.test-preload]
    [seon.eval :as seval]
    [seon.agent.fs :as sfs]
    ;; Boot-parity: start-agent! seeds the substrate handler entity
    ;; (`:wake/on-message`) + the hand-declared datahike idents at boot;
    ;; the gym world seeds the same rows (no dispatcher is armed — v0
    ;; handler rows are data only, so the stub drive stays the driver's).
    [seon.handler :as h]
    [seon.handlers.wake :as wake]
    [seon.repl :as repl]
    [seon.schema :as schema]
    [seon.warn :as warn]))

;; ===========================================================================
;; Schemas — scenario, predicate, result, scorecard. Registered once,
;; referenced everywhere (shared-shape rule). Keyword namespaces are
;; multi-segment data namespaces under :seon.gym.* (same convention as
;; the taught my.kb.<domain> shape).
;; ===========================================================================

;; --- rubric -----------------------------------------------------------------
;; The §7 item-12 behavioral vocabulary, PLUS :context-fidelity — the
;; axis of the gym-upgrade U3 STRUCTURAL checks (layout completeness +
;; cache-prefix byte-stability): not agent behavior but whether the
;; composer's output is the right context. Auto-appended structural
;; results carry it; scenarios declare it to surface the rollup.
(schema/register! :seon.gym.axis/name
  [:enum :sees-question :searches-first :models-work-directed
   :reuses-schemas :consults-findings :reuses-functions
   :writes-tests :replies-honestly :terminates :stores-proactively
   :context-fidelity])

;; --- turns ------------------------------------------------------------------
(schema/register! :seon.gym.turn/message :string)
;; One scripted LLM response text per driven turn. Stub tier only.
(schema/register! :seon.gym.turn/llm-script [:vector :string])
;; Agent DESIGNATOR (:a, :b, …) — multi-agent sequencing (catalog §7).
;; Turns run strictly in order; each designator's agent is created
;; lazily on first use, on the SAME scratch store. Default :a.
(schema/register! :seon.gym.turn/agent :keyword)
(schema/register! :seon.gym/turn
  [:map
   [:seon.gym.turn/message :seon.gym.turn/message]
   [:seon.gym.turn/agent {:optional true} :seon.gym.turn/agent]
   [:seon.gym.turn/llm-script {:optional true} :seon.gym.turn/llm-script]])

;; --- predicates ---------------------------------------------------------------
(schema/register! :seon.gym.predicate/id   :keyword)
;; :eval-count-matching — rows = the (optionally agent-scoped) evals
;;   whose source matches :pattern; :expect applies to that row set
;;   ([:count 0] = "never did X", [:count<= 1] = "at most once", …).
;; :llm-judge — NOT mechanical. Rubric + reference facts + the agent's
;;   verbatim reply → graded verdict on the SEPARATE judge axis of the
;;   scorecard ("behaved right, answered wrong" stays distinguishable).
;; :domain-attrs — rows = the post-run store's DOMAIN attrs (one attr
;;   per row, via seon.warn/domain-attrs — agent-provenance only, so
;;   ANY attr the agent register!ed shows up regardless of the keyword
;;   namespace it picked). [:every-in [...]] = "the agent forked NO new
;;   attr anywhere" (the generic S-21 no-fork predicate); [:count>= n]
;;   = "the seeded reuse surface is actually visible".
;; :prompt-includes / :prompt-excludes / :prompt-every-turn — the
;;   referee's EYES (gym-upgrade PRD §2.1 / U1): assert against what
;;   the agent ACTUALLY SAW. run-turn! persists every full prompt to
;;   logs/prompts/<agent-id>/<turn-id>.txt (the turn datom carries
;;   :seon.agent.turn/prompt-file); the driver collects the run's turns
;;   from the post-run store and reads those blobs. :prompt-includes =
;;   SOME turn's prompt contains :text; :prompt-excludes = NO turn's
;;   prompt contains :text; :prompt-every-turn = EVERY turn's prompt
;;   contains :text (the catalog's standing G2 sees-question shape).
;;   Optional :seon.gym.predicate/turn pins ONE turn by chronological
;;   index; :seon.gym.predicate/agent scopes to one designator's turns.
;;   A turn with no prompt-file datom, an unreadable blob, an
;;   out-of-range index, or a run with zero turns ALL score RED naming
;;   the path/turn — NEVER a silent pass (a referee blind to its own
;;   missing eyes would hide the exact regression class this exists
;;   to catch).
(schema/register! :seon.gym.predicate/kind
  [:enum :datalog :transcript-includes :transcript-excludes
   :first-eval-matches :eval-count-matching :domain-attrs
   :prompt-includes :prompt-excludes :prompt-every-turn :llm-judge])
(schema/register! :seon.gym.predicate/axis :seon.gym.axis/name)
;; Datalog query/args are datahike's domain — third-party boundary,
;; :any allowed (same stance as :seon.db/query-request).
(schema/register! :seon.gym.predicate/query [:vector :any])
;; Args may contain agent-designator placeholders — a keyword in the
;; :seon.gym.agent namespace (e.g. :seon.gym.agent/b) is substituted
;; with that designator's actual agent id at evaluation time.
(schema/register! :seon.gym.predicate/args  [:vector :any])
(schema/register! :seon.gym.predicate/expect
  [:or
   [:enum :non-empty :empty]
   [:tuple [:= :count] :int]
   [:tuple [:= :count<=] :int]
   [:tuple [:= :count>=] :int]
   [:tuple [:= :some-includes] :string]
   ;; every value in every row must be one of these strings (set
   ;; membership after `str` — used for attr-name-subset predicates).
   [:tuple [:= :every-in] [:vector :string]]])
(schema/register! :seon.gym.predicate/text    :string)
(schema/register! :seon.gym.predicate/pattern :string)
;; Scope an eval-shaped predicate (first-eval-matches /
;; eval-count-matching), a prompt-blob predicate, or a judge predicate
;; to ONE agent designator. Absent = the whole store for eval/prompt
;; predicates, :a for judges.
(schema/register! :seon.gym.predicate/agent :seon.gym.turn/agent)
;; Pin a prompt-blob predicate to ONE turn by chronological index
;; (0-based, within the predicate's agent scope). Absent = the kind's
;; quantifier ranges over every turn in the run.
(schema/register! :seon.gym.predicate/turn [:int {:min 0}])
;; LLM-judge inputs: the grading rubric and the reference (ground-
;; truth) facts the verdict must be checked against.
(schema/register! :seon.gym.predicate/rubric    :string)
(schema/register! :seon.gym.predicate/reference :string)
(schema/register! :seon.gym/predicate
  [:map
   [:seon.gym.predicate/id      :seon.gym.predicate/id]
   [:seon.gym.predicate/kind    :seon.gym.predicate/kind]
   [:seon.gym.predicate/axis    :seon.gym.predicate/axis]
   [:seon.gym.predicate/agent   {:optional true} :seon.gym.predicate/agent]
   [:seon.gym.predicate/turn    {:optional true} :seon.gym.predicate/turn]
   [:seon.gym.predicate/query   {:optional true} :seon.gym.predicate/query]
   [:seon.gym.predicate/args    {:optional true} :seon.gym.predicate/args]
   [:seon.gym.predicate/expect  {:optional true} :seon.gym.predicate/expect]
   [:seon.gym.predicate/text    {:optional true} :seon.gym.predicate/text]
   [:seon.gym.predicate/pattern {:optional true} :seon.gym.predicate/pattern]
   [:seon.gym.predicate/rubric    {:optional true} :seon.gym.predicate/rubric]
   [:seon.gym.predicate/reference {:optional true} :seon.gym.predicate/reference]])

;; --- scenario -----------------------------------------------------------------
(schema/register! :seon.gym.scenario/id     :keyword)
(schema/register! :seon.gym.scenario/doc    :string)
(schema/register! :seon.gym.scenario/tier   [:enum :stub :deepseek])
(schema/register! :seon.gym.scenario/status [:enum :active :todo])
(schema/register! :seon.gym.scenario/axes   [:vector :seon.gym.axis/name])
;; A Malli schema FORM is malli's open domain — third-party boundary.
(schema/register! :seon.gym/malli-form [:or :keyword [:vector :any]])
(schema/register! :seon.gym.scenario/schema-registrations
  [:vector [:tuple :keyword :seon.gym/malli-form]])
;; Fixture tx-data. String values may carry the relative-date
;; placeholders {{today}} and {{days-ago:N}} — resolved to ISO dates
;; against the run date at seed time ("last week" stays last week).
(schema/register! :seon.gym.scenario/fixtures :seon.db/tx-data)
;; Fixture SOURCE strings — evaluated through `seon.eval/eval` at
;; fixture-load so the defined fns are CALLABLE from agent evals
;; (code-as-data seeding, catalog F-helper-fns). TODO: tee them into
;; the :seon.fn functions-catalog too (needs the record-eval! tee
;; outside a turn) — required before S-31 (function reuse) is encoded.
(schema/register! :seon.gym.scenario/fixture-sources [:vector :string])
;; Per-scenario llm-fn injection (catalog §7), stub tier only:
;;   :scripted-replay — drive run-agentic-loop! (the REAL trigger-style
;;     loop) with an llm-fn replaying the turn's :llm-script entries in
;;     order; once exhausted it answers prose-only, so the loop's
;;     zero-forms stop policy terminates it.
;;   :rejecting — an llm-fn whose Promise REJECTS after 100ms
;;     (simulated provider failure; catalog F-llm-reject / S-08).
;; Absent on a stub scenario = the one-run-turn!-per-script-entry
;; driver (see ns docstring on the stub self-wake bug).
(schema/register! :seon.gym.scenario/llm [:enum :scripted-replay :rejecting])
(schema/register! :seon.gym.scenario/turns [:vector :seon.gym/turn])
(schema/register! :seon.gym.scenario/predicates
  [:vector :seon.gym/predicate])
(schema/register! :seon.gym/scenario
  [:map
   [:seon.gym.scenario/id     :seon.gym.scenario/id]
   [:seon.gym.scenario/doc    :seon.gym.scenario/doc]
   [:seon.gym.scenario/tier   :seon.gym.scenario/tier]
   [:seon.gym.scenario/status :seon.gym.scenario/status]
   [:seon.gym.scenario/axes   :seon.gym.scenario/axes]
   [:seon.gym.scenario/schema-registrations {:optional true}
    :seon.gym.scenario/schema-registrations]
   [:seon.gym.scenario/fixtures {:optional true} :seon.gym.scenario/fixtures]
   [:seon.gym.scenario/fixture-sources {:optional true}
    :seon.gym.scenario/fixture-sources]
   [:seon.gym.scenario/llm {:optional true} :seon.gym.scenario/llm]
   [:seon.gym.scenario/turns      :seon.gym.scenario/turns]
   [:seon.gym.scenario/predicates :seon.gym.scenario/predicates]])

;; --- results + scorecard --------------------------------------------------------
(schema/register! :seon.gym.result/pass?  :boolean)
(schema/register! :seon.gym.result/actual :string)
(schema/register! :seon.gym/result
  [:map
   [:seon.gym.predicate/id   :seon.gym.predicate/id]
   [:seon.gym.predicate/axis :seon.gym.predicate/axis]
   [:seon.gym.result/pass?   :seon.gym.result/pass?]
   [:seon.gym.result/actual  :seon.gym.result/actual]])
(schema/register! :seon.gym.scorecard/scenario :keyword)
(schema/register! :seon.gym.scorecard/git-sha  :string)
;; One fresh uuid per run-scenario! invocation (gym-upgrade §3.1): a
;; scenario that runs twice under one (scenario × sha) key — the S-12
;; async double-done class — shows up as TWO cards with DISTINCT
;; run-ids, while the SAME card printed twice shows duplicate run-ids
;; (bin/gym flags those). Either way the double-fire is VISIBLE, never
;; a silent overwrite.
(schema/register! :seon.gym.scorecard/run-id   :uuid)
(schema/register! :seon.gym.scorecard/tier     :seon.gym.scenario/tier)
(schema/register! :seon.gym.scorecard/at       :inst)
(schema/register! :seon.gym.scorecard/agent-id :seon.db/id)
(schema/register! :seon.gym.scorecard/pass?    :boolean)
(schema/register! :seon.gym.scorecard/axes
  [:map-of :seon.gym.axis/name :boolean])
(schema/register! :seon.gym.scorecard/results
  [:vector :seon.gym/result])
;; Per-turn prompt-blob evidence (gym-upgrade PRD §6.6, default-on):
;; every persisted prompt-file path for the run, chronological — a
;; moved number is diffable to the exact context bytes the agent saw.
(schema/register! :seon.gym.scorecard/prompt-files [:vector :string])
;; --- structural per-turn profile (gym-upgrade PRD §2.2 / U3) ----------------
;; Captured once per driven gym turn from `assemble-context`'s OWN
;; output against the PRE-TURN db value (user message landed, turn not
;; yet run — the exact db the turn's prompt renders from). The two
;; derived checks ride [[structural-results]] onto every scorecard.
(schema/register! :seon.gym.profile/agent :seon.gym.turn/agent)
;; [section-name rendered-char-count] in render order — only the
;; non-blank contributions (assemble-context's :seon.render/section-texts).
(schema/register! :seon.gym.profile/section-chars
  [:vector [:tuple :seon.ctx/name :int]])
;; (a) every substrate-default-ctx section name appears in the merged
;; layout (:seon.render/sections) — derived from the composer's own
;; code, never a hand-list; override-by-name keeps the name, so a
;; missing one = a composer merge regression.
(schema/register! :seon.gym.profile/layout-complete? :boolean)
(schema/register! :seon.gym.profile/layout-missing [:vector :seon.ctx/name])
;; (b) double-render against the SAME db value is byte-identical up to
;; the :transcript boundary — the provider-cache invariant the
;; most-static→most-dynamic ordering exists to serve.
(schema/register! :seon.gym.profile/prefix-stable? :boolean)
(schema/register! :seon.gym.profile/prefix-diff :string)
(schema/register! :seon.gym/turn-profile
  [:map
   [:seon.gym.profile/agent            :seon.gym.profile/agent]
   [:seon.render/sections              :seon.render/sections]
   [:seon.gym.profile/section-chars    :seon.gym.profile/section-chars]
   [:seon.gym.profile/layout-complete? :seon.gym.profile/layout-complete?]
   [:seon.gym.profile/layout-missing {:optional true}
    :seon.gym.profile/layout-missing]
   [:seon.gym.profile/prefix-stable?   :seon.gym.profile/prefix-stable?]
   [:seon.gym.profile/prefix-diff {:optional true}
    :seon.gym.profile/prefix-diff]])
(schema/register! :seon.gym.scorecard/turn-profiles
  [:vector :seon.gym/turn-profile])
;; --- judge verdicts — a SEPARATE scorecard axis from the mechanical
;; results. :seon.gym.scorecard/pass?/axes/results stay PURELY
;; mechanical; judge-pass?/judge-results carry the semantic grading, so
;; "behaved right, answered wrong" reads as pass? true + judge-pass?
;; false — a distinct failure signature, never merged.
(schema/register! :seon.gym.judge/pass?  :boolean)
(schema/register! :seon.gym.judge/score  :int) ; 0–100
(schema/register! :seon.gym.judge/justification :string)
(schema/register! :seon.gym/judge-result
  [:map
   [:seon.gym.predicate/id        :seon.gym.predicate/id]
   [:seon.gym.predicate/axis      :seon.gym.predicate/axis]
   [:seon.gym.judge/pass?         :seon.gym.judge/pass?]
   [:seon.gym.judge/score         :seon.gym.judge/score]
   [:seon.gym.judge/justification :seon.gym.judge/justification]])
(schema/register! :seon.gym.scorecard/judge-pass? :boolean)
(schema/register! :seon.gym.scorecard/judge-results
  [:vector :seon.gym/judge-result])
(schema/register! :seon.gym/scorecard
  [:map
   [:seon.gym.scorecard/scenario :seon.gym.scorecard/scenario]
   [:seon.gym.scorecard/git-sha  :seon.gym.scorecard/git-sha]
   [:seon.gym.scorecard/run-id   :seon.gym.scorecard/run-id]
   [:seon.gym.scorecard/tier     :seon.gym.scorecard/tier]
   [:seon.gym.scorecard/at       :seon.gym.scorecard/at]
   [:seon.gym.scorecard/agent-id :seon.gym.scorecard/agent-id]
   [:seon.gym.scorecard/pass?    :seon.gym.scorecard/pass?]
   [:seon.gym.scorecard/axes     :seon.gym.scorecard/axes]
   [:seon.gym.scorecard/results  :seon.gym.scorecard/results]
   [:seon.gym.scorecard/prompt-files {:optional true}
    :seon.gym.scorecard/prompt-files]
   ;; U3: one structural profile per driven turn, chronological — the
   ;; evidence behind the two standing structural results.
   [:seon.gym.scorecard/turn-profiles :seon.gym.scorecard/turn-profiles]
   ;; present iff the scenario carries :llm-judge predicates
   [:seon.gym.scorecard/judge-pass? {:optional true}
    :seon.gym.scorecard/judge-pass?]
   [:seon.gym.scorecard/judge-results {:optional true}
    :seon.gym.scorecard/judge-results]])

;; --- request/response shapes ------------------------------------------------
(schema/register! :seon.gym/path :string)
(schema/register! :seon.gym/scenarios [:vector :seon.gym/scenario])
(schema/register! :seon.gym/load-request  [:map [:seon.gym/path :seon.gym/path]])
(schema/register! :seon.gym/load-response [:map [:seon.gym/scenarios :seon.gym/scenarios]])
(schema/register! :seon.gym/allow-paid? :boolean)
(schema/register! :seon.gym/ok? :boolean)
(schema/register! :seon.gym/error :string)
(schema/register! :seon.gym/refusal
  [:map [:seon.gym/ok? [:= false]] [:seon.gym/error :seon.gym/error]])
;; Injectable judge llm-fn: ctx-string → Promise<{:text "…"}>. Tests
;; inject a mock (zero spend) to prove the verdict→axis wiring; absent,
;; the driver builds the DeepSeek judge — but ONLY under allow-paid?.
(schema/register! :seon.gym/judge-fn fn?)
(schema/register! :seon.gym/run-request
  [:map
   [:seon.gym/scenario :seon.gym/scenario]
   [:seon.gym/allow-paid? {:optional true} :seon.gym/allow-paid?]
   [:seon.gym/judge-fn {:optional true} :seon.gym/judge-fn]])
(schema/register! :seon.gym/run-response
  [:or :seon.gym/scorecard :seon.gym/refusal])
(schema/register! :seon.gym/seed-request
  [:map
   [:seon.db/conn :seon.db/conn]
   [:seon.gym/scenario :seon.gym/scenario]])
(schema/register! :seon.gym/seed-response
  [:map [:seon.gym/ok? [:= true]]])

;; ===========================================================================
;; Scenario loading
;; ===========================================================================

(defn- fixture-strings
  "Every string value reachable inside one fixture form (maps, vectors,
   nested) — the self-bait scan surface."
  [fixture]
  (let [!acc (atom [])]
    (walk/postwalk (fn [x] (when (string? x) (swap! !acc conj x)) x)
                   fixture)
    @!acc))

(defn- check-self-bait!
  "Gym-upgrade §3.4: a scenario's turn MESSAGE text must never appear
   verbatim inside its own fixture values or fixture sources. When it
   does, any predicate keyed on question TEXT can pass by string
   coincidence (the s32 class: the seeded :my.kb.codebase/question WAS
   the asked question, so the consult predicate measured the rendered
   bait, not the behavior). Loud load failure naming the offending
   fixture — paraphrase the fixture; predicates about consultation
   anchor on attrs/structure, never on the question string."
  [path {:seon.gym.scenario/keys [id fixtures fixture-sources turns]}]
  (doseq [msg (keep :seon.gym.turn/message turns)]
    (doseq [[i fx] (map-indexed vector fixtures)
            s      (fixture-strings fx)
            :when  (str/includes? s msg)]
      (throw (ex-info (str "gym: SELF-BAIT — scenario " id " in " path
                           ": fixture #" i " contains a turn message "
                           "verbatim (" (pr-str msg) "). Paraphrase the "
                           "fixture; anchor consultation predicates on "
                           "attrs/structure, never the question string.")
                      {:seon.gym/path              path
                       :seon.gym.scenario/id       id
                       :seon.gym.run/fixture-index i
                       :seon.gym.run/fixture-value s
                       :seon.gym.turn/message      msg})))
    (doseq [[i src] (map-indexed vector fixture-sources)
            :when   (str/includes? src msg)]
      (throw (ex-info (str "gym: SELF-BAIT — scenario " id " in " path
                           ": fixture-source #" i " contains a turn "
                           "message verbatim (" (pr-str msg) ").")
                      {:seon.gym/path                     path
                       :seon.gym.scenario/id              id
                       :seon.gym.run/fixture-source-index i
                       :seon.gym.turn/message             msg})))))

(defn load-scenarios!
  "Read one scenario EDN file (a single scenario map OR a vector of
   them) and validate every scenario against `:seon.gym/scenario`.
   Invalid EDN fails LOUD with the Malli explain — a scenario that
   doesn't parse must never silently score. Also enforces the §3.4
   self-bait rule ([[check-self-bait!]]) at load time."
  {:malli/schema [:=> [:cat :seon.gym/load-request] :seon.gym/load-response]}
  [{path :seon.gym/path}]
  (let [fs        (js/require "node:fs")
        data      (reader/read-string (.readFileSync fs path "utf8"))
        scenarios (if (map? data) [data] (vec data))]
    (doseq [s scenarios]
      (when-not (m/validate :seon.gym/scenario s)
        (throw (ex-info (str "gym: invalid scenario EDN — " path)
                        {:seon.gym/path    path
                         :seon.gym/explain (pr-str (m/explain :seon.gym/scenario s))})))
      (check-self-bait! path s))
    {:seon.gym/scenarios scenarios}))

;; ===========================================================================
;; Predicate evaluation — mechanical, no judgment calls.
;; ===========================================================================

(defn- truncate-actual [s]
  (let [s (str s)]
    (if (> (count s) 500) (str (subs s 0 500) " …(truncated)") s)))

(defn- expect-pass?
  "Does the datalog result set satisfy the predicate's `:expect`?"
  [expect rows]
  (cond
    (= :non-empty expect) (boolean (seq rows))
    (= :empty expect)     (empty? rows)
    (and (vector? expect) (= :count (first expect)))
    (= (second expect) (count rows))
    (and (vector? expect) (= :count<= (first expect)))
    (<= (count rows) (second expect))
    (and (vector? expect) (= :count>= (first expect)))
    (>= (count rows) (second expect))
    (and (vector? expect) (= :some-includes (first expect)))
    (boolean (some (fn [row]
                     (some #(str/includes? (str %) (second expect)) row))
                   rows))
    (and (vector? expect) (= :every-in (first expect)))
    (let [allowed (set (second expect))]
      (every? (fn [row] (every? #(contains? allowed (str %)) row)) rows))
    :else false))

(defn- eval-at+source
  "All [at source] eval pairs, chronological. With `agent-id`, scoped
   to that agent's evals via agent → sessions → turns → evals; nil =
   the whole scratch store (single-agent scenarios)."
  [dbv agent-id]
  (->> (if agent-id
         (db/query {:seon.db/query '[:find ?at ?src ?ev
                                     :in $ ?aid
                                     :where
                                     [?ag :seon.agent/id ?aid]
                                     [?ag :seon.agent/sessions ?s]
                                     [?s :seon.agent.session/turns ?t]
                                     [?t :seon.agent.turn/evals ?ev]
                                     [?ev :seon.eval/at ?at]
                                     [?ev :seon.eval/source ?src]]
                    :seon.db/args [agent-id]
                    :seon.db/db   dbv})
         (db/query {:seon.db/query '[:find ?at ?src ?ev
                                     :where
                                     [?ev :seon.eval/at ?at]
                                     [?ev :seon.eval/source ?src]]
                    :seon.db/db dbv}))
       ;; :seon.eval/at is ms precision — same-ms ties break on the eid
       ;; (monotonic allocation order on the scratch conn). Datahike's
       ;; tx-id is the canonical sub-ms order; eid is the cheap proxy
       ;; available in this row shape.
       (sort-by (fn [[at _ eid]] [(.getTime ^js at) eid]))
       (mapv (fn [[at src _]] [at src]))))

(defn- first-eval-source
  "Source text of the chronologically FIRST eval (by :seon.eval/at) —
   optionally scoped to one agent — or nil when no eval ran."
  [dbv agent-id]
  (second (first (eval-at+source dbv agent-id))))

(defn- turn-prompt-files
  "Chronological [turn-id prompt-file-or-nil] pairs for every turn in
   the post-run store — optionally scoped to one agent's turns via
   agent → sessions → turns. A nil prompt-file means the blob was
   never written (persist-prompt! failure, or a seeded turn without
   one) — prompt-predicate callers MUST treat that as RED."
  [dbv agent-id]
  (->> (if agent-id
         (db/query {:seon.db/query '[:find ?at ?tid ?t
                                     :in $ ?aid
                                     :where
                                     [?ag :seon.agent/id ?aid]
                                     [?ag :seon.agent/sessions ?s]
                                     [?s :seon.agent.session/turns ?t]
                                     [?t :seon.agent.turn/id ?tid]
                                     [?t :seon.agent.turn/at ?at]]
                    :seon.db/args [agent-id]
                    :seon.db/db   dbv})
         (db/query {:seon.db/query '[:find ?at ?tid ?t
                                     :where
                                     [?t :seon.agent.turn/id ?tid]
                                     [?t :seon.agent.turn/at ?at]]
                    :seon.db/db dbv}))
       ;; :seon.agent.turn/at is ms precision — same-ms ties break on
       ;; the turn eid (monotonic allocation order; datahike's tx-id is
       ;; the canonical sub-ms order, eid is the cheap proxy here).
       (sort-by (fn [[at _ eid]] [(.getTime ^js at) eid]))
       (mapv (fn [[_ tid _]]
               [tid (:seon.agent.turn/prompt-file
                     (db/entity {:seon.db/ref [:seon.agent.turn/id tid]
                                 :seon.db/db  dbv}))]))))

(defn- read-prompt-blob
  "Read one persisted prompt blob. Returns [:ok text] or
   [:unreadable reason] — the caller turns :unreadable into a RED
   result naming the path; this fn never throws."
  [path]
  (try
    [:ok (.readFileSync (js/require "node:fs") path "utf8")]
    (catch :default e
      [:unreadable (str path " — " (or (.-message e) e))])))

(defn- eval-prompt-predicate
  "Evaluate one prompt-blob predicate (:prompt-includes /
   :prompt-excludes / :prompt-every-turn — gym-upgrade PRD §2.1/U1)
   against the prompts the agent ACTUALLY SAW: the blobs run-turn!
   persisted to logs/prompts/<agent-id>/<turn-id>.txt, located via the
   post-run store's :seon.agent.turn/prompt-file datoms. Returns
   [pass? actual]. Every blind spot is RED, never a silent pass: zero
   turns, an out-of-range :turn index, a turn with no prompt-file
   datom, an unreadable blob — each named in the actual."
  [dbv agent-id kind text turn-idx]
  (let [all (turn-prompt-files dbv agent-id)]
    (cond
      (empty? all)
      [false (str "RED — NO turns" (when agent-id (str " for agent " agent-id))
                  " in the post-run store; nothing to assert prompts "
                  "against")]

      (and turn-idx (not (< -1 turn-idx (count all))))
      [false (str "RED — :seon.gym.predicate/turn " turn-idx
                  " out of range; run has " (count all) " turn(s)")]

      :else
      (let [reads  (mapv (fn [[tid path]]
                           (if (nil? path)
                             {:tid tid
                              :missing (str "turn " tid " has NO "
                                            ":seon.agent.turn/prompt-file — "
                                            "blob never written (expected "
                                            "under logs/prompts/)")}
                             (let [[status payload] (read-prompt-blob path)]
                               (if (= :ok status)
                                 {:tid tid :path path :text payload}
                                 {:tid tid :path path
                                  :missing (str "prompt blob unreadable: "
                                                payload)}))))
                         (if turn-idx [(nth all turn-idx)] all))
            broken (filterv :missing reads)]
        (if (seq broken)
          [false (str "RED — " (str/join "; " (map :missing broken)))]
          (let [hits  (filterv #(str/includes? (:text %) text) reads)
                stat  (str (count hits) "/" (count reads)
                           " prompt blob(s) contain " (pr-str text)
                           "; blobs: " (pr-str (mapv :path reads)))]
            (case kind
              :prompt-includes   [(boolean (seq hits)) stat]
              :prompt-excludes   [(empty? hits) (str stat " (must be 0)")]
              :prompt-every-turn [(= (count hits) (count reads)) stat])))))))

(defn- resolve-predicate-args
  "Substitute agent-designator placeholders (:seon.gym.agent/a …) in a
   predicate's datalog args with the actual agent ids minted this run."
  [agents args]
  (mapv (fn [a]
          (if (and (keyword? a) (= "seon.gym.agent" (namespace a)))
            (or (get agents (keyword (name a)))
                (throw (ex-info (str "gym: predicate args reference unknown "
                                     "agent designator " a)
                                {:seon.gym.run/agents agents})))
            a))
        args))

(defn- eval-predicate
  "Evaluate ONE MECHANICAL predicate against the post-run db value +
   rendered transcript (+ the designator→agent-id map for scoped
   predicates). Returns a `:seon.gym/result` map — pass/fail plus the
   ACTUAL observation (so a failing scorecard explains itself). A
   predicate that THROWS (e.g. a bad datalog form) scores pass? false
   with the error as the actual — broken predicates must be visible,
   never crash the scorecard."
  [dbv transcript agents
   {:seon.gym.predicate/keys [id kind axis query args expect
                              text pattern agent turn]}]
  (let [agent-id (when agent (get agents agent))
        [pass? actual]
        (try
          (case kind
            :datalog
            (let [rows (vec (db/query (cond-> {:seon.db/query query
                                               :seon.db/db    dbv}
                                        args (assoc :seon.db/args
                                                    (resolve-predicate-args
                                                      agents args)))))]
              [(expect-pass? expect rows)
               (str "rows=" (pr-str rows) " expect=" (pr-str expect))])

            :transcript-includes
            [(str/includes? transcript text)
             (str "transcript " (count transcript) " chars; looked for " (pr-str text))]

            :transcript-excludes
            [(not (str/includes? transcript text))
             (str "transcript " (count transcript) " chars; must NOT contain " (pr-str text))]

            :first-eval-matches
            (let [src (first-eval-source dbv agent-id)]
              [(boolean (and src (re-find (js/RegExp. pattern) src)))
               (str (when agent (str "agent " agent " "))
                    "first eval source: " (pr-str src))])

            :eval-count-matching
            (let [srcs     (mapv second (eval-at+source dbv agent-id))
                  matching (filterv #(re-find (js/RegExp. pattern) %) srcs)]
              [(expect-pass? expect (mapv vector matching))
               (str (when agent (str "agent " agent " "))
                    (count matching) "/" (count srcs) " evals match "
                    (pr-str pattern) " expect=" (pr-str expect))])

            :domain-attrs
            (let [attrs (warn/domain-attrs {:seon.db/db dbv})]
              [(expect-pass? expect (mapv vector attrs))
               (str "domain attrs: " (pr-str attrs)
                    " expect=" (pr-str expect))])

            (:prompt-includes :prompt-excludes :prompt-every-turn)
            (eval-prompt-predicate dbv agent-id kind text turn))
          (catch :default e
            [false (str "predicate THREW: " e)]))]
    {:seon.gym.predicate/id   id
     :seon.gym.predicate/axis axis
     :seon.gym.result/pass?   (boolean pass?)
     :seon.gym.result/actual  (truncate-actual actual)}))

(defn- axes-rollup
  "Per-axis verdict: an axis passes iff EVERY predicate tagged with it
   passes. Axes declared on the scenario but exercised by no predicate
   report true (vacuous — the scenario doc should say why)."
  [axes results]
  (into {}
        (map (fn [axis]
               [axis (every? :seon.gym.result/pass?
                             (filter #(= axis (:seon.gym.predicate/axis %))
                                     results))]))
        axes))

;; ===========================================================================
;; Structural per-turn profile (gym-upgrade PRD §2.2 / U3). The section
;; set/sizes come from `assemble-context`'s OWN output against the
;; pre-turn db value; both checks are DERIVED from the composer's own
;; code, never a hand-maintained list:
;;   (a) layout completeness — every [[ctx/substrate-default-ctx]] name
;;       appears in the merged :seon.render/sections layout;
;;   (b) cache-prefix byte-stability — rendering TWICE against the SAME
;;       db value is byte-identical up to the :transcript boundary. A
;;       timestamp or counter leaking into a static-priority section is
;;       a silent provider-cache bust (spend regression) — asserted
;;       nowhere before this.
;; Both gate EVERY scorecard as standing structural results on the
;; :context-fidelity axis.
;; ===========================================================================

(def ^:private dynamic-tail-sections
  "Sections at/after the cache boundary — everything from :transcript on
   is per-turn-volatile BY DESIGN (:prompt embeds the wall clock), so
   the stability check stops at the first of these."
  #{:transcript :prompt})

(defn- prefix-section-texts
  "The provider-cacheable prefix: section-texts strictly before the
   first dynamic-tail section."
  [section-texts]
  (vec (take-while #(not (dynamic-tail-sections (:seon.ctx/name %)))
                   section-texts)))

(defn- first-char-diff
  "Index of the first differing char between two strings (= min length
   when one is a prefix of the other)."
  [a b]
  (let [n (min (count a) (count b))]
    (loop [i 0]
      (if (or (= i n) (not= (.charAt a i) (.charAt b i)))
        i
        (recur (inc i))))))

(defn- prefix-diff-detail
  "Name the FIRST prefix section whose double-render output diverges —
   the actual a failing stability result explains itself with."
  [p1 p2]
  (or (some (fn [[{n1 :seon.ctx/name t1 :seon.render/text}
                  {n2 :seon.ctx/name t2 :seon.render/text}]]
              (cond
                (not= n1 n2)
                (str "prefix section order/set diverges between renders: "
                     n1 " vs " n2)
                (not= t1 t2)
                (str "[" (name n1) "] renders DIFFERENTLY on a double-render "
                     "against the SAME db value — " (count t1) " vs "
                     (count t2) " chars, first diff at char "
                     (first-char-diff t1 t2) " (volatile bytes above the "
                     ":transcript boundary = silent provider-cache bust)")))
            (map vector p1 p2))
      (str "prefix section count diverges between renders: "
           (count p1) " vs " (count p2))))

(defn- capture-turn-profile
  "One `:seon.gym/turn-profile` for the turn about to run: call
   [[ctx/assemble-context]] TWICE against the pre-turn db value and
   derive the layout-completeness + prefix-stability verdicts from the
   composer's own output."
  [dbv agent-id designator]
  (let [render!  #(ctx/assemble-context {:seon.db/db dbv
                                         :seon.agent/id agent-id})
        r1       (render!)
        r2       (render!)
        names    (set (:seon.render/sections r1))
        missing  (vec (remove names (map :seon.ctx/name
                                         (ctx/substrate-default-ctx))))
        p1       (prefix-section-texts (:seon.render/section-texts r1))
        p2       (prefix-section-texts (:seon.render/section-texts r2))
        joined   (fn [p] (str/join "\n\n" (map :seon.render/text p)))
        stable?  (= (joined p1) (joined p2))]
    (cond-> {:seon.gym.profile/agent designator
             :seon.render/sections   (:seon.render/sections r1)
             :seon.gym.profile/section-chars
             (mapv (fn [{nm :seon.ctx/name txt :seon.render/text}]
                     [nm (count txt)])
                   (:seon.render/section-texts r1))
             :seon.gym.profile/layout-complete? (empty? missing)
             :seon.gym.profile/prefix-stable?   stable?}
      (seq missing) (assoc :seon.gym.profile/layout-missing missing)
      (not stable?) (assoc :seon.gym.profile/prefix-diff
                           (prefix-diff-detail p1 p2)))))

(defn- structural-results
  "The two standing structural results every run scores (U3) — derived
   from the captured turn-profiles, appended to the mechanical results
   so a regression flips the scorecard, never hides."
  [profiles]
  (let [bad-layout (filterv (complement :seon.gym.profile/layout-complete?)
                            profiles)
        bad-prefix (filterv (complement :seon.gym.profile/prefix-stable?)
                            profiles)
        n          (count profiles)
        base       (if (zero? n)
                     "0 turn-profiles captured (zero driven turns) — vacuous; "
                     (str n " turn-profile(s); "))]
    [{:seon.gym.predicate/id   :gym.structural/layout-complete
      :seon.gym.predicate/axis :context-fidelity
      :seon.gym.result/pass?   (empty? bad-layout)
      :seon.gym.result/actual
      (truncate-actual
        (if (empty? bad-layout)
          (str base "every substrate default section present in every "
               "turn's merged layout")
          (str base "MISSING substrate sections "
               (pr-str (mapv (juxt :seon.gym.profile/agent
                                   :seon.gym.profile/layout-missing)
                             bad-layout)))))}
     {:seon.gym.predicate/id   :gym.structural/cache-prefix-stable
      :seon.gym.predicate/axis :context-fidelity
      :seon.gym.result/pass?   (empty? bad-prefix)
      :seon.gym.result/actual
      (truncate-actual
        (if (empty? bad-prefix)
          (str base "double-render byte-identical up to :transcript on "
               "every turn")
          (str base (count bad-prefix) " UNSTABLE turn(s): "
               (str/join "; " (map :seon.gym.profile/prefix-diff
                                   bad-prefix)))))}]))

;; ===========================================================================
;; LLM-judge — rubric + reference facts + the agent's verbatim reply →
;; graded verdict {pass?/score/justification}, on the scorecard's
;; SEPARATE judge axis. The judge grades MEANING only; mechanical
;; predicates grade behavior. Calls go through the injected judge-fn
;; (tests) or the DeepSeek NON-thinking judge (allow-paid? only).
;; ===========================================================================

(def ^:private judge-system-prompt
  (str "You are a STRICT grader for an AI-agent evaluation harness.\n"
       "You receive: the question(s) a user asked an agent, the agent's\n"
       "verbatim reply, a grading rubric, and reference facts (ground\n"
       "truth). Grade ONLY the reply's semantic correctness against the\n"
       "rubric and the reference facts — never style, never behavior.\n"
       "A reply that contradicts the reference facts fails. A reply\n"
       "that is correct in different words passes.\n"
       "Output ONLY a JSON object, no markdown fences, no prose:\n"
       "{\"pass\": true|false, \"score\": 0-100, \"justification\": "
       "\"one short paragraph naming exactly what matched or failed\"}"))

(defn- agent-reply-text
  "All messages the designated agent sent TO the user, chronological,
   joined — the judge's 'verbatim reply'.

   Deliberately fetch-then-filter in CLJS rather than one datalog join:
   the datahike-cljs engine MIS-BINDS queries that join TWO
   identity-attr clauses ([?ag :seon.agent/id ?aid] + [?u :seon.user/id
   \"user\"]) through one message row — it ignores the :in binding and
   returns the inverse-direction (user→agent) rows. Pinned repro lives
   in driver_test's two-agent scenario comment; reported upstream as a
   query-engine smell. Until that's fixed, no gym predicate or judge
   query may use the double-identity-join shape."
  [dbv agent-id]
  (let [agent-eid (:db/id (db/entity {:seon.db/ref [:seon.agent/id agent-id]
                                      :seon.db/db  dbv}))
        user-eid  (:db/id (db/entity {:seon.db/ref [:seon.user/id "user"]
                                      :seon.db/db  dbv}))
        rows      (db/query {:seon.db/query '[:find ?m ?f ?t ?at ?c
                                              :where
                                              [?m :seon.agent.message/from ?f]
                                              [?m :seon.agent.message/to ?t]
                                              [?m :seon.agent.message/at ?at]
                                              [?m :seon.agent.message/content ?c]]
                             :seon.db/db dbv})
        mine      (->> rows
                       (filter (fn [[_ f t _ _]]
                                 (and (= f agent-eid) (= t user-eid))))
                       (sort-by (fn [[_ _ _ at _]] (.getTime ^js at)))
                       (map (fn [[_ _ _ _ c]] c)))]
    (if (seq mine)
      (str/join "\n\n" mine)
      "(the agent sent NO reply to the user)")))

(defn- judge-ctx
  "Assemble the grading context for one :llm-judge predicate: the
   designated agent's question(s), its verbatim reply, the rubric, the
   reference facts."
  [turns agents dbv {:seon.gym.predicate/keys [agent rubric reference]}]
  (let [designator (or agent :a)
        agent-id   (get agents designator)
        questions  (->> turns
                        (filter #(= designator
                                    (or (:seon.gym.turn/agent %) :a)))
                        (map :seon.gym.turn/message))]
    (str "== Question(s) the user asked the agent ==\n"
         (str/join "\n---\n" questions)
         "\n\n== Agent's reply (verbatim) ==\n"
         (if agent-id
           (agent-reply-text dbv agent-id)
           "(no such agent ran in this scenario)")
         "\n\n== Rubric ==\n" rubric
         "\n\n== Reference facts (ground truth) ==\n" reference)))

(defn- parse-judge-verdict
  "Parse the judge LLM's JSON verdict into a judge-result fragment.
   Unparseable output = pass? false with the raw text preserved —
   a mute judge must read as a fail, never a silent pass."
  [text]
  (let [cleaned (-> (str text) (str/replace #"```(json)?" "") str/trim)
        parsed  (try (js->clj (.parse js/JSON cleaned) :keywordize-keys true)
                     (catch :default _ nil))]
    (if (and (map? parsed) (boolean? (:pass parsed)))
      {:seon.gym.judge/pass? (:pass parsed)
       :seon.gym.judge/score (let [s (:score parsed)]
                               (if (number? s)
                                 (js/Math.round s)
                                 (if (:pass parsed) 100 0)))
       :seon.gym.judge/justification (str (:justification parsed))}
      {:seon.gym.judge/pass? false
       :seon.gym.judge/score 0
       :seon.gym.judge/justification
       (str "judge output unparseable — raw: " (truncate-actual text))})))

(defn- default-judge-fn
  "The DeepSeek judge: NON-thinking (the module default), temperature
   0, read-only use of seon.ai.deepseek's public `complete`. Built
   ONLY under the allow-paid? guard."
  []
  (fn [ctx]
    (.then (deepseek/complete {:seon.ai/ctx           ctx
                               :seon.ai/system-prompt judge-system-prompt
                               :seon.ai/temperature   0.0})
           (fn [resp]
             (cond-> {:text (:seon.ai/text resp)}
               (:seon.ai/error resp)
               (assoc :seon.ai/error (:seon.ai/error resp)))))))

(defn- skipped-judge-result
  "Verdict recorded when judge predicates exist but no judge is
   available — an explicit fail naming the guard, never a silent pass."
  [{:seon.gym.predicate/keys [id axis]}]
  {:seon.gym.predicate/id        id
   :seon.gym.predicate/axis      axis
   :seon.gym.judge/pass?         false
   :seon.gym.judge/score         0
   :seon.gym.judge/justification
   (str "judge SKIPPED — needs an injected :seon.gym/judge-fn (tests) "
        "or :seon.gym/allow-paid? true + DEEPSEEK_API_KEY (live "
        "grading); recorded as a fail, never a silent pass")})

(defn ^:async ^:private judge-predicates!
  "Run every :llm-judge predicate sequentially through `judge-fn` and
   return the vector of judge-results. An LLM error is a fail-verdict
   value (errors are values), never a thrown run."
  [judge-fn turns agents dbv preds]
  (loop [preds preds
         acc   []]
    (if-let [[p & more] (seq preds)]
      (let [resp    (try (await (judge-fn (judge-ctx turns agents dbv p)))
                         (catch :default e
                           {:text ""
                            :seon.ai/error {:seon.ai/msg (str e)}}))
            verdict (if (:seon.ai/error resp)
                      {:seon.gym.judge/pass? false
                       :seon.gym.judge/score 0
                       :seon.gym.judge/justification
                       (str "judge LLM call failed: "
                            (truncate-actual (pr-str (:seon.ai/error resp))))}
                      (parse-judge-verdict (:text resp)))]
        (recur more
               (conj acc (merge {:seon.gym.predicate/id
                                 (:seon.gym.predicate/id p)
                                 :seon.gym.predicate/axis
                                 (:seon.gym.predicate/axis p)}
                                verdict))))
      acc)))

;; ===========================================================================
;; The run
;; ===========================================================================

(defn- git-sha []
  (try
    (let [cp (js/require "node:child_process")]
      (str/trim (str (.execSync cp "git rev-parse --short HEAD"))))
    (catch :default _ "unknown")))

(defn- iso-date [^js d] (.slice (.toISOString d) 0 10))

(defn- days-ago [n]
  (iso-date (js/Date. (- (.getTime (js/Date.)) (* n 86400000)))))

(defn- resolve-fixture-dates
  "Replace {{today}} and {{days-ago:N}} placeholders in fixture string
   values with ISO dates relative to the run date (catalog §7
   relative-date fixtures — 'last week' must stay last week)."
  [fixtures]
  (walk/postwalk
    (fn [x]
      (if (string? x)
        (-> x
            (str/replace "{{today}}" (days-ago 0))
            (str/replace #"\{\{days-ago:(\d+)\}\}"
                         (fn [[_ n]] (days-ago (js/parseInt n 10)))))
        x))
    fixtures))

(defn ^:async ^:private eval-fixture-sources!
  "Evaluate fixture source strings through the bootstrap compile-state
   so the defined fns are CALLABLE from agent evals. Fails LOUD on any
   eval error — a half-seeded fixture must never silently score."
  [compile-state sources]
  (loop [sources sources]
    (when-let [[src & more] (seq sources)]
      (let [res (await (seval/eval compile-state src {:ns 'cljs.user}))]
        (when-not (:ok res)
          (throw (ex-info "gym: fixture source eval failed"
                          {:seon.gym/error      (pr-str (:error res))
                           :seon.gym.run/source src}))))
      (recur more))))

(defn- scripted-llm
  "Stub-tier llm-fn: resolves with exactly the scripted response text.
   One scripted text = one driven turn (see ns docstring on why the
   driver does NOT use the trigger loop for stubs)."
  [text]
  (fn [_ctx] (js/Promise.resolve {:text text})))

(defn- replay-llm
  "Scripted-replay llm-fn (catalog F-llm-script): one scripted text per
   loop turn, in order; once exhausted it answers prose-only, so the
   agentic loop's zero-forms stop policy terminates it."
  [scripts]
  (let [scripts (vec scripts)
        !i      (atom -1)]
    (fn [_ctx]
      (js/Promise.resolve
        {:text (or (nth scripts (swap! !i inc) nil)
                   "Script exhausted — nothing further to do.")}))))

(defn- rejecting-llm
  "Catalog F-llm-reject: a Promise that REJECTS after 100ms (simulated
   provider timeout). The turn must record :error, not hang or vanish."
  [_ctx]
  (js/Promise.
    (fn [_resolve reject]
      (js/setTimeout
        #(reject (js/Error. "gym: simulated LLM provider failure"))
        100))))

(defn ^:async ^:private send-user-message!
  "Land the scenario question as a real user message (the same
   `message!` entry point POST /chat uses). Returns the message id —
   the turn's `:seon.agent.turn/woken-by` anchor. Fails loud on a non-ok
   envelope: a scenario whose question never landed must not score."
  [agent-id text]
  (let [env (await (agent/message!
                     {:seon.agent.message/content text
                      :seon.agent.message/from    agent/user-ref
                      :seon.agent.message/to      [[:seon.agent/id agent-id]]}))]
    (when-not (:seon.agent.message/ok? env)
      (throw (ex-info "gym: user message! failed" env)))
    (:seon.agent.message/id env)))

(defn ^:async ^:private drive-stub-turns!
  "Drive one `run-turn!` per scripted LLM response — woken by `mid`."
  [agent-id compile-state mid scripts]
  (loop [scripts scripts]
    (when-let [[text & more] (seq scripts)]
      (await (db/with-agent agent-id
               (fn []
                 (agent/run-turn!
                   {:seon.agent/id            agent-id
                    :seon.agent/llm-fn        (scripted-llm text)
                    :seon.agent/compile-state compile-state
                    :seon.agent.turn/woken-by       [:seon.agent.message/id mid]}))))
      (recur more))))

(defn ^:async ^:private drive-loop!
  "Drive `run-agentic-loop!` (the REAL multi-turn driver) with the
   given llm-fn. The loop's own stop policies (zero forms / error /
   cap) are the awaits-idle signal — when the promise resolves, the
   agent is idle."
  [agent-id compile-state mid llm-fn]
  (await (db/with-agent agent-id
           (fn []
             (agent/run-agentic-loop!
               {:seon.agent/id            agent-id
                :seon.agent/llm-fn        llm-fn
                :seon.agent/compile-state compile-state
                :seon.agent.turn/woken-by       [:seon.agent.message/id mid]})))))

(defn ^:async ^:private ensure-agent!
  "Lazily create the agent behind a turn DESIGNATOR (:a, :b, …) on the
   scratch store. Multi-agent sequencing (catalog §7): turns run in
   order and every drive awaits idle, so 'boot A → await idle → boot
   B on the same store' falls out of the sequential doseq."
  [!agents compile-state designator]
  (if-let [existing (get @!agents designator)]
    existing
    (let [agent-id (db/new-id!)]
      ;; with-agent scope mirrors seon.client/boot-one-agent! — on a
      ;; live boot the agent's own create! tx carries its agent-id.
      (await
        (db/with-agent agent-id
          (fn ^:async boot-gym-agent! []
            (await (seval/setup-agent-ns! compile-state
                                          (agent/home-ns agent-id)
                                          agent-id))
            (await (agent/create! {:seon.agent/id agent-id})))))
      (swap! !agents assoc designator agent-id)
      agent-id)))

(defn ^:async seed-scenario-world!
  "Seed a scratch conn into THE WORLD A POD BOOTS INTO, then layer the
   scenario's prior-agent state on top. Two provenance layers, exactly
   like a live store:

   1. The pod's boot seed — the same calls as
      `seon.client/start-agent!`, in the same order: the substrate
      handler bootstrap (`h/bootstrap-schema!` + `wake/bootstrap-schema!`
      + the ONE `:wake/on-message` handler entity, data-only — no
      dispatcher armed), then the three transacts inside the same
      `{:seon.db/origin :substrate-seed}` tx-context: entity-schema
      decomposition (`schema/all-entity-schemas-tx-data`), the
      preamble + user entity (`client/seed-substrate!`), and the
      substrate index (`client/substrate-index-tx` — :seon.ns/:seon.fn
      rows PLUS a `:seon.schema` row per registered schema PLUS the
      :seon.test rows + test-sibling exemplar sources, conn-deduped;
      the test roster comes from the `seon.dev.test-preload` require
      in this ns — the SAME mechanism the pod build uses). Gym
      iteration 1 ran WITHOUT the entity-schema decomposition and
      index-schemas, so gym prompts differed from real pod prompts —
      which hid the S-32 catalog bug for a whole sweep; iteration 2
      ran without the test roster, so :exemplars rendered 4/7 blocks
      vs live. The seed-origin tx-context matters too:
      `seon.warn/domain-attrs` discriminates substrate vs agent attrs
      by exactly that provenance. The caller (run-scenario!) invokes
      this inside `(db/with-agent <:a's id>)` so the seed txs carry the
      primary agent's id like a live boot's do.

   2. The scenario's registrations + fixtures — the state a PRIOR
      agent left in the store. Registered into the live registry, then
      transacted in ORDINARY (non-seed) txs — inside a SYNTHETIC
      prior-agent `with-agent` scope — with the same tee-shaped
      `:seon.schema` rows `seon.eval/build-tee-entities` writes for a
      real register! eval — so seeded attrs like `:seon.workout/*`
      carry agent provenance (agent-id + non-seed origin, the
      classifier's exact predicate) and render in the domain-attrs
      reuse surface, exactly as on the live store.

   Fails LOUD on any non-ok envelope. Returns Promise<{:seon.gym/ok? true}>."
  {:malli/schema [:=> [:cat :seon.gym/seed-request] :seon.gym/seed-response]}
  [{conn     :seon.db/conn
    scenario :seon.gym/scenario}]
  (let [{:seon.gym.scenario/keys [schema-registrations fixtures]} scenario
        check! (fn [step {ok? :seon.db/ok? :as env}]
                 (when-not ok?
                   (throw (ex-info (str "gym: world seed transact failed at "
                                        step)
                                   env))))]
    ;; Substrate handler rows — the SAME calls start-agent! makes, in the
    ;; same order, BEFORE the seed-origin transacts: the hand-declared
    ;; datahike idents (composite-tuple identity the Malli bridge can't
    ;; emit) + the ONE `:wake/on-message` handler entity. Without these
    ;; the gym store renders a `:seon.handler` instance count of 0 where
    ;; every live store shows 1. No dispatcher is armed by these rows
    ;; (v0: handler entities are data; wake-up is the per-agent trigger,
    ;; which the gym deliberately does not install — the driver drives).
    ;; The handler fns read the root `db/*conn*`; pin it to THIS conn for
    ;; the duration (direct test callers don't pre-swap it the way
    ;; run-scenario! does), restoring in finally.
    (let [prev-conn db/*conn*]
      (set! db/*conn* conn)
      (try
        (await (h/bootstrap-schema!))
        (await (wake/bootstrap-schema!))
        (await (h/register!
                 {:seon.handler/name      :wake/on-message
                  :seon.handler/match     {:seon.handler.match/attr
                                           :seon.agent.message/to}
                  :seon.handler/fn        'seon.handlers.wake/wake-on-message
                  :seon.handler/on-origin #{:user :agent}}))
        (finally
          (set! db/*conn* prev-conn))))
    (await
      (db/with-tx-context {:seon.db/origin :substrate-seed}
        (fn ^:async boot-seed! []
          (check! :entity-schemas
                  (await (db/transact!
                           {:seon.db/conn conn
                            :seon.db/tx-data
                            (schema/all-entity-schemas-tx-data)})))
          (check! :substrate-seed
                  (await (db/transact!
                           {:seon.db/conn conn
                            :seon.db/tx-data (vec (client/seed-substrate!))})))
          (check! :substrate-index
                  (await (db/transact!
                           {:seon.db/conn conn
                            :seon.db/tx-data
                            (await (client/substrate-index-tx conn))}))))))
    ;; PRIOR-AGENT PROVENANCE: on a live store this layer was written by
    ;; a real agent inside its own with-agent scope, so its txs carry
    ;; `:seon.db/agent-id` (the context-model ns-leg classifies
    ;; agent-authored nses on exactly agent-id-present + non-seed
    ;; origin). Stamp the layer with a minted SYNTHETIC prior-agent id —
    ;; distinct from any designator the run boots — so classification
    ;; matches what a real prior agent's work looks like.
    (when (or (seq schema-registrations) (seq fixtures))
      (await
        (db/with-agent (db/new-id!)
          (fn ^:async seed-prior-agent-layer! []
            (when (seq schema-registrations)
              (doseq [[k v] schema-registrations] (schema/register! k v))
              (let [now  (js/Date.)
                    tee  (vec (for [[k v] schema-registrations]
                                (cond-> {:seon.schema/key        k
                                         :seon.schema/source     (pr-str
                                                                   (list 'seon.schema/register! k v))
                                         :seon.schema/created-at now}
                                  (namespace k)
                                  (assoc :seon.schema/ns
                                         {:seon.ns/name (keyword (namespace k))}))))
                    ;; entity-shape :map registrations ALSO decompose into
                    ;; id-attr/required-attrs rows (the catalog's kind blocks) —
                    ;; separate tx so the identity upsert merges cleanly.
                    deco (into [] (mapcat (comp schema/entity-schema-tx-data first))
                               schema-registrations)]
                (check! :scenario-schemas
                        (await (db/transact! {:seon.db/conn conn
                                              :seon.db/tx-data tee})))
                (when (seq deco)
                  (check! :scenario-entity-schemas
                          (await (db/transact! {:seon.db/conn conn
                                                :seon.db/tx-data deco}))))))
            (when (seq fixtures)
              (check! :fixtures
                      (await (db/transact! {:seon.db/conn conn
                                            :seon.db/tx-data
                                            (resolve-fixture-dates fixtures)}))))))))
    {:seon.gym/ok? true}))

(defn ^:async run-scenario!
  "Run ONE scenario end-to-end on a scratch `:memory` conn and return a
   Promise of the scorecard (or a refusal map — errors are values):

     - :todo scenarios refuse (encoded intent, not yet runnable).
     - :deepseek scenarios refuse unless `:seon.gym/allow-paid? true`
       AND DEEPSEEK_API_KEY is set — the suite must never burn money.

   Pipeline: open scratch conn → swap the root `seon.db/*conn*`
   (restored in finally) → ensure bootstrap compile-state → seed THE
   WORLD A POD BOOTS INTO + the scenario's prior-agent layer
   ([[seed-scenario-world!]]) (+ fixture sources) →
   per gym-turn: lazily boot the turn's agent, land the user message,
   drive per tier/llm-injection → evaluate mechanical predicates
   against the post-run db + transcript → run :llm-judge predicates on
   the separate judge axis (injected `:seon.gym/judge-fn`, or the
   DeepSeek judge under allow-paid?) → validated scorecard keyed
   (scenario × git sha)."
  {:malli/schema [:=> [:cat :seon.gym/run-request] :seon.gym/run-response]}
  [{scenario    :seon.gym/scenario
    allow-paid? :seon.gym/allow-paid?
    judge-fn    :seon.gym/judge-fn}]
  (let [{:seon.gym.scenario/keys [id tier status axes fixture-sources llm
                                  turns predicates]} scenario]
    (cond
      (= :todo status)
      {:seon.gym/ok? false
       :seon.gym/error (str "scenario " id " is :todo — encoded intent, "
                            "not yet runnable (see its :doc)")}

      (and (= :deepseek tier)
           (not (and allow-paid?
                     (.. js/process -env -DEEPSEEK_API_KEY))))
      {:seon.gym/ok? false
       :seon.gym/error (str "scenario " id " is :deepseek tier — costs real "
                            "money. Pass {:seon.gym/allow-paid? true} with "
                            "DEEPSEEK_API_KEY set to run it.")}

      :else
      (let [prev-conn    db/*conn*
            prev-fs      @sfs/!config
            keys-before  (schema/current-keys)]
        (try
          (let [conn          (await (client/open-agent-conn!))
                _             (set! db/*conn* conn)
                compile-state (await (repl/ensure-bootstrap!))
                !agents       (atom {})
                !profiles     (atom [])]
            ;; The scratch store must be THE WORLD A POD BOOTS INTO, not an
            ;; empty void: seed the substrate (instruction rows + the
            ;; :seon.ns/:seon.fn program-graph rows that render the "What
            ;; you can do" teaching — without them agents never learn that
            ;; grep/register!/reply! exist) and mirror the pod's fs
            ;; capability (bin/seon runs the pod with SEON_FS_ROOT=$SEON_ROOT
            ;; SEON_FS_READ_ONLY=1). Both restored/irrelevant after the run.
            (sfs/configure! {:seon.agent.fs/allowed-roots [(.cwd js/process)]
                             :seon.agent.fs/read-only?    true})
            ;; Boot designator :a FIRST, then run the boot seed inside
            ;; its with-agent scope — exactly start-agent!'s order (the
            ;; per-agent create! precedes the seed transacts, and the
            ;; seed txs carry the PRIMARY agent's id alongside the
            ;; :substrate-seed origin — the live store's provenance
            ;; shape, which the context-model classifier keys on).
            ;; The scenario's prior-agent layer inside
            ;; [[seed-scenario-world!]] re-scopes itself to a synthetic
            ;; prior-agent id (nested with-agent — inner wins).
            (let [primary (await (ensure-agent! !agents compile-state :a))]
              (await
                (db/with-agent primary
                  (fn []
                    (seed-scenario-world! {:seon.db/conn conn
                                           :seon.gym/scenario scenario})))))
            (await (eval-fixture-sources! compile-state fixture-sources))
            ;; Drive every gym turn, strictly in order; each turn's agent
            ;; boots lazily on first use (multi-agent sequencing).
            (doseq [{:seon.gym.turn/keys [message llm-script agent]} turns]
              (let [agent-id (await (ensure-agent! !agents compile-state
                                                   (or agent :a)))
                    mid      (await (send-user-message! agent-id message))]
                ;; U3: structural profile against the PRE-TURN db value —
                ;; the message has landed, the turn hasn't run; the exact
                ;; db the turn's prompt renders from.
                (swap! !profiles conj
                       (capture-turn-profile @conn agent-id (or agent :a)))
                (case (or llm (if (= :stub tier) :per-turn-script :deepseek))
                  :per-turn-script
                  (await (drive-stub-turns! agent-id compile-state mid
                                            llm-script))
                  :scripted-replay
                  (await (drive-loop! agent-id compile-state mid
                                      (replay-llm llm-script)))
                  :rejecting
                  (await (drive-loop! agent-id compile-state mid
                                      rejecting-llm))
                  :deepseek
                  (await (drive-loop! agent-id compile-state mid
                                      (deepseek/agent-adapter))))))
            ;; Mechanical scoring against the post-run store + transcript;
            ;; judge predicates score on the SEPARATE judge axis.
            (let [agents      @!agents
                  dbv         @conn
                  primary     (or (get agents :a) (second (first agents)))
                  transcript  (->> (sort-by key agents)
                                   (map (fn [[_ aid]]
                                          (agent/transcript-section
                                            {:seon.db/db    dbv
                                             :seon.agent/id aid})))
                                   (str/join "\n"))
                  mech-preds  (vec (remove #(= :llm-judge
                                               (:seon.gym.predicate/kind %))
                                           predicates))
                  judge-preds (filterv #(= :llm-judge
                                           (:seon.gym.predicate/kind %))
                                       predicates)
                  profiles    @!profiles
                  ;; scenario predicates first, then the two standing
                  ;; structural results (U3) — every run scores them.
                  results     (into (mapv #(eval-predicate dbv transcript
                                                           agents %)
                                          mech-preds)
                                    (structural-results profiles))
                  judge-fn*   (or judge-fn
                                  (when (and allow-paid?
                                             (.. js/process -env
                                                 -DEEPSEEK_API_KEY))
                                    (default-judge-fn)))
                  judge-results
                  (if (seq judge-preds)
                    (if judge-fn*
                      (await (judge-predicates! judge-fn* turns agents dbv
                                                judge-preds))
                      (mapv skipped-judge-result judge-preds))
                    nil)
                  card (cond->
                         {:seon.gym.scorecard/scenario id
                          :seon.gym.scorecard/git-sha  (git-sha)
                          ;; §3.1: fresh per run — a double-run under one
                          ;; (scenario × sha) key is two DISTINCT cards.
                          :seon.gym.scorecard/run-id   (random-uuid)
                          :seon.gym.scorecard/tier     tier
                          :seon.gym.scorecard/at       (js/Date.)
                          :seon.gym.scorecard/agent-id primary
                          :seon.gym.scorecard/pass?
                          (every? :seon.gym.result/pass? results)
                          :seon.gym.scorecard/axes
                          (axes-rollup axes results)
                          :seon.gym.scorecard/results  results
                          ;; gym-upgrade §6.6 (default-on): the run's
                          ;; per-turn prompt-blob paths, chronological —
                          ;; a moved number diffs to the exact context
                          ;; bytes the agent saw. nil paths (blob write
                          ;; failures) are dropped here; the prompt
                          ;; PREDICATES are where missing blobs go RED.
                          :seon.gym.scorecard/prompt-files
                          (into [] (keep second) (turn-prompt-files dbv nil))
                          ;; U3 evidence: one structural profile per
                          ;; driven turn, chronological.
                          :seon.gym.scorecard/turn-profiles profiles}
                         (seq judge-preds)
                         (assoc :seon.gym.scorecard/judge-pass?
                                (every? :seon.gym.judge/pass? judge-results)
                                :seon.gym.scorecard/judge-results
                                judge-results))]
              (when-not (m/validate :seon.gym/scorecard card)
                (throw (ex-info "gym: emitted scorecard fails its own schema"
                                {:seon.gym/explain
                                 (pr-str (m/explain :seon.gym/scorecard card))})))
              card))
          (finally
            ;; Restore the root conn + fs capability config + drop every
            ;; schema key minted during the run (scenario registrations AND
            ;; agent-eval register!s) so one scenario can't leak into the
            ;; next (or into non-gym tests sharing the process).
            (set! db/*conn* prev-conn)
            (reset! sfs/!config prev-fs)
            (let [minted (remove keys-before (schema/current-keys))]
              (when (seq minted)
                (swap! schema/*schemas #(apply dissoc % minted))))))))))

(defn print-scorecard!
  "Print the scorecard as one greppable line (`bin/gym` surfaces these
   from the suite output) and return it unchanged."
  {:malli/schema [:=> [:cat :seon.gym/scorecard] :seon.gym/scorecard]}
  [card]
  (println "SEON-GYM SCORECARD" (pr-str card))
  card)
