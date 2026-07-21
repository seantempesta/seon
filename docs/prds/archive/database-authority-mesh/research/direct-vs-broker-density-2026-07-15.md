---
type: research
status: completed
tags: [research, prd, database, flow, agent]
---

# Direct versus broker density — 2026-07-15

## Question and boundary

This experiment compares two local transport topologies for isolated Bun agent
processes reaching one authority:

- **direct:** every child owns one persistent native Unix-domain socket to the
  authority; and
- **broker:** every child owns one persistent native Unix-domain socket to its
  Bun cluster supervisor, which multiplexes every request over one persistent
  native socket to the authority.

The experiment returns evidence and reversible options. It does not select the
production topology. In particular, the JVM must not become a global execution
gate: only writes to the same database may require ordering; independent query,
pull, result delivery, and backpressure must make parallel progress.

No Seon lifecycle, source, test, or database process ran. The disposable Bun
fixture lived at `tmp/mesh-density-bench.js` and was removed after measurement.
All socket files were underneath project `tmp/` and were removed by the runner.

## Fixture contract

The fixture used Bun 1.3.14 on macOS arm64 and separate OS processes for the
authority, optional broker, and 1, 8, or 32 clients. Every link used native
`Bun.listen`/`Bun.connect` Unix sockets.

The wire representation was four-byte big-endian length plus JSON. Each stream
had:

- one linear incremental decoder retaining only the unread suffix;
- stable request identities and out-of-order responses;
- a FIFO partial-write queue retaining exactly the unwritten suffix;
- `drain`-driven queue resumption;
- current and maximum queued-byte accounting; and
- a per-client in-flight bound.

The authority never used a global request queue. A delayed request installed an
independent timer and later responded by request identity. The broker likewise
performed no request/response lockstep: it correlated all in-flight requests on
one authority stream and delivered responses independently to child streams.
This deliberately tests a multiplexed topology rather than accidentally making
the broker sequential.

Three synthetic workloads isolated transport behavior:

| Workload | Requests per child | In-flight | Authority delay | Result payload |
|---|---:|---:|---:|---:|
| Small cached-like | 500 | 16 | 0 ms | 32 bytes |
| Heavy-like | 50 | 8 | 5 ms | 32 bytes |
| Large result | 20 | 4 | 0 ms | 256 KiB |

These are not Datahike performance numbers. They isolate topology overhead
before cache identity, single-flight, and actual query scheduling are settled.

## Commands

The complete normal matrix was produced with:

```bash
for w in small heavy large; do
  for n in 1 8 32; do
    bun tmp/mesh-density-bench.js runner direct $n $w normal
    bun tmp/mesh-density-bench.js runner broker $n $w normal
  done
done
```

Multiplexing limits:

```bash
for t in direct broker; do
  for l in 1 4 16; do
    bun tmp/mesh-density-bench.js runner $t 8 heavy normal $l
  done
done
```

Failure and slow-client probes:

```bash
bun tmp/mesh-density-bench.js runner direct 8 large slow
bun tmp/mesh-density-bench.js runner broker 8 large slow
bun tmp/mesh-density-bench.js runner direct 8 heavy child-crash
bun tmp/mesh-density-bench.js runner broker 8 heavy child-crash
bun tmp/mesh-density-bench.js runner broker 8 heavy broker-crash
```

Each row below is one retained run. Child p50 is the median child's p50; child
p95/p99 are the worst child's corresponding percentile. CPU is summed process
user plus system time. RSS is a point sample near completion and must not be
read as retained idle memory or peak RSS.

## Normal density results

### Small cached-like responses

| Children | Topology | p50 | p95 | p99 | Total CPU | Total RSS | Authority wall |
|---:|---|---:|---:|---:|---:|---:|---:|
| 1 | direct | 0.035 ms | 0.214 ms | 0.500 ms | 8.7 ms | 70.9 MiB | 19.8 ms |
| 1 | broker | 0.073 ms | 0.334 ms | 0.740 ms | 17.9 ms | 105.5 MiB | 34.0 ms |
| 8 | direct | 0.166 ms | 0.826 ms | 1.988 ms | 64.9 ms | 318.7 MiB | 37.0 ms |
| 8 | broker | 0.312 ms | 0.896 ms | 1.258 ms | 92.6 ms | 361.5 MiB | 49.2 ms |
| 32 | direct | 0.462 ms | 2.966 ms | 8.563 ms | 277.5 ms | 1,171.3 MiB | 66.8 ms |
| 32 | broker | 1.086 ms | 2.519 ms | 4.837 ms | 344.7 ms | 1,227.8 MiB | 94.2 ms |

The broker roughly doubled median latency at 1 and 32 children and added one
Bun process. Its p99 happened to be lower at 8 and 32 in these single runs;
that variance needs repeated distributions before any tail claim. The stable
finding is additional parse/encode and process work, not a precise percentile
ratio.

### Delayed heavy-like responses

