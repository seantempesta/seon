---
type: research
status: active
tags: [research, flow, lifecycle]
---

# Flow's control protocol, Seon's usage, and the maintenance surface

Grounding for owner Ruling 2026-07-31 #10 — *every agent has the same base and
components, and ALL system process maintenance uses core.async.flow's OWN
protocol*. This document reads the protocol exhaustively from source, audits
every current Seon call site, maps the lifecycle operations the system needs
onto flow's verbs, and records a live probe transcript.

Dependency ledger:

- `reference-code/core.async` at `dc35f3e0d7bc` (`v1.10.874-alpha3`), files
  `src/main/clojure/clojure/core/async/flow.clj`, `flow/impl.clj`,
  `flow/spi.clj`, `flow/impl/graph.clj`.
- First-party owners: `src/seon/flow.clj`, `src/seon/cluster.clj`,
  `src/seon/cluster/agent.clj`, `src/seon/oversight.clj`,
  `script/seon/fresh_operator.clj`.
- Probe: `tmp/flow_control_probe.clj`, OpenJDK 26.0.1, 18 available
  processors, `clojure -Sdeps '{:paths ["tmp"]}' -M:dev`.

## 1. The protocol, exhaustively

### 1.1 There is exactly one wire: the control channel, multed

`start` creates ONE `control-chan` of buffer 10 and wraps it in a mult
(`flow/impl.clj:99-100`). Each proc, at `start-proc`, taps that mult with its
own `(async/chan 10)` and receives it as `::flow/control` in its `:ins`
(`flow/impl.clj:153-159`). So **every command is broadcast to every proc**;
addressing is the receiving proc filtering on `::flow/to`
(`flow/impl.clj:202-207`, contract at `flow/spi.clj:36-42`).

Every control message is a map with `::flow/command`, `::flow/to` (a pid or
`::flow/all`), and — for ping only — `::flow/reply-chan`
(`flow/impl.clj:71-75,80`). `send-command` is a plain `>!!` onto the control
channel (`flow/impl.clj:73-74`).

The SPI **requires** that a proc, whenever reading or writing any channel,
uses `alts!!` including the control channel **with priority**
(`flow/spi.clj:32-34`). The stock proc honours this at both its read
(`flow/impl.clj:295`) and its write (`flow/impl.clj:232-234`).

### 1.2 The verb table

