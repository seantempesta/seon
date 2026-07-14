---
type: prd
status: planned
tags: [prd, database, flow]
---

# Database lifecycle recovery roadmap

## Outcome

One database-native lifecycle reconstructs runtime projections after fresh
boot, config-free reopen, clean restart, unexpected crash, historical reads,
fork, restore, and undo without replaying arbitrary eval effects or consulting
a second authority.

## Current state

The JVM writer is the sole durable Datahike owner; the pod uses one immutable
replica and typed protocol. Durable receipts, bounded replay/live overlap,
config reconciliation, numeric as-of reads, and crash fencing exist. Complete
Malli projection building also exists, but receipt-native schema is still
installed through a hand-written pre-initializer path and post-commit
instrumentation failure does not close admission or reconstruct the committed
generation.

The maintained Datahike SHA already contains same-store branch/delete,
commit/branch root reads, historical-secondary-index correction, awaited
connection release, and guarded/read-back-verified force. Seon still exposes a
physical-copy fork with a new database identity; its registry, protocol, feed,
replica, runtime admission, and operator do not carry the full
`{database-id, branch, commit-id, t}` coordinate or native branch lifecycle.
Quiesced clean restart, restore/undo, branch-local blobs, and ordered multi-form
process-failure proof remain unimplemented.

The exact dependency/source audit, live probes, transition matrix, and ordered
implementation slices are in
[[research/database-lifecycle-source-audit-2026-07-14]]. Implementation remains
dependent on current runtime-reliability graduation.

## Required transition matrix

- fresh boot and converged explicit config;
- existing database reopen with no config and no write;
- failed schema/program publication reconstructed from committed facts;
- clean quiesced restart versus unexpected interrupted-run recovery;
- one `{database-id, branch, commit-id, t}` coordinate through reads, receipts,
  feeds, turns, errors, caches, and bookmarks;
- bounded as-of reads, writable same-database branches, restore, and undo;
- branch-local blob semantics and stale-writer/cursor rejection; and
- ordered multi-form execution that records every real result and fabricates
  none after process failure.

## Graduation

The complete transition matrix passes focused writer/pod tests plus destructive
default-cluster REPL, datom, restart, crash, replay, and read-back proof. Runtime
reconstruction uses committed facts and maintained Datahike primitives only;
there is no hidden manifest/runtime state, arbitrary eval replay, compatibility
path, duplicate registry, or Seon-specific physical history copy.
