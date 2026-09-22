---
type: plan
status: design 2026-09-23 (Fable, from the Opus fact pack, the flow audit and the Astra error-route design); astra review requested; no src/test/resources edits by this lane
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

Inputs, read end to end: AGENTS.md; README §2–§7; the
[fact pack and triage](../../../research/agent-platform/flow-fact-pack-and-triage-2026-09-23.md)
(F1–F13, L1–L5, D1–D10, M1–M13, S1–S9); the
[flow usage audit](../../../research/agent-platform/flow-usage-audit-2026-09-23.md)
(47 sites, three options); the
[error-route final design](../../../research/agent-platform/error-route-final-design-2026-09-23.md)
(Astra, option 2: a Flow-native failure hook plus one acknowledged batching committer)
and its [draft](../../../research/agent-platform/one-error-route-design-2026-09-23.md);
the [flow skill](../../../../.agents/skills/seon-flow-architecture/SKILL.md) (stale
claims listed in the audit §7, schedule row 27); core.async `flow/impl.clj` at gitlink
`dc35f3e0`; Datahike `writer.cljc` and `committed_report.cljc` at gitlink `fbd1ad2d`.
Below, **Flow** = `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj`,
**DH** = `reference-code/datahike/src/datahike/`. Seon line numbers are the working tree
of 2026-09-23 22:00Z (HEAD `d4d4e1369` plus running lanes' hunks); a hunk not yet
committed is marked *(wt)*.

Source facts this design never contradicts: a proc runs one transform at a time and
reads control only between transforms (Flow:288-300); a `:compute` `.get` timeout
reports a late result and stops nothing (Flow:258-260); `error-chan` is sliding-100
(Flow:102) and `stop` closes it before any proc exits (Flow:174-183) — stop is a
command, not a join; Datahike listeners run concurrently on core.async's `:mixed` pool
with no ordering and, under batching, a shared `:db-after` (DH `writer.cljc:390-406`;
fact pack L2); a throwing listener is caught and logged per listener (DH
`writer.cljc:379-387`).

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
holds — Flow's catch, Datahike's ordered commit stream, core.async's executors — plus
machinery to move the copy somewhere else, plus a guard where the copy lost something.
Every hop is a place the fault can be dropped, and AGENTS.md's error policy forbids
exactly that ("never dropped by an overload channel").

**The simpler way, as data flow.** The failure is recorded **where Flow already catches
it, on the proc's thread, before the proc continues**: the maintained fork's catches
call one optional handler with the failure map; the handler is `seon.fault/fault!`
(B3/Astra), which stores the chain, wakes the responsible agent and applies the one
dial; under `:panic` the handler's answer is terminal and the proc exits, Flow stops its
siblings through its own control channel, and the recorded occurrence is what status,
MCP and the page show as `:failed`. `error-chan` becomes an observation of already
recorded failures and may slide. Everything the cluster runs is either a proc under
`flow/stop` with a bounded, named completion wait, or a launcher submission admitted
under a declared bound whose completion returns as a message. The wake router becomes a
proc fed by Datahike's own commit-ordered report source, so no Seon work runs on the
writer's thread and no listener catch exists to swallow anything. Nothing new is
scheduled, dispatched or cached; the pieces are the dependency's.

## 1. The end state and why

| principle | end state | why |
|---|---|---|
| **Flow owns every cluster thread's lifecycle** | Every thread a cluster starts is a Flow proc (built through `var-process`, `flow.clj:132`; stopped by `flow/stop`; joined at its `::flow/stop` transition under a declared bound) or a launcher task (admitted by the refusing buffer, `flow.clj:304-345`; settled exactly once at the `::completion` in-port, `flow.clj:525-580`; drained at stop). The SSE writer per tab is the one exception the audit keeps (row 19): Flow topology is static and tabs are dynamic; it is registered and joined at `web/stop!` under the feed bound. | AGENTS "Bounded, event-driven execution": every surface has a declared bound at admission and awaits the exact terminal event; a thread nobody joins is an unobserved exit (D8). |
| **Long or blocking work goes through the bounded launcher; completion is a message** | The launcher proc (`flow.clj:510-580`) already has the shape: `::compute-submission`/`::io-submission` in, `::completion` back in, one terminal per submission. A model call (S8), an SCI evaluation (S7) and a capability handler (audit row 9) become submissions whose completion re-enters the owning proc as a message on a declared in-port. The owning proc therefore never blocks inside a transform, so it answers ping and takes stop between messages (F5, D10). | A proc is deaf during a transform (Flow:288-300); the only way to stay observable across a 51 s model call (audit P2) is to not be inside the transform for it. |
| **One error route, composed with Flow** | `fault!` is the one required function (final design). The fork hook calls it from Flow's catches; every out-of-proc boundary (boot request, MCP eval, http-kit `:error-logger`, subprocess checks) calls it directly; the process uncaught handler is the backstop. `fault!` decides synchronous commit (first/new identity) versus the acknowledged batching committer (repeats) and applies `policy(mode, committed?)`. The committer is a cluster-graph proc whose own failures take the emergency path (stderr diagnostic, fail waiters, stop the committer, never re-enqueue). The counted-dropping route, the fault graph, both fan-out joins and `emit-core-fault!` are deleted. | AGENTS error policy: stored with its chain at the owning boundary, delivered through the wake route, loud under `:panic`, panic in both modes when the database is down. |
| **A failed graph is positively visible** | Under `:panic` the proc exits and Flow stops the graph; the stored occurrence carries the graph/agent identity and `:seon.error.occurrence/panic-mode`; `seon.problems/open-panics` (final design; `problems.clj:109-128` today holds `error-signatures`) joins it to the agent, and oversight (`oversight.clj:186 flow-status`) reports `:failed <error id>` for that graph instead of `:unknown`. `runtime_status`, `bin/seon status`, every MCP response and the namespace page read the same derivation. | "A failed graph is positively visible" (AGENTS); "derive state, do not remember it" — the fact is the occurrence, not an in-memory flag. |
| **No Seon work on the writer's thread** | One committed-report reader proc per cluster (§4 decision A, recommended) or one offer-only listener (option B). No matcher rebuild, no `snapshot` derivation, no per-agent listener on the notification path (D3, D4). | A listener runs concurrently and unordered on the `:mixed` pool with batch-shared `:db-after` (L2); a router that rebuilds matchers from that db can act on a stale value. |
| **Interrupt is a request; exit is a fact** | `Thread.interrupt` on proc threads stays the stop accelerator until S8 lands (`agent.clj:1024-1039` *(wt)*). An interrupt that lands in a Datahike `transact!` deref leaves that write outcome-unknown to the caller; the turn records the evaluation as interrupted (AGENTS "Interrupted execution never resumes") and recovery reads the facts, never the promise. No wait after interrupt is unbounded. | AGENTS "A timeout is not termination"; README §3 "Timeout includes exit". |

What this design does NOT add: a scheduler, a dispatcher, a second error channel, a
replay store, a retry loop, a thread registry beyond Flow's procs and the launcher's
admitted set, or a namespace roster for who may call `fault!`.

## 2. MUST-NOW — the flow pieces before namespace agents

These are the four "minimal flow pieces needed now" the triage names (B4 end), each one
a bounded slice. They land inside M4's window (after M3's fault-cost fix, `error.clj`
P4: 1,099.9 ms in-transaction vs 26.1 ms outside) except N2, which depends on nothing.
Ownership per the ledger `tmp/orchestrator/file-ownership.md` at 22:00Z: `flow.clj`,
`cluster/boot.clj`, `cluster/wake.clj`, `schedule.clj`, `reference-code/core.async`,
`reference-code/datahike`, `.gitmodules` are free; `cluster.clj` is held by
runtime-status-crash (M7), `cluster/agent.clj` by mcp-and-stop (M5), `sci/eval.clj` by
sci-program-revisions (M1), `render/web.clj` by page-key. A slice needing a held file
goes to its holder as a follow-up or waits; it never runs beside it.

