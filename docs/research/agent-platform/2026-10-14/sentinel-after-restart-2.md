---
type: research
status: measured; default at 243683f1d after start --head and reset; compared to sentinel-baseline.md (e9bcf36d2)
basis: default's JVM at 2026-10-14 13:10-13:20 local (06:10-06:20Z), three sequential runs of seon.sentinel/run, HEAD 6294c7ecc
---

# Sentinel after restart, 2026-10-14 (second run)

## For root

- Warm `(+ 1 2)` is slower than the e9bcf36d2 baseline in all three runs, by more than the baseline's own spread: run medians 57.1, 51.6, 48.7 ms against 38.6 (spread 38.6 to 41.8). Flagged as a regression. The same level was already seen after the 7ef4967db reset (51.9 ms), so this is not new since that reset. Cause unverified.
- The settle stage of the warm probe is the part that moved: 36.2, 38.8, 37.4 ms against 27.4 (baseline spread 26.4 to 30.2). Also flagged.
- Fresh first requests are faster than baseline: run medians 173.1, 171.1, 176.0 ms against 213.9. Defn is faster too: 337.7, 301.4, 326.1 ms against 378.6, with calls 15,503 against 27,030.
- Fresh custody is above baseline in all three runs (run medians 93.9, 102.3, 109.1 ms against 85.4), but the lowest run's excess is inside the baseline spread (80.9 to 88.5), so it is not flagged. Watch it.
- Start: `total-ms` 66,921 and `ready-ms` 33,578, which is 3.4x the 10 s target. 30.7 s of the 33.6 s ready time sits in the `seon.source/current` span (publication of changed source inputs). The 247 changed inputs since 73968333e give about 0.11 s per input, below the 0.26 to 0.83 s per input in the 2026-10-13 boot sentinel.
- The outer driver request of each run is 1.78 to 1.83 s, all in evaluate. This is the script's own driver, not a per-probe figure; each inner probe is under 0.4 s. The platform flagged it as slow.

## Commits

- Default's running commit: 243683f1d (the start log's source root `data/source/243683f1d...`). The probes measure this build.
- HEAD at the time of the runs: 6294c7ecc. The script's `head` field reports it, but it is not what runs on default.

## Probes, three sequential runs

Each run is `(seon.sentinel/run {:running-commit "243683f1d"})` in default's JVM, in JVM mode as root. The inner requests run in SCI mode on the probe agents, as the script defines. All nine fresh requests and fifteen warm samples completed with no failures. Each run's probe agents are retired by the script's `finally`.

| Probe | Run 1 | Run 2 | Run 3 | min / median / max | Baseline (e9bcf36d2) | Verdict |
|---|---|---|---|---|---|---|
| Warm median ms | 57.1 | 51.6 | 48.7 | 48.7 / 51.6 / 57.1 | 38.6 (spread 38.6 to 41.8) | regression, all 3 above spread |
| Warm samples ms (5 per run) | 37.8 40.6 66.5 62.2 57.1 | 55.2 51.8 51.6 50.5 51.2 | 43.3 55.4 45.7 53.3 48.7 | n/a | 38.6 to 41.8 | the first sample of run 1 is inside baseline |
| Warm settle median ms | 36.2 | 38.8 | 37.4 | 36.2 / 37.4 / 38.8 | 27.4 (spread 26.4 to 30.2) | regression, all 3 above spread |
| Warm calls | 5460 | 5460 | 5460 | constant | 6473 | fewer |
| Warm reads | 32 | 32 | 32 | constant | 41 | fewer |
| Fresh median ms (3 agents per run) | 173.1 | 171.1 | 176.0 | 171.1 / 173.1 / 176.0 | 213.9 (spread 205.1 to 214.5) | faster, not flagged |
| Fresh custody ms (median) | 93.9 | 102.3 | 109.1 | 93.9 / 102.3 / 109.1 | 85.4 (spread 80.9 to 88.5) | above baseline; lowest run within spread, not flagged |
| Fresh settle ms (median) | 31.9 | 38.0 | 37.1 | 31.9 / 37.1 / 38.0 | 58.7 | faster |
| Fresh calls | 6062 | 6062 | 6062 | 6062 to 6073 | 13626 | fewer |
| Fresh alloc MB (median) | 25.5 | 25.5 | 25.5 | constant | 75.7 | less |
| Defn ms | 337.7 | 301.4 | 326.1 | 301.4 / 326.1 / 337.7 | 378.6 (single sample, no spread) | faster, not flagged |
| Defn calls | 15503 | 15503 | 15503 | constant | 27030 | fewer |
| Defn test-step ms | 91.6 | 45.0 | 54.4 | 45.0 / 54.4 / 91.6 | 79.7 | within the range |
| Defn settle ms | 85.1 | 86.7 | 92.8 | 85.1 / 86.7 / 92.8 | 136.6 | faster |
| Outer driver request ms | 1778 | 1789 | 1833 | 1778 / 1789 / 1833 | about 1600 | driver, not a probe |

