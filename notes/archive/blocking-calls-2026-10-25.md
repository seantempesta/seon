# Blocking calls reached from an agent, a test fork and the fork server (2026-10-25)

Read-only audit (reading, grep, git; no build, no test run). Question: if a forking process runs only on
Jolt's main carrier (one OS thread runs every fiber; `Thread.start`, `future`, agent pools and executors
become fibers), which calls hold that thread until something outside answers?

Classes: PARKS (the fiber parks through the io poller or a channel), SHORT (bounded by its own work),
BLOCKS (holds the carrier until something outside answers). Numbers are estimates unless stated;
nothing here was measured.

## How Jolt behaves on a fiber (the code that decides)

| Call | On a fiber (main carrier) | Code |
|---|---|---|
| `<!!` `>!!` `alts!!` | PARKS. The var is redefined to dispatch on `(jolt-current-fiber)`: fiber -> `jolt-fiber-<!*`, thread -> the condition wait | `host/chez/java/fibers-async.ss:314-316` (comment `async.ss:1010`) |
| `deref` of a promise or future | PARKS. `jolt-cv-wait-interruptibly` -> `jolt-cv-wait/ibox` -> `jolt-cv-wait/park` when on a fiber; a thread waits on the Chez condition | `concurrency.ss:339` (promise), `:216` (future); `locks.ss:693-725`, `:946` |
| `Thread/sleep` | PARKS with a timer deadline (it used to sleep the carrier; the comment says so) | `host-static-methods.ss:97-122` (`jolt-thread-sleep-ms`) |
| raw `jolt-condition-wait` on a fiber | REFUSES with an error (`jolt-blocking-refuse`), it never silently blocks | `locks.ss:469-482`, `:416` |
| `locking` / `ReentrantLock.lock` contended | PARKS (`monitor-wait!` registers the fiber, returns `jolt-lock-parked`) | `concurrency.ss:1383` |
| timed or interruptible lock acquire (`tryLock` with timeout, `lockInterruptibly`) | polls: `sa-fiber-yield`, then `(sleep 1ms)` of the whole carrier when the run queue is empty. If the holder is parked on I/O, the carrier naps and `main-io-wait` is never reached: a possible livelock, unverified | `concurrency.ss:1514-1527`, users `:4649`, `:4660` |
| `Process.waitFor` | PARKS, but as a WNOHANG poll with backoff (`jolt-pause-ms` parks between tries); not event driven | `process.ss:2230-2240` |
| subprocess pipe read and write | PARKS (O_NONBLOCK pipe, EAGAIN -> `jolt.io-poller/wait-ready`) | `process.ss:736-745`, `:898`, `:932` |
| `jolt.socket` connect, accept, read | PARKS (`poller/wait-ready`, deadline arity) | `stdlib/jolt/socket.clj:21`, `:349`, `:615` |
| any foreign call not routed through the poller, `:blocking` included | BLOCKS the carrier. `:blocking` only means collect-safe, it does not hand the carrier off | `async.ss:1010-1015` says it |
| `future`, `Thread.start` today | a real OS thread (`fork-agent-thread`), which `fork-alone` refuses (`-3 :program-thread`); this is what the plan changes | `concurrency.ss:154` |
| io poller on a main carrier | no poller thread; the loop in `jolt-main-carrier-run!` calls `main-io-wait` only when no fiber can run | `fibers.ss:1644-1690`, `io_poller.clj:705-782` |
| signal wake on a main carrier | `wake-on-signals!` is kqueue `EVFILT_SIGNAL`, macOS only; "Linux would need a signalfd" | `io_poller.clj:730-746` |

Consequence: every `BLOCKS` row below stops every other fiber, the io poller round and the timers of the process
for its duration. A signal still runs its handler (EINTR ends the foreign call, Chez runs the handler on the
same thread), which is why the interrupt and `exit-at-eof!` paths survive a blocked call. A tick budget
(`run-interruptible`) cannot stop a foreign call, only a signal can.

## Rows: agent, its test fork, the fork server

Agent already runs under `jolt.host/main-carrier-run!` on macOS only (`repl.clj:498`); on Linux it runs on a
plain thread (`seon.lib/macos?`).

