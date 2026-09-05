---
type: research
status: active
tags: [research, agent, runtime]
---

# Flow mechanics for per-agent graphs — measured 2026-07-28

Question (owner model, ruled 2026-07-28): every agent is its own sequential
process hosted as a running flow — pausable/resumable, kicked off by
messages, parked between episodes; no central event loop. Does core.async
flow's machinery actually support one graph per agent, and what does an idle
agent cost? Everything below is grounded in
`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`,
`flow/impl.clj`, `impl/dispatch.clj`, `impl/buffers.clj`, and
`src/seon/flow.clj`, and measured by
`docs/prds/sci-execution-runtime/research/scripts/flow-mechanics-2026-07-28.clj`
(sections: `idle | pause | lifecycle | diagnostics | buffers | dbvchan |
hotreload`; run each in a fresh JVM, `clojure -M:dev <script> <section>`,
`dbvchan` under `-M:test` for the Datahike closure). Machine: 18-core Mac,
JDK 26, `-Xmx512m`.

## 1. Idle cost — a parked agent graph is one parked virtual thread

Where the proc loop lives: `spi/start` launches the loop with
`(futurize run {:exec exs})` where `exs` is the `:mixed` executor for
`:mixed` workload and the `:io` executor for both `:io` and `:compute`
workloads (`flow/impl.clj:262`, `:323`). The default `:io` executor is
`Thread/startVirtualThread` per task (`impl/dispatch.clj:82-89`); the
default `:mixed` (and `:compute`) executor is a cached **platform** thread
pool (`impl/dispatch.clj:73`, `:91-96`). Procs start `:paused`
(`flow.clj:109`), and a paused loop parks in `(async/<!! control)`
(`flow/impl.clj:284`) — so a parked proc is exactly one parked thread of
its loop's executor, holding no CPU.

Measured (one-proc graphs sharing default executors; `idle` section):

| n graphs (`:io`) | create total | start total | parked threads | heap delta |
|---|---|---|---|---|
| 10 | 1.9 ms | 4.3 ms | +10 virtual, +0 platform* | ~24 KB/graph |
| 100 | 1.8 ms | 4.3 ms | +100 virtual | 9.0 KB/graph |
| 1000 | 4.9 ms (5 µs/graph) | 21.6 ms (22 µs/graph) | +1000 virtual, platform flat | 8.5 KB/graph (+8.3 MB total) |
| 100 (`:mixed`) | 0.9 ms | 4.1 ms | **+100 platform, 0 virtual** | 4.4 KB/graph |

*The n=10 first-run numbers carry one-time class-init noise; n=100/1000 are
the steady figures. `stop` of 1000 graphs took 17.1 ms and returned heap to
baseline; the platform-thread residue after runs is the cached `:mixed`
dispatch pool (60 s idle timeout, `Executors/newCachedThreadPool`), not a
per-graph cost.

So: 1000 idle agents ≈ 8 MB heap + 1000 parked virtual threads + zero
platform threads — **if and only if every proc declares `:workload :io` (or
`:compute`)**. The `:mixed` default pins one platform thread per proc
forever (`flow/impl.clj:247` defaults workload to `:mixed`); that is the
one way a per-agent-graph fleet stops scaling. Note two current
`src/seon/flow.clj` procs omit a workload and thus ride `:mixed`:
`capacity-observer-proc` (`src/seon/flow.clj:114`) and `mailbox-proc`
(`src/seon/flow.clj:818`).

`create-flow` allocates nothing but data (no threads until `start`,
verified: thread counts flat after creating 1000 graphs), so pre-creating
graph definitions is free.

## 2. Pause / resume / stop — commands between messages, never interruption

Mechanics: `pause`/`resume`/`stop` are one `>!!` of a command map onto the
graph's control channel (buffer 10, `flow/impl.clj:99`), multed to a
per-proc control tap (buffer 10, `:153`). A running proc sees control at
priority in its `alts!!` (`:295`), but only **between** transforms; a
paused proc reads **only** control (`:284`). `stop` additionally closes the
error and report channels and forgets the chans map (`:174-183`); conn
channels are not closed — their contents are dropped, which is exactly the
crash model's "channel contents must be losable". During a blocked output
put, `send-outputs` still alts control at priority (`:232-238`), so a proc
parked on a full downstream channel still answers pause/ping/stop.

Measured timeline (`pause` section; `:io` proc whose transform sleeps 2 s as
a fake model call, downstream sink):

```text
     0 ms  inject :m1            1 ms  transform-start :m1
   203 ms  pause called -> returned same ms (fire-and-forget)
  2007 ms  transform-end :m1; proc NOW observes ::flow/pause
  3205 ms  resume
  3206 ms  transform-start :m2 (buffered while paused); sink receives :m1
  5208 ms  transform-end :m2; sink receives :m2
  5710 ms  stop -> ::flow/stop transition observed
```

