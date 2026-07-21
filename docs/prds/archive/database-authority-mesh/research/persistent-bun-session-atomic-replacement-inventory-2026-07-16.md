---
type: research
status: complete
tags: [research, prd, database, flow, web]
---

# Persistent Bun session atomic replacement inventory — 2026-07-16

## Result

The best seam remains one persistent `Bun.connect` Unix socket per Bun process
and one Java NIO selector for the whole database authority. Source at
`d684c0f8` strengthens this decision: `seon.db.executor` now owns one bounded,
fair capacity across read, KNN, encode, provider, mutation, and HNSW work. The
selector must hand canonical requests to that dispatcher; it must not add a
transport executor, a thread per connection, or a second fairness policy.

The replacement changes reachability atomically, not necessarily authorship in
one enormous edit. Remote-read consumers and completion-driven writer handling
can be prepared behind the existing `seon.db` and `handle-request!` semantic
owners while tests call them directly. The first commit that opens the
persistent session must also remove request-per-socket RPC, the publisher,
transaction replay, the local Datahike replica, and both Node socket imports.
There is never a runtime mode in which old and new transports coexist.

The native web replacement is adjacent but independent: `Bun.serve` and its
direct `ReadableStream` should replace Node HTTP, zlib, raw response objects,
and the hijack sentinel in one web-host cut. It reuses the same database
session, but browser Datastar SSE remains in the Bun web owner and never becomes
a database-session broadcast.

## Reconciled dependency ledger

| Owner | Selected source | Replacement seam |
|---|---|---|
| Bun socket | Bun `be77b652884b16a103cfaa4af3c1102f72f2dcd3`, `packages/bun-types/bun.d.ts:5785-5811,6305-6357,6459-6493` | `Bun.connect`, `uint8array`, actual partial-write count, `drain`, idempotent close callbacks |
| JVM socket | OpenJDK 26 `ServerSocketChannel`, `SocketChannel`, `Selector`, `SelectionKey` | one non-blocking Unix selector; worker completions enqueue a command then call `wakeup` |
| Authority work | `src/seon/db/executor.clj` at `d684c0f8` | the sole capacity and class/database fairness owner; selector admits but never executes database work |
| Semantic dispatch | `src/seon/db/writer.clj:1465-1647` | replace synchronous `handle-request` with completion-driven `handle-request!` in place |
| Current JVM transport | `src/seon/db/transport/uds.clj` | replace blocking streams, connection threads, publisher workers, and subscriber queues with one selector owner |
| Current Bun transport | `src/seon/db/transport/uds.cljs` | replace `node:net`, `node:buffer`, request sockets, timers, `Buffer.concat`, and publisher connection with one native session |
| Current replica | `src/seon/db/replica.cljs` | delete after its surviving descriptor, attachment, receipt, KNN, and health responsibilities move to existing owners |
| Process launch | `src/seon/launch.cljc`, `script/seon/dev/config.clj`, `script/seon/dev/process.clj` | one authority socket path and one `SEON_DB_SOCK`; execute the Shadow CommonJS artifact with the selected Bun runtime |
| Web host | `src/seon/web/serve.cljs`, `router.cljs`, `datastar.cljs`, `debug.cljs` | `Bun.serve`, standard `Request`/`Response`, direct SSE stream, `Bun.file`; preserve shared render units and latest-state-wins |

The exact source proofs and transport measurements remain in
[[research/selector-session-source-proof-2026-07-16]],
[[research/direct-bun-jvm-session-replacement-contract-2026-07-16]], and
[[research/bun-serve-datastar-internals-2026-07-16]]. The consumer migration is
enumerated in
[[research/exhaustive-read-consumer-and-deletion-inventory-2026-07-15]].

## Exact database-session patch inventory

### Rewrite in place

- `src/seon/db/transport/uds.clj`
  - Preserve `encode`, `decode`, `start-request-server!`, and
    `close-request-server!` as public lifecycle concepts.
  - Replace `DataInputStream`, `DataOutputStream`, blocking channels, the accept
    thread, and one `database-request-connection` thread per socket with one
    non-blocking selector thread.
  - Give each channel fixed four-byte header and exact-size payload buffers,
    ordered decode/admission state, bounded incomplete input, bounded in-flight
    request count, a FIFO of response buffers with their current positions, and
    session attachment ownership.
  - Worker completion adds one immutable framed response to a bounded selector
    command queue and calls `Selector.wakeup`. Only the selector changes
    `OP_WRITE`; zero writes wait for readiness and partial writes resume the
    current `ByteBuffer` position.
  - Register request ID to its exact live session before dispatcher admission.
    Reject an active duplicate globally. Compare-remove the same owner on
    completion or close.
