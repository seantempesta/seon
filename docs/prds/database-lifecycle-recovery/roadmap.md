---
type: prd
status: active
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
[[research/database-lifecycle-source-audit-2026-07-14]]. Implementation began
after the completed runtime-reliability graduation checkpoint.

The first coordinate kernel and head handshake are implemented.
`seon.db.coordinate` owns one closed
`{database-id, branch, commit-id, t}` shape plus its stable attachment
projection. The writer's ensure response returns that point from the connected
Datahike value; the pod config consumes the writer-owned database/branch
attachment; replica diagnostics expose the canonical local head instead of a
replica-specific public progress map. Focused proof passes ten JVM tests/51
assertions and 20 CLJS tests/104 assertions. After a public rebuild/restart,
CLJ and CLJS MCP both reported database `54b5b7e7-51fb-3220-b079-81a81914d86f`,
branch `:db`, commit `6a56c20e-eb61-5cc2-b20f-90d25090eab5`, and `t`
`536870932`.

Slice 1 now carries complete coordinates through transaction responses/events,
durable-receipt recovery, frozen replay pages/cursors, replica progress, and
own-write correlation. One immutable replay commit contains every page cut;
later writes cannot move its watermark, and the writer proves an initial cursor
commit is an ancestor before replay. Focused proof passes the complete JVM gate
(55 tests/329 assertions), the replica gate (17/93), and the complete CLJS gate
(1,311/6,195). After a public rebuild/restart, the writer and replica both
reported database `54b5b7e7-51fb-3220-b079-81a81914d86f`, branch `:db`, commit
`6a56c8da-68c9-5c20-b4f4-99b6fc150056`, and `t` `536870935`; feed attachment
replayed zero transactions and became live.

The implementation audit corrected an earlier selector assumption: a Datahike
commit can contain multiple temporal cuts. `commit-id` therefore pins the
immutable containing value while `t` selects a cut inside it. `as-of` is only a
read filter; no code searches ancestry for an exact-`t` commit.

Whole-head writes now carry `expected-coordinate` through `seon.db`, the remote
writer, protocol hashing, rejection errors, and the serialized JVM comparison.
The local Datahike writer receives only the extracted `t` at its third-party
boundary. An equal `t` and commit on a different branch is rejected without a
write. The breaking protocol shape increments the durable receipt version to
2. After the second public rebuild/restart, the JVM writer, public
`seon.db/head-coordinate`, and replica status all reported database
`54b5b7e7-51fb-3220-b079-81a81914d86f`, branch `:db`, commit
`6a56cd91-5f6c-58cc-be3c-eb732741fb5b`, and `t` `536870938`; both runtimes
reported protocol version 2.

Exact historical reads now have one honest asynchronous resolver.
`seon.db/at-coordinate` loads the coordinate's immutable containing commit by
UUID through maintained Datahike, proves it belongs to the currently attached
database branch, validates the selected t inside that container, and returns
the `as-of` view. Partial coordinates, wrong attachments, missing commits, and
out-of-range cuts return structured error values. The focused CLJS proof passes
2 tests/11 assertions. After the combined public rebuild/restart, live CLJS resolved
database `54b5b7e7-51fb-3220-b079-81a81914d86f`, branch `:db`, commit
`6a56d546-9284-5854-beb5-e0902938c200`, t `536870953`; the returned view
reported the same t and queried root successfully. Changing only the branch to
`:experiment` returned a `:user-input` error value. This is the dependency for
migrating turn, error, autocomplete, and frozen-web consumers without retaining
a bare-t path.

Slice 1 remains open at the downstream boundary: turn/error capture, frozen
caches, and bookmarks still carry bare numeric basis values. Registry and
native branch lifecycle also remain later ordered slices.

## Research evidence

- [[research/database-lifecycle-source-audit-2026-07-14]] — current dependency
  ledger, live probes, transition matrix, and ordered implementation slices.
- [[research/config-schema-runtime-restoration-2026-07-12]],
  [[research/malli-runtime-schema-authority-audit-2026-07-13]], and
  [[research/db-protocol-cut-implementation-audit-2026-07-13]] — historical
  schema, reconstruction, writer/protocol, and deletion evidence.
- [[research/datahike-as-of-fork-and-restore-2026-07-12]],
  [[research/time-travel-api-implementation-audit-2026-07-12]], and
  [[research/database-runtime-responsiveness-audit-2026-07-13]] — historical
  branch, restore, coordinate, and responsiveness evidence.
- [[research/human-readable-word-ids-datahike-and-tokenization-2026-07-12]],
  [[research/local-allocation-writer-config-audit-2026-07-12]], and
  [[research/provenance-users-processes-and-ids-2026-07-12]] — identity,
  allocation, and transaction-provenance evidence.

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
