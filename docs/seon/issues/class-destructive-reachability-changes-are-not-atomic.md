---
type: issue
status: open
severity: blocker
tags: [issue, database, blob, class/n14, class-kill, wave/class-kill-queue]
---

# Make destructive reachability changes atomic

## Problem

Collection and replacement operations can separate the decision about what is
reachable from the publication that changes reachability. A failure or
concurrent branch/blob publication can therefore leave no replacement or make
a supposedly unreachable object live while it is being deleted.

## Evidence

Current open members carry `class/n14` and are derived with
`bin/issues-index --class class/n14`.

## Owner

The Datahike/Seon reachability gate, `seon.cluster.registry` collection, and
blob root publication.

## Acceptance

- Replacement publishes the expected new head atomically or preserves the old
  head; there is no destroy-then-create operation.
- Collection holds one exclusive reachability basis from root derivation
  through the last delete, while branch/blob publishers hold the complementary
  permit through publication.
- Cold-reconnect properties cover branch resurrection, reused blobs,
  interruption, and an unchanged second sweep.
