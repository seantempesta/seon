---
type: research
status: active
tags: [research, web, agent, database]
---

# Eval transaction → render fanout: smallest one-mechanism fix

## TL;DR

Keep the evaluator and database truth exactly as they are: attempt every complete
form in order, durably record each real outcome with its accepted program-graph
tee, and let database writes performed by forms commit immediately. The
pathological cost is caused by treating each of those durable eval-record commits
as a human-visible frame.

The smallest safe fix belongs in the **existing Datastar transaction coalescer**:
derive a running eval-record commit from its datoms, merge its change evidence
into the existing pending change, but do not schedule an expensive render while
the owning turn is still `:running`. The already-existing terminal turn
transaction (`:done`, `:error`, or crash-recovery terminalization) schedules one
normal render from the latest immutable database value. Any non-eval transaction
still schedules normally, so a canvas/domain write, browser action, message, or
other deliberate state change remains live and also carries any pending eval
evidence into that frame.

This is presentation coalescing, not state coalescing. It adds no event bus,
projection, durable marker, transaction metadata, cache, or alternate eval path.
It is the concrete application of the roadmap rule: **coalesce notifications,
never state**.

After that first patch, finish the already-designed observed-read/result-change
invalidation and bounded recent transcript readers. Those remove cross-agent and
grown-history amplification from the one final frame. Do not start by buffering
eval rows or memoizing database values.

## Observed failure

The supplied live profile is internally consistent with the source:

- one malformed reply produced 117 persisted eval rows;
- commits were roughly 500 ms apart, so the 16 ms normal settle and 500 ms hard
  coalescing deadline collapsed almost nothing;
- an agent render cost 44–169 ms and an open debug render cost 220–432 ms;
- Node's physical footprint peaked near 2.7 GB and later fell near 833 MB, the
  sawtooth expected when repeated large short-lived render graphs pressure GC;
- the database grew by roughly 14,000 files / 223 MB over the observed interval.

The storage numbers need a controlled before/after measurement before assigning
all of them to those 117 commits—the interval can include schema, boot, index,
commit-graph, and other writes. The direction is nevertheless plausible because
each Datahike commit flushes changed immutable index nodes and updates durable
commit/head state.

## The current causal path

### Every form intentionally gets a durable fact

`eval-batch!` is an explicit non-fail-fast fold. Every parsed entry runs inside
its own record boundary, and an ordinary failure does not stop later entries
([`src/seon/eval.cljs:4593`](../../../../src/seon/eval.cljs),
[`docs/seon/architecture/agent-runtime.md:180`](../../../seon/architecture/agent-runtime.md)).

`record-eval!` allocates and transacts one eval as a component of its turn. The
eval row and accepted program-graph tee share the transaction; a process-local
`result/<id>` handle is bound only after the committed ID is known
([`src/seon/eval.cljs:2982`](../../../../src/seon/eval.cljs),
[`src/seon/eval.cljs:3037`](../../../../src/seon/eval.cljs),
[`src/seon/eval.cljs:4143`](../../../../src/seon/eval.cljs)). This gives the
system three important truths:

- a persisted eval ID denotes a form that was actually attempted;
- its stored outcome and accepted declaration facts are atomic;
- a crash cannot leave a live result handle whose eval fact never committed.

The turn already supplies the durable presentation boundary. `open-turn!`
commits `:seon.agent.turn/status :running` before evaluation, and `close-turn!`
commits `:done` or `:error` after the body. Unexpected recovery terminalizes the
running turn without fabricating missing evals
([`src/seon/agent/turn.cljs:239`](../../../../src/seon/agent/turn.cljs),
[`src/seon/agent/turn.cljs:305`](../../../../src/seon/agent/turn.cljs),
[`docs/seon/architecture/agent-runtime.md:531`](../../../seon/architecture/agent-runtime.md)).
No new batch-complete fact is necessary.

### Every commit currently becomes a render opportunity

