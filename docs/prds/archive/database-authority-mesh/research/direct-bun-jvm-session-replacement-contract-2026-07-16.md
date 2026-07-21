---
type: research
status: complete
tags: [research, prd, database, flow, agent]
---

# Direct Bun–JVM session replacement contract — 2026-07-16

## Decision

Replace the current request-per-socket RPC and transaction publisher together
with one persistent native Unix-domain socket per Bun process. Do not land a
second transport, a compatibility flag, a broker, or a temporary feed. The
existing `seon.db.transport.uds` namespaces are rewritten in place, every
consumer moves to the one session owner, and the replica/publisher/replay path
is deleted in the same runtime cut.

One session accepts many independently identified requests. The authority
admits them in frame order, executes them through their existing bounded work
class, and writes each response when it completes. Response order is therefore
completion order, not request order. The existing
`:seon.db.protocol/request-id` is the only correlation and cancellation
identity from the Bun pending-request map through the JVM and Datahike.

The JVM socket owner is one non-blocking Java NIO selector, not one reader and
writer thread per session. The Bun owner is `Bun.connect`, not `node:net`.
Neither socket event loop performs a Datahike read, transaction, provider call,
KNN search, Transit encode/decode of a complete large value, compression, or
consumer callback.

## Dependency ledger and shortest probes

- Bun `be77b652884b16a103cfaa4af3c1102f72f2dcd3`:
  - `reference-code/bun/packages/bun-types/bun.d.ts:5781-5811` says native
    `Socket.write` is unbuffered and non-blocking, returns the actual number of
    bytes written, and may write less than requested under backpressure.
  - `bun.d.ts:6305-6356` supplies `data`, `drain`, `close`, `error`, `end`, and
    `connectError` callbacks plus a selected binary view.
  - `bun.d.ts:6489-6500` makes a Unix socket a direct `Bun.connect({unix,
    socket, data})` operation.
  - `reference-code/bun/src/runtime/socket/socket_body.rs:2366-2435` updates
    `bytes_written` from the actual native write result.
  - `socket_body.rs:2780-2860` shows that the Node-compatible buffered path
    copies the unwritten suffix. The direct `Socket.write` result plus `drain`
    avoids that hidden per-session copy.
  - `reference-code/bun/test/js/bun/net/socket-huge-fixture.js:21-31` retains
    one large buffer, advances by the returned byte count, and resumes in
    `drain`; `socket.test.ts:1260-1290` exercises the same partial-write shape.
- OpenJDK 26.0.1 in the selected development runtime:
  - `java.nio.channels.ServerSocketChannel`, `SocketChannel`, `Selector`, and
    `SelectionKey` are the public seam; `SocketChannel.write(ByteBuffer)`
    advances the buffer only by bytes accepted.
  - `Selector` documents that an interest-set change becomes visible on the
    next selection and that `wakeup` interrupts a blocked selection.
  - the selected macOS implementation,
    `java.base/sun/nio/ch/KQueueSelectorImpl.java:94-96,99-139,322-329` in the
    installed JDK source archive, uses a registered wakeup fd and drains it in
    the selector loop.
  - an executable probe opened `ServerSocketChannel` with
    `StandardProtocolFamily/UNIX`, registered non-blocking accept and read with
    one selector, connected a Unix `SocketChannel`, and observed exactly the
    two sent bytes. Result: `{:unix-selector? true, :bytes 2, :text "ok"}`.
- Transit:
  - `reference-code/transit-clj/src/cognitect/transit.clj:139-171` writes one
    value to an `OutputStream`.
  - `reference-code/transit-js/src/com/cognitect/transit/impl/writer.js:475-511`
    produces one complete JSON string.
  - `transit-js/src/com/cognitect/transit/impl/reader.js:38-61` calls
    `JSON.parse` on a complete string. Transit is not an incremental parser;
    only the four-byte framing is incremental.
- Existing first-party seams:
  - `src/seon/db/transport/uds.clj` already proves four-byte framing, bounded
    frames, partial Java writes, and duplicate `ByteBuffer` views over one
    encoded publisher frame.
  - `src/seon/db/transport/uds.cljs` currently pays for `node:net`, one socket
    and timer per request, payload/header/`Buffer.concat`, and repeated receive
    `Buffer.concat`.
  - `src/seon/db/executor.clj` already owns fair request admission, exact
    database scope, request identity, and internal asynchronous completion.
  - `src/seon/db/writer.clj:1421-1518` is the one semantic dispatcher. It must
    gain completion delivery rather than being wrapped by another dispatcher.
  - [[authority-protocol-contract-2026-07-16]],
    [[read-materialization-contract-2026-07-16]], and
    [[transit-bun-delivery-internals-2026-07-16]] settle the semantic values,
    host-value rejection, and byte/copy boundary.

