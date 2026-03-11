---
type: capability
status: complete
tags: [vision, agent]
---
# Agent Process Isolation

Each agent operates in its own JVM with isolated nREPL and database connections. Agents cannot corrupt each other's state or crash each other's processes. A pre-warmed pool ensures agents start fast without cold-start overhead.

## What Exists

- Pre-warmed JVM pool with acquire/claim/release/dispose lifecycle
- Each agent gets isolated nREPL + Datalevin connection
- TCP-based cross-namespace routing via harness
- Length-prefixed Nippy communication protocol
- Health checks with grace period, auto-replenishment, stale cleanup

## Gaps

None.

## Related

- Components: [[components/harness]], [[components/flow-topology]], [[components/agent-system]]
- PRDs: [[prds/super-repl/prd]], [[prds/unified-flow/prd]]
