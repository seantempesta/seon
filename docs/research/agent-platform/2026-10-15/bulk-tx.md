---
title: "The bulk transaction: what the 5,146-datom commit spends its 3-4 s on"
type: research
date: 2026-10-15
status: measured (lane bulk-tx, Sonnet); no source edits, no fork changes, default untouched
basis: Seon working tree 2026-10-15 ~14:50 +07 (deps: Datahike fork 96000df8, konserve af35a974 with the new read path, persistent-sorted-set 0.4.137), Java 26, `-Xmx10g`
---

# The bulk transaction

Question from [storage-workload-profile.md](storage-workload-profile.md) §5 rank 1: one 5,144-datom transaction took 4.4-13.4 s, wrote 3,284
files and allocated 4-8 GB, and its cause was not profiled. Candidates: (a) `persistent-sorted-set` `Branch.remove` recount, (b) Seon's
writer, (c) copy-on-write spread over many leaves, (d) other.

**Answer: (a). 81% of the storage reads, 52% of the allocation and about 60% of the CPU samples of the cold transaction are the recount
inside `Branch.remove`; with the node cache warm the same transaction takes 1.0-1.5 s and 1.37 GB, and what is left is (c) plus LZ4 write
buffers. (b) is 170-190 ms; (d) is nothing.**

## 1. Which transaction

The earlier lane's scripts were not kept, so I identified it by size and time (its clone replayed main's last 60 commits at about 13:00
+07). Over the 1,943 commits of `:cluster-default` still in the store (2026-10-13T10:02Z to 2026-10-15T07:45Z), walked through
`[:meta :datahike/parents]`, exactly one fits.

| fact | value |
|---|---|
| transaction (`:max-tx`) | **536873082**, commit `6ad06ccf-fb13-5cb9-9485-9b0a9999c89c`, parent `6ad06cb4-a86c-540d-a17c-c76fdd1a227e` (it is the immediate predecessor: parent `:max-tx` 536873081) |
| when | 2026-10-15T06:03:53Z (13:03 +07), on main |
| datoms | **5,146** = 2,516 adds + 2,630 retractions (the lane's 5,144 = 5,146 minus the tx entity's two datoms; my replay is 2,514 adds + 2,630 retractions) |
| entities | 1,160, eids 2082 to 1,206,413 (the whole range, not a block) |
| what it is | `:seon.fn/form-span` and `:seon.program/analyzed-source-digest` re-written on **566 functions** (566 of 2,630 retractions each, 1,132 = 43% together), plus `calls`/`call-arities` (about 320 each way), `:seon.lint/row` (147), vector cells (`seon.db.vector/*`, about 230), namespace aliases and arities. Only 37 `definition-digest` changed: the definitions did not change, their positions did |
| source files | 225 functions in `src/seon/turn.clj`, 111 `cluster/source.clj`, 48 `cluster/agent.clj`, 34 `context.clj`, 34 `program.cljc`, 32 `test.clj`: exactly the six files of commit `331eec6ec` (12:44 +07, "a forward merge is a merge commit") |
| producer | the transaction carries `:seon.db.transaction/operation seon.db/transact!` and no named transaction function, so it is the publication/reconcile of those six files' declarations into main (reload or write-back), about 19 minutes after the commit. The exact caller (reload batch vs write-back) is not identified; the recording path (`seon.turn/record-evaluated-call`) is not it |
| exactness of my replay | after replaying retractions then adds on the parent, the entities' datoms equal `(d/as-of main 536873082)`: 31,187 vs 31,187, 0 differences |

## 2. Method

