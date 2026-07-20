---
type: issue
status: open
severity: friction
tags: [issue, agent, web]
---

# Render entity converters silently vanish on unresolved symbols

## Problem

`src/seon/render.cljs:352` and `:621` resolve registered render symbols via
`seon.eval/lookup-value` and silently render NOTHING when resolution
returns nil — no legible line, no warning, no fault. Block slots, canvas,
and execution all surface the miss legibly; these two sites are the gap.

## Expected owner

Part of the single unresolved-symbol semantics proposed in
[[../../prds/source-cleanup/research/envelope-symbol-conformance-2026-07-20]]
§B: nil-as-value surfaced legibly, one generalized unresolved-symbol
warning derivation. Rides source-cleanup stage 5 (or the data-browser
implementation, whichever touches render.cljs first).

## Grounded boundary and sibling defect

[[../../prds/source-cleanup/research/unresolved-render-symbol-boundary-2026-07-20]]
(`4f4dbd95`) freezes the dependency order and one semantic: a selected symbol
must resolve to a function; absent and non-function values become the same
visible standard error value in AI and HTML and never fall through to generic
rendering. The current render error-card calls also use unregistered
`:seon.error/symbol`, `:seon.error/where`, and `:seon.error/hint` keys while
omitting the required `:seon.error/kind`. That schema mismatch is part of the
same owner cut: registered presentation keys live inside
`:seon.error/data`, and the standard message/kind/data error shape feeds both
views.

Closure additionally requires the canvas-only unresolved warning to be
replaced by one derived, self-healing `:unresolved-symbol` family over final
context slots, canvas, activated schema properties, and Stage-4 route rows,
with the focused and frozen live proofs named by the boundary report.
