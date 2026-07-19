---
type: issue
status: active
tags: [issue, agent, web, cljs]
---

# Nested authored render hides child reload

## Evidence

On 2026-07-18, the exact read-only package let an ordinary agent publish a
canvas renderer and its action functions successfully. The next real agent
Datastar feed rendered an error instead of the canvas:

```text
Authored source changed; a fresh child is required.

```

The execution host already recognizes
`:seon.execution/reload-required?`, retires the stale child, and retries the
same invocation exactly once. That contract is covered for a top-level
authored invocation. The canvas path enters authored code through the compiled
renderer's `invoke-selected!` callback instead. `seon.execution/invoke-selected!`
caught the same program-change exception and converted it into an ordinary
per-call error value. The compiled renderer therefore returned successfully,
and the host never received the reload signal it owns.

## Expected owner

`seon.execution` preserves the program-change signal through nested authored
selection. `seon.execution.host` remains the one child retirement and bounded
retry owner. Do not add a canvas-specific refresh, program broadcast, second
renderer, or user-visible recovery instruction.

## Acceptance criteria

- A nested authored selection against a changed program rejects with the exact
  `:seon.execution/reload-required?` signal.
- The existing host retires the stale child and retries the complete render
  invocation once in a fresh child.
- The published canvas renders and its input, select, toggle, button, and form
  actions work in a real browser without an intermediate error surface.
- A second retry is impossible and ordinary authored compile/call errors remain
  ordinary render error values.
