---
type: research
status: complete
tags: [research, agent, database, cljs]
---

# Inspect graduation readiness audit

## Conclusion

The Inspect harness is healthy as an offline Python/Inspect system, but it is
not ready to claim Seon's live graduation scenarios. The complete offline
Python suite passes 498 tests with eight intentional skips. The smaller
oracle/task/source-evidence slice passes 199 tests. The real offline Inspect
runner completes all sixteen task arms and correctly distinguishes namespace
and planning good/bad fixtures, but it also exposes a concrete false-negative:
the frozen **database good** milestone scores zero. The runner exits zero
because it checks only task status, not expected metric values.

The first harness correction is therefore small and deterministic: make the
fixed database good fixture satisfy the current structured database-evidence
oracle, add its expected mean to the offline runner, and make the runner fail
when any expected good/bad score differs. Until that is fixed, “offline oracle
liveness” is too broad a claim even though the lower-level oracle server and
missing-bundle fail-loud gates work.

After that correction, the earliest live boundary is not model quality. It is
owned lifecycle. Fixed and generated namespace/database tasks can drive one
explicit static pod, but the only plan-restart driver calls
`create_cluster`, `restart_pod`, and `destroy_cluster`, all of which
deliberately raise `ClusterLeaseUnavailable`. Namespace-targeted messaging,
cross-agent reuse/repair, execution-child recovery, and pod restart are not
first-class Inspect tasks at all. Existing runtime/browser evidence does not
substitute for native Inspect logs and database-derived scoring.

## Dependency ledger

| Dependency | Selected source | Relevant maintained seam |
|---|---|---|
| Inspect AI | `reference-code/inspect-ai` at `05322696a0f784ec399ef6abbafd3d2a250ea9cc` | `Task` supports a dataset, solver, scorer, cleanup, epochs, checkpoint, and limits in `src/inspect_ai/_eval/task/task.py`; `MemoryDataset` retains ordinary samples in `dataset/_dataset.py`; `pass_at` is the native epoch reducer in `scorer/_reducer/reducer.py`; `eval()` returns native logs rather than requiring stdout parsing. |
| Inspect Evals | `reference-code/inspect-evals` at `97c99f5f6507fc5d1449fe3247f267d591f64350`, installed version `0.14.3` | Maintained tasks compose ordinary `@task` factories from datasets, solvers, scorers, limits, version, and metadata. Seon's native scenarios follow the same seam rather than implementing another runner. |
| Seon source admission | `src-inspect-ai/evaluation-sources.lock.json`, schema version 2 | Pins both dependency revisions, the OpenAI package, Python/dataset locks, and admitted Seon source paths. `source_admission.py`, `catalog.py`, and `test_admitted_run.py` prove begin/end identity and native log retention. |
| Namespace/database oracle | `src-inspect-ai/src/seon_inspect/milestone.py` and `tasks/milestone_lift.py` | Fixed contracts and seeded generated rows share one scorer over ordered eval rows and captured database operation evidence. |
| Restart-safe planning oracle | `src-inspect-ai/src/seon_inspect/planning.py` and `tasks/long_term_planning.py` | The pure trajectory scorer is complete; the live custom solver must reuse the same agent across a real pod restart and read plan/eval facts afterward. |
| Pod door | `src-inspect-ai/src/seon_inspect/solver.py` | `POST /agents/run` remains the one execution door. It records source identity, model identity, turn/eval/database evidence, and refuses infrastructure terminal states before capability scoring. |
| Owned target lifecycle | `src-inspect-ai/src/seon_inspect/cluster.py` | Static target validation exists. Per-sample create/restart/release is intentionally unavailable pending an ownership-fenced operator lease. |

The checked-out dependency SHAs exactly match the lock file. The audit used
checkout `c88865293bb3e345b03a45323a1a697fcc2bd467` and changed no runtime or
Inspect source.

## Offline evidence

### Complete Python suite

Command:

```bash
cd src-inspect-ai
.venv/bin/pytest -q
```

Result: **498 passed, 8 skipped, 13 dependency-deprecation warnings in 24.19
seconds**. The skips are optional dependency/platform selections, not failed
namespace/database/planning tests.

### Smallest capability and liveness slice

Command:

```bash
cd src-inspect-ai
.venv/bin/pytest -q \
  tests/test_liveness_gate.py \
  tests/test_oracle_scorers.py \
  tests/test_tasks_offline.py \
  tests/test_milestone.py \
  tests/test_planning.py \
  tests/test_tool_generators.py \
  tests/test_admitted_run.py
```

Result: **199 passed, 13 dependency-deprecation warnings in 9.43 seconds**.
This proves:

