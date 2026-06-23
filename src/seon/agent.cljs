(ns seon.agent
  "Agent runtime — schemas, ctx-rendering, and turn-loop lifecycle.
   This is the single namespace that owns 'what an agent is and how it
   runs.' There is no separate seon.agent.session — the agent IS the unit.

   The agent operates as a real REPL: bootstrap-CLJS evaluates its
   forms, results land in a per-agent home namespace (`my.agent.<id>`)
   as live values keyed by eval-id (on globalThis, via [[seon.eval]]),
   and durable records land as `:seon.eval` entities in the database.
   The agent calls the real `seon.db/*` APIs directly — no
   `say!`/`done!`/`scratch!` wrappers.

   This namespace owns:
     - the `:seon.agent/*`, `:seon.agent.session/*`, `:seon.agent.turn/*`,
       `:seon.eval/*`, `:seon.ns/*`, `:seon.fn/*`, `:seon.schema/*`
       schemas (`:seon.agent.message/*` lives in [[seon.agent.message]],
       `:seon.ctx/*` in [[seon.ctx]])
     - `run-turn!`          — one full turn end-to-end (v1.md §6.1).
                              Thin orchestrator: composes
                              `ensure-session!` + `render-prompt` +
                              `open-turn!` + `ask-and-eval!` under one
                              outer `seon.db/with-tx-context` scope
                              so every tx auto-tags with the causality
                              bundle
     - `open-turn!`         — bracketing combinator (formerly
                              `with-turn!`): opens a turn with
                              prompt-text + the agent's current
                              `:seon.agent/wake` stamped on it, runs a
                              body thunk, folds its result into the
                              close-tx via `close-turn!` (so one open-tx
                              + one close-tx covers the whole turn-level
                              write surface; eval batch adds its own
                              per-form txs)
     - `ask-and-eval!`      — body of `open-turn!`: LLM call + parse
                              + eval-batch; returns the assistant msg
                              and `:seon.agent/eval-count` for the
                              loop's stop policy
     - `render-prompt`      — sync; resolve `:seon.render/ai` slot
                              (defaults to `seon.agent/assemble-context`)
                              and call the composer
     - `assemble-context` + 6 default section fns — v1.md §5.2/§5.3
     - `run-agentic-loop!`  — multi-turn driver, stop policies (v1 §6.2)
     - `install-user-trigger!` — register the tx-listener that wakes
                              `run-agentic-loop!` on a new INBOUND
                              message (to ∋ me AND from ≠ me)
     - `turns-cap`          — read :seon.agent/turns-cap or fallback
                              to `default-turns-cap`
     - `current-session` / `ensure-session!` / `start-session!`
     - `create!`            — allocate an agent entity, init state
     - `message!` / `reply!` — re-exported from [[seon.agent.message]]
                              (the message-model home: from/to refs,
                              hops derivation, blank-content guard)
     - `boot!`              — wire everything: create entity + install
                              inbound-message trigger + install core
                              default `:seon.ctx` layout
     - `reset-ctx!` / `update-ctx!` / `ctx-entities` — agent's ctx-layout
       editing surface
     - `warnings-section`   — clustered warnings via the `seon.warn`
       check registry (compositional; ns-scoped by default)

   Agent-id resolution: every read API takes `:seon.agent/id` and falls
   back to `(seon.db/current-agent-id)` when unset. Callers running
   inside `(seon.db/with-agent id …)` (the normal boot/run path) need
   not pass it. REPL callers from outside any scope must pass it
   explicitly — the helpers throw a clear ex-info rather than guessing.

   ## State machine

   `:seon.agent/state` values (agent-fsm redesign 2026-06-23):
     :idle       — no loop running; wakeable → :active
     :active     — a loop is running; new inbounds are picked up by the
                   running loop's sliding cap, not a fresh wake (handler
                   sees :active and skips)
     :waiting    — parked via (agent/wait …); wakeable → :active
     :completed  — finished via (complete …) / no-forms; wakeable → :active
     :terminated — orchestrator kill; UNWAKEABLE (change state first)

   The handler flips :idle → :active before starting a loop, and back to
   :idle when the loop ends. Concurrent kicks during a loop no-op — the
   running loop's sliding cap picks up any messages that landed during it.

   ## Prompt assembly

   v1.md §5 — the LLM ctx is built via the render dispatch:

     agent entity → :seon.render/ai slot → eval/lookup-value → call → text

   Default symbol: `'seon.agent/assemble-context` (a transitional alias
   of `seon.ctx/assemble-context` — the ONE composer, V3-C). The
   core section LAYOUT is CODE (`seon.ctx/core-default-ctx`);
   the agent's own `:seon.agent/ctx` section maps MERGE with it by one
   priority sort (override-by-name). Each section's `:seon.render/ai`
   slot is a verbatim string or a fn symbol resolved late via
   `seon.eval/lookup-value`.

   Core defaults (`core-default-ctx`): nine sections —
   `system`, `capabilities`, `exemplars`, `schema-catalog`,
   `functions-catalog`, `namespace-context`, `warnings`, `transcript`,
   `prompt`.
   The agent customizes by transacting different
   `:seon.ctx` entities into `:seon.agent/ctx` (use `update-ctx!`)
   or by transacting a completely different symbol onto the agent's
   `:seon.render/ai` slot."
  (:require
    [clojure.string :as str]
    [seon.agent.message :as msg]
    [seon.ai :as ai]
    [seon.ctx :as ctx]
    [seon.ctx.namespaces :as ctx-namespaces]
    [seon.ctx.prompt :as ctx-prompt]
    [seon.ctx.relevant :as ctx-relevant]
    [seon.ctx.transcript :as ctx-transcript]
    [seon.ctx.warnings :as ctx-warnings]
    [seon.ctx.your-entity :as ctx-your-entity]
    [seon.db :as db]
    [seon.debug :as debug]
    [seon.embed :as embed]
    [seon.embed.stash :as embed-stash]
    [seon.eval :as seval]
    [seon.log :as seon-log]
    [seon.render :as render]
    [seon.repl :as repl]
    [seon.schema :as schema]
    [seon.warn :as warn]))

;; ============================================================
;; Schemas — every shape the agent reads or writes.
;;
;; Per spec-05 §22.5 the entity lives at `:seon.agent/*` (formerly
;; `:seon.agent.session/*`). The agent-ns is dropped from the entity — it's
;; deterministic from the id via `home-ns`.
;; ============================================================

