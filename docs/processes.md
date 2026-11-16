# Processes

Every agent is its own OS process, forked from one single-threaded fork server that holds main's db value, so it starts in milliseconds sharing the compiled program copy-on-write, and its crashes and memory are its own.

Root never forks after boot: it is multithreaded, and a process that forks must never have started Chez's parallel-collector threads ([failure points](failure-points.md#physics-who-may-run-threads)). So the fork server, each agent and each agent's test children stay single-threaded; only root runs threads and Flow. An agent's eval is a fiber on its one thread, not a thread, because an agent forks from itself while an eval runs (its test children, a clone) and a fork keeps only the forking thread, leaving the child every lock another thread held. Keeping each fiber's state right across a pause is Jolt's to guarantee ([forks](forks.md#jolt)).

## The fork server

The fork server is one thread that holds main's db value, takes every main commit in order, and forks an agent on request: `seon.fork-server`.

```seon.fork-server/fork-server``` forks it at boot while root has one active thread. Its loop (```seon.fork-server/main```) runs the step-fn ```seon.fork-server/server-step``` under `seon.step/run-loop`. Commits (`:tx`) and fork requests (`:fork`) arrive on one FIFO pipe from the writer, so an agent forked after commit k sees exactly k. A commit that changes main's program is loaded here (`seon.program/load-diff!`), so later agents inherit it compiled. Main's reach graph (`seon.program/reach-graph`) and reconciler index (```seon.fork-server/index-at-fork```) are kept forward and handed to each agent, so its first turn never lists its branch. Each agent works in `:seon.config.agent/cwd`, with no repository above it. An agent's socket arrives with its fork request (SCM_RIGHTS); the server waits on its socket and its children's exits together (`jolt.io-poller/exit-chan`, no timeout) and reaps and reports each child that exits.

The boot server makes a process group every agent, test child and clone inherits; EOF on its socket to root is root's death, and it kills the group. Root's side, ```seon.fork-server/fork-server-proc```, is the one writer of the command pipe; no fork ever comes from root, so a server that dies is lost (```seon.fork-server/lost```): agents keep running and no agent is forked until a restart.

## What a fork inherits

