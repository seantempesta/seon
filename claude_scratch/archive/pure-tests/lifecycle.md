# lifecycle flake: what stop! answers before (2026-10-23)

`bin/check lifecycle` (seon.check/lifecycle, src/seon/check.clj:687-741) goes red now and
then in two ways. They have **two separate causes**, and neither is a sleep or a missed
bound in stop! itself. In each case stop! answers on an event that comes *before* the end
it claims:

| red check | what stop! waits for | what the check measures | where the gap is |
|---|---|---|---|
| `:threads-declared` with `{:os [22 24]}` | Thread.join / Flow's future `.get` (the body returned) | task_threads, the kernel's count | **dependency**: Jolt (and macOS) |
| `:every-stop-exited` with `:left [pids]` | each agent's socket EOF | `kill(pid, 0)` | **Seon**: fork_server.clj release! |

All scratch code is in
`/private/tmp/claude-501/-Users-sean-src-seon-jolt-next--claude-worktrees-repo-overview-prioritize-5dd84e/753730a3-f6a1-43a4-aa10-cbdcd3a6d09b/scratchpad/lifecycle/`
(`run.sh`, `burn.sh`, `window.clj`, `join.c`, `dead.c`, `zombie.py`).

## Reproduction

The command (`run.sh K`): `. bin/env`, then in `target/`:
`SEON_WEB_PORT=0 SEON_PROFILE=scripted SEON_STORE=<cp -c of check-store> SEON_MAIN=check-lifecycle-k nice -n 15 ./seon lifecycle 10`.
`bin/env` is required, or the binary cannot find liblz4.

- Machine load 50-68, no burners: 3 runs (30 cycles), all green. Stop p50 7.5 ms, p95 54 ms.
- The same load plus 4 `yes` burners of mine, each at nice 0: 5 runs (50 cycles). **Run 1 failed:
  cycle 4 `:stopped {:os [22 23]}`, so `:threads-declared false`.** The other 4 runs were green.
  Stop p95 was 30-67 ms in every run.
  So about 1 cycle in 50 under load, and 1 run in 5.
- `:left` did not show up in these 50 cycles. The `zombie.py` miniature below reproduces
  it (14 in 1000 under burners).
- The baseline's stop p95 of 1105 ms did not reproduce. My largest p95 was 67 ms. I have not explained it.

## Cause 1: threads. stop! "joins" a thread before the kernel has removed it

The `{:os [22 23]}` diff means the following. The named roster (Jolt's `live-threads`)
matched what root declares. The kernel's count (`e13_nthreads` = `task_threads`,
native/probe.c:79) was 1-2 higher. Those are threads that have left Jolt's roster but are
still in the task. stop! ends 19 threads per cycle:
- 17 `async-mixed-N`. These are Flow `:mixed` procs: one fresh thread per proc, from
  reference-code/jolt/stdlib/clojure/core/async/impl/dispatch.clj:47.
- `seon-flow-errors` (root.clj:736).
- `seon-fs-events` (fork_server.clj:122).

The diff cannot say which of the 19 lingered, because the kernel threads have no names.

What each wait actually waits for:
- **Flow stop**: `await-exits` does a `Future.get` on each proc's loop
  (flow/impl.clj:184-192, called at :309). The future completes *inside* the worker
  thread, before that thread ends.
- **Thread.join** returns when the body calls `jthread-finish!` (concurrency.ss:2208, run
  at :2290). After that, the thread still has to do the following, in order:
  1. leave `live-threads` (lazy-bridge.ss:362), which is the roster;
  2. run the exit hook;
  3. run Chez's `destroy_thread` (chez/c/thread.c:484-500);
  4. return from pthread;
  5. be torn down by the kernel.
- Chez creates every thread **detached** (chez/c/thread.h:77, `PTHREAD_CREATE_DETACHED`).
  So no pthread_join is possible at all.

Measured with `window.clj`, which runs on target/jolt/jolt and reads probe's
`e13_nthreads` right after the wait returns, 200 trials each:

| wait returned | thread still in roster | thread still counted by the kernel |
|---|---|---|
| `Thread.join` | 0 | 164 |
| `:mixed` executor task's result | 0 | 171 |