| verb | mechanics (source) | guarantees | file:line |
|---|---|---|---|
| `create-flow` | pure construction; describes each proc, validates that `:ins`/`:outs` ids do not collide, builds the conn map. **No channels, no threads.** | synchronous, total; throws on invalid conn or shared io-id | `flow.clj:76-106`; `impl.clj:38-48,50-69` |
| `start` | locks; creates control chan + mult, report chan `(sliding-buffer 100)`, error chan `(sliding-buffer 100)`, all in/out chans, mults for fan-out outs, castee chans `(sliding-buffer 100)`; launches every proc. Returns `{:report-chan :error-chan}`. Re-`start` of a running graph returns the same chans plus `:already-running true`. **Procs start `:paused`.** | synchronous, acknowledged (returns after every proc launched); idempotent-ish via `:already-running`; a proc ctor throw sends `::flow/stop` to all and rethrows | `flow.clj:108-121`; `impl.clj:94-173,271` |
| `resume` | `>!!` `{::flow/command ::flow/resume ::flow/to ::flow/all}` | **asynchronous and unacknowledged.** Blocks only if the 10-slot control buffer is full. Each proc calls its `transition` arity with `::flow/resume` when its status actually changes | `flow.clj:132-134`; `impl.clj:185,201,209-217` |
| `pause` | `>!!` `::flow/pause` to `::flow/all` | asynchronous, unacknowledged. Observed only at a proc's next `alts!!` — see §1.4 for what it does *not* stop | `flow.clj:128-130`; `impl.clj:184` |
| `pause-proc` / `resume-proc` | identical, with `::flow/to` = pid | same; the message still reaches every proc, each ignores it unless addressed | `flow.clj:144-150`; `impl.clj:187-188,203` |
| `stop` | `>!!` `::flow/stop` to `::flow/all`, then **closes the error and report channels** and `reset!`s the chan registry to nil. Returns `true` (or nil if not running) | asynchronous w.r.t. the procs — **`stop` is NOT a join.** It returns before any proc has exited. The graph can be started again, which builds all-new channels | `flow.clj:123-126`; `impl.clj:174-183` |
| `ping` | creates a fresh `reply-chan` of size `(count procs)`, wraps it in `(async/take n)`, sends `::flow/ping` with that reply-chan, then `alts!!` over `[ret-chan timeout]` accumulating `pid -> reply` until a nil | **synchronous, timeout-bounded, partial.** Returns a map of pid → reply for procs that answered within `timeout-ms` (default 1000). Non-answering procs are simply absent | `flow.clj:136-142`; `impl.clj:76-86` |
| `ping-proc` | same machinery with `::flow/to` = pid; returns `(-> ret vals first)` | synchronous; **nil** for an unknown pid or a busy proc — and costs the FULL timeout in that case (§4) | `flow.clj:152-155`; `impl.clj:86,189` |
| `inject` | resolves `[pid io-id]` to a channel through the `Resolver`, then `futurize`s a `doseq >!!` on the `:io` executor. Returns a `java.util.concurrent.Future`. `[::flow/cast sig-id]` broadcasts to every proc whose `:signal-select` accepts the id | **asynchronous, backpressured.** The future completes only when every message has been *put*; on a full buffer of a paused proc it never completes until that proc drains. Throws immediately if the flow is not running, or if the coord names no channel | `flow.clj:157-163`; `impl.clj:190-197,142-144,131-135` |
| `command-proc` | **declared in the `Graph` protocol but NOT implemented by `create-flow`'s reify.** Not exposed in the public `flow` ns at all | calling it throws `AbstractMethodError` (probe §4). Treat as nonexistent | declared `impl/graph.clj:24-25`; absent from `impl.clj:87-197` |

### 1.3 The exact ping reply shape

`pong` (`impl.clj:272-279`) writes, per proc:

```clojure
#:clojure.core.async.flow{:pid    :sink
                          :status :paused          ; :paused | :running
                          :count  3                ; transform passes so far
                          :ins    {:in <datafied chan>}   ; minus ::control/::casts
                          :outs   {}                      ; minus ::error/::report
                          :state  {:got 3 :last {...}}}   ; (ping-map-fn state)
```

Everything but `::flow/state` is `walk/postwalk`ed through flow's `datafy`
(`impl.clj:22-27,275`), so a channel becomes
`{:put-count :take-count :closed? :buffer {:type :count :capacity}}` — which is
exactly what `seon.oversight/occupancy` reads. `::flow/state` is
`(ping-map-fn state)`, defaulting to `identity`
(`flow.clj:191-193`; `impl.clj:246,279`) — so **an unguarded proc's ping
returns its entire state, including closures and connections**; every Seon proc
correctly narrows it with a `:ping-map-fn`.

**A ping reports live, process-local, replaceable facts only**: which pid, its
two-valued status, a pass counter, its channels' occupancy, and whatever the
proc chose to project. Nothing durable, nothing that survives the process.
That is precisely the "on-demand probe, never stored" shape the ruling calls
for.

### 1.4 What pause does and does not stop — corrections

**LOUD CORRECTION 1 — ping replies do NOT go to the report channel.**
`flow/start`'s docstring says "'ping' responses will show up here"
(`flow.clj:112-114`) and `impl/graph.clj:19,23` says pings "will put their
status and state on the report channel". **Both are wrong for this revision.**
The implementation creates a private per-call `reply-chan`
(`impl.clj:77,80`) and the proc pongs to `(::flow/reply-chan cmd)`
(`impl.clj:205`). Probe confirms the report channel stays empty across a ping.
Any design that plans to *observe* pings by tapping the report channel is
built on a stale docstring.

