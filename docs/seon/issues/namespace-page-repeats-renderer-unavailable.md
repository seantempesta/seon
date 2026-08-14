---
type: issue
status: open
severity: friction
tags: [issue, render, web, wave/live-drive-render]
---

# Replace repeated renderer-unavailable placeholders with evidence

## Problem

The Drive 1 agent page repeatedly paints a generic failure string with no
shape, renderer, path, or diagnostic. The page is both ugly and
non-diagnostic.

## Evidence

The HTML for `/agent/drive-one-agent` contains many blocks whose complete
visible content is verbatim:

```html
<div class="seon-render-unavailable">renderer unavailable</div>
```

They appear between ordinary cluster, instruction, and toolkit namespace
blocks at paths including
`[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0
:seon.render.walk/neighbours 0]` and after multiple toolkit namespaces.
`src/seon/render/web.clj` explicitly substitutes this string when a walked
unit carries a failure without output.

The live Attempt 4 page at `/agent/drive-one-agent-attempt-4` reproduced the
same defect after the paid run settled. Its 36,963-byte HTML contained the
same complete visible placeholder 15 times, including immediately after the
configuration, getting-started instruction, toolkit namespaces, historical
message/run, and current message/run blocks:

```html
<div class="seon-render-unavailable">renderer unavailable</div>
```

## Owner

`seon.render.web/unit-html` and the render failure value supplied by the walk.

## Acceptance

An ordinary agent page contains no bare `renderer unavailable`. Every failed
block renders a bounded typed diagnostic naming its path, selected renderer,
and failure, and a regression asserts those facts rather than the placeholder.
