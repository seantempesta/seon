---
type: research
status: complete
tags: [database, capability, research]
---

# Coordinate-pinned read materialization contract

## Decision

The authority exposes Datahike's existing `q-with-evidence`, `pull`, and
`pull-many` result shapes without another result envelope or shape tag. Seon's
existing successful protocol response is the only envelope. Before that
response reaches Transit, one recursive materialization check accepts only
bounded, already-realized ordinary values and rejects every host-owned value.

The operation is selected by the existing `:seon.db.protocol/operation`; the
same request carries the existing request ID, database name, attachment, and
portable coordinate. The authority resolves that coordinate once to a
host-local immutable `DB`, injects it as the first Datahike argument, executes,
checks the returned value, and then drops the `DB`. Neither requests nor
responses contain a database value, connection, Entity, Datom, function,
`IDeref`, Future, Throwable, thread, stream, or lazy sequence.

This is the smallest contract because Datahike already owns query result
shaping, pull recursion, lookup-reference resolution, resource accounting,
cache evidence, identical-query single-flight, and cancellation. A Seon
`relation`/`tuple`/`row` wrapper would duplicate information already fixed by
the query's `:find` form and would force every consumer to unwrap values that
are ordinary Clojure today.

## Dependency ledger

- Datahike `940810f5`:
  - `reference-code/datahike/src/datahike/query.cljc`
  - `reference-code/datahike/src/datahike/pull_api.cljc`
  - `reference-code/datahike/src/datahike/resource.cljc`
  - `reference-code/datahike/src/datahike/impl/entity.cljc`
  - `reference-code/datahike/src/datahike/datom.cljc`
  - `reference-code/datahike/src/datahike/api/specification.cljc`
- Transit CLJ/CLJS at the checked-out repository coordinates:
  - `reference-code/transit-clj/src/cognitect/transit.clj`
  - `reference-code/transit-cljs/src/cognitect/transit.cljs`
- First-party seams:
  - `src/seon/db/protocol.cljc`
  - `src/seon/db/executor.clj`
  - `src/seon/db/writer.clj`
  - `src/seon/db/transport/uds.{clj,cljs}`
- Consumer and deletion inventories:
  - [[remote-read-consumer-classification-2026-07-15]]
  - [[exhaustive-read-consumer-and-deletion-inventory-2026-07-15]]

## Exact Datahike behavior

### Query input and output

`normalize-q-input` accepts a query vector/list, map, quoted form, or EDN
string. A map may contain `:query`, `:args`, `:offset`, `:limit`, `:order-by`,
`:settings`, `:request-id`, and the three resource limits. When `:args` is in
the map, separately supplied arguments are deliberately ignored
(`query.cljc:100-118`). The remote contract should remove that ambiguity:

- `:seon.db.protocol/query-form` contains only the Datalog query form;
- `:seon.db.protocol/arguments` is the only argument vector;
- Datahike's database argument is never caller-supplied;
- request identity and resource limits live in their existing protocol fields;
- offset, limit, order-by, and settings should not be admitted in the first
  read cut unless each is separately schema'd and required by a migrated
  consumer.

The authority constructs the one Datahike call map internally:

```clojure
{:query query-form
 :args (into [database] arguments)
 :request-id request-id
 :max-work max-work
 :max-results max-results
 :max-result-weight max-result-weight}

```

Datahike itself determines the result shape (`query.cljc:2357-2372`):

| Datalog `:find` | Exact result |
|---|---|
| `?x ?y` | set of vectors |
| `[?x ...]` | vector of values |
| `[?x ?y]` | one vector or nil |
| `?x .` | one value or nil |
| `:keys` | vector of maps with the requested keys |

Do not normalize a relation to a vector, a collection to a set, or nil to an
empty collection. Relation ordering is intentionally unspecified. Query
metadata such as function counts is not part of ordinary query results and the
first remote operation does not expose `:stats?` or `:count-fns?`.

`q-with-evidence` already returns exactly three namespaced facts
(`query.cljc:123-144`):

```clojure
{:datahike.query/result result
 :datahike.query/cache-evidence cache-evidence
 :datahike.query/resource-evidence resource-evidence}

```

The query response should merge those three facts into the existing successful
response beside request ID, database name, attachment, and coordinate. Do not
put them under an additional `:seon.db.protocol/result` map.

Query arguments preserve ordinary Clojure values, including lookup refs,
collection/tuple/relation bindings, rules, symbols, keywords, and maps. They
must not contain another database source: the first cut permits only the one
authority-injected `$`. A future multi-source operation would need explicit
authorization and coordinate identity for every source; allowing a DB in
`arguments` would bypass both.