Throwaway JVM (`clojure -J-Xmx10g -M`, Seon classpath at HEAD, socket REPL) on an APFS clone of `data/store` (`cp -c -R`, then `cp -c -R -n`;
425,076 files, 65 s). The store id in the config was kept as the stored one (Datahike refuses a mismatch). For each variant a fresh branch
is cut from the parent commit with `d/branch!`, the node cache (`:datahike.index.interface/node-cache`, Caffeine) is invalidated and
`System/gc` run for "cold", and the replayed tx-data (`[:db/retract e a v]` for the 2,630, then `[:db/add e a v]` for the 2,514) goes through
`d/with` (no writes) or `d/transact` (the real writer: Seon's `:write-fn-map` `processing-measured` wrapper, commit, 3,288 node writes).
Allocation is `ThreadMXBean.getThreadAllocatedBytes` summed over all live threads (the writer thread included). Storage reads are
`CachedStorage` `:stats :reads` (a node-cache miss that reads konserve); each miss was classified by the stack of the calling thread
(hook on `datahike.index.interface/cache-get`). JFR: `jdk.ExecutionSample`, `jdk.ObjectAllocationSample` (throttle 20,000/s),
`jdk.FileWrite/FileForce/FileRead`, stack depth 256. The OS page cache was warm (a truly cold disk would only add to the read side). Clone,
JVM and JFR files deleted; forms are in the lane's gitignored `tmp/bulk-tx/`.

## 3. The numbers

All for transaction 536873082 unless noted. Repeats are separate runs.

| variant | wall ms | alloc MB | storage reads | node writes |
|---|---|---|---|---|
| `d/with` retractions only, **cold** | 1,796 / 1,839 / 1,788 (first run 3,126) | 2,617 | **14,687** | 0 |
| `d/with` additions only, cold | 397-420 | 632 | 2,842 | 0 |
| `d/with` both, cold | 1,638 | 2,699 | 14,688 | 0 |
| **`d/transact` (real), cold** | **2,814-4,089** (4,089 / 3,572 / 2,843 / 2,814; JFR run 2,671) | **3,803-4,162** | 14,688 | 3,287-3,288 (3,284 files, 9.15 MB) |
| `d/transact`, warm cache | 1,004-1,514 | 1,368-1,373 | 0 | 3,288 |
| `d/with` both, fully warm | 87-119 | 222-248 | 0 | 0 |
| `d/with` retractions only, fully warm | 67-92 | 175-206 | 0 | 0 |
| `d/with` additions only, fully warm | 27-28 | 90 | 0 | 0 |
| `seon.db/with` (Seon validation, then refusal; see (b)) fully warm | 267-303 | 468-478 | 0 | 0 |

So the 4-8 GB of the earlier lane was the same shape on a busier machine: 3.8-4.2 GB here, 2.8-4.1 s. A second transaction of the same kind
(536872614, `seon.task/adopt-tasks-tx`, 4,062 retractions, 4,861 adds) cold: 3,155 ms, 4,460 MB, 16,494 reads, 3,790 writes; warm: 1,464 ms,
1,649 MB. `d/with` retractions only cold: 1,947 ms, 3,009 MB, 16,477 reads.

### Where the storage reads come from (cold `d/with`, misses by calling frames)

| cause | retractions only | additions only | both | 536872614 (adoption) |
|---|---|---|---|---|
| **subtree recount** (`Branch.child <- Branch.computeSubtreeCount <- Branch.count <- Branch.childCount`, under `Branch.remove`) | **11,883** (81%) | 126 | 11,883 | **13,720** (83%) |
| add path (the retraction's own temporal-index insert, or an addition) | 2,186 | 1,783 | 2,186 | 2,089 |
| the remove's own path | 562 | 0 | 562 | 594 |
| slice/lookup/other | 56 | 933 | 57 | 91 |
| total misses | 14,687 | 2,842 | 14,688 | 16,494 |

The retraction's own work needs about 2,800 reads (562 + 2,186 + 56); the other 11,883 are the recount. Recount cache hits add 806-1,605 more
restore calls with no read. 11,883 distinct nodes read for 2,630 retractions is 4.5 leaves per retraction, about three indexes times every
leaf under each touched level-1 branch.

### Where the time and allocation go (JFR, cold real transaction; stacks, not guesses)

| bucket | alloc MB (of 3,845) | CPU samples (of 408) |
|---|---|---|
| reads under `Branch.remove` recount | **2,018 (52%)**: fressian decode 1,443, konserve/LZ4 other 527, file 48 | **253 (62%)** |
| reads under the add path | 397 (10%) | 66 (16%) |
| writes (konserve assoc: LZ4 769, fressian 208, other 120, file 20) | 1,117 (29%) | 29 (7%) |
| the writer wrapper and non-storage Datahike/PSS compute | 272 + 30 (8%) | 45 + 13 (14%) |

In the retractions-only profile the recount frame alone is **1,868 of 2,678 MB (70%)**; the remove's own path reads are 126 MB (5%).
Warm real transaction (1.37 GB, 64 samples): writes 1,117 MB (82%), of which LZ4 frame buffers 814 MB (59% of all warm allocation, 3,288
files times about 250 KB); CPU: writes 44%, compute 55%. fsync is not a cost here: 2 `FileForce` events, 11-16 ms; 9,855 `FileWrite` events,
59-63 ms in total. (JFR on this macOS took about 8 ms per execution sample although 2 ms was set, so read the CPU columns as shares.)

## 4. The candidates

| candidate | verdict | evidence |
|---|---|---|
| (a) `Branch.remove` recount | **dominant, confirmed** | 81-83% of reads in two transactions, 70% of retraction allocation, about 60% of cold CPU; cold minus warm is 1.8-2.6 s of the 2.8-4.1 s |
| (b) Seon's writer: validation, transaction functions | **small, not the cause** | `seon.db/with` is 267-303 ms and 470 MB against 87-119 ms and 230 MB for the plain `d/with`: about **170-190 ms and 230 MB** for projection and validation, 4-6% of cold. The processing-measured wrapper is in every run (the config's writer). **Not measured**: the raw replay is refused by Seon's validator (`vector-member-outside-cell`), because raw datoms lack the authored form that `vectors-tx` and `namespace-digests-tx` expand; the successful path and those two functions were not run. The 17-20 ms of the September note is for small transactions |
| (c) copy-on-write spread | **real, proportional, second** | 3,288 node writes for 5,146 datoms (about 7 index operations per written node, over 1,160 entities spread across the eid range). Cost warm: 1.0-1.5 s, 1.37 GB, of which LZ4 buffers 0.81 GB. Linear in touched leaves, not super-linear; the known konserve compressor fix (storage-workload-profile rank 2) takes about 0.8 GB off it |
| (d) other | nothing | fsync 11-16 ms; file writes 60 ms; no unexplained bucket (the "other" class is 0 MB) |

## 5. How often

Per-transaction counts over the 1,940 main commits in the window (a full `history` scan), not a sample.

| fact | value |
|---|---|
| datoms per tx | p50 20, p90 40, p99 3,378, p99.9 10,437, max 20,412 |
| transactions that retract anything | 155 (8.0%) of 1,940; 78,090 retractions in all |
| with at least 100 / 1,000 / 2,000 / 5,000 retractions | 45 / **24** (1.2%) / 14 / 2 |
| share of all retractions in those | 99.1% / **84.4%** (65,911) / 65.7% / 18.3% |
| by producer (`:seon.db.transaction/operation`) | **`seon.task/adopt-tasks-tx`: 37 txs, 48,094 retractions (62%), 17 of the 24 big ones**; `seon.db/transact!` (publication/reconcile): 216 txs, 29,030 retractions (37%), 7 of the 24; `seon.db/absent-call` 703; everything else (1,328 `record-evaluated-call` recordings, host and config calls) retracts 0 to 120 in total |
| rate | 24 in about 45 h on main, clustered around lane work (adoptions and source publications); not forward merges: no big transaction carries a merge operation in this window |

Cost against the whole stream, from the measured per-retraction cost (2.8-4.1 s for 2,630 retractions, 3.2 s for 4,062: about 0.8-1.6 ms each,
cold): the 24 big transactions are about 50-105 s of writer time against about 50-115 s for the other 1,916 at the measured 27-60 ms, and about
85% of the allocation (24 x about 4 GB against 1,916 x about 10 MB). The writer applies one transaction at a time per branch, so each is a
3-5 s stall of main's writes (inferred from `datahike.writer`, not measured here).

The adoption transactions have the same shape as the one replayed: 43-56% of their retractions are `form-span` plus `analyzed-source-digest`
(for example 2 x 1,145 of 4,062 in 536872614), with `definition-digest` mostly unchanged; same-`(e a v)` retract-and-re-add is 0 to 44 per
transaction (the largest, 536871725, has 44), so exp-b's finding that nothing is rewritten identically holds here too.

## 6. The fix, its owner, and the gain

**Dominant cost:** `org.replikativ.persistent_sorted_set.Branch.remove` builds the new child `Slot` with `childCount(storage, i)`
(bytecode offset 446 of `remove`), which calls `child.count(storage)`. For a `Branch` that is `Branch.count`, which returns `_subtreeCount`
when it is at least 0 and otherwise calls `computeSubtreeCount`, which faults every child. The copy a remove produces has no cached
subtree count, so each touched level-1 branch faults all of its leaves (up to 128), in each of eavt, aevt, avet (and the temporal indexes
on the add side). The same file already uses -1 as "unknown" for `bufEntries`; the count is only needed when someone asks `count`.

**The fix:** at the owner, `persistent-sorted-set` (Java, coordinate `org.replikativ/persistent-sorted-set` 0.4.137 in `deps.edn`; it is
not vendored under `reference-code/`, so it needs a fork or an upstream change and a Datahike bump): carry the subtree count through `remove`
(old count minus one when known) or leave the new slot's count unknown (-1) and compute it on demand. This is read from `javap`, not
patched: **the change itself is untested**. Proof, as exp-b said: a storage-read assertion on `d/with` of N retractions (reads per
retracted datom bounded), not wall-clock; the red form is the retractions-only cold `d/with` above (14,687 reads, 2,630 retractions).

**Is it now "a known requirement"?** Yes, on these numbers, and the hold should be released:
- it is the dominant cost of the heaviest transaction class in main's history (24 transactions, 84% of retractions, 62% of them
  adoptions), not a merge-only cost;
