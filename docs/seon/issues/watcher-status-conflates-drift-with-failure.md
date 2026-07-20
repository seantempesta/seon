---
type: issue
status: open
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
