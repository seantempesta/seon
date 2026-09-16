---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [publication, edit-hook, performance, lanes]
---

# Concurrent publications serialize past the hook's bound

## Problem

With three or four lanes editing at once, each `bin/seon init --dev default
--changed` publication queues behind the others and a single changed-path
publication was measured at 106 s wall (dominated by clj-kondo,
reconciliation, SCI acquisition and instrumentation, not the index — see
[issue-index-publication-cost](../../prds/steward-platform/research/issue-index-publication-cost-2026-09-16.md)).
The edit hook's worker then reports "Publication did not finish within its
declared bound" and one foreign publication of `src/seon/test.clj` exited 124
(`logs/current-source-failure.log`, 2026-09-16 ~10:00Z). Lanes that trust the
hook's "queued for publication" line then prove against un-adopted code.

## Why it matters

Publication is on every lane's critical path; a bound firing is a bug report
naming what never arrived, and today it names a queue, not a defect in the
edit. The ten-second-start rule applies to adoption too.

## Candidates

1. Coalesce: one publication per quiet window across ALL queued requests
   (the hook already coalesces per editor; make the operator coalesce across
   editors so N edits cost one publication).
2. Cut the per-publication constant: measure the four phases (kondo,
   reconcile, SCI acquisition, instrumentation) per changed path and make
   each incremental (the issue index already is).
3. Report adoption honestly at the hook: the hook line must say "adopted at
   commit X" or "queued; NOT adopted" so a lane cannot mistake queueing for
   adoption.

Related: `complete-publication-takes-seventy-seconds`,
`issue-indexing-at-publication-costs-13-seconds`.
