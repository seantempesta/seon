---
type: research
status: active
tags: [research, agent, milestone, capability]
---

# Inspect tool-row tasks — 2026-07-15

## Dependency ledger

- Inspect AI is selected from `reference-code/inspect-ai/` at
  `05322696a0f784ec399ef6abbafd3d2a250ea9cc` by the existing local source
  dependency. `inspect_ai.dataset.Sample` owns stable task input, target,
  metadata, and sample identity. `inspect_ai.Task` owns dataset, solver,
  scorer, and post-score cleanup. `_eval/task/run.py` filters exact sample IDs,
  runs the solver before the scorer, copies final `TaskState.metadata` into the
  native sample log, and calls task cleanup after scoring.
- `inspect_ai.solver.TaskState` owns the mutable user prompt, output, sample
  UUID, epoch, and metadata. A registered solver is an async
  `TaskState -> TaskState` transformation. The existing
  `seon_inspect.solver.seon_pod_solver` remains the only static-pod bridge and
  preserves pod/database/turn evidence in final state metadata.
- `inspect_ai.scorer.Score` plus `accuracy()` remains the verdict mechanism.
  `seon_inspect.tool_scorers.workspace_scorer` and
  `fixture_answer_scorer` already adapt the pure `check_workspace` and
  `check_answer` outcome functions to that mechanism.
- `seon_inspect.generators.generate_rows`, `materialize_setup`,
  `render_input`, and `serve_fixtures` remain the dataset and setup owners.
  The selected development artifacts and their hashes in
  `evals/datasets.lock` must remain byte-identical.
- First-party examples are `tasks/long_term_planning.py` and
  `tasks/milestone_lift.py`. Focused behavioral coverage belongs in a new test
  beside `test_tasks_offline.py` and `test_tool_rows.py`; no cluster is needed
  because the task accepts its pod solver as an internal construction seam.

## Failure and acceptance

The frozen shell, file, and web rows have real generators and outcome scorers,
but scored execution currently enters through `run_tool_row`, which writes
custom scorecard records instead of a native Inspect `.eval`. This contradicts
the lane rule that Inspect owns every simulation and prevents the ten-member
P0 slice in [[inspect-suite-freeze-2026-07-15]] from being one task system.

Acceptance is one registered Inspect task family that:

- selects exact deterministic generator positions without changing generated
  bytes;
- materializes a UUID-isolated project-local workspace, substitutes the real
  workspace or loopback fixture URL into the Inspect user prompt, and delegates
  to `seon_pod_solver`;
- uses the existing outcome scorers unchanged;
- records the final rendered prompt, pod reply, database coordinate, and turn
  evidence through ordinary Inspect state and native `.eval` logging;
- treats a pod timeout or core-error close as an evaluation error rather than
  a model capability miss; and
- removes its invocation-local workspace only after Inspect has scored it.

No catalog, provider dependency, cluster lifecycle, or generator byte is in
this unit.

## Result

`seon_inspect.tasks.frozen_tool_rows` is one registered task family for
`shell_use`, `file_edit`, and `web_fetch`. Its dataset is a positional
projection of `generate_rows`; selection preserves caller order and fails on
empty, negative, duplicate, or malformed positions. Each generated metadata
map is copied before Inspect receives it.

The task requires an explicit `cluster_url`. Its solver constructs only
`seon_pod_solver`; it neither imports nor calls `ephemeral_cluster`. A
task-local semaphore keeps the static pod serial even if an Inspect caller
mistakenly requests sample concurrency greater than one.

Every invocation uses `tmp/inspect-tool-rows/<Inspect sample UUID>` unless the
caller selects another project-local root. Initial files are materialized
there, and the real absolute workspace or ephemeral loopback fixture URL
replaces the placeholder in `state.user_prompt`. The fixture server remains
alive for the entire awaited pod call. Inspect cleanup removes only that UUID
directory after scoring, preventing a later sample or epoch from passing on
stale output.

The final state retains the ordinary pod evidence fields, including database
coordinate and ordered turn evidence. A timeout or `:error` close raises
`ToolRowInfrastructureError`, producing an errored native sample with no
capability score. Successful runs flow unchanged into `workspace_scorer` or
`fixture_answer_scorer`.

The former `run_tool_sample`, `run_tool_row`, and
`run_planning_sample_live` functions had no production callers; repository
search found only their own tests and historical evidence. They and their
tests are removed. `tool_rows.py` now contains only the blob-copy helper still
used by the existing pod-backed solver and planning evidence path. The native
`long_term_planning` task remains its scored owner.

## Native invocation

One development member runs against the explicitly owned static ACME target
as follows:

```bash
cd src-inspect-ai
.venv/bin/inspect eval \
  src/seon_inspect/tasks/frozen_tool_rows.py@frozen_tool_rows \
  -T row=shell_use -T seed=1 -T positions=0 \
  -T cluster_url=http://127.0.0.1:7994 \
  --model mockllm/model --max-samples=1 --display plain
```

The task semaphore makes `--max-samples=1` redundant for safety, but keeping
it explicit makes the P0 serial contract visible in the native run record and
operator command.

## Focused evidence

- Fourteen new tests run real `inspect_ai.eval` calls with an injected pod
  solver. All three rows produce native `.eval` files, preserve rendered
  prompts and pod/database/turn metadata, use the existing outcome scorers,
  and delete their UUID workspaces after scoring.
- The same tests prove an untouched workspace scores incorrect, while timeout
  and core-error closes make the native log error with no score. A two-sample
  run configured with `max_samples=2` observes maximum pod concurrency one.
- The focused task, planning, pod-solver, and oracle checkpoint passes 84
  tests. The generated development bytes still match the existing lock:
  `shell_use` `829d5f77...31bdc`, `file_edit` `4d4c2ca5...45d59`, and
  `web_fetch` `68c23d24...b0c`.
- Python compilation and `git diff --check` pass. No live cluster operation or
  broad suite was run.

## Shared-tree interference

A wider focused collection of `test_tool_generators.py` and `test_freeze.py`
reached 93 cases but had five failures while the independent generated-workflow
lane had added `database_workflow` and `namespace_workflow` freeze entries
without yet regenerating `evals/datasets.lock`. Those failures are outside this
unit's owned files and do not weaken the native shell, file, and web task proof.
This unit therefore does not rewrite the shared lock or wait on that lane; its
focused native-task checkpoint and byte-identity checks remain the acceptance
evidence above.
