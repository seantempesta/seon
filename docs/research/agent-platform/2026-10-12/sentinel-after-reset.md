---
type: research
status: measured; default at 7ef4967db after reset; compared to sentinel-baseline.md (e9bcf36d2)
basis: default at 7ef4967db after reset, measured 2026-10-12 after 17:58 +07
---

# Sentinel after the reset, 2026-10-12

## For root

- Regressions: none at 1.5x. Warm 1.34x (51.9 vs 38.6 ms), fresh 1.16x, defn 1.22x; calls and reads fell, so this is time, not work. Re-run on a quiet JVM to confirm.
- Wins: calls down 3-16% on every probe, reads down 6-25%, defn alloc 0.82x.
- Over 1 s: the outer driver request, 2106.8 ms, all in evaluate; no function named. Inner probes are all under 0.5 s.
- Stage: defn derive 1.66x (+14.6 ms). Retiring the five probe branches cost 280 ms in one request.
- Context: default is `:seon.health/state :degraded` (four malformed contracts, one errored receipt). The reply's `:head` was 16ddd77d7, not the running 7ef4967db.

## Probes, baseline vs now

Baseline: `sentinel-baseline.md`, measured on e9bcf36d2 in JVM mode as root, cluster `default`, SCI mode inside `seon.test`. Now: `(seon.sentinel/run {:running-commit "7ef4967db"})` in default's JVM, JVM mode as root, the same surface. Ratio is now / baseline.

| Probe | Baseline | Now | Ratio | Flag |
|---|---|---|---|---|
| Warm `(+ 1 2)` median ms | 38.6 | 51.9 | 1.34x | under 1.5x |
| Warm samples ms (5 recorded) | 39.6, 38.6, 41.8, 38.6, 38.6 | 56.3, 53.3, 51.9, 44.6, 44.9 | n/a | all above baseline max |
| Warm settle median ms | 27.4 | 35.6 | 1.30x | |
| Warm evaluate ms | 1.1 | 2.2 | 2.0x (+1.1 ms) | small absolute |
| Warm unattributed ms | 6.3 | 11.4 | 1.8x (+5.1 ms) | small absolute |
| Warm calls | 6473 | 5416 | 0.84x | fewer |
| Warm reads | 41 | 31 | 0.76x | fewer |
| Warm alloc, measure window MB | 7.30 | 7.08 | 0.97x | |
| Fresh 3 samples ms | 213.9, 205.1, 214.5 | 274.8, 247.4, 231.0 | median 1.16x | under 1.5x |
| Fresh median ms | 213.9 | 247.4 | 1.16x | under 1.5x |
| Fresh custody ms (median) | 85.4 | 94.2 | 1.10x | |
| Fresh evaluate ms (median) | 16.9 | 23.2 | 1.37x | |
| Fresh settle ms (median) | 58.7 | 74.8 | 1.27x | |
| Fresh test-step ms (median) | 38.1 | 37.2 | 0.98x | |
| Fresh calls (median) | 13626 | 11588 | 0.85x | fewer |
| Fresh reads | 66 | 51 | 0.77x | fewer |
| Fresh alloc MB | 75.7 | 75.5 | 1.00x | |
| Defn ms | 378.6 | 460.5 | 1.22x | under 1.5x |
| Defn custody ms | 78.7 | 88.6 | 1.13x | |
| Defn derive ms | 22.0 | 36.6 | 1.66x (+14.6 ms) | stage only, probe under 1.5x |
| Defn evaluate ms | 45.7 | 45.3 | 0.99x | |
| Defn settle ms | 136.6 | 161.4 | 1.18x | |
| Defn test-step ms | 79.7 | 109.2 | 1.37x | |
| Defn calls | 27030 | 26249 | 0.97x | fewer |
| Defn reads | 231 | 192 | 0.83x | fewer |
| Defn alloc MB | 117.9 | 97.1 | 0.82x | less |
| Outer sentinel request ms | about 1600 | 2106.8 | about 1.3x | over one second (driver) |

