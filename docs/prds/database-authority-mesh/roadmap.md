---
type: prd
status: active
tags: [prd, database, flow, agent]
---

# Database authority mesh roadmap

## Approved outcome

One protocol-defined authority deployment hosts many isolated cluster databases.
The first deployment is one JVM/Datahike process, supervised by the existing
Babashka operator, with protocol-level database assignment that preserves a
future measured move to two or four authority shards.

Each active database owns one Datahike connection, exact cache generation,
immutable indexed values, keyed listener, and ordered writer. Independent
databases commit concurrently. Query, pull, index, history, and temporal work
over captured immutable values executes concurrently through fair,
per-database admission into bounded shared capacity. Exact identical reads
compute once; unrelated work never joins that coordination.

Each active agent is a separately supervised Bun child with one direct,
persistent, multiplexed native Unix-domain socket to the authority. Bun IPC is
the cluster control plane; it is not a database broker. No Bun process embeds a
Datahike replica or owns copied Datahike indexes. The web UI also reads through
the authority. Headless and dormant clusters retain no pod, replica, listener,
or hot authority connection merely because their durable database exists.

Datahike remains transport-free. Its API specification describes capabilities;
ordinary Datahike functions execute them. Seon owns sessions, coordinates,
authorization, fair admission, request identity, framing, paging,
backpressure, provenance, fencing, and errors-as-values. Future JVM, native,
Rust, cloud, Tauri, and mobile hosts conform to the same data fixtures.

## Settled laws

- Datahike cache identity is `[connection-id generation commit-id]`.
- Only attached, durably committed raw `DB` values cache in the first cut.
  Temporal, filtered, detached, and speculative values remain uncached.
- Mutation clears cache identity; durable writer publication attaches the new
  committed identity and propagates safe completed results once.
- Final release evicts only its exact `(connection-id, generation)` completed
  and in-flight state. Stale cleanup cannot address a reconnect generation.
- Identical cacheable misses single-flight inside Datahike beside the completed
  weighted cache. Different keys, values, and databases remain parallel.
- One canceled request detaches without affecting other requests. The final
  request sets cooperative cancellation; completed immutable work may still
  enter cache under bounded policy.
- The existing `:seon.db.protocol/request-id` is the one request identity from
  Bun through Seon and Datahike cancellation. Datahike retains it only while
  the read is active; Seon's existing transaction receipts remain the sole
  durable request record.
- Datahike writes order and may batch per connection. A request receipt is not
  a promise of one distinct commit ID per transaction request.
- No global authority request FIFO exists. Per-database ready queues are
  selected fairly before acquiring shared work-class capacity.
- `execute-many` resolves one immutable coordinate once and runs independent
  query/pull/index members under aggregate and member bounds. It is transport
  composition, not another query language.
- Remote CLJS database operations are honestly asynchronous. Core paths batch;
  agent top-level eval auto-awaits; composed functions use `^:async`/`await`.
- Transit JSON is the first codec. Linear framing, semantic pages, and shared
  encoded bodies precede any codec replacement.
- `babashka.process` owns outer JVM/Bun lifecycle. `Bun.spawn` owns Bun agent
  children. GraalVM packaging is not required.

## Optimization and ownership order

1. Preserve exact identity, immutable-value consistency, transaction ordering,
   fencing, idempotency, cancellation truth, and generation-safe cleanup.
2. Compute and encode once per exact value and semantic request.
3. Retain one owner for indexes, caches, listeners, and resource lifetimes.
4. Maximize bounded parallel work across agents, values, and databases.
5. Keep query CPU, provider/embedding, KNN/native, encoding/delivery, mutation,
   and lifecycle/control as separately bounded work classes.
6. Avoid copies, broadcasts, brokers, adapter layers, event-loop hops, and fixed
   per-database threads/processes.
7. Expose coordinates, request/job IDs, cancellation, queue/cache/resource
   evidence, capabilities, and errors as ordinary namespaced data.
8. Delete every superseded replica/feed/adapter mechanism in the cut that makes
   it unreachable.

## Dependency ledger

- Datahike `940810f5` (graduated Units 1–3 atop
  `9ada755087228e10cfb179fa5779ce227a6ed220`):
  `db.cljc`, `connections.cljc`, `connector.cljc`, `core.cljc`,
  `writing.cljc`, `writer.cljc`, `query.cljc`, `resource.cljc`,
  `pull_api.cljc`, and `api/specification.cljc`.
