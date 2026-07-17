(ns seon.derive
  "Pure transformations and asynchronous database-derived projections.

   The agent's derived state, turn counts, and the
   armable-agent ID query were each re-implemented 5+ times across `seon.agent`,
   `seon.agent.ctx`, `seon.render.default`, `seon.agent.run`, and
   `seon.agent.schedule`, every copy justified by dodging the
   `agent → ctx → render` require cycle. A pure `(derive-x db id)` needs
   NOTHING from those namespaces — only `seon.db` to read and `seon.schema`
   to name the shapes — so it sits BELOW all of them and the cycle evaporates.
   Every consumer requires THIS ns, passes the database value it already
   holds, and awaits database-backed projections.

   The ONE state rule is [[state-from-primitives]] (terminated → :idle →
   :paused → :running); [[derive-state]] reads the primitives off a db and
   applies it; [[armable-agent-ids]] / [[agent-idle?]] are FILTERS over it,
   never re-encodings — so the rule cannot drift.

   Dependency direction (acyclic): requires ONLY `seon.db` + `seon.schema`.
   It MUST NOT require `seon.agent`, `seon.agent.ctx`, `seon.render.*`, or
   `seon.agent.loop` — that is the whole point."
  (:require
    [seon.db :as db]
    [seon.schema :as schema]))

;; ============================================================
;; The derived state enum + the ONE projection rule. State is DERIVED from
;; primitives, never stored: presence of `:seon.agent/terminated-at` ⇒
;; :terminated; no open run ⇒ :idle; an open run carrying
;; `:seon.agent.run/paused-at` ⇒ :paused; else :running.
;; ============================================================

(schema/register! :seon.derive/state
  [:enum :idle :running :paused :terminated])

(schema/register! :seon.derive/agent-state-read-attrs
  [:set :qualified-keyword])

(def agent-state-read-attrs
  "Stored attributes read by [[derive-state]].

   Reactive consumers use this one colocated dependency definition instead of
   maintaining UI-specific guesses about what can change an agent's derived
   state. The state itself remains a pure projection of a database value; this
   is only its immutable read-set, never cached status."
  #{:seon.agent/id
    :seon.agent/run
    :seon.agent/terminated-at
    :seon.agent.run/status
    :seon.agent.run/paused-at})

;; The three primitives `state-from-primitives` projects. Keys carry the real
;; attr names; their VALUE schemas are base types so this shape has no
;; load-time dependency on the attr registrations in seon.agent /
;; seon.agent.run. `open?` is "is there an open run".
(schema/register! :seon.derive/primitives
  [:map
   [:seon.agent/terminated-at {:optional true} :inst]
   [:seon.agent.run/open?     {:optional true} :boolean]
   [:seon.agent.run/paused-at {:optional true} :inst]])

(defn state-from-primitives
  "THE state rule (pure): project primitives onto the derived state.

   The caller reads the primitives from a db and hands them in;
   `:seon.agent/terminated-at` present ⇒ :terminated; no open run ⇒ :idle; an
   open run with `:seon.agent.run/paused-at` ⇒ :paused; else :running."
  {:malli/schema [:=> [:catn [:seon.derive/primitives :seon.derive/primitives]]
                  :seon.derive/state]}
  [{:seon.agent/keys [terminated-at]
    open?     :seon.agent.run/open?
    paused-at :seon.agent.run/paused-at}]
  (cond
    terminated-at :terminated
    (not open?)   :idle
    paused-at     :paused
    :else         :running))

;; ============================================================
;; Database-backed projections are async. They remain referentially transparent
;; over the explicit immutable database value, while the JVM owns every read.
;; ============================================================

(schema/register! ::direct-error
  [:map [:seon.error/message :string]])

