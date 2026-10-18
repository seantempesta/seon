---
type: issue
status: fixed-pending-default-reload
severity: friction
created: 2026-10-18
tags: [issue, cost, eval, branch]
---

# An agent's request cost grows with every namespace its branch authored

## Problem

Every eval request of an agent costs work proportional to the program its branch has authored since the loaded
commit, not to the forms it evaluates. An unchanged `nil` request should cost one form whatever the branch holds
(`seon.cost-estimate-test/estimates`, row `:unchanged-request`); it grows linearly with each earlier
namespace the branch defined.

## Evidence (2026-10-18, lane cost-determinism, HEAD b3ccec4cd)

`bin/seon check` JVMs ran the program-change sequence of `seon.cost-estimate-test` ten times in one test var
(one agent, one branch), each pass defining two fresh namespaces (`cost.estimate.defs-<id>` with two defs, one
unmapped; `cost.estimate.redefine-<id>` with a contracted function and its test) and merging a third
(`tq.cost-merge-<id>`). Each pass adds exactly, to the next pass's request on the same branch:

| row | per earlier pass: calls | reads | MB |
|---|---|---|---|
| `:unchanged-request` (`nil`) | +3,950 | +83 | +5.7 |
| `:new-def` | +11,700 | +341 | +40 |
| `:redefine-one-form` | +22,400 | +107 | +69 |

The growth is exact (the same increment every pass, spread under 0.2% in calls), so it is a derivation over the
branch's authored rows, not noise; the step thread carries it (`:seon.cost/step`, e.g. the unchanged request's
step 7.8 → 37.7 MB over nine passes) and, for a redefinition, the writer (51 → 170 MB). With the same names reused
each pass (results reused by content), the growth is +623 calls and +7 reads per pass. The probe that
repeats the sequence is reproduced by calling `seon.cost-estimate-test/measured` ten times in one test var.

## Cause (2026-10-18, lane branch-growth)

Three producers on the turn thread did work proportional to the branch, measured per function in a call sink on
default (agent with N passes, `nil` request):

- `seon.cluster.source/merge-base` walked the commit graph from the branch head down to the merge base for every new
  head: its cache is keyed by the commit pair, and every request writes a commit, so each request missed and read
  one graph record per commit since the fork (about four walks a request; 1,068 calls for 83 commits). Fixed by a
  fold: a single-parent commit with a higher generation than the other side is no ancestor of it, so the pair's
  merge base is its parent's, read from the cache (19 calls).
- `seon.test/observable-changes` re-derived the behavior digest of every changed declaration, twice a request
  (`seon.test/branch-delta` and `seon.cluster.source/merge-members`): about 50 calls and 2 reads per changed
  identity. Held by the content revisions of both values' `:seon.program/definition-digest`, the key
  `seon.program/own-changed-identities` already trusts.
- `seon.test/branch-tests` read each changed test's datoms, and the private `seon.test/current-results` pulled each
  resulted test's source: one read per test, now one read for all.

The proof is `seon.branch-growth-cost-test/an-unchanged-request-costs-the-same-after-one-and-five-authoring-passes`
(calls and reads of a `nil` request within 2% after one and five passes): HEAD 7,238 -> 10,186 calls and 71 -> 159
reads; with the fold alone 7,065 -> 9,709; with all three 6,283 -> 6,351 calls and equal reads. The residual 17 calls a pass is `seon.print/compare-values`
sorting the held delta in `seon.test/branch-delta` (armed comparator, n log n), inside the 2% bound.

## Direction

Find which stage of the request reads every authored row of the branch (candidates on the turn thread: the
overlay plan over authored rows, `seon.sci.eval/overlay-plan`, and the test step's selection over changed
definitions, `seon.test/reaching-selection`) and make it proportional to the rows the request changed.
`seon.cost-estimate-test` measures each row after exactly one warm-up pass on the same branch, so its overruns
include one pass of this growth.
