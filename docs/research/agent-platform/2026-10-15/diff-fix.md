---
title: "diff-fix: datoms-diff exact on every node shape"
type: evidence
status: lane diff-fix (Opus); fork commit 88d05cf8 on 96000df8, pushed to origin seon/overlay-file-frontend; APFS clone, throwaway JVM
created: 2026-10-15
inputs: alignment-check.md (1c9323f09) item 6
---

# diff-fix: `datahike.api/datoms-diff` exact on every node shape

Lane `diff-fix` (Opus), 2026-10-15. Fork `reference-code/datahike`, branch `seon/overlay-file-frontend`.
**Sha for root's bump: `88d05cf8`** (one commit on `96000df8`). Answers item 6 of
[alignment-check](alignment-check.md): H2, H3, H4 and H7 are unblocked once root bumps the gitlink inside a
scheduled restart batch.

## Causes

1. **The NullPointerException (verified, the only cause of the throw).** `datahike.index.persistent-set/slot-datoms`
   read `(aget (.slots branch) i)`. `org.replikativ.persistent_sorted_set.Branch/slots` returns nil when the branch's
   `NodeState.buf` is nil (bytecode of persistent-sorted-set 0.4.137), which is the case for every branch read back
   from storage that was written with no buffered edits: a bulk-built tree, a fresh split, a flushed buffer. On the
   live store's chain head, 5 of 106 level-1 `:eavt` branches have no slots; any pair whose walk expanded one threw.
   In-memory stores keep their live nodes and never restore them, so 771a0c4b's tests never met such a branch.
   A file store reconnected reproduces it (all 23 level-1 branches of a bulk 3,000-row tree).
2. **A cost defect found while measuring (JFR allocation profile).** `diff`'s `unshared` called
   `HashSet.retainAll` with a lazy seq, so each address was tested by a linear scan of that seq: quadratic in the
   unshared children of one level. On the whole-index pairs it was 7.8 of 12.9 GB allocated over three calls.

Checked and not causes: an unstored (`d/with`) node has a nil address (`Branch/address` returns nil without an
addresses array) and `keep first` leaves nil out of the shared set, so it is compared by content; trees of
different heights enter the walk when the loop reaches their root's level; `Branch/child` projects the parent's
slot onto a copy of the child (separators restored), so the range filter reads correct keys, and a skipped child at a
shared address is covered because every slot of every expanded branch, nested diffs included, is a candidate.

## Fix

`src/datahike/index/persistent_set.cljc`: `slot-datoms` returns nothing for a branch with no slots (a missing
buffer means no buffered datoms); `unshared` intersects two `HashSet`s and returns vectors. 10 lines changed.

## Proofs

Fork tests, `test/datahike/test/datoms_diff_test.clj`:

| deftest | what | on the parent `96000df8` |
|---|---|---|
| `a-diff-over-branches-restored-without-a-buffer-is-exact` | file store reconnected: commit pairs and `with` overlays (retract + add) on `:eavt`/`:aevt`/`:avet` whole index; a `with` that adds one entity | 32 NPEs |
| `a-diff-of-trees-of-different-heights-is-exact` | a one-leaf fork against a several-level main, both ways, chain writes too | green (coverage) |
| `datoms-diff-equals-the-plain-set-difference-over-generated-histories` | test.check, 40 histories, seed 20261015: rows, main/chain transactions (insert, retract entity, add/replace), `with` overlays, memory or restored file store, branching 8 and diff-buf 4 (buffer-heavy, deep); every pick pair equals the plain set difference of `[e a v tx]` on all three indexes | fails |

Namespace: 5 tests, 936 assertions, 0 failures (kaocha focus on `88d05cf8`). Full suite `clojure -M:test -m kaocha.runner clj-pss`: **976 tests, 6,597 assertions, 0 failures** (6 min 28 s), run on the fix before its last edit, which only replaced a `some->` by a type-hinted `when-let` in `slot-datoms` (same behaviour, no reflection).

On an APFS clone of the live store (`cp -c -R data/store`, 49 s), throwaway JVM on the fork; main head
`6ad08c5c`, chain `:chain-f1f6dc8e6652`, fork point `6ad046cc` (first parent outside the chain) and the original
fork point `6acf6fed`. Every result below equals the plain set difference:

- the 4 failing pairs: fork point vs chain head on `:aevt [:seon.program/definition-digest]` (both fork points),
  `:aevt []`, `:eavt []`; main vs chain `:eavt []`; chain head vs a `with` overlay of itself;
- 20 random commit pairs (seeded, from 400 commits per branch over all branches) on `:eavt []`, `:aevt []` and
  `:avet []`, whole index (the eavt index holds every datom, so no attribute split was needed);
- 10 consecutive commit pairs (buffered-slot differences) on all three indexes.

## Cost on the real pairs

Same JVM, warm, thread allocation (`ThreadMXBean`, undercounts konserve reads):

| pair | parent | NPE fixed, scan kept | fix |
|---|---|---|---|
| fork point vs chain head, `:eavt []` ([50052 72395]) | NPE | 616 ms / 4,316 MB | **139 ms / 193 MB** |
| main vs chain, `:eavt []` ([70141 74094]) | NPE | 671 ms / 4,648 MB | **152 ms / 219 MB** |
| fork point vs chain head, `:aevt [:seon.program/definition-digest]` ([961 994]) | NPE | 3 ms / 6 MB | 8 ms / 4 MB |
| chain head vs `with` of itself, `:eavt []` ([0 2]) | NPE | 0 ms / 2 MB | 0 ms / 1 MB |
| fork point vs main head, `:aevt [:seon.program/definition-digest]` ([413 445]) | works | 1 ms / 3 MB | 2 ms / 2 MB |

For comparison, the plain set difference of the whole `:eavt` (710,699 vs 733,042 datoms) costs 712 ms / 1,255 MB.
The membership decision of the 122k candidates is 83 ms / 124 MB of the 139 ms. The 20 random pairs' first calls
on `:eavt []`: 20-952 ms / 28-1,077 MB (a suite ran beside them), proportional to the leaves the pair does not share.

## Limits

- Not run on default (never touched). The clone and the worktree are removed.
- Timings are one or two runs each on a JVM that was not quiet for the 20-pair numbers.
