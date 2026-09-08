(ns seon.cluster.work
  "What ONE AGENT should do next, derived from one database value.

  This contract layer is fully implemented and live-proven.

  This is a pure work derivation, never a recovery procedure. There is
  no dirty flag, scan-requested atom, or retry counter: the facts hold
  the work, and `next-agent-work` reads them. Boot recovery runs before
  this derivation and closes every interrupted prior-process run.

  FIVE SITUATIONS, TOTAL AND MUTUALLY EXCLUSIVE, `nil` for idle:

  - `:resume` — an open run this process holds, WITH a plan digest.
    Fold from the first ordinal lacking a terminal receipt. This is the
    ordinary live fold, never a cold continuation after recovery;
  - `:call` — an open run this process holds, WITHOUT a plan digest.
    Derive the prompt, make the ONE paid model call, freeze the plan;
  - `:generate` — a system-authored generated run whose current prefix has
    terminal receipts. Derive and append exactly its next dependency-ready
    form; no model call and no stored whole-episode plan;
  - `:open` — an agent with no open run and an unanswered trigger under the
    episode gate. Every closed run answers its trigger, including a refusal;
    only a new trigger can open another run. Open and claim FIRST, model
    second: the busy fence must exist before the expensive part, so a second
    trigger during a model call cannot start a second turn (the claim-early
    half of n3-plan §9.1, which the night ruling kept);
  - `:close` — an open run whose every evaluable form already has a terminal
    receipt. Comment-only input has no reader event and needs no receipt.
    The fold is done. This is its own situation rather than a
    `:resume` carrying no ordinal, because fold-vs-close is a different
    instruction to the turn proc and an instruction must be visible in the
    value, never inferred from an absent key (seal revision,
    2026-07-27);
  - `nil` — idle.

  NO AUTO-RETRY OR COLD RESUME, EVER (owner ruling 25, 2026-07-29).
  Boot recovery closes the interrupted run, releases custody, and
  retracts the agent pointer in one transaction. `:call` and `:resume`
  are therefore reachable only for runs THIS process holds. An open
  unclaimed run is not work — it is wreckage to settle, which is why
  `interruption` exists and why `next-agent-work` does not return it.

  Crash walk (the kill positions of n3-plan §9.3, as this namespace
  answers them):
  - kill after the trigger commits, before any wake: the trigger is
    unanswered, so the boot pass derives `:open`. A normal first turn;
  - kill after opening a run, during the model call, after plan freeze,
    or mid-fold: `recover-tx` marks any running receipt interrupted,
    closes the run, releases custody, and retracts the pointer. No
    unstarted suffix executes. A later unanswered message derives
    `:open` for a new episode, whose context includes the run's derived
    interruption evidence;
  - kill after the last terminal receipt, before close: recovery closes
    the already-finished run;
  - kill during recovery itself: `recover-tx` is idempotent and every
    terminal receipt is byte-untouched, so the derivation is unchanged."
  (:require [clojure.edn :as edn]
            [seon.db :as db]
            [seon.cluster.message :as message]
            [seon.cluster.run :as run]
            [seon.cluster.wake :as wake]
            [seon.schema.edn :as schema.edn]
            [seon.sci.reader :as reader]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Reading the facts
;;; ---------------------------------------------------------------------------

(defn- agent-run
  "The run an agent currently points at on `db`, pulled whole, or nil.
  The agent's pointer IS the open-run fact: N2 retracts it at close, so
  there is no `status` to read and no closed run to filter out here."
  [db agent-id]
  (let [run (db/q '[:find (pull ?run [*]) .
                   :in $ ?agent-id
                   :where
                   [?agent :seon.cluster.agent/id ?agent-id]
                   [?agent :seon.cluster.agent/run ?run]]
                 db agent-id)]
    ;; ASK ONLY ABOUT A RUN THAT EXISTS. `open?` of nothing answered
    ;; "true" (nil contains no closed-at) and the `when` then returned
    ;; nil anyway — right answer, wrong question, and instrumentation
    ;; named it the first time it ran.
    (when (and run (run/open? run)) run)))

(defn- evaluable-source?
  [source]
  (let [events (reader/read {:seon.sci.reader/text source
                             :seon.config.eval.result/max-source (count source)
                             :seon.sci.reader/defer-auto-resolve? true})]
    (or (map? events) (seq events))))

(defn- next-ordinal
  "The first evaluable form with no terminal fact, or nil.
  Resume is a QUERY, never a cursor: an evaluation is terminal when it
  carries a terminal fact — `result-edn`, `:seon.eval/missing`, `error`, or
  `interrupted-at` (the query twin of `run/terminal?`; there is no status to
  read) —
  and `recover-tx` has already stamped a dead process's dangling
  evaluations with `interrupted-at`, so an interrupted form is DONE being
  attempted and the fold moves past it. A comment-only source produces
  zero reader events, so it is durable input but never work. Nothing
  re-executes.

  ONE ENTITY PER (run, ordinal) makes this ONE query: the frozen source
  and the terminal facts are attributes of the same evaluation, so there
  is no second result set to join in Clojure."
  [db run-id]
  (->> (db/q '[:find ?ordinal ?source
               :in $ ?run-id
               :where
               [?run :seon.cluster.run/id ?run-id]
               [?evaluation :seon.cluster.eval/run ?run]
               [?evaluation :seon.cluster.eval/ordinal ?ordinal]
               [?evaluation :seon.cluster.eval/source ?source]
               (not-join [?evaluation]
                         (or [?evaluation :seon.cluster.eval/result-edn _]
                             [?evaluation :seon.eval/missing _]
                             [?evaluation :seon.cluster.eval/error _]
                             [?evaluation
                              :seon.cluster.eval/interrupted-at _]))]
             db run-id)
       (keep (fn [[ordinal source]]
               (when (evaluable-source? source) ordinal)))
       sort
       first))

