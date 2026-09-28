---
type: landing
status: landed (kernel e7014b3a6, db bd6e4595e); is-message sweep patch pending
lane: heap-retention-opus
date: 2026-09-27
base: b718d78c5
---
# heap-retention-opus: what default's old gen holds after test rounds (2026-09-27)

## Broken first

- **The memory-pressure admission counts garbage as live.** `seon.sci.kernel/collection-usage`
  reads `G1 Old Gen`'s collection usage. On default (JDK 26.0.1, G1), the `G1 Young Generation`
  collector manages `G1 Old Gen` (probed through `GarbageCollectorMXBean/getMemoryPoolNames`), and
  after a young pause the collection usage equals current usage (2,041,047,320 bytes for both). So
  the "after a collection" number includes every promoted object that no marking has reclaimed yet.
  G1's adaptive IHOP lets that garbage stand while the heap has room (IHOP was about 31 % in the
  r10 log). The 8.53 GB that r9 refused on was not live data. Fix: patch
  `tmp/heap-retention/kernel-live-pressure.patch` (kernel.clj is held by one-path-impl). Task:
  `memory-pressure-crossed-during-test-rounds` (revised in `resources/seon/tasks/seon.sci.kernel.edn`).
- **Default died of OOM during this lane (pid 42397, 00:07:10Z).** The cause was
  `Requested array size exceeds VM limit`: a 2 GB `byte[]` inside a `StringWriter`. It was on the
  prepl thread `Clojure Connection seon.cluster/default 1`, and the stack was
  `user$eval…/verdict → pr-str`. That matches `tmp/red-triage-r8/proof.clj`, whose `verdict`
  calls `(pr-str (:actual event))` and only then clips the text with `subs`. The value printed was
  a failing `(every? #(identical? (:ctx context) %) @seen-contexts)` from `seon.render.web-test`,
  so its `:actual` holds SCI contexts. The rest of the heap was 2.2 GB. This is not retention. It is
  unbounded printing outside the floor printer (AGENTS.md "nothing prints unbounded"). The dump and
  MAT indexes are kept in `data/heap/analysis-42397/` (10 GB). Root restarted default as pid 51590
  at 00:09:52Z.
- **Two arity caches sat outside the byte budget.** `seon.db/arity-commit-cache` (core.cache LRU
  16) and `arity-base-cache` (LRU 4) retained 207 MB and 64 MB in the OOM dump. One base on default
  now measures 108,330 edges and 8,094 callers, which is 58 MB at the calibrated 540 B/edge, and a
  cold build takes 452 ms. Sixteen commit entries can each pin a distinct base, so the count bound
  permits about 0.9 GB. Fix: patch `tmp/heap-retention/db-arity-central-cache.patch`, which moves
  both kinds into `seon.cache` under its one byte dial. db.clj is held by boot-source-perf.

## Retained breakdown (live bytes, `jcmd GC.class_histogram`, which forces a full GC)

| point | live heap | note |
|---|---|---|
| pid 42397, 11 min after boot | 1.53 GB | baseline |
| + read-evidence, profile, db tests (107 members) | 1.77 GB | |
| + sci.eval-test (50 executed) | 2.37 GB | |
| + turn-test (8 executed) | 2.26 GB | falls, so it is not monotonic |
| pid 51590 after the whole r10 round (16 nss, about 350 members) | **1.98 GB** | old gen 1.88 GB; peak old after any young pause in r10: 3.24 GB |
| OOM dump of 42397, excluding the 2 GB print buffer | 2.2 GB | |

Owners in the OOM dump (MAT, retained):

| owner | retained | bounded by |
|---|---|---|
| `seon.cache/process` | 248 MB | 512 MB estimate dial (it reported 290 to 355 MB) |
| `seon.db/arity-commit-cache` + `arity-base-cache` | 207 + 64 MB | entry counts only (fixed by the patch) |
| `datahike.connections/*connections*` | 109 MB | 2 to 7 open connections |
| Datahike node cache | 2.9 MB, **one instance shared by all 10 CachedStorages** | the fork already shares it (67b00071, pinned in 591c825d) |
| `seon.db-test/fixture-projection` | 10 MB | test namespace def |
| `seon.sci.eval/base-context-cache` | 7 MB | LRU 4 |
| `seon.db/projection-cache` + value tier | 3.1 + 1.0 MB | ruling B (boot-source-perf); numbers only |

