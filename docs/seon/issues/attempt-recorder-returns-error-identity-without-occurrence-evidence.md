---
type: issue
status: open
severity: friction
tags: [issue, runtime, test, class/p3]
---

# Attempt recorder returns an identity without diagnostic evidence

Batch 19 and the fresh canonical in-process probe at `5c9135c07` both
refuse the backup call at `seon.error/notice`: the supplied fact lacks
`:seon.error/at`, message and payload. `seon.turn/record-attempt!` returns
the first transaction row (`src/seon/turn.clj:3899`), but
`seon.error/recording` deliberately makes that row identity-only; the
occurrence is written by its transaction function (`src/seon/error.clj:1387`).

The repair retains the existing recording descriptor, transacts its
`:seon.db/tx-data`, and returns its prepared diagnostic only after success.
No caller reconstructs evidence from the transaction template. The same
descriptor supplies the failure and truncation refs. Writer refusals remain
terminal before another provider call.

Candidate in-process proof: `an-unpaid-failure-with-a-backup-makes-exactly-two-calls`
passes 15 assertions; partial truncation passes 6; reasoning-only diagnostic
passes 9; cold acquisition passes 6 after its occurrence-aware observation.
The latter three retain distinct observables. All use fresh canonical branches,
SCI contexts and armed contracts. The first candidate correctly exposed that
raw pulled refs need projection too; the final candidate uses the error owner's
existing prepared diagnostic instead of adding another projection mechanism.

Commit and adopted-definition proof are recorded in
[the landing note](../../prds/context-generation/research/turn-test-reds-batch19-2026-09-16.md).
