(ns seon.agent.run
  "The RUN entity + its lifecycle — a run is the bounded unit of work a
   trigger (an inbound message or a due schedule) opens. While a run is open
   the agent is derived-`:running`; the loop drives turns until a BOUND fires:
   the WORK bound (`turn-limit`, a bumpable count) or the WALL-CLOCK bound
   (`deadline`, an absolute instant) — whichever first.

   The run-id is the FENCING TOKEN: the agent's `:seon.agent/run` points at
   the current run, and every WORK tx LEADS with an in-tx CAS
   ([[seon.db/cas-assert]]) asserting the pointer STILL names this run — a
   write from a superseded or timed-out run (a different run-id, or a
   retracted pointer) aborts the whole tx at the single writer (the database,
   not a pre-read predicate, reports the lost authority). New messages
   `renew!` the lease (bump both bounds — the sliding window); `beat!` writes
   a heartbeat per turn.

   This namespace OWNS the `:seon.agent.run/*` schemas and the lifecycle:
   `open-run!` / `close-run!` / `renew!` / `beat!` / `current-run` /
   `turn-limit-reached?` / `deadline-passed?` /
   `close-overdue-runs!` — the deadline WATCHDOG — and `close-stale-runs!` —
   the HEARTBEAT watchdog (both scans run each pass of the one ticker,
   [[seon.agent.loop/run-tick!]]; the schedule half is
   `seon.agent.schedule/fire-due-schedules!`). `close-run!` is also the ONE
   choke point that fires the Piece 2b parent/root OUTCOME notice.

   Dependency direction (acyclic): it transacts via `seon.db` directly and
   references `:seon.agent/*` keywords from the global registry (request agent
   ids use `:string`, so this ns has NO load-time dependency on seon.agent). It
   requires [[seon.agent.message]] (to send outcome notices — that ns never requires run
   back, so acyclic) and the [[seon.error]] leaf (to record a wedge as a
   `:core` fault). seon.agent requires THIS ns, so the edge runs
   agent → run → {message, error}, never the reverse."
  (:require
    [seon.agent.ctx :as ctx]
    [seon.agent.message :as msg]
    [seon.ai.tokens :as tokens]
    [seon.config :as config]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.db.protocol :as db.protocol]
    [seon.error :as error]
    [seon.schema :as schema]))

;; ============================================================
;; Schema — the run entity, by owning namespace. Its generated identity uses
;; the compact policy; refs reuse the canonical :seon.db/ref shape.
;; ============================================================

(schema/register!
  :seon.agent.run/id
  [:and {:seon.db/identity true
         :seon.db.id/generator :seon.db.id.generator/compact}
   ::db.id/compact-value]) ; the FENCING TOKEN
