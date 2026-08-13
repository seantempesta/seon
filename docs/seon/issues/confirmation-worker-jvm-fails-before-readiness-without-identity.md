---
type: issue
status: open
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
