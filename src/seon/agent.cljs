(ns seon.agent
  "Agent runtime — schemas, ctx-rendering, and turn-loop lifecycle.
   This is the single namespace that owns 'what an agent is and how it
   runs.' There is no separate seon.session — the agent IS the unit.

   The agent operates as a real REPL: bootstrap-CLJS evaluates its
   forms, results land in a per-agent home namespace (`seon.agent.<id>`)
   as live values keyed by eval-id (on globalThis, via [[seon.eval]]),
   and durable records land as `:seon.eval` entities in the database.
   The agent calls the real `seon.db/*` APIs directly — no
   `say!`/`done!`/`scratch!` wrappers.

   This namespace owns:
     - the `:seon.agent/*`, `:seon.session/*`, `:seon.turn/*`,
       `:seon.message/*`, `:seon.eval/*`, `:seon.ctx/*`, `:seon.ns/*`,
       `:seon.fn/*`, `:seon.schema/*` schemas
     - `run-turn!`          — one full turn end-to-end (v1.md §6.1).
                              Thin orchestrator: composes
                              `ensure-session!` + `render-prompt` +
                              `with-turn!` + `ask-and-eval!` under one
                              outer `seon.db/with-tx-context` scope
                              so every tx auto-tags with the causality
                              bundle
     - `with-turn!`         — bracketing combinator: opens a turn with
                              prompt-text attached, runs a body thunk,
                              folds its result into the close-tx (so
                              one open-tx + one close-tx covers the
                              whole turn-level write surface; eval
                              batch adds its own per-form txs)
     - `ask-and-eval!`      — body of `with-turn!`: LLM call + parse
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
     - `message!` / `reply!` — THE message write entry points (from/to
                              refs, hops guard, blank-content guard)
     - `boot!`              — wire everything: create entity + install
                              inbound-message trigger + install substrate
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

   `:seon.agent/state` values:
     :idle      — no turn running; ready to be triggered
     :running   — turn in flight; new user messages queue silently
                  (handler sees :running and skips)

   The handler flips :idle → :running before starting a turn, and
   back to :idle when the turn ends. Concurrent kicks during a turn
   no-op — the next kick after the turn ends picks up any messages
   that landed during it.

   ## Prompt assembly

   v1.md §5 — the LLM ctx is built via the render dispatch:

     agent entity → :seon.render/ai slot → eval/lookup-value → call → text

   Default symbol: `'seon.agent/assemble-context`, whose section
   LAYOUT is CODE (`substrate-default-ctx`). A stored `:seon.agent/ctx`
   vector (a cardinality-many component ref to `:seon.ctx` entities),
   when present, OVERRIDES the default. Either way the composer sorts
   by `:seon.ctx/priority`, resolves each entity's `:seon.ctx/fn`
   symbol via `seon.eval/lookup-value`, calls it with the system-input
   map `{:seon.db/db :seon.agent/id :seon.agent/ctx-entity}`, and joins
   the non-blank string results.

   Substrate defaults (`substrate-default-ctx`): nine sections —
   `system`, `capabilities`, `exemplars`, `schema-catalog`,
   `functions-catalog`, `namespace-context`, `warnings`, `transcript`,
   `prompt`.
   The agent customizes by transacting different
   `:seon.ctx` entities into `:seon.agent/ctx` (use `update-ctx!`)
   or by transacting a completely different symbol onto the agent's
   `:seon.render/ai` slot."
  (:require
    [clojure.string :as str]
    [cljs.reader :as edn]
    [seon.db :as db]
    [seon.error.instrument :as einstrument]
    [seon.eval :as seval]
    ;; Read-only fs capability — capabilities-section surfaces the LIVE
    ;; allowed-roots so the agent knows exactly what it may read.
    [seon.fs :as sfs]
    [seon.handlers.fn :as h-fn]
    [seon.handlers.ns :as h-ns]
    [seon.handlers.schema :as h-schema]
    [seon.handlers.test :as h-test]
    [seon.log :as seon-log]
    [seon.render :as render]
    [seon.repl :as repl]
    [seon.schema :as schema]
    [seon.warn :as warn]))

;; ============================================================
;; Schemas — every shape the agent reads or writes.
;;
;; Per spec-05 §22.5 the entity lives at `:seon.agent/*` (formerly
;; `:seon.session/*`). The agent-ns is dropped from the entity — it's
;; deterministic from the id via `home-ns`.
;; ============================================================

