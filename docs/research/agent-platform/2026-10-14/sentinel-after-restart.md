---
type: research
status: measured; default running de1f48c23, compared with the 2026-10-12 baseline (e9bcf36d2)
---

# Performance sentinel after restart, 2026-10-14

Run: `(seon.sentinel/run {:running-commit "de1f48c23"})` in default's JVM (JVM mode, root), with the committed
`script/seon/sentinel.clj` loaded from HEAD de1f48c23. The run's probe agents are its own SCI branches. The
baseline is `docs/research/agent-platform/2026-10-12/sentinel-baseline.md`, measured on e9bcf36d2, so every
movement below spans the commits between the two, not one change.

## Broken first

- The outer driver eval took 1,983 ms and was flagged `:slow`. As in the baseline, that is the sentinel's own
  driver (five warm, three fresh and one defn probe in one request), not a per-probe figure. Each inner probe is
  under one second.
- The run printed nothing: `run` returns a map and only `-main` prints it, so the figures were read back from
  the returned value.

## Leftover-branch check

After the run, the branches of default's store were listed with `datahike.api/branches` (JVM mode, root). No
branch name contains `sentinel`, so the five probe agents' branches (`sentinel-warm-1791946799813`,
`sentinel-defn-1791946799813`, `sentinel-fresh-{0,1,2}-1791946799813`) were retired as commit 5b99122dd intends.
The agent rows stay on main with admission closed, as that commit documents.

## Moved measures (more than 10%)

Baseline values are the 2026-10-12 figures; now values are this run. Warm uses the median sample.

| Probe | Measure | Baseline | Now | Change |
|---|---|---|---|---|
| warm `(+ 1 2)` | ms (median) | 38.6 | 53.1 | +38% |
| warm | samples ms | 38.6 to 41.8 | 49.3 to 56.8 | above baseline spread |
| warm | calls | 6,473 | 5,460 | -16% |
| warm | reads | 41 | 32 | -22% |
| warm | settle stage ms | 27.4 | 33.8 | +23% |
| warm | unattributed stage ms | 6.3 | 14.0 | +122% |
| fresh (median of 3) | ms | 213.9 | 167.3 | -22% |
| fresh | alloc MB | 75.7 | 25.0 to 33.5 | -56% to -67% |
| fresh | calls | 13,626 | 6,062 | -56% |
| fresh | reads | 66 | 36 | -45% |
| fresh | custody stage ms | 80.9 to 88.5 | 88.5 to 100.4 | +10% to +13% |
| fresh | evaluate stage ms | 16.8 to 18.9 | 21.3 to 27.1 | +13% to +43% |
| fresh | settle stage ms | 58.6 to 59.3 | 31.7 to 36.3 | -38% to -46% |
| defn | alloc MB | 117.9 | 32.0 | -73% |
| defn | calls | 27,030 | 17,649 | -35% |
| defn | reads | 231 | 199 | -14% |
| defn | custody stage ms | 78.7 | 92.1 | +17% |
| defn | evaluate stage ms | 45.7 | 30.0 | -34% |
| defn | settle stage ms | 136.6 | 92.8 | -32% |
| defn | test-step stage ms | 79.7 | 137.9 | +73% |
| defn | print stage ms | 2.5 | 7.0 | +180% |

Within 10%: defn ms (378.6 to 411.4, +9%) and warm transactions (1 both).

Headline: the warm `(+ 1 2)` median rose from 38.6 to 53.1 ms, beyond the baseline's sample spread (38.6 to 41.8),
while its calls and reads fell. The unattributed stage (6.3 to 14.0 ms) and settle (27.4 to 33.8 ms) account for
most of the rise. The fresh and defn figures fell in calls, reads and allocation. This is a measured movement on
one run, not a verdict: the baseline's own note says the request-level allocation field under-counts warm samples,
and a single run per probe leaves the warm delta to be confirmed by a second run before anyone calls it a
regression.

## Slow boot layers

Start log `data/clusters/default/logs/operator-start-20261014T095916.984.edn`, `bin/seon start --head` at
2026-10-14T02:58:28 UTC. Phases: total 50,963 ms, ready 29,028 ms, launch 35,136 ms, adopt 6,733 ms,
down 743 ms, source 751 ms, classpath 671 ms, archive 46 ms, share 34 ms.

Layer timings (at-ms from process start, and the delta from the previous layer):

| Layer | at-ms | delta ms |
|---|---:|---:|
| prepl-server | 122 | 122 |
| store/store | 156 | 34 |
| source/current | 23,436 | 23,280 |
| store/branch | 23,506 | 70 |
| boot/cluster-connection | 23,508 | 2 |
| source/commit-id | 23,510 | 2 |
| boot/config-result | 24,014 | 504 |
| sci.eval/ctx | 27,189 | 3,175 |
| flow/work-launcher | 27,189 | 0 |
| flow/graph | 29,020 | 1,831 |
| render.web/served | 29,028 | 8 |

`source/current` took about 23.3 s of the 29 s readiness: it is the publication of changed source inputs on the
resume path, the same layer the 2026-10-13 boot sentinel (`docs/research/agent-platform/2026-10-13/boot-sentinel.md`)
identified as growing with the number of changed inputs. The start log records 139,372 store files and a
`:seon.operator/store` last-collection error (no completed maintenance receipt for this root), which is a
separate finding for the store owner. The sci.eval/ctx (3.2 s) and flow/graph (1.8 s) layers are the next
largest.

The 1,352 ms ready-ms that the reset reported is the status of the already-running JVM's readiness record after
the reset, not a fresh boot, so it is not a comparison for the 29 s figure.

## Verification limits

- One sentinel run; no repeat. The warm delta needs a second run on the same commit before it counts.
- The run's `def` of `sentinel-result` remains in default's JVM root namespace (a JVM-only var, not program
  source); it was left there because removing it would itself be a JVM eval.
