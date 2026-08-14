---
type: issue
status: open
severity: friction
tags: [issue, operator, runtime]
---

# Recursive delete throws when entries vanish mid-walk

## Problem

`seon.fs/delete-recursively!` throws `java.nio.file.NoSuchFileException`
when an entry it enumerated disappears before it deletes it — observed
2026-08-14 when a test-run retained-root sweep raced a concurrently
exiting worker (`tmp/test-runs/run.lwVDTP/workers/pool-3/...`), failing
a lane's focused gate before any test ran. Deletion of a tree whose
entries may be concurrently removed is the NORMAL case for sweep paths
(workers clean up as they exit); an already-gone entry is deletion
SUCCEEDING, not an error.

## Owner

`seon.fs/delete-recursively!` (`src/seon/fs.clj`): treat
NoSuchFileException per entry as satisfied-and-continue (count it in
the progress callback's tally), preserving the symlink-safety and
bound/progress behavior. One regression: delete a tree while a
competing thread removes entries mid-walk; the walk completes and
reports honestly.

## Acceptance

The concurrent-vanish regression passes; the sweep path no longer fails
a gate on a racing worker cleanup.
