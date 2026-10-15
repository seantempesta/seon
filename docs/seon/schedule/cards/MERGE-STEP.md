---
title: "MERGE-STEP: a refused final merge keeps its merge branch and holds a merge step for root"
type: card
status: in implementation (owner rulings 2026-10-15); amends TASK-CHAIN §1, §2.2, §2.6, §2.7, §4
created: 2026-10-15
---

# MERGE-STEP

What exists: root's pass merges a chain whose last task is done (`seon.cluster.agent/merge-chains!` →
`seon.merge/merge!` → `seon.cluster.source/prepare-merge!` → `seon.cluster/candidate-gate!` →
`seon.cluster.source/accept-merge!`). The gate builds a merge branch `:merge-<id>` from main's latest head plus the
chain's merge list and runs the tests there. A refusal bails the chain to root (`bail-chain!`), and the merge branch is
deleted (`seon.merge/merge!` retires it whether or not acceptance accepted). Evidence:
`docs/research/agent-platform/2026-10-15/recovery-step-dig.md`.

The change, from the data outward:

1. **The merge branch is kept on refusal.** One rule retires a merge branch: when its chain's merge publishes
   (`finish-chains!`) or root abandons the chain. Every refusal of the three kinds below leaves the merge branch,
   whichever stage refused; nothing else deletes it.
2. **The three refusals are the gate's**, each before main moves: a conflict (`:seon.source/conflict-error`), a red
   test on the merge branch (`:seon.source/test-evidence-error`, the red tests named), and a file write-back would
   produce that does not analyze cleanly (`:seon.source/write-back-error` `:not-analyzable`, moved from the
   `:root`-only pre-check into the gate). Any other refusal (a configuration refusal such as `:root-refused`) bails as
   today.
3. **Root holds a merge step.** On such a refusal, root writes ONE transaction on the chain branch through the one task
   writer: a new task built from `seon.step.merge/template` (`seon.task/template` merged with the last task's flowing
   members, named by position as `chain!` names later tasks), carrying `:seon.task/branch` (the kept merge branch),
   `:seon.task/refusal` (the refusal's type) and `:seon.task/refused-tests` (its red tests), `:execution :held`, plus
   `[:db/add <last task> :seon.task/next <new task>]`. Its terminal attempt is closed as a bail (§2.7) with the
   refusal as its reason. Nothing starts it: root runs it with `my.root/run!`.
4. **A task selects its branch by data.** A task may carry `:seon.task/branch`; root's pass uses
   `(or (:seon.task/branch task) chain-branch)` for its attempt's branch, and the attempt's world, block and tests
   follow from that row. The merge step's agent therefore checks out the merge branch, its compartment: it writes
   nothing else. Its task and attempt are placed on that branch when the attempt starts (as `seon.task/assign!` places
   a task on an agent's own branch), and root reads that attempt's end there: one reader,
   `seon.task/branch-attempts`, gives every attempt as the branch its row names holds it. Boot keeps a branch a
   chain's task names (`seon.cluster.registry/cluster-claims`).
5. **Its proofs are the merge gate's judgment.** The tests the gate found red (`:seon.task/refused-tests`) are the
   step's `:seon.task/proofs`, so the attempt's start runs them on the merge branch and the done rule
   (`seon.cluster.source/done-refusals`) needs them green; a refusal with no red test (a conflict, a file that
   does not analyze, a clj-kondo error, an uncovered function) takes the chain's last task's proofs. The done rule
   judges what the merge branch alone decides of its merge with the gate's own functions (TASK-CHAIN §2.4,
   `seon.cluster.source/publication`): a clj-kondo error in a file the merge writes and an uncovered function are
   its refusals, so the step's agent gets a turn with the refusal as data instead of ending done at its start and
   re-merging into the same refusal; root's merge of the branch judges the rest (a conflict, a file that does not
   analyze) (6). A
   separate gate deftest (`seon.step.merge-test/gate`, 7e84ba763) is deleted: it only restated the refused tests' state,
   nothing at the start ran those tests, and on S5's merge branch it errored (2026-10-15, attempt 32da963e358d).
   Each attempt's start places the task as the chain branch holds it now (`seon.task/task-placed!`), so root's change
   to a held step reaches its merge branch. An attempt whose start pass could not run its proofs (S5 attempt 3: a
   branch test whose namespace no longer loads, run `c85f90457863` `:request-threw`) ends `:stalled` and bails to root,
   never waiting with no turn and no end (`seon.turn/stalled?`).
