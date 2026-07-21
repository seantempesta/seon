---
type: research
status: complete
tags: [research, prd, database, flow]
---

# Selector session source proof — 2026-07-16

## Result

The direct replacement seam is sound: one persistent `Bun.connect` Unix socket
per Bun process and one Java NIO selector for all Bun processes. This is not a
second transport. Rewrite `seon.db.transport.uds` in place, move request and
transaction delivery onto the session, then delete the request-per-connection
and publisher/replica paths in the same cut.

The selector owns bytes and socket readiness only. Existing authority
admission owns work, database scope, and cancellation. That separation gives
parallel request execution without a thread per connection and makes one child
or malformed session independently disposable.

## Exact dependency seams

Dependency revisions used by this proof:

- Bun `be77b652884b16a103cfaa4af3c1102f72f2dcd3`, executed as Bun 1.3.14.
- OpenJDK 26.0.1, Homebrew build, including its installed source archive.
- Seon branch source at `82a7f9cb` before this report.

The useful Bun seam is the native socket API, not `node:net`:

- `reference-code/bun/packages/bun-types/bun.d.ts:5785-5811` specifies that
  `Socket.write` is unbuffered and non-blocking, accepts an offset and length,
  returns actual bytes accepted, can return a partial count, and returns `-1`
  after shutdown.
- `bun.d.ts:6305-6357` exposes `data`, `drain`, `end`, `close`, `error`, and
  `connectError`, with `binaryType: "uint8array"` to avoid an unnecessary view
  conversion.
- `bun.d.ts:6459-6469,6487-6493` makes Unix-domain connection a direct
  `Bun.connect({unix, socket, data})` operation.
- `reference-code/bun/src/runtime/socket/socket_body.rs:2366-2437` passes bytes
  to the native socket, records the actual accepted count, and returns `-1` on
  a fatal peer error rather than running nested close teardown inside write.
- `socket_body.rs:1562-1604,1936-1995` dispatches peer end and close separately
  and makes late close teardown idempotent after handlers have detached.
- `reference-code/bun/test/js/bun/net/socket-huge-fixture.js:21-31` is Bun's own
  retained-buffer pattern: advance by the returned count and resume the exact
  suffix from `drain`. That is the production write algorithm Seon should use.

The selected JDK supplies the matching server seam:

- `java.nio.channels.SocketChannel.write(ByteBuffer)` advances the buffer by
  only bytes accepted. A zero write under backpressure leaves its position as
  the exact retry cursor.
- `java.nio.channels.Selector:369-421` says selection returns for channel
  readiness, `wakeup`, or interruption. Worker completion can therefore append
  one selector command and call `wakeup`; it never mutates a selection key.
- The macOS implementation in the installed
  `java.base/sun/nio/ch/KQueueSelectorImpl.java:78-139` creates one wakeup socket
  pair, processes its update queue before polling, and uses kqueue for all
  registered channels. This is one selector resource, not hidden polling per
  session.

Transit remains a complete-value codec, not an incremental stream codec. Keep
the current four-byte unsigned big-endian payload length and decode only after
the exact payload is present. The framing parser is incremental; Transit is
not.

## Measured executable proof

A disposable Java 26 server opened a non-blocking Unix
`ServerSocketChannel`, registered it and every accepted `SocketChannel` with
one selector, filled exact four-byte header and payload buffers, admitted work
to virtual threads, sent completions through a concurrent command queue plus
`Selector.wakeup`, and retained each output `ByteBuffer` position across
writes. A Bun 1.3.14 client used `Bun.connect`, one linear two-state parser, and
request IDs carried as payloads.

The first request was written one byte at a time and delayed 50 ms. Later
requests were written contiguously. This exercised fragmented header/payload
input, kernel-coalesced frames, multiplexing, selector wakeup, and out-of-order
completion. The probe rejected a first-in-order result and checked the full
result set for loss or duplication.

| Shape | Requests | Elapsed | Requests/second | Observed completion |
|---|---:|---:|---:|---|
| one persistent session | 10,000 | 60.66 ms | 164,862 | request 1 first, delayed request 0 last |
| connect/write/read/close | 1,000 | 42.48 ms | 23,542 | ordered, one outstanding |
| connect/write/read/close repeat | 1,000 | 48.65 ms | 20,555 | ordered, one outstanding |

This small transport-only probe is directional, not a production capacity
claim. Persistent multiplexing was 7.0–8.0 times the reconnect throughput and
proved that one slow request does not hold response order. The production gate
must repeat with Transit values, bounded authority work, 1/8/32 Bun processes,
and density RSS.

## Framing and backpressure decisions

Use one parser state per session:

1. fill the four-byte header directly;
2. reject length zero, signed overflow, and the configured maximum before
   allocating;
3. allocate exactly the declared payload and fill it directly;
4. hand the completed bytes to bounded codec capacity; and
5. reset to the header and continue through remaining bytes in the same event.

Do not retain a growing buffer and do not use `Buffer.concat`. Bun may decode a
complete contiguous `Uint8Array.subarray` synchronously during the callback;
fragmented payloads copy once into their final allocation. The JVM reads into
the final header or payload `ByteBuffer`. Transit necessarily creates its
complete encoded value, so this is copy-bounded rather than zero-copy.

Each side retains one FIFO of immutable complete outgoing frames plus an offset
per frame. Bun calls `socket.write(frame, offset, remaining)` and treats the
returned positive count as the only progress. Zero waits for `drain`; `-1`
enters session close. The JVM enables `OP_WRITE`, calls
`SocketChannel.write(frame)`, retains the advanced position on zero/partial
write, and removes `OP_WRITE` when empty. Neither side spins or resends bytes
already accepted.

