---
type: prd
status: planned
tags: [prd, database, flow, agent]
---

# Database authority mesh roadmap

## Outcome

One protocol-defined authority service hosts many isolated cluster databases.
The JVM/Datahike implementation maintains one connection and one family of
immutable indexes per active database, executes reads concurrently, preserves
one ordered write lane per database, shares identical query work and encoding,
and releases every process-local resource when demand reaches zero. Separately
supervised Bun agent children gain real parallel CPU and failure isolation
without copying Datahike indexes into every process.

Clients connect directly to the database they address. Full replicas receive
only that database's ordered commits; query clients receive only requested
values and explicitly owned notifications. No global transaction broadcast,
second cache, second subscription system, or JVM object crosses the protocol.

The protocol names database semantics rather than Datahike or JVM mechanics, so
future Bun, Rust, cloud, Tauri, or platform clients can implement or consume the
capabilities they support without changing application data shapes.

## Optimization order

Choose designs by this order, measuring every dimension rather than optimizing
one microbenchmark:

1. correctness, exact identity, ordering, fencing, replay, and cleanup;
2. compute once per immutable database value and exact semantic request;
3. retain one index/cache/resource owner and release it at zero demand;
4. avoid copies, serialization, adapter layers, broadcasts, and event-loop hops;
5. maximize safe parallel reads and independent-database progress;
6. bound memory, queues, work, and slow consumers;
7. expose the strongest useful interface—coordinates, cancellation, resource
   usage, cache evidence, capabilities, and errors as ordinary data; and
8. delete superseded mechanisms and vocabulary.

## Existing mechanisms to compose

- Datahike `DB` is the immutable indexed database value.
- Seon's existing complete coordinate is
  `{database-id, branch, commit-id, t}`; use it only where attachment and
  temporal position must cross a boundary.
- Datahike's global weighted query-result cache keys raw database values by
  `[hash max-tx max-eid]`, records query attribute dependencies, and propagates
  unaffected entries to a transaction's child database value.
- Datahike `connect` shares a physical connection and increments its acquisition
  count; `release` decrements it and the final releaser drains and closes writer,
  secondary-index, store, and registry resources.
- Datahike `listen!`/`unlisten!` own keyed process-local callbacks.
- The Datahike pod assigns explicit IDs to immutable database values and exposes
  `release-db`.
- Konserve separately owns store-node caches and store release.
- Bun native sockets provide the direct bounded local transport; Bun children
  provide isolated agent compute, not database authority.

## Earliest unsettled contract

Prove the identity and lifetime of one cached Datahike database value when one
JVM hosts multiple physical databases and branches. Until that is settled, no
consumer may invent a snapshot handle, connection lease, per-database cache, or
subscription registry.

Integrated proof closes the contract when two identical-looking databases,
sibling branches, raw and temporal values, concurrent connections, cache hits,
transaction propagation, release, and reconnect all retain correct isolation
and bounded resources.

## Ordered research and implementation

1. Execute [[research/source-grounded-research-tasks-2026-07-15]] tasks 1–3:
   database-value identity, existing cache characterization, and single-flight
   ownership.
2. Settle tasks 4–5: compose protocol sessions with Datahike connection
   references and choose between the existing coordinate and pod database-value
   handle without keeping both.
3. Specify task 6's versioned query operations inside `seon.db.protocol`; keep
   `seon.db` the sole application API and make all remote execution honestly
   asynchronous.
4. Settle tasks 7–8: one Datahike listener per active database where sufficient,
   session-owned listener keys, scoped cache eviction, and final connection
   release as the database-idle boundary.
5. Measure task 9's separate query-result, Konserve node, secondary-index,
   encoded-result, and in-flight-work budgets before combining any metric.
6. Prove task 10's CLJ/CLJS interface, then implement one JVM authority registry
   with a fair ordered writer per database and bounded concurrent reads across
   databases and database values.
7. Connect isolated Bun agent children directly through the native framed
   protocol. They own inference/eval/tool work and no Datahike connection or
   index copy.
8. Add exact protocol conformance fixtures so another authority implementation
   can reproduce database identity, query, pull, transaction, history, branch,
   fencing, replay, cancellation, errors, and release semantics.
9. Run multi-database and multi-agent density proof, then remove the old
   one-JVM-per-cluster and broadcast assumptions in one coordinated cut.

## Open design space

The research may select a better internal seam than this roadmap anticipates.
Candidates remain open when they remove work or improve the interface:

- single-flight inside Datahike versus the JVM protocol dispatcher;
- existing coordinate versus Datahike pod-style database-value handles;
- one cluster-local UI replica versus all reads at the authority;
- shared encoded response bytes and named cacheable projections;
- direct UDS locally and TLS/WebSocket/HTTP transports remotely;
- connection release immediately versus an explicitly measured idle grace;
- one authority-wide executor versus fair per-database queues over shared pools;
- mmap or native index implementations for a future non-JVM authority; and
- an observer cluster consuming fleet facts through the same capabilities.

Do not keep two mechanisms after a decision. Git and retained research are the
migration archive.

## Graduation

- One JVM process safely hosts the admitted cluster databases; no cluster pays
  another fixed JVM heap.
- Each active database has one shared connection/index authority and one ordered
  writer; independent reads and databases make bounded parallel progress.
- Identical cacheable queries over the same Datahike database value compute once,
  concurrent misses coalesce, and safe results propagate across unrelated
  transactions through Datahike's existing owner.
- The last real connection/session owner releases listeners, in-flight work,
  database-scoped query results, secondary indexes, Konserve resources, and the
  connection without racing reconnect.
- Bun agent children contain no Datahike replica or copied indexes and survive or
  fail independently.
- Direct connections are database-scoped; no database or client receives
  unrelated transaction traffic.
- CLJ and CLJS use one schema'd `seon.db` data interface with honest sync-local
  versus async-remote behavior.
- The protocol conformance suite is independent of JVM, Bun, Rust, and transport.
- Retained 1/2/4/8-database and multi-agent evidence proves lower total CPU,
  memory, serialization, and latency than duplicated replicas/JVMs.
