---
type: research
status: completed
tags: [research, database, flow, web, agent]
---

# Database runtime responsiveness audit — 2026-07-13

## TL;DR

The database architecture has the right load-bearing shape: one JVM Datahike
writer, immutable local reader snapshots, durable logical-write receipts,
read-your-own-write recovery, and a reconnecting transaction feed. Those parts
should be completed, not replaced. The default processes were idle at roughly
zero CPU during this audit, so there is no evidence that Datahike alone explains
the previously observed CPU sawtooth. The known SCI/render fanout remains the
better-proven immediate cause of that sawtooth.

The database path is nevertheless not yet fast, bounded, or restart-exact:

- an unused, older server-side `:seon.subscription/*` engine installs a listener
  in every database and creates real transactions on converged writer startup;
- boot still rebuilds the whole program/schema/config/skill desired state and
  calls the transactor even when nothing changed;
- `seon.state/reconcile!` is not an exact desired-state compiler: it scans the
  store by provenance, cannot retract all omitted facts safely, has no head
  fence, and cannot return a zero-write convergence result;
- each ambient dereference of the non-streaming reader connection re-reads the
  branch head from Konserve and constructs a fresh Datahike value; the current
  config cache keys by object identity and therefore misses across those reads;
- Datahike's query-result budget counts outer tuples, not retained size. A
  single very large scalar or pull result weighs one, and the pod's remote
  snapshots do not receive the writer's parent-to-child cache propagation;
- commit listeners synchronously Transit-encode and write every transaction to
  every pub socket, including sockets attached to other databases;
- each request RPC opens a new Unix socket and timer, each transaction creates
  one timer per local listener, and reconnect buffers live events in an
  unbounded vector while replay runs; and
- full attachment coordinates, same-store branches, restore fencing, and
  canonical Malli restoration are still incomplete, so cold recovery is not yet
  “attach to facts and resume.”

The first implementation move should be deletion: remove the unused reactive
subscription engine and its schema/wire operations. Then land one exact,
fenced, zero-write state compiler and make boot/config/Malli use it. Only after
those facts are measurable should cache limits or socket batching be tuned.

## Scope and method

This audit covers the active CLJS pod's database boundary, the JVM wire writer,
Datahike/Konserve behavior, transaction feed, exact-state boot/config path,
reader cache behavior, and branch/restore seams. It inspected the maintained
forks actually pinned by `deps.edn`, not package guesses:

- Datahike `67934f650fae30924ac115c899cd3412d90dcacb`;
- Konserve `df6818d43ea3363a808cd051c0d68917f1b987a9`; and
- Seon Git head `9f1f819b00a5b309b12ad6525656c413457f47dd`.

All live probes were read-only. I did not restart or reset either process,
transact facts, create agents, or touch ACME. One `d/with` experiment used an
immutable database value and did not commit. Other agents were writing to the
default store later in the audit, so point-in-time counts below are evidence of
topology, not a controlled performance benchmark.

## Observed baseline

### Cold-resume timing

The current cold reset opened the store and transaction feed quickly, then
spent most of startup reconstructing pod runtime state:

| Milestone | Timestamp | Elapsed from pod boot |
| --- | --- | ---: |
| Pod boot | `03:09:19.524Z` | 0 ms |
| Local store open | `03:09:19.623Z` | 99 ms |
| Transaction feed live | `03:09:20.043Z` | 519 ms |
| Program replay complete | `03:09:27.445Z` | 7,921 ms |
| 708 functions instrumented | `03:09:27.604Z` | 8,080 ms |
| Web listening | `03:09:27.625Z` | 8,101 ms |
| Runtime ready | `03:09:27.844Z` | 8,320 ms |

Evidence: `logs/pod.log:10-25`. The wire writer announced itself ready at
basis-t `536870914`, before the pod's genesis at the next transaction, because
its old subscription bootstrap had already created two transactions.
`logs/wire-server.log:13-19`

### Fresh-store transaction shape

The initial read-only history query found 15 transactions from basis-t
`536870913` through `536870927`. The largest was the core program transaction
with 15,284 datoms. The config transaction carried 96 datoms, including nine
routes and six skills. The first two transactions were the wire-receipt and old
reactive-subscription schemas and had no Seon user/process provenance because
they precede genesis.

