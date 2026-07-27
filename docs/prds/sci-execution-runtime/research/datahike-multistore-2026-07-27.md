---
type: research
status: complete
tags: [research, database, runtime]
---

# Datahike multi-store and self-writer grounding for B1 (2026-07-27)

## Answer

**Yes, with one important correction.** Datahike already runs independent
databases concurrently in one JVM. Each unique `:self` connection constructs
its own `LocalWriter`, with its own transaction queue, commit queue, processing
loop, and commit loop. B1 therefore needs one explicitly keyed store component
per physical store, not one process-wide writer and not a new serialization
layer across stores. Datahike's unit of writer serialization is the connection,
whose `:self` identity is `[store-id branch]`; it is not the physical store.
`reference-code/datahike/src/datahike/store.cljc:50-61`;
`reference-code/datahike/src/datahike/connector.cljc:362-373`;
`reference-code/datahike/src/datahike/writer.cljc:42-76`;
`reference-code/datahike/src/datahike/writer.cljc:282-306`.

The qualification is decisive for O2. A second call in the same process for
the same `[store-id branch]` and acquisition configuration receives the same
connection and writer through Datahike's process registry, but another branch
of the same store has another connection ID and therefore another writer. A
different JVM has another registry altogether. Datahike itself states that
writers for a database are expected to share one JVM and that a writer in
another process is outside its model; without branch-head fencing, two writers
can lose each other's commits. `reference-code/datahike/src/datahike/connections.cljc:3-3`;
`reference-code/datahike/src/datahike/connections.cljc:37-92`;
`reference-code/datahike/src/datahike/gc_guard.cljc:32-41`.

Thus the owner's phrase “separate writers as flows” is right at the ownership
level: each B1 store component owns its Datahike connection/writer independently.
The maintained Datahike implementation is two `core.async` `go` loops, not a
`clojure.core.async.flow` proc, so the B1 contract should say **one self writer
per store component** rather than prescribe another scheduler abstraction.
`reference-code/datahike/src/datahike/writer.cljc:85-101`;
`reference-code/datahike/src/datahike/writer.cljc:201-269`.

## Dependency ledger and scope

| Dependency or owner | Revision read | Source boundary |
|---|---|---|
| Datahike maintained fork | `caf526850084a9d5846ccd9ea34251fe411e0d6b` | Connection identity/registry, `:self` writer, commit ordering, query/schema/report caches, persistent-set storage, and database create/reopen paths. The public connect path normalizes configuration before entering the registry-backed implementation. `reference-code/datahike/src/datahike/connector.cljc:275-320`; `reference-code/datahike/src/datahike/connector.cljc:438-452`. |
| Konserve maintained fork | `b5c99bc02a7175652a610324215288b78551801f` | File-store open/release, blob locking, atomic replacement, per-store handlers/locks, and serializers. `reference-code/konserve/src/konserve/store.cljc:268-313`; `reference-code/konserve/src/konserve/impl/defaults.cljc:736-774`. |
| core.async maintained fork | `dc35f3e0d7bc2eef502e77982f48641f025c8051` | Process-wide executor ownership used by `go` work. `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:71-116`. |
| Seon State A | `35a5c75a8ae45001f759d31e932c3f6bc69746e1` | Current backend identity/config, database registry, server, request executor, and UDS authority. `src/seon/db/backend.clj:73-197`; `src/seon/db/registry.clj:1-30`; `src/seon/db/server.clj:481-539`; `src/seon/db/writer.clj:4718-4800`. |
| B0/B1 target | working tree, read-only | B0 bans an ambient-one-cluster singleton and requires keyed multi-instance state; B1 selects in-process Datahike, one `flock`, one write connection per store, and permits several stores in one process. `docs/prds/sci-execution-runtime/plan/README.md:532-555`. |

The scope is the JVM `:self` writer with Konserve's `:file` backend. Remote
writer backends intentionally append the backend to the connection ID and are
not the B1 agent path. `reference-code/datahike/src/datahike/store.cljc:50-61`.

## 1. Connection identity and writer serialization

### One process registry deduplicates the same connection

