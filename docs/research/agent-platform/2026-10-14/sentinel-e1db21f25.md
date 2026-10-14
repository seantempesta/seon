---
type: research
status: not measured; the probes were not run on default at e1db21f25; boot figures read from the start log
basis: default restarted 2026-10-14 16:24 local onto e1db21f25; baseline sentinel-baseline.md (e9bcf36d2, 2026-10-12); last probe run sentinel-after-restart-2.md (243683f1d, 2026-10-14 13:10 local)
---

# Sentinel at e1db21f25, 2026-10-14

## For root

- The performance probes were NOT run. `seon.sentinel/run` evaluates each probe as an SCI-mode MCP request as root on default (`eval-probe` hardcodes `"sci"`). A Haiku lane may not make SCI requests as root on default, since they write test runs to main, and the brief says JVM mode only. The probe rows below are therefore UNVERIFIED, and no ratio is computed for them. Root (or an agent allowed to make those requests) needs to run `(seon.sentinel/run {:running-commit "e1db21f25"})` once in default's JVM to get the e1db21f25 figures.
- No probe regression is claimed, because none was measured. The candidate list below is empty for that reason.
- Boot ready-ms of 47,820 (VERIFIED, from the log) is the slowest of the 35 boots with a readiness record in `data/clusters/default/logs` since 2026-10-13 00:14. It is 4.7x the 2026-10-13 median of 10,154 ms, 1.23x the previous slowest 2026-10-14 boot (38,968 ms at 14:16 local), and 4.8x the 10 s target. The source publication layer (`seon.source/current`) is reached at 42,055 ms, 88 percent of ready time; the remaining layers take 5.8 s.
- The 16:12 boot (`operator-start-20261014T161224.990.edn`) has no ready-ms; its phases show total-ms 42,392 and the source layer at 833 ms. It did not log readiness, so it is excluded from the boot comparison.

## Probes

Baseline is e9bcf36d2 (2026-10-12, one run of 5 warm and 3 fresh samples). The last run is 243683f1d (2026-10-14 13:10, three runs). Neither is e1db21f25. "Now" is the e1db21f25 measurement, which was not taken.

| Operation | Baseline e9bcf36d2 | Last run 243683f1d (median of 3) | Now e1db21f25 | Ratio now/baseline | Flag | Verified |
|---|---|---|---|---|---|---|
| Warm `(+ 1 2)` median ms | 38.6 (spread 38.6 to 41.8) | 51.6 | not measured | n/a | none | UNVERIFIED |
| Warm settle ms | 27.4 (spread 26.4 to 30.2) | 37.4 | not measured | n/a | none | UNVERIFIED |
| Warm calls | 6473 | 5460 | not measured | n/a | none | UNVERIFIED |
| Warm reads | 41 | 32 | not measured | n/a | none | UNVERIFIED |
| Fresh median ms | 213.9 (205.1 to 214.5) | 173.1 | not measured | n/a | none | UNVERIFIED |
| Fresh custody ms | 85.4 (80.9 to 88.5) | 102.3 | not measured | n/a | none | UNVERIFIED |
| Fresh settle ms | 58.7 | 37.1 | not measured | n/a | none | UNVERIFIED |
| Fresh calls | 13626 | 6062 | not measured | n/a | none | UNVERIFIED |
| Fresh alloc MB | 75.7 | 25.5 | not measured | n/a | none | UNVERIFIED |
| Defn ms (single sample) | 378.6 | 326.1 | not measured | n/a | none | UNVERIFIED |
| Defn calls | 27030 | 15503 | not measured | n/a | none | UNVERIFIED |
| Defn settle ms | 136.6 | 86.7 | not measured | n/a | none | UNVERIFIED |
| Outer driver request ms | about 1600 | 1789 | not measured | n/a | none | UNVERIFIED |

The baseline's `calls` and `reads` counts are from one SCI-mode run; the 243683f1d run had different counts for the same probes, so the count drop from baseline to 243683f1d is not a regression signal and is not analysed here.

## Boot ready-ms

Read from `data/clusters/default/logs/operator-start-*.edn` (`:seon.operator/phases :seon.boot/ready-ms`). The sentinel has no boot baseline of its own, so the comparison is against the boots in this logs directory.

| Boot (log file, local time) | Ready ms | Source-layer at-ms | Verified |
|---|---:|---:|---|
| 2026-10-13, 29 boots 00:14 to 17:02 (median) | 10,154 | n/a | VERIFIED |
| 2026-10-13 max | 30,427 (13:17) | 28,783 | VERIFIED |
| 2026-10-14 09:59 | 29,028 | 23,436 | VERIFIED |
| 2026-10-14 13:10 (start --head, 243683f1d) | 33,578 | 30,666 | VERIFIED |
| 2026-10-14 13:40 | 19,306 | 17,065 | VERIFIED |
| 2026-10-14 14:16 | 38,968 | 34,988 | VERIFIED |
| 2026-10-14 15:01 | 35,186 | 30,923 | VERIFIED |
| 2026-10-14 16:24 (e1db21f25, this boot) | 47,820 | 42,055 | VERIFIED |

Across all 35 boots with ready-ms in the directory (median 11,255, minimum 4,600), this boot is the maximum. The median of the six 2026-10-14 boots, this one included, is 33,578. The phase breakdown for this boot: prepl-server 190 ms, store 232 ms, source current 42,055 ms, branch and cluster connection 42,171 ms, config 42,808 ms, SCI context 44,289 ms, flow graph 47,802 ms, web served 47,820 ms, with launch-ms 53,828 and adopt-ms 12,412.

## Candidates

No probe regressed by a measured figure, so no commit list is given. The boot figure is a measured slowdown against the 10-13 boots, but the sentinel's boot figures have no commit-level attribution yet; the source layer is where the time sits, not a cause.

## Limits

- Probes not run; all probe rows UNVERIFIED.
- The 2026-10-14 16:12 boot has no ready-ms, so it is missing from the boot comparison.
- The boot comparison spans four different source states, from e1db21f25 to earlier commits; it measures the boot, not any one commit.
- The boot figure is one boot. Earlier 2026-10-14 boots of 19 to 39 s show the spread, so a single boot does not establish the new level.

## Reproduce

Root runs `(seon.sentinel/run {:running-commit "e1db21f25"})` once in default's JVM, reads `:summary`, and compares warm `:median-ms` and `:settle-ms`, fresh `:ms`, and defn `:ms` with the baseline table above. Boot figures come from the newest `operator-start-*.edn` file, key `:seon.operator/phases`.
