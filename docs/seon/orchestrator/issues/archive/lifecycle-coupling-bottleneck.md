---
type: issue
status: superseded
severity: friction
milestone: M4
tags: [issue, flow, architecture]
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

## Superseded (2026-06-28 audit)

ns/lifecycle.clj is JVM-paused; there is no equivalent bottleneck in the active pod.
