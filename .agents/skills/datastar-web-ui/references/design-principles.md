# Frontend design principles

Read this before changing the current JVM web UI's visual hierarchy, spacing,
typography, namespace-page layout, or debug layout.

## Contents

- [Maintained source](#maintained-source)
- [Palette](#palette)
- [Typography and density](#typography-and-density)
- [Current rendering boundary](#current-rendering-boundary)
- [Anti-patterns](#anti-patterns)
- [Target caution](#target-caution)

## Maintained source

`resources/public/css/input.css` owns source scanning, the utility safelist,
theme tokens, and semantic component CSS. Its `.cljs`, pod, and `my.canvas`
comments are stale residue beside the live `.clj` scan; do not infer a current
pod or canvas API from those comments
(`resources/public/css/input.css:1-52`).

Build Tailwind through the maintained package scripts
(`package.json:10-12`):

```bash
npm run css:build
npm run css:watch
```

Do not introduce a CDN build.

## Palette

Use the maintained token values from
`resources/public/css/input.css:54-103`:

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

Do not use white, zinc, or gray utility palettes in place of these tokens.

## Typography and density

The maintained font and dense-size tokens are the monospace stack and
`text-2xs` definitions at `resources/public/css/input.css:57-64`; the fixed
message form demonstrates the compact border, spacing, and type rhythm at
`resources/public/css/input.css:732-773`.

- Use the maintained monospace stack.
- Use `text-xs` for dense body text and `text-2xs` for metadata.
- Keep headings compact.
- Prefer tight padding and one-pixel borders over marketing-page whitespace.
- Use dot plus text for status rather than pill badges.

```clojure
[:span {:class "flex items-center gap-1 text-xs font-mono"}
 [:span {:class "text-signal"} "●"]
 [:span {:class "text-text-200"} "running"]]
```

## Current rendering boundary

Current UI hiccup is JVM Clojure serialized through
`src/seon/render/hiccup.clj:470-500`; HTML walk units receive stable wrappers in
`src/seon/render/web.clj:241-350`. `seon.render.block/surface-id` is the one
stable DOM-ID derivation (`src/seon/render/block.clj:72-107`), and delivery is
owned by `src/seon/render/web.clj:497-804`.

Build semantic hiccup with stable element IDs. Let the existing walk and block
owners produce source, transcript, problem, and data presentation; the shared
walk membership/order seam is `src/seon/render/walk.clj:693-876`. Do not
restore old `seon.ui.*` CLJS namespaces or quarry-era block call signatures.

Design within the live page shapes:

- namespace pages use a primary/rail walk-unit layout and a local
  `showEverything` signal (`src/seon/render/web.clj:1011-1039`,
  `resources/public/css/input.css:1237-1312`);
- debug pages use two panes for `:seon.render/ai` and `:seon.render/html`
  (`src/seon/render/web.clj:1041-1072`,
  `resources/public/css/input.css:1314-1389`); and
- the exact live URLs come from the one route table
  (`src/seon/render/route.clj:5-27`).

Inspect the owning renderer before choosing a data shape. Do not assume a
deleted tagged renderer's accepted values survived unchanged.

## Anti-patterns

| bad | use |
|---|---|
| white/zinc/gray palette | `base-*`, `text-*`, semantic tokens |
| pill badges | dot plus status text |
| spacious padding everywhere | dense spacing consistent with the live shell |
| large body type | `text-xs` and `text-2xs` |
| decorative gradients or shadows | borders and tone steps |
| arbitrary runtime utilities | maintained source/safelist or semantic CSS |
| old canvas control classes | no executable control API; mark proposals **[TARGET]** |

## Target caution

Do not use visual work to imply that target runtime mechanisms already exist.
The following remain **[TARGET]**:

- generalized `my.canvas` controls and `/call`: neither appears in the exact
  live route table (`src/seon/render/route.clj:5-27`), while current input is
  the fixed message form and page-local floor checkbox
  (`src/seon/render/web.clj:132-169,1027-1037`);
- agent-owned `::renders`: the live blueprint contains only mailbox and turn
  (`src/seon/cluster/agent.clj:240-264`); and
- revisioned packages and reconnect keyframes: current delivery is complete
  snapshots plus per-tab comparison, and the replacement protocol is marked
  **[TARGET]** at
  `.agents/skills/seon-flow-architecture/references/render-delivery.md:55-94`.

Canonical namespace pages, root/agent aliases, and both debug variants are
current routes (`src/seon/render/route.clj:5-16`). Keep visual work inside those
current boundaries unless the owner explicitly resumes a named target.
