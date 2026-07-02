(ns seon.gym.driver
  "AGENT-GYM scenario harness — PRD §7 item 12 (the testing methodology).

   Scenarios are EDN DATA: a question (or several), optional fixtures
   (tx-data seeded before the run), and PASS-PREDICATES — datalog
   queries against the post-run store plus transcript checks — that a
   driver evaluates MECHANICALLY, plus optional LLM-JUDGE predicates
   (rubric + reference facts → graded verdict) recorded on a SEPARATE
   scorecard axis. Block-by-block context iteration becomes
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
    [seon.ai.diffusiongemma :as diffusiongemma]
    [seon.client :as client]
    [seon.agent.turn :as turn]
    [seon.agent.run :as run]
    [seon.agent.loop :as aloop]
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.namespaces :as ctx-ns]
    [seon.ai.tokens :as tokens]
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
    [seon.render :as render]
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
;; The two CURATION axes (context-curation Phase A) extend the §7
;; behavioral vocabulary: `:makes-few-errors` (eval-error-rate below a
;; threshold — fewer REPL mistakes) and `:drives-canvas` (the agent set
;; its OWN `:seon.render.live-tile/content` — it used the live tile as
;; the primary surface, not just messages). Both are measured every run
;; and surfaced on the scorecard; a scenario opts into asserting them
;; via the `:eval-error-rate` / `:canvas-updated` predicate kinds.
(schema/register! :seon.gym.axis/name
  [:enum :sees-question :searches-first :models-work-directed
   :reuses-schemas :consults-findings :reuses-functions
   :writes-tests :replies-honestly :terminates :stores-proactively
   :makes-few-errors :drives-canvas])

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
;; :eval-error-rate — rows = the (optionally agent-scoped) RUN-DRIVEN
;;   evals; pass iff the FAILED-eval fraction (:seon.eval/ok? false ÷
;;   total) is ≤ :seon.gym.predicate/max-error-rate. The instrument for
;;   the curation goal "agents make few REPL errors" (issue #44: the
;;   segmenter records orphan-delimiter + empty-span evals as ok? false
;;   too, so this also catches malformed-form noise). Zero evals = 0.0
;;   (no errors), passes any threshold.
;; :canvas-updated — pass iff the (optionally agent-scoped, default :a)
;;   agent drove its OWN canvas: :seon.render.live-tile/content is
;;   present on [:seon.agent/id <agent>] in the post-run store. The
;;   instrument for "agents drive the live tile as the primary surface,
;;   messages only a backup".
(schema/register! :seon.gym.predicate/kind
  [:enum :datalog :transcript-includes :transcript-excludes
   :first-eval-matches :eval-count-matching :domain-attrs
   :prompt-includes :prompt-excludes :prompt-every-turn :llm-judge
   :eval-error-rate :canvas-updated])
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
;; Canonical eval-error-rate SHAPE — a fraction in [0,1] (failed evals ÷
;; total). Registered once, referenced by the predicate threshold field
;; AND the scorecard slot (shared-shape rule).
(schema/register! :seon.gym/eval-error-rate [:double {:min 0.0 :max 1.0}])
;; :eval-error-rate predicate threshold — pass iff the run's
;; eval-error-rate is ≤ this. References the canonical shape.
(schema/register! :seon.gym.predicate/max-error-rate :seon.gym/eval-error-rate)
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
   [:seon.gym.predicate/max-error-rate {:optional true}
    :seon.gym.predicate/max-error-rate]
   [:seon.gym.predicate/rubric    {:optional true} :seon.gym.predicate/rubric]
   [:seon.gym.predicate/reference {:optional true} :seon.gym.predicate/reference]])

