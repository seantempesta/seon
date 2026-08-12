---
type: research
status: active
tags: [research, runtime]
---

Terminology: this note records evidence from before the rename; the process holding a run is now `:seon.agent.run/process`.

# Measurements, 2026-07-25 — the numbers, their conditions, and how to falsify them

This is the appendix every other 2026-07-25 document cites. It exists because the
same quantity was quoted with three different values in three files, and because
one whole-megabyte reading was misread as "free". Nothing here is an argument.
Each row is a value, the exact conditions it was taken under, where it came from,
and what it does **not** say.

Companion documents (they carry the arguments; this file carries the numbers):

- `redesign-ledger-2026-07-25.md` — R-1..R-18, the physics list.
- `simplification-design-2026-07-25.md` — the target design. Written 09:17; several
  of its numbers predate the evening's measurements and are corrected below.
- `implementation-plan-2026-07-25.md` — waves. Its Wave 0 "re-measure in situ" item
  is discharged by §5.
- `flow-design-2026-07-25.md` — **contains three sentences known false**; it says so
  itself. Do not implement from it. §6.4, §8.1 and §8.4 are the falsifications.
- `flow-prototype-2026-07-25.md` — D1..D16, the adversarial suite. Most numbers here
  originate there.
- `vector-order-audit-2026-07-25.md` — the twelve classified attributes.
- `u1-fuel-calibration-2026-07-23.md` — the only calibration ever performed.

## 0. How to read a row

| marker | meaning |
|---|---|
| **measured** | a number this session produced, with its conditions stated |
| **verified** | read in source at a named file:line, not measured |
| **lower bound** | the true value is at least this; the method could not see more |
| **artifact** | the reading is dominated by measurement resolution, not by the quantity |
| **unverified** | asserted, not confirmed in this session — never quote as settled |

**Rules that must travel with any number lifted out of this file.**

1. **Quote one boot pair with its flags**, never three boot numbers as one fact
   (§2.4). The only before/after pair anyone measured is `10,293 → 3,886 ms` at
   `-Xmx2g` on JDK 26.0.1.
2. **Every memory figure carries its `-Xmx`.** Heap and RSS are functions of the
   heap ceiling and GC timing; a figure without its flag is not a measurement.
3. **`0.53` and `0.73 ms/tx` are two different runs** in the same document (§7.1).
   Name the run.
4. **The `sci/fork` cost was measured against an empty base**, not Seon's real one
   (§3.2). Re-measure before quoting either figure.
5. **Interpreted-path interrupt-fn overhead is ~3x, not 44x** (§5). The 44x row is
   a microbenchmark artifact and is dead.
6. **Nothing here measures an LLM call, a real capability call, or streaming**
   (§14). Every turn-level timing is a lower bound by the cost of a model call.

## 1. Machine, toolchain, and the constant conditions

**verified.** All figures below, unless stated otherwise:

| | |
|---|---|
| JDK | OpenJDK 26.0.1, 2026-04-21, Homebrew build, 64-Bit Server VM, mixed mode, sharing |
| Clojure | 1.12.0 |
| `availableProcessors` | 18 |
| Virtual-thread parallelism | default, except where a probe pins it to 1 |
| SCI | `reference-code/sci` (the pinned fork) |
| Datahike | `reference-code/datahike` (the pinned fork), file stores on local disk |
| Aliases | `-M:writer:host` or `-M:host` as noted |

Verify the JDK with `java -version`. If it is not 26.0.1, **every thread and
allocation number below is suspect** — virtual-thread behaviour and
`ThreadImpl`'s `isVirtual` checks are version-coupled (§4).

---

## 2. Boot

### 2.0 The JVM is not the problem — one dependency is

**measured, 2026-07-25.** Restored after the consolidation pass dropped it: the
conclusion below survived into three documents while the numbers that support
it survived into none.

```bash
time java -version                                  # 0.022 s total
time clojure -M:writer -e '(println "core up")'     # 0.235 s total

```

| step | ms | share of a 9,647 ms source boot |
|---|---:|---:|
| bare JVM start | **22** | 0.2% |
| + `clojure.core` | **235** | 2.4% |
| + `datahike.api` | **6,089** | **63%** |
| + `sci.core` | 442 | 4.6% |
| + `seon.db.writer` | 1,357 | 14% |
| + `seon.host.context` | 930 | 9.6% |
| + everything else | ~320 | 3.3% |
| **total** | **9,647** | 358 namespaces loaded |

Two conclusions, and neither is derivable without the first two rows:

1. **The JVM starts in 22 ms.** Clojure adds 213 ms. Boot cost is not JVM
   startup — Clojure namespace loading *is compilation* (read, macroexpand,
   emit bytecode, load classes), and one dependency plus its transitive
   closure — Datahike with hitchhiker-tree, konserve, core.async,
   superv.async — is **63% of it**.
2. **Seon's own namespaces are 40–155 ms each** (`seon.schema` 42,
   `seon.host.eval` 155, `seon.agent.driver` 122). Adding fifty namespaces
   costs about a second. **This is not a trend the codebase is on; it is a
   dependency it already has** — which is why the fix is AOT plus AppCDS
   against that closure, and not a namespace budget.

### 2.1 The three configurations

**measured, 2026-07-25 evening.** Script `tmp/boot_breakdown.clj` (retained; it is a
plain `clojure.main` script that `require`s each namespace and prints ms/heap/RSS
per step). Three runs, identical script, `-Xmx2g`:

Run **A**, source classpath:

```bash
java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
     --sun-misc-unsafe-memory-access=allow -XX:+UseG1GC -Xmx2g \
     -cp "$(clojure -Spath -M:writer:host)" clojure.main tmp/boot_breakdown.clj

```

Run **B**, AOT jar with the archive disabled — same flags, plus:

```bash
     -Xshare:off -cp tmp/seon-jvm-artifacts/<digest>/seon-jvm-aot.jar

```

Run **C**, AOT jar plus the AppCDS archive — same flags, plus:

```bash
     -Xshare:on \
     -XX:SharedArchiveFile=tmp/seon-jvm-artifacts/<digest>/seon-jvm-aot.jsa \
     -cp tmp/seon-jvm-artifacts/<digest>/seon-jvm-aot.jar

```

| step | A source | B AOT only | C AOT + AppCDS |
|---|---:|---:|---:|
| `datahike.api` | 6,568 ms | 755 ms | **300 ms** |
| `sci.core` | 464 ms | 845 ms | 825 ms |
| `seon.schema` | 42 ms | — | — |
| `seon.db` | 361 ms | — | — |
| `seon.db.writer` | 1,501 ms | — | 723 ms |
| `seon.host.context` | 1,013 ms | — | 900 ms |
| `seon.host.eval` | 161 ms | — | — |
| `seon.agent.driver` | 133 ms | — | — |
| **TOTAL wall since `clojure.core`** | **10,293 ms** | **3,976 ms** | **3,886 ms** |
| final heap | 337 MB | — | 284 MB |
| final RSS | 1,214 MB | — | 1,112 MB |
| namespaces loaded | 358 | 358 | 358 |

Blank cells are steps not separately captured in that run; the totals are complete.

### 2.2 The AOT-versus-AppCDS split — the correction, quantified

**measured.** On `datahike.api` alone, the 6,268 ms saving decomposes:

| contributor | ms | share |
|---|---:|---:|
| AOT (skipping compilation) | 5,813 | **92.7%** |
| AppCDS (caching class loading) | 455 | **7.3%** |

**This settles the correction.** AppCDS caches *class loading*. The 6.1 s is
*compilation* — Clojure namespace loading **is** compilation. Only AOT skips
compilation; AppCDS then caches the resulting classes. It is the **pair**, and AOT
carries almost all of it.

An earlier claim that "AppCDS targets it directly" was an overclaim. **Do not
repeat it.** Anyone proposing an AppCDS-only lane is proposing a 455 ms win.

### 2.3 Where the residual 3,886 ms is

**measured + verified.** 63% of what remains is the three namespaces that are
**not** in the AOT closure: `sci.core` 825 + `seon.host.context` 900 +
`seon.db.writer` 723 = **2,448 ms**.

The closure is writer-only: `resources/seon/dev/writer-aot-namespaces.edn` holds
246 symbols including all 56 `datahike` namespaces, **zero** `sci` and **zero**
`seon.host` namespaces. The blocker is stated in the tree: SCI's host closure is
not AOT-admissible because `copy-vars` asserts during compilation
(`build.clj:105-137`, in particular the comment and copy at `:132-137`).
`jvm-aot-class-dir` is a straight copy of `writer-aot-class-dir`.

**Making SCI AOT-admissible is the next boot lever.** Nothing in this session
attempted it.

### 2.4 The three-boot-numbers problem

**measured, all three honest.** `9,232 ms`, `9,647 ms` and `10,293 ms` all appear
in the corpus. They are the same phenomenon on different runs and flags; none is
wrong and none is a defect **except** that the document set quotes three as if they
were one fact.

Likewise `datahike.api` is recorded at 6,089 / 6,568 / 6,742 ms — an ~8% run-to-run
spread on a two-run isolated probe. The structural claim is unaffected and is the
one to carry: **one dependency is roughly two-thirds of boot, and AOT removes 92.7%
of it.**

### 2.5 `sci.core` got slower in the artifact path

**measured.** 464 ms from the source classpath → 845 ms (`-Xshare:off`) → 825 ms
(archive on). ~380 ms **worse**, and independent of AppCDS. It is source-loaded
from a 53 MB uberjar rather than a directory classpath. That is ~10% of the
remaining 3,886 ms and is unexplained.

### 2.6 The AppCDS archive loads, degraded

**measured.** Every `-Xshare:on` launch prints:

```
[error][cds] Mismatched values for property jdk.module.addmods:
  jdk.incubator.vector specified during runtime but not during dump time
... Disabling optimized module handling

```

`-Xshare:on` did **not** abort, so the archive **is** in use and the 21.9x on
`datahike.api` still lands. What is lost is optimized module handling only. Cause:
dump-time flags (`build.clj:31-35` via `write-writer-cds!` `:173-188`) differ from
launch-time flags (`script/seon/dev/artifact.clj:19-24`). Not fatal; an unexplained
`[error]` line on every boot will be misread as breakage.

### 2.7 Two disjoint boot problems

**verified.** The 271 s fresh-cluster reset (81 s corpus indexing, 46 s
`build-projection` computed twice, 35 s unlogged gap, 16 s paging;
`boot-time-design-2026-07-23.md`) is **pod-side**. The 9.6–10.3 s is **JVM
namespace loading**. AOT+AppCDS does nothing for the 271 s; de-quadratic
`build-projection` does nothing for the 9.6 s. **Two line items, two owners.**

### 2.8 The artifact pair exists and is current

**verified, 2026-07-25.**

| | |
|---|---|
| `tmp/seon-jvm-artifacts/dee7cc11…/seon-jvm-aot.jar` | 53,779,158 B, published 18:25 |
| `tmp/seon-jvm-artifacts/dee7cc11…/seon-jvm-aot.jsa` | 101,990,400 B, published 18:25 |
| jar sha-256 | `a890311312f0cb75c1e907d99e9bbf2b132997c6be146467c6ef1b1b89095410` |
| archive sha-256 | `be77028ed3e5ca531a4df82f9a32efee9a5cd8f767751c5b5b61b1f223dd0c53` |

Both match `tmp/seon-artifact-build/writer.edn` **and** the published manifest
`tmp/seon-operator/artifact.edn` (`:version 14`). `jvm-publication-status` will
therefore report ready.

**The "all four expected digests nil" reports in `simplification-design` (:53-56,
:228-231) and `implementation-plan` (:90) are STALE, not a live defect.** Those
files were written at 09:17 and 14:18; the archive landed at 18:06/18:25 the same
day. Do not carry the nil-digest sentence forward.

**Storage.** `du -sh tmp/seon-jvm-artifacts` = **297 MB** across two digest-keyed
directories built 19 minutes apart (18:06 and 18:25). Nothing prunes them. The
prune idiom already exists at `script/seon/dev/test_artifact.clj:184`.

### 2.9 Not measured, and it matters

- **No live `bin/seon up` proving an `-Xshare:on` argv.** Readiness is proven only
  by arithmetic over the manifest. The most recent process record on disk
  (`tmp/seon-operator-lifecycle/processes/writer.edn`, 2026-07-24) uses
  `target/seon-database-server-standalone.jar` with **no** archive. Per the repo's
  own rule a reset-boundary live proof is a different failure class than any
  fixture can see.
