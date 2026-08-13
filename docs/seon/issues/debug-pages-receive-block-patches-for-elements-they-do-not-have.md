---
type: issue
status: open
severity: friction
tags: [issue, render, web, class/n1, wave/ui-watchability]
---

# Debug pages receive block patches for elements they do not have

## Problem

Loading a debug page produces **200+** `PatchElementsNoTargetsFound`
warnings from `datastar.js`. The render proc publishes complete-page
block patches whose element ids exist on the normal namespace layout
but not on the two-pane debug layout, so every one of those patches is
derived, serialized, written to the SSE connection, and then discarded
by the client because its target selector matches nothing.

Two costs. The `:io` render proc and the connection-owned virtual
thread do real work per patch for no effect. And the console fills with
a wall of identical warnings, which is exactly the condition under
which a real console error becomes invisible.

## Evidence

Live browser, `http://127.0.0.1:7994/ns/my.agents.root/debug`
(cluster `default`, pid 31570), 2026-08-10, current HEAD.

One page load, filtering the console for the warning:

```text
[warn] PatchElementsNoTargetsFound
More info: https://data-star.dev/errors/…"datastar-patch-elements"
  }, "element": {} }, stack: Error
    at Number.he (http://127.0.0.1:7994/js/datastar.js:8:1880)
```

× 200+ occurrences, all identical, from a single load.

The debug page's own content is delivered correctly in the same
session — the left `:seon.render/ai` pane reaches 61,190 chars and the
right `:seon.render/html` pane reaches 251,233 chars across 348 walk
units within 5 s. So the feed is working; these warnings are the
*surplus* patches on top of the ones that land.

The layouts differ as expected from the source: the normal page paints
into `#surface-stream` with per-unit `seon-walk-unit` ids
(`src/seon/render/web.clj:262-264`), while the debug page paints into
`#debug-ai-<agent>` and `#debug-html-<agent>` panes
(`src/seon/render/web.clj:428-441,1041-1072`). The per-tab delivery
loop compares against the tab's last delivered map
(`src/seon/render/web.clj:692-804`) but the snapshot it compares is the
complete page's blocks, not the blocks the tab's layout actually
contains.

## Class

This is the **send-then-discover** class: the producer does not know
what the consumer can accept, so correctness is established at the
client by failing to find a target. The wanted structure is that a
tab's registered interest names the blocks its layout owns, so a patch
for a block the tab does not have cannot be constructed — rather than
being constructed, sent, and dropped.

## Owner

`src/seon/render/web.clj:692-804` (the per-tab delivery loop and its
interest registration), with the debug page layout at
`src/seon/render/web.clj:428-441,1041-1072` as the consumer whose block
set differs.

## Acceptance

- A debug page load produces zero `PatchElementsNoTargetsFound`
  warnings.
- A normal namespace page load produces zero of them as well.
- The debug page still fills both panes from the feed as it does today
  (61 KB AI, 251 KB HTML on the root agent) — this must not be fixed by
  sending less real content.
- One recurring proof asserts that every patch a tab receives targets
  an element that tab has, so the class cannot silently return.

## N1 disposition — 2026-08-12

Skipped because protected `src/seon/render/web.clj` belongs to the live N5
completion lane. Its exact edit is to derive each tab's subscribed block IDs
from the elements created by that page and filter/package deltas against that
set before delivery; the recurring proof must assert every patch target exists
in that tab's DOM.
