---
type: defect
status: open
severity: blocker
tags: [render, walk, history, debug-page, class/total-boundary]
---

# The render proc faults in `seon.render.walk/history-entries` on a Long

Observed 2026-09-08 16:00 on a freshly reforked `default` (commit
`6aa081b3…`), Juniper reseeded through the fixture, first debug page load:

```
SEON CORE FAULT (dev panic): Don't know how to create ISeq from: java.lang.Long
:seon.error/proc  :seon.render.web/render
:seon.error/cid   :seon.render.web/context
:seon.instrument/fn seon.render.walk/history-entries
```

The fault fires on the render proc's `::context` demand (the debug page's
agent-context request). `history-entries` receives a Long where it walks a
sequence — most likely an evaluation/turn attribute whose shape changed
under the turn cut (`:seon.turn/*` rows, `:seon.eval/*` ordinal or basis)
while the walk still expects the run-form vector. The fault's own data was
capped (`:seon.eval/missing :over-bound`), so the offending value is not in
the fact; reproduce with `(seon.render.walk/history-entries db agent)` on
`default` and read the complete envelope.

A render must never throw (AGENTS §2.4): whatever the shape, the history
renders a typed elision naming the attribute, and the proc keeps serving
the page. Fix the shape at its writer AND make the walk total.
