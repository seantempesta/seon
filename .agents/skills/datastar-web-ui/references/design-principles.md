# Frontend design principles

Read this when changing the current JVM web UI's visual hierarchy, spacing,
typography, or theme tokens.

Seon uses a Phosphor Terminal aesthetic: warm blacks, cream text, amber
accents, monospace type, and information-dense layouts. Think Lisp machine,
not a generic dashboard.

## Contents

- [Maintained source](#maintained-source)
- [Palette](#palette)
- [Typography and density](#typography-and-density)
- [Current rendering boundary](#current-rendering-boundary)
- [Anti-patterns](#anti-patterns)
- [Target caution](#target-caution)

## Maintained source

`resources/public/css/input.css` owns the theme tokens, source scan, utility
safelist, and component CSS. It currently scans fresh `.clj`/`.cljc` sources
and retains some stale CLJS/`my.canvas` comments and safelist entries from the
deleted UI. Treat those comments as quarry; do not infer that the pod or
canvas API exists.

Build local Tailwind with the repository scripts:

```bash
npm run css:build
npm run css:watch
```

Never introduce a CDN build.

## Palette

Use the maintained token names:

```css
/* warm base */
--color-base-950: #0d0d0c;
--color-base-900: #1a1918;
--color-base-850: #252422;
--color-base-800: #302e2b;
--color-base-700: #3d3a36;

/* cream text */
--color-text-50: #faf9f7;
--color-text-100: #ece9e3;
--color-text-200: #d4d0c8;
--color-text-400: #8c8578;
--color-text-500: #6b6459;

/* semantic */
--color-signal: #f0b429;
--color-success: #34d399;
--color-error: #f87171;
--color-warning: #fbbf24;
--color-info: #60a5fa;
```

Do not use `bg-white`, `text-white`, `text-zinc-*`, or `text-gray-*`.

## Typography and density

- Use JetBrains Mono/the maintained monospace stack everywhere.
- Use `text-xs` as the primary dense size and `text-2xs` for metadata.
- Keep page titles at or below `text-lg`.
- Prefer `p-3`, `py-2`, and `gap-4` over spacious marketing-page rhythm.
- Use one-pixel base borders and compact cards.
- Use dot plus text for status, never pill badges.

```clojure
[:span {:class "flex items-center gap-1 text-xs font-mono"}
 [:span {:class "text-signal"} "●"]
 [:span {:class "text-text-200"} "running"]]
```

## Current rendering boundary

Current UI hiccup is JVM Clojure and serializes through
`src/seon/render/hiccup.clj`. Page blocks and surfaces are derived by
`src/seon/render/block.clj`; delivery is owned by
`src/seon/render/web.clj`.

Build semantic hiccup with stable element IDs. Let server-side rendering
produce markdown/data/source presentation through the existing block owners;
do not restore old `seon.ui.*` CLJS namespaces or `seon.render/block` call
signatures from `src-old/`.

The current generalized value renderer lives under `src/seon/render/`. Inspect
the owning function before choosing a data shape; do not assume the deleted
tagged renderer's accepted values survived unchanged.

## Anti-patterns

| bad | why | use |
|---|---|---|
| white/zinc/gray palette | fights the warm terminal palette | `base-*`, `text-*`, semantic tokens |
| pill badges | generic dashboard language | dot plus status text |
| `p-6` everywhere | destroys information density | `p-3` |
| large body type | reduces scan density | `text-xs` |
| decorative gradients/shadows | obscures structure | borders and tone steps |
| arbitrary runtime utilities | Tailwind may not emit them | maintained source/safelist or semantic class |
| old canvas control classes | no fresh canvas API exists | tabled UI design |

## Target caution

Broader UI restoration is tabled at
`docs/prds/sci-execution-runtime/plan/README.md:1087-1097`. Read
`docs/seon/architecture/ui.md` as target design, not current implementation.
Keep visual work inside the routes and block surfaces that
`src/seon/render/web.clj:734-840` actually serves.