(schema/register! :seon.agent/id            [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.agent/state         [:enum :idle :running])
;; v0 :seon.agent/turn-count, :seon.agent/turns-since-inbound,
;; :seon.agent/interrupted? attrs deleted 2026-05-22. turn-count
;; was a holdover that always read 0; turns-since-inbound moved to
;; :seon.session; interrupted? was registered but never written.

;; Cap on consecutive agentic turns per user message. Lives on the
;; agent entity (overridable via transact); defaults to 20 when the
;; attr is absent. Reading from the entity instead of a hardcoded
;; constant makes the cap discoverable + tunable from the agent's
;; own eval.
(schema/register! :seon.agent/turns-cap :int)
(def default-turns-cap 20)

(defn turns-cap
  "Read `:seon.agent/turns-cap` from the agent entity. Returns the
   configured cap or `default-turns-cap` when the attr is absent.
   Use this at every cap-check site so the agent can override the
   default by transacting its own value."
  [agent-id]
  (or (:seon.agent/turns-cap
        (db/entity {:seon.db/ref [:seon.agent/id agent-id]}))
      default-turns-cap))

;; Messaging codified (unit 1.5, 2026-06-09): every stored message is
;; FULLY FORMED — from + to + content + at + id + hops. Identity is the
;; ref (`:seon.message/from` points at the sender entity — a
;; `:seon.user/id` or `:seon.agent/id` entity); `role` and `agent` are
;; RETIRED. "My conversation" is DERIVED: from = me OR to ∋ me.
(schema/register! :seon.message/id      [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.message/content :string)
(schema/register! :seon.message/from    :seon.db/ref)
(schema/register! :seon.message/to      [:vector :seon.db/ref])
(schema/register! :seon.message/at      :inst)
;; Ping-pong guard: 0 when from = the user; agent-originated sends
;; carry waking-message-hops + 1. The wake trigger REFUSES messages
;; whose hops reached `seon.warn/hop-cap` so two agents can't auto-bill
;; an infinite reply chain.
(schema/register! :seon.message/hops    :int)

;; The user is a REAL entity — ONE `:seon.user/id` row seeded at boot
;; (identity upsert, idempotent — same pattern as agent entities). All
;; message refs are uniform; later home for user prefs/memory.
(schema/register! :seon.user/id         [:string {:seon.db/identity true}])

(def user-ref
  "Lookup ref of THE user entity (one human for now). Seeded at boot by
   seon.client; the default `:seon.message/to` target."
  [:seon.user/id "user"])

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
;; now land as component-many children of :seon.turn/evals (v1.md
;; §2.1). Agent ref is reachable via the component chain (agent →
;; sessions → turns → evals); the standalone back-refs were noise.

;; ============================================================
;; v1 causality graph — :seon.session + :seon.turn entities (v1.md §2.1).
;; One pod run = one :seon.session. Each render → LLM → eval-batch
;; cycle = one :seon.turn. Both ride as component refs on their
;; parents (cascade-retract on parent retract).
;;
;; ALL counters and derivable values are NOT persisted. v1 follows
;; the reactive-context principle (docs/seon/concepts/reactive-context):
;;
;; - turn-count = (count (:seon.session/turns session)) — read time.
;; - turn-index = (count …) at write time.
;; - turns-since-inbound = count of :seon.turn entities with
;;   :seon.turn/at strictly greater than the latest INBOUND message's
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

(schema/register! :seon.session/id    [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.session/at    :inst)
;; :db/isComponent on the ref vectors — retracting a session/turn
;; cascade-retracts its child entities, and one nested pull on the
;; agent walks the whole causality chain inline (v1.md §2.1).
(schema/register! :seon.session/turns [:vector {:seon.db/component true} :seon.db/ref])

(schema/register! :seon.turn/id           [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.turn/at           :inst)
(schema/register! :seon.turn/status       [:enum :running :done :error])
;; The message whose wake opened this turn (optional — boot/manual turns
;; have none). `reply!` reads the CURRENT turn's woken-by → its
;; `:seon.message/from` = the reply target. Derived + deterministic; no
;; reply-target atom anywhere.
(schema/register! :seon.turn/woken-by     :seon.db/ref)
;; The assembled prompt is NOT persisted as a datom (three-tier storage
;; rule: datoms hold projections, blobs hold full content). run-turn!
;; writes the full prompt to logs/prompts/<agent-id>/<turn-id>.txt and
;; the turn entity carries the char count + file pointer. The old
;; `:seon.turn/prompt-text` datom (silently capped at 16,406 chars by
;; cap-edn — truncated evidence for any long run) is RETIRED 2026-06-09.
;; `:seon.turn/prompt-text` stays registered ONLY as the in-memory
;; plumbing key between run-turn!/with-turn!/ask-and-eval! — it is not
;; in `agent-bootstrap-attrs` and never reaches the DB.
(schema/register! :seon.turn/prompt-text  :string)
(schema/register! :seon.turn/prompt-chars :int)
(schema/register! :seon.turn/prompt-file  :string)
(schema/register! :seon.turn/messages     [:vector {:seon.db/component true} :seon.db/ref])
(schema/register! :seon.turn/evals        [:vector {:seon.db/component true} :seon.db/ref])

(schema/register! :seon.agent/sessions    [:vector {:seon.db/component true} :seon.db/ref])

;; ============================================================
;; v1 §5.1 — :seon.ctx entity. One section in the agent's render
;; layout. Component-owned by the agent via :seon.agent/ctx, sorted
;; by :seon.ctx/priority at render time. :seon.ctx/fn is a fully-
;; qualified symbol resolved via seon.eval/lookup-value at call time.
;; ============================================================

(schema/register! :seon.ctx/name     :keyword)
(schema/register! :seon.ctx/priority :int)
(schema/register! :seon.ctx/fn       :symbol)

(schema/register! :seon.agent/ctx    [:vector {:seon.db/component true} :seon.db/ref])

;; ============================================================
;; v1 §2.2 — program graph. :seon.ns owns the namespace source;
;; :seon.fn / :seon.schema reference their ns via child→parent
;; plain refs (NOT component — a fn does not own its ns). Identity
;; attrs upsert on redefine; history retains prior :source values.
;;
;; Substrate fns/schemas/nses populate via bootstrap.edn on first
;; boot (§7.3); agent-defined entities populate via detect-and-tee
;; in eval-batch! (§4.2 step 7).
;; ============================================================

(schema/register! :seon.ns/name    [:keyword {:seon.db/identity true}])
(schema/register! :seon.ns/source  :string)

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
;; Entity-kind `:map` schemas. One per renderable kind. The
;; `:seon.render/ai` / `:seon.render/html` symbols live on the schema's
;; own properties — `seon.schema/register!` walks the entries and
;; auto-derives `:seon.entity/id-attr` from whichever entry carries
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
;;   :seon.message — `message!` (the single write entry point, agent.cljs)
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
  [:map {:seon.render/ai   'seon.handlers.eval/render-ai
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

(schema/register! :seon.message
  [:map {:seon.render/ai   'seon.handlers.message/render-ai
         :seon.render/html 'seon.handlers.message/render-html}
   [:seon.message/id      :seon.message/id]
   [:seon.message/from    :seon.message/from]
   [:seon.message/to      :seon.message/to]
   [:seon.message/content :seon.message/content]
   [:seon.message/at      :seon.message/at]
   [:seon.message/hops    :seon.message/hops]])

(schema/register! :seon.fn
  [:map {:seon.render/ai   'seon.handlers.fn/render-ai
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
  [:map {:seon.render/ai   'seon.handlers.schema/render-ai
         :seon.render/html 'seon.handlers.schema/render-html}
   [:seon.schema/key    :seon.schema/key]
   [:seon.schema/source :seon.schema/source]
   [:seon.schema/ns         {:optional true} :seon.schema/ns]
   [:seon.schema/created-at {:optional true} :seon.schema/created-at]])

(schema/register! :seon.ns
  [:map {:seon.render/ai   'seon.handlers.ns/render-ai
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
(schema/register! :seon.agent
  [:map {:seon.render/html 'seon.render.default/view}
   [:seon.agent/id    :seon.agent/id]
   [:seon.agent/state :seon.agent/state]
   [:seon.agent/sessions  {:optional true} :seon.agent/sessions]
   [:seon.agent/turns-cap {:optional true} :seon.agent/turns-cap]
   [:seon.agent/ctx       {:optional true} :seon.agent/ctx]
   [:seon.render/ai   {:optional true} :seon.render/ai]
   [:seon.render/html {:optional true} :seon.render/html]])

;; ============================================================
;; Home-ns derivation. Per spec-05 §22.5 the agent's home ns is a
;; deterministic function of the agent's id — no need to store it
;; on the entity.
;; ============================================================

(defn home-ns
  "Return the deterministic home-ns symbol for an agent id.
   `(home-ns \"seon\") => 'seon.agent.seon`."
  [agent-id]
  (symbol (str "seon.agent." agent-id)))

;; ============================================================
;; Turn loop — was seon.session.cljs, now consolidated here.
;;
;; One turn = build ctx → call LLM → parse → eval batch → flip to :idle.
;; Partial-failure: form N+1 always runs (see seon.eval/eval-batch!).
;; ============================================================

(defn- log [agent-id turn-n stage & info]
  (seon-log/info-console!
    (str "seon.agent/" agent-id)
    (str "turn " turn-n " ▸ " stage)
    (if (= 1 (count info)) (first info) (vec info))))

;; Forward refs — run-turn!, run-agentic-loop!, current-session,
;; start-session!, turn-index live in the v1 scaffold block below;
;; inbound-message-handler calls them. (Reorganizing the file is a separate
;; pass.)
(declare run-turn! run-agentic-loop! current-session start-session! turn-index
         turns-since-inbound current-ns substrate-default-ctx pretty-ai)

(defn- per-agent-shape?
  "True when `sym` is in the agent's own home namespace (per spec-05
   §15.1a). Per-agent fns get the per-agent input shape (entity
   pre-pulled under a namespaced key); everything else gets the system
   shape (`:seon.agent/id` + DB; fn pulls the entity itself)."
  [sym agent-id]
  (and (qualified-symbol? sym)
       (str/starts-with? (namespace sym)
                         (str "seon.agent." agent-id))))

(defn- ai-render-input
  "Build the input map for the agent's `:seon.render/ai` dispatch.
   Two shapes, picked by symbol namespace (spec-05 §15.1a)."
  [sym db agent-id ent]
  (if (per-agent-shape? sym agent-id)
    {:seon.db/db                                          db
     (keyword (str "seon.agent." agent-id) "ctx")         ent}
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
;; Hop guard lives HERE (at wake): a message whose :seon.message/hops
;; reached `warn/hop-cap` wakes nothing — loud console.error + the
;; clustered `check-hop-exhausted` warning surface the refusal.
;; ============================================================

(defonce ^:private !kick-scheduled
  ;; agent-ids with a loop scheduled-or-running via the kick path.
  (atom #{}))

(defn- inbound-msg-datom?
  "True iff this added `:seon.message/to` datom targets `my-eid` from a
   DIFFERENT sender. The to-check is load-bearing: every agent installs
   this listener, so without it ONE message wakes EVERY agent's loop —
   each stray wake is a wasted LLM call (observed live 2026-06-09).
   The from-check (`from ≠ me`) is what stops an agent's own writes —
   including its per-turn assistant message — from re-kicking itself."
  [db {eid :seon.db/e target :seon.db/v} my-eid]
  (and (= target my-eid)
       (let [msg (db/entity {:seon.db/db db :seon.db/ref eid})]
         (not= my-eid (:db/id (:seon.message/from msg))))))

(defn- inbound-message-handler
  [{:seon.agent/keys [id] :as input}]
  (fn [{:seon.db/keys [db attr-index]}]
    (let [my-eid  (:db/id (db/entity {:seon.db/db db
                                      :seon.db/ref [:seon.agent/id id]}))
          inbound (when my-eid
                    (->> (:seon.message/to attr-index)
                         (filter :seon.db/added?)
                         (filter #(inbound-msg-datom? db % my-eid))))
          {waking false exhausted true}
          (group-by (fn [{eid :seon.db/e}]
                      (>= (or (:seon.message/hops
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
                 " — message " (:seon.message/id msg)
                 " hops=" (:seon.message/hops msg)
                 " reached hop-cap " warn/hop-cap
                 " (agent↔agent ping-pong guard). A human message"
                 " resets the chain (hops 0)."))))
      (when (seq waking)
        (let [state (:seon.agent/state
                      (db/entity {:seon.db/db db
                                  :seon.db/ref [:seon.agent/id id]}))
              ;; The waking message — recorded on every turn this loop
              ;; run opens (:seon.turn/woken-by) so reply! can derive
              ;; its target with no atom.
              mid   (:seon.message/id
                      (db/entity {:seon.db/db db
                                  :seon.db/ref (:seon.db/e (first waking))}))]
          (when (and (not= :running state)
                     (not (contains? @!kick-scheduled id)))
            (swap! !kick-scheduled conj id)
            ;; setTimeout breaks the ALS scope — re-enter `with-agent`
            ;; so the loop's downstream calls (run-turn!, eval-batch!,
            ;; section fns, web handlers) see (db/current-agent-id).
            (js/setTimeout
              (fn []
                (-> (js/Promise.resolve
                      (db/with-agent id
                        #(run-agentic-loop!
                           (assoc input :seon.turn/woken-by
                                  [:seon.message/id mid]))))
                    (.finally (fn [] (swap! !kick-scheduled disj id)))))
              0)))))))

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
   resets state to :idle (transact is upsert-by-unique-id)."
  [{:seon.agent/keys [id]}]
  (await (db/transact!
           {:seon.db/tx-data [{:seon.agent/id    id
                               :seon.agent/state :idle}]}))
  {:seon.agent/id id})

;; ============================================================
;; Boot. The single entry point seon.client calls at startup.
;;
;; V0 hardcoded `default-id` / `default-ns` removed 2026-05-24 (audit P1
;; — see docs/prds/agent-runtime/research/schema-state-architecture-audit
;; -2026-05-23.md §2). Multi-agent v1 needs agent identity to flow via
;; the `seon.db/agent-id-als` substrate, not via process-global atoms.
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

   Returns `{:seon.agent/id _ :seon.agent/ns _}`. The first user
   message kicks `run-agentic-loop!` (which lazily opens a
   `:seon.session` on first turn)."
  [{:seon.agent/keys [id llm-fn compile-state]}]
  (let [{:seon.agent/keys [id]}
        (await (create! {:seon.agent/id id}))
        agent-ns (home-ns id)]
    (install-user-trigger! {:seon.agent/id id
                    :seon.agent/llm-fn llm-fn
                    :seon.agent/compile-state compile-state})
    {:seon.agent/id id
     :seon.agent/ns agent-ns}))

;; ============================================================
;; message! / reply! — the SINGLE write entry point for messages.
;; Presence of attributes IS the intent; the DB holds only FULLY-
;; FORMED messages (from + to + content + at + id + hops). All
;; defaulting is a message!-boundary liberty, never a storage shape.
;; ============================================================

(declare current-turn)

(schema/register! ::message-request
  [:map
   [:seon.message/content :seon.message/content]
   ;; from defaults to the calling agent's ref via the ALS scope
   ;; ((seon.db/current-agent-id)); the HTTP adapter passes the user
   ;; ref explicitly.
   [:seon.message/from {:optional true} :seon.message/from]
   ;; to accepts ONE ref or a vector of refs (fan-out); defaults to
   ;; THE user. Storage is always the normalized vector.
   [:seon.message/to {:optional true}
    [:or :seon.db/ref [:vector :seon.db/ref]]]])

;; Concise success / standard error envelope (#26, A3 applied): the raw
;; transact tx-report is OFF the agent surface — ~1.5k transcript chars
;; per reply taught nothing and carried a misdirected "narrow your
;; query" hint. Success answers the three things a sender can act on:
;; did it store, which message, at what hop depth. Failure stays the
;; substrate-standard error envelope (errors are values).
(schema/register! ::message-response
  [:or
   [:map
    [:seon.message/ok?  [:= true]]
    [:seon.message/id   :seon.message/id]
    [:seon.message/hops :seon.message/hops]]
   [:map
    [:seon.db/ok?   [:= false]]
    [:seon.db/error :seon.db/error]]])

(defn- user-entity?
  "Does `ref` resolve to THE user entity?"
  [ref]
  (boolean (:seon.user/id (db/entity {:seon.db/ref ref}))))

(defn- waking-hops
  "Hops of the NEWEST inbound message (to ∋ me, from ≠ me), or 0 when
   none. This — not the turn's woken-by — is the hops base for
   agent-originated sends: a long-running loop keeps replying while new
   inbound messages arrive, and deriving from the loop's ORIGINAL
   waking message would pin hops constant forever (observed live
   2026-06-09: two stub agents ping-ponged at hops 2↔3 indefinitely,
   the cap never reached). The latest inbound climbs with the chain, so
   replies carry climbing hops and the guard actually bites."
  [agent-id]
  (let [my-eid (:db/id (db/entity {:seon.db/ref [:seon.agent/id agent-id]}))]
    (or (when my-eid
          (->> (db/query
                 {:seon.db/query
                  '[:find ?at ?h
                    :in $ ?me
                    :where
                    [?m :seon.message/to ?me]
                    [?m :seon.message/from ?f]
                    [(not= ?f ?me)]
                    [?m :seon.message/at ?at]
                    [(get-else $ ?m :seon.message/hops 0) ?h]]
                  :seon.db/args [my-eid]})
               (sort-by #(.getTime ^js (first %)))
               last
               second))
        0)))

(defn ^:async message!
  "Send a message — THE single entry point for `:seon.message` writes.
   Map-in / map-out; returns a CONCISE envelope, never the raw
   tx-report:
     {:seon.message/ok? true :seon.message/id _ :seon.message/hops _}
     {:seon.db/ok? false :seon.db/error …}   ; failure (errors are values)

   Defaulting (boundary liberties — the STORED row is always full):
     :seon.message/from — defaults to [:seon.agent/id (current-agent-id)]
                          from the ALS turn scope. No scope + no explicit
                          from → error envelope.
     :seon.message/to   — single ref or vector; defaults to the user.
     hops               — 0 when from = the user; otherwise the waking
                          message's hops + 1 (ping-pong guard — the wake
                          trigger refuses past `seon.warn/hop-cap`).

   Blank content is REJECTED with an error envelope — empty assistant
   messages were a recurring live defect (runs 3 + 6); since every
   message write routes through here, the guard kills the class."
  {:malli/schema [:=> [:cat ::message-request] ::message-response]}
  [{:seon.message/keys [content from to]}]
  (let [agent-id (db/current-agent-id)
        from     (or from (when agent-id [:seon.agent/id agent-id]))
        to       (cond
                   (nil? to)             [user-ref]
                   (and (vector? to)
                        (vector? (first to))) to        ; vector of lookup refs
                   (and (vector? to)
                        (keyword? (first to))) [to]     ; single lookup ref
                   (vector? to)          to             ; vector of eids
                   :else                 [to])]         ; single eid
    (cond
      (or (nil? content) (str/blank? content))
      {:seon.db/ok? false
       :seon.db/error
       {:seon.error/message
        (str "message!: blank :seon.message/content refused — a message "
             "with nothing to say must not be stored. Compose the text "
             "first, then send.")}}

      (nil? from)
      {:seon.db/ok? false
       :seon.db/error
       {:seon.error/message
        (str "message!: no :seon.message/from and no agent-id in scope — "
             "pass from explicitly or call inside (seon.db/with-agent …).")}}

      :else
      (let [hops   (if (user-entity? from)
                     0
                     (inc (waking-hops agent-id)))
            msg-id (db/new-id!)
            env    (await
                     (db/transact!
                       {:seon.db/tx-data
                        [{:seon.message/id      msg-id
                          :seon.message/from    from
                          :seon.message/to      to
                          :seon.message/content content
                          :seon.message/at      (js/Date.)
                          :seon.message/hops    hops}]}))]
        (if (:seon.db/ok? env)
          ;; concise success — the tx-report stays off the agent surface;
          ;; the id is the durable handle ([:seon.message/id msg-id]).
          {:seon.message/ok?  true
           :seon.message/id   msg-id
           :seon.message/hops hops}
          env)))))

(defn ^:async reply!
  "Reply to whoever woke the current turn: `message!` with `to` := the
   `:seon.message/from` of the current turn's `:seon.turn/woken-by`
   message (derived — the substrate knows who's talking to you; no
   target atom). Falls back to the user when the turn wasn't woken by a
   message. Returns `message!`'s concise envelope. The one-liner for
   both user- and agent-conversations:

     (seon.agent/reply! {:seon.message/content \"done — stored 2 rows\"})"
  {:malli/schema [:=> [:cat ::message-request] ::message-response]}
  [{:seon.message/keys [content]}]
  (let [agent-id (db/current-agent-id)
        woke-from (get-in (current-turn {:seon.agent/id agent-id})
                          [:seon.turn/woken-by :seon.message/from :db/id])]
    (await (message! {:seon.message/content content
                      :seon.message/to      (if woke-from [woke-from] [user-ref])}))))

;; ============================================================
;; v1 §6 — turn lifecycle.
;;
;; Composition: three small ^:async helpers + a `with-turn!`
;; bracketing combinator + a `run-turn!` orchestrator. Every transact
;; in the pipeline runs inside ONE outer `with-tx-context` scope that
;; carries agent/session/turn/origin — no manual `:tx-meta` plumbing
;; at any call site (auto-merged via `seon.db/transact!`'s
;; `merge-tx-context-into-opts`).
;;
;; Two transacts per turn instead of four: `with-turn!` folds the
;; prompt-text into the open-tx and the assistant message into the
;; close-tx. Eval-batch's per-form txs stay (each form is its own
;; tx for partial-failure semantics — v1.md §4.4).
;;
;; The named-inline `(fn ^:async name [] …)` is the one CLJS shape
;; that propagates `:async` correctly across `(.run als-instance …
;; f)`; the cleaner pattern (which we use here) is to define helpers
;; with `defn ^:async` and pass plain anonymous thunks to
;; `with-tx-context`. See `docs/prds/agent-runtime/research/
;; cljs-runturn-simplification-2026-05-23.md`.
;; ============================================================

(defn current-session
  "Most-recent `:seon.session` entity for `agent-id`. Returns nil if
   the agent has no sessions yet (fresh boot before `start-session!`)."
  [agent-id]
  (let [a (db/entity {:seon.db/ref [:seon.agent/id agent-id]})]
    (last (sort-by :seon.session/at (:seon.agent/sessions a)))))

(defn turn-index
  "Zero-indexed next turn slot for the session — derived from the
   current count of `:seon.session/turns`. Not persisted (storing
   would let it desync from reality)."
  [session-id]
  (count (:seon.session/turns
           (db/entity {:seon.db/ref [:seon.session/id session-id]}))))

(defn ^:async start-session!
  "Open a new `:seon.session` for `agent-id` and append to
   `:seon.agent/sessions`. Returns the new session entity."
  [agent-id]
  (let [session-id (db/new-id!)]
    (await (db/transact!
             {:seon.db/tx-data
              [{:seon.agent/id agent-id
                :seon.agent/sessions
                [{:seon.session/id session-id
                  :seon.session/at (js/Date.)}]}]}))
    (db/entity {:seon.db/ref [:seon.session/id session-id]})))

(defn ^:async ensure-session!
  "Return the agent's current session, opening one if none exists.
   Idempotent — re-uses an existing session within the same pod run."
  [agent-id]
  (or (current-session agent-id)
      (await (start-session! agent-id))))

(defn render-prompt
  "Sync — resolve the agent's `:seon.render/ai` symbol (default
   `seon.agent/assemble-context`) and call it. Returns the prompt string
   (empty when the symbol can't be resolved)."
  [agent-id]
  (let [ent   (db/entity {:seon.db/ref [:seon.agent/id agent-id]})
        sym   (:seon.render/ai ent 'seon.agent/assemble-context)
        input (ai-render-input sym @db/*conn* agent-id ent)]
    (or (:seon.render/text (render/ai-render sym input)) "")))

(declare with-turn-body!)

(defn ^:async with-turn!
  "Bracketing combinator. Opens a `:seon.turn` on the given session
   with `prompt-text` already attached, flips agent state to
   `:running`, then awaits `body-fn` (a plain 0-arg thunk that returns
   a Promise<map>). On success, closes the turn with `:status :done`,
   folds in any `:seon.turn/messages` from the body's result map, and
   flips agent state back to `:idle`. On throw, flips the turn to
   `:status :error` and re-throws so callers see the failure shape.

   Returns whatever `body-fn` returned, so the caller can read e.g.
   `:seon.agent/eval-count` for stop-policy decisions."
  [{:seon.agent/keys [id]
    :seon.session/keys [id-of-session]
    :seon.turn/keys [id-of-turn prompt-text prompt-file woken-by]}
   body-fn]
  ;; Short-circuit on open-turn failure (task 9b finding 3). If the
  ;; open-tx returns `{::ok? false}`, there is NO turn entity in the
  ;; DB — calling `body-fn` (the LLM) would run a turn that has no
  ;; trace, and the close-tx + error-tx below would silently fail
  ;; against the missing entity. Bail with the envelope so the caller
  ;; sees the same shape it sees from any other transact failure.
  (let [open-result
        (await
          (db/transact!
            {:seon.db/tx-data
             [{:seon.session/id id-of-session
               :seon.session/turns
               [(cond->
                  {:seon.turn/id           id-of-turn
                   :seon.turn/at           (js/Date.)
                   :seon.turn/status       :running
                   ;; Three-tier storage: the datom is a PROJECTION (char
                   ;; count); the full prompt lives in the blob file run-turn!
                   ;; wrote (`:seon.turn/prompt-file`). No truncation anywhere
                   ;; — the file is the complete evidence.
                   :seon.turn/prompt-chars (count (str prompt-text))}
                  ;; nil when the file write failed (logged) — chars survive.
                  prompt-file (assoc :seon.turn/prompt-file prompt-file)
                  ;; The waking message — reply!'s derivation source.
                  woken-by (assoc :seon.turn/woken-by woken-by))]}
              {:seon.agent/id id :seon.agent/state :running}]}))]
    (if (false? (:seon.db/ok? open-result))
      open-result
      (with-turn-body! id id-of-turn body-fn))))

(defn ^:async ^:private with-turn-body!
  "Internal — the body of `with-turn!` after the open-tx succeeded.
   Split out so the open-tx envelope short-circuit at the call site
   stays readable. Maintains the same behavior the inlined version had:
   await body-fn, close the turn on success, flip to :error on throw."
  [id id-of-turn body-fn]
  (try
    (let [result (await (body-fn))]
      (await
        (db/transact!
          {:seon.db/tx-data
           [(merge {:seon.turn/id id-of-turn :seon.turn/status :done}
                   (select-keys result [:seon.turn/messages :seon.turn/status]))
            {:seon.agent/id id :seon.agent/state :idle}]}))
      result)
    (catch :default e
      (try
        (await (db/transact!
                 {:seon.db/tx-data
                  [{:seon.turn/id id-of-turn :seon.turn/status :error}
                   {:seon.agent/id id :seon.agent/state :idle}]}))
        (catch :default _ nil))
      (throw e))))

(defn ^:async ^:private ask-and-eval-reply!
  "Internal — the successful-LLM-reply half of `ask-and-eval!`: parse
   the reply, eval-batch the forms, fold the assistant self-message."
  [resp id id-of-turn compile-state]
  (let [reply-text (or (:text resp) "")
        parsed     (repl/parse-forms reply-text)
        batch      (await (seval/eval-batch! compile-state parsed
                                             (home-ns id) id id-of-turn))]
    (cond->
      {:seon.agent/eval-count (:seon.eval/n-ok batch)}
      ;; The turn-log record of the raw LLM output: a fully-formed
      ;; self→self message (from = to = this agent — appears in the
      ;; agent's own derived conversation, wakes nothing since the
      ;; trigger requires from ≠ me, and never reads as user-directed).
      ;; Blank output stores NOTHING — the empty-assistant-message
      ;; defect (runs 3 + 6) ends at this boundary too.
      (not (str/blank? reply-text))
      (assoc :seon.turn/messages
             [{:seon.message/id      (db/new-id!)
               :seon.message/from    [:seon.agent/id id]
               :seon.message/to      [[:seon.agent/id id]]
               :seon.message/content reply-text
               :seon.message/at      (js/Date.)
               :seon.message/hops    0}]))))

(defn ^:async ask-and-eval!
  "Body of `with-turn!`. Calls the LLM with `prompt-text`, parses the
   reply, eval-batches the forms (each as a `:seon.turn/evals`
   component via Platform's eval-batch!), and returns
   `{:seon.turn/messages [<assistant>] :seon.agent/eval-count n-ok}`
   for `with-turn!` to fold into the close-tx. An LLM-call failure
   (`:seon.ai/error` on the response) NEVER closes `done [0 ok]` — it
   stores a visible error self-message and closes the turn :error."
  [{:seon.agent/keys [id llm-fn compile-state]
    :seon.turn/keys  [id-of-turn prompt-text]}]
  (let [resp (await (llm-fn prompt-text))]
    (if-let [err (:seon.ai/error resp)]
      {:seon.agent/eval-count 0
       :seon.turn/status      :error
       :seon.turn/messages
       [{:seon.message/id      (db/new-id!)
         :seon.message/from    [:seon.agent/id id]
         :seon.message/to      [[:seon.agent/id id]]
         :seon.message/content (str "⚠ LLM call failed — " (:seon.ai/msg err))
         :seon.message/at      (js/Date.)
         :seon.message/hops    0}]}
      (await (ask-and-eval-reply! resp id id-of-turn compile-state)))))

(defn- persist-prompt!
  "Write the turn's full assembled prompt to
   `logs/prompts/<agent-id>/<turn-id>.txt` (gitignored — `logs/` blob
   tier; the DB datom is only the char-count projection +  this path).
   Returns the relative path string, or nil on write failure (logged,
   never throws — losing the prompt blob must not abort the turn)."
  [agent-id turn-id text]
  (try
    (let [fs   (js/require "node:fs")
          dir  (str "logs/prompts/" agent-id)
          path (str dir "/" turn-id ".txt")]
      (.mkdirSync fs dir #js {:recursive true})
      (.writeFileSync fs path (str text) "utf8")
      path)
    (catch :default e
      (js/console.warn
        (str "seon.agent/persist-prompt!: could not write prompt blob for "
             agent-id "/" turn-id " — " (or (.-message e) e)))
      nil)))

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
   `{:seon.turn/status :error :seon.error/data <str>}`."
  [{:seon.agent/keys [id llm-fn compile-state]
    :seon.turn/keys  [woken-by]}]
  (let [session    (await (ensure-session! id))
        session-id (:seon.session/id session)
        turn-id    (db/new-id!)
        turn-idx   (turn-index session-id)
        prompt     (render-prompt id)
        ;; Blob tier — full prompt to disk; the turn datom carries only
        ;; chars + this pointer (see :seon.turn/prompt-chars note above).
        prompt-file (persist-prompt! id turn-id prompt)]
    (log id turn-idx "open" turn-id "+" (count prompt) "ctx-chars")
    (try
      (let [result (await
                     ;; Two nested ALS scopes — tx-context carries the
                     ;; full causality bundle into every transact's
                     ;; tx-meta; agent-id-als is the substrate read by
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
                             (with-turn!
                               (cond->
                                 {:seon.agent/id           id
                                  :seon.session/id-of-session session-id
                                  :seon.turn/id-of-turn    turn-id
                                  :seon.turn/prompt-text   prompt}
                                 prompt-file
                                 (assoc :seon.turn/prompt-file prompt-file)
                                 woken-by
                                 (assoc :seon.turn/woken-by woken-by))
                               #(ask-and-eval! {:seon.agent/id            id
                                                :seon.agent/llm-fn        llm-fn
                                                :seon.agent/compile-state compile-state
                                                :seon.turn/id-of-turn     turn-id
                                                :seon.turn/prompt-text    prompt})))))))
            n-ok (or (:seon.agent/eval-count result) 0)]
        (log id turn-idx (name (or (:seon.turn/status result) :done)) n-ok
             (if (:seon.turn/status result) "llm-error" "ok"))
        (assoc (db/pull {:seon.db/pull-pattern
                         '[* {:seon.turn/messages [*]
                              :seon.turn/evals    [*]}]
                         :seon.db/ref [:seon.turn/id turn-id]})
               :seon.agent/eval-count n-ok))
      (catch :default e
        (log id turn-idx "run-turn! error" (str e))
        (try
          (await (db/transact!
                   {:seon.db/tx-data
                    [{:seon.agent/id id :seon.agent/state :idle}]}))
          (catch :default _ nil))
        {:seon.turn/status :error
         :seon.error/data (str e)}))))

(defn ^:async run-agentic-loop!
  "Per v1.md §6.2 — multi-turn driver. Calls `run-turn!` repeatedly
   until a stop policy fires.

   Default stop policies:
     - Last turn errored.
     - Last turn produced zero forms (agent emitted prose only).
     - `turns-since-inbound` exceeded `(turns-cap id)` — derived
       from the message + turn log; see docs/seon/concepts/reactive-context.

   The message-arrival stop policy is handled externally: the kick
   handler's state-machine guard + scheduled-latch ensure a fresh
   inbound message can't stack new loops on top of an in-flight one."
  [{:seon.agent/keys [id] :as input}]
  (loop []
    (let [result   (await (run-turn! input))
          since-in (turns-since-inbound {:seon.agent/id id})
          status   (:seon.turn/status result)
          n-forms  (or (:seon.agent/eval-count result) 0)]
      (cond
        (= :error status)
        result

        (zero? n-forms)
        result

        (>= since-in (turns-cap id))
        (do (await
              ;; Self→self note (from = to = me): lands in the agent's
              ;; own derived conversation, wakes nothing (from ≠ me
              ;; fails at the trigger).
              (message!
                {:seon.message/from    [:seon.agent/id id]
                 :seon.message/to      [[:seon.agent/id id]]
                 :seon.message/content
                 (str "[turn cap hit — " (turns-cap id)
                      " agentic turns since the last inbound message"
                      " without a final reply. Ask again or"
                      " narrow the question.]")}))
            (assoc result :seon.agent/halt :cap-hit))

        :else
        (recur)))))

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
;; Pretty-print + truncation helpers.
;; ------------------------------------------------------------

(defn host-timezone
  "IANA tz string for the running pod, or 'UTC' if Intl is unavailable."
  []
  (try
    (or (some-> (js/Intl.DateTimeFormat.) .resolvedOptions .-timeZone) "UTC")
    (catch :default _ "UTC")))

(defn truncate-edn
  "pr-str a value, truncate to ~2 KB for display in the eval log
   (v1.md §1's three-tier storage rule: DB datoms hold projections,
   not full content)."
  ([v] (truncate-edn v 2048))
  ([v limit]
   (let [s (pr-str v)]
     (if (> (count s) limit)
       (str (subs s 0 (max 0 (- limit 4))) " ...")
       s))))

(defn message-label
  "Transcript label for a message's `:seon.message/from` ref (a pulled
   map carrying `:seon.user/id` / `:seon.agent/id`), resolved by REF
   KIND: the user → `user`, this agent itself → `assistant`, any other
   agent → `agent-<id>`."
  [from own-id]
  (cond
    (:seon.user/id from)             "user"
    (= own-id (:seon.agent/id from)) "assistant"
    (:seon.agent/id from)            (str "agent-" (:seon.agent/id from))
    :else                            "unknown"))

(defn- format-message-row
  "Render one message as a REPL event for the interleaved transcript:
   `user> …` / `assistant> …` / `agent-<id>> …`. The `<label>>` prefix
   lines it up visually with eval `> form` lines so the merged stream
   reads as one coherent REPL session."
  [{from :seon.message/from content :seon.message/content} own-id]
  (str (message-label from own-id) "> " content))

(defn- read-error-envelope
  "Best-effort EDN decode of a `:seon.eval/error-data` instrument-envelope
   string. Returns the envelope map, or nil when blank/unreadable. Never
   throws. (The plain `:seon.eval/error` string is now stored pre-rendered
   and legible by `seon.eval/render-error-string`, so it is NOT decoded
   here — `format-eval-row` surfaces it as-is.)"
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (try (edn/read-string s)
         (catch :default _ nil))))

(def eval-render-cap
  "Per-eval rendered-result char cap for the transcript context section.
   Context-SAFETY invariant: no single eval's result may dominate the
   agent's whole context. One 9.7M-char `pull` result used to blow
   render-prompt to ~9.8M chars; capping each rendered result here keeps
   `transcript-section` bounded regardless of how large any individual
   `:seon.eval/result-edn` blob is."
  1500)

(defn cap-result
  "Truncate a rendered eval-result string to `eval-render-cap`,
   appending an elision marker reporting how many chars were dropped.
   Operates on the ALREADY-stringified result (`:seon.eval/result-edn`
   is a pr-str string), so no re-quoting. Nil-safe."
  ([s] (cap-result s eval-render-cap))
  ([s limit]
   (let [s (str s)
         n (count s)]
     (if (> n limit)
       (str (subs s 0 limit) " …⟨" (- n limit) " chars elided⟩")
       s))))

(defn cap-result-body
  "Like `cap-result`, but for an eval RESULT body specifically: when the
   value is clipped by size, append a GUIDING clip message that teaches
   the agent how to get less/narrower output, instead of a bare elision
   marker. A clip is feedback, not a failure (errors are values the agent
   reads).

   Only the SIZE clip (a huge scalar/string that overflows the display
   cap) gets this guide. Large COLLECTIONS are already bounded upstream
   with their own row-count guide in `:seon.eval/result-edn`
   (`seon.eval/render-result-edn`), so their preview fits under the cap
   and no second guide fires here — no double-noising.

   The full value is always available via `(result <id>)` (the live
   globalThis stash); the clip is display-only."
  ([s] (cap-result-body s eval-render-cap nil))
  ([s limit] (cap-result-body s limit nil))
  ([s limit eid]
   (let [s (str s)
         n (count s)]
     (if (> n limit)
       (let [ref (if eid (str "(result :" eid ")") "(result :<id>)")]
         (str (subs s 0 limit)
              " …⟨" (- n limit) " chars clipped at " limit "⟩"
              "\n;; Narrow it: add a :find aggregate or limit, a tighter "
              ":where, or pull fewer attrs; " ref " holds the full value "
              "to drill with get-in/filter."))
       s))))

(defn- format-eval-row
  "Multi-line render for the recent-evals tile — narration, source,
   result/error, and the timing footer (`; # eval-id  Nms`).

   The rendered result/error body is capped at `eval-render-cap` chars
   (`cap-result`) so one huge eval result can't dominate the agent's
   context (context-SAFETY invariant).

   Error rendering branches: if `:seon.eval/error-data` decodes to a
   Malli instrumentation envelope, use `render-malli-error` (the
   structured ;; ERROR block with expected/got/reason/hint columns).
   Otherwise fall back to the legacy `(str \";; ERROR \" err)` plain
   path — covers timeouts, generic throws, anything pre-instrumentation."
  [{src      :seon.eval/source
    ok?      :seon.eval/ok?
    res      :seon.eval/result-edn
    out      :seon.eval/output
    err      :seon.eval/error
    err-data :seon.eval/error-data
    eid      :seon.eval/id
    dur      :seon.eval/duration-ms
    narr     :seon.eval/narration}]
  (let [envelope (read-error-envelope err-data)
        body (cond
               ok?
               (cap-result-body (or res "nil") eval-render-cap eid)

               (einstrument/instrument-error? envelope)
               (cap-result-body (einstrument/render-malli-error envelope)
                                eval-render-cap eid)

               (and (string? err) (not (str/blank? err)))
               ;; `:seon.eval/error` is now stored pre-rendered + legible
               ;; (deepest real message + structured `:seon.error/data`,
               ;; no opaque raw/stack) by `seon.eval/render-error-string`,
               ;; so it's already short — just prefix + plain-clip. NOT
               ;; `cap-result-body`, whose "narrow your query" guide is for
               ;; oversized RESULTS and is nonsensical on an error.
               (cap-result (str ";; ERROR " err))

               :else ";; <no result>")
        footer (str "  ; # " eid (when dur (str "  " dur "ms")))
        ;; Captured println/prn output (fix f) — shown above the result
        ;; like a real REPL prints before returning. Bounded by the same
        ;; per-eval render cap.
        out-ln (when (and (string? out) (not (str/blank? out)))
                 (str (cap-result (str/trimr out)) "\n"))]
    (str (when (and narr (not (str/blank? narr))) (str narr "\n"))
         "> " (cap-result src) "\n"
         out-ln
         body footer)))

;; ------------------------------------------------------------
;; Read API — what the agent calls from its REPL to walk its own
;; state. All sync, all pulling from the live conn. Match v1.md §5's
;; map-arg convention with smart defaults.
;;
;; Agent-id resolution: callers pass `:seon.agent/id` explicitly OR
;; run inside a `(seon.db/with-agent id …)` scope (the normal boot/
;; run-loop path). `resolve-id` throws a clear ex-info when neither
;; is available — we don't guess, we don't fall back to a hardcoded
;; process-global default (audit P1).
;; ------------------------------------------------------------

(defn- resolve-id
  "Return the explicit id when supplied, else `(db/current-agent-id)`,
   else throw with a clear message. Centralized so every read API
   surfaces the same instruction when called outside any agent scope."
  [id]
  (or id
      (db/current-agent-id)
      (throw (ex-info
               (str "seon.agent: no agent-id in scope — pass "
                    ":seon.agent/id explicitly or call inside "
                    "(seon.db/with-agent id …).")
               {::error :seon.agent/no-agent-id}))))

(defn messages
  "Last N messages of MY conversation, oldest-first. The conversation
   is DERIVED — `from = me OR to ∋ me` — never stored as a membership
   attr (the retired per-message agent back-ref). Queries the
   message log DIRECTLY, not via :seon.session/turns → :seon.turn/
   messages (the turn-walk was the run-3 demo killer: standalone
   inbound messages never attach to a turn). The from/to refs are
   pulled with their id attrs so transcript labeling resolves by ref
   kind. Default {:seon.agent/n 50}."
  ([] (messages {}))
  ([{:seon.agent/keys [n id] :or {n 50}}]
   (let [id     (resolve-id id)
         my-eid (:db/id (db/entity {:seon.db/ref [:seon.agent/id id]}))
         rows   (when my-eid
                  (db/query
                    {:seon.db/query
                     '[:find (pull ?m [* {:seon.message/from
                                          [:db/id :seon.user/id :seon.agent/id]
                                          :seon.message/to
                                          [:db/id :seon.user/id :seon.agent/id]}])
                       :in $ ?me
                       :where
                       (or-join [?m ?me]
                         [?m :seon.message/from ?me]
                         [?m :seon.message/to ?me])
                       [?m :seon.message/at _]]
                     :seon.db/args [my-eid]}))
         msgs   (->> rows
                     (map first)
                     (sort-by #(.getTime ^js (:seon.message/at %))))]
     (vec (take-last n msgs)))))

(defn current-turn
  "Most-recent :seon.turn on the agent's current session — the one
   that's :running, or the last :done if no turn is open."
  ([] (current-turn {}))
  ([{:seon.agent/keys [id]}]
   (let [id      (resolve-id id)
         session (current-session id)]
     (last (sort-by :seon.turn/at (:seon.session/turns session))))))

(defn evals
  "Last N :seon.eval entries for the agent's current session,
   oldest-first. Walks :seon.session/turns → :seon.turn/evals (Platform
   migrated eval storage to this shape in commit 5786247).
   Default {:seon.agent/n 20}."
  ([] (evals {}))
  ([{:seon.agent/keys [n id] :or {n 20}}]
   (let [id      (resolve-id id)
         session (current-session id)
         es      (for [t (sort-by :seon.turn/at (:seon.session/turns session))
                       e (sort-by :seon.eval/at (:seon.turn/evals t))]
                   e)]
     (vec (take-last n es)))))

(defn current-ns
  "The agent's current namespace — derived from the latest successful
   eval's :seon.eval/ns. Falls back to (home-ns id) when no successful
   eval has run yet. Reactive: the next successful eval that switches
   ns (via `(ns …)`) shows up here on the next call. See
   docs/seon/concepts/reactive-context."
  ([] (current-ns {}))
  ([{:seon.agent/keys [id]}]
   (let [id (resolve-id id)
         ;; All evals across all sessions, latest first.
         all-evals
         (for [s (:seon.agent/sessions (db/entity {:seon.db/ref [:seon.agent/id id]}))
               t (:seon.session/turns s)
               e (:seon.turn/evals t)
               :when (true? (:seon.eval/ok? e))]
           e)
         latest (last (sort-by :seon.eval/at all-evals))]
     (or (:seon.eval/ns latest) (home-ns id)))))

(defn turns-since-inbound
  "Count of :seon.turn entities in the agent's current session whose
   :seon.turn/at is strictly after the latest INBOUND message's :at —
   a message with to ∋ me AND from ≠ me (sender-agnostic: the user and
   other agents both reset the window). Drives `run-agentic-loop!`'s
   cap policy. Derived from the message + turn log; nothing stored.
   See docs/seon/concepts/reactive-context."
  ([] (turns-since-inbound {}))
  ([{:seon.agent/keys [id]}]
   (let [id      (resolve-id id)
         session (current-session id)
         turns   (:seon.session/turns session)
         ;; lookup refs are NOT auto-resolved in query args — bind the
         ;; eid explicitly so the ref-valued ?me joins work.
         my-eid  (:db/id (db/entity {:seon.db/ref [:seon.agent/id id]}))
         latest-inbound-at
         (when my-eid
           (->> (db/query
                  {:seon.db/query
                   '[:find (max ?at)
                     :in $ ?me ?cap
                     :where
                     [?m :seon.message/to ?me]
                     [?m :seon.message/from ?f]
                     [(not= ?f ?me)]
                     ;; hop-exhausted messages must NOT extend the loop:
                     ;; without this filter two live agent loops reset
                     ;; each other's window forever (the wake guard only
                     ;; gates loop STARTS, not in-flight loops).
                     [(get-else $ ?m :seon.message/hops 0) ?h]
                     [(< ?h ?cap)]
                     [?m :seon.message/at ?at]]
                   :seon.db/args [my-eid warn/hop-cap]})
                ffirst))]
     (count
       (if latest-inbound-at
         (filter #(> (.getTime ^js (:seon.turn/at %))
                     (.getTime ^js latest-inbound-at))
                 turns)
         turns)))))

(defn ctx-entities
  "Pull the agent's :seon.agent/ctx vector with each :seon.ctx entity
   inlined. Sorted by :seon.ctx/priority. Useful for inspection
   and for the agent's layout-editing flow."
  ([] (ctx-entities {}))
  ([{:seon.agent/keys [id]}]
   (let [id (resolve-id id)]
     (->> (db/pull {:seon.db/pull-pattern
                    '[{:seon.agent/ctx [:db/id :seon.ctx/name
                                        :seon.ctx/priority :seon.ctx/fn]}]
                    :seon.db/ref [:seon.agent/id id]})
          :seon.agent/ctx
          (sort-by :seon.ctx/priority)
          vec))))

;; ------------------------------------------------------------
;; Section fns (v1.md §5.2). Each takes :seon.render/system-input
;; {:seon.db/db :seon.agent/id} optionally with :seon.agent/ctx-entity
;; (the :seon.ctx entity that named this section, so the fn can read
;; per-section overrides like :seon.agent/n). Returns a string;
;; empty string = section suppressed by the composer.
;; ------------------------------------------------------------

(defn system-section
  "REPL header: who-am-I, the strict response-format contract, and the
   discovery cheat-sheet.

   CACHE-PREFIX invariant: this section is the FIRST bytes of every
   turn's user message and must be BYTE-STABLE across turns. No
   timestamps, no current-ns, no counts — anything per-turn volatile
   lives in `prompt-section` (the always-changing tail). The old
   `Now: <ISO>` line here busted the provider prompt-cache at char 35
   every single turn (context-audit-2026-06-09 §4)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id]}]
  (str "<system agent=\"" id "\">\n"
       "  Your current namespace, the turn counts and the wall-clock time\n"
       "  are in the status block at the very END of this context; the\n"
       "  final line is a clean REPL prompt (<your-ns>=>) — your reply is\n"
       "  the next REPL input.\n"
       "\n"
       "  FORMAT IS STRICT. Everything you emit is either\n"
       "    (a) a Clojure form — (...), [...], {...}, @!atom\n"
       "    (b) a comment line starting with ;;\n"
       "  Anything else is a bug. Bare prose HAS eaten responses before\n"
       "  (\"Let me read the file\" once became four bogus eval entries).\n"
       "  If you write a sentence, put ;; in front of every line of it.\n"
       "\n"
       "  Correct shape:                 Wrong shape (don't do this):\n"
       "    ;; first, look around          Let me look around first.\n"
       "    (seon.db/query ...)            (seon.db/query ...)\n"
       "    ;; then, write a reply         Now I'll write the reply.\n"
       "    (seon.db/transact! ...)        (seon.db/transact! ...)\n"
       "\n"
       "  Walk your own state:\n"
       "    (seon.agent/messages)        ; current session's messages — default {:seon.agent/n 50}\n"
       "    (seon.agent/evals)           ; current session's evals — default {:seon.agent/n 20}\n"
       "    (seon.agent/current-ns)      ; your ns as data (the prompt line shows it too)\n"
       "    (result <eval-id>)           ; full live result of a prior eval (this session)\n"
       "\n"
       "  Namespaces are workspaces: (ns my.domain.thing) moves you there\n"
       "  and your CONTEXT FOLLOWS YOUR NAMESPACE — build where the work\n"
       "  lives. println/prn output is captured onto the eval's record\n"
       "  (shown above the result), but prefer returning values.\n"
       "\n"
       "  See your code in the current ns:\n"
       "    (seon.db/pull {:seon.db/pull-pattern\n"
       "                    '[:seon.ns/source\n"
       "                       {:seon.fn/_ns [*] :seon.schema/_ns [*]}]\n"
       "                    :seon.db/ref [:seon.ns/name (seon.agent/current-ns)]})\n"
       "</system>"))

;; ------------------------------------------------------------
;; capabilities-section — the "## What you can do" worked-examples
;; block the system-prompt sticky promises. DERIVED, never hardcoded:
;; the core seon.db API fns are persisted as :seon.fn entities
;; (seeded by seon.client/index-substrate!), each carrying the real
;; :seon.fn/sym + :seon.fn/arglists + :seon.fn/doc. We render those
;; rows so the agent sees the exact MAP-IN call shape — the mistake
;; we observed (calling transact!/query positionally, hallucinating
;; seon.agent/current-agent-id) becomes impossible to make from
;; context. Bounded: the curated core API only (~5 fns), NOT every
;; registered :seon.fn — never the unbounded fn dump.
;; ------------------------------------------------------------

;; Render order + which core fns appear. These are exactly the syms
;; seon.client/index-substrate! persists; we pull them by identity so
;; the rendered shape is the SAME data the agent reads via
;; (seon.db/pull [:seon.fn/sym …]) — one source, no divergence.
(def ^:private capability-syms
  ["seon.schema/register!"
   "seon.db/transact!"
   "seon.db/query"
   "seon.db/pull"
   "seon.db/entity"
   "seon.db/listen!"
   "seon.db/current-agent-id"])

(defn- first-doc-line
  "First SENTENCE of a docstring (joined across the first few lines, cut
   at the first \". \") — the one-liner for the catalogs. The old
   first-LINE version dangled mid-sentence (\"Two call shapes:\") when a
   docstring's opening sentence wrapped. Full doc stays on the
   :seon.fn entity."
  [doc]
  (let [flat (->> (str/split-lines (or doc ""))
                  (map str/trim)
                  (remove str/blank?)
                  (take 3)
                  (str/join " "))
        idx  (str/index-of flat ". ")]
    (cond
      (str/blank? flat) nil
      (some? idx)       (subs flat 0 (inc idx))
      (> (count flat) 140) (str (subs flat 0 140) " …")
      :else             flat)))

(defn- arglist-vectors
  "Split a stored `:seon.fn/arglists` string — \"([k v])\",
   \"([req] [db selector eid])\" — into its top-level arg-vector strings
   ([\"[k v]\"] / [\"[req]\" \"[db selector eid]\"]). Returns [] for
   blank or \"()\" (unknown arity)."
  [arglists]
  (let [s (str/trim (or arglists ""))
        n (count s)]
    (loop [i 0 depth 0 start nil out []]
      (if (>= i n)
        out
        (let [c (nth s i)]
          (cond
            (= c \[) (recur (inc i) (inc depth)
                            (if (zero? depth) i start) out)
            (= c \]) (let [d (dec depth)]
                       (if (and (zero? d) (some? start))
                         (recur (inc i) d nil (conj out (subs s start (inc i))))
                         (recur (inc i) d start out)))
            :else    (recur (inc i) depth start out)))))))

(defn- callable-sigs
  "One CALLABLE shape per arity from a fn sym + stored arglists string:
   \"([k v])\" → [\"(sym k v)\"]; \"([req] [db eid])\" → [\"(sym req)\"
   \"(sym db eid)\"]. The old render glued the raw arglists string after
   the sym — `(seon.db/pull ())` / `(register! ([k v]))` — which taught
   an UNCALLABLE shape (context-audit 2026-06-09 §2). Unknown arity →
   [\"(sym …)\"]."
  [sym arglists]
  (let [vs (arglist-vectors arglists)]
    (if (seq vs)
      (mapv (fn [v]
              (let [inner (str/trim (subs v 1 (dec (count v))))]
                (if (str/blank? inner)
                  (str "(" sym ")")
                  (str "(" sym " " inner ")"))))
            vs)
      [(str "(" sym " …)")])))

(defn capabilities-section
  "Render the `## What you can do` block the system-prompt sticky
   promises. DERIVED from the persisted core `:seon.fn` entities —
   each fn's `:seon.fn/sym` + `:seon.fn/arglists` (the map-in shape) +
   a one-line `:seon.fn/doc`. Includes one fully-worked `transact!`
   example so the positional-call mistake is impossible to make from
   context. Bounded to the curated core API (`capability-syms`)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db]}]
  (let [rows  (->> capability-syms
                   (keep (fn [sym]
                           (let [e (db/entity {:seon.db/db db
                                               :seon.db/ref [:seon.fn/sym sym]})]
                             (when e
                               {:sym      (:seon.fn/sym e)
                                :arglists (:seon.fn/arglists e)
                                :doc      (first-doc-line (:seon.fn/doc e))})))))
        lines (for [{:keys [sym arglists doc]} rows]
                (str (str/join "\n"
                       (map #(str "  " %) (callable-sigs sym arglists)))
                     (when (seq doc) (str "\n      ; " doc))))
        roots (seq (:seon.fs/allowed-roots @sfs/!config))]
    (if (seq rows)
      (str "## What you can do\n\n"
           "These are the core APIs. Map-in is the preferred shape: you pass\n"
           "ONE map with fully-namespaced keys (see the worked examples below).\n"
           "The db ops (query/pull/entity/transact!) ALSO accept a natural\n"
           "datahike-style positional form.\n\n"
           (str/join "\n" lines)
           "\n\n"
           "### Storing a NEW KIND of data: register the schema FIRST\n\n"
           "To store a NEW kind of fact you must REGISTER each attribute with\n"
           "`seon.schema/register!` BEFORE you transact it. Storing a schema's\n"
           "source as data is NOT registration — an unregistered attr is\n"
           "REJECTED by transact!. register! is the single source of truth:\n"
           "register the TYPE and the system derives datahike storage.\n\n"
           "Use DEEP, namespaced attrs — the keyword namespace must have at\n"
           "least TWO dot-separated segments, like a real code namespace:\n"
           "  :kb.finding/claim   YES — multi-segment namespace\n"
           "  :finding/claim      NO  — single-segment namespace, same\n"
           "                            violation as a bare key\n"
           "  :title              NO  — bare key\n"
           "Common shapes:\n"
           "  - natural-key identity (upsert): [:string {:seon.db/identity true}]\n"
           "  - a reference to another entity: :seon.db/ref\n"
           "  - many references:               [:vector :seon.db/ref]\n"
           "  - numbers: :int for counts/ids, :double for measures —\n"
           "    :number is NOT a type (the transact! gate will tell you).\n\n"
           "  ;; 1. register the attrs (do this ONCE per attr)\n"
           "  (seon.schema/register! :kb.doc/path  [:string {:seon.db/identity true}])\n"
           "  (seon.schema/register! :kb.doc/title :string)\n"
           "  (seon.schema/register! :kb.doc/tags  [:vector :keyword])\n\n"
           "  ;; 2. NOW transact data using those attrs — upserts by :kb.doc/path\n"
           "  (seon.db/transact!\n"
           "    {:seon.db/tx-data\n"
           "     [{:kb.doc/path  \"docs/seon/_dashboard.md\"\n"
           "       :kb.doc/title \"Dashboard\"\n"
           "       :kb.doc/tags  [:index :dashboard]}]})\n\n"
           "  ;; 3. read it back\n"
           "  (seon.db/query {:seon.db/query\n"
           "                  '[:find ?path ?title\n"
           "                    :where [?e :kb.doc/path ?path]\n"
           "                           [?e :kb.doc/title ?title]]})\n\n"
           "Totals and aggregates: compute IN the query over the STORED data —\n"
           "(sum ?v), (count ?e), (max ?v) — never by hand from your own turn:\n"
           "  (seon.db/query {:seon.db/query\n"
           "                  '[:find (sum ?secs)\n"
           "                    ;; :with ?e is REQUIRED for a row total —\n"
           "                    ;; datalog is set-semantics, so without it\n"
           "                    ;; two entities with the SAME value dedupe\n"
           "                    ;; to one and the sum comes out short.\n"
           "                    :with ?e\n"
           "                    :where [?e :my.domain/duration-seconds ?secs]]})\n\n"
           "When a query comes back EMPTY (#{}), suspect a misspelled attribute\n"
           "before you conclude there's no data. The usual cause is a shortened\n"
           "namespace: copy the attribute keyword EXACTLY as the schema-catalog\n"
           "shows it (if the catalog lists :seon.kb.doc/path, query that — not\n"
           ":kb.doc/path). Fix the keyword and re-run.\n\n"
           "### Reading one entity: pull and entity\n\n"
           "The db ops are datahike-compatible — map-in (shown) and positional\n"
           "(db-first, e.g. (seon.db/pull <db> selector eid)) both work.\n\n"
           "  ;; pull — one entity as a plain map, by lookup-ref or eid\n"
           "  (seon.db/pull {:seon.db/pull-pattern '[:seon.fn/sym :seon.fn/doc]\n"
           "                 :seon.db/ref          [:seon.fn/sym \"seon.db/query\"]})\n\n"
           "  ;; entity — lazy map-like view; read attrs like a map\n"
           "  (:seon.fn/doc (seon.db/entity {:seon.db/ref [:seon.fn/sym \"seon.db/query\"]}))\n\n"
           "### Reacting to writes: listen!\n\n"
           "  (seon.db/listen!\n"
           "    {:seon.db/key     :my-ns/watch\n"
           "     :seon.db/handler (fn [{:seon.db/keys [db attr-index]}]\n"
           "                        ;; runs after EVERY transact; attr-index\n"
           "                        ;; groups the tx's datoms by attribute\n"
           "                        (when (:kb.doc/path attr-index)\n"
           "                          (js/console.log \"new doc stored\")))})\n"
           "  ;; same :seon.db/key replaces; remove with\n"
           "  ;; (seon.db/unlisten! {:seon.db/key :my-ns/watch})\n\n"
           "### Reading the repo (files on this machine)\n\n"
           (if roots
             (str "You can READ files under: " (str/join ", " roots) "\n"
                  "(read-only; everything outside these roots is denied)\n\n")
             (str "No filesystem roots are granted right now (default-deny) —\n"
                  "every seon.fs call returns an error envelope that explains\n"
                  "how access is configured.\n\n"))
           "Paths are ABSOLUTE, real machine paths — there is no virtual\n"
           "root or chroot. When your human asks where something is,\n"
           "answer with the real path exactly as the substrate returns it.\n\n"
           "The recipe is CONSULT FINDINGS → SEARCH → READ PRECISELY (never\n"
           "walk + guess). Your FIRST move on ANY repo question is step 0 —\n"
           "prior agents already answered many questions and stored the\n"
           "answers; re-deriving one is wasted turns:\n\n"
           "  ;; 0. FIRST: query stored findings on the topic. The\n"
           "  ;;    schema-catalog's domain-attrs block shows the EXACT\n"
           "  ;;    finding attrs that exist — query those keywords.\n"
           "  (seon.db/query {:seon.db/query\n"
           "                  '[:find ?q ?claim ?path ?line\n"
           "                    :where [?f :kb.finding/question    ?q]\n"
           "                           [?f :kb.finding/claim       ?claim]\n"
           "                           [?f :kb.finding/source-path ?path]\n"
           "                           [?f :kb.finding/line        ?line]]})\n"
           "  ;; A hit IS the answer, with provenance — cite it (re-read the\n"
           "  ;; source line only if you must verify). Search the repo ONLY\n"
           "  ;; when no stored finding covers the question.\n\n"
           "  ;; 1. grep for a term (regex). Call it as the WHOLE form — the\n"
           "  ;;    result is auto-awaited; inside a let you'd get a Promise.\n"
           "  (seon.search/grep {:seon.search/pattern \"validate-entity-values!\"\n"
           "                     :seon.search/glob    \"*.cljs\"})\n"
           "  ;; => {:seon.search/ok? true, :seon.search/matches\n"
           "  ;;     [{:seon.search/path \"…/src/seon/db.cljs\"\n"
           "  ;;       :seon.search/line-number 803, …}], …}\n\n"
           "  ;; 2. read the exact hit (sync; match paths are absolute)\n"
           "  (seon.fs/read-file {:seon.fs/path \"<absolute path from the match>\"})\n\n"
           "A denial is a VALUE, not a crash — {:seon.fs/ok? false\n"
           ":seon.fs/error \"…\"} tells you whether the path is out of scope\n"
           "or the fs is read-only. Read the error; it says what to do.\n\n"
           "### Storing what you learn — the canonical finding shape\n\n"
           "STORE PROACTIVELY: whenever you VERIFY a non-trivial result —\n"
           "an answer dug out of the repo, a computed fact, a confirmed\n"
           "behavior — store it as a finding by default, without being\n"
           "asked. A finding nobody stored is research the next agent (or\n"
           "you, next session) pays for again.\n\n"
           "Use these EXACT attrs (check the schema-catalog first: if\n"
           "finding attrs already exist there, REUSE those exact keywords —\n"
           "NEVER invent a parallel shape):\n\n"
           "  (seon.schema/register! :kb.finding/question    :string)\n"
           "  (seon.schema/register! :kb.finding/claim       :string)\n"
           "  (seon.schema/register! :kb.finding/source-path :string)\n"
           "  (seon.schema/register! :kb.finding/line        :int)\n"
           "  (seon.schema/register! :kb.finding/confidence  [:enum :verified :inferred])\n\n"
           "  (seon.db/transact!\n"
           "    {:seon.db/tx-data\n"
           "     [{:kb.finding/question    \"how does transact! validate schemas?\"\n"
           "       :kb.finding/claim       \"seon.db/transact! Malli-validates every entity before the tx reaches datahike\"\n"
           "       :kb.finding/source-path \"src/seon/db.cljs\"\n"
           "       :kb.finding/line        803\n"
           "       :kb.finding/confidence  :verified}]})  ; :verified = you read that line\n\n"
           "WHY one shape: the next agent discovers your attrs in the\n"
           "schema-catalog, CONSULTS your claims (recipe step 0) and reuses\n"
           "them — findings compound only when everyone writes the same kind.\n\n"
           "### Replying — one line, the substrate knows who asked:\n\n"
           "  (seon.agent/reply! {:seon.message/content \"on it — here's what I found\"})\n"
           "  ;; => {:seon.message/ok? true, :seon.message/id \"MSG…\",\n"
           "  ;;     :seon.message/hops 1}   ; failure → {:seon.db/ok? false …}\n\n"
           "Messaging another agent (or an explicit target) — :seon.message/to\n"
           "takes a ref or a vector of refs:\n\n"
           "  (seon.agent/message!\n"
           "    {:seon.message/to      [:seon.agent/id \"<other-agent-id>\"]\n"
           "     :seon.message/content \"can you check the workout totals?\"})\n\n"
           "### Your live tile (your one HTML surface in the inspector)\n\n"
           "You own ONE always-visible tile, rendered above the entity cards.\n"
           "Default renderer: seon.render.default/view. Repoint it: define a fn\n"
           "returning {:seon.render/hiccup [...]}, then transact its symbol:\n\n"
           "  (defn my-tile [_input]\n"
           "    {:seon.render/hiccup [:div [:h2 \"status\"] [:p \"all green\"]]})\n\n"
           "  (seon.db/transact!\n"
           "    {:seon.db/tx-data\n"
           "     [{:seon.agent/id    (seon.db/current-agent-id)\n"
           "       :seon.render/html 'YOUR-CURRENT-NS/my-tile}]})  ; fully qualified")
      "")))

;; ============================================================
;; exemplars-section — FULL source of the chosen exemplar namespaces,
;; rendered from the program graph (context-focus-redesign 2026-06-10).
;; The user direction: fewer mechanisms at full depth beats 102
;; signatures at zero depth — give the agent COMPLETE, in-conventions
;; namespaces (schemas + fns + tests) to copy the SHAPE from.
;;
;; The section NEVER re-reads files at render time (code-as-data): the
;; boot indexer (`seon.client/index-substrate!` / `index-tests`) is the
;; ONE file-reader; it persists the real full file text on
;; `:seon.ns/source` for every ns matched by `exemplar-ns?`. This
;; section just queries those datoms. Byte-stable for the life of a pod
;; process (source can only change with a build change, which restarts
;; the pod) — it belongs inside the provider-cacheable static prefix,
;; so it renders at priority 22, between :capabilities (20) and the
;; semi-static :schema-catalog (25).
;; ============================================================

(def exemplar-roots
  "ROOT namespace names (strings) of the exemplar set — the namespaces
   whose FULL file source renders into every prompt as the :exemplars
   section. An indexed ns is included iff its name equals a root,
   starts with `<root>.` (children ride along by default), or is the
   TEST SIBLING (`…-test`) of an included ns — see [[exemplar-ns?]].

   Why these two (context-focus-redesign §1): `seon.search` is THE
   exemplar npm-package wrapper (wrapper doctrine, 17 register! calls,
   map-in/map-out request/response schemas, error envelopes);
   `seon.fs` is the agent's most-used API (config-map pattern,
   allowlist/default-deny envelopes, sync + async fns). Their test
   sibling (`seon.search-test`) is the model test ns. Lives in code as
   a def (same lifecycle as the build that contains the source); a
   DB-resident override is deliberately NOT v1 (spec open question 3)."
  #{"seon.fs" "seon.search"})

(defn- exemplar-base-name
  "The ns name with a trailing `-test` stripped — the SUBJECT ns a test
   sibling pairs with (`seon.search-test` → `seon.search`). Non-test
   names pass through unchanged."
  [ns-str]
  (if (str/ends-with? ns-str "-test")
    (subs ns-str 0 (- (count ns-str) 5))
    ns-str))

(defn exemplar-ns?
  "True when `ns-name` (string, symbol, or ns-name keyword) is in the
   exemplar set: equal to a root in [[exemplar-roots]], a name-child of
   one (`<root>.<x>`), or the `-test` sibling of an included ns
   (`<included>-test` / `<included>.<child>-test` — test nses are not
   name-children, so the sibling rule is explicit). Used by the boot
   indexer (`seon.client`) to decide which `:seon.ns/source` rows carry
   the real full file text, and by [[exemplars-section]] to select the
   rows it renders — ONE rule, two sites, no drift."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s    (if (keyword? ns-name) (name ns-name) (str ns-name))
        base (exemplar-base-name s)]
    (boolean (some #(or (= base %) (str/starts-with? base (str % ".")))
                   exemplar-roots))))

(defn- exemplar-sort-key
  "Deterministic render order for exemplar nses: alphabetical by the
   base (subject) name, test sibling AFTER its subject. For the current
   set that yields seon.fs → seon.search → seon.search-test — which is
   also dependency order (search requires fs) and is byte-stable across
   renders (LLM cache-prefix invariant: no timestamps, no map-order
   nondeterminism)."
  [ns-str]
  [(exemplar-base-name ns-str) (if (str/ends-with? ns-str "-test") 1 0)])

(def ^:private exemplars-header
  ";; These complete namespaces are THE models for code you write: this is\n;; what a finished schema set, a specced map-in/map-out fn, an error\n;; envelope, and a test suite look like here. Copy the SHAPE — register!\n;; shapes, ::request/::response pairs, :malli/schema on every public fn,\n;; errors as values, deftest + fixture + envelope assertions.\n;; These fns already exist — call them; never re-define them.")

(defn exemplars-section
  "FULL source of the exemplar namespaces ([[exemplar-roots]] + children
   + test siblings), each wrapped in `<exemplar ns=\"…\">…</exemplar>`,
   queried from the `:seon.ns/source` datoms the boot indexer persisted —
   never a render-time file read (code-as-data: the boot indexer is the
   ONE file-reader; everything downstream reads the graph).

   A matched ns whose source is missing or still the `(ns x)` stub
   renders NOTHING for that ns and logs fail-loud — never throws, never
   silently pads with the stub. Deterministically ordered
   ([[exemplar-sort-key]]) so the section is byte-stable across renders."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db]}]
  (let [rows   (->> (db/query
                      {:seon.db/db db
                       :seon.db/query
                       '[:find ?nm ?src
                         :where
                         [?n :seon.ns/name ?nm]
                         [?n :seon.ns/source ?src]]})
                    (map (fn [[nm src]] [(name nm) src]))
                    (filter (fn [[ns-str _]] (exemplar-ns? ns-str)))
                    (sort-by (fn [[ns-str _]] (exemplar-sort-key ns-str))))
        blocks (keep (fn [[ns-str src]]
                       (if (or (str/blank? src)
                               (= (str/trim src) (str "(ns " ns-str ")")))
                         (do (seon-log/error-console!
                               "seon.agent/exemplars-section"
                               (str "exemplar ns " ns-str " has no full "
                                    ":seon.ns/source (stub or blank) — "
                                    "omitted from the section; the boot "
                                    "indexer should have persisted the "
                                    "real file text"))
                             nil)
                         (str "<exemplar ns=\"" ns-str "\">\n"
                              (str/trim src)
                              "\n</exemplar>")))
                     rows)]
    (if (seq blocks)
      (str "<exemplars>\n" exemplars-header "\n\n"
           (str/join "\n\n" blocks)
           "\n</exemplars>")
      "")))

;; ============================================================
;; schema-catalog-section — the GLOBAL cross-namespace catalog of every
;; ENTITY kind stored in the system. Layered ON TOP of the per-ns
;; `namespace-context` (T5): namespace-context is the DEEP current-ns
;; view (this ns's fns/tests/source); the catalog is the BROAD view —
;; ALL the kinds of things that exist in the substrate, REGARDLESS of
;; the agent's current ns. This is HOW the agent knows what data the
;; system holds (user, 2026-06-08 night).
;;
;; DERIVED, never hardcoded: the catalog reads the `:seon.schema`
;; entities seeded at boot (`all-entity-schemas-tx-data`). An entity
;; KIND is a `:seon.schema` entity that carries a `:seon.schema/render-fn`
;; — i.e. a renderable `:map` entity-shape schema (`:seon.fn`, `:seon.ns`,
;; `:seon.eval`, `:seon.message`, `:seon.schema`, `:seon.test`, …) — as
;; opposed to a request/response `:map` (which has no render symbol). The
;; `:seon.schema` entity stores the kind's `:seon.schema/id-attr`; the
;; per-attr SHAPE (type + which attrs are optional) is pulled from the
;; live registry via `seon.schema/schema-definition`, the source the
;; `:seon.schema` entity doesn't itself carry. Instance counts come from
;; one AEVT count on each kind's id-attr — defined-but-empty kinds still
;; list (count 0 is informative).
;;
;; Pure render of the DB + registry — stores nothing.
;; ============================================================

(defn- catalog-type-str
  "Render an attr's registered Malli form as a COMPACT type label for the
   catalog: a bare keyword as-is; `[:and {…} inner]` (the identity-wrap)
   as its inner ref; `[:vector/:set {…} elem]` as `vector<elem>`;
   `[:enum …]` as `enum (…)`; any other `[:type {props}]` as just `:type`.
   Keeps each attr line to one short token so a whole-system catalog of
   ~10 kinds stays a few KB."
  [t]
  (cond
    (keyword? t) (str t)
    (vector? t)
    (let [head (first t)
          rst  (rest t)]
      (case head
        :and (let [inner (remove map? rst)]
               (if (= 1 (count inner))
                 (catalog-type-str (first inner))
                 (str "(" (str/join " & " (map catalog-type-str inner)) ")")))
        :or  (str "(" (str/join " | " (map catalog-type-str (remove map? rst))) ")")
        (:vector :set) (str (name head) "<" (catalog-type-str (last (remove map? rst))) ">")
        :enum (str "enum " (pr-str (vec rst)))
        (str head)))
    :else (pr-str t)))

(defn- catalog-attr-rows
  "Attribute rows for an entity KIND, pulled from the live registry
   (`seon.schema/schema-definition`). Each row:
   `{:attr <kw> :type <compact-str> :optional <bool> :id? <bool>}`.
   Returns nil when `kind` isn't a registered `:map` schema. The id-attr
   is read from the schema's derived `:seon.entity/id-attr` prop."
  [kind]
  (let [form (schema/schema-definition kind)]
    (when (and (vector? form) (= :map (first form)))
      (let [props   (when (map? (second form)) (second form))
            id-attr (:seon.entity/id-attr props)
            body    (let [b (rest form)]
                      (if (and (seq b) (map? (first b))) (rest b) b))]
        (for [entry body :when (and (vector? entry) (keyword? (first entry)))]
          (let [k     (first entry)
                eprops (let [p (second entry)] (when (map? p) p))]
            {:attr     k
             :type     (catalog-type-str (schema/schema-definition k))
             :optional (boolean (:optional eprops))
             :id?      (= k id-attr)}))))))

(defn- catalog-kind-count
  "Count instances of `kind` by counting datoms on its `id-attr`
   (one AEVT scan). Bounded: one count query per kind."
  [db id-attr]
  (count (db/query {:seon.db/db db
                    :seon.db/query [:find '?e :where ['?e id-attr '_]]})))

(def ^:private uncounted-kind-id-attrs
  "Id-attrs of HIGH-CHURN substrate kinds whose live instance count
   changes EVERY turn (each eval + message is an instance) — rendering
   an exact count here would bust the prompt-cache prefix on every
   render (context-audit 2026-06-09 §4). The kind + attrs still list;
   the instances themselves are already in the transcript."
  #{:seon.eval/id :seon.message/id})

(defn- fuzzy-count
  "Bucketed live-count label for catalog blocks: exact below 20, then
   rounded DOWN to a bucket (\"40+\", \"300+\", \"2000+\") so slow corpus
   growth doesn't bust the semi-static catalog prefix per increment."
  [n]
  (cond
    (< n 20)   (str n)
    (< n 200)  (str (* 10 (quot n 10)) "+")
    (< n 2000) (str (* 100 (quot n 100)) "+")
    :else      (str (* 1000 (quot n 1000)) "+")))

(defn- catalog-kind-block
  "Render one entity kind: a `[kind  N instances]` header then one line
   per attribute (`id`/`opt` flags + compact type). The id-attr line is
   marked `id`; optional attrs are marked `?`. High-churn substrate
   kinds (`uncounted-kind-id-attrs`) render without a count; other
   kinds use the bucketed `fuzzy-count` label — both are cache-prefix
   stability measures."
  [db {:keys [kind id-attr]}]
  (let [rows  (sort-by (fn [{:keys [id? attr]}] [(if id? 0 1) (str attr)])
                       (catalog-attr-rows kind))
        lines (for [{:keys [attr type optional id?]} rows]
                (str "  " (cond id? "id " optional "?  " :else "   ")
                     attr " : " type))
        label (if (contains? uncounted-kind-id-attrs id-attr)
                "(per-turn data — uncounted)"
                (let [n (fuzzy-count (catalog-kind-count db id-attr))]
                  (str n " instance" (when (not= "1" n) "s"))))]
    (str "[" kind "]  " label "\n"
         (str/join "\n" lines))))

(defn- db-schema
  "The datahike schema map of `db`, FilteredDB-safe. FilteredDB (the
   inspector's per-agent view) doesn't implement ILookup — `(:schema db)`
   THROWS. The schema is conn-level (the filter can't change it), so
   read through to the wrapped db. Same guard as
   `seon.warn/domain-attrs`; surfaced live at the flip (2.2e) because
   the cluster store carries attrs absent from the live Malli registry
   (other writers' attrs), which sent [[domain-attr-line]] down the
   installed-valueType fallback for the first time on a FilteredDB."
  [db]
  (try (:schema db)
       (catch :default _
         (:schema (.-unfiltered-db ^js db)))))

(defn- domain-attr-line
  "One catalog line for a DOMAIN attr: keyword, compact type (live
   registry when present, installed datahike valueType otherwise) and
   the live instance count — `duration-seconds (2 entities)` is what
   makes an existing attr hard to miss."
  [db attr]
  (let [t (if-let [form (schema/schema-definition attr)]
            (catalog-type-str form)
            (str (get-in (db-schema db) [attr :db/valueType])))
        n (fuzzy-count (catalog-kind-count db attr))]
    (str "  " attr " : " t " — " n " entit" (if (= "1" n) "y" "ies"))))

(defn- domain-attrs-block
  "The `domain data attrs` portion of the catalog: every agent-
   registered attr installed on the db (via [[seon.warn/domain-attrs]]
   — substrate internals excluded), grouped by keyword namespace, each
   with type + live instance count. Empty string when no domain attrs
   exist yet. This is the REUSE surface: run 4 proved an agent forks a
   parallel attr when the existing shape isn't in front of it."
  [db]
  (let [attrs  (warn/domain-attrs {:seon.db/db db})
        groups (->> attrs (group-by namespace) (sort-by first))]
    (if (seq attrs)
      (str "\n\n=== domain data attrs — REUSE these exact keywords ===\n"
           ";; Attrs already holding your human's data. Before you register!\n"
           ";; a new attr, check here: same kind of fact → use the EXISTING\n"
           ";; attr (exact keyword, exact unit). Extend with new attrs only\n"
           ";; for genuinely new facts; never fork the same quantity into\n"
           ";; different units.\n"
           (str/join "\n"
             (for [[ns-str ks] groups]
               (str "[" ns-str "]\n"
                    (str/join "\n" (map #(domain-attr-line db %) ks))))))
      "")))

(defn- squash-one-line
  "Whitespace-squash + cap a stored string for a one-line catalog row."
  [s]
  (let [flat (str/replace (str s) #"\s+" " ")]
    (if (> (count flat) 140) (str (subs flat 0 140) " …") flat)))

(defn- finding-claims-block
  "One-liner CONTENT of stored findings — the claim strings themselves,
   not just attr names (#26 finding-salience: run 7 proved attr names in
   the catalog are discoverable but not CONSULTED — agent #2 re-derived
   a stored answer). Renders the values of every domain attr NAMED
   `claim` (any namespace — the taught shape is :kb.finding/claim, but
   earlier corpora used other namespaces), capped at 12 rows of ≤140
   chars. Empty string when no claims exist. Pure render of the db —
   stores nothing."
  [db]
  (let [claim-attrs (->> (warn/domain-attrs {:seon.db/db db})
                         (filter #(= "claim" (name %))))
        rows (->> claim-attrs
                  (mapcat (fn [a]
                            (->> (db/query {:seon.db/db db
                                            :seon.db/query
                                            [:find '?v :where ['_ a '?v]]})
                                 (map first)
                                 sort
                                 (map (fn [v] [a v])))))
                  (take 12))]
    (if (seq rows)
      (str "\n\n=== stored findings — CONSULT these before re-deriving ===\n"
           ";; Claims prior agents verified and stored. If one answers the\n"
           ";; question at hand, pull its full row (sibling attrs in the\n"
           ";; same namespace: question, source path, line, confidence)\n"
           ";; instead of re-searching the repo.\n"
           (str/join "\n"
             (for [[a v] rows]
               (str "  " a " — \"" (squash-one-line v) "\""))))
      "")))

(defn- schema-ns-summary-block
  "Compact index of EVERY registered schema in the system, as per-ns
   count lines (unit #23 fix b: all ~276 registered schemas are now
   `:seon.schema` rows; rendering each would blow the context budget, so
   the catalog shows the index and teaches the entity-read). Namespaced
   keys only — the un-namespaced entity KINDS already render as full
   blocks above. Counts are bucketed (`fuzzy-count`) for cache-prefix
   stability."
  [db]
  (let [ks     (->> (db/query {:seon.db/db db
                               :seon.db/query
                               '[:find ?k :where [?e :seon.schema/key ?k]]})
                    (map first)
                    (filter namespace))
        groups (->> ks (group-by namespace) (sort-by first))]
    (if (seq groups)
      (str "\n\n=== all registered schemas, by namespace ===\n"
           ";; Every registered schema is a :seon.schema row; read a shape:\n"
           ";; (:seon.schema/source (seon.db/entity\n"
           ";;    {:seon.db/ref [:seon.schema/key :seon.db/ref]}))\n"
           (str/join "\n"
             (for [[ns-str ns-ks] groups]
               (str "  " ns-str " — " (fuzzy-count (count ns-ks))
                    " schema" (when (not= 1 (count ns-ks)) "s")))))
      "")))

(defn schema-catalog-section
  "GLOBAL schema catalog — EVERY registered entity KIND in the system,
   grouped by owning namespace, REGARDLESS of the agent's current ns.
   This is how the agent knows what data the substrate holds: each kind's
   key, its attributes (name + compact type, identity attr flagged), and
   a live instance count.

   DERIVED from the `:seon.schema` entities (seeded at boot via
   `seon.schema/all-entity-schemas-tx-data`) — a kind is a `:seon.schema`
   entity carrying a `:seon.schema/render-fn` (a renderable `:map`
   entity-shape, not a request/response map). Per-attr shapes come from
   the live registry; counts from an AEVT scan on each id-attr. A
   trailing `domain data attrs` block lists every agent-registered attr
   installed on the db (with type + instance count) and states the
   reuse contract — the run-4 fix for forked parallel attrs; a trailing
   `stored findings` block surfaces finding CONTENT one-liners (the #26
   consult-before-research salience fix). Stores nothing; register a
   new entity kind and it appears here next render."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db]}]
  (let [kinds (->> (db/query
                     {:seon.db/db db
                      :seon.db/query
                      '[:find ?k ?ida
                        :where
                        [?e :seon.schema/key ?k]
                        [?e :seon.schema/id-attr ?ida]
                        [?e :seon.schema/render-fn _]]})
                   (map (fn [[k ida]]
                          {:kind k :id-attr ida :owner-ns (namespace ida)})))
        groups (->> kinds
                    (group-by :owner-ns)
                    (sort-by first))]
    (if (seq kinds)
      (str "<schema-catalog>\n"
           ";; Every kind of entity stored in the system, grouped by namespace.\n"
           ";; This is the WHOLE substrate — not just your current ns. These\n"
           ";; shapes EXIST: REUSE their exact attrs (copy keywords + units\n"
           ";; exactly); register! only what's missing. Query any kind by its\n"
           ";; id-attr, e.g. (seon.db/query {:seon.db/query\n"
           ";;   '[:find ?id :where [?e :seon.fn/sym ?id]]}).\n\n"
           (str/join "\n\n"
             (for [[ns ks] groups]
               (str "=== " ns " ===\n"
                    (str/join "\n\n"
                      (map #(catalog-kind-block db %)
                           (sort-by (comp str :kind) ks))))))
           (schema-ns-summary-block db)
           (domain-attrs-block db)
           (finding-claims-block db)
           "\n</schema-catalog>")
      "")))

;; ============================================================
;; render-namespace — the foundational whole-namespace render.
;;
;; Renders ONE namespace (ns source + its fns + schemas + tests) in
;; either :ai text or :html hiccup, recursing into the namespaces it
;; `(:require …)`s. Required nses render FIRST (prepended) so that, read
;; top-to-bottom, a reference resolves before its use. The default
;; context an agent receives is built from this: drop an agent into a
;; near-empty ns that requires a parent agent ns, and depth-1 brings the
;; parent's fns/schemas into view.
;;
;; Pure function of the DB — stores nothing. Per-member output is bounded
;; here (signature + doc by default, full source only for small fns); the
;; clip guardrail is a later backstop, not a crutch.
;; ============================================================

(def ^:private fn-source-inline-threshold
  "Fns whose `:seon.fn/source` is at or under this many chars render
   their full source in the :ai form; larger fns show signature + doc
   only. Keeps a whole-ns render bounded to a few KB."
  240)

(def ^:private member-doc-clip
  "Max chars of a fn docstring surfaced per member in the :ai form."
  280)

(defn- clip
  "Clip `s` to `n` chars with an ellipsis marker. nil-safe."
  [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 n) " …") s)))

(defn- parse-require-syms
  "Parse an `(ns … (:require …))` source string and return the vector of
   required namespace symbols (in declaration order, deduped). Handles
   bare-symbol specs (`a.b`) and vector specs (`[a.b :as c :refer […]]`).
   Returns [] on any parse failure or when there's no `(ns …)` form —
   recursion simply stops rather than erroring."
  [src]
  (if (or (nil? src) (str/blank? src))
    []
    (try
      (let [form (edn/read-string src)]
        (if (and (seq? form) (= 'ns (first form)))
          (->> (rest form)
               (filter #(and (seq? %) (= :require (first %))))
               (mapcat rest)
               (keep (fn [spec]
                       (cond
                         (symbol? spec)     spec
                         (sequential? spec) (first spec)
                         :else              nil)))
               (filter symbol?)
               distinct
               vec)
          []))
      (catch :default _ []))))

(defn- pull-ns-data
  "Reverse-ref pull of everything one `:seon.ns` owns: its source plus
   every `:seon.fn` / `:seon.schema` / `:seon.test` whose `:ns` points at
   it. Returns nil when no `:seon.ns` entity exists for `ns-kw` (the
   caller renders a one-line 'not in db' note instead). `:seon.test` is a
   real entity kind (Step 3); its rows are pulled and rendered under the
   ns alongside fns and schemas.

   Guarded by an `entity` existence check first: `db/pull` throws on an
   unresolved lookup-ref, so we confirm presence before pulling."
  [db ns-kw]
  (when (db/entity {:seon.db/db db :seon.db/ref [:seon.ns/name ns-kw]})
    (let [core (db/pull {:seon.db/db db
                         :seon.db/ref [:seon.ns/name ns-kw]
                         :seon.db/pull-pattern
                         '[:seon.ns/source
                           {:seon.fn/_ns     [:seon.fn/sym :seon.fn/arglists
                                              :seon.fn/doc :seon.fn/source
                                              :seon.fn/private? :seon.fn/spec
                                              :seon.fn/schema-error]
                            :seon.schema/_ns [:seon.schema/key :seon.schema/source]}]})
          ;; :seon.test is now a real entity kind (Step 3): `:seon.test/ns`
          ;; IS registered, so this reverse-ref pull resolves. Kept as a
          ;; SEPARATE guarded call (vs. inlining into the `core` pull) for
          ;; cleanliness: a conn that has no `:seon.test` rows for this ns
          ;; yields nil and the merge below is a no-op.
          tests (try
                  (-> (db/pull {:seon.db/db db
                                :seon.db/ref [:seon.ns/name ns-kw]
                                :seon.db/pull-pattern
                                '[{:seon.test/_ns
                                   [:seon.test/sym :seon.test/source
                                    :seon.test/last-passed-at
                                    :seon.test/last-failed-at
                                    :seon.test/last-failure-summary]}]})
                      :seon.test/_ns)
                  (catch :default _ nil))]
      (cond-> core
        (seq tests) (assoc :seon.test/_ns tests)))))

(defn- fn-block-ai
  "One fn rendered for the :ai form: `(sym arglists)` header, clipped
   doc, and full source only when small. Reuses the conventional
   signature shape via `seon.handlers.fn/render-ai` is overkill here
   (that fn also runs test-status queries); we render flat + bounded."
  [{:seon.fn/keys [sym arglists doc source private? spec schema-error]}]
  (let [sig    (when (and arglists (not (str/blank? arglists)))
                 (let [a (str/trim arglists)]
                   (if (and (str/starts-with? a "(") (str/ends-with? a ")"))
                     (str "(" sym " " (subs a 1 (dec (count a))) ")")
                     (str "(" sym " " a ")"))))
        flags  (cond-> []
                 private?      (conj ":private")
                 (some? spec)  (conj (str ":spec " (clip spec 80)))
                 (nil? spec)   (conj ":unspecced")
                 schema-error  (conj (str ":schema-error " (clip schema-error 80))))
        header (str "[fn " sym "]"
                    (when sig (str "  " sig))
                    (when (seq flags) (str "  " (str/join " " flags))))
        small? (and source (<= (count source) fn-source-inline-threshold))
        lines  (cond-> [header]
                 (and doc (not (str/blank? doc)))
                 (conj (str ";; " (clip (first (str/split-lines doc)) member-doc-clip)))
                 small?
                 (conj (str/trim source)))]
    (str/join "\n" lines)))

(defn- schema-block-ai
  "One schema rendered for the :ai form: `[schema :ns/key]  <malli form>`.
   Pulls the live shape from the registry; falls back to the persisted
   `:seon.schema/source` when the registry has no entry."
  [{:seon.schema/keys [key source]}]
  (let [shape (when (keyword? key)
                (try (schema/schema-definition key) (catch :default _ nil)))
        form  (cond
                shape                       (clip (pr-str shape) 200)
                (not (str/blank? source))   (clip (str/trim source) 200)
                :else                       "<not registered>")]
    (str "[schema " (pr-str key) "]  " form)))

(defn- test-block-ai
  "One test rendered for the :ai form — `[test sym]` header, the
   pass/fail status line (✓/✗/•), and clipped source. The status glyph
   is derived via the shared `seon.handlers.test/status-line` — the
   SINGLE source of the ✓/✗/• logic — so this whole-ns block and the
   per-kind `seon.handlers.test/render-ai` never diverge."
  [{:seon.test/keys [sym source] :as test}]
  (str "[test " sym "]"
       "\n" (h-test/status-line test)
       (when (and source (not (str/blank? source)))
         (str "\n" (clip (str/trim source) fn-source-inline-threshold)))))

(defn- render-one-ns-ai
  "Render a single namespace block to text. `ns-kw` is the namespace
   keyword; `data` is the `pull-ns-data` result (or nil = not in db)."
  [ns-kw data]
  (if (nil? data)
    (str ";; requires: " (name ns-kw) " (not in db)")
    (let [src     (:seon.ns/source data)
          fns     (->> (:seon.fn/_ns data)     (sort-by :seon.fn/sym))
          schemas (->> (:seon.schema/_ns data) (sort-by (comp str :seon.schema/key)))
          tests   (->> (:seon.test/_ns data)   (sort-by :seon.test/sym))
          body    (cond-> []
                    (and src (not (str/blank? src)))
                    (conj (str/trim src))
                    (seq fns)
                    (into (map fn-block-ai fns))
                    (seq schemas)
                    (into (map schema-block-ai schemas))
                    (seq tests)
                    (into (map test-block-ai tests)))]
      (str "<namespace name=\"" (name ns-kw) "\">\n"
           (if (seq body) (str/join "\n\n" body) ";; (no recorded source/fns/schemas)")
           "\n</namespace>"))))

(defn- render-one-ns-html
  "Render a single namespace block to hiccup. Reuses the per-kind
   `seon.handlers.{ns,fn,schema}/render-html` for each member so the
   webview card styling stays consistent with the inspector panes."
  [db ns-kw data]
  (if (nil? data)
    [:div {:class "py-1 text-xs font-mono text-text-500 italic"}
     (str "requires: " (name ns-kw) " (not in db)")]
    (let [fns     (->> (:seon.fn/_ns data)     (sort-by :seon.fn/sym))
          schemas (->> (:seon.schema/_ns data) (sort-by (comp str :seon.schema/key)))
          tests   (->> (:seon.test/_ns data)   (sort-by :seon.test/sym))
          ns-ent  {:seon.ns/name ns-kw}]
      (into
        [:section {:class "py-1 border-l-2 border-base-700 pl-2"}
         (:seon.render/hiccup (h-ns/render-html {:seon.db/db db :seon.render/entity ns-ent}))]
        (concat
          (for [f fns]
            (:seon.render/hiccup
              (h-fn/render-html {:seon.db/db db :seon.render/entity f})))
          (for [s schemas]
            (:seon.render/hiccup
              (h-schema/render-html {:seon.db/db db :seon.render/entity s})))
          ;; Tests rendered via the per-kind handler — same `test-status`
          ;; source as the AI path, so the pass/fail pill never diverges.
          (for [t tests]
            (:seon.render/hiccup
              (h-test/render-html {:seon.db/db db :seon.render/entity t}))))))))

(defn- collect-ns-order
  "Compute the ordered, deduped list of namespace keywords to render —
   required nses FIRST (prepended), then the ns itself, recursing to
   `depth`. Cycle- and revisit-safe: a ns already in the accumulator is
   never expanded or re-added. depth 0 = just `ns-kw` (no requires).

   Returns `[ordered-kws data-by-kw]` where `data-by-kw` caches each
   ns's `pull-ns-data` result (possibly nil for not-in-db requires)."
  [db ns-kw depth]
  (let [data-by-kw (atom {})
        seen       (atom #{})
        order      (atom [])
        ;; memoized pull
        data-for   (fn [k]
                     (if (contains? @data-by-kw k)
                       (@data-by-kw k)
                       (let [d (pull-ns-data db k)]
                         (swap! data-by-kw assoc k d)
                         d)))
        walk       (fn walk [k d]
                     (when-not (contains? @seen k)
                       (swap! seen conj k)
                       (let [data (data-for k)
                             reqs (when (and data (pos? d))
                                    (->> (parse-require-syms (:seon.ns/source data))
                                         (map keyword)))]
                         ;; required nses first (prepended), then self
                         (doseq [r reqs] (walk r (dec d)))
                         (swap! order conj k))))]
    (walk ns-kw depth)
    [@order @data-by-kw]))

(schema/register! :seon.render/depth :int)
(schema/register! :seon.render/format [:enum :ai :html])

(schema/register! ::render-namespace-request
  [:map
   [:seon.ns/name        :seon.ns/name]
   [:seon.render/depth   {:optional true} :seon.render/depth]
   [:seon.render/format  {:optional true} :seon.render/format]
   [:seon.db/db          {:optional true} :seon.db/db]])

(schema/register! ::render-namespace-response
  [:map
   [:seon.render/text   {:optional true} :string]
   [:seon.render/hiccup {:optional true} [:fn render/valid-hiccup?]]])

(defn render-namespace
  "Render a WHOLE namespace — its `(ns …)` source plus every `:seon.fn`,
   `:seon.schema`, and (when the kind exists) `:seon.test` it owns — in
   either `:ai` text or `:html` hiccup, recursing into the namespaces it
   `(:require …)`s.

   Required namespaces render FIRST (prepended), then the namespace
   itself, to `:seon.render/depth` (default 1 = the ns + its direct
   requires). Recursion is deduped (each ns rendered once) and cycle-safe.
   A required ns with no `:seon.ns` entity is noted on a single line
   (`requires: x.y (not in db)`), never errored.

   Map-in / map-out:

     {:seon.ns/name <keyword>
      :seon.render/depth  <int, default 1>
      :seon.render/format <:ai | :html, default :ai>
      :seon.db/db <db value, optional — defaults to @*conn*>}

   → {:seon.render/text <string>}     for :ai
   → {:seon.render/hiccup <hiccup>}   for :html

   This is the foundation of every agent's default context; the section
   that surfaces the agent's namespaces resolves to it (T5)."
  {:malli/schema [:=> [:cat ::render-namespace-request] ::render-namespace-response]}
  [{ns-name :seon.ns/name
    :seon.render/keys [depth format]
    :seon.db/keys [db]
    :or {depth 1 format :ai}}]
  (let [db    (or db @db/*conn*)
        ns-kw (if (keyword? ns-name) ns-name (keyword (str ns-name)))
        [order data-by-kw] (collect-ns-order db ns-kw (max 0 depth))]
    (if (= format :html)
      {:seon.render/hiccup
       (into [:div {:class "flex flex-col gap-2"}]
             (for [k order]
               (render-one-ns-html db k (data-by-kw k))))}
      {:seon.render/text
       (str/join "\n\n" (for [k order]
                          (render-one-ns-ai k (data-by-kw k))))})))

(defn namespace-context-section
  "The agent's NAMESPACE context — `render-namespace` of the agent's
   current namespace at depth 1, so its direct `(:require …)`s render
   FIRST (prepended), then the ns itself. Drop a fresh agent into a
   near-empty home-ns that requires a parent agent ns and depth-1 brings
   the parent's fns/schemas/tests into view.

   Pure render of the DB: `render-namespace` reads the persisted
   `:seon.ns`/`:seon.fn`/`:seon.schema`/`:seon.test` corpus and stores
   nothing. Renders blank only when the ns has no recorded entities and
   no requires (a brand-new home-ns before any `(ns …)`/`(defn …)`)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [ns    (current-ns {:seon.agent/id id})
        ;; current-ns returns a SYMBOL (the home-ns / latest eval's ns);
        ;; render-namespace's input schema requires a keyword :seon.ns/name.
        ns-kw (if (keyword? ns) ns (keyword (str ns)))
        text  (-> (render-namespace {:seon.ns/name ns-kw
                                     :seon.render/depth 1
                                     :seon.render/format :ai
                                     :seon.db/db db})
                  :seon.render/text)
        ;; Empty-ns nudge (unit #23 fix c): when the CURRENT ns owns no
        ;; fns/schemas/tests, say so and teach the move — context follows
        ;; the namespace, so an agent sitting in an empty ns should either
        ;; define here or (ns …) to where the code is.
        data  (pull-ns-data db ns-kw)
        empty-ns? (and (empty? (:seon.fn/_ns data))
                       (empty? (:seon.schema/_ns data))
                       (empty? (:seon.test/_ns data)))
        ;; A fresh agent's own not-yet-in-db home-ns used to render as
        ;; ';; requires: <own-ns> (not in db)' — a mislabel (it's not a
        ;; require; context-audit item 11). Drop that lone line; the
        ;; empty-ns nudge below says it properly.
        text  (if (and (nil? data)
                       (= (str/trim text)
                          (str ";; requires: " (name ns-kw) " (not in db)")))
                ""
                text)
        nudge (when empty-ns?
                (str ";; Your current namespace (" (name ns-kw) ") is EMPTY —\n"
                     ";; no fns, schemas or tests yet. Define here, or switch\n"
                     ";; with (ns other.ns) to move where the code is: your\n"
                     ";; context follows your namespace."))]
    (cond
      (and (str/blank? text) (nil? nudge)) ""
      (str/blank? text)
      (str "<namespace-context>\n" nudge "\n</namespace-context>")
      :else
      (str "<namespace-context>\n" text
           (when nudge (str "\n\n" nudge))
           "\n</namespace-context>"))))

;; ============================================================
;; functions-catalog-section — the THIN cross-namespace INDEX of the fn
;; corpus. The sibling of `schema-catalog-section`: the catalog answers
;; "what KINDS of data exist"; this answers "what CODE already exists".
;; This is how a later agent (or a later turn) discovers and reuses an
;; earlier agent's work instead of re-deriving it (user, 2026-06-09 —
;; kill the over-orientation / re-implementation loop).
;;
;; Collapsed to a thin index (context-focus-redesign §2, unit E2/E3):
;;   - SUBSTRATE nses (compiled seon.* code) — ONE count line per ns;
;;     bodies are one `:seon.fn/source` pull away (the header teaches
;;     the query). Exemplar-root nses cross-reference the full source
;;     rendered in :exemplars above.
;;   - AGENT-AUTHORED nses — one callable line per fn for small groups,
;;     a count line for large ones. The agent's OWN ns renders its full
;;     source in :namespace-context (the deep current-ns view) — the
;;     old own-ns full-source duplicate here DIED with the redesign.
;;
;; DERIVED, never hardcoded: one datalog join over the `:seon.fn` corpus
;; (the same entities `index-substrate!` seeds and detect-and-tee
;; appends). Define a fn → it appears here next render; stores nothing.
;; ============================================================

(defn- substrate-ns-name?
  "True when `ns-str` names a COMPILED-substrate namespace — `seon.*`
   but not the per-agent home nses (`seon.agent.<id>`). Used to pick
   the count-line depth in the functions catalog. A NAME rule, not the
   var-derived `seon.client/substrate-ns-set` (require direction: client
   requires agent) — agents author in `seon.agent.<id>` home nses and
   their own domain nses (`kb.findings`, …), neither of which matches,
   so agent code keeps per-fn lines."
  [ns-str]
  (and (str/starts-with? ns-str "seon.")
       (not (str/starts-with? ns-str "seon.agent."))))

(defn- fn-catalog-block-brief
  "One AGENT-authored fn for the catalog: ONE LINE — the first-arity
   callable signature only. Compact — the agent only needs to know it
   exists and how to call it; doc + body are one `:seon.fn` pull away
   (the header teaches the query)."
  [{:keys [sym arglists]}]
  (str "  " (first (callable-sigs sym arglists))))

(def ^:private fn-catalog-brief-max
  "Agent-authored ns groups with at most this many fns render one line
   per fn; larger groups collapse to a single count line. The DB carries
   everything either way — the catalog shows the index, the header
   teaches the query."
  8)

(defn- fn-catalog-summary-line
  "Single count line for a substrate or LARGE agent-ns group — the fns
   are all `:seon.fn` rows; the catalog header teaches how to list them,
   so the line is JUST `ns — N fns` (the old per-line 'query :seon.fn
   rows' boilerplate repeated ~30× and tripled the section)."
  [ns-name ns-fns]
  (str "  " ns-name " — " (count ns-fns)
       " fn" (when (not= 1 (count ns-fns)) "s")))

(defn functions-catalog-section
  "THIN index of every fn defined in the substrate, grouped by owning
   namespace — the sibling of `schema-catalog-section`. Substrate nses
   collapse to one count line each (exemplar nses cross-reference their
   full source in :exemplars above); agent-authored nses render one
   callable line per fn (count line when large). The agent's own ns
   source renders ONCE per prompt — in :namespace-context, not here.

   DERIVED from the `:seon.fn` corpus (one datalog join `:seon.fn` →
   `:seon.fn/ns` → `:seon.ns/name`); stores nothing. Define a fn and it
   appears here next render."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db]}]
  (let [rows   (db/query
                 {:seon.db/db db
                  :seon.db/query
                  '[:find ?sym ?nm ?arglists
                    :where
                    [?f :seon.fn/sym ?sym]
                    [?f :seon.fn/ns ?ns]
                    [?ns :seon.ns/name ?nm]
                    [(get-else $ ?f :seon.fn/arglists "") ?arglists]]})
        fns    (map (fn [[sym nm arglists]]
                      {:sym sym :ns (name nm) :arglists arglists})
                    rows)
        groups (->> fns (group-by :ns) (sort-by first))]
    (if (seq fns)
      (str "<functions>\n"
           ";; Every fn defined across the WHOLE substrate is a :seon.fn row,\n"
           ";; indexed here by namespace. This is a COUNT INDEX — more exists\n"
           ";; than is shown. List any namespace's fns from the db, e.g.:\n"
           ";;   (seon.db/query {:seon.db/query\n"
           ";;     '[:find ?sym ?arglists :where [?f :seon.fn/ns ?n]\n"
           ";;       [?n :seon.ns/name :seon.db] [?f :seon.fn/sym ?sym]\n"
           ";;       [(get-else $ ?f :seon.fn/arglists \"\") ?arglists]]})\n"
           ";; and pull :seon.fn/source / :seon.fn/doc by [:seon.fn/sym \"…\"].\n"
           ";; Check here BEFORE writing a helper — it may already exist.\n\n"
           (str/join "\n"
             (for [[ns-name ns-fns] groups]
               (cond
                 (exemplar-ns? ns-name)
                 (str (fn-catalog-summary-line ns-name ns-fns)
                      " (full source above)")

                 (substrate-ns-name? ns-name)
                 (fn-catalog-summary-line ns-name ns-fns)

                 (<= (count ns-fns) fn-catalog-brief-max)
                 (str "=== " ns-name " ===\n"
                      (str/join "\n"
                        (map fn-catalog-block-brief (sort-by :sym ns-fns))))

                 :else
                 (fn-catalog-summary-line ns-name ns-fns))))
           "\n</functions>")
      "")))

(defn warnings-section
  "Render current problems as ONE clustered `<warnings>` block via the
   `seon.warn` check registry: one complete explanation + one targeted
   fix example per kind, then the affected list with specific locations.
   Empty string when everything is clean; warnings vanish the moment the
   underlying state goes away (derived, never stored — see
   docs/seon/concepts/reactive-context).

   The CORPUS checks (no-malli-schema, return-is-any, arg-is-any,
   uses-maybe, no-return-spec, no-input-spec, missing-test) default to
   the agent's CURRENT ns so an agent isn't confused by other
   namespaces' defects. Override per-section via the `:seon.ctx` entity:
   `:seon.warn/ns <ns-kw>` scopes to that ns; `:seon.warn/ns
   :seon.warn/all` is the whole-substrate overview. The RUNTIME checks
   (failed-evals, bad-ref, slow-evals, failing-tests) are always global
   — cross-agent visibility is their point.

   To add a warning kind, add a check fn to `seon.warn/checks`."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id] :as input}]
  (let [override (:seon.warn/ns (:seon.agent/ctx-entity input))
        scope    (cond
                   (= override :seon.warn/all) nil
                   (some? override)            override
                   :else
                   (let [ns (current-ns {:seon.agent/id id})]
                     (if (keyword? ns) ns (keyword (str ns)))))]
    (warn/render-warnings
      (cond-> {:seon.db/db db}
        (some? scope) (assoc :seon.warn/ns scope)))))

(def transcript-char-budget
  "Total rendered-chars cap for the transcript section (~6k tokens at
   chars/4). Why 24,000: the audit measured an UNBOUNDED transcript at
   90,468 chars by turn 58 — 83% of a 27k-token context, dominating
   both spend and the model's attention. 24k keeps the newest ~15
   worst-case eval rows (≤1.6KB each via `eval-render-cap`) or several
   dozen typical items whole — comfortably more than the 2–4 turns most
   questions need — while bounding context ≈ static sections + 6k tok.
   Retention is NEWEST-FIRST: oldest items drop beyond the budget and
   an elision note replaces them at the top."
  24000)

(defn- transcript-item-at
  "Wall-clock `:at` of a transcript item (a message or an eval), as
   epoch-ms. Used to interleave the two streams chronologically."
  [item]
  (let [d (or (:seon.message/at item) (:seon.eval/at item))]
    (if d (.getTime ^js d) 0)))

(defn- format-transcript-item
  "Render one transcript item — a `:seon.message` as a REPL event
   (`user>`/`assistant>`/`agent-<id>>` line, labeled by from-ref kind)
   or a `:seon.eval` via `format-eval-row` (`> form\\n result`).
   Dispatch on which kind-keyed `:at` is present."
  [item own-id]
  (if (:seon.message/at item)
    (format-message-row item own-id)
    (format-eval-row item)))

(defn transcript-section
  "The chronological TRANSCRIPT — the agent's messages and evals
   INTERLEAVED into a single oldest-first stream, so the agent reads one
   coherent REPL session (user input as `user>`/`assistant>` events,
   evals as `> form` + result) rather than two divorced blocks. Reads
   `:seon.agent/n` from the ctx-entity if present (caps EACH stream
   before the merge), else defaults to 50 messages + 50 evals.

   Pure render: messages via `seon.agent/messages` (direct agent-ref query,
   Change B 2026-06-09); evals via `seon.agent/evals` (turn-walk). Merges
   by `:at`, stores nothing. Each eval row is
   `cap-result`-bounded (`format-eval-row`) so one huge result can't
   dominate the transcript (context-SAFETY invariant)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id] :as input}]
  (let [n     (or (:seon.agent/n (:seon.agent/ctx-entity input)) 50)
        msgs  (messages {:seon.agent/n n :seon.agent/id id})
        es    (evals    {:seon.agent/n n :seon.agent/id id})
        items (->> (concat msgs es)
                   (sort-by transcript-item-at))]
    (if (seq items)
      (let [rendered (mapv #(format-transcript-item % id) items)
            ;; NEWEST-FIRST retention under the total budget: walk from
            ;; the end accumulating rendered chars; keep the newest
            ;; items WHOLE (always at least one), drop everything older.
            kept-n   (loop [i (dec (count rendered)) acc 0 kept 0]
                       (if (neg? i)
                         kept
                         (let [acc' (+ acc (count (rendered i)) 2)]
                           (if (and (pos? kept) (> acc' transcript-char-budget))
                             kept
                             (recur (dec i) acc' (inc kept))))))
            elided   (- (count rendered) kept-n)
            kept     (subvec rendered elided)]
        (str "<transcript>\n"
             (when (pos? elided)
               (str ";; … " elided " older item" (when (not= 1 elided) "s")
                    " elided (transcript capped at " transcript-char-budget
                    " chars; the full log is in the db — "
                    "(seon.agent/messages) / (seon.agent/evals))\n\n"))
             (str/join "\n\n" kept)
             "\n</transcript>"))
      "")))

(defn prompt-section
  "TERMINAL-style trailing prompt (unit #23 fix e, per the plan's
   REPL-PARITY CONTRACT prompt redesign): a per-turn STATUS BLOCK above,
   then a CLEAN REPL prompt as the very last line —

     ;; You are at a ClojureScript REPL — reply ONLY with forms + ;; comments.
     ;; ── turn 6 · 3 since-user (cap 20) · 2026-06-09T22:14:00.000Z ──
     my.domain.thing=>

   The status block carries the session turn count, the since-inbound
   count vs the agent's turns-cap, and the wall-clock timestamp (+ pod
   tz) — every per-turn-volatile byte lives HERE at the context tail so
   the static sections above stay a stable provider-cacheable prefix
   (context-audit 2026-06-09 §4). Turn-pressure nudges render inside
   this block when escalating (wrap up at halfway, FINAL WARNING three
   turns before `run-agentic-loop!` cuts the loop off). The final line
   is EXACTLY `<current-ns>=> ` — no trailing metadata; the agent
   completes the next REPL input. Always present (never blank)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id]}]
  (let [now      (.toISOString (js/Date.))
        ns       (current-ns {:seon.agent/id id})
        ;; current-ns returns a keyword (latest eval's :seon.eval/ns) or a
        ;; symbol (home-ns fallback) — render without the keyword colon,
        ;; like a real REPL prompt.
        ns-str   (if (keyword? ns) (name ns) (str ns))
        sess     (current-session id)
        n-turns  (count (:seon.session/turns sess))
        since-u  (turns-since-inbound {:seon.agent/id id})
        cap      (turns-cap id)
        pressure
        (cond
          (>= since-u (max 1 (- cap 3)))
          (str ";; ⚠⚠⚠ FINAL WARNING — turn " since-u "/" cap " since your\n"
               ";; human last spoke. You WILL hit the cap in a turn or two.\n"
               ";; STOP researching. TRANSACT THE :assistant MESSAGE NOW with\n"
               ";; whatever you have — even partial. Your human gets NOTHING\n"
               ";; if you don't reply.\n")
          (>= since-u (quot cap 2))
          (str ";; ⚠ Turn " since-u "/" cap " since your human last spoke —\n"
               ";; past halfway. You probably have enough. Stop reading new\n"
               ";; things; compose the :assistant reply with what you found.\n")
          (>= since-u 5)
          (str ";; Turn " since-u "/" cap " since your human last spoke —\n"
               ";; most questions need 2–4 turns. If you have the answer,\n"
               ";; reply now.\n")
          :else "")]
    (str ";; You are at a ClojureScript REPL — reply ONLY with forms + ;; comments.\n"
         ";; ── turn " n-turns " · " since-u " since-user (cap " cap ") · "
         now " (pod tz: " (host-timezone) ") ──\n"
         pressure
         ns-str "=> ")))

;; ------------------------------------------------------------
;; Composer (v1.md §5.3).
;;
;; Reads the agent's :seon.agent/ctx, sorts by priority, resolves
;; each :seon.ctx/fn symbol via seon.eval/lookup-value, calls it with
;; the system-input map (plus :seon.agent/ctx-entity), joins the
;; non-blank results.
;;
;; Return shape per MVP decision Q1 (2026-05-23): just
;; {:seon.render/text "..."}. Persisting the prompt evidence (the
;; logs/prompts/<agent>/<turn>.txt blob + :seon.turn/prompt-chars /
;; :seon.turn/prompt-file projection) is run-turn!'s responsibility —
;; composer does not double-write.
;;
;; v2 will extend section-fn return maps with :seon.render/hiccup
;; alongside :seon.render/text; the composer joins both surfaces
;; without needing a new :seon.ctx slot.
;; ------------------------------------------------------------

(defn pretty-ai
  "Fallback render when a :seon.ctx/fn symbol doesn't resolve.
   v1.md §5.1 contract: 'symbol misses fall through to pretty-print
   — the composer renders the section entity itself.'"
  [section-entity]
  (str "<unresolved-section name=\""
       (:seon.ctx/name section-entity)
       "\">"
       (pr-str (dissoc section-entity :db/id))
       "</unresolved-section>"))

;; Context-assembly shapes. The section LAYOUT is CODE
;; (substrate-default-ctx); a stored :seon.agent/ctx, when present, is an
;; OPTIONAL override. :seon.render/sections is the list of section names
;; in render order (provenance, not a content source).
(schema/register! :seon.render/sections [:vector :seon.ctx/name])

(schema/register! :seon.render/assemble-request
  [:map
   [:seon.db/db    :seon.db/db]
   [:seon.agent/id :string]])

(schema/register! :seon.render/assemble-response
  [:map
   [:seon.render/text            :string]
   [:seon.render/sections        :seon.render/sections]
   [:seon.render/token-estimate  :int]])

(defn assemble-context
  "Compose the LLM context. The section LAYOUT is CODE
   (`substrate-default-ctx`) by default; a stored `:seon.agent/ctx`, when
   present, OVERRIDES it. Pure function of the DB — stores nothing; the
   absence of stored ctx falls back to the code default, never empty.

   ONE composer, called by BOTH the agent prompt path (`render-prompt`)
   and the inspector — divergence is impossible.

   Returns
     `{:seon.render/text \"…\"
       :seon.render/sections [<section-name> ...]   ; render order
       :seon.render/token-estimate <int>}`          ; char-count / 4 v0 heuristic"
  {:malli/schema [:=> [:cat :seon.render/assemble-request]
                       :seon.render/assemble-response]}
  [{:seon.db/keys [db] :seon.agent/keys [id] :as input}]
  (let [stored   (sort-by :seon.ctx/priority
                          (:seon.agent/ctx
                            (db/pull {:seon.db/db db
                                      :seon.db/pull-pattern
                                      '[{:seon.agent/ctx
                                         [:seon.ctx/name :seon.ctx/priority
                                          :seon.ctx/fn]}]
                                      :seon.db/ref [:seon.agent/id id]})))
        sections (if (seq stored) stored (substrate-default-ctx))
        ctx-in   (assoc input :seon.agent/ctx-entity nil)
        rendered (->> sections
                      (map (fn [section]
                             (let [f  (seval/lookup-value (:seon.ctx/fn section))
                                   in (assoc ctx-in :seon.agent/ctx-entity section)]
                               (if f
                                 (f in)
                                 (pretty-ai section)))))
                      (remove str/blank?))
        text     (str/join "\n\n" rendered)]
    {:seon.render/text           text
     :seon.render/sections       (mapv :seon.ctx/name sections)
     :seon.render/token-estimate (quot (count text) 4)}))

;; ------------------------------------------------------------
;; Layout verbs — reset-ctx! restores substrate defaults; update-ctx!
;; threads f over the current :seon.agent/ctx and retract-then-adds
;; the result. Component-cardinality-many means the retract is needed
;; to drop the old ctx entities before transacting new ones (per
;; v1.md §5.4 — cardinality-many ref attrs accumulate on upsert).
;; ------------------------------------------------------------

(defn substrate-default-ctx
  "The default :seon.ctx section layout that ships with every fresh
   agent — ordered MOST-STATIC → MOST-DYNAMIC (prompt-cache friendly),
   per the context-render PRD (Phase 2) table:

     1. :system            — Seon identity + CLJS-in-Node + REPL contract (static)
     2. :capabilities      — core API worked examples (static)
     3. :exemplars         — FULL source of the exemplar namespaces
                             (seon.fs, seon.search + test sibling), queried
                             from :seon.ns/source; byte-stable for the pod's
                             life (static — inside the cache prefix)
     4. :schema-catalog    — GLOBAL catalog of every entity KIND in the
                             system (cross-ns; what DATA exists), grouped by
                             namespace with attrs + instance counts;
                             semi-static (busts only on schema register)
     5. :functions-catalog — THIN per-ns count index of every fn defined in
                             the system (cross-ns; what CODE exists);
                             semi-static (busts when a fn is (re)defined)
     6. :namespace-context — `render-namespace` of required nses + own ns
                             (mostly static; busts on ns edit)
     7. :warnings          — current cross-agent problems (failed/slow evals,
                             failing tests); reactive, vanishes when fixed (dynamic)
     8. :transcript        — messages + evals interleaved chronologically (dynamic)
     9. :prompt            — `seon.agent.<id>=>  ; turn N` (always changing)

   :exemplars sits at 22, between :capabilities (20) and :schema-catalog
   (25): system + capabilities + exemplars are all fully byte-stable while
   the catalogs are only semi-static (fuzzy counts move on corpus growth) —
   static-before-semi-static maximizes the provider-cacheable prefix
   (context-focus-redesign §2). The two catalogs are the BROAD cross-ns
   view — schema-catalog is 'what kinds of data exist', functions-catalog
   is 'what code already exists' (so a later agent reuses an earlier one's
   work instead of re-deriving it). The per-ns `namespace-context` that
   follows is the DEEP current-ns view.

   Smallest priority first. `root-pull` is DELETED (was the
   `[*]`-everywhere amplifier that flooded context); `current-turn`/
   `current-session` fold into the prompt line."
  []
  [{:seon.ctx/name :system            :seon.ctx/priority 10
    :seon.ctx/fn   'seon.agent/system-section}
   {:seon.ctx/name :capabilities      :seon.ctx/priority 20
    :seon.ctx/fn   'seon.agent/capabilities-section}
   {:seon.ctx/name :exemplars         :seon.ctx/priority 22
    :seon.ctx/fn   'seon.agent/exemplars-section}
   {:seon.ctx/name :schema-catalog    :seon.ctx/priority 25
    :seon.ctx/fn   'seon.agent/schema-catalog-section}
   {:seon.ctx/name :functions-catalog :seon.ctx/priority 27
    :seon.ctx/fn   'seon.agent/functions-catalog-section}
   {:seon.ctx/name :namespace-context :seon.ctx/priority 30
    :seon.ctx/fn   'seon.agent/namespace-context-section}
   {:seon.ctx/name :warnings          :seon.ctx/priority 40
    :seon.ctx/fn   'seon.agent/warnings-section}
   {:seon.ctx/name :transcript        :seon.ctx/priority 50
    :seon.ctx/fn   'seon.agent/transcript-section}
   {:seon.ctx/name :prompt            :seon.ctx/priority 99
    :seon.ctx/fn   'seon.agent/prompt-section}])

(defn ^:async reset-ctx!
  "Restore the substrate-default ctx layout for `agent-id` by RETRACTING
   the stored :seon.agent/ctx override (cascade-retracts the existing
   :seon.ctx entities via component semantics). With no stored ctx,
   `assemble-context` falls back to the CODE default
   (`substrate-default-ctx`) — so the agent tracks every future layout
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
