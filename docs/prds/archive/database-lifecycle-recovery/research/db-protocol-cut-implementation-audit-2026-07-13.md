---
type: research
status: active
tags: [research, database, flow]
---

# Database protocol atomic-cut implementation audit

Date: 2026-07-13

Scope: the active CLJS pod, the JVM Datahike writer, their Unix-domain-socket
protocol, the local read replica, database lifecycle, focused tests, launch
scripts, and current architecture/runbook references. This is an implementation
audit, not a migration plan. Test data may be reset.

## TL;DR

The current database boundary works, but five concerns are interleaved across
`seon.store.wire`, `seon.store.internal.wire-node`, and
`seon.server.{wire,codec,broadcast,store,registry}`:

- semantic request, response, error, receipt, and transaction-event data;
- authoritative transaction execution and idempotency recovery;
- Unix-socket framing and connection resources;
- local Datahike replica attachment and feed catch-up; and
- translation from Seon database options to third-party Datahike/Konserve
  config.

Make one atomic cut, with no aliases and no legacy decoder:

| Concern | One owner after the cut |
| --- | --- |
| Cross-runtime semantic data | `seon.db.protocol.cljc` |
| Authoritative operations | `seon.db.writer.clj` |
| JVM UDS bytes and sockets | `seon.db.transport.uds.clj` |
| Node UDS bytes and sockets | `seon.db.transport.uds.cljs` |
| CLJS local read replica | `seon.db.replica.cljs` |
| JVM Datahike/Konserve config adapter | `seon.db.backend.clj` |
| JVM live Datahike connections | `seon.db.registry.clj` |
| Process composition | thin `seon.server.boot` |

The minimum live data protocol has exactly five operations:
`ping`, `ensure-database`, `transact`, `replay-transactions`, and optional
`knn-search`. The generic remote `q`, `pull`, and `schema` paths are test and
development leftovers: the pod reads its local Datahike value. `list-dbs` and
`remove-db` are likewise not used by the pod; lifecycle commands currently call
the registry through the writer REPL. Delete all five dead wire operations now.
A typed supervisor API can replace the arbitrary REPL later without restoring
them to the data protocol.

The largest simplification is database identity. The JVM backend must be the
only code that derives the Datahike database UUID. `ensure-database` already
runs before the CLJS peer calls `d/connect`; its success response should return
the authoritative database ID. The replica then uses that ID in its private
Datahike config. Delete the hand-maintained CLJS MD5/UUID reproduction.

Use keywords throughout the semantic protocol: fully namespaced keyword
operations, response statuses, typed errors, and events; one fully namespaced
key for every map field. Use one keyword database name end to end. Do not make
database routing optional and do not fall back to an ambient connection.

Rename the managed filesystem leaf from `/store` to `/db` in the same cut and
reset the test clusters. Literal `:store` remains only inside private adapters
that call Datahike or another third-party API whose config shape requires it.

The current transaction/feed state machine contains important, tested behavior
that must move intact: frozen request IDs across retries, commit-unknown after
reply loss, feed-before-response support, bounded replay to a fixed watermark,
overlap de-duplication, negative transaction IDs for replayed retractions, and
bounded live buffering. This is a rename and separation of ownership, not a
rewrite of those semantics.

Finally, there is no active “roster” data concept to preserve. That word is
overloaded prose for ordinary query results such as agent IDs, function
schemas, or test vars. Use the exact names (`agent-ids`, `resumable-agent-ids`,
`hosted-agent-ids`, `function-schemas`, `test-vars`). Agent membership remains a
database query; do not add a roster entity, registry, cache, or protocol.

## Settled constraints

- Database and DB are the Seon vocabulary. Store is not an architectural
  synonym.
- Every Seon map key and persisted attribute is fully namespaced.
- Semantic enum values are fully namespaced keywords, not strings.
- The protocol is data, not a transport API. UDS is one delivery adapter.
- Datahike is the semantic database. Konserve is a private storage dependency.
- One JVM is the authoritative writer. CLJS peers read local lazy Datahike
  values and forward writes.
- Runtime atoms are allowed for irreducible opaque resources such as open
  connections and sockets. They must not become semantic database authority.
- Request correlation is an operational fact, not actor provenance. Actor/user
  provenance remains transaction metadata.
- No migration or compatibility layer is required. Stop processes, reset test
  data, deploy both ends, and start cleanly.
- No forwarding namespaces, duplicate constructors, old-key readers, dual
  paths, or “v2” modules.
- Preserve behavior with behavioral and edge-case tests. Do not assert prompt
  wording or context prose.

## Current end-to-end behavior

### Cold attachment