;;; ---------------------------------------------------------------------------
;;; Routed-problem settlement — derived, never stored
;;; ---------------------------------------------------------------------------

(def ^:private sci-unbound-class "sci.impl.vars.SciUnbound")

(defn unbound-value?
  "True when an admitted value contains sci's structured unbound marker.
  Admission has already bounded the ordinary value, so this walks data only;
  no class object or stringified exception crosses this seam."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [value]
  (boolean
   (some (fn [node]
           (and (map? node)
                (= sci-unbound-class (:seon.sci.admit/opaque node))))
         (tree-seq coll? seq value))))

(defn problem-id
  "The receipt identity naming one form's derived problem."
  {:malli/schema [:=> [:cat :seon.cluster.run/id
                       :seon.cluster.eval/ordinal]
                  :seon.problems/id]}
  [run-id ordinal]
  (run/receipt-identity run-id ordinal))

(defn planner-scoped-attempt?
  "True when `run-id` belongs to a goal's caused-by message chain.

  A planner attempt's recorded run trigger points at one member of the chain:
  either the depth-zero goal message itself or a later caused-by message.
  A triggerless historical run has no membership edge and fails closed."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.run/id]
                  :boolean]}
  [db run-id]
  (some? (message/trigger db run-id)))

(defn- receipt-value
  [receipt]
  (when-let [printed (:seon.cluster.eval/result-edn receipt)]
    (try
      (edn/read-string printed)
      (catch Throwable _
        nil))))

(defn red-receipt?
  "True when a terminal receipt is red: error, interruption, or unbound."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [receipt]
  (boolean
   (or (:seon.cluster.eval/error receipt)
       (:seon.cluster.eval/interrupted-at receipt)
       (unbound-value? (receipt-value receipt)))))

(defn resume-artifact?
  "True when this ordinal's failure belongs to interrupted process history.
  A directly interrupted receipt and every later ordinal after an interrupted
  prefix are excluded from owner routing; neither says owner code is wrong."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.run/id
                       :seon.cluster.eval/ordinal :boolean]
                  :boolean]}
  [db run-id ordinal interrupted?]
  (boolean
   (or interrupted?
       (db/q '[:find ?receipt .
              :in $ ?run-id ?ordinal
              :where
              [?run :seon.cluster.run/id ?run-id]
              [?receipt :seon.cluster.eval/run ?run]
              [?receipt :seon.cluster.eval/ordinal ?prior]
              [(< ?prior ?ordinal)]
              [?receipt :seon.cluster.eval/interrupted-at _]]
            db run-id ordinal))))

