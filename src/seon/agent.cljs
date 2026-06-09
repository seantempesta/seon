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
                              `run-agentic-loop!` on a new :user message
     - `turns-cap`          — read :seon.agent/turns-cap or fallback
                              to `default-turns-cap`
     - `current-session` / `ensure-session!` / `start-session!`
     - `create!`            — allocate an agent entity, init state
     - `chat`               — inject a :user message
     - `boot!`              — wire everything: create entity + install
                              user-message trigger + install substrate
                              default `:seon.ctx` layout
     - `reset-ctx!` / `update-ctx!` / `ctx-entities` — agent's ctx-layout
       editing surface
     - `register-warning!` / `unregister-warning!` — warning predicate
       registry (atom-backed; v1.md §5.2)
     - `replies-after`      — poll-style read of :assistant messages

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

   Substrate defaults (`substrate-default-ctx`): six sections —
   `system`, `messages`, `current-ns`, `warnings`, `recent-evals`,
   `prompt`. The agent customizes by transacting different
   `:seon.ctx` entities into `:seon.agent/ctx` (use `update-ctx!`)
   or by transacting a completely different symbol onto the agent's
   `:seon.render/ai` slot."
  (:require
    [clojure.string :as str]
    [cljs.reader :as edn]
    [seon.db :as db]
    [seon.error.instrument :as einstrument]
    [seon.eval :as seval]
    [seon.handlers.fn :as h-fn]
    [seon.handlers.ns :as h-ns]
    [seon.handlers.schema :as h-schema]
    [seon.handlers.test :as h-test]
    [seon.log :as seon-log]
    [seon.render :as render]
    [seon.repl :as repl]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas — every shape the agent reads or writes.
;;
;; Per spec-05 §22.5 the entity lives at `:seon.agent/*` (formerly
;; `:seon.session/*`). The agent-ns is dropped from the entity — it's
;; deterministic from the id via `home-ns`.
;; ============================================================

(schema/register! :seon.agent/id            [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.agent/state         [:enum :idle :running])
;; v0 :seon.agent/turn-count, :seon.agent/turns-since-user,
;; :seon.agent/interrupted? attrs deleted 2026-05-22. turn-count
;; was a holdover that always read 0; turns-since-user moved to
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

(schema/register! :seon.message/id      [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.message/role    [:enum :user :assistant :system])
(schema/register! :seon.message/content :string)
;; :seon.message/agent — present on user-originated messages
;; (`chat` writes it so user-message-handler can find the target agent),
;; absent on assistant/system messages that live as turn components
;; and reach the agent via the component chain. The validation gate
;; only fires when the key IS in tx-data, so absence is naturally
;; OK (no `:optional` wrapper needed on a standalone schema reg).
(schema/register! :seon.message/agent   :seon.db/ref)
(schema/register! :seon.message/at      :inst)

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
;; - turns-since-user = count of :seon.turn entities with :seon.turn/at
;;   strictly greater than the latest :seon.message/role :user's :at.
;;   See `seon.agent/turns-since-user` helper. Derived; no storage.
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
(schema/register! :seon.turn/prompt-text  :string)
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
;;   :seon.message — `chat` + assistant-msg writer (agent.cljs)
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
   [:seon.eval/error       {:optional true} :seon.eval/error]
   [:seon.eval/error-data  {:optional true} :seon.eval/error-data]])

(schema/register! :seon.message
  [:map {:seon.render/ai   'seon.handlers.message/render-ai
         :seon.render/html 'seon.handlers.message/render-html}
   [:seon.message/id      :seon.message/id]
   [:seon.message/role    :seon.message/role]
   [:seon.message/content :seon.message/content]
   [:seon.message/at      :seon.message/at]
   [:seon.message/agent {:optional true} :seon.message/agent]
   [:seon.message/from  {:optional true} :seon.message/from]
   [:seon.message/to    {:optional true} :seon.message/to]])

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
;; user-message-handler calls them. (Reorganizing the file is a separate
;; pass.)
(declare run-turn! run-agentic-loop! current-session start-session! turn-index
         turns-since-user current-ns substrate-default-ctx pretty-ai)

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
;; filter for new :user messages on the matching agent and schedule
;; run-agentic-loop! via setTimeout so we return to the listener
;; immediately (no blocking the transactor).
;;
;; State-machine guard: if the agent is already :running, do
;; nothing. The loop's next render reads accumulated user messages
;; via the chain; the listener's job is to wake the loop, not to
;; queue work.
;; ============================================================

(defn- user-msg-eid? [db eid]
  (= :user (:seon.message/role
             (db/entity {:seon.db/db db :seon.db/ref eid}))))

(defn- user-message-handler
  [{:seon.agent/keys [id] :as input}]
  (fn [{:seon.db/keys [db attr-index]}]
    (let [added-roles (->> (:seon.message/role attr-index)
                           (filter :seon.db/added?))
          new-user    (filter #(user-msg-eid? db (:seon.db/e %)) added-roles)]
      (when (seq new-user)
        (let [ae    (db/entity {:seon.db/db db
                                :seon.db/ref [:seon.agent/id id]})
              state (:seon.agent/state ae)]
          (when-not (= :running state)
            ;; Fresh user message ⇒ schedule the loop. No counter to
            ;; reset: turns-since-user is derived from the message log
            ;; (count of turns whose :at is after the latest user msg's
            ;; :at), so the just-landed user message naturally resets
            ;; the window. See docs/seon/concepts/reactive-context.
            ;;
            ;; setTimeout breaks the ALS scope — re-enter `with-agent`
            ;; so the loop's downstream calls (run-turn!, eval-batch!,
            ;; section fns, web handlers) see (db/current-agent-id).
            (js/setTimeout
              (fn [] (db/with-agent id #(run-agentic-loop! input)))
              0)))))))

(defn install-user-trigger!
  "Register the user-message-trigger listener for this agent. Idempotent:
   unlistens any prior handler for the same agent-id first so
   hot-reload of agent.cljs doesn't leave stale closures wired to
   the tx bus.

   Input map:
     :seon.agent/id              the agent's id string
     :seon.agent/llm-fn          ctx-string -> Promise<{:text \"…\"}>
     :seon.agent/compile-state   bootstrap compile-state"
  [{:seon.agent/keys [id] :as input}]
  (let [k [::user-message-trigger id]]
    (try (db/unlisten! {:seon.db/key k}) (catch :default _ nil))
    (db/listen!
      {:seon.db/key     k
       :seon.db/handler (user-message-handler input)})))

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
;; chat — inject a :user message, return a Promise that resolves
;; after the transact lands. The kick listener will fire on the
;; transact and run-turn-once! on the next event-loop tick.
;; ============================================================

(defn ^:async chat
  "Inject a :user message for an agent. Returns the message-id after
   the transact lands. The agent's reply arrives asynchronously —
   poll via `replies-after` or watch the message log.

   Wraps the transact in `(db/with-agent agent-id …)` so the tx-meta
   `:seon.db/agent-id` is set — required for the inspector's per-agent
   filtered view to surface this message to the right pane.

   Stamps the message with the substrate-default `:seon.render/ai` +
   `:seon.render/html` symbols so it appears in the inspector's two
   panes immediately."
  [agent-id text]
  (let [mid (db/new-id!)]
    (await (db/with-agent agent-id
             (fn ^:async chat-tx! []
               (db/transact!
                 {:seon.db/tx-data
                  [{:seon.message/id      mid
                    :seon.message/role    :user
                    :seon.message/content text
                    :seon.message/agent   [:seon.agent/id agent-id]
                    :seon.message/at      (js/Date.)}]}))))
    mid))

(defn replies-after
  "Return :assistant messages for `agent-id` whose :at is strictly
   after `since-inst`, oldest-first. Sync (reads are sync)."
  [agent-id since-inst]
  (->> (db/query
         {:seon.db/query
          '[:find ?at ?content
            :in $ ?aid ?->ms ?since-ms
            :where
            [?m :seon.message/agent ?aid]
            [?m :seon.message/role :assistant]
            [?m :seon.message/at ?at]
            [?m :seon.message/content ?content]
            [(?->ms ?at) ?ms]
            [(> ?ms ?since-ms)]]
          :seon.db/args [[:seon.agent/id agent-id]
                         (fn [^js d] (.getTime d))
                         (.getTime since-inst)]})
       (sort-by first)
       (mapv second)))

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
    :seon.turn/keys [id-of-turn prompt-text]}
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
               [{:seon.turn/id          id-of-turn
                 :seon.turn/at          (js/Date.)
                 :seon.turn/status      :running
                 ;; Cap the PERSISTED prompt (MEMORY-SAFETY) so a huge
                 ;; prompt can't bloat the datom and OOM a later whole-DB
                 ;; scan. The render cap bounds what's normally IN
                 ;; prompt-text; this is the defensive store-time bound.
                 :seon.turn/prompt-text (seval/cap-edn prompt-text)}]}
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
                   (select-keys result [:seon.turn/messages]))
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

(defn ^:async ask-and-eval!
  "Body of `with-turn!`. Calls the LLM with `prompt-text`, parses the
   reply, eval-batches the forms (each as a `:seon.turn/evals`
   component via Platform's eval-batch!), and returns
   `{:seon.turn/messages [<assistant>] :seon.agent/eval-count n-ok}`
   for `with-turn!` to fold into the close-tx."
  [{:seon.agent/keys [id llm-fn compile-state]
    :seon.turn/keys  [id-of-turn prompt-text]}]
  (let [resp       (await (llm-fn prompt-text))
        reply-text (or (:text resp) "")
        parsed     (repl/parse-forms reply-text)
        batch      (await (seval/eval-batch! compile-state parsed
                                             (home-ns id) id id-of-turn))]
    {:seon.turn/messages
     [{:seon.message/id      (db/new-id!)
       :seon.message/role    :assistant
       :seon.message/content reply-text
       :seon.message/agent   [:seon.agent/id id]
       :seon.message/at      (js/Date.)}]
     :seon.agent/eval-count (:seon.eval/n-ok batch)}))

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
  [{:seon.agent/keys [id llm-fn compile-state]}]
  (let [session    (await (ensure-session! id))
        session-id (:seon.session/id session)
        turn-id    (db/new-id!)
        turn-idx   (turn-index session-id)
        prompt     (render-prompt id)]
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
                               {:seon.agent/id           id
                                :seon.session/id-of-session session-id
                                :seon.turn/id-of-turn    turn-id
                                :seon.turn/prompt-text   prompt}
                               #(ask-and-eval! {:seon.agent/id            id
                                                :seon.agent/llm-fn        llm-fn
                                                :seon.agent/compile-state compile-state
                                                :seon.turn/id-of-turn     turn-id
                                                :seon.turn/prompt-text    prompt})))))))
            n-ok (or (:seon.agent/eval-count result) 0)]
        (log id turn-idx "done" n-ok "ok")
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
     - `turns-since-user` exceeded `(turns-cap id)` — derived
       from the message + turn log; see docs/seon/concepts/reactive-context.

   The user-message-arrival stop policy is handled externally:
   the kick handler runs in `:running` state-machine guards so a
   fresh user message can't stack new loops on top of an in-flight
   one."
  [{:seon.agent/keys [id] :as input}]
  (loop []
    (let [result   (await (run-turn! input))
          since-u  (turns-since-user {:seon.agent/id id})
          status   (:seon.turn/status result)
          n-forms  (or (:seon.agent/eval-count result) 0)]
      (cond
        (= :error status)
        result

        (zero? n-forms)
        result

        (>= since-u (turns-cap id))
        (do (await
              (db/transact!
                {:seon.db/tx-data
                 [{:seon.message/id (db/new-id!)
                   :seon.message/role :system
                   :seon.message/agent [:seon.agent/id id]
                   :seon.message/at (js/Date.)
                   :seon.message/content
                   (str "[turn cap hit — " (turns-cap id)
                        " agentic turns since your last message"
                        " without a final reply. Ask again or"
                        " narrow the question.]")}]}))
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

(defn- format-message-row
  "Render one message as a REPL event for the interleaved transcript:
   `user> …` / `assistant> …` / `system> …`. The `<role>>` prefix lines
   it up visually with eval `> form` lines so the merged stream reads as
   one coherent REPL session."
  [{role :seon.message/role content :seon.message/content}]
  (str (name role) "> " content))

(defn- read-error-envelope
  "Best-effort EDN decode of a `:seon.eval/error-data` string. Returns
   the envelope map, or nil when blank/unreadable. Never throws."
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
               (cap-result-body (str ";; ERROR " err) eval-render-cap eid)

               :else ";; <no result>")
        footer (str "  ; # " eid (when dur (str "  " dur "ms")))]
    (str (when (and narr (not (str/blank? narr))) (str narr "\n"))
         "> " (cap-result src) "\n"
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
  "Last N user/assistant/system messages on the agent's current
   session, oldest-first.  Walks :seon.session/turns →
   :seon.turn/messages (component refs), so the values are inlined
   without an extra query. Default {:seon.agent/n 50}."
  ([] (messages {}))
  ([{:seon.agent/keys [n id] :or {n 50}}]
   (let [id      (resolve-id id)
         session (current-session id)
         msgs    (for [t (sort-by :seon.turn/at (:seon.session/turns session))
                       m (sort-by :seon.message/at (:seon.turn/messages t))]
                   m)]
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

(defn turns-since-user
  "Count of :seon.turn entities in the agent's current session whose
   :seon.turn/at is strictly after the latest :seon.message/role :user
   message's :at. Drives `run-agentic-loop!`'s cap policy. Derived from
   the message + turn log; nothing stored. See
   docs/seon/concepts/reactive-context."
  ([] (turns-since-user {}))
  ([{:seon.agent/keys [id]}]
   (let [id      (resolve-id id)
         session (current-session id)
         turns   (:seon.session/turns session)
         latest-user-at
         (->> (db/query
                {:seon.db/query
                 '[:find (max ?at)
                   :in $ ?aid
                   :where
                   [?m :seon.message/agent ?aid]
                   [?m :seon.message/role :user]
                   [?m :seon.message/at ?at]]
                 :seon.db/args [[:seon.agent/id id]]})
              ffirst)]
     (count
       (if latest-user-at
         (filter #(> (.getTime ^js (:seon.turn/at %))
                     (.getTime ^js latest-user-at))
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
;; Tunables for `warnings-section`. Predicate values; not stored.
;; ------------------------------------------------------------

(def slow-eval-threshold-ms 500)

;; ------------------------------------------------------------
;; Section fns (v1.md §5.2). Each takes :seon.render/system-input
;; {:seon.db/db :seon.agent/id} optionally with :seon.agent/ctx-entity
;; (the :seon.ctx entity that named this section, so the fn can read
;; per-section overrides like :seon.agent/n). Returns a string;
;; empty string = section suppressed by the composer.
;; ------------------------------------------------------------

(defn system-section
  "REPL header: who-am-I, what's-now, discovery cheat-sheet."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id]}]
  (let [ns  (current-ns {:seon.agent/id id})
        now (.toISOString (js/Date.))]
    (str "<system agent=\"" id "\" ns=\"" ns "\">\n"
         "  Now: " now "  (pod tz: " (host-timezone) ")\n"
         "\n"
         "  Walk your own state:\n"
         "    (seon.agent/messages)        ; current session's messages — default {:seon.agent/n 50}\n"
         "    (seon.agent/evals)           ; current session's evals — default {:seon.agent/n 20}\n"
         "    (seon.agent/current-ns)      ; derived from your latest successful eval\n"
         "    (result <eval-id>)           ; full live result of a prior eval (this session)\n"
         "\n"
         "  See your code in the current ns:\n"
         "    (seon.db/pull {:seon.db/pull-pattern\n"
         "                    '[:seon.ns/source\n"
         "                       {:seon.fn/_ns [*] :seon.schema/_ns [*]}]\n"
         "                    :seon.db/ref [:seon.ns/name (seon.agent/current-ns)]})\n"
         "</system>")))

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
  ["seon.db/transact!"
   "seon.db/query"
   "seon.db/pull"
   "seon.db/entity"
   "seon.db/current-agent-id"])

(defn- first-doc-line
  "First non-blank line of a docstring — the one-liner for the
   capabilities cheat-sheet. Full doc stays on the :seon.fn entity."
  [doc]
  (->> (str/split-lines (or doc ""))
       (map str/trim)
       (remove str/blank?)
       first))

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
                (str "  (" sym " " arglists ")"
                     (when (seq doc) (str "\n      ; " doc))))]
    (if (seq rows)
      (str "## What you can do\n\n"
           "These are the core APIs. Map-in is the preferred shape: you pass\n"
           "ONE map with fully-namespaced keys (see the worked example below).\n"
           "The db ops (query/pull/entity/transact!) ALSO accept a natural\n"
           "datahike-style positional form.\n\n"
           (str/join "\n" lines)
           "\n\n"
           "Worked example — reply to the user AND save a fact in one tx\n"
           "(note :seon.db/tx-data is a VECTOR of entity maps):\n\n"
           "  (seon.db/transact!\n"
           "    {:seon.db/tx-data\n"
           "     [{:seon.message/id      (seon.db/new-id!)\n"
           "       :seon.message/role    :assistant\n"
           "       :seon.message/content \"on it — here's what I found\"\n"
           "       :seon.message/agent   [:seon.agent/id (seon.db/current-agent-id)]\n"
           "       :seon.message/at      (js/Date.)}]})\n\n"
           "  (seon.db/query {:seon.db/query\n"
           "                  '[:find ?content\n"
           "                    :where [?m :seon.message/role :user]\n"
           "                           [?m :seon.message/content ?content]]})")
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

(defn- catalog-kind-block
  "Render one entity kind: a `[kind  N instances]` header then one line
   per attribute (`id`/`opt` flags + compact type). The id-attr line is
   marked `id`; optional attrs are marked `?`."
  [db {:keys [kind id-attr]}]
  (let [n     (catalog-kind-count db id-attr)
        rows  (sort-by (fn [{:keys [id? attr]}] [(if id? 0 1) (str attr)])
                       (catalog-attr-rows kind))
        lines (for [{:keys [attr type optional id?]} rows]
                (str "  " (cond id? "id " optional "?  " :else "   ")
                     attr " : " type))]
    (str "[" kind "]  " n " instance" (when (not= 1 n) "s") "\n"
         (str/join "\n" lines))))

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
   the live registry; counts from an AEVT scan on each id-attr. Stores
   nothing; register a new entity kind and it appears here next render."
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
           ";; This is the WHOLE substrate — not just your current ns. Query any\n"
           ";; kind by its id-attr, e.g. (seon.db/query {:seon.db/query\n"
           ";;   '[:find ?id :where [?e :seon.fn/sym ?id]]}).\n\n"
           (str/join "\n\n"
             (for [[ns ks] groups]
               (str "=== " ns " ===\n"
                    (str/join "\n\n"
                      (map #(catalog-kind-block db %)
                           (sort-by (comp str :kind) ks))))))
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
                  :seon.render/text)]
    (if (str/blank? text)
      ""
      (str "<namespace-context>\n" text "\n</namespace-context>"))))

(defn warnings-section
  "Survey the DB for current problems and render them as a single
   section. Cross-agent visibility by default — queries are not
   filtered by :seon.agent/id, so agent A's failed eval surfaces in
   agent B's render. Empty section when nothing's wrong; warnings
   vanish the moment the underlying state goes away (no stored
   warning datoms; nothing to clear).

   Sections are derived, not stored. To add a warning kind, add a
   query here OR write a new section function. See
   docs/seon/concepts/reactive-context."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db]}]
  (let [;; Pre-compute latest user message timestamp so the "since
        ;; latest user msg" window is unambiguous. Without this, a
        ;; bare datalog join over `?u :seon.message/at ?u-at` joins
        ;; on EVERY user msg (one row per msg), surfacing stale
        ;; failures even after the user moved on.
        latest-user-at
        (ffirst (db/query
                  {:seon.db/db db
                   :seon.db/query
                   '[:find (max ?at)
                     :where
                     [?u :seon.message/role :user]
                     [?u :seon.message/at ?at]]}))
        ;; Failed evals since the latest user message — anywhere in
        ;; the system. Vanishes when the next user msg lands AND
        ;; subsequent evals succeed.
        failed-evals
        (if latest-user-at
          (db/query
            {:seon.db/db db
             :seon.db/query
             '[:find ?eid
               :in $ ?cutoff
               :where
               [?e :seon.eval/ok? false]
               [?e :seon.eval/at ?e-at]
               [(> ?e-at ?cutoff)]
               [?e :seon.eval/id ?eid]]
             :seon.db/args [latest-user-at]})
          ;; No user msgs yet — every failed eval is "current".
          (db/query
            {:seon.db/db db
             :seon.db/query
             '[:find ?eid
               :where
               [?e :seon.eval/ok? false]
               [?e :seon.eval/id ?eid]]}))
        ;; Slow evals in the last hour anywhere in the system. Stops
        ;; surfacing when the bad code is fixed (new evals are fast)
        ;; and the offending eval ages out.
        cutoff-at  (js/Date. (- (js/Date.now) (* 60 60 1000)))
        slow-evals
        (db/query
          {:seon.db/db db
           :seon.db/query
           '[:find ?eid ?dur
             :in $ ?threshold ?cutoff
             :where
             [?e :seon.eval/duration-ms ?dur]
             [(>= ?dur ?threshold)]
             [?e :seon.eval/at ?at]
             [(> ?at ?cutoff)]
             [?e :seon.eval/id ?eid]]
           :seon.db/args [slow-eval-threshold-ms cutoff-at]})
        ;; Failing tests — Platform Phase 2 test entities. Filters on
        ;; last-failed > last-passed; vanishes when the test passes.
        failing-tests
        (db/query
          {:seon.db/db db
           :seon.db/query
           '[:find ?sym
             :where
             [?t :seon.test/sym ?sym]
             [?t :seon.test/last-failed-at ?f-at]
             (or-join [?t ?f-at]
                      (and (not [?t :seon.test/last-passed-at _])
                           [(identity ?f-at) _])
                      (and [?t :seon.test/last-passed-at ?p-at]
                           [(> ?f-at ?p-at)]))]})
        lines (cond-> []
                (seq failed-evals)
                (conj (str (count failed-evals)
                           " failed eval"
                           (when (> (count failed-evals) 1) "s")
                           " across agents since latest user message"))
                (seq slow-evals)
                (conj (str (count slow-evals)
                           " slow eval"
                           (when (> (count slow-evals) 1) "s")
                           " (≥" slow-eval-threshold-ms "ms) in the last hour"))
                (seq failing-tests)
                (conj (str (count failing-tests)
                           " failing test"
                           (when (> (count failing-tests) 1) "s")
                           ": "
                           (str/join ", " (map first failing-tests)))))]
    (if (seq lines)
      (str "<warnings>\n"
           (str/join "\n" lines)
           "\n</warnings>")
      "")))

