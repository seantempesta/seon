(ns seon.agent
  "The agent RECORD + the agent-facing verbs — 'what an agent IS' (the loop
   that runs it lives in [[seon.agent.fsm]], one turn in [[seon.agent.turn]]).

   The agent operates as a real REPL: bootstrap-CLJS evaluates its forms,
   results land in a per-agent home namespace (`my.agent.<id>`) as live
   values keyed by eval-id (via [[seon.eval]]), and durable records land as
   `:seon.eval` entities. The agent calls the real `seon.db/*` APIs directly.

   This namespace owns:
     - the `:seon.agent/*` schemas (id/purpose/state/wake/parent/
       max-turns-per-loop + the entity map), plus the `:seon.eval/*`,
       `:seon.ns/*`, `:seon.fn/*`, `:seon.schema/*` corpus schemas
       (`:seon.agent.message/*` lives in [[seon.agent.message]],
       `:seon.agent.session/*` + `:seon.agent.turn/*` in
       [[seon.agent.turn]], `:seon.ctx/*` in [[seon.ctx]])
     - state helpers: `current-state` / `set-state!` / `fresh-wake!` /
       `armable-agent-ids` (the FSM coordination seam over seon.db)
     - the lifecycle verbs `wait` / `complete` / `terminate` — each a small
       state transact (the verbs SET state; the loop only READS it)
     - `inbound-msg-datom?` — the wake gate ([[seon.agent.fsm]]'s trigger
       and the transcript head-render both reuse it)
     - `create!` / `boot!` — allocate the agent entity (boot! does NOT arm
       the wake trigger — that's the client boot path, which requires fsm)
     - `message!` / `user-ref` — re-exported from [[seon.agent.message]]
     - `add-section!` / `remove-section!` / `reset-ctx!` / `update-ctx!` —
       the agent's ctx-layout editing surface

   Agent-id resolution: read APIs take `:seon.agent/id` and fall back to
   `(seon.db/current-agent-id)` when unset (the boot/run path wraps calls in
   `(seon.db/with-agent id …)`).

   ## State machine

   `:seon.agent/state` values (agent-fsm redesign 2026-06-23):
     :idle       — neutral / between work; wakeable → :active
     :active     — a loop is running; new inbounds are picked up by the
                   running loop's sliding cap, not a fresh wake (the
                   handler sees :active and skips)
     :waiting    — parked via (agent/wait …); wakeable → :active
     :completed  — finished via (complete …) / no-forms; wakeable → :active
     :terminated — orchestrator kill; UNWAKEABLE (change state first)

   The wake handler flips a wakeable agent → :active and starts a loop; the
   loop resets it to :idle on a clean exit (see [[seon.agent.fsm/run-loop!]]).

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
    [seon.ctx :as ctx]
    [seon.ctx.namespaces :as ctx-namespaces]
    [seon.ctx.prompt :as ctx-prompt]
    [seon.ctx.transcript :as ctx-transcript]
    [seon.ctx.warnings :as ctx-warnings]
    [seon.ctx.your-entity :as ctx-your-entity]
    [seon.db :as db]
    [seon.schema :as schema]))

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

;; The :seon.agent.session/* + :seon.agent.turn/* schemas + the turn
;; machinery moved to [[seon.agent.turn]] (its data-owner) in the agent-fsm
;; carve (U4). :seon.agent/sessions stays here (it is a :seon.agent/* attr —
;; the agent record owns the ref TO its sessions; the sessions own their
;; turns).
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
;; The wake GATE — the one predicate the FSM trigger ([[seon.agent.fsm]])
;; and the transcript head-render both reuse so a message wakes (and renders
;; as an inbound) under exactly ONE rule. The loop + the trigger themselves
;; live in seon.agent.fsm; this gate stays here (the agent owns 'what counts
;; as a message TO me').
;; ============================================================

(defn inbound-msg-datom?
  "True iff this added `:seon.agent.message/to` datom targets `my-eid` from a
   DIFFERENT sender with a WAKING origin (∈ {:human :agent}). The to-check is
   load-bearing: every agent installs the wake listener, so without it ONE
   message wakes EVERY agent's loop. The from-check (`from ≠ me`) stops an
   agent's own writes from re-waking itself. The origin-check (#43) stops a
   :core substrate nudge from waking an idle agent. A tx-hook-consumed
   message (`handled? = true`) does not wake. Legacy rows have no origin attr
   — treat absent origin as waking (those predate :core, all human/agent)."
  [db {eid :seon.db/e target :seon.db/v} my-eid]
  (and (= target my-eid)
       (let [msg (db/entity {:seon.db/db db :seon.db/ref eid})]
         (and (not= my-eid (:db/id (:seon.agent.message/from msg)))
              (not= :core (:seon.agent.message/origin msg))
              (not (true? (:seon.agent.message/handled? msg)))))))

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
  "Create the agent entity. Map-in / map-out.

   Input:
     :seon.agent/id             agent id string (REQUIRED — pass the id
                                minted by the caller; no implicit default)
     :seon.agent/llm-fn         ctx-string -> Promise<{:text \"…\"}> (kept in
                                the signature for the caller; not used here)
     :seon.agent/compile-state  defonce'd bootstrap compile-state (idem)

   Does NOT arm the wake trigger — that is the CLIENT boot path's job
   (`seon.agent.fsm/install-wake-trigger!`), so `seon.agent` need not depend
   on `seon.agent.fsm` (acyclic). Returns `{:seon.agent/id _ :seon.agent/ns _}`.
   On a FAILED create! the db error envelope propagates as-is (errors are
   values): there is NO agent entity, so the caller must not arm a trigger."
  [{:seon.agent/keys [id purpose]}]
  (let [res (await (create! {:seon.agent/id id :seon.agent/purpose purpose}))]
    (if (false? (:seon.db/ok? res))
      ;; create! already console.error'd the transact failure; name the
      ;; boot path too, then hand the envelope up — callers branch.
      (do (js/console.error
            (str "seon.agent/boot! ABORTED for " id
                 " — create! failed; propagating the error envelope"))
          res)
      (let [{:seon.agent/keys [id]} res]
        {:seon.agent/id id
         :seon.agent/ns (home-ns id)}))))

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
;; The agent's ctx-LAYOUT editing surface — read-only against the DB except
;; the explicit layout verbs (reset-ctx! / update-ctx! / add-section! /
;; remove-section! / set-purpose!) the agent invokes. The section fns + the
;; composer live in seon.ctx (re-exported above as transitional aliases).
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
