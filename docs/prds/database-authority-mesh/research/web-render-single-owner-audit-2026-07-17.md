---
title: Web render single-owner audit
type: research
status: completed
tags: [research, database, flow, web]
---

# Web render single-owner audit

## Decision

The next web/render boundary is one atomic database-value cut, not another
adapter. Retain four owners:

- `seon.render/render` owns guarded AI and HTML render semantics over ordinary
  data;
- `seon.execution.runtime/render-agent-view!` owns the outer database
  acquisition and isolated authored-function invocation;
- `seon.render.surface` and `seon.ui.agent-view` own pure surface and page
  formatting; and
- `seon.web.datastar` owns normalized subscriptions, one complete serialized
  event per equivalent view, SSE delivery, and browser backpressure.

Delete the remaining parallel owners: host-side canvas acquisition and SCI
invocation, the unused `/view/unit` producer path, the two feed render
contracts, and database-coordinate-era listener plumbing. Agent, debug, and
data feeds become ordinary definitions consumed by the one Datastar owner.

This is a current-source audit at Seon
`9352b0dfadf88db9332ccbc74b52983324f06932`. It makes no claim that the cut is
implemented.

## Source overrides older roadmap claims

The older roadmap and [[async-render-authority-seam-2026-07-16]] describe
`seon.web.view-unit` as the shared render cache and say the local SCI renderer
and web snapshot/output cache were deleted. Current source contradicts those
claims:

- `src/seon/web/view_unit.cljs:1-35` now contains only token encoding;
- the complete subscription, serialized-event, and consumer state lives in
  `src/seon/web/datastar.cljs:218-232,469-738`;
- `src/seon/render.cljs:657-842` still contains the host-side canvas
  acquisition, SCI invocation, error handling, and serialization path; and
- `src/seon/web/datastar.cljs:52-214,1330-1427` still contains an unused
  producer/catalog/action-render system.

Implementation must follow current source. Historical commit summaries are
useful evidence, not proof of the present tree.

## Dependency ledger

| Dependency or owner | Selected source | Constraint |
|---|---|---|
| Seon database API | `src/seon/db.cljs:268-280,513-587,968-1045` | Database values are ordinary data. `listen!` returns its public key, and handlers receive native transaction report data. |
| Database value contract | `src/seon/db/protocol.cljc:213-229` | The closed value is `db-name`, `t`, `as-of`, `since`, `history`, and `datahike/commit-id`; no Datahike object crosses the wire. |
| Writer interest | `src/seon/db/writer.clj:2123-2193,2311-2365` | Installation is serialized, query dependencies select interested listeners, matching commits carry transaction reports, and a gap carries `:db-after` resynchronization. |
| Datahike | `reference-code/datahike` at `a464cd887458d2572414a6ea951c477b0981fdae` | `datahike.core/listen!` is keyed replacement plus transaction-report callback; `unlisten!` removes by key. |
| Datastar | `reference-code/datastar` at `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f` | The client consumes arbitrary asynchronous byte chunks; outer element patches need no explicit selector. |
| Datastar Clojure | `reference-code/datastar-clojure` at `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2` | `examples.tiny-gzip` uses a separate long-lived GET stream and demonstrates immediate gzip delivery for small events. |
| Bun | `reference-code/bun` at `be77b652884b16a103cfaa4af3c1102f72f2dcd3` | Native streams may later replace Node socket/gzip mechanics without changing render or subscription ownership. |
| Intended seam | [[async-render-authority-seam-2026-07-16]] | Acquire outside pure render transitions; equivalent browsers share acquisition, render, serialization, and retained complete bytes. |

## Shortest falsifiers

### Listener contract

`seon.db/listen!` returns the listener key directly at
`src/seon/db.cljs:973-1023`. Its event adapter exposes `:db-before`,
`:db-after`, `:tx-data`, `:tempids`, and `:tx-meta` at
`src/seon/db.cljs:268-280`.

Current web consumers still expect the removed protocol-coordinate shape:

- Datastar reads `::protocol/coordinate` and `::protocol/datoms`, then expects
  `(::db/coordinate result)` from listener registration at
  `src/seon/web/datastar.cljs:768-775,931-947`.
- Router reads `:seon.db.protocol/coordinate` from each event and expects
  map-shaped `::db/key` and `::db/coordinate` registration output at
  `src/seon/web/router.cljs:420-460`.
- `test/seon/web/datastar_test.cljs:13-23` and
  `test/seon/web/router_test.cljs:58-83,196-255` fake the old contract and mask
  the mismatch.