Transactions are 1 per probe in every run, as in the baseline. Warm reads and calls are constant across runs, so the variance is time, not work.

Stage details for warm, medians of the three runs: custody 0.6, fork 0.4, read 0.1, evaluate 1.2, settle 37.4, print 1.0, unattributed 9.6 ms. Baseline: custody 0.4, evaluate 1.1, settle 27.4, print 0.9, unattributed 6.3 ms. The growth is in settle (about +10 ms) and unattributed (about +3 ms).

## Boot: start --head

Source: `data/clusters/default/logs/operator-start-20261014T131012.590.edn` (start --head, PID 88215).

| Figure | Value | Note |
|---|---|---|
| total-ms | 66,921 | target 10 s |
| ready-ms | 33,578 | target 10 s |
| launch-ms | 42,618 | contains the boot |
| adopt-ms | 9,514 | |
| source-ms | 2,607 | |
| archive-ms | 2,015 | |
| classpath-ms | 567 | |
| down-ms | 816 | |
| share-ms | 25 | |

Boot layers by `at-ms`:

| Layer | at-ms |
|---|---:|
| `seon.boot/prepl-server` | 125 |
| `seon.store/store` | 148 |
| `seon.source/current` | 30,666 |
| `seon.store/branch` | 30,733 |
| `seon.boot/cluster-connection` | 30,735 |
| `seon.source/commit-id` | 30,735 |
| `seon.boot/config-result` | 31,129 |
| `seon.sci.eval/ctx` | 32,057 |
| `seon.flow/work-launcher` | 32,057 |
| `seon.flow/graph` | 33,569 |
| `seon.render.web/served` | 33,578 |

The slowest layer is `seon.source/current` at about 30.7 s, which is 91 percent of ready time. Everything after it is about 2.9 s. This is the same span the 2026-10-13 boot sentinel named (`refresh-source!` on changed inputs), and the phase breakdown inside it is still not logged. The 247 changed inputs since 73968333e are counted by `git diff --name-only 73968333e 243683f1d -- src test resources script`.

## Status

`bin/seon status` after the restarts, at `:seon.health/state :degraded`: four warnings, one `:seon.problems/errored-receipt`, three malformed contracts, one unowned namespace. Heap 2.0 GB used of 4.3 GB. Cache 133.7 MB of 512 MB. Recorded errors and degraded health are reported, not fixed, here.

## Limits

- Three runs on a machine other lanes were using. The shared-JVM figures show 1.2 to 1.8 s of shared GC and JIT time on the eval requests. Quiet-JVM confirmation is not done.
- The baseline is one sentinel run at e9bcf36d2, with five warm samples and three fresh samples. Spreads are from that run. The defn figure has no spread.
- Running commit 243683f1d is not HEAD 6294c7ecc. The probes measure the running build.
- The regression flags depend on the chosen baseline. Against the post-reset run at 7ef4967db (warm 51.9 ms, settle 35.6 ms), the warm median is flat.
- The settle and unattributed increases have no attributed cause. Profiling the warm path (`seon.profile/summary`) is the next step before naming one.

## Reproduce

Root runs `(seon.sentinel/run {:running-commit "<default's commit>"})` three times in default's JVM, one at a time, and reads `:summary`. Compare warm `:median-ms` and `:settle-ms`, fresh `:ms`, and defn `:ms` with `sentinel-baseline.md`.
