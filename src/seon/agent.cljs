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
     - `new-id!`            — 12-char base62 id generator (8-char
                              epoch-ms prefix + 4-char random suffix;
                              lex-sorts by creation time)
     - the `:seon.agent/*`, `:seon.session/*`, `:seon.turn/*`,
       `:seon.message/*`, `:seon.eval/*` schemas
     - `run-turn!`          — open turn entity → render → ask LLM →
                              record assistant message + prompt-text →
                              eval-batch → close turn (v1.md §6.1)
     - `run-agentic-loop!`  — multi-turn driver, stop policies (v1 §6.2)
     - `install-user-trigger!` — register the tx-listener that wakes
                              `run-agentic-loop!` on a new :user message
     - `turns-cap`          — read :seon.agent/turns-cap or fallback
                              to `default-turns-cap`
     - `current-session`    — most-recent :seon.session for an agent
     - `start-session!`     — open a new :seon.session
     - `create!`            — allocate an agent entity, init state
     - `chat`               — inject a :user message
     - `boot!`              — wire everything: create entity + install
                              user-message trigger
     - `replies-after`      — poll-style read of :assistant messages
     - `default-id`         — \"seon\" (V0 hardcoded default; tightening
                              to 12-char strict pairs with the
                              default-id refactor)
     - `default-ns`         — 'seon.agent.seon (derived from default-id)

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

   Per spec-05 §15.4 the LLM ctx is built via the render dispatch:

     entity → :seon.render/ai slot → eval/lookup-value → call → text

   Default: `'seon.render.default/ctx` composes REPL header + 'how you
   respond' + worked examples + conventions + recent conversation +
   recent evals + recent errors + schema reference. Agents override
   by transacting their own `:seon.render/ai` symbol pointing at a
   fn that picks which fragments to keep + adds their own."
  (:require
    [clojure.string :as str]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.log :as seon-log]
    [seon.render :as render]
    [seon.repl :as repl]
    [seon.schema :as schema]))

;; ============================================================
;; ID generation — 12-char base62: 8-char epoch-ms time prefix +
;; 4-char random suffix. Lex-sorts by creation time because the
;; base62 alphabet (0…9A…Za…z) is lex-equivalent to ASCII byte
;; order. Identity attrs are constrained to exactly 12 chars at
;; the Malli boundary (see :seon.session/id, :seon.turn/id, etc.).
;; ============================================================

(def ^:private base62-alphabet
  "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")

(defn- int->base62
  "Encode a non-negative integer `n` as base62, left-padded with '0'
   to `width` chars. Result length = `width` when n fits in that
   many digits; longer otherwise."
  [n width]
  (loop [n n acc ""]
    (if (zero? n)
      (if (empty? acc)
        (apply str (repeat width "0"))
        (str (apply str (repeat (max 0 (- width (count acc))) "0")) acc))
      (recur (js/Math.floor (/ n 62))
             (str (nth base62-alphabet (mod n 62)) acc)))))

