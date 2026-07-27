---
type: issue
status: superseded
tags: [issue, agent, cljs]
severity: friction
---

# Execution process proof seeds incomplete schema population

## Overnight triage — 2026-07-23

**FOLD-INTO-UNIT — U9 deletion.** This fixture and its nested-render proof
target the outgoing real Bun-child topology.

## Evidence

`bin/test-writer seon.execution-process-test` reaches the real Bun child, but
startup admission rejects the fixture before any invocation. The fixture's
custom initialization seeds only a small `schema-forms` subset. The current
execution artifact reconstructs the compiled `:seon.agent` schema, whose
referenced schemas are absent from that admitted population, and fails with
`:malli.core/invalid-schema`.

This independently blocks the new nested-render deadline proof. The ordinary
ClojureScript execution/render gates and complete 1,123-test/4,981-assertion
gate pass.

## Expected owner

The existing `seon.execution-process-test` fixture should consume the same
immutable program-source/schema artifact as execution-child admission rather
than maintain a hand-written schema subset.

## Acceptance

- The real process proof admits the exact current execution program without a
  second schema list.
- A nested authored synchronous renderer hits its parent deadline, retires only
  that agent's child, leaves another agent responsive, and reloads current
  program source in a replacement child.

Triage 2026-07-23 — **DISSOLVES into post-P4 child deletion**; this proof scaffolding targets the outgoing execution-child topology.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
