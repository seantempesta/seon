---
type: research
status: complete
tags: [research, agent, flow, web]
---

# Render process recovery audit

## Decision

Delete the SCI render cage now, with a small dispatch cleanup and process-level
regression proof. The critical routing is already built: prompt block symbols,
HTML surface symbols, and the canvas symbol are invoked by
`seon.execution.runtime` through `invoke-selected!` inside the existing
per-agent Bun execution child. The web feed calls only the compiled
`render-agent-view!` child entrypoint, and turn prompt assembly calls only the
compiled `render-prompt!` child entrypoint. SCI is no longer the production
isolation boundary for either primary path.

The remaining SCI call is nested inside the child. The transcript's AI
formatter uses `seon.render/render` to recursively format message/eval event
entities; if one of those nodes carries an agent-authored slot override,
`resolve-render` invokes SCI. Direct/test callers of `seon.render/render` can
also reach it. Those calls are already process-isolated in production, so SCI
adds interpreter semantics and environment reconstruction without protecting
the pod. Replace that branch with the same compiled `eval/lookup-value` call
used for core symbols, keep the walker's error guard, and prove a hung nested
renderer retires only the child.

A crashed or synchronously wedged Bun
process cannot be “uncrashed.” It can only be terminated, reaped, and replaced.
This is sufficient because Seon's durable understanding is database data and
source, while the child already treats compiler state and live result values as
disposable process-local artifacts.

The seam is already the current `seon.execution` invocation protocol and
`seon.execution.runtime/render-agent-view!`, not a new evaluator and not a new
render worker. Each agent already keeps one child for authored eval and authored
rendering. The one guarded recursive `seon.render` walker runs where its owning
compiled formatter runs; for production authored prompt rendering that is the
child. An authored render call inherits the strict parent-owned deadline on the
outer compiled invocation. On deadline, protocol
failure, signal, or abnormal exit, the host kills and retires the complete child,
records the affected eval/turn/run outcome through existing receipt/recovery
transitions, and lazily starts a clean child for the next invocation.

This is stronger than SCI's current protection. SCI interrupts interpreted
function and loop entry, but its own source documents that compiled host loops
and native regular-expression backtracking remain uninterruptible in
ClojureScript. Process termination bounds both classes.

Do not “roll back the database.” Commits that succeeded before the crash remain
true history. Do not rewrite a failed eval's source or pretend it did not run.
The durable receipt should retain the exact authored source and transition from
`:running` to `:interrupted`; the run can close `:crashed` when the child loss
invalidates the active turn. A later turn explains that execution was
interrupted, that the child was replaced, and that process-local runtime values
are gone. This resumes the agent's understanding without falsifying history.

## Shortest falsifiers

The design is rejected if any of these are true:

- a synchronous `(loop [] (recur))` in an authored render can block the pod's
  HTTP/feed event loop after the parent deadline;
- the host resolves the failed invocation but reuses the possibly mutating
  child;
- replacement requires replaying all historical evals rather than loading the
  canonical namespace source/program facts;
- a transaction committed before termination is silently retracted or hidden;
- the next prompt omits the interruption or implies that process-local
  `result/<id>` values survived;
- repeated render invalidation can create an unbounded crash/spawn loop; or
- moving authored rendering into the agent child creates a second render engine
  rather than returning data to the existing guarded walker.

## Production call trace and built/gap state

The earlier version of this audit described child routing as future work. That
was incorrect. Exact production tracing shows:

1. `seon.agent.turn/render-prompt` calls
   `execution.host/invoke-compiled!` with
   `seon.execution.runtime/render-prompt!`.
2. `render-prompt!` resolves every symbolic stored/derived prompt block through
   its injected `invoke-selected!`. `seon.execution/call-selected!` looks up and
   directly invokes authored or compiled functions in the child.
3. `seon.web.datastar/render-agent-view!` calls
   `execution.host/invoke-compiled!` with
   `seon.execution.runtime/render-agent-view!`.
4. `render-agent-view!` builds `html-call` targets for every symbolic context
   block plus the canvas block and resolves all of them through
   `invoke-selected!`. The pod receives only the bounded ordinary projection and
   passes it to the trusted `seon.ui.agent-view` presenter.
5. Canvas acquisition returns a literal hiccup vector or canvas function
   symbol. A symbol is part of the same `html-call` target set; the pod does not
   call it.
