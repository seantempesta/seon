---
type: research
status: active
tags: [research, agent, runtime, architecture]
---

# The flow inventory and the transport law

Owner direction (2026-07-28): identify all the possible flows in the
system; remove or push complexity to the edges so the agent loop is a
running flow that can be arbitrarily paused and restored and kicks off
from messages it receives; message channels for messaging and error
propagation; metadata to the database (shared), huge values over
channels with buffers and backpressure.

Grounding: `src/seon/cluster.clj` (the boot tower and today's one-proc
graph), `src/seon/cluster/loop.cljc`, `src/seon/cluster/wake.cljc`,
`src/seon/render/web.clj`, `src/seon/ai.cljc` + `src/seon/ai/stream.clj`,
`src/seon/flow.clj`, `reference-code/core.async/.../flow.clj` +
`flow/impl.clj` + `flow/spi.clj`, and the prior research this extends
rather than re-derives: `flow-per-cluster-2026-07-27.md` (graph
granularity), `turn-dispatcher-design-2026-07-27.md` (the race audit any
plural design must keep killed), `flow-dynamic-update-2026-07-27.md`
(vars hot-reload procs; topology rebuild ≈ 0.3 ms),
`n4-plan-2026-07-27.md` (render topology). The one-open-run-per-agent
transaction fence and interrupted+adapt recovery are law and unchanged
throughout.

## 1. The transport law, revised

**The rule, in two sentences.** Anything recovery, attribution, or
another process could ever need is a database fact — identities,
receipts, dispositions, messages, errors, config, the settled reply —
and when the payload is bulky the fact carries the identity, digest,
and size while a blob carries the bytes. Everything in flight between
procs rides channels, however large, provided its loss is free by
construction: either the value re-derives from facts at a basis
(wakes, work submissions, render values) or a newer complete value
supersedes it (streamed snapshots, page repaints) — and the buffer is
chosen from that loss semantics, never from a number.

**What this revises.** The standing law said "flow channels carry only
disposable wake/submission/render values" and routed even streamed
reply partials through the database (coalesced no-history snapshots —
the path `src/seon/ai/stream.clj` implements today). The revision keeps
the first half exactly — nothing durable ever depends on a channel —
and widens "disposable" to include **large transient payloads**: a
40 KB streamed partial, a whole rendered page, a morph patch. Those
were disposable all along; the old law's mistake was concluding
"disposable, therefore route the churn through the writer so the one
database path stays the one path." The one path stays the one path for
FACTS. High-churn presentation bytes get channels, backpressure, and
latest-wins buffers — which the O14-dissolution ruling already ordered
for streamed partials ("their coalesced no-history fact is retired",
plan README rulings 2026-07-26 PM) and which the current N4 slice has
not yet applied.

**The buffer table** — each loss semantics has exactly one buffer shape,
and choosing a buffer IS choosing the loss semantics:

| semantics | buffer | used by |
|---|---|---|
| newest-only signal, payload-free | `(sliding-buffer 1)` | wakes (boot, listener, self-rewake, completion→wake) |
| newest-only complete value, possibly huge | `(sliding-buffer 1)` | stream snapshots, per-tab render taps |
| bounded work admission — park the producer | fixed buffer, depth a config fact | compute submissions, `:io` turn tasks |
| reserved completion positions | fixed buffer = active+queued capacity | launcher completions |
| lossy observation, loss counted out loud | counted-dropping (ours) over flow's `(sliding-buffer 100)` source | fault tap; monitor taps stay plain sliding |

**The misdesign test.** For every flow below, the crash walk asks: kill
-9 at the worst instant — what was on a channel, and does anything
durable depend on it? A flow where channel loss breaks recovery is
misdesigned; each design below passes the walk, and section 5 records
the walks.

## 2. The inventory

Verdict codes: **(a)** its own flow graph, **(b)** a proc within a
shared graph, **(c)** a plain function/loop on an executor or virtual
thread, **(d)** not concurrent at all.

Graph census under this design — the complete list, nothing else earns
a graph:

- **one process-root work-launcher graph** (execution substrate, both
  workload classes),
- **one cluster graph per cluster** (dispatcher proc, render proc,
  later a schedule proc),
- **one fault fan-out mini-graph per observed graph** (the existing
  `start-error-fanout!` shape — it must outlive and observe its source,
  so it cannot be a proc inside it),
- **one build/indexer graph per publish or dev-watch invocation** (N5,
  process-scoped, not cluster-scoped).

