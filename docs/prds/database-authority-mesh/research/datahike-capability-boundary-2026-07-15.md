---
type: research
status: completed
tags: [research, prd, database, capability]
---

# Datahike capability boundary audit — 2026-07-15

## Scope and decision status

This report designed the transport-free capability seam around exact Datahike
database values. The selected Datahike portion is implemented and graduated;
Seon sessions, scheduling, and transport remain in their ordered later units.

Selected dependency and first-party seam:

- Datahike `940810f5`, including graduated exact identity, single-flight, and
  capability work.
- `reference-code/datahike/src/datahike/api/specification.cljc` is Datahike's
  declarative semantic API authority.
- `reference-code/datahike/src/datahike/connector.cljc`, `connections.cljc`,
  `query.cljc`, `pull_api.cljc`, and `core.cljc` own connection, query, pull,
  cancellation, and listener behavior.
- `src/seon/db/protocol.cljc`, `writer.clj`, `replica.cljs`, and `db.cljs` own
  Seon's current protocol, authority policy, local replica, and application API.

The cache identity and single-flight prerequisites in
[[datahike-cache-identity-2026-07-15]] and
[[single-flight-proof-2026-07-15]] are graduated dependencies.

## Result in one paragraph

The optimal seam is not a second object API. Datahike already has a declarative
API specification that describes operations, schemas, remote suitability, and
referential transparency. Extend that authority with exact database-value
identity, cache evidence, scoped eviction, and cancellation primitives; execute
through ordinary functions. Seon's authority owns authenticated client
sessions, request IDs, transport, backpressure, encoding, and database policy.
Expose a pure-data capability description and pure-data requests/results, while
keeping connections, DB values, callbacks, cancel signals, and single-flight
owners inside their host. Add `execute-many` against one exact immutable value
so removing local replicas does not replace synchronous leaf reads with hundreds
of network hops. Immutable reads remain parallel across different request keys,
values, and databases; only identical requests single-flight, and writes order
only within their Datahike connection.

## Existing source laws

### Datahike already has a capability description

`datahike.api.specification/api-specification` is explicitly intended to derive
Clojure, Java, JavaScript/TypeScript, HTTP, and CLI bindings. Each operation
already declares argument/return schemas, categories, stability,
`:supports-remote?`, and referential transparency
(`api/specification.cljc:1-22,80-88`).

The existing entries already say:

- `connect`, `db`, and `release` are remote-capable lifecycle operations
  (`api/specification.cljc:169-212`).
- `q`, `query-stats`, `pull`, and `pull-many` are remote-capable and
  referentially transparent (`api/specification.cljc:295-386`).
- `history`, `since`, and `as-of` are remote-capable immutable view operations
  (`api/specification.cljc:512-550`).
- `transact` is remote-capable and blocking; `transact!` is the local async
  variant and is deliberately not remote (`api/specification.cljc:218-248`).
- `listen` and `unlisten` are callback-based local operations and deliberately
  not remote (`api/specification.cljc:749-779`).

This means a separate Seon query-service taxonomy would duplicate information
Datahike already owns. What is missing is a session-safe binding of these
semantics to exact immutable values and remotely meaningful listener/cancel
operations.

### Connection acquisition is already single-owner/reference-counted

`connection-id` is `[store-id branch]` for a self writer and adds the remote
writer backend when required (`store.cljc:41-61`). Connection acquisition
atomically returns owner, opening waiter, existing reference, config mismatch,
or releasing state (`connections.cljc:37-88`). Publishing an opened connection
sets its count to owner plus waiters (`connections.cljc:90-106`). Release
atomically identifies retained versus final ownership
(`connections.cljc:11-35`).

The final releaser drains the writer, closes secondary indexes, releases the
Konserve store, and removes the connection (`connector.cljc:438-510`). A Seon
session should therefore acquire and release this real reference rather than
maintain a parallel database lease count.

### Query cancellation is already transport-neutral

Datahike query input accepts `:cancel`, and query contexts carry an optional
volatile signal. Cancellation raises `:datahike/canceled` at checkpoints;
adapters translate it at their boundary (`query.cljc:78-83,93-115`). Query
execution composes that signal with work/result budgets
(`query.cljc:4006-4014`).

