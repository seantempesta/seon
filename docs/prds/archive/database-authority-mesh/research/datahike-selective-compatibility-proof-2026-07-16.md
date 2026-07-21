---
type: research
status: complete
tags: [research, prd, database, cljs]
---

# Selective Datahike compatibility proof

## Result

Port a small semantic subset of Datahike's maintained tests into Seon's two
existing test surfaces. Do not copy Datahike's full suite and do not add a
runner:

- `test/seon/db/remote_contract_test.clj` proves the real JVM writer and
  Unix-domain socket against real Datahike databases; and
- `test/seon/db_remote_contract_test.cljs` proves the asynchronous public
  `seon.db` arities, ordinary values, request formation, and Promise results.

The JVM namespace is the semantic authority. The CLJS namespace must not
reimplement Datalog, pull, index, or transaction semantics with a fake
database. It proves that the public facade preserves the arguments and values
that the real boundary accepts. A small
`test/seon/db/remote_contract_fixtures.cljc` is justified only if both
namespaces consume the same ordinary query forms and expected values.

This gives selective compatibility without making every Seon edit pay for
Datahike's storage engines, HTTP server, generated bindings, planner
regressions, or full CLJ/CLJS matrix.

## Dependency ledger

| Owner | Selected source | Contract used |
|---|---|---|
| Maintained Datahike | `reference-code/datahike` at `670cd1ada40462cb5927f0dc687f6b3a95f9e13f` | Public semantics and executable fixtures |
| Datahike API catalog | `src/datahike/api/specification.cljc` | Arity, remote suitability, host-value, lazy-result, cache, cancellation, and resource facts |
| Datahike remote baseline | `test/datahike/test/http/server_test.clj` | One remote create/connect/transact/db/query/pull/entity/index/schema/release round trip |
| Datahike query semantics | `query_pull_test.cljc`, `query_planner_test.clj`, `query_test.cljc`, and `api_test.cljc` | Multiple sources, temporal values, ordinary inputs, and exact find-result shapes |
| Datahike pull/index semantics | `pull_api_test.cljc`, `index_page_test.clj`, and `index_page_temporal_test.cljc` | Missing positions, eager bounded pages, native order, exact cursors, and history polarity |
| Datahike reports and cancellation | `listen_test.cljc`, `committed_report_test.clj`, and `query_cancel_test.clj` | Transaction reports, ordered committed delivery, unlisten, cancellation, and final release |
| Seon runners | `bin/test-writer` and `bin/test-cljs` | Namespace-selective JVM proof and exact-var CLJS proof |
| Seon boundary owners | `src/seon/db.cljs`, `src/seon/db/writer.clj`, `src/seon/db/protocol.cljc`, and `src/seon/db/transport/uds.{clj,cljs}` | One facade, one writer, one protocol, and one persistent session |

Datahike's catalog is the API inventory, not another Seon registry. It marks
`q`, `pull`, `pull-many`, `entity`, the index functions, `history`, `since`,
`as-of`, `schema`, and transactions as remotely meaningful. It also records
why Seon must adapt rather than imitate host behavior: `db`, temporal
constructors, transactions, and connections return host values, while
`datoms`, `seek-datoms`, `rseek-datoms`, and `index-range` are lazy.
`index-page` and `pull-many` already return eager bounded values.

## Source tests to port

### Remote baseline

Datahike's `http/server_test.clj` `run-server-tests` is the minimum precedent
for a real remote contract. It proves that a remote transaction changes the
database value, `db-after` becomes the connection's current value, query and
pull preserve results, entity is readable, index reads preserve datoms, schema
is data, and release terminates ownership.

Port the semantics, not its HTTP machinery:

| Datahike assertion | Seon JVM proof |
|---|---|
| Transaction changes `db-before` to `db-after` | Both fields are ordinary database maps; the new value is usable by a later read and the old value remains immutable |
| `q` returns `#{["Peter" 42]}` | `query` over the returned database value returns the same relation set through UDS |
| `pull` returns an ordinary map | Selector, lookup ref/eid, nil absence, and nested refs survive Transit |
| `entity` is remotely readable | Seon returns the settled eager ordinary map or nil, never Datahike's lazy Entity |
| `datoms`/`seek-datoms` return native order | The one eager bounded `index-page` owner returns ordinary datoms in native order |
| `schema` is a map | `installed-schema`/schema returns eager ordinary data at the selected value |
| `since` and `as-of` create remote values | Seon creates ordinary database maps and resolves them only when an operation consumes them |
| `release` ends connection ownership | Explicit database release and physical-session close release all acquired databases exactly once |