### N1. The fork error hook, and channel `:xform` refused at admission (S1)

**Fact first: there is no Seon fork of core.async.** `.gitmodules:17-19` points at
`https://github.com/clojure/core.async.git`; Datahike is already the personal fork
(`git@github.com:seantempesta/datahike.git`). Step one is a fork under the same account,
the gitlink repointed, pushed without asking (owner rule: personal forks push when a fix
lands, never a PR upstream).

**The change in the fork** (Flow, four catch sites, one construction option):

| site | today | with the hook |
|---|---|---|
| step catch Flow:312-316 | `>!!` the map `{pid status state count cid msg op :step ex}` to `error-chan`, continue with pre-step state | call `(on-error failure-map)` first, on the proc thread; if it returns `::flow/continue`, put the map on `error-chan` (observation) and continue exactly as today; if `::flow/exit`, put the map, send `::flow/stop ::flow/all` on control (the shape `start-proc` already uses at Flow:164), and return `[:exit state …]` so this proc's loop falls out (Flow:321) |
| outer catch Flow:317-320 (transition, control, `send-outputs`) | same put, continue | same protocol; a failure in the `::flow/stop` transition is handed to the hook BEFORE the closed-channel put, which closes D2 |
| `start-proc` catch Flow:163-165 | send stop to all, rethrow | call the hook with `{pid ex op :start}`, then the existing stop + rethrow (the graph owner sees the throw; the fact is already recorded) |
| `:compute` timeout Flow:258-260 | `TimeoutException` into the step catch while the Future runs | unchanged mechanically; the hook receives it like any step failure. README §3 "Timeout includes exit" is Seon's obligation: no Seon proc declares `:compute` with substantive work until S7 gives it the SCI arm (audit §1) |
| channel `:xform` ex-handler Flow:103-111 | `put!` to `error-chan` under the channel mutex (`impl/channels.clj:78-94`) | **not hooked**: the handler runs during buffer mutation under the mutex; nothing may transact, wait or stop there (final design). Seon refuses `:xform` at admission instead (below) |
| construction `create-flow` Flow:52 / `proc` Flow:245 | no handler | `:on-error` accepted per proc in `proc`'s options map (so `var-process` passes it per proc) and as a graph-wide default in `create-flow`'s config; absent = today's behaviour, so upstream tests still pass |