**LOUD CORRECTION 2 — pause does not interrupt an in-flight transform, and it
does not even stop that transform's outputs from being delivered.** Status is
only re-read at `alts!!` points. A running transform is a plain function call
(`impl.clj:302-305`); flow has no way to interrupt it. Worse, in `send-outputs`
the pause command is handled and then the loop **recurs on the same messages**
— only `:exit` breaks out (`impl.clj:229-239`). So:

- pause ⇒ no *new* input is read, but the current transform completes fully
  and its entire output is still written downstream;
- stop ⇒ the current transform completes, and its *remaining* outputs are
  **abandoned** at the next message boundary (`impl.clj:221,230`).

This is the mechanical justification for the agent graph's two-proc split
(`src/seon/cluster/agent.clj:20-27`): the mailbox answers ping/pause in
microseconds while the turn's multi-second provider call is uninterruptible.
The docstring's claim there is accurate.

**Channel contents.** Pause leaves every channel intact and buffered —
injected messages simply accumulate (probe: 2 messages sat in `:src :in` while
paused, delivered on resume). Stop closes only `::flow/error` and
`::flow/report` (`impl.clj:179-180`); the in/out channels are **not** closed
and are simply dropped, because a subsequent `start` builds all-new ones
(`impl.clj:99-124`). Everything in flight at stop is therefore lost — which is
exactly the transport law's precondition, and why Seon's stop paths join a
proc-published completion instead of trusting `stop`.

**Transitions.** `transition` is called only when status actually changes
(`impl.clj:209-217`). Notably there is **no** `::flow/pause` transition at
start even though procs begin `:paused`, and the `::flow/stop` transition is
called on the proc's own thread just before it falls out of its loop
(`impl.clj:321-322`) — this is the hook Seon uses for orderly-stop completion.

**Errors do not stop a proc.** Any throw from a transform is caught, written to
`::flow/error` with `{:pid :status :state :count :cid :msg :op :ex}`, and the
loop continues with the *pre-transform* state (`impl.clj:312-316`).

**A closed input is removed from the read set** — but only after the transform
has been called once with a `nil` msg (`impl.clj:309-311`). Seon's
fault-committer correctly treats that nil as lifecycle, not a fault
(`src/seon/flow.clj:576-580`).

**`:compute` procs.** The proc loop itself runs on `:io`; each transform is
submitted to the compute executor and `.get`-ed with `compute-timeout-ms`
(default 5000), a timeout being reported on `::flow/error`
(`impl.clj:258-262`; `flow.clj:264-286`).

## 2. Current usage audit

### 2.1 Every call site in fresh `src/`

| site | verbs | notes |
|---|---|---|
| `src/seon/cluster.clj:1055-1057` | `create-flow`, `start`, `resume` | the one cluster graph; armer + render plumbing |
| `src/seon/cluster.clj:1170-1178` | `stop` + `<!!` completion joins | orderly stop; the docstring at `1148-1149` already cites `impl.clj:174-183` for "stop is not a join" — accurate |
| `src/seon/cluster.clj:1523` | (`stop-installed-work-launcher!`) | last instance out stops the process-root launcher |
| `src/seon/cluster/agent.clj:387-401` | `create-flow`, `start`, `resume` | one graph per agent, armed then left **running forever** |
| `src/seon/cluster/agent.clj:431` | `stop` + completion join | `disarm!` |
| `src/seon/flow.clj:418-427,439` | `create-flow`, `start`, `resume`, `stop` | work launcher |
| `src/seon/flow.clj:492` | `inject` | `submit!!`'s bounded compute submission — the one inject in the system, and it `.get`s the future for backpressure (`flow.clj:500`) |
| `src/seon/flow.clj:660-668,712` | `create-flow`, `start`, `resume`, `stop` | fault graph |
| `src/seon/oversight.clj:90,125` | `ping` (20 ms) | the ONLY read verb in use; per-agent and per-cluster-graph |
| `src/seon/flow.clj:604-626` | passthrough `Graph` reify | monitor view |

