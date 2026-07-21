---
type: research
status: complete
tags: [research, prd, database, flow, agent]
---

# Remote `seon.db` contract freeze — 2026-07-16

## Result

The final Bun-facing database seam is a small asynchronous `seon.db` API over
one persistent native session. Every read is resolved against one exact
coordinate, every successful value is eager ordinary data, and the JVM keeps
Datahike database values, entities, Datoms, indexes, functions, listeners,
threads, dereferenceable values, Futures, and query calls inside their owning
host.

Protocol version 6 already contains nearly all of the required wire contract:
query, pull, pull-many, schema, native index pages, execute-many, cancellation,
selective interests, coordinate-pinned KNN, attachment acquisition, and the
idempotent transaction receipt. The remaining work is chiefly to make
`src/seon/db.cljs` tell that truth and delete its local-Datahike surface. It is
not to add a remote connection, entity, temporal database, listener, or replay
emulation.

The final public success shapes remain convenient:

- `query`, `pull`, `pull-many`, `entity`, `installed-schema`, and `index-page`
  resolve to their ordinary successful values;
- `query-with-evidence` and `execute-many` expose the fuller namespaced result
  maps used by core acquisition, rendering, and diagnostics;
- `transact!` retains its compact `:seon.db/ok?` envelope; and
- agent evaluation awaits the host Promise and presents only its resolved data.

`entity` may remain as a convenience implemented by one `pull '[*]`; it is not
a protocol operation. `entity-lazy`, database-value constructors, local read
replay, raw transaction reports, and listener aliases have no place in the
remote contract.

## Dependency ledger

| Owner | Selected source | Contract fact |
|---|---|---|
| Seon wire data | `src/seon/db/protocol.cljc` at `0dbdb2e9` | Version 6 has one request ID, complete coordinates, closed read/member maps, ordered member results, selective-interest data, and pure constructors. |
| JVM interpreter | `src/seon/db/writer.clj` at `0dbdb2e9` | One exact attachment scope admits reads; one immutable value serves execute-many; results are recursively checked before delivery; physical connections own acquisitions and interests. |
| Bun session | `src/seon/db/transport/uds.cljs` at `0dbdb2e9` | `Bun.connect` multiplexes requests and addressed events, bounds request/event/output retention, auto-cancels timed-out requests, and keeps native socket values in closures. |
| Current application facade | `src/seon/db.cljs` at `0dbdb2e9` | Public reads are still synchronous over `*conn*`; it still exposes entities, temporal database values, captured read replay, raw reports, and local Datahike listeners. |
| Datahike | `reference-code/datahike` at `d9765276` | `q-with-evidence`, `pull-many`, `index-page`, schema, temporal wrappers, exact materialized-value release, query cancellation, and committed-report readiness are the native seams. |
| Generated identities | `src/seon/db/id.cljc`, `src/seon/db/id/schema.cljc` | The candidate manifest already has a precise ordinary-data shape; Bun builders are pure and the JVM validates policy, collision, commit, and recovery. |
| Consumer decisions | [[atomic-bun-authority-consumer-replacement-2026-07-16]], [[generated-id-authority-seam-2026-07-16]], [[async-render-authority-seam-2026-07-16]] | The replica disappears atomically; core views acquire then render purely; authored views await inside their child; candidate construction remains in Bun. |

## Common request laws

Every operation uses the existing `:seon.db.protocol/request-id`. There is no
member ID, session ID, waiter ID, or job ID on the wire.

- A client mints a request ID once and never reuses it during that session.
- A transaction keeps the same request ID and identical encoded bytes across
  ambiguous delivery. Its durable receipt makes that identity meaningful after
  process and transport completion.
- An execute-many member is identified by vector position only.
- A listen request ID remains live as the interest identity until unlisten or
  physical disconnect.
- A cancel or unlisten request has its own request ID and names the existing
  target request ID.

No unbounded completed-request registry is required. The authority retains
only active requests and live interests; Datahike facts retain transaction
receipts. Random UUID request IDs plus this ownership law replace any proposed
completed-ID or “tombstone” vocabulary.

An acquired data request carries database name, attachment, and complete
coordinate once. Execute-many members must not repeat them. Database name is a
route; attachment and coordinate are identity. The physical session, rather
than a repeated session field, is the security and cleanup owner.

