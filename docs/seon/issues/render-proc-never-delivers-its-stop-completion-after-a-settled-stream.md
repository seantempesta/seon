---
type: issue
status: open
severity: blocker
tags: [issue, render, web, flow]
---

# The render proc never delivers its stop completion after a settled stream

## Problem

`seon.render.web/render-step`'s transition arity puts `::stopped` on the
proc's own completion when flow delivers `::flow/stop`
(`src/seon/render/web.clj:875-881`). After
`seon.render.web-test/thinking-stream-morphs-into-the-settled-session-transcript`
that put never happens: `flow.core/stop` returns, and the completion is still
empty 20 s later.

This matters beyond the test. `disarm-agents!` joins both cluster-graph procs'
active transforms on that same completion before releasing the branch
connection, so a stop transition that never runs is a shutdown that never
finishes.

## Evidence

Found 2026-08-07 repairing `seon.render.web-test`. It is DETERMINISTIC, not
load-dependent: three consecutive isolated runs of that one var through
`clojure.test/test-vars` (no `bin/test`, no other namespace) all failed the
same way, and it was the only red left in the namespace after the reconnect
and ping-oracle repairs (`bin/test seon.render.web-test`: 38 tests, 238
assertions, 0 failures, 1 error).

The failure is `[:render-proc-stopped]` in `with-server`'s `finally`
(`test/seon/render/web_test.clj`), which is the shared loud backstop doing
exactly its job.

Ruled out so far:

- **Not a thrown transform.** The fixture now KEEPS everything flow puts on
  `error-chan` and asserts it is empty before waiting on the completion. That
  assertion passes: the graph reported no proc error.
- **Not a failing test body.** Every assertion in the test itself passes; only
  the fixture's shutdown wait fails.
- **Not the derivation cost alone.** A pass measures ~1.9 s after the first
  (`render-package-proc-reruns-unchanged-renderers.md`), so 20 s is roughly
  ten passes of headroom, and no other test in the namespace loses its stop.

What is distinctive about this test: it is the only one that drives the
`::stream` in-port through a reasoning partial AND then commits the settling
attempt/form/eval/plan-digest facts, so the proc's last work before stop is a
terminal-fact pass that empties `::streams`.

## Owner

`seon.render.web/render-step`'s transition arity and the flow graph's stop
path (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj`).

## Acceptance

`clojure.test/test-vars` on
`thinking-stream-morphs-into-the-settled-session-transcript` is green three
times in a row, with the cause named rather than the wait lengthened — a
larger backstop would hide the shutdown defect that `disarm-agents!` depends
on. A virtual-thread-aware `jcmd Thread.dump_to_file` taken inside the 20 s
window is the next step; two attempts on 2026-08-07 missed the window because
the JVM had already exited.
