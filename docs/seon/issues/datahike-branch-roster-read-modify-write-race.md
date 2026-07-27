---
type: issue
status: open
severity: blocker
tags: [issue, database, runtime]
---

# Datahike's `:branches` roster loses branches under concurrent `branch!`

## Problem

`branch!` and `delete-branch!` mutate the store-wide `:branches` roster with an
unsynchronized read-modify-write
(`reference-code/datahike/src/datahike/versioning.cljc:257` — `k/update store
:branches #(conj (set %) new-branch)`; `:289` — the `disj` counterpart).

Two Datahike connections to the same physical store on different branches do
**not** share a konserve store instance, so konserve's per-key application lock
(`konserve/core.cljc:180-205`, `:294-306`) is per-instance and does not
serialize them. The fork's own source states this: `gc_guard.cljc:47-50` — "separate connections to the same physical store hold DIFFERENT konserve store
instances". The per-blob OS `FileLock`
(`konserve/impl/defaults.cljc:303-351`) does not close the gap either, because
the write completes by atomic-move over the key
(`konserve/filestore.clj:196-200`), so the lock is held on the replaced inode.

Result: concurrent branch creation silently drops branches from the roster.
`branch!` returns success, the branch's head record is on disk, and the branch
is absent from `:branches`. Because GC's whitelist is exactly that roster
(`datahike/gc.cljc:136-143`), the lost branch's data is then collectable — the
next `gc-storage` deletes a cluster nobody knows is missing.

## Evidence

`tmp/b2-probe/branch_gc_probe.clj`, section C' — 12 concurrent `branch!` calls
issued alternately from two branch connections of one store:

```
C'. two connections share one raw konserve store? false
C'. {:attempted 12, :outcomes {:ok 11, :err 1},
     :in-roster 9, :lost 3, :head-blobs-written 12}
C'. ORPHAN RISK: head records written but missing from :branches = 3
```

Eleven callers were told `:ok`; nine branches exist; three head blobs are
orphaned garbage.

## Blast radius

Silent loss of a whole logical database. This is the L6 scar
(two writers, 40/40 commits destroyed, zero errors) reproduced at branch
granularity, and unlike L6 it happens **inside one process**, so the
single-writer `flock` does not fence it.

## Owner

Datahike, our fork (owner ruling 2026-07-27: Datahike is part of Seon; a small
fork change is acceptable when it buys real isolation).

## Acceptance criteria

Serialize `:branches` mutation per **physical store id**, around both call
sites. The fork already owns this idiom for exactly this reason:
`datahike/gc_guard.cljc:52` keeps an `in-flight` atom keyed by store-id
precisely because connections do not share store instances. Apply the same
keying one key over — approximately 15 lines, one keyed lock, two call sites.

Rejected alternative: sharing one konserve `DefaultStore` per physical path.
It would fix the race through the per-key lock, but merges caches, handlers and
write-hooks across branches — a far wider change for the same result.

Falsifier: N concurrent `branch!` calls from N threads across two or more
connections yield N branches in the roster and zero orphan head records. Write
it before the fix and watch it fail.

## Scope note

Discovered while evaluating branch-per-cluster for the B2 rung
(`docs/prds/sci-execution-runtime/research/b2-plan-2026-07-27.md` §0.3). The
fix is a blocking precondition for that design, but the defect is real and
upstream-reportable regardless of which cluster-materialization verdict B2
takes.