Handler contract, in the fork's own vocabulary: `(fn [failure-map]) → ::flow/continue | ::flow/exit`.
The map is Flow's existing map plus `::flow/op` for the two catches that lack it. A
handler that throws is not caught by Flow: the throw leaves the proc loop and reaches the
executor's uncaught path (the proc's `run` is a `FutureTask`, Flow:29-36 — a Future
captures the throwable, so **N4's uncaught handler does not see it**; the fork therefore
wraps the handler call once: on a handler throw it prints the emergency diagnostic
`(pr-str (Throwable->map t))` to stderr with the failure map's pid, sends stop to all and
exits the proc. Print-only is not the outcome: the handler's own failure is `fault!`'s
"database down" case, which already stops the graph and panics in both modes.)

**The change in Seon** (`flow.clj`):

- `var-process` (`:132`) takes `::on-error` as a required member of `options` — the
  cluster owner supplies it once at graph construction, so `create-flow` definitions stay
  data. It passes it to `flow/process` as `:on-error`. The Seon adapter is
  `(fn [failure] (case (::disposition (fault/fault! world failure ctx)) :record ::flow/continue :panic ::flow/exit))`
  where `world` is the environment the proc's args already carry
  (`env/refuse-absent-environment!` at `:167` guarantees it), `ctx` is
  `{:seon.error/layer :seon.flow/proc :seon.error/operation <step Var symbol> ::flow/pid pid :seon.agent/id (when carried)}`,
  and the `::flow/ex` is extracted before `prepare` (draft P3: today the chain is lost
  there). Under `:panic`, `fault!` throws to an ordinary caller; the Flow adapter
  catches only the panic receipt it recognizes (record-once: "once per propagation",
  final design) and returns `::flow/exit` — it never recatches anything else.
- `start-graph!` (`:71`) refuses any proc whose `:chan-opts` names `:xform` (also
  `:in-opts`/`:out-opts`), naming the pid and cid. The source search found none under
  `src/seon`; admission enforces it, not the search.
- **Deleted in the same slice as M4:** `counted-dropping-buffer` (`:999`), the fault
  graph and `fault-committer-step` as the *route* (`:1005-1090`; the committer proc
  survives only as `fault!`'s batching worker, fed by a fixed-buffer channel with an ack
  promise, final design "Burst accounting"), `join-fault-committer-errors!` and
  `report-committer-loss!` (`:1165-1180`), `start-error-fanout!`'s mult/dropping stage
  (`:1191-1263`), `join-error-fanout!` (`:1266`), `stop-error-fanout!` (`:1294`),
  `boot/join-launcher-errors!` (`c2140df4e`, whose completion is never awaited and whose
  stop-order gap is schedule row 29), `emit-core-fault!` (`cluster.clj:3042-3066`), and
  the six `offer!`+`println` sites, which become `fault!` calls (`turn.clj:5366`,
  `:5389`, `agent.clj:1072`, `web.clj` ×3 — each in its holder's follow-up).
- `error-chan` and `report-chan` stay unread except by monitor taps; they slide. Loss
  there is loss of an observation of an already recorded failure.

**Why the hook and not the audit's `var-process` wrapper (audit §3.1).** The wrapper
sees only the transform and transition arities; Flow's outer catch (control,
`send-outputs`, the compute timeout) and `start-proc` are outside it, and the wrapper
cannot make the proc exit — a rethrow inside the step is swallowed by Flow's catch
(Flow:312). The fork hook is one seam covering every catch with Flow's full context, and
it is the change Astra's design recommends.

**Dependency seams:** Flow:52, 103-111, 163-165, 245, 258-260, 288-300, 312-320, 321;
`impl/channels.clj:78-94`; `flow.clj:71-94`, `:132-181`, `:999-1320`.

**Proof (regressions on the canonical branch fixture, real SCI, armed contracts):**
(a) a step throw, a `::flow/stop`-transition throw, a `send-outputs` throw to an
unresolvable port, a `:compute` timeout and a `start-proc` throw each produce one stored
occurrence with a ≥2-link chain BEFORE the proc's next input is read (assert on the
branch inside the step's next call); (b) more than 100 distinct concurrent failures
across one graph preserve every identity (the channel slid; the store did not);
(c) under `:panic` the failing graph's procs exit (their `proc-stopped` completions
deliver within the bound), a sibling graph answers ping, the REPL evaluates, and
`flow-status` reports `:failed <id>`; (d) a graph definition with `:xform` on any channel
refuses at `start-graph!` naming pid and cid; (e) a handler throw (a refusing writer) exits
the proc, stops the graph and leaves the emergency diagnostic on stderr with both causes.
Live: one deliberate `(throw …)` in a scratch proc on `default` after adoption →
`runtime_status` shows the occurrence and the graph `:failed`; `bin/seon status` shows the
open panic.

