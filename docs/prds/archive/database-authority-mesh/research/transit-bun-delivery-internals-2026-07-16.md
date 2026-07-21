---
type: research
status: active
tags: [research, prd, database, flow, web]
---

# Transit and Bun delivery internals — 2026-07-16

## Decision

Interpret once, Transit-encode once, frame once, and retain one immutable frame
while accepted sessions reference it. Each session owns only its current offset
and byte budget. Partial writes advance that offset and resume on writable;
closing a session releases its reference exactly once.

The JVM publisher already demonstrates this model. In
`src/seon/db/transport/uds.clj:334-357`, one encoded frame is shared through
duplicate `ByteBuffer` views with independent position and limit. Its
`uds.clj:267-302` write loop preserves partial progress. Strengthen this owner
rather than inventing a new fanout mechanism.

## Transit boundaries

Transit CLJ writes directly to an `OutputStream` at
`reference-code/transit-clj/src/cognitect/transit.clj:139-171`; it does not
require an intermediate String. Transit JS creates one complete JSON string
through `JSON.stringify` at
`reference-code/transit-js/src/com/cognitect/transit/impl/writer.js:475-511`
and clears its token cache after each top-level value. The cache is
within-message encoding compression, not a reusable result cache.

Transit JS read calls `JSON.parse` on a complete string at
`transit-js/src/com/cognitect/transit/impl/reader.js:38-61`. Incremental parsing
therefore belongs only to framing. Accumulate a four-byte length and then fill
one exactly sized payload buffer; decode only at exact completion. Reject
oversized lengths and truncated EOF before Transit.

## Copies to remove

The current CLJS request path creates a Transit String, a UTF-8 Buffer, a header
Buffer, and then a copied `Buffer.concat` frame. Its receive paths repeatedly
concatenate the entire accumulated reply, which can make copied bytes quadratic
under fragmented delivery. Persistent Bun sessions remove per-request socket,
timer, and event-emitter ownership and replace both receive accumulators with
one linear frame parser.

The JVM request encoder copies its `ByteArrayOutputStream` payload once through
`toByteArray`. The publisher then copies that payload into a second full frame.
A finalized framed output may remove one of these copies, but the existing
shared-frame and partial-write mechanism remains the owner.

## Bun ownership

Vendored Bun reports actual partial native socket writes at
`reference-code/bun/src/bun.js/api/bun/socket_body.rs:2393-2437`. Its compatible
buffered path copies the unwritten suffix into per-socket owned storage at
`socket_body.rs:2824-2866` and resumes it on writable. That is safe but can
multiply retained payload tails by session count.

For controlled fanout, keep the shared frame and session offset in the Bun host
when the public native call exposes partial progress. If the selected public API
always buffers a copy, measure and report that per-session cost rather than
claiming zero-copy.

## Byte bounds and compression

The current JVM publisher bounds 16 frames per subscriber but not bytes. A few
large frames can therefore retain hundreds of MiB even when frame count looks
small. Admission and evidence must bound queued bytes per session and globally;
a slow session cannot retain query permits or delay fast sessions.

Loopback remains uncompressed by default. For remote clients, compression is
explicit, configurable, and thresholded from measurements. Compress once after
Transit and before fanout so every session shares the same representation. Bound
both compressed wire bytes and declared uncompressed bytes, and enforce an
expansion limit before decode.

## Falsifiers

- Fan out one result to 1, 8, and 64 sessions; observe one Transit encode.
- Stall every session after a partial write; measure retained bytes versus
  session count and distinguish shared bytes from copied tails.
- Deliver header and payload one byte at a time and many frames in one chunk;
  copied bytes remain linear and results remain ordered.
- Stall one session while fast sessions continue; byte limits close or replay
  the slow session without retaining read capacity.
- Benchmark compression off/on over real results and remote-like bandwidth and
  latency; select a threshold only where total latency improves.
