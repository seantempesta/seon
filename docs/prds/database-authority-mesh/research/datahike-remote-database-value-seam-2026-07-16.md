---
type: research
status: complete
tags: [research, prd, database, decision, flow]
---

# Datahike remote database-value seam

## Recommendation

Keep the already-selected flat ordinary database map at the Bun boundary:

```clojure
{:db-name "default"
 :t 536870916
 :as-of nil
 :since nil
 :history false
 :datahike/commit-id #uuid "72482707-c5c3-52b5-b803-8fcd3d89df2f"}

```

Do not expose Datahike's `RemoteDB`, `RemoteHistoricalDB`, `RemoteAsOfDB`, or
`RemoteSinceDB` records, and do not translate their Transit tags into
one-key maps. Datahike's remote implementation proves the native semantic
decomposition—one committed raw `DB` plus `HistoricalDB`, `AsOfDB`, and
`SinceDB` wrappers—but its client records also carry a `remote-peer` host owner
and route every call through one HTTP peer. Those are exactly the host values
the Bun boundary is intended to exclude.

The smallest implementation is a Seon authority boundary over existing
Datahike functions:

1. route `:db-name` through the session's already-acquired registry entry;
2. use the entry's live connection for the matching head commit or
   `d/commit-as-db` for a retained ancestor;
3. verify `:datahike/commit-id`, `:t`, and reachability before applying
   `d/as-of`, `d/since`, then `d/history` in the supported canonical order;
4. pass the resulting native value to Datahike only for the physical request;
   and
5. fully realize the result and call `d/release-materialized-db` in `finally`
   only when the raw value was loaded by commit.

No Datahike database-value source change is required for this cut. The one
dependency addition the boundary actually needed already exists at maintained
Datahike `0070d507`: `d/query-source-bindings` identifies parsed top-level
source argument positions. That lets the authority rehydrate database maps
only where Datahike says a database source belongs, while leaving identical
maps in relation or scalar data untouched.

## Dependency ledger

| Owner | Selected source | Relevant seam |
|---|---|---|
| Maintained Datahike | `reference-code/datahike` at `0070d507728159cb48c4c46d249d88db829ac679` | Exact source inspected for native database records, readers, Transit, API wrappers, query source parsing, versioning, and HTTP remote tests. |
| Native raw database | `src/datahike/db.cljc:132-411` | A committed raw `DB` owns indexes and carries exact process-local cache identity separately from its persisted commit ID. |
| Native temporal values | `src/datahike/db.cljc:499-675` | `HistoricalDB`, `AsOfDB`, and `SinceDB` are thin wrappers over `origin-db`; history has no point, while as-of/since carry `time-point`. |
| Native temporal constructors | `src/datahike/api/impl.cljc:148-194` | `since`, `as-of`, and `history` validate temporal capability and construct the native wrappers. History is idempotent. |
| Native retained commit | `src/datahike/versioning.cljc:403-443` | `commit-id`, `commit-as-db`, and `release-materialized-db` are the exact persisted identity, materialization, and cleanup primitives. Loading through a connection preserves cache generation ownership. |
| Query source positions | `src/datahike/query.cljc:2817-2863`; `src/datahike/api/specification.cljc:143-151` | Parsed `SrcVar` bindings yield ordered top-level argument positions. Cache arguments already remove only those positions. |
| Datahike server serialization | `src/datahike/transit.cljc:13-79` | Native values encode as recursive tagged DB/wrapper shapes; a raw DB payload contains connection/store identity, commit ID, maximum entity ID, and maximum transaction. |
| Datahike server readers | `src/datahike/readers.cljc:18-45` | A tagged raw DB is resolved from an active connection and commit; temporal payloads construct native wrappers. This is useful evidence, but it bypasses Seon's logical-name authorization, lineage proof, and explicit materialization cleanup. |
| Datahike HTTP client | `src/datahike/remote.cljc:29-162`; `src/datahike/http/client.clj:19-87,152-188` | Client tags decode to peer-carrying records. Remote dispatch rejects arguments from different peers, then sends one HTTP request per API call. |
| Upstream remote proof | `test/datahike/test/http/server_test.clj:9-101` | The suite proves transaction reports, immutable DB values, query, pull, datoms, schema, entity, since/as-of records, and connection release through EDN, Transit, and JSON. |
| Native multi-source proof | `test/datahike/test/query_planner_test.clj:1037-1052`; `test/datahike/test/api_test.cljc:387-394` | Datahike queries two distinct database values and a current plus `SinceDB` source in the same `:in`. |