- Konserve `b5c99bc02a7175652a610324215288b78551801f`:
  cache/store lifecycle and selected backend source.
- Bun `be77b652884b16a103cfaa4af3c1102f72f2dcd3`:
  native spawn, socket, stream, and server types/implementations.
- Babashka process 0.4.14:
  `reference-code/babashka-process` and `script/seon/dev/process.clj`.
- First-party owners:
  `src/seon/db/protocol.cljc`, `db/writer.clj`, `db/registry.clj`,
  `db/transport/uds.{clj,cljs}`, `db.cljs`, `db/replica.cljs`,
  `client.cljs`, `web/serve.cljs`, `embed.cljs`, and agent runtime owners.

Detailed evidence and falsifiers live in
[[research/architecture-recommendation-2026-07-15]],
[[research/exact-value-identity-proof-2026-07-15]],
[[research/single-flight-proof-2026-07-15]], and
[[research/multidb-execute-many-proof-2026-07-15]].

## Ordered implementation spine

### Unit 1 — exact committed Datahike cache identity — graduated

Add connection generation to the existing registry, process-local committed
cache context to raw `DB`, committed identity lookup, and generation-scoped
metrics/eviction. Clear identity on speculative mutation. Move cache propagation
from speculative construction to the writer's one durable parent-to-final-child
publication, unioning modified attributes across a writer batch.

Exit proof:

- forced legacy hash collision, two stores, sibling branches at one commit,
  reconnect, config mismatch, and stale-release barrier isolate correctly;
- only attached committed raw values cache;
- dependency invalidation and weighted bounds remain green; and
- final release retains no cache/index/connection reference for its generation.

Graduated evidence: Datahike `999e26a2` includes exact implementation and
retained proofs. The focused CLJ gate passes 111 tests and 390 assertions across
PSS, HHT, and spec configurations. It deterministically covers forced legacy
identity collision, two stores, same-commit sibling branches, config mismatch,
writer-batch attribute union and unknown-change fallback, speculative/temporal
exclusion, stale close, reconnect, weighted bookkeeping, release drain/failure,
and late-put resurrection. The maintained Node CLJS gate passes 107 tests and
838 assertions, including a native Promise lifecycle fixture for committed
identity, propagation behavior, release eviction, reconnect generation, and
stale cleanup.

### Unit 2 — Datahike single-flight and cancellation — graduated

Add one JVM-only in-flight coordinator beside the completed weighted cache.
Completed hits bypass it. The owner rechecks cache, computes once, caches only a
successful weight-certified result, delivers tagged completion, and
compare-removes in `finally`. Waiter identity, cancellation, final-waiter signal,
reentrancy bypass, bounds, and metrics remain process-local.

Exit proof:

- 2/8/32 identical misses compute once;
- different keys/values/databases make bounded parallel progress;
- failure, retry, cancellation, last waiter, reentrancy, overflow, propagation,
  release, and shutdown leave zero retained in-flight state; and
- existing synchronous CLJ/CLJS query semantics remain unchanged.

Graduated evidence: Datahike `f8192962` adds the JVM-only coordinator beside
the completed weighted cache. Completed hits bypass it; owners recheck, compute
with the shared cooperative cancellation signal, atomically fence publication
against release and explicit clear/resize epochs, and compare-remove by
completion identity. Retained proofs cover 2/8/32 identical callers, parallel
database keys, shared failure/retry, independent and final-waiter cancellation,
reentrancy, overflow state bounds, clear/release, stale-owner ABA, and real
concurrent Datahike queries. The focused CLJ gate passes 135 tests and 537
assertions across PSS, HHT, and specs. The maintained Node CLJS gate remains
green at 107 tests and 838 assertions, preserving synchronous semantics.

Remote request cancellation remains a Unit 3 capability concern: the
transport-free seam passes the same universal request ID into the
coordinator's detach operation. Unit 4 admission, rather than single-flight,
bounds unique-key computation; local synchronous overflow deliberately retains
Datahike's direct-compute behavior.

### Unit 3 — Datahike capability seam — graduated

Extend `datahike.api.specification` with exact identity, cache evidence,
cancellation, scoped release, and remote-suitable read capabilities. Keep
ordinary functions as the execution interface; do not add a Clojure protocol
until a second in-process implementation requires type dispatch. Make listener
handoff bounded and non-blocking before network or derived work.

