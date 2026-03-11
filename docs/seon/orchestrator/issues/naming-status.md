---
type: issue
status: open
severity: friction
---
# Naming Conflict: "status" Means 3 Different Things

## Problem
"status" is used for three different concepts with no qualifier:

1. System health (healthy/degraded)
2. Runtime instance state (running/stopped/crashed)
3. Flow process state (running/stopped/error)

All use the bare word "status," making code and data ambiguous.

## Where
- System health status — `src/seon/health.clj`
- Runtime instance status — `src/seon/runtime.clj`
- Flow process status — `src/seon/flow/topology.clj`

## Acceptance Criteria
- Each concept has a qualified, distinct name (e.g., `:seon.health/status`, `:seon.runtime/state`, `:seon.flow/process-state`)
- Schema registrations reflect the distinct names
- No bare `:status` keys remain in the data model

## Related
- [[components/flow-topology]]
- [[components/system-lifecycle]]