(schema/register! :seon.agent.run/agent      :seon.db/ref)   ; back-ref → agent
(schema/register! :seon.agent.run/started-at :inst)          ; the wake time
(schema/register! :seon.agent.run/trigger    [:enum :message :schedule])
(schema/register! :seon.agent.run/cause      :seon.db/ref)   ; → the waking message (when :message)
(schema/register! :seon.agent.run/turn-limit :int)           ; WORK-QUANTITY bound (bumpable)
(schema/register! :seon.agent.run/deadline   :inst)          ; WALL-CLOCK bound (absolute)
(schema/register! :seon.agent.run/last-beat-at :inst)        ; heartbeat (liveness; per turn)
;; Pause marker: presence on the OPEN run ⇒ derived state :paused (read by
;; seon.derive/derive-state via the agent's primitives).
(schema/register! :seon.agent.run/paused-at  :inst)
;; Wall-clock budget banked at pause time (deadline − now). `resume!` re-extends
;; `deadline` by this so a long pause never blows the clock bound the instant
;; you wake the run (Gemini fix #1 — pause vs absolute deadline).
(schema/register! :seon.agent.run/remaining-ms :int)
(schema/register! :seon.agent.run/status     [:enum :open :closed])
;; Present iff :closed. :no-forms = the loop's empty-streak halt (the LLM
;; produced no actionable forms for a streak of turns — a clean close to
;; :idle, NOT a bound overrun). :crashed = a boot-recovery close of a run
;; orphaned by a pod crash (architecture crash-recovery), beyond the spec's
;; clean reasons.
(schema/register! :seon.agent.run/closed-reason
                  [:enum :completed :waited :turn-limit :deadline-exceeded
                         :terminated :superseded :error :no-forms :crashed
                         :quiesced])

;; DURABLE RETURN VALUE (multi-agent-context Piece 1). `complete` writes these
;; onto the run when it closes it `:completed`: `result` is the short answer or
;; a pointer one-liner — the DURABLE counterpart to the wake MESSAGE `complete`
;; also sends (message = wake signal, datom = the value a parent reads back at
;; any later time, even after a restart); `result-ref` OPTIONALLY points at the
;; stored work product (a `my.kb` source, a blob entity, a plan root). Both
;; reference the canonical shapes; never inline.
(schema/register! :seon.agent.run/result     :string)
(schema/register! :seon.agent.run/result-ref :seon.db/ref)
;; The close instant — written in EVERY close tx alongside `closed-reason`
;; (whichever reason). Run duration is derivable (`closed-at − started-at`),
;; and the Piece 2d circuit breaker windows over THIS (datahike `:db/txInstant`
;; cannot be backdated, so a stored close instant is what tests window over).
(schema/register! :seon.agent.run/closed-at  :inst)
;; Watchdog staleness threshold (ms) used by the pure `stale-run-ids` scan.
;; `close-stale-runs!` acquires it from the config singleton.
(schema/register! :seon.agent.run/stale-ms   :int)

;; Stored entity kind — required attrs reflect what `open-run!` writes
;; unconditionally; everything conditional is {:optional true} (absent = no
;; key, never nil).
(schema/register! :seon.agent.run
  [:map {:seon.db/entity true}
   [:seon.agent.run/id            :seon.agent.run/id]
   [:seon.agent.run/agent         :seon.agent.run/agent]
   [:seon.agent.run/started-at    :seon.agent.run/started-at]
   [:seon.agent.run/trigger       :seon.agent.run/trigger]
   [:seon.agent.run/status        :seon.agent.run/status]
   [:seon.agent.run/turn-limit    :seon.agent.run/turn-limit]
   [:seon.agent.run/deadline      :seon.agent.run/deadline]
   [:seon.agent.run/cause         {:optional true} :seon.agent.run/cause]
   [:seon.agent.run/last-beat-at  {:optional true} :seon.agent.run/last-beat-at]
   [:seon.agent.run/paused-at     {:optional true} :seon.agent.run/paused-at]
   [:seon.agent.run/remaining-ms  {:optional true} :seon.agent.run/remaining-ms]
   [:seon.agent.run/closed-reason {:optional true} :seon.agent.run/closed-reason]
   [:seon.agent.run/result        {:optional true} :seon.agent.run/result]
   [:seon.agent.run/result-ref    {:optional true} :seon.agent.run/result-ref]
   [:seon.agent.run/closed-at     {:optional true} :seon.agent.run/closed-at]])

;; Slot schemas for the positional predicates below (registered so the
;; :catn labels resolve — the shared-shape / named-arg conventions).
(schema/register! :seon.agent.run/turn-count :int)   ; derived current-turn count
(schema/register! :seon.agent.run/now        :inst)  ; an explicit wall-clock instant

;; ============================================================
;; Defaults — seed a run's two bounds when the agent carries no override.
;; ============================================================

(def default-turn-limit
  "Work-bound seed when the agent has no `:seon.agent/default-turn-limit`.

   Denominated in the loop's work unit for repl-mode `:batch`: TURNS. This is
   a generous safety ceiling; completion, wait, no-progress, errors, or the
   deadline normally stop a run first."
  ctx/default-turn-limit)

(def default-form-limit
  "Work-bound seed under repl-mode `:stream` — denominated in FORMS.

   One stream turn evals at most one form, so the bound is form-count
   ([[seon.derive/run-form-count]]). 3× the batch default: a `:batch` turn
   averaged ~5.6 forms in the repl-milestone rung-0 matrix, and the
   namespaces-milestone rung-1 gate drive
   showed a 20-form budget strands a multi-phase task short of its
   report (evals/runs/2026-07-10-minimal-buildup, ds-r1-ns-move-v1-d1).
   An agent-level `:seon.agent/default-turn-limit` or an explicit
   request seed still wins, whatever the mode."
  ctx/default-form-limit)

(def default-deadline-ms
  "Wall-clock-bound seed (30 min) when the agent has no
   `:seon.agent/default-deadline-ms`. Generous on purpose — the turn-limit is
   the usual stopper; the deadline catches a stalled LLM."
  (:seon.config.run/deadline-ms (config/default-run-policy)))

;; ============================================================
;; Optional agent-local run-bound overrides live in `seon.agent`. The cluster
;; defaults live only in the database config singleton.
;; ============================================================

(schema/register! ::effective-deadline-request
  [:map
   [:seon.db/db :seon.db/db]
   [:seon.agent/id {:optional true} :string]])

(defn- deadline-ms-from [agent policy]
  (or (:seon.agent/default-deadline-ms agent)
      (:seon.config.run/deadline-ms policy)))

(defn- error-value? [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn- direct-error [message]
  {:seon.error/message message})

(schema/register! ::direct-error
  [:map [:seon.error/message :string]])
(schema/register! ::transact-result
  [:or :seon.db/transact-response ::direct-error])

(defn- pull-member [database selector ref]
  {::db.protocol/operation db.protocol/pull-operation
   ::db/db database
   ::db.protocol/selector selector
   ::db.protocol/entity-id ref})

(defn- query-member [database query arguments]
  {::db.protocol/operation db.protocol/query-operation
   ::db/db database
   ::db.protocol/query-form query
   ::db.protocol/arguments arguments})

(defn- successful-member? [member]
  (true? (::db.protocol/success? member)))

(defn- member-result [member]
  (or (::db.protocol/result member)
      (:datahike.query/result member)))

(defn- failed-member [label member]
  {:seon.error/message (str label " failed: "
                            (or (::db.protocol/error member)
                                "the database returned no error message."))
   :seon.error/kind (or (:seon.error/kind member) :core-bug)
   :seon.error/data member})

(def ^:private config-selector
  [:seon.config/repl-mode
   :seon.config.run/batch-turn-limit
   :seon.config.run/stream-form-limit
   :seon.config.run/deadline-ms])

(defn- policy-from [stored]
  (merge (config/default-run-policy) stored))

(defn ^:async effective-deadline-ms
  "Effective run deadline duration for an agent and database value."
  {:malli/schema [:=> [:cat ::effective-deadline-request]
                  [:or [:int {:min 1}] ::direct-error]]}
  [{:seon.db/keys [db] id :seon.agent/id}]
  (let [members (cond-> [(pull-member db config-selector
                                      [:seon.config/id config/cluster-config-id])]
                  id (conj (pull-member db
                                        [:seon.agent/default-deadline-ms]
                                        [:seon.agent/id id])))
        acquired (await (db/execute-many {::db/db db ::db/members members}))
        results (::db/results acquired)]
    (cond
      (error-value? acquired) acquired
      (not= (count members) (count results))
      (direct-error "Effective deadline acquisition returned the wrong member count.")
      (not (every? successful-member? results))
      (failed-member "effective deadline acquisition"
                     (first (remove successful-member? results)))
      :else
      (let [[stored agent] (map member-result results)]
        (deadline-ms-from agent (policy-from stored))))))

;; ============================================================
;; Reads — async over one explicit immutable database value.
;; ============================================================

(schema/register! :seon.agent.run/snapshot
  ;; The run's fingerprint — a plain data view (the internal ref pointers
  ;; agent/cause are omitted; readers drill via the run-id).
  [:map
   [:seon.agent.run/id            :seon.agent.run/id]
   [:seon.agent.run/status        :seon.agent.run/status]
   [:seon.agent.run/trigger       :seon.agent.run/trigger]
   [:seon.agent.run/started-at    :seon.agent.run/started-at]
   [:seon.agent.run/turn-limit    :seon.agent.run/turn-limit]
   [:seon.agent.run/deadline      :seon.agent.run/deadline]
   [:seon.agent.run/last-beat-at  {:optional true} :seon.agent.run/last-beat-at]
   [:seon.agent.run/paused-at     {:optional true} :seon.agent.run/paused-at]
   [:seon.agent.run/closed-reason {:optional true} :seon.agent.run/closed-reason]])

(defn- run-projection [run]
  (select-keys run [:seon.agent.run/id :seon.agent.run/status
                    :seon.agent.run/trigger :seon.agent.run/started-at
                    :seon.agent.run/turn-limit :seon.agent.run/deadline
                    :seon.agent.run/last-beat-at :seon.agent.run/paused-at
                    :seon.agent.run/closed-reason]))

;; The agent-id VALUE schema in these request maps is base `:string`, NOT
;; `:seon.agent/id`: every register! here is LOAD-TIME, and seon.agent.run is a
;; LEAF (requires no seon.agent namespace) that loads BEFORE seon.agent registers
;; `:seon.agent/id` — referencing it at cold boot throws. `:string` also admits
;; the literal "root" orchestrator-root id (the generated shape is enforced
;; at CREATE, via `:seon.agent/id` itself); these are run OPS over an existing id.
(schema/register! ::current-run-request
  [:map
   [:seon.agent/id :string]
   [:seon.db/db {:optional true} :seon.db/db]])

(defn ^:async current-run
  "The agent's CURRENT open run entity, or nil.

   The `:seon.agent/run` pointer, if it
   resolves to an `:open` run. A plain touched map; drill its refs
   via follow-up reads. Omission of `:seon.db/db` acquires the current cached
   database value once."
  {:malli/schema [:=> [:cat ::current-run-request]
                  [:or [:maybe :map] ::direct-error]]}
  [{database :seon.db/db id :seon.agent/id}]
  (let [database (or database (await (db/db)))]
    (if (error-value? database)
      database
      (let [agent (await
                   (db/pull
                    {::db/db database
                     ::db/pull-pattern
                     [{:seon.agent/run
                       [:seon.agent.run/id :seon.agent.run/status
                        :seon.agent.run/trigger :seon.agent.run/started-at
                        :seon.agent.run/turn-limit :seon.agent.run/deadline
                        :seon.agent.run/last-beat-at
                        :seon.agent.run/paused-at
                        :seon.agent.run/closed-reason]}]
                     ::db/ref [:seon.agent/id id]}))
            run (:seon.agent/run agent)]
        (cond
          (error-value? agent) agent
          (= :open (:seon.agent.run/status run)) run
          :else nil)))))

(schema/register! ::current-runs
  [:vector
   [:map
    [:seon.agent/id :string]
    [:seon.agent.run/id :seon.agent.run/id]]])
(schema/register! ::running-turns
  [:vector
   [:map
    [:seon.agent.run/id :seon.agent.run/id]
    [:seon.agent.turn/id :string]]])
(schema/register! ::quiescence-work
  [:map
   [:seon.db/db :seon.db/db]
   [::current-runs ::current-runs]
   [::running-turns ::running-turns]])

(def ^:private current-runs-query
  '[:find ?agent-id ?run-id
    :where
    [?agent :seon.agent/id ?agent-id]
    [?agent :seon.agent/run ?run]
    [?run :seon.agent.run/id ?run-id]
    [?run :seon.agent.run/status :open]])

