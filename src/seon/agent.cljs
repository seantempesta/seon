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
     - `message!` / `reply!` — re-exported from [[seon.agent.message]]
                              (the message-model home: from/to refs,
                              hops derivation, blank-content guard)
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

   Default symbol: `'seon.agent/assemble-context` (a transitional alias
   of `seon.ctx/assemble-context` — the ONE composer, V3-C). The
   substrate section LAYOUT is CODE (`seon.ctx/substrate-default-ctx`);
   the agent's own `:seon.agent/ctx` section maps MERGE with it by one
   priority sort (override-by-name). Each section's `:seon.render/ai`
   slot is a verbatim string or a fn symbol resolved late via
   `seon.eval/lookup-value`.

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
    [seon.agent.message :as msg]
    [seon.ctx :as ctx]
    [seon.db :as db]
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
(schema/register! :seon.agent/state         [:enum :idle :running])
;; Lifecycle (P3.5/#31, 2026-06-10): ABSENT = active. A booting pod
;; resumes every agent entity WITHOUT this attr; `complete!` stamps it;
;; un-complete is an explicit `[:db/retract …]` (mirrors
;; `seon.agent.todo/completed-at` — one vocabulary). Completed agents
;; stay queryable history: never resumed, never triggered.
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
(def capabilities-section ctx/capabilities-section)
(def exemplars-section ctx/exemplars-section)
(def schema-catalog-section ctx/schema-catalog-section)
(def functions-catalog-section ctx/functions-catalog-section)
(def render-namespace ctx/render-namespace)
(def namespace-context-section ctx/namespace-context-section)
(def warnings-section ctx/warnings-section)
(def transcript-char-budget ctx/transcript-char-budget)
(def transcript-section ctx/transcript-section)
(def prompt-section ctx/prompt-section)
(def assemble-context ctx/assemble-context)
(def substrate-default-ctx ctx/substrate-default-ctx)

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
;; The message whose wake opened this turn (optional — boot/manual turns
;; have none). `reply!` reads the CURRENT turn's woken-by → its
;; `:seon.agent.message/from` = the reply target. Derived + deterministic; no
;; reply-target atom anywhere.
(schema/register! :seon.agent.turn/woken-by     :seon.db/ref)
;; The assembled prompt is NOT persisted as a datom (three-tier storage
;; rule: datoms hold projections, blobs hold full content). run-turn!
;; writes the full prompt to logs/prompts/<agent-id>/<turn-id>.txt and
;; the turn entity carries the char count + file pointer. The old
;; `:seon.agent.turn/prompt-text` datom (silently capped at 16,406 chars by
;; cap-edn — truncated evidence for any long run) is RETIRED 2026-06-09.
;; `:seon.agent.turn/prompt-text` stays registered ONLY as the in-memory
;; plumbing key between run-turn!/with-turn!/ask-and-eval! — it is not
;; in `agent-bootstrap-attrs` and never reaches the DB.
(schema/register! :seon.agent.turn/prompt-text  :string)
(schema/register! :seon.agent.turn/prompt-chars :int)
(schema/register! :seon.agent.turn/prompt-file  :string)
(schema/register! :seon.agent.turn/messages     [:vector {:seon.db/component true} :seon.db/ref])
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
   [:seon.agent.turn/woken-by     {:optional true} :seon.agent.turn/woken-by]
   [:seon.agent.turn/prompt-text  {:optional true} :seon.agent.turn/prompt-text]
   [:seon.agent.turn/prompt-chars {:optional true} :seon.agent.turn/prompt-chars]
   [:seon.agent.turn/prompt-file  {:optional true} :seon.agent.turn/prompt-file]
   [:seon.agent.turn/messages     {:optional true} :seon.agent.turn/messages]
   [:seon.agent.turn/evals        {:optional true} :seon.agent.turn/evals]])

(schema/register! :seon.agent/sessions    [:vector {:seon.db/component true} :seon.db/ref])

