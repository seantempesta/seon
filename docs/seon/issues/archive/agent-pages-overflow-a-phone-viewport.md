---
type: issue
status: resolved
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

## Verified at HEAD (2026-09-16, N1 verification)

**RESOLVED.** Live measurement on the running `default` cluster
(`http://127.0.0.1:7994`, pid 69622), viewport emulated at 375x812, three
pages, each read after the feed had painted:

```text
/agent/root         innerWidth 375  body.scrollWidth 375  overflowing elements 0
/agent/root/debug   innerWidth 375  body.scrollWidth 375  overflowing elements 0
                    max(pre.scrollWidth - pre.clientWidth) = 0
/ns/my.agents.root  innerWidth 375  body.scrollWidth 375  overflowing elements 0
```

`overflowing` counts every element whose bounding rectangle extends past
`innerWidth`. The body no longer scrolls sideways at 375 px and no wide
container escapes its own box, which is this note's acceptance. The value
surfaces now wrap: every `pre`/`code` on the debug page computes
`white-space: pre-wrap` (`resources/public/css/input.css:874`).

The separate VERTICAL loss recorded in the 2026-08-14 section belongs to
[walk-units-hide-their-overflow-instead-of-eliding-it](walk-units-hide-their-overflow-instead-of-eliding-it.md)
and is unaffected by this closure.