1. `bin/seon` launches the JVM writer and currently passes a database name,
   backend, a path ending in `/store`, and request/publish socket paths.
2. `seon.server.boot` composes connection initialization, embeddings, registry,
   wire handlers, sockets, and a supervisor REPL.
3. The CLJS pod calls `ping` with bounded retry.
4. The pod calls `ensure-db`, causing the JVM registry to create/open a Datahike
   connection and initialize required schemas/functions.
5. `seon.store.wire` independently derives a database UUID, constructs the
   local file-reader Datahike config, and calls `d/connect` with a custom
   `PWriter`.
6. It opens the publish feed, replays any gap to a fixed upper transaction, and
   then applies live transaction events.

Steps 4 and 5 currently contain two implementations of database identity. The
target makes step 4 return the ID and deletes the second implementation.

### Write path

1. Pod code calls the sole public `seon.db/transact!` API.
2. Datahike dispatches the transaction through the replica's custom `PWriter`.
3. The replica creates one request ID and one complete transaction request,
   then retries that exact request map on transport failure.
4. UDS writes one length-prefixed Transit map to the writer.
5. The writer validates the request, resolves the named database connection,
   detects an existing receipt or transacts the logical transaction plus its
   receipt atomically, and returns a transaction report projection.
6. Datahike's JVM listener projects the transaction event and publishes it to
   feed subscribers. Datahike invokes listeners before it completes the
   transaction delivery, so the feed event may arrive before the RPC response.
7. The local writer correlates response and feed. It reports committed only
   when it can prove the local read side reached the commit. If all replies are
   lost, the correct result is commit status unknown, not “failed to commit.”

### Read path

Normal pod reads use the local Datahike database value through `seon.db`.
Consequently, generic remote query, pull, and schema operations duplicate a
path the runtime does not use. The only current remote read-like operation that
has a real caller is optional `knn-search`, because the index is owned by the
JVM writer.

### Feed recovery

The subscriber opens the live feed before replay. It buffers live events while
requesting bounded replay pages through a fixed `through-t` watermark, applies
replayed events in strict basis order, de-duplicates overlap, then drains the
buffer and enters live mode. On a gap, invalid page, or disconnect, it starts
the same recovery sequence from the last proven basis. Preserve this ordering.

## Dependency-grounded findings

### Datahike

The following behavior was verified in vendored source rather than inferred:

- `reference-code/datahike/src/datahike/config.cljc` owns and validates
  Datahike's literal `:store` config. That spelling is an upstream API detail,
  not Seon vocabulary.
- `reference-code/datahike/src/datahike/connector.cljc` checks the underlying
  `[:store :id]` when connecting and removes `:store` before comparing semantic
  config. It also reacquires the current branch head from Konserve for a
  non-streaming custom writer.
- `reference-code/datahike/src/datahike/writer.cljc` defines `PWriter` and
  dispatches a custom writer. After a successful transaction it calls native
  listeners before delivering transaction completion.
- `reference-code/datahike/src/datahike/writing.cljc` persists pointed-to
  content/schema/commit data before updating the mutable branch head.
- `reference-code/datahike/src/datahike/versioning.cljc` implements physical
  fork by copying Konserve keys. It warns that concurrent source writes can
  tear a copy and that unrelated secondary external data is not copied.

Implications:

- Keep `PWriter/-streaming?` false for the shared-file peer. Each dereference
  must observe the authoritative branch head rather than trust a pushed DB
  object.
- The current lock-free reader option is justified by this topology: immutable
  content is written before the atomically replaced branch root.
- Do not build a second logical replication database. The current feed advances
  the same local Datahike view.
- Keep fork/restore as explicit lifecycle work. Do not disguise it as a generic
  data-protocol operation.

### Konserve

`reference-code/konserve/src/konserve/impl/defaults.cljc` shows the default blob
read locking behavior. The file implementations use atomic replacement for the
mutable root (`Files/move` on the JVM and rename on Node). Therefore the active
replica may keep its private `:lock-blob? false` setting, but only inside the
Datahike/Konserve adapter and only while the single-writer immutable-root
invariant remains true.

### Transit

The vendored Transit JS reader and writer clear their per-message caches, so
reusing one instance in the single-threaded Node transport is safe. JVM Transit
readers/writers are stream-bound, so constructing them over each frame's byte
array is appropriate. One semantic map should be encoded once; do not add an
inner string/EDN/JSON encoding.

The JVM currently rejects frames over 16 MiB. The CLJS decoder does not
consistently enforce the same ceiling before buffering. The extracted UDS
adapters must share one documented numeric limit and enforce it on both encode
and decode, before allocating or buffering the payload.

## Target namespace ownership

### `seon.db.protocol.cljc`

