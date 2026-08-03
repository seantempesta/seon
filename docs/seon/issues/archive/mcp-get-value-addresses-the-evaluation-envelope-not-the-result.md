---
type: issue
status: resolved
severity: friction
tags: [issue, mcp, repl, render]
---

# Make an MCP digest address the evaluated value directly

## Problem

Door `eval_clj` stores the whole `seon.sci.eval/evaluate` result map as the
MCP artifact. `get_value` therefore defaults to drilling that evaluation
envelope, not the value whose printed face and digest the caller just saw.
The useful path is the undocumented `[:seon.sci.admit/value]`.

This makes the natural root drill actively expensive and confusing. On
2026-08-03, door `(vec (range 50000))` returned digest
`adb10ba93c250287c21b6b2ff0f7196bd97efb1f0ac35cc2947bc0fdf1a85973`.
`get_value` with its default `path []` returned the entire evaluation-shaped
map, clipped a nested `result-edn` string, and minted a second 689,315-byte
artifact under digest
`3260649f3b2286972b6f2aff817911cbd5bef13f3dac9559ee4c43e49f71f20a`.
The same source digest with path `[:seon.sci.admit/value]` returned the
expected structural page.

The same envelope economics misclassify small faces as large results. Defining
`dogfood-double` printed only
`#'my.agents.mcp-dogfood/dogfood-double`, yet the response reported
`windowed? true`, a 4,165-byte artifact, and a retrievable digest.

## Owner

The one value-artifact seam shared by `seon.cluster/mcp-project` and
`seon.cluster/mcp-get-value`; the MCP bridge remains a thin adapter.

## Acceptance

- The digest presented for an evaluated value drills that value at `path []`.
- A root drill does not create a second artifact merely by reading the first.
- Evaluation metadata remains available under an explicit data path or
  sibling observation rather than wrapping the addressed value.
- A tiny returned face does not become `windowed? true` solely because the
  internal evaluation envelope exceeds the blob threshold.
- Recurring MCP tests cover root, nested, and offset drills without teaching
  callers an implementation-only `:seon.sci.admit/value` prefix.

## Resolution

Resolved by commit `4f299cba4`. `seon.cluster/mcp-project` now stores an
evaluation result's print node as the value artifact while retaining the
evaluation record beside the text face. Non-evaluation values keep the same
artifact semantics.

The focused and drill-owner gate passed 16 tests and 60 assertions across
`seon.cluster.mcp-test` and `seon.render.value-test`. Live door evaluation of
`(vec (range 50000))`, after `(require 'seon.cluster :reload)`, returned a
digest whose root window began `[0 1 2 3 4 5 6 7]`; offset 7 began
`[7 8 9 10 11 12 13 14]`; projecting either drill minted no second digest.
