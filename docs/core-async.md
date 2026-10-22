# core.async on Jolt

What core.async is on Jolt, read from the source (`reference-code/jolt`, Jolt `9e42a0d8`) and checked in the REPL. Every claim names its file and line. `[A..]` is a check in `../flowlab/docs_check.clj`; run it with `flowlab/run.sh docs_check`, which needs n ≤ 100 and ends `DOCS-CHECK OK`.

Paths: `async.ss` and `fibers-async.ss` are under `host/chez/java/`, `fibers.ss` under `host/chez/`, and `async.clj` under `stdlib/clojure/core/`.

## The model
- **A channel is a Chez mutex and condition variable with a FIFO** (`async.ss:74-76`). Its ops are legal anywhere, inside or outside `go`. This is a superset of the JVM: there is no go thread pool and **no cap on pending ops** (`async.ss:1-8`).
- **There is one waiter protocol, with two ways to wake.** A pending op is an `alt-handler` (`async.ss:33-49`). A thread waiter is woken by a condition variable; a fiber waiter by putting the fiber on its carrier's run queue (`alt-deliver!`, `async.ss:302-311`). A fiber's `<!` registers on the same `alt-takers` list as `alts!` (`fibers-async.ss` header).
- **`<!` `>!` `<!!` `>!!` dispatch on the caller** (`fibers-async.ss:314-317`). On a fiber they **park**; on an OS thread they **block** on the condition variable [A13]. `<!`/`>!` and `<!!`/`>!!` differ only in whether `Thread.interrupt` reaches them: the `!!` forms are interruptible (`async.ss:85-183`, `async.clj:50-56`).
- **Fibers and carriers** (`fibers.ss:1-40`):
  - Fibers run on a pool of N carriers, where N is the processor count (`fibers.ss:393`), 18 on this machine.
  - A fiber costs about 3.5 KB once it parks (`:20`).
  - **A fiber is bound to its carrier for life**, with no work stealing (`:31-38`). A blocking foreign call strands every fiber on that carrier.
  - Preemption is always on, at about 0.45 ms (`fibers.ss:780,818`).
  - **What parks:** channel ops, `deref` of a promise or future, `jolt.socket` IO, subprocess pipe IO, and **`Thread/sleep`**, whose deadline goes on the timer (`host-static-methods.ss:105-110`) [A16]. **What pins a carrier:** a raw fd read, or an FFI call that blocks. `async.clj:628` and `:655` still say `Thread/sleep` pins a carrier; that comment is stale.

## Channels and buffers

| form | what a put does when full | loses data? | source |
|---|---|---|---|
| `(chan)` | a rendezvous: the putter waits for a taker [A1] | no | `async.ss:525-541` |
| `(chan n)`, `(buffer n)` | parks or blocks [A2] | no | `async.ss:516-524` |
| `(sliding-buffer n)` | never waits; **drops the oldest** [A3] | yes, silently | `async.ss:214`, `:510` |
| `(dropping-buffer n)` | never waits; **drops the newest** [A4] | yes, silently | `async.ss:214`, `:510` |
| `(promise-chan)` | keeps the first value; every later put **answers true and is dropped**; every take peeks the one value [A5] | later values | `async.ss:512-515`, `:1316-1319` |
| `(chan n xf ex-handler)` | the transducer runs **on the put**, under the channel lock; a throw puts `(ex-handler e)` if non-nil [A19]; a `reduced` result closes the channel | per the xform | `async.ss:416-437`, `:486-505` |

Closing and nil:
- **`close!`** is idempotent. A put on a closed channel answers `false`. Buffered values still drain, then a take answers `nil` [A6] (`async.ss:459-475`).
- **Putting `nil` throws** `IllegalArgumentException` [A7] (`async.ss:937-941`).

## Ops