Own only portable semantic data:

- operation, response, event, error, receipt, and datom schemas;
- pure request/response/event constructors;
- validation and Malli explanations;
- the current single receipt version;
- logical transaction hashing and receipt data derivation; and
- persisted receipt attribute schemas.

It must not open sockets, touch a Datahike connection, inspect a filesystem
path, select retry delays, or know how a publisher is implemented.

### `seon.db.writer.clj`

Own authoritative semantic execution:

- classify process-scoped, registry-scoped, and database-scoped requests;
- resolve a required database name for every database-scoped operation;
- perform transaction receipt lookup, conflict detection, recovery, generated
  ID allocation, and transaction execution;
- project transaction reports into protocol success responses and events;
- perform bounded replay page construction;
- invoke the optional KNN dependency; and
- register/unregister the Datahike listener used to publish events.

It accepts maps and returns maps. It receives connection lookup, publish, and
KNN functions through boot composition. It must not read/write a frame or own a
socket.

### `seon.db.transport.uds.clj`

Own JVM transport resources only:

- Transit encode/decode;
- unsigned/validated length framing and maximum frame size;
- request socket accept, per-connection read/write loops, and delivery to a
  supplied map handler;
- publish socket accept and fan-out to current subscribers;
- explicit close of servers, accepted channels, subscribers, and socket files;
  and
- transport-local failures such as malformed or over-limit frames.

The return from each `start-*` function must be a resource map with an explicit
stop operation, not merely a server channel. Subscriber state is a justified
resource-local atom; it is not database state.

### `seon.db.transport.uds.cljs`

Own the corresponding Node byte/socket behavior:

- Transit encode/decode and the same maximum frame size;
- partial-frame accumulation and multiple-frame extraction;
- RPC request delivery and timeout/cancellation;
- publish stream bytes and connection lifecycle; and
- socket closure and reconnect notification.

It must not define `ping`, `ensure-database`, `transact`, `q`, `pull`, `schema`,
`knn-search`, or replay constructors. Those are semantic protocol maps created
by `seon.db.protocol` and coordinated by the replica. It must not have a
standalone `-main`.

### `seon.db.replica.cljs`

Own the Datahike peer and semantic coordination:

- private translation from an authoritative database attachment to the CLJS
  Datahike/Konserve reader config;
- boot attachment: ping, ensure, Datahike connect, live feed, bounded replay;
- the custom non-streaming `PWriter`;
- frozen request creation and retry;
- response/feed correlation and local commit proof;
- bounded replay/live buffers and gap recovery;
- semantic retry/reconnect policy; and
- drain/disconnect/re-attach lifecycle.

The ideal public operation is one `attach!` call returning the connection plus
an opaque attachment resource. `seon.client/open-cluster-conn!` should not
manually recreate the attachment sequence.

### `seon.db.backend.clj`

Own the JVM private translation from Seon options to Datahike/Konserve config.
It should return a Seon descriptor containing fully namespaced facts such as
database ID and durable path plus an opaque Datahike config. The registry must
not reach into `[:store ...]` to recover those facts.

This is one of only two legitimate places for Datahike's literal `:store`: the
JVM adapter and the replica's private CLJS adapter. Proximum or a future cloud
backend may have a separate third-party config key with the same spelling; it
also remains private to that adapter.

### `seon.db.registry.clj`

Own only live JVM database connections and their lifecycle:

- `ensure-database!`;
- `connection`;
- `list-databases` for supervisor/runtime inspection;
- `release-database!`;
- `delete-database!`; and
- current explicit fork support until the lifecycle/time-travel chunk gives it
  a final typed supervisor boundary.

The registry atom is justified because an open Datahike connection is an opaque
runtime resource. It is not a list of semantic database membership facts and
must not grow an agent registry. Rename old “session” terminology to database.

### `seon.server.boot`

Keep this namespace as a thin process entry point. It composes one registry,
one writer, one request server, one publisher, optional KNN, and the temporary
supervisor REPL; it installs clean shutdown and then blocks. It should not
reimplement operation handling or framing.

## Canonical protocol

### Type decisions

| Field | Type and rule |
| --- | --- |
| Database name | One keyword type end to end. Parse a launch string once at the process edge. |
| Database ID | UUID generated only by the JVM backend and returned by ensure. |
| Request ID | Non-empty string identity generated once per logical transaction. |
| Operation | Fully namespaced keyword. |
| Response status | Fully namespaced keyword `success` or `failure`. |
| Error | Fully namespaced keyword plus a separate human message. |
| Event | Fully namespaced keyword. |
| Transaction/basis values | Integers; a replay datom's transaction position may be negative for a retraction. |
| Optional values | Omit the key. Do not transmit nil as an absence marker. |

