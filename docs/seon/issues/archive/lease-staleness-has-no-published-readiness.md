---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, runtime, database]
---

# Publish lease staleness readiness from the claim interface

## Problem

An open run becomes stealable when wall time crosses its stored
`:seon.agent.run/lease-until`, but that transition publishes no observable
event. The transaction feed cannot wake a replacement process when the
database is otherwise quiet.

The JVM driver also sets the lease only while opening the run. It does not
renew the lease while driving forms, so a healthy run that outlives the lease
can be stolen.

## Evidence

The D2 measurement killed the only process holding a run 600 ms into a
seven-form chain with a 3,000 ms lease. The run was claimable at four seconds
and remained stranded through twelve seconds and four lease periods, with one
of seven receipts, until an unrelated transaction caused a scan.

Datahike specifies that `listen` calls its callback with a transaction report
“on each transact”
(`reference-code/datahike/src/datahike/api/specification.cljc:1070-1083`).
Wall time crossing `:seon.agent.run/lease-until` is not a transaction.

The absolute lease entered with the initial JVM driver in `2cc91d508`, following
the crash-boundary design that replaced heartbeat-plus-config reads with
`:seon.agent.run/lease-until`. Subscribing to that attribute was removed from
`seon.agent.driver/start!` by `71f3cb0e0` because the driver itself writes the
attribute, creating a self-wake, while still providing no staleness event.

Before `3946b7192`, `seon.agent.driver/process-message!` set one 120-second
lease before the run opened and never renewed it. The same driver executed its
process-local parsed source vector and did not resume the committed form plan.

## Owner

The claim interface in `seon.agent.driver` must publish its own liveness and
readiness. Database claim facts and CAS remain the authority; no periodic
database scan, durable readiness flag, or second scan path should be
introduced.

## Acceptance

- The operation that acquires or renews a lease publishes the observable event
  a replacement process uses to arm exactly one staleness readiness transition.
- A healthy long drive renews its lease under the existing run fence.
- Killing the process holding a run while the database is otherwise quiet
  causes a replacement to claim and resume the committed next form.
- The primary path is event-driven. If a clock backstop is still required, its
  firing logs a core bug and names the missing observable event it stands in
  for.
- No listener wake attribute is committed by work that listener starts.

## Resolution

Commit `3946b7192` closes both halves in the existing driver.

Each nonterminal form now adds `run/renew-tx-data` to the same transaction that
terminalizes its receipt. The duration comes from the database config singleton
at `:seon.config.watchdog/stale-ms`; absence uses config resolution's same
1,200,000 ms default. A deterministic 3,000 ms regression settles one form at
2,000 ms, observes the lease renewed to 5,000 ms without another transaction,
and proves a competing process cannot steal at 4,000 ms after the original
lease has passed.

The existing scan also reads open runs with committed form plans. Replacement
startup observes each committed lease instant and arms one process-local
one-shot virtual thread for that exact instant. The wake calls the same
serialized scan; it is neither a periodic scan nor a second scheduler service.
The message listener remains interested only in
`:seon.agent.message/to`, so lease acquisition and renewal cannot feed back
into the wake path. Datahike's lease and epoch CAS remains the cross-process
authority.

The recurring replacement test starts from a dead process with a seven-form
plan and ordinal zero already terminal. It commits nothing while the exact
3,000 ms lease is live, claims at epoch two after **3,006 ms**, evaluates only
the six remaining forms, and closes `:completed`. The transaction count is one
claim, twelve receipt transactions, and one timing-settlement transaction.

Conditions: MacBook Pro `Mac17,6`, Apple M5 Max, 18 cores, macOS 26.5.2,
Homebrew OpenJDK 26.0.1 arm64, `-Xmx512m`, revision `3946b7192`. Direct
invocation of `seon.agent.driver-test` ran 10 tests / 57 assertions with zero
failures or errors; the separate run-core test ran 5 tests / 21 assertions
with zero failures or errors.

No production backstop remains. The only clock wait is the lease's own
committed future instant. The test's five-second latch bound stands in for its
observable completion event and fails loudly if that event is absent.

One residual is explicit: renewal occurs only at a terminal receipt boundary.
A single eval or blocking host call whose uninterrupted wall span exceeds the
configured **1,200,000 ms (20 minute)** lease cannot renew from inside that
form and is stealable after the lease instant. The configured lease is above
the declared fifteen-minute turn horizon, and ordinary SCI compute has a
shorter time limit, but an unobservable blocked host call can still expose this
edge.

`bin/test-writer` remains unrun because the owner froze the shared artifact
build until the audit lanes land. Both regressions live in
`test/seon/agent/driver_test.clj`, which that runner claims; the direct
invocations above are the current proof.
