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
bin/seon start <name|all>   Start (idempotent — no-op if already running)
bin/seon stop <name|all>    Stop (idempotent — no-op if not running)
bin/seon restart <name|all> stop + start
bin/seon status [name]      Show one or all (omitted = all)
bin/seon tail <name>        tail -f logs/<name>.log  (Ctrl-C to exit)
bin/seon logs <name> [n]    Last n lines (default 200)
bin/seon adopt <name> <pid> Register a manually-started PID under <name>
bin/seon cluster reset [n]  DESTRUCTIVE: wipe data/clusters/<n>/store; bounce
                            wire-server + pod when <n> is the cluster THIS
                            supervisor manages (store == $SEON_CLUSTER_DIR/store),
                            else wipe only (fresh DB)
bin/seon cluster create <n> [--ephemeral] [--frozen|--watched]
                            NEW cluster on this supervisor's wire-server:
                            its own store dir + its own pod (pod-<n>, free port)
bin/seon cluster fork <src> <t|--at t> [fork-name]
                            NEW cluster = <src>'s world at basis-t <t> —
                            independent, writable, disposable (see below)
bin/seon cluster destroy <n> stop pod-<n>, delete the db from the live
                            wire-server registry, remove data/clusters/<n>/

```

## `cluster fork` — a cluster's world at a basis-t, live and writable

`bin/seon cluster fork <src> <t|--at t> [fork-name]` (default fork-name
`fork-<src>-<t>`) boots a NEW disposable cluster whose database is `<src>`'s
world exactly at transaction id `<t>`. The primary consumer is error
forensics: `seon.agent.inspect/repro` returns this command verbatim as its
`::fork-hint`, with `t` = the error's `:seon.error/at` (the basis-t the
failing code saw — the error datom itself commits later, so it is not
inside the fork).

Mechanism: `seon.server.registry/fork-db!`, invoked over the wire-server's
loopback socket REPL (7891 — the same supervisor channel `cluster destroy`
uses; never agent-exposed) via `bin/seon-server-call`, the babashka helper
that parses the REPL's printed reply as EDN and keys success on the fn's
own namespaced flag (`::forked?` / `::deleted?`) — the supervisor never
pattern-matches printed EDN. It wraps `datahike.api/fork-database`: a
konserve-layer store copy, the fork's head pointed at the commit whose
`:max-tx` equals `t`, and a fresh deterministic store identity — verified
after the copy (head at `t` + a full history index scan; one retry covers
a copy that raced a live write). Entity ids and tx ids are byte-identical,
so every stored basis-t means the same thing inside the fork. The source's
`blobs/` (turn prompt/reply content) is copied alongside so turn replay
works. Boot then follows `cluster create` exactly — the fork pod's own
`ensure-db` registers the db, so the fork is indistinguishable from a
normal cluster (feed, replay, inspect functions all work; the pod's boot-seed
appends its usual idempotent txs, moving the head slightly past `t` while
the world-state at `t` stays intact). The fork store is independent by
construction: `bin/seon cluster destroy <fork-name>` removes it without
touching the source.

## `start all` / `restart all` / `stop all` — the whole stack, ordered + gated

`bin/seon start all` brings the stack up in dependency order — **cljs-watch → wire-server → pod** — and gates each stage on REAL readiness before starting the next (`stop all` reverses: pod → wire-server → cljs-watch). `jvm` is deliberately NOT in `all` — it's an independent lane; start it explicitly.

Ready gates (`wait_ready` in the script — observed signals, not just log lines):

| Process | Ready when | Bound |
|---|---|---|
| `cljs-watch` | `out/client/main.js` newer than this start, or `Build completed` in the fresh log | 300s |
| `wire-server` | `tmp/seon-cluster-default-req.sock` ACCEPTS a connection (real `nc -U` connect — macOS `nc -z -U` is broken) + `$SEON_WRITER_REPL_PORT_FILE` (default `tmp/seon-writer-repl-port-default`) written | 180s |
| `pod` | `$SEON_PORT_FILE` (default `tmp/seon-port`) written + HTTP answers on `/` | 120s |

On timeout or early death the wait fails LOUD, naming the log to read, and later stages are not started. `bin/seon cluster reset` shares the same `wait_ready` helper.

The pod side cooperates: `seon.store.wire/ping!` (the fail-loud boot gate) retries the wire-server ping for ~10s (5 × 2s rpc timeout, 500ms backoff) before throwing, closing the race where the pod execs before the writer's socket accepts. Boot stays fail-loud — just not fail-instant.

### Auto-prep on dep (git sha) change

Real failure 2026-06-10: a datahike `:git/sha` bump made the first wire-server start download + build the git dep inside its ready window — boot timeouts blew and the pod fail-loud-exited against the missing socket. The supervisor now hashes deps.edn's `:git/url`/`:git/sha` lines into `tmp/proc/<name>/deps-fingerprint` (plus a `~/.gitlibs` presence check) before spawning `wire-server` (`:writer`) or `cljs-watch` (`:cljs`); on change it runs `clojure -P -M:<alias>` + `clojure -X:deps prep :aliases '[:<alias>]'` SYNCHRONOUSLY and loudly (output in `logs/<name>.log`) before the spawn.

## Registered processes

| Name | Command | Log | Ready-when |
|---|---|---|---|
| `pod` | `node out/client/main.js` | `logs/pod.log` | `$SEON_PORT_FILE` (default `tmp/seon-port`) written + HTTP answers on `/` |
| `cljs-watch` | `clj -M:cljs watch client` | `logs/cljs-watch.log` | "Build completed" appears in log |
| `jvm` | `./bin/run` | `logs/jvm.log` | `logs/app.log` shows "Server started" |
| `wire-server` | `clojure -M:writer ...` | `logs/wire-server.log` | `tmp/seon-cluster-default-req.sock` accepting + `$SEON_WRITER_REPL_PORT_FILE` (default `tmp/seon-writer-repl-port-default`) written |

### IMPORTANT — pod ↔ cljs-watch dependency

**`cljs-watch` must be running BEFORE the pod is built or restarted** for MCP eval (`mcp__seon_cljs__eval`) to reach the pod. Reason: shadow-cljs only injects the websocket-based REPL runtime client (`shadow.cljs.devtools.client.node`) into the bundle when compilation runs under a **watcher**. A one-shot `clj -M:cljs compile client` produces a NO-REPL bundle — pod boots and runs, but MCP eval against it fails with "No available JS runtime."

**Don't run `clj -M:cljs compile client` for the pod.** Always go through `bin/seon start cljs-watch` (or restart it after build config changes) and let watch own the bundle. The supervisor enforces this implicitly — `bin/seon start pod` doesn't trigger a build itself.

Recovery if a manual `compile` has overwritten the bundle:

```bash
bin/seon restart cljs-watch  # forces full rebuild with ws client
bin/seon restart pod         # pod loads the REPL-able bundle

