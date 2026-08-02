(ns seon.cluster.work
  "What ONE AGENT should do next, derived from one database value.

  This contract layer is fully implemented and live-proven.

  This is a pure work derivation, never a recovery procedure. There is
  no dirty flag, scan-requested atom, or retry counter: the facts hold
  the work, and `next-agent-work` reads them. Boot recovery runs before
  this derivation and closes every interrupted prior-process run.

  FOUR SITUATIONS, TOTAL AND MUTUALLY EXCLUSIVE, `nil` for idle:

  - `:resume` — an open run this process holds, WITH a plan digest.
    Fold from the first ordinal lacking a terminal receipt. This is the
    ordinary live fold, never a cold continuation after recovery;
  - `:call` — an open run this process holds, WITHOUT a plan digest.
    Derive the prompt, make the ONE paid model call, freeze the plan;
  - `:open` — an agent with no open run and either a lint-refused latest
    closed turn below the episode cap or an unanswered trigger. A lint
    refusal reuses that turn's trigger identity so its committed findings
    appear in the corrective turn without a new message. Open and claim
    FIRST, model second: the busy fence must exist before the expensive
    part, so a second trigger during a model call cannot start a second
    turn (the claim-early half of n3-plan §9.1, which the night ruling
    kept);
  - `:close` — an open run whose every form already has a terminal
    receipt. The fold is done. This is its own situation rather than a
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
            [datahike.api :as d]
            [seon.cluster.message :as message]
            [seon.cluster.run :as run]
            [seon.schema.edn :as schema.edn]))

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
  (let [run (d/q '[:find (pull ?run [*]) .
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

(defn- next-ordinal
  "The first form ordinal of `run` with no terminal receipt, or nil.
  Resume is a QUERY, never a cursor: a receipt is terminal when it
  carries a terminal fact — `result-edn`, `error`, or `interrupted-at`
  (the query twin of `run/terminal?`; there is no status to read) —
  and `recover-tx` has already stamped a dead process's dangling
  receipts with `interrupted-at`, so an interrupted form is DONE being
  attempted and the fold moves past it. Nothing re-executes."
  [db run-id]
  (let [ordinals (d/q '[:find [?ordinal ...]
                        :in $ ?run-id
                        :where
                        [?run :seon.cluster.run/id ?run-id]
                        [?form :seon.cluster.run.form/run ?run]
                        [?form :seon.cluster.run.form/ordinal ?ordinal]]
                      db run-id)
        settled (into #{}
                      (d/q '[:find [?ordinal ...]
                             :in $ ?run-id
                             :where
                             [?run :seon.cluster.run/id ?run-id]
                             [?receipt :seon.cluster.eval/run ?run]
                             [?receipt :seon.cluster.eval/ordinal ?ordinal]
                             (or [?receipt :seon.cluster.eval/result-edn _]
                                 [?receipt :seon.cluster.eval/error _]
                                 [?receipt
                                  :seon.cluster.eval/interrupted-at _])]
                           db run-id))]
    (first (sort (remove settled ordinals)))))

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
  "The unambiguous identity of one form's derived problem."
  {:malli/schema [:=> [:cat :seon.cluster.run/id
                       :seon.cluster.run.form/ordinal]
                  :seon.problems/id]}
  [run-id ordinal]
  (str "problem-" (pr-str [run-id ordinal])))

(defn planner-scoped-attempt?
  "True when `run-id` belongs to a goal's caused-by message chain.

  A planner attempt's opening transaction points at one member of the chain:
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
      (catch #?(:clj Throwable :cljs :default) _
        nil))))

(def ^:private lint-rejected-kind
  :seon.cluster.loop/lint-rejected)

