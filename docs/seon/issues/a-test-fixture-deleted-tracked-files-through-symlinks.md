---
type: issue
status: open
severity: blocker
tags: [issue, testing, tooling]
---

# A test fixture deleted 55 tracked paths by following symlinks

## Problem

A cluster-reset test fixture recursively deleted a directory tree and
FOLLOWED SYMLINKS out of its own scratch area into the repository, removing
**55 tracked paths**: 45 files under `src/` (the entire maintained source
tree) and 13 vendored `reference-code/` submodule working trees.

The fixture believed it was cleaning a scratch cluster directory. The links
it followed are the ones introduced the same evening when the three skill
directories were collapsed into one real directory plus two symlinks
(ruling 29) — so a design change that made one class of bug impossible
handed a fixture a path out of its sandbox.

Nothing was permanently lost (everything deleted was committed, and the
Datahike fork commit `19f5cdd9` survived in the submodule's own object
store), but a fixture that can empty `src/` while a suite runs is a
data-loss mechanism, not a test.

## Evidence

Inventory the lane captured before stopping:
`tmp/accidental-deletions-20260729.txt`.

Observed state: `ls src/` empty, `git status` showing 55 ` D` entries,
`git submodule status` showing 13 entries prefixed `-` (working trees gone,
gitlinks intact), and `bin/test` failing to build a classpath because
`reference-code/datahike` had no manifest.

Recovery performed by the orchestrator: `git checkout --` restricted to
exactly the deleted paths (nothing staged, so no in-flight lane work was
overwritten — the three concurrently modified files were left untouched),
then `git submodule update --init` for the 13 emptied submodules.

Credit where due: the lane DETECTED its own damage, captured a precise
inventory, refused to self-recover without authorization, and reported the
blocking boundary. That is the correct behavior and it is why this was a
ten-minute recovery.

## Owner

Whichever test-support fixture performs recursive cluster/scratch cleanup
(the reset path used by the cluster-priming work). The rule it needs is
narrow and absolute.

## Acceptance

- Recursive deletion in any fixture NEVER follows symlinks
  (`Files/walkFileTree` without `FOLLOW_LINKS`, or an explicit `lstat`
  check per entry), and refuses any path that resolves outside the
  fixture's own scratch root.
- A regression plants a symlink to a sentinel file inside a scratch
  directory, runs cleanup, and asserts the sentinel SURVIVES.
- The deletion boundary is derived from the fixture's own root, never from
  the process working directory (the operator work established the same
  lesson: process CWD is not authoritative ownership).

## Related

- Ruling 29 collapsed the skill trees to one real directory plus symlinks;
  this is the sharp edge that change exposed.
- [[root-store-holder-does-not-canonicalize-store-dir]] — the same family:
  path identity treated casually.
