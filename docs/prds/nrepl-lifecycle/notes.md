# nREPL Lifecycle Notes

## Gotchas for Future Agents

### 1. nREPL Servers Run in the JVM, Not Separate Processes

nREPL servers created via `nrepl.server/start-server` run as threads in the same JVM. They're not child processes. This means:
- They survive namespace reloading
- `defonce` atoms preserve their values but the server threads keep running
- You cannot "kill" them externally - you must call `nrepl.server/stop-server`

### 2. The `servers` and `port-registry` Atoms Are Separate

In `seon.orchestrator.nrepl`:
- `servers` atom maps session-id -> full server info (including the server object)
- `port-registry` atom maps session-id -> port number

Both must be cleaned up when stopping a server. If you clear the registry but don't call `stop-server`, the server keeps listening.

### 3. `cleanup-orphaned-resources!` Now Has Two Modes

**Old behavior (still present):**
- Finds registry entries pointing to dead ports
- Cleans up stale registry entries

**New behavior (added):**
- Scans port range 7889-7999 for listening ports NOT in registry
- Kills those processes via `lsof`/`kill`

### 4. `shutdown-all!` Has a Safety Net

The agent shutdown process now has belt-and-suspenders:
1. Call each agent's `close!` function (proper cleanup)
2. Call `stop-all-namespace-nrepls!` (catch any survivors)

This prevents orphans even if individual `close!` functions throw.

### 5. Testing nREPL Lifecycle

To verify no orphans after reset:
```bash
# Check for listening ports in agent range (excluding main nREPL on 7888)
lsof -i :7889-7999 -sTCP:LISTEN
```

To manually clean up orphans:
```clojure
(require '[seon.health :as health])
(health/cleanup-orphaned-resources! {::health/node node})
```

### 6. The Port Range is Configurable

Tests use a different port range (17889-17999) to avoid conflicts:
```clojure
(nrepl/set-port-range! 17889 17999)
;; ... run tests ...
(nrepl/reset-port-range!)
```
