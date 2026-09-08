---
type: issue
status: superseded
severity: friction
tags: [issue, render, web, print]
---

# The `/data` route has no presentation bound for a long string

## Problem

`/data` is the paged navigation surface into one nested value. A COLLECTION
there is windowed by `:seon.render.value/max-collection` (8 entries, with a
pager and a breadcrumb back to the root). A STRING has no such window: until
2026-09-07 it was clipped incidentally by
`:seon.config.eval.result/max-string`, a STORAGE cap applied during
admission.

That cap is gone (elision happens only where AI context is generated, and
HTML renders the stored value with no limits), so a 5 MiB string attribute is
now served whole in one response. Measured by
`seon.render.web-test/data-serves-a-five-megabyte-attribute-whole-with-its-handle`:
the body is larger than 5 MiB where it used to be under 300 KB.

That is the ruled behavior for the VALUE — nothing is hidden, and the page
tells the truth — but `/data` is a navigation surface, and every other shape
it shows is paged. A string is the one member that is not.

## What to do

Give the string the same treatment the collection already has: a declared
presentation window on the `/data` route (offset, length, pager, and the
existing breadcrumb identity), so navigating a long string is paging rather
than downloading. The bound belongs to the route's own presentation config
beside `:seon.render.value/max-collection` — never to an admission cap, which
is what coupled the two decisions in the first place.

Grounding:
[the storage-bound landing note](../../../prds/context-generation/research/storage-bound-landing-2026-09-07.md).

## Disposition — 2026-09-08

Superseded by the turn PRD §15 and AGENTS.md §2.4: HTML serves the complete
value, and presentation elision belongs only to the AI render functions or
value renderer. The proposed route-level string bound would introduce another
clipping owner. The existing five-megabyte whole-value regression cited above
asserts the ruled behavior; no protected web file was changed by this sweep.