(defn- transcript-item-at
  "Wall-clock `:at` of a transcript item (a message or an eval), as
   epoch-ms. Used to interleave the two streams chronologically."
  [item]
  (let [d (or (:seon.message/at item) (:seon.eval/at item))]
    (if d (.getTime ^js d) 0)))

(defn- format-transcript-item
  "Render one transcript item — a `:seon.message` as a REPL event
   (`user>`/`assistant>` line) or a `:seon.eval` via `format-eval-row`
   (`> form\\n result`). Dispatch on which kind-keyed `:at` is present."
  [item]
  (if (:seon.message/at item)
    (format-message-row item)
    (format-eval-row item)))

(defn transcript-section
  "The chronological TRANSCRIPT — the agent's messages and evals
   INTERLEAVED into a single oldest-first stream, so the agent reads one
   coherent REPL session (user input as `user>`/`assistant>` events,
   evals as `> form` + result) rather than two divorced blocks. Reads
   `:seon.agent/n` from the ctx-entity if present (caps EACH stream
   before the merge), else defaults to 50 messages + 50 evals.

   Pure render: walks the session's `:seon.turn/messages` +
   `:seon.turn/evals`, merges by `:at`, stores nothing. Each eval row is
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
      (str "<transcript>\n"
           (str/join "\n\n" (map format-transcript-item items))
           "\n</transcript>")
      "")))

