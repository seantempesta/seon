---
type: decision
status: active
date: 2026-08-01
tags: [decision, architecture, database, runtime, flow]
---

# ADR-012: One process-root store with branch-per-cluster runtime state

## Context

Datahike serializes transactions through the connection writer. Its branches
give each cluster an independent database lineage without duplicating the
physical store. The JVM can share process-local capacity across clusters while
keeping each cluster's database connection, acquired base program context, Flow graphs,
routing state, web service, and lifecycle independent.

The owner replaced one store and JVM per cluster on 2026-07-27 with a single
process-root store and branch-per-cluster databases. The same day's CLJ-only
ruling removed the pod and Shadow build. The 2026-07-28 custody ruling removed
claim epochs, and rulings 2026-08-01 #27 and #29 established one live program
graph per cluster.

## Decision

One JVM process may host several named cluster instances. It owns one physical
Datahike store under the process root and holds one exclusive `flock` for that
store's lifetime. Each cluster owns one named branch and live connection, one
acquired base SCI `ctx`, fresh per-turn forks, its agent and render Flow graphs,
routing state, advertisement, and web service. The process root shares only the
store holder and the bounded
`:compute` and `:io` executors.

Database access is co-located. `seon.db` reads immutable database values from
the current cluster connection; `seon.db/transact!` calls
Datahike's writer and returns either the transaction report or a flat error
value. No internal database transport, remote replica, protocol version, or second
mutation owner exists.

Runs are claimable database state. Presence of
`:seon.cluster.run/process` means held; absence means unheld. Transition
functions execute inside Datahike transactions, and recovery marks dangling
receipts interrupted without re-executing work. There is no claim epoch or
lease clock.

## Consequences

- A second process opening the same physical store refuses at the `flock`
  before Datahike opens.
- Starting or stopping one cluster changes only that cluster's branch-owned
  connection, graphs, program context, routing state, and web service.
- One process failure may stop several hosted clusters; recovery derives each
  cluster independently from its branch facts.
- The browser SSE connection is an external wire. In-process movement uses
  Flow channels and database facts.
- A committed program change installed in the live base is visible to later turn forks within
  its cluster and never crosses into another cluster.

## Owners

- `src/seon/cluster.clj` — process entry, shared executors, cluster instances,
  and lifecycle.
- `src/seon/cluster/store.clj` — process-root `flock`, store holder, branches,
  and transaction boundary.
- `src/seon/db.clj` — co-located application reads over immutable database
  values.
- `src/seon/sci/eval.clj` — one acquired base cluster `ctx`, per-turn forks, and
  cold acquisition.
- `src/seon/cluster/run.clj` and `resources/seon/schemas/seon.cluster.run.edn` — presence
  custody and transactional recovery.

## Related

- [[architecture]] — complete target topology.
- [[agent-runtime]] — agent graphs, run transitions, and recovery.
- The `datahike` and `seon-flow-architecture` skills — exact dependency and
  first-party source seams.
