---
type: research
status: active
tags: [research, database, flow]
---

# Selector session resource ownership

## Question

What must the Java selector and Bun session own so many independent children
cannot create hidden heap, reuse a physically active request identity, or wedge
authority shutdown?

## Source grounding

- Bun `Socket.write(data, offset, length)` is unbuffered and returns the exact
  accepted byte count. The caller retains the suffix until `drain`.
- Java `SocketChannel.write(ByteBuffer)` advances the buffer position, so the
  selector can retain one exact cursor without copying the suffix.
- Java executor interruption is best effort. A bounded close API therefore
  needs a deadline and honest evidence; it cannot promise to kill arbitrary JVM
  code.
- The current semantic executor admits at most one shared protocol frame of
  request bytes. The transport must pass the exact four-byte header plus payload
  charge instead of estimating the decoded map.

Relevant sources are Bun `bun.d.ts` and `socket_body.rs`, JDK 26
`SocketChannelImpl` and `ThreadPoolExecutor`, and first-party
`seon.db.transport.uds`, `seon.db.executor`, and `seon.db.writer`.

## Selected seam

The physical socket is both the authorization owner and the resource owner.
There is no second session ID.

On input, the selector reads a complete header, validates the one shared
four-megabyte protocol ceiling, and reserves exact `4 + payload` bytes globally
before allocating the payload buffer. That exact count is handed to the writer
and charged once to the outer executor job. Execute-many members charge zero
because they share the retained outer request.

Every admitted request also reserves one response slot until its final encoded
byte is written or the connection closes. This bounds tiny frames as well as
bytes. Response completion encodes off the selector and enqueues only an
immutable frame with its byte ownership; unencoded response maps are not queued.
The current contiguous encoder conservatively reserves twice the maximum frame
during encoding because Transit first creates a payload byte array and then the
framed buffer. It shrinks to exact retained bytes afterward. A chunked Transit
output stream with header backfill is the next measured allocation improvement;
it can remove that copy and reduce slack to one chunk without changing the
selector or protocol.

The authority and each connection have independent input, output, response-slot,
and connection limits. A slow or oversized connection closes without consuming
query permits or sibling capacity indefinitely.

Connection cleanup has its own fixed two-worker capacity and a queue no larger
than the accepted-connection ceiling. Connection admission includes opening,
open, closing, encoding, and cleanup-in-progress sessions; a session leaves only
after database cleanup and every response slot complete. Reconnect churn
therefore cannot create more cleanup work than the bound or silently discard an
acquisition. Saturation cannot create one emergency thread per disconnect or
consume codec progress.

Shutdown first drains, then force-closes at its configured deadline, stops codec
producers before draining their final selector commands, uses bounded joins,
and reports whether it was graceful and whether selector, codec, and cleanup
workers stopped. A forced connection is not itself unsafe: Datahike release is
allowed only after all three owners prove stopped. If an encoder ignores
interruption, the first close returns incomplete evidence while retaining its
slot and session; a later close can finish once that code returns.

## Bun timeout law

Timeout rejects the caller-facing Promise once and sends cancellation, but the
session retains the request ID and its capacity slot until the late response or
connection close. This prevents both ABA reuse and repeated timeouts from
exceeding physical work capacity. Deadline policy is operation-specific; there
is no universal five-second timeout. An accepted mutation timeout is an unknown
outcome recovered through its existing durable request receipt.

## Proof

The retained matrix covers fragmented and coalesced input, reverse completion,
partial output writes, exact pre-allocation input counters, a second session
denied at the global input bound, slow-reader output accounting, bounded broken
handler shutdown, forced encoding retention, failed-start native cleanup, fixed
cleanup concurrency, closing-session admission retention, and exactly-once
owner close.
Bun proof covers partial writes, timeout retirement, request-ID non-reuse,
physical pending capacity, terminal races, and a real native UDS roundtrip.

## Remaining falsifier

Measure the contiguous encoder against a bounded chunked Transit output stream
for 4 KiB, 64 KiB, 1 MiB, and 4 MiB results. Graduate the chunked form only if
copy/allocation reduction outweighs gathering-write and chunk bookkeeping cost.