Datahike's registry is the dynamic process-global atom `*connections*`. A
`:self` connection ID is `[store-id branch]`; its registry entry carries the
connection, reference count, generation, acquisition key, physical-store key,
and write hooks. `reference-code/datahike/src/datahike/connections.cljc:1-3`;
`reference-code/datahike/src/datahike/store.cljc:41-61`;
`reference-code/datahike/src/datahike/connections.cljc:94-111`.

Opening the same connection and matching physical/acquisition keys increments
the reference count and returns the existing connection. Concurrent first
opens share one completion; incompatible reuse of the same connection ID
returns `:config-mismatch`. The first caller alone becomes `:owner`.
`reference-code/datahike/src/datahike/connections.cljc:37-92`.

The owner alone calls `ks/connect-store`, restores the configured branch,
materializes the database value, creates the connection, and installs the
writer. It then publishes that connection into the registry before waking
waiters. `reference-code/datahike/src/datahike/connector.cljc:275-320`;
`reference-code/datahike/src/datahike/connector.cljc:324-373`;
`reference-code/datahike/src/datahike/connector.cljc:395-397`.

Final release is also reference-counted. Only the last releaser closes query
cache/report ownership, drains and shuts down the writer, closes secondary
resources, releases the Konserve store, and deletes the registry entry.
`reference-code/datahike/src/datahike/connector.cljc:454-524`;
`reference-code/datahike/src/datahike/connector.cljc:535-540`;
`reference-code/datahike/src/datahike/connections.cljc:11-35`;
`reference-code/datahike/src/datahike/connections.cljc:124-127`.

### The queues are per connection

`create-writer :self` calls `create-thread` for the supplied connection.
`create-thread` allocates one fixed-buffer transaction channel and one
fixed-buffer commit channel, each defaulting to capacity 120,000, then returns
those channels and both loops to one `LocalWriter`.
`reference-code/datahike/src/datahike/writer.cljc:78-92`;
`reference-code/datahike/src/datahike/writer.cljc:269-306`.

The processing loop threads one immutable `old` database value through
accepted transaction functions and advances that value only when the result
is admitted to its commit queue. The commit loop drains a batch from that
queue, performs one durable `commit!`, resets that connection to the committed
database value, and resolves every transaction report in the batch.
`reference-code/datahike/src/datahike/writer.cljc:94-188`;
`reference-code/datahike/src/datahike/writer.cljc:201-268`.

Serialization is therefore **per connection**, which for `:self` normally
means per `[store-id branch]`. It is not per JVM and not per physical store.
Datahike's commit source explicitly assumes a single writer for the branch and
describes concurrent writers as last-writer-wins data loss.
`reference-code/datahike/src/datahike/store.cljc:50-61`;
`reference-code/datahike/src/datahike/writing.cljc:321-348`.

Two connections to one physical store on different branches are possible:
their connection IDs differ, so each is an owner with its own writer. Datahike
does share the write-hooks atom between registry entries with the same
physical-store key, but that coordinates hooks, not commit serialization.
`reference-code/datahike/src/datahike/connections.cljc:59-73`;
`reference-code/datahike/src/datahike/connector.cljc:357-373`.

Datahike's delete path further demonstrates this distinction: it scans every
active `[store-id branch]` registry key and refuses physical database deletion
while any branch connection remains. `reference-code/datahike/src/datahike/writing.cljc:717-731`.

## 2. N stores in one JVM and shared mutable points

N distinct stores have distinct connection IDs, Konserve store objects,
database values, persistent-set storage, writer queues, and writer loops.
There is no database-content singleton in the open path: all of those values
are constructed inside the first-owner branch and attached to that connection.
`reference-code/datahike/src/datahike/connector.cljc:316-373`;
`reference-code/datahike/src/datahike/index/persistent_set.cljc:541-548`.

The JVM does contain the following deliberately process-wide facilities.
They do not merge the stores' database values, but they are real
cross-store resource coupling:

| Shared point | Source-grounded effect between stores |
|---|---|
| Connection registry | One atom contains all keyed connection entries. Same-ID opens deduplicate; distinct store IDs remain distinct entries. Registry contention is shared, but connection state is keyed. `reference-code/datahike/src/datahike/connections.cljc:3-3`; `reference-code/datahike/src/datahike/connections.cljc:37-111`. |
| Parsed-query and query-plan LRUs | Two process-wide volatile LRUs each have a 100-entry bound. Load from one store may evict reusable query/plan entries used by another store; the cache holds derivations, not database contents. `reference-code/datahike/src/datahike/query.cljc:61-61`; `reference-code/datahike/src/datahike/query.cljc:2413-2418`. |
| Query-result cache | One global atom owns a weighted LRU, with defaults of 64 database snapshots and structural weight 1,000,000. Keys include exact `[connection-id generation commit-id]`, so stores cannot alias results, but they share eviction capacity. `reference-code/datahike/src/datahike/query.cljc:2428-2464`; `reference-code/datahike/src/datahike/query.cljc:2479-2491`; `reference-code/datahike/src/datahike/query.cljc:2524-2528`. |
| Cold-query single flight | One process coordinator admits at most 1,024 distinct cold computations; when full, another request takes the explicit overflow path. Store A can consume admission capacity and cause store B to bypass coordination, without changing B's database value. `reference-code/datahike/src/datahike/query/single_flight.cljc:3-17`; `reference-code/datahike/src/datahike/query/single_flight.cljc:103-145`. |
| Schema caches | The schema read cache is shared and bounded at 1,024 entries. The write-cache registry is also shared, but is an LRU of per-store caches keyed by store ID and bounded at 1,024 stores. Cross-store pressure can evict cache entries; write-cache contents remain keyed. `reference-code/datahike/src/datahike/config.cljc:14-16`; `reference-code/datahike/src/datahike/schema_cache.cljc:7-20`; `reference-code/datahike/src/datahike/schema_cache.cljc:31-41`. |
| Committed-report readiness | One global source atom and one 4,096-slot ready-source queue serve all connection generations. Sources are scoped by `[connection-id generation]`, but enough active sources in A can exhaust the process-wide admission bound before B opens another source. `reference-code/datahike/src/datahike/committed_report.cljc:7-11`; `reference-code/datahike/src/datahike/committed_report.cljc:61-96`. |
| GC write guard | One global atom tracks in-flight unreferenced-write sequences, keyed by store ID. Separate stores do not share a guard entry; operations contend only on the atom. The source explicitly says a different process is outside this guard's model. `reference-code/datahike/src/datahike/gc_guard.cljc:36-55`; `reference-code/datahike/src/datahike/gc_guard.cljc:57-102`. |
| core.async execution | Datahike's writer loops are `go` work. core.async memoizes one executor per workload for the process, so stores share executor scheduling and threads rather than receiving a pool per store. `reference-code/datahike/src/datahike/writer.cljc:94-95`; `reference-code/datahike/src/datahike/writer.cljc:201-203`; `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:71-116`. |
| JVM heap and GC | Every connection retains its database value, caches, queues, and lazy index nodes in the same heap. Allocation or queue growth in A can therefore increase GC pauses experienced by B, even though their values are disjoint. `reference-code/datahike/src/datahike/db.cljc:307-314`; `reference-code/datahike/src/datahike/writer.cljc:78-92`; `reference-code/datahike/src/datahike/index/persistent_set.cljc:409-469`. |
| File system/device | Konserve forces each updated blob and then the backing directory after atomic replacement. Distinct stores on the same device can contend for I/O and fsync latency even though their paths and keys are separate. `reference-code/konserve/src/konserve/impl/defaults.cljc:85-116`; `reference-code/konserve/src/konserve/filestore.clj:195-217`. |

Other process-global atoms are keyed or metadata-only rather than store
authorities. Temporal Date-to-transaction resolution uses a global cache keyed
by store ID, branch, basis transaction, and Date and drops the whole cache after 2,048
entries; the legacy datom-search cache is a five-database LRU used only when
the database config supplies nonzero `:cache-size`. The compatibility
`tempid` counter shares negative-number allocation across all callers, which
cannot couple durable entities because those values are transaction-local
temporary IDs. `reference-code/datahike/src/datahike/query/execute.cljc:679-719`;
`reference-code/datahike/src/datahike/db/search.cljc:17-26`;
`reference-code/datahike/src/datahike/core.cljc:219-234`.

