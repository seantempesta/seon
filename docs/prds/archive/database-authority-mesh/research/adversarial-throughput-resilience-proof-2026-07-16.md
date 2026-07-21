---
type: research
status: active
tags: [research, database, flow]
---

# Adversarial throughput and resilience proof

## Decision

Prove progress with deterministic latches before measuring latency. Wall-clock
throughput cannot distinguish useful parallelism from queueing, JIT, storage,
or test-compilation noise.

The first two falsifiers can still change the architecture:

1. Hold one cold Datahike query for database A. Fill the remaining Seon read
   workers with identical A queries that join Datahike single-flight, then
   submit one distinct B query. B must enter before A releases. Today the join
   occurs after scarce read admission, so blocked joiners may consume every CPU
   permit even though they perform no duplicate computation.
2. Hold every codec worker at handler entry for A, then send B a small control
   request. B must complete before A releases. The selector remains responsive,
   but decode, connection opening, and semantic entry currently share codec
   capacity; selector responsiveness alone does not prove control progress.

The results decide whether single-flight joining moves before CPU admission and
whether control receives an independent decode floor. Increasing worker counts
does not settle either ownership problem.

## Integrated matrix

Run one release-built artifact at fixed processor selections over 2, 4, and 8
databases. Use one persistent Bun session per database, deterministic equal
data, both memory and maintained durable storage, and bounded result digests.
Build and warm once; record Shadow/test compilation separately.

For each database count, prove:

- distinct cold reads enter concurrently up to configured capacity;
- a permanently full A queue cannot skip an already-ready peer for more than
  one rotation of ready databases;
- a blocked A writer does not prevent B from committing and reading its result;
- saturated A read, provider, and KNN work does not consume B's independent
  progress;
- an A client that stops reading a large response retains only A's configured
  bytes while B query and control responses complete;
- queued, running, joined, and final-waiter cancellation has one truthful
  outcome and does not affect B;
- an A crash removes only its physical ownership, preserves its sibling, and
  leaves an accepted mutation recoverable by request receipt;
- reconnect churn cannot let stale cleanup reach a replacement generation; and
- shutdown returns truthful graceful/forced evidence and every completed owner
  count returns to baseline.

## Relative budgets

- Parallel entry count is `min(configured capacity, runnable independent work)`.
- Once all databases are ready in one work class, maximum service gap is no
  larger than the number of ready databases.
- Healthy-peer latency must not follow A's backlog length, response size, or
  stalled-reader duration.
- Queued canceled work has zero body entries. Joined cancellation preserves
  other callers; final cancellation sets Datahike's cooperative signal.
- After final close, executor identities, active writer requests, transport
  bytes and slots, Datahike reads/report source, and registry attachment return
  to their exact pre-test counts.
- Retained bytes follow configured bounds. RSS need not immediately fall because
  JVM and Bun allocators may retain arenas.
- Performance claims use paired repeated distributions from the same artifact;
  averages alone do not graduate a change.

## Evidence additions

Existing executor, Datahike query/cache, committed-report, writer, registry,
UDS, and Bun session evidence covers deterministic ownership. Add only bounded
aggregates:

- service selections and maximum service gap by existing work class/database;
- queue, execution, and end-to-end duration histograms;
- maximum observed running work by class;
- transport input/output bytes, response slots, connection counts, close
  reasons, selector lag, and codec queue activity;
- transaction queue and batch-size histograms;
- encode duration, allocation, and bytes; and
- Bun event-loop delay plus process RSS around large values.

Use JFR to find allocation, GC, locks, parked virtual threads, carrier pinning,
common pools, and dependency-native pools. In particular, Proximum construction
already owns a hidden physical-core-sized pool, the global Datahike query LRU
may contend under cross-database hot hits, and response encoding currently runs
on the completion caller outside a separately visible work class.

## Authority shard decision

Only compare one JVM with two and four JVMs after the one-JVM deterministic
matrix passes. Keep total processors, databases, sessions, request trace, cache
capacity, transport bytes, data, and warmup constant. Compare throughput and
latency distributions, total RSS/heap/mapped bytes, allocation, GC pauses,
thread counts, cache/single-flight evidence, and failure containment.

One JVM minimizes class metadata, JIT, cache, transport, and supervisor cost.
Shards may reduce global-cache contention, GC blast radius, and selector/codec
contention, but duplicate JVM baseline memory and pools and divide cache reuse.
Sharding graduates only for a repeated measured tail-throughput or resilience
gain worth that exact cost; database count alone is not a reason.
