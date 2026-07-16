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
- No global authority request FIFO exists. When shared capacity is saturated,
  ordinary round robin selects a database before a worker starts. Equal-weight,
  unit-cost weighted deficit round robin has the same trace and is therefore
  not implemented until measured costs or an intentional unequal weight require
  it.
- `execute-many` resolves one immutable coordinate once and runs independent
  query/pull/index members under aggregate and member bounds. It is transport
  composition, not another query language.
- Remote CLJS database operations are honestly asynchronous. Core paths batch;
  agent top-level eval auto-awaits; composed functions use `^:async`/`await`.
- Embeddings are asynchronous derived data. Primary writes, exact reads, and
  unrelated semantic searches never wait for a provider, retry, or backfill.
  Current-document hash mismatch is the repair predicate; no pending flag is
  stored. Background completion recomposes the full current document before a
  separate derived commit and discards stale vectors.
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
5. Bound query workers, embedding calls, native KNN work, encoded response
   bytes, mutation submission, and lifecycle requests independently.
6. Avoid copies, broadcasts, brokers, adapter layers, event-loop hops, and fixed
   per-database threads/processes.
7. Expose coordinates, request/job IDs, cancellation, queue/cache/resource
   evidence, capabilities, and errors as ordinary namespaced data.
8. Delete every superseded replica/feed/adapter mechanism in the cut that makes
   it unreachable.

## Dependency ledger

- Datahike `d7ac886f` (graduated Units 1–3 at `940810f5`, plus attached
  exact-commit cache identity, atop
  `9ada755087228e10cfb179fa5779ce227a6ed220`):
  `db.cljc`, `connections.cljc`, `connector.cljc`, `core.cljc`,
  `writing.cljc`, `writer.cljc`, `query.cljc`, `resource.cljc`,
  `pull_api.cljc`, and `api/specification.cljc`.
- Konserve `b5c99bc02a7175652a610324215288b78551801f`:
  cache/store lifecycle and selected backend source.
- Bun `be77b652884b16a103cfaa4af3c1102f72f2dcd3`:
  native spawn, socket, stream, and server types/implementations.
- Babashka process `16a84e0a` (`v0.6.25`):
  `reference-code/babashka-process` and `script/seon/dev/process.clj`.
- Superv.async `3e6ed755`, partial-cps `1e119b03`, and
  persistent-sorted-set `e1a17bbe` (`0.4.137`) are now checked out at the exact
  selected coordinates under `reference-code/`, rather than existing only as
  dependency declarations.
- First-party owners:
  `src/seon/db/protocol.cljc`, `db/writer.clj`, `db/registry.clj`,
  `db/transport/uds.{clj,cljs}`, `db.cljs`, `db/replica.cljs`,
  `client.cljs`, `web/serve.cljs`, `embed.cljs`, and agent runtime owners.

Detailed evidence and falsifiers live in
[[research/architecture-recommendation-2026-07-15]],
[[research/exact-value-identity-proof-2026-07-15]],
[[research/single-flight-proof-2026-07-15]], and
[[research/multidb-execute-many-proof-2026-07-15]]. The reduced wire and host
identity contract is [[research/authority-protocol-contract-2026-07-16]]. Read
and delivery capacity are grounded in
[[research/datahike-parallel-read-internals-2026-07-16]] and
[[research/transit-bun-delivery-internals-2026-07-16]]. Proximum search,
construction, cancellation, and filter seams are grounded in
[[research/proximum-native-capacity-2026-07-16]]. The native web seam and its
direct-stream backpressure/compression constraints are grounded in
[[research/bun-serve-datastar-internals-2026-07-16]]. Shadow artifact/runtime
compatibility and the smallest Bun-only launcher cut are grounded in
[[research/shadow-bun-runtime-internals-2026-07-16]]. The exact consumer and
deletion inventory is
[[research/exhaustive-read-consumer-and-deletion-inventory-2026-07-15]], and
the zero-copy persistent-result boundary is
[[research/read-materialization-contract-2026-07-16]]. The one-host capacity
and oversubscription contract is
[[research/single-jvm-host-capacity-2026-07-16]].

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

### Unit 4 — fair bounded multi-database execution