After the rounds, the rosters were clean. There were 2 to 3 Datahike connections (cluster, `:db`,
and one agent branch). Test members opened and released their own. `running-instances` held only
`default`. The `tfp-sel`, `tfp-sel2` and `tfp-sel3` branches are unconnected roster entries from
test-feedback-perf's MCP retire NPE. They hold no heap. Retention GC reclaims their store once they
are unlinked (root's call).

## Verdict

This is **not a leak**. Live bytes after a full 16-namespace round are 1.98 GB, against 1.53 GB at
boot, and they fall between namespaces. The 8.3 to 8.9 GB readings were uncollected garbage that
the pressure gate took for live data. The one real byte-unbounded holder was the arity caches (up
to about 0.9 GB permitted).

Task `4e824da5bfb7` (a per-connection node cache of about 350 MB per branch) is already fixed by
datahike 67b00071, and the dump shows one cache shared by 10 storages. I deleted it from
`seon.cluster.registry.edn`.

## Patches for root (held files)

1. `tmp/heap-retention/kernel-live-pressure.patch` (kernel.clj, +14/-8 → one-path-impl).
   - `enter-memory-pressure!` runs one `System/gc` per crossing notice.
   - It enters only when the collection still reads at or above `::enter`.
   - The full collections observed here took 0.1 to 0.5 s at 1.5 to 2.4 GB live.
   - Resume is unchanged: it re-reads on each admission while pressure is held.
2. `tmp/heap-retention/db-arity-central-cache.patch` (db.clj, db_test.clj → boot-source-perf).
   - Deletes both `defonce` LRUs.
   - Adds the kinds `:seon.db/arity-base` (content key) and `:seon.db/arity-commit` (commit id)
     through `seon.cache/lookup`, weighed by `arity-base-weight` (540 B/edge + 100 B per key datom).
   - The seed in `report-arity-comparison` goes through the same kind.
   - `a-write-seeds-its-final-values-arity-base` reads `seon.cache/retained`.
   - Net +10 lines. Both new private fns are contracted, and the contracts compile against default's
     projection.
   - Tests root should run: `seon.db-test/a-write-seeds-its-final-values-arity-base` and the
     arity-gate members of `seon.db-test`.

## Limits

- Root applied both patches (kernel e7014b3a6, db bd6e4595e). This lane has not measured a round on them.
- After-install proof: repeat r10 and read the peak collection usage and the live histogram.
  Expect no pressure fault, and `:seon.db/arity-*` bytes listed in `seon.cache/status`.
- Forced full collections in this lane:
  - 2 on pid 42397 (baseline and after the small round), plus 2 more inside the heavy round's
    per-namespace histograms before the OOM;
  - 1 on pid 51590 after r10.

## The four heap dumps (analyzed offline with MAT, then deleted 2026-09-28)

Every dump was parsed with MAT `ParseHeapDump.sh … org.eclipse.mat.api:suspects`. Queries used
`org.eclipse.mat.api:query` (`path2gc`, `dominator_tree`, OQL over `clojure.lang.Var`). The dumps
and indexes held 27 GB in `data/heap/` and were deleted on the owner's approval. This section is
what remains of them.

### `analysis/live-1725.hprof`: idle default (2026-09-26, 1.5 GB used, 2.7 GB file)

- **Cause:** none. This was an idle snapshot taken by mem-idle-opus, not an OOM. The waste it
  showed:
  - three per-connection Datahike node caches (347 + 24 + 1.2 MB);
  - arity bases (92.6 MB);
  - the render cache atom (69.8 MB);
  - two instrument-wrapper generations pinned by one in-flight SCI context (212 MB).
- **Fixes:**
  - malli `a89ebef0` / seon `8099d9c50`: wrappers share the registry's cached validator.
  - datahike `67b00071`: one node cache per physical store.
  - `bd6e4595e`: arity bases moved into `seon.cache` (this lane's patch).
- **Details:** `landing/mem-idle-2026-09-26.md`.

### `analysis-63809/oom.hprof`: OOM "Java heap space" (2026-09-27 ~17:29Z, 10.9 GB)

- **Cause:** a corpus item `(range)`. After the evaluation disarmed, `seon.turn/unbound-value?`
  walked its live result with `tree-seq`: no bound, no interrupt. One realized `LazySeq`/`Cons`
  chain held 115.9 M Longs and retained 10.2 GB (93.7 %). Its only root was the turn thread's
  local.
- **Fix:** `9e61340b2`. Nothing walks a result after evaluation (task `d1efccd3ab7e`).
- **Details:** `landing/oom-63809-2026-09-27.md`.

### `analysis-42397/oom.hprof`: OOM "Requested array size exceeds VM limit" (2026-09-28 00:07Z, 8.1 GB)

- **Cause:**
  - A 2 GB `byte[]` in a `StringWriter`, on prepl thread `Clojure Connection seon.cluster/default 1`.
  - The stack was `user$eval…/verdict → pr-str`. The source is red-triage-r8's JVM probe
    `tmp/red-triage-r8/proof.clj`, whose `verdict` printed each failure's `:actual` whole, then
    clipped it with `subs`.
  - The value printed was a failing `(every? #(identical? (:ctx context) %) @seen-contexts)` from
    `seon.render.web-test`: SCI contexts.
  - The rest of the heap was 2.2 GB (`seon.cache` 248 MB, arity caches 271 MB, connections 109 MB).
- **Fixes:**
  - The probe now prints with `*print-length*`/`*print-level*` bounds (red-triage-r8,
    `tmp/red-triage-r8/proof.clj:95,127`).
  - Print owners and the `:discouraged-var` lint (`f3b05eb31`) name every bare print.

### `analysis-3360/oom.hprof`: OOM "Requested array size exceeds VM limit" (2026-09-27 21:07 local, r13, 10.6 GB)

- **Thread and stack:**
  - The thread was a virtual thread: a test member's body thread.
  - `Arrays.copyOf ← StringBuffer.append ← StringWriter ← clojure.core/pr ← pr-str ←
    seon.sci.branch_execution_test$fn…` at `branch_execution_test.clj:269`, under
    `seon.db/call-with-custody`.
- **Value built:** a 2,147,483,645-byte string. It began
  `{:seon.schema/projection {:seon.schema.projection/function-contracts {seon.cluster/dropped-summary …`,
  so this was `installed`, the value `seon.sci.eval/install-row!` returned, and it carries the
  member's whole schema projection. The heap besides it was 1.65 GB (3.8 GB used): index `Leaf`s
  522 MB, no leak.
- **Producer:** `(is (= :unavailable (:seon.sci.eval/load-state installed)) (pr-str installed))`.
  - A `clojure.test/is` message is evaluated on every assertion, passing ones included, so the
    projection was printed whole on each run.
  - The print lint did not stop it, because `:discouraged-var` is a warning. The residue in `test/`
    and `script/` was 1,498 unbounded-print warnings, 915 of them bare printers in `is` messages.
- **Decision: yes, both must go through the value printer.**
  - **Test assertion messages:** `seon.print.value/bounded-text`, as an error-level lint.
  - **The runner's own output:** already bounded. `seon.test.runner/failure-message` prints
    `:expected`/`:actual` through `report-value` under one budget (runner.clj:182–206). Its
    remaining `pr-str`s are sort keys over symbols and the first ten digests.
  - **What the runner cannot bound:** a message the test body already built before the runner saw
    it. So the lint belongs at the message.
- **Fixes:**
  1. `85b4de483`: `test/seon/sci/branch_execution_test.clj` (unheld). Its 3 messages and 2
     diagnostic prints go through `seon.print.value/bounded-text`. A value holding the projection
     now prints as 401 characters in 0.5 ms (measured on default).
  2. `tmp/heap-retention/is-message-bounded-sweep.patch`, from the scripted sweep
     `tmp/heap-retention/is_message_sweep.clj` (rewrite-clj; `bb … --write FILE…`):
     - **Scope:** 970 messages in 200 test/script files.
     - **Rewrite:** every `pr-str` in an `is` message becomes `seon.print.value/bounded-text`, and a
       multi-argument `(pr-str a b)` bounds `[a b]`. `seon.print.value` is added to each ns require.
     - **Lint gate:** a new kondo hook `.clj-kondo/hooks/is_message.clj`, registered in
       `.clj-kondo/config.edn` as `:seon/unbounded-assertion-message` at `:error`. It refuses any
       bare printer (`pr-str`, `prn-str`, `print-str`, `println-str`, `with-out-str`, `pprint`) in an
       `is` message.
     - **Verified:** from a `git archive` of HEAD plus the patch, `clj-kondo --lint test script`
       gives 0 errors, and unbounded-print warnings drop from 1,498 to 587. src lint is unchanged
       (770 warnings, 0 errors). The hook flags 975 sites on HEAD and 0 after the sweep.
     - **Routing:** root applies it at once. The files span many holders and it is one mechanical
       sweep, and `config.edn` is print-owners-opus's.
- **Still open:** a message built with `str` over a large collection is equally unbounded
  (`str` prints through `RT/printString`), and no var-level lint can tell that from text.
