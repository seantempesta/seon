---
type: issue
status: open
severity: blocker
tags: [issue, database, flow]
---

# Fence accepted writes before retained-head preparation

## Problem

The retained-head coordinator can freeze and publish an exact main head, then
create its undo and prepared-target branches while the ordinary pod and writer
still accept work. A concurrent main transaction can therefore stale the
confirmed intent before preparation. Existing expected-head checks prevent an
incorrect force, but the operation is left as a retained divergence with no
public, proved abort/replan transition.

Pod quiescence alone is not the complete fence. It drains agent runs and
detaches the replica, while the UDS writer owns independently accepted handler
threads. Only writer shutdown closes request admission and joins those handlers.

## Original evidence

`script/seon/dev/restore_state.clj` currently derives and publishes the intent,
then lets the fact-derived planner create the undo and prepared-target branches
before `prepare-exclusive-transition` stops retained pods, the main pod, and
the writer. `src/seon/db/transport/uds.clj` accepts connections on independent
handler threads; observing on a fresh connection is not evidence that an older
accepted transaction has finished.

The exact transition contract in
`docs/prds/database-lifecycle-recovery/research/datahike-as-of-fork-and-restore-2026-07-12.md`
requires closing write admission, draining accepted work, and re-reading the
confirmed main head before either reserved branch is created. The guarded
branch-create and force operations correctly reject a moved expected head, so
this is presently fail-closed rather than silent corruption.

## Current implementation

The source coordinator now separates read-only `plan!`, exactly confirmed
`apply!`, prompt-free `resume!`, and narrowly proved `abort!`. Apply rederives
the complete candidate and requires whole-value equality before fsync
publication. Resume materializes the frozen blobs, stops every retained pod,
drains the ordinary pod, writer, and watcher, reopens only the observation
writer, and requires the fact-derived next command still be `create-undo`
before publishing `U`. The pre-admin transition rechecks retained-pod absence.

Abort is allowed only before `U` or `P`, with no completion, no admin result,
an exact read-only restore-admin absence projection, and no record for the
intent's restore-pod generation. It deletes blob-result evidence first,
rechecks admin absence, and deletes the intent last. Focused plan/apply/abort,
same-`t` completion-coordinate, CLI, and coordinator proof is green at 51 tests
and 221 assertions.

The issue remains open for an accepted-UDS-write crash-cut falsifier and the
source-frozen live restore/abort/replan sequence. A 2026-07-15 executable
runbook audit found that the public coordinator still converges every derived
command in one uninterrupted invocation. Polling and killing a process cannot
name an exact durable cut and would bypass supervisor ownership. The minimal
proof support is invocation-local fault injection at the existing derived
command boundary, with no persisted phase or second coordinator; it must cover
after intent/before force, after force/before completion, after
completion/before evidence deletion, and after deletion/before fresh ordinary
startup.

## Owner

The one `seon.dev.restore-state` external coordinator, using the existing
`seon.dev.process` pod quiescence and writer terminal-drain evidence plus the
existing expected-head database protocol operations.

## Acceptance

- After confirmation and durable intent publication, the coordinator stops the
  main pod, closes the writer, and proves every accepted handler terminal before
  preparing `U` or `P`.
- A fresh observation writer re-reads exact `H`, `T`, and the complete roster;
  branch creation and the later force retain their existing expected-head
  fences.
- A crash at every cut resumes from immutable facts without opening ordinary
  pod admission or overlapping a writer/admin generation.
- If `H`, `T`, or the roster moved before the exclusive fence, force is never
  invoked. An explicit abort/replan operation may delete the stale intent only
  after proving no completion/admin effect and no reserved branch exists.
- Focused concurrency proof injects an already accepted transaction across pod
  shutdown and shows that preparation observes either its committed head before
  confirmation or a typed stale-plan rejection after confirmation.
