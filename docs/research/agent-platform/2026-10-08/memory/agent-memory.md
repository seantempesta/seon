# Agent memory at 50 agents

Measured 2026-10-08, lane `agent-memory`, notes and probe only. A cluster hosts branch agents through the existing MCP entrance; each agent holds a SCI fork, a branch connection and its Flow graph. The smallest composition is the existing shared program plus cheap forks, with bases advanced by changed definitions. No program implementation changed.

**Idle retention passes: 1.25 MB per agent in the warmed 0-to-50 cohort (1.21 MB from 10 to 50). Concurrency reaches 3.87 GB heap and at least 7.09 GB RSS.** Idle retention is not the problem demonstrated here; simultaneous model work and the process memory envelope still need attention. All units below are decimal MB/GB unless explicitly marked.

## Findings and limits first

- The final simultaneous workload completed all **10 MCP SCI passes and 10 model passes**, one form each, with **10 loopback fixture requests and no paid calls**. Peak observed heap was **3,867,465,592 bytes**; RSS immediately after it was **7,086,604,288 bytes**. This misses the proposed 6 GB RSS envelope. There is only 0.13 GB headroom below a decimal 4 GB heap budget in this configuration.
- The configured heap ceiling was **10,997,465,088 bytes**, not 4 GB. There is no explicit `-Xmx` on this root: the effective `-XX:MaxRAMPercentage=8` wins over the earlier 12.5 flag. The machine has **137,438,953,472 bytes (128 GiB)** RAM. On a 16 GiB machine 8% would be approximately 1.28 GiB, not 11 GB. These measurements therefore do **not** prove that a 16 GB machine needs 7 GB RSS or that the same workload fails there; its collector would run under a different ceiling. They do show that the current launcher does not enforce a portable absolute 4 GB cap, and that unrestricted concurrent work reaches GB scale on this host.
- Full-GC heap returned to **471.464 MB** after the final workload and **374.393 MB** after all 50 hosts retired. Cumulative allocation was **32.272 GB**, distinct from peak or retained heap. It was reclaimed; it is not a 32 GB live-memory claim.
- A fresh root initially reported **717 platform threads**; later idle observations were 23–74. A post-start process sample reached **9.581 GB RSS**. The final concurrent sample saw **262 platform threads**. These are observed samples, not a complete native-memory attribution or an exact RSS peak. Existing task `seon.flow/blocking-konserve-io-bursts-the-mixed-pool-to-five-hundred-threads` covers the pool burst.
- Shared default at entry: pid 51487, archive `b20762d6b`, `:faulted`, three open faults, 35 errored receipts, 19 unowned namespaces. `bin/seon status` and MCP `runtime_status` agreed. No evaluation, modification, stop or reset was sent to default. The MCP connector has no root argument; owned-root PREPL transport invoked the installed MCP entrance instead.
- Isolated status began `:observed`. After the first two concurrency rounds it was `:degraded`: five over-bound owners and unowned namespace `user`, **zero open turns**, no reported open faults. The over-bound owners were `acquire-program!` (max 2,607 ms), `call-turn` (3,780), `system-turn` (5,716), `walk/history` (2,942), `render/acquire-context!` (3,406). Existing acquisition and model-overhead tasks below cover these findings. No performance fix or green end-state claim is made.

## Installation and measurement

Measured HEAD archive: **`c6ae8875d68b73fa04112b36e449ebca167e3b96`**. Root: `/Users/sean/src/seon/tmp/agent-memory/root`; pid **58782**, start instant **2026-10-08T02:06:14.008Z**. Its cluster is named `default`, but its process, root and store are disposable and separate from shared default. Startup readiness was **53,911 ms**, including initial publication; existing `seon.cluster/boot-publication-phases-are-invisible` and `docs/seon/issues/from-zero-boot-takes-minutes.md` cover this cost/visibility class.

