---
type: issue
status: open
severity: blocker
tags: [issue, runtime, cleanup]
---

# `bin/seon up` exits 0 after a readiness timeout

## Problem

`bin/seon up` printed `✗ Timed out waiting for Seon process readiness.`
(watcher never became ready — it was still compiling the CLJS build) and
then exited with code 0. A caller scripting `up && <proof>` reads the
failed boot as success. This is the program's recurring failure class:
a check that reports health on absence of the signal.

## Evidence

2026-07-27, branch `codex/runtime-reliability-refactor`: background task
running `bin/seon cluster apply default && bin/seon up` completed with
exit code 0 while its output ends in the timeout error and
`bin/seon status` showed every process down. Watcher log
`logs/operator/watcher/fcfadf7f-5012-4b81-b7d5-95dd2bc380a5.log` ends in
`Worker shutdown.` mid-compile.

## Owner

`bin/seon` / `script/seon/dev/process.clj` reconcile loop — the exit path
for readiness timeout must propagate a non-zero exit.

## Acceptance

`bin/seon up` that ends in a readiness timeout or a process failure exits
non-zero; a green boot exits 0. One regression at the operator boundary.
Note: the operator chain is condemned by the nucleus ladder (B0, the
ten-second start ruling 2026-07-27); if `bin/seon start` replaces `up`
before this is fixed, close this with that commit.