Recommended core keys:

```clojure
:seon.db.protocol/operation
:seon.db.protocol/status
:seon.db.protocol/error
:seon.db.protocol/error-message
:seon.db.protocol/database-name
:seon.db.protocol/database-id
:seon.db.protocol/database-path
:seon.db.protocol/request-id
:seon.db.protocol/basis-t
:seon.db.protocol/basis-t-before
:seon.db.protocol/transaction-data
:seon.db.protocol/transaction-meta
:seon.db.protocol/temporary-ids
:seon.db.protocol/generated-candidates
:seon.db.protocol/generated-entity-ids
:seon.db.protocol/event
:seon.db.protocol/events
:seon.db.protocol/since-t
:seon.db.protocol/through-t
:seon.db.protocol/continuation-t
:seon.db.protocol/complete?
:seon.db.protocol/replayed-count
:seon.db.protocol/datoms-added
:seon.db.protocol/datoms-retracted
:seon.db.protocol/recovered?
:seon.db.protocol/query
:seon.db.protocol/limit
:seon.db.protocol/entity-ids
:seon.db.protocol/hits
```

Recommended operation values:

```clojure
:seon.db.protocol.operation/ping
:seon.db.protocol.operation/ensure-database
:seon.db.protocol.operation/transact
:seon.db.protocol.operation/replay-transactions
:seon.db.protocol.operation/knn-search
```

Recommended response values:

```clojure
:seon.db.protocol.status/success
:seon.db.protocol.status/failure
```

Recommended errors:

```clojure
:seon.db.protocol.error/invalid-request
:seon.db.protocol.error/database-failure
:seon.db.protocol.error/internal-failure
:seon.db.protocol.error/database-not-found
:seon.db.protocol.error/request-id-conflict
:seon.db.protocol.error/generated-candidate-conflict
```

Frame-too-large and malformed-frame errors belong to the transport namespace
because no valid semantic request exists yet.

The replica's local proof result is not the wire response status. Use a
replica-owned fact such as:

```clojure
:seon.db.replica/commit-status
:seon.db.replica.commit-status/committed
:seon.db.replica.commit-status/unknown
:seon.db.replica.error/feed-behind
```

Do not mix `committed`, `unknown`, or `feed-behind` into the semantic writer
response status enum. The writer can truthfully return success while the client
still cannot prove that its local reader reached the basis.

### Response schemas

Do not retain the current weak “boolean success plus optional arbitrary error”
map. Register one multi-schema response family, dispatched by operation and
status, with one typed failure shape. At minimum:

- ping success: status and optional protocol version/capability floor;
- ensure success: status, database name, authoritative database ID, optional
  resolved durable path, and current basis;
- transact success: status, database name, request ID, basis before/after,
  changed datom projection, tempid/generated-ID projections, counts, and
  recovered fact when applicable;
- replay success: status, database name, fixed through watermark, ordered
  events, continuation, completion, and count;
- KNN success: status, database name, bounded hit vector; and
- failure: status, operation when known, typed error, message, and database or
  request identity only when known.

Use `:any` only at the genuine Datahike/Transit value boundary: transaction
forms, a datom's value, and opaque third-party config. Do not use it for a whole
request, response, event, receipt, or hit map.

The transaction event has the same database, basis, datom, transaction-meta,
and request-ID vocabulary as the transact response. There must not be a second
event envelope vocabulary.

### Routing classes

Classify before resolving a connection:

| Class | Operations | Resolution rule |
| --- | --- | --- |
| Process-scoped | ping | no database |
| Registry-scoped | ensure-database | validates required name and creates/opens before resolution |
| Database-scoped | transact, replay-transactions, knn-search | required name, exact registry lookup, typed not-found failure |

Delete the ambient-connection fallback. An absent database name on a scoped
operation is an invalid request, not permission to write whichever connection
boot happened to hold.

### Durable transaction receipt

Atomically rename the persisted receipt vocabulary:

| Old | Target |
| --- | --- |
| `:seon.store.wire/id` | `:seon.db.protocol/request-id` |
| `:seon.store.wire/request-hash` | `:seon.db.protocol/request-hash` |
| `:seon.store.wire/protocol-version` | `:seon.db.protocol/receipt-version` |
| `:seon.store.wire.tempid/key-edn` | `:seon.db.protocol.tempid/key-edn` |
| `:seon.store.wire.tempid/entity` | `:seon.db.protocol.tempid/entity` |

Register one Malli entity schema for this persisted shape and derive Datahike
attribute declarations through the existing Malli-to-Datahike bridge. Avoid a
raw declaration vector that can drift independently from the registered Malli
schemas. This small bootstrap protocol schema is installed before domain
schemas; it is not a second schema authority.

