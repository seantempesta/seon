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
| `:prove` | `seon.prove/prove-proc` | the test service's admission: one run at a time per checkout, whoever asks ([merging](merging.md#the-test-service)) |
| `:vectors` | `seon.vector/vectors-proc` | main's missing text vectors: sends due texts to `:embed`, writes their vectors through the writer ([vectors](vector.md)) |
| `:render` | `seon.render.proc/render-proc` | the dashboard's views ([dashboard](dashboard.md)) |
| `:web` | `seon.web/web-proc` | the HTTP listener and the tabs ([dashboard](dashboard.md)) |

The file's `:fx` map declares the effect kinds (`:echo`, `:file`, `:source`, `:git`, `:deps`, `:build`, `:tests`, `:history`, `:embed`, `:model`), each built into an owner proc ``:fx/<kind>`` and its workers. Each agent adds one more proc, `seon.agent-proc/agent-proc`, added and removed with `flow/update!` ([processes](processes.md#the-agent-process)).

```seon.system/build``` turns the declaration into procs and conns, filling each ``:bind/<name>`` keyword from the values boot holds; `seon.system/read-decl` reads the file. ```seon.root/apply!``` re-reads it while root runs and rebuilds only the procs whose declaration changed; an unchanged proc keeps its queue (`bin/check system` proves both). The proc contract they all follow: [flow](flow.md).

## Boot

Boot opens the store, forks the fork server while root has one thread, starts the graph, and writes one boot fact on main whose commit is the reconciler's first pass: ```seon.root/start!```.

