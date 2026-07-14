---
type: research
status: completed
tags: [research, prd, flow]
---

# Shadow compiler memory profile (2026-07-13)

## TL;DR

The 2.6 GiB physical footprint of the long-running Shadow watcher is high, but
the captured run does **not** show an application-style leak or duplicate test
builds accumulating inside that process. The primary cause is simpler: the
At measurement time the `:cljs` alias supplied no heap policy, so the JVM
sized itself from the host's
128 GiB of RAM. It starts with a 2 GiB heap and permits nearly 30 GiB. G1 had
2.14 GiB committed even when only about 812 MiB was used after a natural young
collection. The old generation was about 360 MiB. RSS therefore stayed near
2.65 GiB because the JVM had touched and retained committed pages; it was not a
measure of the current live Clojure object graph.

The compiler also has a legitimately large baseline. One retained `:client`
worker held 533 build sources, 436 analyzer namespaces, 11,546 analyzed defs,
534 outputs, 9,845 constants, and full incremental compiler state. Shadow is
designed to retain that one immutable build-state value and replace invalidated
namespace/output entries on reload. After 19 compile cycles there was still one
worker, zero pending results, zero REPL actions, and bounded source/output
counts. No full GC occurred, and ordinary hot reloads did not add another build
state.

Focused tests do **not** share or accumulate a `:test` worker in the watcher.
Each `bin/test-cljs` invocation starts a separate one-shot JVM, compiles the
same 630-file `:test` graph, exits, and only then runs the selected tests in
Node. The `--test` selector affects runtime selection, not compilation. This is
an important aggregate-memory and CPU problem: every one-shot compiler gets the
same 2 GiB initial/nearly-30-GiB maximum heap policy, and multiple agents can
launch them concurrently while the watcher remains resident.

Recommended first change: put `-Xms256m -Xmx3g` on the single `:cljs` alias,
then mechanically prove cold `:client`, full `:test`, and repeated reloads. An
explicit maximum alone is insufficient on this host: `-Xmx2g` still selected a
2 GiB initial heap. The 3 GiB ceiling is a conservative starting bound, not a
proven minimum. Keep it only if the full compile gate passes and post-GC old
generation remains comfortably below the cap.

Second, serialize and freshness-deduplicate `bin/test-cljs` compilation so
concurrent agents cannot compile and overwrite the same artifact at once.
Third, remove `seon.dev.test-preload` from the ordinary client build if live-pod
test execution is no longer a product requirement: its 21 exclusive resources
accounted for about 1.81 million estimated tokens, roughly 20% of retained
emitted JavaScript text, despite being only 4% of the source count.

## Scope and safety

This was a read-only profile of watcher PID 54452 before the authorized cold
reset. It used process arguments, `jcmd`, `jstat`, `vmmap`, the Shadow nREPL for
small count projections, existing logs, repository configuration, and vendored
source. It did not trigger a full GC, class histogram, heap dump, build, test,
reload, or restart. No production or test source was changed.

The runtime jar was Shadow 3.4.10. `reference-code/shadow-cljs` was at 3.4.11,
but the audited worker, build-state, invalidation, CLI, and build-history files
had no diff from the repository's 3.4.10 bump commit. ClojureScript runtime was
1.12.145; the relevant vendored analyzer/compiler-state behavior is unchanged
in the checked-in source.

## What RSS means here

RSS is resident set size: pages the operating system currently keeps in
physical RAM for the process. It includes committed Java heap pages, metaspace,
thread stacks, JIT/code-cache pages, native allocations, and resident shared
library mappings. It is neither the JVM maximum heap nor a count of live
objects. Shared mappings can also make process RSS sums overstate unique system
memory.

On macOS, `vmmap`'s physical footprint is the better per-process pressure
number. This watcher showed:

| Measure | Captured value |
|---|---:|
| `ps` RSS | about 2.65 GiB |
| `vmmap` physical footprint | 2.6 GiB |
| `vmmap` peak footprint | 2.8 GiB |
| G1 committed heap | 2.14 GiB |
| G1 reserved maximum | 29.97 GiB |
| Metaspace used/committed | 161 MiB / 195 MiB |
| Native malloc resident | about 144 MiB |

