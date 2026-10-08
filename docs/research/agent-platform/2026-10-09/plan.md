---
title: Platform plan 2026-10-09
type: plan
status: active
created: 2026-10-09
---

# Platform plan, 2026-10-09

Goal: every platform fix is worked through the root REPL, the way a Seon agent works. That means `defn` on a branch, proving through the system's own test step and platform check, `my.program/write-branch!`, and committing with branch evidence. Wave A fixes what stops that today. Wave B is the backlog: lanes work it through the REPL as soon as A lands, and by editing files directly until then.

The successor to `../2026-10-08/overnight-plan.md`. Lane rules, stop conditions and the paid-run rules carry over unchanged.

## Wave A: gates on doing platform work through the root REPL

| # | Gate | Owner / task | State |
|---|---|---|---|
| A0 | Default runs HEAD. `write-branch!` checks files against the *published* commit's digests, so default lagging HEAD refuses write-back. | root: batched `start --head` | due after total-path and cost-reg-1 land |
| A1 | An evaluation whose run is refused before it opens returns its values | `seon.cluster.agent/a-run-refused-before-it-opens-answers-no-values` | landed 38ff88494 (live after A0) |
| A2 | A second branch can write back to a file a sibling change already landed in (rebase by declaration, not refuse) | `my.program/write-branch-refuses-a-file-a-sibling-change-already-landed-in` | lane write-rebase |
| A3 | A host-bound change can be proven from the REPL: `my.test/check-platform` runs the branch's written snapshot through `bin/seon check --at` and records the result as test evidence on the branch, and `bin/lane commit --branch-evidence` accepts it | PLATFORM-STEP slice 1 | lane check-platform |
| A4 | A check costs JVM ready + tests: run the rehearsal in parallel, and boot from a template store per base commit | `seon.operator/one-test-check-takes-74-seconds`, PLATFORM-STEP slices 0 and 2 | lane check-speed |
| A5 | An agent can redefine a file function that calls host IO (`slurp`) | `seon.sci.kernel/a-file-function-that-calls-slurp-cannot-be-redefined` | next |
| A6 | A refused `remove-ns` leaves the namespace in the context | `seon.turn.batch/a-refused-remove-ns-still-removes-the-namespace-from-the-context` | next |
| A7 | One agent's `retire-branch!` error is not delivered in another agent's reply | `seon.cluster.registry/a-retire-branch-error-repeats-in-every-reply` | next |
| A8 | An agent's own contract error is delivered, not recorded as a core fault (it turns health `:faulted`) | found by what-broke | next |

| A9 | A test the candidate or branch adds runs in the check (the template store was built from the base commit, so a new deftest is "not eligible") | `seon.operator/a-test-the-candidate-adds-is-not-run-by-the-check`, `seon.test.platform/a-test-the-branch-adds-is-not-eligible-in-the-check` | lane check-new-tests |
| A10 | Test namespaces have their publics and aliases in an agent's SCI context, so a proof can be written at the REPL | `seon.sci.eval/a-test-namespace-is-empty-in-an-agents-context` | lane test-ns-context |
| A11 | A host-bound redefinition runs its reaching tests through `check-platform`, not in SCI. The SCI run took 10.5 s for 115 tests, meant nothing, and drove heap to 3.2 GB. A platform check returns to the REPL past `eval_clj`'s 32 s, and a refusal names its diagnostic. | `seon.turn/a-host-bound-redefinition-runs-its-reaching-tests-in-sci`, `seon.mcp/eval-clj-advertises-no-timeout`, `seon.test.platform/a-refused-platform-check-names-no-diagnostic` | lane platform-route |
| A12 | A declaration's stored source can be transformed at the REPL (rewrite-clj), not retyped whole | to file | next |

The first acceptance run (lane repl-only-a5) broke at A9, A10 and A11; tasks are in 9cb8aa8fb. A5 takes option (b): declare `slurp`/`spit`/`file-seq` as `:seon.sci.binding/target` entries and delete `seon.sci.kernel/host-function-tools`. Its candidate sits on branch `a5-io`.

**Done when** one platform task from wave B is taken from red to committed by a lane working only through `eval_clj`, `write-branch!`, `my.test/check-platform` and `bin/lane commit --branch-evidence`. Every friction met on the way becomes a task.

## Wave B: known platform fixes (through the REPL once A lands)

Cost, by pattern (`../2026-10-08/cost-design/merged.md`):
- `seon.batch/a-request-cost-counts-only-the-evaluation-arm` (P6; a reported 22 MB was really 1 GB). This comes first, because it measures everything else.
- `seon.cluster.store/the-node-cache-counts-nodes-not-bytes` (P5; drives the cold rereads and the `write-branch!` cost).
- `my.test/check` costs about 400 MB per newly run test, and a cold `reach-digests` 499 MB (P1; cards CO-4/CO-5).
- `seon.sci.eval/a-fork-snapshots-every-program-var` (P1).
- Empty-store publication doubled from 37 s to 68 s (lane publish-bisect).
- A root request that defines nothing still pays about 0.8 s of test step. A trivial JVM-mode recording takes 236 ms, against a 100 ms rule.
- The printer realizes 3,201 elements to show about 1,250.

Storage:
- First collection of the 788k-file store; an idle writer at about 9.5 files/s (lane store-gc).
- Killed checks leave disposable roots behind (check-speed sweeps the verified-dead ones).

Proof and regression:
- COST-REG: slice 1 stores each test's cost (lane cost-reg-1); slice 2 turns a cost over baseline into a red result; slice 3 takes the baseline at assignment.
- Contract task generator and guide (lane contract-gen); a pilot of 3 DeepSeek agents, then batches.
- `seon.test.runner/tests-reaching-misses-seon-id-id-from-its-calling-test`.
- `seon.instrument/a-call-missing-an-argument-is-refused-as-the-wrong-argument`.
- `my.test/check` refuses `:seon.test/tests` with a fix text that names the wrong key.

Regressions with known commits (what-broke):
- refused deletion (65cf8c26d);
- lost model call (CX-1);
- reset CLI stdout;
- a stop during context assembly;
- overlay-world reuse;
- rewrite the `with-redefs` prompt test, which is still a task's only proof.

## Order

1. Now: A2 and A3 run beside A4 and the running lanes.
2. A0 restart, batched: once total-path (landed) and cost-reg-1 land. It also takes in trivial-calls, reach-transitive, the profiler fix and the five fan-out 9 merges.
3. A5–A8, file-disjoint, as lanes free up.
4. Wave A acceptance: one wave B fix done entirely through the REPL.
5. Wave B by pattern, through the REPL. Contract back-fill batches run beside it as DeepSeek fan-outs.