| component | verdict | reason in one sentence |
|---|---|---|
| per-agent loop | **(b) one dispatcher proc per cluster — never a graph or proc per agent** | an agent is one datom, agents are created/destroyed as data while flow topology is static (`create-flow` closes over procs; no add/remove-proc — `flow/impl.clj:38-70`), so per-agent concurrency is the launcher's task table and per-agent pause is a fact `next-work` respects (§3.1). |
| the turn body (claim → prompt → model → freeze → fold) | **(c) one `:io` task per turn, submitted by the dispatcher** | it blocks on provider and database I/O for seconds, so it is launcher work with a retained `FutureTask`, not a proc (turn-dispatcher §"Submitted-task options", option 2). |
| guarded sci eval | **(c) `submit!!` through the bounded `:compute` class** | already the design: fixed-buffer channel = backpressure, bounded platform executor = parallelism, two mechanisms never one Semaphore (`src/seon/flow.clj:386-420`). |
| model-call streaming (tokens) | **conn, not a proc**: a `(sliding-buffer 1)` stream channel from the turn's SSE fold into the render proc's in-port | the fold already runs on the turn's thread and the sink is one `offer!`; the only concurrent thing streaming needs is transport with latest-wins loss (§3.3). |
| render pipeline (N4 full) | **(b) one render proc per cluster** + per-tab taps | interest wake → one derivation → equality suppression → `mult` → per-tab `(sliding-buffer 1)` taps; one derivation serves 32 tabs instead of 32 (§3.2). |
| per-tab SSE writer | **(c) one virtual-thread loop per connection reading its tap** | connections churn with browsers while graphs are static topology; a per-tab flow graph (the `not-yet` idea) buys only monitor visibility at the cost of a graph lifecycle per socket — cut it (§3.2). |
| fault committer + error propagation | **(b) proc in the fan-out mini-graph, exactly as built** | core faults ride flow's error channel → `mult` → counted-dropping tap → committer proc; agent mistakes are flat values/facts and NEVER touch these channels — the owner's "channels for error propagation" is this split, already landed (`src/seon/flow.clj:452-600`). |
| wake / `listen!` fan-out | **(d) a four-line callback, never a proc** | it runs inside the writer's commit path where a throw hangs the committer and a park slows every transaction (measured, `wake.cljc:12-32`); it offers into in-ports and returns. |
| message delivery between agents | **(d) database rows + the wake conn — message CONTENT never rides a channel** | a trigger must sit unanswered across a crash until something wakes the agent (interrupted+adapt law), so the row is the transport and the channel carries only "look"; a huge payload is a blob ref on the row (§3.5). |
| pre-provider capture (context-blocks) | **(d) sequential inside the turn** | ruled: the exact prompt commits BEFORE the provider call, a durability ordering constraint — concurrency here would break the very guarantee it exists for. |
| schedule fires (future) | **(b) one schedule proc per cluster** | derives earliest-fire from facts, parks until it, commits the fire as an ordinary trigger message (which wakes agents by the one path); a schedule-fact commit re-wakes it through `listen!` so the park is re-derived, never a tuned poll (§3.6). |
| /data drill paging | **(d) request/response, URL is the state** | already stateless by design ("a drilled position is a link somebody can send" — `web.clj:338-360`); http-kit's virtual-thread workers are all the concurrency it has or needs. |
| embeddings (`SEON_EMBED`) | **(c) a capability owner on `:io`** | one blocking call through the effect owner like any other leaf; bulk (re)indexing, if it ever exists, is a build-graph pipeline, not cluster machinery. |
| corpus / N5 indexer | **(a) its own build-scoped graph**: source-enumerator (`:io`) → indexer (`:compute`) → committer (`:io`) | different lifecycle (publish command / dev watch, process-scoped) and a genuine parallel pipeline with backpressure between stages; the testbed procs in `flow.clj` are already this shape. |
| operator advertisement | **(d) one file write at boot/stop** | the interface publishes its own readiness; a reader validates (pid, start-instant) against the process table — nothing runs. |
| config apply / reconcile / recovery pass | **(d) boot-tower steps, strictly ordered** | each layer reads only the one below it; converged = zero writes; ordering is the contract, concurrency would be a bug. |
| problems / readiness / banner | **(d) pure derivations of a database value** | derive, never store; nothing to schedule. |
| process work launcher | **(a) one process-root graph, both workload classes** | the execution substrate every cluster shares — like the root executors it must not die with any one cluster; extend the existing compute launcher to `:io` per the dispatcher design rather than building a second one (§3.4). |

## 3. Per-flow designs

### 3.1 The cluster graph — dispatcher, and why agents are not graphs

The owner's centerpiece — "the agent loop is just a running flow we can
arbitrarily pause and restore and kick off from messages it receives" —
is satisfied at three granularities WITHOUT a per-agent graph, and each
granularity uses the mechanism that already owns it:

- **per-agent pause/restore** is a database fact. An agent is
  attributes and connections; "paused" is one attribute `next-work`
  filters on, so pausing an agent is a transaction, restoring it is a
  retraction plus the wake that commit itself fires, and the state
  survives every crash for free. Flow cannot give this: `pause-proc`
  is an ops throttle on a proc, not durable state, and there is no
  proc per agent to address.
- **per-cluster pause/restore** is flow's own addressed
  `pause`/`resume` on the cluster graph — channels and their buffered
  contents survive a pause (`flow/impl.clj:199-217`), so this is the
  live ops control.
- **across a crash**, restore is not a code path: boot primes one wake,
  `next-work` re-derives everything from facts, and interrupted runs
  reach the agent as the one derived warning. "Kick off from messages
  it receives" is exactly today's trigger row + `listen!` wake —
  already built, already live-proven.

A per-agent graph would multiply control channels, error/report
channels, fan-outs, and monitor views by the agent count, force a
topology rebuild on every agent create/delete, and hold NO state worth
holding — the run facts already are the loop's state. The linear
channel/proc-loop cost per graph is measured and real
(`flow-per-cluster-2026-07-27.md` §"start allocates"), and it buys
nothing the three granularities above don't give more simply. Agents
stay data; the loop stays one proc.

**The dispatcher proc** (the turn-dispatcher design, adopted): ordinary
`flow/process` built with a var (`#'step`) so a re-evaluated defn
updates the running proc; `:workload :io`; in-ports = the wake channel
`(sliding-buffer 1)` and the launcher's completion channel; state = the
immutable `agent-id → submission-id` active map. One pass: pin one
database value, derive all admissible work (`all-work`), skip active
agents, `try-submit!` up to capacity, return. Completion removes the
mapping and offers one wake. Serial-today is the same mechanism at
`:io` concurrency 1 — no inline mode, no second loop. Every race in the
audit stays killed: the active map excludes duplicate `:call`/`:resume`
submission before the paid call, and the transaction fences
(agent-pointer, plan-digest CAS, receipt identity, held-run) remain the
durable last line.

What rides where: the wake channel carries `::wake` (nothing);
the submission channel carries a task closure plus the (agent, run)
identity — a POINTER to durable work, never the work; the completion
channel carries {submission-id, agent-id, report-or-fault, instant};
the report channel carries turn reports (observation, droppable).
Everything the turn DECIDES commits as facts inside the turn.

### 3.2 The render pipeline

One render proc per cluster (`:io` — it reads the database and
serializes; the per-block evals reach `:compute` through `submit!!`
like every other eval). In-ports: the render-interest wake
`(sliding-buffer 1)` from `listen!`, and the stream channel (§3.3).
Body: derive the registered surfaces at one database value, serialize,
equality-suppress against registration memory (the last value PRODUCED,
held in proc state — disposable, rebuilt by one re-render on restart),
and put changed `[surface-id html]` patches to its out. The out feeds
one `mult`; each SSE connection taps it with `(sliding-buffer 1)` so a
slow browser gets the newest page and nothing upstream ever waits.

**Per tab: a tap and a virtual thread, not a graph.** The connection's
`on-open` taps the mult and starts one virtual-thread loop that reads
the tap and writes SSE, honouring the coalesce floor; `on-close` untaps
and the loop ends. This deletes today's per-connection `listen!` +
hand-rolled `ArrayBlockingQueue` mailbox + per-connection re-derivation
(the `not-yet` items `::interest-matching`, `::shared-registration`),
and it deliberately REJECTS `::per-tab-graph`: a flow graph per socket
is lifecycle machinery for something whose whole life is
`on-open`/`on-close`, and its only benefit (monitor visibility per tab)
is served by the render proc's ping map carrying tap counts.

Backpressure shape: the render proc's put into the mult parks only if
EVERY tap is full and unbuffered — with sliding-1 taps it never parks;
the wire's backpressure is each tab's own socket, absorbed by that
tab's loop, invisible to the proc. This is the owner's "buffers,
backpressure" applied: the huge value (a page of HTML) rides the
channel, the newest wins, and no consumer can slow the producer.

### 3.3 Model-call streaming — tokens as channel values

The turn's SSE fold (`ai/stream-fold`) already calls a sink once per
content chunk with a COMPLETE snapshot `{text, tokens}`. The sink
becomes one line: `(offer! stream-channel snapshot)` onto a
`(sliding-buffer 1)` channel that is an in-port of the render proc.
The render proc treats the newest snapshot as transient input to the
stream blocks (held in proc state beside registration memory, keyed by
agent) and repaints exactly as it would for a database wake. On the
turn's terminal transaction the settled facts commit, the next
database wake repaints from facts, and the proc drops the snapshot for
that agent.

