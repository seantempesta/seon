---
type: research
status: proposal
created: 2026-09-23
lane: flow-join-research (Opus 5.5, read-only, web research)
tags: [research, bounded-execution, flow, core.async, executor, stop, join]
extends: docs/research/agent-platform/hangs-root-causes-and-solutions-2026-09-23.md (Option B1)
---

# Flow stop as a join, without forking core.async

The owner, 2026-09-23: "I don't think we should be forking core.async? launch a research
opus agent to do web research and to look for more clojure like solutions that don't
involve hacking everything."

Option B1 in the hangs note proposed a core.async fork that exposes each proc loop's
`Future`. This note finds the idiomatic answer: Flow already lets the caller supply the
`Executor` that runs every proc loop. **An executor Seon owns per graph is the join.**
The JDK's `ExecutorService.shutdown` and `awaitTermination` are the join and its
deadline. `isTerminated` answers "may this graph's agent restart".

Pinned dependency: `org.clojure/core.async 1.10.874-alpha3` (`deps.edn:23`), source at
`reference-code/core.async`, commit `dc35f3e0d` ("prepare release v1.10.874-alpha3").
All `impl.clj` lines are `src/main/clojure/clojure/core/async/flow/impl.clj`, `flow.clj`
lines are `.../core/async/flow.clj`, and `spi.clj` lines are `.../core/async/flow/spi.clj`.

## 1. Recommendation

**Each graph gets its own `ExecutorService` as its `:io-exec`. Stop is
`flow/stop`, then `.shutdown`, then `.awaitTermination` with the time left before one
deadline. Nothing in core.async changes.**

Construction, in the one seam every graph already passes through
(`seon.flow/start-graph!`, `src/seon/flow.clj:71-93`):

```clojure
(defn- graph-executor
  "One graph's proc-loop executor: a virtual thread per task, projection bound."
  [graph-name projection-state]
  (Executors/newThreadPerTaskExecutor
   (reify ThreadFactory
     (newThread [_ runnable]
       (.unstarted (.name (Thread/ofVirtual) (str graph-name))
                   (fn [] (schema/call-with-projection-state
                           projection-state #(.run ^Runnable runnable))))))))
```

`start-graph!` assocs it as `:io-exec`, starts the graph, and returns it in the
construction map beside `::graph` (for example `::executor`).

The join (`seon.flow/stop-graph!`, one function):

```clojure
(flow/stop graph)                       ; the command (impl.clj:174-183)
(.shutdown executor)                    ; no new loop can start on it
(if (.awaitTermination executor remaining-ms TimeUnit/MILLISECONDS)
  :exited                               ; every proc loop has returned
  <typed timeout naming the graph>)     ; B3: keep custody, watch the exit
```

Restart refusal: `arm!` refuses by name while the agent's previous entry's
`(.isTerminated executor)` is false. This reads a JDK fact about threads that exist. It
is not a flag Seon keeps.

**Estimated lines:**

- +12 for `graph-executor`.
- +3 for `start-graph!` to assign it and return it.
- +15 for `stop-graph!` with its typed timeout.
- +6 for the `arm!` refusal.
- +5 per surviving stop transition to catch its own failure (§3.4).
- −25 for the two `projection-executor` reifies it replaces (`src/seon/cluster.clj:3307-3320`,
  `src/seon/flow.clj:1123-1135`).
- About −80 for the hand-published completion channels that the hangs note's B1 already
  lists for deletion (`:seon.agent/turn-stopped`, `:seon.turn.loop/completion`,
  `:seon.render.web/completion`, the fan-out completions, `proc-stopped`).

That comes to about **+45 / −105**, against B1's fork of about +25 in `flow/impl.clj`
plus the same Seon-side deletions. The fork is gone and the lines are fewer.

## 2. The Flow behaviours this relies on, with file:line

1. **The caller may supply the executor for each workload.** `create-flow` takes
   `:mixed-exec`, `:io-exec` and `:compute-exec`, each "the Executor to use for the
   corresponding workload, in lieu of the lib defaults" (`flow.clj:100-103`). The impl
   asserts each is an `Executor` (`impl.clj:55-57`). The resolver returns the supplied
   one, or the library default when it is nil (`impl.clj:148`).
