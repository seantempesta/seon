---
name: seon-flow-architecture
description: "How Seon is architected on core.async.flow — procs, workloads, buffers, agent graphs, the boot tower, and how flow composes with the database and SCI. Load this whenever you are adding or changing a proc, graph, channel, or buffer; deciding :io vs :compute; wiring wakes, faults, or streaming; reasoning about what survives a crash; or wondering where a new runtime mechanism belongs. Also load it before designing ANY new runtime machinery in Seon, even if flow is not mentioned — the answer is usually a proc in an existing graph, and this skill says which one and why."
---

# Seon flow architecture

Seon's runtime is `core.async.flow` graphs in one JVM process; one JVM may host
several sovereign clusters (`src/seon/cluster.clj:1388-1405`). There is no
central loop, no dispatcher, no scheduler entity — that shape is banned by
owner ruling ("a JavaScript event loop inside Clojure"). If you are about to
write one, stop and read *The banned shapes* below.

This skill is the map. Sections marked **[TARGET]** describe designs that
are ruled and evidenced but **not built yet** — never write code that
assumes them without checking the tree first.

## Read the source, not your memory

Every source claim here points at its current owner; measured claims point at
their owning research note. This program has repeatedly been misled by
day-old prose, so verify with one live command (`rg`, `bin/test`) before
acting. The dependency's own source is vendored under
`reference-code/` precisely so you can check semantics rather than guess:

| question | read |
|---|---|
| what a proc/channel/buffer really does | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj` + `flow/impl.clj` + `flow/spi.clj` |
| what `:io`/`:compute`/`:mixed` actually construct | `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:71-96` |
| how the graph monitors itself | `reference-code/core.async.flow-monitor/` |
| transactions, listeners, query planning | `reference-code/datahike/` (our fork — internal calls are sanctioned) |
| the interrupt hook, contexts, forks | `reference-code/sci/doc/interrupt.md`, `reference-code/sci/src/sci/` |
| SSE writes and socket backpressure | `reference-code/http-kit/` (our fork — see *Streaming*) |

Deeper research, all with evidence, lives in
`docs/prds/sci-execution-runtime/research/`: `flow-mechanics-2026-07-28.md`,
`flow-inventory-2026-07-28.md`, `workload-classification-2026-07-28.md`,
`workload-scheduling-truth-2026-07-29.md`,
`agent-flow-render-falsification-2026-07-29.md`,
`render-pipeline-design-2026-07-29.md`,
`flow-control-protocol-2026-07-31.md`. Cite them; don't re-derive them.

Read the progressive-disclosure references when the map is not enough:

- `references/workloads-and-scheduling.md` — executor ownership, parking,
  admission, and the measured scheduling probes.
- `references/agent-graphs.md` — the current two-proc blueprint, arming,
  custody, episode caps, and the proposed `::renders` proc.
- `references/wakes-and-faults.md` — listener constraints, selective interest,
  the old E/A/V design worth reusing, and fault fan-out.
- `references/render-delivery.md` — the current snapshot/delta renderer,
  target packages/keyframes, buffer laws, http-kit drain state, and frame
  budgets.
- `references/degraded-start.md` — tower-layer diagnosis, advertisements,
  scratch-JVM cleanup, stale code, and the in-memory fallback boundary.
- `references/decisions.md` — the rulings, rationale, and rejected
  predecessors.

## The tower — where a new mechanism belongs

Boot is layered, each layer reading only the one below and publishing its
own readiness (`src/seon/cluster.clj`):

1. **Process** — REPL opens first. JVM process identity is exactly
   `(pid, start-instant)` (`src/seon/cluster/process.clj:2-28`); the cluster
   advertisement adds cluster name and the bound REPL endpoint to that
   identity (`src/seon/cluster.clj:1422-1452`).
2. **Store** — one process-root Datahike store under a lifetime lock; sibling
   clusters use distinct branches and reuse the held root store
   (`src/seon/cluster.clj:1295-1311`).
3. **Facts** — after opening the branch, boot refuses an incoherent program,
   accretes schema, settles interrupted work, applies config, and ensures the
   cluster and root-agent facts (`src/seon/cluster.clj:1312-1342`).
4. **Context** — boot creates the cluster's one live SCI ctx after recovery
   and config but before any agent graph. `cluster-ctx` acquires the program
   graph and restores the durable session image into that ctx
   (`src/seon/cluster.clj:1343-1348`;
   `src/seon/sci/eval.clj:1142-1228`).
5. **Flow** — the work launcher is installed, then the cluster graph and one
   graph per agent are armed, and only then is the web UI served
   (`src/seon/cluster.clj:1357-1386`).

The complete current order is advertisement → store → source commit/fork →
connection → coherent-program validation → schema accretion → recovery →
config and root facts → live ctx plus session-image restoration → work launcher
→ agent arm → web serve (`src/seon/cluster.clj:1289-1386`). Before adding a
mechanism, ask which layer owns it. Most "new machinery" is a proc in an
existing graph or a derivation over facts — not a new subsystem.

The executor owners are deliberately distinct:

- Core.async constructs and memoizes the default `:io` executor: virtual
  thread per task when supported, cached-platform fallback. Seon's root pair
  holds that dependency-owned object (`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:82-105`;
  `src/seon/cluster.clj:158-182`). The work-launcher definition supplies no
  `:io-exec`, so its proc loop and ordinary cluster/agent/fault graphs all
  resolve this default (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:145-148`;
  `src/seon/flow.clj:381-423,626-666`;
  `src/seon/cluster.clj:1079-1096`;
  `src/seon/cluster/agent.clj:337-390`).
