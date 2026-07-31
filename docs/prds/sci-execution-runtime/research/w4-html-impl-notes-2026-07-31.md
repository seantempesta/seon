---
type: research
status: active
tags: [research, ui, render, datastar]
---

# W4 HTML implementation notes

## Landed units

- `1b8f86794` exposes the one `seon.render.walk/units` flattener and
  `(changed-at, branch, path)` ordering. `walk/prose` consumes it without a
  second sorter. The focused gate passed 11 tests and 48 assertions.
- `4cd9c4715` ports the namespace-page, mobile, two-pane debug, and stacked
  token-bar geometry into `resources/public/css/input.css`; `bin/css` passed.
- `53d2ff57b` persists the provider's raw usage map once on
  `:seon.ai.attempt/usage-edn`. Focused persistence and loop gates passed.
- `96cea1442` deletes the two-distance focus/rail renderer and corrects the
  stale apparatus-exclusion description. The agent renderer gate passed four
  tests and 11 assertions.
- `2c74a2353` inverts page membership onto the HTML walk, adds path-stable DOM
  order plus CSS recency ranks, restores the shell message bar, adds the
  client-only floor control, and gives the transient partial one fixed stream
  target.

## Scratch-cluster evidence

Cluster: `w4-html`, served at `http://127.0.0.1:7736` from a hot-reloaded Var
over its existing sovereign branch.

- `GET /` returned 200 and 21,789 bytes after the membership inversion.
- The initial page contained 13 walked units in depth-first path order, one
  `seon-rank-primary`, three `seon-rank-rail` units, two floor-marked units,
  `surface-message-bar`, and `surface-stream`.
- `GET /feed/root` delivered 21,205 bytes in three seconds. This directly
  falsifies the visual-QA report's zero-byte feed caused by empty block
  membership.
- The page walk and `page-of` at one database value reported 13 units and 14
  targets: exactly one target per unit plus the transient stream strip.
- `GET /agent/root/debug` returned 200 and 28,941 bytes with the byte-preserving
  AI `<pre>` on the left and every independently addressed HTML walk unit on
  the right.

The in-app browser backend was unavailable (`agent.browsers.list()` returned
an empty collection), so viewport, zero-network checkbox, and browser morph
timings remain unclaimed. HTTP, SSE, and live-REPL evidence above is valid but
does not substitute for those browser-only gates.

## Red gates exposed by the cut

The first post-inversion `seon.render.web-test` run failed only assertions
written around seeded block membership, fixed two-block page sizes, or fixed
two-event SSE reads. The H7 deletion lane began removing those seams, but was
interrupted at the required stop boundary before committing. Restoring block
membership would recreate the defect.

The provider-persistence lane's full turn suite also exposed an unrelated
existing failure at the terminal-refusal settlement boundary
(`loop.cljc:409`, `turn_test.clj:938-941`). It was not changed in W4.

## Unsettled exact dependencies

- The user assigned the AI labeling and sorter-diagnosis seam in
  `walk.clj` to the in-flight context-quality lane. The landed tree currently
  exposes no `walk/stable-prefix`; the debug bar must consume that derivation
  rather than invent a second breakpoint in `web.clj` or revive contribution
  bands. The exact left bytes currently come only from the complete
  `render/walk` assembly, while per-unit token estimates require its already
  rendered AI units. Rendering a second AI neighborhood would violate the
  one-render-per-unit ruling.
- The current `render.clj` namespace override constructs a single
  `<viewer-ns>/render-html` symbol without the architecture's same-schema
  program-graph query, while root is assigned `my.agents.root` and that
  namespace has no `render-html` Var. Carrying the viewer namespace through
  the walk is landed, but root-fleet acceptance needs the surviving resolver
  and root namespace facts to agree.