The secondary-index factory registry and the query engine's late-binding
registry are global mutable code catalogs, not per-store data. Registering or
replacing an implementation changes behavior process-wide, but ordinary store
load does not mutate either registry. `reference-code/datahike/src/datahike/index/secondary.cljc:318-336`;
`reference-code/datahike/src/datahike/query/relation.cljc:279-299`.

Two opt-in/out-of-scope paths have additional keyed global state. The
optimistic overlay API holds a global atom keyed by live connection and adds
per-connection overlay/listener atoms on registration; B1 need not enable it
at the store rung. Konserve's `:memory` backend has a global registry keyed by
memory-store ID, while each registered memory store owns its own state,
handlers, locks, and hooks; B1's durable `:file` path does not use that
registry. `reference-code/datahike/src/datahike/optimistic.cljc:39-46`;
`reference-code/datahike/src/datahike/optimistic.cljc:415-473`;
`reference-code/konserve/src/konserve/memory.cljc:11-20`;
`reference-code/konserve/src/konserve/memory.cljc:166-190`.

Konserve does not add a global mutable serializer or per-key lock authority
across file stores. Its global serializer maps contain stateless serializer
records; every serialization constructs its writer from immutable handler
maps. Each `DefaultStore` instead receives its own handler atoms, lock atom,
and write-hooks atom. `reference-code/konserve/src/konserve/serializers.cljc:29-59`;
`reference-code/konserve/src/konserve/serializers.cljc:81-96`;
`reference-code/konserve/src/konserve/impl/defaults.cljc:475-476`;
`reference-code/konserve/src/konserve/impl/defaults.cljc:736-774`.

Konserve's application-level key locks are consequently per `DefaultStore`.
The registry is an atom of key-to-semaphore entries created from
`(:locks store)`; separately opened `DefaultStore` instances for the same path
do not share it. `reference-code/konserve/src/konserve/core.cljc:77-89`;
`reference-code/konserve/src/konserve/core.cljc:125-171`;
`reference-code/konserve/src/konserve/impl/defaults.cljc:761-773`.

The answer to “beyond CPU?” is therefore **yes, bounded resource coupling but
not database-state coupling**: heap/GC, executor scheduling, cache eviction,
global admission caps, and shared-device I/O are process-wide. None of the
examined paths supplies a global transaction queue or makes one store's
branch head part of another store's database value. `reference-code/datahike/src/datahike/writer.cljc:85-92`;
`reference-code/datahike/src/datahike/query.cljc:2431-2491`;
`reference-code/datahike/src/datahike/connector.cljc:319-373`.

## 3. File-store opening and the missing writer fence

### What exists today

Konserve's `:file` `connect-store` checks that the directory exists and then
calls `connect-fs-store`; `create-store` checks that it does not exist and calls
the same function. `connect-fs-store` builds a fresh `BackingFilestore` and
`DefaultStore`. Neither path acquires a store-lifetime file lock, and
`:file` release is explicitly a no-op. `reference-code/konserve/src/konserve/store.cljc:271-313`;
`reference-code/konserve/src/konserve/filestore.clj:662-703`.

The file backend does acquire OS `FileLock`s, but only on individual blob
channels around one key operation; the lock is released at the end of that
operation. The default file configuration enables `:lock-blob?`, and
`io-operation` acquires and releases the blob lock around a read or update.
`reference-code/konserve/src/konserve/filestore.clj:219-223`;
`reference-code/konserve/src/konserve/filestore.clj:365-369`;
`reference-code/konserve/src/konserve/filestore.clj:431-436`;
`reference-code/konserve/src/konserve/filestore.clj:685-695`;
`reference-code/konserve/src/konserve/impl/defaults.cljc:303-316`;
`reference-code/konserve/src/konserve/impl/defaults.cljc:321-351`.

Those per-blob locks cannot protect a Datahike commit. One commit writes
content-addressed schema/index values and a commit record before replacing the
mutable branch head. Two writers can each complete individually locked key
writes and then overwrite the same head; Datahike documents that outcome as
last-writer-wins loss. `reference-code/datahike/src/datahike/writing.cljc:321-348`;
`reference-code/datahike/src/datahike/writing.cljc:423-565`.

### The one fenced place

