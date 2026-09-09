---
type: issue
status: resolved
severity: blocker
tags: [issue, test, operator, wave/test-fixture]
---

# Published test cache reclamation refuses its linked root

## Problem

Nested gates access the shared published-base directory through a worker
symlink. The cache owner supplies that unresolved path as the deletion root;
`seon.fs/delete-recursively!` correctly refuses crossing an intermediate
symlink. A nested gate exits before producing a tally.

## Evidence

2026-09-09 selected runner gate at `5cebd004e`: 43 tests / 272 assertions,
6 failures in `concurrent-bin-test-invocations-both-reach-their-tallies`.
Both child launchers exit 1 with "Recursive deletion target crosses an
intermediate symlink." The stack names `seon.test.cache/reap!` and the
worker's `target/test-published-bases` path. Other worker-exchange class
regressions passed; this is not the historical missing-reply wedge.

## Owner

`src/seon/test/cache.clj` must resolve the cache directory it owns before
constructing child paths and invoking recursive deletion. Deletion retains
its refusal and never follows child symlinks.

## Acceptance

The existing retention regression exercises the linked cache root, reclaims
inactive bases, preserves live references and a symlinked external sentinel;
the concurrent launcher regression and runner namespace pass. Verification
of the canonical-root change is in progress.

## Resolution — 2026-09-09

The cache owner resolves its parent with `File.getCanonicalFile` before
constructing child paths and at reclamation entry. The existing retention
regression now enters through a linked parent and preserves its external
sentinel. Recursive deletion still never follows child symlinks.

Fast runner suite: 43 tests / 268 assertions, green. Exact isolated gate:
`bin/test --paths src/seon/test/cache.clj test/seon/test_runner_test.clj -- seon.test-runner-test`
ran 43 tests / 270 assertions, green; both real concurrent launchers reached
their tallies (77,138 ms for that class regression). Platform on the same
selected paths: 83 tests / 490 assertions, green. Selected native lint:
zero errors / 532 warnings, 4,561 ms.
