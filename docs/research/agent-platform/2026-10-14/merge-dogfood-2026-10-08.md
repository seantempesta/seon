---
type: research
status: dated evidence (moved out of docs/seon/specs/merge.md, 2026-10-14)
created: 2026-10-08
tags: [research, merge, evidence]
---
# Merge dogfood record and measured costs (2026-10-08, default at 81020da6a)

Moved from `docs/seon/specs/merge.md` §7-§9. File:line citations and function names are as of that commit; most
`my.program` verbs named here were replaced by `my.root/merge!`, `my.root/merge-requests` and `my.root/close!`.

## 7. What exists and what is missing

Exists (file:line at `81020da6a`, today's names):
- request and proofs gate and message: `my.program/request-merge!` program.clj:416, `seon.task/request-merge!` task.clj:655;
- open requests: `my.program/proposals` program.clj:473;
- comparison: `compare-branch` source.clj:824, `merge-base` source.clj:727, `three-way` program.cljc:704;
- the agent's own diff: `my.program/diff` program.clj:974, `declaration-diff` program.clj:991;
- test merge with selection and file proof: source.clj:847, program.clj:513;
- commit with its checks: source.clj:941, program.clj:547; write-back source.clj:1265;
- close: `seon.task/decide!` task.clj:679; branches: `my.agent/create-branch!` agent.clj:117, `retire-branch!`
  agent.clj:168 (renamed `branch!` and `delete-branch!`, Datahike's names).

Missing or broken, found by the dogfood (§9):

| # | Gap | Task |
|---|---|---|
| G1 | a new contracted function cannot arm on the merge branch: its proof is green on the source branch and red on the merge branch ("this context holds no Var to arm its contract on") | `seon.cluster.source/a-new-function-cannot-arm-in-the-merge-gate` |
| G2 | one registered schema on a branch makes `diff`, compare and the test merge refuse the whole comparison (`changed-identities` scope, program.cljc:700) | `seon.cluster.source/a-branch-schema-blocks-diff-and-merge` |
| G3 | the proof run cannot see a branch-registered schema, so the merge request fails | `seon.test/check-fails-on-agent-registered-schemas` |
| G4 | requesting and merging throw instead of returning their error | `my.program/merge-tools-throw-instead-of-returning` |
| G5 | a test-only new namespace throws entity-id/missing at the test merge | `seon.cluster.source/a-test-only-namespace-throws-at-prepare` |
| G6 | every `defn` rewrote its namespace row: false conflicts, ns doc stripped on merge (fixed `76daa8fd5`) | `seon.turn.batch/a-defn-rewrites-its-namespace-row` |
| G7 | root cannot diff a merge request from its own context | `my.program/root-cannot-diff-a-proposal` |
| G8 | merging cannot delete a hosted source branch (open fault) | `my.program/accept-cannot-retire-a-hosted-proposers-branch` |
| G9 | a red or refused test merge leaks its merge branch | `seon.cluster.source/a-red-prepare-leaks-its-candidate-branch` |
| G10 | the test merge costs seconds | `my.program/the-merge-prepare-costs-three-seconds-and-400k-calls` |
| G11 | no kept merge branch, `checkout!`, dangling, schema check, automatic merge, close with reason, target branch, norms | ledger §3.10 |

**Slices.** Rows of [the ledger](../schedule/agent-loop-ledger.md) §3.10 (merge), §3.16 (renames into these words) and
§3.15 (the merge request without a kind), with their proofs, order and status. Proofs assert data through MCP
`eval_clj`, on a disposable target branch or the `competency` cluster, never default's program.

## 8. Costs, measured 2026-10-08

Default at `81020da6a`, warm JVM, MCP `eval_clj` SCI, wall time by `System/nanoTime` around the call, one sample each
(today's function names). No arbitrary targets (owner 2026-10-08): each step is judged against what its work must do,
and 10× over that expectation is a defect to diagnose; AGENTS.md's 1 s and 10 s still apply.

| Step | Measured | Expected from the work |
|---|---|---|
| `proposals` | 39 ms, 212 calls | one query of the open requests at the home |
| `seon.task/assign!` | 41-54 ms (479 ms first call), ~820 calls | one checked write of the assignment |
| `request-merge!` (one proof run on the branch) | 0.46-1.05 s, 39k-159k calls | the proofs' own bodies plus one write of the request |
| compare (the diff, 4 identities) | 174 ms (JVM, `compare-branch`) | digests of the changed identities only (§2 Cost) |
| `prepare-merge!`, 1 proof | 1.7 s warm, 175k calls; 8.7 s first call (8.0 s in the run) | the tests' bodies plus one branch and one write of the changes |
| `prepare-merge!`, 1 proof + 2 reaching tests | 2.8 s, 602k calls | as above |
| `accept-merge!` refused (red proof) | 135 ms, 2.4k calls | a read of the run's results |
| `accept-merge!` accepted (3 identities) | 1.78 s, 6.2k calls | one guarded transaction of the changed rows |
| retire 10 branches | 558 ms | — |

The accept's calls are few but its wall time is 1.78 s: the time is outside armed functions (the writer's
transaction or the retire submission). Profile before ranking. Prepare's calls are dominated by
`seon.schema/projection-cache-value` (24k-89k) and storage-schema predicates, the same family task G10 names.

## 9. Dogfood record (2026-10-08)

Agents `mcp/merge-dogfood` to `…7` requested merges; reviewers `mcp/merge-root-dogfood…4` held disposable branches
off default as targets. Default's program was not changed. Every branch and merge branch was deleted; the open merge
requests were closed.

1. A branch with a registered schema could not request a merge: `request-merge!` threw `:malli.core/invalid-schema`
   (G3, G4), even after the contract stopped naming the schema.
2. A function and test in a new namespace requested green (1.05 s) and tested red on the merge branch (G1). The same
   with one function (G1 again).
3. A test-only new namespace threw at the test merge in 77 ms (G5).
4. A docstring change to `seon.task/step-summary` with a proof in `seon.task` requested (0.9 s), tested with the proof
   green and one unrelated reaching red shown (2.8 s), and merged onto the reviewer's checked-out branch (1.78 s). The
   home recorded the request accepted although the merge landed on a scratch branch: hence the target branch (§1).
   Deleting the source branch failed and recorded an open fault (G8).
5. Root's review needed JVM mode, `seon.operator.runtime/running-instances` for the store, and
   `seon.cluster.source/compare-branch` to see what a merge request changes (G7).
6. A reviewer branch made before a merge request does not see it: requests live at the home. Automatic merging and
   `merge-requests` read the home, not the reviewer's branch.
7. Test failures print to the run's `:out` through `clojure.test`'s reporter, a value printed outside the value
   printer.
