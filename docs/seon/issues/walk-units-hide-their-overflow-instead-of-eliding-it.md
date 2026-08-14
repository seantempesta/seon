---
type: issue
status: open
severity: blocker
tags: [issue, render, web, class/n1, wave/visual-qa, wave/live-drive-render]
---

# Elide a walk unit's excess instead of hiding it with CSS

## Problem

Every `.seon-walk-unit` is a fixed `max-height: 160px` box with `overflow:
hidden`. Content taller than that is silently discarded: no scrollbar, no
"more" affordance, no elision value, no count. A reader has no way to know
anything was omitted, and no way to reach it.

This is the project's own recurring failure class — absence of signal reading
as health — expressed in CSS, and it defeats the elision-value contract
(omitted detail must be ordinary data carrying count, path, and requery
identity; never bare truncation).

## Evidence

Measured live 2026-08-14 in the browser at 1280x720:

```javascript
getComputedStyle(unit) // → maxHeight "160px", height "160px",
                       //   overflow "hidden", overflowY "hidden"
```

Clipped units (those whose `scrollHeight` exceeds `clientHeight`):

| Page | Clipped | Total units |
|---|---|---|
| `http://127.0.0.1:7994/` (default root, HEAD) | 55 | 138 |
| `http://127.0.0.1:7994/agent/root` | 55 | 138 |
| `http://127.0.0.1:55156/agent/drive-one-agent-attempt-5` | 11 | 38 |

Worst observed on the drive agent page — 80% of the content is unreachable:

| Walk path | clientHeight | scrollHeight |
|---|---|---|
| `[… /neighbours 0 … 2]` (`my.background`) | 158 px | 800 px |
| `[… /neighbours 0 … 5]` (`my.message`) | 158 px | 800 px |
| `[… /neighbours 0 … 8]` (`my.run`) | 158 px | 614 px |
| `[… /neighbours 0 … 6]` (`my.note`) | 158 px | 428 px |
| `[… /neighbours 0 … 1]` (getting-started instruction) | 158 px | 372 px |
| `[… /neighbours 0 … 0]` (configuration) | 158 px | 279 px |

Visible consequence in the screenshot: a run block's sentence stops mid-phrase
at "Nothing was retried, and nothing it asked for" with nothing indicating a
remainder exists.

Related but distinct axis: `agent-pages-overflow-a-phone-viewport` covers
HORIZONTAL overflow at 375 px. This note is VERTICAL clipping at desktop
width, and the two want the same owner's attention in one pass.

Full walk:
[ui-verification-2026-08-14](../../prds/sci-execution-runtime/research/ui-verification-2026-08-14.md).

## Owner

`resources/public/css/input.css` for the box, and the block producer for the
missing elision — the fit decision belongs to the render profile through
`seon.print/fit`, not to a CSS height.

## Acceptance

No rendered walk unit has content taller than its box without an affordance:
either the unit scrolls in its own container, or the producer fits it and
emits an elision value carrying the omitted count, path, and requery
identity. A recurring proof asserts that a unit's rendered content is either
fully visible or accompanied by an elision value — never silently cut by
`overflow: hidden`.
