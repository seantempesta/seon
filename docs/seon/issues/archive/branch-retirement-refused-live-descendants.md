---
type: issue
status: resolved
severity: blocker
tags: [issue, database, operator, datahike]
---

# Allow branch names to retire while descendants survive

## Problem

`seon.cluster.registry/retire-branch!` refused to remove a branch name when
another roster branch descended from its head. This made repeated
`current-src` publication and `init NAME --force` fail even though Datahike
branches retain immutable commits independently.

## Evidence

The live `init default --force` failed with
`:seon.cluster.registry/cannot-retire-live-ancestor`. Maintained Datahike
source shows `delete-branch!` removes the roster name, while GC seeds every
remaining branch head and walks its parents
(`reference-code/datahike/src/datahike/versioning.cljc:261-293`,
`reference-code/datahike/src/datahike/gc.cljc:22-81`).

## Owner

`seon.cluster.registry` branch lifecycle.

## Acceptance

- Retiring an ancestor branch name succeeds while a written descendant lives.
- Datahike collection runs after retirement.
- The descendant still exposes inherited and descendant-local facts.

## Resolution

The redundant descendant walk and refusal were deleted. The recurring
registry test now retires `current-src`, collects unreachable objects, and
reads both inherited and cluster-local markers from the surviving cluster.
The live destructive refork then succeeded.