Dependency pins: SCI `ce0aa53b9bf35029e0bc27a962d799c196a37874`, Datahike `e5453d967b749794944dbdfe64b751519983ba59`, core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`, Malli `43c6dca8d2d8df3162b13725939954807be4a4ed`. Source anchors below refer to this immutable archive. The PB slice mapping refers to the reconciled plan as revised during this measurement, not to installed code.

The [probe](probe.clj) uses local `let` bindings, existing `seon.pass-probe/mcp-pass`, `agent-world`, `model-pass` and `seon.test-support/with-provider`. It defines/replaces no production Var. Each added agent runs `(+ 1 2)` through `seon.mcp/call`, which creates its branch and arms its graph. Snapshots count the live routing rosters, so a committed agent row alone is not counted as an armed agent. Model windows end after terminal **and idle** reports.

“0 agents” below means **zero added hosted agents**; boot's root agent remains in every row. The total armed counts are consequently 1, 2, 11 and 51. Removing the root would change the baseline system. Every heap reading calls `System/gc` **twice**, then reads `MemoryMXBean.getHeapMemoryUsage().getUsed()`. Successful full collections are corroborated by the old-generation GC counter. Sampling uses the raw host PREPL only for orchestration and MXBean readings; SCI/model behavior uses the real agent entrances.

## Idle curve

A first 0/1/10/50 cohort exposed startup cleanup: the 0-agent heap was larger than the 1-agent heap. To avoid treating that as negative agent cost, all 50 hosts were retired, collection repeated, and a second cohort used new identities 100–149. This warmed repeat is the primary slope. It retains the first cohort's durable history and warmed shared caches in **every** row.

| Added agents | Full-GC used bytes | MB | Delta from warm 0, MB | Platform threads | RSS, GB |
|---:|---:|---:|---:|---:|---:|
| 0 | 364,006,880 | 364.007 | 0 | 38 | 2.104 |
| 1 | 368,565,952 | 368.566 | 4.559 | 66 | 2.152 |
| 10 | 378,451,352 | 378.451 | 14.444 | 69 | 2.110 |
| 50 | 426,651,664 | 426.652 | 62.645 | 74 | 3.405 |

- Warm average: `(426651664 - 364006880) / 50` = **1,252,895.68 bytes/agent**.
- Warm marginal 10→50: `(426651664 - 378451352) / 40` = **1,205,007.8 bytes/agent**.
- First cohort 0/1/10/50 used **384,496,024 / 328,468,272 / 341,789,376 / 401,526,792 bytes**. Its 10→50 slope was **1,493,435.4 bytes/agent**. Repeating its 50-agent snapshot gave **401,581,792 bytes**, only 55 KB different.
- The measured range is therefore **about 1.2–1.5 MB per idle agent**, with a one-agent shared warmup cost. It passes ≤20 MB comfortably. Extrapolation to thousands of agents or agents retaining large user values is not established by this finite, trivial-value workload.

At 50 agents the roster holds **101 execution contexts**, **51 unique base `:env` atoms**, and **101 unique context `:env` atoms**. The extra contexts are branch and agent handles, not 101 independent full programs. The global base LRU held **3 entries** before the model workload. SCI `fork` (`reference-code/sci/src/sci/core.cljc:334`) only replaces the env atom and generation while sharing the persistent environment. Seon's per-branch base wrappers therefore do not prove a whole-program copy per agent. They also do not satisfy the target of one incrementally advanced cluster base: installed `derive-base-ctx` still builds a program on a cache miss.

## Retained owners

`jcmd 58782 GC.class_histogram` at the first idle 50-agent point reported **401,376,328 live shallow bytes**. Its largest classes were Object arrays **108.076 MB**, byte arrays **61.736 MB**, PersistentVector **47.604 MB**, and Datahike Datom **30.782 MB** (769,551 objects). Generic maps/vectors cannot honestly be assigned to SCI or Datahike from a histogram alone.

For stronger attribution, a live HPROF was taken with **the same 50 agents still hosted, after two concurrency rounds**. Its nearby full-GC reading was **507.345 MB**. Eclipse MAT 1.17 computed the **collective retained set** for each named class family below: objects collectable if all instances of that family cease retaining their descendants. This is not the size of all reachable shared data. Sets may overlap, so **do not sum the rows**, or subtract them from the earlier idle histogram. The MAT analysis itself ran in a separate offline process and is excluded from target RSS/heap readings.

| Owner | Measured attribution | Proportional to; producer | Planned treatment / budget assessment |
|---|---|---|---|
| SCI contexts and forks | **68.776 MB collective retained** by `sci.impl.opts.Ctx`. First idle histogram: 170 Ctx objects, 13,125 SCI Vars (0.525 MB shallow), SCI/Seon-SCI classes 0.610 MB shallow | Shared admitted program plus per-branch overlays, private Vars and live result objects. `src/seon/sci/eval.clj:2991` derives the base; `:3044` copies/forks it; `:2732` regenerates agent state; `cluster/agent.clj:1133` chooses the branch base | **PB-01, PB-02, PB-02b, PB-03** now explicitly cover the shared base, delta overlay, fork-once context and incremental adoption. They are planned, not installed. Idle budget passes |
| Datahike caches and indexes | **149.032 MB collective retained** by `datahike.*` and `org.replikativ.persistent_sorted_set.*`. MAT's top Leaf dominators alone account for **80.128 MB**. Idle histogram: 14,580 Leaf, 2,696 Branch, 562 DB objects; Datahike/index classes **31.978 MB shallow** | Stored facts/history and nodes reachable from live branch/old database values; connection-local caches and held snapshots. `reference-code/datahike/src/datahike/index/persistent_set.cljc:433` owns CachedStorage; `:450` accesses nodes. Effective `:store-cache-size` is 8192. `cluster/agent.clj:1106–1178` holds connections and acquired database values | **S4-06/07b, S5/S6/P14** reduce obsolete records and writes; they do not establish a bound on every old DB/root holder. No owner exceeds an individual retained allowance in this probe. Long-history retention remains an uncovered acceptance case |
| Central `seon.cache` | **65.236 MB collective retained** by the one Caffeine SSLMW. Weight was **119.890 MB** before model work, **132.635 MB** after it, under **512 MB** configured weight; 693 entries before model work. Caffeine bookkeeping only **0.029 MB shallow** | Content-derived schema, declaration, render and program entries, bounded by calibrated weights. `src/seon/cache.clj:54–75` creates the weighted cache; `:281` reports estimates | No measured budget breach. Weights are not retained-byte measurements. **PB-01** moves the separate base LRU into this cache. No need to invent another cache |
| Flow graphs and threads | Flow implementation objects collectively retain **0.963 MB**; VirtualThread plus StackChunk set **1.602 MB** in the later dump. First histogram: **208 virtual threads**, 373 StackChunks (**2.838 MB shallow**), 2,988 channels. Different collection points explain the different stack totals | One armer graph per hosted branch plus mailbox/turn/schedule procs per agent, shared root graphs, active stack depth. `src/seon/cluster/host.clj:105`, `cluster/agent.clj:563,1291–1348`; core.async dispatch `impl/dispatch.clj:71–111` owns executors | Retained graph structures are small. **S6-07** deletes permit/backstop machinery; it does **not** fix native pool/RSS bursts. The existing mixed-pool task is **uncovered by these plans** |
| Per-agent proc state | Lives in generic persistent maps and parked continuations; **no independent, additive byte total is claimed**. It holds handles to the SCI/DB rows above. Final retirement of all 50 agents reduced heap **471.464→374.393 MB**, a **97.071 MB joint lifecycle delta**, not a proc-state-only size | Mailbox counters, schedule state, held ctx/results, plus history-dependent turn state. `cluster/agent.clj:477,511,1309` and `src/seon/turn.clj:5299,5856` are the producers | **S4-03/04/06 and PB-02b** replace refresh state with held transcript/context. The trivial-value idle bound passes; unbounded live result ownership has no explicit removal slice (below) |

The histogram is a direct live-object census; the MAT sets are stronger ownership evidence, but are not a disjoint dominator partition by business owner. Native stacks, JIT code, metaspace, mapped files and allocator residency are outside all heap rows. The RSS excess is not falsely charged to SCI based on generic arrays.

## Twenty simultaneous passes

Twenty platform-thread callers wait at one latch: agents 100–109 call MCP SCI `(+ 1 2)`; agents 110–119 receive an outside wake and a fixture reply containing that same form. Agent creation, provider configuration and two full GCs occur **before** the measured interval. JFR starts before releasing the latch and stops after all terminal/idle reports. This measures simultaneous admitted work, not twenty evaluations guaranteed to execute at exactly the same instant; existing writers and provider transport may serialize parts.

| Final run | Result |
|---|---:|
| Window wall time | **4,045.325 ms** |
| Heap before release | **426.909 MB** |
| Peak from 609 approximately 5 ms samples | **3,860.035 MB** |
| Largest JFR before-GC heap | **3,867.466 MB** |
| All-thread allocated bytes (MXBean) | **32,271,768,624** |
| GC events / stop-the-world pause events | **20 / 21** |
| Pause total / maximum | **91.100 / 13.253 ms** |
| Peak sampled platform threads | **262** |
| RSS immediately after completion | **7.087 GB** |
| Full-GC heap afterward | **471.464 MB** |
| MCP elapsed range, all completed without `isError` | **154.340–874.394 ms** |
| Model elapsed range, all closed with one form and idle | **3,644.077–4,045.118 ms** |
| Fixture requests | **10** |

Both sampling and JFR can miss an instantaneous maximum; report the larger observed number, not a claim of an exact supremum. No full GC is forced **inside** the window. JFR start overhead precedes the latch; stop/dump follows completed work. GC pause totals count only top-level `jdk.GCPhasePause`, not nested pause phases or concurrent collection durations.

Two earlier controls on agents 0–19: the first model-wake JFR reached **3,824.627 MB**, 25 pauses totaling **130.918 ms**, max **17.199 ms**. A probe-parenthesis error discarded that first run's returned summary after recording, so it supplies a GC observation, not a separately certified completion row. The corrected repeat completed all twenty passes in **8,888.816 ms**, peak **1,989.073 MB** by JFR, 17 pauses totaling **211.306 ms**, max **81.928 ms**, with ten fixture requests. The final warmed-cohort run above is the reproducible primary result. An earlier incomplete reader form was corrected before measurement; it is a probe error, not a Seon defect.

## Plan coverage and uncovered owners

The [reconciled plan](../split/reconciled-plan.md) acquired PB-01/02/02b/03 during this lane. Those rows explicitly cover acquisition and context retention, superseding the earlier model-pass note's “no slice” statement for that owner. [REPL P11](../repl-audit/repl-plan.md) alone only fixes print-option tax; it is not the context-sharing slice. S4 removes repeated model context traversal, and S5/S6 plus P14 consolidate settlement. None of those changes has been measured as installed here.

1. **Process envelope and native burst: uncovered.** The producer of the effective ceiling is `deps.edn:157–165` (`:test` JVM flags; `:dev` at `:133–137`). The pool producer is core.async `impl/dispatch.clj:71–111`, reached by storage work; the existing task is `seon.flow/blocking-konserve-io-bursts-the-mixed-pool-to-five-hundred-threads`. Smallest next fix to evaluate: make the operator's heap policy explicitly cap at 4 GB while retaining sensible small-machine sizing, then replay this probe under that ceiling; route blocking storage operations through the dependency's virtual-thread I/O seam, as the existing task proposes. **Do not claim that capping heap alone fixes RSS** or that all native excess is thread stacks. Add the ≤6 GB RSS check to that slice. No new runtime task was written to shared default because this assignment prohibits default work and authorizes notes only.
2. **Unbounded live result objects: uncovered long-lived risk, not an observed over-budget row here.** `src/seon/sci/eval.clj:579–599` stores every result in both its handle map and SCI result namespace. Existing task `seon.sci.eval/live-results-are-held-for-the-contexts-lifetime` already names the defect. PB context reuse preserves those objects; it does not bound them. The smallest proposed change is a declared result-handle retention policy on this owner, releasing old handle references while preserving explicitly held private values and returning the existing gone-value marker. Policy must be agreed because this changes handle lifetime; no arbitrary bound is imposed by this measurement lane.
3. **History / old database retention: uncovered acceptance case.** Existing task `seon.turn/each-trivial-evaluation-retains-two-hundred-kilobytes` names the hypothesized old-index-root holder; its cause is still a hypothesis. The measured short workload is comfortably below budget, and retirement releases memory, so this run does not prove a leak. The smallest next diagnostic is the same heap/dominator comparison over repeated passes on one retained agent, attributing old DB roots before changing their producer. Hold commit identities instead of old DB values only where the actual retaining path proves that replacement correct. S4 deletes many records, but does not substitute for this proof.

Existing performance tasks also linked by this evidence: `seon.sci.eval/acquisition-rebuilds-the-whole-program`, `seon.db/the-checked-writer-is-slow-on-a-fresh-branch`, `seon.db/every-commit-validates-its-write-report-three-ways`, and `seon.turn/a-trivial-agent-turn-takes-eleven-seconds`. The owner of that delay is the planned S4 context/refresh deletion, not the provider fixture.

## Reproduction

Run from the checkout at the measured commit. Keep the launching shell/session alive until cleanup. `--root` requires the directory to exist. The operator builds a committed archive and shares its configured dependency caches; no worktree or working-tree source load is involved.

```sh
mkdir -p tmp/agent-memory/root
bin/seon --root "$PWD/tmp/agent-memory/root" start default
```

The outer Babashka driver below is transport only. Its classpath should point to the measured archive's `script:src:resources` for strict replay. The checked-in probe is a host measurement form with no definitions; supply `phase`, `from` and `to` as literals, as below. Require the committed fixture before compiling the enclosing probe form.

```clojure
(require '[seon.operator :as op] '[clojure.pprint :as pp])
(let [root "/Users/sean/src/seon/tmp/agent-memory/root"
      ad (first (op/advertisements root))
      [phase from to] *command-line-args*
      source (slurp "docs/research/agent-platform/2026-10-08/memory/probe.clj")]
  (when-not ad (throw (ex-info "Owned root absent" {:root root})))
  (read-string source) ; reject an incomplete measurement form before sending
  (op/prepl-value! ad "(require 'seon.pass-probe 'seon.cache 'clojure.core.async.flow)" 120000 nil 120000)
  (pp/pprint
   (op/prepl-value! ad
    (str "(let [phase " phase " from " (or from "0") " to " (or to "0") "] " source ")")
    120000 nil 120000)))
