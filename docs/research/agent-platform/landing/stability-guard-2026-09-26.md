---
type: landing
lane: stability-guard-opus
created: 2026-09-26
status: "orphan retirement committed (2b4a836fd); memory guard is a patch for root; the 16:10Z retainer is not named"
---
# Landing: stability-guard-opus (2026-09-26)

RESET NEEDED. Default (pid 27723) runs an archive that has neither change.
Root must apply `tmp/stability-guard-opus/memory-guard.patch` and then run
`bin/seon start --head`.

## Broken or unproven, first

1. **The 16:10Z retainer is not named.** Root told me not to parse the dumps,
   and I stopped my MAT parse of `java_pid19859.hprof` while it was still in
   the object-extraction phase. I deleted its partial index files. What
   remains is the dump's thread records, which the parser writes during its
   first scan. They are kept at `tmp/stability-guard-opus/java_pid19859.threads`.
   The post-mortem below uses those records, the log and root's live facts.
   Root's MAT dominator result on `live-1725.hprof` has not reached me.
   Fold it in when it arrives.
2. **The memory guard is not committed.** It needs `src/seon/sci/kernel.clj`
   (held by unrestrict-sci-opus / repl-parity-opus-2), its schema resource,
   and a `src/seon/test.clj` hunk. My `boot.clj` hunk calls kernel functions
   that do not exist yet, so committing it alone would stop HEAD from loading.
   The whole guard is therefore one patch: 5 files, +194/−4, `git apply --check`
   clean on HEAD 2b4a836fd.
3. **Over the ~100-line bound, and nothing is deleted.** The guard is about
   60 code lines in the kernel and 35 in boot; the rest is docstrings, schema
   and the regression. It is a new capability the owner asked for. It
   duplicates no other path, so there is nothing to delete. The smaller
   alternative is the JVM's own `-XX:+ExitOnOutOfMemoryError`, which is
   already in place. It is why the process dies rather than wedges, but it
   refuses nothing early. Root rules.
4. **Unrun:** both regressions (the LOAD CAP, and default cannot load the
   code). The own-arm refusal wiring is not covered by a test, because
   exercising it needs process-wide pressure; see the root checks below.
