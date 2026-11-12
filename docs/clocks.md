# Clocks

Time is measured and recorded, and decides only at the owner's four exceptions; `bin/laws clocks` and `bin/laws measures` name every place in `src/` that reads or waits on a clock.

Work runs because it arrived and parks when there is none; a missing answer is a recorded
fact (blocked, stalled, dead), never a timer's guess. The exceptions are the cron proc, a
message's delayed insert, the kernel's TCP keepalive, and a run ended far past its measured time once its evidence is
captured ([below](#time-decides-the-owners-exceptions)).

`bin/laws clocks` lists every function in `src/` whose code (strings and comments left
out) sleeps, takes a timeout, holds a deadline or names a `*-MS` constant, and fails on one
not named under a "Time decides" heading below. `bin/laws measures` lists every function
that reads a clock (`currentTimeMillis`, `nanoTime`, `now-ns`, `now-ms`) and fails on one
this page does not name. A function named only under "Time measured" that starts to decide
by time is red under `clocks`. The marks are text: a bound passed under another name (a
3-arity `deref`, `.join n`, a literal compared to a duration) is not seen: such a function is
named under "Decides by time, unmarked" below, which review keeps, and the fix is the mark, not
an exemption. A list item names its site before its first colon, and the site's helpers after it; a
site whose mark is gone must leave the page in the same commit (no check catches it yet: the
health problem classes, ``jolt/health``). Catches are judged the same
way in [failure points](failure-points.md).

## If nothing has changed, we do nothing

An idle system does no work: no proc steps, renders, pings, flushes or polls happen unless a message arrived. Work runs because it arrived, is admitted by its measured workload ([below](#time-orders-a-queue-measured-workload)) and parks when there is none. A proc reports its own problems as facts and its changes as events, so health is the absence of open problem facts ([errors](errors.md#problems)) and nothing is polled to learn it; the `idle` check step holds it, an idle root with a tab open stepping nothing ([checks](checks.md#modes)). A clock, timer, sleep, poll, deadline, ping or fixed slot standing in for a dependency or a workload is the mistake this rules out (owner, 2026-10-30: "timeouts just mean we have the wrong design (most of the time)"): a wait ends on its value or on its producer's typed error ([errors](errors.md#nothing-crashes-and-nothing-hangs)).

## Time decides (the owner's exceptions)

The sites where time decides because the owner allowed it, the provider's delayed retry among them.

- `seon.fx/wake-later`: the delayed insert, with its helpers `seon.fx/refill`, `seon.fx/admit`, `seon.fx/dispatch` and `seon.fx/owner-proc`. The provider's rate is a token bucket (halved by a rate-limit outcome, raised by a twentieth toward the configured rate by every other, and a request is admitted only while a worker is free and the bucket holds its estimate); a request the bucket cannot cover yet is put back on the owner's `:tick` port once it refills, by `seon.step/put-after!`.
- `seon.step/put-after!`: the one timer: a go block parks on a core.async `timeout`, then puts the message. The delayed insert, the cron's tick and root's stall check (`seon.system/api-proc`, below) all arm through it. Measured on the current Jolt pin, 25 and 1000 pending timers hold the same thread count (21) for this form and for the callback `take!` it replaced, so the saving is one mechanism, not threads (`seon.step-test/timer-threads`).
- `bin/check`'s step bound (owner, 2026-11-10, [decisions](decisions.md#a-run-far-past-its-measured-time-is-ended-after-its-evidence-owner-2026-11-10)): a step of a `bin/prove` run past its bound (the plan's `step-bounds`: `:seon.config.check/step-end-factor` times the median of its measured runs, the `:ms` its green verdicts keep, floored at `:seon.config.agent/stall-ms`; a step never measured has none) is captured by `bin/evidence` first (the process tree, each process's sample and files, its fiber dumps, and its root's Seon state, `seon.system/stall-state`, asked of the server each check step's root serves on the port it writes beside its store), then ended, and it FAILS, `STEP-OVERRAN`, named with its CPU seconds and the load, its store kept and no green stored: a step or a test past its bound is a bug, recorded as an error and fixed (owner, 2026-11-12: "A test killed for running too long is a bug and it should be recorded as an error and fixed ... we shoudln't do nothing and not record anything"; `seon.check-test/a-step-past-its-bound-is-ended-with-its-evidence-and-fails`). A test `bin/test` runs is held to the same rule by `bin/stalls`: past its end bound (the plan's `stall-bounds`, third word: `:seon.config.check/step-end-factor` times its own last recorded run, floored at the factor times `:seon.config.agent/stall-ms`, which also bounds a test with no record), its shard's evidence is captured by `bin/evidence` into the `-overran` directory beside the shard's log, `OVERRAN <test> <ms>` is recorded in that log and the shard is ended (`bin/end-tree`, then the shard); `bin/test` stores the test at its digest as an error with that line, since a test killed for running too long is a bug to record and fix (owner, 2026-11-12: "it should be recorded as an error and fixed ... we shoudln't do nothing and not record anything"; `bin/test --overran`, ```test-runner/overran!```, `seon.runner-test/an-overrun-is-recorded-as-an-error-and-the-rest-runs-again`), and runs the shard's tests that never ended in a new shard, so the run ends. A process item of the test service (```seon.fx/run-item```) with a `:bound` (```seon.prove```'s plan, from its measured ms by the same factor) runs through `bin/bounded`: the command in a process group of its own (`bin/held`), past its bound its evidence captured by `bin/evidence`, then the whole group ended with SIGKILL, an orphan reparented to pid 1 included (`bin/end-tree` walks by parent and spares its caller's group, so a forked shard runner held a prove 22 minutes, 2026-11-11), and the item answers the step's signal, `:overran`, with no verdict; what a bounded command leaves in its group is ended when it ends (```seon.fx-test/a-process-item-past-its-bound-is-a-signal-and-nothing-of-it-runs-on```). `seon.system/stall-state`'s one Flow ping is bounded by `:seon.config.check/ping-ms`: a proc mid-step that long is missing from its `:answered`.
- `seon.step/timer`, `seon.step/arm!` and `seon.step/fired!`: a self-timer's armed flag, a host object the proc's state holds, so a step that took the timer's message and then threw (Flow keeps the state from before it) cannot leave the timer armed for good: root's stall check and the delayed insert's `:tick` arm through it. With the flag in the state, root's stall check stayed armed and was never armed again for the life of root (batch 15's problems step waited 14.5 min on a stall it never recorded; the proof is root's step-fn test of a step that throws after a stall check). The cron's tick is armed in the step that takes it, before anything can throw.
- `seon.cron/cron-proc`, with `seon.cron/next-fire-ms` and `seon.cron/epoch-ms`: the cron proc, the owner's one allowed schedule. At the time of day `:seon.config.proofs/full-run-at` names (UTC), `seon.step/put-after!` puts a `:tick` on its port, and the proc puts one full-run request for main on the test service's queue, then arms the next day's: it decides nothing but when the message is put. `epoch-ms` is its one clock read, to find that time. A root started after the time waits for the next day's.

## Time decides (a check's measurement, and a check's ping)

`bin/laws clocks` reads `src` and `test`: the check harness (`seon.check`, a driver in `test/`, and `seon.e2e`) and every test, so a wait there that stands for an event is the event instead ([machine](machine.md#builds-and-checks)).
- `seon.check/scale-work`: the window between its readings of the machine's available memory and its processes' footprints while ten agents work (`:seon.config.check/sample-ms`); the readings are the measurement, nothing waits on one.
- `seon.check/backpressure`, `seon.check/scale`, `seon.check/idle`: the window over which a check measures that something does not happen or what it costs (`:seon.config.check/hold-ms`: a paused agent's request unanswered, a paused proc's flood and a paused writer's commits held; `:seon.config.check/pages-ms`: memory the kernel returns after the kill; idle's window, bin/check's argument). The measurement, never a dependency: load only makes what it measures likelier.
- `seon.check/ping`, `seon.check/system-check`: Flow's ping takes a bound (`:seon.config.check/ping-ms`); a proc that has not taken its control message by then is mid-step that long, and the check records it `:blocked` (an expected proc missing from a graph ping is reported under `:no-answer`), never reads it as nil.

## Time decides (a test's subject)

Tests whose subject is itself a timeout or a measurement window, so the clock read there is what they test.

- `seon.step-test/timer-threads`: arms timers (long ones, never fired) to count the threads they hold, the timer being its subject.
- `seon.check/fork-cases`, `seon.lib-test/fork-case`: what they test is a timeout in a forked child (core.async's `timeout` fires there; the child's wait has a bound so a child whose runtime threads are gone ends), run in a fresh process (`target/seon forks CASE`) and asserted by the test.
- `seon.procs-test/notice`: a writer's commit notice made by hand for a reader's test, whose `:ns` (when the notice was made) only the reader's latency stat reads; nothing waits on it.
- `seon.profile-test/allocating-thread`: the window over which it measures another thread's allocation, and a call that sleeps to show ticks count work, not waiting.

## Time decides, owed its owner's fix

Each is a defect named with the task that removes it, so the laws check stays a ratchet: a new
site is red, and a fixed one leaves this list in the fix's commit.
- `seon.system/start!` (Flow's `:stop-timeout-ms`, `:seon.config.root/flow-stop-ms`: what flow/stop waits for a proc's exit; a proc still running is named in stop!'s `:graph`, printed on ROOT STOPPED): task `seon.system/shutdown-and-death-are-dataflow`. Root's stop parks on the drain and an agent's end on its socket's close, no bound (2026-10-30: an agent with an eval in flight is interrupted first, `seon.agent-proc/stop!`; the timed derefs, the SIGKILL ladder and their four keys deleted).
- `seon.system/api-proc` (the stall timer): with `seon.system/back-op` (its `:stall-check`, a clock read compared to the bound) and `seon.agent-proc/agent-proc` (the `:last-frame-ms` the stall compares), task `seon.system/a-stall-is-past-the-requests-own-bound`.
- `bin/check`'s one record of its steps at 10 minutes (`bin/evidence`): an observation, once, which ends nothing.
- `bin/stalls`, which `bin/test` runs beside its shards (observed outside any root): every second it reads each running shard's last `started` line and the age of its log, and past that test's bound (`:seon.config.proofs/stall-factor` times its own last recorded run, floored at `:seon.config.agent/stall-ms`, which also bounds a test with no record) prints and records `STALLED <test> <ms>` once per test. An observation only: the stall bound ends nothing (a test far past its measured time is ended by the step bound's rule, [above](#time-decides-the-owners-exceptions)). It goes with the same task.
- `seon.web/web-proc` (the HTTP adapter's keep-alive and write timeouts, literals): task `seon.web/web-server-threads-are-joined-at-stop`.

## Decides by time, unmarked

A bound passed under another name, which the marks do not see; each is owed its mark or its fix.
- `seon.check/lifecycle`: its bound is a 3-arity `deref` over `:seon.config.check/hold-ms`, a check's measurement window like `seon.check/backpressure`'s.

## Time decides nothing here: clock reads named as data

Names that look like clock reads but are quoted as data, so no time decides at these sites.

- `seon.model-test/stop-now!`: stops a test's local HTTP server with `:drain-timeout-ms 0`, no drain wait, so the word is a setting of nothing.
- `seon.program-test/var-row` (the form after it records a guarded function's source, `Thread/sleep` and a timed `deref` as text), `seon.render-test/task` (the test after it names the removed `SAMPLE-MS` to show it is gone) and `seon.db-test/attr-db` (the test after it, `an-idle-store-sets-no-timer`, passes `:stale-timeout` to a supervisor and counts the timers pending, expecting none): clock reads quoted as data.
- `seon.render/component-edits`: passes editscript `:vec-timeout nil`. Left to its default of one second, a clock would decide what a diff comes out as, by `:quick` as by `:a-star` (`editscript.util.common/with-vec-deadline`); nil reads no clock.
- `seon.render-test/no-position?`: the test after it asserts that `:vec-timeout nil` holds no deadline.
- `seon.check/hidden-inputs`, `seon.check/named-test-inputs`: the lists the `test-inputs` check step reads test forms against (a clock read, a sleep, a timeout among the names they hold); they name those vars as data and read no clock.

## The machine's load admits work

A reading of the machine, not a clock: what it reads is how busy the machine is now, never when, and nothing waits on it or is woken by it.
- `seon.prove/machine-load`, `seon.prove/grant` (with `seon.lib/load-average`, libc's `getloadavg`): with `:seon.config.proofs/max-load` set, the test service grants an item only while the machine's 1-minute load average is under it ([merging](merging.md#the-test-service)). It is read only inside a step the service is already taking (an ask arrived, an item ended), once a grant pass, and only when it decides (an item to grant, one running): never polled, no timer. With nothing running one item is always granted whatever the load, so the queue never stalls waiting for a step nothing would send: the item's end is the next step, and the next reading. It decides admission only, never a verdict, and the reading is shown in the state the service reports (`:load`).

## Time orders a queue (measured workload)

A measured time chooses which work goes first, never whether a result is green (work is admitted by its measured workload).
- `seon.fx/slow-tests`: a test measured at `:seon.config.proofs/slow-test-ms` or over is left out of a run until it is fast ([merging](merging.md#a-slow-test-runs-last)): what is scheduled, never a verdict.
- `seon.proofs/shards`: bin/test's shards are dealt by each test's typical measured ms (the median of its newest cached results', ```seon.fx/typical-ms```), so they end together; a test never measured counts as the mean.
- bin/check's queue: a full run's steps start longest first by the wall seconds of the last full run (its `STEP.time` files); a step not measured yet starts first.

## Time measured (recorded, never compared to decide)

Clock reads whose value is recorded or shown and never compared to decide anything.

- `seon.profile/now-ns`, `seon.profile/ms-since`: the one clock read ([machine](machine.md#the-clock)); their callers are judged here one by one.
- `seon.step/event`, `seon.agent-proc/agent-in`, `seon.system/end-agent`, `seon.system/record-death`, `seon.system/set-agent`, `seon.system/stall-task`, `seon.system/previous-end`, `seon.reconcile/judge-tx`, `seon.reconcile/start`, `seon.reconcile/dead-tx`, `seon.server/attach!`, `seon.server/held` (the methods of `op-answer` after it: the stamp on a refused `:record`'s error), `seon.writer/main-commit`, `seon.writer/stored!`, `seon.writer/writer-proc` (the `:since` on its dirty notice), `seon.repl/wrap-caught`, `seon.web/render-in`: a wall-clock stamp on an event, a death, a root's end, a problem, an error, a commit or a dashboard chunk.
- `seon.fx/stored-results`: the mtime of a verdict it reads, set to now so the verdict cache's eviction order is least recently used ([merging](merging.md#verdicts-are-a-cache); the owner's ruling, 2026-10-29); recorded on the file, no wait or deadline.
- `seon.profile/measure!`: the one measurement of a call ([cost](cost.md)): its wall ns are what the REPL records as a form's ms, its CPU ns what a cost proof times; shown, never compared.
- `seon.proofs/mg-run`: the ms one generative check took (`:ms` of its verdict, which orders the next run's deal of the checks into shards, never what a check proves).
- `seon.repl/record!`: a record's id made unique by the clock read.
- `seon.render.proc/web-answered`: a UI call's answer, its record's id made unique by the clock read and its stream event stamped.
- `seon.repl/page-walk`, `seon.turn/act!`, `seon.turn/work!`, `seon.turn/observe`, `seon.turn/context-block`, `seon.model/openai-compat`, `seon.model/scripted`, `seon.fx/build`, `seon.fx/run-on-checkout`, `seon.fx/run-item` (a session's item's ms, reported in its answer; a step's bound is bin/check's to judge, read from its overran list), `seon.fx/worker-proc`, `seon.fx/done-of` and `seon.fx/stream-step` (a streamed call's timing, the same outcome fields), `seon.vector/start!` and `seon.vector/finished` (`System/currentTimeMillis` for the embedding helper's `:started-ms` and `:exited-ms`, shown in its worker's ping; display-only, nothing reads them), `seon.contract/instrument!`, `seon.agent/clone!`, `seon.agent/run-tests-forked`, `seon.proofs/run-tests!`, `seon.db/compact-if-due!`, `seon.db/retain!`, `seon.db/collect!`, `seon.fork-server/serve`, `seon.system/boot!` (the boot's open, seed and digest ms), `my.root/import!` (a re-index's ms on its source fact), `seon.system/seed-program!` and `seon.system/built-program` (a kept index's ms on its source fact), `seon.server/root-eval`: a duration recorded on the work's own fact or reply (`seon.fork-server/serve`'s on its `:applied`, `:fork` and `:static` events, with a commit's lag from the writer's stamp).
- `seon.system/seed-program!`: the seed's duration (`ms-since` its start), recorded in its answer and, through `seon.system/import-tree!` (which takes the start, as a boot passes its program phase's), in the `:seon.system/source` fact, shown; nothing waits or decides by it ([machine](machine.md#a-root-boots-from-a-seeded-store)).
- `seon.system/state`: the writer's transactions per second over the last second, shown.
- `seon.system/stop!`: the stop fact's `:at-ms` and the stop's own duration (`:ms`, on ROOT STOPPED), recorded; it waits on answers, none on a clock.
- `seon.render.proc/pass`: a render pass's duration and the events' age, shown on the dashboard.
- `seon.agent/handle!`, `seon.system/ext-op`, `seon.system/start-spawn`, `seon.system/back-op` (a Back to here's `:seon.agent/started-ms`, which moves the reconciler's index), `seon.reconcile/advance`, `seon.reconcile/reconciler-proc`: stamps and durations.
- `seon.agent/init!`, `seon.agent/hello!`: a spawn's phases (`seon.agent/spawn-phases`: the fork server's marks, then the child's, each with the bytes it allocated), sent with the agent's hello and shown on root's `:ready` event; nothing decides by them.
- `seon.system/deliver-message`: the occurrence stamp of the problem it records for a send to an agent main does not have; nothing decides by it.
- `seon.system/message-write`, `seon.reconcile/write` (the message `tell-tx` writes) and `seon.turn/tell-error!`: `:seon.message/sent-ms`, when the message was written; it orders and nothing decides by it.

The thread CPU clock (`seon.profile/cpu-ns`, what a cost proof's `seon.cost/measure` times through `seon.profile/measure!`) orders nothing and carries no mark; [cost](cost.md) says what it measures.
