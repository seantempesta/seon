---
type: issue
status: open
severity: platform
created: 2026-09-25
tags: [issue, boot, stop, projection]
---

# Stop marks running-instances with a bare Object that projection readers refuse

Seen 2026-09-25 23:44:06Z on `bin/seon down` of the scratch root `tmp/lane-fix-boot-dep/snap` (HEAD 71f14d1d4).
The stop was reported under `:graceful-failures` as `SEON CORE FAULT (panic)`:

> seon.db/loaded-builder refused instances at [0]: expected a map, got an instance of java.lang.Object.

How it happens:

1. `seon.cluster.boot/stop!` claims the instance by swapping a bare `(Object.)` marker into `running-instances`
   (`src/seon/cluster/boot.clj:495-499`). Start does the same with its reservation (`:419-423`).
2. `stop!` then calls `cluster/disarm-agents!`, which reaches `seon.config/effective` and then
   `seon.db/carried-projection`.
3. `carried-projection` passes `(vals @running-instances)`, unfiltered, into `loaded-builder`, whose contract takes
   `[:maybe [:sequential :map]]` (`src/seon/db.clj:1536-1542`).

The fault is in the producer's shape. `boot.clj:358` filters `map?` for the same reason, and `db.clj:231`,
`cluster.clj:509`, `test/runner.clj:498,635` read the same atom.

The process still exited, but a stop that faults is not a clean stop. Fix at the producer: make the marker a
declared map (or keep it out of the map of instances) and delete the reader-side `map?` filter.
