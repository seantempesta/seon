---
type: issue
status: open
severity: defect
tags: [issue, test-runner, drift]
---

# The runner's live-cluster drift snapshot is always empty

## Problem

`src/seon/test/runner.clj:627` reads `::snapshot-live-clusters` through
`(resolve-loaded 'seon.cluster/running-instances)`. `resolve-loaded` uses
`find-var` (`runner.clj:467-475`). `find-var` finds only *interned* Vars, but
`running-instances` was only *referred* into `seon.cluster` from
`seon.operator.runtime`. The lookup therefore returns nil, and the snapshot is
always `#{}`. The drift check has never compared live clusters, and it reports
nothing, which reads as health.

## Evidence

Default JVM (pid 90471), 2026-09-24:
`[(find-var 'seon.cluster/running-instances) (ns-resolve 'seon.cluster 'running-instances)]`
returns `[nil #'seon.operator.runtime/running-instances]`.

## Fix

Name the owner: `(resolve-loaded 'seon.operator.runtime/running-instances)`, as
`runner.clj:491` already does. Add a regression that the snapshot contains a
running cluster's name. Lane fix-entry-eval-2 removes the `:refer`, and that
removal does not change this behaviour: the lookup was already nil.
