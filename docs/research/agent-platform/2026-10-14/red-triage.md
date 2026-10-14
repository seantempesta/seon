---
title: "Red triage: bulk tier at 46992f75b"
type: research
status: active
---

# Red triage: bulk tier at 46992f75b, 2026-10-14

Scope: one bulk `bin/seon check --at 46992f75b8d080c6e89cae61284d74a374360ae6 --include-long` over 398 test
namespaces (every namespace with a deftest, minus fixture material, support and two namespaces the check refuses
as having no eligible tests). HEAD had moved to 46992f75b during the run; the run pins that commit.

## The bulk tier did not complete

The run stopped at the check's 600 s bound (`600757 ms`): 185 members executed, 1 unfinished, 2807 not started.
The bulk tier therefore has no verdict; the 2807 unrun members may hold further reds. `--include-long` was used, so
no long test is excluded. Bulk totals for the executed part: 1130 passing assertions, 34 failing, 6 erroring.

The check's `--at` path cannot raise the bound from `bin/seon check`. The isolated host (`bin/test --platform`)
runs a different member set, so it is not a substitute. Root needs a run that can finish the 2993-member tier (a
larger bound, or batches), recorded as evidence.

Two process defects found while setting up the run (not fixed here, no source edits):
- `bin/seon check` refuses a whole run when one named namespace has no eligible tests (`seon.test-support`,
  `seon.repl-parity-test`, `seon.competency`). The run cannot skip such a namespace, so the caller must filter them.
- A namespace holding a fixture (`seon.test-runner-failure-fixture`, `seon.test-resolution-fixture`,
  `seon.test.interpreted-fixture-test`, `seon.test.fixture-timing-test`) is refused as "Explicit fixture material is not
  gate membership". The bulk list must exclude them.

`seon.repl-parity-test` has a deftest-generating macro and no eligible tests. Whether its tests are meant to run is
unknown; untracked.

## Method

1. Bulk run (above): 17 red members (14 failing with a deterministic error plus 3 that are load-sensitive), plus one
   unfinished member among them.
2. Each red rerun alone with `--test <var> --include-long` at the same pin. 14 reproduce red alone. 3 pass alone.
3. Each deterministic red run at the oldest commit of the last 60 (`73cf41e88`, HEAD~59). All 14 are red there, so the
   turn-red commit is not in the window; the first red commit is "red at the oldest commit checked".
4. Task cross-check: `rg` of each var name in `resources/seon/tasks`.
5. Doomed check against TASK-CHAIN §6 (obligations, product, relaunch, chain!/chain-copy/effective, tags, survey/define
   templates) and the retired 2026-10-13 acceptance tests. None of the 17 reds exercises that machinery.

## Results

Class: `det` = deterministic red (red alone); `flake` = passes alone, red only in the bulk run (load/timing);
`env` = slot, classpath or store error (none observed).

