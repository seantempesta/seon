# core.async on Jolt

core.async on Jolt is one waiter protocol over Chez mutexes, condition variables and fibers, with no go thread pool and no cap on pending ops; this page states what each form costs and loses, read from the pinned Jolt source (`reference-code/jolt`).

The source is the pinned Jolt's: `reference-code/jolt/host/chez/java/async.ss`, `reference-code/jolt/host/chez/java/fibers-async.ss`, `reference-code/jolt/host/chez/java/host-static-methods.ss`, `reference-code/jolt/host/chez/fibers.ss` and `reference-code/jolt/stdlib/clojure/core/async.clj`. Each claim names the function or comment that carries it. [flow](flow.md) builds on this page.

## The model
- **A channel is a Chez mutex and condition variable with a FIFO** (`reference-code/jolt/host/chez/java/async.ss`, its header). Its ops are legal anywhere, inside or outside `go`. This is a superset of the JVM: there is no go thread pool and **no cap on pending ops**.
- **There is one waiter protocol, with three ways to wake.** A pending op is an `alt-handler` (`reference-code/jolt/host/chez/java/async.ss`). A thread waiter is woken by a condition variable; a fiber waiter by putting the fiber on its carrier's run queue (`alt-deliver!`); a `put!`/`take!` callback waiter by running its callback on a fiber (`async-callback-waiter`). A fiber's `<!` registers on the same `alt-takers` list as `alts!` (`reference-code/jolt/host/chez/java/fibers-async.ss`, its header).
- **`<!` `>!` `<!!` `>!!` dispatch on the caller** (`reference-code/jolt/host/chez/java/fibers-async.ss`). On a fiber they **park**; on an OS thread they **block** on the condition variable. `<!`/`>!` and `<!!`/`>!!` differ only in whether `Thread.interrupt` reaches them: the `!!` forms are interruptible.
- **Fibers and carriers** (`reference-code/jolt/host/chez/fibers.ss`, its header):
  - Fibers run on a pool of N carriers, where N is the processor count unless ``*fiber-carrier-count*`` pins it.
  - A fiber costs about 3.5 KB once it parks.
  - **A fiber is bound to its carrier for life**, placed round-robin at spawn, with no work stealing. A blocking foreign call strands every fiber on that carrier.
  - Preemption is on by default, at about 0.45 ms (``*fiber-preempt-ticks*``'s comment in `reference-code/jolt/host/chez/java/async.ss`).
  - **What parks:** channel ops, `deref` of a promise or future, `jolt.socket` IO, subprocess pipe IO, and **`Thread/sleep`**, whose deadline goes on the timer (`reference-code/jolt/host/chez/java/host-static-methods.ss`, the `Thread/sleep` comment). **What pins a carrier:** a raw fd read, or an FFI call that blocks. The comments above `thread-call` and `io-thread` in `reference-code/jolt/stdlib/clojure/core/async.clj` still name `Thread/sleep` as pinning; that is a Jolt docstring defect.

## Channels and buffers

| form | what a put does when full | loses data? |
|---|---|---|
| `(chan)` | a rendezvous: the putter waits for a taker | no |
| `(chan n)`, `(buffer n)` | parks or blocks | no |
| `(sliding-buffer n)` | never waits; **drops the oldest** | yes, silently |
| `(dropping-buffer n)` | never waits; **drops the newest** | yes, silently |
| `(promise-chan)` | keeps the first value; every later put **answers true and is dropped**; every take peeks the one value | later values |
| `(chan n xf ex-handler)` | the transducer runs **on the put**, under the channel lock; a throw puts `(ex-handler e)` if non-nil; a `reduced` result closes the channel | per the xform |

Closing and nil:
- **`close!`** is idempotent. A put on a closed channel answers `false`. Buffered values still drain, then a take answers `nil`.
- **Putting `nil` throws** `IllegalArgumentException` (`async-check-put!`).

## Ops

| op | on a fiber | on an OS thread | cost / note |
|---|---|---|---|
| `>!` `<!` `>!!` `<!!` | park | block (condition variable) | inside `go`, a park the CPS pass can see costs no continuation capture (`reference-code/jolt/stdlib/clojure/core/async.clj`) |
| `offer!` / `poll!` | never wait; `nil` if they can't | same | |
| `alts!` `alts!!` | park / block on all ports at once | same | a fast pass, then handler registration, no poll loop; `:priority true` takes the first ready port in order; `:default` answers at once |
| **`put!` `take!` that can't complete at once** | a callback waiter on the channel | same | `jolt-async-put!`, `jolt-async-take!`: the pending op is a handler registered under the channel's mutex, so pending puts complete in order; its callback runs on a fiber. No thread per pending op, and a put or take nobody completes holds nothing up. |
| `timeout ms` | a channel the **one timer thread** closes at ms | same | `jolt-async-timeout`. All deadlines share one thread and a min-heap: O(log k) to arm, O(1) to cancel. 100 pending timeouts → +1 thread, once per process. |

## go, thread, io-thread
- **`go` / `go-loop`** spawn on the backend ``*go-backend*`` names at spawn time. The default is **`:fiber`** (`reference-code/jolt/host/chez/java/async.ss`, the ``*go-backend*`` comment). A fiber go costs 2.2 µs and 6 KB to spawn; a `:thread` go costs 8 µs and 87 KB. 100 go blocks parked on a channel add no OS thread.
- **`thread`** is always an OS thread.
- **`io-thread`**, or `(thread-call f :io)`, is always a fiber. `:mixed` and `:compute` are OS threads (`thread-call`).

## The library over the primitives

The library forms in `reference-code/jolt/stdlib/clojure/core/async.clj` are built on the ops above and inherit their costs.

- **`pipe`** is a `go-loop`.
- **`pipeline` and `pipeline-blocking` start `n` OS threads** (`thread`, in `pipeline*`). `pipeline-async` uses go-loops.
- **`mult`** delivers each value with `put!` to every tap and waits for all of them. So one slow tap holds back every tap; a full tap holds a callback waiter, not a thread. **With no taps, the value is dropped.**
- **`pub`/`sub`** is built on mults: a topic nobody subscribes to is dropped.
- **`mix`, `merge`, `split`, `reduce`, `into`, `onto-chan!`, `to-chan!`** are go-loops over the primitives.

## Idioms for Seon
- **Dependency:** park on a channel, with `<!` on a fiber. Never a sleep, a poll or a timeout standing in for the thing you wait for.
- **Backlog and admission:** a fixed `(chan n)` is the queue. A full one parks the producer, and that is the back-pressure. Sliding and dropping buffers are only for values where the newest subsumes the rest (a tick, the latest commit, a view). **Never use them for data that must arrive.**
- **Delayed retry:** `(go (<! (timeout ms)) (>! retry-ch m))`. That is one heap entry on the one timer thread plus a parked fiber, about 3.5 KB. No cron proc and no thread.
- **Request and answer:** carry `:reply` (a channel, or a Flow coord) in the request. **Credit:** an asker keeps at most N requests outstanding and its answer channel holds N, so an answer never parks; without credit, two bounded queues in a request/answer cycle deadlock. A `promise-chan` per request works when exactly one answer is wanted.
- **Failure:** a throw inside a `go` kills that fiber. Its result channel closes, and `jolt.fibers/monitor!` and `join` see the throwable. Answer errors as values on the reply channel.
- **Quiet:** core.async alone cannot see it. Count messages (see [flow](flow.md#idioms-for-seon)).
