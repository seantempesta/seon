---
type: research
status: complete
tags: [research, prd, database, flow, agent]
---

# Atomic replica and publisher deletion audit — 2026-07-16

## Result

The buildable deletion seam is now smaller than the older inventories imply.
The persistent request session already carries responses, addressed Datom
events, resynchronization events, deadlines, cancellation, and bounded native
backpressure. Datahike's committed-report source already feeds those interests
inside the JVM. The publisher socket, transaction event, transaction-history
replay, local Datahike connection, and own-write correlation exist only to keep
the superseded Bun-side replica coherent.

Delete that whole path together after the last production consumer moves to
the async `seon.db` session. Do not retain a replay client, remote Datahike
writer, local connection, or request-per-socket adapter as a transition. The
one surviving launch value is `seon.launch/process-launch-descriptor`; the one
surviving database liveness fact is an acquired, connected `seon.db` session.
Restore readiness adds its exact coordinate and completion evidence to that
fact; it does not restore replica feed status.

This removes, per authority deployment, one publisher accept thread. It also
removes one publisher writer thread, queue, second Unix socket, reconnect
timer, replay buffer, and own-write correlation entry set per attached Bun
process. More importantly, one commit is inspected and routed once through the
existing selective-interest path instead of being encoded as a global
transaction event and decoded/applied independently by every replica.

## Dependency ledger

| Owner | Selected source | Fact used by this audit |
|---|---|---|
| Bun | `reference-code/bun` at `be77b652884b16a103cfaa4af3c1102f72f2dcd3` | `Bun.connect({unix, socket})` is the native Unix client. `Socket.write` returns the accepted byte count and may accept a prefix; `drain` resumes output. The existing session handles both facts directly. |
| Shadow | `reference-code/shadow-cljs` at `4e72595f57618f5c43388ad13d5136cd3bede566` | `node-script` produces the CommonJS server-JavaScript artifact and sets `cljs.core/*target*` to `"nodejs"`; it does not select the executable. Only `node-test` autorun literally launches `node`, and Seon's runners do not use that autorun. No Shadow fork is needed for this deletion. |
| Bun session | `src/seon/db/transport/uds.cljs` | One `Bun.connect` session multiplexes bounded requests and addressed Datom/resynchronization events. Its event queue retains at most one event per interest and turns coalescing into explicit resynchronization. |
| JVM session server | `src/seon/db/transport/uds.clj` | One selector accepts all request sessions and exposes bounded `send!` on the physical connection. The later publisher implementation in the same file is independent legacy code. |
| Interest owner | `src/seon/db/writer.clj` | `install-interest-locked!` opens Datahike's committed-report source; `deliver-report!` routes matching Datoms; `send-interest-event!` writes through the requesting session; physical close removes its acquisitions and interests. |
| Current replica | `src/seon/db/replica.cljs` | `RemoteWriter`, publication feed, replay, local materialization, synthetic listeners, reconnect, and own-write correlation are one closed obsolete mechanism. |
| Current facade and bootstrap | `src/seon/db.cljs`, `src/seon/client.cljs`, `src/seon/launch.cljc` | Uncommitted work has moved the launch descriptor and begun the direct session facade, but local transaction, temporal, capture/replay, bootstrap, and lifecycle assumptions remain reachable. |

## Shortest falsifier

The publisher is redundant only if a commit can wake an interested Bun owner
without it. Current source proves the complete path:

1. `install-interest-locked!` in `src/seon/db/writer.clj` opens one
   generation-fenced Datahike committed-report source per active database
   scope.
2. `run-readiness!` submits a ready source to bounded `:delivery` capacity.
3. `deliver-report!` selects interests by attribute, filters ordinary Datoms,
   and calls `send-interest-event!`.
4. `send-interest-event!` calls the physical request connection's bounded
   `send!`; no publisher is involved.
5. `src/seon/db/transport/uds.cljs` accepts only addressed Datom and
   resynchronization events and dispatches them by the existing request ID.

Conversely, `::publisher` is used only by `transaction-listener`, which builds
the legacy global transaction event. Transaction-history replay reconstructs
that same legacy event shape. Neither operation participates in selective
interest delivery. Therefore deleting the publisher does not weaken the new
resilience contract: session pressure closes that session, interest cleanup is
physical-connection-owned, and reconnect performs a new listen plus a
coordinate-pinned read at the listen acknowledgement.

