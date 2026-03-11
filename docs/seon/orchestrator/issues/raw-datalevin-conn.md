---
type: issue
status: open
severity: cleanup
---
# Raw Datalevin Connection in agent_runner.clj

## Problem
`agent_runner.clj:44` calls `datalevin.core/get-conn` directly via `requiring-resolve`, bypassing the `seon.db` API that the rest of the codebase uses. This creates an untracked database connection outside the connection manager's control. Low priority since it's only used during agent JVM bootstrap.

## Where
- `src/seon/flow/agent_runner.clj:44` — direct `d/get-conn` call

## Acceptance Criteria
- `agent_runner.clj` uses `db/resolve-conn` or equivalent `seon.db` API
- No direct `datalevin.core/get-conn` calls outside `src/seon/db/`
- Agent JVM bootstrap still works correctly

## Related
- [[components/database]]
- [[components/flow-topology]]
