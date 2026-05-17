(ns seon.session
  "Session lifecycle. Owns the runtime side the agent doesn't see:

     - create!         — allocate a session entity, init state
     - run-turn-once!  — ^:async — one LLM call + REPL-batch eval cycle
     - install-kick!   — register the user-message-kick listener
     - chat            — ^:async — inject a :user message into a session
     - boot!           — wire everything: create session, set LLM,
                         install kick listener
     - replies-after   — poll-style read of :assistant messages

   ## REPL-as-harness

   The agent's response is parsed by `seon.repl/parse-forms` into
   (narration, source) pairs, then passed to `seon.eval/eval-batch!`
   which evaluates each in the agent's bootstrap-CLJS compile-state.
   Partial-failure semantics: form N+1 always runs, even if N threw.
   Live values land in the agent's home-ns `!results` atom; durable
   records as `:seon.eval` entities. See [[seon.eval]] for the eval
   contract.

   ## State machine

   :seon.session/agent-loop-state values:
     :idle      — no turn running; ready for kick
     :running   — turn in flight; new user messages queue silently
                  (kick handler sees :running and skips)

   The kick handler flips :idle → :running before starting a turn, and
   back to :idle when the turn ends. Concurrent kicks during a turn
   no-op — the next kick after the turn ends picks up any messages
   that landed during it.

   ## H-1a deviation from spec-02 sequencing

   spec-02 said S-7 would migrate session.cljs to ^:async/await. H-1a
   does it now because seon.db/transact! is already ^:async (H-2) —
   the rest of this namespace is straight-line `await`s over those."
  (:require
    [seon.agent :as agent]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.repl :as repl]))

;; ============================================================
;; Logging — one helper, used everywhere.
;; ============================================================

(defn- log [session-id turn-n stage & info]
  (apply js/console.log
         (str "[session " session-id " ▸ turn " turn-n " ▸ " stage "]")
         info))

;; ============================================================
;; Tick management — bump :running before, flip :idle after.
;; ============================================================

(defn ^:async ^:private bump-tick!
  "Increment :seon.session/tick-count, flip state to :running. Returns
   the new tick-count."
  [session-id]
  (let [s (db/entity {:seon.db/ref [:seon.session/id session-id]})
        n (inc (or (:seon.session/tick-count s) 0))]
    (await (db/transact!
             {:seon.db/tx-data
              [{:seon.session/id              session-id
                :seon.session/tick-count      n
                :seon.session/agent-loop-state :running}]}))
    n))

(defn ^:async ^:private end-turn! [session-id]
  (await (db/transact!
           {:seon.db/tx-data [{:seon.session/id session-id
                               :seon.session/agent-loop-state :idle}]})))

;; ============================================================
;; One turn — the eval+react cycle. ^:async, single await chain.
;;
;;   1. flip state to :running, bump tick
;;   2. build ctx via seon.agent
;;   3. call LLM (await)
;;   4. parse response via seon.repl
;;   5. eval the batch via seon.eval/eval-batch! (partial-failure)
;;   6. flip state to :idle (whether or not the agent flipped it)
;;
;; The state flip at the end is idempotent — the agent's forms may
;; have flipped it; we re-flip to be sure.
;; ============================================================

(defn ^:async run-turn-once!
  "Execute exactly one turn for `session-id`. Returns a Promise that
   resolves to a status map.

   Args:
     session-id    — the owning session id
     agent-ns-sym  — agent's home ns symbol (e.g. 'seon.agent.seon)
     llm-fn        — ctx-string → Promise of {:text \"...\"}
     compile-state — the defonce'd bootstrap compile-state"
  [session-id agent-ns-sym llm-fn compile-state]
  (try
    (let [turn-n  (await (bump-tick! session-id))
          ctx     (agent/build-ctx session-id agent-ns-sym)
          _       (log session-id turn-n "req" (count ctx) "chars")
          resp    (await (llm-fn ctx))
          text    (or (:text resp) "")
          _       (log session-id turn-n "resp" (count text) "chars")
          parsed  (repl/parse-forms text)
          _       (log session-id turn-n "parsed" (count parsed) "forms")
          eids    (await (seval/eval-batch! compile-state parsed
                                            agent-ns-sym session-id turn-n))]
      (await (end-turn! session-id))
      (log session-id turn-n "done" (count parsed) "forms eval'd")
      {:seon.session/turn turn-n
       :seon.session/forms (count parsed)
       :seon.session/eval-ids eids})
    (catch :default e
      (log session-id "?" "error" (str e))
      (await (end-turn! session-id))
      {:seon.session/error (str e)})))

