---
type: prd
status: draft
tags: [prd, database]
---
# System Lifecycle Patterns

Reference for agents and humans writing Integrant components in Seon.

---

## Component Lifecycle: suspend/resume vs halt/init

Integrant supports two lifecycle paths during `(reset)`:

1. **halt/init** (default) -- component is stopped and recreated from scratch
2. **suspend/resume** -- component keeps its state, only refreshes if needed

### When to use suspend/resume

Use suspend/resume when the component manages an **expensive or stateful resource** that does not need to restart when code changes:

- **External processes** (Caddy, Tailwind) -- process stays alive, no reason to kill/restart
- **Database connections** (Datalevin server, connection manager) -- reconnecting is slow
- **Network servers** (nREPL, HTTP) -- restarting drops active connections (SSE, REPL sessions)
- **Computed state** (schema registry, runtime DB cache) -- pure values that survive reload

### When halt/init is fine

Use plain halt/init for cheap, stateless components:

- Pure config maps (`:seon.ai.claude/sdk`)
- Components that delegate to an idempotent `init!` function

### The process-alive check pattern

For external process components, resume should check if the process is still alive and restart only if it died:

```clojure
(defmethod ig/suspend-key! ::my-process [_ state] state)

(defmethod ig/resume-key ::my-process
  [_ opts _old-opts old-state]
  (if (and (:process old-state) (.isAlive ^Process (:process old-state)))
    old-state  ; process still running, keep it
    (do (ig/halt-key! ::my-process old-state)
        (ig/init-key ::my-process opts))))

```

---

## Init patterns

### Stale process cleanup

External process components should kill stale processes on init to prevent port conflicts after a crash:

```clojure
(defn- kill-stale!
  "Kill stale processes from previous runs."
  []
  ;; Use a specific match pattern -- NOT just the binary name
  (let [builder (ProcessBuilder. ["pkill" "-f" "caddy run"])]
    ...))

```

Match the specific invocation (e.g., `"caddy run"`) rather than just the binary name (`"caddy"`) to avoid killing unrelated processes.

### Port verification after start

After starting an external process, verify the port is actually accepting connections:

```clojure
(defn- port-open? [port timeout-ms]
  (try
    (let [socket (java.net.Socket.)]
      (.connect socket (java.net.InetSocketAddress. "localhost" (int port)) (int timeout-ms))
      (.close socket)
      true)
    (catch Exception _ false)))

```

Log a warning if the port is not open yet but do not fail -- the process may need time for TLS setup or other initialization.

### Log directory

Use `(.mkdirs (clojure.java.io/file "logs"))` inline before creating log files. It is idempotent -- no need for a separate helper function.

---

## What `(reset)` does

1. Calls `suspend-key!` on all components in **reverse dependency order**
2. Refreshes changed namespaces via `clojure.tools.namespace`
3. Calls `resume-key` on all components in **dependency order**
4. Components without suspend/resume fall back to halt/init automatically

This means a component with suspend/resume will keep its state across code reloads. The handler refresh pattern (using `requiring-resolve` for late binding) means the HTTP server can pick up new route code without restarting.

---

## Component state return values

Always return a **map** from `init-key`, not the raw resource. Include:

| Key | When | Purpose |
|-----|------|---------|
| `:process` | External processes | The `Process` object for lifecycle management |
| `:pid` | External processes | PID for REPL introspection (`(.pid process)`) |
| `:url` | Network services | The URL to reach the service |
| `:upstream` | Reverse proxies | The upstream URL being proxied |
| `:port` | Servers | The port being listened on |
| `:config-file` | File-configured processes | Path to config used (enables resume comparison) |

Example from Caddy:

```clojure
{:process process
 :config-file config-file
 :pid (when process (.pid process))
 :url "https://localhost:3030"
 :upstream "http://localhost:8080"}

```

This makes REPL introspection natural:

```clojure
(:url (:seon.web/caddy @integrant.repl.state/system))
;; => "https://localhost:3030"

```

---

## Naming convention

**Component key matches the namespace that defines it:**

| Key | Namespace |
|-----|-----------|
| `:seon.web/caddy` | `seon.web.caddy` |
| `:seon.web/tailwind` | `seon.web.tailwind` |
| `:seon.web.server/http-server` | `seon.web.server` |
| `:seon.db.datalevin/server` | `seon.db.datalevin.server` |

An agent sees the key in `system.edn` and immediately knows where to find the code.

`resources/system.edn` is the **single source of truth** for the dependency graph. Dependencies are declared with `#ig/ref`.

---

## Survival strategy

Components are grouped by how critical they are and whether they survive `(reset)`:

### Critical infrastructure (survive everything)

These components have suspend/resume and only restart if their config changes:

- **Datalevin server** -- database engine, all data depends on it
- **Connection manager** -- connection cache, expensive to rebuild
- **Runtime DB** -- runtime registry + code graph, hydrated from Datalevin
- **nREPL** -- REPL sessions, losing them is disruptive

### Process components (survive reset, restart if dead)

External processes with the process-alive check pattern:

- **Caddy** -- reverse proxy for HTTPS, no state to refresh
- **Tailwind** -- CSS watcher, filesystem-driven

### Stateless survivors

Pure values or late-bound references:

- **HTTP server** -- handler uses `requiring-resolve` so new code is picked up without restart
- **Schema registry** -- pure value, no resources to manage
- **Code scanner** -- writes to runtime DB which survives, no need to re-scan

### Recreated on reset

Components without suspend/resume that are cheap to restart:

- **Claude SDK config** -- pure config map, instant init
