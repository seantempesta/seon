(ns seon.session
  "Session lifecycle. Owns the runtime side the agent doesn't see:

     - create!       — allocate a session entity, init state, return
                       the binding info
     - run-turn-once! — execute exactly one LLM-call + form-eval cycle
                       for a session (Promise-returning, no looping)
     - install-kick! — register the user-message-kick listener that
                       calls run-turn-once! on new :user messages
     - chat          — inject a :user message into a session for
                       testing (returns immediately; poll the DB for
                       agent replies)
     - boot!         — wire everything: create session, set LLM,
                       install kick listener, bind dynamics

   ## Design choices

   ### One turn per kick, no looping at this layer

   V0 MVP runs exactly ONE turn per user message. After the agent's
   forms are eval'd, the kick handler exits. If the agent wants to
   keep working, they have to wait for the user to message again.
   Auto-continuation across turns (without user input) waits for
   the core.async.flow integration in V0-S-2 — that's the canonical
   loop substrate, not hand-rolled go-loops in this namespace.

   ### Why no go-loop here

   The hand-rolled go-loop pattern is fragile (no backpressure, no
   supervision, no snapshot/restore). One turn = one Promise chain.
   Async is unavoidable for the LLM call, but each turn is a single
   linear chain, not a loop.

   ### State machine

   :seon.session/agent-loop-state values:
     :idle      — no turn running; ready for kick
     :running   — turn in flight; new user messages queue silently
                  (kick handler sees :running and skips)

   The kick handler flips :idle → :running before starting a turn,
   and back to :idle when the turn ends (or the agent calls done!).
   Concurrent kicks during a turn no-op — the next kick after the
   turn ends picks up any messages that landed during it."
  (:require
    [cljs.core.async :as a]
    [clojure.string :as str]
    [seon.agent :as agent]
    [seon.db :as db]
    [seon.repl :as repl])
  (:require-macros
    [cljs.core.async :refer [go]]))

;; ============================================================
;; The runtime eval. V0 only allows verbs in seon.agent/verb-table;
;; full bootstrap-CLJS eval lands in V0-B-8.
;;
;; No global LLM atom. The LLM fn is passed explicitly through the
;; kick-handler closure and run-turn-once! arg, the way Rich would
;; want it — no hidden config reachable from anywhere.
;; ============================================================

(defn- runtime-eval
  "V0 curated eval: only forms whose head is a symbol in
   `seon.agent/verb-table` are allowed. Future V0-B-8 swaps this
   for bootstrap-CLJS eval. The compromise that makes V0 demo-able
   without the bootstrap eval substrate."
  [form]
  (cond
    (seq? form)
    (let [vsym (first form)
          vfn  (get agent/verb-table vsym)
          args (rest form)]
      (when-not vfn
        (throw (ex-info
                 (str "Unknown verb: " vsym
                      ". V0 only supports verbs in seon.agent/verb-table.")
                 {:seon.session/error :unknown-verb
                  :seon.session/symbol vsym})))
      (apply vfn args))
    (symbol? form) (or (get agent/verb-table form)
                       (throw (ex-info (str "Unknown symbol: " form)
                                       {:seon.session/error :unknown-symbol})))
    :else form))

;; ============================================================
;; Form eval — wrap runtime-eval, transact a :seon.eval entity.
;; Errors don't bubble; they're stored on the entity and the next
;; turn's ctx surfaces them.
;; ============================================================

(defn- eval-and-record!
  "Eval one parsed (narration, form) pair. Transact a :seon.eval
   entity carrying source + result + narration. Returns the eval-id."
  [{:keys [narration source form]} session-id turn-n]
  (let [eid (agent/new-id!)
        at  (js/Date.)]
    (try
      (let [result (runtime-eval form)
            edn    (try (pr-str result) (catch :default _ (str result)))]
        (db/transact!
          {:seon.db/tx-data
           [{:seon.eval/id          eid
             :seon.eval/session     [:seon.session/id session-id]
             :seon.eval/at          at
             :seon.eval/narration   (or narration "")
             :seon.eval/source      source
             :seon.eval/result-edn  edn
             :seon.eval/turn        turn-n}]})
        eid)
      (catch :default e
        (db/transact!
          {:seon.db/tx-data
           [{:seon.eval/id        eid
             :seon.eval/session   [:seon.session/id session-id]
             :seon.eval/at        at
             :seon.eval/narration (or narration "")
             :seon.eval/source    source
             :seon.eval/error     (str e)
             :seon.eval/turn      turn-n}]})
        eid))))