Replace one-process/one-database assumptions with attachment-owned authority
entries backed by real Datahike connection references. Each database retains
its existing Datahike writer. When shared workers are full, per-database ready
queues use ordinary work-conserving round robin. Reserve independent capacity
for lifecycle and cancellation requests and retain background progress.

Bound these existing operations independently:

- read CPU: Datalog, pull, history, index reads, execute-many members;
- blocking provider: embeddings and remote object/provider calls;
- secondary/KNN/native: vector search and index construction;
- encode/delivery: Transit, compression when configured, queued bytes;
- ordered mutation: the existing writer per connection; and
- lifecycle/control: cancel, health, capability, acquire, release.

Safe idle capacity may be borrowed under global ceilings, but queries,
mutations, and control retain hard floors. Heavy database A cannot consume
database B's query, mutation, delivery, or control progress.

Query, pull, and index access share one fair read executor. Their Datahike
implementations are synchronous caller-thread work over the same immutable
indexes; separate pools would add nested blocking and oversubscription without
an independent dependency seam. The first measured read limits are the global
query-result-cache LRU atom under hot hits and per-connection cold storage I/O.

Delivery retains the existing JVM publisher's encode-once shared-frame model
but adds per-session and global byte bounds. Frame count alone is insufficient.
Loopback compression remains off; remote compression is configured and selected
only after encode/compress CPU, wire bytes, latency, and retained memory cross
over on representative results.

KNN search and index construction retain separate capacity. The selected
Proximum build path currently uses a physical-core-sized static ForkJoinPool
even when its public batch parallelism is one, so construction begins with one
process-wide permit. Running search is not interrupt-cancellable yet; the fork
already owns timeout, maximum-distance-computation, and patience controls that
must be passed through before the protocol claims bounded native cancellation.
The current scoped-search predicate path also scans the full index before HNSW;
direct existing ID/bitset input is the next dependency-level optimization.

Current implementation evidence: `seon.db.executor` now has a pure bounded
per-database FIFO selector and shared fixed workers. Twelve focused tests with
74 assertions prove cyclic A/B/C service, hot-database starvation resistance,
bounded rejection, exact-generation close, rejection after close, late-result
fencing, replacement-generation safety, and zero retained job identities. A
real eight-database fixture starts four immutable Datahike queries concurrently,
returns all eight exact results, and proves worker count rather than database
count bounds active CPU work without queueing DB values or connections.

Primary transactions no longer call the embedding provider. After a successful
Datahike report, the writer submits only the committed numeric entity IDs and
host-local exact generation to bounded per-database background execution.
Provider work holds no registry or connection lock. Before a later derived
transaction, the writer resolves the same exact generation and recomposes the
complete current entity; changed documents are discarded and removed triggers
retract both derived attributes. Datahike's existing per-connection writer
admission is the release race fence, so no global Seon lifecycle lock was added.
Boot backfill is admitted in bounded 256-entity batches after the connection is
published instead of blocking database initialization.

The focused executor, embedding, receipt, and writer integration gates pass 32
tests and 212 assertions. Deterministic provider latches prove the primary
response and an unrelated same-database write complete before provider release.
The same proof fills the per-database embedding queue and shows another primary
write still returns. That falsifier exposed per-message Malli schema
recompilation; retained recursively resolved protocol validators reduce a
warmed 10,000-response probe from 621.69 ms to 2.38 ms (about 261 times) while
preserving validation. Further proofs change an entity while its old provider
call is blocked and show only the later document installs a derived value, then
release and reopen the same attachment while a provider is blocked and prove
the old generation cannot install into the replacement.

Query, pull, and pull-many now enter one shared read executor after database
selection. Its worker count follows available processors with a bounded ceiling
and leaves at least one processor for other JVM work. Jobs retain only ordinary
request data and exact scope, never a DB or connection. Final release cancels
active Datahike queries, rejects queued reads, and waits for running readers to
relinquish the exact generation before closing its indexes and storage. Derived
embedding work remains abandonable and does not acquire read-drain semantics.

A retained executor proof cancels a running reader, abandons its queued sibling,
waits for worker release, and retains no identity. A real writer proof blocks a
pull, starts final release concurrently, and shows release cannot complete until
the read relinquishes the generation. The combined executor, writer, and UDS
gate passes 35 tests and 222 assertions.
The broader embedding, receipt, replay, generated-ID, and transaction-coordinate
gate passes 58 tests and 345 assertions.

