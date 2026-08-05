---
type: issue
status: resolved
severity: blocker
tags: [issue, operator, process, reset]
---

# Operator process records had two authorities

## Problem

The installation-wide claims authority under `data/operator/claims/processes/`
coexisted with a compatibility read from each managed root's
`data/clusters/processes/`. Reconciliation copied a valid legacy record into
the claims authority, so deleting either copy did not converge. A confirmed
dead exact process record could therefore reappear and block forced reset.

## Evidence

Before the repair, a probe wrote one valid dead record only beneath
`tmp/p21-unit1-preprobe/data/clusters/processes/`. Calling
`reconcile-process-records!` created the same generation beneath
`data/operator/claims/processes/` and returned it as current.

The same losing directory also supplied the fallback log path for a discovered
operator JVM without its explicit log property.

## Owner

`seon.operator.state/process-claim-directory` is the one installation-wide
process-record authority. `seon.fresh-operator` filters those claims by their
canonical managed root and never reads a managed-root record directory.

## Acceptance

- Production source has no `data/clusters/processes/` read, write, relocation,
  or fallback path.
- A valid record placed only in the losing directory is ignored and never
  copied into the claims authority.
- After a real cluster JVM is forcibly terminated, `reset --force` removes
  its confirmed-dead exact claim and completes without manual file deletion.
- The claims directory is the only process-record location in production code.

## Resolution

Resolved by the commit containing this note. The compatibility relocation is
deleted, the recovered-log fallback now uses `data/clusters/logs/`, and the
recurring operator test injects a losing-directory duplicate before forcibly
terminating a real JVM.

The isolated live proof reported:

```text
PROCESS RECORD CENSUS ... records=1 unreadable=0
  pid=5852 ... state=not-alive
● JVM pid 5852 path=already-exited
● cleanup complete; reclaimed 49326215 bytes; removed .../data/clusters
● reset republished current-src and reforked default
```

The focused record gate passed 4 tests and 28 assertions with zero failures or
errors.
