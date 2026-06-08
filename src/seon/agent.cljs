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
(schema/register! :seon.fn/specced?   :boolean)
;; Set when `:malli/schema` metadata is present but the value fails to
;; parse via `malli.core/schema`. Companion of `:seon.fn/specced?` —
;; when this is set, `:specced?` is forced to false (we will not
;; instrument an unparseable schema). Phase 3 of mvp-completion-plan.
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
   [:seon.fn/specced?   {:optional true} :seon.fn/specced?]
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
  "Single-line render for the messages tile."
  [{role :seon.message/role content :seon.message/content}]
  (str (name role) ": " content))

(defn- read-error-envelope
  "Best-effort EDN decode of a `:seon.eval/error-data` string. Returns
   the envelope map, or nil when blank/unreadable. Never throws."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (try (edn/read-string s)
         (catch :default _ nil))))

(def eval-render-cap
  "Per-eval rendered-result char cap for the recent-evals context
   section. Context-SAFETY invariant: no single eval's result may
   dominate the agent's whole context. One 9.7M-char `pull` result
   used to blow render-prompt to ~9.8M chars; capping each rendered
   result here keeps `recent-evals-section` bounded regardless of how
   large any individual `:seon.eval/result-edn` blob is."
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
               (cap-result (or res "nil"))

               (einstrument/instrument-error? envelope)
               (cap-result (einstrument/render-malli-error envelope))

               (and (string? err) (not (str/blank? err)))
               (cap-result (str ";; ERROR " err))

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

(defn root-pull
  "One nested pull walks the agent's whole causality graph: sessions
   → turns → (messages + evals) + ctx. Components inline, so a single
   call returns the full tree.  v1.md §2.4 idiom #1."
  ([] (root-pull {}))
  ([{:seon.agent/keys [id]}]
   (let [id (resolve-id id)]
     (db/pull
       {:seon.db/pull-pattern
        '[* {:seon.agent/sessions
             [* {:seon.session/turns
                 [* {:seon.turn/messages [*]
                     :seon.turn/evals    [*]}]}]
            :seon.agent/ctx [*]}]
        :seon.db/ref [:seon.agent/id id]}))))

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
         "    (seon.agent/root-pull)       ; you + sessions + turns + messages + evals + ctx\n"
         "    (seon.agent/messages)        ; current session's messages — default {:seon.agent/n 50}\n"
         "    (seon.agent/evals)           ; current session's evals — default {:seon.agent/n 20}\n"
         "    (seon.agent/current-turn)    ; this turn's entity\n"
         "    (seon.agent/current-ns)      ; derived from your latest successful eval\n"
         "    (result <eval-id>)           ; full live result of a prior eval (this session)\n"
         "\n"
         "  See your code in the current ns:\n"
         "    (seon.db/pull {:seon.db/pull-pattern\n"
         "                    '[:seon.ns/source\n"
         "                       {:seon.fn/_ns [*] :seon.schema/_ns [*]}]\n"
         "                    :seon.db/ref [:seon.ns/name (seon.agent/current-ns)]})\n"
         "\n"
         "  Tune your context:\n"
         "    (seon.agent/ctx-entities)    ; your section layout\n"
         "    (seon.agent/update-ctx! id f) ; reshape (swap fn, change priority, etc.)\n"
         "    (seon.agent/reset-ctx! id)   ; restore substrate defaults\n"
         "</system>")))

(defn messages-section
  "Recent user/assistant conversation. Reads :seon.agent/n from the
   ctx-entity if present, else defaults to 50."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id] :as input}]
  (let [n    (or (:seon.agent/n (:seon.agent/ctx-entity input)) 50)
        msgs (messages {:seon.agent/n n :seon.agent/id id})]
    (if (seq msgs)
      (str "<messages>\n"
           (str/join "\n" (map format-message-row msgs))
           "\n</messages>")
      "")))

(defn current-ns-section
  "Every entity owned by the agent's current ns — ns source + every
   :seon.fn / :seon.schema whose :ns is this ns — via one reverse-ref
   pull. Empty until the agent successfully evals a `(ns …)` /
   `(defn …)` / `(schema/register! …)` form in this ns; eval-batch!'s
   detect-and-tee step (which HAS shipped — `seon.eval/build-tee-entities`)
   then records the program-graph entities this pull reads."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [ns    (current-ns {:seon.agent/id id})
        ns-kw (if (keyword? ns) ns (keyword (str ns)))
        ;; db/pull throws on unresolved lookup-refs; guard with entity
        ;; (returns nil for missing) so the section renders blank
        ;; rather than crashing when no :seon.ns entity exists yet
        ;; (e.g. before the agent has eval'd a `(ns …)` form that
        ;; detect-and-tee in eval-batch! would record).
        owned (when (db/entity {:seon.db/db db :seon.db/ref [:seon.ns/name ns-kw]})
                (db/pull {:seon.db/db db
                          :seon.db/pull-pattern
                          '[:seon.ns/source
                            {:seon.schema/_ns [:seon.schema/source]
                             :seon.fn/_ns     [:seon.fn/source]}]
                          :seon.db/ref [:seon.ns/name ns-kw]}))
        parts (concat
                (when-let [src (:seon.ns/source owned)] [src])
                (map :seon.schema/source (:seon.schema/_ns owned))
                (map :seon.fn/source     (:seon.fn/_ns owned)))]
    (if (seq parts)
      (str "<current-namespace name=\"" ns "\">\n"
           (str/join "\n\n" parts)
           "\n</current-namespace>")
      "")))

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

(defn recent-evals-section
  "Last N evals in the current session, oldest-first. Reads
   :seon.agent/n from the ctx-entity if present, else defaults to 20."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id] :as input}]
  (let [n  (or (:seon.agent/n (:seon.agent/ctx-entity input)) 20)
        es (evals {:seon.agent/n n :seon.agent/id id})]
    (if (seq es)
      (str "<recent-evals>\n"
           (str/join "\n\n" (map format-eval-row es))
           "\n</recent-evals>")
      "")))

(defn prompt-section
  "Trailing `:current.ns=> ; turn N` line. Always present."
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
  "The six default :seon.ctx maps that ship with every fresh agent
   (v1.md §5.2). Smallest priority first."
  []
  [{:seon.ctx/name :system       :seon.ctx/priority 10
    :seon.ctx/fn   'seon.agent/system-section}
   {:seon.ctx/name :messages     :seon.ctx/priority 20
    :seon.ctx/fn   'seon.agent/messages-section}
   {:seon.ctx/name :current-ns   :seon.ctx/priority 30
    :seon.ctx/fn   'seon.agent/current-ns-section}
   {:seon.ctx/name :warnings     :seon.ctx/priority 40
    :seon.ctx/fn   'seon.agent/warnings-section}
   {:seon.ctx/name :recent-evals :seon.ctx/priority 50
    :seon.ctx/fn   'seon.agent/recent-evals-section}
   {:seon.ctx/name :prompt       :seon.ctx/priority 99
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
