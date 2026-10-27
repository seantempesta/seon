# Processes

Every agent is its own OS process, forked from one single-threaded fork server that holds main's db value, so it starts in milliseconds sharing the compiled program copy-on-write, and its crashes and memory are its own.

Root never forks after boot: it is multithreaded, and a process that forks must never have started Chez's parallel-collector sweeper threads ([failure points](failure-points.md#physics-who-may-run-threads)). So the fork server, each agent and each agent's test children stay single-threaded, and only root runs threads and Flow.

## The fork server

The fork server is one thread that holds main's db value, takes every main commit in order (read from the store as the writer made it, never replayed from its datoms), and forks an agent on request: `seon.fork-server`.

```seon.fork-server/fork-server``` forks it at boot while root has one active thread. Its loop, ```seon.fork-server/main```, runs its step-fn ```seon.fork-server/server-step``` under `seon.step/run-loop`, the same step-fn contract as root's procs. Commits (`:tx`) and fork requests (`:fork`) arrive on one FIFO pipe from the writer, so an agent forked after commit k sees exactly k: the writer created its branch at that point. A commit that changes main's program is loaded here (`seon.program/load-changes!`), so agents forked from then on inherit it compiled. Main's reach graph is built when the server starts and updated by the commits since at each fork, and handed to the agent (`seon.program/reach-graph`); so is main's reconciler index, listed once when the server starts and observed forward at each fork (```seon.fork-server/index-at-fork```), so an agent's first turn observes its branch from main's index (2M ticks on 908 tasks) and never lists it (33-166M). Each agent works in the scratch directory `:seon.config.agent/cwd`, with no repository above it. Each agent's socket arrives with its fork request (SCM_RIGHTS); the server waits on its socket and its children's exits together (one kqueue, no timeout: `seon.lib/kq-wait`), reaps each child that exits and reports it.

The boot server makes a process group every agent, test child and clone inherits; EOF on its socket to root is root's death, and it kills the group ([failure points](failure-points.md)). Root's side is ```seon.fork-server/fork-server-proc```, the one writer of the command pipe: it keeps a standby (a fork of the server, idle until promoted) and promotes it when the server dies, so no fork ever comes from root.

## What a fork inherits

