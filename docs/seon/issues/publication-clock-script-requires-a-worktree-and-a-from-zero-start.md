---
type: issue
status: open
severity: blocking
created: 2026-09-23
tags: [issue, publication, measurement, tooling]
---

# The publication clock script requires a worktree and a from-zero start

AGENTS.md requires every publication-path slice to land with a clock row from
`docs/prds/steward-platform/research/measure-publication-path-2026-09-22.sh`.
The script cannot run under the rules in force since 2026-09-23:

- it creates `git worktree add "$WT" HEAD` (line 13); lanes never create
  worktrees;
- its first clock is `bin/seon --root "$ROOT" start head` on a freshly deleted
  root (lines 21-23, 49-51): a from-zero start, ruled out by the owner
  ("a schema change should not require a from scratch boot. Period.").
  `PUBLICATION_CLOCK_RESUME=1` skips the start and fork but still requires the
  worktree and an already-running `head` cluster on that root.

Observed by lane publication-lock-deletion (commits `a102a8403`, `874918765`),
which therefore landed publication-path changes without a clock row.

Wanted: a mode that clocks publication and development adoption against a root
and cluster the caller already holds (no worktree, no reset), reading the
source from a `git archive` snapshot or the shared tree, so every
publication-path landing can produce its row.
