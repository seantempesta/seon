---
type: issue
status: superseded
severity: friction
tags: [issue, agent, flow]
---

# Shared-HEAD amend can absorb a concurrent commit

## Problem

Multiple agents commit into one shared checkout. A path-limited
`git commit --amend` still rewrites whichever commit is at `HEAD` when the
command runs, which may be a different agent's newly landed commit.

## Evidence

While the source-cleanup graduation matrix lane reconciled a concurrent cap
ruling, it first committed its assigned report as `e61e90ad`. Another lane
then landed the cap-ledger update as `5fa4fc70`. The matrix lane's subsequent
path-limited amend rewrote that new `HEAD` as `e27ada04`, combining the cap
changes with five matrix lines. No content was lost and the original commit
remains in the reflog, but the expected branch-visible hash disappeared.

## Owner

The shared-checkout Git protocol. Ordinary agents must use a new path-limited
commit for follow-up changes and must never amend, rebase, reset, or otherwise
rewrite shared `HEAD` without an explicit coordinated source freeze.

## Acceptance

- Shared-tree instructions explicitly forbid `git commit --amend` outside a
  coordinated history-rewrite freeze.
- Agent commit guidance uses a new path-limited commit for every follow-up.
- A concurrency regression or harness check proves a newly advanced `HEAD`
  cannot be rewritten by another lane's follow-up operation.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