- `src/seon/db/transport/uds.cljs`
  - Preserve Transit and four-byte framing, but replace the namespace body with
    one process-local `Bun.connect({unix, data, socket})` session.
  - Remove `node:net`, `node:buffer`, `.createConnection`, per-request timers,
    and every `Buffer.concat` accumulator.
  - Retain one outgoing frame plus accepted-byte offset, and resume only its
    exact suffix from `drain`. A negative/fatal native write closes once.
  - Retain one pending map keyed by the existing protocol request ID. Resolve
    completion-order responses by ID and settle all pending Promises once on
    `error`, `end`, `close`, or `connectError`.
  - A request deadline sends the existing cancel operation when connected; it
    does not destroy a healthy shared socket merely because one request timed
    out.
- `src/seon/db/writer.clj`
  - Change `handle-request` to `handle-request! runtime request complete!` and
    retain the single operation dispatch. Immediate operations may call
    `complete!` before returning; dispatcher work calls it when complete.
  - Submit mutation, read, KNN, provider, encode, and HNSW work only through the
    authority-wide executor introduced by `d684c0f8`.
  - Remove publisher from runtime/server schemas, transaction listener fanout,
    replay page generation, replay dispatch, publisher startup, and publisher
    close.
  - On dead-session completion, skip encoding a response. Query and
    `execute-many` cancellation detaches that request ID; an accepted mutation
    finishes and remains recoverable by its durable receipt.
- `src/seon/db/server.clj`
  - Replace `--req-sock` plus `--pub-sock` with one `--db-sock`; start and log
    one session endpoint.
- `src/seon/launch.cljc`
  - Replace `::request-socket-path` and `::publish-socket-path` with one
    `::database-socket-path` in writer-owner and descriptor request schemas.
    This is launch data, not a new wire identity.
- `script/seon/dev/config.clj`, `script/seon/dev/process.clj`
  - Derive, prepare, probe, and clean one socket. Pass `SEON_DB_SOCK` to every
    Bun pod and `--db-sock` to the JVM. Writer readiness connects to that one
    socket or uses the writer REPL port; no second readiness file exists.
  - Keep the one `SEON_JS_RUNTIME` selection and make Bun the chosen executable
    for pod, focused/full CLJS tests, changed-test runner, validator worker, and
    package proof. Shadow remains `:node-script`/`:node-test`; no Shadow fork or
    Bun-specific build target is needed.
- `script/seon/dev/branch.clj`, `script/seon/dev/restore_state.clj`
  - Consume the single descriptor field. Administrative database requests use
    the same framing and bounded session implementation; they do not preserve a
    private blocking socket implementation.
- `src/seon/db.cljs`, `src/seon/client.cljs`, `src/seon/embed.cljs`
  - Keep `seon.db` as the only application database API. Move attachment,
    coordinate, transaction, receipt recovery, query/pull/pull-many,
    `execute-many`, and KNN calls onto the persistent session.
  - Make remote operations honestly asynchronous. Replace local Entity,
    temporal wrapper, and ambient database-value traversal with bounded
    ordinary results pinned to the session attachment and coordinate.
  - Move launch descriptor decoding to `seon.launch`, session health to the
    transport owner, database attachment state to `seon.db`, and blob storage
    view access to the existing client launch descriptor. Do not create a
    renamed replica namespace.
- `src/seon/web/datastar.cljs`, `src/seon/web/router.cljs`,
  `src/seon/agent/loop.cljs`
  - Replace local Datahike listeners and captured-read replay with one selective
    authority interest per database and coordinate-pinned grouped reads.
  - Preserve one candidate read plan and one render/serialization for every
    shared view unit; browser count must not multiply authority work.

### Delete in the same reachability-changing commit