| # | Call site | Waits for | Class | Fix at its owner | macOS-specific / Linux needs |
|---|---|---|---|---|---|
| 1 | `sock.clj:196-203` `recv!` (idle wait, `await-op!`, `fx!`, `next-msg!`) | root's next frame | PARKS on main carrier: `recvmsg` with `MSG_DONTWAIT`, EAGAIN -> `poller/wait-ready fd :read` | none | works on epoll too; the constants are not portable (row 21) |
| 2 | `sock.clj:180` `c-recvmsg` blocking arm, taken when `park?` is false (every agent on Linux today, every non-fiber caller) | root's next frame | BLOCKS | run Linux agents on the main carrier too (needs row 20) | Linux |
| 3 | `sock.clj:180,224,32` `next-frame!`, `inbox`, `fill!` nonblocking | nothing (`MSG_DONTWAIT`) | SHORT | none | none |
| 4 | `sock.clj:90` `peer-gone?` (`MSG_PEEK or MSG_DONTWAIT`) | nothing | SHORT | none | none |
| 5 | `sock.clj:236` `write-all!` -> `lib.clj:38` `c-write` (every `agent/send!`, `write-line!`; the test child's progress lines to its pipe) | the peer to drain the socket buffer (`sock-buf-bytes`); blocks only when the buffer is full | BLOCKS (bounded by root's reader, or by the agent for the test pipe) | in `sock.clj`, same shape as `recv!`: on `(main-carrier? and fiber?)` send with `MSG_DONTWAIT`, on EAGAIN `poller/wait-ready fd :write`, retry the rest; handle a short send | the fds are socketpairs, so `send(2)` flags work; Linux adds `MSG_NOSIGNAL` (no `SO_NOSIGPIPE`) |
| 6 | `sock.clj:277` `c-sendmsg` (fd passing: clone, standby) | same | BLOCKS | same fix, `sendmsg` with `MSG_DONTWAIT` | as row 5 |
| 7 | `agent.clj:648-651` -> `lib.clj:121` `poll1(fd, -1)` in `run-tests-forked` | the test child to write a line or exit; the whole test run, minutes | BLOCKS (the most dangerous: every fiber of the agent is stopped for the run) | `lib/line-reader`: replace `poll1` with `poller/wait-ready fd :read` and read with `c-recv-nb`; the `ms` argument is only ever -1 here | `poll1` is portable poll(2), the replacement is kqueue/epoll |
| 8 | `lib.clj:123` `c-read` after `poll1` said readable (cap 1 MB) | nothing, data is there | SHORT | none (it is `:blocking` only because the fd is blocking; with row 7 use the nonblocking read) | none |
| 9 | `agent.clj:652` -> `lib.clj:204` `wait-status` (blocking `waitpid` of the test child) | the child's exit; it comes after the pipe's EOF, and the pipe closes only at the child's `_exit` | SHORT in practice (the child is exiting). Unbounded if a test left a grandchild holding the pipe's write end, but then row 7 never returned either | to be fiber-safe regardless: after EOF call `waitpid` with WNOHANG and park on the child's exit if it is not yet reaped (pidfd on Linux, `EVFILT_PROC` on macOS, see row 12) | kqueue vs pidfd |
| 10 | `lib.clj:232` `wait-status` of the double-fork middle (`clone!`, `agent.clj:782`) | the middle process's immediate `_exit` | SHORT | none | none |
| 11 | `lib.clj:226` `jolt.host/fork-alone` (`fork!`) | fork(2) of the heap, copy-on-write page tables | SHORT (ms; the fork server reports `:fork-ms` per fork: measured there, not read here) | none. After the change the refusals `-3` and `-4` must never fire: no program thread, no running go block | none |
| 12 | no site yet: how a fiber waits for a child's exit | a child's exit | (missing) | a fiber-aware child wait in `jolt.io-poller` (owner): macOS `EVFILT_PROC` + `NOTE_EXIT` on its kqueue, Linux `pidfd_open` readable on exit, else `SIGCHLD` through `wake-on-signals!` and a WNOHANG loop. On a Jolt branch `jolt/<topic>` | macOS now, Linux needs pidfd (kernel 5.3) or signalfd |
| 13 | `lmdb_ffi.jolt:74-78` `begin ... 0` (every `write-txn`, `transact`, so every Datahike commit and `commit-pending!`, `agent.clj:134`) | LMDB's cross-process writer mutex, held by another process's short commit | SHORT: holders write dirty pages with pwrite under `MDB_NOSYNC` (`db.clj:79,106`), no fsync; a dead holder is released by the robust lock build (`failure-points.md#lmdb-robust-locks`). Unbounded only if a holder is stopped (SIGSTOP) | none; the fsync lives in `pdb/sync!` and is root's writer's (row 31) | the robust lock is the `bin/build lmdb` build on macOS; Linux has native robust mutexes |
| 14 | `lmdb_ffi.jolt:114` `mdb-txn-commit` | pwrite of the dirty pages | SHORT (estimate: sub-millisecond to low ms for an agent's commit) | none | none |
| 15 | `lmdb_ffi.jolt:60-67` `open-env` after fork (`db.clj:346-353` `after-fork!`, then `d/connect`): `txn_begin` write, `dbi_open`, commit | the same writer mutex | SHORT | none | none |
| 16 | LMDB reads (`read-txn`, `mdb-get`, cursors, `commit-as-db`) | mmap page faults, reader slot | SHORT | none | none |
| 17 | `Thread/sleep`, promise and future `deref`, `<!!`, `>!!`, `alts!!`, `locking` | channel, cv, timer | PARKS (table above) | none | none |
| 18 | timed or interruptible lock acquires | the lock | SHORT, with the polling risk in the table above | none unless used: grep found no use in `src/` | none |
| 19 | `clojure.java.shell/sh`, `ProcessBuilder` in a test (`process.ss`) | child exit, pipes | PARKS (waitFor is a parked WNOHANG poll with 1 ms..backoff steps, `process.ss:2230`) | `waitFor` could use row 12 instead of polling | row 12 |
| 20 | `intr.clj:80,92` `wake-on-signals!` (SIGUSR1, SIGIO) | not a blocking call: how root's interrupt and the socket EOF wake an idle main loop | PARKS (kqueue) | Linux: signalfd (or the self-pipe) in `jolt.io-poller`, else a signal landing just before `epoll_wait` is lost | macOS only |
| 21 | `sock.clj:36-49` constants: `SOL_SOCKET 0xffff`, `SO_NOSIGPIPE`, `O_ASYNC 0x40`, `MSG_DONTWAIT 0x80`, `FD_CLOFORK`, and `lib.clj` `SIGUSR1 30`, `SIGIO 23` | not a call | n/a | `FD_CLOFORK` does not exist on Linux: `clofork!` (`sock.clj:70`) would need `FD_CLOEXEC` semantics replaced by explicit closes in the child | macOS only (the fd-inheritance rule the agent relies on, `agent.clj` `init!`, is a macOS flag) |
| 22 | regular-file reads and writes in an agent or test (`slurp`, `spit`, `File`, `lib.clj:109`, `my/agent.clj:134`) | local disk | SHORT (a FIFO, a tty or a network mount would BLOCK; none is used) | none | none |
| 23 | `stderr` (`agent.clj` `stderr-to-file!`, fd 2 to a file), `c-creat`, `c-dup2` | local disk | SHORT | none | none |
| 24 | `seon_ids_take` (`db.clj:262`, `native/ids.c:27`) | one atomic add on shared memory | SHORT | none | none |
| 25 | `fork_server.clj:157` `lib/kq-wait` -> `e13_wait` (kevent, no timeout) in `recv!` | root's socket readable, or a watched child's exit | BLOCKS by design: it is the server's one idle wait. Harmless while the server hosts no other fiber; stops them all if it does | make it two parks: `poller/wait-ready fd :read` for the socket and row 12 for the exits; `lib.clj:60-63` `kq-new/kq-watch/kq-wait` and `adopt` go | `native/probe.c:49-70` is kqueue only; Linux needs epoll plus pidfd or signalfd |
| 26 | `fork_server.clj:23` `reaped` `c-waitpid pid 0` after `NOTE_EXIT` | nothing, the zombie is there | SHORT | none | none |
| 27 | `fork_server.clj:68` stop: `c-kill 9` then `c-waitpid` on every child | each child's kernel teardown after SIGKILL (tens of ms for a large CoW heap, estimated) | SHORT | none | none |
| 28 | `fork_server.clj:182` run-loop `send!` -> `sock/send!` (events to root) | root's reader thread draining | BLOCKS (bounded by root's `start-event-reader!` thread, `fork_server.clj:170`) | same fix as row 5 | as row 5 |
| 29 | `fork_server.clj:73-90` `:tx` and `:fork`: `d/commit-as-db` (rows 13-16), `program/load-changes!`, `reach-update`, `lib/static!` (a collection) | compute and mmap reads | SHORT (the file says static! is 6 ms when unmoved) | none | none |
| 30 | `fork_server.clj:46` `fork-carrying!` -> `lib/fork!` | as row 11 | SHORT | none | none |