The external request ID must not become Datahike's cancel object. Seon should
map request ID to an internal Datahike signal and erase that mapping on terminal
completion.

### Listeners are keyed callbacks on a connection

Datahike `listen!` stores one callback by key and replaces the same key;
`unlisten!` removes it (`core.cljc:206-224`). Listener callbacks run during
writer publication, and synchronous writer work from a callback can deadlock;
the API specification warns callers accordingly
(`api/specification.cljc:753-779`).

Remote subscriptions therefore cannot expose callbacks. Seon should install a
Datahike callback owned by an internal listener key, promptly copy a bounded
ordinary-data event into a non-writer queue, and return. Transport delivery and
slow-client policy occur after that handoff.

### Seon currently depends on local synchronous reads

`seon.db/query`, `pull`, `entity`, `history`, `as-of`, and `since` dereference a
local connection and return synchronously. The `query` and `pull` implementations
also add Seon-specific schema guards, budgets, and read-observation capture
(`src/seon/db.cljs:891-1014,1256-1438`). `entity` touches lazy Datahike entities
before returning ordinary data (`src/seon/db.cljs:1440-1537`).

A current-tree search found 984 references to the broader read family and 931
references to `query`, `pull`, or `entity` across `src`, `test`, and `script`.
This is a rough syntactic count, not 931 distinct production RPCs. It does prove
that per-leaf remote adaptation would create broad async contagion and excessive
hops.

The current replica opens shared immutable storage locally and advances it from
the transaction feed (`src/seon/db/replica.cljs:1-10,159-197`). Removing that
replica deletes duplicated indexes and caches but also removes the premise that
all these reads are synchronous.

## Disposable in-process capability probe

The shortest probe used one ordinary `invoke` function over data-shaped
operation requests. It acquired a normal Datahike connection, installed a keyed
listener, transacted, resolved the exact current value internally, queried,
pulled, canceled a query, unlistened, and released. No transport or durable store
was involved.

The internal resolved value was represented during the probe by its connection
scope, commit ID, transaction, and opaque DB value. Only the first three would
cross a process boundary.

Raw result:

```clojure
{:scope [#uuid "d7e5865b-4c94-4cd4-a448-bc6574451f28" :db]
 :commit-id #uuid "6a58025e-cff0-5d42-beef-50537ba04083"
 :t 536870913
 :query 42
 :pull {:db/id 1, :probe/value 42}
 :events [2]
 :cancel #:datahike{:canceled true}}
```

The listener observed the transaction report, the query and pull used the same
captured immutable DB value, and a pre-set Datahike cancel signal produced the
documented cancellation value. This falsifies the need for transport semantics
inside Datahike. It does not prove remote scheduling, session cleanup, or
single-flight cancellation; those require retained tests.

## Proposed semantic layers

```text
agent-facing seon.db functions
  -> pure-data Seon database requests
  -> Seon authority policy/session
  -> ordinary Datahike capability functions
  -> Datahike connection, exact DB value, cache, writer, listener
```

### Datahike-owned layer

Datahike should own mechanisms that are true for every embedding or adapter:

- stable connection scope from its existing `connection-id`;
- exact process-local identity for committed, speculative, and supported view
  values;
- resolution of a durable commit/time/view selector to one immutable DB value;
- query, pull, pull-many, index and temporal execution on that exact value;
- completed-result caching, identical-key single-flight, scoped eviction, and
  cache metrics;
- creation/checking of cancellation signals;
- keyed local listener installation/removal;
- real connection acquisition/reference release; and
- declarative capability metadata derived from `api-specification`.

Candidate ordinary functions, names illustrative:

```clojure
(datahike.api/capabilities)
;; => {:datahike.capability/operations #{:datahike.operation/query ...}
;;     :datahike.capability/exact-value-identity? true
;;     :datahike.capability/single-flight? true
;;     :datahike.capability/cancellation? true}

(datahike.value/identity db)
;; => process-local immutable data; never a DB/index object

(datahike.value/resolve conn
  {:datahike.value/commit-id commit-id
   :datahike.value/view {:datahike.view/name :datahike.view/as-of
                         :datahike.view/time-point t}})
;; => opaque DB value inside the host

(datahike.query/cache-metrics {:datahike.cache/scope scope})
(datahike.query/evict-cache-scope! {:datahike.cache/scope scope
                                    :datahike.cache/generation generation})
```

