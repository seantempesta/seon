---
type: issue
status: open
severity: friction
tags: [issue, test, operator, wave/operator-child-lifecycle]
---

# A nested test snapshot overwrites its fresh run claim

Observed 2026-09-15 in the test-provenance fast gate's existing concurrent
launcher regression. Parent snapshot `run.TNojzc` launched children with PIDs
28254 and 28255. Both child roots (`run.ld9ewy`, `run.fsbo8e`) instead contained
the parent's `test-run.txt` header: PID 25560, suite start
`2026-09-15T17:55:07Z`, and the parent's four-namespace selection.

`bin/test:493–499` includes all non-ignored untracked files in the default
overlay. When invoked from an already prepared snapshot, this includes its
`test-run.txt`; `bin/test:517` copies it over the child's fresh claim written at
line 330. The later snapshot-report exclusion at line 534 only hides the file
from reporting. Both children completed, but their owner claims were wrong.

The snapshot owner should exclude its own generated run metadata from input
population. Extend the launcher regression to start from a prepared snapshot
and assert each child's recorded PID and selection match that child. This
population seam is outside the test-provenance assignment's destination change.