(defn- random-base62
  "Generate `length` random base62 chars."
  [length]
  (apply str (repeatedly length #(nth base62-alphabet (rand-int 62)))))

(defn new-id!
  "Generate a fresh 12-char base62 entity ID. Lex-sorts by creation time."
  []
  (str (int->base62 (.now js/Date) 8)
       (random-base62 4)))

;; ============================================================
;; Schemas — every shape the agent reads or writes.
;;
;; Per spec-05 §22.5 the entity lives at `:seon.agent/*` (formerly
;; `:seon.session/*`). The agent-ns is dropped from the entity — it's
;; deterministic from the id via `home-ns`.
;; ============================================================

(schema/register! :seon.agent/id            [:string {:seon.db/identity true}])
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

(schema/register! :seon.message/id      [:string {:min 12 :max 12 :seon.db/identity true}])
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

(schema/register! :seon.eval/id          [:string {:min 12 :max 12 :seon.db/identity true}])
(schema/register! :seon.eval/at          :inst)
;; Wall-clock duration of the eval in milliseconds. Populated by
;; seon.eval/eval-batch! per form. Used by the slow-eval warning
;; predicate (v1.md §5.2) without needing to walk evals or compute
;; differences from :at timestamps.
(schema/register! :seon.eval/duration-ms :int)
(schema/register! :seon.eval/narration   :string)
(schema/register! :seon.eval/source      :string)
(schema/register! :seon.eval/ok?         :boolean)
(schema/register! :seon.eval/result-edn  :string)
(schema/register! :seon.eval/error       :string)
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
;; Counters NOT persisted (derived at read time from component
;; vectors): turn-count = (count (:seon.session/turns session));
;; turn-index = (count …) at write time. Storing them means they
;; can desync from reality. :seon.session/turns-since-user IS
;; persisted because it can't be derived (resets on user message).
;;
;; Identity attrs are strict 12-char Malli (matches `new-id!`
;; output). `:seon.agent/id` is the lone holdout while default-id
;; `"seon"` (4 chars) is in use — tightening it requires the
;; default-id refactor.
;; ============================================================

(schema/register! :seon.session/id               [:string {:min 12 :max 12 :seon.db/identity true}])
(schema/register! :seon.session/at               :inst)
(schema/register! :seon.session/turns-since-user :int)
;; :db/isComponent on the ref vectors — retracting a session/turn
;; cascade-retracts its child entities, and one nested pull on the
;; agent walks the whole causality chain inline (v1.md §2.1).
(schema/register! :seon.session/turns            [:vector {:seon.db/component true} :seon.db/ref])

(schema/register! :seon.turn/id           [:string {:min 12 :max 12 :seon.db/identity true}])
(schema/register! :seon.turn/at           :inst)
(schema/register! :seon.turn/status       [:enum :running :done :error])
(schema/register! :seon.turn/prompt-text  :string)
(schema/register! :seon.turn/messages     [:vector {:seon.db/component true} :seon.db/ref])
(schema/register! :seon.turn/evals        [:vector {:seon.db/component true} :seon.db/ref])

(schema/register! :seon.agent/current-ns  :keyword)
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

(schema/register! :seon.fn/sym     [:string {:seon.db/identity true}])
(schema/register! :seon.fn/ns      :seon.db/ref)
(schema/register! :seon.fn/source  :string)

(schema/register! :seon.schema/key    [:keyword {:seon.db/identity true}])
(schema/register! :seon.schema/ns     :seon.db/ref)
(schema/register! :seon.schema/source :string)

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
(declare run-turn! run-agentic-loop! current-session start-session! turn-index)

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
            ;; Fresh user message ⇒ reset the session's
            ;; turns-since-user counter. The agent gets up to
            ;; `(turns-cap id)` agentic turns before the loop
            ;; self-terminates.
            (when-let [session (current-session id)]
              (db/transact!
                {:seon.db/tx-data
                 [{:seon.session/id (:seon.session/id session)
                   :seon.session/turns-since-user 0}]}))
            (js/setTimeout
              (fn [] (run-agentic-loop! input))
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
;; V0 MVP defaults — one hardcoded agent at the canonical id.
;;
;; The home ns is deterministic — `(home-ns default-id)` gives the
;; runtime ns symbol `'seon.agent.seon`, created via
;; seon.eval/setup-agent-ns! at boot.
;; ============================================================

(def default-id
  "The V0.5 agent's id. Lowercase keeps URLs (/chat?agent=seon) and
   namespace (seon.agent.seon) consistent; consumers display it with
   their own capitalization rule."
  "seon")
(def default-ns (home-ns default-id))

;; ============================================================
;; Boot. The single entry point seon.client calls at startup.
;; ============================================================

(defn ^:async boot!
  "Create the V0 agent, install the kick listener. Map-in / map-out.

   Input:
     :seon.agent/llm-fn         ctx-string -> Promise<{:text \"…\"}>
     :seon.agent/compile-state  defonce'd bootstrap compile-state

   Returns `{:seon.agent/id _ :seon.agent/ns _}`. The first user
   message kicks `run-agentic-loop!` (which lazily opens a
   `:seon.session` on first turn)."
  [{:seon.agent/keys [llm-fn compile-state]}]
  (let [{:seon.agent/keys [id]}
        (await (create! {:seon.agent/id default-id}))
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
   poll via `replies-after` or watch the message log."
  [agent-id text]
  (let [mid (new-id!)]
    (await (db/transact!
             {:seon.db/tx-data
              [{:seon.message/id      mid
                :seon.message/role    :user
                :seon.message/content text
                :seon.message/agent   [:seon.agent/id agent-id]
                :seon.message/at      (js/Date.)}]}))
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
;; v1 §6 — run-turn! + run-agentic-loop! scaffold
;;
;; New entry points per v1.md §6.1 / §6.2. Each turn opens a
;; :seon.turn entity on the agent's current session, renders ctx,
;; calls the LLM, records the assistant message + prompt-text as
;; turn components, eval-batches, closes the turn.
;;
;; Status: scaffold lives alongside the v0 run-turn-once! +
;; user-message-handler path so the live deepseek loop keeps working.
;; Cut-over (kick handler → run-agentic-loop!, delete the v0
;; machinery) is a follow-on patch after end-to-end REPL
;; verification.
;;
;; :tx-meta plumbing is intentionally absent — Platform's Phase 3a
;; `*tx-context*` auto-merge will tag every tx with the causality
;; bundle once it ships. The conflict rule ("explicit opts.tx-meta
;; wins per-key; dynvar fills unset keys") means this scaffold
;; gets the full bundle automatically when Platform's patch lands,
;; no rewrite needed.
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
   `:seon.agent/sessions`. Returns the session-id string."
  [agent-id]
  (let [session-id (new-id!)]
    (await (db/transact!
             {:seon.db/tx-data
              [{:seon.agent/id agent-id
                :seon.agent/sessions
                [{:seon.session/id session-id
                  :seon.session/at (js/Date.)
                  :seon.session/turns-since-user 0}]}]}))
    session-id))

(defn ^:async run-turn!
  "Per v1.md §6.1 — one full turn end-to-end. Map-in / map-out.

   Input keys:
     :seon.agent/id             the agent's id string
     :seon.agent/llm-fn         ctx-string -> Promise<{:text \"…\"}>
     :seon.agent/compile-state  bootstrap compile-state

   Returns the closed `:seon.turn` entity via `db/pull '[*]`, plus
   an :seon.agent/eval-count key tallying forms eval'd. On caught
   error, returns `{:seon.turn/status :error :seon.error/data <str>}`."
  [{:seon.agent/keys [id llm-fn compile-state]}]
  (try
    (let [_          (when-not (current-session id)
                       (await (start-session! id)))
          turn-id    (new-id!)
          session-id (:seon.session/id (current-session id))
          turn-idx   (turn-index session-id)
          _ (log id turn-idx "open" turn-id)
          ;; 1. Open turn entity. Bump turns-since-user only; turn-
          ;; count is derived from (count turns) at read time.
          _ (await
              (db/transact!
                {:seon.db/tx-data
                 [{:seon.session/id session-id
                   :seon.session/turns-since-user
                   (inc (or (:seon.session/turns-since-user
                              (db/entity {:seon.db/ref [:seon.session/id session-id]}))
                            0))
                   :seon.session/turns
                   [{:seon.turn/id turn-id
                     :seon.turn/at (js/Date.)
                     :seon.turn/status :running}]}
                  {:seon.agent/id id :seon.agent/state :running}]}))
          ;; 2. Render via the existing composer.
          ent     (db/entity {:seon.db/ref [:seon.agent/id id]})
          sym     (:seon.render/ai ent 'seon.render.default/ctx)
          input   (ai-render-input sym @db/*conn* id ent)
          {:seon.render/keys [text]} (render/ai-render sym input)
          prompt-text (or text "")
          _ (log id turn-idx "render" (count prompt-text) "chars"
                 "via" (str sym))
          ;; 3. Persist :seon.turn/prompt-text inline (v1 §5.3).
          _ (await
              (db/transact!
                {:seon.db/tx-data
                 [{:seon.turn/id turn-id
                   :seon.turn/prompt-text prompt-text}]}))
          ;; 4. Ask the LLM.
          resp        (await (llm-fn prompt-text))
          reply-text  (or (:text resp) "")
          _ (log id turn-idx "resp" (count reply-text) "chars")
          ;; 5. Record assistant message as turn component.
          ;; Assistant messages do NOT set :seon.message/agent —
          ;; the chain (agent → sessions → turns → messages) is
          ;; the canonical lookup. Only :user messages carry the
          ;; agent ref (user-message-handler join key).
          _ (await
              (db/transact!
                {:seon.db/tx-data
                 [{:seon.turn/id turn-id
                   :seon.turn/messages
                   [{:seon.message/id (new-id!)
                     :seon.message/role :assistant
                     :seon.message/content reply-text
                     :seon.message/at (js/Date.)}]}]}))
          ;; 6. Eval the forms. eval-batch! attaches each :seon.eval
          ;; as a component child of the turn (v1.md §2.1) and returns
          ;; {:seon.eval/ids :seon.eval/n-ok :seon.eval/n-fail} so the
          ;; loop's stop policy can distinguish "10 evals all failed"
          ;; from "10 evals all succeeded".
          parsed (repl/parse-forms reply-text)
          _ (log id turn-idx "parsed" (count parsed) "forms")
          batch  (await (seval/eval-batch! compile-state parsed
                                           (home-ns id) id turn-id))
          n-ok   (:seon.eval/n-ok   batch)
          n-fail (:seon.eval/n-fail batch)
          ;; 7. Close the turn.
          _ (await
              (db/transact!
                {:seon.db/tx-data
                 [{:seon.turn/id turn-id :seon.turn/status :done}
                  {:seon.agent/id id :seon.agent/state :idle}]}))]
      (log id turn-idx "done" n-ok "ok" n-fail "fail")
      (assoc (db/pull {:seon.db/pull-pattern '[*]
                       :seon.db/ref [:seon.turn/id turn-id]})
             :seon.agent/eval-count n-ok))
    (catch :default e
      (log id "?" "run-turn! error" (str e))
      (try
        (await (db/transact!
                 {:seon.db/tx-data
                  [{:seon.agent/id id :seon.agent/state :idle}]}))
        (catch :default _ nil))
      {:seon.turn/status :error
       :seon.error/data (str e)})))

(defn ^:async run-agentic-loop!
  "Per v1.md §6.2 — multi-turn driver. Calls `run-turn!` repeatedly
   until a stop policy fires.

   Default stop policies:
     - Last turn errored.
     - Last turn produced zero forms (agent emitted prose only).
     - `:seon.session/turns-since-user` exceeded `(turns-cap id)`.

   The user-message-arrival stop policy is handled externally:
   the kick handler runs in `:running` state-machine guards so a
   fresh user message can't stack new loops on top of an in-flight
   one. (v0 kick handler still routes to run-turn-once!; the
   cut-over patch points it at run-agentic-loop!.)"
  [{:seon.agent/keys [id] :as input}]
  (loop []
    (let [result   (await (run-turn! input))
          session  (current-session id)
          since-u  (or (:seon.session/turns-since-user session) 0)
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
                 [{:seon.message/id (new-id!)
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
;; assemble-ctx) is task #6 and lands after Platform's Patch 1/2
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

(defn- format-eval-row
  "Multi-line render for the recent-evals tile — narration, source,
   result/error, and the timing footer (`; # eval-id  Nms`)."
  [{src     :seon.eval/source
    ok?     :seon.eval/ok?
    res     :seon.eval/result-edn
    err     :seon.eval/error
    eid     :seon.eval/id
    dur     :seon.eval/duration-ms
    narr    :seon.eval/narration}]
  (let [body (cond
               ok?                            (or res "nil")
               (and (string? err)
                    (not (str/blank? err)))   (str ";; ERROR " err)
               :else                          ";; <no result>")
        footer (str "  ; # " eid (when dur (str "  " dur "ms")))]
    (str (when (and narr (not (str/blank? narr))) (str narr "\n"))
         "> " src "\n"
         body footer)))

;; ------------------------------------------------------------
;; Read API — what the agent calls from its REPL to walk its own
;; state. All sync, all pulling from the live conn. Match v1.md §5's
;; map-arg convention with smart defaults.
;; ------------------------------------------------------------

(defn root-pull
  "One nested pull walks the agent's whole causality graph: sessions
   → turns → (messages + evals) + ctx. Components inline, so a single
   call returns the full tree.  v1.md §2.4 idiom #1."
  ([] (root-pull {}))
  ([{:seon.agent/keys [id] :or {id default-id}}]
   (db/pull
     {:seon.db/pull-pattern
      '[* {:seon.agent/sessions
           [* {:seon.session/turns
               [* {:seon.turn/messages [*]
                   :seon.turn/evals    [*]}]}]
          :seon.agent/ctx [*]}]
      :seon.db/ref [:seon.agent/id id]})))

(defn messages
  "Last N user/assistant/system messages on the agent's current
   session, oldest-first.  Walks :seon.session/turns →
   :seon.turn/messages (component refs), so the values are inlined
   without an extra query. Default {:seon.agent/n 50}."
  ([] (messages {}))
  ([{:seon.agent/keys [n id] :or {n 50 id default-id}}]
   (let [session (current-session id)
         msgs    (for [t (sort-by :seon.turn/at (:seon.session/turns session))
                       m (sort-by :seon.message/at (:seon.turn/messages t))]
                   m)]
     (vec (take-last n msgs)))))

(defn current-turn
  "Most-recent :seon.turn on the agent's current session — the one
   that's :running, or the last :done if no turn is open."
  ([] (current-turn {}))
  ([{:seon.agent/keys [id] :or {id default-id}}]
   (let [session (current-session id)]
     (last (sort-by :seon.turn/at (:seon.session/turns session))))))

(defn evals
  "Last N :seon.eval entries for the agent's current session,
   oldest-first.

   v1 spec puts evals as component-many on :seon.turn/evals (v1.md
   §2.1). Today eval-batch! still writes them under :seon.eval/agent
   + :seon.eval/turn :int (V0 shape — PLATFORM-FLAG 2). This fn
   reads the spec shape; once Platform's Patch 2 migrates eval-batch!,
   this returns data without changes. Default {:seon.agent/n 20}."
  ([] (evals {}))
  ([{:seon.agent/keys [n id] :or {n 20 id default-id}}]
   (let [session (current-session id)
         es      (for [t (sort-by :seon.turn/at (:seon.session/turns session))
                       e (sort-by :seon.eval/at (:seon.turn/evals t))]
                   e)]
     (vec (take-last n es)))))

(defn ctx-entities
  "Pull the agent's :seon.agent/ctx vector with each :seon.ctx entity
   inlined. Sorted by :seon.ctx/priority. Useful for inspection
   and for the agent's layout-editing flow."
  ([] (ctx-entities {}))
  ([{:seon.agent/keys [id] :or {id default-id}}]
   (->> (db/pull {:seon.db/pull-pattern
                  '[{:seon.agent/ctx [:db/id :seon.ctx/name
                                      :seon.ctx/priority :seon.ctx/fn]}]
                  :seon.db/ref [:seon.agent/id id]})
        :seon.agent/ctx
        (sort-by :seon.ctx/priority)
        vec)))

;; ------------------------------------------------------------
;; Warning-predicate registry (v1.md §5.2).
;;
;; Atom-backed, volatile. Predicate functions survive across pod
;; restart because they're agent-eval'd code — the resume phase
;; re-evals each :seon.fn/source which re-runs the (register-warning!
;; 'sym) call that lives in or alongside the predicate's defining
;; form. The atom itself doesn't persist; the act of resuming the
;; agent's code restores the registry as a side effect.
;;
;; This intentionally diverges from "DB-entity for everything" —
;; predicates are CODE the agent calls, not data the agent queries,
;; and the registration is a coupling between code and the warnings
;; tile rather than a piece of persisted state.
;; ------------------------------------------------------------

(defonce !warning-predicates (atom #{}))

(defn register-warning!
  "Add `sym` (a fully-qualified symbol) to the warning-predicate
   registry. Idempotent (set semantics). Each call to
   warnings-section resolves these symbols at render time via
   seon.eval/lookup-value, so the predicate fn can be redefined
   between renders and the new version takes effect immediately."
  [sym]
  (swap! !warning-predicates conj sym)
  sym)

(defn unregister-warning!
  "Remove `sym` from the registry. Idempotent."
  [sym]
  (swap! !warning-predicates disj sym)
  sym)

(defn registered-warning-predicates
  "Resolve every registered symbol to its live fn, dropping any that
   no longer resolve (deleted defns, typos)."
  []
  (keep seval/lookup-value @!warning-predicates))

;; ------------------------------------------------------------
;; Default warning predicates (v1.md §5.2).
;;
;; slow-eval-warning is the spec's one default. recent-eval-errors
;; is added per MVP decision (2026-05-23) — the agent's only header-
;; level correctness signal. Without it, ok?=false evals are visible
;; only inline in recent-evals-section, with no rollup.
;; ------------------------------------------------------------

(def slow-eval-threshold-ms 500)

(defn slow-eval-warning
  "Predicate: returns warning maps for any eval in the recent N whose
   :seon.eval/duration-ms exceeds the slow-eval threshold."
  [_input]
  (for [e (evals {:seon.agent/n 20})
        :when (> (or (:seon.eval/duration-ms e) 0) slow-eval-threshold-ms)]
    {:seon.warning/severity :info
     :seon.warning/text
     (str "slow eval " (:seon.eval/id e)
          " took " (:seon.eval/duration-ms e) "ms")}))

(defn recent-eval-errors
  "Predicate: surfaces ok?=false evals from the agent's recent N as
   a single rollup warning (the per-form details still appear inline
   in recent-evals-section). Header-level correctness signal — without
   this the agent has no way to notice 'my last 5 forms all failed'
   except by scanning the eval log."
  [_input]
  (let [failed (filter #(false? (:seon.eval/ok? %)) (evals {:seon.agent/n 20}))]
    (when (seq failed)
      [{:seon.warning/severity :warn
        :seon.warning/text
        (str (count failed) " failed eval"
             (when (> (count failed) 1) "s")
             " in the last 20: "
             (str/join ", " (map :seon.eval/id failed)))}])))

;; Auto-register the two substrate defaults at ns-load. Idempotent
;; (atom is a set); re-runs on hot reload without duplicating.
(register-warning! 'seon.agent/slow-eval-warning)
(register-warning! 'seon.agent/recent-eval-errors)

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
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [agent (db/pull {:seon.db/db db
                        :seon.db/pull-pattern
                        '[:seon.agent/id :seon.agent/current-ns]
                        :seon.db/ref [:seon.agent/id id]})
        ns    (or (:seon.agent/current-ns agent) (home-ns id))
        now   (.toISOString (js/Date.))]
    (str "<system agent=\"" id "\" ns=\"" ns "\">\n"
         "  Now: " now "  (pod tz: " (host-timezone) ")\n"
         "\n"
         "  Walk your own state:\n"
         "    (seon.agent/root-pull)       ; you + sessions + turns + messages + evals + ctx\n"
         "    (seon.agent/messages)        ; current session's messages — default {:seon.agent/n 50}\n"
         "    (seon.agent/evals)           ; current session's evals — default {:seon.agent/n 20}\n"
         "    (seon.agent/current-turn)    ; this turn's entity\n"
         "    (result <eval-id>)           ; full live result of a prior eval (this session)\n"
         "\n"
         "  See your code in the current ns:\n"
         "    (seon.db/pull {:seon.db/pull-pattern\n"
         "                    '[:seon.ns/source\n"
         "                       {:seon.fn/_ns [*] :seon.schema/_ns [*]}]\n"
         "                    :seon.db/ref [:seon.ns/name (:seon.agent/current-ns ...)]})\n"
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
   pull. Empty string today because eval-batch!'s detect-and-tee step
   (Platform's Patch 2) hasn't shipped, so no program-graph entities
   exist. Auto-populates once Patch 2 lands."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [agent (db/pull {:seon.db/db db
                        :seon.db/pull-pattern '[:seon.agent/current-ns]
                        :seon.db/ref [:seon.agent/id id]})
        ns    (or (:seon.agent/current-ns agent) (home-ns id))
        owned (db/pull {:seon.db/db db
                        :seon.db/pull-pattern
                        '[:seon.ns/source
                          {:seon.schema/_ns [:seon.schema/source]
                           :seon.fn/_ns     [:seon.fn/source]}]
                        :seon.db/ref [:seon.ns/name (keyword (str ns))]})
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
  "Run every registered predicate. Each returns nil or a seq of
   {:seon.warning/severity :seon.warning/text} maps. Sorted by severity
   descending (:error → :warn → :info)."
  {:malli/schema [:=> [:cat :map] :string]}
  [input]
  (let [preds (registered-warning-predicates)
        ws    (->> (for [p preds, w (p input) :when w] w)
                   (sort-by (fn [{sev :seon.warning/severity}]
                              ({:error 0 :warn 1 :info 2} sev 3))))]
    (if (seq ws)
      (str "<warnings>\n"
           (str/join "\n" (map :seon.warning/text ws))
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
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [agent   (db/pull {:seon.db/db db
                          :seon.db/pull-pattern '[:seon.agent/current-ns]
                          :seon.db/ref [:seon.agent/id id]})
        ns      (or (:seon.agent/current-ns agent) (home-ns id))
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

(defn assemble-ctx
  "Compose the LLM ctx from :seon.agent/ctx entities. Returns
   {:seon.render/text 'composed-text'} matching :seon.render/ai-response."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:seon.db/keys [db] :seon.agent/keys [id] :as input}]
  (let [agent    (db/pull {:seon.db/db db
                           :seon.db/pull-pattern
                           '[:seon.agent/id
                             {:seon.agent/ctx
                              [:seon.ctx/name :seon.ctx/priority :seon.ctx/fn]}]
                           :seon.db/ref [:seon.agent/id id]})
        sections (sort-by :seon.ctx/priority (:seon.agent/ctx agent))
        ctx-in   (assoc input :seon.agent/ctx-entity nil)
        text     (->> sections
                      (map (fn [section]
                             (let [f  (seval/lookup-value (:seon.ctx/fn section))
                                   in (assoc ctx-in :seon.agent/ctx-entity section)]
                               (if f
                                 (f in)
                                 (pretty-ai section)))))
                      (remove str/blank?)
                      (str/join "\n\n"))]
    {:seon.render/text text}))

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
