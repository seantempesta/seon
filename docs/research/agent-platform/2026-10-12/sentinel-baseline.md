---
type: research
status: baseline; measured on default's running commit, not HEAD
---

# Performance sentinel baseline, 2026-10-12

The sentinel is `script/seon/sentinel.clj` (`seon.sentinel/run`). Each probe is
an MCP eval request measured by `seon.profile/measure`; the figures are the
reply's request cost (`:seon.repl/request`). `run` returns the probe agent ids;
root retires their branches with `my.agent/retire-branch!` after each run (the
branches of this baseline were retired and are absent from the roster).

## Commits

- Default's running commit: e9bcf36d2 (`bin/seon status`, source root
  `data/source/e9bcf36d2...`). The baseline measures this build.
- HEAD at the start of the session: 373ad0ac8. HEAD when the probes were
  recorded: f319ac158 (one commit ahead, not running on default). Default
  was not restarted; the numbers describe e9bcf36d2 only.

## Probes

Each row measured in JVM mode as root on cluster `default`; the inner request
ran in SCI mode in `seon.test`.

| Probe | Samples | Median or value |
|---|---|---|
| Warm `(+ 1 2)`, one reused agent `sentinel-warm` | 2 discarded, 5 recorded | 38.6 ms |
| Fresh agent first request, new id each | 3 | 205.1 to 214.5 ms |
| `defn` on `sentinel-defn`'s branch | 1 | 378.6 ms |

### Warm (median-ms sample)

Samples: 39.6, 38.6, 41.8, 38.6, 38.6 ms. Settle across the five: 27.3, 27.4,
30.2, 26.8, 26.4 ms.

| Figure | Value |
|---|---|
| ms | 38.6 |
| calls | 6473 |
| reads | 41 |
| transactions | 1 |
| stages | custody 0.4, derive 0.0, evaluate 1.1, fork 0.3, print 0.9, read 0.1, settle 27.4, test-step 2.1, unattributed 6.3 |
| alloc (request reply) | 0.0 MB |
| alloc (measure window, median of 5) | 7.30 MB |

The reply's request allocation reads 0.0 MB on warm samples while the measure
window reads 7.3 MB; the request figure counts a narrower thread window. Both
are recorded; the discrepancy is a finding for the owner of the reply's
allocation field.

### Fresh (three new agent ids)

| Figure | Fresh 1 | Fresh 2 | Fresh 3 |
|---|---:|---:|---:|
| ms | 213.9 | 205.1 | 214.5 |
| alloc MB | 75.7 | 75.7 | 75.7 |
| calls | 13626 | 13626 | 13626 |
| reads | 66 | 66 | 66 |
| transactions | 1 | 1 | 1 |
| custody | 88.5 | 80.9 | 85.4 |
| evaluate | 16.8 | 16.9 | 18.9 |
| settle | 58.7 | 59.3 | 58.6 |
| test-step | 38.1 | 37.7 | 39.8 |

### defn (`(defn sentinel-probe-inc [x] (inc x))`)

ms 378.6, alloc 117.9 MB, calls 27030, reads 231, transactions 1. Stages:
custody 78.7, derive 22.0, evaluate 45.7, settle 136.6, test-step 79.7,
print 2.5, facts 1.3, fork 0.3, unattributed 11.6.

## Findings

- The whole probe run is one eval request of about 1.6 s. The platform flagged
  it as over one second (`:seon.repl/finding :slow`); that outer request is
  the sentinel's own driver, not a per-probe figure. Each inner request is
  below one second.
- The fresh requests show 1 transaction (the recording) and 66 reads; the
  earlier cost-fresh note counted 3 to 4 checked transactions per request
  including admission. The reply field here is `:seon.cost/transactions`.
- The defn request settles in 136.6 ms, the largest settle stage measured.
  Its share is the recording of the new definition, not the probe.

## Reproduce

Root runs `(seon.sentinel/run {:running-commit "<default's commit>"})` in
default's JVM (JVM mode, root) once the committed script is loaded there. The
probes need a running cluster, so `-main` is for the same JVM. Compare the next restart's warm median and fresh medians with
these figures; a rise beyond the sample spread is a measured regression.
