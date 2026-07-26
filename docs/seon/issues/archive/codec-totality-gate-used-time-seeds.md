---
type: issue
status: resolved
severity: blocker
tags: [issue, database, schema, test]
---

# Pin the codec-totality gate seed

## Problem

The full CLJS gate generated a fresh test.check seed for every run. Identical
source produced 83 failures at 22:03 and 85 at 22:55, so the suite could not
distinguish a regression from a different sample.

## Evidence

The retained reports
`tmp/test-cljs-20260725-220339-12545.report.edn` and
`tmp/test-cljs-20260725-225154-55575.report.edn` differ only in
`seon.db.codec-totality-test/every-registered-wire-shape-is-total-and-round-trips`.
The property moved from 62 to 64 failures while every other failure and error
was identical.

Commit `1fbbc7b8e` pins seed `424242` and includes it in the failure report.
After the schema repair, unchanged full runs
`tmp/test-cljs-20260725-234245-10131.report.edn` and
`tmp/test-cljs-20260725-234528-12850.report.edn` both report 1,304 tests,
6,697 assertions, zero failures, and one authorized R28 error.

## Owner

`seon.db.codec-totality-test/every-registered-wire-shape-is-total-and-round-trips`
owns the deterministic gate sample.

## Acceptance

Two full CLJS runs against one unchanged bundle produce the same test,
assertion, failure, and error counts, and a counterexample prints a replayable
seed.