(defn form-owner
  "The parse-time namespace owner, or the run author as the total fallback."
  {:malli/schema [:=> [:cat :seon.db/database-value :map]
                  :seon.cluster.agent/id]}
  [db form]
  (let [form-eid (:db/id form)
        namespace-owner
        (when (contains? (:schema db) :seon.cluster.eval/ns)
          (db/q '[:find ?owner-id .
                 :in $ ?form
                 :where
                 [?form :seon.cluster.eval/ns ?namespace]
                 [?owner :seon.cluster.agent/namespace ?namespace]
                 [?owner :seon.cluster.agent/id ?owner-id]]
               db form-eid))]
    (or namespace-owner
        (db/q '[:find ?author-id .
               :in $ ?form
               :where
               [?form :seon.cluster.eval/run ?run]
               [?run :seon.cluster.run/agent ?author]
               [?author :seon.cluster.agent/id ?author-id]]
             db form-eid))))

(defn- terminal-receipt?
  [receipt]
  (boolean
   (and receipt
        (or (:seon.cluster.eval/result-edn receipt)
            (:seon.cluster.eval/error receipt)
            (:seon.cluster.eval/interrupted-at receipt)))))

(defn- form-run-id
  [db form]
  (db/q '[:find ?run-id .
         :in $ ?form
         :where
         [?form :seon.cluster.eval/run ?run]
         [?run :seon.cluster.run/id ?run-id]]
       db (:db/id form)))

(defn- assignment-facts
  [db evaluation owner-id]
  (let [evaluation-eid (:db/id evaluation)
        author-eid
        (db/q '[:find ?author .
               :in $ ?form
               :where
               [?form :seon.cluster.eval/run ?run]
               [?run :seon.cluster.run/agent ?author]]
             db evaluation-eid)
        owner-eid
        (db/q '[:find ?owner .
               :in $ ?owner-id
               :where [?owner :seon.cluster.agent/id ?owner-id]]
             db owner-id)
        assignment?
        (boolean
         (and evaluation-eid owner-eid author-eid
              (db/q '[:find ?assignment .
                     :in $ ?problem ?author ?owner
                     :where
                     [?assignment :seon.cluster.message/about ?problem]
                     [?assignment :seon.cluster.message/from ?author]
                     [?assignment :seon.cluster.message/to ?owner]]
                   db evaluation-eid author-eid owner-eid)))
        declination?
        (boolean
         (and assignment?
              (db/q '[:find ?declination .
                     :in $ ?problem ?author ?owner
                     :where
                     [?declination :seon.cluster.message/about ?problem]
                     [?declination :seon.cluster.message/from ?owner]
                     [?declination :seon.cluster.message/to ?author]
                     [?declination :my.message/reason _]]
                   db evaluation-eid author-eid owner-eid)))]
    {:seon.cluster.work/assignment? assignment?
     :seon.cluster.work/declination? declination?}))

(defn form-settlement
  "One evaluation's exactly-one derived state at this database value.

  ONE ENTITY PER (run, ordinal): the frozen source and the terminal facts
  are the same entity, so `:unevaluated` is the absence of a start instant
  and `:running` is a started evaluation with no terminal fact. There is no
  twin to join and no pair that can disagree."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.eval/id]
                  :seon.cluster.work/form-settlement]}
  [db form-id]
  (let [evaluation (db/pull db '[*] [:seon.cluster.eval/id form-id])
        owner-id (form-owner db evaluation)
        {:seon.cluster.work/keys [assignment? declination?]}
        (assignment-facts db evaluation owner-id)
        started? (some? (:seon.cluster.eval/at evaluation))
        red? (and (terminal-receipt? evaluation) (red-receipt? evaluation))
        artifact? (and red?
                       (resume-artifact?
                        db
                        (form-run-id db evaluation)
                        (:seon.cluster.eval/ordinal evaluation)
                        (boolean (:seon.cluster.eval/interrupted-at
                                  evaluation))))
        [state settled?]
        (cond
          (not started?) [:unevaluated false]
          (not (terminal-receipt? evaluation)) [:running false]
          declination? [:owner-declared-cant true]
          artifact? [:unrouted-red false]
          (and red? assignment?) [:routed false]
          red? [:unrouted-red false]
          assignment? [:owner-fixed true]
          :else [:succeeded true])]
    (cond-> {:seon.cluster.eval/id (:seon.cluster.eval/id evaluation)
             :seon.cluster.eval/ordinal
             (:seon.cluster.eval/ordinal evaluation)
             :seon.cluster.agent/id owner-id
             :seon.cluster.work/form-state state
             :seon.cluster.work/settled? settled?}
      (:seon.problems/id evaluation)
      (assoc :seon.problems/id (:seon.problems/id evaluation)))))