5. **Retire sacrifices** (by design, from inv-lifecycle's option 1): anything no
   fact claims is lost at restart. That covers an unaccepted merge
   candidate (`merge-<id>`, the proposal is a REPL value), a checkpoint, and
   an MCP branch created but never hosted. In a JVM hosting two clusters,
   the second cluster's boot can also retire the first cluster's
   created-but-not-yet-connected branch.

## 1. Post-mortem: 16:10Z (pid 19859, booted 16:02Z, died about 8 min later)

The log (`data/clusters/default/logs/seon.log:107409-107471`) shows three things.

- **Boot and agent faults.** Boot happened at 16:02:16. Agent turns then
  panicked as core faults:
  - String→Keyword and Symbol→Keyword casts in `seon.cluster.agent/turn-step`;
  - `:seon.fn.contract/finding` written as an uninstalled attribute (16:06:58);
  - clj-kondo alias misses for `str/` and `effect/` (16:07:25).
- **A test request.** One ran: `seon.effect-test`, then `seon.reset-edges-test`
  at 16:07.
- **The crash.** OOM, then a 17.7 GB dump in 5.5 s.

The dump's thread records show 86 threads at the moment of death:

| thread | doing |
|---|---|
| agent turn → `evaluate` → `acquire!` → `base-ctx` → `derive-base-ctx` → `acquire-program!` (eval.clj:2620) → `seon.fn/reverse-closure` → `gate-sets-in` (fn.clj:1807) → `db/q` | building a transient set from a whole-program query. This is the base-context derivation (the "base-ctx derivation" named in the brief), and it was allocating when the JVM died |
| agent turn → `evaluate-for-install` → SCI eval | throwing (building an `ex-info` stack trace) |
| 2 × prepl `mcp-runtime-observation` → `boot/readiness` → `problems` → `turn/episode-runs` → `db/q` | two concurrent `runtime_status` calls |
| web `graph-debug-html` → `render.walk/graph` → `owning-schemas` → `db/q` | debug graph page render |
| prepl `seon.cluster.agent/disarm!` | waiting on a delay |
| 19 Flow executor threads, 2 turn-completion backstops | parked |

Absent at death:

- `datahike.gc` frames: inv-lifecycle-storage's 99-branch reachable-set probe
  was not running.
- `seon.test` frames: no test body was live.

So neither of those was the allocator at the end.

Refuted: the Datahike query-result cache as an unbounded retainer. On the
live JVM, `datahike.query/query-cache-metrics` reads 64 entries and weight
864,026 against a limit of 1,000,000. It is bounded (`query.cljc:2457-2534`).

**Verdict: not determined as a single retainer.** The evidence points to low
headroom plus a concurrent spike:

- Root's live facts show the idle JVM now holds 3.7 GB after a full GC, against
  1.4 GB earlier today. That is 6.8 M Datoms against 498 k at the head, held
  outside the indexes in vectors, plus about 2.5 M Malli validator closures and
  two generations of instrument wrappers kept alive by a reload.
- On top of that ran a whole-program base-context derivation, an agent
  install, two status reads, a debug page, and a test request.

The dominator tree names the baseline retainer. The guard below bounds the
spike regardless.

## 2. Guard: the JVM refuses new work before it dies (patch)

**Mechanism: the JDK's own crossing notice.** `kernel/watch-memory!` does two
things, once per JVM:

- It sets the tenured pool's collection usage threshold with
  `MemoryPoolMXBean.setCollectionUsageThreshold`. The pool is found as the one
  heap pool that supports a usage threshold; the JDK documents that eden-like
  pools do not suit one. It is `G1 Old Gen`, verified live. The threshold is
  70 % of the pool's maximum (10,997,465,088 bytes on default).
- It registers a `NotificationListener` on the `MemoryMXBean` (a
  `NotificationEmitter`) for `MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED`.

The JDK behaviour this relies on:

- The JVM emits the notification after a collection leaves the pool at or
  above the threshold.
- It emits again only after usage has fallen below the threshold and
  crossed it again.
- `MemoryPoolImpl.java` `setCollectionUsageThreshold` registers the pool's
  `CollectionSensor`, and `CollectionSensor.triggerAction` creates the
  notification.

Nothing polls.

**The one admission.** The check is in `kernel/own-arm`:

- Every bounded SCI execution takes its arm there: agent runs, MCP SCI
  evaluations (runs of root or a hosted agent), test bodies
  (`sci.eval/run-test`) and render calls.
- Nested work inherits the governing arm and never reaches `own-arm`, so
  pressure refuses new outer work and cuts nothing already admitted.
- This includes the nested hosted runs of the 14:20Z recursion: each ran on its
  own Flow thread with its own arm.
- The unpressured path is one volatile deref, before any contracted call.

`seon.test/run` asks the same decision before it builds a request world, so a
refused request builds nothing. JVM-mode prepl evaluation is not armed, so it
stays available for diagnosis. That is deliberate.

**Refusal.** `:seon.sci.kernel/memory-pressure-error` is a flat error over
`:seon.error/base`. It carries:

- the pool, the used, maximum, enter and resume bytes, and `entered-at`;
- `in-flight`: the `[agent-id run-id]` of every open run on every running
  cluster at the crossing.

Its message states all of these.

**One core fault per crossing.** `boot/memory-pressure-crossed!` runs on the
JVM's notification thread. It:

- enters the pressure state; a crossing that happens while the pressure is
  already held answers nil and records nothing;
- prints `SEON MEMORY PRESSURE: …` to stderr;
- records the refusal as one core fault on every running cluster, through
  `fault/record!` with the uncaught-handler's proven source shape.

If recording fails, `uncaught-emergency!` prints it and never throws.

**Resumption.** The resume bound is 55 %. Only a held pressure re-reads
`getCollectionUsage`, and it clears once usage is below the bound. The next
admission attempt or `(kernel/memory-pressure)` does that re-read, so no
second listener and no timer is needed.

**Holder, justified.** The state lives in the kernel, the process-scope owner
of arming (the process guard, the deadline timer). It is a `defonce` volatile
beside the guard, not a key inside it: the guard is a `defonce` value that
reload never rebuilds, so a key added to it would be absent after adoption by
reload, and every arm would NPE. Nothing else can hold the state:

- The JVM keeps none that a volatile read can answer.
  `isCollectionUsageThresholdExceeded` calls into the VM and allocates a
  `MemoryUsage`.
- A Flow proc cannot be read synchronously by every arm.

**Evidence (REPL, default, read-only):**

- The pool resolves to `G1 Old Gen`; collection usage is 1.10 GB of 11.0 GB;
  the threshold was 0 (unset).
- A real pressure value and its refusal both validate against the new schema
  forms composed with the live projection registry (`m/explain` → nil).
- The in-flight query answered `[["root" "f4d2c7cf8eab"]]` in under 7 ms.
- The patch applied to a `git archive` of HEAD passes
  `seon.contracts-compile-test/check` (0 findings, 526 ms). The schema EDN
  reads, and clj-kondo reports 0 errors.
- An earlier draft of the EDN failed this check, because a schema member was
  inserted inside a map. The fix is in the patch.

**Regression** (in the patch):
`seon.sci.kernel-test/memory-pressure-holds-until-usage-falls-below-its-resume-bound`.
It holds its own state value and never pressures the process it runs in.

**Limit.** G1 updates old-gen collection usage only after mixed, full or
remark collections. If in-flight work ends while the JVM is idle, the
pressure can hold until the next old-gen collection. The JVM REPL stays
open: `(System/gc)` there re-reads it.

## 3. Orphan branches: committed 2b4a836fd

`registry/retire-unclaimed!` (+44 lines) retires every roster branch that no
fact claims and no connection in this process holds. The claims are:

- the caller's declared branches (boot passes `source/current-branch`);
- `:db`;
- each cluster branch, meaning a branch whose own `:seon.cluster/name` row
  names it through `cluster.branch/cluster-branch`. Forks inherit that row, so
  the comparison, not the branch's spelling, tells a cluster from its forks;
- each agent row's `:seon.agent/branch` on a cluster branch with no
  `:seon.agent/admission-closed-tx`.

`boot/stand-boot-layers!` calls it after publication and before any branch
is acquired, and publishes the set on the instance as
`:seon.cluster.registry/retired`.

The REPL session found one edge case, now fixed: `d/datoms :avet
:seon.cluster/name` throws on `:db` and `:current-src`, which install no such
attribute, so the claim read is a query.

Measured read-only on default's live store:

- 107 roster branches;
- claims: `cluster-default`, `current-src`, `db`, 11 open agent branches;
- 93 would retire: 48 `agent-*`, 42 `host-*`, a checkpoint,
  `dogfood-analyst-astra-2` and `merge-b90fd4f9b09e`;
- **439 ms**, once.

Steady state is about 4.5 ms per branch, so about 50 ms at about 10 branches.
The mark cost falls with it (6.5 s at 99 branches).

Regression: `seon.cluster.registry-test/boot-retires-only-branches-no-fact-claims`.
It covers the cluster, an open agent, a closed agent, an orphan fork (which
inherits the name row), a connected unclaimed branch, and a second pass that
finds nothing.

## 4. Stabilization note

| crash class today | what bounds it now | still unbounded |
|---|---|---|
| 14:20Z recursive install gate (symbol mention selected the hosting test → nested test worlds) | call-reach selection (20a87b1d1); an unexited member blocks further bodies (9fc60b979); the guard refuses each new nested hosted run's arm once old gen crosses 70 % | a compiled host test body inside an arm: SCI cannot interrupt host code, so an admitted body runs to its own end |
| 16:10Z concurrent heavy users on a 3.7 GB retained baseline | the guard refuses every new armed execution and test request above 70 % old gen after a collection, loudly, naming the open runs, with one fault per crossing | JVM-mode prepl work (probes, `runtime_status` internals, lane scripts) is unarmed; the web page's host-side derivations; the baseline retention itself (root's MAT) |
| store growth from dead-process branches (36 % of keys) | boot retires unclaimed branches (2b4a836fd) | branches of work dying mid-run in a live JVM until the next boot |
| root's graph stops on one refused write | fix-root-graph-panic-opus | — |
| agent hang on an alias | 71d3d74c5 | — |