The shortest executable falsifier invokes Datastar's transaction handler with
an ordinary transaction report and asserts that one affected subscription
renders at `:db-after`. A second test makes `db/listen!` return `::views` and
asserts installation succeeds. Current code fails both assumptions before a
browser is involved.

### Canvas default

`seon.execution.runtime/render-agent-view!` is the intended acquisition owner,
but `src/seon/execution/runtime.cljs:318-381` turns absent canvas content into
`No canvas render yet.` It does not apply the established explicit,
configured, derived, then welcome precedence encoded by
`src/seon/render/canvas.cljs:405-438`.

The shortest falsifier renders an ordinary acquired agent with no explicit
canvas and asserts the selected renderer is `seon.render.canvas/welcome` and
is invoked once. The current runtime returns the placeholder without invoking
it.

## Retained owners

| Behavior | Retained owner | Reason |
|---|---|---|
| AI and HTML semantics | `seon.render/render` | One guarded recursive renderer already owns literal slots, selected symbols, schema fallback, response extraction, and visible failures. |
| Database acquisition and authored invocation | `seon.execution.runtime/render-agent-view!` | It already performs one bounded `execute-many` and one batched isolated invocation. |
| Surface normalization | `seon.render.surface/materialized` and `face` | Compact and expanded faces derive from one resolved hiccup without invoking the renderer twice. |
| Page formatting | `seon.ui.agent-view/render-agent-view` | It is pure over the child-produced ordinary projection. |
| Live subscription and SSE | `seon.web.datastar` | One registry already shares render work and complete bytes across equivalent consumers and keeps newest-only socket pressure. |
| Route compilation | `seon.web.router` | Its process-lifetime route cache has a lifecycle independent of browser feeds; fix its listener contract without merging it into Datastar. |

AI and HTML are legitimate twins. Compact and expanded faces are legitimate
projections. Agent, debug, and data are legitimate feed definitions. The
shared complete event and each socket's newest pending event also have
different scopes: reconnect/fan-out versus browser pressure.

## Superseded owners

### Render execution

- `src/seon/render.cljs:201-229` `ai-render` and `html-render` duplicate the
  later `render` and `resolve-render` dispatch.
- `src/seon/render.cljs:657-842` `render-agent-canvas` is a second database
  acquisition, selected-renderer invocation, SCI containment, response
  extraction, error, and serialization owner. Only tests call it directly.
- `src/seon/render/surface.cljs:55-73` `renderer-value` is a second HTML
  response/error extractor beside `seon.render`'s one response extraction.
- `src/seon/render.cljs:911-924` `renderable-inst` has no caller and performs a
  database read inside render code.
- `src/seon/render.cljs:1080-1139` `slot` performs ambient database acquisition
  inside recursive rendering.

Retain the general recursive renderer. The execution child resolves an
authored function once, places the ordinary resolved value or visible error on
the block, and passes that block through the same renderer. Do not retain a
second result-envelope normalizer in `seon.render.surface`.

### Feed rendering

- `:seon.web.feed/render-full` and `:seon.web.feed/render-change` plus
  `::element` and `::elements` create two contracts for one database-derived
  view at `src/seon/web/datastar.cljs:106-140,277-317,405-439`.
- `src/seon/web/datastar.cljs:52-214,1330-1427` defines catalogs, producers,
  active-unit transitions, and direct action rendering. No production caller
  constructs a catalog; only `test/seon/web/datastar_test.cljs:25-31` does.
- `src/seon/route.cljs:103-107` nevertheless publishes `/view/unit`, whose
  handler invokes the producer outside normalized subscription sharing.

Use one feed render function of an ordinary database value, returning one
elements-plus-dependencies value. Initial paint, commit refresh, reconnect,
and historical rendering then traverse the same subscription owner. Delete
the inactive unit route and its token-only namespace if no remaining caller
requires it.

### Database acquisition

- `src/seon/web/datastar.cljs:1479-1526,1604-1663` retains the old four-field
  historical selector and `db/head-coordinate` flow.
- `src/seon/web/debug.cljs:73-93,138-172` passes coordinates to prompt and
  index reads instead of ordinary database values.
- `src/seon/web/datastar.cljs:1457-1462,1636-1666` queries agent existence and
  then makes the child acquire the same agent again for first paint.
- `src/seon/render/default.cljs:82-148,177-217`,
  `src/seon/render/chat.cljs:128-184`, and
  `src/seon/render/canvas.cljs:482-535` still query synchronously or fall back
  to `db/*conn*` inside core render functions.

Core page facts belong in the outer bounded acquisition. Pure renderers format
the returned agent, user, message, run, and context data. Open-ended authored
renderers may make awaited `seon.db` calls inside their isolated child, but the
web host must not simulate a local database value or add a result cache.

## Protected dirty paths

