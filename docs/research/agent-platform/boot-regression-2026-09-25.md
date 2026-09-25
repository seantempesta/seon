---
type: reference
status: measured; read-only diagnosis lane diagnose-boot-30s
created: 2026-09-25
tags: [agent-platform, boot, publication, measured]
---

# Boot regression: 9.4 s → 30.1 s ready (2026-09-25)

The extra ~20 s is the boot publishing source files it has not seen before (the resume path in
`seon.cluster.boot/stand-boot-layers!`, boot.clj:185-195: `changed-source-paths` → `cluster/refresh-source!`).
Hook publication is paused (open-items.md:13), so every commit made after the last root checkpoint is
published by the next start, and a publication costs ~15-23 s whether it covers 2 files or 22.
It was not a one-time analyzer reanalysis, a kondo cache fill or a dependency-class miss.
Diagnosis was read-only: log, MCP `runtime_status` and read-only `eval_clj`. No JVM was started.

## Evidence

Boot phase logging was deleted in a79508fd1, so the timeline below comes from log timestamps and the
`:seon.source/built-at` of each `:current-src` commit (walked through `datahike.versioning/parent-commit-ids`).

| | boot 1 (after OOM of pid 39156) | boot 2 (pid 67823) |
|---|---|---|
| JVM process start (`ps lstart`) | not recorded | 00:50:43Z |
| `start!` entered (ready − ready-ms) | ≈23:30:44.1Z | ≈00:51:05.7Z |
| first store query (datahike estimate line) | 23:30:45.28Z | 00:51:06.99Z |
| source publication committed | none (checkout = head 23:26:51Z) | 00:51:30.6Z (commit 6ab5c592) |
| web view served (`seon.cluster` INFO) | 23:30:53.48Z | 00:51:35.79Z |
| `:seon.boot/ready-ms` | 9,382 | 30,051 |

- In boot 2, about 23.6 s of the 30.1 s went to the one publication, from store open (00:51:07.0) to commit (00:51:30.6).
  The remaining ~6.5 s is the same boot work boot 1 did in about 9 s.
- Boot 2 published 21 changed files and 1 deletion, compared with the 00:24:25Z checkpoint 6ab5bf39. They included the
  schema resources `seon.error.edn` and `seon.problems.edn`, plus `cluster.clj`, `instrument.clj`, `test.clj`, `maintenance.clj`,
  `problems.clj`, `render/value.clj` and 13 test files. These are the fault-resolution, status-bounds, dead-code and
  late-load-arming commits.
- It was not `:all`. The analyzer digest stored on 6ab5bf39 and the digest recomputed from its rows are both
  `9b606cb041`, so `analyzer-changed?` (cluster.clj:1983-1989) was false. c3a225dc7's full reanalysis did not fire at boot.
- The boot publication runs before `arm-host-program!` (boot.clj:84), so its armed profile does not exist.
  The next publication is profiled, and shows that size is not the driver. At 00:56:56Z, 6ab5c6d8 changed only `src/seon/db.clj`
  and `program_change_test.clj`, a `:selected` publication. Its single profiled call took
  `seon.cluster/full-source-refresh!` 16,352 ms, of which `seon.cluster.source/publish!` took 14,617 ms, and
  `development-source-refresh!` a further 11,428 ms. Cells shared with test runs report
  `seon.fn/analyze-rows` max 16,298 ms and `seon.fn.analyzer/invoke-kondo` max 8,419 ms (48 calls, 22,839 ms).
- A 2-file and a 22-file publication both take 15-23 s, so the cost scales with the whole program, not the change.
  This is the same shape as `docs/seon/issues/a-full-reanalysis-publication-takes-twenty-five-seconds.md`
  (published-index-rows, file-rows and pull storms), now observed on the `:selected` path.
- Also outside ready-ms: 22.7 s between JVM start and `start!` in boot 2 (launcher load and compile). The operator's
  wall time for this start was about 53 s. Boot 1's process start is unknown, so this gap has no baseline.

## Does it recur?

Yes, whenever a start finds the checkout different from the published head, which under paused hook publication means
every restart after un-checkpointed commits. A restart immediately after a root checkpoint publishes nothing and
should return to about 9 s, as boot 1 did.

The next publication will be worse. `6ab5c6d8` stores analyzer `9b606cb041`, but its own rows give `2f9ac65ab2`, because
`db.clj` is inside the analyzer's reach from `seon.fn/analyze-rows`. By c3a225dc7's design, the next publication,
at boot or at a checkpoint, is `:all`: a full reanalysis of 16-25 s. The reach includes `seon.db`, one of the most edited
namespaces, so that one-time reanalysis recurs often.

## Smallest fix

1. Operational, no code: root runs the checkpoint `bin/seon init --dev default --changed <paths>` before every
   `bin/seon down && bin/seon start`, so boot publishes nothing. The publication cost moves off the boot path but
   is still paid.
2. Root cause, in the owner's issue: make incremental publication cost follow the change. The 2-file `:selected` run
   spending 14.6 s in `source/publish!` is a >10 s defect that
   `a-full-reanalysis-publication-takes-twenty-five-seconds.md` does not yet cover, because that issue names only `:all`.
   The next step is a profiled probe with `seon.profile/explain` around one 2-file `refresh-source!` on a scratch cluster,
   to split analyze-rows, invoke-kondo and index rows. The analyzer's reach through `seon.db` should also be narrowed so that
   ordinary db edits stop forcing `:all`.
3. Do not move the boot publication after readiness. Boot refuses to run stale program rows (boot.clj:185-187),
   and that law stands.

Unverified: why the previous JVM stopped publishing after 00:24Z. The expected explanation is the paused hook and
root-only checkpoints (open-items.md:13), but no hook log was read. Per-phase boot times inside the publication are
not available because it is unprofiled. Re-adding a boot phase clock row would make the next regression readable.
