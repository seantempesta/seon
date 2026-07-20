---
type: issue
status: open
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
