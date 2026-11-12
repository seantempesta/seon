# The reconciler

The reconciler turns main's commits into what root does next, and a task holds how its work stands and ended: root decides once, when an agent ends, and stores the answer on the task.

## A task holds how its work ended

A task's attributes on main say how its work stands, written only by root when it starts the work and when it judges the agent's end: `seon.reconcile/judgment` decides once, in `seon.reconcile/judge-tx`, and `seon.reconcile/actions` reads the stored answer and derives nothing.

- **How it stands** (`seon.task/outcome-tx`, `seon.task/move-tx`): `:seon.task/outcome`, a compare-and-swap guarded state:
  - `:running` (an agent works it), `:parked` (its agent waits for a message, its process stays), `:told` (root told it a verdict and it waits for the next wake), `:cap` (it spent its turns: [turn](turn.md#the-cap));
  - `:done` (accepted on its branch), `:ready` (ready for the octopus merge), `:gate` (its process tests run first), `:merged`, `:behind` (main moved under the merge);
  - `:dead` (its agent died), `:lost` (its process was gone), `:killed` (stopped for a cancel, a hold or a changed environment), `:refused` (root refused the start: [root](root.md#holders-and-refused-spawns)), `:closed` (judged when it no longer ran, or held for root).
  The schema's properties name the sets `seon.task/finished` (`:done` `:merged`), `seon.task/judged` (`:ready` `:gate`) and `seon.task/failed` (`:dead` `:lost`).
- **Why**: `:seon.task/reason` (the agent's words or root's), `:seon.task/reasons` (what the done rule said was not done, [tasks](tasks.md#the-done-rule)), `:seon.task/commit` (the merges-branch commit of the files a merge rewrote; a refused commit is `:seon.task/error`, `:seon.git/commit-failed`). Only when they happen: `merged-t`, `error`, `deferred`, `deferred-results` (the process tests the change reaches, with digests, and the gate's run of them), `gate-unread`, `changed`, `unmerged`, `conflicts`, `task-conflicts`, `failures`, `costs`, `waste`.
- **What judging sets**: `seon.task/outcome-tx` sets what the end measured and retracts every other judged attribute (`seon.task/judged-attrs`), so a task holds its last end only; later steps (octopus merge, gate, write-back) move the outcome with `seon.task/move-tx`, which retracts nothing. Earlier states are the attributes' history (`d/history`).
- **A task's agent**: `:seon.task/agent` refs the agent working it (`seon.task/start-tx` writes it with the outcome swap). The agent holds its environment on main (`:seon.agent/task` `branch` `model` `holder` `max-turns` `boot` `started-ms` `ended-ms`) and its own end on its branch (`outcome` `reason` `ended-turn` `error` `waste`, by `seon.agent/write-end!`). A retry, a wake and a load are the same agent (key `<boot>/<id>`).
- **The end is read once** (`seon.reconcile/pull-end`): each wake first clears the previous wake's end on its branch (`seon.agent/clear-end!`), so a wake that died silently has none and is judged on what root measured. A flight (the chain of requests of one action) is keyed by task id.

A `:cap` task continues when a message wakes it, which raises `:seon.agent/max-turns` by the wake allowance ([the cap](turn.md#the-cap)), or when a person releases it again (`my.root/run!`, which retracts the reasons).

`seon.reconcile/judgment` is pure: given the index and the measured facts (the agent's end, a death, proof results, contract gaps, unmerged keys, how far behind main, how often judged behind, the reasons last told), it answers the outcome, the done rule's reasons, a hold, and a verdict when the agent is told. In order:

- a task no longer `:running` is `:closed`; a dead agent is `:dead` and held;
- a merge task the done rule refuses nothing is `:ready`, or `:gate` when its change reaches process tests (held when this root has no checkout to run them on); any other task is `:done`;
- an agent that ended `:waiting` or `:cap` on a task that is no merge parks (`:parked` or `:cap`), never a hold ([turn](turn.md#parked));
- an end the done rule refuses is told why (`:told`, a verdict message that wakes the session); the same reasons again hold the task. `:behind` alone is told again until `:seon.config.merge/max-behind` judgments, an outside agent's hand-back too (`seon_done`, asked of main itself: `seon.reconcile-test/an-outside-agents-green-work-behind-main-is-judged-behind-not-closed`); a task a merge task follows is never judged behind;
- every other reason holds the task ([tasks](tasks.md#a-refused-run-is-held-for-root)).

## The reconciler's index

The reconciler reads main through an index of its tasks, agents and root, kept current by each commit's datoms: `seon.reconcile/index`.

`seon.reconcile/empty-index` is the index of a db with no tasks. `seon.reconcile/observe` takes the index and a commit's datoms (`[op e a v]`) with the db after it, and re-reads only the entities touched. `seon.reconcile/index` of a db lists every task, agent and root entity; given an earlier index and its db, it observes only the watched datoms that differ (`datoms-diff`), so the cost is what changed, never the program. The sets `:live`, `:reap`, `:kill`, `:judge` and `:merging` hold task ids (`:kill` maps an id to its reason). `:seon.agent/boot` and `:seon.agent/started-ms` are watched: one moving re-derives every task naming the agent. The fork server hands an agent main's index the same way ([processes](processes.md#the-fork-server)).

Asked of it: `seon.reconcile/done?` (merged into its target, or accepted on its branch), `seon.reconcile/quiescent` ([root](root.md#root-causes-at-quiescence)) and `seon.reconcile/last-commit` (the write-back's sha). `seon.reconcile/hairy` decides from measured facts that a merge is too hairy to keep resolving (conflicts past the config key, a deleted var the proofs or cites name, too many turns without green), so the merge agent stops early.

**A task has one flight, so one action a pass.** `seon.reconcile/actions` answers the first action for a task in root's order; the next waits for the pass its write moves main to. A task reaped or killed starts on the next pass, once `:lost` or `:killed` is written; two actions for one task would let the first's answer end the second's flight (`seon.reconcile-test/a-previous-roots-work-is-reaped-and-resumed`).
