# Agent Isolation - Notes & Gotchas

## Persistent nREPL Sessions (2026-01-09)

**Implementation Notes**:

1. **Session cloning happens in MCP server**: After `create_session` starts the Seon session, the MCP server (`bin/mcp-server`) sends a `clone` op to the agent's nREPL to get a persistent session ID. This ID is then stored via `set-nrepl-session-id!`.

2. **Why clone?** Without a session ID, each eval creates an ephemeral session. This means:
   - `*1`, `*2`, `*3` don't persist between evals
   - Can't use nREPL's `interrupt` op (requires session ID)
   - No ability to kill hung evaluations

3. **nREPL ops used**:
   - `clone` - Creates a persistent session, returns `new-session` ID
   - `eval` with `session` key - Evaluates in that session (enables *1/*2/*3)
   - `interrupt` with `session` key - Attempts to stop running eval

4. **Interrupt behavior**: The `interrupt` op returns status:
   - `"interrupted"` - Request identified, interruption attempted
   - `"session-idle"` - No request currently executing
   - `"interrupt-id-mismatch"` - Wrong interrupt-id (we don't use this)
   - `"session-ephemeral"` - Can't interrupt ephemeral sessions

5. **MCP tools updated**:
   - `create_session` - Now returns `nrepl_session_id` in response
   - `eval` - Passes session ID for persistent *1/*2/*3
   - `interrupt_eval` - New tool to kill hung evaluations
   - `list_sessions` - Includes `nrepl_session_id` for debugging

---

## RESOLVED: Custom Subagent Instructions

**Status**: Working (as of 2026-01-09)

**Resolution**: Markdown agent body content NOW loads as system prompt. The issue was likely caching from a previous session. Re-testing confirmed agents say "PINEAPPLE" when instructed to (test phrase in body).

**Remaining limitation**: The `skills:` frontmatter field does NOT restrict skills - all project skills remain available. Can only ADD context, not REMOVE tools.

**Alternative if regression occurs**: Use Claude Agent SDK (`@anthropic-ai/claude-agent-sdk`) for programmatic agent definitions with guaranteed prompt loading.

**See**: `docs/prds/agent-isolation/research/custom-subagent-investigation.md`

---

## Phase 4c: Session Observability (2026-01-09)

**Implementation Notes**:

1. **Activity tracking is in-memory only**: The `::last-activity-at`, `::eval-count`, and `::current-eval` fields are stored in the session registry atom, NOT in XTDB. This is intentional because:
   - Updates happen on every eval (too frequent for DB writes)
   - Data is ephemeral - resets on session restart anyway
   - Fast access needed for `list_sessions`

2. **MCP server calls orchestrator for activity tracking**: The `record-activity!` helper in `bin/mcp-server` fires nREPL evals to the orchestrator to call `record-eval-start!` and `record-eval-complete!`. This is "fire and forget" with a 1s timeout - activity tracking should never block an actual eval.

3. **Uptime calculation happens in MCP server code**: Rather than adding `::uptime-seconds` to the session registry (which would need constant updates), the Clojure code sent via MCP calculates uptime from `started-at` at query time.

4. **Health check deferred**: The optional health check (ping with timeout) was not implemented. If needed later, it would require:
   - Sending a simple eval (`(+ 1 1)`) to each session's nREPL
   - Using a short timeout (2s)
   - Adding `health: ok/unresponsive` to the output

---

## Claude Agent SDK Research

**Status**: Complete (2026-01-09)

**Summary**: Evaluated SDK for potential migration from markdown agents.

**Key Findings**:
1. SDK provides `query()` function with `agents` option for programmatic subagent definitions
2. Agent `prompt` field is **guaranteed** to load as system prompt
3. Supports in-process MCP servers via `createSdkMcpServer()` (zero startup overhead)
4. Supports lifecycle hooks (PreToolUse, PostToolUse, etc.) for validation/logging
5. V2 API (`unstable_v2_*`) supports multi-turn sessions

**Recommendation**: Stick with current approach (markdown agents + bin/mcp-server) because:
- Markdown agents work now
- MCP server handles all tool calls cleanly
- No extra Node.js runtime needed

**Reconsider SDK if**:
- Markdown agents regress
- Need per-agent tool restrictions
- Need lifecycle hooks
- Building external product

**See**: `docs/prds/agent-isolation/research/sdk-architecture.md`

---

## Research Questions - RESOLVED

### 1. Multiple nREPL Servers in One JVM - RESOLVED

**Answer: YES, fully supported.**

See `docs/prds/agent-isolation/research/nrepl-multi-server.md` for full details.

- Each `nrepl.server/start-server` call creates independent server
- Sessions are keyed by UUID (global atom, but no conflicts)
- Thread pools are shared (efficient, not a problem)
- Server implements `java.io.Closeable` for clean shutdown

### 2. Context Injection - RESOLVED

**Answer: Intern `*ctx*` directly in agent's namespace, then bind in session.**

The key insight (from Gemini search): intern the var in the target namespace so agents can use `@*ctx*` without qualification.

```clojure
(defn make-context-middleware [ctx-atom target-ns]
  (let [ensure-ns-and-ctx (fn []
                            ;; Create namespace if needed
                            (let [ns-obj (or (find-ns target-ns)
                                             (binding [*ns* (create-ns target-ns)]
                                               (refer-clojure)
                                               *ns*))]
                              ;; Intern *ctx* as dynamic var in TARGET namespace
                              (when-not (ns-resolve ns-obj '*ctx*)
                                (let [v (intern ns-obj '*ctx* nil)]
                                  (.setDynamic v true)))
                              {:ns-obj ns-obj
                               :ctx-var (ns-resolve ns-obj '*ctx*)}))
        setup (delay (ensure-ns-and-ctx))]
    (fn wrap-context [handler]
      (fn [{:keys [session] :as msg}]
        (let [{:keys [ns-obj ctx-var]} @setup]
          (when (and (instance? clojure.lang.Atom session)
                     (not (identical? (get @session ctx-var) ctx-atom)))
            (swap! session assoc
                   #'*ns* ns-obj
                   ctx-var ctx-atom)))  ;; Bind the TARGET ns var, not a central one
        (handler msg)))))
```

**Key points:**
- Use `intern` + `.setDynamic` to create dynamic var in target namespace
- `with-meta '*ctx* {:dynamic true}` does NOT work with intern
- The session binds the TARGET namespace's `*ctx*` var, not a central one

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

## bin/agent-eval - Shell Escaping Issue - SUPERSEDED BY MCP

### The Problem (Historical)

The `!` character was being corrupted when passing code as shell arguments:

```bash
./bin/agent-eval c76a1e81 '(swap! *ctx* assoc :key 1)'  # Failed: \! corrupted the code
```

### Root Cause

**Shell quoting in zsh/bash escapes `!` even in single quotes.** When you run:

```bash
echo '(def test! 42)'
```

The shell outputs `(def test\! 42)` with a backslash before `!`. This is NOT Babashka's fault - it's the shell's behavior. The backslash then gets sent to nREPL, causing a syntax error because `test\!` is tokenized as two symbols.

### Original Workaround (heredocs)

**Use heredocs to pipe code to agent-eval.** Heredocs preserve exact content:

```bash
# CORRECT - heredoc preserves !
cat << 'END' | ./bin/agent-eval c76a1e81
(swap! *ctx* assoc :seon.trading/signals [...])
END
```

### Final Solution: MCP Agent Eval Tool

**Created `bin/mcp-server` to bypass shell entirely.** See [mcp-agent-eval.md](mcp-agent-eval.md).

The MCP server:
1. Claude sends JSON-RPC directly via stdio - no shell involved
2. Parameters are JSON strings - all characters preserved
3. Fast startup with Babashka (~50ms)

**Usage:**
```
agent_eval(session_id="abc12345", code="(swap! *ctx* assoc :key 1)")
```

All special characters (`!`, `$`, backticks, quotes) work correctly.

### Characters That Need Heredocs (for bin/agent-eval manual use only)

- `!` - History expansion in bash/zsh
- `$` - Variable expansion
- `` ` `` - Command substitution
- `"` - Requires escaping in double quotes
- `\` - Escape character itself

### Technical Details

Tested byte-by-byte. The backslash insertion happens at the shell level before the process even starts. Neither `echo`, `printf`, nor direct argument passing preserves the `!` in single quotes.

Only heredocs with quoted delimiter (`<< 'END'`) pass content verbatim.

### MCP Protocol Notes

The MCP server implementation learned:
- **JSON-RPC 2.0 over stdio**: Line-delimited JSON, one message per line
- **stdout is sacred**: Only JSON-RPC messages, never log output
- **stderr for debugging**: Use `DEBUG=1` env var to enable debug logging to stderr
- **Must flush**: Call `(flush)` after every response
- **Babashka works**: cheshire (JSON) and bencode.core are built-in

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