## One wire and one framing parser

Keep the current wire shape: four unsigned big-endian length bytes followed by
one Transit JSON payload. A complete protocol request or response remains the
Transit value, including request ID. Do not add a session ID, member ID,
transport envelope, chunk message, or codec negotiation to every request.

Both runtimes use the same two-state framing parser:

1. fill exactly four header bytes;
2. reject zero, negative/signed-overflow, or over-limit length before
   allocation;
3. allocate exactly that payload length and fill it; and
4. decode only at exact completion, reset to the four-byte header, and continue
   through all remaining bytes in the same callback/readiness event.

There is no growing accumulator and no `Buffer.concat`. A fragmented byte is
copied once into its final payload. On Bun, an unfragmented complete frame can
decode a `Uint8Array.subarray` synchronously before the native `data` callback
returns and avoid the payload copy. On the JVM, `SocketChannel.read` targets
the four-byte header or exact payload `ByteBuffer` directly, avoiding an
intermediate read buffer and `compact` copies. One-byte headers/payloads,
several frames in one chunk, exact-boundary frames, truncated EOF, and an
oversized declared length are mandatory pure parser fixtures on both sides.

Transit JS still allocates its complete JSON string, and UTF-8 conversion still
allocates bytes. Do not claim zero-copy. Benchmark one contiguous framed write
against separate header/payload native writes: Bun explicitly recommends one
write for many small pieces, while two writes may save the full payload copy
for large responses. Choose from measured syscall, copied-byte, and latency
crossovers; do not create two production encoders.

## JVM session owner

`start-request-server!` remains the sole server lifecycle name but is replaced
internally with these owners:

- one non-blocking Unix `ServerSocketChannel` and `Selector`;
- one selector thread owning accept, channel registration, parser positions,
  output queue positions, interest changes, and channel close;
- one bounded per-session queue of complete undecoded payloads, decoded and
  admitted in arrival order through the existing bounded codec/control
  capacity; and
- the existing request work classes, which call one completion function when a
  response is ready.

Arrival-order admission matters even though completion is unordered. A cancel
received after its target and an operation received after acquire must never
overtake them because codec workers raced. Each session therefore advances one
decode/admission at a time, but admitted query, pull, mutation, KNN, provider,
and lifecycle work runs independently. The admission continuation immediately
starts the next decode; it does not wait for request completion.

The semantic owner changes in place from blocking
`handle-request runtime request -> response` to completion-driven admission:

```clojure
(handle-request! runtime request complete!)
;; returns after validation and work-class admission
;; calls complete! exactly once with the canonical response
```

Immediate control results may call `complete!` before returning. Reads use the
existing executor completion. Mutations use the connection's existing ordered
writer acceptance and complete after its durable result. KNN/provider/codec
use only their own capacity. No generic Future/Promise enters protocol data,
and the selector never blocks dereferencing the current Clojure promise.

Worker completion places one immutable framed response and offset request on a
thread-safe selector command queue, then calls `Selector.wakeup`. Only the
selector changes `SelectionKey` interests. It enables `OP_WRITE` while a
session has bytes, calls `SocketChannel.write` with the current buffer, retains
the advanced position on a partial/zero write, and disables `OP_WRITE` when the
queue empties. It never spins on a zero write.

An active request-ID registry precedes work-class admission. A duplicate
active ID, whether on the same or another session, is a protocol error; it must
not join an executor job and expose another session's result. The current
executor's internal same-ID join remains useful only after this authority
check. Completion or session cleanup compare-removes the exact active owner.

## Bun session owner

Rewrite `src/seon/db/transport/uds.cljs` in place around
`Bun.connect({unix, data, socket})`. Do not require `node:net` or
`node:buffer`. One process-local owner retains:

- the native socket and framing state;
- a map from the existing request ID to resolve, reject, deadline, operation,
  and attachment data needed for truthful recovery;
- one FIFO of immutable outgoing frames with the offset returned by
  `socket.write`; and
- current in-flight and queued-byte counts.

`request!` inserts the pending entry before making its frame writable. It
rejects a duplicate local ID, checks in-flight and byte admission, writes from
the retained frame and offset, and resolves only the response carrying that
ID. `drain` resumes the exact suffix. `data` advances the linear parser and may
resolve responses in any order. `error`, `end`, and `close` enter one idempotent
close function, settle every pending Promise once, release frame references,
and start no hidden reconnect timer.