Do not port Datahike's Swagger, EDN/JSON HTTP bindings, fixed TCP ports,
authentication token, or database create/delete API. Seon uses Transit over a
native Unix-domain socket, its cluster lifecycle is operator-owned, and these
tests would verify removed mechanisms rather than compatibility.

### Query sources and ordinary inputs

Port the following source behavior into one real multi-database fixture:

- `query_pull_test.cljc/test-multiple-sources`: `$1`, `$2`, and the default
  `$` select the database named by the parsed `:in` position, including pull
  expressions that explicitly select a source.
- `query_planner_test.clj/test-multi-source-queries`: two databases join while
  scalar and collection inputs retain their positions; `not`, `or`,
  predicates, lookup refs, disjoint sources, and empty results remain normal
  Datalog behavior.
- `query_planner_test.clj/test-variable-attribute-multisource-function-value`:
  a value computed from `$a` constrains a variable-attribute lookup in `$b`.
  This is a high-value regression because an argument-position or source-map
  mistake can produce a Cartesian product rather than merely an empty result.
- `api_test.cljc/test-since-docs`: current `$` and `$since` from the same
  database are two independent source values in one query.

The Seon extension is a third named database. One query declares
`[:in $a ?ordinary $b $c]`, interleaves an ordinary scalar or relation value,
and joins all three databases. It proves that the protocol neither assumes the
first argument is the only database nor prepends an out-of-band database.

Add the shortest false-positive guard: place a complete database-shaped map
inside a scalar, tuple, collection, and relation input. Only top-level values
bound to parsed source variables may be resolved. The nested maps must reach
Datahike byte-for-byte as ordinary data. Do not recursively search arguments
for maps that look like database values.

### Temporal database values

Derive the expected rows from Datahike's `test-history-docs`,
`test-as-of-docs`, and `test-since-docs`:

- a current value sees the replacement;
- `as-of` sees the earlier assertion;
- `since` sees only datoms added after its cut;
- history sees both assertions and the retraction polarity; and
- one query can combine current with `since`, `as-of`, or history sources.

Cover numeric transaction cuts in the always-run selective proof. One instant
case is sufficient because it exercises serialization and Datahike's native
time-point resolution; do not retain sleeps from Datahike's documentation
tests. Obtain transaction instants from committed reports instead.

The old database map must remain usable after a later transaction. It may not
silently resolve to the latest head. A missing, unreachable, released, or
lineage-mismatched database value returns the one ordinary Seon error value.

### Exact query result shapes

Port exact values rather than `seq`/`set` coercions:

| Datahike source | Required Seon result |
|---|---|
| `query_pull_test/test-find-spec` and `api_test/test-q-docs` | Relation remains a set of tuples |
| `query_pull_test/test-find-spec` | Scalar find returns the scalar or nil |
| `query_pull_test/test-find-spec` | Collection find returns a vector |
| `query_pull_test/test-find-spec` | Tuple find returns one vector or nil |
| `query_test/test-return-maps` | `:keys`, `:strs`, and multi-find return maps retain their key and collection shapes |
| `query_pull_test/test-basics` | Pull expressions inside `:find` return eager ordinary maps in the same positions |

This proof catches a common remote-adapter error: normalizing every query into
a vector of rows destroys Datahike's semantic result shape even when its
contents look right.

### Pull, entity, and index

- Port `pull_api_test/ordered-pull-many-preserves-input-positions` exactly:
  empty inputs, repeated eids, numeric eids, lookup refs, and missing refs
  preserve vector length, order, duplicates, and nil slots.
- Port the two positional pull arities and dependency argument-map behavior
  from `api_test/test-pull-docs`; separately prove Seon's namespaced public map
  arity.
- Define `entity` compatibility as eager full entity data or nil. Exclude
  `entity-db`, lazy lookup, reverse navigation through a host Entity, equality,
  deref, and object identity. Callers use query or pull for those relationships.
- Port `index_page_test/current-and-history-pages-compose-in-both-directions`,
  `polarity-is-part-of-an-exact-history-cursor`, and the empty/exact/partial
  page cases. A cursor has all five native fields including `added?`; forward
  and reverse continuation neither repeat nor skip a datom.