The B1 fence belongs in the **store component's lifetime open/close boundary,
above Datahike**. That one component must resolve the canonical physical file
store, acquire a non-blocking exclusive `flock`, and only then perform the
existence/create/connect sequence; it must hold the lock descriptor until
after final `d/release`. Wrapping only Datahike's connection-owner call to
`ks/connect-store` would miss `database-exists?` and `create-database`, both of
which independently open the store before `connect`. `reference-code/datahike/src/datahike/connector.cljc:275-320`;
`reference-code/datahike/src/datahike/writing.cljc:608-628`;
`reference-code/datahike/src/datahike/writing.cljc:630-715`;
`reference-code/datahike/src/datahike/connector.cljc:454-524`.

If the fence were placed in the dependency instead, the narrow Konserve seam
would be the `:file` `-connect-store`/`-create-store` methods feeding
`connect-fs-store`, with the returned store retaining the lock handle and the
currently no-op `:file` `-release-store` releasing it. That would fence every
file-store opener, including readers, so the B1 application-lifetime wrapper
is the better semantic owner for a writer-only assertion.
`reference-code/konserve/src/konserve/store.cljc:271-313`;
`reference-code/konserve/src/konserve/filestore.clj:662-703`.

The lock file should not become a Konserve key inside the store directory:
the file backend enumerates every first-level file for keys and hands foreign
entries to migration handling. The fence should use a canonical adjacent
operator-owned path or an explicitly ephemeral entry excluded from enumeration.
`reference-code/konserve/src/konserve/filestore.clj:101-121`;
`reference-code/konserve/src/konserve/filestore.clj:177-182`.

The same-process connection registry remains useful beneath this fence, but
it is not the fence. Exact repeated opens share a reference-counted connection;
different processes cannot see that atom, and a different branch is a
different connection ID. B1 must therefore own exactly one lock handle and
one write connection per physical store in an explicit map keyed by store,
never in an ambient-one-cluster var. `reference-code/datahike/src/datahike/connections.cljc:3-3`;
`reference-code/datahike/src/datahike/connections.cljc:37-111`;
`reference-code/datahike/src/datahike/store.cljc:50-61`.

## 4. Kill -9 and reopen behavior

### Established-store commits

Datahike's durability protocol is values first, pointer last. `commit!` opens
the in-process GC write guard, flushes index nodes and schema metadata, writes
the immutable commit record, waits for those writes, and replaces the branch
head only last. `reference-code/datahike/src/datahike/writing.cljc:423-446`;
`reference-code/datahike/src/datahike/writing.cljc:477-565`.

Konserve implements each key replacement by writing a `.new` blob, forcing the
blob, closing it, atomically moving it over the key, and then forcing the store
directory. The file backend requests `ATOMIC_MOVE` plus `REPLACE_EXISTING`.
`reference-code/konserve/src/konserve/impl/defaults.cljc:85-116`;
`reference-code/konserve/src/konserve/filestore.clj:195-201`.

On reopen, Datahike reads the configured branch head, converts that stored
database value back into a database value, and attaches lazy index storage.
It does not replay a transaction log in this path. A kill before the atomic
head replacement therefore reopens the old head; newly written values and
commit records are unreachable garbage. A kill after the head replacement
reopens the new head, whose referenced values were forced first.
`reference-code/datahike/src/datahike/connector.cljc:319-368`;
`reference-code/datahike/src/datahike/writing.cljc:230-295`;
`reference-code/datahike/src/datahike/gc_guard.cljc:32-40`.

This clean two-outcome argument depends on the one-writer invariant. With two
processes, the last head replacement can discard the other process's already
successful commit without producing a torn blob or transaction error.
`reference-code/datahike/src/datahike/writing.cljc:321-348`;
`reference-code/datahike/src/datahike/gc_guard.cljc:36-41`.

### First-create crash window

Initial database creation has an additional source-visible window. It writes
the immutable commit, then mutable `:db` branch head, then the `:branches`
roster last. `database-exists?` checks only whether `:db` exists. A kill after
`:db` lands but before `:branches` lands can therefore reopen the main branch
and report that the database exists while its branch roster is absent.
`reference-code/datahike/src/datahike/writing.cljc:630-715`;
`reference-code/datahike/src/datahike/writing.cljc:608-625`.