Counts (30 rows; row 21 is a constants row, not a call): PARKS 4 (rows 1, 17, 19, 20), SHORT 18 (3, 4, 8, 9, 10, 11, 13, 14, 15, 16, 18, 22, 23, 24, 26, 27, 29, 30), BLOCKS 6 (2, 5, 6, 7, 25, 28), missing 1 (row 12).

## Outside the three processes (root; listed so they are not forgotten, not counted)

Root keeps threads (`:workload :mixed` workers, edge threads), so a block there stops one thread, not a carrier.

| Call site | Waits for | Class |
|---|---|---|
| `seon.model` -> http-client `net.clj:60,366` `getaddrinfo` (`:blocking`) | the resolver | BLOCKS (no async variant in the library) |
| `net.clj:283` `await-connect` -> `poll-fd` (`poll(2)` called directly, not the poller) and the untimed blocking `connect` (`:315-327`) | the TCP handshake | BLOCKS (the fix of today's stall covers `recv-bytes` only, `net.clj:485-568`) |
| `net.clj:585` `send-bytes` (blocking socket) | the send buffer | BLOCKS (rare: requests are small) |
| `net.clj:540` `recv-bytes` | response bytes | PARKS on a fiber (`await-readable!`, MSG_DONTWAIT) |
| TLS (`tls.clj`) | none: in-memory BIOs, ciphertext moves through `net.clj` | SHORT (compute) |
| `fork_server.clj:226` `release!` `c-waitpid` of the server, and `:171` reader `sock/recv!` | the server's exit; root's socket | BLOCKS root's proc thread (declared `:io`) |
| `db.clj:130` `pdb/sync!` -> `mdb_env_sync` (`writer.clj:296,311,350`) | fsync | BLOCKS (~5-10 ms per the comment at `db.clj:314`), root's writer |
| `vector.clj:176,178` llama.cpp open and batch | CPU | BLOCKS (compute, root's `:embed` worker) |
| `fx.clj` `sh/sh` (jolt `shell.clj:108-116` makes `future`s, real threads today) | child | PARKS on a fiber |
| `watch.clj:60-90` ProcessBuilder stream read, `.join` | file events | PARKS on a fiber; a thread blocks only itself today |
| `agent_proc.clj:67` `alts!!` with `timeout` | channel | PARKS on a fiber |

## BLOCKS rows, most dangerous first

1. Row 7, `lib/line-reader` `poll1(-1)` in `run-tests-forked` (`agent.clj:648`, `lib.clj:121`). Holds the agent for every test run, and a test run is the longest wait an agent makes. Fix: `poller/wait-ready` plus a nonblocking read. Portable.
2. Row 5/6/28, `sock/send-frame!` / `write-all!` / `sendmsg` (`sock.clj:236,277`), every message to root, from the agent, the test child and the fork server. Blocks only on a full buffer, but a root that is slow to drain stalls the whole process. Fix: `MSG_DONTWAIT` and `wait-ready :write`, the twin of `recv!`.
3. Row 25, the fork server's `kq-wait` (`fork_server.clj:157`). Its idle wait. Needs a child-exit wait in the poller (row 12) before the server can host any other fiber. kqueue only today.
4. Row 2, the blocking `recvmsg` arm when `park?` is false: all of Linux today, until Linux runs on the main carrier.
5. Missing row 12, a fiber-aware wait for a child's exit. Needed by rows 9 and 25 to be correct and not merely short. Also removes the polled `Process.waitFor`.

Linux needs, in order: signalfd or a self-pipe for `wake-on-signals!`, pidfd (or signalfd for SIGCHLD) for exits, the socket and `fcntl` constants of Linux, a replacement for `FD_CLOFORK`, and `native/probe.c` without kqueue.

## What docs/flow.md should say about a blocking foreign call in a main-carrier-only process

- In a process that runs only on the main carrier, a foreign call that waits for something outside holds the one thread. Nothing else in the process runs: no fiber, no timer, no io poller round. `:blocking true` does not change that, it only makes the call collect-safe.
- Allowed: calls that park (the Jolt waits in the table above, `poller/wait-ready` for any socket or pipe fd, set nonblocking) and calls bounded by their own work (an mmap read, a local file, a short LMDB commit), with the estimate or measurement next to the call.
- Not allowed: `read`, `write`, `recv`, `send`, `accept`, `connect`, `poll`, `kevent`, `waitpid` and `getaddrinfo` on a path that waits for another process or the network. Make the fd nonblocking and park on `wait-ready`, or wait for an exit through the poller's child-exit wait. Otherwise declare a thread (the proc's `:workload :mixed`) and keep that process out of the forking set, because a program thread makes `fork-alone` refuse.
- A tick budget does not stop a foreign call. Only a signal does (EINTR), so every wait loop retries EINTR and the process needs a signal wake source: kqueue `EVFILT_SIGNAL` on macOS, signalfd on Linux.
- `Thread.start`, `future`, `<!!`, `deref` and `Thread/sleep` are safe (they park) once they run as fibers; a blocking foreign call inside one of them is still the process's problem, not the fiber's.