(defn prompt-section
  "Trailing REPL prompt line: `seon.agent.<id>=>  ; turn N`. The ns shows
   the agent's current namespace and `turn N` the current-session turn
   count — a REPL already shows your ns, so `current-turn`/
   `current-session` collapse into this one always-present line. Always
   present (never blank)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id]}]
  (let [ns      (current-ns {:seon.agent/id id})
        sess    (current-session id)
        n-turns (count (:seon.session/turns sess))]
    (str ns "=>  ; turn " n-turns)))

;; ------------------------------------------------------------
;; Composer (v1.md §5.3).
;;
;; Reads the agent's :seon.agent/ctx, sorts by priority, resolves
;; each :seon.ctx/fn symbol via seon.eval/lookup-value, calls it with
;; the system-input map (plus :seon.agent/ctx-entity), joins the
;; non-blank results.
;;
;; Return shape per MVP decision Q1 (2026-05-23): just
;; {:seon.render/text "..."}. :seon.turn/prompt-text is run-turn!'s
;; responsibility to persist (v1.md §6.1 step 3) — composer does not
;; double-write.
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

     1. :system            — CLJS-in-Node + conventions + REPL contract (static)
     2. :capabilities      — core API worked examples (static)
     3. :schema-catalog    — GLOBAL catalog of every entity KIND in the
                             system (cross-ns; what data exists), grouped by
                             namespace with attrs + instance counts;
                             semi-static (busts only on schema register)
     4. :namespace-context — `render-namespace` of required nses + own ns
                             (mostly static; busts on ns edit)
     5. :warnings          — current cross-agent problems (failed/slow evals,
                             failing tests); reactive, vanishes when fixed (dynamic)
     6. :transcript        — messages + evals interleaved chronologically (dynamic)
     7. :prompt            — `seon.agent.<id>=>  ; turn N` (always changing)

   The catalog sits between capabilities (static) and namespace-context:
   it is the BROAD cross-ns 'what kinds of things exist' view; the per-ns
   `namespace-context` that follows is the DEEP current-ns view. Both are
   semi-static, the catalog more so (it only changes when a schema is
   registered, vs. on any ns edit), so it renders first.

   Smallest priority first. `root-pull` is DELETED (was the
   `[*]`-everywhere amplifier that flooded context); `current-turn`/
   `current-session` fold into the prompt line."
  []
  [{:seon.ctx/name :system            :seon.ctx/priority 10
    :seon.ctx/fn   'seon.agent/system-section}
   {:seon.ctx/name :capabilities      :seon.ctx/priority 20
    :seon.ctx/fn   'seon.agent/capabilities-section}
   {:seon.ctx/name :schema-catalog    :seon.ctx/priority 25
    :seon.ctx/fn   'seon.agent/schema-catalog-section}
   {:seon.ctx/name :namespace-context :seon.ctx/priority 30
    :seon.ctx/fn   'seon.agent/namespace-context-section}
   {:seon.ctx/name :warnings          :seon.ctx/priority 40
    :seon.ctx/fn   'seon.agent/warnings-section}
   {:seon.ctx/name :transcript        :seon.ctx/priority 50
    :seon.ctx/fn   'seon.agent/transcript-section}
   {:seon.ctx/name :prompt            :seon.ctx/priority 99
    :seon.ctx/fn   'seon.agent/prompt-section}])

(defn ^:async reset-ctx!
  "Restore the substrate-default ctx layout for `agent-id`. Retracts
   :seon.agent/ctx (cascade-retracts the existing :seon.ctx entities
   via component semantics), then transacts the six defaults."
  [agent-id]
  (await (db/transact!
           {:seon.db/tx-data
            [[:db/retract [:seon.agent/id agent-id] :seon.agent/ctx]
             {:seon.agent/id agent-id
              :seon.agent/ctx (substrate-default-ctx)}]})))

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
