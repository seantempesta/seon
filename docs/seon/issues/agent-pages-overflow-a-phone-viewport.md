---
type: issue
status: open
severity: cleanup
tags: [issue, web, render]
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
