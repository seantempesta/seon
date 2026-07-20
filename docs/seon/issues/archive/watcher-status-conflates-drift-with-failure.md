---
type: issue
status: resolved
severity: friction
tags: [issue, architecture]
---

# Watcher status conflates artifact drift with failure

## Problem

`bin/seon status` reports the watcher `not-ready` (degraded) whenever
source digests drift from the launch-recorded artifact — including the
ordinary situation of live editing during incremental watching. During the
B10 live reload-storm verification this made "ready" unusable as a
survival probe: the client was healthy and serving while status looked
degraded.

## Expected owner

The operator status derivation (`script/seon/dev/`) should distinguish
"artifact drifted, rebuild pending" from "process failing", so live-proof
probes can assert health during reload testing.

## Acceptance

During an active edit-reload cycle with a healthy client, status shows a
drift-aware healthy state; a genuinely failed watcher still reports
failure.

## Fix (2026-07-20)

`script/seon/dev/process.clj` `status` now derives
`:seon.dev.process/rebuild-pending? true` for an alive watcher whose
readiness probe fails without a newer `Build failure:` line than the last
`Build completed.` (digest drift or an in-flight compile). When every
process is alive and every not-ready process is only rebuild-pending (and
external dependencies are ready), the target status is the new
`:seon.dev.target.status/rebuilding` instead of `degraded`; `bin/seon
status` prints `◐ Seon rebuilding` and `watcher alive rebuild-pending`.
A watcher whose newest terminal build line is a failure, or whose process
died, still reports degraded/down.

## Proof

- New operator test `watcher-drift-reports-rebuild-pending-not-failure`
  (drift → `rebuilding` + `rebuild-pending?`; appended `Build failure:`
  → `degraded`, no `rebuild-pending?`).
- Full gate: `bin/seon test operator` — 289 tests, 1619 assertions,
  0 failures, 0 errors.
- Live: the running default cluster was mid-drift during this change;
  `bin/seon status` reported `◐ Seon rebuilding / watcher alive
  pid=96748 rebuild-pending / writer alive / pod alive` where it
  previously reported a bare degraded not-ready.
