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
Malli projection building also exists. Receipt-native schema is now derived
from that registry, installed through Datahike's creation transaction, and
validated before an existing connection is published. Post-commit
instrumentation failure still does not close admission or reconstruct the
committed generation.

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

Turn capture and autocomplete export now use the same complete coordinate.
The turn open transaction writes database id, branch, containing commit id,
and t as an all-or-none group; old partial rows remain honestly
unreconstructable. Debug projection returns those facts and reports a numeric
t delta only inside one proven containing commit. Autocomplete export resolves
each point through `seon.db/at-coordinate` and emits the complete coordinate in
JSONL metadata. The focused gate passed 11 tests/73 assertions. After a public
rebuild/restart, live turn `ep2np287dio2` stored database
`54b5b7e7-51fb-3220-b079-81a81914d86f`, branch `:db`, commit
`6a56dd4d-4110-5bc4-8c4d-6235b75796bc`, and t `536870956`; resolving that
point returned t `536870956` and excluded the turn's later creation datom.

Error capture and reproduction now use the same full point. The injected
database seam returns one canonical coordinate; `seon.error/record!` projects
its four facts together, and `seon.agent.debug/repro` asynchronously resolves
the retained containing commit and cut. Old/partial rows return a typed
non-reconstructable value. The unsafe t-only writable-fork hint is omitted
until native coordinate-aware branch creation replaces physical copying. The
focused gate passed 17 tests/116 assertions. After a public rebuild/restart,
live error eid `3097` stored database
`54b5b7e7-51fb-3220-b079-81a81914d86f`, branch `:db`, commit
`6a56e16e-47c6-5d2e-82c5-907822251e3a`, and t `536870963`; repro resolved t
`536870963` and proved that the later error datom was absent from that value.

The downstream coordinate cut is complete. Historical web selectors are
all-or-none, resolve the retained containing commit, key frozen subscriptions
by the full point, and echo it in the SSE response. Public transaction and
reconcile success envelopes also return the complete point, while the hot
config-view cache keys plain decoded data by the point and retains no database
value. Focused web proof passes 36 tests/180 assertions, the combined
turn/error/autocomplete/web gate passes 64/369, state/config/envelope passes
48/235, and replica remains green at 17/93. Registry and native branch/restore
lifecycle are the next Slice 1 boundary. After a full rebuild, a converged live
reconcile returned exactly default head
`54b5b7e7-51fb-3220-b079-81a81914d86f`/`:db`/
`6a56e5fa-0caa-5574-b579-ba8be7a2ae85`/`536870971`; the config cache held
that coordinate plus the decoded map and no database value.

The receipt schema bypass is removed. `request-id` declares its identity
semantics in the canonical Malli form; the writer derives all five native
Datahike declarations from the registry snapshot. A fresh database receives
them through Datahike `:initial-tx`, while a reopened database must already
match before the registry publishes its connection. The raw declaration vector
and receipt-specific seed transaction are deleted. Datahike source and live
probes established why genesis is required here: ordinary entity data may use
schema declared in the same transaction, but transaction metadata is validated
against the schema that existed before that transaction. Receipts are
transaction metadata. The complete JVM gate passes 57 tests/337 assertions;
the relevant CLJS schema/replica gate passes 24/140. After a full restart, the
writer and pod agreed on default head
`54b5b7e7-51fb-3220-b079-81a81914d86f`/`:db`/
`6a56e85d-cd67-5c5a-a2cb-5f1aeb6ef905`/`536870974`, and live schema read-back
showed the expected string, UUID, long, string, and ref signatures with
`request-id` as a unique identity. Post-commit schema/program publication
admission is the remaining candidate boundary. Its implementation audit found
one hard predecessor: exact wrapper preparation still accepts an incomplete
multi-arity contract, after which Malli unstrument can destroy an uncovered
live arity. Enforce live/schema arity parity before mutation, then add the one
fail-closed admission and committed-generation reconstruction transition; full
reconstruction is not a safe recovery primitive before that precondition
holds.

The JVM writer-drain prerequisite is implemented in place. The UDS request
server admits each decoded request under its existing lifecycle lock, rejects
later admission once close begins, preserves the complete response for work
already admitted, and joins every accept/connection thread before returning.
Registry release now trusts maintained Datahike's awaited shutdown result:
success removes the entry, while failure retains the exact database identity
and error and cannot be reclassified as success inside that process. Deletion
stops at an unproved release, and writer/server stop project all failures as
`stopped? false`; the JVM shutdown hook emits the same result. A pre-edit
executable probe had shown the old false success and discarded identity. The
focused transport/registry/writer/server gate passes 17 tests/87 assertions,
and the complete writer checkpoint passes 62/360. Clean agent-turn quiescence
and operator coordination remain the next lifecycle boundary.

The first branch-local blob prerequisite is implemented independently of
native branch attachment. `my.blob` now consumes one validated process-local
storage view with one writable directory and ordered read-only bases. Writes
publish a unique temporary file through fsync plus atomic rename; reads search
overlay-to-base, recompute SHA-256, and refuse corrupt bytes instead of hiding
them through fallback. The five public blob functions and the database
projection are unchanged. Focused proof passes 10 tests/65 assertions; the
combined blob/turn/retry/loop/autocomplete gate compiles with zero warnings and
passes 43/245. A live default-cluster probe read source bytes through an empty
overlay without copying them, then placed corrupt bytes in the overlay and
received a false integrity envelope naming the actual digest. Supplying the
storage view from a branch launch descriptor, overlay release, promotion
materialization, and retention remain later lifecycle slices.

## Research evidence

- [[research/database-lifecycle-source-audit-2026-07-14]] — current dependency
  ledger, live probes, transition matrix, and ordered implementation slices.
- [[research/native-branch-registry-protocol-audit-2026-07-14]] — exact native
  branch attachment, registry, protocol, and deletion cutover.
- [[research/branch-local-blobs-forensic-runtime-audit-2026-07-14]] — blob
  overlays, integrity, non-autonomous runtime, and promotion materialization.
- [[research/quiesced-restart-restore-undo-audit-2026-07-14]] — planned drain,
  unexpected recovery, immutable restore intent, promotion, and undo.
- [[research/post-commit-program-admission-audit-2026-07-14]] — exact
  publication failure paths, runtime admission gates, partial Malli mutation,
  committed-generation reconstruction, readiness, and ordered proof.
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