(defn- lint-refusal-receipt?
  [receipt]
  (= lint-rejected-kind
     (:seon.error/kind (receipt-value receipt))))

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
                       :seon.cluster.run.form/ordinal :boolean]
                  :boolean]}
  [db run-id ordinal interrupted?]
  (boolean
   (or interrupted?
       (d/q '[:find ?receipt .
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
        (when (contains? (:schema db) :seon.cluster.run.form/ns)
          (d/q '[:find ?owner-id .
                 :in $ ?form
                 :where
                 [?form :seon.cluster.run.form/ns ?namespace]
                 [?owner :seon.cluster.agent/namespace ?namespace]
                 [?owner :seon.cluster.agent/id ?owner-id]]
               db form-eid))]
    (or namespace-owner
        (d/q '[:find ?author-id .
               :in $ ?form
               :where
               [?form :seon.cluster.run.form/run ?run]
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

(defn- form-receipt
  [db form]
  (d/q '[:find (pull ?receipt [*]) .
         :in $ ?run ?ordinal
         :where
         [?receipt :seon.cluster.eval/run ?run]
         [?receipt :seon.cluster.eval/ordinal ?ordinal]]
       db
       (:db/id (:seon.cluster.run.form/run form))
       (:seon.cluster.run.form/ordinal form)))

(defn- form-run-id
  [db form]
  (d/q '[:find ?run-id .
         :in $ ?form
         :where
         [?form :seon.cluster.run.form/run ?run]
         [?run :seon.cluster.run/id ?run-id]]
       db (:db/id form)))

(defn- assignment-facts
  [db form receipt owner-id]
  (let [form-eid (:db/id form)
        receipt-eid (:db/id receipt)
        author-eid
        (d/q '[:find ?author .
               :in $ ?form
               :where
               [?form :seon.cluster.run.form/run ?run]
               [?run :seon.cluster.run/agent ?author]]
             db form-eid)
        owner-eid
        (d/q '[:find ?owner .
               :in $ ?owner-id
               :where [?owner :seon.cluster.agent/id ?owner-id]]
             db owner-id)
        assignment?
        (boolean
         (and receipt-eid owner-eid author-eid
              (d/q '[:find ?assignment .
                     :in $ ?problem ?author ?owner
                     :where
                     [?assignment :seon.cluster.message/about ?problem]
                     [?assignment :seon.cluster.message/from ?author]
                     [?assignment :seon.cluster.message/to ?owner]]
                   db receipt-eid author-eid owner-eid)))
        declination?
        (boolean
         (and assignment?
              (d/q '[:find ?declination .
                     :in $ ?problem ?author ?owner
                     :where
                     [?declination :seon.cluster.message/about ?problem]
                     [?declination :seon.cluster.message/from ?owner]
                     [?declination :seon.cluster.message/to ?author]
                     [?declination :my.message/reason _]]
                   db receipt-eid author-eid owner-eid)))]
    {:seon.cluster.work/assignment? assignment?
     :seon.cluster.work/declination? declination?}))

(defn form-settlement
  "One frozen form's exactly-one derived state at this database value."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.run.form/id]
                  :seon.cluster.work/form-settlement]}
  [db form-id]
  (let [form (d/pull db '[*] [:seon.cluster.run.form/id form-id])
        receipt (form-receipt db form)
        owner-id (form-owner db form)
        {:seon.cluster.work/keys [assignment? declination?]}
        (assignment-facts db form receipt owner-id)
        red? (and (terminal-receipt? receipt) (red-receipt? receipt))
        artifact? (and red?
                       (resume-artifact?
                        db
                        (form-run-id db form)
                        (:seon.cluster.run.form/ordinal form)
                        (boolean (:seon.cluster.eval/interrupted-at receipt))))
        [state settled?]
        (cond
          (nil? receipt) [:unevaluated false]
          (not (terminal-receipt? receipt)) [:running false]
          declination? [:owner-declared-cant true]
          artifact? [:unrouted-red false]
          (and red? assignment?) [:routed false]
          red? [:unrouted-red false]
          assignment? [:owner-fixed true]
          :else [:succeeded true])]
    (cond-> {:seon.cluster.run.form/id
             (:seon.cluster.run.form/id form)
             :seon.cluster.run.form/ordinal
             (:seon.cluster.run.form/ordinal form)
             :seon.cluster.agent/id owner-id
             :seon.cluster.work/form-state state
             :seon.cluster.work/settled? settled?}
      receipt
      (assoc :seon.cluster.eval/id (:seon.cluster.eval/id receipt))
      (:seon.problems/id receipt)
      (assoc :seon.problems/id (:seon.problems/id receipt)))))