The source audit in
[[research/datahike-maximum-safe-parallelism-2026-07-16]] confirms that only
mutation, commit, and publication serialize per connection. Independent
database writers and complete reads over one captured immutable database value
run concurrently; secondary-index concurrency remains adapter-qualified. Its
focused PSS/HHT/spec proof passes 104 tests and 392 assertions.

Historical committed values now have explicit native secondary-index
ownership. `commit-as-db` can omit secondary owners for proven primary-only
work, and `release-materialized-db` idempotently closes owners after readers
finish without touching the live connection value. The focused Datahike proof
passes 24 tests and 198 assertions. Seon's single-read path releases every
historical materialization in `finally`; arbitrary Datalog is not guessed to
be primary-only because the planner may legitimately select a secondary index.

The first one-host replacement is now built in `seon.db.executor`. One
immutable startup map owns the CPU ceiling, per-class/per-database queue
bounds, and decoded-request byte bound. CPU work rotates class then database;
provider waits use separately admitted virtual threads and do not consume CPU
workers. `writer/start!` constructs one dispatcher rather than independent
read and embedding executors, and `embed-texts` no longer hides another
six-thread pool behind one admitted provider job. Exact-scope release drains
reads while abandoning repairable provider work. Mutation admission now shares
the dispatcher but uses separately bounded virtual threads: mutations
serialize only per database while independent database writers progress
together. CPU and waiting-work rotation have independent cursors, startup
rejects invalid capacity maps, shutdown waits for a bounded interval before
interruption, and a released attachment also releases its drained generation
fence. Twelve executor tests prove these contracts, including four concurrent
immutable database reads. The affected gate passes 57 tests and 331
assertions.

[[research/one-host-dispatcher-replacement-design-2026-07-16]],
[[research/datahike-mutation-admission-2026-07-16]], and
[[research/proximum-dispatcher-seams-2026-07-16]] define the remaining Unit 4
work. The commit-specific
[[research/one-host-dispatcher-code-review-2026-07-16]] exposed seven P1 gaps;
mutation admission, independent scheduling cursors, released-fence retention,
bounded shutdown, and startup capacity validation are now repaired. Exact
frame-byte admission is deliberately not claimed until persistent sessions
own decoded frames. The remaining work is to route KNN bounds and external
entity IDs, supply HNSW construction capacity, add encode/session byte
ownership, and give provider calls explicit deadlines with matching connection
capacity.

Semantic search is now one continuously owned dispatcher job with two resource
phases. Initial admission reserves downstream KNN queue and conservative vector
bytes before paying provider cost. Provider completion atomically rewrites the
same job, scope, result, and request ID from `:provider` to queued `:knn`; it
never runs native search on the provider virtual thread and cancellation or a
scope fence cannot resurrect the next phase. The KNN phase resolves the
request's exact attachment and coordinate only while holding its CPU permit,
then releases historical secondary resources in `finally`. The protocol no
longer accepts unpinned KNN requests, and the combined `embed/knn-search` path
has been removed in favor of the existing `query-vec` and `knn` functions.
Focused transition and historical integration proof contributes to a 64-test,
384-assertion writer gate; the focused Bun/CLJS embedding build passes 3 tests
and 8 assertions from a clean dependency SHA.

The Datahike Proximum adapter now converts an upstream `EntityBitSet` once to
external entity IDs and calls Proximum's indexed external-ID filter seam.
Filtered search no longer translates every indexed internal node back to an
entity ID merely to run a predicate. The focused PSS/HHT/spec secondary-index
gate passes 45 tests and 267 assertions. Sparse/dense crossover measurement
remains a later tuning question; correctness uses the same entity set.

The clean Bun/CLJS compile also exposed an unconditional refer to Datahike's
CLJ-only `release-db`; owned fork `d7ac886f` conditions that refer at the source.
[[docs/seon/issues/archive/datahike-cljs-release-db-refer]] records the resolved
dependency defect.

This is not Unit 4 graduation because query, KNN, encode/delivery, mutation, and
control capacity are not all wired and 2/4/8-database adversarial proof remains.

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

