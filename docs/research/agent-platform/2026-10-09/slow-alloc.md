---
type: research
status: measured
date: 2026-10-09
lane: slow-alloc
---

# Slow and allocating operations: what each one is proportional to (slow-alloc, 2026-10-09)

**What exists.** Five overnight cost reports (A my.test/check 6.8 GB for 17 tests, B proof reads 2 GB for 3 tasks, C
`my.program/write-branch!` 763 MB, D `my.program/search` 150 MB and `my.program/sizes` 110 MB, E the store's 767,709
files) re-measured on default (pid 40778, archive `f54e73c83`, started 2026-10-08T21:32Z) through `eval_clj`. **Method.**
Per-call allocation is the calling thread's (`seon.profile/measure`, the reply's `:alloc-mb`); JVM-wide allocation is the
sum of `ThreadMXBean.getThreadAllocatedBytes` over all threads (idle background: 0.08 MB/s, so a bracket is clean);
attribution is a JFR `jdk.ObjectAllocationSample` recording around one request (aggregation scripts were scratch).
**Principal finding.** There is no single shared cause, but there are two that account for A-D and one reporting gap that
hid them: whole-population work where a bound lookup would do (P1), index nodes re-read from disk and decoded
(LZ4 + Fressian) because the shared node LRU is 8,192 nodes against 772,000 stored (P5), and a reply cost that counts
only the evaluating thread (P6).

## 0. Broken or surprising first

- **The reply's `:seon.cost/alloc-mb` is the evaluation arm's allocation only** (`seon.batch/arm-measures`). One
  `defn` plus one `deftest` request reported 22 MB; JVM-wide it allocated **1.0 GB** (JFR 1.05 GB: 709 MB on virtual
  threads, i.e. the test step, 296 MB on `async-mixed` pool threads, 37 MB on the writer thread). Every number the
  overnight reports quote for `my.test/check` and tests is therefore a floor, and an agent cannot see the cost it
  caused (P6). Task `seon.batch/a-request-cost-counts-only-the-evaluation-arm`.
- `seon.task-ingestion-test/an-assignment-reads-only-its-proofs` is red in the real runner at 23.4 MB against 8 MB; a
  JVM-mode replica of it is not evidence (it refuses `:unknown-task`). See §B.
- Datahike clause order is not planned for the `sizes` joins: moving the bound clause first cut a call from 79 MB / 60 ms
  to 11.8 MB / 6 ms (landed, §D).

## A. `my.test/check`: about 400 MB per reaching test, 6.8 GB for 17

**Reproduction.** On a disposable agent branch (`slow-probe4`) a new `defn` plus `deftest` in a scratch namespace;
the request's test step runs that one test. Reply: `:alloc-mb 22.4`, `:test-step 653-1200 ms`, `:reads 408-440`,
`:transactions 4`. JVM-wide bracket: **1,004 MB** (one run; a second bracket by thread name summed about 3.9 GB, other
lanes were active, so 1.0 GB is the clean figure). `(my.test/check {})` afterwards, tests unchanged: 8.7-14.5 MB, 10-25 ms
(reuse by reach digest works). So the cost is the execution of a *new* test, and it is per test, which is what 6.8 GB over
17 tests says (400 MB each).

**Where (JFR, one trivial test, 1,049 MB).** Leaf-most Seon owner: `seon.db/vector-query-call` 137 MB (Datahike query
execution beneath `seon.db/q`), `seon.db/declaration-content-key` 63, `seon.schema/projection-admissions` 47,
`seon.db/datoms->data` 38, `seon.schema/projection-entities` 33, `seon.test.runner/reach-attribute` 28,
`seon.db/pull-many-plan-with-evidence` 27, `seon.sci.eval/base-bindings` 18, `seon.db/write-owned-values-error` 17.
Inclusive: `seon.db/with-declarations` 205 MB (every `db/q` and `db/pull` runs it: 440 reads), `seon.schema/load-projection`
67, `seon.sci.eval/regenerate-agent-context!` + `fork-for-turn` 41, `seon.cluster.agent/acquire-context!` 30.

