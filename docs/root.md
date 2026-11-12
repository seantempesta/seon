# Root

Root is the control process: it opens the one store, forks the fork server, runs one Flow graph of procs on its main, and from there reconciles the tasks on main into agents, judgements and merges (`seon.system`, `seon.reconcile`).

## The root graph

Root's procs are declared as data in `resources/seon/system.edn` and started all at once as one Flow graph; there is no start order, because every proc parks on its inputs until upstream sends.

- `:api` (```seon.system/api-proc```): where requests come in; routes every request and answer as data.
- `:writer` and `:flusher` (`seon.writer/writer-proc`, `seon.writer/flusher-proc`): the only transact, branch and merge caller, and the store's sync ([storage](storage.md)).
- `:fork-server` (`seon.fork-server/fork-server-proc`): the fork server's pipe and events ([processes](processes.md#the-fork-server)).
- `:reconciler` (`seon.reconcile/reconciler-proc`): main's commits turned into actions ([judging](#runs-and-judging)).
- `:prove` (`seon.prove/prove-proc`): the test service, each (test, digest) run once for every asker, admitted by ```seon.prove/admission``` ([merging](merging.md#the-test-service)).
- `:cron` (`seon.cron/cron-proc`): the daily full run's one schedule ([clocks](clocks.md#if-nothing-has-changed-we-do-nothing)).
- `:vectors` (`seon.vector/vectors-proc`): main's missing text vectors ([vectors](vector.md)).
- `:render` and `:web` (`seon.render.proc/render-proc`, `seon.web/web-proc`): the dashboard ([dashboard](dashboard.md)).

The file's `:fx` map declares the effect kinds, each built into an owner proc ``:fx/<kind>`` and its workers ([flow](flow.md#effects-one-owner-and-its-workers-per-kind)). Each agent adds one `seon.agent-proc/agent-proc`, with `flow/update!` ([processes](processes.md#the-agent-process)).

```seon.graph/build``` turns the declaration into procs and conns, filling each ``:bind/<name>`` from boot's values. ```seon.system/apply!``` re-reads the file while root runs and rebuilds only the procs whose declaration changed (`bin/check system`).

## Boot

Boot opens the store, forks the fork server while root has one thread, starts the graph, and writes one boot fact on main whose commit is the reconciler's first pass: ```seon.system/start!```.

In order: the store is opened and main resumed, reading no file and no git (`seon.db/open!`; an empty main is seeded first, [source](source.md#a-boot-resumes-from-the-db)); main's config held and its program digested; core contracts instrumented; agents' code paths warmed (`seon.system/warm!`) so children inherit them compiled; the fork server forked ([failure points](failure-points.md#physics-who-may-run-threads)); GC sweepers set to `:seon.config.root/gc-sweepers`; the graph started. One write then holds the boot fact (`seon.task/boot-tx`), the config in force (`seon.task/config-tx`, [config](config.md#facts-on-main)), the program the binary was built from (```seon.system/booted-source```, [source](source.md#the-binary-records-its-program)) and how the previous root ended (```seon.system/previous-end```, `:unclean` without a stop fact). A refused boot fact ends the boot. Tasks of an earlier boot still `:running` are reaped as `:lost` by the first pass.

`seon.system/stop!` writes `:seon.system/stopped` (`{:boot :cause :at-ms}`) and drains the graph. It ends the fork server's process group at once (```seon.fork-server/release!```), kills and awaits every child not yet reaped, and answers how the server ended (`:drained :ended`, `:group-ended`). `bin/system start` runs a root detached on the checkout's ports; `bin/system next` starts the next generation's binary on the same store ([source](source.md#the-next-generation)).

## Runs and judging

The reconciler starts an agent for each ready task and judges the agent's end from measured facts only; what it decides is stored on the task ([reconcile](reconcile.md#a-task-holds-how-its-work-ended)).

```seon.reconcile/actions``` derives what root does next from the index (`seon.reconcile/index`, `seon.reconcile/observe`), in order: reap, kill, close, gate, an octopus merge of ready merge tasks, then starts (a spawn, or a wake of a parked agent) up to `:seon.config.agent/max-running` (merges up to `:seon.config.merge/max-running`). A pass costs what changed plus what is in flight, never the number of tasks.

A merge the writer refuses as behind main stands `:behind` and its task starts again, up to `:seon.config.merge/max-behind` times, then is held. A task judged while its branch is behind gets the done rule's `:behind` reason. An end its turn loop never wrote is a dead agent ([turn](turn.md#the-end)).

The judge asks the agent's REPL for its proofs' results, reads the branch once (the writer's `:branch-diff`), stops the agent unless it parks, and writes the decision with `seon.reconcile/judge-tx`: outcome, reasons, results, contract gaps, unmerged keys, and the task content carried to main. `seon.reconcile/done-explain` decides ([tasks](tasks.md#the-done-rule)); a refused or dead task is held (```seon.reconcile/hold-tx```, [tasks](tasks.md#a-refused-run-is-held-for-root)). An outside agent's `seon_done` enters the same judge ([server](server.md#ops)).

## The reconciler's index

The reconciler reads main through an index of its tasks, agents and root, kept current by each commit's datoms: [reconcile](reconcile.md#the-reconcilers-index).

## Root's functions

A caller outside root's procs (the server, a test, the dashboard) reaches root through functions that put a request on the api's `:ext` channel and answer a promise, never a wait inside a step.

```seon.system/call!``` is the one entry; after stop it answers `:seon.system/no-agent`. ```seon.system/request``` is the one table of ops shared with the dashboard. See each var's docstring:

- agents: ```seon.system/spawn!```, ```seon.system/clone!```, ```seon.system/submit!```, ```seon.system/message!```, ```seon.system/opened!```, ```seon.system/interrupt!```, ```seon.system/kill!```;
- tasks and branches: ```seon.system/release!```, ```seon.system/run-tests!```, ```seon.system/merge-main!```, ```seon.system/judge!```; a branch reaches main only through a merge task;
- the store: ```seon.system/transact-main!``` (`{:flush? true}` answers after the durable flush), ```seon.system/effect!```, ```seon.system/store-clean!```, ```seon.system/branch-db```, ```seon.system/branch-diff```, ```seon.system/union``` and ```my.root/union``` (every branch's history as one db, [storage](storage.md));
- what it shows: ```seon.system/state```, ```seon.system/view-numbers```, ```seon.system/errors```, ```seon.system/events```, ```seon.system/gone-statuses``` (a dead agent's last `:seon.config.root/death-stderr-bytes` of stderr is kept with its death).

## Holders and refused spawns

A branch has at most one live agent, and root refuses a spawn past `:seon.config.root/max-agents` live agents: both are refused spawns the api answers, recorded on the task.

The reconciler writes a refusal on the task (```seon.task/refused-tx```, outcome `:refused`) and asks again only once main moves. A holder whose own task is already judged is stopped, and its stop asks the refused start again (```seon.reconcile-test/a-refused-start-is-asked-again-when-its-holder-ends```); a holder still working is never stopped for another. The api refuses `:collect` while any agent is live ([storage](storage.md#collection)).

`:spawn` is answered `{:id}` at the agent's hello, so what is sent next reaches a ready agent. An agent that ends first answers that end instead (`:seon.agent/died`, `:seon.agent/failed`, `:seon.system/ended`), exactly once, and the reconciler takes it as a death, not a refusal.

## Root causes at quiescence

Why released work is not done is observed, never stored on the task: ```seon.reconcile/root-causes``` reads it from the index once, at quiescence.

A cause is: a task held with a failure; a task root refused to start; a task whose agent parked its session waiting for a message; a merged task whose write-back failed; or an unreleased task that released work needs, with what it blocks (```seon.reconcile/blocks```, `seon.reconcile/blocked-by`). A session parked for its person that blocks nothing is at rest. When nothing is in flight or live after a pass that started nothing, the reconciler writes `:seon.system/quiescent` once per quiet spell (`seon.reconcile/quiescent`) and asks the writer to collect. `my.root/problems` reports held tasks with the other problems ([errors](errors.md#problems)).

A wait on a fact the reconciler's work makes ends at its quiet: `seon.system/await-db`, named, asks the reconciler for its first quiet at or after main's head; if the pred is still false then, it answers `:seon.system/quiet-before-true` (`seon.reconcile/answer-waits`). A wait on another proc's facts is unnamed and ends only on a commit.
