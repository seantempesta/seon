---
type: plan
status: revised 2026-09-23 after the Astra review (review-flow-prd-2026-09-23.md): five P0s and the P1 shrink applied; decisions A and B RULED by the owner (README §7 "Wake routing through Flow; launcher kept"); no src/test/resources edits by this lane
created: 2026-09-23
tags: [agent-platform, flow, core-async-flow, errors, datahike, listeners, lifecycle]
owner: the flow half of README §4 cut 3/4; composes B2 (execution), B3 (errors), A2 (Datahike seams), B1b (boot)
---

# Lane FLOW — flow owns running machinery

**Owner, verbatim (2026-09-23):** "we have flow for all the processes. This has a good
error design"; "Spec out the best plan you can think of and I want an astra review. I
want to prioritize getting to the namespace agents work done first though so maybe
this happens later." **Ruled the same day (README §7 "Priority to namespace agents",
`a2e9253fe`):** the must-fix order is the running must-fixes → M3 → M9 → M4 → cut 3;
the flow restructure follows, except its MUST-NOW pieces below; the 1.4 projection
sweep runs after cut 3's first namespace agents.

**Review (Astra, `docs/research/agent-platform/review-flow-prd-2026-09-23.md`):**
retain the direction; correct the terminal and error paths before MUST-NOW is approved.
Its five P0s and its P1 are applied in §2 and marked **[R1]**–**[R6]**; its placement
and acceptance corrections are applied in §3 and §5. **Decisions A and B were then
ruled by the owner (README §7 "Wake routing through Flow; launcher kept"; §4):** the
router is a Flow proc fed by one offer-only listener, waking each affected agent on its
mailbox wake port, a failed hand-off throwing into Datahike through the fork's failure
callback; the launcher stays the admission owner, and B2 commit 7 retires it only on an
admission proof.

