---
type: issue
status: open
severity: friction
tags: [issue, cluster, boot, runtime]
---

# Cluster boot instruments in-flight working-tree vars

## Problem

`bin/seon start <name>` on the shared root forks the PUBLISHED commit
for its data, but boot then runs `seon.instrument/apply!` over the live
host JVM's loaded Vars — which carry whatever the working tree held
when they loaded. A lane's uncommitted namespace (a Var present, its
schema absent from the published projection) fails contract
registration and the whole cluster boot with it. Fork-from-published
promises churn immunity that the shared-JVM Var population does not
deliver: a new cluster's boot can be broken by any lane's half-edit.

## Evidence

2026-08-14 Drive 1: `bin/seon start drive-one` created the published
branch, then boot failed registering the uncommitted
`seon.await/await!` (`Invalid schema: :seon.await/request` — the Var
loaded from the working tree, the schema not yet in the published
projection). The drive moved to an isolated `--root` JVM as the
workaround.

The independent observer watched the specified shared-root advertisement
boundary from 05:27Z through 06:10Z. `bin/seon status` advertised only
`default`, `mcp__seon__runtime_status` returned an empty cluster set for
`drive-one`, and `data/clusters/drive-one` never existed. Only after the
45-minute boundary did a filesystem search find the fallback at
`tmp/drive-1-root`, where it was advertised as cluster `default`, not
`drive-one`. The workaround therefore also made the live drive invisible to
the independent observer under the agreed discovery contract.

## Owner

The boot/instrumentation seam. Candidate shapes (design, not ruled):
instrumentation scopes to the vars the published projection declares
(unregistered extras get a typed diagnostic, never a boot failure), or
shared-root cluster starts declare that they instrument the live JVM
honestly and the isolated `--root` path is the ruled substrate for
churn-sensitive work (documented, not discovered mid-drive).

## Acceptance

A shared-root cluster boot with a half-edited foreign namespace either
boots with a typed diagnostic naming the unregistered var or refuses
with a message naming the churn source — never an opaque contract
registration failure; one regression proves it.

If an isolated-root fallback is the ruled substrate, the drive handoff names
that root and actual cluster name through an observer-visible fact before the
episode begins; an observer must not spend the complete watch window polling a
name that cannot appear.
