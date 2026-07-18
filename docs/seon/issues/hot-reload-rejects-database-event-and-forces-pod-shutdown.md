---
type: issue
status: open
severity: blocker
tags: [issue, database, cljs, flow]
---

# Make hot reload preserve a valid database session

## Problem

A Shadow build completion can cause the pod's database session to reject a
writer event, fail database initialization, and remain unavailable until the
operator forcibly stops the pod. The transport currently discards the rejected
event and its protocol validation explanation, so the actual contract mismatch
cannot be identified from the retained log.

## Evidence

The live pod log for generation
`2ef80ce6-1c6a-4522-8e26-47cf0d167702` records a build completion followed by
`Database session received an invalid event`, two database-open core faults,
and no committed publication. The next coordinated restart reported
`pod: forced reason=incomplete-application`. `seon.db.transport.uds` validates
all writer events with `seon.db.protocol/valid-response?`, but its failure data
currently contains neither the rejected response nor
`seon.db.protocol/explain-response`.

## Owner

`seon.db.transport.uds` owns validation and diagnostics for decoded protocol
events. `seon.client/shadow-build-notify!` owns the single hot-reload database
session and publication sequence.

## Acceptance

- A rejected writer event retains the exact decoded response and protocol
  validation explanation.
- A controlled build completion identifies and repairs the underlying contract
  mismatch without introducing a second session or publication path.
- Hot reload commits publication, the database session remains attached, and a
  coordinated restart stops the pod cleanly without a forced fallback.
- Focused transport and client initialization tests cover the repaired case.
