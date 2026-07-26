---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, database]
---

# Recover a run opened before its plan commits

## Problem

A process killed after `open-run!` and before the plan transaction leaves a run
that **no survivor can ever recover**. It is invisible to both recovery paths at
once:

- the recoverable-run query requires a plan digest, which this run does not yet
  have, so recovery skips it;
- the pending-message query excludes any message that already has a run cause,
  and `open-run!` committed exactly that cause, so re-enumeration skips it too.

The run stays `:open` forever, its agent pointer stays occupied, and the
originating message is permanently consumed without work being done.

**The window is the provider call**, which is 78.5% of a turn's wall clock. This
is therefore the *most likely* moment to die and the one moment that cannot be
resumed — the inverse of the property the design exists to guarantee.

## Evidence

Verified 2026-07-26 at `71f3cb0e0`.

- `open-run-tx-data` (`src/seon/agent/driver.clj:363-375`) commits the run with
  `:seon.agent.run/status :open`, `:seon.agent.run/cause
  [:seon.agent.message/id message-id]`, and a CAS on the agent's run pointer.
  It does **not** write `:seon.agent.run/plan-digest`.
- `recoverable-run-query` (`:343-350`) requires
  `[?run :seon.agent.run/plan-digest _]`.
- `pending-message-query` (`:332-341`) ends with
  `(not [?run :seon.agent.run/cause ?message])`.
- The plan digest is committed later, by `plan-tx-data`, after the model reply
  returns — so the gap spans the whole provider call.

Turn attribution measuring the provider share at 78.51% is recorded in
[[../../prds/sci-execution-runtime/research/measurements-2026-07-25]] with its
conditions.

Contrast: crash resume is otherwise correct and measured — six kill positions
plus a double kill converged, one re-execution per crash, and SIGKILL inside
`d/transact` at 8 points over 200-datom transactions produced zero torn
transactions. Every one of those cases had a committed plan. **No prior resume
evidence covers the pre-plan window.**

## Owner

`seon.agent.driver` owns run admission and recovery enumeration.
`seon.agent.run.core` owns the claim/epoch/lease algebra and is unchanged by
this.

## Acceptance

- A process killed between run open and plan commit leaves work that a survivor
  completes, proven by an executing test that kills mid-provider-call — not by
  argument. The existing kill-position harness is the model.
- The fix does not introduce a second recovery path or a scan that competes with
  the wake feed. Prefer making the run visible to the recovery it already has
  over adding a third query; an open run with no plan is a legitimate,
  queryable state and should be recoverable *as* that state.
- No wake attribute is added that the wake path's own work commits (landmine 8;
  the earlier feedback loop measured 7.0 → 14.4 → 124.8 commits per useful run
  and OOM at n=20).
- The originating message is never consumed without a terminal outcome: either
  the run completes, or it closes with a recorded fault.
- One recurring regression under `test/` claimed by `bin/test-writer`.

Found by the scheduling design investigation (`d29138d1d`) while auditing
process-local state; recorded here because a lane summary is not a durable
record.

Related: [[agent-messages-never-wake-the-jvm-driver]].