(defn- error-value? [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn- primitives-from-agent [agent]
  (let [run (:seon.agent/run agent)]
    (cond-> {:seon.agent.run/open? (= :open (:seon.agent.run/status run))}
      (:seon.agent/terminated-at agent)
      (assoc :seon.agent/terminated-at (:seon.agent/terminated-at agent))

      (:seon.agent.run/paused-at run)
      (assoc :seon.agent.run/paused-at (:seon.agent.run/paused-at run)))))

(defn ^:async derive-state
  "The agent's DERIVED FSM state over the db value `db`.

   One of :idle/:running/:paused/:terminated. Reads `terminated-at`,
   whether it has an OPEN run, and that run's `paused-at`, then applies
   [[state-from-primitives]]. The ONE reader the readline / web UI / loop /
   wake gate share."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db]
                             [:seon.agent/id :seon.agent/id]]
                  [:or :seon.derive/state ::direct-error]]}
  [database agent-id]
  (let [agent (await
               (db/pull
                {:seon.db/db database
                 :seon.db/selector
                 [:seon.agent/terminated-at
                  {:seon.agent/run
                   [:seon.agent.run/status :seon.agent.run/paused-at]}]
                 :seon.db/eid [:seon.agent/id agent-id]}))]
    (if (error-value? agent)
      agent
      (state-from-primitives (primitives-from-agent agent)))))

(defn ^:async run-form-count
  "How many FORMS (persisted evals) ran under run `run-id`.

   The repl-mode `:stream` work budget: a stream turn evals at most one
   form, so counting TURNS makes the cap a form budget that prose and
   orientation turns burn for nothing (repl-milestone rung-0 verdict, 2026-07-10) —
   under `:stream` the loop bounds work by THIS count instead. Joins
   `:seon.agent.turn/run` → `:seon.agent.turn/evals` over the explicit
   db value; excludes schedule-fire turns."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db]
                             [:seon.agent.run/id :seon.agent.run/id]]
                  [:or :int ::direct-error]]}
  [database run-id]
  (let [result
        (await
         (db/query {:seon.db/db database
                    :seon.db/query
                    '[:find (count ?e) . :in $ ?rid
                      :where
                      [?r :seon.agent.run/id ?rid]
                      [?t :seon.agent.turn/run ?r]
                      (not [?t :seon.agent.turn/scheduled? true])
                      [?t :seon.agent.turn/evals ?e]]
                    :seon.db/args [run-id]}))]
    (if (error-value? result) result (or result 0))))

(defn ^:async agent-turn-count
  "Turn count for an agent across ALL its runs.

   agent ← run
   (`:seon.agent.run/agent`) ← turn (`:seon.agent.turn/run`), counted over the
   explicit db value. Query-based (not reverse-ref nav) so it works on a plain
   pulled/touched agent map and on a FilteredDB."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db]
                             [:seon.agent/id :seon.agent/id]]
                  [:or :int ::direct-error]]}
  [database agent-id]
  (let [result
        (await
         (db/query {:seon.db/db database
                    :seon.db/query
                    '[:find (count ?t) . :in $ ?aid
                      :where
                      [?a :seon.agent/id ?aid]
                      [?r :seon.agent.run/agent ?a]
                      [?t :seon.agent.turn/run ?r]]
                    :seon.db/args [agent-id]}))]
    (if (error-value? result) result (or result 0))))

(defn ^:async last-beat
  "The run's heartbeat instant over `db`, or nil.

   `:seon.agent.run/last-beat-at`; nil when the run never beat / doesn't resolve."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db]
                             [:seon.agent.run/id :seon.agent.run/id]]
                  [:or [:maybe :inst] ::direct-error]]}
  [database run-id]
  (let [run (await
             (db/pull {:seon.db/db database
                       :seon.db/selector [:seon.agent.run/last-beat-at]
                       :seon.db/eid [:seon.agent.run/id run-id]}))]
    (if (error-value? run) run (:seon.agent.run/last-beat-at run))))