;; ============================================================
;; Kick handler — datahike tx-listener fires on every transact; we
;; filter for new :user messages on the matching session and schedule
;; run-turn-once! via setTimeout so we return to the listener
;; immediately (no blocking the transactor).
;;
;; (H-4 will retire this in favor of a persisted :seon.trigger entity
;; that registers the same effect through seon.trigger/register!. The
;; behavior stays the same; the storage shifts to data.)
;; ============================================================

(defn- user-msg-eid? [db eid]
  (= :user (:seon.message/role
             (db/entity {:seon.db/db db :seon.db/ref eid}))))

(defn- kick-handler [session-id agent-ns-sym llm-fn compile-state]
  (fn [{:seon.db/keys [db attr-index]}]
    (let [added-roles (->> (:seon.message/role attr-index)
                           (filter :seon.db/added?))
          new-user    (filter #(user-msg-eid? db (:seon.db/e %)) added-roles)]
      (when (seq new-user)
        (let [sess  (db/entity {:seon.db/db db
                                :seon.db/ref [:seon.session/id session-id]})
              state (:seon.session/agent-loop-state sess)]
          (when-not (= :running state)
            (js/setTimeout
              (fn [] (run-turn-once! session-id agent-ns-sym llm-fn
                                     compile-state))
              0)))))))

(defn install-kick!
  "Register the user-message-kick listener for this session, closing
   over the LLM fn + compile-state. Returns the listener key for
   unlisten!."
  [session-id agent-ns-sym llm-fn compile-state]
  (db/listen!
    {:seon.db/key     [::user-message-kick session-id]
     :seon.db/handler (kick-handler session-id agent-ns-sym llm-fn
                                    compile-state)}))

;; ============================================================
;; Session creation. Allocates an id + ns, transacts the entity.
;; ============================================================

(defn ^:async create!
  "Allocate a session entity. Idempotent: re-calling with the same id
   resets state to :idle (transact is upsert-by-unique-id)."
  [{:seon.session/keys [id agent-ns]}]
  (await (db/transact!
           {:seon.db/tx-data [{:seon.session/id id
                               :seon.session/agent-ns agent-ns
                               :seon.session/agent-loop-state :idle}]}))
  {:seon.session/id id
   :seon.session/agent-ns agent-ns})

;; ============================================================
;; V0 MVP defaults — one hardcoded agent at the canonical id.
;;
;; Note: agent-ns is now the per-agent RUNTIME ns (`seon.agent.seon`),
;; created via seon.eval/setup-agent-ns! at boot, not a compile-time
;; placeholder like the old `seon.agents.alice`.
;; ============================================================

(def default-session-id "seon")
(def default-agent-ns 'seon.agent.seon)

;; ============================================================
;; Boot. The single entry point seon.client calls at startup.
;; ============================================================

(defn ^:async boot!
  "Create the V0 agent session, install the kick listener.

   Caller passes:
     - llm-fn        — ctx-string → Promise of {:text \"...\"}
     - compile-state — the defonce'd bootstrap compile-state

   Returns {:seon.session/id _ :seon.session/agent-ns _}."
  [llm-fn compile-state]
  (let [{:seon.session/keys [id agent-ns]}
        (await (create! {:seon.session/id      default-session-id
                         :seon.session/agent-ns default-agent-ns}))]
    (install-kick! id agent-ns llm-fn compile-state)
    {:seon.session/id id
     :seon.session/agent-ns agent-ns}))

;; ============================================================
;; chat — inject a :user message, return a Promise that resolves
;; after the transact lands. The kick listener will fire on the
;; transact and run-turn-once! on the next event-loop tick.
;; ============================================================

(defn ^:async chat
  "Inject a :user message into a session. Returns the message-id after
   the transact lands. The agent's reply arrives asynchronously —
   poll via `replies-after` or watch the message log."
  [session-id text]
  (let [mid (agent/new-id!)]
    (await (db/transact!
             {:seon.db/tx-data
              [{:seon.message/id      mid
                :seon.message/role    :user
                :seon.message/content text
                :seon.message/session [:seon.session/id session-id]
                :seon.message/at      (js/Date.)}]}))
    mid))

(defn replies-after
  "Return :assistant messages in `session-id` whose :at is strictly
   after `since-inst`, oldest-first. Sync (reads are sync)."
  [session-id since-inst]
  (->> (db/query
         {:seon.db/query
          '[:find ?at ?content
            :in $ ?sid ?->ms ?since-ms
            :where
            [?m :seon.message/session ?sid]
            [?m :seon.message/role :assistant]
            [?m :seon.message/at ?at]
            [?m :seon.message/content ?content]
            [(?->ms ?at) ?ms]
            [(> ?ms ?since-ms)]]
          :seon.db/args [[:seon.session/id session-id]
                         (fn [^js d] (.getTime d))
                         (.getTime since-inst)]})
       (sort-by first)
       (mapv second)))
