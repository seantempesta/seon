---
type: issue
status: open
severity: cleanup
tags: [issue, web, render, class/n1, wave/visual-qa]
---

# Keep the page body from scrolling sideways on a phone

## Problem

At 375 px the value tables run off the right edge and the body scrolls
horizontally: attribute values are cut mid-token (`DEEP` for
`DEEPSEEK_API_KEY`, `4436`, `deep`, `root`), and the walk's path labels are
clipped. Wide content is not confined to its own scroll container.

## Evidence

`tmp/visual-qa/m-agent-scout.png`, `tmp/visual-qa/m-root.png`,
`tmp/visual-qa/m-debug-scout.png` (Chrome headless, 375x812, 2026-07-31).

## Owner

`resources/public/css/input.css` — the value/table surfaces.

## Acceptance

At 375 px the body has no horizontal scrollbar; each wide table or code block
scrolls inside its own `overflow-x` container and no value is visually
truncated without an affordance to see it.

## N1 disposition — 2026-08-12

Still open outside this lane's owned paths. The exact edit belongs in
`resources/public/css/input.css`: make the page grid shrinkable and give wide
table/code/value containers their own horizontal scrolling at 375 px, then
verify the body has no horizontal scrollbar. The N1 terminal fit does not
replace viewport layout.

## Fresh evidence — 2026-08-14

Walked at DESKTOP width (1280x720) rather than 375 px, which turns up two
adjacent layout facts for the same owner:

- The body no longer scrolls sideways at 1280 px (`scrollWidth` 1280 equals
  `innerWidth`), but the page grid uses only 787 px of it — columns measured
  525 px and 262 px — leaving the right ~38% of the window empty while content
  is squeezed and clipped. Run and message blocks sit in the 262 px column.
- Content is instead lost VERTICALLY: every `.seon-walk-unit` is
  `max-height: 160px; overflow: hidden`, silently discarding 55 of 138 units
  on the default root page and 11 of 38 on the drive agent page. Filed
  separately as
  [walk-units-hide-their-overflow-instead-of-eliding-it](walk-units-hide-their-overflow-instead-of-eliding-it.md),
  since it is a different axis with a different fix, but both live in
  `resources/public/css/input.css` and should be settled in one pass.

Full walk:
[ui-verification-2026-08-14](../../prds/context-generation/research/ui-verification-2026-08-14.md).
