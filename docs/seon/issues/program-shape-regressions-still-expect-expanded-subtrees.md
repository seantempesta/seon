---
type: issue
status: open
severity: friction
created: 2026-09-23
tags: [schema-shape, testing, class/stored-derived, wave/publication-velocity]
---

# Program shape regressions still expect expanded subtrees

The authored-shape ruling removes registry expansion and duplicate subtree
strings. Two regressions outside the schema-shape lane's owned test directory
still assert the retired representation:

- `seon.program-test/empty-composite-schema-shapes-remain-canonical-and-queryable`,
  `test/seon/program_test.clj:103`: follows entries directly from a named
  request shape, then expects `row-form` to expand its registry references
  (`:132–143`). The authored request is `:seon.reconcile/request`; consumers
  needing its map use the compiled registry.
- `seon.program-test/regex-rest-tail-is-complete-and-element-is-only-derived-when-proven`,
  `test/seon/program_test.clj:246`: expects complete subtree strings in
  `:seon.schema.shape/form` (`:260–271`). Assert the reconstructed authored
  form through `row-form`, and assert the stored node is only its head.

These are source-confirmed stale expectations, not a claim that the platform
suite was run. The bounded lane ran only its path-selected fast namespace;
the orchestrator owns the cold/platform proof and these out-of-scope tests.
The replacement size, roundtrip and identity regressions are in
`test/seon/fn/schema_shape_test.clj`. Measurements and the accepted semantics:
[authored shape landing note](../../prds/steward-platform/research/schema-shape-authored-2026-09-23.md).
