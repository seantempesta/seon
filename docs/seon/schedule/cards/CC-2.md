---
title: "Card CC-2: The test step's admission rides the recording transaction"
type: reference
status: active
created: 2026-10-09
---

# CC-2 The test step's admission rides the recording transaction

Step 1 ([README](README-commit-cost.md)). Lane: Opus (the one REPL path's recording). Owner question CC-Q1 first.

## What exists
An eval request that evaluated a form commits its recording (`seon.turn.batch/settle`), then the test step
(`seon.turn` `test-step` → `run-proofs` / `run-reaching` → `seon.test/run`) commits an admission
(`[:db.fn/call seon.test/admit-run request]` through `seon.test/selection-admission`) and, after executing, one
member completion (`seon.test.runner/record-tx`). Measured 2026-10-09: a `defn` with one reaching test is 3 durable
commits on the agent's branch (recording 263 datoms, admission 18, completion 11). CO-2 already removes the admission
when nothing executes; this card removes it as a separate commit when members do execute.

## Change
Probe first, on your own branch: does `seon.test/admit-run`, called as a transaction function inside the recording
transaction, see the program facts written earlier in the same transaction, and can the step's selection be derived
from the recording's `d/with` result before it commits? If either is no, stop and report it with the measured gap.
If yes: the step selects from the recording's `:db-after`-equivalent value before settle, the recording's tx-data ends
with the admission call, and the step executes against the committed head that recording produced. The writer still
decides admission (OD-Q5); only the separate commit goes.

## Functions
- CHANGE `seon.turn` test-step ordering and `seon.turn.batch/settle`'s tx-data composition.
- CHANGE `seon.test/run` to accept a selection admitted by the caller's transaction (no second `admit-run`).

## Proof
`seon.turn-test/a-defn-with-a-reaching-test-commits-recording-and-completion-only` (new): a `defn` reaching one test
advances the branch by 2 commits (today 3), the reply's `:seon.cost/transactions` is 2 (needs CC-1), and the admission
datoms are on the recording transaction. Existing `seon.test.admission-digest-test` stays green.

## Cost
Saves one durable commit per eval request whose tests execute: measured 19-25 ms for a 17-datom commit, its two full
barriers and its node files. Expected after: recording + completion, ~2 × 12-25 ms.

## Durability
The recording (now carrying the admission) is durable before the step runs and before the answer; completion is
durable before the answer, as today.

## Commit
`bin/lane commit <lane> -m "CC-2: test admission rides the recording transaction" --test seon.turn-test/a-defn-with-a-reaching-test-commits-recording-and-completion-only --test seon.test.admission-digest-test <paths>`

## Dependencies
CC-Q1 (owner), CO-2 (same owner path), CC-1 (the count). No gitlink bump.
