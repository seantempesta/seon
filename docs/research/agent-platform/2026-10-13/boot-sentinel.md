---
title: "Boot sentinel: 73968333e restart"
type: research
status: active
---

# Boot sentinel: 73968333e restart (2026-10-13)

Research only. No restart, no SCI evals on default, no branches created.

## Verdict

The slow restart is a real cost of publishing more changed inputs, not a regression in per-input cost. The growth sits in one layer, `:seon.source/current`, which is the publication of changed source inputs (`changed-source-paths`, then `cluster/refresh-source!`) on the resume path in `seon.cluster.boot`. The chain-prose docstring work is not the cause: `seon.ref/names` over every docstring costs about 1 s, not 20 s.

## Evidence

Per-layer `at-ms` from `data/clusters/default/logs/operator-start-*.edn` for the last 14 starts. Only `:seon.source/current` and the layers after it are shown; the `store/store` layer stays at about 110-140 ms in every start.

| start (local) | sha | changed inputs since prev start | source/current at-ms | total-ms | ready-ms |
|---|---|---|---|---|---|
| 044132 | 8a46d0810 | n/a | 5068 | 17374 | 6411 |
| 054420 | 4a1fc2e7e | 35 | 21374 | 36707 | 23249 |
| 061359 | 3b5585017 | 25 | 9171 | 24226 | 11255 |
| 062215 | 487388073 | 1 | 2847 | 13484 | 4600 |
| 065351 | 6299bcda4 | 3 | 3982 | 15734 | 6101 |
| 070816 | 606110768 | 4 | 3416 | 14459 | 5061 |
| 072158 | 1d538ec20 | 10 | 5081 | 17830 | 6776 |
| 075534 | d8b470c57 | 20 | 8708 | 23792 | 10329 |
| 082946 | 6e8b157ed | 23 | 10575 | 23795 | 11965 |
| 085656 | 371a754df | 23 | 21837 | 37381 | 23836 |
| 113218 | 23d3bb3a8 | n/a (prev start unparsed) | 6491 | 17365 | 8196 |
| 115623 | 1d950f730 | 10 | 6886 | 18988 | 8942 |
| 131716 | 73968333e | 68 | 28783 | 49768 | 30427 |

Layers after `source/current` in the slow start: `store/branch` 28809, `cluster-connection` 28811, `source/commit-id` 28813, `config-result` 29009, `sci.eval/ctx` 29848, `flow/work-launcher` 29849, `flow/graph` 30414, `render.web/served` 30427. The span from `source/current` to ready is about 1.6 s, and that is typical of every start. So the whole regression is inside the one span.

Phase totals for 131716: `launch-ms` 36537, `adopt-ms` 7295, `archive-ms` 2453, `source-ms` 3066, `classpath-ms` 590, `down-ms` 893, `share-ms` 23. `launch` contains the boot; the boot is dominated by `source/current`.

Changed inputs were counted as `git diff --name-only <prev> <sha> -- src test resources script`.

Per-input rate, taking the floor as the 1-input start (2.8 s):

- 73968333e: (28.8 - 2.8) / 68 ≈ 0.38 s per input
- 4a1fc2e7e: (21.4 - 2.8) / 35 ≈ 0.53 s per input
- 6e8b157ed: (10.6 - 2.8) / 23 ≈ 0.34 s per input
- 371a754df: (21.8 - 2.8) / 23 ≈ 0.83 s per input (outlier)
- 3b5585017: (9.2 - 2.8) / 25 ≈ 0.26 s per input

73968333e sits in the middle of the observed range, so its per-input cost is not an outlier. The 371a754df start is the one anomaly, and nothing in the logs explains it. It may be machine load (the commit window overlapped other lanes), but that is unverified.

## The docstring work

Measured in the default JVM (JVM mode, read-only, 2026-10-13):

- `seon.ref/names` over all 9,185 docstrings in the loaded namespaces (1.92 M chars): 944 ms, 163,692 calls, 773 MB allocated.

The commit message reports 345 ms for the same pass on a warm JVM. So the pass is about 1 s here, which is a fixed-cost component at most. It cannot account for the roughly 26 s above the floor. Whatever the extra cost is, it is in `refresh-source!` or the publication it runs. The logs do not split that span further, so I could not attribute it to a single function.

## Limits

- I did not run `script/seon/sentinel.clj`. It is not read-only: `run` evaluates in SCI mode on default under agent ids (`sentinel-warm`, `sentinel-fresh-*`, `sentinel-defn`), creates branches, and defines `sentinel-probe-inc`. That violates the rule against SCI as root on default. It needs root to run it, or a JVM-mode variant.
- The per-phase timings inside `refresh-source!` are not logged, so the attribution is to the layer and to the changed-input count, not to a function.
- The input counts come from git diffs, which include tests, scripts and resources, and not only the files that are actually published. The per-input rates are therefore approximate.

## Recommendations

1. Log the phases inside the `source/current` span (changed-path discovery, digest compare, `refresh-source!` publication, reindex) into the operator-start log. That answers the attribution question directly, and it is cheap.
2. Do not restart again just for measurement yet. A zero-change restart on the same commit would confirm the fixed floor (about 2.5 s) and would show whether the 73968333e cost repeats when nothing changed. Run that only after step 1 lands, so its numbers are attributed.
3. Investigate 371a754df's 0.83 s per input to see whether it was load or a real path.
4. Watch the docstring pass. At 944 ms cold it is the largest single item measured here, and it runs over every docstring on each cold index. It should be proportional to changed docstrings, not all of them, if it is ever on the per-change path.
