---
type: issue
status: superseded
severity: blocker
tags: [issue, agent, runtime, database]
---

# Keep an unfinished effect identity stable across run recovery

## Problem

The Step-1 op-id is the eval receipt id `(run, ordinal, claim-epoch)`. Run
recovery increments claim epoch before re-executing an unfinished form, so the
retried effect receives a different op-id. The writer cannot recognize it as a
replay and may commit the external effect again.

Claim epoch is a fencing generation for the process holding a run. It is not a
stable logical effect identity across process death.

## Evidence

- `src/seon/eval/receipt.cljc:84-92` serializes
  `[run-id ordinal claim-epoch]` as the receipt id.
- `src/seon/agent/driver.clj:672-689` uses that receipt id directly as
  `:seon.capability/op-id`.
- `src/seon/agent/run/core.cljc:176-190` increments claim epoch on reacquire or
  steal.
- `src/seon/agent/driver.clj:712-734` passes the recovered claim epoch into
  form execution.

Thus a post-effect/pre-terminal crash changes the identity from, for example,
`[run 0 1]` to `[run 0 2]` while the logical unfinished form is the same.

## Owner

`seon.effect` owns logical request identity. `seon.agent.run.core` must retain
claim epoch as the run fence; replay must not weaken that fence or repurpose it
as logical operation identity.

## Acceptance

- A production-path kill after the effect commit and before the terminal
  receipt causes recovery to return the original result with
  `:seon.capability/replayed? true`.
- Exactly one durable database write or message exists after recovery.
- Claim epoch still increments and fences the dead process.
- The proof records both claim epochs and the single stable effect op-id.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
