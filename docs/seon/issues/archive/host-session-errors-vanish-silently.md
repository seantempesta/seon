---
type: issue
status: resolved
severity: friction
tags: [issue, agent, architecture, database]
---

# Host session errors vanish silently

## Observed (2026-07-20, U1.5 live drive)

`seon.host/serve-session!` ends every session under
`(catch Throwable _ nil)` and `seon.host.context/writer-call!`'s second
failure propagates into that catch, so a pod (or probe) connecting while
the cluster writer is down/restarting sees only an EOF — no error frame,
no host log line. During the U1.5 drive this made a concurrent-lane
writer restart look like a host defect: every new session died at
startup with a bare EOF until version-consistent processes reconnected
(evidence: `tmp/sci-probe/exec/out/u15*-drive.log` plus the probe
transcripts in the U1.5 roadmap section of
`docs/prds/sci-execution-runtime/roadmap.md`).

## Expected owner and shape

`src/seon/host.clj` (`serve-session!`, `accept-startup!`). A failure the
host can name must leave the session as data: a startup `error` frame
with the writer failure's `:seon.error/message` (resolve-head! already
returns error values — the throwing path is the retained-connection
retry inside `seon.host.context/writer-call!`), and at minimum one
host-side log line for a session that dies on a Throwable. Errors are
values at this boundary; a silent close is the violation.

## Acceptance

- A session opened while the writer is unreachable receives a startup
  error frame naming the writer failure (conformance-testable against
  the fake writer by killing it between sessions).
- The host process logs one line per session terminated by a Throwable.
- No behavior change for healthy sessions.

## Resolution

Resolved on 2026-07-23 by `d1e6612fe`. `accept-startup!` now consumes the
database leaf's canonical flat error value and sends its message through the
existing bounded startup error frame. A non-timeout session Throwable still
records one core fault and now also emits one structured error log event.

Recurring proof lives in `seon.host-conformance-writer-test`: the fake writer
is stopped between sessions, the next session receives a keyed startup error
frame before EOF, the writer is restarted, and a following healthy session
receives READY. The malformed-frame regression asserts one persisted core
fault and exactly one keyed error log event. The focused gate passed 36 tests
and 198 assertions; its full transcript is
`tmp/orchestrator/hosterr-gate.log`.