6. `seon.render/render` has one production source caller outside its own
   namespace: `seon.agent.ctx.transcript/format-transcript-block`. The compiled
   `transcript-block` itself is invoked through the prompt entrypoint inside the
   child. Its local fallback walker therefore also runs inside the child.
7. `render-entity-ai`/`render-entity-html` directly call resolved converter
   functions. Their active transcript/handler uses likewise run inside the
   child. They never depended on SCI for containment.
8. `seon.render.sci/invoke-bounded` has exactly one caller:
   `seon.render/resolve-render`'s agent-authored-symbol branch. There is no
   direct web/feed/turn/canvas caller.

Built: per-agent process isolation, parent deadlines, generation fencing,
poison-on-cancel/timeout, lazy child replacement, compiled prompt/view
entrypoints, child-local authored symbol calls, bounded result IPC, and
in-place pod presentation errors.

Remaining gap before deletion: prove the outer invocation deadline kills a
synchronously stuck *nested transcript walker* call and that feed/prompt callers
surface the existing child failure envelope. No routing architecture needs to
be built. The only source behavior change is direct lookup/call in
`resolve-render` after removing the SCI-specific envelopes.

## What Bun can and cannot recover

`Bun.spawn` returns a process handle with `pid`, `kill(signal)`, `exited`,
`exitCode`, `signalCode`, and post-exit `resourceUsage()`. Seon's
`seon.subprocess` owner already adds detached process-group termination,
TERM-to-KILL escalation, stream draining, byte caps, and exit classification.
`seon.execution.host` presently uses the same owner, captures bounded stdout and
stderr tails, resolves startup/active failures with PID, exit code, artifact
digest, and the invocation's database value, removes the dead child, and starts
a new child on demand. The parent deadline already cancels and retires a
non-settling child. The child also marks itself poisoned and exits after local
timeout or cancellation because a late `cljs.js` continuation may still mutate
compiler/global state.

Once a process exits, its JavaScriptCore heap, compiler atom, namespace vars,
global result stash, pending Promises, open database session, timers, workers,
and ordinary object identities are gone. Neither signals nor a process handle
restore them. Bun exposes heap *diagnostics* through
`Bun.generateHeapSnapshot`; these are inspector/V8 graph formats, not a restorable
runtime image. `Bun.JSC.serialize` covers structured-clone-compatible values,
not closures, stacks, compiler internals, sockets, or the global heap. Workers
have `terminate()` and shared/transferable data, but they are another disposable
isolate, not an in-place recovery API. Shared memory would also make the failure
boundary harder to reason about and provides no reason to bypass the already
working process boundary.

Therefore “uncrash” means replace plus rehydrate. A process that is blocked in
native synchronous code cannot execute an in-process timer, exception handler,
heap dump, or graceful cancellation. Only its parent remains capable of
enforcing the deadline and killing it.

## State survival matrix

| State | Survives child death? | Recovery source |
|---|---:|---|
| Database datoms and completed transactions | yes | current or historical database value |
| Basis transaction and commit ID | yes | ordinary database value/authority branch head |
| Eval source, narration, namespace, and receipt | yes | `:seon.eval/*` facts |
| Namespace and function source/index facts | yes | `:seon.ns/*`, `:seon.fn/*`, `:seon.schema/*` |
| Turn prompt/reply evidence | yes | turn facts and blob refs |
| Run/turn/eval terminal state | yes after recovery transaction | existing CAS-fenced recovery transitions |
| Compiler/analyzer state | no | reconstruct from current namespace/program source |
| Vars and global object values | no | re-evaluate canonical namespace sections as needed |
| `result/<id>` live values | no | honest prior-session miss; durable bounded result text remains |
| Pending Promises, timers, handles, sockets | no | never replay as if completed |
| Uncommitted in-memory mutation | no | intentionally discarded |

The last successful database value is not necessarily “the value before the
eval.” An authored function may have committed one or more valid transactions
before later looping or crashing. The terminal evidence should retain both the
invocation's pinned input database value and the authority's database value
observed when recovery commits. Where exact lineage matters, record/use the
existing basis transaction and commit ID vocabulary. Do not invent a generic
checkpoint coordinate.

## Rollback semantics

Seon's database is bitemporal and append-only in its historical truth. Four
different operations must not be conflated:

1. **Process replacement** discards transient runtime state only.
2. **Receipt recovery** CAS-transitions a still-`:running` eval to
   `:interrupted`, marks a running turn interrupted, and may close its run
   `:crashed`. `seon.runtime.recovery` already owns this cold-recovery shape.