The request ID identifies one logical write across delivery, durable receipt,
recovery, response, and event. A retry must reuse the exact complete request
map, not only regenerate the same ID. Reusing an ID with a different logical
hash is a typed conflict and does not transact.

## Operation inventory

| Current operation | Production caller | Decision | Reason |
| --- | --- | --- | --- |
| `ping` | CLJS boot/attachment | Keep as namespaced `ping` | Bounded readiness gate. |
| `ensure-db` | CLJS boot/attachment | Keep as `ensure-database` | Creates/opens and returns authoritative identity. |
| `transact` | Datahike custom writer | Keep | Sole authoritative mutation path. |
| `replay-tx` | Feed recovery | Keep as `replay-transactions` | Repairs missed live events. |
| `knn-search` | `seon.embed.cljs` | Keep, optional | JVM owns the embedding index. Require database name. |
| `q` | wire-node helpers/tests | Delete | Pod queries the local Datahike value. |
| `pull` | wire-node helpers/tests | Delete | Pod pulls from the local Datahike value. |
| `schema` | wire-node helpers/tests | Delete | Local Datahike/schema APIs own this. |
| `list-dbs` | no pod caller | Delete from data protocol | Supervisor inspection belongs to a future typed admin API. |
| `remove-db` | no pod caller | Delete from data protocol | Current lifecycle uses registry through writer REPL. |

The current default KNN call omits the database name and can reach the ambient
database in a multi-database writer. This is a real routing bug. Route KNN
through the same replica attachment descriptor and required protocol
constructor as all other database-scoped operations.

## Source and caller inventory

### Production namespace actions

| Current file | Action |
| --- | --- |
| `src/seon/server/wire.clj` | Extract semantic code to `seon.db.writer`; delete socket loop and dead handlers; then delete file. |
| `src/seon/server/codec.clj` | Fold framing into JVM `seon.db.transport.uds`; delete file. |
| `src/seon/server/broadcast.clj` | Fold subscribers into the publisher resource; delete global namespace/atom. |
| `src/seon/server/store.clj` | Replace with `seon.db.backend`; no forwarding namespace. |
| `src/seon/server/registry.clj` | Replace with `seon.db.registry`; rename session vocabulary. |
| `src/seon/server/client.clj` | Delete production smoke/test helper; keep any useful socket fixture under `test/`. |
| `src/seon/store/internal/wire_node.cljs` | Extract bytes to CLJS UDS adapter, delete wrappers and `-main`, then delete file. |
| `src/seon/store/wire.cljs` | Move the proven peer state machine to `seon.db.replica`; delete file. |
| `src/seon/server/boot.clj` | Require and compose writer/registry/transport resources. |
| `src/seon/client.cljs` | Call one replica attach/disconnect API. |
| `src/seon/embed.cljs` | Use protocol/replica response vocabulary and route the database explicitly. |
| `src/seon/db/id.cljc` | Read replica-owned commit proof keys, not old wire keys. |
| `src/seon/eval.cljs` | Read replica-owned commit proof keys. |
| `src/seon/db/internal.cljs` | Update the custom-writer contract/comment; no old namespace. |
| `src/seon/agent/ctx/render_fns.cljs` | Exclude the renamed protocol request-ID receipt attribute. |
| `src/seon/agent/debug.cljs` | Require replica, not store wire. |
| `src/seon/dev/runtime_id.cljc` | Remove only the obsolete standalone `proc:wire` assumption; preserve general runtime identity. |
| `src/seon/session.clj` | Archive/delete with the paused JVM session lane; do not port its agent-to-DB registry to `seon.db.registry`. |

The old `seon.session` mapping is not part of the active writer topology. Moving
it into the new registry would reintroduce exactly the semantic agent registry
the cut is removing.

### Launch and configuration actions

| File/surface | Required action |
| --- | --- |
| `bin/seon` | Replace all managed database leaves and messages with `/db`; update writer ns/options and registry REPL calls; stop both processes before reset; rebuild before restart. |
| `bin/acme` | Use the same `/db` leaf and lifecycle commands; update only after default-cluster proof. |
| `bin/seon-server-call` | Update registry response keys/namespace while the temporary REPL admin path remains. |
| `bin/test-writer` | Point at the renamed focused behavioral namespaces. |
| `bin/mcp-server-cljs` | Remove standalone `proc:wire` examples/probes. |
| `shadow-cljs.edn` | Delete the `:wire-node` build. The pod build already contains the transport. |
| `deps.edn` | Update stale comments/test examples; keep actual writer dependencies explicit. |
| `config/system.edn`, `config/acme.edn` | Do not confuse result persistence caps with database paths; schedule `store-edn-cap` vocabulary for its owning eval/config cut if not changed atomically here. |

