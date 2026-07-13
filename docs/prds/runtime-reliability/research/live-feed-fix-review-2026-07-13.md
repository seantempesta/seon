---
type: research
status: completed
tags: [research, web, agent, flow]
---

# Live Datastar feed fix review — 2026-07-13

## TL;DR

The successful `/call` is not the problem. The transaction commits, but the
normal agent feed classifies it as irrelevant and emits no agent-view event.
The live log shows the exact split: after `/call OK`, the
`:my.demo.task/*` transaction rerenders `/agents`, while the three equivalent
root feeds emit nothing.

There are two compounding causes:

- equivalent sockets share a render opportunistically, but the dependency
  state belongs to each socket and `broadcast!` uses the first socket's private
  closure; and
- the dependency set is a static literal-keyword projection from the top-level
  renderer. It deliberately misses reads performed by helper functions or
  dynamically constructed queries, so it is not a sound invalidation oracle
  for arbitrary agent-authored UIs.

Fixing only “pick a newer socket” would move the bug around. The smallest
honest design is one normalized subscription with many transport consumers,
runtime-captured `seon.db` read observations owned by that subscription, and a
current full-view resync as the correctness fallback. Per-surface observations
then refine that same mechanism so ordinary changes remain small patches. No
second listener, event bus, dirty flag, or persisted subscription data is
needed.

The same patch should also make slow consumers resync from current state and
put a maximum wait on transaction coalescing. Current “latest raw event wins”
and unbounded trailing debounce can each leave a browser stale even after
dependency routing is corrected.

## Live evidence

No store mutation or restart was performed for this review.

The relevant sequence in `logs/pod.log` is:

1. At `10:11:52.701Z`, `/call OK` records
   `my.agent.root/add-task!` for root.
2. At `10:11:52.821Z`, the committed transaction contains
   `:my.demo.task/agent`, `:my.demo.task/created-at`,
   `:my.demo.task/done`, `:my.demo.task/id`, and
   `:my.demo.task/title`.
3. The roster subscription emits one target for that transaction.
4. No `[:seon.web.feed/agent "root"]` broadcast is logged for the same
   transaction, despite three root connections being present in adjacent log
   entries.
5. The same pattern repeats after later task writes, including
   `10:13:17.481Z`.

`broadcast!` logs only when its renderer returns elements. The absence of a
root line therefore occurs before gzip, network delivery, Datastar parsing, or
idiomorph: the server dependency gate returned an empty patch.

The earlier canvas transition is also visible. At `09:56:55.466Z`, a
`:seon.render.canvas/content` change produces a three-target root patch, but
the connection-local dependency cache is refreshed only by the structural
branch. A canvas renderer replacement changes the read plan even when surface
membership does not change.

## Current path and exact defects

### 1. Socket state is incorrectly acting as subscription authority

`src/seon/web/datastar.cljs:207-215` stores a complete descriptor per ephemeral
view/socket in `!feeds`. Each live agent feed constructs its own
`!dependencies` atom at `src/seon/web/datastar.cljs:1048-1074`.

`broadcast!` groups descriptors by feed key and active fingerprint, then calls
the `render-change` closure from `(first conns)` at
`src/seon/web/datastar.cljs:406-434`. Consequently:

- open order selects dependency authority;
- closing that socket silently promotes a different private cache;
- a newer tab can have correct dependencies but never be consulted; and
- reconnect fencing protects view ownership, but not shared render state.

The current test at `test/seon/web/datastar_test.cljs:576-599` gives both
connections the exact same closure, so it cannot expose this defect.

### 2. Static renderer attributes are not complete runtime dependencies

`seon.ui.agent-view/agent-view-dependencies` derives surface attributes from
stored `:seon.fn/read-attrs` at
`src/seon/ui/agent_view.cljs:398-428`. The underlying contract explicitly says
that dynamically reached attributes are not watched:
`src/seon/agent/ctx/render_fns.cljs:186-207`.

