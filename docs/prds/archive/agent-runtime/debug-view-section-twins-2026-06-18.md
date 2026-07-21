---
type: prd
status: draft
tags: [prd, agent, web, flow]
---

# Debug-view redesign — the right pane renders section html twins

## TL;DR

The debug inspector's two panes have silently diverged. The LEFT pane is
the agent's exact context (`assemble-context` — the one composer). The
RIGHT pane is a *legacy* per-entity card view (`visible-entities`, the
last-64-by-tx-time renderable-entity window) that no longer corresponds
to anything in the prompt and is flooded with the core's own test
captures.

The fix is the generalization we want anyway: **every context section
gains an optional `:seon.render/html` twin, and the right pane renders
those twins in render order — mirroring the left.** This:

- makes the two panes mirror each other again (they share the section
  set, so they cannot diverge — same property the left already has);
- deletes the test-entity flood for free (core `:seon.test` rows are not
  a section, so they leave the right pane the moment it stops rendering
  the entity window);
- gives the downstream its rich panel (tables, images) as **one section
  row** with an `:seon.render/ai` twin (what the agent reads) +
  `:seon.render/html` twin (what the human sees) — the separate
  `debug-panel` render surface (#31) dissolves into this.

## Verified current reality (live, agent `RDg-2606181337`, 2026-06-18)

Observed against the running pod, not inferred:

- The prompt is exactly 7 non-blank sections: `system · namespaces ·
  your-entity · live-tile · transcript · inventory · prompt`
  (121,303 chars / ~30K tokens). The left pane IS these bytes —
  `assemble-context` is called by both the prompt path and the inspector
  (`ctx.cljs:2466`), so the left cannot diverge from what the LLM sees.
- The right pane shows **64 entities** (the `renderable-entities` window,
  capped at 64 by tx-time — `render.cljs:332`), of which **55 are
  `:seon.test`**, 5 messages, 4 evals. The left shows 7 sections. The two
  panes are showing different things; the inspector docstring's claim
  "same set of entities" (`inspector.cljs:7`) is now false.
- The store holds **75 namespaces (23 test) and 228 `:seon.test`
  entities** — the pod indexes the core's own test suite at boot
  (`!indexed-test-vars`, `client.cljs:887`); `:seon.test` is a renderable
  kind (it carries a `render-fn`), so the entity window fills with core
  test captures.
- Tests are NOT in the prompt — the left is clean (only 3 incidental
  "test"-named core namespaces such as `seon.test.runner`; no `-test`
  suites). The flood is confined to the right pane's stale view.
- The section `:seon.render/html` twin already exists in the schema
  (`ctx.cljs:99-105`) and is decoded (`decode-section`,
  `ctx.cljs:2337`) but is **rendered nowhere** — `render-section`
  (`ctx.cljs:2376`) reads only `:seon.render/ai`. The twin is dormant.
- The ONE section whose html does render today is the live tile (the
  "Good afternoon" welcome card at the top of the right pane) — and it
  renders through a bespoke path (`render-agent-tile`), not the section
  mechanism. This redesign generalizes the tile's html treatment to all
  sections.

Separate flag (not this PRD, tracked below): `namespaces` is 107,415 of
the 121,303 prompt chars — **88% of the entire context** is rendering all
54 core namespaces. That is the real token hog and is independent of the
test issue.

## The redesign

### Principle

A context section is "a thing with an `:seon.render/ai` twin and an
optional `:seon.render/html` twin." The left pane renders the `ai` twins
(it already does — that IS the prompt). The right pane renders the `html`
twins, in the same render order. One section set, two surfaces — the
turtles-all-the-way-down shape, and the same non-divergence guarantee the
left already enjoys.

The legacy `visible-entities` / per-entity-card path on the right is
deleted. The genuinely useful part of it — the agent's own
turns/evals/messages — is preserved as the **`transcript` section's html
twin**, which renders those rows as cards. Core `:seon.test` captures are
not a section, so they simply stop appearing.

### Implementation steps

All line numbers verified 2026-06-18.

1. **Render the dormant twin in the composer.** In `assemble-context`
   (`ctx.cljs:2465`), alongside `:seon.render/section-texts`, produce
   `:seon.render/section-html` — for each non-blank section resolve its
   `:seon.render/html` slot through the EXISTING `seon.render/html-render`
   (`render.cljs:166`, already handles symbol | literal-hiccup | else) and
   wrap in the same throw-to-banner guard `render-entity-html` uses
   (`render.cljs:462-470`: a throwing twin degrades to a legible banner,
   never nil — vanish is banned). Sections with no `:seon.render/html`
   slot contribute nothing (the right pane simply has no card for them).
   Add `:seon.render/section-html` to `::assemble-response`.

2. **A `transcript` html twin.** Give the transcript section a
   `:seon.render/html` fn that renders the agent's turns/evals/messages as
   the cards the old right pane showed (lift the useful card markup from
   `render-entity-hiccup` / the snapshot's `cards` builder,
   `inspector.cljs:311-324`) — scoped to the agent's own rows, so no core
   `:seon.test` captures. This is where "render order" lives: the
   transcript card list grows naturally as the agent works.

3. **Right pane renders section html.** In the debug `snapshot`
   (`inspector.cljs:292`) replace the per-entity `cards` build with the
   composer's `:seon.render/section-html` (in render order). In
   `debug-payloads` (`inspector.cljs:1567`) emit those as the right-pane
   morph fragment. The existing reactive path (`push-agent!` →
   `schedule-push!` → the tx-listener) re-renders on every commit
   unchanged — no tx-listener edit.

4. **Delete the legacy entity-window path on the right.** Once the right
   pane renders sections, `visible-entities` / `renderable-entities` /
   `renderable-kinds` are no longer the debug view's source. Audit other
   callers before removing (the grid tile uses `render-agent-tile`, not
   this path) and delete what only the old right pane used. The live-tile
   welcome card continues to render through its bespoke `render-agent-tile`
   path, mounted above the section cards — it is NOT a section twin (the
   `:live-tile` core-default carries only `:seon.render/ai`; `transcript`
   is the only section with an html twin after this work).

5. **Teach the html twin in system-text (small).** The `THE RENDERING
   SYSTEM` paragraph (`ctx.cljs:898-901`) already says "two surfaces" —
   extend it one sentence so the agent knows a *section* (not just the
   tile) can carry an `:seon.render/html` twin, and that's where rich
   panels (tables, images, SVG) go.

### What the downstream consumes (zero further seon change)

The downstream adds one section through the existing `add-section!` path:

```clojure
{:seon.ctx/name    :my-inspector
 :seon.ctx/priority 33
 :seon.render/ai   'my.feature/section-ai     ; the agent reads this
 :seon.render/html 'my.feature/section-html}  ; the human sees this panel
```

The `html` fn returns arbitrary hiccup — a table, `[:img {:src "data:…"}]`,
inline SVG. One row, both audiences. The standalone `debug-panel` render
surface (ask #31) is no longer needed; the panel is a section twin.

Note this lands the panel in the `⚙ debug` overlay (the developer
inspector), which the downstream has confirmed is fine for now. A polished
consumer-view pane beside chat would be a later, separate surface.

## Tracked follow-ons (not this PRD)

- **Core test entities should not flood the agent store.** 228
  `:seon.test` rows + 23 test namespaces are indexed at boot
  (`!indexed-test-vars`, `client.cljs:887`). The right-pane redesign hides
  them, but they still inflate the `inventory` section counts and the
  store. Decide: stop indexing the core's own tests into the agent store,
  or make `:seon.test` a non-renderable kind. The agent should only ever
  see ITS OWN tests, if any.
- **`namespaces` is 88% of the prompt.** 107K of 121K chars renders all 54
  core namespaces. This is the dominant token cost and the higher-value
  context fix; it is independent of the debug view and deserves its own
  pass (recency/relevance windowing, depth control, or on-demand
  `render-namespace` instead of always-full).

## Size and risk

Steps 1-3 are the core: one composer addition (reusing `html-render` +
the existing banner guard), one transcript twin (lifted from existing card
markup), two inspector wiring spots. Step 4 is deletion. Low risk — every
new piece reuses a proven seam, and the non-divergence guarantee is
structural (both panes read the same section set). The live-tile path is
untouched; it just becomes the first section twin rather than a special
case.
