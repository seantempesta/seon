# Flow and core.async on Jolt

Every Seon component is a Flow proc over core.async's channels. Jolt implements both: core.async is one waiter protocol over Chez mutexes, condition variables and fibers, and Flow is upstream's API over Jolt's own implementation. This page is one primer, read whole before any design that makes a proc, a port, a buffer, a fan-out or a wait: the channels first, then Flow's contract, hazards and idioms, read from the pinned source under `reference-code/jolt/` (`host/chez/java/async.ss`, `host/chez/java/fibers-async.ss`, `host/chez/fibers.ss`, `stdlib/clojure/core/async.clj`, and `stdlib/clojure/core/async/` `flow.clj`, `flow/impl.clj`, `flow/spi.clj`, `flow/impl/graph.clj`, `impl/dispatch.clj`). A design that skips the channels part has shipped the wrong buffer (a sliding buffer carrying many branches' notices, 2026-11-14).

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
- `(sliding-buffer n)`: never waits; **drops the oldest**, silently to the putter; the channel counts its drops (`jolt.host/chan-dropped`, which Flow's ping reports).
- `(dropping-buffer n)`: never waits; **drops the newest**, silently.
- `(promise-chan)`: keeps the first value; later puts **answer true and are dropped**; every take reads the one value.
- `(chan n xf ex-handler)`: the transducer runs **on the put**, under the channel lock; a throw puts `(ex-handler e)` if non-nil; `reduced` closes the channel.

`close!` is idempotent; a put on a closed channel answers `false`; buffered values drain, then a take answers `nil`. **Putting `nil` throws** (`async-check-put!`).

## Ops

What each operation does on a fiber and on an OS thread.

- `>!` `<!` `>!!` `<!!`: park on a fiber, block on a thread. Inside `go`, a park the CPS pass can see captures no continuation.
- `offer!`: never waits; answers true, false when the channel is closed, nil when full. `poll!`: never waits; nil when nothing is ready.
- `alts!`, `alts!!`: wait on all ports at once, a fast pass then handler registration, no poll loop; `:priority true` takes the first ready port in order; `:default` answers at once.
- **`put!`, `take!` that can't complete at once:** a callback handler registered under the channel's mutex (`jolt-async-put!`, `jolt-async-take!`), so pending puts complete in order and the callback runs on a fiber. No thread per pending op.
- `timeout ms`: a channel the shared timer closes at ms: one min-heap for every deadline in the runtime, O(log k) to arm (a main carrier runs it in its program loop). A timeout channel can't be cancelled; it just closes. The O(1) cancel, `jolt.host/timer-cancel!`, is for timed socket and deref waits.

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

## Channel idioms

How Seon meets each need with channels.

- **Dependency:** park on a channel with `<!` on a fiber; never a sleep, poll or timeout standing in for the thing you wait for.
- **Backlog and admission:** a fixed `(chan n)` is the queue, and a full one parking the producer is the back-pressure.
- **A lossy buffer** (sliding, dropping) only on **one stream whose newest value subsumes the rest** (a tick, main's latest commit, a view); **never for data that must arrive**, and never for many keys on one port, where one key's value evicts another's. Many keys on one port that a reader may drop: the message says only that something moved, and the reader reads the state of the keys it holds (a branch's head). There is no buffer that keeps the latest value per key, and a `sub` channel per key can't be a Flow proc's port (ports are fixed at init). A best-effort stream beside an answer that carries everything may slide (`seon.server/prove-streamed`).
- **Delayed retry, and any wait past a bound:** ```seon.step/put-after!``` puts the message on the proc's own in-port once ms pass (one heap entry and a parked fiber), the one timer ([clocks](clocks.md)).
- **Request and answer:** carry `:reply` (a channel or a Flow coord) in the request. **Credit:** an asker keeps at most N requests outstanding and its answer channel holds N, so an answer never parks; without credit, two bounded queues in a request/answer cycle deadlock. A `promise-chan` per request when exactly one answer is wanted.
- **Failure:** a throw inside `go` kills that fiber and closes its result channel; `(go-monitor ch)` yields the throwable (a clean finish just closes it), and the throw also goes to the uncaught-exception handler. `jolt.fibers/monitor!` and `join` take a fiber (`jolt.fibers/spawn`), never a `go` channel. Answer errors as values on the reply channel.
- **Quiet:** core.async alone cannot see it; count messages ([flow](flow.md#idioms-for-seon)).

## The five rules

Every design that waits, steps or bounds follows these; the Flow specialist reviews each such design before it is built.

1. **A proc waits only by parking on its in-port for the thing itself,** and steps when it arrives. A step never waits inside: a call out is an output, its answer a message on the proc's own in-port (a model call, an ask of root, a test run). A step that waits inside can be neither pinged nor stopped until it returns. The one exception is an eval: the agent's own code, which may ask root and use the answer as a REPL caller expects (`(prove)` returns its verdict). Its ask is an edge ask (```seon.step/ask-edge```, and an agent's own asks of root through ```seon.agent/await-reply```), ended by root's answer, its effect's deadline, root's interrupt, or rule 3; it is the one sanctioned wait in a step, not a violation of this rule.
2. **Control never waits:** a lifecycle command is a `put!` (never blocks, arrives in order), a ping an `offer!` that names who did not answer, and a closed control channel is the proc's exit. A proc stuck in a step then wedges nothing else: a ping waits only for the procs not `:stepping` when it is sent, and names a stepping one by its site, unanswered (Jolt gate flowpingstepping).
3. **"Nothing hangs" comes from sources, never from a watcher.** Every way a thing can fail to answer is a message from where it happens: its answer; its death (the socket's end of file); a loop that never parks, by its tick budget (counted work); a wait on the outside world, by its effect's declared deadline. No proc polls another's quiet, and nothing watches for a stall ([processes](processes.md#every-wait-ends)).
4. **Time only at the outside edge:** an effect on the outside world (the model, a shell, the network) has its declared deadline, one entry on Jolt's deadline heap (```seon.step/put-after!```), cancelled when it is answered (```seon.step/cancel!```). This is the only clock after boot.
5. **The residue is named, not timed:** a blocking native call that may never return is closed at its source, as an effect with its bound or with a bound of its own (the fork's `:seon.config.agent/fork-wait-ms`).

## Where Jolt's Flow differs from upstream

`flow.clj` and `flow/impl/graph.clj` are upstream's `dev-flow-alpha` plus `update!` (`graph.clj` adds the `Update` protocol); `flow/spi.clj` is upstream's; `flow/impl.clj` is Jolt's:

- each proc has its own control channel (no control mult);
- outputs are routed when sent, by a resolver per pid reading a live table (`proc-resolver`), and an output to a pid not in the flow is reported, not thrown;
- `update!` diffs procs by fingerprint;
- `stop` awaits every exit and finishes in-flight outputs;
- Flow's own plumbing runs on fibers;
- `:chan-opts` apply to **in-ports only**: `:chan` (a channel you made, used as is and closed by the flow when the in-port is dropped), `:buf-or-n` (default 10), `:xform` (with no `:buf-or-n`, a rendezvous channel; its throw goes to the error chan). An out's chan-opts are ignored: a fan-out's mult source is a fixed `(chan 10)`, and a mult once made stays.

`update!`'s docstring promises `{:added :removed :rewired :running}`, but it answers `{:stopped :started :kept :lost :missed-exit :ms}` (`create-flow`'s `update-flow`); `:lost` counts dropped in-chans only, never a message a stopped proc held.

## Workloads

`:workload` decides where a proc's loop runs (`impl/dispatch.clj` `executor-for`, `flow/impl.clj` `proc`):

- `:io`: a fiber; 50 procs add no thread.
- `:mixed` (the default when describe names none): an OS thread per proc for its life.
- `:compute`: a fiber, with each transform run as a future on a cached pool and awaited with no clock; a `compute-timeout-ms` given to `flow/process` bounds it, and past it a `TimeoutException` goes to the error chan and the result is lost while the future keeps running.

## The proc contract

Flow calls a proc's four functions so (`flow.clj` `process`, `flow/impl.clj` `proc`):

- **describe** is called once, when `flow/process` builds the launcher; its `:ins`, `:outs`, `:params`, `:workload`, `:signal-select` and `:ping-map-fn` are fixed for the proc's life.
- **init** runs inside `flow/start` (or `update!`) with `::flow/pid` in args; a throw surfaces from `start`. `::flow/in-ports` and `::flow/out-ports` are read once from its state, so a port can't be added later. `::flow/input-filter`, a predicate of cid, is re-read each step.
- **transition** runs only when the status changes (`::flow/resume`, `::flow/pause`, `::flow/stop`).
- **transform** `(state in msg)` answers `[state' {out [msgs]}]`; a reloaded Var takes effect at the next step.

The loop: paused, it reads only control; running, `alts!! :priority true` over control, casts and the filtered ins. A closed in-port gives `msg = nil` once, then leaves the read set. A throw in transform keeps the old state, drops the outputs, sends `{::flow/pid ::flow/msg ::flow/ex}` to the error chan and carries on. Outputs are put one at a time against control, so a stop reaches a proc parked on a full output.

Where an output goes (`proc-resolver`): a connected out to its consumer (a mult for fan-out); a declared out with no conn is dropped and reported as `{::flow/undeliverable [pid out]}` on the report chan; `[pid in]` to that in-chan, or, for a pid not in the flow, dropped with `{::flow/undeliverable coord}` on the report chan; `::flow/report` to the report chan; `[::flow/cast sig]` to every proc whose `:signal-select` takes it. Fan-out is a mult, so one full consumer holds back all ([the library](#the-library-over-the-primitives)).

## The API

What each function of Jolt's Flow API does, with its defaults.

- `create-flow`: `:stop-timeout-ms` default 10000; in-port buffer default 10.
- `start`: makes the report chan (sliding 100: old entries dropped, the count in the ping answer's metadata; root reads it, so an output Flow could not deliver or a stop dropped is a problem, ```seon.system-test/an-undeliverable-output-is-a-problem```) and the error chan (a fixed `(chan 100)` that never drops, written with `put!`, so a reporting proc never waits: past 100 its errors queue as pending puts, unbounded in Jolt, each holding the proc's state, so every flow's owner drains it, as root's and each agent's do), runs every init, starts every proc paused.
- `resume`, `pause`, `stop` and their `-proc` forms `put!` on each proc's own control chan (`(chan 10)`), never blocking, in order; a ping `offer!`s.
- `ping`: waits up to its timeout (1000 ms) only for the procs not `:stepping` when it is sent, and answers with each proc's `::flow/site` and `::flow/in-fill`, read without asking it; its metadata names the `::flow/unanswered` pids and `::flow/report-dropped`. `ping-proc` has no such fix: on a proc inside a step it waits its whole timeout and answers nil.
- `inject`: puts on an `:io` fiber and answers a Future at once; an edge derefs it to feel back-pressure.
- `update!`: unchanged procs (step Var, describe, args, chan-opts) keep loop, state and queue; changed and removed ones are stopped and awaited.
- `stop`: awaits every exit within one `:stop-timeout-ms` for the whole stop, then closes everything, answering `true` or `{::flow/running #{pids}}`; a proc parked on a full output finishes its outputs with `put!` (no clock), and one a closed channel refuses is reported `{::flow/dropped-on-stop out}`. A proc reads control between steps, so a stop finishes the step in flight and then exits: messages still queued on its in-ports are never processed and nothing returns them, lost with the flow, while `pause` keeps them for its `resume` (measured: 9 of 10 queued lost on stop, all kept on pause). So nothing in a channel may be its only copy: what must outlive a stop is a fact first ([a fact or a channel](#idioms-for-seon)); a drain finishes the queues before the stop (task `seon.system/an-update-loses-nothing`).
- casts: a sliding 100 per proc; one not taken in time is dropped.

## Changing a running graph

A running graph takes new code and new procs with no restart, and Seon relies on it: root adds and removes each agent's proc live (```seon.system/api-proc```), and an agent adds and removes its test runs' procs (```seon.agent/run-tests-forked```). Proven by ```seon.step-test/a-running-graph-takes-new-code-and-new-procs-with-no-restart``` (red when a proc holds its step-fn's value instead of its Var):

- **A redefined step-fn** is the proc's code from its next step, with no `update!`: a proc holds its step-fn by Var (`flow/process #'f`), and its state is kept. `describe` is captured when `flow/process` ran, so a redefinition that changes ports or workload is not seen (that is an `update!`), and the new code must accept the old state's shape; a proc built from a function value, not its Var, never sees a redefinition.
- **`update!` that adds or rewires procs** stops none: every unchanged proc keeps its loop, state and queue, a fan-out it now feeds becomes a mult, and nothing in flight is lost (`:lost {}`).
- **`update!` with a proc's own definition changed** (its step Var, describe data, args or chan-opts, compared by `=`) stops and restarts that proc alone with fresh state (its init re-runs); its in-ports and queued messages are kept unless its in-port's `:chan-opts` changed, which closes, drains and counts the old channel in `:lost`; a change only in a describe function (`:ping-map-fn`, `:signal-select`) is not seen. Every other proc keeps its state.

So code reaches a running system by redefinition and procs by `update!`; a restart is for a change of the binary only.

## Hazards found in the source and confirmed

The ways a Flow graph can wedge or lose data, each read in the source and confirmed.

1. **A proc stuck inside a step answers no command until it returns,** but wedges no other proc: commands are `put!` on its own control chan, a ping `offer!`ed and the proc named unanswered (Jolt 887c1b7f, gate flowstuckcontrol).
2. **A step that never returns can't be stopped:** `stop` names it after `:stop-timeout-ms`, and it lives on.
3. **Clocks inside Flow:** `stop-timeout-ms` (one bound for the whole stop), ping's timeout, and `compute-timeout-ms` only when given ([clocks](clocks.md)).
4. **Drops:** the report chan past 100 (sliding, counted in the ping's metadata), casts past 100 (oldest dropped), a mult with no taps, and any lossy-buffer channel (counted by `jolt.host/chan-dropped`); an unconnected out is dropped but reported; the error chan never drops, and nothing bounds its pending puts (its owner drains it).
5. **A step that runs long shows as an unanswered ping:** ```clojure.core.async.flow/ping-proc``` with `:timeout-ms 1`, asked twice a few seconds apart, gets no answer from a proc inside one step (seen 2026-11-14: `:render` reading health whole for 80 s a step). A `:mixed` proc is its own OS thread, so its frames are not in the fiber dump.

## The step-fn contract every proc follows

Every component of Seon is a step-fn proc declared as data (`resources/seon/system.edn`), and each follows one contract:
- a Var with four arities, whose `describe` declares ins, outs, an honest `:workload` and a ping-map, and `:in-params`, the params that are channels it reads as its own in-ports when one is bound in `system.edn` rather than routed by a conn (the writer's `:to-writer`, which it drains whole for one group commit): whoever puts on that channel knows its reader, so a message to a proc whose transition threw is answered with that error (```seon.system/answer-down```);
- `transform` returns `[state' outputs]`, with data outputs;
- a step never blocks on another proc: request and answer with credit, `{:id :reply}`, exactly one answer ([below](#idioms-for-seon));
- state is plain data under its schema, and a host object lives only in its owning proc;
- buffers are bounded by their loss semantics; a dependency is a channel a proc parks on, and back-pressure is a full one;
- procs run on fibers, and a blocking foreign call gets a declared thread; no thread, future or atom lives outside procs, except an edge reader for a blocking read;
- a proc reports its own problems as facts and its changes as events, and steps only when a message arrived;
- the work is done when the count of messages reaches zero: each one promised is counted, each one taken is taken off, and zero is quiet ([below](#idioms-for-seon));
- a proc never binds an out-port to its own in-port's channel: Flow names a channel by one port, so the in-port's close is missed and the proc reads a closed channel forever. A proc that needs a later step of its own asks another proc that answers on its in-port (the writer asks the flusher for its `:commit`).

## Messages are made from their schema

A message one proc or process sends another is made by ```seon.contract/message```: the keys its map schema names, checked there, so the schema is the one list of what crosses, and a key the receiver gains reaches it with no edit at the sender. A hand list of keys at a send dropped each new key in turn: the api's `:process?`, the test batch's candidate, an item's `:write-out` and the effect's request (`:seon.prove/request`, `:seon.test/candidate`, `:seon.prove/held-candidate`, `:seon.prove/item-request`, `:seon.fx/admission`). Health's `:message-lists` law reads a send whose line takes a `select-keys`; the sends that keep one are a reply's chosen part or a message yet to be given its schema:
- `seon.system/end-agent`, `seon.system/ext-op`: a reply of an agent's `:status` and `:ended-ms`, and of the build's counts.
- `seon.system/await-message`: an await's own `:ms` and `:due` from its options.
- `seon.agent-proc/agent-frame`: the status fields a frame carried.
- `seon.vector/round`: an embedding round's point.
- `seon.web/http-in`: the render messages `:toggle` and `:at` (web's, to be made from a schema).

## Idioms for Seon

How Seon meets each need on Flow.

- **Dependency:** a conn; the consumer parks on its in-port, so there is no start order. A join reads only what it still lacks (`::flow/input-filter`).
- **Backlog and admission:** the in-port is the queue, sized by loss semantics. An owner admits by leaving `:in` out of its read set at its cap, so askers park (`seon.fx/owner-proc`, `seon.fx/bound`); its workers compete for one shared in-port (`seon.fx/worker-proc`; `seon.patterns-test/a-backlog-is-the-unread-queue`, `seon.patterns-test/workers-compete-for-one-shared-in-port`). A kind with `:batch n` hands a free worker its head request with those queued behind it (`seon.fx/dispatch`); a kind with `:open` makes its host object on the worker's first item and keeps it (`seon.fx-test/a-batch-kind-takes-what-is-queued`, `seon.fx-test/a-worker-performs-a-batch-in-one-call-and-opens-once`). A request of a `:seon.fx/class` is admitted by the config's dials, read live ([config](config.md#the-test-services-three-limits); `seon.fx/hold-reason`, `seon.fx/limits`; `seon.fx-test/a-dial-turned-holds-at-the-next-admission`, `seon.fx-test/admission-reads-nothing-off-the-machine`); proving's items are such requests of the `:process` kind. A request whose `:req` names `:seon.fx/ask` (a test batch, which the agent runs) holds no worker: the owner asks the api for it as an output and takes its one answer on its `:answers` port, sized for every request admission lets be in flight, so no step waits inside and no answer parks (`seon.fx-test/a-test-batch-is-asked-of-the-api-and-answered-once`).
- **Delayed retry, or any wait past a bound:** in-ports are fixed at init, so the proc has one in-port for its dues and ```seon.step/put-after!``` puts the message there once ms pass, the one timer ([channel idioms](#channel-idioms), [clocks](clocks.md)).
- **Request and answer:** a request carries `:reply [pid port]`; the answer is one output to that coord, `{:value}` or `{:error typed}`. With credit: the asker leaves `:ask` out of its read set at N outstanding, its answer in-port holding N; without it, bounded request and reply deadlock.
  - **The asker's side has one implementation:** ```seon.step/pend```, ```seon.step/ask``` and ```seon.step/answered``` (a second answer finds nothing), used by root's api (`seon.system/pend`, `seon.system/deliver-rid`), the reconciler (`seon.reconcile/request`), the render proc (`seon.render.proc/ask`) and the web proc.
  - **An edge asks one way:** a caller outside the graph puts its request with a fresh promise as `:p` through ```seon.step/ask-edge```; a closed channel answers at once (`seon.step-test/an-edge-asks-one-way`, `seon.main-test/an-edge-asks-one-way`).
  - **A throwing step's message is answered:** Flow's error chan is piped into the api's `:back` (`seon.system/start!`), and `seon.system/answer-taken` answers the caller with the defect, once; the api keeps the batch it took in `::taken`, which survives Flow's revert. The defect is also a `:proc-defect` event (```seon.system/api-proc```), so a wait on events ends with it.
  - **An answer and the fact it made arrive on two ins, in no set order:** a step that reads main after its own write holds the answer (```seon.reconcile/behind?```) until `:main` brings its t (`seon.reconcile-test/a-step-reads-the-db-its-own-write-made`).
- **A fact or a channel:** what must outlive its process (a message, a test's result, a request answerable after a restart) is a fact first, and only a fact true when it was written: a process's liveness is a live value, never stored, written by its one writer; a channel carries only the notice (the writer's commit to `:main`, a `:wake` to the reconciler) and live requests and answers. A message to an agent is a fact on main (```seon.task/message-tx```) plus a wake, and the agent takes it onto its branch at its next act (```seon.agent/undelivered```).
- **Failure:** typed errors are answers, not throws ([errors](errors.md#errors)); a throw is a defect, answered as above.
- **Quiet:** Flow can't see it, so count messages: +1 when promised, −1 when taken; zero is quiet. A stall is named from each queue's fill, capacity, writer and reader's read set.

## Flow inside an agent

An agent is a Flow graph of three `:io` procs on its main carrier, so it stays one OS thread and forks its test children and clones as before (```seon.repl/serve!```, [processes](processes.md#the-agent-process)).

- **One active thread:** such a graph keeps one active thread through `start`, `resume`, `inject`, `ping` and `stop`, so a fork succeeds at each (`seon.lib/fork!`) ([machine](machine.md#what-a-spawn-costs)).
- **A wait inside a step is an edge's ask:** the `:repl` proc's eval may wait for root's answer to its own request; that wait is a promise the `:transport` proc delivers by key (```seon.agent/await-reply```), and the fiber parks meanwhile. Root's interrupt stops a parked eval; a busy loop that never parks is stopped only by its tick budget or root's signal.
- **A fiber's allocation is its own:** a byte or tick budget (`jolt.host/run-interruptible`) counts only its eval's fiber, never the `:tests` proc's work beside it. A measure (`jolt.host/ticks-of`, and a `jolt.host/ticks` delta inside one) counts the fibers its body spawns too, and a stranger's none (Jolt gate tickslineage).
- **A step past its proc's `:step-ticks` ends there,** reported on `::flow/error` as any throw, and reaches the proc as a message, cid `::flow/step-ended` with `{:msg :cid :ticks-spent :ticks-budget}`, taken on its next read, so it answers its asker with a typed error; a proc that declares `:step-ticks` handles that cid (Jolt gate flowstepended).
- **A run is the `:repl` proc's state:** `seon.turn/work!` in an eval hands its run to the proc (```seon.repl/*run*```), which steps it with ```seon.turn/step-run``` on each answer: every wait of a turn is an output (`:seon.turn/output`: main's merge, the observation, the verdict, the model call), run on a fiber of its own (the edge ask) and back on the proc's `:run` port, so no step waits and the run answers ping and stop throughout (```seon.turn-test/a-run-answers-ping-while-main-merges```). A hand-back (```seon.turn/hand-back-step```) is a run on the same protocol. Requests that arrive meanwhile wait in the proc's queue and run in order after it; root's `:stop` ends the run at once, `:waiting`, its call in flight dropped (its owner does not cancel it yet: task `seon.fx/a-model-call-is-cancelled-at-its-owner`). Root's reply to the `work!` eval is sent at the run's end (```seon.turn-test/a-run-answers-ping-and-stop-between-turns```).
- **The bound:** the `:repl` and `:tests` in-ports each hold `:seon.config.agent/inbox-size`; at it the transport stops reading until one is taken.

## Every process is a Flow process

The owner's rule (decisions, 2026-11-16): every process Seon starts runs a Flow graph, agents included, and nothing outside one. A Seon process's state is its procs'; a foreign tool (git, zsh, tar, ps) is no Seon process and runs only as a bounded effect through its kind's owner. Health's `:stateful-outside-flow` counts what is left in `src` ([health](health.md#state-or-a-wait-outside-flow)). The census, each kind with its state and the task that converts or deletes it:

| Process | Today | Owner and task |
|---|---|---|
| The system process (the binary's main, the fork point) | the writer process itself, its fork server a child; no graph of its own | deps: a Flow graph on its one thread, its executors all fibers (``seon.main/the-binary-main-is-the-system-process``) |
| The writer process (its first fork: writer, api, reconciler) | a Flow graph (`seon.graph`); outside it the server's accept and connection threads, `seon.main`'s stdin thread | web and flow: one proc per connection (``seon.server/one-listener-for-roots-life``); flow: the stdin thread goes when the system process owns start and stop (``seon.step/roots-procs-are-the-first-forks-graph``) |
| The fork server | `seon.step/run-loop`, no graph | becomes the system process's graph (deps) |
| Agents | a Flow graph (`:repl`, `:transport`, `:tests`); outside it `seon.agent/state` (most of health's rows), REPL runs on their own threads, the fiber pump's thread | runtime: each field into its proc, the atom deleted, runs inside `:repl` (``seon.fork-server/the-system-process-loads-only-from-commit-notices``); flow: the `:transport` reconnect (``seon.step/roots-procs-are-the-first-forks-graph``) |
| Test children | forked from a multithreaded agent, no graph | tests2: forked by the system process at main's program, the branch's diff loaded, a one-proc graph (``seon.prove/tests-fork-from-the-branchs-warm-agent``) |
| Clones of an agent | forked from a multithreaded agent | runtime: forked by the system process, or deleted (``seon.fork-server/the-system-process-loads-only-from-commit-notices``) |
| The embed worker (`seon embed-worker`) | `seon.vector/serve!`'s loop, no graph | context: a one-proc graph, or deleted (``seon.ctx/context-values-are-computed-once-and-kept-by-digest``) |
| Seon-binary subprocesses of effects (`:tests`, `:process`, `:build`) | `bin/test` shards, `./seon --steps`, `bin/check`, `bin/build` | tests2: tests as system-process forks (``seon.prove/tests-fork-from-the-branchs-warm-agent``); deps: `:build` calls Jolt directly (``seon.main/the-binary-main-is-the-system-process``) |
| Foreign tools (git, zsh, tar, ps, lsof, sample) | bounded effects through their owner | stays so; `:evidence`'s scripts move inside (flow, ``seon.step/a-stall-is-read-from-flow-inside-the-writer-process``) |
| `bin/` scripts that start or hold processes | `system` and `held` (start, stop, the holder), `stalls` and `evidence` (a watchdog), `update`, `test`, `prove`, `full-run`, `check`, `jolt-gates`, the worktree scripts | deps: the system process holds (``seon.main/the-binary-main-is-the-system-process``); flow: the stall watchdog inside (``seon.step/a-stall-is-read-from-flow-inside-the-writer-process``); runtime: the `:update` proc (``seon.system/an-update-loses-nothing``); tests2: the test service inside; the worktree scripts go with worktree systems. `bin/mcp` and `bin/ask` stay: thin clients over ops, no process of Seon's |

**Every proc is seen** (owner, 2026-11-16: threads are fine; a process we can't see into, or one off the unified system, is not). A `:mixed` proc runs on a thread of its own, which no fiber dump shows, so a proc is read through Flow itself: its ping answers its status, its count and its site, the in-port and message it is stepping on or the out-port it is writing to, for every workload. That read covers the writer process's graph and each agent's, and is the view a person and an agent look at.

## Effects: one owner and its workers per kind

Every outside effect has one owner proc and a few worker procs per kind, declared as data in `resources/seon/system.edn`. ```seon.graph/read-decl``` reads it (`{:procs :fx :conns}`); `seon.graph/build` builds the graph; ```seon.graph/fx-owners``` answers the owners' pids, the only procs an agent's proc may address. ```seon.fx/procs``` builds the owner `:fx/<kind>` and workers `:fx.<kind>/<i>` on one work channel. ```seon.fx/declared-refs``` lists the file's symbols as reach edges, so a test that boots root reaches its procs.

An agent reaches an owner through ```my.fx/call``` (the outcome, with `{:prio :budget :budget-key}` options) or ```my.fx/send``` (a key now, the outcome later as a message); inside a test fork nothing leaves the process. The owner admits while a worker is free, the task has budget and the provider's bucket (```seon.fx/refill```: refills at the configured rate, halves on a rate limit, regains a twentieth otherwise, [clocks](clocks.md#time-decides-the-owners-exceptions)) covers the estimate; otherwise it waits for the owner's `:tick`, or is answered `:seon.fx/budget-spent`.

**A kind's bound:** an agent waits on an effect that does not stream as long as its kind declares in `:seon.config.fx/bounds` (its `:seon.bound/ms`, ten times the slowest measured call), then the outcome is the typed `:seon.bound/reached`, which the turn reads as the call's error (```seon.agent-test/an-effects-wait-ends-at-its-kinds-declared-bound```). A kind not named there waits until its bound is measured. A streaming call (one that names its `:call`) is ended by its owner, never cut by the agent: its bound is of quiet: its worker re-arms it with each line, so a reply that keeps streaming is never cut, and past it shuts the connection, answers `:seon.bound/reached` and is free for its next item (```seon.fx-test/a-stream-that-goes-quiet-past-its-kinds-bound-is-ended-reached```).

An owner holds at most `:seon.config.fx/buf` agent requests, then stops reading `:in`, so asking agents park. Root's own asks go to the owner's `:ask` port, which the bound never closes, so the api never waits behind agents (```seon.fx-test/the-api-answers-while-an-effects-queue-is-full```).

The performers are Vars of `seon.fx`, each answering `{:value ...}` or a declared error ([failure points](failure-points.md#edges-that-catch-each-names-its-one-variant)), among them ```seon.fx/echo``` (the test effect), ```seon.fx/model``` (`seon.model/complete`; its `:cost` settles the bucket and the task's spend) and ```seon.fx/tree-of``` ([health](health.md#checks-are-callers-of-seon)).

**A streaming perform** answers `{:stream {:pull :init :step :final :close}}` in place of its outcome, as ```seon.model/complete``` does for a request naming its model call (```seon.turn/model-request!```). Since a step's outputs leave only when it returns, ```seon.fx/worker-proc``` starts one edge reader (```seon.fx/read-lines!```) that puts each whole line on the worker's `:lines` in-port, then `{:end true}` or `{:error e}` (a socket failure is `:seon.model/transport`, from `seon.model/http-post`). ```seon.fx/stream-step``` folds each line and outputs to `[:web :streams]`, reading only `:lines` while the call is in flight, then gives the owner `:done` once. ```seon.model/sse-step``` is also `seon.model/sse-reply`'s parser, and ```seon.model/json-feed``` reads a tool call's arguments as they arrive; what streams is display only, and the committed reply is read from the whole arguments. A batch kind's perform answers the same map for its whole batch, its `:final` one outcome per request; a stream marked `:frames` gets each value its `:pull` answers as one line, whole (the `:embed` helper's framed answers, ```seon.vector/embed```). A wait on a host (a model's load, an HTTP reply) is the reader's, never the step's, so a stop's transition calls the stream's `:shut` at once (```seon.fx-test/a-batch-call-in-flight-ends-at-the-stop```).