The existing Seon registry remains the routing and authorization owner. A
Datahike connection ID is not a public database name: `datahike.store/connection-id`
is a process-oriented vector containing store identity, branch, and sometimes
writer backend. Exposing it would leak physical placement and make a future
non-Datahike authority emulate a Datahike implementation detail.

## What Datahike's remote representation actually does

Datahike already has two complementary converter sets:

- the server `datahike.transit/write-handlers` converts native `DB` and
  temporal wrappers into tagged Transit payloads;
- the client `datahike.remote/transit-read-handlers` converts those tags into
  `Remote*` records and dynamically attaches `*remote-peer*`.

For a raw database, `datahike.transit/db->map` emits:

```clojure
{:store-id  [store-uuid branch]
 :commit-id commit-uuid
 :max-eid   max-eid
 :max-tx    max-tx}

```

For temporal values, Transit recursively emits `{:origin <encoded-db>}` plus
`:time-point` for as-of/since. The EDN printer expresses the same structure as
`#datahike/DB`, `#datahike/HistoricalDB`, `#datahike/AsOfDB`, and
`#datahike/SinceDB` (`db.cljc:717-767`). This is strong confirmation that the
native semantics are raw immutable value + temporal wrappers; it is not a good
public Bun value shape.

On the client, every `Remote*` constructor associates the dynamic remote peer
(`remote.cljc:56-106`), and `get-remote` inspects those record owners before
each API call (`http/client.clj:152-188`). Therefore the records are not
ordinary values despite looking map-like. They encode both database meaning
and transport destination.

The server reader is also deliberately weaker than the required authority
boundary. `db-from-reader` takes `:store-id`, obtains any matching active
connection, reads the commit directly with `k/get`, and calls `stored->db`
(`readers.cljc:18-29`). It does not:

- authorize a logical database name against the current session;
- prove the commit is reachable from that named database's current head;
- verify the claimed basis transaction;
- preserve attached cache identity through `commit-as-db`; or
- pair a resource-owning materialization with `release-materialized-db`.

Reuse Datahike's native versioning functions, not this generic reader, for the
Seon authority.

## Representation comparison

### Flat Datomic-shaped map — selected

Advantages:

- ordinary map in CLJ, CLJS, Bun, Transit, EDN, and JSON;
- established Datomic lookup vocabulary (`:db-name`, `:t`, `:as-of`,
  `:since`, `:history`) plus Datahike's existing persisted
  `:datahike/commit-id`;
- one map allocation on Transit decode, without a record, dynamic peer, or
  recursive origin tree;
- database routing is explicit and transport-neutral;
- temporal constructors are pure map updates in Bun and native wrappers are
  constructed only inside the JVM request;
- query source replacement can be limited to parsed top-level source
  positions; and
- a future Bun/Rust authority can implement the same value without Datahike
  classes or physical store IDs.

The flat shape intentionally canonicalizes the supported temporal surface. It
represents current, as-of, since, history, and history-of-as-of. It should
reject simultaneous as-of and since and unknown wrapper compositions until
their semantics are deliberately added. Arbitrary recursive wrapper identity
is not a compatibility requirement.

### Tagged-literal-shaped one-key maps — rejected

An ordinary approximation such as:

```clojure
{:datahike/AsOfDB
 {:origin {:datahike/DB
           {:store-id ... :commit-id ... :max-tx ... :max-eid ...}}
  :time-point 536870914}}

```

keeps the type tag visible without a record, but has no corresponding
Datahike public API. It adds an invented map encoding of Transit itself. It
also:

- exposes physical connection/store identity instead of logical `:db-name`;
- recursively repeats the origin for every wrapper;
- requires callers to pattern-match a wrapper tree rather than use ordinary
  database lookups;
- allocates at least one extra map per wrapper on decode;
- either requires recursive rewriting, which can corrupt identical ordinary
  application data, or still requires parsed source-position handling; and
- couples every future authority to Datahike's class hierarchy.

Transit tags already solve typed serialization when both endpoints want host
types. Reifying those tags as one-key maps gives the costs of the tagged model
without its automatic handler dispatch.

