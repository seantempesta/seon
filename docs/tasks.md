# Tasks and templates

All work is task entities on main, linked by `:needs`; a template turns what someone asked into the linked set that works it, and root writes the whole set in one transaction before any agent of it forks.

## A request is one transaction

A request becomes its whole linked task set in one transaction on main, released, and the reconciler forks the set's first agent only from a main that holds all of it, so every agent of it reads the same data from its first form.

```seon.template/request``` makes the set from one declaration (the request's ns, name, plan, and any proofs or model):
- the request itself as the **design** step (kind `:design`, its plan the request's words);
- `<name>-implement` (kind `:implement`), needing design;
- `<name>-merge` (kind `:merge`), needing implement: the template adds it, explicit in the graph from the start.

All three check out design's branch, so implement holds design's schemas, stubs and red proofs, and merge holds both. The request's proofs are design's and reach the later steps along `:needs` (`seon.task/accumulate`).

```seon.template/request-tx``` is the one transaction: `seon.task/create-tx` of the set, then `seon.task/execution-tx` releasing it. `my.root/request!` writes it with one `seon.root/transact-main!`. Since the reconciler reads only committed main, no agent of the set sees part of it. Not this: create held, then `my.root/run!` (two transactions), or adding `:needs` afterwards.

`seon.template/with-merge` makes a linked set the same way: it follows each declared work task (the task files, `my.root/load-tasks!`) with its merge task, gives a task with no branch its default one (`seon.task/default-branch`), and points a `:needs` on a work task at that task's merge; a task file's tasks are written held (`seon.task/load-tx` over `seon.task/create-tx`: new entries created held, changed ones updated, removed ones cancelled), so nothing loaded from a file runs until root releases it with `my.root/run!`.

A merge whose branch's change reaches process tests (```seon.program/process-tests```, recorded as `:seon.run/deferred`) is gated first only when root asked (```my.root/gate!```, `:seon.task/gate?`), and otherwise merges with them unrun, an open `:unrun` problem: the reconciler's `:gate` writes the changed namespaces' files over a worktree of the merges head and runs those tests there (the `:tests` effect, ```seon.fx/run-tests```; prove's entry, `bin/prove --tests`, through ```seon.fx/test-command```), records `:seon.run/deferred-results`, and a red is refused as a red proof is. An agent's turn and root's judge run only the in-process tests, in a fork of the agent, never a process test that is none of the task's proofs ([merging](merging.md#a-merge-runs-what-the-change-reaches)).

## The agent knows its task

An agent holds the task and run root forked it for from its first form: `my.task/current` reads the task through `seon.turn/current-task`. Both ride `seon.root/spawn!`'s opts (`:branch`, `:task`, `:run`) into the writer's `:spawn` request and the fork server's `:fork` message, and `seon.agent/init!` keeps them in the agent's state (nil for an agent forked with none). A turn loop, a context read and an attached outside agent read it the same way.

## Task content reaches main at the run's end

When a run ends, every task whose content its branch changed since the branch's base is written onto main, and one that main also changed, differently, is a conflict for root: a task edited on two live branches should not happen, and when it does, root resolves it.

`seon.task/carry-tx` compares the merge base, the branch and main over the carried task attributes (`seon.merge/carried-platform-attrs`: the plan, instructions and proofs `my.task/update!` edits). Tasks main does not hold are left. A member the branch changed and main did not overwrites main's; one equal on both sides writes nothing; one main changed too, to another value, is never resolved: it goes on the run as `:seon.run/task-conflicts` and `seon.problems/report` shows it in its `:task-conflict` class. Root's judge (`seon.reconcile/judge-tx`) calls it with the base the writer's branch read answers, so it is the one carry: a design step's plan reaches main before implement starts, on the same branch. Merging merges the program only (`:data-attrs #{}`), so main's task edits never put a merge behind. Not this: carrying only the run's own plan, or merging task data at merge, where every judged plan on main would make each merge `:seon.merge/behind`.

## A refused run is held for root

A task whose run was refused, or that its agent marked failed, is held for root: its failure and evidence are data on it, the problems read shows it, and nothing retries it on its own.

Every failure takes the one hold. The reconciler judges an ended run by the done rule; a reason (a red or unrun proof, a regression, a stray red, a contract gap, an unmerged key, `:no-design`, whatever the run's outcome, `:no-progress` included), a dead agent, or a merge the octopus merge refused is its `:hold` action, and `seon.reconcile/hold-tx` is its one write: `:seon.task/failure` (the reason as the reconciler states it, and the run), `:seon.task/evidence` (the run's conflicts, unmerged keys, red proofs and the reason), `:seon.task/execution :held`, and the run `:closed`, answered once. The hold is a compare-and-swap from `:running`, so a cancel that merged first refuses it. An agent calls `my.task/fail!` instead: the failure (with a counterexample) goes on its task on its branch and its turn loop ends; `seon.reconcile/judge-tx` carries a failure of *this* run onto main with the task held (an earlier run's failure on a reused branch is not carried), and the judged rule closes the run. No task is created and none cancelled.

`seon.reconcile/task-state` reads held-with-a-failure as `:failed`; `seon.problems/report` lists it in its `:failed` class with its evidence, open while held; at quiescence `seon.reconcile/root-causes` names it as a root cause with the released tasks it blocks. An idle system then does nothing: a held task is not ready, and root's own commits do not end a quiet spell.

Root, or the owner through root, decides: release it with `my.root/run!`, edit the task first, or cancel it. A released task's next run runs on the same branch (the writer resumes a kept branch; its agent, forked holding main's program, loads the branch's own changes since its merge base on top, ```seon.agent/init!```, and the run merges main first, ```seon.turn/work!```, so a branch behind main holds what main gained, its tests included: `seon.agent-test/a-resumed-branch-holds-what-main-gained`), and `seon.task/onto-tx` puts the task onto it as main holds it, its failure and evidence included, so the agent starts from the evidence (its context's `:evidence`). The failure stays as a record after a release. Not this: a refinement chain (design → implement → test → merge on a fresh branch, the failed chain cancelled) attached while a budget lasted, then a "budget spent" fact; or a send-back carried as a string in the run's end.

## The done rule

`seon.reconcile/done-explain` says why a run's measured facts do not make its task done; empty means done. A proof with no result never ran: it refuses as `:proof-unknown`, not `:proof-red` (v1's reading), so the reason names what was not measured. A proof left out of a run (a process test that was not run) is neither red nor unknown; an open problem the branch's work made true (`seon.problems/caused`) refuses it too, and a `:design` task may leave its proofs red but not unknown, until its plan differs from the one its run began with.