(defn ^:async agent-idle?
  "Is the agent DERIVED `:idle` (wakeable)?

   Not terminated, no open run. A
   FILTER over [[derive-state]], never a re-encoding of the rule."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db]
                             [:seon.agent/id :seon.agent/id]]
                  [:or :boolean ::direct-error]]}
  [database agent-id]
  (let [state (await (derive-state database agent-id))]
    (if (error-value? state) state (= :idle state))))

(defn ^:async armable-agent-ids
  "Born agent ids whose derived state is `:idle`.

   These are the agents a trigger can wake. Birth is the presence of the
   durable `:seon.eval/home-requires` fact;
   an identity-only lookup target such as provenance genesis's reserved root
   stub is not yet a runnable agent. A FILTER over [[derive-state]] (one rule,
   never a re-encoded idle query), sorted asc for deterministic boot logs."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db]]
                  [:or [:vector :seon.agent/id] ::direct-error]]}
  [database]
  (let [rows
        (await
         (db/query
          {:seon.db/db database
           :seon.db/query
           '[:find ?id
                    (pull ?agent
                          [:seon.agent/terminated-at
                           {:seon.agent/run
                            [:seon.agent.run/status
                             :seon.agent.run/paused-at]}])
             :where
             [?agent :seon.agent/id ?id]
             [?agent :seon.eval/home-requires _]]}))]
    (if (error-value? rows)
      rows
      (->> rows
           (keep (fn [[id agent]]
                   (when (= :idle
                            (state-from-primitives (primitives-from-agent agent)))
                     id)))
           sort
           vec))))

(def resumable-agent-ids-query
  "Born, nonterminated process hosts used by resume and runtime discovery."
  '[:find [?id ...]
    :where
    [?a :seon.agent/id ?id]
    [?a :seon.eval/home-requires _]
    (not [?a :seon.agent/terminated-at _])])

(defn ^:async resumable-agent-ids
  "Born agent ids whose derived state is not `:terminated`.

   These are the agent IDs this process must host, distinct from [[armable-agent-ids]]: a
   running or paused agent still needs its transient namespace, loop input, and
   listener reconstructed after process start or code reload. Identity-only
   lookup targets are not hosts; birth requires `:seon.eval/home-requires`.
   Among born agents the only exclusion is the durable termination fact."
  {:malli/schema
   [:=> [:catn [:seon.db/db :seon.db/db]]
    [:or [:vector :seon.agent/id] ::direct-error]]}
  [database]
  (let [result
        (await
         (db/query {:seon.db/db database
                    :seon.db/query resumable-agent-ids-query}))]
    (if (error-value? result) result (->> result sort vec))))

;; ============================================================
;; Schedule-wake circuit breaker (multi-agent-context Piece 2d) — a derived
;; crash-loop guard. With no auto-rewake, the ONE autonomous repeat-wake source
;; is schedules; a deterministic wedge + a periodic schedule is a slow crash
;; loop. `recent-crash-count` windows over the STORED `:seon.agent.run/closed-at`
;; instant (NOT `:db/txInstant`, which can't be backdated in tests);
;; `schedule-breaker-tripped?` compares it to a config-supplied N. Pure over
;; (db, now, dials) — nothing stored, so the window sliding past re-enables
;; schedules on its own (worst case one wedge per window while the cause lasts).
;; ============================================================

(defn ^:async recent-crash-count
  "Count of this agent's runs closed `:crashed` at/after `since` over `db`.

   Windows over the stored `:seon.agent.run/closed-at` instant (`:db/txInstant`
   can't be backdated, so a stored close instant is what a test windows over).
   A pure count — the breaker's state IS the run log, nothing stored."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db]
                             [:seon.agent/id :seon.agent/id]
                             [:since :inst]]
                  [:or :int ::direct-error]]}
  [database agent-id since]
  (let [result
        (await
         (db/query {:seon.db/db database
                    :seon.db/query
                    '[:find [?at ...] :in $ ?aid
                      :where
                      [?a :seon.agent/id ?aid]
                      [?r :seon.agent.run/agent ?a]
                      [?r :seon.agent.run/closed-reason :crashed]
                      [?r :seon.agent.run/closed-at ?at]]
                    :seon.db/args [agent-id]}))]
    (if (error-value? result)
      result
      (->> result
           (filter #(>= (.getTime ^js %) (.getTime ^js since)))
           count))))