(schema/register! :seon.agent/id            [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.agent/purpose       :string)
;; The FSM coordination truth (agent-fsm redesign 2026-06-23, §1). STORED
;; on the agent record, re-read each loop iteration:
;;   :idle       neutral / between work — wakeable → :active
;;   :active     a loop is running — NOT wakeable (the running loop picks
;;               up new inbounds via the sliding cap)
;;   :waiting    parked via (agent/wait …) — wakeable → :active
;;   :completed  finished via (complete …) / no-forms — wakeable → :active
;;   :terminated orchestrator kill — UNWAKEABLE (change state first)
(schema/register! :seon.agent/state         [:enum :idle :active :waiting :completed :terminated])
;; The current wake-episode token (reuses the canonical id shape, single
;; source of truth in seon.schema). STORED. Replaces the deleted
;; `!kick-scheduled` atom + `:seon.agent.turn/woken-by` attr: each wake
;; mints a fresh id, the loop re-reads it and bails if a newer wake
;; superseded it (optimistic concurrency via the DB, no atom/CAS).
(schema/register! :seon.agent/wake          :seon.db/id)
;; Base per-loop turn cap (optional; env SEON_MAX_TURNS_PER_LOOP / 20 when
;; absent). The effective cap is a sliding window — every inbound during a
;; wake grants +1 turn (derived, not stored).
(schema/register! :seon.agent/max-turns-per-loop :int)
;; Subagent → parent (optional; delivery descoped to a thin conditional in
;; `complete` — no spawn path sets this yet). References the canonical ref
;; shape; never inline.
(schema/register! :seon.agent/parent        :seon.db/ref)
;; Note surfaced to monitoring agents while parked (optional; set by
;; `(agent/wait …)`).
(schema/register! :seon.agent/wait-note      :string)
;; ORPHANED by the FSM: lifecycle is now `:seon.agent/state`
;; (`armable-agent-ids` keys on state ≠ `:terminated`), and `complete`
;; no longer stamps this attr. Nothing in `seon.agent` writes or reads it.
;; The registration stays only because out-of-lane readers still pull it
;; (FLAGGED for the enum-ripple / U6 sweep): `seon.web.serve`
;; (`/agent/<id>/complete` still calls the removed `agent/complete!`),
;; `seon.web.inspector` (the completed-grid grouping), `seon.client`,
;; `seon.ctx`. Those should move to the state enum; delete this attr after.
(schema/register! :seon.agent/completed-at  :inst)
;; v0 :seon.agent/turn-count, :seon.agent/turns-since-inbound,
;; :seon.agent/interrupted? attrs deleted 2026-05-22. turn-count
;; was a holdover that always read 0; turns-since-inbound moved to
;; :seon.agent.session; interrupted? was registered but never written.

;; Cap on consecutive agentic turns per user message. Lives on the
;; agent entity (overridable via transact); defaults to 20 when the
;; attr is absent. Reading from the entity instead of a hardcoded
;; constant makes the cap discoverable + tunable from the agent's
;; own eval.
(schema/register! :seon.agent/turns-cap :int)
;; ============================================================
;; TRANSITIONAL aliases — the context machinery moved to `seon.ctx`
;; (V3-C, 2026-06-10). These keep (a) the agent-TAUGHT read surface
;; (`seon.agent/messages` …) resolving via seon.eval/lookup-value,
;; (b) stored `:seon.render/ai` slots pointing at
;; 'seon.agent/assemble-context working, and (c) existing callers and
;; tests compiling. The P6 agent.cljs split re-points callers and
;; deletes this block. NOTE: an alias captures the fn value at load
;; time (pre-instrumentation) — call `seon.ctx/*` directly when you
;; want the validated entry point.
;; ============================================================

(def default-turns-cap ctx/default-turns-cap)
(def turns-cap ctx/turns-cap)
(def home-ns ctx/home-ns)
(def current-session ctx/current-session)
(def messages ctx/messages)
(def current-turn ctx/current-turn)
(def evals ctx/evals)
(def current-ns ctx/current-ns)
(def turns-since-inbound ctx/turns-since-inbound)
(def ctx-entities ctx/ctx-entities)
(def host-timezone ctx/host-timezone)
(def truncate-edn ctx/truncate-edn)
(def message-label ctx/message-label)
(def eval-render-cap ctx/eval-render-cap)
(def cap-result ctx/cap-result)
(def cap-result-body ctx/cap-result-body)
(def system-section ctx/system-section)
;; Per-section ctx fns moved to seon.ctx.<name> (ctx-sections-split-
;; 2026-06-18). render-namespace + the shared read API stay in seon.ctx.
(def namespaces-section ctx-namespaces/namespaces-section)
(def your-entity-section ctx-your-entity/your-entity-section)
(def render-namespace ctx/render-namespace)
(def warnings-section ctx-warnings/warnings-section)
(def transcript-char-budget ctx-transcript/transcript-char-budget)
(def transcript-section ctx-transcript/transcript-section)
(def prompt-section ctx-prompt/prompt-section)
(def assemble-context ctx/assemble-context)
(def core-default-ctx ctx/core-default-ctx)

(schema/register! :seon.eval/id          [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.eval/at          :inst)
;; Wall-clock duration of the eval in milliseconds. Populated by
;; seon.eval/eval-batch! per form. Source of truth for slow-eval
;; warnings (v1.md §5.2) without walking evals or computing :at deltas.
(schema/register! :seon.eval/duration-ms :int)
(schema/register! :seon.eval/narration   :string)
(schema/register! :seon.eval/source      :string)
(schema/register! :seon.eval/ok?         :boolean)
(schema/register! :seon.eval/result-edn  :string)
;; println/prn output captured during the eval span (unit #23 fix f —
;; *print-fn* otherwise routes to the pod's stdout, invisible to the
;; agent; a REPL shows print output next to the result). Written by
;; record-eval! only when something printed; absent = no output.
(schema/register! :seon.eval/output      :string)
(schema/register! :seon.eval/error       :string)
;; Phase A item 8 — structured envelope alongside the rendered string.
;; Populated by record-eval! when the failure carries an instrumentation
;; envelope (i.e. (:seon.error/data error) satisfies
;; seon.error.instrument/instrument-error?). Programmatic readers
;; (renderers, agents) branch on this; absent for non-instrumentation
;; failures (timeouts, generic throws).
;;
;; Stored as :string (pr-str at write, read-string at read) because the
;; seon.db Malli→datahike bridge has no :db.type/map entry today; the
;; envelope itself is a map per seon.error.instrument/explain-payload.
;; Bridge enhancement to support :map natively is a follow-up.
(schema/register! :seon.eval/error-data  :string)
;; The namespace the eval ended in (v1.md:236). Written by eval-batch!'s
;; per-form reduce from the (:ns raw-result) of cljs.js/eval-str. For
;; failed forms (read or eval), carries the unchanged current-ns
;; accumulator — the last-known-good ns the form WOULD have run in.
;; Always populated; never nil. Cross-batch derivation of "the agent's
;; current ns" reads this attribute on the latest successful eval.
(schema/register! :seon.eval/ns          :keyword)
;; :seon.eval/agent and :seon.eval/turn deleted 2026-05-23 — evals
;; now land as component-many children of :seon.agent.turn/evals (v1.md
;; §2.1). Agent ref is reachable via the component chain (agent →
;; sessions → turns → evals); the standalone back-refs were noise.

;; ============================================================
;; v1 causality graph — :seon.agent.session + :seon.agent.turn entities (v1.md §2.1).
;; One pod run = one :seon.agent.session. Each render → LLM → eval-batch
;; cycle = one :seon.agent.turn. Both ride as component refs on their
;; parents (cascade-retract on parent retract).
;;
;; ALL counters and derivable values are NOT persisted. v1 follows
;; the reactive-context principle (docs/seon/concepts/reactive-context):
;;
;; - turn-count = (count (:seon.agent.session/turns session)) — read time.
;; - turn-index = (count …) at write time.
;; - turns-since-inbound = count of :seon.agent.turn entities with
;;   :seon.agent.turn/at strictly greater than the latest INBOUND message's
;;   :at (to ∋ me, from ≠ me). See `seon.agent/turns-since-inbound`
;;   helper. Derived; no storage.
;; - current-ns = the latest successful eval's :seon.eval/ns attr (or
;;   the agent's home-ns if no evals yet). See `seon.agent/current-ns`
;;   helper. Derived; no storage.
;;
;; Identity attrs reference the canonical :seon.db/id shape (single
;; source of truth in seon.schema). The [:and {…} :seon.db/id]
;; wrapping adds {:seon.db/identity true} so the bridge writes
;; :db/unique :db.unique/identity to datahike.
;; ============================================================

(schema/register! :seon.agent.session/id    [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.agent.session/at    :inst)
;; :db/isComponent on the ref vectors — retracting a session/turn
;; cascade-retracts its child entities, and one nested pull on the
;; agent walks the whole causality chain inline (v1.md §2.1).
(schema/register! :seon.agent.session/turns [:vector {:seon.db/component true} :seon.db/ref])

(schema/register! :seon.agent.turn/id           [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.agent.turn/at           :inst)
(schema/register! :seon.agent.turn/status       [:enum :running :done :error])
;; The wake-episode this turn belongs to (agent-fsm redesign 2026-06-23,
;; §1). Each turn-open STAMPS the agent's current `:seon.agent/wake` here, so
;; the per-loop count (`count turns where wake = my-wake`) is derivable. STORED
;; — it is coordination metadata, not derivable. References the canonical id
;; shape (single source of truth in seon.schema); never inline. REPLACES the
;; deleted `:seon.agent.turn/woken-by` attr (NOT carried alongside): a turn no
;; longer points at the message that woke it (`reply!` is deleted) — it points
;; at the wake EPISODE, which the loop uses for the sliding cap.
(schema/register! :seon.agent.turn/wake         :seon.db/id)
;; The assembled prompt is NOT persisted as a datom (three-tier storage
;; rule: datoms hold projections, blobs hold full content). run-turn!
;; writes the full prompt to logs/prompts/<agent-id>/<turn-id>.txt and
;; the turn entity carries the char count + file pointer. The old
;; `:seon.agent.turn/prompt-text` datom (silently capped at 16,406 chars by
;; cap-edn — truncated evidence for any long run) is RETIRED 2026-06-09.
;; `:seon.agent.turn/prompt-text` is a PLAIN in-memory plumbing key
;; between run-turn!/with-turn!/ask-and-eval! — NOT registered (never
;; persisted, never in `agent-bootstrap-attrs`). Registering it bought
;; nothing (no datom ever carried it); it stays a local binding only.
(schema/register! :seon.agent.turn/prompt-chars :int)
(schema/register! :seon.agent.turn/prompt-file  :string)
;; In-memory plumbing key only (like :seon.agent.turn/prompt-text): the
;; monotonic per-agent turn index, threaded run-turn! → ask-and-eval! →
;; ask-and-eval-reply! so debug capture keys prompt + response into the
;; SAME per-turn dir. Never reaches the DB (the turn-idx is derivable
;; from session turns; storing would let it desync).
(schema/register! :seon.agent.turn/turn-idx     :int)
;; Honest record of the bounded LLM retry (agent-robustness unit,
;; 2026-06-11): when the provider call failed TRANSPORT-shaped
;; (`:seon.ai/transport?` — fetch threw before any HTTP status) the
;; turn loop retries ONCE after a small backoff, and the turn carries
;; how many retries happened (today always 1). ABSENT = no retry —
;; optional-is-absent, never stored 0.
(schema/register! :seon.agent.turn/llm-retries  :int)
;; #25 tier-2 LLM provider metadata, per turn: BOTH are EDN-stringified
;; opaque provider telemetry — the usage map (:seon.ai/usage —
;; prompt/completion/total tokens, cache fields, provider-specific
;; nested *_tokens_details) and the provider-fields (unrecognized
;; top-level response fields the adapter preserved). Stored as strings,
;; NOT :map: provider telemetry is a third-party boundary with arbitrary
;; nesting/keys (e.g. DeepSeek's :prompt_token_ids), and :map is not a
;; bridgeable datahike attr type — a :map attr's close-tx fails the
;; schema bridge (`:seon.db/unbridgeable-attrs`), and since with-turn!'s
;; close-tx silently dropped that envelope, the turn never closed and
;; the agent hung in :running forever (deaf-after-one-message bug,
;; 2026-06-17). pr-str at the write site (mirrors llm-meta); no consumer
;; reads it back today (pure telemetry). Both ABSENT on a stub-LLM turn
;; or when the provider returns neither — optional-is-absent.
(schema/register! :seon.agent.turn/llm-usage    :string)
(schema/register! :seon.agent.turn/llm-meta     :string)
;; :seon.agent.turn/messages DELETED (agent-fsm redesign 2026-06-23, §5):
;; the per-turn self→self note (from = to = me) is gone — an agent's notes to
;; itself are eval narration (:seon.eval/narration), never a message row.
(schema/register! :seon.agent.turn/evals        [:vector {:seon.db/component true} :seon.db/ref])

(schema/register! :seon.agent.session
  [:map {:seon.db/entity true}
   [:seon.agent.session/id    :seon.agent.session/id]
   [:seon.agent.session/at    :seon.agent.session/at]
   [:seon.agent.session/turns {:optional true} :seon.agent.session/turns]])

(schema/register! :seon.agent.turn
  [:map {:seon.db/entity true}
   [:seon.agent.turn/id           :seon.agent.turn/id]
   [:seon.agent.turn/at           :seon.agent.turn/at]
   [:seon.agent.turn/status       :seon.agent.turn/status]
   [:seon.agent.turn/wake         {:optional true} :seon.agent.turn/wake]
   [:seon.agent.turn/prompt-chars {:optional true} :seon.agent.turn/prompt-chars]
   [:seon.agent.turn/prompt-file  {:optional true} :seon.agent.turn/prompt-file]
   [:seon.agent.turn/debug-dir    {:optional true} :seon.agent.turn/debug-dir]
   [:seon.agent.turn/llm-retries  {:optional true} :seon.agent.turn/llm-retries]
   [:seon.agent.turn/llm-usage    {:optional true} :seon.agent.turn/llm-usage]
   [:seon.agent.turn/llm-meta     {:optional true} :seon.agent.turn/llm-meta]
   [:seon.agent.turn/evals        {:optional true} :seon.agent.turn/evals]])

(schema/register! :seon.agent/sessions    [:vector {:seon.db/component true} :seon.db/ref])

;; The agent's OWN context sections — a component vector of
;; :seon.ctx/section maps (see seon.ctx). MERGED with the core
;; defaults by one priority sort at render time (override-by-name);
;; the old stored-ctx-REPLACES-defaults semantics died with the
;; self-context spec (2026-06-10). :seon.ctx/fn is DEAD — the one
;; slot attr is :seon.render/ai.
(schema/register! :seon.agent/ctx    [:vector {:seon.db/component true} :seon.db/ref])

;; ============================================================
;; v1 §2.2 — program graph. :seon.ns owns the namespace source;
;; :seon.fn / :seon.schema reference their ns via child→parent
;; plain refs (NOT component — a fn does not own its ns). Identity
;; attrs upsert on redefine; history retains prior :source values.
;;
;; Core fns/schemas/nses populate via bootstrap.edn on first
;; boot (§7.3); agent-defined entities populate via detect-and-tee
;; in eval-batch! (§4.2 step 7).
;; ============================================================

;; :seon.ns/name + :seon.ns/source registrations moved to seon.ctx
;; (V3-C) — ctx's render-namespace schemas reference them and seon.ctx
;; loads first.

(schema/register! :seon.fn/sym        [:string {:seon.db/identity true}])
(schema/register! :seon.fn/ns         :seon.db/ref)
(schema/register! :seon.fn/source     :string)
;; Projections from the analyzer's var-map (v1.md §2.2 / Phase B item 10).
;; Re-derived on every detect-and-tee + on bulk-load resume.
(schema/register! :seon.fn/fn-var?    :boolean)
(schema/register! :seon.fn/arglists   :string)
(schema/register! :seon.fn/doc        :string)
(schema/register! :seon.fn/private?   :boolean)
;; The fn's contract: `(pr-str (m/form <the fn's :malli/schema>))`.
;; PRESENT ⇒ specced (the exact contract is in the corpus); ABSENT ⇒
;; unspecced. Replaces the old boolean specced flag — the form carries
;; strictly more information than a bare flag.
(schema/register! :seon.fn/spec       :string)
;; Set when `:malli/schema` metadata is present but the value fails to
;; parse via `malli.core/schema`. Orthogonal to `:seon.fn/spec` — when
;; this is set, the schema is present but unparseable, so we omit
;; `:seon.fn/spec` and will not instrument the fn. Phase 3 of
;; mvp-completion-plan.
(schema/register! :seon.fn/schema-error :string)
(schema/register! :seon.fn/created-at :inst)

(schema/register! :seon.schema/key        [:keyword {:seon.db/identity true}])
(schema/register! :seon.schema/ns         :seon.db/ref)
(schema/register! :seon.schema/source     :string)
(schema/register! :seon.schema/created-at :inst)

;; ============================================================
;; Entity-kind `:map` schemas. One per renderable kind, each DECLARED
;; with `{:seon.db/entity true}` (entity-kind-ness is declared, never
;; inferred — request/response envelopes stay unmarked). The
;; `:seon.render/ai` / `:seon.render/html` symbols live on the schema's
;; own properties — for a declared entity, `seon.schema/register!`
;; derives `:seon.entity/id-attr` from whichever entry carries
;; `{:seon.db/identity true}`. That id-attr is what the renderer
;; enumerates in AEVT to find all instances of the kind; the render
;; symbols are looked up via `(m/properties (m/schema :seon.eval))`
;; at render time (no per-row stamping).
;;
;; These are intentionally MINIMAL — they exist so the renderer's
;; discovery loop has a schema to consult. Full per-attr lists with
;; `{:optional true}` flags are a Phase 1b/1c follow-up.
;; ============================================================

;; Required attrs reflect what every writer of the kind populates
;; unconditionally — derived empirically from the write sites:
;;   :seon.eval   — `record-eval!` (eval.cljs)
;;   :seon.agent.message — `message!` (the single write entry point,
;;                         seon.agent.message — its entity-kind :map
;;                         schema lives there too, P6 split)
;;   :seon.fn     — `build-tee-entities` (eval.cljs)
;;   :seon.schema — `build-tee-entities` (eval.cljs)
;;   :seon.ns     — `build-tee-entities` (eval.cljs)
;;
;; Anything written conditionally (errors only on failure, result only
;; on success, projections that may be nil) is `{:optional true}` per
;; CLAUDE.md "Optional = absent" rule. Never `[:maybe X]`.
;;
;; These required-sets feed schemas-as-queryable-data: at boot,
;; `seon.client/start-agent!` decomposes each :map into a `:seon.schema`
;; entity whose `:seon.schema/required-attrs` is the set computed from
;; entries without `{:optional true}`. Kind-lookup in `seon.render`
;; queries those entities via datalog.

(schema/register! :seon.eval
  [:map {:seon.db/entity   true
         :seon.render/ai   'seon.handlers.eval/render-ai
         :seon.render/html 'seon.handlers.eval/render-html}
   [:seon.eval/id          :seon.eval/id]
   [:seon.eval/source      :seon.eval/source]
   [:seon.eval/ok?         :seon.eval/ok?]
   [:seon.eval/at          :seon.eval/at]
   [:seon.eval/duration-ms {:optional true} :seon.eval/duration-ms]
   [:seon.eval/narration   {:optional true} :seon.eval/narration]
   [:seon.eval/ns          {:optional true} :seon.eval/ns]
   [:seon.eval/result-edn  {:optional true} :seon.eval/result-edn]
   [:seon.eval/output      {:optional true} :seon.eval/output]
   [:seon.eval/error       {:optional true} :seon.eval/error]
   [:seon.eval/error-data  {:optional true} :seon.eval/error-data]])

(schema/register! :seon.fn
  [:map {:seon.db/entity   true
         :seon.render/ai   'seon.handlers.fn/render-ai
         :seon.render/html 'seon.handlers.fn/render-html}
   [:seon.fn/sym    :seon.fn/sym]
   [:seon.fn/ns     :seon.fn/ns]
   [:seon.fn/source :seon.fn/source]
   ;; analyzer projections — present when the eval defined a var; null
   ;; on schema-only registrations. Optional rather than always-present
   ;; because var-projection returns nil for non-var defs.
   [:seon.fn/fn-var?    {:optional true} :seon.fn/fn-var?]
   [:seon.fn/arglists   {:optional true} :seon.fn/arglists]
   [:seon.fn/doc        {:optional true} :seon.fn/doc]
   [:seon.fn/private?   {:optional true} :seon.fn/private?]
   [:seon.fn/spec       {:optional true} :seon.fn/spec]
   [:seon.fn/schema-error {:optional true} :seon.fn/schema-error]
   [:seon.fn/created-at {:optional true} :seon.fn/created-at]])

(schema/register! :seon.schema
  [:map {:seon.db/entity   true
         :seon.render/ai   'seon.handlers.schema/render-ai
         :seon.render/html 'seon.handlers.schema/render-html}
   [:seon.schema/key    :seon.schema/key]
   [:seon.schema/source :seon.schema/source]
   [:seon.schema/ns         {:optional true} :seon.schema/ns]
   [:seon.schema/created-at {:optional true} :seon.schema/created-at]])

(schema/register! :seon.ns
  [:map {:seon.db/entity   true
         :seon.render/ai   'seon.handlers.ns/render-ai
         :seon.render/html 'seon.handlers.ns/render-html}
   [:seon.ns/name   :seon.ns/name]
   [:seon.ns/source :seon.ns/source]])

;; :seon.agent — the agent's OWN entity-kind (unit 1.4). The
;; `:seon.render/html` property makes `seon.render.default/view` the
;; DEFAULT tile renderer via the same kind-lookup every other kind
;; uses; an agent OVERRIDES by transacting `:seon.render/html
;; '<its-own-fn-sym>` onto its own entity (per-entity override wins in
;; `seon.render/entity-html-sym`). No `:seon.render/ai` property —
;; the agent entity must NOT enter the chronological ai window.
;; Required attrs (id + state) mirror `create!`, the one writer that
;; runs unconditionally; everything else arrives lazily.
;; Entity map = §1 of the agent-fsm redesign: id (req) + state (req) +
;; the optional config/coordination attrs. The render slots
;; (props `:seon.render/html` + the optional :seon.render/ai/html attrs)
;; are KEPT — they're the live agent-tile surface (U6 territory), not
;; data the FSM owns. `completed-at`/`sessions`/`turns-cap`/`ctx` keep
;; their own register! (still transactable/queryable) but leave the
;; entity declaration: they're no longer part of the FSM record shape.
(schema/register! :seon.agent
  [:map {:seon.db/entity   true
         :seon.render/html 'seon.render.default/view}
   [:seon.agent/id      :seon.agent/id]
   [:seon.agent/purpose            {:optional true} :seon.agent/purpose]
   [:seon.agent/state   :seon.agent/state]
   [:seon.agent/wake               {:optional true} :seon.agent/wake]
   [:seon.agent/max-turns-per-loop {:optional true} :seon.agent/max-turns-per-loop]
   [:seon.agent/parent             {:optional true} :seon.agent/parent]
   [:seon.agent/wait-note          {:optional true} :seon.agent/wait-note]
   [:seon.render/ai   {:optional true} :seon.render/ai]
   [:seon.render/html {:optional true} :seon.render/html]])

;; ============================================================
;; FSM state helpers (agent-fsm redesign 2026-06-23, U1). The agent
;; record's `:seon.agent/state` + `:seon.agent/wake` are the loop's
;; coordination truth — re-read each iteration, externally controllable.
;; These are the thin read/write seam over `seon.db`; the lifecycle verbs
;; (wait/complete/terminate, U3) and the loop (U4) build on them.
;; ============================================================

(schema/register! ::current-state-request [:map [:seon.agent/id :seon.agent/id]])
(schema/register! ::set-state-request
  [:map [:seon.agent/id :seon.agent/id] [:seon.agent/state :seon.agent/state]])
(schema/register! ::fresh-wake-request    [:map [:seon.agent/id :seon.agent/id]])

(defn current-state
  "The agent's current `:seon.agent/state` keyword (sync read from the
   local db value), nil if the agent entity doesn't resolve. Map-in."
  {:malli/schema [:=> [:cat ::current-state-request]
                  [:maybe :seon.agent/state]]}
  [{:seon.agent/keys [id]}]
  (:seon.agent/state
    (db/entity {:seon.db/ref [:seon.agent/id id]})))

(defn ^:async set-state!
  "Transact a new `:seon.agent/state` for the agent (upsert by id).
   Returns the transact-response promise. Map-in. `^:async` fns aren't
   runtime-instrumented — the schema is the contract."
  {:malli/schema [:=> [:cat ::set-state-request] :seon.db/transact-response]}
  [{:seon.agent/keys [id state]}]
  (await (db/transact!
           {:seon.db/tx-data [{:seon.agent/id id :seon.agent/state state}]})))

(defn ^:async fresh-wake!
  "Mint a fresh wake-episode id, transact it onto `:seon.agent/wake`, and
   return the minted id. Each wake gets a new token so the loop can
   re-read it and bail when a newer wake supersedes it (optimistic
   concurrency via the DB — no atom, no CAS). Map-in. `^:async`."
  {:malli/schema [:=> [:cat ::fresh-wake-request] :seon.db/id]}
  [{:seon.agent/keys [id]}]
  (let [wake (db/new-id!)]
    (await (db/transact!
             {:seon.db/tx-data [{:seon.agent/id id :seon.agent/wake wake}]}))
    wake))

(schema/register! ::armable-agent-ids-request [:map [:seon.db/db {:optional true} :seon.db/db-val]])
(schema/register! ::armable-agent-ids-response [:vector :seon.db/id])

(defn armable-agent-ids
  "Agent ids whose triggers should be ARMED — every agent NOT in the
   terminal `:terminated` state (the single source of truth for 'this
   agent can still be woken'). Replaces the old three vocabularies
   (`live-agent-ids` / `all-running-agents` / `resumable-agent-ids`):
   a `:waiting`/`:completed` agent must still get a trigger so a new
   message wakes it; only an orchestrator-`:terminated` agent is armless.
   Derived from the db value at call time (reactive-context: no stored
   registry). Sorted asc for deterministic boot logs. Map-in; `:seon.db/db`
   optional (defaults to `*conn*`'s db via `db/query`)."
  {:malli/schema [:=> [:cat ::armable-agent-ids-request] ::armable-agent-ids-response]}
  [{:seon.db/keys [db]}]
  (let [q '[:find ?aid
            :where
            [?a :seon.agent/id ?aid]
            [?a :seon.agent/state ?state]
            [(not= :terminated ?state)]]]
    (->> (if db
           (db/query {:seon.db/db db :seon.db/query q})
           (db/query {:seon.db/query q}))
         (map first)
         sort
         vec)))

;; ============================================================
;; Turn loop — was seon.agent.session.cljs, now consolidated here.
;;
;; One turn = build ctx → call LLM → parse → eval batch → flip to :idle.
;; Partial-failure: form N+1 always runs (see seon.eval/eval-batch!).
;; ============================================================

(defn- log [agent-id turn-n stage & info]
  (seon-log/info-console!
    (str "seon.agent/" agent-id)
    (str "turn " turn-n " ▸ " stage)
    (if (= 1 (count info)) (first info) (vec info))))

;; Forward refs — run-turn!, run-agentic-loop!, start-session!,
;; turn-index live in the v1 scaffold block below;
;; inbound-message-handler calls them. (Reorganizing the file is a separate
;; pass.)
(declare run-turn! run-agentic-loop! start-session! turn-index)

(defn- per-agent-shape?
  "True when `sym` is in the agent's own home namespace (per spec-05
   §15.1a). Per-agent fns get the per-agent input shape (entity
   pre-pulled under a namespaced key); everything else gets the system
   shape (`:seon.agent/id` + DB; fn pulls the entity itself)."
  [sym agent-id]
  (and (qualified-symbol? sym)
       (str/starts-with? (namespace sym)
                         (str "my.agent." agent-id))))

(defn- ai-render-input
  "Build the input map for the agent's `:seon.render/ai` dispatch.
   Two shapes, picked by symbol namespace (spec-05 §15.1a)."
  [sym db agent-id ent]
  (if (per-agent-shape? sym agent-id)
    {:seon.db/db                                          db
     (keyword (str "my.agent." agent-id) "ctx")         ent}
    {:seon.db/db    db
     :seon.agent/id agent-id}))

;; ============================================================
;; Kick handler — datahike tx-listener fires on every transact; we
;; filter for new INBOUND messages (to ∋ me AND from ≠ me — sender-
;; agnostic: the user and other agents wake the loop the same way) and
;; schedule run-agentic-loop! via setTimeout so we return to the
;; listener immediately (no blocking the transactor).
;;
;; State-machine guard: if the agent is already :running, do nothing —
;; the loop's next render reads accumulated messages via the derived
;; conversation. A per-agent SCHEDULED LATCH closes the read-state-
;; then-schedule window (multi-agent-state-isolation Q2 #4: two quick
;; txs could both read non-:running before either loop's open-tx
;; landed → two concurrent loops for ONE agent). The latch is set
;; SYNCHRONOUSLY in the handler and cleared when the loop exits — a
;; legitimate runtime artifact, not derivable state.
;;
;; Hop guard lives HERE (at wake): a message whose :seon.agent.message/hops
;; reached `warn/hop-cap` wakes nothing — loud console.error + the
;; clustered `check-hop-exhausted` warning surface the refusal.
;; ============================================================

(defonce ^:private !kick-scheduled
  ;; agent-ids with a loop scheduled-or-running via the kick path.
  (atom #{}))

(defn- inbound-msg-datom?
  "True iff this added `:seon.agent.message/to` datom targets `my-eid` from a
   DIFFERENT sender with a WAKING origin (∈ {:human :agent}). The to-check
   is load-bearing: every agent installs this listener, so without it ONE
   message wakes EVERY agent's loop — each stray wake is a wasted LLM call
   (observed live 2026-06-09). The from-check (`from ≠ me`) stops an agent's
   own writes — including its per-turn assistant message — from re-kicking
   itself. The origin-check (#43) is what stops a :core substrate nudge (a
   tile-recovery message, sent FROM the user-ref) from waking an idle agent:
   only a real :human message or an :agent↔peer consult wakes a loop. Legacy
   rows have no origin attr — treat absent origin as waking (those predate
   :core and were all human/agent)."
  [db {eid :seon.db/e target :seon.db/v} my-eid]
  (and (= target my-eid)
       (let [msg (db/entity {:seon.db/db db :seon.db/ref eid})]
         (and (not= my-eid (:db/id (:seon.agent.message/from msg)))
              (not= :core (:seon.agent.message/origin msg))
              ;; I-1: a tx-hook-consumed message (handled? = true) does
              ;; NOT wake — a downstream deterministic chat-control sets
              ;; handled? in the same tx that processes the command, so
              ;; the agent isn't double-woken.
              (not (true? (:seon.agent.message/handled? msg)))))))

(defn- inbound-message-handler
  [{:seon.agent/keys [id] :as input}]
  (fn [{:seon.db/keys [db attr-index]}]
    (let [my-eid  (:db/id (db/entity {:seon.db/db db
                                      :seon.db/ref [:seon.agent/id id]}))
          inbound (when my-eid
                    (->> (:seon.agent.message/to attr-index)
                         (filter :seon.db/added?)
                         (filter #(inbound-msg-datom? db % my-eid))))
          {waking false exhausted true}
          (group-by (fn [{eid :seon.db/e}]
                      (>= (or (:seon.agent.message/hops
                                (db/entity {:seon.db/db db :seon.db/ref eid}))
                              0)
                          warn/hop-cap))
                    inbound)]
      ;; Hop guard AT wake — refuse, loudly. The message stays in the
      ;; DB (check-hop-exhausted renders it in <warnings>); the loop
      ;; does NOT start for it, so an A↔B auto-reply chain dies here.
      (doseq [{eid :seon.db/e} exhausted]
        (let [msg (db/entity {:seon.db/db db :seon.db/ref eid})]
          (js/console.error
            (str "seon.agent: WAKE REFUSED for agent " id
                 " — message " (:seon.agent.message/id msg)
                 " hops=" (:seon.agent.message/hops msg)
                 " reached hop-cap " warn/hop-cap
                 " (agent↔agent ping-pong guard). A human message"
                 " resets the chain (hops 0)."))))
      (when (seq waking)
        (let [state (:seon.agent/state
                      (db/entity {:seon.db/db db
                                  :seon.db/ref [:seon.agent/id id]}))
              ;; The waking message id — surfaced in the WAKE SKIPPED log
              ;; below. (`:seon.agent.turn/woken-by` is DELETED, agent-fsm
              ;; §5: turns now stamp `:seon.agent.turn/wake` from the
              ;; agent's wake-episode token; `reply!`'s target-derivation
              ;; from woken-by is gone with `reply!`.)
              mid   (:seon.agent.message/id
                      (db/entity {:seon.db/db db
                                  :seon.db/ref (:seon.db/e (first waking))}))]
          (if (and (not= :active state)
                   (not (contains? @!kick-scheduled id)))
            (do
              (swap! !kick-scheduled conj id)
              ;; setTimeout breaks the ALS scope — re-enter `with-agent`
              ;; so the loop's downstream calls (run-turn!, eval-batch!,
              ;; section fns, web handlers) see (db/current-agent-id).
              (js/setTimeout
                (fn []
                  (-> (js/Promise.resolve
                        (db/with-agent id
                          #(run-agentic-loop! input)))
                      (.finally (fn [] (swap! !kick-scheduled disj id)))))
                0))
            ;; #49 fail-loud: a wake was SKIPPED. A silently-dropped wake
            ;; is the exact fail-loud violation the project forbids — name
            ;; WHY and what re-processes it. state=:running → the in-flight
            ;; loop's next halt check reads the message as an unanswered
            ;; live inbound (unanswered-live-inbound?) and keeps running for
            ;; it. latch held → a loop is scheduled-or-running for this id;
            ;; the same halt check covers a message that lands while it runs.
            ;; The skip is observable, not invisible.
            (js/console.warn
              (str "seon.agent: WAKE SKIPPED for agent " id
                   " — message " mid
                   (cond
                     (= :active state)
                     (str " — state=:active (in-flight loop's next halt check"
                          " sees it as an unanswered live inbound)")
                     (contains? @!kick-scheduled id)
                     (str " — !kick-scheduled latch held (a loop is already"
                          " scheduled-or-running for this id)")
                     :else " — guard failed (unexpected)")))))))))

(defn install-user-trigger!
  "Register the inbound-message trigger listener for this agent — wakes
   `run-agentic-loop!` when a message lands with to ∋ me AND from ≠ me.
   Idempotent: unlistens any prior handler for the same agent-id first
   so hot-reload of agent.cljs doesn't leave stale closures wired to
   the tx bus. (Fn + listener key keep their historical names so re-arm
   replaces listeners installed before the from/to migration.)

   Input map:
     :seon.agent/id              the agent's id string
     :seon.agent/llm-fn          ctx-string -> Promise<{:text \"…\"}>
     :seon.agent/compile-state   bootstrap compile-state"
  [{:seon.agent/keys [id] :as input}]
  (let [k [::user-message-trigger id]]
    (try (db/unlisten! {:seon.db/key k}) (catch :default _ nil))
    (db/listen!
      {:seon.db/key     k
       :seon.db/handler (inbound-message-handler input)})))

;; ============================================================
;; Agent creation. Allocates an id, transacts the entity.
;; ============================================================

(defn ^:async create!
  "Allocate an agent entity. Idempotent: re-calling with the same id
   resets state to :idle (transact is upsert-by-unique-id) and NEVER
   re-seeds — a resumed agent keeps its own purpose and sections. A
   GENUINELY NEW entity gets `:seon.agent/purpose` ONLY when the human
   stated one; otherwise the attr stays ABSENT (optional = absent)
   until the agent derives a purpose and transacts it — the
   derive-your-purpose teaching lives in the `<your-entity>` context
   render (seon.ctx/your-entity-section), NEVER in the stored value:
   the welcome tile shows purpose verbatim to the CUSTOMER, so
   agent-directed instruction text must not masquerade as data
   (chat-surface task #29, a23). Purpose is ENTITY DATA rendered by
   the `<your-entity>` section (context-v4 §2.5 — the old
   `:purpose`/`:your-sections` seed sections died with it).
   `:seon.agent/turns-cap`, when given, is transacted onto the entity
   (it only WORKS as entity data — see `seon.ctx/turns-cap`); absent
   leaves the stored cap unchanged.

   Returns `{:seon.agent/id id}` on success; on a FAILED transact the
   db error envelope (`{:seon.db/ok? false :seon.db/error …}`) comes
   back as-is — errors are values, the same contract as
   `seon.agent.message/message!`. A failed create means NO agent
   entity; callers must branch instead of chasing a ghost."
  [{:seon.agent/keys [id purpose turns-cap]}]
  (let [fresh? (nil? (db/entity {:seon.db/ref [:seon.agent/id id]}))
        res    (await (db/transact!
                        {:seon.db/tx-data
                         [(cond-> {:seon.agent/id    id
                                   :seon.agent/state :idle}
                            (and fresh?
                                 (string? purpose)
                                 (not (str/blank? purpose)))
                            (assoc :seon.agent/purpose purpose)
                            (some? turns-cap)
                            (assoc :seon.agent/turns-cap turns-cap))]}))]
    (if (false? (:seon.db/ok? res))
      ;; Surface-errors-loudly AND return the failure: a success-shaped
      ;; map after a failed transact is a dishonest record.
      (do (js/console.error
            (str "seon.agent/create! transact FAILED for " id ": "
                 (:seon.error/message (:seon.db/error res))))
          res)
      {:seon.agent/id id})))

;; ============================================================
;; Boot. The single entry point seon.client calls at startup.
;;
;; V0 hardcoded `default-id` / `default-ns` removed 2026-05-24 (audit P1
;; — see docs/prds/agent-runtime/research/schema-state-architecture-audit
;; -2026-05-23.md §2). Multi-agent v1 needs agent identity to flow via
;; the `seon.db/agent-id-als` core, not via process-global atoms.
;; Callers (seon.client/start-agent!) now mint the id locally and wrap
;; the boot pipeline in `(seon.db/with-agent id …)`. The home-ns stays
;; deterministic via `(home-ns id)`.
;; ============================================================

(defn ^:async boot!
  "Create an agent entity + install the kick listener. Map-in / map-out.

   Input:
     :seon.agent/id             agent id string (REQUIRED — pass the id
                                minted by the caller; no implicit default)
     :seon.agent/llm-fn         ctx-string -> Promise<{:text \"…\"}>
     :seon.agent/compile-state  defonce'd bootstrap compile-state

   Returns `{:seon.agent/id _ :seon.agent/ns _}`. On a FAILED create!
   the db error envelope (`{:seon.db/ok? false :seon.db/error …}`)
   propagates as-is — errors are values, same contract as create!
   itself: there is NO agent entity, so no trigger is installed and no
   nil id leaks downstream. The first user message kicks
   `run-agentic-loop!` (which lazily opens a `:seon.agent.session` on
   first turn)."
  [{:seon.agent/keys [id llm-fn compile-state purpose]}]
  (let [res (await (create! {:seon.agent/id id :seon.agent/purpose purpose}))]
    (if (false? (:seon.db/ok? res))
      ;; create! already console.error'd the transact failure; name the
      ;; boot path too, then hand the envelope up — callers branch.
      (do (js/console.error
            (str "seon.agent/boot! ABORTED for " id
                 " — create! failed; propagating the error envelope"))
          res)
      (let [{:seon.agent/keys [id]} res
            agent-ns (home-ns id)]
        (install-user-trigger! {:seon.agent/id id
                                :seon.agent/llm-fn llm-fn
                                :seon.agent/compile-state compile-state})
        {:seon.agent/id id
         :seon.agent/ns agent-ns}))))

;; ============================================================
;; message! — moved to seon.agent.message (P6 split, 2026-06-10) so the
;; keyword namespace matches the code namespace. Re-exported on the face
;; (the agent-taught call surface is seon.agent/message!). `reply!` is
;; DELETED (agent-fsm redesign U2) — the agent-facing messaging verbs are
;; now seon.agent.message/user + /agent via the `message/` alias. Same
;; caveat as the ctx aliases above — a def alias captures the fn value at
;; load time (pre-instrumentation); call seon.agent.message/* directly for
;; the validated entry point.
;; ============================================================

(def message! msg/message!)
(def user-ref msg/user-ref)

;; ============================================================
;; Lifecycle verbs — wait / complete / terminate (the agent-facing
;; terminal transitions, reached through the `agent/` alias). Each is a
;; small state transact reading the calling agent from the ALS scope; it
;; returns the new `:seon.agent/state` keyword (the value the transcript
;; shows). `^:async` fns aren't runtime-instrumented — the schema is the
;; contract. No verb ever writes a self→self message.
;; ============================================================

(defn ^:async wait
  "Park the calling agent: state → :waiting, with a note surfaced to
   monitoring agents. The agent resumes (→ :active) the moment a message
   arrives — the wake gate handles that."
  {:malli/schema [:=> [:catn [::note :string]] :seon.agent/state]}
  [note]
  (let [id (db/current-agent-id)]
    (await (db/transact!
             {:seon.db/tx-data [{:seon.agent/id        id
                                 :seon.agent/state     :waiting
                                 :seon.agent/wait-note note}]}))
    :waiting))

(defn ^:async complete
  "Finish the calling agent's work: state → :completed (still wakeable —
   a new message resumes it). If `:seon.agent/parent` is set, send the
   result to the parent (which wakes it via the normal inbound gate); no
   parent → the result is for the human (already said via message/user)."
  {:malli/schema [:=> [:catn [::result :string]] :seon.agent/state]}
  [result]
  (let [id     (db/current-agent-id)
        ent    (when id (db/entity {:seon.db/ref [:seon.agent/id id]}))
        parent (:seon.agent/parent ent)]
    (await (db/transact!
             {:seon.db/tx-data [{:seon.agent/id    id
                                 :seon.agent/state :completed}]}))
    (when parent
      (await (msg/message! {:seon.agent.message/content result
                            :seon.agent.message/to      parent})))
    :completed))

(defn ^:async terminate
  "Kill an agent: state → :terminated — the one UNWAKEABLE state (a
   message will not start a loop). Orchestrator-only; an agent does not
   terminate itself."
  {:malli/schema [:=> [:catn [::id ::id]] :seon.agent/state]}
  [id]
  (await (db/transact!
           {:seon.db/tx-data [{:seon.agent/id    id
                               :seon.agent/state :terminated}]}))
  :terminated)

;; ============================================================
;; v1 §6 — turn lifecycle.
;;
;; Composition: three small ^:async helpers + an `open-turn!`
;; bracketing combinator + a `run-turn!` orchestrator. Every transact
;; in the pipeline runs inside ONE outer `with-tx-context` scope that
;; carries agent/session/turn/origin — no manual `:tx-meta` plumbing
;; at any call site (auto-merged via `seon.db/transact!`'s
;; `merge-tx-context-into-opts`).
;;
;; Two transacts per turn instead of four: `open-turn!` folds the
;; prompt-text + the agent's current `:seon.agent/wake` into the
;; open-tx and the close-tx (`close-turn!`) folds the turn's telemetry.
;; Eval-batch's per-form txs stay (each form is its own tx for
;; partial-failure semantics — v1.md §4.4).
;;
;; The named-inline `(fn ^:async name [] …)` is the one CLJS shape
;; that propagates `:async` correctly across `(.run als-instance …
;; f)`; the cleaner pattern (which we use here) is to define helpers
;; with `defn ^:async` and pass plain anonymous thunks to
;; `with-tx-context`. See `docs/prds/agent-runtime/research/
;; cljs-runturn-simplification-2026-05-23.md`.
;; ============================================================

(defn turn-index
  "Zero-indexed next turn slot for the session — derived from the
   current count of `:seon.agent.session/turns`. Not persisted (storing
   would let it desync from reality)."
  [session-id]
  (count (:seon.agent.session/turns
           (db/entity {:seon.db/ref [:seon.agent.session/id session-id]}))))

(defonce ^:private !boot-sessions
  ;; Session ids opened by THIS pod process. `defonce` — survives hot
  ;; reload (a reload is the same pod run), empty on a fresh Node boot.
  ;; `ensure-session!` only reuses sessions found here, so a pod
  ;; restart always opens a FRESH session for a resumed agent: the
  ;; agent entity, purpose, and messages persist across restarts
  ;; (messages are global), but evals are session-scoped — the
  ;; intended resume shape.
  (atom #{}))

(defn ^:async start-session!
  "Open a new `:seon.agent.session` for `agent-id` and append to
   `:seon.agent/sessions`. Records the id in `!boot-sessions` (this
   process opened it). Returns the new session entity."
  [agent-id]
  (let [session-id (db/new-id!)]
    (await (db/transact!
             {:seon.db/tx-data
              [{:seon.agent/id agent-id
                :seon.agent/sessions
                [{:seon.agent.session/id session-id
                  :seon.agent.session/at (js/Date.)}]}]}))
    (swap! !boot-sessions conj session-id)
    (db/entity {:seon.db/ref [:seon.agent.session/id session-id]})))

(defn ^:async ensure-session!
  "Return the agent's current session, opening one if THIS pod process
   hasn't opened one yet. Idempotent within a pod run; a session found
   in the DB but opened by a previous pod run is NOT reused — every
   pod boot starts a fresh session for a resumed agent (cross-restart
   reuse was never intended; messages stay global, evals are
   session-scoped)."
  [agent-id]
  (let [sess (current-session agent-id)]
    (if (and sess
             (contains? @!boot-sessions (:seon.agent.session/id sess)))
      sess
      (await (start-session! agent-id)))))

(defn render-prompt
  "Sync — resolve the agent's `:seon.render/ai` slot (default
   `seon.agent/assemble-context`) and call it. The slot is
   bridge-decoded (`seon.db/decode-edn-value`); a STRING slot renders
   verbatim (relaxed slot spec, self-context 2026-06-10); a symbol is
   resolved and called. Returns the prompt string (empty when the
   symbol can't be resolved)."
  [agent-id]
  (let [ent  (db/entity {:seon.db/ref [:seon.agent/id agent-id]})
        slot (or (some->> (:seon.render/ai ent)
                          (db/decode-edn-value :seon.render/ai))
                 'seon.agent/assemble-context)]
    (if (string? slot)
      slot
      (let [input (ai-render-input slot @db/*conn* agent-id ent)]
        (or (:seon.render/text (render/ai-render slot input)) "")))))

(defn embed-retrieval-on?
  "True when the embedding-retrieval feature is enabled — the env var
   `SEON_EMBED` is PRESENT (any value, incl. empty string). This is the SAME
   single switch the wire-server reads (`seon.embed/embed-feature-enabled?`),
   so one env var gates the whole feature across both processes. UNSET ⇒ false
   ⇒ the prefetch never fires and `render-prompt` runs on the exact
   pre-retrieval code path (the byte-identical-OFF contract)."
  []
  (some? (.. js/process -env -SEON_EMBED)))

(defn ^:async prefetch-and-render-prompt!
  "Render this turn's prompt, OPTIONALLY prefetching embedding-retrieval hits
   first. The async seam: the wire `knn-search` is awaited HERE (in the async
   `run-turn!`), the hits stashed in a fiber-local store, then the SYNCHRONOUS
   `render-prompt` runs inside that scope so the `:relevant-source` section
   reads the hits without making the value-returning `assemble-context` async.

   DEFAULT-OFF (byte-identical): when [[embed-retrieval-on?]] is false this is
   exactly `(render-prompt agent-id)` — no wire call, no stash, no behavior
   change. When ON: derive the query from the latest live inbound (sync), then
   KNN over the WHOLE embedding index — NO `:where`/`:eids` scope, so the
   wire-server runs unscoped KNN across EVERY embedded entity of ANY kind (fns,
   KB, future). This is deliberately kind-GENERAL: 'the most relevant context
   for your task', not 'the most relevant function'. (A `:where` scope is NOT
   used because the only kind-agnostic 'has an embedding' marker — the
   secondary-only `:seon/embedding` — does not resolve on the pod's local db,
   and `:seon.embed/source-hash` is a JVM-write-side attr unregistered in the
   pod's `seon.schema`, so `where->eids`→`db/query` would throw; unscoped is the
   correct whole-index search and needs no local resolution.) `:seon.embed/db`
   is still threaded so the hit-ENRICHMENT pulls each entity's display fields
   (fn source / kb title+body / …) from the pod's LOCAL db. `k =
   seon.ctx.relevant/top-k`, FAIL-SOFT to nil hits on any error (the section
   then renders blank), render inside the stash."
  [agent-id]
  (if-not (embed-retrieval-on?)
    (render-prompt agent-id)
    (let [db    @db/*conn*
          query (ctx/retrieval-query {:seon.db/db db :seon.agent/id agent-id})
          hits  (if (str/blank? query)
                  nil
                  (-> (.then
                        (embed/search-pull
                          {:seon.embed/query query
                           :seon.embed/k ctx-relevant/top-k
                           :seon.embed/db db})
                        (fn [{:seon.embed/keys [hits]}] hits))
                      (.catch (fn [e]
                                (js/console.warn
                                  "[seon.agent] embed prefetch failed (fail-soft → no hits):"
                                  (or (.-message e) (str e)))
                                nil))))
          hits  (await hits)]
      (embed-stash/with-hits hits #(render-prompt agent-id)))))

(declare close-turn!)

(defn ^:async open-turn!
  "Bracketing combinator (formerly `with-turn!`). Opens a
   `:seon.agent.turn` on the given session with `prompt-text` already
   attached and the agent's current `:seon.agent/wake` STAMPED on
   `:seon.agent.turn/wake` (so the turn records which wake-episode it
   belongs to — the loop's sliding cap derives its per-loop count from
   it). Flips agent state to `:active`, then awaits `body-fn` (a plain
   0-arg thunk that returns a Promise<map>) via `close-turn!`, which
   closes the turn `:status :done` and flips agent state back to
   `:idle`. On throw, `close-turn!` flips the turn to `:status :error`
   and re-throws so callers see the failure shape.

   Returns whatever `body-fn` returned, so the caller can read e.g.
   `:seon.agent/eval-count` for stop-policy decisions."
  [{:seon.agent/keys [id]
    :seon.agent.session/keys [id-of-session]
    :seon.agent.turn/keys [id-of-turn prompt-text prompt-file]}
   body-fn]
  ;; Short-circuit on open-turn failure (task 9b finding 3). If the
  ;; open-tx returns `{::ok? false}`, there is NO turn entity in the
  ;; DB — calling `body-fn` (the LLM) would run a turn that has no
  ;; trace, and the close-tx + error-tx below would silently fail
  ;; against the missing entity. Bail with the envelope so the caller
  ;; sees the same shape it sees from any other transact failure.
  (let [wake (:seon.agent/wake
               (db/entity {:seon.db/ref [:seon.agent/id id]}))
        open-result
        (await
          (db/transact!
            {:seon.db/tx-data
             [{:seon.agent.session/id id-of-session
               :seon.agent.session/turns
               [(cond->
                  {:seon.agent.turn/id           id-of-turn
                   :seon.agent.turn/at           (js/Date.)
                   :seon.agent.turn/status       :running
                   ;; Three-tier storage: the datom is a PROJECTION (char
                   ;; count); the full prompt lives in the blob file run-turn!
                   ;; wrote (`:seon.agent.turn/prompt-file`). No truncation anywhere
                   ;; — the file is the complete evidence.
                   :seon.agent.turn/prompt-chars (count (str prompt-text))}
                  ;; nil when the file write failed (logged) — chars survive.
                  prompt-file (assoc :seon.agent.turn/prompt-file prompt-file)
                  ;; The wake-episode this turn belongs to (agent-fsm §1).
                  ;; Absent when the agent has no wake yet (boot/manual turn).
                  wake (assoc :seon.agent.turn/wake wake))]}
              {:seon.agent/id id :seon.agent/state :active}]}))]
    (if (false? (:seon.db/ok? open-result))
      open-result
      (close-turn! id id-of-turn body-fn))))

(defn ^:async ^:private ensure-idle!
  "Failsafe state-reset for `id` (errors-are-values, never throws). The
   wake guard `(not= :active state)` in inbound-message-handler means a
   single missed `:idle` reset leaves the agent permanently DEAF (the
   deaf-after-one-message bug, 2026-06-17: a close-tx that failed the
   schema bridge for an unbridgeable folded attr left state :running
   forever). So EVERY exit from a turn — close success, close FAILURE,
   throw, throw-handler failure — funnels through here: a minimal
   state-only tx that can't itself carry an unbridgeable attr. Loud on
   failure; swallows its own throw (the loop must keep running)."
  [id]
  (try
    (let [env (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent/id id :seon.agent/state :idle}]}))]
      (when (false? (:seon.db/ok? env))
        (js/console.error
          (str "seon.agent/ensure-idle!: state reset FAILED for " id
               " — agent may stay deaf to new messages. " (pr-str (:seon.db/error env))))))
    (catch :default e
      (js/console.error
        (str "seon.agent/ensure-idle!: state reset THREW for " id
             " — agent may stay deaf to new messages. " (or (.-message e) e))))))

(defn ^:async ^:private close-turn!
  "Internal — the body of `open-turn!` after the open-tx succeeded
   (formerly `with-turn-body!`). Split out so the open-tx envelope
   short-circuit at the call site stays readable. await body-fn, close
   the turn on success, flip to :error on throw. CRITICAL: state ALWAYS
   returns to :idle on EVERY exit (close success OR close FAILURE OR
   throw) — a missed reset leaves the wake guard permanently blocked and
   the agent deaf."
  [id id-of-turn body-fn]
  (try
    (let [result (await (body-fn))
          close  (await
                   (db/transact!
                     {:seon.db/tx-data
                      [(merge {:seon.agent.turn/id id-of-turn :seon.agent.turn/status :done}
                              (select-keys result [:seon.agent.turn/status
                                                   :seon.agent.turn/llm-retries
                                                   :seon.agent.turn/llm-usage
                                                   :seon.agent.turn/llm-meta
                                                   :seon.agent.turn/debug-dir]))
                       {:seon.agent/id id :seon.agent/state :idle}]}))]
      ;; A4: db/transact! returns an envelope, never throws. If the
      ;; combined close-tx FAILED (e.g. a folded telemetry attr won't
      ;; bridge), the agent state was NOT reset — funnel through the
      ;; minimal state-only failsafe so the wake guard never stays
      ;; blocked. The turn record may stay :running, but the agent
      ;; recovers (the failure is logged loudly for diagnosis).
      (when (false? (:seon.db/ok? close))
        (js/console.error
          (str "seon.agent/close-turn!: turn close-tx FAILED for "
               id " turn " id-of-turn " — forcing :idle. "
               (pr-str (:seon.db/error close))))
        (await (ensure-idle! id)))
      result)
    (catch :default e
      ;; Mark the turn :error AND reset state. If the combined tx fails
      ;; (e.g. the turn entity is gone), the failsafe still forces :idle
      ;; so the agent never goes deaf on a throwing turn.
      (let [env (try
                  (await (db/transact!
                           {:seon.db/tx-data
                            [{:seon.agent.turn/id id-of-turn :seon.agent.turn/status :error}
                             {:seon.agent/id id :seon.agent/state :idle}]}))
                  (catch :default _ {:seon.db/ok? false}))]
        (when (false? (:seon.db/ok? env))
          (await (ensure-idle! id))))
      (throw e))))

(defn ^:async ^:private ask-and-eval-reply!
  "Internal — the successful-LLM-reply half of `ask-and-eval!`: parse
   the reply and eval-batch the forms. The raw reply is NOT folded into a
   self→self message anymore (agent-fsm redesign 2026-06-23, §5: an
   agent's notes to itself are eval narration — :seon.eval/narration on
   the per-form evals — never a message row; the verbatim raw text still
   lands on disk via debug capture below).

   `id` / `turn-idx` / `id-of-turn` are LOCALS threaded down from
   `run-turn!` (captured before the LLM await — NOT re-read from
   AsyncLocalStorage post-await), so debug capture pairs this verbatim
   reply with the same turn's prompt by construction. When capture is
   ON, the returned map carries `:seon.agent.turn/debug-dir` (pointer)."
  [resp id id-of-turn turn-idx compile-state]
  (let [reply-text (or (:text resp) "")
        ;; Verbatim raw reply capture — response.txt (even when blank,
        ;; closing the blank-output gap) + response.edn (the resp map,
        ;; round-trips into a fixture). No-op + nil when capture is off.
        debug-dir  (debug/capture-response! id turn-idx id-of-turn
                                            reply-text resp)
        parsed     (repl/parse-forms reply-text)
        batch      (await (seval/eval-batch! compile-state parsed
                                             (home-ns id) id id-of-turn))]
    (cond->
      ;; ATTEMPTED forms (ok + failed), not just n-ok: the loop's
      ;; zero-forms stop policy means "prose only, no progress
      ;; possible" — NOT "every form errored". Counting only n-ok
      ;; ended the loop when a turn's sole eval failed, so the agent
      ;; idled WITHOUT EVER SEEING the error and never replied (gym
      ;; S-12, 2026-06-10: B's one consult query used
      ;; clojure.string/includes? inside :where — eval error, n-ok 0,
      ;; silent idle). A failed eval must yield a next turn that shows
      ;; the error; turns-cap still bounds a stuck agent.
      {:seon.agent/eval-count (+ (:seon.eval/n-ok batch)
                                 (:seon.eval/n-fail batch))}
      ;; Debug capture pointer (projection only; the blob lives under
      ;; the captured dir). Present ONLY when capture wrote — absent off.
      debug-dir (assoc :seon.agent.turn/debug-dir debug-dir))))

(def llm-transport-retry-backoff-ms
  "Backoff before the single transport-error LLM retry (see
   [[ask-and-eval!]]). Small on purpose: a transient \"fetch failed\"
   (DNS blip, dropped connection) usually heals immediately, and a
   long wait just stretches the turn."
  2000)

(defn- transport-error?
  "True when `resp` failed TRANSPORT-shaped: the provider fetch threw
   before any HTTP status (`:seon.ai/transport?` on the error — see
   seon.ai.openai-compat). HTTP 4xx/5xx, parse failures, and wall-clock
   timeouts are NOT transport errors and never retry."
  [resp]
  (true? (get-in resp [:seon.ai/error :seon.ai/transport?])))

(defn ^:async ^:private call-llm!
  "Internal — `(llm-fn prompt-text)` with ONE bounded retry on a
   transport-shaped provider failure (observed live 2026-06-11: a
   transient DeepSeek \"fetch failed\" ended the wake silently).
   Network-shaped errors ONLY — HTTP-status/processing errors and
   timeouts pass straight through. When the retry fires, the returned
   resp carries `:seon.agent.turn/llm-retries 1` so the turn record
   is honest whether the retry recovered or not."
  [id id-of-turn llm-fn prompt-text]
  (let [resp (await (llm-fn prompt-text))]
    (if-not (transport-error? resp)
      resp
      (do
        (log id id-of-turn "llm transport error — one retry in"
             (str llm-transport-retry-backoff-ms "ms — "
                  (get-in resp [:seon.ai/error :seon.ai/msg])))
        (await (js/Promise.
                 (fn [resolve]
                   (js/setTimeout resolve llm-transport-retry-backoff-ms))))
        (assoc (await (llm-fn prompt-text))
               :seon.agent.turn/llm-retries 1)))))

(defn ^:async ask-and-eval!
  "Body of `open-turn!`. Calls the LLM with `prompt-text` (via
   [[call-llm!]] — one bounded retry on transport-shaped provider
   failures, recorded as `:seon.agent.turn/llm-retries`), parses the
   reply, eval-batches the forms (each as a `:seon.agent.turn/evals`
   component via Platform's eval-batch!), and returns
   `{:seon.agent/eval-count n}` (plus optional telemetry) for
   `open-turn!` to fold into the close-tx. An LLM-call failure
   (`:seon.ai/error` on the response) NEVER closes `done [0 ok]` — it
   closes the turn `:status :error` (the turn record carries the error
   signal; render derives a system line from the :error status — no
   self→self message row, agent-fsm §5). The failure is logged loudly."
  [{:seon.agent/keys [id llm-fn compile-state]
    :seon.agent.turn/keys  [id-of-turn turn-idx prompt-text]}]
  (let [resp    (await (call-llm! id id-of-turn llm-fn prompt-text))
        retries (:seon.agent.turn/llm-retries resp)
        ;; #25 tier-2: the provider's structured usage + unrecognized
        ;; top-level fields ride under :seon.ai/raw (the adapter's full
        ;; response). Both ABSENT on a stub-LLM turn or when the
        ;; provider returns neither — optional-is-absent.
        raw     (:seon.ai/raw resp)
        usage   (:seon.ai/usage raw)
        pfields (:seon.ai/provider-fields raw)]
    (if-let [err (:seon.ai/error resp)]
      (do
        ;; Fail-loud: an LLM-call failure no longer leaves a self→self
        ;; message row — the turn closes :error (the render derives the
        ;; human-visible line from that). Name WHY here so a silent
        ;; provider failure is never invisible in the logs.
        (log id turn-idx "llm error — turn :error"
             (str (when retries (str "(after " retries " retry) "))
                  (:seon.ai/msg err)))
        (cond->
          {:seon.agent/eval-count 0
           :seon.agent.turn/status :error}
          retries (assoc :seon.agent.turn/llm-retries retries)))
      (cond-> (await (ask-and-eval-reply! resp id id-of-turn turn-idx compile-state))
        retries     (assoc :seon.agent.turn/llm-retries retries)
        ;; EDN-stringified (mirrors llm-meta) — :map is unbridgeable, see
        ;; the :seon.agent.turn/llm-usage register! note above.
        (seq usage) (assoc :seon.agent.turn/llm-usage (pr-str usage))
        (seq pfields) (assoc :seon.agent.turn/llm-meta (pr-str pfields))))))

(defn ^:async run-turn!
  "v1.md §6.1 — one full turn end-to-end. Map-in / map-out.

   Input keys:
     :seon.agent/id             agent id string
     :seon.agent/llm-fn         ctx-string -> Promise<{:text \"…\"}>
     :seon.agent/compile-state  bootstrap compile-state

   Wraps the whole pipeline in a `with-tx-context` scope so every
   transact (including the per-form txs inside `eval-batch!`)
   auto-tags with the full causality bundle.

   Returns the closed turn entity pulled with messages + evals
   inlined (one pull = full turn, per v1.md §9 acceptance criterion
   11), plus `:seon.agent/eval-count`. On catastrophic error (LLM
   throw, eval engine crash) returns
   `{:seon.agent.turn/status :error :seon.error/data <str>}`."
  [{:seon.agent/keys [id llm-fn compile-state]}]
  (let [session    (await (ensure-session! id))
        session-id (:seon.agent.session/id session)
        turn-id    (db/new-id!)
        turn-idx   (turn-index session-id)
        ;; OPTIONAL embedding-retrieval prefetch (P2-D, env-gated default-OFF):
        ;; when SEON_EMBED is UNSET this is exactly `(render-prompt
        ;; id)` — byte-identical to the pre-retrieval path. When set, the wire
        ;; KNN is awaited here + stashed so the sync :relevant-source section
        ;; reads it (the async seam — `assemble-context` stays sync).
        prompt     (await (prefetch-and-render-prompt! id))
        ;; DEBUG representation = the FULL prompt the agent sees: soul
        ;; system block + boundary + ctx, via the ONE shared composer
        ;; (`ai/debug-full-prompt`) the inspector preview also uses. This
        ;; feeds the disk capture and the persisted `prompt-chars`. It is
        ;; NEVER sent to the LLM — `prompt` (block2/ctx) is; the adapters
        ;; add the soul system block themselves. Decoupling these is what
        ;; keeps the soul from DOUBLING in the real call.
        full-prompt (ai/debug-full-prompt {:seon.ai/ctx prompt})
        ;; Blob tier — full prompt to disk, GATED behind the debug-capture
        ;; flag (seon.debug). OFF by default (stops the unbounded
        ;; logs/prompts growth); when ON, prompt.txt lands in the unified
        ;; per-turn dir <SEON_DEBUG_CAPTURE_DIR>/<id>/<turn-idx>-<turn-id>/.
        ;; The turn datom still carries chars + the pointer (prompt-file →
        ;; the captured path) WHEN capturing — absent when off (gym opts
        ;; in via debug/set-override! so its prompt-blob evidence survives).
        prompt-file (debug/capture-prompt! id turn-idx turn-id full-prompt)]
    (log id turn-idx "open" turn-id "+" (count prompt) "ctx-chars")
    (try
      (let [result (await
                     ;; Two nested ALS scopes — tx-context carries the
                     ;; full causality bundle into every transact's
                     ;; tx-meta; agent-id-als is the core read by
                     ;; non-tx code (inspectors, section fns, web
                     ;; handlers) via `(seon.db/current-agent-id)`.
                     (db/with-agent id
                       (fn []
                         (db/with-tx-context
                           {:seon.db/agent-id   id
                            :seon.db/session-id session-id
                            :seon.db/turn-id    turn-id
                            :seon.db/origin     :system}
                           (fn []
                             (open-turn!
                               (cond->
                                 ;; DEBUG: open-turn! uses prompt-text ONLY
                                 ;; to derive the stored `prompt-chars`
                                 ;; projection — so it gets the FULL prompt
                                 ;; (soul + boundary + ctx). It does NOT
                                 ;; feed the LLM (ask-and-eval! below gets
                                 ;; its OWN block2 `prompt`), so no doubling.
                                 ;; open-turn! reads the agent's current
                                 ;; :seon.agent/wake itself and stamps it.
                                 {:seon.agent/id           id
                                  :seon.agent.session/id-of-session session-id
                                  :seon.agent.turn/id-of-turn    turn-id
                                  :seon.agent.turn/prompt-text   full-prompt}
                                 prompt-file
                                 (assoc :seon.agent.turn/prompt-file prompt-file))
                               #(ask-and-eval! {:seon.agent/id            id
                                                :seon.agent/llm-fn        llm-fn
                                                :seon.agent/compile-state compile-state
                                                :seon.agent.turn/id-of-turn     turn-id
                                                :seon.agent.turn/turn-idx       turn-idx
                                                :seon.agent.turn/prompt-text    prompt})))))))
            n-ok (or (:seon.agent/eval-count result) 0)]
        (log id turn-idx (name (or (:seon.agent.turn/status result) :done)) n-ok
             (if (:seon.agent.turn/status result) "llm-error" "ok"))
        (assoc (db/pull {:seon.db/pull-pattern
                         '[* {:seon.agent.turn/evals [*]}]
                         :seon.db/ref [:seon.agent.turn/id turn-id]})
               :seon.agent/eval-count n-ok))
      (catch :default e
        (log id turn-idx "run-turn! error" (str e))
        (try
          (await (db/transact!
                   {:seon.db/tx-data
                    [{:seon.agent/id id :seon.agent/state :idle}]}))
          (catch :default _ nil))
        {:seon.agent.turn/status :error
         :seon.error/data (str e)}))))

(schema/register! ::unanswered-live-inbound-request
  [:map [::id ::id]])

(defn- query-count
  "ffirst of a `(count ?x)` query — the row count (0 when empty)."
  [q args]
  (or (ffirst (db/query {:seon.db/query q :seon.db/args args})) 0))

(defn- live-inbound-count
  "How many LIVE inbound messages for `my-eid` are awaiting an answer —
   to ∋ me, from ≠ me, hops < `warn/hop-cap`, origin ∉ {:core} (#43),
   handled? ≠ true (I-1). 0 when `my-eid` is nil. The SAME exclusions
   as the wake gate `inbound-msg-datom?`: a message that does not WAKE
   must not be COUNTED here either (mirrors seon.ctx/turns-since-inbound).
   These are the only messages the stop-policy treats as questions."
  [my-eid]
  (if my-eid
    (query-count
      '[:find (count ?m)
        :in $ ?me ?cap
        :where
        [?m :seon.agent.message/to ?me]
        [?m :seon.agent.message/from ?f]
        [(not= ?f ?me)]
        ;; hop-exhausted messages never wake a loop and must not be
        ;; counted as questions (mirrors seon.ctx/turns-since-inbound).
        [(get-else $ ?m :seon.agent.message/hops 0) ?h]
        [(< ?h ?cap)]
        ;; :core substrate nudges (tile recovery, sent FROM the
        ;; user-ref) never wake a loop and are not questions (#43).
        ;; Legacy rows have no origin ⇒ default to :human (those
        ;; predate :core; all were human/agent).
        [(get-else $ ?m :seon.agent.message/origin :human) ?o]
        [(not= ?o :core)]
        ;; I-1: a tx-hook-consumed message (handled? = true) neither
        ;; wakes (inbound-msg-datom?) nor counts as a question.
        [(get-else $ ?m :seon.agent.message/handled? false) ?handled]
        [(not= ?handled true)]]
      [my-eid warn/hop-cap])
    0))

(defn- user-facing-reply-count
  "How many USER-FACING replies `my-eid` has emitted — from = me with
   at least one recipient ≠ me (a `reply!` or a `message!` consult) AND
   origin ∉ {:core}. EXCLUDED so the count can't be skewed: assistant
   self-notes (from = to = me — the per-turn thinking message, the
   cap-hit note, and the empty-completion give-up line are all
   from = to = me, dropped by the recipient ≠ me clause) and
   :core-origin outbound nudges. 0 when `my-eid` is nil. One genuine
   answer to a human/peer = one count."
  [my-eid]
  (if my-eid
    (query-count
      '[:find (count ?m)
        :in $ ?me
        :where
        [?m :seon.agent.message/from ?me]
        [?m :seon.agent.message/to ?t]
        [(not= ?t ?me)]
        ;; :core outbound nudges are substrate, not answers. Genuine
        ;; replies/consults have no origin (legacy/test) or :agent ⇒
        ;; default to :agent so only :core is excluded.
        [(get-else $ ?m :seon.agent.message/origin :agent) ?o]
        [(not= ?o :core)]]
      [my-eid])
    0))

(defn unanswered-live-inbound?
  "THE loop stop-policy predicate — TRUE when the agent owes at least
   one more answer, so `run-agentic-loop!` must keep running. The test
   is a COUNT comparison, NOT a timestamp comparison:

     (count LIVE UNANSWERED inbounds) > (count user-facing REPLIES)

   - INBOUNDS counted ([[live-inbound-count]]): to ∋ me, from ≠ me,
     hops < `warn/hop-cap`, origin ∉ {:core}, handled? ≠ true — the
     SAME exclusions as the wake gate `inbound-msg-datom?`.
   - REPLIES counted ([[user-facing-reply-count]]): from = me to a
     non-self recipient, origin ∉ {:core} — EXCLUDING self→self notes
     (the per-turn thinking message, cap-hit note, empty-completion
     give-up line are all from = to = me), :core nudges.

   Why count, not timestamp: a timestamp comparison (`is there an
   outbound STRICTLY AFTER the latest inbound?`) silently DROPS a
   message — when a 2nd inbound arrives mid-wake and the reply to the
   1st is emitted at a time AFTER the 2nd's timestamp, that one reply
   reads as answering BOTH and the 2nd is lost forever (the live-acme
   message-drop regression, 4/4 trials). Ordering by `:at` cannot tell
   which inbound a given reply answered; counting can: each genuine
   answer balances exactly one question.

   This is the ONE question the loop asks, and it subsumes every prior
   phrasing:
     - not-yet-replied this wake — 1 inbound, 0 replies ⇒ 1 > 0 ⇒ recur.
     - a NEW inbound arrived mid-wake (the old #49 'drain') — 2 inbounds,
       1 reply ⇒ 2 > 1 ⇒ recur; the agent answers it, 2 replies ⇒ 2 > 2
       false ⇒ halt. No baseline/latch/drain bookkeeping — the unanswered
       count keeps the loop alive, the balanced count stops it (no drop,
       no duplicate).
     - balanced — N inbounds answered by N replies ⇒ N > N false ⇒ halt
       :replied. A long balanced conversation stays at 0 net ⇒ halts.
   Fully derived from the message log — nothing stored, nothing to clear
   (docs/seon/concepts/reactive-context)."
  {:malli/schema [:=> [:cat ::unanswered-live-inbound-request] :boolean]}
  [{::keys [id]}]
  (let [my-eid (:db/id (db/entity {:seon.db/ref [:seon.agent/id id]}))]
    (> (live-inbound-count my-eid)
       (user-facing-reply-count my-eid))))

(def max-empty-reprompts
  "Bound on CONSECUTIVE re-prompts after a turn that produced no
   visible output (zero evals, zero outbound, no reply since the
   inbound — see [[run-agentic-loop!]]). Two re-prompts, then the wake
   ends WITH a chat-visible system line — the agent never just looks
   dead, and a provider stuck returning empty completions can't burn
   turns forever."
  2)

(def empty-completion-nudge
  "The core-origin transcript note injected before an
   empty-completion re-prompt (self→self message — appears in the
   agent's transcript next turn, wakes nothing, never reads as
   user-directed). Same bracketed-core-note shape as the
   turn-cap note."
  (str "[previous completion produced no visible output — no forms"
       " were evaluated and nothing was sent. Respond with Clojure"
       " forms to evaluate, or reply to your human via"
       " (seon.agent/reply! {:seon.agent.message/content \"…\"}).]"))

(defn- empty-completion-give-up-text
  "Content of the chat-visible system line stored when the empty-turn
   guard gives up (the ask-6 pattern: an :error turn carrying a
   self-message renders as a `::system` chat line —
   seon.render.chat/provider-failure-rows appends
   \"— it will resume on your next message\")."
  [attempts]
  (str "completion produced no visible output (0 forms, no reply) on "
       attempts " consecutive turns despite re-prompts — wake ended"))

(defn ^:async run-agentic-loop!
  "Per v1.md §6.2 — multi-turn driver. Calls `run-turn!` repeatedly
   until a stop policy fires. The stop policy is ONE question —
   [[unanswered-live-inbound?]] — plus three guards.

   Stop policies (in cond order):
     1. Last turn errored → halt `:error`.
     2. `turns-since-inbound` reached `(turns-cap id)` → a self→self
        cap note, then halt `:cap-hit`. Checked BEFORE the empty-turn
        guard so re-prompts (which consume turns) can never push past
        the cap.
     3. NOT [[unanswered-live-inbound?]] → halt `:replied`. ONE
        predicate is the whole stop-policy, a COUNT comparison:
        (count live unanswered inbounds) > (count user-facing replies).
        When they balance, every question has an answer — the
        conversation ball is in the other court. A new inbound re-wakes
        via the kick trigger; a mid-wake arrival lifts the inbound count
        above the reply count here and keeps the loop going until it too
        is answered (no baseline/latch/drain bookkeeping — counting
        subsumes it: each answer balances exactly one question, so
        nothing is dropped and nothing is duplicated). SHARP EDGE: each
        outbound to a non-self recipient (a `message!` consult counts)
        balances one inbound. A replied agent that then emits an empty
        completion halts here and does NOT re-prompt.
     4. EMPTY-TURN GUARD (standalone — reached only with a live
        unanswered inbound; downstream ask 20): a turn that produced
        ZERO evals (the deepseek/anthropic thinking-only shape — all
        tokens in the reasoning field, empty visible content) injects
        [[empty-completion-nudge]] as a self→self note and re-prompts,
        at most [[max-empty-reprompts]] consecutive times (any turn with
        forms resets the streak), then ends the wake by flipping the
        last turn to `:seon.agent.turn/status :error` with a stored
        self-message (the ask-6 LLM-error shape) so the human sees a
        `::system` chat line instead of silence. Halt marker:
        `:seon.agent/halt :no-visible-output`.
     5. :else → recur (a turn with forms while a live inbound is still
        unanswered; resets the empty streak).

   The double-wake guard is external: the kick handler's `:running`
   state guard + `!kick-scheduled` latch ensure a fresh inbound can't
   stack new loops on an in-flight one. A message that arrives mid-wake
   while the loop is running is picked up at the next halt check (it is
   an unanswered live inbound there)."
  [{:seon.agent/keys [id] :as input}]
  (loop [empty-streak 0]
    (let [result   (await (run-turn! input))
          since-in (turns-since-inbound {:seon.agent/id id})
          status   (:seon.agent.turn/status result)
          n-forms  (or (:seon.agent/eval-count result) 0)
          turn-idx (turn-index (:seon.agent.session/id (current-session id)))]
      (cond
        (= :error status)
        result

        (>= since-in (turns-cap id))
        (do (await
              ;; Self→self note (from = to = me): lands in the agent's
              ;; own derived conversation, wakes nothing (from ≠ me
              ;; fails at the trigger).
              (msg/message!
                {:seon.agent.message/from    [:seon.agent/id id]
                 :seon.agent.message/to      [[:seon.agent/id id]]
                 :seon.agent.message/content
                 (str "[turn cap hit — " (turns-cap id)
                      " agentic turns since the last inbound message"
                      " without a final reply. Ask again or"
                      " narrow the question.]")}))
            (assoc result :seon.agent/halt :cap-hit))

        ;; THE stop-policy: once the latest live inbound has an answer
        ;; (an outbound strictly after it), the wake is complete —
        ;; regardless of this turn's output (a replied agent that then
        ;; emits an empty completion does NOT re-prompt). A new inbound
        ;; re-wakes via the kick trigger; a mid-wake arrival is an
        ;; unanswered live inbound here and keeps the loop going.
        (not (unanswered-live-inbound? {::id id}))
        (do (log id turn-idx "halt" "replied — wake complete")
            (assoc result :seon.agent/halt :replied))

        ;; EMPTY-TURN GUARD — standalone (no replied interaction; we only
        ;; reach it with a live unanswered inbound): zero forms ⇒ bump
        ;; the streak + re-prompt; over the bound ⇒ halt
        ;; :no-visible-output. A turn WITH forms falls through to the
        ;; recur (it made progress on the still-unanswered inbound).
        (zero? n-forms)
        (if (< empty-streak max-empty-reprompts)
          (do (log id turn-idx "empty turn"
                   (str "no visible output (streak "
                        (inc empty-streak) "/" (inc max-empty-reprompts)
                        ") — nudge + re-prompt"))
              (await (msg/message!
                       {:seon.agent.message/from    [:seon.agent/id id]
                        :seon.agent.message/to      [[:seon.agent/id id]]
                        :seon.agent.message/content empty-completion-nudge}))
              (recur (inc empty-streak)))
          (let [attempts (inc empty-streak)]
            (log id turn-idx "halt"
                 (str "no visible output after " attempts
                      " attempts — wake end"))
            ;; Flip THIS turn to :error so render derives a chat-visible
            ;; `::system` line from the turn record. The give-up self→self
            ;; MESSAGE is DELETED (agent-fsm §5: `:seon.agent.turn/messages`
            ;; attr removed; an agent's notes-to-self are eval narration,
            ;; never a message row). U4 replaces this whole halt branch with
            ;; a clean `:idle` (no `:error`); until then the turn-status flip
            ;; preserves the existing chat-surfacing.
            (await (db/transact!
                     {:seon.db/tx-data
                      [{:seon.agent.turn/id     (:seon.agent.turn/id result)
                        :seon.agent.turn/status :error}]}))
            (assoc result
                   :seon.agent.turn/status :error
                   :seon.agent/halt :no-visible-output)))

        ;; A turn with forms while a live inbound is still unanswered →
        ;; keep working (resets the empty streak).
        :else
        (recur 0)))))

;; ============================================================
;; v1 §5 render-side helpers + section fns + composer.
;;
;; All purely additive against existing run-turn! / V0 ctx machinery.
;; Read-only against the DB; nothing transacts state changes except
;; reset-ctx! / update-ctx! (which the agent invokes explicitly).
;;
;; Wire-up to run-turn! (replace render/ai-render call with
;; assemble-context) is task #6 and lands after Platform's Patch 1/2
;; for eval-batch! so the work doesn't conflict.
;; ============================================================


;; ------------------------------------------------------------
;; Layout verbs — reset-ctx! restores core defaults; update-ctx!
;; threads f over the current :seon.agent/ctx and retract-then-adds
;; the result. Component-cardinality-many means the retract is needed
;; to drop the old ctx entities before transacting new ones (per
;; v1.md §5.4 — cardinality-many ref attrs accumulate on upsert).
;; ------------------------------------------------------------


(defn ^:async reset-ctx!
  "Restore the core-default ctx layout for `agent-id` by RETRACTING
   the stored :seon.agent/ctx override (cascade-retracts the existing
   :seon.ctx entities via component semantics). With no stored ctx,
   `assemble-context` falls back to the CODE default
   (`core-default-ctx`) — so the agent tracks every future layout
   change automatically instead of freezing a stored copy of today's
   default (the pre-2026-06-10 behavior, which left prior agents on
   stale layouts whenever the default evolved)."
  [agent-id]
  (await (db/transact!
           {:seon.db/tx-data
            [[:db/retract [:seon.agent/id agent-id] :seon.agent/ctx]]})))

(defn ^:async update-ctx!
  "Apply `f` to the current ctx vector for `agent-id`; transact the
   result. `f` receives the existing seq of :seon.ctx entity maps
   (component-inlined via pull) and returns a vector of ctx maps.
   Use to add/remove sections or change priorities without blowing
   away the whole layout."
  [agent-id f]
  (let [current (ctx-entities {:seon.agent/id agent-id})
        new-ctx (vec (f current))]
    (await (db/transact!
             {:seon.db/tx-data
              [[:db/retract [:seon.agent/id agent-id] :seon.agent/ctx]
               {:seon.agent/id agent-id
                :seon.agent/ctx new-ctx}]}))))

;; ============================================================
;; Self-context verbs (agent-self-context spec, 2026-06-10) — the
;; validated path onto YOUR OWN `:seon.agent/ctx` sections. Same
;; envelope discipline as seon.agent.todo: errors are values; blank
;; text is refused with a guiding message; unknown name on remove
;; names the current section list. Default scope = the calling agent;
;; explicit :seon.agent/id allowed (a human or another agent can
;; configure an agent — it is all just transacts; the verb is the
;; validated path).
;; ============================================================

(schema/register! ::add-section-request
  [:map
   [:seon.ctx/name     :seon.ctx/name]
   [:seon.ctx/priority {:optional true} :seon.ctx/priority]
   [:seon.render/ai    :seon.render/ai]
   [:seon.render/html  {:optional true} :seon.render/html]
   [:seon.agent/id     {:optional true} :seon.agent/id]])

(schema/register! ::remove-section-request
  [:map
   [:seon.ctx/name :seon.ctx/name]
   [:seon.agent/id {:optional true} :seon.agent/id]])

;; Shared response shapes for the section verbs (add-section! /
;; remove-section!), referenced by ::section-response below.
(schema/register! ::ok?   :boolean)
(schema/register! ::error :string)

(schema/register! ::section-response
  [:or
   [:map
    [::ok?          [:= true]]
    [:seon.ctx/name :seon.ctx/name]]
   [:map
    [::ok?   [:= false]]
    [::error ::error]]])

(def ^:private default-section-priority
  "Priority when add-section! is called without one — between
   :open-todos (45) and :transcript (50), so an unplaced section lands
   late in the static-ish region without displacing the transcript."
  46)

(defn ^:async add-section!
  "Add or update ONE section of your own context — upsert-by-name
   within your `:seon.agent/ctx` vector (re-adding a name replaces that
   entry, so iterating on a section doesn't accumulate copies). A name
   that collides with a core default OVERRIDES it (deliberate,
   visible as data). `:seon.render/ai` is a string (rendered verbatim —
   doctrine, notes-to-self) or a qualified symbol of a fn called at
   every render with {:seon.db/db … :seon.agent/entity …}.

     (seon.agent/add-section!
       {:seon.ctx/name :doctrine :seon.ctx/priority 15
        :seon.render/ai \"Always reconcile against my.finance.ledger.\"})
     ;; => {:seon.agent/ok? true :seon.ctx/name :doctrine}"
  {:malli/schema [:=> [:cat ::add-section-request] ::section-response]}
  [{nm :seon.ctx/name pri :seon.ctx/priority slot :seon.render/ai
    html :seon.render/html id :seon.agent/id}]
  (let [id (or id (db/current-agent-id))]
    (cond
      (nil? id)
      {::ok? false
       ::error (str "add-section!: no agent in scope — pass "
                    ":seon.agent/id or call inside (seon.db/with-agent id …).")}

      (not (keyword? nm))
      {::ok? false
       ::error ":seon.ctx/name must be a keyword (e.g. :doctrine)."}

      (and (string? slot) (str/blank? slot))
      {::ok? false
       ::error (str "blank section text refused — write the text you "
                    "want rendered every turn, or remove-section! to "
                    "drop the section.")}

      (not (or (string? slot) (qualified-symbol? slot)))
      {::ok? false
       ::error (str ":seon.render/ai must be a string (verbatim text) or "
                    "a fully-qualified symbol of a section fn, got "
                    (pr-str slot) ".")}

      :else
      (let [current (ctx/ctx-entities {:seon.agent/id id})
            section (cond-> {:seon.ctx/name     nm
                             :seon.ctx/priority (or pri default-section-priority)
                             :seon.render/ai    slot}
                      (some? html) (assoc :seon.render/html html))
            new-ctx (conj (->> current
                               (remove #(= nm (:seon.ctx/name %)))
                               (mapv #(dissoc % :db/id)))
                          section)
            res     (await
                      (db/transact!
                        {:seon.db/tx-data
                         [[:db/retract [:seon.agent/id id] :seon.agent/ctx]
                          {:seon.agent/id  id
                           :seon.agent/ctx new-ctx}]}))]
        (if (false? (:seon.db/ok? res))
          {::ok? false
           ::error (str "add-section! transact failed: "
                        (:seon.error/message (:seon.db/error res)))}
          {::ok? true :seon.ctx/name nm})))))

(defn ^:async remove-section!
  "Remove ONE of your own sections by name. Unknown name → error
   naming the current section list (errors are values)."
  {:malli/schema [:=> [:cat ::remove-section-request] ::section-response]}
  [{nm :seon.ctx/name id :seon.agent/id}]
  (let [id (or id (db/current-agent-id))]
    (cond
      (nil? id)
      {::ok? false
       ::error (str "remove-section!: no agent in scope — pass "
                    ":seon.agent/id or call inside (seon.db/with-agent id …).")}

      :else
      (let [current (ctx/ctx-entities {:seon.agent/id id})
            names   (mapv :seon.ctx/name current)]
        (if-not (some #{nm} names)
          {::ok? false
           ::error (str "no section named " nm " — your sections: "
                        (pr-str names) ".")}
          (let [new-ctx (->> current
                             (remove #(= nm (:seon.ctx/name %)))
                             (mapv #(dissoc % :db/id)))
                res     (await
                          (db/transact!
                            {:seon.db/tx-data
                             (into [[:db/retract [:seon.agent/id id]
                                     :seon.agent/ctx]]
                                   (when (seq new-ctx)
                                     [{:seon.agent/id  id
                                       :seon.agent/ctx new-ctx}]))}))]
            (if (false? (:seon.db/ok? res))
              {::ok? false
               ::error (str "remove-section! transact failed: "
                            (:seon.error/message (:seon.db/error res)))}
              {::ok? true :seon.ctx/name nm})))))))

(defn ^:async set-purpose!
  "Pin or update why you exist — sugar over a one-attr transact to
   your own entity (`:seon.agent/purpose`, rendered every turn in
   `<your-entity>`). Equivalent to the lookup-ref transact the
   creation tutorial demonstrates."
  {:malli/schema [:=> [:cat [:map
                             [:seon.render/ai :string]
                             [:seon.agent/id {:optional true} :string]]]
                  ::section-response]}
  [{text :seon.render/ai id :seon.agent/id}]
  (let [id (or id (db/current-agent-id))]
    (if (nil? id)
      {::ok? false
       ::error (str "set-purpose!: no agent in scope — pass "
                    ":seon.agent/id or call inside (seon.db/with-agent id …).")}
      (let [res (await (db/transact!
                         {:seon.db/tx-data
                          [{:seon.agent/id      id
                            :seon.agent/purpose text}]}))]
        (if (false? (:seon.db/ok? res))
          {::ok? false
           ::error (str "set-purpose! transact failed: "
                        (:seon.error/message (:seon.db/error res)))}
          {::ok? true :seon.ctx/name :purpose})))))
