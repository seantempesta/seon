---
type: issue
status: open
severity: defect
created: 2026-09-24
tags: [issue, render, performance, agent-platform]
---

# The value renderer sorts every visited map whole before choosing children

`seon.render.value/value-node*` orders a map's or set's WHOLE membership with
`print/compare-values` before it keeps `:seon.render.profile/max-children`
(`src/seon/render/value.clj:482-488`, "Order whole members before choosing
which children to retain"). The AI render therefore costs O(n log n) in the
size of every map it visits, although it shows at most 32 children of each.

## Evidence (default, JVM `eval_clj`, scratch ns `lane-fix-runner-print-probe`, 2026-09-24)

`seon.test.runner/report-value` under the default agent profile
(token budget 15000, max-children 32):

| value | ms | output chars |
|---|---|---|
| map of 10,000 integer entries | 82 | 498 |
| map of 100,000 integer entries | 959 | 500 |
| map of 100,000 keyword → 10-vector entries | 824 | 1,272 |
| vector of 1,000,000 integers | 5.5 | 364 |
| vector of 200 × 1 MiB strings (>200 MB printed) | ~116 | 25,626 |

The profile line: `seon.print/compare-values ×2,176,864, 1090 ms` for one
batch of five renders. A vector is bounded by the profile; a map is not.

## Why it matters

Every bounded surface that routes a large map through the renderer inherits
this cost: the test runner's gate message and stored failure text
(`seon.test.runner/report-value`), REPL shown text and fault evidence. A test
failure whose actual is a program overlay map of a million entries would
take seconds to report although the report is a few KB.

## Done when

The rendered order stays stable and total, and rendering a map costs the
profile's bound (children shown), not the map's size: e.g. select the first
`max-children` members in order with a bounded selection instead of a full
sort, or keep sorted/array-map order as given. Regression: a 1,000,000-entry
map renders under the default profile in well under 100 ms.