(defn ^:async schedule-breaker-tripped?
  "Is the schedule-wake circuit breaker TRIPPED for this agent at `now`?

   True when ≥`n` of its runs closed `:crashed` within the last `window-ms`
   (via [[recent-crash-count]]). Pure over (db, now, dials) — the caller
   supplies the config-dialed N + window; nothing stored. Human/agent MESSAGES
   still wake a tripped agent — only SCHEDULE wakes are refused (the caller
   gates on this)."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db]
                             [:seon.agent/id :seon.agent/id]
                             [:seon.agent/now :inst]
                             [:seon.config.breaker/crash-count :int]
                             [:seon.config.breaker/window-ms :int]]
                  [:or :boolean ::direct-error]]}
  [database agent-id now n window-ms]
  (let [crashes (await
                 (recent-crash-count
                  database agent-id
                  (js/Date. (- (.getTime ^js now) window-ms))))]
    (if (error-value? crashes) crashes (>= crashes n))))

;; ============================================================
;; The agent status is one asynchronous projection over one immutable database
;; value. Every member remains an ordinary Datahike read owned by the JVM.
;; ============================================================

(defn ^:async ^:private open-step-count
  "Count of the agent's unfinished LEAF steps — the real work still owed.
   With `my.plan` trees, milestone PARENTS stay stored `:open` (their
   done-ness is DERIVED — done once every child is), so a flat status count
   over-counts them. Counting only leaves (no child names it as parent — the
   `leaf` predicate from `my.plan.internal/rules`, inlined to keep this ns's
   acyclic seon.db/seon.schema-only dependency) yields the actionable work.
   :active and :blocked leaves are still owed, so they count (use `next` for
   the READY-only focus queue)."
  [database id]
  (let [result
        (await
         (db/query {:seon.db/db database
                    :seon.db/query
                    '[:find (count ?step) . :in $ ?aid
                      :where
                      [?a :seon.agent/id ?aid]
                      [?step :my.plan/agent ?a]
                      [?step :my.plan/status ?s]
                      [(!= ?s :done)]
                      (not-join [?step] [?child :my.plan/parent ?step])]
                    :seon.db/args [id]}))]
    (if (error-value? result) result (or result 0))))

(defn ^:async ^:private last-human-inbound-at
  "The `:at` of the latest inbound `:human`-origin message to the agent, or
   nil when none."
  [database id]
  (let [result
        (await
         (db/query {:seon.db/db database
                    :seon.db/query
                    '[:find (max ?at) . :in $ ?aid
                      :where
                      [?agent :seon.agent/id ?aid]
                      [?message :seon.agent.message/to ?agent]
                      [?message :seon.agent.message/origin :human]
                      [?message :seon.agent.message/at ?at]]
                    :seon.db/args [id]}))]
    (if (error-value? result) result result)))

(schema/register! :seon.derive/closed-run
  [:map
   [:seon.agent.run/id :string]
   [:seon.agent.run/started-at :inst]
   [:seon.agent.run/closed-reason :keyword]
   [:seon.agent.run/closed-at {:optional true} :inst]
   [:seon.agent.run/result {:optional true} :string]
   [:seon.agent.run/result-ref {:optional true} :seon.db/ref]])

