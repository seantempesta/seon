---
type: issue
status: open
severity: blocker
tags: [issue, database, operator, performance, wave/exclusive-sweep]
---

# Store grew to 69 gigabytes in one day of lanes

## Problem

The shared root's `data/clusters/store` grew from 1.79 GiB (2026-08-12
evening `bin/seon status` footprint) to **69 GB** by 2026-08-13 morning —
one day of lane work, with a directory entry size of 23,122,688 bytes
implying hundreds of thousands of konserve files. The exclusive-sweep
machinery is supposed to be the recurring reclamation guarantee; either it
is not running on the shared root, cannot keep up with publication/eval
write amplification, or something new writes unreachable segments at this
rate. The known per-sample cost issue
([eval-samples-cost-42mb-of-store-each](eval-samples-cost-42mb-of-store-each.md))
may be one contributor, but a 38× daily growth factor needs its own
attribution.

## Evidence

- 2026-08-12 ~19:00: `bin/seon status` reported root footprint 1.79 GiB.
- 2026-08-13 ~12:30: `du -sh data/clusters` = 69 GB; `lsof` on the reset
  JVM showed `data/clusters/store` with dirent size 23,122,688.
- The store was deleted during the wedged reset (database data is
  disposable by owner ruling), so the artifact is gone; the next
  reproduction should capture a file census by konserve key prefix before
  deleting.

## Owner

The sweep/reclamation owners (`seon.cluster.export`/collection machinery)
and whatever tonight's lanes wrote at volume (publications, eval receipts,
blob retention). Attribution first: a per-key-prefix census on the next
bloated store names the writer.

## Acceptance

- The growth is attributed to named writers with a census.
- The recurring sweep provably bounds the shared root's store across a day
  of lane work, or the writer's amplification is fixed at cause.