- it was already the multiplier in exp-b (1,067 retractions, 2.0 s, 1.3 GB), konserve-read-fix (5,059 retractions, 16.7k reads cold) and
  here, three independent workloads;
- the earlier fixes cannot remove it: konserve's per-read fix cut each read, not their number.

**Expected gain** (estimate = the measured warm floor plus the remaining reads at the measured cost per read, about 0.115 ms and
0.18 MB; not measured with a fix):

| | now (cold) | after | change |
|---|---|---|---|
| reads for this transaction | 14,688 | about 2,800 | -81% |
| `d/with` retractions only | 1.8 s, 2.6 GB | about 0.45 s, 0.8 GB | -75%, -70% |
| real `d/transact` | 2.8-4.1 s, 3.8-4.2 GB | about **1.3-1.8 s, 1.8-2.0 GB** | about -55%, -50% |
| the adoption transaction 536872614 | 3.2 s, 4.5 GB | about 1.8 s, 2.1 GB | about -45%, -50% |

What remains after the fix is (c): the 3,288 node writes, of which LZ4 buffers are 0.81 GB; the konserve compressor change (rank 2 in the
write profile) would bring the warm transaction from 1.37 GB to about 0.55 GB.

**Two levers that are not the library:**
1. Producer (a data-model question for the owner, not a fix to do now): 43-56% of the retractions in these transactions are `form-span`
   and `analyzed-source-digest`, which change for every function below an edit because spans are absolute. Storing position relative to
   its declaration, or not digesting it, would roughly halve retractions and node writes for source publication and adoption. It changes
   what a digest means, so it needs the owner.
2. Seon's validator is 170-190 ms and 230 MB on this transaction on the refused path; measure the successful path (authored form through
   `seon.db/transact!`) before ranking it.

## 7. Limits

- One transaction in depth, a second for confirmation; the third largest (536871328) could not be replayed because its parent commit is
  gone from the store.
- Cold means node cache cleared and the OS page cache warm. The machine was shared with other lanes (cold wall varied 2.8-4.1 s; the
  allocation and read counts did not).
- The replay is raw datoms through plain Datahike with the config's writer wrapper; it is not the authored transaction through
  `seon.db/transact!` (refused by the validator), so tx functions and the commit-time validator of the successful path are not in the totals.
- Read classification uses the calling stack at the node-cache miss; the recount class matches the JFR allocation frames
  (`computeSubtreeCount` 1,868 MB), two independent views agree.
- The fix estimate is arithmetic on measured pieces, not a measurement with the fix.
