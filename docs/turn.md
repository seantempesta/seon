# The turn loop

An agent works its task in a loop inside its own process: observe the branch, end if the done rule refuses nothing, otherwise send the context to the model and evaluate its reply, then observe again.

## One run, from release to judge

Root's reconciler forks an agent for a released task and evaluates ```seon.turn/work!``` in it, naming the task, the run and `:seon.config.agent/max-turns`. The loop ends on the first of:
- the done rule refuses nothing (`:green`; all green at the start ends it with no model call);
- a function asked to stop: `my.turn/wait` (`:waiting`) or `my.task/fail!` (`:failed`, the task held for root: [tasks](tasks.md#a-refused-run-is-held-for-root));
- the turns run out (`:cap`, with the done rule's reasons as its reason);
- a turn repeats the last turn's calls from the same observed state (`:no-progress`, naming the calls);
- a merge task's merge of main is too hairy (`:merge-failed`, [below](#a-merge-tasks-loop));
- the model call fails (`:error`: [model](model.md#failures)).

Its end is written on the branch (`seon.turn/end!`: outcome, turns, tokens, the error that ended it, the turns' `:waste`), the live runtime is staged onto the branch (`seon.stage/stage!`) so the next agent on it holds it, and root's judge reads that end, asks the agent for its test results, reads the branch and its diff, and writes the run's end on main from those measured facts (`seon.reconcile/judge-tx`; a merge task is green only when merging main again brings nothing new).

## Observe

Each turn starts from facts measured now, never remembered: `seon.turn/observe` asks the test service (`seon.agent/prove!`, `seon.prove`: one run per branch and tx whoever asks, in a fork of the agent) to run the task's accumulated proofs and the tests the branch changed, bounded by `:seon.config.agent/turn-test-ticks` (a run that cannot be made ran no proof, so every proof is unknown and the run's error is `:test-run`), the contract gaps the branch adds since its merge base (`seon.merge/uncontracted`, the question root's judge asks), its unmerged entries, the problems its own work made true (`seon.problems/caused-split`: `:caused` refuses done, `:attention` is only shown), and the done rule over them (`seon.reconcile/done-explain`: [tasks](tasks.md#the-done-rule)). `:will-merge` is everything the branch's merge would carry to main, and `:tests` says how many tests this turn ran.

## Act

`seon.turn/act!` records what the context is built from (its basis: the commit it reads, the task, the turn, the observations, and the last evaluations' transcript on a first turn or an outside read) rather than the context's text, renders the context ([render](render.md#the-models-context-a-cached-prefix-then-the-turn)), and asks root's `:model` owner for a completion. Each of the reply's eval calls goes through the one REPL path (`seon.repl/eval-string`: read, evaluate, record), and its answer is that call's result, which the next model call reads. The turn's facts (reply, reasoning, finish reason, each call's evaluations), its tokens and the prefix-cache counts ([model](model.md#usage-is-recorded-never-assumed)) and where its time went (`:seon.turn/split`) go on the branch.

## Who runs which tests

Tests run three times in a run's life, each on what it needs:
- **each turn**, in the agent: the task's proofs and the tests the branch changed, in a fork, under `:seon.config.agent/turn-test-ticks` (above; the tests it did not finish are unknown, never red);
- **the judge**, in root: when the loop ends, root asks the agent's REPL for its test results (`:tests`, the plan's in-process tests under `:seon.config.agent/test-ticks`) and judges the end from them, the writer's diff of the branch and the branch's end (`seon.reconcile/judge-tx`); a process test never runs in a turn or the judge, and is read as deferred ([merging](merging.md#a-merge-runs-what-the-change-reaches));
- **the merge**: the writer's merge gate takes the branch only when its contract gaps are none, the same `seon.merge/uncontracted` answer, and the process tests the change reaches run before it merges only when root asked (`my.root/gate!`), else it merges with them unrun ([merging](merging.md#a-merge-runs-what-the-change-reaches)).

## A merge task's loop

A merge task's agent first merges main into its branch with no model call, and merges main again each time all is green, until a merge brings nothing new; it stops early when the merge is too hairy (`seon.reconcile/hairy`). Its unmerged entries are in its context, and it resolves them at the REPL ([agent](agent.md#merging)).

## An outside agent

A Claude session works a task through the same path: `seon_attach` and `seon_done` on the server. ```seon.turn/context-block``` gives it the block a model would read, observed now, and records the read as a turn of the run with origin `:outside`.
