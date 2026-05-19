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
     - `build-ctx`          — fallback prompt builder (see seon.render.default/ctx)
     - `run-turn-once!`     — one LLM call + REPL-batch eval cycle
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

   ## build-ctx shape

   What the LLM sees is structured as a REPL session header (current
   ns, agent-id, turn count) followed by capability examples in the
   SAME shape the agent is expected to respond in (`;; narration` +
   `(form)`). Live DB state, recent conversation, and recent evals
   land below. The agent learns by mimicking what it sees — there's
   no schema-as-docs to interpret, only worked patterns.

   `build-ctx` is the fallback prompt builder. The canonical render
   path is `seon.render.default/ctx` (see spec-05 §15.3); when an
   agent's `:seon.render/ai` slot is unset, the dispatcher falls
   through to a pretty-print. `build-ctx` is kept here for legacy
   call sites until spec-05 A-7 wires the render dispatcher in
   front of `run-turn-once!`."
  (:require
    [clojure.string :as str]
    [cljs.reader :as edn]
    [seon.db :as db]
    [seon.eval :as seval]
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
(schema/register! :seon.agent/interrupted?  :boolean)

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
;; Live state probes — pure DB queries used to render ctx.
;; ============================================================

(defn- agent-entity [agent-id]
  (db/entity {:seon.db/ref [:seon.agent/id agent-id]}))

(defn- recent-messages [agent-id n]
  (->> (db/query
         {:seon.db/query '[:find ?at ?role ?content
                           :in $ ?aid
                           :where
                           [?m :seon.message/agent ?aid]
                           [?m :seon.message/at ?at]
                           [?m :seon.message/role ?role]
                           [?m :seon.message/content ?content]]
          :seon.db/args [[:seon.agent/id agent-id]]})
       (sort-by first)
       (take-last n)))

(defn- recent-evals [agent-id n]
  (->> (db/query
         {:seon.db/query
          '[:find ?id ?at ?src ?ok ?res ?err
            :in $ ?aid
            :where
            [?e :seon.eval/agent ?aid]
            [?e :seon.eval/id ?id]
            [?e :seon.eval/at ?at]
            [?e :seon.eval/source ?src]
            [(get-else $ ?e :seon.eval/ok? true) ?ok]
            [(get-else $ ?e :seon.eval/result-edn "") ?res]
            [(get-else $ ?e :seon.eval/error "") ?err]]
          :seon.db/args [[:seon.agent/id agent-id]]})
       (sort-by second #(compare %2 %1))
       (take n)
       reverse))

(defn- try-read-edn
  "Read an EDN string and render it for the agent. If it round-trips
   cleanly, return the pretty-printed value. Otherwise return the raw
   string truncated — the agent reads whatever's there (it's still
   informative even when CLJS-side tagged literals like #datahike/DB
   aren't reader-registered)."
  [s]
  (when-not (str/blank? s)
    (let [trimmed (if (> (count s) 400)
                    (str (subs s 0 400) " …")
                    s)]
      (try (pr-str (edn/read-string s))
           (catch :default _ trimmed)))))

;; ============================================================
;; Render sections — each emits a markdown block. build-ctx
;; concatenates them in order.
;;
;; Examples are formatted in the SAME shape the agent's expected
;; response is parsed (`;; narration\n(form)`) — see seon.repl/
;; parse-forms. The agent learns by mimicking.
;; ============================================================

(defn- render-repl-state
  "REPL prompt header — who you are, where you are, what turn this is.
   `current-ns` defaults to the agent's home ns; updates on `(ns …)`."
  [{:keys [agent-id agent-ns-sym current-ns turn-n]}]
  (let [ae (agent-entity agent-id)]
    (str "## REPL state\n"
         ";; current-ns:  " current-ns "\n"
         ";; agent home:  " agent-ns-sym
         "  (auto-loaded with !session-id, !current-ns atoms"
         " + session-id, result accessor fns)\n"
         ";; agent-id:    " (pr-str agent-id) "\n"
         ";; turn:        " (or turn-n (:seon.agent/turn-count ae) 0) "\n"
         ";; agent-state: " (pr-str (:seon.agent/state ae)) "\n")))

(defn- render-how-you-respond
  "Tell the LLM the response shape it should emit. Mimics what
   `seon.repl/parse-forms` reads back out: `;; narration` lines paired
   with s-exprs. Each form runs independently — form N+1 still runs
   if N fails (real-REPL copy-paste semantics)."
  [_]
  (str "## How you respond\n\n"
       "Write a sequence of Clojure forms. You may precede each form\n"
       "with one or more `;; narration` lines explaining what you're\n"
       "about to do. Forms run in order. If form N fails, form N+1\n"
       "still runs (just like pasting a block into a REPL). The result\n"
       "of every form is captured under a 10-char eval-id; you'll see\n"
       "those in the next turn's `recent evals` and can refer back to\n"
       "any of them via `(result :<eval-id>)`.\n\n"
       "Example response shape:\n\n"
       ";; first, look at what's here\n"
       "(seon.db/query ...)\n\n"
       ";; then, write a reply\n"
       "(seon.db/transact! ...)\n"))

