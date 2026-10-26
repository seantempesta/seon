# core.async.flow on Jolt

Every Seon component is a Flow proc, and Jolt's Flow is upstream's API over Jolt's own `reference-code/jolt/stdlib/clojure/core/async/flow/impl.clj`; this page states the proc contract, where each workload runs, what the API really does and the hazards, read from the pinned Jolt source and checked in the REPL.

The source is the pinned Jolt's: `reference-code/jolt/stdlib/clojure/core/async/flow.clj` (the API), `reference-code/jolt/stdlib/clojure/core/async/flow/impl.clj`, `reference-code/jolt/stdlib/clojure/core/async/flow/spi.clj`, `reference-code/jolt/stdlib/clojure/core/async/flow/impl/graph.clj` and `reference-code/jolt/stdlib/clojure/core/async/impl/dispatch.clj`. Each claim names the function that carries it. Read [core-async](core-async.md) first.

## Where Jolt's Flow differs from upstream

Where Jolt's Flow departs from upstream's dev-flow-alpha.

- **`reference-code/jolt/stdlib/clojure/core/async/flow.clj` and `reference-code/jolt/stdlib/clojure/core/async/flow/spi.clj`** are upstream's `dev-flow-alpha`, plus `update!`.
- **`reference-code/jolt/stdlib/clojure/core/async/flow/impl.clj` is Jolt's** (its header comment):
  - each proc has its own control channel (there is no control mult);
  - outputs are routed when they are sent, by a resolver per pid reading a live table (`proc-resolver`);
  - `update!` diffs procs by fingerprint;
  - `stop` awaits every exit and finishes in-flight outputs;
  - Flow's own plumbing runs on fibers;
  - `:chan-opts {:in {:chan c}}` uses a channel you made (`make-chan`);
  - an output to a pid that isn't in the flow is reported, not thrown (`proc-resolver`).