These functions strengthen existing owners. They do not constitute a parallel
connection, query, or subscription API.

### Seon authority-owned layer

Seon owns policy and facts that are not generic Datahike behavior:

- authentication, authorization, cluster/database routing, and admission;
- session ID, session generation, request ID, deadlines, and client ownership;
- durable Seon coordinate and expected-coordinate write fencing;
- transaction idempotency receipts and Seon provenance;
- native socket framing, encoding, compression, queue bounds, and backpressure;
- request-ID to Datahike cancel-signal mapping;
- listener interest filtering and ordinary-data event encoding;
- fair per-database admission over shared read executors;
- response clipping appropriate to agents and the web UI; and
- translating every failure to the one `seon.db.protocol` data envelope.

The authority must not retain Datahike DB values after the owning exact-value
handle/session is released. It may retain a lightweight mapping from a durable
coordinate to Datahike's internal identity only while Datahike owns that value.

## Transport-free request sketch

The semantic request is data. An in-process adapter and a UDS adapter consume
the same shape; only the former can carry an already-resolved internal DB value.

```clojure
{:seon.db.protocol/operation :seon.db.protocol.operation/execute-many
 :seon.db.protocol/session-id "session-uuid"
 :seon.db.protocol/request-id "request-uuid"
 :seon.db.protocol/coordinate
 {:seon.db.coordinate/database-id #uuid "..."
  :seon.db.coordinate/branch :db
  :seon.db.coordinate/commit-id #uuid "..."
  :seon.db.coordinate/t 536870913}
 :seon.db.protocol/reads
 [{:seon.db.read/id :messages
   :seon.db.read/operation :seon.db.read.operation/query
   :seon.db/query '[:find ?m :where [?m :seon.message/to ?agent]]
   :seon.db/args ["agent-1"]
   :seon.db/max-work 100000}
  {:seon.db.read/id :agent
   :seon.db.read/operation :seon.db.read.operation/pull
   :seon.db/pull-pattern [:seon.agent/id :seon.agent/name]
   :seon.db/ref [:seon.agent/id "agent-1"]}]
 :seon.db.protocol/deadline-ms 2000}
```

Response order can match request order while each entry retains its ID:

```clojure
{:seon.db.protocol/success? true
 :seon.db.protocol/coordinate {...exact same coordinate...}
 :seon.db.protocol/results
 [{:seon.db.read/id :messages :seon.db.read/result #{...}}
  {:seon.db.read/id :agent :seon.db.read/result {...}}]
 :seon.db.protocol/cache-evidence
 {:datahike.cache/computed 1
  :datahike.cache/hits 1
  :datahike.cache/joined 0}}
```

No connection, DB value, Entity, callback, Future, Promise, volatile, or
single-flight owner appears in either shape.

## Why `execute-many` is part of the minimum seam

A network call for every existing `query`, `pull`, or `entity` would preserve
function names while destroying the performance model. `execute-many` captures
one exact DB value once, applies common deadline/budget policy once, amortizes
framing and encoding, and returns ordinary results together.

Concurrency law:

- every batch resolves its coordinate once before executing any member;
- read members are semantically independent and may execute concurrently;
- response ordering is deterministic by input order or explicit read ID;
- identical cache keys join one Datahike single-flight owner;
- different keys on the same immutable value run concurrently within bounds;
- reads on different values and databases run concurrently within bounds;
- no batch-wide lock surrounds query execution;
- writes never appear in the same unordered read batch; and
- writes are serialized only by their owning Datahike connection/writer.

The authority may choose sequential execution for tiny batches when measurement
shows executor overhead is greater, but this is a scheduling optimization, not
a global semantic gate.

`execute-many` should not become a miniature imperative program language. Each
member is an ordinary independent query, pull, index read, schema read, or
temporal projection. If one result must feed another, express the join in
Datalog/pull, issue a second request, or introduce one named, measured heavy
projection—not references between batch steps.

## Agent and application ergonomics

### Local CLJ embedding

Keep Datahike's existing synchronous ordinary functions over immutable DB
values. A CLJ renderer or authority-internal function can capture `db` once and
call `q`/`pull` directly. Returning a Future merely because a remote binding
exists would weaken the local API.

### Bun/CLJS remote children