Inputs, read end to end: AGENTS.md; README §2–§7; the
[fact pack and triage](../../../research/agent-platform/flow-fact-pack-and-triage-2026-09-23.md)
(F1–F13, L1–L5, D1–D10, M1–M13, S1–S9); the
[flow usage audit](../../../research/agent-platform/flow-usage-audit-2026-09-23.md)
(47 sites, three options); the
[error-route final design](../../../research/agent-platform/error-route-final-design-2026-09-23.md)
(Astra, option 2) and its [draft](../../../research/agent-platform/one-error-route-design-2026-09-23.md);
the [review](../../../research/agent-platform/review-flow-prd-2026-09-23.md); the
[flow skill](../../../../.agents/skills/seon-flow-architecture/SKILL.md) (stale claims:
audit §7, schedule row 27); core.async `flow/impl.clj` at gitlink `dc35f3e0`; Datahike
`writer.cljc` and `committed_report.cljc` at gitlink `fbd1ad2d`. Below, **Flow** =
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj`, **DH** =
`reference-code/datahike/src/datahike/`. Seon line numbers are the working tree of
2026-09-23 22:00Z (HEAD `d4d4e1369` plus running lanes' hunks); *(wt)* marks an
uncommitted hunk.

Source facts this design never contradicts: a proc runs one transform at a time and
reads control only between transforms (Flow:288-300); a `:compute` `.get` timeout
reports a late result and stops nothing (Flow:258-260); `error-chan` is sliding-100
(Flow:102) and `stop` closes it before any proc exits (Flow:174-183) — stop is a
command, not a join; control is a fixed-10 channel behind a mult with fixed-10 taps
(Flow:99-100, 153-154), and a mult delivers to every tap synchronously
(`core.async.clj:797-804`), so a full tap blocks every sender; Datahike listeners run
concurrently on core.async's `:mixed` pool with no ordering and, under batching, a
shared `:db-after` (DH `writer.cljc:390-406`; fact pack L2); a throwing listener is
caught and logged per listener (DH `writer.cljc:379-387`); a launcher task failure is
emitted as DATA, an output to `::flow/error`, and no catch fires (`flow.clj:569-578`).

## 0. For the owner: what is dumb, and the simpler way

**What runs today.** Flow already owns the graph and its error contract: a step throws,
Flow catches on the proc's own thread and puts a complete failure map on `error-chan`
(Flow:312-320). Seon then throws that design away: the map crosses a sliding-100
channel, a `mult`, a counted-dropping buffer of 64 (`flow.clj:999`, `cluster.clj:3265`)
and lands in a committer proc that under `:panic` prints one stderr line and returns
nil (fix schedule row 0). Six sites outside any proc `offer!` a fault onto that same
channel and `println` when it is full (`turn.clj:5366`, `:5389`; `agent.clj:1072`;
`render/web.clj` ×3). The launcher graph's error channel had no reader until
`c2140df4e`. A failure inside a proc's `::flow/stop` transition is written to the
already-closed channel and vanishes (D2). Twelve sites read the loudness dial for
themselves (draft §1 R6). Beside the graphs, the cluster runs threads Flow never sees: a
turn backstop loop (`turn.clj:5396-5450`), an SSE writer per browser tab
(`web.clj:2882`), an http-kit executor never shut down (`:3693`), and 3 + N Datahike
listeners that do query work on the writer's notification thread and drop their own
faults (`wake.clj:517-547`). Stop waits on those threads are unbounded
(`cluster.clj:3394-3417`, `flow.clj:1305-1309`), so a stuck proc hangs `bin/seon stop`
(M5). The monitor at `agent.clj:1190` *(wt)* serializes every arm against a disarm that
honestly includes a model call (D6).

**Why that shape was wrong.** Each piece is a copy of a guarantee the dependency already
holds — Flow's catch, Datahike's settled-then-notify order, core.async's executors —
plus machinery to move the copy somewhere else, plus a guard where the copy lost
something. Every hop is a place the fault can be dropped, and AGENTS.md's error policy
forbids exactly that ("never dropped by an overload channel").

**The simpler way, as data flow.** The failure is recorded **where Flow already catches
it, on the proc's thread, before the proc continues**: the maintained fork's three
catches call one optional handler with the failure map; the handler is
`seon.fault/fault!` (B3/Astra), which stores the chain, wakes the responsible agent and
applies the one dial synchronously; under `:panic` the handler's answer is terminal, the
proc runs its own `::flow/stop` transition, exits, and its exit is a delivered fact the
supervisor joins under a bound; siblings are stopped through Flow's control channel
under bounded admission; the recorded occurrence is what status, MCP and the page show as
`:failed`. `error-chan` becomes an observation of already recorded failures and may
slide. A failure that Flow never catches because it was emitted as data (the launcher's
terminal) is recorded by its producer through the same function. Everything the cluster
runs is either a proc under `flow/stop` with a bounded, named exit wait, or a launcher
submission admitted under a declared bound whose completion returns as a message. The
wake listener does no work: it offers one look and the router proc reads its own
connection. Nothing new is scheduled, dispatched, batched or cached.

## 1. The end state and why

| principle | end state | why |
|---|---|---|
| **Flow owns every cluster thread's lifecycle** | Every thread a cluster starts is a Flow proc (built through `var-process`, `flow.clj:132`; stopped by `flow/stop` under bounded command admission; joined at its delivered exit fact under a declared bound) or a launcher task (admitted by the refusing buffer, `flow.clj:304-345`; settled exactly once at the `::completion` in-port, `flow.clj:525-580`; drained at stop). The SSE writer per tab is the one exception the audit keeps (row 19): Flow topology is static and tabs are dynamic; it is registered and joined at `web/stop!` under the feed bound. | AGENTS "Bounded, event-driven execution": every surface has a declared bound at admission and awaits the exact terminal event; a thread nobody joins is an unobserved exit (D8). |
| **Long or blocking work goes through the bounded launcher; completion is a message** | The launcher proc already has the shape: `::compute-submission`/`::io-submission` in, `::completion` back in, one terminal per submission. A model call (S8), an SCI evaluation (S7) and a capability handler (audit row 9) become submissions whose completion re-enters the owning proc as a message on a declared in-port, so the proc never blocks inside a transform and answers ping and stop between messages (F5, D10). Which of these land, and when, is B2's single execution design (§3, §4 B). | A proc is deaf during a transform (Flow:288-300); the only way to stay observable across a 51 s model call (audit P2) is to not be inside the transform for it. |
| **One error route, composed with Flow, synchronous** | `fault!` is the one required function (final design). The fork hook calls it from Flow's catches; the launcher's terminal calls it for failures it emits as data; every out-of-proc boundary (boot request, MCP eval, http-kit `:error-logger`, subprocess checks) calls it directly; the listener failure handler and the process uncaught handler are the backstops. In the pre-agent slice `fault!` commits synchronously on the caller's thread (triage M4: "minimal"; B2 defers batching); the acknowledged batching committer of the final design is revisited after the first agents with measured repeat load **[R6]**. The counted-dropping route, the fault graph, both fan-out joins and `emit-core-fault!` are deleted with M4. | AGENTS error policy: stored with its chain at the owning boundary, delivered through the wake route, loud under `:panic`, panic in both modes when the database is down. No committer means no stop-order cycle (a stopping proc awaiting a committer stopped in the same graph). |
| **A failed graph is positively visible; exit is a delivered fact** | Under `:panic` the failing proc runs its stop transition and exits; the fork delivers `{pid outcome cleanup-ex}` on a per-proc exit promise-chan; the supervisor joins it under the bound. The stored occurrence carries the graph/agent identity and the observed panic mode as a historical observation (not a mutable flag); `seon.problems/open-panics` (final design; `problems.clj:109-128` today) joins it to the agent, and oversight (`oversight.clj:186 flow-status`) reports `:failed <error id>` for that graph. | "A failed graph is positively visible"; "derive state, do not remember it" — the fact is the occurrence; the exit is the run boundary's delivery, not an inference from a returned loop. |
| **No Seon work on the writer's thread; agents are woken on their own Flow ports** | **Ruled (§4 A).** One offer-only sliding-1 listener per cluster puts a payload-free wake on the router proc's in-port; the router proc reads its own connection at one captured value per look, wakes each affected agent on its mailbox proc's `:seon.agent/wake` port (every agent is a Flow graph whose `:seon.agent/mailbox` proc reads that port, `agent.clj:481`, `:519-540`) and advances its basis only after successful delivery. A failed hand-off throws into Datahike through the fork's failure callback (N3). No matcher rebuild, no `snapshot` derivation, no per-agent listener on the notification path (D3, D4). | Owner: "Flow processes for datahike listening is a great idea especially since agents are also flow processes … so we can wake them on their channels." A listener runs concurrently and unordered on the `:mixed` pool with batch-shared `:db-after` (L2); a router that acts on the callback's report can act on a stale value; a router that reads its own connection cannot. |
| **Interrupt is a request; exit is a fact** | `Thread.interrupt` on proc threads stays the stop accelerator (`agent.clj:1024-1039` *(wt)*) until B2's single execution design rules otherwise; async model calls alone do not justify deleting interrupts that evaluations and other host work still need. An interrupt that lands in a Datahike `transact!` deref leaves that write outcome-unknown to the caller; the turn records the evaluation as interrupted (AGENTS "Interrupted execution never resumes") and recovery reads the facts, never the promise. No wait after interrupt is unbounded; expiry retains custody and denies restart or overlapping work until the exit fact arrives. | AGENTS "A timeout is not termination"; README §3 "Timeout includes exit". |

What this design does NOT add: a scheduler, a dispatcher, a second error channel, a
replay store, a retry loop, a batching committer in the pre-agent slice, a temporary
backstop proc B2 would delete, a thread registry beyond Flow's procs and the launcher's
admitted set, a readiness dispatcher for the router, or a namespace roster for who may
call `fault!`.

## 2. MUST-NOW — the flow pieces before namespace agents

Shrunk per the review **[R6]**: the dependency hook plus channel-`:xform` refusal
(N1), bounded stop and exit (N2), the listener failure lifecycle (N3) and a minimal,
evidence-attributed uncaught handler (N4). No Seon step wrapper, no committer, no
batching. They land inside M4's window (after M3's fault-cost fix, `error.clj` P4:
1,099.9 ms in-transaction vs 26.1 ms outside) except N2, which depends on nothing.
Estimates are revised design estimates including the terminal, custody and recursion
proofs the review named; they are not measurements.

Ownership per `tmp/orchestrator/file-ownership.md` at 22:00Z: `flow.clj`,
`cluster/boot.clj`, `cluster/wake.clj`, `schedule.clj`, `reference-code/core.async`,
`reference-code/datahike`, `.gitmodules` are free; `cluster.clj` is held by
runtime-status-crash (M7), `cluster/agent.clj` by mcp-and-stop (M5), `sci/eval.clj` by
sci-program-revisions (M1), `render/web.clj` by page-key, `db.clj` by writer-cost. A
slice needing a held file goes to its holder as a follow-up or waits; it never runs
beside it.

### N1. The fork error hook, terminal protocol, bounded control admission, and `:xform` refused

**Fact first: there is no Seon fork of core.async.** `.gitmodules:17-19` points at
`https://github.com/clojure/core.async.git`; Datahike is already the personal fork
(`git@github.com:seantempesta/datahike.git`). Step one is a fork under the same account,
the gitlink repointed, pushed without asking (owner rule: personal forks push when a fix
lands, never a PR upstream). The fork keeps upstream's tests green: every addition is
opt-in.

