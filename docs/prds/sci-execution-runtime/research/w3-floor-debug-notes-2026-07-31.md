---
type: research
status: complete
tags: [research, render, ui]
---

# W3 — one HTML floor and the per-agent debug view

## Result

The router and per-agent debug view now share one admission-backed structural
floor. The router falls through to
`seon.render.block/data-panel`; that seam and the AI `data-prose` seam both
delegate to `seon.render.value`, where `seon.sci.admit/admit` is the only
nested-data admission walk (`src/seon/render.clj:166`,
`src/seon/render/block.clj:903-939`, `src/seon/render/value.cljc:173-398`). The
block expander no longer declares its own fallback before calling the router.

The pre-W3 `/data` raw renderer is deleted. `seon.render.data` now owns only
total cursor parsing and `get-in` selection; the route passes the selected
value to `seon.render.block/data-panel`. Its independent entry realization,
summary, HTML, id, and paging implementation and the tests that pinned those
details are gone. `/data`, routed units, and per-agent debug nodes therefore
all reach the same admitted HTML floor.

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
  `9b3be9d59cb0`. `d/pull '[*]` was not used as a
  recursive debug walk: non-component refs pull only `{:db/id ...}` while
  component refs may nest (`reference-code/datahike/test/datahike/test/api_test.cljc:266-267`).
  The debug reader instead reads one opened entity's current EAVT datoms and
  leaves every ref as a `{:db/id eid}` drill handle.
- http-kit: vendored commit
  `238a85cc555a`. The existing write-state drain
  completion remains the socket backpressure owner; W3 added no writer.
- First-party precedents: `src/seon/render/web.clj`'s exact four-route
  dispatcher and feed; the former `src/seon/render/data.clj` EDN path/offset
  cursor vocabulary;
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
uses W1's newest-first cap, restores ascending display order, and appends the
same flat `:seon.render.walk/elided` error shape as the context walk.

This is intentionally a generic EAVT read plus a generic reverse-ref query.
It is the exception allowed by ruling #8: 168 of 323 installed attributes are
outside every current family, and a debug view that used only family pull
selectors would lie by omission. The cost is conservative wake/read behavior:
while a debug tab is open, every commit can re-read its opened entity and
reverse refs. Direct cardinality-many values remain lazy from `d/datoms`, so
the floor consumes only the opened window instead of materializing the whole
attribute first. The generic `/data` entity root uses the same lazy direct read
without reverse refs instead of wildcard pull. Attribute discovery must still
scan that entity's current EAVT datoms; this on-demand wake/read cost does not
affect closed tabs or the curated page, and W5 should not copy the shape into
ordinary render invalidation.

## Protected-walk boundary

`src/seon/render/walk.clj` remained untouched by W3. W1 landed the protected
integration in `071ca1e50`: projection resolution now threads
`:seon.render/would-fall-to-floor?`, family-derived concrete selectors replace
wildcard context reads, and truncation is a loud flat error value. W3's debug
reader deliberately does not call W1's context `refs`: that function excludes
apparatus and family-less attributes by contract, while the on-demand debug
surface must expose both. Debug does reuse W1's cap ordering and error shape.

## Independent landing audit

The required post-landing adversarial pass found four W3-local defects, all
fixed in follow-up commits:

- entity and block floor calls without an explicit debug root could share a
  DOM id; the fallback root now uses `:db/id` or block name;
- composite map keys and set members reused their parent's id; each now gets a
  deterministic output-local entry address;
- map/set paging selected hash iteration order before sorting; selection now
  uses canonical printed order, preserving page membership across equivalent
  values; and
- a bare unit could display router provenance, viewer namespace, and admission
  caps as if they were domain data; `floor-unit` now removes those request
  fields.

The same pass caught unconditional JVM code in the `.cljc` namespace; schema
loading and SHA-256 identity are now CLJ reader branches, with a CLJS-local
stable hash fallback. Regressions cover distinct entity ids, composite entry
ids, insertion-order-independent pages, and request-data omission.

The post-W1 deletion audit found no remaining second floor. Its four boundary
findings were resolved before landing: curated agent pages now link the
always-available per-agent debug route; direct cardinality-many EAVT values are
lazy until the admitted floor opens a bounded window; explicit nil wording now
matches `floor-unit`; and the `/data` comment now states that the required
shared shell retains the ordinary agent feed even though `/data` has no
dedicated repaint derivation.

## Proof

Focused recurring gates, all green:

- `bin/test seon.render.data-test seon.render.web-test`: 39 tests, 169
  assertions, zero failures and zero errors, including the recurring real-HTTP
  5 MiB `/data` cap regression and the curated-page debug link.
- After replacing cursor `count` with bounded explicit-index access,
  `seon.render.data-test` independently passed 3 tests and 21 assertions.
- `bin/test seon.render.value-test seon.render-test seon.render.block-test
  seon.render.agent-test seon.render.data-test seon.render.web-test`: 92 tests,
  329 assertions, zero failures and zero errors.
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

After the `/data` deletion, the scratch cluster was re-proved against the
post-change handler. The cluster initially shared a JVM whose web dispatcher
had captured the old function; W3 hot-reloaded the four owner namespaces and
restarted only `w3-proof` before measuring. This proves hot-reloaded Vars plus
a fresh web handler in a cluster forked from the published current source; it
does not claim that file edits automatically synchronize sovereign clusters.

- `/data?entity=[:seon.ns/name w3.proof.large]&path=[:seon.ns/source]`
  returned 5,715 bytes with the one panel, `1,310,720 tokens`, `inspect`, loud
  `elided`, and a root-preserving handle.
- The three-level debug path
  `[:seon.cluster.agent/blocks 0 :seon.render.block/name]` returned 850 bytes
  with the block name and a stable drilled-node id.
- The apparatus path
  `[:seon.render.debug/reverse-refs :seon.db/user 0]` returned 3,527 bytes and
  exposed `:db/txInstant` under a stable id.
- The debug 5 MiB path
  `[:seon.cluster.agent/namespace :seon.ns/source]` returned 5,276 bytes with
  the token estimate, inspect affordance, loud elision, and stable id.

`w3-proof` was stopped after the proof. The in-app browser runtime reported no
available browser instances, so the live route and SSE proof used the real
local HTTP server directly; the recurring web suite independently exercises
the same paths over real sockets and validates the Datastar morph bytes.
The final post-audit repaint also verified that `GET /agent/root` contains
`/agent/root/debug`, the debug route remains a stable floor panel, and the 5
MiB `/data` response remains capped and loud.