| Children | Topology | p50 | p95 | p99 | Total CPU | Total RSS | Authority wall |
|---:|---|---:|---:|---:|---:|---:|---:|
| 1 | direct | 5.776 ms | 6.213 ms | 6.216 ms | 7.2 ms | 62.2 MiB | 60.0 ms |
| 1 | broker | 5.874 ms | 6.399 ms | 6.472 ms | 11.4 ms | 93.7 MiB | 71.4 ms |
| 8 | direct | 5.892 ms | 6.371 ms | 6.490 ms | 47.9 ms | 281.7 MiB | 67.4 ms |
| 8 | broker | 5.865 ms | 7.123 ms | 7.215 ms | 55.2 ms | 316.6 MiB | 78.0 ms |
| 32 | direct | 5.133 ms | 7.388 ms | 7.503 ms | 143.2 ms | 1,025.1 MiB | 87.7 ms |
| 32 | broker | 5.327 ms | 8.329 ms | 8.887 ms | 175.8 ms | 1,065.4 MiB | 100.0 ms |

Because the authority allowed all timers to overlap, 32 children did not turn
1,600 five-millisecond requests into eight seconds of serialized work. Both
topologies preserved concurrent in-flight progress; the broker added modest
latency and CPU rather than a global execution lock in this workload.

### Large results

| Children | Topology | p50 | p95/p99 | Total CPU | Total RSS | Max authority queue | Authority wall |
|---:|---|---:|---:|---:|---:|---:|---:|
| 1 | direct | 1.828 ms | 3.265 ms | 18.1 ms | 97.8 MiB | 0.99 MiB | 29.6 ms |
| 1 | broker | 2.484 ms | 6.011 ms | 35.3 ms | 159.0 MiB | 0.98 MiB | 44.0 ms |
| 8 | direct | 6.310 ms | 10.864 ms | 193.9 ms | 516.0 MiB | 0.99 MiB | 58.2 ms |
| 8 | broker | 14.592 ms | 22.431 ms | 258.9 ms | 581.9 MiB | 7.98 MiB | 114.8 ms |
| 32 | direct | 21.529 ms | 42.404 ms | 872.6 ms | 1,873.4 MiB | 0.99 MiB | 163.7 ms |
| 32 | broker | 58.655 ms | 69.660 ms | 1,187.6 ms | 1,919.6 MiB | 31.90 MiB | 369.2 ms |

The broker is materially costly for large results: at 32 children authority
wall time was 2.26 times direct, median child latency 2.72 times direct, total
CPU 1.36 times direct, and the single authority connection accumulated about
31.9 MiB. The broker itself parsed 1,280 messages, forwarded 160 MiB of payload,
and used 335 ms CPU. This is the clearest evidence against putting all bulk data
for many children on one broker-to-authority byte stream without independent
delivery lanes and strict byte admission.

## Multiplexing-limit results

Eight children each performed fifty delayed requests. The same connection
topology was tested with 1, 4, and 16 requests in flight per child.

| Topology | Limit | p95 | p99 | Authority wall | Total CPU |
|---|---:|---:|---:|---:|---:|
| direct | 1 | 6.090 ms | 6.472 ms | 313.9 ms | 138.3 ms |
| direct | 4 | 6.210 ms | 6.288 ms | 102.0 ms | 62.0 ms |
| direct | 16 | 6.197 ms | 6.280 ms | 49.3 ms | 43.2 ms |
| broker | 1 | 6.564 ms | 7.078 ms | 336.5 ms | 159.2 ms |
| broker | 4 | 7.170 ms | 7.291 ms | 117.3 ms | 72.0 ms |
| broker | 16 | 7.157 ms | 7.234 ms | 60.9 ms | 47.5 ms |

Multiplexing is not optional for latency-hiding work: limit 16 completed about
6.4 times faster than limit 1 for direct and 5.5 times faster for broker without
materially worsening request p99. Production limits still need work/byte
admission because a count treats a tiny cached result and a huge cold query as
equal.

## Slow-client isolation and global-gate falsifier

In the large-result slow probe, one of eight children spent 10 ms of CPU before
accepting each response. The other seven children's worst p95 was:

| Topology | Normal baseline p95 | Normal children with slow peer | Slow/normal aggregate p95 |
|---|---:|---:|---:|
| direct | 10.864 ms | 9.474 ms | 48.711 ms |
| broker | 22.431 ms | 23.179 ms | 55.706 ms |

The slow child harmed itself but did not materially raise the other children's
latency in this bounded 40 MiB run. Therefore a correctly multiplexed broker
does not inherently serialize unrelated clients. However, the broker baseline
was already about twice direct, and its single authority stream accumulated far
more queued bytes under density. A larger or sustained slow-output test must
reach configured byte limits and prove that one database/session is rejected or
paused without stopping another database's responses.

The production scheduler needs two independent fairness planes:

- compute admission by physical database and work class; and
- response-byte admission by session/database and delivery lane.

A shared executor or socket is acceptable only if neither plane becomes a
global FIFO. Same-database writes remain ordered; reads and different databases
must not inherit that ordering.

