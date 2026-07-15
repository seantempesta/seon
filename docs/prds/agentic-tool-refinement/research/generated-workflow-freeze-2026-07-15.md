---
type: research
status: active
tags: [research, agent, milestone, database, schema]
---

# Generated workflow freeze — 2026-07-15

## Purpose

This is the durable resumption record for the generated database and namespace
workflow unit. It separates source that exists in the shared working tree from
proof that has actually passed. Do not infer P0 readiness from an implementation
checkbox alone.

The unit owns deterministic generated Seon workflow variants, their structured
milestone oracles, explicit serial static-ACME milestone execution, and the
smallest tier-safe positional projection in the existing freeze runner. It does
not own source admission, the task catalog, provider configuration, cluster
lifecycle, or planning restart semantics.

## Dependency ledger

- `seon_inspect.generators` is the one generated-row owner. Development uses
  seed `1`, aggregate milestone uses seed `2`, and formal blind generation mints
  a fresh 256-bit seed only when the blind run opens.
- `seon_inspect.milestone` owns the structured database and namespace verdicts.
  Generated data changes oracle values, not the capability contract or scorer
  family.
- `seon_inspect.tasks.milestone_lift` is the existing Inspect task boundary.
  Its pod path targets one explicit serial ACME URL and consumes the response's
  request-scoped evidence; it neither opens a writer endpoint nor calls the
  unavailable ephemeral cluster lease API.
- `seon_inspect.solver._record_result` is the current canonical projection of
  `/agents/run` output into Inspect task metadata. Ordered turns and the final
  database coordinate remain native Inspect evidence.
- `POST /agents/run` returns the exact request-scoped eval projection from the
  pod's final immutable database value. Static execution consumes that response
  directly and never opens a writer REPL or creates, restarts, resets, or
  destroys a cluster.
- `seon_inspect.freeze` and `evals/datasets.lock` remain the only tier and
  dataset-lock owners. Upstream Inspect positional filtering remains grounded in
  `reference-code/inspect-ai/src/inspect_ai/_eval/task/util.py` and
  `reference-code/inspect-ai/src/inspect_ai/_eval/task/run.py`.
- Focused proof belongs in `test_milestone.py`, `test_tool_generators.py`, and
  `test_freeze.py`. No broad suite or live cluster operation belongs in this
  implementation unit.

## Falsifiable failure

Before this unit, `milestone_lift(endpoint="pod")` called
`pod_milestone_driver`, which called `create_cluster`. The result was
`ClusterLeaseUnavailable`, so the task was not ready for the live static ACME
target at `http://127.0.0.1:7994`. The fixed `db` and `namespaces` prompts also
could not demonstrate generalization because development and milestone reused
the same prompt and answer.

Acceptance requires all of the following:

- database and namespace rows are deterministic for a supplied seed and differ
  between seeds `1` and `2`;
- each generated row carries only the structured oracle facts required by the
  existing milestone scorer;
- the task requires an explicit static pod coordinate and performs no cluster
  lifecycle operation;
- pod reply, ordered turn bundle, final database coordinate, and bounded writer
  evidence reach native Inspect metadata and the existing scorer;
- development bytes and development/milestone SHA-256 values are recorded in
  the one `evals/datasets.lock`;
- positional selection occurs inside `run_split` and does not make milestone or
  blind IDs iterable or printable; and
- focused tests pass without opening or generating formal blind data.

## Current working-tree state

Implemented but not yet graduated:

- `database_workflow` generates a goal-stated schema, transact, later
  query/aggregate, and final-report task with a sample-specific identity
  attribute, measure attribute, and expected answer.
- `namespace_workflow` generates a goal-stated namespace-movement,
  dependency-load, definition/refinement, database-sum, cross-namespace call,
  and final-report task with sample-specific namespaces, symbols, and numeric
  answers.
- `check_store_recall` and `check_ns_movement` accept sample oracle data while
  retaining the fixed regression rows as defaults.
- `pod_milestone_driver` accepts an explicit `cluster_url`, calls the ordinary
  pod bridge, and consumes its database-derived evaluation evidence. It owns
  neither a writer-REPL parser nor ephemeral cluster lifecycle.
- `milestone_lift` accepts generated seed/position selection and an explicit
  static pod coordinate. Its absence fails task construction rather than
  silently entering the broken lease path.
- `run_split` accepts a positional subset and selects internally. The public
  milestone and blind split representations remain aggregate-only.
- fresh formal generated seeds use a 256-bit range. This source change is not
  permission to open the blind tier during development.

Verified offline:

- `evals/database_workflow.dev.jsonl` is byte-identical to the seed `1`
  generator result. Its development SHA-256 is
  `5b8b7d1573ad1f6f8d377635efa87900ea667cde1cfee323d5b76ff6754cee8e`;
  its seed `2` aggregate milestone SHA-256 is
  `03e76adf9b8864562fd7767648283c07ed55696b5bdb12d63f302c3ec74780f5`.
- `evals/namespace_workflow.dev.jsonl` is byte-identical to the seed `1`
  generator result. Its development SHA-256 is
  `24340d31f4c80ff30fdcfa9dd72433f9424046f1ab5729b4b3271d0bfbd26742`;
  its seed `2` aggregate milestone SHA-256 is
  `44d0c98d3add18dbf5acba845acf7e752f9f43482d8526a84efa6fd0a61a0c23`.
- Both generated entries live in the existing `evals/datasets.lock` with
  development seed `1`, milestone seed `2`, one epoch, and
  `test_seed="fresh-per-draw"`.
- The focused milestone, generator, freeze, and canary gate passes 115 tests in
  2.16 seconds. It covers seed determinism and separation, goal-stated task
  text, JSON/artifact/lock byte identity, oracle good/bad behavior, explicit
  static coordinates with no lifecycle call, positional projection, held-out
  representation discipline, and canary absence.
- Python compilation, direct artifact/hash verification, and `git diff
  --check` pass on owned paths.

Not yet complete:

- no native `.eval` has run against static ACME, so live ready status remains
  **no**;
- planning restart remains dependent on an operator-owned lease/static restart
  boundary and is outside this unit; and
- source-admission, catalog, task-row, and provider/model identity edits are
  concurrent work owned elsewhere and must not be staged with this unit.

## Ordered resumption boundary

1. Validate this document and review every owned diff plus the shared roadmap
   overlap.
2. Commit only the generated-workflow unit. Preserve all concurrent edits.
3. After the parent integrates P1 source/log identity and the native tool-row
   task unit, run one generated development sample serially against explicit
   static ACME coordinates and inspect its native `.eval` before attempting the
   ten-member development slice.

## Gate status

| Gate | State | Required evidence |
|---|---|---|
| Deterministic generators | verified offline | focused seed/oracle tests pass |
| Static milestone boundary | verified offline, live open | no-lifecycle test passes; one native ACME `.eval` remains |
| Tier-safe positional subset | verified offline | focused runner test passes with aggregate held-out surface |
| Dataset lock and artifacts | verified offline | byte hashes and lock coverage tests pass |
| Formal blind tier | sealed | no sample generation, IDs, prompts, targets, or canaries opened |
| P0 live readiness | no | integrated native tasks, identities, and serial ACME artifacts |