### Pull and pull-many

`pull` and `pull-many` parse the same selector and resolve every entity ID with
`entid-strict` before work begins (`pull_api.cljc:328-359`). Accepted entity
identifiers therefore retain Datahike's forms: numeric entity ID, ident, or
two-element lookup ref. A lookup ref is data such as
`[:seon.agent/id "agent-1"]`; it is not a new protocol coordinate.

Pull returns a map or nil. Pull-many returns a vector of maps in the same order
as its input IDs. An unresolved ID throws `:entity-id/missing`; it does not
insert nil for that member. The authority converts that Throwable to the one
failed protocol response and preserves safe exception data as payload data.
It must not partially return a pull-many result.

Reference attributes do not return Entity objects. Without a subpattern they
return ordinary `{:db/id eid}` maps, with `:db/ident` when applicable;
subpatterns recursively return maps (`pull_api.cljc:150-184`). Cardinality-many
attributes are vectors, not sets. Component refs expand automatically. Pull
attribute `:limit` defaults to 1000 (`pull_api.cljc:16,128-148`), but that is a
per-attribute semantic limit, not an operation memory bound; the request still
needs Datahike's maximum work, results, and result-weight limits.

Pull and pull-many responses use the existing
`:seon.db.protocol/result` directly. They need no cache-evidence wrapper because
the current Datahike pull API does not expose query-cache evidence. Both must
return the exact coordinate used, so a client can compose the result with other
members without guessing whether the head moved.

### Resource limits and errors

Datahike's resource owner counts synchronous work, result nodes, and shallow
result weight. It raises structured `ExceptionInfo` as soon as a configured
limit is exceeded (`resource.cljc:14-31,56-93`). Final certification refuses
an uncounted collection rather than realizing it for cache admission
(`resource.cljc:120-168`). These are the right first limits and should remain
the exact names:

- `:datahike.resource/max-work`
- `:datahike.resource/max-results`
- `:datahike.resource/max-result-weight`

They complement rather than replace the authority executor's bounded queue and
the transport's encoded-byte bound. A shallow result-weight limit cannot prove
the encoded byte count of a large string/map graph. Materialize first, encode
once, then enforce the frame/session byte budget before admission to delivery.

The failed response remains the current
`:seon.db.protocol/success? false`, error kind, and message. Add at most one
optional `:seon.db.protocol/error-data` ordinary-value field; never serialize
the Throwable or stack. Datahike exception data currently uses dependency-owned
keys such as `:error` and `:entity-id`. Preserve those keys exactly inside the
error-data payload. The outer error response stays fully namespaced.

## Ordinary-value grammar

The current provisional `:any` schemas for query arguments, selector, entity
ID, and results are too weak. One shared recursive predicate/schema should be
used by request validation and result materialization. Its semantic grammar is:

```clojure
::ordinary-scalar
[:or nil? :boolean :string :int :double :keyword :symbol :uuid inst? uri?
     biginteger? bigdecimal? ratio?]

::ordinary-value
[:schema {:registry
          {::ordinary-value
           [:or ::ordinary-scalar
            [:vector [:ref ::ordinary-value]]
            [:set [:ref ::ordinary-value]]
            [:map-of [:ref ::ordinary-value] [:ref ::ordinary-value]]
            [:and sequential? counted? (complement record?)
             [:sequential [:ref ::ordinary-value]]]]}}
 [:ref ::ordinary-value]]

```

The implementation predicate must check forbidden host classes before testing
collection interfaces, because both Entity and Datom deliberately implement
collection-like interfaces. It rejects, recursively:

- Datahike DB values and connections;
- `datahike.impl.entity/Entity`;
- `datahike.datom/Datom`;
- functions and records;
- `IDeref`, Future, Promise, Thread, Throwable, streams, channels, sockets, and
  JavaScript host objects;
- lazy, uncounted, reducible-only, iterator, and Java array values.

Persistent lists are allowed because Datalog queries and rules use lists and
Transit preserves them. Lazy sequences are rejected even though both Transit
implementations have sequence handlers: those handlers realize the sequence
during encoding (`transit-clj:92-97`; `transit-cljs:164-170,235-251`). Encoding
must never become an unbounded database computation or move an exception from
the read executor into the delivery worker.

The scalar list above should be implemented with explicit CLJ/CLJS predicates,
not Java class names in protocol schemas. It covers Datahike's stored scalar
types and Transit CLJ/CLJS common handlers. Do not add a Transit default
handler: transit-clj intentionally throws for unknown types when no handler is
provided (`transit.clj:139-166`), which is a useful final defense after the
materialization check.