**N1a — the hook, one handler at three catches [R6].** Flow has exactly three `catch`
clauses — `start-proc` (Flow:163), the step catch (Flow:312, which also covers
`send-outputs` and the `:compute` `.get` timeout, Flow:259, since both run inside that
`try`) and the outer catch (Flow:317, transition and control) — plus the channel
exception callback (Flow:107). The fork adds one optional handler, supplied per proc
through `proc`'s options (Flow:245) with a graph-wide default in `create-flow`'s config
(Flow:52); absent = today's behaviour. Each catch calls
`(on-error failure-map)` on the proc thread before its existing `>!!` to `error-chan`,
with Flow's existing map plus `::flow/op` where it is missing (`:start`, `:transition`,
`:control`). The handler answers `::flow/continue` or `::flow/exit`. The channel
callback is **not** hooked: it runs under the channel mutex during buffer mutation
(`impl/channels.clj:78-94`); Seon refuses `:xform` before any channel is created
(N1d). Graph construction and `describe` failures (`prep-proc`/`create-flow`,
Flow:38-68; `proc`'s `(step)` at Flow:246) throw to the constructing caller; Seon's
`start-graph!` (`flow.clj:71-94`) is that outer construction boundary and calls `fault!`
there before rethrowing.

**A handler that throws is not caught by Flow.** The fork wraps the handler call once:
on a handler throw it retains the terminal disposition (`::flow/exit`), preserves the
handler's throwable beside the original in the exit fact (N1b) and on stderr as the
emergency diagnostic, and never calls the handler again for that failure. The proc's
`run` is a `FutureTask` (Flow:29-36) that captures throwables, so the process uncaught
handler (N4) never sees this; the exit fact is the only truthful channel for it.

The Seon adapter, supplied by the cluster owner at graph construction so `create-flow`
definitions stay data (`var-process`, `flow.clj:132`, gains a required `::on-error`
option and passes it through; no wrapper of the step Var exists):
`(fn [failure] (case (fault/fault! world failure ctx) :seon.fault/recorded ::flow/continue …))`
where `world` is the environment the proc's args already carry
(`env/refuse-absent-environment!`, `flow.clj:167`), `ctx` is
`{:seon.error/layer :seon.flow/proc :seon.error/operation <step Var symbol> ::flow/pid pid :seon.agent/id (when carried)}`,
and `::flow/ex` is extracted before `prepare` (draft P3: the chain is lost there today).
Under `:panic` `fault!` throws the panic receipt; the adapter recognizes exactly that
receipt (record-once, "once per propagation", final design) and answers `::flow/exit`;
any other throw leaves the adapter and takes the fork's handler-throw path above, which
records nothing again. Under `:record` the proc continues with its pre-step state
(Flow:316) as today.

**N1b — the terminal protocol: exit runs the stop transition, and exit is delivered
[R2].** `::flow/exit` does not bypass `handle-transition`. The fork's loop, on that
answer, sets `nstatus :exit` and runs `(handle-transition step status :exit state)`
(Flow:209-217) inside its own `try`: this is the proc's `::flow/stop` transition, where
Seon delivers `proc-stopped` and releases resources today (`flow.clj:526-528`,
`schedule.clj:809-813`, `turn.clj:5487`, `web.clj:2647`). If the transition throws, the
handler is called once more with `{op :transition ex}` and **the terminal disposition is
retained regardless of the answer**: a proc asked to exit exits. The same rule fixes
D2's sibling under `:record`: a stop-transition throw today restores the old status
(Flow:285, 299, 317-320) and the proc keeps running; with the fork, a failure inside the
transition to `:exit` never un-exits.

Exact terminal observation lives at Flow's run boundary: `start` creates one
promise-chan per proc, exposed as `:exit-chans {pid chan}` in `start`'s return map and in
`(datafy graph)`; the fork's `run` delivers `#::flow{:pid :outcome (:exited | :failed)
:cleanup-ex <transition throwable or absent> :handler-ex <absent or the throwable>}` in a
`finally` around the loop. A promise-chan cannot lose it, and a supervisor joins it under
its bound (N2). The loop returning is never claimed as "resources released": the outcome
carries the actual cleanup result, and `:failed` with a `:cleanup-ex` is what the
supervisor reports. Partial startup (Flow:149-171): `start-proc`'s catch already sends
stop to all; the fork additionally returns the exit-chans of the procs it did start
inside the rethrown `ex-info`'s data, so `start-graph!` joins them under its bound before
rethrowing — no proc is left running behind a construction failure.

The Seon `proc-stopped` promises (`flow.clj:526-528`, `agent.clj` `turn-stopped`) become
readers of the exit-chans in the same slice or are deleted where the exit fact replaces
them; a hand-rolled completion beside the fork's exit fact is a second mechanism.

**N1c — bounded control admission [R3].** Three changes at the dependency owner:

1. An exiting proc untaps its control tap at the run boundary (`async/untap
   control-mult control-tap`, Flow:153-154) before delivering its exit fact, so an exited
   proc never holds the mult (`core.async.clj:797-804`: a mult waits for every tap).
2. `send-command` (Flow:71-75) takes a bound: `(alts!! [[control cmap] (timeout ms)])`;
   a timeout returns `#::flow{:admission :timeout :command … :to …}` instead of blocking.
   `stop`, `pause`, `resume`, `ping` and the failing proc's sibling-stop all use it.
   Public `stop` takes an optional `timeout-ms` (default: the lib's `ping` default,
   1,000 ms) and returns the admission result; it still holds its lock across the bounded
   send (Flow:174-183), which is now bounded, so the lock is too. A timed-out stop is a
   typed failure the caller sees, never `true`.
3. A failing proc under `::flow/exit` sends stop to its siblings through the bounded
   send; on admission timeout the exit fact carries `:sibling-stop :timeout` and the proc
   still exits. Stopping the siblings is then the supervisor's job, which retries `stop`
   under its own bound and reports the missing event; nothing moves the blocking send
   into an unobserved Future.

A busy proc (inside a transform, Flow:288-300) does not drain its tap; with taps of 10 the
mult blocks on the eleventh queued command. Bounded admission makes that a reported
failure at the sender; the supervisor's bound (N2) makes it a named missing event. It is
not termination: expiry retains custody and denies restart or overlapping work until the
exit fact arrives.

**N1d — `:xform` refused before channels exist.** `start-graph!` (`flow.clj:71`) walks
the graph definition's `:chan-opts` (`:xform`, `:in-opts`, `:out-opts`) and refuses
naming pid and cid **before** `flow/create-flow` and `flow/start` create any channel.
The source search found none under `src/seon`; admission enforces it, not the search.
Nothing transacts, waits or stops under a channel mutex.

