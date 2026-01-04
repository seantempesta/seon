# Agent Isolation - Notes & Gotchas

## Key Research Questions (Unresolved)

### 1. Multiple nREPL Servers in One JVM
Can we start multiple nREPL servers on different ports within the same JVM?
- Each bound to a different namespace
- Need to test: `(nrepl.server/start-server :port 7889)` called multiple times

### 2. Context Injection
How does the agent get access to `ctx`?
- Dynamic var `*ctx*` bound in nREPL session?
- Well-known atom `seon.agent/ctx`?
- Query orchestrator DB on demand?

### 3. Datastar SSE Scoping
Current SSE broadcasts to all connected clients. Need:
- Per-namespace SSE channels
- Agent's `render-fn` targets only their namespace's channel

### 4. Code Loading in Shared JVM
Agent edits files in worktree, but shared JVM loads from main repo.
- When does agent's new code get loaded?
- `(reload)` reloads from main repo, not worktree
- May need worktree-aware reload or separate classpath

---

## XTDB Multi-Database

**Attach syntax** (from reference-code):
```sql
ATTACH DATABASE "seon.trading" WITH $$
  log: !Local
    path: 'data/namespaces/seon.trading/log'
  storage: !Local
    path: 'data/namespaces/seon.trading/storage'
$$
```

**Important**: ATTACH must be run from primary `xtdb` database connection.

**Cross-database query**:
```sql
SELECT * FROM "seon.trading".trades t
  JOIN "seon.health".users u ON t.user_id = u._id
```

---

## Git Worktree Commands

```bash
# Create worktree for namespace
git worktree add ../seon-trading -b agent/seon.trading/20260104

# List worktrees
git worktree list

# Remove worktree
git worktree remove ../seon-trading
```

**Gotcha**: Branch can't be checked out elsewhere. Use unique branch per session.

---

## Memory Budget (16GB Machine)

| Component | Memory |
|-----------|--------|
| macOS overhead | ~2GB |
| IDE + browser | ~2-3GB |
| Orchestrator (XTDB + nREPL + HTTP) | ~4GB |
| Per namespace nREPL | ~50-100MB |
| Per namespace XTDB DB (attached) | ~100-200MB |
| **Available for ~5 namespaces** | ~1-1.5GB |

Shared JVM approach is efficient. Full isolation would be ~1.5GB per namespace.

---

## Port Allocation

| Service | Port |
|---------|------|
| Orchestrator HTTP | 8080 |
| Orchestrator nREPL | 7888 |
| seon.trading nREPL | 7889 |
| seon.health nREPL | 7890 |
| seon.finance nREPL | 7891 |
| ... | 7892+ |

Derive port from namespace registration order or hash.

---

## Agent Lifecycle

```
1. start-namespace-agent!(namespace)
   ├── Check namespace not locked
   ├── Create git worktree (if not exists)
   ├── ATTACH DATABASE (if not attached)
   ├── Start nREPL for namespace
   ├── Create ctx atom
   ├── Register agent session in orchestrator DB
   └── Return {:nrepl-port 7889 :worktree-path "..."}

2. Agent works...
   ├── Evals via nREPL
   ├── Queries via ctx db
   ├── Renders via ctx render-fn

3. stop-namespace-agent!(session-id)
   ├── Stop namespace nREPL
   ├── Update session status
   ├── Optionally: keep DB, archive worktree
   └── Unlock namespace
```
