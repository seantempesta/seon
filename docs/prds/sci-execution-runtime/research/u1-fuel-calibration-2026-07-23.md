---
type: research
status: complete
tags: [research, runtime]
---

# U1 fuel calibration — 2026-07-23

## Method

`bench/u1_guard_calibration.clj` ran the production portable guard in
counting-only mode around JVM SCI. The corpus covers scalar eval, an interpreted
collection transform, the interrupt-aware native `reduce` override, a plan
projection, and an authored hiccup renderer. Each case ran ten times. Counting
mode used the same retained-context holder, reset, safepoint closure, and door
as enforcement; it omitted the watchdog armer and never stopped work.

The reruled JavaScript acceptance is thread-free semantics, not a speculative
Bun SCI engine: production Bun remains `cljs.js` until U9 removes it. The
portable `.cljc` conformance suite separately compiles and runs under CLJS.

Command:

```bash
clojure -Sdeps '{:paths ["src" "bench"]}' \
  -M:writer:host -m u1-guard-calibration
```

## Distribution and selected defaults

With ten samples, empirical P99.9 is the observed maximum.

| Invocation class | Samples | P99.9 steps | P99.9 ms | P99.9 output chars | Default fuel | Default deadline ms | Default output chars |
|---|---:|---:|---:|---:|---:|---:|---:|
| agent eval | 30 | 19,999 | 440.347 | 11,928 | 100,000,000 | 600,000 | 1,638,400 |
| authored render | 10 | 751 | 15.043 | 7,531 | 100,000,000 | 600,000 | 1,638,400 |
| plan | 10 | 500 | 35.781 | 9,641 | 100,000,000 | 600,000 | 1,638,400 |

Every selected default is at least 100× the corresponding observed P99.9.
These are circuit breakers, not throughput governors. The high common fuel
default also leaves room for legitimate corpora larger than this bounded
calibration set.

## Raw samples

Values below are `elapsed-ns`; step and output counts were deterministic for
every repetition of a case.

- Scalar agent eval: steps `0`, chars `2`, ns
  `[6787584 286584 437125 464708 217917 173250 189667 185417 275709 191208]`.
- Collection transform: steps `1500`, chars `11928`, ns
  `[31050333 17393292 22139375 17376833 16946500 14806833 15824167 15163000 15701042 38763625]`.
- Native reduce: steps `19999`, chars `8`, ns
  `[301239583 440347458 256448542 208203417 199929500 206580375 203084792 205852291 422335791 332510042]`.
- Plan projection: steps `500`, chars `9641`, ns
  `[35780584 7305166 5767917 6701334 5395917 5452583 5680083 6493542 7516250 7551541]`.
- Authored render: steps `751`, chars `7531`, ns
  `[15042750 11442208 8989167 8279666 10544917 9082542 9085250 8978792 10284083 10878500]`.

The complete machine-readable run is retained at
`tmp/orchestrator/u1-calibration-raw-final.edn`; the committed benchmark
reproduces it.
