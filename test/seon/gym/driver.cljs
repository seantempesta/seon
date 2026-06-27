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
                 (`:seon.gym.turn/llm-script`, one text per turn) and the
                 driver opens a `:message` run then drives ONE `run-turn!`
                 per script entry — deliberately NOT the trigger-driven
                 loop, because a stub script that never emits a terminal
                 verb would otherwise drive the loop to its run turn-limit.
                 Set `:seon.gym.scenario/llm` to `:scripted-replay` to
                 drive the REAL agentic loop with a replaying llm-fn instead
                 (the script's last entry must close the run — e.g.
                 `(wait …)` — since the loop runs until a bound or verb
                 closes the run), or `:rejecting` for the
                 simulated-provider-failure fixture.
     :paid     — costs real money. The driver wires the ACTIVE
                 provider's agent adapter (`seon.ai/provider` —
                 anthropic or deepseek) through `seon.agent.loop/run-loop!`
                 (awaits the loop's own termination = idle), but
                 REFUSES to run unless the caller passes
                 `:seon.gym/allow-paid? true` AND the active
                 provider's API key is set. LLM-judge predicates run
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
     - fixture-sources tee into :seon.fn rows (the fns ARE callable;
       prompt visibility — the reconstituted <namespace> tags,
       context-v4 — needs the record-eval! tee outside a turn;
       required before S-31 function-reuse is encoded)."
  (:require
    [cljs.reader :as reader]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [malli.core :as m]
    [seon.agent :as agent]
    [seon.ai :as ai]
    [seon.ai.anthropic :as anthropic]
    [seon.ai.openai-compat :as openai]
    [seon.client :as client]
    [seon.agent.turn :as turn]
    [seon.agent.run :as run]
    [seon.agent.loop :as aloop]
    [seon.agent.ctx :as ctx]
    [seon.db :as db]
    [seon.debug :as debug]
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
    [seon.agent.fs.internal :as sfs-int]
    [seon.log :as slog]
    [seon.repl :as repl]
    [seon.schema :as schema]
    [seon.warn :as warn]))

;; QUIET LANE for gym/scratch worlds (opus-live-tests 2026-06-12
;; limitation 10): datahike's per-index-node trace logging wrote
;; 27-49MB per suite run with single MB-sized lines (whole tx-data
;; inline), drowning paid-run evidence and the test footer grep. The
;; live pod installs this exact gate at boot (seon.client/-main); the
;; :test/gym processes never ran -main, so they flooded. Same
;; mechanism, same defaults — trace/debug from the replikativ stack
;; drop, info/warn/error pass, seon's own logging is untouched.
(slog/quiet-library-logs!)

;; ===========================================================================
;; Schemas — scenario, predicate, result, scorecard. Registered once,
;; referenced everywhere (shared-shape rule). Keyword namespaces are
;; multi-segment data namespaces under :seon.gym.* (same convention as
;; the taught my.kb.<domain> shape).
;; ===========================================================================

