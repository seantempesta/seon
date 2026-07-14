---
type: research
status: completed
tags: [research, web, database, flow]
---

# Reactive UI dependency routing — source audit and target design (2026-07-12)

## TL;DR

The live channel is built on the right primitives, but the dependency graph is
not yet one mechanism. The main agent feed has attribute-level filtering, the
roster renders the whole fleet on every transaction, debug and `/data` have a
second SSE registry and provenance-based fan-out, `/sse` is an unused third
registry, and route datoms do not actually trigger the router rebuild their
documentation promises. These paths explain both stale views and wasted work.

The target is one runtime-compiled graph:

```text
database route/context/program facts
  -> reitit route match + normalized parameters
  -> derived view plan
  -> stable render units
  -> runtime-captured database read observations
  -> reverse index from changed attributes to candidate reads

transaction report
  -> compare each candidate read over db-before and db-after
  -> dirty only units whose read result changed
  -> render each dirty unit once
  -> emit complete ID-addressed Datastar elements
  -> fan the shared event to subscribed gzip streams
```

No dependency, subscription, dirty, render-hash, or “last seen” entities belong
in Datahike. They are projections of open sockets, code, route parameters, and
the current database. Transaction user/process metadata remains useful for
provenance and deliberate-focus semantics, but it must not decide which views
can have changed.

The source supports this design directly:

- Reitit resolves data, checks conflicts, compiles it once, validates it, and
  selects an optimized matcher. Its lesson is the compilation boundary, not
  that URL tries should be repurposed for attribute matching.
- Datahike already extracts conservative query attribute dependencies, uses
  `:all` for unknown reads, and invalidates its query-result cache by changed
  attributes. The UI should reuse or expose that parser rather than implement a
  second Datalog parser.
- Datastar officially accepts several complete top-level elements in one patch
  event and targets each by id. The existing complete-element morph protocol is
  correct and should stay.
- Hyperlith supplies latest-wins buffering and CPU-pool isolation, but it sends
  every refresh to every view and renders per connection. It is not a
  dependency-routing implementation.

The immediate scaling fix is to stop invoking renderers when their database
reads did not change. Do not raise the SCI budget. The grown-store observation
of repeated SCI interrupts and 1.4–2.5 GB RSS sawtoothing is consistent with
avoidable whole-view/whole-roster rendering plus intrinsically broad render
queries. After invalidation is exact, the remaining legitimate render paths
must be bounded before they allocate.

## Scope and method

This audit is read-only except for this document. It covers:

- `src/seon/web/datastar.cljs`, `debug.cljs`, `router.cljs`, and `serve.cljs`;
- `src/seon/ui/agent_view.cljs`, `header.cljs`, and the context render path;
- `src/seon/db.cljs`, `db/internal.cljs`, and `store/wire.cljs`;
- current structural tests under `test/seon/web` and `test/seon/ui`;
- vendored Reitit, Datastar, Datastar Clojure, Hyperlith, and Datahike source;
- the grown-store feed evidence in
  `docs/prds/agent-ctx/coordination.md:2429-2501`.

It does not propose persisted authorization data, edit context wording, or
implement the refactor.

## Current graph

### Transaction delivery

Datahike supplies a sound base event. `seon.db/listen!` wraps a native listener
and gives each consumer `db-before`, `db`, decoded datoms, and one attribute
index (`src/seon/db/internal.cljs:1515-1571`). Foreign writer transactions enter
the same native listener collection through the wire adapter. The adapter
orders feed events, deduplicates them by basis transaction, and schedules each
listener on its own Node macrotask so one listener does not stop the replica feed
(`src/seon/store/wire.cljs:428-540`).

That scheduling is isolation from the feed pump, not CPU isolation. Every
callback and renderer still executes on the pod's one Node thread.

### Main Datastar feed

`seon.web.datastar` owns a vector with one descriptor per socket. Broadcast
groups live descriptors by `:seon.web.feed/key`, invokes the first descriptor's
change renderer once, and writes the same event string to every equivalent gzip
stream (`src/seon/web/datastar.cljs:51-59,245-275`). Each stream has a
latest-event slot while backpressured, so it never builds an unbounded queue
(`src/seon/web/datastar.cljs:208-243`). Frozen `as-of` feeds are excluded from
current broadcasts.

