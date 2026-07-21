---
type: research
status: complete
tags: [research, database, flow, agent, web]
---

# Render and context single-owner cut — 2026-07-16

## Decision

Do not add an asynchronous renderer, a render-only process, or a compatibility
database wrapper. The existing execution child is already the asynchronous
boundary. A trusted invocation carries one ordinary `:seon.db/db` value;
`seon.execution` binds that value into the child's transaction context before
calling `render-prompt!` or `render-agent-view!`; every nested selected
function executes in that same child and inherits that same value.

Retain these owners and strengthen them in place:

- `seon.execution.runtime/render-prompt!` owns complete AI context acquisition
  and selected-function execution. `seon.agent.ctx` retains only pure block
  selection, decoding, ordering, and text assembly after acquisition.
- `seon.execution.runtime/render-agent-view!` owns complete agent-page
  acquisition and selected human-surface execution.
  `seon.ui.agent-view/render-agent-view` remains the pure page formatter.
- `seon.render.surface` owns the ordinary materialized-surface shape.
- `seon.render.canvas/wired-content` owns canvas selection. Prompt and human
  surfaces may acquire different projections, but both derive the selected
  literal or function from this one pure rule.
- `seon.render.system/system-view` owns root's dynamic canvas and runs as an
  ordinary selected function in root's existing execution child.
- `seon.render/block` owns trusted typed-value rendering used by message, eval,
  transcript, and debug formatting.

Agent-authored `:seon.render/ai` and `:seon.render/html` symbols use only the
existing `invoke-selected!` path. That path loads the current authored program
into the agent's retained compiler, wraps sync or async returns in a Promise,
applies the parent deadline, bounds the eager ordinary result, and kills and
replaces a child that does not terminate. There is no reason to reconstruct
source in SCI or call a dynamic global from the pod.

This supersedes the SCI and operation-capture portions of
[[async-render-authority-seam-2026-07-16]]. The child path now exists. Render
dependencies are declared database facts on the function and returned surface,
not a replay log of database calls. Eval operation capture is being deleted by
the native-result cut and must not be reintroduced for rendering.

## Dependency ledger

| Dependency or existing owner | Selected source | Constraint used by this cut |
|---|---|---|
| Datahike | `reference-code/datahike` at `a464cd887458d2572414a6ea951c477b0981fdae` | One immutable database value owns its indexes and exact query-cache identity. Bun receives ordinary results, never a Datahike object. |
| Bun | `reference-code/bun` at `be77b652884b16a103cfaa4af3c1102f72f2dcd3` | The existing child host already provides process isolation, deadlines, cancellation, and replacement. No render worker is needed. |
| Shadow ClojureScript | `reference-code/shadow-cljs` at `4e72595f57618f5c43388ad13d5136cd3bede566` | The execution artifact already includes trusted core functions and the child compiler entrypoint. |
| SCI | `reference-code/sci` at `b4917436550c857a18b8f6a4a8b5b26356acc2c4` | No retained production render behavior requires a second interpreter once authored functions run in an isolated compiled child. |
| Database facade | `src/seon/db.cljs:430-749` | `db`, query, pull, schema, and `execute-many` are asynchronous and return ordinary values or direct `:seon.error/*` maps. `execute-many` returns `::db/results`, not a database coordinate. |
| Child invocation | `src/seon/execution.cljs:417-468,605-630,705-800` | The parent puts one database value on the invocation. A compiled function marked `::pin-database?` receives it through `db/with-tx-context`; selected calls use `Promise.all` and inherit the same invocation. |
| Compiled render entrypoints | `src/seon/execution/runtime.cljs:180-424` | `render-prompt!` and `render-agent-view!` already batch outer reads and resolve selected AI/HTML functions in the child. |
| Prompt formatter | `src/seon/agent/ctx.cljs`, `src/seon/agent/ctx/*.cljs` | `rendered-context-from-entity`, block ordering, and the acquired formatting tails are ordinary-data transformations. Several acquisition heads still use the deleted coordinate response and are the next mechanical cut. |
| Human view | `src/seon/ui/agent_view.cljs`, `src/seon/render/surface.cljs` | The pod formats one returned ordinary page projection; it does not need a database value or a renderer lookup. |
| Current render leftovers | `src/seon/render.cljs`, `src/seon/render/{default,canvas,chat,sci}.cljs` | The old in-pod renderer still performs synchronous database reads, ambient connection fallback, dynamic lookup, and SCI execution. Production page and prompt entrypoints now bypass most of it. |

