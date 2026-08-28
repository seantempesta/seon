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

### Fresh single-session reproduction — Drive 1 Attempt 5, 2026-08-14

The preserved isolated root `tmp/drive-1-root` reproduced the class inside one
live session. At the observer's first census:

```text
FileStore allocated bytes: 11,711,643,648 (10.907 GiB)
FileStore logical bytes:   11,692,409,267 (10.889 GiB)
regular .ksv files:        8,554
cluster Lucene directory:  1.8 MiB
build artifacts:           7.9 MiB
```

The FileStore was therefore effectively the entire 10.9 GiB root. While the
specimen remained live during the read-only observation, a later census found
12,434 files and 11,996,090,368 allocated bytes (11.172 GiB). This temporal
comparison does not attribute those concurrent writes to the observer or a
particular running proc; it proves only that retention was still rising.

A cheap current-fact payload census grouped UTF-8 strings and byte arrays by
attribute namespace. Its 18,370,907 bytes (17.52 MiB) were:

| Attribute family | Current payload bytes |
|---|---:|
| `seon.error` | 9,564,409 |
| `seon.fn` | 3,033,164 |
| `seon.test` | 2,040,511 |
| `seon.schema.shape` | 1,310,773 |
| `seon.ns` | 365,826 |
| `seon.schema.map-entry` | 333,134 |
| `seon.ai.attempt` | 328,295 |
| `seon.context.capture` | 291,093 |
| `seon.cluster.eval` | 76,270 |

The largest exact attributes were `:seon.error/data-edn` at 9,560,791 bytes,
`:seon.fn/source` at 2,488,480, `:seon.test/source` at 1,950,163,
`:seon.ai.attempt/sent-body` at 308,590, and
`:seon.context.capture/prompt` at 290,652. Current fact payload is only about
1/636 of the physical logical bytes, so no current domain family explains the
footprint directly. Konserve key enumeration was also not a family census: it
returned opaque UUID keys with `:type :edn`, not writer/domain prefixes. The
remaining attribution must distinguish retained historical/index nodes and
unreachable objects from current fact payload without inferring a family from
UUID filenames.

## Owner

The sweep/reclamation owners (`seon.cluster.export`/collection machinery)
and whatever tonight's lanes wrote at volume (publications, eval receipts,
blob retention). Attribution first: a per-key-prefix census on the next
bloated store names the writer.

## Acceptance

- The growth is attributed to named writers with a census.
- Attribution reconciles physical bytes with current facts, retained
  historical/index nodes, and unreachable candidates; opaque UUID file names
  are not treated as family evidence.
- The recurring sweep provably bounds the shared root's store across a day
  of lane work, or the writer's amplification is fixed at cause.

### Post-reset regrowth data point — 2026-08-28 session start

The 2026-08-17 full reset brought the shared root to 0.27 GiB. At the
2026-08-28 session start, with zero clusters alive since, `data/store`
held 5,232 `.ksv` files totaling 9.6 GiB — the largest single blobs
158–184 MB each — a ~36× regrowth produced by one session of wave-A
work (init republish plus core-call-edge and schema-reference-graph
indexing). The class therefore reproduces without lanes and without a
long-lived live cluster; publication/indexing write amplification alone
accounts for order-of-magnitude regrowth. Store reset again this
session after this census.
