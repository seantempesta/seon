---
type: research
status: complete
tags: [research, prd, database, decision, flow]
---

# Database-value protocol cut

## Result

The smallest correct cut replaces the public and application-wire
`database-name + attachment + coordinate` triple with one ordinary database
value. It does not replace the registry, executor, UDS framing, request
correlation, Datahike connection ownership, query cache, or committed-report
feed. Those are already the right owners.

Use the previously selected flat value:

```clojure
{:db-name "default"
 :t 536870916
 :as-of nil
 :since nil
 :history false
 :datahike/commit-id #uuid "72482707-c5c3-52b5-b803-8fcd3d89df2f"}

```

Datahike `0070d507728159cb48c4c46d249d88db829ac679` supplies the missing
multi-source seam: `d/query-source-bindings` returns the ordered parsed source
symbols and their top-level argument positions. The authority replaces only
values at those positions. It never searches recursively for maps that look
like database values.

The atomic cut also replaces the compact transaction envelope and partial
listener event with Datahike's transaction-report map, after converting host
database values and Datoms to ordinary data. Direct operations and
`execute-many` return the same semantic values. There is no compatibility
protocol beside it.

## Dependency ledger

| Owner | Selected source | Constraint used by the cut |
|---|---|---|
| Maintained Datahike | `reference-code/datahike` at `0070d507728159cb48c4c46d249d88db829ac679` | `query-source-bindings`, composite query-cache identity, multi-generation release, native reports, temporal wrappers, and eager `index-page` are the implementation seams. |
| Query parsing | `src/datahike/query.cljc:96-119,867-885,2824-2863` | `normalize-q-input` and the parsed `SrcVar` bindings, not map-shape guessing, determine which top-level args are database sources. |
| Datahike remote values | `src/datahike/remote.cljc:40-106,121-162`; `src/datahike/transit.cljc:31-72` | Existing remote DB records prove the minimum native identity and exact wrapper structure, but they are host records attached to a remote peer and are not Seon's ordinary transport-neutral public value. |
| Datahike database values | `src/datahike/db.cljc:307-411,499-701` | A committed raw DB has exact process-local cache identity; history, as-of, and since are wrappers over an origin DB. |
| Datahike retained commits | `src/datahike/versioning.cljc:403-443` | `commit-as-db` preserves an attached generation and its materialized result must be released after terminal use. |
| Datahike report | `src/datahike/api/types.cljc:69-96` | The established result is `{:db-before :db-after :tx-data :tempids :tx-meta}`. |
| Datahike index paging | `src/datahike/index_page.cljc:14-37,91-159` | The native eager result keys and five-value cursor already provide bounded forward/reverse index traversal. |
| Seon protocol | `src/seon/db/protocol.cljc:200-332,394-678,730-961,1000-1457` | Version 8 currently repeats coordinates and attachments through every read, response, cursor, transaction, listener, KNN, and lifecycle shape. |
| Seon writer | `src/seon/db/writer.clj:567-589,607-613,656-939,1836-2069,2155-2452,2966-3189` | The writer already owns materialization, scheduling, cancellation, selective interests, session acquisitions, and disconnect cleanup. |
| Seon registry | `src/seon/db/registry.clj:597-830,1970-1992` | Acquisitions are already idempotent per transport connection and final release already drains before closing Datahike. |
| UDS | `src/seon/db/transport/uds.cljs:137-235,237-555`; `uds.clj:387-455,700-810` | Framing, correlation, backpressure, and physical-session cleanup are independent of database-value shape and stay in place. |
| Public facade | `src/seon/db.cljs:17-189,193-506,537-896` | The current draft translates a flat database value back into the old single coordinate; that translation is the compatibility path to delete, not complete. |

## Reconciliation with Datahike's remote records

Datahike already serializes:

```clojure
#datahike/DB {:store-id [store-id branch]
              :commit-id commit-id
              :max-tx max-tx
              :max-eid max-eid}
#datahike/AsOfDB {:origin <db> :time-point point}

```

