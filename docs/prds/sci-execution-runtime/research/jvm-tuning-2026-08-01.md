---
type: research
status: active
tags: [research, runtime]
---

# JVM tuning for reasonable consumer hardware — measured 2026-08-01

## Verdict

Keep G1, reduce the heap percentage from 25% to 12.5%, and make G1's idle
periodic collection explicit. Do not add ZGC, `SoftMaxHeapSize`, string
deduplication, compact object headers, fixed TLAB/young-generation sizes, or a
custom AppCDS archive. Remove `--sun-misc-unsafe-memory-access=allow`; retain
the Vector API and native-access flags that Proximum demonstrably needs.

The recommended 16 GB default is a **2 GiB maximum heap**, not a 4 GiB heap.
The actual indexing burst and 12-turn drive both completed at 2 GiB. On the
measurement Mac, G1 peaked at 1.59 GiB RSS for indexing and 1.58 GiB RSS for
the turn drive. After a 60-second idle, a 30-second G1 periodic interval left
942 MiB RSS and 384 MiB committed heap. This is the only tested collector and
heap combination that met both sides of the owner's request: good turn/index
performance and a JVM that does not retain most of the available memory.

Recommended review diff for each of `:dev`, `:test`, and `:writer`:

```clojure
:jvm-opts ["--add-modules" "jdk.incubator.vector"
           "--enable-native-access=ALL-UNNAMED"
           "-XX:+UseG1GC"
           "-XX:MaxRAMPercentage=12.5"
           "-XX:G1PeriodicGCInterval=30000"]
```

This document recommends but does not apply that production change.

## Measurement contract

### Host and projection rule

The host was a 2026 M5 MacBook Pro, 18 logical processors, 128 GiB unified
memory, macOS 26.5, Homebrew OpenJDK 26.0.1, arm64. The primary matrix used
commit `4f7a8fb36a159c3605f15f4552906fc31c9b35ba`; scripts first archive that
committed first-party tree so concurrent first-party source edits cannot
change a case halfway through. Vendored revisions are pinned separately below.
Every case used its own JVM, private process root, private Datahike file store,
and scratch cluster. The shared default cluster was untouched.

The primary matrix is one whole-workload run per cell, not a microbenchmark
confidence interval. Each run nevertheless contains 38–121 measured GC
pauses. The CDS comparison has five repetitions per condition. Conclusions
are limited to effects large enough to survive that design.

The 128 GiB host does **not** make an observed wall time a 16 GiB prediction.
CPU, SSD, thermal, and OS differences do not scale linearly. Each GC case
instead set `-Xmx2g`, `4g`, or `8g` explicitly. Thus the 2 GiB case is the
capacity-equivalent 16 GiB projection for the proposed 12.5% policy: the same
maximum heap, collector, object graph, classpath, and workload. Its macOS RSS
is a measured reference, not a guarantee for another OS. Windows should be
validated with Working Set and Private Bytes; Linux with RSS/PSS.

### Workloads

- **Indexing burst:** `seon.cluster/refresh-source!` against an empty private
  root, which runs the real source analysis, program projection, Datahike
  indexing, persistent-sorted-set flush, and Konserve file-store commit. This
  is the path that OOMed under the former 512 MiB cap.
- **Turn drive:** publish the source, start one real private cluster, transact
  12 sequential messages to root, await each durable closed run through
  Datahike `listen`, and execute the real SCI/print/admission/database path.
  Only the remote model call is replaced by a deterministic EDN- and
  string-allocating response. The measured interval excludes source
  publication and startup and includes the 12 complete turns.
- **Idle reclamation:** the same turn drive followed by heap samples at 15,
  30, 45, and 60 seconds, with one-second process RSS sampling throughout.

The runner uses `-Xlog:gc*,safepoint,gc+heap=debug,gc+tlab=debug,
stringdedup=debug`, `-Xms16m`, one explicit maximum heap, and direct `java` so
the alias's G1 flag cannot silently override a collector case. The production
recommendation does not need `-Xms16m`; leaving initial heap ergonomic is what
permits a small process and demand-driven growth.

Reproduce with:

```bash
tmp/jvm-tuning/freeze-head.sh
tmp/jvm-tuning/run-matrix.sh
tmp/jvm-tuning/flags.sh
tmp/jvm-tuning/cds.sh
```

`run-case.sh` also accepts individual collector experiments; `summarize.py`
extracts workload time, pause quantiles, heap, and RSS from the raw results.

## Why this stack allocates this way

This is not a generic "Clojure likes G1" recommendation.

- Datahike's self writer owns transaction and commit queues
  (`reference-code/datahike/src/datahike/writer.cljc:85-104,201-245`). A commit
  flushes the EAVT/AEVT/AVET persistent-set indexes
  (`reference-code/datahike/src/datahike/writing.cljc:73-84`). Seon's file
  configuration enables root fusion and a 256-entry diff buffer
  (`src/seon/cluster/store.clj:155-178`); fusion inlines post-flush root nodes
  into the database record (`writing.cljc:142-180`). This creates a bursty
  short-lived allocation profile around a durable live index.
