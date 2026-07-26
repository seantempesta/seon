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

## Custody-window simulation

`test/seon/flow/custody_window_test.clj` now exercises both candidate shapes
with a real Flow `:io` proc, a throwaway in-memory Datahike connection, an
injectable kill while the fake provider call holds custody, and explicit seed
`20260726`. The focused result is 2 tests, 26 assertions, 0 failures, and 0
errors.

Candidate A behaved as proposed:

- **Expected:** a derived plan-less/lapsed query names the invisible claimed
  run; takeover closes it with a receipt; wake derivation restarts the message
  exactly once.
- **Observed:** the current recoverable-run and pending-message queries were
  both empty. The candidate query named the exact run, takeover advanced epoch
  1 to 2, recovery recorded `:failed-before-plan`, and one retry completed.
  There were two physical provider invocations, one upserted logical model-call
  fact, one recovery receipt, no open run, and no orphan.

Candidate B also behaved as proposed and is the recommendation:

- **Expected:** the provider call is held by a bounded attempt, not an open
  run. A lapsed attempt is recoverable; a successful attempt creates the run
  and freezes the plan atomically.
- **Observed:** the killed attempt was fenced and recorded `:crashed`, which
  re-derived exactly one wake for attempt ordinal 1. Every retained database
  value had zero open runs without plans. Run identity and plan digest datoms
  shared one transaction, the existing plan-only recoverable-run query saw the
  resulting run, outcomes were exactly `#{:crashed :success}`, and no
  recoverable attempt remained.

B wins because it makes the bad run state unrepresentable, preserves one
plan-complete meaning for run recovery, and extends the existing bounded
provider-attempt owner instead of occupying the run pointer during the longest
window. It needs attempt connections to the originating message and holding
process plus claim epoch and lease instant. Attempt recovery queries open,
lapsed attempts and uses epoch/lease takeover before recording `:crashed`;
pending-message derivation excludes a live attempt or resulting run, but admits
the same message after a crashed attempt. The successful attempt transaction
fences its epoch and commits run identity, cause, process, claim epoch, lease,
plan digest, plan forms, and agent run pointer together. Run recovery remains
unchanged and requires a plan.

This breaks the current ordering deliberately: `open-run!` moves after the
provider reply, the separate absent-to-digest `plan-tx-data` CAS is no longer
the publication boundary, and run claim epoch begins only after provider
success. Attempt takeover must reuse or generalize the existing run
claim-epoch/lease algebra. A deadline alone is insufficient because it cannot
fence a late former holder.

Step 2's message-identity work must settle originating message identity before
this change: attempt identity derives from `(originating-message-id,
attempt-ordinal)`, provider request identity derives from the attempt, and
re-executing a sending receipt `(run, ordinal, epoch)` must reproduce the same
originating message ID. Otherwise recovery creates a second attempt lineage.
The outbound message ID remains the sending receipt identity, not the provider
attempt identity.

The issue remains open until production admission adopts B and the recurring
test is claimed by the repository writer runner.

## Owner

`seon.agent.driver` owns wake derivation and the atomic run-plus-plan
publication boundary. `seon.ai.attempt` owns provider-attempt evidence.
`seon.agent.run.core` owns the claim/epoch/lease algebra that attempt custody
must reuse or generalize.

## Acceptance

- A process killed during the provider call leaves an open attempt whose
  expiry re-derives the same message wake. Run recovery remains the one
  plan-complete run path; attempt expiry does not compete with the wake feed.
- No committed database value contains an open run without both plan digest
  and plan forms.
- An attempt holder is fenced by process, claim epoch, and lease; a late former
  holder cannot publish a run or plan after takeover.
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