;; ============================================================
;; One turn — the only async chain in this namespace.
;;
;; Returns a Promise (well, a 1-bufferd chan we resolve) so callers
;; can observe completion. Internally:
;;   1. flip state to :running
;;   2. build ctx via seon.agent
;;   3. call LLM (async)
;;   4. parse response via seon.repl
;;   5. eval each form synchronously (transact :seon.eval entities)
;;   6. flip state to :idle (whether or not agent called done!)
;;
;; The state flip at the end is idempotent — if the agent called
;; done!, the state's already :idle. We re-flip to be sure.
;; ============================================================

(defn- log [session-id turn-n stage & info]
  (apply js/console.log
         (str "[session " session-id " ▸ turn " turn-n " ▸ " stage "]")
         info))

(defn- bump-tick! [session-id]
  (let [s (db/entity {:seon.db/ref [:seon.session/id session-id]})
        n (inc (or (:seon.session/tick-count s) 0))]
    (db/transact!
      {:seon.db/tx-data [{:seon.session/id session-id
                          :seon.session/tick-count n
                          :seon.session/agent-loop-state :running}]})
    n))

(defn- end-turn! [session-id]
  (db/transact!
    {:seon.db/tx-data [{:seon.session/id session-id
                        :seon.session/agent-loop-state :idle}]}))

