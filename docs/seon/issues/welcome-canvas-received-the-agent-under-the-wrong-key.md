---
type: issue
status: open
severity: high
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