Answers: an in-flight blocking `:io` call is **never interrupted** — pause
returns immediately, the call runs to completion, its output lands in the
conn buffer, and only then does the proc park paused. Messages injected
while paused sit in the input buffer and run on resume. The same holds for
`stop`: an in-flight transform completes on its (virtual) thread after
`stop` returns. For `:compute` workload the loop instead blocks in
`.get compute-timeout-ms` on the futurized transform (`flow/impl.clj:258-260`);
a timeout raises into the error channel but the compute future itself is
**not cancelled** — a wedged compute transform leaks its thread until it
returns, which is why the evaluation boundary's `:interrupt-fn` remains the real
stop for agent code.

## 3. Dynamic lifecycle — 0.084 ms per full cycle, no leak

Measured (`lifecycle` section): 1000 × (create-flow → start → resume →
stop) of a 3-proc, 2-conn graph = 84.2 ms total, **0.084 ms/cycle**. Heap
15.7 → 15.9 MB (flat after GC); threads 8 → 33 platform, all from the
cached dispatch pool (idle-timeout, not per-cycle growth), 0 virtual
residue. No leak. A graph can also be restarted after `stop`
(`flow.clj:123-126`), state re-derived by `init` — same rebuild-freely
economics the cluster graph already relies on (~0.3 ms, boot sequence doc).

## 4. Per-graph diagnostics — ping, report, error

Each `start` returns that graph's own `:report-chan` and `:error-chan`,
both `(sliding-buffer 100)` (`flow/impl.clj:101-102`) — under overflow
they **drop**, which is why `src/seon/flow.clj`'s fan-out replaces the
fault path with a counted dropping buffer whose overflow calls
`commit-drop!` (`src/seon/flow.clj:546-548`).

`flow/ping` round-trips a `::flow/ping` command per proc
(`flow/impl.clj:76-86`) and returns, per pid: `:status`
(`:paused`/`:running`), `:count` (messages transformed — this is how the
backpressure probe read progress), datafied ins/outs **with live buffer
occupancy**, and `::flow/state` from the proc's `:ping-map-fn`
(`flow.clj:191-193`). Demonstrated live (`diagnostics` section):

```clojure
{:loop #::flow{:status :running, :count 2,
               :ins {:in {..., :buffer {:type FixedBuffer, :count 0, :capacity 10}}},
               :state {:handled 2}}}
```

Error propagation to the cluster's fault committer, demonstrated as
topology: three one-proc "agent" graphs, each graph's `error-chan` piped
into one shared fault channel with `(async/pipeline 1 fault-chan (map
#(assoc % :agent id)) error-chan false)` — `close? false` so one agent's
stop never closes the committer's inbox. A throwing transform produced
`{:agent :agent-3, ::flow/pid :loop, ::flow/cid :in, ::flow/msg :boom,
::flow/ex #error...}` at the fault channel — full provenance (which agent,
which proc, which input, which message) with zero extra machinery; the
faulted proc kept running (`:handled` advanced afterward), matching
`flow/impl.clj:312-315` (transform exceptions go to the error chan, loop
recurs). Per-agent graphs therefore make "who should fix this" a channel
tag plus the existing fault-committer proc; `seon.flow/start-error-fanout!`
already owns this shape for one graph and generalizes by taking N error
mults into the one dropping-buffer fault channel.

## 5. Buffers and backpressure for large transient values

- **Backpressure is free on conns.** Producer transform emitting 100
  messages against a `{:buf-or-n 4}` conn and a 50 ms/msg consumer: the
  producer's ping `:count` stayed **0** for the whole 2 s window while the
  consumer's state advanced 4 → 19 → 38 — the producer was parked inside
  `send-outputs` (`flow/impl.clj:232`, blocking put via `alts!!`) the whole
  time. A fixed buffer on the conn is the correct shape for big eval
  results and model replies: the fast side parks, nothing is copied,
  nothing is dropped.
- **Sliding for token streams.** `(sliding-buffer 1)` (`impl/buffers.clj:59-79`,
  drop-oldest on add) absorbed 100,000 puts in 21.9 ms (~4.6 M puts/s),
  producer never parked, consumer sees only the newest — the existing
  latest-wins presentation mailbox, correct for streamed partials where
  only the current snapshot matters.
