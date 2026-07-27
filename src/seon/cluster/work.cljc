(ns seon.cluster.work
  "What the loop should do next, derived from one database value.

  DRAFT CONTRACT LAYER FOR ORCHESTRATOR SEAL (drafted 2026-07-27 — N3,
  package 1, from n3-plan §4.3, §9 and the 2026-07-27 night rulings).
  Nothing here is implemented: every body throws
  `awaits implementation`.

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
  - kill after the last terminal receipt, before close: `:resume` with
    no next ordinal, which is the loop's signal to close;
  - kill during recovery itself: `recover-tx` is idempotent and every
    terminal receipt is byte-untouched, so the derivation is unchanged."
  (:require [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/work.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The derivations
;;; ---------------------------------------------------------------------------

(defn next-work
  "The ONE thing to do next on `db`, or nil when idle.
  Pure. The situations are ordered by what is already committed, not by
  preference: a held run outranks a trigger, because finishing what is
  started is what makes the busy fence mean anything.
  `:resume` carries the ordinal the fold restarts at — the first form
  ordinal with no terminal receipt — so the loop never recomputes it."
  {:malli/schema [:=> [:cat :any :seon.cluster.work/request]
                  [:maybe :seon.cluster.work/next]]}
  [db request]
  (throw (ex-info "awaits implementation" {::fn `next-work})))

(defn more-work?
  "True when another pass would find work. The self-rewake predicate.
  Exactly `(some? (next-work db request))` — stated as its own contract
  because the loop's rewake must never drift from the derivation it
  rewakes for."
  {:malli/schema [:=> [:cat :any :seon.cluster.work/request] :boolean]}
  [db request]
  (throw (ex-info "awaits implementation" {::fn `more-work?})))

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
  (throw (ex-info "awaits implementation" {::fn `interruption})))

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
  (throw (ex-info "awaits implementation" {::fn `unanswered-triggers})))
