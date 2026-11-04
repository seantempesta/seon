# Root

Root is the control process: it opens the one store, forks the fork server, runs one Flow graph of procs on its main, and from there reconciles the tasks on main into agents, judgements and merges (`seon.root`, `seon.reconcile`).

## The root graph

Root's procs are declared as data in `resources/seon/system.edn` and started all at once as one Flow graph; there is no start order, because every proc parks on its inputs until upstream sends.

| Proc | Step-fn | What it owns |
|---|---|---|
| `:api` | ```seon.root/api-proc``` | where requests come in: callers' reply promises, the agents' procs, root's view; it routes every request and answer as data |
| `:writer` | `seon.writer/writer-proc` | the only transact, branch and merge caller, with group commit ([storage](storage.md)) |
| `:flusher` | `seon.writer/flusher-proc` | the store's sync ([storage](storage.md)) |
| `:fork-server` | `seon.fork-server/fork-server-proc` | the fork server's command pipe, its events, its standby ([processes](processes.md#the-fork-server)) |
| `:reconciler` | `seon.reconcile/reconciler-proc` | main's commits, turned into actions ([runs](#runs-and-judging)) |
| `:prove` | `seon.prove/prove-proc` | the test service's admission: one run at a time per checkout, whoever asks, and its state told to root for a stall's evidence ([merging](merging.md#the-test-service)) |
| `:vectors` | `seon.vector/vectors-proc` | main's missing text vectors: sends due texts to `:embed`, writes their vectors through the writer ([vectors](vector.md)) |
| `:render` | `seon.render.proc/render-proc` | the dashboard's views ([dashboard](dashboard.md)) |
| `:web` | `seon.web/web-proc` | the HTTP listener and the tabs ([dashboard](dashboard.md)) |

The file's `:fx` map declares the effect kinds (`:echo`, `:file`, `:source`, `:git`, `:deps`, `:build`, `:tests`, `:history`, `:embed`, `:model`), each built into an owner proc ``:fx/<kind>`` and its workers. Each agent adds one more proc, `seon.agent-proc/agent-proc`, added and removed with `flow/update!` ([processes](processes.md#the-agent-process)).

```seon.system/build``` turns the declaration into procs and conns, filling each ``:bind/<name>`` keyword from the values boot holds; `seon.system/read-decl` reads the file. ```seon.root/apply!``` re-reads it while root runs and rebuilds only the procs whose declaration changed; an unchanged proc keeps its queue (`bin/check system` proves both). The proc contract they all follow: [flow](flow.md).

## Boot

Boot opens the store, forks the fork server while root has one thread, starts the graph, and writes one boot fact on main whose commit is the reconciler's first pass: ```seon.root/start!```.

In order: the store is opened and main resumed (`seon.db/open!`); the program loaded (`seon.program/load-all!`) and the contracts of the core namespaces instrumented; the code paths agents take are run once (`seon.root/warm!`), so forked children inherit them compiled; the fork server is forked (the physics rule: [failure points](failure-points.md#physics-who-may-run-threads)); the GC's sweeper threads are set to `:seon.config.root/gc-sweepers`; the graph starts. Then one write holds the boot fact (`seon.task/boot-tx`: a new boot id, `:seon.config.agent/max-running` and, when a test started this root, its name as `:seon.root/started-by`), the config in force (`seon.config/facts`, [config](config.md#facts-on-main)), the wiki (```seon.root/wiki-boot-tx```: ```seon.root/wiki-tx``` with the git-derived baseline of `seon.root/wiki-baseline` for sections with no read record, [health](health.md#a-stale-section), and nothing when main holds the digest of what it reads, [references](references.md#the-wiki-in-the-store)) and how the previous root on this main ended (```seon.root/previous-end```: its stop fact, or `:unclean` when it wrote none). A refused boot fact ends the boot. Last, main's program follows Seon's own source (```seon.root/import-source!```, [source](source.md#import-the-running-generations-source-as-mains-program)).

Runs of an earlier boot still `:running` are reaped as `:lost` ("root restarted") by the reconciler's first pass.

`seon.root/stop!` drains the graph and first writes `:seon.root/stopped` (`{:boot :cause :at-ms}`), so every clean end says why. `bin/root start` runs a root detached on this checkout's ports; `bin/root next` stops it and starts the next generation's binary on the same store and main ([source](source.md#the-next-generation)).

## Runs and judging

A run is one agent's try at a task; the reconciler starts it, and judges its end from measured facts only.

**Being replaced:** runs go: the reconciler reads a task's attributes and its current agent, and a task's earlier states are history. See [the Seon way](seon-way.md#what-needs-to-change); do not build on it.

```seon.reconcile/actions``` derives, from an index of main kept current by each commit's datoms (`seon.reconcile/index`, `seon.reconcile/observe`), what root does next, in order: reap, kill, judge, an octopus merge of the green merge runs, then spawns of ready tasks up to `:seon.config.agent/max-running` (merges up to `:seon.config.merge/max-running`). A pass costs what changed plus what is in flight, never the number of tasks.

A run's status (`:seon.run/status`) moves `:running` → `:ended` (or `:dead`, `:killed`, `:lost`) → `:done`, `:merged`, `:behind`, `:bailed`, `:closed`; `:refused` is a spawn root refused. The schema's properties name the finished, judged and failed sets, read through `seon.contract/properties` ([render](render.md)). Its outcome (`:seon.run/outcome`) is what its turn loop wrote: `:green`, `:red`, `:waiting`, `:cap`, `:failed`, `:merge-failed`, `:error`, or `:no-end` when the agent wrote none (it died, was killed, or never reached `seon.turn/end!`; an outside agent's `:done` writes it through `seon.turn/hand-back!`) ([turn](turn.md#one-run-from-release-to-judge)). A merge the writer refuses as behind main (main's program moved since the branch's merge base) ends `:behind` and its task runs again, up to `:seon.config.merge/max-behind` such runs; the next holds the task for root with the writer's evidence (`seon.reconcile`'s `:octopus-merged`). A run judged while its branch is behind is told so first (the done rule's `:behind` reason, the keys main changed, recorded as `:seon.run/behind`): its proofs ran on a program main has moved from.

The judge asks the agent's REPL for its proofs' results (`:tests`), reads the branch once (the writer's `:branch-diff`: its head, its merge base and main's t there, and what it changed since), stops the agent, and writes the end with `seon.reconcile/judge-tx`: outcome, results, contract gaps, unmerged keys, the task content the branch changed carried to main. Then `seon.reconcile/done-explain` decides ([tasks](tasks.md#the-done-rule)): none, and the run is accepted (`:done`), or for a merge task, ready to merge. A refused or dead run holds its task for root with the failure and the measured evidence (```seon.reconcile/hold-tx```), and nothing retries it on its own ([tasks](tasks.md#a-refused-run-is-held-for-root)). An outside agent's `seon_done` enters the same judge ([server](server.md#ops)).

## The reconciler's index

The reconciler reads main through an index of its tasks, runs and root, kept current by each commit's datoms and listed only once: ```seon.reconcile/index```.

```seon.reconcile/empty-index``` is the index of a db with no tasks. ```seon.reconcile/observe``` takes the index and a commit's resolved datoms (`[op e a v]`) with the db after it, and re-reads only the entities the commit touched. ```seon.reconcile/index``` of a db lists it by observing every task, run and root entity at once; given an earlier index and its db, it observes only the watched attributes' datoms that differ (`datoms-diff`, which skips the stored subtrees both share), so the cost is the watched datoms and what changed, never the program's. The fork server hands an agent main's index the same way ([processes](processes.md#the-fork-server)).

What is asked of it: ```seon.reconcile/done?``` (a task merged into its target, or accepted on its branch), and the quiet and write-back facts `seon.reconcile/quiescent` (below) and ```seon.reconcile/last-commit``` (the merges commit of the last merged run, the write-back's sha). ```seon.reconcile/pull-end``` reads the end a run's turn loop wrote on its branch (`{:outcome :reason :turns :tokens}` and the error that ended it), nil when it wrote none: the run is then `:no-end`. ```seon.reconcile/hairy``` decides from measured facts that a merge is too hairy to keep resolving (more conflicts than the config key, a var main deleted that the proofs or the plan's cites name, more turns without green than the config key), so the merge agent stops early and the reconciler needs no more than the run's facts.

## Root's functions

A caller outside root's procs (the server, a test, the dashboard) reaches root through functions that put a request on the api's `:ext` channel and answer a promise, never a wait inside a step.

```seon.root/call!``` puts a request with a fresh reply promise on the api; after `seon.root/stop!` the api is closed and the promise answers `:seon.root/no-agent` ("root stopped"). ```seon.root/request``` is the one table of ops the agent functions below share with the dashboard's calls (`seon.web`): ```seon.root/spawn!``` (forks an agent onto a new branch of main, answering its id at its hello, or throwing the end it met first), ```seon.root/clone!``` (a live agent forked onto a branch of its branch), ```seon.root/submit!``` (forms to an agent; a reply per form, each bounded by ticks), ```seon.root/message!``` (the owner's words, root's one delivery, also a fact on main), ```seon.root/release!``` ([my.root/run!](tasks.md#releasing-and-cancelling)'s one transaction), ```seon.root/run-tests!``` (the branch proved by the test service as the judge proves it, recorded on main), ```seon.root/merge-main!``` (git merge main on the agent's branch; conflicts stay as unmerged entries), ```seon.root/opened!``` (a reader opened an eval of the agent's page: a fact on main, taken onto the branch by the agent's next turn), ```seon.root/interrupt!``` (SIGUSR1, run by Chez at the agent's next safe point) and ```seon.root/kill!``` (SIGKILL). A branch reaches main only through a merge task.

Root's own: ```seon.root/transact-main!``` (a write to main; with `{:flush? true}` it is answered after the group flush that makes it durable, for a write an outside effect follows), ```seon.root/effect!``` (an effect through its owner proc), ```seon.root/store-clean!``` (answered once every commit so far is synced), ```seon.root/branch-db``` and ```seon.root/branch-diff``` (the writer's db of a branch and the branch versus main, which the judge reads once) and ```seon.root/judge!``` (an outside agent's run handed to the reconciler for judgement as a turn loop's end is). ```seon.root/union``` (and ```my.root/union```, its db) is every branch's history as one db, built at the first ask and kept current by every commit, queried with plain `d/q` ([storage](storage.md)).

What it shows: ```seon.root/state``` is the current state as plain EDN from the api's view (with `{:datoms true}`, main's datom count too, which costs O(datoms)); ```seon.root/view-numbers``` is the one decoration of the view into the numbers shown (the writer's batch p50, transactions per group commit, the fork server's lag and fork p50s), read by `state` and the render proc alike; ```seon.root/errors``` is the api's fold of the errors it saw, including repeats of written signatures that main does not hold; ```seon.root/events``` is a fresh tap of every event. ```seon.root/gone-statuses``` are the agent statuses of a process that is gone, and the last `:seon.config.root/death-stderr-bytes` of a dead agent's stderr file (```seon.lib/tail-bytes```) is recorded with its death. ```seon.root/platform-tx``` records an error observed outside root's procs (boot, the file watcher, a source fact the store refused) as a `:platform` error by its signature ([errors](errors.md)).

## Root watches its loaders

Root watches the directories its loaders read, and loads what changes; the mechanism and its reasons are [health's](health.md#every-writer-root-loads-what-changed).

```my.root/watch!``` starts ```seon.watch/start!``` over the loader directories (the wiki, the task files, the source) and the repository's refs, and answers the watcher or the error. The watcher is an OS child over FSEvents and one edge thread; ```seon.watch/record``` reads each record it prints (a kind letter then the path: `:path` changed, `:rescan` an unreliable subtree, `:root` a watched root moved, `:ready` the stream started) and each batch goes to the batch handler. ```my.root/source-changes``` names the source files a batch changed when the batch made root load the source by files alone (nil when any record is a rescan, a moved root or the branch's own ref moving: the whole tree is read). A watcher that cannot start or ends without ```seon.watch/stop!``` is the `:seon.watch/died` error, recorded on main by ```my.root/watch-died-tx``` as a `:platform` error with the watch fact; a live watcher's next write closes it. Root goes on unwatched, never polling.

## Holders and refused spawns

A branch has at most one live agent, and root refuses a spawn past `:seon.config.root/max-agents` live agents: both are refused spawns the api answers, recorded as the run.

The api's `:spawn` request names the branch's holder: a live agent whose branch it is. Then root answers `seon.root/spawn!`'s refused answer ("branch … is held by live agent …"); past the live-agent bound, "too many live agents" (`:clone` has the same bound). The reconciler writes the refused answer as the run itself, status `:refused` with root's reason, and asks again only once main moves for another reason: asking an unchanged main would be refused again. A collection waits the same way: the api refuses `:collect` while any agent is live ([storage](storage.md#collection)).

The api's `:spawn` is answered `{:id}` at its agent's hello (`:ready`), never at once, so what is sent to the agent next reaches a ready agent. An agent that ends first answers that end instead: its death (`:seon.agent/died`, with the exit status the fork server reaped), a failed spawn (`:seon.agent/failed`) or the stop it met (`:seon.root/ended`: root stopped it), each naming the agent, and exactly one answer is given. The reconciler takes such an answer as the run's death (its run row started, then the dead path), not as a refused spawn.

## Root causes at quiescence

Why released work is not done is observed, never stored on the task: ```seon.reconcile/root-causes``` reads it from the index once, at quiescence.

A cause is one of these: a task held for root with a failure (a run refused, its agent dead, its merge refused, or marked failed: its reason); a task whose latest run root refused to spawn (its reason: the branch is held, or too many live agents); a task whose latest run parked its session (it waits for a message, which wakes it); a merged run whose write-back failed (no commit holds its files); or a task not released (cancelled, held) that released work needs, with the released tasks it blocks (```seon.reconcile/blocks```). Blocked is a task's derived state when a need is not done (`seon.reconcile/blocked-by`, `seon.reconcile/task-state`: [tasks](tasks.md#where-a-tasks-state-lives)). When nothing is in flight and no run is live after a pass that started nothing, the reconciler writes `:seon.root/quiescent` once per quiet spell (the t judged and the root causes; read by `seon.reconcile/quiescent`) and asks the writer to collect the store. `my.root/problems` (which reads `seon.problems/health` with the api's fold of recorded errors) reports held tasks among the other problems ([errors](errors.md#problems)).

A wait on a fact the reconciler's work makes ends at its quiet, too. `seon.root/await-db`, named, asks the reconciler on its `:judge` channel (behind any judge handed it first) for its first quiet at or after main's head as of the ask; the reconciler answers it from its own state (`seon.reconcile/answer-waits`: quiet, nothing in flight, nothing live), and a wait whose pred is still false then answers `:seon.root/quiet-before-true`, since nothing in flight will make it true. `seon_done` takes that as final; a caller waiting on outside input asks again. The reconciler holds each wait's promise across steps, the one proc state that keeps an edge's promise past the step that took it, because a wait outlives that step; a stop answers what it holds, and root's stop answers any wait still on the channel. A wait on another proc's facts (embeddings, a problems report) is unnamed: the reconciler's quiet says nothing of that proc's work, so only a commit ends it (task `seon.root/a-wait-on-main-ends-when-nothing-can-make-it-true`).
