# core.async.flow on Jolt

Every Seon component is a Flow proc. Jolt's Flow is upstream's API over Jolt's own implementation; this page states the proc contract, where each workload runs, what the API really does and its hazards, read from the pinned source under `reference-code/jolt/stdlib/clojure/core/async/` (`flow.clj`, `flow/impl.clj`, `flow/spi.clj`, `flow/impl/graph.clj`, `impl/dispatch.clj`). Read [core-async](core-async.md) first.

## Where Jolt's Flow differs from upstream

`flow.clj` and `flow/spi.clj` are upstream's `dev-flow-alpha` plus `update!`. `flow/impl.clj` is Jolt's:

- each proc has its own control channel (no control mult);
- outputs are routed when sent, by a resolver per pid reading a live table (`proc-resolver`), and an output to a pid not in the flow is reported, not thrown;
- `update!` diffs procs by fingerprint;
- `stop` awaits every exit and finishes in-flight outputs;
- Flow's own plumbing runs on fibers;
- `:chan-opts {:in {:chan c}}` uses a channel you made.

`update!`'s docstring promises `{:added :removed :rewired :running}`, but it answers `{:stopped :started :kept :lost :missed-exit :ms}` (`create-flow`'s `update-flow`); `:lost` counts dropped in-chans only, never a message a stopped proc held.

## Workloads

`:workload` decides where a proc's loop runs (`impl/dispatch.clj` `executor-for`, `flow/impl.clj` `proc`):

- `:io`: a fiber; 50 procs add no thread.
- `:mixed` (the default when describe names none): an OS thread per proc for its life.
- `:compute`: a fiber, with each transform run as a future on a cached pool and awaited for `compute-timeout-ms` (5000); past it a `TimeoutException` goes to the error chan and the result is lost, but the step keeps running.

## The proc contract

Flow calls a proc's four functions so (`flow.clj` `process`, `flow/impl.clj` `proc`):

- **describe** is called once, when `flow/process` builds the launcher; its `:ins`, `:outs`, `:params`, `:workload`, `:signal-select` and `:ping-map-fn` are fixed for the proc's life.
- **init** runs inside `flow/start` (or `update!`) with `::flow/pid` in args; a throw surfaces from `start`. `::flow/in-ports` and `::flow/out-ports` are read once from its state, so a port can't be added later. `::flow/input-filter`, a predicate of cid, is re-read each step.
- **transition** runs only when the status changes (`::flow/resume`, `::flow/pause`, `::flow/stop`).
- **transform** `(state in msg)` answers `[state' {out [msgs]}]`; a reloaded Var takes effect at the next step.

The loop: paused, it reads only control; running, `alts!! :priority true` over control, casts and the filtered ins. A closed in-port gives `msg = nil` once, then leaves the read set. A throw in transform keeps the old state, drops the outputs, sends `{::flow/pid ::flow/msg ::flow/ex}` to the error chan and carries on. Outputs are put one at a time against control, so a stop reaches a proc parked on a full output.

