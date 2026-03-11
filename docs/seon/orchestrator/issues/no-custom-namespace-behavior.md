---
type: issue
status: open
severity: architectural
---
# No Way for Namespaces to Define Custom Behavior

## Problem
All namespaces get the same ctx atom + harness proxy. A namespace that needs custom request handling, derived state computation, or specialized lifecycle behavior has no extension point to define it. This forces all namespaces into one behavioral mold, limiting what domain namespaces can do.

## Where
- `src/seon/flow/harness.clj` — fixed proxy behavior for all namespaces
- `src/seon/ctx.clj` — fixed atom storage for all namespaces

## Acceptance Criteria
- Namespaces can define custom request handlers
- Namespaces can define derived state computations
- Namespaces can customize their lifecycle behavior
- Extension mechanism is discoverable (e.g., via metadata or protocol)
- Default behavior still works for namespaces that don't customize
- Tests cover custom behavior extension

## Related
- [[components/context]]
- [[components/harness]]
- [[components/namespace-lifecycle]]