**`pause`, `resume-proc`, `pause-proc`, and `ping-proc` have ZERO first-party
call sites.** Half the control protocol is currently unused.

### 2.2 How an agent parks between episodes today

**Not with `pause`.** `arm!` does `start` → `resume`
(`src/seon/cluster/agent.clj:391-401`) and the graph stays `:running` for its
whole life. "Parked" means each proc's `alts!!` is blocked on its input
channels (`impl.clj:295`) — an ordinary channel read, on a virtual thread.
Waking is a datom-routed `offer!` onto the sliding-1 mailbox channel
(`src/seon/cluster/wake.cljc:146-228`).

This matters for the measured numbers. `flow-mechanics-2026-07-28.md`'s "two
procs, ~8.5 KB per parked proc" was measured on procs parked **on a channel
read while `:running`**, which is the state Seon actually keeps agents in. A
flow-`paused` proc parks on `(<!! control)` instead (`impl.clj:284`) — one
blocked virtual thread either way, same order of footprint, but with a
different reachability set (the paused proc holds only control; the running
proc holds its full read set). **The 8.5 KB figure applies to the current
running-and-parked shape, and must not be cited as a "paused agent" cost until
someone measures a genuinely paused graph.** No behavioral difference in
footprint is expected; the difference is semantic, and the semantic difference
is the whole point of the ruling: today "parked" is indistinguishable from
"actively awaiting work", so there is no way to express *suspended*.

### 2.3 How `bin/seon stop` / `down` reaches graphs

`script/seon/fresh_operator.clj:1556-1563` builds a form that prepl-evals
`(seon.cluster/stop! instance)` in the target JVM; `stop!`
(`src/seon/cluster.clj:1500+`) calls `disarm-agents!`
(`src/seon/cluster.clj:1160-1186`) which drives the flow `stop` verbs in a
fixed order, each joined at a proc-published completion. If the prepl path
fails, the operator falls back to `SIGTERM`
(`fresh_operator.clj:1610-1611`) and then reaps an emptied JVM
(`1613-1621`). So the operator reaches graphs **through flow's own `stop`**,
with process signals only as the crash path. This is already correct.

### 2.4 Retirement candidates and defects

1. **`work-launcher-proc` re-implements the control protocol by hand** —
   `src/seon/flow.clj:285-360`. It is a bespoke `ProcLauncher` whose loop
   re-derives `handle-command` (`impl.clj:199-207`) inline at lines 331-357.
   Two concrete defects fall out:
   - **`(async/alts!! channels)` at `src/seon/flow.clj:319` omits
     `:priority true`.** The SPI *requires* control priority
     (`flow/spi.clj:32-34`) and the stock proc supplies it
     (`impl.clj:295`). Under sustained submission pressure a `stop`/`pause`
     can be starved behind work. **File an issue.**
   - It never invokes a `transition`, so it has no orderly-stop completion and
     no resource-cleanup hook; `::flow/stop` just returns nil
     (`src/seon/flow.clj:339-340`), abandoning in-flight compute accounting.
   Whether this launcher can become an ordinary `var-process` is a real design
   question (it needs a three-way alts including its own completion channel,
   with a conditional read set). Flow already supports exactly that:
   `::flow/in-ports` for the completion channel plus `::flow/input-filter` to
   drop `::compute-submission` from the read set when at capacity
   (`flow.clj:219-232`). **Strong retirement candidate: the bespoke launcher
   should become a `var-process` with an input-filter.**
2. **`monitor-graph`'s `command-proc` passthrough is dead and throws** —
   `src/seon/flow.clj:623-624` delegates to `flow.graph/command-proc`, which
   `create-flow`'s reify does not implement (probe: `AbstractMethodError`).
   Delete the arity or make it refuse with an error value.
