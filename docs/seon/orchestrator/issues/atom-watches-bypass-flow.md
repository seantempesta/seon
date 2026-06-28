---
type: issue
status: superseded
severity: architectural
milestone: M1
tags: [issue, flow]
---
# Atom Watches Bypass Flow, Making State Changes Invisible

## Problem

When namespace state changes in ctx, atom watches fire persistence and SSE push directly. These side effects happen outside flow, so no other flow process can observe, intercept, or react to state transitions. This undermines flow as the routing backbone.

## Where

- `src/seon/ctx.clj:285-355` — watch-based persistence and SSE push

## Acceptance Criteria

- State changes route through flow
- Other flow processes can observe and react to namespace state transitions
- Persistence and SSE push are flow steps, not side effects of atom watches
- Flow topology has full visibility into state changes
- Tests verify state changes are observable via flow

## Related

- [[components/context]]
- [[components/flow-topology]]

## Superseded (2026-06-28 audit)

ctx.clj watch-to-flow wiring is JVM; the active pod is core.async-free (native ^:async).