;; The agent's OWN context sections — a component vector of
;; :seon.ctx/section maps (see seon.ctx). MERGED with the substrate
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
;; Substrate fns/schemas/nses populate via bootstrap.edn on first
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
(schema/register! :seon.agent
  [:map {:seon.db/entity   true
         :seon.render/html 'seon.render.default/view}
   [:seon.agent/id    :seon.agent/id]
   [:seon.agent/state :seon.agent/state]
   [:seon.agent/completed-at {:optional true} :seon.agent/completed-at]
   [:seon.agent/sessions  {:optional true} :seon.agent/sessions]
   [:seon.agent/turns-cap {:optional true} :seon.agent/turns-cap]
   [:seon.agent/ctx       {:optional true} :seon.agent/ctx]
   [:seon.render/ai   {:optional true} :seon.render/ai]
   [:seon.render/html {:optional true} :seon.render/html]])

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
   DIFFERENT sender. The to-check is load-bearing: every agent installs
   this listener, so without it ONE message wakes EVERY agent's loop —
   each stray wake is a wasted LLM call (observed live 2026-06-09).
   The from-check (`from ≠ me`) is what stops an agent's own writes —
   including its per-turn assistant message — from re-kicking itself."
  [db {eid :seon.db/e target :seon.db/v} my-eid]
  (and (= target my-eid)
       (let [msg (db/entity {:seon.db/db db :seon.db/ref eid})]
         (not= my-eid (:db/id (:seon.agent.message/from msg))))))

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
              ;; The waking message — recorded on every turn this loop
              ;; run opens (:seon.agent.turn/woken-by) so reply! can derive
              ;; its target with no atom.
              mid   (:seon.agent.message/id
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
                           (assoc input :seon.agent.turn/woken-by
                                  [:seon.agent.message/id mid]))))
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

(schema/register! :seon.agent/purpose :string)

(defn ^:async create!
  "Allocate an agent entity. Idempotent: re-calling with the same id
   resets state to :idle (transact is upsert-by-unique-id) and NEVER
   re-seeds — a resumed agent keeps its own sections. A GENUINELY NEW
   entity is seeded with its `:purpose` launch-directive section plus
   the tiny fn-shaped `:your-sections` example (`seon.ctx/seed-sections`
   — agent-self-context spec 2026-06-10). Optional
   `:seon.agent/purpose` carries the human's stated purpose (the web
   create form / a future spawner agent); absent → the
   acquire-your-purpose placeholder."
  [{:seon.agent/keys [id purpose]}]
  (let [fresh? (nil? (db/entity {:seon.db/ref [:seon.agent/id id]}))
        res    (await (db/transact!
                        {:seon.db/tx-data
                         [(cond-> {:seon.agent/id    id
                                   :seon.agent/state :idle}
                            fresh? (assoc :seon.agent/ctx
                                          (ctx/seed-sections purpose)))]}))]
    ;; Surface-errors-loudly: a failed create means NO agent entity —
    ;; everything downstream (triggers, renders) would chase a ghost.
    (when (false? (:seon.db/ok? res))
      (js/console.error
        (str "seon.agent/create! transact FAILED for " id ": "
             (:seon.error/message (:seon.db/error res)))))
    {:seon.agent/id id}))

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
   `:seon.agent.session` on first turn)."
  [{:seon.agent/keys [id llm-fn compile-state purpose]}]
  (let [{:seon.agent/keys [id]}
        (await (create! {:seon.agent/id id :seon.agent/purpose purpose}))
        agent-ns (home-ns id)]
    (install-user-trigger! {:seon.agent/id id
                    :seon.agent/llm-fn llm-fn
                    :seon.agent/compile-state compile-state})
    {:seon.agent/id id
     :seon.agent/ns agent-ns}))

