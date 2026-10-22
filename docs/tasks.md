# Tasks and templates

All work is task entities on main, linked by `:needs`; a template turns what someone asked into the linked set that works it, and root writes the whole set in one transaction before any agent of it forks.

## A request is one transaction

A request becomes its whole linked task set in one transaction on main, released, and the reconciler forks the set's first agent only from a main that holds all of it (owner, 2026-10-22: "it's just one transaction with several maps … only when everything is set should it fork and run it so they have the same data until that point").

`seon.template/request` makes the set from one declaration (the request's ns, name, plan, and any proofs or model):
- the request itself as the **design** step (kind `:design`, its plan the request's words);
- `<name>-implement` (kind `:implement`), needing design;
- `<name>-merge` (kind `:merge`), needing implement: the template adds it, explicit in the graph from the start (DESIGN-tasks D3(a)).

All three check out design's branch, so implement holds design's schemas, stubs and red proofs, and merge holds both. The request's proofs are design's and reach the later steps along `:needs` (`seon.task/accumulate`).

`seon.template/request-tx` is the one transaction: `seon.task/create-tx` of the set, then `seon.task/execution-tx` releasing it. `my.root/request!` writes it with one `seon.root/transact-main!`. Since the reconciler reads only committed main, no agent of the set sees part of it. Not this: create held, then `my.root/run!` (two transactions), or adding `:needs` afterwards.

`seon.template/with-merge` makes a linked set the same way: it follows each declared work task (the task files, `my.root/load-tasks!`) with its merge task.

What is not settled: proofs accumulate along `:needs`, so a design step's own check would block implement (DESIGN-tasks §11 #14), and the proofs design writes reach main only as its plan's text (§11 #11, #16).

## The agent knows its task

An agent holds the task and attempt root forked it for from its first form: `my.task/current` reads the task through `seon.turn/current-task`. Both ride `seon.root/spawn!`'s opts into the writer's `:spawn` request and the fork server's `:fork` message, and `seon.agent/init!` keeps them in the agent's state. A turn loop, a context read and an attached outside agent read it the same way.

## Task content reaches main at the attempt's end

When an attempt ends, every task whose content its branch changed since the branch's base is written onto main, and one that main also changed, differently, is a conflict for root (owner, 2026-10-22, D4: a task edited on two live branches should never happen; if it does, root figures it out).

`seon.task/carry-tx` compares the merge base, the branch and main over the carried task attributes (`seon.merge/carried-platform-attrs`: the plan and instructions `my.task/update!` edits). A member the branch changed and main did not overwrites main's; one equal on both sides writes nothing; one main changed too, to another value, is never resolved: it goes on the attempt as `:seon.attempt/task-conflicts` and `seon.problems/report` shows it in its `:task-conflict` class. Root's judge (`seon.reconcile/judge-tx`) calls it with the base the writer's branch read answers, so it is the one carry: a design step's plan reaches main before implement starts, on the same branch. Merging merges the program only (`:data-attrs #{}`), so main's task edits never put a merge behind. Not this: carrying only the attempt's own plan, or merging task data at merge, where every judged plan on main would make each merge `:seon.merge/behind`.

## A refused attempt is held for root

A task whose attempt was refused, or that its agent marked failed, is held for root: its failure and evidence are data on it, the problems read shows it, and nothing retries it on its own (owner, 2026-10-23: "no automatic retries, send it to root to decide if it should be retried"; 2026-10-22: "We need a way to mark the task as failed and root should be notified … where we are just updating the task data and root is monitoring for these situations").

Every failure takes the one hold. The reconciler judges an ended attempt by the done rule; a refusal (a red or unrun proof, a regression, a stray red, a contract gap, an unmerged key, `:no-design`, whatever the attempt's outcome, `:no-progress` included), a dead agent, or a merge the octopus merge refused is its `:hold` action, and `seon.reconcile/hold-tx` is its one write: `:seon.task/failure` (the reason as the reconciler states it, and the attempt), `:seon.task/evidence` (the attempt's conflicts, unmerged keys, red proofs and the reason), `:seon.task/execution :held`, and the attempt `:closed`, answered once. An agent calls `my.task/fail!` instead: the failure (with a counterexample) goes on its task on its branch and its turn loop ends; `seon.reconcile/judge-tx` carries a failure of *this* attempt onto main with the task held (an earlier attempt's failure on a reused branch is not carried), and the judged rule closes the attempt. No task is created and none cancelled.

`seon.reconcile/task-state` reads held-with-a-failure as `:failed`; `seon.problems/report` lists it in its `:failed` class with its evidence, open while held; at quiescence `seon.reconcile/stuck` names it as a root cause with the released tasks it blocks. An idle system then does nothing: a held task is not ready, and root's own commits do not end a quiet spell.

Root, or the owner through root, decides: release it with `my.root/run!`, edit the task first, or cancel it. A released task's next attempt runs on the same branch (the writer resumes a kept branch), and `seon.task/onto-tx` puts the task onto it as main holds it, its failure and evidence included, so the agent starts from the evidence (its context's `:evidence`). The failure stays as a record after a release. Not this: a refinement chain (design → implement → test → merge on a fresh branch, the failed chain cancelled) attached while a budget lasted, then a "budget spent" fact; or a send-back carried as a string in the attempt's end.

## The done rule

`seon.reconcile/done-refusals` says why an attempt's measured facts do not make its task done; empty means done. A proof with no result never ran: it refuses as `:proof-unknown`, not `:proof-red` (v1's reading), so the refusal names what was not measured.

A contract gap is a function the branch's own changes define without a contract: `seon.merge/uncontracted` over the three-way merge's `:ours`, one answer for the writer's merge gate and for root's judge (which reads it from the writer's `:branch-diff`), so the done rule never refuses what the merge would take, or the reverse. The agent's own done check (`seon.turn`) asks the same function of its branch's changes since its merge base.

Related: [merging](merging.md) for how a merge task's branch reaches main, [references](references.md) for how a plan's citations become the task's references, [parity](parity.md) for v1's chain and templates.