Reasserting the three installed `:seon.subscription/*` schema maps with
`d/with` advanced the simulated basis by one and produced one datom:
`:db/txInstant`. Datahike does suppress the repeated schema assertions, but a
transaction is still real because every call gets a monotonic transaction
instant. A converged application must avoid calling `transact` at all.

That behavior follows directly from
`reference-code/datahike/src/datahike/db/transaction.cljc:1125-1152`.

### Point-in-time process and store observations

- Initial database snapshot: 16,270 live datoms, max eid 3,071, basis-t
  `536870927`, two Datahike listeners (`raw-broadcast` and `reactive`), and no
  persisted or active reactive subscription.
- Query-result cache in the live writer: the configured maximum 64 snapshot
  buckets, 383 entries, and reported tuple weight 1,408. This proves the cache
  is active and at its snapshot cap; it does not prove its retained bytes.
- Later process sample while other work was active: Node RSS about 1.4 GiB,
  JVM RSS about 613 MiB, both around 0% CPU; 13 Node thread rows, 45 JVM thread
  rows, 51 Node descriptors, and 220 JVM descriptors.
- The writer emitted an estimator warning that this store lacks precomputed
  subtree counts and is using heuristic query planning.
  `logs/wire-server.log:20`

Neither RSS figure isolates database retention: Node also owns the self-hosted
compiler/analyzer and rendered values, while the JVM owns Datahike indexes,
Konserve caches, the Clojure runtime, and a large dependency graph. Heap and
retained-object profiles are required before attributing memory.

## What is already sound

- The JVM remains the sole durable writer and the pod reads immutable database
  values locally. `src/seon/store/wire.cljs:274-278`
- Every logical wire write gets a durable id/hash receipt. Lost replies are
  recovered without double-applying a transaction, and an id reused with
  different data is rejected. `src/seon/server/wire.clj:770-840`
- The write response materializes a database value at or past the acknowledged
  basis before resolving, preserving straight-line read-your-own-write
  semantics. `src/seon/store/wire.cljs:419-519`
- The feed holds a monotonic transaction watermark, pages replay under a fixed
  upper bound, and drops/reconnects rather than silently skipping malformed
  pages. `src/seon/store/wire.cljs:606-756`
- The pod funnels own and foreign transactions into Datahike's normal listener
  registry. The web layer already groups equivalent views, coalesces changed
  attributes, and keeps latest-wins gzip backpressure. Those mechanisms should
  be made correct and bounded rather than replaced.
- `seon.db` already has a general database-read capture/replay primitive. Exact
  invalidation can be derived from observed reads instead of a parallel
  subscription language. `src/seon/db.cljs:459-483` and
  `src/seon/db.cljs:1342-1420`
- Durable provenance is now the minimal pair of `:seon.db/user` and
  `:seon.db/process`; turn/eval/session implementation details do not leak into
  facts. `src/seon/db/internal.cljs:1393-1450`

## P0 — blockers to reliable convergence and recovery

### 1. Delete the unused server reactive-subscription system

`seon.server.boot` keeps a per-database engine atom, installs three
`:seon.subscription/*` attributes, rebuilds a query engine, and registers a
second Datahike listener on every database.
`src/seon/server/boot.clj:115-185`

No production caller registers one of these subscriptions, and the inspected
fresh store had none. The active pod instead uses raw transaction reports and
its own read capture. The schema installer still calls `d/transact` every time,
so even an otherwise converged writer restart advances history by one
`:db/txInstant`. It is duplicate architecture and directly violates the
one-mechanism rule.

Delete the engine, schema seed, changed-summaries broadcast, wire handlers, and
tests that only preserve this path. Afterward, a writer reopen must install only
the raw transaction broadcast listener and perform zero database transactions.

### 2. Replace `reconcile!` with an exact, fenced state compiler

The current reconciler:

- discovers managed entities through a provenance scan;
- upserts every desired entity map whether or not it differs;
- retracts only whole stale entities;
- cannot safely remove an omitted scalar or cardinality-many value;
- does not guard component children, outside-authored facts, or incoming refs;
- has no compare-and-swap/head fence between read and commit; and
- reports desired row count as “upserted,” not the effective delta.

Evidence: `src/seon/state.cljs:59-139`. Its underlying
`managed-identities`/inventory paths scan all live datoms and reconstruct
first-transaction provenance. `src/seon/db.cljs:1565-1790`

The replacement should be one pure compiler:

1. Accept one explicit immutable database value, desired entity maps, identity
   attributes, and the exact authority boundary.
2. Query only candidate identities and their relevant current attributes.
3. Emit additions, value retractions, component-safe entity retractions, and a
   summary from facts, not from how the diff was processed.
4. Refuse ambiguous ownership or incoming refs rather than guessing.
5. Include a database-head/CAS fence so the compiled transaction cannot apply
   to a different state.
6. Return an empty transaction and do no RPC when already converged.
7. Commit the complete desired-set change in one ordinary transaction.

This compiler should be reused by boot, optional config application, canonical
schema restoration, routes, and other exact subsets. Do not add a second
config-specific reconciliation path.

### 3. Make boot attach first, then do only proven deltas

`boot-seed!` currently always:

- builds the complete program graph before deciding what is absent;
- transacts entity-schema decomposition;
- transacts core seed maps;
- transacts the core-index vector even when its delta is empty;
- loads a manifest, scans skills from disk, and constructs all routes/config;
- invokes the incomplete reconciler; and
- runs a separate config-singleton healing transaction.

Evidence: `src/seon/client.cljs:2203-2351`. The live timing shows the store/feed
were attached in about 0.5 seconds, while replay/instrumentation delayed web
readiness to about 8.3 seconds.

Required state transitions must be distinct:

- fresh store: genesis plus the minimal required initial facts;
- populated store, no overlay: attach, restore canonical runtime projections,
  and transact nothing;
- populated store, explicit config overlay: compile and apply exactly that
  subset once, then reach zero-write convergence;
- mint agent: one birth transition, without core/schema/config work; and
- branch/restore: attach to the requested coordinate without silently applying
  config unless explicitly requested.

### 4. Finish canonical Malli restoration before claiming trivial resume

`seon.schema/*schemas` is still the live authority. Registration mutates an
atom and asynchronously tees declarations to the database; many `defonce`
initializers retain successive registry-map values.
`src/seon/schema.cljc:80-159`

Persisted schema decomposition contains selected projections, while the full
schema form and kv vectors are typed as opaque `:any`. A cold process therefore
cannot restore the exact registry atomically from the database before program
loading. Boot must re-run namespace registration instead.

Store one canonical, versioned schema declaration fact sufficient to rebuild a
fresh Malli registry. At attach, validate the complete candidate registry,
swap it into runtime state once, and instrument from that accepted snapshot.
Incremental definitions can then compile a small schema/program delta. The atom
remains a process projection, not an independent source of truth.

### 5. Complete attachment coordinates before fork/restore work

The active registry still routes some requests through bare database names or a
global `{agent-id -> db-name}` atom, and `fork-db!` remains a physical store-copy
operation. `src/seon/server/registry.clj:210-280` and
`src/seon/server/registry.clj:403-508`

That cannot represent the same logical agent id on two branches and has no
durable cold rebuild. The coordinate must name store identity, branch, and
commit/basis (with mode where required). Once that is the only attachment key,
same-store branch heads can provide writable forks and `as-of` can provide
read-only simulations. Restore then becomes an explicit, fenced branch-head
move through the maintained Datahike path, not a second database copier.

## P1 — throughput, boundedness, and responsiveness blockers

### 6. Ambient reads repeatedly reconstruct database values

For Seon's non-streaming writer, every connection dereference reads the current
branch record from Konserve and runs `stored->db`.
`reference-code/datahike/src/datahike/connector.cljc:80-90` and
`reference-code/datahike/src/datahike/writing.cljc:226-287`

Many zero-argument `seon.db` APIs independently dereference the ambient
connection. The config reader attempts to collapse hot calls with a one-slot
cache keyed by `(identical? db ...)`, but every non-streaming dereference creates
a fresh database map, so that identity key cannot hit across ambient calls.
`src/seon/db.cljs:1808-1847`