`seon.db/listen!` turns each Datahike report into a post-commit database value,
pre-commit value, effective datoms, and an attribute index
([`src/seon/db.cljs:1439`](../../../../src/seon/db.cljs),
[`src/seon/db/internal.cljs:1939`](../../../../src/seon/db/internal.cljs)). The
single Datastar listener passes every report to one process-local coalescer
([`src/seon/web/datastar.cljs:555`](../../../../src/seon/web/datastar.cljs)).

That coalescer is correct for bursts: it keeps earliest `db-before`, latest
`db-after`, unioned datoms/attributes, a 16 ms normal settle, 300 ms structural
settle, and a 500 ms maximum wait. It cannot collapse transactions arriving
about 500 ms apart. Each timer drains into `broadcast!`, which invokes every
live subscription's render transition before it knows whether an event will be
sent ([`src/seon/web/datastar.cljs:505`](../../../../src/seon/web/datastar.cljs),
[`src/seon/web/datastar.cljs:588`](../../../../src/seon/web/datastar.cljs)).

The agent feed currently routes only by changed **attribute**, not entity/read
result. Any eval transaction intersects the transcript renderer's eval attrs;
the same attr can dirty every open agent transcript even when the eval belongs
to a different agent
([`src/seon/web/datastar.cljs:1236`](../../../../src/seon/web/datastar.cljs),
[`src/seon/ui/agent_view.cljs:420`](../../../../src/seon/ui/agent_view.cljs)).

An open debug feed is worse: its transition ignores the change and recomputes
the exact AI projection plus the debug application on every database
transaction ([`src/seon/web/debug.cljs:1018`](../../../../src/seon/web/debug.cljs)).
The good news is that debug already pays nothing when its feed is closed.

### Each transcript frame overreads growing history

The transcript first queries all messages, walks all turns and all evals, unions
and sorts the complete event history, and only then bounds the HTML window
([`src/seon/agent/ctx/transcript.cljs:466`](../../../../src/seon/agent/ctx/transcript.cljs),
[`src/seon/agent/ctx/transcript.cljs:508`](../../../../src/seon/agent/ctx/transcript.cljs),
[`src/seon/agent/ctx/transcript.cljs:611`](../../../../src/seon/agent/ctx/transcript.cljs),
[`src/seon/agent/ctx/transcript.cljs:850`](../../../../src/seon/agent/ctx/transcript.cljs)).
Sequential eval commits therefore combine a linear number of frames with a
growing amount of work per frame—approximately quadratic projection work for a
single long reply.

### Gzip and browser morphing happen too late to help

The Datastar client parses each complete `datastar-patch-elements` event into a
new document fragment and morphs the selected element
([`reference-code/datastar/library/src/plugins/watchers/patchElements.ts:40`](../../../../reference-code/datastar/library/src/plugins/watchers/patchElements.ts)).
Gzip reduces bytes on the wire. It does not avoid server-side DB queries, SCI,
Hiccup construction, HTML serialization, browser parsing, or DOM comparison.
Client backpressure also applies only after the server has already rendered the
event. The correct optimization point is before render generation.

## Required invariants

Any fix must preserve all of the following:

1. Every complete form is attempted in source order.
2. Every actually attempted form gets its own success/error fact.
3. Ordinary errors do not prevent later forms from running.
4. The program-graph tee stays atomic with the eval whose source produced it.
5. No `result/<id>` binding exists before that ID durably commits.
6. A process crash creates no outcomes for forms that were never attempted.
7. Database writes performed inside a form are immediately committed and are
   visible to later forms in the same reply.
8. Canvas/domain writes and browser actions remain promptly reactive.
9. Debug work remains absent while debug is closed.
10. The database listener remains the one refresh bus and the current Datastar
    feed remains the one rendering path.

## Option comparison

