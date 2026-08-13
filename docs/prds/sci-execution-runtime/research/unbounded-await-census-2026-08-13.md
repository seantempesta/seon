---
type: research
status: complete
tags: [runtime, agent, flow, testing]
---

# Unbounded await census — 2026-08-13

## Question and method

Which waits in `src/` can park forever, which are already bounded by a declared
fact, and which are process-lifetime loops rather than completion awaits?

The census searched every `.clj`/`.cljc` source for `deref`, `@`, `<!!`,
`>!!`, `promise`, `promise-chan`, `Future.get`, `CompletableFuture.join`, and
`Process.waitFor`, then read every candidate at its call site. Ordinary atom,
volatile, delay, Datahike connection, `Optional.get`, `Atomic*.get`, map `.get`,
and dereference of an already-created `reduced` value are not waits and are
excluded. Line numbers below name the 2026-08-13 tree after the agent-turn
repair.

## Verdicts

| Site | Event awaited | Bound | Verdict |
|---|---|---|---|
| `src/seon/cluster/agent.clj:271-291` | the armed-ready turn completion permit | `:seon.config.agent/turn-completion-backstop-ms` | **fixed here**: `alts!!` preserves the permit event and expiry throws a typed diagnostic naming agent and held run |
| `src/seon/cluster/agent.clj:588-630` | completion or `turn-stopped` after Flow stop | same config fact | **fixed here**: the original reproducer's unbounded `<!!` is now an event race with a loud bound |
| `src/seon/cluster/agent.clj:550` | initial put of `::ready` | none needed | **not an unbounded wait**: a fresh fixed buffer of one is empty and has exactly one producer before publication |
| `src/seon/render.clj:635-639` | admission to the context proc, then its reply | none | **open, agent-facing**: both the unbuffered put and reply take can wedge prompt construction; owned by [agent host crossings](../../../seon/issues/agent-facing-host-crossings-can-wait-without-a-bound.md) |
| `src/seon/effect.clj:358-378` | a capability handler `FutureTask` | none at this join | **open, agent-facing**: the SCI arm is adopted by the task, but a handler blocked in a host call need not re-enter SCI; same host-crossing issue |
| `src/seon/shell/jvm.clj:94-117` | stdout, stderr, or stdin virtual task termination | none at `Thread.join` / promise deref | **open, agent-facing**: child exit is separately bounded, but evidence capture can still strand the capability call; same host-crossing issue |
| `src/seon/shell/jvm.clj:245-271` | child termination after signal and foreground execution | shell/evaluation deadlines passed to timed `waitFor` / `Future.get` | **bounded** and loud; not a member |
| `src/seon/flow.clj:696-720` | launcher drain and launcher-proc stop | none | **open lifecycle join**: cancellation and terminal callbacks are the event half, but missing delivery parks cluster teardown; owned by [orderly-stop joins](../../../seon/issues/orderly-stop-completion-joins-have-no-bound.md) |
| `src/seon/flow.clj:803-830` | compute submission result | submission `time-limit-ms` | **bounded**: the promise deref uses the remaining declared submission limit |
| `src/seon/flow.clj:1101-1127` | source error-channel close while forwarding faults | task lifetime | **lifecycle loop**, not a synchronous completion await; the loop ends on source close and publishes its own completion |
| `src/seon/flow.clj:1122-1123` | source fault take and sink put | task lifetime / dropping buffer | **lifecycle transport**: the source take is the join task's purpose; the counted-dropping sink admits without backpressure |
| `src/seon/flow.clj:1129-1139` | fault-committer proc stop completion | none | **open lifecycle join**, in the orderly-stop issue |
| `src/seon/cluster.clj:2363-2386` | armer admission and quiescence acknowledgement | none | **open lifecycle join**: both the put and acknowledgement can strand `cluster/stop!`; in the orderly-stop issue |
| `src/seon/cluster.clj:2391-2402` | cluster loop, render, and search proc stop completions | none | **open lifecycle joins**: exact event ownership is correct, but there is no loud last-resort bound; same issue |
| `src/seon/render/web.clj:1283-1289` | http-kit socket queue drain or close | none | **open external delivery join**: an accepted write can retain a connection virtual thread forever; owned by [web delivery awaits](../../../seon/issues/web-delivery-awaits-have-no-bound.md) |
| `src/seon/render/web.clj:1295-1318` | first matching render package | none | **open request join**: a missed proc publication wedges initial HTTP rendering; same web issue |
| `src/seon/render/web.clj:1364-1381` | successive packages until tab close | connection lifetime | **lifecycle loop**: the thread exists to serve the open SSE connection and cleanup closes its tap; not a one-shot completion wait |
| `src/seon/test/runner.clj:1098-1107,1165-1169` | worker readiness/RPC protocol line | only the suite-wide silence watchdog | **open developer-facing boundary**: the call itself has no peer-naming bound; the observed confirmation wedge remains in [the existing issue](../../../seon/issues/confirmation-parallel-failure-blocks-reading-worker-protocol.md) |
| `src/seon/test/runner.clj:330-351` | concurrent `jcmd` dump futures | each child `waitFor` is bounded at line 315 | **eventually bounded**: every task must finish after its child's ten-second bound; the un-timed aggregation cannot exceed that task contract absent an executor fault |
| `src/seon/test/runner.clj:1210-1260` | exact worker process-tree exit | ten-second `CompletableFuture.get` before and after force | **bounded**: later exact-root `.get` / `.waitFor` follows the already-observed all-process exit event |
| `src/seon/test/runner.clj:1321-1322,1430,1564` | task, confirmation, and worker-launch futures | suite-wide silence watchdog only | **open developer-facing joins**: the global watchdog makes the process fail after silence, but these seams do not identify the missing peer; confirmation is the demonstrated member, and the existing issue owns the class |
| `src/seon/cluster/export.clj:118-125` | clone subprocess output and exit | none | **open foreign subprocess join**: merged output is drained before `waitFor`, but a child can still run forever; folded into [the subprocess issue](../../../seon/issues/operator-subprocesses-have-unbounded-read-and-wait-paths.md) |
| `src/seon/eval/drive.clj:61-80` | a database fact publication | caller `timeout-ms` | **bounded and event-driven**: listener stands before derive, and `alts!!` reports the missing label |
| `src/seon/artifact.clj:99-106` | JVM process lifetime | process shutdown | **intentional lifetime latch**: this is the artifact main thread keeping the service process alive, not admitted work or teardown; the shutdown hook owns stop |
| `src/seon/sci/kernel.clj:581-585` | dereference of `Reduced` call-preparation refusal | already realized | **not a wait**: `deref` unwraps an existing `Reduced` value synchronously |

## Result

The direct agent-graph completion class is bounded at both entrances: a lost
turn permit and an orderly disarm now fail under the same declared fact. The
remaining real waits divide into four owner-sized issues. No bare timeout was
substituted for an event; each issue preserves the exact completion event and
asks its owner to add the loud last-resort half.

`src/seon/render.clj`, `src/seon/render/web.clj`, and
`src/seon/test/runner.clj` were modified-uncommitted by other lanes during this
census. They were read and recorded but not edited.
