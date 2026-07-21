---
title: Async database facade source audit
type: research
status: complete
tags:
  - database
  - runtime
  - protocol
---

# Async database facade source audit

## Decision

Unit 7 should replace `seon.db`'s local Datahike handles with one process-owned
UDS session and an async, ordinary-data facade. `seon.client` remains the sole
lifecycle driver; `seon.db` owns the session resource and request semantics.
There is no replica, cached latest database value, compatibility namespace, or
second public database API.

The smallest coherent implementation boundary is:

1. connect once with `seon.db.transport.uds/connect!`;
2. negotiate capabilities, select/ensure the database when bootstrapping, and
   acquire it;
3. validate every request and response with `seon.db.protocol`;
4. expose async query, pull, schema, index, transaction, and interest functions
   that return ordinary values or `:seon/error` values; and
5. close the physical UDS session as the inverse of acquire.

Do not call the operator's `release-database` request during ordinary pod
shutdown. That operation is an administrative deletion/lifecycle boundary,
not the inverse of acquiring a session
([[remote-seon-db-contract-freeze-2026-07-16]]).

## Dependency ledger

- The maintained protocol vocabulary and schemas are
  `src/seon/db/protocol.cljc:23-50,174-348,458-718,748-1004`.
- Canonical request constructors are
  `src/seon/db/protocol.cljc:1239-1408`; success/failure constructors are
  `:1410-1423`; request/response validators begin at `:1454`.
- The Bun-owned native socket is already hidden inside the session closure in
  `src/seon/db/transport/uds.cljs:31-66,237-537`. Public transport operations
  are only `request!`, `close!`, and `connected?` at `:539-555`.
- Existing async execution-local state is
  `src/seon/db/internal.cljs:62-149`. It already uses AsyncLocalStorage and is
  the correct ClojureScript mechanism for a coordinate scoped to a turn or
  operation.
- The old replica boundary and launch descriptor are in
  `src/seon/db/replica.cljs:1-25,84-197`; its request functions still call the
  removed `uds/rpc` at `:223-410`. Delete this namespace after consumers move.
- `seon.client` currently opens the local replica at
  `src/seon/client.cljs:860-906,2583-2654` and tears it down at `:3042-3054`.
  These are the lifecycle call sites to replace, not a reason to put lifecycle
  policy in the facade.

## One session owner

Use one private atom in `seon.db`. Its value contains only the UDS session
closure, selected database name, acquisition state, negotiated capabilities,
and local interest handlers. It must not retain a database value or a mutable
"latest coordinate."

Open is idempotent for the same live selection and rejects conflicting opens.
Close atomically removes the state before calling `uds/close!`, which prevents
new requests from entering a closing session. A disconnect callback may clear
state only when it still identifies that same session. Transport terminal
semantics already reject pending requests and fence late frames
(`src/seon/db/transport/uds.cljs:384-429,487-537`).

The session supports concurrent requests. The transport correlates replies by
the existing request ID and enforces a 16-request in-flight cap
(`src/seon/db/transport/uds.cljs:442-486`). Do not add round-robin scheduling,
worker IDs, or another queue.

## Coordinate semantics

For reads, choose the coordinate in this order:

1. the request's explicit `:seon.db/coordinate`;
2. `:seon.db/coordinate` in the current AsyncLocalStorage transaction context;
3. an awaited `resolve-head` request.

Core operations should pass an explicit coordinate. Ad hoc reads may pay one
head resolution. Never treat the head returned by acquire as mutable global
truth: that recreates replica staleness and makes concurrent turns race.

A successful transaction returns its committed coordinate. The owning
turn/eval orchestration runs dependent work in a nested existing transaction
context carrying that coordinate; it does not mutate process state. This keeps
read-your-write local to the async execution that needs it.

## Public facade shapes

Reuse current `seon.db` names and keys. Map them to protocol constructors at
one private request function. That function must validate the request before
`uds/request!`, await it, validate the response, verify the response request
ID, and convert failure to an ordinary `:seon/error` value.

- `head-coordinate` returns a coordinate or `:seon/error`.
- `query` accepts `:seon.db/query`, optional `:seon.db/args`, coordinate, and
  budgets; it returns the query result directly. `query-with-evidence` returns
  `{:seon.db/coordinate c, :datahike.query/result r,
  :datahike.query/attribute-dependencies d,
  :datahike.query/cache-evidence ce,
  :datahike.query/resource-evidence re}`.
- `pull` accepts the existing pull pattern, ref, coordinate, and budgets and
  returns a map, nil, or error. `pull-many` uses the same shape with a vector of
  refs and preserves order. `entity` is `pull '[*]`; delete lazy entities.
- `installed-schema` returns the schema at its resolved coordinate.
- `index-page` returns `{:seon.db/coordinate c, :seon.db/datoms xs,
  :seon.db/complete? b}` plus the protocol cursor when incomplete.
- `execute-many` accepts ordinary protocol member data and returns the member
  results plus the one resolved coordinate. Preserve the constructor's 4 MiB
  default outer result bound (`src/seon/db/protocol.cljc:1283-1292`).
