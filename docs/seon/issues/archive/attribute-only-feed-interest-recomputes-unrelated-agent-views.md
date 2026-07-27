---
type: issue
status: superseded
severity: friction
tags: [issue, architecture, web, database]
---

# Select entity-scoped feed interests in the writer

## Overnight triage — 2026-07-23

**FOLD-INTO-UNIT — web slice 2.** Entity-scoped interest selection and its
browser/feed cost proof are part of the JVM web-render transition.

## Problem

Read evidence for an agent feed is reduced to changed attributes. When two
agent pages both read `:seon.ai.attempt/partial-text`, every partial-text
transaction is therefore a candidate for both feeds even when only one agent
owns the changed attempt. Equality suppression prevents an unnecessary morph,
but it happens after the unrelated feed has recomputed and serialized its
complete view.

This is an accepted limitation for the streaming-descriptors unit. It belongs
to web tier slice 2 beside the C10 stale-render/invalidation family, not to the
partial-publication correctness gate.

## Evidence

`evidence-dependencies` reduces each captured Datahike dependency plan through
`dependency-plan-attributes` in `src/seon/db/writer.clj:2543-2560`.
`candidate-interests` then selects registered listeners from
`::by-attribute` for every changed datom in
`src/seon/db/writer.clj:2858-2873`. The pod installs the captured evidence as
one interest in `src/seon/reactive.cljs:327-371`, and the agent feed renders a
complete view in `src/seon/web/datastar.cljs:1093-1104`. Equality suppression
is downstream of that render.

A focused Node 26.4.0 benchmark exercised the compiled ClojureScript boundary
`seon.ui.agent-view/render-agent-view` followed by
`seon.ui.html/->string`. It used one fixed ordinary projection, 200 warm-up
iterations per scale, and timed each recomputation independently:

| Surfaces | Serialized view | Samples | p50 | p95 | p99 | Mean |
|---:|---:|---:|---:|---:|---:|---:|
| 2 | 9,110 bytes | 3,000 | 0.236 ms | 0.371 ms | 0.597 ms | 0.258 ms |
| 5 | 50,558 bytes | 3,000 | 1.094 ms | 1.478 ms | 1.663 ms | 1.151 ms |
| 10 | 181,078 bytes | 1,500 | 3.880 ms | 6.861 ms | 8.111 ms | 4.324 ms |

These figures measure the pure view derivation and serialization cost that an
unrelated wake necessarily pays; they intentionally exclude child-process
acquisition, database query time, scheduling, and socket work. The repeatable
harness and full min/max distribution are recorded in
`tmp/orchestrator/streamlane-web-measurement.txt`.

## Owner

**Corrected 2026-07-27 (n4-plan §12 R3):** the plan is attribute-only BY
CONSTRUCTION in Datahike (`query-dependency-plan` returns per-source
attribute sets and nothing else, query.cljc:2877-2903) — the owner is
the DEPENDENCY (a Datahike fork plan change), not Seon's interest
tables. Original text below is superseded.

The JVM writer's per-scope interest tables and the Datahike read-evidence
projection that feeds them. The future web-tier-slice-2 unit should preserve
attribute indexing while adding the entity constraints required to select
only affected agent-view interests.

## Acceptance

- Read evidence retains a stable entity constraint for the agent-to-open-run,
  turn, and attempt join without adding a second notification registry.
- A `:seon.ai.attempt/partial-text` transaction for agent A schedules agent
  A's feed and does not schedule agent B's feed when both are registered for
  the attribute.
- Attribute-only queries and unconstrained dependency plans retain their
  current conservative wake behavior.
- Feed replacement, historical branch identity, latest-wins scheduling, and
  equality-based morph suppression remain unchanged.
- A focused benchmark at the 50,558-byte fixture reports both candidate-count
  reduction and end-to-end avoided recomputation cost relative to the
  1.094 ms p50 and 1.478 ms p95 baseline above.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