3. **User-visible resumption** starts a later turn from current database truth
   and includes the interruption evidence.
4. **Database restore/branch** is an explicit administrative operation with
   retained source/target commit IDs and an undo branch. It is not automatic
   crash handling.

Automatic retraction of transactions is wrong. Seon cannot generally know
whether an external effect happened, and a database transaction completed
before the crash is real. Rewriting the eval's authored source to append a
synthetic crash message is also wrong; it destroys the byte truth. Instead,
retain source verbatim, add terminal interruption/error evidence, and derive a
concise next-turn line such as: “The previous evaluation stopped because your
execution child exited (deadline/exit signal). A clean child resumed from
database program state; prior live result values and pending work did not
survive.” The exact prose is presentation, not a new stored status.

## Rehydration without historical replay

The child already has the right recovery mechanism. `ensure-program!` acquires
the program projection for the requested symbols, constructs whole namespace
sections from `:seon.ns/source`, `:seon.fn/source`, require edges, and schema
rows, loads dependencies, and checks source/artifact identity. `eval-batch!`
retains self-host compiler state only as a performance artifact. A fresh child
can rebuild the current canonical namespace program; it must not replay every
eval receipt in temporal order.

This loses transient values by design. `result/<id>` is already documented as
process-scoped and returns an honest prior-session message after restart.
Namespace source and function definitions are durable code-as-data. The
recovery proof must include redefinition, failed definitions, aliases/refers,
protocols, schemas, tests, and instrumentation so replacement does not install a
semantically older namespace.

A “hot base child” is feasible only as a pool of already-started *empty* exact
artifact children. Bun has no portable fork/heap-clone API. A warmed child can
preload the execution artifact and self-host bootstrap cache, then bind an agent
and database session only when claimed, but today's startup contract binds
agent ID and database selection immutably. Changing that contract would add
pool ownership and admission complexity. First measure ordinary lazy replacement
latency. At roughly 219 MB private physical per loaded child, a pool can erase
latency only by paying substantial always-resident memory; it is unlikely to be
the default density win. A single spare per cluster may be justified only by a
measured p99 recovery target.

## Circular and infinite computation

No maintained Clojure/ClojureScript dependency makes arbitrary native
ClojureScript computation preemptible:

- SCI `0.15.56` (`9e9c78f4`) provides `:interrupt-fn` checks at interpreted
  boundaries and uncatchable interrupt markers. Its interrupt-aware sequence
  overrides help interpreted work, but the source explicitly says native regex
  interruption is JVM-only. Compiled host loops remain outside the interpreter.
- core.async `1.10.870-alpha2` schedules channel state machines and timers.
  Cooperative timeouts cannot fire while synchronous JavaScript blocks the
  event loop.
- superv.async (`3e6ed755`) supervises channel failures, stale pending work, and
  restart policies. Its watchdog also depends on timers/channel progress and
  cannot preempt a native loop.
- partial-cps (`1e119b03`) transforms selected effect boundaries and
  trampolines continuations. It deliberately keeps synchronous sections
  synchronous and yields only when a configured handler schedules a yield; it
  is not a general loop instrumentation or interruption transform.
- Babashka process is subprocess orchestration, not CLJS execution control.
  Nbb/Babashka runtime source is not vendored as an exact selected dependency,
  and adopting either would introduce another evaluator/runtime without
  solving arbitrary native loops inside the current child.

Static detection still has value as feedback, not containment. The analyzer can
warn about direct self-recursion, dependency cycles, and obviously constant
`while` conditions. It cannot soundly prove termination, bound data-dependent
recursion, or catch host/native calls. Dynamic event-loop lag also detects a
problem only after the owning event loop can run again. The robust bound is the
parent process deadline.

Render recursion itself should keep the existing guarded walker depth/node/
output bounds. A render function that calls another selected authored renderer
remains inside one child invocation and one deadline. The selected-call graph
can carry a per-invocation visited-symbol set to return a cycle error before
re-entering a known symbol, but process death remains the backstop for cycles
hidden in ordinary computation.

## Side-effect boundary

Moving renders to the execution child isolates CPU and heap failure; it does not
make rendering pure. An authored renderer currently receives ordinary arguments
and can call exposed compiled functions. The safest contract is:

- pin every render invocation to its input database value;
- expose read/query and pure rendering functions, not write or external-effect
  capabilities, when invoking an authored render;
- return only bounded ordinary data plus read-dependency evidence;
- never retry a failed render automatically, because an effect may have happened;
  and
- fence any unavoidable write-capable path with the active run ID and preserve
  transaction truth.

The current lexical selected-function capability is already appended only to
compiled composition roots; authored functions receive declared arguments.
That is the correct place to narrow the render capability. A dedicated render
child would improve least privilege and allow cheaper churn, but it duplicates
compiler/program memory and introduces another child registry and rehydration
path. Use the existing per-agent child first. Split only if measurement proves
render crashes are frequent enough that losing eval-local state is materially
harmful, or capability narrowing cannot be expressed at the selected-call seam.

## Render and feed behavior after a crash

The pod must never wait indefinitely for a child. A render invocation returns
one of: bounded rendered data, an ordinary error, or a parent-synthesized
process-exit/deadline error containing the invocation identity, PID/exit signal,
artifact digest, bounded stream tails, and pinned database value. The existing
walker renders the error in place and keeps sibling surfaces/feed connections
alive.

Do not cache a failure as rendered output. Any active render cache is keyed by
the relevant database value/read dependencies and the exact execution artifact
and source digest. Child replacement increments process generation and evicts
in-flight entries. A terminal recovery transaction changes the database basis,
which naturally invalidates affected derived units. To prevent a crash loop,
the same source digest that killed a child should receive bounded backoff or a
small circuit state in the host's process-local supervision data; database
source change or artifact change clears it. The durable error remains the
truth, while the circuit is merely transient resource protection. Do not store
renders or acknowledgement flags.

## Recommended implementation sequence

1. Add one execution-host integration regression whose selected compiled prompt
   block reaches the transcript walker and then an authored synchronous loop.
   Assert the parent deadline retires the child, the pod-side promise settles
   with the current failure envelope, another agent remains usable, and the next
   invocation spawns a clean generation.
2. Simplify `seon.render/resolve-render`: for every symbolic slot, use
   `eval/lookup-value`; return `missing-render` when absent and otherwise invoke
   the compiled function under the existing walker guard. Keep fault
   classification by `err/agent-authored-sym?`. Remove SCI envelopes,
   interrupt-specific nil behavior, and SCI-specific comments.
3. Delete `src/seon/render/sci.cljs`, remove its require from `seon.render`, and
   remove `org.babashka/sci` from `deps.edn` if the complete repository search
   still finds no other production consumer. Update stale analyzer/eval comments
   that name SCI while preserving the program facts they document for the
   official evaluator.
4. Replace cage-focused unit tests with ordinary walker tests for missing,
   throwing, and valid authored renderers plus the process integration hang
   test. Do not execute a compiled infinite loop directly in the shared test
   process.
5. Run the focused render/execution gates, the complete ClojureScript gate, and
   a live feed proof: kill/wedge one agent child, observe the feed error, then
   observe a clean successful replacement while the pod/writer and another
   agent remain responsive.

Crash-to-eval/turn/run terminalization is related runtime-hardening work, not a
prerequisite for deleting SCI from rendering. Existing parent failure evidence
is already sufficient to contain and display a render crash. Add the durable
CAS recovery integration before claiming full “resume the agent's turn”
semantics, but do not hold the redundant interpreter after process containment
is proven.

## Deletion ledger

After graduation, remove:

- `src/seon/render/sci.cljs` in full, including environment reconstruction,
  warning FIFO, warmup, interrupt classification, and canvas recovery logic;
- the `sci.core` and `sci.interrupt` render dependency imports;
- `SEON_CANVAS_SCI` and the renderer-specific SCI budget/configuration;
- the authored-symbol branch in `seon.render` that calls
  `render-sci/invoke-bounded`;
- SCI cage tests and old spike documentation whose contracts are replaced by
  process-boundary tests; and
- analyzer/index support used only to rebuild SCI aliases/host namespaces.

Do **not** remove namespace/function/schema program facts or
`eval/lookup-value`; the official self-host evaluator and execution-child
rehydration still own them. The likely comment-only cleanup is in
`seon.analyzer-info` and `seon.eval`, where load-order or environment-rebuild
explanations currently name `seon.render.sci`.

Keep SCI as a dependency only if another maintained production owner still uses
it; the evaluator audit already rejects it for agent eval. Do not preserve a
fallback direct authored call in the pod.