| Option | What it saves | Correctness / architecture result |
|---|---|---|
| Buffer all eval rows and commit once per reply | Render frames, writer commits, durable objects | Reject. A mid-batch crash loses already-attempted outcomes; result IDs/handles and declaration visibility must be redesigned; one transaction collapses per-form transaction identity and durability. |
| Apply transactions to an immutable DB with `datahike.api/with`, then diff/commit | Might help inspect a hypothetical batch | Reject as an execution path. `with` creates one speculative transaction report and advances transaction state once; it cannot contain arbitrary external effects or authoritative writer concurrency. It is useful for simulation, not durable form execution. |
| Increase debounce/throttle | Some render frames | Reject as primary fix. The 500 ms-spaced commits defeat a short debounce; a long arbitrary interval delays canvas/browser feedback and either starves or periodically repeats expensive work without a semantic completion boundary. |
| Suppress every transaction while an agent runs | Many frames | Reject. Domain/canvas writes inside forms and concurrent browser actions are deliberate live changes. Agent state/provenance is not dependency routing. |
| Attribute/entity dependency routing only | Cross-agent and unrelated-unit renders | Necessary second step, but an owning transcript genuinely changes on every eval row, so routing alone does not remove the 117 renders. |
| Memoize render functions | Repeated stable subtrees | Secondary only. It does not reduce listener/render count or writer commits. Unbounded `memoize` leaks; a database value is a poor cache key; stable-unit caches need explicit bounds and changed-read invalidation. |
| Defer running eval-record **presentation** in the existing coalescer | Nearly all pathological render frames while preserving every commit | **Recommended first patch.** It uses existing transaction datoms, pending-change state, and terminal turn fact. No second mechanism. |

Datahike source confirms why a speculative or single final transaction is not
equivalent. `with` processes one sequential transaction data collection into one
report and advances `max-tx` once
([`reference-code/datahike/src/datahike/core.cljc:126`](../../../../reference-code/datahike/src/datahike/core.cljc),
[`reference-code/datahike/src/datahike/db/transaction.cljc:1125`](../../../../reference-code/datahike/src/datahike/db/transaction.cljc)).
Each real commit flushes pending immutable index objects, derives/stores commit
state, and moves the branch head
([`reference-code/datahike/src/datahike/writing.cljc:391`](../../../../reference-code/datahike/src/datahike/writing.cljc)).

## Recommended design

### 1. Give the existing coalescer a semantic hold, not another queue

Derive `running-eval-record?` from the existing change map:

- effective added datoms contain `:seon.eval/id`;
- those eval entity IDs are linked by `:seon.agent.turn/evals` to their owning
  turns; and
- each owning turn is still `:running` in the supplied post-commit DB.

Presence of the eval identity is the structural signature of
`record-eval!`'s transaction. Do **not** maintain an allowlist of eval attrs:
the same transaction can legitimately contain allocator facts, lazy schema
installation, and the accepted program-graph tee. Do **not** stamp new process
metadata merely to recognize work the datoms already prove.

Change `schedule-broadcast!` within its current lifecycle:

1. Always merge the report into `::pending-change` exactly as today.
2. If it is a running eval-record commit and no non-eval timer already owns the
   pending batch, retain the merged change without starting a timer.
3. If a timer already exists, do not cancel or postpone it; a prior deliberate
   transaction already earned a frame.
4. Any non-eval transaction schedules the existing bounded timer with the merged
   pending change.
5. The turn-close transaction is non-eval, so it naturally flushes all results
   once. A crash-recovery terminalization does the same.

This deliberately permits an unrelated deliberate transaction to flush partial
eval progress. That is safe: the view is always derived from the latest committed
DB, and flushing is preferable to delaying a canvas or browser action. The goal
is not an atomic UI fiction; it is to stop creating a frame for bookkeeping alone.

There is no lost-notification problem. Reconnect performs a full current render,
and any later non-eval transaction renders from latest state. If the process
crashes, its process-local pending change disappears but all eval facts remain;
the restarted/reconnected view and recovery transaction derive them normally.
Do not add a durable pending flag or acknowledgement row.