2. **The proc loop runs on that executor, as one task.** `process` documents that
   `:workload` "dictates the type of thread in which the process loop will run"
   (`flow.clj:272-274`). The impl picks `:io` for both `:io` and `:compute` workloads
   (`impl.clj:262`) and submits the whole `run` loop as one `FutureTask`
   (`impl.clj:29-36` builds it and calls `.execute`; `impl.clj:323` submits it).
   **Probe P1 and P3 observed it:** the loop and both transitions ran on
   `VirtualThread[...,probe-io-0]` and `probe-loop-0`, the supplied executor's threads.
3. **The task returns only when the loop exits.** The loop recurs until the status is
   `:exit` (`impl.clj:321-322`), and `::flow/stop` maps to `:exit`
   (`impl.clj:199-207`). The SPI's contract says that on `::flow/stop` "any thread(s)
   should exit ordinarily" (`spi.clj:43-45`). So "every task on this executor has
   completed" means "every proc loop of this graph has exited".
4. **The stop transition is the loop's last act, on the proc thread.** On a stop
   command, `handle-transition` calls `(step state ::flow/stop)` (`impl.clj:209-217`).
   It does this from the paused path (`impl.clj:284-285`), the running path
   (`impl.clj:297-300`), or a blocked output (`impl.clj:235-237`, which returns `:exit`
   so `send-outputs` falls out, `impl.clj:221`). The loop then falls out. The executor
   therefore terminates only after every stop transition has returned, so cleanup is
   inside the join. Probe P1: the transition ran 319.8 ms after `stop`, immediately
   after the blocked transform was released, and termination followed 0.31 ms after
   the release.
5. **`stop` is a command, not a join.** It sends `::flow/stop` on the control channel,
   closes `error-chan` and `report-chan`, and clears the running channels
   (`impl.clj:174-183`). Probe P1: `stop` returned `true` in 0.142 ms while the proc was
   still inside its transform.
6. **Flow never submits to `:io-exec` after start.** The only `:io-exec` submission is the
   loop at `spi/start` (`impl.clj:262,323`). `inject` uses the library's global `:io`
   executor, not the supplied one (`impl.clj:197`, `futurize` with the keyword `:io`
   resolved at `impl.clj:31-33`). So `.shutdown` right after `flow/stop` cannot reject
   work Flow still needs. A later `flow/start` of the same graph object would be
   rejected at `impl.clj:35`, and `start-proc`'s catch stops the rest and rethrows
   (`impl.clj:163-165`). Seon never restarts a graph object, because `start-graph!`
   creates a new one each time (`src/seon/flow.clj:85`).

## 3. What it guarantees, and what it gives up

### 3.1 Guarantees

- **Stop means stopped, observed from the thread owner.** `awaitTermination` returns
  true only when every proc-loop task of that graph has returned, including its stop
  transition. This is the JDK's own contract: it "blocks until all tasks have completed
  execution after a shutdown request, or the timeout occurs, or the current thread is
  interrupted" ([ExecutorService javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ExecutorService.html)).
- **Absence is not health.** A proc that never exits leaves `isTerminated` false.
  Probe P2: a stop transition that throws leaves the proc alive, and
  `awaitTermination(300)` answered false.
- **A bounded, typed answer.** The deadline is the argument to `awaitTermination`. Its
  expiry returns false, and never a silent pass.
- **Restart cannot overlap an exit.** `isTerminated` is a fact about live threads, read
  at the point where `arm!` decides.
- **Cost proportional to procs.** The idle join took 0.48 ms (P1b). A per-graph
  thread-per-task executor holds no pool and no idle threads. Its only state is the
  set of its live threads. The library's own `:io` default is also a virtual thread
  per task on this JDK (`dispatch.clj:82-89`), so moving the loops from the shared
  `:io` executor to a per-graph one changes the executor's owner and nothing about
  the kind of thread.

### 3.2 What it gives up, and the known gaps

1. **Graph granularity, not proc granularity.** The join says "this graph has exited"
   and gives no `{pid Future}`. Agent graphs are one agent each, so this is the unit
   the restart refusal needs. For the fault diagnostic, name the graph's threads by
   graph (the `.name` above). The turn thread's stack is already held
   (`src/seon/cluster/agent.clj:1044`, the B3 fault record). Naming the individual
   stuck pid would need Seon's `var-process` launcher (`src/seon/flow.clj:175-179`)
   to wrap the `:resolver` so that `get-exec` tags the task with its `:pid`. The SPI
   does hand the resolver in (`spi.clj:72-86`), but this is deferred until a report
   actually needs the pid.
