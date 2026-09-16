---
type: issue
status: open
severity: friction
tags: [test, runner, bounded-execution, total-tally]
---

# A nonexistent test namespace crashes the coordinator instead of a typed tally

## Observed

Batch 69 B on `54d3ee20f`, 2026-09-16 06:52 CST: the selection named
`my.test-test`, which has no file under `test/`. The coordinator loaded
four namespaces, then threw at `seon.test.runner/run-coordinator!`
(`src/seon/test/runner.clj:3322`, the `require` under `REQUIRE_LOCK`):

```
Execution error (FileNotFoundException) at seon.test.runner/run-coordinator!$fn (runner.clj:3322).
Could not locate my/test_test__init.class, my/test_test.clj or my/test_test.cljc on classpath.
```

No tally line was printed, the four loaded namespaces never ran, the
isolated root `tmp/test-runs/run.Ok1sVf` was retained as "failed", and
nothing was recorded. Log: `tmp/orchestrator/gate-results/batch-69/named.log`.

## Why it matters

"The tally is total — unlaunchable or unconfirmed work is typed, never
silent" (CLAUDE.md §5). A misspelled or deleted namespace is ordinary
input; it should appear in the tally as one typed unloadable entry naming
the symbol and the classpath miss, while every other selected namespace
still runs and records. Today one typo costs the whole batch, its slot,
and a retained root.

## Fix shape

At the load loop, catch the load failure per namespace into a typed
`:seon.test.runner/unloadable` entry carried into the tally (the same
shape the worker-exchange bound already uses), continue with the rest,
and exit red with the entry printed. Regression: a selection with one
nonexistent namespace runs the others and reports exactly one typed
unloadable entry.
