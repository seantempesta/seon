# Tasks and templates

All work is task entities on main, linked by `:seon.task/needs`; a template turns what someone asked into the linked set that works it, and root writes the whole set in one transaction before any agent of it forks. Merges, checks and builds are tasks too.

## A request is one transaction

A request becomes its whole linked task set in one transaction on main, released, and the reconciler forks the set's first agent only from a main that holds all of it, so every agent of it reads the same data from its first form.

- **Where requests come from.** A sentence in a user's request box is one (```seon.system/ask!```), its words its name and plan, from the `:user` template ([working for a person](agent.md#working-for-a-person)). An agent starts another task with ```my.task/launch!```; the task keeps `:seon.task/started-by`, and every end of its work tells that agent (```seon.reconcile/tell-tx```).
- **Templates.** A template is a map of the attributes it sets (```seon.template/templates```); making a task merges one onto what was asked (```seon.template/from```).
- **The default** (`my.root/request!` with ```seon.template/request```) is one `:whole` task on its own branch: one agent reproduces the problem, writes the red proof, makes it green, merges main at each green, and its branch merges into main as a merge task's does (```seon.task/merges?```) ([decisions](decisions.md#a-request-is-one-whole-task-for-now)).
- **Experiment templates**, passed to `my.root/request!`: ```seon.template/design-build``` (a `:work` task then `<name>-merge`, one session, ```seon.task/agent-key```) and ```seon.template/stages``` (`:design`, then `<name>-implement`, then `<name>-merge`, each needing the one before). All check out design's branch; proofs reach later steps along `:needs` (```seon.task/accumulate```). ```seon.template/with-merge``` adds each work task's merge task and default branch (```seon.task/default-branch```) for these only.
- **The one transaction** is ```seon.template/request-tx```: ```seon.task/create-tx``` of the set, then ```seon.task/execution-tx``` releasing it, written by one `seon.system/transact-main!`. The reconciler reads only committed main, so no agent sees part of a set.
- **Process tests gate a merge.** A merge whose change reaches process tests (```seon.program/process-tests```, kept as `:seon.task/deferred`) is gated by the reconciler's `:gate`, which runs them over a worktree of the merged head (```seon.fx/run-tests```, ```seon.system/prove-tree!```) and keeps `:seon.task/deferred-results`; a red refuses it unless main is red there too ([merging](merging.md#a-merge-runs-what-the-change-reaches)). An agent's turn and root's judge run only in-process tests.

## Releasing and cancelling

A task is written held, released once and cancelled by root's functions; nothing autoruns.

