---
type: issue
status: open
severity: friction
tags: [issue, test, recording]
---

# Moving a failing assertion conflicts with its immutable report

The duration baseline recorded run `7aa326277331`. Adding small-publication
fixture helpers moved existing assertions in `seon.cluster.source-test`.
Later fast completion `e08a64bc85c0` was refused with
`:seon.test/report-conflict`: the same expected/actual assertion content had
already been stored with its earlier line number. The reporter then could
not obtain a durable tally. Test execution still completed independently.

`src/seon/test/runner.clj:2725` derives report identity from the test symbol
and captured failure identity. The immutable comparison at `:2767` also
compares `:seon.test.failure/line`. A source move can change that line while
retaining the captured identity. The existing immutable-report regression
also requires refusal of changed content carrying the same signature; a fix
must retain that check, not make reports mutable.

Acceptance: run the same failing assertion before and after a source move,
record both observations, and preserve their source positions. Forged
content with an unchanged captured signature must still refuse. The
[test-system note](../../prds/steward-platform/research/test-system-fork-2026-09-23.md)
records the affected iteration and separates this recording refusal from
the fixture's publication results.

## B1b recurrence, 2026-09-21

Canonical request `ec0505e0706c` reproduced the same class while the tooling-lock
regression was being repaired. Failure identity
`859787847f4d53ecc034fd9529c554e133dfcc4345579a62305746f7ed081769`
and report `3279f2b65d4b` retained the same test symbol, expected and actual values,
but the current assertion was at line 192 and its immutable earlier row at 191.
`seon.test.runner/record-tx` refused with “The test execution evidence does not
authorize this transition.” The underlying BB FileLock API mistake was corrected;
canonical retry `8ed74a43d301` then recorded 2 pass / 0 fail / 0 error. This does
not resolve the recorder's source-position collision.
