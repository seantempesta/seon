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
     - `new-id!`            — base62 10-char id generator
     - the `:seon.agent/*`, `:seon.message/*`, `:seon.eval/*` schemas
     - `run-turn-once!`     — one LLM call + REPL-batch eval cycle;
                              builds the prompt via `seon.render/ai-dispatch`
                              against the agent's `:seon.render/ai` slot
                              (defaults to `'seon.render.default/ctx`)
     - `install-kick!`      — register the user-message-kick listener
     - `create!`            — allocate an agent entity, init state
     - `chat`               — inject a :user message
     - `boot!`              — wire everything: create entity + install kick
     - `replies-after`      — poll-style read of :assistant messages
     - `default-id`         — \"seon\" (V0 hardcoded default)
     - `default-ns`         — 'seon.agent.seon (derived from default-id)

   ## State machine

   `:seon.agent/state` values:
     :idle      — no turn running; ready for kick
     :running   — turn in flight; new user messages queue silently
                  (kick handler sees :running and skips)

   The kick handler flips :idle → :running before starting a turn, and
   back to :idle when the turn ends. Concurrent kicks during a turn
   no-op — the next kick after the turn ends picks up any messages
   that landed during it.

   ## Prompt assembly

   Per spec-05 §15.4 the LLM ctx is built via the render dispatch:

     entity → :seon.render/ai slot → resolve-symbol → call → text

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
;; ID generator — 10-char base62, time-prefixed so ids sort by
;; creation. Used for eval-ids, message-ids, trigger-ids, etc.
;; ============================================================