**Cost:** fork + gitlink ≈ 0.5 day; Seon side ≈ 1 day with regressions (fact pack S1);
the M4 deletions above are M4's own slice. Risk medium: every proc's catch path changes;
the hook runs on the proc thread and must never park on a committer that is itself a
proc — `fault!`'s synchronous path parks on Datahike's writer (allowed: it is not a
proc), and its batching path parks on the committer's ack (allowed for every proc except
the committer, whose adapter is the emergency handler).

### N2. Bound every stop wait (S3, D7, M5's remainder)

The exact change is already written in the quick-wins landing note (Step 5, rows 26-27)
and is not repeated here beyond its shape:

- `cluster.clj` `disarm-agents!` (`:3385-3417`): the armer `>!!`+`<!! quiesced` and both
  completion `<!!` go through `seon.await/await!` (`await.clj:110`) under
  `:seon.config.agent/turn-completion-backstop-ms` carried on the loop handle; a
  `:seon.await/timeout-error` or `closed-error` throws `(ex-info message diagnostic)`
  naming the missing event (`::armer-quiesced`, `::cluster-loop-completion`,
  `::render-completion`).
- `flow.clj` `stop-error-fanout!` (`:1294-1317`): the same bound — but this function is
  deleted by N1/M4, so the honest order is: if M4 lands first, row 27 has nothing left;
  if N2 lands first, bound it as the landing note says and delete it with M4.
- `agent.clj` row 25 is in mcp-and-stop's *(wt)* hunk (`await-turn-completion!` always
  has the backstop in its `alts!!` ports).

**Seam:** `await.clj:110-150`; Flow:174-183 (why a join is needed at all).
**Proof:** a proc that never publishes its completion (a step that parks on a promise)
makes `disarm-agents!` throw within the bound naming the event; the JVM and REPL stay up;
no `<!!` without a bound remains in `cluster.clj`/`flow.clj` (`rg '<!!' src/seon/cluster.clj src/seon/flow.clj`
lists only sites inside `await!`). **Cost:** < 0.5 day; risk low. Waits on
`cluster.clj`'s release by M7's lane; `flow.clj` is free.

### N3. The listener failure path — how the fork makes "throw into Datahike" loud

The ruling: a failed listener hand-off throws into Datahike. Today that ends in
`log/error :datahike/listener-error` (DH `writer.cljc:379-387`) — the transaction is
already delivered (`:398` delivers before `notify-listeners!` at `:406`), the writer is
not stranded (the 2026-09-18 fork fix), and the fault is swallowed by AGENTS' definition.

**The change in the Datahike fork** (one seam, upstream-compatible default):

- `notify-listeners!` reads an optional failure handler from the connection's meta,
  beside `:listeners` (`(:listener-failure (meta connection))`, an atom holding one
  fn or nil, installed by Seon at connection open through the same `alter-meta!`
  path `d/listen` uses). Per listener: `(callback tx-report)`; on Throwable, when the
  handler is present call `(handler {:listener-key k :exception e :tx-report report})`
  on the same thread, after the promise was delivered, never inside the commit loop;
  when absent, log as today. The handler's return is ignored.
- A handler that itself throws is **not caught** by the fork: the throw leaves the
  `transact!` go block. core.async's go machinery hands it to `dispatch/ex-handler`
  (`impl/dispatch.clj:63-69`), which conveys it to the current thread's uncaught handler
  — that is N4's process handler. So "handler failure" = "`fault!` could not record or
  panicked" reaches the one backstop with both causes, and nothing is recatched.
- Regression in the fork (extends `writer-error-test`): a throwing listener with a
  handler installed → the handler receives key, exception and report; the transaction
  promise was delivered first; a later listener still runs; a subsequent transaction
  commits. A handler that throws → the throw reaches a test-installed uncaught handler on
  that thread.

**The change in Seon:** one handler per connection, installed where the connection is
opened for a cluster (`store/open-branch!` / `boot.clj`, free), calling
`(fault/fault! world failure {:seon.error/layer :seon.db/listener :seon.error/operation <listener-key>})`
with the cluster's environment. Under `:record` it returns and notification continues;
under `:panic` `fault!` throws the panic receipt → uncaught handler → the cluster is
marked failed by the recorded occurrence (no graph owns a listener; the recipient is the
namespace owner then `escalate-to`, never nobody). The router's own catch
(`wake.clj:546-547`) is deleted (call-site rule 3: not exist), as are the `offer!` result
drops at `:388`. This is the "listener backstop half of A9"; the other half — the router
becomes a proc — is §3/§4 and removes the router listener entirely, leaving N3 for the
listeners that remain (`::program-identity`, `sci/eval.clj:2366` *(wt)*, until S4
replaces it too).