That is the closest native description, and it supplies useful implementation
checks: the raw committed value has a connection/store identity, commit ID,
maximum transaction, and maximum entity ID; temporal values are wrappers, not
mutated raw databases.

It should not become a new one-key-map public encoding such as
`{:datahike/DB {...}}`:

- Datahike's actual seam is a tagged literal or Transit tag that reads into a
  `RemoteDB` record carrying `remote-peer`; a one-key map would be a new Seon
  encoding rather than reuse of that implementation.
- `:store-id` is Datahike's process/physical routing identity, including the
  branch. Seon's authorized logical session route is `:db-name`; exposing the
  physical route makes a future Bun, Rust, or cloud authority imitate a JVM
  storage detail.
- Nested wrapper maps are larger on every request and report. The independent
  encoding audit measured an as-of value at about 244 bytes nested versus 161
  bytes flat.
- The supported public compositions are current, as-of, since, history, and
  history-of-as-of. The flat value expresses each without losing an admitted
  semantic state. Simultaneous as-of and since remains rejected.
- `:max-eid` is useful native verification data but is not required to identify
  a retained value after `:db-name`, containing `:t`, and commit ID are checked.

Therefore the public value remains Datomic-shaped and transport-neutral, while
the JVM resolver directly checks the corresponding Datahike native facts. This
supersedes neither Datahike's own Transit handlers nor its remote API; Seon does
not instantiate those host records at the Bun boundary.

## Current call chain and the exact in-place replacement

### Current single-database path

1. `seon.db/open-session!` negotiates capabilities, ensures one named route,
   receives a coordinate, derives its attachment, and acquires that attachment.
2. Every read calls `read-request-base!`, which resolves or translates one
   coordinate and repeats database name, attachment, and coordinate in the
   request.
3. `scope-for-read` asks `connection-for-request` for that one name, compares
   the repeated attachment, and produces one committed executor scope.
4. `pinned-database` resolves the coordinate's commit, proves ancestry and
   scope, converts an earlier `t` to `d/as-of`, and returns one native value.
5. `execute-db-read` prepends that native value to every query's arguments.
   This prevents a caller from preserving Datahike's real `:in` positions and
   makes a second database an ordinary, unresolved map.
6. The response repeats the route triple. Transactions collapse the native
   report into coordinate, previous-coordinate, counts, and renamed fields.
   Listener events carry only coordinate plus matching datoms.

### Target path

1. A physical session acquires its default database once by logical name.
   `(db)` returns the latest ordinary value. A named `db` call lazily acquires
   that additional logical name once and returns its latest value.
2. Read requests carry database values where the operation naturally accepts
   them. A query carries its complete argument vector; pull, schema, index, KNN,
   transact, and listen carry one `:seon.db/db` selection.
3. One writer function resolves and retains every distinct database value for
   the request. Active-request state records every participating database name
   and committed scope, not one primary scope.
4. The operation runs with native values in their original positions.
5. One terminal owner releases each temporary `commit-as-db` materialization
   after success, failure, queued/active cancellation, result delivery failure,
   or disconnect. Session acquisitions remain live until explicit secondary
   release or disconnect.

The UDS namespaces remain unaware of database semantics. They continue to
frame and correlate ordinary maps.

## Minimal wire values

All maps below remain private to `seon.db` and the authority. Public functions
retain their Datahike-shaped positional and fully namespaced map arities.

### Reads

```clojure
;; Query. :seon.db/db is present only as the ambient-default candidate.
{:seon.db.protocol/operation :seon.db.protocol.operation/query
 :seon.db.protocol/request-id "..."
 :seon.db.protocol/query-form query-form
 :seon.db.protocol/arguments [arg0 arg1 ...]
 :seon.db/db database-value
 :datahike.resource/max-work n
 :datahike.resource/max-results n
 :datahike.resource/max-result-weight n}

;; Pull and pull-many.
{:seon.db.protocol/operation :seon.db.protocol.operation/pull
 :seon.db.protocol/request-id "..."
 :seon.db/db database-value
 :seon.db.protocol/selector selector
 :seon.db.protocol/entity-id eid}

;; Schema, index-page, and KNN use the same :seon.db/db field.

```