- a missing CLJS oracle bundle aborts loudly;
- the live bundle and Babashka oracle discriminate their golden samples;
- namespace and generated database evidence checks fail closed when operation,
  transaction order, database identity, request turn membership, or result data
  is missing or changed;
- planning rejects a wrong answer, a newly created replacement root plan,
  unfinished pre-restart leaves, and work without a model-authored plan; and
- native Inspect logs retain admitted source and model/target identity.

### Real offline Inspect task run

Command:

```bash
cd src-inspect-ai
.venv/bin/python -m seon_inspect.offline_proof
```

The command returned zero in 7.2 seconds. Expected discrimination worked for
E1, skill, ladder, namespace, and planning. The important exception was:

| Arm | Observed mean | Required |
|---|---:|---:|
| namespace good | 1.0 | 1.0 |
| namespace bad | 0.0 | 0.0 |
| database good | **0.0** | **1.0** |
| database bad | 0.0 | 0.0 |
| planning good | 1.0 | 1.0 |
| planning bad | 0.0 | 0.0 |
| planning model-authored | 1.0 | 1.0 |
| planning no-plan | 0.0 | 0.0 |

`tasks/milestone_lift.py` still supplies a minimal historical database good
fixture containing only a measure schema, one incomplete transaction, and a
query. The current `check_store_recall` correctly requires the identity and
measure schemas, all requested rows, later query, human report, completion,
and—when generated metadata is present—captured database-operation evidence.
The direct milestone tests use a complete `_DB_GOOD_ROWS` fixture, so the
stale task fixture escaped the 498-test suite. `offline_proof.main` considers
only `STATUS=` errors, so a semantically dead good arm still exits zero.

## Scenario inventory and exact missing proof

| Graduation scenario | Current implementation | Verdict | Exact missing proof |
|---|---|---|---|
| Fixed namespace movement | `milestone_lift(milestone="namespaces", seed=None)` uses one stated fixed contract, the real pod door, structured eval rows, and a discriminating scorer. | Ready to run after source freeze; not graduated. | Three consecutive live passes on one admitted static target, native logs retained, each showing movement, schema-after-move, bare require, in-place redefine, no parallel function, and exact computed report. |
| Fixed later-turn database recall | The task and scorer exist, but the task's frozen good offline fixture is stale. No current live log is retained. | **Harness incomplete.** | Repair the golden; require offline good=1/bad=0; then three consecutive live passes proving schema, one complete transaction, later captured query result from the same database, human report, and completion. |
| Generated namespace variants | `milestone_lift` maps `namespace_workflow` generator rows to the same scorer when a seed and positions are supplied. It requires a static pod and records source admission. | Structurally ready; never live-graduated. | Freeze one seed and five positions, run serially through the admitted target, require at least four of five passes, and retain each generated oracle plus native log. Do not add answer-specific scorer exceptions. |
| Generated database variants | Generator and strict structured oracle exist; tests mutate every important evidence field and prove failure. | Structurally ready after fixed-golden repair; never live-graduated. | Same five-position/4-of-5 gate, with exact transaction/query captured values and unique transaction ordering. Deterministic missing evidence must void the run, not score incorrect. |
| Plan persistence across pod restart | The generator, pure trajectory scorer, mock good/bad arms, and same-agent two-phase driver exist. | **Live blocked by harness contract.** | Supply an ownership-fenced target lease; phase 1 creates a model-authored plan, restart only the leased pod, phase 2 reuses the same agent, and final facts prove an existing pre-restart leaf completed after restart with no new root plan. Run fixed three times and five generated variants. |
| Namespace-targeted launch/message | No Inspect task names an arbitrary namespace symbol, proves one resident, sends again to that namespace, or scores convergent creation. `namespace_reachability` is an experimental single-agent/introspection suite and its root row is ID-oriented. | **Missing.** | One native task whose database evidence proves two sends to a missing namespace create one agent, both messages have explicit from/to refs, the second send reuses that resident, and the agent's first eval starts in the requested namespace. Include a simultaneous-send generated variant. |
| Cross-agent function/schema/test reuse | Runtime live evidence exists, but no Inspect dataset/scorer joins producer and consumer agent facts. | **Missing.** | Root delegates two namespace residents; producer commits one function/schema/test; consumer calls it without redefining it; scorer joins agent, namespace, eval, function, schema, test, and result facts at one final database value. |
| Cross-agent repair | No Inspect task asks another agent to fix the same qualified function in place or detects a parallel replacement. | **Missing.** | Producer introduces a deterministic defect, peer agent redefines the exact qualified function, a fresh child passes the same test, and database facts contain one function identity with later admitted source and no `v2` namespace/function. |
| Multi-agent messaging/application | The live pod solver is one sample/one agent; no task orchestrates three residents or scores their explicit from/to message graph. | **Missing.** | One custom solver drives root plus `my.orders`, `my.customers`, and `my.fulfillment`; retained facts prove explicit messages, simultaneous work, shared function use, terminal runs, and one coherent domain result. Narration is supporting evidence only. |
| Execution-child crash/recovery | No task injects a child failure. Existing scorers do not consume recovery entity/blob or child process identity. | **Missing.** | During accepted work, trigger the maintained deterministic child-failure door, prove failed turn/eval plus recovery and diagnostic blob, exactly one replacement child, no replay of the crashing eval, later successful work from database state, and no sibling interruption. |
| Pod restart outside planning | Only the blocked planning custom driver represents a mid-sample restart. Static milestone and reachability tasks cannot request one. | **Missing lifecycle seam.** | The same ownership-fenced lease must permit restart of only its pod, return a new dynamic URL, preserve database/agent identity, and guarantee cleanup via task cleanup/finally. Add a namespace/database scenario that reads its committed result after restart. |
| Native logs and scorecard | `catalog.run_native_task`, source admission, model identity, log retention, and scorecard code are tested. Existing checked-in logs stop on 2026-07-15 and do not cover the overnight namespace-resident design. | Mechanism proven; evidence missing. | Retain every new `.eval` log outside transient sample state and append mean, pass@k, latency, tokens, admitted source/model identity, and classified infrastructure failures. Capability scores publish only from fully admitted successful runs. |

