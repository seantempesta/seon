# Agent Isolation - Notes & Gotchas

## Research Questions - RESOLVED

### 1. Multiple nREPL Servers in One JVM - RESOLVED

**Answer: YES, fully supported.**

See `docs/prds/agent-isolation/research/nrepl-multi-server.md` for full details.

- Each `nrepl.server/start-server` call creates independent server
- Sessions are keyed by UUID (global atom, but no conflicts)
- Thread pools are shared (efficient, not a problem)
- Server implements `java.io.Closeable` for clean shutdown

### 2. Context Injection - RESOLVED

**Answer: Use custom nREPL middleware with dynamic var.**

```clojure
(def ^:dynamic *ctx* nil)

(defn make-context-middleware [ctx-atom target-ns]
  (fn wrap-context [handler]
    (fn [{:keys [session] :as msg}]
      (when (and session (not (contains? @session #'*ctx*)))
        (swap! session assoc
               #'*ns* (find-ns target-ns)
               #'*ctx* ctx-atom))
      (handler msg))))
```

Middleware descriptor must specify:
- `:requires #{"session"}` - runs after session middleware
- `:expects #{"eval"}` - runs before eval middleware

### 3. Datastar SSE Scoping - STILL UNRESOLVED

Current SSE broadcasts to all connected clients. Need:
- Per-namespace SSE channels
- Agent's `render-fn` targets only their namespace's channel

### 4. Code Loading in Shared JVM - RESOLVED

**Answer: clj-reload can load from worktree directories.**

See `docs/prds/agent-isolation/research/worktree-reloading.md` for full details.

Key findings:
- clj-reload uses `Compiler/load` with file content, bypassing classpath
- Call `(reload/init {:dirs ["/path/to/worktree/src" "src" ...]})` to add worktree
- One active worktree at a time (global state)
- Agents must work on non-overlapping namespaces

**Implementation approach**:
```clojure
(defn activate-agent! [{:seon.agent/keys [worktree-path]}]
  (reload/init {:dirs [(str worktree-path "/src") "src" "env/dev/clj" "test"]
                :no-reload '#{user}}))

(defn reload-agent-namespaces! [namespace-sym]
  (reload/reload {:only (re-pattern (str "^" namespace-sym "\\..*"))}))
```

---

## nREPL Implementation Gotchas

### Global Sessions Atom

The `sessions` atom in `nrepl.middleware.session` is JVM-global:

```clojure
(def ^:private sessions (atom {}))  ; session.clj line 20
```

This is **NOT a problem** because:
- Sessions are keyed by UUID (globally unique)
- Each server can have sessions with the same client
- `ls-sessions` returns all sessions across all servers (might be surprising)

### Middleware Ordering

Critical for context injection:
1. `session` middleware must run first (creates the `:session` atom)
2. Our `wrap-context` runs next (injects `*ns*` and `*ctx*`)
3. `interruptible-eval` runs last (evaluates code with bindings)

**IMPORTANT**: Use **var references**, not strings:

```clojure
;; CORRECT - use var references
(set-descriptor! #'wrap-context
  {:requires #{#'nrepl.middleware.session/session}
   :expects #{#'nrepl.middleware.interruptible-eval/interruptible-eval}})

;; WRONG - strings don't work for middleware ordering
(set-descriptor! #'wrap-context
  {:requires #{"session"}   ; This refers to operation names, NOT middleware
   :expects #{"eval"}})     ; Won't work as intended!
```

The nREPL middleware linearizer uses var metadata to build the execution order.
String-based `:requires`/`:expects` refer to operation names (from `:handles` map),
which is different from middleware ordering. See the Phase 2 "Key Fix" in the PRD.

### Port 0 Auto-Assignment

Use `:port 0` to let the OS assign an available port:

```clojure
(let [server (nrepl/start-server :port 0)]
  (println "Server started on port" (:port server)))
```

The actual port is available in `(:port server)` after startup.

### Thread Pool Sharing

All nREPL servers share these thread pools (defined in `util/threading.clj`):

```clojure
(def listen-executor ...)   ; Accepts connections
(def handle-executor ...)   ; Handles messages
(def transport-executor ...) ; Transport layer
```

This is efficient (no per-server overhead) but means:
- A CPU-intensive eval on one server affects others slightly
- OOM from one server crashes all

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