(def ^:private running-turns-query
  '[:find ?run-id ?turn-id
    :where
    [?run :seon.agent.run/id ?run-id]
    [?turn :seon.agent.turn/run ?run]
    [?turn :seon.agent.turn/id ?turn-id]
    [?turn :seon.agent.turn/status :running]])

(defn ^:async quiescence-work!
  "Read current run ownership and running turn brackets at one database value.

   The current-run projection includes only open runs still pointed to by an
   agent. The running-turn projection deliberately includes every running
   turn, even when a concurrent lifecycle close has already removed its run
   pointer. Planned drain callers close pointer-owned runs without a running
   turn, then re-read this projection until both vectors are empty."
  {:malli/schema [:=> [:cat] [:or ::quiescence-work ::direct-error]]}
  []
  (let [database (await (db/db))]
    (if (error-value? database)
      database
      (let [acquired
        (await
         (db/execute-many
          {::db/db database
           ::db/members
           [(query-member database current-runs-query [])
            (query-member database running-turns-query [])]}))
            members (::db/results acquired)]
        (cond
          (error-value? acquired) acquired
          (not= 2 (count members))
          (direct-error "Quiescence acquisition returned the wrong member count.")
          (not (every? successful-member? members))
          (failed-member "Quiescence acquisition"
                         (first (remove successful-member? members)))
          :else
          (let [[current-runs running-turns] (map member-result members)]
            {::db/db database
       ::current-runs
       (->> current-runs
            (map (fn [[agent-id run-id]]
                   {:seon.agent/id agent-id
                    :seon.agent.run/id run-id}))
            (sort-by (juxt :seon.agent/id :seon.agent.run/id))
            vec)
       ::running-turns
       (->> running-turns
            (map (fn [[run-id turn-id]]
                   {:seon.agent.run/id run-id
                    :seon.agent.turn/id turn-id}))
            (sort-by (juxt :seon.agent.run/id :seon.agent.turn/id))
            vec)}))))))

(defn turn-limit-reached?
  "Work bound: has `turn-count` reached `turn-limit`?

   Pure — the caller
   passes the derived current-turn count, so this is wall-clock-free."
  {:malli/schema [:=> [:catn [:seon.agent.run/turn-count :seon.agent.run/turn-count]
                             [:seon.agent.run/turn-limit :seon.agent.run/turn-limit]]
                  :boolean]}
  [turn-count turn-limit]
  (>= turn-count turn-limit))

(defn deadline-passed?
  "Wall-clock bound: is `now` past `deadline`?

   Pure — the caller passes the
   instant, so it's testable without the system clock."
  {:malli/schema [:=> [:catn [:seon.agent.run/deadline :seon.agent.run/deadline]
                             [:seon.agent.run/now :seon.agent.run/now]]
                  :boolean]}
  [deadline now]
  (> (.getTime now) (.getTime deadline)))

;; ============================================================
;; Writes — fencing-guarded lifecycle. Each WORK tx LEADS with an in-tx CAS
;; ([[seon.db/cas-assert]]) asserting the agent's `:seon.agent/run` STILL names
;; this run-id; if it moved (superseded) or was retracted (watchdog-closed /
;; timed-out) the whole tx aborts at the writer. The writer returns that CAS
;; failure directly as data; errors are values and never throw.
;; ============================================================

