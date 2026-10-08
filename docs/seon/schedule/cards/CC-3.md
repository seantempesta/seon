---
title: "Card CC-3: Every branch-head reader accepts a head that names its commit"
type: reference
status: active
created: 2026-10-09
---

# CC-3 Every branch-head reader accepts a head that names its commit

Step 2 ([README](README-commit-cost.md)), readers first. Lane: Opus in the Datahike fork (`reference-code/datahike`,
pinned `86c3301a`). Task `seon.cluster.store/each-commit-serializes-its-root-record-twice`.

## What exists
`datahike.writing/commit!` writes `db-to-store` (the fused-root record from `datahike.writing/db->stored`) under the
commit id (immutable) and again under the branch key (mutable). The branch key's readers decode a full record:
`datahike.connector` connect and refresh (`k/get store (:branch config)` → `stored->db`),
`datahike.versioning/branch-as-db`, `datahike.versioning/branch-history`, `datahike.versioning/branch!` and
`datahike.versioning/force-branch!`, `datahike.versioning/merge!`, `datahike.readers`, the GC mark in `datahike.gc`
that walks branch heads, and on Seon's side `seon.db/branch-connection` and `seon.cluster.registry` (both through
`branch-as-db`).

## Change
One function `datahike.writing/resolve-head` (fork-internal): given the value read at a branch key, return the full
stored record: itself when it is a full record, or the record at its `:datahike/commit-id` (one `k/get`) when it is a
compact head `{:datahike/commit-id cid}`. Every reader above calls it before `stored->db`; GC marks the named record.
No writer change: nothing writes a compact head yet, so existing stores are untouched.

## Functions
- NEW `datahike.writing/resolve-head`; CHANGE the readers listed (fork only).

## Proof
Fork test `datahike.test.store-test/a-compact-head-reads-as-its-commit` (new): on a file store with history and the
commit graph, replace the branch key with `{:datahike/commit-id (commit-id @conn)}` by hand; connect, `branch-as-db`,
`branch-history`, `branch!` from it, `merge!` and a GC mark each give the same datoms and commit id as with the full
head; a compact head naming a missing record fails loudly. Existing `datahike.test.store-test/test-diff-buf-upsert-reopen`
and `test-detached-root-count-after-reopen` stay green.

## Cost
A full head: unchanged. A compact head: one extra `k/get` of the commit record at connect or `branch-as-db` (cold,
node-cache warm afterwards).

## Durability
Unchanged (no write path touched).

## Commit
Fork commit in `reference-code/datahike`, then root's gitlink bump (batched with CC-4). Seon side:
`bin/lane commit <lane> -m "CC-3: branch-head readers accept a compact head" --test seon.cluster.store-test/open-write-release-reopen-preserves-data reference-code/datahike`.

## Dependencies
None. Gitlink bump: yes (root batches).
