---
type: reference
status: active
tags: [reference, agent, dashboard]
---

# Process Management — `bin/seon`

The supervisor script that owns process lifecycle for the Seon dev stack. Multi-agent-safe by design: any number of agents (Platform, MVP, future tracks, Sean himself) can call any subcommand at any time, and the supervisor arbitrates without conflicts.

## Why this exists

Before `bin/seon`, processes were "owned" by whichever agent or terminal launched them:

- Two agents both running `node out/client/main.js` → port collision, second one fails opaquely.
- One agent calling `pkill -f seon.runner` → kills the JVM another agent was using.
- Logs scattered across terminals → no shared way to tail.
- "What's running right now?" required `lsof -i :PORT` per port.

The supervisor removes ownership. State (PIDs, start times, logs) lives in known files (`tmp/proc/<name>/`, `logs/<name>.log`); concurrency is guarded by a per-process mkdir mutex; commands are idempotent.

## Subcommands

```text
bin/seon start <name>       Start (idempotent — no-op if already running)
bin/seon stop <name>        Stop (idempotent — no-op if not running)
bin/seon restart <name>     stop + start
bin/seon status [name]      Show one or all (omitted = all)
bin/seon tail <name>        tail -f logs/<name>.log  (Ctrl-C to exit)
bin/seon logs <name> [n]    Last n lines (default 200)
bin/seon adopt <name> <pid> Register a manually-started PID under <name>
```

## Registered processes

| Name | Command | Log | Ready-when |
|---|---|---|---|
| `pod` | `node out/client/main.js` | `logs/pod.log` | `tmp/seon-port` exists |
| `cljs-watch` | `clj -M:cljs watch client` | `logs/cljs-watch.log` | "Build completed" appears in log |
| `jvm` | `./bin/run` | `logs/jvm.log` | `logs/app.log` shows "Server started" |

Add a new process by editing the `process_command` case branch at the top of `bin/seon` (plus `process_ready_hint` and `all_processes` if you want it in default-status output).

## Concurrency model

Each process has a `tmp/proc/<name>/lock/` directory used as an atomic mutex (`mkdir` is atomic on POSIX, works on macOS without `flock`). On entry to `start`/`stop`/`restart`/`adopt`, the script:

1. Acquires the lock (busy-wait up to 10s, then errors with stale-lock recovery hint).
2. Sets a `trap` to release the lock on EXIT/INT/TERM.
3. Performs the operation.
4. Lock auto-released as the script exits.

Two agents calling `bin/seon start pod` simultaneously: one wins the mkdir, starts the process, releases the lock; the second one acquires the lock, sees the pid file + alive PID, no-ops with "already running" message. No collisions.

## State files

```text
tmp/proc/<name>/
├── pid           PID of the running process
├── cmd           Exact command launched (forensics)
├── started-at    UTC ISO timestamp of start
└── lock/         Mutex directory (briefly held during operations)
```

PID + start-at survive across `bin/seon` invocations. They're cleared by `stop`.

## Logs

Every supervised process logs to `logs/<name>.log`:

- `bin/seon tail <name>` is `exec tail -f logs/<name>.log` — multiple agents can tail simultaneously.
- `bin/seon logs <name> [n]` is one-shot last-n.
- Logs are truncated on each fresh `start` so `tail` doesn't show stale runs. Use `bin/seon logs <name>` if you need history before restart.

The pod's `seon.web.serve` HTTP loopback also writes to `tmp/seon-port`; `bin/seon status` surfaces the port + reachable URL when the file exists.

## Multi-agent patterns

**Agent A wants the pod up:**

```bash
bin/seon start pod      # idempotent
```

**Agent B wants to watch what the pod is doing:**

```bash
bin/seon tail pod       # safe to run alongside any number of agents
```

**Agent A wants a clean pod after changing substrate code:**

```bash
bin/seon restart pod    # stop + start, atomic from caller's view
```

**Agent B was tailing while A restarted:**

The `tail -f` follows the truncated-then-appended log — they'll see "log truncated" briefly, then the new pod's output stream in.

**Any agent wants to know the state:**

```bash
bin/seon status
# ● pod  pid=12345  started=2026-05-23T18:45:21Z
# ○ cljs-watch  (not running)
# ○ jvm  (not running)
#   pod port: 7890  →  http://127.0.0.1:7890
```

## Adoption — manually started processes

If a process is running in a foreground terminal (e.g., `node out/client/main.js` in a watched tab), the supervisor doesn't know about it. Two options:

1. **Adopt it** — `bin/seon adopt pod <PID>`. From then on, `bin/seon stop pod` works on it. The foreground terminal still owns stdout; no log file is managed.
2. **Kill it and re-start under supervision** — `bin/seon start pod` will detect the port collision and the underlying node will fail; manually kill, then `start`.

## Stale lock recovery

If `bin/seon` itself is SIGKILLed mid-operation, the lock dir may persist. The supervisor will busy-wait up to 10s on subsequent invocations, then error with a hint. Manual recovery:

```bash
rm -rf tmp/proc/<name>/lock
```

This should be rare. Normal Ctrl-C and most other signals trigger the trap that cleans up.

## What `bin/seon` does NOT manage

- **Caddy** (port 3030) — separate process, not currently supervised. Run manually if you need HTTPS proxy.
- **MCP servers** (`bin/mcp-server`, `bin/mcp-server-cljs`) — launched by Claude Code via the MCP integration, not supervised here.
- **JVM REPL operations** (`(user/reload)`, `(user/reset)`, `(user/restart-db!)`) — these are inside-the-JVM verbs that operate on Integrant components, not on OS processes. Keep using them for in-process operations; use `bin/seon restart jvm` only when you actually need a fresh JVM.

## Cross-references

- Supervisor source: `bin/seon`
- Pod HTTP server: `src/seon/web/serve.cljs`
- CLAUDE.md "Process Architecture (IMPORTANT)" — high-level intro
- Logs convention: `logs/<name>.log` (this supervisor) + `logs/app.log` / `logs/error.log` (JVM Timbre/logback)