### 2. Complete the one intended dependency mechanism

The architecture already specifies normalized observed reads, an
attribute→read→unit index, evaluation on `db-before` and `db-after`, and rendering
only when the read result differs
([`docs/seon/architecture/ui.md:394`](../../../seon/architecture/ui.md)). Complete
that mechanism in place instead of adding provenance routing or another cache.

The first useful scopes are:

- agent/turn/eval/message dependencies include the owning agent/entity IDs, so
  agent A's eval does not render agent B's transcript;
- debug's exact AI projection is a proper unit with observed dependencies rather
  than an unconditional per-commit full application render;
- identical serialized unit output suppresses the morph;
- the debug shell remains lazy and HTML twins remain activated only while open.

Transaction user/process should continue to affect focus semantics only. It
must not become an invalidation shortcut; root/config/REPL provenance can touch
many unrelated entities.

### 3. Bound the work in the one final frame

Replace the full-history-then-tail transcript path with the roadmap's one bounded
recent reader per fact owner: recent messages from `seon.agent.message`, recent
evals from `seon.eval`, using reverse index seeks/cursor windows exposed through
`seon.db`. Merge those two bounded streams for the timeline. Do not persist a
recent-list projection
([`docs/prds/runtime-reliability/roadmap.md:686`](../roadmap.md)).

Only after semantic invalidation and bounded reads should a measured hot pure
renderer receive a bounded stable-unit output cache. Key it by normalized read
results/unit parameters—not by a database connection/value—and give it an
explicit size policy. This cache then composes across equivalent subscriptions
without retaining an unbounded database lineage.

The 117 durable commits remain after this UI fix. Measure writer object/file and
byte deltas around an isolated N-eval run before changing that contract. If the
commit layer is still excessive, optimize Datahike/Konserve node flushing,
commit-graph policy, or existing multi-assoc behavior in the maintained source.
Do not buy storage savings by weakening eval truth.

## Mechanical proof plan

Use behavioral assertions and counters, never exact response prose.

1. Open one agent feed and its debug feed. Run a reply containing 100 malformed
   complete forms followed by one valid form. Assert 101 ordered eval facts, 100
   errors, the valid form attempted, and one terminal turn. Assert eval-record
   commits do not each invoke the expensive render transitions; the terminal
   transaction produces the final transcript/debug frame.
2. Put a domain/canvas transaction in the middle of a multi-form reply. Assert
   its datoms are visible to the next form and its affected live unit morphs
   before turn close. The later final frame must contain every eval result.
3. Kill the evaluator after N committed entries. Assert exactly N eval facts and
   no fabricated tail. Recovery terminalizes the turn; a reconnect/full paint
   and the recovery transaction both show the committed prefix.
4. While eval presentation is held, submit a browser action and an unrelated
   agent transaction. Assert they are not delayed and no pending change evidence
   is lost.
5. With feeds for two agents open, transact an eval for one agent. After scoped
   read invalidation lands, assert the other agent's transcript renderer is not
   invoked.
6. Repeat on a grown database and record render invocations, render latency,
   emitted token estimate, event count, CPU, RSS, GC movement, transaction count,
   durable object/file delta, and bytes. Gzip bytes alone are not an acceptance
   measure.

## Top three implementation recommendations

1. **First:** add the datom-derived running-eval semantic hold to the one existing
   Datastar coalescer; flush on any non-eval transaction, especially the existing
   terminal turn transaction.
2. **Next:** finish normalized observed-read/result-change invalidation with
   entity/agent scope, and make debug a dependency-routed lazy unit instead of an
   unconditional per-commit full render.
3. **Then:** replace transcript full-history scans with the single bounded recent
   eval/message paths, re-profile, and optimize the maintained commit/storage
   layer only if isolated measurements still justify it. Do not buffer durable
   eval facts or start with general memoization.