## Shortest falsifier

The facade now makes the old context contract impossible:

- `seon.db/execute-many` returns only `::db/results` on success.
- `menu`, `transcript`, `typeahead-steps`, `namespaces`, `warnings`,
  `subagents`, and `canvas` still read `::db/coordinate`, put it on later
  requests, compare it between stages, or fail when it is absent.
- Their focused tests manufacture the old response and therefore prove an
  interface that no longer exists.

One direct child invocation with a real ordinary database value is enough to
falsify the current code: the first successful `execute-many` has no
`::db/coordinate`, so a dependent context block takes its failure branch even
though all member results succeeded. No performance experiment is needed
before fixing this contract.

The matching positive proof is also small. Bind database value `D` in a child
invocation; make a two-stage context block issue both reads without any
coordinate field; assert that the facade puts `D` on every wire member and
that the pure formatting tail performs zero database calls. Advance the
session head between the two replies. Both reads must still use `D`.

## Retained owners by surface

| Surface | Retained execution owner | Retained pure formatter | Superseded path to delete |
|---|---|---|---|
| Complete AI prompt | `seon.execution.runtime/render-prompt!` | `seon.agent.ctx/rendered-context-from-entity` and block-local formatting tails | synchronous `seon.render/render` prompt assembly, injected render callback, local database render reads |
| Stored and derived AI block | `render-prompt!` plus child-local `invoke-selected!` | literal/string normalization in `resolve-blocks!`; `seon.agent.ctx.render-fns/render-fn-block-ai` for the established twin contract | `seon.render.sci/invoke-bounded`, pod `eval/lookup-value`, any unbounded fallback |
| Agent web page | `seon.execution.runtime/render-agent-view!` | `seon.ui.agent-view/render-agent-view` | `seon.render.default/view`, schema-selected agent renderer, local page queries |
| Stored/derived HTML surface | `render-agent-view!` plus child-local `invoke-selected!` | `seon.render.surface/materialized` and `renderer-value` | `seon.render/render-entity-html`, `html-render`, pod `eval/lookup-value`, SCI |
| Agent canvas | page or prompt compiled entrypoint, using the same selected child call | `seon.render.canvas/wired-content` and the returned HTML/AI twin | `seon.render/render-agent-canvas`, `canvas-state` database reads, ambient fallback, SCI recovery transaction |
| Root canvas | root's selected `seon.render.system/system-view` call | `fleet-summary`, `human-view`, and `ai-view` | a second root/page renderer or recursive rendering of every child agent |
| Transcript events | `transcript-block` acquisition inside `render-prompt!` | direct dispatch to existing `message->renderable`, `eval->renderable`, and `coalesced->renderable`; `seon.render/block` for typed content | injected `:seon.render/render`, fallback recursive walker, schema lookup, dynamic symbol lookup |
| Typed core values | caller's existing prompt/page/debug owner | `seon.render/block` | parallel entity-schema renderer when it has no production caller |

`seon.render.chat/conversation`, `seon.render.default/view`,
`render-entity-ai`, and `render-entity-html` currently have no production
caller outside the superseded renderer graph. `seon.render.chat/last-reply` is
still reached by the welcome canvas and should be reduced to a pure formatter
over already acquired message data before deleting the rest of `render.chat`.
Do not async-port an otherwise dead renderer merely because its tests still
call it.

## Exact obsolete paths

### Ambient and synchronous database reads

Delete every `db/*conn*`, `@db/*conn*`, and `:seon.db/conn` path. The active
inventory is:

- `seon.render/render-agent-canvas` falls back to `@db/*conn*`, then calls the
  synchronous `canvas-state`, derived-surface query, and selected renderer.
- `seon.render/slot` falls back to `@db/*conn*` and calls synchronous
  `agent-ctx-block`/`db/pull` while walking the render tree.
- `seon.render.chat/conversation` and `seon.render.canvas/user-name` fall back
  to `db/*conn*` and synchronously query or inspect installed schema.
- `seon.render.default/recent-messages` and `view` use ambient connection and
  synchronous message/derive reads.
- `seon.render.sci/fn-source`, require reconstruction, and namespace exposure
  synchronously query a local database value.
- `renderable-inst`, old entity dispatch, and old canvas/default functions call
  asynchronous facade functions as if they returned immediate values.

