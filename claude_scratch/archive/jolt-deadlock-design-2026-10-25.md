# Global deadlock detection in Jolt's scheduler: a design

Read-only research against `reference-code/jolt` at e1b72c1a (all paths below are relative to it unless they say otherwise; `~chez` means `reference-code/chez`). Nothing was edited, built or left running.

## 0. Two findings to read first

1. **This would not have caught the 1h45m hang.** That hang (`seon.agent-test/a-failure-marked-through-the-server-holds-the-task`, task `seon.root/a-wait-for-quiet-ends-when-quiet-is-past`) ran a root in-process, and a root's server has an edge thread blocked in `accept` (`src/seon/server.clj:263-272`, "blocking accept and reads"). A thread in a blocking accept or read is a live waker: a connection could arrive. Go reaches the same verdict, since a goroutine in `Accept` is a netpoll waiter. A pending cron timer has the same effect. So a global check only fires in processes that have no listener, no pending timer and no awaited descriptor: a test runner shard whose tests deadlock among themselves, a script, or an agent body. For a hung *root*, the producer has to answer the wait, which is the task's own fix (`seon.reconcile/answer-waits`). The detector's real value is in shards and agents. A per-wait check can see past listeners, and Go now ships one: the goroutine-leak profile, which runs a GC reachability pass in which a waiter whose primitive is unreachable from any runnable goroutine can never wake. It is opt-in (`GOEXPERIMENT=goroutineleakprofile`) in 1.26 and reported as becoming default in 1.27. That is a separate design (§9).
2. **Main-carrier mode already has the single-thread version, and it has a latent false positive.** `jolt-main-carrier-run!` (host/chez/fibers.ss:1638-1679) raises `"stuck: …"` at fibers.ss:1676 when nothing is queued, no timer is set and no descriptor is awaited. It ignores other OS threads. Futures and `Thread.`s still fork real threads in that mode (`jolt-main-thread` is read only in java/fibers-async.ss:512 and java/async.ss:858). So a fiber that derefs a running future under `main-carrier-run!` is declared stuck while the future computes. Seon's agents avoid this only because they stay one thread. The design below folds this path into the general counter (§5.6).

## 1. Every place a thread or fiber parks today

