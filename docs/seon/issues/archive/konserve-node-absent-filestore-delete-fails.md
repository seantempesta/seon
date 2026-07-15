---
type: issue
status: resolved
severity: blocker
tags: [issue, database, cljs]
---

# Make absent Node filestore deletion idempotent

## Problem

Konserve's Node filestore deletion treated an absent directory as an error.
Datahike's CLJS tiered-storage recovery gate therefore failed before database
creation when its cleanup first deleted a fresh temporary path.

## Evidence

`konserve.node-filestore/delete-store-async` passed the `ENOENT` result from
`cljs-node-io.fs/arm-r` to the caller. Datahike's
`tiered-frontend-deletion-recovery-test` reproduced the exact failure as an
`ENOENT` `scandir` on its never-created temporary directory.

Konserve commit `b5c99bc02a7175652a610324215288b78551801f` makes sync and async
deletion first observe existence, treats an already-absent store as converged,
and retains parent-directory synchronization after an actual deletion. Its
focused regression covers both sync nested-path and async absent-root deletion.
Datahike publication descendant
`9ada755087228e10cfb179fa5779ce227a6ed220` pins that Konserve commit and its
complete Node gate passes 105 tests/824 assertions.

## Owner

Konserve's one Node filestore deletion boundary in
`konserve.node-filestore`.

## Acceptance

- Sync and async deletion of a never-created Node filestore return success.
- Deletion of an existing filestore still removes it and synchronizes the
  parent directory.
- Non-absence filesystem errors remain visible to the caller.
- Datahike's tiered frontend deletion/recovery regression passes from a clean
  temporary path.