The fix is an operation boundary, not a global cached database:

- dereference once at the start of a render group, turn, debug unit, or state
  compilation;
- thread that immutable database value through pure readers;
- key derived caches by the full attachment coordinate plus database cache key,
  not object identity; and
- expose branch-head reads and `stored->db` materializations as counters.

This also prevents one logical render from mixing two database heads.

### 7. Query-result retention is not bounded by retained size

Datahike currently keeps 64 database-snapshot buckets and a global weight limit
of 1,000,000 outer result tuples.
`reference-code/datahike/src/datahike/query.cljc:2370-2417`

`result-weight` counts only the outer collection or assigns scalar/single-pull
results weight one. A one-row result containing a huge transcript therefore
weighs one. The weighted LRU also always retains one overweight snapshot because
it shrinks only while more than one key remains.
`reference-code/datahike/src/datahike/query.cljc:2391-2404` and
`reference-code/datahike/src/datahike/lru.cljc:109-118`

The cache key correctly uses stable database facts `[hash max-tx max-eid]`, so
freshly materialized equivalent snapshots can share entries.
`reference-code/datahike/src/datahike/query.cljc:2447-2450`. However,
parent-to-child selective propagation runs only from in-process Datahike update
paths. Seon's remote writer synthesizes reports and the feed materializes from
the store; neither calls `complete-db-update`, so the pod recomputes queries for
new snapshots and may retain results across 64 heads.
`reference-code/datahike/src/datahike/query.cljc:2554-2582` and
`src/seon/store/wire.cljs:419-519`

Instrument hits, misses, evictions, bucket count, tuple weight, and estimated
retained tokens/bytes before changing limits. Then fix the maintained Datahike
fork so one giant result can be rejected/evicted and weight approximates actual
retained size. Do not layer an unbounded Seon memoize cache over it.

### 8. Pub broadcast can hold up the writer and crosses database boundaries

The Datahike listener synchronously Transit-encodes and writes the complete
event to every subscriber socket. Each socket has a lock to prevent frame
interleaving, but a slow socket can still extend listener/commit completion.
Every socket receives every database's transactions and clients discard those
for other databases. `src/seon/server/broadcast.clj:79-116` and
`src/seon/store/wire.cljs:651-659`

Bind the pub connection to a full attachment during a handshake. Enqueue a
single encoded event into a bounded per-attachment fanout after commit, outside
the writer's critical path. A slow consumer should be disconnected and recover
through the existing replay protocol; transaction events themselves must not be
dropped or coalesced.

### 9. Request and listener dispatch allocate work per transaction

Every request RPC opens and closes a Unix socket, allocates parser atoms, and
starts a 250 ms interval. The server already accepts multiple frames per
connection. `src/seon/store/internal/wire_node.cljs:147-219`

Each foreign transaction then schedules one `setTimeout 0` callback per
Datahike listener. A burst can build an arbitrarily large timer queue containing
obsolete render work. `src/seon/store/wire.cljs:567-586`

Use one lifecycle-owned request connection with request ids and bounded
in-flight work, or a serialized keepalive connection if ordering is preferred.
Keep durable receipt retry semantics unchanged. Replace timer-per-listener with
one dispatcher that distinguishes lossless transaction/wake consumers from
coalescible render consumers.

### 10. Reconnect and own-write state need explicit bounds and teardown

During replay, the pub socket appends every live event to an unbounded vector,
including other databases until its database name is known.
`src/seon/store/wire.cljs:764-844`. Resolved own writes remain in
`!transactions` until a matching feed/watermark prunes them; a prolonged feed
failure can retain them. `src/seon/store/wire.cljs:280-319`

The adapter atom does not own the pub socket or reconnect timer and has no full
stop/reset lifecycle. Bound replay memory by attachment filtering and a maximum
gap/buffer policy that restarts replay from the last applied watermark. Make the
socket, timer, request channel, watermark, and in-flight receipts one explicit
attachment-owned runtime resource.

### 11. Per-write schema validation rereads the head