| Var | Class | First red commit | Owner task | Doomed? | First failing message (alone) |
|---|---|---|---|---|---|
| `my.agent-test/an-answer-is-validated-and-keeps-its-message` | det | red at oldest checked, 73cf41e88 | `chain-intake-answer` (seon.task.edn; intent cites the test) | no (I2 stays) | `(string? first-reply)`: `db/q` refused a read (`:seon.db/invalid-read-error`); bulk run also NPE on `answer!` null |
| `my.edit-test/public-entries-declare-resolvable-io-capabilities` | det | red at oldest checked, 73cf41e88 | untracked | no | `(= :io (:seon.fn/workload entry))` got nil |
| `my.fs-test/public-entries-declare-resolvable-io-capabilities` | det | red at oldest checked, 73cf41e88 | untracked | no | `(true? (:seon.fn/private? declaration))` got nil |
| `my.root-test/a-merge-is-keyed-by-the-assignment` | det | red at oldest checked, 73cf41e88 | untracked | no | `(= :merged (:seon.task/outcome row))` got nil |
| `my.test-check-test/a-check-of-named-identities-answers-a-reused-reds-failure-reports` | det | red at oldest checked, 73cf41e88 | `a-check-of-named-identities-answers-a-reused-reds-failure-reports` (my.test.edn) | no | `(seq (:seon.test/results named))` is empty |
| `my.test-check-test/a-reused-check-answers-a-run-id-it-recorded` | det | red at oldest checked, 73cf41e88 | `a-reused-answer-names-a-recorded-run` (seon.test.edn) | no | `(true? reused)` got false |
| `my.test-test/an-agents-own-test-reaches-its-cluster-through-the-elided-arity` | det | red at oldest checked, 73cf41e88 | untracked | no | error: fixture write refused, `:seon.agent/branch` missing (ExceptionInfo) |
| `seon.adoption-contract-freshness-test/adopted-contracts-govern-both-sci-and-host-calls` | det | red at oldest checked, 73cf41e88 | untracked | no | subprocess exit 1, expected 0; also 24.5 s against a 5 s bound |
| `seon.adoption-diagnostic-test/operator-reports-the-typed-refusal-over-a-real-prepl` | det | red at oldest checked, 73cf41e88 | untracked | no | `(< byte-count 1024)`: 2590 bytes |
| `seon.agent-call-edges-test/admitted-declarations-retain-call-dependencies-across-turn-order` | det | red at oldest checked, 73cf41e88 | untracked | no | error: `seon.test/reach-digest` refused a string `test-symbol` (ExceptionInfo) |
| `seon.ai-test/captured-run4-replies-stop-before-fabricated-responses` | det | red at oldest checked, 73cf41e88 | untracked | no | `(and (vector? forms) (seq forms))` is false |
| `seon.armed-calls-test/a-database-passed-for-a-connection-is-an-ordinary-argument-refusal` | det | red at oldest checked, 73cf41e88 | untracked | no | error type nil, expected `:seon.instrument/contract-error` |
| `seon.await-owner-census-test/bounded-await-owner-has-exactly-the-declared-production-callers` | det | red at oldest checked, 73cf41e88 | untracked | no | production callers set is empty; declared set is not |
| `seon.background-blob-test/background-binary-results-remain-exact-across-the-inline-threshold` | det | red at oldest checked, 73cf41e88 | `background-binary-fixture-reaches-its-handler` (seon.test-support.edn) | no | error: fixture write refused, `:seon.program/definition-digest` missing (ExceptionInfo) |
| `seon.blob-publication-test/publication-and-collection-are-exclusive-in-both-orderings` | flake | n/a (passes alone, 20/20 assertions) | untracked | no | bulk only: `(nil? (blob/get ...))` saw an unreferenced blob after the sweep |
| `seon.blob-test/binary-content-round-trips-on-both-sides-of-the-inline-threshold` | flake | n/a (passes alone, 20 s) | untracked | no | bulk only: 232.5 s against a 60 s bound |
| `seon.blob-test/generated-binary-values-round-trip-through-staging-and-chunks` | flake | n/a (passes alone, 20 s) | untracked | no | bulk only: did not exit within its 57 s remainder; a thread kept its branch live |

Counts: 14 deterministic reds, 3 flakes, 0 environment. Deterministic reds with an owner task: 4 (`chain-intake-answer`,
two in `my.test`/`seon.test`, `background-binary-fixture-reaches-its-handler`). Untracked deterministic reds: 10.
Doomed by TASK-CHAIN §6: none.

## Surviving deterministic reds

Ten untracked deterministic reds need a task each (no owner task names them): the two `public-entries-declare-resolvable-io-capabilities`
reds (fn declarations missing workload, spec and private flags), `a-merge-is-keyed-by-the-assignment`,
`an-agents-own-test-reaches-its-cluster-through-the-elided-arity`, `adopted-contracts-govern-both-sci-and-host-calls`,
`operator-reports-the-typed-refusal-over-a-real-prepl`, `admitted-declarations-retain-call-dependencies-across-turn-order`,
`captured-run4-replies-stop-before-fabricated-responses`, `a-database-passed-for-a-connection-is-an-ordinary-argument-refusal`,
and `bounded-await-owner-has-exactly-the-declared-production-callers`. Owner-task reds are in the table.

Two root-cause patterns repeat across the table and are worth one task each: fixtures refused at the writer for missing
required attributes (`my.test-test`, `background-blob-test`), and fn declarations missing workload and spec (`my.edit-test`,
`my.fs-test`).

## Limits

- The first red commit is not identified: all 14 are red at the oldest commit checked, which is inside the last 60
  commits. Deeper history is needed before naming a turn-red commit.
- The flakes are classed by alone-pass evidence only. The bulk run's timing is contaminated by other lanes' checks on
  the shared JVM slots.
- Only the first 185 members of the bulk tier ran. Any red among the 2807 unrun members is unknown.