## Exact responsibility transfer

| Removed responsibility | Final owner |
|---|---|
| Decode and validate `SEON_LAUNCH_DESCRIPTOR`; derive the development fallback | `src/seon/launch.cljc`, once, as `process-launch-descriptor` |
| Ensure and acquire the selected database | `seon.db/open-session!` over the persistent request session |
| Process database liveness | `seon.db/attached?`: one connected session with a completed acquisition |
| Current immutable database point | Explicit operation coordinate; otherwise one `resolve-head` request at the async outer acquisition boundary |
| Read-your-own-write | Successful transaction response coordinate followed by explicitly coordinate-pinned reads; no local `ryow-deref!` |
| Ambiguous transaction delivery | The existing request ID, frozen request bytes, and durable transaction receipt in the authority |
| Database change wakeup | Query/Datom interest on the requesting session, backed by Datahike committed-report readiness |
| Missed/coalesced change | Addressed resynchronization event followed by one coordinate-pinned read; no transaction replay |
| Schema installation and Datahike value conversion | JVM authority using `src/seon/db/datahike/schema.clj`; Bun sends namespaced schema/transaction data |
| Query, pull, index, temporal, and KNN execution | JVM authority over one retained immutable Datahike value |
| Bun-facing entity convenience | Eager ordinary map from one remote wildcard pull; no `datahike.impl.entity/Entity` |
| Session shutdown | `seon.db/close-session!` after agent/web interests drain; physical close releases acquisitions and interests |
| Restore readiness | Client bootstrap proves expected coordinate, completion facts, required schema, and required interest acknowledgement, then the web readiness route exposes that ordinary evidence |

The launch descriptor retains the database route, backend/path, request socket,
runtime artifact, process paths, blob view, and optional restore evidence. It
does not retain a publish socket or replica progress. Keep the existing
`::launch/request-socket-path` name for this cut; renaming a working protocol
route to a new synonym adds migration surface without throughput or resilience.

## Production source closure before deletion

These are the remaining direct blockers in the current shared checkout. Their
replacement is part of the consumer migration, not the deletion commit.

| Path | Remaining obsolete reachability | Required replacement |
|---|---|---|
| `src/seon/client.cljs` | Direct `datahike.api` and `konserve.node-filestore`; `replica/ping!`, `ensure-database!`, `database-config`, `attach!`, `status`, and `detach!`; local `conn` and `db/*conn*` lifecycle; local diagnostic DB construction | Open one session from `seon.launch/process-launch-descriptor`; perform schema/provenance writes through remote `transact!`; acquire bootstrap data in coarse coordinate-pinned reads; drain interests then close the session. Move isolated database tests out of the production client graph. |
| `src/seon/db.cljs` | Local Datahike imports and connection shape; local transaction body; query guards over DB values; lazy entity and temporal DB values; index traversal; capture/replay; release-connection | Finish the async session surface, especially transaction, execute-many/index, strict temporal reads, evidence capture, and error classification. Delete DB/connection arities rather than emulate them remotely. |
| `src/seon/db/internal.cljs` | Datahike DB/entity recognition, connection resolution, local read normalization/replay, schema installation, raw report conversion, native listener inputs, and local commit | Retain only pure namespaced transaction validation/transformation that belongs in Bun. Use the existing JVM schema bridge and authority transaction result for host-specific work. |
| `src/seon/embed.cljs` | Direct Datahike value access and `replica/knn-search!` | Send the already-settled coordinate-pinned KNN request through `seon.db`/the session; embeddings remain asynchronous derived work. |
| `src/seon/handlers/ns.cljs` | Direct `datahike.api/q` | Replace with the relevant async coarse namespace/program-graph read. |
| `src/seon/repl.cljs` | A private in-memory Datahike connection in a production-reachable namespace | Keep only evaluation/history process state; database reads and writes use the selected live session. Test-only database fixtures belong in tests, not this owner. |
| `src/seon/web/serve.cljs` | Readiness depends on `replica/status` and a caught-up feed | Depend on client-owned session/bootstrap readiness. The route interest acknowledgement is the exact reactive-read fence. |
| `src/seon/dev/runtime_id.cljc`, `script/seon/dev/mcp.clj`, `src/seon/config.cljs`, `src/seon/log.cljs`, `shadow-cljs.edn` | Replica/Datahike/Node-era explanatory text and log filtering | Update after reachability closes; do not preserve these comments as architecture. |