- Delete `src/seon/db/replica.cljs` in full, including `RemoteWriter`, local
  Datahike connection/index/cache construction, read-your-own-write deref,
  own-write correlations, publisher replay/buffering, reconnect timers, and
  synthetic native listener calls.
- Delete `test/seon/db/replica_test.cljs` in full.
- Delete `test/seon/db/replay_test.clj`; database transaction replay has no
  consumer after replica removal. Program-graph replay and resume replay are
  unrelated and remain.
- Delete the publisher half of `src/seon/db/transport/uds.clj`:
  `start-subscriber!`, `start-publisher!`, `publish!`, `close-publisher!`, its
  queue/thread schemas, and `ArrayBlockingQueue` imports.
- Delete the old request-per-socket and publisher-client implementation from
  `src/seon/db/transport/uds.cljs`; the file itself survives as the native Bun
  session owner.
- Delete replay request/response schemas and constructors from
  `src/seon/db/protocol.cljc` only after no administrative caller uses them.
  Preserve durable request receipts and transaction-coordinate resolution.
- Delete `SEON_REQ_SOCK`, `SEON_PUB_SOCK`, `--req-sock`, `--pub-sock`, both old
  descriptor fields, publish readiness cleanup, replica status/readiness,
  replay-page limits, feed reconnect limits, and compatibility flags from
  source, tests, manifests, and documentation.

### Files whose tests migrate rather than disappear

- `test/seon/db/transport_uds_test.clj`: replace blocking RPC and publisher
  cases with selector framing, multiplexing, pressure, ordering, and cleanup.
- `test/seon/db/writer_integration_test.clj`,
  `request_receipt_test.clj`, `generated_id_transaction_test.clj`, and
  `transaction_coordinate_test.clj`: retain semantic assertions, but drive
  completion callbacks and persistent sessions; remove publisher assertions.
- `test/seon/db/server_test.clj`, `test/seon/dev/process_test.clj`,
  `branch_test.clj`, `restore_test.clj`, and database restore tests: assert one
  socket field, one readiness path, and the same bounded framing.
- `test/seon/client_runtime_test.cljs`, `agent_lifecycle_test.cljs`,
  `embed_test.cljs`, and `web/serve_test.cljs`: replace replica stubs and
  connected-status gates with session capability, attachment, and health
  fixtures.
- `test/seon/web/datastar_test.cljs`, `view_unit_test.cljs`, router/debug tests:
  retain browser SSE behavior while changing database fixtures to grouped
  authority reads and selective interests.

## Minimal session proof before consumer migration

The session implementation needs a small deterministic gate before the broad
consumer suite. These are fixtures of the one production parser and writer,
not a parallel transport harness.

1. **Pure framing on both runtimes.** Feed every one-byte split of a frame,
   several coalesced frames, exact boundaries, zero length, signed overflow,
   configured over-limit length, malformed Transit, and truncated EOF. Assert
   exact allocations and no growing accumulator.
2. **Partial output.** Script JVM writes of zero, prefix, zero, and remainder;
   script Bun writes of a prefix followed by `drain`. Assert the accepted
   prefix is never resent, output order is FIFO, and queued bytes return to
   zero exactly once.
3. **Multiplexing.** Admit A then B on one session, complete B then A, and prove
   both pending calls settle by request ID. A cancel after A must be admitted
   after A even if another decode worker is busy.
4. **Identity isolation.** Submit the same active request ID from two sessions.
   The second receives a protocol failure and cannot join the first executor
   result. Reuse after exact completion succeeds.
5. **Session pressure.** Exhaust in-flight, incomplete-input, and queued-output
   bounds independently. Reject/close only the owning session as specified;
   a slow session cannot consume a dispatcher worker or delay a healthy one.
6. **Disconnect truth.** Close during a query, during queued work, during an
   accepted mutation, and after response encoding. Reads cancel, queued work
   releases, the mutation receipt remains recoverable, late results are not
   encoded, attachments release after scoped work drains, and all counters
   return to baseline.
7. **Lifecycle.** Closing the authority stops admission, drains accepted work,
   closes channels, joins the one selector thread, closes the one dispatcher,
   and releases every database. There is no per-session JVM worker to join.

## Child crash and reconnect semantics