Still unbounded, by nature:

- a host call SCI cannot interrupt (timeouts are not termination);
- allocation inside one admitted evaluation, since the guard is admission and
  not a per-evaluation heap quota;
- work that is not armed.

## Root runs (after applying the patch and `bin/seon start --head`)

```
git apply tmp/stability-guard-opus/memory-guard.patch
bin/test-check default --test seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection
bin/test-check default --test seon.schema.edn-test/every-packaged-schema-resource-reads-as-edn
git commit --only -m "..." -- src/seon/sci/kernel.clj resources/seon/schemas/seon.sci.kernel.edn src/seon/test.clj test/seon/sci/kernel_test.clj src/seon/cluster/boot.clj
bin/seon start --head      # boot logs the retire; expect ~93 branches retired, ~0.44 s
bin/test-check default --test seon.cluster.registry-test/boot-retires-only-branches-no-fact-claims
bin/test-check default --test seon.sci.kernel-test/memory-pressure-holds-until-usage-falls-below-its-resume-bound
```

Live checks, JVM mode:

- `(:seon.cluster.registry/retired (get @seon.operator.runtime/running-instances "default"))`
- `(.getCollectionUsageThreshold <G1 Old Gen pool>)` should be about 7.70e9.
- `(seon.sci.kernel/memory-pressure)` should be nil.

## Numbers

- Committed: 2b4a836fd. registry.clj +44, boot.clj +4, schema +1, test +32
  (net +81, nothing deleted).
- Patch: +194/−4 over 5 files.
- Probes on default: 6 read-only JVM probes, each under 0.6 s.