## Source-level risks to settle before live runs

1. **The database golden is stale and the runner is not an assertion.** Fix
   both before spending model calls; otherwise a zero-valued good arm can be
   reported as a successful proof run.
2. **The lifecycle owner is intentionally absent.** `cluster.py` must not
   resurrect retired shell create/destroy commands. Consume one structured,
   ownership-fenced operator lease that returns cluster name, artifact digest,
   database selection, dynamic pod URL, and restart/release operations. Use
   Inspect `Task.cleanup` or the custom solver's `finally` to release exactly
   that lease.
3. **Inspect still exposes retired database terminology.** `milestone.py`,
   `solver.py`, `reachability.py`, `planning.py`, and their tests use
   `database_coordinate`, `rendered_coordinate`, and operation `coordinate`
   maps while active Seon vocabulary is database value, basis transaction,
   commit ID, connection ID, store ID, and branch. Runtime source no longer
   contains this interface. The Inspect boundary must consume the actual pod
   response's database-value fields and name their Datahike facts directly;
   do not add another translation noun to runtime.
4. **The experimental reachability task is not the new resident-agent test.**
   It scans prompt cards and a `my.agent.*` home block and asks root to discover
   a generated child ID. The current requirement addresses agents by namespace
   while retaining immutable agent ID only for history. Reuse its strict
   ordered-turn and captured-operation helpers where they still match, but do
   not rename this old scenario into namespace-targeted graduation.
5. **Pass@k cannot replace mean or deterministic admission.** Vendored Inspect
   implements pass@k as probability of at least one correct epoch; the offline
   E1 and ladder bad arms demonstrate pass@4=1.0 with mean=0.25. Graduation
   therefore requires the stated three fixed passes, four of five generated
   variants, and 100% deterministic infrastructure separately.

## Ordered implementation and proof plan

1. Correct the fixed database golden and make `offline_proof` assert expected
   reduced metrics. Re-run the 498-test suite and the sixteen-arm Inspect proof.
2. Add one ownership-fenced Inspect target lease at the operator boundary. It
   selects an already built admitted artifact, creates or assigns one isolated
   cluster database, restarts only its pod, returns refreshed endpoints, and
   idempotently releases its exact ownership. It is infrastructure, not an
   agent-visible tool or benchmark runtime.
3. Run fixed namespace and database tasks three consecutive times. Preserve
   native logs and source/model/target identity before adding scenarios.
4. Run five generated namespace and five generated database positions; require
   four of five model passes and every infrastructure admission.
5. Graduate the existing planning task through the lease: same agent, real pod
   replacement, database-derived plan/eval facts, three fixed and five
   generated runs.
6. Add one coherent multi-agent native task, not five partial harnesses. Its
   phases cover namespace-targeted launch/messages, producer reuse, peer repair,
   deterministic child failure/recovery, pod restart, and final database
   read-back. Score independent named checks from one final database value so a
   failure identifies the real missing capability.
7. Append the scorecard from retained native Inspect logs and classify all
   timeout, refusal, stub-provider, source drift, target drift, process loss,
   and missing-evidence cases as infrastructure failures rather than model
   incorrectness.

The final Inspect gate is green only when the fixed/generated thresholds pass
on one exact admitted source and model identity, every deterministic run is
valid, and the scored evidence is ordinary database/turn/eval/process data
from the same product doors used by the browser and runtime graduation.
