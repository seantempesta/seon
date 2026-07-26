---
type: research
status: active
tags: [research, runtime]
---

# JVM boot remeasurement after the guarded-door deletion, 2026-07-26

## Question

The comparable 2026-07-25 measurement was one source/AOT+AppCDS pair of
**10,293 → 3,886 ms** under OpenJDK 26.0.1, `-Xmx2g`, and G1. Its remaining
3,886 ms was reported as 63% in `sci.core` (825 ms),
`seon.host.context` (900 ms), and `seon.db.writer` (723 ms).
Commit `8dc8623ad5053d90f34e84803638735937778715` then deleted the old guarded
door, including `seon.host.context` and `seon.host.eval`, and reduced
`seon.host` to the current 61-line main. This note repeats the same three-mode
boot breakdown on the post-deletion tree.

## Conditions

| condition | value |
|---|---|
| measured | 2026-07-26 |
| checkout | `4dbaeda0ef905c07600637e86df5d5de8fc7e725` |
| named change | guarded-door deletion `8dc8623ad5053d90f34e84803638735937778715` |
| machine | Apple M5 Max, 18 available processors, 128 GiB RAM |
| operating system | Darwin 25.5.0, arm64 |
| JDK | OpenJDK 26.0.1, Homebrew build, 64-Bit Server VM, mixed mode, sharing |
| Clojure | 1.12.5; the prior note recorded 1.12.0, so this is a changed condition |
| JVM flags in every timed run | `--add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -XX:+UseG1GC -Xmx2g` |
| source aliases | `-M:writer:host` |
| process state | `bin/seon down`; no Seon processes were live during timed runs |
| run state | five separate fresh JVMs per mode, run sequentially; filesystem caches were not flushed |

The operator checked the artifact immediately before measurement.
`bin/seon up` reported `reuse canonical shared JVM AOT+CDS pair`; writer and the
new host reached ready, while the pod refused its already-known applied-release
mismatch. `bin/seon down` then removed every supervised process. The artifact
was therefore current and did not need rebuilding.

| artifact | conditions |
|---|---|
| AOT jar | `tmp/seon-jvm-artifacts/def4d7a59283a44932c1190452e1f01ceda9e21be86b7dd06661d2b1318dc806/seon-jvm-aot.jar`; 53,654,391 bytes; SHA-256 `7f707c5e4cbdaa245e230f95dcb4a5a61df4018648c0cc9f68fc6b40b43714e9` |
| AppCDS archive | same directory, `seon-jvm-aot.jsa`; 89,210,880 bytes; SHA-256 `656a3176c89c329873960343aecfc40496c4cb49f8993dd72b11c14985c9d70d` |
| published manifest | version 14, published `2026-07-26T15:32:18.927751Z` |

The archive still emits two degraded-use diagnostics: the dump did not record
the runtime values for `jdk.module.addmods` or
`jdk.module.enable.native.access`. `-Xshare:on` did not abort. A separate
logging-only check observed 3,016 class loads from the shared-objects file, so
the archive was in use; optimized module handling was not. That check is not
included in the timing sample.

## Method

The retained `tmp/boot_breakdown.clj` method was preserved: record
`System/nanoTime` around ordered `require` calls, report heap and RSS after each
step, and report total wall time since `clojure.core` was already loaded. The
current-tree copy is `tmp/boot_breakdown_current.clj`. It keeps the prior writer
sequence intact, removes only the two namespaces deleted by `8dc8623ad`, and
loads the surviving host closure leaf-first:

```clojure
datahike.api
sci.core
seon.schema
seon.db
seon.db.writer
seon.ai.http
seon.db.protocol
seon.db.host
seon.sci.ctx
seon.sci.interrupt
seon.sci.eval
seon.agent.driver
seon.host

```

Each labeled time is an incremental `require` boundary and therefore includes
any transitive namespace work not loaded by an earlier row; it is not an
isolated profiler sample for one source file.

The three launch modes exactly match the prior flags:

```bash
# Source classpath.
java --add-modules jdk.incubator.vector \
  --enable-native-access=ALL-UNNAMED \
  --sun-misc-unsafe-memory-access=allow \
  -XX:+UseG1GC -Xmx2g \
  -cp "$(clojure -Spath -M:writer:host)" \
  clojure.main tmp/boot_breakdown_current.clj

# AOT jar, archive disabled.
java --add-modules jdk.incubator.vector \
  --enable-native-access=ALL-UNNAMED \
  --sun-misc-unsafe-memory-access=allow \
  -XX:+UseG1GC -Xmx2g -Xshare:off \
  -cp "$AOT_JAR" \
  clojure.main tmp/boot_breakdown_current.clj

# AOT jar plus AppCDS.
java --add-modules jdk.incubator.vector \
  --enable-native-access=ALL-UNNAMED \
  --sun-misc-unsafe-memory-access=allow \
  -XX:+UseG1GC -Xmx2g -Xshare:on \
  -XX:SharedArchiveFile="$APPCDS_ARCHIVE" \
  -cp "$AOT_JAR" \
  clojure.main tmp/boot_breakdown_current.clj

```

## Raw total boot numbers

Times are milliseconds since `clojure.core`; run numbers show collection order
within each mode and are not paired simultaneous observations.

