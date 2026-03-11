# Stability Improvements - Health Checks, Error Boundaries, Automated Cleanup

## Problem Summary

The system has stability concerns due to:

1. **No real health checks** - `/api/health` just returns static "ok"
2. **Error boundaries are incomplete** - Database failures propagate and crash the system
3. **Orphaned resources** - Processes/ports/channels can leak on crashes

## Current State (from exploration)

### Registries Tracking Resources

| Registry | Location | Tracks |
|----------|----------|--------|
| `session-registry` | session.clj:190 | Active agent sessions |
| `port-registry` | nrepl.clj:114 | Allocated nREPL ports |
| `servers` | nrepl.clj:289 | Running nREPL servers |
| `agent-registry` | agent.clj:257 | Active agent handles |

### Existing Recovery

- `recover-sessions!` marks XTDB sessions as 'stopped' on startup
- `shutdown-all!` in agent.clj can terminate all agents
- Process exit watcher unblocks reader on Claude crash

### Key Gaps

1. **Health**: No XTDB, nREPL, or process liveness checks
2. **Errors**: Database failures propagate; no retry logic
3. **Cleanup**: Port registry not synced with actual ports; channels may leak

---

## Implementation Plan

### Phase 1: Health Check System

**Create `src/seon/health.clj`** with comprehensive checks:

```clojure
(ns seon.health
  "System health checks and monitoring.")

;; Component health checks
(defn check-xtdb [node] ...)      ; Execute simple query, return {:ok true} or {:ok false :error "..."}
(defn check-nrepl [port] ...)     ; Connect and clone session
(defn check-http [port] ...)      ; Self-request to /api/health
(defn check-resources [] ...)     ; Count agents, ports, channels

;; Aggregate health
(defn system-health [system] ...)  ; Returns {:status :healthy/:degraded/:unhealthy, :checks {...}}
```

**Enhance `/api/health` endpoint**:

- Quick check: XTDB ping, agent count
- Return proper HTTP status (200/503)

**Add `/api/health/deep` endpoint**:

- Full component-by-component breakdown
- Resource counts and utilization
- Recent error summary

### Phase 2: Error Boundaries

**Wrap critical paths with recovery**:

1. **Message persistence** (claude.clj:716-723):
   - Already catches and logs - GOOD
   - Add: retry once after 100ms delay

2. **AI session end** (claude.clj:738-750):
   - Already catches and logs - GOOD
   - No change needed

3. **Database operations** (session.clj):
   - Wrap `store-session!`, `update-session-status!` in try-catch
   - Log and continue on failure (session still works in-memory)

4. **nREPL creation** (nrepl.clj):
   - Add timeout to port allocation
   - Return error map instead of throwing

5. **MCP server startup** (bin/mcp-server):
   - If orchestrator unreachable, exit with clear error
   - Don't accept requests that will always fail

### Phase 3: Automated Cleanup on Startup

**Enhance `recover-sessions!`** to also:

1. Kill orphaned Claude processes (by checking session-map.edn)
2. Release leaked nREPL ports (scan port-registry vs actual listeners)
3. Clear stale agent-registry entries

**Add `cleanup-orphaned-resources!`** function:

```clojure
(defn cleanup-orphaned-resources! [{::keys [node]}]
  ;; 1. Find processes that claim to be Seon agents
  ;; 2. Check if their session exists and is running
  ;; 3. Kill orphaned processes
  ;; 4. Release orphaned ports
  ;; 5. Return cleanup report)
```

**Call during system startup** (system.clj):

- After XTDB starts, before accepting connections
- Log what was cleaned up

### Phase 4: Health Monitoring in Observatory

**Add health indicator to Observatory UI**:

- Status dot in header: green/yellow/red
- Click to expand component details
- Auto-refresh via SSE

---

## Files to Create/Modify

| File | Action | Purpose |
|------|--------|---------|
| `src/seon/health.clj` | **CREATE** | Health check functions |
| `src/seon/web/handlers.clj` | Modify | Enhanced /health, add /health/deep |
| `src/seon/web/routes.clj` | Modify | Add /api/health/deep route |
| `src/seon/system.clj` | Modify | Call cleanup on startup |
| `src/seon/orchestrator/session.clj` | Modify | Enhanced recovery, error boundaries |
| `src/seon/orchestrator/nrepl.clj` | Modify | Port cleanup, error boundaries |
| `src/seon/ai/claude.clj` | Modify | Retry on message persistence |
| `bin/mcp-server` | Modify | Fail fast if orchestrator unreachable |

---

## Verification

### Health Check Tests

```bash
# Quick health (should return 200 with component status)
curl http://localhost:8080/api/health

# Deep health (full breakdown)
curl http://localhost:8080/api/health/deep
```

### Error Boundary Tests

```clojure
;; Test: Message persistence failure doesn't crash agent
;; (Simulate by temporarily breaking XTDB connection)

;; Test: nREPL port exhaustion returns error, doesn't throw
```

