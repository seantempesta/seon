---
type: issue
status: open
severity: blocker
tags: [issue, testing, bin/test, snapshot, wave/contract-gate]
---

# A bare cold gate's snapshot carries uncommitted working-tree files

## Problem

`bin/test --platform` with no `--paths` is documented as a HEAD-exact
snapshot ("snapshot HEAD plus named paths"). On 2026-09-17 ~22:10Z a bare
platform gate on HEAD `f9eab9e31` (retained root `tmp/test-runs/run.OZGw7Q`)
refused in `seon.test.runner/verify-long-declarations-indexed!` naming eight
`seon.dev.fresh-operator-reset-test` tests, one of which
(`reset-phase-records-derive-incomplete-status-and-continuation`) exists ONLY
in a lane's uncommitted edit of `test/seon/dev/fresh_operator_reset_test.clj`:

```text
grep -c … tmp/test-runs/run.OZGw7Q/test/seon/dev/fresh_operator_reset_test.clj  → 1
git show HEAD:test/seon/dev/fresh_operator_reset_test.clj | grep -c …           → 0
```

The launcher printed "snapshot differences from HEAD f9eab9e31: (none)" for
that run. `bin/test:716` copies the source tree into the run root before
`bin/test:732` extracts `git archive "$git_sha"` over it; a modified tracked
file survives that order or the difference report misses it. Either way the
gate tested a tree that was not HEAD while claiming it was, and its published
base (indexed from HEAD) disagreed with the loaded Vars — the exact drift the
check exists to catch, caused by the launcher itself.

## Consequence

While any lane holds an uncommitted edit, the orchestrator's cold gate is not
a proof of HEAD. With four lanes editing, that is always.

## Owner

`bin/test` (the launcher). Fix: the bare snapshot is `git archive HEAD` alone
plus explicitly named `--paths`; the difference report compares the run root
against `git ls-tree HEAD` and refuses when a non-named path differs.
Regression in `test/seon/test_runner_test.clj` beside the overlay tests: a
dirty tracked test file not named in `--paths` does not reach the run root.