(defn- render-what-you-can-do
  "Worked examples for each primitive the agent uses. Real forms with
   real agent-id — the agent can copy them, change the strings, and
   the patterns work."
  [{:keys [agent-id]}]
  (str
    "## What you can do\n\n"

    ";; read your own agent entity\n"
    "(seon.db/entity {:seon.db/ref [:seon.agent/id " (pr-str agent-id) "]})\n\n"

    ";; query for recent user messages\n"
    "(seon.db/query\n"
    "  {:seon.db/query '[:find ?at ?content\n"
    "                    :in $ ?aid\n"
    "                    :where\n"
    "                    [?m :seon.message/agent ?aid]\n"
    "                    [?m :seon.message/role :user]\n"
    "                    [?m :seon.message/at ?at]\n"
    "                    [?m :seon.message/content ?content]]\n"
    "   :seon.db/args  [[:seon.agent/id " (pr-str agent-id) "]]})\n\n"

    ";; reply by transacting an :assistant message\n"
    ";; (session-id) reads your own id from the home-ns atom\n"
    "(seon.db/transact!\n"
    "  {:seon.db/tx-data\n"
    "   [{:seon.message/id      (seon.agent/new-id!)\n"
    "     :seon.message/role    :assistant\n"
    "     :seon.message/content \"your text here\"\n"
    "     :seon.message/agent   [:seon.agent/id (session-id)]\n"
    "     :seon.message/at      (js/Date.)}]})\n\n"

    ";; pull a specific entity by lookup-ref\n"
    "(seon.db/pull {:seon.db/pull-pattern '[*]\n"
    "               :seon.db/ref [:seon.message/id \"some-msg-id\"]})\n\n"

    ";; reach back to a prior eval's value by id\n"
    "(result :<eval-id-from-recent-evals>)\n\n"

    ";; define a function for later turns — vars persist in your home ns\n"
    "(defn double-it [n] (* n 2))\n\n"

    ";; later (this turn or any future turn): just call it\n"
    "(double-it 21)\n"))

(defn- render-conventions
  "Hard rules + gotchas the agent should know up front."
  [{:keys [agent-ns-sym]}]
  (str "## Conventions + gotchas\n\n"
       "- Stay in your home namespace `" agent-ns-sym "` unless you have\n"
       "  a reason to switch. (`(ns other)` works but cross-ns bare\n"
       "  value reads return nil — that's a cljs.js limitation. Atoms\n"
       "  cross-ns fine; fns cross-ns fine.)\n"
       "- Use atoms for state you want to read back: `(def !x (atom 0))`\n"
       "  + `@!x` works. `(def x 42)` then later `x` returns nil — use\n"
       "  `(def x (atom 42))` instead.\n"
       "- Your turn ends automatically after your forms run; the agent\n"
       "  state flips to :idle. The user's next message kicks a new\n"
       "  turn.\n"
       "- Errors from your forms are values, not exceptions. A failed\n"
       "  form lands in `recent evals` as `:ok? false` with the full\n"
       "  error map readable from `:error`. The agent keeps going.\n"))

(defn- render-current-state
  "Live snapshot — output of the example queries against the real DB.
   This is what the example queries above would actually return RIGHT
   NOW. The agent sees these as a feedback loop."
  [{:keys [agent-id]}]
  (let [ae          (agent-entity agent-id)
        recent-user (->> (db/query
                           {:seon.db/query
                            '[:find ?at ?content
                              :in $ ?aid
                              :where
                              [?m :seon.message/agent ?aid]
                              [?m :seon.message/role :user]
                              [?m :seon.message/at ?at]
                              [?m :seon.message/content ?content]]
                            :seon.db/args [[:seon.agent/id agent-id]]})
                         (sort-by first)
                         (take-last 5))]
    (str "## Current state (live)\n\n"
         ";; your agent entity\n"
         (pr-str {:seon.agent/id          (:seon.agent/id ae)
                  :seon.agent/state       (:seon.agent/state ae)
                  :seon.agent/turn-count  (:seon.agent/turn-count ae)}) "\n\n"
         ";; last 5 user messages\n"
         (if (seq recent-user)
           (str/join "\n" (map (fn [[at content]]
                                 (str "  " (pr-str at) "  " (pr-str content)))
                               recent-user))
           "  (none)") "\n")))

(defn- render-recent-conversation
  [{:keys [agent-id]}]
  (let [msgs (recent-messages agent-id 20)]
    (str "## Recent conversation (last 20)\n\n"
         (if (seq msgs)
           (str/join "\n" (map (fn [[_ role content]]
                                 (str (name role) ": " content))
                               msgs))
           "  (no messages yet)"))))