The full async consumer inventory remains in
[[exhaustive-read-consumer-and-deletion-inventory-2026-07-15]]. The atomic cut
must not begin merely because the files above compile: final reachability must
also find no production caller of `db/*conn*`, local DB-value arities,
`entity-lazy`, `at-coordinate`, capture/replay, or raw transaction reports.

## Atomic deletion set

Once the production closure above is empty, make one path-limited source cut.
The following edits are coupled because separating them either leaves a dead
second server or makes a still-reachable replica uncompilable.

### Bun replica and facade remnants

- Delete `src/seon/db/replica.cljs` in full.
- In `src/seon/db.cljs`, delete local Datahike/connection imports and all
  DB-value, connection, raw-report, local temporal/index, lazy entity,
  connection release, captured-read replay, and native listener code.
- In `src/seon/db/internal.cljs`, delete the Datahike imports and the host-value
  recognition, connection, local schema-install/commit, report, listener, and
  replay sections. Retain pure Malli and namespaced transaction-data policy
  only when still called by the remote transaction face.
- Remove the CLJS Datahike branches that remain reachable through
  `src/seon/db/id.cljc` and `src/seon/db/coordinate.cljc`; keep their pure
  candidate/coordinate data and the CLJ authority branches.
- Remove the replica import and every replica call from `src/seon/client.cljs`,
  `src/seon/embed.cljs`, and `src/seon/web/serve.cljs`.

### Publisher, replay, and global transaction event

- In `src/seon/db/protocol.cljc`, delete
  `replay-transactions-operation`, `transaction-event`, `feed-behind-status`,
  their allowed-operation/event/status entries, replay request/response
  schemas and constructor, and global transaction-event schemas. Increment the
  protocol version because this intentionally removes wire shapes.
- In `src/seon/db/writer.clj`, delete `::publisher` and
  `::publish-socket-path` from runtime/start/server schemas; delete
  `transaction-event-from-data`, `transaction-listener`, and the Datahike
  `::transaction-publication` listener; delete the entire
  transaction-history-replay section and replay handler/dispatch; remove
  publisher start/close from writer lifecycle. Keep committed-report interests
  and addressed Datom/resynchronization delivery unchanged.
- In `src/seon/db/transport/uds.clj`, delete only the publisher/subscriber
  schemas, queue/thread implementation, `start-publisher!`, `publish!`, and
  `close-publisher!`. Keep the selector request server and its physical-session
  `send!` path.
- In `src/seon/db/server.clj`, delete publish-socket schema/default/argument,
  `--pub-sock` parsing and logging, and the writer start argument. The terminal
  result file publisher is unrelated lifecycle durability and remains.

### Launch and operator

- In `src/seon/launch.cljc`, delete `::publish-socket-path` from
  `::writer-owner`, default-descriptor input/construction, and the CLJS fallback.
  Keep `process-launch-descriptor` here; do not move it back into a database
  implementation namespace.
- In `script/seon/dev/config.clj`, delete
  `:seon.dev.config/publish-socket`, `SEON_PUB_SOCK`, `pub-sock`, and the launch
  field.
- In `script/seon/dev/process.clj`, delete the pod environment publication of
  `SEON_PUB_SOCK`, writer `--pub-sock` arguments, publish-socket readiness-path
  cleanup, and external dependency comparisons. Writer readiness continues to
  probe the request socket; pod readiness continues through its HTTP endpoint.
- Update literal launch fixtures in restore/branch/operator tests; restore
  descriptors still carry exact database coordinates and blob views.

## Test deletion and replacement

Delete tests whose subject disappears:

- delete `test/seon/db/replica_test.cljs`;
- delete `test/seon/db/replay_test.clj`;
- delete `test/seon/db/read_observer_test.cljs` once remote query evidence and
  async render acquisition tests cover the surviving behavior; and
- delete only the publisher-specific cases and helpers from
  `test/seon/db/transport_uds_test.clj`, retaining selector framing,
  backpressure, bounds, connection cleanup, and shutdown tests.

Rewrite, rather than delete, tests whose application contract survives:

- `test/seon/db_session_test.cljs` becomes the direct facade/session owner and
  covers acquisition, explicit/ambient/head coordinates, transaction response,
  interest acknowledgement, resynchronization, close, and ordinary errors;
