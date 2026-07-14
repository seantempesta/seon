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
config reconciliation, and crash fencing exist. Candidate/native-schema
installation, fail-closed publication/reconstruction, quiesced clean restart,
canonical database coordinates, and complete history/branch transitions remain
to be grounded and implemented.

The detailed source/dependency audit is in progress. No implementation is
authorized by this scaffold; the audit must first replace broad claims with
exact owners, executable probes, failure evidence, and acceptance tests.

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