(defn plan-settlement
  "Every form state and whether all forms of `run-id` are settled."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.run/id]
                  :seon.cluster.work/plan-settlement]}
  [db run-id]
  (let [form-ids
        (db/q '[:find ?form-id ?ordinal
               :in $ ?run-id
               :where
               [?run :seon.cluster.run/id ?run-id]
               [?form :seon.cluster.eval/run ?run]
               [?form :seon.cluster.eval/id ?form-id]
               [?form :seon.cluster.eval/ordinal ?ordinal]]
             db run-id)
        forms (mapv (fn [[form-id _]] (form-settlement db form-id))
                    (sort-by second form-ids))]
    {:seon.cluster.run/id run-id
     :seon.cluster.work/forms forms
     :seon.cluster.work/settled?
     (every? :seon.cluster.work/settled? forms)}))

;;; ---------------------------------------------------------------------------
;;; The derivations
;;; ---------------------------------------------------------------------------

(declare unanswered-wakes)

;;; ---------------------------------------------------------------------------
;;; The turn bound — derived from transaction :t, with zero stored counters
;;; ---------------------------------------------------------------------------

(defn- inside-wakes
  "Of `wakes`, those the population itself caused, by entity id.

  INSIDE-NESS IS DECLARED, NOT INFERRED. An attribute carrying
  `:seon.wake/inside true` marks any wake entity bearing it as the
  population's own activity: an agent-sent message
  (`:seon.cluster.message/from`), the error recorder's notification
  (`:seon.cluster.message/about`), a fault routed for repair
  (`:seon.error/steward`), an effect the agent requested
  (`:seon.effect/to`). The `from`-or-`about` pair this replaces was a
  hand list inside this namespace; each family now owns its own
  declaration."
  [db wakes]
  (let [ids (into #{} (map :db/id) wakes)]
    (if (empty? ids)
      #{}
      (into #{}
            (db/q '[:find [?wake ...]
                    :in $ [?wake ...] [?inside ...]
                    :where
                    [?wake ?inside _]]
                  db ids (wake/inside-attributes db))))))

(defn outside-wake-t
  "The transaction `:t` of the agent's latest wake from OUTSIDE it, or 0.

  The anchor of the turn bound. A human message and a schedule firing
  are outside; an agent-sent message, a fault routed to its steward, and
  an agent's own settled effect are inside (`inside-wakes`). Zero when
  the agent has never been woken from outside, which counts EVERY turn:
  all of them are autonomous continuation, and a free pass here would
  void the bound for exactly the agent-spawned agents it most concerns."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.agent/id]
                  [:int {:min 0}]]}
  [db agent-id]
  (let [wakes (unanswered-wakes db agent-id {:seon.cluster.work/answered? :any})
        inside (inside-wakes db wakes)]
    (reduce max 0
            (keep (fn [wake]
                    (when-not (contains? inside (:db/id wake))
                      (:seon.wake/t wake)))
                  wakes))))

(defn episode-runs
  "The agent's turns taken since its latest wake from outside itself.

  DERIVED FROM `:t` AND NOTHING ELSE. Datahike stamps every datom with
  its transaction, so a turn's own identity datom carries the basis it
  projected from and a wake carries the moment it arrived. The count is
  the turns whose own `:t` is at or after the anchor `outside-wake-t`
  returns — no stored counter, no episode entity, and no reset code,
  because an outside wake arriving IS the reset.

  Measured at 0.115 ms against 0.134 ms for the run-trigger derivation
  it replaces, same answer (prototype claim 4). The anchor moved: this
  refills the bound the moment an outside wake ARRIVES, where the trigger
  derivation refilled when one was ANSWERED."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.agent/id]
                  :seon.cluster.work/episode-runs]}
  [db agent-id]
  (or (db/q '[:find (count ?run) .
              :in $ ?agent-id ?since
              :where
              [?agent :seon.cluster.agent/id ?agent-id]
              [?run :seon.cluster.run/agent ?agent]
              [?run :seon.cluster.run/id _ ?tx]
              [(>= ?tx ?since)]]
            db agent-id (outside-wake-t db agent-id))
      0))