(defn- run-fence
  "The leading work-fence CAS op (DATA): assert the agent `id`'s
   `:seon.agent/run` pointer STILL names `run-id`. Lead a work-tx with this and
   the tx commits iff the pointer is unchanged; a moved/retracted pointer aborts
   the WHOLE tx and returns a direct :transact/cas error value."
  [id run-id]
  (db/cas-assert [:seon.agent/id id] :seon.agent/run [:seon.agent.run/id run-id]))

(schema/register! ::open-run-request
  [:map
   [:seon.agent/id              :string]
   [:seon.agent.run/trigger     :seon.agent.run/trigger]
   [:seon.agent.run/cause       {:optional true} :seon.db/ref]
   [:seon.agent.run/turn-limit  {:optional true} :seon.agent.run/turn-limit]
   [:seon.agent.run/deadline    {:optional true} :seon.agent.run/deadline]])

(defn- new-run-row
  [run-id id trigger cause now turn-limit deadline]
  (cond->
   {:db/id                     "run"
    :seon.agent.run/id         run-id
    :seon.agent.run/agent      [:seon.agent/id id]
    :seon.agent.run/started-at now
    :seon.agent.run/trigger    trigger
    :seon.agent.run/status     :open
    :seon.agent.run/turn-limit turn-limit
    :seon.agent.run/deadline   deadline}
    cause (assoc :seon.agent.run/cause cause)))

(defn ^:async open-run!
  "Open a run for an EXISTING agent and point `:seon.agent/run` at it.

   In the SAME tx — ATOMICALLY, via a compare-and-swap that asserts the
   agent had NO
   open run (the `:seon.agent/run` attr ABSENT). Idle→running is one
   serialized step: when two wakes (a message + a schedule fire, or two
   messages) race, the database writer serializes the transactions and the second CAS sees
   the first's pointer, so its WHOLE tx fails — no duplicate `:open` run is
   ever committed (the loser gets the direct CAS error; the wake handler
   renews instead). Seeds `turn-limit` from `:seon.agent/default-turn-limit`
   (else [[default-turn-limit]]) and `deadline` from now +
   `:seon.agent/default-deadline-ms` (else [[default-deadline-ms]]); explicit
   seeds in the request win. Does NOT flip any stored state — state is
   derived. Returns the run's plain projection on success, or a direct error
   value — a CAS loss included. `^:async`."
  {:malli/schema [:=> [:cat ::open-run-request]
                  [:or :seon.agent.run/snapshot ::direct-error]]}
  [{id :seon.agent/id trigger :seon.agent.run/trigger cause :seon.agent.run/cause
    tl :seon.agent.run/turn-limit dl :seon.agent.run/deadline}]
  (let [database (await (db/db))]
    (if (error-value? database)
      database
      (let [acquired
            (await
             (db/execute-many
              {::db/db database
               ::db/members
               [(pull-member database
                             [:seon.agent/default-turn-limit
                              :seon.agent/default-deadline-ms]
                             [:seon.agent/id id])
                (pull-member database config-selector
                             [:seon.config/id config/cluster-config-id])]}))
            members (::db/results acquired)]
        (cond
          (error-value? acquired) acquired
          (not= 2 (count members))
          (direct-error "open-run!: database acquisition returned the wrong member count.")
          (not (every? successful-member? members))
          (failed-member "open-run! acquisition"
                         (first (remove successful-member? members)))
          :else
          (let [[a stored-policy] (map member-result members)]
            (if (nil? a)
              (direct-error
               (str "open-run!: no agent " (pr-str id)
                    " — create the agent first."))
              (let [now        (js/Date.)
            policy     (policy-from stored-policy)
            ;; Run-bound SEED (config-driven agent-init, move 9): the new
            ;; Agent-level datoms are explicit overrides. Cluster defaults are
            ;; the frozen config singleton policy above.
            turn-limit (or tl
                           (:seon.agent/default-turn-limit a)
                           ;; mode-denominated const fallback: forms under
                           ;; :stream, turns under :batch (repl-milestone rung-0 verdict).
                           (if (= :stream (:seon.config/repl-mode stored-policy))
                             (:seon.config.run/stream-form-limit policy)
                             (:seon.config.run/batch-turn-limit policy)))
            deadline   (or dl (js/Date. (+ (.getTime now)
                                           (deadline-ms-from a policy))))
            res
            (await
              (db.id/allocate!
                {::db/db database
                 ::db.id/allocations
                 [{::db.id/key :seon.agent.run/id
                   ::db.id/identity-attr :seon.agent.run/id}]
                 ::db.id/transaction-builder
                 (fn [ids]
                   (let [run-id  (get ids :seon.agent.run/id)
                         run-row (new-run-row run-id id trigger cause now
                                              turn-limit deadline)]
                     {:seon.db/tx-data
                      [run-row
                       ;; ATOMIC WAKE GUARD: point the agent at the
                       ;; just-created run ONLY IF it has no open run. The
                       ;; run row leads, so the lookup ref resolves inside
                       ;; this same allocation-aware transaction.
                       [:db.fn/cas [:seon.agent/id id]
                        :seon.agent/run nil
                        [:seon.agent.run/id run-id]]]}))}))]
        (if (error-value? res)
          res
          (run-projection
           (new-run-row (get-in res [::db.id/ids :seon.agent.run/id])
                        id trigger cause now turn-limit deadline)))))))))))

(schema/register! ::close-run-request
  [:map
   [:seon.agent.run/id            :seon.agent.run/id]
   [:seon.agent.run/closed-reason :seon.agent.run/closed-reason]
   [:seon.db/db {:optional true} :seon.db/db]])