```

Driver phases in order: `:snapshot`; `:add 0 1`; `:snapshot`; `:add 1 10`; `:snapshot`; `:add 10 50`; `:snapshot`; `:inspect`; `:concurrent 0`; `:retire 0 50`. For the warmed curve use `:snapshot`, then add 100–101, 101–110, 110–150 with snapshots between; `:concurrent 100`; `:snapshot`; `:retire 100 150`; `:snapshot`. The final script omits repeated model prompt text from its report and reports the global fixture count once. The fixture ledger is shared, so a per-worker ledger delta would not be a per-agent request count.

After verifying the pid **and start instant** against the root advertisement:

```sh
ps -p VERIFIED_PID -o pid,rss,etime
jcmd VERIFIED_PID GC.class_histogram > tmp/agent-memory/histogram-50.txt
jcmd VERIFIED_PID GC.heap_dump "$PWD/tmp/agent-memory/heap-50.hprof"
jfr print --json --events jdk.GCHeapSummary,jdk.GarbageCollection,jdk.GCPhasePause \
  tmp/agent-memory/concurrent.jfr > tmp/agent-memory/gc.json
```

The JFR file path is explicit in the probe. GC peak is the maximum `heapUsed` from `jdk.GCHeapSummary`; pause milliseconds are `duration` from top-level `jdk.GCPhasePause` only. macOS `ps` RSS is KiB: multiply by 1024. Runtime arguments and MXBean maximum come from `:inspect`/`:snapshot`.

MAT reproduction, separately from the target JVM, using the installed app's `ParseHeapDump.sh`:

```sh
/Applications/MemoryAnalyzer.app/Contents/Eclipse/ParseHeapDump.sh \
  "$PWD/tmp/agent-memory/heap-50.hprof" org.eclipse.mat.api:suspects \
  org.eclipse.mat.api:top_components -vmargs -Xmx4g
