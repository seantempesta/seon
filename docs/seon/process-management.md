---
type: reference
status: active
tags: [reference, agent, database]
---

# Process management — `bin/seon`

`bin/seon` is the single operator for the local development system. It derives
one process graph, builds one artifact closure, and reconciles the complete
watcher → database-server → pod target under one kernel file lock. There are no
public per-process start, stop, adopt, prep, or alternate supervisor paths.

## Daily commands

```bash
bin/seon up                     # build and reconcile; bare bin/seon is the same
bin/seon up --open              # also open the ordinary agent view
bin/seon status [--edn]         # derive health from records + live probes
bin/seon logs [--lines 200]     # current lifetime logs for every process
bin/seon logs pod --follow      # follow one current process lifetime
bin/seon restart [--open]       # drain, rebuild, and reconcile
bin/seon down                   # drain the complete target
bin/seon doctor [--edn]         # host prerequisites and artifact status
bin/seon cluster reset default  # destructive, scoped database reset
```

The default web UI is `http://127.0.0.1:7890`. `up` and `restart` return only
after every direct readiness probe passes; they print the actual bound URL.

## Managed graph

| Process | Responsibility | Ready when |
|---|---|---|
| `watcher` | Incremental Shadow CLJS development build | its latest bounded log window ends in a successful client build |
| `writer` | Sole Datahike writer and transaction feed/replay server | its diagnostic port file exists and request socket accepts |
| `pod` | Node agent runtime and Datastar web UI | auto-boot is present in its bounded log window and HTTP answers |

Dependencies are data: the pod depends on watcher and writer. Start order is a
topological projection; stop order is its reverse.

## Build and reconciliation

In a source checkout, every `up` performs the canonical dependency preparation,
writer uberjar build and preflight, client build, self-host bootstrap build,
macro repair, and CSS build. The watcher and pod are quiesced first, so no live
runtime can read a partially rebuilt output closure. A manifest is published
only after all required outputs exist and their content digests are known.

Process identity is exact argv + the relevant projected environment + artifact
digest. Ambient Codex/session variables do not cause restarts. Changed writer
content drains the pod then writer; changed application content drains the pod.
Unchanged, ready processes are retained.

## Ownership and failure behavior

Each process record contains its PID, OS start instant, process group, argv,
environment digest, artifact digest, start time, and lifetime log. PID reuse is
never treated as ownership. A dead recorded leader with a still-live process
group fails closed instead of signaling an unverifiable group.

Lifecycle transitions use an OS file lock, not a stale owner file. Closing the
operator or crashing it releases the kernel lock. State and artifact manifests
are atomically replaced. Read-only `help`, `status`, and `doctor` do not create
operator directories.

Readiness rejects unmanaged listeners and never unlinks their evidence. On a
failed transition, use the log path in the error or `bin/seon logs`; do not
start an ad-hoc replacement process.

## Database reset

```bash
bin/seon cluster reset default
```

The name must equal the explicitly configured cluster. Reset stops the pod and
writer, removes only `<SEON_CLUSTER_DIR>/db`, and reconciles the same graph and
artifact again. It does not preserve prior database facts. Never delete database
files while the writer is running.

## Environment seams

| Variable | Meaning |
|---|---|
| `SEON_CLUSTER_DIR` | managed cluster directory; database is `<dir>/db` |
| `SEON_PROC_DIR` / `SEON_LOG_DIR` | isolated operator records and lifetime logs |
| `SEON_REQ_SOCK` | persistent database-session request socket |
| `SEON_WRITER_REPL_PORT` / `SEON_WRITER_REPL_PORT_FILE` | loopback diagnostic port selection and record |
| `SEON_PORT` / `SEON_PORT_FILE` / `SEON_BIND` | Bun pod HTTP selection, bound-port record, and bind address |
| `SEON_FEED_COMPRESSION` | Datastar response encoding: identity by default, explicit `gzip` for remote clients |
| `SEON_WRITER_PROC_DIR` | process directory owning the one JVM writer when this Bun runtime does not |
| `SEON_CONFIG` | optional runtime manifest selected by the pod |
| `SEON_EXTRA_SRC` / `SEON_EXTRA_PRELOAD` | downstream CLJS overlay inputs |
| `SEON_SHELL` / `SEON_WEB` / `SEON_EMBED` | host-owned capability and feature gates |

The invoking environment wins over `.env`; `.env` is parsed as data and never
executed as shell code. All child processes receive the same derived environment.

## References

- Operator launcher: `bin/seon`
- Operator implementation: `script/seon/dev/`
- Database process: `src/seon/db/server.clj`
- Pod boot: `src/seon/client.cljs`
- Active roadmap: [[../prds/runtime-reliability/roadmap]]