(defn run-turn-once!
  "Execute exactly one turn for `session-id`. Returns a channel that
   delivers a status map when the turn completes. The channel always
   closes — no infinite waits.

   `llm-fn` is a ctx-string → Promise-of-`{:text \"...\"}`. Passed in
   so this fn has no hidden dependencies on global state."
  [session-id agent-ns llm-fn]
  (let [done (a/chan 1)]
    (binding [agent/*session-id* session-id
              agent/*agent-ns*   agent-ns]
      (let [turn-n (bump-tick! session-id)
            ctx    (agent/build-ctx)
            _      (log session-id turn-n "req" (count ctx) "chars")]
        (-> (llm-fn ctx)
            (.then (fn [resp]
                     (let [text   (or (:text resp) "")
                           _      (log session-id turn-n "resp"
                                       (count text) "chars")
                           parsed (repl/parse-forms text)
                           _      (log session-id turn-n "parsed"
                                       (count parsed) "forms")]
                       (binding [agent/*session-id* session-id
                                 agent/*agent-ns*   agent-ns]
                         (doseq [pair parsed]
                           (eval-and-record! pair session-id turn-n)))
                       (end-turn! session-id)
                       (log session-id turn-n "done"
                            (count parsed) "forms eval'd")
                       (a/put! done {:seon.session/turn turn-n
                                     :seon.session/forms (count parsed)})
                       (a/close! done))))
            (.catch (fn [e]
                      (log session-id turn-n "error" (str e))
                      (end-turn! session-id)
                      (a/put! done {:seon.session/error (str e)})
                      (a/close! done))))))
    done))

;; ============================================================
;; The kick handler. Called by seon.db's tx-listener. Synchronous
;; (returns immediately to the listener) — schedules the turn via
;; setTimeout so the listener doesn't block the transactor.
;; ============================================================

(defn- user-msg-eid? [db eid]
  (= :user (:seon.message/role (db/entity {:seon.db/db db :seon.db/ref eid}))))

(defn- kick-handler [session-id agent-ns llm-fn]
  (fn [{:seon.db/keys [db attr-index]}]
    (let [added-roles (->> (:seon.message/role attr-index)
                           (filter :seon.db/added?))
          new-user (filter #(user-msg-eid? db (:seon.db/e %)) added-roles)]
      (when (seq new-user)
        (let [sess  (db/entity {:seon.db/db db
                                :seon.db/ref [:seon.session/id session-id]})
              state (:seon.session/agent-loop-state sess)]
          (when-not (= :running state)
            ;; Schedule via setTimeout so we return to datahike's
            ;; listener immediately; the actual turn runs on the
            ;; next event-loop tick.
            (js/setTimeout
              (fn [] (run-turn-once! session-id agent-ns llm-fn))
              0)))))))

(defn install-kick!
  "Register the user-message-kick listener for this session, closing
   over the LLM fn. Returns the listener key for unlisten!."
  [session-id agent-ns llm-fn]
  (db/listen!
    {:seon.db/key     [::user-message-kick session-id]
     :seon.db/handler (kick-handler session-id agent-ns llm-fn)}))

;; ============================================================
;; Session creation. Allocates an id + ns, transacts the entity,
;; returns the bindings the caller should use.
;; ============================================================

(defn create!
  "Allocate a session entity by id + ns. Idempotent: re-calling with
   the same id resets state to :idle but doesn't reset the
   scratchpad or tick-count (transact is upsert-by-unique-id).
   Returns `{:seon.session/id _ :seon.session/agent-ns _}`."
  [{:seon.session/keys [id agent-ns]}]
  (db/transact!
    {:seon.db/tx-data [{:seon.session/id id
                        :seon.session/agent-ns agent-ns
                        :seon.session/agent-loop-state :idle}]})
  {:seon.session/id id
   :seon.session/agent-ns agent-ns})

;; ============================================================
;; V0 MVP — one hardcoded agent.
;;
;; We pick a stable id ("seon") and a stable ns ('seon.agents.alice)
;; for the V0 demo, sidestepping the namespace-safe-id-generator
;; question entirely. The agent's playground file lives at
;; src/seon/agents/alice.cljs (compiled by shadow at boot).
;;
;; Multi-agent + an ns-safe id generator come back when V0-B-8
;; brings bootstrap eval — that's when an agent dynamically
;; allocating a new playground ns becomes meaningful.
;; ============================================================

(def default-session-id "seon")
(def default-agent-ns 'seon.agents.alice)

;; ============================================================
;; Boot. The single entry point seon.client calls at startup.
;; ============================================================

(defn boot!
  "Create the V0 agent session and install the kick listener. Returns
   binding info the caller can use (or just let chat / replies-after
   resolve by session-id).

   `llm-fn` is a ctx-string → Promise-of-`{:text \"...\"}`. The kick
   handler closes over it; no globals.

     (boot! deepseek-adapter)
     ;; => {:seon.session/id \"seon\"
     ;;     :seon.session/agent-ns seon.agents.alice}"
  [llm-fn]
  (let [{:seon.session/keys [id agent-ns]}
        (create! {:seon.session/id default-session-id
                  :seon.session/agent-ns default-agent-ns})]
    (install-kick! id agent-ns llm-fn)
    {:seon.session/id id
     :seon.session/agent-ns agent-ns}))

;; ============================================================
;; The smart-REPL chat verb.
;;
;; (chat session-id \"text\") — transact a :user message into the
;; session. The kick listener will pick it up and run-turn-once!
;; will fire. The caller polls the DB for the agent's response;
;; we return immediately after the transact.
;;
;; Future (when text fails to read as Clojure at the REPL level):
;; the smart-REPL wrapper turns reader-errors into chat calls
;; automatically. For now it's explicit.
;; ============================================================

(defn chat
  "Inject a :user message into a session. Returns the message-id.
   Doesn't wait for the agent's reply — poll the DB (or watch the
   message log) to see what the agent says back."
  [session-id text]
  (let [mid (agent/new-id!)]
    (db/transact!
      {:seon.db/tx-data
       [{:seon.message/id      mid
         :seon.message/role    :user
         :seon.message/content text
         :seon.message/session [:seon.session/id session-id]
         :seon.message/at      (js/Date.)}]})
    mid))

(defn replies-after
  "Return :assistant messages in `session-id` whose :at is strictly
   after `since-inst`, oldest-first. Pair with chat for poll-style
   testing."
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
