---
type: issue
status: open
severity: architectural
---
# State Managed in Three Separate Mechanisms That Don't Sync

## Problem

Namespace state is managed by three independent systems that each hold partial truths:

1. **ctx.clj** (atom registry) -- namespace state atoms
2. **flow/harness.clj** (TCP proxy) -- request/response over TCP
3. **flow/topology.clj** (process graph) -- process lifecycle

When one updates, the others don't know. Any component that needs to know "what is namespace X doing right now?" must consult multiple sources and reconcile conflicting answers.

## Where

- `src/seon/ctx.clj` — atom registry
- `src/seon/flow/harness.clj` — TCP proxy
- `src/seon/flow/topology.clj` — process graph

## Acceptance Criteria

- Single source of truth for namespace state
- State changes propagate to all consumers through one mechanism
- No need to consult multiple sources to answer "what is namespace X doing?"
- Tests verify state consistency across the unified mechanism

## Related

- [[components/context]]
- [[components/harness]]
- [[components/flow-topology]]
