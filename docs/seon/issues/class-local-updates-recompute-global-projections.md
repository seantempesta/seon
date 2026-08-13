---
type: issue
status: open
severity: friction
tags: [issue, performance, render, schema, class/n9, class-kill, wave/class-kill-queue]
---

# Make local updates unable to recompute global projections

## Problem

Several local operations reconstruct or rerun a whole schema, source,
namespace, render, or run projection. Costs that should scale with the changed
dependency closure instead scale with the complete database/program graph.

## Evidence

Current open members carry `class/n9` and are derived with
`bin/issues-index --class class/n9`.

## Owner

The immutable-basis projection caches and recorded dependency edges at schema,
publication, run-loop, and render boundaries.

## Acceptance

- Derived state rides the database value/program generation that determines
  it, and invalidation is the recorded transitive dependency closure.
- Local constructors cannot call a global rebuild or full render walk.
- Cost properties show unchanged work remains constant while total graph size
  grows, and changed work scales with the affected closure.
