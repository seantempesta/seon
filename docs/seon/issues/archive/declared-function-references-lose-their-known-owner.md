---
type: issue
status: resolved
severity: friction
tags: [issue, program-graph, test-selection, seon.fn]
---

# Declared function references lose their known owner

Batch 106, `tmp/orchestrator/gate-results/batch-106.log:273`, selected
1,580 tests for the synthetic capability handler in
`seon.fn-test/declared-function-values-contribute-edges-without-arities`.
Its single declaring capability already identifies the caller. The dynamic
declaration query instead joined every mention of the reference attribute
to the handler, including the indexer that writes that attribute.

`seon.fn/declared-reference-edges` and the Datalog reach rules now share
`declared-reference-rules` (`src/seon/fn.clj:1263`): a reference stored on a
function declaration belongs to that function. Data rows without a function
identity retain conservative attribute-consumer reach. Calls and references
still contribute their full union.

The regression removes the explicit owner-to-handler call in an immutable
canonical database value and asserts the exact declared edge, Datalog edge,
and selected test. The [landing note](../../../prds/steward-platform/research/call-graph-fidelity-fix-2026-09-17.md)
records the query evidence, per-extra-test paths, and verification boundary.