**Proportional to.** The program: each fresh test world re-acquires the schema projection and the reach attributes
over the whole declaration population (`projection-entities`, `reach-attribute`, `declaration-content-key` are
O(declarations)), plus a per-read declaration check (`with-declarations`) and four writer validations
(`write-owned-values-error`). **Expected cost** of running `(is (= 2 (tiny-a 1)))` on a fork: a branch fork, a context
fork, one eval, one recording transaction, a few MB. **Gap** about 100-200x. **Pattern** P1 (whole program per test),
P2 (per-read wrappers), P6 (the cost is invisible in the reply). This is the same class as the landed revision reuse
(`44cce8fe4`, [check-alloc-astra.md](../2026-10-08/check-alloc/check-alloc-astra.md)) and cards CO-4/CO-5; this lane
added the missing measurement: the test step is outside the reply's number. **Not fixed here** (over 15 minutes,
platform code). Existing tasks own the fix; the new reporting task is `seon.batch/a-request-cost-counts-only-the-evaluation-arm`.

## B. Reading a task's proofs: the reach acquisition, 500 MB the first time

**Reproduction.** `seon.test/verified?` (what `seon.task/assign!` calls per proof when the recorded result is green)
calls `seon.test.runner/reach-digests`. Measured on the default head, a recorded-green proof:

| Call | alloc | ms |
|---|---|---|
| `seon.task/task-edges` (3 indexed queries) | 1 MB | 1 |
| `seon.test/recorded-result` | 0 | 0.9 |
| `reach-digests` of one proof, **first on this head** | **499 MB** | **307** |
| same proof again, same or another agent | 1 MB | 0.3-4 |
| a second proof, same head | 2 MB | 7 |
| a third proof whose closure is not cached | 12 MB, or 182 MB when asked for with the others | 11-166 |
| after one `defn` on the branch, the first proof | 104 MB | 84 |

So 2 GB for 3 tasks is three cold acquisitions of the program's reach attributes: the cache works (content keyed) but
every new head, every changed branch and every evicted entry pays the whole program, 500 MB cold and about 100 MB after one
edit. **Proportional to** declarations in the program (8,433 on this head), not to the proof's closure. **Expected**: a
closure walk of one test, 1-2 MB (what the hot rows show). **Gap** 50-500x cold. **Pattern P1**, the one the cost-design
cards CO-4 (decode) and CO-5 (demand acquisition of the closure) already own.

**The named proof.** `an-assignment-reads-only-its-proofs` through the real runner (`bin/seon check --test`, a throwaway
JVM from the candidate archive `f2c0b15d2`): red, `executed 1 / pass 1 / fail 1`, the assignment is written and the
bound fails: **23,355,128 bytes** against 8,388,608, 8.9 s for the run. (The task text recorded 11.7 MB at another
state and 64.4 MB on the cluster, so the figure moves with what the fresh world has already acquired, which is the
cold-acquisition signature of the table above.) My JVM-mode call of the same deftest is not the runner: it fails on
the first assertion (the assignment is nil, `:unknown-task`), so a JVM-mode replica proves nothing here and the
profile of that call (1.0 MB, 310 calls) is of a refusal. The 23 MB is `verified?` -> `reach-digests` over the test
world's program plus the schema projection of a fresh store, not the two `task-edges` queries (1 MB).

## C. `my.program/write-branch!`: 170 MB warm, 310-470 MB cold, before it writes anything

