---
type: reference
status: active
tags: [reference, agent, database]
---

# Process management — `bin/seon`

`bin/seon` is the single operator door for the local Seon application. It owns
process registration, logs, dependency ordering, readiness checks, and cluster
database lifecycle. Calls are idempotent and serialized, so several development
agents can inspect or request the same transition without racing each other.

## The processes

| Name | Responsibility | Default log | Ready when |
|---|---|---|---|
| `cljs-watch` | Builds and hot-reloads `out/client/main.js` | `logs/cljs-watch.log` | the current build completes |
| `database-server` | Runs `seon.db.server`, the sole Datahike writer and transaction feed/replay source | `logs/database-server.log` | request socket accepts and the diagnostic port file exists |
| `pod` | Runs the Node agent runtime and web UI | `logs/pod.log` | auto-boot completes and `/` remains healthy |
| `diffusion-server` | Optional local DiffusionGemma worker | `logs/diffusion-server.log` | its health endpoint answers |

`start all` runs `cljs-watch → database-server → pod`; `stop all` reverses that
order. The optional diffusion worker is registered but is not part of `all`.
The former embedded JVM application and per-agent JVMs no longer exist.

## Daily commands

```bash
bin/seon start all
bin/seon stop all
bin/seon restart all

bin/seon status
bin/seon status pod database-server
bin/seon logs all 120
bin/seon logs pod 200
bin/seon tail database-server

bin/seon restart pod
bin/seon restart cljs-watch
bin/seon print-cmd database-server
bin/seon print-env
```

`logs all` is the default debugging front door: it merges recent process logs,
labels each source, and orders timestamped lines. `tail` follows one process.
Every fresh start truncates that process's log, so read useful evidence before a
restart.

`status` also prints the pod URL from `SEON_PORT_FILE`; the default web UI is
`http://127.0.0.1:7890`.

## Start and readiness behavior

Starting a process does more than fork a command:

1. Acquire the shared lifecycle lock and the process lock.
2. Reject an unmanaged listener rather than unlinking a live socket or hiding a
   port collision.
3. Prepare changed Git dependencies before the process's readiness window.
4. Remove readiness files that describe a dead prior lifetime.
5. Spawn into an owned process group and record its PID identity.
6. Wait for an observed readiness condition, failing loudly on early exit,
   timeout, or a core-fault marker.

The current readiness bounds are 300 seconds for `cljs-watch`, 180 seconds for
`database-server`, and 120 seconds for a pod. A pod must pass three consecutive
health observations after its auto-boot marker; a briefly serving process that
is still unwinding toward a crash is not declared ready.

On failure, use the exact log named by the supervisor. Do not follow a failed
managed start with a manual process invocation; that creates two ownership
paths.

## Builds and dependency preparation

The watched build must own `out/client/main.js`. A one-shot Shadow compile does
not include the development runtime used by the CLJS evaluation connection.
When a manual compile replaced the bundle, recover through the supervisor:

```bash
bin/seon restart cljs-watch
bin/seon restart pod
```

Before starting `database-server` or `cljs-watch`, the supervisor fingerprints
the Git dependencies in `deps.edn` and checks their local preparation output.
When necessary it runs the alias-specific download and prep synchronously. The
public reusable door is:

```bash
bin/seon prep
```

Downstream launchers should call that command instead of reconstructing the
`tools.deps` invocation.

## Cluster database lifecycle

A cluster is one named database, one root agent, task agents, and one Node pod.
The database server can host several named databases. These operations are
deliberately destructive where stated and share the same lifecycle lock as
ordinary process transitions.

### Reset the managed cluster

```bash
bin/seon cluster reset default
```

This stops the managed pod and database server, removes only the managed
cluster's `db/` directory, restarts both through the normal readiness gates, and
lets pod boot seed the fresh database. It does not preserve prior database
facts. Never emulate reset by deleting files under a running server.

### Create a separate cluster

```bash
bin/seon cluster create experiment
bin/seon cluster create sample --ephemeral
bin/seon cluster create sample --ephemeral --watched
```

