---
type: issue
status: resolved
severity: friction
tags: [issue, operator, runtime]
---

# The operator bypasses instrumentation to call stop!

Found 2026-07-28 reviewing the fresh operator's landed diff (`6e7b03738`).

## The smell

`script/seon/fresh_operator.clj:382-401` (`stop-form`) resolves
`(malli.instrument/-original (var seon.cluster/stop!))` and calls THAT
instead of the var. Digging the uninstrumented original out of malli's
internals is a workaround shape: if the instrumented var worked, the
form would just call `seon.cluster/stop!`. The likely cause is that
`stop!`'s malli input schema refuses the live instance map (which
carries process-local objects — connection, flow graph — that a
declared schema may not admit), and the lane routed around the
validation instead of fixing the schema or the call.

Symptom-side risk: any boot path that did not instrument leaves
`-original` without a stored original (behavior at that point is
malli-internal), and the operator is now coupled to instrumentation
internals for a plain shutdown.

## Acceptance

`stop-form` calls `seon.cluster/stop!` through the var, and the
instrumented call passes — which means either `stop!`'s input schema
honestly admits the live instance shape, or the schema/caller is fixed
at the owner. One regression: a booted-and-instrumented cluster stops
through the plain var call. Remove the `malli.instrument` require from
the form. (Related magic number in the same form: the
`(Thread/sleep 100)` before `System/exit` is a flush backstop — judge
it when fixing; the prepl :ret has already been written by then.)

## Resolution

Resolved by `7ccd1347a`.

The historical refusal named one exact mismatch:
`:seon.render.web/served` was a disallowed key. PID 84702 had started
before `f88b537cf` landed and retained the older instrumented
`:seon.boot/instance` snapshot while the hot-loaded instance producer
already returned `:seon.render.web/served`. The current schema from
`f88b537cf` already admits that field as the concrete
`:seon.render.web/server` shape, along with every other process-local
field and `:seon.boot/ready-ms`. Widening it again, especially to
`:any`, would hide the real schema-snapshot drift.

`stop-form` now calls `seon.cluster/stop!` directly. It no longer
requires `malli.instrument` or reaches into Malli for the original
function.

The 100 ms exit sleep is deleted. The evaluated form only stops the
instance and returns `:stopped`. After the operator client has read the
io-prepl `:ret` event—the observable socket-flush boundary—it derives
whether any advertisement for that PID remains. For an empty JVM it
sends SIGTERM and awaits `ProcessHandle.onExit`; the five-second
foreign-process deadline is a loud backstop, not the completion
mechanism.

## Verification

Live drive on 2026-07-28:

- bare `start` launched `default`;
- the next bare `start` generated `exp-1`;
- both advertisements named PID 8665;
- `stop exp-1` returned `path=prepl`, and `default` remained alive;
- `stop default` returned `path=prepl`, then reported
  `empty JVM pid 8665 exited`;
- final `status` reported `0/0 clusters alive`, and `ps -p 8665`
  returned no process.

The ordinary var call remained instrumented throughout. `bin/test`
completed with exit status 0 after the live drive.