(defn ^:no-doc close-tx-data
  "Transaction data that closes a run.

   Always the close-row (status :closed +
   reason). When the agent still OWNS this run, ALSO lead with the work-fence
   CAS ([[run-fence]] — assert the pointer STILL names this run) and retract the
   pointer, all in ONE tx — so a supersede landing in the owns?-read→commit
   window aborts the whole tx instead of retracting the NEW owner's pointer
   (the pointer only moves off this run via a concurrent close, which already
   closed it). Pure (no db read) so the fence wiring is unit-testable. Every
   close stamps `:seon.agent.run/closed-at` = `closed-at` alongside the reason."
  [owns? agent-id run-id reason closed-at]
  (let [close-row {:seon.agent.run/id            run-id
                   :seon.agent.run/status        :closed
                   :seon.agent.run/closed-reason reason
                   :seon.agent.run/closed-at     closed-at}]
    (if owns?
      [(run-fence agent-id run-id)
       close-row
       [:db/retract [:seon.agent/id agent-id] :seon.agent/run]]
      [close-row])))

;; ============================================================
;; Outcome routing (multi-agent-context Piece 2b) — a closed run is a TASK
;; OUTCOME, and the task's owner is the PARENT. `close-run!` is the ONE choke
;; point every abnormal close funnels through (the loop's bound closes, the
;; deadline watchdog, the heartbeat watchdog, boot crash-recovery all call it),
;; so the parent notice lives HERE, once — never sprinkled across the close
;; sites. Notices go out only for the OUTCOME reasons below; `:completed` is
;; excluded (`seon.agent.lifecycle/complete` sends the result itself),
;; `:waited`/`:terminated`/`:superseded` are not outcomes. A notice is an
;; `:agent`-origin message `from` the child — the loop/watchdog sends ON THE
;; CHILD'S BEHALF (same authorship model as `complete`), so it WAKES the parent
;; through the normal inbound gate. NOT `:core`: `waking-inbound?` (message.cljs)
;; excludes `:core`-origin messages from waking, and an outcome notice that
;; can't wake a parked parent defeats the design. `:crashed` (a wedge) ALSO
;; escalates to root, deduped when the parent already IS root. Root's OWN wedge
;; is parentless → the notice goes to the user and root stays idle (the
;; stay-idle ruling: a self-message would be refused and no rewake is wanted).
;; ============================================================

