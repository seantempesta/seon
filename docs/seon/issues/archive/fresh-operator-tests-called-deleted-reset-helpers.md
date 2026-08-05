---
type: issue
status: resolved
severity: friction
tags: [issue, operator, testing, deletion]
---

# Fresh operator tests called deleted reset helpers

## Problem

The fresh-operator namespace retained three tests for
`delete-cluster-root-no-follow!`, `assert-store-flock-free!`, and
`with-store-flock!` after those private helpers were deleted. The assertions
could only fail at `ns-resolve`, so they tested no surviving mechanism.

The same namespace still expected a managed-root lifecycle lock and only three
calls through `start-child-jvm!`, both predating the installation-wide operator
authority and the managed-root cleanup child. Its restart proof also compared
an unrelated background startup-message count before and after restart; the
message could commit between the two queries even though the explicitly
transacted marker and agent facts were stable.

## Evidence

The complete namespace gate failed the stale private calls with a nil Var at
`var-get`. The launch-owner source census found four calls: offline roster,
cluster launch, source initialization, and managed-root cleanup. The public
root test failed because it wrote a process claim without first publishing the
managed root's external claim, then looked for the deleted per-root lock.

## Owner

The recurring fresh-operator namespace proves command composition and live
operator transitions. `seon.operator-test` proves the surviving cleanup owner,
including no-follow deletion.

## Acceptance

- No test resolves a deleted private reset helper.
- No-follow cleanup remains covered at the public `seon.operator/cleanup-root!`
  boundary.
- The public root seam publishes the root claim and asserts the one
  installation lifecycle lock.
- The child-process census names all four surviving call sites.
- The restart proof compares only the explicit marker and stable agent facts,
  not asynchronous background traffic.

## Resolution

Resolved by the commit containing this note. The three tests for deleted
private helpers are deleted; `cleanup-is-complete-truthful-and-never-follows-a-symlink`
in `test/seon/operator_test.clj` remains the recurring no-follow proof. The
root and child-owner assertions now describe the surviving authorities.
The restart proof no longer treats eventual startup messages as branch
contents authored by that test.
