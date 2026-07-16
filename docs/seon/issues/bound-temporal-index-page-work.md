---
type: issue
status: open
severity: friction
tags: [issue, database, flow, architecture]
---

# Bound temporal index-page work

## Problem

A narrow `index-page` over an earlier `AsOfDB` or `SinceDB` can consume and
reconstruct the entire index side from its seek point, then comparison-sort the
eager result before applying the requested prefix and page limit. A one-value
AVET page near the middle of a grown database can therefore perform work
proportional to roughly half the database, and small cursor pages repeat that
remaining-side work.

Current-head pages and historical pages without a time predicate retain their
bounded lazy traversal. The issue is the time-filtered temporal path, not the
public page shape or the correctness repair in `f0ee54c2`.

## Evidence

- `datahike.db/contextual-seek-datoms` and `contextual-rseek-datoms` merge from
  the seek point to a global index bound before the page's prefix check
  (`reference-code/datahike/src/datahike/db.cljc:256-279`).
- `post-process-datoms` eagerly filters and reconstructs that candidate stream
  (`reference-code/datahike/src/datahike/db.cljc:179-192`).
- `filter-txInstant` consumes all candidates and performs a temporal EAVT
  lookup for every distinct transaction, even when the cut is numeric
  (`reference-code/datahike/src/datahike/db/utils.cljc:277-289`).
- Datahike `f0ee54c22d70a20de0279996f93aea98c6a9d1df` correctly restores native
  order, but sorts before prefix, cursor, and `limit + 1` processing
  (`reference-code/datahike/src/datahike/index_page.cljc:45-53,130-154`).
- The complete complexity analysis, PSS/HHT distinction, bounded dependency
  seam, and grown-database falsifier are in
  [[../../prds/database-authority-mesh/research/datahike-temporal-index-page-cost-2026-07-16]].

## Owner

The maintained Datahike `datahike.index-page` path, using its existing native
index slices, temporal merge helpers, search context, and numeric transaction
cut. Seon must not add a comparator, compensating scan, temporal materialized
index, or another paging protocol.

The selected implementation bounds both ends of the raw current and temporal
index slices, preserves their native forward or reverse merge, reduces adjacent
`[e a v]` event groups through the existing temporal semantics, and stops after
`limit + 1` visible datoms. Cursor continuation reopens and verifies the
cursor's complete value group. PSS owns the fully lazy path; HHT reverse paging
requires a dependency-native reverse iterator before it can claim equivalent
density.

## Acceptance

- On a persisted 50,000-entity, five-replacement history fixture, an exact
  one-value earlier AVET page consumes candidates and restores index nodes
  proportional to that value's own temporal events plus one cursor group, not
  unrelated datoms before or after the prefix.
- Forward and reverse EAVT, AEVT, and AVET pages remain identical to the eager
  semantic oracle across cardinality one/many, assertion/retraction/re-add,
  same-transaction polarity, `as-of`, `since`, history around each, lookup
  refs, refs, bytes, and every cursor split.
- Numeric cuts perform no per-transaction `:db/txInstant` lookups. Date cuts
  reuse the existing bounded query-engine transaction-cut resolution rather
  than adding another cache.
- PSS page allocation is bounded by the returned page plus constant
  reconstruction state, and cold/warm restored-node evidence shows lazy
  traversal. HHT forward has the same bound; HHT reverse remains explicitly
  limited until its exact dependency source and reverse iterator are present.
- Current-head and ordinary history page latency, allocation, cursor behavior,
  result-weight certification, and public response shape do not regress.
