---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, web]
---

# Pass the agent entity through the renderer's system-input key

## Problem

The execution runtime builds the canvas block with `:seon.render/entity`, but
selected renderers receive the declared `:seon.render/system-input`, whose
entity field is `:seon.agent/entity`. The welcome renderer therefore rejects a
missing agent entity and the canvas falls back to an error card.

## Evidence

The real agent prompt reported `seon.render.canvas/welcome` invalid input and
showed the exact mismatch: supplied `:seon.render/entity`, expected
`:seon.agent/entity`.

## Owner

`seon.execution.runtime/render-agent-view!` owns the ordinary selected-renderer
argument map.

## Acceptance

- Canvas blocks carry the selected entity under `:seon.agent/entity`.
- The existing welcome runtime test asserts the actual argument.
- The real canvas renders without its fallback error card.

## Triage closure — 2026-07-23

Current source disproves the key mismatch:
`src/seon/execution/runtime.cljs:527-547` constructs the canvas block with
`:seon.agent/entity` and uses that same key for the selected-renderer call.
