# core.async.flow on Jolt

What Flow is on Jolt, read from the source and checked in the REPL. Every claim names its file and line. `[F..]` is a check in `../flowlab/docs_check.clj` (run `flowlab/run.sh docs_check`); `E..` and `R..` are the experiments in `../notes/option3-FLOWLAB*.md`.

Paths are under `reference-code/jolt/stdlib/clojure/core/async/`: `flow.clj` (the API), `flow/impl.clj`, `flow/spi.clj`, `flow/impl/graph.clj`, and `impl/dispatch.clj`. Read [core-async](core-async.md) first.

## Where Jolt's Flow differs from upstream
- **`flow.clj` and `spi.clj`** are upstream's `dev-flow-alpha` at `42dbd51`, plus `update!` (`flow.clj:141-150`). The file header still says "verbatim"; that is stale.
- **`impl.clj` is Jolt's.** It was rewritten in `00d090a5`, `414ce2b6`, `7706dc1b` and `33cb3b3a` (`impl.clj:58-79`):
  - each proc has its own control channel (there is no control mult);
  - outputs are routed when they are sent, by a resolver per pid reading a live table;
  - `update!` diffs procs by fingerprint;
  - `stop` awaits every exit and finishes in-flight outputs;
  - Flow's own plumbing runs on fibers;
  - `:chan-opts {:in {:chan c}}` uses a channel you made (`:105-110`);
  - an output to a pid that isn't in the flow is reported, not thrown (`:155-161`).
- **The `update!` docstring is wrong.** It promises `{:added :removed :rewired :running}`; the impl answers `{:stopped :started :kept :lost :missed-exit :ms}` [F16]. `:lost` counts only in-chans that were dropped, never a message a stopped proc held in its state (E3.4).

## Workloads (`impl/dispatch.clj:41-75`, `impl.clj:420-438`)

| `:workload` | the proc loop runs on | transform runs on | note |
|---|---|---|---|
| `:io` | a **fiber** | that fiber | 50 procs add no thread [F15] |
| `:mixed` (**the default** when describe names none, `impl.clj:421`) | **an OS thread per proc, for its life** | that thread | 5 procs = +5 threads [F14] |
| `:compute` | a fiber | a cached thread pool, one future per message, awaited with `.get` for **`compute-timeout-ms` (5000, `:419`, `:434-436`)** | past the bound: a `TimeoutException` on the error chan and the step's result is lost, **but the step keeps running** [F11] |

## The proc contract (`flow.clj:189-313`, `impl.clj:417-499`)
- **describe `()`** is called **once**, when `flow/process` builds the launcher (`impl.clj:420`). Its `:ins`, `:outs`, `:params`, `:workload`, `:signal-select` and `:ping-map-fn` are fixed for the proc's life; a redefined Var changes none of them (V1).
- **init `(args)`** runs **inside `flow/start`** (or `update!`), with `::flow/pid` added to args. A throw surfaces from `flow/start` [F17]. The state it returns may carry:
  - `::flow/in-ports {cid chan}` and `::flow/out-ports {cid chan}`, which are **read once, from init's state** (`:440-441`). A port can't be added later.
  - `::flow/input-filter`, a predicate of cid. Any state may carry it; it is re-read each step (`:465`).
- **transition `(state t)`**, with t one of `::flow/resume`, `::flow/pause`, `::flow/stop`. It runs only when the status changes (`handle-transition`).
- **transform `(state in msg)`** answers `[state' {out [msgs]}]`. `in` is an in-port id, or a signal id for a cast. Reloading the Var takes effect at the next step.

