---
title: "Card SR-E A thrown test failure stores shown text only"
type: reference
status: active
created: 2026-10-08
---

# SR-E A thrown test failure stores shown text only

Ledger row SR-E (group E). Storage rule (storage-roles §4, ledger §3.17): no second printed copy of a value (a node
tree artifact) is stored. Deletion. Lane: Sonnet.

## What exists
`seon.test.runner/prepare-failures!` (`src/seon/test/runner.clj:1407`) stages a node-tree artifact of a thrown
actual with `(blob/stage! connection (thrown-artifact thrown admission-caps))` (`:1435-1439`) under
`:seon.test.failure/actual-blob`; `thrown-artifact` is `:1388`. The shown text `:seon.test.failure/actual` already
goes through `blob/inline-or-stage` (`:1482`, SR-B).

## Files
`src/seon/test/runner.clj`, `resources/seon/schemas/seon.test.failure.edn`, `resources/seon/schemas/seon.test.report.edn`,
`test/seon/test_failure_facts_test.clj`, `test/seon/test_support_test.clj`, new `test/seon/test_failure_storage_test.clj`.

## Deleted
`thrown-artifact` (`:1388-1405`), the staging branch (`:1433-1440`), `:seon.test.failure/actual-blob` from the
selector at `:1268`; schema `:actual-blob` (`seon.test.failure.edn:14,51,73`; `seon.test.report.edn:20`); the
assertions on it in `test_failure_facts_test.clj:171-172,208` (converted to assert `:seon.test.failure/actual`) and
`test_support_test.clj:136` (converted). Report the count.

## Proof
`seon.test-failure-storage-test/thrown-actual-has-no-artifact-blob` (new file): a deftest that throws
`(ex-info "boom" {:k 1})`, run through the one runner on a test branch; the stored failure has
`:seon.test.failure/actual` (or its size+blob pair by SR-B's rule) and no `:seon.test.failure/actual-blob` key.

## Other tests reaching it
`seon.test-failure-facts-test`, `seon.test-support-test`, `seon.blob-threshold-test`.

## Cost
D: ~18 KB less per thrown failure (ledger), one fewer blob write.

## Commit
`bin/lane commit <lane> -m "SR-E: a thrown test failure stores shown text only" --test seon.test-failure-storage-test --test seon.test-failure-facts-test --test seon.test-support-test src/seon/test/runner.clj resources/seon/schemas/seon.test.failure.edn resources/seon/schemas/seon.test.report.edn test/seon/test_failure_facts_test.clj test/seon/test_support_test.clj test/seon/test_failure_storage_test.clj`

## Dependencies
SR-D (in flight, led-storage).

## Reset
None.