The public facade may resolve the current head before an ad hoc operation, but
core and authored computations pass the coordinate they already own. The wire
must not silently interpret a missing coordinate as “whatever is current”: an
explicit coordinate makes retries, cancellation, caching, evidence, rendering,
and stale-completion rejection deterministic. If the extra resolve-head round
trip proves material for ungrouped agent reads, a measured head-capture request
variant is a later protocol decision rather than an implicit fallback.

## Operation inventory

### Session and control

| Wire operation | Final owner and result | Decision |
|---|---|---|
| `capabilities` | Bootstrap/session owner; bounded Datahike capability catalog plus protocol version and maximum frame bytes. | Retain. It contains data, never implementation functions or schemas. |
| `ensure-database` | Operator/bootstrap; database route, backend, and coordinate. | Retain outside the agent-facing toolkit. |
| `acquire-database` | Session owner; attachment, current coordinate, and acquired fact. | Retain. All data operations require this physical-session acquisition. |
| `resolve-head` | Session owner; current attachment and coordinate for a route. | Retain for explicit current-head acquisition. |
| `release-database`, branch create/delete, lifecycle observation | Operator/supervisor. | Retain as lifecycle operations, not ordinary agent reads. Physical disconnect remains the inverse for session acquisitions. |
| `ping` and health | Operator/session readiness. | Retain but keep out of `seon.db`'s agent-facing surface. |

One persistent session can acquire several databases because each operation
names database route and attachment once. In the selected topology, a cluster
host or isolated child normally owns a database-scoped session, so closing the
session is the simple acquisition inverse. Add an explicit per-session
acquisition release only if a measured long-lived multi-database owner needs to
drop one acquisition while retaining its sibling sessions; do not overload the
operator's attachment release to mean that.

### Reads

| Wire operation | Required input | Successful result | Public `seon.db` face |
|---|---|---|---|
| `query` | Attachment, coordinate, query form, arguments, optional history, native resource bounds. | Datahike result, attribute dependencies, cache evidence, and resource evidence. | `query` resolves the result; `query-with-evidence` resolves the complete ordinary evidence map. |
| `pull` | Attachment, coordinate, selector, entity ID/lookup ref, native resource bounds. | One ordinary map or nil. | `pull`; `entity` delegates to `pull '[*]`. |
| `pull-many` | Same selector plus an ordered vector of IDs/lookup refs. | Ordered vector corresponding exactly to the input positions. | Add `pull-many`; migrate N repeated pulls to it. |
| `schema` | Attachment and coordinate. | Installed Datahike schema map. | Keep the discoverable name `installed-schema`; make it asynchronous. |
| `index-page` | Attachment, coordinate, index, zero-to-four component prefix, direction, 1–200 limit, optional history and cursor, result-weight bound. | Eager Datom maps, completion fact, optional cursor. | Add one `index-page`; delete local `index-datoms` and `rseek-datoms`. |
| `execute-many` | Attachment, one coordinate, 1–64 independent read members. | One ordered result per member at the same coordinate. | Core coarse acquisition and an optional advanced agent-facing function. |
| `resolve-transaction-coordinate` | Acquired database, frozen head coordinate, and numeric transaction ID. | The original complete coordinate containing that transaction. | Retain as a bounded historical-navigation helper, not a database-value constructor. |
| `knn-search` | Attachment, coordinate, text query, bounded limit, optional numeric entity IDs. | Ordered ordinary hit maps. | Keep behind `seon.embed`/semantic search rather than teaching a second database read style. |

Datahike's lazy `entity`, `datoms`, `seek-datoms`, `rseek-datoms`, history,
since, and as-of values are implementation capabilities, not wire operations.
The authority applies the required wrapper and eagerly materializes the chosen
read.

### Existing application semantics to preserve

The current facade strengthens raw Datahike in three user-visible ways that the
first remote writer does not yet preserve:

- a missing lookup ref or numeric entity returns nil from `pull`/`entity`
  instead of throwing;
- a pull attribute registered in Seon's schema but not yet installed is
  omitted as an ordinary absent value, while a genuinely unknown attribute is
  a legible user-input error; and
- a concrete query attribute that is neither installed nor registered is a
  legible typo error instead of a silently empty result.

Datahike `pull` and `pull-many` currently call `entid-strict`; the latter parses
the selector once but fails the entire call on a missing lookup ref. Because we
own the dependency, add an exact non-throwing pull option or operation there so
one native pull-many preserves input order and nil positions without parsing N
times. Do not replace it with N Seon pulls.