- Konserve stages serialized blobs, optionally forces each file, atomically
  moves it, then forces the directory
  (`reference-code/konserve/src/konserve/filestore.clj:90-149`); JVM sync uses
  `FileChannel.force(true)` (`filestore.clj:518-521`). The adjacent admission
  study measured a 43 ms file-store transaction floor, 104 ms for an inline
  8 MB value, and 10.01 ms for the same 8 MB as a blob
  (`admission-caps-and-blob-fallback-2026-08-01.md:211-247`). Those storage
  barriers and serialization allocations are part of the workload, not GC
  folklore.
- Core.async flow's default `:io` executor starts one virtual thread per task
  (`reference-code/core.async/src/main/clojure/clojure/core/async/impl/
  dispatch.clj:75-96`), and a proc runs its parked loop on the resolved `:io`
  executor (`flow/impl.clj:243-323`). The earlier direct measurement found
  about 8.5 KiB per parked proc (`flow-mechanics-2026-07-28.md:38-51`).
  **Current-stack correction:** Seon's current process-root resolver supplies
  a cached platform-thread pool for `:io`, not that default virtual-thread
  executor (`src/seon/cluster.clj:156-179`). Therefore this turn matrix
  measured today's agent graphs on platform `:io` threads. The web feed does
  independently start one virtual thread per SSE connection
  (`src/seon/render/web.clj:720-804`). This architecture/source mismatch should
  be settled in the flow owner; it is not a reason to tune a collector around
  either implementation.