| run | source | AOT, `-Xshare:off` | AOT + AppCDS |
|---:|---:|---:|---:|
| 1 | 9,160 | 2,935 | 2,537 |
| 2 | 9,216 | 3,083 | 2,838 |
| 3 | 9,414 | 2,916 | 2,877 |
| 4 | 10,278 | 3,208 | 2,903 |
| 5 | 9,766 | 3,196 | 2,816 |
| **mean** | **9,567** | **3,068** | **2,794** |
| **median** | **9,414** | **3,083** | **2,838** |
| **range** | **9,160–10,278** | **2,916–3,208** | **2,537–2,903** |
| **sample standard deviation** | **463** | **139** | **148** |

Every run loaded 336 namespaces. The source path's 1,118 ms range was mostly
`datahike.api` (6,193–6,942 ms); the AOT+AppCDS path's 366 ms range was spread
across the residual steps.

## Current AOT plus AppCDS residual

The AOT configuration lists 246 namespace symbols. “AOT-listed” below means
the symbol occurs in
`resources/seon/dev/writer-aot-namespaces.edn`; it does not mean its runtime
initialization cost is zero.

| incremental require | AOT-listed? | five raw times, ms | mean, ms | range, ms | share of 2,794 ms mean |
|---|---|---|---:|---:|---:|
| `sci.core` | no | 696, 777, 830, 786, 794 | **777** | 696–830 | 27.8% |
| `seon.db.writer` | yes | 679, 735, 759, 825, 784 | **756** | 679–825 | 27.1% |
| `seon.ai.http` | no | 321, 381, 310, 325, 307 | **329** | 307–381 | 11.8% |
| `datahike.api` | yes | 255, 278, 324, 291, 297 | **289** | 255–324 | 10.3% |
| `seon.db` | yes | 221, 256, 249, 283, 255 | **253** | 221–283 | 9.0% |
| `seon.agent.driver` | no | 176, 208, 202, 194, 189 | **194** | 176–208 | 6.9% |
| `seon.sci.ctx` | no | 44, 38, 39, 39, 38 | 40 | 38–44 | 1.4% |
| `seon.db.host` | no | 27, 34, 35, 29, 28 | 31 | 27–35 | 1.1% |
| `seon.sci.interrupt` | no | 22, 24, 22, 24, 22 | 23 | 22–24 | 0.8% |
| `seon.sci.eval` | no | 16, 20, 18, 19, 18 | 18 | 16–20 | 0.7% |
| `seon.schema` | yes | 5, 5, 5, 5, 5 | 5 | 5–5 | 0.2% |
| `seon.host` | no | 2, 2, 2, 2, 2 | 2 | 2–2 | 0.1% |
| `seon.db.protocol` | yes | 0, 0, 0, 0, 0 | 0 | 0–0 | 0.0% |
| timer/reporting remainder | — | — | 79 | — | 2.8% |

The deleted `seon.host.context` 900 ms row has no successor of comparable
size. The new current host-specific rows from `seon.db.host` through
`seon.host`, excluding the independently listed driver, sum to a mean 113 ms;
the 61-line `seon.host` main itself averages 2 ms.

## AOT and AppCDS coverage

The comparable prior 92.7% / 7.3% numbers were attribution of the
`datahike.api` saving, not counts of covered namespaces. Repeating that
calculation over the five-run means:

| `datahike.api` stage | prior single run, ms | current mean, ms |
|---|---:|---:|
| source | 6,568 | 6,464 |
| AOT, archive off | 755 | 582 |
| AOT + AppCDS | 300 | 289 |
| total saving | 6,268 | 6,175 |
| AOT contribution | 5,813 / **92.7%** | 5,882 / **95.3%** |
| AppCDS contribution | 455 / **7.3%** | 293 / **4.7%** |

Across the complete measured boot rather than only `datahike.api`, the current
mean saving is 6,773 ms: AOT contributes 6,499 ms (**96.0%**) and AppCDS adds
273 ms (**4.0%**). These percentages carry the degraded archive condition
above.

## Comparison with the prior boot

The prior values are single runs. Current deltas use the five-run means; the
range remains visible so the comparison does not imply more precision than
was measured.

| mode | prior, ms | current mean, ms | current range, ms | delta | delta percent |
|---|---:|---:|---:|---:|---:|
| source | 10,293 | 9,567 | 9,160–10,278 | **−726 ms** | **−7.1%** |
| AOT, archive off | 3,976 | 3,068 | 2,916–3,208 | **−908 ms** | **−22.9%** |
| AOT + AppCDS | 3,886 | 2,794 | 2,537–2,903 | **−1,092 ms** | **−28.1%** |

The named intervening change is the guarded-door deletion
`8dc8623ad`, especially removal of the prior ~900 ms
`seon.host.context` load; the checkout also now reports Clojure 1.12.5 rather
than the prior 1.12.0, so the observed delta must not be attributed to the
deletion alone as a controlled causal estimate.

## Conclusion

Boot is no longer dominated by the prior three non-AOT namespaces: non-AOT `sci.core` is now the single slowest incremental require at 777 ms, essentially tied with AOT-listed `seon.db.writer` at 756 ms, while the next non-AOT residuals are `seon.ai.http` at 329 ms and `seon.agent.driver` at 194 ms.