The installed-versus-registered guard is Seon policy and belongs at the JVM
authority immediately before the native call, over the same exact database
value. Registered schema is already ordinary database data. Cache only its
exact-coordinate projection through Datahike's existing query cache; do not
add a Bun schema authority or a second registry. Unknown query dependencies are
already `:all` and remain executable because they cannot be safely classified
as a typo.

### Mutation

The existing `transact` input is the final mutation language:

- database route and universal request ID;
- ordinary Datahike transaction data;
- optional expected coordinate;
- minimal transaction metadata; and
- optional generated-candidate manifest.

It must remain the only write operation. CAS forms and generated dependent
identity claims are transaction data, not new authority commands.

The final reply needs request ID, coordinate, previous coordinate, temporary
IDs, added/retracted counts, optional generated entity IDs, and optional
recovered fact. It does **not** need to echo transaction data or transaction
metadata. The Bun caller already retains the frozen request until terminal
delivery, and the old echo exists to synthesize a local Datahike report for the
replica. Removing it eliminates potentially large result allocation, Transit
encoding, socket bytes, Bun decoding, and retained callback data on every
write.

Delete `:seon.db/return-report?` and `:seon.db/tx-report`. No production caller
requests the escape hatch, and a raw report necessarily contains `db-before`
and `db-after` host values. Transaction events likewise disappear with replay;
selective ordinary Datom events are the one reactive path.

## Exact temporal and index semantics

A coordinate identifies both a containing commit and its selected transaction
`t`. The authority must resolve the containing commit once, validate database,
branch, commit, and range, then apply the selected temporal cut internally.
Returning the resulting Datahike wrapper would violate the boundary.

The current writer's `pinned-database` accepts only a coordinate equal to the
resolved containing database value. It therefore handles historical retained
commits but not a strict `t` cut inside a containing commit. Before deleting
`at-coordinate`, strengthen this owner so an exact coordinate with an earlier
valid `t` produces the host-local as-of value used by the operation and is
released with the containing materialization. This is a real implementation
gap, not a reason for a public `as-of` database value.

History remains an option on query and index-page. Current consumers use
history only for Datalog provenance/retraction queries and database-browser
index pages. `seon.db/since` has no production caller and should be deleted.
`seon.db/as-of` has only explanatory references; exact-coordinate read inputs
replace it. `at-coordinate` callers become one coordinate-pinned acquisition
or execute-many request followed by pure computation.

An index cursor is Datahike's exact public five-field Datom cursor plus Seon's
coordinate, index, direction, and history facts. It deliberately does not copy
the prefix. Protocol validation rejects a different coordinate/index/direction/
history, while Datahike verifies cursor existence and membership in the native
resolved prefix. Datahike alone owns retraction transaction encoding, added
ordering, lookup-ref resolution, byte-array equality, and forward/reverse
comparison.

## Query evidence and capture

Every query response already returns
`:datahike.query/attribute-dependencies`, cache evidence, and resource evidence.
Do not add an evidence boolean or make Bun parse Datalog.

`query` unwraps only `:datahike.query/result` for ordinary callers. The session
operation-capture owner records request ID, coordinate, and the native
dependency/evidence maps before unwrapping. `query-with-evidence` exposes the
same complete map to core acquisition and diagnostics. An authored renderer's
async capture unions the returned dependencies; `:all` dominates concrete
sets. It does not retain query results as a replay cache.

Pull and index operations retain their existing native bounds but do not claim
query cache evidence. Do not invent Datahike-shaped evidence for operations
whose selected native APIs do not return it. Authority/session aggregate
metrics may still account their requests, bytes, failures, and latency.

Delete synchronous `capture-reads`, `read-observation-changed?`, normalized
read results, replayability facts, and replay execution. Keep the existing
AsyncLocalStorage operation-capture idea, but let it record only ordinary
remote request/evidence data across awaits.

## Execute-many contract

Execute-many is non-fail-fast after outer acquisition:

- the authority resolves and retains one exact Datahike value once;
- members are independent query, pull, pull-many, schema, or index-page data;
- all members use that identical host object and can run under the fair read
  executor;
- result vector position is the only member identity;
- an outer failure means attachment, coordinate, validation, or initial
  admission failed before member execution; and
- after acquisition, every position contains its successful operation result
  or its own closed error result. Completed siblings are preserved.

