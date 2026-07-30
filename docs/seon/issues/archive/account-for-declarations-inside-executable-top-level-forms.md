---
type: issue
status: resolved
severity: blocker
tags: [issue, reader, program-graph]
---

# Account for declarations inside executable top-level forms

## Resolution

Repository indexing no longer depends on `seon.sci.reader` to discover
definitions. The one clj-kondo analysis records definitions nested in
executable top-level forms. Runtime reading also resolves executable operator
identity and lifts the declaration from `(do (defn ...))` while leaving
qualified lookalikes and quoted forms inert. The recurring regression is
`declaration-and-namespace-semantics-use-resolved-operator-identity` in
`test/seon/sci/reader_test.clj`.
