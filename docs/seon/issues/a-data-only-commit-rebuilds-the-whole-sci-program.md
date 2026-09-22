---
type: issue
status: open
severity: friction
created: 2026-09-23
tags: [issue, sci, acquisition, performance, tests]
---

# A data-only commit rebuilds the whole SCI program

## Problem

`seon.sci.eval/acquire!` (`src/seon/sci/eval.clj:2296`) treats a context as
already acquired only when the supplied database is the same committed value
(`acquired-database?`, `:2283`, compared by Datahike commit id since
lane-realities-commit-4). Any new commit — including one that only records test
results, turns or messages — misses, and `acquire!` calls `base-ctx`
(`:2249`), which re-derives and re-installs the whole program.

Measured on a scratch cluster (frozen `bfe3445f8` + commit-4 files, root
`tmp/realities-c4-root`, 2026-09-22):

| operation | ms |
|---|---:|
| `acquire!` on the cluster ctx after the head moved by data-only commits | 514.8 |
| same `acquire!` repeated on the now-acquired commit | 0.036 |
| `seon.test/run` request-level isolated acquisition at a new commit | 795.4 |
| member acquisition forked from a context already acquired at that commit | 30–50 |

Every `seon.test/run` request records results, so the next request's captured
commit is always new and pays this once (per request, never per member).
Agent turns that re-acquire at a moved head pay the same. The slow-assumptions
audit (`docs/research/agent-platform/slow-assumptions-audit-tests-2026-09-23.md`
P7c) measured `base-ctx` at 452–1,192 ms on one unchanged commit.

## Owner and acceptance

`seon.sci.eval` acquisition (lane-realities-one-lifecycle §2 row 5, "data-only
commits install zero rows"). Acceptance: acquisition of a commit whose program
rows equal the acquired commit's installs zero rows and costs O(changed program
rows), measured; a `seon.test/run` request on an unchanged program then costs
tens of milliseconds before its first member.
