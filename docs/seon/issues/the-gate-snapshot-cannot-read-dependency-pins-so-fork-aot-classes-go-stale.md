---
type: issue
status: open
severity: blocker
tags: [issue, testing, bin/test, dev-cache, dependencies, wave/contract-gate]
---

# The gate snapshot cannot read dependency pins, so a fork's AOT classes go stale

## Problem

`dev_cache.clj` keys the dependency-classes cache on `deps.edn`, JVM
properties and the submodule pins read by `git -C <root> ls-files --stage --
reference-code` (`dev_cache.clj:203`, `:221-228`). A cold gate runs from a
run root produced by `git archive` (`bin/test:732`) — not a git repository —
so that command yields nothing there, the pin digest is empty, and the cache
digest is independent of every fork commit. The worker then puts
`target/dev-dependency-classes/<digest>` FIRST on its classpath, ahead of
`reference-code/datahike/src`, and loads the pre-fix compiled classes.

## Evidence (2026-09-18 ~04:50Z)

- Datahike fork fix `e11845ba` (listener completion), gitlink `95e2e1983`.
- Fast run in the repo (`bin/test-fast … seon.db-test`): 58 tests green —
  the fix in effect.
- Cold gate `tmp/orchestrator/gate-results/manifest-merge-gate.log`:
  `seon.db-test/a-throwing-datahike-listener-cannot-strand-a-committed-write`
  fails with `:seon.db/write-bound-exceeded` — the OLD writer behaviour.
  Retained root `tmp/test-runs/run.NL3zMp`: its classpath begins with
  `target/dev-dependency-classes/25e1db91…` (built Sep 17 14:56 local, before
  the gitlink moved) and that directory contains compiled
  `datahike/writer$create_thread…` classes; the root's
  `reference-code/datahike` is at `e11845ba`.

The same class as every silent-absence defect: a pin source that is missing
reads as "no pins" instead of refusing.

## Fix

The snapshot carries its pins: `bin/test` writes the source repo's
`git ls-files --stage -- reference-code` output into the run root at
snapshot time (beside `changed-paths.txt`), and `dev_cache.clj` reads pins
from that file when the root is not a git repository, REFUSING with a typed
error when neither source is available. Regression in
`seon.dev.dependency-cache-test`: a snapshot root with moved pins yields a
different cache digest; a root with no pin source refuses.
