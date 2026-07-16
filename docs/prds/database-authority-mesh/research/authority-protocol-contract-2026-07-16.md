---
type: research
status: active
tags: [research, prd, database, flow, agent]
---

# Database authority protocol contract — 2026-07-16

## Purpose

The protocol is the stable semantic seam between `seon.db` and any authority
implementation. It is ordinary namespaced data, not a Clojure `defprotocol`, a
transport abstraction, or lowest-common-denominator CRUD. The first host uses
Datahike directly; a future Bun, Rust, cloud, or platform host may implement the
same observable contract.

The contract deliberately preserves each component's strengths:

- Datahike owns immutable database values, exact committed identity, ordered
  writers, queries, cache lifecycle, cancellation, and resource evidence.
- The JVM authority resolves portable coordinates to host-local Datahike values
  and bounds parallel work across databases.
- Bun owns persistent multiplexed sessions and independently supervised agent
  children.
- `seon.db` remains the only application interface.

## Identity boundary

Every remote request has the existing `:seon.db.protocol/request-id`. Acquire
and lifecycle discovery use `:seon.db.protocol/database-name`; data operations
use the acquired attachment and a complete coordinate. Repeating database name,
attachment, and coordinate inside every `execute-many` member is redundant and
must be rejected rather than reconciled.

The wire carries the portable Datahike coordinate:

```clojure
{:seon.db.coordinate/database-id #uuid "..."
 :seon.db.coordinate/branch :db
 :seon.db.coordinate/commit-id #uuid "..."
 :seon.db.coordinate/t 42}
```

Host-local `:datahike.value/connection-id` and
`:datahike.value/generation` never cross the wire. Datahike explicitly defines
them as process-local ownership data in
`reference-code/datahike/src/datahike/db.cljc`. The authority uses them
internally to fence cache, executor, release, and asynchronous derived work.

## Operations

The minimal target surface is:

- capabilities;
- acquire/ensure, resolve head, and attachment-fenced release;
- query, pull, pull-many, bounded paged index reads, history, since, and as-of;
- coordinate-pinned non-fail-fast `execute-many`;
- transact with the existing durable request receipt;
- cancel by target request ID;
- listen/unlisten with selective interest data;
- KNN search; and
- health.

One-member execution resolves the coordinate once to one raw immutable Datahike
value. `execute-many` resolves it once and passes that identical object to every
member. Members retain their own request IDs and ordered independent outcomes;
one error does not erase completed results.

Datahike evidence stays in its native ordinary-data shape. Query cache evidence,
resource work, result count, result weight, and configured limits are not
renamed into Seon synonyms. Lazy Datoms, entities, database values, connections,
callbacks, threads, Futures, Promises, sockets, and Bun values remain inside the
host owner.

## Cancellation and mutation truth

A cancellation request has its own request ID and one target request ID. For an
identical shared query it detaches that caller; only the final interested caller
sets Datahike's cooperative computation signal. Cancelling `execute-many` stops
queued or remaining members while returning completed member outcomes.

Once Datahike accepts a transaction, cancellation or disconnect cannot claim
rollback. The durable transaction receipt remains recoverable by the same
request ID.

## Asynchronous derived data

Embedding is not part of the transaction protocol response. A primary
transaction commits and returns immediately. The authority derives committed
numeric entity IDs from Datahike's transaction report, performs provider work
through separately bounded capacity, recomposes the current complete entity,
and submits a later ordinary derived transaction only when the document still
matches.

No pending attribute, provider name, worker, queue, Future, or background-job
shape enters the database protocol. Current source-hash mismatch is sufficient
repair evidence. Datahike's per-connection writer admission is the final release
fence: an old-generation derived transaction is accepted and drained before
release returns or rejected after admission closes; it cannot commit through a
reopened generation.

## Fields to reject

- A separate member ID beside member request ID.
- Database name beside attachment on acquired data operations.
- Per-member attachment or coordinate inside `execute-many`.
- Session ID on every request; the persistent transport owns its session.
- Work-class, queue, executor, thread count, provider, or cache-generation
  fields in application requests.
- Embedding status in a transaction result.
- A boolean requesting evidence when Datahike already exposes an explicit
  evidence-capable operation.
- Host/lazy values serialized opportunistically.

## Current migration risks

The current version 2 protocol requires request identity only for transactions,
has no query/pull/index/execute-many/cancel/listen capability surface, mixes
closed and open maps, and lets KNN run without a pinned coordinate. Version 3 is
therefore a deliberate schema break, not a permissive compatibility extension.

Pull has resource budgets but does not yet prove cooperative cancellation.
History and index operations expose host or lazy values and require bounded
materialization before transport. `datahike.api/entity` is unsuitable remotely;
the protocol exposes pull instead.

## Sean's retained decisions

The semantic contract above does not settle these measured deployment choices:

- one Bun supervisor per cluster or one supervisor across clusters;
- child fate when its Bun parent dies;
- stdout/stderr and socket queued-byte limits;
- compression defaults by environment and payload size;
- when direct-query measurements justify deleting the local replica; and
- one JVM authority versus two or four authority shards on modest hardware.

Each remains an explicit product tradeoff with benchmark evidence before the
implementation is frozen.