The transaction coalescer loses most of the useful report. It retains the latest
database, unions changed attributes, and sets one structural flag. Entity ids,
added/retracted datoms, the earliest `db-before`, and transaction boundaries are
discarded (`src/seon/web/datastar.cljs:277-306`). Every batch still visits every
unique live view key.

Agent feeds cache three hard-coded attribute sets per connection:

- surface attributes, assembled from stored renderer read attributes;
- structural attributes, a fixed program/context set;
- header attributes, another fixed set.

The first matching class renders surfaces, the whole shell, or the header
(`src/seon/web/datastar.cljs:648-705` and
`src/seon/ui/agent_view.cljs:213-283,378-409`). The roster has no dependency
gate: every transaction returns a complete `roster-view`
(`src/seon/web/datastar.cljs:721-737`). A roster render enumerates every agent,
renders the system header, and invokes every non-root agent's canvas for its
preview (`src/seon/web/datastar.cljs:97-183`).

### Debug and data browser

`seon.web.debug` is a separate reactive system:

- a second socket registry, grouped by agent id or the pseudo-agent `::data`;
- uncompressed, separately framed SSE;
- a separate database listener;
- a per-agent 100 ms timer;
- a full snapshot of the debug header, AI pane, HTML pane, and context bar;
- origin/agent provenance as the fan-out rule.

A transaction with no agent id or origin `:core-seed`/`:config` targets every
debug viewer; otherwise it targets the stamping agent. `/data` targets every
commit (`src/seon/web/debug.cljs:915-1117`). This is precisely the provenance
substitution the new model must remove. An agent can read another agent's facts,
a shared fact, or a global config fact; authorship does not describe the query's
dependency.

`seon.web.serve` still owns a third `!sse-connections` registry and `/sse`
handler (`src/seon/web/serve.cljs:60-78,188-213`). No current broadcaster writes
to it. The static router still installs the route
(`src/seon/web/router.cljs:229-266`). It is dead connection machinery.

### Router

Route entities are already database facts. `db->routes` pulls them, groups them
by path, and compiles a Reitit Ring handler. `rebuild!` caches the result
(`src/seon/web/router.cljs:205-227,317-347`). The documentation says a route
transaction listener will call `rebuild!`, but no such listener exists. Route
changes after boot/hot install therefore need not reach the cached router.

The static supplement still owns `/sse`, debug, data, and most POST doors. This
is a migration gap, not a reason to introduce another router.

## Concrete correctness bugs

### Equivalent tabs can inherit a stale dependency cache

Dependency state belongs to each connection closure, but broadcast renders with
only the first connection in a view-key group. Only that first closure refreshes
its dependency atom after a structural change. The other equivalent connections
receive the morph but retain their old dependency sets. If the first connection
closes, a surviving tab becomes the group's renderer with stale dependencies
(`src/seon/web/datastar.cljs:250-264,678-705`).

The fix is one subscription state per unique view key with connections attached
to it. Dependencies, plan, output cache, and basis belong to that subscription,
never to an arbitrary socket.

### Renderer redefinition can miss the renderer's next dependency

`:seon.fn/source` and `:seon.fn/read-attrs` are included in surface attributes,
not structural attributes. A renderer redefinition can therefore render once
without rebuilding the cached dependency projection. If the new definition
reads a new attribute, the following transaction on that attribute can be
missed (`src/seon/ui/agent_view.cljs:220-283`).

### Current-namespace changes can change the surface set without a shell morph

Auto-run surface membership derives from the current namespace and program
graph. Latest eval namespace/status/time, agent-run/turn relationships, and
namespace membership can change that set, but those inputs are absent from the
fixed structural set. A namespace switch can leave the old shell or dependency
projection live.

### Structural attributes cause cross-agent full renders

The structural test is attribute-only. A context or function definition change
for one agent marks every open agent view structural because entity and query
scope were discarded. Every affected feed can rebuild `#app-view`, including
all of its SCI-rendered surfaces.

### Attribute-only routing still over-invalidates agent-scoped data

Two transcript queries may both read `:seon.agent.message/content`, while only
one agent's result changes. Attribute intersection invalidates both. Provenance
filtering would be equally incorrect for shared/global reads. Query-result
comparison is the non-hacky scope check.

### The derived canvas default can be moved by another agent