B1's “clean reopen after kill -9” proof must distinguish established-store
commit recovery from first creation. Under the lifetime flock, creation must
either complete the required `:db` plus `:branches` state before publishing
readiness, or detect and refuse/repair the `:db`-present,
`:branches`-missing partial creation on the next open. The normal connector
reads the configured branch key directly, so merely proving `d/connect`
succeeds does not prove the branch roster is complete.
`reference-code/datahike/src/datahike/connector.cljc:319-330`;
`reference-code/datahike/src/datahike/writing.cljc:707-712`.

## 5. Retained memory and ten-store sanity

One warm connection retains its current immutable `DB` record: schema maps,
three current index roots, three temporal index roots when history is enabled,
identity maps, secondary indices, metadata, and cache context. Restored roots
are attached to storage lazily rather than eagerly realizing the entire index.
`reference-code/datahike/src/datahike/db.cljc:307-314`;
`reference-code/datahike/src/datahike/writing.cljc:230-295`.

For the maintained persistent-set index, each connection owns a
`CachedStorage` with an LRU whose default threshold is 1,000 nodes, plus small
stats, pending-write, freed-address, freed-set, and freelist atoms. Datahike
also wraps the raw Konserve store in a separate per-connection LRU with the
same configured threshold. `reference-code/datahike/src/datahike/config.cljc:18-24`;
`reference-code/datahike/src/datahike/index/persistent_set.cljc:409-469`;
`reference-code/datahike/src/datahike/index/persistent_set.cljc:541-548`;
`reference-code/datahike/src/datahike/store.cljc:25-35`.

Each self writer additionally owns two buffered channels with default
capacities of 120,000 entries and two active loops. Empty capacity is not the
same as 240,000 retained transactions, but queue occupancy under load can
become the dominant per-store heap cost. `reference-code/datahike/src/datahike/writer.cljc:42-92`;
`reference-code/datahike/src/datahike/writer.cljc:94-104`;
`reference-code/datahike/src/datahike/writer.cljc:171-181`.

The global query-result cache remains bounded across all stores rather than
multiplying its 64-snapshot/1,000,000-weight defaults per connection. Parsed
query, plan, and schema caches are also shared bounded resources.
`reference-code/datahike/src/datahike/query.cljc:2413-2418`;
`reference-code/datahike/src/datahike/query.cljc:2445-2491`;
`reference-code/datahike/src/datahike/schema_cache.cljc:7-20`.

**Source-grounded judgment:** hosting ten ordinary warm stores in one JVM is
structurally sane. The retained shape scales roughly with ten database values,
ten pairs of per-connection storage LRUs, ten writer queue pairs, and any
materialized secondary indices—not with ten fully realized databases. The
source provides no byte-size guarantee, so B1 should treat heap/GC and queue
occupancy as observable per-store/process capacity, especially when
`keep-history?` and secondary indices are enabled.
`reference-code/datahike/src/datahike/db.cljc:307-314`;
`reference-code/datahike/src/datahike/index/persistent_set.cljc:461-469`;
`reference-code/datahike/src/datahike/writer.cljc:78-92`.

Ten stores are not ten fault-isolated processes: a process OOM, long GC pause,
or exhaustion of a process-wide admission cap affects every resident store.
That is a resource/failure-domain trade, not a Datahike consistency coupling.
`reference-code/datahike/src/datahike/query/single_flight.cljc:3-17`;
`reference-code/datahike/src/datahike/committed_report.cljc:7-11`;
`reference-code/datahike/src/datahike/index/persistent_set.cljc:409-469`.

## State A disposition

- **Adopt:** retain the pure, explicit derivation of deterministic store UUID,
  canonical `data/clusters/<name>/db` path, `[store-id branch]`, and Datahike
  configuration from named database facts; this is already compatible with N
  keyed stores and contains no I/O. `src/seon/db/backend.clj:73-123`;
  `src/seon/db/backend.clj:125-197`.
