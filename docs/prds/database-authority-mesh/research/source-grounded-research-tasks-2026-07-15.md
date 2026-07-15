---
type: research
status: active
tags: [research, prd, database, flow, agent]
---

# Source-grounded database authority research tasks — 2026-07-15

## Purpose

We know the product shape: one database authority, shared indexed computation,
direct database-scoped clients, isolated Bun agent compute, and an
implementation-neutral protocol. We do not yet know the optimal composition of
the existing Datahike and Konserve internals. These tasks settle that seam before
implementation and forbid parallel names for existing library concepts.

## Task 1 — prove database-value cache identity

Read Datahike `DB` equality/hash/max-tx/max-eid, query `db-cache-key`, commit IDs,
branch values, temporal wrappers, and tests. Create two independent databases
with identical facts/counts, sibling branches, speculative `db-with` values, and
raw/as-of/since/history variants. Falsify accidental cache sharing or false
separation. Decide whether `[hash max-tx max-eid]` is sufficient or must include
physical database/branch identity. Deliver the exact identity law and tests.

## Task 2 — characterize the existing query cache

Measure exact hits, weighted eviction, current-head bursts, repeated agent
queries, pull-only dependencies, wildcard/dynamic pulls, rules, variable attrs,
unrelated-transaction propagation, schema changes, and concurrency. Record which
queries cache precisely, conservatively, or not at all. Inspect cache state
directly and retain CPU/latency/allocation/weight evidence. Do not build another
cache.

## Task 3 — settle single-flight ownership

Prove whether concurrent identical misses currently compute more than once.
Compare one in-flight owner inside Datahike with coalescing in the JVM protocol
dispatcher. Preserve synchronous referentially transparent `q`, remove failed
computations, distinguish cancellation by waiter, avoid reentrant deadlock, and
never store Future/Promise values as query results. Deliver the smallest owner
that composes with Datahike's completed-result cache.

## Task 4 — map client demand to Datahike connection references

Exercise simultaneous connect/open, reference increments, final release,
release during queries/writes, crash/disconnect, reconnect during release,
configuration mismatch, branches, secondary indexes, and store shutdown. Decide
whether one protocol session acquires one existing Datahike connection reference
for its lifetime. Do not add a second database lease count unless the existing
mechanism is proven insufficient.

## Task 5 — choose coordinate or pod database-value handle

Read Seon's `seon.db.coordinate`, `at-coordinate`, and protocol schemas beside
Datahike pod `generate-db-id`, `resolve-db`, and `release-db`. Measure repeated
coordinate resolution and retained historical values. Use the existing complete
coordinate across durable/protocol boundaries; add an opaque process-local
database-value handle only if it eliminates measured work and has one explicit
release owner. Never expose both as equivalent public identities.

## Task 6 — specify authority query operations

Inventory `q`, pull/pull-many, touched entity, datoms/seek/index-range, schema,
history/as-of/since, commit/branch, KNN, and named heavy projections. For each,
define namespaced request/response data, coordinate behavior, capability,
size/time/work bounds, cancellation, cacheability, error envelope, encoding, and
future non-JVM conformance. Extend the one `seon.db.protocol`; do not create a
query-service protocol.

## Task 7 — settle listener and remote-session ownership

Read keyed Datahike `listen!`/`unlisten!`, listener callback restrictions, Seon
replica/feed listeners, and core.async pub/sub only as a falsifier. Compare one
Datahike listener per database that multiplexes session interests with one
listener per session. Prove idempotent reconnect, disconnect cleanup,
backpressure, and that a slow consumer cannot block writer publication. Derive
“subscribed” from owned keys; never store an active flag.

## Task 8 — couple final release to scoped cache eviction

Determine what query-cache entries retain, add database-scoped bucket eviction
if necessary, and order shutdown: stop admission, drain accepted writes, remove
listeners, finish/cancel in-flight queries, evict scoped query/encoding results,
close secondary indexes, release Konserve, then delete the connection. Prove a
concurrent reconnect cannot be evicted by stale release work. Never globally
clear unrelated databases.

## Task 9 — separate and measure every cache/resource layer

Measure Datahike query plans/results, Konserve store nodes, lazy index nodes,
secondary indexes, encoded response bytes, and in-flight computations
independently. Determine which are shared by physical store or branch and which
release at final connection close. Define per-database and global budgets from
retained structural weight/RSS/allocation evidence, not one umbrella “cache.”

## Task 10 — prove the CLJ/CLJS interface

Inventory every synchronous `seon.db` reader. Define local operations over an
immutable Datahike value and honestly asynchronous authority operations for Bun
children. Prove `^:async`/`await`, eval auto-await, cancellation, one-coordinate
turn/render consistency, errors-as-values, and whether the UI retains one local
replica. Keep identical namespaced request/result shapes across CLJ and CLJS;
never fake synchronous network access.

## Task 11 — prove fair multi-database concurrency

Run 1/2/4/8 physical databases with identical and adversarial workloads. Locate
writer, query executor, Konserve, secondary-index, global cache, and GC
contention. Select fair bounded scheduling that preserves one write order per
database while allowing independent reads and databases to progress. Record
tail latency, CPU, allocation, RSS, cache hit rate, queue depth, cancellation,
and starvation evidence.

## Task 12 — freeze protocol conformance

Build data-only histories and expected results for identity, schema, query,
pull, transaction, CAS fencing, history, branches, commit lookup, replay,
cancellation, errors, capabilities, listener ordering, release, and crash
recovery. Run them against the JVM/Datahike authority. These fixtures define the
semantic role a future Bun/Rust/cloud authority must satisfy without copying
Datahike's internal representation.

## Source ledger

- `reference-code/datahike/src/datahike/db.cljc`
- `reference-code/datahike/src/datahike/query.cljc`
- `reference-code/datahike/src/datahike/core.cljc`
- `reference-code/datahike/src/datahike/connections.cljc`
- `reference-code/datahike/src/datahike/connector.cljc`
- `reference-code/datahike/src/datahike/pod.clj`
- `reference-code/datahike/src/datahike/codegen/pod.clj`
- `reference-code/datahike/test/datahike/test/query_cache_test.cljc`
- `reference-code/datahike/test/datahike/test/listen_test.cljc`
- `reference-code/konserve/src/konserve/cache.cljc`
- `reference-code/konserve/src/konserve/store.cljc`
- `reference-code/bun/packages/bun-types/bun.d.ts`
- `src/seon/db.cljs`, `src/seon/db/protocol.cljc`, `src/seon/db/replica.cljs`
- `src/seon/db/server.clj`, `src/seon/db/transport/uds.cljs`

Every task records exact selected SHAs, source lines, the shortest executable
falsifier, raw evidence, settled contract, deletion consequence, and the next
dependency-ready task.