- `transact!` preserves its existing application envelope: success contains
  `:seon.db/ok?`, coordinate, tempids, transaction number/count, added, and
  retracted; failure contains `:seon.db/ok? false` and an ordinary error. Ignore
  the protocol's echoed transaction data and metadata now; Unit 9 removes that
  bandwidth after measurement.
- `listen!` accepts the current handler plus either query or datom patterns.
  Its returned key is the existing listen request ID. Register the local
  handler before sending and roll it back on failure. `unlisten!` removes the
  handler only after its acknowledgement. Do not invent another subscription
  ID.
- Transaction-coordinate resolution and KNN map directly to their existing
  protocol constructors. Embeddings remain asynchronous and do not gate
  unrelated reads or writes.

The protocol constructors already define the exact wire maps: resolve-head
`src/seon/db/protocol.cljc:1245-1250`, query `:1252-1256`, pull `:1258-1268`,
schema/index `:1270-1281`, execute-many `:1283-1292`, listen/unlisten
`:1300-1310`, acquire `:1325-1330`, transaction `:1361-1375`, resolve
transaction `:1389-1395`, and KNN `:1397-1408`. The facade must not hand-build
a parallel wire dialect.

## Error contract blocks implementation first

The writer currently collapses most caught `ExceptionInfo` values to the
protocol's generic database error in
`src/seon/db/writer.clj:3000-3014`; member failures do the same at `:785-791`.
The original `:seon.error/kind` survives only inside a rendered string. The
facade therefore cannot preserve `:user-input` versus `:core-bug` correctly.

Repair this before broad consumer migration. Carry the existing
`:seon.error/kind` as ordinary response data on protocol failures; do not add a
new error taxonomy. The failure response is deliberately open
(`src/seon/db/protocol.cljc:748-756`), so this does not require a new operation.
The facade returns only ordinary data such as:

```clojure
{:seon.error/message message
 :seon.error/kind :user-input
 :seon.error/data {:seon.db.protocol/error-kind protocol-kind
                   :seon.db.protocol/request-id request-id}}
```

Do not use `seon.error/->map` on a JavaScript exception: it retains
`:seon.error/raw` (`src/seon/error.cljs:115-160`), which violates the ordinary
wire/result boundary. Known stale-coordinate, candidate-conflict, malformed
request, and budget failures are user input; connection loss, invalid server
responses, and internal faults are core bugs unless an existing caller
timeout/cancel contract says otherwise.

## Delete together

The in-place facade cut removes these local-authority sections from
`src/seon/db.cljs` together, rather than preserving two execution paths:

- Datahike/connector/index/entity/replica requires (`:57-77`), local handle and
  raw-report schemas (`:133-334`), `*conn*` attachment (`:351-398`), and local
  provenance boot (`:763-845`);
- synchronous local query/read/index/schema/pull/entity functions
  (`:855-1533`), temporal handles (`:1535-1777`), captured replay/candidate
  machinery (`:1779-1932`), and direct Datahike listeners (`:1938-1981`);
- local database counts/provenance scans and coordinate-keyed config cache
  (`:2030-2240`), whose consumers must use async query/evidence or an authority
  operation; and
- `src/seon/db/replica.cljs` after its client and embedding call sites use the
  facade.

Keep the existing AsyncLocalStorage transaction/agent/operation contexts and
pure Malli-to-Datahike/EDN transformations. Move pure transaction
normalization only if needed to keep `seon.db` free of local Datahike objects.
Registered-versus-installed schema enforcement belongs at the JVM authority,
not as a second CLJS guard.

This source-coherent deletion must be coordinated with the consumer migration:
the no-argument synchronous callers identified by
[[async-consumer-migration-audit-2026-07-16]] cannot remain compiling against
an async facade. Git is the archive; do not leave aliases or compatibility
wrappers.

## Existing proof to retain

- Ordinary recursive wire values and rejection of Promise, Error, and socket
  owners: `test/seon/db/protocol_test.cljs:20-73`.
- Closed query/schema maps and generated transaction requests:
  `test/seon/db/protocol_test.clj:43-77,303-349`.
- Execute-many bounds and interest maps:
  `test/seon/db/protocol_test.clj:148-252`.
- Fragmentation, coalescing, multiplexing, and out-of-order responses:
  `test/seon/db/transport_uds_test.cljs:152-204`.
- Bun-native socket round trip and timeout correlation/capacity retention:
  `test/seon/db/transport_uds_test.cljs:430-526`.
- Writer acquire/head and read operation integration:
  `test/seon/db/writer_integration_test.clj:56-113,301-539`.
- Writer transaction acquisition and commit:
  `test/seon/db/writer_integration_test.clj:1113-1180`.

## Earliest falsifier

Through a real writer and UDS session, issue a known user-input failure, such
as an invalid pull selector, through the new facade. Assert that the resolved
value is recursively ordinary, has `:seon.error/kind :user-input`, retains the
protocol error kind and request ID in `:seon.error/data`, and neither rejects
the Promise nor closes the session. The current writer fails this assertion;
until it passes, consumer migration would silently degrade the application's
central error contract.
