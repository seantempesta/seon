---
type: issue
status: open
severity: friction
tags: [issue, test, datahike]
---

# Parallel published-base acquisition can lose a filestore key

## Loop-runtime recurrence — 2026-09-10 01:43 UTC

The three-worker loop gate at `617e538f3` plus the loop paths lost published
base `3688ade0a905f0ba729f2b9d0c7af7d025c71b197bb904bd1d9be9634c06fe57`
key `de01176c-769f-40ec-a35e-df311c1c0a8e` while connecting the canonical
fixture for `seon.loop-proof-test/running-fixture-settles-its-seeded-wake`.
The same filestore migration/tiered sync/connector stack appeared. Its
isolated confirmation passed with no prior worker-global drift. The lane
used a separate worktree cache and one worker for its final gate; no
foreign runner or store was operated. This repeats the observation and
does not establish the deleting actor.

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

## Recurrence in context blocks — 2026-09-09

The slice-4 three-worker gate at HEAD `e506861ac` plus its named paths
raised `NoSuchFileException` in
`my.plan-test/nested-steps-derive-parent-depth-and-order` for published base
`9d0eb8b029e072fd7ef0661082b437bf0b825e51ad9365be58dce4cba2a0c151`, key
`88fe77b0-bc40-4949-8d5b-f4a9f458a006`. The runner's isolated confirmation
passed and reported no worker-global drift. Log:
`tmp/context-blocks/gate-slice4.log`. The actor remains unestablished;
no other lane's process or files were operated.

## Recurrence in the data lane — 2026-09-09

Three-worker path gate `tmp/test-runs/run.AzsyoH`, published base
`e9d919732fc91fcf5dbbac584c66e9996f417a193cc04df5a434a825f2bd98cf`, lost key
`8f0704c2-a60b-4252-b600-0d8b69af6244`. Pool worker 2 exited before completing
`my.plan-test/a-changed-title-is-an-ordinary-fact-update`; isolated confirmation
passed. The source regression also had an independently reproducible SCI
fixture defect, corrected by acquiring the agent context through
`fork-for-turn`. Subsequent data-lane gates run serially with one worker.
The deleting actor is still unestablished.
