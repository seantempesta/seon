# Default boot time: ce241b8a3 against earlier starts (2026-10-16)

Evidence only. Source: the `:seon.boot/layers` timeline (`seon.cluster.boot/boot-timeline`,
ms since the start began) and `:seon.operator/phases` in each
`data/clusters/default/logs/operator-start-*.edn`. Read-only: default was not restarted,
and no SCI request was made on default.

## Per-layer timeline (ms since start began)

| Layer (boot order) | ce241b8a3 12:00 | 7d74a762f 11:02 | 7ce251085 10:44 | 44fbe9fbd 10:48 |
|---|---:|---:|---:|---:|
| store (`:seon.store/store`) | 135 | 147 | 123 | 134 |
| publication (`:seon.source/current`) | **28649** | 5661 | 6049 | 9420 |
| publication's own cost (store to source/current) | **28514** | 5514 | 5926 | 9286 |
| branch, connection, commit-id | 28835 | n/a | n/a | n/a |
| config-result | 29968 | n/a | n/a | n/a |
| sci ctx and work launcher | 30861 | n/a | n/a | n/a |
| flow graph | 31161 | 11734 | 11147 | 15317 |
| served (ready) | 31224 | 11743 | 11157 | 15324 |
| `:seon.boot/ready-ms` | 31225 | 11743 | 11157 | 15324 |

Only the store, publication, flow and served layers were printed for the three earlier starts; for ce241b8a3 the
sub-layers were read in full.

## Start phases (operator)

| Phase | ce241b8a3 | 7d74a762f | 7ce251085 | 44fbe9fbd |
|---|---:|---:|---:|---:|
| total-ms | 56923 | 27587 | 25617 | 28135 |
| source-ms | 3256 | 3436 | 3003 | 2615 |
| archive-ms | 2617 | 2801 | 2421 | 2067 |
| classpath-ms | 609 | 606 | 557 | 524 |
| down-ms | 803 | 716 | 728 | 681 |
| adopt-ms | **11520** | 2697 | 3220 | 3671 |
| launch-ms | 36649 | 18507 | 16233 | 20713 |

## Reading

- The growth is in publication: +23.0 s over 7d74a762f. The three earlier starts with the same
  four layers printed (10:44, 10:48, 11:02) ran publication in 5.5 to 9.3 s.
  Flow and graph are faster at ce241b8a3 (about 0.3 s from sci ctx to graph, against 5 to 6 s
  before), so the layers after publication did not grow.
- adopt-ms also rose, by about 8 s (11.5 s against 2.7 to 3.7 s). Adoption runs after
  ready, so it adds to total only.
- Earlier starts today from 06:40 to 08:42 spread wider: ready 12.5 to 51.7 s, and publication
  5.9 to 42.8 s. The 08:26 start (e29f7a259) had publication 42.1 s. Load was present then too, so the
  spread predates this commit.

## Machine load at the time

- 18 cores, 128 GB. `uptime` at 12:01 local: load 8.89 / 9.32 / 9.25 (1, 5, 15 minutes).
- Check JVMs (`bin/seon check`, operator-check-*.edn) were booting during default's window
  (12:00:20 to 12:01:12 local): operator-check-20261016T115951 (boot-ms 38462) and
  operator-check-20261016T120026 (boot-ms 45945, tests-ms 45610). A check JVM started at 12:01:0x
  (pid 23206, 266% CPU at the probe) and an operator-programs bb (pid 24832, 80% CPU).
- Check runs all morning had tests-ms from 0 to 575807 (operator-check-20261016T115528) and
  slot-wait up to 15.9 s (T114718), so the machine was busy from about 11:40.

## Verdict (evidence, not yet proven)

- Load is present but does not explain the layer. The earlier starts ran under similar
  check traffic and their publication was 5.5 to 9.3 s; the 28.5 s here is more than
  three times that. The sub-layers after publication were faster, so general slowness is
  unlikely to be the whole story.
- Candidate cause (unverified): commit ce241b8a3 changed the publication analyzer
  configuration (P1 removed `:hooks` from the clj-kondo publication config in
  `src/seon/fn/analyzer.clj`). The `refresh-source!` docstring (`src/seon/cluster.clj`)
  says "Analyzer configuration changes select every source", so the first start after that
  change may republish every source once. Only 19 src or resources files differ from 7d74a762f,
  which would not by itself account for 23 s.
- Discriminating test: the next start on the same commit with no source edits. If
  publication falls back to about 5 s, the cost was a one-time full republish after the analyzer
  change. If it stays near 28 s, it is a real regression in publication, not load.
- `ensure-dependency-analysis!` (lints checkout roots on every boot) runs inside the same
  stage, before the source/current publication. It was not separately timed here.

## Not done

- Sentinel: `script/seon/sentinel.clj` `run` evaluates probe agents on the live default
  cluster and retires their branches, so it is not read-only. Not run. No stored baseline file was
  found.
- Per-layer logs for the earlier starts beyond four layers are not in the operator files.
