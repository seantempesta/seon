# Clocks

Time is measured and recorded, and decides only at the owner's exceptions. `bin/laws clocks` lists every function in `src/` and `test/` whose code (strings and comments left out) sleeps, takes a timeout, holds a deadline or names a `*-MS` constant, and fails on one not named under a "Time decides" heading below; `bin/laws measures` lists every function in `src/` that reads a clock (`currentTimeMillis`, `nanoTime`, `now-ns`, `now-ms`) and fails on one this page does not name. The marks are text, so a bound passed under another name (a 3-arity `deref`, `.join n`) is not seen: such a site is named under [decides by time, unmarked](#decides-by-time-unmarked), and the fix is the mark. A list item names its site before its first colon and its helpers after it; a site whose mark is gone leaves this page in the same commit. Catches are judged the same way in [failure points](failure-points.md).

## If nothing has changed, we do nothing

An idle system does no work: no proc steps, renders, pings, flushes or polls happen unless a message arrived. Work runs because it arrived, is admitted by its measured workload and parks when there is none. A proc reports its problems as facts and its changes as events, so health is the absence of open problem facts ([errors](errors.md#problems)) and nothing is polled to learn it; the `idle` check step holds it ([checks](checks.md#modes)). A clock, sleep, poll, deadline or fixed slot standing in for a dependency or a workload is the mistake this rules out (owner, 2026-10-30: "timeouts just mean we have the wrong design (most of the time)"): a wait ends on its value or on its producer's typed error ([errors](errors.md#nothing-crashes-and-nothing-hangs)).

## Time decides (the owner's exceptions)

The sites where the owner allowed time to decide: the delayed insert, the one timer, the cron, the kernel's TCP keepalive, and a run ended far past its measured time once its evidence is captured.

- `seon.fx/wake-later`: the delayed insert, with its helpers `seon.fx/refill`, `seon.fx/admit`, `seon.fx/dispatch` and `seon.fx/owner-proc`. The provider's rate is a token bucket (halved by a rate-limit outcome, raised a twentieth toward the configured rate by any other); a request the bucket cannot cover yet is put back on the owner's `:tick` port once it refills, by `seon.step/put-after!`.
- `seon.step/put-after!`: the one timer: a go block parks on a core.async `timeout`, then puts the message. The delayed insert, the cron's tick and root's stall check arm through it (`seon.step-test/timer-threads`).
- `seon.step/timer`, `seon.step/arm!` and `seon.step/fired!`: a self-timer's armed flag, a host object in the proc's state, so a step that took the timer's message and then threw cannot leave the timer armed for good.
- `seon.cron/cron-proc`, with `seon.cron/next-fire-ms` and `seon.cron/epoch-ms`: the owner's one schedule. At `:seon.config.proofs/full-run-at` (UTC) it puts one full-run request for main on the test service's queue and arms the next day's; `epoch-ms` is its one clock read.
- `bin/check`'s step bound ([decisions](decisions.md#a-run-far-past-its-measured-time-is-ended-after-its-evidence-owner-2026-11-10)): a `bin/prove` step's whole run, every phase of it (`bounded` in `bin/check`), past `:seon.config.check/step-end-factor` times the median of its measured runs (floored at `:seon.config.agent/stall-ms`; a step never measured takes the largest median, `seon.proofs/step-bounds`) is captured by `bin/evidence` (process tree, samples, fiber dumps, `seon.system/stall-state`), then ended and FAILED, `STEP-OVERRAN`, with the load: a step past its bound is a bug, recorded as an error and fixed.
- `bin/stalls` for a test `bin/test` runs: past its end bound (the step-end factor times its last recorded run, floored at the factor times `:seon.config.agent/stall-ms`) its shard's evidence is captured into the `-overran` directory, `OVERRAN <test> <ms>` is written to the shard's log and the shard is ended (`bin/end-tree`). ```test-runner/overran!``` (`bin/test --overran`) makes that test a signal with no verdict stored, the run red, and runs the shard's unfinished tests in a new shard.
- `seon.system/stall-state`: its one Flow ping is bounded by `:seon.config.check/ping-ms`; a proc mid-step that long is missing from its `:answered`.

## Time decides (a check's measurement, and a check's ping)

The check harness (`seon.check`, `seon.e2e`) and the tests are read too, so a wait there that stands for an event is the event instead ([machine](machine.md#builds-and-checks)).
- `seon.check/scale-work`: the window between its readings of memory and footprints (`:seon.config.check/sample-ms`); nothing waits on a reading.
- `seon.check/idle`: the window over which a check measures that something does not happen, or what it costs (`:seon.config.check/hold-ms`, `:seon.config.check/pages-ms`, idle's argument).
- `seon.check/ping`, `seon.check/system-check`: Flow's ping bounded by `:seon.config.check/ping-ms`; a proc that has not taken its control message is recorded `:blocked` (a missing expected proc is `:no-answer`).

## Time decides (a test's subject)

Tests whose subject is itself a timeout or a measurement window.

- `seon.step-test/timer-threads`: arms long timers to count the threads they hold.
- `seon.check/fork-cases`, `seon.lib-test/fork-case`: a timeout in a forked child, run in a fresh process (`target/seon forks CASE`).
- `seon.procs-test/notice`: a hand-made commit notice whose `:ns` only a latency stat reads.
- `seon.profile-test/allocating-thread`: a window measuring another thread's allocation, and a sleeping call showing ticks count work, not waiting.

## Time decides, owed its owner's fix

Each is a defect named with the task that removes it; a new site is red, and a fixed one leaves this list in the fix's commit.
- `seon.system/start!` (Flow's `:stop-timeout-ms`, `:seon.config.root/flow-stop-ms`; a proc still running is named in stop!'s `:graph`): task `seon.system/shutdown-and-death-are-dataflow`. An agent with an eval in flight is interrupted first (`seon.agent-proc/stop!`).
- `seon.system/api-proc` (the stall timer): with `seon.system/back-op` (its `:stall-check`) and `seon.agent-proc/agent-proc` (the `:last-frame-ms` it compares), task `seon.system/a-stall-is-past-the-requests-own-bound`.
- `bin/check`'s one capture of its steps at 600 s (`bin/evidence`): an observation that ends nothing.
- `bin/stalls`' stall bound: every second it reads each running shard's last `started` line, and past `:seon.config.proofs/stall-factor` times the test's last recorded run (floored at `:seon.config.agent/stall-ms`) records `STALLED <test> <ms>` once; it ends nothing. Same task.
- `seon.web/web-proc` (the HTTP adapter's keep-alive and write timeouts, literals): task `seon.web/web-server-threads-are-joined-at-stop`.

## Decides by time, unmarked

A bound passed under another name, which the marks do not see; each is owed its mark or its fix.
- `seon.check/lifecycle`: a 3-arity `deref` over `:seon.config.check/hold-ms`, a measurement window.

## Time decides nothing here: clock reads named as data

Names that look like clock reads but are data.

- `seon.model-test/stop-now!`: `:drain-timeout-ms 0`, no drain wait.
- `seon.program-test/var-row`, `seon.render-test/task` and `seon.db-test/attr-db`: clock reads quoted as data in the forms after them.
- `seon.render/component-edits`: passes editscript `:vec-timeout nil`, so no clock decides a diff (`editscript.util.common/with-vec-deadline`).
- `seon.render-test/no-position?`: the test after it asserts `:vec-timeout nil` holds no deadline.
- `seon.check/hidden-inputs`, `seon.check/named-test-inputs`: the names the `test-inputs` step reads test forms against.

## The machine's load admits work

A reading of how busy the machine is now, never of when.
- `seon.prove/machine-load`, `seon.prove/grant` (with `seon.lib/load-average`, libc's `getloadavg`): with `:seon.config.proofs/max-load` set (per machine, in `config/local.edn`), the test service grants an item only while the 1-minute load is under it ([merging](merging.md#the-test-service)). It is read only in a step the service already takes, and with nothing running one item is always granted, so the queue never stalls. Admission only, never a verdict; shown as `:load`.

## Time orders a queue (measured workload)

A measured time chooses which work goes first, never whether a result is green.
- `seon.fx/slow-tests`: a test at `:seon.config.proofs/slow-test-ms` or over runs last ([merging](merging.md#a-slow-test-runs-last)).
- `seon.proofs/shards`: shards are dealt by each test's median measured ms (```seon.fx/typical-ms```); a test never measured counts as the mean.
- bin/check's queue: a full run's steps start longest first by the last full run's `STEP.time`; an unmeasured step starts first.

## Time measured (recorded, never compared to decide)

Clock reads whose value is recorded or shown, never compared to decide.

- `seon.profile/now-ns`, `seon.profile/ms-since`: the one clock read ([machine](machine.md#the-clock)).
- `seon.step/event`, `seon.agent-proc/agent-in`, `seon.system/end-agent`, `seon.system/record-death`, `seon.system/set-agent`, `seon.system/stall-task`, `seon.system/previous-end`, `seon.reconcile/judge-tx`, `seon.reconcile/start`, `seon.reconcile/dead-tx`, `seon.server/attach!`, `seon.server/held`, `seon.writer/main-commit`, `seon.writer/stored!`, `seon.writer/writer-proc`, `seon.repl/wrap-caught`, `seon.web/render-in`: a wall-clock stamp on an event, a death, an end, a problem, an error, a commit or a dashboard chunk.
- `seon.fx/stored-results`: sets a verdict file's mtime to now, so the cache evicts least recently used ([merging](merging.md#verdicts-are-a-cache)).
- `seon.profile/measure!`: the one measurement of a call ([cost](cost.md)); its wall ns are a form's recorded ms, its CPU ns what a cost proof shows.
- `seon.proofs/mg-run`: one generative check's ms, which orders the next deal into shards.
- `seon.repl/record!`, `seon.render.proc/web-answered`: an id made unique by the clock read, and a stream event's stamp.
- `seon.repl/serve!`: the time an agent proc's defect is noted in its error fold (`seon.error/note`'s first and last ms), which orders nothing.
- `seon.repl/page-walk`, `seon.turn/act!`, `seon.turn/work!`, `seon.turn/observe`, `seon.turn/context-block`, `seon.model/openai-compat`, `seon.model/scripted`, `seon.fx/build`, `seon.fx/run-on-checkout`, `seon.fx/run-item`, `seon.fx/worker-proc`, `seon.fx/done-of`, `seon.fx/stream-step`, `seon.vector/start!`, `seon.vector/finished`, `seon.contract/instrument!`, `seon.agent/clone!`, `seon.agent/run-tests-forked`, `seon.proofs/run-tests!`, `seon.db/compact-if-due!`, `seon.db/retain!`, `seon.db/collect!`, `seon.fork-server/serve`, `seon.system/boot!`, `my.root/import!`, `seon.system/built-program`, `seon.server/root-eval`: a duration recorded on the work's own fact, event or reply.
- `seon.system/seed-program!`: the seed's duration, in its answer and through `seon.system/import-tree!` on the `:seon.system/source` fact ([machine](machine.md#a-root-boots-from-a-seeded-store)).
- `seon.system/state`: the writer's transactions per second, shown.
- `seon.system/stop!`: the stop fact's `:at-ms` and the stop's duration; it waits on answers, none on a clock.
- `seon.render.proc/pass`: a render pass's duration and the events' age, shown.
- `seon.agent/handle!`, `seon.system/ext-op`, `seon.system/start-spawn`, `seon.system/back-op` (a Back to here's `:seon.agent/started-ms`), `seon.reconcile/advance`, `seon.reconcile/reconciler-proc`: stamps and durations.
- `seon.agent/init!`, `seon.agent/hello!`: a spawn's phases (`seon.agent/spawn-phases`), shown on root's `:ready` event.
- `seon.system/deliver-message`: the stamp of the problem recorded for a send to an agent main does not have.
- `seon.system/message-write`, `seon.reconcile/write` and `seon.turn/tell-error!`: `:seon.message/sent-ms`, which orders messages.

The thread CPU clock (`seon.profile/cpu-ns`) orders nothing and carries no mark; [cost](cost.md#what-is-measured) says what it measures.
