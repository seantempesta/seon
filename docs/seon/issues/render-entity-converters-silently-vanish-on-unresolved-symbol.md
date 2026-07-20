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
