# Dragons

Owner, 2026-10-20: "Guards and fences are almost always wrong. We should be doing the right
thing, not guarding for it or trying to block random shit. We should know where all the
dragons are and name them explicitly when dealing with them."

A **dragon** is a place where the world outside our code can fail us. Each one is met by
exactly one function, its **edge**. The edge either answers a declared variant, which is
plain data its callers handle as an ordinary branch, or it is an **entrant boundary**, which
turns a defect into a recorded error map. Nothing else catches. `bin/dragons` (run by
`bin/check`) lists every `catch` in `src/` and fails when one is not named below by its
function.

## Edges that catch (each names its one variant)

| dragon | edge (`ns/fn`) | declared variant | handled by, as data |
|---|---|---|---|
| Datahike or the store refuses a write (a bad transaction, LMDB full or failing) | `seon.writer/writer-one` | `:seon.writer/refused` (the one error map, with `:ok false`) | the caller of the write: an agent's `transact!` throws it into its own eval (an `:err` item); root's callers read `:ok` and `:seon.error/message` |
| The model's HTTP call (connect, TLS, timeout, a non-200), made by root's `:model` effect lanes | `seon.model/http-post` | `:seon.model/provider-error`, or `:seon.model/rate-limited` for HTTP 429 | the `:model` owner answers it to the asking agent; `seon.turn/act!` returns it; `seon.turn/work!` ends the attempt `:error` with its message |
| An agent's authored contract does not compile | `seon.contract/arm!` | `:seon.contract/invalid` | `seon.repl/admit` puts it on the reply item (`:contract-error`); `seon.program/load-src!` reports it as a load error |
| An agent mints past its block of entity ids (its lease, `seon.config/EID-LEASE`; Datahike's `next-eid` refuses at the ceiling `seon.db/lease!` set) | `seon.agent/transact!` (`seon.db/lease-spent`); for the record of its evals, `seon.repl/record!` | `:seon.db/lease-spent` (`:lease :ceiling :entity-id`; nothing applied, and a refused transaction keeps the ids it reserved, so the lease stays spent) | the agent's eval (an `:err` item); a record of its evals it cannot write goes to root (`{:op :unrecorded}`) and is recorded on main as its problem |
| Program code from the db does not load (a source or ns form that throws, a missing alias target) | `seon.program/load-src!`, `seon.program/load-ns!`, `seon.program/aliases!` | `:seon.program/error` (`{:ns :message}`) | root and the fork server put load errors in their view and events; an agent's `:load-errors` |
| The filesystem refuses a write-back (`:file` effect) | `seon.fx/write-file` | `:seon.fx/io-error` | the asker's `my.fx/call` answer, recorded as `:seon.fx/*` on its branch |
| Root's entrance is not listening (the MCP server's one request: connect, write, read) | `seon.mcp/request!` | the tool's text `{:error ...}` | the outside agent reads it as the tool's answer |
| Text a model or a plan wrote, read as data (a reference span, a reply's line): it may not read as Clojure, and edamame 1.4.32 on Jolt throws a bare IllegalArgumentException for some (`a^`) rather than its `:edamame/error` | `seon.reader/read-text` | `nil`: the text is not Clojure | `seon.reconcile/reference` (not a reference), `seon.reader/forgive` (not a literal line) |
| Text an agent wrote that does not read as Clojure, read for waste at its attempt's end (lane D2) | `seon.learn/read-forms` | `[]`: the form is charged to no rule | `seon.learn/waste` |
| A task file a person wrote does not read as one EDN vector of declarations | `seon.task/file-declarations` | `:seon.task/refused` (the one error map, naming the file) | `seon.task/load-tx` answers the refusal; `my.root/load-tasks!` returns it and writes nothing |
| The web listener cannot bind its port (taken, refused) | `seon.web/web-proc` | `{:listening false :error message}` in the `:web` proc's state | its ping shows it on the dashboard's health line; boot goes on without the web |
| An interrupt or a timeout stops a running form (SIGUSR1, SIGALRM) | `seon.intr/run` | ex-info `{:interrupted true}` or `{:timeout-ms n}` | `seon.repl/eval-code` answers the form `interrupted`/`eval-error` and goes on |

## Entrant boundaries (a defect becomes a recorded error map)

| boundary | (`ns/fn`) | what it records |
|---|---|---|
| the agent's REPL: reading and evaluating an agent's form | `seon.repl/eval-code` | the form's error as an `:err` reply item (the one error map in `seon/error`) |
| the agent's request handler | `seon.repl/agent-step` | `err` + `done` on the request |
| a test run, in the test child of an agent | `seon.agent/run-tests-forked` | an `:error` report in the run |
| an effect lane performing a request (lane FX1) | `seon.fx/lane-proc` | `:seon.fx/defect` as the outcome: its asker is always answered |
| one outside connection's request (seon.entrance, line EDN) | `seon.entrance/serve-connection!` | the request's answer `{:error :data}`; the connection goes on |
| every root proc | Flow's error channel, read by root's error reader | `:root-errors` in the api's view, with the error map; counted by signature on main (`seon.error`, class `:platform`, lane D2) |

## Dragons met without a catch (the edge answers data)

| dragon | edge | answer |
|---|---|---|
| A peer's socket is gone (EOF, or its end frame) | root: `jolt.async.remote`'s reader pump; agents and the fork server: `seon.sock/fill!`, `seon.sock/recv!` | root: the end's `:in` closes, the stand-in's `:from` reads nil and becomes `{:op :dead}`; elsewhere `:eof` |
| A write to a dead peer (EPIPE) | `seon.sock/send-frame!` | `:epipe`; the agent exits, root reads EOF |
| `fork()` refused (another Chez thread active, the OS) | `seon.fork-server/server-step`, `seon.lib/safe-fork` | `:fork-failed`; `-2` from safe-fork (the test run and clone report it) |
| A process dies (killed, crashed, `System/exit`) | the socket's EOF; `waitpid` in `seon.fork-server/reap` | `:dead` / `:exit` events; the agent's pending replies answer `:seon.agent/died` (its last op and stderr tail), recorded with its exit status on main (lane D2) |
| An agent's stderr file cannot be made (a store with no path) | `seon.agent/stderr-to-file!` | stderr stays where it was; a death records no tail |
| A dead agent's stderr file is gone or empty | `seon.root/stderr-tail` | nil |
| The fork server dies | `seon.fork-server/fork-server-proc` (EOF of its events) | its standby is promoted (`:server-down`, `:server-up`) |
| A test never ends or kills its process | `seon.agent/run-tests-forked` | killed at its bound; an `:error` report |
| An agent past its heap bound | Jolt's heap ceiling (`seon.lib/heap-cap!`) | `OutOfMemoryError` in the form: an `:err` item |
| A forked process holding its parent's LMDB env and connections (physics: fork shares handles) | `seon.db/after-fork!` | forgotten, not closed; the agent opens the store itself |
| Datahike refuses an agent's own write (its run-ahead apply) | `datahike.writer/apply!` (the library's edge) | the exception as its answer; `seon.agent/transact!` throws its message into the agent's eval (an `:err` item) |

