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
  Resume is a QUERY, never a cursor: a receipt is terminal when its
  status is not `:running`, and `recover-tx` has already turned a dead
  process's `:running` receipts into `:interrupted` ones — so an
  interrupted form is DONE being attempted and the fold moves past it.
  Nothing re-executes."
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
                             [?receipt :seon.cluster.eval/status ?status]
                             [(not= ?status :running)]]
                           db run-id))]
    (first (sort (remove settled ordinals)))))

;;; ---------------------------------------------------------------------------
;;; The derivations
;;; ---------------------------------------------------------------------------

(declare unanswered-triggers)

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

(defn next-work
  "The ONE thing to do next on `db`, or nil when idle.
  Pure. The situations are ordered by what is already committed, not by
  preference: a held run outranks a trigger, because finishing what is
  started is what makes the busy fence mean anything.
  `:resume` carries the ordinal the fold restarts at — the first form
  ordinal with no terminal receipt — so the loop never recomputes it;
  when no such ordinal remains the situation is `:close`."
  {:malli/schema [:=> [:cat :any :seon.cluster.work/request]
                  [:maybe :seon.cluster.work/next]]}
  [db {:keys [:seon.cluster.run/process]}]
  (some
   (fn [agent-id]
     (let [run (agent-run db agent-id)]
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
         (when-let [trigger (first (unanswered-triggers db agent-id))]
           {:seon.cluster.work/situation :open
            :seon.cluster.agent/id agent-id
            :seon.cluster.message/id
            (:seon.cluster.message/id trigger)}))))
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
  "The trigger messages for `agent-id` that no run-opening transaction
  points at, oldest first.
  Answeredness is TRANSACTION METADATA, not a flag on the message and
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
