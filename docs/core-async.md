# core.async on Jolt

core.async on Jolt is one waiter protocol over Chez mutexes, condition variables and fibers, with no go thread pool and no cap on pending ops; this page states what each form costs and loses, read from the pinned Jolt source: `reference-code/jolt/host/chez/java/async.ss`, `reference-code/jolt/host/chez/java/fibers-async.ss`, `reference-code/jolt/host/chez/java/host-static-methods.ss`, `reference-code/jolt/host/chez/fibers.ss` and `reference-code/jolt/stdlib/clojure/core/async.clj`. [flow](flow.md) builds on it.

## The model

How Jolt's core.async works underneath: channels, one waiter protocol, parking against blocking, and fibers on carriers.

- **A channel is a Chez mutex and condition variable with a FIFO** (`async.ss`). Its ops are legal anywhere, inside or outside `go`; there is no go thread pool and **no cap on pending ops**.
- **One waiter protocol, three ways to wake.** A pending op is an `alt-handler`. A thread waiter wakes by condition variable, a fiber waiter by going on its carrier's run queue (`alt-deliver!`), a `put!`/`take!` callback by running on a fiber (`async-callback-waiter`). A fiber's `<!` registers on the same `alt-takers` list as `alts!`.
- **`<!` `>!` `<!!` `>!!` dispatch on the caller** (`fibers-async.ss`): on a fiber they **park**, on an OS thread they **block**. The `!!` forms are the ones `Thread.interrupt` reaches.
- **Fibers and carriers** (`fibers.ss`): fibers run on N carriers, N the processor count unless ``*fiber-carrier-count*`` pins it. A parked fiber costs about 3.5 KB. **A fiber is bound to its carrier for life** (round-robin at spawn, no work stealing), so a blocking foreign call strands every fiber on that carrier. Preemption is on, at about 0.45 ms (``*fiber-preempt-ticks*``).
- **What parks:** channel ops, `deref` of a promise or future, `jolt.socket` IO, subprocess pipe IO, and `Thread/sleep` (its deadline goes on the timer). **What pins a carrier:** a raw fd read, or a blocking FFI call; use `thread` for those.

## Channels and buffers

What a put does when the channel is full:
- `(chan)`: rendezvous, the putter waits for a taker.
- `(chan n)`, `(buffer n)`: parks or blocks; loses nothing.
- `(sliding-buffer n)`: never waits; **drops the oldest**, silently.
- `(dropping-buffer n)`: never waits; **drops the newest**, silently.
- `(promise-chan)`: keeps the first value; later puts **answer true and are dropped**; every take reads the one value.
- `(chan n xf ex-handler)`: the transducer runs **on the put**, under the channel lock; a throw puts `(ex-handler e)` if non-nil; `reduced` closes the channel.

`close!` is idempotent; a put on a closed channel answers `false`; buffered values drain, then a take answers `nil`. **Putting `nil` throws** (`async-check-put!`).

## Ops

What each operation does on a fiber and on an OS thread.

- `>!` `<!` `>!!` `<!!`: park on a fiber, block on a thread. Inside `go`, a park the CPS pass can see captures no continuation.
- `offer!`, `poll!`: never wait; `nil` if they can't.
- `alts!`, `alts!!`: wait on all ports at once, a fast pass then handler registration, no poll loop; `:priority true` takes the first ready port in order; `:default` answers at once.
- **`put!`, `take!` that can't complete at once:** a callback handler registered under the channel's mutex (`jolt-async-put!`, `jolt-async-take!`), so pending puts complete in order and the callback runs on a fiber. No thread per pending op.
- `timeout ms`: a channel the **one timer thread** closes at ms (`jolt-async-timeout`): one min-heap for all deadlines, O(log k) to arm, O(1) to cancel.

## go, thread, io-thread

Which backend each of `go`, `thread` and `io-thread` runs on, and what it costs.

- **`go` / `go-loop`** spawn on the backend ``*go-backend*`` names at spawn time, **`:fiber`** by default: 2.2 µs and 6 KB to spawn, against 8 µs and 87 KB for `:thread`. Parked go blocks add no OS thread.
- **`thread`** is always an OS thread.
- **`io-thread`**, or `(thread-call f :io)`, is a fiber; `:mixed` and `:compute` are OS threads.

## The library over the primitives

The library forms in `reference-code/jolt/stdlib/clojure/core/async.clj` are built on the ops above and inherit their costs.

- **`pipe`**, `mix`, `merge`, `split`, `reduce`, `into`, `onto-chan!`, `to-chan!`, `pipeline-async`: go-loops.
- **`pipeline` and `pipeline-blocking` start `n` OS threads** (`thread`, in `pipeline*`).
- **`mult`** puts each value to every tap and waits for all, so one slow tap holds back every tap; a full tap holds a callback, not a thread. **With no taps, the value is dropped.**
- **`pub`/`sub`** is built on mults: a topic nobody subscribes to is dropped.

## Idioms for Seon

How Seon meets each need with channels.

- **Dependency:** park on a channel with `<!` on a fiber; never a sleep, poll or timeout standing in for the thing you wait for.
- **Backlog and admission:** a fixed `(chan n)` is the queue, and a full one parking the producer is the back-pressure. Sliding and dropping buffers only for values the newest subsumes (a tick, the latest commit, a view); **never for data that must arrive.**
- **Delayed retry:** `(go (<! (timeout ms)) (>! retry-ch m))`: one heap entry and a parked fiber.
- **Request and answer:** carry `:reply` (a channel or a Flow coord) in the request. **Credit:** an asker keeps at most N requests outstanding and its answer channel holds N, so an answer never parks; without credit, two bounded queues in a request/answer cycle deadlock. A `promise-chan` per request when exactly one answer is wanted.
- **Failure:** a throw inside `go` kills that fiber; its result channel closes and `jolt.fibers/monitor!` and `join` see the throwable. Answer errors as values on the reply channel.
- **Quiet:** core.async alone cannot see it; count messages ([flow](flow.md#idioms-for-seon)).