Cancellation stops queued members, detaches running queries, waits for
uncancelable running pull work before releasing the database value, and fills
unstarted positions with ordinary canceled errors. The cancel response is an
observation of whether cancellation detached or found running work; it is not
a rollback claim and does not replace the target's eventual terminal result.

The current member count bound is not an aggregate result bound. Sixty-four
members can each satisfy their own resource limit yet collectively exceed the
4 MiB canonical frame after all work has run. Add an outer cumulative result-
weight bound and require the authority to stop admitting remaining members
when it cannot produce one legal response. Per-member bounds still constrain
individual queries; the outer bound constrains composition, so their meanings
do not conflict. Final Transit frame validation remains the exact byte fence.

Do not add member dependencies or result bindings in the first contract. A
dependent read belongs in ordinary `^:async` ClojureScript; independent reads
belong in execute-many. This keeps one query language and makes parallelism
obvious.

## Cancellation and deadlines

Cancellation is scoped to the same physical session as the target. A client
cannot cancel a sibling child's work by guessing its request ID.

- Queued work can return `canceled? true`, `running? false`.
- A running or joined query detaches that caller; the final interested caller
  sets Datahike's cooperative signal.
- Pull and pull-many are currently not cooperatively interruptible, so the
  response must not claim they stopped before they actually return.
- A transaction accepted by Datahike is never described as rolled back.
  Ambiguous delivery is resolved only by its receipt and identical redelivery.
- A caller deadline rejects the local awaiting Promise, sends cancel, and
  retains the request ID/capacity until the late terminal response or session
  close. This prevents unsafe ID reuse and unowned late messages.

The current Bun session implements the final deadline behavior. Its Promise is
a process-local host owner. It never enters Transit, a database value, a
captured operation, an agent result, or durable data.

## Selective-interest contract

`listen` accepts either a query form or one-to-64 ORed Datom patterns. Query
dependencies are derived by Datahike; variables, rules, malformed forms, or
unknown clauses conservatively become `:all`. Datom patterns require an
attribute and may additionally constrain entity, value, and added fact.

The acknowledgement returns the source coordinate. A racing commit is in that
coordinate or a later event; no event precedes the acknowledgement. Events use
the listen request ID and contain only the committed coordinate plus matching
ordinary Datom maps. Overflow or a delivery gap emits one resynchronization
event at a known coordinate instead of replaying a feed. Unlisten removes the
target under the same connection ordering before acknowledging; no event may
follow that acknowledgement. Disconnect removes interests before acquisitions.

Callbacks are registered with the Bun session owner and never cross the wire.
The aliases `listen-sync!` and `listen-async!` have no production callers and
should be deleted. One asynchronous `listen!` plus `unlisten!` is sufficient.

The authority must reject a second live listen using the same request ID on the
same connection. The current installation replaces the connection-local map
entry without first removing the old reverse-index owner, which can retain an
undeliverable interest. This needs a focused regression before the public seam
freezes. It needs no completed-ID registry: only the already-retained live
interest map participates.

## Generated-candidate manifest

Generated allocation remains one transaction variant, not another operation.
The final manifest item is the existing ordinary shape:

```clojure
{:seon.db.id/key :domain/allocation-key
 :seon.db.id/identity-attr :domain/id
 :seon.db.id/value "candidate"
 :seon.db.id/dependent-lookup-refs
 [[:other.domain/id "already-frozen-value"]]}

```

Move or reference this portable closed shape from `seon.db.id.schema` so
`protocol.cljc` can use it without a cycle. Tighten both
`generated-candidates` and `generated-candidate`; the current protocol's
`[:vector :any]` and `:any` admit malformed manifests until the writer's later
validation. Candidate key and identity attribute are qualified keywords, value
is `:seon.db/id`, dependent lookup refs are an optional nonempty vector, and
manifest keys must be unique.

On exact collision, Bun regenerates, reruns the pure builder, and uses a new
request ID. On ambiguous transport, it resends the identical frozen bytes and
same request ID. Success returns candidate values locally paired with the
writer's generated entity-ID map. The local committed-entity scan disappears.

## Recursive ordinary-data boundary

The writer already provides the decisive result check in
`ordinary-data?`/`materialize-result`. It recursively rejects:

- Datahike DBs, connections, Datoms, and entities;
- functions and records;
- `IDeref`, `Thread`, `Future`, and `Throwable` values; and
- lazy or unrecognized sequential values.