(def ^:private outcome-reasons
  "The `closed-reason`s that message the PARENT (Piece 2b). `:completed` is
   NOT here — `complete` owns the result message; `:waited`/`:terminated`/
   `:superseded` are not task outcomes."
  #{:turn-limit :deadline-exceeded :error :no-forms :crashed})

(def ^:private root-id
  "The orchestrator-root's literal id (carved into `:seon.agent/id`)." "root")

(defn- outcome-notice-content
  "The short parent-facing notice text for a closed run outcome."
  [child-id reason turns purpose]
  (str "run for " child-id " closed :" (name reason)
       " after " turns " turn" (when (not= 1 turns) "s")
       (when (and (string? purpose) (seq purpose))
         ;; TOKEN-budgeted clip (the one estimator) — never a raw char subs.
         (str " — " (tokens/clip-str purpose 20)))
       (when (contains? #{:turn-limit :deadline-exceeded} reason)
         (str " · budget exhausted, not death: message me again to open a "
              "fresh run (my context + plan persist)."))))

(def ^:private close-selector
  [:seon.agent.run/id :seon.agent.run/status
   {:seon.agent.run/agent
    [:seon.agent/id :seon.agent/purpose
     {:seon.agent/parent [:seon.agent/id]}
     {:seon.agent/run [:seon.agent.run/id]}]}
   {:seon.agent.turn/_run
    [:seon.agent.turn/id :seon.agent.turn/scheduled?]}])

(defn- work-turn-count [run]
  (->> (:seon.agent.turn/_run run)
       (remove :seon.agent.turn/scheduled?)
       count))

(defn- log-notice-error! [child-id recipient result]
  (when (error-value? result)
    (js/console.error
     (str "seon.agent.run/notify-outcome!: " recipient
          " notice FAILED for " child-id ": "
          (:seon.error/message result)))))

(defn ^:async ^:private notify-outcome!
  "Message the parent (or the user) that the run closed with `reason`.

   On `:crashed` also escalate to root. `origin :agent`, `from` the child — so
   it WAKES the parent through the normal inbound gate. A no-op for
   non-outcome reasons. Errors are values — a failed notice is logged, never
   re-thrown into the close."
  [database reason run-id]
  (when (contains? outcome-reasons reason)
    (let [acquired
          (await
           (db/execute-many
            {::db/db database
             ::db/members
             [(pull-member database close-selector
                           [:seon.agent.run/id run-id])
              (pull-member database [:seon.agent/id]
                           [:seon.agent/id root-id])]}))
          members (::db/results acquired)]
      (if (or (error-value? acquired)
              (not= 2 (count members))
              (not (every? successful-member? members)))
        (js/console.error
         (str "seon.agent.run/notify-outcome!: outcome acquisition FAILED for "
              run-id ": "
              (or (:seon.error/message acquired)
                  (::db.protocol/error
                   (first (remove successful-member? members)))
                  "invalid result")))
        (let [[run root] (map member-result members)
          child     (:seon.agent.run/agent run)
          child-id  (:seon.agent/id child)
          parent-id (:seon.agent/id (:seon.agent/parent child))
          purpose   (:seon.agent/purpose child)
          turns     (work-turn-count run)
          content   (outcome-notice-content child-id reason turns purpose)
          parent-to (if parent-id [:seon.agent/id parent-id] msg/user-ref)
          ;; Sent IN THE CHILD'S SCOPE: the notice goes out on the child's
          ;; behalf, so hop derivation uses the child's per-pair depth (the
          ;; ping-pong guard applies to a notice storm exactly as to any
          ;; agent send — the message always TRANSACTS; only waking is
          ;; hop-gated at the trigger).
          send!     (fn ^:async send-notice! [to]
                      (db/with-agent child-id
                        (fn ^:async notice! []
                          (await
                            (msg/message! {:seon.agent.message/content content
                                           :seon.agent.message/from    [:seon.agent/id child-id]
                                           :seon.agent.message/to       [to]
                                           :seon.db/db database})))))]
      (log-notice-error! child-id "parent" (await (send! parent-to)))
      ;; Wedge escalation: a :crashed run additionally tells root — but never
      ;; a self-message (child IS root) and never a duplicate (parent IS root).
      (when (and (= reason :crashed)
                 (not= child-id root-id)
                 (not= parent-id root-id)
                 (some? root))
        (log-notice-error! child-id "root escalation"
                           (await (send! [:seon.agent/id root-id])))))))))

(defn ^:async close-run!
  "Close a run — `:status :closed` + `closed-reason`.

   When the agent still
   OWNS this run, also retract its `:seon.agent/run` pointer so derived state
   falls to `:idle`. When it does NOT own it (a superseded run being cleaned
   up), the run is marked closed but the agent's live pointer is left
   untouched — fencing protects the current run.

   The owned retract is FENCED in-tx (the same work-fence CAS as `beat!`/
   `renew!`): the tx LEADS with a CAS asserting the agent's `:seon.agent/run`
   STILL names this run, so a supersede landing in the owns?-read→commit window
   aborts the WHOLE tx rather than retracting the NEW owner's pointer. The
   pointer only ever moves off this run via a (concurrent) `close-run!` retract,
   so a supersede in that window means another close already closed THIS run and
   a fresh run now owns the agent; the loser's CAS-aborted tx is a harmless
   no-op (this run is already closed; the new owner is left intact). Close +
   retract stay in ONE tx — never a `:closed` run with a live pointer, which
   would deadlock `open-run!`'s absent-pointer CAS. `^:async`."
  {:malli/schema [:=> [:cat ::close-run-request] ::transact-result]}
  [{run-id :seon.agent.run/id reason :seon.agent.run/closed-reason
    database :seon.db/db}]
  (let [database (or database (await (db/db)))]
    (if (error-value? database)
      database
      (let [r (await
               (db/pull {::db/db database
                         ::db/pull-pattern close-selector
                         ::db/ref [:seon.agent.run/id run-id]}))]
        (cond
          (error-value? r) r
          (nil? r) (direct-error (str "close-run!: no run " (pr-str run-id) "."))
          :else
          (let [agent     (:seon.agent.run/agent r)
            agent-id  (:seon.agent/id agent)
            ;; OWNS? = the agent's current open run IS this run in the acquired
            ;; ordinary projection. When owned, retract the
            ;; pointer in the SAME tx so derived state falls to :idle; when a
            ;; DIFFERENT run owns the agent (a superseded run being cleaned up),
            ;; mark this run closed but leave the live pointer untouched.
            owns?     (= run-id
                         (:seon.agent.run/id (:seon.agent/run agent)))
            report    (await (db/transact!
                              {::db/db database
                               ::db/tx-data
                               (close-tx-data owns? agent-id run-id reason
                                              (js/Date.))}))]
        ;; Piece 2b — route the parent/root OUTCOME notice through this one
        ;; choke point (only after the close committed; only for an outcome
        ;; reason; never for :completed, which `complete` messages itself).
        (when-not (error-value? report)
          (await (notify-outcome! (:db-after report) reason run-id)))
        report))))))

(schema/register! ::renew-request
  [:map
   [:seon.agent/id     :string]
   [:seon.agent.run/id :seon.agent.run/id]
   ;; Optional clock extension (ms) — defaults to the agent's
   ;; default-deadline-ms (else the global default).
   [:seon.agent.run/deadline-extension-ms {:optional true} :int]])

(defn ^:async renew!
  "Renew the lease — the sliding turn + wall-clock window.

   Bump `turn-limit` by +1 and push
   `deadline` out to now + extension. The tx LEADS with the [[run-fence]] CAS —
   a write from a superseded/timed-out run aborts at the writer and returns the
   direct CAS error (no pre-read predicate). `^:async`."
  {:malli/schema [:=> [:cat ::renew-request] ::transact-result]}
  [{id :seon.agent/id run-id :seon.agent.run/id
    ext :seon.agent.run/deadline-extension-ms}]
  (let [database (await (db/db))]
    (if (error-value? database)
      database
      (let [acquired
            (await
             (db/execute-many
              {::db/db database
               ::db/members
               [(pull-member database [:seon.agent.run/turn-limit]
                             [:seon.agent.run/id run-id])
                (pull-member database [:seon.agent/default-deadline-ms]
                             [:seon.agent/id id])
                (pull-member database config-selector
                             [:seon.config/id config/cluster-config-id])]}))
            members (::db/results acquired)]
        (cond
          (error-value? acquired) acquired
          (not= 3 (count members))
          (direct-error "renew!: database acquisition returned the wrong member count.")
          (not (every? successful-member? members))
          (failed-member "renew! acquisition"
                         (first (remove successful-member? members)))
          :else
          (let [[run agent stored-policy] (map member-result members)
                old-turn-limit (:seon.agent.run/turn-limit run)
                extension (or ext (:seon.agent/default-deadline-ms agent)
                              (:seon.config.run/deadline-ms
                               (policy-from stored-policy)))
                new-deadline (js/Date. (+ (.getTime (js/Date.)) extension))]
            (if-not (int? old-turn-limit)
              (direct-error (str "renew!: no run " (pr-str run-id) "."))
              (await
               (db/transact!
                {::db/db database
                 ::db/tx-data
                 [(run-fence id run-id)
                  [:db.fn/cas [:seon.agent.run/id run-id]
                   :seon.agent.run/turn-limit old-turn-limit (inc old-turn-limit)]
                  [:db/add [:seon.agent.run/id run-id]
                   :seon.agent.run/deadline new-deadline]]})))))))))

(schema/register! ::beat-request
  [:map
   [:seon.agent/id     :string]
   [:seon.agent.run/id :seon.agent.run/id]])

(defn ^:async beat!
  "Heartbeat: write `last-beat-at` = now.

   The tx LEADS with the [[run-fence]]
   CAS, so a beat from a superseded/closed run aborts and returns the CAS-fail
   direct error — the loop reads that as lost authority and stops.
   `^:async`."
  {:malli/schema [:=> [:cat ::beat-request] ::transact-result]}
  [{id :seon.agent/id run-id :seon.agent.run/id}]
  (await (db/transact!
           {:seon.db/tx-data
            [(run-fence id run-id)
             {:seon.agent.run/id run-id :seon.agent.run/last-beat-at (js/Date.)}]})))

(schema/register! ::pause-request
  [:map
   [:seon.agent/id     :string]
   [:seon.agent.run/id :seon.agent.run/id]
   [:seon.db/db {:optional true} :seon.db/db]])

(defn ^:async pause!
  "Pause the open run: stamp `paused-at` = now (⇒ `:paused`).

   BANK the remaining wall-clock budget on `remaining-ms` (`deadline − now`,
   floored at 0). [[resume!]] re-extends `deadline` by it, so a long pause
   never instantly blows the clock bound. The tx LEADS with the [[run-fence]]
   CAS. `^:async`."
  {:malli/schema [:=> [:cat ::pause-request] ::transact-result]}
  [{id :seon.agent/id run-id :seon.agent.run/id database :seon.db/db}]
  (let [database (or database (await (db/db)))]
    (if (error-value? database)
      database
      (let [run (await
                 (db/pull {::db/db database
                           ::db/pull-pattern [:seon.agent.run/deadline]
                           ::db/ref [:seon.agent.run/id run-id]}))
            deadline (:seon.agent.run/deadline run)]
        (cond
          (error-value? run) run
          (nil? deadline) (direct-error (str "pause!: no run " (pr-str run-id) "."))
          :else
          (let [now (js/Date.)
                remaining-ms (max 0 (- (.getTime deadline) (.getTime now)))]
            (await
             (db/transact!
              {::db/db database
               ::db/tx-data
               [(run-fence id run-id)
                (db/cas-assert [:seon.agent.run/id run-id]
                               :seon.agent.run/deadline deadline)
                [:db.fn/cas [:seon.agent.run/id run-id]
                 :seon.agent.run/paused-at nil now]
                [:db/add [:seon.agent.run/id run-id]
                 :seon.agent.run/remaining-ms remaining-ms]]}))))))))

(schema/register! ::resume-request
  [:map
   [:seon.agent/id     :string]
   [:seon.agent.run/id :seon.agent.run/id]
   [:seon.db/db {:optional true} :seon.db/db]])

(defn ^:async resume!
  "Resume a PAUSED run: RETRACT `paused-at` (⇒ `:running`).

   Re-extend `deadline` to now + the banked `remaining-ms`
   (a long pause never instantly blows the clock bound). GUARDED on
   `paused-at`: a run that is NOT paused has no banked budget, so resume! is
   a loud direct error rather than an accidental deadline
   overwrite with the default window. The tx LEADS with the [[run-fence]] CAS
   too. `^:async`."
  {:malli/schema [:=> [:cat ::resume-request] ::transact-result]}
  [{id :seon.agent/id run-id :seon.agent.run/id database :seon.db/db}]
  (let [database (or database (await (db/db)))]
    (if (error-value? database)
      database
      (let [run (await
                 (db/pull {::db/db database
                           ::db/pull-pattern [:seon.agent.run/paused-at
                                          :seon.agent.run/remaining-ms]
                           ::db/ref [:seon.agent.run/id run-id]}))
            paused-at (:seon.agent.run/paused-at run)
            remaining-ms (:seon.agent.run/remaining-ms run)]
        (cond
          (error-value? run) run
          (or (nil? paused-at) (nil? remaining-ms))
          (direct-error
           (str "resume!: run " (pr-str run-id)
                " is not paused with a banked :seon.agent.run/remaining-ms."))
          :else
          (let [new-deadline (js/Date. (+ (.getTime (js/Date.)) remaining-ms))]
            (await
             (db/transact!
              {::db/db database
               ::db/tx-data
               [(run-fence id run-id)
                (db/cas-assert [:seon.agent.run/id run-id]
                               :seon.agent.run/paused-at paused-at)
                (db/cas-assert [:seon.agent.run/id run-id]
                               :seon.agent.run/remaining-ms remaining-ms)
                [:db/add [:seon.agent.run/id run-id]
                 :seon.agent.run/deadline new-deadline]
                [:db/retract [:seon.agent.run/id run-id]
                 :seon.agent.run/paused-at]
                [:db/retract [:seon.agent.run/id run-id]
                 :seon.agent.run/remaining-ms]]}))))))))

;; ============================================================
;; The deadline WATCHDOG — the wall-clock half of the one ticker
;; ([[seon.agent.loop/install-ticker!]]). The DB is passive about wall-clock:
;; `now > deadline` is true in the cluster, but nothing fires until something
;; checks. This scan IS that check — the EXTERNAL enforcement of the clock
;; bound (a stalled LLM burns the clock and can't self-detect). A run whose
;; async turn overran its deadline is closed here; when the await returns the
;; loop's `next-event` (re-read over the latest db) sees it closed and bails,
;; and any in-flight work tx's leading CAS aborts. (A truly-SYNC
;; runaway needs worker termination — Phase 2, not here.)
;; ============================================================

(schema/register! ::close-overdue-request  [:map [:seon.agent/now :inst]])
(schema/register! ::close-overdue-response
  [:map [:seon.agent.run/closed [:vector :seon.agent.run/id]]])

(defn ^:async close-overdue-runs!
  "Close every OPEN, non-PAUSED run whose `deadline` is past `now`.

   With `:deadline-exceeded` — clearing the agent's `:seon.agent/run` pointer (via
   [[close-run!]]'s owned-retract) so derived state falls to `:idle`. Returns
   the run-ids it closed (map-out). IDEMPOTENT: a re-run finds the now-`:closed`
   runs gone from the scan. A PAUSED run (carrying `:seon.agent.run/paused-at`)
   is SKIPPED — `paused-at` froze the clock and its absolute `deadline` is
   stale until [[resume!]] re-extends it, so it must never be deadline-killed.
   Fencing is automatic (each close goes through [[close-run!]]). `^:async`."
  {:malli/schema [:=> [:cat ::close-overdue-request]
                  [:or ::close-overdue-response ::direct-error]]}
  [{now :seon.agent/now}]
  (let [database (await (db/db))]
    (if (error-value? database)
      database
      (let [rows (await
                  (db/query
                   {::db/db database
                    ::db/query
                        '[:find ?rid ?deadline
                          :where
                          [?r :seon.agent.run/status :open]
                          [?r :seon.agent.run/id ?rid]
                          [?r :seon.agent.run/deadline ?deadline]
                          (not [?r :seon.agent.run/paused-at _])]}))]
        (if (error-value? rows)
          rows
          (let [overdue (->> rows
                             (filter (fn [[_ deadline]]
                                       (> (.getTime now) (.getTime deadline))))
                             (map first)
                             sort
                             vec)]
            (loop [[rid & more] overdue]
              (if-not rid
                {:seon.agent.run/closed overdue}
                (let [result
                      (await
                       (close-run! {:seon.agent.run/id rid
                                    :seon.agent.run/closed-reason
                                    :deadline-exceeded}))]
                  (if (error-value? result)
                    result
                    (recur more)))))))))))