3. **`seon.oversight`'s 20 ms ping is serial and per-agent** —
   `src/seon/oversight.clj:39,90,125`. A ping costs the full timeout whenever
   *any* addressed proc fails to answer (§4). Every mid-turn agent therefore
   adds 20 ms to a root-page render, serially: 10 busy agents = 200 ms+ per
   render pass. Not a defect today, but a measured scaling edge that belongs
   in the render-cost ledger.
4. Not a retirement candidate, but worth naming: the **proc-published
   completion promise-chan** (`src/seon/cluster/agent.clj:202-206`,
   `src/seon/flow.clj:564-570`, `src/seon/render/web.clj:578`) is Seon's own
   join layered on flow's `::flow/stop` transition. It is not a second
   mechanism — it uses flow's protocol and supplies the one guarantee flow
   deliberately omits. Keep it, and keep it uniform: every proc that holds a
   database connection must publish one.

## 3. The maintenance surface, mapped

Given "every agent is a graph, nothing special", each lifecycle operation maps
to flow's own verbs. Nothing below invents a noun.

| operation | flow mechanics | owner fn | database |
|---|---|---|---|
| create agent | a `:seon.cluster.agent/id` datom | `seon.cluster.agent/arm!` (via `::armer` proc) | **fact**: the agent's identity attributes |
| arm its graph | `create-flow` → `start` → `resume` | `arm!` (`agent.clj:349-410`) | none — the graph is derived from (agent-id, handle) |
| park between episodes | *no verb*: the procs stay `:running`, blocked on their read set | — | none |
| wake | a routed datom → `offer!` on the sliding-1 mailbox | `wake/route!` | the message/agent datom IS the wake |
| **pause (operator or root intervention)** | `flow/pause graph` — mailbox stops reading instantly, in-flight turn runs to its durable terminal | **new**: `seon.cluster.agent/pause!` | a **fact** — an operator/root intervention is a durable decision with provenance, and a re-armed graph after a crash must come back paused. Store the *decision*, never the status |
| resume | `flow/resume graph` | **new**: `seon.cluster.agent/resume!` | retract/supersede the pause decision fact |
| inspect ("is it busy?") | `flow/ping graph :timeout-ms N` | `seon.oversight/unit` (already) | **NEVER stored.** A ping is an on-demand probe of replaceable process-local state |
| stop / kill one agent | `flow/stop` + join the turn proc's completion | `agent/disarm!` (`agent.clj:431`) | none; run receipts already own durability |
| cluster shutdown ordering | listener → armer → each agent graph → fan-out, each `stop`ped and joined | `cluster/disarm-agents!` (`cluster.clj:1160-1186`) | none |
| process-death recovery | nothing re-executes: re-derive graphs, re-arm, prime one wake each; dangling receipts marked `:interrupted` | `cluster/recover-runs!` + arm | receipts are facts; **the pause decision fact is what makes a paused agent come back paused** |

Where a verb invocation lives: in the **runtime owner namespace for that
graph** — `seon.cluster.agent` for agent graphs, `seon.cluster` for the cluster
graph, `seon.flow` for the work launcher — as an ordinary function taking one
namespaced request map. Each is therefore callable from the REPL, from the
operator's prepl form, and from the capability request handler. There is no lifecycle
service, registry, or manager namespace, and nothing outside those owners
holds a graph.

The derived/stored line, restating the ruling's oversight-retraction: **a pause
DECISION is a fact; a pause STATUS is a ping.** `flow/ping` already reports
`::flow/status`, so storing it would be stored-derived state that goes stale
the instant an operator calls `pause` from a REPL. The rendered "paused" a user
sees must come from the ping (as `seon.oversight/agent-story-text` already does
for parked/mid-turn), while "why is it paused and who did it" comes from the
fact.

### The `my.*` request shape (shape only, no implementation)

An agent pausing itself is **not** a capability request — that is already the
settled `n/wait` value the driver interprets
(`src/seon/sci/eval.clj`, `src/seon/cluster/loop.cljc`), and it must stay
there: in-eval runtime mutation is exactly the old-engine residue the standing
goal rejects.

