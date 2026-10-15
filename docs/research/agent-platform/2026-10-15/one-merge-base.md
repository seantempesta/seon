---
title: "One merge base: Datahike's commit graph is the only record of where a branch starts"
type: plan
status: research (phase status below; every lane updates it in its commit)
created: 2026-10-15
---

# One merge base

The owning plan. Each agent builds on this document in turn, so nobody redoes work that is already done better
elsewhere. Every call site lives in the ledger, [one-merge-base-callsites.md](one-merge-base-callsites.md), and
nothing is done until every ledger row is converted or deleted.

**Evidence it builds on:**
- [datahike-internals.md](datahike-internals.md) (`d10e70f10`): the fork's internals, measured.
- [datahike-seams-inventory.md](datahike-seams-inventory.md) (`cb161f018`): Seon's seams, from grep.
- [own-changes-notes.md](own-changes-notes.md) and `own-changes.patch` (`b064a3e8f`): the red deftest and the
  S5 numbers with the right base.
- [chain-step-cost.md](chain-step-cost.md) (`a76a9c141`).

## The problem (reproduced by root, 2026-10-15)

Transaction ids are counted per branch: 774 tx ids name different transactions on main and on
`:chain-f1f6dc8e6652` (same id, different `:db/txInstant`). Seon records where a branch starts in six places:
- `seon.cluster.registry/base-commit-id`, a base ref that `advance-base!` writes as a side commit that is never on main's lineage;
- `:seon.task/merge-base` copied onto attempt rows;
- `seon.turn/fork-commit`;
- `seon.test/fork-commit` and `change-base`;
- `seon.cluster.source/branch-base-t`;
- the adoption record.

Several of these are *t* values compared across branches. `seon.merge/merge-main!` writes a plain
transaction rather than a merge commit. Consequence: the S5 branch's own change came out as 685 identities where
the right answer is 24, and three live attempts failed, one per reader patched.

## The model (git's, taken whole)

- **A forward merge is a merge commit.** `merge-main!` writes with `:parents #{main-commit}`, as
  `accept-merge!` already does for chain→main.
- **The merge base is a function of two commit ids,** computed from the commit graph by git's walk: generation
  numbers, highest generation first. It is a fork function, `datahike.versioning/merge-base`, cached forever by
  its two ids. Nothing stores a copy.
- **A branch's own change is the diff of two database values,** base vs head, walking both index trees and
  skipping subtrees held at the same address, limited to program attributes, then the existing digest three-way.
  No *t* is ever compared across branches.

## Phases

| Phase | Owner | What | Red proof | Status |
|---|---|---|---|---|
| 0 | Sonnet | `seon.cluster.db-flow-test/every-agent-start-has-a-task` never exits and eats every check's 600 s | ends within its bound | in progress |
| R1 | Haiku | dig: prior art (git commit-graph, upstream Datahike, earlier Seon attempts and why), rulings the design must keep | n/a | in progress |
| R2 | Haiku | call-site ledger, src + test + script + resources + docs/skills, each row verified | n/a | in progress |
| C | Sonnet | REPL confirmation of the approach on real data before any implementation (section below) | numbers below | waiting on R1, R2 |
| 1 | Opus | fork: generation + small parents record per commit; `datahike.versioning/merge-base` | fork tests: fork, after merge from main, criss-cross | waiting on C |
| 2 | Opus | `merge-main!` writes a merge commit; one Seon reader of the merge base; every ledger reader converts | `seon.merge-forward-test/after-a-forward-merge-the-branch-changed-only-its-own-declarations`; S5: 24, `done-refusals` [] | waiting |
| 3 | Opus | own change by address-skipping index diff; history walks (`commit-at-t`, `basis-revisions`, `commit-facts`) replaced | `seon.profile/measure` on `merge-main!` and the step's change set, parent vs change | waiting |
| 4 | Sonnet | delete what is left: base refs, `advance-base!`, the 125 `:base/*` branches, `base-t`, fork-commits, `change-base`, stored merge-base copies | ledger all converted/deleted; HEAD boots | waiting |

Then one restart with the fork bump in the batch, and one live S5 attempt. Expected: done at start, 0 model calls,
and a merge of its 9 tested entities.

## R1: prior art and constraints

(Haiku dig writes here.)

## C: REPL confirmation

(Sonnet writes here: each claim of the model above, its form, its measured value and its verdict.)

## Implementation notes

(Opus writes here per phase: the decisions made, anything that differed from the plan and why.)