Where an output goes (`proc-resolver`): a connected out to its consumer (a mult for fan-out); a declared out with no conn is dropped with no report; `[pid in]` to that in-chan, or, for a pid not in the flow, dropped with `{::flow/undeliverable coord}` on the report chan; `::flow/report` to the report chan; `[::flow/cast sig]` to every proc whose `:signal-select` takes it. Fan-out is a mult, so one full consumer holds back all ([core-async](core-async.md#the-library-over-the-primitives)).

## The API

What each function of Jolt's Flow API does, with its defaults.

- `create-flow`: `:stop-timeout-ms` default 10000; in-port buffer default 10.
- `start`: makes the report and error chans (sliding 100: old entries dropped silently), runs every init, starts every proc paused.
- `resume`, `pause` and their `-proc` forms put on the proc's control chan (`(chan 10)`) with `>!!` under the graph lock.
- `ping`: waits up to 1000 ms; a proc inside a step is left out.
- `inject`: puts on an `:io` fiber and answers a Future at once; an edge derefs it to feel back-pressure.
- `update!`: unchanged procs (step Var, describe, args, chan-opts) keep loop, state and queue; changed and removed ones are stopped and awaited.
- `stop`: awaits each exit up to `:stop-timeout-ms`, then closes everything, answering `true` or `{::flow/running #{pids}}`; a proc parked on a full output finishes its outputs, up to 1000 ms per message, then reports `{::flow/dropped-on-stop out}`.
- casts: a sliding 100 per proc; one not taken in time is dropped.

## Hazards found in the source and confirmed

The ways a Flow graph can wedge or lose data, each read in the source and confirmed.

1. **A proc stuck inside a step wedges the graph's control.** Its control chan holds 10 and commands are put under the graph lock, so the 11th blocks holding it: `ping`, `pause`, `update!` and `stop` of every proc then wait for that step.
2. **A step that never returns can't be stopped:** `stop` names it after `:stop-timeout-ms`, and it lives on.
3. **Clocks inside Flow:** `compute-timeout-ms`, `finish-outputs!`'s 1000 ms, `stop-timeout-ms`, ping's 1000 ([clocks](clocks.md)).
4. **Silent drops:** an unconnected out, report and error chans past 100, casts past 100, a mult with no taps.

## The step-fn contract every proc follows

Every component of Seon is a step-fn proc declared as data (`resources/seon/system.edn`), and each follows one contract:
- a Var with four arities, whose `describe` declares ins, outs, an honest `:workload` and a ping-map;
- `transform` returns `[state' outputs]`, with data outputs;
- a step never blocks on another proc: request and answer with credit, `{:id :reply}`, exactly one answer ([below](#idioms-for-seon));
- state is plain data under its schema, and a host object lives only in its owning proc;
- buffers are bounded by their loss semantics; a dependency is a channel a proc parks on, and back-pressure is a full one;
- procs run on fibers, and a blocking foreign call gets a declared thread; no thread, future or atom lives outside procs, except an edge reader for a blocking read;
- a proc reports its own problems as facts and its changes as events, and steps only when a message arrived;
- the work is done when the count of messages reaches zero: each one promised is counted, each one taken is taken off, and zero is quiet ([below](#idioms-for-seon));
- a proc never binds an out-port to its own in-port's channel: Flow names a channel by one port, so the in-port's close is missed and the proc reads a closed channel forever. A proc that needs a later step of its own asks another proc that answers on its in-port (the writer asks the flusher for its `:commit`).

## Idioms for Seon

How Seon meets each need on Flow.

- **Dependency:** a conn; the consumer parks on its in-port, so there is no start order. A join reads only what it still lacks (`::flow/input-filter`).
- **Backlog and admission:** the in-port is the queue, sized by loss semantics. An owner admits by leaving `:in` out of its read set at its cap, so askers park (`seon.fx/owner-proc`, `seon.fx/bound`); its workers compete for one shared in-port (`seon.fx/worker-proc`; `seon.patterns-test/a-backlog-is-the-unread-queue`, `seon.patterns-test/workers-compete-for-one-shared-in-port`). A kind with `:batch n` hands a free worker its head request with those queued behind it (`seon.fx/dispatch`); a kind with `:open` makes its host object on the worker's first item and keeps it (`seon.fx-test/a-batch-kind-takes-what-is-queued`, `seon.fx-test/a-worker-performs-a-batch-in-one-call-and-opens-once`).
- **Delayed retry:** in-ports are fixed at init, so the proc has one `:retry` in-port and `(go (<! (timeout ms)) (>! retry m))` puts on it ([clocks](clocks.md)).
- **Request and answer:** a request carries `:reply [pid port]`; the answer is one output to that coord, `{:value}` or `{:error typed}`. With credit: the asker leaves `:ask` out of its read set at N outstanding, its answer in-port holding N; without it, bounded request and reply deadlock.
  - **The asker's side has one implementation:** ```seon.step/pend```, ```seon.step/ask``` and ```seon.step/answered``` (a second answer finds nothing), used by root's api (`seon.system/pend`, `seon.system/deliver-rid`), the reconciler (`seon.reconcile/request`), the render proc (`seon.render.proc/ask`) and the web proc.
  - **An edge asks one way:** a caller outside the graph puts its request with a fresh promise as `:p` through ```seon.step/ask-edge```; a closed channel answers at once (`seon.step-test/an-edge-asks-one-way`, `seon.main-test/an-edge-asks-one-way`).
  - **A throwing step's message is answered:** Flow's error chan is piped into the api's `:back` (`seon.system/start!`), and `seon.system/answer-taken` answers the caller with the defect, once; the api keeps the batch it took in `::taken`, which survives Flow's revert. The defect is also a `:proc-defect` event (```seon.system/api-proc```), so a wait on events ends with it.
  - **An answer and the fact it made arrive on two ins, in no set order:** a step that reads main after its own write holds the answer (```seon.reconcile/behind?```) until `:main` brings its t (`seon.reconcile-test/a-step-reads-the-db-its-own-write-made`).
- **A fact or a channel:** what must outlive its process (a message, a verdict, a request answerable after a restart) is a fact first, written by its one writer; a channel carries only the notice (the writer's commit to `:main`, a `:wake` to the reconciler) and live requests and answers. A message to an agent is a fact on main (```seon.task/message-tx```) plus a wake, and the agent takes it onto its branch at its next act (```seon.agent/undelivered```).
- **Failure:** typed errors are answers, not throws ([errors](errors.md#errors)); a throw is a defect, answered as above.
- **Quiet:** Flow can't see it, so count messages: +1 when promised, −1 when taken; zero is quiet. A stall is named from each queue's fill, capacity, writer and reader's read set.

## Flow inside an agent

An agent is a Flow graph of three `:io` procs on its main carrier, so it stays one OS thread and forks its test children and clones as before (```seon.repl/serve!```, [processes](processes.md#the-agent-process)).

- **One active thread:** such a graph keeps one active thread through `start`, `resume`, `inject`, `ping` and `stop`, so a fork succeeds at each (`seon.lib/fork!`) ([machine](machine.md#what-a-spawn-costs)).
- **A wait inside a step is an edge's ask:** the `:repl` proc's eval may wait for root's answer to its own request; that wait is a promise the `:transport` proc delivers by key (```seon.agent/await-reply```), and the fiber parks meanwhile. Root's interrupt stops a parked eval; a busy loop that never parks is stopped only by its tick budget or root's signal.
- **A fiber's allocation is its own:** a byte or tick budget (`jolt.host/run-interruptible`) counts only its eval's fiber, never the `:tests` proc's work beside it.
- **Hazards 1 and 2 hold inside it:** `:repl` is inside one step for a whole work (`seon.turn/work!`), so nothing in the agent pings or stops its graph; root's stop is a `:stop` message the REPL takes in order.
- **The bound:** the `:repl` and `:tests` in-ports each hold `:seon.config.agent/inbox-size`; at it the transport stops reading until one is taken.

## Effects: one owner and its workers per kind

Every outside effect has one owner proc and a few worker procs per kind, declared as data in `resources/seon/system.edn`. ```seon.graph/read-decl``` reads it (`{:procs :fx :conns}`); `seon.graph/build` builds the graph; ```seon.graph/fx-owners``` answers the owners' pids, the only procs an agent's proc may address. ```seon.fx/procs``` builds the owner `:fx/<kind>` and workers `:fx.<kind>/<i>` on one work channel. ```seon.fx/declared-refs``` lists the file's symbols as reach edges, so a test that boots root reaches its procs.

An agent reaches an owner through ```my.fx/call``` (the outcome, with `{:prio :budget :budget-key}` options) or ```my.fx/send``` (a key now, the outcome later as a message); inside a test fork nothing leaves the process. The owner admits while a worker is free, the task has budget and the provider's bucket (```seon.fx/refill```: refills at the configured rate, halves on a rate limit, regains a twentieth otherwise, [clocks](clocks.md#time-decides-the-owners-exceptions)) covers the estimate; otherwise it waits for the owner's `:tick`, or is answered `:seon.fx/budget-spent`.

An owner holds at most `:seon.config.fx/buf` agent requests, then stops reading `:in`, so asking agents park. Root's own asks go to the owner's `:ask` port, which the bound never closes, so the api never waits behind agents (```seon.fx-test/the-api-answers-while-an-effects-queue-is-full```).

The performers are Vars of `seon.fx`, each answering `{:value ...}` or a declared error ([failure points](failure-points.md#edges-that-catch-each-names-its-one-variant)), among them ```seon.fx/echo``` (the test effect), ```seon.fx/model``` (`seon.model/complete`; its `:cost` settles the bucket and the task's spend), ```seon.fx/stall-evidence``` ([processes](processes.md#a-stalled-agent)), ```seon.fx/test-results``` (no verdict is never a pass) and ```seon.fx/tree-of``` ([health](health.md#checks-are-callers-of-seon)).

**A streaming perform** answers `{:stream {:pull :init :step :final :close}}` in place of its outcome, as ```seon.model/complete``` does for a request naming its model call (```seon.turn/act!```). Since a step's outputs leave only when it returns, ```seon.fx/worker-proc``` starts one edge reader (```seon.fx/read-lines!```) that puts each whole line on the worker's `:lines` in-port, then `{:end true}` or `{:error e}` (a socket failure is `:seon.model/transport`, from `seon.model/http-post`). ```seon.fx/stream-step``` folds each line and outputs to `[:web :streams]`, reading only `:lines` while the call is in flight, then gives the owner `:done` once. ```seon.model/sse-step``` is also `seon.model/sse-reply`'s parser, and ```seon.model/json-feed``` reads a tool call's arguments as they arrive; what streams is display only, and the committed reply is read from the whole arguments.