**The loop, step by step:**
- **Paused**, it reads only its control chan (`:459-462`). Messages queue and casts wait [F7, F8].
- **Running**, it does `alts!! :priority true` over `[control casts & ins]`, filtered by `::flow/input-filter`, with control first (`:465-471`).
- **A closed in-port** gives transform `msg = nil` once, then leaves the read set [F20] (`:482-484`).
- **A throw in transform:** the message is **lost**, the state is unchanged, `{::flow/pid ::flow/msg ::flow/ex ...}` goes to the error chan, and the proc carries on [F2] (`:488-491`).
- **Outputs** are put one at a time with `alts!!` against control, so a pause or stop reaches a proc parked on a full output (`send-outputs`, `:389-415`).

**Where an output goes** (`proc-resolver`, `:148-168`):

| the output key | where it goes |
|---|---|
| a connected out | its consumer's in-chan (1:1) or a mult (fan-out) |
| a declared out with no conn | **dropped, no report** [F4] |
| `[pid in]` | that in-chan (dynamic routing, e.g. a reply) |
| `[pid in]` for a pid that isn't in the flow | dropped, `{::flow/undeliverable coord}` on the report chan [F3] |
| `::flow/report` | the report chan [F5] |
| `[::flow/cast sig]` | every proc whose `:signal-select` accepts `sig` |

**Fan-out is a mult** (`:126`), so it inherits `put!`: a full consumer costs an OS thread [F18].

## The API

| call | what it really does (Jolt) | timeouts and literals |
|---|---|---|
| `create-flow` `{:procs :conns :stop-timeout-ms}` (`flow.clj:89`, `impl.clj:203`) | plans procs; validates conns | `:stop-timeout-ms` default **10000** (`:205`); in-port buffer default **10** (`:110`) [F9] |
| `start` (`impl.clj:287-301`) | makes report and error chans, runs every init, starts every proc **paused** [F1] | report and error chans are **sliding 100** (`:292-293`): old reports and errors are dropped silently |
| `resume`/`pause`, `resume-proc`/`pause-proc` (`:316-334`) | put a command on each proc's control chan with `>!!`, **under the graph lock** | control chan is `(chan 10)` (`:173`) |
| `ping`/`ping-proc` (`:320-340`) | a ping command; waits up to `timeout-ms` for the pongs; a proc inside a step can't answer and is **left out** [F10] | `timeout-ms` default **1000** (`flow.clj:160,176`) |
| `inject` `[pid in]` or `[::flow/cast sig]` (`:341-348`) | runs on an `:io` fiber, puts each msg with `>!!`, answers a **Future at once**; the caller isn't parked [F6] | an edge must deref it to feel back-pressure |
| `update!` (`flow.clj:141`, `impl.clj:207-285`, `:351`) | diffs fingerprints (step Var, describe without fns, args, chan-opts): unchanged procs keep their loop, state and queue; changed and removed ones are stopped and their exit awaited; in-chans survive by coord and options | per stop, `:stop-timeout-ms` |
| `stop` (`:302-315`) | stop to every proc; awaits each exit up to `:stop-timeout-ms`; closes control chans, mult sources, error and report; answers `true` or `{::flow/running #{pids}}` [F13] | a proc parked on a full output **finishes its outputs, waiting up to 1000 ms per message** (`finish-outputs!`, `:356-367`, literal at `:393`, `:404`), then puts `{::flow/dropped-on-stop out}` on the report chan [F12] |
| casts (`:247-255`, `:290-297`) | a cast is delivered to each selecting proc's castee chan | **sliding 100** (`:253`): a cast a proc doesn't take in time is dropped |

## Hazards found in the source and confirmed
1. **A proc stuck inside a step wedges the whole graph's control** [F19]. Its control chan holds 10, and commands are put with `>!!` under the graph lock. After 10 unanswered commands (pings, say), the 11th blocks while holding the lock. From then on, `ping` of any other proc, `pause`, `update!` and `stop` all wait until that step returns.
2. **A step that never returns can't be stopped.** `stop` gives up after `:stop-timeout-ms` and names it [F12, F13], but the fiber or thread lives on.
3. **Time inside Flow:** `compute-timeout-ms` 5000, `finish-outputs!` 1000 ms per message, `stop-timeout-ms` 10000, and ping's 1000. Each is a clock that decides. Tasks are filed (`flowlab/tasks.edn`: `no-timeout-in-the-proc-loop`).
4. **Silent drops:** an unconnected out, report and error chans past 100, casts past 100, and a mult with no taps.