An agent pausing or resuming a **child agent it created** is a genuine
capability request through the one bounded evaluation, because it mutates another
sovereign graph's lifecycle:

```clojure
;; agent-facing call
(my.agents/pause  {:my.agents/id "researcher-3"
                   :my.agents/reason "waiting on the index rebuild"})
(my.agents/resume {:my.agents/id "researcher-3"})
(my.agents/status {:my.agents/id "researcher-3"})
```

- `pause`/`resume` enter `seon.effect` as ordinary requests carrying the one
  request identity; the driver commits the pause-decision fact with provenance
  and calls `agent/pause!`, which calls `flow/pause`. Authorization is a query
  over the creation ref, not a flag.
- `status` is read-only and returns the ping projection — a flat value with
  `:my.agents/status` (`:paused`/`:running`/`:mid-turn`) and mailbox occupancy,
  and the flat `:seon.error` value when the id names no armed graph. Because it
  is a probe, calling it twice may legitimately differ; nothing is stored.
- All three return flat values. Nothing throws into the loop.

## 4. Live probe transcript

`tmp/flow_control_probe.clj`, two `:io` procs (`:src` → `:sink`), OpenJDK
26.0.1, 18 processors, core.async `v1.10.874-alpha3`. Verbatim:

```
START returned keys: (:report-chan :error-chan) in ms 4.047667

-- ping AFTER start, BEFORE resume (procs start :paused) --
ping ms 2.176292
  :sink => #:clojure.core.async.flow{:pid :sink, :status :paused, :count 0, :state {:got 0, :last nil}}
  :src  => #:clojure.core.async.flow{:pid :src,  :status :paused, :count 0, :state {:seen 0}}
  ins keys of :src => (:in)
  raw ins val :src :in => {:put-count 0, :take-count 0, :closed? false,
                           :buffer {:type FixedBuffer, :count 0, :capacity 10}}
  report-chan empty? poll => nil          ; <= ping did NOT touch the report chan

-- inject while PAUSED --
inject returned java.util.concurrent.FutureTask done? false
after 50ms done? true ms 52.757917
ping-proc :src => {:pid :src, :status :paused, :count 0, :state {:seen 0}}
  :src :in buffer => {:type FixedBuffer, :count 2, :capacity 10}   ; buffered, not lost

-- resume --
resume call ms 0.035708
  [src transition] ::flow/resume
  [sink transition] ::flow/resume
report drain: ({:sink-saw {:n 1}} {:sink-saw {:n 2}} nil nil)
  :sink status :running count 2 state {:got 2, :last {:n 2}}
  :src  status :running count 2 state {:seen 2}

-- pause while a transform is IN FLIGHT (sink sleeps 400ms) --
pause call returned ms 0.119833     ; src transitions immediately, sink cannot
ping DURING in-flight transform, ms 110.428791 replied pids: (:src)
  sink reply? nil                   ; <= full 100ms timeout burned, sink absent
  [sink transition] ::flow/pause    ; only after its transform returned
after transform finished: {:sink [:paused 3 {:got 3, :last {:n 3, :slow 400}}],
                           :src  [:paused 3 {:seen 3}]}
report drain: ({:sink-saw {:n 3, :slow 400}} nil nil)   ; pause did NOT suppress its output

-- inject into a PAUSED proc, more than buffer (10) --
inject 15 into paused sink: done? false ms 107.872541
  sink :in buffer => {:type FixedBuffer, :count 10, :capacity 10}  ; injector parked
  resume-proc :sink
  [sink transition] ::flow/resume
  inject future done now? true
  sink after resume: {:pid :sink, :status :running, :count 18, :state {:got 18, :last {:n 114}}}

-- ping timing, 100 samples on a running graph --
ping ms p50 0.273 p95 0.711625 max 1.3735

-- ping a proc that does not exist --
ping-proc :nope => nil ms 210.380084     ; 200ms timeout, no error

-- stop --
stop returned true in ms 0.237708
  [src transition] ::flow/stop   <= AFTER stop returned
report-chan closed? poll => {:sink-saw {:n 100}}   ; closed, buffered values still drain
  [sink transition] ::flow/stop
ping after stop => THREW flow not running

-- restart the same graph --
restart ms 0.386042 keys (:report-chan :error-chan)
counts after restart: {:src 0, :sink 0}     ; fresh channels, fresh proc state
double start => {:already-running true}

-- command-proc --
java.lang.AbstractMethodError: Receiver class clojure.core.async.flow.impl$create_flow$reify__9812
  does not define or inherit an implementation of the resolved method 'command_proc'
```

