---
type: component
status: active
tags: [component, web, agent]
---

# Agent-Content CSS

> The CSS contract for agent-rendered hiccup: a base content layer for semantic HTML, a safelisted runtime utility vocabulary, and the agents-view canvas-preview clamp. All in `resources/public/css/input.css` (Tailwind v4, CSS-first — there is no tailwind.config.js).

## The problem it solves

Agents emit hiccup at RUNTIME, but Tailwind ships only classes scanned from SOURCE at build time — runtime-only utilities silently don't exist. And Tailwind preflight strips all semantic element styling, so classless `[:table …]` / `[:ul …]` / `[:h2 …]` render flat. Both fixed structurally in `input.css`:

## Mechanisms

- **Base content layer** (`@layer base`, currently scoped through the legacy
  implementation selectors in `input.css` plus `.seon-bubble`, `.markdown`,
  and `.seon-agent-content`): Phosphor-styled h1–h4, p, ul/ol/li,
  table/th/td, code/pre, blockquote, dl/dt/dd, hr, a, strong/em. The public
  path is "write semantic hiccup, zero classes needed"; do not teach the
  legacy selectors as architecture.
- **Safelist** (`@source inline(...)` block at the top of `input.css`): the curated utility vocabulary for data display — layout/spacing/typography/Phosphor colors/borders. Documented for agents in the `seon.render.canvas` ns docstring; `seon.render.canvas-test` asserts the docstring vocabulary ⊆ safelist.
- **Canvas-preview clamp**: the agents view's preview
  (`seon.web.datastar/agent-canvas-preview`) applies a 7rem clip with an
  `absolute inset-0` overlay link, not a wrapping `<a>`, because agent hiccup
  can contain links and nested anchors make the HTML parser split the row.
- **Scoped source scan** (`@import "tailwindcss" source(none)`): Tailwind v4 auto-detects content sources, which made it walk the whole repo — including the ~61k vendored `reference-code/` files — costing ~42s on every boot. `source(none)` disables auto-detection so only the explicit `@source` globs (our `src/`/hiccup + the `@source inline` safelist) are scanned; boot dropped to ~0.5s. Any new directory that emits classes must be added as an explicit `@source`.

## Editing rules

- New utility for agents → add to BOTH the `@source inline` safelist and the canvas docstring vocabulary, then `npm run css:build` (output.css is gitignored).
- New agent-content surface → add its container class to the `:is(...)` scope, never global element styles.
