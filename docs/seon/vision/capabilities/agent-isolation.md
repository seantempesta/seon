---
type: capability
status: complete
tags: [vision, agent]
---
# Agent Process Isolation

Each agent operates in its own JVM with isolated nREPL and database connections. Agents cannot corrupt each other's state or crash each other's processes. A pre-warmed pool ensures agents start fast without cold-start overhead.

## What Exists

- Pre-warmed JVM pool with acquire/claim/release/dispose lifecycle
- Each agent gets isolated nREPL + embedded Datahike connection `[JVM track — paused]`
- TCP-based cross-namespace routing via harness
- Length-prefixed Nippy communication protocol
- Health checks with grace period, auto-replenishment, stale cleanup

## Gaps

- **SSE scoping**: Agent log streams are broadcast globally — no per-agent SSE channel scoping for targeted observatory views
- **Git worktree integration**: Archive research explored git worktrees for per-agent file isolation, but this was never built. Agents share the working tree with manual coordination.
- **Stuck detection**: No distinction between "thinking" and "stuck" agents — see [[issues/archive/no-agent-stuck-detection]]

## Related

- Components: [[components/harness]], [[components/flow-topology]], [[components/agent-system]]
- PRDs: `prds/super-repl/prd`, [[prds/unified-flow/prd]]
