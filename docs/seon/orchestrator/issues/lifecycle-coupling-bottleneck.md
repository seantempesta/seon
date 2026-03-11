---
type: issue
status: open
severity: friction
---
# Namespace Lifecycle Is a Coupling Bottleneck

## Problem

`ns/lifecycle.clj` `ensure-instance!` depends on 7 other components: ctx, db, graph, runtime, render, schema, web. Any change to any of these components risks breaking namespace startup. Testing lifecycle in isolation is effectively impossible.

## Where

- `src/seon/ns/lifecycle.clj` — `ensure-instance!` function

## Acceptance Criteria

- `ensure-instance!` depends on at most 2-3 components
- Remaining dependencies are injected or accessed through a narrower interface
- Lifecycle can be tested in isolation with mocked dependencies
- Changes to downstream components (e.g., render, graph) don't break namespace startup
- Tests pass

## Related

- [[components/namespace-lifecycle]]