### Modified `Remote*` records or converters — rejected for production

Changing the Datahike HTTP client handlers to return ordinary maps would not be
a local optimization:

- remote dispatch currently discovers the peer from record protocol
  `PRemotePeer`;
- write handlers dispatch by concrete record class, while ordinary map
  handlers cannot distinguish a database map from arbitrary map data;
- HTTP tests explicitly assert `RemoteSinceDB` and `RemoteAsOfDB` classes;
- recursive tags cause server readers to rehydrate every tagged occurrence,
  whereas Seon must rehydrate only parsed source positions; and
- request-per-call HTTP semantics do not supply Seon's persistent multiplexed
  session ownership or multi-database admission.

Keep the Datahike remote implementation as an upstream compatibility oracle
and source of semantic fixtures. Port its operation sequence selectively to
Seon's UDS tests. Do not make it Seon's client runtime.

If implementation later reveals duplicated native wrapper inspection in more
than one authority, the only justified Datahike addition is a small pure host
function that describes a native database value as ordinary Datahike data. It
must not route by database name, attach a peer, materialize a commit, or own
release. There is no evidence that this extra API is needed for the first cut:
the request already carries the temporal fields, and head/transaction report
values are raw native databases.

## Resolution and rehydration

### One descriptor

For a descriptor `value` and the current UDS session:

1. Validate the closed ordinary map before any store access.
2. Resolve `(:db-name value)` only from the session's acquired registry
   entries. Passing an old descriptor after explicit secondary `release` must
   fail; it must not reacquire implicitly.
3. Capture the entry's live connection and current raw `d/db`.
4. If the descriptor commit equals `d/commit-id` of the head, use the head and
   mark it unowned by this request.
5. Otherwise prove the requested commit is an ancestor reachable from the
   current head, then call `d/commit-as-db` through the connection. Passing the
   connection preserves Datahike's generation/cache identity
   (`versioning.cljc:415-434`). Mark that raw value request-owned.
6. Require the raw value's maximum transaction to equal descriptor `:t`.
   Never treat `:t` as the as-of point.
7. Apply the non-nil `:as-of` or `:since`, followed by `d/history` when
   `:history` is true. The wrapper delegates search and index access to its
   origin while contributing the temporal search context
   (`db.cljc:529-563,594-631,660-675`).
8. Return both the operation value and the owned raw value. Cleanup releases
   the raw value, not merely its lightweight wrapper.

Use ordinary error values for missing acquisition, missing commit, unreachable
lineage, basis mismatch, invalid temporal point, and unsupported composition.
Never fall back to the latest head.

### Multi-database query

Call `d/query-source-bindings` once on the query form. For each returned
`:datahike.query.source/argument-position`, inspect only that top-level
argument. A database descriptor there is resolved as above. An ordinary
relation source remains ordinary data. No nested walk is permitted.

Deduplicate identical descriptors within the request so the same retained raw
value is materialized once. Record the distinct `:db-name` set before
admission, because releasing any member database must wait for the query's
physical terminal state. Preserve the original argument order when passing
native values to `d/q`; maintained Datahike already uses the ordered source
symbols/positions for its composite cache identity (`query.cljc:2824-2863`).

This supports:

- two current values from different named databases;
- current plus `since` or `as-of` from one database;
- three or more named databases; and
- interleaved scalar/relation inputs.

It also preserves an ordinary map that merely happens to equal a database
descriptor when that map occurs inside a relation or another ordinary input.

## Retention and release

The ordinary descriptor owns no JVM resource. There are two existing and
separate lifetimes:

1. **Named database lifetime.** The persistent session acquires each logical
   database once. The default remains until disconnect; explicit Datahike-named
   `release` drops one secondary database acquisition by `:db-name`. This owns
   the connection, indexes, store cache, writer, and query-cache generation.
2. **Request materialization lifetime.** A retained non-head commit loaded by
   `d/commit-as-db` is request-owned. Its raw database stays alive until query,
   pull, index traversal, eager entity conversion, serialization, failure, or
   cancellation is physically terminal, then
   `d/release-materialized-db` runs exactly once.

Reachable ancestor commits remain durable database history while the named
database is acquired; a descriptor is not a lease per immutable value. After a
branch/restore operation makes a commit unreachable, the old descriptor must
fail lineage validation rather than pinning or resurrecting the old branch.