The lifecycle check is usually green only because stop! does more work after
`flow/stop` (`d/release`, `close-env!`) before cycles measures. Under load, a kernel
teardown outlasts that work.

**Which event would be the thread's end?** Measured with `join.c` and `dead.c` in C on this
Mac. The "load" columns add 4 burners.

| wait | still counted, idle | still counted, load |
|---|---|---|
| A: detached thread, condition signalled as its last act (today's Chez + Jolt) | 1781 of 2000 | 919 of 1000 |
| B: joinable thread + `pthread_join` | 888 of 2000 | 455 of 1000 |
| **C: Mach dead-name notification on the thread's kernel port** | **0 of 2000** | **0 of 2000** |

So on macOS even `pthread_join` returns before the kernel has removed the thread. That
means "make Chez threads joinable" alone is not the fix. The dead-name notification is the
event that follows the removal. On Linux, pthread_join (the kernel's CLONE_CHILD_CLEARTID
futex) is the equivalent.

**Fix (dependency: Jolt; waits for root's go).** The aim is a Thread's end that is the
kernel's end.
1. lazy-bridge.ss `%ls-fork-thread`, child's first act (about :350): on Darwin, call
   `mach_port_request_notification(mach_task_self(), mach_thread_self(),
   MACH_NOTIFY_DEAD_NAME, …)` to a receive port kept for that thread id.
   On Linux: create the thread joinable, which is a Chez change at thread.h:77, plus a
   `thread-join` primitive.
2. concurrency.ss "join" (:2323): after `jthread-alive?` is false, await that port's
   notification. This is a blocking foreign call, with the Chez thread deactivated
   while it waits. Then the port is freed.
3. dispatch.clj:47 and flow/impl.clj:184: Flow's `await-exits` joins the `:mixed` worker
   *Thread* (kept beside its future in `:running`), not only the future.
   `:io` procs run on carriers, so they are not affected.
4. Jolt test: the `window.clj` loop (start, join, then `task_threads` equals the
   baseline), 200 trials. Today it fails about 80% of trials; after the change it fails
   none.

Seon then needs no new code. It only drops its bounds, because a join becomes the exit
event:
- root.clj:1006: `(.join 5000)` becomes `(.join)`;
- fork_server.clj:170: `(.join 5000)` becomes `(.join)`.

**Proof in Seon:** `bin/check lifecycle` under 4 burners, `:threads-declared`. Before, 1 run
in 5 is red. After, it should be green in every run. Run it about 10 times once, not as a
loop. Also `seon.root-test/root-threads-are-what-root-declares` (the `threads-declared`
mode).

**Until the Jolt change merges:** the `:os` term in `seon.check/thread-diff`
(check.clj:485) measures a kernel teardown that nothing in Seon or Jolt can await today.
- Settling it by polling would be a §9 poll, so it is not an option.
- The honest interim is the owner's call: either keep the term and accept the flake, or
  drop `:os` from the per-stop comparison. Dropping it leaves the named roster, which stop!
  can promise after this change, and keeps `:os` in the running row.

## Cause 2: `:left`. root SIGKILLs the fork server before the server has reaped its agents

Agents are children of the fork server, not of root. The stop path:
1. api `:closing` sends `:drain` to each agent;
2. each agent proc sees its socket EOF, which root reads as `:ended`;
3. when `(:agents st)` is empty, the writer closes (root.clj:531);
4. `:drained` sends `:to-fs {:op :shutdown}`;
5. fork_server.clj:228-231 calls `release!`, which at :168 does `(c-kill pid 9)` on the
   server, then waits for it with `waitpid`.

The gap is in step 2 and step 5. An agent's socket EOF is not its exit. `_exit` closes the
descriptors (repl.clj:396) before the process becomes a zombie. If the server is
SIGKILLed in that window, the exiting agent is reparented to launchd. Nobody in root waits
for it, and `kill(pid, 0)` in cycles (check.clj:689) still finds it, so it shows up in
`:left`. (XNU reaps a dead parent's children that are *already* zombies at once. Only an
agent that is still exiting is left behind.)

The server's own reaping is a 100 ms poll: `recv-within! conn 100` produces `:tick`, which
calls `reap` (fork_server.clj:107, :51). Nothing ties it to shutdown.

`zombie.py` reproduces root's exact sequence in miniature: EOF of the agent, SIGKILL the
server, waitpid the server, then `kill(agent, 0)`. 1000 trials under 4 burners:

| sequence | agent still alive afterwards |
|---|---|
| **A, today**: SIGKILL the server | **14 of 1000** |
| **B, fix**: root closes its end; the server reaps every child (blocking) and exits; root waits for the server | **0 of 1000** |

Idle, both are 0 of 200. Each cycle stops 3 agents, so at about 1.4% per agent the
2026-10-23 baseline's cycles 0 and 8 fit.

**Fix (Seon, at the producer).** The server's exit becomes the end of every one of its
children, and root awaits the server's exit, not a signal it sent.

```clojure
;; seon.fork-server/server-step (fork_server.clj:47-49): track children, reap them all at stop
([{:keys [db conn]}] {:db db :mismatch 0 :conn conn :status-buf (ffi/alloc 4) :children #{}})
;; stop (root closed its end, or died): every child killed and reaped before this process
;; exits, so root's waitpid of the server is also the end of every agent and standby
([st tr] (when (= tr ::flow/stop)
           (run! #(lib/c-kill % 9) (:children st))
           (while (pos? (lib/c-waitpid -1 (:status-buf st) 0))))
         st)
;; :forked / :standby parents (:66, :78): (update st :children conj pid);
;; reap (:18): the reaped pids disj'd from :children by its callers
```

```clojure
;; seon.fork-server/release! (fork_server.clj:163-173): EOF, not SIGKILL; await the exit
[{:keys [pid fd reader] :as st}]
(drop-standby (:standby st)) (drop-standby (:standby-pending st))
(lib/c-shutdown fd 1)                          ; SHUT_WR: the server reads EOF, stops, reaps its children
(some-> pid (lib/c-waitpid (ffi/alloc 4) 0))   ; its exit, after every child's
(some-> ^Thread reader .join)                  ; its socket's EOF follows
nil
```

This also needs one line in lib.clj: `(defc c-shutdown "shutdown" [:int :int] :int)`. Its
return value goes to docs/failure-points.md beside `c-kill` and `c-waitpid`.

`main`'s `(lib/c-kill 0 9)` (:112) stays. It still ends group stragglers (an agent's
clones) and the server itself, but only after the reap.

What this removes:
- root's SIGKILL of a server that is still serving;
- the dependence on the 100 ms tick for the reap that matters.

The api's close condition (root.clj:531) needs no change: `:fs-down` now follows every
child's reap.

Caveats:
- A promoted server is not root's child, so `waitpid` answers ECHILD at once. Its end is
  then unobservable to root, the same as today.
- An agent's clones are children of the agent. They are killed by the group kill, but
  nobody waits for them. That is a separate task.

**Proof (red → green).** A deterministic `seon.root-test` deftest: *stop! answers after
every agent process is gone, even one that closed its socket and lives on.*
1. An agent evaluates code that closes its own socket fd and then blocks reading a pipe
   the test holds.
2. Root sees EOF, so `:ended`.
3. Call stop!.
4. Assert `kill(agent-pid, 0)` returns `-1`.

Today this is red every time: the agent is reparented and still alive. After the fix it is
green, because the server SIGKILLs and reaps it before exiting.

The statistical proof is `bin/check lifecycle` `:every-stop-exited` under burners, which
is the same run as cause 1's.

## What was ruled out

- Not a timeout in stop!: stop p95 in the failing run was 39 ms. The 3 s `::late` kill-all
  and the 15 s and 5 s bounds did not fire.
- Not GC sweepers: `gc-sweepers` was 0 in both the declared and the measured counts.
- Not the web server: port 0, no `clojure-agent-pool` threads declared or measured.
- Not a roster-level race: in 400 trials, 0 threads were still in the roster after the
  wait. That race is possible in the code, since jthread-finish! wakes the joiner before
  `live-threads` removal, but it was not observed.

Nothing of mine is running. Every burner was killed by PID, and the scratch stores were
deleted. The `./seon lifecycle` processes on the machine during this work belong to
`next-printer-eval`.
