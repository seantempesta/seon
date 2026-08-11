---
type: issue
status: open
severity: blocker
tags: [issue, testing, concurrency, operator]
---

# Await every worker writer before deleting its root

## Problem

The changed-files gate cannot settle because
`seon.test-runner-test/interrupted-launcher-awaits-its-runner-before-retaining-the-root`
tries to delete a worker root while its clj-kondo cache directory is not empty.
The same test fails in the runner's fresh isolated confirmation, so reducing
worker parallelism or classifying it as parallel-only would hide the failure.

## Evidence

The 2026-08-11 changed-files run for commit `2a19869c7` completed 1,109 tests
and 8,968 assertions with one error. Both the pool attempt and isolated
confirmation ended in:

```text
java.nio.file.DirectoryNotEmptyException:
.../workers/pool-8/.clj-kondo/.cache/v1/clj
  at seon.fs/delete-recursively! (fs.clj:69)
```

The runner retained the failed isolated root at
`tmp/test-runs/run.9lLkUI`. This note records only the observed boundary;
whether a process, thread, or cache writer outlived its published completion
still needs a direct probe.

## Owner

The test-runner interruption/completion boundary and the worker-root cleanup
that consumes it. `seon.fs/delete-recursively!` is the visible refusal site,
not yet an attributed cause.

## Acceptance

- The interrupted launcher publishes completion only after every writer under
  its worker root has stopped using that root.
- The existing regression passes in the worker pool and in a fresh isolated
  confirmation without retrying directory deletion on a clock.
- The schema-sensitive changed-files gate completes without retaining a failed
  operator root.