Exit proof: one transport-free in-process fixture acquires, resolves one value,
queries, pulls, listens, cancels, transacts, reports cache evidence, and releases
without exposing a DB, connection, callback, thread, Promise, or Future in data.

Graduated evidence: Datahike `940810f5` exposes a bounded namespaced catalog
using concrete existing API facts, a namespaced committed-value identity, query
results with per-request cache/resource evidence, cancellation by the existing
universal request ID, aggregate cache evidence, and exact release evidence.
Active duplicate request IDs reject; completed reads retain no request record.
Each request has its own completion so cancellation wakes that caller without
canceling shared work; the final request sets the existing cooperative query
signal. Normal `q` remains value-compatible and does not allocate evidence
counters.

The durable writer now offers committed reports into a demand-opened bounded
source using only a constant-time generation-fenced atom update before legacy
listeners. Overflow fails closed, stale generations cannot cross reconnect,
and final connection release abandons retained reports and removes the source.
Projection, encoding, filtering, and network work remain outside Datahike's
writer path.

The integrated CLJ gate passes 174 tests and 759 assertions across PSS, HHT,
and specs. The maintained Node CLJS gate passes 107 tests and 838 assertions.
The retained in-process fixture composes capability discovery, connection/value
ownership, transaction publication, query, pull, cancellation, cache/resource
evidence, and release while recursively rejecting DBs, connections, Datoms,
functions, IDeref values, threads, futures, and throwables from returned data.

### Unit 4 — multi-database authority registry and fair work classes

Replace one-process/one-database assumptions with attachment-owned authority
entries backed by real Datahike connection references. Each database owns ready
queues and its writer. Weighted deficit round robin selects databases before
shared permits. Start with equal per-database weight and aging; reserve a small
lifecycle/cancellation lane and a background service floor.

Bound these classes independently:

- query CPU: Datalog, pull, history, index reads, execute-many members;
- blocking provider: embeddings and remote object/provider calls;
- secondary/KNN/native: vector search and index construction;
- encode/delivery: Transit, compression when configured, queued bytes;
- ordered mutation: the existing writer per connection; and
- lifecycle/control: cancel, health, capability, acquire, release.

Safe idle capacity may be borrowed under global ceilings, but query, mutation,
and control retain hard floors. Heavy database A cannot consume database B's
query, mutation, delivery, or control ownership.

Exit proof: real 2/4/8-database adversarial workloads show independent writes,
bounded query progress, no global gate, truthful cancellation, queue evidence,
and background progress while provider/KNN/large-encode capacity is saturated.

### Unit 5 — versioned authority protocol and `execute-many`

Extend the one `seon.db.protocol` with capabilities, session/acquire, exact
coordinate resolution, query/pull/index/history, execute-many, transaction,
listen/unlisten, cancel, page/continuation, cache/resource evidence, and release.
Every request and result is correlated independently. Execute-many returns each
member's outcome rather than fail-fast by default; cancellation may stop
remaining members without discarding completed results.

Transaction responses retain durable request receipts and committed
coordinates without claiming one unique commit per request. Once Datahike has
accepted a mutation, disconnect/cancellation cannot claim rollback; its receipt
remains recoverable through request identity.

Exit proof: transport-neutral fixtures cover schemas, values, identity,
query/pull/history, branches, fencing, batching, member errors, cancellation,
listener ordering, paging, release, malformed input, and version negotiation.

### Unit 6 — direct persistent Bun-native sessions

Replace the Node request-per-socket adapter with one direct, persistent,
multiplexed `Bun.connect` session per agent child and UI host. Use request IDs,
out-of-order responses, linear chunk/cursor decoding, exact partial-write
suffixes, `drain`, bounded in-flight work/bytes, semantic cancel, and independent
page delivery. Keep four-byte length framing and Transit JSON initially.

Encode one exact completed result/page once and deliver it through independent
session cursors. A slow recipient counts the full body against its own byte
budget and never retains query permits. A mmap body is an optional later
capability only if 256 KiB–4 MiB shared-result evidence beats optimized UDS.

Exit proof: 1/8/32 children, multiplex limits, fragmented frames, partial
writes, large pages, slow recipients, cancellation, child crash, reconnect,
and release preserve bounded independent progress and outperform the removed
request-per-socket path.

### Unit 7 — remote `seon.db` and coarse core reads

Keep `seon.db` as the sole application interface. Add honest remote async
operations and coordinate-pinned read-many composition. Classify current read
call sites instead of mechanically adding awaits:

