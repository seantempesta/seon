# Processes

Every agent is its own OS process, forked from one single-threaded fork server that holds main's db value, so it starts in milliseconds sharing the compiled program copy-on-write, and its crashes and memory are its own.

Root never forks after boot: it is multithreaded, and a process that forks must never have started Chez's parallel-collector sweeper threads ([failure points](failure-points.md#physics-who-may-run-threads)). So the fork server, each agent and each agent's test children stay single-threaded, and only root runs threads and Flow.

## The fork server

The fork server is one thread that holds main's db value, applies every main commit in order, and forks an agent on request: `seon.fork-server`.

```seon.fork-server/fork-server``` forks it at boot while root has one active thread. Its loop, ```seon.fork-server/main```, runs its step-fn ```seon.fork-server/server-step``` under `seon.step/run-loop`, the same step-fn contract as root's procs. Commits (`:tx`) and fork requests (`:fork`) arrive on one FIFO pipe from the writer, so an agent forked after commit k sees exactly k: the writer created its branch at that point. A commit that changes main's program is loaded here (`seon.program/load-changes!`), so agents forked from then on inherit it compiled. Each agent's socket arrives with its fork request (SCM_RIGHTS); the server reaps its children and reports their exits.

The boot server makes a process group every agent, test child and clone inherits; EOF on its socket to root is root's death, and it kills the group ([failure points](failure-points.md)). Root's side is ```seon.fork-server/fork-server-proc```, the one writer of the command pipe: it keeps a standby (a fork of the server, idle until promoted) and promotes it when the server dies, so no fork ever comes from root.

## The agent process

An agent is a forked process holding its branch's db value and running its REPL over one socket to root, single-threaded: `seon.agent`, `seon.repl`.

The child runs `seon.repl/run!`: ```seon.agent/init!``` makes the process the agent (its socket, a namespace of its own, its state, its interrupt handlers, its stderr file, the store opened for its own branch's writes, the model's API key removed from its environment), then it says hello and serves. ```seon.repl/agent-step``` is the agent as a step-fn: an `:eval` request runs through ```seon.repl/eval-string``` (read, evaluate, record, one reply item per form), and Seon's own ops (`:tests`, `:stats`, `:clone`, `:merge-main`, `:message`, `:fx-reply`) through `seon.agent/handle!`. A defect no request's boundary caught ends the process at `seon.repl/serve!` with exit status 70 and its cause written as the attempt's end (`seon.agent/died!`).

The REPL speaks nREPL's message model: a handler with middleware composed over it (```seon.repl/middleware```: the one printer, the one error map, per-form cost, the compiler's account recorded on the branch, the branch's schema registry, contract instrumentation). The agent writes its own branch (`seon.agent/transact!`); root reads its commits through the writer ([storage](storage.md)). What an agent changes becomes program through that recording and through staging the live runtime (```seon.stage/stage!```: a var unmapped or a method removed, which only the runtime knows), so nothing it evaluated is evaluated again ([source](source.md#an-agent-changes-code-at-its-repl)).

Its tests run in a fork of itself (`seon.agent/run-tests-forked`), bounded by `:seon.config.agent/test-ticks` of work, never time; a fork refused because a thread is active answers `:seon.test/fork-refused` ([turn](turn.md#who-runs-which-tests)).

## The socket and the agent proc

Root and an agent speak length-prefixed frames over one Unix socket, read and written on the agent's side by its one thread with no pumps: `seon.sock`.

A frame is a length word and a jfasl body; a descriptor can travel with a frame. The socket's buffer is the bounded channel: a full buffer makes the sender wait. Root's end is a `jolt.async.remote` end on fiber pumps, whose `:in` is the in-port of the agent's proc in root, ```seon.agent-proc/agent-proc```: one proc per agent, added and removed by the api with `flow/update!` ([root](root.md#the-root-graph)). It passes Flow's pause, resume and stop on to the agent's own loop; on stop the agent exits after the requests before it, and past `seon.agent-proc/stop-bound-ms` it is SIGKILLed.

## Interrupts

Root stops a running form out of band with SIGUSR1, and a form past its work budget stops itself: `seon.intr`.

Each eval runs under its own interrupt token (Jolt's `jolt.host/run-interruptible`); the signal sets every token on the stack, so the whole request stops, nested evals included, at a safe point the form's own `try` cannot swallow. A form past its tick budget (procedure calls and loop back-edges, never elapsed time; an outside or UI eval takes `:seon.config.agent/form-ticks`, `seon.root/submit!`) answers `:seon.repl/ticks-spent`, and the request goes on ([failure points](failure-points.md)).
