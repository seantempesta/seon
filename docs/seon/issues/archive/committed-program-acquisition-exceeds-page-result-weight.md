---
type: issue
status: resolved
tags: [issue, database, runtime, flow]
severity: blocker
---

# Committed program acquisition exceeds page result weight

## Evidence

The 2026-07-23 isolated `s2fix2` boot passed watcher, writer, and host
readiness after both compiled-build inventories were admitted into the
immutable runtime root. The pod then failed during committed-program
acquisition:

```text
The database read exceeded :result-weight
(observed 63344, allowed 60000).

```

The failure was reported at `:seon.runtime.admission/stage :query` against
basis transaction `536871013`. There was no missing-file or inventory-read
failure: both immutable inventory members existed and matched their manifest
SHA-256 digests.

Evidence is retained in `tmp/orchestrator/s2fix2-gate.log` and
`logs/s2fix2/pod/e0b80e9b-5e98-4cbc-a445-1641ed432205.log`. The isolated
operator was shut down with `bin/seon down`.

## Owner

Committed-program acquisition and its page result-weight policy are owned by
the initialization/admission read path, not the build artifact membership
path.

## Acceptance

- Ground the intended page boundary in
  `seon.db.protocol/initialization-pages`, the committed-program acquisition
  query, and Datahike's result-weight accounting.
- A fresh current-corpus boot completes committed-program acquisition without
  weakening the global database read ceiling or adding an unbounded read.
- The isolated operator reaches pod readiness and is shut down cleanly.

## Resolution

Resolved by `7a1c5de68`. Committed-program acquisition still enumerates
identities in deterministic AEVT cursor pages, but expands each identity into
its variable-weight canonical row with one bounded database query. Every
request uses the same immutable database value and retains the 60,000
result-weight breaker, so corpus growth creates more bounded requests rather
than a larger payload.

The recurring CLJS regression acquires 40 synthetic functions whose aggregate
source weight exceeds 80,000, and proves that each expansion request contains
one identity, retains the breaker, and receives the identical database value.
`seon.runtime.admission-test` passed 25 tests and 141 assertions; the writer
initialization fixture passed 12 tests and 77 assertions.

The fresh isolated `acqpage` reset reached pod and web-render readiness in 294
seconds. The pod acquired 927 schema identities and 2,948 function identities,
then reported `auto-boot ready`; the operator reset exited zero and
`bin/seon down` cleanly stopped every supervised process. Evidence is retained
in `tmp/orchestrator/acqpage-gate.log`,
`logs/acqpage/pod/f3d0ad3c-17c1-49f6-b37c-a68891943e06.log`, and
`tmp/orchestrator/acqpage-down.log`.
