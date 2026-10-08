---
title: "Card CC-0: A small commit reports its milliseconds, files and barriers"
type: reference
status: active
created: 2026-10-09
---

# CC-0 A small commit reports its milliseconds, files and barriers

Proof card for the CC series ([README](README-commit-cost.md)). Lane: Sonnet. No production change.

## What exists
`seon.cluster.store/open-store!` creates a throwaway store at the production creation configuration
(`seon.cluster.store/datahike-configuration`: LZ4, `:defer-immutable-sync? true`, fused roots, history, 128/32), as
`seon.cluster.store-test/open-write-release-reopen-preserves-data` does with `fresh-dir`. Konserve's
`konserve.core/add-write-hook!` sees every key written (the store test already uses it). Nothing measures a commit.

## Change
Add one deftest that, on a fresh throwaway store, transacts a probe schema of 20 attributes and a 1,000-entity seed,
warms with 5 commits, then makes 20 commits of 17 datoms over 14 distinct attributes (recording-shaped scatter, not one
attribute), and measures for each: wall ms (`seon.profile/measure`), keys written and their bytes by kind (node, commit
record, branch head) from a write hook, and the branch head's size. The test asserts the shape of today's write
(exactly one commit-record key and one branch-head key per commit; head bytes > 1 KB today, the bound CC-4 tightens)
and stores the summary (p50/p95 ms, files and bytes per commit) as profile evidence. Each later CC card tightens one
assertion of this test. Never default's store.

## Functions
- NEW test `seon.cluster.store-test/a-small-commit-reports-its-milliseconds-files-and-head` in
  `test/seon/cluster/store_test.clj`.

## Proof
The test itself, green, with its numbers in the landing note (expected on this machine: p50 10-15 ms, 4-40 node files).

## Cost
Expected: 25 commits × ~12 ms plus the seed, under the default 5 s bound; a longer bound names the seed.

## Durability
Unchanged; measures the installed protocol.

## Commit
`bin/lane commit <lane> -m "CC-0: a small commit reports its milliseconds, files and head" --test seon.cluster.store-test/a-small-commit-reports-its-milliseconds-files-and-head test/seon/cluster/store_test.clj`

## Dependencies
None. No gitlink bump.
