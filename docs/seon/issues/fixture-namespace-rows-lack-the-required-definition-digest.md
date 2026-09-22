---
type: issue
status: open
severity: blocking
created: 2026-09-23
tags: [issue, testing, fixture, program]
---

# Fixture namespace rows lack the required definition digest

After `b51a24055` made `:seon.program/definition-digest` a required member of
program rows, the canonical helper `seon.test-support/program-row` (HEAD
`test/seon/test_support.clj:1014`, working-tree line 743) hands
`seon.fn/source-rows` a namespace row `{:seon.ns/name ...}` without a digest,
and armed contracts refuse it:
"seon.fn/source-rows refused namespace-row at [:seon.program/definition-digest]".

Observed by lane publication-lock-deletion, 2026-09-22, on a fresh base built
from the same commit (so not the stale-base class), under armed contracts:

- `seon.cluster.publication-delta-test`: `transaction-report-identities-select-only-changed-program-rows`,
  `schema-referrers-are-selected-for-arming-without-a-namespace-reload` (this
  refusal); `selected-rows-reconcile-without-a-manifest` (a separate refusal:
  "Program deletion leaves surviving referrers" for `sample.rows/removed`
  referenced by the test's own row). Identical at `1d8838629` and `87c4228f7`.
- `seon.cluster.source-test`: `source-tombstone-provenance-does-not-prevent-live-removal`
  (this refusal); `flat-scratch-write-refusal-retires-the-candidate` (the
  redefined `db/transact!` refusal no longer reaches
  `::scratch-schema-refused`; rule is nil). Identical at `a614fb898` and
  `a102a8403`.

Wanted: the helper derives the namespace row's digest with
`seon.program/definition-digest` (the owner), and the two other reds get the
three questions from the plan (deleted machinery, retired assumption, or a
surviving seam's wanted behavior).