**N1e — failures emitted as data, recorded by their producer [R1].** Flow's catches
never see these; N1a alone would make them silently losable once the launcher join
(`c2140df4e`) leaves. Three sites in the launcher, all converted in N1's slice, none
adding a channel reader:

| site | today | with N1e |
|---|---|---|
| `::completion` transform (`flow.clj:559-580`): a task's `::throwable` becomes a `::flow/error` output | the map goes to the launcher graph's `error-chan` → the join → the fault channel | the launcher proc, in the same transform and after `release-admission!` (`:563`), calls `(fault/fault! (env/of work) throwable {:seon.error/layer :seon.flow/submission :seon.error/operation <work-fn symbol> ::submission-id …})` with the submission's carried world; the `::flow/error` output is kept as an observation. Exactly-once settlement is unchanged: the submitter's terminal already ran on the task thread (`io-terminal!`, `:425-445`); the fault is recorded once, by the proc, after admission release. Under `:panic` the adapter's disposition applies to the launcher graph as for any proc (N1b) |
| `io-terminal!`'s `complete!` callback (`flow.clj:425-445`, `try`/`finally`, no catch) | a throwing `complete!` propagates to the task thread; the `FutureTask` captures it; lost | a `catch Throwable` around the `kernel/adopt-arm` call records through `fault!` with `(env/of work)` and puts the callback failure into the completion message (`::callback-throwable`) so the launcher proc applies the disposition; the `finally` still releases the submission and offers the completion |
| `execute-io-work!`'s `.execute` rejection (`flow.clj:474-486`) and the compute twin (`execute-work!`) | terminal settled with the rejection | the same: recorded once through `fault!` with the submission's world at the settling site, then settled |

The launcher's `error-chan` therefore needs no reader; `boot/join-launcher-errors!`
leaves, and schedule row 29's stop-order gap closes because nothing crosses a channel
between graphs any more.

