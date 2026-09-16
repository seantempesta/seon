---
type: issue
status: open
severity: friction
tags: [issue, test, runtime, class/p3]
---

# In-process test timeout returns before its fixture releases

At 2026-09-16 03:05:28 UTC, isolated cluster `turn-test-reds19` started
`seon.cluster.turn-test/generated-model-attempt-traces-preserve-presence-and-episode-laws`
on a fresh canonical base. In-process run 48413 recorded 0 passes, 0 failures,
1 error when `:seon.test/remaining-ms` reached its declared 120000 ms bound.
The helper then called the canonical `close-base!` owner and received
`Cannot delete a database with active connections. Release them first.`

`src/seon/test.clj:42` owns the virtual-thread FutureTask. Its `finally`
cancels unfinished work (`:78`) but does not observe thread/fixture completion
before returning a terminal test result. The reproduction establishes that
cancellation had not completed resource release; it does not identify the
blocking fixture operation. The lane shut down only its owned operator root
after recording the failure, so no abandoned fixture thread survived reporting.
Default was untouched.

Acceptance: a bounded in-process test reports both its execution verdict and
cleanup completion before callers may reclaim its canonical base. Preserve the
declared bound and the 48-case property; do not infer cleanup from cancellation.
The runner owner must name the missing completion event. The
[complete recorded timeout](../../prds/context-generation/research/turn-test-reds-batch19-boundaries-2026-09-16.edn)
is retained with the turn-test batch-19 landing record.