The query success is the native result plus existing Datahike evidence when
the evidence operation requests it. Pull returns a map or nil. Pull-many
returns an ordered vector preserving nils. Schema returns its ordinary map.
The index result keeps Datahike's native keys:

```clojure
{:datahike.index-page/datoms [[e a v tx added?] ...]
 :datahike.index-page/complete? false
 :datahike.index-page/cursor [e a v tx added?]}

```

The cursor is meaningful only with the immutable `:seon.db/db` in the next
request. It does not contain another coordinate wrapper.

### Query source rehydration

For each query:

1. Call `d/query-source-bindings` once. Its result is already ordered and uses
   Datahike's parsed `SrcVar` semantics.
2. Align arguments before replacement. Zero source bindings means no database
   is injected. Full input count means every source is explicit, including an
   ordinary relation source. When the first declared source is position zero
   and it is the one missing input, insert the captured ambient `:seon.db/db`
   there. Every other missing source is an arity error.
3. A small sibling pure Datahike helper should expose parsed top-level input
   count; do not duplicate `normalize-q-input`, implicit `$`, or built-in-rule
   injection in Seon. `query-source-bindings` remains the authority for source
   positions.
4. At each source position, resolve a value only when it validates as the
   complete closed database-value schema. An ordinary relation source remains
   ordinary data and makes the query uncacheable under the current Datahike
   policy.
5. Deduplicate equal database values within the request, replace their exact
   top-level argument positions, and pass the vector unchanged to Datahike.
6. Never recurse into an ordinary scalar, tuple, collection, relation, rule,
   pull selector, transaction, or entity map. A descriptor-shaped map nested
   inside any such value is application data.

This covers zero, one, and any number of named database sources without a
macro. It also preserves current-plus-since queries because each temporal
database value is resolved independently.

### Native transaction and listener report

```clojure
{:db-before database-value
 :db-after database-value
 :tx-data [[e a v tx added?] ...]
 :tempids {caller-tempid eid}
 :tx-meta ordinary-map}

```

Generated-ID and durable request-recovery facts may remain additional
namespaced keys when present. Delete coordinate, previous-coordinate,
transaction counts, added count, and retracted count; each is contained in or
derived from the report.

The listener registration result is its existing key. A datom event delivers
the same report shape with only matching `:tx-data`; a pressure gap remains a
distinct resynchronization event containing the latest `:db-after`. The key is
also the request ID, so no listener ID is added.

Datahike batching can give a logical transaction an uncommitted intermediate
native `:db-before`. Encode it as the final containing committed DB value with
an `:as-of` point equal to that logical report's native maximum transaction.
That is the established database-value expression of the state and replaces
`previous-coordinate`.

### Cancellation and release

Cancellation remains one request ID targeting another request ID. The active
entry's set of database names/scopes ensures release of any participating
secondary stops new dependent admission and waits for or cancels admitted
work. It does not create another cancellation identity.

Public `release` accepts an ordinary database value and selects the session
acquisition by `:db-name`. It returns `true` only when the secondary acquisition
was removed, `false` when already absent, and an error for the ambient default.
Disconnect cancels and awaits every request, removes interests, then releases
all remaining acquired names exactly once. Internally the registry still uses
the attachment it already owns to prove that final cleanup closes the intended
Datahike connection.

## Temporal resolution

The single JVM resolver performs these checks before any operation:

1. Require the descriptor's `:db-name` to be acquired by this physical
   session; passing an old released value never reacquires it.
