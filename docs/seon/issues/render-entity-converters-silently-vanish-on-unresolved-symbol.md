---
type: issue
status: open
severity: friction
tags: [issue, agent, web]
---

# Render entity converters silently vanish on unresolved symbols

## Overnight triage — 2026-07-23

**FOLD-INTO-UNIT — web slice 2.** One unresolved-symbol family across AI/HTML
render slots and routes belongs to the JVM web-render transition.

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

## Fresh-agent live evidence — 2026-07-24

After coherent source artifact publication restored fresh birth, agent
`open-fans-wish` returned HTTP 200 and its feed emitted a complete canvas.
The same frame rendered the `interaction-outcome` context surface as
`The authored function identity could not be acquired.` This is independent
of agent creation and does not displace that blocker repair.

Closure now also requires a current source apply followed by a fresh agent
feed in which `interaction-outcome` omits itself before terminal interaction
facts and renders the derived outcome after those facts exist; no unresolved
function error card may remain.
