---
type: issue
status: open
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

## Direction

Find which stage of the request reads every authored row of the branch (candidates on the turn thread: the
overlay plan over authored rows, `seon.sci.eval/overlay-plan`, and the test step's selection over changed
definitions, `seon.test/reaching-selection`) and make it proportional to the rows the request changed.
`seon.cost-estimate-test` measures each row after exactly one warm-up pass on the same branch, so its overruns
include one pass of this growth.
