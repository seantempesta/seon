---
type: research
status: active
tags:
  - database
  - datahike
  - datomic
  - protocol
  - performance
---

# Public `seon.db` facade compatibility matrix

## Question and result

What is the smallest public `seon.db` interface that preserves the useful
Datahike and Datomic Client semantics across the Bun-to-JVM boundary, while
keeping Seon values ordinary data and allowing database work to run in
parallel?

The compatible seam is a ClojureScript function facade over ordinary data.
Database values are immutable descriptor maps in Bun and are rehydrated to
native immutable Datahike values only for the lifetime of a JVM request. A
query may declare any number of sources in `:in`. For every top-level source
argument that is a database descriptor, the writer must resolve it, pass the
native value to Datahike in its original argument position, and release every
temporary materialization after the request reaches a terminal state. Ordinary
relation sources remain ordinary data.

This is a function interface, not a macro interface. Datahike already accepts
the query form and every input as runtime data, and `:in` source variables are
bound by `resolve-in`. A macro cannot eliminate that work, cannot see dynamic
database values, makes self-hosted ClojureScript harder, and provides no useful
transport optimization.

The current single-database wire request is not compatible with Datahike
queries. Maintained Datahike proves both different-database sources and
current-plus-`since` sources. The current Seon request instead carries one
database coordinate separately and prepends that database to otherwise
ordinary arguments. It therefore cannot express the maintained dependency's
query contract without a protocol change.

## Settled compatibility target

Sean settled the following boundary decisions during this research:

- Ordinary data is the compatibility target, not JVM host object identity or
  lazy host behavior.
- A public database value is an ordinary immutable descriptor map. The JVM
  rehydrates it to a native Datahike value only while serving an operation.
- Every database descriptor bound to a parsed `:in` source variable is
  resolved. An ordinary relation source is passed through, and nested
  descriptor-shaped ordinary data remains data. Queries may use several values
  from one database or values from different databases.
- `entity` returns an eager ordinary map or nil. It does not emulate the lazy
  `datahike.impl.entity/Entity` host object.
- Datoms cross the boundary as ordinary established-field data in native index
  order, not as `datahike.datom.Datom` objects.
- Transaction and listener reports are ordinary data that preserve the native
  report fields and semantic result shape.
- Compatibility preserves result meaning, collection shape, order, and nil
  placement. It does not preserve host types, laziness, deref behavior, or
  object identity.

These are intentional transport adaptations, not open tradeoffs.

## Dependency ledger