- combine joins into one Datalog query or pull;
- group independent reads into one execute-many request;
- add a named projection only for one measured stable heavy derivation;
- retain synchronous calls only inside the JVM over an explicit DB value; and
- remove lazy remote entity traversal.

Migrate context assembly, turn preparation, render derivation, routes, web
feeds, embeddings/KNN, autocomplete, and agent-authored reads against one exact
coordinate. Agent top-level eval auto-awaits; authored composition is explicit
async.

Exit proof: root/agent/data/debug views, context, agent turn, arbitrary query,
pull, transaction, KNN, reconnect, and cancellation work with no Promise in
agent-visible or database values and no Datahike object in Bun.

### Unit 8 — isolated Bun agent children

Use the one Bun-native subprocess owner to run each active agent in a separate
child. The cluster supervisor owns lifecycle/control IPC, inference admission,
restart policy, logs, and terminal evidence. Children own eval/inference/tool
CPU and direct database sessions; they do not share mutable application state.

Exit proof: CPU-bound agents overlap on multiple cores; timeout, cancellation,
crash, restart, parent loss, database disconnect, and child resource limits do
not corrupt sibling agents or the authority. Modest-hardware admission limits
active children without losing durable agent/database state.

### Unit 9 — atomic replica/feed removal

After Units 1–8 prove all consumers, delete the superseded path in one cut:

- `src/seon/db/replica.cljs` and its replica tests/configuration;
- local Bun Datahike connection/index/cache construction;
- transaction publisher socket, subscriber fanout, replay/buffer/reconnect,
  response/feed correlation, and replica read-your-own-write advancement;
- replica readiness/status and launch-descriptor fields;
- global transaction broadcasts and replica-only locks/configuration;
- Node socket adapters and per-request socket RPC; and
- any compatibility flag, alternate read path, or duplicate cache/listener.

Move surviving responsibilities before deletion: durable receipts and
coordinates to protocol/authority, request recovery to session operations,
selective listeners to authority interests, KNN to its capability class,
readiness to session/capability health, and runtime identity to the operator's
one launch descriptor.

Exit proof: reachability finds no replica/publisher/Node transport path; Bun
contains no Datahike connection or index; no full transaction feed is emitted;
and source/tests/docs describe only the authority mechanism.

### Unit 10 — density, packaging, and shard graduation

Run repeated real workloads at 1/2/4/8 databases and 1/4/16/32 active children,
including cold/warm queries, pulls, writes, embeddings, KNN, large results,
slow recipients, cancellations, crashes, release/reopen, and headless clusters.
Record per-database/class p50/p95/p99, CPU, allocation, JVM/Bun RSS, GC, cache
hits/single-flight, queue depth/age, writer batching, encoded/queued bytes,
child resource usage, and cleanup.

Package one Babashka-supervised JVM authority plus Bun runtime with no Node
requirement and no source checkout. Then compare one JVM with two and four
authority shards over the same 8/32-database workload. Sharding graduates only
if blast-radius/GC evidence justifies duplicated runtime/cache cost; the
protocol and database APIs do not change.

## Dependency-ready parallel portfolio

The implementation is ordered by semantic dependency, while independent proof
and consumer inventory may run in parallel:

- Spine: Unit 2 Datahike single-flight and cancellation.
- Slot 2: Unit 3 capability fixture and protocol-schema design against the
  settled exact-identity contract, without implementing the unsettled Unit 2
  coordinator.
- Slot 3: convert the completed remote-consumer classification into retained
  execute-many request fixtures without editing the database mechanism.
- Slot 4: retain transport fragmentation/backpressure and heavy-class
  adversarial fixtures against the settled data envelopes.

After each unit, integrate its retained proof before refilling. A build/restart
checkpoint freezes all artifact inputs; lifecycle remains operator-owned.

## Current boundary and final graduation gate

Earliest unsettled implementation contract: Unit 2 identical-query
single-flight, waiter cancellation, bounded admission, and exact-generation
cleanup inside Datahike.

Integrated proof that closes it:
[[research/single-flight-proof-2026-07-15]] plus retained identical/different
key-value-database contention, waiter cancellation, failure/retry, reentrancy,
overflow, propagation, release, and shutdown tests with zero retained in-flight
state.

Final graduation requires all ten units, deletion of the replica/feed/Node
transport mechanisms, clean protocol conformance, real browser and agent
journeys, full correctness gates, modest-hardware density, no-source packaging,
restart/release proof, and the measured authority-shard decision.