(defn- render-recent-evals
  [{:keys [agent-id]}]
  (let [rows (recent-evals agent-id 10)]
    (str "## Recent evals (last 10, oldest-first)\n\n"
         (if (seq rows)
           (str/join "\n\n"
                     (map (fn [[id _ src ok res err]]
                            (str "[" id "] " src "\n"
                                 ";; " (if ok ":ok " ":error ")
                                 (cond
                                   ok                       (or (try-read-edn res) res)
                                   (not (str/blank? err))   (pr-str (try-read-edn err))
                                   :else                    "<no result>")))
                          rows))
           "  (none yet)"))))

(defn- render-schema-reference
  "Bottom — schema reference. Reference material, not example, so it
   lives at the end."
  [_]
  (let [filtered (->> (schema/registered-schemas)
                      (filter (fn [[k _]]
                                (#{"seon.agent" "seon.message" "seon.eval"}
                                  (namespace k))))
                      sort)]
    (str "## Schema reference\n\n"
         (str/join "\n"
                   (map (fn [[k v]] (str "  " k "  " (pr-str v)))
                        filtered)))))

;; ============================================================
;; build-ctx — concatenate the sections in order. Pure of state
;; once the DB queries land; safe to call from anywhere.
;;
;; Args:
;;   agent-id      — agent id string (e.g. \"seon\")
;;   agent-ns-sym  — agent's home ns symbol (e.g. 'seon.agent.seon)
;;   current-ns    — the agent's tracked current ns (from @!current-ns;
;;                   defaults to agent-ns-sym if not provided)
;;   turn-n        — current turn counter (optional; reads from agent
;;                   entity if not provided)
;; ============================================================

(defn build-ctx
  "Build the text blob the LLM sees this turn. Pure DB queries; no
   bootstrap-eval round-trip required."
  ([agent-id agent-ns-sym]
   (build-ctx agent-id agent-ns-sym nil nil))
  ([agent-id agent-ns-sym current-ns turn-n]
   (let [m {:agent-id     agent-id
            :agent-ns-sym agent-ns-sym
            :current-ns   (or current-ns agent-ns-sym)
            :turn-n       turn-n}]
     (str (render-repl-state m)
          "\n"
          (render-how-you-respond m)
          "\n"
          (render-what-you-can-do m)
          "\n"
          (render-conventions m)
          "\n"
          (render-current-state m)
          "\n"
          (render-recent-conversation m)
          "\n\n"
          (render-recent-evals m)
          "\n\n"
          (render-schema-reference m)))))

;; ============================================================
;; Turn loop — was seon.session.cljs, now consolidated here.
;;
;; One turn = build ctx → call LLM → parse → eval batch → flip to :idle.
;; Partial-failure: form N+1 always runs (see seon.eval/eval-batch!).
;; ============================================================

(defn- log [agent-id turn-n stage & info]
  (apply js/console.log
         (str "[agent " agent-id " ▸ turn " turn-n " ▸ " stage "]")
         info))

(defn ^:async ^:private bump-turn!
  "Increment :seon.agent/turn-count, flip state to :running. Returns
   the new turn-count."
  [agent-id]
  (let [a (db/entity {:seon.db/ref [:seon.agent/id agent-id]})
        n (inc (or (:seon.agent/turn-count a) 0))]
    (await (db/transact!
             {:seon.db/tx-data
              [{:seon.agent/id          agent-id
                :seon.agent/turn-count  n
                :seon.agent/state       :running}]}))
    n))

(defn ^:async ^:private end-turn! [agent-id]
  (await (db/transact!
           {:seon.db/tx-data [{:seon.agent/id    agent-id
                               :seon.agent/state :idle}]})))

(defn ^:async run-turn-once!
  "Execute exactly one turn for `agent-id`. Returns a Promise that
   resolves to a status map.

   Args:
     agent-id      — the agent's id string
     agent-ns-sym  — agent's home ns symbol (e.g. 'seon.agent.seon)
     llm-fn        — ctx-string → Promise of {:text \"...\"}
     compile-state — the defonce'd bootstrap compile-state"
  [agent-id agent-ns-sym llm-fn compile-state]
  (try
    (let [turn-n  (await (bump-turn! agent-id))
          ctx     (build-ctx agent-id agent-ns-sym)
          _       (log agent-id turn-n "req" (count ctx) "chars")
          resp    (await (llm-fn ctx))
          text    (or (:text resp) "")
          _       (log agent-id turn-n "resp" (count text) "chars")
          parsed  (repl/parse-forms text)
          _       (log agent-id turn-n "parsed" (count parsed) "forms")
          eids    (await (seval/eval-batch! compile-state parsed
                                            agent-ns-sym agent-id turn-n))]
      (await (end-turn! agent-id))
      (log agent-id turn-n "done" (count parsed) "forms eval'd")
      {:seon.agent/turn turn-n
       :seon.agent/forms (count parsed)
       :seon.agent/eval-ids eids})
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

(def default-id "seon")
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