(def ^:private alphabet
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")

(defn- to-base62 [n width]
  (let [s (loop [n n out ()]
            (if (zero? n)
              (apply str out)
              (recur (quot n 62) (cons (nth alphabet (mod n 62)) out))))]
    (str (apply str (repeat (max 0 (- width (count s))) \A)) s)))

(defn new-id!
  "10-char base62 id. 4 chars time-prefix + 6 random. 62^6 random
   space per millisecond — collision-safe at any sane rate."
  []
  (let [now-mod    (mod (.now js/Date) (Math/pow 62 4))
        time-prefix (to-base62 (Math/floor now-mod) 4)
        rand-part   (apply str (repeatedly 6 #(rand-nth alphabet)))]
    (str time-prefix rand-part)))

;; ============================================================
;; Schemas — every shape the agent reads or writes.
;;
;; Per spec-05 §22.5 the entity lives at `:seon.agent/*` (formerly
;; `:seon.session/*`). The agent-ns is dropped from the entity — it's
;; deterministic from the id via `home-ns`.
;; ============================================================

(schema/register! :seon.agent/id            :string)
(schema/register! :seon.agent/state         [:enum :idle :running])
(schema/register! :seon.agent/turn-count    :int)
(schema/register! :seon.agent/turns-since-user :int)
(schema/register! :seon.agent/interrupted?  :boolean)

;; Cap on consecutive agentic turns per user message. The agent may
;; need several turns to walk the user's folder, read files, decide,
;; then reply — but it must not loop forever. After this many turns
;; without a final :assistant message we stop and surface a system
;; note so the user can re-prompt.
(def max-turns-per-message 20)

(schema/register! :seon.message/id      :string)
(schema/register! :seon.message/role    [:enum :user :assistant :system])
(schema/register! :seon.message/content :string)
(schema/register! :seon.message/agent   :seon.db/ref)
(schema/register! :seon.message/at      :inst)

(schema/register! :seon.eval/id         :string)
(schema/register! :seon.eval/agent      :seon.db/ref)
(schema/register! :seon.eval/at         :inst)
(schema/register! :seon.eval/turn       :int)
(schema/register! :seon.eval/narration  :string)
(schema/register! :seon.eval/source     :string)
(schema/register! :seon.eval/ok?        :boolean)
(schema/register! :seon.eval/result-edn :string)
(schema/register! :seon.eval/error      :string)

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

(defn ^:async ^:private bump-turn!
  "Increment :seon.agent/turn-count + :seon.agent/turns-since-user,
   flip state to :running. Returns `[turn-count turns-since-user]`."
  [agent-id]
  (let [a       (db/entity {:seon.db/ref [:seon.agent/id agent-id]})
        n       (inc (or (:seon.agent/turn-count a) 0))
        since-u (inc (or (:seon.agent/turns-since-user a) 0))]
    (await (db/transact!
             {:seon.db/tx-data
              [{:seon.agent/id                agent-id
                :seon.agent/turn-count        n
                :seon.agent/turns-since-user  since-u
                :seon.agent/state             :running}]}))
    [n since-u]))

(defn ^:async ^:private end-turn! [agent-id]
  (await (db/transact!
           {:seon.db/tx-data [{:seon.agent/id    agent-id
                               :seon.agent/state :idle}]})))

(defn- latest-message-role
  "Return the `:seon.message/role` of the most-recent message for
   `agent-id` in `db`, or nil. Used by the multi-turn loop to decide
   whether the agent has issued its final reply this conversation."
  [db agent-id]
  (let [rows (db/query
               {:seon.db/db    db
                :seon.db/query
                '[:find ?at ?role
                  :in $ ?aid
                  :where
                  [?m :seon.message/agent ?aid]
                  [?m :seon.message/at ?at]
                  [?m :seon.message/role ?role]]
                :seon.db/args [[:seon.agent/id agent-id]]})]
    (->> rows (sort-by first) last second)))

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

(defn ^:async run-turn-once!
  "Execute exactly one turn for `agent-id`. Returns a Promise that
   resolves to a status map.

   Per spec-05 §15.4 the prompt now flows through `seon.render/ai-dispatch`
   against the agent's `:seon.render/ai` slot — defaults to
   `'seon.render.default/ctx` when unset. Symbol resolution falls
   through to pretty-print when unresolvable (render mechanism never
   crashes; missing → pretty-print floor).

   Args:
     agent-id      — the agent's id string
     agent-ns-sym  — agent's home ns symbol (e.g. 'seon.agent.seon)
     llm-fn        — ctx-string → Promise of {:text \"...\"}
     compile-state — the defonce'd bootstrap compile-state"
  [agent-id agent-ns-sym llm-fn compile-state]
  (try
    (let [[turn-n since-u] (await (bump-turn! agent-id))
          db      @db/*conn*
          ent     (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id agent-id]})
          sym     (:seon.render/ai ent 'seon.render.default/ctx)
          input   (ai-render-input sym db agent-id ent)
          {:seon.render/keys [text]} (render/ai-dispatch sym input)
          ctx     (or text "")
          _       (log agent-id turn-n "req" (count ctx) "chars"
                       "via" (str sym) "since-user" since-u)
          resp    (await (llm-fn ctx))
          text2   (or (:text resp) "")
          _       (log agent-id turn-n "resp" (count text2) "chars")
          parsed  (repl/parse-forms text2)
          _       (log agent-id turn-n "parsed" (count parsed) "forms")
          eids    (await (seval/eval-batch! compile-state parsed
                                            agent-ns-sym agent-id turn-n))]
      (await (end-turn! agent-id))
      (log agent-id turn-n "done" (count parsed) "forms eval'd")
      ;; Multi-turn loop: if the agent didn't emit an :assistant
      ;; message this turn (i.e. it's still researching) and we
      ;; haven't hit the per-message cap, kick another turn. This
      ;; lets the agent walk the user's folder over multiple turns
      ;; before composing its final reply.
      (let [db2          @db/*conn*
            last-role    (latest-message-role db2 agent-id)
            replied?     (= :assistant last-role)
            hit-cap?     (>= since-u max-turns-per-message)]
        (cond
          replied?
          nil

          hit-cap?
          (await (db/transact!
                   {:seon.db/tx-data
                    [{:seon.message/id      (new-id!)
                      :seon.message/role    :system
                      :seon.message/agent   [:seon.agent/id agent-id]
                      :seon.message/at      (js/Date.)
                      :seon.message/content
                      (str "[turn cap hit — " max-turns-per-message
                           " agentic turns since your last message"
                           " without a final reply. Ask again or"
                           " narrow the question.]")}]}))

          :else
          (js/setTimeout
            (fn []
              (run-turn-once! agent-id agent-ns-sym llm-fn compile-state))
            0)))
      {:seon.agent/turn        turn-n
       :seon.agent/since-user  since-u
       :seon.agent/forms       (count parsed)
       :seon.agent/eval-ids    eids})
    (catch :default e
      (log agent-id "?" "error" (str e))
      (await (end-turn! agent-id))
      {:seon.agent/error (str e)})))

;; ============================================================
;; Kick handler — datahike tx-listener fires on every transact; we
;; filter for new :user messages on the matching agent and schedule
;; run-turn-once! via setTimeout so we return to the listener
;; immediately (no blocking the transactor).
;; ============================================================

(defn- user-msg-eid? [db eid]
  (= :user (:seon.message/role
             (db/entity {:seon.db/db db :seon.db/ref eid}))))

(defn- kick-handler [agent-id agent-ns-sym llm-fn compile-state]
  (fn [{:seon.db/keys [db attr-index]}]
    (let [added-roles (->> (:seon.message/role attr-index)
                           (filter :seon.db/added?))
          new-user    (filter #(user-msg-eid? db (:seon.db/e %)) added-roles)]
      (when (seq new-user)
        (let [ae    (db/entity {:seon.db/db db
                                :seon.db/ref [:seon.agent/id agent-id]})
              state (:seon.agent/state ae)]
          (when-not (= :running state)
            ;; Fresh user message ⇒ reset the multi-turn counter. The
            ;; agent gets up to `max-turns-per-message` agentic turns
            ;; before the loop self-terminates.
            (db/transact!
              {:seon.db/tx-data
               [{:seon.agent/id               agent-id
                 :seon.agent/turns-since-user 0}]})
            (js/setTimeout
              (fn [] (run-turn-once! agent-id agent-ns-sym llm-fn
                                     compile-state))
              0)))))))

(defn install-kick!
  "Register the user-message-kick listener for this agent, closing
   over the LLM fn + compile-state. Returns the listener key for
   unlisten!."
  [agent-id agent-ns-sym llm-fn compile-state]
  (db/listen!
    {:seon.db/key     [::user-message-kick agent-id]
     :seon.db/handler (kick-handler agent-id agent-ns-sym llm-fn
                                    compile-state)}))

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
  "The V0.5 agent's id. Renamed from \"seon\" → \"seon\" per Sean
   2026-05-19. Lowercase keeps URLs (/chat?agent=seon) + namespace
   (seon.agent.seon) consistent; displays capitalize as \"Alpha\"."
  "seon")
(def default-ns (home-ns default-id))

;; ============================================================
;; Boot. The single entry point seon.client calls at startup.
;; ============================================================

(defn ^:async boot!
  "Create the V0 agent, install the kick listener.

   Caller passes:
     - llm-fn        — ctx-string → Promise of {:text \"...\"}
     - compile-state — the defonce'd bootstrap compile-state

   Returns {:seon.agent/id _ :seon.agent/ns _}."
  [llm-fn compile-state]
  (let [{:seon.agent/keys [id]}
        (await (create! {:seon.agent/id default-id}))
        agent-ns (home-ns id)]
    (install-kick! id agent-ns llm-fn compile-state)
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
