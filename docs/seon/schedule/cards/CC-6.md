---
title: "Card CC-6: Konserve's barriers are counted per head write"
type: reference
status: active
created: 2026-10-09
---

# CC-6 Konserve's barriers are counted per head write

Step 3 ([README](README-commit-cost.md)), test only. Lane: Sonnet in the konserve fork (`reference-code/konserve`,
pinned `e4334756`).

## What exists
`konserve.impl.defaults/sync-plan` answers `:deferred` for a new immutable key and `:barriered` for every other write
of an opted-in store; `konserve.impl.defaults/update-blob` then runs `-sync-data`, `-sync-store`, move, `-sync-store`
(the `konserve.impl.storage-layout/PDataSyncBackingStore` methods; on macOS the store barrier is `F_FULLFSYNC`, ~4
ms). The existing test `sync-plan-defers-only-new-immutable-keys-of-an-opted-in-store` checks the plan, not the
barrier count or the order of callbacks against barriers.

## Change
A test-only backing that wraps the filestore backing and records each `-sync-data`, `-sync-store` and move, with a
latch to pause before a barrier. Two concurrent writers of distinct mutable keys (two branch heads) on one store.

## Functions
None in production. NEW test in `test/konserve/filestore_test.clj`.

## Proof
`konserve.filestore-test/each-barriered-write-pays-two-store-barriers` (new): two head writes give 4 `-sync-store`
calls today; a write's callback never completes while it is paused before either of its barriers; a failure injected
at the data push, the first barrier, the move and the second barrier each delivers an error, never success. CC-7
changes the count assertion from 2N to 2 per group.

## Cost
Test only, milliseconds.

## Durability
Unchanged; this test is the guard CC-7 must keep green.

## Commit
Fork commit in `reference-code/konserve` (its own test task); gitlink bump batched by root.

## Dependencies
None. Gitlink bump: yes (test only; may ride CC-7's bump).