A forked process gets its parent's compiled program and db values copy-on-write; every rule of fork, LMDB and Chez that follows is handled in `src`, so any process holding a commit's program can fork like an agent.
- **Program and db values** are shared copy-on-write. Compiled code cannot be stored ([storage](storage.md)), so another commit's program is this one plus the forms it differs by (```seon.program/load-diff!```, ```seon.program/reach-update```).
- **The LMDB env and Datahike connections** are not used across a fork: ```seon.db/after-fork!``` forgets them, and the child reconnects and remakes every db value it inherited by commit ([storage](storage.md#an-agents-reads-and-writes)).
- **Threads**: only the calling thread survives; ```seon.lib/fork!``` forks once its caller is the one active thread: Jolt's ``jolt.host/fork-alone``, the one way to fork, waits for the others to park (Chez's `fork_alone`, woken as each parks, never a clock), and answers -2 only when ``jolt.host/fork-alone-cancel!`` ends that wait.
- **Jolt's runtime** resets itself in the child before interrupts return (``jolt-after-fork-child!``) and starts no thread, so a child can fork again: every carrier, the timer and the signal watcher are gone and start afresh on first use; a program forked from its main carrier keeps carrier 0 and only the forking fiber, so a fiber parked in the parent never resumes in the child; pending timeouts are dropped; every lock and monitor is made afresh (one held at the fork is held by nobody); the poller's table starts empty; the signal mask is emptied (SIGTERM ends the child until the watcher is re-armed); the parent's shutdown hooks and delete-on-exit paths are not the child's; ``jolt.async.remote`` ends it inherited are closed (FD_CLOFORK, or the after-fork hook on Linux); and the hooks a library registered (``jolt.host/after-fork-add!``) run last, oldest first. A fiber pool made before the fork is not restarted.
- **Never rely across a fork on** a fiber, a timeout or a pool of the parent's, a lock's holder, or an inherited remote end. A child that is a proc of its parent's flow is made with ``jolt.async.remote.flow/fork!``: its end is opened in the child, and the parent adds it to the running flow by ``update!``.
- **Descriptors**: a child holds what it is given and nothing else (see below).
- **Cost**: a fork takes about 0.8 ms; an idle agent privately owns about 120 MB and an idle test child about 10 MB ([machine](machine.md#what-a-process-owns-in-memory)).

### A child holds what it is given

A forked child closes every stream descriptor (a socket, pipe or FIFO) but 0-2, its own `:fd` and the ones it is given to `:keep`, once, before its code runs (``jolt.async.remote/close-streams-but!``, the one rule, which ```seon.lib/fork!``` and ``jolt.async.remote.flow/fork!`` both call: ProcessBuilder's rule). Files stay: a native library keeps its own (the LMDB build's lock), which ```seon.db/after-fork!``` forgets rather than closes. So a pipe's write end is held by the child it was made for alone, and the pipe's end of file is that child's end: an agent's test runs hear each child's link end at its exit (```seon.agent/test-run-step```) however many run at once, and an agent, a clone or the fork server keeps none of its parent's sockets (```seon.lib-test/a-forked-child-holds-only-what-it-is-given```). An exec'd command holds neither end of a ```seon.sock/socketpair```, whose ends are FD_CLOEXEC.

Tests need a program per git tree, not a fork: ```seon.proofs``` keeps the repository's store, in generations, holding a branch per tree id ([source](source.md#tests-run-by-reach)).

## A step that throws

A throw out of the fork server's step changes nothing and answers what the step took: ```seon.fork-server/server-step``` keeps its state and sends `{:ev :step-failed :op :error}` (a `:seon.fork-server/step-failed` error naming the op), closing a fork's socket if the fork was not made. Root's ```seon.fork-server/fork-server-proc``` answers the asker (a waiter by its rid, a fork as `:fork-failed`) and records a platform problem, and the server serves the next request; a commit it could not read is caught up by the next one read whole (`d/commit-as-db`). Proofs: ```seon.procs-test/a-fork-server-step-that-throws-answers-and-the-server-serves-on```, ```seon.procs-test/a-fork-server-step-that-throws-answers-with-the-error-and-its-state-is-unchanged``` ([failure points](failure-points.md)).

## An agent that starts without its program

An agent forked from a server whose program partly failed to load lacks the same parts, and it runs, never silently (owner's ruling, 2026-11-11). The server holds its load errors by namespace (```seon.fork-server/stood```) and gives them to every agent it forks (```seon.fork-server/fork-args```' `:program-errors`); the agent adds them to its own (```seon.agent/init!```), and its hello makes each a `:seon.agent/load-failed` problem (```seon.agent-proc/agent-proc```). Root answers the spawn with a typed `:seon.agent/started-without-program` naming every error and writes it to the agent as a message from the system entity, its first fact, waking nothing (```seon.system/set-agent```). The message says whose each error is (```seon.system/started-without-program-text```): a namespace not the task's own or its tests' is the program's defect, recorded for its owner, and the agent goes on with its task; an agent told only the error spent its turns on another area's bug. The agent still runs, since it may be the one sent to fix the namespace (```seon.procs-test/an-agent-forked-from-a-program-that-did-not-load-is-told```).

## A stop reaps every child

Stopping the fork server ends every process it forked before it exits (```seon.fork-server/server-step``` on `::flow/stop` kills and reaps its `:children`). An agent's socket EOF is not its exit, so the server reaps rather than trusting EOF. Root's stop ends the boot server's whole process group at once (```seon.fork-server/release!```); since a server killed so reaps nothing, root's side keeps every pid the server reported forked until its `:exit` and `release!` kills each and awaits its exit by the kernel (`jolt.io-poller/exit-chan`). `bin/check lifecycle` reads what a stop left; a zombie whose parent is not the root under test counts as ended.

Open, in Jolt: on macOS a joined thread is still counted by the kernel for a moment, so `bin/check lifecycle`'s `:os` thread term can flake under load until `Thread.join` awaits the Mach dead-name notification.

## The agent process

An agent is a forked process holding its branch's db value and running its REPL over one socket to root, single-threaded: `seon.agent`, `seon.repl`.

The child runs `seon.repl/run!`: ```seon.agent/init!``` makes the process the agent (its socket, namespace, state, interrupt handlers, stderr file, its branch's store, the model's API key removed from its environment, its heap cap from `:seon.config.agent/trip-bytes` and `:seon.config.agent/heap-extra-bytes`), then it says hello and serves as a Flow graph of three `:io` procs on its one thread (```seon.repl/serve!```, [flow](flow.md#flow-inside-an-agent)):
- ```seon.repl/transport-step```, the one reader of root's socket, routes each frame;
- ```seon.repl/agent-step```, the REPL, runs requests one at a time in arrival order: `:eval` through ```seon.repl/eval-string``` (read, evaluate, record: [failure points](failure-points.md#entrant-boundaries-a-defect-becomes-a-recorded-error-map)), `:render`, and `:judge`, `:merge-main` and `:clone` through `seon.agent/handle!`, then `:stop`;
- ```seon.repl/tests-step``` serves root's `:tests` on its own fiber, never charged to an eval waiting on it.

What the process is given once and the store does not hold is its agent value (`:seon.agent/start`: its key, its role, its store connection and config, the loop a clone runs, a promise of its graph): `init!` answers it, `serve!` gives it to each proc as the flow def's `:args`, each proc's init keeps it in its state, and a function that needs it takes it first. What the store records of the agent (its entity, by its key: its branch, its task) is read there (```seon.agent/task-of```), never kept beside it. No var holds it, so a namespace reloaded at a running agent changes no agent's identity. An eval is given it too (```seon.repl/eval-as```, [the REPL](agent.md#the-repl)), and so is a test's agent, the one way a test makes one (```my.test/eval-in```).

A wait inside an eval for root's answer (a prove, an effect, a clone's descriptor) is a promise the transport delivers by the request's `:k` (```seon.agent/await-reply```), so no wait reads the socket. A defect no request's boundary caught (the agent redefined a function its procs call) is answered as an error (```seon.repl/defect-answer```) and the process goes on, so the agent can repair it.

The REPL follows nREPL's message model: a handler with middleware (```seon.repl/middleware```: the error map, per-form cost, the compiler's account on the branch, the branch's schemas, contract instrumentation). The agent writes its own branch (`seon.agent/transact!`); what it changes becomes program through that recording alone, a removal included (```seon.repl/removal-tx```), so nothing it evaluated is evaluated again ([source](source.md#an-agent-changes-code-at-its-repl)).

Its tests run in a fork of itself (`seon.agent/run-tests-forked`), each under a tick budget (`:seon.config.agent/test-ticks`, [merging](merging.md#a-tests-tick-budget)), never time; when every selected test is green at its digest nothing is forked; a fork refused because a thread is active answers `:seon.test/fork-refused` ([turn](turn.md#who-runs-which-tests)).

## An agent is never gated

An agent has a real REPL, and Clojure's semantics are the interface: the system writes down what happened and never gates what an agent may do. No cut or window is put on its context or its evals ([decisions](decisions.md#no-artificial-limits-or-cuts-on-an-agents-context-or-evals-owner-2026-10-29)), and a problem its branch made is shown to it as attention ([health](health.md#a-function-that-cites-no-section)). Before adding a check on an agent ask: what would a plain REPL do? Is this recording or judging? What observed thing breaks without it?

## A parked process

An agent that ends waiting keeps its process, parked on its task, and the next message to the agent wakes that same process: the same agent, no fork. One at its cap has ended ([the cap](turn.md#the-cap)).

At the agent's end (`seon.reconcile/judge-tx`) a work task whose agent ended on a park outcome (`seon.reconcile/park-outcomes`: a reply with no eval, or its turn budget spent) keeps its process and is stored `:parked` (`:cap` for the budget); an end that parks on a task with no proofs runs no tests (```seon.reconcile/after-work```). An end the done rule refuses stands `:told` and its `:verdict` is a message to the agent ([turn](turn.md#what-a-turn-merges-from-main)). What readies a parked, told or capped task is a wake, never a count: when the writer commits a message the api puts `{:agent key}` on the reconciler's `:wake` port for each recipient (`resources/seon/system.edn`), and the reconciler holds it (`seon.reconcile/wake`; a wake for a running agent is held until it waits). The next start is a `:wake` on the parked process when it is of this boot, else a `:spawn`, and raises `:seon.agent/max-turns` by `:seon.config.agent/wake-turns` ([the cap](turn.md#the-cap)). At boot the first listing wakes each waiting agent owed a message, once (`seon.reconcile/boot-wakes`).

A parked process is one thread at 0% CPU, held by root as a fiber parked on the agent proc's in-port, so its cost is memory: measured once at 47-55 MB fresh, about 135 MB resumed and about 300 MB after real turns, never shrinking; a wake takes 7-35 ms.

Wake hazards, each a proof:
1. A wake's loop starts anew, so it takes its message and runs turns (`seon.agent-test/a-waiting-agent-parks-and-a-message-wakes-the-same-process`).
2. A wake's turns continue the agent's count ([turn](turn.md#the-turn-is-a-number); the same test).
3. A message that came while the agent was running still wakes it once it parks (`seon.reconcile-test/a-kept-message-does-not-wake-again`, `a-waiting-agent-parks-its-task-and-a-message-wakes-it`).
4. A wake stands the task `:running`, so two wakes never run two `work!` forms in one process.
5. The judge parks a park outcome, never holds it.
6. A wake whose parked process died is `:lost`, and the next start a spawn (`seon.reconcile-test/a-wake-whose-parked-process-is-gone-is-lost-and-the-next-start-a-spawn`).

Root records what it observes of each agent on main (```seon.system/record-agents```, ```seon.task/agent-tx```: status, pid, branch, the form running) when one of those changes; memory alone is no change.

## Every wait ends

There is no stall detector: a watcher for a process that stopped moving is a guard, and is deleted (owner, 2026-11-16; [decisions](decisions.md)). Every wait ends by construction instead. A wait on our own procs ends by the producer's value or its typed end: the transport answers every wait on root typed at its drain or at root's end of file, and a drain answers what each proc holds. A wait on the outside world ends by its effect's declared deadline, answered typed: a model call's quiet bound, the writer's flush, a test's run (a test is code under test, so its run's deadline is its stated estimate times the config leeway, and past it the test is red, never a held run). A wait that can hang is a bug at its cause, never a reason for a timer around it.

## The socket and the agent proc

Root and an agent speak length-prefixed frames (a length word and a jfasl body, optionally carrying a descriptor) over one Unix socket. On the agent's side `seon.sock` is read by one fiber (```seon.agent/intake```, the `:transport` proc's in-port) and written whole by whichever proc answers. The socket's buffer is the bounded channel: a full buffer makes the sender wait. Root's end is a `jolt.async.remote` end whose `:in` is the in-port of ```seon.agent-proc/agent-proc```, one proc per agent, added and removed with `flow/update!` ([root](root.md#the-root-graph)). It passes Flow's pause, resume and stop to the agent; on stop an eval in flight is interrupted (SIGUSR1) and the agent exits after the requests before it, and an agent that never exits is a stall (```seon.agent-proc/stop!```).

## Interrupts

Root stops a running form out of band with SIGUSR1, and a form past its work budget stops itself: `seon.intr`. A stop of root interrupts nothing: each agent is sent `:stop`, which its transport reads at once, so a run in flight ends `:waiting` at once, its call in flight dropped, with what its turns committed (the `:repl` proc holds the run: [flow](flow.md#flow-inside-an-agent)), and the agent exits after the requests before it, each ending by its own bound (```seon.agent-proc/stop!```); the stop's own bound, `:seon.config.root/stop-ms`, ends a root that never exits.

Each eval runs under its own interrupt token (`jolt.host/run-interruptible`); the signal sets every token on the stack, so the whole request stops at a safe point the form's own `try` cannot swallow. A form past its tick budget (calls and loop back-edges, never time; `:seon.config.agent/form-ticks` for a model's, an outside or a UI eval, `seon.repl/budgeted`) answers `:seon.repl/ticks-spent`, and one past its byte budget (`:seon.config.agent/form-bytes`) `:seon.repl/bytes-spent`; the request goes on ([failure points](failure-points.md)).