Current implementation evidence: capability discovery and head resolution are
the first two control operations on the existing protocol. Both carry the one
request ID needed by a future multiplexed session and bypass query admission.
Capability discovery returns Datahike's ordinary bounded catalog unchanged,
including the facts that identify host-returning APIs. Head resolution turns
the registry's live connection into only the existing database name,
attachment, and portable coordinate; Datahike's process-local connection ID
and generation never cross the wire. A real UDS fixture proves correlation,
the exact catalog, portable head identity, typed unknown-database failure, and
no retained host owner in the returned values. The focused transport and
writer gates pass 19 tests and 124 assertions.

The first coordinate-pinned read falsifier found that Datahike's public
`commit-as-db` correctly loaded an immutable durable commit but detached its
connection-generation cache identity. The owned fork now preserves that exact
identity only when loading through an attached connection or committed DB;
loading through a raw store remains detached. Consequently, several readers of
the same older coordinate share the same completed cache and single-flight key
instead of recomputing after the head advances. The full versioning namespace
passes 18 tests and 153 assertions across specification, persistent-set, and
hitchhiker-tree configurations; the focused identity/cache proof contributes
3 tests and 30 assertions.

Query, pull, and pull-many are now coordinate-pinned protocol operations. The
authority injects the one resolved DB into query arguments, preserves Datahike's
native relation, collection, tuple, scalar, and pull shapes, and merges
`q-with-evidence` fields directly into the response without a second result
wrapper or shape tag. One recursive validation walk returns the identical
persistent result and rejects DB, connection, Entity, Datom, record, function,
derefable, Future, Throwable, lazy, and unsupported host values before Transit.
Embedded Datalog and database values retain legitimate bare keys while outer
protocol keys stay qualified. A historical-coordinate UDS fixture advances the
head, then proves the old coordinate returns old query/pull data, two identical
queries produce miss-then-hit cache evidence, `:keys` result maps retain their
shape, and pull-many preserves input order.

Cancellation is a control operation with its own request ID and one target
request ID. Queued reads are removed before execution. A running query uses
Datahike's existing request-addressed detach/cooperative signal; the authority
briefly closes the executor-to-Datahike registration race without retaining a
second cancellation record. Running pull remains truthfully reported as
running and uncanceled because Datahike has no cooperative pull cancellation
yet. Mutation cancellation never claims rollback. Executor and real UDS
fixtures cover queued/running/absent distinctions and the canonical correlated
response; the focused gate passes 36 tests and 229 assertions.

[[research/execute-many-value-reuse-2026-07-16]] measures why the batch seam
must own one immutable database value. At 32 members, resolving once reduced
historical materialization from 384.2 microseconds and 1.23 MB per request to
10.9 microseconds and 38 KB. Eight distinct scan-heavy members over that one
value ran 5.47 times faster concurrently than sequentially. Member jobs stay
flat in the one fair dispatcher, results retain input order, and only the outer
request ID crosses the wire.

[[research/bounded-query-cache-semantics-2026-07-16]] finds that bounded
Datahike queries currently bypass both completed caching and single-flight.
The selected dependency fix keeps completed semantic identity independent of
limits, certifies hits per caller, and includes the three resource limits only
in cold in-flight identity. A successful bounded computation then populates
the ordinary completed cache without any Seon cache. Datahike `7eb1b849`
implements that seam; the focused cache, single-flight, and resource proof
passes 84 tests and 405 assertions across PSS, HHT, and API specifications.

[[research/execute-many-protocol-implementation-2026-07-16]] grounds the first
wire shape and shared-value lifetime. Protocol version 3 now defines one closed
execute-many request with 1–64 existing query, pull, or pull-many member maps;
members cannot carry another request identity or database coordinate. The
executor retains the exact generation by the existing outer request ID across
resolution and progressive member admission, group cancellation addresses all
internal jobs through that same ID, and final database release waits for that
owner. The writer now resolves one immutable value under ordinary read
admission, progressively refills no more than the per-database queue window in
completion order, preserves response order, and releases a historical value
exactly once after all admitted work stops. A 64-member historical request on a
two-processor capacity completes without overflowing the eight-member queue.
The affected executor, writer, protocol, embedding, replay, and transaction
gate passes 60 tests and 357 assertions. Running-query cancellation and
release-race fixtures remain before Unit 5 graduation.