(defn ^:async latest-closed-run
  "The agent's most-recently-started closed run entity, or nil.

   This is the one immutable run-outcome projection used by status, child
   context, and root's fleet view. It is gated on the run schema being
   installed so old/unseeded database values omit the fact cleanly."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db]
                             [:seon.agent/id :string]]
                  [:or :nil :seon.derive/closed-run ::direct-error]]}
  [database id]
  (let [installed (await (db/installed-schema database))]
    (cond
      (error-value? installed) installed
      (not (contains? installed :seon.agent.run/closed-reason)) nil
      :else
      (let [rows
            (await
             (db/query {:seon.db/db database
                        :seon.db/query
                        '[:find ?run ?started :in $ ?aid
                          :where
                          [?agent :seon.agent/id ?aid]
                          [?run :seon.agent.run/agent ?agent]
                          [?run :seon.agent.run/closed-reason]
                          [?run :seon.agent.run/started-at ?started]]
                        :seon.db/args [id]}))]
        (if (error-value? rows)
          rows
          (when-let [run-eid (some->> rows
                                      (sort-by #(.getTime ^js (second %)))
                                      last
                                      first)]
            (let [run (await
                       (db/pull
                        {:seon.db/db database
                         :seon.db/selector
                         [:seon.agent.run/id :seon.agent.run/started-at
                          :seon.agent.run/closed-reason
                          :seon.agent.run/closed-at :seon.agent.run/result
                          :seon.agent.run/result-ref]
                         :seon.db/eid run-eid}))]
              (if (error-value? run) run run))))))))

(defn ^:async last-closed-reason
  "The `closed-reason` of the agent's latest closed run, or nil."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db]
                             [:seon.agent/id :seon.agent/id]]
                  [:or [:maybe :seon.agent.run/closed-reason] ::direct-error]]}
  [database id]
  (let [run (await (latest-closed-run database id))]
    (if (error-value? run) run (:seon.agent.run/closed-reason run))))

(defn ^:async ^:private open-turn-count [database id]
  (let [result
        (await
         (db/query {:seon.db/db database
                    :seon.db/query
                    '[:find (count ?turn) . :in $ ?aid
                      :where
                      [?agent :seon.agent/id ?aid]
                      [?agent :seon.agent/run ?run]
                      [?turn :seon.agent.turn/run ?run]
                      (not [?turn :seon.agent.turn/scheduled? true])]
                    :seon.db/args [id]}))]
    (if (error-value? result) result (or result 0))))

(schema/register! :seon.derive/status-request
  [:map
   [:seon.db/db {:optional true} :seon.db/db]
   ;; Leaf-purity (see note below): this is a LOAD-TIME register! in a leaf ns
   ;; — seon.agent requires THIS, so `:seon.agent/id` is NOT registered yet at
   ;; cold boot and can't be referenced here. The base type `:string` still
   ;; admits the literal "root" id (the orchestrator-root); the generated
   ;; shape is enforced where the agent is CREATED (`:seon.agent/id` itself).
   ;; This is a derived READ. The defn `:malli/schema` slots below (resolved at
   ;; instrumentation time, after seon.agent loads) DO reference `:seon.agent/id`.
   [:seon.agent/id  :string]
   ;; optional explicit wall-clock for ms-remaining (defaults to (js/Date.))
   [:seon.agent/now {:optional true} :inst]])