2. **`:compute` transforms outlive a timed-out wait.** A `:compute` proc submits each
   transform to `:compute-exec` and waits up to `compute-timeout-ms` (5 s by default,
   `impl.clj:245,259-260`). On timeout the loop reports the timeout and continues, and
   the transform keeps running on the compute pool. The per-graph `:io-exec` joins
   the loop, not that orphan. In Seon today only the work launcher's
   `capacity-observer` is `:compute` (`src/seon/flow.clj:217-220`), and it passes the
   root's shared bounded pool (`src/seon/flow.clj:654`). The rule "a `:compute`
   transform must not block" (`flow.clj:285`) is what bounds it. If that ever fails,
   the fix is a per-graph wrapper counting in-flight compute tasks around the shared
   pool. It is not needed now.
3. **`inject` futures are not joined.** They run on the library's global `:io`
   executor (`impl.clj:197`). They are one put each, and a proc whose loop has exited
   is gone either way. This is recorded, not handled.
4. **A stop transition that throws is a live leak today, with or without this change.**
   The throw is caught by the loop's outer `catch` (`impl.clj:317-320`), which keeps the
   previous status (`:running`). The loop recurs and parks on a control channel that
   will never carry another stop, because `stop` already cleared the channels
   (`impl.clj:181`). The error `>!!` goes to the just-closed `error-chan` and is
   dropped (`impl.clj:180,318`). Probe P2 reproduced it in a throwaway JVM: the proc did
   not exit, and no error was observable. `.shutdownNow` does not rescue it either: the
   interrupt surfaces in the parked `alts!!`, the same catch absorbs it, and the loop
   parks again.
   - The join turns this from invisible into a loud typed timeout. That is the wanted
     behaviour.
   - Seon's side of the rule: every surviving stop transition catches its own failure,
     records it through Seon's core-fault path (the error policy in `AGENTS.md`), and
     returns the state. A step must never throw from `::flow/stop`.
   - This extends the existing issue class
     `flow-stop-transition-errors-are-dropped-on-a-closed-error-channel` (cited in the
     hangs note §3). That issue should add the leak, which is the worse half.

## 4. The deadline and "refuse restart until exited"

- **One deadline for many graphs.** Compute the deadline once (`System/nanoTime` plus
  the configured bound). Call `flow/stop` and `.shutdown` on every graph first, so they
  all wind down concurrently. Then call `awaitTermination` on each with the time
  remaining. The total wait is bounded by the deadline, not by the sum of the bounds.
- **Order in `disarm-agents!`** (`src/seon/cluster.clj:3505-3525`) is unchanged: the
  listener first, the armer quiesced, agent graphs joined, then the cluster graph. Only
  the join primitive changes, and each hand-published completion wait converts to it.
- **On expiry** (hangs note B3):
  1. Record the fault with the held turn thread's stack.
  2. Keep custody: the routing entry and the branch connection stay held.
  3. `arm!` refuses that agent by name while `(.isTerminated executor)` is false.
  4. One virtual-thread watcher calls `(.close executor)`, which is shutdown plus
     await until done, and then releases custody. `close` is the JDK's own "wait until
     all tasks have completed and the executor has terminated", so the watcher is one
     call, not a loop.
- **Interrupt stays a request.** The existing transform interrupt
  (`src/seon/cluster/agent.clj:1036-1050`) remains the way to shorten a wait. A parked
  proc loop is never interrupted, which matches the leak mechanics in §3.2.4. Never use
  `shutdownNow` as termination.

## 5. Rejected alternatives and why

