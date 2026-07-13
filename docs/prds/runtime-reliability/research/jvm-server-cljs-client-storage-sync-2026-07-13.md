---
type: research
status: active
tags: [research, database, flow]
---

# JVM server and CLJS client storage/synchronization boundary

## TL;DR

Archive the **legacy JVM application**, not the JVM. The target system still has
an active, load-bearing JVM server: it owns the one serialized Datahike writer,
durable storage, transaction receipts, branch/time-travel operations, embeddings,
secondary indexes, and other heavy work. The CLJS side owns agent execution and
the one web UI/render implementation.

Datahike already contains the right replication substrate for remote CLJS
clients: immutable, content-addressed index nodes plus mutable branch-head
pointers can be synchronized into a local Konserve store, including an IndexedDB
store in a browser or Tauri webview. This is the Datahike **Distributed Index
Space** model. The current Seon co-located topology is already a version of it:
Node reads the JVM writer's file-backed store locally and uses an ordered
transaction feed as the wake/recovery channel.

The remote design should **not** yet claim that clients can become exact replicas
by simply applying streamed transaction datoms. Datahike's transactor accepts
`Datom` values and preserves supplied transaction ids, but there is no public,
packaged, mechanically proven `apply-committed-transaction!` replica operation.
Ordinary `with`/`transact!` also performs transaction processing and transaction
metadata generation. Storage-root synchronization through Konserve is the
implemented replica mechanism today.

Kabel plus `konserve-sync` is useful source, not a production-ready dependency we
can drop in unchanged:

- Datahike labels CLJS and Kabel support beta.
- `src-kabel` and its dependencies are present only in Datahike's `:test` alias,
  not its normal source paths or runtime dependencies.
- the connector advances a local connection from synchronized Konserve roots,
  but it does not subscribe its separate `tx_broadcast` channel to deliver
  foreign transactions through normal `d/listen!` callbacks;
- `KabelWriter` can wait forever for store synchronization and has no durable
  same-request-id receipt/recovery contract;
- current Seon already has the stronger pieces: durable transaction receipts,
  bounded same-id retries, read-your-own-write materialization, paged gap replay,
  replay/live overlap deduplication, and reconnect from a transaction watermark.

Therefore the least-duplicative path is to combine the proven pieces:

1. Retain Seon's writer command/receipt semantics and generalize the transport
   beyond the local Unix socket.
2. Promote or upstream Datahike's storage-root synchronization as a supported
   module; do not copy it into a parallel Seon implementation.
3. Synchronize current reachable index nodes into memory + IndexedDB, publish the
   new branch head only after all referenced nodes have landed, and then issue
   one explicit conservative root-advance invalidation for pure projections.
4. Reserve exact per-transaction replay for contracts that genuinely require it:
   write receipts, forensic/debug consumers, and durable effect processors.
   A UI derived from the current database does not need every intermediate wake.
5. Treat offline reads and offline writes separately. IndexedDB gives durable
   offline reads. Offline writes require a durable, idempotent command/fact
   outbox or an explicitly designed branch/merge protocol; Kabel itself provides
   neither.

S3 and GCS are supported through external Konserve backend packages, not by the
Datahike core artifact and not by Seon's current dependency/configuration. Modern
S3 and GCS provide strong per-object read-after-write consistency, but neither
makes Datahike's multi-object commit atomic. Datahike's node-first/head-last
commit order is what makes a torn commit safe. Direct object-store writes still
carry request latency and write amplification, so the authoritative cloud
topology must be benchmarked before choosing direct object storage over a local
hot store plus an explicitly defined durable mirror/backup policy.

## Scope and evidence rule

This audit answers four questions:

- What storage/distribution behavior do Datahike, Konserve, Kabel, and
  `konserve-sync` actually implement?
- Which parts of the existing JVM system are the intended server and which are
  legacy application paths?
- What can a low-resource CLJS/Tauri client safely do locally?
- Which desired behaviors remain Seon coordination work rather than library
  capabilities?

Source wins over prose documentation when they disagree. The inspected local
forks are:

- Datahike at `67934f65`, based on upstream `0.8.1729` plus Seon's transaction,
  writer-drain, connection, branch, and secondary-index fixes;
- Konserve at `df6818d`, based on upstream `0.9.356` plus Seon's compatibility
  work and the upstream ordered-batch, read-miss, and tiered-cache changes.

No cluster process was started or mutated for this research.

### Existing architecture text that must be corrected

Two contradictions in the idealized architecture should be resolved when this
decision lands:

- `docs/seon/architecture/architecture.md:27-31` assigns render/serve to the JVM,
  while `architecture.md:186-219` assigns the only browser-facing UI and renderer
  to Node and says the JVM handles data only. The latter matches the one-renderer
  target: JVM server + co-located/remote CLJS UI host.
- `architecture.md:40-51` currently declares compressed full transaction-log
  replay and a deterministic “trailing applier” as settled, with root transfer
  only a later optimization. The inspected library has the inverse maturity:
  storage-root synchronization is implemented, while an exact persisted datom
  applier is not a public/proven replica API. Rewrite this as the target decision
  after the owner answers open decision 1; do not leave an unsupported mechanism
  marked settled.

