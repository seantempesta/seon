(ns seon.cluster.work
  "Derive one agent's next operation from one database value.

  The open turn follows its agent ref and absence of closed-at. No pointer
  or counter is stored on the agent. Legacy process custody and generated
  turns remain until the turn PRD implementation replaces those paths."
  (:require [clojure.edn :as edn]
            [seon.db :as db]
            [seon.ai :as ai]
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
  "The agent's open turn, derived from its owning ref and closed-at."
  [database agent-id]
  (when-let [id (run/open-for-agent database [:seon.cluster.agent/id agent-id])]
    (db/pull database '[*] [:seon.cluster.run/id id])))

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
                         (or [?evaluation :seon.eval/value _]
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
  (when-let [printed (:seon.eval/value receipt)]
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
        (or (:seon.eval/value receipt)
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

(defn- agent-eid
  "The entity id of `agent-id` on `db`, or nil.
  The one lookup the wake seeks need: every listened attribute is a ref
  whose value IS this entity, so the index seek is (attribute, value)."
  [db agent-id]
  (db/q '[:find ?agent .
          :in $ ?agent-id
          :where [?agent :seon.cluster.agent/id ?agent-id]]
        db agent-id))

(defn- wake-attribute-set
  "The listened attributes one derivation binds.
  `:opening` — only those declared `:seon.wake/opens-turn? true`, which
  is what may cause a model call. `:listened` — every listened
  attribute, which is what a CONTEXT shows: a wake declared
  `opens-turn? false` (a schedule firing) must still reach the agent's
  next context and must still be able to anchor the turn bound. Binding
  the opening set in both modes made every firing invisible to every
  derivation — declared, routed, and consumed by nothing (verifier
  blocker 2)."
  [db attributes]
  (if (= :listened attributes)
    (wake/wake-attributes db)
    (wake/turn-opening-attributes db)))

(defn outside-wake-t
  "The transaction `:t` of the agent's latest wake from OUTSIDE it, or 0.

  The anchor of the turn bound. A human message and a schedule firing
  are outside; an agent-sent message, a fault routed to its steward, and
  an agent's own settled effect are inside. Zero when the agent has
  never been woken from outside, which counts EVERY turn: all of them
  are autonomous continuation, and a free pass here would void the bound
  for exactly the agent-spawned agents it most concerns.

  O(THE AGENT'S OWN WAKES), IN INDEX SEEKS. It walks
  `wake/agent-wake-datoms` — one `:avet` seek per listened attribute,
  newest first — and asks whether an entity is inside only for a
  candidate that would actually raise the answer, so the ordinary cost
  is one `:eavt` seek. The derivation this replaces asked
  `unanswered-wakes` for EVERY wake the agent had ever received and
  pulled each one: 47.5 ms per turn-proc pass at 2,008 lifetime wakes,
  against 0.163 ms for the trigger-anchored form before it, growing
  without bound in the agent's lifetime.

  THE WHOLE LISTENED SET, not the turn-opening one: a firing opens no
  turn and must still be able to refill the bound, which is exactly what
  `:seon.wake/opens-turn? false` means."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.agent/id]
                  [:int {:min 0}]]}
  [db agent-id]
  (if-let [eid (agent-eid db agent-id)]
    (let [inside (wake/inside-attributes db)]
      (reduce
       (fn [best [wake tx _attribute]]
         (if (and (> tx best) (not (wake/inside-wake? db wake inside)))
           tx
           best))
       0
       (wake/agent-wake-datoms db eid (wake-attribute-set db :listened))))
    0))

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
  "Read the agent override, otherwise the branch's config singleton."
  [database agent-id]
  (or (:seon.config.run/max-episode-runs (ai/agent-overlay database agent-id))
      (db/q '[:find ?value .
              :where [?config :seon.config/cluster _]
                     [?config :seon.config.run/max-episode-runs ?value]]
            database)))

(defn- episode-capped?
  "True when `agent-id` may open no turn at all: the turn count has
  reached the dial, the dial is absent, or the wake declarations cannot
  carry a bound.

  FAIL-CLOSED IN BOTH DIRECTIONS. The dial's absence was already
  fail-closed; the DECLARATIONS' absence was fail-open, and on the more
  expensive side — with no attribute declaring `:seon.wake/inside`,
  every wake reads as arriving from outside, the anchor jumps to the
  newest wake, and the bound REFILLS on a paid model loop (measured:
  verify-listened-attributes-2026-09-08 §10). One missing schema
  resource is not a licence to spend."
  [db agent-id]
  (let [limit (max-episode-runs db agent-id)]
    (or (nil? limit)
        (some? (wake/declarations-refusal db))
        (>= (episode-runs db agent-id) limit))))

(defn- openable-wakes
  "The unanswered wakes `agent-id`'s next turn answers, under the bound.

  A TURN OPENS FOR ALL OF THEM AT ONCE. Answeredness is the answering
  turn's own `:t`, so every wake at or before it is answered by the one
  turn whose context contained them — two wakes in one transaction are
  one paid call, where selecting one at a time paid twice (prototype 1c,
  measured on live data).

  AT THE CAP, NOTHING OPENS. The exemption that let an OUTSIDE wake open
  a turn at the cap existed so a human could always reach a looping
  agent; under answered-by-`:t` it is both redundant and unbounded. Any
  message from outside is newer than every turn, so it moves the anchor
  and the count derives to zero — the bound refills by arithmetic, with
  no exemption. Keeping the exemption instead made an unanswered outside
  wake open a turn at the cap forever, which is precisely the paid loop
  a turn whose provider fails now produces."
  [db agent-id]
  (if (episode-capped? db agent-id)
    []
    (unanswered-wakes db agent-id {})))

(defn deferred-triggers
  "The unanswered wakes the turn bound is deferring, oldest first.
  Non-empty exactly while the agent is at the cap (or the dial is
  absent) AND wakes are pending. PRESENCE, no stored anything: the
  refusal wrote nothing, so this derivation is the whole deferred state
  — the next outside wake moves the anchor and this derives to empty."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.agent/id]
                  [:vector [:map [:seon.cluster.message/id
                                  :seon.cluster.message/id]]]]}
  [db agent-id]
  (if (episode-capped? db agent-id)
    (into []
          (comp (keep :seon.cluster.message/id)
                (map (fn [id] {:seon.cluster.message/id id})))
          (unanswered-wakes db agent-id {}))
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

(defn latest-answering-turn-t
  "The `:t` of the newest turn of `agent-id` that ANSWERED, or 0.

  A turn's own identity datom carries the `:t` of the transaction that
  opened it, and that transaction's database value IS what the turn's
  context projected from (`run/opening-db` derives the same `:t` from
  the `opened-at` datom and hands it to `as-of`). So the basis is not
  stored: Datahike already stamps it, and a stored copy was measured off
  by one on every turn — swallowing a wake transacted with the turn that
  answered it (prototype 0b.1).

  ONLY A TURN WHOSE REPLY CAME FROM A MODEL ATTEMPT ANSWERS. The join is
  to a `:seon.ai.attempt` of this turn carrying no
  `:seon.ai.attempt/error` — an attempt row is written for every
  attempt, and the absence of that error ref IS its success
  (`seon.cluster.loop/record-attempt!`). A turn that died before its
  reply, a turn whose every attempt failed, and a source submission
  never showed the wakes to a model, so they answer nothing and the
  wakes open the next turn.

  Joining on the REPLY instead would not say this: a source submission
  stores the submitted text as the run's reply, and the verifier
  measured one silently consuming a pending message
  (verify-listened-attributes-2026-09-08 §2d). What keeps the reopening
  finite is the turn bound, which counts turns TAKEN, answered or not."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.agent/id]
                  [:int {:min 0}]]}
  [db agent-id]
  (or (db/q '[:find (max ?tx) .
              :in $ ?agent-id
              :where
              [?agent :seon.cluster.agent/id ?agent-id]
              [?run :seon.cluster.run/agent ?agent]
              [?run :seon.cluster.run/id _ ?tx]
              [?attempt :seon.ai.attempt/run ?run]
              (not [?attempt :seon.ai.attempt/error _])]
            db agent-id)
      0))