- **The `update!` docstring is wrong** (a Jolt docstring defect). It promises `{:added :removed :rewired :running}`; the impl answers `{:stopped :started :kept :lost :missed-exit :ms}` (`create-flow`'s `update-flow`). `:lost` counts only in-chans that were dropped, never a message a stopped proc held in its state.

## Workloads (`reference-code/jolt/stdlib/clojure/core/async/impl/dispatch.clj` `executor-for`, `reference-code/jolt/stdlib/clojure/core/async/flow/impl.clj` `proc`)

What each :workload decides: whether a proc runs on a fiber or on an OS thread of its own.

| `:workload` | the proc loop runs on | transform runs on | note |
|---|---|---|---|
| `:io` | a **fiber** | that fiber | 50 procs add no thread |
| `:mixed` (**the default** when describe names none) | **an OS thread per proc, for its life** | that thread | 5 procs = +5 threads |
| `:compute` | a fiber | a cached thread pool, one future per message, awaited with `.get` for **`compute-timeout-ms` (5000)** | past the bound: a `TimeoutException` on the error chan and the step's result is lost, **but the step keeps running** |

## The proc contract (`reference-code/jolt/stdlib/clojure/core/async/flow.clj` `process`, `reference-code/jolt/stdlib/clojure/core/async/flow/impl.clj` `proc`)

When Flow calls each of a proc's four functions and what it may return: describe once, init at start, transition on a status change.

- **describe `()`** is called **once**, when `flow/process` builds the launcher. Its `:ins`, `:outs`, `:params`, `:workload`, `:signal-select` and `:ping-map-fn` are fixed for the proc's life; a redefined Var changes none of them.
- **init `(args)`** runs **inside `flow/start`** (or `update!`), with `::flow/pid` added to args. A throw surfaces from `flow/start`. The state it returns may carry:
  - `::flow/in-ports {cid chan}` and `::flow/out-ports {cid chan}`, which are **read once, from init's state**. A port can't be added later.
  - `::flow/input-filter`, a predicate of cid. Any state may carry it; it is re-read each step.
- **transition `(state t)`**, with t one of `::flow/resume`, `::flow/pause`, `::flow/stop`. It runs only when the status changes (`handle-transition`).
- **transform `(state in msg)`** answers `[state' {out [msgs]}]`. `in` is an in-port id, or a signal id for a cast. Reloading the Var takes effect at the next step.

**The loop, step by step** (`proc`):
- **Paused**, it reads only its control chan. Messages queue and casts wait.
- **Running**, it does `alts!! :priority true` over `[control casts & ins]`, filtered by `::flow/input-filter`, with control first.
- **A closed in-port** gives transform `msg = nil` once, then leaves the read set.
- **A throw in transform:** the state is unchanged, the outputs are dropped, `{::flow/pid ::flow/msg ::flow/ex ...}` goes to the error chan, and the proc carries on. Root answers the message's caller (see [request and answer](#idioms-for-seon)).
- **Outputs** are put one at a time with `alts!!` against control, so a pause or stop reaches a proc parked on a full output (`send-outputs`).

**Where an output goes** (`proc-resolver`):

| the output key | where it goes |
|---|---|
| a connected out | its consumer's in-chan (1:1) or a mult (fan-out) |
| a declared out with no conn | **dropped, no report** |
| `[pid in]` | that in-chan (dynamic routing, e.g. a reply) |
| `[pid in]` for a pid that isn't in the flow | dropped, `{::flow/undeliverable coord}` on the report chan |
| `::flow/report` | the report chan |
| `[::flow/cast sig]` | every proc whose `:signal-select` accepts `sig` |

**Fan-out is a mult** (`routes-for`), so it inherits `put!`: one full consumer holds back every consumer, as a callback waiter rather than a thread ([core-async](core-async.md#the-library-over-the-primitives)).

## The API

What each call of Flow's API really does in Jolt, with its timeouts and literals.

| call | what it really does (Jolt) | timeouts and literals |
|---|---|---|
| `create-flow` `{:procs :conns :stop-timeout-ms}` | plans procs; validates conns | `:stop-timeout-ms` default **10000**; in-port buffer default **10** (`make-chan`) |
| `start` | makes report and error chans, runs every init, starts every proc **paused** | report and error chans are **sliding 100**: old reports and errors are dropped silently |
| `resume`/`pause`, `resume-proc`/`pause-proc` | put a command on each proc's control chan with `>!!`, **under the graph lock** (`send-control!`) | control chan is `(chan 10)` (`start-proc!`) |
| `ping`/`ping-proc` | a ping command; waits up to `timeout-ms` for the pongs; a proc inside a step can't answer and is **left out** | `timeout-ms` default **1000** |
| `inject` `[pid in]` or `[::flow/cast sig]` | runs on an `:io` fiber, puts each msg with `>!!`, answers a **Future at once**; the caller isn't parked | an edge must deref it to feel back-pressure |
| `update!` | diffs fingerprints (step Var, describe without fns, args, chan-opts): unchanged procs keep their loop, state and queue; changed and removed ones are stopped and their exit awaited; in-chans survive by coord and options | per stop, `:stop-timeout-ms` |
| `stop` | stop to every proc; awaits each exit up to `:stop-timeout-ms` (`await-exits`); closes control chans, mult sources, error and report; answers `true` or `{::flow/running #{pids}}` | a proc parked on a full output **finishes its outputs, waiting up to 1000 ms per message** (`finish-outputs!`, called from `send-outputs`), then puts `{::flow/dropped-on-stop out}` on the report chan |
| casts | a cast is delivered to each selecting proc's castee chan | **sliding 100**: a cast a proc doesn't take in time is dropped |

## Hazards found in the source and confirmed

The ways a Flow graph wedges, hangs or drops messages silently, found in Jolt's source and confirmed.

1. **A proc stuck inside a step wedges the whole graph's control.** Its control chan holds 10, and commands are put with `>!!` under the graph lock. After 10 unanswered commands (pings, say), the 11th blocks while holding the lock. From then on, `ping` of any other proc, `pause`, `update!` and `stop` all wait until that step returns.
2. **A step that never returns can't be stopped.** `stop` gives up after `:stop-timeout-ms` and names it, but the fiber or thread lives on.
3. **Time inside Flow:** `compute-timeout-ms` 5000, `finish-outputs!` 1000 ms per message, `stop-timeout-ms` 10000, and ping's 1000. Each is a clock that decides, in the dependency ([clocks](clocks.md)).
4. **Silent drops:** an unconnected out, report and error chans past 100, casts past 100, and a mult with no taps.

## Idioms for Seon

How Seon meets each need (a dependency, a backlog, a delayed retry, quiet) on Flow.

- **Dependency:** a conn. The consumer parks on its in-port and starts on its first message; there is no start order. A join reads only what it still lacks: `::flow/input-filter`.
- **Backlog and admission:** the in-port is the queue. Size it by loss semantics (fixed for data that matters). An owner admits by **leaving `:in` out of its read set** while it is at its cap, so the unread queue fills and askers park. `seon.fx/owner-proc` does so (`seon.fx/bound`), and its workers compete for one shared in-port (`seon.fx/worker-proc`): proven by `seon.patterns-test/a-backlog-is-the-unread-queue` and `seon.patterns-test/workers-compete-for-one-shared-in-port`. A kind with `:batch n` gives a free worker its head request and those queued behind it as one item, taken from the queue, never waited for (`seon.fx/dispatch`; the `:embed` kind's texts encode together); a kind with `:open` makes its host object (a model) on the worker's first item and keeps it in the worker's state (`seon.fx/worker-proc`; `seon.fx-test/a-batch-kind-takes-what-is-queued`, `seon.fx-test/a-worker-performs-a-batch-in-one-call-and-opens-once`).
- **Delayed retry:** a timeout channel can't be an in-port itself, because in-ports are fixed at init. Give the proc one `:retry` in-port, made in init and placed in `::flow/in-ports`. To retry `m` in `ms`, start `(go (<! (timeout ms)) (>! retry m))`: one heap entry on the one timer thread and a parked fiber. **No cron proc.** Cron is only for scheduled work that isn't a reply to anything.
- **Request and answer:** a request carries `:reply [pid port]`, and the answer is an output to that coord: exactly one answer, `{:value}` or `{:error typed}`. **Credit:** the asker leaves `:ask` out of its read set at N outstanding, and its answer in-port holds N. Without credit, request and reply between bounded in-ports deadlock.
  - **The asker's side has one implementation:** ```seon.step/pend```, ```seon.step/ask``` and ```seon.step/answered``` (a second answer to the same id finds nothing). Root's api (`seon.root/pend`, `seon.root/deliver-rid`), the reconciler (`seon.reconcile/request`), the render proc (`seon.render.proc/ask`) and the web proc (`seon.web/web-proc`) keep their asks so. A request in flight is the render proc's coalescing window; no clock re-asks.
  - **An edge asks one way:** a caller outside the graph (the web's handler, root's REPL surface through `seon.root/call!`, root's stop) puts its request with a fresh promise as `:p` through ```seon.step/ask-edge```, and the proc delivers its one answer to it. A channel already closed answers the caller's closed value at once, so no request waits on a proc that will never take it (`seon.step-test/an-edge-asks-one-way`); no other src form puts a request carrying `:p` (`seon.main-test/an-edge-asks-one-way`).
  - **A throwing step's message is answered:** Flow's error channel is piped into the api's `:back` (`seon.root/start!`: `a/pipe` through a channel that maps each defect to an `:error` message; no thread), so each defect reaches the api with the `::flow/msg` the step took, and `seon.root/answer-taken` answers its caller with the defect, once (task `seon.root/a-proc-answers-every-request-it-took`). The api's own step takes a batch, so it keeps what it took in a volatile of its state (`::taken`) that survives Flow's revert, carries it as `::lost` into its next step, and answers all of it when the defect arrives.
  - **An answer and the fact it made arrive on two ins, in no set order:** the writer answers a write on main before its commit reaches a proc's `:main`, so a step that reads main after its own write takes the answer only once main holds it. The reconciler holds such an answer (```seon.reconcile/behind?```) and takes it when `:main` brings its t, as it holds a wait on main (`seon.reconcile/answer-waits`); without the hold, a stage's `:started` read a db without the run its `:spawned` wrote and opened the stage with no `:stage` entry (`seon.reconcile-test/a-step-reads-the-db-its-own-write-made`).
  - Not this: a counter and a pending map per proc, or a deadline that counts an answer as gone.
- **Failure:** send typed errors as answers, not throws ([errors](errors.md#errors)); a throw is a defect, and its message is answered as above.
- **Quiet:** Flow can't see it, because channels are opaque and ping misses procs inside a step. Count messages: +1 when promised (outputs, injects, answers owed by an edge, a step in progress), −1 when taken. Zero is quiet. A stall is named from each queue's fill, its capacity, its writer, and its reader's read set.
- **Never** let a step block on another proc (hazard 1). Make every slow wait a message and a park.