- **No determinism re-check.** Commit `be30f420` *removed* AOT from this artifact
  precisely because two clean builds from identical inputs produced different
  normalized digests (`docs/seon/issues/archive/writer-uber-aot-is-nondeterministic.md`,
  status resolved). AOT is now reintroduced at `build.clj:105-137` and nothing
  re-tests it.

---

## 3. Memory and `sci/fork`

### 3.1 Process memory

**measured, `-Xmx2g`, JDK 26.0.1** (from the §2.1 runs):

| configuration | final heap | final RSS |
|---|---:|---:|
| source classpath | 337 MB | 1,214 MB |
| AOT + AppCDS | 284 MB | 1,112 MB |

The AOT+CDS path is **not** a memory regression: ~53 MB lower heap and ~100 MB
lower RSS at the same `-Xmx`.

A conversation-carried figure of "final heap 256 MB, final RSS 868 MB" records **no
flags** and cannot be reconciled with these. Neither is wrong; a memory number
without its `-Xmx` is not a measurement.

### 3.2 `sci/fork` cost — and the artifact that misled once

**measured**, script retained at
`/private/tmp/claude-501/-Users-sean-src-seon/ad6e7227-…/scratchpad/threadmem.clj`
(session scratchpad; **copy it before quoting it — the directory is session-scoped**).
Method: 10,000 warm forks, `System/gc` + 300 ms settle, 100,000 forks retained in a
`doall`, `System/gc` + settle, heap delta divided by count; timing over a separate
200,000 discarded forks.

| quantity | value |
|---|---:|
| retained heap per fork | **182 bytes** |
| wall per fork | **0.04 µs** |
| base used | `(sci/init {})` — **empty**, not Seon's ~182-namespace base |

**THE ARTIFACT, stated plainly.** `tmp/boot_breakdown.clj` also reports "1000 sci
forks: heap 0 → 0 MB". **That is measurement resolution, not a result.** At 182
bytes/fork, 1,000 forks move the heap ~0.18 MB, which rounds to 0 at whole-MB
resolution. At the earlier-quoted 539 bytes/fork it is ~0.5 MB — still 0 MB at that
resolution. **Forks are cheap. Forks are not free. Never report 0 MB.**

**Provenance conflict, unresolved.** Three documents (`flow-design:79`,
`implementation-plan:23`, `simplification-design:32,224`) quote **539 bytes / 2.1 µs**
with no recorded provenance. My 182 bytes / 0.04 µs is at finer resolution but
against an **empty** base. **Re-measure against Seon's real base before quoting
either.** In the meantime, `simplification-design:224`'s "10,000 live forks = 5.1 MB"
should read **1.8–5.4 MB**.

---

## 4. Threads and allocation measurability

**measured**, `scratchpad/threadmem.clj` (see §3.2 for the path caveat), JDK 26.0.1,
`-Xmx4g`. Creation cost is measured after two warm rounds of the same count.

| quantity | platform | virtual |
|---|---:|---:|
| creation, 2,000 threads | **50.8 µs/thread** | **5.2 µs/thread** |
| RSS per live thread | **47.1 KB** (2,000 live) | **0.60–0.80 KB** (100,000 live) |
| 100,000 sleeping 50 ms, join all | — | **250–420 ms** |

Conversation-carried ranges (platform 29.4–69.9 µs / 38–60 KB; virtual 1.6–4.8 µs /
0.6–5.1 KB; 337 ms for the 100k sleep) are all reproduced within run-to-run variance
**except** virtual creation, which I measured at 5.2 µs against a recorded 1.6–4.8.
Treat virtual creation as **~2–5 µs**, not a point value.

### 4.1 Allocation is unmeasurable on a virtual thread

**measured, three independent times.** `com.sun.management.ThreadMXBean`:

| probe | result |
|---|---|
| `isThreadAllocatedMemorySupported` | `true` |
| platform, `getCurrentThreadAllocatedBytes` before → after | `376 → 2,939,976` / `0 → 4,796,600` / `376 → 6,877,912` |
| **virtual**, same | **`-1 → -1`** |
| watcher thread reading the virtual thread's id | **`-1`** |
| virtual threads in `getAllThreadIds` | **absent** (8 platform ids total) |
| cost of one `getCurrentThreadAllocatedBytes` call | **31.5 ns** |

**verified in JDK source** on this machine, `src.zip`,
`java.management/sun/management/ThreadImpl.java`:

- `:346-350` — `isThreadAllocatedMemoryEnabled() && !Thread.currentThread().isVirtual()`
  (this is `getCurrentThreadAllocatedBytes`);
- `:358-368` — `thread.isVirtual() ? -1L : …` (the by-id form);
- `:131-134` — virtual threads absent from `getAllThreadIds`.

**A citation correction:** `flow-design:71-74` attributes *both* results to
`ThreadImpl.java:347`. That single line covers only the current-thread form.

**Consequence: an allocation limit REQUIRES a platform thread.** There is no
watcher workaround. This is the entire basis of R-18.

**But note what it does and does not buy** — see §6.4: the allocation metric is
anti-correlated with the heap risk it appears to bound. If no allocation *limit* is
authorized, the platform thread buys only the `:seon.eval/allocated-bytes`
**diagnostic**.

### 4.2 `Thread.stop` is gone

**measured.** `Thread.class.getMethod("stop")` throws `NoSuchMethodException` on
JDK 26.0.1. Not deprecated — removed. **A wedged host call is escapable only by
killing the process.** This is physics item 1 and it is confirmed on the pinned JDK.

### 4.3 `parkNanos` — the fairness fix is free on both, and cheaper on platform

**measured**, raw `LockSupport/parkNanos(1000)`:

| | per call |
|---|---:|
| platform thread | **3.6 µs** |
| virtual thread | **11.9 µs** |

Over 3,000,001 SCI fn entries with one park per 65,536 entries (45 parks): virtual
**+0.7 ms**, platform **−7.3 ms** — noise on both.

**This falsifies `simplification-design:205-208`**, which states "~1.4 ms each on a
platform thread — which is exactly why the fixed platform eval pool has to go with
it". Parking is ~390x cheaper than that claim and *cheaper on a platform thread*.
**One of the two written arguments for moving SCI onto virtual threads is void.**

---

## 5. `:interrupt-fn` cost — settled in situ

**measured**, 3,000,001 real SCI fn entries via
`(loop [i 0 a 0] (if (< i 3000000) (recur (inc i) (+ a i)) a))`, JDK 26.0.1,
`reference-code/sci`, `-M:host`, median of 5 after 3 warm runs.

| `:interrupt-fn` shape | median | per fn entry | ratio vs none |
|---|---:|---:|---:|
| none | 24.6 ms | — | 1.00x |
| **agent-fork shape** — `(guard/interrupt-fn holder)`, installed by `context/fork-context` (`src/seon/host/context.clj:1423-1430`; `guard.cljc:49-55, 194-205`) | **73.4 ms** | **16.3 ns** | **2.98x** |
| **base-ctx shape** — `(sci.ctx-store/get-ctx)` → `::guard/holder` → `::guard/check!` (`context.clj:1409-1412`) | 116.4 ms | 30.6 ns | 4.73x |
| target: closed-over `long-array` + one volatile read | 34.5 ms | 3.3 ns | 1.40x |
| `System/nanoTime` on every entry (comparison) | 81.8 ms | — | 2.85x |

### 5.1 The finding nobody had: there are TWO shapes

**verified.** `build-base!` (`context.clj:1409-1412`) installs a closure doing
`sci.ctx-store/get-ctx` plus two keyword lookups per entry. `fork-context`
(`context.clj:1423-1430`) **overwrites** `:interrupt-fn` on every agent fork with a
closure over the holder.

**Agent evals never pay the ctx-store deref.** It is worth **14.3 ns/entry** — 47%
of the base-shape overhead — and it is a dynamic-var deref
(`reference-code/sci/src/sci/ctx_store.cljc:29-36`). Base-ctx evaluation (base
build, portable-slice load) does pay it. **Every prior analysis conflated the two.**
The fix owner is `guard.cljc` **and** `context.clj:1409-1412`.

### 5.2 The 44x is dead

**The `44x` and `0.204 ns/step` rows in
`docs/seon/issues/guard-safepoint-destructures-a-map-on-every-interpreter-step.md`
are a microbenchmark artifact.** That table calls `guard/check!` directly in a
compiled loop, omits `sci.ctx-store/get-ctx`, and its 0.204 ns/step is *below the
cost of a single memory operation* — a JIT-eliminated loop.

The independent `u1-fuel-calibration-2026-07-23.md:64-71` figure of **29.857
ns/check** over one million warmed checks **agrees** with the 30.6 ns base-shape
row. (u1's own first attempt measured 10,960.872 ns/check before the type-hinted
array fast path — quoted here only to show that microbenchmark shape dominates the
result.)

