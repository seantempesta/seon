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

Two open storage issues were introduced on 2026-07-31 and 2026-08-05:
[[storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing]] and
[[ranged-store-collection-can-delete-live-segments-via-branch-resurrection]].

The recent archive shows three destructive multi-phase recurrences on
2026-08-07, 2026-08-08, and 2026-08-11:
[[archive/refork-held-a-store-across-the-arm-that-released-it]],
[[archive/init-force-destroys-the-branch-then-refuses-its-own-second-store-open]],
and [[archive/force-refork-can-destroy-the-branch-then-fail-silently-leaving-no-cluster]].
The force-refork branch replacement is now structurally atomic; GC/blob
reachability remains open.

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
