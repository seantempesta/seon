---
type: issue
status: open
severity: friction
tags: [program-graph, publication, clj-kondo, call-graph]
opened: 2026-09-17
---

# The published graph shows zero out-edges for `seon.print/emit` after the protocol-impls fix

## Problem

Commit `15a35c2a7` enabled clj-kondo's `:protocol-impls` in the analyzer, yet
`default`'s published graph still reports zero `:seon.fn/calls` out-edges for
`seon.print/emit` while `clojure.core/methods` shows 25 method bodies
referencing functions such as `seon.print/emit-sequential`
(`docs/prds/steward-platform/research/call-graph-sources-and-storage-2026-09-17.md` §1, §3).
Either the cluster has not been republished since the fix (a live-proof
boundary, not a defect) or the multimethod dispatch edge is still not
attributed. Verify after the next complete publication; if the edge is still
absent, the fix is the span-join attribution slice
(`call-graph-fidelity-fix` lane).
