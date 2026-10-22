# Failure points

Owner, 2026-10-20: "Guards and fences are almost always wrong. We should be doing the right
thing, not guarding for it or trying to block random shit. We should know where all the
failure points are and name them explicitly when dealing with them."

A **failure point** is a place where the world outside our code can fail us. Each one is met by
exactly one function, its **edge**. The edge either answers a declared variant, which is
plain data its callers handle as an ordinary branch, or it is an **entrant boundary**, which
turns a defect into a recorded error map. Nothing else catches. `bin/laws catch` (run by
`bin/check`) lists every `catch` in `src/` and fails when one is not named below by its
function. The same sites check judges the other laws: [clocks](clocks.md) and [resolution](resolution.md).

## Edges that catch (each names its one variant)

| failure point | edge (`ns/fn`) | declared variant | handled by, as data |
|---|---|---|---|
| Datahike or the store refuses a write (a bad transaction, LMDB full or failing) | `seon.writer/writer-one` | `:seon.writer/refused` (the one error map, with `:ok false`) | the caller of the write: an agent's `transact!` throws it into its own eval (an `:err` item); root's callers read `:ok` and `:seon.error/message` |
| The store refuses a commit: its codec (konserve.jolt.fasl) has no encoding for a value a transaction carries (a sorted map, a host object) | `seon.writer/commit-batch` | `:seon.writer/refused` to the transaction that carried it (the batch is retried one transaction at a time; main is as it was, Datahike moves it only after the write) | the asker; the writer goes on |
| The model's HTTP call (connect, TLS, the socket dying mid-stream), made by root's `:model` effect workers; streamed over SSE with no clock (a reasoning reply streams for minutes) | `seon.model/http-post` | `:seon.model/transport` (the connection failed or dropped); a non-200 is `:seon.model/provider-error` with the whole body, or `:seon.model/rate-limited` for HTTP 429 with its Retry-After (`seon.model/complete`) | the `:model` owner answers it to the asking agent (a 429 is kept and retried after its wait); `seon.turn/act!` returns it; `seon.turn/work!` ends the attempt `:error` with its message |
| A model's eval call carries arguments that are not JSON with code text (a reply cut mid-call at its token limit) | `seon.model/call-code` | none: the arguments as they came are the call's code | `seon.turn/act!` evaluates them like any code; the reader repairs what it can and the call's result shows what they held |
| An agent's authored contract does not compile | `seon.contract/arm!` | `:seon.contract/invalid` | `seon.repl/instrument` puts it on the reply item (`:contract-error`); `seon.program/load-form!` reports it as a load error |
| An agent mints past its block of entity ids (its lease: `:seon.config.store/eid-lease` ids reserved by `seon.db/lease-block!`; Datahike's `next-eid` refuses at the ceiling `seon.db/lease!` set) | `seon.agent/transact!` (`seon.db/lease-spent`); for the record of its evals, `seon.repl/record!` | `:seon.db/lease-spent` (`:lease :ceiling :entity-id`; nothing applied, and a refused transaction keeps the ids it reserved, so the lease stays spent) | the agent's eval (an `:err` item); a record of its evals it cannot write goes to root (`{:op :unrecorded}`) and is recorded on main as its problem, and the reply's items say `:unrecorded`; blocks and the restart invariant: [storage](storage.md#leases) |
| Program code from the db does not load (a form that throws: a missing require target, a reference to a var main does not hold) | `seon.program/load-form!` | `:seon.program/error` (`{:ns :message}`) | root and the fork server put load errors in their view and events; an agent's `:load-errors` |
| The filesystem refuses a write-back (`:file` effect) | `seon.fx/write-file` | `:seon.fx/io-error` | the asker's `my.fx/call` answer, recorded as `:seon.fx/*` on its branch |
| Seon's own source cannot be read (the tree root's `src` missing, a file unreadable) at boot (`:source` effect) | `seon.fx/read-source` | `:seon.fx/io-error` | `seon.root/import-source!` records it as root's `:seon.root/source` `:error`; main holds no source and nothing is written back |
| git refuses the merges commit (not a repository, the branch moved meanwhile, no author identity) (`:git` effect) | `seon.fx/git-commit` | `:seon.fx/io-error` | the reconciler records it on each merged attempt as `:seon.attempt/commit-error`; main's merge stands |
| A dependency does not resolve (a `:mvn/version` in no repository, a `:git/sha` that cannot be fetched, a `:local/root` that does not exist, no network) (`:deps` effect) | `seon.fx/resolve-deps` | `:seon.fx/unresolved` (`{:deps [lib] :unresolved [{:coord :version :detail}]}`, from `jolt.deps`'s own `:unresolved` ex-data) | the asker's `my.fx/call` answer; it records it on its branch and loads nothing |
| An agent registers a schema form that does not compile (a name resolving to nothing, a malformed form) or that Datahike cannot store (`my.schema/register!`) | `my.schema/judged` | `:seon.schema/registration-refused` (the key, `:invalid` or `:unstorable`, Malli's or the declaration builder's message) | the agent's `register!` answer; nothing is admitted to its branch |
| Text a model, a plan or an agent wrote, read as data (a reference span, a reply's line, an evaluated form read for waste): it may not read as Clojure, and edamame 1.4.32 on Jolt throws a bare IllegalArgumentException for some (`a^`) rather than its `:edamame/error` | `seon.reader/read-text` | `nil`: the text is not Clojure | `seon.ref/parse` (a span that is not one name is prose), `seon.reader/forgive` (not a literal line), `seon.learn/waste` (a form charged to no rule) |
| A task file a person wrote does not read as one EDN vector of declarations | `seon.task/file-declarations` | `:seon.task/refused` (the one error map, naming the file) | `seon.task/load-tx` answers the refusal; `my.root/load-tasks!` returns it and writes nothing |
| A `--set :ns/key=edn` a person typed on the command line does not read as EDN | `seon.config/parse-set` | `:seon.config/refused` (thrown, naming the argument) | boot is refused: the process exits with the refusal before any mode runs |
| The web listener cannot bind its port (taken, refused) | `seon.web/web-proc` | `{:listening false :error e}` in the `:web` proc's state, `e` the one error map (`:seon.web/unbound`) | its ping shows it on the dashboard's health line; boot goes on without the web |
| An interrupt or a tick budget stops a running form (root's SIGUSR1; Jolt's `run-interruptible` past its ticks, `:seon.config.agent/form-ticks`: work done, never time). Interrupts live in `seon.intr`, from two sources: root's SIGUSR1 (`seon.intr/install!`), and SIGIO from the agent's own socket to root (`seon.intr/exit-at-eof!`), which ends the process when that socket reads EOF | `seon.intr/run` | ex-info `{:interrupted true}`, or the typed `:seon.repl/ticks-spent` `{:ticks :budget}` | `seon.repl/eval-code` answers the form `interrupted` or its typed error and goes on |
| A proof run that does more work than its tick budget (`:seon.config.agent/test-ticks`; Jolt's `run-interruptible`) | `seon.agent/run-tests-here` | `:seon.test/ticks-spent` `{:ticks :budget :test}`, naming the test that was running | an `:error` report in the run; the turn's context shows it as the test run failing |

## Entrant boundaries (a defect becomes a recorded error map)

| boundary | (`ns/fn`) | what it records |
|---|---|---|
| the agent's REPL: reading and evaluating an agent's form | `seon.repl/eval-code` | the form's error as an `:err` reply item (the one error map itself, under `:err`) |
| a request's evaluation | `seon.repl/eval-string` | the answers so far plus one `:err` item (its error map and id), recorded with them |
| the agent process's loop: a defect no request's boundary caught (the agent redefined a function its own loop calls; a failing commit of its record) | `seon.repl/serve!` | the cause on stderr, then the attempt's end on its branch (`seon.agent/died!`: outcome `:error`, the one error map with its cause chain); exit 70, which names the death; root's judge carries the end to the attempt's reason and error |
| a test run, in the test child of an agent | `seon.agent/run-tests-forked` | an `:error` report in the run |
| an effect worktree session performing a request (session FX1) | `seon.fx/worker-proc` | `:seon.fx/defect` as the outcome: its asker is always answered |
| one outside connection's request (seon.server, line EDN or MCP's JSON-RPC) | `seon.server/respond` | that request's answer, the typed `:seon.server/defect` (the line it took, the cause's ex-data printed; for MCP, inside a JSON-RPC error with the request's id, `-32700` for a line opening `{"` that is not JSON, naming the rule that such a line is MCP's and an EDN request a keyword-keyed map); the connection and every other one go on |
| every root proc | Flow's error channel, read by root's error reader | `:root-errors` in the api's view, with the error map; written once per signature on main, repeats counted in the api's fold (`seon.error/flush-tx`; `my.root/problems` reads both); a repeat of a written signature writes nothing, or the error's own record would be the next commit it fails on |

## Failure points met without a catch (the edge answers data)

| failure point | edge | answer |
|---|---|---|
| A peer's socket is gone (EOF, or its end frame) | root: `jolt.async.remote`'s reader pump; agents and the fork server: `seon.sock/fill!`, `seon.sock/recv!` | root: the end's `:in` closes, the agent proc's `:from` reads nil and becomes `{:op :dead}`; elsewhere `:eof` |
| A write to a dead peer (EPIPE) | `seon.sock/send-frame!` | `:epipe`; the agent exits, root reads EOF |
| `fork()` refused (another Chez thread active, the OS) | `seon.lib/fork!`, the one spawning skeleton (the fork server's agents and standby, an agent's test child and clone) | its `refused` branch with the active Chez threads, checked once, never waited out: `:fork-failed` / `:standby-failed` with `:active-threads`; the test run's `:error` report; the clone's `:clone-failed` |
| An outside client half-open at root's server (it never sends its line, or never reads its answer) | `seon.server/serve-connection!`, on that connection's own edge thread | nothing: that connection's thread parks (no bound can tell slow from gone); every other connection is served. It shows as one more root thread, and ends when the client's socket closes |
| Root's server is not running, not listening, or closes without answering (the MCP relay's one connection per message: read the port bin/root recorded, `runs/root.port`; connect, write, read) | `bin/mcp` (stock perl, outside `src`) | a message with an id is answered the JSON-RPC error naming why (`-32000`: "no root running for this checkout", "not listening (Connection refused): bin/root start", or "root answered nothing"); the relay goes on to the next message, so a restarted root answers it. A line that is not JSON has no id the relay can read: it is relayed as it is, and root answers its defect (`seon.server/respond`: the JSON-RPC error `-32700` for a line opening `{"` that is not JSON, the EDN defect for any other line) |
| A process dies (killed, crashed, `System/exit`) | the socket's EOF; `waitpid` in `seon.fork-server/reap` | `:dead` / `:exit` events; the agent's pending replies answer `:seon.agent/died` (its exit status, last op and stderr tail) once the server reaped it (at once for a clone or an orphan of a server that went down, whose status no one reaps), recorded on main (session D2); a dead attempt's reason is the end it wrote on its branch, if any, beside that death (`seon.reconcile/dead-tx`) |
| An agent's stderr file cannot be made (a store with no path) | `seon.agent/stderr-to-file!` | stderr stays where it was; a death records no tail |
| A dead agent's stderr file is gone or empty | `seon.root/stderr-tail` | nil |
| Root dies (killed, crashed) | the fork server's loop, `seon.fork-server/main`, reads EOF on its socket to root and kills its process group (every agent, clone, test child and standby is in it); each agent also reads EOF on its own socket to root, idle (`seon.agent/recv-raw!`) or busy (SIGIO, `seon.intr/exit-at-eof!`) | nothing of that root outlives it. A cleanup kills a root, or its fork server's group (`kill -9 -PGID`), never a fork server by pid: that skips the group kill (task `seon.agent/an-agent-ends-with-its-root`) |
| An agent's socket to root cannot raise SIGIO (`fcntl` F_SETOWN or O_ASYNC refused) | `seon.sock/signal-at-input!` | `:seon.sock/unwatched`, sent with the agent's hello and recorded as its problem; the agent then ends with its root only when it next reads that socket |
| The fork server dies | `seon.fork-server/fork-server-proc` (EOF of its events) | its standby is promoted (`:server-down`, `:server-up`) |
| A test never ends or kills its process | `seon.agent/run-tests-forked` | a test past the run's tick budget stops (`seon.agent/run-tests-here`); a death ends the pipe, and the agent reads it to its end (no clock) and names the last test started: an `:error` report |
| An agent past its heap bound | Jolt's heap ceiling (`seon.lib/heap-cap!`) | `OutOfMemoryError` in the form: an `:err` item |
| A forked process holding its parent's LMDB env and connections (physics: fork shares handles) | `seon.db/after-fork!` | forgotten, not closed; the agent opens the store itself |
| Datahike refuses an agent's own write (its run-ahead apply) | `datahike.writer/apply!` (the library's edge) | the exception as its answer; `seon.agent/transact!` throws its message into the agent's eval (an `:err` item) |
| A process dies inside an LMDB write txn while other processes hold the store's env open (a root SIGKILLed mid-commit, a crash check) | LMDB's own writer lock, built robust (`bin/build lmdb`, below) | the kernel releases the dead holder's lock (SysV `SEM_UNDO`); the txn is lost, the next writer commits |