## Supported behavior versus proposed Seon behavior

| Capability | Library/source status | Consequence for Seon |
|---|---|---|
| One writer, many local readers | Implemented Datahike architecture | Keep as the invariant for every topology. |
| Immutable index nodes and a mutable branch head | Implemented Datahike storage format | Use the branch head as the publish boundary. |
| Co-located JVM writer + Node read-only file-store peer | Implemented and already used by Seon | Keep as the local/server-agent fast path. |
| Browser CLJS query/pull/entity APIs | Implemented, explicitly beta | Viable for a potato client after focused production proof. |
| IndexedDB Konserve backend | Implemented, async only | Viable local durable replica store; Tauri platform durability still needs proof. |
| Differential index-node synchronization over Kabel | Implemented in beta `src-kabel` + `konserve-sync` source | Promote/upstream and harden; it is not in the normal Datahike artifact path. |
| Exact foreign `d/listen!` parity on Kabel clients | Not integrated | Add one explicit root-advance invalidation and, where required, a tx-report/replay bridge. |
| Durable same-id remote write recovery | Implemented by Seon wire, absent from `KabelWriter` | Preserve Seon's receipt protocol across any new transport. |
| Exact replica construction by applying tx datoms | Building blocks exist; no packaged/proven replica API | Do not make this the canonical path without a Datahike-level primitive and equivalence proof. |
| Offline reads | Implemented by a completed local replica | Supported once the last complete root is durable. |
| Offline authoritative writes | Not provided by IndexedDB, Kabel, or store sync | Add a durable outbox or explicitly choose branch/CRDT semantics. |
| S3/GCS Konserve stores | External packages exist; absent from Seon | Add and benchmark one backend deliberately; do not treat a documentation example as deployment proof. |
| CLJS secondary-index parity | Not implemented in the inspected source | Keep embeddings/vector/full-text and other secondary-index work on the JVM. |
| Current-head catch-up followed by one UI refresh | Supported by immutable root sync; explicit invalidation is Seon work | Prefer this for pure projections over replaying every intermediate wake. |

## The database representation already supports read replicas

### Immutable values and one publish pointer

Datahike describes its Distributed Index Space as persistent indices shared
across processes, complementary to RPC
(`reference-code/datahike/doc/distributed.md:3-22`). A transaction writes new
copy-on-write index nodes and then publishes a new root. Readers can query an
immutable snapshot without an RPC round-trip and can cache/synchronize nodes
differentially (`distributed.md:24-47`).

The source matches the model. `db->stored` serializes:

- live EAVT, AEVT, and AVET roots;
- temporal roots when history is enabled;
- schema metadata, max transaction/eid, commit metadata, and configuration;
- JVM-only secondary-index key maps.

See `reference-code/datahike/src/datahike/writing.cljc:48-180`. Live storage
handles are deliberately detached before roots are stored and rebound by
`stored->db` for the reader's local store (`writing.cljc:135-141,226-287`). That
is the exact property needed for the same logical database to exist in a JVM
writer store, a Node file-store view, and a browser IndexedDB replica.

Content-addressed pending nodes are marked immutable
(`writing.cljc:378-389`). `commit!` then writes every referenced value before
the mutable branch-head pointer. The ordered multi-key path preserves the
writer's causal order; the non-batch path explicitly waits for nodes and the
commit record before publishing the head (`writing.cljc:456-504`). The source
also states why this matters: filesystems and S3 do not provide an atomic
multi-key transaction, so a crash may leave unreachable orphan nodes but must
not leave a visible head pointing at absent nodes (`writing.cljc:459-475`).

This yields the required reader rule:

> A replica may expose a new database value only after every node reachable from
> that database value is durable in the replica's local store.

### Branches are cheap pointers, not copied databases

Datahike branches are named head records over structurally shared immutable
nodes. `branch!` can start from another branch or a commit id and writes a new
branch head rather than duplicating the index (`reference-code/datahike/src/
datahike/versioning.cljc:153-197`). Deleting a branch requires its connections
to be released (`versioning.cljc:199-227`); forcing a head requires exclusive
writer ownership and makes existing connections stale
(`versioning.cljc:229-242`).

Commit-graph opt-out is incompatible with the desired debugging model. With
`:commit-graph? false`, branch-from-commit is unavailable
(`versioning.cljc:163-184`). Seon requires as-of, writable forks, restoration,
and provenance-chain inspection, so the server should retain the commit graph
unless a separate disposable database class proves it needs none of those.

History and branches are different costs. The sync walker can scope which branch
heads/nodes are copied, but a history-enabled branch head still reaches its
temporal indexes. A potato client cannot get a history-less copy merely by
requesting `:branches :trunk`; a genuinely lean current-state replica requires a
defined projection/export format or server RPC for history.

## Konserve guarantees and shared files

