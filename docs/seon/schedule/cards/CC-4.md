---
title: "Card CC-4: A commit writes its root record once and a compact head"
type: reference
status: active
created: 2026-10-09
---

# CC-4 A commit writes its root record once and a compact head

Step 2 ([README](README-commit-cost.md)). Lane: Opus in the Datahike fork. Closes task
`seon.cluster.store/each-commit-serializes-its-root-record-twice`.

## What exists
After CC-3 every reader resolves a compact head. `datahike.writing/commit!` still serializes and LZ4-compresses the
fused record twice (konserve `konserve.impl.defaults/update-blob` encodes per key) and writes ~88 KB twice on default;
the second copy is the mutable head, written under the `:barriered` plan.

## Change
When `:commit-graph?` is true (Seon's configuration), the branch key gets `{:datahike/commit-id cid}` after the
immutable commit record, in both the `multi-key-capable?` and the ordered path; the order "everything the head names
first, the head last" is unchanged. With `:commit-graph? false` there is no commit record, so the full head stays.

## Functions
- CHANGE `datahike.writing/commit!` (fork).

## Proof
CC-0's test tightened: head bytes per commit under 1 KB, one record encode per commit, commit p50 lower by the
measured encode time. Fork test `datahike.test.store-test/a-commit-writes-a-compact-head` (new): after a commit the
branch key holds the compact head; injected failure after the record and before the head leaves the previous head
resolving to a complete commit; reopen equals. CC-3's test stays green.

## Cost
Saves one Fressian + LZ4 encode of the fused record (task estimate 5-8 ms on 134 KB; ~88 KB today, expected 2-5 ms)
and its ~88 KB write per commit, and about 40 MB per 500 commits of head rewrites. Barriers unchanged (two per head):
expected after on CC-0's shape, about the two-barrier floor (~8 ms) plus node writes, ~10 ms.

## Durability
Identical: the head is still written under `:barriered` after the record it names.

## Commit
Fork commit, gitlink bump batched by root with CC-3; Seon side
`bin/lane commit <lane> -m "CC-4: one root record per commit, compact head" --test seon.cluster.store-test/a-small-commit-reports-its-milliseconds-files-and-head --test seon.cluster.store-test/open-write-release-reopen-preserves-data reference-code/datahike`.

## Dependencies
CC-3 (readers installed and booted first), CC-0. Gitlink bump: yes. A store written by CC-4 is unreadable by a JVM
before CC-3: root's `start --head` restore must not cross back over CC-3.