Count an encoded frame against the session and authority byte limits from
allocation until its final byte is accepted or the session closes. A slow
recipient loses only its own session when that bound is exceeded and never
retains an execution permit. Page semantic results before framing rather than
inventing transport fragments.

## Ordering, cancellation, and resilience

One session admits complete requests in frame order, but responses are written
in completion order and correlated solely by
`:seon.db.protocol/request-id`. Decode/admission of request B cannot overtake A;
execution of B need not wait for A. This preserves acquire-then-read and
request-then-cancel ordering without serializing independent database work.

Register the request ID to the session before authority admission. Reject an
active duplicate globally so another session cannot attach to a result it does
not own. Completion compare-removes that exact registration, enqueues its
response only while the session is live, and calls `Selector.wakeup`.

Session close is one idempotent transition:

- cancel admitted query and `execute-many` work by the existing request ID;
- release queued input, output frames, pending Bun Promises, and attachment
  ownership exactly once;
- do not encode late read completions for the dead session;
- let a mutation already accepted by Datahike finish and recover its durable
  receipt after reconnect; never claim rollback; and
- release the final database attachment only through the existing scoped drain
  and registry reference count.

A child crash therefore closes one channel and cannot terminate the selector,
JVM, sibling Bun processes, or accepted mutations. Malformed framing, malformed
Transit, callback failure, or byte overflow closes only the owning channel.
Codec work must run off-selector so a malicious or expensive value cannot stop
socket progress. A selector-loop invariant failure is an authority-core fault
and follows the supervisor policy rather than being mislabeled as a client
error.

Reconnect is explicit at the Bun database owner: create one new session, run
capabilities once, reacquire databases, and resolve current heads. It must not
silently retry a mutation with an unknown response; receipt lookup by the same
request ID resolves that outcome. It may retry a coordinate-pinned read only
under the caller's normal request policy.

## Minimal atomic replacement inventory

Strengthen existing owners; do not add a parallel transport namespace.

- Rewrite `src/seon/db/transport/uds.clj` from blocking streams, one thread per
  connection, and publisher workers to the single selector, linear parsers,
  completion command queue, and bounded session state. Preserve
  `start-request-server!` and `close-request-server!` as lifecycle names.
- Rewrite `src/seon/db/transport/uds.cljs` from `node:net`, one connection and
  timer per request, repeated `Buffer.concat`, and a separate publisher into
  one process-local `Bun.connect` owner with pending request map, output FIFO,
  linear parser, and idempotent close.
- Change `src/seon/db/writer.clj:1466-1576` in place from synchronous
  `handle-request` to completion-driven `handle-request!`; preserve the one
  semantic operation dispatch. Remove the publisher from runtime assembly and
  route only selective protocol results/events through sessions.
- Collapse the two socket arguments into one in `src/seon/db/server.clj`,
  `script/seon/dev/config.clj`, `script/seon/dev/process.clj`, and launch data.
  Update restore/branch administrative callers to the persistent session or a
  deliberately bounded one-shot administrative call using the same framing.
- Migrate `src/seon/db/replica.cljs` consumers to remote protocol reads before
  deleting that namespace's local replica, replay buffer, publisher connection,
  and request retry machinery. Do not retain a compatibility feed.
- Replace the blocking transport and publisher assertions in
  `test/seon/db/transport_uds_test.clj`; migrate writer, replay, replica,
  process, launch, restore, branch, generated-ID, receipt, and coordinate tests
  that name request/publish sockets.

The transaction publisher at `src/seon/db/writer.clj:408-420`, publisher start
at `1592`, and publisher close at `1635,1664` are deleted in this cut. Full
transaction broadcast does not move into the session. A later selective
listener delivers only explicitly interested database changes.

## Acceptance gate

The replacement is ready to graduate only when focused tests prove:

- pure parser fixtures on both runtimes: every one-byte split, coalesced frames,
  exact boundary, zero/overflow/over-limit length, truncated EOF, malformed
  Transit, and no growing accumulator;
- partial and zero writes resume the exact suffix after `drain`/`OP_WRITE`, a
  `-1`/peer reset settles every pending request once, and queued byte accounting
  returns to zero;
- responses complete out of order while acquire/cancel admission remains in
  input order; duplicate active IDs fail without joining work;
- one slow session, malformed session, canceled request, and crashed child do
  not delay healthy sessions or another database;
- disconnect cancels reads, preserves accepted mutation receipt recovery, and
  releases attachments after admitted scope work drains;
- 1/8/32 Bun children remain within configured session, in-flight, input, and
  output byte bounds with one selector thread and no per-session JVM worker;
- Transit request/result/page values preserve the existing protocol fixtures;
  and
- measured persistent sessions beat the deleted request-per-connection path on
  latency, CPU, allocations, and throughput, with process RSS included in the
  final cluster density proof.

## Consequential values still requiring measurement

The mechanism is settled; these limits are not. Start from the host-capacity
plan's semantic page target of 256 KiB, hard frame limit of 4 MiB, 8 MiB queued
bytes per session, and 32/64/128 MiB authority output totals at 2/4/8 cores.
Measure real query, pull, transaction, and KNN distributions before asking Sean
to choose a higher bound. Also measure whether one framed write beats separate
header/payload writes across the 256 KiB–4 MiB range. These are capacity
choices, not reasons to change the session protocol.