The first release-race audit found and closed a coordinator deadlock: a scope
fence now records every removed member as completed before settling its result,
so the retained outer request cannot wait for an identity the fence discarded.
Grouped query cancellation uses bounded retry across the dispatcher-to-Datahike
registration interval, Datahike caller IDs remain strings distinct from
executor tuple identities, and mixed cancellation truthfully reports both
stopped queued work and still-running uncancelable work. The focused executor,
writer, and transport gate passes 39 tests and 248 assertions.

Exit proof: transport-neutral fixtures cover schemas, values, identity,
query/pull/history, branches, fencing, batching, member errors, cancellation,
listener ordering, paging, release, malformed input, and version negotiation.

### Unit 6 — direct persistent Bun-native sessions

Replace the Node request-per-socket adapter with one direct, persistent,
multiplexed `Bun.connect` session per agent child and UI host. Use request IDs,
out-of-order responses, linear chunk/cursor decoding, exact partial-write
suffixes, `drain`, bounded in-flight work/bytes, semantic cancel, and independent
page delivery. Keep four-byte length framing and Transit JSON initially.

Protocol version 4 closes the correlation prerequisite: every request,
successful response, and failed response carries the existing request ID,
including ping, database lifecycle, replay, transaction-coordinate, and KNN
operations. The writer attaches that identity at its one canonical response
boundary, and capability discovery reports the protocol version. A future
selector therefore needs no transport correlation envelope; a frame without a
usable request ID is a session protocol failure.

The atomic-cut audit exposed two additional prerequisites. Writer admission
must become callback-complete rather than blocking selector or waiter threads,
and database attachment ownership must be counted per live internal session so
one child disconnect cannot release a database still acquired by siblings.
Exact `4 + payload` input bytes must be reserved from the frame header before
allocation, handed into semantic admission, and retained until that handoff
settles. These are part of the replacement, not compatibility layers.

[[research/connection-acquisition-lifetime-2026-07-16]] removes a planned
Seon-side reference count: Datahike already increments on matching `connect`
and performs cache/report/writer/index/store cleanup only on final `release`.
Seon retains only exact live socket membership for idempotent crash cleanup; the
socket object is internal and no additional identifier crosses the protocol.
Final release is claimed before draining and runs outside the registry-wide
lock so one database cannot gate unrelated databases.

The registry implementation now transfers the startup ensure reference to the
first live connection, calls Datahike `connect` only for additional siblings,
and releases each exact membership once. Its reconstructed config includes
Seon's serialized allocation-writer setting because Datahike correctly includes
writer configuration in physical connection identity; omitting it opened a
second writer instead of sharing the registered one. Duplicate acquire/close,
sibling survival, administrative-reference independence, final drain outside
the global lock, close-versus-reacquire generation fencing, stale close, and
cleanup-required failure pass 20 focused tests/126 assertions. The broader
registry, routing, executor, writer-integration, and server proof passes 61
tests/430 assertions.

[[research/callback-complete-writer-2026-07-16]] defines the no-waiter writer
seam. One writer active-request map plus one stable executor completion function
replaces per-job result promises, Future waiters, and the execute-many
coordinator loop. Completion updates accounting under the executor lock and
routes outside it; cancellation retains physical cleanup until the shared
database value can be released exactly once.

The first callback checkpoint is implemented: executor startup owns one stable
completion function; value, throwable, admission rejection, queued cancel,
running cancel after physical release, queued/running fence, and the final
provider-to-KNN phase publish exactly once outside the executor lock. Completion
can reenter admission, and a throwing completion cannot leak job/class counts.
Legacy result promises remain only as the current writer's proof oracle until
the active-request migration deletes them in the next checkpoint. The focused
executor proof passes 22 tests/134 assertions; executor, writer integration,
request receipts, and server pass 49 tests/342 assertions.

That audit also found and closed a real collision: transaction-derived
embedding reused the still-running mutation job ID and could be treated as a
duplicate instead of admitted. It now uses `[request-id :embedding]`; a
mutation-dispatch regression proves the commit remains nonblocking and the
provider job actually starts (7 tests/45 assertions).

[[research/selector-session-source-proof-2026-07-16]] validates the replacement
against exact Bun and JDK 26 source. A disposable fragmented-frame probe
delivered 10,000 multiplexed requests at about 164,862 requests per second,
versus 20,555–23,542 with reconnect per request, a directional 7–8 times gain.
The selector owns bytes and readiness only; frame-order admission,
completion-order responses, selector wakeups, exact partial-write suffixes,
and session-close semantics remain explicit above it.

