---
type: issue
status: resolved
severity: friction
tags: [issue, rendering, repl, mcp]
---

# Keep nested map sequences structurally readable

## Problem

The ordinary print floor promotes a nested sequence of maps to a Markdown
table even when that sequence is a value inside an enclosing map. The table
bytes then interrupt the enclosing EDN face, so the result is neither a
coherent structural value nor a standalone table.

## Evidence

On 2026-08-03, the curriculum's nested pull for `my.fs/read` was evaluated
through the live `default` cluster's SCI evaluator. Its top-level value is a vector
containing an arity map. The input refs remained structural EDN, but the two
output-ref maps became a Markdown table between `:output-refs` and the closing
map delimiter. `table-data` in `src/seon/print.cljc:251-278` selects table form
from the current node's map-shaped items, and `emit-node` applies that choice
recursively at `src/seon/print.cljc:492-498`.

## Owner

`seon.print` owns structural text emission and table selection.

## Acceptance

A sequence of maps nested inside another structural value renders as coherent
bounded structural text. Standalone tabular query results retain their useful
table face. A recurring regression covers both cases through the ordinary
print floor and the live SCI result projection.

## Resolution

Resolved by `4bc8104d8`. `seon.print` derives table presentation only for a
depth-zero sequence; the same sequence nested under `{:rows ...}` remains
structural text. `derived-table-is-one-text-and-html-face` pins both sides of
the class and passed in the focused print gate.