## Acceptance and performance measurements

Correctness and containment:

- authored direct loop, mutual recursion, lazy infinite sequence, native regex
  pathological case, and compiled-host loop each retire only the owning agent
  child within deadline plus kill grace;
- pod HTTP, another agent's eval/render, writer requests, and existing SSE feeds
  remain responsive throughout;
- late IPC/result/Promise completion from the dead generation cannot settle a
  newer invocation or write through an obsolete run fence;
- one crash yields one `:interrupted` eval/turn and, where applicable, one
  `:crashed` run transition;
- the exact source remains visible, completed database commits remain queryable,
  and no automatic retract/restore occurs;
- replacement reconstructs aliases, schemas, protocols, tests, metadata, and
  current definitions without historical eval replay;
- prior live `result/<id>` returns the honest lost-session result; and
- a throwing renderer becomes an in-place error without killing the child,
  while a timed-out/native-wedged renderer kills it.

The minimum deletion gate is narrower: direct valid/missing/throwing walker
behavior stays equivalent; the nested authored infinite-loop integration is
bounded by the host; the primary prompt, agent feed, and canvas paths contain no
pod-side authored function call; and repository search finds no SCI consumer.

Measure cold and warm child ready latency, first program-load latency, first and
steady render p50/p95/p99, deadline overshoot, TERM-to-KILL time, time to next
successful render, retained/private physical memory before and after repeated
replacement, peak private physical memory during replacement overlap, and idle
pool cost. Run at least 100 sequential crash/replacements and concurrent healthy
agents to detect process, pipe, IPC, database-session, feed, or compiler leaks.
Compare one shared per-agent child against a dedicated disposable render child
only after the shared design is correct; require a material measured benefit to
pay for the second topology.

## Exact source read-map

- `src/seon/render/sci.cljs` — current cage, interpreter deadline, known native
  residuals, source/alias reconstruction, and recovery behavior.
- `src/seon/render.cljs` — authored-symbol dispatch and the one guarded walker.
- `src/seon/execution.cljs` — protocol v3, program construction/loading,
  invocation deadline, poisoned child, cancellation, and compiled/authored
  selected calls.
- `src/seon/execution/host.cljs` — per-agent queue, generation fencing, bounded
  tails, parent deadline, process kill/removal, and lazy replacement.
- `src/seon/execution/runtime.cljs` — `render-prompt!`,
  `render-agent-view!`, `eval-batch!`, and exact compiled entrypoint map.
- `src/seon/eval/internal.cljs` — receipt states and CAS-fenced terminal
  transition.
- `src/seon/runtime/recovery.cljs` — existing cold recovery for running
  run/turn/eval facts.
- `test/seon/execution_test.cljs`, `test/seon/execution/host_test.cljs`, and
  `test/seon/execution/runtime_test.cljs` — poisoning, cancellation, exit,
  deadline, source loading, and renderer entrypoint proofs.
- `reference-code/bun` at `be77b652884b16a103cfaa4af3c1102f72f2dcd3`:
  `packages/bun-types/bun.d.ts` (`Subprocess`, workers, heap snapshots),
  `packages/bun-types/jsc.d.ts` (structured serialization and heap statistics),
  and `src/js/node/worker_threads.ts` (worker termination/exit lifecycle).
- `reference-code/sci` at `9e9c78f4f358ede939b94352ff4edc03b0186c7a`:
  `src/sci/interrupt.cljc` and evaluator internals.
- `reference-code/core.async` at `b871f3519de6843a9f5ce66cf8d5c6cbe44d3222`:
  CLJS dispatch/timer/channel implementations.
- `reference-code/superv.async` at
  `3e6ed755f83634c9e9bbb58707f9446420d32ce9`:
  `src/superv/async.cljc` and `README.md`.
- `reference-code/partial-cps` at
  `1e119b03ea908ad925b98f9ba0a26371c65441e3`:
  `README.md`, `runtime.cljc`, and `async.cljc`.

## Conclusion

SCI is no longer necessary for render containment because active production
authored rendering already runs through the isolated execution child. Its one
remaining call site is a nested walker dispatch inside that child. Process
replacement cannot
preserve runtime state, but Seon's architecture does not require it to: database
facts and current program source are the durable authority. The correct user
experience is an explicit interrupted/crashed receipt followed by a clean child
rehydrated from current database state—not an attempted heap resurrection and
not a historical rollback.