2. Resolve head when commit ID matches, otherwise call `d/commit-as-db` on the
   acquired connection.
3. Prove the commit is reachable from that logical route's current head.
4. Require native maximum transaction to equal `:t` and native commit ID to
   equal `:datahike/commit-id`. Never fall back to head.
5. Reject simultaneous non-nil `:as-of` and `:since`.
6. Apply `d/as-of` or `d/since`, then `d/history` when requested. Numeric
   points must be within the containing value and name a real transaction;
   instants pass to Datahike unchanged.
7. Release a non-head materialized containing DB in the terminal owner, after
   every native wrapper and result has stopped using it.

Transactions require a current, unfiltered database selection. An optional
expected database uses the same complete unfiltered shape and compares logical
name, `t`, and commit ID at writer admission.

## Exact deletion inventory

### Delete from the public facade and application wire in the atomic cut

- `seon.db.coordinate` requires and public schemas in `seon.db`.
- `coordinate->db`, `db->coordinate`, `read-coordinate!`,
  `read-request-base!`, `head-coordinate`, and
  `resolve-transaction-coordinate!`.
- `::coordinate`, `::expected-coordinate`, `::head-coordinate`, and every
  public coordinate-bearing request/result option. Replace the write fence
  with `::expected-db`.
- `::attachment` from open-session results and all data-operation requests,
  responses, cursors, errors, listener registrations, and events.
- Repeated `::database-name` from data operations after the database value is
  present. Keep it only on named acquisition/ensure administration.
- `::history?` on read requests; history is part of the database value.
- Transaction `::previous-coordinate`, `::coordinate`, `::datoms-added`, and
  `::datoms-retracted`, plus facade `::tx`, `::tx-count`, `::added`, and
  `::retracted`.
- Listener coordinate-only events and the second compact listener report.
- `execute-many`'s top-level single coordinate/attachment and member response
  envelopes. Members carry their natural database values; results are ordered
  direct-operation values or error values.
- KNN's repeated coordinate and attachment. Its one database value selects the
  native vector index at that immutable commit.
- Cursor coordinate maps. Keep the native five-value Datahike cursor and the
  database value on the request.
- Coordinate/attachment alternatives from the protocol request and response
  unions. They must not remain accepted as a hidden compatibility version.

Administrative lifecycle operations currently sharing `protocol.cljc` must
either move to their private operator contract in the same cut or translate
their wire values to ordinary database values before the application protocol
is graduated. A public/wire coordinate exception would preserve the obsolete
abstraction everywhere through the shared response validator.

### May remain private temporarily

- `registry.clj`'s physical attachment, route bijection, branch identity, and
  current-coordinate calculations.
- Branch/restore intent and administration coordinates used to prove exact
  physical head transitions, provided they no longer enter the Bun application
  protocol or public `seon.db` values.
- `writer.clj`'s process-local committed scope containing Datahike connection
  ID and generation. This is the correct cache, executor, committed-report,
  cancellation, and release fence.
- Registry cleanup errors that record the private attachment for operator
  diagnosis. Public errors expose database name/value and the ordinary failure,
  not the attachment.

These private facts are implementation state, not a second database value.
They can be removed or renamed later without another application migration.

## Consequential multi-database API decision

The database-value read contract does not by itself settle write and listener
selection because Datahike accepts a connection for both, not an immutable DB.
Two reasonable choices remain, and Sean should select them explicitly.

### Recommended transaction selection

Use only these forms:

```clojure
(transact! tx-data)
(transact! {:seon.db/db secondary-db
            :seon.db/tx-data tx-data
            :seon.db/tx-meta tx-meta
            :seon.db/expected-db expected-db})

```

`:seon.db/db` chooses the acquired connection by `:db-name`; it does not make
that immutable value an implicit stale-head fence. `:seon.db/expected-db` is
the explicit complete optimistic fence. Omission selects the ambient default.
Reject temporal/history values for both fields.

