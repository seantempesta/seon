---
type: research
status: active
tags: [research, runtime]
---

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

**The uncatchable marker holds, and agent code cannot even name a broad class.**
**measured 2026-07-25:** `Throwable`, `Error`, `RuntimeException`,
`StackOverflowError` and `:default` all fail with *"Unable to resolve classname"*;
only `Exception` resolves. The marker contract is stated verbatim in
`reference-code/sci/src/sci/interrupt.cljc:32-42`.

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

**The commit path, not SCI, is the cost centre.** SCI is ~5% of a turn — *before* an
LLM call that dwarfs everything. Every "make the interpreter faster" proposal (JIT,
graduation-as-speed, compiled tiers) optimizes that 5%.

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
`:seon.error.frame/index`.

### 10.3 Tuples are not a general escape

**verified.** Homogeneous tuples **throw** above 8 values — *"Cannot store more than
8 values for homogeneous tuple"* — `reference-code/datahike/src/datahike/db/transaction.cljc:1006-1012`.
Element-level Datalog against a tuple returns nothing; only whole-value match works.

### 10.4 The rule this episode teaches

**Order is never a property of the collection type.** It is a stored ordinal on the
child (`:seon.error.frame/index`), a recovered transaction id (`::status-tx`), or an
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
  write passed through the door. No `:mixed` workload. No streaming. Every "reply"
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
- **The concurrency ceiling.** The curve was still improving at n=200 (§7.1).
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
- **`:seon.agent.run/process` as the replacement for the coinage "claimant"** —
  proposed, not ratified. Grounding is real on both sides (`rg -i claimant
  reference-code/datahike/ -l` → **0 files**, against `rg -c claimant src/` → **25
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