### Cleanup Tests

```bash
# 1. Start server, launch agent
# 2. Kill server ungracefully (kill -9)
# 3. Restart server
# 4. Check logs for cleanup report
# 5. Verify no orphaned ports: lsof -i :7889-7999
```

---

## Implementation Order

1. **Health checks first** - Gives visibility into what's broken
2. **Error boundaries** - Prevent crashes while we monitor
3. **Automated cleanup** - Clean recovery from failures
4. **UI integration** - Surface health in Observatory

This order ensures we can see problems before we try to fix them.

### Phase 5: Agent Robustness (from namespace-ui PRD Phase 1c)

**Goal:** Make agents resilient to errors so they don't exit unexpectedly.

**Background:** Investigation found agents getting `:interrupted` status (exiting without sending result message) when users did not interrupt them. Agent d35d died silently mid-task without the system detecting it:

- Claude process terminated but `agent-status` remained `:running`
- nREPL server orphaned (still listening on port)
- No COMPLETE marker in log file

#### 5.1 Defensive Logging

**Problem:** `log-sdk-message!` at claude.clj:668 is not wrapped in try/catch. If it throws, the entire agent reader loop fails.

**File:** `src/seon/ai/claude.clj`

```clojure
;; Before (line 668):
(agent-log/log-sdk-message! agent-logger msg)

;; After:
(try
  (agent-log/log-sdk-message! agent-logger msg)
  (catch Exception e
    (log/warn e "Failed to log SDK message" {:session-id id :msg-type msg-type})))
```

#### 5.2 Fix Hook Matcher

**Problem:** Hook matcher `(Edit|Write)` matches `TodoWrite` via substring.

**File:** `.claude/settings.json`

```json
// Before:
"matcher": "(Edit|Write)"

// After - use anchors for exact match:
"matcher": "^(Edit|Write)$"
```

#### 5.3 Process Death Detection

**Problem:** Reader loop in `src/seon/ai/claude.clj:619-696` should detect process death via `readLine` returning `nil`, then set status to `:terminated` in the `finally` block. This isn't always happening.

**Fixes needed:**

1. Add process health check / watchdog
2. Monitor `exit-ref` from process
3. Timeout-based stuck detection
4. Cleanup orphaned nREPL servers when process dies

### Phase 6: MCP Eval Timeout Fix

**Problem:** Agent e84d got stuck on an `mcp__seon__eval` that hung indefinitely. The 30s timeout returns an error but doesn't kill the blocked eval thread.

**File:** `bin/mcp-server`

**Fix:** Add auto-interrupt after timeout:

```clojure
;; After timeout, also send interrupt
(when (= (:ex result) "timeout")
  (nrepl-interrupt port nrepl-session-id))
```

### Phase 7: Hook Self-Edit Protection

**Problem:** When an agent edits `hook.clj` itself and breaks its syntax, the nREPL eval to call the repair code fails because the broken code can't compile. This creates a chicken-and-egg problem.

**Potential fixes:**

1. Move syntax repair to `bin/seon-hook` (Babashka) so it runs before Clojure load
2. Special-case `hook.clj` edits with pre-edit syntax validation
3. Keep a backup of last-known-good `hook.clj` and restore on compile failure
4. Add a "hook health check" that validates hook.clj can compile before proceeding

---

## Files to Create/Modify (Updated)

| File | Action | Purpose |
|------|--------|---------|
| `src/seon/health.clj` | **CREATE** | Health check functions |
| `src/seon/web/handlers.clj` | Modify | Enhanced /health, add /health/deep |
| `src/seon/web/routes.clj` | Modify | Add /api/health/deep route |
| `src/seon/system.clj` | Modify | Call cleanup on startup |
| `src/seon/orchestrator/session.clj` | Modify | Enhanced recovery, error boundaries |
| `src/seon/orchestrator/nrepl.clj` | Modify | Port cleanup, error boundaries |
| `src/seon/ai/claude.clj` | Modify | Retry on message persistence, defensive logging, death detection |
| `src/seon/ai/agent/log.clj` | Modify | Defensive try/catch in log functions |
| `bin/mcp-server` | Modify | Fail fast if orchestrator unreachable, auto-interrupt on timeout |
| `bin/seon-hook` | Modify | Pre-Clojure syntax validation for hook.clj |
| `.claude/settings.json` | Modify | Fix hook matcher regex |

---

## Success Criteria

1. `/api/health` returns actual component status (not just static "ok")
2. `/api/health/deep` provides detailed breakdown of all components
3. Database failures are caught and logged, not propagated
4. Server startup cleans up orphaned resources from previous crashes
5. Observatory shows system health status
6. Agent logging failures don't crash the reader loop
7. TodoWrite no longer triggers seon-hook incorrectly
8. MCP eval timeouts properly interrupt blocked threads
9. Hook self-edits that break syntax are caught before they brick the system
