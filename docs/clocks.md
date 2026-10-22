# Clocks

Time is measured and recorded, and decides only at the owner's three exceptions; `bin/dragons clocks` and `bin/dragons measures` name every place in `src/` that reads or waits on a clock.

Work runs because it arrived and parks when there is none; a missing answer is a recorded
fact (stuck, stalled, dead), never a timer's guess. The exceptions are the cron proc, a
message's delayed insert, and the kernel's TCP keepalive ([AGENTS.md §4](../AGENTS.md)).

`bin/dragons clocks` lists every function in `src/` whose code (strings and comments left
out) sleeps, takes a timeout, holds a deadline or names a `*-MS` constant, and fails on one
not named under a "Time decides" heading below. `bin/dragons measures` lists every function
that reads a clock (`currentTimeMillis`, `nanoTime`, `now-ns`, `now-ms`) and fails on one
this page does not name. A function named only under "Time measured" that starts to decide
by time is red under `clocks`. The marks are text: a bound passed under another name (a
3-arity `deref`, `.join n`, a literal compared to a duration) is not seen, as
`seon.agent/transact!`, `seon.agent/mem-mb` and `seon.render.proc/ordered` show below;
review catches it, and the fix is the mark, not an exemption. Catches are judged the same
way in [DRAGONS](DRAGONS.md).

## Time decides (the owner's exceptions)

- `seon.fx/wake-later`, `seon.fx/refill`, `seon.fx/admit`, `seon.fx/dispatch`, `seon.fx/owner-proc`: the delayed insert. The provider's rate is a token bucket (halved by a rate-limit outcome); a request the bucket cannot cover yet is put back on the owner's `:tick` port once it refills, by one core.async `timeout`.

## Time decides, owed its owner's fix

Each is a defect named with the task that removes it, so the census stays a ratchet: a new
site is red, and a fixed one leaves this list in the fix's commit.
- `seon.root/start!` (Flow's `:stop-timeout-ms`), `seon.root/stop!` (timed derefs and a `.join`), `seon.agent-proc/await-dead` (the SIGKILL ladder), `seon.fork-server/main` and `seon.sock/recv-within!` (the 100 ms poll by which a child's death is seen): task `seon.root/shutdown-and-death-are-dataflow`.
- `seon.root/api-proc` (the stall timer), `seon.root/back-op` (its `:stall-check`), `seon.agent-proc/agent-proc` (the `:last-frame-ms` the stall compares): task `seon.fences/a-stall-is-past-the-requests-own-bound`.
- `seon.root/agent-health`, `seon.root/health` (`:unknown` after a ping's 1000 ms): task `seon.fences/health-is-answered-and-failover-recorded`.
- `seon.writer/flusher-proc` (sleeps out the flush window), `seon.writer/writer-proc` (the `:since` it decides from), `seon.agent/transact!` (commits after the flush window): task `seon.writer/durability-is-continuous-group-commit`.
- `seon.render.proc/RETIRE-MS`, `seon.render.proc/order`, `seon.render.proc/ordered` (an ended agent leaves the grid by elapsed time; the order is re-derived when a second passed), and `seon.render.proc/system-value` (carries the bound to the tile's caption): task `seon.render/an-ended-agent-leaves-the-grid-by-an-event`.
- `seon.web/web-proc` (the HTTP adapter's keep-alive and write timeouts, literals): task `seon.web/web-server-threads-are-joined-at-stop`.
- `seon.agent/mem-mb` (memory re-read at most once a second, a cache keyed by time): task `seon.agent/memory-is-read-when-asked`.

## Time measured (recorded, never compared to decide)

- `seon.lib/now-ns`, `seon.lib/ms-since`, `seon.profile/now-ns`, `seon.sock/now-ns`: the clock reads themselves; their callers are judged here one by one.
- `seon.step/event`, `seon.agent-proc/agent-in`, `seon.root/end-agent`, `seon.root/record-death`, `seon.root/set-agent`, `seon.reconcile/judge-tx`, `seon.reconcile/start`, `seon.reconcile/dead-tx`, `seon.entrance/attach!`, `seon.writer/main-commit`, `seon.repl/wrap-caught`, `seon.web/render-in`: a wall-clock stamp on an event, an attempt, a death, a problem, an error, a commit or a dashboard chunk.
- `seon.repl/record!`, `seon.web/http-in`: a record's id made unique by the clock read.
- `seon.turn/act!`, `seon.turn/work!`, `seon.turn/observe`, `seon.turn/context-block`, `seon.model/openai-compat`, `seon.model/scripted`, `seon.fx/build`, `seon.fx/lane-proc`, `seon.contract/instrument!`, `seon.cost/once`, `seon.repl/wrap-cost`, `seon.agent/clone!`, `seon.agent/run-tests-forked`, `seon.db/collect!`, `seon.fork-server/server-step`, `seon.root/import-source!`, `seon.entrance/root-eval`: a duration recorded on the work's own fact or reply.
- `seon.root/state`: the writer's transactions per second over the last second, shown.
- `seon.render.proc/render-proc`, `seon.render.proc/pass`: a render pass's duration and the events' age, shown on the dashboard.
- `seon.agent/handle!`, `seon.root/ext-op`, `seon.reconcile/advance`, `seon.reconcile/reconciler-proc`: stamps and durations.