In order: the store is opened and main resumed (`seon.db/open!`); the program loaded (`seon.program/load-all!`) and the contracts of the core namespaces instrumented; the code paths agents take are run once (`seon.root/warm!`), so forked children inherit them compiled; the fork server is forked (the physics rule: [failure points](failure-points.md#physics-who-may-run-threads)); the GC's sweeper threads are set to `:seon.config.root/gc-sweepers`; the graph starts. Then one write holds the boot fact (`seon.task/boot-tx`: a new boot id and `:seon.config.agent/max-running`), the config in force (`seon.config/facts`, [config](config.md#facts-on-main)), the wiki (```seon.root/wiki-tx```, with the git-derived baseline of `seon.root/wiki-baseline` for sections with no read record, [health](health.md#a-stale-section)) and how the previous root on this main ended (```seon.root/previous-end```: its stop fact, or `:unclean` when it wrote none). A refused boot fact ends the boot. Last, main's program follows Seon's own source (```seon.root/import-source!```, [source](source.md#import-the-running-generations-source-as-mains-program)).

Runs of an earlier boot still `:running` are reaped as `:lost` ("root restarted") by the reconciler's first pass.

`seon.root/stop!` drains the graph and first writes `:seon.root/stopped` (`{:boot :cause :at-ms}`), so every clean end says why. `bin/root start` runs a root detached on this checkout's ports; `bin/root next` stops it and starts the next generation's binary on the same store and main ([source](source.md#the-next-generation)).

## Runs and judging

A run is one agent's try at a task; the reconciler starts it, and judges its end from measured facts only.

```seon.reconcile/actions``` derives, from an index of main kept current by each commit's datoms (`seon.reconcile/index`, `seon.reconcile/observe`), what root does next, in order: reap, kill, judge, an octopus merge of the green merge runs, then spawns of ready tasks up to `:seon.config.agent/max-running` (merges up to `:seon.config.merge/max-running`). A pass costs what changed plus what is in flight, never the number of tasks.

A run's status (`:seon.run/status`) moves `:running` → `:ended` (or `:dead`, `:killed`, `:lost`) → `:done`, `:merged`, `:behind`, `:bailed`, `:closed`; `:refused` is a spawn root refused. The schema's properties name the finished, judged and failed sets, read through `seon.contract/properties` ([render](render.md)). Its outcome (`:seon.run/outcome`) is what its turn loop wrote: `:green`, `:red`, `:waiting`, `:cap`, `:failed`, `:merge-failed`, `:error`, or `:no-end` when the agent wrote none (it died, was killed, or never reached `seon.turn/end!`; an outside agent's `:done` writes it through `seon.turn/hand-back!`) ([turn](turn.md#one-run-from-release-to-judge)).

The judge asks the agent's REPL for its proofs' results (`:tests`), reads the branch once (the writer's `:branch-diff`: its head, its merge base and main's t there, and what it changed since), stops the agent, and writes the end with `seon.reconcile/judge-tx`: outcome, results, contract gaps, unmerged keys, the task content the branch changed carried to main. Then `seon.reconcile/done-explain` decides ([tasks](tasks.md#the-done-rule)): none, and the run is accepted (`:done`), or for a merge task, ready to merge. A refused or dead run holds its task for root with the failure and the measured evidence (```seon.reconcile/hold-tx```), and nothing retries it on its own ([tasks](tasks.md#a-refused-run-is-held-for-root)). An outside agent's `seon_done` enters the same judge ([server](server.md#ops)).

## Holders and refused spawns

A branch has at most one live agent, and root refuses a spawn past `:seon.config.root/max-agents` live agents: both are refused spawns the api answers, recorded as the run.

The api's `:spawn` request names the branch's holder: a live agent whose branch it is. Then root answers `seon.root/spawn!`'s refused answer ("branch … is held by live agent …"); past the live-agent bound, "too many live agents" (`:clone` has the same bound). The reconciler writes the refused answer as the run itself, status `:refused` with root's reason, and asks again only once main moves for another reason: asking an unchanged main would be refused again. A collection waits the same way: the api refuses `:collect` while any agent is live ([storage](storage.md#collection)).

The api's `:spawn` is answered `{:id}` at its agent's hello (`:ready`), never at once, so what is sent to the agent next reaches a ready agent. An agent that ends first answers that end instead: its death (`:seon.agent/died`, with the exit status the fork server reaped), a failed spawn (`:seon.agent/failed`) or the stop it met (`:seon.root/ended`: root stopped it), each naming the agent, and exactly one answer is given. The reconciler takes such an answer as the run's death (its run row started, then the dead path), not as a refused spawn.

## Root causes at quiescence

Why released work is not done is observed, never stored on the task: ```seon.reconcile/root-causes``` reads it from the index once, at quiescence.

A cause is one of three: a task held for root with a failure (a run refused, its agent dead, its merge refused, or marked failed: its reason); a task whose latest run root refused to spawn (its reason: the branch is held, or too many live agents); or a task not released (cancelled, held) that released work needs, with the released tasks it blocks (```seon.reconcile/blocks```). Blocked is a task's derived state when a need is not done (`seon.reconcile/blocked-by`, `seon.reconcile/task-state`: [tasks](tasks.md#where-a-tasks-state-lives)). When nothing is in flight and no run is live after a pass that started nothing, the reconciler writes `:seon.root/quiescent` once per quiet spell (the t judged and the root causes; read by `seon.reconcile/quiescent`) and asks the writer to collect the store. `my.root/problems` (which reads `seon.problems/health` with the api's fold of recorded errors) reports held tasks among the other problems ([errors](errors.md#problems)).

A wait on a fact the reconciler's work makes ends at its quiet, too. `seon.root/await-db`, named, asks the reconciler on its `:judge` channel (behind any judge handed it first) for its first quiet at or after main's head as of the ask; the reconciler answers it from its own state (`seon.reconcile/answer-waits`: quiet, nothing in flight, nothing live), and a wait whose pred is still false then answers `:seon.root/quiet-before-true`, since nothing in flight will make it true. `seon_done` takes that as final; a caller waiting on outside input asks again. The reconciler holds each wait's promise across steps, the one proc state that keeps an edge's promise past the step that took it, because a wait outlives that step; a stop answers what it holds, and root's stop answers any wait still on the channel. A wait on another proc's facts (embeddings, a problems report) is unnamed: the reconciler's quiet says nothing of that proc's work, so only a commit ends it (task `seon.root/a-wait-on-main-ends-when-nothing-can-make-it-true`).
