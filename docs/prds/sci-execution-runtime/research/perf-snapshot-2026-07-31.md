---
type: research
status: active
tags: [research, architecture]
---

# Performance snapshot — 2026-07-31 (WIP, development machine)

Every number below was MEASURED on the owner's development machine (Apple
Silicon) during dated research; each carries its source document. These are
work-in-progress engineering measurements, not service-level guarantees or
tuned benchmarks. Gaps are listed at the end with the benchmark run that
fills them.

## Database

| Measure | Number | Source |
|---|---|---|
| New cluster creation (branch fork) | ~17 ms | unsettled.md, measured branch-off |
| Cached query result reuse across commits | ~22 µs | render-invalidation-caching |
| Query dependency capture (invalidation) | 3.75 µs/query | render-invalidation-falsification |
| Staleness check, 100 blocks × 1,702 deps | 66 µs/commit | render-invalidation-falsification |
| Datom index amplification | ~2.2× (bulk stays in blobs) | architecture laws |

## Runtime / agents

| Measure | Number | Source |
|---|---|---|
| Parked agent cost | 2 procs, ~8.5 KB/proc (≈17 KB/agent parked → ~60k parked agents/GB, extrapolated) | flow-mechanics |
| Agent ctx fork | 50–72 ns | sci-door + interrupt ground truth |
| Guarded eval invoke (armed) | 3.2 µs; full pipeline 100.8 µs | sci-door-ctx-sharing |
| Per-fn-entrance safety check | 1.8–2.4 ns | sci stable-guard fix |
| Runaway agent code interrupted | at limit +6 ms (1,506 ms vs 1,500 ms limit, live) | turn-loop-preflight |
| Crash recovery to ready | 968 ms (kill -9, nothing re-executes) | plan ledger, live proof |
| Real end-to-end agent turn (local Ollama qwen) | ~40 s, model-bound | turn-loop-preflight |
| Historical throughput at 10 agents (local model) | ~4 turns/min, 99.24% of tokens = model thinking (system not the bottleneck) | plan ledger 2026-07-29 |

## Rendering / context

| Measure | Number | Source |
|---|---|---|
| Namespace context render (104-fn worst case) | 17,729 → 470 tokens at d1 (37×) | ns-renderer-correction |
| Full agent context walk d2 | 20–22k tokens, ~12 KB | context-walk falsification |
| HTML delivery, one change to 50 browser tabs | 1.17 ms (serialize once, mult) | render-pipeline-design |
| Single block morph in browser | 1.2–1.5 ms | render-pipeline-design |
| Debug view of a 5 MiB value | 5,715 bytes rendered, capped, navigable | w3-floor-debug notes |

## Developer loop (velocity, not product)

| Measure | Number | Source |
|---|---|---|
| Full first-party reindex (123 files) | 1.76–3.28 s (warm/cold); 5–32 ms/file incremental | unsettled.md |
| Complete corpus census (116 ns, 1,331 fns) | 16.8 s cold / 112 ms cached | unsettled.md |
| Full test suite | ~8 s (606 tests / 2,680 assertions at last frozen gate) | plan ledger |
| JVM load to REPL | 2.23 s | plan ledger |

## The dedicated benchmark (landed same day)

`perf-benchmark-2026-07-31.md` fills the gaps with 19 measured rows +
methodology + reproduction scripts. Headlines: 25 clusters in one JVM at
+1.24 MiB RSS / 1.4 MiB disk each with ZERO cross-tenant interference
(delivery 3.7 → 3.2 ms with 5 active neighbors); a new cluster boots into
a running JVM in 518 ms; agent arm 0.47 ms at a 1,000-agent fleet; 1,000
parked agents = +79 MiB RSS (≈12,600/GiB — bounded today by a FILED
defect: each armed agent holds one platform thread; ~33,000/GiB after the
fix, do not quote the higher number yet); commit-to-wire 3.7 ms flat at
1/10/50 connections, byte-identical; sustained churn 60 s/816 commits
with no memory growth; a stalled tab affects nobody. The one real
ceiling: file-store commits at 8 tx/s (99.3% konserve file backend — the
same commit is 1,088 tx/s in memory, 2,000 rows/s batched), an honest
known limit of the current storage backend, not the model.