| op | on a fiber | on an OS thread | cost / note |
|---|---|---|---|
| `>!` `<!` `>!!` `<!!` | park | block (condition variable) | inside `go`, a park the CPS pass can see costs no continuation capture (`async.clj:27-45`) |
| `offer!` / `poll!` | never wait; `nil` if they can't [A8] | same | `async.ss:668-671`, `async.clj:600` |
| `alts!` `alts!!` | park / block on all ports at once | same | a fast pass, then handler registration, no poll loop (`async.ss:1119-1180`); `:priority true` takes the first ready port in order [A17]; `:default` answers at once [A18] (`async.clj:465-495`) |
| **`put!` `take!` that can't complete at once** | **an OS daemon thread each**, blocked until it completes [A9] | same | `async.ss:900-919`. 10 pending → +10 threads. No cap. This is the one op that doesn't use the waiter protocol; session THREADS is changing it to a callback waiter on a fiber. |
| `timeout ms` | a channel the **one timer thread** closes at ms [A15] | same | `async.ss:889-893`. All deadlines share one thread and a min-heap: O(log k) to arm, O(1) to cancel (`async.ss:673-874`). 100 pending timeouts → +1 thread, once per process [A14]. |

## go, thread, io-thread
- **`go` / `go-loop`** spawn on the backend `*go-backend*` names at spawn time. The default is **`:fiber`** since `93af2568` [A10] (`async.ss:943-995`, `async.clj:439-452`). A fiber go costs 2.2 µs and 6 KB to spawn; a `:thread` go costs 8 µs and 87 KB. 100 go blocks parked on a channel add no OS thread [A12].
- **`thread`** is always an OS thread (`async.ss:1305-1311`, `:1014-1030`) [A11].
- **`io-thread`**, or `(thread-call f :io)`, is always a fiber. `:mixed` and `:compute` are OS threads (`async.clj:631-656`) [A11].

## The library over the primitives (`async.clj`)
- **`pipe`** (`:662`) is a `go-loop`.
- **`pipeline` and `pipeline-blocking` start `n` OS threads** (`thread`, `:699`) [A20]. `pipeline-async` uses go-loops (`:704`).
- **`mult`** (`:876-905`) delivers each value with `put!` to every tap and waits for all of them. So one slow tap holds back every tap, and with today's `put!` **a full tap costs an OS thread**. **With no taps, the value is dropped** [A21].
- **`pub`/`sub`** (`:1016`) is built on mults: a topic nobody subscribes to is dropped.
- **`mix`, `merge`, `split`, `reduce`, `into`, `onto-chan!`, `to-chan!`** are go-loops over the primitives.

## Idioms for Seon
- **Dependency:** park on a channel, with `<!` on a fiber. Never a sleep, a poll or a timeout standing in for the thing you wait for.
- **Backlog and admission:** a fixed `(chan n)` is the queue. A full one parks the producer, and that is the back-pressure. Sliding and dropping buffers are only for values where the newest subsumes the rest (a tick, the latest commit, a view). **Never use them for data that must arrive.**
- **Delayed retry:** `(go (<! (timeout ms)) (>! retry-ch m))`. That is one heap entry on the one timer thread plus a parked fiber, about 3.5 KB. No cron proc and no thread.
- **Request and answer:** carry `:reply` (a channel, or a Flow coord) in the request. **Credit:** an asker keeps at most N requests outstanding and its answer channel holds N, so an answer never parks; without credit, two bounded queues in a request/answer cycle deadlock. A `promise-chan` per request works when exactly one answer is wanted.
- **Failure:** a throw inside a `go` kills that fiber. Its result channel closes, and `jolt.fibers/monitor!` and `join` see the throwable. Answer errors as values on the reply channel.
- **Quiet:** core.async alone cannot see it. Count messages (see [flow](flow.md#idioms-for-seon)).
- **Until THREADS' fix merges:** don't leave `put!`/`take!` pending (that rules out fixed-tap mults, pub/sub, `put!` callbacks), and use `pipeline-async` (go) rather than `pipeline` when threads matter.

