# Frontend Design Principles

Seon uses a **Phosphor Terminal** aesthetic - warm blacks, cream text, amber accents. Think Lisp machine, not generic web app.

## Tailwind Build

We use **local Tailwind** (not CDN) with `@tailwindcss/typography` plugin:

```bash
npm run css:build   # Build once
npm run css:watch   # Watch mode for development
```

Theme defined in `resources/public/css/input.css`.

## Color Palette (Phosphor Terminal)

```css
/* Base colors (warm blacks) */
--color-base-950: #0d0d0c;  /* Deepest background */
--color-base-900: #1a1918;  /* Card backgrounds */
--color-base-850: #252422;  /* Elevated surfaces */
--color-base-800: #302e2b;  /* Borders, dividers */
--color-base-700: #3d3a36;  /* Subtle borders */

/* Text colors (cream, NOT white) */
--color-text-50: #faf9f7;   /* Primary text */
--color-text-200: #d4d0c8;  /* Secondary text */
--color-text-400: #8c8578;  /* Muted text */
--color-text-500: #6b6459;  /* Disabled text */

/* Semantic colors */
--color-signal: #f0b429;    /* Amber accent (primary) */
--color-success: #34d399;   /* Green */
--color-error: #f87171;     /* Red */
--color-warning: #fbbf24;   /* Yellow */
--color-info: #60a5fa;      /* Blue */
```

**NEVER use**: `bg-white`, `text-zinc-*`, `bg-gray-*`, `text-white`

## Typography

- **Font**: JetBrains Mono everywhere (`font-mono` on body)
- **Primary text**: `text-xs` (11px) for density
- **Max header size**: `text-lg` for page titles
- **Line height**: Tight (`leading-tight`)

## Spacing & Density

Terminal-dense, not spacious:

| Use | Not |
|-----|-----|
| `p-3` | `p-6` |
| `gap-4` | `gap-6` |
| `py-2` | `py-4` |

## Status Indicators

Use **dot + text**, not pill badges:

```clojure
;; Good: dot with label
[:div {:class "flex items-center gap-1.5"}
 [:div {:class "w-1.5 h-1.5 rounded-full bg-success"}]
 [:span {:class "text-xs text-success"} "running"]]

;; Bad: pill badge
[:span {:class "px-2 py-1 bg-green-100 text-green-800 rounded-full"} "Running"]
```

## Rendering in the active pod

The pod renders hiccup in `.cljs` and serializes with `seon.ui.html/->string`
(`src/seon/ui/html.cljc`). There is no `seon.web.components` component library on
the active track — that is the paused JVM `.clj` web stack. Build tiles directly
as hiccup and route content through `seon.render/block`:

```clojure
;; A status chip — dot + label (seon.ui.world/status-chip is the live example)
[:span {:class "flex items-center gap-1 text-xs font-mono"}
 [:span {:class "text-signal"} "●"]
 [:span {:class "text-text-200"} "running"]]

;; Markdown / source / data → hiccup via the ONE typed renderer
(seon.render/block :html {:seon.render/markdown llm-reply-string})  ; → md->hiccup
(seon.render/block :html {:seon.render/source   clj-source-string}) ; → clj->hiccup, server-side highlight
```

## Prose/Markdown Styling

LLM replies arrive as markdown and render through `seon.ui.markdown/md->hiccup`
(a CLJS-native hand-rolled subset — the full markdown-clj lib is JVM-only), or
via `seon.render/block` with `{:seon.render/markdown "…"}`. The Tailwind
`@tailwindcss/typography` plugin supplies dark-theme `prose` styling via the CSS
variables in `input.css` when a tile opts into `prose prose-sm max-w-none`.

## Anti-Patterns

| Bad | Why | Good |
|-----|-----|------|
| `bg-white` | Not warm | `bg-base-900` |
| `text-zinc-*` | Wrong palette | `text-text-*` |
| Pill badges | Generic | Dot + text |
| `p-6` padding | Too spacious | `p-3` |
| `text-base` size | Too large | `text-xs` |
| Rounded-full buttons | Generic | Sharp corners |

## Key Reference Files

| File | Purpose |
|------|---------|
| `docs/prds/namespace-ui/design-system.md` | Full design system spec |
| `src/seon/ui/world.cljs` · `header.cljs` | Live Phosphor hiccup tiles + the status bar (active pod) |
| `src/seon/render.cljs` | `block`/`slot` — the typed value renderer every tile shares |
| `resources/public/css/input.css` | Tailwind v4 theme source (`@theme` + `@source`) |
