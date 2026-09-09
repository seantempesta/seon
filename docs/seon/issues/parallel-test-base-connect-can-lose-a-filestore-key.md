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
