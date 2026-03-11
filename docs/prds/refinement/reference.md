---
type: prd
status: draft
tags: [prd, database]
---
# Refinement Reference: Agent System Architecture

## Agent Launch Call Chain

```
user/launch-agent!! (env/dev/clj/user.clj)
  → claude/launch-agent! (src/seon/ai/claude.clj)
    → session/start-agent-session! (src/seon/orchestrator/session.clj)
      → multi/ensure-namespace-db! (XTDB domain isolation)
      → multi/create-namespace-connection (XTDB domain conn)
      → conn/get-namespace-conn! (Datalevin namespace conn)
      → ctx/make-persisted-ctx (persisted ctx atom)
      → nrepl-multi/start-namespace-nrepl! (isolated nREPL)
    → ai/start-session! (src/seon/ai.clj)
      → datalevin-write! :save-session (Datalevin master DB)
    → sdk/spawn-claude-code (src/seon/ai/claude/sdk.clj)
      → Process (Claude CLI at /Users/sean/.local/bin/claude)
    → reader future (reads stdout, persists messages)
      → persist-message! → datalevin-write! :save-message
    → ai/end-session! (on completion)
      → datalevin-write! :update-session
```

## Data Flow

### Where AI Data Lives (Datalevin only since Phase E3)

- **Sessions**: `seon.ai.datalevin/save-session!` → master Datalevin DB
- **Messages**: `seon.ai.datalevin/save-message!` → master Datalevin DB
- **Orchestrator sessions**: `seon.orchestrator.session` → `seon.orchestrator` namespace DB
- **Entity ID mapping**: `:xt/id` → `:seon.ai.datalevin/xtdb-id` (legacy naming)

### Where Domain Data Lives (XTDB)

- Per-namespace XTDB databases for agent domain work (trading, health, etc.)
- Orchestrator uses primary XTDB database

### Where Graph Data Lives (Datalevin)

- Code scanner → `seon-graph` Datalevin DB
- `seon.fn/*` entities with render-input-keys, output specs
- `seon.render/set-conn!` connects to this graph DB

## Key Components & Config

### system.edn Components

| Component | Purpose | Dependencies |
|-----------|---------|--------------|
| `:seon/xtdb-node` | Domain data | None |
| `:seon/datalevin-server` | Datalevin server (port 8898) | None |
| `:seon/connection-manager` | Lazy Datalevin connections | datalevin-server |
| `:seon/code-scanner` | Populates graph DB, wires render/set-conn! | connection-manager |
| `:seon/agent-pool` | Pre-warmed JVM pool | datalevin-server |
| `:seon/primer-ctx` | Primer session management | connection-manager |
| `:seon/orchestrator-sessions` | Agent session management | connection-manager |
| `:seon.web.server/http-server` | HTTP (port 8080) | xtdb-node |
| `:seon/nrepl-server` | REPL (port 7888) | None |

### Observatory Data Sources

- **Running agents**: `seon.ai.agent/agent-registry` (in-memory atom)
- **Completed sessions**: `seon.ai.datalevin/dl-list-sessions`
- **Messages**: `seon.ai.datalevin/dl-get-messages`, `dl-recent-messages`
- **Stats**: `seon.ai.datalevin/dl-message-stats-by-session`

## Known Issues to Investigate

### 1. Legacy `::ai/node` Threading

The `::ai/node` parameter is threaded through the entire agent API but writes go to Datalevin. It's still needed for:

- `session/start-agent-session!` → `multi/ensure-namespace-db!` (XTDB domain isolation)
- `session/stop-agent-session!` → cleanup

If `(user/xtdb-node)` returns nil (system not fully started), launch fails.

### 2. Agent Pool

`seon/agent-pool` starts pre-warmed JVMs. If this fails silently, agents may not get pool resources. Check `src/seon/flow/pool.clj`.

### 3. Claude CLI

Hardcoded path: `/Users/sean/.local/bin/claude` (from system.edn `:seon/claude-code`).
Check: `which claude` or `ls -la /Users/sean/.local/bin/claude`

### 4. Connection Manager Availability

`seon.ai.datalevin/get-conn` relies on `(:seon/connection-manager state/system)`. If system isn't running when persist-message! is called, writes silently fail.

### 5. Observatory Route Registration

After the `ns.routes` migration, verify routes are registered:

- `/agents` → `seon.web.agents/agents-page`
- `/agents/:agent-id` → `seon.web.agents/agent-detail-page`
- `/api/agents/sse` → `seon.web.agents/agents-sse`
- `/api/agents/:agent-id/sse` → `seon.web.agents/agent-detail-sse`

## Files to Read for Each Track

### Agent Launch Fix

- `src/seon/ai/claude.clj` (launch-agent!, persist-message!)
- `src/seon/ai/claude/sdk.clj` (spawn-claude-code)
- `src/seon/orchestrator/session.clj` (start-agent-session!)
- `src/seon/orchestrator/nrepl.clj` (start-namespace-nrepl!)
- `env/dev/clj/user.clj` (user/launch-agent!!)

### Observatory Fix

- `src/seon/web/agents.clj` (handlers, SSE, rendering)
- `src/seon/ai/agent.clj` (registry, Observatory API)
- `src/seon/ai/agent/views.clj` (tool renderers)
- `src/seon/web/routes.clj` (route registration)

### Render Pipeline Verification

- `src/seon/render.clj` (set-conn!, find-renderer)
- `src/seon/system.clj` (code scanner init → set-conn!)
- `src/seon/graph/scanner.clj` (spec extraction)
- `src/seon/graph/ingest.clj` (Datalevin population)
- `src/seon/health/workout/render.clj` (test renderer)
