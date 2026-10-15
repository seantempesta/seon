---
title: "Branch verification: fork, forward merge, main-has-moved and the conflict merge step"
type: evidence
status: verification (lane branch-verify, Opus, 2026-10-16)
created: 2026-10-16
---

# Branch verification

The owner asked on 2026-10-16 whether the branch work of 2026-10-15 met its goals: forking is cheap, work on a
branch merges reliably, the main-has-moved case is tested, and the fourth step works (a merge step gets the
conflicting merge and fixes it). Every verdict below is backed by a run or a probe made by this lane. HEAD was
`d2681a4d6` when the work started and `62bb68f13` when it ended. Default ran `8592c669d`.

## Broken first

1. **A conflict merge step overwrites main's change, and no agent sees it.** Reproduced end to end by the new
   deftest `seon.cluster.merge-scenarios-test/a-conflict-merge-step-resolves-the-conflict-and-merges-onto-main`,
   red in run `f4e3812d3ecd` (11 pass, 4 fail, 9.3 s). The steps:
   - The chain changes `shared` from 1 to 2 in a scripted model turn.
   - Main moves: a published file change sets `shared` to 3.
   - The final merge refuses with `:seon.source/conflict-error` and holds a merge step on the kept merge branch,
     and the step's instructions name `shared`. All of this is correct.
   - The defect: the merge branch already holds the chain's version, forced on top by
     `seon.cluster.source/prepare-merge!`. It forks main's head, so its merge base is main's head, and
     `seon.cluster.source/compare-branch` from it reports `shared` as the branch's own change with an empty
     conflict set. The conflict is erased when the merge branch is built.
   - `seon.cluster.source/done-refusals` does not judge conflicts. The step's proofs are the chain's own (MERGE-STEP
     5) and pass with the chain's version, so the step ends done at its start with no model turn (1 provider request
     in total, the chain's own).
   - Root's next pass merges the merge branch with no refusal (`:merged`). **Main's `shared` becomes the chain's
     `2` and main's `3` is lost.**

   MERGE-STEP 5 assumes that "root's merge of the branch judges ... a conflict". That holds only if main moves a
   second time. Filed as `seon.cluster.source/a-conflict-merge-step-overwrites-mains-change-unseen`.

2. **A conflicting second chain is recorded `:merged` with its own change dropped.** This is the cause of
   `seon.cluster.merge-test/a-chain-merges-once-and-closes-every-attempt` line 1023, which is red on HEAD.
   - A copy of that scenario, run through `bin/seon check` as a temporary deftest that was deleted before the
     commit, gave `seon.merge/merge!` answering `{:seon.task/outcome :merged :seon.merge/conflicts []}`. Main kept
     the first chain's `chain-value` (2).
   - The second chain's change (3) is not in its merge list: its proof is main's, so its seeds reach nothing on its
     branch. `prepare-merge!` lets a conflict in a change that stays never refuse (3c5f650a0).
   - Recorded on the existing task `seon.merge/a-chain-merges-once-is-red-on-head`. Unlike item 1, no main data is
     lost. The defect is that the chain is closed as merged although none of its program reached main. That is the
     same class as S5's merged-with-empty-delta.

3. **`seon.cluster.db-flow-test/a-merge-steps-merge-carries-the-chains-declarations` asserts almost nothing.** Its
   only check of the outcome is `(or (not merged?) carried?)`, which is green whenever the merge does not happen.
   In its world the step's merge refuses at write-back (`:one-file`), so it is green without ever merging.
   `a-done-merge-step-merges-its-branch-onto-main` also stops at a write-back refusal, which is MERGE-STEP phase E's
   stated "Open". The new deftest is the first that takes a merge step to `:merged` on main. It needs a git checkout
   holding the declaration's file (`seon.cluster.db-flow-test/checkout!`), and main's change must be published from
   that file:
   - a REPL `define!` on main drops `:seon.fn/file`, giving `:one-file`;
   - a hand-built entity has no span, giving "A before-state has no span".

## 1. Cheap fork: VERIFIED

