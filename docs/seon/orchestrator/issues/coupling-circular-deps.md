---
type: issue
status: open
severity: architectural
milestone: M1
tags: [issue, architecture]
---
# Coupling: 3 Circular Dependencies Broken by requiring-resolve

## Problem

Three pairs of namespaces depend on each other and use `requiring-resolve` to avoid load-order failures. This is fragile under code reload and indicates architectural boundaries drawn in the wrong place. `requiring-resolve` masks the cycle rather than fixing it.

## Where

- Three namespace pairs using `requiring-resolve` for circular dependency avoidance (exact pairs need identification via grep for `requiring-resolve`)

## Acceptance Criteria

- All circular dependencies eliminated by restructuring (extract shared logic, invert dependency, or merge)
- No `requiring-resolve` used for cycle-breaking purposes
- Clean load order without workarounds
- Code reload (`user/reload`) works reliably
- Tests pass

## Related

- [[components/code-graph]]