It admits eager maps, vectors, sets, scalar Transit values, byte arrays, and
the persistent list syntax needed inside request forms. The retained writer
test explicitly rejects a Future, lazy sequence, and function-valued query
argument while preserving an ordinary result map.

That result check must become one shared protocol-level recursive wire-value
predicate with host-specific forbidden extensions. Apply it before Bun
encoding and before JVM response encoding, not only inside read execution.
Today `::arguments`, selectors, transaction data, generated candidates, and
results contain necessary third-party `:any` slots, while `uds.cljs` accepts
any map and invokes Transit without first calling `protocol/valid-request?`.
A JS Promise or function therefore fails late in the codec rather than as a
canonical protocol error. Malli describes outer structure; the recursive
predicate enforces the polymorphic ordinary-data leaf law.

The invariant to test recursively over every request, response, member result,
event, and public resolved result is:

> no DB, connection, entity, Datom, function, IDeref, Promise, Future, thread,
> socket, lazy sequence, query-call owner, or error object crosses the wire or
> survives into agent-visible data.

Promises necessarily exist as the Bun host implementation of asynchronous
functions. They remain inside `seon.db`, the session, web owner, or isolated
child and are awaited before Malli validates or agent eval records the resolved
value.

## Public Malli truth

All remote database functions that perform I/O become honestly `^:async`:

- `query`, `query-with-evidence`, `pull`, `pull-many`, `entity`,
  `installed-schema`, `index-page`, `execute-many`, `transact!`, `listen!`,
  `unlisten!`, coordinate resolution, and semantic search;
- public schemas describe their **resolved ordinary value**, following the
  existing `transact!` convention; and
- validation and error conversion happen after `await`, never against the
  Promise object.

Agent top-level eval already auto-awaits native Promises. Composed authored code
uses `^:async` and `await`. Core rendering performs one awaited outer
acquisition and then calls synchronous pure render functions. No compatibility
object pretends to be dereferenceable, a database value, or a synchronous
entity.

The current `seon.db` schemas are stale for this target: they accept explicit
connections and database values, teach synchronous returns, expose lazy
entities, contain raw report `:any`, and use broad open maps. Rewrite them in
the same atomic cut as the implementation. Do not temporarily claim a direct
result while returning a Promise under instrumentation.

## Surface to delete rather than emulate

Delete from Bun production reachability:

- `*conn*` as a Datahike connection and every explicit `::conn`/`::db` input;
- connection dereference, `attached?`'s connector inspection, and local
  connection release;
- `entity-lazy` and every caller that navigates Datahike entities;
- `history`, `as-of`, `since`, and `at-coordinate` database-value returns;
- local `index-datoms` and `rseek-datoms`;
- synchronous `capture-reads`, result normalization, replay, and change
  comparison;
- local Datahike `listen!`, rich handler reports, and listener aliases;
- `return-report?`, raw tx reports, replay-transactions, transaction events,
  publisher socket, and replica correlation; and
- all CLJS Datahike/Konserve requires made unreachable by those deletions.

Retain names only where they still name an honest application concept:
`query`, `pull`, `pull-many`, `entity` as eager pull convenience,
`installed-schema`, `transact!`, `head-coordinate`, `listen!`, and `unlisten!`.
Git, not compatibility functions, preserves the old API.

## Real gaps, not speculative features

1. **Strict temporal coordinate resolution.** `pinned-database` must apply the
   coordinate's selected `t`, not require it to equal the containing commit's
   maximum transaction.
2. **Aggregate execute-many output.** Add a cumulative result bound before a
   legal 64-member request can compute an unencodable response.
3. **Pull absence and schema guard.** Preserve nil for missing refs,
   pull-many position, registered-but-uninstalled omission, and unknown-attr
   errors at the authority/native pull seam.
4. **Generated manifest schema.** Replace protocol `:any` with the existing
   portable candidate shape and close transaction request/response maps.
5. **Live interest ID uniqueness.** Reject duplicate live listen IDs before
   reverse-index mutation.
6. **Recursive wire validation on both hosts.** Fail functions, Promises, host
   records, and lazy values canonically before Transit or delivery.
7. **Compact transaction response.** Remove transaction-data/meta echo and raw
   report reachability.
8. **Async facade truth.** Replace synchronous local-DB schemas and arities;
   add pull-many, query evidence, index-page, execute-many, and ordinary
   operation capture.