Encode one exact completed result/page once and deliver it through independent
session cursors. A slow recipient counts the full body against its own byte
budget and never retains query permits. A mmap body is an optional later
capability only if 256 KiB–4 MiB shared-result evidence beats optimized UDS.

[[research/optimal-integration-seams-2026-07-16]] rechecks the target against
newer Bun and the maintained Datahike internals. Native UDS remains the database
data plane; Bun child IPC is control-only because its public send API has no
exact byte/drain contract and proxying would recreate a broker. Datahike already
shares immutable index roots and connection-scoped storage caching across one
captured DB value. Its existing `d/transact!` result supports nonblocking
core.async `take!`, so the callback cut also removes parked mutation threads
without bypassing public transaction, backfill, listener, or batching semantics.
The final Bun child owner uses `onDisconnect`/`onExit` for normal lifecycle and
evaluates `--no-orphans` only as Linux/macOS parent-loss containment.

The executor ready structure now retains each database once only while that
class has queued work, reappending it only when work remains. Completion and
cancellation remove the final empty queue, so cluster churn cannot increase
future selection scans. The retained 512-database churn regression plus the
focused executor gate pass 23 tests/650 assertions; the changed writer boundary
passes 69 tests/968 assertions.

The same executor now consumes a returned core.async `ReadPort` without parking
a worker: the job and its class/database capacity remain physically active until
the port yields a result, Throwable, or closes, and only then enter the one
completion path. The focused executor gate passes 25 tests/659 assertions and
the changed writer boundary passes 71 tests/977 assertions. Migrating
`execute-mutation!` from blocking `d/transact` to existing `d/transact!` is the
next writer-owned step; the compatibility result promises are still removed
only with the active-request cut.

Exit proof: 1/8/32 children, multiplex limits, fragmented frames, partial
writes, large pages, slow recipients, cancellation, child crash, reconnect,
and release preserve bounded independent progress and outperform the removed
request-per-socket path.

Runtime evidence does not require a Shadow fork: the maintained `:node-script`
and `:node-test` CommonJS artifacts execute directly under Bun. A focused
artifact passed 2 tests and 7 assertions under both runtimes; the directional
single sample used 2.05 seconds and 52.57 billion instructions under Bun versus
4.55 seconds and 128.69 billion under Node. Bun's maximum RSS was higher
(1.024 GB versus 701 MB), matching the accepted speed-first tradeoff but making
full-suite and density RSS mandatory graduation evidence. One
`SEON_JS_RUNTIME` owner must select the pod, focused/full tests, changed-test
runner, worker validator, and packaging path; no Bun-specific Shadow target is
justified.

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

- Spine: Unit 4 phase-aware bounded execution and fair database selection.
- Slot 2: Unit 5 protocol and execute-many fixtures against graduated Datahike
  capabilities, without implementing the unsettled Unit 4 execution owner.
- Slot 3: retain Bun persistent-session fragmentation and backpressure fixtures
  against the settled protocol envelopes.
- Slot 4: retain consumer migration batches and deletion inventory without
  editing the database mechanism.

After each unit, integrate its retained proof before refilling. A build/restart
checkpoint freezes all artifact inputs; lifecycle remains operator-owned.

## Current boundary and final graduation gate

Earliest unsettled implementation contract: the callback-complete writer closes
the remaining Unit 4/Unit 6 execution boundary. Provider waits consume only
embedding capacity; native KNN, queries, mutation completion, response encoding,
and lifecycle requests retain their own bounded workers or bytes. `d/transact!`
completion must hold its mutation admission without a parked thread. Database
selection precedes shared worker acquisition, and the ready structure retains
only databases with queued work so churn cannot make authority selection scan
historical empty names. Each Datahike connection remains the only ordered writer
for its database.

Integrated proof that closes it:
[[research/authority-heavy-class-proof-plan-2026-07-15]] plus retained 2/4/8
database saturation, cancellation, failure/retry, encoding pressure, release,
and shutdown tests with bounded threads, queues, bytes, and zero retained work.

Final graduation requires all ten units, deletion of the replica/feed/Node
transport mechanisms, clean protocol conformance, real browser and agent
journeys, full correctness gates, modest-hardware density, no-source packaging,
restart/release proof, and the measured authority-shard decision.