(defn unanswered-wakes
  "The agent's wakes no answering turn has covered, oldest first.

  ANSWERED IS DERIVED FROM `:t`, AND NOTHING IS STORED. A wake is a
  datom on an attribute declared `:seon.wake/listen true` whose value is
  this agent; every datom carries its transaction, and a turn's own
  transaction is the basis its context projected from. So a wake is
  answered exactly when an ANSWERING turn of that agent
  (`latest-answering-turn-t`) has `:t` at or after it — no reference
  from turn to wake, no claim, no per-wake write, and no way for a turn
  to answer something its context never contained, or something no model
  ever saw.

  Two consequences the trigger reference could not express: two wakes in
  ONE transaction share a `:t` and are answered by ONE turn (they were
  paid for twice), and a wake asserted DURING a turn has `:t` greater
  than that turn's and opens the next one.

  TWO REQUEST KEYS, both declared. `:seon.cluster.work/answered?` `:any`
  includes answered wakes; absent means unanswered only.
  `:seon.cluster.work/attributes` `:listened` binds EVERY listened
  attribute — what a context shows — while the default `:opening` binds
  only those that may open a turn. Binding the opening set in both modes
  is what made a schedule firing invisible to every derivation.

  THE SEEK IS THE INDEX. One `:avet` seek per bound attribute through
  `wake/agent-wake-datoms`, filtered on the transaction each datom
  already carries; only the wakes that survive the filter are pulled, so
  the cost follows the agent's PENDING wakes rather than its lifetime
  ones.

  The rule the declarations state and every writer keeps: a wake datom
  is asserted ONCE and never retracted-and-reasserted, because a
  reassertion moves its `:t` forward and re-opens a paid turn."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.agent/id
                       :seon.cluster.work/wake-request]
                  [:vector :seon.wake/unanswered]]}
  [db agent-id {answered? :seon.cluster.work/answered?
                attributes :seon.cluster.work/attributes}]
  (if-let [eid (agent-eid db agent-id)]
    (let [since (if (= :any answered?)
                  -1
                  (latest-answering-turn-t db agent-id))]
      (->> (wake/agent-wake-datoms db eid (wake-attribute-set db attributes))
           (filter (fn [[_wake tx _attribute]] (> tx since)))
           (sort-by (fn [[wake tx _attribute]] [tx wake]))
           (mapv (fn [[wake tx attribute]]
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
                              (:seon.cluster.message/ordinal pulled))))))))
    []))

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