### OS threads
| Site | file:line | Wait | Its waker(s) |
|---|---|---|---|
| The one thread wait, `jolt-condition-wait` | host/chez/locks.ss:469-490 | `condition-wait`; jolt-cv branch counts `jolt-cv-threads` | `jolt-cv-wake!` locks.ss:588, `jolt-cv-signal-one!` locks.ss:623. `host/chez/lock-check.sh` refuses `jolt-cv-cond` anywhere else, so for a jolt-cv these are **statically the only wakers** |
| Thread arm of `jolt-cv-wait/park` (promise/future deref, agent await, join, latch, Object.wait, queue take/put, Future.get, awaitTermination, pipe read, process latch, main-job, Thread/sleep) | locks.ss:805-810 (indefinite at :810) | the above, on a jolt-cv | as above |
| Promise deref | java/concurrency.ss:339-344 (`jolt-promise-deref` → `jolt-cv-wait-interruptibly "promise deref"`) | interruptible | `jolt-deliver` :322 → `jolt-cv-wake!` |
| Future deref | java/concurrency.ss:218, 227 | interruptible | future completion → `jolt-cv-wake!` |
| `<!!`/`>!!` on a thread | java/async.ss:556-600 `ac-take` → `ac-take-wait` → `ac-wait!` :178 → `ac-cv-wait` :78 (`jolt-condition-wait` on `async-chan-cv`, a jolt-cv) | interruptible when `intr?` (the `!!` vars); `ac-intr-wait!` :134 registers in the interrupt table | `ac-broadcast!` :82 → `jolt-cv-wake!` |
| `alts!!` on a thread | java/async.ss:1262-1270 (`jolt-condition-wait wcv wmu`, handler's jolt-cv) | interruptible via `ac-intr-wait!` | `alt-deliver!` → `jolt-cv-wake!` |
| Monitor enter (`locking`) | java/concurrency.ss:1384-1396 (`monitor-wait!`, monitor's jolt-cv from `make-monitor` :1283) | **not** interruptible (as on the JVM) | monitor exit → `jolt-cv-wake!` |
| Carrier idle, default pool | fibers.ss:1696-1705 (`jolt-condition-wait` on a **raw** Chez condition, :1703) | runtime | `jolt-fiber-enqueue!/locked` fibers.ss:496 (`condition-signal` on empty→non-empty); `jolt-fiber-pool-reset!` broadcast :1749 |
| Carrier idle, named pools | fibers.ss:1786-1796 (:1794) | runtime | enqueue; `jolt-fiber-pool-release!` :1843; shutdown :1858 |
| Main carrier loop | fibers.ss:1650-1679 | drains, runs timers, `jolt-main-io-wait` (kevent) or `sleep` until the next deadline, else raises "stuck" | none: it is the only thread |
| Timer thread | java/async.ss:825-835 `timeout-collect-due!`: indefinite `jolt-condition-wait timeout-cv` when the heap is empty (:831), timed when a min exists (:833); raw condition | runtime | `jolt-timer-at!` :852 (`condition-signal` when the new entry is the min) |
| Pending timers | java/async.ss:738-745: min-heap `timeout-heap`, `timeout-heap-n`, `timeout-heap-dead` (lazy cancels), guarded by `timeout-mu` (a leaf) | live timers = `n - dead`; a dead min still makes the timer wait *timed* until it pops | |
| I/O poller thread | stdlib/jolt/io_poller.clj:588-597 `poller-loop` → `poller-round` :373, blocking `c-kevent` :449 (`:blocking`, i.e. `__collect_safe`) | foreign call | the pipe (`pipe-write!` :305, from `commit-wait!` :827) and kernel events |
| Awaited I/O | io_poller.clj:712 `awaited?`: `:pending`, `:signals`, any `:fds … :waiters`. Thread fd waits are in `:threads` and block on their own kq (`kevent-wait!`) | | |
| Shutdown watcher | java/concurrency.ss:5198-5228, `c-sigwait` (`jolt-foreign-proc-blocking`) at :5213, forked by `fork-thread-dormant` (lazy-bridge.ss:396) | foreign call | a signal: runs the hooks then `_exit`, **or** `jolt-sigint-trip!` when `jolt-sigint-target` (:5142) is set, which wakes waits |
| Exit wait | java/concurrency.ss:447-456 `await-exit-holds!` on raw `exit-cv` (:451-452 indefinite, :454 timed) | runtime | `exit-nondaemon-leave!` lazy-bridge.ss:337, `exit-release-pools!` |
| Executor worker idle | java/concurrency.ss:3040-3053 (st 3 is a jolt-cv, :2924) | runtime | `jolt-cv-signal-one!` |
| Tap loop | java/concurrency.ss:907 (raw `make-condition`, :869) | runtime | `tap>` signal |
| Main-queue pump | java/concurrency.ss:4868 (raw `jolt-main-queue-cv` :4702) | runtime | job enqueue |
| Loader wait | loader.ss:2805, **timed** (15 s slice, `ldr-wait-slice` :2723) | counts as a waker (timed) | |
| Thread/sleep | java/host-static-methods.ss:118-127, `jolt-cv-wait-interruptibly` with a deadline | timed | |
| `jolt-pause-ms` (runtime poll loops) | locks.ss:669 | timed | |
| Mutex acquire | locks.ss:206-216 `jolt-mutex-acquire!`: tries first; contended with interrupts on, a blocking `mutex-acquire`; off, spins on `jolt-collect-safe-pause` | kernel or foreign wait | the holder; it **cannot be woken with an error** |
| Collect rendezvous | locks.ss:505 `jolt-stop-the-world-wait`, timed, inside a running thread | | |
| Blocking foreign calls | scheme-adapter-runtime.ss:878-880, 916-919 (`foreign-procedure __collect_safe`); rt.ss:126 `jolt-foreign-proc-blocking`; `ffi/defcfn … :blocking` | Chez deactivates the thread (for the GC); **Jolt keeps no record of it** | external |

### Fibers
- **Commit points** (state → `'parked`): `jolt-fiber-commit-park!` fibers.ss:1124 and `/ibox!` :1093 (both under the carrier mu); `jolt-cv-wait/park`'s fiber arm locks.ss:802, `monitor-wait!` concurrency.ss:1389 and the loader at loader.ss:2798 (all three under the *waiter's* mu, then `jolt-fiber-switch-for-park?` fibers.ss:1141 under the carrier mu); cheap parks in java/sm.ss.
- **Switch**: `jolt-fiber-to-scheduler!` fibers.ss:597. **Resume**: `sa-fiber-resume` fibers.ss:687 and `jolt-fiber-iwait-wake!` :1112, both under the carrier mu.
- **Waking a parked fiber with an error already exists**: `jolt-fiber-interrupt! f throwable` fibers.ss:1159/1164 resumes a fiber parked anywhere: a generic park, an alt handler (via `alt-claim!`) or a lock-wait. The fiber raises the throwable at `jolt-fiber-check-interrupt!` :1202, which every park site calls on return (`jolt-lock-wait/abandon` locks.ss:370-393, `jolt-fiber-park!` :657). A masked fiber keeps it pending.
- **No registry of live or parked fibers.** A parked fiber is reachable only from what it waits on.

## 2. Is there already one place that knows the counts?

No. The places that exist:
- **Chez's `$active-threads`** (~chez/c/thread.c:185, 274, 299, 432). It counts threads not deactivated. A thread blocked in `condition-wait` is deactivated (~chez/c/thread.c:701-703), but so is a thread in any `__collect_safe` call, so a deadlocked waiter and a thread blocked in `read` look the same. It cannot tell "can be woken" from "cannot be woken". It is also read under `$tc-mutex`. Not usable as the counter.
- **`live-threads` / `exit-nondaemon-live`** (lazy-bridge.ss:322-380). Every thread the runtime forks goes through `%ls-fork-thread` (lazy-bridge.ss:342; the only raw `fork-thread` is `%ls-orig-fork-thread` :341). That is **the one birth/death site**, and the thread-count half belongs there.
- **Per-thread wait state**: `thread-state-box` locks.ss:442 (a box per thread, written on every wait by `call-with-thread-state` :459, enumerable through `thread-state-boxes` under `thread-state-mu`). It is the existing per-thread record a report can read.
- **Interrupt registry**: `jolt-interrupt-waits` locks.ss:858 (box → the `(mu . cv)` or procedure entries of every *interruptible* wait in progress), `jolt-interrupt-owners` (fiber boxes), `jolt-interrupt-wake-waits!` :916. This is **the existing "wake this waiter, whatever it waits on" mechanism**, and its race argument is already written and gated (locks.ss:565-583, java/async.ss:86-160).

GC sweeper threads are not Scheme threads (~chez/c/gc.c:133, "the sweepers threads are not the same as Scheme threads"). They are not in `S_threads` and are invisible to everything here.

## 3. The design: Go's `checkdead` on a running count, with token handoff on wake

Go's shape (runtime/proc.go `checkdead`, called when an M goes idle or exits): `run = mcount - nmidle - nmidlelocked - nmsys`. When `run == 0`, a timer on any P returns early, otherwise it throws. An M that blocks in netpoll because there are netpoll waiters is not idle, and a waker hands the running slot to the M it wakes (`startm` takes the M off the idle list before `notewakeup`). Jolt gets the same two properties.

### 3.1 The counter
`dl-running`, one word in foreign memory, changed only by Chez's atomic `ftype-locked-incr!` / `ftype-locked-decr!`. Per CSUG threads.stex:466-477, both are atomic and "return #t if the new value is 0". **The thread whose decrement reaches 0 is the detector.** No global mutex sits on any wait path. java/async.ss:115-160 shows that a global mutex on every channel wake was measured and removed once (#1311), so it must not come back.

It counts Scheme threads that are running, or that have been woken and will run:
- **+1** for the primordial thread (initial value 1), and in `%ls-fork-thread`, **in the parent, before the fork** (so a child never exists uncounted).
- **−1** in the child's exit after-thunk (lazy-bridge.ss, beside `exit-nondaemon-leave!`). This is a transition and may detect.
- **Dormant threads are not counted** (`fork-thread-dormant`, i.e. the shutdown watcher: "runs no jolt code until a signal arrives"). Its one wake of waits, `jolt-sigint-trip!`, is covered by the `jolt-sigint-target` condition below; its other path exits the process.
- **−1 on entering a counted wait, +1 on leaving it**, with the handoff below.

### 3.2 What counts as a wait (idle) and what counts as running
- **Idle = an indefinite wait on a jolt-cv in `jolt-condition-wait`** (the `(cv mu)` arity, locks.ss:469). Under the existing "every thread wait goes through jolt-condition-wait" rule this one site covers every user wait in §1 and the jolt-cv runtime idles (executor).
- **The five raw-condition idles become jolt-cvs**, so they fall under the same rule: the carrier cv (fibers.ss:439-460, 1801-1810), `timeout-cv` (java/async.ss), `exit-cv` (lazy-bridge.ss:321), the tap queue cv (concurrency.ss:869), `jolt-main-queue-cv` (:4702). Their `condition-signal` / `condition-broadcast` become `jolt-cv-signal-one!` / `jolt-cv-wake!`. Every one is called with its mu held already, and no fiber waits on any of them, which is `jolt-cv-signal-one!`'s stated precondition. After this, the accounting lives in three functions in locks.ss and nowhere else.
- **The poller** marks itself idle around `c-kevent` only when `(not (awaited? st))`, under `pm`. That is Go's "an M in netpoll with waiters is not idle". `pipe-write!` from `commit-wait!` (under `pm`) hands it a token if it is idle. Two host functions, exposed through `jolt.host`, do this.
- **Everything else is running**: timed waits (a deadline is a waker, as a sleeping goroutine's timer is), blocking and non-blocking foreign calls, contended `mutex-acquire`, the loader's sliced wait, the collect rendezvous. **Unknown = running** gives the safety direction: anything Jolt cannot see costs a missed detection (false negative), never a false raise.
- **Timers need no separate test.** With a pending live timer, the timer thread is in a *timed* wait, so it is running. A heap of only cancelled entries keeps the timer timed until the dead min pops; then it waits indefinitely and the count can reach 0. That is an event, not a clock.

### 3.3 Handing over on wake (what makes the count exact)
A broadcast cannot tell its waker whom it woke, and without a handoff, a waker that wakes B and then parks itself drives the count to 0 before B has run. That false positive is the race that would sink a naive counter. A jolt-cv therefore grows two fixnums beside `threads` (locks.ss:285-291), all read and written under its mu as `threads` already is:
- `sleepers`: counted waiters that gave up their running slot and have not been handed one back;
- `tokens`: slots handed to waiters that have not yet returned.

```
jolt-condition-wait (cv mu), jolt-cv, no deadline:   [mu held]
  threads++ ; sleepers++
  when (ftype-locked-decr! dl-running) → (dl-detect! who)   ; we were the last runner
  (condition-wait (jolt-cv-cond cv) mu)
  if tokens > 0: tokens--                    ; a waker counted us already
  else: sleepers-- ; (ftype-locked-incr! dl-running)   ; spurious or uncounted wake
  threads--
  ;; the leave half runs in call-with-thread-state's after-thunk, so a non-local escape keeps the count right
jolt-cv-wake! cv:        [mu held]  k = sleepers; sleepers = 0; tokens += k; dl-running += k   (then the existing broadcast and fiber resumes)
jolt-cv-signal-one! cv:  [mu held]  if sleepers > 0: sleepers--; tokens++; dl-running++     (then the signal)
```
Spurious wakes are self-counting. Signal-one is exact whichever waiter pthread releases: either the first to return takes the token, or the later one counts itself. Timed waiters never touch `sleepers`. Adding k is one `ftype-locked-incr!` per token, or one `$fptr-locked-` add loop; k is the number of threads actually woken, so it is the work the wake already does.

### 3.4 The exact condition, checked only by the detector
At the decrement that returns #t, `dl-detect!` takes `dl-mu` (a leaf, held only here and at reaper start and end) and declares a deadlock iff **all** of the following hold:
1. `dl-running` is still 0 (re-read under `dl-mu`; a concurrent handoff may already have raised it);
2. no reaper is running (`dl-reaping` is #f);
3. I/O is not awaited: the poller's `awaited?`, installed as a host hook the way `jolt-main-io-wait` is (fibers.ss:1635). If the poller never started, nothing is awaited;
4. `(unbox jolt-sigint-target)` is #f (java/concurrency.ss:5142): a REPL evaluation can be freed by ^C, so a deref at the REPL is never declared;
5. no `:collect-safe` callable has been registered (`jolt-ffi-register-callable!` java/ffi.ss:1408 sets a flag). A foreign thread can call back into Scheme and deliver; this is Go's cgo `extraM` case;
6. **Chez agrees on the thread count**: `(length ($thread-list))` (under `$tc-mutex`, ~chez/s/cpprim.ss:3734) equals the counted threads plus the dormant ones. A thread forked by raw Chez `fork-thread` (reachable through `jolt.scheme/eval-string`, which Seon uses) is invisible to the counter, and this one comparison at the rare moment of detection turns that blind spot into a refusal. It needs a second, plain count of counted threads kept beside `dl-running` in `%ls-fork-thread`.

If any condition fails, nothing happens and the detector waits as usual. **If nothing changed, nothing is done**: the check runs only on a running→idle transition or a thread exit that reaches 0. There is no timer and no poll.

### 3.5 Waking every parked wait with the error
The detector holds its own wait's mu and cannot safely take other waiters' mutexes (a waiter may hold one mutex across its wait on another). So the detector does not wake anyone itself:
- Under `dl-mu` it sets `dl-reaping`, adds +1 to `dl-running` on the reaper's behalf, and forks one daemon thread, `jolt-deadlock`, through the counted `fork-thread/daemon`. Then it **carries on into its own condition-wait like everyone else**. Its interrupt registration already exists, so it is woken the same way.
- The reaper holds no lock. It:
  1. **builds the report**: for every thread in a counted wait (`thread-state-boxes`, whose cell is extended to record `who`, passed down from `jolt-cv-wait/park`'s `who` and from async's `"<!!"`/`"alts!!"`), `{:thread <jolt-thread-name> :waits "promise deref" :state :waiting}`; for parked fibers (§5.5) `{:fiber … :waits …}`. Then `(jolt-ex-info "deadlock: every thread is waiting and nothing can wake them" {:jolt/deadlock true :parked [...]})` (rt.ss:1804);
  2. **delivers to every interruptible wait**: for each box with a non-empty entry set in `jolt-interrupt-waits`, and each owner box, it records `box → ex` in `dl-pending` (a weak eq table plus a "non-empty" fixnum), sets the flag, and calls the existing `jolt-interrupt-wake-waits!`. The waits then do what they already do for `.interrupt`: read and clear the flag, drop their registration, give back `takew`/`cvw` counts (java/async.ss:592-600, 1270-1290), and call `jolt-interrupted-throw!` (locks.ss:934). **That function is changed to throw `dl-pending`'s ex-info for the current box when one is there**, and an InterruptedException otherwise. One function, and every interruptible site (deref of a promise or future, `<!!`, `>!!`, `alts!!`, join, latch, Object.wait, queue take/put, Future.get, agent await, on threads or fibers) raises `:jolt/deadlock` with its cleanup already proven. The race argument for "flag set before registry read, waiter re-reads after registering" (locks.ss:565-573) covers a detector-time waiter that is between registration and `pthread_cond_wait`, because `jolt-interrupt-wake-waits!` takes the waiter's mu, so the broadcast lands only once the waiter is truly waiting;
  3. **interrupts parked fibers** with `jolt-fiber-interrupt! f ex` (needs §5.5);
  4. clears `dl-reaping` and exits. Its exit is a counted transition, so if the woken code re-parks into the same shape, a new detection (a new epoch) follows. That is event-driven, not clocked.
- **The ex-info unwinds to each waiter's caller**, as `.interrupt` does. A future's body gets it in its own thread, so `(deref fut)` on another thread sees it wrapped as that future's failure. A go body's handler reports it and closes its channel.
- **Not woken, but counted and reported**: non-interruptible waits (monitor enter `locking`, pipe read, process latch, main-job) and runtime idles (carriers, timer, poller, executor, tap, main-queue, exit wait). Runtime idles must never raise. They wake naturally when the fibers they carry are interrupted (the enqueue signals the carrier) or when the threads they wait for exit (exit wait). A `locking` lock-order deadlock is detected and reported but left parked, as on the JVM. Making `monitor-wait!`'s thread branch read `dl-pending` after its wake is a one-line follow-up if the owner wants it raised.

### 3.6 Mutexes: why `mutex-acquire` is not a park
A contended Chez `mutex-acquire` is a kernel wait Jolt cannot interrupt and cannot re-enter with an error. It stays counted as running: a raw-mutex lock-order deadlock is a false negative. That is the honest choice. Making it detectable would mean making every acquire a park on a jolt-cv, which is far outside this change. If the reaper blocks inside `jolt-interrupt-wake-waits!` on a mutex that a parked thread holds across its own wait, the waiters form a hold-and-wait cycle that no wake can unwind. The reaper then waits like they do, and the process is no worse than today. A try-first pass (`(mutex-acquire mu #f)` over every entry, then blocking for the rest) shrinks that window and is cheap to add.

## 4. The smallest set of changes (file:function)

| # | File: function | Change |
|---|---|---|
| 1 | host/chez/locks.ss: new `dl-*` block before `jolt-condition-wait` (:469) | `dl-running` and thread-count words (ftype), `dl-mu`, `dl-reaping`, `dl-pending`, `dl-detect!`, the hooks (`dl-io-awaited?`, `dl-foreign-callbacks?`), and `jolt.host/deadlock-state` (counts, for tests) |
| 2 | locks.ss:285-291 `jolt-cv` record, `jolt-make-cv` | add `sleepers`, `tokens` (new nongenerative uid; check `state-image.ss`'s jolt-cv stand-in) |
| 3 | locks.ss:469 `jolt-condition-wait` (jolt-cv, no deadline) | §3.3 enter and leave, plus an optional `who` |
| 4 | locks.ss:588 `jolt-cv-wake!`, :623 `jolt-cv-signal-one!` | hand over tokens |
| 5 | locks.ss:810 `jolt-cv-wait/park` | pass `who` down |
| 6 | locks.ss:934 `jolt-interrupted-throw!` | throw `dl-pending`'s ex-info for the current box when present |
| 7 | locks.ss:442/459 thread-state cell | record `who` beside the state |
| 8 | host/chez/lazy-bridge.ss:342 `%ls-fork-thread` | +1 in the parent before the fork (unless dormant), −1 and detect in the exit after-thunk |
| 9 | host/chez/fibers.ss: carrier construction :439-460, :1801-1810; `jolt-fiber-enqueue!/locked` :496; reset :1749; `jolt-fiber-pool-release!` :1843; shutdown :1858 | carrier cv → jolt-cv; signal → `jolt-cv-signal-one!`, broadcast → `jolt-cv-wake!` |
| 10 | host/chez/java/async.ss: `timeout-cv`, `timeout-collect-due!` :825, `jolt-timer-at!` :852 | jolt-cv; `jolt-cv-signal-one!` |
| 11 | host/chez/lazy-bridge.ss `exit-cv` and `exit-nondaemon-leave!` :337; concurrency.ss `exit-release-pools!`, tap queue :869/:907 and its signal, main-queue :4702/:4868 and its signal | jolt-cv and its wake functions |
| 12 | stdlib/jolt/io_poller.clj: `poller-loop` :588 / `poller-round` :449; `pipe-write!` :305; `ensure-started!` | idle bracket around `c-kevent` when not `awaited?`; token on registration; install the `awaited?` hook |
| 13 | host/chez/java/ffi.ss:1408 `jolt-ffi-register-callable!` | set `dl-foreign-callbacks?` (`:collect-safe` callables only, if the registration can tell) |
| 14 | host/chez/fibers.ss:1666-1677 main-carrier loop | §5.6 |
| 15 | host/chez/lock-check.sh | refuse raw `condition-signal`/`condition-broadcast` on the converted conditions, so a waker that skips the handoff (the one source of false positives) cannot come back |

That is about 15 small edits in 7 files, with locks.ss as the owner. It builds no second wait or wake mechanism: it reuses `jolt-condition-wait`, `jolt-cv-wake!`, the interrupt registry and `jolt-fiber-interrupt!`.

## 5. Notes per piece

5.1 **Cost** (to prove with work counts, not time): a thread's indefinite wait gains two atomic word operations and two fixnum stores under a mutex it already holds; a wake gains k atomic increments for k threads actually woken. There is nothing on a fiber park or resume (phase 1), on a timed wait, or on a settled deref (the decide answers before any wait). The benches to compare before and after are a thread `<!!`/`>!!` ping-pong and the 1000-taker fan-out (java/async.ss:119), plus bench/fibers for the carrier-cv swap.

5.2 **The poller race**: a fiber's `commit-wait!` puts `:pending` and writes the pipe under `pm` before it parks. The poller's idle mark is taken under `pm`. So when the parking carrier's decrement reaches 0, `awaited?` is already true. `process-events!` removes waiters and resumes outside `pm` while the poller is running, so it is never idle in between.

5.3 **Signals**: a Chez `register-signal-handler` handler runs at the next safe point, and a thread in `condition-wait` is not at one (concurrency.ss:5190-5194 says so). So it is not a waker in pool mode. In main-carrier mode, `wake-on-signals!` puts the signals in `:signals`, which `awaited?` reads.

5.4 **Loader**: its wait is sliced (15 s) and so counts as running. A thread waiting on a load blocks detection, which is a false negative. The loader has its own cycle check (loader.ss ~2812).

5.5 **Parked fibers (phase 2)**: fibers in interruptible waits are already reached by step 2 of the reaper (their boxes are in `jolt-interrupt-waits` or owned). Fibers parked in `<!`, `>!`, `alts!` or a monitor need a list. The proposal is an intrusive doubly linked "parked" list per carrier: two fields on `jolt-fiber` and a head on `jolt-carrier`. It is linked where the state becomes `'parked` under the carrier mu (`jolt-fiber-commit-park!` :1124, `/ibox!` :1093, `jolt-fiber-switch-for-park?` :1141 when still `'parked`, sm.ss's commit) and unlinked where it leaves under the carrier mu (`sa-fiber-resume` :687, `jolt-fiber-iwait-wake!` :1112, the take-back in :1141). Each switch then costs a few stores under a held lock; measure against the ~136 ns switch (locks.ss:93-96). Phase 1 without it already raises in every thread wait, so the main thread's `<!!` on a stuck go block raises, while the go block's own `<!` stays parked as on the JVM.

5.6 **Main carrier**: in the "nothing queued, no timer, no descriptor" branch (fibers.ss:1676):
- if the counted threads equal 1 (only main), `jolt-fiber-interrupt!` the program fiber `f` with the ex-info and loop. `f` raises from its park, its guard writes the box, and `main-carrier-run!` rethrows `:jolt/deadlock`. The second time round (`f` masked), it raises as today;
- if other threads exist, wait on carrier 0's cv (now a counted jolt-cv), which the global detector covers. This fixes the latent false positive in §0.2.

No reaper thread is forked in this mode, so the process stays one thread. Today's `test/chez/main-carrier-test.clj:58,122` asserts the "stuck" message and must change to `:jolt/deadlock`.

## 6. Risks

- **False negatives dominate, by design.** These keep detection off: listeners and blocking reads (every Seon root, §0.1); pending timers (cron, a `timeout` someone forgot); blocking foreign calls; contended raw mutexes; timed waits that loop (sliced waits are clocks deciding "still alive"); a sigint target; registered callbacks. **Any uncounted indefinite wait switches detection off for the process lifetime**, which is why the five raw conditions must be converted and the lint (row 15) added.
- **False positives come from exactly one source**: a counted wait woken without a handoff. Wakes of a jolt-cv are statically confined to `jolt-cv-wake!`/`jolt-cv-signal-one!` (lock-check.sh), and the converted raw conditions get the same lint. The unknown-thread cases are refused by conditions 4-6:
  - foreign callbacks (`dl-foreign-callbacks?`);
  - raw Chez threads (the `$thread-list` cross-check);
  - the shutdown watcher (dormant, plus `jolt-sigint-target`).
- **A thread blocked in a foreign read that Jolt doesn't know about** is counted running, so it is never a false positive. That is the "unknown = running" rule.
- **Chez's GC sweepers** are not Scheme threads and invisible (~chez/c/gc.c:133). The collect rendezvous waits are timed and happen in running threads.
- **Retry livelock**: code that catches everything around a deadlocked wait and retries gets the error on each round, and each round is a real park transition. It is the program's choice, as with a caught InterruptedException in a loop. Worth a sentence in the docs, not a mechanism.
- **The reaper is a thread forked at the moment of the deadlock.** It is daemon, counted, and short-lived. A process that must stay one thread (main-carrier) never forks it (§5.6).
- **Thread.interrupt semantics**: only boxes registered in an interruptible wait are flagged, and the wait consumes the flag, so no stray InterruptedException is left for a later sleep.
- **Fork**: `dl-running` lives in foreign memory. A forked child inherits it with the parent's count, so `jolt-after-fork-child!` must reset it to 1 and the thread count to 1, beside the poller reset (`after-fork!` io_poller.clj:748).

## 7. Tests (model: test/chez/nested-budget-test.clj; `check` accumulates failures, the last form prints `X OK` and exits 0/1)

Add `test/chez/deadlock-test.clj` to the Makefile `fibers:` target (Makefile:363-381, run as `bin/jolt run test/chez/deadlock-test.clj`, beside `main-carrier-test.clj` and `nested-budget-test.clj`). The file must start no socket and arm no timer before its first section, or detection is correctly off. A broken detector hangs this test, and the make target's timeout bounds it; the test has no clock of its own.

**Sketch A: everything parks, and it ends with `:jolt/deadlock`**
```clojure
(require '[clojure.core.async :as a])
(def failures (atom []))
(defn check [what ok? got] (when-not ok? (swap! failures conj (str what ": got " (pr-str got)))))
(defn outcome [f] (try (f) :finished (catch clojure.lang.ExceptionInfo e (ex-data e))))
(def base (jolt.host/deadlock-state))            ; {:running 1 :threads n} before anything

(let [e (outcome #(deref (promise)))]
  (check "a deref nothing can deliver raises :jolt/deadlock" (true? (:jolt/deadlock e)) e)
  (check "the report names the wait" (some #(= "promise deref" (:waits %)) (:parked e)) e))
(let [e (outcome #(a/<!! (a/chan)))]
  (check "<!! on a channel nobody puts to raises it" (true? (:jolt/deadlock e)) e))
(let [p (promise) q (promise)
      t (future (deref p) (deliver q 1))         ; each waits on the other
      e (outcome #(deref q))]
  (check "two threads waiting on each other: main raises" (true? (:jolt/deadlock e)) e)
  (check "and so does the other thread's wait"
         (true? (:jolt/deadlock (ex-data (.getCause (try @t (catch Exception x x)))))) t))
(let [e (outcome #(a/<!! (a/go (a/<! (a/chan)))))]
  (check "main's <!! on a stuck go block raises" (true? (:jolt/deadlock e)) e))
(check "the counts come back to where they started" (= base (jolt.host/deadlock-state)) (jolt.host/deadlock-state))
(check "the process carries on" (= 3 (+ 1 2)) nil)
```

**Sketch B: one live waker, and nothing is touched**
```clojure
(defn on-time [f] (= :ok (try (f) (catch clojure.lang.ExceptionInfo e (if (:jolt/deadlock (ex-data e)) :deadlock (throw e))))))
(check "a pending timer is a waker"
       (on-time #(let [p (promise)] (a/go (a/<! (a/timeout 200)) (deliver p :ok)) @p)) nil)
(check "a running thread is a waker"
       (on-time #(let [p (promise)] (future (dotimes [_ 30000000]) (deliver p :ok)) @p)) nil)
(check "a sleeping thread is a waker"
       (on-time #(let [p (promise)] (future (Thread/sleep 200) (deliver p :ok)) @p)) nil)
(jolt.ffi/defcfn c-usleep "usleep" [:uint] :int :blocking)
(check "a thread in a blocking foreign call is a waker"
       (on-time #(let [p (promise)] (future (c-usleep 200000) (deliver p :ok)) @p)) nil)
;; last, since it starts the poller: an awaited descriptor is a waker
(check "a fiber awaiting a pipe a child writes later is a waker"
       (on-time #(do (a/<!! (a/go (slurp (.getInputStream (.exec (Runtime/getRuntime)
                                   (into-array ["sh" "-c" "sleep 0.2; echo hi"]))))))
                     :ok)) nil)
(check "and none of them left the count off" (= base (jolt.host/deadlock-state)) (jolt.host/deadlock-state))
(if (empty? @failures)
  (do (println "DEADLOCK-TEST OK") (flush) (System/exit 0))
  (do (doseq [f @failures] (println "FAIL:" f)) (flush) (System/exit 1)))
```
The `deadlock-state` checks are the mutation-sensitive part. A missing token handoff, or a leave half skipped on an escape, shows as a drifted count even in runs where no false raise happened to land. A Scheme-level test in the style of `test/chez/fibers-interrupt-test.ss` should hammer the handoff: N threads in `<!!` on one channel, M puts with broadcast and signal-one, spurious wakes forced by `condition-broadcast` on a private cv, and the count asserted exact after every round. Update `test/chez/main-carrier-test.clj:58,122` for §5.6.

## 8. Open points for root
- The owner rulings needed: **a new key** (none planned; detection is always on, as the lock assertion is, locks.ss:91), **a new thread kind** (the reaper; the alternative is running the wake as a due thunk on the timer thread, which reuses a thread but blurs "no timer"), and a **new ex-data key** `:jolt/deadlock`.
- This is a Jolt change, so it goes on a `jolt/<topic>` branch of `seantempesta/jolt`.

## 9. Alternative, not designed here: per-wait (reachability) detection
Go's goroutine-leak profile uses a GC pass in which blocked goroutines' stacks are not roots: a waiter whose primitive is unreachable from runnable goroutines is permanently blocked. That catches the Seon-root case in §0.1 that a global check cannot. Chez offers guardians and weak pairs, but not a custom root set, so the Jolt equivalent would have to make the waiter lists hold waiters weakly and ask a guardian. That is a research project, not a scheduler edit.

Sources: Go 1.26 release notes, https://golang.google.cn/doc/go1.26 ; Go blog, https://go.dev/blog/goroutine-leak-profiles ; Go runtime/proc.go `checkdead` (summarized from the source).