The caller owns reconnect because it owns the database attachment and durable
operation intent. One shared session connection function may retry connection
establishment with a bounded operator-configured policy; an individual request
must never open a replacement socket. There is no synchronous facade: remote
`seon.db` functions return Promises and coarse callers use `execute-many`.

## Initial hard bounds

These are implementation defaults to falsify on modest hardware, not new wire
fields or permanent product constants:

| Retained resource | Per session | Authority total | Overflow action |
|---|---:|---:|---|
| active sessions | — | 64 | refuse connection before parser allocation |
| incomplete input plus queued undecoded payload | 2 MiB | 128 MiB derived maximum | close only that session |
| one request or response frame | 1 MiB | — | typed too-large response when correlation is known; otherwise close |
| admitted requests without response | 16 | 512 | typed busy response before work admission |
| framed output not accepted by kernel | 2 MiB | 128 MiB derived maximum | close the slow session and cancel its cancellable work |

The global byte maxima are the product of the admitted-session limit and the
per-session reservation, so one session can never consume another session's
already-admitted byte capacity. Semantic paging keeps ordinary query/index
results below one frame; large blobs remain content-addressed outside database
values. A transaction genuinely needing more than 1 MiB must be measured
before raising the one bound. Do not silently fragment one semantic response
inside the transport because doing so creates another paging/reassembly
protocol.

The selector command queue is also bounded by the total in-flight count.
Encoded bytes count from allocation until the final byte is accepted or the
session closes. Incomplete input bytes count from length acceptance until
decode finishes. Counts are decremented exactly once by the same owner that
removes the reference, and health evidence reports sessions, in-flight
requests, incomplete input bytes, queued output bytes, rejected admissions,
partial writes, and disconnect cancellations.

The initial 1/2 MiB values deliberately replace the current 16 MiB frame times
16 queued frames, which can retain roughly 256 MiB for one slow subscriber.
Benchmarks may increase the semantic page/frame bound, but the per-session ×
session-count proof must continue to fit the density target.

## Encode-once truth

Encode each canonical correlated response exactly once. Its request ID and
per-request Datahike evidence make responses to two otherwise identical reads
different protocol values, so this transport must not add a response cache or
claim one shared Transit frame across different request IDs. Datahike already
computes the identical query result once; that is the expensive shared seam.

One immutable frame is shared only when the exact protocol value is genuinely
one-to-many, such as a future selective database-interest event with no
recipient-specific field. Each recipient then owns a duplicate JVM
`ByteBuffer`/Bun offset, and the frame is released after the final recipient.
Datastar browser fanout remains in the Bun web owner: one authority read, one
CLJS render, and one SSE body for interested browsers. Do not move browser
fanout or full transaction broadcasts into this session transport.

## Disconnect, cancellation, acquire, and release

- Session close removes its admission immediately and prevents later response
  encoding for that session.
- Every admitted query or `execute-many` member is canceled by its existing
  request ID. Datahike detaches only that caller; another identical reader may
  continue.
- A mutation already accepted by Datahike is not called canceled or rolled
  back. It completes durably without retaining a dead session response. After
  reconnect, the client resubmits/looks up the same durable request ID and
  recovers the existing receipt and committed coordinate.
- Acquire is idempotent within one live session and records the returned
  attachment under that session. Data requests use attachment plus coordinate;
  they do not repeatedly acquire by database name.
- Explicit release removes that session's attachment ownership only after its
  admitted exact-scope work obeys the existing drain/fence rules. Disconnect
  performs the same release for every acquired attachment after accepted
  mutations settle and cancellable reads relinquish it.
- Reconnect creates a new session, runs capabilities/version negotiation once,
  reacquires the database, resolves head, then lets callers decide whether to
  retry a coordinate-pinned read. It never pretends the old attachment or an
  unknown transaction response survived the socket.
- Final Datahike release still occurs only when the registry's last attachment
  owner is gone. Other sessions and databases continue independently.

Malformed Transit, invalid framing, decoder failure, callback failure, output
overflow, and native socket error close only their session. A selector-loop or
authority-core fault follows the existing core-error policy; it is not caught
and mislabeled as a client failure. A Bun child crash cannot take down the JVM
or sibling children. A JVM exit rejects each Bun session and leaves the Bun
supervisor free to report/reconnect; it cannot corrupt durable receipts.

## Atomic replacement and exact deletions

Tests for the pure parsers and session state may be written before the runtime
cut, but production has one reachable mechanism at every commit. The cut is:

1. make `writer/handle-request!` completion-driven and prove it through direct
   fixtures without adding a second semantic dispatcher;
2. replace, in place, the blocking server implementation in
   `src/seon/db/transport/uds.clj` with the selector session server;
3. replace, in place, the Node implementation in
   `src/seon/db/transport/uds.cljs` with the native Bun session;
