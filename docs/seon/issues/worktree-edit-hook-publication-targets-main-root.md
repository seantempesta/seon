---
type: issue
status: open
severity: friction
tags: [issue, tooling, worktree]
---

# Worktree edits queue publication against the main root

The fresh-tree hook reproduces the checkout-routing class previously archived
in [worktree-edit-hook-checkout-drift.md](archive/worktree-edit-hook-checkout-drift.md).

On 2026-09-17, applying the proposed SCI hook hunk only to
`tmp/repl-program-operations-wt/src/seon/sci/eval.clj` queued main-root source
publication job `bfe2aa55-66cb-44d1-ae59-59193637e064`. Its result is recorded in
`tmp/source-publications/bfe2aa55-66cb-44d1-ae59-59193637e064.edn`; the worker was
PID 51675 and the operation log was
`data/operator/operations/init-init-51676.log`. Publication refused because
source changed during analysis (digests `544519ae2fee8c56780ea8977d4cc7666979ee78cee7172f71e583c20d104a96`
and `c5e840e7ed455050d3daf451d867a2bb86fcfc541a9bf995829cb1df2f746f49`).
No successful adoption of that worktree hunk is claimed.

The disposable worktree was explicitly for HEAD-plus-owned-paths testing;
its native-hook hunk was not present in the held main-tree SCI file. Hook
feedback must identify and honor the edited checkout, or explicitly decline
publication for an isolated worktree. A worktree edit must never silently
schedule publication against another checkout. The hook owner is `bin/seon-hook`,
currently held by the tooling lane; this lane preserved that file.

Acceptance: a regression applies an edit inside a worktree and observes either
publication scoped to that worktree or a typed, explicit skipped-publication
result, with no main-root publication job.

A second observation from `reset-batch` in the same date: read-only `sed`
commands with explicit workdir `tmp/reset-batch-wt` received derived-write
refusals naming unmatched parentheses in the main checkout's concurrently
edited `test/seon/render/web_debug_test.clj` (line 1, end lines 1080 and 1098).
That path was clean in the selected worktree at the time; subsequent reads
succeeded. The same acceptance must cover attribution: a returning reader
must not be blamed for another checkout's writer. The hook was not edited or
disabled by this lane.