The correct replacement is not an awaited call inside the recursive walker.
The owning compiled entrypoint acquires ordinary rows first. The formatter
receives those rows and stays synchronous.

### Removed database-coordinate response

Delete `::db/coordinate` request/response handling and comparisons from:

- `seon.agent.ctx.menu/acquire-prompt-menu`;
- `seon.agent.ctx.transcript/acquire-transcript`;
- both acquisitions in `seon.agent.ctx.typeahead-steps`;
- `seon.agent.ctx.namespaces` acquisition and schema-frontier helpers;
- warning acquisition and instrumentation-gap acquisition;
- subagent two-stage acquisition; and
- canvas candidate/history acquisition.

The outer compiled invocation already owns `D`. The `seon.db/execute-many`
facade should resolve `D` once from explicit `::db/db` or the current
transaction context and associate it with every member that does not already
name a database value. The wire protocol may continue carrying `:seon.db/db`
on each member because each member is independently valid; application callers
should not repeat it. A member that explicitly names another database value is
invalid inside this one-value helper, not a reason to support mixed snapshots.

Dependent reads simply run in the same transaction context. There is no second
identity to compare. Direct calls outside a compiled child may pass
`{::db/db D}` once to `execute-many`; omission may acquire `D` once at the
facade boundary. This preserves the normal optional-database convention of
`query`, `pull`, and `installed-schema`.

### Old result and error envelopes

`seon.db/transact!` returns a native transaction report or a direct
`:seon.error/*` map. Delete:

- `:seon.db/ok?` and nested `:seon.db/error` branching in
  `seon.agent.ctx/install!` and `remove!`;
- context text teaching agents to inspect `:seon.db/ok?` envelopes;
- `:seon.db/error` canvas response maps where the ordinary error belongs under
  `:seon.error/*`; and
- stale envelope examples in `seon.render.value` and render tests.

The public `install!`/`remove!` result may retain its own established
`::ok?`/`::error` contract. It should decide failure by
`:seon.error/message` on the database result, not translate from a deleted
database envelope.

### Duplicate renderer and fallback behavior

Delete, rather than port:

- `seon.render.sci` and all `invoke-bounded`, interrupt-envelope, recovery
  transaction, and bounding-dial branches;
- agent-authored branches in `seon.render/resolve-render` and
  `render-agent-canvas`;
- `ai-render`/`html-render` dynamic global lookup and the silent
  `pretty-ai`/`pending-html` fallback for an unresolved authored symbol;
- the old schema-selected `render-entity-ai`/`render-entity-html` path after
  converting the transcript's small trusted dispatch and confirming no live
  caller remains;
- `slot` and `agent-ctx-block`; page layout already consumes materialized
  surfaces and must not issue database reads through a layout hole;
- `seon.render.default/view` and its query helpers; the agent page projection
  replaces it; and
- `seon.render.chat/conversation` after its welcome-card data formatter is
  separated from database acquisition.

An unresolved selected symbol returns the existing child-local structured
error surface. It does not silently pretty-print an unrelated input map. A
literal string or hiccup remains ordinary data and never goes through lookup.

## Database-value flow

### Prompt

1. The turn owner acquires current database value `D` once.
2. The host invokes compiled `render-prompt!` with `D`.
3. `seon.execution` binds `D` into the child's transaction context.
4. `render-prompt!` performs one outer `execute-many` for agent/config facts.
5. Block acquisition functions perform any data-dependent second stage in the
   same context. Their pure tails receive ordinary data only.
6. Selected AI functions run through child-local `invoke-selected!`; their
   optional `seon.db` calls inherit `D`.
7. The child returns the complete ordinary rendered context. The host verifies
   the returned invocation database value is `D`.

### Human page

1. The subscription owner resolves the requested live or historical database
   value `D`; it must not pass the old four-field coordinate where
   `invoke-compiled!` requires `:seon.db/db`.
2. The host invokes compiled `render-agent-view!` with `D`.
3. The child acquires the page projection and invokes selected surfaces under
   `D`.
4. The child returns ordinary materialized surfaces and declared dependency
   attributes.
5. `seon.ui.agent-view/render-agent-view` creates the page. The Datastar owner
   shares the resulting serialized element among equivalent consumers and
   performs no database query or renderer lookup.

The web parser may still accept the public historical selector fields. Its
owner must resolve them to an ordinary database value before child invocation;
that serialized selector is not the render/context interface.

