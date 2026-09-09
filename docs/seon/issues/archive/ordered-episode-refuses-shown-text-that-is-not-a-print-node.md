---
type: issue
status: resolved
severity: blocker
tags: [issue, render, walk, history, class/total-boundary]
---

# `seon.render.walk/ordered-episode` refuses saved shown text that is not a print node

Observed 2026-09-09 01:44:29 on the reforked `default` (custody removal
`b4d665f89` adopted; evaluations store shown text per `ff9507c1b`), first
debug page load after reseed: a fault committed to Juniper —

```
seon.render.walk/ordered-episode violated its contract (invalid-input):
must be a print node at [[:seon.repl/s…
```

The walk's episode ordering still expects a print node under the REPL
emission where the evaluation entity now stores shown TEXT (PRD §15). Same
class as `render-proc-faults-in-history-entries-on-a-long`: the history
walk was not updated when the evaluation shape changed, and a render
threw instead of rendering a typed value (§2.4). Fix at the walk: accept
the §15 shape (shown text, out, error) and delete the print-node
expectation; regression on the canonical fixture with one stored
evaluation; the render never throws.


Resolved by `a90ed5cce`: the canonical stored-evaluation regression passes
1 test / 7 assertions, including unparseable shown text and error-only settlement.
Ordering accepts settlement facts without reconstructing a print node.
