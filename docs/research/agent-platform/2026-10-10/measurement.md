---
title: Measurement evidence — astra-measure
date: 2026-10-10
type: research
status: partial
---

# Measurement evidence — astra-measure

**Partial fix; causal all-thread accounting is still unavailable.** Flow is a useful measurement boundary, but the writer is not Flow. Replacing Flow's virtual executor alone would leave pooled writer work, shared commits and detached work unattributed. Default remains `ffe62e1c37f60566e68cb32943e870a1b4b47922`, OpenJDK 26.0.1 on macOS. **RESET NEEDED** to install the eventual landing; this lane did not restart, reset or stop default.

The existing path is MCP → agent Flow step → evaluation/test workers → Datahike writer → rendering. This slice composes JDK counters with the existing request map and merges completed test sinks into their parent. It adds no executor, scheduler or attribution registry. Cost is O(1) per counter snapshot and O(distinct called functions) per completed test sink; stages retain their existing clocks.

## Ground truth and before/after

[measurement-probe.clj](measurement-probe.clj) runs the real MCP entrance from JVM mode, with a disposable SCI agent. `ThreadMXBean.getTotalThreadAllocatedBytes` and `OperatingSystemMXBean.getProcessCpuTime` bracket each request. These are **whole-JVM observation windows, not causal request truth**: other agents, GC and compilation can contribute. The API documents allocation as approximate; CPU has nanosecond precision, not guaranteed nanosecond accuracy. Current-thread allocation and CPU both return -1 on a virtual thread. Platform counters are available without sampling; no per-carrier subtraction can separate interleaved virtual requests.