### Bare keys are payload data

Seon's “every map key is fully namespaced” rule applies to maps Seon designs:
protocol envelopes, application entities, and public function requests and
responses. It cannot be applied recursively to the embedded Datalog language
or to values returned by Datahike:

- map-form Datalog uses dependency-owned bare syntax keys such as `:find`,
  `:where`, `:in`, and `:args`;
- `:find ... :keys id tag` deliberately returns `{:id ... :tag ...}`
  (`query.cljc:2742-2749`);
- database keyword values may themselves be bare (`:ready` is valid data);
- query inputs and expression results may contain maps whose keys are data;
- Datahike exception data currently contains bare dependency-owned keys.

Therefore validate that every *outer protocol field* is a qualified keyword,
but preserve all keys recursively inside `query-form`, `arguments`, `selector`,
`result`, and `error-data`. This is an embedded-language/value boundary, not an
exception permitting bare keys in Seon-owned schemas.

## Executable falsifiers

Run these against one memory database from `clojure -M:writer` before wiring a
network session:

1. Insert two entities with a unique identity, keyword value (including a bare
   keyword), and ref. At one captured DB value, assert the five exact shapes:
   relation set, collection vector, tuple vector/nil, scalar/nil, and `:keys`
   vector of bare-key maps.
2. Query with scalar, tuple, collection, relation, lookup-ref, and rules inputs.
   Assert the authority injects the only DB and the caller cannot pass a DB as
   an argument.
3. Pull by numeric ID, ident, and lookup ref. Pull a ref with and without a
   subpattern. Assert there is no Entity anywhere in the result.
4. Pull-many two valid lookup refs and assert ordered vector output. Include one
   missing lookup ref and assert one failed response with no partial result.
5. Return or inject, at every nesting depth, a DB, connection, Entity, Datom,
   function, promise/atom, Future, Throwable, and lazy sequence. Assert the
   materialization check fails before Transit encoding and before queueing
   response bytes.
6. Round-trip every accepted value through the exact CLJ Transit writer and
   CLJS Transit reader and compare with `=`. Include UUID, instant, URI,
   bigint/bigdecimal/ratio, symbols, lists, sets, namespaced and bare keywords,
   bare-key return maps, and nested pull maps.
7. Set each resource limit below observed use and assert a failed response with
   bounded ordinary error data. After every failure, executor evidence must show
   no retained request identity.
8. Resolve coordinate T, begin a blocked write, execute query + pull +
   pull-many against T, release the write, and assert every response still names
   T and reflects only T. This falsifies accidental head re-resolution per
   operation.
9. Measure one large relation and pull-many batch as: read CPU, materialization
   walk, Transit encode CPU, uncompressed bytes, decode CPU, and retained bytes.
   Request count must remain one and the result graph must be walked only once
   before encode.

The local probe on 2026-07-16 confirmed Datahike's exact relation, collection,
tuple, scalar, return-map, nested-ref pull, and pull-many shapes. Transit
round-tripped the accepted persistent values. It rejected Entity and Datom as
unsupported classes without custom handlers. It would encode a LazySeq by
realizing it, confirming why the explicit pre-encode rejection is required.

## Performance and copy consequences

- Datahike already realizes query tuples into persistent vectors and relation
  sets; pull builds transient maps/vectors then persists them. A second recursive
  copy would double allocation. The materializer is a validation walk that
  returns the identical root value.
- Validate once on the authority worker's completed value, then encode once.
  Do not validate again per Bun session and do not create per-session result
  maps. Delivery can retain one immutable encoded body plus each session's byte
  offset.
- `q-with-evidence` adds bounded volatile counters only for the evidence call;
  ordinary `q` remains allocation-compatible. Remote query should use it once,
  because recreating evidence in Seon would miss Datahike cache/single-flight
  truth.
- Pull-many is one traversal frame over all IDs (`pull_api.cljc:328-359`) and is
  the correct replacement for query-then-N-pull loops. Query pull expressions
  are even closer when the IDs are produced by the same query.
- The materialization walk is O(number of returned nodes), which encoding must
  already pay. It is preferable to trusting Transit because it fails before
  byte allocation and prevents hidden lazy work on the delivery pool.

## Implementation boundary

Replace the provisional `:any` schemas in `seon.db.protocol` with the one
ordinary-value owner before graduating query/pull/pull-many. Add no codec
handler, result-shape tag, remote Entity operation, or compatibility path.
Execute all three through the existing fair read executor against one exact
resolved value. Once their focused protocol and live cross-coordinate proofs
pass, migrate consumers directly to these operations and delete the local
replica reads in the same cut that makes each old owner unreachable.