;; ============================================================
;; message! / reply! — moved to seon.agent.message (P6 split,
;; 2026-06-10) so the keyword namespace matches the code namespace.
;; Re-exported on the face: the agent-taught call surface IS
;; seon.agent/message! + reply! (the capabilities text,
;; my.kb.instruction, the HTTP /chat adapter, the gym driver all say
;; seon.agent/…). Same caveat as the ctx aliases above — a def alias
;; captures the fn value at load time (pre-instrumentation); call
;; seon.agent.message/* directly for the validated entry point.
;; ============================================================

(def message! msg/message!)
(def reply! msg/reply!)
(def user-ref msg/user-ref)

;; ============================================================
;; complete! — agent lifecycle end-stamp (P3.5/#31). Same vocabulary as
;; seon.agent.todo/complete!: stamp `completed-at`, unknown id → fail
;; envelope, already-completed → idempotent success. A completed agent
;; is HISTORY: the booting pod's resume query skips it, no trigger is
;; armed, the mission-control page groups it collapsed at the bottom.
;; ============================================================

(schema/register! ::ok?    :boolean)
(schema/register! ::error  :string)

(schema/register! ::complete-request
  [:map
   ;; default: the calling agent from the ALS scope (like reply!).
   [::id {:optional true} ::id]])

(schema/register! ::complete-response
  [:map
   [::ok?   ::ok?]
   [::id    {:optional true} ::id]
   [::error {:optional true} ::error]])

(defn ^:async complete!
  "Mark an agent's work finished, stamping `:seon.agent/completed-at`.
   Map-in / envelope-out; `:seon.agent/id` defaults to the calling agent
   from the ALS scope (like `reply!`); an explicit id completes another
   agent. Unknown id → fail envelope; already-completed is idempotent
   success. Mirrors `seon.agent.todo/complete!` semantics exactly — one
   vocabulary for 'done'.

   A completed agent is not resumed at pod boot and its user-message
   trigger is not re-armed — it remains queryable history. Un-complete
   is an explicit retract (absent = active, nil is never stored):

     (seon.db/transact!
       {:seon.db/tx-data
        [[:db/retract [:seon.agent/id id] :seon.agent/completed-at]]})

   …after which the next pod boot resumes it again."
  {:malli/schema [:=> [:cat ::complete-request] ::complete-response]}
  [{::keys [id]}]
  (let [id  (or id (db/current-agent-id))
        ent (when id (db/entity {:seon.db/ref [:seon.agent/id id]}))]
    (cond
      (nil? id)
      {::ok? false
       ::error (str "complete!: no :seon.agent/id and no agent in scope — "
                    "pass an id or call inside (seon.db/with-agent …).")}

      (nil? (:seon.agent/id ent))
      {::ok? false
       ::error (str "complete!: no agent " (pr-str id)
                    " — query [?a :seon.agent/id ?id] for the live ids.")}

      (some? (:seon.agent/completed-at ent))
      {::ok? true ::id id}

      :else
      (let [env (await (db/transact!
                         {:seon.db/tx-data
                          [{:seon.agent/id           id
                            :seon.agent/completed-at (js/Date.)}]}))]
        (if (:seon.db/ok? env)
          {::ok? true ::id id}
          {::ok? false
           ::error (str "complete!: store failed — "
                        (get-in env [:seon.db/error :seon.error/message]))})))))

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

(declare with-turn-body!)

(defn ^:async with-turn!
  "Bracketing combinator. Opens a `:seon.agent.turn` on the given session
   with `prompt-text` already attached, flips agent state to
   `:running`, then awaits `body-fn` (a plain 0-arg thunk that returns
   a Promise<map>). On success, closes the turn with `:status :done`,
   folds in any `:seon.agent.turn/messages` from the body's result map, and
   flips agent state back to `:idle`. On throw, flips the turn to
   `:status :error` and re-throws so callers see the failure shape.

   Returns whatever `body-fn` returned, so the caller can read e.g.
   `:seon.agent/eval-count` for stop-policy decisions."
  [{:seon.agent/keys [id]
    :seon.agent.session/keys [id-of-session]
    :seon.agent.turn/keys [id-of-turn prompt-text prompt-file woken-by]}
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
                  ;; The waking message — reply!'s derivation source.
                  woken-by (assoc :seon.agent.turn/woken-by woken-by))]}
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
           [(merge {:seon.agent.turn/id id-of-turn :seon.agent.turn/status :done}
                   (select-keys result [:seon.agent.turn/messages :seon.agent.turn/status]))
            {:seon.agent/id id :seon.agent/state :idle}]}))
      result)
    (catch :default e
      (try
        (await (db/transact!
                 {:seon.db/tx-data
                  [{:seon.agent.turn/id id-of-turn :seon.agent.turn/status :error}
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
      ;; The turn-log record of the raw LLM output: a fully-formed
      ;; self→self message (from = to = this agent — appears in the
      ;; agent's own derived conversation, wakes nothing since the
      ;; trigger requires from ≠ me, and never reads as user-directed).
      ;; Blank output stores NOTHING — the empty-assistant-message
      ;; defect (runs 3 + 6) ends at this boundary too.
      (not (str/blank? reply-text))
      (assoc :seon.agent.turn/messages
             [{:seon.agent.message/id      (db/new-id!)
               :seon.agent.message/from    [:seon.agent/id id]
               :seon.agent.message/to      [[:seon.agent/id id]]
               :seon.agent.message/content reply-text
               :seon.agent.message/at      (js/Date.)
               :seon.agent.message/hops    0}]))))

(defn ^:async ask-and-eval!
  "Body of `with-turn!`. Calls the LLM with `prompt-text`, parses the
   reply, eval-batches the forms (each as a `:seon.agent.turn/evals`
   component via Platform's eval-batch!), and returns
   `{:seon.agent.turn/messages [<assistant>] :seon.agent/eval-count n-ok}`
   for `with-turn!` to fold into the close-tx. An LLM-call failure
   (`:seon.ai/error` on the response) NEVER closes `done [0 ok]` — it
   stores a visible error self-message and closes the turn :error."
  [{:seon.agent/keys [id llm-fn compile-state]
    :seon.agent.turn/keys  [id-of-turn prompt-text]}]
  (let [resp (await (llm-fn prompt-text))]
    (if-let [err (:seon.ai/error resp)]
      {:seon.agent/eval-count 0
       :seon.agent.turn/status      :error
       :seon.agent.turn/messages
       [{:seon.agent.message/id      (db/new-id!)
         :seon.agent.message/from    [:seon.agent/id id]
         :seon.agent.message/to      [[:seon.agent/id id]]
         :seon.agent.message/content (str "⚠ LLM call failed — " (:seon.ai/msg err))
         :seon.agent.message/at      (js/Date.)
         :seon.agent.message/hops    0}]}
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
   `{:seon.agent.turn/status :error :seon.error/data <str>}`."
  [{:seon.agent/keys [id llm-fn compile-state]
    :seon.agent.turn/keys  [woken-by]}]
  (let [session    (await (ensure-session! id))
        session-id (:seon.agent.session/id session)
        turn-id    (db/new-id!)
        turn-idx   (turn-index session-id)
        prompt     (render-prompt id)
        ;; Blob tier — full prompt to disk; the turn datom carries only
        ;; chars + this pointer (see :seon.agent.turn/prompt-chars note above).
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
                                  :seon.agent.session/id-of-session session-id
                                  :seon.agent.turn/id-of-turn    turn-id
                                  :seon.agent.turn/prompt-text   prompt}
                                 prompt-file
                                 (assoc :seon.agent.turn/prompt-file prompt-file)
                                 woken-by
                                 (assoc :seon.agent.turn/woken-by woken-by))
                               #(ask-and-eval! {:seon.agent/id            id
                                                :seon.agent/llm-fn        llm-fn
                                                :seon.agent/compile-state compile-state
                                                :seon.agent.turn/id-of-turn     turn-id
                                                :seon.agent.turn/prompt-text    prompt})))))))
            n-ok (or (:seon.agent/eval-count result) 0)]
        (log id turn-idx (name (or (:seon.agent.turn/status result) :done)) n-ok
             (if (:seon.agent.turn/status result) "llm-error" "ok"))
        (assoc (db/pull {:seon.db/pull-pattern
                         '[* {:seon.agent.turn/messages [*]
                              :seon.agent.turn/evals    [*]}]
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

(schema/register! ::replied-since-inbound-request
  [:map [::id ::id]])

(defn replied-since-inbound?
  "Loop-economy stop derivation (#35): TRUE when an OUTBOUND message
   (from = me with at least one recipient ≠ me — a `reply!` to the
   asker or a `message!` consult to another agent; the per-turn
   assistant self-message is from = to = me and never counts) landed
   strictly AFTER the latest live inbound message (to ∋ me, from ≠ me,
   hops < `warn/hop-cap` — the same window `turns-since-inbound`
   counts against). With no inbound on record the baseline is the
   current session's start, so replies from prior wakes don't stop a
   manually-driven loop. Fully derived from the message log — nothing
   stored, nothing to clear (docs/seon/concepts/reactive-context).
   A NEW inbound message arriving after the reply moves the inbound
   side of the comparison forward, so the loop keeps running for it."
  {:malli/schema [:=> [:cat ::replied-since-inbound-request] :boolean]}
  [{::keys [id]}]
  (let [my-eid (:db/id (db/entity {:seon.db/ref [:seon.agent/id id]}))
        latest-at
        (fn [q args]
          (ffirst (db/query {:seon.db/query q :seon.db/args args})))
        inbound-at
        (when my-eid
          (latest-at
            '[:find (max ?at)
              :in $ ?me ?cap
              :where
              [?m :seon.agent.message/to ?me]
              [?m :seon.agent.message/from ?f]
              [(not= ?f ?me)]
              ;; hop-exhausted messages never wake a loop and must not
              ;; anchor its reply window either (mirrors
              ;; seon.ctx/turns-since-inbound).
              [(get-else $ ?m :seon.agent.message/hops 0) ?h]
              [(< ?h ?cap)]
              [?m :seon.agent.message/at ?at]]
            [my-eid warn/hop-cap]))
        outbound-at
        (when my-eid
          (latest-at
            '[:find (max ?at)
              :in $ ?me
              :where
              [?m :seon.agent.message/from ?me]
              [?m :seon.agent.message/to ?t]
              [(not= ?t ?me)]
              [?m :seon.agent.message/at ?at]]
            [my-eid]))
        baseline (or inbound-at
                     (:seon.agent.session/at (current-session id)))]
    (boolean (and outbound-at baseline
                  (> (.getTime ^js outbound-at)
                     (.getTime ^js baseline))))))

(defn ^:async run-agentic-loop!
  "Per v1.md §6.2 — multi-turn driver. Calls `run-turn!` repeatedly
   until a stop policy fires.

   Default stop policies:
     - Last turn errored.
     - A reply landed during this wake and no newer inbound message
       arrived (`replied-since-inbound?`) — the conversation ball is in
       the other court; churning verification turns to the cap after
       answering was the #35 loop-economy bug (3/5 paid P8 runs burned
       ~15 turns post-answer). A new inbound message re-wakes the loop
       via the kick trigger. Halt marker: `:seon.agent/halt :replied`.
       SHARP EDGE: ANY outbound to a non-self recipient ends the wake —
       including an interim \"I'm looking into it\" acknowledgement or a
       `message!` consult to another agent. Complete the work first,
       reply once; consults resume via the re-wake when the answer
       arrives.
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
          status   (:seon.agent.turn/status result)
          n-forms  (or (:seon.agent/eval-count result) 0)]
      (cond
        (= :error status)
        result

        (replied-since-inbound? {::id id})
        (do (log id (turn-index (:seon.agent.session/id (current-session id)))
                 "halt" "replied — wake complete")
            (assoc result :seon.agent/halt :replied))

        (zero? n-forms)
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
;; Layout verbs — reset-ctx! restores substrate defaults; update-ctx!
;; threads f over the current :seon.agent/ctx and retract-then-adds
;; the result. Component-cardinality-many means the retract is needed
;; to drop the old ctx entities before transacting new ones (per
;; v1.md §5.4 — cardinality-many ref attrs accumulate on upsert).
;; ------------------------------------------------------------


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
   that collides with a substrate default OVERRIDES it (deliberate,
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
  "Pin or update why you exist — sugar over add-section!."
  {:malli/schema [:=> [:cat [:map [:seon.render/ai :string]]]
                  ::section-response]}
  [{text :seon.render/ai}]
  (await (add-section! {:seon.ctx/name     :purpose
                        :seon.ctx/priority 12
                        :seon.render/ai    text})))