The high number is therefore real physical pressure, but committed capacity is
the main explanation. It does not imply that 2.6 GiB of compiler data is live.

## Captured heap behavior

### The host-sized default is the dominant baseline

`jcmd VM.flags` reported:

```text
InitialHeapSize = 2147483648
MaxHeapSize     = 32178700288
SoftMaxHeapSize = 32178700288
G1HeapRegionSize = 16777216
```

The host has 128 GiB of physical RAM. JDK defaults were
`InitialRAMPercentage=1.5625` and `MaxRAMPercentage=25`, which explain the 2
GiB initial heap and roughly 30 GiB usable maximum after JVM address/layout
constraints.

The command line contained no `-Xms` or `-Xmx`. The absence follows the
repository path exactly:

- `deps.edn:93-167` defines `:cljs` dependencies and main opts but no
  `:jvm-opts`;
- `bin/seon:266` launches `clj -M:cljs watch client` without a heap override;
  and
- `package.json:13` exposes the same unbounded command.

A clean JVM flag probe showed that `-Xmx2g` alone still chose a 2 GiB initial
heap. `-Xms256m -Xmx2g` selected the intended 256 MiB initial heap. This is why
the fix must set both ends, not merely add a maximum.

### Used heap oscillated while retained old generation stayed modest

The first sample after several reload/test cycles showed about 1.27 GiB used.
Without any forced collection, the next young collection reduced used heap to
about 812 MiB:

```text
young/survivor capacity and use: about 1.28 GiB / 426 MiB
old capacity and use:            about 880 MiB / 360 MiB
full collections:                0
```

Later, two ordinary reloads filled Eden again and raised used heap to about
1.61 GiB while old use remained about 360 MiB. That is allocation churn in a
large committed young generation, not evidence that every reload retained its
temporary objects. G1 had performed 89 young collections in 32 minutes with
only about 0.29 seconds of aggregate young-GC time. GC CPU was not the observed
problem.

The right leak signal is the post-collection old-generation floor across many
equivalent reloads, not peak RSS or Eden occupancy. This run was too short for
a formal no-leak proof, but it showed a stable old floor while compile cycles
rose from 16 to 19.

## What Shadow retains by design

Shadow's watcher owns one worker per active build. The worker stores one
`:build-state` in its volatile state (`reference-code/shadow-cljs/src/main/
shadow/cljs/devtools/server/worker.clj:203-230`). The `:client` worker compile
path transforms the current value and associates the resulting value back once
(`worker/impl.clj:349-400`). It does not append prior build states.

The retained state is intentionally rich:

- `shadow.build.api/default-compiler-options` enables full source-map detail
  and embedded source content (`build/api.clj:64-92`);
- default build options use `:cache-level :all` (`build/api.clj:94-118`);
- build state owns source resources, compiled output, dependency indexes, and
  `:previously-compiled` (`build/api.clj:130-197`); and
- the ClojureScript compiler environment owns namespaces and a global constant
  table (`reference-code/clojurescript/src/main/clojure/cljs/env.cljc:16-57`).

The captured live state projected through Shadow's nREPL was:

| Retained slot | Count |
|---|---:|
| active builds/workers | 1 (`:client`) |
| build sources | 533 |
| source records / output records | 534 / 534 |
| analyzer namespaces | 436 |
| analyzed defs | 11,546 |
| build macros | 66 |
| global constants | 9,845 |
| Shadow JS properties | 7,949 |
| previously compiled ids | 534 |
| REPL sources / actions | 51 / 0 |
| worker pending results | 0 |
| connected runtimes | 1 |

On source change, Shadow computes affected resources, removes output and the
namespace's analyzer entry, then recompiles (`shadow.build.api/reset-resources`
at `build/api.clj:325-414`; removal at `build/data.clj:230-266`). This is
replacement, not an explicit history list. Build-history UI state also resets
its log on configure/build start (`server/build_history.clj:9-45`), so it is
not retaining every compiler log.

Two bounded-but-monotonic slots deserve long-run measurement:

- ClojureScript's global constant table only associates newly seen constants;
  namespace invalidation removes the namespace map but does not prune that
  table (`cljs/analyzer.cljc:550-568`). It was 9,845 entries here.
- Shadow's `:previously-compiled` is a set of resource ids. It matched current
  sources in this run, but transient added/deleted source ids should be checked
  over a long session.

Neither is large enough to explain this 2.6 GiB footprint today.

## Metaspace and class loaders

The watcher had 34,956 loaded classes, 12,042 class loaders, and about 161 MiB
of used metaspace. Almost all reported loader rows were
`clojure.lang.DynamicClassLoader`, which is consistent with loading and
compiling the JVM-side Clojure/macro surface. This is high baseline overhead,
but ordinary CLJS hot reload evidence did not show runaway loader growth: one
reload left both class and loader totals unchanged; two later reloads added 29
classes and 12 loaders in total.

Do not infer a loader leak from the absolute count alone. The useful experiment
is a cold baseline followed by a controlled series of ordinary `.cljs` edits
and a separate series of macro `.clj` edits, measuring class-loader and
metaspace floors. If only macro reloads grow monotonically, that is a JVM macro
reload lifecycle issue rather than duplicate CLJS build state.

## Test compilation is separate but wasteful

`bin/test-cljs:101-110` launches `clojure -M:cljs compile test` for every run.
The focused selector is forwarded only to Node at `bin/test-cljs:130-134`.
Shadow's `:node-test` target therefore still discovers/compiles every namespace
matching `-test$` (`shadow-cljs.edn:225-242`). Recent focused logs consistently
reported a 630-file graph and 7–11 second incremental compiles, including runs
that compiled only two or three changed files.

Direct `clj -M:cljs compile` is not delegated into the watch server. Shadow's
CLI handles `compile` with `api/with-runtime`
(`reference-code/shadow-cljs/src/main/shadow/cljs/devtools/cli_actual.clj:
131-166`); when no runtime exists in that new JVM, `with-runtime` creates one
and stops it on completion (`devtools/api.clj:76-91`). Live inspection agreed:
the watcher exposed only `:client`, never a retained `:test` worker, while many
focused test logs were produced.

This avoids test-state contamination of the watcher, which is good. The waste
is cross-process:

- every focused run pays JVM and 630-file graph setup;
- every compiler inherits the same 2 GiB initial/nearly-30-GiB maximum heap;
- concurrent agents can run multiple compilers simultaneously; and
- all of them write the same `out/test/test.js`, so concurrency is also an
  artifact-integrity risk, not only a resource concern.

Do not solve this by permanently loading `:test` into the existing watch
worker. That would trade transient isolation for a second large retained build
state. The smaller solution is one compile lock plus a source/dependency
fingerprint: one process refreshes the canonical artifact when stale; waiters
reuse the fresh result and run their independently selected Node tests.

## The dev test preload is small by count, large by emitted output

The ordinary `:client` includes `seon.dev.test-preload` and `seon.demo`
(`shadow-cljs.edn:57-76`). Dependency-closure analysis showed:

- `seon.client` itself reaches 496 sources;
- `seon.dev.test-preload` reaches 517 sources because it includes the client;
- only 21 sources are exclusive to the test preload; and
- those 21 outputs contain about 1.81 million estimated tokens of emitted
  JavaScript, out of about 8.94 million across all retained output strings.

Several test namespaces are among the largest compiled outputs. Source maps
and analyzer data amplify that text further. Removing the preload will not make
the compiler tiny—the application legitimately pulls Datahike, SCI, Malli, and
`cljs.js`—but it is a defensible 15–20% build-state reduction if live-pod test
execution/indexing is no longer required. Do not keep it merely because it was
historically convenient.

## Prioritized recommendations

### P0 — bound the one CLJS JVM policy

Add one policy to the existing `:cljs` alias, initially:

```clojure
:jvm-opts ["-Xms256m" "-Xmx3g"]
```

This reaches the watcher and one-shot CLJS compilers through their existing
single alias. Do not add a second launcher-specific heap configuration. Verify
the proposed 3 GiB ceiling rather than treating it as doctrine:

- cold `:client` compile and watch readiness;
- cold/full `:test` compile;
- at least 50 representative ordinary hot reloads;
- one macro reload sequence; and
- no allocation-failure/OOM or sustained old-generation use above 70%.

If all pass with wide margin, test 2 GiB. If the full test compiler needs more,
raise the shared cap deliberately or reduce the graph; do not return to a
host-sized 30 GiB default. Consider `-XX:+HeapDumpOnOutOfMemoryError` only with
an explicit safe external path and operator policy; do not emit giant dumps
into the repository.

### P0 — serialize and deduplicate test artifact builds

Give `bin/test-cljs` one artifact-build critical section and a complete
freshness fingerprint over source, config, dependency basis, and build options.
Only the lock owner compiles when stale. Other callers wait, verify the same
fingerprint, then run their selected tests. Preserve separate Node processes
and the current fail-loud completion verdict.

This both prevents concurrent artifact corruption and removes repeated JVM
startup/compiler allocation. A focused selector still does not make Shadow's
node-test compiler graph focused; describe that truth in command output.

### P1 — remove the default test preload if its capability is retired

If tests no longer need to be executable through the live pod, remove
`seon.dev.test-preload` from the ordinary `:client` preload vector and remove
its test path requirement from that build. Keep the canonical `:test` build as
the test authority. If live-pod tests are still required, make that capability
explicit and opt-in; do not make every normal watcher pay for it invisibly.

### P1 — add bounded compiler telemetry to operator status

Report only actionable process measures:

- physical footprint/RSS;
- committed and used heap;
- old-generation use;
- metaspace;
- active build ids and compile count; and
- running one-shot CLJS compiler count.

Do not poll `jcmd` continuously. Sample on readiness, after build completion,
and on explicit diagnostics. Alert on trends or concurrent compiler processes,
not on a single Eden-filled heap sample.

### P2 — measure before changing Shadow caches or source maps

Do not disable incremental cache or source maps as a first response. They are
the reason reloads completed in roughly 0.36–0.69 seconds. First remove the JVM
ergonomic overcommit and unnecessary test graph. Only then compare a controlled
build with reduced source-map detail/content. Accept a change only if measured
memory improves materially without making reloads or debugging worse.

### P2 — trend the known monotonic structures

At cold start and after fixed reload counts, record only counts for:

- analyzer namespaces and defs;
- output/source ids and `:previously-compiled`;
- constant-table entries;
- REPL actions and pending results;
- class loaders/classes/metaspace; and
- post-collection old-generation use.

A leak is a retained floor that rises under equivalent work. RSS alone is not
that test.

## Mechanical comparison protocol after reset

Use the new cold watcher as the zero point:

1. Record process flags, heap committed/used, old generation, metaspace,
   physical footprint, class-loader total, and Shadow state counts immediately
   after the first successful build.
2. Repeat after 10 and 50 ordinary `.cljs` reloads with no new namespaces.
3. Repeat after adding and deleting one temporary namespace; source/output and
   `:previously-compiled` should return to the expected bounded set.
4. Separately exercise a macro reload and observe loaders/metaspace.
5. Run one cold full test compile under the proposed heap policy and record peak
   footprint. Then run two focused callers concurrently: only one compile
   should occur after the lock/fingerprint change.
6. Leave the watcher idle through a natural young collection and compare the
   old-generation floor, not the pre-collection peak.

Success is a small cold footprint, a stable retained floor, one active client
build, no concurrent duplicate test compilers, and unchanged fast reloads.

## Commands used

The principal low-impact observations were:

```bash
bin/seon status
ps -p 54452 -o pid=,lstart=,etime=,%cpu=,%mem=,rss=,vsz=,command=
jcmd 54452 VM.command_line
jcmd 54452 VM.flags
jcmd 54452 GC.heap_info
jcmd 54452 VM.metaspace basic scale=MB
jcmd 54452 VM.classloader_stats
jstat -gc 54452
jstat -class 54452
vmmap -summary 54452
```

Small read-only nREPL projections inspected
`shadow.cljs.devtools.api/get-worker :client` and counted keys in its current
worker/build/compiler maps. No full object graph was printed and no state was
mutated.
