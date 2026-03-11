---
type: issue
status: open
severity: architectural
---
# No Unified Namespace Model

## Problem

Namespace behavior is split between harness and ctx with no unified model. The harness handles requests (what a namespace *does*), while ctx holds state (what a namespace *knows*). There is no single component that represents "a namespace as a running entity." Behavior and state are defined in separate systems with separate lifecycles, forcing every consumer to coordinate two unrelated systems.

## Where

- `src/seon/flow/harness.clj` — behavior (request handling)
- `src/seon/ctx.clj` — state (what the namespace knows)

## Acceptance Criteria

- Single component or protocol represents a namespace as a running entity
- Behavior and state are co-located or have a unified interface
- Consumers interact with one system, not two
- Namespace creation/restoration goes through the unified model

## Related

- [[components/context]]
- [[components/harness]]
- [[components/namespace-lifecycle]]
