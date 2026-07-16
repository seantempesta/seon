---
type: issue
status: resolved
severity: blocker
tags: [issue, database, flow, architecture]
---

# Preserve temporal index-page order

## Problem

A time-filtered Datahike index page can lose native AVET, AEVT, or EAVT order
after reconstructing cardinality-one state. Prefix paging then stops at an
unrelated datom and silently omits valid rows.

## Evidence

With bootstrap schema datoms, an indexed cardinality-one score of Alice=1 and
Bob=2 at transaction T, and Alice replaced with 9 at T+1, forward AVET paging
over `(d/as-of containing-db T)` returns `[1 2]` while reverse paging returns
only `[2]`. The temporal history contains the expected assertion/retraction
polarity, so data is not missing from storage. `datahike.db/post-process-datoms`
groups reconstructed state by entity/attribute; the resulting vector is not in
the index order that `datahike.index-page/index-page` assumes before its prefix
`take-while`.

## Owner

The maintained Datahike `datahike.index-page` implementation owns restoring
its native comparator order after time-filtered reconstruction, before prefix
and cursor processing. Seon must not add a comparator or compensating scan.

Resolved by Datahike commit
`f0ee54c22d70a20de0279996f93aea98c6a9d1df`. Time-filtered pages restore the
requested native temporal comparator order before prefix/cursor handling;
ordinary current and history pages retain their existing lazy traversal.

## Acceptance

Persistent-set and hitchhiker-tree tests prove forward `[1 2]`, reverse
`[2 1]`, one-row cursor continuation, and exact history polarity. The same
ordering/cursor contract passes in ClojureScript. The dependency commit is
pushed, pinned in both Seon dependency declarations, and the integrated strict
temporal writer proof passes without a Seon ordering workaround.

Focused persistent-set, hitchhiker-tree, and specification proof passes 30
tests with 141 assertions. The canonical Node CLJS gate, including the new
portable temporal ordering and polarity fixtures, passes 137 tests with 940
assertions.