```

Full root-cause analysis: [[../prds/agent-runtime/research/shadow-node-runtime-2026-05-23]].

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

The pod's `seon.web.serve` HTTP loopback also writes its bound port to `$SEON_PORT_FILE` (default `tmp/seon-port`, per-cluster — `bin/acme` overrides it to `tmp/seon-port-acme` so a second cluster's pod can't clobber the default's file); `bin/seon status` surfaces the port + reachable URL when the file exists.

## Multi-agent patterns

**Agent A wants the pod up:**

```bash
bin/seon start pod      # idempotent

```

**Agent B wants to watch what the pod is doing:**

```bash
bin/seon tail pod       # safe to run alongside any number of agents

```

**Agent A wants a clean pod after changing core code:**

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
- **JVM REPL operations** `[JVM track — paused]` (`(user/reload)`, `(user/reset)`, `(user/restart-db!)`) — these are inside-the-JVM functions that operate on Integrant components, not on OS processes. They belong to the paused JVM main-app track; the active track is the CLJS pod (`bin/seon ... pod`). Use them for in-process JVM operations when working that track; use `bin/seon restart jvm` only when you actually need a fresh JVM.

## Cross-references

- Supervisor source: `bin/seon`
- Pod HTTP server: `src/seon/web/serve.cljs`
- CLAUDE.md "Process Architecture (IMPORTANT)" — high-level intro
- Logs convention: `logs/<name>.log` (this supervisor) + `logs/app.log` / `logs/error.log` (JVM Timbre/logback)
