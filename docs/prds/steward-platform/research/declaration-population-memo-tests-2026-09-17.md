---
type: research
status: landed, cold gate owed to the orchestrator
created: 2026-09-17
tags: [research, testing, schema, performance]
---

# The declaration-population regressions measure a first resolution, explicitly

2026-09-17. Lane: declaration-population-memo-tests. Branch `steward-platform`.

## The reds

Cold gate batch 116 A (platform tier, HEAD `d65cc688c`,
`tmp/orchestrator/gate-results/batch-116.log` ~1355-1382) had exactly two
reds, one class:

- `test/seon/schema/declaration_population_test.clj:56`
  `an-operation-resolves-the-declaration-population-once` — "one explicit
  packaged resolution reads every schema resource … expected `(pos? one)`,
  actual `(not (pos? 0))`";
- `test/seon/sci/admit/declaration_population_test.clj:82`
  `an-admission-resolves-the-declaration-population-at-most-once` — same
  shape, "the acquisition measurement must read resources".

## Premise confirmed

`5e54c9ae1` ("One packaged resource population per declaration stamp") added
`packaged-population-cache` in `src/seon/schema/edn.clj` — an atom keyed on
the `seon/schemas` resource URL plus `declaration-stamp` (the sorted
`[name, length, last-modified]` of the directory's files). `packaged-forms`
reads through it. Both tests count reads at `read-schema-resource`, and both
ran in a worker JVM that had already resolved the population under the same
stamp, so the measured "first" resolution read 0 of the 185 resources. The
0 is the retention, not a missing fallback: that is exactly the class these
namespaces exist to kill — a measurement reading absence of signal as
behaviour.

The retention itself was reviewed and accepted (31 ms → 1.6 ms per
resolution); `seon.schema.edn-test/packaged-population-is-derived-once-per-resource-stamp`
already proves it. The defect was in the tests.

## The change

One new public operation in `seon.schema.edn` (`src/seon/schema/edn.clj:384`):

`forget-packaged-population!` — `{:malli/schema [:=> [:cat] :boolean]}`,
drops the retained population under the same `locking` the resolution uses
and returns whether one was held. No `with-redefs`, no private-var poke.

Both regressions now measure through that seam:

- `seon.schema.declaration-population-test` gains `reads-of-first-resolution`;
  `one` and each per-operation measurement (`seon.config/default-decisions`,
  `seon.config/default-population`) forget first, so `(= one …)` still asserts
  ONE resolution per operation rather than comparing two retention hits.
- `seon.sci.admit.declaration-population-test/one-population-reads` forgets
  before acquiring.
- Both gain a new assertion of the retention as WANTED behaviour: a second
  resolution under the same declaration stamp reads zero resources.

Every pre-existing assertion (one resolution per operation, at most one per
admission, supplied population/projection resolves nothing, the unhanded
refusal) is unchanged.

## Verification boundary

`bin/test-fast --paths src/seon/schema/edn.clj
test/seon/schema/declaration_population_test.clj
test/seon/sci/admit/declaration_population_test.clj --
seon.schema.declaration-population-test
seon.sci.admit.declaration-population-test seon.schema.edn-test`:

**23 tests, 1082 assertions, 0 failures, 0 errors** (2026-09-17T04:20:45Z).

This is the fast loop: the worker's contract arming in one JVM over a
HEAD-plus-these-paths snapshot. It is NOT the isolated gate — no per-worker
isolation, no retained run root, no automatic platform tier, no recorded
result facts. The cold proof still owed to the orchestrator is
`bin/test --paths <these three files> -- <these three namespaces>` plus
`bin/test --platform` (both namespaces are `:seon.test/platform`).