**Reproduction.** On a branch with scratch changes and a scratch root, the call refuses ("Each change needs its source
and one file.", my scratch namespace has no file) after doing its comparison. Cold: 313.7 MB / 268 ms (JVM-wide 470 MB).
Repeated: 169-178 MB / 106-132 ms, constant (three runs).

**Where (JFR, warm call, 317 MB).** About **108 MB is konserve + LZ4 + Fressian decoding of persistent-sorted-set nodes**
(`konserve.compressor.Lz4Compressor`, `FressianSerializer`, `org.replikativ.persistent_sorted_set.fressian`, leaf-most
`incognito.fressian` 74 MB, `LZ4FrameInputStream` 16): nodes read from disk, not found in the node cache. The Seon owners
above them are `seon.cluster.source/commit-database` (31 MB leaf) and `merge-base` (27), `datahike.versioning` (35): the
merge-base walk opens commit values. Another 42 MB is `seon.sci.eval/regenerate-agent-context!` (the call's own world
preparation) and 129 MB is the armed wrappers (`seon.instrument/arm-var!`, `call_preparation/preparing`, 93 MB inclusive).

**Proportional to** the commits between the branch and the head plus the nodes each commit value must materialise; not to
the number of changed declarations (none was written). The overnight 763 MB is that plus analysis of written files.
**Expected**: the diff of a branch with N changes should read the N changed entities, not decode nodes; 10-20 MB. **Gap**
about 10x warm. **Pattern P5** (the node LRU, §E) with P1 (the walk reopens every commit's index from disk).

## D. `my.program/sizes` and `my.program/search`

**`sizes` (landed).** The query put the *unbound* clause first: `[?e :seon.fn/ns ?ns] [?ns :seon.ns/name ?n]`, so
Datahike joins every function's namespace edge before the name filter, in two queries (functions and tests). The cost was
independent of the namespace asked: an empty namespace `seon.task.x` cost 83.6 MB; `seon.profile` 84.8, `seon.cluster.store` 87.4, `seon.blob`
83.0 (the 291-666 MB first readings were the cold node reads of §C/§E on top). Direct queries, clause order only:

| Query (namespace seon.sci.lookup, 20 functions) | alloc | ms |
|---|---|---|
| original order, with source | 46.0 MB | 30 |
| name clause first, with source | 5.0 MB | 3.6 |
| tests, original / name first | 23.8 / 2.4 MB | 12.3 / 1.3 |

Fix: put `[?ns :seon.ns/name ?n]` first. Proven on branch `slow-probe4`: `(my.program/sizes 'seon.cluster.wake)` with
the old order 79.3 MB / 60 ms, the new order on a different namespace 11.8 MB / 6 ms, and the tests reaching the function were
green on the branch. Landed as the commit named in §H. **Proportional to** the program's declarations before, to the namespace's
after. **Pattern P1.** Residual 11.8 MB is the `seon.db/q` wrapper path (`with-declarations`, 1-2 MB per call on a
trivial query) and result decoding, not scanning.

**`search`.** Scans every indexed source once, as its docstring says: 8,433 declarations, 9.57 M characters of source.
Measured at 50 MB / 90 ms (a miss), 87 MB for "defn", 50 MB for a keyword; the on-thread split is `sources` 19 MB,
`str/split-lines` over every source 35 MB, `match-row` 32 MB. A first search on a freshly changed branch was 760 MB /
460 ms once and 52-56 MB on seven repeats (the node reads again). **Expected**: reading 9.6 MB of source with
`String/indexOf` allocates about the result, a few MB (the sources vector alone is 19 MB). **Gap** 3-5x steady, which is
not the 150 MB overnight reading; that reading was a first-after-change one. **Pattern P1** (whole program per search,
documented) and P5. Not fixed: a search over the whole program is the job; `split-lines` per source and per call is the
avoidable part, in `my.program/match-row` (a 15-minute change, left out because the speedup is 3x and the file is in
active use by other lanes).

## E. The store: 788,000 files, 4.1 GB, 98% index nodes written in the last day, collection never ran

**Count by kind** (script over the first 300 bytes of every file, 784,870 files at the time):

| Kind | files | bytes |
|---|---|---|
| persistent-sorted-set leaf nodes | 614,358 | 1.42 GB |
| persistent-sorted-set branch nodes | 157,951 | 2.29 GB |
| everything else (head/commit records, temporal roots, blobs) | about 12,500 | 0.4 GB |

Age: 730,028 files (93%) were written in the last 24 hours, 50,367 the day before, 8,301 nine days ago (the store began
2026-09-29). Rate while lanes work: 1,856 files in the last minute, 18,674 in 10 minutes, 29,051 in an hour; 9.5 files/s
on a quiet half minute. Median file 1.7 KB, p99 105 KB, max 3.7 MB. The store is one flat directory of `.ksv` files
(`data/store`), which is also what `bin/seon status :seon.operator/store :files` counts.

**Branches versus agents.** `d/branches` returns 18 branches (15 `:agent-*`, a checkpoint branch, 2 others) against 22
agents (22 with `:seon.agent/branch`). Branches are not the growth: every branch is a pointer, and the nodes are shared.
The files are the copy-on-write nodes each *commit* leaves behind (every transaction rewrites the touched leaf and the
path above it in each of six indexes, `branching-factor 128`, `diff-buf-size 32`); nothing has reclaimed them.

**Does retention GC ever run?** It is scheduled and configured, and has never fired:
- `seon.maintenance/collect!` is the handler of the root schedule `root/maintenance/compact-schedule`, cron `0 3 * * *` UTC
  (`seon.schedule/root-maintenance-portfolio`), enabled by `:seon.config.maintenance/collect? true` on default, with
  `:seon.config.db/snapshot-window-ms 3600000` (one hour; the largest governs).
- The three schedule tasks were created at **2026-10-08T21:09:01Z** by the boot seed (`root-maintenance-seed-call`),
  so no nominal 03:00 instant has been due since they existed; fires: 0, maintenance receipts: 0, results: 0. The status
  line "No completed maintenance receipt records a collection of this root" is therefore accurate and expected until the
  first fire at **03:00 UTC (about 5 hours after this measurement)**.
- Whether that first fire works at this scale is unmeasured: the mark phase walks every roster branch's whole history
  from the epoch cutoff with `reachable-in-branch`, counts all keys (`k/keys`, 785,000), and runs twice (collect, then a
  verification collect) with `registry/retention-cutoff` per pass. I did not run `dry-run-store!` on default (it opens the
  store the live JVM holds, and the mark could allocate GBs on a 4 GB heap). The risk is the first collection of a
  store whose `snapshot-window-ms` is one hour: nearly everything older than an hour is collectable, which is
  both the win (an expected 3+ GB back) and the risk (unmeasured mark time and heap).
Existing tasks: `seon.maintenance/the-daily-collection-keeps-every-commit-of-a-live-branch`, `seon.maintenance/a-test-request-collects-the-whole-store-under-the-exclusive-gate`.
New: `seon.maintenance/the-first-collection-of-a-785000-file-store-is-unmeasured` (a dry run under supervision, recorded).
**Pattern P5** (retention without an owner running), with P4 in the write amplification below.

**Write amplification.** 730,000 nodes in 24 hours is about 25-30 files per transaction at the observed transaction rate;
the 2.29 GB in branch nodes (14.5 KB average) is the cost of fused roots with a 32-entry diff buffer. `store.clj` already
explains the 4,096 to 128 choice (184 KB per small commit). Per commit that is still 25 files; the number of commits
(every eval request writes at least one, tests write more: `transactions 4` for a `defn` plus `deftest`) is the lever.

## Shared causes across A-D

1. **Node-cache sizing is wrong in unit (P5).** `:seon.config.db/store-cache-size` is 8,192 *nodes*; CLAUDE.md says the
   node cache is byte-bounded. The shared LRU was full (8,192 of 8,192) on default while the store holds 772,000 nodes.
   The `store.clj` comment says it "holds a branch's whole-program read; below that working set each branch re-reads those
   nodes from disk". The measured effect is that C pays about 108 MB per call in LZ4 + Fressian decode, the first
   whole-population read after a change (search 760 MB once, `sizes` 291-666 MB first) pays more, and every search or
   check on a branch whose nodes were evicted by another lane pays it again. The CachedStorage `:reads` counter for the
   head storage was 35,135 against 1.87 M accesses (1.9% miss) over the process life, so the head's own cache works; the
   branch storages (which hold private tree copies) are not visible in that counter, which is itself a gap.
2. **Whole-population reads where a bound lookup would do (P1):** the `sizes` clause order (fixed), `reach-digests`
   (500 MB cold), the per-test projection and reach acquisition (A), `search` (documented), the
   `[_ :seon.source/test-input-digest ?digest]` scan in `seon.test/verified?` (0.17 ms here, cheap, but unbound by name).
   My own exploratory queries with an unbound attribute scan (`[?e ?a ?v]` with a namespace predicate) allocated 700 MB
   to 1.2 GB; the planner does not save an unbound-first query.
3. **The cost the reply states excludes the test step and the writer (P6).** It made A look like 22 MB.

## F. Findings outside the five items

- A request as trivial as `(+ 1 1)` reports `:calls 53,851` and about 70 ms: the baseline every reply pays
  (`settle` 31 ms, `test-step` 16 ms, `fork` 13 ms) with nothing evaluated.
- `bin/seon status` lists the top hot function as `seon.ai/stream-fold` (14 calls, mean 2.7 s) and
  `my.program/nearest` twice over bound (2.5 and 4.5 s per call, task `24a94b0d7529`); not examined here.
- `seon.cluster.source/write-back!` (`my.program/write-branch!`) refuses with "The file changed after its declarations were
  analyzed" when default's loaded program is older than HEAD for that file, as it was for `src/my/program.clj`
  (fanout-9b landed after the last restart). Correct, but the refusal does not name the file or the two digests.

## G. Patterns (docs/research/agent-platform/2026-10-08/cost-design/merged.md)

| Item | Cause | Pattern |
|---|---|---|
| A | per-test projection and reach acquisition over the whole program, per-read declaration check, hidden from the reply | P1 + P2 + P6 |
| B | `reach-digests` cold acquisition of the whole program's reach attributes | P1 |
| C | merge-base walk decoding index nodes the node LRU no longer holds, plus context preparation | P5 + P1 |
| D `sizes` | unbound clause first (landed) | P1 |
| D `search` | scan of all sources by design, `split-lines` per call, first-after-change node reads | P1 + P5 |
| E | commits' copy-on-write nodes accumulate; the first scheduled collection is not yet due and unmeasured | P5 |

## H. Landings, tasks, open measurement

- Commit (this lane): `src/my/program.clj` `my.program/sizes` clause order, proven by the tests reaching the function on
  branch `slow-probe4` and by the measured before and after in §D.
- New tasks: `seon.batch/a-request-cost-counts-only-the-evaluation-arm`,
  `seon.cluster.store/the-node-cache-counts-nodes-not-bytes`, `seon.maintenance/the-first-collection-of-a-785000-file-store-is-unmeasured`.
  Each is written without a red proof file: a proof would need a harness fixture the lane did not build (a request whose
  JVM-wide allocation exceeds its reported one; a store with more nodes than the cache; a supervised dry run); the numbers
  above are the baseline the proof must beat.
- Open: the 03:00 UTC first collection, and the fix of B's cold acquisition (cards CO-4/CO-5).

### Real-runner result for the proof of B

`bin/seon check --test seon.task-ingestion-test/an-assignment-reads-only-its-proofs` at candidate `f2c0b15d2`: red on
the 8 MB bound with 23,355,128 bytes (above). The proof is a true red; its fix is cold acquisition (CO-5), not a change
to `assign!`.