The database server is started and proven ready first. The new database is
ensured through the normal pod boot path, and `pod-<name>` gets an ephemeral
HTTP port recorded in `tmp/seon-port-<name>`.

Durable clusters default to the watched development bundle. Ephemeral clusters
default to a frozen bench bundle so a source save cannot hot-patch a running
measurement; `--watched` and `--frozen` explicitly override the default.

### Fork at an exact database basis

```bash
bin/seon cluster fork default 536870990
bin/seon cluster fork default --at 536870990 repro-example
```

Forking creates an independent writable database at that transaction id, copies
the cluster's referenced turn blobs, starts its own pod, and leaves the source
untouched. `seon.agent.inspect/repro` returns a ready `:fork-hint` for a recorded
core fault. The fault datom is later than the captured `:seon.error/at`, so it is
intentionally absent from its own reproduction database.

The implementation is `seon.db.registry/fork-database!`, invoked through the
database server's loopback diagnostic channel by `bin/seon-server-call`; agents
do not receive that capability.

### Destroy a created cluster

```bash
bin/seon cluster destroy experiment
```

This stops `pod-experiment`, closes and deletes its registered database, and
removes `data/clusters/experiment/`, including blobs. The supervisor refuses to
destroy its own managed cluster; use `cluster reset` for that database.

## Fault monitoring

```bash
bin/seon watch-faults
bin/seon watch-faults --cluster experiment
```

This blocks until a new unexpected `SEON-CORE-FAULT` appears, prints the marker
and nearby pod log lines, then exits successfully so a supervising task can
triage it. Expected test markers are ignored. The database workflow after the
marker is `seon.agent.inspect/errors → error → repro`; the web UI debug page is
a view of the same facts, not a second error system.

## Registration, locks, and adoption

Each managed process has state under `tmp/proc/<name>/`, including the PID,
process-start identity, exact command, start time, ownership marker, and lock.
Locks are owner-PID symlinks: a dead owner is reclaimed automatically while a
live owner makes a concurrent caller wait.

If a deliberately manual process must become managed:

```bash
bin/seon adopt pod <pid>
```

Inspect the PID first. Adoption records lifecycle ownership but cannot recover
stdout that was never directed to the managed log. After an interrupted
operator command, use `bin/seon status` before touching a lock; the supervisor
prints the exact recovery path when manual removal is actually warranted.

## Environment seams

The supervisor exports one coherent environment to both processes. Important
overrides are:

| Variable | Meaning |
|---|---|
| `SEON_CLUSTER_DIR` | managed cluster directory; database lives at `<dir>/db` |
| `SEON_REQ_SOCK` / `SEON_PUB_SOCK` | database request and committed-transaction sockets |
| `SEON_WRITER_REPL_PORT` | loopback-only database diagnostic port, default 7891 |
| `SEON_WRITER_REPL_PORT_FILE` | per-supervisor file containing that port |
| `SEON_PORT` / `SEON_PORT_FILE` | pod HTTP selection and bound-port record |
| `SEON_CONFIG` | selected runtime manifest path |
| `SEON_EXTRA_SRC` / `SEON_EXTRA_PRELOAD` / `SEON_EXTRA_NPM` | downstream CLJS overlay inputs |
| `SEON_SHELL` / `SEON_WEB` / `SEON_EMBED` | host-owned capability and feature gates |

Use `bin/seon print-cmd <process>` and `bin/seon print-env` to inspect resolved
values without spawning anything.

## What the supervisor does not manage

- Inspect AI evaluation runs under `src-inspect-ai/`.
- Browser tabs and the user's browser session.
- Remote production infrastructure.
- Arbitrary database calls. The only privileged diagnostic calls used here are
  the named cluster registry operations in `bin/seon-server-call`.

Keep process operations in this supervisor instead of adding compatibility
launchers.

## References

- Supervisor implementation: `bin/seon`
- Database process: `src/seon/db/server.clj`
- Pod boot: `src/seon/client.cljs`
- Pod HTTP server: `src/seon/web/serve.cljs`
- Active runtime roadmap: [[../prds/runtime-reliability/roadmap]]