(defn plan-settlement
  "Every form state and whether all forms of `run-id` are settled."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.run/id]
                  :seon.cluster.work/plan-settlement]}
  [db run-id]
  (let [form-ids
        (d/q '[:find ?form-id ?ordinal
               :in $ ?run-id
               :where
               [?run :seon.cluster.run/id ?run-id]
               [?form :seon.cluster.run.form/run ?run]
               [?form :seon.cluster.run.form/id ?form-id]
               [?form :seon.cluster.run.form/ordinal ?ordinal]]
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

(declare unanswered-triggers)

;;; ---------------------------------------------------------------------------
;;; The episode — derived purely from committed facts (F1 §7)
;;; ---------------------------------------------------------------------------

(defn- outside-trigger?
  "True when the message came from outside the population's AUTONOMOUS
  activity — a human, or a schedule fire. An agent-sent message carries
  `:seon.cluster.message/from`; the error recorder's carries
  `:seon.cluster.message/about` (recorder provenance) and deliberately
  does NOT reset the episode (F1 seal correction R3: an agent in an
  error loop must not have its cap reset by its own failure
  notifications)."
  [db message-id]
  (nil? (d/q '[:find ?message .
               :in $ ?id
               :where
               [?message :seon.cluster.message/id ?id]
               (or [?message :seon.cluster.message/from _]
                   [?message :seon.cluster.message/about _])]
             db message-id)))

(defn episode-runs
  "The agent's runs since the one answering the last outside trigger.

  Inclusive of that run, and derived purely from committed facts: every
  run's opening transaction names its trigger as `:seon.db/trigger`
  tx-meta. Refusal correction reuses that trigger, so the episode anchor
  is the FIRST answering transaction for each outside trigger, then the
  latest of those first answers; later corrective runs cannot reset their
  own bound. A trigger is outside exactly when it carries neither `from`
  nor `about` (R3 — the error recorder never resets the episode). Zero
  new facts, no stored counter — an outside trigger's first run IS the
  reset, so no reset code exists. An agent that has NEVER answered an
  outside trigger counts EVERY run: all of them are autonomous
  continuation, and a zero here would void the cap for exactly the
  agent-spawned agents it most concerns (review-caught, 2026-07-28)."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.agent/id]
                  :seon.cluster.work/episode-runs]}
  [db agent-id]
  (let [first-outside-txs
        (d/q '[:find ?message (min ?tx)
               :in $ ?agent-id
               :where
               [?agent :seon.cluster.agent/id ?agent-id]
               [?run :seon.cluster.run/agent ?agent]
               [?run :seon.cluster.run/id _ ?tx]
               [?tx :seon.db/trigger ?message]
               (not [?message :seon.cluster.message/from _])
               (not [?message :seon.cluster.message/about _])]
             db agent-id)
        outside-tx (reduce max 0 (map second first-outside-txs))]
    (or (d/q '[:find (count ?run) .
               :in $ ?agent-id ?outside-tx
               :where
               [?agent :seon.cluster.agent/id ?agent-id]
               [?run :seon.cluster.run/agent ?agent]
               [?run :seon.cluster.run/id _ ?tx]
               [(>= ?tx ?outside-tx)]]
             db agent-id outside-tx)
        0)))

(defn- max-episode-runs
  "The episode dial, read from the config singleton on this database
  value — a config fact like every other dial, so a live change applies
  at the very next pass. Nil when absent, and absence is FAIL-CLOSED
  for agent-sent triggers (the `max-chain` precedent)."
  [db]
  (d/q '[:find ?value .
         :where [_ :seon.config.run/max-episode-runs ?value]]
       db))

(defn- episode-capped?
  "True when only OUTSIDE triggers may open a run for `agent-id`: the
  episode count has reached the dial, or the dial is absent
  (fail-closed)."
  [db agent-id]
  (let [limit (max-episode-runs db)]
    (or (nil? limit)
        (>= (episode-runs db agent-id) limit))))

(defn- latest-closed-run
  "The latest run this agent closed, ordered by its closing transaction."
  [db agent-id]
  (->> (d/q '[:find ?run ?run-id ?opened-tx ?closed-tx
              :in $ ?agent-id
              :where
              [?agent :seon.cluster.agent/id ?agent-id]
              [?run :seon.cluster.run/agent ?agent]
              [?run :seon.cluster.run/id ?run-id ?opened-tx]
              [?run :seon.cluster.run/closed-at _ ?closed-tx]]
            db agent-id)
       (sort-by (fn [[_ run-id opened-tx closed-tx]]
                  [closed-tx opened-tx run-id]))
       last))

(defn- lint-refusal-continuation-trigger
  "The latest closed run's trigger when any receipt was lint-refused.

  Presence derives the continuation below the existing episode cap. The
  trigger identity is reused; no message, timer, cursor, or retry fact is
  created. Selecting the latest closed run before examining its receipts
  prevents an older refusal from resurfacing after a later successful turn."
  [db agent-id]
  (when-not (episode-capped? db agent-id)
    (when-let [[run _run-id opened-tx _closed-tx]
               (latest-closed-run db agent-id)]
      (when (some lint-refusal-receipt?
                  (d/q '[:find [(pull ?receipt
                                  [:seon.cluster.eval/result-edn]) ...]
                         :in $ ?run
                         :where
                         [?receipt :seon.cluster.eval/run ?run]
                         [?receipt :seon.cluster.eval/result-edn _]]
                       db run))
        (d/q '[:find ?trigger-id .
               :in $ ?opened-tx
               :where
               [?opened-tx :seon.db/trigger ?trigger]
               [?trigger :seon.cluster.message/id ?trigger-id]]
             db opened-tx)))))