Konserve's guarantee is ACID **per key**, not across the whole Datahike commit
(`reference-code/konserve/doc/backend.org:55-70`). A backend must provide at
least read-committed views (`backend.org:72-80`). Konserve's ordinary key lock is
process-local; multiple writing processes require a backend distributed lock or
must be prohibited (`backend.org:81-90,155-174`). This reinforces rather than
relaxes Datahike's single-writer rule.

The JVM file backend writes a replacement blob and uses `Files/move` with
`ATOMIC_MOVE` and `REPLACE_EXISTING`
(`reference-code/konserve/src/konserve/filestore.clj:138-201`). The Node backend
uses an exclusive `.lock` file for writes and filesystem rename for the atomic
move (`reference-code/konserve/src/konserve/node_filestore.cljs:24-42,325-336`).

Current Seon goes one step further for readers: the CLJS connection opens the
shared file store with `:lock-blob? false` and never mutates it locally. Its
source documents that the mutable root is atomically replaced and index nodes
are immutable (`src/seon/store/wire.cljs:130-153`). That is a sound co-located
topology with exactly one JVM writer on the same local filesystem.

It is not a general network-filesystem contract. Do not assume the same behavior
for an arbitrary NFS mount, cloud-sync folder, or filesystem adapter without
proving atomic replace, read-committed visibility, and failure semantics.
Remote clients should use their own local Konserve replica instead of sharing
the server's file tree.

Readers must also remain readers operationally. They must never run storage
migration, garbage collection, deletion, branch forcing, or repair against the
writer's shared store. Those are server/writer responsibilities.

## Browser and Tauri storage

Datahike's CLJS query, pull, and entity surfaces run in Node and browser
environments, but the project explicitly marks the feature beta
(`reference-code/datahike/doc/cljs-support.md:1-17`). Browser persistence uses
Konserve's IndexedDB backend; operations are asynchronous
(`cljs-support.md:41-62`). The documented fast local shape is a memory frontend
over an IndexedDB backend (`cljs-support.md:64-80`).

Konserve's IndexedDB implementation is async-only and registers normal Konserve
create/connect/delete dispatch methods
(`reference-code/konserve/src/konserve/indexeddb.cljs:470-560`). It stores
Konserve values as blobs in one IndexedDB object store and relies on IndexedDB's
transactionality (`indexeddb.cljs:470-516`).

That proves browser-compatible persistence; it does not prove identical native
durability on every Tauri target. WebView quota, eviction, backup, background
suspension, multi-window access, and IndexedDB implementation behavior differ by
macOS, Windows, iOS, and Android. The first client milestone needs a platform
matrix that kills the process during sync/write, relaunches, and verifies the
last complete root on every supported target. If a platform cannot provide the
needed guarantee, add a native Konserve backend behind the same interface rather
than a second database protocol.

## Tiered stores and durable cloud storage

### What tiering actually guarantees

Konserve's `TieredStore` offers these relevant policies:

- `:write-through`: write the backend first, then the frontend;
- `:write-behind`: return after the frontend write and copy to the backend in an
  asynchronous task;
- `:write-around`: write only the backend and invalidate the frontend;
- `:frontend-only`: write/delete only the frontend while reads may fall through
  to a read-only backend.

See `reference-code/konserve/src/konserve/tiered.cljc:21-31,210-293,367-426`.
The special `:frontend-only` policy was added specifically for a local cache over
a shared writer-owned backend (`tiered.cljc:23-28,350-358`). Walk-based warming
can copy only keys reachable from chosen roots (`tiered.cljc:99-148`).

The durability consequences are important:

- `:write-through` can make a cloud backend authoritative, but every commit pays
  cloud latency before it returns.
- `:write-behind` is a cache/performance policy, not synchronous cloud
  durability. A server crash after the local return can lose the backend copy,
  and current failures are only logged by the asynchronous task.
- A local authoritative DB plus cloud backup is valid, but it has an explicit
  recovery-point objective. It must not be described as synchronously durable
  cloud state.

### S3 and GCS are external backends

Datahike configuration documentation lists S3 and GCS as external Konserve
libraries (`reference-code/datahike/doc/config.md:38-55` and
`storage-backends.md:130-171`). Seon's `deps.edn` contains neither package, and
`src/seon/server/store.clj:1-39` currently supports only memory/file in practice
(with an unfinished SQLite path). Cloud storage is therefore a new, explicit
deployment choice, not a capability already enabled in the current cluster.