**N1f — what leaves with M4 (M4's slice, listed here so the dependency is explicit):**
`counted-dropping-buffer` (`flow.clj:999`), the fault graph and the committer as the
route (`:1005-1090`), `join-fault-committer-errors!` and `report-committer-loss!`
(`:1165-1180`), `start-error-fanout!`'s mult/dropping stage (`:1191-1263`),
`join-error-fanout!` (`:1266`), `stop-error-fanout!` (`:1294`), `boot/join-launcher-errors!`
(`c2140df4e`), `emit-core-fault!` (`cluster.clj:3042-3066`), and the six `offer!`+`println`
sites, which become `fault!` calls (`turn.clj:5366`, `:5389`, `agent.clj:1072`, `web.clj`
×3 — each in its holder's follow-up). `error-chan` and `report-chan` stay unread except
by monitor taps; they slide.

**Why the hook and not the audit's `var-process` wrapper (audit §3.1) [R6].** The
wrapper sees only the transform and transition arities; Flow's outer catch, the compute
timeout inside the inner `try` and `start-proc` are outside it, and the wrapper cannot
make the proc exit — a rethrow inside the step is swallowed by Flow's catch (Flow:312).
The fork hook is one seam at the three catches with Flow's full context; a Seon wrapper
beside it would be a second catch adapter.

**Dependency seams:** Flow:29-36, 38-68, 52, 71-75, 99-100, 107, 149-171, 153-154,
163-165, 174-183, 209-217, 245-246, 258-260, 288-300, 312-323; `impl/channels.clj:78-94`;
`core.async.clj:797-804`; `flow.clj:71-94`, `:132-181`, `:425-445`, `:474-486`,
`:526-528`, `:559-580`, `:760`, `:999-1320`.

**Proof:** §5 gates 1–2. **Cost:** fork ≈ 1.5 days (hook, terminal protocol, exit
facts, bounded admission, untap, fork regressions incl. upstream-compatible defaults);
Seon ≈ 1.5 days (`var-process` option, `start-graph!` refusal and construction
boundary, launcher producer sites, `proc-stopped` replaced by exit facts); ≈ 3 days
total. Risk medium: every proc's catch and exit path changes; every proof in §5 gate 2 is
owed before N1 is called landed.

### N2. Bound every stop: the whole request/stop/join sequence [R3]

The exact per-site change is in the quick-wins landing note (Step 5, rows 26-27) and is
amended here in one respect: the bound covers the **entire** sequence — request
(the armer `>!!`), the stop command's admission (N1c's bounded `stop`), and the exit
joins (N1b's exit-chans) — not only the `<!!` calls after a stop already sent.

- `cluster.clj` `disarm-agents!` (`:3385-3417`): the armer `>!!`+`<!! quiesced` and the
  cluster-loop and render joins go through `seon.await/await!` (`await.clj:110-122`, the
  right request/reply mechanism) under `:seon.config.agent/turn-completion-backstop-ms`
  carried on the loop handle; one deadline across put and take; a
  `:seon.await/timeout-error` or `closed-error` throws `(ex-info message diagnostic)`
  naming the missing event (`::armer-quiesced`, `::cluster-loop-exit`, `::render-exit`).
- `flow.clj` `stop-work-launcher!` (`:752-770`) calls `flow/stop` **between** two bounded
  awaits today; with N1c it calls the bounded `stop` and treats an admission timeout as
  the missing event; its `proc-stopped` deref becomes the exit-chan join.
- `flow.clj` `stop-error-fanout!` (`:1294-1317`) is deleted by M4; if N2 lands first it
  is bounded as the landing note says and deleted with M4.
- `agent.clj` row 25 is in mcp-and-stop's *(wt)* hunk; its `flow/stop` at `:1191` takes
  the bounded form when N1c lands (a follow-up to that holder).
- Expiry semantics, stated once: a fired bound names the missing event and fails; the
  supervisor retains custody of the graph and its resources, denies restart of that agent
  and any overlapping work until the exit fact arrives, and reports the live thread. No
  retry, no second wait beyond the declared one.

**Seams:** `await.clj:110-150`; Flow:174-183; N1b/N1c. **Proof:** §5 gate 2 (stop
part). **Cost:** < 0.5 day after N1c; risk low. Waits on `cluster.clj`'s release by M7's
lane; `flow.clj` is free.

### N3. The listener failure path — loud, and non-recursive [R4]

The ruling: a failed listener hand-off throws into Datahike. Today that ends in
`log/error :datahike/listener-error` (DH `writer.cljc:379-387`) — the transaction is
already delivered (`:398` delivers before `notify-listeners!` at `:406`; the 2026-09-18
fork fix), so a failure callback cannot retroactively fail that transaction and does not
try to; the fault is swallowed by AGENTS' definition.

**The change in the Datahike fork** (one seam, upstream-compatible default):

1. `notify-listeners!` reads an optional failure handler from the connection's meta,
   beside `:listeners` (`(:listener-failure (meta connection))`, installed by Seon at
   connection open through the same `alter-meta!` path `d/listen` uses).
2. On a listener Throwable, **the failed listener is retired first**: its key is
   removed from the connection's listener registry (`swap! listeners dissoc key`) before
   the handler runs. Then `(handler {:listener-key k :exception e :tx-report report})` is
   called on the same thread, after the promise was delivered, never inside the commit
   loop (DH `writer.cljc:256-261`). When no handler is installed, the fork logs as today
   and does not retire (upstream behaviour).
3. The handler's return is ignored; the `doseq` continues to the remaining listeners.
   A handler that throws aborts the remaining `doseq` (the fork catches nothing around
   it), so the Seon handler's contract is to return; its own failure takes the emergency
   path below and returns.

**Why this terminates.** Recording the listener's fault is itself a transaction on the
same connection, which notifies the listeners again. Because the failed listener was
retired before the handler ran, the fault transaction cannot re-invoke it, so there is no
feedback. Callbacks of that listener already in flight on other `:mixed` threads (L1/L2:
notification is concurrent) may fail once more each; retirement is idempotent and each
such failure is one more `fault!` call whose occurrence dedupes by D13 identity in the
writer (a count increment, not a new fact). The bound is therefore the number of
in-flight notifications at retirement, never unbounded.

**The Seon handler**, installed where the connection is opened for a cluster
(`store/open-branch!` / `boot.clj`, free), with that cluster's environment captured at
install — the exact world, not a lookup:
`(fault/fault! world failure {:seon.error/layer :seon.db/listener :seon.error/operation <listener-key> :seon.db/listener-retired true})`.
Under `:record` it returns and notification continues for the remaining listeners. Under
`:panic` `fault!` throws the panic receipt; the handler recognizes it, exposes it through
the process failure observation (N4's seam) and returns — no graph owns a listener, so
there is nothing to stop, and the cluster is marked failed by the recorded occurrence.
If recording itself fails (database down), the handler takes the terminal emergency path:
the original and the recording failure together on stderr and in the process failure
observation, no second `fault!` attempt, return.

**Failed delivery is visible pending work.** The retired key is in the occurrence's
context, and the owning lifecycle re-arms it: the router's owner re-registers at the
next arm (`cluster.clj`'s cluster loop / `wake.clj:495`), the schedule proc at its next
transition, the program-identity observer at the next context acquisition. Until then
`runtime_status` reports the cluster's wake route as retired with the occurrence id,
never as healthy absence.

The router's own catch (`wake.clj:546-547`) is deleted (call-site rule 3: not exist), as
is the `offer!` result drop at `:388`. Once L1 makes the router a proc, N3 covers the
listeners that remain (`::program-identity`, `sci/eval.clj:2366` *(wt)*; the schedule
and call-preparation listeners until L1 deletes them).

**The blocking, bounded now [R6].** The handler's `fault!` parks the `:mixed`
notification thread on Datahike's writer promise for one commit. That pool is unbounded
and cached (`dispatch.clj:106-111`), and the writer's own loops run on other threads of
it, so this is a parked thread, not a deadlock. The pre-agent slice supplies the
**system-write bound** the audit's row 29 left open: `db.clj:3872-3886`'s bounded deref
applies to every write, system writes included, with the existing
`:seon.db/write-bound-exceeded` as the outcome-unknown result; `fault!` reports outcome
unknown without retry and without an overlapping replacement write. This is `db.clj`
(held by writer-cost): a follow-up to that holder, required before N3 is landed.

**Seams:** DH `writer.cljc:256-261`, `:379-387`, `:390-406`; `dispatch.clj:63-69`,
`:106-111`; `db.clj:3872-3886`. **Proof:** §5 gate 3. **Cost:** fork ≈ 0.75 day
(retire-then-handle, in-flight regression), Seon ≈ 0.75 day (handler, emergency path,
re-arm at the three owners, write bound); ≈ 1.5 days; risk low-medium.

### N4. The process uncaught handler — attribute from evidence, or report unknown [R5]

`Thread/setDefaultUncaughtExceptionHandler` is installed **once**, chainsafe (the
existing handler is wrapped, never replaced; a second install refuses), at process root
in `seon.operator.runtime` beside `root-executor-pair`
(`resources/seon/operator/runtime.clj:17-22`), because that namespace is process custody
outside every cluster program. It is the backstop for go blocks (Datahike's writer,
`dispatch.clj:63-69`) and virtual-thread tasks; Futures capture their throwables
(Flow:29-36), so it is not universal and is not claimed to be.

**Custody comes from the throwable, never from `running-instances`.** The handler
attributes only from evidence carried by the failure:

| what the throwable carries | action |
|---|---|
| a panic receipt already recorded by `fault!` (final design: "once per propagation") | propagate: no count, no write; expose the receipt in the process failure observation |
| an `ex-data` world — `:seon.env/environment` or a `:seon.db/connection` — placed there by the boundary that started the work (N3's handler keeps its captured world; a Seon-started virtual task carries its environment in its `ex-info` wrapper) | `fault!` on that world, layer `:seon.process/uncaught`, operation the thread name |
| neither | **explicit process failure**: no database write is chosen (default-or-first was map iteration, rejected); the failure is retained with its whole `Throwable->map` in the process failure observation and printed once to stderr; status reports it as unattributed |

The **process failure observation** is the existing process/graph observation seam
extended, not a sink: `seon.operator.runtime` retains the last N uncaught failures as
live objects (like `*e`: disposable, process-local, never a database write), and
`boot/readiness` → `runtime_status` / `bin/seon status` report them as
`:seon.process/failures` with count, classes, thread names and the exact REPL form to
inspect one. A durable process-error sink exists only if the process owner supplies it
deliberately as a bootstrap setting (`boot.clj`'s tiny bootstrap settings, AGENTS "The
system"), naming the cluster; absent that setting, unattributed failures stay process
observations. A recording failure inside the handler takes the terminal emergency path
(both causes retained, stderr, return) and never writes into another cluster.

The same slice wires http-kit's `:error-logger` (audit row 20; the request carries its
world) → `fault!`; the SSE feed catch (`web.clj:2882`, held by page-key) is a follow-up
to that holder.

**Seams:** `dispatch.clj:63-69`; `runtime.clj:11-22`; final design E:40, 82-84.
**Proof:** §5 gate 3 (process part). **Cost:** ≈ 0.5 day; risk low. Files:
`runtime.clj`, `boot.clj` (free), `web.clj` (follow-up).

### The dependency between N1–N4 and the error route

N1, N3 and N4 call `fault!`, which is M4's function (final design §"One required
namespace") in its synchronous form. The order inside M4's window: M3 (fault cost) →
`seon.fault/fault!` synchronous (M4 step 0, B3's file) → N1 (fork + `var-process` +
`start-graph!` + launcher producers) → N3 (fork + handler + write bound) → N4 → N1f's
deletions and the site conversions. N2 is independent and lands when `cluster.clj`
frees. The batching committer, its ack protocol and the stop-order question it raises
are revisited after the first agents with measured repeat load (§3 L8).

## 3. LATER — the flow restructure, ordered and placed against README §4

Everything below waits for cut 3's first namespace agents unless a measurement promotes
it (triage B2: "promote to MUST if a probe shows …"). Placement follows the review.

| # | slice | files | seam | depends on | cut | cost / risk |
|---|---|---|---|---|---|---|
| L1 | **Router proc** (S4, D3/D4; **ruled**, §4 A): `:seon.cluster.wake/router` `:io` in the cluster graph (`cluster.clj:3181-3190`). The listener only `offer!`s `::look` (sliding-1) into the proc's in-port; the proc wakes each affected agent on its mailbox proc's `:seon.agent/wake` port (the channel `route!` delivers to today, `wake.clj:536-542`, becomes the proc's out-port); the proc registers the listener **before** deriving initial work, captures one `db/db` per look, derives wakes through that value's basis, compares Datahike dependency revisions before rebuilding matchers (proportional to relevant declaration changes, not every commit), and advances its basis only after successful delivery; a late callback causes a redundant look, never a backward cursor. Its recovery contract is the probe in §5 gate 4 (a bare `d/since` is a temporal predicate with nonhistorical collapse, DH `db.cljc:154-192`, `:678-680`, not the `[e a v tx added]` event stream `wake.clj:524-542` consumes). Deletes the per-agent schedule listener (`schedule.clj:799`), the call-preparation watch (`call_preparation.clj:564-583`) and the `::program-identity` listener (`sci/eval.clj:2366` *(wt)*), each becoming a router-delivered wake; context adoption semantics stay with B2. The wake regression suite passes unchanged. | `wake.clj`, `cluster.clj`, `schedule.clj`, `call_preparation.clj`, `sci/eval.clj` | as cited | M4; gate 4's probe | 3, after the first agents | ≈ 1 day after the probe (unmeasured until then) / medium |
| L2 | **Armer owns arm/disarm** (S5, D6): arm and disarm become messages to `:seon.agent/armer` (it already speaks `quiesce`, `cluster.clj:3360-3374`); `submit-source!` and every direct installer inject `{:seon.agent/arm id :reply ch}` and await under the backstop bound; both monitors (`agent.clj:784`, `:1190`) deleted. Limited to lifecycle plumbing; M1/M6 remove the seconds of work under the lock first. | `agent.clj`, `cluster.clj`, the installers | as cited | M1, M6 | 3, after the first agents | ≈ 1 day / medium |
| L3 | **SSE join and http-kit executor shutdown** (S9, D8c/d): exact change in the quick-wins landing "Step 14". | `render/web.clj`, `seon.render.web.edn` | `web.clj:2882`, `:3693`; http-kit `server.clj:321` | none | after the first agents, unless a measured leak blocks operation | 0.5 day / low |
| L4 | **Capability dispatch and evaluations through the launcher** (S7; the launcher stays the admission owner, **ruled**, §4 B): capability plumbing (`effect.clj:492-529` → a new `:io` arm of `submit!!`, `flow.clj:903-914` admits compute only today) may precede cut 4; **wiring turn evaluation** (`submit-evaluation!!`, `turn.clj:3315`, no caller) changes execution semantics regardless of being one call and is cut 4 unless measured starvation earns an explicit MUST promotion. Compute placement (A10.3): compute submissions run on the root `:io` executor today (`flow.clj:687`); measure agent count × evaluation load first. | `effect.clj`, `flow.clj`; `turn.clj:3315` | as cited | §4 B; M4 | plumbing 3; turn wiring 4 | 2–3 days / medium |
| L5 | ~~Backstop proc~~ **dropped**: B2 §6 deletes the observer in favour of the transform's own two bounds; a disposable proc ahead of that is a second mechanism. The existing bound (`turn.clj:5396-5450`) stays until B2's replacement proves it; its `offer!` becomes `fault!` in N1f. | — | — | — | — | 0 |
| L6 | **Model call as a launcher submission** (S8, D10): `ai/complete` (`turn.clj:4520`) as `flow/submit!` `:io` with completion re-entering the turn proc as a message; the retry `Thread/sleep` (`:4605`) as a delayed resubmission. Decided with B2's single execution design, never as a competing plan. | `turn.clj`, `agent.clj` | `flow.clj:525-580`; `turn.clj:4520`, `:4605` | B2 commit 4 | 4 | 2–3 days / high |
| L7 | **Ping during a model call** falls out of L6; until then oversight's `:unknown` is honest. Interrupts stay (§1). | — | — | L6 | 4 | 0 |
| L8 | Report-channel readers and Flow casts: **dropped** (unneeded readers). Render scheduling (`web.clj:2681` coalesce sleep): measured first, valid attribute/declaration-keyed caches preserved. The `acquire!` lock is already gone *(wt)*. **Batching committer** (final design "Burst accounting"): revisited here with measured repeat load after the first agents. | per row | — | — | when measured | small |
| L9 | ~~Flow-native committing with a `:compute` tier~~ **removed from the four-cut deliverable**: a future measured compute optimization, separate follow-up. Generic host computation gains no termination from an SCI arm (README §3:110-112). | — | — | — | — | — |

The skill's stale claims (schedule row 27) are corrected by the skill's owner when N1
lands, since N1 changes the mechanism the skill describes.

## 4. Decisions — RULED by the owner (README §7 "Wake routing through Flow; launcher kept")

The 1.4 projection-sweep question is closed by `a2e9253fe`. Both decisions below were
ruled after the review; the option tables stay as the record of what was priced and why
the ruled option won.

### A. The router's source — RULED: option 1

**Owner:** "Flow processes for datahike listening is a great idea especially since
agents are also flow processes … so we can wake them on their channels." Verified: each
agent is a Flow graph whose `:seon.agent/mailbox` proc reads a `:seon.agent/wake` port
(`agent.clj:481`, `:519-540`). **The design:** one offer-only listener per cluster puts a
payload-free wake on a sliding-1 in-port of a router proc; the router reads its own
connection and wakes each affected agent on its mailbox wake port; a failed hand-off
throws into Datahike through the fork's failure callback (N3). Its recovery contract is
§5 gate 4, run before L1's cost is quoted.

| option | guarantee | cost | gives up |
|---|---|---|---|
| **1: offer-only sliding-1 listener into the Flow in-port; the proc reads its own connection** (Astra's option 1 with a recovery contract). The listener does `(async/offer! look ::look)` and nothing else. The proc registers before deriving initial work, captures one database value per look, processes through that value's basis, compares dependency revisions before rebuilding matchers, advances only after successful delivery. Recovery after restart and across gaps is a **history-preserving bounded query for the declared interests** (retraction, assert-and-retract in one interval, no-history attributes, schema-plus-wake changes), proven by §5 gate 4 before the ~1 day is quoted. | loss-free: durable work re-derives from facts; the callback's unordered, batch-collapsed report is never read, so L2's objection does not apply; one listener, covered by N3; no fork API | ≈ 1 day **after** gate 4's probe; the range read's cost is unmeasured here | a listener remains on the notification path (an `offer!`, microseconds) |
| **2: the fork's `datahike.committed-report` source.** Ordered per-transaction payloads and bounded overflow evidence (DH `committed_report.cljc:67` `open!`, `:140` `offer-committed!`, `:17-25`). **As first written it cannot work:** `poll-batch!` is explicitly non-blocking (`:175-180`), `poll-ready!` consumes a process-global queue (`:7-11`, `:259-275`), `take-ready!` blocks without a timeout (`:278-284`) — per-cluster polling steals another cluster's readiness, busy-spins or stops waking when empty; `:gapped` rejects every later offer (`:115-117`, `:129-134`), so "since once and continue" never reopens; ordered reports still share batch `:db-after` (`writer.cljc:256-261`). Viable only by extending the dependency's per-source readiness into an event-driven Flow port with close/reopen-before-catch-up, overlap dedup, generation fencing and bounded drain. | zero routing listeners; commit order; typed overflow | well beyond the 1.5 days first quoted: fork lifecycle proofs, close/reopen races, concurrent clusters | a fork-only API; a readiness dispatcher unless the readiness becomes per-source and event-driven |
| **3: fixed-buffer blocking hand-off from the listener** | none beyond option 1 | ≈ 0.5 day | blocks the notification thread; not ordered per listener either (notifications stay concurrent, `writer.cljc:390-413`); rejected |

Astra recommended option 1 with the recovery contract. This lane recommended option 2
in its first draft and withdrew it on the review's source facts (non-blocking poll,
global unbounded take, gapped never reopens): option 2 is a fork extension, not a reuse,
and the `d/since` concern that motivated it is answered by reading one captured value
per look. The owner ruled option 1. Option 2 remains only as a possible later extension
of the dependency's own seam if router wake latency or listener cost is ever measured
to matter; nothing schedules it.

### B. Evaluations — RULED: the launcher stays the admission owner; B2 commit 7 retires it only on an admission proof

B2 §5 commit 7 retires `flow.clj:192-944` and futurizes evaluations directly, and
B2:166-172 already gates that on another existing owner proving bounded admission —
"neither `:compute` nor `futurize` supplies a bounded pool by itself". The audit's row 8
and L4 route evaluations through the launcher. The two are reconciled by the gate, not by
choosing one plan over the other.

| option | guarantee | cost | gives up |
|---|---|---|---|
| **1: keep the launcher as the admission owner until B2's gate passes; route through it where L4 lands.** The refusing buffer (`:seon.config.flow/concurrency`, a flat refusal to the submitter), one settled-once terminal, wedged work holding its slot until actual exit — the "timeout includes exit" shape installed today. B2 commit 7 is neither decreed permanent nor pre-approved. | the declared cross-agent bound survives; no second admission owner | 0 additional prerequisite work now; L4's plumbing ≈ 1 day when it lands | nothing now; B2's −800 lines wait for the gate |
| **2: B2 as written now** — futurize on the carried compute executor; one evaluation per agent bounds by agent count. `newFixedThreadPool` (`flow.clj:186-190`) bounds workers, not admission: N agents × 1 evaluation queue with no refusal and no admission fact. | serial per agent | 0 beyond B2 | the cross-agent admission bound — exactly what a 3,145-function campaign loads; this misreads B2's gate as permission for an unbounded queue |
| **3: shrink the launcher in place in cut 4** — keep the refusing buffer, the `::completion` in-port, queued cancellation, arm transfer and retained ownership until actual body exit; delete the capacity observer and the duplicated compute/io bookkeeping; then B2 commit 7 removes the rest if a smaller dependency seam supplies the same four properties. | option 1's guarantee at roughly half the code | ≈ 2 days (design estimate) | schedule risk on `flow.clj` during cut 4 |

Astra and this lane both recommended option 1 now, with option 3's reduction in place
preferred in cut 4 if no smaller dependency seam supplies refusal, queued cancellation,
arm transfer and retained ownership until actual body exit, and L6 decided inside B2's
single execution design. **The owner ruled it:** the launcher is kept as the admission
owner; B2 commit 7's retirement happens only on an admission proof. Option 3 is the
cut-4 shape that proof would most likely take; it is not scheduled by this ruling.

## 5. Acceptance — behavioural classes and measurable gates

One regression per behaviour class, on the canonical branch fixture with real SCI and
armed contracts, run through the installed `seon.test/run` request per slice, never a
lane suite; platform and affected integration at the orchestrator boundary. Destructive
stop and handler tests use **owned isolated custody** (a scratch root and cluster of the
lane's own), never `default` (AGENTS: lanes never stop `default`). Live observations on
`default` are read-only. Every landing note records the exact forms, values and timings,
the fork commit ids and gitlink advances, which path the evidence exercised (hot reload,
new fork, in-place adoption) and the verification boundary; a passing regression proves
the behaviour, not adoption, browser paint or a deletion's safety.

**Gate 1 — every failure entry is recorded before continuation (N1).** Exercise: a step
throw; a `send-outputs` throw to an unresolvable port; a `:compute` timeout whose body
stays alive; a `start-proc` throw with one proc already started; a construction failure
(`prep-proc` shared ids, Flow:44-47); a stop-transition throw in both modes; the
launcher's direct `::flow/error` output; a throwing `complete!` callback; an executor
rejection. Each yields exactly one durable occurrence and one wake on the correct branch
before acknowledged continuation, with a two-link chain where two links were thrown (the
fixture forces two). Under `:panic` there is no "next input": the assertion observes from
the supervisor via the exit fact. More than 100 distinct concurrent failures in one graph
preserve every identity (the channel slid; the store did not). `:xform` anywhere in a
definition refuses at `start-graph!` before any channel exists, naming pid and cid.

**Gate 2 — terminal, cleanup and admission are truthful (N1b, N1c, N2).** Saturate the
control channel and every tap before a panic; throw during stop in both modes; fail the
recorder while stopping; time out a compute Future whose body remains alive. Prove:
bounded command admission (a typed admission timeout, never a blocked sender); the stop
transition ran and its outcome (`:exited` / `:failed` + `:cleanup-ex`) is in the
delivered exit fact; a handler throw retains the exit disposition and both throwables;
an exited proc no longer holds the mult (untapped); no self-join; no resource reuse or
replacement work before the exit fact; expiry retains custody and denies restart; the
whole `disarm-agents!` sequence fails within its one bound naming the missing event; an
independent graph pings and the REPL evaluates throughout; `rg '<!!' src/seon/cluster.clj src/seon/flow.clj`
finds only sites inside `await!`. A proc-completion promise alone does not prove its child
computation exited: the compute-timeout case asserts the body's own exit signal
separately.

**Gate 3 — listener and process failures terminate and attribute honestly (N3, N4).**
Fail a listener on every transaction, including its own fault transaction: prove the
listener was retired before recording, fault writes are bounded by the in-flight count
(assert the exact count with a latch-held concurrent notification), no recursive
recording, remaining listeners behave under each mode (`:record` continues; `:panic`
exposes the receipt and returns), the writer stays usable afterwards, and the retired
route is reported as pending repair then re-armed by its owner. Inject faults on two
branches plus an unattributed process thread: no cross-branch recording, no receipt
recount, the unattributed failure appears in `:seon.process/failures` with its
`Throwable->map` and an explicit database-unavailable status when the store is down.
Verify recipient wake delivery separately from transaction-promise delivery. Installing
the handler twice refuses.

**Gate 4 — the router's recovery contract (L1, before its cost is quoted).** Registration
races (a transaction between listener registration and the first derivation), reordered
callbacks, batch boundaries, retraction, assert-and-retract within one look, no-history
attributes, schema-plus-wake changes in one transaction, restart with pending durable
work, and the query-work bound. Matcher recomputation happens only on relevant
declaration changes (assert the revision comparison, not the commit). "Listener count
zero" is not asserted while other listeners remain; the assertion is "no Seon query on
the notification thread" (a recording listener captures thread and elapsed).

**Gate 5 — cost, measured against the immediate parent.** The measurement script is
committed beside `docs/prds/steward-platform/research/measure-publication-path-2026-09-22.sh`
and its raw samples land in the landing note: wall, CPU, allocation, p50/p95, retained
memory, queue depth and phase breakdown for a no-fault Flow step, an ordinary data-only
write, the first fault, a repeated fault, concurrent faults, a router wake and a stop —
candidate versus its immediate parent with identical admitted workload, fixture basis,
dependency pins and linked valid caches; absolute and delta timings with sample counts.
P4's 1,099.9 ms is prior evidence, never presented as new. A fault transaction causes
zero program/schema/context reconstruction (cache-miss counters at the owners read 0);
a relevant declaration change recomputes only its closure. Hot operations are
sub-second; every operation over 1 s carries its phases; over 10 s is a defect needing
authorization, never an excused benchmark.

**Gate 6 — schema and fork adoption.** Any new occurrence/context member is proven by
incremental declaration adoption on a branch of a live store with warm-cache reuse,
retirement refusal and affected re-derivation (README §7 "Schema change and reset");
no reset, nuke or from-zero reindex as proof. The modified forks are pinned and adopted,
and loaded behaviour is proven separately from source (a form on the running JVM that
exercises the new handler), with the forks' own compatibility tests retained and green.

## 6. Out-of-scope findings recorded while designing (no edits by this lane)

- The `seon-flow-architecture` skill's stale claims (schedule row 27) are a
  high-priority defect and must be corrected with N1, since N1 changes the described
  mechanism. Owner: the skill's owner; not this lane.
- Datahike `replikativ.logging/raise` throws without a cause (schedule row 24h); N3's
  handler records such failures with a one-link chain until that fork fix lands.
- A Flow fault's evidence measures the whole environment carried in `::flow/msg`
  (201 ms, 8.4 MB; schedule row 30). N1 hands `fault!` the same map Flow builds today;
  the cost belongs to `error/prepare`'s owner and gates nothing here once M3 lands.
- The system-write bound (audit row 29) is now a prerequisite of N3, in `db.clj`
  (held by writer-cost); recorded here for the ledger, not filed as a new issue.

## PROPOSAL — one row for README §4 (the orchestrator integrates it after review)

| Step | Slice | Frees |
|---|---|---|
| 3.f | **Flow owns running machinery** ([spec](lane-flow-owns-running-machinery.md); reviewed 2026-09-23; ruled order `a2e9253fe`): MUST-NOW inside M4's window, synchronous `fault!` only — the core.async fork gains one optional per-proc error handler called at its three catches on the proc thread (`::flow/exit` runs the proc's own stop transition and retains the disposition even when cleanup throws; every proc's exit is a delivered fact on a per-proc promise-chan; an exiting proc untaps control; command admission is bounded and typed; `:xform` refused before channels exist), the launcher records the failures it emits as data through the same function at their producers (no channel reader), the Datahike fork's `notify-listeners!` retires a failing listener before calling a per-connection failure handler (default: today's log) so recording cannot recurse, the process uncaught handler attributes only from evidence the throwable carries and otherwise reports an explicit process failure, and every stop sequence in `cluster.clj`/`flow.clj` — request, bounded stop, exit join — runs under one `await!` bound with expiry retaining custody; the counted-dropping route, fault graph, fan-out joins, `emit-core-fault!` and the six `offer!`+`println` sites leave with M4. Ruled (README §7 "Wake routing through Flow; launcher kept"): the router is a Flow proc fed by one offer-only sliding-1 listener, reading its own connection and waking each affected agent on its mailbox wake port, a failed hand-off throwing into Datahike through the fork's failure callback; the launcher stays the admission owner and B2 commit 7 retires it only on an admission proof. LATER, after the first namespace agents: the router proc deleting the per-agent, call-preparation and program-identity listeners; the armer owning arm/disarm; SSE join and http-kit shutdown; capability dispatch through the launcher; with B2 in cut 4: turn evaluation wiring, the model call as a submission, ping during a call; batching revisited on measured repeat load | every cluster thread under Flow or the launcher with a delivered exit; no fault crosses a dropping channel; no Seon work on the writer's thread; stop is bounded end to end; the turn proc observable during a model call (cut 4) |