- Seon constructs one fixed platform `:compute` executor at available-processor
  parallelism and supplies it only as the work-launcher graph's
  `:compute-exec` (`src/seon/cluster.clj:158-182`;
  `src/seon/flow.clj:381-423`).
- The work launcher separately constructs one virtual-thread-per-task executor
  and submits each admitted evaluation task to it
  (`src/seon/flow.clj:135-137,199-229,401-430`;
  `src/seon/cluster/loop.cljc:509-525`).
- Core.async's unoverridden `:compute` and `:mixed` defaults are cached platform
  pools, but Seon's ordinary `var-process` refuses missing or `:mixed`
  workloads (`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:91-105`;
  `src/seon/flow.clj:83-115`).

## Degraded start and stale JVMs

When a scratch cluster fails partway up the tower, read
`references/degraded-start.md` before retrying. The short version:

- inspect the carried `:seon.boot/instance`; absent keys identify the last
  published layer (`src/seon/cluster.clj:1289-1386,1458-1485`);
- inspect the advertisement and prove both the named cluster and any detached
  operator JVM are gone with `bin/seon status` (root-scoped; use
  `--root PATH` for an isolated operator root);
- remember that `bin/seon start <name>` adds to an already-running JVM when
  this operator root has a reachable anchor, so its Var roots may predate the
  source edit (`script/seon/fresh_operator.clj:1593-1654`). Use
  `bin/seon --root PATH ...` for a lane-owned operator root: process records,
  advertisements, logs, store observations, and anchor selection are scoped to
  that canonical root (`bin/seon:4-18`;
  `script/seon/fresh_operator.clj:789-866,1041-1113,1593-1627`); and
- if boot is blocked by shared-tree churn but the subject is a pure
  transformation, fall back to a separate `clojure -M:dev` JVM with immutable
  in-memory inputs and make no live-tower claim.

## Building a proc

Use `seon.flow/var-process` (`src/seon/flow.clj:83-115`). Two things matter:

**Reference the step-fn as a var (`#'f`), never a value.** This is what
makes live update work: re-evaluating a `defn` against the running system
changes proc behavior immediately, zero restart. Topology changes (procs,
conns, buffers) instead rebuild the graph — stop → `create-flow` → start. A
three-proc create/start/resume/ping-ready/stop API round trip measured 0.343 ms
median after five warm-ups over 50 samples on OpenJDK 26.0.1 with 18 available
processors. Ping proved all three procs responsive; `stop` returning was not
an exit-join proof. Rebuild is safe only when every channel's contents are
losable by construction
(`docs/prds/sci-execution-runtime/research/flow-dynamic-update-2026-07-27.md`).

**Declare the workload explicitly.** `var-process` **refuses a missing or
`:mixed` workload at construction** (`src/seon/flow.clj:91-100`) because the
default `:mixed` execution path occupies one cached platform thread for the
proc's blocking loop and inline transform. This is a scaling cliff; call it
thread occupation, not Loom carrier pinning. Refusing at construction makes
the mistake unrepresentable rather than discovered under load.

```clojure
(seon.flow/var-process #'turn-step :io
                       {:seon.cluster.loop/cluster handle})
```

## The control protocol — flow already ships lifecycle

**Do not hand-roll a lifecycle path.** Ping, start, stop, pause, resume and
their per-proc variants are flow's own verbs, and because every agent IS a
graph, agent lifecycle is those verbs applied to that agent's graph
(Ruling 2026-07-31 #10, `docs/prds/sci-execution-runtime/plan/README.md:1439-1449`).

**The scar behind that ruling.** The first agent-flow attempt put every agent
into ONE graph, forcing agents to run serially; the aha — **all longer-term
STATEFUL processes are flows, each its own graph, controlled under the one
system** — is the origin of the 2026-07-28 agents-are-flows ruling (ADDENDUM,
`docs/prds/sci-execution-runtime/plan/README.md:1450-1466`). The standing
practice from the same addendum: **flow work always gets research plus a live
probe before implementation — never write a flow mechanism from remembered
semantics.** The two stale docstrings corrected below are the recurring proof.

### State discipline — the database, then flow's own hooks

Owner correction in the same addendum
(`plan/README.md:1457-1461`): **the state itself is stored IN THE DATABASE.**
Where something like an atom is genuinely required, it is initialized during
the proc's **init** phase and properly unwound in the **shutdown transition** —
flow's own lifecycle hooks, never ad-hoc setup or teardown outside them.

A step-fn is one function of four arities (`flow.clj:172-175`):

| arity | when it runs | source |
|---|---|---|
| 0 `describe` | at `create-flow`/`process` construction, to discover channels, `:workload`, `:ping-map-fn` | `flow.clj:183-209`; `flow/impl.clj:246` |
| 1 `init` | **once**, on the thread that CALLED `flow/start` — not the proc's thread — before `run` is submitted to the executor: `(step args)`, args carrying `::flow/pid`. The place to acquire an atom, an external `::flow/in-ports`/`::flow/out-ports` channel, or any process-local resource | `flow.clj:211-225`; `flow/impl.clj:263,323,166-167` |
| 2 `transition` | on **each actual status change** with `::flow/resume`, `::flow/pause`, or `::flow/stop` — and only when the status really changed | `flow.clj:234-243`; `flow/impl.clj:209-217` |
| 3 `transform` | per message | `flow.clj:245`; `flow/impl.clj:304-305` |

`::flow/stop` is the cleanup hook — the proc is never used again after it. It
is invoked on the proc's own thread from whichever site saw the command
(paused branch `flow/impl.clj:285`, running branch `:299`, mid-output `:237`)
immediately before the loop falls out (`flow/impl.clj:321`). Two traps: there
is **no `::flow/pause` transition at start** even though procs begin `:paused`
(`flow/impl.clj:271`) — the first transition you see is `::flow/resume`; and
because `transition` fires only on a real change, a repeated `pause` is a
no-op, not a second teardown. Whatever `init` acquired, unwind it here, and
never reach for a setup/teardown path outside these arities.

A third trap follows from where `init` runs (probed, `main` thread): **`init`
must not block.** `create-flow`'s `start` calls `spi/start` inline for every
proc (`flow/impl.clj:166-167`) and only then submits `run` to the `:io` or
`:compute` executor (`flow/impl.clj:323`), so a slow init serializes into the
`flow/start` caller and never gets the proc's own workload context. Acquire
cheaply in `init`; do the blocking acquisition on the first `transform`, or
hand the proc an already-open resource through its args.

Durable state is a database fact regardless. An atom in proc state is
process-local, replaceable, and dies with the graph — if losing it would break
recovery, it was a fact in the wrong place.

Full grounding, audit and probe transcript:
`docs/prds/sci-execution-runtime/research/flow-control-protocol-2026-07-31.md`.
Paths below are relative to
`reference-code/core.async/src/main/clojure/clojure/core/async/`.

### One wire, broadcast, filtered by the receiver

`start` creates ONE control chan of buffer 10 wrapped in a mult
(`flow/impl.clj:99-100`); each proc taps it with its own `(chan 10)` delivered
as `::flow/control` in its `:ins` (`flow/impl.clj:153-159`). **Every command
reaches every proc**; addressing is the receiving proc filtering on `::flow/to`
(`flow/impl.clj:199-207`; contract `flow/spi.clj:32-42`). `send-command` is a
plain `>!!` (`flow/impl.clj:71-75`). The SPI *requires* control priority in
every read and write `alts!!` (`flow/spi.clj:32-34`); the stock proc supplies
it at both (`flow/impl.clj:295,234`).

### The verb table

| verb | mechanics | guarantee | source |
|---|---|---|---|
| `create-flow` | pure construction; no channels, no threads | synchronous; throws on an invalid conn | `flow.clj:76`; `flow/impl.clj:38-69` |
| `start` | builds control/report/error/io chans, runs every proc's `init` inline, then submits each `run`; returns `{:report-chan :error-chan}`, plus `:already-running true` if re-started | **synchronous and acknowledged** | `flow.clj:108`; `flow/impl.clj:94-173` |
| `stop` | `>!!` `::flow/stop` to all, closes error+report, nils the chan registry | **asynchronous — NOT a join.** Returns before any proc exits | `flow.clj:123`; `flow/impl.clj:174-183` |
| `pause` / `resume` | one `>!!` to `::flow/all` | **unacknowledged**; observed at the proc's next `alts!!` | `flow.clj:128,132`; `flow/impl.clj:184-185` |
| `pause-proc` / `resume-proc` | identical with `::flow/to` = pid | same; still broadcast, each proc ignores unless addressed | `flow.clj:144,148`; `flow/impl.clj:187-188` |
| `ping` / `ping-proc` | fresh per-call reply chan, `alts!!` against a timeout | **synchronous, timeout-bounded, partial** — non-answering procs are simply absent | `flow.clj:136,152`; `flow/impl.clj:76-86,186,189` |
| `inject` | resolves `[pid io-id]` to a channel, `futurize`s a `doseq >!!` on `:io` | **asynchronous, backpressured**: the future completes only when every message is **put**, so a full buffer on a paused proc leaves it incomplete | `flow.clj:157`; `flow/impl.clj:190-197` |
| `command-proc` | declared in the `Graph` protocol, **not implemented** by `create-flow`'s reify and not exposed in `flow` | throws `AbstractMethodError` (probed). Treat as nonexistent | declared `flow/impl/graph.clj:24-25` |

Only `start` and `ping` are acknowledged. `pause`, `resume` and `stop` are
channel puts — never treat one as a completion. Seon's own join is a
proc-published completion promise-chan layered on flow's `::flow/stop`
transition (`src/seon/cluster/agent.clj:190-194`, `src/seon/flow.clj:553-570`);
that is not a second mechanism, it supplies the one guarantee flow omits.

### Two documented-vs-actual corrections — READ THESE

**1. Ping replies do NOT go to the report channel.** `flow/start`'s docstring
says "'ping' responses will show up here" (`flow.clj:112-114`), and
`flow/impl/graph.clj:19,23` and `flow/spi.clj:48-49` say the same. **All three
are wrong for this revision.** The implementation creates a private per-call
`reply-chan` and the proc pongs to `(::flow/reply-chan cmd)`
(`flow/impl.clj:77,80,205`); the report channel stays empty across a ping
(probed). Any design that observes pings by tapping the report channel is
built on a stale docstring.

**2. Pause does not interrupt an in-flight transform, and does not suppress
its already-computed outputs.** Status is re-read only at `alts!!` points; a
running transform is a plain function call (`flow/impl.clj:304-305`) with no
interrupt path. In `send-outputs` a pause is handled and the loop **recurs on
the same messages** — only `:exit` breaks out (`flow/impl.clj:221,230-239`).
So pause ⇒ no *new* input is read, but the current transform completes and its
entire output is still written downstream; stop ⇒ the current transform
completes and its *remaining* outputs are abandoned at the next message
boundary. This is the mechanical justification for the agent graph's two-proc
split: the mailbox answers control in microseconds while the turn's provider
call is uninterruptible.

### The ping reply, and what it costs

`pong` (`flow/impl.clj:272-279`) writes per proc:

```clojure
#:clojure.core.async.flow{:pid :sink, :status :paused   ; :paused | :running
                          :count 3                      ; transform passes
                          :ins {:in <datafied chan>}    ; minus ::control/::casts
                          :outs {}                      ; minus ::error/::report
                          :state {...}}                 ; (ping-map-fn state)
```

Everything but `::flow/state` is `postwalk`ed through flow's `datafy`
(`flow/impl.clj:22-27,275`), so a channel becomes
`{:put-count :take-count :closed? :buffer {...}}` — what
`seon.oversight/occupancy` reads. **`:ping-map-fn` defaults to `identity`**
(`flow.clj:191-193`; `flow/impl.clj:246`), so an unguarded proc's ping returns
its whole state including closures and connections. **Always declare a
`:ping-map-fn`.**

Cost model: a ping costs the FULL `timeout-ms` whenever *any* addressed proc
fails to answer, because `ret-chan` is `(async/take n reply-chan)` and closes
early only when all `n` reply (`flow/impl.clj:78,81-85`). An unknown pid
returns `nil`, never an error — indistinguishable from a busy proc. Pair the
ping with the graph's datafied `:procs` to tell them apart, as
`src/seon/oversight.clj:126` already does. Oversight's budget is 20 ms
(`src/seon/oversight.clj:39,90,125`).

### Procs start `:paused`

`start` alone runs nothing (`flow/impl.clj:271`; `flow/spi.clj:30`) — `arm!`
does `start` → `resume` (`src/seon/cluster/agent.clj:376-390`). A stopped graph
may be started again, which builds all-new channels and zeroed proc state
(`flow/impl.clj:99-124`), so everything in flight at stop is lost — exactly the
transport law's precondition.

### Parked is not paused

**Nothing in the running system ever flow-`paused` an agent.** An agent graph
is `:running` for its whole life; "parked" means each proc's `alts!!` is
blocked on its input channels
(`flow/impl.clj:295`) — an ordinary channel read on a virtual thread,
woken by a datom-routed `offer!` onto the sliding-1 mailbox
(`src/seon/cluster/wake.cljc:163-228`). A flow-`paused` proc instead parks on
`(<!! control)` (`flow/impl.clj:284`). One blocked virtual thread either way,
but different states: the measured ~8.5 KB is the **running-and-parked** shape
and must not be cited as a paused-agent cost. `pause`, `pause-proc` and
`resume-proc` have **no production call site in `src/`** — only
`monitor-graph`'s passthrough arities (`src/seon/flow.clj:616-620`); the only
`src/` uses of the protocol are `flow/start` + `flow/resume` at arm
(`src/seon/cluster/agent.clj:380,390`), `flow/ping` in oversight
(`src/seon/oversight.clj:90,125`), `flow/inject`, and `flow/stop`. The pause
half is nonetheless test-covered, including a live agent graph paused during
an in-flight provider call (`test/seon/cluster/agent_test.clj:445-470`,
`test/seon/flow_test.clj:833-900`) — the semantics are proven, but nothing
running exercises them, which is why there is still no way to express
*suspended*.

### [TARGET] The maintenance surface

Not built. What ruling #10 actually settles is narrow: the verbs are flow's
own, and there is no bespoke maintenance machinery. The rest below is the
**proposed** shape from the grounding audit
(`flow-control-protocol-2026-07-31.md:252-257`) — design input, not an owner
ruling; do not cite it as settled. Proposed: a pause/resume **decision** is a
durable fact with provenance (so a re-armed graph after a crash comes back
paused), while live **status** is always a fresh `flow/ping` and is **never
stored** — storing it is stored-derived state that goes stale the instant
someone pauses from a REPL. Verb invocations live in the runtime owner
namespace for that graph (`seon.cluster.agent`, `seon.cluster`, `seon.flow`)
as ordinary functions over one namespaced request map. Per ruling #10 there is
no lifecycle service, registry, or manager namespace and no bespoke
maintenance machinery.

Two current shapes are **defects — do not copy them**:
`seon.flow/work-launcher-proc` re-implements the control protocol by hand and
its `alts!!` omits `:priority true`, violating `flow/spi.clj:32-34`
(`src/seon/flow.clj:319`;
`docs/seon/issues/work-launcher-control-alts-lacks-priority.md`), and
`seon.flow/monitor-graph`'s `command-proc` arity delegates to the
unimplemented protocol method and throws (`src/seon/flow.clj:623-624`;
`docs/seon/issues/monitor-graph-command-proc-throws.md`).

## Workloads: the measured truth

- **`:io`** — core.async's default is a virtual thread per task on a
  virtual-thread-capable JDK with a cached-platform fallback; current Seon
  graph definitions do not override `:io-exec`, including the work launcher
  (`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:82-105`;
  `src/seon/flow.clj:381-423,626-666`;
  `src/seon/cluster.clj:1079-1096`;
  `src/seon/cluster/agent.clj:337-390`). May block; must not compute.
  Ordinary parking releases a carrier. The documented probe found a
  `synchronized` sleep pinned on JDK 21 but not JDK 26.0.1; native or critical
  sections can still pin (`workload-scheduling-truth-2026-07-29.md`).
- **`:compute`** — the whole transform is submitted to the graph's compute
  executor. It is bounded only where Seon supplies the process-root executor;
  core.async's default is cached and unbounded. It must never block.
- **`:mixed`** — **not a splitting scheduler.** It runs the proc's entire
  blocking loop and transform inline on one cached platform thread
  (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:243-323`).
  It is a fail-closed bucket for code the
  graph cannot resolve, and Seon refuses it outright.

`:compute` never identifies I/O *inside* a transform — the executor hop
moves the whole transform. So a chain that both computes and blocks must
be split at an explicit boundary, or run on `:io` and stay honest about
it. Classification is per-function and **derived** where possible: key
capability leaves carry `^{:seon.workload :io}` or `:compute` metadata lifted
at parse time into `:seon.fn/workload`
(`src/seon/sci/reader.cljc:198-245`). **[TARGET]** No current runtime derives
workload by reachability over `:seon.fn/calls`, including the planned
pure-implies-compute case. Until that owner exists, do not claim that
unannotated program-graph functions have a derived workload.

## Buffers: the loss semantics *are* the design

Pick the buffer by asking *what may be lost*, because the transport law
says anything recovery could need is a **database fact**, and anything in
flight rides channels **provided loss is free** — re-derivable from facts
or superseded by a newer complete value.

| buffer | meaning | example |
|---|---|---|
| `(sliding-buffer 1)` | latest-wins mailbox; a wake says only "look" and the woken pass derives everything from facts | agent episode conn (`src/seon/cluster/agent.clj:240-264`), armer/render/stream inputs (`src/seon/cluster.clj:1119-1148`), and page taps (`src/seon/render/web.clj:692-804`) |
| fixed | backpressure — the producer must wait | bounded work submission |
| counted-dropping | observation that must never block the producer | flow's error/report channels |

A design where channel loss breaks recovery is wrong by definition. If you
cannot name what makes a dropped item harmless, you have found a fact that
belongs in the database.

## Agent graphs

Every agent is its own flow graph, created with the agent from one
blueprint (two `:io` procs) and kicked off by the messages it receives
(`src/seon/cluster/agent.clj:240-264`). Between episodes it is **`:running`
and parked on a channel read, never flow-`paused`** — see *Parked is not
paused* above; the graph stays `:running` from `arm!` onward
(`src/seon/cluster/agent.clj:376-390`). The ~8.5 KB and
one-virtual-thread baseline was the steady 1,000 one-proc graph case on an
18-core Mac, JDK 26, `-Xmx512m`; it is not a production-agent heap
measurement:

- **`::mailbox`** (`:io`) — total and instant: forwards a payload-free
  wake; it cannot recurse because the pass derives from facts.
- **`::turn`** (`:io`) — one episode pass per signal; claims the run,
  calls the model, evaluates, commits receipts.

Parallelism across agents is by construction. Runaway protection is one
per-agent dial (max consecutive runs per episode); **any race that could
loop agents forever is a design defect to dissolve, never a thing to
cap** — and nothing re-fires: a failed turn's only retry budget is the
episode's remaining turns.

**[TARGET] `::renders`** — a third proc owning every derived view of the
agent's world (html blocks *and* its own AI context pieces), memoized in
proc state with byte digests. A 100-agent in-memory comparison on JDK 26 with
`-Xmx512m -XX:+UseG1GC`, two discarded warm-ups, three forced GCs, and a
400 ms park measured +7.3 to +9.2 KB/agent and zero new platform threads; its
one measured pass used 12 registrations
(`agent-flow-render-falsification-2026-07-29.md`). It also exposed
unresolved interest-narrowness and unbounded-memory seams. The proc and its
contract are not authored: the current graph definition contains only
`::mailbox` and `::turn` (`src/seon/cluster/agent.clj:240-264`). Production
delivery stays per-cluster.

## Wakes, faults, and streaming

**Wakes are event-driven, never polled.** One `listen!` per cluster routes
committed transactions to agent and render inputs
(`src/seon/cluster/wake.cljc:163-228`). Message/agent creation routing is
datom-selective; the current render input receives every transaction report.
Two rules that cost real debugging to learn (documented at
`src/seon/cluster/wake.cljc:6-63`): the listener **must never throw or park**
(Datahike invokes it before transaction delivery), and re-asserting an identical value produces no
datom and therefore no routed wake.

**Faults ride flow's error channel** into `fault-committer-proc`
(`src/seon/flow.clj:553-602`), which commits each as a durable fact with
provenance — so "who should fix this" is a query, not a router. Agent
*mistakes* are different: they never touch these channels, they become
flat `:seon.error` values the agent sees. One config dial selects `:record` or
`:panic` (`resources/seon/schema/config.edn:7-8`). In current `:panic` mode the
cluster handler still commits the fault and prints it; it does not throw from
the recorder (`src/seon/cluster.clj:1151-1190`).

**Streaming**: partials ride channels; only the settled reply commits. The
current JVM renderer emits complete page snapshots, mults them, and computes
per-tab changed blocks (`src/seon/render/web.clj:300-350,443-549,692-804`).
**[TARGET]** Revisioned packages/keyframes would serialize shared bytes once.
Two runs on OpenJDK 26.0.1/18 processors and headless Chrome 150.0.7871.187
with Datastar 1.0.0-RC.7 measured 0.872–1.171 ms p95 for once+mult at 50 tabs
versus 31.783–42.479 ms p50 for per-tab serialization, and 1.2–1.5 ms p95 for
a 250-event Chrome block morph; the browser measurement excludes transport
(`render-pipeline-design-2026-07-29.md`). Our
http-kit fork adds per-channel pending-byte state and a drain-or-close
completion so the `:io` writer **parks** on real backpressure — stock
`send!` reports channel openness, not socket drain
(`src/seon/render/web.clj:701-725`;
`reference-code/http-kit/src/org/httpkit/server.clj:321-326`;
`httpkit-write-path-2026-07-29.md`).

## Evaluation and the guarded door

Untrusted agent code runs in the cluster's one live SCI ctx under one
`:interrupt-fn` with a time limit as the only limit. A supplied ctx is used as
given, so definitions accumulate across evaluations; only a namespace-unmap
event evaluates in an SCI fork, whose exact namespace state becomes visible in
the live ctx only after the terminal transaction commits
(`src/seon/sci/eval.clj:1230-1287,1380-1428,789-895`;
`reference-code/sci/src/sci/core.cljc:326-330`). SCI counts nothing and has no
step concept; its interrupt hook runs at interpreted function-body entrances,
while a host call with no interpreted entrance is the known ceiling
(`reference-code/sci/doc/interrupt.md:6-8,50-65`). Evaluations go through the
one bounded submission owner (`seon.flow/submit!!`,
`src/seon/flow.clj:479-499`; `src/seon/cluster/loop.cljc:509-525`) rather than
inline on a turn thread, so eval concurrency is bounded and observable.

## The banned shapes

Each of these was tried, measured, or ruled against — reintroducing one
is a regression even if tests pass:

- **A central loop, dispatcher, scheduler, or active-set.** Agents are
  independent flows; the JVM's threads are cheap.
- **`:mixed`, or an unannotated proc.** Refused at construction.
- **A hand-rolled lifecycle or control path.** Flow ships
  ping/pause/resume/stop/inject and per-proc variants; use them on the
  graph that owns the thing (Ruling 2026-07-31 #10).
- **Polling or `Thread/sleep` where an event exists.** Interfaces express
  their dependencies and publish their own readiness; a timeout may only
  guard genuinely unobservable external state, and its firing is itself a
  bug report.
- **A second mechanism** — `foo-v2`, a parallel registry, a compatibility
  path. Fix the one owner in place and delete the superseded path in the
  same change.
- **Storing what a query derives** — status fields, counters, cached
  projections. State is which facts exist.
- **Re-execution on recovery.** Nothing re-fires: reopen the store, mark
  dangling receipts interrupted, let the agent adapt.

## Working effectively

- **Probe before you plan.** Use the selected cluster's `eval_clj` operation;
  one live form answers most design questions. The REPL is the first design
  surface; checked-in source is the durable authority.
- **Use scratch clusters.** Live proof belongs on your own named cluster;
  never reset or bounce someone else's. Clusters have distinct database
  branches, live SCI contexts, and graph state even when sibling clusters share
  the process-root store and executors
  (`src/seon/cluster.clj:1295-1311,1343-1348,1404-1405`).
- **Measure, never assert.** Every performance claim in this skill
  carries the conditions it was measured under, because this program has
  been misled twice by a number without its context.
- **When tests get awkward, suspect the design.** Fixture pain, polling,
  and exact-string assertions are design verdicts. Move the invariant to
  one choke point and keep one regression per class.
- **Read `reference-code/` before naming anything at an integration
  point.** Use the dependency's own vocabulary (`proc`, `step-fn`,
  `conns`, `:io`) — invented nouns drift and cause integration bugs.

## Related skills

`data-oriented-clojure` (write it the Seon way) → `data-modeling` (what
shape to register) → `datahike` (query/transact mechanics) →
`clojure-testing` (how to prove it) → `repl` (how the eval boundary reads
your forms).