This audit did not modify or assume ownership of these active overlaps:

- `src/seon/web/serve.cljs`;
- `src/seon/web/reactive/call.cljs`; and
- `test/seon/web/reactive/call_test.cljs`.

The reactive call diff begins moving invocation capture to `db/db`, but
`src/seon/web/reactive/call.cljs:260` still reads `@db/*conn*`, and its tests
still install local connections. The serve diff remains coordinate- and
envelope-heavy; its new retained-blob query also uses removed
`::db/coordinate`. That owner must finish and commit before a later composition
cut overlaps `serve.cljs`.

Other dirty source observed during the audit was outside this boundary:
`src/my/blob.cljs`, `src/my/plan.cljs`, `src/my/plan/internal.cljs`,
`src/seon/agent/home.cljs`, `src/seon/db/internal.cljs`,
`src/seon/test/runner.cljs`, their tests, and `locks/`. Preserve all of it.

## Ordered cut boundaries

1. **Fix transaction-report consumption.** Make Datastar and router accept the
   listener key and native transaction reports. Derive changed attributes from
   `:tx-data`, render at `:db-after`, and treat resynchronization as a complete
   refresh at its `:db-after`.
2. **Unify the feed contract.** Replace full/change and element/elements with
   one database-value render result while retaining current subscription
   sharing, stale-completion fencing, complete bytes, and newest-only socket
   pressure.
3. **Delete the direct unit renderer.** Remove the unused catalog, producer,
   activation state, `/view/unit` route, token-only owner, and obsolete tests.
4. **Complete the child render owner.** Resolve explicit, configured, derived,
   and welcome canvas selection from the one acquired entity/context value;
   invoke literal or selected HTML once; route the resolved result through the
   one guarded renderer and pure surface formatter.
5. **Move core reads outward.** Add the existing agent, user, message, run, and
   context facts needed by core surfaces to the bounded acquisition. Remove
   ambient database reads from the core render path and delete the superseded
   canvas orchestration and result normalizer.
6. **Prove one live system.** Certify root, agent, debug, and data feeds plus
   reconnect, selective refresh, slow renders, browser pressure, and final
   consumer release. Native Bun serving is a later transport replacement, not
   a reason to preserve two semantic systems.

The current dirty serve and reactive-call work is sequenced separately. Do not
expand this cut into those files until their current owners hand them off.

## Acceptance matrix

| Contract | Focused proof | Failure excluded |
|---|---|---|
| Current listener API | Native transaction-report and scalar-key tests in `test/seon/web/datastar_test.cljs` and `test/seon/web/router_test.cljs` | Old coordinate acknowledgement/event adapters cannot return. |
| Selective refresh | An unrelated attribute causes zero render work; a matching datom renders once at `:db-after` | Broadcast-all and leaf-by-leaf reacquisition cannot return. |
| Gap recovery | Resynchronization installs only a complete render at its `:db-after` | Partial replay and local replica reconstruction cannot return. |
| Equivalent consumers | Two equivalent sockets cause one acquisition, invocation, serialization, and retained complete event | Browser count cannot multiply database or render work. |
| Async ordering | A slow older render cannot replace a newer database value; only the newest pending value remains | Late completion and unbounded pending queues cannot return. |
| Final release | Closing the last consumer removes its subscription and any in-flight work; closing one of two does not | Render state cannot outlive its readers. |
| Canvas semantics | Explicit, configured, derived, and welcome precedence; literal and awaited authored functions; visible errors | Placeholder-only default and dual canvas owners cannot remain. |
| One renderer | `seon.render/render` handles the resolved HTML or AI value; compact/expanded derive from that one value | `surface/renderer-value`, `render-agent-canvas`, and action rendering cannot remain. |
| Live HTTP/SSE | Root, agent, debug, and data pages pass browser checks; a server-side client proves long-lived gzip SSE | Unit tests alone cannot claim live feed correctness. |
| Deletion | Primary paths contain no `db/*conn*`, `head-coordinate`, `at-coordinate`, `::db/coordinate`, `render-full`, `render-change`, `::producer`, `handle-view-unit!`, `render-agent-canvas`, or `surface/renderer-value` | Compatibility vocabulary and parallel implementations cannot survive. |

Focused namespaces are `seon.web.datastar-test`, `seon.web.router-test`,
`seon.route-test`, `seon.execution.runtime-test`,
`seon.ui.agent-view-test`, `seon.render-test`,
`seon.render.canvas-test`, and `seon.render.chat-test`. Add
`seon.web.debug-test` only if the debug feed's single render definition cannot
be proved through the Datastar boundary. Run the complete `bin/test-cljs` only
at the coordinated atomic checkpoint.
