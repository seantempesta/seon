---
type: issue
status: resolved
severity: blocker
tags: [issue, render, error, agent, class/absence-as-health]
---

# A new agent's opening is interrupted by its own empty fault block

Observed 2026-09-16 on an isolated scratch cluster (`start-arms`, operator
root `tmp/start-arms-root`) while proving `seon.issue/start!`.

## Problem

`seon.error/faults-form` pulled on `(faults-input unit)` without checking
that the unit carried a value. A brand-new agent has no
`:seon.error/of-steward` datom at all, so the walk handed it nil,
`seon.db/pull`'s declared contract refused, and the throw escaped the
renderer:

```
The renderer seon.error/render-faults-ai did not return: refused.
It interrupted run f60df7bf808b.
;; seon.db/pull refused entity-id at []: expected an integer, got nil.
;; caller: seon.error (error.clj:1670)
```

The fault committer recorded `:seon.render/unknown`, the generating turn was
interrupted, and the worker closed its opening turn with ZERO evaluations —
so the first agent `seon.issue/start!` creates can never store an opening
while this holds. Absence of a fault read as a renderer failure: the exact
inverse of the usual class, and just as silent, because the worker looked
started and simply produced nothing.

## Resolution

`src/seon/error.clj` — `faults-form` emits no form when the unit carries no
fault entity. Proven live on the scratch cluster: the next worker stored an
11-evaluation opening including the restored fault read form
([landing note](../../prds/steward-platform/research/start-arms-and-wakes-2026-09-16.md)).
