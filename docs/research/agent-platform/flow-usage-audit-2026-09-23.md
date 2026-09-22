---
type: research
status: fact-finding and design; no src/test/resources edits
created: 2026-09-23
tags: [agent-platform, flow, concurrency, errors, faults]
---

# Flow usage audit (2026-09-23)

Lane `flow-usage-audit`. This is fact-finding and design only.

**The owner, verbatim.** "Oh yeah we have flow for all the processes. This has a good
error design. Audit our use of flow processes and identify where we can just expand
their usage. This could handle additional concurrency issues too and it has :io and
:compute schedulers."

**Authorities.**

- AGENTS.md "Bounded, event-driven execution" (`AGENTS.md:250-257`).
- AGENTS.md "Each agent owns a flow graph. Procs declare `:io` or `:compute`; no
  central scheduler or dispatcher is added" (`:158`).
- AGENTS.md "No swallowed errors" and "The error policy" (`:269-297`). Its key clause:
  a fault is "committed at the owning boundary and never dropped by an overload
  channel".
- Prior art: [error-route-inspiration-flow](error-route-inspiration-flow-2026-09-23.md).
- Coordination: [one-error-route design](one-error-route-design-2026-09-23.md), whose
  recommended option B is `seon.fault/fault!`.

**Snapshot.** HEAD `bb357f308` plus other lanes' uncommitted hunks, working tree at
2026-09-22 21:50-21:57Z. `cluster.clj`, `turn.clj` and `boot.clj` have moving hunks,
so line numbers are for that tree. Dependencies: core.async `dc35f3e`, Datahike
`fbd1ad2d`.

**Method.** One `rg` pass over `src/` for every concurrency primitive (0.03 s, 324
raw hits), then one for flow construction. Each hit that is live code was read at its
owner. Four read-only JVM probes ran on `default` (pid 70720); they are in §5.

## 0. Findings first (broken today)