;; --- scenario -----------------------------------------------------------------
(schema/register! :seon.gym.scenario/id     :keyword)
(schema/register! :seon.gym.scenario/doc    :string)
(schema/register! :seon.gym.scenario/tier   [:enum :stub :paid])
(schema/register! :seon.gym.scenario/status [:enum :active :todo])
(schema/register! :seon.gym.scenario/axes   [:vector :seon.gym.axis/name])
;; The CAPABILITY this scenario exercises (context-curation Phase A) —
;; the grouping the curation battery runs by. One competency per
;; scenario; the rubric `axes` are the fine-grained behaviors WITHIN it.
;;   :planning       — multi-step work that must survive interruption.
;;   :db-memory      — store-then-retrieve schema'd facts across turns.
;;   :error-recovery — recover from a failed eval without forking shapes.
;;   :honesty        — refuse / admit-don't-know rather than fabricate.
;;   :over-retrieval — answer a NARROW question without dumping the store.
;;   :ui             — drive the live canvas/tile as the PRIMARY surface
;;                     (present richly, not recite in prose).
(schema/register! :seon.gym.scenario/competency
  [:enum :planning :db-memory :error-recovery :honesty :over-retrieval :ui])
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
   [:seon.gym.scenario/competency :seon.gym.scenario/competency]
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
;; --- curation axes (context-curation Phase A), measured EVERY run -----------
;; The whole-run eval-error-rate (failed RUN-DRIVEN evals ÷ total; 0.0
;; when no eval ran) — the "agents make few REPL errors" instrument.
;; Informational telemetry plus the assertion surface for the
;; `:eval-error-rate` predicate; references the canonical rate shape.
(schema/register! :seon.gym.scorecard/eval-error-rate :seon.gym/eval-error-rate)
;; Did the PRIMARY agent (:a) drive its own canvas this run — i.e. set
;; :seon.render.live-tile/content on its own entity? The "agents drive
;; the live tile as the primary surface" instrument.
(schema/register! :seon.gym.scorecard/canvas-updated? :boolean)
;; Toolkit-ADOPTION signal (the axis that would've caught #42): per `my.*`
;; toolkit namespace, how many times the run-driven evals REFERENCE it
;; (`my.data/`, `my.ui/`, `my.tile/`) — did the agent CALL the taught
;; toolkit, or hand-roll the equivalent footgun path? A SIGNAL, never a
;; gate: a context change that drops these toward 0 is a render-prominence
;; regression (the #42 namespaces signature-trim regressed my.data adoption
;; to 0×, which the confounded total-tokens axis missed). Reads eval SOURCE
;; only (anti-cheat: never an answer). FREE-mode-blind — stub scripts don't
;; call tools, so it fills on --paid drives.
(schema/register! :seon.gym/toolkit-ns [:enum :my.data :my.ui :my.tile])
(schema/register! :seon.gym.scorecard/toolkit-calls
  [:map-of :seon.gym/toolkit-ns [:int {:min 0}]])
;; Per-turn prompt-blob evidence (gym-upgrade PRD §6.6, default-on):
;; every persisted prompt-file path for the run, chronological — a
;; moved number is diffable to the exact context bytes the agent saw.
(schema/register! :seon.gym.scorecard/prompt-files [:vector :string])
;; --- per-turn context telemetry (informational, NEVER gates pass?) ----------
;; Captured once per driven gym turn from the per-block render path's OWN
;; output against the PRE-TURN db value (user message landed, turn not
;; yet run — the exact db the turn's prompt renders from). Pure
;; evidence for "what context was loaded for this turn": context BLOCK
;; names in render order + per-block TOKEN estimates. This is the lever
;; the config-aware A/B reads — the resulting context SIZE (in tokens)
;; for the loadout the run booted under. The gym's job is testing the
;; AGENT — no layout predicate, no block-name coupling, nothing here
;; affects the scorecard verdict (user r2, 2026-06-11).
(schema/register! :seon.gym.profile/agent :seon.gym.turn/agent)
;; Context BLOCK names in render order (the non-blank contributions from
;; the per-block render path). Current ctx model: each block is a
;; `:seon.agent.ctx/block` named by `:seon.agent.ctx/name` (the renamed
;; section→block model); the profile carries a gym-local copy.
(schema/register! :seon.gym.profile/blocks [:vector :seon.agent.ctx/name])
;; [block-name rendered-token-estimate] in render order — only the
;; non-blank block contributions. TOKENS, never chars (the hard
;; size-reporting rule): `seon.ai.tokens/estimate` (chars/4).
(schema/register! :seon.gym.profile/block-tokens
  [:vector [:tuple :seon.agent.ctx/name :int]])
(schema/register! :seon.gym/turn-profile
  [:map
   [:seon.gym.profile/agent        :seon.gym.profile/agent]
   [:seon.gym.profile/blocks       :seon.gym.profile/blocks]
   [:seon.gym.profile/block-tokens :seon.gym.profile/block-tokens]])
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
   [:seon.gym.scorecard/eval-error-rate :seon.gym.scorecard/eval-error-rate]
   [:seon.gym.scorecard/canvas-updated? :seon.gym.scorecard/canvas-updated?]
   [:seon.gym.scorecard/toolkit-calls   :seon.gym.scorecard/toolkit-calls]
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
;; CONTEXT CONFIG the run boots agents under — the unified `seon.config`
;; seam. A run names a whole manifest FILE (`:path`, sets SEON_CONFIG); the
;; driver steers that env var around the run and the REAL seed paths already
;; read it — `seon.client/boot-seed!` (routes/skills) and `agent/create!` →
;; `ctx/seed-default-ctx!` → `config/resolve-agent-context` (the two-level
;; context loader). So the gym agents' `:seon.agent/ctx` is seeded FROM the
;; named manifest with zero duplicated resolution. Absent = today's full
;; default context (byte-identical no-config boot). The resulting context
;; SIZE lands in each turn-profile's `:seon.gym.profile/block-tokens`.
(schema/register! :seon.gym.config/path :string)
(schema/register! :seon.gym/config
  [:map
   [:seon.gym.config/path {:optional true} :seon.gym.config/path]])
(schema/register! :seon.gym/run-request
  [:map
   [:seon.gym/scenario :seon.gym/scenario]
   [:seon.gym/config {:optional true} :seon.gym/config]
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

;; --- ALIAS-BLIND PREDICATE GUARD --------------------------------------------
;; This bug class has bitten three times (my.tile e6aaf9f0; three seon.db/
;; store-read predicates fc557fbf): a predicate :pattern regexes a
;; FULLY-QUALIFIED `seon.<ns>/` name, but the agent's home ns aliases that
;; ns to a SHORT prefix (seon.db -> db, seon.agent.message -> message, …),
;; so the agent writes `(db/query …)` and the qualified-only pattern
;; false-negatives EVERY correct read — silently suppressing the true
;; pass-rate. A judge that can't see a correct answer isn't honest, so an
;; alias-blind pattern is a gym-integrity violation: it CRASHES the load,
;; exactly like the §3.4 self-bait guard, never scores silently.

(defn- re-escape-dots
  "A dotted ns name (\"seon.db\") as it literally appears regex-escaped
   inside a :pattern string (\"seon\\.db\") — each `.` becomes `\\.`. Built
   char-wise to avoid str/replace replacement-string ambiguity."
  [s]
  (str/join (map #(if (= % ".") "\\." %) s)))

(defn- dotted-prefix-esc
  "The regex-escaped leading PREFIX of a dotted ns name — everything up to
   and including the final dot (\"seon.db\" -> \"seon\\.\", \"my.tile\" ->
   \"my\\.\", \"my.plan\" -> \"my\\.\"). Used to build the
   per-ns optional-prefix idiom `(?:<prefix>)?<alias>` an alias-tolerant
   pattern may use — `(?:seon\\.)?db` for a seon verb, `(?:my\\.)?tile` for
   a toolkit verb — so the guard accepts the correct prefix for EITHER
   family rather than a hardcoded `seon\\.`."
  [dotted]
  (re-escape-dots (str (str/join "." (butlast (str/split dotted #"\."))) ".")))

(defn- home-ns-seon-aliases
  "Map of `seon.*` dotted-ns-name -> its short `:as` alias, DERIVED from
   seon.eval/home-ns-require-specs (the single source of truth for how
   every agent's home ns is wired). Only the `:as`-aliased `seon.*`
   namespaces — these are the ones an agent writes by the seeded SHORT
   alias (db/, message/, schema/, agent/, plan/) yet a predicate may regex
   by the long seon.<ns>/ form. `:refer`'d lifecycle verbs (wait, complete,
   …) carry no alias and aren't a qualified/alias split, so they're out of
   scope. Derives, never hardcodes — it can't drift from the agent's prompt."
  []
  (into {}
        (keep (fn [spec]
                (when (and (= :as (second spec))
                           (str/starts-with? (name (first spec)) "seon."))
                  [(name (first spec)) (name (nth spec 2))])))
        seval/home-ns-require-specs))

(defn- home-ns-toolkit-aliases
  "Map of `my.*` TOOLKIT dotted-ns-name -> its short alias, DERIVED from
   (seon.agent.ctx.namespaces/always-full-my-nses) (the my.* members of the
   resolved config `:seon.config/always` policy — the toolkit set the agent
   composes its canvas/memory from). The alias is the last dotted segment —
   the CONVENTION every agent writes the toolkit by (`my.tile` → `tile`,
   `my.ui` → `ui`, `my.data` → `data`, `my.kb` → `kb`), confirmed by the
   toolkit test nses' `(:require [my.tile :as tile] …)` heads. These are NOT in
   [[home-ns-seon-aliases]] (a DIFFERENT wiring than the seon.* verbs in
   home-ns-require-specs), yet the ORIGINAL alias-blind instance was a
   `my.tile/button` predicate — so without them the guard misses the whole
   toolkit-alias class. Derives from the policy, never hardcodes."
  []
  (into {}
        (map (fn [k]
               (let [dotted (name k)]
                 [dotted (last (str/split dotted #"\."))])))
        (ctx-ns/always-full-my-nses)))

(defn- home-ns-aliases
  "Map of every agent-home-ns dotted-ns-name -> its short alias, the UNION
   of the seon.* verbs ([[home-ns-seon-aliases]], from `home-ns-require-specs`)
   and the my.* toolkit ([[home-ns-toolkit-aliases]], from
   `canonical-full-my-ns`). One map of every dotted/alias pair an agent
   writes through a SHORT prefix — what [[alias-blind-predicate?]] scans
   against. Both halves derive from their source vars, so the guard can't
   drift from the agent's actual aliasing."
  []
  (merge (home-ns-seon-aliases) (home-ns-toolkit-aliases)))

(defn- qualified-fn-ref?
  "True when `pattern` references `dotted` (its regex-escaped form `esc`) as
   a FN-CALL alias target — `<esc>/` or `<esc>\\b` — at a position that is
   NOT part of a namespaced KEYWORD (`:<esc>/…`). A namespaced keyword like
   `:my.tile/action` is an un-aliasable DATA KEY (the tile map's key), never
   a `tile/action` alias call, so a `:my\\.tile/(action|submit)` data-key
   predicate must NOT read as an alias-blind `my.tile/button` fn call. We
   strip the `:<esc>` keyword occurrences first, then test the remainder for
   the bare fn-call form."
  [pattern esc]
  (let [no-kw (str/replace pattern (str ":" esc) "")]
    (or (str/includes? no-kw (str esc "/"))
        (str/includes? no-kw (str esc "\\b")))))

(defn- alias-blind-predicate?
  "nil if `pred`'s :pattern is alias-safe, else a violation map naming the
   aliased ns whose FULLY-QUALIFIED `<long-ns>/` FN-CALL form the pattern
   regexes WITHOUT also accepting the short alias the agent actually writes.

   For each aliased ns ([[home-ns-aliases]] — the seon.* verbs db/message/
   schema/agent/plan AND the my.* toolkit tile/ui/data/kb): if the pattern
   references the qualified FN-CALL form `<long>\\<ns>/` (or `<long>\\b`) —
   [[qualified-fn-ref?]], which ignores un-aliasable `:<long>/…` keyword
   data-keys — it points at the long name directly; that is alias-BLIND
   unless the pattern ALSO accepts the short alias via one of the sanctioned
   idioms — a `(?:<prefix>)?<alias>` optional prefix (`(?:seon\\.)?db`,
   `(?:my\\.)?tile`), or a `\\b<alias>/`/`|<alias>/`/`(<alias>/`/leading
   `<alias>/` alternative.

   No false positives: a fully-qualified pattern for a ns that is NOT
   aliased (seon.agent.search/grep, seon.agent.fs/read-file — agents write
   those qualified) is CORRECT, as is a namespaced-keyword data-key
   (`:my.tile/action`). The qualified test demands a `/` or `\\b` right
   after `<ns>` and discounts keyword occurrences, so `seon\\.agent\\.search/`
   never trips the `seon.agent` alias and `:my\\.tile/action` never trips
   the `my.tile` alias."
  [{:seon.gym.predicate/keys [id pattern]}]
  (when pattern
    (some (fn [[dotted alias]]
            (let [esc        (re-escape-dots dotted)
                  qualified? (qualified-fn-ref? pattern esc)
                  alias-ok?  (or (str/includes? pattern (str "(?:" (dotted-prefix-esc dotted) ")?" alias))
                                 (str/includes? pattern (str "\\b" alias "/"))
                                 (str/includes? pattern (str "|" alias "/"))
                                 (str/includes? pattern (str "(" alias "/"))
                                 (str/starts-with? pattern (str alias "/")))]
              (when (and qualified? (not alias-ok?))
                {:seon.gym.predicate/id      id
                 :seon.gym/alias-blind-ns    dotted
                 :seon.gym/alias-blind-alias alias
                 :seon.gym.predicate/pattern pattern})))
          (home-ns-aliases))))

(defn- check-alias-blind!
  "Crash the load if any of a scenario's predicates is
   [[alias-blind-predicate?]] — a qualified `<long-ns>/` FN-CALL pattern
   (seon.* verb OR my.* toolkit) that ignores the short alias the agent
   actually writes, which would false-negative every correct read and
   silently suppress the pass-rate. Loud failure naming the scenario,
   predicate, ns + alias, and the fix (accept the alias: the ns's own
   `(?:<prefix>)?<alias>` optional prefix or `\\b<alias>/`)."
  [path {:seon.gym.scenario/keys [id predicates]}]
  (doseq [pred predicates
          :let [v (alias-blind-predicate? pred)]
          :when v]
    (throw (ex-info (str "gym: ALIAS-BLIND PREDICATE — scenario " id
                         " in " path ": predicate "
                         (:seon.gym.predicate/id v) " regexes the qualified "
                         (:seon.gym/alias-blind-ns v) "/ but the agent's home "
                         "ns aliases it to " (:seon.gym/alias-blind-alias v)
                         "/ — the pattern false-negatives every correct read. "
                         "Accept the alias too: (?:"
                         (dotted-prefix-esc (:seon.gym/alias-blind-ns v)) ")?"
                         (:seon.gym/alias-blind-alias v) " or \\b"
                         (:seon.gym/alias-blind-alias v) "/.")
                    (assoc v :seon.gym/path path :seon.gym.scenario/id id)))))

(defn load-scenarios!
  "Read one scenario EDN file (a single scenario map OR a vector of
   them) and validate every scenario against `:seon.gym/scenario`.
   Invalid EDN fails LOUD with the Malli explain — a scenario that
   doesn't parse must never silently score. Also enforces the §3.4
   self-bait rule ([[check-self-bait!]]) and the alias-blind-predicate
   rule ([[check-alias-blind!]]) at load time."
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
      (check-self-bait! path s)
      (check-alias-blind! path s))
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

   The cause clause scopes to message-driven runs: every message-driven run
   the gym opens carries the waking message as its `:seon.agent.run/cause`.
   Eval-shaped predicates measure the agent's behavior IN RESPONSE TO
   MESSAGES. (Historically this also excluded a boot greeting turn's evals;
   that turn no longer exists — a minted agent has ZERO runs until its first
   message — so the cause clause is now vacuous-but-correct: every run is
   message-caused.)"
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

(defn- run-eval-oks
  "The `:seon.eval/ok?` boolean of every RUN-DRIVEN eval (caused-run
   scoping, same boundary as [[eval-at+source]] — excludes the bootstrap
   turn's tutorial evals), optionally scoped to one agent. The raw rows
   the eval-error-rate is computed from (issue #44: the segmenter records
   orphan-delimiter + empty-span evals as ok? false, so they count too)."
  [dbv agent-id]
  (->> (if agent-id
         (db/query {:seon.db/query '[:find ?ev ?ok
                                     :in $ ?aid
                                     :where
                                     [?ag :seon.agent/id ?aid]
                                     [?r :seon.agent.run/agent ?ag]
                                     [?r :seon.agent.run/cause _]
                                     [?t :seon.agent.turn/run ?r]
                                     [?t :seon.agent.turn/evals ?ev]
                                     [?ev :seon.eval/ok? ?ok]]
                    :seon.db/args [agent-id]
                    :seon.db/db   dbv})
         (db/query {:seon.db/query '[:find ?ev ?ok
                                     :where
                                     [?r :seon.agent.run/cause _]
                                     [?t :seon.agent.turn/run ?r]
                                     [?t :seon.agent.turn/evals ?ev]
                                     [?ev :seon.eval/ok? ?ok]]
                    :seon.db/db dbv}))
       (mapv second)))

(defn- eval-error-rate*
  "Fraction of RUN-DRIVEN evals that FAILED (`:seon.eval/ok?` false) ÷
   total — the curation eval-error-rate (`:seon.gym/eval-error-rate`
   shape). 0.0 when no eval ran (no errors). agent-id nil = whole store."
  [dbv agent-id]
  (let [oks (run-eval-oks dbv agent-id)
        n   (count oks)]
    (if (zero? n)
      0.0
      (/ (count (remove identity oks)) n))))

(def ^:private toolkit-nses
  "The `my.*` toolkit namespaces the toolkit-adoption signal watches."
  [:my.data :my.ui :my.tile])

(defn- count-substring
  "How many (non-overlapping) times `sub` occurs in `s`."
  [s sub]
  (loop [from 0 n 0]
    (if-let [i (str/index-of s sub from)]
      (recur (+ i (count sub)) (inc n))
      n)))

(defn- toolkit-calls*
  "Per `my.*` toolkit namespace, how many times the RUN-DRIVEN evals
   REFERENCE it (`my.data/`, `my.ui/`, `my.tile/`) — the toolkit-ADOPTION
   signal (did the agent CALL the taught toolkit, or hand-roll it?). Reads
   eval SOURCE only (same caused-run scoping as [[eval-at+source]];
   anti-cheat: never an answer). Always returns all three keys (0 when the
   ns is unreferenced — e.g. every stub run, since scripts don't call
   tools). agent-id nil = whole store."
  [dbv agent-id]
  (let [joined (str/join "\n" (mapv second (eval-at+source dbv agent-id)))]
    (into {} (map (fn [tk] [tk (count-substring joined (str (name tk) "/"))]))
          toolkit-nses)))

(defn- agent-canvas-updated?
  "Did the agent drive its OWN canvas — i.e. is
   `:seon.render.live-tile/content` present on `[:seon.agent/id agent-id]`
   in the post-run store? (Fresh gym agents — designators :a, :b — start
   with the attr ABSENT; only the live 'root' agent is seeded a default
   tile, and the gym never boots root.)"
  [dbv agent-id]
  (boolean
    (and agent-id
         (some? (:seon.render.live-tile/content
                  (db/entity {:seon.db/ref [:seon.agent/id agent-id]
                              :seon.db/db  dbv}))))))

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
                              text pattern agent turn max-error-rate]}]
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

            ;; Curation: eval-error-rate ≤ threshold (issue #44 noise
            ;; counts as error). nil agent = whole store.
            :eval-error-rate
            (let [rate (eval-error-rate* dbv agent-id)]
              [(<= rate max-error-rate)
               (str (when agent (str "agent " agent " "))
                    "eval-error-rate=" rate
                    " max=" max-error-rate)])

            ;; Curation: did the agent (default :a) drive its own canvas?
            :canvas-updated
            (let [aid (or agent-id (get agents :a))]
              [(agent-canvas-updated? dbv aid)
               (str "agent " (or agent :a)
                    " :seon.render.live-tile/content "
                    (if (agent-canvas-updated? dbv aid)
                      "PRESENT (canvas driven)"
                      "ABSENT (canvas not driven)"))])

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
;; Per-turn context telemetry — INFORMATIONAL ONLY. One render of the
;; per-block context path against the pre-turn db value records what
;; context was loaded for the turn (BLOCK names + TOKEN estimates). It
;; never gates pass?: the gym tests the agent, not the layout (user
;; r2, 2026-06-11 — the former structural gates broke on every context
;; change and were removed).
;; ===========================================================================

(defn- capture-turn-profile
  "One `:seon.gym/turn-profile` for the turn about to run — context BLOCK
   names in render order + per-block TOKEN estimates (the hard
   size-reporting rule: tokens via `seon.ai.tokens/estimate`, never
   chars) from [[ctx/ctx-sections]] (the SAME per-block render path the
   prompt and the inspector take) against the pre-turn db value."
  [dbv agent-id designator]
  (let [texts (:seon.render/section-texts
                (ctx/ctx-sections {:seon.db/db dbv
                                   :seon.agent/id agent-id}))]
    {:seon.gym.profile/agent  designator
     :seon.gym.profile/blocks (mapv :seon.agent.ctx/name texts)
     :seon.gym.profile/block-tokens
     (mapv (fn [{nm :seon.agent.ctx/name txt :seon.render/text}]
             [nm (tokens/estimate txt)])
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
   reply'. A reply is one whose `:seon.agent.message/at` is at or after the
   earliest user→agent question. (Historically this also excluded a boot
   greeting the minted agent sent before any question landed; that turn no
   longer exists — a minted agent is silent until its first message — so the
   at-or-after guard is now vacuous-but-correct.)

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

(defn- agent-canvas-ai
  "The agent's resolved live-tile `:seon.render/ai` twin — what the human
   actually SEES on the canvas — so a canvas-content judge grades the
   RENDERED tile, not just the reply prose. Goes through the public
   `seon.render/render-agent-tile` path (the ONE tile entry point the
   inspector + the live-tile awareness block use), NEVER render-engine
   internals. Falls back to the error message + twin when the renderer
   threw, the hiccup pr-str when a tile carries no ai twin, and the empty
   string when nothing resolves (no canvas → nothing to append)."
  [dbv agent-id]
  (let [{:seon.render/keys [ai error hiccup]}
        (render/render-agent-tile {:seon.agent/id agent-id :seon.db/db dbv})]
    (cond
      (some? error) (str "⚠ canvas renderer threw: " (:seon.error/message error)
                         (when (some? ai) (str "\n" ai)))
      (some? ai)     (str ai)
      (some? hiccup) (pr-str hiccup)
      :else          "")))

(defn- judge-ctx
  "Assemble the grading context for one :llm-judge predicate: the
   designated agent's question(s), its verbatim reply, its RENDERED
   CANVAS (the resolved live-tile `:seon.render/ai` twin — so a
   canvas-content judge grades what the human actually sees, not the
   reply prose alone), the rubric, the reference facts."
  [turns agents dbv {:seon.gym.predicate/keys [agent rubric reference]}]
  (let [designator (or agent :a)
        agent-id   (get agents designator)
        questions  (->> turns
                        (filter #(= designator
                                    (or (:seon.gym.turn/agent %) :a)))
                        (map :seon.gym.turn/message))
        reply      (if agent-id
                     (agent-reply-text dbv agent-id)
                     "(no such agent ran in this scenario)")
        canvas     (when agent-id (agent-canvas-ai dbv agent-id))
        reply+     (if (seq canvas)
                     (str reply
                          "\n\n== Agent's live canvas (the rendered tile the "
                          "human SEES) ==\n" canvas)
                     reply)]
    (format-judge-ctx questions reply+ rubric reference)))

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
                     :diffusiongemma (case (ai/dg-backend)
                                       :control (diffusiongemma/agent-adapter)
                                       (openai/agent-adapter))
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

   Boot parity: a minted agent is `:idle` with ZERO runs the moment its
   entity + home ns exist — no turn 0 (the boot greeting turn was removed;
   `seon.client/init-agent!` is the ONE deterministic init, no ceremony).
   It wakes on the first message like a live minted agent.

   `pre-id` (optional) pins the minted agent's id — run-scenario!
   mints :a's id BEFORE seeding so the seed txs can carry it, then
   boots :a here AFTER the seed (the live mint-onto-populated-store
   order, so :a's creation evals see the seeded world)."
  [!agents compile-state designator & [pre-id]]
  (if-let [existing (get @!agents designator)]
    existing
    (let [agent-id (or pre-id (db/new-id!))]
      ;; with-agent scope mirrors seon.client/init-agent! — on a
      ;; live boot the agent's own create! tx carries its agent-id. The gym
      ;; wires the home ns itself (setup-agent-ns!) + creates the entity; no
      ;; turn 0 (the boot greeting turn was removed — a minted agent is
      ;; :idle with zero runs the instant its entity + ns exist).
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
      this OUTSIDE any agent scope, like the live boot does — a
      `:core-seed` claim inside an agent scope trips the
      origin-forge guard. The test roster
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

(defn- env-get [k] (aget (.-env js/process) k))

(defn- env-set!
  "Set (or, on nil, delete) a `process.env` key — the seam the gym uses to
   steer `seon.config`'s `SEON_CONFIG` read for a run."
  [k v]
  (if (nil? v)
    (js-delete (.-env js/process) k)
    (aset (.-env js/process) k v)))

(defn- apply-run-config!
  "Steer the `seon.config` env for the run from a `:seon.gym/config` map
   (`:path` → SEON_CONFIG — a whole manifest file). Returns the prior
   SEON_CONFIG value so `finally` can restore it. nil config → no-op (today's
   full default context)."
  [config]
  (let [prev (env-get "SEON_CONFIG")]
    (when-let [path (:seon.gym.config/path config)]
      (env-set! "SEON_CONFIG" path))
    prev))

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
    config      :seon.gym/config
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
            ;; FULL registry snapshot — not just the key SET. Restoring the
            ;; whole map in `finally` reverts both keys the run MINTED *and*
            ;; pre-existing keys whose VALUE a scenario re-registered (a
            ;; scenario narrowing :seon.agent.message/to poisoned every later
            ;; paid scenario at message-land time — the key-diff reap missed
            ;; the mutated value). Strictly more correct than the diff, and
            ;; simpler.
            schemas-before @schema/*schemas
            ;; UNIFIED config seam: steer SEON_CONFIG before the seed + agent
            ;; boot so boot-seed!'s manifest read and create!'s
            ;; resolve-agent-context pick up the run's chosen manifest.
            ;; nil config → no-op. Restored in finally.
            prev-env     (apply-run-config! config)]
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
            ;; Run the boot seed OUTSIDE any agent scope — the live
            ;; store's provenance shape: `start-agent!` seeds before
            ;; entering its with-agent scope, because a `:core-seed`
            ;; origin claim from inside an agent scope is what the
            ;; origin-forge guard warns on. :a's actual BOOT (create! +
            ;; creation evals) happens AFTER the seed + fixtures, so
            ;; its creation-turn `store-inventory`/instructions evals
            ;; see the seeded world — the live mint-onto-populated-
            ;; store order (a scenario's fixtures ARE prior state).
            ;; The scenario's prior-agent layer inside
            ;; [[seed-scenario-world!]] scopes itself to a synthetic
            ;; prior-agent id (its own with-agent).
            (let [primary (db/new-id!)]
              (await (seed-scenario-world! {:seon.gym/scenario scenario
                                            :seon.db/conn conn}))
              ;; World-parity: a live boot syncs the :seon.ai/config
              ;; row from the SEON_AI_* env vars (start-agent! →
              ;; ai/sync!; env OWNS the row) inside its agent scope.
              ;; The gym never ran the sync, so env knobs
              ;; (SEON_AI_TIMEOUT_MS, _MODEL, _THINKING) were silently
              ;; DEAD in gym worlds while live pods honored them.
              (await
                (db/with-agent primary
                  (fn ^:async sync-ai! []
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
                          ;; curation axes — measured every run (whole
                          ;; store for the rate; primary agent :a for the
                          ;; canvas), independent of whether a predicate
                          ;; asserts them.
                          :seon.gym.scorecard/eval-error-rate
                          (eval-error-rate* dbv nil)
                          :seon.gym.scorecard/canvas-updated?
                          (agent-canvas-updated? dbv primary)
                          ;; toolkit-adoption signal — whole-store eval
                          ;; source scan (the axis that would've caught
                          ;; #42). FREE-mode-blind; fills on --paid.
                          :seon.gym.scorecard/toolkit-calls
                          (toolkit-calls* dbv nil)
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
            ;; knob + the FULL schema registry snapshot so one scenario can't
            ;; leak into the next (or into non-gym tests sharing the process).
            ;; Resetting to the captured map reverts BOTH keys the run minted
            ;; (scenario registrations AND agent-eval register!s) AND any
            ;; pre-existing key whose value a scenario re-registered — the
            ;; latter is what a key-only diff-reap silently left mutated.
            (set! db/*conn* prev-conn)
            (reset! sfs-int/!config prev-fs)
            (debug/set-override! :env)
            (env-set! "SEON_CONFIG" prev-env)
            (reset! schema/*schemas schemas-before)))))))

;; ===========================================================================
;; Context-size MEASUREMENT — the context-improvement loop's free probe.
;; Seeds the scenario world under a chosen config + boots agent :a + lands
;; the first turn's user message, then captures the PRE-TURN context
;; profile WITHOUT driving the LLM. Free for ANY tier (paid/todo
;; included): it never calls a provider — it measures the context an agent
;; WOULD see for turn 1, through the SAME seed + `seed-default-ctx!` →
;; `resolve-agent-context` path a real run uses, so the token numbers are real.
;; This is what makes pass-rate-vs-context systematic: the SIZE axis is
;; measurable for every scenario for free; pass/fail needs the paid drive.
;; ===========================================================================

(schema/register! :seon.gym/total-tokens :int)
(schema/register! :seon.gym/measure-request
  [:map
   [:seon.gym/scenario :seon.gym/scenario]
   [:seon.gym/config {:optional true} :seon.gym/config]])
(schema/register! :seon.gym/measure-response
  [:map
   [:seon.gym.scorecard/scenario :seon.gym.scorecard/scenario]
   [:seon.gym/total-tokens        :seon.gym/total-tokens]
   [:seon.gym/turn-profile        :seon.gym/turn-profile]])

(defn ^:async measure-context!
  "Measure the fresh-agent turn-1 context SIZE for a scenario under the
   given `:seon.gym/config` (nil = today's full default), WITHOUT spending
   on the LLM. Same isolation + seed path as [[run-scenario!]] (scratch
   `:memory` conn, root `*conn*` swap restored in finally, schema keys
   reaped, SEON_CONFIG steered + restored). Returns
   Promise<:seon.gym/measure-response> — the scenario id, the per-block
   token estimates (`:seon.gym/turn-profile`), and the summed
   `:seon.gym/total-tokens`."
  {:malli/schema [:=> [:cat :seon.gym/measure-request] :seon.gym/measure-response]}
  [{scenario :seon.gym/scenario config :seon.gym/config}]
  (let [{:seon.gym.scenario/keys [id fixture-sources turns]} scenario
        prev-conn   db/*conn*
        prev-fs     @sfs-int/!config
        keys-before (schema/current-keys)
        prev-env    (apply-run-config! config)]
    (try
      (let [conn          (await (client/open-agent-conn!))
            _             (set! db/*conn* conn)
            compile-state (await (repl/ensure-bootstrap!))
            !agents       (atom {})
            cwd           (.cwd js/process)]
        (sfs/configure! {:seon.agent.fs/allowed-roots
                         [(str cwd "/src") (str cwd "/docs")]
                         :seon.agent.fs/read-only? true})
        (let [primary (db/new-id!)]
          ;; Seed outside any agent scope (live provenance shape — see
          ;; the origin-forge note at the run-scenario! seed site).
          (await (seed-scenario-world! {:seon.gym/scenario scenario
                                        :seon.db/conn conn}))
          (await
            (db/with-agent primary
              (fn ^:async sync-ai! []
                (await (ai/sync!)))))
          (await (eval-fixture-sources! compile-state fixture-sources))
          (await (ensure-agent! !agents compile-state :a primary)))
        (let [designator (or (:seon.gym.turn/agent (first turns)) :a)
              agent-id    (await (ensure-agent! !agents compile-state designator))]
          ;; land the first turn's message so reactive blocks render the
          ;; same pre-turn db a real turn 1 would (the message has landed,
          ;; the turn hasn't run) — but never drive the LLM.
          (when-let [msg (:seon.gym.turn/message (first turns))]
            (await (send-user-message! agent-id msg)))
          (let [profile (capture-turn-profile @conn agent-id designator)]
            {:seon.gym.scorecard/scenario id
             :seon.gym/turn-profile       profile
             :seon.gym/total-tokens
             (reduce + 0 (map second (:seon.gym.profile/block-tokens profile)))})))
      (finally
        (set! db/*conn* prev-conn)
        (reset! sfs-int/!config prev-fs)
        (env-set! "SEON_CONFIG" prev-env)
        (let [minted (remove keys-before (schema/current-keys))]
          (when (seq minted)
            (swap! schema/*schemas #(apply dissoc % minted))))))))

(defn print-scorecard!
  "Print the scorecard as one greppable line (`bin/gym` surfaces these
   from the suite output) and return it unchanged."
  {:malli/schema [:=> [:cat :seon.gym/scorecard] :seon.gym/scorecard]}
  [card]
  (println "SEON-GYM SCORECARD" (pr-str card))
  card)

;; ===========================================================================
;; Competency battery — run every scenario tagged with one
;; `:seon.gym.scenario/competency`, in order (run-scenario! swaps the
;; root *conn*, so the runs MUST be sequential, never parallel). The
;; curation loop's grouping lever: "how does the planning competency
;; move when I drop a context block?" Each entry is a scorecard OR a
;; refusal (errors are values — :paid/:todo scenarios refuse without
;; spend), so a battery is always safe to run free.
;; ===========================================================================

(schema/register! :seon.gym/competency :seon.gym.scenario/competency)
(schema/register! :seon.gym/battery-request
  [:map
   [:seon.gym/scenarios   :seon.gym/scenarios]
   [:seon.gym/competency  :seon.gym/competency]
   [:seon.gym/config      {:optional true} :seon.gym/config]
   [:seon.gym/allow-paid? {:optional true} :seon.gym/allow-paid?]
   [:seon.gym/judge-fn    {:optional true} :seon.gym/judge-fn]])
(schema/register! :seon.gym/battery-response [:vector :seon.gym/run-response])

(defn ^:async run-competency-battery!
  "Run every scenario in `:seon.gym/scenarios` tagged with the given
   `:seon.gym/competency`, strictly in order, under one `:seon.gym/config`
   loadout, and return the vector of scorecards/refusals. Sequential by
   construction — run-scenario! swaps the root `seon.db/*conn*`, so two
   runs must never overlap. `:paid`/`:todo` members refuse (no spend)
   unless `:seon.gym/allow-paid?` + the active key make them runnable."
  {:malli/schema [:=> [:cat :seon.gym/battery-request] :seon.gym/battery-response]}
  [{scenarios   :seon.gym/scenarios
    competency  :seon.gym/competency
    config      :seon.gym/config
    allow-paid? :seon.gym/allow-paid?
    judge-fn    :seon.gym/judge-fn}]
  (let [matching (filterv #(= competency (:seon.gym.scenario/competency %))
                          scenarios)]
    (loop [ss  matching
           acc []]
      (if-let [[s & more] (seq ss)]
        (let [card (await (run-scenario!
                            (cond-> {:seon.gym/scenario s}
                              config      (assoc :seon.gym/config config)
                              (some? allow-paid?)
                              (assoc :seon.gym/allow-paid? allow-paid?)
                              judge-fn    (assoc :seon.gym/judge-fn judge-fn))))]
          (recur more (conj acc card)))
        acc))))