- Port `index_page_temporal_test`'s reverse temporal-order and history-polarity
  regressions. These specifically prevent a transport adapter from rebuilding
  ordering with ordinary comparison.

Do not port unbounded lazy `datoms`, `seek-datoms`, `rseek-datoms`, or
`index-range` realization. If familiar facade names remain, their proof calls
the same bounded `index-page` implementation and asserts its limit.

### Transactions and listeners

Port `listen_test/test-listen!` and the remote transaction assertions as one
real UDS sequence:

1. register a keyed listener before the transaction;
2. transact with transaction metadata;
3. receive one ordinary report containing `:db-before`, `:db-after`, ordered
   ordinary `:tx-data`, `:tempids`, and `:tx-meta`;
4. prove the direct transaction result and listener report have the same
   semantic shape;
5. register the same key again and prove replacement rather than duplicate
   delivery;
6. unlisten and prove the later commit produces no event; and
7. prove two sessions listening to different database names do not receive
   one another's reports.

Use `committed_report_test/durable-publication-offers-once-in-commit-order` as
the ordering source. Do not run projection, Transit encoding, or callbacks on
Datahike's writer thread; the Seon test observes only the already committed
delivery.

### Cancellation, disconnect, and release

The Datahike cancellation tests establish that preset and concurrent
cancellation end a query with structured `:datahike/canceled` evidence and a
later query can still run. Seon's contract adds transport ownership:

- cancel one public request ID while another caller has joined the same
  multi-source computation; the canceled request ends, the sibling completes,
  and no database value is released early;
- cancel the final caller before execution and while running; both paths reach
  terminal cleanup without a retained queued job or active request;
- disconnect with one current and two secondary named databases acquired;
  every acquisition and temporary materialized database value releases once;
- disconnect during success, structured failure, cancellation, and response
  delivery exercises the same terminal release owner;
- release one secondary database explicitly, prove duplicate release is a
  false/no-op result, and prove another acquired database remains usable; and
- after final release, completed cache and single-flight evidence retains no
  entry whose composite source identity includes any released generation.

The last assertion is required for two- and three-source compatibility. A
green result followed by a leaked native database value is not compatible with
the modest-hardware goal.

## Proposed selective test inventory

### JVM writer and UDS namespace

`seon.db.remote-contract-test` should contain these independently selectable
behaviors internally, while `bin/test-writer` selects the namespace as its
smallest supported unit:

| Test var | What it graduates |
|---|---|
| `database-values-and-transaction-reports-are-ordinary-data` | Current/old database maps, native report fields, immutability, no host owners |
| `query-preserves-every-find-result-shape` | Relation, scalar, collection, tuple, return maps, find-pull |
| `query-resolves-two-and-three-database-sources-in-place` | Named sources, interleaved inputs, joins, default `$`, no prepend |
| `query-rehydrates-only-top-level-source-bindings` | Nested descriptor-shaped data remains ordinary data |
| `query-composes-current-as-of-since-and-history-values` | Numeric/instant filters, current plus temporal sources, old-value stability |
| `pull-entity-and-pull-many-preserve-eager-values` | Positional and map pull, eager entity, exact nil positions |
| `index-pages-preserve-native-order-and-cursors` | Current/history/temporal, forward/reverse, five-field cursor, bounded pages |
| `keyed-listeners-preserve-commit-order-and-isolation` | Register-before-read ordering, replacement, unlisten, per-database delivery |
| `cancel-disconnect-and-release-clean-every-source` | Joined caller cancellation, success/failure cleanup, 2/3-source generation eviction |

Every JVM test uses real Datahike databases and the real writer/UDS path. Pure
protocol validation remains in `seon.db.protocol-test`; do not repeat it here.

### CLJS facade namespace

`seon.db-remote-contract-test` should prove only public behavior:

| Test var | What it graduates |
|---|---|
| `database-values-are-ordinary-immutable-maps` | `db`, `as-of`, `since`, and `history` shapes |
| `positional-and-namespaced-map-arities-form-the-same-requests` | Query, pull, pull-many, entity, index, transact, listen, and unlisten arities |
| `query-preserves-source-argument-order-and-result-shapes` | 2/3 sources, interleaved ordinary data, no recursive descriptor rewrite |
| `all-authority-operations-return-promises-of-values` | Async honesty and no thrown transport failure into the agent loop |
| `listener-registration-replacement-and-unlisten-are-session-owned` | Public key/callback lifecycle without a second local listener registry |
| `cancel-and-release-use-the-public-request-and-database-values` | One request ID, explicit secondary release, physical close fallback |