- **Leave dead:** do not port the database-server/UDS/request-executor runtime
  or its second process registry as B1's connection authority. State A starts
  an addressed request server around a registry of live connections; O1 makes
  that wire authority unnecessary, while Datahike already reference-counts
  exact connections. `src/seon/db/registry.clj:1-30`;
  `src/seon/db/registry.clj:376-380`;
  `src/seon/db/server.clj:481-539`;
  `src/seon/db/writer.clj:4718-4800`;
  `src/seon/db/writer.clj:4817-4859`.

## B1 contract implications

- Encode one explicit store component per canonical physical store; a JVM may
  own N such components concurrently, and no function may obtain “the”
  cluster/store/connection from an ambient singleton.
  `docs/prds/sci-execution-runtime/plan/README.md:532-555`;
  `reference-code/datahike/src/datahike/connector.cljc:275-373`.
- Encode exactly one live write connection for the entire physical store, not
  merely one per `[store-id branch]`; clusters never share a store. Datahike's
  built-in writer serialization stops at the connection boundary.
  `reference-code/datahike/src/datahike/store.cljc:50-61`;
  `reference-code/datahike/src/datahike/writing.cljc:321-348`.
- Reuse Datahike's `:self` `LocalWriter` per store component. Do not add a
  cross-store transaction queue or serialize independent stores through one
  process-wide writer.
  `reference-code/datahike/src/datahike/writer.cljc:85-92`;
  `reference-code/datahike/src/datahike/writer.cljc:282-306`.
- Acquire one non-blocking exclusive flock on the canonical physical-store
  identity before existence check, creation, or connection; refuse immediately
  when held elsewhere; retain the descriptor through final Datahike release.
  `reference-code/datahike/src/datahike/writing.cljc:608-715`;
  `reference-code/datahike/src/datahike/connector.cljc:454-524`;
  `reference-code/konserve/src/konserve/store.cljc:271-313`.
- Do not count Datahike's process connection registry, Konserve's per-store
  lock atom, or per-blob OS locks as the O2 fence; none spans two JVMs for the
  lifetime of a multi-key Datahike commit.
  `reference-code/datahike/src/datahike/connections.cljc:3-3`;
  `reference-code/konserve/src/konserve/core.cljc:125-171`;
  `reference-code/konserve/src/konserve/filestore.clj:365-436`;
  `reference-code/datahike/src/datahike/writing.cljc:423-565`.
- Keep the flock outside Konserve's ordinary key namespace, or explicitly
  exclude it from file enumeration and migration.
  `reference-code/konserve/src/konserve/filestore.clj:101-121`;
  `reference-code/konserve/src/konserve/filestore.clj:177-182`.
- Make readiness depend on a completely opened connection and completely
  initialized store. First-create recovery must detect the `:db`-present,
  `:branches`-missing window; a successful main-branch `connect` alone is
  insufficient proof.
  `reference-code/datahike/src/datahike/writing.cljc:608-625`;
  `reference-code/datahike/src/datahike/writing.cljc:630-715`;
  `reference-code/datahike/src/datahike/connector.cljc:319-330`.
- Prove kill -9 recovery at two boundaries: an established commit yields
  exactly the old or new branch head, and interrupted initial creation never
  publishes a partially initialized store as ready.
  `reference-code/datahike/src/datahike/writing.cljc:423-565`;
  `reference-code/konserve/src/konserve/impl/defaults.cljc:85-116`;
  `reference-code/datahike/src/datahike/writing.cljc:707-712`.
- Treat query/schema cache eviction, cold-query single-flight admission,
  committed-report source capacity, core.async scheduling, file I/O, heap, and
  GC as process-wide capacity concerns. Key observability by store and process;
  do not misdescribe those shared resources as shared database state.
  `reference-code/datahike/src/datahike/query.cljc:2413-2491`;
  `reference-code/datahike/src/datahike/query/single_flight.cljc:3-17`;
  `reference-code/datahike/src/datahike/committed_report.cljc:7-11`;
  `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:98-116`.
- Bound or observe each store's transaction/commit queue occupancy and
  per-connection caches before raising resident-store count; ten warm stores
  are supported by the ownership structure, not guaranteed by a fixed memory
  budget in the source.
  `reference-code/datahike/src/datahike/writer.cljc:78-104`;
  `reference-code/datahike/src/datahike/index/persistent_set.cljc:461-469`;
  `reference-code/datahike/src/datahike/store.cljc:25-35`.
