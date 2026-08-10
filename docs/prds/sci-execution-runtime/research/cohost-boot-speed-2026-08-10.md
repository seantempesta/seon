---
type: research
status: complete
tags: [runtime, boot, performance, testing]
---

# Co-hosted boot speed measurement

## Question

Why did
`seon.cluster.cohost-boot-test/a-second-cluster-boots-under-the-first-cluster-s-instrumentation`
consume 2,616.8 seconds in the 2026-08-10 complete gate, and which term belongs
to the co-hosted production boot rather than the test fixture?

## Dependency ledger

- Datahike commit `10540578248eaa686c1f88a7fe57644ee4c9f993` owns wildcard
  pull and history database semantics. `reference-code/datahike/src/datahike/pull_api.cljc:323-420`
  shows that `[*]` expands the selected entity's complete EAVT slice;
  `reference-code/datahike/src/datahike/api/impl.cljc:185-196` owns the temporal
  history database value.
- Seon's exact provenance reconciliation is `src/seon/reconcile.cljc:336-427`;
  configuration calls it from `src/seon/config.clj:431-466`.
- The production boot phases are published by `src/seon/cluster.clj:2360-2419`.
  The regression reproduces the operator ordering in
  `test/seon/cluster/cohost_boot_test.clj`.
- Malli instrumentation is applied through `src/seon/instrument.clj`; the
  regression intentionally leaves those wrappers live for the second boot.

## Reproduction

The first isolated namespace run on the current 2026-08-10 tree, before this
change, was:

```text
bin/test seon.cluster.cohost-boot-test
1 test, 88 assertions, 0 failures, 0 errors
real 145.19 s
```

A project-local probe bound `seon.cluster/*source-progress!*` and
`seon.cluster/*boot-progress!*`, timed publication, both boots,
instrumentation, and teardown, and recorded the second boot with JFR. It used
its own operator roots under `tmp/`; it did not address or mutate the
concurrent complete-gate process.

| Phase | First cluster | Instrumented second cluster |
|---|---:|---:|
| Complete source publication fixture | 42.985 s | — |
| Whole boot | 6.519 s | 55.405 s |
| Branch to recovery | — | 4.283 s |
| Recovery to config | — | 36.379 s |
| Config to program | — | 14.032 s |

A virtual-thread-aware dump during the 36.379-second interval put the main
thread at `seon.reconcile/plan-transaction-data`, called by
`seon.config/apply-compiled!`, repeatedly executing instrumented
`seon.db/pull` with selector `[*]`. A direct function probe made the scale
visible: one converged configuration plan pulled 11,164 identity-bearing
entities even though exactly one belonged to the managing process.

## Cause

`plan-transaction-data` discovered provenance ownership, but wildcard-pulled
every identity-bearing entity before using that ownership. On a source fork,
that made a small configuration reconciliation inspect the complete program
graph. Process-wide Malli instrumentation multiplied each irrelevant pull's
validation and schema work, exposing the defect on the co-hosted second boot.

The wrong state was representable because the pull input was
`(keys entity-identities)`, the global identity census. Reconciliation can
inspect or change only `managed-eids`; provenance derives that set before any
entity value is needed.

## Correction and class proof

`src/seon/reconcile.cljc` now refuses a desired identity outside the managed
scope first, then wildcard-pulls only `managed-eids`. The regression in
`test/seon/reconcile_test.clj` creates one managed configuration row and 20
foreign rows and asserts that a converged plan pulls exactly the managed
entity. It is a structural selection assertion, not a wall-clock threshold.

The direct probe changed from 11,164 pulls to one. The reconciliation suite is
green: 9 tests, 22 assertions, zero failures and errors.

## After measurement

The same phase probe after the correction measured:

| Phase | Before | After | Change |
|---|---:|---:|---:|
| Instrumented second boot | 55.405 s | 21.663 s | -33.742 s |
| Recovery to config | 36.379 s | 0.561 s | -35.818 s |
| Config to program | 14.032 s | 15.761 s | +1.729 s |

The isolated test changed from 145.19 seconds to 124.05 seconds, a 21.14-second
wall-time saving under concurrent machine load. Its body changed from about
130.81 seconds to 110.44 seconds. The phase measurement is the cleaner
production attribution because source publication and teardown varied with
machine load.

The historical 2,616.8-second full-gate test was recorded at commit
`e85b847f5`, before `f098bbdc7` made database-attribute derivation resolve its
schema population once. That historical log publishes no phases between test
begin and the first cluster's ready line, so its roughly 42-minute pre-A term
cannot honestly be assigned from the log alone. On the current tree, complete
publication reproduces in 43-92 seconds rather than 42 minutes.

The required changed gate loaded all 120 test namespaces before running the
platform tier, matching the historical full-suite load shape. In that run the
test completed in 71.31 seconds: cluster A became ready after 46.94 seconds and
cluster B became ready 20.41 seconds later. Replacing the historical test time
with that result projects a 2,545.49-second (42.42-minute) complete-suite saving
across both landings. The measured saving attributable specifically to this
reconciliation correction is 21.14 seconds in the isolated end-to-end pair, or
33.74 seconds at the co-hosted production boot itself.

The changed gate passed 106 tests and 632 assertions with zero failures and
errors. It included the cohost regression, the reconciliation suite, all 70
platform tests, and 36 bulk tests reaching the three changed Clojure paths.

## Remaining boundary

The second boot's remaining roughly 16-second config-to-program phase is
mostly instrumented schema candidate-registry work. It no longer trips the
30-second silence backstop in this reproduction, but the issue's separate
architectural requirement still stands: observable boot progress/readiness,
not a silence clock, must determine operator success.