Network reads are honestly asynchronous. A top-level agent form can continue to
feel direct because Seon's eval path auto-awaits a returned native Promise.
Inside an authored function, remote reads require `^:async` and `await`; bare
top-level `await` remains invalid. The system must not fake synchronous network
access or block Bun's event loop.

The transition should avoid mechanically converting hundreds of leaf calls:

1. Context assembly, rendering, and turn preparation issue coarse
   `execute-many` or named projection requests against one coordinate.
2. Agent-authored ad hoc `db/query` and `db/pull` return Promises in a remote
   child; top-level eval auto-await covers the common case.
3. Functions composing several reads use one explicit async batch helper rather
   than serial awaits.
4. Hot paths that depend on lazy `entity-lazy` traversal must become explicit
   pull/query projections; a remote lazy Entity is impossible and must not be
   emulated with hidden RPC.

Candidate Seon ergonomic shape:

```clojure
(db/read-many
 {::db/coordinate coordinate
  ::db/reads
  [{::db/id :agent ::db/operation ::db/pull-operation ...}
   {::db/id :messages ::db/operation ::db/query-operation ...}]})
;; local CLJ => ordinary map
;; remote CLJS => Promise resolving to the identical ordinary map
```

Using one name with platform-dependent return timing is risky if shared `.cljc`
code assumes a value. An alternative is explicit `read-many` for local and
`read-many!` for remote while sharing request/result schemas. Sean should choose
whether naming continuity or timing honesty is more important after call-site
classification.

### Replica option remains measurable, not assumed

One cluster-local UI replica could still win for an extremely read-heavy web
render path, but it retains another index/cache family and reintroduces replay
complexity. Compare it against batched authority reads. Agent children should
not each own replicas.

## Single-flight and cancellation contract

Single-flight is per exact `(scope, value, semantic request)` key. It never
serializes unrelated work.

Suggested states:

```text
absent -> owner computing -> completed cached
                    |-> failed and removed
                    |-> canceled and removed
```

Waiter rules:

- joining a flight does not transfer ownership of the computation;
- canceling one waiter detaches only that waiter;
- the owner continues while at least one live waiter remains;
- when no waiter remains, Datahike may cancel the owner if the operation is
  marked cancel-when-unobserved; otherwise it may finish for cache value;
- deadline expiration is waiter cancellation, not automatically global owner
  cancellation;
- owner failure reaches current waiters and never poisons the completed cache;
- in-flight state is never propagated to a child DB value;
- a reentrant same-key query on the owner thread must bypass joining or fail
  explicitly rather than deadlock; and
- release stops new admission, cancels or drains owned in-flight work according
  to declared policy, then evicts by exact scope generation.

For expensive shared agent queries, finishing an unobserved computation may
waste CPU; for near-complete cacheable work, canceling may waste prior CPU.
Expose this as an internal operation policy measured by query class, not as a
client-controlled arbitrary switch.

## Listener/session contract

Suggested remote flow:

1. `acquire` establishes a Seon session and acquires one real Datahike
   connection reference.
2. `listen` registers an interest under `(session-id, subscription-id)` and
   installs or shares the authority's one Datahike callback for that database.
3. The callback enqueues a compact transaction fact without blocking on a
   client.
4. The authority filters by database and declared interest, applies bounded
   per-session queues, and emits ordinary data.
5. `unlisten`, disconnect, or session release removes owned interest. Whether a
   shared Datahike callback remains is derived from remaining keys.
6. Final database release removes the callback before closing Datahike
   resources.

One Datahike listener per active database is the likely minimum callback count,
but it must be measured against per-session listeners. This is Seon multiplexing
policy; Datahike's keyed listener map already supplies the primitive.

## Interface-form comparison

### Capability data maps

Best for discovery, negotiation, conformance fixtures, remote encoding, and
future Bun/Rust authorities. They cannot execute behavior or safely carry
callbacks/handles. Derive them from Datahike's existing API specification.

Verdict: required as description and request/result shapes.

### Clojure protocol

A protocol is useful when the same in-process caller must dispatch efficiently
across multiple concrete authority host types. It would make `acquire`,
`execute`, and `release` explicit and mockable. Costs are a new object-oriented
surface, opaque session objects, duplicated operation schemas, and premature
commitment when only one Datahike authority exists.

Illustrative, not recommended yet:

```clojure
(defprotocol DatabaseAuthority
  (-capabilities [authority])
  (-acquire [authority request])
  (-execute [authority session request])
  (-release [authority session]))
```

Verdict: defer until a second in-process implementation needs type dispatch.
Protocol conformance across a socket is still data fixtures, not this Clojure
protocol.

### Ordinary functions

Ordinary functions match Datahike's present API, compose naturally in the REPL,
are easy to instrument/test, and keep opaque values in lexical scope. A single
`invoke` case may dispatch data operations at the adapter edge without turning
the whole library into a method table.

Verdict: strongest execution interface inside Datahike and the first JVM
authority.

### Recommended combination

- capability and operation descriptions: data maps derived from
  `api-specification`;
- Datahike execution: ordinary functions;
- Seon transport dispatch: one validated operation dispatcher over data;
- Clojure protocol: absent until two in-process host implementations justify it.

## Minimal Datahike changes versus Seon changes

### Minimal generic Datahike changes

1. Exact internal `value-identity` covering raw committed, speculative, and
   explicitly supported temporal values.
2. Correct cache identity using connection scope plus exact value identity.
3. Identical-key single-flight with waiter-aware cancellation.
4. Scoped/generation-fenced cache eviction and aggregate cache metrics.
5. Small public cancellation-signal helpers instead of requiring adapters to
   know the volatile representation.
6. Capability metadata for exact identity, cache evidence, cancellation,
   temporal-value support, and resource bounds in `api-specification`.
7. If measurement supports it, a generic `execute-many` for independent reads
   over one already-resolved DB value; otherwise Seon can parallelize ordinary
   Datahike functions without duplicating query semantics.

Datahike should not know Seon session IDs, users, agents, clusters, socket paths,
compression, request receipts, or protocol error envelopes.

### Minimal Seon changes

1. Extend the one `seon.db.protocol` with capability, acquire, resolve,
   execute-many, transact, listen, cancel, release, and cache-evidence data.
2. Replace one-writer-per-process assumptions with an authority registry of
   database sessions backed by real Datahike references.
3. Add bounded read scheduling without a global authority lock.
4. Map request cancellation to Datahike signals and listener keys to session
   ownership.
5. Build coarse read batches/projections for context, render, and turn hot paths.
6. Change remote `seon.db` reads to honest Promise results and preserve local
   synchronous Datahike use inside the JVM.

## Superseded Seon mechanisms to delete if the design wins

- Per-cluster CLJS Datahike replica creation and local immutable index ownership
  in `seon.db.replica`.
- Transaction-feed replay whose sole purpose is advancing those read replicas;
  retained event subscriptions use the direct authority listener capability.
- Shared-file lock avoidance and replica-specific Datahike configuration.
- Local `*conn*` assumptions inside Bun child read functions.
- Lazy remote `entity-lazy` behavior; replace hot paths with explicit pull/query
  projections.
- Any protocol-dispatcher query-result cache or single-flight layer; Datahike
  owns computation results.
- Per-operation socket wrapper functions that differ only in framing; one
  validated dispatcher handles semantic request data.
- Global transaction broadcasting to clients without matching database and
  interest ownership.

Do not delete durable transaction receipts, coordinates, expected-coordinate
fences, provenance, schema guards, budgets, error envelopes, or render/read
observation semantics. They remain Seon policy or must move deliberately into
coarse authority projections.

## Maximum-parallelism law

The authority is a resource owner, not a global execution gate:

- immutable `q`, pull, index, history, and temporal reads may execute
  simultaneously across callers, DB values, and physical databases;
- one bounded shared executor or work-stealing pool may serve reads, with fair
  per-database admission to prevent starvation;
- no connection-wide read lock is allowed around immutable values;
- single-flight coordinates only exact identical keys;
- cache atom updates cover lookup/publication metadata, never query execution;
- one database's slow query cannot prevent unrelated databases from starting;
- writes retain Datahike's ordered writer per connection/branch;
- reads may overlap writes because they target captured immutable values;
- resolving `head` happens once per request/batch, after which that value is
  frozen even if a write commits; and
- bounded queues, deadlines, cancellation, and result budgets prevent
  parallelism from becoming unbounded memory.

