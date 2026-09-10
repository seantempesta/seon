---
type: issue
status: open
severity: friction
tags: [issue, tooling, browser]
---

# Browser observation has no accessible window

On 2026-09-09 during the turn namespace lane, CUA `getState` returned an
empty browser list despite listing Chrome as a running app. Selecting
`com.google.Chrome` returned `cgWindowNotFound`. No browser paint could be
observed. Local debug HTTP requests succeeded; they do not verify paint.

Restore an accessible browser/window surface in the computer-use service,
then observe the default debug page after the required schema refork.
The lane must not claim a screenshot or page paint from an HTTP response.

Reproduced by loop-proof on 2026-09-09: CUA `getState` reports zero browser
surfaces; `getApp("com.google.Chrome")` returns `cgWindowNotFound` (-10005).
The three controls were exercised by their actual curl POST routes. This
does not establish browser repaint.

Page-review recheck at 2026-09-09 18:44 reproduced the same result twice.
Earlier in this lane Chrome's native surface supplied real screenshots and AX
text for the scratch debug page, and the lane closed its own tab. After the
owner's grammar correction, the service still listed Chrome as running but
returned `cgWindowNotFound` and no browser surfaces. The final grammar has
canonical HTML and saved-prompt evidence, without a new browser-paint claim.