That includes an ordinary compositional UI whose top-level tile calls a helper
that performs `db/query`. The helper's query result changes, but the top-level
renderer's literal set need not contain the domain attribute. This explains
why a newly opened page can paint the latest database state yet miss the next
write.

Seon already has the correct primitive:

- `db/capture-reads` records actual synchronous query/pull/entity results as
  immutable normalized observations at `src/seon/db.cljs:459-483`; and
- `db/read-observation-changed?` safely replays them against a later database
  value at `src/seon/db.cljs:1383-1420`.

Those functions see transitive helper calls because capture occurs at the
`seon.db` boundary, not by scanning renderer source. They are implemented and
tested but are not connected to the normal web feed.

### 3. Dependency-plan changes do not consistently rebuild the cache

The feed refreshes `!dependencies` only when changed attributes intersect
`::structural-attrs` at `src/seon/web/datastar.cljs:1056-1074`.

`src/seon/ui/agent_view.cljs:365-379` omits at least these dependency-plan
inputs:

- `:seon.render.canvas/content`, which selects a different renderer; and
- `:seon.fn/read-attrs`, which changes the declared read plan.

Adding those keywords to the structural set is a useful safety correction, but
it is not a replacement for runtime observations. A source list cannot become
a complete description of arbitrary transitive runtime reads.

### 4. Latest serialized event is not a valid partial-patch merge

`push-event!` stores only the newest raw SSE string while a gzip stream is
draining at `src/seon/web/datastar.cljs:366-399`.

Datastar applies every top-level child by looking up that child's existing DOM
id, and skips missing targets:
`reference-code/datastar/library/src/plugins/watchers/patchElements.ts:125-145`.
It does not reconcile omitted siblings. Therefore a later unit patch cannot
replace a pending full `#app-view` membership patch. Dropping the full patch can
make all later unit patches target elements the browser never received.

The test at `test/seon/web/datastar_test.cljs:636-659` proves only string
replacement (`"latest"` wins). It does not prove that the resulting DOM equals
any database state.

### 5. The transaction debounce has no liveness bound

`schedule-broadcast!` clears and restarts one trailing timer after every
transaction at `src/seon/web/datastar.cljs:441-465`. Once a structural change
enters the pending batch, continuous writes can perpetually restart the longer
delay.

The listener already supplies both immutable endpoints and decoded datoms:
`src/seon/db.cljs:1426-1441`. The batch should retain earliest
`:seon.db/db-before`, latest `:seon.db/db`, and the union of datoms/attributes,
then flush no later than one explicit maximum wait.

## Minimal patch design

The following is the smallest design that fixes the reported failure without
creating a compatibility path.

### A. Make one shared subscription the render authority

Keep `!feeds` as the one ephemeral registry, but separate data inside each
entry:

- the **subscription** owns the normalized key, renderer/plan state, captured
  read observations, and any current output cache; and
- the **consumer** owns only view id, feed id, gzip/response handles,
  backpressure state, and open time.

Equivalent entries reference the same immutable subscription descriptor plus
its small mutable observation cell. Do not add a second global registry.

Add colocated private schemas and helpers in `seon.web.datastar` near
`src/seon/web/datastar.cljs:113-131` and `:207-215`:

- `::subscription-key` — `[feed-key active-fingerprint]` for current routes;
- `::subscription` — the render functions plus one observation/plan cell;
- `subscription-key` — pure normalization;
- `matching-subscription` — finds the existing shared descriptor in the one
  registry; and
- `attach-subscription` — reuses that descriptor or creates it once.

Update these functions:

- `prepare-feed` (`:690-710`) attaches a shared subscription instead of
  retaining per-socket render closures.
- `broadcast!` (`:406-434`) groups by `::subscription-key`, invokes the
  subscription once, and fans one event to every current consumer.
- `push-full!` (`:401-404`) invokes the subscription's authoritative current
  full renderer.