This retires the ENTIRE database half of `seon.ai.stream`:
`snapshot-tx`, `settle-tx`, the `:seon.ai.stream/*` attributes, the
no-history facet use, the publisher's virtual thread + mailbox + atom
+ cadence loop, and the settle-rides-the-terminal-transaction coupling
— replaced by one channel and one `offer!`. The two exercise blocks
survive unchanged in spirit: they read the snapshot from the render
unit instead of a query. This is the O14-dissolution ruling finally
landing, and it is the cleanest demonstration of the revised law: a
20 KB partial fifty times a second was never a fact; the ATTEMPT row
and the settled reply are the facts, and they were already committed
on the durable path.

Memory bound: one slot per stream channel = at most one snapshot in
flight, superseded in place — the buffer IS the coalescing, so the
config cadence survives only as the render proc's repaint floor.

### 3.4 The process work launcher

One process-root graph owning the custom `ProcLauncher`
(`work-launcher-proc` extended to both classes per the dispatcher
design): per-class submission channels with FIXED buffers whose depths
are config facts — a full buffer parks (`submit!!`, compute evals) or
returns `full` (`try-submit!`, turn tasks) and that is the whole
backpressure story; per-class active counts enforce concurrency;
completions ride a channel sized to reserved capacity; started
`FutureTask`s are retained so stop can interrupt and publish its
stopped event only after owned tasks exit. Shared by every cluster the
way the root executors are; a cluster reset never touches it.

### 3.5 Messaging — where the owner's "message channels" intuition lands

Message CONTENT stays a database row, and this is load-bearing, not
conservatism: the crash model requires a trigger to sit unanswered
across kill -9 until the agent next wakes, answeredness is derived from
run-opening tx-meta, and the double-send/idempotency proofs all fence
on committed rows. A channel-borne message would be lost with the
process and the recipient would never know it existed — the exact
misdesign the crash walk exists to catch. What IS channel-shaped in
messaging is already channels: the delivery commit fires the
recipient's wake (`:seon.cluster.message/to` is the wake set), and
when clusters are plural in one process the same mechanism spans them —
commit into the recipient cluster's branch, its listener wakes its own
graph. A huge payload gets a blob and the row carries identity, digest,
size — the law's database clause verbatim. No new mechanism.

### 3.6 Schedule fires (when the component exists)

One proc per cluster, `:io`. Facts own the schedule (`fire-at`,
recurrence); the proc derives the earliest future fire at one basis and
parks until either that instant or a wake from `listen!` on schedule
attributes (a changed schedule re-derives the park — the clock is the
backstop for a genuinely unobservable future instant, which is the one
thing L7 permits a clock for). Firing = committing an ordinary trigger
message; the agent wakes by the one path. Kill at any instant: the
schedule facts survive, the next boot's proc re-derives the earliest
fire, a fire that committed is answered or unanswered like any trigger,
a fire that didn't commit fires (once) on re-derivation. Nothing rides
a channel but the wake.

### 3.7 The build/indexer graph (N5)

Its own graph because its lifecycle is an invocation (publish command,
dev watch), not a cluster: source-enumerator (`:io`) → indexer
(`:compute`, fixed-buffer conn = backpressure against a fast reader) →
one committer (`:io`, the single writer of program-graph pages).
Channel values are namespace sources and tx-pages — large, re-derivable
from the tree, free to lose: a killed build reruns; the ancestor's
rename-at-end keeps a partial build invisible. The testbed procs
(`source-enumerator-proc`, `indexer-proc`) are already this topology.

## 4. What must never be a flow

Naming these prevents the machinery-first mistake at review time:

- **the wake listener** — it runs inside the writer's commit; four
  lines, two prohibitions, measured.
- **transactions and transitions** — the writer is one serial loop
  (L11); "a flow of writes" would be a second writer.
- **the boot tower** — strictly ordered layers; readiness is published
  per layer, not scheduled.
- **derivations** (prompt, problems, blocks, banner) — pure functions
  of a database value; the render proc SCHEDULES them, it does not
  make them concurrent.
- **request/response HTTP** (/data, page GETs) — http-kit's virtual
  threads already own it.

## 5. Crash walks — kill -9 at the worst instant, per flow

Every row must satisfy: nothing re-executes, no refire, channel loss
free.