;; Leaf-purity: the run/* + message/* field KEYS are the real attr names, but
;; their VALUE schemas are BASE TYPES (not the registered `:seon.agent.run/*`
;; enums) so this register! has NO load-time dependency on seon.agent.run's
;; registrations — run requires THIS leaf, so it loads AFTER. The precise enums
;; are validated where the run is WRITTEN; this is a derived read. (Same rule
;; `:seon.derive/primitives` follows.)
(schema/register! :seon.derive/status
  [:map
   [:seon.agent/state                 :seon.derive/state]   ; DERIVED enum
   [:seon.agent/total-turns           :int]
   [:my.plan/open-count       :int]
   [:seon.agent.run/status        {:optional true} :keyword]   ; :open/:closed
   [:seon.agent.run/trigger       {:optional true} :keyword]   ; :message/:schedule
   [:seon.agent.run/turn-limit    {:optional true} :int]
   [:seon.agent.run/deadline      {:optional true} :inst]
   [:seon.agent.run/last-beat-at  {:optional true} :inst]
   [:seon.agent.run/closed-reason {:optional true} :keyword]   ; closed-reason enum
   [:seon.agent.run/turn            {:optional true} :int]     ; derived current-turn
   [:seon.agent.run/turns-remaining {:optional true} :int]
   [:seon.agent.run/ms-remaining    {:optional true} :int]
   [:seon.agent.message/last-human-at {:optional true} :inst]])

(defn ^:async derive-status
  "The agent's full DERIVED status in one map (map-in / map-out).

   A pure
   DERIVED READ — no writes. State comes from [[state-from-primitives]] over
   the primitives; run/turn/step fields derive from cheap queries against ONE
   threaded database value. Pass `:seon.db/db` to freeze the read; omission
   acquires the current cached value once. Run-scoped fields are present only
   while there IS an open run; `:seon.agent/now` fixes `ms-remaining`."
  {:malli/schema [:=> [:cat :seon.derive/status-request]
                  [:or :seon.derive/status ::direct-error]]}
  [{database :seon.db/db id :seon.agent/id now :seon.agent/now}]
  (let [now (or now (js/Date.))
        database (or database (await (db/db)))]
    (if (error-value? database)
      database
      (let [values
            (await
             (js/Promise.all
              #js [(db/pull
                    {:seon.db/db database
                     :seon.db/selector
                     [:seon.agent/terminated-at
                      {:seon.agent/run
                       [:seon.agent.run/id :seon.agent.run/status
                        :seon.agent.run/trigger :seon.agent.run/turn-limit
                        :seon.agent.run/deadline
                        :seon.agent.run/last-beat-at
                        :seon.agent.run/paused-at
                        :seon.agent.run/remaining-ms]}]
                     :seon.db/eid [:seon.agent/id id]})
                   (agent-turn-count database id)
                   (open-step-count database id)
                   (last-human-inbound-at database id)
                   (last-closed-reason database id)
                   (open-turn-count database id)]))
            [agent total-turns open-count last-human last-closed turn-count]
            (array-seq values)
            failure (first (filter error-value? (array-seq values)))]
        (if failure
          failure
          (let [run (:seon.agent/run agent)
                paused-at (:seon.agent.run/paused-at run)
                state (state-from-primitives (primitives-from-agent agent))
                base (cond-> {:seon.agent/state state
                              :seon.agent/total-turns total-turns
                              :my.plan/open-count open-count}
                       last-human
                       (assoc :seon.agent.message/last-human-at last-human)
                       last-closed
                       (assoc :seon.agent.run/closed-reason last-closed))]
            (if-not (= :open (:seon.agent.run/status run))
              base
              (let [turn-limit (:seon.agent.run/turn-limit run)
                    deadline (:seon.agent.run/deadline run)
                    last-beat (:seon.agent.run/last-beat-at run)]
                (cond->
                 (assoc base
                        :seon.agent.run/status (:seon.agent.run/status run)
                        :seon.agent.run/trigger (:seon.agent.run/trigger run)
                        :seon.agent.run/turn-limit turn-limit
                        :seon.agent.run/deadline deadline
                        :seon.agent.run/turn turn-count
                        :seon.agent.run/turns-remaining
                        (max 0 (- turn-limit turn-count)))
                  last-beat
                  (assoc :seon.agent.run/last-beat-at last-beat)
                  deadline
                  (assoc :seon.agent.run/ms-remaining
                         (if paused-at
                           (or (:seon.agent.run/remaining-ms run) 0)
                           (- (.getTime ^js deadline)
                              (.getTime ^js now)))))))))))))
