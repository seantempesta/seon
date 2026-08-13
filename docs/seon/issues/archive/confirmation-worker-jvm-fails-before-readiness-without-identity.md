---
type: issue
status: resolved
severity: blocker
tags: [issue, test, runtime]
---

# Confirmation worker JVM fails before readiness without identity

## Problem

A `bin/test --all` confirmation phase aborted because one confirmation worker
JVM (`confirmation-373`) could not load `clojure.main`, and five confirmation
workers never published their test identity. The runner then emitted no final
aggregate assertion/failure/error counts, so a run whose every bulk task
executed still produced no honest tally. A worker that dies before readiness
must publish which task it carried and a typed launch-failure fact, and the
coordinator must still emit the complete aggregate with those tasks marked
unconfirmed — never abort the tally.

## Evidence

Retained root `tmp/test-runs/run.qisLgP` (2026-08-13 night, HEAD near
`66cecb816`): 71 platform + 1,127 bulk tests ran, 23 red-task confirmations
launched, nine confirmed reproducible, five confirmation workers identity-less,
`confirmation-373` failed with `clojure.main` unloadable — a classpath or
checkout-construction failure in the confirmation launch path, distinct from
the three wedge causes fixed the same day.

## Owner

`src/seon/test/runner.clj` confirmation-phase worker launch: worker identity
must be published at launch (before readiness), launch failure must be a typed
per-task fact, and the aggregate tally must be total over unconfirmed tasks.

## Acceptance

- A confirmation worker that fails before readiness leaves a typed
  launch-failure fact naming its task; the run's final tally still prints,
  with unconfirmed tasks listed.
- One class regression: an injected unlaunchable confirmation worker does not
  suppress the aggregate counts.

## Resolution

Commit `fc92ddaac` fixes the class at the runner owner:

- every confirmation launch records `confirmation-launch.edn` and emits the
  worker id plus exact task before waiting for readiness;
- a start failure, pre-readiness exit, or invalid readiness value becomes a
  typed `:seon.test.runner/worker-launch-failure` attached to that task;
- confirmation failures are represented as unconfirmed task results instead
  of escaping the confirmation phase, and the final tally is printed before
  any failing exit; and
- confirmation workers receive the coordinator JVM's already-built classpath
  through `-Scp`, so they do not construct or read a shared mutable Clojure
  CLI classpath cache.

The retained `tmp/test-runs/run.qisLgP` evidence names the second defect at
cause. Every confirmation JVM used the same writable
`workers/confirmation/.cpcache` directory. Clojure CLI `1.12.5.1654` reads and
writes the cache's `.cp` file without a lock or atomic publication boundary;
concurrent confirmation launches could therefore consume a partial classpath.
That accounts for both the early missing `clojure.main` and the later missing
Jackson class. The retained final `.cp` contains both dependencies, and an
isolated launch from the retained checkout with that completed classpath loads
both successfully.

## Proof

- Focused: `bin/test seon.test-runner-test` ran 15 tests containing 151
  assertions, with 0 failures and 0 errors.
- Full: the single `bin/test --all` at `fc92ddaac`, retained at
  `tmp/test-runs/run.QfMToj`, ran all 71 platform and 1,128 bulk tasks and
  printed the total: 1,200 tests, 9,215 assertions, 120 failures, 33 errors.
  It listed 65 failing tests and no unconfirmed tasks.
- The same ordinal that failed in the earlier evidence now reported worker
  `confirmation-373` carrying
  `seon.cluster.turn-test/concurrent-streams-share-one-conn-test` before
  readiness, then completed and was classified reproducible.
- The class regression
  `unlaunchable-confirmation-worker-does-not-suppress-the-tally` injects a
  launch failure and asserts both the aggregate counts and the named typed
  unconfirmed task.

## Remaining gap

The runner acceptance is satisfied. The full run is honestly red at its frozen
`fc92ddaac` checkout; commits `dc6604dac` and `0a39f71d6` landed their separately
owned fixture/cluster-test corrections after that checkout was created, so
this lane does not claim those 65 task failures are resolved. Issue archival
and index reconciliation remain with the orchestrator because this lane was
explicitly prohibited from editing `docs/seon/issues/index.md`.