- `handle-view-unit!` (`:827-880`) and `reconcile-view-catalog!` (`:882-899`)
  re-key the view when active membership changes; they must either attach the
  already-existing matching subscription or derive a new descriptor from the
  same renderer definition.

The first socket is then only one consumer. Closing it cannot change which
dependency plan is used because every survivor holds the same subscription
object.

### B. Capture actual reads at the subscription render boundary

At subscription construction and after each dirty render, run the renderer
inside:

```clojure
(db/capture-reads
  {:seon.db/db dbv
   :seon.db/thunk #(render-current dbv)})
```

Store only `:seon.db/read-observations` in the ephemeral subscription. Never
store a database value, Datahike entity, dirty flag, or observation datom.

On a coalesced transaction, replay the observations against the batch's latest
`:seon.db/db`. If none changed, emit nothing. If an observation changed,
rerender from that latest immutable value and replace the observation set from
the same render.

For the first correctness patch, the dirty result may be a complete
`#app-view` morph. That is the necessary fallback because a changed conditional
read can alter membership or introduce a new read branch. It is still
pay-for-use with respect to unrelated transactions: only a changed observed
result renders.

The immediate follow-through is to store observations per stable surface
coordinate, using the existing `selection`/DOM ids in
`src/seon/ui/agent_view.cljs:646-695`. Then:

- shell/catalog observations changing produce a full `#app-view` morph;
- one surface's observations changing rerender only its primary and rail
  elements;
- header observations changing rerender only the header; and
- every rerender atomically replaces that unit's observations, so conditional
  branches self-correct.

This is not a second mechanism. It refines the subscription's one observation
set into a map keyed by the stable units already used for patches.

Keep `:seon.fn/read-attrs` only as a cheap candidate hint until runtime capture
fully owns every agent-view unit. It must never suppress a changed runtime
observation. Add `:seon.render.canvas/content` and `:seon.fn/read-attrs` to the
plan-change set during the cutover so renderer replacement immediately
recaptures.

### C. Make backpressure request a current full resync

Replace `:seon.web.feed/pending-event` with a logical
`:seon.web.feed/pending-resync?` cell.

- If a write succeeds, nothing changes.
- If the gzip stream is draining and any later update arrives, set resync true;
  do not retain a raw partial event.
- On `drain`, if resync is true, render one current complete view through the
  subscription and write it.

This is bounded to one pending operation and is correct for every mixture of
membership and unit changes. Slow consumers pay for one full catch-up render;
healthy consumers continue receiving shared small patches.

This requires `render-full` to mean **current authoritative complete view**.
Agent, roster, and frozen-as-of definitions already satisfy that. Debug does
not: `src/seon/web/debug.cljs:1018-1042` captures `initial` forever. Extract one
`render-current` closure there that recomputes the projection/catalog and use it
for both full resync and change rendering.

### D. Give batching a maximum wait

Extract `flush-pending!` from `schedule-broadcast!` and use two timer roles:

- a trailing near-frame/structural coalescer; and
- a maximum-wait timer installed only for the first transaction in a batch.

Whichever fires first atomically takes the pending batch and clears both timer
handles. Subsequent transactions may move the trailing timer but never the
maximum deadline.

`merge-change` should retain:

- earliest `:seon.db/db-before`;
- latest `:seon.db/db`;
- concatenated effective `:seon.db/datoms` or a merged attr index;
- unioned changed attributes; and
- structural/plan-change dominance.

This shape is also exactly what before/after observation comparison and future
route invalidation need.

### E. Make consumer cleanup once-only on every terminal path

At `open-feed!` (`src/seon/web/datastar.cljs:733-770`), create one cleanup
closure guarded by a per-consumer boolean. Attach it to request close, response
close/error, and gzip close/error. Reconnect's feed-id compare remains the
ownership fence.

Validate the agent before `ensure-installed!` in `open-agent-feed!`
(`:1026-1032`) so an invalid feed cannot install a listener with no registered
consumer capable of releasing it.

