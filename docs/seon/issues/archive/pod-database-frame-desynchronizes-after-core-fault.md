---
type: issue
status: resolved
severity: blocker
tags: [issue, database, runtime, flow]
---

# Preserve database frame boundaries while recording a core fault

## Problem

The pod's persistent database session lost its frame boundary while recording
the first live-drive core fault. Four bytes of response payload were then read
as a new length header, producing a fatal invalid-frame error and draining the
pod.

## Evidence

During the default-cluster run for agent `tall-turkeys-turn`, prompt
instrumentation recorded two consecutive `:seon.error/fault :core` entities at
transactions 536871708 and 536871709. Immediately afterward,
`logs/operator/pod/b21b042c-fb81-439d-9f03-dc39e243adc4.log:437` records:

```text
SEON-CORE-FAULT Invalid database frame length: 1869491813 bytes.
{:seon.db.transport.uds/frame-bytes 1869491813,
 :seon.db.transport.uds/failure
 :seon.db.transport.uds.failure/invalid-frame}
@basis-t=536871707

```

The configured frame maximum was 4,194,304 bytes. The alleged length is
`0x6f6e2e65`, the ASCII bytes `on.e`, which is response payload text rather
than a plausible binary length prefix. The next log line says
`on-core-error :crash — exiting after persisting the fault datom`.

Read-only operator inspection then showed:

- watcher, writer, claimant host, and web-render processes alive;
- pod process `11431` drained and not ready;
- no live Shadow client runtime advertisement;
- run `q8mrne0fdwvo` still open and fenced to claimant
  `11431@2026-07-24T04:46:42.811Z`.

The writer log has no matching transport-side failure and remained ready. The
failure is therefore on the client session's framing/consumption path, not a
writer process exit.

The exact producer-side mechanism was two output holders for one physical
socket. Ordinary response frames entered the `::outputs` deque through
`accept-encoded-response!`, while an unsolicited event frame remained only in
`::event-state`. On every writable selector turn, `write-session!` preferred
the head of `::outputs` over `::event-state`. A non-blocking channel write can
consume only a prefix, so this sequence was possible:

1. write an event frame prefix and leave its suffix in `::event-state`;
2. enqueue a normal response in `::outputs`;
3. write the complete response before the event suffix; and
4. resume the event suffix.

The resulting byte stream was
`event-prefix + response-frame + event-suffix`. The client correctly trusted
the event length, consumed bytes across the inserted response, and then read
the displaced Transit payload suffix (`on.e`) as the next length prefix.
Serialization itself was not partial: `message-frame` constructs a complete
`ByteBuffer` before either output path queues it, and only the selector thread
writes the server socket.

Commit `0b8ad3537` makes `::outputs` the one ordered sequence for opening
responses, request responses, and unsolicited events. `::event-state` retains
only event admission and completion ownership; an encoded event is appended
to `::outputs`, whose head cannot be replaced until its `ByteBuffer` is fully
written.

A real-UDS regression fills the socket with a three-megabyte event, observes
that its frame has advanced but still has remaining bytes, submits a request,
and proves the response is queued behind the partial event. The peer then
decodes the complete event and complete response, in that order, and all
output reservations return to zero. The focused JVM namespace passed 40 tests
and 217 assertions; the CLJS namespace passed 23 tests and 80 assertions, both
with zero failures or errors. The transcript is
`tmp/orchestrator/framedesync-gate.log`.

The canonical `bin/test-writer` wrapper could not start because this checkout
has no current compiled program artifact and its documented remedy is a full
operator cycle. The same exact writer classpath and namespace were therefore
run directly without starting a cluster. Per the live-proof source-freeze
boundary, the orchestrator owns the rebuild, restart, and fresh agent
re-drive.

## Owner

The one client database transport in `seon.db.transport.uds` owns frame
accumulation, response/event delivery, and session termination. Core-fault
recording must use that same ordered session without permitting delivery
callbacks or consecutive responses to reset or overrun the parser state.

## Acceptance

- A live instrumented core-output failure can persist its fault datom and
  receive consecutive writer responses without closing or desynchronizing the
  database session.
- A transport regression sends several framed responses in fragmented and
  coalesced chunks while response handling initiates another database write;
  every frame decodes once and in order.
- The same live falsifier leaves the pod ready and the next healthy database
  request succeeds.

The frame-ordering source contract and recurring real-socket regression are
resolved by `0b8ad3537`. The fresh core-fault live re-drive remains the
orchestrator's integration proof after its coordinated rebuild and restart;
it is not a remaining transport source change in this issue.