Never release a head obtained from `d/db`, and never release a temporal wrapper
instead of the raw materialization. Lazy results cannot cross the boundary:
realize and certify them before cleanup.

## Transit copy and allocation implications

A direct probe on the selected checkout encoded and decoded three small
representations with Transit JSON. The absolute sizes are not a production
benchmark because the shapes contain different routing fields, but the
structural result is decisive:

| Value | Encoded bytes |
|---|---:|
| Flat selected as-of descriptor | 161 |
| Recursive one-key-map approximation | 244 |
| Native Datahike tag decoded as nested `Remote*` records | 234 |

For current values, the same probe produced 156 bytes for the flat map, 174
for a one-key map, and 169 for the Datahike tag. More important than these tiny
payload differences is allocation topology:

- the flat descriptor is already ordinary Transit data and decodes directly
  to one persistent map;
- the one-key temporal representation decodes an outer tag map, wrapper
  payload map, origin tag map, and raw payload map;
- the Datahike client handler builds the nested maps, constructs a record per
  level, and associates `remote-peer`; and
- `datahike.remote/map-without-remote` performs `into {}` plus `dissoc` for
  every record on each encode (`remote.cljc:139-153`).

None is zero-copy: Transit must parse bytes and allocate Clojure/JavaScript
values. The performance win is to keep descriptors tiny and ordinary, resolve
native values once per physical request, share Datahike's existing indexes and
query cache, and serialize only eager results. A custom tag cannot avoid the
UDS byte copy or native result serialization; it only changes the objects
allocated around the small descriptor.

Do not optimize these tens of bytes before measuring whole requests. The
architecture-scale gains remain one JVM authority, shared Datahike indexes,
multi-source cache/single-flight, bounded parallel work, and no Bun replica.

## Exact selective proof

Reuse the existing runners and the already-planned remote contract namespaces.
The useful upstream sequence is `datahike.test.http.server-test/run-server-tests`
(`server_test.clj:9-101`), but omit HTTP format, Swagger, authentication,
remote connection record, and host-class assertions.

The JVM UDS contract needs these focused cases:

1. **Current value round trip.** `db`, transaction `:db-before`/`:db-after`,
   query, pull, datoms/index, schema, eager entity, and exact ordinary map
   fields.
2. **Temporal values.** Current, as-of, since, history, and history-of-as-of
   return the same results as direct native Datahike calls. Invalid as-of plus
   since is rejected before query execution.
3. **Parsed source positions.** Query two different named databases and a
   current plus since value. Interleave scalar and relation inputs. Put an
   exact descriptor-shaped map inside ordinary relation data and prove it is
   not rewritten.
4. **Exact immutable identity.** Head resolves without materialization; an
   ancestor resolves by commit and exact `:t`; wrong name, commit, basis, or
   sibling/unreachable lineage never falls back to head.
5. **Cleanup.** Instrument `d/release-materialized-db`: one ancestor used twice
   in a query materializes/releases once; head releases zero times; success,
   failure, cancellation, and disconnect all release exactly once after
   physical completion.
6. **Session release.** Repeated named `db` is idempotent; explicit secondary
   `release` makes old descriptors fail until explicit reacquisition; socket
   close releases every remaining name.

The CLJS facade contract should assert that `as-of`, `since`, and `history` are
pure immediate map transformations, while query/pull/index operations return
Promises of ordinary eager values. It should not assert Datahike record class,
tag, host identity, or deref behavior.

## Shortest falsifier

Before changing the whole protocol, make one real JVM test pass:

```clojure
(d/q '[:find ?left ?right ?ordinary
       :in $left-db $right-db [[?ordinary]]
       :where
       [$left-db _ :sample/value ?left]
       [$right-db _ :sample/value ?right]]
     <resolved-left-descriptor>
     <resolved-right-since-descriptor>
     [[<an exact descriptor-shaped ordinary map>]])

```

Construct the native expected result directly, then run the same form through
the proposed resolver. Assert equal result, two parsed database source
positions, the nested map unchanged, and exact-once release of the one retained
raw commit after eager completion.

This single test falsifies the riskiest assumptions at once: logical-name
routing, two-database rehydration, temporal wrapper reconstruction, parsed
source-only replacement, argument order, eager lifetime, and cleanup. If it
passes, the rest of the remote compatibility suite is systematic operation
coverage rather than another architecture decision.