- ```my.root/create!``` writes declarations as held tasks.
- ```my.root/run!``` is the one release: each held task becomes `:running` by compare-and-swap on main, so a racing cancel refuses it; an id not held is answered `:seon.system/not-held`.
- ```my.root/cancel!``` writes `:cancelled`; a live agent's work is killed, and tasks needing it stay blocked.
- ```my.root/restart!``` starts a held task again with a new agent on a fresh branch from main, its instructions naming the run it left; ```seon.task/restart-tx``` is the one write for every start again (this, a superseded refine and attach's `:fresh`): the branch named by the task's last ending, its end's facts retracted (`seon.reconcile-test/a-task-starts-again-with-a-fresh-agent-on-a-fresh-branch`).

The writes are ```seon.task/execution-tx``` and ```seon.task/env-tx``` (branch, model, max turns). ```seon.reconcile/desired-env``` states the environment a task's work must run in, and work in another is restarted clean; a merge task's limits are ```seon.reconcile/merge-limits```. How work stands and ends: [reconcile](reconcile.md#a-task-holds-how-its-work-ended).

## Task files

A task file is one EDN vector of declarations named by its owning namespace, and root keeps main's tasks equal to the files.

```seon.system/task-files``` reads the files under `:seon.config.root/tasks-dir` as `{owning-ns declarations-or-error}`; ```seon.task/file-declarations``` refuses text that is not exactly one vector with `:seon.task/refused`, as data. ```my.root/load-tasks!``` reconciles them into main through ```seon.task/load-tx```: new entries created held, changed ones updated (```seon.task/entity-tx``` makes the entity state exactly its declaration), removed ones cancelled, unchanged ones untouched. A good read writes root's `:seon.system/tasks`; a refused one answers its error. Nothing loaded runs until `my.root/run!`. A seed loads the files, then a person's re-index or a landing ([source](source.md#indexing-a-tree)). A file's declaration is one `:whole` task that merges itself.

## A task names examples

`:seon.task/examples` is a set of refs to agents keyed `example/<id>` (`[:seon.agent/key "example/<id>"]`), finished sessions of other tasks that the agent reads before its own work.

An example is an ended agent holding a copy of the session it was made from: ```seon.ctx.session/session-tx``` rewrites each id and call id to a digest of `example/<id>` and the original, so no example collides with a live agent, and ```my.root/load-example!``` transacts it onto main once. It is a copy because a session's data lives on its branch and its ids would collide. Refusals are `:seon.system/example-refused` and `:seon.ctx/session-refused`, as data. The task's pattern joins the examples, so ctx holds them: folded for the person, as messages for the model (```seon.ctx.session/examples-of```, ```seon.render.ui/example```, [render](render.md#the-models-context-a-cached-prefix-then-the-turn)). An example has no branch, so its page is `/agent/main/example-<id>` (```seon.render.proc/location```). A task naming none reads what it always did (```seon.ctx.wire-test/a-task-with-no-examples-reads-what-it-read-before```).

## A refine curates its own task

A refine is an ordinary task: every end writes one (```seon.task/refine-tx```), done, red, held, dead or out of turns, `:seon.task/after` the task it refines, so it starts once that task has ended, on a branch of its own with a new agent (`seon.reconcile-test/every-end-gets-a-refine-after-it-and-its-review`). Its plan cites the task and this section (what makes it a refine), and the one call that reads the run folded, `my.task/run` (```seon.learn/run```: each turn with what it read, the vars its final definitions used, the waste; `seon.learn-test/a-run-folds-what-each-turn-read`); its proof is the check that task files and pages still load, since it changes no code, and it is done by one write, the task it refines naming a function its plan did not cite before (the done rule's `:no-change-site`, ```seon.task/names-new-site?```: its proof is green before it starts, so this is how it knows done; `seon.reconcile-test/a-refine-is-done-when-its-task-names-a-function-it-did-not`), or by its typed end when the run lacked nothing a plan could name: `my.task/found-nothing!` with why, carried to main for its review, never a site invented to pass (`seon.reconcile-test/a-refine-that-found-nothing-ends-done-not-capped`), and as every task once its branch changed something (the done rule's `:no-change`, which every task meets: a proof green before a run did anything proves nothing it did; the agent's turn check and root's judging measure it by one rule, ```seon.merge/changed?```, from the merge base the run holds from turn 0, so a refine never ends at turn 0: `seon.agent-test/a-task-green-before-its-work-does-not-end-at-turn-0`). One refine per task; a refine and a merge step get none, and so does a run that took no turn, which left nothing to read (`seon.reconcile-test/a-refine-is-done-by-its-own-change-and-a-run-that-took-no-turn-gets-none`).

It reads the run that worked the task on that task's branch (``(my.db/at {:branch b})``), then changes only:

- **Its plan:** first what blocked the run, then what it learned and did not use, with the forms that mattered: one `swap!` at the cited task's path ([an agent edits a task its pull holds](#an-agent-edits-a-task-its-pull-holds)).
- **What it cites:** name the section or function the run had to find; remove names it never used.
- **The wiki sections it pulled,** where one was wrong or lacking ([changing a doc](agent.md#changing-a-doc)).
- **The run as an example:** ```seon.ctx.session/session-tx``` of the run's agent, less the turns that taught nothing, written with ``my.db/transact!`` and named on the task's `:seon.task/examples`.

A refine nobody acted on (cancelled, or held at an outcome that ended its run without finishing it: the outcome schema's ended less finished) is superseded by the task's next end, declared again on a fresh branch named by that ending's t, its end's facts retracted (```seon.task/restart-tx```, ```seon.task/end-attrs```: a judging's, a hold's failure and evidence, its outcome, reasons and agent), its old branch kept (```seon.task/refine-tx```; `seon.reconcile-test/a-refine-nobody-acted-on-is-superseded-by-the-next-ends`), so one stale refine never stops the task's refines. It changes no code, and ends once the task's plan names its change site, or with `my.task/found-nothing!` saying why. Its review is its merge step, a `:merge` task needing it, written held: a session attaches, reads the change and ends it with `seon_done`, which merges it or sends it back.

## An agent edits a task its pull holds

A task is edited where the pull holds it, whoever's task it is: an edit at a path writes the entity ([the pattern](ctx.md#the-pattern)). Your own task is at `[:seon.agent/task]`, a task it needs at `[:seon.agent/task :seon.task/needs e]`, and any task you cite at `[:seon.cite/_of e :seon.cite/target]`.

- **Put it in your pull:** `(my.agent/cite! "its.ns/its-name")`.
- **Edit its plan:** `swap!` ctx with `assoc-in` at `[:seon.cite/_of e :seon.cite/target :seon.task/plan]`. `e` is the cite's key in the join's map (an entity id); any other key is read as a new member and refused with "a new member names its identity: :seon.cite/id", meaning the key is wrong.
- **Add a cite:** name the section or function in its plan.
- **Add an example**, or anything the pattern does not hold: ``(my.db/transact! [{:seon.task/id "its.ns/its-name" :seon.task/examples [[:seon.agent/key k]]}])``.

The edit is on your branch and reaches main when your task merges, so a task that prepares another is one the other needs. Two edits of one plan conflict at the merge.

## The agent knows its task

An agent holds the task root started it for from its first form: `my.task/current` reads it through `seon.turn/current-task`. `seon.system/spawn!`'s opts (`:branch`, `:task`) ride into the fork, and `seon.agent/init!` keeps them in the agent's state.

## Task content reaches main at the run's end

When an agent ends, every task whose content its branch changed since its merge base is written onto main, and one that main also changed, differently, is a conflict for root.

A task's content (plan, instructions, proofs, a stall task's cause) is a key of Seon's one three-way merge (a `:task` row of `seon.merge`'s stated keys), merged like a var: a change on one side alone is taken, equal values write nothing, and a change on both sides is an unmerged entry, settled with ``(my.branch/resolve! [:task "ns/name"] :theirs)``. A branch's content never makes a task main doesn't hold; its declaration does. Root's judge (`seon.reconcile/judge-tx`) merges the branch's task keys at its run's end, so a design's plan reaches main before implement starts, and a both-changed member is among the task's `:seon.task/conflicts`.

## A refused run is held for root

A task whose end was refused, or that its agent marked failed, is held for root: its failure and evidence are data on it, the problems read shows it, and nothing retries it on its own.

- **The one hold.** Any refusal by the done rule, a dead agent, or a refused merge is the reconciler's `:hold`, written by `seon.reconcile/hold-tx`: `:seon.task/failure` (reason, agent, turn), `:seon.task/evidence` (conflicts, unmerged keys, red proofs, reason), `:seon.task/execution :held`, outcome `:closed`. It is a compare-and-swap from `:running`, so a cancel that won refuses it.
- **An agent gives up** with `my.task/fail!`: the failure goes on its task on its branch and its loop ends; `seon.reconcile/judge-tx` carries this end's failure onto main, held.
- **Reading it.** `seon.reconcile/task-state` reads held-with-a-failure as `:failed`; `seon.problems/report` lists it as `:failed`; at quiescence `seon.reconcile/root-causes` names it with the released tasks it blocks ([root](root.md#root-causes-at-quiescence)).
- **Root decides:** release with `my.root/run!`, edit first, or cancel. A released task resumes on the same branch and agent: the agent loads the branch's changes on main's program (```seon.agent/init!```) and merges main first (```seon.turn/work!```), and `seon.task/onto-tx` puts the task as main holds it, failure and evidence included, onto the branch. The failure stays after release.

## The done rule

`seon.reconcile/done-explain` says why an end's measured facts do not make its task done; empty means done.

- An agent working for a person ([working for a person](agent.md#working-for-a-person)) is `:open`: nothing is wrong, and it parks between messages.
- A proof with no result refuses as `:proof-unknown`, a red one as `:proof-red`; a process test left out of the judging is neither.
- An open problem the branch caused (`seon.problems/caused`) refuses.
- A `:design` task may leave proofs red but not unknown, until its plan differs from the one it began with.
- Every task is refused `:no-proofs` until it has one; the reason shows the form that registers one: ``(my.task/update! :seon.task/proofs conj 'ns.x-test/a-proof)`` (`seon.task/member-tx`). A red test not registered is a `:stray-red`. Proofs merge onto main with the plan, as task content (`seon.merge`'s `:task` key).
- A red counts only when the branch made it: a non-proof test red on main (`seon.proofs/main-red`) is never a `:stray-red`; it stays main's. A proof of the task always counts.
- A contract gap is a function the branch defines without a contract: `seon.merge/uncontracted` over the merge's `:ours`, the same answer for the writer's merge gate, root's judge and the agent's own check (`seon.turn`).

## A task's facts and how they are read

Tasks, agents and root's boot are entities on main, written by functions that answer transaction data and read back as plain values.

- **Writes:** ```seon.task/task-id``` (`ns/name`), ```seon.task/default-branch``` (`task-<ns>-<name>`), ```seon.task/start-tx``` and ```seon.task/refused-tx``` (start or refusal), ```seon.task/outcome-tx```, ```seon.task/move-tx``` (compare-and-swap) ([reconcile](reconcile.md#a-task-holds-how-its-work-ended)); ```seon.task/message-tx``` the one message fact; ```seon.task/placeholder-assertion``` the stub assertion in a proof not written yet, read by `seon.problems/uncited-tasks`.
- **Reads:** ```seon.task/tasks``` and ```seon.task/task``` answer a task with the plan and proofs ```seon.task/accumulate``` gathers over its needs (each prerequisite once, first); ```seon.task/running```, ```seon.task/agents```; the outcome sets declared on `:seon.task/outcome`'s schema, ```seon.task/finished```, ```seon.task/judged``` and ```seon.task/failed```; ```seon.task/done?```; ```seon.task/root```; ```seon.task/entity-value``` and ```seon.task/entity-ids``` for the reconciler's index ([root](root.md#the-reconcilers-index)).

## Where a task's state lives

A task's state is derived from its outcome on main (```seon.reconcile/task-state```: `:cancelled`, `:merged`, `:done`, `:running`, `:blocked`, `:failed`, `:held`, `:open`), never stated. Work merged from a worktree has no outcome, so its merge says `Completes: ns/name` and removes the entry ([merging](merging.md#a-merge-says-what-it-completes)).

Related: [turn](turn.md), [merging](merging.md), [references](references.md) (a plan's citations), [parity](parity.md) (v1's chain and templates).
