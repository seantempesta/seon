---
type: issue
status: open
severity: friction
tags: [issue, test, datahike]
---

# Parallel published-base acquisition can lose a filestore key

## Problem

A three-worker path gate failed while connecting a canonical fixture base,
before its recovery test could assert behavior. The same task passed the
runner's isolated confirmation. No worker-global instrumentation drift was
reported; the actor removing the file has not been established.

## Evidence

2026-09-09, snapshot `b59cc419bcda8d1bdf85d59631026bf74300fd49f7eb4743db119f9d7802ff53`:
`seon.turn-test/recovery-cannot-stamp-a-settled-receipt` raised
`NoSuchFileException` for published-base store key
`27219425-ee05-4341-9863-fb0d4c27926d`. The stack enters
`konserve.filestore/migrate-old-files`, `konserve.tiered/sync-on-connect`,
and `datahike.connector/connect`. Log: `tmp/turn-rename-gate.log`.
The isolated confirmation passed. The rename's final gate uses one worker.

## Owner

Test-base/fixture acquisition and the Konserve/Datahike store seam.

## Acceptance

Probe simultaneous canonical-base acquisitions with bounded completion;
identify the file mutation owner and preserve all reachable keys throughout
connection. A passing isolated rerun alone does not establish parallel safety.

## Recurrence in the namespace move — 2026-09-09

Snapshot `e9d7f62a43db5bf3e2e9a20591d37c9a224aa7459a02284ab3cce60e21ae2de2`,
root `tmp/test-runs/run.krpVAa`: `seon.turn-work-test/situation-totality-property`
raised `NoSuchFileException` for base store key
`40c4dc77-b976-4c53-a5d6-9a7f212d48b9`, through the same filestore migration,
tiered sync, and connector frames. Log `tmp/turn-namespace-gate.log:376`.
This invocation used one worker but overlapped a separate one-worker platform
gate. Its isolated confirmation passed. The removing actor remains unknown;
this evidence does not attribute the loss to either runner or to GC.
The lane repeats the final gate and platform serially after the source fix.