6. **The merge branch is a fork of main with its fork entity** (`seon.cluster/candidate-gate!` forks it with
   `seon.cluster.registry/fork!`): its own delta (`seon.program/own-changed-identities`) walks from where it forked, so
   root's forward merge into it never hides the chain's changes written before (S5, 2026-10-15: a merge branch made with
   `branch!` merged with an empty delta and was recorded `:merged` with none of the chain's program). A merge step
   attempt closed by root is closed on its merge branch too, so its merge brings no open attempt to main.
7. **Root merges the repaired branch.** A merge step that ends done is the chain's last task: root's pass merges its
   attempt's branch (the merge branch) like any finished chain; the gate runs again on main's latest head, so a moved
   main is judged again, and a new refusal holds a new merge step the same way.
8. **A merge step starts only by root's run.** It is never advanced into: a rerun predecessor's done merges again
   from main's latest head (`seon.cluster.agent/chain-ends` skips a task whose next is a merge step,
   `seon.cluster.agent/chain-finishes` merges it), the held step stays held and is moot once that merge publishes; a new
   refusal appends a new merge step after it, the newest the one to run (S5 run 14, 2026-10-15: an advance started
   the held step).
9. **Root's context shows held merge steps.** `my.root/instructions` carries the law; `my.root/merge-steps` lists each held merge step with
   its `my.root/run!` form.

## Phases

| Phase | Change | Red proofs | Status |
|---|---|---|---|
| A | This card; TASK-CHAIN amended; `resources/seon/tasks/seon.step.merge.edn`; `:seon.task/branch` on the task schema | `seon.cluster.db-flow-test/a-refused-final-merge-keeps-its-merge-branch-and-holds-a-merge-step` (rows i and iv); `seon.cluster.db-flow-test/a-task-naming-a-branch-starts-its-attempt-there` (row ii) | done c09651be8 |
| B | Keep on refusal, one retirement rule (`seon.merge/merge!`, `seon.cluster.source/prepare-merge!`); the analysis and lint checks and a conflict are the gate's refusals, each answered with the merge branch the gate built (a conflicted declaration forced on top as the chain holds it); a gate run error retires its branch | the phase A deftest's merge-branch assertions; `seon.cluster.merge-test`, `seon.cluster.write-back-test` | done (merge side c62266fd4; source side with this status) |
| C | `seon.task/next-tx` (one transaction, the template and the `next` ref); `merge-chains!` appends on a refusal that kept a merge branch | the phase A deftest's merge-step assertions | done c62266fd4 |
| D | A task's own branch: `chain-read` reads it, `launch-actions` uses it, the task, agent and row placed there by identity; root reads an attempt where its row names (`seon.task/branch-attempts`, called by `chain-read`, `chain-finishes`, `seon.task/chain-attempts`; `seon.merge` reads the row there too); boot keeps a branch a chain's task names (`seon.cluster.registry/cluster-claims`); the step's proofs are the refused tests (the gate deftest `seon.step.merge-test/gate` of 7e84ba763 deleted, MERGE-STEP 5); the refusal in the step's instructions (`## Merge refusal`, each red test and conflicted declaration a reference its block resolves) | `a-task-naming-a-branch-starts-its-attempt-there` (end read on its branch); `seon.cluster.registry-test/a-chain-fact-keeps-its-branch-and-base-through-retirement` (a task-named branch survives); `seon.cluster.db-flow-test/a-merge-step-green-at-its-start-ends-done-with-no-model-call` | done with this status |
| E | Root's pass merges a done merge step's attempt from its branch (the gate runs again on main's latest head); the chain's publication retires the merge branches its tasks named; `my.root/merge-steps` and root's law | `seon.cluster.db-flow-test/a-done-merge-step-merges-its-branch-onto-main` (row iii) | part: the done step's end is read on its merge branch, judged done there and taken to `seon.merge/merge!` with the merge branch as source; that world names no checkout, so its merge stops at the write-back root. Open: row iii's accepted merge in a world with a checkout (`seon.cluster.merge-test`'s), and the merge test's two reds already on c09651be8 (`a-chain-merges-once-and-closes-every-attempt` lines 1020, 1023) |

Conflicts and files that do not analyze are judged by the gate root runs when it merges the repaired branch: a
still-broken file or a conflict main made since refuses the merge again and holds a new merge step.