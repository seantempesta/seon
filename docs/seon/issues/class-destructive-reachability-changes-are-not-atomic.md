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

## Re-verified at HEAD (2026-09-15)

UNVERIFIABLE-WITHOUT-GATE (`seon.cluster.registry-test`, `seon.operator-test`, `seon.blob-test`). Audited HEAD `7e35df213:src/seon/cluster/registry.clj:250-285` replaces a branch through Datahike force-branch!, rather than destroy then create; `src/seon/operator.clj:928-960` retains the old branch until replacement. `src/seon/blob.clj:271-291` holds a reachability permit across blob publication AND root commit (commit `5019f5406`); registry collection at `:519-550` uses Datahike gc-storage with referenced blobs. These are existing corrections, not evidence that the historical race still reproduces. The member `ranged-store-collection-can-delete-live-segments-via-branch-resurrection.md` still owns the dangerous residual claim. Need interrupted replacement and branch-resurrection/concurrent-publication properties on a disposable store; never exercise collection or replacement on default. Retain blocker pending that proof.

surface: store-process