## Failure isolation

With one of eight children aborting after five requests:

- direct: the failed child exited on `SIGABRT`; all seven siblings completed;
- broker: the failed child exited on `SIGABRT`; all seven siblings completed;
  and the broker removed that session's outstanding correlations.

When the broker itself aborted during eight children's delayed work, all eight
children reported connection failure and exited 2. The authority remained alive
and had accepted 64 requests. This is the broker topology's unavoidable
cluster-wide data-plane failure domain. A direct child socket has no equivalent
intermediate process; authority failure remains global in either topology.

Cancellation semantics remain unproved: disconnect removed broker correlations,
but already admitted authority work could still complete. Production requires a
semantic cancel/release request, not merely local Promise rejection or socket
close.

## File descriptors, memory, and copies

The short-lived fixture did not retain a reliable `lsof` peak sample. Socket
ownership gives an exact topology count for `N` children:

- direct: `N` child endpoints plus `N` authority endpoints;
- broker: `N` child endpoints plus `N` broker-facing endpoints, then one broker
  endpoint plus one authority endpoint.

The broker reduces the authority's connection count from `N` to one but does
not reduce system-wide socket endpoints; it changes `2N` into `2N + 2`. Its
possible memory benefit must therefore come from making authority session state
expensive and broker session state cheap—not from fewer kernel endpoints.

Observed child RSS was roughly 31-36 MiB for small/heavy processes and roughly
53-56 MiB while receiving 256 KiB results. This per-agent process cost dominated
the socket topology. The broker added roughly 35-69 MiB RSS depending on load.
These point samples argue for measuring Bun child density and heap policy as a
separate product dimension.

Logical copy evidence:

- direct parses and encodes once at the authority and once at the child;
- broker additionally parses and re-encodes every request and response;
- the broker recorded exactly two parsed messages per completed request;
- at 32 large-result children it forwarded 160 MiB through that extra process.

The fixture cannot count native/JSC/kernel copies. Allocation profiling is still
required. A broker could avoid decoding opaque authority frames, but it would
still need a fixed routing header, correlation ownership, bounded per-child
queues, and an additional process hop. Shared encoded result bytes could change
the result only when several clients request the same result at the same exact
database value; that should be measured beside Datahike single-flight rather
than assumed.

## Options preserved for Sean

### Direct data plane, supervisor control plane

Each child talks directly to the authority for query/pull/transaction data;
Bun IPC remains only for lifecycle and supervision. This preserves the smallest
failure and backpressure domain and avoids duplicate broker parsing. Authority
sessions/FDs and admission must be cheap and bounded.

### Cluster broker for control and small metadata only

The supervisor multiplexes lifecycle, capabilities, and small coordination
messages, while bulk database values use direct per-child lanes. This retains a
cluster context without forcing large query results through the broker. It adds
two transport classes and needs a precise rule, so it is justified only if the
control surface materially simplifies ownership.

### Broker with multiple independent authority delivery lanes

A bounded broker pool can separate long/large from short/cached traffic or map
lanes to databases. This may reduce authority session objects while avoiding one
global output queue, but it retains broker CPU/copies and cluster-wide broker
failure. Pool size is a measured tuning value, not protocol identity.

### Opaque frame router

A broker could route a small envelope without decoding database bodies. This
reduces parse/encode cost but requires a transport-level routing envelope and
cannot share semantic results without understanding identity. It should be
compared against direct before adding a second framing concept.

## Decision questions

Sean should remain involved in these tradeoffs:

1. Is direct per-child data-plane isolation worth `N` authority sessions when
   total system socket endpoints are not reduced by brokering?
2. Should interactive clusters receive explicit weight over experiment clusters,
   or should all databases receive strict equal progress?
3. Are query/pull results expected to be commonly large enough that the measured
   broker penalty rules out one shared data stream?
4. Does a supervisor need database data for cluster management, or can it remain
   a pure lifecycle/control owner?
5. Should one child have one multiplexed connection or separate bounded lanes
   for short and bulk work?

## Required next evidence

- Repeat every material row and retain distributions, peak RSS, allocations,
  context switches, and reliable per-process FD samples.
- Replace echoes with settled Datahike cache-hit, cold query, pull, index-range,
  transaction, and shared-result fixtures.
- Run two physical databases: saturate one with cold CPU and large output while
  proving bounded p99 and transaction progress in the other.
- Drive one client beyond its response-byte limit and prove independent
  database delivery continues.
- Compare direct, decoded broker, opaque broker, and a small lane pool.
- Measure session memory at 1/8/32/128 idle and active children.
- Couple crash/disconnect to explicit cancellation and Datahike database-value
  release, then prove no admitted work or cache owner leaks.
- Measure shared encoded results only after exact database-value identity and
  single-flight ownership are settled.

The evidence currently favors no hidden global queue and keeps the direct data
plane as the performance baseline. It does not yet settle whether authority
session memory, shared encoding, authentication, or operational simplicity can
justify a broker or bounded lane pool.