| flow | lost on the channel | re-derives from facts | re-executes? |
|---|---|---|---|
| dispatcher | queued wakes, queued/active submissions, completions | boot wake → `recover-runs!` marked dangling receipts interrupted, released dead custody; `next-work` finds open triggers and resumable folds; the interrupted warning derives | nothing — a paid call that may have transmitted is never re-called (`ai/disposition` choke point); the trigger sits until the next wake |
| turn task mid-model-call | the in-flight completion | attempt row already committed receipt-before-terminal; settled reply absent → interruption settles claim-then-close, agent adapts | no |
| streaming | the newest snapshot(s) | nothing needs to — the attempt row and (if it committed) the settled reply are the facts; no stale partial row exists to retract, which DELETES today's crash-walk row about one | no |
| render | pending patches, taps, registration memory | every tab reconnects and repaints from current facts; reconnect = repaint | no — one re-render per open page is derivation, not re-execution |
| fault fan-out | buffered faults not yet committed | committed fault facts survive; uncommitted ones are lost WITH their process, which is the honest ceiling for observation of a dying process (drops are counted out loud while alive) | no |
| messaging | only wakes | rows are durable; unanswered-trigger derivation finds them; boot wake delivers the look | no |
| work launcher | submissions, completions | every submission was a pointer to durable run facts; the dispatcher's next pass re-derives and re-submits admissible work under the same fences | the FENCES prevent re-execution: receipt-exists, plan-frozen, agent-already-running |
| schedule proc | the parked timer | earliest-fire re-derives at next boot; a committed fire is a message row | a fire commits at most once per derivation window; the fence is the fire's own identity row |
| indexer graph | sources/pages in flight | the tree; rerun the build — rename-at-end means a partial build was never visible | re-running a pure indexer over sources is derivation |

No flow above depends on a channel for recovery; the law holds at every
row.

## 6. Lowest complexity — what this architecture deletes

- **`seon.ai.stream`'s database half**: `snapshot-tx`, `settle-tx`, the
  publisher (thread + mailbox + atom + volatile + cadence loop), the
  `:seon.ai.stream/*` attributes and their no-history registration, and
  the settle-tx-rides-the-terminal-commit coupling in the loop. Kept:
  `stream-fold`/`stream-event` (the pure wire fold) and the two blocks,
  re-pointed at the snapshot in the render unit.
- **`seon.render.web`'s per-connection machinery**: the per-tab
  `d/listen` + hand-rolled `ArrayBlockingQueue` + per-tab full
  re-derivation, replaced by the one render proc + mult + sliding-1
  taps. Three of the four `not-yet` items land as this one change; the
  fourth (`::per-tab-graph`) is REJECTED, not deferred.
- **the central inline pass**: `loop.cljc`'s `step` stops executing the
  turn in-transform (the event-loop-shaped serial pass, including its
  in-pass `Thread/sleep` backoff) and becomes derive/filter/submit/
  return; the turn body moves whole into a launcher task. The global
  `next-work`-for-an-ordinal lookup inside a fold dies with it
  (`next-agent-work` per the dispatcher design).
- **`seon.flow/database-proc` and helpers** — already named dead in
  `loop.cljc`'s docstring; this design gives nothing a reason to
  resurrect them.
- **testbed fakes in the production namespace** — `planner-proc`,
  `namespace-owner-proc`, `seeded-outcome`, `lineage-status`,
  `escalate-lineage?` serve only the standing testbed; they move under
  test support or die when N5's real procs land (their real
  counterparts are the indexer graph's).
- **no new mechanisms**: no per-agent graphs, no per-tab graphs, no
  message bus, no second wake path, no scheduler framework — the
  additions are one proc (render), one conn (stream), one future proc
  (schedule), and the already-specified launcher generalization.

Net: the system's concurrent surface is four graph SHAPES (launcher,
cluster, fan-out, build), every channel's buffer states its loss
semantics, and everything else is facts and pure functions — which is
the owner's "complexity removed or pushed to the edges" stated as an
inventory.

## 7. Owner decisions this raises

1. **Seal the revised transport law** (§1) — it supersedes the
   "channels carry only disposable values / one database path for live
   updates" phrasing in CLAUDE.md and `architecture.md`; the streamed-
   partial no-history pattern text dies with it.
2. **Confirm agents-are-not-graphs** (§3.1) — per-agent pause as a
   database fact, per-cluster pause as flow's own command; this is the
   load-bearing interpretation of "arbitrarily pause and restore".
3. **Confirm the per-tab-graph rejection** (§3.2) — `ui.md` currently
   specifies a small per-tab flow graph; this design replaces it with
   tap + connection-owned virtual thread and should be reconciled in
   `ui.md` when N4's shared-registration slice lands.