(defn- max-episode-runs
  "The episode dial, read from the config singleton on this database
  value — a config fact like every other dial, so a live change applies
  at the very next pass. Nil when absent, and absence is FAIL-CLOSED
  for agent-sent triggers (the `max-chain` precedent)."
  [db]
  (db/q '[:find ?value .
         :where [_ :seon.config.run/max-episode-runs ?value]]
       db))

(defn- episode-capped?
  "True when only OUTSIDE wakes may open a turn for `agent-id`: the
  turn count has reached the dial, or the dial is absent
  (fail-closed)."
  [db agent-id]
  (let [limit (max-episode-runs db)]
    (or (nil? limit)
        (>= (episode-runs db agent-id) limit))))

(defn- openable-wakes
  "The unanswered wakes `agent-id`'s next turn answers, under the bound.

  A TURN OPENS FOR ALL OF THEM AT ONCE. Answeredness is the turn's own
  `:t`, so every wake at or before it is answered by the one turn whose
  context contained them — two wakes in one transaction are one paid
  call, where selecting one at a time paid twice (prototype 1c, measured
  on live data).

  Below the cap: every unanswered turn-opening wake. AT the cap (or with
  the dial absent): only OUTSIDE wakes can open, so an agent in a loop
  still hears a human — and an older deferred inside wake is SKIPPED
  rather than blocking selection, which is the cap-hit deadlock the F1
  seal corrected."
  [db agent-id]
  (let [wakes (unanswered-wakes db agent-id {})]
    (if (episode-capped? db agent-id)
      (let [inside (inside-wakes db wakes)]
        (into [] (remove #(contains? inside (:db/id %))) wakes))
      wakes)))

(defn deferred-triggers
  "The unanswered wakes the turn bound is deferring, oldest first.
  Non-empty exactly while the agent is at the cap (or the dial is
  absent) AND inside wakes are pending. PRESENCE, no stored
  anything: the refusal wrote nothing, so this derivation is the whole
  deferred state — the next outside wake refills the bound and this
  derives to empty."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.agent/id]
                  [:vector [:map [:seon.cluster.message/id
                                  :seon.cluster.message/id]]]]}
  [db agent-id]
  (if (episode-capped? db agent-id)
    (let [wakes (unanswered-wakes db agent-id {})
          inside (inside-wakes db wakes)]
      (into []
            (comp (filter #(contains? inside (:db/id %)))
                  (keep :seon.cluster.message/id)
                  (map (fn [id] {:seon.cluster.message/id id})))
            wakes))
    []))

(defn- fold-or-close
  "The instruction for a planned run: fold on, or close it.
  One place decides, so `:resume` always carries a real ordinal and
  `:close` never carries one."
  [db run agent-id]
  (let [run-id (:seon.cluster.run/id run)]
    (if-let [ordinal (next-ordinal db run-id)]
      {:seon.cluster.work/situation :resume
       :seon.cluster.run/id run-id
       :seon.cluster.agent/id agent-id
       :seon.cluster.eval/ordinal ordinal}
      {:seon.cluster.work/situation :close
       :seon.cluster.run/id run-id
       :seon.cluster.agent/id agent-id})))

(defn- resume-or-generate
  [db run agent-id]
  (let [run-id (:seon.cluster.run/id run)]
    (if-let [ordinal (next-ordinal db run-id)]
      {:seon.cluster.work/situation :resume
       :seon.cluster.run/id run-id
       :seon.cluster.agent/id agent-id
       :seon.cluster.eval/ordinal ordinal}
      {:seon.cluster.work/situation :generate
       :seon.cluster.run/id run-id
       :seon.cluster.agent/id agent-id})))

(defn next-agent-work
  "The ONE thing to do next for `agent-id` on `db`, or nil when idle.
  Pure — the per-agent derivation every turn proc runs (F1 §5.2). The
  situations are ordered by what is already committed, not by
  preference: a held run outranks a trigger, because finishing what is
  started is what makes the busy fence mean anything.
  `:resume` carries the ordinal the fold restarts at — the first form
  ordinal with no terminal receipt — so a turn never recomputes it;
  when no such ordinal remains the situation is `:close`. With no open
  run, the `:open` arm selects an unanswered trigger under the episode
  gate. A closed run already answers its trigger, including when that run
  closed on a refusal; answered triggers never derive work again. A deferred
  trigger simply derives no work — no consumer ever sees a decision to
  refuse."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.work/agent-request]
                  [:maybe :seon.cluster.work/next]]}
  [db {:keys [:seon.cluster.agent/id :seon.cluster.run/process]}]
  (let [agent-id id
        run (agent-run db agent-id)]
    (cond
      ;; a run this process holds outranks any trigger: finishing what
      ;; is started is what makes the busy fence mean anything
      (and run (= process (:seon.cluster.run/process run)))
      (cond
        (:seon.cluster.run/plan-digest run)
        (fold-or-close db run agent-id)

        (= :generate (:seon.cluster.work/situation run))
        (resume-or-generate db run agent-id)

        (= :call (:seon.cluster.work/situation run))
        {:seon.cluster.work/situation :call
         :seon.cluster.run/id (:seon.cluster.run/id run)
         :seon.cluster.agent/id agent-id}

        :else nil)

      ;; An unheld run is interruption wreckage, never work. Boot
      ;; recovery normally closes it before any graph is armed; keeping
      ;; this derivation total prevents a fabricated or in-process
      ;; orphan from becoming a cold resume.
      (some? run) nil

      :else
      ;; ONE TURN FOR EVERY UNANSWERED WAKE. The turn's own transaction
      ;; answers all of them, so nothing is selected and nothing is
      ;; claimed; the wakes are named only so a consumer can say what it
      ;; is about to answer.
      (let [wakes (openable-wakes db agent-id)]
        (when (seq wakes)
          (cond->
           {:seon.cluster.work/situation :open
            :seon.cluster.agent/id agent-id}
            (some :seon.cluster.message/id wakes)
            (assoc :seon.cluster.message/id
                   (some :seon.cluster.message/id wakes))))))))

