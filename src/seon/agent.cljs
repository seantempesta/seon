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

(schema/register! :seon.agent/id            :string)
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

(schema/register! :seon.message/id      [:string {:min 12 :max 12}])
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

(schema/register! :seon.eval/id         [:string {:min 12 :max 12}])
(schema/register! :seon.eval/agent      :seon.db/ref)
(schema/register! :seon.eval/at         :inst)
(schema/register! :seon.eval/turn       :int)
(schema/register! :seon.eval/narration  :string)
(schema/register! :seon.eval/source     :string)
(schema/register! :seon.eval/ok?        :boolean)
(schema/register! :seon.eval/result-edn :string)
(schema/register! :seon.eval/error      :string)

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

(schema/register! :seon.session/id               [:string {:min 12 :max 12}])
(schema/register! :seon.session/at               :inst)
(schema/register! :seon.session/turns-since-user :int)
(schema/register! :seon.session/turns            [:vector :seon.db/ref])

(schema/register! :seon.turn/id           [:string {:min 12 :max 12}])
(schema/register! :seon.turn/at           :inst)
(schema/register! :seon.turn/status       [:enum :running :done :error])
(schema/register! :seon.turn/prompt-text  :string)
(schema/register! :seon.turn/messages     [:vector :seon.db/ref])
(schema/register! :seon.turn/evals        [:vector :seon.db/ref])

(schema/register! :seon.agent/current-ns  :keyword)
(schema/register! :seon.agent/sessions    [:vector :seon.db/ref])

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
          ;; 6. Eval the forms. eval-batch! still writes evals with
          ;; :seon.eval/agent + numeric :seon.eval/turn; the move
          ;; to `:seon.turn/evals` component refs lands when the
          ;; eval-pipeline refactor follows.
          parsed (repl/parse-forms reply-text)
          _ (log id turn-idx "parsed" (count parsed) "forms")
          eids   (await (seval/eval-batch! compile-state parsed
                                           (home-ns id) id turn-idx))
          ;; 7. Close the turn.
          _ (await
              (db/transact!
                {:seon.db/tx-data
                 [{:seon.turn/id turn-id :seon.turn/status :done}
                  {:seon.agent/id id :seon.agent/state :idle}]}))]
      (log id turn-idx "done" (count eids) "evals")
      (assoc (db/pull {:seon.db/pull-pattern '[*]
                       :seon.db/ref [:seon.turn/id turn-id]})
             :seon.agent/eval-count (count eids)))
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