The old default path in some code/tests is `data/sessions/<name>/store`, while
scripts use `data/clusters/<name>/store`. The settled target is
`data/clusters/<name>/db`. The backend, writer launch, replica, cluster
create/fork/reset/destroy, ACME wrapper, help text, tests, and active runbooks
must all change together. There is no read fallback to either old path.

### Third-party `:store` exceptions

Do not mechanically replace every textual `:store`:

- Datahike config requires `{:store {...}}`.
- The CLJS replica's Datahike connect config requires the same third-party key.
- Proximum currently has its own storage config key in the embedding adapter.
- Vendored source/tests naturally retain upstream terminology.

The rule is that no Seon public API, domain fact, path, log, UI, or semantic
protocol exposes store as a synonym for database. Literal third-party keys stay
inside the smallest adapter function and are treated as opaque everywhere
else.

## Replica behavior that must survive the move

- Open publish before replay so no gap exists between the two mechanisms.
- Freeze a fixed replay upper watermark and page only through it.
- Preserve strict `basis-t-before -> basis-t` continuity.
- Preserve negative transaction values in retraction datoms.
- Preserve transaction metadata and request ID on replayed events.
- De-duplicate feed/replay overlap exactly once.
- Buffer live events with a hard cap; reconnect rather than grow without bound.
- Bound pending response/feed correlations.
- Accept both feed-before-response and response-before-feed order.
- Retry a frozen transaction request after reply loss.
- Return local commit status unknown after exhausted replies.
- On invalid replay data, close and restart recovery; never silently skip.
- Drain accepted writer promises, feed timers/sockets, and the Datahike
  connection on disconnect.
- Allow clean stop and re-attach without retaining stale correlations.

The custom writer's use of core.async is valid at the Datahike `PWriter`
boundary because that is the library contract. It does not justify introducing
core.async into the rest of the CLJS pod, which remains native Promise/
`^:async` code.

## Runtime resource lifecycle

The current server state does not provide one complete, ordered stop operation.
The extracted resources should make shutdown explicit:

1. stop accepting new request and publish connections;
2. close accepted request channels and current subscribers;
3. stop/unregister Datahike transaction listeners;
4. close the supervisor REPL;
5. release every registered Datahike connection;
6. unlink both UDS paths; and
7. let the process exit only after bounded cleanup.

Install the same operation in the JVM shutdown hook. Startup should unlink only
stale socket files after proving no managed process is alive; operational
coordination remains `bin/seon`'s responsibility.

The CLJS attachment stop operation should:

1. reject/drain outstanding RPC and writer correlations with an explicit local
   status;
2. cancel reconnect timers;
3. close publish and request sockets;
4. release the Datahike connection; and
5. clear only opaque runtime resources.

Neither side should persist “connected,” “caught up,” or “subscriber” facts in
the database; those are current resource state, not durable domain facts.

## Test audit and target suite

### Keep and rename as behavioral tests

- Generated-ID allocation is atomic, collision-safe, and recoverable.
- Same request ID plus same request recovers exactly once.
- Same request ID plus a different logical transaction returns a typed conflict
  and adds no datoms.
- Caller tempids and generated IDs resolve identically on first response and
  receipt recovery.
- Native Transit values survive a transaction/event round trip.
- Transactions route to the required named database and never another
  connection.
- KNN routes to the required named database when enabled.
- Feed publication contains exact database identity, basis chain, datoms,
  metadata, and request ID.
- Replay pagination uses a fixed watermark, continuation, retractions,
  metadata, and request ID.
- Registry ensure is idempotent, initialization happens once, initialization
  failure releases the connection, and release/delete are idempotent.
- Backend descriptor produces deterministic identity and the canonical `/db`
  path without leaking a public `:store` shape.
- UDS handles fragmented frames, multiple frames, native Transit values,
  over-limit input/output, fan-out, disconnect, and clean stop.
- Replica tests cover response/feed ordering, frozen retries, bounded ping,
  bounded buffers, overlap de-duplication, reconnect, unknown commit, and
  stop/re-attach.
- Protocol constructors and schemas run under both CLJ and CLJS.

### Delete or convert

