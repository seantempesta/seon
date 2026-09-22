---
type: landing
status: assigned regressions verified; namespace and platform limits recorded
created: 2026-09-21
tags: [test-selection, platform, gate-inputs]
---

# Gate widening and named-selection duration

Assignment baseline: `826fdbd6d4fbfaaaa9f3911571ccaa89e24036b4` plus the
orchestrator's uncommitted cache predicate edit. Owned production/test changes:
`src/seon/test/cache.clj`, `test/seon/test_cache_test.clj`,
`test/seon/test/selection_test.clj`. Evidence also adds this note and
`measure-gate-widening-2026-09-21.clj`, and updates the existing issue
`docs/seon/issues/the-publication-export-does-not-identify-the-exported-program.md`.
Repair commit: `dbb1bc79b` (six paths, +233/-3, including this evidence).
No foreign dirty file or pre-existing untracked file was changed; the later
foreign `test/seon/cluster/turn_test.clj` edit was also preserved.

## Widening: cause and declared semantics

`47c6d1123` added nonindexed graph fixtures to the widening predicate but
accepted the bare graph directory through `input-path?`'s equality arm.
`declared-input-roots` already removes `src` and `test` from deps.edn roots;
the suspected first-arm inclusion is false. Keep the platform expectation:
a bare graph root does not widen, an unindexed fixture beneath it does,
and `.clj`, `.cljc`, `.edn` files select by indexed reach. Documentation
outside declared inputs never widens (AGENTS.md and B4 §2 input/reuse rows).

The owner now tests graph descendants with a slash-delimited prefix. This
retains the orchestrator edit's semantics without constructing the graph-root
set twice. Both arities have regression coverage. The cache regression also
retains changed/deleted fixture digest and publication-exclusion assertions.
Loading HEAD's original cache owner in Babashka and running the strengthened
regression produces exactly the two bare-root failures:
`tmp/gate-widening-falsify.log`. The fixed owner passes those cases in the
armed HEAD-plus-owned-paths run.

The supplied `tmp/platform-gate-2026-09-21b.log` actually records HEAD
`826fdbd6d`, **no snapshot differences**, and three workers. It does not prove
that the uncommitted orchestrator edit ran. No claim about another historical
version of that log is made.

## Runtime observation before edits

`bin/seon status` and MCP `runtime_status` reached PID 56288, start
`2026-09-22T00:33:08.873Z`, with no missing readiness layers. Status reported
three error signatures, one errored receipt and one failed run; that is not
an all-green runtime claim. No missing MCP tool or workaround.

Read-only MCP JVM form, explicit root `/Users/sean/src/seon`, cluster `default`:

```clojure
(let [roots (seon.test.cache/input-roots "/Users/sean/src/seon")]
  {:roots roots
   :paths (mapv (fn [p] [p (seon.test.cache/input-path? roots p)
                        (seon.test.cache/widening-path? roots p)])
                ["src" "test" "src/fixture.txt" "src/seon/db.clj"])})
```

Complete returned value included the declared non-graph roots and paths
`[["src" false true] ["test" false true] ["src/fixture.txt" false true]
["src/seon/db.clj" false false]]`, 22 ms. This exercises the old loaded JVM
predicate. The same question in a fresh Babashka process loading on-disk code
returned `false false true false` for widening. No reload, reset, stop,
or other explicit default operation was performed. The editor hook announced
a queued publication; this note makes no publication/adoption or browser claim.

## Duration: three measurements before changing the bound

`tmp/gate-widening-measure.log` retains three sequential diagnostic invocations
of the **unchanged test body**, through `runner/run-var!` with all 1,536
registered contracts armed (1,528 program-armable), panic mode. The committed
measurement script wraps original callables, never bypasses them, and restores
all temporary Var metadata/redefinitions. These are diagnostic measurements,
not separately recorded gate passes. All seven behavioral assertions passed
on every trial; trials 1 and 2 correctly remained red under the old 5 s bound.

| Trial | Complete test body, ms | Runner duration when red, ms | Machine load / concurrency |
|---|---:|---:|---|
| 1 | 6336.271875 | 6336.600417 | Series load 4.05 → 4.52; one probe JVM, idle default |
| 2 | 5067.093709 | 5067.124666 | Same sequential process; no competing test JVM |
| 3 | 4902.838208 | below 5000 | Same sequential process; no competing test JVM |

Process-table observations bracketed the series. Before it, only default was
running; during it PID 64786 was the only additional Java process and default
reported 0% CPU. Load is the macOS one-minute average, not instantaneous
utilization. No kind-cut session was messaged, resumed, edited or stopped.
Fixture base acquisition was measured separately at **1529.981417 ms** and
excluded from the three body durations. The body includes its own branch
acquisition and release. Observed probe RSS during startup was 3,661,664 KiB;
this is a point sample, not peak memory or an allocation measurement.

The body seeds one canonical cluster/configuration, analyzes four declarations,
performs four selection calls, six program-provenance calculations, five
explicit fixture transactions, and retires its branch. Source analysis cost
117.440 / 39.011 / 35.274 ms; the two reach-digest calls together cost
25.229 / 19.738 / 16.786 ms. Cluster seeding cost 1568.459 / 819.780 / 763.679 ms
(the script also observes the nested arity, which must not be summed).
The six program digests cost 2360.992 / 2191.031 / 2158.170 ms in aggregate.
Selection and transaction observations are inclusive and overlap those digests.

Inspection: `runtime-analysis-batch` reads declarations in the supplied
namespace and analyzes one batch; `program-digest` compares only program
entities touched since the source seal; reach hashing is requested only for
the unchanged candidate. No whole-program reindex was found in this test.
The existing assertion that the changed candidate never requests a reusable
reach digest remains intact. This measurement justifies a lifecycle bound,
not a new algorithm or cache. The declared **8000 ms** covers the measured
6336 ms maximum plus the observed 1433 ms first-run spread, rounded up to a
whole second. It does not convert either old timeout to green.