`renderer-touch` correctly joins history to the agent transaction metadata, but
`last-updated-tile` computes its attribute maxima over all history without the
agent join (`src/seon/agent/ctx/render_fns.cljs:266-303,305-391`). Another agent
touching a shared watched attribute can make this agent's candidate tile look
newer. Provenance is semantically appropriate here because the question is
“what did this agent deliberately update,” not “which UI can have changed.”

### Header dependencies do not match header reads

The header's fixed attribute set omits inputs used by fleet state, error storms,
brand, and the exact datom count. It intentionally samples inventory only on
selected changes, so the displayed exact count can be stale. It also calls
`store-inventory`, which scans all live datoms and reconstructs provenance
groups just to display a count (`src/seon/ui/header.cljs:150-164,226-264` and
`src/seon/db.cljs:1260-1289,1383-1471`).

`throughput` reads `Date.now` while claiming to be pure of the database. Its
rolling rate changes as time passes without a transaction, and does not update
when the fixed attribute gate skips a transaction (`src/seon/ui/header.cljs:71-100`).

### Structural trailing debounce can starve

Every arriving transaction clears and restarts the one timer. A continuous
stream of structural writes can postpone the render forever. Latest-wins must
also have a maximum wait or single scheduled flush.

### Route data is not reactive

The cached router is rebuilt on install/start, not by the promised transaction
listener. A persisted route change can be valid database state while the HTTP
front door serves the previous compiled graph.

## Why the stored literal read-set is not a sufficient dependency graph

The program tee walks qualified keyword literals in a function's parameter/body
forms and stores them in `:seon.fn/read-attrs`
(`src/seon/eval.cljs:2425-2492,2663-2693`). Render invalidation intersects that
set with installed attributes.

This approximation has both false negatives and false positives:

- helper functions' and transitive calls' reads are absent;
- wildcard pulls are invisible as a dependency on attributes added later;
- dynamic attributes and Datalog rules can be absent;
- a qualified keyword can be a write key, option, output key, or constant rather
  than a read;
- direct `datahike.api` calls bypass the `seon.db` boundary;
- it describes literal syntax, not the entity/ref/query scope of a read;
- conditional branches record keywords that did not execute, while an executed
  helper branch can remain invisible.

It also violates the desired fact/projection boundary. “This source contains
these keyword literals” is derivable from source; “this renderer depends on
these database results for this invocation” is runtime observation. Neither is
a durable domain fact.

Do not delete `:seon.fn/read-attrs` in the first patch: canvas recency and the
current feed depend on it. Replace those consumers, prove runtime capture and
restart recency, then delete the stored attribute, tee code, regex fallback,
tests, and documentation together.

## Source-grounded target design

### Reitit: compile facts into a runtime index

Reitit's `router` resolves raw route data, detects path/name conflicts, compiles
the routes, validates them, and selects a lookup, trie, mixed, or quarantine
matcher (`reference-code/reitit/modules/reitit-core/src/reitit/core.cljc:331-380`).
Route compilation is a separate pass
(`reference-code/reitit/modules/reitit-core/src/reitit/impl.cljc:177-184`), and
Ring compilation turns method data and middleware into endpoints
(`reference-code/reitit/modules/reitit-ring/src/reitit/ring.cljc:58-83`).

Use the same pattern for live views:

1. Query authored route/context/program facts.
2. Validate and compile one normalized view plan.
3. Index its runtime read observations for cheap transaction matching.
4. Recompile only when the plan's own database result changes.

Do not use Reitit's path trie to match attributes. A map from attribute to read
ids is simpler and correct. Reitit owns path matching and route hierarchy; the
database read compiler owns invalidation.

A live view key should derive from the Reitit match, not ad hoc route code:

```clojure
[:seon.web.view/key
 {:seon.route/name       :seon.route/agent-feed
  :seon.route/path-params {:id "..."}
  :seon.route/query-params {}
  :seon.db/as-of          :live}]
```

The example is runtime data, not a stored entity.

### Datahike: reuse conservative query dependencies

The vendored Datahike fork already contains the relevant algorithm:

- query results are cached per immutable database snapshot;
- where/pull attribute dependencies are extracted;
- variable attributes, wildcard pulls, rules, and unknown forms become `:all`;
- a child snapshot structurally shares parent entries except those whose
  dependencies overlap modified attributes;
- result-cache retention is bounded by snapshot count and tuple weight.