This is real JVM multicore execution. Bun children also execute concurrently as
separate OS processes; their database work joins only at the exact Datahike
resources it genuinely shares.

## Required falsifiers before a final PRD

- exact-value resolution for two stores, sibling branches, reconnect, commit,
  speculative, history, two as-of points, and two since points;
- 2/8/32 identical reads compute once while different keys start concurrently;
- reads across 1/2/4/8 databases show no global gate or starvation;
- same-database writes commit in order while reads of captured values continue;
- cancel one waiter, final waiter, owner, queued request, and completed hit;
- disconnect during query/listen/transact and reconnect during final release;
- execute-many versus individual calls at 1/8/32 members, including encoding,
  allocation, latency, executor overhead, and result size;
- batched authority reads versus one cluster-local replica for real context and
  render workloads, including JVM RSS and Bun RSS;
- slow listener consumer cannot delay writer publication;
- scope release removes cache/value/listener/session strong references without
  touching another database;
- agent top-level remote query auto-awaits, composed authored functions require
  honest async, and no Promise leaks into rendered/database values; and
- call-site classification identifies which of the rough 984 reads become one
  Datalog query, pull, execute-many request, named projection, retained local
  JVM call, or deliberately measured UI replica.

## Tradeoff brief for Sean

Decisions to make with Sean after the falsifiers:

1. Capability execution form: accept the recommended data-description plus
   ordinary-function combination, or require a Clojure protocol now for a
   second in-process authority implementation.
2. Read granularity: make `execute-many` foundational, or use only named coarse
   projections for core hot paths while leaving ad hoc operations individual.
   The strongest likely result supports both, with named projections reserved
   for measured heavy computations.
3. Remote API naming: same `db/query` name returning a Promise in Bun versus an
   explicit async name. Same data shape is required either way.
4. Replica exception: prohibit all Bun Datahike replicas initially, or retain
   one cluster-local UI replica only if end-to-end render measurements beat
   batched authority reads enough to justify its indexes and replay machinery.
5. Single-flight final-waiter policy: cancel when unobserved versus finish for
   cache, selected by measured query cost and remaining work.
6. Temporal value coverage: raw committed values first versus including
   history/as-of/since in the first exact-identity/cache unit.
7. Branch sharing: strict branch-scoped ownership first versus deliberate
   cross-branch content sharing with separate accounting.

The reversible boundary is pure request/result data plus durable coordinates.
Internal identity, scheduling, batching, and host implementation can change
without changing agent data. The costly mistakes would be exposing a
process-local Datahike handle, promising synchronous remote access, or making
one authority lock the route through which every query must pass.

## Implemented result

Datahike `940810f5` selected the data-description plus ordinary-function form.
The public catalog projects only existing API names/categories and concrete
facts about cancellation, caching, resource limits, host values, and lazy
results. It does not expose implementation symbols, schemas, routes, sessions,
or transport fields.

The evidence query follows the same normalization, planning, execution, cache,
and single-flight path as `q`; ordinary `q` still returns only its value and
pays no evidence-counter allocation. Evidence calls report whether the result
was a completed hit, one computed miss, or a joined request, plus invocation-
local charged work/results. Aggregate cache evidence omits cache keys and
database objects.

The existing Seon `request-id` is passed unchanged into Datahike. Internally it
indexes one request attached to shared query work. Canceling it wakes exactly
that request; only the final request sets the shared cooperative cancellation
signal. Active duplicates reject and terminal reads remove the ID immediately.
Seon's existing transaction receipt remains the only durable request record.

The prior callback proposal was improved at the dependency seam. Datahike now
offers committed reports at durable publication into one demand-opened,
generation-owned, fixed-capacity source. The offer performs no projection,
encoding, filtering, callbacks, threads, or network work. Overflow closes the
source with a replay gap rather than silently dropping. Exact final connection
release fences and abandons the source, while stale cleanup cannot affect a
reconnected generation.

The integrated retained proof passes 174 CLJ tests and 759 assertions across
PSS, HHT, and specs. The maintained Node CLJS suite passes 107 tests and 838
assertions. One fixture composes discovery, value identity, transaction,
committed report, query, pull, cancellation, cache/resource evidence, scoped
release, and connection release, then recursively proves its returned maps
contain no DB, connection, Datom, callback/function, IDeref, thread, Future,
Throwable, Promise, or socket value.