| Alternative | Why rejected |
|---|---|
| Fork core.async to expose `{pid Future}` (hangs note B1) | The owner rejected it. It is also unnecessary: the public `:io-exec` option (`flow.clj:100-103`) already puts the loop on an executor Seon owns. |
| The stop transition delivers a promise or latch (Seon's current pattern) | This is the hand-published completion mechanism the hangs note already deletes. Every step must remember to publish. It fires a few instructions before the loop's task actually returns. It never fires when the transition throws, and then the proc lives on (§3.2.4). The executor observes the thread itself. |
| Keep the `FutureTask` that `spi/start` returns (Seon's `var-process` wrapper sees it at `src/seon/flow.clj:175-179`; `impl.clj:323` returns it) | The SPI says the return of `start` is "ignored" (`spi.clj:72-73`), and that a launcher "should acquire no resources, nor retain any connection to the started process" (`spi.clj:19-21`). Relying on it relies on an implementation detail the contract disclaims. |
| A wrapper `Executor` that records each submitted `FutureTask` (it is a `Runnable`, `impl.clj:34-35`) | It works, but it is a hand-kept mirror of what `ExecutorService` already tracks. `isTerminated` and `awaitTermination` are that record, owned by the JDK. |
| A `Phaser`, `CountDownLatch` or counter wrapped around the shared `:io` executor | This hand-rolls termination accounting. Phaser termination at zero parties makes a late registration return a negative phase silently. `ExecutorService` has the same semantics with none of that code. |
| `flow/ping` / `ping-proc` to observe exit | After `stop`, the running channels are nil, so `ping` throws "flow not running" (`impl.clj:70,76-80,181`). Before `stop`, a ping reports `::flow/status` (`impl.clj:272-279`). That is a liveness sample, not a terminal event. |
| A report or error channel "stopped" event | None exists. `stop` closes both channels immediately (`impl.clj:179-180`). The docs describe `report-chan` as ping replies and explicit reports only (`flow.clj:108-120`). |
| `Future.cancel(true)`, `shutdownNow`, `Thread.stop` | An interrupt is a request. A parked loop absorbs it and re-parks (`impl.clj:317-320`). The hangs note §5 already rejects these as termination. |
| `StructuredTaskScope` | It is a preview API through JDK 25 (JEP 505). `newThreadPerTaskExecutor` plus `awaitTermination`/`close` gives the same join with a final API, and it fits Flow's `Executor` parameter directly. |

## 6. Web findings: what Flow intends for stop

- **The intended stop contract is cleanup in the transition, then ordinary thread exit.**
  - The [flow guide](https://clojure.github.io/core.async/flow-guide.html) says the
    transition arity is for "the creation, pausing, and shutdown of external
    resources".
  - The [SPI docs](https://clojure.github.io/core.async/clojure.core.async.flow.spi.html)
    say that on stop "any thread(s) should exit ordinarily". This is the same text as
    `spi.clj:43-45`.
- **No stop-completion observation is documented.** Neither the
  [guide](https://clojure.github.io/core.async/flow-guide.html), the
  [rationale](https://clojure.github.io/core.async/flow.html), the
  [API docs](https://clojure.github.io/core.async/clojure.core.async.flow.html), nor the
  [announcement](https://clojure.org/news/2025/04/28/async_flow) describes a way to
  learn that procs have exited after `stop`.
  - The guide lists `stop` only as "Stops all procs in the flow".
  - `ping` is described as a status sample.
- **flow-monitor** ([repo](https://github.com/clojure/core.async.flow-monitor)) visualizes,
  pauses and resumes. Its README describes no exit observation.
- **Where the executor fits.** The rationale says processes "start threads (in fully
  user-configurable thread pools)" ([flow.html](https://clojure.github.io/core.async/flow.html)).
  The user-owned executor is the designed extension point for threading. That is the
  seam this note uses.
- **No community thread found.** Searches found no Ask Clojure, ClojureVerse, JIRA or
  GitHub thread about joining a Flow stop. Flow issues are filed in the core.async JIRA
  project, and no matching ticket appeared in search. The pinned history's latest
  Flow tickets are ASYNC-275 (`futurize` returns `FutureTask`) and ASYNC-277
  (`:ping-map-fn`), in `git log` of `reference-code/core.async`.

## 7. What other Clojure systems do

- **Integrant**: `halt-key!` "tells Integrant how to stop and clean up after a key".
  Its return is discarded, and by convention it returns after the resource is released
  (for example `(.stop server)`)
  ([README](https://github.com/weavejester/integrant/blob/master/README.md)).
  - Component's `stop` and mount's `stop` follow the same convention: a synchronous
    function whose return means "done". This is from general knowledge; neither README
    was fetched in this lane.
  - **The lesson that transfers:** Seon's stop should be one synchronous function whose
    return means exited. That function is `stop-graph!` above, bounded.
- **Missionary** separates the command from the terminal event. Cancellation is
  best-effort, "a 'pretty please', not a do-or-die order", and supervision waits for
  each child's own termination
  ([README](https://github.com/leonoel/missionary),
  [hello task](https://github.com/leonoel/missionary/blob/master/doc/tutorials/hello_task.md)).
  - **The lesson that transfers:** termination is observed from the runtime that owns
    the thread, never inferred from the command. Here the runtime that owns the thread
    is the `ExecutorService`.
- **manifold and JDK executors**: shutdown plus awaitTermination is the standard JVM
  idiom for "stop and know it stopped". Seon adopting it for Flow's documented executor
  parameter is the plain-Clojure, plain-JVM answer.

## 8. Probes

### P1, P1b, P3 (default JVM, MCP `eval_clj`, JVM mode, namespace `tmp.flow-join-probe`)

These are my own tiny graphs with anonymous step fns and fresh executors. No Seon graph
was touched and nothing was redefined; the form is a single `let`.

- **P1:** one `:io` proc on `(Executors/newThreadPerTaskExecutor (.factory (.name (Thread/ofVirtual) "probe-io-" 0)))`.
  Its transform blocks on a promise. After `stop` and `.shutdown`,
  `awaitTermination 50 ms` answers false. The promise is released 250 ms later, and
  then `awaitTermination 1000 ms` answers true.
- **P1b:** the same with an idle proc.
- **P3:** a `:compute` proc with separate `:io-exec` and `:compute-exec`. It records
  which thread runs each arity.

Observed values:

```
p1  {:stop-returned true, :stop-ms 0.142,
     :await-50ms-while-busy false, :early-wait-ms 63.2,
     :terminated-before-release false,
     :joined-after-release true, :join-after-release-ms 0.31,
     :stop-transition-ran-after-stop-ms 319.8,
     :loop-thread "VirtualThread[#193,probe-io-0]/runnable@ForkJoinPool-1-worker-2"}
p1b {:idle-joined true, :idle-join-ms 0.48}
p3  {:threads [[:transition ::flow/resume "VirtualThread[#196,probe-loop-0]..."]
               [:transform "VirtualThread[#198,probe-compute-0]..."]
               [:transition ::flow/stop "VirtualThread[#196,probe-loop-0]..."]],
     :loop-exec-terminated true, :compute-exec-terminated true}
default :io executor: clojure.core.async.impl.dispatch$make_io_executor$reify__528
```

### P2 (a throwaway JVM, so that its deliberately leaked parked thread dies with it)

Script: `$SCRATCH/p2/p2.clj`. Command:

```
clojure -Sdeps '{:deps {org.clojure/core.async {:mvn/version "1.10.874-alpha3"}}}' -M p2.clj
```

The same graph as P1b, run twice: once with a normal stop transition, and once with a
stop transition that throws.

```
{:throw? false, :joined-within-300ms true,  :ms 5.87,   :error-seen-on-closed-chan false}
{:throw? true,  :joined-within-300ms false, :ms 302.58, :error-seen-on-closed-chan false}
```

### Regression the implementation should add

This is the shape of the regression, not installed code. It goes in the flow tier.

- **(a)** A proc whose transform blocks on a never-delivered promise.
  `stop-graph!` with a 200 ms deadline returns the typed timeout naming the graph
  within 250 ms. `arm!` for that agent refuses by name. Delivering the promise lets the
  watcher's `close` return, custody releases, and `arm!` succeeds.
- **(b)** An idle graph. `stop-graph!` returns `:exited` in under 10 ms.
- **(c)** A step whose stop transition fails. Seon's guard records the fault, and the
  graph exits: `:exited`, plus one error fact.

## 9. Timings

| Operation | ms | Over 1 s? Justification |
|---|---|---|
| `runtime_status` default | n/a (MCP) | no |
| P1+P1b+P3 probe form (MCP round trip) | 392 | no. It contains a deliberate 250 ms sleep, a 50 ms bounded wait and a 50 ms sleep, so its length is those waits. |
| `flow/stop` return (P1) | 0.142 | no |
| `awaitTermination` while busy (P1, 50 ms bound) | 63.2 | no. This is the bound firing, as designed. |
| Join after release (P1) | 0.31 | no |
| Idle stop + join (P1b) | 0.48 | no |
| P2 normal stop + join | 5.87 | no |
| P2 throwing transition, bounded wait | 302.6 | no. This is the 300 ms bound firing, as designed. |
| P2 throwaway JVM, wall clock | 4,910 | **yes.** A fresh JVM's startup plus loading core.async from its jar, proportional to JVM boot and not to the probe. It is required because P2 deliberately leaves a parked proc thread alive (§3.2.4), and that must not accumulate in `default`. |
