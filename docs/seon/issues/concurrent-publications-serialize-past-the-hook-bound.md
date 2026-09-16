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

## S11 follow-up observation — 2026-09-16 22:06 UTC

The S11 follow-up's explicit `init --dev default --changed
src/seon/render/transcript.clj` reported waiting for the lifecycle lock held
by PID 69608, whose changed-path publication began at 21:55:31 UTC. It then
reported `current-src: request accepted`, but the caller was still waiting
after eight minutes. `logs/current-source-failure.log` was modified at
22:04:31 UTC and contained exactly `Publication did not finish within its
declared bound.` This establishes unverified adoption, not a cause inside
the S11 edits. The lane used isolated fast tests and claimed no post-edit
default or browser proof. Raw operator output: `tmp/s11-publication.log`.
The explicit caller subsequently exited with `Source changed while incremental
publication was being analyzed.` Its before/after digests were
`259ae978a6a4d85011bc77f5ad249405a5fbfdeb149e303028e63108d103eec5` and
`a9b821678cb32cfa17338813ca23d23f0f46781f3b669a2612c6ca995d9d03cd`.
