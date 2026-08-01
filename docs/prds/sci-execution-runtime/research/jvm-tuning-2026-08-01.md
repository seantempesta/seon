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