## Invariants the patch must enforce

1. One normalized live subscription owns one render plan regardless of tab
   count.
2. Every equivalent open consumer receives the same event derived from the
   same immutable database coordinate.
3. Opening, closing, reconnecting, or hiding a consumer cannot replace or
   regress subscription dependency state.
4. A renderer helper's query is a dependency even when the top-level renderer
   source contains no domain attribute literal.
5. After every dirty render, observations describe exactly the branch that was
   rendered.
6. A partial patch is sent only when all of its target ids are known to exist;
   uncertainty or missed transport work becomes a current full resync.
7. Pending work is bounded per consumer and a busy transaction stream cannot
   postpone broadcast past the configured maximum wait.
8. A frozen subscription never observes or renders current transactions.
9. The last consumer releases both its subscription state and the shared
   database listener; stale cleanup cannot release a replacement.

## Behavioral tests

Add behavioral tests to `test/seon/web/datastar_test.cljs`; do not assert UI
copy or serialized context wording.

### Shared subscription and disconnect order

- Open two equivalent agent feeds with distinct view/feed ids.
- Assert subscription construction and change rendering each occur once.
- Broadcast one database change and assert both consumers receive the same
  target ids.
- Close the first opener, broadcast again, and assert the survivor updates.
- Repeat with the opposite close order.
- Reconnect one view id, fire the stale request's close event, and prove the
  replacement plus the other equivalent view remain attached.

### Transitive and dynamic reads

- Render a tile whose top-level function calls a helper; only the helper runs a
  query.
- Capture the render, transact a matching fact, and prove the subscription
  marks the observation dirty and emits an update.
- Transact an unrelated attribute and prove no renderer runs.
- Exercise a conditional query branch, change the branch selector, rerender,
  then change an attribute read only by the new branch and prove it updates.

### Renderer-plan replacement

- Start with canvas renderer A and open two equivalent consumers.
- Change `:seon.render.canvas/content` to renderer B, whose actual query reads a
  different attribute.
- Change B's attribute and prove both already-open consumers update without a
  reload.
- Open a third consumer after the replacement and prove it joins the same
  current subscription rather than installing a competing dependency cache.

### Backpressure dominance

- Force the first gzip write to return false.
- While draining, deliver a full membership change followed by one or more
  unit changes.
- Advance the renderer's current state, fire `drain`, and prove the only
  pending write is a full view containing the latest membership and values.
- Prove pending memory remains one logical resync, not an event queue.

### Bounded coalescing

- Drive arrivals more frequently than both trailing delays.
- With an injected clock/timer seam, prove a flush occurs no later than the
  maximum deadline from the first arrival.
- Prove the flushed batch carries the first `db-before`, last `db`, and union of
  changed attributes/datoms.
- Prove the racing trailing/max callbacks can consume the batch only once.

### Wire and browser acceptance

- Use a Node gunzip client to keep two `/agent/root/feed` streams open.
- Transact through `/agent/root/call` and assert both streams receive the same
  canvas target (or full fallback) without reconnecting.
- Close the earlier stream and repeat on the survivor.
- In the real browser, submit a form and observe the rendered database result
  change in place. Do not use the browser bridge to judge the long-lived SSE
  transport; it does not proxy that stream reliably.

## Recommended implementation order

1. Add the shared subscription descriptor and once-only consumer cleanup.
2. Connect runtime read capture with a full current-view correctness fallback.
3. Replace raw pending-event overwrite with current full resync.
4. Add the bounded batch window and retain both immutable endpoints.
5. Prove the multi-feed/transitive-read/backpressure tests and live gunzip
   acceptance.
6. Partition the same observations by stable agent-view surface so correct
   updates remain small under grown-store load.

Do not ship a “newest tab wins,” “always include `:my.demo.*`,” or broader
keyword-regex patch. Those approaches preserve connection-local authority or
hardcode one demo's data and will fail again for the next composed agent UI.