| Current test area | Action |
| --- | --- |
| `facts_test` | Delete obsolete proof-of-concept remote query suite. |
| `overlay_semantics_test` | Delete generic remote reads; retain only a real transaction/event invariant if unique. |
| `protocol_extensions_test` | Delete `q`/`pull`/`schema` protocol assertions. |
| `temporal_read_test` | Convert to local Datahike `as-of` behavior or the later typed coordinate API. |
| `wire_props_test` | Retain only transport/protocol properties that survive; delete generated remote-read operations. |
| `protocol_integration_test` | Split semantic writer behavior from UDS delivery; delete remote-read assertions. |
| `wire_types_test` | Split into protocol and Transit framing tests. |
| `seon.server.client` smoke usage | Move a minimal socket fixture under test or delete it. |
| `session_with_agent_test` | Archive with paused JVM session registry. |

Rename `test/seon/store/wire_test.cljs` to
`test/seon/db/replica_test.cljs`. Do not rewrite its proven state-machine
scenarios into shallow constructor tests.

The focused runner should list only the new protocol, backend, registry, writer,
JVM UDS transport, and optional embedding tests. CLJS replica tests stay in the
targeted CLJS runner. A one-line protocol constructor change must not require
the retired gym, paused JVM application suite, browser suite, or all-project
tests.

Use injected zero-delay retry policies or a fake clock in unit tests; do not
make test processes sleep through production 500 ms backoff. Integration tests
may use real sockets but should use isolated temporary database directories and
bounded deadlines.

## Exact cut order

The repository can stage these internally in the following order, but the old
and new runtime halves must land in one compatible commit/deploy boundary.

1. Stop the pod and writer. Record a known baseline and accept that the test DB
   will be reset.
2. Add `seon.db.protocol.cljc` with final typed operation/request/response/event
   schemas, constructors, receipt schema, and CLJ+CLJS tests. Do not add a
   decoder for old strings or old keys.
3. Add `seon.db.backend.clj` and `seon.db.registry.clj`; make the registry treat
   the Datahike config as opaque. Fix database-name type, identity, canonical
   path, and lifecycle tests.
4. Extract semantic handlers into `seon.db.writer.clj`. Classify request scope
   before connection resolution and delete `q`, `pull`, `schema`, `list-dbs`,
   and `remove-db` handlers.
5. Extract JVM framing/request/publish resources to
   `seon.db.transport.uds.clj`. Add explicit resource stop and matching frame
   limits.
6. Extract Node framing/RPC/publish bytes to
   `seon.db.transport.uds.cljs`. Delete all semantic wrappers, standalone main,
   and the Shadow `wire-node` build.
7. Move the peer state machine to `seon.db.replica.cljs`. Consume the database
   ID returned by ensure; delete the duplicate CLJS ID algorithm. Preserve all
   correlation/replay behavior.
8. In one compilation pass, update `seon.client`, `seon.embed`, `seon.db.id`,
   `seon.eval`, DB internals, context dependency exclusions, debug, and boot.
   Make KNN database routing explicit.
9. Rename managed paths to `/db` in backend, launch/reset/fork/destroy commands,
   ACME wrapper, help, focused tests, and active runbooks. Delete old test data.
10. Delete all old namespaces and obsolete tests. Do not leave forwarding vars
    or aliases.
11. Build the JVM writer and CLJS pod, run focused suites, cold-reset the default
    cluster, and collect live proofs.
12. Only after the default cluster passes, apply the same reset/start proof to
    ACME.

### Required zero-reference gate

Before live testing, active source/tests/bin/config/current architecture docs
must have zero references to:

```text
seon.store.wire
seon.store.internal.wire-node
seon.server.wire
seon.server.codec
seon.server.broadcast
seon.server.store
seon.server.registry
:seon.store.wire/
"ensure-db"
"replay-tx"
"list-dbs"
"remove-db"
proc:wire
/store
```

The `/store` grep needs reviewed exceptions for vendored/archived evidence and
literal third-party config, not a blind global replacement.

## Current draft hazards to resolve before landing

At audit time, uncommitted target namespace drafts were present in the shared
tree. They are useful progress, but the following contradictions must not land:

- Protocol database name is currently a string while backend/registry use a
  keyword. Pick keyword once.
- Protocol responses still use a boolean success field plus optional string
  error instead of the required typed response multi-schema/status.
- `committed`, `unknown`, and `feed-behind` currently appear as protocol status
  values; they belong to local replica commit proof.
- `ensure-database` does not yet guarantee the authoritative database ID in its
  success schema.
- Receipt declarations exist as raw Datahike maps alongside Malli definitions;
  derive them through the canonical bridge.
- The CLJS UDS draft still contains copied old semantic wrappers, string ops,
  old keys, remote q/pull/schema, and a standalone main. Strip it to bytes and
  sockets.
- Registry resolution still documents optional database routing and ambient
  fallback. Scoped operations must require exact routing.
- Registry schemas/implementation have had `released?`/`removed?` drift in the
  shared draft; make the name and shape exact.
