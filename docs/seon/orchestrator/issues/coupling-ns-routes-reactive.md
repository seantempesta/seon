---
type: issue
status: open
severity: friction
milestone: M4
tags: [issue, architecture]
---
# Coupling: ns/routes.clj Uses web/reactive Directly

## Problem

Namespace views are tightly coupled to the reactive system implementation rather than going through the standard SSE push path. `ns/routes.clj` uses `web/reactive.*` directly instead of the shared push mechanism.

## Where

- `src/seon/ns/routes.clj` — direct use of `web/reactive.*`

## Acceptance Criteria

- `ns/routes.clj` uses the standard SSE push path
- No direct coupling to `web/reactive` implementation details
- Reactive updates still work correctly in namespace views
- Tests pass

## Related

- [[components/namespace-lifecycle]]
- [[components/web-layer]]