**Blocking on the notification thread, stated:** the handler's `fault!` parks the
`:mixed` dispatch thread on Datahike's writer promise for one commit. That pool is
unbounded and cached (`dispatch.clj:106-111`); the writer's own loops run on other
threads of it, so this is a parked thread, not a deadlock. It is bounded by the write
bound `db.clj` already applies to system writes only if that bound exists (audit row 29
leaves system writes unbounded — a separate `db.clj` question for its owner); the PRD
does not claim it is bounded until it is. "Never await a same-writer transaction from
its transaction function" (final design) is about tx-fns and is respected: the listener
runs after the commit.

**Seams:** DH `writer.cljc:379-387`, `:390-406`, `:256-261` (commit loop, where
listeners do NOT run); `dispatch.clj:63-69`. **Proof:** on the canonical branch under
`:record`, a listener that throws produces one occurrence naming the listener key with the
original as cause, the transaction's caller got its report first, and a later
transaction commits; under `:panic` the same throw produces the occurrence and the
process uncaught handler receives the panic receipt (test-installed handler asserts it).
**Cost:** fork ≈ 0.5 day (change + regression), Seon ≈ 0.5 day; risk low. Files:
`reference-code/datahike` (free), `boot.clj` or `store.clj` (free), `wake.clj` (free).

### N4. The process-wide uncaught handler (S9's backstop, audit row 30)

`Thread/setDefaultUncaughtExceptionHandler` is set **once** at process root, in
`seon.operator.runtime` beside `root-executor-pair` (`resources/seon/operator/runtime.clj:17-22`),
because that namespace is process custody outside every cluster program. It calls
`fault!` with the process world. What "the process world" is, ruled here rather than
left open: the handler reads `running-instances` (`runtime.clj:11`, existing custody,
not a new registry) and records against the development cluster's connection
(`"default"` when running, else the first booted instance) with
`:seon.error/layer :seon.process/uncaught`, `:seon.error/operation` the thread name, the
agent unknown. When no instance is running, or recording fails, it takes `fault!`'s
"database down" path: emergency diagnostic to stderr with the whole `Throwable->map`,
and — since no graph owns the thread — nothing to stop; the JVM stays up. Futures capture
their throwables, so this handler is the backstop for go blocks (Datahike's writer,
`dispatch.clj:63-69`), virtual-thread tasks started with `Thread/startVirtualThread`,
and N3's handler throws; it is not universal and the PRD does not claim it is.

The same slice wires the two foreign callback seams that are free today: http-kit's
`:error-logger` (audit row 20) → `fault!`; the SSE feed catch (`web.clj:2882`, held by
page-key — a follow-up to that lane).

**Seams:** `dispatch.clj:63-69`; `runtime.clj:11-22`. **Proof:** a `go` block that throws
on `default` (a scratch form) produces one occurrence with layer `:seon.process/uncaught`
and the thread name; the same with every instance stopped prints the emergency diagnostic
and returns; installing the handler twice is refused (chainsafe: the existing handler is
wrapped once, never replaced). **Cost:** ≈ 0.25 day; risk low. Files: `runtime.clj`
(free), `boot.clj` (free), `web.clj` (follow-up to its holder).

### The dependency between N1 and the error route

N1/N3/N4 call `fault!`, which is M4's function (final design §"One required namespace").
Until M4 lands, the fork hook and the listener handler have no callee. The order inside
M4's window is therefore: M3 (fault cost) → `seon.fault/fault!` (M4 step 0, B3's file)
→ N1's fork + `var-process` option + N3's fork + N4 → M4's deletions and the site
conversions. N2 is independent and lands when `cluster.clj` frees. A10.2's question
("which does the fork hook call?") is answered above: always `fault!`; `fault!` alone
decides synchronous versus batched; the committer proc's own adapter is the emergency
handler, so a committer failure never enqueues itself.

## 3. LATER — the flow restructure, ordered and placed against README §4

Everything below waits for cut 3's first namespace agents unless a measurement promotes
it (triage B2: "promote to MUST if a probe shows …"). Order is by dependency, then value.

