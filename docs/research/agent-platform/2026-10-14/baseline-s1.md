---
title: "Baseline: S1 chain and task-writer tests at HEAD"
type: research
status: measured at HEAD 5e0774bd6, read-only
created: 2026-10-14
---

# Baseline: S1 chain and task-writer tests at HEAD

Haiku baseline lane. Source HEAD `5e0774bd6` (archive of HEAD, no working-tree diff). Each run was a throwaway JVM via `bin/seon check --test NS/NAME ...` (no `--include-long`; none of these tests was declared long). The pre-brief (`prebrief-s1.md`, commit 4a6ba813a) said "18" and its status note lists 19 symbols; the set run here is those 19 plus `seon.task-test/only-a-merge-completes-a-leaf` (named in its S1b-2 section), 20 in all. No `fork!` core-case tests are named by the pre-brief, so none were added.

Per-test elapsed time is not printed by `bin/seon check`; the table gives each run's total as reported by the runner (`run ms`). Those totals include all tests of that run.

## Results

| Test | Run | Status | run ms |
|---|---|---|---|
| `seon.task-test/the-task-facts-adopt-on-a-branch-of-the-live-store` | 1 | green | 10054 |
| `seon.task-test/an-adoption-keeps-a-merged-refinement-the-files-have-not-seen` | 1 | green | 10054 |
| `seon.task-test/one-write-clones-a-chain-and-one-running-readies-survey` | 1 | green | 10054 |
| `seon.task-test/only-a-merge-completes-a-leaf` | 1 | green | 10054 |
| `seon.step-test/survey-waits-for-the-latest-user-approval` | 1 | **red** (error + fail) | 10054 |
| `seon.step-test/a-default-judges-the-task-it-is-given` | 1 | green | 10054 |
| `seon.task-ingestion-test/ingested-tasks-answer-both-ways-and-unchanged-input-writes-nothing` | 1 | green | 10054 |
| `seon.task-ingestion-test/malformed-task-files-refuse-by-name-without-writing` | 2 | green | 17857 |
| `seon.task-ingestion-test/adoption-carries-published-tasks-keeps-the-destinations-own-and-refuses-a-competing-edit` | 2 | green | 17857 |
| `seon.task-ingestion-test/a-revised-task-shows-on-the-next-call-without-reassignment` | 2 | green | 17857 |
| `seon.task-ingestion-test/assignment-refuses-a-task-without-proofs` | 2 | green | 17857 |
| `seon.cluster.db-flow-test/a-scripted-full-chain-completes-without-hand-acts` | 2 | **red** (error) | 17857 |
| `seon.cluster.db-flow-test/a-hand-assignment-writes-the-attempt-on-the-agents-own-branch` | 2 | green | 17857 |
| `seon.cluster.db-flow-test/a-refinement-on-a-branch-reaches-main-only-at-the-end` | 2 | green | 17857 |
| `seon.cluster.registry-test/boot-retires-only-branches-no-fact-claims` | 3 | green | 14946 |
| `seon.cluster.merge-test/a-refined-task-is-written-to-its-task-file-and-survives-re-ingestion` | 3 | green | 14946 |
| `seon.cluster.merge-test/only-an-accepted-candidate-completes-a-task` | 3 | green | 14946 |
| `seon.merge-test/a-base-ref-keeps-the-fork-commit-only-until-retirement` | 3 | green | 14946 |
| `my.task-test/refine-preserves-every-declared-member` | 3 | green | 14946 |
| `seon.cache-test/a-kinds-weight-counts-its-value-not-its-key` | 3 | green | 14946 |

Runs: 1 = 7 vars, `run 855a85228a18`, pass 54 / fail 1 / error 1, 10054 ms. 2 = 7 vars, `run f4dcab27c80e`, pass 52 / fail 0 / error 1, 17857 ms. 3 = 6 vars, `run 70e326f1204b`, pass 41 / fail 0 / error 0, 14946 ms, check OK. Run 1 and run 2 each reported their red through the candidate-red diagnostic (`data/clusters/default/logs/operator-check-20261014T105338.553.edn`, `...T105353.006.edn`). A test is green when the runner lists no red for it in its run.

## Reds: first failing message

- `seon.step-test/survey-waits-for-the-latest-user-approval`: `step_test.clj:131`, uncaught `NullPointerException`: `Cannot invoke "clojure.lang.IFn.invoke(Object)" because "this.answer_BANG_" is null`. The same test also fails at `step_test.clj:138`: `no answer: the send and the wait alone never complete Survey` (expected `(false? (survey-green?))`, actual `nil`).
- `seon.cluster.db-flow-test/a-scripted-full-chain-completes-without-hand-acts`: uncaught `ExceptionInfo` at a fixture write (`db_flow_test.clj:1501`, `test_support.clj:270`): `Fixture write was refused at the write: Nothing found for entity id [:seon.task/id "3f12ae4e8cc4"]`, writing `:seon.task/execution :running` (`:seon.db/transaction-refused`, `:seon.error/type :seon.db.write/validation-refusal`).

## Notes for the S1 slices

- Measured, not diagnosed: the two reds are not explained here. The `a-scripted-full-chain` refusal is a `:seon.task/execution :running` write against an entity id the transaction does not yet hold; the cause is for the S1 owner to confirm.
- The tests the pre-brief tied to S1b-1 (`malformed-task-files-refuse-by-name-without-writing`, `ingested-tasks-answer-both-ways-and-unchanged-input-writes-nothing`, `one-write-clones-a-chain-and-one-running-readies-survey`) are green on HEAD. The S1b-1 red `task-members-have-defaults-and-kinds` is not in this set; it does not exist yet.
