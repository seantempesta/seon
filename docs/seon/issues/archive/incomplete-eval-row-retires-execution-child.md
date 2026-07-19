---
type: issue
status: resolved
tags:
  - agent-runtime
  - reliability
---

# Incomplete eval row retires execution child

## Evidence

The live `root_orchestration` Inspect scenario on 2026-07-19 opened root's run
immediately, then retired its execution child while rendering prior context.
The persisted core fault reports `seon.agent.ctx/cap-result` received `nil` for
its boolean `:seon.agent.ctx/full?` argument from `format-eval-row`.

`small-full?` was derived with `and`; an absent `:seon.eval/ok?` therefore
produced `nil` even though the callee's public contract requires a boolean.

## Acceptance

- A partially assembled eval row renders without throwing under instrumentation.
- Focused context tests pass.
- The retained live root orchestration scenario advances beyond prior-context
  rendering without retiring its execution child for this fault.

## Resolution

Commit `3ee9b129` makes the derived flag explicitly boolean and adds the focused
regression. The focused context gate passes 8 tests/19 assertions. On the
rebuilt artifact the same live root request rendered its context and reached
the provider; it no longer retired the execution child for this fault.