A Bun child is an ordinary peer process, not a thread inside the JVM. Its crash
closes exactly one Unix channel. That close does not stop the selector,
authority, supervisor, accepted mutation, sibling Bun child, or another
database. A malformed frame, invalid Transit value, exceeded byte limit, or
client callback failure also closes only that session. Only a selector-loop
invariant failure is an authority core failure.

The cluster supervisor—not the database authority—owns child restart policy.
On restart the child opens one new session, calls `capabilities` once,
reacquires its databases, and resolves current heads. It may retry a pinned read
under the caller's ordinary retry policy. It must not silently retry a mutation
whose response was lost: it looks up or resubmits the same durable request ID
and receives the existing receipt/coordinate. Session close releases the
child's attachment ownership only after its admitted database work drains.

One Bun process may host several logical cluster consumers, but one child per
active agent gives actual CPU parallelism and crash isolation. Both shapes use
the same session protocol: one Bun process needs only one multiplexed socket and
may attach several databases. Separate Bun processes cost another runtime RSS
but permit OS scheduling, resource limits, independent replacement, and genuine
parallel JavaScript execution. The density gate, not the protocol, decides how
many active children modest hardware admits.

## `Bun.serve` and Datastar seam

The HTTP cut rewrites `src/seon/web/serve.cljs`, `router.cljs`,
`datastar.cljs`, and `debug.cljs` together:

- replace `node:http` with one `Bun.serve({fetch})` and ordinary Web
  `Request`/`Response` values;
- remove `:seon.http/node-req`, `:seon.http/node-res`, imperative
  `writeHead`/`write`/`end`, and `:seon.http/hijacked`;
- return one direct `ReadableStream` response for SSE, call
  `server.timeout(request, 0)`, observe `request.signal`, and preserve the feed
  ID replacement fence;
- on a negative direct-stream `write`, remember that the event was accepted,
  await `flush(true)`, and then offer only a newer pending event; never resend
  the pressured event;
- retain one pending latest event with exact byte accounting per browser and a
  bounded authority-wide total; and
- replace synchronous `node:fs` reads with `Bun.file` responses.

Loopback feeds initially use identity encoding. Delete `node:zlib`, per-feed
gzip transforms, `Z_SYNC_FLUSH`, and compression-specific drain wiring in the
native cut. Remote compression remains one explicit configuration value and
must be selected after browser/proxy measurements; the chosen Bun server still
does not provide automatic streaming response compression.

The database session sends database results and selective committed-change
evidence only. Datastar retains one interest per shared unit family, one Bun
render and SSE event per changed unit, and independent browser stream cursors.
Moving browser fanout or complete transaction broadcasts to the JVM would
increase bytes and couple slow browsers to database delivery.

## Atomic acceptance gate

- `rg` finds no reachable `seon.db.replica`, `node:net`, `node:buffer`, publish
  socket, database transaction replay, subscriber queue, `RemoteWriter`, or
  local Bun Datahike/Konserve opening.
- The JVM shows one selector thread and the authority-wide dispatcher threads,
  not one JVM thread per session. At 1/8/32 Bun children, session, request, and
  byte counts remain within configured limits and return to baseline after
  disconnect.
- Responses complete out of order; acquire/cancel admission remains input
  ordered; one slow, malformed, or crashed child does not change healthy child
  p99 or database progress materially.
- Accepted-mutation disconnect proves receipt recovery by the same request ID.
  No response or log claims rollback.
- Root, agent, data, debug, historical, and browser feeds prove one grouped
  authority read and one shared Bun render for equivalent browsers. Unrelated
  transactions cause no candidate query.
- Full CLJS tests run under Bun using the maintained Shadow artifact. Packaging
  contains no Node runtime requirement and starts with the source checkout
  unavailable.
- Compare persistent sessions with the deleted request-per-socket baseline for
  p50/p95/p99, requests per second, CPU, allocations, copied/queued bytes, and
  JVM plus Bun RSS. The earlier transport-only probe's 7–8 times throughput
  gain is a falsifier target, not a production claim.

Start measurement with the host-capacity plan's 256 KiB semantic page target,
4 MiB hard frame, 8 MiB queued bytes per session, and 32/64/128 MiB global
output totals at 2/4/8 selected processors. Measure actual query, transaction,
KNN, and Datastar distributions before asking Sean to raise them.