- `test/seon/client_runtime_test.cljs` replaces local connection/feed effects
  with one session acquisition, bootstrap proof, interest acknowledgement, and
  session drain/close ordering;
- `test/seon/web/serve_test.cljs` proves readiness closes on session loss and
  restore readiness requires exact ordinary evidence, with no replica status;
- `test/seon/embed_test.cljs` stubs the coordinate-pinned session KNN operation;
- `test/seon/launch_test.cljs`, `test/seon/db/restore_admin_test.clj`, and
  `test/seon/dev/restore_test.clj` remove the publish-socket fixture field;
- `test/seon/dev/process_test.clj` removes `SEON_PUB_SOCK`, `--pub-sock`, and
  publish-path assertions while retaining process ordering and request-socket
  readiness;
- `test/seon/db/server_test.clj` stops passing `--pub-sock` but retains terminal
  result publication, REPL port, startup, and managed shutdown tests; and
- writer integration, interest, admission, generated-ID, receipt, and
  transaction-coordinate tests remove the unused publisher runtime/start
  fixture. Their semantic assertions remain because those authority behaviors
  survive.

Do not keep a CLJS Datahike fixture solely to avoid rewriting a consumer test.
Core acquisition tests should stub or exercise ordinary protocol responses;
Datahike semantics remain tested under `bin/test-writer` against the real JVM
dependency.

## Compile dependency removal

After the last production and CLJS-test Datahike require is gone, remove from
the `:cljs` alias in `deps.edn`:

- `org.replikativ/datahike`;
- `org.replikativ/persistent-sorted-set`;
- `org.clojure/core.async` when the replica namespace/test is deleted;
- the CLJS overrides for Datahike, Konserve, superv.async, and partial-cps.

The `:writer` alias retains the maintained Datahike and Konserve forks,
Proximum, Transit CLJ, and JVM flags. Transit CLJS remains because it is the
wire codec. Malli, SCI, reitit, Shadow, and the self-host compiler remain.

Remove `konserve.node-filestore` from `src/seon/client.cljs`. Re-check the
`externs/node_fs.js` uses in `shadow-cljs.edn` after the Bun-native file/web
cut; it was originally added for Datahike/environ, but other currently
reachable server-JavaScript file interop may still need property externs.
Deleting it based only on its old comment is not a safe part of this cut.

The Shadow targets remain `:node-script` and `:node-test`: vendored source
shows they describe artifact shape and compiler target, not the production
executable. Update stale comments and `package.json`'s `client:run` separately
to say/run Bun, but do not invent a `:bun-script` target.

## Smallest buildable order without adapters

1. **Finish the one facade.** Complete direct remote transaction, strict
   temporal/index/execute-many reads, KNN, interest, and close semantics in
   `seon.db`; prove them with `test/seon/db_session_test.cljs`. No application
   code imports the transport directly.
2. **Move bootstrap and readiness.** Make client startup open the session,
   prepare the database through remote writes, acquire bootstrap data, install
   required interests, and only then expose readiness. Make drain close the
   same session after its owners. The descriptor already belongs in
   `seon.launch`.
3. **Migrate every async consumer.** Remove `db/*conn*` and DB-value flow from
   agent, context, eval, render, web, authored toolkit, embedding, and REPL
   owners. Gather ordinary inputs at one explicit coordinate, then compute
   purely where possible.
4. **Run the reachability falsifier.** Production source must have no reference
   to `seon.db.replica`, `datahike.*`, `konserve.*`, publisher/replay protocol,
   `db/*conn*`, local DB-value arities, or raw report/capture-replay APIs except
   CLJ authority code and deliberate test fixtures awaiting the same cut.
5. **Make the atomic deletion commit.** Apply every source, protocol, writer,
   transport, server, launch/operator, dependency, and test deletion listed
   above together. This is the first commit that removes the publisher API;
   therefore no still-reachable replica can fail halfway through the history.
6. **Prove the smaller graph.** Compile client/acme/bench and tests under Bun;
   run focused session, client lifecycle, route/render, interest, writer,
   server, and operator gates; inspect the dependency tree; then run the broad
   CLJS and writer suites. Live lifecycle proof remains a later coordinated
   source-freeze checkpoint, not part of this audit.

The earliest unsettled contract remains the async facade and its consumers,
not publisher deletion. Once that contract is closed, the deletion itself has
no remaining design choice: one session and Datahike committed-report interests
replace the entire replica/feed/replay mechanism.