## Idioms for Seon
- **Dependency:** a conn. The consumer parks on its in-port and starts on its first message; there is no start order. A join reads only what it still lacks: `::flow/input-filter` (E2).
- **Backlog and admission:** the in-port is the queue. Size it by loss semantics (fixed for data that matters). An owner admits by **leaving `:in` out of its read set** while it is at its cap, so the unread queue fills and askers park (R1). `seon.fx/owner-proc` does so (`seon.fx/bound`), and its workers compete for one shared in-port (`seon.fx/worker-proc`): proven by `seon.patterns-test/a-backlog-is-the-unread-queue` and `seon.patterns-test/workers-compete-for-one-shared-in-port`.
- **Delayed retry:** a timeout channel can't be an in-port itself, because in-ports are fixed at init (`impl.clj:440`). Give the proc one `:retry` in-port, made in init and placed in `::flow/in-ports`. To retry `m` in `ms`, start `(go (<! (timeout ms)) (>! retry m))`: one heap entry on the one timer thread and a parked fiber. **No cron proc.** Cron is only for scheduled work that isn't a reply to anything.
- **Request and answer:** a request carries `:reply [pid port]`, and the answer is an output to that coord: exactly one answer, `{:value}` or `{:error typed}`. **Credit:** the asker leaves `:ask` out of its read set at N outstanding, and its answer in-port holds N. Without credit, request and reply between bounded in-ports deadlock (R1, R6). **The asker's side has one implementation:** `seon.step/pend` (a new id from the state's `:rid`, what awaits its answer under `:pending`), `seon.step/ask` (the same, plus the `{:op :request :req :route [pid in] :tag rid}` message a root proc sends to the api) and `seon.step/answered` (the answer takes its entry, once: a second answer to the same id finds nothing). Root's api (`seon.root/pend`, `seon.root/deliver-rid`), the reconciler (`seon.reconcile/request`), the render proc (`seon.render.proc/ask`) and the web proc (its waiting pages and its tabs' ids, `seon.web/web-proc`) keep their asks so. A request in flight is the render proc's coalescing window; no clock re-asks: the api answers every request it took, a defect included (task `seon.root/a-proc-answers-every-request-it-took`). **A throwing step's message is answered:** Flow's step catch keeps the old state, drops the outputs and names the message the step took (`::flow/msg`); root's error reader (in `seon.root/start!`) forwards it as `:msg`, and `seon.root/answer-taken` answers its caller with the defect: the answer its `:dest` names, its promise or `:route`, its `:rid`, or, for an answer a proc took (`{:tag :r}`), that answer again on `[pid :answers]` (never an answer that was already an error, so a step cannot loop on it). The api's own step takes a batch, so it keeps what it took in a volatile of its state (`::taken`, which survives Flow's revert) and answers all of it. Callers park on the reply (`seon.root/state` answers the defect undecorated). Not this: a counter and a pending map per proc, or a deadline that counts an answer as gone.
- **Failure:** today a throw loses its message (`:488`). Use the hold pattern (catch in the step, keep the message in state, read set `#{}`, retry on a `::reloaded` cast; E3, R2). Send typed errors as answers, not throws.
- **Quiet:** Flow can't see it, because channels are opaque and ping misses procs inside a step. Count messages: +1 when promised (outputs, injects, answers owed by an edge, a step in progress), −1 when taken. Zero is quiet. A stall is named from each queue's fill, its capacity, its writer, and its reader's read set or hold (R6, `flowlab/kit.clj`).
- **Never** let a step block on another proc (hazard 1). Make every slow wait a message and a park.