## Physics: who may run threads

Three rules of the OS and Chez that no code may "fix" by adding a thread or a check:

- **A process that forks must never have started Chez's parallel-collector sweeper
  threads.** Chez starts them at the first collection that finds another thread, and keeps
  them; a child forked from such a process hangs in its first multi-threaded collection
  (session RC1, sampled in `S_gc_par_entry` -> `run_sweeper`). The processes that fork are the
  fork server (it forks agents and its standby) and every agent (its test children,
  `clone!`), each through ```seon.lib/fork!```, which forks only while its caller is the
  one active Chez thread. So both are single-threaded and read and write their sockets themselves
  (`seon.sock`), and only root, which never forks after boot, holds `jolt.async.remote`
  ends with pumps. `bin/check threads` counts the OS threads of the fork server and of an
  agent. The real fix is a Chez atfork handler that resets the sweepers (RC1's "next (b)").
  Root, which does hold threads, fixes its sweepers at boot, right after the fork server is
  forked: `seon.lib/gc-sweepers!` (our Chez's `(cs)collect_sweepers`, branch
  `jolt/gc-sweepers`) fixes them at `seon.config/GC-SWEEPERS`, 0 (measured: sweepers did
  not shorten root's small collections), and no collection starts one.
  Unfixed, Chez kept one per thread a collection found waiting, so root's thread count
  drifted by load (0, 5, 15 sweepers across identical runs; session THREADS).
- **A forked child holds none of its parent's threads, only their bookkeeping.** fork(2)
  copies the calling thread; Jolt's fiber carriers, its timer and its shutdown watcher are
  parked when the one active thread forks (a parked thread is not active, so
  ```seon.lib/fork!``` forks), and root has them all before it forks the fork server (its
  boot's first Datahike release runs a `go`). So the child resets them first: the fork in
  `seon.lib`'s `proto-fork-alone` calls Jolt's `jolt-after-fork-child!` (branch
  `jolt/fork-carriers`), which drops the carrier pool, the timer and its pending deadlines (the
  parent's waiters), the thread tables and the watcher (emptying the child's mask, the default
  mask Jolt gives every child), so each starts afresh on first use. It starts no thread: a
  child that forks again (the fork server's double fork) must still be one thread. Before it,
  every `go` that parked in an agent never resumed: a design agent hung making a test store,
  the judge's proof fork would have too, and SIGTERM stayed pending (the step E dry run,
  2026-10-22; `seon.lib-test/a-forked-child-runs-go-blocks-and-makes-a-store`, Jolt's
  `test/chez/fork-child-test.clj`). A pool made with `jolt-fiber-pool-make` is not restarted:
  a forking program makes it after the fork.
- **On macOS, a descriptor in flight by SCM_RIGHTS can be discarded when other unix
  sockets close**, so the sender keeps its copy until the receiver confirms (the fork
  server's `:in-flight`, a clone's socket kept until `:cloned`). A descriptor rides a frame
  of its own on a socket a pump also writes (`seon.agent-proc/send-fd!`), sent only while
  that pump is idle: the agent asks for it (`:fd-ready`) and the agent proc holds its other
  messages until it is sent.

## Contracts over host objects are proved by deftests, never by generation

The mg/check gate (`bin/check mg-check`) runs `malli.generator/check` only on the pure
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

## LMDB robust locks

On macOS, LMDB locks with POSIX semaphores unless built with `MDB_USE_ROBUST` (`mdb.c`:193).
Those are not robust: a process SIGKILLed inside a write transaction leaves the writer lock
held, and every other process with the env open (root always has its store open) blocks on
its next write, forever. Measured 2026-10-21 by `target/lmdb-kill` (`native/lmdb_kill.c`):
with the POSIX build the next writer and the holder both block; with the robust build both
commit and the killed transaction is gone. `bin/build lmdb` builds `target/lib/liblmdb.dylib`
robust from `../vendor-c/lmdb` (stamped with its commit), `bin/env` points `JOLT_LMDB_LIB`
at it, and `bin/check`'s `lmdb-kill` step proves it. Every process on a store must load the
same build: the lock scheme is part of the lock file's format.

The robust build uses SysV semaphores, which outlive their processes: each store path keeps
one semaphore set (two semaphores, keyed by the lock file) after its last process exits.
See them with `ipcs -s` (LMDB's are mode `--ra-ra-r--`, owner you); one is reused when the
same store opens again. The system limit is `sysctl kern.sysv.semmni` (87381 here), so one
store per run is nothing. Removing a set (`ipcrm -s ID`) while no process has its store open
is safe; LMDB makes a new one at the next open.

