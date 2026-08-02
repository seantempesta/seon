---
type: issue
status: open
severity: blocker
tags: [issue, architecture, database, runtime]
---

# Retire active ADRs that authorize the deleted topology

## Problem

Several active ADRs still carry decisions superseded by the fresh
branch-per-cluster JVM and CLJ-only runtime. Because an active ADR is a design
authority, these are not harmless history: they authorize restoring remote
replicas, claim epochs, Shadow reload selection, Integrant lifecycle, and one
JVM per cluster.

## Evidence

- ADR-009 is titled “One cluster JVM per store” and says a cluster owns one
  store/process directory, scale means adding cluster JVMs, and Integrant
  protects the JVM (`docs/seon/architecture/decisions/009-cluster-jvm-topology.md:8-58`).
  `src/seon/cluster.clj:3-17` instead defines one JVM that hosts several
  cluster instances over one process-root store and shared executors.
- ADR-008 declares live `seon.db.protocol`, protocol version 7, persistent
  remote sessions, replica catch-up, and claim-epoch mutation
  (`docs/seon/architecture/decisions/008-database-protocol.md:19-57`). No fresh
  `seon.db.protocol` namespace exists; cluster reads and writes are co-located,
  and `resources/seon/schema/run.edn:59-64` models custody by process presence
  with no epoch.
- ADR-007 says hot reload uses Shadow's Node selection
  (`docs/seon/architecture/decisions/007-runtime-instrumentation.md:35-42`).
  The CLJS build is off; fresh instrumentation is
  `src/seon/instrument.clj` and fresh source publication is the JVM
  `current-src` mechanism.
- ADR-004 calls load-time `schema/register!` the single authored shipped
  schema surface. Current first-party declarations are EDN resources admitted
  by `src/seon/schema/edn.clj`; runtime registrations are a separate admitted
  domain.
- ADR-011 still couples a run claim to a claim-epoch increment despite the
  2026-07-28 no-epoch ruling.

The reader chain is structural: `architecture.md` exposes the decisions
directory as settled design; root `AGENTS.md:419-420` calls decisions settled
ADRs; ADRs cross-link architecture, data-model, UI, toolkit, laws, and
library-grounding; ADR-006 explicitly points readers to ADR-009 as its
superseding current decision. An agent doing the correct ADR lookup therefore
lands on the wrong topology.

## Owner

`docs/seon/architecture/decisions/` owns durable rulings. The current process
topology is defined by `src/seon/cluster.clj`, `src/seon/cluster/store.clj`,
and the fresh operator; schema and instrumentation decisions must align with
their current owners.

## Acceptance

- Every active ADR is revalidated against fresh source and the latest owner
  rulings; superseded decisions become abandoned/history or are replaced by a
  current ADR.
- No active decision authorizes remote database replicas/protocol version 7,
  claim epochs, one JVM per cluster, Integrant lifecycle, or Shadow hot reload.
- Cross-links from abandoned ADRs resolve to the one current decision rather
  than forming a stale authority chain.