| # | slice | files | seam | depends on | cut | cost / risk |
|---|---|---|---|---|---|---|
| L1 | **Router proc** (S4, D3/D4): `:seon.cluster.wake/router` `:io` in the cluster graph (`cluster.clj:3181-3190`); source per §4 decision A (recommended: the fork's `datahike.committed-report`, opened per connection generation, `open!` DH `committed_report.cljc:67`, `poll-batch!` `:175`, `poll-ready!` `:259`, `close!` `:295`; the commit loop offers in order at `writer.cljc:260`) or B (offer-only listener + `d/since` from the proc's basis `t`). The proc keeps its last-handled basis `t` as proc state, rebuilds matchers only when a schema/wake datom is in the batch, delivers wakes exactly as `route!` does today; on a `:gapped` source it reads `d/since` from its basis once and continues. Deletes the per-agent schedule listener (`schedule.clj:799`, the router derives schedule interest), the call-preparation watch (`call_preparation.clj:564-583`, "an optimization only"), and the `::program-identity` listener (`sci/eval.clj:2366` *(wt)*, which becomes a router-delivered wake to the context owner). The wake regression suite passes unchanged. | `wake.clj`, `cluster.clj`, `schedule.clj`, `call_preparation.clj`, `sci/eval.clj` | as cited | M4 (the proc's failures take the hook); §4 decision | 3 (after the first agents) | 1–2 days / medium: the suite must pass; L2's out-of-order batch-shared reports are what the current router already receives |
| L2 | **Armer owns arm/disarm** (S5, D6): arm and disarm become messages to `:seon.agent/armer` (it already speaks `quiesce`, `cluster.clj:3360-3374`); `submit-source!` and every direct installer inject `{:seon.agent/arm id :reply ch}` and await the reply under the backstop bound; both monitors (`agent.clj:784`, `:1190`) deleted. M1/M6 remove the seconds of work under the lock first, so this slice changes serialization ownership, not cost. | `agent.clj`, `cluster.clj`, the installers | `cluster.clj:3360-3374`; `agent.clj:917`, `:1190` | M1, M6 landed | 3 | ≈ 1 day / medium: all installers convert in one slice |
| L3 | **SSE join and http-kit executor shutdown** (S9 remainder, D8c/d): exact change in the quick-wins landing "Step 14" (feeds registered, `.join` under the feed bound; `workers` shut down and awaited; a live thread after the bound throws naming the count). | `render/web.clj`, `seon.render.web.edn` | `web.clj:2882`, `:3693`; http-kit `server.clj:321` drain | none | any (page-key's follow-up) | 0.5 day / low |
| L4 | **Evaluations and capability dispatch through the launcher** (S7, audit rows 8–9): `submit-evaluation!!` (`turn.clj:3315`, no caller today) called from the turn; `effect/dispatch` (`effect.clj:492-529`) through a new `:io` arm of `submit!!` (today it admits compute only, `flow.clj:903-914`), gaining the io admission bound and one settled-once terminal. The compute-placement question (A10.3): compute submissions run on the root `:io` executor today (`flow.clj:687`), so the bound is the admission count, not CPU; **measure agent count × evaluation load first** and move to the bounded platform executor (`runtime.clj:17-21`) only with the SCI arm carried and a measured need. Conflicts with B2 commit 7 (launcher retired) — §4 decision B. | `turn.clj:3315` (one call, the plumbing-first rule allows a one-line caller; the turn function itself is untouched), `flow.clj`, `effect.clj` | as cited | §4 decision B; M4 | 3 late / 4 | 2–3 days / medium |
| L5 | **Backstop proc** (S6, D8a): `:seon.agent/backstop` `:io` in the agent graph, in-ports `parts`/`cancel` plus an `async/timeout` kick (the shape `25de4dc81` gave the schedule timer); when it fires the transform throws the diagnostic so the fault takes the hook; the executor loop (`turn.clj:5396-5450`) and its `offer!` go. B2 §6's "the completion observer IS the transform's own wait" may dissolve this proc again; the ruling is B2's. | `turn.clj`, `agent.clj` | `turn.clj:5396-5450`; Flow:289-294 (`::flow/input-filter`) | N1 | 4 | 0.5 day / low, but it edits `turn.clj` |
| L6 | **Model call as a launcher submission** (S8, D10): `ai/complete` (`turn.clj:4520`) becomes `flow/submit!` `:io`; completion re-enters the turn proc as a message on a new in-port (call/resume split); the retry `Thread/sleep` (`:4605`) becomes a delayed resubmission through `async/timeout`; the turn proc answers ping and takes stop during a call; `interrupt-graph!` loses its reason. | `turn.clj`, `agent.clj` | `flow.clj:525-580` (the completion in-port pattern); `turn.clj:4520`, `:4605` | L4, B2 commit 4 (one turn function) | 4 | 2–3 days / high: a turn restructure |
| L7 | **Ping during a model call** | falls out of L6; until then oversight's `:unknown` for an open turn is honest and stays | — | — | L6 | 4 | 0 |
| L8 | Report-channel readers (D9), Flow casts (F13), the `render/coalesce-ms` sleep (audit row 21), the `acquire!` lock → commit-id `delay` (row 18; already gone *(wt)* in sci-program-revisions) | per row | — | — | when convenient | small |
| L9 | **Flow-native committing with a real `:compute` tier** (audit option 3) | `flow.clj`, `fault.clj` | Flow:258-260 | L4's SCI arm on `:compute` | after cut 4, if measured | 2 days |

Placement rule, restated from README §4 and the 2026-09-23 ruling: L1–L3 are cut-3
work after the first namespace agents start (they touch no turn code); L4 straddles
(its `turn.clj` edit is one call site); L5–L7 are cut 4, the last cut, with B2. The
skill's stale claims (schedule row 27) are corrected by the skill's owner when N1 lands,
since N1 changes the mechanism the skill describes.

## 4. Open decisions for the owner

The 1.4 projection-sweep question is closed by `a2e9253fe` (after cut 3's first
namespace agents) and is not reopened here. Two remain, each with three priced options;
the recommendation is marked.

### A. The router's source

| option | guarantee | cost | gives up |
|---|---|---|---|
| **1 (simplest viable): offer-only listener + `d/since` from the proc's basis `t`** (the audit's row 1 shape). The listener does `(async/offer! look ::look)` on a sliding-1 channel and nothing else; the router proc reads `(d/since db basis-t)` on each wake, rebuilds matchers when the range holds a schema/wake datom, and advances `basis-t`. | loss-free (work derives from facts); one listener remains, with N3 as its failure path; no fork API beyond N3 | ≈ 1 day; one `since` read per wake (the merge pack measured a `since` floor of ≈ 130 ms on retained history — the proc must read the tx range, not scan history, or this is the wrong cost) | commit ordering under batching is reconstructed from the range, not given; the listener still runs on the notification thread (an `offer!`, microseconds) |
| **2 (RECOMMENDED): the fork's `datahike.committed-report` source.** The router proc opens one source per connection generation (`open!`, DH `committed_report.cljc:67`), and its transform is driven by `poll-ready!`/`poll-batch!` (`:259`, `:175`) through an in-port the proc feeds itself (an `async/thread` is not allowed — the proc reads the source with `poll-batch!` inside its `:io` transform under a declared bound, returning to `alts!!` between batches so control is heard). Reports arrive in commit order with their commit db; overflow marks the source `:gapped` with counters (`:17-25 public-evidence`), and the proc then reads `d/since` from its basis once and continues. | commit-ordered, bounded, gap-signalled, **zero listeners for routing** (N3 survives only for the remaining listeners until they go); no Seon code on the writer's path at all (`offer-committed!` at `writer.cljc:260` is the fork's own non-blocking offer) | ≈ 1.5 days; the source's readiness capacity is structural (4,096 active sources, `:8`); one per cluster is far under it. Seon used this seam before (`2b584a6f2`, `2750c158d`); it left in `f25e34594`, the tree split that made the fresh tree the project, not for a defect in the seam — the old router is evidence to quarry, not baggage | a fork-only API (upstream Datahike has no committed-report); the proc must poll between control reads rather than block on one channel, so its loop shape differs from every other proc |
| **3: fixed-buffer blocking hand-off** — the listener `>!!`s the full report into a fixed buffer the router proc reads. | ordered per listener, no since read | ≈ 0.5 day | blocks Datahike's notification thread under load (L1: listeners run in the transact! go block) — the exact "work on the writer path" the audit forbids; rejected unless the owner wants backpressure into the writer |

Recommendation: 2, because it is the only option with no listener on the routing path,
and the fact pack's L2 measurement (six concurrent callbacks, batch-shared `:db-after`)
means option 1's router would still be handed unordered, batch-collapsed reports. If the
`f25e34594` reason turns out to be a defect in the seam, fall back to 1.

### B. Evaluations: the launcher stays as the admission owner, or B2 commit 7 retires it

B2 §5 commit 7 retires `flow.clj:192-944` and futurizes evaluations directly, admitting
"neither `:compute` nor `futurize` supplies a bounded pool by itself" and gating the
retirement on "that existing admission owner preserves its guarantee". The audit's row 8
and this PRD's L4 route evaluations THROUGH the launcher. These contradict; one must be
ruled.

| option | guarantee | cost | gives up |
|---|---|---|---|
| **1 (RECOMMENDED, simplest viable): the launcher stays as THE admission owner; B2 commit 7 is amended to route `submit-evaluation!!` through it and keep `flow.clj:192-944`.** | a declared, refusing cross-agent bound (`:seon.config.flow/concurrency`) with a flat refusal value to the submitter, one settled-once terminal, wedged work holding its slot until actual exit — the "timeout includes exit" shape already installed | ≈ 1 day (L4's turn call site + the `:io` arm) | B2's −800 lines from commit 7; the capacity observer proc survives |
| **2: B2 as written** — futurize on the carried compute executor, one evaluation per agent, the completion observer as the bound. | serial per agent; bound by agent count | 0 beyond B2 | the cross-agent CPU bound: N agents × 1 evaluation each queue on a fixed pool with no refusal and no admission fact; a 3,145-uncontracted-function campaign is exactly the load that needs it |
| **3: shrink the launcher to admission only** — keep the refusing buffer and the `::completion` in-port, delete the capacity observer and the compute/io split's duplicated bookkeeping; then B2 commit 7 deletes the rest. | option 1's guarantee at roughly half the code | ≈ 2 days | nothing in guarantee; schedule risk on `flow.clj` during M4 |

Recommendation: 1 now, 3 as the cut-4 refinement when B2 opens `turn.clj`.

## 5. Acceptance proofs

One regression per behaviour class, on the canonical branch fixture with real SCI and
armed contracts, run through the installed `seon.test/run` request; never a lane suite.
Live proofs run on `default` after adoption and are recorded in the landing note with
their forms, values and timings (every operation over 1 s with its phase breakdown).

| class | regression | live proof |
|---|---|---|
| **recorded before continuing** (N1) | step / transition / output / compute-timeout / start failures each yield one occurrence with the chain before the next input is read | a scratch proc's throw on `default` → occurrence visible in `runtime_status` within one commit |
| **nothing dropped** (N1, M4) | >100 distinct concurrent failures in one graph preserve every identity; no `CountedDroppingBuffer`, `join-error-fanout!` or `offer!`-onto-a-fault-channel remains (`rg` = 0) | — |
| **panic stops only its graph, visibly** (N1) | the failing graph's completions deliver within the bound; sibling graph pings; REPL evaluates; `flow-status` reports `:failed <id>`; `bin/seon status` lists the open panic | the same on `default` with a deliberate throw in a scratch agent graph |
| **`:xform` refused** (N1) | `start-graph!` refuses naming pid and cid | — |
| **handler failure is loud** (N1, N3, N4) | a refusing writer under both modes: the proc exits, the graph stops, stderr carries both causes, the test-installed uncaught handler receives the panic receipt for the listener case | — |
| **every stop wait is bounded** (N2) | a parked proc makes `disarm-agents!` throw within the bound naming the missing event; `rg '<!!'` in `cluster.clj`/`flow.clj` finds only sites inside `await!` | `bin/seon stop` on `default` with a deliberately parked scratch proc returns within the bound with the named event; timing recorded |
| **listener failure recorded, transaction delivered** (N3) | a throwing listener: the caller's report first, one occurrence naming the key, a later listener runs, a later transaction commits | — |
| **uncaught is recorded, once** (N4) | a throwing `go` block on the fixture → one occurrence, layer `:seon.process/uncaught`; a second install refused | a throwing `go` on `default` → occurrence; with every instance stopped → stderr diagnostic |
| **router off the writer path** (L1) | the wake regression suite unchanged; the connection's listener count is 0 for routing (or 1 under option 1) and the notification thread executes no Seon query (assert with a listener that records its thread and elapsed) | `(keys @(:listeners (meta (boot/connection "default"))))` = `[]` (option 2); a `defn` transaction wakes root within one commit |
| **no lock only for a race** (L2) | two concurrent arms and one disarm through the armer complete in order without a monitor; `rg 'locking' src/seon/cluster/agent.clj` = 0 | — |
| **threads joined** (L3) | after `web/stop!` with one open tab, the feed thread is not alive and `workers` is terminated | — |
| **observable turn** (L4–L7) | ping answers during an in-flight model call; stop takes effect between messages; one fault, no overlap, no late settlement (B2 F4) | `flow/ping-proc :seon.agent/turn` during an open turn on `default` replies (today: nil after 3,009 ms, audit P2) |

The landing note for every slice states which path the evidence exercised (hot reload,
new fork, in-place adoption), the fork commit ids and gitlink advances, `wc -l` per
touched file, and the exact verification boundary. A passing regression proves the
behaviour, not adoption on `default`, browser paint, or a deletion's safety; those are
named separately.

## 6. Out-of-scope findings recorded while designing (no edits by this lane)

- The `seon-flow-architecture` skill's stale claims (schedule row 27) are a
  high-priority defect and must be corrected with N1, since N1 changes the described
  mechanism. Owner: the skill's owner; not this lane.
- Datahike `replikativ.logging/raise` throws without a cause (schedule row 24h); N3's
  handler will record such failures with a one-link chain until that fork fix lands.
- A Flow fault's evidence measures the whole environment carried in `::flow/msg`
  (201 ms, 8.4 MB; schedule row 30). N1 hands `fault!` the same map Flow builds today;
  the cost belongs to `error/prepare`'s owner and gates nothing here once M3 lands.

## PROPOSAL — one row for README §4 (the orchestrator integrates it after review)

| Step | Slice | Frees |
|---|---|---|
| 3.f | **Flow owns running machinery** ([spec](lane-flow-owns-running-machinery.md); ruled order `a2e9253fe`): MUST-NOW inside M4's window — the core.async fork gains an optional per-proc error handler called from every Flow catch on the proc's thread (`seon.fault/fault!` is the handler; `:panic` exits the proc and stops the graph through Flow control; channel `:xform` refused at `start-graph!`), the Datahike fork's `notify-listeners!` gains a per-connection failure handler (default: today's log) whose own throw reaches the process uncaught handler installed once in `seon.operator.runtime`, and every stop wait in `cluster.clj`/`flow.clj` goes through `await!` under the turn backstop bound; the counted-dropping route, fault graph, fan-out joins, `emit-core-fault!` and the six `offer!`+`println` sites leave with M4. LATER, after the first namespace agents: the router as a cluster-graph proc fed by the fork's committed-report source (owner decision A) deleting the per-agent, call-preparation and program-identity listeners; the armer owning arm/disarm (both monitors deleted); SSE join and http-kit shutdown; evaluations and capability dispatch through the launcher (owner decision B amends B2 commit 7); the backstop proc, the model call as a submission and ping during a call with B2 in cut 4 | every cluster thread under Flow or the launcher; no fault crosses a dropping channel; no Seon work on the writer's thread; stop is bounded; the turn proc observable during a model call (cut 4) |