| Dependency or mechanism | Selected source | Evidence used |
|---|---|---|
| Maintained Datahike | `reference-code/datahike` at `670cd1ada40462cb5927f0dc687f6b3a95f9e13f` | `src/datahike/api/specification.cljc`, `api/impl.cljc`, `query.cljc`, `query/single_flight.cljc`, `core.cljc`, `pull_api.cljc`, `impl/entity.cljc`, `db.cljc`, local API tests, and `test/datahike/test/http/server_test.clj` remote tests |
| Current Seon facade | current parent of this report | `src/seon/db.cljs` |
| Current wire and writer | current parent of this report | `src/seon/db/protocol.cljc`, `src/seon/db/writer.clj`, and `src/seon/db/transport/uds.cljs` |
| Datomic Client | official current documentation, read 2026-07-16 | [sync API](https://docs.datomic.com/client-api/datomic.client.api.html), [async API](https://docs.datomic.com/client-api/datomic.client.api.async.html), [Client reference](https://docs.datomic.com/reference/client-reference.html), and [index APIs](https://docs.datomic.com/indexes/index-apis.html) |

Maintained Datahike's `query_planner_test.clj` lines 1037-1052 execute a query
whose `:in` is `[$a $b]` against two different immutable databases. Its
`api_test.cljc` lines 387-394 execute `:in [$ $since]` against a current value
and `(d/since current date)`. These are executable compatibility requirements,
not hypothetical future features.

## Three map categories that must stay distinct

The same operation currently has several maps with different ownership. They
must not be conflated:

1. A **Seon public argument map** uses fully namespaced `:seon.db/*` keys. It is
   accepted by agent-facing `seon.db` functions and exists to make optional
   arguments unambiguous.
2. A **dependency argument map** is passed only inside the JVM to Datahike. It
   uses Datahike's keys such as `:query`, `:args`, `:selector`, `:eid`,
   `:tx-data`, `:index`, and `:components`.
3. A **dependency result shape** is the semantic output shape Seon preserves as
   ordinary data. Transaction reports therefore retain `:db-before`,
   `:db-after`, `:tx-data`, `:tempids`, and `:tx-meta`; the two database fields
   contain Seon database descriptor maps.

Private protocol request and response maps are none of these. They may change
without becoming public vocabulary.

## Exact public database value

The companion decision [[datomic-client-database-value-seam-2026-07-16]] owns
the closed ordinary shape:

```clojure
{:db-name "default"
 :t 536870916
 :as-of nil
 :since nil
 :history false
 :datahike/commit-id #uuid "72482707-c5c3-52b5-b803-8fcd3d89df2f"}
```

`:db-name`, `:t`, `:as-of`, `:since`, and `:history` are Datomic Client's
observable vocabulary. Maintained Datahike's commit id is the one extension
needed to resolve retained commits and branches exactly. Connection id,
generation, attachment, native database id, and branch remain JVM ownership
state and never enter the public value.

A query source argument must satisfy this closed schema. Descriptor recognition
is nevertheless directed by the parsed `:in` binding, not by a recursive map
search. This distinction lets a scalar, tuple, collection, or relation input
legitimately contain a map with similar keys without having it rewritten.

## Recommended common laws

- Every authority-crossing operation returns a Promise in ClojureScript. The
  agent evaluator may await it, but the function itself remains explicit
  `^:async` behavior.
- Pure descriptor transformations `as-of`, `since`, and `history` are
  synchronous because they only return another ordinary map. Resolution and
  validation occur when an operation consumes that map.
- Omitting a database means “resolve the latest database once at operation
  entry.” A multi-step operation never re-resolves latest between members.
- Explicit database descriptors always win. Ambient insertion never replaces
  or reorders an explicit descriptor.
- Public map arities use namespaced keys. Positional arities preserve the
  familiar Datahike order where it is unambiguous.
- A success is the operation's semantic value directly. A failure is one
  ordinary `:seon/error` value with `:seon.error/message`,
  `:seon.error/kind`, and bounded `:seon.error/data`. Do not make callers learn
  one error envelope for reads and another for writes.
- No operation throws into the agent loop. Transport rejection, timeout,
  cancellation, stale database values, Datahike anomalies, and user input
  errors all normalize to that error value.
- Results are eager ordinary data before the JVM releases temporary database
  values. Potentially unbounded index reads require an explicit bound or page.

## Compatibility matrix

“Latest” below means one head descriptor captured once at call entry. “Native
shape” means Datahike or Datomic's semantic collection/map shape with every
host value converted to ordinary data.

| Operation | Public positional and map arities | Latest omission | Async and exact success | Failure and eager behavior | Required current-call-site impact |
|---|---|---|---|---|---|
| `db` | `(db)`; optionally map `{:seon.db/database-name name}` when several named databases are public | The operation is the latest lookup | Promise of one database descriptor map | Error value; eager | Existing ambient callers continue. Delete `head-coordinate`; the database map is the public value. |
| `query` | `(query query-form & inputs)` follows parsed `:in`; `{:seon.db/query form :seon.db/args [...]}` is the unambiguous map form. A source-bound top-level input may be a database descriptor or an ordinary relation source. | Insert latest only when the default `$` source is the first missing input. Determine omission from parsed binding/input counts, not map shape. Never add it when explicit sources already satisfy `:in`; every other declared source requires an explicit input. | Promise of the exact Datalog result: relation set, scalar, collection vector, tuple, or return-map collection | Error value; fully eager/certified before release | 176 forms in 61 files. Existing ambient calls mostly survive. Explicit/local-db calls become real again. Query plumbing must stop prepending one separately routed database. |
| `pull` | `(pull database selector eid)`, ambient `(pull selector eid)`, and `{:seon.db/db db :seon.db/selector selector :seon.db/eid eid}` | Ambient form captures latest once | Promise of map or nil | Error value; eager | 49 forms in 29 files. Remove stale “local replica” assumptions; retain argument order. |
| `pull-many` | `(pull-many database selector eids)`, ambient `(pull-many selector eids)`, and namespaced map | Ambient form captures latest once | Promise of an ordered vector containing one map or nil for every input eid | Error value; eager | Six forms in five files. Preserve duplicates, ordering, vector length, and nil positions. |
| `entity` | `(entity database eid)`, ambient `(entity eid)`, and namespaced map | Ambient form captures latest once | Promise of eager map or nil, defined as the established full entity projection | Error value; eager by settled decision | 86 forms in 24 files. Attribute lookup on a returned lazy Entity and reverse/component navigation are not supported; callers query or pull those relationships explicitly. |
| `transact!` | `(transact! tx-data)`, `(transact! {:seon.db/tx-data data :seon.db/tx-meta meta ...})`; no public connection argument because the writer owns it | Always the current writable head selected once before admission | Promise of `{:db-before descriptor :db-after descriptor :tx-data vector-of-ordinary-datoms :tempids map :tx-meta map-or-nil}` | Error value; no mixed `:seon.db/ok?` envelope | 174 forms in 60 files. Replace compact `tx`, counts, and added/retracted duplicate projections with the native-shaped report. Most callers that only check success simplify to `:seon/error` detection. |
| `listen!` | `(listen! key callback)`, `(listen! callback)` with generated key, and namespaced map for query/datom filters | Listener attaches to the named database's committed feed, defaulting to current cluster database | Promise of the registered key; callback receives the same ordinary transaction report shape as `transact!` | Registration failure is an error value. Callback failures are recorded and do not stop feed processing. | Five forms in five files. Same key replaces callback, matching Datahike. Datomic Client has no equivalent; this is Seon/Datahike functionality. |
| `unlisten!` | `(unlisten! key)` and namespaced map | Uses the listener's database/session owner | Promise of boolean removed? | Error value | Seven forms in five files. This intentionally improves Datahike's incidental return of the whole listeners map and must be documented as Seon semantics. |
| `installed-schema` | `(installed-schema)`, `(installed-schema database)`, and namespaced map | Omission captures latest once | Promise of Datahike's schema map as ordinary data | Error value; eager | 22 forms in 11 files. Datomic Client has no operation because schema is database data; keeping the established Seon convenience is useful. |
| `datoms` | `(datoms database index & components)` plus `{:seon.db/db db :seon.db/index index :seon.db/components components :seon.db/limit n}` | No positional ambient form if it would obscure the required bound; map may omit db | Promise of vector of ordinary datoms in native index order | Error value; eager and bounded | No current calls. Datahike's lazy sequence cannot cross the boundary. This should be a thin bounded use of the same index-page owner, not a second scan mechanism. |
| `seek-datoms` | Same structure as `datoms`, beginning at components | Same as `datoms` | Promise of bounded vector in forward index order | Error value; eager and bounded | No current calls. Implement through the one page primitive. |
| `rseek-datoms` | Same structure, reverse direction | Same as `datoms` | Promise of bounded vector in reverse index order | Error value; eager and bounded | Three forms in two files already expect reverse pagination. Preserve reverse order and cursor semantics. |
| `index-page` | `(index-page database options)` and namespaced map | Map may omit db and capture latest once | Promise of `{:datahike.index-page/datoms vector :datahike.index-page/complete? boolean :datahike.index-page/cursor optional-five-tuple}` with ordinary datoms | Error value; eager and bounded | Three forms in three files. This is the transport primitive and should remain the sole implementation owner for index scans. |
| `index-range` | `(index-range database attr start end)` and namespaced map with an explicit limit/page | Map may omit db | Promise of bounded ordinary datoms in AVET order | Error value; eager and bounded | No current calls. Datahike's end is exclusive. Do not expose an unbounded realized replacement for its lazy sequence. |
| `as-of` | `(as-of database point)` | No omission; compose from `(db)` explicitly when needed | Immediate database descriptor with `:as-of point` | Returns an error value for malformed descriptor/point if validation is local; no host DB | Four forms in three files. Support integer transaction id and instant, both of which Datahike accepts. |
| `since` | `(since database point)` | No omission | Immediate database descriptor with `:since point` | Same as `as-of` | Two forms in one file. Datahike semantics are datoms added strictly after the point. Current wire rejection is incompatible. |
| `history` | `(history database)` | No omission | Immediate descriptor with `:history true` | Pull/entity on history returns an error; query and index operations accept it | Five forms in four files. Current separate request boolean must disappear into the database descriptor. |
| `execute-many` | `{:seon.db/db optional-default :seon.db/operations [fully-namespaced-operation-maps]}` | Capture one latest value for the whole call; members may contain explicit database descriptors | Promise of an ordered vector containing each direct operation success value or its error value | Overall protocol/admission failure is one error; member failures stay in their positions; eager | 44 forms in 25 files. Current member-specific response envelopes should collapse to the same values as direct calls. Maximum member count remains a resource policy, not API meaning. |
| cancellation | Recommended `(cancel! request-id)` plus `:seon.db/request-id` on query map calls | Not applicable | Promise of ordinary cancellation result including found, detached, last-caller, unstarted-owner, and cooperative-signal facts | Cancellation of the original call resolves that call to a cancellation error value | No current public calls. Timeout-triggered private cancellation already exists, but callers cannot intentionally cancel without a public request identity. |

Datahike names its query function `q`; Seon already names it `query`. The
recommendation is one public `query` function, not `q` plus an alias. The name
is a facade choice; the input and result semantics above are the compatibility
contract.

## Multi-database query contract

### What Datahike actually does

`normalize-q-input` turns either `(q form & inputs)` or an argument map into
`{:query normalized-map :args inputs}`. `resolve-ins` zips parsed `:in`
bindings with those values. `resolve-in` identifies each `SrcVar` and places
its corresponding value in `Context.sources` under `$`, `$a`, `$since`, or any
other declared source symbol. Source-prefixed clauses later read the matching
value from that map. Database recognition occurs through Datahike's database
predicates while resolving and planning each clause.

Consequences:

- The database is not a special out-of-band request property in Datahike. It
  is an input value whose position is defined by `:in`.
- There may be zero, one, or several database inputs.
- Database inputs can be interleaved with scalars, tuples, collections, rules,
  and relation inputs.
- Different source symbols may refer to different temporal views of one
  commit, different commits of one database, or different databases.
- A macro is neither required nor helpful. The normalized query and source
  bindings are runtime values by design.

### Exact wire rehydration

For one query request, the JVM owner should perform this sequence:

1. Parse and validate the query and its ordinary `:seon.db/args` without
   changing their order or collection shapes.
2. Parse `:in` and align it with top-level runtime arguments exactly as
   `resolve-ins` does. If the default `$` is the sole first missing binding,
   insert the captured latest descriptor there. This count-and-position rule
   preserves an explicit ordinary relation source without guessing from shape.
   Only arguments whose binding is a `SrcVar` are source candidates. Rehydrate
   a candidate that satisfies the strict complete database-map schema; pass an
   ordinary relation source unchanged. Never inspect nested maps or collection
   members for database identity.
3. Record each source symbol, top-level argument position, descriptor, and
   canonical identity. Deduplicate identical
   descriptors, then sort the unique acquisitions by stable database identity,
   commit, transaction cut, and temporal flags. Stable ordering prevents two
   multi-database requests from acquiring resources in opposite orders.
4. Acquire each named database generation and verify attachment/database
   identity. Resolve its commit once. A head commit reuses the attached native
   value; an older commit uses `commit-as-db` once per unique commit.
5. Derive `as-of`, `since`, or `history` wrappers from that containing value.
   Current and `since` inputs for the same commit share the containing value;
   only the wrappers differ. Integer and instant points retain Datahike's
   native semantics.
6. Replace only those source-bound top-level arguments at their exact positions
   with the resolved native values. Pass the query form and rebuilt arguments
   directly to `acquire-q!`. Do not prepend another database. Descriptor-shaped
   data under scalar, tuple, collection, or relation bindings remains unchanged.
7. Keep every acquired attachment and temporary containing value alive through
   cache lookup, queued wait, computation, result materialization, delivery,
   cancellation, or disconnect.
8. In one terminal `finally` owner, release each temporary containing value
   exactly once and release every database acquisition. Temporal wrappers do
   not receive separate physical release calls.

Failure to resolve any source descriptor fails the whole query before computation.
There is no meaningful partial multi-source query.

### Admission, fairness, and parallel reads

Datahike serializes transactions through its connection writer. It does not
serialize immutable reads. The JVM query executor can therefore run many
queries concurrently, including many queries over the same snapshot and
queries spanning several databases.

The writer executor should remain the sole admission owner, but it must not be
a single execution gate. One request is one executor job carrying the set of
database generations it uses. Admission should atomically charge the job to
every distinct database in that set and to the global bounded worker pool.
Per-database FIFO queues plus a global ready queue provide fairness; acquiring
several quotas piecemeal would introduce deadlock. No database lock or registry
lock remains held while Datahike computes. Cancellation removes a queued job
or detaches the caller from an active shared computation and always runs the
same release owner.

This gives parallel flow without launching another JVM and without allowing a
busy cross-database workload to evade per-database limits by selecting a quiet
database as its first argument.

## Cache and single-flight correction

The maintained cache is correct for one database input but not yet for several.
`raw-q-mode` currently chooses `(first args)` as `db`, keys the completed cache
bucket by that one database's committed identity, and puts `(vec (rest args))`
in the result key. Therefore a second native database value is treated as an
ordinary non-database argument. That has four consequences:

- the key retains the foreign host database value;
- a generation close for the second database cannot find and evict the entry;
- transaction propagation and attribute invalidation only understand the first
  database; and
- equivalent multi-source calls cannot be reasoned about by a plain-data key
  spanning all database inputs.

The single-flight key inherits the same problem because it starts with the
one-database cache key. `close-scope!` also pattern-matches only that first
scope. The open issue
[[multi-source-query-cache-retains-foreign-database-values]] records this
dependency defect.

The required maintained-Datahike first implementation is:

- During normalized `:in` binding, collect every source symbol, top-level
  argument position, and exact committed database identity.
- Build a composite source identity ordered by the query's source bindings,
  not by map iteration: `[[source-symbol argument-position database-identity]
  ...]`.
- Replace database values in the remaining-argument cache key with those plain
  identities. No host database value may be retained by a cache or metric.
- Completed-cache and single-flight identity is the normalized query, composite
  source identity, non-database inputs, pagination/order/resource options, and
  planner mode.
- Retain the existing conservative union of query attribute dependencies. It
  may invalidate more than necessary when one source changes, but it cannot
  preserve a stale result.
- A cache entry has a reverse membership edge from every contributing
  connection generation. Closing any generation atomically fences new puts,
  evicts completed entries containing it, detaches all callers from flights
  containing it, and wakes them with the existing scope-closed failure.
- Transaction propagation may derive a new composite key by replacing one
  source identity with its child commit only when that source's modified
  attributes do not intersect the conservative global dependency union. Old
  immutable-snapshot entries remain valid until their weighted LRU or
  generation lifetime evicts them. If dependency analysis is uncertain, the
  union is `:all` and propagation does not occur.
- `as-of` integer views may use Datahike's existing origin-plus-cut identity.
  `since`, `history`, instant-based cuts, speculative values, and any source
  without an exact committed identity remain uncacheable until they gain a
  proven exact identity. An ordinary relation source likewise makes the query
  uncacheable until a bounded plain-data identity is deliberately supported.
  Any uncacheable source makes the whole result uncacheable; correctness wins
  over a partial key.

This patch computes one result for all simultaneous callers of the exact same
multi-source query and immutable inputs. It also makes “evict when no database
generation is live” precise without reference counting Bun objects.

Source-specific dependency sets are a later propagation optimization, not a
correctness prerequisite. They can let a transaction in `$a` invalidate only
attributes used from `$a` rather than attributes used anywhere in the query.
The composite key, global union, and all-member-generation release are already
one correct cache mechanism. Implementing that mechanism immediately is
simpler than introducing an uncached multi-source branch and replacing it
later. Multi-source becomes uncacheable only when one of its native sources
lacks an exact cache identity.

## Operation-specific semantic notes

### Query result shape

Datahike returns different shapes according to `:find`: a relation is a set of
tuples, scalar find ends in `.`, collection find in `...` is a vector, tuple
find is one vector or nil, and `:keys`/`:strs`/`:syms` returns maps. The facade
must not force all query results into vectors or wrap them in a response map.
`query-with-evidence` is a separate diagnostic function whose namespaced
Datahike evidence map may be preserved as ordinary data.

### Entity

Datahike Entity is a lazy map-like JVM object with database attachment,
on-demand attribute lookup, reverse references, and component touching.
Datomic Client deliberately has no entity API. Seon's eager `entity` is thus a
convenience projection, not an attempted remote host proxy. The implementation
should be the one established full pull projection and should document that
relationship navigation requires explicit pull/query data.

### Indexes

Datahike `datoms`, `seek-datoms`, `rseek-datoms`, and `index-range` return lazy
sequences. Datomic sync Client returns iterable results and async Client emits
chunks. Neither behavior can cross the current request/response transport
without retaining JVM resources. Maintained Datahike already supplies the
right primitive: `index-page` eagerly returns namespaced datoms, completion,
and optional cursor fields. All public index conveniences should compile to
that one bounded operation.

Ordinary datoms need one established representation everywhere. The current
writer already uses `[e a v tx added?]`; if the final public representation
instead uses maps, that choice must be made once for transaction reports,
listeners, index reads, and debugging. Mixing vectors in reports and maps in
indexes would be needless incompatibility.

### Temporal values

`as-of` includes facts through the point; `since` contains additions strictly
after it; `history` contains assertions and retractions. Datahike accepts
integer transaction points and instants. Datomic documents that history values
work for query and index APIs but not pull. Seon should enforce the same
meaning before dispatch and remove the current separate `history?` request
field.

### Transactions and listeners

The report shape is one contract. `transact!` success and listener callback
both carry `:db-before`, `:db-after`, `:tx-data`, `:tempids`, and optional
`:tx-meta`. Database values and datoms are converted to ordinary data. Compact
counts are derivable and should not compete with the report as stored truth.

Datomic Client has no listener operation. Seon's committed feed must therefore
define delivery, replay, replacement-by-key, disconnect cleanup, and callback
failure independently while retaining the Datahike transaction-report shape.

### Errors and cancellation

Native Datahike synchronous calls throw and `transact!` returns a future whose
failure throws on deref. Datomic sync Client throws anomaly `ex-info`; Datomic
async Client places anomaly maps on channels. Seon's errors-as-values rule is
an intentional facade difference and should be uniform across all operations.

The current UDS client automatically sends a private cancel request when a
timeout fires. The writer maps it to Datahike's request-identity cancellation,
which can detach one caller, signal a last caller's running computation, or
remove an unstarted owner. A public `cancel!` requires the original query to
accept or return a stable public request id. Cancellation is caller-specific:
canceling one waiter must not terminate computation still needed by another.

## Current call-site inventory

Measured with fixed-string call searches over `src/` and `test/` on 2026-07-16:

| Function | Forms | Files |
|---|---:|---:|
| `query` | 176 | 61 |
| `pull` | 49 | 29 |
| `pull-many` | 6 | 5 |
| `entity` | 86 | 24 |
| `transact!` | 174 | 60 |
| `listen!` | 5 | 5 |
| `unlisten!` | 7 | 5 |
| `installed-schema` | 22 | 11 |
| `rseek-datoms` | 3 | 2 |
| `index-page` | 3 | 3 |
| `as-of` | 4 | 3 |
| `since` | 2 | 1 |
| `history` | 5 | 4 |
| `execute-many` | 44 | 25 |

There were no direct `db`, `datoms`, `seek-datoms`, or `index-range` call forms.
Counts include tests and may overlap helper definitions; they measure migration
surface, not unique semantic patterns.

## Executable compatibility proof inventory

Compatibility graduates by porting maintained Datahike behavior into Seon
remote-contract tests. Passing Datahike's own local-host suite is necessary for
the dependency but does not prove descriptor rehydration, wire materialization,
Promise behavior, cancellation cleanup, or the public facade.

### Exact proposed test paths

- `test/seon/db/remote_contract_test.clj` owns true JVM writer plus UDS tests.
  It starts isolated in-memory named databases through the existing writer test
  fixture, sends ordinary-data protocol requests, and asserts the actual
  Datahike results after transport conversion.
- `test/seon/db_remote_contract_test.cljs` owns public `seon.db` Promise,
  arity, namespaced-map, latest-omission, and error normalization tests. It uses
  a deterministic fake authority only to inspect public request formation and
  response conversion; it does not duplicate database semantics.
- `test/seon/db/remote_contract_fixtures.cljc` is optional shared pure data:
  namespaced schema transactions, query forms, selectors, entity ids, index
  options, and expected ordinary values. Create it only when both test
  namespaces consume the same values. It must not require a Datahike test
  namespace or expose Datahike fixture helpers.

The smallest true-remote selector is:

```bash
bin/test-writer seon.db.remote-contract-test
```

The smallest public-facade selector is one exact test var, for example:

```bash
bin/test-cljs --test=seon.db-remote-contract-test/query-rehydrates-every-declared-source
```

The final focused compatibility gate runs those two namespaces. It does not add
another test runner.

### Source-to-target matrix

| Maintained source test | Reusable behavior and data | Remote fixture adaptation | Async and unsupported-host adaptation | Proposed target test |
|---|---|---|---|---|
| `datahike.test.http.server-test/test-server` through `run-server-tests` | This is the highest-value upstream remote baseline: remote transact report, immutable `db`, `q`, pull, datoms, seek, schema, entity, since/as-of, and release are exercised after serialization. Reuse its operation sequence and ordinary expected values | Run over Seon's one multiplexed UDS session and named database fixture, not three HTTP servers or request-per-call connection records. Seon's maintained wire format is the test subject; EDN/JSON/Swagger/auth are Datahike HTTP concerns | Replace `RemoteSinceDB`, `RemoteAsOfDB`, remote connection, and `RemoteEntity` records with ordinary database descriptors, internal session ownership, and eager entity maps. Reject `entity-db` host identity and HTTP lifecycle assertions | `upstream-remote-operation-sequence-remains-compatible` in `seon.db.remote-contract-test` |
| `datahike.test.http.server-test/test-json-interface` | Its raw serialized q proves that a named source and relation result survive a remote representation | Reuse the query and expected tuple set through canonical Seon transport encoding | Do not copy JSON tagged-set text, raw HTTP request construction, or JSON parsing expectations | Covered by `query-result-shapes-and-map-arity`; no second JSON mechanism |
| `datahike.test.api-test/test-q-docs` | Query vector/map forms, ordinary relation source, relation/scalar/collection/tuple results, and pagination expectations can be copied | Seed database cases through remote `transact!`; relation-source cases cross directly as ordinary data; mechanically namespace domain attributes | Await every Seon call. Relation-source calls are uncacheable until they have a deliberate bounded identity | `query-result-shapes-and-map-arity` in both target namespaces |
| `datahike.test.query-planner-test/test-multi-source-queries` | `:in $1 $2`, source-prefixed joins, predicates, `not`, `or`, disjoint sources, lookup-ref inputs, and expected tuple sets | Create two named Seon databases with the same namespaced schema and distinct facts; use returned descriptor maps in the original argument positions | Remove planner dynamic-var comparison, which is dependency-internal. Await one remote query and assert the same semantic set | `query-joins-two-named-databases` and `query-preserves-interleaved-source-inputs` |
| Datahike `resolve-in` source-versus-value behavior in `query.cljc` | Only top-level values paired with parsed `SrcVar` bindings enter `Context.sources`; other inputs remain values | Pass a strict descriptor as `$a` and place an equal descriptor-shaped map inside a collection or relation binding | Assert the source is rehydrated and the nested map reaches the query unchanged | `query-rehydrates-only-declared-source-bindings` |
| `datahike.test.query-planner-test/test-variable-attribute-multisource-function-value` | Query structure and exact `#{["Doc1" "Peter"] ["Doc2" "Anna"]}` expectation | Namespace attributes; transact docs and people into different named databases | Do not expose planner toggle. This is the highest-value cross-source correctness probe | `query-correlates-variable-attribute-across-databases` |
| `datahike.test.query-planner-test/test-variable-attribute-multisource-avet-seek` | Same cross-source result under a large indexed target | Use a smaller but threshold-proving fixture only if maintained planner threshold is stable; otherwise keep this in Datahike's suite | This is dependency performance-path proof, not required in the remote contract unless the threshold is intentionally public | Optional `query-cross-source-index-path-remains-exact` JVM-only test |
| No maintained three-source facade test | Extend the same source-binding law to `:in $a $b $c` and assert one joined result | Create three named databases and pass three descriptor maps | Required Seon-owned extension because “2+” must not accidentally mean exactly two | `query-joins-three-named-databases` |
| `datahike.test.api-test/test-since-docs` | Current-plus-`since` query form and expected `#{["Alice" 30]}` | Replace sleeps/date capture with transaction ids from descriptor maps for deterministic proof; add a separate instant case if instant support is claimed | Await transaction/query; descriptor transformation remains synchronous | `query-combines-current-and-since-descriptors` |
| `datahike.test.api-test/test-as-of-docs` and `test-history-docs` | Before/after result sets and history inclusion semantics | Capture `:t` from transaction report database descriptors; use namespaced attributes | Host DB classes are irrelevant. Assert descriptor fields before remote consumption and query results after rehydration | `query-rehydrates-as-of-and-history-descriptors` |
| `datahike.test.query-planner-test/test-temporal-queries` | Query semantics over history, as-of, and since, including retractions and predicates | Port the minimal clauses that cover each wrapper; setup via ordered remote transactions | Do not duplicate planner-vs-legacy assertions | `temporal-query-semantics-survive-transport` |
| `datahike.test.api-test/test-pull-docs` | Selector forms and exact nested eager map | Seed namespaced cardinality-many/ref schema remotely | Await map result; no host adaptation beyond ordinary data | `pull-preserves-nested-eager-map` |
| `datahike.test.api-test/test-pull-many-docs` plus `datahike.test.pull-api-test/ordered-pull-many-preserves-input-positions` | Empty vector, repeated ids, missing ids, lookup refs, exact ordering, vector length, and nil positions | Use Seon namespaced identity/name attrs and remote eids/tempids | Convert thrown malformed-ref cases to `:seon/error` assertions. Budget internals remain Datahike-only | `pull-many-preserves-order-duplicates-and-nils` |
| `datahike.test.entity-test/test-entity` | Fully realized ordinary attribute map, false value, lookup ref, and missing entity cases | Seed remote entities; expected map includes the chosen public `:db/id` rule | Do not port `entity-db`, `identical?`, IFn invocation, lazy `contains?`, partial print, or cache realization | `entity-returns-established-eager-map-or-nil` |
| `datahike.test.entity-test/test-entity-refs` and `test-entity-walk` | The underlying relationship data is useful pull/query fixture data | Reuse facts to prove an explicit pull projection | Lazy nested Entity navigation, reverse lookup through `_attr`, `touch`, and walk behavior are deliberately unsupported host-object semantics | `entity-does-not-proxy-host-navigation` documents error/absence; `pull-explicitly-navigates-refs` proves the replacement |
| `datahike.test.api-test/test-datoms-docs`, `test-seek-datoms-doc`, and `test-index-range-doc` | Index component filtering, inclusivity, AVET range, and native ordering expectations | Use namespaced attrs and compare the selected established ordinary datom fields | Replace lazy seq operations with bounded page calls and await eager vectors | `index-reads-preserve-components-range-and-order` |
| `datahike.test.index-test/test-rseek-datoms` | Inclusive reverse seek, closest-value behavior, and descending order | Remote fixture uses bounded reverse pages | Do not port “lazy take touches only tail” as facade semantics; maintained Datahike retains that internal proof | `reverse-index-page-is-inclusive-and-ordered` |
| `datahike.test.index-page-test/current-and-history-pages-compose-in-both-directions`, `as-of-avet-pages-compose-after-a-cardinality-one-replacement`, and `polarity-is-part-of-an-exact-history-cursor` | Page concatenation, cursor, completion, temporal visibility, and added/retracted polarity are already ordinary semantic expectations | Convert Datahike datoms to the one chosen ordinary representation; seed via remote reports | Await each page. Retain exact cursor and order expectations | `index-pages-compose-across-current-as-of-and-history` |
| `datahike.test.api-test/test-db-docs` | An immutable value captured before a write still queries the old snapshot | Assert ordinary descriptor schema and stable old result rather than JVM class/type | Do not port `type datahike.db.DB`, deref, equality partition, hash, or metadata | `db-returns-immutable-ordinary-descriptor` |
| `datahike.test.api-test/test-transact-docs`, `datahike.test.transact-test/test-transact!`, and `test-tx-meta` | Transaction data effects, tempids, before/after values, tx datom polarity, and tx-meta | Use Seon's schema-first setup and avoid fixed eid/tx constants; derive ids and transaction ids from reports | Await Promise. Invalid tx input becomes ordinary error. Do not assert Future/deref behavior | `transact-report-preserves-native-semantic-shape` and `transact-error-is-ordinary-data` |
| `datahike.test.listen-test/test-listen!` | Two reports arrive in commit order, tx-meta and added/retracted datoms are preserved, unlisten prevents later delivery | Register through public facade, perform ordered remote transactions, use a Promise/latch with bounded timeout, then unlisten and transact once more | Await registration and writes. Compare ordinary datoms, not `Datom` identity. Add same-key callback replacement because Datahike documents it | `listen-delivers-ordered-reports-until-unlisten` and `listen-same-key-replaces-callback` |

### Fixture and assertion rules

- Copy only forms and expected ordinary values. Do not require
  `datahike.test.*`, call private vars, or share mutable connection fixtures.
- Seon fixtures use fully namespaced application attributes because that is a
  Seon database law. Mechanical attribute renaming is not a semantic deviation.
- Never assert fixed entity or transaction ids when a report can supply them.
- Each test captures database descriptors from `db` or transaction reports and
  passes those same ordinary values back through the public API. Constructing
  fake descriptor maps would fail to prove attachment and commit resolution.
- Multi-database tests must use different `:db-name` values. A second temporal
  view of one database is a separate test, not a substitute.
- Every Promise test uses `cljs.test/async`, terminates both success and failure
  branches, and has a bounded timeout through the existing transport/test
  mechanism.
- The JVM remote test asserts no returned tree contains a Datahike database,
  connection, Datom, Entity, lazy sequence, Future, Throwable, or derefable.
- Direct and `execute-many` forms reuse the same expected operation values so
  batching cannot silently invent a second result contract.

### Graduation set

The focused compatibility set is complete only when it proves:

- two- and three-named-database queries;
- current plus `since`, `as-of`, and history descriptors;
- `:in`-directed source replacement without argument reordering or corruption
  of nested descriptor-shaped data;
- eager pull, ordered pull-many nil placement, and eager entity;
- forward, reverse, range, temporal, and cursor index semantics;
- immutable descriptor reuse across later writes;
- ordinary transaction and listener report parity;
- listener replacement, order, and unlisten cutoff;
- uniform ordinary errors and caller-specific cancellation; and
- release after success, error, queued cancellation, active cancellation, and
  transport disconnect for every database participating in a query.

## Deviations that still require Sean's decision

The ordinary-data and eager-result choices are settled. These remaining public
contract choices are consequential:

1. **One public query name.** Recommend retaining `query` and not adding a `q`
   alias. Adding both creates two names for one mechanism with no compatibility
   benefit inside Seon.
2. **Public cancellation.** Recommend adding `cancel!` and public request ids
   now, because intentional cancellation and timeout observability are central
   to modest-hardware operation. Deferring it preserves correctness but gives
   agents less control over expensive work.
3. **Index datom representation.** Choose `[e a v tx added?]` everywhere to
   match the current writer's compact ordinary representation, or choose
   namespaced maps everywhere for field discoverability. Do not mix them.
4. **Raw index convenience names.** Recommend keeping `index-page` as the sole
   primitive and implementing bounded `datoms`, `seek-datoms`, `rseek-datoms`,
   and `index-range` only as facade conveniences. Omitting them reduces API
   surface but gives up familiar Datahike/Datomic entry points.
5. **`execute-many` member failures.** Recommend an ordered vector containing
   success values or error values, so one bad independent read does not discard
   the others. Fail-fast would be simpler internally but less useful and would
   differ from the current per-member result model.
6. **Ambient versus explicit query ambiguity.** Recommend parsed `:in` plus
   binding/input-count alignment as specified above. It supports an omitted
   default database, explicit database descriptors, and ordinary relation
   sources without type guessing. Requiring only the namespaced map for
   multi-source queries needlessly abandons Datahike's positional API.
7. **Cross-source cache graduation gate.** Recommend implementing the ordered
   composite key, existing global dependency union, and multi-generation
   release as the first and only cache path before claiming multi-database
   parity. A blanket uncached multi-source mode is correct but creates a second
   behavior that the optimal architecture would immediately remove. Only a
   source without exact committed cache identity makes its query uncacheable.
8. **Ordinary relation sources.** Recommend preserving Datahike's ability to
   bind a set of datom tuples to a source variable, as exercised by
   `test-q-docs`. It already crosses as ordinary data and adds no host-object
   seam. Treat it as uncacheable initially rather than inventing an expensive
   retained-data hash in the facade.
9. **`unlisten!` success value.** Recommend a boolean indicating whether the
   key was present. Datahike returns its entire process-local listener map only
   because that is the return of `swap!`; preserving that would leak callbacks
   and host state and contradict the ordinary-data boundary.
10. **Transaction input arities.** Recommend accepting a transaction-data
    vector directly and one fully namespaced Seon argument map. Do not retain a
    variadic “each argument is a transaction item” convenience: Datahike does
    not define it, a vector already expresses it, and map transaction entities
    would be ambiguous with the Seon argument map.

## Recommended implementation boundary

The clean cut is not a compatibility layer beside the current protocol:

1. Replace the public facade contracts and ordinary database descriptor in
   place.
2. Replace the one-coordinate read request with ordinary operation arguments
   containing every declared database source descriptor.
3. Add one JVM rehydration/release owner shared by query, pull, entity, schema,
   indexes, and execute-many. Query resolution is directed by parsed `:in`
   source bindings; other operations have explicit database argument fields.
4. Patch maintained Datahike's cache and single-flight identity for every
   database query source, with the existing conservative dependency union and
   multi-generation eviction. Source-specific dependencies are a later
   propagation optimization.
5. Route every index function through eager `index-page`.
6. Normalize all direct and batched success/failure shapes at the facade, then
   update the measured call sites in semantic groups.
7. Prove different-database, current-plus-since, cancellation, disconnect,
   generation-close, listener-report, and bounded-index behavior before the
   final full suite and live cluster proof.

The highest-value performance property is architectural: native immutable
database values and Datahike's shared query computation stay inside one JVM,
while many Bun processes submit ordinary-data work concurrently. The facade
does not need to reproduce JVM objects to preserve that advantage.
