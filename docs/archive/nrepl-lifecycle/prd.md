---
type: prd
status: completed
tags: [prd, archive]
---

# PRD: nREPL Server Lifecycle Management

## Status: COMPLETE — Port allocation, orphan cleanup, and BindException retry logic implemented

**Status:** Fixed
**Priority:** Critical (blocks agent system)
**Date:** 2026-01-25

---

## Problem

After `(user/reset)`, orphaned nREPL servers remain running but are not tracked in registries.

### Symptoms

```bash
# Health check shows 0 servers
curl http://localhost:8080/api/health
# => {"resources": {"nrepl-servers": 0, "ports": 0}}

# But ports are actually in use
lsof -i :7889-7899
# => 10 servers listening on ports 7889-7898

```

### Impact

- Cannot launch new agents (port allocation fails)
- `cleanup-orphaned-resources!` doesn't find them (checks registry, not port range)
- Only fix is full JVM restart

---

## Root Cause Analysis

### What Was Happening

1. **`user/reset` calls `agent/shutdown-all!`** which iterates through `agent-registry`
2. Each agent's `close!` function calls `session/stop-agent-session!` -> `nrepl/stop-namespace-nrepl!`
3. **If any step throws an exception**, the nREPL server survives but the registry entry may be removed
4. **`cleanup-orphaned-resources!` looked for wrong orphans** - it checked for registry entries with dead ports, not live ports without registry entries

### The Gap

The cleanup function was inverted:
- It found: registry entries -> dead ports (stale entries)
- It should also find: live ports -> no registry entries (true orphans)

---

## Fix Applied

### 1. Immediate Fix: `cleanup-orphaned-resources!` (seon.health)

Added `find-listening-orphans` function that scans port range 7889-7999 for ports that are listening but NOT in the registry. These are killed via `lsof`/`kill`.

```clojure
;; New function scans the port range
(find-listening-orphans)
;; => [{:port 7889 :status :listening-but-unregistered} ...]

;; Cleanup now kills these orphans
(cleanup-orphaned-resources! {::node node})
;; => {::ports-released 0
;;     ::servers-removed 0
;;     ::orphans-killed 3
;;     ::cleanup-errors []}

```

### 2. Root Cause Fix: `shutdown-all!` (seon.ai.agent)

Added a safety net that calls `nrepl/stop-all-namespace-nrepls!` after individual agent shutdown. This ensures all nREPL servers are stopped even if individual `close!` functions fail.

```clojure
;; shutdown-all! now has two steps:
;; 1. Call each agent's close! function
;; 2. Safety net: call stop-all-namespace-nrepls! to catch survivors

```

---

## Files Changed

| File | Change |
|------|--------|
| `src/seon/health.clj` | Added `find-listening-orphans`, `kill-port!`, updated `cleanup-orphaned-resources!` |
| `src/seon/ai/agent.clj` | Added safety net nREPL cleanup to `shutdown-all!` |

---

## Verification

```bash
# 1. Launch agents
# 2. Run (user/reset)
# 3. Verify no orphan ports
lsof -i :7889-7999 | grep -v 7888
# Should return empty

# 4. If orphans exist, cleanup finds and kills them
(require '[seon.health :as health])
(health/cleanup-orphaned-resources! {::health/node node})
# => {::health/orphans-killed N ...}

# 5. Launch new agent - should work

```

---

## Test Criteria

- [x] `shutdown-all!` stops all nREPL servers via safety net
- [x] `cleanup-orphaned-resources!` finds and kills listening orphans
- [x] New schemas registered for cleanup response
- [ ] Manual verification: launch agents, reset, check for orphans
