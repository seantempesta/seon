# The turn loop

An agent works its task in a loop inside its own process: observe the branch, end if the done rule refuses nothing, otherwise send the context to the model and evaluate its reply, then observe again.

## One run, from release to judge

Root's reconciler forks an agent for a released task and evaluates ```seon.turn/work!``` in it, naming the task, the run and `:seon.config.agent/max-turns`. The loop ends on the first of:
- the done rule refuses nothing (`:green`; all green at the start ends it with no model call);
- `my.task/fail!` asked to stop (`:failed`, the task held for root: [tasks](tasks.md#a-refused-run-is-held-for-root));
- a merge task's merge of main is too hairy (`:merge-failed`, [below](#a-merge-tasks-loop));
- the turns run out (`:cap`, with the done rule's reasons as its reason): a run whose last turn waits spent its budget, so it is `:cap`, never `:waiting` (`seon.agent-test/a-run-at-its-cap-is-recorded-cap-never-waiting`);
- `my.turn/wait` asked to stop, or a reply made no eval call (`:waiting`);
- the model call fails (`:error`: [model](model.md#failures)).

Its end is written on the branch (`seon.turn/end!`: outcome, turns, tokens, the error that ended it, the turns' `:waste`), the live runtime is staged onto the branch (`seon.stage/stage!`) so the next agent on it holds it, and root's judge reads that end, asks the agent for its test results, reads the branch and its diff, and writes the run's end on main from those measured facts (`seon.reconcile/judge-tx`; a merge task is green only when merging main again brings nothing new).

## Observe

Each turn starts from facts measured now, never remembered: `seon.turn/observe` asks the test service (`seon.agent/prove!`, `seon.prove`: one run per branch and program digest whoever asks, in a fork of the agent) to run the task's accumulated proofs and the tests the branch changed, each test bounded by `:seon.config.agent/test-ticks` (a run that cannot be made ran no proof, so every proof is unknown and the run's error is `:test-run`), the contract gaps the branch adds since its merge base (`seon.merge/uncontracted`, the question root's judge asks), its unmerged entries, the problems its own work made true (`seon.problems/caused`: it refuses done), and the done rule over them (`seon.reconcile/done-explain`: [tasks](tasks.md#the-done-rule)), read from the reconciler's index of the branch, which the agent holds across turns and observes from the last turn's db over what differs (`seon.turn/index!`, `seon.reconcile/index`): listing it again every turn cost 92M ticks and 384 MB on a main of 680 tasks, observing it 1.3M and 10 MB. That is all a turn observes: the done rule's inputs. What else a reader wants of the branch (whether it can merge, what its merge carries, what it changed under the wiki, its tests) is a view of the task's render (```seon.render.ui/merge-view```, `divergence-view`, `caused-view`, `gaps-view`), a component over the branch's db with main's and the merge base's commits as its children, drawn when one of them moves, never per turn (owner, 2026-10-27: "Info that is derived and made for the agent or the user should go into the hiccup"). Merge readiness per turn read main's whole diff (1.78B ticks with main moved); a turn's observe now costs 9.4M ticks (measured on an outside agent, nothing new), and an unrelated eval redraws none of the views (`seon.render-test/an-unrelated-change-redraws-none-of-the-merge-views`).

## Act

`seon.turn/act!` records what the context is built from (its basis: the commit it reads, the task, the turn, the observations, and the last evaluations' transcript on a first turn or an outside read) rather than the context's text, renders the context ([render](render.md#the-models-context-a-cached-prefix-then-the-turn)), and asks root's `:model` owner for a completion. Each of the reply's eval calls goes through the one REPL path (`seon.repl/eval-string`: read, evaluate, record), and its answer is that call's result, which the next model call reads. The turn's facts (reply, reasoning, finish reason, each call's evaluations), its tokens and the prefix-cache counts ([model](model.md#usage-is-recorded-never-assumed)) and where its time went (`:seon.model-call/split`) go on the branch, on the turn's model call (`:seon.model-call/*`, its own entity pointing at its turn) beside the basis and what it sent; the turn keeps its reply, reasoning, calls and finish.

## Who runs which tests

Tests run three times in a run's life, each on what it needs:
- **each turn**, in the agent: the task's proofs and the tests the branch changed, in a fork, each test under `:seon.config.agent/test-ticks` (one bound everywhere, per test: a test past it is red, `:seon.test/ticks-spent`, the same in the judge);
- **the judge**, in root: when the loop ends, root asks the agent's REPL for its test results (`:tests`, the plan's in-process tests under the same per-test bound; each green the turn left at its digest is reused, so the judge runs only what the turn did not, and forks nothing when that is none) and judges the end from them, the writer's diff of the branch and the branch's end (`seon.reconcile/judge-tx`); a process test that is none of the task's proofs never runs in a turn or the judge, and is read as deferred ([merging](merging.md#a-merge-runs-what-the-change-reaches));
- **the merge**: the writer's merge gate takes the branch only when its contract gaps are none, the same `seon.merge/uncontracted` answer, and the process tests the change reaches run before it merges, on the root's gate checkout, nobody asking (a root with none holds it) ([merging](merging.md#a-merge-runs-what-the-change-reaches)).

## A merge task's loop

Every run's agent first merges main into its branch with no model call (a kept branch made before main moved holds main's program, its tests and their digests included; one at main's head brings nothing). A merge task's agent merges main again each time all is green, until a merge brings nothing new; it stops early when the merge is too hairy (`seon.reconcile/hairy`). Its unmerged entries are in its context, and it resolves them at the REPL ([agent](agent.md#merging)).

## What a turn takes from main

Words to the agent and what root decided of its runs are facts on main, and a turn takes them onto its branch before it observes, so its context and page read them from the branch like any other fact: `seon.turn/take!`, the one way outside facts reach the branch. Root writes nothing to a live process for them, so a session whose process is gone (parked in an earlier boot, a lost wake) takes them when it is spawned again.
- **Entries** (```seon.agent/untaken-entries```): the `:seon.message/*` facts main holds to the runs of the agent's session (its run's `:seon.run/branch`, as main names it), of each kind (`:seon.message/kind`): a person's `:message` (`seon.root/message!`, refused for an agent working no run), root's `:stage` boundary, and root's `:verdict` (below). Each is written on the branch with the same id, in main's order.
- **Taken attributes** (```seon.agent/untaken-facts```): the run attributes whose schema says `{:seon.db/taken true}`, its outcome and reason, copied onto the runs the branch holds; never its status, since the branch holds its run by reference and a status there says it holds the whole run. With them, each eval of its own the page opened (main's `:seon.agent/opened`, written by `seon.root/opened!`) as `:seon.eval/opened`.

A run the done rule refuses (`:green` or `:red` by the agent's own word, but not done by root's facts) is told why rather than held: root's judge writes a `:verdict` entry with the reasons and results it judged on and parks the run, and the entry wakes the session ([processes](processes.md#a-parked-process)). The same reasons again hold the task. A merge task's refused run holds at once, and so does an outside agent's, which read the verdict as `seon_done`'s answer.

## A session parks, wakes on a message and is loaded at a commit

A session is the agent's branch across its runs, and a run that ends waiting or out of turns parks its process until the next message to the session wakes it for its next run ([processes](processes.md#a-parked-process)); a parked agent costs its memory and nothing else. A message is a fact on main first and a turn takes it onto the branch (above), so a wake that was lost, or a process that died, loses no word.

Starting and resuming are meant to be one mechanism (owner, 2026-10-25), loading the session at a commit: a new task loads its branch's head, a dead process loads the same head, and a rewind (`d/branch!` at the commit, a pointer) loads the earlier one. A load forks, loads the program diff from main's image to the commit (about 0.35 s, most of its cost), binds ``result/eN`` to each recorded data value, and replays only the pure evals of the record, each marked as replayed; an eval that asked an effect is listed and never replayed unless the agent evaluates it again by hand ([processes](processes.md#a-parked-process): a replay trusts no form whose effect hides behind a helper or whose world moved on). This is planned, not built (task `seon.reconcile/root-work-is-tasks`; the design and its lab measurements are in `docs/turn.md`).

## An outside agent

A Claude session works a task through the same path: `seon_attach` and `seon_done` on the server. ```seon.turn/context-block``` gives it the request a model would be sent, observed now (the page as its model would read it), and records the read as a turn of the run with origin `:outside`.
