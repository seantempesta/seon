# The turn loop

An agent works its task in a loop inside its own process: run its proofs, end if the done rule refuses nothing, otherwise send the context to the model and evaluate its reply, then run its proofs again.

## Work, from release to judge

Root's reconciler starts an agent for a released task (a fork, or a wake of the agent parked on the task's branch) and evaluates ```seon.turn/work!``` in it, naming the task and `:seon.agent/max-turns`. The loop ends on the first of:

- the done rule refuses nothing (`:green`; all green at the start ends it with no model call);
- `my.task/fail!` asked to stop (`:failed`, the task held for root: [tasks](tasks.md#a-refused-run-is-held-for-root));
- a merge task's merge of main is too hairy (`:merge-failed`, [below](#a-merge-tasks-loop));
- the turns run out (`:cap`, [the cap](#the-cap));
- `my.turn/wait` asked to stop, or a reply made no eval call (`:waiting`);
- the model call fails (`:error`: [model](model.md#failures)).

## The end

An agent's end is a fact on its branch, and root reads it once to decide how its task stands.

The loop writes it (`seon.turn/end!`, through ```seon.agent/write-end!```): `:seon.agent/outcome` (`:green` `:red` `:waiting` `:cap` `:failed` `:merge-failed` `:error`), the reason, the error that ended it as data, the turns' `:waste`, and `:seon.agent/ended-turn`, the turn it came on. The process's death writes it the same way. The live runtime is staged onto the branch (`seon.stage/stage!`) so the next agent on it holds it. Each wake begins by clearing the end before it (```seon.agent/clear-end!```). Root's judge reads the end with ```seon.reconcile/pull-end```, asks the agent for its test results, reads the branch and its diff, and stores its decision on the task from those measured facts (`seon.reconcile/judge-tx`; a merge task is green only when merging main again brings nothing new): [reconcile](reconcile.md#a-task-holds-how-its-work-ended). An agent that wrote none (it died, was killed, or never reached its end) is a task `:dead`; an outside agent's `:done` writes its end through `seon.turn/hand-back!`.

## The cap

The cap is the agent's own: `:seon.agent/turn` (and the turns that threw) against `:seon.agent/max-turns`, never restarting across wakes.

A wake gets no fresh budget. An agent whose last turn waits when its turns are spent ends `:cap`, never `:waiting` (`seon.agent-test/a-task-at-its-cap-is-recorded-cap-never-waiting`), and its task stands `:cap`: no message wakes it. A person raises `:seon.agent/max-turns` and releases the task again (`my.root/run!`); the same agent continues from its branch. The page reads the cap as its turn line (`seon.render.ui/run-head`).

## Observe

Each turn starts from facts measured now, never remembered: `seon.turn/observe` asks the test service (`seon.agent/prove!`, `seon.prove`: one test run per branch and program digest whoever asks, in a fork of the agent) to run the task's accumulated proofs and the tests the branch changed, each test bounded by `:seon.config.agent/test-ticks`. The results are facts on the branch (```seon.agent/record-results!```: each test's verdict at its reach digest now, its failing assertions as data, a process test left to root's gate as `:deferred`), and a test run that cannot be made is one too (`:seon.agent/test-error`, every proof then `:not-run`). Nothing else is observed or held: how the work stands (the proofs, the done rule's reasons over them, the contract gaps, what the work caused, the unmerged entries, whether it can merge) is ```seon.context/status``` of those facts, which the agent's walk draws once and the loop's done check reads from it (`seon.turn`'s `status-now`: [render](render.md#how-the-work-stands)), the same value the page and the model read.

## Act

`seon.turn/act!` records what the context is built from (its basis: the commit it reads, the task and the turn's number: everything the request shows is a fact of that commit, what the turn took among them) rather than the context's text, builds the request from the agent's record and its live walk ([render](render.md#the-models-context-a-cached-prefix-then-the-turn): the system prompt, the page's parts, whole at the session's first request and after it only those whose content changed, and the records in order), and asks root's `:model` owner for a completion. Each of the reply's eval calls (prose and one or more forms: [prompt](prompt.md)) goes through the one REPL path (`seon.repl/eval-string`: read, evaluate, record), and its answer is that call's result, which the next model call reads. The turn's facts (reply, reasoning, finish reason; each call's evaluations are evals of the turn, below), its tokens and the prefix-cache counts ([model](model.md#usage-is-recorded-never-assumed)) and where its time went (`:seon.model-call/split`) go on the branch, on the turn's model call (`:seon.model-call/*`, its own entity pointing at its agent) beside the basis and what it sent (`:seon.model-call/sent`: the records and parts the request held and each part's content hash, [the page is sent as what changed](render.md#the-page-is-sent-as-what-changed)).

## The turn is a number

A turn is a number, not an entity (owner, 2026-11-04: "wtf is a turn but a number? An agent has turns and evals why are we organizing them on top of other entities"). `:seon.agent/turn` is the agent's: agent-wide, never restarting across wakes (the cap reads it: [above](#the-cap)). It moves when a model turn starts, in `seon.turn/act!`, in the same transaction as the turn's model call's first facts; the model call is `<agent key>/<n>`, with `:seon.model-call/agent`, `n` and `origin`, and later its reply, reasoning, finish, truncation and error (a failed call's typed error is on it). A context read an outside agent makes (`seon.turn/context-block`) moves no number: its model call is `<agent key>/read-<k>`, `k` the agent's own count of reads, counted from the branch.

Each record takes the number in force when it is written: `:seon.eval/turn` on every eval of the agent (the agent's code's and an outside `seon_eval`'s included; the loop's own eval, which is no conversation record, takes none), `:seon.message/turn` on a message when it is taken. A model turn's messages (`seon.turn/take!`) and the loop's own (`seon.turn/tell-error!`, the repeated-turn note) take the number of the turn that reads them, one past the one in force; a message the agent's code takes in the middle of a turn (`seon.agent/messages`) takes the number in force. Before its first turn the number is 0.

So the conversation is ordered by the numbers it carries (`seon.context/records`, and the page with it, `seon.render.ui/conversation`): turn, then rank (a message 0, a reply 1, an eval 2), then the message's transaction or the eval's issue number (`e9` before `e10`). A request for turn *n* holds the records of earlier turns and the messages taken in *n*; its evals and its reply come of it. A reply's text and reasoning are its model call's, and its evals belong to it by their turn number, so unlinking an eval from `@ctx` never moves another to a different reply. What a request has not printed yet is read newest first until a record's turn is below the previous request's.

## Who runs which tests

Tests run three times in a task's work, each on what it needs:
- **each turn**, in the agent: the task's proofs and the tests the branch changed, in a fork, each test under `:seon.config.agent/test-ticks` (one bound everywhere, per test: a test past it is red, `:seon.test/ticks-spent`, the same in the judge);
- **the judge**, in root: when the loop ends, root asks the agent's REPL for its test results (`:tests`, the plan's in-process tests under the same per-test bound; each green the turn left at its digest is reused, so the judge runs only what the turn did not, and forks nothing when that is none) and judges the end from them, the writer's diff of the branch and the branch's end (`seon.reconcile/judge-tx`); a process test that is none of the task's proofs never runs in a turn or the judge, and is read as deferred ([merging](merging.md#a-merge-runs-what-the-change-reaches));
- **the merge**: the writer's merge gate takes the branch only when its contract gaps are none, the same `seon.merge/uncontracted` answer, and the process tests the change reaches run before it merges, on the root's gate checkout, nobody asking (a root with none holds it) ([merging](merging.md#a-merge-runs-what-the-change-reaches)).

## A merge task's loop

Every work's agent first merges main into its branch with no model call (a kept branch made before main moved holds main's program, its tests and their digests included; one at main's head brings nothing). A merge task's agent merges main again each time all is green, until a merge brings nothing new; it stops early when the merge is too hairy (`seon.reconcile/hairy`, over the task's facts: the conflicts, the turns, and the names the task's proofs and plan's cites hold, never the plan re-parsed). Its unmerged entries are in its context, and it resolves them at the REPL ([agent](agent.md#merging)).

## What a turn takes from main

Words to the agent and what root decided of its task are facts on main, and a turn takes them onto its branch before it observes, so its context and page read them from the branch like any other fact: `seon.turn/take!`, the one way outside facts reach the branch. Root writes nothing to a live process for them, so a session whose process is gone (parked in an earlier boot, a lost wake) takes them when it is spawned again.
- **Entries** (```seon.agent/untaken-entries```): the `:seon.message/*` facts main holds to the agent's session (its branch, `:seon.agent/branch`, as main names it), of each kind (`:seon.message/kind`): a person's `:message` (`seon.root/message!`, refused for an agent working no task), root's `:stage` boundary, and root's `:verdict` (below). Each is written on the branch with the same id, in main's order.
- **Opened evals** (```seon.agent/untaken-facts```): each eval of its own the page opened (main's `:seon.agent/opened`, written by `seon.root/opened!`), copied onto the branch as `:seon.eval/opened`. How many messages the agent took is a count root writes on the agent at its judging (`:seon.agent/taken`), which a parked agent is woken against.

An end the done rule refuses (`:green` or `:red` by the agent's own word, but not done by root's facts) is told why rather than held: root's judge writes a `:verdict` entry with the reasons and results it judged on and stands the task `:told`, and the entry wakes the session ([processes](processes.md#a-parked-process)). The same reasons again hold the task, except a verdict that is only `:behind`, which only a task no merge task follows gets: main moved under the branch, which is no news of the agent's (the same count twice is two different mains), so it is told again until `:seon.config.merge/max-behind` judgments were `:behind` (`seon.reconcile/judgment`). A task a merge task follows is never judged behind at all: the merge task merges main into the branch and the gate tests what it writes, so its green work is done and its merge starts (bin/check swarm held green tasks after three behind verdicts under a busy main, 2026-10-31: `seon.reconcile-test/a-green-work-task-judged-behind-is-done-and-its-merge-runs`). Green work merges main before it ends, a work task's as a merge's, until the merge brings nothing new (`seon.turn/work!`), so it ends on main as main is. A merge task's refused end holds at once, and so does an outside agent's, which read the verdict as `seon_done`'s answer.

## Effects from an agent

An agent reaches outside only through root's owner procs ([fx](root.md)): ```seon.agent/fx!``` asks an effect kind and waits for its outcome (`{:value ...}` or its declared error), recorded with the eval that asked; ```seon.agent/fx-send!``` starts it and answers its key at once, and the outcome arrives later as a message `{:from :fx :k :kind :body}` in the inbox. Inside a test fork nothing leaves the process: the test binds ```seon.agent/*fx-stub*``` to a function of kind and request that answers the outcome, and with none bound the effect is the typed `:seon.fx/in-test` error.

## Folding the turns into waste

At its end the agent folds

## The tests in a fork of the agent

The tests a turn or the judge runs execute in a fork of the agent, within the per-test tick bound ([above](#who-runs-which-tests)), so the plan is selected in the parent and run in the child. What the selection reads: ```seon.agent/test-inputs``` (the reach inputs of the tests: root's binary's when it was built from its source, else blank), ```seon.agent/branch-graph``` (the branch's reach graph, the base's updated to the branch's db and held while that db is the newest asked: a second ask is a key lookup) and ```seon.agent/judges?``` (a `deftest` of a namespace that is not scratch). The plan, the fork and the test service are in [merging](merging.md#the-test-service). Each test is written as it starts on a pipe to the parent, so a death names the test that was running. The parent reads the pipe with ```seon.agent/read-to-exit``` (to the pipe's end of file or the child's exit, whichever comes first, because a process the child forked may hold the write end) and answers with ```seon.agent/test-run-answer``` (the last line when it is the result; else the child died while running the test last started, and the answer is one error report on it; a pipe still held is an error report of its own). ```seon.agent/test-report``` keeps one `clojure.test` report: type, test var, message, the failing assertion's expected and actual by the one printer, and a cost proof's rows and verdict.

## Parked

A session is the agent's branch across its tasks, and an agent that ends waiting or at its cap parks its process on the task (`:parked`, `:cap`) until the next message to the session wakes it (a `:cap` task waits for a raised budget instead: [the cap](#the-cap)): the same agent, no fork ([processes](processes.md#a-parked-process)); a parked agent costs its memory and nothing else. A message is a fact on main first and a turn takes it onto the branch (above), so a wake that was lost, or a process that died, loses no word: the task is `:lost` and the next start a spawn of the same agent.

Starting and resuming are meant to be one mechanism (owner, 2026-10-25), loading the session at a commit: a new task loads its branch's head, a dead process loads the same head, and a rewind (`d/branch!` at the commit, a pointer) loads the earlier one. A load forks, loads the program diff from main's image to the commit (about 0.35 s, most of its cost), binds ``result/eN`` to each recorded data value, and replays only the pure evals of the record, each marked as replayed; an eval that asked an effect is listed and never replayed unless the agent evaluates it again by hand ([processes](processes.md#a-parked-process): a replay trusts no form whose effect hides behind a helper or whose world moved on). This is planned, not built (task `seon.reconcile/root-work-is-tasks`; the design and its lab measurements are in `docs/turn.md`).

## An outside agent

A Claude session works a task through the same path: `seon_attach` and `seon_done` on the server. ```seon.turn/context-block``` gives it the request a model would be sent, its proofs run now, as one text: the system prompt and the page's parts as its model would read them, every part whole at its first read and after it those that changed since the read before ([render](render.md#the-page-is-sent-as-what-changed)). It records the read as a turn of the agent with origin `:outside`, and a model call holding its basis and what it sent.