The maintained [Konserve S3 backend](https://github.com/replikativ/konserve-s3)
registers `:s3`, supports S3-compatible APIs, and offers optional ETag
conditional-write retries. Without that option, concurrent read-modify-write
operations are last-write-wins. The maintained
[Konserve GCS backend](https://github.com/replikativ/konserve-gcs) registers
`:gcs` and offers analogous generation-match retries. These mechanisms protect
one Konserve key; they do not make a multi-object Datahike commit safe for
multiple Datahike writers. Keep one writer.

The Datahike storage-backend prose still calls S3 eventually consistent. That is
stale. [AWS documents](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Welcome.html)
strong read-after-write consistency and atomic updates for a single key.
[Google documents](https://docs.cloud.google.com/storage/docs/consistency)
strong global object read-after-write/list consistency and per-object atomicity,
while batch requests are not atomic. Datahike's node-first/head-last protocol is
still required because per-object consistency is not a multi-object transaction.

### Recommended cloud experiment

Do not choose the final cloud topology from API shape alone. Benchmark these two
honest options with representative transaction sizes:

1. **Direct authoritative object store**: JVM Datahike writer uses S3 or GCS,
   optionally with a local read cache. Measure commit latency, objects written,
   cold/warm reads, recovery, and cost. Datahike already exposes object-store
   write-amplification controls—diff buffering and root fusion—but commit-graph
   opt-out is not acceptable for Seon's time-travel/fork requirements
   (`reference-code/datahike/doc/config.md:278-287`).
2. **Local hot authoritative store plus durable copy**: JVM writes a local
   file/LMDB-class store and a separately specified synchronous or asynchronous
   cloud replication/backup process. State the RPO/RTO and failover authority.
   Do not use generic `:write-behind` while claiming zero acknowledged data loss.

Clients should normally receive replica nodes over the authenticated sync
channel rather than hold cloud bucket credentials or query object storage on
every cache miss.

## Kabel and `konserve-sync`: useful beta source with material gaps

### Packaging and maturity

The [Kabel project](https://github.com/replikativ/kabel) is a symmetric CLJ/CLJS
WebSocket transport for Clojure values with Transit/Fressian middleware. It is a
transport, not an offline-write conflict protocol.

Datahike pins Kabel `0.3.100`, `distributed-scope` `0.1.6`, and
`konserve-sync` `0.1.35`, but only in its `:test` alias. Its normal paths are
`["src" "target/classes" "resources"]`; `src-kabel` is added only by
`:test` (`reference-code/datahike/deps.edn:33,78-106`). A normal git dependency
on Datahike therefore does not make `datahike.kabel.*` available. The production
plan must first promote it into a supported artifact/module and pin its runtime
dependencies. Adding Seon copies of these namespaces would create the parallel
path this refactor is trying to eliminate.

### Correct storage synchronization behavior

The source contains several good mechanisms worth preserving:

- The Datahike walker discovers only nodes reachable from selected branches and
  returns an ordered vector with immutable nodes first and mutable `:branches`
  and branch-head cells last. It supports all branches, trunk only, or an
  explicit subset
  ([`konserve-sync` walker 0.1.35](https://github.com/replikativ/konserve-sync/blob/0.1.35/src/konserve_sync/walkers/datahike.cljc#L132-L217)).
- `konserve-sync` presence-deduplicates immutable nodes. Mutable roots are always
  resent during a handshake because comparing wall-clock `:last-write` values
  across machines cannot establish version equality
  ([`pubsub.cljc:49-147`](https://github.com/replikativ/konserve-sync/blob/0.1.35/src/konserve_sync/pubsub.cljc#L49-L147)).
- Ongoing ordered Konserve batches preserve the writer's node-first/pointer-last
  order rather than guessing from key shapes
  ([`pubsub.cljc:305-370`](https://github.com/replikativ/konserve-sync/blob/0.1.35/src/konserve_sync/pubsub.cljc#L305-L370)).
- Datahike's connector waits until the complete initial handshake has drained
  before exposing the received branch head. This specifically prevents a warm
  but partial cache from publishing a root whose children did not survive an
  interrupted sync
  (`reference-code/datahike/src-kabel/datahike/kabel/connector.cljc:199-267`).
- For a tiered store whose backend is shared read-only truth, the connector
  subscribes the local frontend directly so sync writes cannot mutate the shared
  backend and existence checks do not leave the frontend cold
  (`connector.cljc:159-180`).

Initial catch-up is differential at the **storage-key** level, not the datom
level. A warm client sends its local key inventory; immutable nodes already
present are skipped, the current reachable missing nodes are copied, and the
current branch head lands last. This naturally skips intermediate branch-head
values that no longer matter to the current snapshot.

### Missing listener and write-recovery behavior

Datahike's ongoing store callback calls `on-db-sync!`, which reconstructs the
new database value and resets the connection
(`reference-code/datahike/src-kabel/datahike/kabel/writer.cljc:195-240`). It only
has exact `tx-data` for the client's own pending transaction. It does not fire
normal listeners for a foreign synchronized root.

There is a separate `datahike.kabel.tx-broadcast` implementation that can publish
and subscribe transaction reports
(`reference-code/datahike/src-kabel/datahike/kabel/tx_broadcast.cljc:1-155`). The
server handler publishes/registers that topic, but the inspected connector never
subscribes it; its only foreign path is `on-db-sync!`. This is consistent with
Datahike's optimistic overlay explicitly documenting that foreign-peer writes do
not currently emit connection-advance reports
(`reference-code/datahike/src/datahike/optimistic.cljc:494-523`).

`KabelWriter` has a second correctness gap: after the remote transaction reply it
waits for the local store's `max-tx` to reach the result, with no timeout
(`reference-code/datahike/src-kabel/datahike/kabel/writer.cljc:83-132`). Shutdown
can cancel waiters, but a lost sync can otherwise leave the call pending
indefinitely. The request also carries no durable same-id receipt semantics in
the writer source.

Current Seon must not regress to that contract. Its wire adapter:

- freezes a write request under a durable wire id and retries the same id on lost
  replies (`src/seon/store/wire.cljs:280-353,419-513`);
- persists receipt/fingerprint facts on the writer and reconstructs the prior
  result on a same-id retry (`src/seon/server/wire.clj:193-239,704-838`);
- re-dereferences the shared store to the committed basis before completing the
  local write (`src/seon/store/wire.cljs:430-519`);
- opens the live feed first, pages replay under one fixed upper watermark,
  buffers concurrent frames, drains them, and then goes live
  (`src/seon/store/wire.cljs:764-840`);
- reconnects from the last applied basis and drops replay/live overlap by its
  monotonic watermark (`src/seon/store/wire.cljs:606-649,846-910`);
- reconstructs bounded, ordered replay pages including retractions and
  transaction metadata on the JVM (`src/seon/server/wire.clj:547-638`).

Generalize those semantics to the remote transport. Do not replace them with
bare distributed-scope invocation.

## Why streamed datoms are not yet the canonical replica protocol

Datahike has promising lower-level support. Its transaction loop accepts
`Datom` objects, preserves a supplied transaction id on additions, and preserves
the original transaction id on retractions
(`reference-code/datahike/src/datahike/db/transaction.cljc:713-745,1229-1233`).
This means an upstream exact committed-transaction applier is plausible.

It is not the same as proving ordinary transaction replay creates an identical
durable replica:

- `datahike.core/with` runs the normal transaction pipeline and generates/merges
  transaction metadata before processing the supplied entities
  (`reference-code/datahike/src/datahike/core.cljc:126-147` and
  `db/transaction.cljc:1125-1178`). A writer tx report already contains tx-entity
  datoms, so naïve replay can process metadata twice or create different temporal
  transitions.
- `transact!` is a writer operation and commits a new local branch head. A
  read-only replica must not quietly become a second independent writer.
- the `load-entities-with`/`transact-entities-directly` path is migration/import
  machinery with eid/tx migration state, not a documented replica-apply API
  (`core.cljc:149-156`, `db/transaction.cljc:1239-1285`).
- there is no public operation that checks expected prior coordinate, schema and
  index compatibility, exact transaction continuity, and resulting root/hash
  equality before atomically advancing a replica connection.

The current Seon adapter does not apply streamed datoms to rebuild its local
indexes. It receives datoms as a wake/report payload, then reads the newly
published immutable roots from the shared file store
(`src/seon/store/wire.cljs:532-649`). That distinction is load-bearing.

If a datom applier is still desirable later, implement it in Datahike rather than
Seon and require mechanical equivalence checks over:

- live and temporal EAVT/AEVT/AVET roots;
- max transaction/eid and transaction metadata;
- schema and attribute-reference mappings;
- commit id/parents and branch head;
- retractions, no-history attributes, tuples/components, uniqueness, transaction
  functions, and bitemporal valid-time metadata;
- restart persistence and interrupted-apply recovery.

Until that exists, index-node/head synchronization is the supported mechanism.

## Replica convergence and reactive wakes are different contracts

The storage channel answers: **which immutable database value can this reader
query safely?** The reactive channel answers: **which local work should rerun
because the readable value changed?** They should share a coordinate, but they
need not carry the same payload or cadence.

### Reconnect can converge to the current head once

For a pure UI/context projection `view = f(db-at-head)`, replaying 500
intermediate wake events after a disconnect is unnecessary work. The storage
handshake can:

1. record the reader's previous `{store-id, branch, commit-id/max-tx}`;
2. copy every missing immutable node reachable from the server's current head;
3. apply the current mutable head last;
4. verify the new coordinate and reconstruct the local DB;
5. emit one explicit `root-advanced` invalidation from the old coordinate to the
   new coordinate;
6. conservatively invalidate unknown query/render dependencies and derive once
   from the new DB.

This is exactly where current-head root synchronization is better than replaying
every branch-head mutation. It is also compatible with UI reconnect semantics:
the browser repaints from current server state rather than replaying UI patches.

Do not fake this as an ordinary one-transaction `d/listen!` report with empty
`tx-data`; that would lie about the contract. Give the replica owner an explicit
root-advance event whose changed-attribute set may be `:unknown`, and let the
projection layer choose conservative invalidation.

### Some consumers still require exactness

Head coalescing is not permission to discard semantics:

- a submitted write needs its exact durable receipt and result;
- forensic/debug tooling may request each transaction and its metadata;
- a consumer whose domain contract truly reacts once per transaction needs a
  durable cursor and replay;
- asynchronous agent work must be re-driven from durable incomplete facts/CAS
  fences at the resolved head, not from a transient in-memory wake count.

The general rule is: **coalesce notification, never state**. Durable facts and
the resolved database coordinate are authoritative. Pure projections rerun once;
effectful processors query what remains actionable and advance their own durable
fence; forensic tools can ask the bounded replay API for the exact range.

This also prevents the replica protocol from becoming a second application event
bus.

## Offline behavior

### Offline reads

Once the local IndexedDB store contains every node reachable from its last
published head, the client can query that immutable snapshot offline. The UI
should display its resolved coordinate/staleness state, but no server is needed
for local Datalog reads.

### Optimistic UI is not offline authority

`datahike.optimistic` overlays pending transaction data over an immutable local
DB with `d/with`, exposes an effective DB for immediate rendering, and removes or
reconciles entries when the durable connection advances. Entries have TTL,
conflict, cancellation, and process-local registration semantics
(`reference-code/datahike/src/datahike/optimistic.cljc:476-585`). The source calls
`transact-local!` overlay-only and explicitly says the durable write travels by
an application channel and must later echo through store sync
(`optimistic.cljc:556-579`).

That is a useful UI projection. It is not a durable offline queue and must not be
treated as one.

### Offline writes need one explicit policy

The conservative default is a durable client outbox of typed commands/facts with
stable request ids:

- persist the user's intended fact/command and request id locally;
- optionally render it through the optimistic overlay;
- on reconnect, submit it to the JVM writer under the same id until a durable
  receipt is returned;
- let the writer validate/CAS against current total-ordered state;
- mark/remove the outbox item only from the receipt, then let root sync reconcile
  the replica.

The outbox is not a local write to the authoritative replica branch. It is a
separate set of unsent intentions with explicit conflict/error states.

A writable local Datahike branch is a different product choice. Datahike can
create cheap branches and merge transactions, but it does not make concurrent
offline domain edits conflict-free. Choosing local branches requires merge
policy, identity collision handling, authorization, rejected-write UX, and
garbage collection. Replikativ/CRDT systems may inform that later; Kabel alone
does not supply it.

## History, indexes, and client resource limits

### History is expensive and not branch-scope removable

The `konserve-sync` Datahike walker includes temporal EAVT/AEVT/AVET roots when
present
([`walkers/datahike.cljc:120-129`](https://github.com/replikativ/konserve-sync/blob/0.1.35/src/konserve_sync/walkers/datahike.cljc#L120-L129)).
Scoping to trunk avoids copying sibling branches; it does not omit trunk history.

The server should keep full history and the commit graph because Seon requires
as-of/fork/restore. A potato-client policy remains open:

- sync full primary history locally and pay its storage/memory cost; or
- define a current-state replica/export with an exact origin coordinate and
  route `as-of`/history queries to the JVM until optional history is fetched.

The second option is present in the architecture prose but is not implemented by
the inspected Datahike/Kabel path. It needs a real format and tests before being
called supported.

### Secondary indexes belong on the JVM

Primary indexes are CLJC and reconstruct from synchronized roots. Secondary
index flush/restore and branch handling are reader-conditional JVM code;
`restore-secondary-indices` returns `{}` on CLJS
(`reference-code/datahike/src/datahike/writing.cljc:85-97,182-224` and
`versioning.cljc:66-119`). Datahike can build a secondary index in background and
atomically install it through the writer (`writing.cljc:706-772`).

This matches the intended resource split:

- JVM: embeddings, vector/full-text/other secondary indexes, heavy scans,
  multi-threaded preparation, branch/restore, and serialized install/write;
- CLJS client: primary-index Datalog reads and lightweight local projections;
- RPC: heavy or secondary-index queries whose results are ordinary data.

Datahike's secondary-index prose says Konserve-backed secondary indexes are
available to distributed readers, but the inspected source does not restore them
on CLJS. Treat source as authoritative for the current client design.

### Replica stores need compaction policy

Differential sync skips immutable nodes already present, but the handshake does
not reconcile by deleting client keys absent from the server. A long-lived
IndexedDB replica can therefore accumulate unreachable historical/orphan nodes.
Branch deletion and scoped sync also do not automatically prove local cleanup.

Datahike online GC is intentionally disabled for multi-branch stores because
branches structurally share nodes
(`reference-code/datahike/src/datahike/online_gc.cljc:137-200`). Remote replica
compaction must walk all locally retained roots/branches, respect pinned
snapshots, and run only against the client's own store. It must never delete from
the writer-owned backend through a tiered reader.

## Recommended deployment roles

### 1. JVM server: authoritative and heavy

Keep one server role that owns:

- serialized Datahike writes and total transaction order;
- durable same-id receipts and write-result recovery;
- branch, as-of, fork, restore, and garbage-collection operations;
- schema/config reconciliation at the writer;
- cloud/local Konserve backend lifecycle;
- embeddings and JVM secondary indexes;
- heavy queries/processing and bounded worker pools;
- replica bootstrap/current-head sync and exact replay endpoints.

Heavy work may use multiple threads to prepare results, but only the Datahike
writer commits state. Background secondary-index construction follows this
pattern already: build outside the critical writer step, then install through
the writer.

### 2. Co-located Node server agents/UI: current fast path

Keep the existing shared-file Distributed Index Space path for Node processes on
the same trusted server/filesystem:

- reads follow immutable roots locally;
- writes go to the JVM over the writer protocol;
- the transaction feed supplies low-latency wakes, own-write suppression,
  receipt correlation, and reconnect replay.

This is appropriate for always-on server agents a human can reach without their
personal computer running. The renderer and agent runtime remain CLJS/Node;
co-locate Node beside the JVM rather than maintaining a second JVM renderer.
Portable pure render/data functions can remain `.cljc`, but there should be one
web UI implementation.

### 3. Remote CLJS/Tauri client: potato replica

The remote client owns:

- a local memory + IndexedDB primary-index replica;
- local Datalog reads and lightweight derived surfaces;
- native/device data capture;
- stable-id write submission and a durable offline outbox;
- optimistic display only as an overlay;
- root-coordinate sync, staleness/error display, and one conservative
  invalidation after catch-up.

It does not own cloud credentials, branch authority, JVM secondary indexes, or a
second renderer protocol.

## Keep/archive implications

### Keep and make explicitly active

The following are not paused-JVM leftovers; they are the target server:

- `src/seon/server/wire.clj` transaction RPC, receipts, replay, query/pull, and
  listener broadcast mechanisms;
- `src/seon/server/registry.clj` branch-qualified connection/store lifecycle,
  after atom-backed routing authority is reduced to derived runtime handles;
- `src/seon/server/store.clj` as the one backend-config constructor, rewritten to
  support only proven backends and remove the unfinished SQLite fiction;
- `src/seon/server/codec.clj`, `transit.clj`, and the minimal transport/broadcast
  code required by the one wire protocol;
- JVM embedding/secondary-index code and Datahike/Konserve fork fixes;
- `src/seon/store/wire.cljs` semantics: read-only local connection, writer RPC,
  durable id correlation, RYOW, feed watermark, replay, and reconnect;
- shared `.cljc` schemas, ids, value contracts, and genuinely portable pure
  functions.

Names/files may consolidate during the refactor; the mechanisms and invariants
above are what must survive.

### Archive after a dependency proof

Archive the old embedded JVM **application** paths:

- duplicate JVM agent loop/evaluator/context/runtime implementations;
- duplicate JVM web UI/render/SSE/router implementations;
- the old Integrant application graph and core.async.flow request topology that
  exist to host that duplicate application;
- old embedded database facades that bypass the wire-server authority;
- JVM harness/gym/MCP/UI paths that no active server responsibility imports;
- tests/docs/runbooks whose only subject is those retired mechanisms.

Do this from an import/entry-point inventory, not by deleting every `.clj` file.
The phrase “paused JVM track” should disappear because it conflates two different
things. Use:

- **JVM server** — active and permanent;
- **legacy JVM application** — archived;
- **CLJS agent/UI runtime** — active and permanent.

### Consolidate rather than preserve two live channels

The current Seon tx feed and Datahike's beta Kabel store sync overlap but are not
identical:

- store sync makes a local database root readable;
- Seon receipts make remote writes unambiguous;
- tx replay supplies exact missed transaction reports;
- projection invalidation makes current views rerun cheaply.

Expose these as facets of one versioned remote protocol/connection lifecycle,
not four independently configured client systems. Kabel may be the WebSocket
transport and `konserve-sync` may be the storage-sync implementation, but neither
should create a second authority beside Seon's receipt/replay semantics.

## Mechanical proof plan

No context-wording tests are needed. These are storage/protocol behavioral tests.

### Phase 1: freeze the co-located invariant

- Cold-start the JVM writer and Node reader on a fresh file store.
- Prove local reads, foreign listener delivery, same-id lost-reply recovery,
  feed disconnect/replay, and restart attach.
- Record `{store-id, branch, commit-id/max-tx}` on both sides and compare query
  results at the same coordinate.

### Phase 2: make Datahike sync a supported module

- Move `src-kabel` into an intentional Datahike artifact/source path with its
  pinned dependencies and public lifecycle API; do not vendor-copy it into Seon.
- Add a foreign-root advance callback and integrate or replace the currently
  disconnected tx-broadcast path.
- Add finite timeout/cancellation and reconnect behavior.
- Carry Seon's durable id/fingerprint receipt protocol through the remote writer
  call.

### Phase 3: cold/warm IndexedDB replica

- Cold sync current trunk into IndexedDB; interrupt after arbitrary node counts;
  relaunch and prove no head is exposed before all referenced nodes exist.
- Warm reconnect and prove immutable-node dedup plus mutable-head refresh.
- Advance the writer by many transactions while disconnected, reconnect directly
  to current head, and prove one conservative projection invalidation produces
  the same final view as a fresh derive.
- Separately prove exact replay for a consumer that asks for every transaction.
- Restart the browser/Tauri process and query the last complete offline root.

### Phase 4: writes and offline intent

- Drop every possible request/reply/sync edge around a commit and prove repeated
  same-id submission commits at most once and returns the original result.
- Queue multiple offline commands, reconnect, crash during drain, and prove the
  durable outbox resumes without duplicate facts.
- Inject a CAS conflict and prove the outbox remains visible/actionable rather
  than silently disappearing.
- Prove `datahike.optimistic` state can vanish on process death without losing
  the durable outbox.

### Phase 5: history, branches, and compaction

- Decide full-history versus current-state client policy before measuring client
  size.
- Prove branch-scoped sync and behavior when switching to an unsynchronized
  branch.
- Prove local compaction retains every pinned/reachable root and never calls a
  delete against the writer backend.
- Keep server branch/as-of/restore equivalence tests on the JVM.

### Phase 6: cloud backend benchmark

For S3 and GCS independently:

- prove backend registration/configuration, create/connect/restart, and branch
  operations;
- kill the writer between node writes and head publication and prove readers see
  either the old or complete new root;
- measure p50/p95/p99 commit latency, object GET/PUT counts, bytes, request cost,
  cold/warm query latency, and recovery time;
- test conditional-write configuration but retain the one-writer invariant;
- compare direct object-store authority against the explicitly defined local-hot
  plus cloud-copy alternative.

### Phase 7: Tauri platform matrix

Run cold/warm/interrupted sync, quota pressure, process kill, device reboot,
offline query, and outbox recovery on macOS, Windows, iOS, and Android before
claiming those clients share one durability class.

## Open decisions for the owner

1. **Canonical remote replica mechanism:** approve storage-root sync as the
   first production path, with exact datom apply deferred to a Datahike upstream
   primitive? This audit recommends yes.
2. **Cloud authority:** should an acknowledged transaction wait for S3/GCS, or
   may a local hot store acknowledge before an explicitly stated cloud RPO? This
   cannot be hidden behind `:write-behind`.
3. **Client history:** should potato clients carry full temporal indexes, or
   default to current state and route history/as-of to the server? This audit
   recommends current state once an exact export/origin format exists; until
   then full root sync is the only implemented option.
4. **Offline writes:** approve durable command/fact outbox as the default and
   defer writable-branch/CRDT merge semantics? This audit recommends yes.
5. **Server rendering:** approve one co-located Node UI renderer beside the JVM
   rather than a second JVM web UI? This audit recommends yes.
6. **Reactive catch-up:** approve one explicit root-advance invalidation for pure
   projections, while exact tx replay remains opt-in for receipts/forensics and
   durable processors? This audit recommends yes.

## Primary source index

### Local source

- Datahike distribution model:
  `reference-code/datahike/doc/distributed.md:3-87,147-287`
- Datahike CLJS/IndexedDB/Kabel status:
  `reference-code/datahike/doc/cljs-support.md:1-99,133-139`
- Stored database shape and commit ordering:
  `reference-code/datahike/src/datahike/writing.cljc:48-180,226-287,378-515`
- Branch lifecycle:
  `reference-code/datahike/src/datahike/versioning.cljc:123-255`
- Raw-datom transaction behavior:
  `reference-code/datahike/src/datahike/db/transaction.cljc:713-745,1125-1237`
- Secondary-index CLJ/CLJS split:
  `reference-code/datahike/src/datahike/writing.cljc:85-97,182-224,706-772`
- Kabel dependency/source-path status:
  `reference-code/datahike/deps.edn:33,78-106`
- Kabel connector root gating:
  `reference-code/datahike/src-kabel/datahike/kabel/connector.cljc:138-323`
- Kabel writer wait/listener behavior:
  `reference-code/datahike/src-kabel/datahike/kabel/writer.cljc:83-158,178-255`
- Separate tx-broadcast implementation:
  `reference-code/datahike/src-kabel/datahike/kabel/tx_broadcast.cljc:1-155`
- Optimistic overlay boundary:
  `reference-code/datahike/src/datahike/optimistic.cljc:476-585`
- Konserve per-key guarantees:
  `reference-code/konserve/doc/backend.org:55-102,155-174`
- Konserve file atomic replacement:
  `reference-code/konserve/src/konserve/filestore.clj:138-223`
- Konserve IndexedDB:
  `reference-code/konserve/src/konserve/indexeddb.cljs:470-560`
- Konserve tiering:
  `reference-code/konserve/src/konserve/tiered.cljc:21-148,210-293,350-499`
- Current Seon reader/writer/feed contract:
  `src/seon/store/wire.cljs:1-39,130-153,280-649,764-910`
- Current Seon receipt and replay authority:
  `src/seon/server/wire.clj:193-239,547-638,704-838`

### External primary source

- [Kabel](https://github.com/replikativ/kabel)
- [`konserve-sync` 0.1.35](https://github.com/replikativ/konserve-sync/tree/0.1.35)
- [Konserve S3](https://github.com/replikativ/konserve-s3)
- [Konserve GCS](https://github.com/replikativ/konserve-gcs)
- [Amazon S3 consistency model](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Welcome.html)
- [Google Cloud Storage consistency](https://docs.cloud.google.com/storage/docs/consistency)
