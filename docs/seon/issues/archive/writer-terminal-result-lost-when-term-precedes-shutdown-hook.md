---
type: issue
status: resolved
severity: friction
tags: [issue, database, flow, archive]
---

# Writer terminal result lost when TERM precedes shutdown-hook registration

## Problem

`seon.db.server/-main` registered its shutdown hook only after `start!`
returned, but `start!` advertises readiness mid-flight: the request socket
binds inside `writer/start!` before the surrounding printlns and the return
to `-main`. A `SIGTERM` delivered in that window terminated the JVM with
zero registered hooks, so the fsync'd terminal-result publication
(`run-shutdown!` -> `terminal-publisher` -> `atomic-write-edn!`) never ran
even though the writer had already told its operator it was ready. Operators
and the retained test then observe a cleanly exited writer with no terminal
result file.

Surfaced as the intermittent
`real-writer-process-publishes-its-successful-terminal-result` failure
(`test/seon/db/server_test.clj:166`): 2/6 full `bin/test-writer` runs on
2026-07-20 under heavy machine load. The test polls for the socket every
25 ms and TERMs immediately, so load only had to stretch the bind-to-
registration gap past one poll interval.

## Evidence

- Standalone repro (launch `clojure.main -m seon.db.server`, busy-poll the
  socket path, TERM the instant it appears): 20/20 runs exited with no
  result file pre-fix; writer logs ended at "[database] booting pid=",
  proving TERM landed after bind but before hook registration.
- Post-fix the same repro published 30/30; focused
  `bin/test-writer seon.db.server-test` looped 10x green under 8-way CPU
  load; one full `bin/test-writer` green.
- Once the hook is registered, publication IS ordered before exit by
  construction: the hook fsyncs the file and its parent directory before
  returning, and the JVM's TERM handling waits for registered hooks.

## Resolution

`b34548b0`: `-main` registers the shutdown hook before calling `start!`.
The hook derefs a `started` promise (five-minute bound), so a TERM during
start waits for `start!` to settle and then runs the one existing
`run-shutdown!` publish path; a failed or stalled start reports to stderr
instead of stopping a server that never existed. No second publish path was
added and the test's 1000 ms budget was left unchanged — after `waitFor`
returns, the file must already exist.

The original B8 sightings (`writer-integration` release path,
`query-admission` injected-release) did not recur in the six-run loop and
are in-process, order-dependent failures — a separate mechanism from this
subprocess signal race; they remain tracked under B8.