## Dependency order

1. **Settle one-value `execute-many`.** Make the facade attach one explicit or
   inherited database value to every member. Add direct proof that it resolves
   omission once and rejects mixed member database values.
2. **Remove coordinate handling from context acquisition.** Convert all seven
   context families together so no old response contract remains. Keep their
   existing bounded members and two-stage query plans; only the database-value
   ownership changes.
3. **Correct native errors.** Update `install!`, `remove!`, canvas error data,
   and agent-facing context text in the same cut as their tests.
4. **Make canvas selection shared and ordinary.** Keep
   `canvas/wired-content`; ensure prompt and page owners both acquire the rows
   it needs and invoke the chosen symbol only through `invoke-selected!`.
5. **Remove the old renderer graph.** Replace transcript's injected recursive
   dispatch with its existing direct event converters, then delete SCI,
   dynamic authored lookup, ambient reads, slot, default view, and unused
   entity/chat renderers with their tests.
6. **Prove the existing compiled owners end to end.** Only after the focused
   gates pass, run the maintained CLJS gate and live root/agent/Datastar proof.
   Do not optimize batching or serialization before correctness is measured.

This order deliberately removes the stale database contract before deleting
large render files. Otherwise failures in the surviving prompt blocks would be
misdiagnosed as renderer failures.

## Focused proof

### Facade and child database value

- `execute-many` with explicit `D` puts exactly `D` on all wire members.
- omission under `db/with-tx-context {::db/db D}` resolves no head and uses
  exactly `D` for all members and dependent requests.
- omission outside a transaction context resolves the current database once,
  not once per member.
- a mixed-database member request fails as an ordinary core error before wire
  dispatch.
- `render-prompt!` and `render-agent-view!` return the same `D` in their child
  result envelope; a later session head does not change their reads.

### Context

- menu, transcript, namespace, typeahead, warning, subagent, and canvas
  focused tests use ordinary `database` maps, never coordinate fixtures.
- every two-stage test advances the session head between stages and proves the
  second request still uses `D`.
- every formatting tail redefines all `seon.db` read functions to throw and
  still returns the expected text.
- literal, async selected, missing, throwing, and non-terminating render
  functions produce the established ordinary value or error without blocking
  a sibling block.

### Human surfaces

- one root page and one ordinary agent page render through
  `render-agent-view!`; root's system canvas and an authored canvas both use
  selected child calls.
- literal hiccup never invokes a function; one authored symbol invokes exactly
  once; equivalent browser consumers reuse one serialized result.
- child timeout kills only that child, returns an error surface, and a later
  invocation succeeds after replacement.
- an unrelated transaction does not rerender a surface whose declared read
  attributes do not intersect the transaction.

### Deletion assertions

After the cut, production source has zero matches for:

```text
db/*conn*
:seon.db/conn
:seon.db/ok?
seon.render.sci
invoke-bounded
render-agent-canvas
render-entity-ai
render-entity-html

```

There is exactly one production call path for authored AI and HTML functions:
`execution child -> invoke-selected!`. There is exactly one recursive or typed
core value formatter retained: `seon.render/block`; transcript event selection
is direct ordinary-data dispatch, not another general renderer.

## Performance implications

The architectural gains do not depend on a microbenchmark:

- no Bun process holds Datahike indexes, entities, history values, or query
  cache entries;
- one invocation database value lets Datahike share exact query cache and
  single-flight work across every agent asking the same question;
- independent `execute-many` members and selected async functions can be in
  flight together without serial leaf awaits;
- formatting performs no IPC and no database read;
- deleting SCI avoids rebuilding an interpreter environment and querying
  stored source/namespace facts on every render;
- deleting the old recursive renderer removes duplicate error, fallback,
  lookup, canvas, and database behavior; and
- equivalent browser consumers retain one complete serialized surface, not a
  second database-result cache.

After correctness, measure cold and warm prompt/page latency, authority cache
evidence, member concurrency, child CPU/RSS, serialized bytes, and rerender
counts. Only measured retained work may justify another optimization.

## Graduation gate

The cut is complete when the maintained CLJS gate passes and live proof shows
root, agent, data, and debug views; one complete turn; literal and authored
canvas updates; multiple agents; historical rendering; child timeout and
replacement; and selective Datastar refresh, all with ordinary database values
and no local Datahike object. Static deletion checks supplement that proof but
do not replace it.
