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

The two other templates make linked sets the same way:
- `seon.template/with-merge` follows each declared work task (the task files, `my.root/load-tasks!`) with its merge task;
- `seon.template/attach-tx` attaches design → implement → test → merge to a failed attempt, in the one transaction that cancels the failed chain.

What is not settled: proofs accumulate along `:needs`, so a design step's own check would block implement (DESIGN-tasks §11 #14), and the proofs design writes reach main only as its plan's text (§11 #11, #16).

## The agent knows its task

An agent holds the task and attempt root forked it for from its first form: `my.task/current` reads the task through `seon.turn/current-task`. Both ride `seon.root/spawn!`'s opts into the writer's `:spawn` request and the fork server's `:fork` message, and `seon.agent/init!` keeps them in the agent's state. A turn loop, a context read and an attached outside agent read it the same way.

## Task content reaches main at the attempt's end

When an attempt ends, every task whose content its branch changed since the branch's base is written onto main, and one that main also changed, differently, is a conflict for root (owner, 2026-10-22, D4: a task edited on two live branches should never happen; if it does, root figures it out).

`seon.task/carry-tx` compares the merge base, the branch and main over the carried task attributes (`seon.merge/carried-platform-attrs`: the plan and instructions `my.task/update!` edits). A member the branch changed and main did not overwrites main's; one equal on both sides writes nothing; one main changed too, to another value, is never resolved: it goes on the attempt as `:seon.attempt/task-conflicts` and `seon.problems/report` shows it in its `:task-conflict` class. Root's judge (`seon.reconcile/judge-tx`) calls it with the base the writer's branch read answers, so it is the one carry: a design step's plan reaches main before implement starts, on the same branch. Merging merges the program only (`:data-attrs #{}`), so main's task edits never put a merge behind. Not this: carrying only the attempt's own plan, or merging task data at merge, where every judged plan on main would make each merge `:seon.merge/behind`.

## A failed task is held for root

A task is marked failed by writing `:seon.task/failure` (its reason, a counterexample, the attempt) on it; root holds it, shows it among the problems, and never retries it on its own (owner, 2026-10-22: "We need a way to mark the task as failed and root should be notified … where we are just updating the task data and root is monitoring for these situations").

An agent calls `my.task/fail!`: the failure goes on its task on its branch, and a turn loop ends after that turn (outcome `:failed`). Agents commit their own branches, so root sees branch data at the attempt's end, the event it already reacts to: `seon.reconcile/judge-tx` reads the branch and carries a failure of *this* attempt onto main with the task held (an earlier attempt's failure on a reused branch is not carried). The judged rule then closes the attempt, with no refinement. `seon.reconcile/task-state` reads held-with-a-failure as `:failed`, and `seon.problems/report` lists it in its `:failed` class, open while the task is held. Root answers by releasing it (`my.root/run!`); the failure stays as a record. Root or a check marks a task failed the same way, by writing the fact on main with the task held. Not this: a send-back carried as a string in the attempt's end, which the judge refined automatically.

## The done rule

`seon.reconcile/done-refusals` says why an attempt's measured facts do not make its task done; empty means done. A proof with no result never ran: it refuses as `:proof-unknown`, not `:proof-red` (v1's reading), so the refusal names what was not measured.

Related: [merging](merging.md) for how a merge task's branch reaches main, [references](references.md) for how a plan's citations become the task's references, [parity](parity.md) for v1's chain and templates.