(defn more-agent-work?
  "True when another pass would find work for this agent.

  The turn proc's self-rewake predicate — exactly
  `(some? (next-agent-work db request))`, stated as its own contract
  because the rewake must never drift from the derivation it rewakes
  for."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.work/agent-request]
                  :boolean]}
  [db request]
  (some? (next-agent-work db request)))

(defn interruption
  "The open, unclaimed run of `agent-id` on `db`, or nil.
  Planned or unplanned, an unheld run is not work and never cold
  resumes. Boot recovery normally closes prior-process runs before any
  graph is armed; this derivation keeps the same rule total for
  in-process wreckage. Returned separately from `next-agent-work`
  because it is not work — the difference between `continue this` and
  `bury this` must be visible in the value, not in a flag.

  AGENT-SCOPED AND ALWAYS WAS: each turn proc settles its OWN orphan
  before deriving, and the armer's arm-prime pass covers an agent with
  no graph yet. The global plural died with the central pass (F2)."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.agent/id]
                  [:maybe [:map [:seon.cluster.run/id :seon.cluster.run/id]]]]}
  [db agent-id]
  (let [run (agent-run db agent-id)]
    (when (and run
               (nil? (:seon.cluster.run/process run)))
      {:seon.cluster.run/id (:seon.cluster.run/id run)})))

(defn latest-turn-t
  "The transaction `:t` of the newest turn `agent-id` has opened, or 0.

  A turn's own identity datom carries the `:t` of the transaction that
  opened it, and that transaction's database value IS what the turn's
  context projected from (`run/opening-db` derives the same `:t` from
  the `opened-at` datom and hands it to `as-of`). So the basis is not
  stored: Datahike already stamps it, and a stored copy was measured off
  by one on every turn — swallowing a wake transacted with the turn that
  answered it (prototype 0b.1)."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.agent/id]
                  [:int {:min 0}]]}
  [db agent-id]
  (or (db/q '[:find (max ?tx) .
              :in $ ?agent-id
              :where
              [?agent :seon.cluster.agent/id ?agent-id]
              [?run :seon.cluster.run/agent ?agent]
              [?run :seon.cluster.run/id _ ?tx]]
            db agent-id)
      0))