No additional remote entity, cache, temporal-value, listener, subscription,
broker, result-binding language, or transaction-template protocol is needed.

## Smallest implementation order

1. Close the protocol-only gaps: shared ordinary-data predicate, generated
   candidate schema, transaction response reduction, aggregate execute-many
   bound, duplicate live-interest rejection, and strict temporal coordinate
   resolution. Add the native non-throwing pull-many seam and authority schema
   guard in this dependency boundary. Preserve version 6 only if no released
   consumer exists; otherwise advance once for the complete schema break rather
   than piecemeal variants.
2. Build the session-owned async `seon.db` core: acquire/resolve, request ID,
   exact coordinate, error conversion, cancellation, query evidence, pull,
   pull-many, schema, index-page, and execute-many. Prove every resolved value
   recursively ordinary.
3. Migrate bounded utilities first: database browser, message windows,
   embedding/KNN, schema inspection, and entity callers. Replace repeated
   entity/pull work with explicit query or pull-many; delete entity-lazy and
   raw index traversal as their last callers move.
4. Migrate agent/runtime/eval/context computations to awaited coarse outer
   reads, keeping pure inner functions. Move generated allocation to the frozen
   transaction request and delete local policy/committed-EID reads.
5. Migrate core rendering to execute-many plus pure rendering, authored
   rendering to the owning child, and the web host to one selective interest.
   Replace read replay with native query dependency evidence.
6. Atomically delete replica, replay, publisher, local listeners, Datahike
   database values, Node adapters, and their tests/dependencies. Build only the
   Bun artifact and run the complete integrated proof.

## Consequential tradeoffs to keep visible

### Required coordinate versus implicit current head

Required coordinates make all expensive work deterministic and maximize exact
Datahike cache/single-flight reuse. They can cost an extra resolve-head request
for an ad hoc read that owns no recent coordinate. Core refresh, authored
render, transaction continuation, and interest-driven paths already own one,
so the extra trip should be rare. Measure real agent interaction before adding
an explicit head-capture variant; do not weaken all reads preemptively.

### Public direct values versus universal envelopes

Wrapping every successful query/pull in another `ok?` map makes failures
uniform but adds cognitive and allocation overhead to the dominant path and
breaks ordinary Datalog composition. Preserve direct resolved success values,
convert boundary failures to existing `:seon/error` data for agent evaluation,
and retain the explicit `transact!` envelope where commit truth must always be
checked. Core owners treat rejected/failed acquisition as their existing error
surface.

### Evidence always present on the wire

Query evidence adds a small map to every query response, but it removes Bun
Datalog parsing, enables selective routing and diagnostics, and exposes cache
and resource behavior needed for performance work. Keep it on the wire and let
the convenience `query` face unwrap only the result.

### One grouped response versus independent surface batches

Execute-many removes repeated coordinate resolution and shares one immutable
value, but one very large group can increase head-of-line latency and approach
the frame bound. Keep one semantic contract with an aggregate limit; split only
at an existing independently renderable surface boundary after measuring
returned weight and latency.

## Graduation evidence

- Protocol fixtures recursively reject every forbidden host value on request
  and response, including a JS Promise before encoding and a JVM Future before
  delivery.
- A strict historical `t` coordinate drives query, history query, and index
  page without returning a temporal DB value or leaking a restored secondary
  resource.
- Pull-many parses once, preserves input order/nil positions, and replaces
  representative N-plus-one entity callers; missing refs and uninstalled or
  unknown attributes retain the current public distinctions.
- Query result, dependencies, cache evidence, and resource evidence all name
  the same returned coordinate.
- Execute-many preserves vector order and per-member errors under inverted
  completion, cancellation, and mixed success; aggregate overflow becomes a
  bounded ordinary failure before an oversized frame.
- Duplicate live listen ID is rejected with unchanged reverse indexes;
  unlisten/disconnect leave zero interests.
- Generated candidate collision, stale policy, changed request fingerprint,
  and post-commit reply loss preserve the selected retry/receipt laws.
- Transaction replies contain no echoed transaction data, metadata, report,
  DB, Datom, entity, or dereferenceable value.
- Agent-facing async schemas validate resolved results; no Promise reaches
  Malli output validation, render serialization, eval history, or durable data.
- The Bun artifact has no Datahike/Konserve CLJS reachability and the JVM has no
  replica publisher/replay owner.
