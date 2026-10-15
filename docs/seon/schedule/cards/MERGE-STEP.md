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
   a task on an agent's own branch), and root reads that attempt's end there.
5. **Its proof is the merge gate.** `seon.step.merge-test/gate` asserts `seon.step.merge/gate` refuses nothing for the
   supplied task over the supplied branch: every refused test green, every changed file analyzes cleanly, no
   conflicted declaration left at the value the refusal found. The done rule (`seon.cluster.source/done-refusals`) is
   unchanged: the proof is the step's clause.
6. **Root merges the repaired branch.** A merge step that ends done is the chain's last task: root's pass merges its
   attempt's branch (the merge branch) like any finished chain; the gate runs again on main's latest head, so a moved
   main is judged again, and a new refusal holds a new merge step the same way.
7. **Root's context shows held merge steps.** `my.root/instructions` carries the law; a held merge step is listed with
   its `my.root/run!` form.

## Phases

| Phase | Change | Red proofs | Status |
|---|---|---|---|
| A | This card; TASK-CHAIN amended; `resources/seon/tasks/seon.step.merge.edn`; `:seon.task/branch` on the task schema | `seon.cluster.db-flow-test/a-refused-final-merge-keeps-its-merge-branch-and-holds-a-merge-step` (rows i and iv); `seon.cluster.db-flow-test/a-task-naming-a-branch-starts-its-attempt-there` (row ii) | done c09651be8 (both proofs red for the right reason) |
| B | Keep on refusal, one retirement rule (`seon.merge/merge!`, `seon.cluster.source/prepare-merge!`); the analysis check inside the gate | the phase A deftest's merge-branch assertions | part: `seon.merge/merge!` keeps a refused merge branch and names it on the refusal (`:seon.source/candidate`, red tests as `:seon.source/red`); the hand merge (`my.root/merge!`) and the per-assignment path retire it. Open in `seon.cluster.source` (held by another lane today): the analysis check inside the gate, a conflict building its merge branch, retirement on a gate run error |
| C | The append in `seon.task` (one transaction, the template and the `next` ref); `merge-chains!` writes it on a gate refusal | the phase A deftest's merge-step assertions | done (`seon.task/next-tx`; `merge-chains!` appends on a refusal that kept a merge branch; proof green) |
| D | `:seon.task/branch` in root's launch (`seon.cluster.agent/chain-read`) with the task placed on that branch; `seon.step.merge/gate` and its deftest; the refusal in the block | `a-task-naming-a-branch-starts-its-attempt-there`; `seon.step.merge-test/gate` | part: launch done (`chain-read` reads `:seon.task/branch`; `launch-actions` uses it; the task, agent and row placed on that branch by identity; proof green). Open: root reads the step's end on its branch (below); `seon.step.merge/gate` and its deftest; the refusal in the block |
| E | Root's merge of a done merge step's branch; root's instructions and held merge steps | row iii: a green merge step's branch merges on root's pass | — |

## Open (2026-10-15)

- **Root reads the merge step's end where its agent writes it.** The attempt row is on the chain branch (root's
  readers walk it) and placed on the merge branch (the agent's world); the agent writes its end on the merge branch.
  `seon.cluster.agent/chain-read`, `chain-finishes` and `seon.task/chain-attempts` read ends on the chain head only.
  Options for root: (1) those three read a row's end on the branch its row names (one shared reader, three callers);
  (2) on the refusal the chain moves to its merge branch (`:seon.chain/branch` re-pointed; the merge branch already
  holds the chain's tasks and attempts from the merge's facts), so no reader changes and `:seon.task/branch` is not
  needed by the step; (3) root copies the end onto the chain branch on the merge branch's wake.
- **A kept merge branch survives a restart.** `seon.cluster.registry/cluster-claims` claims chain branches only; a
  merge branch named by a chain task's `:seon.task/branch` is retired by boot's `retire-unclaimed!` until it claims
  those too.