Numbers worth carrying:

- `ping` of a fully responsive 2-proc graph: **p50 0.273 ms, p95 0.712 ms, max
  1.37 ms** over 100 samples. Cheap enough to call per render.
- `ping` with **any** unresponsive proc costs the **entire** `timeout-ms`
  (110 ms observed at a 100 ms budget; 210 ms at 200 ms). `ret-chan` is
  `(async/take n reply-chan)`, so it closes early only when all `n` reply
  (`impl.clj:78,81-85`). This is the cost model behind `oversight.clj`'s 20 ms.
- `pause` 0.120 ms, `resume` 0.036 ms, `stop` 0.238 ms — all just a channel
  put. **None of them is a completion.**
- `start` 4.05 ms cold, 0.386 ms on restart of the same graph value.
- A missing pid returns `nil`, never an error. Callers must distinguish
  "no such proc" from "busy proc" themselves — they cannot, from the reply
  alone. Seon's `oversight` already handles this by pairing the ping with the
  graph's datafied `:procs` (`oversight.clj:126`), which is the right idiom;
  any new caller must copy it.

## 5. Skill drift

`.claude/skills/seon-flow-architecture/SKILL.md`:

- **It does not teach the control verbs at all.** `pause`, `resume`,
  `pause-proc`, `resume-proc`, `inject`, and the `::flow/control` channel are
  absent. `ping` appears only inside a parenthetical about a timing
  measurement. An agent that loads this skill to "design a runtime mechanism"
  learns procs, workloads and buffers, and would plausibly hand-roll a
  lifecycle control path because it does not know one ships. **This is the
  highest-value gap given Ruling #10** — add a control-protocol section keyed
  to §1.2 above.
- The one control claim it *does* make is correct: "`stop` returning was not an
  exit-join proof" matches `impl.clj:174-183`.
- Its "parked between episodes (two `:io` procs)" wording is ambiguous in
  exactly the way §2.2 describes — it should say parked **on a channel read
  while running**, and note that no agent is ever flow-`paused` today.
- `references/agent-graphs.md` should be checked for the same ambiguity.

No other verified drift found in the sections this audit touched: the tower,
executor ownership (`cluster.clj:156-179`, `flow.clj:381-425`), the `:mixed`
refusal (`flow.clj:106-111`), and the buffer table all match source.

## 6. Issues to file

1. `seon.flow/work-launcher-proc` omits `:priority true` on its control
   `alts!!` (`src/seon/flow.clj:319`), violating `flow/spi.clj:32-34`.
2. `seon.flow/monitor-graph`'s `command-proc` arity delegates to an
   unimplemented protocol method and throws `AbstractMethodError`
   (`src/seon/flow.clj:623-624`).
3. Retirement candidate: rebuild the work launcher as a `var-process` using
   `::flow/in-ports` + `::flow/input-filter` instead of a hand-written
   `ProcLauncher`.
4. Upstream-doc defect (informational, not ours to fix): `flow.clj:112-114` and
   `impl/graph.clj:19,23` both claim ping replies land on the report channel.
   They do not.

## Independent skill verification

