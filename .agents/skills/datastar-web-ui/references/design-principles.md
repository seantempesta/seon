---
type: reference
status: active
tags: [reference, ui]
---

# Frontend design principles

Read this before changing the current JVM web UI's visual hierarchy, spacing,
typography, namespace-page layout, or debug layout.

## Contents

- [Maintained source](#maintained-source)
- [Palette](#palette)
- [Typography and density](#typography-and-density)
- [Current rendering boundary](#current-rendering-boundary)
- [Anti-patterns](#anti-patterns)
- [The product bar (owner, 2026-09-14)](#the-product-bar-owner-2026-09-14)
- [Target caution](#target-caution)

## Maintained source

`resources/public/css/input.css` owns source scanning, the utility safelist,
theme tokens, and semantic component CSS. Its `.cljs`, pod, and `my.canvas`
comments are stale residue beside the live `.clj` scan (no `canvas` namespace
exists under `src/`); do not infer a current pod or canvas API from those
comments (`resources/public/css/input.css:1-52`).

Build Tailwind through the maintained package scripts
(`package.json:10-12`):

```bash
npm run css:build
npm run css:watch
```

Do not introduce a CDN build.

## Palette

Use the maintained token values from
`resources/public/css/input.css:56-105`:

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
`text-2xs` definitions at `resources/public/css/input.css:58-64`; the fixed
message bar demonstrates the compact border, spacing, and type rhythm
(`.seon-bar`, `resources/public/css/input.css:991-1029`, rendered by
`message-bar-html`, `src/seon/render/web.clj:268`).

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

Current UI hiccup is JVM Clojure serialized through `seon.render.hiccup/->string`
(`src/seon/render/hiccup.clj:494`); HTML walk units receive stable wrappers in
`surface-html` (`src/seon/render/web.clj:377-399`). `seon.render.block/surface-id`
is the one stable DOM-ID derivation (`src/seon/render/block.clj:61-96`), and
delivery is owned by the render proc and feed (`render-step`,
`src/seon/render/web.clj:2618`; `feed`, `:2857`).

Build semantic hiccup with stable element IDs. Let the existing walk and block
owners produce source, transcript, problem, and data presentation; the shared
walk membership/order seam is `seon.render.walk/neighborhood` and
`ordered-episode` (`src/seon/render/walk.clj:751`, `:897`). Do not
restore old `seon.ui.*` CLJS namespaces or quarry-era block call signatures.

Design within the live web UI shapes:

- namespace pages place walk units in one ranked layout
  (`page-response`, `src/seon/render/web.clj:3205-3230`;
  `.seon-rank-layout`, `resources/public/css/input.css:1301`);
- debug pages carry a local `showEverything` signal and a grid of panes
  (`debug-response`, `src/seon/render/web.clj:3276`, signals at `:3288`,
  `:3366`; `.seon-debug`, `resources/public/css/input.css:1349`); and
- the exact live URLs come from the one route table
  (`src/seon/render/route.clj:5-31`).

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
  live route table (`src/seon/render/route.clj:5-31`), while current input is
  the fixed message bar and context-action POSTs (`message-bar-html`,
  `src/seon/render/web.clj:268`; `context-response`, `:3489`) plus
  browser-local Datastar signals;
- agent-owned `::renders`: the live blueprint contains only mailbox, turn and
  schedule (`graph-definition`, `src/seon/cluster/agent.clj:535-581`).

Revisioned packages and reconnect keyframes are current. The render proc builds
each package with a delta and complete keyframe, while each tab sends the delta
for a contiguous revision and the keyframe after a gap (`next-package`,
`src/seon/render/web.clj:1885-1911`; `package-patches`, `:1913-1923`).

Canonical namespace pages, root/agent aliases, and both debug variants are
current routes (`src/seon/render/route.clj:5-16`). Keep visual work inside those
current boundaries unless the owner explicitly resumes a named target.

## The product bar (owner, 2026-09-14)

The namespace page and the debug page are user-facing product surfaces,
judged as if shown in a funding presentation: someone who has never seen
Seon opens the page and understands within ten seconds what this agent is
doing, what it saw, and what went wrong. Every visual change is verified
by LOOKING at a screenshot of the live page at 1440 px and 700 px before
it is committed; the screenshot log (file → defects seen → change) goes in
the landing note. A page that is "green" but unread is unverified.

Information design for a UI that is scanned, not read:

- **Summary before detail.** The first screen answers the question; the
  evidence is below or on demand. A wall of collapsed headers is not a
  summary.
- **State in form, not only in words.** A dot plus a word for state; the
  semantic colours (`--color-success`, `--color-warning`, `--color-error`,
  `--color-info`) are reserved for state and never used as accent.
- **Faithful and formatted are two layers.** Stored bytes (prompt text,
  replies, shown text) are rendered exactly, tokenised into spans whose
  text concatenates back to the bytes; formatting sits in the spans and
  around the block, never inside the bytes. An agent's `:error` evaluation
  is history, styled as history, never as a page failure.
- **Repeated things compose as one object.** Cards, rows, and labels in a
  series share edges, baselines, and inner padding; a recurring element
  sits in the same place on each. Consecutive identical items fold into one
  row with a count.
- **Not everything is a card.** Border, fill, and radius say "separate
  object"; spend them on the one thing that needs lifting.
- **The page at rest is complete.** Everything meant to be read is present
  on first paint; "Loading…" placeholders that depend on the feed are a
  defect on the initial GET, and the initial GET stays under one second.
- **Words are design material.** Labels name what a person recognises
  (agent, namespace, turn, plan step), never the mechanism (`subject`,
  `viewer`, `output :seon.render/html`, lookup refs, db ids, `#inst`).
- **Structure encodes truth.** Turn ordinals, plan positions, and step
  states are shown because the order and the state are facts; decorative
  numbering or dividers are not.
- **Whole-page layout.** A normal scrolling document with a compact sticky
  header; no `body { overflow: hidden }` with an inner 90k-px scroller; no
  fixed column that leaves half of a 700 px screen empty.
