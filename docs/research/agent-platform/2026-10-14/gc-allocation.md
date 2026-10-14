---
type: research
status: investigated
date: 2026-10-14
---

# Collection allocation: metadata enumeration dominates the dry run

`seon.maintenance/collect!` delegates reachability to Datahike and candidate selection to Konserve.
The current mark already shares visited commits and index addresses. Phase 1 passes one Konserve
key inventory as a value to its existing consumers, replacing three enumerations.

Disposition: **phase 1 passes the owner's repeated-measurement rule and is prepared for the root
restart batch.** Seven alternating fresh-process pairs on the unchanged quiet 10,009-key copy reduce
median full dry-run wall time from 2,060.73 to 1,385.38 ms (32.77%) and cumulative allocation from
4,640.590008 to 1,628.624096 MB (64.90%). The wall ranges do not overlap. The earlier single-sample
stop was not a sufficient measurement; it is superseded by the repeated evidence below. Fork commits
are local, unpushed. The Seon change and gitlink bumps are delivered as a patch, not installed on
default. Phases 2 and 3 remain proposed.

Task: `seon.maintenance/a-collection-dry-run-allocates-in-proportion-to-what-it-marks`.
Related task: `seon.maintenance/collection-marks-slowly-and-records-nothing`.
Prior observation: [chain branch mechanics](chain-on-one-branch.md#1-branch-mechanics).

## Phase 1: one enumeration, repeated proof, restart-batch delivery

`konserve.gc/sweep!` now accepts `:konserve.gc/report?`. Its default result remains the candidate
set. The opt-in result carries `:konserve.gc/candidates` and `:konserve.gc/observed-keys`, both from
the existing metadata enumeration; the observed key set is realized before deletion.
`datahike.gc/gc-storage!` and its writer already pass the result through unchanged, so the Datahike
change documents that interface, pins the supporting Konserve revision, and proves the forwarding.
Seon's registry consumes the report, deletes its count-only enumeration and key-count atom, and
returns the declared `:seon.cluster.registry/observed-keys`. The dry-run verifier and real collector
use that set instead of their separate inventory. `seon.maintenance/konserve-key-set` is deleted and
its test caller converted. The public inventory/result projection otherwise stays the same.

Cutoff derivation, safe-point acquisition, deletion batches, root reopening and exception carriers
remain at their existing owners. Real collection's stored-blob evidence is taken before deletion by
the sweep's enumeration; it is never reconstructed after deletion. Storage encoding is unchanged.
No cache, retry, gate or second collector was added. Work remains proportional to retained commits,
unique reachable nodes, referenced blobs and all stored keys; two full metadata enumerations disappear.

### Seven alternating parent/candidate pairs

The benchmark uses Seon parent `cf0a70e318173108047e704622fe1c1be606f825`, Datahike
`c9e57c6fb65365d5ce92b59854fb02ea59054fe7`, and Konserve
`26444d372bf97de219d55c06d5d23bd6f5cfc634`. Parent and candidate are separate `git archive`
snapshots, not worktrees; changed dependency source trees are separately archived. Their GC source
is exactly the delivered change. The touched Seon paths did not change between that benchmark base
and delivery base `978f7122cb68258f99a98c567f9e0beaca0c0d4d`. Other dependency paths reused the
checkout's existing dependency locations. Candidate code ran only in owned JVMs (`java`, 2 GiB heap),
never in default. `bin/seon status` and MCP `runtime_status` answered; default was already faulted.
No collection ran there.

The [probe](gc-one-enumeration-probe.clj) calls `seon.maintenance/collect!` under
`seon.profile/measure`, with both the enable and dry-run flags true. `measure-once` performs exactly
one measured public dry run, then a separate untimed registry dry run to capture candidate identity.
Execution order was P1 C1 P2 C2 P3 C3 P4 C4 P5 C5 P6 C6 P7 C7, each in a **fresh process with no
collection warm-up**. JVM/class loading is outside the measured call. Store acquisition is measured
separately below; JIT/GC work occurring during collection remains in its counters. No other lane's
test JVM was launched by this lane during the measurements. The developer machine was not reserved;
the store itself had no other holders, graphs, schedules or writers. All fourteen JVMs exited zero.

The same preserved store copy was reopened at its original path because Datahike's store identity
is path-derived. It contains 10,009 logical keys, 10,000 candidates, 1,039,757 physical store bytes,
one branch `:db`, and commit `6acee46d-af8d-572e-98a7-1fb2abace86f`. Every run used cutoff
`2026-10-13T02:09:49.032Z`, verified roots, completed, and reported zero swept objects and reclaimed
bytes. EDN equality checks compared all fourteen registry results and public results with only the
two duration fields removed. SHA-256 over sorted relative file names and contents was unchanged from
before the first investigation through these fourteen runs:
`d8c705ac9723e3f19ef2e0e4c5484e975c93a1028d0fac7fb2b3481392d0f852` (10,010 files including the lock).
No larger fixture was run: another fourteen fresh JVMs plus creating another store was not a cheap
extension. These numbers make no claim about a larger store's absolute latency.

Allocation is **cumulative all-thread JVM-window allocation**, in decimal MB, from
`seon.profile/measure`; it includes compilation and other work in that JVM. It is neither retained
heap nor the calling-thread-only counter. Each row below is one parent/candidate pair; no sample was
omitted.

| Pair | Parent wall ms | Candidate wall ms | Parent allocation MB | Candidate allocation MB |
|---|---:|---:|---:|---:|
| 1 | 2,062.85 | 1,334.27 | 4,640.590008 | 1,628.665752 |
| 2 | 2,060.73 | 1,582.73 | 4,640.259904 | 1,628.709136 |
| 3 | 1,876.01 | 1,621.75 | 4,640.820304 | 1,628.624096 |
| 4 | 2,119.07 | 1,547.66 | 4,639.615400 | 1,628.313872 |
| 5 | 2,214.70 | 1,257.67 | 4,641.095376 | 1,628.627232 |
| 6 | 1,967.86 | 1,318.78 | 4,640.121968 | 1,628.461016 |
| 7 | 2,046.41 | 1,385.38 | 4,640.646408 | 1,627.927120 |

| Statistic | Parent wall ms | Candidate wall ms | Parent allocation MB | Candidate allocation MB |
|---|---:|---:|---:|---:|
| Median | 2,060.73 | 1,385.38 | 4,640.590008 | 1,628.624096 |
| Minimum | 1,876.01 | 1,257.67 | 4,639.615400 | 1,627.927120 |
| Maximum | 2,214.70 | 1,621.75 | 4,641.095376 | 1,628.709136 |
| Median absolute deviation | 58.34 | 127.71 | 0.330104 | 0.085040 |

**Decision: deliver.** Allocation falls and median candidate wall time is below median parent wall
time. Even the slowest candidate is faster than the fastest parent in this sample. The owner's
2026-10-14 rule is met without appealing to a noise allowance. The remaining 1.39 s median reflects
one enumeration of 10,009 metadata objects plus marking and verification; phase 1 does not establish
a sub-second guarantee or resolve the broader allocation/receipt tasks.

Acquisition was outside the collection interval, with its own all-thread counter:

| Pair | Parent acquisition ms / MB | Candidate acquisition ms / MB |
|---|---:|---:|
| 1 | 160.31 / 6.539112 | 148.56 / 6.557752 |
| 2 | 158.04 / 6.537576 | 147.13 / 6.582024 |
| 3 | 159.97 / 6.576472 | 150.55 / 6.528712 |
| 4 | 169.97 / 6.575504 | 151.11 / 6.582920 |
| 5 | 173.42 / 6.586912 | 156.40 / 6.555632 |
| 6 | 194.08 / 6.538736 | 140.55 / 6.557600 |
| 7 | 172.26 / 6.509320 | 159.64 / 6.471360 |

Full metadata enumerations per public dry run: **3 before, 1 after**. This is source-path evidence,
not an instrumented call counter: the parent registry count, Konserve sweep and verification inventory
become only the Konserve sweep. Per-function call counters in this isolated measurement are zero
because Seon's wrappers were not installed. The full JVM allocation and wall counters still include
all executing code. The instrumentation/booted-runner checks below are separate correctness proof.

The exact invocation for each variant was:

```sh
java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xmx2g \
  -cp "$(cat tmp/gc-one-enumeration/parent.classpath)" clojure.main \
  docs/research/agent-platform/2026-10-14/gc-one-enumeration-probe.clj measure-once \
  /Users/sean/src/seon/tmp/gc-one-enumeration/store
```

Replace `parent.classpath` with `candidate.classpath` for C, alternate seven times. The archived
sources/classpaths and complete logs are retained in `tmp/gc-one-enumeration/`; the new logs are
`alternating/01-parent.log` through `alternating/07-candidate.log`, and their parsed measurement maps
are in `alternating/measurements.edn`. The probe and every numeric result are committed here.

### Correctness proof and delivery

- Konserve regression `konserve.gc-callback-test/sweep-report-keeps-the-observed-inventory-before-deletion`
  failed three assertions on parent. Its namespace passes on candidate (four tests, 24 assertions).
  It proves the additive report on empty stores, cutoff exclusion, retained/candidate keys, dry runs
  and real deletion. The existing callback-order/failure tests remain green.
- The combined native check passed **11 tests, 60 assertions, zero failures/errors**: the four tests in
  `konserve.gc-callback-test`, `konserve.memory-test/async-gc-test`,
  `datahike.test.gc-test/collection-passes-through-the-sweep-inventory`,
  `datahike.test.gc-test/marking-with-a-shared-seen-set-walks-each-node-once`, and
  `datahike.test.background-gc-test/guard-spares-unreferenced-writes`,
  `datahike.test.background-gc-test/sweep-spares-in-flight-commit`,
  `datahike.test.background-gc-test/sweep-spares-in-flight-schema`,
  `datahike.test.background-gc-test/sweep-spares-in-flight-branch`.
  The previous missing `kaocha.stacktrace` was resolved by including Datahike's declared Kaocha jar.
- Seon's archived candidate full lint (`clj-kondo --lint src script test --cache false`) passed with
  zero errors, 1,662 warnings in 8,804 ms. The final delivery candidate (current HEAD plus exactly
  the patch) also passed: zero errors, 1,664 warnings in 7,435 ms. Immediately before the note
  commit, a fresh HEAD archive lint passed with zero errors, 1,664 warnings in 7,523 ms. Booted test
  evidence follows below.

The first combined candidate check booted and executed all sixteen named reaching tests: **117
passing assertions, three failures, zero errors**, with fifteen tests green. Its only red was
`seon.cluster.fault-storage-test/oversized-fault-evidence-is-bounded-and-retrievable`. A separate
parent check reproduces the same three failures, so this is an existing foreign boundary:

| Revision / run | Fault test duration ms | Occurrences (expected 1) | Count (expected 500) | Time bound ms |
|---|---:|---:|---:|---:|
| Candidate `b8fc082bd6` / `90e847c52b52` | 63,759.346542 | 3 | 1 | 5,000 |
| Parent `978f7122cb` / `df48c0c35368` | 69,118.994417 | 3 | 1 | 5,000 |

The parent run has ten passing assertions, three failures, zero errors. Total named-test times
were 150,815 ms (candidate sixteen tests) and 73,058 ms (parent one test). Neither result is counted
as green. Occurrence aggregation belongs to fault recording, outside this lane's ownership; its
cause is not inferred from this reproduction. The passing test vars are named individually in the
root commit command below. The logs are retained at `tmp/gc-one-enumeration/seon-candidate-check.log`
and `tmp/gc-one-enumeration/seon-parent-fault-check.log`.

A subsequent check of the exact patch candidate with those fifteen vars is **green**: run
`a55a878b0da1`, fifteen executed, 107 passing assertions, zero failures/errors, 88,243 ms for tests
and 134,787 ms for the complete archived boot/check. Boot was 32,046 ms and adoption 2,814 ms.
Command: `bin/seon check --at b8fc082bd6b58e1ff41bd6f763cbfd055aa0acc3`, followed by each of the
fifteen `--test` arguments listed below. Full output is retained in
`tmp/gc-one-enumeration/seon-candidate-green-check.log`. These isolated correctness tests include
real sweeps of their own disposable stores; the performance probe remains dry-run-only.

The first note/probe/patch commit attempt used `bin/lane commit` with those fifteen named tests.
It booted newer HEAD `de1f48c23ce0da42f5961bbc44a2dec5a03e5ab6` plus only these three documentation
paths (candidate `18bea4cd30c89fe64f679dd8a7e55ef727e497de`), without installing the GC patch.
That run, `e335e49d815e`, had 97 passing assertions, one failure, zero errors in 93,959 ms:
`seon.cluster.armed-test/an-eval-request-after-a-collection-reads-no-collected-value` expected
`{:error? false :values [3]}` for its post-collection `(+ 1 2)` request but received
`{:error? true :values ()}`. The commit was refused. This is a second foreign boundary in the
newer HEAD's eval-request path: documentation changes no program input, and the exact GC candidate
passed this same test. Its cause is not diagnosed here and is not assigned to a particular foreign
commit. The failed check is preserved in `tmp/gc-one-enumeration/note-lane-commit.log`.

The final documentation-only commit names the remaining fourteen vars individually. Its check
proves HEAD plus these delivery files; the independent exact-candidate check above proves all fifteen
production reaching tests. Root still runs the broad tiers centrally, including the armed test
again on its final restart-batch composition.

Fork commits, on their existing working branches, **not pushed**:

| Fork | Commit | Branch |
|---|---|---|
| Konserve | `b4b896f24317d90f955405b950efefbb8b303512` | `seon/tiered-range-reads` |
| Datahike | `62209ee62051bfcd52f7135b7dc898f25457e1bc` | `seon/overlay-file-frontend` |

[gc-one-enumeration.patch](gc-one-enumeration.patch) carries both gitlinks, the matching Konserve SHA
in `deps.edn`, the registry/verifier change, its schema, and its two test files. Datahike's own
`deps.edn` pins the new Konserve commit too. The Seon production diff is nine lines smaller; the test
diff adds seven net lines and the schema adds two. This patch does not change a receipt encoding.

The patch's exact candidate is `b8fc082bd6b58e1ff41bd6f763cbfd055aa0acc3`, built from delivery base
`978f7122cb68258f99a98c567f9e0beaca0c0d4d` through a temporary Git index. This is an unreferenced verification commit object; no branch tip, worktree or shared
index was changed. The default process remains on its previous
archive. Root applies this only in its next restart batch.

Apply steps, from `/Users/sean/src/seon`, after root opens that batch and claims the paths:

```sh
# Both fork working branches already hold these commits; verify before applying.
git -C reference-code/konserve rev-parse HEAD
# b4b896f24317d90f955405b950efefbb8b303512
git -C reference-code/datahike rev-parse HEAD
# 62209ee62051bfcd52f7135b7dc898f25457e1bc

# Seed tools.deps' existing mirror from the local fork; no push or remote fetch is needed.
git -C /Users/sean/.gitlibs/_repos/https/github.com/seantempesta/konserve \
  fetch /Users/sean/src/seon/reference-code/konserve b4b896f24317d90f955405b950efefbb8b303512

# The gitlinks are supplied by the already-committed fork checkouts.
# Apply only the remaining files; bin/lane records both gitlinks in the same commit.
git apply --check --exclude=reference-code/konserve --exclude=reference-code/datahike \
  docs/research/agent-platform/2026-10-14/gc-one-enumeration.patch
git apply --exclude=reference-code/konserve --exclude=reference-code/datahike \
  docs/research/agent-platform/2026-10-14/gc-one-enumeration.patch
```

Root commits **all eight paths together** through its restart-batch lane. The command below names
all green reaching tests; the fault-storage red is reported separately rather than hidden by a
namespace-level selection. Claim the paths before the apply commands above (after this lane releases
its claims). `LANE_ADOPT=1` is the documented root-only claim operation for the already-changed
gitlinks. Use the root's own attribution if it changes the implementation or commit message.

```sh
LANE_ADOPT=1 bin/lane claim gc-one-enumeration-root \
  deps.edn \
  reference-code/konserve \
  reference-code/datahike \
  src/seon/cluster/registry.clj \
  src/seon/maintenance.clj \
  resources/seon/schemas/seon.cluster.registry.edn \
  test/seon/cluster/registry_test.clj \
  test/seon/maintenance_test.clj

bin/lane commit gc-one-enumeration-root --restart-batch --at-candidate \
  -m $'Reuse one GC metadata enumeration across the collector and verifier\n\nModel: GPT-6 (Codex)' \
  --test seon.maintenance-test/collection-results-project-branch-maps-to-components \
  --test seon.maintenance-test/collection-reports-and-verifies-the-exact-store \
  --test seon.maintenance-test/a-branch-that-commits-after-its-root-was-recorded-still-verifies \
  --test seon.cluster.registry-test/blob-lifetime-follows-schema-derived-history-reachability \
  --test seon.cluster.registry-test/non-temporal-collection-marks-current-blob-references \
  --test seon.cluster.registry-test/dry-run-enumerates-candidates-without-deleting \
  --test seon.cluster.registry-test/implicit-retention-requires-declared-cluster-windows \
  --test seon.cluster.registry-test/retention-keeps-every-commit-a-current-fact-names \
  --test seon.cluster.registry-test/retiring-one-cluster-reclaims-only-its-own-tail \
  --test seon.cluster.registry-test/the-plain-collection-keeps-held-values-adopted-commits-and-concurrent-writes \
  --test seon.cluster.registry-test/refusals-name-their-rule \
  --test seon.cluster.registry-test/an-idle-branch-keeps-the-commit-it-forked-from-through-collection \
  --test seon.cluster.registry-test/a-deleted-attempts-storage-is-reclaimed-by-the-next-collection \
  --test seon.blob-publication-test/publication-and-collection-are-exclusive-in-both-orderings \
  --test seon.cluster.armed-test/an-eval-request-after-a-collection-reads-no-collected-value \
  deps.edn \
  reference-code/konserve \
  reference-code/datahike \
  src/seon/cluster/registry.clj \
  src/seon/maintenance.clj \
  resources/seon/schemas/seon.cluster.registry.edn \
  test/seon/cluster/registry_test.clj \
  test/seon/maintenance_test.clj
```

Root's normal batched `bin/seon start --head` follows that accepted commit. Release
`gc-one-enumeration-root` afterward. No staged gitlinks should be left separately.

Foreign boundary: in-flight edits to batch/turn/repl, context, render, source publication and their
schema/test resources were excluded from candidate construction. No other lane was resumed,
messaged, or edited. The independent archive, not the shared working tree, is the proof subject.

### Earlier four-process exploratory measurements (superseded verdict)

These samples triggered the initial stop. They remain evidence, but a single first-run delta was
not sufficient to decide delivery; the owner replaced that decision rule with the seven-pair rule
above. They are not mixed into its medians.

| Run, in execution order | Acquisition ms / MB | First call ms / MB | Warm 1 ms / MB | Warm 2 ms / MB | Warm 3 ms / MB |
|---|---:|---:|---:|---:|---:|
| Parent A | 180.86 / 6.535 | 3,493.89 / 4,640.854 | 2,190.81 / 4,624.945 | 1,927.78 / 4,624.725 | 1,987.97 / 4,624.711 |
| Candidate A | 173.67 / 6.553 | 4,117.51 / 1,629.344 | 1,707.24 / 1,613.017 | 2,358.83 / 1,615.227 | 1,865.29 / 1,615.242 |
| Candidate B | 165.66 / 6.499 | 1,982.61 / 1,628.763 | 1,335.75 / 1,612.941 | 1,418.11 / 1,612.617 | 1,402.25 / 1,612.616 |
| Parent B | 181.71 / 6.535 | 3,467.88 / 4,640.875 | 2,559.97 / 4,624.923 | 2,681.00 / 4,624.721 | 2,813.67 / 4,624.703 |

## Earlier default-store measurement and limits

One completed whole-store dry run, through MCP `eval_clj`, explicit `agent=root`, `cluster=default`,
`mode=jvm`, bridge timeout 120000 ms. The connection was explicitly obtained with
`seon.cluster.boot/connection`; the public maintenance request names the same managed root.
The request set both the required repository root and `:seon.operator.collect/dry-run? true`.
An initial request missing the required repository-root key was refused by its input contract before
collection entered; it performed no collection. The corrected request is preserved in
[the probe](gc-allocation-probe.clj). No real sweep, reset, stop, reload, source definition, branch
creation, or retirement was performed by this lane. MCP itself records JVM evaluations as transactions.

Runtime: PID 2041, started 2026-10-13T10:01:53.730Z; archive
`cde025f68be2c15f497891999d1f62ad6b30ee0c`; checkout HEAD at report preparation
`af11ed4582ef47cd512e647156453b47db0a496d`.
The live resource URL confirms Datahike pin `c9e57c6fb65365d5ce92b59854fb02ea59054fe7`.
Konserve checkout pin: `26444d372bf97de219d55c06d5d23bd6f5cfc634`.
The live and checkout registry source files compared equal.
`bin/seon status` and MCP `runtime_status` both answered; default was already faulted
(one open fault, 35 errored receipts, three agent-error signatures). No tool was missing.

Measured window: 2026-10-14T01:56:04.679255Z–01:56:34.514271Z.
Starting default commit: `6acee134-ea22-5107-aa17-6acac12b19f6`.
Result handle: `result/e7b88e911142b`.

| Full public dry run | Observed |
|---|---:|
| `seon.profile/measure` wall time | 29,818.55 ms |
| JVM-window allocation | 56,382.298128 MB |
| Calling platform-thread allocation | 18,533,671,560 bytes |
| Process CPU window | 32,364.748 ms |
| GC count / accumulated collection time reported by measure | 49 / 189 ms |
| Registry `:mark-duration-ms` | 25,300 ms |
| Logical keys reported | 115,111 |
| Physical store bytes reported | 409,652,017 |
| Candidate keys, derived from projection formula | 75,900 |
| Recorded branch heads | 21 |
| Swept objects / reclaimed bytes | 0 / 0 |
| Roots verified / complete / dry run | true / true / true |

MB here is decimal. Allocation is cumulative allocation, **not retained heap**. JVM counters and the
profile rankings include concurrent work. Two unrelated `seon.cluster.registry/branch!` calls appeared
in the profile, with 21,473 ms summed self time, consistent with the existing sweep-permit contention
class but not an isolated concurrency proof. Branch count grew during this investigation. This was
not a quiet benchmark and cannot establish a performance commit's gain. The 25,300 ms field is not
native mark time: its producer times writer dispatch, native mark, extension, and dry sweep together.
The displayed projected duration was 404,800 ms; its fixed multiplier is already a separate task.

JFR recorded allocation samples at throttle 300/s and execution samples at 10 ms, without changing
production Vars. It produced 8,675 allocation samples and 182 execution samples. Sample weights total
55,249.196456 MB; do not equate them with exact per-phase allocation counters. Async continuations
lose caller frames, and stacks were captured at the JVM's existing depth. In particular, the second
key listing cannot be exactly separated from other async reads from these stacks alone.

| Phase / owner | Time evidence | Allocation evidence |
|---|---|---:|
| Native mark, `datahike.gc/reachable-in-branch` | Allocation samples at +0.433–0.846 s; execution samples through +0.850 s | 330.153 MB with this ancestor visible; incomplete |
| Inventory denominator, `seon.cluster.registry/collect-and-inventory!` → `konserve.core/keys` | Allocation samples at +1.002–13.369 s | 17,323.355 MB attributed by stack |
| Blob extension, `seon.cluster.registry/referenced-blobs` | Two calls; summed instrumented self time 82 ms; samples in both extension and later verification | 986.321 MB attributed by stack, both calls combined |
| Candidate enumeration, `konserve.gc/sweep!` → `konserve.core/keys` | Other listing stacks at +13.847–25.456 s | 1,252.715 MB directly visible; the async read allocation below is not fully attributable |
| Physical byte count, `seon.fs/tree-bytes` | Instrumented self time 317 ms | Not isolated |
| Verification inventory, `seon.maintenance/konserve-key-set` | Instrumented self time 3,372 ms; allocation samples +26.443–29.678 s | 16,891.255 MB attributed by stack |
| Remaining async/other stacks | Across the window | 18,465.396 MB unassigned |

These sample intervals are first/last observed samples, **not exact phase entry/exit times**. Instrumented
self times exclude instrumented callees but include waits. No exact allocation figure is invented for
an uninstrumented async phase. The two directly attributed redundant listings alone account for
34,214.610 MB of sample weight.

A separate read-only native mark probe, using the installed private owner and its shared sets, returned
`result/e16e6a3e21257`. It did not call sweep or repeat the public dry run. On the later, changed store:

| Measured phase | Wall ms | JVM-window allocation MB | Caller-thread bytes |
|---|---:|---:|---:|
| `seon.maintenance/collection-cutoff` | 681.92 | 853.330104 | 851,920,416 |
| All captured branches' native marks | 648.44 | 1,525.900160 | 6,731,256 |

The cutoff was 2026-10-13T10:02:13.568Z. The mark saw 31 branches, 298 visited branch/commit keys,
42,021 index addresses, and 42,338 reachable keys. The visited count includes branch names, not just
unique commit IDs. This phase comparison has different heads and cache state; it is diagnostic only.
It shows why the earlier 27 s figure must not be reported as current native marking cost. Native mark
still allocates substantially; decoder cost and stored record size remain relevant within that 1.53 GB.
Neither warm acquisition nor unrelated work is silently subtracted.

Exact phase form (submit through the same JVM MCP entrance):

```clojure
(let [conn (seon.cluster.boot/connection "default")
      held (:seon.store/store (#'seon.cluster.boot/selected-instance "default"))
      cutoff (seon.profile/measure #(#'seon.maintenance/collection-cutoff held))
      database @conn
      branches (datahike.api/branches conn)
      visited (atom #{})
      seen (atom #{})
      measured
      (seon.profile/measure
        #(let [channels (mapv (fn [branch]
                               (#'datahike.gc/reachable-in-branch
                                 (:store database) branch (:seon.profile/value cutoff)
                                 (:config database) visited seen))
                             branches)
               results (mapv clojure.core.async/<!! channels)]
           (doseq [result results]
             (when (instance? Throwable result) (throw result)))
           {:branches (count branches) :visited-commit-keys (count @visited)
            :index-addresses (count @seen)
            :reachable-keys (count (apply clojure.set/union results))}))]
  {:cutoff (dissoc cutoff :seon.profile/called :seon.profile/ranked-by-calls :seon.profile/ranked-by-self)
   :native-mark (dissoc measured :seon.profile/called :seon.profile/ranked-by-calls :seon.profile/ranked-by-self)})
```

## Owner and proportionality

The read path, at the pinned revisions:

1. `seon.maintenance/collect!` acquires the held store through `seon.maintenance/with-root-store!`.
   `seon.maintenance/dry-run-store!` derives the retention cutoff, inventories, counts physical bytes,
   captures root evidence, enumerates keys again, and verifies roots.
2. `seon.maintenance/collection-cutoff` composes `seon.cluster.registry/retention-cutoff` with
   `seon.cluster.registry/retained-before`. Named exact snapshots can move retention earlier than the
   configured window; removing that work or advancing the cutoff changes what survives.
3. `seon.cluster.registry/collect-and-inventory!` calls `datahike.api/gc-storage`, dispatched by
   `datahike.writer/gc-storage!`. Its extension captures branch heads, lists **all keys just to count
   them**, then derives blob references through `seon.cluster.registry/referenced-blobs`.
4. `datahike.gc/gc-storage!` owns the sweep permit and safe point, clears the schema write cache,
   reads branches, and creates **one shared** visited-commit atom and seen-node atom for this collection.
   `datahike.gc/reachable-in-branch` resolves compact heads through `datahike.writing/read-head`,
   decodes retained commit records, and marks current and temporal indexes. It seeds fused roots into
   storage-bound copies through `datahike.index.interface/with-storage` and
   `datahike.index.interface/-seed-root!`; it does not reload an inlined root as a separate object.
   The index method `datahike.index.persistent-set/mark-unseen` prunes already-seen subtrees.
   Secondary key maps enter `datahike.index.secondary/mark-from-key-map`.
5. `konserve.gc/sweep!` performs the **second full key listing**, selects keys outside the reachable set
   older than the safe point, and returns their keys. The true `:konserve.gc/dry-run?` option takes the
   branch that calls neither deletion callbacks nor deletion operations. The Seon option is translated
   to this precise option by the registry owner.
6. `seon.maintenance/konserve-key-set` does the **third full key listing** for root verification.
   `seon.maintenance/root-verification` distinguishes stored blob references from content digests that
   were never stored, reopens recorded commits, and physically reads stored blobs. Preserve that meaning.

The shared traversal fix already landed as Datahike `25dcaabb7b047dc130decc4864f76c31e5569361`.
The existing proof is `datahike.test.gc-test/marking-with-a-shared-seen-set-walks-each-node-once`.
Reimplementing it would not fix this observation. Some old task prose describes the pre-fix algorithm.
Compact branch names can still cause a head record to be resolved separately from its commit-key visit;
that is not evidence of branch-times-whole-history traversal.

Native work should follow distinct retained commit records and the unique reachable index DAG from
live heads (including temporal roots and the commits retained by policy). Denote their encoded/decoded
volume by C and N. It also visits child address edges of newly visited internal nodes; fixed branching
factor makes this proportional to the visited graph. Blob work follows relevant AEVT subtrees and
schema-derived digest attributes. Sweeping additionally **must** inspect K stored keys, including
unreachable keys: the whole operation cannot honestly promise cost solely proportional to N.
The target is O(C + N + relevant blob data + K), with one K enumeration and no payload-sized scratch
allocation for each tiny metadata item. Each collection recomputes from its captured roots and storage
inventory; the proposal introduces no persistent cache.

### The allocating producer

`konserve.impl.defaults/list-keys` opens every object and calls
`konserve.impl.defaults/read-blob` in `:read-meta` mode. The header chooses a compressor; metadata is
passed through `konserve.compressor.Lz4Compressor/-deserialize`, which creates a fresh
`net.jpountz.lz4.LZ4FrameInputStream`. Its `readHeader` allocates both `compressedBuffer` and `rawBuffer`
at the frame's maximum block size, independent of the small metadata value being decoded.

This is confirmed by the installed lz4-java 1.8.0 bytecode (`javap -c -p`): two `newarray byte`
instructions at offsets 249 and 259 take `maxBlockSize`. Konserve already switched writes from 4 MiB
to 64 KiB blocks in `c73cbde`; simply reducing that default is not an unimplemented fix. Even with
64 KiB frames, two buffers × 115,111 keys × three listings imply **45.26 GB** of buffer allocation
before Fressian and async machinery. This is an explanatory calculation, not a census of frame sizes.

JFR attributes 42,901.821328 MB inclusively to LZ4 input stacks (77.65% of all sampled weight);
49,131.328600 MB is byte arrays. Top identical stacks alone contribute 16,637.751248 MB under
verification key enumeration, 15,693.563728 MB under inventory key enumeration, and 10,397.468768 MB
under async `konserve.impl.defaults/read-blob` continuations. The byte arrays and LZ4 totals overlap.
`konserve.serializers.FressianSerializer/-deserialize` also rebuilds its handler lookup per metadata
decode, but the current evidence makes the buffer allocation the first owner to address.

## Proposed change and required proof

Phase status: phase 1 passes the seven-pair performance proof and is delivered as local fork commits
and the matching Seon restart-batch patch; installation on default is pending root. Phases 2 and 3
remain proposed. The broader task is not complete.

1. **First, remove redundant work without changing storage encoding.** Extend Konserve's existing
   sweep result interface so its one enumeration supplies both candidate keys and the observed key
   inventory/count to callers. Preserve its existing candidate-set API by an explicit additive result
   option or a new named result entry point, selected during interface design. Datahike passes that
   result through; Seon's registry consumes it instead of calling `konserve.core/keys` for a count.
   The dry-run verifier consumes the same inventory instead of enumerating again. For real collection,
   capture the pre-sweep stored-blob evidence at that same enumeration, never infer it after deletion.
   Keep existing cutoff, safe-point, root reopening and error behavior. This deletes two full metadata
   scans; it adds no cache, retry, gate, or second collector. The seven-pair proof above establishes
   the phase 1 gain on the quiet copied fixture; installation awaits root's restart batch.
2. **Then fix remaining per-item decoder cost at Konserve/LZ4.** Investigate the dependency's supported
   decoder options before proposing a replacement. The installed constructor owns two block-sized
   arrays and exposes no caller-supplied buffers in the bytecode inspected. Do not add a pool/cache
   beside `seon.cache`, hand-write an LZ4 parser, or turn compression off globally. Independently encoded
   small metadata is a possible storage-layout change, not a local GC tweak: it needs format/version
   compatibility and a measured win on old and new objects. Existing compressed objects must remain
   readable. No stored encoding change is proposed for immediate implementation.
3. **Make phase evidence truthful at the owner.** Keep the existing duration name's historical meaning
   documented and introduce separately named phase observations if needed. Capture mark, extension,
   inventory/selection and verification windows explicitly. Allocation from JVM counters must retain
   its `:jvm-window` / includes-unrelated-work scope; it must not be advertised as exclusive ownership.
   Publish the declared result schema and its receipt projection together. The task's request for
   allocation beside mark time is not implemented by this research commit.

The performance proof must call the same public `seon.maintenance/collect!` request under
`seon.profile/measure` on parent and candidate, against the same captured store contents in a quiet,
owned disposable root. Keep the dry-run bit true. Measure acquisition separately, and the full first
and warm calls; do not use the 648 ms native-only probe to prove a full-collection improvement.
Compare candidate identities, branch evidence, cutoff, root verification, zero deletion and key counts,
as well as elapsed time and allocation. Exact candidate equality can be checked at the registry
entrance alongside the public proof, without adding bulk keys to the public result.

Tag the new proof with this task and make it red on parent before implementation. Derive its budget
from the fixture's key count and measured necessary decoding cost, then assert both full-call elapsed
time and allocation with `seon.profile/measure`. A successful reduction must also improve measured
end-to-end wall time; two-thirds fewer enumerations alone is supporting evidence. Scale K while
holding the reachable graph fixed, then scale shared branches while holding distinct nodes fixed.
Reuse the shared-mark regression and the existing maintenance root-verification tests. Test old/new
metadata round trips in Konserve if phase 2 changes encoding. No production default sweep is a proof.

The current 29.82 s cost is unjustified ordinary work; two discarded inventories are unnecessary.
One filesystem inventory still visits 115k objects, so a sub-second whole-store guarantee needs a
measured per-key cost, not an invented timeout. This assignment explicitly authorized the slow dry-run
investigation, and the two existing tasks retain the over-ten-second finding.

## Earlier allocation-profile reproduction

The earlier JFR investigation used the stated MCP entrance; do not repeat it on a busy default.
Phase 1 uses the separate owned-store probe and commands above. JFR data is preserved locally at
`tmp/gc-allocation/dry-run.jfr`, SHA-256
`602e43601caf674352036ac574a174ecb7ef95f6c3c9ce191cae4679adbd199c`.
The durable measurements and exact probe are in this commit; the large raw JFR/JSON remain local
diagnostic evidence. To reproduce the committed aggregation:

```sh
jfr print --json --stack-depth 96 --events jdk.ObjectAllocationSample,jdk.ExecutionSample tmp/gc-allocation/dry-run.jfr > tmp/gc-allocation/dry-run.json
python3 docs/research/agent-platform/2026-10-14/gc-allocation-jfr.py tmp/gc-allocation/dry-run.json 2026-10-14T01:56:04.679255+00:00
```

The earlier research-only commit passed lint in 8,730 ms with zero errors and 1,662 warnings,
and the Python aggregation processed all 8,857 recorded events. Its projection-only check did not
prove a performance implementation. Phase 1's current lint, measured proof, fork tests and booted
Seon reaching tests are reported above; the broad tiers remain root's central responsibility.