A task's proofs are content its agent may improve, like its plan: a design made from a request carries none, so it registers each red proof it writes with ``(my.task/update! :seon.task/proofs conj 'ns.x-test/a-proof)`` (`seon.task/member-tx`), and a red test it has not registered is a `:stray-red` whose reason shows that form. The judge carries the proofs onto main with the plan (`seon.task/carry-tx`, a set compared as a set), so the done rule sees them red at design and green at implement (owner, 2026-10-25, DECISIONS Dogfood rulings).

A contract gap is a function the branch's own changes define without a contract: `seon.merge/uncontracted` over the three-way merge's `:ours`, one answer for the writer's merge gate and for root's judge (which reads it from the writer's `:branch-diff`), so the done rule never refuses what the merge would take, or the reverse. The agent's own done check (`seon.turn`) asks the same function of its branch's changes since its merge base.

## Where an agent's run falls short today

Working a design task through the server (Claude subagents as Seon agents, 2026-10-25) found where the run gives an agent less than the done rule asks of it. Each gap is a task.
- `(my.task/status)` answers `:seon.turn/not-observed` until a model turn has observed the task, so an outside agent never reads it: `my.task/status-answers-before-any-turn`.
- `(my.agent/prove)` answers a process test as `:deferred` with no reason, so an agent cannot tell why its own new proof did not run: `my.agent/a-deferred-test-names-the-process-it-reaches`.
- `(my.agent/grep "word")` answers names unranked and without their line, at about 350 ms and 240 MB a call, the main tool for a plan that cites nothing: `my.agent/grep-ranks-definitions-first`.

## Where a task's state lives

A task's state is derived from its runs on main (```seon.reconcile/task-state```: `:cancelled`, `:merged`, `:done`, `:running`, `:blocked`, `:failed`, `:held`, `:open`), never stated. Work a worktree session merges has no run, so its task file entry reads the same before and after the merge; how that work is recorded is open (task `seon.task/merged-work-leaves-the-task-files`). A run's own lifecycle (its outcomes, judging, root causes) is [root's](root.md#runs-and-judging); its turns are [turn](turn.md)'s.

Related: [turn](turn.md) for a run's loop, [merging](merging.md) for how a merge task's branch reaches main, [references](references.md) for how a plan's citations become the task's references, [parity](parity.md) for v1's chain and templates.