JVM-mode probe on default, run twice. Each run used a disposable branch made with `seon.cluster.registry/fork!` (the
owner `my.agent/create-branch!` and `candidate-gate!` call) and deleted with `seon.cluster.registry/retire-branch!`.
Both branches are gone (`:still-there? false`).

| operation | ms | allocation |
|---|---|---|
| `registry/fork!` (branch plus fork-entity commit), main holds 741,310 datoms | 59.7 / 51.3 | 3.65 MB |
| first open of the branch (`registry/on-branch` → `db`) | 3.0 | 1.8 MB |
| `seon.cluster.source/branch-merge-base` of the new fork | 0.8 | 0.23 MB |
| `retire-branch!` | 13.9 | 0.16 MB |

- **Storage:** the fork added 12 store objects (491,444 → 491,456), which is the one fork-entity commit. Index nodes
  are shared, nothing is copied, and the cost does not grow with the 741k datoms.
- The fork's head has main's head as its only parent, and its merge base from the commit graph is main's head
  `6ad15e88`.
- The probe's 1.1 to 2.2 s total was the probe's own directory count and datom count, not the fork.

## 2. Work on a branch, merge reliably: PARTLY

Phase status of `docs/research/agent-platform/2026-10-15/one-merge-base.md`, checked against the source at HEAD:

| phase | goal | status found |
|---|---|---|
| 1 | Merge base from Datahike's commit graph (`datahike.versioning/merge-base`, a parents and generation record per commit) | **Landed.** The fork submodule is `88d05cf8`, a descendant of `593a8276`. Fork tests `datahike.test.merge-base-test` and `datahike.test.datoms-diff-test`: 15 tests, 984 assertions, 0 failures. |
| 2 | `merge-main!` writes a merge commit; one reader of the merge base; no t compared across branches | **Landed and green** (run `d69579197de1`): `seon.merge-forward-test/a-forward-merge-is-a-merge-commit-on-mains-head` (the head has main's head as a parent, the merge base is main's head, an up-to-date branch writes nothing, a relaunch has the same base), `after-a-forward-merge-the-branch-changed-only-its-own-declarations`, `a-forward-merge-brings-the-files-main-added`, `seon.merge-test/root-merges-main-forward-into-a-chain-branch` (its "only program attributes moved" was red at phase 2 and is green now). |
| 2b | Cluster facts merge three-way from the fork point, derived from the graph (`seon.cluster.registry/fork-point`); `:seon.task/merge-base` not read | **Landed.** `:seon.task/merge-base` has no reader in `src`; the schema keeps it marked retired. Known gap, not fenced: a branch merged twice re-squashes from its fork point. |
| 3 | Own change by the address-skipping index diff; history walks replaced | **First slice only.** `datahike.api/datoms-diff` is in the fork and used only by reach attribute maps (`seon.db/datoms-diff`, `seon.test.runner`). `seon.program/own-changed-identities` still walks history from `:seon.branch/base-t`, and `seon.db/commit-at-t` still has callers. |
| 4 | Delete base refs, the `:base/*` branches, `base-t`, `fork-id`, `change-base`, stored merge-base copies | **Not started.** In `src`: `seon.cluster.registry/base-branch` (4 uses), `:seon.branch/base-t` (6), `:seon.branch/fork-id` (3), `seon.test/change-base` (3). |

Tests the phase table called "still red":
- `seon.merge-test/a-retraction-on-the-branch-retracts-on-an-unchanged-main-only` is red on HEAD.
  `seon.program/changed-identities` passes a refused read map to `seon.db/basis-revisions` (contract error:
  "This database value has no commit").
- `seon.cluster.db-flow-test/every-agent-start-has-a-task` is red on HEAD: an error plus lines 2181 and 2183.
- `my.root-test/a-jvms-first-facts-merge-follows-the-changed-facts` is red on HEAD: 171.65 ms against its 100 ms
  bound, and its allocation bound fails too (run `842b3e076ae6`).

None of these is new.

## 3. Main has moved: VERIFIED, with one gap

These tests do exactly this and are green in run `d69579197de1`:

- `seon.merge-test/root-merges-main-forward-into-a-chain-branch`. Main changes g after the fork, the chain changes
  f, and both change h. After `seon.merge/merge-main!`:
  - g is main's, and f and h stay the branch's;
  - h is listed as a conflict with its three digests;
  - the merge base is main's head, and the cluster facts' base is still the fork point;
  - the chain's final merge (`merge-tx`) lands on main's latest head, keeping main's change to task T and the
    chain's change to task U;
  - main's task never moves to the chain.
- `seon.merge-forward-test/after-a-forward-merge-the-branch-changed-only-its-own-declarations`. Main defines eight
  functions after the fork. After the forward merge the branch's own change (`seon.step/changed` and
  `own-changed-identities`) is its one function.
- `seon.cluster.db-flow-test/a-merge-step-green-at-its-start-ends-done-with-no-model-call`. Root fixes main and runs
  `my.root/merge-main!` into the kept merge branch, and the step ends done with no model turn.

The gap: a declaration both sides changed is kept as the branch's version and reported in the return value
(`:seon.program/conflict`). Nothing stores it (the reactive-start narrow check found the same, `f684c6181`). After a
forward merge the conflict is gone from every later comparison, which is item 1's mechanism.

## 4. Conflict → merge step (the fourth step): NOT VERIFIED

Before this lane, no test drove a conflict into a merge step. Every merge-step test used a red-test refusal, and
none reached `:merged` on main (see Broken first, item 3). Main records 7 merge refusals in total, read-only on
default: 4 `:seon.source/write-back-error`, 2 `:seon.source/lint-error`, 1 `:seon.source/red-on-target-error`, and
**0 `:seon.source/conflict-error`**. A conflict merge step has never run live.

The new deftest drives the whole path. What works:
- the conflict refusal;
- the held step with `:seon.task/refusal :seon.source/conflict-error`;
- the kept merge branch;
- the instructions naming the declaration;
- root's `my.root/run!`;
- the step ending done;
- root's pass merging the merge branch to `:merged`.

What fails, red:
- the merge branch no longer shows the conflict;
- the agent never takes a turn;
- main receives the chain's version over its own (Broken first, item 1).

The test is committed red as the proof of `seon.cluster.source/a-conflict-merge-step-overwrites-mains-change-unseen`.

## 5. Declared done but not

- **MERGE-STEP phase D, the card's point 5:** "root's merge of the branch judges the rest (a conflict, ...)".
  Contradicted: the merge branch erases the conflict, as item 1 shows.
- **MERGE-STEP phase E** is marked "part" and has never produced an accepted merge-step merge in any test before
  this one. `a-merge-steps-merge-carries-the-chains-declarations` (S5's regression) is vacuous: see Broken first,
  item 3.
- **one-merge-base phase 2's "S5: 24, `done-refusals` []"** stands as measured. Phases 3 and 4 are open, and the
  plan's own table says so: the history walks, base refs and `base-t` remain. "We have a git-style system" is true
  for:
  - fork cost;
  - merge base from the graph;
  - merge commits with parents;
  - the forward merge.

  It is not yet true for:
  - recording conflicts as data (git's unmerged index entries);
  - a conflict reaching a resolver;
  - a chain recorded `:merged` only when its change reached main (item 2).

## Runs

| run | candidate | tests | result |
|---|---|---|---|
| `d69579197de1` | `d2681a4d6` | 14 named vars (forward merge ×3, `seon.merge-test` ×2, `seon.cluster.merge-test` ×2, merge-step db-flow ×6, registry ×1) | 12 green. Red on HEAD: `a-chain-merges-once-and-closes-every-attempt` (lines 1020, 1023) and `a-retraction-on-the-branch-retracts-on-an-unchanged-main-only` |
| `842b3e076ae6` | `62bb68f13` | `every-agent-start-has-a-task`, `a-jvms-first-facts-merge-follows-the-changed-facts`, `seon.task-ingestion-test` | ingestion green; the other two red on HEAD |
| `f4e3812d3ecd` | HEAD + new test | `a-conflict-merge-step-resolves-the-conflict-and-merges-onto-main` | red, 11 pass / 4 fail (item 1) |
| fork | `88d05cf8` | `datahike.test.merge-base-test`, `datahike.test.datoms-diff-test` | 15 tests, 984 assertions, green |
