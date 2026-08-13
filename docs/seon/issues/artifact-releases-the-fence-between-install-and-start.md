---
type: issue
status: open
severity: friction
tags: [issue, database, class/n4, wave/artifact-startup]
---

# Hold one store ownership interval across artifact install and start

## Problem

The standalone artifact releases the process-root store after installing
packaged source and reacquires it only during cluster startup. A competing JVM
can win that gap and make this artifact fail after it has already mutated the
root.

This is an availability and startup-atomicity defect, not a demonstrated
two-writer corruption path: every actual store access remains fenced and the
loser should fail before opening Datahike.

## Evidence

- `src/seon/artifact.clj:51-70` opens, publishes, then releases the store.
- `src/seon/artifact.clj:72-97` starts the cluster only after that function
  returns.
- `src/seon/cluster.clj:1309-1329` reacquires the process-root store before
  opening the branch.
- Repository-wide source/test search found no recurring artifact test for this
  handoff.

## Owner

The standalone artifact entry and `seon.cluster`'s existing root-store holder.

## Acceptance

Packaged installation and the started cluster share one explicit store-holder
interval, or the artifact is made an explicit two-phase operator whose
non-atomic behavior is intentional and tested. A competing opener cannot make
startup fail after an unreported partial lifecycle.
