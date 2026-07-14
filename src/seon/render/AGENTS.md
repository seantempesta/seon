# src/seon/render — the ONE projection engine (with src/seon/ui as its view library)

**Read before editing:** `docs/seon/architecture/ui.md` (block/render/canvas/
slot/layout + the render engine contract), `architecture.md` glossary.
Skills: `ui-canvas` (the agent-facing canvas how-to), `datastar-web-ui`.

## The contract

- **One guarded recursive walker, two views.** Every projection in the
  system—the agent's prompt text (`:seon.render/ai`) and the human renderer
  selected by `:seon.render/html`—comes out of this engine. Its HTML response
  carries `:seon.render/hiccup`. A new
  way to surface data is a new block/render FN, never a new mechanism.
- **Renders are never stored.** A render is a function of the db at render
  time; if you're persisting one, you're building a cache the architecture
  says must key on basis-t — and probably don't need (measure first).
- **A render fn must never crash the walk** — guard it; a throwing render
  becomes a `:seon/error` surfaced in place.
- **`canvas.cljs` owns the low-level canvas render contract**: agents set their focal view by
  transacting hiccup OR a qualified fn symbol onto
  `:seon.render.canvas/content`. Its docstring and the CSS safelist must
  stay in sync — agents guess from the docstring.
- **Render-prominence law**: a COMPOSITION function's value is its worked
  example — compact/signature renders are for simple-call functions only.
  Compact namespace cards select by presence-sets (`::full-source` /
  `::with-tests`), never a map-of/density enum.
- Sizes rendered anywhere are TOKENS (`seon.ai.tokens/estimate`).

`sci.cljs` runs agent-authored render fns inside the cage — agent renders
are data in, data out, guarded like everything else. `value.cljs` /
`chat.cljs` / `default.cljs` are the typed-value renderers the block
renderer dispatches to.

`src/seon/ui/` is the canvas/layout library on top (agent view, header, markdown,
clojure, components) — hiccup only, placed into slots by layouts; keep
logic in render/derive fns, not visual components.
