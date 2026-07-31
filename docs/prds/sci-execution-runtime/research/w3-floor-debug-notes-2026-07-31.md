---
type: research
status: complete
tags: [research, render, ui]
---

# W3 — one HTML floor and the per-agent debug view

## Result

W3 now has one admission-backed structural floor. The router falls through to
`seon.render.block/data-panel`; that seam and the AI `data-prose` seam both
delegate to `seon.render.value`, where `seon.sci.admit/admit` is the only
nested-data admission walk (`src/seon/render.clj:166`,
`src/seon/render/block.clj:903-939`, `src/seon/render/value.cljc:173-398`). The
block expander no longer declares its own fallback before calling the router.

The router's public `resolve-unit` associates the chosen declaration and
`:seon.render/would-fall-to-floor?` on the returned unit. The boolean records
which precedence branch won; it is not inferred by comparing symbols, so an
explicit declaration that happens to name `data-panel` remains explicit
(`src/seon/render.clj:166-182`). W4 can consume this without reproducing the
precedence chain.

`GET /agent/{id}/debug` is now an exact, read-only route. It starts at that
agent's entity and navigates with an EDN `get-in` path plus offset. The floor
keeps the root selector in every link and derives a stable element id from
agent + root selector + path (`src/seon/render/value.cljc:28-56`,
`src/seon/render/web.clj:285-390,879-1002`). Unknown agents answer 404.

The debug feed is the existing delivery mechanism, not a second one:
`/feed/{id}?debug=true&path=...` uses the same mult, sliding-1 tap, virtual
thread, socket drain fence, byte diff, and Datastar outer morph as the curated
page (`src/seon/render/web.clj:445-488,668-757`). A debug registration causes a
commit wake to reach the existing mult even when curated-page bytes did not
change; the on-demand tab then derives its current drilled node. Closed debug
tabs cost nothing.

## Dependency ledger

- core.async Flow: `org.clojure/core.async` `1.10.874-alpha3`, vendored at
  `reference-code/core.async` commit
  `dc35f3e0d7bc2eef502e77982f48641f025c8051`. The existing render proc, mult,
  sliding-1 taps, and workload tag remain the delivery owner.
- Datahike: vendored commit
  `9b3be9d59cb07d9c895af280e60eb074bb57a400`. `d/pull '[*]` was not used as a
  recursive debug walk: non-component refs pull only `{:db/id ...}` while
  component refs may nest (`reference-code/datahike/test/datahike/test/api_test.cljc:266-267`).
  The debug reader instead reads one opened entity's current EAVT datoms and
  leaves every ref as a `{:db/id eid}` drill handle.
- http-kit: vendored commit
  `238a85cc555a38892f2f9a7583c9cf5cec0fb201`. The existing write-state drain
  completion remains the socket backpressure owner; W3 added no writer.
- First-party precedents: `src/seon/render/web.clj`'s exact four-route
  dispatcher and feed; `src/seon/render/data.clj`'s EDN path/offset cursor;
  `src/seon/render/block.clj`'s admission-backed panel; and
  `src/seon/sci/admit.clj`'s finite ordinary-data codec.

## Quarry — what survived and what did not

From `src-old/seon/render/value.cljc` W3 kept the useful presentation
properties: typed structural summaries, loud elision, retained map-key/vector
index navigation identity, lazy-head safety, opaque/reference markers,
bounded string heads with whole-value token estimates, collapsible structure,
and inspect affordances. From the old `/data` implementation W3 kept the
literal `get-in` path vocabulary, path-prefix breadcrumbs, offset paging, and
the rule that the root selector is independent of the path.

W3 left behind the old renderer's independent `sample` traversal, semantic
field ranking that required another raw walk, eval-result/blob identities,
whitespace dials, schema badges, CLJS machinery, and full-string expansion.
It also left behind the old debug page's latest-turn cache and separate feed.
Those shapes either duplicate the admission codec, belong to eval retention,
or recreate a second delivery system.

## Debug visibility and its deliberate cost

The debug entity reader shows every direct current attribute and both forward
and reverse ref handles. Therefore the blocks referenced by the agent and
transaction entities pointing to the agent are drillable here, even though
the context walk correctly excludes both as apparatus. Reverse-ref truncation
uses the existing collection cap and appends the admission elision marker.

This is intentionally a generic EAVT read plus a generic reverse-ref query.
It is the exception allowed by ruling #8: 168 of 323 installed attributes are
outside every current family, and a debug view that used only family pull
selectors would lie by omission. The cost is conservative wake/read behavior:
while a debug tab is open, every commit can re-read its opened entity and
reverse refs. It does not affect closed tabs or the curated page; W5 should
not copy this read shape into ordinary render invalidation.

## Protected-walk boundary

`src/seon/render/walk.clj` remains untouched. Its current `projection`
function selects a winner and associates that declaration onto the unit before
calling the router (`src/seon/render/walk.clj:151-175,382-390`). That erases
branch provenance for callers downstream of the walk: an inherited floor can
look explicit to `resolve-unit`. W3 did not work around this with symbol
comparison because an explicit declaration may legitimately name the floor.
W1 owns the required chain integration. Until it lands, W4 must call
`resolve-unit` on an unresolved unit rather than infer the checkbox flag from a
walk node's chosen symbol.

## Proof

Focused recurring gates, all green:

- `bin/test seon.render.value-test seon.render-test seon.render.block-test`
- `bin/test seon.render.web-test`
- `clj-kondo --lint` over the seven changed source/test files: zero errors;
  remaining warnings pre-existed except the intentionally named generic
  bindings.

Live proof used scratch cluster `w3-proof`, then stopped it with
`bin/seon stop w3-proof`:

- Hot-reloaded the four owners and rebuilt only the scratch web server; it
  rebound `http://127.0.0.1:7764` in 15 ms.
- Attached namespace `my.agents.w3-proof` to real agent `root` at basis
  `536870926`, with `:seon.ns/source` exactly `5,242,880` characters.
- `GET /agent/root/debug` returned 200.
- The live SSE drill at
  `[:seon.cluster.agent/blocks 0 :seon.render.block/name]` returned one
  Datastar patch rooted at stable id `seon-value-8f79374578b3fea59640467c`,
  with all three path segments in its breadcrumbs and value `:header`.
- A provenance transaction pointing at root made
  `[:seon.render.debug/reverse-refs :seon.db/user 0]` expose
  `:db/txInstant`, `:seon.db/process`, and `:seon.db/user`: transaction
  apparatus is reachable rather than silently filtered.
- The live SSE drill at
  `[:seon.cluster.agent/namespace :seon.ns/source]` returned stable id
  `seon-value-ada08df54df2f58211e287b3`, `1,310,720 tokens`, `inspect`, and
  loud `elided` markers. The patch stayed below 100 KB rather than carrying
  the 5 MiB source.

The in-app browser runtime reported no available browser instances during the
visual pass. The live route and SSE proof therefore used the real local HTTP
server directly; the recurring web suite independently exercises the same
paths over real sockets and validates the Datastar morph bytes.