(defn unanswered-wakes
  "The agent's wakes no turn has answered, oldest first.

  ANSWERED IS DERIVED FROM `:t`, AND NOTHING IS STORED. A wake is a
  datom on an attribute declared `:seon.wake/listen true` whose value is
  this agent; every datom carries its transaction, and a turn's own
  transaction is the basis its context projected from. So a wake is
  answered exactly when a turn of that agent has `:t` at or after it —
  no reference from turn to wake, no claim, no per-wake write, and no
  way for a turn to answer something its context never contained.

  Two consequences the trigger reference could not express: two wakes in
  ONE transaction share a `:t` and are answered by ONE turn (they were
  paid for twice), and a wake asserted DURING a turn has `:t` greater
  than that turn's and opens the next one.

  `:seon.cluster.work/answered?` `:any` includes answered wakes — what
  the turn-bound anchor needs; absent means unanswered only. By default
  only attributes declared `:seon.wake/opens-turn? true` are considered.

  The rule the declarations state and every writer keeps: a wake datom
  is asserted ONCE and never retracted-and-reasserted, because a
  reassertion moves its `:t` forward and re-opens a paid turn."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.agent/id
                       :seon.cluster.work/wake-request]
                  [:vector :seon.wake/unanswered]]}
  [db agent-id {answered? :seon.cluster.work/answered?}]
  (let [since (if (= :any answered?) -1 (latest-turn-t db agent-id))]
    (->> (db/q '[:find ?wake ?attribute ?tx
                 :in $ ?agent-id ?since [?attribute ...]
                 :where
                 [?agent :seon.cluster.agent/id ?agent-id]
                 [?wake ?attribute ?agent ?tx]
                 [(> ?tx ?since)]]
               db agent-id since
               (wake/turn-opening-attributes db))
         (sort-by (fn [[wake _attribute tx]] [tx wake]))
         (mapv (fn [[wake attribute tx]]
                 (let [pulled (db/pull db [:seon.cluster.message/id
                                           :seon.cluster.message/at
                                           :seon.cluster.message/ordinal]
                                       wake)]
                   (cond-> {:db/id wake
                            :seon.wake/attribute attribute
                            :seon.wake/t tx}
                     (:seon.cluster.message/id pulled)
                     (assoc :seon.cluster.message/id
                            (:seon.cluster.message/id pulled))
                     (:seon.cluster.message/at pulled)
                     (assoc :seon.cluster.message/at
                            (:seon.cluster.message/at pulled))
                     (:seon.cluster.message/ordinal pulled)
                     (assoc :seon.cluster.message/ordinal
                            (:seon.cluster.message/ordinal pulled)))))))))

(defn unanswered-triggers
  "The agent's unanswered MESSAGE wakes, oldest first.

  A projection of `unanswered-wakes` onto the message family, not a
  second derivation: answeredness is decided in exactly one place, by
  `:t`. It survives under this name for the callers that ask about
  messages specifically — the unread count, the concurrency proofs, the
  page."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.agent/id]
                  [:vector [:map [:seon.cluster.message/id
                                  :seon.cluster.message/id]]]]}
  [db agent-id]
  (->> (unanswered-wakes db agent-id {})
       (filter #(= :seon.cluster.message/to (:seon.wake/attribute %)))
       ;; ORDERED BY THE MESSAGE'S OWN `at`, not by commit order: the
       ;; fact carries the time, and a message written later can be
       ;; older. `unanswered-wakes` orders by `:t` because that is the
       ;; only time a wake in general has; the message family orders by
       ;; the one it declares.
       (sort-by (juxt #(inst-ms (:seon.cluster.message/at %))
                      :seon.wake/t
                      #(or (:seon.cluster.message/ordinal %) 0)
                      :db/id))
       (mapv #(select-keys % [:seon.cluster.message/id
                              :seon.cluster.message/at]))))
