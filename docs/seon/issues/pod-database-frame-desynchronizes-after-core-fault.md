---
type: issue
status: open
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
