(ns seon.agent.lifecycle
  "The agent's terminal-transition verbs — `wait` / `complete` / `terminate`.

   Each is a small state transact: the verb SETS `:seon.agent/state`; the
   loop ([[seon.agent.loop]]) only READS it. `wait` and `complete` BOTH park
   to the single wakeable `:idle` state — the DIFFERENCE is data, not a
   distinct state value: a `:seon.agent.loop/stop-reason` tx-meta (`:wait` /
   `:complete`) marks WHY, and `wait` also writes `:seon.agent/wait-note`.
   `terminate` is the one state change that alters behavior (→ `:terminated`,
   unwakeable). Each returns the new `:seon.agent/state` keyword (the value
   the transcript shows: `:idle` for wait/complete, `:terminated` for
   terminate), or a loud error envelope on a failed transact or no agent in
   scope (errors are values — never a throw). No verb ever writes a self→self
   message.

   `wait` and `complete` default to the calling agent, read from the ALS
   scope via `(seon.db/current-agent-id)` — call them inside
   `(seon.db/with-agent id …)`. `terminate` takes an explicit id (it is
   orchestrator-only; an agent does not terminate itself).

   The `:seon.agent/*` state schemas live in [[seon.agent]]; the shared
   no-agent-in-scope envelope lives in [[seon.agent.internal]].

   `^:async` fns aren't runtime-instrumented — the `:malli/schema` is the
   contract."
  (:require
    [seon.agent.internal :as internal]
    [seon.agent.message :as msg]
    [seon.db :as db]
    [seon.schema :as schema]))

(schema/register! ::note   :string)
(schema/register! ::result :string)

(defn ^:async wait
  "Park the calling agent: state → :idle, with a note surfaced to monitoring
   agents (`:seon.agent/wait-note`) and a `:seon.agent.loop/stop-reason :wait`
   tx-meta marking WHY it parked (the activity log reads it back). :idle is
   the single wakeable parked state — the agent resumes (→ :active) the
   moment a message arrives via the wake gate. Returns :idle on success, a
   loud error envelope on a failed transact or no agent in scope."
  {:malli/schema [:=> [:catn [::note :string]]
                  [:or :seon.agent/state :seon.db/transact-response]]}
  [note]
  (if-let [id (db/current-agent-id)]
    (let [env (await (db/transact!
                       {:seon.db/tx-data [{:seon.agent/id        id
                                           :seon.agent/state     :idle
                                           :seon.agent/wait-note note}]
                        :seon.db/opts {:tx-meta {:seon.agent.loop/stop-reason :wait}}}))]
      (if (:seon.db/ok? env) :idle env))
    (internal/no-agent-error "wait")))

(defn ^:async complete
  "Finish the calling agent's work: state → :idle (the single wakeable
   parked state — a new message resumes it), tagged with a
   `:seon.agent.loop/stop-reason :complete` tx-meta (WHY it parked, for the
   activity log). If `:seon.agent/parent` is set, send the result to the
   parent (which wakes it via the normal inbound gate); no parent → the
   result is for the human (already said via message/user). The result is a
   MESSAGE, not a state — :idle holds no completion payload. Returns :idle
   on success, the error envelope on a failed transact or no agent in scope."
  {:malli/schema [:=> [:catn [::result :string]]
                  [:or :seon.agent/state :seon.db/transact-response]]}
  [result]
  (if-let [id (db/current-agent-id)]
    (let [ent    (db/entity {:seon.db/ref [:seon.agent/id id]})
          parent (:db/id (:seon.agent/parent ent))
          env    (await (db/transact!
                          {:seon.db/tx-data [{:seon.agent/id    id
                                              :seon.agent/state :idle}]
                           :seon.db/opts {:tx-meta {:seon.agent.loop/stop-reason :complete}}}))]
      (if (:seon.db/ok? env)
        (do (when parent
              (await (msg/message! {:seon.agent.message/content result
                                    :seon.agent.message/to      [parent]})))
            :idle)
        env))
    (internal/no-agent-error "complete")))

(defn ^:async terminate
  "Kill an agent: state → :terminated — the one UNWAKEABLE state (a
   message will not start a loop), tagged with a
   `:seon.agent.loop/stop-reason :terminate` tx-meta for the activity log.
   Orchestrator-only; an agent does not terminate itself. Returns
   :terminated on success, the error envelope on a failed transact."
  {:malli/schema [:=> [:catn [::id :seon.agent/id]]
                  [:or :seon.agent/state :seon.db/transact-response]]}
  [id]
  (let [env (await (db/transact!
                     {:seon.db/tx-data [{:seon.agent/id    id
                                         :seon.agent/state :terminated}]
                      :seon.db/opts {:tx-meta {:seon.agent.loop/stop-reason :terminate}}}))]
    (if (:seon.db/ok? env) :terminated env)))