Alternative: make every explicit `:seon.db/db` an implicit expected-head
fence. That is safer by default but surprising: a harmless named-database
selection captured before an unrelated write would fail, unlike Datahike's
connection-based transact. It also conflates routing with concurrency control.

Do not add `(transact! database tx-data)` unless Sean prefers it after this
tradeoff. Datahike's positional argument is a connection, not a database value,
and Seon deliberately exposes no connection value. The fully namespaced map is
unambiguous beside transaction entity maps.

### Recommended listener selection

Support:

```clojure
(listen! key callback)
(listen! database key callback)
(listen! {:seon.db/db database
          :seon.db/key key
          :seon.db/handler callback
          :seon.db/query query-form})

```

The database value selects the acquired connection by `:db-name`; registration
starts at the authority's current committed report boundary. It is not pinned
forever to the immutable commit, and temporal/history values are rejected.
Keys are unique across the physical session: registering the same key replaces
the previous listener even when it selected another database. This keeps one
key and one `unlisten!` operation.

Alternative: key listeners by `[db-name key]`, mirroring separate Datahike
connection listener maps. That permits the same key on two databases but adds
a compound identity and makes `unlisten! key` ambiguous. The global session
key is simpler and matches the user's preference for one universal identity.

A query interest remains scoped to one selected committed feed. It may use the
query only to derive attribute dependencies. A reactive query spanning several
databases needs one listener per database source or a later explicitly designed
multi-source interest; silently treating one source's commits as the whole
query would be incorrect.

## Shortest implementation order

1. Freeze the flat database-value schema and the transaction/listener choices.
2. Strengthen `protocol.cljc` in place: add database values and native results,
   remove coordinate/attachment application alternatives, and keep one request
   validator.
3. Change registry session acquisition sets to logical database names while
   retaining private attachments in entries. Add named acquire/release without
   implicit create.
4. Add the one writer database-value resolver and parsed query-source
   replacement. Record all database names/scopes on the active request and
   centralize terminal release.
5. Route direct reads and `execute-many` through that owner. Preserve query
   single-flight and cancellation; do not add an uncached multi-source path.
6. Return native transaction/index shapes and use the same report for
   selective listener delivery.
7. Replace the `seon.db` facade in place, then migrate consumers by operation
   family. Delete coordinate and compact-envelope code rather than adapting it.
8. Run the focused Datahike-derived JVM and CLJS compatibility selections,
   followed by one integrated writer/pod/live checkpoint under source freeze.

## Shortest falsifiers

- `query-source-bindings` returns zero, one, and three exact positions for
  pure, default-source, and interleaved multi-source queries.
- Default plus two acquired named databases execute one three-source join with
  all argument positions preserved.
- A descriptor-shaped map nested in an ordinary relation or scalar input is
  byte-for-byte unchanged at `d/q`.
- Current plus since/as-of/history values return the Datahike reference result;
  as-of plus since is rejected before execution.
- Reusing an old immutable value after a later write returns the old result;
  releasing its named secondary makes the same value fail without reacquiring.
- Closing any participating generation evicts/cancels the composite cache
  entry and releases every temporary materialization exactly once.
- Direct query and the corresponding `execute-many` member return equal values
  and equal error shapes.
- A transaction and matching listener callback carry equal native report
  fields; counts are derivable and no coordinate field survives.
- Forward/reverse temporal pages concatenate in native order using only the
  five-value cursor and the explicit database value.
- Cancel, timeout, explicit secondary release, graceful disconnect, and abrupt
  socket close leave no active request, listener, acquisition, materialized DB,
  query caller, or executor reservation.
- `rg` over public source and protocol schemas finds no application-wire
  `coordinate`, `attachment`, `previous-coordinate`, `tx-count`, `added`, or
  `retracted` compatibility field.

The graduation gate is the selective Datahike compatibility suite over a real
UDS writer plus the CLJS facade suite, not merely protocol-schema tests.
