---
type: issue
status: superseded
severity: cleanup
tags: [issue, testing, process, storage]
---

# Branch SIGINT fixtures can erase ownership before descendants exit

## Problem

The historical real-SIGINT fixtures create one UUID scratch root per case but
their cleanup can remove the root and its process records before every spawned
descendant has exited. Interrupted test JVMs bypass the fixture `finally`
entirely.

## Evidence

`test-old/seon/dev/branch_test.clj:264-314,316-360,362-404` creates
`branch-sigint-*`, `branch-sigint-reuse-*`, and
`branch-sigint-failure-*`. Each `finally` calls `.destroyForcibly` on only the
immediate owner and then immediately calls `fs/delete-tree`; it neither
captures descendants nor awaits the owner's exit. The emergency deletion
transcript recorded hundreds of these roots.

## Owner

The branch real-process fixture lifecycle, if any of this quarry is adopted
into the fresh test tree; otherwise deletion of the obsolete quarry tests.

## Acceptance

- A fixture records every exact spawned process identity and its scratch-root
  claim before starting work.
- Cleanup stops and awaits every child before removing process records or its
  exact claimed root.
- The same cleanup runs from the suite exit owner when ordinary fixture
  `finally` cannot run.
- An interrupted regression leaves neither a live descendant nor an unclaimed
  `branch-sigint-*` root.

## Resolution

Superseded on 2026-08-04. The creating suite exists only under the disabled
`test-old/` quarry and no fresh runner discovers or invokes it. The surviving
test-root owner is `bin/test`; commit `7eeff3e70` makes it publish and await its
exact runner process before retaining or deleting the root. The focused
fixture gate passed 9 tests / 30 assertions with zero failures or errors.