## Physics: who may run threads

Two rules of the OS and Chez that no code may "fix" by adding a thread or a check:

- **A process that forks must never have started Chez's parallel-collector sweeper
  threads.** Chez starts them at the first collection that finds another thread, and keeps
  them; a child forked from such a process hangs in its first multi-threaded collection
  (lane RC1, sampled in `S_gc_par_entry` -> `run_sweeper`). The processes that fork are the
  fork server (it forks agents and its standby) and every agent (its test children,
  `clone!`). So both are single-threaded and read and write their sockets themselves
  (`seon.sock`), and only root, which never forks after boot, holds `jolt.async.remote`
  ends with pumps. `bin/check threads` counts the OS threads of the fork server and of an
  agent. The real fix is a Chez atfork handler that resets the sweepers (RC1's "next (b)").
  Root, which does hold threads, fixes its sweepers at boot, right after the fork server is
  forked: `seon.lib/gc-sweepers!` (our Chez's `(cs)collect_sweepers`, branch
  `jolt/gc-sweepers`) fixes them at `seon.config/GC-SWEEPERS`, 0 (measured: sweepers did
  not shorten root's small collections), and no collection starts one.
  Unfixed, Chez kept one per thread a collection found waiting, so root's thread count
  drifted by load (0, 5, 15 sweepers across identical runs; lane THREADS).
- **On macOS, a descriptor in flight by SCM_RIGHTS can be discarded when other unix
  sockets close**, so the sender keeps its copy until the receiver confirms (the fork
  server's `:in-flight`, a clone's socket kept until `:cloned`). A descriptor rides a frame
  of its own on a socket a pump also writes (`seon.agent-proc/send-fd!`), sent only while
  that pump is idle: the agent asks for it (`:fd-ready`) and the stand-in holds its other
  messages until it is sent.

## Contracts over host objects are proved by deftests, never by generation

The census gate (`bin/check census`) runs `malli.generator/check` only on the pure
namespaces' contracts. A contract over a host binding (a pid, a file descriptor, a signal
number, a socket, a connection) is never handed generated arguments: a generated pid passed
to `kill(2)` is a real signal to a real process, a generated fd closes someone's socket.
Those functions are proved by their deftests and the end-to-end checks, which call them with
the values the system really holds.

## The profile's counts are exact on one thread only

`seon.profile` keeps each armed function's counts in a plain Scheme vector, with no lock
and no atomic, because a lock would cost more than the counter (0.9 ns). An agent, the fork
server and a test child are single-threaded, so their counts are exact and cost proofs run
there. Root's procs share functions across threads: two threads can lose an increment, and
a function first called on two threads at once can be missed from `touched`. Root's counts
are approximate and nothing reads them as proof. A measurement nested inside another (a
cost proof run inside an eval form) restarts the epoch, so the outer form records only the
calls after it.