Dependency seams inspected: Clojure `test-var`/`test-vars`
(`reference-code/clojure/src/clj/clojure/test.clj:710`, pin
`b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d`) emits begin/end events around each
body and composes namespace fixtures; `runner` owns elapsed-bound enforcement.
Datahike `branch!` (`reference-code/datahike/src/datahike/versioning.cljc:212`,
pin `006e634ae955c186619adb5f3868cca29d8c97fb`) takes the explicit fixture base
and branch name, retaining roster custody. `with-branched-database` calls it
once per body and releases the branch. Neither dependency changed.

Reproduce diagnostic measurements from the checkout (one test JVM at a time):

```sh
gate_measure_base=$(bb --classpath src -e '(require (quote [seon.test.cache :as c])) (let [v (binding [*out* *err*] (c/newest-base "." "HEAD"))] (println (:seon.test.cache/base v)))')
clojure "-J-Dseon.test.published-base=$gate_measure_base" -M:test \
  docs/prds/agent-platform/landing/measure-gate-widening-2026-09-21.clj
```

## Verification boundary

Exact iteration command, both runs:

```sh
bin/test-fast --paths src/seon/test/cache.clj test/seon/test_cache_test.clj \
  test/seon/test/selection_test.clj -- seon.test.selection-test seon.test-cache-test
```

First run `beff5778d34b`: 15 executed, 0 reused, 240 assertions, 2 failures,
1 error (`tmp/gate-widening-fast-1.log`). Widening passed. Named selection
failed only its old duration, 5045.132708 ms. Other evidence:
`fileless-sci-tests-use-the-same-selection` exceeded 5 s at 9000.866458 ms;
`selection-derives-bases-obligations-and-exact-symbol-reach` injected a read
refusal that the armed `seon.fn/declared-reference-edges` output contract
rejected as a base error without a complete facet. The latter is the existing
[refusal propagation issue](../../../seon/issues/test-refusal-observations-overflow-in-projection-acquisition.md).
These are separate from the two assigned reds and are not classified as
foreign edit breakage: the snapshot used HEAD plus exactly the three owned
paths. No worktree was needed. A diagnostic attach attempt as the first JVM
was exiting failed its handshake; process exit was subsequently observed.

Second run `a088fe64a752`: 15 executed, 0 reused, 239 assertions, 1 failure,
1 error (`tmp/gate-widening-fast-2.log`). Both assigned tests passed; the
named lifecycle's begin/end interval was 5528.435 ms. The remaining duration
failure was the fileless SCI test at 10099.843958 ms; the same injected-refusal
error remained. A competing turn-test JVM (PID 65370) started after this
snapshot launched, so this run is not the final quiet-machine proof.

The final regression also checks `.cljc` and `.edn` in the platform test.
Babashka owner check: 5 tests, 59 assertions, zero failures/errors
(`tmp/gate-widening-cache-final.log`). clj-kondo: zero errors, five existing
warnings in untouched forms (`tmp/gate-widening-kondo.log`).

Third run, HEAD `dbb1bc79b`: both assigned regressions executed and passed
with only the test JVM (PID 66412) and idle default. At their completion the
one-minute load was 3.89 (launch 3.49); no competing test JVM. Begin/end
intervals: widening **11.047 ms**, named selection **5122.777 ms**. These
intervals are the runner's progress timestamps, not its discarded internal
nanosecond counters. Log: `tmp/gate-widening-fast-3.log`; launch process/load
inventory: `tmp/gate-widening-fast-3-load.log`. Recorded run `da809c372335`:
**15 executed, 0 reused, 243 assertions, 1 failure, 1 error**, process exit 1.
The assigned tests contributed 23 and 7 passing assertions respectively.
The remaining failure is fileless SCI duration (9219.785167 ms); the error
is the same injected-refusal output-contract violation described above.
This is green evidence for the two assigned regressions, not for the whole
namespace or platform. The runner exited and removed its snapshot; the
probe's canonical fixture shutdown left `tmp/fixture-bases` empty. No owned
shell or JVM remains.

After `dbb1bc79b`, the required fresh JVM check exited 0:
`clojure -M -e "(require 'seon.test.cache 'seon.test)"`
(`tmp/gate-widening-head-load-1.log`). A second read-only MCP JVM probe with
the same four paths returned `[["src" false false] ["test" false false]
["src/fixture.txt" false true] ["src/seon/db.clj" false false]]`, 26 ms.
This positively observes changed loaded behavior after the editor hook;
it does not establish source-commit convergence or browser paint. The orchestrator retains the platform
checkpoint; this lane never invokes `bin/test --platform` or a full suite.


Closeout preserves the subsequently dirty `src/seon/cluster/agent.clj`,
`src/seon/cluster/reply.clj` and `src/seon/turn.clj`. Foreign commit
`aba6d445e` arrived after the final test snapshot was captured; that snapshot
still records `dbb1bc79b`. The post-closeout load command is the same required
`clojure -M -e "(require 'seon.test.cache 'seon.test)"`, run in a clean Git
archive of HEAD with the unchanged vendored dependency directory linked,
so foreign uncommitted source cannot change the load subject. Its output is
`tmp/gate-widening-head-load-2.log`; the owned archive is removed after exit.
No worktree is created.

Production/test line delta in `dbb1bc79b`: cache +2/-1, selection test +11/-2,
cache test +8/-0. The larger commit total includes the 37-line measurement
script, landing evidence and 12-line existing-issue update. This slice changes
gate classification and a test bound; it does not alter publication machinery
or assert a publication-path performance result.