(defn- openable-trigger
  "The trigger `agent-id`'s next run answers, under the episode gate.
  Below the cap: oldest-first over all unanswered triggers. AT the cap
  (or with the dial absent): only OUTSIDE triggers are selectable,
  oldest such first — a deferred agent-sent trigger is SKIPPED, never a
  selection blocker, because an older deferred self-trigger that
  blocked selection would keep the arriving outside trigger from ever
  opening and the count from ever resetting (F1 seal correction:
  the cap-hit deadlock found in review)."
  [db agent-id]
  (let [triggers (unanswered-triggers db agent-id)]
    (if (episode-capped? db agent-id)
      (first (filter #(outside-trigger?
                       db (:seon.cluster.message/id %))
                     triggers))
      (first triggers))))

(defn deferred-triggers
  "The unanswered triggers the episode gate is deferring, oldest first.
  Non-empty exactly while the agent is at the cap (or the dial is
  absent) AND agent-sent triggers are pending. PRESENCE, no stored
  anything: the refusal wrote nothing, so this derivation is the whole
  \"deferred\" state — the next outside trigger's run resets the count
  and this derives to empty."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.agent/id]
                  [:vector [:map [:seon.cluster.message/id
                                  :seon.cluster.message/id]]]]}
  [db agent-id]
  (if (episode-capped? db agent-id)
    (into []
          (remove #(outside-trigger? db (:seon.cluster.message/id %)))
          (unanswered-triggers db agent-id))
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
       :seon.cluster.run.form/ordinal ordinal}
      {:seon.cluster.work/situation :close
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
  run, a lint refusal on the latest closed run derives corrective `:open`
  first below the episode cap; otherwise the `:open` arm selects an
  unanswered trigger under that same gate. A deferred trigger simply
  derives no work — no consumer ever sees a decision to refuse."
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
      (if (:seon.cluster.run/plan-digest run)
        (fold-or-close db run agent-id)
        {:seon.cluster.work/situation :call
         :seon.cluster.run/id (:seon.cluster.run/id run)
         :seon.cluster.agent/id agent-id})

      ;; An unheld run is interruption wreckage, never work. Boot
      ;; recovery normally closes it before any graph is armed; keeping
      ;; this derivation total prevents a fabricated or in-process
      ;; orphan from becoming a cold resume.
      (some? run) nil

      :else
      (when-let [trigger-id
                 (or (lint-refusal-continuation-trigger db agent-id)
                     (:seon.cluster.message/id
                      (openable-trigger db agent-id)))]
        {:seon.cluster.work/situation :open
         :seon.cluster.agent/id agent-id
         :seon.cluster.message/id trigger-id}))))

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

(defn unanswered-triggers
  "The agent's trigger messages no run has answered, oldest first.

  A trigger is answered exactly when some run-opening transaction
  points at it. Answeredness is TRANSACTION METADATA, not a flag on the message and
  not a flag on the run (owner ruling, 2026-07-27 night): the
  run-opening transaction carries `:seon.db/trigger`, so this is one
  query over transactions and there is nothing to keep in sync. A
  message with no run pointing at it is unanswered by construction —
  which is also why deleting a run would make its trigger live again,
  and why nothing deletes runs."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.agent/id]
                  [:vector [:map [:seon.cluster.message/id
                                  :seon.cluster.message/id]]]]}
  [db agent-id]
  (->> (d/q '[:find ?id ?at
              :in $ ?agent-id
              :where
              [?agent :seon.cluster.agent/id ?agent-id]
              [?message :seon.cluster.message/to ?agent]
              [?message :seon.cluster.message/id ?id]
              [?message :seon.cluster.message/at ?at]
              ;; answered = SOME transaction named it as its trigger.
              ;; The absence is the fact; there is no flag to maintain.
              (not [_ :seon.db/trigger ?message])]
            db agent-id)
       (sort-by (fn [[id at]] [(inst-ms at) id]))
       (mapv (fn [[id at]] {:seon.cluster.message/id id
                            :seon.cluster.message/at at}))))