Every normal write resolves provenance entities and calls
`ensure-datahike-attrs!`, which dereferences the non-streaming connection to
inspect installed schema; divergence checks may dereference again.
`src/seon/db/internal.cljs:1625-1695`

Validation is necessary, but it should compare against the same explicit
database snapshot used to compile the transaction. Canonical schema restoration
also permits a version/signature fast path for already-installed attributes.
It must invalidate on attachment or schema-coordinate change, not become a
second registry authority.

## P2 — simplification and diagnostics

### 12. Delete unused remote APIs and duplicate volatile routing after proof

The writer retains an unbounded filtered-database handle map, a sequential
partial-commit `transact-batch` endpoint, remote query/pull operations, and a
global agent-to-database routing atom. Repository searches found no active-pod
production callers for the filtered handle or batch paths; their callers are
tests/older infrastructure. `src/seon/server/wire.clj:264-272`,
`src/seon/server/wire.clj:894-956`, and
`src/seon/server/wire.clj:1012-1040`

Confirm there is no external wire consumer, then delete them rather than
maintaining parallel read/config mechanisms. A forgotten filtered handle
currently retains an immutable database indefinitely.

### 13. Migrate old index format deliberately

The writer log reports that the live store lacks precomputed subtree counts, so
the new planner falls back to heuristics. The fallback is intentional and safe,
but it means current query performance does not exercise the maintained fork's
fast cardinality estimator.
`reference-code/datahike/src/datahike/query/estimate.cljc:33-79`

Add an explicit, mechanically verified store-format migration/reindex step to
the lifecycle plan. Never silently rewrite the only branch during ordinary
boot. Prove a disposable branch/copy first, verify history and secondary
indices, then move the attachment with a fence.

### 14. Remove stale operator guidance and log noise

The Datahike skill and `src/seon/CLAUDE.md` still describe retired provenance
and boot/config behavior; root instructions claim a `src/seon/AGENTS.md` that
does not exist. This makes future fixes likely to recreate deleted paths. The
pod also writes a heartbeat debug line every minute indefinitely.

Update those only after the new mechanisms land, so guidance describes one
implemented path rather than an aspiration. Keep health metrics structured and
queryable; remove routine heartbeat log spam.

## Recommended implementation order

1. Add observability at the existing seams: transaction stage timings,
   connection materialization counts, query-cache retention, listener queue,
   pub write time, reconnect gap/buffer, sockets, and process resources.
2. Delete the server reactive-subscription engine and prove writer reopen is
   zero-write with only the raw broadcast listener.
3. Implement the pure exact desired-state compiler plus head/CAS fence and
   zero-RPC empty delta.
4. Move boot/config/routes/skills onto that compiler; make config application
   explicit and optional; remove the separate config healer.
5. Persist and atomically restore the canonical Malli registry, then instrument
   once from that accepted snapshot and incrementally on definition changes.
6. Establish one database snapshot per operation and fix coordinate-keyed
   derived caches. Remove broad provenance scans from startup.
7. Make the transaction channel attachment-routed and bounded: asynchronous
   fanout, persistent request connection, one listener dispatcher, bounded
   reconnect, and explicit teardown.
8. Correct and measure Datahike result-cache retention in the maintained fork;
   tune only after grown-store profiles.
9. Complete full attachment coordinates, same-store writable branch lifecycle,
   fenced restore, and old-index migration; delete physical-copy and bare-agent
   routing paths.
10. Delete unused filtered/batch/remote-read APIs and stale documentation, then
    run the full cold-resume, interruption, and grown-store soak gates.

## Measurable acceptance gates

### Convergence and boot

- Reopening a converged writer creates zero transactions and installs exactly
  one raw transaction-broadcast listener.
- Restarting a populated pod with no config overlay creates zero database
  transactions and performs no full-store provenance scan.
- Applying an unchanged explicit config performs zero RPCs; one changed desired
  subset commits exactly one transaction; a second apply performs zero RPCs.
- A no-overlay populated restart is web-ready in under 2 seconds at p95 on the
  current development machine; a fresh current-core store is ready in under
  5 seconds at p95; minting an agent remains under 1 second at p95 and does no
  core/schema/config work.