**Quote no ratio above ~3x for the agent path.** `simplification-design:44-52` ("the
input measurements disagree") and `implementation-plan:158` ("do not quote 44x")
were right and are now **discharged**. `implementation-plan` Wave 0's "re-measure in
situ" item is **DONE**.

### 5.3 Citation corrections into `guard.cljc`

**verified.** `src/seon/host/guard.cljc` is **242 lines**.
`simplification-design`'s "install-interrupted!, guard.cljc:250-252" and
"policy-error! hardcodes :agent at guard.cljc:149" cannot be right; the sites are
**:242** and **:150**. `guard.cljc` is also the **only** guard file — there is no
`.clj` twin, so `guard.clj[c]` in an issue note is misleading.

---

## 6. Containment: what actually bounds an eval

### 6.1 Where the `:interrupt-fn` fires

**verified.** Exactly three sites, all in SCI's fn invocation trampoline: once per
fn body entrance and once per `recur`
(`reference-code/sci/src/sci/impl/fns.cljc:52, :77, :166`;
`reference-code/sci/doc/interrupt.md:6-7, :50`). There is no other hook.

**The SCI interpreter contains ZERO counters.** "interpreter-step budget" and "fuel"
named a mechanism the dependency does not have, which is why the default sat at
100,000,000 and was never calibrated. `time-limit` is **defined** at
`interrupt.md:26` (`:32` is its *usage* line — a ratified vocabulary row currently
cites the wrong one).

`interrupt.md:52, :85` also state upstream's own ceiling, which Seon must adopt as
doctrine: the interrupt-fn fires only on interpreted code, and *"For hard guarantees
it is best to run untrusted code in a separate process that can be killed."*

### 6.2 fn entries charged by idiomatic agent code

**measured, 2026-07-25**, counting `:interrupt-fn` invocations directly against a
base built from `sci.interrupt/clojure-core` **and** `sci.interrupt/clojure-string`
— which is what the tree actually merges (`src/seon/host/context.clj:1405-1406`).

| form | fn entries |
|---|---:|
| `(reduce + (map inc (range 1e6)))` | 1,999,999 |
| `(count (filter even? (range 1e6)))` | 1,500,000 |
| `(apply + (repeat 1e6 1))` | 1,000,000 |
| `(sort (vec (range 300000)))` | 300,000 |
| `clojure.string/split` over 200k chars | 300,000 |
| `(doall (map inc (range 100000)))` | 200,000 |
| `(frequencies (range 200000))` | 200,000 |
| `(clojure.string/join "," (range 100000))` | 100,000 |
| `clojure.string/replace` over 200k chars | 100,000 |

**This falsifies the headline of
`docs/seon/issues/core-hof-forms-bypass-the-guard-safepoint-entirely.md`**, which
claims `(reduce + (map inc (range 1000000)))` charges 0 safepoints and that "for
idiomatic agent code there is no in-process bound at all". That issue measured
against a bare ctx merging neither namespace. **Idiomatic agent Clojure IS metered
in this tree.** (The issue was corrected in place today with this table.)

`flow-prototype` D16 reports `clojure.string` at 0 entries. **D16 is
PROTOTYPE-ONLY** — `src-flow-prototype/src/flow/ctx.clj:20` merges `clojure-core`
but not `clojure-string`. Carry D16 forward only as an invariant the new
`seon.sci.ctx` must not regress.

### 6.3 The real hole: one un-overridden host call

**measured.**

```clojure
(alength (byte-array 200000000))
;; => 200,033,752 bytes allocated, 1 ms, 0 fn entries, outcome :ok
;; under BOTH a 500 ms time-limit AND a 64 MB allocation cap

```

`(alength (byte-array 100000000))` likewise charges 0 entries. **No interrupt-fn
cadence can see this, and none ever will** — the entry count is the only clock and
there are no entries. This is the residue that survives §6.2, and it is exactly the
process-boundary case `interrupt.md:85` names.

### 6.4 Proven kills, and the two escapes

**measured** (`flow-prototype` section 1 unless marked):

| case | result |
|---|---|
| `(loop [] (recur))`, `time-limit` 500 ms, cap 4 GB | killed `:time` at **503 ms**, 107,533,312 fn entries, 2,593,128,128 bytes |
| allocating interpreted loop, cap 64 MB | killed `:memory` at **67,324,048 bytes in 21 ms** — 0.32% overshoot from the 1024-entry sample |
| SCI interpreted allocation rate | **~5.2 GB/s, ~48.1 bytes per fn entry** |
| bare spin loop against a 64 MB cap | trips in **29 ms** — 17x sooner than a 500 ms `time-limit` |
| `(try <runaway> (catch Exception e …))` | killed at 1,385,472 fn entries; the marker never reached the user catch clause |
| `StackOverflowError` | contained in **17–26 ms**, permit released, host healthy |
| host interop with `:classes` unset | `(.pow (biginteger 10) 50000000)` and `(java.lang.Thread/sleep 2000)` both fail to analyse |
| permits leaked across ~50 killed/thrown/OOM'd evals | **zero** |

**The uncatchable marker holds.** The 2026-07-25 baseline had only
`Exception`: `Throwable`, `Error`, `RuntimeException`, `StackOverflowError`,
and `:default` all failed with *"Unable to resolve classname"*. The marker
contract is stated in `reference-code/sci/src/sci/interrupt.cljc:32-42`.

**Re-measured 2026-07-26 through `seon.sci.eval/evaluate` after installing the
deliberate broad-root class surface:**

| catch spelling | observed result |
|---|---|
| `Throwable` | caught an `Exception` and returned `:caught` |
| `Error` | caught an `Error` and returned `:caught` |
| `Exception` | caught an `Exception` and returned `:caught` |
| `RuntimeException` | `Unable to resolve classname: RuntimeException` |
| `StackOverflowError` | `Unable to resolve classname: StackOverflowError` |
| `:default` | `Unable to resolve classname: :default` |

Subclasses are intentionally not an open-ended allowlist: portable agent code
uses `Throwable`, while `:default` is a ClojureScript spelling. Most
importantly, `(try (loop [] (recur)) (catch Throwable _ :swallowed))` still
returned the flat `:time` error after 14 ms; the user catch never received
SCI's interrupt marker. A real recursive overflow reported throwable class
`java.lang.StackOverflowError`; before the correction its public
`:seon.error/message` was only the generic evaluation diagnosis, and after the
correction it is `java.lang.StackOverflowError` (30 ms in the confirming run).

**ESCAPE 1 — D8, the 1024-entry sample. measured, reproduced independently.**

```clojure
(let [b (loop [v 2N i 0] (if (< i 18) (recur (* v v) (inc i)) v))]
  (mod (apply * (repeat 300 b)) 7))

```

| | prototype | my reproduction |
|---|---:|---:|
| fn entries | 29–319 | **319** |
| wall against a 500 ms `time-limit` | 9,825 ms (19.6x) | **8,867 ms (17.7x)** |
| bytes against a 64 MB cap | 99,340,558,744 (1,480x) | **99,341,268,032** |
| outcome | `:ok` | **`:ok`** |

The agent received its answer. Related: a 29-entry BigInteger ladder ran 2,338 ms /
15.7 GB / `:ok`; `host/block 900` under a 500 ms limit completed at 905 ms `:ok`.

**THE FIX IS PROVEN, NOT PROJECTED. measured:** reading the time flag as a
**volatile on EVERY fn entry** (allocation still sampled at 1024) killed the same
form **`:time` at 505 ms with 99 fn entries**. It does **not** fix §6.3 and nothing
can.

**ESCAPE 2 — D14, the allocation metric measures the wrong quantity.** It is
cumulative allocation (throughput), not live footprint, so it is **anti-correlated
with the heap risk it appears to bound**:

| case | result |
|---|---|
| kills a harmless program: 20,000 × `(byte-array 1000000)`, all immediately garbage, live footprint ~0 | killed `:memory` at 1,023,124,376 bytes |
| misses a dangerous one: retaining 1,000 × 1 MB under `-Xmx512m` | 473 fn entries, only 473,333,160 bytes allocated, **cap never fired, the JVM OOM'd instead** |
| kills ordinary agent work: `(reduce + (range 500000))` | killed at **20 ms**, 67,188,432 bytes |
| `(reduce + (range 100000))` | survives at 24.8 MB |
| the 99 GB program above | passes |

**Consequence:** at the default cap every interpreted runaway is reported `:memory`
in ~12 ms, which makes *":time with few fn entries = blocked in a host call"* — the
single most diagnostic string in the design — **effectively unreachable**. Removing
the cap as a *limit* restores that message.

**Reframing that follows from the rate:** at 48.1 bytes/entry, a 64 MB cap is a
**work budget of ~1.4M interpreted steps**, not a heap bound.

### 6.5 The configured limits are 5,000x the only calibration

**verified.** `config/system.edn:83-88` — `interpreter-step-budget` 100,000,000
(all three invocation classes), `deadline-ms` 600,000, `output-cap` 1,638,400.

**measured**, `u1-fuel-calibration-2026-07-23.md:33-42`, observed P99.9 (with ten
samples, empirical P99.9 **is** the observed maximum — a lower bound on the true
tail):

| invocation class | samples | P99.9 steps | P99.9 ms | P99.9 output chars |
|---|---:|---:|---:|---:|
| agent eval | 30 | 19,999 | 440.347 | 11,928 |
| authored render | 10 | 751 | 15.043 | 7,531 |
| plan | 10 | 500 | 35.781 | 9,641 |

Every default is at least 100x the observed P99.9; the step budget is ~5,000x. **This
is the concrete case for "time-limit is the only limit."** If the step budget is
deleted, this number must move with it or the reason it was never a real limit is
lost — `u1` is marked `status: complete` and belongs to a superseded unit.

### 6.6 What containment is today

**verified.** Seven mechanisms plus a documented walk-away and one hard concurrency
ceiling:

| mechanism | site |
|---|---|
| step budget + interrupt predicate, one array-backed check | `src/seon/host/guard.cljc:150, :194-205` (file is 242 lines; `install-interrupted!` at `:242`) |
| 2-thread watchdog delivering ONE `Thread.interrupt` | `src/seon/host/invoke.clj:37-44` |
| output cap | `src/seon/host/eval.clj:216-217` |
| wire result byte limit | `src/seon/host/session/leaf.clj:131-156` |
| explicit cancel: waits **2 s** on the Future, then **leaves the thread running** | `src/seon/host/invoke.clj:263-284`, the `.get` at `:281-283` |
| process `destroyForcibly` | `script/seon/dev/process.clj` |
| **fixed 10-thread platform eval pool** | `src/seon/host.clj:61, :332-333` |

Also: `policy-error!` hardcodes `:seon.error/fault :agent` (`guard.cljc:150`), so a
`time-limit` or `output-cap` trip is filed as an agent **coding mistake** rather than
a resource event.

### 6.7 `read-eval` executes agent source before SCI ever sees it

**measured 2026-07-25, reproduced against the production functions under
`-M:writer:host`.**

```clojure
(defn f [] #=(clojure.core/spit "<path>" "…") 1)
;; spit EXECUTED, file written on disk
;; returns (defn f [] nil 1)
;; 0 fn entries, no ctx, no :classes allowlist, no receipt

```

The mechanism is **`clojure.tools.reader`'s OWN `*read-eval*`** — a *different var*
from `clojure.core/*read-eval*` — defaulting to `true`, with `#=` wired as a
dispatch macro (tools.reader 1.5.2 `reader.clj:879-895, :816, :591-595`).
`seon.host.record/read-forms` (`record.clj:36-58`, verified to bind only `*ns*` and
`*alias-map*`), `read-host-form` (`:77-87`), `seon.host.eval` (`:477-481`) and
`seon.host.context/host-form` (`:1033-1040`) all read agent-authored source and bind
nothing.

`read-forms` is gated on `(= :form kind)`, **not on `ok?`** — a form SCI already
**rejected** is handed to tools.reader anyway. **A fix that binds only
`clojure.core/*read-eval*`, or that merely reorders behind `ok?`, does NOT close
it.** Filed: `docs/seon/issues/tools-reader-evaluates-agent-source-at-read-time.md`.

**Correction to `flow-prototype` D7's stated reason.** D7 says sci's reader is
"edamame, which has no `#=` at all". **measured:** `(sci/parse-string ctx "[:x #=(…)]")`
throws *"EvalReader not allowed when `*read-eval*` is false."* Edamame **recognizes**
`#=` and **refuses** it under its own default-false flag. The fix direction is
right; the stated reason is wrong, and a wrong reason invites someone to "restore"
the flag.

---

## 7. Concurrency, the commit path, and where the time actually goes

### 7.1 Write coalescing — and the 0.53 / 0.73 disambiguation

**measured** (`flow-prototype` section 1, Datahike file store, real virtual threads):

| run | figure |
|---|---|
| **run A** — dedicated benchmark, 200 single-datom transactions | serial **45.09 ms/tx**, 200 concurrent **0.53 ms/tx** = **84.6x** |
| **run B** — swept curve, n = 1 / 10 / 50 / 200 concurrent agents | **30.25 / 6.73 / 2.04 / 0.73 ms/tx** |

**Both are in the same document, two lines apart, and they are two different runs.**
Name the run when quoting. The prototype notes the concurrent figure matches a prior
session (0.49) while the *serial* figure is 2x worse on this store path.

**200 was a BENCHMARK SIZE, not a limit.** The curve was still improving at n=200;
**the ceiling was never found.**

**verified — the mechanism.** Datahike's writer is two stages: a strictly serial
processing go-loop threading `db-before → db-after` (`writer.cljc:100-188`, the
`(recur (:db-after res))` at `:181`) feeding a separate commit thread that drains
the queue as one `batch-commit` (`:213`), with `DEFAULT_COMMIT_WAIT_TIME = 0`
(`:83`) and `(<! (timeout commit-wait-time))` at `:266`. **Batch size self-tunes
upward with offered load** — which is precisely why ms/tx kept falling and no
ceiling appeared.

**verified — an unexplored free dial.** `rg 'commit-wait-time|transaction-queue-size'
src/ config/` returns **nothing**. Seon sets neither. Raising `commit-wait-time`
above 0 trades per-transaction latency for larger batches, on the one measured cost
centre.

### 7.2 Where a turn's time goes

**measured.** One turn of 7 steps end to end: **~104 ms/step, of which SCI eval is
0–5 ms**, across **12 transactions per turn**.

**Scope correction, verified 2026-07-25.** That row measures the prototype path,
not the surviving JVM driver's current one-form HTTP path. Source tracing of
`POST /agents/run` finds six transaction boundaries inside the request wall
clock: message, run, turn, execution plan, running eval, and terminal eval plus
lifecycle/reply. The current path has not been measured end to end, so neither
12 transactions nor SCI's ~5% share may be quoted as a current-runtime result.
See §16.4.

**The commit path, not SCI, is the cost centre.** SCI is ~5% of a turn — *before* an
LLM call that dwarfs everything. Every "make the interpreter faster" proposal (JIT,
accretion-as-speed, compiled tiers) optimizes that 5%.

**This turn figure is a LOWER BOUND by the cost of a model call.** No LLM ran (§14).

### 7.3 The semaphore queues and never bounces a claim

**measured.** 22 concurrent evals against 18 permits (config fact, default
`availableProcessors`): 4 queued, **max wait 71 ms**, total 88 ms, every caller
proceeded, **no claim refused**. Same at 8 against 2.

Containment under concurrency: 18 healthy evals `[min median max]` **20/23/25 ms**
alone → **19/25/48 ms** with a `:time` runaway and a `:memory` hog added, both killed
correctly.

100 disjoint agents × 3 steps driven by 100 simultaneous claimants: all 300 `:ok`
receipts, every counter exactly 3. **Every corruption found needed shared state or a
shared run.**

### 7.4 No carrier pinning; the arming site is load-bearing

**measured.** With `jdk.virtualThreadScheduler.parallelism=1` and 8 claimants wedged
inside evals, an unrelated virtual thread completed **5/5 steps in 902 ms**. Both
`Semaphore.acquire` and `Future.get` unmount. Agent code never runs on a virtual
thread, so it has no pinning surface — **a second, independent reason the
platform-thread choice is load-bearing**, one that does not depend on §4.1.

**Implementation trap, measured:** the `:interrupt-fn` must be armed **on the
`:compute` thread**. Arming it on the `:io` caller reported **183 KB** for a run that
allocated **~67 MB**, and misattributed a `:memory` kill as `:time`.

### 7.5 Fork isolation

**measured.** 200 concurrent forks each defining and reading the **same** var name
through the full eval path: **zero cross-fork bleed**. Same for
`defmulti`/`defmethod`, `defprotocol`/`extend-protocol`, and records. SCI built-ins
are read-only from agent code (`alter-var-root` on `clojure.core/inc` → "Built-in var
is read-only"; `intern` → same; `in-ns 'clojure.core` → refused). Host-function vars
in the base cannot be hijacked (they are plain fns, not sci vars).

**The leak class is real and was demonstrated** on a deliberately-unsafe base: fork
A's `swap!` on a base atom is visible to fork B; fork A's `defmethod` on a base
multimethod is dispatched by fork B. The invariant *"base vars hold only functions
and immutable values"* is load-bearing and **nothing enforces it** — it is prose plus
one demo line.

---

## 8. Crash, resume, and durability

### 8.1 Form granularity is FORCED — the load-bearing measurement

**measured.** The **identical form** answered **0** against the turn's opening basis
and **9** against the step's basis (the previous step's transaction report
`:db-after`).

A turn-level transform `(db, agent, message) → [tx-data, messages, effects]` can
only ever return the first answer, so **read-your-own-writes inside a turn is
impossible under that shape** and it was never built. The built shape is
`(db-after of previous step, agent id, step result) → tx-data`. **The turn is a fold
of forms; the resume unit is the form.**

Read-your-own-writes then costs **zero extra round trips** — Datahike's transaction
report already carries `:db-after`. Receipts record `:seon.eval/basis-t` per step and
the chain is strictly increasing.

**This single measurement is the strongest simplification in the design, and no
recurring test claims it.** A future reader who reopens the turn-level transform has
nothing to run.

### 8.2 Crash resume

**measured**, real second JVMs killed with `destroyForcibly`:

| case | result |
|---|---|
| SIGKILL between form 3 and 4 of 7 | child exit 137; survivor's **pure query** returned `total 7`, in-flight `{:index 3 …}`, remaining indices 3–6 with exact sources, receipts `[[0 :ok][1 :ok][2 :ok][3 :running]]`; survivor claimed by CAS and finished 3–6 |
| six kill positions (first form, mid-turn, inside the step commit, last form, final commit, after close) + a double kill | right position every time, no index skipped, all receipts terminal, run closed; double kill converged at **epoch 3** |
| at-least-once cost | **exactly one re-execution per crash** — 8 evals for 7 steps; 9 after a double kill |
| preflight splicing modelled (6 emitted entries → 7 executed forms) | resume answered `total 7` throughout; **a reply re-parse answers 6** |
| commits that returned, then SIGKILL (after commits 7, 63, 211) | survived every time |
| **SIGKILL inside `d/transact`**, 8 kill points, 200-datom transactions | every reopen succeeded, every tag held exactly **0 or 200** entities — **no torn transaction, no corruption** |

### 8.3 D1 — two live JVMs on one file store silently destroy each other

**measured, fatal.** Two live JVMs, one file store: the child's held connection
returned **VISIBLE 0** after the parent committed 99 datoms. **Both processes won
the same epoch CAS**, both reported `my-view-epoch 1`, both drove the identical run.

**Parent: 0 of 40 committed entities survived. Child: 40 of 40. Forty
successfully-returned commits vanished with zero transact errors — and the final
store looked pristine** (epoch 1, receipts `[0..5 :ok]`, counter 6), with no trace
that a second claimant ever existed.

**Every clean crash-resume result in §8.2 was single-writer or strictly sequential
JVMs. None of them is evidence for the multi-writer configuration.**

**verified — both sides of the topology fact.** `create-writer` is a defmulti at
`reference-code/datahike/src/datahike/writer.cljc:282` with `:self` at `:286`
**and** a `:datahike-server` HTTP backend at
`reference-code/datahike/src/datahike/http/writer.clj:35`. So "ships only `:self`"
is **wrong as written**; the correct statement is *"the `:self` backend is
process-local, so one WRITE CONNECTION per store for that backend."*

**verified — and decisive for any remote topology:** Datahike's own `listen` is
declared `:supports-remote? false` (`api/specification.cljc:1073`). A remote-writer
topology loses the dependency's wake path outright. `seon.db.host/listen!`
(`host.clj:306-329`) plus the writer's interest transport is already a
remote-capable interest mechanism and is what the pod uses.

### 8.4 D2 — a stranded run is not recovered, and cannot be by the commit feed

**measured.** SIGKILL the only claimant 600 ms into a chain, start a survivor running
the real `wake!` path, commit nothing. The run went `claimable? true` at t=4 s (lease
3000 ms) and **stayed stranded through t=12 s — four lease periods — with 1 of 7
receipts and `:run/open? true`. Nothing ever scanned.** Committing one unrelated
entity (`{:agent/id "totally-unrelated"}`) completed the entire chain instantly.

**verified — this is STRUCTURAL, not an oversight.** Datahike's `listen` callback
fires *"on each transact"* only (`api/specification.cljc:1076`). A lease going stale
is **not** a commit, so the committed-transaction feed **structurally cannot deliver
it** — and the moment it matters is exactly when the feed goes silent.

**`flow-design:103`'s "No ticker, no polling" is FALSE AS WRITTEN.** This is the
legitimate case for a loud last-resort backstop whose firing is itself a bug report
— but prefer changing the interface so the claim publishes its own liveness.

**verified — the lease is never renewed during a drive.** `beat-tx-data` exists and
is correct (`src/seon/agent/run/core.cljc:160-170`) but is reachable only through
`claim-plan`'s `:held` arm (`:138-142`), i.e. only when a claim is *re-attempted*.
`drive-claim!` (`src/seon/agent/driver.cljc:517-594`) re-reads state between steps
and never beats. With `stale-ms` 1,200,000 (`config/system.edn:619`) a healthy run
driving longer than 20 minutes is stealable.

### 8.5 D5 — the wake stampede

**measured**, as a function of concurrent agents n:

| n | commits per useful run | lost CAS claims |
|---:|---:|---:|
| small | 7.0 | 5 |
| mid | 14.4 | 157 |
| large | **124.8** | **10,343** |
| n=20 | **OOM after 2,555 scans** | — |

Mechanism: `scan!` commits → every commit fires `listen!` → every `listen!` submits
a new `scan!`.

**verified — the fix is a PARAMETER of the existing mechanism.**
`seon.db.host/listen!` already accepts `::protocol/datom-patterns` (e/a/v/added?,
max 64 — `src/seon/db/protocol.cljc:595-601`) and the writer maintains a
`::by-attribute` interest index (`src/seon/db/writer.clj:2860-2878, :2900-2905`).
The claimant passes the **worst** available option,
`:datahike.read/dependency-plan :all` with `(fn [_] (scan!))`
(`src/seon/agent/driver/host.clj:809-814`) — a full open-runs query inline in the
callback for **every cluster commit**.

`redesign-ledger` R-16 and `flow-design:100-103` cite that exact wiring as
already-correct and event-driven. **It is the defect.**

### 8.6 D9 — one poisoned form drains every permit, permanently

**measured.** Permits 4, lease 1500 ms, **one** message to **one** agent whose reply
is `(host/block 600000)`: rounds 0–3 show epoch climbing 1,2,3,4 with permits free
3,2,1,0; rounds 4–5 still claim (epoch 5,6) and park on the semaphore. A healthy
agent's message then produced *"STILL BLOCKED, never started after 6005 ms, permits
free 0"*. **Lost CAS claims: 0** — every re-claim succeeded because the wedged
claimant never contests.

Related, same fix: `(try <runaway> (finally (host/block 3000)))` recorded the kill
but ran **3,056 ms against a 500 ms limit**; with `(host/block 600000)` the eval was
still running after 4 s and the permit count dropped permanently. **"The interrupt
fired" and "the eval finished" are different events**, and `:seon.eval/ms` is the
only place that shows it.

**verified — the tree's version is different and worse in one way.** HEAD has no
semaphore: it has a **fixed 10-thread platform pool** (`src/seon/host.clj:61,
:332-333`) and `cancel-active!` which waits 2 s on the Future and then **walks away
leaving the thread running** (`src/seon/host/invoke.clj:280-283`). Ten wedged evals
exhaust the cluster permanently, with no queue, no permit accounting and no signal.
Better in one way: no lease-steal-into-the-same-step path, so a wedged eval cannot be
re-fed to the next victim.

### 8.7 D11 — `start-run!` is check-then-act

**measured.** `start-run!` spliced two model replies into one 7-step plan `BBBBAAA`
in **3 of 12 trials**; the race window is the model call. The committed ordered step
plan is the proven resume mechanism (§8.2) **and this race must be fixed in the same
design** — gate the insert on a `:db/cas` or write the plan as one cardinality-one
value.

### 8.8 D10 — agent-returned tx-data reaches the database unfiltered

**measured.** A step returned `{:facts [[:db/add "not-an-eid" :nope 1]]}`. The eval
**succeeded**; the poison detonated in the driver's own `d/transact` and the
exception **escaped `drive-run!` entirely**. Run left open, epoch 1, receipt stuck at
`:running`, **no fault recorded anywhere** — outcomes present were only
`#{:running :ok}`. A survivor retried 3 times and threw 3 times: a permanent poison
pill with no attempt counter and no dead-letter path.

Hostile-but-valid facts are worse because nothing complains: one step wrote **424242
into another agent's counter**; another retracted its own run's `:run/open?` and
`drive-run!` carried on because it never re-reads that flag.

### 8.9 HEAD's own receipt identity

**verified.** `:seon.eval/id` is a generated `::db.id/compact-value`
(`src/seon/eval/receipt.cljc:48`), **not** `(run, index)`, and forms hang off
cardinality-many `:seon.agent.turn/evals` (`:69-88`). So at HEAD:

- *"form 3 of 7"* is **unanswerable**;
- a duplicate execution is **indistinguishable** from a legitimate one.

The terminal transition **is** CAS-fenced `:running → :running` (`:90-100`), so
double *recording* is prevented; double *execution* is not. `start!`'s `handles`
atom keyed by run-id (`src/seon/agent/driver/host.clj:773-795`) prevents one
*process* double-driving one run — which is why D3's 704x re-execution is a
prototype-scale result; across processes nothing protects it (that is §8.3).

**D3 and D4 are PROTOTYPE-ONLY defects whose FIXES still stand.** D4's epoch fence
already exists at HEAD (`run-fence`, `src/seon/agent/run/core.cljc:38-47`, riding
every phase/work transaction); the prototype's `drive-run!` simply omitted it. State
the HEAD symptom next to each prototype defect or the cut wave aims at the wrong
thing.

---

## 9. OOM survival — with the caveat that must always travel with it

**measured, twice.**

| case | result |
|---|---|
| `(byte-array 2000000000)` under `-Xmx512m`, co-located Datahike writer | surfaced as a **flat error value**, `:seon.error/raw "Java heap space"`; the **very next `d/transact` committed** |
| retained-1 GB attack, independent reproduction | next eval returned **2** |
| transactor during the attack | **survived, 0 errors, 94 further commits**, store consistent on reopen |
| latency during the attack | one **180 ms** spike against a **33 ms** median |
| `StackOverflowError` | contained, 17–26 ms |
| permits leaked across ~50 killed/thrown/OOM'd evals | **zero** |

**THE SCOPE CAVEAT. What was measured is a REFUSED OVERSIZED ALLOCATION. Sustained
heap exhaustion by retention across many threads was NEVER reproduced.**

Neither flat sentence in the corpus may enter a spec:
`simplification-design:478-486` states as physics that *"an agent OOM restarts the
whole cluster"* — falsified. `flow-prototype:144-149`'s *"OOM does not take the
writer down"* — true only for the narrower case.

**The honest sentence:** *"a single oversized allocation is refused and survivable
(measured); sustained retention is unproven and is bounded by no in-process
metric."* This is evidence **for** the open ruling on co-located heap blast radius,
not a proof of survivability. Note also §6.4: the metric that appears to bound it
missed exactly this case.

---

## 10. Datahike collection shapes

### 10.1 Two cardinalities, no ordered one

**verified.** `(s/def :db.type/cardinality #{:db.cardinality/one :db.cardinality/many})`
— `reference-code/datahike/src/datahike/schema.cljc:59`.

**An ordered cardinality-many attribute is not expressible at all.** This is why the
vector defect could only live in the *declaration* — there was nothing for a bridge
to get wrong. Neither Seon bridge distinguishes `[:vector X]` from `[:set X]`
(`src/seon/db/internal.cljc:135-140`; `src/seon/db/datahike/schema.clj:163`).

### 10.2 Pull order

**measured**, `datahike.core/empty-db` + `datahike.api/with` (no durable write):

| case | input | pulled |
|---|---|---|
| cardinality-many **scalars** | `[9 1 5]` | `[1 5 9]` — **sorted BY VALUE** |
| **component refs**, children allocated first, attached in permutation `[-4 -2 -3]` | tempids `{-2 1, -3 2, -4 3}` | `["two" "three" "four"]` — **ascending ENTITY ID** |

**Component refs are NOT an exception.** Fresh component refs can *appear* to retain
insertion order when entity ids happen to be allocated in encounter order; the
permutation probe falsifies that.

**verified — the mechanism.** Datahike's pull scans the EAVT index for
`[entity attribute]` (`pull_api.cljc:262-274`) and accumulates matching datoms
(`:198-207, :240-243`); the EAVT comparator orders by entity, attribute, value,
transaction (`datom.cljc:325-330`). **A pulled vector is index-order
materialization, not transaction input order.**

`:seon.error/frames` is safe only because its renderers sort by an explicit
`:seon.error.frame/ordinal`.

### 10.3 Tuples are not a general escape

**verified.** Homogeneous tuples **throw** above 8 values — *"Cannot store more than
8 values for homogeneous tuple"* — `reference-code/datahike/src/datahike/db/transaction.cljc:1006-1012`.
Element-level Datalog against a tuple returns nothing; only whole-value match works.

### 10.4 The rule this episode teaches

**Order is never a property of the collection type.** It is a stored ordinal on the
child (`:seon.error.frame/ordinal`), a recovered transaction id (`::status-tx`), or an
explicit sort key (`(juxt :seon.eval/at :db/id)`). No architecture document states
this, which is why `docs/seon/architecture/data-model.md:434, :558, :559` still shows
`[:vector …]` as a *design* shape.

### 10.5 What landed, and what the count really is

**verified.** Commit `5a37489c6` "Correct unordered database collection schemas"
(2026-07-25 18:32, 22 files, +382/−134): nine declarations became `[:set …]`, two
config contexts became cardinality-one component refs. The bridge was touched **only**
in codec/traversal helpers (`internal.cljc:277-283, :328-338, :353-357`;
`db.cljc:915-918`) — **zero lines** of `form->cardinality` or the tuple trigger
changed, confirming the root cause was the declarations.

**"12 wrong declarations" is wrong twice.** Twelve is only what one probe could see
with `seon.config.resolve` loaded. Nine were fixed. **At least THIRTEEN stored
ordered declarations survive**, all in namespaces the probe and the recurring
invariant test cannot load: `my/kb.cljc:109, :111-112`; `my/kb/shared.cljs:19`;
`my/plan.cljc:40`; `agent.cljs:117-118`; `agent/message.cljc:60`;
`render.cljc:180-181`; `eval/receipt.cljc:40-41`; `agent/turn.cljs:100`;
`agent/testrun.cljs:42`; `agent/ctx/transcript.cljc:59, :66`;
`test/runner.cljs:151`.

**Say "nine corrected of a population the probe could not enumerate."** Never restate
12 as the population. The recurring test
(`test/seon/schema_collection_order_test.clj`) requires only `seon.config.resolve`,
so it is **structurally blind** to all thirteen — by the repo's own rule the class is
proven dead only for the third of it that happens to load.

---

## 11. The SCI JIT: no substrate, and a measured ceiling

### 11.1 There is nothing to compile on the JVM

**verified in this checkout**, `reference-code/sci/src/sci/impl/types.cljc`:

| what | line |
|---|---|
| SCI's own comment: *the `:clj` reify and `:cljd` branches DISCARD the form at expansion time* | `:245-247` |
| `->Node` on `:clj` — a bare `reify` that **never references its `ast` argument** | `:264-273` |
| `attach-ast` on `:clj` — the identity, `node` | `:281-288` |
| *the jit and its ast only exist on cljs* | `:290` |

`ls reference-code/sci/src/sci/impl | rg -i jit` → **`jit.cljs` only**. A JVM JIT is
upstream surgery in the pinned fork, not a port.

**CITATION CORRECTION.** `redesign-ledger` R-5/R-9 and `simplification-design` cite
`types.cljc:181-191` and `:195-200`. Lines 181-191 in this checkout are the CLJS
`js-eval-available`/`jit-enabled` block. **The claim is right; the citation is
wrong** — in a corpus whose entire premise is that every claim carries a file:line.

### 11.2 The ceiling, priced

**measured**, a sealed-compile prototype, fib(30):

| tier | wall |
|---|---:|
| compiled, **no** interrupt check | **7.0 ms** |
| compiled, **production** interrupt check | **34.7 ms** |
| interpreted | **70.9 ms** |

**The check costs 4x the compiled body it protects.** Fix the check (§5) before any
compiler. A compile tier is then worth ~9x, not 20x — on the ~5% of a turn that SCI
occupies (§7.2).

---

## 12. Token economics of the corpus surface

**measured** — `redesign-ledger-2026-07-25.md:494-506` (R-15). Values are
`seon.ai.tokens/estimate`.

| | cost |
|---|---:|
| one compact card | median **41** est. tokens (mean 45.7, p90 63, max 257) |
| whole current public schema-complete surface, 474 rows | **21,659** est. tokens |
| one `:seon.ns/summary` catalog line | median **20** est. tokens |
| catalog for all 206 namespaces | **4,078** est. tokens |

Break points: cards break first at **~500 functions**; the catalog survives to
**~5,000 namespaces**; **search precision fails first at ~5,000 functions — and both
searches are off**.

**NO REPRODUCTION COMMAND IS RECORDED with these figures.** They are the sizing input
for every advertising and corpus-surface decision, and they cannot currently be
re-derived from the document. **Treat the break points as estimates, not
measurements.**

Related, **verified**: an agent's reachable surface without discovery is **five**
namespaces (`src/seon/agent/home.cljc:95-112`), because three discovery paths are
dead on the claimant JVM — `seon.embed/enabled?` installed as `(constantly false)`
with `search-pull` a fixed `:user-input` error (`context.clj:729-739`); `grep-graph`
CLJS-only (`search.cljs:303`) and not among the 24 host `::lib` registrations;
`my.ns` excluded by **database** markers (`(await` at `my/ns.cljs:71`, `db/query`
`:76`, `db/pull` `:96`) — **whose reason is void on the JVM, where `db/query` is
synchronous**.

---

## 13. Size arithmetic

**measured** (`flow-prototype:596-614`):

| | lines |
|---|---:|
| prototype built | 767 |
| net core | 284 |
| **projected with the D1–D16 fixes** | **450–550** |
| replaced, across 14 files | **~6,994** |
| **ratio** | **13–15x** |

**Quote 450–550, never 284** — 284 excludes the fixes that make it correct.

**`flow-design:55-56`'s "Four, roughly 250 lines total. Everything they replace is
~10,000+" is wrong on BOTH numbers and must not enter any spec.** The ratio is not
40x.

**Two subtractions that must travel with the ratio or it becomes a lie:**

- `host/context.clj` (2,181) + `agent/ctx.cljc` (1,959) = **4,140 lines are a PORT,
  not a deletion** (R-8c). They move tiers; they do not disappear.
- **~3,650 wire lines SURVIVE for web-render** (R-8b). Not in the replaced count;
  never counted as saved.

**Coverage arithmetic. verified:** 149 files in `docs/seon/issues/`, ~146 real
issues, 133 listed in `index.md`. `src-flow-prototype/`'s D1–D16 are claimed by
**zero** runners (`bin/test-cljs`, `bin/test-writer`, `bin/seon test operator` all
miss it), so **by the repo's own rule the entire adversarial suite currently counts
as NOT COVERED** — including the §8.1 basis measurement. ~40 `store-a*` directories
are checked into the working tree.

---

## 14. What was NOT measured

Be suspicious of anything not above. Specifically:

- **No LLM. No real capability calls.** No fs, web, shell, or agent-authored `db`
  write passed through `:interrupt-fn`. No `:mixed` workload. No streaming. Every "reply"
  was a pure function; every eval was CPU-only or a deliberate host block.
  **Every turn-level timing is therefore a LOWER BOUND by the cost of a model call.**
- **No live `bin/seon up` with `-Xshare:on`** (§2.9).
- **No AOT determinism re-check** (§2.9), against an archived issue that documents
  the exact failure being reintroduced.
- **No sustained heap exhaustion by retention across many threads** (§9).
- **Lease renewal while parked on the semaphore is not implemented.** Renewal happens
  only at step boundaries; max observed wait was 71 ms against a 60 s lease, so it
  never fired here. A convoy of long evals makes a healthy queued run stealable.
- **R-8a was not falsified.** `seon.host.instrument`'s global fair
  `ReentrantReadWriteLock` was not exercised.
- **`sci/fork` against Seon's real base** (§3.2).
- **The real-agent concurrency ceiling.** The direct transaction ceiling is now
  measured in §16, but disposable-cluster lifecycle breaks before a real agent
  turn can start.
- **Token-economics reproduction** (§12).

## 15. Unverified

Marked here so nothing lifts them as settled.

- **The accretion / Spec-ulation attribution.** *"accretion"*, *"breakage"*, and the
  rule *"require no more, provide no less"* attributed to Rich Hickey's
  *Spec-ulation* (Clojure/conj 2016) is **UNVERIFIED**. The lane assigned to confirm
  it failed; this run did not verify it either — no vendored source under
  `reference-code/` contains the talk and no external material was fetched. **The
  words may be used; the citation may not, until confirmed against a primary
  source.** The attribution is **already asserted as fact** in the committed tree at
  `docs/seon/issues/output-map-closedness-decides-accretion-legality.md:15-16`,
  which defeats the design set's own UNVERIFIED marker unless that line is marked.
- **`:seon.agent.run/process` as the replacement for the coinage "cluster JVM"** —
  proposed, not ratified. Grounding is real on both sides (`rg -i cluster JVM
  reference-code/datahike/ -l` → **0 files**, against `rg -c cluster JVM src/` → **25
  files**; `script/seon/dev/process.clj:95, :910, :918, :929-938` carries the process
  record with `(pid, start-instant)` and generation, matching JDK `ProcessHandle`).
  The rename itself is an owner ruling, not a measurement.
- **539 bytes / 2.1 µs per `sci/fork`** — quoted in three documents with **no
  recorded provenance** (§3.2).
- **The `0.53` vs `0.73 ms/tx` split** — attributed here to two different runs on the
  strength of the surrounding text; the prototype was **not re-run** to confirm it
  (§7.1).
- **`:seon.ns/owner`** — does not exist in source. Registered `:seon.ns` attributes
  are name, source, doc, summary, require-edges (`src/seon/ns/source.cljc:17,
  :19-37, :45, :46`). Any design naming it is proposing a **new** attribute, not a
  repoint.

---

## 16. Load and saturation

### 16.0 Conditions and method

**Measured, 2026-07-26 00:10–00:29 EDT, during the 2026-07-25 work period.**
These conditions travel with every figure in §16:

| condition | value |
|---|---|
| machine | MacBook Pro `Mac17,6`, Apple M5 Max, 18 cores, 128 GB RAM |
| operating system | macOS 26.5.2, build `25F84` |
| local store | APFS `/dev/disk3s5`, 1.8 TiB total, 838 GiB free |
| JVM | Homebrew OpenJDK 26.0.1, G1, `-Xmx4g`, NMT summary, no AOT/AppCDS |
| Clojure | 1.12.5 |
| Datahike | `caf526850084` |
| SCI | `8fac6e88f32d` |
| Seon starting revision | `742a11f73ca5bef7d0754c55be9409e6400ea0fd` |
| other work | default and lifecycle Seon processes, watchers, desktop apps; the machine was not a sole-tenant benchmark host |

The committed `bench/writer_throughput.clj` `saturation` mode drives direct
Datahike `d/transact!` calls. Each point creates a fresh file database with
`:schema-flexibility :write`, `:keep-history? true`, and
`:fuse-index-roots? true`; installs one cardinality-one long attribute; starts
the stated number of virtual-thread workers behind one latch; and assigns a
fixed total number of single-datom transactions from an atomic ordinal. It
records wall time, individual promise latency, transaction-report commit IDs,
process CPU, heap delta, GC, and platform-thread counts. A 64-worker,
256-transaction warm-up precedes each process.

The fixed-work property matters: curves with different transaction totals are
reported separately below and **must not be spliced**. This is a direct
Datahike file-store benchmark, not a `seon.db` protocol benchmark and not an
agent turn.

The reportable invocations were:

```bash
U3_BENCH_MODE=saturation \
U3_BENCH_CONCURRENCIES=200,400,800,1600,3200,6400,8192,12800,16384 \
U3_BENCH_TRANSACTIONS=16384 \
clojure -J-Xmx4g -J-XX:NativeMemoryTracking=summary \
  -M:writer:host:writer-test \
  -i bench/writer_throughput.clj \
  -e '(bench.writer-throughput/-main)'

U3_BENCH_MODE=saturation \
U3_BENCH_CONCURRENCIES=65536,131072 \
U3_BENCH_TRANSACTIONS=131072 \
clojure -J-Xmx4g -J-XX:NativeMemoryTracking=summary \
  -M:writer:host:writer-test \
  -i bench/writer_throughput.clj \
  -e '(bench.writer-throughput/-main)'

```

### 16.1 The curve, knee, and ceiling

**Measured, fixed work = 16,384 transactions per fresh store:**

| concurrent callers | wall ms | tx/s | p50 ms | p95 ms | commits |
|---:|---:|---:|---:|---:|---:|
| 200 | 84,579.18 | 193.71 | 906.87 | 2,211.52 | 161 |
| 400 | 46,880.58 | 349.48 | 1,070.15 | 2,286.48 | 79 |
| 800 | 25,516.27 | 642.10 | 1,048.71 | 2,418.68 | 40 |
| 1,600 | 13,065.78 | 1,253.96 | 1,015.80 | 2,359.11 | 21 |
| 3,200 | 7,541.25 | 2,172.58 | 858.02 | 2,358.07 | 11 |
| 6,400 | 3,823.84 | 4,284.70 | 754.24 | 2,532.48 | 6 |
| 8,192 | 2,908.19 | 5,633.75 | 933.84 | 2,300.62 | 5 |
| 12,800 | 3,182.00 | 5,148.97 | 1,819.77 | 2,658.63 | 5 |
| 16,384 | 1,938.93 | 8,450.04 | 1,891.57 | 1,894.69 | 4 |

The non-monotonic 12,800/16,384 pair is real run noise plus a batch-boundary
change, not a defensible knee. At 16,384 callers the curve was still improving
and the entire workload had collapsed into four commits.

The high-concurrency extension used more fixed work so each caller remained
meaningful:

| fixed transactions | concurrent callers | wall ms | tx/s | p50 ms | p95 ms | commits | peak platform threads | GC ms |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 131,072 | 65,536 | 30,225.99 | **4,336.40** | 10,439.55 | 19,663.02 | 9 | 1,995 | 1,073 |
| 131,072 | 131,072 | 36,190.02 | **3,621.77** | 17,673.50 | 35,365.91 | 6 | 2,598 | 1,063 |

**The measured knee is 65,536 concurrent callers under this 131,072-transaction
workload.** Doubling concurrency to 131,072 made throughput **16.48% worse**,
wall time **19.73% worse**, and p50 latency **69.29% worse**, even though
coalescing reduced nine commits to six. The last successful point was 131,072
callers; it is a degraded point, not a recommended operating capacity.

The terminal escalation held concurrency at 131,072 and doubled fixed work to
262,144 transactions. It did not finish within the harness's 120-second
backstop and then failed with `OutOfMemoryError: Java heap space`. Immediately
before failure Datahike reported its 120,000-entry transaction queue above 90%
(observed count 117,553) and its commit queue above 50% (observed count
60,006). Therefore concurrency alone is not a sufficient capacity coordinate:
131,072 callers completed 131,072 transactions, but the same concurrency could
not sustain 262,144 transactions on `-Xmx4g`.

### 16.2 The named resources

There are two walls at different offered-load regimes.

**Measured — moderate concurrency is durable-file synchronization bound.**
A 60-second JFR interval during the 200-caller fixed-work escalation recorded
38,580 `jdk.FileForce` events, all metadata forces in Konserve's file-store
path. Their aggregate duration was 347.32 seconds because eight
`async-dispatch` threads overlapped them; per force, p50 was 8.14 ms, p95
17.95 ms, and mean 9.00 ms. JVM CPU averaged 1.01% user and 5.20% system while
machine CPU averaged 26.78%. Eight collections had a longest pause of
15.4 ms. No `jdk.VirtualThreadPinned` event occurred.

The resource is **local APFS metadata synchronization (`FileChannel.force` /
fsync through Konserve)**. It is not the one-core Datahike processing loop,
heap, GC, or carrier threads at this part of the curve. Higher offered
concurrency improves throughput by amortizing those forces across ever-larger
commit batches.

**Measured — the terminal wall is queued transaction state in the Java heap.**
Datahike fixes both queue capacities at 120,000
(`reference-code/datahike/src/datahike/writer.cljc:78`) and emits pressure at
90% of the transaction queue (`:104`) and 50% of the commit queue (`:174`).
During the failed 131,072-caller/262,144-transaction run:

- the `-Xmx4g` heap reached 4.0 GiB and stayed there;
- JFR measured peak RSS at **5.391 GB / 5.021 GiB**;
- 699 GC events were recorded, including 323 full collections; their event
  durations sum to 209.48 seconds but overlap, so that sum is **not** a
  wall-clock share;
- the longest recorded GC event was 2,028.98 ms;
- Java thread count peaked at 3,399;
- JVM CPU averaged 24.39% user plus 15.14% system, while the non-isolated
  machine averaged 57.40% total; and
- no virtual-thread pinning event occurred.

The named failure is **heap exhaustion from retained transaction/commit-queue
state**, with GC thrash as the terminal symptom. The evidence falsifies
commit-loop CPU and carrier-thread saturation as the first cause. Disk forces
remain the ordinary cost centre below the queue/heap wall.

### 16.3 Real-agent load: the infrastructure broke first

**Measured blocker, not an agent-throughput number.** The supported
disposable-cluster path broke before `N=1`: `bin/seon cluster open <name>`
reconciles only the target pod, not a target JVM driver or web-render process
(`script/seon/dev/cluster.clj:233-258,318-333`). A provider request in that
state would exercise the default cluster's process, not the throwaway cluster
the measurement was required to isolate.

The safely exercised real-agent count was therefore **zero**. This is not a
claim that the system's agent capacity is zero; it is the ceiling reached
before the first required runtime resource existed. The resource that broke
first was **named-cluster lifecycle composition**. No paid model call was made,
which obeys cheapest-probe-first rather than spending money on invalid
topology. The blocker and acceptance are recorded in
`docs/seon/issues/named-cluster-open-does-not-reconcile-jvm-host.md`.

### 16.4 End-to-end turn attribution

**Unmeasured.** There is no honest current-runtime end-to-end turn breakdown.
The disposable JVM driver did not start (§16.3), and the surviving driver
persists only `:seon.eval/duration-ms`. It does not persist model-call duration,
per-transaction duration, context-derivation duration, or publish duration.
The current HTTP path passes the request message directly as eval context and
combines completion with reply publication in the terminal transaction, so
“context” and “publish” are not separately observable phases there.

Source tracing verifies six transaction boundaries for one current form, but
it does not measure their duration. Subtracting SCI time from request wall
would mix model, queue wait, transactions, and unobserved driver work. Thus the
§7.2 historical statement “SCI is ~5% of a turn” remains a prototype lower
bound and is **not confirmed for the new runtime**. The missing waterfall is
owned by
`docs/seon/issues/agent-turns-lack-database-read-cost-attribution.md`.

### 16.5 Memory and idle agents

**Real-agent RSS and heap curves are unmeasured** for the same disposable-driver
blocker. Source inspection verifies that an idle agent is database facts: agent
creation allocates no per-agent thread, and the surviving one-message path uses
transient virtual threads and transient SCI forks. It does not retain the
target architecture's one context per agent. Therefore “idle agents cost zero
threads” is source-verified for the current implementation, but no RSS/heap
number is claimed and the current implementation cannot prove the target
retained-context memory model.

The transaction-load memory figures in §16.1–16.2 are not substitutes for
agent memory. In particular, per-point heap deltas moved in both directions
with G1 timing and are not retained-footprint measurements.

### 16.6 What this section did not measure

- No real agent turn, model call, paid token, capability call, streaming reply,
  context derivation, or publish phase.
- No agent-count RSS/heap curve and no retained SCI context per agent.
- No isolated sole-tenant disk bandwidth or alternate disk/filesystem.
- No `seon.db` protocol, replica, reactive feed, or browser path.
- No heap ceiling other than `-Xmx4g`.
- No safe operating limit below the knee; the run found a failure envelope,
  not a production capacity policy.

The direct transaction load clause now has a number, a knee, a degraded point,
and named resources. The real-agent and end-to-end performance clauses remain
open on the two explicit blockers above.

## 17. Named-cluster real-agent load and turn waterfall

### 17.0 The earlier blocker is superseded, not erased

**Measured and fixed, 2026-07-26 01:00–02:15 EDT.** Sections 16.3–16.6
accurately describe revision `4ea5d22fb`, but their zero-agent result is no
longer current. This section records the disagreement explicitly: after the
named-cluster lifecycle fix, the same disposable-cluster path reached ready,
drove real DeepSeek turns, and completed all requested `1, 5, 10, 25`
concurrency rungs.

The pre-fix cause was exact:

- `seon.dev.cluster/ensure-under-lock!` selected only `pod-id`
  (`script/seon/dev/cluster.clj` at revision `4ea5d22fb`, lines 233–241);
- close/restart targeted only the pod (`:318–350`);
- the complete host/pod/web-render graph already existed in
  `seon.dev.process/owned-process-graph`
  (`script/seon/dev/process.clj`, lines 221–250), so cluster lifecycle was
  bypassing its own authority;
- process records used the target-private directory only for an externally
  written pod, causing named host/web reads to alias the source operator
  (`script/seon/dev/process.clj`, lines 321–328);
- the web-render port used the source process directory (`:216–219`); and
- host/web specs passed the source database/socket coordinates rather than the
  selected launch descriptor (`:706–779`).

`051825d92` makes open/close/restart reconcile the complete derived target
graph and gives every target-owned process private records/endpoints.
`2c885f754` supplies the selected database backend/path and fixes apply-time
writer ownership. `037e285e2` makes named reset coordinate-safe.

The live named target then reported:

| member | workload PID | private evidence |
|---|---:|---|
| host / JVM driver | `15335` | `logs/clusters/agentload0726/host/…`, target host socket and database path |
| pod | `15367` | target HTTP endpoint `http://127.0.0.1:55729` |
| web-render | `15402` | target-private port file and log |

The database was
`/Users/sean/src/seon/data/clusters/agentload0726/db`; target status was
`:seon.dev.target.status/ready`.

**Remaining topology disagreement.** The target owns the JVM driver but still
uses default writer PID `14973` through
`tmp/seon-cluster-default-req.sock`. This was the implementation's deliberate
“shared writer” topology, but it contradicts the later O9 one-writer-per-store
isolation ruling. It did not invalidate database-path isolation for this
measurement, but process failure and queue/heap isolation are not yet true.
[[../../../seon/issues/named-clusters-share-one-writer-process]] owns that
separate blocker.

### 17.1 Conditions and method

Every number in §§17.2–17.6 has these conditions unless a table overrides one:

| condition | value |
|---|---|
| machine | MacBook Pro `Mac17,6`, Apple M5 Max, 18 cores, 128 GiB RAM |
| operating system | macOS 26.5.2, build `25F84` |
| JVM | Homebrew OpenJDK 26.0.1 arm64, G1, `-Xmx4096m` |
| JVM launch flags | `--add-modules jdk.incubator.vector`, `--enable-native-access=ALL-UNNAMED`, `--sun-misc-unsafe-memory-access=allow`, `-XX:+UseG1GC`, `-Xshare:on`, AppCDS archive |
| Seon release | application digest `596b6c1d43bd76cbf925ea288bc402d3c393cdab9fc9bc06e3309c0e91a3ca0a`; commits through `ad33c2268` |
| cluster | fresh reset/apply/open of `agentload0726`; host workload PID `15335`; file database; shared default writer PID `14973` |
| model | DeepSeek `deepseek-v4-flash`, thinking disabled, non-streaming, `max_tokens=64`, temperature `0.7`, zero configured retries |
| request | one raw instruction asking for exactly `(seon.agent.lifecycle/complete "<RUNG>_OK")`; request timeout 120 s, model-attempt timeout 30 s |
| concurrency | calls launched within 1.577 ms (`N=5`), 11.490 ms (`N=10`), and 34.762 ms (`N=25`) |
| other work | complete default Seon system, watcher, shared writer, named host/pod/web-render, unrelated Shadow build artifacts, and desktop apps; not sole tenant |

`bench/agent_turn_load.sh` is the reproducible HTTP rung driver.
`bench/jvm_memory_snapshot.sh` records JVM/OS conditions, forces a full GC, and
captures whole-KiB heap/RSS/thread evidence. Response JSON and the database
timeline used here were retained under `tmp/load-saturation/`.

The requested rungs made **41 confirmed paid model calls**. Three additional
completed preflight calls were required while repairing the truthful
`/agents/run` evidence projection, for **44 confirmed completed calls** in
this cluster; one earlier interrupted pre-fix request may have reached provider
dispatch, but that is unverified. Token usage was not persisted, so exact
dollar cost is **unmeasured**. The output cap and tiny prompt bounded spend,
but no cost is inferred without usage facts.

### 17.2 One real turn proves the named driver and database

The `N=1` request reused agent `red-windows-pump`. It returned HTTP 200 with
reply `RUNG1_OK`, one turn, and one eval after 2,409 ms inside the handler
(2,575.833 ms full HTTP wall).

Its named-database evidence was:

```text
run       m5bng2aq847g
turn      q88c21uqtcee
eval      ["m5bng2aq847g" 0 1]
source    (seon.agent.lifecycle/complete "RUNG1_OK")
status    :done, :seon.eval/ok? true, :seon.eval/duration-ms 3
reply     RUNG1_OK at 2026-07-26T05:53:08.902Z

```

A history query showed
`:seon.agent.run/process "host-15335"` asserted at basis transaction
`536871033` and retracted in the terminal transaction `536871036`. That PID
was the named host workload PID, not the default host. The current run pull
showed `:closed-reason :completed` and result `RUNG1_OK`; the transcript query
returned the same reply. The database value after the response carried basis
transaction `536871036` and commit ID
`6a65a0c5-c022-5fa3-9e9c-56227485e0e1`.

This closes the old “probe broke before `N=1`” result. It does not prove
per-store writer-process isolation; §17.0 names that remaining difference.

### 17.3 Requested concurrency climb

Every request returned HTTP 200, exactly one successful eval receipt, exactly
one turn, and the exact rung reply:

| concurrent real agents | successes | handler elapsed min / median / max | full HTTP wall min / max | first observed defect |
|---:|---:|---:|---:|---|
| 1 | 1/1 | 2,409 / 2,409 / 2,409 ms | 2.576 / 2.576 s | none |
| 5 | 5/5 | 3,508 / 4,065 / 4,402 ms | 4.023 / 4.638 s | 12 duplicate run-open CAS losses |
| 10 | 10/10 | 5,306 / 6,617 / 7,097 ms | 6.211 / 7.537 s | 29 duplicate run-open CAS losses |
| 25 | 25/25 | 6,724 / 16,773 / 18,331 ms | 8.493 / 19.341 s | 62 duplicate run-open CAS losses |

**The first thing that broke was same-process run admission at `N=5`.**
`seon.agent.driver/start!` starts a virtual thread for every row returned by
each interest-triggered `pending-messages` scan. `scanning?` prevents two scan
enumerations at once, but no in-flight message set spans scans. Before the
winning run-cause transaction becomes visible, a new scan starts more threads
for the same message. Datahike's agent-run CAS correctly lets one win; the
others consume the writer queue and log errors.

The resource is **duplicate run-open transactions against the shared writer**,
not provider capacity, SCI, carrier threads, or a user-visible timeout. The
defect grew 12 → 29 → 62 losing transactions across the three concurrent
rungs. [[../../../seon/issues/agent-driver-scans-duplicate-run-open-attempts]]
owns the fix.

No hard success ceiling was reached through the requested maximum of 25, and
no request was bought above that number merely to obtain a rounder ceiling.
The usable ceiling established here is therefore: **25/25 succeeded, with the
first scaling defect already present at 5**. This is a load-to-first-break
result, not a claim that capacity is 25.

### 17.4 One-turn end-to-end waterfall

**Historical broken-turn measurement — do not use as the corrected
baseline.** Section 18 proves that this turn's plan transaction failed and the
driver ignored the error. The numbers remain here to preserve the
disagreement.

The `N=1` turn has enough transaction timestamps to bound the real wall.
The handler timer began at the inbound message's logical time
`05:53:06.897Z`; its 2,409 ms total ends at approximately
`05:53:09.306Z`.

| interval | wall | classification | what it includes |
|---|---:|---|---|
| handler start → inbound-message transaction `536871032` | 188 ms | measured | message construction, protocol transaction, commit |
| message commit → run-open transaction `536871033` | 291 ms | measured | interest delivery, scan/thread scheduling, run allocation, CAS commit |
| run commit → turn-open transaction `536871034` | 141 ms | measured | turn allocation and commit |
| turn commit → eval-admission transaction `536871035` | 1,398 ms | measured model envelope; model duration upper bound | config/agent pulls, request construction, DeepSeek call, response parse, and the failed plan transaction |
| eval admission → terminal/reply transaction `536871036` | 128 ms | measured | SCI eval plus terminal commit and reply publication |
| final commit → handler result complete | 263 ms | derived from measured endpoints | final database reacquisition and response projection |
| outside handler timer | 166.833 ms | measured | remaining HTTP client/server request and response wall |

The six inside-handler intervals sum exactly to 2,409 ms. The real provider
call is **not separately timestamped**: 1,398 ms is an honest upper bound and
envelope, not a fabricated model duration. It is 58.0% of handler wall.

SCI recorded **3 ms**, or **0.1245%** of handler wall. Thus the historical
prototype statement “SCI is ~5% of a turn” does **not** survive this minimal
real-model turn. At `N=25`, all 25 eval durations recorded `0 ms`; that is
millisecond-resolution truncation, not zero work and not a better SCI result.

The current driver does not derive the full agent context for this path.
`model-request` pulls model configuration and the agent row, then sends the raw
message with a static system instruction. **Context derivation is absent, not
measured as zero.** Reply publication shares terminal transaction
`536871036`, so its standalone duration is also unobservable. Commit-adjacent
intervals include scheduling and protocol work and must not be relabeled as
pure Datahike commit time.

The expected “model dwarfs everything else” shape is only partly true: the
model envelope is the largest interval, but at most 58.0% of handler wall;
the remaining non-SCI path is about 41.9%. “Kick-ass fast” therefore has a
real conditioned waterfall now, but its performance claim is **falsified, not
graduated**.

### 17.5 A silent missing transaction changes the waterfall

Source tracing predicted an execution-plan transaction between the model reply
and eval admission. It did not commit.

The fresh database had none of the six
`:seon.agent.run/plan-*` / `:seon.agent.run.form/*` attributes registered by
`seon.agent.driver`. `process-message!` discards the returned error value from
that transaction and evaluates anyway (`src/seon/agent/driver.clj:461-471`).
The `N=1` run had no plan attributes, and every rung advanced the basis by
exactly five successful transactions per completed turn. At `N=25`, basis
transaction `536871111` became `536871236`: exactly 125 successful
transactions.

This is not a timing optimization. It is missing durable plan evidence and a
silently ignored database error. The failed call's wall is included inside the
1,398 ms model envelope. The correctness blocker is
[[../../../seon/issues/jvm-driver-ignores-plan-transaction-errors]].

### 17.6 Idle-agent and load memory

**Measured idle curve.** Before the final helper-only artifact rebuild, named
host PID `89573` ran the same OpenJDK 26.0.1, G1, `-Xmx4096m`, AppCDS flags,
cluster database, default shared writer, and non-sole-tenant machine described
in §17.1. Host source was unchanged by the later rebuild. After each agent
count was committed, `jcmd GC.run` completed before `GC.heap_info`, RSS, and
`ps -M` were read:

| total database agents | used heap KiB | committed heap KiB | RSS KiB | OS thread rows excluding header |
|---:|---:|---:|---:|---:|
| 2 seeded | 59,679 | 253,952 | 1,005,520 | 62 |
| 3 | 59,679 | 233,472 | 607,888 | 62 |
| 7 | 59,679 | 225,280 | 586,848 | 62 |
| 12 | 59,679 | 219,136 | 579,024 | 62 |
| 27 | 59,679 | 219,136 | 572,752 | 62 |

The correct result is **no retained host-heap change detectable at 1 KiB
resolution through 25 additional idle agents, and no thread increase**. It is
not “zero bytes per agent.” The identical 59,679 KiB values are a
whole-KiB/resolution artifact, just as §3.1's “1,000 forks = 0 MB” was a
whole-megabyte artifact. RSS decreased because G1 uncommitted heap pages across
successive forced collections; negative RSS is not an agent-memory credit and
these points cannot yield a marginal byte count.

Source agrees with the thread result: agent creation writes facts and starts
no thread. `seon.agent.driver/start!` creates transient virtual threads only
when a pending message is scanned (`src/seon/agent/driver.clj:495-522`).
The current runtime also creates transient SCI forks; it does not retain the
target one-context-per-agent model. “Zero idle threads per agent” is therefore
source-verified and live-confirmed for the current implementation only.

Transient load memory, measured before forced GC on final host PID `15335`,
was:

| completed rung | used / committed heap KiB | RSS KiB | OS threads |
|---:|---:|---:|---:|
| 1 | 176,512 / 1,161,216 | 1,451,936 | 76 |
| 5 | 512,360 / 1,161,216 | 1,454,368 | 82 |
| 10 | 572,323 / 1,161,216 | 1,486,160 | 83 |
| 25 | 176,352 / 1,161,216 | 1,489,728 | 91 |

GC timing makes the `N=25` used-heap point lower than `N=10`; it is not a
retained-footprint curve. After two explicit full collections the same loaded
process reported 61,491 KiB used, 239,616 KiB committed, 607,968 KiB RSS, and
86 OS thread rows. That is post-load process retention, not per-agent memory.

### 17.7 What this section did not measure

- No exact provider-call start/end, token usage, cache hit, or dollar cost.
- No full target context derivation; this driver sends the raw message.
- No standalone transaction-service, plan-attempt, or reply-publish duration.
- No streaming reply, capability call, browser/SSE repaint, or hour-long
  survival.
- No load above 25 real agents and no provider-capacity ceiling.
- No retained SCI context per agent; the current path forks transiently.
- No sub-KiB retained-heap attribution, NMT/object histogram by agent, database
  disk bytes per agent, or sole-tenant repeat.
- No named-cluster writer failure isolation; the measurement shared the
  default writer.

### 17.8 Reset falsifier

After all database evidence above was captured, the explicit destructive
falsifier ran:

```text
bin/seon cluster reset agentload0726
  web-render: clean generation=27dc2264-339a-416a-af34-0104c4d72d55
  pod: clean generation=c6bd57e8-b664-458e-b9fc-6e06591aa007
  host: clean generation=68f17a74-290a-4f7e-ad8b-9c93c92af42e
● cluster agentload0726 reset

```

The target host/pod/web-render records were absent afterward and
`data/clusters/agentload0726/db` no longer existed. The default database
remained present and ready. Its watcher generation
`77448069-93b3-4491-b563-9aab360d93d6` (owner PID `14119`) and writer
generation `a4f62e62-cce4-409d-bf04-6bf94da1ebc5` (owner PID `14973`) were
unchanged across the reset.

Reset deletes the selected database and applied identity, then instructs the
operator to apply the current initialization pages again. No migration step or
data-migration path ran. The throwaway ended down, which is the intended
disposable-cluster terminal state.

Recurring proof after the reset: `bin/seon test operator` passed 336 tests /
1,960 assertions, and the focused `seon.web.serve-test` CLJS gate passed 35 /
185, both with zero failures/errors. Both benchmark scripts pass `bash -n`.
The generated issue projection contains every valid new note, but the
authority-wide `bin/issues-index --check` remains red on unrelated invalid
frontmatter and is recorded in
[[../../../seon/issues/issue-authority-frontmatter-drift-blocks-index]].

The topology blocker from §16 is fixed and the two requested clauses now have
real-agent evidence. The load clause has a first break and named resource.
The speed clause has a conditioned end-to-end waterfall, but the expected
result did not pass: SCI is ~0.12%, the model envelope is at most 58%, and the
non-SCI remainder is too large and too coarsely attributed to graduate.

## 18. Corrected self-attributing turn

Section 17 is retained because deleting a misleading number would delete the
lesson. Its `N=1` waterfall is **invalidated as a performance baseline**: the
plan transaction failed schema admission, its flat error was ignored, and eval
continued. The 1,398 ms “model envelope” therefore includes a failed
transaction and never timestamps the provider boundary. Its 2,409 ms total and
3 ms eval remain historical observations of that broken turn, not comparable
phase measurements.

Commit `c03ff91eb` closes both causes:

- plan and form schemas load from the portable run authority and are present
  in fresh initialization pages;
- a plan transaction that returns a flat `:seon/error` closes the turn/run and
  refuses eval;
- the JVM HTTP leaf measures request send through response-body consumption
  and JSON parsing, and both hosted wire cores retain that value;
- completed turns store one monotonic nanosecond total plus identity-free
  component rows for context derivation, provider request/response,
  model-envelope overhead, reply derivation, every transaction call, eval, and
  final publication; and
- the timing-settlement transaction is explicitly outside the measured total.

### 18.1 Conditions and cost

Every number in §18 has these conditions:

| condition | value |
|---|---|
| captured | 2026-07-26 03:18–03:19 EDT |
| machine | MacBook Pro `Mac17,6`, 18 cores, 128 GiB RAM |
| operating system | macOS 26.5.2, build `25F84` |
| code | commit `c03ff91ebbf9685fb4a13b197797fc07e45bc910`; application release `4c42148511c3b74fd869c0b9377de0a9a9589d9ebef8d0eb190ee682aaeb55f2`; writer digest `b186ef04b8d877a444313d8ea4e1c1b10f743ec25b0e2f2ae72703509edc62c6` |
| JVM | OpenJDK 26.0.1, G1, `-Xmx4096m`, `-Xshare:on`, AppCDS; host workload PID `90168`, shared writer workload PID `88807` |
| material JVM flags | `--add-modules jdk.incubator.vector`, `--enable-native-access=ALL-UNNAMED`, `--sun-misc-unsafe-memory-access=allow`, `-Xshare:on`, AppCDS archive, `InitialHeapSize=8 MiB`, `MaxHeapSize=4096 MiB`, `SoftMaxHeapSize=4096 MiB`, `UseG1GC`, `UseCompressedOops`, `UseCondCardMark`, 12 compiler threads, 4 concurrent-GC threads |
| cluster | fresh apply/open of `turnmeasure0726a`; file database `/Users/sean/src/seon/data/clusters/turnmeasure0726a/db`; host/pod/web-render workload PIDs `90168` / `90208` / `90240`; shared writer PID `88807`; watcher workload PID `89279` |
| database before request | basis transaction `536870932`, commit ID `6a65b394-d7f0-5f87-b04a-8d44ac11aa3a`; all seven required plan/timing attributes proven installed from database facts |
| model | DeepSeek `deepseek-v4-pro`, temperature `0.7`, `max_tokens=4096`, thinking disabled; driver forced non-streaming batch mode |
| request | one raw instruction asking for exactly `(seon.agent.lifecycle/complete "clean-turn")`; HTTP request bound 120 s; model-attempt bound 60 s |
| concurrency | one agent, one request, no launch spread |
| other work | shared writer and watcher, named host/pod/web-render, the Codex task, desktop applications, and unrelated checkout artifacts; not sole tenant; no test suite overlapped the paid request |
| cost | exactly one completed paid benchmark request was bought after local/focused proofs; no repeat ladder; token usage, cache use, retry-attempt count, and dollar cost were not persisted and are unmeasured |

`bench/agent_turn_load.sh` made the one request.
`bench/jvm_memory_snapshot.sh` captured the JVM, flags, heap, RSS, and thread
conditions immediately afterward. Its forced-GC snapshots are measurement
conditions, not per-turn resource claims: the host reported 62,467 KiB used /
260,096 KiB committed heap, 989,808 KiB RSS, and 78 OS thread rows excluding
the header; the shared writer reported 37,723 KiB used / 178,176 KiB committed
heap, 1,395,280 KiB RSS, and 93 thread rows.

The reconciliation tolerance was declared before the paid call:

```text
remainder-ns >= 0
remainder-ns <= max(5,000,000 ns, floor(total-ns / 100))

```

No remainder row is stored. It is derived from the total and measured
components.

### 18.2 Durable driver waterfall

The turn `ngbg2wjsud7g`, run `r1k04yr6rhqe`, and eval
`["r1k04yr6rhqe" 0 1]` completed `:done` / `:completed`. The run stored plan
digest
`3aa9dd30fb3bdd365906b3dc0c8fa62f79dbbe682d095b20450217dc220cd76e`
and the exact ordinal-zero source
`(seon.agent.lifecycle/complete "clean-turn")`.

The durable JVM-driver total is entry after the inbound message is observed
through return of the final publish transaction call:

| component fact | duration | share of driver total | classification |
|---|---:|---:|---|
| run-admission transaction call | 106.287167 ms | 4.1093% | measured; allocation, protocol, scheduling, and commit through returned report |
| turn transaction call | 109.814375 ms | 4.2457% | measured transaction-call envelope |
| current context derivation | 31.904666 ms | 1.2335% | measured; two pulls, config resolution, raw request content, static instruction; not full target context |
| provider request/response | 2,030.632542 ms | 78.5095% | measured at the HTTP leaf from send through complete body receipt and JSON parse |
| model-envelope overhead | 0.975958 ms | 0.0377% | measured outer transport envelope minus the nested provider interval |
| reply derivation | 4.898334 ms | 0.1894% | measured parse of the provider text into executable forms |
| plan transaction call | 94.078000 ms | 3.6373% | measured transaction-call envelope |
| eval-admission transaction call | 90.847791 ms | 3.5124% | measured transaction-call envelope |
| eval | 3.948458 ms | 0.1527% | measured complete JVM `evaluate!` call; nanoseconds avoid the old millisecond false zero |
| publish transaction call | 112.121458 ms | 4.3349% | measured final eval receipt + lifecycle close + reply publication through returned report |
| **persisted component sum** | **2,585.508749 ms** | **99.9625%** | measured |
| **derived unexplained wall** | **0.970334 ms** | **0.0375%** | derived, not stored |
| **persisted driver total** | **2,586.479083 ms** | **100%** | measured monotonic interval |

The allowed tolerance was 25.864790 ms (1% dominated the 5 ms absolute
floor). The 0.970334 ms remainder passes by 24.894456 ms. The component sum
does not equal the total by construction; the small positive difference is
the actual driver overhead between timestamped intervals.

The provider is the dominant driver component at 78.5095%. SCI is 0.1527%,
not the historical ~5%. This does not establish a universal provider or SCI
ratio: it is one minimal, one-form, non-streaming DeepSeek turn under the
conditions above.

### 18.3 Transaction and end-to-end proof

Every intended transaction in the measured driver sequence returned a native
transaction report and is joined from the timing facts to its exact committed
transaction:

| transaction | basis transaction | committed at UTC |
|---|---:|---|
| run admission | `536870934` | 2026-07-26 07:18:32.243 |
| turn open | `536870935` | 2026-07-26 07:18:32.343 |
| durable plan | `536870936` | 2026-07-26 07:18:34.512 |
| eval admission | `536870937` | 2026-07-26 07:18:34.604 |
| final publish | `536870938` | 2026-07-26 07:18:34.712 |

The next database value was basis transaction `536870939`, commit ID
`6a65b4ca-2986-5342-b32e-aff99a271868`; that is the timing-settlement
transaction and no timing row points to it. The turn has no
`:seon.agent.turn/error`, the eval has no `:seon.eval/error`, the plan facts
exist, and the run closed `:completed`. Unlike §17, no failed transaction is
inside this measured sequence.

Three nested wall boundaries remain deliberately distinct:

| boundary | duration | classification |
|---|---:|---|
| durable JVM-driver interval | 2,586.479083 ms | measured, self-attributed by turn facts |
| `/agents/run` endpoint envelope | 3,021 ms | measured integer-millisecond artifact returned by the endpoint |
| full `curl` HTTP wall | 3,214.469 ms | measured client wall |

The endpoint-minus-driver difference is 434.520917 ms. The
client-minus-driver difference is 627.989917 ms. Those differences include
the inbound-message transaction, wake delivery, final database acquisition
and response projection, HTTP request/response work, scheduling, and the
endpoint timer's millisecond truncation. They are **boundary artifacts**, not
durable driver components, because they cross clocks/processes whose owners
do not yet publish compatible timing facts. The provider interval is 67.2172%
of the endpoint envelope and 63.1716% of full HTTP wall. No part of either
difference is relabeled as provider, context, transaction service, or
publication.

### 18.4 What this correction did not measure

- Full target context derivation is still absent on this driver path. The
  31.904666 ms row measures only the current two-pull request derivation.
- The transaction-call rows are not pure Datahike commit durations; they
  include allocation/request construction where applicable, protocol,
  scheduling, writer work, commit, and report receipt.
- The timing-settlement transaction cannot record its own completed duration
  and remains an explicit unmeasured artifact.
- Token usage, cache hits, retry attempts, exact dollar cost, streaming,
  capability calls, browser/SSE repaint, and sole-tenant behavior were not
  measured.
- No concurrency or capacity claim is derived from this `N=1` correction.
- The named cluster used the shared writer, so it does not prove O9
  one-writer-per-store process isolation.

### 18.5 Fresh-cluster and reset falsifiers

Before the provider call, a cluster-qualified database probe at basis
transaction `536870932` proved the plan digest/forms and all turn timing
attributes installed. Focused recurring proof was green before the live
drive:

- JVM: 18 tests / 86 assertions for the driver, cold runtime schema, and real
  local HTTP boundary;
- CLJS cold bootstrap: 1 test / 2 assertions;
- portable hosted-provider wire cores: 28 tests / 108 assertions.

The full writer runner did not reach tests because the pre-existing
`seon.host-eval-wire-safety-writer-test` still references deleted
`seon.db.session/error-value`; that independent compile blocker is recorded in
[[../../../seon/issues/full-writer-suite-references-deleted-session-error-value]].

Before reset, canonical paths were proven unequal:

```text
/Users/sean/src/seon/data/clusters/default/db
/Users/sean/src/seon/data/clusters/turnmeasure0726a/db

```

`bin/seon cluster reset turnmeasure0726a` removed only target generations
`438438ab-ecf7-4d7e-8405-3542bca060a9` (host),
`93ddbe2a-fa0a-4694-afee-35673223e2b5` (pod), and
`c6ec1fad-c38a-4353-9208-f9e2463c27ee` (web-render), plus the target database.
The target was freshly applied from current initialization pages and reset;
no migration command or data-migration path ran.
The default database remained present. Default watcher owner PID `89276`,
generation `8c5311fb-039f-480b-9334-a70ac532dd68`, and writer owner PID
`88800`, generation `52166984-3ec0-4395-9cd5-b2d254bd35bb`, were exact before
and after the named reset. The shared processes were then shut down normally.

The corrected turn satisfies the performance clause: its facts answer where
the defined driver interval went, the components reconcile within a
predeclared tolerance, and the turn contains a successful durable plan rather
than a failed transaction hidden inside an envelope.