```

For each retained set, run that command with `-command=show_retained_set CLASS_PATTERN` as **one argument**, followed by `org.eclipse.mat.api:query`; save each generated `_Query.zip` before the next query. Patterns: `sci.impl.opts.Ctx`; `(datahike|org.replikativ.persistent_sorted_set).*`; `com.github.benmanes.caffeine.cache.SSLMW`; `clojure.core.async.flow.impl.*`; `java.lang.VirtualThread jdk.internal.vm.StackChunk`. Read the total shallow bytes of the resulting retained-set histogram, not the approximate per-class retained column. These external analysis commands do not change product code.

## Landing and verification

- Claimed both files with `bin/lane claim agent-memory` before creating them. Source/test delta **0/0**. The only artifact beyond this note is the measurement form beside it.
- Design check: cheap persistent SCI forks held against the real runtime, and idle costs stayed small. The awkward part is the process envelope: heap residency, configured maximum, cumulative allocation and RSS are different quantities. The report keeps them separate, and names generic proc-state attribution as a limitation instead of manufacturing a per-owner sum.
- Foreign boundary: another lane's in-flight `sci/forgive.clj`, batch schema and test edits, the dirty HTTP-kit submodule and untracked competency/P11 artifacts were present at entry. More REPL/turn/schema changes arrived during measurement. The measured root used only the pinned archive. None of those files was changed, resumed or tested as part of this slice. HEAD advanced independently; the documentation gate therefore verifies its candidate archive, not a rerun of these memory numbers on later HEAD.
- Required full lint: `clj-kondo --lint src script test --cache false`, **13,240 ms**, **0 errors, 1,455 warnings**. Warnings are not presented as zero findings. This note and probe are outside the program lint roots.
- Required named proof: `seon.contracts-compile-test/a-check-of-no-sources-has-no-contract-findings`, through `bin/lane commit`. It proves the no-program-input gate, not the performance target. No whole test namespace or broad tier is claimed green; no pre-existing red was encountered in the named proof.
- Cleanup: all 100 measured hosted agents were retired through their owner, each returning nil; only the boot root remained. The owned JVM is stopped through `bin/seon --root … down`, its exit verified, and only its disposable root removed without following shared-cache symlinks. Local histogram/JFR/HPROF/MAT evidence beside that root is retained; committed numbers and the probe preserve the findings if those large local artifacts are later discarded. Default needs **no restart or reset** for this notes-only landing. Gate/commit receipt is reported with the landing.