Sources: [JDK allocation counters](https://docs.oracle.com/en/java/javase/26/docs/api/jdk.management/com/sun/management/ThreadMXBean.html), [thread CPU](https://docs.oracle.com/en/java/javase/26/docs/api/java.management/java/lang/management/ThreadMXBean.html), [process CPU](https://docs.oracle.com/en/java/javase/26/docs/api/jdk.management/com/sun/management/OperatingSystemMXBean.html).

Live before readings (decimal MB; CPU ms). The fresh explicit check recorded its test with automatic test-step disabled **only on the disposable branch**, then executed exactly one new test, reused zero, passed true.

| Case | Reply wall ms | Reply arm MB / CPU ms | JVM window MB / CPU ms | Candidate after evidence |
|---|---:|---:|---:|---|
| Warm `(+ 1 1)` | 72.6 | 0.0 / 0.2 | 65.149 / 226.341 | Window present; bounded comparison passes |
| One defn + deftest | 1519.7 | 27.9 / 20.5 | 1883.181 / 3645.097 | Window present; bounded comparison passes |
| Redefinition | 1564.4 | 28.4 / 18.4 | 2118.305 / 2386.601 | Window present; bounded comparison passes |
| Fresh `my.test/check` | 1318.2 | 1946.0 / 842.5 | 2645.993 / 3007.628 | One test executed; bounded comparison passes |
| `my.program/write-branch!` | 181.7 | 42.5 / 20.2 | 307.396 / 399.841 | Only empty-selection request tested; successful write unproven |
| `my.program/source` | 93.4 | 4.6 / 2.4 | 91.281 / 376.352 | Window present; bounded comparison passes |
| Joined virtual allocation, 8 MB | — | Virtual counters unavailable | — | Value returns; window observes ≥8 MB |

The live write returned “merge base unavailable within commit bound”; its row measures a refusal, not a successful write. Candidate tests use an empty identity selection to avoid external writes. After evidence is an allocation accuracy assertion, **not a second numeric performance benchmark**. Allocation's inner/outer window difference is bounded by max(8 MB, 20% of outer); CPU checks containment only. Outer includes cost printing and MCP JSON work excluded from the inner observation. A proposed CPU tolerance max(5 ms, 20%) failed: 73.149 ms fell outside the inner window (outer 214.950 ms). Comparing these different intervals cannot prove tight CPU accuracy; that assertion was removed, not called a pass. This verifies observation boundaries, not causal isolation. No claim of complete ground truth for a busy shared JVM is made.

[measurement-jfr.clj](measurement-jfr.clj) independently records allocation samples and groups their weights by thread kind and leaf Seon frame. For one redefinition: counter window 2336.517 MB, sampled weights 2326.047 MB (0.45% difference), reply arm 29.6 MB. Breakdown: virtual 1843.872 MB, async-mixed pool 441.351 MB, test-body 13.985 MB, other 26.839 MB. 7998 allocation samples; no CPUTimeSample or ExecutionSample events appeared with this recording's settings on this machine. Absence is **unknown CPU attribution**, never zero. Samples can span the window boundary; weight is not exact bytes per function. 1675.949 MB had no Seon frame in the recorded stack. JFR is diagnostic, not an always-on per-request counter.

## What this slice fixes

- `seon.batch/request-cost` labels top-level allocation/CPU `:evaluation-arms-only`, causal attribution `:unavailable`. `:unattributed` makes rounded wall stages sum to total; negative residual exposes overlapping clocks rather than hiding them.
- `seon.mcp/eval-clj` and `seon.profile/measure` add `:seon.cost/jvm-window` with explicit scope, accuracy and unavailable-counter data. The final cost's own printing and outer JSON encoding remain outside that window.
- `seon.test/measured-body` adds its completed sink to its parent while retaining each test's counters. The regression observes exactly 17 helper calls in the parent and the test's own stored call count.
- Counter reads sit outside `seon.profile/measure`'s function-count snapshots. An initial placement polluted the rankings and failed the existing read-count test; the corrected placement passes it.

Top functions **per stage**, library call counts, causal all-thread CPU/allocation and completed asynchronous work remain missing. Existing per-function self time and calls cover instrumented first-party functions; library work is folded into its instrumented caller. The open task `seon.batch/a-request-cost-counts-only-the-evaluation-arm` is not retired.

## Flow seam and the remaining design decision

Pins examined: core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`; Datahike `e65599de5eb393ca842304b6487453616214ea26`; Malli `dd13ff81139159778edb0a67b491eccc0baa991d`.

`seon.flow/var-process` wraps each step; `seon.flow/start-graph!` supplies the virtual `:io-exec` made by `seon.flow/graph-executor`. core.async `clojure.core.async.impl.dispatch/executor-for` uses virtual IO and platform compute/mixed. Request identity and accounting must travel in messages: Flow does not convey caller bindings. `seon.cluster.agent/blueprint` has **three** long-lived IO loops per agent. A fixed pool smaller than live loops can starve graphs; a platform switch needs admission/thread-count policy, not merely a pool dial.

Two correctness boundaries prevent the proposed single-wrapper change:

1. `seon.turn/step` offers the reply before returning to the generic step wrapper. Step-exit counters cannot reach an already-delivered reply. Final reply publication must follow completion accounting. Nested same-thread spans must not be counted twice. Concurrent wall intervals require separate worker-time and elapsed-time semantics.
2. `datahike.writer/create-thread` runs processing and commit go-loops, not Flow. Its commit loop batches several submissions into one `datahike.writing/commit!`. Root bindings intentionally remove caller custody. Wrapping a synchronous caller's wait measures neither writer allocation nor CPU. The maintained fork needs an observation hook and an explicit shared-commit charging rule. There is currently no library hook supplying causal per-request allocation/CPU here.

The root-frame virtual thread in `seon.db` captures bindings at initialization, not on every request. Ordinary `seon.cache/lookup` derives synchronously; `seon.cache/lookup-within` may leave a virtual derivation running after a waiter returns. Web workers and detached work likewise need pending observations or an explicit excluded-work declaration, not invented final totals.

Options, in increasing scope: (A) land this bounded observation/call fix now (proven; causality remains unknown); (B) **recommended next**, change Flow completion ordering and platform step execution, plus the Datahike observation hook/shared-commit rule (multi-owner work, approximately 1–2 days including proof; exact available thread counters, shared cost explicitly labeled); (C) retain virtual steps and use JFR attribution (sampled, unsuitable for exact small-request CPU on the tested setup). Per-stage function allocation can use platform allocation deltas minus child deltas; CPU sampling and library calls must remain labeled unavailable where unsupported. No replacement writer or scheduler is proposed.

## Overhead and qualification

[measurement-flow.clj](measurement-flow.clj) compares the actual core.async Flow library with one owned graph at a time. 100 small steps: virtual 10.58/9.82 ms, platform 9.32/19.17 ms. Platform steps report 114952 allocated bytes and warm CPU 11–23 µs; virtual readings are unavailable. These small noisy runs establish counter availability, **not a throughput win**. Native stack cost is unmeasured; default already had 269 platform threads and RSS 5611376 KiB at one observation.

Warm raw counter costs: current-thread allocation 0.037 µs, CPU 0.391 µs, both 0.423 µs; whole-JVM allocation+CPU pair 3.92 µs. Two pairs/request are about 7.84 µs, 0.013% of a 60 ms trivial request, before instrumentation/formatting. Alternating warm trivial requests with/without outer snapshots had medians 60.37/60.27 ms; noise exceeds the estimated overhead. Always-on per-call CPU reads would add roughly 60 ms at 77000 calls; prefer step counters. Complete candidate wrapper/formatting overhead has not been isolated.

To qualify a solution, run finite sizes N, 2N, 4N through `seon.profile/measure`, preserve the value and measurements, and assert deterministic work bounds with `seon.profile/calls-of`; do not use a truncated top-functions list for an exact symbol count. Compare warm repeated wall/allocation windows in an otherwise idle disposable execution, separately from acquisition. Reject a repeatable bad scaling ratio or bound breach; a shared-JVM outlier requires attribution before blaming the solution. Arm allocation alone cannot qualify end-to-end cost. Do not assert precise causal CPU against these JVM windows.

## Proof and limits

Run `3d9ec1ea3391`: initial slice, 4 tests, 34 assertions, green. Expanded run `f6f28c185ec2`: 10 tests, one ranking failure caused by the new counter placement. Corrected run `d596b205ec63`: 10 tests, 103 assertions, zero failures/errors, tests 15947 ms; full check 65797 ms, publication 7908 ms, adoption 1705 ms. Commit attempt `d0ba2298e43d`: 25 tests, 177 passes, one invalid CPU-window tolerance failure described above; nothing committed. These isolated checks publish/adopt HEAD plus the claimed paths; default stays unchanged.

Landed `cdce8a37e` through `bin/lane commit astra-measure`: final run `19f83391c75e`, 25 tests / 171 assertions / zero failures or errors, tests 15861 ms, total gate 54963 ms, publication 6880 ms, adoption 1326 ms. Full `src script test` lint with `--cache false`: zero errors, 1462 warnings. Net production Clojure +50 lines, tests +68, schema +13; three reproducible probe scripts total 133 lines. Tests selected: all `seon.request-cost-test`, `seon.test-cost-test`, `seon.profile-test`; `seon.eval-alloc-test/an-evaluation-reply-shows-what-it-allocated` and `seon.eval-alloc-test/a-measured-call-reports-allocation-and-reads`; MCP entrance vars `an-sci-call-answers-what-the-agents-next-context-reads`, `a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle`, `a-jvm-reply-keeps-its-duration-after-an-earlier-run`, `a-thrown-call-carries-its-whole-request`. No broad tier claimed. The case branch accepted its own retirement; the edit branch is preserved for its unresolved acquisition evidence.

Default was already degraded: six open faults, 114 unknown tests and maintenance overruns. SCI edit-hook acquisition on this lane cannot install the new host/schema boundary on old default (`7c52d6ce…`, unresolved `seon.batch/jvm-window`); isolated candidate proof is used, not branch evidence. A disposable SCI config write also refused its current-evaluation reference (`aefbc3b8…`); explicit JVM root bindings on the owned connection permitted configuration. Foreign boundary: dirty http-kit checkout, root-report documentation and other lanes' unclaimed work were excluded from the candidate and never edited. No pre-existing red was waived in the selected final test set.
