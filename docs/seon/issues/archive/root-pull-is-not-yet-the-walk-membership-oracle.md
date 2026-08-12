---
type: issue
status: resolved
severity: blocker
tags: [issue, render, context, performance]
---

# Root pull is not yet the walk membership oracle

## Problem

W2 had a schema-derived selector and root acquisition, but `neighborhood` and
`history` still ran the old per-entity discovery alongside it.

## Acceptance

- `neighborhood` and `history` derive membership, stable order, arrivals, and
  removals from the supplied root acquisition.
- The per-entity discovery path is deleted rather than retained as a fallback.
- A cold pass uses one root pull and unchanged context acquisition uses zero
  reads.
- Forward, reverse, and component changes produce exact membership changes.

## Resolution — 2026-08-12

`bc3dfe3fd` integrated exact staleness with the membership oracle and deleted
the old `eid-of`, concrete-entity, refs/reverse scans, changed-at scans, and
transcript discovery path. `bdb7b8efc` made production root acquisition use
the carried schema projection and proved exactly one database pull;
`291681222` proves supplied `neighborhood` and `history` perform zero further
reads, including component, forward, reverse, declared-identity, and
semantic-equality cases.

The complete results are in
[W2 change-flow acceptance evidence](../../../prds/sci-execution-runtime/research/w2-change-flow-acceptance-2026-08-12.md).
The structural blocker is closed. The measured cold-latency regression is a
separate open defect: [cold root pull is slower than the four-query floor](../cold-root-pull-is-slower-than-the-four-query-floor.md).
