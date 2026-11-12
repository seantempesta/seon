# The turn loop

An agent works its task in a loop inside its own process: run its proofs, end if the done rule refuses nothing, otherwise send the context to the model and evaluate its reply, then run its proofs again.

## Work, from release to judge

Root's reconciler starts an agent for a released task (a fork, or a wake of the agent parked on the task's branch) and evaluates ```seon.turn/work!``` in it, naming the task and `:seon.agent/max-turns`. The loop ends on the first of:
- the done rule refuses nothing (`:green`; all green at the start ends it with no model call);
- `my.task/fail!` asked to stop (`:failed`, held for root: [tasks](tasks.md#a-refused-run-is-held-for-root));
- a merge task's merge of main is too hairy (`:merge-failed`, [below](#a-merge-tasks-loop));
- the turns run out (`:cap`, [the cap](#the-cap));
- `my.turn/wait` asked to stop, or a reply made no eval call (`:waiting`);
- the model call fails (`:error`: [model](model.md#failures)).

## The end

An agent's end is a fact on its branch, which root reads once to decide how its task stands. The loop writes it through ```seon.agent/write-end!```: `:seon.agent/outcome` (`:green` `:red` `:waiting` `:cap` `:failed` `:merge-failed` `:error`), the reason, the error as data, the turns' `:waste`, and `:seon.agent/ended-turn`. The process's death writes it the same way, and each wake clears the end before it (```seon.agent/clear-end!```). The live runtime is staged onto the branch (`seon.stage/stage!`). Root's judge reads the end (```seon.reconcile/pull-end```), the agent's test results and the branch's diff, and stores its decision on the task (`seon.reconcile/judge-tx`; [reconcile](reconcile.md#a-task-holds-how-its-work-ended)). An agent that wrote no end is a task `:dead`; an outside agent's `:done` writes its end through `seon.turn/hand-back!`.

## The cap

The cap is the agent's own: `:seon.agent/turn` against `:seon.agent/max-turns`, never restarting across wakes. The write that starts a woken agent adds `:seon.config.agent/wake-turns` to `:seon.agent/max-turns` (``seon.task/start-tx``; `seon.reconcile-test/a-wake-raises-max-turns-by-its-allowance`), so an agent nobody messages is never woken and one that is has an allowance. An agent whose turns are spent ends `:cap`, never `:waiting` (`seon.agent-test/a-task-at-its-cap-is-recorded-cap-never-waiting`). A cap ends the run, held, and nothing parks on it (`seon.reconcile-test/a-cap-ends-the-run-held-and-gets-its-refine`); a person may raise its max and release the task (`my.root/run!`), which runs it again from its branch's head. A reply with no evals stops it early (`seon.agent-test/a-reply-with-no-evals-stops-with-turns-left`). The page shows the cap on the turn line (`seon.render.ui/turn`).

## Observe

Each turn starts from facts measured now: `seon.turn/observe` asks the test service (`seon.agent/prove!`, [merging](merging.md#the-test-service)) to run the task's proofs and the tests the branch changed, each under `:seon.config.agent/test-ticks`. The results are facts on the branch (```seon.agent/record-results!```), and a run that cannot be made is one too (`:seon.agent/test-error`, every proof `:not-run`). How the work stands is ```seon.ctx.status/status``` of those facts ([render](render.md#how-the-work-stands)), the one value the loop, the page and the model read.

## Act

`seon.turn/act!` records the context's basis (the commit it reads, the task, the turn's number) rather than its text, places the page before its call when it moved ([the render is an eval](render.md#the-render-is-an-eval)), builds the request from the agent's records ([render](render.md#the-models-context-a-cached-prefix-then-the-turn)), and asks root's `:model` owner for a completion. Each eval of the reply (an `intent` and forms: [prompt](prompt.md)) runs through `seon.repl/eval-string`, and its result is what the next call reads. The reply, reasoning, finish reason, tokens and cache counts ([model](model.md#usage-is-recorded-never-assumed)) go on the turn's model call beside its basis and `:seon.model-call/sent-hash` ([the debug page](render.md#the-debug-page-is-the-wire-request)).

## The turn is a number

A turn is a number, not an entity (owner, 2026-11-04: "wtf is a turn but a number?"). `:seon.agent/turn` is agent-wide and never restarts. It moves in `seon.turn/act!`, in the transaction holding the turn's model call's first facts; the call's id is the digest of the agent's key and `n` (``seon.lib/call-id``). An outside agent's context read (`seon.turn/context-block`) moves no number; its call's id is the digest of the key and `read-<k>`.

Every eval names the model call of the turn in force (`:seon.eval/call`, ```seon.task/call-ref```; the loop's own eval and a context read name none). Before the first turn the number is 0 and the call is `<agent key>/0`. A message names no call: the turn that read it is the first whose basis `:t` is at or after the transaction that delivered it, which the pattern asks (`{[:seon.message/_to :tx :at] ..}`; `seon.ctx/placed-messages`). Messages delivered by `seon.turn/deliver!` precede the basis; one taken mid-turn reads at the next turn.

So the conversation is its turns in number order (```seon.ctx/turns```). Each turn is a component (`seon.render.ui/conversation-turn`) over its call's evals (```seon.ctx/turn-evals```, a reply's tool results right after its tool calls) and its messages (```seon.ctx/placed-messages```). The page's chat and the model's history read this one order (`seon.ctx.wire-test/the-chat-and-the-request-walk-one-sequence`), resting on when each record reached the branch, never on entity ids (`seon.ctx.wire-test/a-message-delivered-mid-turn-reads-after-that-turns-evals-on-a-branch-that-merged-main`). Unlinking an eval from `@ctx` never moves another to a different reply.

## An eval can be run again

A person's step button runs an eval again in its place (```seon.repl/again```, optionally edited): the same `:seon.eval/id`, its code and response replaced, the old ones in history, so the next request reads the new one where the old stood. ```seon.repl/again-to``` runs the conversation's evals down to one, in order, stopping at the first that fails. Effects happen again. The call goes through root's `again!` with origin `:context`.

## An agent goes back to an eval

A person's Back to here takes an agent to just after an eval (```seon.system/back!```): a new branch from the commit holding that eval's last write (the writer's `:clone-branch`), this process ended and one spawned on the new branch under the same key; the old branch stays. What lived only in the process (atoms, `agent.*` definitions) is rebuilt by running the steps again. The task moves to the new branch, [parked](#parked). It is refused, nothing ended, when the agent isn't idle, is already going back, cannot be started, or the eval has no commit of its own.

## An eval can be inserted

The model is one source of an agent's evals; whoever creates the agent is another: `seon.system/spawn!` takes `:evals`, pairs of intent and code. Root sends them at the agent's hello as one write, run before anything else (`seon.repl/insert!`, over `seon.repl/run-calls`). Each is an ordinary eval, `:seon.eval/by` the system entity, no call id, and every one runs whatever the one before did ([decisions](decisions.md#every-eval-of-a-reply-runs-owner-2026-11-08)).

The page shows it as its own conversation entry. The request makes its tool call from its id, intent and code (```seon.ctx.wire/eval-msgs```, call id `eval-<id>`), so no reply's message is edited; an eval with no intent is one user message. The page itself is no record: it is generated before each model call from its basis and the one before (```seon.ctx.wire/page-diff-msgs```).

## Who runs which tests

Which tests run where: each turn, the gate, the judge and the merge.

- **each turn**, in a fork of the agent: the task's proofs and the tests the branch changed, each under `:seon.config.agent/test-ticks` (past it a test is red, `:seon.test/ticks-spent`);
- **the gate**, in the turn: a clear status runs the judge's selection, then the process tests on the merge gate's checkout (```seon.agent/judge!```), so a red comes back with a turn left;
- **the judge**, in root: root asks the agent's REPL for its `:tests` (greens at their digest reused) and judges the end from them, the branch's diff and its end (`seon.reconcile/judge-tx`); a process test that is no proof is read as deferred;
- **the merge**: the writer's gate takes the branch only with no contract gaps (`seon.merge/uncontracted`) and runs the process tests the change reaches ([merging](merging.md#a-merge-runs-what-the-change-reaches)).

The tests run in a fork of the agent: the plan is selected in the parent (```seon.agent/test-inputs```, ```seon.agent/branch-graph```, ```seon.agent/judges?```) and run in the child, a proc of the agent's graph, which puts each test's name on its link as it starts so a death names it. A proc of the run folds what the children put (```seon.agent/test-run-step```) and answers (```seon.agent/test-run-answer```); ```seon.agent/test-report``` keeps one `clojure.test` report per test.

## A merge task's loop

Every agent first merges main into its branch with no model call. A merge task's agent merges main again each time all is green, until a merge brings nothing new; it stops when the merge is too hairy (`seon.reconcile/hairy`, over the task's facts). It resolves unmerged entries at the REPL ([agent](agent.md#merging)). Green work of any task merges main before it ends.

## What a turn merges from main

Messages to the agent and root's decisions on its task are on main, and a turn delivers them onto its branch before it observes (`seon.turn/deliver!`, the one way outside facts reach the branch), so a session whose process is gone takes them when spawned again.
- **Messages** (```seon.agent/undelivered```): `:seon.message/*` entities on main naming the agent in `:seon.message/to` that its branch lacks: a person's (`seon.system/message!`), another agent's, and root's (a stage boundary, a `:verdict`). Each is written with the same id, in main's order; `(swap! ctx update :messages dissoc e)` removes one for good ([messages](users.md#messages)).
- **Opened evals** (```seon.agent/untaken-facts```): main's `:seon.agent/opened` copied as `:seon.eval/opened`.

An end the done rule refuses is told why: root's judge writes a `:verdict` message with its reasons, stands the task `:told`, and the reconciler wakes the agent ([processes](processes.md#a-parked-process)). The same reasons again hold the task, except a verdict that is only `:behind` (main moved), told again until `:seon.config.merge/max-behind` judgments (`seon.reconcile/judgment`); a task a merge task follows is never judged behind (`seon.reconcile-test/a-green-work-task-judged-behind-is-done-and-its-merge-runs`). A merge task's refused end holds at once, as an outside agent's does.

## Effects from an agent

An agent reaches outside only through root's owner procs ([root](root.md)): ```seon.agent/fx!``` asks an effect and waits for its outcome; ```seon.agent/fx-send!``` answers its key at once and the outcome arrives as a message `{:from :fx :k :kind :body}`. In a test fork the test binds ```seon.agent/*fx-stub*```; with none bound the effect is the error `:seon.fx/in-test`.

## Parked

An agent that ends waiting parks its process on the task (`:parked`) until the next message wakes it with its allowance ([the cap](#the-cap)): the same process, no fork ([processes](processes.md#a-parked-process)), costing only its memory ([decisions](decisions.md#a-parked-agent-stays-in-memory-and-replay-is-deliberate)). A reply with text and no calls parks it whatever its proofs say ([decisions](decisions.md#a-reply-with-no-calls-pauses-the-agent-whatever-its-proofs-say)). A message is on main first, so a lost wake or a dead process loses nothing: the task is `:lost` and the next start a spawn. Resuming a session by replaying its pure evals is planned, not built (task `seon.reconcile/root-work-is-tasks`).

## An outside agent

A Claude session works a task through `seon_attach` and `seon_done`. ```seon.turn/context-block``` gives it the request a model would get, as text or, with `wire`, as the provider's JSON body (`seon.ctx.wire/wire-turn`). The agent's own outside model's read takes its messages, shows the page, and is recorded as a model call with origin `:outside`; an observer's read writes nothing (owner, 2026-11-10: "A read never writes"; `seon.server-test/a-context-read-that-only-looks-writes-nothing`). Its `seon_eval` is one turn (```seon.turn/outside-reply!```, opened by `seon.turn/open-turn!`, its evals through ```seon.repl/run-calls```, model "outside"), so its conversation reads as a model's. The answer is what a model's next request would add: the results and any page eval. A read runs no proof; an agent whose REPL is busy is read from its branch, saying so.
