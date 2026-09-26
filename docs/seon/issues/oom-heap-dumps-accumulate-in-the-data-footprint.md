---
type: issue
status: open
created: 2026-09-26
severity: friction
tags: [issue, operator, disk, heap]
---

# OOM heap dumps accumulate in the data footprint

`bin/seon status` reported `:file-bytes 53274842530` on 2026-09-26. That is
`seon.fs/footprint` of all of `data/` (fs.clj:55-66). It is not the store.
`du -sk data` gave 53.4 GB:

- `data/heap`: 40 GB, from three OOM dumps written by
  `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=data/heap` (deps.edn:163):
  - `java_pid70372.hprof`: 14.0 GB, 2026-09-24 12:08
  - `java_pid39156.hprof`: 10.8 GB, 2026-09-24 17:27
  - `java_pid67823.hprof`: 17.8 GB, 2026-09-24 21:09
- `data/store`: 4.7 GB, 28,121 konserve files since the 00:16Z nuke.
- `data/clusters/default/logs`: 3.5 GB, almost all `seon.log.1` from 2026-09-24.
- `data/operator/operations`: 1.4 GB.

The decision that added the flag was "keep the newest only"
(`published-head-references-never-written-index-nodes.md`, option b). Nothing
enforces it, and a dump is never reclaimed.

The store's own growth after the nuke was about 1.2–2.1 GB per 10 minutes
between 18:20 and 18:50 local, while root ran test requests (7,809
`Testing seon` lines in seon.log). The roster then held 5 branches. So the
growth is unlinked test-branch data waiting for collection. That is the open
`storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing` and
`shared-store-grows-during-render-source-work`, not a new producer.

Wanted:

- The launcher or maintenance keeps only the newest dump, or moves dumps out
  of `data/`.
- Status names the footprint per owner (store, heap, logs, operations), so
  one number is not read as the store's size.
