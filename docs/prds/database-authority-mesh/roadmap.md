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
immutable indexed values, one generation-fenced committed-report source, and
ordered writer. Independent
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

- Datahike `670cd1ada40462cb5927f0dc687f6b3a95f9e13f` (schema-update
  comparison retains every detected incompatibility, atop bounded JVM-host
  structural weight over direct numeric earlier-as-of query sharing, ordered
  temporal index-page, ordered
  pull-many, and two-phase host query
  admission atop fair report batching at `d9765276`; graduated Units 1–3 at
  `940810f5`, plus attached
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
Selector byte/identity/shutdown ownership is
[[research/selector-session-resource-ownership-2026-07-16]], and the smallest
replica-removal operation surface is
[[research/remote-datahike-operation-seams-2026-07-16]]. The deterministic
fairness, resilience, and later one-versus-shards measurement is
[[research/adversarial-throughput-resilience-proof-2026-07-16]].
The final Bun consumer closure, deletion order, and two remaining semantic
probes are grounded in
[[research/atomic-bun-authority-consumer-replacement-2026-07-16]].
The Datahike-owned pre-compute acquisition that removes single-flight joiners
from scarce Seon CPU capacity is
[[research/single-flight-admission-seam-2026-07-16]].
The generated-ID remote seam and rejected declarative-template alternative are
[[research/generated-id-authority-seam-2026-07-16]].
The async render cut that preserves pure recursive rendering while moving
database I/O to coordinate-pinned outer acquisition or the owning isolated
agent child is [[research/async-render-authority-seam-2026-07-16]].
The data-driven correction that selects one trusted compiled child prompt
owner, rejects the fixed seed-count batch, and preserves the stable `seon.db`
facade is [[research/compiled-child-prompt-owner-2026-07-16]].
The first two data-dependent prompt cohorts are grounded in
[[research/namespaces-remote-acquisition-cut-2026-07-16]] and
[[research/transcript-remote-acquisition-cut-2026-07-16]]. They preserve the
existing namespace/transcript formatting owners, replace repeated lazy local
traversal with bounded coordinate-pinned acquisition, and retain the dependent
second request where later inputs cannot exist before discovery.
The corresponding canvas, menu/typeahead, subagent, warning, plan, and authored
slot cuts are [[research/remaining-prompt-block-acquisition-cuts-2026-07-16]].
They keep query definitions with each block owner, run selected owners
concurrently, and reject both a global prompt planner and per-entity remote
traversal.
The final ordinary-data async database surface and its eight concrete closure
gaps are [[research/remote-seon-db-contract-freeze-2026-07-16]]. The exact Bun
child lifecycle, zero-process dormant-agent wake seam, and density proof are
[[research/bun-child-supervision-seam-2026-07-16]].
The exact modest-hardware supervision policy, Bun terminal/resource signals,
host memory-pressure seam, platform containment limits, and retained 1/4/16/32
matrix are
[[research/bun-child-modest-hardware-supervision-policy-2026-07-16]]. It selects
one parent-owned invocation deadline, `proc.exited` terminal truth, immediate
idle retirement, no heartbeat, and no production RSS poller. Final density
numbers wait until the execution child is authority-only, because today's
child can still construct and retain local Datahike state. Static dependency
reachability and generated JavaScript size are not density evidence.
The exact Shadow reachability and launch-environment seam is
[[research/execution-artifact-database-dependency-seam-2026-07-16]]. Its useful
finding is the import path that still permits an execution child to construct
and retain a local Datahike database, not the generated JavaScript volume. The
one public `seon.db` namespace selects a private build-role implementation only
if an independently shipped execution artifact must precede the atomic
consumer cut. The selected full-control plan instead deletes the final local
CLJS consumers and that second live database owner in the same cut, leaving the
remote implementation as canonical `seon.db` without a lasting alias layer.
The child launch freezes absolute runtime root/cwd and an explicit environment
instead of inheriting the host.
Shadow/ClojureScript remains the sole closure owner: Seon defines the correct
entrypoint and removes architecturally unreachable local-replica dependencies,
then accepts the compiler-derived transitive package. There is no manual module
list, dependency stub, or bundle-byte-driven removal of capabilities an agent
legitimately needs.
Strict reads inside one containing commit are grounded in
[[research/strict-temporal-coordinate-seam-2026-07-16]]. The dependency-native
ordered/missing-value pull contract is
[[research/datahike-ordered-pull-many-seam-2026-07-16]]. Deterministic aggregate
member-result admission is
[[research/aggregate-execute-many-result-bound-2026-07-16]]. The exact
dependency-native cache/single-flight extension for numeric earlier cuts is
[[research/datahike-temporal-query-sharing-seam-2026-07-16]].

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
the changed writer boundary passes 71 tests/977 assertions. Compatibility
result promises are still removed only with the active-request cut.

That mutation migration is now implemented. Transaction preflight and durable
receipt recovery remain serialized by the executor's one active mutation per
database, `d/transact!` supplies the physical completion, and the executor
retains the mutation/database count until its `ReadPort` yields. The synchronous
direct handler retains its connection lock only as the temporary unary-server
adapter; the selector cut removes that blocking transport path. Executor,
receipts, generated identities, transaction coordinates, writer integration,
and server pass 60 tests/901 assertions.

The active-request deletion review closes the final scope-lifetime ambiguity.
Writer request ownership replaces executor retained requests, but executor
drain cannot by itself authorize database release: an execute-many completion
may still be releasing its shared database value. Finalization retains the
writer request through that release, then compare-removes it and wakes the
scope drain before delivery. The same cut reserves member positions before
submission so synchronous admission rejection can safely reenter completion,
and rechecks cancellation after admission so reserved work cannot escape.

Public request cancellation no longer depends on the executor's temporary
retained-request entry: it finds ordinary jobs by their existing request ID,
removes queued jobs, marks running jobs canceled, and publishes physical
completion normally. This is the first deletion prerequisite for moving request
lifetime wholly into the writer. The focused executor gate passes 26 tests/664
assertions and the changed writer boundary passes 72 tests/982 assertions.

The writer now owns every production request from admission through physical
executor completion. One active-request map reserves the exact scope and job
identities before submission, so synchronous rejection can reenter completion
without a waiter or race. Single reads and mutations deliver only after their
real executor result; execute-many progressively refills its bounded window,
retains one materialized database value, releases it before removing the active
request, and only then calls transport delivery. Final database release drains
executor jobs and then waits for that writer-owned scope cleanup. Active
duplicate request IDs reject before Datahike work and become reusable after
cleanup. The blocking UDS server is now explicitly a temporary callback adapter,
not the request-lifetime owner. The focused writer integration proof passes 14
tests/128 assertions, including callback duplicate rejection/reuse and the
existing execute-many and release fences; executor, receipts, and integration
pass 47 tests/837 assertions.

The compatibility executor mechanism is now deleted. Jobs retain one private
object solely for ABA-safe ownership; no executor result Promise, retained
public request, completed-job queue, waiter API, or blocking submit API remains.
Admission returns ordinary evidence and all terminal paths use the one stable
completion callback outside the executor lock. Queued cancellation completes
immediately, while running cancellation completes only after the worker or
asynchronous `ReadPort` physically returns. A scope fence may abandon a running
provider job only when it has no public request ID; public provider/KNN work
retains truthful request and capacity ownership until physical completion.
Dead synchronous read, execute-many, admitted-mutation, and KNN writer paths
are removed; the request server's blocking adapter waits only on the writer
callback and disappears with the selector cut. The focused executor proof
passes 23 tests/645 assertions. Executor, protocol, registry, receipts, writer,
generated identities, transaction coordinates, and replay pass 86 tests/1,049
assertions.

Exact Datahike source and an in-memory probe confirm that execute-many already
gets the intended index reuse: one captured immutable value shares its primary
index objects and the connection-owned storage cache across every member, while
historical secondary owners require only the one outer release. The maintained
query cache already uses exact connection/generation/commit identity and
generation-scoped eviction. The older datom-search memoizer reads
`:cache-size` while configuration defines `:search-cache-size`; it is therefore
normally disabled. Its global five-value, legacy-hash retention has no
generation release fence, so the spelling mismatch is not repaired. A
directional 5,000-datom probe measured 30.32 ms disabled, 24.19 ms when forced,
and 2.46 ms for an exact query-result-cache hit loop. Direct pull/search evidence
must justify any future exact-identity replacement.

Selector source review removes the unwired executor `:encode` class. Encoding
has no database scope, immediate responses also require it, and a rejected
encode after semantic completion has no honest response path. The transport
instead reserves one response slot at frame admission, encodes with a small
ready-session-fair worker set, converts the slot to exact framed bytes, and
hands immutable output plus offset to the selector. Query, KNN, and mutation
capacity is already released before that work; slow sockets retain only their
bounded transport bytes. The focused executor proof remains 25 tests/659
assertions and the changed writer boundary remains 71 tests/977 assertions.

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

Exact Bun source further constrains the native session. `Socket.write` is
unbuffered: it returns the accepted byte count and does not retain the suffix,
so the owner keeps one immutable frame plus offset and retries only from
`drain`. Every terminal callback and the rejected connect Promise enter one
idempotent close transition because `connectError` does not imply `close`.
Native `data` callbacks are synchronous state transitions; Bun does not await a
returned Promise. Receive chunks are copied into JS ownership, so retaining the
current chunk and offset is safe but not zero-copy. The implementation uses
`uint8array`, exact payload allocation, linear cursors, and `pause` only after
retaining the unconsumed current chunk. `Bun.allocUnsafe`,
`Bun.ArrayBufferSink`, and `Bun.concatArrayBuffers` remain three measured frame
encoding candidates rather than assumptions. Bun socket and server callbacks
preserve `AsyncLocalStorage`; an interleaved two-context proof remains required
for first-party usage.

The callback and acquisition cut now binds database authority to the physical
selector connection itself. `acquire-database` validates the caller-resolved
attachment before Datahike ownership changes; every network read, transaction,
replay, transaction-coordinate, and KNN admission resolves exact connection
membership and captures the connection plus attachment before worker handoff.
Duplicate acquire is idempotent. Sibling sockets share Datahike's own connection
reference and indexes, while final socket close cancels only its work, waits for
physical completion, drains the full connection generation, and releases every
acquisition exactly once. Cancellation also requires the target request to
belong to the same physical connection, without revealing another child's
request. Administrative release claims the route before it drains, so a failed
release against a live acquisition cannot disrupt valid work. Focused registry
and real-socket writer proof passes 35 tests/269 assertions, including denied
pre-acquire reads/writes, duplicate acquire, sibling survival, foreign cancel,
running-pull disconnect drain, target-branch isolation, and final index-owner
release.

The request server is now one Java NIO selector rather than one thread per
socket. It admits frames in connection order, completes responses independently,
retains exact `ByteBuffer` positions across partial writes, and performs codec
and handler work off the selector. Fragmentation, coalescing, reverse completion,
slow readers, graceful drain, owner identity, and exactly-once close pass 16
tests/54 assertions.

The CLJS transport is now one persistent `Bun.connect` session with a linear
`Uint8Array` parser, request-ID correlation, immutable output frames plus exact
write offsets, `drain` resumption, one deadline ticker, and one idempotent close
transition. The Node socket, Buffer, request-per-socket RPC, and publisher APIs
are deleted from this owner. A focused release artifact, including a real
`Bun.listen`/`Bun.connect` UDS roundtrip, passes 7 tests/19 assertions under Bun
and the same artifact under Node.

The native session now also demultiplexes repeated addressed database events
without confusing them with one-shot request responses. Callback handoff is
bounded to 64 interests and two maximum frames of retained event bytes; a
second undelivered event for one interest becomes one explicit
resynchronization instead of an unbounded local transaction queue. Overflow,
callback failure, or any native terminal callback enters one idempotent close
transition. The Bun 1.3.14 proof passes 14 tests and 53 assertions, including
interleaved response/event correlation, repeated-event coalescing, partial and
zero writes, `drain`, and a real native UDS roundtrip.

The first integrated resource cut now uses one shared four-megabyte protocol
ceiling across Bun, JVM framing, and executor admission. The selector reserves
exact `4 + payload` input bytes globally before allocation, passes that charge
once to the outer writer/executor request, and reserves both response count and
bytes per session and authority. Unencoded result maps are no longer queued:
completion encodes off-selector under a conservative two-copy allowance, shrinks
to exact retained framed bytes, and the selector owns only immutable bytes plus
write position. Slow readers therefore retain bounded accounted bytes and no
query permit. The next measured transport optimization is a chunked Transit
output stream that removes the remaining payload-to-frame copy.

Connection admission and cleanup are also fixed-capacity. Cleanup has two
dedicated workers and a queue no larger than the accepted-connection ceiling;
mass disconnect cannot create emergency threads or consume codec progress.
Admission counts opening, open, closing, response-encoding, and cleanup-in-
progress sessions until exact database cleanup and every response slot finish,
so reconnect churn cannot overrun the cleanup queue or silently lose an
acquisition. Shutdown drains to a configured deadline, force-closes afterward,
stops response producers before draining their final commands, uses only
bounded joins, and returns ordinary evidence for graceful versus forced close
and worker termination. The writer releases no Datahike authority unless the
selector, codec workers, and cleanup workers all prove stopped. A broken
handler or encoder cannot wedge the close API or turn a deadline into a false
success.

On Bun, a timed-out request rejects its caller once but retains its request ID
and physical capacity until the late response or connection close. Duplicate
reuse and repeated timeout overflow are therefore impossible. Operation policy,
not one universal five-second default, chooses deadlines; accepted mutations
retain an explicitly recoverable unknown outcome through their durable receipt.
The focused Bun release artifact passes 10 tests/30 assertions under both Bun
and Node. The final selector checkpoint passes 21 tests/95 assertions alone;
selector, executor, and writer integration pass 60 tests/884 assertions. The
broader registry, routing, receipts, generated IDs, transaction coordinates,
replay, transport, writer, and server gate passes 109 tests/1,196 assertions.

Addressed JVM delivery is now admission-only: the caller reserves bounded
session/authority slots and bytes, appends to that session's ordered codec
queue, and returns without doing Transit work. Sessions encode in parallel;
one session preserves admission order. Session pressure closes only the slow
session, while authority pressure is a structured retryable result. Review
found and fixed the empty-queue/idle handoff race by making observation and
idle publication one transition under the admission lock. The focused UDS
proof passes 30 tests and 143 assertions.

Two shortest throughput falsifiers precede larger tuning. First, Datahike
single-flight waiters currently join only after Seon's scarce read admission;
enough identical cold callers may therefore park every read worker while an
unrelated database waits. Second, decode, connection-open, and handler entry
share the codec workers; saturated handler entry may delay control even though
the selector remains responsive. The deterministic proofs hold A with latches
and require B to progress before A releases. Their results decide whether join
coordination moves before CPU admission and whether control needs its own decode
floor; worker-count tuning cannot answer either question.

The first falsifier confirmed the gate. With three read workers, one A owner
and two identical A joiners occupied all three while distinct database B
remained queued. Datahike reported one `miss-owner`, two `miss-joined`, and one
predicate execution; B entered only after A released. This was an admission
defect rather than a query-compute defect.

The selected repair is a host-only opaque Datahike query call acquired after
exact-value resolution but before query-compute admission. A completed hit
finishes directly, one `:run` owner enters Seon CPU capacity, and `:waiting`
joiners retain ordinary request/cancellation lifetime without an executor job.
`run!` binds the actual worker thread so nested same-key reentrancy remains
sound. Final cancellation of an acquired but unstarted owner compare-removes
it; run versus cancel is one phase CAS. Cache identity, evidence, completion,
release, and ABA fencing remain entirely inside Datahike.

The repair is now integrated at `3297b4be` plus the physical-lifetime follow-up
`e4b80c96`. Standalone and `execute-many` query calls briefly acquire under
fair read admission; completed hits finish directly, waiting callers yield
their worker immediately, and only one `:run` owner continues in place. With
one blocked A owner plus 32 identical callers, exactly one read job remains,
zero joiner jobs queue, and database B completes before A releases. The same
proof covers 33 query members in one `execute-many` request. Final unstarted
cancellation removes the exact owner job without polling.

Logical caller completion no longer releases a historical database value still
used by the physical single-flight owner. The bounded owner-job map retains that
exact value until executor completion; waiting/completed callers release from
their own callback, and `execute-many` retains its one outer owner. The focused
gate passes 4 tests/37 assertions, and query admission plus writer integration
and executor pass 45 tests/858 assertions. Cancellation, injected release
failure, and completion leave zero writer requests, executor jobs, Datahike
calls, owner-job mappings, and retained database values.

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

The cluster host owns one gap-safe selective wake interest and starts a child
only when database facts require work. Dormant agents retain no process,
compiler, session, or listener. Bun IPC carries only bounded lifecycle controls;
database requests/results remain direct child-to-authority traffic because
`Subprocess.send` has an unbounded native queue and no drain event. `proc.exited`
is the terminal authority; IPC disconnect and `onExit` ordering are only
observations. Children remain referenced and non-detached under explicit Bun
no-orphans plus Babashka's outer process-group containment.

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

- Spine: migrate the remote `seon.db` consumers against the settled ordered
  `pull-many`, strict temporal read, temporal query sharing, and deterministic
  aggregate `execute-many` result boundaries. Query single-flight
  pre-admission and process-global committed-report readiness are complete.
- Slot 2: exact addressed-delivery accounting is complete: response slots
  bound unencoded work, fixed codec workers bound transient copies, and only
  exact framed bytes consume retained global/session output capacity.
- Slot 3: the remote `seon.db` contract is frozen. Refill with its protocol and
  dependency gaps: strict temporal `t`, aggregate execute-many weight,
  nonthrowing ordered pull-many, and truthful resolved async schemas. Compact
  transaction replies move with the atomic replica/replay deletion rather than
  creating a temporary committed-datom bridge. Generated-manifest schema,
  duplicate live interest rejection, and recursive ordinary-wire validation
  are complete.
- Slot 4: Bun child supervision is source-grounded. Refill after the database
  facade with a dedicated child artifact and the zero-process dormant-agent
  trigger falsifier; IPC never becomes a database broker.

After each unit, integrate its retained proof before refilling. A build/restart
checkpoint freezes all artifact inputs; lifecycle remains operator-owned.

## Current boundary and final graduation gate

The callback deletion and physical-connection acquisition boundaries are closed:
the writer is the only public request-lifetime owner, and the socket is the one
internal authority token with no wire session ID or second reference count.
Physical-connection-owned selective `listen`/`unlisten` is complete over
the completed `schema`, operation-local `history?`, native `index-page`, and
fair committed-report batch/requeue seams. One process-wide dispatcher now
routes Datahike's global readiness sources through the exact source-to-scope
owner. A source ready during ownership publication is requeued without
consuming a report; the final runtime interrupts and joins the sole thread.
Concurrent-runtime and integrated interest proof passes 46 tests and 849
assertions. No synchronous local read is made asynchronous before this seam.

The source-grounded split is now exact. `schema` calls Datahike's existing
public `d/schema`; no parallel schema projection is built. Executable probes
proved that Seon cannot correctly compose raw `seek-datoms`/`rseek-datoms` into
paging: retraction polarity, content-equal bytes, and resolved lookup refs are
Datahike comparator concerns. The maintained fork therefore owns eager bounded
`index-page`; Seon keeps only the pinned value, ordinary datom conversion, and
coordinate/index/direction/history cursor seal. History is an operation option
over the captured raw value, not another remotely addressable database.

Datahike `d9765276` now returns already-analyzed attribute dependencies on cold,
joined, hit, and uncacheable queries. It also owns one bounded process-wide
blocking committed-report readiness queue, with no Datahike-created thread,
callback, Future, sleep, or per-database poller. One source per connection
generation remains the only commit-delivery source. The remaining small
host integration drains a bounded batch and requeues a still-ready source only
after its serialized per-database delivery job completes. The existing fair
executor then filters different databases in parallel without another queue
or scheduler.

The same dependency now owns eager bounded native paging across current and
history values. Its exact five-field cursor includes retraction polarity;
lookup refs, ref values, content-equal byte arrays, forward/reverse order, and
wrong-prefix/absent cursor errors remain Datahike concerns. Datahike's paging
proof passes 96 tests and 450 assertions. Seon has deleted its local comparator
and seek logic and retains only ordinary protocol conversion and sealing.

Committed delivery now drains at most one configured batch from a ready source
and requeues that still-ready source at the global tail only after delivery.
Rejected Seon admission requeues without consuming a report. Duplicate
requeue, close/reopen ABA, gaps, shutdown, and hot-A/cold-B fairness are
dependency-owned invariants; 102 relevant Datahike tests and 840 assertions
pass. The maintained seam creates no thread, Future, callback, or sleep.

Registration opens its source before reading the acknowledgement coordinate;
reports at or below that cut are already reflected, later reports are filtered,
and a gap abandons the partial prefix, opens a replacement before its new cut,
and emits one resynchronization event. Query interests carry the query form and
the authority derives dependencies; correctness never trusts a client-supplied
dependency set. Physical connection locks order acknowledgement, event, and
unlisten. Native send is admission-only with per-session ordering, so Transit
encoding cannot serialize the report dispatcher.

Addressed delivery no longer reserves two maximum-sized frames before Transit
encoding. Admission retains one bounded response slot; the fixed codec-worker
pool bounds transient payload/frame copies; and the selector reserves only the
exact framed bytes it retains. The focused UDS gate passes 32 tests and 153
assertions, including 64 simultaneously admitted small sessions and
session-local exact-byte pressure with a healthy sibling.

The source-frozen integrated selector, selective-interest, writer, and fair
executor checkpoint passes 78 tests and 1,002 assertions. Its expected injected
pressure, release-failure, forced-shutdown, and completion-callback logs remain
structured test evidence rather than uncaught failures.

The retained fanout falsifier registers 1,000 exact datom-pattern interests on
one committed scope and proves one matching transaction addresses exactly one
physical session. Filtering may inspect the changed attribute's candidates,
but unrelated sessions perform no send or Transit encode.

Protocol v6 now applies one recursive ordinary-wire predicate at the canonical
request and response seam. It preserves eager persistent maps, vectors, sets,
lists, bytes, and Transit scalars while rejecting lazy sequences, records,
functions, derefables, futures/Promises, throwables/errors, and unsupported host
objects before encoding. The generated-ID manifest is one closed portable
shape with qualified identity/result keys, canonical Seon IDs, nonempty
dependent lookup refs when present, and unique result keys. Commit `e9a9a793`
passes 12 CLJ protocol/generated-ID tests with 86 assertions and 3 focused CLJS
tests with 13 assertions. The writer's older private ordinary-data walk is now
duplicate policy to delete during the remote-facade cut, not a second contract.

The compact mutation response is deliberately coupled to replica removal. The
current replica consumes the authority's returned, schema-coerced committed
datoms to advance its local database; the caller's original transaction form is
not an equivalent substitute. Removing the echoed transaction data earlier
would require a temporary replacement translation/feed path. In the atomic
Unit 9 cut, the Bun caller already retains its frozen request and the response
shrinks directly to request ID, previous/current coordinate, temporary IDs,
added/retracted counts, optional generated entity IDs, and recovery truth while
the replay/event schemas and their producers disappear in the same commit.

Strict temporal reads keep the existing four-field coordinate. The authority
resolves and retains the raw containing commit once, proves that commit is on
the attached branch lineage, validates `tx0 <= t <= max-t` plus exact
transaction existence, and shares one `d/as-of` wrapper when `t` is earlier.
Datahike otherwise treats a future numeric `as-of` as head, and a UUID loaded
through a native branch may retain the source branch in its immutable config,
so neither numeric range nor raw full-coordinate equality is sufficient.
Temporal schema and KNN remain explicitly unsupported until their dependency
owners can prove earlier-`t` semantics; releasing the raw containing value, not
only its wrapper, owns restored secondary resources.

The Seon temporal cut is implemented in the existing coordinate and writer
owners. `coordinate/at` now requires an exact `:db/txInstant` transaction (with
Datahike's `tx0` origin exception). The writer proves current-branch ancestry,
derives one `d/as-of` operation value, retains and releases the raw containing
value across standalone query physical completion or the entire execute-many,
and reports temporal schema/KNN as unsupported protocol operations. Current
head, older full commit, same-container earlier cut, native-fork source commit,
future cut, and sibling-lineage cases are covered in the focused proof.

The first integrated run exposed and closed one dependency defect: temporal
AVET paging at the same cut returned `[1 2]` forward but only `[2]` reverse when
bootstrap datoms preceded the requested prefix. Datahike `f0ee54c2` restores
native comparator order after time-filtered reconstruction and before prefix or
cursor handling; Seon adds no comparator or compensating scan. Persistent-set,
hitchhiker-tree, and specification proof passes 30 tests/141 assertions, and
the canonical CLJS gate passes 137/940. The integrated coordinate, writer,
query-lifetime, fork/sibling, temporal history/index, schema/KNN rejection, and
ordered-missing-pull checkpoint passes 24 tests/226 assertions.

Direct numeric earlier `AsOfDB` queries now use Datahike's private existing
connection, generation, containing commit, and `t` as the database key. The
public raw-value identity and every weighted-cache, single-flight,
cancellation, evidence, and release owner remain unchanged. Focused
persistent-set, hitchhiker-tree, and specification proof passes 102 tests/756
assertions; the canonical Node ClojureScript gate passes 137/945. The retained
2/32-way, completed-hit, identity-isolation, resource, cancellation, clear,
failure/retry, reentrancy, final-release, and reconnect matrix is grounded in
[[research/datahike-temporal-query-sharing-seam-2026-07-16]]. The grown
latency/allocation benchmark remains a Unit 10 graduation measure in
[[../../seon/issues/temporal-query-work-is-not-shared]], not another cache
design. Temporal KNN validation now runs before provider work. Commit
`6424165b` validates current and historical coordinates through the existing
Datahike resolver with secondary-index restoration disabled, releases only
owned materialized values, and resolves again for native KNN after the
embedding is ready. Invalid earlier, missing, sibling, and force-discarded
coordinates invoke neither provider nor KNN; current-head and historical
native-index behavior is covered by the 45-test/914-assertion writer and
executor checkpoint.

The earlier Unit 7 facade checkpoint below is superseded by the source-grounded
database-value contract in
[[research/datomic-client-database-value-seam-2026-07-16]] and
[[research/public-seon-db-facade-compatibility-matrix-2026-07-16]]. The public
value is now an ordinary Datomic-shaped database map; `coordinate`, attachment,
and compact transaction projections are implementation debt to delete rather
than facade semantics to preserve. Query database values occupy their parsed
Datahike `:in` source positions, so zero, one, or several named/temporal
databases use the same function and wire argument model. Query forms already
are runtime data; no macro or second query language is introduced.

Maintained Datahike's multi-source query-cache ownership is closed at
`0070d507` and selected by root commit `5dcaa1d2`. The completed cache and
single-flight coordinator now derive ordered source symbols and top-level
argument positions from Datahike's parsed `:in`, replace every native database
with its exact committed identity, and retain no source database in a cache or
flight key. One-source keys remain compatible; two- and three-source keys carry
all member identities. Closing any member generation fences publication,
evicts completed entries, and detaches active callers. Propagation advances
only the changed source, preserves every other source, and uses the existing
conservative attribute-dependency union. A source without exact committed
identity makes the whole query uncacheable; there is no second cache path.

The exact four new behaviors pass 12 tests/66 assertions across PSS, HHT, and
specification configurations. The complete focused query-cache namespace
passes 114/822, and related single-flight, specification, and capability tests
pass 54/435. The remaining dependency risk is measured cache cost, retained as
a later performance gate rather than a second design. The source issue remains
open through that retained proof:
[[../../seon/issues/multi-source-query-cache-retains-foreign-database-values]].

The integrated proof that closes this contract ports Datahike's existing
remote HTTP compatibility suite and selected public API tests into two focused
existing-runner namespaces: a real JVM writer/UDS contract and a CLJS public
facade contract. It adds two- and three-named-database queries, current plus
`since`/`as-of`/history values, interleaved ordinary inputs, nested
descriptor-shaped data that must not be rewritten, exact result shapes,
cross-source cache hit/single-flight, and release after success, failure,
cancellation, and disconnect. The smallest selectors remain the focused writer
namespace `seon.db.remote-contract-test` and an exact `bin/test-cljs --test=...`
var; no runner or full-suite tax is added. The exact upstream-test mapping,
test inventory, exclusions, selectors, and graduation matrix are
[[research/datahike-selective-compatibility-proof-2026-07-16]].

Commit `dd10db08` now owns the real JVM writer/UDS namespace. Its focused
selector loads and runs nine tests/53 assertions against real in-memory
Datahike databases and the real Unix-domain socket with zero harness errors.
The 45 intentional failures are the exact replacement ledger: reads still
require an out-of-band database name, attachment, and coordinate; transactions
still return compact projections; listeners still require attachments and
reject keyed replacement; and release still requires an attachment/head pair.
The suite also proves the desired multi-source, temporal, eager pull/entity,
native index-order, listener-isolation, joined-cancellation, sibling-release,
and terminal-cleanup behavior as those source contracts turn green.

The ordinary-value read cut now runs against the maintained Datahike submodule
itself rather than the stale pre-helper Git dependency. Query input count and
source positions come from Datahike's parser; the writer injects an optional
current `$` only when exactly one parsed input is missing, rehydrates only
top-level source values, acquires every participating database on the physical
session, admits the request against every database generation, and transfers
all owned historical values to Datahike's existing single-flight owner. Pull,
pull-many, schema, and index paging use the same descriptor resolver and return
native ordinary shapes.

That integration exposed a Datahike planner defect rather than a transport
defect. A disconnected multi-source query projecting a scalar, tuple,
collection, or relation supplied only through `:in` had no Cartesian-component
output position and threw while indexing with nil. Datahike `a464cd88` delegates
that exact shape to its existing relation engine; no Seon query fallback or
cache was added. The focused multi-source test passes one test/nine assertions
under persistent-sorted-set, hitchhiker-tree, and specification configurations.
The real JVM/UDS contract now reaches 56 assertions with zero harness errors and
11 remaining failures, all confined to the next ordered transaction, listener,
and explicit-release contract cut. Every query result shape, two- and
three-database placement, descriptor-shaped nested input, temporal composition,
pull/pull-many nesting, and native index cursor assertion is green.

The source-grounded database-value seam is
[[research/datahike-remote-database-value-seam-2026-07-16]]. Datahike's native
`RemoteDB`, `RemoteHistoricalDB`, `RemoteAsOfDB`, and `RemoteSinceDB` records
confirm the raw-value plus temporal-wrapper semantics, but their Transit tags,
physical store identity, remote peer, and recursive host records remain inside
Datahike. The public flat ordinary map is retained deliberately: session-owned
logical-name routing preserves authorization and future authority neutrality;
`d/commit-as-db`, `d/as-of`, `d/since`, `d/history`, and
`d/release-materialized-db` remain the native rehydration/lifetime seam. A
structural Transit probe measured 161 bytes for a flat as-of value versus 244
for a one-key tagged-map approximation. This is not a whole-request benchmark;
the architecture win remains shared indexes and computation rather than tens
of descriptor bytes.

Current ordered boundary: replace the legacy coordinate-shaped protocol and
writer resolver with ordinary database values, then turn the two selective
compatibility namespaces green before migrating consumers. Commits `74953530`,
`ae43154c`, and `022c09ef`
settle the always-current database-value architecture, explicit secondary-
database lifetime, and selective upstream compatibility proof. Commit
`97f9d6bb` adds the six exact-var-selectable CLJS facade contracts using only
ordinary database values; they intentionally reject the legacy coordinate
handshake and remain red until the canonical protocol/facade cut. Commit
`dd10db08` adds the matching real JVM writer/UDS contract. The top level owns
that protocol/facade implementation and integration judgment. The stopped
plan-consumer draft remains an explicit uncommitted
handoff because its pure row transformations are reusable but its coordinate
and compact-envelope transport is not. After the dependency proof, refill that
slot with protocol-consumer deletion proof after the public contract turns
green. Replace protocol, resolver, reports/listeners, and consumers in one
dependency-ordered cut; the final graduation gate remains the full focused,
integrated, live, Bun, and modest-hardware density proof.

The previous facade history remains as implementation evidence. Source audit
found that the JVM discarded the existing
`:seon.error/kind` while constructing protocol failures, so version 7 first
preserves that ordinary field on outer and grouped-member errors. The facade
then replaces local handles with one process session; route is the first
consumer, followed by core render/context/turn acquisition and agent-authored
reads in their owning Bun children. No consumer may preserve a replica or
invent another cache while migrating. Exact source and decisions are in
[[research/async-db-facade-source-audit-2026-07-16]] and
[[research/route-direct-interest-source-audit-2026-07-16]].

Commit `82c96010` now owns one persistent process session in `seon.db`, honest
async query/pull/pull-many/schema/index/KNN/transaction operations, exact
coordinate precedence, selective interests, and compact ordinary results. The
route cache subscribes before reading and acquires its initial projection at
the interest acknowledgement coordinate; later completions are fenced by
coordinate and owner, and request dispatch performs no database read. Embedding
scope, KNN, and one ordered pull-many enrichment stay at one coordinate. Focused
proof passes 6 tests/38 assertions for the session facade, 6/22 for routes, and
3/8 for embeddings. The remaining synchronous-call compiler warnings are the
consumer inventory; they are not compatibility work to preserve.

Client bootstrap cannot switch independently. Review rejected and reverted a
partial session cut because its next provenance, schema, restore, program,
recovery, and quiescence owners still dereferenced the returned ordinary value
as a Datahike connection. The replacement must migrate the complete cold-start
call graph or remain on the last stable client until the atomic cut. The exact
plan is the active client audit lane; focused tests that stub these owners do
not prove production startup.

The turn/context falsifier also corrected the dependency order. Core entity
render dispatch is now pure over ordinary node data at `1a19c3e7`, but authored
renderers still execute synchronously in-process and discover source, requires,
exposed namespaces, and helpers through a local Datahike value. There is no
existing child invocation surface to bind those open-ended reads to the prompt
coordinate. The earlier proposed 13-member ordinary-agent / 16-member root
vector is a measured seed snapshot, not yet a frozen interface: stored blocks,
profiles, namespace edges, menus/typeahead, derived render functions, canvas
selection, and authored slots make acquisition data-dependent. The canonical
Unit 8 child execution service must therefore prove either coarse queries that
absorb those joins or a bounded discovery-then-acquisition sequence at the
same coordinate. A fixed replay table, render-only executor, or local fallback
is prohibited.

### 2026-07-16 active implementation card

#### Impact-ranked remaining work

The remaining program is ranked by eliminating duplicate live state,
parallelism, failure isolation, and code simplification. Dependency correctness
may precede a higher-impact deletion, but artifact size, warning count,
isolated query latency, codec choice, and other local improvements do not
displace this order.

1. **Finish the authority-only database cut.** One JVM authority owns each
   database's Datahike connection, immutable indexed values, query cache,
   single-flight work, listener, and write order. Every Bun process reads and
   writes it directly through the same persistent protocol session. Migrate
   coherent synchronous-reader owners—turn/run/context, web render/view,
   message/schedule/derive, and debug/history—rather than wrapping individual
   calls. The performance exit is zero Bun-side Datahike database, index,
   query cache, transaction replay, or full-feed subscription across any
   number of clusters. Removing unused dependency source is not this win and
   is not a performance gate.
2. **Make each agent child the sole owner of its compiler, eval, and tests.**
   Reuse the existing per-agent child, supervision, deadline, cancellation,
   database session, and eval implementation. One child retains one
   `cljs.js` compile state for its lifetime and reconstructs accepted authored
   source from ordinary database rows after restart. First convert only the
   eval correctness branches that still require a local Datahike value. Do not
   build a parent compatibility compiler, second eval path, fixture subsystem,
   or speculative test extension.
3. **Delete the pod-global compiler and program replay.** Remove compile-state
   threading from turn, loop, runtime, and client startup; remove the global
   replay graph and the pod's bootstrap owner while retaining the bootstrap
   artifact required by children. This is the first direct heap and cognitive
   complexity reduction.
4. **Delete replica/feed/Node compatibility reachability.** Once authority-only
   behavior is proven, delete the replica, transaction publisher/replay,
   full-feed correlation, local database construction, Node socket adapters,
   and synchronous compatibility arities together. This deletion matters
   because it makes a second live cache/index mechanism impossible—not because
   it shrinks a JavaScript file.
5. **Remove the production client's test-only local database owner.** Move the
   `open-agent-conn!` fixture to existing test support so production startup
   cannot accidentally regain a local Datahike database merely because old
   tests use its helper. Removing its imports is maintenance proof, not a
   claimed runtime-performance result.
6. **Finish the native Bun web owner.** Remove obsolete replica tests,
   transaction publication and
   replay assumptions, dynamic eval lookups, and any second server owner. Let
   it use direct authority reads, bounded Datastar streams, configurable
   compression, and native backpressure.
7. **Thin startup only after program/schema admission has an explicit owner.**
   Remove client source scanning and reconciliation machinery only after cold
   and warm starts prove identical canonical program facts. Do not trade a
   large deletion for hidden bootstrap behavior.
8. **Graduate supervision, packaging, and density.** Prove persistent compiler
   reuse, two-agent CPU overlap, isolated timeout/crash/restart, immediate idle
   release, parent-loss cleanup, no-source Bun packaging, and 1/4/16/32 child
   density before selecting process caps or authority shards.
9. **Measure before any remaining optimization.** Only retained CPU, RSS,
   latency, allocation, queue, or response-byte evidence may promote cache,
   batching, compression, codec, `--smol`, warm-child, or shard tuning. Small
   wins that do not remove an owner, unblock correctness, or close a measured
   budget remain out of the active spine.

The shortest decisive proof for the child cut is one child retaining a
definition, namespace, and live result across two eval requests; a second child
defining the same symbol independently; and a killed child reconstructing its
accepted authored program without a pod compiler. The authority-only proof is
runtime evidence: multiple Bun clients share one exact JVM-side cache/index
generation, identical reads compute once there, no Bun process opens a local
Datahike connection, and cluster count does not multiply indexed database
state. Static reachability is only a guard against accidentally restoring a
second owner.

The retained-state density gate uses real Bun OS children and the canonical
persistent database session. Existing query replies already expose exact
attachment/coordinate plus `miss-owner`, `miss-joined`, and `hit` cache
evidence; the JVM already exposes registry, committed-value identity,
query-cache/single-flight, and executor evidence. The missing proof harness is
test-only: hold 1 then 8 Bun children behind a barrier, issue the same expensive
query at one coordinate, and sample JVM connection/index-root identity plus
child PID/RSS/heap and open descriptors. One connection/generation and one cold
owner must serve all children; the second pass is all hits; child count must not
change index-root identity; children must hold no database-file or publication
descriptor; all flights/executor requests return to zero; and child retained
heap stays invariant as the database grows. No production evidence API or
package-size measurement is added for this test.

- **Stable integration boundary:** application and agent code continues to use
  the existing `seon.db` functions. Only `seon.db` owns the persistent Bun
  session, protocol requests, cancellation, and ordinary response decoding;
  Datahike APIs and values remain JVM-internal. Migration is by async
  computation owner—one prompt acquisition, route refresh, quiescence scan, or
  startup acquisition—not by mechanically replacing every leaf query call.
  The synchronous recursive render/context formatter stays pure over ordinary
  acquired data. No compatibility connection, query-result replay map, public
  namespace alias, or second database facade may make old local-Datahike call
  shapes appear synchronous.
- **Earliest unsettled contract:** complete the deliberately breaking canonical
  `seon.db` cut and migrate its explicit compile-error inventory. Commit
  `8561ae64` deletes the pod replica, its transaction feed/replay implementation,
  the replica and replica-coupled lifecycle tests, both production-bundled local
  Datahike constructors, and the REPL's hidden in-memory database. Namespace
  rendering now consumes ordinary pre-fetched data. There is no compatibility
  period: remaining callers that require a database value, connection, lazy
  entity, temporal wrapper, or synchronous query are invalid and must move to
  coordinate-bound authority operations or be deleted.
  Commit `fbc40f48` makes that break canonical: `seon.db` now exposes only the
  persistent asynchronous authority session and ordinary protocol values. Its
  authority-density build compiles 122 files with zero warnings into 52,847
  bytes and contains no Datahike, Konserve, PSS, superv.async, or partial-cps
  dependency strings; the full client compile's 236 warnings are the honest
  legacy-consumer inventory. Commits `de766b71`, `9668a2b0`, `63be0019`,
  `ac7b1399`, and `25ccb5a2` remove ambient database injection and move brand,
  knowledge, and skill consumers onto coordinate-pinned ordinary reads.
  Commit `9d9e870b` removes the web-side database snapshot/output cache and
  replay bookkeeping in favor of one normalized subscription and child-owned
  projection, deleting 6,890 lines. Commit `7873b0ad` makes root's system view
  an ordinary dynamic render through that same child/surface path; it acquires
  authority rows and does not recursively construct another agent's surfaces.
  Commit `4804349e` deletes the SCI/local-database auto-run renderer: both AI
  and HTML twins now invoke the same selected function asynchronously in the
  child. Commit `0e5be5dc` deletes the final database-interest aliases, leaving
  `listen!` and `unlisten!` as the only public interest names.

  Datahike remains the computation-cache owner. Exact identical reads at one
  immutable coordinate share its completed cache and single-flight. The web
  layer retains only normalized subscription/delivery state and the latest
  complete serialized render for fan-out or reconnect; it must not add a
  second query/result cache. Transaction changed attributes intersect each
  subscription's declared read attributes before a render is scheduled, so
  independent streams advance only when their own projection can change.
  Stored-test,
  authored-route, and direct-render execution move into the existing per-agent
  child; pod-wide program replay is deleted rather than adapted. Client session
  open, restore completion,
  readiness, startup birth, and singleton configuration are now ordinary-data,
  session-native owners. Planned quiescence already uses the
  authority facade, and `seon.db/close-session!` is the complete native inverse;
  neither needs another protocol. Prompt consumers are complete: the live debug
  feed and autocomplete/export consume the same coordinate-pinned child result
  without a synchronous cache or second renderer. The optional prompt owners
  are complete: `2dc9b44a` feeds the one
  existing warning-check registry shared ordinary acquisition, `0602b8a0`
  replaces per-child subagent reads with two bounded batches, and `93d8e0b0`
  acquires menu/typeahead data without a local database value. Commit `43645eaa`
  closes the production turn caller: one
  trusted compiled invocation returns the complete rendered-context value, the
  pod validates its exact coordinate, and only the returned text reaches the
  LLM turn. Commit `e5556524` restores derived auto-run blocks from namespace
  rows and canvas selection already acquired in the child, so discovery adds no
  duplicate database request. Recursive formatting receives only ordinary
  precomputed results and remains synchronous. The normalized Datastar
  subscription now admits a value or Promise while retaining one active render,
  at most the newest coherently merged pending change, shared equivalent
  consumers, initial-full upgrade, and subscription-id fencing. The debug
  transition awaits the child through that settled reactive owner rather than
  hiding it in a prompt-specific process cache. Autocomplete uses the same
  compiled entrypoint with its database-owned profile at the exact turn
  coordinate; export invokes that owner twice for its byte-stability check. The
  obsolete synchronous `render-context`, `rendered-context`, and
  `rendered-context-blocks` functions are gone.
  The compiled owner now acquires the agent and `:seon.config/system-text` in
  one inherited-coordinate `execute-many` and returns the established
  `:seon.ai/system-prompt` beside the rendered context. Turn capture, token
  accounting, retry requests, dispatch, and both OpenAI-compatible and
  Anthropic bridges preserve that exact value; none rereads the system text.
  No caller invokes SCI, discovers a local database value for prompt rendering,
  or introduces a render-only executor.
- **Integrated proof that closes it:** commit `86db045d` supplies deterministic
  authored-program identity, target-plus-reachable `cljs.js` loading, exact
  symbol/source/agent/REPL verification, one compiler state per child, fresh
  replacement on source change, parent-owned deadlines before and after ready,
  timeout poisoning, late-result refusal, and cross-agent fault isolation. Its
  focused gate passes 16 tests/72 assertions. The remaining proof invokes cold,
  warm, changed, canceled, and synchronous-loop authored renders through the
  real web/context owners, then proves the same ordinary result reaches pure
  rendering while deleting the SCI and in-pod authored-render door.
- **Dependency-ready parallel portfolio:** commit `72bcf3ba` owns the
  flavor-specific execution artifact and native `Bun.spawn` host; its focused
  proof passes 5 tests/23 assertions and its artifact/config/process proof
  passes 79/364. Commits `eff08b64` and `62681ee5` own coordinate-pinned core
  program/config acquisition, pure transaction compilation, exact lookup-pair
  resolution, and bounded stale core-index retry. Commit `551723fc` moves
  recovery and resumable-agent acquisition to ordinary coordinate-pinned
  authority reads, makes generated-ID allocation session-native, and removes
  178 net lines; its CLJ allocation proof passes 12 tests/75 assertions and the
  complete CLJS artifact compiles, while execution of its four focused CLJS
  namespaces remains blocked by the intentionally broken legacy replica import.
  Commit `6ccd25df` closes the discovered MCP membership regression with one
  shared resumable-agent query and one addressed interest whose owner and exact
  coordinates fence post-boot birth, termination, reverse completion, and
  detach. Its full focused artifact compiles; runtime remains blocked before
  namespace start only by the same intentional replica import. No polling,
  second registry, resume/unhost invalidation, or synchronous database fallback
  was added. Commit `86db045d` implements
  the one authored-source owner grounded in
  [[research/unit-8-authored-source-loading-seam-2026-07-16]]. Commit
  `0f6d06ea` adds one coordinate-pinned multi-agent preparation, parallel
  distinct-agent/sequential same-agent child scheduling with positional
  results, and moves the capability-approved `/call` action off in-pod
  lookup/apply; its runnable execution proof passes 18 tests/86 assertions and
  the action-bearing artifact compiles without a new warning. The independent
  supervision audit rejects heartbeats and polling: the host absolute deadline
  must cancel and retire the exact child, `proc.exited` closes the slot, and an
  abnormal post-ready exit runs exact fenced recovery without replay. A
  source-grounded remote-client dependency audit identifies the local Datahike
  construction path that must become unreachable. The full-control cut does
  not need a temporary build-role implementation: it removes that second live
  database owner and leaves the remote owner behind the unchanged public
  `seon.db` interface. Eval-limit environment reads move into closed
  startup/config data before enforcing the explicit child environment.
  Commit `ecead888` advances the closer data-driven seam with execution
  protocol v2: the one invocation identity is either an authored source digest
  or the one fixed compiled prompt entrypoint plus verified artifact digest.
  Host callers cannot select another compiled symbol, invalid identity fails
  before session/program work, and valid compiled dispatch performs zero
  authored-program reads. Focused execution/host proof passes 22 tests and 98
  assertions.
  Commit `ff87ea2e` moves planned-quiesce reads to the same facade: one
  two-member `execute-many` supplies current runs, running turns, and its exact
  coordinate; one aligned `pull-many` classifies observed terminal turns at
  that coordinate. Autonomous drain carries the final empty-work coordinate,
  while nonautonomous drain resolves once before projection detach and retains
  that value across release retry. Its focused artifact compiles; runtime
  assertions remain blocked before namespace startup by the intentional legacy
  replica import.
  Commit `9ff4b1a1` adds the one static execution composition root for the
  default and downstream artifacts and the fixed compiled prompt function. It
  performs one inherited-coordinate `seon.db/pull` of database-owned prompt
  inputs, then preserves the existing pure omission, ordering, caps, brackets,
  and cache-boundary formatting over ordinary data. Database failures remain
  explicit block errors and unresolved symbol slots remain local errors until
  their owning acquisition lands. The focused execution gate passes 26 tests
  and 115 assertions; both execution artifacts compile and include the shipped
  symbol-backed block owners through ordinary Shadow reachability. It adds no
  production caller or compatibility route.
  Commit `db365729` adds the private namespace acquisition head: one
  coordinate-pinned discovery request, one dependent current-namespace edge
  pull, and one selected `pull-many` plus transaction query. Its executable
  falsifier rejected the proposed quadratic anti-join in favor of Datahike's
  ordered query-map path; 512 successful evals use 1,036 work, 1,542 result
  nodes, and 9,766 structural weight and select the correct equal-time later
  transaction. The artifact compiles; focused runtime assertions remain blocked
  before namespace startup by the known legacy replica import. The remaining
  namespace dependency is the shared pure `ctx.cljs` namespace/schema
  formatter and bounded schema-frontier acquisition.
  Commit `a5df3d79` adds the transcript's two coordinate-pinned acquisition
  stages and retains its one synchronous formatter over ordinary data. The
  focused gate passes 17 tests and 77 assertions, including exact production
  resource bounds over 200 turns/evals/messages, zero formatting database I/O,
  and exclusion of direct/core evals outside the existing turn graph. Its
  temporary local acquisition is deleted when the compiled prompt caller
  invokes the async owner.
  Commit `159e16c4` completes namespace acquisition through ordinary data: the
  selected namespace rows remain pinned to the invocation coordinate, shared
  schema rows are acquired in batches of at most 40 keys, and acquisition walks
  each complete reachable closure before the existing formatter independently
  selects its lexical 40-definition token cap. Shared row/missing-key caches
  prevent repeated reads across namespaces and a 2,048-key aggregate bound
  fails as data. The complete execution artifacts compile; the focused runtime
  runner reaches zero test namespaces because the obsolete replica still
  imports the intentionally removed two-socket UDS defaults, recorded in
  [[../../seon/issues/legacy-replica-load-blocks-cljs-tests]].
  Commit `f8f718d4` adds the bounded canvas-resolution sub-slice. Explicit and
  configured pins perform no database request; automatic selection performs one
  candidate query and, only when candidates read database attributes, one
  grouped history query at the same coordinate. A real Datahike falsifier
  resolves 256 candidates and 800 changed entities without a per-candidate or
  per-entity query. Authored invocation, selected source acquisition, and the
  pure canvas prose tail remain unsettled and this commit does not claim a
  complete remote canvas block.
  Commit `ec1d1b37` adds the plan normal-path sub-slice. Two coordinate-pinned
  `execute-many` requests acquire the bounded active/ready/recent frontier,
  agent, selected ancestor chain, and root rollup, then reuse the existing pure
  formatter. The 1,000-step direct Datahike proof executes four queries and
  returns only the eight-row ready frontier plus its overflow witness. Active
  evaluation/wedge evidence and the conditional escalation request remain
  unsettled, so the public plan block is not switched yet.
  Commit `5d644e47` completes plan escalation with bounded conditional
  acquisition at the same coordinate, and `143cae9b` extracts the canvas's
  ordinary-data formatter. Commit `f5646f46` loads selected authored targets
  once, while `da90c65e` invokes selected functions inside the execution child.
  Commits `6ff02c0a` and `60d9582e` make that nested capability lexical and
  explicit: compiled prompt owners receive it; authored functions and nested
  renderers do not. Commit `5347ea7d` makes namespace, transcript, and plan
  their final async public owners and deletes 148 lines of local prompt
  acquisition. Commit `2366590a` moves canvas prompt selection, renderer
  invocation, authored source, and error rendering through the same child and
  removes its local `render-agent-canvas`/source-query branch. Both execution
  artifact flavors compile with the same 20 pre-existing synchronous-consumer
  warnings, now the explicit deletion/migration inventory.
  Commit `e5556524` derives auto-run renderer blocks entirely from the already
  acquired namespace rows and canvas pin, then executes their AI functions in a
  second bounded child-local batch. Both execution artifact flavors compile
  with the unchanged 20-warning inventory. Commit `43645eaa` wires that complete
  child result into real turn execution and converts outer child/coordinate
  failure to turn error data.
- `2dc9b44a` preserves the single warning registry and per-check isolation while
  sharing acquired function, schema, provenance, count, and runtime rows across
  all checks. `0602b8a0` preserves the derived subagent state and breaker rules
  without N child reads. `93d8e0b0` preserves one menu numbering and provider
  gating through bounded direct/required-symbol acquisition. The integrated
  bench-client and both execution artifact flavors compile; execution warnings
  fall from 20 to 18 because the menu/typeahead prompt-local arities are gone.
  The six focused test namespaces compile in a 527-file artifact, then the
  runner stops before any namespace starts on the intentionally broken legacy
  `seon.db.replica.js` import.
- The async Datastar subscription cut adds deferred-first-paint sharing and
  stale-result/newest-change regressions. `bench-client` compiles 516 files with
  the unchanged 46-warning migration inventory. The changed-test artifact also
  compiles before the same obsolete `seon.db.replica.js` load failure starts
  zero test namespaces; no runtime pass is claimed.
- The frozen-system cut keeps the child execution warning inventory at 18 and
  `bench-client` at 46 while both artifacts compile. Focused runtime, turn, and
  provider bridge regressions compile in the test artifact; their runtime proof
  remains behind the same legacy replica import until the atomic client cut.
- Provider configuration and retry policy now join that same prompt
  acquisition. One inherited-coordinate `execute-many` pulls the agent's
  ordinary override/retry values, the cluster system-text/transport limits,
  and the LLM config row; the execution child resolves the established
  defaults and provenance once. Every attempt receives that exact ordinary
  value and prompt coordinate, so a retry performs no database read and cannot
  silently switch model, endpoint, timeout, or retry budget halfway through a
  turn. The focused execution runtime passes 9 tests/39 assertions; the
  connection-free attempt regression and pure row resolver each pass 1 test/8
  assertions. `bench-client` compiles 515 files with the existing 29-warning
  consumer-deletion inventory.
- The debug feed now awaits that same compiled child result once at the event's
  immutable coordinate. Raw AI disclosures close over its ordinary bytes and
  make no second child call; HTML twins remain managed database units. Candidate
  catalogs flow through the normalized async render result and install only
  after its subscription/render-id fence accepts the completion. The complete
  execution and bench-client artifacts compile at the unchanged 18/46 warning
  inventories; the focused test artifact compiles before the known replica
  import stops namespace startup.
- Restore completion recording, retry adoption, startup attachment validation,
  and the HTTP readiness door now use one coordinate-pinned four-member
  `execute-many` acquisition over schema, completion, publication facts, and
  generated-ID policy. Readiness is pure over ordinary values; successful
  publication reads back at the returned commit coordinate, while a failed
  competing write reacquires the current head before adopting only identical
  facts. The complete CLJS test artifact compiles, then the known obsolete
  `seon.db.replica.js` import stops the runner before namespace startup. The
  `bench-client` artifact compiles with 40 synchronous-consumer warnings, down
  from 46, and the execution artifact remains at 18.
- Runtime program admission now acquires schema forms and function contracts
  together at one authority coordinate before building and reconciling the
  process-local projection. Preparation, immediate publication, eval
  publication, and Shadow rebuild publication honestly await that acquisition;
  none dereferences `seon.db/*conn*`. `bench-client` compiles with the same 40
  remaining warnings, so this removes a hidden local database dependency
  without adding a compatibility projection path.
- Immutable process launch decoding and environment fallback now belong to
  `seon.launch`, the existing descriptor owner. Client runtime, advertisement,
  reload, blob, and entrypoint consumers read that one value directly; the
  replica no longer owns configuration needed after its deletion. This ports
  the previously proven `068c41a3` seam without restoring its stale bootstrap
  call shapes.
- Production client open now uses the one persistent `seon.db/open-session!`
  transition for capability negotiation, database ensure/acquire, provenance,
  schema installation, reads, writes, and interests. Planned quiesce and stop
  close that session only after projection and interest teardown. The client
  no longer imports the replica; `bench-client` compiles 515 files with 30
  synchronous-consumer warnings, down from 40. The exposed next blockers are
  exactly startup birth/replay/resume and web/config consumers, not transport
  compatibility.
- The public asynchronous query request now carries Datahike's existing
  `history?` capability through the same protocol request. Startup birth can
  derive whether any non-root agent has ever existed without a local history
  value, a replica, or a second query interface; focused session proof checks
  the exact flag and coordinate on the wire.
- LLM configuration seeding and brand reconciliation now each acquire their
  singleton as one bounded ordinary pull and fence any derived transaction at
  that exact coordinate. Their established pure transaction compilers and
  nonfatal startup behavior remain unchanged; neither singleton sync reads a
  local database value.
- Startup birth now acquires root, root home, historical ordinary-agent birth,
  and the stored agent-ID generator policy in one three-member authority
  request at one immutable coordinate. Fresh boot compiles root completion and
  the first ordinary agent into one coordinate-fenced allocation transaction;
  restart performs no write, retracted ordinary agents are not silently
  replaced, and known-ID creation uses one ordered pull-many instead of local
  entity reads. Production birth and allocation no longer dereference or pass
  a Datahike connection.
  Four focused authority-only regressions pass 4 tests/39 assertions. The
  containing legacy lifecycle namespace has six additional failures because
  its old fixture still opens a local Datahike database; those tests are
  deletion/rewrite inventory, not a compatibility requirement.
- Eval receipt allocation, terminalization, transcript-first fallback, and
  settled-CAS inspection now use only the authority facade. Generated eval IDs
  no longer receive a local connection, transaction requests contain only
  ordinary data, and a losing terminal CAS resolves the durable receipt status
  through one remote pull. The execution artifact compiles at its unchanged
  415 files/18-warning inventory; the remaining eval-local branches are the
  ordered preflight/program/test migration boundary.
- Trusted compiled child calls now use the existing function-symbol plus exact
  artifact-digest identity without a prompt-specific protocol or version bump.
  The execution artifact owns one closed direct function map; unknown symbols
  and digest mismatches fail before session/program work, and trusted dispatch
  cannot be redirected through global symbol lookup. The execution artifact
  remains 415 files/18 warnings; focused direct dispatch, host identity, and
  real turn-caller proof passes 8 tests/20 assertions.
- The execution child now owns exactly one lazily initialized `cljs.js`
  compile state. Trusted artifact adapters receive only the existing selected-
  function capability and a private function that returns that state; neither
  the state nor the execution owner crosses Transit or enters database data.
  Authored program loading now reuses the same state even on its first load,
  while prompt rendering does not initialize it. Focused adapter/state proof
  passes 2 tests/8 assertions, and both execution artifacts compile at the
  unchanged 415 files/18-warning inventory. Eval is not exposed through this
  door until its remaining local-Datahike correctness reads are converted to
  authority requests.
- The eval batch run fence is now unconditional whenever a run ID is present;
  absence of a local connection can no longer skip the serialized writer CAS.
  A losing terminal receipt CAS whose authority status read also fails now
  stops as database error data instead of entering transcript fallback. Two
  connection-free regressions pass 2 tests/6 assertions.
- Eval program projection no longer reads a database value merely to compute
  optional function-field, declared-read-attribute, or namespace require-edge
  diffs. Idempotent whole-attribute retraction followed by the exact current
  values expresses all three replacements directly in the one serialized
  transaction; Datahike's component semantics retract the old edge entities.
  This deletes three local snapshot dependencies from accepted program changes
  and removes stale-diff/component-ID code without adding a remote read.
  Focused pure transaction proof passes 3 tests/6 assertions.
- Prestarted eval receipt terminalization now accepts the authority coordinate
  used to compile its program transaction. A stale coordinate writes nothing
  and returns immediately before receipt inspection, logging, or the
  transcript-without-tee fallback, so the caller can reacquire and recompile
  frozen data without rerunning agent code. The matching acquisition groups the
  only two remaining read-dependent facts—boot-owned changed functions and a
  bare-require namespace declaration/provenance—into one bounded authority
  request, while declaration merging is pure over its ordinary result. The
  successful eval path now freezes the executed result, analyzer edges, schema
  state, output, and captured database operations once, then retries only
  authority acquisition, pure transaction compilation, and coordinate-fenced
  recording. Stale authority movement cannot rerun agent code or publish a
  provisional schema projection, and the normal path no longer reads local
  Datahike state for either fact. Focused fence/acquisition/merge/retry proof
  passes 4 tests/14 assertions. The remaining special REPL forms are the next
  local eval-read boundary before the child adapter can own all eval.
- Cold authored-program reconstruction now uses one seven-member
  coordinate-pinned authority request for the agent's namespace sources,
  require-edge links, functions, tests, home namespace, schemas, and function
  contracts. It returns only ordinary rows and canonicalizes them into the
  existing `:seon.ns/name`, `:seon.fn/_ns`, and `:seon.test/_ns` shapes before
  loading them into the child-owned compiler state. Source, namespace-link,
  schema-form, and paired source/spec transaction provenance fail closed so
  another agent cannot redirect or amend the accepted program. A direct
  maintained-Datahike probe exercised all seven query forms and returned the
  expected owned plus boot rows; focused acquisition/canonicalization/real
  compiler proof passes 4 tests/14 assertions, and both execution artifacts
  compile at 415 files/18 pre-existing migration warnings. The remaining
  special REPL forms are the next authority-read boundary.
- Guarded namespace loading now consumes one explicit ordinary map from
  namespace name to authored source. It has no ambient database fallback and
  does not copy that map into compiler state, so the child retains only its
  compiler while the JVM remains the sole database/index/query-cache owner.
  The trusted eval artifact acquires one coordinate-pinned authored program,
  installs it in that retained compiler, and invokes the existing eval batch
  owner. Prompt acquisition remains pinned to its invocation coordinate;
  mutating eval deliberately is not, so its fenced authority writes and
  stale-coordinate reacquisition can advance. Focused connection-free loading,
  acquisition, coordinate, and adapter proof passes 6 tests/21 assertions.
- Production turn eval now sends the parsed model reply to that same per-agent
  child at the exact prompt coordinate. The pod no longer evaluates model
  output or receives a compiler state on this path. The child returns only the
  ordinary existing eval result; coordinate, artifact, agent, deadline, and
  result bounds remain the existing execution-host contracts. All five focused
  prompt/eval dispatch tests pass 5 tests/9 assertions, and the frozen client
  artifact compiles with its existing migration-warning inventory.
- Special `in-ns`, `alias`, `ns-unmap`, and `ns-unalias` handling no longer
  reads the pod's local database. Namespace movement consumes the explicit
  accepted source map. The two read-dependent mutations acquire their ordinary
  authority facts at one coordinate, compile idempotent transactions, record
  behind that coordinate fence, reacquire on stale data, and apply the
  child-local compiler change exactly once only after an accepted record.
  Focused transaction and retry proof passes 3 tests/8 assertions. Legacy
  local-Datahike REPL fixtures currently fail during their obsolete provenance
  bootstrap before reaching these forms; the active atomic-client issue owns
  their deletion or authority-session rewrite and prohibits a local fallback.
- Scheduled function execution now parses in the pod but evaluates in the
  same per-agent child through the shared `turn/eval-parsed!` orchestration
  function. It captures one frozen coordinate before opening the schedule turn,
  retains the existing run fence and scheduled-turn accounting, and passes no
  compiler state through loop input. The frozen client artifact compiles with
  its existing migration-warning inventory.
- Runtime resume, reload rehosting, and loop wake state no longer acquire,
  retain, or thread a pod-global compiler. The supervised child lazily owns
  compiler reconstruction; the pod retains only its provider closure and wake
  listener. The `bench-client` artifact compiles 515 files with the existing
  32-warning synchronous-consumer inventory. The now-unreachable replay
  implementation remains deletion inventory for the atomic client cut; its
  source volume and generated package contribution are not performance
  evidence.
- The retained-state harness now holds one and then eight real Bun OS children
  on independent canonical persistent sessions. Its 52 assertions prove one
  identical JVM registry connection, unchanged `eavt`/`aevt`/`avet` root
  identities, one cold `miss-owner`, seven `miss-joined`, all second-pass hits,
  no publication-socket descriptor in a child, complete session release, and
  zero remaining flights/callers. The same release-artifact run measured
  206–225 MiB RSS per child under eight-child pressure, despite no child opening
  a database. Bun itself measured about 22 MiB. Release packaging therefore
  does not close the density gate: the combined `seon.db` namespace still
  reaches its obsolete local Datahike/Konserve implementation. This is retained
  RSS evidence for the atomic authority-only code cut, not a package-size goal.
  A controlled release-artifact probe sharpened the cause: no database,
  Konserve store, query cache, or single-flight registry was populated, yet the
  child retained about 140,000 objects, 19,500 functions, and all 16,034 JSC
  function executables after forced collection. The release graph contains 173
  namespaces, 101 from the obsolete Datahike/Konserve/PSS/superv.async/
  partial-cps/Fress/core.async closure. `--smol` reduced one sample from roughly
  224–230 MiB to 209 MiB but did not collapse that live graph. The decisive
  next falsifier is therefore zero dependency reachability after the canonical
  `seon.db` cut, followed by the same physical-footprint and 1/8-child proof;
  bundle bytes and heap flags remain secondary.
  The proof also exposed an independent cross-runtime aggregate-query Transit
  mismatch. Commit `ad54f1c6` fixes it at the dependency-native JVM decoder
  seam: Transit semantic lists become eager persistent lists while arbitrary
  lazy sequences remain invalid. Direct and `execute-many` aggregate requests
  now pass framed UDS execution; the focused gate passes 53 tests and 411
  assertions. The resolved issue is
  [[../../seon/issues/archive/bun-transit-query-list-is-not-ordinary-on-jvm]].
- **Next refill:** integrate the in-progress publisher/replay deletion and the
  changed-attribute subscription gate, then use the remote-only compile
  inventory in dependency order: agent run/loop/lifecycle/schedule transitions;
  `my.plan` and toolkit functions; obsolete context/render fallbacks; web,
  debug, and database browser; then eval/autocomplete and remaining tests.
  Each public mutation acquires one coordinate-pinned ordinary projection,
  compiles a pure transaction, and submits it behind that coordinate fence.
  Delete the old arity or test in the same cut; do not restore a connection,
  database value, temporal wrapper, SCI renderer, publisher, replay page, Node
  adapter, or compatibility name merely to reduce warnings.
  Supervision graduation then proves immediate idle release, parent-loss
  cleanup, memory-pressure admission, remote-only artifact reachability, and
  the retained 1/4/16/32 density matrix before selecting the shipped child cap
  or any warm set.

Datahike's existing `pull-many` already parses once, owns one pull frame
machine and resource budget, and certifies one eager result. Its dependency
change therefore preserves that mechanism while resolving missing numeric and
lookup refs without throwing, retaining nil at the exact input position, and
preserving lookup syntax/uniqueness errors. Seon keeps only registered-versus-
installed selector policy; it does not run or zip N pulls.

The dependency change is implemented and pushed at Datahike
`1296cfc4cb8c9b4868dde8bb6c3f4d4dc523d043`. Persistent-set,
hitchhiker-tree, attribute-ref, and API-specification proof passes 120 tests and
393 assertions; the canonical Node CLJS gate, now including pull and API
specification suites, passes 135 tests and 934 assertions. The full dependency
`bb test` still aborts later during unrelated suite cleanup because a database
has active connections; the exact evidence is retained in
[[research/datahike-ordered-pull-many-seam-2026-07-16]]. The remaining ordered
pull boundary is Seon's writer/protocol integration and consumer deletion, not
another Datahike mechanism.

The aggregate `execute-many` result bound is implemented using Datahike's
unchanged bounded structural-weight traversal, exposed only to CLJ hosts at
`d21abadb`. The wire requires one outer bound and the constructor supplies the
4 MiB default. Admission reserves the complete response plus one fixed error
per member; contiguous positions replace those reservations deterministically.
Overflow preserves the accepted prefix, stops refill, cancels queued work,
drains running work, and leaves exact Transit bytes as the independent final
fence. Refill now counts admitted-but-not-accepted positions rather than only
jobs, so a slow early member bounds running, yielded-query, and completed
out-of-order retention in the existing eight-position window. Members inherit
the outer limit unless they declare a narrower one, and complete success or
failure responses are certified before batch-state retention. The cutoff also
fences reserved and racing submissions. Focused protocol/writer proof passes
31 tests/298 assertions; executor/protocol/UDS/query-admission proof passes
71/936, and UDS alone passes 32/153. Independent adversarial review is clean.
The focused CLJS protocol source compiles, while the cold bundle still stops in
the superseded replica's removed Node UDS imports; the Unit 7/9 consumer cut
owns that deletion.

Integrated proof that closes it:
Schema/index/history/selective-interest conformance, including forward/reverse
cursor continuity, register/commit/unlisten ordering, addressed one-of-1,000
delivery, report-gap resynchronization, sibling disconnect, and final release;
followed by the first coarse remote `seon.db` consumer plan.

The render consumer plan is now settled. Core views issue one coordinate-pinned
`execute-many` acquisition and then render synchronously over ordinary data.
Agent-authored renderers execute and await ordinary `seon.db` calls inside the
owning isolated Bun child. The web host performs I/O outside pure view-unit
state transitions, fences late completion by coordinate plus renderer token,
and shares one acquisition/render/serialization across equivalent browsers.

Generated identity allocation does not need another authority operation. All
11 production builders are pure over generated IDs and frozen caller data. Bun
keeps candidate generation and sends the existing transaction data plus
candidate manifest; the JVM retains policy validation, collision detection,
serialized commit, durable receipt, and generated-entity recovery. The warm
path is already one request. Only a collision uses a new candidate round and
request ID; ambiguous delivery resends identical bytes and the identical
request ID. A declarative template would add a second transaction language to
avoid only that rare retry.

Final graduation requires all ten units, deletion of the replica/feed/Node
transport mechanisms, clean protocol conformance, real browser and agent
journeys, full correctness gates, modest-hardware density, no-source packaging,
restart/release proof, and the measured authority-shard decision.
