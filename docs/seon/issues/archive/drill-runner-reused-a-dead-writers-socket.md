---
type: issue
status: resolved
severity: minor
tags: [issue, architecture]
---

# Make the kill-drill runner detect a dead drill writer's socket

## Problem

`tmp/sci-probe/jvm/drill.sh` started its private drill writer only when
`tmp/host-drill/writer.sock` was absent. A socket FILE outlives its
writer process (kill, reboot), so a stale socket from an earlier drill
made the runner skip the writer start and the drill client failed
immediately with `ConnectException` at the first UDS connect.

## Evidence

U2's required kill-drill re-run (2026-07-20): `tmp/host-drill/` held
`writer.sock` and a `writer.log` from a writer booted at 17:59 whose pid
was no longer alive; the drill run produced only
`Execution error (ConnectException) at sun.nio.ch.UnixDomainSockets/connect0`.
Removing the stale directory and re-running passed
(`DRILL {:phase :done, :pass? true}`).

## Resolution

Fixed in the same unit: `drill.sh` now removes the socket when no
`seon.db.server` process serving `host-drill` is alive before the
existence check, so a fresh writer starts. Committed with the U2
wrapper-registry unit; behavioral proof is the passing clean re-run
above.