| # | defect | evidence | class |
|---|---|---|---|
| D1 | **Nobody reads the work launcher graph's error channel.** A throwing background submission becomes a `::flow/error` output (`src/seon/flow.clj:569-578`). That output goes into the launcher graph's own `error-chan`, which is `sliding-buffer 100`. `start-work-launcher!` returns `::started` (`flow.clj:704`), but no fan-out join is ever made for it (`src/seon/cluster/boot.clj:73-90`; `rg ':error-chan'` finds only the cluster graph and agent graphs). Launcher faults are evicted silently. The submitter's `complete!` still sees the throwable (`flow.clj:431-446`), but the fault route never does. | probe P4: the launcher error-chan exists and has zero readers | swallowed error |
| D2 | **Errors raised in a proc's `::flow/stop` transition are lost by construction.** Flow's `stop` sends the stop command and then closes `error-chan` at once (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:174-183`). Each proc handles the transition afterwards, on its own thread. The outer catch at `impl.clj:317-320` does `>!!` onto a closed channel, which returns false and drops the error. Every Seon stop transition is exposed to this: it releases listeners, closes channels, and delivers completions (`schedule.clj:809-813`, `flow.clj:527`, `flow.clj:1017`, `turn.clj:5487`, `render/web.clj:2647`). | source | swallowed error |
| D3 | **One listener per cluster is false.** `default`'s connection holds three Datahike listeners: `:seon.call-preparation/rows`, `:seon.agent/route`, and one random-uuid key per agent from the schedule proc (probe P1; `schedule.clj:799`, `call_preparation.clj:578`, `wake.clj:505`). With N agents that is 2 + N listeners, and every one runs on the writer's critical path. The call-preparation listener computes `snapshot` (a database derivation) inside the listener (`call_preparation.clj:582`). It also uses one fixed key, so each `watch!` from a fork (`sci/eval.clj:2560`, `:2627`) silently replaces the previous context's listener, and nothing ever unwatches. | probe P1 | stale skill claim; work on the transaction path |
| D4 | **The router does work on the writer thread.** On any `seon.wake`/`seon.listen`/schema datom, `route!` rebuilds its matchers with `(wake-matchers (:db-after report))` inside the listener (`wake.clj:517-524`). That breaks the skill's own never-park/no-query rule (wakes-and-faults.md "Never park"). The catch `offer!`s a bare Throwable to the fault channel and drops the result (`wake.clj:546-547`, `:388`). | source | work on the transaction path; swallowed error |
| D5 | **Faults cross an overload channel.** Every graph's errors reach the committer through core.async's sliding-100 `error-chan` (`impl.clj:101-102`), then a `mult`, then Seon's counted-dropping buffer of 64 (`flow.clj:999`, `:1223-1224`, `cluster.clj:3265`). Six out-of-flow sites `offer!` into that same channel and fall back to `println` (`turn.clj:5373-5376`, `:5396-5399`; `agent.clj:1036-1041`; `render/web.clj:2289-2295`, `:2887`, `:3434`). The error policy forbids this ("never dropped by an overload channel"). | source; probe P3: `committed 0 lost 0`, buffer capacity 65 | policy breach |
| D6 | **`arm!` and `disarm!` hold one monitor over seconds of work.** `(locking routing …)` at `agent.clj:917` wraps `acquire-context!`: a branch open, projection carry, and possibly `fork-cluster-ctx`. At `:1070` it wraps `await-turn-completion!`, which "honestly includes a seconds-long model call" (`cluster.clj:3339-3342`). While any agent disarms, every other arm is blocked, including the armer proc's own. The lock exists only because "direct source installers" race the armer proc (`agent.clj:908-911`). | source | lock for a race |
| D7 | **Unbounded waits on stop.** `disarm-agents!` uses `<!!` on quiescence and completions with no bound (`cluster.clj:3367`, `:3386-3390`, whose docstring is "no sleep or deadline"). `stop-error-fanout!` uses `<!!` twice (`flow.clj:1305`, `:1309`). `await-turn-completion!` with an active backstop does `alts!!` over `[turn-stopped failure-channel]` with no timeout (`agent.clj:1005-1008`). AGENTS: "Every execution surface has a declared bound … names the missing event." | source | missing bound |
| D8 | **Out-of-graph threads whose exit nobody observes.** (a) The turn backstop watcher: a loop on the executor, outside every graph (`turn.clj:5434-5450`). (b) The per-agent schedule timer: a virtual thread that runs `Thread/sleep` and then `offer!` (`schedule.clj:747-756`). Cancelling it is an interrupt, and its exit is not joined. (c) One SSE feed writer virtual thread per browser tab (`render/web.clj:2840-2894`); `web/stop!` does not join these (`:3692-3700`). (d) The http-kit worker executor is created per serve and never shut down on stop (`render/web.clj:3652`, `:3676` bind-failure only). | source | leaked threads; unobserved exit |
| D9 | **Nothing reads the report channels.** Agent graphs' `report-chan` is never read; probe P4 found 25 queued. The cluster fan-out's `application-report-channel` has no consumer (`rg` finds no reader). This is harmless (observational, sliding), but it means ping and `report` are the only live observation today. | probe P4 | dead plumbing |
| D10 | **The turn proc cannot be observed while it works.** A model call and every SCI evaluation run inline in the `:io` turn transform (`turn.clj:4521` `ai/complete`; `submit-evaluation!!` at `:3316` still has no caller, see existing issue `docs/seon/issues/turn-evaluations-bypass-work-submission.md`). Probe P2: `ping-proc :seon.agent/turn` timed out after 3,009 ms during a 51 s open turn, so status shows `:unknown`. Stop waits for the whole call. | probe P2 | overlapping/unobservable work |

Two further findings that are not concurrency defects, in scope for other lanes:

- `seon.issue/note-opened` returns nil on a non-zero git exit (`src/seon/issue.clj:85`).
  That is an unchecked failure-as-data swallow, which belongs to the swallowed-errors
  census.
- `render/web.clj:111-113` binds a real loopback http-kit server for the lifetime of the
  process, only to serve a schema generator.

## 1. Flow census

### Graphs

| graph | constructed at | procs (workload) | executors | error exit | stop/exit observed |
|---|---|---|---|---|---|
| work launcher (per process root instance) | `flow.clj:689` `start-work-launcher!` | `::work-launcher` `:io` (`flow.clj:587`); `::capacity-observer` `:compute` (`:239`) | `:compute-exec` = root task executor (`:654`, `:700`, `:710`); `:io` is core.async's global (existing issue `flow-work-launcher-graph-omits-its-root-io-executor.md`) | **none (D1)** | `proc-stopped` promise delivered from the stop transition, awaited under a bound (`flow.clj:757-765`) |
| cluster graph | `cluster.clj:3228` via `cluster-graph-definition` `:3145-3167` | `:seon.agent/armer` `:io`; `:seon.render.web/render` `:io` | `:io-exec` = `projection-executor` over root `:io` (`cluster.clj:3127-3141`, `:3167`) | `start-error-fanout!` mult → counted-dropping(64) → committer (`cluster.clj:3235-3270`) | completions awaited unbounded (D7) |
| fault graph (per cluster) | `flow.clj:1228` | `::fault-committer` `:io` (`flow.clj:1090`) | `projection-executor` (`flow.clj:1189`) | its own error-chan → `join-fault-committer-errors!` → `println` of class name only (`flow.clj:1135-1180`) | `completion` promise-chan, unbounded `<!!` (`flow.clj:1305`) |
| agent graph (per armed agent) | `agent.clj:956` via `graph-definition` `:498-542` | `:seon.agent/mailbox` `:io` (`:481`); `:seon.agent/turn` `:io` (`turn.clj:5481`); `:seon.agent/schedule` `:io` (`schedule.clj:784`) | `:io-exec` = handle executor (`agent.clj:541-542`) | `join-error-fanout!`: one blocking task per graph moving errors into the cluster fault channel, tagged `{:seon.agent/id …}` (`flow.clj:1266-1295`) | turn-stopped promise under backstop (`agent.clj:993-1041`) |

Every proc is built through `var-process` (`flow.clj:132-181`). It refuses a non-Var
step, a missing or `:mixed` workload, and args without an environment. **No `:compute`
proc does substantive work.** The only one is the capacity observer, so flow's
`compute-timeout-ms` (default 5000, `impl.clj:249`, `:259-260`) bounds nothing that
matters. `var-process` passes no `compute-timeout-ms` (`flow.clj:170-172`).

### Channels and buffers

| channel | buffer | path | verdict |
|---|---|---|---|
| flow `error-chan`, `report-chan` (every graph) | sliding 100 (`impl.clj:101-102`) | error, report | error path: loses silently when unread (D1) or under a burst (D5) |
| cluster fault channel | `CountedDroppingBuffer` 64 (`flow.clj:999`, `cluster.clj:3265`) | **error, durable** | dropping on the durable error path; the policy forbids it (D5) |
| monitor/application taps | sliding 64 (`flow.clj:1217-1221`) | observation | fine (observation) |
| armer, stream, render, runtime-eval, pages | sliding 1 (`cluster.clj:3187-3208`) | payload-free wake | fine: work derives from facts |
| agent wake, episode | `CountedSlidingBuffer` 1 (`agent.clj:127`, `:531`) | payload-free wake, counted | fine |
| schedule kick | sliding 1 (`agent.clj:931`) | payload-free | fine |
| agent `completion` | fixed 1 (`agent.clj:932`) | turn permit | fine: backpressure is intended |
| launcher compute/io submission | `RefusingBuffer` (`flow.clj:304-345`) | admission | fine: refusal is a flat error value delivered to the submitter |
| launcher `::completion` | fixed parallelism+io (`flow.clj:525`) | lifecycle | fine; `offer!` result ignored at `:351`, `:393`, `:407`, `:438`, but the capacity equals the admitted count |
| turn backstop `parts` | sliding 1 (`turn.clj:5422`) | bound extension | fine |
| SSE per-tab tap | sliding 1 (`render/web.clj:2831`) | newest page | fine |

### Ping

Every Seon proc declares a deliberate `:ping-map-fn` projection (`flow.clj:221`, `:516`,
`:1008`; `agent.clj:482`, `:1129`; `schedule.clj:785`; `turn.clj:5482`;
`render/web.clj:2594`). Oversight reads it under a configured timeout and reports a miss
as `:unknown` (`oversight.clj:46-52`, `:100-184`). That is correct, and D10 is what makes
the turn proc's ping miss.

## 2. Census of concurrency outside flow: full site table

Verdicts:

- **EXPAND**: it becomes a proc, a step of an existing proc, or a launcher submission.
- **KEEP**: stays as is, with the reason.
- **DELETE**: the work is unnecessary or already owned elsewhere.

"Route" means where an error ends up today.

| # | site | what | bound | errors today | exit observed | lifecycle tie | verdict | closes |
|---|---|---|---|---|---|---|---|---|
| 1 | `cluster/wake.clj:505-547` `route!` `d/listen` | the one router: matcher rebuild plus offers, on the writer thread | none (runs inside the transaction) | catch → `offer!` of a bare Throwable; result dropped (D4) | n/a (callback) | cluster, via `unlisten!` | **EXPAND**: the listener only `offer!`s `::look` (sliding-1) to a new cluster-graph proc `:seon.cluster.wake/router` `:io`. The router reads `(d/since db basis)`/tx range from its last-handled `t`, rebuilds matchers when needed, and delivers. Loss-free because work derives from facts and the router keeps its basis `t` as proc state. | D3, D4, swallowed listener fault; moves queries off the writer |
| 2 | `schedule.clj:799` per-agent `d/listen` | kicks the schedule proc on relevant attributes | none | none caught | unlisten at pause/stop | agent | **DELETE**: the router (row 1) derives schedule interest (`:seon.schedule.*` attributes) and offers the kick | D3 (N listeners) |
| 3 | `schedule.clj:747-756` virtual timer thread | `Thread/sleep delay` then `offer!` kick | delay | `InterruptedException` → nil | no (interrupt only) | agent (cancel on stop) | **EXPAND/DELETE thread**: `(async/take! (async/timeout d) (fn [_] (async/offer! kick ::kick)))`. That is core.async's own timer; a stale kick is harmless because each pass re-derives `earliest-next-at`. Deletes the thread, the sleep and the interrupt. | D8b; no-`Thread/sleep` rule |
| 4 | `call_preparation.clj:578` `listen!` via `watch!` (`sci/eval.clj:2560`, `:2627`) | re-derives the call-preparation snapshot into an atom, on the writer thread | none | refusal values ignored (`:583`) | never unwatched | none (fixed key, last writer wins) | **DELETE**: its own docstring says "an optimization only … never the correctness boundary" (`:563-568`). The invocation basis comparison remains the correctness path. If the eager refresh is wanted, the router offers a wake to the owner of the context. | D3; state remembered beside the connection |
| 5 | `turn.clj:5414-5452` backstop watcher on the executor | `alts!!` `[cancel parts timeout]`; on timeout, offers the fault | turn-completion backstop | `offer!` + `println` (D5) | no | agent run | **EXPAND**: a `:seon.agent/backstop` `:io` proc in the agent graph. Its in-ports are `parts`/`cancel`, plus a timer kick from `async/timeout` (row 3's shape). When it fires, its transform throws the diagnostic, so the fault reaches the var-process boundary (§3). Flow stop ends it. | D8a, D5 |
| 6 | `turn.clj:4521` `ai/complete` inside the turn transform | provider HTTP (JDK HttpClient timeout, `ai.clj:1338-1340`) | `:seon.ai/timeout-ms` × schedule (`turn.clj:4268-4272`) | declared values, good | yes (inline) | turn proc | **EXPAND**: `flow/submit!` `:io` through the launcher, whose completion re-enters the turn proc as a message on a new in-port. The turn proc stays pingable and stoppable, and interruption follows the launcher's cancel path (`flow.clj:720-732`). This is a turn-state restructure (call/resume split); do it last. | D10, D7 |
| 7 | `turn.clj:4606` `Thread/sleep` retry backoff | finite backoff inside the turn transform | finite schedule | n/a | inline | turn proc | **DELETE** once row 6 lands: the retry becomes a delayed resubmission (`async/timeout` → resubmit). Until then KEEP; it is bounded. | no-sleep rule |
| 8 | SCI evaluation inline in the turn (`submit-evaluation!!` `turn.clj:3316` has no caller) | interpreted evaluation on the turn's `:io` thread | SCI arm time limit | flat values | inline | turn proc | **EXPAND**: call `submit-evaluation!!` → `submit!!` `:compute` (the existing issue `turn-evaluations-bypass-work-submission.md`). The launcher's active-count gate bounds CPU across agents (`flow.clj:537-548`). | admission bound; D10 |
| 9 | `effect.clj:492-529` `dispatch` FutureTask on root `:io` | capability handler, awaited | `:seon.config.eval/time-limit-ms` via `await!` | ExecutionException propagates; cancel on expiry | cancel ≠ exit (AGENTS "timeout is not termination") | request | **EXPAND**: `flow/submit!!` with `::workload :io`. `submit!!` today admits only compute work (`flow.clj:870-876` injects `::compute-submission`), so an `:io` arm of `submit!!` is needed. It gains the launcher's io admission bound (`:seon.config.flow.io/concurrency`) and one settled-exactly-once terminal (`io-terminal!`). | missing admission bound; overlapping work |
| 10 | `effect.clj:886` `flow/submit!` (background) | already a launcher io submission | fresh detached arm | terminal to `complete!`; `::flow/error` output **unread (D1)** | launcher drain on stop | cluster launcher | **KEEP**, and fix D1 | D1 |
| 11 | `flow.clj:1165-1180` `join-fault-committer-errors!` | task on root `:io` draining the committer graph's error-chan to stderr | none | prints the class name only | completion chan | fault graph | **DELETE** with the committer (§3, option B) | swallowed error |
| 12 | `flow.clj:1266-1295` `join-error-fanout!` | one blocking task per agent graph, error-chan → fault channel | none | `>!!` never blocks (dropping buffer) | completion chan, not awaited by `disarm!` | agent graph | **KEEP the shape, change the sink**: one drain per graph, synchronous `fault!` per residual error (§3). Await its completion in `disarm!`. | D5 |
| 13 | `flow.clj:399-411`, `:470-495` launcher task executor | runs submitted work | submission time limit / arm | captured into the terminal | terminal delivery | launcher | **KEEP**: this is flow's expansion mechanism already | — |
| 14 | `flow.clj:190` `bounded-platform-executor` and root pair `resources/seon/operator/runtime.clj:17-21` | process-root executors | fixed `availableProcessors` for compute | n/a | JVM lifetime | process root | **KEEP**, JVM-level. Also pass the root `:io` to the launcher graph (existing issue). | — |
| 15 | `cluster.clj:3127-3141`, `flow.clj:1123-1133` two `projection-executor`s | bind the projection around root `:io` runs | n/a | n/a | n/a | graph | **DELETE one**: two copies of one wrapper (`seon.flow` private, `seon.cluster` public). Keep `cluster/projection-executor` and pass it to the fault graph. | duplicate mechanism |
| 16 | `agent.clj:784` `(locking contexts …)` | serializes context acquisition, including branch open and fork | none | throws | n/a | cluster | **EXPAND**: subsumed when the armer proc owns arm/acquire (row 17). The proc is the serializer. | D6 |
| 17 | `agent.clj:917`, `:1070` `(locking routing …)` arm/disarm | serializes graph lifecycle against direct installers | none; disarm waits for the turn | throws | yes | cluster | **EXPAND**: arm and disarm become messages to `:seon.agent/armer` (it already has the `quiesce` protocol, `cluster.clj:3360-3374`). `submit-source!` and the other direct installers inject `{:seon.agent/arm id :reply ch}` and await the reply under the backstop bound. Then delete the monitor. | D6, lock for a race |
| 18 | `sci/eval.clj:2469` `(locking program-snapshot …)` in `acquire!` | once-per-commit program acquisition | none | throws | n/a | cluster ctx | **DELETE the lock**: `program-identity-cache` already does `cache/lookup-or-miss` with a `delay` keyed by commit id (`sci/eval.clj:2455-2458`). Make `acquire!` deref that delay, so the `delay` serializes one construction per commit id and a second reader waits on it. | lock for a race |
| 19 | `render/web.clj:2840-2894` per-tab SSE feed virtual thread | writes page packages to one browser socket | `backstop-ms` per write | catch → `offer!` fault (D5) | no; the `painting` flag only | http-kit connection | **KEEP** (the lifetime is the socket, and flow topology is static), but register each thread in the service and join it in `web/stop!`; the catch goes the route | D8c, D5 |
| 20 | `render/web.clj:3652` `newVirtualThreadPerTaskExecutor` for http-kit | request workers | per-request | http-kit `:error-logger` default (stderr) | **not shut down on stop** | web service | **KEEP**; `web/stop!` closes the executor, and `:error-logger` → route (prior-art doc §3) | D8d |
| 21 | `render/web.clj:2681` `Thread/sleep remainder` in the render proc | coalesce floor | `::coalesce-ms` | n/a | inline | render proc | **KEEP, measure first**: sliding-1 already coalesces while the proc is busy. If the floor is needed, use a self-kick via `async/timeout`. Low value. | no-sleep rule |
| 22 | `render/web.clj:2289`, `:2887`, `:3434` `offer!` fault + `log/warn` | render/feed/data faults | n/a | droppable (D5) | n/a | — | **EXPAND to the route**: the render proc's (`:2289`) becomes a throw into the var-process boundary; feed/data become `fault!` calls | D5 |
| 23 | `turn.clj:5373`, `:5396`, `agent.clj:1036` `offer!`+`println` | bound-fired faults | n/a | droppable, print-only | n/a | — | **EXPAND to the route**: `fault!` (above the route) | D5 |
| 24 | `turn.clj:5277` `await-turn-permit!` `alts!!` completion/timeout | awaits the turn permit | backstop | throws a diagnostic | yes | turn proc | **KEEP**; bounded and named | — |
| 25 | `agent.clj:993-1041` `await-turn-completion!` | disarm join | backstop only without an active backstop (D7) | throws plus `offer!`/`println` | yes | agent | **KEEP**, add the bound on the active-backstop branch, route the fault | D7 |
| 26 | `cluster.clj:3360-3390` disarm `>!!`/`<!!` | cluster disarm joins | **none (D7)** | throws on closed | yes | cluster | **KEEP**, declare the backstop bound on every wait (`await/await!` exists: `await.clj:111-150`) | D7 |
| 27 | `flow.clj:1300-1320` `stop-error-fanout!` `<!!` ×2 | fan-out join | **none (D7)** | — | yes | cluster | **DELETE** with the fan-out (option B), else bound it | D7 |
| 28 | `eval/drive.clj:61-80` `await-fact!` temporary `d/listen` | dev drive waits on a fact | declared `timeout-ms` | throws | unlisten in `finally` | call | **KEEP** (bootstrap drive tool; a temporary listener, exact bound) | — |
| 29 | `db.clj:3872-3886` write-bound `deref` | awaits Datahike's writer promise | agent writes only; system writes unbounded | typed | Datahike | connection | **KEEP**: Datahike owns the writer. A system write without a bound is a separate question for the db owner. | — |
| 30 | Datahike writer go-blocks on core.async's `:mixed` pool | transactions, including Seon tx fns | Datahike | go `ex-handler` → thread uncaught handler (`impl/dispatch.clj:63-69`) | Datahike | connection | **KEEP** (dependency). Probe P1 saw an `async-mixed-4` thread in `seon.schema/canonical-data-string`; `executor-for :core-async-dispatch` = `:mixed` (`dispatch.clj:109-111`). Install one `Thread/setDefaultUncaughtExceptionHandler` → route, at boot. | escaped go/virtual-thread errors |
| 31 | `sci/kernel.clj:48-56` `ScheduledThreadPoolExecutor` `seon-sci-time-limit` | sets the SCI arm's deadline latch | per arm | n/a (one `.set`) | daemon | JVM | **KEEP**: SCI's interrupt mechanism, below every graph | — |
| 32 | `sci/kernel.clj` `AtomicLong`/`AtomicBoolean` (`:63-86`, `:195-236`) | arm counters and latches | n/a | n/a | n/a | arm | **KEEP**: counters, not locks | — |
| 33 | `test.clj:133-190` test body virtual thread plus exit watcher | one test body under a bound | per-test bound | ExecutionException → failure text | **yes**: the watcher joins before release | test | **KEEP**: the test runner boundary; it observes exit correctly | — |
| 34 | `test/runner.clj:877` `(locking holder …)` | reach-index cache | n/a | throws | n/a | test tier | **KEEP** (test tier; B4 owns) | — |
| 35 | `cluster/process.clj:205-275` `run-process!`, `future` output pump, `.waitFor`, `onExit` | operator subprocess under deadline/silence | declared deadline | throws with evidence; cleanup `TimeoutException` → nil, reported through `reaped?` | future not joined on the timeout path | operator | **KEEP** (operator, outside clusters) | — |
| 36 | `shell/jvm.clj:90-105` `virtual-task` stdin/stdout/stderr pumps | stream pumps for `my.shell/run` | `task-result` under the shell limit (`:107-130`) | captured into the result | awaited by `task-result` | capability request (row 9) | **KEEP**: inside the capability handler, governed by row 9's bound | — |
| 37 | `issue.clj:71`, `test/cache.clj:35`, `:187` `process/process` + bounded `deref` | git reads | declared bounds | throw on expiry; `issue.clj:85` returns nil on non-zero exit | `destroy-tree` | call | **KEEP**; the nil return is a swallowed-errors census item | — |
| 38 | `web/jvm.clj:72-101`, `ai.clj:1332-1340` JDK HttpClient `.timeout` | HTTP | JDK request timeout | typed values | inline | caller | **KEEP**: the JDK enforces it; it runs inside rows 6 and 9 | — |
| 39 | `cluster/store.clj:315`, `:344` (`held-flocks`), `:549` (`branch-open-monitor`) | flock and branch-open custody | n/a | throws | n/a | process root | **KEEP**: JVM-level custody, microsecond sections, before any graph | — |
| 40 | `cluster.clj:903`, `:933` `root-store-holder`; `boot.clj:266`, `:333`, `:436` `running-instances` | store refcount, instance reservation | n/a | throws | n/a | process root | **KEEP**: bootstrap custody before graphs exist | — |
| 41 | `cluster.clj:545` prepl print `locking` | serializes prepl output | n/a | n/a | n/a | REPL | **KEEP**: the REPL's own threads | — |
| 42 | `fs/jvm.clj:677` `write-serialization` | serializes `my.fs` writes | the effect bound (row 9) | declared values | n/a | capability | **KEEP** until row 9 lands, then consider an io concurrency of 1 for fs writes; low value | — |
| 43 | `schema/edn.clj:376`, `:401` `packaged-population-cache` lock | cache compute under a lock | n/a | throws | n/a | JVM | **KEEP** (JVM-level resource cache, not db-derived) | — |
| 44 | `artifact.clj:104-109` shutdown hook + `@(promise)` | the artifact main thread parks forever | none | n/a | JVM exit | JVM | **KEEP**: the process main | — |
| 45 | `bootstrap_drive.clj:444-466` sequential drives | dev harness | declared remote timeout | failed report | yes | call | **KEEP** | — |
| 46 | `cluster.clj:521` `mcp-valf` `offer!` runtime-eval | MCP invalidation wake | n/a | `catch Throwable _` → pr-str error (census item) | n/a | REPL | **KEEP** (sliding-1 wake) | — |
| 47 | `cluster/status.clj:92` `thread-counts` | observation | n/a | n/a | n/a | status | **KEEP** | — |

Totals: 47 sites.

- **EXPAND: 14** (rows 1, 3, 5, 6, 8, 9, 16, 17, 19-fix, 20-fix, 22, 23, plus 12 and 25-27 bounded).
- **DELETE: 6** (rows 2, 4, 7-after-6, 11, 15, 18).
- **KEEP: the rest.** Every KEEP is JVM or bootstrap custody, the REPL, the SCI kernel,
  the test boundary, the operator, or a dependency.

No site uses `future-call`, `pmap`, `send`/`send-off`, `go`/`go-loop`,
`pipeline`, `Semaphore`, `ReentrantLock` or `Timer` directly in `src/`. (`sci/eval.clj:7`
and `:2987` mention a Semaphore only in docstrings.)

## 3. How the one error route and flow compose

**Principle.** Flow's own design is that a step fn throws and the graph's error channel
carries the failure to one reader (`flow.clj:116-120`, `flow/spi.clj:56-58`). The
error-route prior-art doc (§1) reads this as "Call site: nothing". Seon keeps that shape
but moves the reader *into the proc's own thread*, because the channel is lossy
(sliding 100) and the policy requires a synchronous commit.

1. **Inside procs, do not catch.** A step transform lets a Throwable propagate.
   `var-process` (`flow.clj:132`), already "THE construction seam for every proc",
   wraps the step Var once. It calls through the Var on every invocation, so hot reload
   survives. For the **transform** arity (the fourth) and the **transition** arity (the
   third), the wrapper catches Throwable and calls
   `(seon.fault/fault! env failure {:seon.error/layer ::proc :seon.error/operation step-sym ::flow/pid pid ::flow/cid cid})`.
   The pid comes from `(::flow/pid state)`, since flow assoc's it into the args
   (`impl.clj:156`). Under `:record` the wrapper then returns `[state nil]`, which is
   flow's own "attempt to continue" (`impl.clj:316`), now made explicit. Wrapping the
   transition arity closes D2: a stop-transition failure is committed on the proc's
   thread before flow's closed `error-chan` can drop it.
2. **Responsible agent comes from the environment, not a pid table.** Every agent-graph
   proc's args carry the environment scoped with `:seon.agent/id` (`agent.clj:511-512`).
   The wrapper reads `(:seon.agent/id (env/of args))`. A cluster-graph proc has none, so
   delivery falls to one-error-route §5 (namespace owner, then `escalate-to`).
   `join-error-fanout!`'s tag merge (`flow.clj:1288`) is then unnecessary. The pid stays
   in the fact as the location (`:seon.error/operation` = the step Var's symbol, plus
   `::flow/pid`).
3. **`:panic` stops the source graph after the commit.** A rethrow inside a proc is
   swallowed by flow's catch (`impl.clj:312-316`), so the wrapper does not rethrow.
   - `start-graph!` (`flow.clj:71-94`) delivers the created graph into a promise.
     Every proc's args carry that promise, on the same principle as `::routing-graph` at
     `cluster.clj:3281-3286`.
   - Under `:panic`, after `fault!` returns the stored identity, the wrapper calls
     `(flow/stop graph)` on its own graph and records the terminal status, so oversight
     shows `:failed` with the error id, never `:unknown`. It then returns `[state nil]`.
   - The JVM, the REPL, sibling graphs and other clusters keep running.
   - Outside procs, `fault!` throws after commit to its caller (one-error-route §3).
   - A `submit!!` caller receives the stored error in its terminal, and the submitter's
     own boundary applies the dial.
4. **Flow's sliding-100 `error-chan` is drained into a counted route.** After (1), the
   only things that still reach `error-chan` are flow-internal failures:
   - control/command handling at `impl.clj:317-320`;
   - `send-outputs` to an unresolvable port;
   - an xform ex-handler (`impl.clj:105-110`). Seon declares no conn xforms today
     (`rg xform`: none).
   These are residue, and core.async gives Seon no hook into that buffer. So: exactly
   **one drain per graph**, the existing `join-error-fanout!` shape (row 12), taking with
   `<!!` and calling `fault!` synchronously on its task. No mult, no second reader (the
   flow-monitor lesson: a second reader steals errors) and no dropping buffer. Monitor
   taps may still read the *report* channel.

   The drain must run for **every** graph, the launcher included (D1). Loss can then
   happen only if more than 100 residual errors arrive while one `fault!` commits. The
   drain counts every take in its ping, and the loss bound is stated as "flow's
   sliding-100, residual errors only".
5. **Coalescing.** A hot loop throwing in a transform calls `fault!` once per step.
   one-error-route §4's first-occurrence plus window flush bounds the writes. A panic
   stops the graph on the first occurrence, so a storm under `:panic` is one write.
6. **The listener is not a proc and must never park.** The router (row 1) removes its
   work, so its catch can only see an `offer!` failure on a sliding-1 channel. That is
   practically unreachable. `seon.cluster.wake` sits below the route
   (one-error-route §2), so it rethrows. Datahike then fails the caller's transaction
   delivery loudly (wakes-and-faults.md "Never throw" explains why that was avoided).
   **This is an open decision** (§4, option 2's cost line).

**What is deleted when this lands** (the same set as one-error-route option B):

- `counted-dropping-buffer`, `fault-committer-step`/`-proc` and the fault graph
  (`flow.clj:990-1090`, `:1182-1263`);
- `join-fault-committer-errors!`, `report-committer-loss!` (`:1135-1180`);
- `stop-error-fanout!` (`:1296-1320`) and `emit-core-fault!`;
- the private `projection-executor` copy (`flow.clj:1123-1133`).

`start-error-fanout!` shrinks to a report-channel monitor view.

## 4. Three options

**Option 1: close the holes, keep the shapes (simplest viable).**

- Land one-error-route step 0 (`seon.fault/fault!`).
- Wrap the `var-process` transform and transition arities (§3.1-3.3).
- Replace the committer fan-out with one synchronous drain per graph, the launcher
  included (§3.4).
- Replace rows 22-23's `offer!`/`println` with `fault!`.
- Bound the waits in rows 25-27.
- Swap the schedule timer thread for `async/timeout` (row 3).
- Shut down the http-kit worker executor (row 20).

*Guarantee:* every proc failure, including a stop-transition failure, is stored with
its chain on the failing thread before the proc continues. It is delivered to a named
agent. `:panic` stops its graph visibly. No fault crosses a Seon dropping buffer. Every
stop wait is bounded and names its event. D1, D2, D5, D7, D8b and D8d close.

*Cost:* about 1-1.5 days after one-error-route step 0. Four source files (`flow.clj`,
`cluster.clj`, `turn.clj`, `agent.clj`) plus `schedule.clj` and `render/web.clj`.

*Gives up:* the listener work stays on the writer thread (D3, D4). The arm/disarm
monitor stays (D6). The model call and evaluations stay inline, so the turn proc stays
unpingable during a call (D10). The backstop watcher and SSE threads stay outside flow
(D8a, D8c).

**Option 2: option 1 plus expanding flow to every cluster-owned thread (RECOMMENDED).**

Option 1, then the EXPAND rows in the order below:

- the router proc, which deletes the schedule and call-preparation listeners (rows 1, 2, 4);
- the armer serializes arm/disarm, which deletes both monitors (rows 16, 17);
- the backstop proc (row 5);
- evaluations through `submit!!` `:compute` (row 8);
- capability dispatch through a `submit!!` `:io` arm (row 9);
- the model call as a launcher io submission with a completion in-port (row 6), which
  deletes the retry sleep (row 7);
- SSE threads joined at stop (row 19);
- the `acquire!` lock deleted in favour of the existing commit-id `delay` (row 18).

*Guarantee:* every thread a cluster starts is either a flow proc (stopped by
`flow/stop`, joined by its stop-transition completion) or a launcher task (admitted
under a declared bound, settled exactly once, drained at stop). Every error from them
takes the one boundary in `var-process` or the launcher's terminal. The launcher's
active-count gate bounds CPU across all agents. Only one Datahike listener per cluster
remains, and it does no work on the writer thread. No lock exists only for a race. The
turn proc answers ping and stop during a model call. Every D row closes.

*Cost:* option 1 plus about 4-5 days. Rows 1-5 and 16-18 are about 2 days and
low-to-medium risk. Rows 6-9 are about 2-3 days: row 6 restructures the turn's
call/resume into a two-message proc protocol.

One new decision, for the owner: when the router listener's `offer!` fails, should it
rethrow into Datahike's delivery, or should `fault!` accept a db-free
panic-to-stderr-and-stop for the listener only?

*Gives up:* model-call latency gains one in-process hop (sub-millisecond). Turn code
becomes message-driven, which is more to read than the inline call. The SSE feed stays a
thread per tab, joined rather than a proc, because flow topology is static and tabs are
dynamic.

**Option 3: option 2 plus flow-native committing and a real `:compute` tier.**

Keep a committer *proc* per cluster, fed by a **fixed-buffer, blocking** `>!!` with a
reply promise-chan from the `var-process` wrapper. The wrapper awaits the commit under
the write bound. Coalescing state is proc state instead of `seon.fault`'s process-local
map. Also:

- every graph receives both root executors (the existing issue);
- CPU-heavy procs (a render derivation, the router's matcher rebuild) are declared
  `:compute` with an explicit `compute-timeout-ms` beside the SCI arm.

*Guarantee:* option 2's, plus coalescing held by flow and inspectable by ping. CPU
derivations stop occupying `:io` virtual threads.

*Cost:* option 2 plus about 2 days. Every fault takes an extra channel round-trip. A
failure inside the committer cannot use itself, so it needs a separate stderr-and-stop
path. Flow's `:compute` `.get` timeout does not terminate the future (`impl.clj:259-260`;
AGENTS "A timeout is not termination"), so every `:compute` proc still needs the SCI arm
or a host-bound refusal.

*Gives up:* synchronous-on-caller simplicity. `fault!` can no longer be one required
function callable from non-proc sites without the committer channel in the environment,
which is one-error-route option A's weakness.

**Recommendation: option 2.** Land option 1 as its first slice, because D1 and D2 are
silent losses today.

## 5. Ranked conversion order (risk × value)

| rank | step | value | risk | depends on |
|---|---|---|---|---|
| 1 | Join the launcher graph's error-chan into the route (D1) | high: silent loss today | very low: one join | none (possible today with `join-error-fanout!`) |
| 2 | `var-process` wrapper over transform and transition; `start-graph!` graph promise; `:panic` stop | high: D2, D5, the panic contract | medium: every proc passes through it | one-error-route step 0 |
| 3 | One synchronous drain per graph; delete the fan-out, committer and counted-dropping buffer | high: policy compliance | low once 2 lands | 2 |
| 4 | Rows 22-23: `offer!`/`println` → `fault!` | medium | low | step 0 |
| 5 | Bound every stop wait (rows 25-27) | medium: no silent hang on stop | low | none |
| 6 | Router proc; delete the schedule and call-preparation listeners (rows 1, 2, 4) | high: the writer's critical path, D3, D4 | medium: the wake-routing regression suite must pass unchanged | none |
| 7 | Schedule timer → `async/timeout` (row 3) | medium | very low | none |
| 8 | Armer serializes arm/disarm; delete both monitors (rows 16, 17) | high: D6 | medium: every direct installer converts in one slice | none |
| 9 | Backstop proc (row 5) | medium | low | 2 |
| 10 | Delete the `acquire!` lock via the commit-id `delay` (row 18) | medium | low | none |
| 11 | Evaluations via `submit!!` `:compute` (row 8) | high: cross-agent CPU bound | medium | the existing issue's owner ruling |
| 12 | Capability dispatch via a `submit!!` `:io` arm (row 9) | medium | medium | 11 |
| 13 | Model call as a launcher submission; delete the retry sleep (rows 6, 7) | high: D10 | high: turn restructure | 11, 12 |
| 14 | SSE join and worker-executor shutdown (rows 19, 20) | low-medium | low | none |

Rows 1, 5, 7, 10 and 14 are file-disjoint and depend on nothing. They can launch in
parallel today.

## 6. Probes (read-only, JVM mode, `default` pid 70720)

- **P1** (1.8 ms in-form): `(keys @(:listeners (meta (seon.cluster.boot/connection "default"))))`
  → `[:seon.call-preparation/rows :seon.agent/route #uuid "59397c0e-…"]`. That is three
  listeners with one agent. There were 51 platform threads; the `async-mixed-*` threads
  were confirmed as core.async's go-dispatch pool (Datahike writer).
- **P2** (3,009 ms, which is the deliberate 3 s ping window):
  `(flow/ping-proc graph :seon.agent/turn :timeout-ms 3000)` → nil, with the backstop
  armed (`:seon.agent/cancel :seon.agent/failure-channel :seon.turn.loop/await-part`)
  and `turn-stopped` empty. Open turn `355fab5ff381` was opened at 21:55:39Z, probed at
  21:56:30Z, so it had been open 51 s. The turn proc was inside a transform.
- **P3** (0.65 ms): fault committer ping `{committed 0, lost 0, panicked 0}`; fault
  buffer `CountedDroppingBuffer` capacity 65, count 0.
- **P4** (3 ms): launcher `error-chan` count 0 and no reader. Agent `report-chan` count
  25, unread. Cluster `application-report-channel` count 0, no consumer.

## 7. Stale skill claims found (seon-flow-architecture)

These must be corrected by the skill's owner. This lane commits only this file.

- `references/wakes-and-faults.md` says "Fresh Seon installs one Datahike `listen!`
  router per cluster". This is false; see D3. Its line references `wake.clj:163-228`,
  `flow.clj:553-715` and `cluster.clj:1151-1229` are stale. The current locations are
  `wake.clj:495-547`, `flow.clj:990-1320` and `cluster.clj:3171-3320`.
- `references/workloads-and-scheduling.md` says per-agent, cluster and fault graphs
  "omit executor overrides". This is false: `agent.clj:541-542`, `cluster.clj:3167` and
  `flow.clj:1189` all pass `:io-exec`. Its claim "turn path evaluates SCI inline
  (`turn.clj:4283`)" is true in behavior, but the line is stale: the call is `:4521`
  for the provider, and `submit-evaluation!!` at `:3316` has no caller.

## Durable issue paths

Existing issues this audit extends:

- `docs/seon/issues/turn-evaluations-bypass-work-submission.md` (row 8)
- `docs/seon/issues/flow-work-launcher-graph-omits-its-root-io-executor.md` (row 14)
- `docs/seon/issues/from-zero-boot-takes-minutes.md` (`ready-ms` 106,787)

D1-D10 are recorded here. None of them has its own issue note yet. The orchestrator
should file them per defect class; this lane was limited to this one file.

## Timings

| operation | wall | notes |
|---|---:|---|
| `bin/seon status` | 224 ms | |
| scripted `rg` census | 31 ms | 324 raw hits |
| probes P1, P3, P4 | 1.8 / 0.65 / 3 ms in-form | |
| probe P2 | 3,009 ms | the deliberate ping window; the proc was busy (D10) |
| `default` `ready-ms` (observed, not this lane's) | **106,787 ms** | over 10 s, a defect; extends `from-zero-boot-takes-minutes.md` |

Nothing this lane started exceeded 1 s except P2's declared ping window. No cache
applies to a read-only `rg` or probe.