4. move `seon.db`, client boot/health, Datastar interests, KNN, and every read
   consumer to that owner; and
5. in the same source cut, delete the now-unreachable replica/feed/replay
   implementation and both-socket launch fields. There is no runtime flag or
   fallback.

Exact old transport functions deleted from `uds.clj`:

- blocking `connect!`, `call!`, `admit-request!`, `finish-request!`, and
  `serve-connection!`;
- the thread-per-connection body of `start-request-server!` and
  `close-request-server!`; and
- `message-frame`, `close-subscriber!`, `start-subscriber!`,
  `start-publisher!`, `publish!`, `close-publisher!`, subscriber queue schemas,
  and all publisher constants.

Exact old client functions/values deleted from `uds.cljs`:

- `js/require "node:net"`, `js/require "node:buffer"`;
- `default-request-socket-path`, `default-publish-socket-path`, `rpc-tick-ms`,
  and `default-rpc-timeout-ms`;
- `rpc`, `connect-publisher!`, `::rpc-request`, and `::publisher-request`; and
- both growing `!buffer`/`Buffer.concat` receive paths.

The same cut deletes:

- all of `src/seon/db/replica.cljs` and `test/seon/db/replica_test.cljs`;
- the replay operation, schemas, constructor, `replay-transactions-page`,
  writer dispatch, `test/seon/db/replay_test.clj`, and feed-only assertions;
- `::publisher`, publisher startup/publication/shutdown, transaction-event
  fanout, `::publish-socket-path`, `--pub-sock`, `SEON_PUB_SOCK`, and every
  request/publish dual-path launch/config field;
- local Bun Datahike connection/index/cache construction, native listener
  synthesis, replay buffers, own-write correlations, feed status/readiness,
  and reconnect timers; and
- the old publisher cases in `transport_uds_test.clj` and
  `writer_integration_test.clj`, replaced by session fragmentation,
  multiplexing, receipt recovery, and selective-interest proofs.

`::request-socket-path` is renamed directly to one authority socket field only
if the one launch descriptor benefits from the clearer name; both names must
never coexist. `seon.db` remains the sole application API, and
`seon.db.transport.uds` remains the sole byte/socket owner.

## Executable graduation falsifiers

Correctness and failure:

- send requests A(slow), B(fast), C(fast) on one socket; observe B/C responses
  before A and correlate all solely by request ID;
- interleave 1/8/32 Bun children, 16 in-flight requests each, and prove no
  response crosses a session or request identity;
- deliver every header/payload one byte at a time, many frames in one native
  callback, exact-boundary frames, truncated EOF, malformed Transit, and
  declared lengths 0, 1 MiB, and 1 MiB + 1;
- force Bun and JVM native writes to return partial progress/zero, resume only
  from the recorded offset on `drain`/`OP_WRITE`, and compare a payload digest
  at the receiver;
- stall one recipient until its 2 MiB bound closes it while fast siblings,
  lifecycle/cancel, another database, and the selector continue;
- disconnect during queued/running identical query, execute-many, pull,
  accepted transaction, encoding, and partial write; prove zero retained
  request/frame/attachment identities and recover only the transaction receipt;
- crash one Bun child, its parent, and the JVM separately; prove the documented
  sibling/parent fate and exact attachment cleanup after reconnect/release; and
- release/reopen the same database while an old session has queued and running
  work; prove no old generation result or cancellation crosses into the new
  attachment.

Performance and density, always against the removed request-per-socket build at
the same commit parent and workload:

- 1/8/32 children × 1/4/16 in-flight cold and warm query/pull/execute-many;
- p50/p95/p99 end-to-end and server queue latency, requests/s, syscalls/request,
  bytes copied, Transit encode/decode count, selector wakeups, CPU, allocation,
  GC, JVM RSS, Bun RSS, thread count, and open descriptors;
- 1 KiB/64 KiB/1 MiB fragmented and contiguous responses, with fast and slow
  recipients, to choose contiguous versus header/payload writes;
- demonstrate one persistent descriptor and zero per-request timer/socket/event
  emitter, one JVM selector thread rather than one session thread, bounded
  codec/work-class threads, and no Node module loaded; and
- repeat 2/4/8 databases under read/provider/KNN/encoding saturation to prove
  session delivery does not become the global gate Unit 4 removed.

The replacement graduates only when the reachability search finds no
`node:net`, `uds/rpc`, publisher socket, replay transaction, replica Datahike
connection, feed listener, compatibility selector, or second transport; all
real browser/agent journeys use the persistent native session; and the measured
latency, throughput, CPU, memory, and cleanup evidence beats or explains every
regression against the deleted path.