The CLJS transport may be a recording fake because semantics already ran
through the JVM boundary. It must record ordinary requests and return ordinary
responses; it must not construct a Datahike connection, database, Datom,
Entity, listener, cache, or temporal wrapper.

## Selective commands and cadence

During writer/protocol work, run only the real remote namespace:

```bash
bin/test-writer seon.db.remote-contract-test

```

`bin/test-writer` supports namespace selectors, not exact vars. Keep the
namespace intentionally small instead of adding a runner merely to select one
JVM var.

During facade work, run the exact affected CLJS var:

```bash
bin/test-cljs --test=seon.db-remote-contract-test/query-preserves-source-argument-order-and-result-shapes
bin/test-cljs --test=seon.db-remote-contract-test/positional-and-namespaced-map-arities-form-the-same-requests

```

At the public-contract checkpoint, run both focused namespaces:

```bash
bin/test-writer seon.db.remote-contract-test
bin/test-cljs --test=seon.db-remote-contract-test

```

Run Datahike's own selected tests only when the maintained dependency changes.
Use its existing Kaocha selector mechanism from inside
`reference-code/datahike`; do not copy its build or storage matrix into Seon.
Run Seon's full writer and CLJS gates only at the integrated unit checkpoint,
then retain the focused commands as the ordinary regression path.

## Explicit exclusions

Compatibility does not mean copying every Datahike behavior across a process:

- no remote connection record, deref, Entity, Datom, lazy sequence, function,
  callback, Future, Promise, Throwable, writer, or store object;
- no HTTP server, Swagger, authentication-token, EDN, JSON, Java, native,
  Python, or generated binding test;
- no alternate storage backend, schema-flexibility, attribute-ref, planner,
  or full Datahike CLJ/CLJS matrix in Seon's normal gate;
- no speculative `with`/`db-with`, function-valued `filter`, `entity-db`,
  connection-level branch API, or maintenance operation unless Seon later
  deliberately exposes it;
- no unbounded realization of a lazy index API;
- no macro query layer and no second query language; and
- no exact host exception class or message comparison. Public failures are the
  one ordinary Seon error shape with stable kind and relevant bounded data.

Datahike's upstream tests remain authoritative for its internals. Seon's port
owns only the semantic behavior that must survive the Bun-to-JVM boundary.

## Graduation matrix

The selective proof graduates only when every row is green through the real
JVM boundary and its matching public CLJS facade case:

| Area | Required proof | Failure that the proof must catch |
|---|---|---|
| Database values | Current, old, as-of, since, and history are ordinary maps and resolve exactly | Silent fallback to latest or leaked native value |
| Two/three databases | Ordered source bindings with interleaved scalar/relation inputs | First-database-only routing, prepending, or argument reordering |
| Descriptor recognition | Only parsed top-level source arguments rehydrate | Nested descriptor-shaped application data rewritten |
| Query values | Relation, scalar, collection, tuple, return maps, and find-pull match Datahike | Universal row-vector normalization |
| Pull/entity | Selector semantics, eager entity, ordered pull-many nils | Lost duplicates, removed nils, lazy host Entity |
| Index | Native current/history/temporal order, both directions, exact cursor | Ordinary comparator rebuild, skipped/repeated datom, unbounded result |
| Transaction | Native report fields as ordinary data; old/new values usable | Compact incompatible envelope or host Datom leakage |
| Listen | Register-before-read, key replacement, ordered commits, unlisten, database isolation | Broadcast, duplicate callbacks, missed acknowledgement window |
| Cancel | One caller detaches; final caller cancels work; later query succeeds | Sibling canceled, request retained, released source used by running work |
| Disconnect/release | Success, failure, cancellation, and delivery release all 2/3-source ownership once | Cache/single-flight/native database retained after terminal state |
| Public facade | Familiar positional plus fully namespaced map arities; Promise results | Call-site-wide signature drift, sync fiction, thrown transport error |

This is a compatibility proof, not a permanent fork of Datahike's suite. Its
value is that every future protocol or authority implementation can run the
same small ordinary-data fixture and demonstrate the same semantics.
