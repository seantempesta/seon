---
type: research
status: active
tags: [research, evidence]
---

# Benchmark probes — 2026-07-31

Every number in `../../perf-benchmark-2026-07-31.md` comes from these scripts.
They run against a live cluster booted in its own operator root, over that
cluster's advertised `io-prepl`, so nothing touches `default` or another
lane's cluster.

## Reproduce

```bash
mkdir -p tmp/bench-head && git archive HEAD | tar -x -C tmp/bench-head
rm -rf tmp/bench-head/reference-code
ln -s "$PWD/reference-code" tmp/bench-head/reference-code
cd tmp/bench-head
bb --config bb.edn --deps-root . --classpath script \
   -m seon.fresh-operator --seon-root "$PWD" init          # ~28 s
clojure -M:dev -i <path>/drive.clj                          # prints BENCH-READY + prepl port
```

Then send each probe over the advertised prepl:

```bash
bb prepl.clj <prepl-port> <probe>.clj
```

## Order

| script | section |
|---|---|
| `drive.clj`, `ollama.edn` | boot the isolated bench cluster (local provider) |
| `support.clj` | shared instrumentation: quantiles, SSE clients, probes, memory |
| `db.clj`, `db2.clj`, `db3.clj`, `db4.clj` | §1–2 writes, the 123 ms decomposition, batching, reads |
| `blocks.clj` | reinstate the html block set deleted by `29794272b` |
| `web1.clj` | page GET latency |
| `web4.clj` | delta latency at 1/10/50 connections, controlled |
| `web5.clj` | 60 s sustained churn with 10 tabs |
| `web6.clj` | the stalled consumer (a reader that genuinely blocks) |
| `agents.clj` | parked agent density to 1,000 |
| `threads3.clj`, `threads4.clj`, `threads5.clj` | the per-agent platform thread finding and its release |
| `clusters.clj`, `clusters2.clj` | 25 clusters in one JVM; the fork alone |
| `clusters5.clj`, `clusters6.clj` | cross-cluster interference, alternated |
| `cache.clj` | process-wide query-cache pressure at 25 clusters |
| `active.clj` | real turns against the local Ollama server |

`web1.clj` predates `support.clj` and carries its own copies of the client and
quantile helpers; the later web scripts use `support.clj`. `clusters5.clj`
defines the probe helpers that `clusters6.clj` reuses from the same session.