See `reference-code/datahike/src/datahike/query.cljc:2340-2582,4002-4033`.
This is strong evidence for changed-attribute candidate routing, conservative
unknowns, and result equality. It also means Seon must not grow a second partial
Datalog parser. Expose the dependency projection through an appropriate
Datahike/`seon.db` API or share the existing implementation.

Do not assume cross-snapshot propagation currently benefits the pod's wire-fed
read replica. The cache is process-local and the writer transaction occurs in
the JVM. Benchmark the actual CLJS path. The correctness algorithm remains
valid if the old read is cached and the new read executes normally.

### Runtime read observation

Add a synchronous, runtime-only observer at the sole `seon.db` read boundary.
When no render observer is bound, reads retain their current fast path. While a
render unit runs, record normalized operations such as:

```clojure
{:seon.db.read/op      :seon.db.read/query
 :seon.db.read/request {:seon.db/query <form>
                        :seon.db/args  <non-db inputs>}
 :seon.db.read/attrs   #{:domain/id :domain/value}}
```

Every map key and public API remains fully namespaced. This map is ephemeral;
it is not transacted.

Capture actual calls, not function source. This sees reads in helper functions,
conditional branches, and SCI calls into compiled `seon.db` functions. Unknown
query shape, wildcard pull, temporal/lazy read, or dynamic attribute becomes a
broad observation. Broad means “compare this read on each batch,” not “invoke
the renderer on each batch.”

Direct `datahike.api` reads in render paths must move behind `seon.db`.
Known violations include `src/seon/render.cljs`, `handlers/fn.cljs`,
`handlers/ns.cljs`, and `web/debug.cljs`. Low-level database/wire internals are
the legitimate exception.

`entity-lazy` is not honestly observable at call time because attribute lookup
happens later. Core render paths using it, including transcript/context walks,
should become explicit bounded queries or pulls. Until then those units need an
explicit conservative read observation. Do not claim generic capture covers a
lazy entity.

### Exact change decision

For a coalesced batch, retain the earliest `db-before` and latest `db-after`.
For each candidate normalized read, execute it once against each snapshot and
compare results with Clojure equality:

```clojure
(not= (read db-before request)
      (read db-after request))
```

This is preferable to storing a new result hash:

- there is no collision;
- no large per-view result is retained;
- retractions are handled naturally;
- a change that reverts inside the batch produces no render;
- Datahike can reuse its existing query/plan/result caches;
- one normalized read shared by several units/views is evaluated once per
  snapshot.

Attribute dependencies are only the candidate gate. Result equality is the
scope gate. Later, point pulls can add entity-id/ref-aware fast rejection from
the retained datoms, but that is an optimization, not a second correctness
mechanism.

Program source/spec, context membership, route facts, render input shape, and
explicit clock/runtime signals are plan dependencies. A plan-result change
rebuilds the shell and recaptures reads. A normal data-result change dirties only
the dependent render unit.

### Render units and subscription ownership

A unit is the smallest output that can be rendered once and patched by stable
ids. Suggested units are:

- one global system header shared by every open current page;
- roster shell/membership and one roster row per agent;
- one agent shell/plan for surface membership and order;
- one unit per agent surface, producing its primary and rail elements together;
- the focus controller;
- debug header, raw AI pane, each HTML twin, and token/context bar separately;
- data-browser result for one normalized parameter set.

One unique view subscription owns the derived plan and attaches N connection
handles. Shared units can have several subscriptions. A unit records its read
observations, target ids, current basis, and last serialized output. Connection
state contains only socket/gzip/backpressure data.

Initial paint derives the plan, renders each unit once under observation, and
assembles `#app-view`. An ordinary update emits only changed complete elements.
If a unit disappears or the plan changes, emit the shell `#app-view` fallback.

After rendering a dirty unit, compare its serialized complete elements to the
last bounded output. Identical output suppresses bandwidth and DOM work. This
output check is after the database-result gate; it cannot recover render CPU
already spent.

### Datastar transport stays

Datastar's patch watcher defaults to outer morph. Without an explicit selector,
it iterates every top-level child and finds the target by `child.id`
(`reference-code/datastar/library/src/plugins/watchers/patchElements.ts:39-145`).
The Clojure SDK has an explicit `patch-elements-seq!`
(`reference-code/datastar-clojure/libraries/sdk/src/main/starfederation/datastar/clojure/api/elements.clj:98-129`).

Therefore the current one-event/many-ID-elements framing is correct. Preserve:

- complete target elements rather than hand-authored DOM diffs;
- gzip streams with sync flush;
- one shared event string per rendered view/unit set;
- independent latest-wins backpressure per socket;
- frozen `as-of` feeds doing no current transaction work;
- inputs/feed openers outside `#app-view` where required.

### Hyperlith lessons and limits

Hyperlith taps every view onto one global refresh mult with a dropping buffer of
one, performs render/compression on a CPU pool, and scopes resources with
`with-open` (`reference-code/hyperlith/src/hyperlith/impl/datastar.clj:122-181`).
Its `refresh-all!` is global
(`reference-code/hyperlith/src/hyperlith/core.clj:106-108`).

Keep the latest-wins and resource-lifetime lessons. Do not copy its fan-out or
per-connection rendering. Node also cannot copy Hyperlith's thread-pool design
directly because the live Datahike value is not a cheap transferable worker
value. First eliminate irrelevant work and bound legitimate reads. If profiling
still shows event-loop stalls, isolate serialization/compression or provide a
worker a bounded materialized read model; do not pass/rebuild the whole database
per render.

## Data model: persist only irreducible facts

### Keep

- Domain entity attributes and refs.
- Route pattern/method/name/handler/middleware facts.
- Agent-to-context-block component relationships and each block's render symbol
  or literal value.
- Program source/spec/namespace/require facts needed to restore executable code.
- Minimal transaction user/process provenance ratified by the lifecycle design.
- Wire write correlation at the transport boundary.

### Do not add

- View, connection, subscription, render-unit, dirty, or invalidation entities.
- Stored changed-attribute sets or dependency edges.
- Render hashes/output snapshots.
- “last rendered,” “last seen,” acknowledgement, or global refresh flags.
- User/process categories used as UI routing rules.
- Stored clock ticks merely to refresh throughput.

### Remove after cutover proof

- `:seon.fn/read-attrs` and its tee/diff/regex compatibility path, if no remaining
  program-semantic consumer is proven.
- The origin-based debug routing branches.
- `seon.web.serve/!sse-connections`, `open-sse-connections`, `open-sse!`, and
  `/sse`.
- `seon.web.debug/!sse-by-agent`, its timer/listener/framing, and separate
  `/debug/sse`/`data/sse` implementations after those pages use the one feed.
- Hard-coded agent-view structural/header/surface dependency sets after compiled
  plan/read observation replaces them.
- Broad inventory calls from the persistent header.

## Global fan-out inventory

| Current condition | Current effect | Target |
|---|---|---|
| Every committed transaction | Wire publisher reaches each pod for that database | Keep; this is replica delivery |
| Every local listener | Each receives every native tx report | Keep one UI listener; cheap compile/match only |
| Every main-feed batch | Visit every unique live view key | Reverse-index to candidate reads/units |
| Any fixed structural attribute | Full `#app-view` for every agent feed | Compare plan read per scoped subscription |
| Any shared surface attribute | Render every surface with that attribute | Compare each normalized read; render changed units only |
| Header attribute match | Recompute header separately inside each agent view | Render one shared header unit once |
| Any transaction with roster open | Full roster, every agent canvas preview | Membership/row/canvas units and result comparison |
| Debug tx with no agent or config/core origin | Every debug agent snapshot | Remove; dependency graph decides |
| Debug agent-stamped tx | That agent's entire debug snapshot | Dirty exact AI/HTML/diagnostic units |
| Any transaction with `/data` open | Whole current data page | Parameterized query-result unit |
| Continuous structural transactions | Timer repeatedly reset | Scheduled flush plus bounded max wait |
| Route fact transaction | Currently no guaranteed rebuild | Recompile router once when route projection changes |
| Frozen `as-of` feed | Excluded from current updates | Keep |

Transaction metadata datoms will still appear in the base changed-attribute set.
They should dirty a view only when one of its reads actually projects them.

## Open-feed RSS and SCI budget

The measured grown store had about 192,000 datoms. With one SSE feed open,
repeated HTML renders exceeded the SCI deadline and RSS oscillated from roughly
1.4 to 2.5 GB before GC reclaimed it. The pod remained responsive, so this was
transient allocation pressure rather than an accumulating leak
(`docs/prds/agent-ctx/coordination.md:2487-2501`).

There are several source-proven amplifiers:

- roster change rendering invokes every agent canvas on every commit;
- structural attributes globally rebuild agent shells;
- debug rebuilds every HTML twin on provenance-selected commits;
- the header runs a whole-store inventory scan per render;
- transcript's default empty tiers render all historical events, and its path
  includes lazy entity walks and wildcard pulls;
- each agent-authored renderer rebuilds an SCI environment, queries/indexes
  required namespaces, creates a fresh SCI context, evaluates stored source, and
  deep-realizes the result (`src/seon/render/sci.cljs:314-437,468-606`);
- the SCI deadline checks interpreted code, but cannot interrupt a large native
  Datahike query or compiled helper while that host call is allocating.

The observed feed path/view was not captured in the evidence, so do not claim
one amplifier as the sole root. Instrument first.

Fix order:

1. Exact invalidation prevents irrelevant SCI invocation.
2. Render the global header once and replace its inventory scan with a cheap
   current-datom count. Plain Datahike `DB` count delegates to the EAVT index
   count (`reference-code/datahike/src/datahike/db.cljc:307-326`); expose this
   through `seon.db`, with honest wrapper behavior for filtered/temporal values.
3. Decompose roster/debug into bounded units.
4. Refactor core render reads to agent/entity-scoped queries and explicit
   bounds. Collapsed markup is not a compute bound if the server still builds
   every hidden child.
5. Add a render-scoped database-read budget/fail-loud policy for agent-authored
   HTML. Datahike's top-level `:limit` is often a result transform after
   execution, so it is not by itself proof of bounded intermediate allocation
   (`reference-code/datahike/src/datahike/query.cljc:3752-3957`). Require
   structurally selective queries for large stores and measure planner
   intermediates where needed.
6. Only after these changes consider worker isolation. Never “fix” this by
   increasing the 250 ms SCI budget.

Datahike's process-local query result cache is itself bounded by snapshots and
tuple weight, but it should be included in RSS profiling. Separate retained
cache weight from transient SCI/query allocations.

## Phased implementation plan

### Phase 0 — baseline and observability

- Add runtime counters/timers without changing rendering decisions.
- Record commits, coalesced batches, live sockets, unique view subscriptions,
  candidate reads, compared reads, dirty units, renderer invocations, SCI
  interrupts, output-suppressed units, event-loop delay, GC, heap, and RSS.
- Break render time down by plan, database reads, SCI environment, SCI body,
  hiccup serialization, gzip write, and unit/view key.
- Reproduce on a copy/synthetic grown default-cluster store. Leave ACME alone.
- Capture which feed path and renderer symbol produces each over-budget event.

Human-facing size reporting remains in estimated tokens. Low-level transport or
heap profilers may retain native byte measurements internally, but do not add a
new character-count display.

### Phase 1 — one subscription owner and lossless coalescing

- Replace per-connection render/dependency closures with one subscription per
  normalized view key and attach connections to it.
- Retain earliest `db-before`, latest `db`, unioned datoms/entities/attributes,
  and structural hints in the pending batch.
- Replace reset-forever trailing debounce with frame coalescing plus a bounded
  structural maximum wait.
- Preserve per-socket gzip and latest-wins backpressure.
- Add the route-attribute listener and rebuild the compiled router once per
  relevant batch.
- Prove the equivalent-tab cache survives closing the first-opened socket.

### Phase 2 — stable render units and one feed

- Extract global header, agent shell, surfaces, roster rows, debug panes, and
  data browser into stable ID-addressed units.
- Share the header render across all current views.
- Move debug and data onto the Datastar gzip subscription mechanism.
- Delete their registry/listener/timers/framing after live equivalence proof.
- Delete dead `/sse` machinery and its static route.
- Ensure a full shell morph remains the correctness fallback for membership or
  conditional disappearance.

### Phase 3 — observed reads and result-diff routing

- Add a no-overhead-when-unbound synchronous read observer to `seon.db`.
- Reuse/expose Datahike's dependency extraction; unknown reads are broad.
- Migrate render-layer direct Datahike calls and lazy entity walks.
- Compile read-to-unit and attribute-to-read reverse indexes per plan.
- Compare each candidate normalized read on batch `db-before`/`db-after` once.
- Render only dirty units, recapture their conditional reads, update indexes,
  and suppress byte-identical output.
- Treat route/context/program plan reads separately from ordinary output reads.
- Do not consult transaction user/process to decide invalidation.

### Phase 4 — recency and stored-read-set deletion