Transactions are 1 per probe in both runs. Fresh failures: none.

## Flags

- No probe is at 1.5x or more. Probes over one second: the outer driver request only (2106.8 ms). Inner probes are all below 0.5 s.
- Derive in the defn probe is 1.66x; the stage is small in absolute terms (+14.6 ms).

## Cache: kinds over 10 MB, hit rate per kind

Total weighted bytes 66.9 MB of the 512 MB budget (13 percent). 28 kinds. Hit rate is hits / (hits + misses) from `seon.cache/status`, read in JVM mode as root.

Kinds over 10 MB:

| Kind | Bytes (MB) | Hits | Misses | Hit rate |
|---|---:|---:|---:|---:|
| `:seon.sci.eval/program-context` | 29.8 | 15 | 6 | 0.71 |
| `:seon.schema/projection` | 10.5 | 1 | 1 | 0.50 |

Hit rate per kind (per-kind bytes below 10 MB were not recorded in this pass):

| Kind | Hits | Misses | Hit rate |
|---|---:|---:|---:|
| `:seon.cluster/branch-attribute-changes` | 22 | 3 | 0.88 |
| `:seon.cluster/derivation-world` | 1 | 1 | 0.50 |
| `:seon.cluster/file-derivation-key` | 549 | 549 | 0.50 |
| `:seon.cluster/file-schema-keys` | 0 | 548 | 0.00 |
| `:seon.error.refusal/authored-namespace` | 791 | 31 | 0.96 |
| `:seon.fn/population-targets` | 4 | 2 | 0.67 |
| `:seon.health/derived` | 45 | 43 | 0.51 |
| `:seon.health/problems` | 0 | 5 | 0.00 |
| `:seon.health/reads` | 0 | 17 | 0.00 |
| `:seon.health/replaced-roots` | 0 | 5 | 0.00 |
| `:seon.health/untested` | 0 | 5 | 0.00 |
| `:seon.program/authored-shapes` | 4089 | 1 | 1.00 |
| `:seon.program/program-attributes` | 2 | 2 | 0.50 |
| `:seon.schema/canonical-keys` | 114 | 1 | 0.99 |
| `:seon.schema/declaration-predicates` | 214 | 2 | 0.99 |
| `:seon.schema/declaration-projection` | 214 | 2 | 0.99 |
| `:seon.schema/population-predicate-symbols` | 2 | 2 | 0.50 |
| `:seon.schema/projection` | 1 | 1 | 0.50 |
| `:seon.schema.datahike/decoded-edn` | 3 | 3 | 0.50 |
| `:seon.schema.datahike/vector-pull` | 2578 | 60 | 0.98 |
| `:seon.schema.edn/packaged-population` | 4320 | 1 | 1.00 |
| `:seon.sci.eval/loaded-digests` | 4 | 2 | 0.67 |
| `:seon.sci.eval/overlay-plan` | 0 | 6 | 0.00 |
| `:seon.sci.eval/program-context` | 15 | 6 | 0.71 |
| `:seon.sci.eval/program-key` | 11 | 6 | 0.65 |
| `:seon.task/file-declarations` | 0 | 96 | 0.00 |
| `:seon.task/task-basis` | 0 | 1 | 0.00 |
| `:seon.task/tasks-tx` | 1 | 1 | 0.50 |

Zero-hit kinds after a reset are expected for cold derivations; `:seon.task/file-declarations` (0 of 96) and `:seon.sci.eval/overlay-plan` (0 of 6) are the ones to watch on the next restart.

## Probe branches

The five probe agents (`sentinel-warm`, `sentinel-defn`, three `sentinel-fresh-*`) were retired on their `:agent-*` branches after the run. The roster is back to `:db`, `:current-src`, `:cluster-default`, as in the baseline.
