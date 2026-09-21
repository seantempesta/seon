---
type: issue
status: resolved
severity: friction
created: 2026-09-19
tags: [testing, fixture, worktree]
---

# Preflight source fixture rejects an absolute Git common directory

## Problem

The reset preflight fixture cannot construct its source checkout when run
from a Git worktree. Git returns an absolute common-directory path, which
`preflight-source-checkout!` passes as the child argument of `io/file`.

## Evidence

At HEAD `0aee224c5` plus the platform-marker correction, the isolated fast
run reported `java.lang.IllegalArgumentException: /Users/sean/src/seon/.git
is not a relative path` in three tests of
`seon.dev.fresh-operator-reset-test`: the two dependency-cache/preflight-bound
tests and `source-syntax-refuses-before-lock-or-destruction`.
`test/seon/dev/fresh_operator_reset_test.clj:124` obtains `--git-common-dir`;
`:125` constructs `(io/file project <output>)` without accounting for an
absolute output. The committed fixture precedes the platform marker change;
that change modifies no fixture bodies. Raw lane evidence was captured in
`tmp/platform-drill-isolated.log` (2026-09-19, errors at 18:07Z).

## Owner

`seon.dev.fresh-operator-reset-test/preflight-source-checkout!`.
This is separate from destroyer selection; do not weaken the tier checker.

## Acceptance

The existing three preflight tests construct their source fixtures from both
an ordinary checkout and an isolated Git worktree, using Git's actual common
directory in either absolute or relative spelling. Their preflight assertions
run through the normal armed fast runner.

## Resolution — 2026-09-19

Verified `.git` in the main checkout and `/Users/sean/src/seon/.git` in a
throwaway worktree. The fixture now resolves Git's output with Path.resolve.
One regression uses real Git's relative and absolute path formats and proves
both fixtures have the same committed source tree and populated cache.
All three existing preflight tests passed their assertions in the armed
HEAD-plus-owned-path fast run. The complete namespace ran 12 tests / 146
assertions, with one failure and one error in the separate isolated boot test
(publication child process-exit deadline, 180000 ms). Cold platform proof is
owed by the orchestrator. See
[the landing evidence](../../prds/steward-platform/research/preflight-fixture-path-2026-09-19.md).
