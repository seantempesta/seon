(ns seon.cluster.work
  "What the loop should do next, derived from one database value.

  This contract layer is fully implemented and live-proven.

  THIS IS THE RESUME MODEL, and it is a pure derivation rather than a
  recovery procedure. There is no dirty flag, no scan-requested atom, no
  retry counter: the facts hold the work, and `next-work` reads them.
  Recovery is therefore not a code path — it is the same derivation
  running against a database that happens to carry a dead process's
  wreckage.

  FOUR SITUATIONS, TOTAL AND MUTUALLY EXCLUSIVE, `nil` for idle:

  - `:resume` — an open run this process holds, WITH a plan digest.
    Fold on at the first ordinal lacking a terminal receipt. Committed
    work continues; nothing re-executes;
  - `:call` — an open run this process holds, WITHOUT a plan digest.
    Derive the prompt, make the ONE paid model call, freeze the plan;
  - `:open` — an unanswered trigger and an agent with no open run.
    Open and claim FIRST, model second: the busy fence must exist
    before the expensive part, so a second trigger during a model call
    cannot start a second turn (the claim-early half of n3-plan §9.1,
    which the night ruling kept);
  - `:close` — an open run whose every form already has a terminal
    receipt. The fold is done. This is its own situation rather than a
    `:resume` carrying no ordinal, because fold-vs-close is a different
    instruction to the loop and an instruction must be visible in the
    value, never inferred from an absent key (seal revision,
    2026-07-27);
  - `nil` — idle.

  NO AUTO-RETRY, EVER (owner ruling, 2026-07-27 night). This is the
  clause that supersedes n3-plan §9.1's Option A recovery half: a run
  whose custody was released by recovery and which has NO plan digest
  lost its paid model call, and the loop DOES NOT call again. The
  interrupted run is settled and the agent adapts on its next trigger.
  A lost call is lost; the agent is told, not the call repeated.

  Consequently `:call` is reachable only for a run THIS process holds
  and opened in this pass. A run that is open, unclaimed and unplanned
  is not work — it is wreckage to settle, which is why
  `interruption` exists and why `next-work` does not return it.

  Crash walk (the kill positions of n3-plan §9.3, as this namespace
  answers them):
  - kill after the trigger commits, before any wake: the trigger is
    unanswered, so the boot pass derives `:open`. A normal first turn;
  - kill after claim, before/during/after the model call: N2's
    `recover-tx` releases dead custody; the run is open, unclaimed,
    unplanned. `next-work` does NOT return it — `interruption` does,
    and the loop settles it with no reply. ONE paid call is lost and
    the agent is told;
  - kill after plan freeze, before form 0: `:resume` at ordinal 0;
  - kill mid-fold: `:resume` at the first ordinal lacking a terminal
    receipt. Rows 6 and 7 are indistinguishable from the facts — the
    effect MAY have happened — and the derived warning says exactly
    that;
  - kill after the last terminal receipt, before close: `:close`, and
    the completion lands one wake later;
  - kill during recovery itself: `recover-tx` is idempotent and every
    terminal receipt is byte-untouched, so the derivation is unchanged."
  (:require [datahike.api :as d]
            [seon.cluster.run :as run]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/work.edn
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

(defn- agents-with-work
  "Every agent id that has an open run or an unanswered trigger."
  [db]
  (into (sorted-set)
        (d/q '[:find [?agent-id ...]
               :where
               [?agent :seon.cluster.agent/id ?agent-id]
               (or [?agent :seon.cluster.agent/run _]
                   [_ :seon.cluster.message/to ?agent])]
             db)))

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
  tx-meta, and a trigger is outside exactly when it carries neither
  `from` nor `about` (R3 — the error recorder never resets the
  episode). Zero new facts, no stored counter — an outside trigger's
  own run IS the reset, so no reset code exists. An agent that has
  NEVER answered an outside trigger counts EVERY run: all of them are
  autonomous continuation, and a zero here would void the cap for
  exactly the agent-spawned agents it most concerns (review-caught,
  2026-07-28). The delivered conservation-audit derivation (§6),
  measured ~34 µs on a 64-run history."
  {:malli/schema [:=> [:cat :any :seon.cluster.agent/id]
                  :seon.cluster.work/episode-runs]}
  [db agent-id]
  (let [outside-tx
        (d/q '[:find (max ?tx) .
               :in $ ?agent-id
               :where
               [?agent :seon.cluster.agent/id ?agent-id]
               [?run :seon.cluster.run/agent ?agent]
               [?run :seon.cluster.run/id _ ?tx]
               [?tx :seon.db/trigger ?message]
               (not [?message :seon.cluster.message/from _])
               (not [?message :seon.cluster.message/about _])]
             db agent-id)]
    (or (d/q '[:find (count ?run) .
               :in $ ?agent-id ?outside-tx
               :where
               [?agent :seon.cluster.agent/id ?agent-id]
               [?run :seon.cluster.run/agent ?agent]
               [?run :seon.cluster.run/id _ ?tx]
               [(>= ?tx ?outside-tx)]]
             db agent-id (or outside-tx 0))
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
  {:malli/schema [:=> [:cat :any :seon.cluster.agent/id]
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
  ordinal with no terminal receipt — so the loop never recomputes it;
  when no such ordinal remains the situation is `:close`. The episode
  gate lives in the `:open` arm's trigger selection, so a deferred
  trigger simply derives no work — no consumer ever sees a decision to
  refuse."
  {:malli/schema [:=> [:cat :any :seon.cluster.work/agent-request]
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

      ;; an open run nobody holds: a planned one is committed work we
      ;; may pick up, an unplanned one lost its paid call and is
      ;; `interruption`'s business, never ours
      (and run (nil? (:seon.cluster.run/process run)))
      (when (:seon.cluster.run/plan-digest run)
        (fold-or-close db run agent-id))

      ;; another process holds it: not ours to touch
      (some? run) nil

      :else
      (when-let [trigger (openable-trigger db agent-id)]
        {:seon.cluster.work/situation :open
         :seon.cluster.agent/id agent-id
         :seon.cluster.message/id
         (:seon.cluster.message/id trigger)}))))

(defn more-agent-work?
  "True when another pass would find work for this agent. The turn
  proc's self-rewake predicate — exactly
  `(some? (next-agent-work db request))`, stated as its own contract
  because the rewake must never drift from the derivation it rewakes
  for."
  {:malli/schema [:=> [:cat :any :seon.cluster.work/agent-request]
                  :boolean]}
  [db request]
  (some? (next-agent-work db request)))

(defn next-work
  "The ONE thing to do next on `db`, or nil when idle.
  Pure, and since F1 exactly a `some` of `next-agent-work` over the
  agents that have anything — one derivation, two scopes, so the
  central pass and the per-agent procs can never disagree about what a
  situation is. The global sorted `some` dies with the central pass
  (F2)."
  {:malli/schema [:=> [:cat :any :seon.cluster.work/request]
                  [:maybe :seon.cluster.work/next]]}
  [db {:keys [:seon.cluster.run/process :seon.cluster.work/now]}]
  (some
   (fn [agent-id]
     (next-agent-work db {:seon.cluster.agent/id agent-id
                          :seon.cluster.run/process process
                          :seon.cluster.work/now now}))
   (agents-with-work db)))

(defn more-work?
  "True when another pass would find work. The self-rewake predicate.
  Exactly `(some? (next-work db request))` — stated as its own contract
  because the loop's rewake must never drift from the derivation it
  rewakes for."
  {:malli/schema [:=> [:cat :any :seon.cluster.work/request] :boolean]}
  [db request]
  (some? (next-work db request)))

(defn interruption
  "The open, unclaimed, unplanned run of `agent-id` on `db`, or nil.
  A run whose process died before the plan was frozen: its paid call is
  lost and NOTHING re-calls it. The loop settles it with no reply so the
  agent stops being busy, and the agent's next prompt carries the
  warning. Returned separately from `next-work` because it is not work
  — the difference between `continue this` and `bury this` must be
  visible in the value, not in a flag."
  {:malli/schema [:=> [:cat :any :seon.cluster.agent/id]
                  [:maybe [:map [:seon.cluster.run/id :seon.cluster.run/id]]]]}
  [db agent-id]
  (let [run (agent-run db agent-id)]
    (when (and run
               (nil? (:seon.cluster.run/process run))
               (nil? (:seon.cluster.run/plan-digest run)))
      {:seon.cluster.run/id (:seon.cluster.run/id run)})))

(defn interruptions
  "Every agent's open, unclaimed, unplanned run — the wreckage to settle.
  The plural of `interruption`, and the loop's entry point: a pass
  settles what a dead process left before it derives work, because an
  unsettled orphan keeps its agent BUSY and no trigger for that agent
  can ever be answered. One query rather than a scan, because the loop
  runs it every pass."
  {:malli/schema [:=> [:cat :any]
                  [:vector [:map
                            [:seon.cluster.agent/id :seon.cluster.agent/id]
                            [:seon.cluster.run/id :seon.cluster.run/id]]]]}
  [db]
  (->> (d/q '[:find ?agent-id ?run-id
              :where
              [?agent :seon.cluster.agent/id ?agent-id]
              [?agent :seon.cluster.agent/run ?run]
              [?run :seon.cluster.run/id ?run-id]
              (not [?run :seon.cluster.run/closed-at _])
              (not [?run :seon.cluster.run/process _])
              (not [?run :seon.cluster.run/plan-digest _])]
            db)
       (sort-by second)
       (mapv (fn [[agent-id run-id]]
               {:seon.cluster.agent/id agent-id
                :seon.cluster.run/id run-id}))))

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
  {:malli/schema [:=> [:cat :any :seon.cluster.agent/id]
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
