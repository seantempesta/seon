---
type: issue
status: resolved
severity: blocker
tags: [issue, database, operator, archive]
---

# Writer start error masquerades as ready

## Problem

The standalone writer composition translated `:seon.config/on-core-error`
into the UDS request-server options without carrying that attribute in the
operational launch envelope. The translation therefore asserted a present
`nil` for an optional enum field. `seon.db.writer/start!` correctly returned
an error value, but `seon.db.server/start!` treated that value as a running
writer, published a REPL port, and logged the request socket and `ready`
although no request server existed.

The operator then reduced the missing request socket and every other
session-open refusal to bare `false` until its three-minute backstop fired.
Shutdown passed the start-error map into `writer/stop!`; the absent request
server reached `(long nil)` in `close-request-server!`, producing
`Number.doubleValue() because x is null`.

## Evidence

- The failed launch envelope omitted `:seon.config/on-core-error`.
- The derived request-server options contained
  `:seon.db.transport.uds/on-core-error nil`.
- Writer validation named the exact input path
  `[:seon.db.writer/request-server-options
  :seon.db.transport.uds/on-core-error]`.
- The writer log advertised `ready`, but the UDS close diagnostic never ran
  and no request socket was bound.
- The shutdown stack was `RT.longCast` ->
  `seon.db.transport.uds/close-request-server!` ->
  `seon.db.writer/stop!` -> `seon.db.server/stop!`.

## Resolution

The resolved `:seon.config/on-core-error` enum is now an enforced operational
launch dependency with its real schema, and the server forwards it to the UDS
request server. `seon.db.server/start!` rejects a writer error value before
starting the REPL or advertising readiness. The operator writer probe now
retains and reports the exact missing path, connect exception, session-open
response/validation data, or close failure; its latest observation is carried
into timeout and status evidence.

The temporary synthetic close stack trace was deleted. Normal shutdown is not
a fault, selector failures already log their real throwable, and incomplete
shutdown already returns structured results.

## Acceptance

- Operator readiness and writer lifecycle focused suites are green.
- A writer start error cannot log `ready` or enter `server/stop!`.
- The live default writer completes a real UDS session-open handshake.
- `bin/seon status` reports watcher, writer, host, pod, and web-render ready.
- Normal shutdown contains no `doubleValue` NPE.