Adversarial pass over the `seon-flow-architecture` skill's control-protocol
and state-discipline sections (commits `d5bfde658`, `27107785d`), trusting
neither the authoring lane nor this document. Every `file:line` anchor was
reopened at that line in `reference-code/core.async` (`v1.10.874-alpha3`,
`dc35f3e`) and in `src/`; every behavioral claim was re-probed from scratch
with independently written probes — `tmp/flow_skill_verify_probe.clj` and
`tmp/flow_skill_verify_probe2.clj`, written before reading
`tmp/flow_control_probe.clj` — on `clojure -M:dev`, OpenJDK 26.

Confirmed by probe: only `start` (3.6 ms) and `ping` (1.9 ms) are
acknowledged, while `resume` (0.55 ms), `pause` (0.027 ms) and `stop`
(0.015 ms) return before any proc observes them; `:already-running true` on
re-`start`; ping replies arrive on the private per-call reply chan and the
report channel polls `nil` twice across a ping; procs start `:paused` and
`start` + `inject` alone delivered nothing; no `::flow/pause` transition at
start, first transition is `::flow/resume`, and three consecutive `pause`
calls produced exactly one `::flow/pause` transition per proc; the ping map's
`:ins`/`:outs` datafy to `{:put-count :take-count :closed? :buffer}` and an
undeclared `:ping-map-fn` returned the proc's whole init state; ping costs the
full timeout when any addressed proc is busy (110 ms at 100, 310 ms at 300)
while an answering `ping-proc` returns in 0.5 ms; unknown pid → `nil`;
`inject`'s future stayed pending on a full paused proc and completed on
resume; `command-proc` throws `AbstractMethodError` and is absent from the
`flow` namespace.

The mid-flight pause claim needed a second, better probe. Pausing the whole
graph is not a valid test — the downstream proc is paused too, so nothing is
consumed and delivery is unobservable. Re-run with `pause-proc` on the
producer only, an 8-message output and a gated sink holding the buffer full:
the transform ran to completion, the `::flow/pause` transition fired *during*
`send-outputs`, and all 8 messages were still written downstream, while no new
input was read. The skill's claim is exactly right; the naive probe is not.

Four claims falsified and fixed in `SKILL.md`:

1. **`init` does not run on the proc's own thread.** `(step args)`
   (`flow/impl.clj:263`) is inside `spi/start`, which graph `start` calls
   inline per proc (`:166-167`); `run` is submitted to the executor only at
   `:323`. Probed: init recorded thread `main`, the `flow/start` caller.
   Consequence now taught — a blocking `init` serializes into `flow/start` and
   never gets the proc's `:io`/`:compute` context.
2. **"`pause`, `pause-proc`, `resume-proc` and `ping-proc` have zero
   first-party call sites" is false.** All four are called throughout `test/`
   (`flow_test.clj`, `agent_test.clj`, `armed_test.clj`), and `ping-proc` is a
   primary test probe. Corrected to: no production call site in `src/` beyond
   `monitor-graph`'s passthroughs.
3. **"No Seon agent is ever flow-`paused` today" is overstated.**
   `test/seon/cluster/agent_test.clj:445-470` pauses a live agent graph during
   an in-flight provider call and asserts these exact semantics. Reworded to
   "nothing in the running system", with the test cited as the proof.
4. **The maintenance surface's "Ruled shape" mis-attributed a lane proposal to
   the owner.** Ruling #10 settles only that the verbs are flow's own and that
   there is no bespoke machinery; the durable pause-decision-fact design is
   this document's proposal (lines 252-257). Marked as proposed, not ruled.

Everything else held: the full verb table, both stale-docstring corrections,
the scar and state-discipline wording against the corrected ledger
(`plan/README.md:1450-1466`, owner correction at `:1457-1461`), the four
step-fn arity anchors, the parked-cost correction in
`references/agent-graphs.md`, the `[TARGET]` markings, and both issue pointers
(`src/seon/flow.clj:319` still lacks `:priority true`; `:623-624` still
delegates to the unimplemented method). No contradiction was found between the
new sections and the older parked/ping wording elsewhere in the skill.