A forked process gets the parent's compiled program and db values copy-on-write, and every rule of fork, LMDB and Chez that follows from that is already handled in `src`, so a process that holds a commit's program (a test run included) can fork from it like an agent does.
- **The program and db values** are copied copy-on-write (on macOS arm64 the JIT pages are first moved out of `MAP_JIT` by a static collect, Chez patches 0006 and 0007). Compiled code cannot be stored (Jolt units are 15 times their source, hold absolute paths and are valid under one runtime: [storage](storage.md)), so a fork has exactly its parent's program, and any other commit's is that plus the forms it differs by (```seon.program/load-changes!```, 1.6-5 ms a form; `d/commit-as-db` 0.7-0.9 ms; the reach graph by ```seon.program/reach-update```, 20-45 ms).
- **The LMDB env and Datahike connections** must not be used across a fork: ```seon.db/after-fork!``` forgets them, and the child connects again. A storeless parent forks the same way, and the in-memory test store is copied as a value.
- **Threads and fibers**: only the calling thread survives (```seon.lib/fork!``` refuses with more than one active, and `jolt-after-fork-child!` repairs the host's books), and a process that ever started Chez's parallel sweepers never forks, which is why root cannot be the forker ([failure points](failure-points.md)).
- **The fork costs** about 0.8 ms, and an agent about 5 MB of its own beside the parent's shared pages.

What the tests still need beyond this is a program per git tree, not a fork: ```seon.proofs``` keeps one durable store per checkout holding a branch per tree id, brought current by `seon.source/refresh-tx` ([source](source.md#tests-run-by-reach)). Only a per-process, in-memory store has no node addresses, so a diff between two values there shares nothing.

## A stop reaps every child

Stopping the fork server ends every process it forked before it exits, so root's `waitpid` of the server is also the end of each agent and standby (```seon.fork-server/server-step``` on `::flow/stop` kills and reaps its `:children`).
An agent's socket EOF is not its exit (`_exit` closes the descriptors before the process is a zombie), so a server killed on that EOF handed an exiting agent to launchd, which `bin/check lifecycle` read as `:left` (14 of 1000 stops under four busy processes, 0 of 1000 once the server reaps first). A promoted server is not root's child, so its end is unobservable to root.

Open, in Jolt: a joined thread is still counted by the kernel (`task_threads`) for a moment, in 164 of 200 trials, because Chez creates threads detached and on macOS even `pthread_join` returns before the kernel removes the thread. Only a Mach dead-name notification on the thread's port follows the removal (0 of 2000 still counted), so a `Thread.join` that awaited it would end the `:os` term of `bin/check lifecycle`'s thread comparison; until then that term can flake under load, and the named roster is what a stop can promise.

## The agent process

An agent is a forked process holding its branch's db value and running its REPL over one socket to root, single-threaded: `seon.agent`, `seon.repl`.

The child runs `seon.repl/run!`: ```seon.agent/init!``` makes the process the agent (its socket, a namespace of its own, its state, its interrupt handlers, its stderr file, the store opened for its own branch's writes, the model's API key removed from its environment, its collect trip and its heap cap from `:seon.config.agent/trip-bytes` and `:seon.config.agent/heap-extra-bytes`; a kept branch resumed takes its task from main), then it says hello and serves. ```seon.repl/agent-step``` is the agent as a step-fn: an `:eval` request runs through ```seon.repl/eval-string``` (read, evaluate, record, one reply item per form), and Seon's own ops (`:tests`, `:stats`, `:clone`, `:merge-main`, `:message`, `:fx-reply`) through `seon.agent/handle!`. A defect no request's boundary caught ends the process at `seon.repl/serve!` with exit status 70 and its cause written as the run's end (`seon.agent/died!`).

The REPL speaks nREPL's message model: a handler with middleware composed over it (```seon.repl/middleware```: the one printer, the one error map, per-form cost, the compiler's account recorded on the branch, the branch's schema registry, contract instrumentation). The agent writes its own branch (`seon.agent/transact!`); root reads its commits through the writer ([storage](storage.md)). What an agent changes becomes program through that recording and through staging the live runtime (```seon.stage/stage!```: a var unmapped or a method removed, which only the runtime knows), so nothing it evaluated is evaluated again ([source](source.md#an-agent-changes-code-at-its-repl)).

Its tests run in a fork of itself (`seon.agent/run-tests-forked`), each test bounded by `:seon.config.agent/test-ticks` of work of its own (one bound in a turn, the judge and bin/test: `seon.proofs/run-tests!`), never time; when every selected test is green at its digest nothing is forked; a process test runs there only when its namespace is named or it is one of the proofs; a fork refused because a thread is active answers `:seon.test/fork-refused` ([turn](turn.md#who-runs-which-tests)).

## A parked process

An agent whose run ends waiting or out of turns keeps its process, parked, and the next message to its session wakes that same process for its next run: `seon.reconcile` (DECISIONS, Every agent parks).

At the run's end (`seon.reconcile`'s `:diffed` step) a work task's run on a park outcome (`seon.reconcile/park-outcomes`: a reply with no eval, or its turn budget spent) keeps its process, and root's `:park` action writes the run `:parked`, once; any other end is judged as before, and an error holds the task with its cause. A turn takes the entries main holds to its session's runs onto the branch ([turn](turn.md#what-a-turn-takes-from-main)); at the run's end the judge counts those (`seon.reconcile/taken-messages`, the run's `:seon.run/taken`), and root's index counts the entries main holds to the branch's runs (`seon.reconcile/branch-messages`). A run the done rule refuses parks too: its `:verdict` entry is one main holds and the run did not take, so it wakes the session; that run's process ended, so its next run is a `:spawn`. When main holds more than the parked run took, the task is ready, and its next run is a `:wake` on the parked process when that process is of this boot, else a `:spawn` that loads the session. Quiescence names a parked run that blocks released work as a root cause (`seon.reconcile/root-causes`: "parked: cap, waiting for a message"). A cancel, a hold or a new branch for the task stops the parked process; a merge task never parks.

A parked process is one thread at 0% CPU, and root holds it as a fiber parked on the agent proc's in-port, so memory is what a parked agent costs. Measured once, with a scripted model, in the flow-lab of 2026-10-25 (its record: claude_scratch/archive/research-2026-10-25/flow-lab.md): a parked agent's footprint is 47-55 MB fresh, about 135 MB resumed and about 300 MB after real turns, and it does not shrink (Chez keeps its heap); a message wakes one in 7-35 ms, to its `work!` starting. So, by arithmetic, `:seon.config.machine/memory-mb` 4096 less root (1.1-1.9 GB measured) and the fork server (0.3 GB) leaves room for some 50 fresh parked agents, 18 resumed, or 8 that have worked.

The flow-lab's wake hazards, each a proof:
1. A stop the parked run left must not end the next at once with its message untaken: `begin!` starts each run's turn state anew (`seon.agent-test/a-waiting-run-parks-and-a-message-wakes-the-same-process`: the wake's run takes the message and runs turns of its own).
2. A re-entered loop must not number its turns over the recorded ones: a turn's id is its run's id and its number, and each wake is a run of its own (the same test: each run's turns are numbered from its own 0).
3. A message must wake a parked session whatever turn of the run before it came in: main counts every message to the branch's runs, so a run that parked having taken fewer is woken (`seon.reconcile-test/a-waiting-run-parks-and-a-message-wakes-it`).
4. Two wakes must never run two `work!` forms in one process: a wake's run is running, so the session is not parked and more messages start nothing more (the same test).
5. The judge must not end a parked session: a park outcome parks the run, never holds it (the same test).
6. A wake whose parked process died before it reached it took nothing: the run is `:lost`, not a death, and the next run is a spawn (`seon.reconcile-test/a-wake-whose-parked-process-is-gone-is-lost-and-the-next-run-a-spawn`).

A replay that resumes a session must not trust a form whose effect is visible only through a helper, a form that reads a world that has moved on (the time, files, main), or a handle to a resource that died with the process.

## A stalled agent

An agent with a request in flight that sends root nothing past the stall bound is observed stalled, once per quiet spell: a recorded fact on the agent, never an end (`seon.agent-proc`'s stall check; the bound is `:seon.config.agent/stall-ms`, a clock named in [clocks](clocks.md)).

Root writes it as the agent's `:seon.agent/stall`, with what it waits on: the request root last sent it, its kind and form, and for a test run the test that started last (each test's start reaches root as a `:testing` frame from `seon.agent/run-tests-forked`). While the fact holds it is an open `:stalled` problem naming the agent and what it waits on (`seon.problems/stalled-agents`, under the agents area of root's page and `my.root/problems`), and it is in every ctx that holds the agent. The agent's next frame retracts it (`:spoke`), and so does its end.

A stall is not pushed to the owner: it becomes a task for root to deal with. Its first write in a live run writes one task per agent and run from a template (`seon.template/stall`, called by `seon.root/stall-task`): `root/stall-<agent>-<run>`, held, its model `"outside"`, so the reconciler forks nothing for it and a Claude session works it through `seon_attach`. Its evidence is captured as the stall is recorded, while the process is stuck (the `:evidence` effect, `seon.fx/stall-evidence`: its fiber dump by SIGINFO, `sample` for 5 s, `lsof`, `ps` of its process group, files under the runs directory's `stalls/<task>`), and their paths are written into its `:seon.task/cause` `:evidence`, which is pre-filled with the stall: a restart that takes the process leaves the evidence. Its plan says what to capture by hand when that failed, and its proofs are its boundary conditions (`seon.template/stall-proofs`, in `seon.stall-proofs`): the stall ended, the cause is recorded with its evidence (`(my.task/update! :seon.task/cause merge {:what .. :evidence {..} :bug? ..})`), and a bug's defect task is filed and named. A test bin/test runs that goes quiet past its own bound (`:seon.config.proofs/stall-factor` times its last recorded run, floored at the same key) is named `STALLED <test> <ms>` in its output and its shard's log (`bin/stalls`), and is never ended.

## The socket and the agent proc

Root and an agent speak length-prefixed frames over one Unix socket, read and written on the agent's side by its one thread with no pumps: `seon.sock`.

A frame is a length word and a jfasl body; a descriptor can travel with a frame. The socket's buffer is the bounded channel: a full buffer makes the sender wait. Root's end is a `jolt.async.remote` end on fiber pumps, whose `:in` is the in-port of the agent's proc in root, ```seon.agent-proc/agent-proc```: one proc per agent, added and removed by the api with `flow/update!` ([root](root.md#the-root-graph)). It passes Flow's pause, resume and stop on to the agent's own loop; on stop the agent exits after the requests before it, and past `seon.agent-proc/stop-bound-ms` it is SIGKILLed.

## Interrupts

Root stops a running form out of band with SIGUSR1, and a form past its work budget stops itself: `seon.intr`.

Each eval runs under its own interrupt token (Jolt's `jolt.host/run-interruptible`); the signal sets every token on the stack, so the whole request stops, nested evals included, at a safe point the form's own `try` cannot swallow. A form past its tick budget (procedure calls and loop back-edges, never elapsed time; an outside or UI eval takes `:seon.config.agent/form-ticks`, `seon.root/submit!`) answers `:seon.repl/ticks-spent`, and the request goes on ([failure points](failure-points.md)).