- One render group, turn, debug unit, or desired-state compile materializes at
  most one head database value unless it explicitly crosses a transaction.

### Wire and feed

- Metrics expose p50/p95/p99 for validation, head read, request wait, writer
  queue, transaction/commit, broadcast enqueue, feed delivery, and local RYOW.
- A stalled pub consumer adds less than 5 ms to writer transaction p99, is
  disconnected at its bound, and recovers every transaction through replay.
- A pod receives zero events for another attachment.
- A 10,000-transaction reconnect test has no missing/duplicate listener
  deliveries, bounded replay memory, stable descriptors/threads, and a
  monotonically advancing persisted watermark.
- A 10,000-request write test reuses the request channel and shows no monotonic
  socket, interval, thread, or receipt growth.

### Cache and memory

- Query-cache metrics expose hit, miss, eviction, snapshot buckets, estimated
  retained tokens/bytes, and the largest entry.
- A single one-row result containing a large transcript cannot bypass the cache
  budget by weighing one.
- With a grown transcript and open normal/debug feeds, RSS reaches a plateau
  after warmup and remains within an agreed budget for a 30-minute soak; the
  test records GC, query-cache, SCI/render, and Konserve-cache measurements so
  attribution is possible.
- Idle combined CPU remains below 1% after warmup, with no monotonic descriptor,
  timer, listener, or thread growth.

### Branch and recovery

- Two branches may contain the same agent ids without cross-routing reads,
  writes, or feed events.
- `as-of` simulation and writable fork share the maintained Datahike read/index
  implementation; no second query engine or physical-copy-only branch exists.
- Fenced restore either moves the expected branch head atomically or returns a
  conflict value. A crash before/after the move resumes from a valid head.
- Old-format reindex is proven on a disposable branch, preserves history and
  secondary-index answers, and removes the estimator fallback warning before a
  production head is moved.

## Decisions to confirm with the user

The source strongly supports the recommended defaults, but these are the three
scope decisions worth asking directly:

1. May the unused `:seon.subscription/*` server engine and wire API be deleted
   outright, assuming one final repository/external-consumer check finds no
   caller? Recommended: yes.
2. Is one pod permanently attached to exactly one `{store, branch}` coordinate
   at a time? Recommended: yes; bind both request and pub connections to it and
   require an explicit lifecycle transition to switch.
3. Are the old remote query/pull/filter and partial `transact-batch` operations
   public compatibility commitments? Recommended: no; delete them after the
   external-consumer check and keep one local-read/one serialized-write path.

## Source map

- Active database facade and read capture:
  `src/seon/db.cljs:345-483`, `src/seon/db.cljs:1342-1420`, and
  `src/seon/db.cljs:1565-1847`
- Transaction validation/provenance: `src/seon/db/internal.cljs:1389-1450` and
  `src/seon/db/internal.cljs:1625-1695`
- Boot and current desired-state application: `src/seon/client.cljs:2203-2471`
  and `src/seon/state.cljs:59-139`
- Reader/writer adapter: `src/seon/store/wire.cljs:250-910` and
  `src/seon/store/internal/wire_node.cljs:147-225`
- JVM request, registry, and fanout: `src/seon/server/wire.clj:251-272`,
  `src/seon/server/wire.clj:770-1050`, `src/seon/server/broadcast.clj:79-120`,
  and `src/seon/server/registry.clj:210-508`
- Old reactive engine installation: `src/seon/server/boot.clj:115-195`
- Datahike non-streaming materialization and caches:
  `reference-code/datahike/src/datahike/connector.cljc:80-90`,
  `reference-code/datahike/src/datahike/writing.cljc:226-287`,
  `reference-code/datahike/src/datahike/query.cljc:2340-2582`, and
  `reference-code/datahike/src/datahike/query.cljc:4002-4033`
- Datahike/Konserve cache defaults:
  `reference-code/datahike/src/datahike/config.cljc:18-24`,
  `reference-code/datahike/src/datahike/db/search.cljc:17-26`, and
  `reference-code/datahike/src/datahike/store.cljc:25-35`