- Use runtime-derived renderer read observations for historical deliberate-touch
  queries.
- Keep transaction user solely where the semantic question is who deliberately
  changed a surface.
- Fix `last-updated-tile` to scope attribute touches to that user.
- Prove focus/order after cold restart and after shared-attribute writes.
- Remove `:seon.fn/read-attrs` and all compatibility paths if no concrete
  non-derived consumer remains.

### Phase 5 — bound legitimate renders

- Replace persistent-header inventory with index count and shared projections.
- Bound/window roster previews, transcript HTML, debug HTML twins, and data
  browser results before building hidden hiccup.
- Add fail-loud render read budgets for agent-authored HTML without silently
  truncating domain semantics.
- Remove rolling throughput from the persistent header. Derive interval usage
  on demand from timestamped turn/log facts; do not transact fake timer facts.
- Profile Datahike result-cache retention separately from transient query/SCI
  allocation.

### Phase 6 — delete and graduate

- Delete hard-coded dependency tables, provenance fan-out, duplicate SSE paths,
  stale docs, and superseded tests in the same phase as their replacements.
- Update `docs/seon/architecture/ui.md` to describe runtime read observations and
  shared subscriptions rather than the current literal read-set cache.
- Run cold restart, warm mint, agent planning/memory workflow, browser morph,
  server-side gzip feed, time-travel, CPU, RSS, and event-loop acceptance drives.
- Commit each phase only after live proof.

## Verification and profiling matrix

Structural tests should exercise mechanisms, not context wording.

| Scenario | Required observation |
|---|---|
| Unrelated attribute transaction | Zero candidate renderer/SCI invocations |
| Same attribute, other agent's entity | Query comparison may run; unrelated unit result stays equal and renderer does not run |
| Relevant agent message | Only transcript/focus/header units whose reads changed render |
| Shared global fact | Every genuinely dependent unit updates regardless of transaction user |
| Renderer redefinition adds a new read | Plan/dependency recompiles; next write to new attr updates |
| Current namespace changes auto-run membership | Shell morph adds/removes exact surfaces |
| Two equivalent tabs | One render; two pushes; state remains correct after either tab closes |
| Change then revert inside one batch | Read results equal; no render |
| Conditional renderer becomes absent | Full shell fallback removes old targets |
| Continuous structural writes | Latest state renders within bounded maximum latency |
| Frozen `as-of` feed | No current read comparison or render |
| Route datom add/change/remove | Reitit recompiles once and request dispatch reflects it |
| Roster agent-local update | One row/preview, not every agent canvas |
| Debug agent-local update | Exact raw/HTML/diagnostic units only; no provenance branch |
| Grown store, one feed | No unrelated SCI interrupts; bounded RSS/heap sawtooth |
| Grown store, several same/different views | Work scales with dirty unique units, not socket count × full views |

Run open-feed verification with a Node gunzip client rather than the browser
bridge's long-lived SSE proxy. Use a real browser for morph correctness, input
focus, scroll anchoring, and equivalent-tab behavior.

## Decisions after the audit

- Runtime-captured database reads are the general dependency truth. No
  dependency entities are persisted; conservative runtime observations cover
  lazy/clock/non-database legacy reads until those paths are removed.
- `/data` uses exact query-result invalidation as one bounded unit. The global
  header does not run the full provenance inventory.
- `:seon.fn/read-attrs` is deleted after runtime capture and cold-restart
  deliberate-recency proof.
- Transcript/debug HTML twins are windowed with older detail loaded on demand;
  exact raw AI text remains available.
- A relevant agent-authored renderer that still exceeds its bounded-read budget
  fails loudly with selective-query guidance rather than silently clipping
  domain data.

The rolling 60-second token rate is removed from the persistent header. Usage
over an interval remains an on-demand database/log query.

## Conclusion

The database already emits the change information, Reitit already demonstrates
the facts-to-compiled-index boundary, Datahike already demonstrates conservative
attribute dependency invalidation, and Datastar already accepts the exact
multi-element morph shape Seon sends. The missing piece is ownership and
composition: one compiled runtime graph per unique view, one read-observation
mechanism, one feed, and query-result equality as the final scope test.

This design removes duplicate paths and fixes stale-cache bugs without storing
processing descriptions in Datahike. It also attacks the grown-store problem at
the correct layer: avoid irrelevant renderer work first, then bound the small
set of legitimate renders that remains.