- **Channel vs database for an 8 MB value** (`dbvchan` section): channel
  hand-off = **0.010 ms** (a pointer pass — `FixedBuffer.add!*` is
  `LinkedList.addFirst` of the reference, `impl/buffers.clj:24-27`).
  Datahike transact of the same string: `:mem` **1.4–2.1 ms** (~150–200×),
  `:file` (the durable production shape) **74–88 ms** (~7,000×), and even
  64 KB costs ~38 ms durable. The owner's intuition is confirmed with a
  wide margin: streamed tokens and multi-MB transients must ride channels;
  the database gets the settled fact (and at most coalesced
  `:seon.db/no-history?` snapshots at a config cadence, per the existing
  live-update rule).

## Hot reload and cluster reset — honest caveats

- **Hot reload works only for var step-fns.** The loop calls `transform`
  = the step it was constructed with (`flow/impl.clj:258-261`); passing
  `#'f` means each call goes through the var. Proven (`hotreload`
  section): after `alter-var-root`, the var-backed proc emitted `[:v2 :b]`
  while an identical proc built from the captured fn **value** still
  emitted `[:v1 :b]`. Consequence: `src/seon/flow.clj` procs built as
  `(flow/process (flow/map->step {...closures...}))` capture their
  closures at construction — re-evaluating the underlying `defn`s does
  NOT change a running graph. Per-agent graphs that should hot-reload must
  pass a var as the step-fn (one `defn agent-step` with the map->step
  dispatch inside, or `(flow/process #'step)`). Also fixed at
  construction: `describe`/ins/outs/workload (`flow/impl.clj:39`, `:246`)
  — topology or workload changes always need the rebuild, which §3 prices
  at 0.084 ms.
- **Describe is called at `flow/process` time** (`flow/impl.clj:246`), so
  even a var-based proc's channel set is frozen until rebuild.
- **Cluster reset** = `flow/stop` on the cluster graph and each agent
  graph: idempotent under the graph lock (`flow/impl.clj:174-183`),
  measured ~17 µs/graph at n=1000; in-flight `:io` transforms complete
  after stop returns (§2), so a reset that must not observe stragglers'
  writes should stop graphs **before** releasing the store, exactly the
  `start-error-fanout!` completion-join pattern
  (`src/seon/flow.clj:589-592`). Conn contents are dropped on stop — safe
  by the crash model (nothing re-executes; boot re-derives from facts),
  but it means a **per-episode** destroy loses buffered mailbox messages
  as a normal-path event, not only on crash.

## Verdict: per-agent graph, procs pinned to `:io`/`:compute`

The numbers remove the idle-cost argument for per-episode graphs: an idle
agent graph is ~8.5 KB heap and one parked virtual thread (1000 idle agents
≈ 8 MB, zero platform threads, 22 µs each to start), while per-episode
create/destroy — though itself only 0.084 ms — makes mailbox loss a
normal-path event and re-runs `init` on every wake. Create the graph at
agent creation, park it between episodes (procs are born `:paused`; pause
is free and instant), and let messages on its in-ports kick episodes. Two
enforced conditions: (1) every proc declares `:workload :io` or `:compute`
— the `:mixed` default pins a platform thread per proc and is the only
scaling cliff found; (2) step-fns are vars, or hot reload silently stops
applying to running agents.

## One-sentence answers

1. **Idle cost:** a parked per-agent graph is one parked virtual thread and
   ~8.5 KB heap per proc (loop hosted on the `:io` virtual-thread executor
   for `:io`/`:compute` workloads, on a platform cached pool for `:mixed`),
   with 1000 graphs costing 8.3 MB and 21.6 ms to start.
2. **Pause/resume/stop:** commands are one buffered control put observed
   only between transforms — an in-flight blocking `:io` call always runs
   to completion (pause returns in ~0 ms, the 2 s fake model call finished,
   its output buffered, then the proc parked), and `:compute` futures are
   not cancelled on timeout.
3. **Dynamic lifecycle:** full create/start/resume/stop of a 3-proc graph
   costs 0.084 ms with zero heap or thread leak across 1000 cycles.
4. **Per-graph diagnostics:** each graph's ping returns per-proc status,
   message count, live buffer occupancy, and `:ping-map-fn` state, and each
   graph's own error channel carries `{pid cid msg ex}` fault maps that
   pipeline (close? false) into the cluster's one dropping-buffer fault
   channel tagged with the agent id.
5. **Buffers/backpressure:** fixed conn buffers park the fast producer for
   free (producer count 0 while consumer drained at 50 ms/msg),
   `(sliding-buffer 1)` absorbs 4.6 M puts/s for token streams, and an 8 MB
   value crosses a channel in 0.01 ms vs 1.4–2.1 ms `:mem` / 74–88 ms
   `:file` Datahike transact (~7,000× durable) — large transients belong on
   channels, settled facts in the database.

Probe script:
`docs/prds/sci-execution-runtime/research/scripts/flow-mechanics-2026-07-28.clj`.
