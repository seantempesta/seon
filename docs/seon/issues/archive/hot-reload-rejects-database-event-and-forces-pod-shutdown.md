---
type: issue
status: resolved
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

The retained rejected response proved the concrete mismatch: a selective
`datoms` event carried a valid `db-after`, but its raw Datahike `db-before` had
no committed-value connection identity and was encoded with `:store-id nil`.
Ordinary transaction replies already reuse the request's complete database
value; the selective-interest path instead constructed both values from the
raw transaction report. The current closed database-value schema correctly
rejects nil.

The audit also found that recursively resolved protocol validators and
explainers were retained with `defonce`, so schema declarations changed by a
later Shadow generation could remain invisible. They now compile once per
source generation from the declaration candidate, matching publication order.

After that rejection closes the session, later build completions cannot recover:
`seon.client/shadow-build-notify!` is guarded by `seon.db/attached?`, even
though the retained runtime phase and launch capability still identify the one
running runtime that must reopen its selected database.

## Owner

`seon.db.transport.uds` owns validation and diagnostics for decoded protocol
events. `seon.client/shadow-build-notify!` owns the single hot-reload database
session and publication sequence. `seon.db.protocol` owns the single validator
set and must reconstruct it from the schemas registered by each source
generation.

## Acceptance

- A rejected writer event retains the exact decoded response and protocol
  validation explanation.
- A controlled build completion identifies and repairs the underlying contract
  mismatch without introducing a second session or publication path.
- A later complete build can reopen a lost session for the retained running
  runtime; build notifications remain inactive during cold start and shutdown.
- Hot reload commits publication, the database session remains attached, and a
  coordinated restart stops the pod cleanly without a forced fallback.
- Focused transport and client initialization tests cover the repaired case.

## Resolution

Commits `61c96c43`, `3311982f`, and `52bc742b` preserve rejected-event evidence,
construct `db-before` with the same stable store ID as `db-after`, rebuild the
one protocol validator set from each source generation's declarations, and let
a retained running runtime reopen a lost session on a later complete build.
Focused proof passes schema 9/62, protocol 7/22, transport 17/65, client
initialization 7/20, and writer interests 7/57. A real source reload committed
publication and rehosted all three agents while five Datastar interests stayed
valid. The following coordinated restart reported clean pod, writer, and
watcher shutdown.
