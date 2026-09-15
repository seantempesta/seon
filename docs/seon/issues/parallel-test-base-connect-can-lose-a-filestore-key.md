---
type: issue
status: open
severity: friction
tags: [issue, test, datahike]
---

# Parallel published-base acquisition can lose a filestore key

## Run7-wave recurrence — 2026-09-15

Three-worker named-path gate `run.nCxztY`, published base
`1ffe4236497fefef5818ec000c0c89180903189934a750bc94455c1e5afb3233`,
lost key `2a7db2a9-8a20-4448-8591-07850a3192d6` while
`my.plan-api-test/plan-writes-use-one-request-map-and-return-the-changed-item`
connected its fixture. The stack enters `konserve.filestore/migrate-old-files`,
`konserve.tiered/sync-on-connect`, and `datahike.connector/connect`.
Isolated confirmation passed in 17,360 ms with no prior worker-global drift.
The gate reported 55 tests / 1,080 assertions / zero assertion failures /
one acquisition error. The deleting actor is not established. Run7-wave's
final full named-path gate uses `SEON_TEST_WORKERS=1`; this is a verification
workaround, not a claim to have repaired this issue.

## Context-renders recurrence — 2026-09-15

Owned-path turn gate `tmp/test-runs/run.1zymx6`, published base
`5f121585eb18ce908d9823db643f75ca9f60c5de8baf59a4db888317ede74f93`,
lost key `87693937-5244-4f59-99be-e2076ef68226` during
`seon.turn-test/recovery-closes-a-turn-with-no-evaluations` acquisition.
The stack enters Konserve tiered sync and Datahike connect. The runner's
isolated confirmation passed and reported no earlier worker-global drift.
Overall: 25 tests / 531 assertions, zero failures and one acquisition
error. The deleting actor remains unknown. The fresh path-limited gate
passed recovery acquisition; it instead reported two debug-HTML assertions.
That later acquisition does not prove this store race resolved.

The focused continuation/status/REPL gate `tmp/test-runs/run.6Y77Tm`
then lost key `3104b0f0-37c6-42a9-8bac-8d6616268561` in base
`626b8ed267b4da513b1a5bdf6ee8166edf44367cbc4d9665b5eca47b25d301ac`.
The status accounting worker exited 1 after arming, before `task-complete`;
the stderr names `NoSuchFileException`. Overall: 19 tests / 225 assertions,
zero failures and one worker-exchange error. Final verification uses the
same canonical gate with `SEON_TEST_WORKERS=1`.

Evidence-listens recurrence, 2026-09-09: three-worker owned-path gate
`tmp/test-runs/run.KnfZkw`, base
`e62e5f0737a960e14d69729106558439c3efa3ff2b084b12a34c7e2e56830ec0`,
lost key `d34d9bab-c160-4e4f-8818-8788c5b942b8` while
`seon.db-test/diff-no-change-is-empty` acquired its canonical fixture.
Isolated confirmation passed with no detected worker-global drift. Later
one-worker gates did not reproduce it; the deleting actor remains unknown.

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

## HTML views recurrence — 2026-09-14

Three-worker gate `tmp/test-runs/run.PzoKkQ`, published base
`20cfe210df2d21069a035d88796ebd0b7a341b12c0844e31a501b44f5ce69a26`,
lost key `5a669849-a0f4-49e1-a4e8-3c5808b928e0` during
`seon.html-views-test/settings-pair-omits-absences-and-preserves-ai` fixture
acquisition. Isolated confirmation passed; no worker-global drift was
detected. A subsequent focused gate passed this test. The deleting actor
remains unknown. Evidence: `tmp/html-views/final-gate-2.log:531` and
`tmp/html-views/focused-gate.log`.
