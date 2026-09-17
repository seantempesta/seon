---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, test, operator, wave/dev-tooling-face-hygiene]
---

# Fast path snapshots are misclassified as full gates

## Problem

The process census classified this lane's explicitly authorized fast run as
a forbidden full gate and terminated it. Reading the executable name alone
does not identify the selected test mode: the fast launcher's path-selection
arm reuses the gate's snapshot code.

## Evidence

The working-edge entry in commit `36aa40b8b` says that the program-ops lane's
issue-generate run was a full gate and was killed. The exact invocation was:

```sh
SEON_TEST_SLOTS=3 bin/test-fast --paths src/my/program.clj src/seon/issue.clj resources/seon/schemas/my.program.edn resources/seon/schemas/seon.program.edn test/my/program_test.clj test/my/program_query_test.clj -- my.program-test my.program-query-test seon.issue-generate-test
```

At that commit, `bin/test-fast:13–14` executes `bin/test --fast` when passed
`--paths`. The log identifies snapshot `tmp/test-runs/run.sD1cPM`, acquired
its slot after 240 seconds, and reports `role= test-fast`, `worker= test-fast`,
and 1,108 armed contracts. It received TERM and exited 143 before any test
completed; its cleanup removed the snapshot. No cold gate, platform tier,
published base, or worker pool was requested by this lane.

## Owner

The operator/test process census. The command's declared mode and the
launcher's mode report decide what is running; a basename is insufficient.
The program-read lane does not change held test-launcher files or operate
another lane's session.

## Acceptance

A census distinguishes `bin/test --fast --paths ...` from a cold gate,
reports the selected mode with its holder identity, and does not stop an
authorized fast invocation as a full gate. Verify against the existing
path-selection launcher, preserving the shared slot bound.