- JDK 26 includes the virtual-thread monitor-pinning removal delivered by
  [JEP 491](https://openjdk.org/jeps/491) in JDK 24. Thus synchronized blocking
  no longer pins a carrier merely because the task is virtual. Parked virtual
  thread continuations remain ordinary heap-reachable state; neither that nor
  http-kit's long-lived sockets creates a collector-specific requirement. The
  measured live-set and pause/footprint trade-off should select the collector.
- SCI evaluates synchronously on its caller's bounded `:compute` task and
  prints through a `StringWriter` (`src/seon/sci/eval.clj:1216-1264,
  1287-1304`). Its interrupt hook enters every interpreted function body
  (`reference-code/sci/doc/interrupt.md:6-8,50-52`). The deterministic drive
  exercises that platform-thread interpretation plus EDN and string churn.
- Http-kit exposes exact pending-byte and drain completion for the long-lived
  SSE channel (`reference-code/http-kit/src/org/httpkit/server.clj:321-326`),
  and Seon parks the feed virtual thread on that completion before sending the
  newest sliding-buffer value (`src/seon/render/web.clj:710-739`). Long-lived
  idle connections therefore add roots and thread metadata, but not a reason
  for sub-millisecond GC pauses at any footprint cost.

Pinned source revisions: Datahike `256b714d`, Konserve `737697d9`,
persistent-sorted-set `e1a17bbe`, core.async `dc35f3e0`, SCI `a27e2c0`,
http-kit `238a85cc`, and Proximum `9846d3e7`.

## G1 versus generational ZGC

On JDK 26 `-XX:+UseZGC` already means generational ZGC. The separate
`-XX:+ZGenerational` flag is obsolete and this JVM rejects it. This matches
[JEP 490](https://openjdk.org/jeps/490), which removed non-generational ZGC in
JDK 24.

### Indexing burst

| collector | max heap | wall, workload | pauses n | p50 | p95 | p99 | max | peak RSS | final committed heap |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| G1 | 2 GiB | **10.681 s** | 63 | 3.027 ms | 10.441 ms | 18.666 ms | 26.198 ms | **1.59 GiB** | 1.04 GiB |
| ZGC | 2 GiB | 13.316 s | 109 | 0.008 ms | 0.042 ms | 0.054 ms | 0.068 ms | 2.60 GiB | 1.89 GiB |
| G1 | 4 GiB | **10.548 s** | 54 | 2.721 ms | 10.937 ms | 23.372 ms | 27.729 ms | **1.94 GiB** | 1.34 GiB |
| ZGC | 4 GiB | 11.198 s | 62 | 0.007 ms | 0.020 ms | 0.047 ms | 0.054 ms | 4.59 GiB | 3.84 GiB |
| G1 | 8 GiB | **11.338 s** | 56 | 2.786 ms | 15.730 ms | 22.711 ms | 25.653 ms | **2.90 GiB** | 3.88 GiB |
| ZGC | 8 GiB | 11.630 s | 38 | 0.007 ms | 0.033 ms | 0.039 ms | 0.039 ms | 8.57 GiB | 7.89 GiB |

### Twelve-turn drive

| collector | max heap | wall, 12 turns | pauses n | p50 | p95 | p99 | max | peak RSS | final committed heap |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| G1 | 2 GiB | **5.445 s** | 102 | 2.241 ms | 10.592 ms | 25.873 ms | 41.321 ms | **1.58 GiB** | 523 MiB |
| ZGC | 2 GiB | 5.496 s | 121 | 0.008 ms | 0.037 ms | 0.045 ms | 0.053 ms | 2.59 GiB | 1.88 GiB |
| G1 | 4 GiB | 5.901 s | 72 | 2.899 ms | 14.310 ms | 29.368 ms | 42.901 ms | **1.93 GiB** | 1.20 GiB |
| ZGC | 4 GiB | **5.654 s** | 76 | 0.008 ms | 0.031 ms | 0.058 ms | 0.075 ms | 4.58 GiB | 3.86 GiB |
| G1 | 8 GiB | **5.813 s** | 70 | 3.409 ms | 13.293 ms | 38.511 ms | 41.545 ms | **1.85 GiB** | 1.25 GiB |
| ZGC | 8 GiB | 8.124 s | 46 | 0.007 ms | 0.042 ms | 0.205 ms | 0.329 ms | 8.31 GiB | 7.49 GiB |

ZGC delivers its advertised sub-millisecond pauses, but the consumer target
does not need to pay 1.0–6.7 GiB more peak RSS for them. G1 was faster in all
six indexing cells and in two of three turn cells. Its worst observed pause
was 42.9 ms, small against a multi-hundred-millisecond local turn and remote
provider latency. More heap did not make either real workload faster. **G1 at
2 GiB is the knee.**

For a 16 GiB machine the 2 GiB rows transfer directly as heap-capacity tests.
Their RSS is 9.9–10.0% of 16 GiB for G1, versus 16.2% for ZGC. The 4 and 8 GiB
rows are not estimates for a 16 GiB default; they establish the same derived
policy's behavior on 32 and 64 GiB hosts.

### Idle return to the OS

| 2 GiB case after turn | turn wall | peak RSS | RSS after 60 s | committed heap after 60 s | used heap after 60 s |
|---|---:|---:|---:|---:|---:|
| G1, ergonomic default (`G1PeriodicGCInterval=0`) | 5.099 s | 1.59 GiB | 1.06 GiB | 514 MiB | 405 MiB |
| G1, periodic 30 s | **4.998 s** | 1.59 GiB | **942 MiB** | **384 MiB** | 114 MiB |
| ZGC, default (`ZUncommitDelay=300`) | 5.582 s | 2.59 GiB | 2.58 GiB | 1.88 GiB | 252 MiB |
| ZGC, `SoftMaxHeapSize=1g`, uncommit delay 30 s | 8.100 s | 1.78 GiB | 1.36 GiB | 756 MiB | 318 MiB |

G1 already shrank after active collection, but by default it does not start a
periodic idle cycle. A 30-second interval returned another 146 MiB RSS and 130
MiB committed heap by the one-minute sample without slowing the active turn.
That is 5.7% of a 16 GiB machine after idle. Oracle's JDK 26 G1 guide describes
periodic collection specifically as the mechanism for returning unused
committed memory during idle periods
([G1 periodic GC](https://docs.oracle.com/en/java/javase/26/gctuning/garbage-first-g1-garbage-collector1.html)).

ZGC's default 300-second uncommit delay retained essentially its whole 2 GiB
heap through the observed minute. `SoftMaxHeapSize` is useful only as a ZGC
soft target; it is not a G1 reclamation dial. Reducing ZGC's delay reclaimed
memory, but still ended 446 MiB above periodic G1 and performed 217 GC pauses
versus G1's 93. Do not add SoftMax to the G1 default.

The 30-second interval is deliberately a measured policy number, not a GC
folk constant: it is the earliest case tested that produced visible return by
the owner's one-minute "polite idle" horizon. If a future always-busy server
shows periodic-cycle CPU cost, remeasure this one dial rather than adding
young-generation knobs.

## Heap policy and compressed ordinary object pointers

The current `MaxRAMPercentage=25.0` is correct only on the smallest target.
`PrintFlagsFinal` made the large-host failure concrete:

| effective input | max heap | compressed ordinary object pointers |
|---|---:|---:|
| current 25%, actual 128 GiB host | **32 GiB** | **off** |
| proposed 12.5%, actual 128 GiB host | 16 GiB | on |
| current 25%, simulated 16 GiB | 4 GiB | on |
| proposed 12.5%, simulated 16 GiB | **2 GiB** | on |

The exact 32 GiB current result disables compressed ordinary object pointers
on this JVM. That inflates references throughout Datahike's index trees,
Clojure maps, SCI values, and strings precisely on a big development machine.
The common suggestion to combine a percentage with `-XX:MaxRAM=...` is not a
good JDK 26 answer: this JVM warns that `MaxRAM` is deprecated and likely to
be removed. A 12.5% policy is one derived rule that stays below the boundary
on the owner's 128 GiB machine and gives the measured 2 GiB consumer target.

Do not add `-Xms` or a fixed `-Xmx` to the aliases. Percentage scaling is the
only machine-specific behavior required. Do not add `SoftMaxHeapSize`: its
useful implementation is ZGC's soft ceiling, while the selected G1 policy
already grows on demand and reclaims through periodic GC.

## Startup: CDS, AppCDS, and CRaC

The stock JDK default CDS archive is already enabled. `-Xlog:cds=info` showed
`$JAVA_HOME/lib/server/classes.jsa` loading without a project flag.

Dynamic AppCDS cannot archive Seon's real development classpath directly:
JDK 26 refused `ArchiveClassesAtExit` because entries such as `src` and the
vendored fork source roots are non-empty directories. The probe therefore
packaged every directory into an order-preserving JAR before testing. That is
already operational cost which the current source-first system does not have.

| `(require 'seon.cluster)` condition | five runs, ms | median |
|---|---|---:|
| actual source-directory classpath, default CDS | 10872, 13790, 13522, 13669, 10378 | 13522 |
| staged JAR classpath, default CDS | 10864, 11449, 13154, 12190, 13519 | **12190** |
| staged JAR classpath, custom AppCDS | 12309, 11907, 12519, 12344, 10406 | 12309 |
| staged JAR classpath, `-Xshare:off` | 12607, 13110, 12300, 12429, 9950 | 12429 |

Packaging took 1.930 seconds. Archive creation took 14.386 seconds and created
a 139,067,392-byte (132.6 MiB) archive. Custom AppCDS was 119 ms slower than
the ordinary staged classpath median and only 120 ms faster than disabling CDS;
the run-to-run spread is much larger. The apparent 1.3-second staged-versus-
actual difference cannot be attributed to AppCDS and would require converting
the live source classpath to JARs on every change.

Reject custom AppCDS. It does not materially improve the ten-second-start
budget, adds a large artifact, and must be regenerated on every classpath or
source-directory change. The JDK classpath constraint and archive lifecycle
are documented in the [JDK 26 `java` command](https://docs.oracle.com/en/java/javase/26/docs/specs/man/java.html).

CRaC is a note, not a recommendation. It is absent from this stock Homebrew
OpenJDK 26. The OpenJDK [CRaC project](https://openjdk.org/projects/crac/)
remains a separate project; Azul's current runtime documentation says full
checkpoint/restore is supported on Linux x86-64/aarch64 while macOS and
Windows builds provide simulation only and cannot create a checkpoint
([Azul CRaC runtime](https://docs.azul.com/crac/usage/running-crac.html)). It
therefore cannot be the one consumer Mac/Windows startup mechanism. A restored
image also carries sizing assumptions from its checkpoint host, contrary to
the derived-hardware policy here.

## Allocation dials: all rejected

| candidate, G1 2 GiB | matched baseline | candidate | result |
|---|---:|---:|---|
| string dedup, turn wall | 5.445 s | 6.419 s | 17.9% slower |
| string dedup, peak RSS | 1.58 GiB | 1.61 GiB | no footprint win |
| compact headers, init wall | 10.681 s | 11.023 s | 3.2% slower |
| compact headers, init peak RSS | 1629 MiB | 1625 MiB | only 4 MiB saved |
| compact headers, turn wall | 5.445 s | 8.273 s | 52% slower; reject on one-run evidence |
| fixed 512 KiB TLAB, init wall | 10.681 s | 16.418 s | 53.7% slower |
| fixed 512 KiB TLAB, init peak RSS | 1629 MiB | 1640 MiB | worse |
| 30–80% G1 young range, init wall | 10.681 s | 12.175 s | 14.0% slower |
| 30–80% G1 young range, p95 pause | 10.441 ms | 17.429 ms | worse |

`UseStringDeduplication` did find the expected repeated EDN strings: 287,433
deduplications, 19.17 MiB, 55.8% of inspected candidates. The saving was too
small to reduce RSS and the active workload was slower. Leave it off.

Compact object headers are a product flag in this JDK 26 build and default
off. [JEP 519](https://openjdk.org/jeps/519) made the feature product in JDK
25 but did not enable it by default. Seon's direct runs did not reproduce a
useful memory reduction, so do not opt in ahead of JVM ergonomics.

`TLABSize=1m` did not even start: JDK 26 reported an ergonomic maximum of
524,288 bytes. Fixing that maximum and disabling resizing was much slower.
Leave `ResizeTLAB=true` and `TLABSize=0`. Likewise leave G1's young-generation
range ergonomic. The indexing burst benefited from neither a larger fixed
young range nor a manually sized allocation buffer.

## Existing flag audit

| flag | recommendation | provenance |
|---|---|---|
| `-XX:+UseG1GC` | retain | Six-cell real workload matrix above; best performance/footprint compromise. |
| `-XX:MaxRAMPercentage=25.0` | replace with `12.5` | `PrintFlagsFinal` proves 32 GiB/uncompressed pointers on 128 GiB; 2 GiB real workloads pass. |
| `-XX:G1PeriodicGCInterval=30000` | add | Real 60-second idle test returned another 146 MiB RSS; Oracle JDK 26 documents the mechanism. |
| `--add-modules jdk.incubator.vector` | retain | Proximum imports `jdk.incubator.vector.FloatVector` (`reference-code/proximum/src/proximum/vectors.clj:33-41`); requiring it without the module failed with `ClassNotFoundException`. |
| `--enable-native-access=ALL-UNNAMED` | retain | Proximum maps its vector file through `Arena`/`MemorySegment` (`vectors.clj:186-207`) and its own maintained aliases carry the flag (`reference-code/proximum/deps.edn:32-40`). |
| `--sun-misc-unsafe-memory-access=allow` | remove | No maintained Seon or selected fork source references `sun.misc.Unsafe`; a complete private 2 GiB indexing run succeeded without it and emitted only the expected incubator-module warning. |
| `-XX:+EnableDynamicAgentLoading` | do not add | `PrintFlagsFinal` says it is already true on this JDK 26 build. Proximum merely has `clj-memory-meter` as a transitive dependency (`reference-code/proximum/deps.edn:8-15`); Seon has no call site. A future explicit meter probe can opt into self-attach in its probe alias. |
| `-XX:SoftMaxHeapSize=...` | do not add | ZGC-only useful policy; ZGC rejected by measured footprint. |
| `-XX:+UseStringDeduplication` | do not add | 19.17 MiB deduplicated, but slower with higher peak RSS. |
| `-XX:+UseCompactObjectHeaders` | do not add | Available, default off, direct workload shows no useful win. |
| TLAB/young sizing | do not add | Both direct experiments regressed indexing. |

No flag is recommended solely to silence a warning. The remaining incubator
warning describes a real Proximum dependency; the native-access flag permits
a real foreign-memory operation. Dynamic-agent and Unsafe allowances do not
describe a current runtime operation.

## Consumer hardware matrix

One flag set serves all rows. Only `MaxRAMPercentage` derives a different
maximum; there is no "beefy dev" tuning profile.

| physical memory and plain hardware equivalence | derived max heap | evidence and expected behavior |
|---|---:|---|
| **16 GiB Apple M-series**, SSD; or **16 GiB modern x86-64 Windows laptop**, NVMe SSD, at least 4 performance cores / 8 logical processors | **2 GiB** | Directly tested. Index peak 1.59 GiB RSS; turn peak 1.58 GiB; periodic idle 0.92 GiB. G1 compressed pointers on. This reserves the large majority of unified/system memory for the OS, browser, GPU, and other applications. |
| 32 GiB Apple Silicon or modern x86-64 | 4 GiB | Directly tested. Peak 1.93–1.94 GiB; no performance gain over 2 GiB, but extra burst headroom. Compressed pointers on. |
| 64 GiB workstation/laptop | 8 GiB | Directly tested. Peak 1.85–2.90 GiB. More maximum heap did not speed either workload. Compressed pointers on. |
| 128 GiB development Mac/workstation | 16 GiB | `PrintFlagsFinal` tested the derived maximum and compressed pointers remain on. Workload demand was tested through 8 GiB, so 16 GiB is headroom, not an RSS forecast. No hand override. |

The 16 GiB Windows equivalent is not a high-end workstation: a current
x86-64 laptop with 16 GiB RAM, NVMe storage, and ordinary multicore CPU is the
target. The JVM flags are identical on macOS, Windows, and Linux. Expect wall
time to vary with CPU and filesystem durability costs; do not multiply the M5
times by a RAM ratio. On Windows record both process Working Set and Private
Bytes before changing the policy, because neither is identical to macOS RSS.

At 12.5%, machines below 16 GiB derive less than the proven 2 GiB minimum and
are outside this recommendation. Container limits are treated as available
RAM by HotSpot when container support is active; a 16 GiB host with a smaller
container limit should therefore be evaluated by the same 2 GiB minimum rule,
not assumed supported because the physical host has 16 GiB.

## Limits and next production proof

- The primary cells are single whole-workload observations. Before applying
  the alias change, repeat the G1 2 GiB baseline and periodic-idle case on one
  actual 16 GiB M-series Mac and one 16 GiB Windows x86-64 laptop. Graduation
  is successful indexing, 12 settled turns, no OOM, peak process footprint
  under 2.5 GiB, and idle footprint below 1.5 GiB after one minute.
- The deterministic provider intentionally isolates JVM/database work from
  network variance. Add an SSE-connected drive for the consumer acceptance
  run; the source grounding says each connection is a virtual thread, but the
  matrix did not create thousands of sockets.
- Resolve the current root `:io` executor's cached-platform implementation
  against the virtual-thread architecture before making thread-count claims.
  Collector choice need not wait: both collectors were measured against the
  actual current implementation, and the footprint result is decisive.
- The source-first startup cost is dominated by Clojure namespace loading and
  analysis, not class sharing. Attack that owner if the ten-second-start law
  remains unsettled; do not carry a 133 MiB custom archive that measured as
  noise.

## Web-research follow-up — 2026-08-01

### Source provenance and two corrections

The first pass was not a systematic web-research pass. Its sources were the
vendored dependency and first-party source lines named above, isolated local
measurements, `PrintFlagsFinal`/unified-log output, and the Oracle/OpenJDK
documents linked at the individual flag decisions. The collector and workload
conclusions were measurement-led. This follow-up separately swept the JDK 26
release notes, JEPs and issue tracker; Oracle's JDK 26 operational and migration
guides; Clojure release notes and Ask Clojure; and Apple/OpenJDK platform
material. Community searches found no M-series Clojure/Datahike workload close
enough to substitute for Seon's direct measurements.

Two claims in the first pass need explicit correction:

- Agent graphs did **not** use the process-root cached platform `:io` pool.
  `arm!` called `flow/create-flow` without an executor override
  (`src/seon/cluster/agent.clj:376-380`), so flow resolved `:io` through
  core.async's default (`reference-code/core.async/src/main/clojure/clojure/
  core/async/flow/impl.clj:145-148`), which creates virtual threads on this JDK
  (`impl/dispatch.clj:75-96`). The explicit process-root pair was consumed by
  the work-launcher graph (`src/seon/flow.clj:381-425`). Commit `4ac039c7b`
  subsequently replaced that pair's cached platform `:io` executor with the
  same core.async virtual executor. Thus both the original turn drive and the
  corrected repeats below exercised agent procs on virtual threads. This
  supersedes the current-stack correction and the first bullet under “Limits.”
- The first-pass case runner changed directory to its archived tree only after
  resolving the classpath, and `git archive` did not populate submodules. The
  JVMs and database roots were private, but the exact source classpath came
  from the then-live checkout, not entirely from the named archived commit.
  `freeze-head.sh` now archives initialized submodules and prepares their Java
  classes; `run-case.sh` resolves both classpath and workload from that tree.
  The follow-up measurements below use the corrected frozen tree at
  `4ac039c7bb7bc4f945b423139f171ddd8fc2c015`. The first-pass workload numbers
  remain observations of the actual checkout, but their commit identity must
  not be treated as stronger than that.

### JDK 26 HotSpot changes: adopt only the automatic ones

The primary release source is Oracle's
[JDK 26 release notes](https://www.oracle.com/java/technologies/javase/26-relnote-issues.html).
The relevant changes reinforce G1; they do not add another production flag.

| JDK 26 change | stack relevance and local falsifier | decision |
|---|---|---|
| [JEP 522: lower G1 synchronization](https://openjdk.org/jeps/522) | Seon's indexing burst creates and mutates many reference-bearing tree nodes. The new second card table costs 0.2% of maximum heap: **4 MiB at the directly tested 2 GiB consumer heap**, independent of the host's 128 GiB. Every first-pass G1 number already included the change. | Adopt automatically with JDK 26; no tuning flag. It strengthens the measured G1 choice. |
| Eager G1 reclaim of humongous reference objects | JDK 26 can reclaim eligible reference arrays/objects at any pause. It does not cover primitive `byte[]`, so it should not be credited with reclaiming Konserve's 8 MiB byte payloads. The workload already ran with it. | Adopt automatically; no flag and no revised footprint claim. |
| G1 `UseGCOverheadLimit` | JDK 26 now defaults to OOM after five collections above 98% GC time with less than 2% free heap. The 2 GiB cases never triggered it. | Retain the default. Disabling it could turn a capacity failure into a GC livelock. |
| Smaller ergonomic initial heap | JDK 26 now chooses `MinHeapSize` when `-Xms`/`InitialHeapSize` is absent instead of 1/64 of physical RAM. This directly avoids a large-machine startup commitment. | Adopt automatically. Keep production free of `-Xms`; the probe-only `-Xms16m` remains an explicit controlled baseline. |
| `MaxRAM` deprecation | JDK 26 deprecates `-XX:MaxRAM`; it was used only to simulate a 16 GiB host in `flags.sh`. Direct 2 GiB workload cases, not `MaxRAM`, establish the consumer result. | Never add a production `MaxRAM` cap. Keep the explicit `MaxRAMPercentage=12.5` policy and use `-Xmx` for capacity experiments. |
| Linux-container default heap rises to 75% | [JDK-8356194](https://bugs.openjdk.org/browse/JDK-8356194) changes only the default on detected Linux containers. Seon's explicit 12.5% overrides it. | No flag change. A container needs at least a 16 GiB memory limit to derive the tested 2 GiB heap. |
| G1 idle uncommit | Automatic time-based idle uncommit remains an open enhancement, [JDK-8357445](https://bugs.openjdk.org/browse/JDK-8357445). JDK 26 did not replace periodic GC with an automatic idle policy. | Retain the measured `G1PeriodicGCInterval=30000`. |
| Virtual thread waits for class initialization | JDK 26 now usually unmounts a virtual thread while another thread initializes a class. That removes a startup/first-use starvation edge for flow `:io` tasks. | Adopt automatically; no scheduler or JVM flag. |
| `MemoryPoolMXBean.getTotalGcCpuTime()` | `javap` on this JDK confirms the new default method returning accumulated GC CPU nanoseconds. | Adopt in future measurement tooling if GC CPU attribution is needed; it is not a launch flag. |

The first-pass ZGC result does not change. The JDK 26 release notes contain no
new generational-ZGC footprint policy that falsifies its measured 2 GiB RSS
disadvantage. JEP 516 merely makes the AOT object cache collector-neutral.

### JDK 26 AOT cache: stronger rejection than legacy AppCDS

The first pass measured `ArchiveClassesAtExit`, the legacy AppCDS path. JDK 26
also has the newer object-bearing cache described by
[JEP 516](https://openjdk.org/jeps/516) and the
[JDK 26 `java` command](https://docs.oracle.com/en/java/javase/26/docs/specs/man/java.html).
It is tied to the exact application classpath, JDK release, OS, and CPU.

`aot26.sh` tested that path against the corrected frozen tree:

- The actual source-first classpath trained for 12.128 seconds, then assembly
  refused its non-empty directory entries (`Cannot have non-empty directory in
  paths`). This is a structural failure, not a timing loss.
- Staging every directory as a JAR cost **1.627 seconds**. Training plus
  assembly took **16.305 seconds** and wrote a 163.1 MiB configuration, but the
  Homebrew OpenJDK 26.0.1 assembly child crashed with `SIGSEGV` in
  `InstanceKlass::major_version()` and left a zero-byte cache. The one-step
  parent returned zero despite its child status 134; the probe now validates
  that the archive is non-empty before attempting comparisons.

Reject the JDK 26 AOT cache. It cannot consume Seon's operational classpath,
its best-case packaged path crashed this VM, and even a fixed assembler would
retain the exact-classpath regeneration cost. These are CPU/startup and
artifact-size observations; they do not scale down by the 128/16 GiB RAM
ratio. A 16 GiB machine would receive the same zero usable cache and do the
same class loading, only on a different CPU. Reconsider only after both a
packaged launch artifact and a fixed JDK exist, then repeat on consumer Macs
and Windows rather than projecting M5 wall time.

### Clojure 1.12 on JDK 26

Seon pins Clojure 1.12.5, the current stable release listed by
[Clojure Downloads](https://clojure.org/releases/downloads). Clojure emits
Java 8-compatible bytecode that newer JVMs load. The project officially
supports current Java LTS releases through 25 and says it tries to keep interim
releases working; JDK 26 is therefore compatible-by-testing, not an official
LTS support promise ([installation guidance](https://clojure.org/guides/install_clojure)).
The Clojure 1.12.5 changelog contains no JDK 26 compatibility fix.

Clojure 1.12 had already changed `lazy-seq` and `delay` locking to avoid the
JDK 21 synchronized-pinning behavior
([1.12 release notes](https://clojure.org/news/2024/09/05/clojure-1-12-0)).
JEP 491 removed that synchronized-pinning class in JDK 24, but the Clojure
change remains compatible. Core.async's published virtual-thread design says
`:io` uses virtual threads on JDK 21+ and a cached platform fallback on older
JDKs; alpha3 also stopped retaining virtual-thread references
([core.async announcement](https://clojure.org/news/2026/03/11/async_virtual_threads)).
The pinned `1.10.874-alpha3` source lines above implement that behavior.

The corrected 12-turn cases emitted only the expected incubator Vector API
warning. There were no reflective-call, native-access, or final-field-mutation
warnings. Both JFR recordings contained zero `jdk.FinalFieldMutation` events,
and a separate 12-turn run completed in **4.392 seconds** with
`--illegal-final-field-mutation=deny`. Because Oracle recommends fixing or
replacing a mutating library rather than pre-authorizing it
([JDK 26 final-field guidance](https://docs.oracle.com/en/java/javase/26/migrate/preparing-final-field-mutation-restrictions.html)),
do not add `--enable-final-field-mutation=ALL-UNNAMED`. The default warning is
useful; `deny` belongs in an occasional migration gate, not the consumer
launch block while there is no offender.

Ask Clojure's relevant interop cases point to type hints, qualified Clojure
1.12 method symbols, parameter tags, or explicit coercion when overload
reflection is ambiguous—not a JVM warning-suppression flag
([reflection warning case](https://ask.clojure.org/index.php/14622/refection-warning-even-with-type-hinting),
[overload case](https://ask.clojure.org/index.php/14612/method-with-parameter-called-although-provided-parameter?show=14617)).
No JDK 26-specific Clojure 1.12 incompatibility was found in the Clojure
release, Ask Clojure, or core-library issue searches. That search boundary is
not a claim that future dependency combinations cannot expose one.

Keep `--enable-native-access=ALL-UNNAMED`. Proximum's FFM caller is presently
on Seon's source/class path, so `ALL-UNNAMED` is the launcher's only classpath
granularity. Oracle recommends moving the native caller to the module path for
narrower enablement; that would be a packaging design, not a more precise flag
available to today's classpath. Adding `--illegal-native-access=deny` beside
the existing enablement would not expose another unnamed-module caller, so it
is rejected as false assurance.

### Virtual-thread pinning diagnostic

Do not add `-Djdk.tracePinnedThreads=full`. JEP 491 explicitly removed the
property; setting it has no effect on JDK 26. The successor is JFR's
`jdk.VirtualThreadPinned` event, retained for native/FFM pinning, plus
`jdk.VirtualThreadSubmitFailed`. Oracle's
[JDK 26 virtual-thread guide](https://docs.oracle.com/en/java/javase/26/core/virtual-threads.html)
says both are enabled by the default JFR configuration, with a 20 ms threshold
for pinned events. The same guide documents the virtual-thread scheduler MBean;
`jcmd <pid> Thread.vthread_scheduler` is useful when queued tasks, rather than
pinning, are suspected.

Two corrected frozen-tree 2 GiB G1 turn drives per condition measured the
diagnostic itself:

| condition | 12-turn workload times | mean | peak RSS mean | recording |
|---|---|---:|---:|---:|
| no JFR | 4.259 s, 4.660 s | 4.459 s | 1656 MiB | — |
| JFR `settings=default` | 4.783 s, 4.336 s | 4.560 s | 1749 MiB | 4.59, 4.28 MiB |

The two-run mean is 2.3% slower with JFR. Peak RSS was 92 MiB higher on
average, dominated by one 1876 MiB run; with two observations this is an
overhead bound, not a stable estimate. Both 28-second recordings contained
**zero pinned events, zero submit failures, and zero final-field mutations**.
Because every case fixed `-Xmx2g`, these are direct 16 GiB-target capacity
projections, not 128 GiB-derived heap estimates. CPU overhead still needs
consumer-hardware confirmation.

Adopt `jfr-pinning.sh` as a bounded virtual-thread migration/acceptance probe.
Reject always-on JFR in `:dev`: it writes about 4.4 MiB per short process and
has measurable overhead. For a live JVM, the lower-churn operational form is
`jcmd PID JFR.start name=pinning settings=default`, exercise the flow, then
`jcmd PID JFR.dump name=pinning filename=tmp/jvm-tuning/pinning-PID.jfr` and
`jfr print --events jdk.VirtualThreadPinned,jdk.VirtualThreadSubmitFailed FILE`.

### M-series, Windows, large pages, and containers

Apple Silicon's relevant difference is its normal page size, not a hidden
HotSpot tuning opportunity. `sysctl vm.pagesize hw.pagesize` returned
**16,384 bytes**. `-Xlog:pagesize=info` showed 16 KiB for the G1 heap, both card
tables, mark bitmap, and code heaps. OpenJDK's Apple Silicon port discussion
also records `vm.pagesize: 16384`
([HotSpot runtime thread](https://mail.openjdk.org/pipermail/hotspot-runtime-dev/2021-February/045781.html)).
This size is OS/architecture-derived and does not scale with installed RAM; it
changes mapping-rounding granularity, not the GiB consumer projections.

The shortest local falsifiers reject three tempting footprint flags:

- `-XX:+UseLargePages` warned that large pages are unsupported by this VM.
- `-XX:+UseTransparentHugePages` was unrecognized. Oracle documents THP for
  Linux; JDK 26 fixed its G1 use there, not on macOS.
- `-XX:TrimNativeHeapInterval=30000` warned that native trimming is unsupported.
  The [JDK 26 launcher guide](https://docs.oracle.com/en/java/javase/26/docs/specs/man/java.html)
  limits it to Linux/glibc.

None can enter the cross-platform default. Windows large pages require an
operator privilege and precommit policy, which is the opposite of “polite
consumer footprint”; do not enable them without a separate Windows workload
result. There is no macOS transparent-huge-page analog exposed by this HotSpot
build.

Native macOS has no Linux cgroup/container detector; even the
`os+container` log tag is absent from this build. Docker Desktop runs the JVM
inside its Linux VM, where HotSpot sees the Linux cgroup limit, not macOS
unified memory. The explicit 12.5% policy prevents JDK 26's new 75% Linux
container default from changing Seon, but the existing minimum still applies:
a limit below 16 GiB derives below the tested 2 GiB heap and is unsupported.

No credible community measurement combined current JDK 26, Clojure 1.12,
Datahike/Konserve, and M-series hardware. Generic Java or Clojure startup
anecdotes therefore do not change Seon's measured numbers. The final consumer
gate remains one actual 16 GiB M-series Mac and one 16 GiB modern x86-64
Windows laptop.

### Recommendation delta for review

There is **no default alias flag-block change** from the first-pass
recommendation:

```diff
 :jvm-opts ["--add-modules" "jdk.incubator.vector"
            "--enable-native-access=ALL-UNNAMED"
            "-XX:+UseG1GC"
            "-XX:MaxRAMPercentage=12.5"
            "-XX:G1PeriodicGCInterval=30000"]
+;; no JDK 26 web-sweep additions
```

In particular, do not add `jdk.tracePinnedThreads`, always-on JFR,
`UseLargePages`, `UseTransparentHugePages`, `TrimNativeHeapInterval`,
`MaxRAM`, final-field allowances, or native-access suppression. The only
adoption is the opt-in JFR probe, plus the automatic improvements already in
JDK 26. This follow-up makes no production edit.