;; ============================================================
;; The HEARTBEAT WATCHDOG (multi-agent-context Piece 2c) — a WEDGED agent
;; never closes its own run (no event ever fires), so a stale heartbeat needs
;; an observer OUTSIDE the agent. This is a stateless SCAN (no per-run armed
;; timers — everything derived from datoms each pass, so it survives a pod
;; restart and catches a pre-restart wedge on the first scan). Reuses the ONE
;; ticker ([[seon.agent.loop/run-tick!]] calls this each pass) — no parallel
;; setInterval. On an OPEN, non-paused run whose freshness anchor
;; (`last-beat-at`, else `started-at`) is older than `stale-ms`:
;;   1. close it `:crashed` (→ [[close-run!]] retracts the pointer so the
;;      agent falls to :idle+wakeable AND fires the Piece 2b parent/root
;;      notice via the one choke point), then
;;   2. `seon.error/record!` a `:core` fault so the wedge enters the standard
;;      triage chain (watch-faults → inspect → repro → fork). Under the dev
;;      `:crash` dial the pod exits loudly on a wedge — the house posture.
;; Fencing already covers the false-positive case: the pointer retract in
;; close-run! means a late-beating driver's leading CAS aborts (lost authority
;; → the loop terminates) — a no-op, never a double-drive. PAUSED runs are
;; SKIPPED (a paused agent legitimately does not beat).
;; ============================================================