- Registry entries should carry backend descriptor facts, including database
  ID, rather than reconstructing or introspecting third-party config.
- The backend must use the settled `data/clusters/<name>/db` layout everywhere;
  no `data/databases`, `data/sessions`, or root-relative bare database.
- Some registry fork/delete surfaces still mention the old temporary names and
  REPL port in docstrings. Update exact current behavior, without pretending
  the future typed supervisor API already exists.

Because multiple agents share the tree, these are audit findings, not a request
to replace another agent's draft with a parallel implementation.

## Risks and mitigations

| Risk | Mitigation/proof |
| --- | --- |
| A feed event arrives before the transact response | Retain explicit two-sided correlation tests. |
| A retry duplicates a committed write | Persist one receipt in the same transaction and resend an identical request. |
| A lost response is misreported as no commit | Return replica commit status unknown after bounded retries; query receipt on retry. |
| A missed event leaves a peer behind | Live-before-replay, fixed watermark, strict basis chain, reconnect on gap. |
| One DB receives another DB's write/KNN request | Required keyword database name and exact lookup; no ambient fallback. |
| JVM/CLJS derive different database IDs | Writer returns the sole authoritative ID from ensure. |
| Shared-file reads observe partial state | Preserve Datahike write ordering, atomic root replacement, and non-streaming writer semantics. |
| UDS allocates an attacker/bug-sized frame | Enforce the same limit before allocation on both runtimes. |
| Shutdown leaks subscribers/sockets/conns | Explicit aggregate resource stop with bounded lifecycle tests. |
| Old data accidentally appears supported | No old path/key/op fallback; reset test clusters. |
| A generic registry becomes new semantic authority | Keep only opaque connections/resources; query semantic membership from DB. |
| Fork copies inconsistent external state | Keep fork explicit, verify whole Datahike history, and separately design secondary-index snapshotting. |

## Verification checklist

### Static

- [ ] Every public request/response/event map key is fully namespaced.
- [ ] Every operation/status/error/event value is a fully namespaced keyword.
- [ ] Database name has one keyword type from launch parsing through protocol,
      registry, response, event, and replica.
- [ ] `ensure-database` returns the writer-authoritative database ID.
- [ ] No duplicate CLJS UUID derivation remains.
- [ ] No database-scoped operation accepts an absent database name.
- [ ] Literal third-party `:store` occurs only in adapter functions or vendored
      evidence.
- [ ] Old namespaces, keys, op strings, standalone wire build, and `/store`
      managed paths pass the zero-reference gate.
- [ ] No agent/session/roster registry was moved into `seon.db.registry`.

### Focused behavior

- [ ] Protocol CLJ and CLJS schema/constructor tests pass.
- [ ] Backend/registry/writer/UDS focused JVM tests pass.
- [ ] Replica focused CLJS tests pass.
- [ ] Duplicate request, conflict, generated-ID, tempid, and recovery cases pass.
- [ ] Feed-before-response and response-before-feed both commit exactly once.
- [ ] Replay gap, overlap, retraction, invalid page, and reconnect cases pass.
- [ ] Oversize and fragmented frame tests pass on JVM and Node.
- [ ] Stop/restart leaves no socket, subscriber, pending promise, timer, or
      unreleased connection.

### Cold live proof

- [ ] A clean `bin/seon` reset rebuilds writer and pod and creates only
      `data/clusters/default/db`.
- [ ] Writer readiness, ensure, and replica catch-up are visible without legacy
      warnings or fallback.
- [ ] A transaction sent through `seon.db/transact!` returns success, is read
      locally from the named database, and appears once on the live UI.
- [ ] Kill the reply path after commit, retry the same frozen request, and prove
      receipt recovery without duplicate datoms.
- [ ] Interrupt/restart the pod while the writer remains up and prove replay
      reaches the current basis.
- [ ] Run two named test databases and prove writes and optional KNN results do
      not cross-route.
- [ ] Stop/restart the complete cluster and prove no stale UDS files or extra
      processes remain.
- [ ] Run the basic inspect-ai smoke only after focused infrastructure proof.
- [ ] Reset and prove ACME only after default-cluster success.

## Definition of done

There is one portable semantic DB protocol, one authoritative writer, one UDS
adapter per runtime, one local replica state machine, one backend adapter, and
one live connection registry. The pod pays for local reads and forwards only
the five operations it genuinely needs. All managed paths and Seon vocabulary
say database/DB. Old keys, ops, namespaces, wrappers, builds, and tests are
gone. A cold reset proves exact routing, idempotent writes, reactive catch-up,
bounded recovery, and clean lifecycle with no compatibility path hiding stale
code.