;; --- rubric -----------------------------------------------------------------
;; The §7 item-12 behavioral vocabulary — agent BEHAVIOR only. The gym
;; tests the agent (mechanical store/outcome checks + LLM judge), never
;; the context layout itself (user r2, 2026-06-11: structural gates
;; broke the gym on every context change and were ripped out).
(schema/register! :seon.gym.axis/name
  [:enum :sees-question :searches-first :models-work-directed
   :reuses-schemas :consults-findings :reuses-functions
   :writes-tests :replies-honestly :terminates :stores-proactively])

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
;;   the agent ACTUALLY SAW. run-turn! persists every full prompt (via
;;   seon.debug capture, forced ON for gym runs) to
;;   <debug-dir>/<agent-id>/<turn-idx>-<turn-id>/prompt.txt (the turn
;;   datom carries :seon.agent.turn/prompt-file); the driver collects the run's turns
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
(schema/register! :seon.gym.scenario/tier   [:enum :stub :paid])
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
;; :seon.fn rows too, so they render in the reconstituted <namespace>
;; tags (context-v4; needs the record-eval! tee outside a turn) —
;; required before S-31 (function reuse) is encoded.
(schema/register! :seon.gym.scenario/fixture-sources [:vector :string])
;; Per-scenario llm-fn injection (catalog §7), stub tier only:
;;   :scripted-replay — drive seon.agent.loop/run-loop! (the REAL
;;     agentic loop) with an llm-fn replaying the turn's :llm-script
;;     entries in order; the script's last entry must close the run (a
;;     lifecycle verb like `(wait …)`) since the loop runs until a bound
;;     or verb closes the run.
;;   :rejecting — an llm-fn whose Promise REJECTS after 100ms
;;     (simulated provider failure; catalog F-llm-reject / S-08).
;; Absent on a stub scenario = the one-run-turn!-per-script-entry
;; driver (see ns docstring — opens a `:message` run, no trigger loop).
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
;; --- per-turn context telemetry (informational, NEVER gates pass?) ----------
;; Captured once per driven gym turn from `assemble-context`'s OWN
;; output against the PRE-TURN db value (user message landed, turn not
;; yet run — the exact db the turn's prompt renders from). Pure
;; evidence for "what context was loaded for this turn": section names
;; in render order + per-section char counts. The gym's job is testing
;; the AGENT — no layout predicate, no section-name coupling, nothing
;; here affects the scorecard verdict (user r2, 2026-06-11).
(schema/register! :seon.gym.profile/agent :seon.gym.turn/agent)
;; Section names in render order (the non-blank contributions from
;; ctx-sections' :seon.render/section-texts). The carve retired
;; :seon.render/sections (its old producer assemble-context is gone), so
;; the profile carries its own gym-local copy.
(schema/register! :seon.gym.profile/sections [:vector :seon.agent.ctx/name])
;; [section-name rendered-char-count] in render order — only the
;; non-blank contributions (ctx-sections' :seon.render/section-texts).
(schema/register! :seon.gym.profile/section-chars
  [:vector [:tuple :seon.agent.ctx/name :int]])
(schema/register! :seon.gym/turn-profile
  [:map
   [:seon.gym.profile/agent         :seon.gym.profile/agent]
   [:seon.gym.profile/sections      :seon.gym.profile/sections]
   [:seon.gym.profile/section-chars :seon.gym.profile/section-chars]])
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
   ;; one context-telemetry profile per driven turn, chronological —
   ;; informational evidence only, never part of the verdict.
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

(defn- normalize-ws
  "Collapse every whitespace run (incl. newlines) to one space, trimmed
   — text predicates compare NORMALIZED forms (user decision
   2026-06-11: the s32 salience text lived in message!'s docstring
   SPLIT BY A LINE BREAK, so verbatim matching both missed real prompt
   contamination and broke on rendered line wrapping)."
  [s]
  (str/trim (str/replace (str s) #"\s+" " ")))

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
   anchor on attrs/structure, never on the question string.

   Matching is WHITESPACE-NORMALIZED ([[normalize-ws]], user decision
   2026-06-11): a turn message hiding in a fixture across a line break
   is the same self-bait."
  [path {:seon.gym.scenario/keys [id fixtures fixture-sources turns]}]
  (doseq [msg (keep :seon.gym.turn/message turns)]
    (doseq [[i fx] (map-indexed vector fixtures)
            s      (fixture-strings fx)
            :when  (str/includes? (normalize-ws s) (normalize-ws msg))]
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
            :when   (str/includes? (normalize-ws src) (normalize-ws msg))]
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
  "All [at source] eval pairs from RUN-DRIVEN turns (turns stamped with a
   `:seon.agent.turn/run` whose run carries a `:seon.agent.run/cause` — a
   message-triggered run), chronological. With `agent-id`, scoped to that
   agent's evals via agent ← run ← turn ← evals; nil = the whole scratch
   store (single-agent scenarios).

   The cause clause excludes the BOOTSTRAP turn's tutorial evals (turn 0
   hello + park — boot parity): `seon.client/bootstrap-turn!` opens its run
   with NO cause, while every message-driven run the gym opens carries the
   waking message as its `:seon.agent.run/cause`. Eval-shaped predicates
   measure the agent's behavior IN RESPONSE TO MESSAGES; the boot tutorial
   is core-scripted, not behavior, and would otherwise be every scenario's
   'first eval'."
  [dbv agent-id]
  (->> (if agent-id
         (db/query {:seon.db/query '[:find ?at ?src ?ev
                                     :in $ ?aid
                                     :where
                                     [?ag :seon.agent/id ?aid]
                                     [?r :seon.agent.run/agent ?ag]
                                     [?r :seon.agent.run/cause _]
                                     [?t :seon.agent.turn/run ?r]
                                     [?t :seon.agent.turn/evals ?ev]
                                     [?ev :seon.eval/at ?at]
                                     [?ev :seon.eval/source ?src]]
                    :seon.db/args [agent-id]
                    :seon.db/db   dbv})
         (db/query {:seon.db/query '[:find ?at ?src ?ev
                                     :where
                                     [?r :seon.agent.run/cause _]
                                     [?t :seon.agent.turn/run ?r]
                                     [?t :seon.agent.turn/evals ?ev]
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
  "Chronological [turn-id prompt-file-or-nil] pairs for every RUN-DRIVEN
   turn (stamped with a `:seon.agent.turn/run` whose run carries a
   `:seon.agent.run/cause` — the bootstrap turn's run has no cause and
   renders no prompt, so prompt predicates range over the turns the agent
   was actually prompted on) in the post-run store — optionally scoped to
   one agent's turns via agent ← run ← turn. A nil prompt-file means the
   blob was never written (capture failure, or a seeded turn without one)
   — prompt-predicate callers MUST treat that as RED."
  [dbv agent-id]
  (->> (if agent-id
         (db/query {:seon.db/query '[:find ?at ?tid ?t
                                     :in $ ?aid
                                     :where
                                     [?ag :seon.agent/id ?aid]
                                     [?r :seon.agent.run/agent ?ag]
                                     [?r :seon.agent.run/cause _]
                                     [?t :seon.agent.turn/run ?r]
                                     [?t :seon.agent.turn/id ?tid]
                                     [?t :seon.agent.turn/at ?at]]
                    :seon.db/args [agent-id]
                    :seon.db/db   dbv})
         (db/query {:seon.db/query '[:find ?at ?tid ?t
                                     :where
                                     [?r :seon.agent.run/cause _]
                                     [?t :seon.agent.turn/run ?r]
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
   persisted (via seon.debug capture, forced ON for gym runs) to
   <debug-dir>/<agent-id>/<turn-idx>-<turn-id>/prompt.txt, located via
   the post-run store's :seon.agent.turn/prompt-file datoms. Returns
   [pass? actual]. Every blind spot is RED, never a silent pass: zero
   turns, an out-of-range :turn index, a turn with no prompt-file
   datom, an unreadable blob — each named in the actual.

   Containment is WHITESPACE-NORMALIZED ([[normalize-ws]]): rendered
   prompts line-wrap source text, so verbatim matching missed real
   contamination (the s32 salience text split by a docstring line
   break) and flaked on wrapping."
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
                                            "under the debug-capture dir, "
                                            "default logs/turns/)")}
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
          (let [norm  (normalize-ws text)
                hits  (filterv #(str/includes? (normalize-ws (:text %)) norm)
                               reads)
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
            ;; The query typo-guard (seon.db/query, context-v4) throws a
            ;; legible error when a query names an attr that is neither
            ;; installed nor registered. Predicates DELIBERATELY query
            ;; such attrs to assert "nothing landed" (e.g. a rejected
            ;; unregistered attr) — for the gym's purposes the honest
            ;; answer is the empty set, so map that one error back to [].
            (let [rows (try
                         (vec (db/query (cond-> {:seon.db/query query
                                                 :seon.db/db    dbv}
                                          args (assoc :seon.db/args
                                                      (resolve-predicate-args
                                                        agents args)))))
                         (catch :default e
                           (if (get-in (ex-data e)
                                       [:seon.error/data
                                        :seon.db/missing-attrs])
                             []
                             (throw e))))]
              [(expect-pass? expect rows)
               (str "rows=" (pr-str rows) " expect=" (pr-str expect))])

            ;; whitespace-normalized containment (same rule as the
            ;; prompt-blob predicates — see [[normalize-ws]]).
            :transcript-includes
            [(str/includes? (normalize-ws transcript) (normalize-ws text))
             (str "transcript " (count transcript) " chars; looked for " (pr-str text))]

            :transcript-excludes
            [(not (str/includes? (normalize-ws transcript) (normalize-ws text)))
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
;; Per-turn context telemetry — INFORMATIONAL ONLY. One render of
;; `assemble-context` against the pre-turn db value records what
;; context was loaded for the turn (section names + char counts). It
;; never gates pass?: the gym tests the agent, not the layout (user
;; r2, 2026-06-11 — the former structural gates broke on every context
;; change and were removed).
;; ===========================================================================

(defn- capture-turn-profile
  "One `:seon.gym/turn-profile` for the turn about to run — section
   names in render order + per-section char counts from
   [[ctx/ctx-sections]] (the SAME post-budget per-section path the prompt
   and the inspector take) against the pre-turn db value."
  [dbv agent-id designator]
  (let [texts (:seon.render/section-texts
                (ctx/ctx-sections {:seon.db/db dbv
                                   :seon.agent/id agent-id}))]
    {:seon.gym.profile/agent    designator
     :seon.gym.profile/sections (mapv :seon.agent.ctx/name texts)
     :seon.gym.profile/section-chars
     (mapv (fn [{nm :seon.agent.ctx/name txt :seon.render/text}]
             [nm (count txt)])
           texts)}))

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
  "The messages the designated agent sent TO the user IN RESPONSE TO the
   scenario's question(s), chronological, joined — the judge's 'verbatim
   reply'. EXCLUDES the bootstrap greeting: turn 0 (`bootstrap-turn!`,
   boot parity) sends `(message/user \"Hi — I'm up …\")` BEFORE any
   question lands, so a reply is one whose `:seon.agent.message/at` is at
   or after the earliest user→agent question — anything earlier is the
   pre-question hello, not an answer (it would otherwise pollute the
   judge's view of what the agent actually answered).

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
        ms        (fn [^js at] (.getTime at))
        ;; earliest question time = first user→this-agent message; replies
        ;; before it are the bootstrap greeting, not answers.
        q-from    (->> rows
                       (filter (fn [[_ f t _ _]] (and (= f user-eid)
                                                      (= t agent-eid))))
                       (map (fn [[_ _ _ at _]] (ms at)))
                       (reduce min js/Infinity))
        mine      (->> rows
                       (filter (fn [[_ f t at _]]
                                 (and (= f agent-eid) (= t user-eid)
                                      (>= (ms at) q-from))))
                       (sort-by (fn [[_ _ _ at _]] (ms at)))
                       (map (fn [[_ _ _ _ c]] c)))]
    (if (seq mine)
      (str/join "\n\n" mine)
      "(the agent sent NO reply to the user)")))

(defn- format-judge-ctx
  "The judge's grading-context STRING — question(s), the verbatim reply,
   the rubric, the reference facts. ONE formatter shared by the live
   judge path ([[judge-ctx]]) and the calibration probe
   ([[calibrate-judge!]]) so a calibrated judge grades the byte-identical
   shape it sees in a real run."
  [questions reply rubric reference]
  (str "== Question(s) the user asked the agent ==\n"
       (str/join "\n---\n" questions)
       "\n\n== Agent's reply (verbatim) ==\n" reply
       "\n\n== Rubric ==\n" rubric
       "\n\n== Reference facts (ground truth) ==\n" reference))

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
    (format-judge-ctx questions
                      (if agent-id
                        (agent-reply-text dbv agent-id)
                        "(no such agent ran in this scenario)")
                      rubric reference)))

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
   0, read-only use of seon.ai.openai-compat's public `complete`. Built
   ONLY under the allow-paid? guard.

   MODEL PINNED EXPLICITLY (2026-06-12, paid sweep run 0): the
   `:seon.ai/config` row's `:seon.ai/model` is provider-UNQUALIFIED —
   a sweep steering the AGENT via SEON_AI_MODEL=claude-sonnet-4-6
   leaked that name into the judge's deepseek call (HTTP 400 \"The
   supported API model names are deepseek-v4-pro or
   deepseek-v4-flash\"), killing every judge verdict. The judge is a
   FIXED grading instrument — it must never inherit the agent-under-
   test's model steering."
  []
  (fn [ctx]
    (.then (openai/complete {:seon.ai/ctx           ctx
                             :seon.ai/system-prompt judge-system-prompt
                             :seon.ai/model         "deepseek-v4-pro"
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

;; ===========================================================================
;; JUDGE CALIBRATION — before trusting the judge as the PRIMARY signal, prove
;; it DISCRIMINATES: feed it a known-GOOD reply (must PASS) and a known-BAD /
;; fabricated reply (must FAIL) for the SAME rubric + reference facts, through
;; the SAME judge-fn + the SAME ctx format a real run uses. A judge that
;; passes both (or fails both) is noise — a rubric/structure bug to fix, NOT a
;; signal to trust. `discriminates?` = good PASS ∧ bad FAIL.
;; ===========================================================================

(schema/register! :seon.gym.calib/question   :string)
(schema/register! :seon.gym.calib/rubric     :string)
(schema/register! :seon.gym.calib/reference  :string)
(schema/register! :seon.gym.calib/good-reply :string)
(schema/register! :seon.gym.calib/bad-reply  :string)
(schema/register! :seon.gym.calib/request
  [:map
   [:seon.gym.calib/question   :seon.gym.calib/question]
   [:seon.gym.calib/rubric     :seon.gym.calib/rubric]
   [:seon.gym.calib/reference  :seon.gym.calib/reference]
   [:seon.gym.calib/good-reply :seon.gym.calib/good-reply]
   [:seon.gym.calib/bad-reply  :seon.gym.calib/bad-reply]
   [:seon.gym/judge-fn    {:optional true} :seon.gym/judge-fn]
   [:seon.gym/allow-paid? {:optional true} :seon.gym/allow-paid?]])
(schema/register! :seon.gym.calib/verdict
  [:map
   [:seon.gym.judge/pass?         :seon.gym.judge/pass?]
   [:seon.gym.judge/score         :seon.gym.judge/score]
   [:seon.gym.judge/justification :seon.gym.judge/justification]])
(schema/register! :seon.gym.calib/discriminates? :boolean)
(schema/register! :seon.gym.calib/response
  [:map
   [:seon.gym.calib/good          :seon.gym.calib/verdict]
   [:seon.gym.calib/bad           :seon.gym.calib/verdict]
   [:seon.gym.calib/discriminates? :seon.gym.calib/discriminates?]])

(defn ^:async calibrate-judge!
  "Run the judge twice on the SAME rubric + reference facts — once with a
   known-GOOD reply, once with a known-BAD/fabricated reply — and report
   whether it discriminated. Uses the injected `:seon.gym/judge-fn`, or
   the real DeepSeek judge under `:seon.gym/allow-paid? true` +
   DEEPSEEK_API_KEY; with neither, both verdicts record the explicit
   SKIP fail (so `discriminates?` is false, never a silent pass).

   Returns Promise<:seon.gym.calib/response>:
   `{:seon.gym.calib/good <verdict> :seon.gym.calib/bad <verdict>
     :seon.gym.calib/discriminates? (good-PASS ∧ bad-FAIL)}`."
  {:malli/schema [:=> [:cat :seon.gym.calib/request] :seon.gym.calib/response]}
  [{:seon.gym.calib/keys [question rubric reference good-reply bad-reply]
    judge-fn :seon.gym/judge-fn allow-paid? :seon.gym/allow-paid?}]
  (let [judge-fn (or judge-fn
                     (when (and allow-paid?
                                (.. js/process -env -DEEPSEEK_API_KEY))
                       (default-judge-fn)))
        grade    (fn ^:async grade-reply [reply]
                   (if-not judge-fn
                     {:seon.gym.judge/pass? false :seon.gym.judge/score 0
                      :seon.gym.judge/justification
                      (str "judge SKIPPED — inject :seon.gym/judge-fn or set "
                           ":seon.gym/allow-paid? true + DEEPSEEK_API_KEY")}
                     (let [resp (try (await (judge-fn (format-judge-ctx
                                                        [question] reply
                                                        rubric reference)))
                                     (catch :default e {:text "" :seon.ai/error
                                                        {:seon.ai/msg (str e)}}))]
                       (if (:seon.ai/error resp)
                         {:seon.gym.judge/pass? false :seon.gym.judge/score 0
                          :seon.gym.judge/justification
                          (str "judge LLM call failed: "
                               (truncate-actual (pr-str (:seon.ai/error resp))))}
                         (parse-judge-verdict (:text resp))))))
        good     (await (grade good-reply))
        bad      (await (grade bad-reply))]
    {:seon.gym.calib/good           good
     :seon.gym.calib/bad            bad
     :seon.gym.calib/discriminates?
     (boolean (and (:seon.gym.judge/pass? good)
                   (not (:seon.gym.judge/pass? bad))))}))

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

(defn- usage-logging
  "Wrap a paid llm-fn so every response's usage block prints as ONE
   greppable line (`SEON-GYM LLM-USAGE <provider> <usage>`). Spend
   telemetry: paid sweeps previously had NO per-call token/cost
   evidence — budgets were tracked by guesswork. Response passes
   through unchanged."
  [provider llm-fn]
  (fn [ctx]
    (.then (llm-fn ctx)
           (fn [resp]
             (when-let [u (get-in resp [:seon.ai/raw :seon.ai/usage])]
               (println "SEON-GYM LLM-USAGE" (name provider) (pr-str u)))
             resp))))

(defn- paid-adapter
  "The paid tier's agent llm-fn: the provider `seon.ai/provider`
   selects (SEON_AI_PROVIDER env / `:seon.ai/config` row — the SAME
   selection point as the live pod's `seon.client/current-llm-fn`),
   wrapped in [[usage-logging]]. Scenario files carry
   `:seon.gym.scenario/tier :paid` (renamed from the historical
   `:deepseek` 2026-06-12, L13 — the name predated provider dispatch)."
  []
  (let [provider (ai/provider)]
    (usage-logging provider
                   (case provider
                     :anthropic (anthropic/agent-adapter)
                     (openai/agent-adapter)))))

(defn- paid-key-set?
  "Is the ACTIVE provider's API key present? The paid-tier budget
   guard — generalized from the former hardwired DEEPSEEK_API_KEY
   check when the provider became selectable."
  []
  (case (ai/provider)
    :anthropic (boolean (.. js/process -env -ANTHROPIC_API_KEY))
    (boolean (.. js/process -env -DEEPSEEK_API_KEY))))

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
   `message!` entry point POST /chat uses). Returns the message id. On a
   live boot this inbound datom would trip the wake trigger; the gym drives
   explicitly instead ([[drive-loop!]] / [[drive-stub-turns!]] open the run
   themselves, cause = this message), so the trigger is never armed here.
   Fails loud on a non-ok envelope: a scenario whose question never landed
   must not score."
  [agent-id text]
  (let [env (await (agent/message!
                     {:seon.agent.message/content text
                      :seon.agent.message/from    agent/user-ref
                      :seon.agent.message/to      [[:seon.agent/id agent-id]]}))]
    (when-not (:seon.agent.message/ok? env)
      (throw (ex-info "gym: user message! failed" env)))
    (:seon.agent.message/id env)))

(defn ^:async ^:private drive-stub-turns!
  "Drive one `turn/run-turn!` per scripted LLM response under a freshly
   OPENED `:message` run (cause = the waking user message), so each turn
   carries `:seon.agent.turn/run` and the run carries a cause — the marker
   eval/prompt predicates scope on caused runs, distinguishing
   message-driven turns from the bootstrap turn 0 (whose run has no cause).
   Closes the run `:completed` when the scripts are exhausted — unless a
   lifecycle verb in a script already closed it (a script `(wait …)` /
   `(complete …)` leaves the agent :idle, so `current-run` is nil and we
   skip). Deliberately NOT the trigger-driven loop: a stub script that
   never emits a terminal verb would otherwise drive the loop to its run
   turn-limit. Fails loud if the run never opened — a scenario whose turns
   were never stamped must not silently score."
  [agent-id compile-state cause scripts]
  (let [opened (await (run/open-run! {:seon.agent/id          agent-id
                                      :seon.agent.run/trigger :message
                                      :seon.agent.run/cause   cause}))]
    (when (false? (:seon.db/ok? opened))
      (throw (ex-info "gym: drive-stub-turns! open-run! failed" opened)))
    (let [run-id (:seon.agent.run/id opened)]
      (loop [scripts scripts]
        (when-let [[text & more] (seq scripts)]
          (await (db/with-agent agent-id
                   (fn []
                     (turn/run-turn!
                       {:seon.agent/id            agent-id
                        :seon.agent/llm-fn        (scripted-llm text)
                        :seon.agent/compile-state compile-state
                        :seon.agent.run/id        run-id}))))
          (recur more)))
      (when (run/current-run {:seon.agent/id agent-id})
        (await (run/close-run! {:seon.agent.run/id            run-id
                                :seon.agent.run/closed-reason :completed}))))))

(defn ^:async ^:private drive-loop!
  "Drive `seon.agent.loop/run-loop!` (the REAL multi-turn driver) under a
   freshly OPENED `:message` run (cause = the waking user message), exactly
   as the live `wake-handler` :idle branch does: open the run, then hand
   its run-id to `run-loop!`. The loop's own stop policies (turn-limit /
   deadline / error / a lifecycle verb closing the run) are the awaits-idle
   signal — when the promise resolves the agent is :idle (or :terminated).
   Because the loop runs until a bound or verb closes the run, a
   `:scripted-replay` script's last entry must close the run (e.g.
   `(wait …)`). Fails loud if the run never opened."
  [agent-id compile-state cause llm-fn]
  (await
    (db/with-agent agent-id
      (fn ^:async drive! []
        (let [opened (await (run/open-run! {:seon.agent/id          agent-id
                                            :seon.agent.run/trigger :message
                                            :seon.agent.run/cause   cause}))]
          (if (false? (:seon.db/ok? opened))
            (throw (ex-info "gym: drive-loop! open-run! failed" opened))
            (await (aloop/run-loop!
                     {:seon.agent/id            agent-id
                      :seon.agent/llm-fn        llm-fn
                      :seon.agent/compile-state compile-state}
                     (:seon.agent.run/id opened)))))))))

(defn ^:async ^:private ensure-agent!
  "Lazily create the agent behind a turn DESIGNATOR (:a, :b, …) on the
   scratch store. Multi-agent sequencing (catalog §7): turns run in
   order and every drive awaits idle, so 'boot A → await idle → boot
   B on the same store' falls out of the sequential doseq.

   Boot parity: after create!, the agent runs the SAME
   `seon.client/bootstrap-turn!` (turn 0) a live minted agent runs — the
   hello + park — so its transcript carries the bootstrap-turn evidence
   exactly like a live agent's. (Turn 0's run is opened with NO
   `:seon.agent.run/cause`; eval- and prompt-shaped predicates scope on
   caused runs and so exclude it — see [[eval-at+source]].)

   `pre-id` (optional) pins the minted agent's id — run-scenario!
   mints :a's id BEFORE seeding so the seed txs can carry it, then
   boots :a here AFTER the seed (the live mint-onto-populated-store
   order, so :a's creation evals see the seeded world)."
  [!agents compile-state designator & [pre-id]]
  (if-let [existing (get @!agents designator)]
    existing
    (let [agent-id (or pre-id (db/new-id!))]
      ;; with-agent scope mirrors seon.client/boot-one-agent! — on a
      ;; live boot the agent's own create! tx carries its agent-id.
      (await
        (db/with-agent agent-id
          (fn ^:async boot-gym-agent! []
            (await (seval/setup-agent-ns! compile-state
                                          (agent/home-ns agent-id)
                                          agent-id))
            (await (agent/create! {:seon.agent/id agent-id})))))
      (await (client/bootstrap-turn!
               {:seon.agent/id            agent-id
                :seon.agent/compile-state compile-state}))
      (swap! !agents assoc designator agent-id)
      agent-id)))

(defn ^:async seed-scenario-world!
  "Seed a scratch conn into THE WORLD A POD BOOTS INTO, then layer the
   scenario's prior-agent state on top. Two provenance layers, exactly
   like a live store:

   1. The pod's boot seed — `seon.client/boot-seed!`, the boot's OWN
      code path (handlers + entity-schema decomposition +
      seed-core! + soul seed + core index, under the
      `:core-seed` origin). One mechanism: the gym used to
      hand-mirror this sequence and drifted twice (iteration 1 missed
      the entity-schema decomposition + index-schemas — hid the S-32
      catalog bug for a whole sweep; iteration 2 missed the test
      roster — :exemplars rendered 4/7 blocks vs live; the third
      drift, missing the `:soul-seed` step, is what forced the
      extraction). The seed-origin tx-context matters:
      `seon.warn/domain-attrs` discriminates core vs agent attrs
      by exactly that provenance. The caller (run-scenario!) invokes
      this inside `(db/with-agent <:a's id>)` so the seed txs carry
      the primary agent's id like a live boot's do. The test roster
      comes from the `seon.dev.test-preload` require in this ns — the
      SAME mechanism the pod build uses.

   2. The scenario's registrations + fixtures — the state a PRIOR
      agent left in the store. Registered into the live registry, then
      transacted in ORDINARY (non-seed) txs — inside a SYNTHETIC
      prior-agent `with-agent` scope — with the same tee-shaped
      `:seon.schema` rows `seon.eval/build-tee-entities` writes for a
      real register! eval — so seeded attrs like `:my.workout/*`
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
    (await (client/boot-seed! {:seon.db/conn conn}))
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
     - :paid scenarios refuse unless `:seon.gym/allow-paid? true` AND
       the active provider's API key is set — the suite must never
       burn money.

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

      (and (= :paid tier)
           (not (and allow-paid? (paid-key-set?))))
      {:seon.gym/ok? false
       :seon.gym/error (str "scenario " id " is :paid tier — costs "
                            "real money. Pass {:seon.gym/allow-paid? true} "
                            "with the active provider's API key set "
                            "(provider: " (name (ai/provider)) ") to run it.")}

      :else
      (let [prev-conn    db/*conn*
            prev-fs      @sfs-int/!config
            keys-before  (schema/current-keys)]
        ;; The gym's prompt-blob evidence (§6.6) IS debug capture — it
        ;; reads back the verbatim prompt the agent saw. run-turn!'s
        ;; capture is OFF by default (live pods don't want the disk
        ;; growth), so the gym forces it ON for the run and restores the
        ;; prior knob in `finally`. Without this the prompt-* predicates
        ;; would silently lose their eyes (a turn with no prompt-file).
        (debug/set-override! :on)
        (try
          (let [conn          (await (client/open-agent-conn!))
                _             (set! db/*conn* conn)
                compile-state (await (repl/ensure-bootstrap!))
                !agents       (atom {})
                !profiles     (atom [])]
            ;; The scratch store must be THE WORLD A POD BOOTS INTO, not an
            ;; empty void: seed the core (instruction rows + the
            ;; :seon.ns/:seon.fn program-graph rows that render the "What
            ;; you can do" teaching — without them agents never learn that
            ;; grep/register!/reply! exist) and mirror the pod's fs
            ;; capability (bin/seon runs the pod with SEON_FS_ROOT=$SEON_ROOT
            ;; SEON_FS_READ_ONLY=1) — MINUS test/: the gym's own scenario
            ;; EDNs and judge references live under test/seon/gym/**, and
            ;; the post-v4 sweep caught a paid agent grepping the judge's
            ;; reference text out of driver_test.cljs (the answer key) —
            ;; the filesystem variant of the §3.4 self-bait rule. src/ +
            ;; docs/ is the realistic research surface; the deliberate
            ;; parity divergence is documented here. Restored after the run.
            (let [cwd (.cwd js/process)]
              (sfs/configure! {:seon.agent.fs/allowed-roots
                               [(str cwd "/src") (str cwd "/docs")]
                               :seon.agent.fs/read-only?    true}))
            ;; Mint :a's id FIRST and run the boot seed inside its
            ;; with-agent scope — the seed txs carry the PRIMARY
            ;; agent's id alongside the :core-seed origin (the
            ;; live store's provenance shape, which the context-model
            ;; classifier keys on). :a's actual BOOT (create! +
            ;; creation evals) happens AFTER the seed + fixtures, so
            ;; its creation-turn `store-inventory`/instructions evals
            ;; see the seeded world — the live mint-onto-populated-
            ;; store order (a scenario's fixtures ARE prior state).
            ;; The scenario's prior-agent layer inside
            ;; [[seed-scenario-world!]] re-scopes itself to a synthetic
            ;; prior-agent id (nested with-agent — inner wins).
            (let [primary (db/new-id!)]
              (await
                (db/with-agent primary
                  (fn ^:async seed-and-sync! []
                    (await (seed-scenario-world! {:seon.gym/scenario scenario
                                                  :seon.db/conn conn}))
                    ;; World-parity: a live boot syncs the :seon.ai/config
                    ;; row from the SEON_AI_* env vars (start-agent! →
                    ;; ai/sync!; env OWNS the row). The gym never ran the
                    ;; sync, so env knobs (SEON_AI_TIMEOUT_MS, _MODEL,
                    ;; _THINKING) were silently DEAD in gym worlds while
                    ;; live pods honored them.
                    (await (ai/sync!)))))
              (await (eval-fixture-sources! compile-state fixture-sources))
              (await (ensure-agent! !agents compile-state :a primary)))
            ;; Drive every gym turn, strictly in order; each turn's agent
            ;; boots lazily on first use (multi-agent sequencing).
            (doseq [{:seon.gym.turn/keys [message llm-script agent]} turns]
              (let [agent-id (await (ensure-agent! !agents compile-state
                                                   (or agent :a)))
                    mid      (await (send-user-message! agent-id message))
                    ;; The waking message is the run's cause (the live
                    ;; wake-handler stamps the inbound datom's eid; the gym
                    ;; opens the run itself with the same message via its
                    ;; identity lookup-ref). The cause is what distinguishes
                    ;; a message-driven run from the bootstrap run (no cause)
                    ;; in the marker queries.
                    cause    [:seon.agent.message/id mid]]
                ;; Context telemetry against the PRE-TURN db value —
                ;; the message has landed, the turn hasn't run; the exact
                ;; db the turn's prompt renders from. Informational only.
                (swap! !profiles conj
                       (capture-turn-profile @conn agent-id (or agent :a)))
                (case (or llm (if (= :stub tier) :per-turn-script :paid))
                  :per-turn-script
                  (await (drive-stub-turns! agent-id compile-state cause
                                            llm-script))
                  :scripted-replay
                  (await (drive-loop! agent-id compile-state cause
                                      (replay-llm llm-script)))
                  :rejecting
                  (await (drive-loop! agent-id compile-state cause rejecting-llm))
                  :paid
                  (await (drive-loop! agent-id compile-state cause
                                      (paid-adapter))))))
            ;; Mechanical scoring against the post-run store + transcript;
            ;; judge predicates score on the SEPARATE judge axis.
            (let [agents      @!agents
                  dbv         @conn
                  primary     (or (get agents :a) (second (first agents)))
                  transcript  (->> (sort-by key agents)
                                   (map (fn [[_ aid]]
                                          (agent/transcript-block
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
                  results     (mapv #(eval-predicate dbv transcript agents %)
                                    mech-preds)
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
                          ;; context telemetry: one profile per driven
                          ;; turn, chronological — informational only.
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
            ;; Restore the root conn + fs capability config + debug-capture
            ;; knob + drop every schema key minted during the run (scenario
            ;; registrations AND agent-eval register!s) so one scenario can't
            ;; leak into the next (or into non-gym tests sharing the process).
            (set! db/*conn* prev-conn)
            (reset! sfs-int/!config prev-fs)
            (debug/set-override! :env)
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
