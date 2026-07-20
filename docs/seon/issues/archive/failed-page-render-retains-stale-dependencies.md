---
type: issue
status: resolved
severity: blocker
tags: [issue, web, database, flow]
---

# Widen failed page renders to all dependencies

## Problem

A failed Datastar page render retained the subscription's prior narrow
dependency set. A later transaction that repaired the page could therefore be
filtered out forever.

## Resolution

`seon.reactive` owns the normalized computation transition. Every failed,
malformed, or rejected computation completes with the existing absorbing
dependency value `:all`; a later successful computation atomically replaces
that interest with its Datahike-owned read evidence. Datastar consumes the
ordinary reactive value and does not own a second recovery path.

The focused regression begins with a successful narrow plan, delivers a failed
page value, proves the installed interest widens to `:all`, drives a disjoint
repair event, and proves the repaired value narrows back to exact evidence. It
passes in `seon.reactive-test` as part of 7 tests and 45 assertions.

A live `reactive-owner-repair` probe reproduced the same transition against
the writer. The error completed at basis transaction 536870917 with `:all`; a
real transaction at 536870918 delivered `OWNER-REPAIR:REPAIR-FINAL`, captured
the exact Datahike plan for `:seon.agent/id` and `:seon.agent/purpose`, and
advanced both completed and installed-interest basis transactions. Cleanup
returned all reactive owner counts and the writer queue to zero, followed by a
clean cluster close.

Equivalent sockets share this computation through one reactive registration;
their independent transport delivery remains the existing latest-wins
Datastar mechanism.
