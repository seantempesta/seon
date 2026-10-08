---
title: "Card CC-5: Measure what unfused roots save on a recording-shaped commit"
type: reference
status: active
created: 2026-10-09
---

# CC-5 Measure what unfused roots save on a recording-shaped commit

Step 2 ([README](README-commit-cost.md)), measurement only. Lane: Sonnet.

## What exists

`seon.cluster.store/datahike-configuration` creates stores with `:fuse-index-roots? true`: the commit record embeds
all six index roots, so a commit re-encodes unchanged temporal roots (~57 KB raw each on a long-value store, astra
§3.4). On an insert-only fixture unfused was no faster (12.03 vs 12.02 ms); a recording-shaped scatter was never
measured. Fusion is fixed at creation: changing it needs a new store, so it is decided by numbers first.

## Change

Run CC-0's commit shape, plus a replay of 20 recording transactions (their datoms, read once from an agent branch's
history on default, read-only, and re-transacted with the schema they use), on two throwaway stores: fused and
unfused. Record ms p50/p95, files, bytes and record size per commit. No configuration change.

## Functions

None. A probe under `docs/research/agent-platform/2026-10-09/commit-cost/` (committed script and numbers).

## Proof

The committed numbers table; a decision row for root: change creation config (new stores only) or keep.

## Cost

Expected: two throwaway stores, ~2 × 25 commits, seconds.

## Durability

Unchanged.

## Commit

`bin/lane commit <lane> -m "CC-5: fused and unfused roots on a recording-shaped commit" --test seon.cluster.store-test/configuration-is-the-one-shape <probe paths>`

## Dependencies

CC-0; after CC-4 (measure the record that remains). No gitlink bump.
