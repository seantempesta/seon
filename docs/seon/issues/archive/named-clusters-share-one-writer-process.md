---
type: issue
status: superseded
severity: blocker
tags: [issue, architecture, operator, database, runtime]
---

# Give each named cluster its own writer process

## Problem

Named clusters now own their host, pod, web-render process, database path, and
endpoint files, but clusters of the default artifact flavor still route all
transactions through the default operator's writer process. One writer crash,
queue storm, or heap exhaustion can therefore affect several database stores.

This is a known implementation shape that now contradicts owner ruling O9:
the cluster/store is the isolation boundary and shares no mutable state.

## Evidence

`seon.dev.cluster/open!` still describes an autonomous cluster “through the
shared writer” (`script/seon/dev/cluster.clj:252-267`).
`seon.dev.process/owned-process-graph` omits `writer-id` when the target launch
descriptor points at an external writer owner
(`script/seon/dev/process.clj:224-247`). The live `agentload0726` host command
used its own database path but the default writer socket:

```text
database-path .../data/clusters/agentload0726/db
writer-socket-path .../tmp/seon-cluster-default-req.sock
```

`cluster status agentload0726 --edn` reported writer PID `14973` as an external
dependency, while the named host/pod/web-render had private records. The
25-agent rung's losing run-open CAS errors appeared in that default writer's
log, proving the live route.

The earlier dependency audit already measured the failure domain in
[[../../prds/sci-execution-runtime/research/audit-benchmark-pkg-readiness-2026-07-21]].

## Owner

`seon.dev.launch` owns process/store coordinates and
`seon.dev.process` owns the derived process graph. The surviving architecture
requires one writer process per database store; `bin/acme` is the existing
separate-operator precedent.

## Acceptance

- A newly named cluster derives and starts one writer process whose database
  store and process directory belong to that cluster.
- Its host, pod, and web-render connect only to that writer's endpoint.
- Killing, exhausting, closing, or resetting one named cluster leaves the
  default writer generation and readiness unchanged.
- No migration is introduced; reset remains delete-and-reapply.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