(def ^:private stale-runs-query
  '[:find ?rid ?agent-id ?started ?anchor
    :where
    [?r :seon.agent.run/status :open]
    [?r :seon.agent.run/id ?rid]
    [?r :seon.agent.run/agent ?agent]
    [?agent :seon.agent/id ?agent-id]
    [?r :seon.agent.run/started-at ?started]
    (not [?r :seon.agent.run/paused-at _])
    (or-join [?r ?anchor]
             [?r :seon.agent.run/last-beat-at ?anchor]
             (and [?r :seon.agent.run/started-at ?anchor]
                  (not [?r :seon.agent.run/last-beat-at _])))])

(schema/register! ::stale-run-row [:tuple :string :string :inst :inst])
(schema/register! ::stale-run-rows [:set ::stale-run-row])

(defn stale-run-ids
  "Open, non-paused run-ids whose heartbeat is stale at `now`.

   A pure function of acquired rows, `now`, and `stale-ms`. The scan is
   deterministic: a test backdates beats and passes a chosen `now`, with zero
   timers. The freshness anchor is `:last-beat-at`, or `:started-at` for a run
   that never beat. Paused runs are excluded by the acquisition query."
  {:malli/schema [:=> [:catn [::stale-run-rows ::stale-run-rows]
                             [:seon.agent/now :inst]
                             [:seon.agent.run/stale-ms :seon.agent.run/stale-ms]]
                  [:vector :seon.agent.run/id]]}
  [rows now stale-ms]
  (let [cutoff (- (.getTime ^js now) stale-ms)]
    (->> rows
         (keep (fn [[run-id _agent-id _started-at anchor]]
                 (when (< (.getTime ^js anchor) cutoff) run-id)))
         sort
         vec)))

(def ^:private watchdog-config-selector
  [:seon.config/id :seon.config.watchdog/stale-ms])

(schema/register! ::close-stale-request
  [:map [:seon.agent/now :inst]])

(defn ^:async close-stale-runs!
  "Close every open run whose heartbeat is stale at `now` as `:crashed`.

   The effectful SHELL over the pure [[stale-run-ids]] scan.
   For each stale run close it `:crashed` (via [[close-run!]] — the Piece 2b
   parent/root outcome notice + the pointer retract that unsticks the agent)
   and `seon.error/record!` a `:core` fault (the wedge is OUR bug — it plugs
   into the triage chain: watch-faults → inspect → repro → fork). Close FIRST
   so the notice + retract land regardless of the dial, THEN record — on the
   dev `:crash` dial the loud exit happens AFTER the agent is unstuck. Returns
   the run-ids it closed (map-out). IDEMPOTENT — a re-scan finds the
   now-`:closed` runs gone. `^:async`."
  {:malli/schema [:=> [:cat ::close-stale-request]
                  [:or ::close-overdue-response ::direct-error]]}
  [{now :seon.agent/now}]
  (let [database (await (db/db))]
    (if (error-value? database)
      database
      (let [acquired
            (await
             (db/execute-many
              {::db/db database
               ::db/members
               [(query-member database stale-runs-query [])
                (pull-member database watchdog-config-selector
                             [:seon.config/id config/cluster-config-id])]}))
            members (::db/results acquired)]
        (cond
          (error-value? acquired) acquired

          (not= 2 (count members))
          (direct-error
           "close-stale-runs!: database acquisition returned the wrong member count.")

          (not (every? successful-member? members))
          (failed-member "close-stale-runs! acquisition"
                         (first (remove successful-member? members)))

          :else
          (let [[rows stored-configuration] (map member-result members)]
            (if-not stored-configuration
              (direct-error "close-stale-runs!: the config singleton is missing.")
              (let [configuration (db/decode-edn-values stored-configuration)
                    stale-ms (config/watchdog-stale-ms configuration)
                    ids (stale-run-ids rows now stale-ms)
                    agent-id-by-run-id (into {} (map (juxt first second)) rows)]
                (loop [[run-id & more] ids]
                  (if-not run-id
                    {:seon.agent.run/closed ids}
                    (let [agent-id (get agent-id-by-run-id run-id)
                          result
                          (await
                           (close-run!
                            {:seon.agent.run/id run-id
                             :seon.agent.run/closed-reason :crashed}))]
                      (if (error-value? result)
                        result
                        (do
                          (error/record!
                           {:seon.error/raw
                            (js/Error.
                             (str "heartbeat watchdog: run " run-id
                                  " (agent " agent-id
                                  ") wedged — no heartbeat progress for ≥"
                                  stale-ms "ms; closed :crashed"))
                            :seon.error/fault :core})
                          (recur more))))))))))))))
