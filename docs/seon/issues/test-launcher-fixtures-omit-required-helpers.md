---
type: issue
status: open
severity: friction
tags: [issue, test, operator]
---

# Test launcher fixtures omit required helper files

## Problem

The selected-path launcher regression copies selected launcher files into a
temporary repository. At HEAD `466f562e6` it omits `bin/_test-slot`, which
`bin/test` now sources. The fixture fails before testing snapshot behavior.

## Evidence

2026-09-15 doc/dir platform gate, selected paths `src/seon/db.clj` and
`test/seon/db_test.clj`: 85 tests / 514 assertions / 6 failures / 0 errors.
All six failures are
`seon.test-runner-test/selected-paths-overlay-head-for-preparation-and-every-worker`.
The subprocess output is exactly:

```text
bin/test: line 405: bin/_test-slot: No such file or directory
```

The concurrent test-provenance lane's uncommitted fixture change copies the
complete bin directory. It was correctly excluded by the owned-path snapshot.
[Its landing note](../../prds/steward-platform/research/test-provenance-landing-2026-09-15.md)
already records that repair in progress. This issue records the remaining
HEAD gate boundary, without claiming the uncommitted change is verified.

## Owner

`test/seon/test_runner_test.clj`, currently protected by test-provenance.

## Acceptance

The selected-path fixture runs the actual launcher with its complete helper
closure, reaches both snapshot-verification markers, and passes in the
platform gate. Do not relax the snapshot assertions.
