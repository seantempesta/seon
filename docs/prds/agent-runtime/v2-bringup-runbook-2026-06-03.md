---
type: reference
status: active
tags: [reference, agent, database]
---

# V2 single-DB bring-up runbook (2026-06-03)

How to bring the **V2 platform Host** up so it is LIVE and reachable, plus the
honest boundary between what runs today and what is the next tick (P1).

The V2 Host is the JVM datahike **wire-server** (`src/seon/server/wire.clj`),
launched via the `:writer` alias. It owns ONE datahike connection (single-DB
today), serves DB ops over a UDS request socket, broadcasts tx events over a UDS
pub socket, and — with `--repl-port` — opens a loopback-only Clojure socket REPL
for diagnostics. On boot it prints `[writer] ready`.

"Single-DB V2 up" means exactly: the wire-server running + reachable (its UDS
sockets + its socket-REPL). Nothing more. Multi-DB/cluster wiring and the
Node-side wire client are NEXT TICK (see the LIVE NOW vs NEXT TICK section).

> Naming note: the socket/path strings carry `cluster` in their names
> (`tmp/seon-cluster-default-*.sock`, `data/clusters/default/store`) for the
> eventual multi-DB layout. Those are CLI argument strings, not code
> identifiers. "Session" is retired as a platform term; do not introduce new
> "session"-named code/vars/paths.

## Start V2 (the Host)

The wire-server is a registered `bin/seon` process. Start is idempotent and
multi-agent-safe (same mutex/logging pattern as `pod` / `cljs-watch` / `jvm`):

```bash
bin/seon start wire-server      # idempotent — no-op if already running
bin/seon tail wire-server       # watch the boot log (Ctrl-C to exit)
bin/seon status                 # all processes, PIDs
```

Underlying launch command (registered in `bin/seon`):

```bash
clojure -M:writer \
  --backend file \
  --path data/clusters/default/store \
  --req-sock tmp/seon-cluster-default-req.sock \
  --pub-sock tmp/seon-cluster-default-pub.sock \
  --repl-port 7891
```

Ready signal — `logs/wire-server.log` ends with:

```text
[writer] starting with {:backend file, :path data/clusters/default/store, ...}
[writer] datahike ready; basis-t= 536870912
[writer] pub socket: tmp/seon-cluster-default-pub.sock
[writer] req socket: tmp/seon-cluster-default-req.sock
[writer] dev REPL (127.0.0.1): 7891
[writer] ready. PID= <pid>
```

The shadow watcher (`cljs-watch`) and the MVP `:client` pod (`pod`) are
independent V0/MVP processes and should already be up; bringing V2 up does NOT
touch them. Confirm with `bin/seon status` — do NOT restart `cljs-watch` or the
MCP server.

## Socket-REPL into the Host

`--repl-port 7891` opens a Clojure socket REPL bound to **127.0.0.1 only**
(never reachable off-host). The chosen port is written to a port file so tools
can discover it:

- Port file: `tmp/seon-writer-repl-port` (contains `7891`)
- Connect: any raw-TCP REPL client to `127.0.0.1:7891`, e.g.
  `nc 127.0.0.1 7891` or a bash `/dev/tcp/127.0.0.1/7891` redirect.

The live datahike connection lives in the wire ns's private `state` atom. Reach
it by switching into the ns (the var is `^:private`):

```clojure
(in-ns 'seon.server.wire)
(require '[datahike.api :as d])
(def conn (:conn @state))
(:max-tx (d/db conn))                                   ; current basis-t
(d/transact conn [{:db/ident :wire.smoke/probe
                   :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one}])
(d/transact conn [{:wire.smoke/probe "hello"}])
(d/q '[:find ?v :where [?e :wire.smoke/probe ?v]] (d/db conn))
```

Verified round-trip 2026-06-03: basis-t advanced `536870912 → 536870913 →
536870914`; the query returned `#{["hello-from-runbook-2026-06-03"]}`.

## Spawn Node agents

Node agents register as shadow-cljs runtimes under the single
`:client`/`:node-agent` worker. Each `node …` invocation is one addressable
runtime; run N times for N agents. The public handle is the `--agent-id` string
(internal shadow client-id is unstable across crash+restart, so resolution is
by agent-id).

```bash
# Keep the node-agent build compiling (separate from the :client build):
clj -M:cljs watch node-agent           # output: out/node-agent/main.js

# Spawn agents (one process per agent-id):
node out/node-agent/main.js --agent-id a1
node out/node-agent/main.js --agent-id a2
```

Each prints a greppable ready line: `node-agent ready: agent-id=a1 pid=<pid>`.

## Eval into an agent by agent-id (via MCP)

The `seon_cljs` MCP server (`bin/mcp-server-cljs`, in `.mcp.json`) evals into a
chosen runtime. Eval-by-agent-id uses the `agent_id` param:

```text
mcp__seon_cljs__eval  { code, agent_id, timeout_ms }      # agent_id selects the agent
```

The server resolves `agent_id → current client-id` per call (self-heals across
agent restarts — a respawned agent re-registers its new client-id under the same
agent-id; no MCP restart needed). Per-agent churn already works.

> ONE-TIME HARNESS ACTION: activating the `agent_id` param requires a single
> reload of the `seon_cljs` MCP server (harness-side — restart the MCP server
> connection so the new tool param shape is picked up). This is a one-time
> action, NOT per-agent. After that reload, eval-by-agent-id and agent
> crash/respawn churn need no further MCP restarts.

## LIVE NOW vs NEXT TICK

### LIVE NOW (verified 2026-06-03)

- **Single-DB wire-server (the V2 Host).** Boots via `bin/seon start
  wire-server` / `clojure -M:writer …`, prints `[writer] ready`, owns one file-
  backed datahike conn (`data/clusters/default/store`), binds the req + pub UDS
  sockets.
- **Loopback socket-REPL on the Host.** `--repl-port 7891`, 127.0.0.1 only,
  port file `tmp/seon-writer-repl-port`. DB read + transact + query round-trip
  proven against the live conn (basis-t advances, written datom comes back).
- **Node-agent shadow runtimes + eval-by-agent-id.** N Node agents register
  under one shadow worker; the `seon_cljs` MCP `agent_id` param targets each by
  id; per-agent crash/respawn churn self-heals with no MCP restart (one-time MCP
  reload to activate the param, see above).

### NEXT TICK (P1 — NOT built)

- **Multi-DB / cluster wiring.** The session→registry rename (`session.clj`
  identifiers renamed atomically), conn-per-request resolution, per-DB tx
  broadcast, and the `listen!` hook. Today the wire-server is single-DB: one
  conn, one `"db-name" "default"` in the broadcast envelope.
- **Node-side wire client.** The CLJS guest's DB-client-over-the-wire (so a
  node agent actually performs a DB op against the Host via the UDS req socket).
  Until this lands, **node-agent ↔ DB is NOT wired** — a node agent cannot yet
  do a DB op. Eval-into-agent and the Host's own socket-REPL DB access are the
  only DB paths live today.

## Teardown / restart

```bash
bin/seon restart wire-server    # stop + start (atomic from other agents' POV)
bin/seon stop wire-server       # SIGTERM, then SIGKILL after 2.5s
bin/seon logs wire-server 200   # last 200 log lines
```

Do NOT `bin/seon restart cljs-watch` or restart the MCP server as part of V2
bring-up — those are MVP-track processes.
