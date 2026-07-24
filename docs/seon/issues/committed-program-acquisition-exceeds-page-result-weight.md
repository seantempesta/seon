---
type: issue
status: open
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
