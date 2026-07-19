---
type: research
status: active
tags: [research, database, web, pod]
---

# Current architecture performance evidence

## Scope

This report measures the exact current default cluster after commit `82e9a7b1`.
It uses the maintained JVM writer, `seon.db` Bun client, Datahike query cache,
execution-child artifact, and Datastar feed registry. It does not introduce a
benchmark-only runtime or infer production memory from the development Shadow
watcher.

## Database query latency

The database contained 11 agents at basis transaction `536874794`. One direct
writer REPL form used the registry's existing `:default` connection and an
ordinary immutable Datahike database value. The Bun measurement used the same
query through the live pod's `seon.db/query` protocol path.

| Path | Work | Samples | Mean | p50 | p95 | p99 | Maximum |
|---|---|---:|---:|---:|---:|---:|---:|
| JVM direct | identical cached query | 2,000 | 0.018 ms | 0.015 ms | 0.022 ms | 0.031 ms | 0.096 ms |
| JVM direct | unique input, uncached result | 200 | 0.183 ms | 0.162 ms | 0.335 ms | 0.630 ms | 1.589 ms |
| Bun→UDS→JVM→UDS→Bun | identical cached query | 500 | 1.153 ms | 0.979 ms | 2.226 ms | 3.304 ms | 3.543 ms |
| Bun→UDS→JVM→UDS→Bun | unique input, uncached result | 200 | 1.240 ms | 1.063 ms | 2.293 ms | 4.649 ms | 6.705 ms |

`query-with-evidence` returned `:datahike.cache.outcome/hit` for the repeated
agent query over the exact database value. The protocol hop adds about one
millisecond at p50; the uncached Datahike work in this small query is much
smaller than transport, validation, encoding, and scheduling together, but the
complete path remains within interactive latency.

## Shared JVM query work

The maintained `seon.authority-density-test` compiled its real Bun client and
ran against one isolated in-memory database with 400 matching result rows.
Eight Bun OS processes opened independent UDS sessions at one barrier. The JVM
retained one Datahike connection and identical EAVT/AEVT/AVET index roots.

- One client: exactly one `miss-owner`.
- Eight concurrent clients: exactly one `miss-owner` and seven `miss-joined`.
- Every client's second query: cache hit.
- Every result contained the same 400 rows.
- All sessions released and single-flight active flights/callers returned to
  zero.
- Minimal-client RSS ranged from 159,696 to 163,200 KiB in the eight-client
  wave.

Focused proof passed one test with 51 assertions. This directly establishes
that clients do not copy indexes and concurrent identical misses compute once
inside the JVM.

## Datastar shared rendering and fanout

Each wave opened distinct view IDs on `/agent/root/feed`, reset
`seon.web.datastar` measurements, committed one new agent through ordinary
`POST /agents`, and retained every SSE stream under `tmp/` for byte comparison.

| Views | Subscriptions | Renders | Serializations | Fanout | Render duration | Event bytes |
|---:|---:|---:|---:|---:|---:|---:|
| 10 | 1 | 1 | 1 | 10 | 76.50 ms | 77,774 |
| 50 | 1 | 1 | 1 | 50 | 76.89 ms | 78,680 |
| 100 | 1 | 1 | 1 | 100 | 74.47 ms | 79,604 |

All 10 and all 100 clients contained the committed purpose and had one unique
complete-stream SHA-256 per wave. Every write was accepted, no subscription
was skipped, and closing the clients returned both view and subscription count
to zero. Render time stayed flat with fanout because equivalent sockets share
one semantic subscription, render, and serialized event. The roughly 79 KiB
root event makes configurable gzip valuable for remote clients even though it
is deliberately disabled on loopback.

After explicitly reclaiming the root execution child, the next demanded root
feed measured one cold render at 1,385.77 ms. Warm database-update renders were
about 75 ms. Cold execution-child startup/program load is therefore material;
socket fanout is not.

## Process memory and reclamation

macOS `vmmap -summary` distinguishes the production writer from the
development Shadow compiler and reports physical footprint rather than adding
RSS values that count mapped pages repeatedly.

| Process | RSS | Physical footprint | Peak | Relevant retained state |
|---|---:|---:|---:|---|
| JVM writer | 849 MiB | 635.8 MiB | 951.5 MiB | G1 heap 337 MiB committed, 149 MiB used, 512 MiB maximum |
| Bun pod | 936 MiB | 276.4 MiB | 819.5 MiB | UI, sessions, compiler/runtime program state |
| Full root execution child | 303 MiB | 166.8 MiB | 236.8 MiB | isolated CLJS execution runtime |
| Shadow watcher/compiler | 1.82 GiB | 1.7 GiB | 1.8 GiB | development-only compiler, not shipped operation |

The execution host's maintained idle timeout is 300,000 ms. Explicit
`stop-child!` removed the measured root PID completely, reclaiming its 166.8
MiB physical footprint; the next demand created a fresh child. Separately, live
pod `SIGKILL` proof removed both pod and detached child through Bun no-orphans
containment, while the writer remained ready.

## Current decision

The architecture-level wins are confirmed:

- database indexes and identical query computation remain in one JVM owner;
- the complete remote query hop is about one millisecond at p50;
- Datastar client count does not multiply render or serialization work; and
- idle/terminated execution processes reclaim their private footprint.

The material costs are cold execution-child startup/program acquisition,
complete root rendering, the large uncompressed root event, and baseline
writer/pod footprints. Continue measuring transaction propagation, 2/4-client
query waves, slow-client backpressure, and idle CPU before changing code. Do
not trade exact ClojureScript semantics or the one-render/one-writer design for
small bundle or call-site micro-optimizations.
