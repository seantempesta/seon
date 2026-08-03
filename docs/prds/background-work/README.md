---
type: prd
status: active
tags: [prd, agent, flow, runtime, database]
---

# Agent-facing background work

## Decision

Background work is an execution mode of the one durable effect request, not a
second job abstraction. An explicit `(my.background/background (f request))`
commits the ordinary `seon.effect` receipt, submits its declared handler to an
`:io` arm of the cluster's existing work-launcher graph, and immediately
returns the receipt lookup ref. The same receipt identity covers foreground
and background execution; background mode adds only the durable obligation to
notify the initiating agent when that receipt settles. This composes with the
one effect owner and its settle-once sequence instead of creating a job
registry or another dispatch table
(`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:142-217`;
`AGENTS.md:632`).

Awaiting is an agent lifecycle choice, not a thread wait. If the receipt is
still pending, `(my.background/await result-ref note)` returns the existing
`:wait` disposition with the receipt ref added as ordinary data; the run
closes, and the agent starts a fresh run when settlement commits. If the
receipt is already terminal, `await` returns the same bounded descriptor as
`poll`. This matches the measured `my.run/wait` contract: a run does not
resume; the next trigger opens a fresh run whose context must carry the note
and new fact (`src/my/run.clj:40-67`; `src/seon/cluster/loop.clj:221-230`).

Continuing needs no operation. The agent keeps evaluating its current plan;
the terminal transaction commits a recipient ref, the existing Datahike
listener offers a payload-free wake, and the next agent pass derives the
unanswered result from facts. The wake is losable because it contains no
information; arming primes a fresh pass and re-derives all work
(`src/seon/cluster/wake.clj:163-227`; `src/seon/cluster/agent.clj:247-271`).

This PRD is bounded to capability effects already declared through
`:seon.effect/capability`. It does not put arbitrary SCI evaluation, pure
compute, subprocess lifecycle, model turns, or scheduled work behind
`background`. Capability identity and workload are separate program facts, so
`:io` alone is neither authority nor sufficient evidence for background
dispatch
(`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:144-201`).

## Authority and dependency ledger

The named authorities were read end to end before this decision:

- The agent-tools design defines one declared handler, one effect identity,
  open-receipt-before-dispatch, `:io` execution, settle-once, and no refire
  (`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:142-258`).
- The Flow control audit records core.async at
  `dc35f3e0d7bc2eef502e77982f48641f025c8051`, the one control protocol, async
  backpressured injection, and the fact that `flow/stop` is not a join
  (`docs/prds/sci-execution-runtime/research/flow-control-protocol-2026-07-31.md:15-24,47-60`).
- The Flow skill identifies the cluster work launcher as the existing
  out-of-graph work owner, the default `:io` executor as virtual-thread based,
  channels as losable transport, and database facts as recovery truth
  (`.agents/skills/seon-flow-architecture/SKILL.md:79-105,360-403`).
- The repository law rejects a central scheduler, permits in-flight channel
  transport only when loss is free, and defines recovery as interruption plus
  re-derivation rather than replay (`AGENTS.md:248-297`).
- The data-modeling rules require identity attributes, refs for relationships,
  and state derived from attribute presence rather than stored status labels
  (`.agents/skills/data-modeling/SKILL.md:35-45,63-81`).
- Datahike is pinned at
  `0e8601d7f2f68c01070e13a95483bc82be04cabc`; the known stale skill pin is
  already tracked at
  `docs/seon/issues/datahike-skill-pin-drifted-after-cache-cleanup.md:10-22`.
  Transitions that depend on the current database belong in one transaction
  function and return transaction data, not caller-side read-then-write logic
  (`.agents/skills/datahike/SKILL.md:404-451`).

The implementation dependencies, in order, are:

| Dependency | Current evidence | Required boundary |
|---|---|---|
| Declared effect owner | `src/seon/effect.clj` does not yet exist; the admitted sequence is specified at `docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:203-217` | Implement one receipt and handler path first. Background mode may add fields to that receipt, but may not precede or duplicate it. |
| Program facts | Capability declaration, handler resolution, and effect reachability are specified at `docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:142-201` | `background` structurally accepts one direct call whose owner row carries `:seon.effect/capability`; it never infers effectfulness from a name or `:io`. |
| Work launcher | The cluster already owns one bounded launcher graph; it currently admits only compute submissions and runs their closures through the root `:io` executor (`src/seon/flow.clj:330-447`) | Extend this graph with one IO-effect arm and an asynchronous submission interface. Do not create a background graph or scheduler. |
| Agent graph and wake router | Every agent graph already consists of mailbox and turn procs joined by a sliding-1 episode channel (`src/seon/cluster/agent.clj:247-271`); the listener routes recipient datom values without querying or parking (`src/seon/cluster/wake.clj:163-227`) | A terminal background receipt must commit a recipient ref whose datom value is the agent entity id. Add that declared attribute to the computed wake set and route it through the same mailbox path. |
| Run derivation | Runs currently answer message triggers through `:seon.cluster.run/trigger`; unansweredness is derived by absence of a reverse run ref (`src/seon/cluster/work.clj:612-639`; `resources/seon/schema.edn:2388-2407`) | Add a separate cardinality-many run-to-background-result ref. Do not change the meaning of the existing message trigger key. |
| Blob tier | Current `seon.blob` accepts a complete UTF-8 string and uses `readAllBytes` (`src/seon/blob.clj:15-69`) | Land binary staged writes and bounded chunk reads before a background capability may return large data, as already ordered by the tool design (`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:871-884`). |
| Context | Context must show what just happened, what is awaited, and what changed since the previous model-call basis (`docs/seon/architecture/context.md:56-107`) | Render terminal background results as attributed events in the first subsequent model prompt, with exact refs and bounded values. |
| Shutdown | Cluster stop currently calls `stop-work-launcher!` immediately before releasing the connection (`src/seon/cluster.clj:1783-1813`), while `stop-work-launcher!` merely calls non-joining `flow/stop` (`src/seon/flow.clj:449-455`; `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:174-183`) | The launcher must publish its own completion and cluster stop must await that event before releasing the branch connection. A clock may be only a loud backstop (`AGENTS.md:891-907`). |

The architecture text still describes four replay classes even though the
active crash law says nothing re-executes
(`docs/seon/architecture/toolkit.md:126-148`; `AGENTS.md:295-297`). The effect
owner must reconcile that authority before implementation. This PRD defines no
replay class, retry flag, or safe-to-refire exception.

## Shortest current falsifier

The checkout was probed on 2026-08-03 with the dependency's actual executor:

```clojure
(require '[clojure.core.async.impl.dispatch :as dispatch])
(let [p (promise)]
  (.execute (dispatch/executor-for :io)
            #(deliver p {:name (.getName (Thread/currentThread))
                         :virtual? (.isVirtual (Thread/currentThread))}))
  (deref p 2000 :timed-out))
;; => {:name "", :virtual? true}
```

The result agrees with core.async's implementation: on a supporting JDK the
default `:io` executor starts a virtual thread per task
(`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:76-105`).
It does not justify blocking the agent evaluation: current evals occupy the
bounded `:compute` launcher until `evaluate` returns
(`src/seon/cluster/loop.clj:574-591`). Therefore a synchronous deref inside
`my.background/await` is a design failure even though the handler itself parks
cheaply on a virtual thread.

## Agent-facing semantics

### Start

The initial syntax is one macro over one direct declared capability call:

```clojure
(def result-ref
  (my.background/background
   (my.web/get {:my.web/url "https://example.test/report"})))
;; => [:seon.effect/id "<run-id>/<form-ordinal>/<effect-ordinal>"]
```

The macro exists because evaluating an ordinary function argument first would
perform the foreground effect before `background` could select a mode. It
inspects the already-read list structurally, resolves the callee through the
shared SCI context, and delegates to the same public capability owner with a
background execution value. It performs no text scan, symbol construction, or
handler lookup of its own; the effect owner queries the function row
(`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:170-201`;
`AGENTS.md:882-889`).

The accepted body is exactly one direct call whose function row declares
`:seon.effect/capability`. Zero calls, multiple forms, a pure function, a
wrapper with unresolved effect reachability, and a raw handler symbol return a
flat error before any receipt is opened. Supporting arbitrary bodies would
create a second guarded SCI evaluation and a second run boundary, while the
declared leaf already supplies the exact external effect seam
(`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:170-201,249-258`).

For an accepted call, `seon.effect/request!` performs the established sequence:

1. derive `(run, form ordinal, effect ordinal)` and its deterministic
   `:seon.effect/id`;
2. validate the request and resolve the declared protected handler;
3. commit the open receipt, including the initiating agent as a pending
   notification obligation;
4. obtain non-blocking admission from the launcher's IO arm;
5. return the lookup ref once admission or refusal is recorded; and
6. let the IO handler settle the same receipt once
   (`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:203-217`).

If durable open-receipt commit fails, no handler runs and the call returns the
flat database error. If admission refuses, the effect owner settles that same
receipt with a flat capacity error and returns its ref; it does not delete the
receipt or invent a submission identity. This preserves the existing
non-blocking refusal pattern while keeping the effect identity authoritative
(`src/seon/flow.clj:183-242,457-514`).

Ordinary `(my.web/get request)` remains foreground and returns its ordinary
result. Background mode does not change the capability function's request or
result meaning; it changes when the caller receives that result. Changing all
declared IO calls automatically would violate the invariant that a key and its
relationship to output never change (`AGENTS.md:746-780`).

### Inspect and drill

`(my.background/poll result-ref)` is a bounded ambient-database read. It
returns one of three open shapes, distinguished by terminal fact presence:

```clojure
;; pending
{:seon.effect/id "..."
 :seon.effect/request-edn "..."}

;; successful or agent-facing failure (a flat error is still a result)
{:seon.effect/id "..."
 :seon.effect/result-edn "..."
 :seon.effect/result-size 219}

;; process interruption before settlement
{:seon.effect/id "..."
 :seon.effect/interrupted-at #inst "..."}
```

There is no stored `:status`, `:pending?`, or `:landed?`. Pending means the
absence of result and interruption attributes; success and interruption are
terminal attribute presence, following the existing eval-receipt construction
(`resources/seon/schema.edn:2365-2387,2426-2454`). A flat error is admitted as
the result rather than creating a fourth state (`resources/seon/schema.edn:997-1005`).

The returned lookup ref is an ordinary Datahike entity reference. Agents may
use `seon.db/pull` to select receipt facts and `get-in` on admitted result data;
the design does not create a parallel `job/status`, `job/output`, or value
navigator. A blob-backed result exposes digest and exact byte size on the
receipt; bounded binary reads use the one forthcoming blob chunk API. The
complete value remains reachable even when prompt presentation is windowed
(`docs/seon/architecture/context.md:159-175`;
`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:53-57`).

`poll` never acknowledges delivery. It is a read, and notification remains
guaranteed. If the agent polls a result just before its terminal wake is
processed, a later prompt may still report the landing; no mutable “seen” flag
or timing race decides whether durable work is visible
(`docs/seon/architecture/context.md:103-107`).

### Await or continue

```clojure
(my.background/await result-ref
  "Waiting for the report; compare its totals with :invoice/id 42.")
```

If terminal facts are already present, `await` returns the same bounded
descriptor as `poll`. If they are absent, it returns:

```clojure
{:my.run/disposition :wait
 :my.run/note "Waiting for the report; compare its totals with :invoice/id 42."
 :my.background/result result-ref}
```

`:my.background/result` is a declared ref-shaped key added to the open
`:my.run/wait` map. The existing disposition and note meanings do not change;
the added key preserves the exact durable identity in the transcript. Because
the run loop acts only on the last form's admitted disposition, `await` must be
the last form when the agent intends to suspend
(`src/seon/cluster/loop.clj:221-230`; `resources/seon/schema.edn:959-963`).

No thread waits on the receipt. The last-form value tells the run loop to close
the current run; terminal receipt delivery later opens a fresh run. The note
must therefore carry the semantic continuation, while the result ref provides
the exact data connection (`src/my/run.clj:40-67`).

If the agent chooses not to await, it does nothing special. It may keep working,
retain the ref in a durable plan or fact, and poll when useful. The background
receipt's terminal delivery remains independent of process-local bindings, so
losing a fresh SCI evaluation context cannot lose the result
(`docs/seon/architecture/context.md:56-64`; `AGENTS.md:295-297`).

## Facts and transitions

The effect PRD owns the final names, but it must admit one globally declared
entity shape with these relationships. The background slice adds no entity
family beside the effect receipt
(`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:203-217`).

| Proposed attribute | Shape | Meaning and invariant |
|---|---|---|
| `:seon.effect/id` | identity string | Deterministic from `(run, form ordinal, effect ordinal)` and returned as `[:seon.effect/id id]`; no second job or submission id (`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:203-217`). |
| `:seon.effect/run` | ref | The run that requested the effect; receipts are durable identified entities, not run components (`resources/seon/schema.edn:2388-2407`). |
| `:seon.effect/form` | ref | The exact ordered form, whose existing identity is already run plus ordinal (`resources/seon/schema.edn:2409-2424`). |
| `:seon.effect/ordinal` | non-negative int | Orders multiple effects in one form and completes the deterministic identity (`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:203-210`). |
| `:seon.effect/function` | ref to `:seon.fn` | The declared public capability owner; handler resolution derives from its program facts, never a family enum (`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:185-201`). |
| `:seon.effect/request-edn`, optional `/request-blob`, `/request-size` | bounded string or blob descriptor | Exact admitted request evidence; large bytes use the one blob owner (`src/seon/blob.clj:29-69`). |
| `:seon.effect/process` | process identity | Presence is live process custody. Settlement or recovery retracts it, as run custody already does (`resources/seon/schema.edn:2401-2406`). |
| `:seon.effect/notify` | ref to agent, pending background only | A durable obligation created before dispatch. Foreground receipts omit it; no execution mode enum is stored (`AGENTS.md:283-297`). |
| `:seon.effect/to` | ref to agent, terminal background only | The delivery relation and wake datom. Terminal transition retracts `/notify` and adds `/to` in the same transaction, so state is attribute presence and the listener can route from the datom value (`src/seon/cluster/wake.clj:163-225`). |
| `:seon.effect/result-edn`, optional `/result-blob`, `/result-size` | bounded admitted value descriptor | The one terminal success arm. A capability failure is its flat error result (`resources/seon/schema.edn:997-1005,2375-2380`). |
| `:seon.effect/interrupted-at` | instant | The other terminal arm. Boot adds it to a dangling receipt and never dispatches the handler again (`AGENTS.md:295-297`). |
| `:seon.cluster.run/background-results` | cardinality-many ref | Every run atomically attaches all currently unanswered terminal results for its agent. Answeredness is the reverse ref's presence; the set is rendered in settlement order, never Datahike set order (`src/seon/cluster/work.clj:612-639`; `.agents/skills/data-modeling/SKILL.md:73-81`). |

The state machine is entirely facts:

| State | Required presence | Required absence | Transition owner |
|---|---|---|---|
| Foreground pending | `/id`, `/run`, `/form`, `/function`, `/process` | `/notify`, `/to`, terminal attrs | `seon.effect/request!` open transaction (`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:203-213`). |
| Background pending | foreground pending facts plus `/notify` | `/to`, terminal attrs | Same open transaction, before Flow injection (`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:203-213`). |
| Foreground terminal | one terminal arm | `/process`, `/notify`, `/to` | Effect settle-once transaction (`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:211-217`). |
| Background terminal | one terminal arm plus `/to` | `/process`, `/notify` | Same settle-once transaction; `/to` is the wake-producing datom (`src/seon/cluster/wake.clj:213-225`). |
| Background answered | terminal facts plus reverse `:seon.cluster.run/_background-results` | none beyond terminal invariants | Atomic run-open transaction (`src/seon/cluster/work.clj:612-639`). |

Opening a receipt, settling it, recovering it, and opening the run that answers
it are database-dependent decisions. Each must be a transaction function over
the writer's current database, with settle-once expressed by terminal-attribute
absence and run opening attaching every currently unanswered result. A caller
must not pre-read and then transact an assumed state
(`.agents/skills/datahike/SKILL.md:404-451`).

Multiple background results may land before the agent is free. One run attaches
all of them through the cardinality-many ref and renders them in transaction
order, avoiding one model turn per result. A result landing after that run-open
transaction remains unanswered and its datom produces another wake. Channel
coalescing cannot erase either case because the query, not the number of wakes,
determines the work (`src/seon/cluster/agent.clj:247-271`;
`src/seon/cluster/wake.clj:188-196`).

## Flow wiring

The existing per-cluster work-launcher graph owns pending IO effects. It gains
an `::io-submission` input and completion input beside its current compute
submission arm. The launcher proc remains `:io`; it admits quickly, starts each
accepted handler on the root `:io` executor, and consumes completion messages
to call the effect settlement owner. A blocking handler never runs inline in
the proc transform, because that would serialize the whole cluster's effects
(`src/seon/flow.clj:330-374`).

The IO input reuses the launcher's one non-blocking admission mechanism. A
fixed refusing buffer records an immediate flat capacity result rather than
parking the agent or accumulating an unbounded queue
(`src/seon/flow.clj:183-245,395-416`). IO concurrency and queue depth must be
declared config facts with measured defaults; capability-specific remote limits
remain capability policy and do not create another executor or scheduler
(`AGENTS.md:891-907`).

The in-flight submission carries a handler closure and receipt ref only as
process-local channel data. Loss is free because the open database receipt is
the recovery authority; a missing completion leaves a receipt that recovery
marks interrupted. The channel never carries the only copy of request,
identity, recipient, or result metadata (`AGENTS.md:283-297`).

`flow/inject` is asynchronous and backpressured, returning a future that
completes only after the put. The background call must not deref that future.
The refusing buffer makes injection itself non-parking, while the durable
capacity-refusal result covers rejected admission
(`docs/prds/sci-execution-runtime/research/flow-control-protocol-2026-07-31.md:47-60`;
`src/seon/flow.clj:208-242`).

There is no third proc in each agent graph. Agent graphs continue to derive
work only when their mailbox wakes, and parked agents consume the same two-proc
baseline whether they have zero or many pending background receipts
(`src/seon/cluster/agent.clj:247-271`).

### Stop and recovery

The launcher's `::flow/stop` transition first closes admission, requests
cancellation from active handler futures where the capability supports it,
marks their process-local completion path closed, and publishes an explicit
completion promise-channel after every launcher-owned task has left. Cluster
stop waits for that event before releasing the database connection. This is a
Seon completion layered on Flow because Flow stop itself returns before procs
exit
(`.agents/skills/seon-flow-architecture/SKILL.md:237-248`;
`src/seon/cluster.clj:1803-1813`).

If an external operation's state is genuinely unobservable, its capability's
declared time limit remains the backstop and firing is durable failure evidence;
elapsed time is never used to infer local completion. Subprocess termination is
only a shell handler's cleanup responsibility, not the background scheduling
mechanism (`AGENTS.md:891-907`;
`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:811-833`).

On boot, before agent graphs are armed, recovery queries open effect receipts
whose `/process` identity is dead. One transaction retracts `/process`, adds
`/interrupted-at`, and, for background receipts, replaces `/notify` with `/to`.
It never re-injects a handler. Arming's prime pass then derives the unanswered
terminal result even if the listener did not observe the recovery transaction
(`AGENTS.md:295-297`; `src/seon/cluster/agent.clj:247-271`).

## Notification and right context

Adding `:seon.effect/to` to `wake-attributes` and the existing `route!` case is
the only wake wiring. The listener reads the recipient entity id directly from
the committed datom, uses `offer!`, performs no query or commit, and retains its
unconditional render wake. This is the exact message-delivery mechanism applied
to a second declared delivery relation, not a second listener
(`src/seon/cluster/wake.clj:78-93,163-227`).

The turn proc's priority remains: finish a held run before opening new work.
Thus a result landing during an active plan does not interrupt an eval or inject
text into an already-rendered model call; it waits as an unanswered fact. At
the first subsequent run opening, the transaction attaches all unanswered
background results, and prompt derivation renders them under “what just
happened” and the delta from the previous context-capture basis
(`src/seon/cluster/work.clj:534-576`;
`docs/seon/architecture/context.md:77-107`).

Each rendered event names the capability function, result ref, originating
form/run, settled or interrupted fact, bounded preview, exact size, and blob
digest when present. It is visually and semantically distinct from user or
peer messages, preserving event attribution in the transcript
(`docs/seon/architecture/context.md:117-157`). The await note renders under
“what I am waiting on” until the terminal fact exists, then disappears by
derivation; no notification row, acknowledgement flag, or stored rendered text
exists (`docs/seon/architecture/context.md:77-107`).

An already-open run may attach a message trigger and every pending background
result in the same transaction. A run opened only for results has no
`:seon.cluster.run/trigger`; it has one or more
`:seon.cluster.run/background-results`. This adds a new relationship rather
than changing the message-only meaning of the existing key
(`resources/seon/schema.edn:2388-2407`; `AGENTS.md:746-780`).

## Options considered

### Option 1 — explicit mode on the one effect receipt (recommended)

Guarantee: the agent chooses foreground by calling the capability normally or
background by wrapping one declared call; either path has one effect identity,
one durable receipt, one protected handler, and one result. Await suspends the
agent run without parking compute, while continuing guarantees a later
fact-derived wake (`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:203-217`;
`src/my/run.clj:40-67`).

Cost and trade-off: implement the effect owner first, add an IO arm plus joining
shutdown to the launcher, extend run triggering, and render attributed result
events. Agents must write the explicit wrapper when they want concurrency, but
ordinary calls retain ordinary Clojure composition and return semantics
(`src/seon/flow.clj:395-455`; `src/seon/cluster/work.clj:534-576`).

Capability deliberately given up: the first slice cannot background arbitrary
pure/mixed forms or a wrapper that hides several effects. Such a facility would
need a separately designed durable evaluation contract, not an accidental
extension of effect dispatch (`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:185-201`).

### Option 2 — automatically background every declared IO call

Guarantee: capability calls would return result refs immediately without an
explicit form. Implementation is superficially smaller because the mode is
implicit in workload (`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:192-201`).

Cost and trade-off: rejected. Workload says where blocking work runs, not
whether a call is an external effect, and it does not authorize changing a
function's output from its declared result to a ref. Existing expressions could
no longer compose normally, and pure blocking IO would be misclassified as a
capability (`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:198-201,242-245`).

Capability given up by rejection: agents must mark intended concurrency
explicitly. This is preferable to making every ordinary capability call
implicitly asynchronous and semantically breaking existing callers
(`AGENTS.md:746-780`).

### Option 3 — arbitrary background SCI tasks or one graph per job

Guarantee: `(background ...)` could accept any body, and each job could expose
independent process-local lifecycle controls. The deleted shell quarry used the
corresponding mutable job registry, virtual thread, polling, and stop surface
(`src-old/seon/agent/shell/leaf.clj:169-307`).

Cost and trade-off: rejected. Arbitrary bodies require another guarded eval,
namespace/session semantics, compute placement, receipt model, and recovery
boundary. Per-job graphs or a scheduler duplicate Flow and the agent/run loop;
process-local job state violates recovery. The tool design already rejects the
old background job API (`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:858-869`;
`AGENTS.md:248-297`).

Capability given up by rejection: this slice cannot detach arbitrary compute
or manage a generic task tree. Genuine subprocesses remain one shell
capability handler, and background scheduling never calls
`babashka.process` (`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:798-833`).

## Implementation order

1. Reconcile the no-replay architecture text, land declared capability facts,
   and implement the one `seon.effect` receipt/settlement owner
   (`docs/seon/architecture/toolkit.md:126-148`;
   `docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:871-884`).
2. Extend `seon.blob` with staged binary writes and bounded chunk reads; prove
   exact bytes across the inline threshold (`src/seon/blob.clj:15-69`;
   `docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:839-854`).
3. Generalize the existing work launcher admission path, add the IO arm and
   asynchronous `submit!`, and add a real stop completion before branch release
   (`src/seon/flow.clj:330-514`; `src/seon/cluster.clj:1803-1813`).
4. Add the effect receipt schema, settle/recovery transaction functions, and
   deterministic identity property tests; add no status attribute
   (`resources/seon/schema.edn:2426-2454`;
   `.agents/skills/datahike/SKILL.md:404-451`).
5. Add `my.background/background`, `poll`, and `await`, limited to one direct
   declared capability call; preserve the existing wait disposition
   (`src/my/run.clj:40-67`; `resources/seon/schema.edn:959-963`).
6. Add terminal delivery routing, atomic unanswered-result attachment at run
   open, and prompt/transcript projections. Preserve the two-proc agent graph
   (`src/seon/cluster/wake.clj:163-227`;
   `src/seon/cluster/agent.clj:247-271`).
7. Prove the full boundary in an isolated operator root, including live process
   interruption on both sides of dispatch and settlement. Nothing refires
   (`AGENTS.md:295-297,1160-1179`).

## Falsifiers and graduation gate

The slice graduates only when all of these falsifiers pass against a freshly
forked cluster from the published source commit:

- Start a blocking test capability in background. The form returns a stable
  effect lookup ref before the handler finishes, the handler runs on a virtual
  `:io` thread, and Flow observation shows no bounded compute permit occupied
  while it waits (`src/seon/cluster/loop.clj:574-591`;
  `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:82-105`).
- Invoke the same form identity twice through recovery and duplicate delivery.
  Exactly one receipt exists and settlement is once; no process restart or
  wake re-runs the handler (`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:203-217`;
  `AGENTS.md:295-297`).
- Fill IO admission. Refusal returns immediately as the flat result of the same
  receipt, with no parked injection future, leaked task, or second submission
  identity (`src/seon/flow.clj:183-242,457-514`).
- Await a pending result. The run closes with the note and result ref, consumes
  no thread, and the terminal transaction opens a fresh run whose first prompt
  contains the attributable result (`src/my/run.clj:40-67`;
  `docs/seon/architecture/context.md:77-107`).
- Continue while one result lands during a held run. The current eval is not
  interrupted; the next model call sees the result exactly once as a triggered
  event (`src/seon/cluster/work.clj:534-576`;
  `docs/seon/architecture/context.md:135-157`).
- Land several results before one pass, including two in the same transaction.
  A sliding-1 mailbox may coalesce every wake, yet one run attaches and renders
  every unanswered result in settlement order (`src/seon/cluster/agent.clj:247-271`;
  `src/seon/cluster/wake.clj:188-196`).
- Terminate the JVM immediately before dispatch, after external start, before
  result commit, and after result commit. Open receipts become interrupted,
  background interruptions wake the agent, terminal receipts remain terminal,
  and no handler refires (`AGENTS.md:295-297`;
  `docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:823-828`).
- Stop a cluster with active background handlers. Admission closes, launcher
  completion is observed before connection release, and no task attempts a
  late settlement against the released branch (`src/seon/cluster.clj:1803-1813`;
  `docs/prds/sci-execution-runtime/research/flow-control-protocol-2026-07-31.md:47-60`).
- Return invalid UTF-8 and bytes on both sides of the inline threshold. Poll
  stays bounded, chunk reads reconstruct exact bytes and digest, and no
  `readAllBytes` path is proportional to the large result
  (`src/seon/blob.clj:22-27`;
  `docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:839-854`).
- Poll before, during, and after delivery. Poll never suppresses notification,
  writes acknowledgement state, or changes the terminal facts
  (`docs/seon/architecture/context.md:103-107`).

The integrated graduation gate is: one isolated-cluster agent starts two slow
capabilities, continues useful work, logically awaits one, receives both
settlements in the first eligible context, drills one blob in bounded chunks,
survives a JVM interruption with the other receipt marked interrupted, and
shows through database queries that every outcome is one receipt, every wake is
derived from its terminal recipient fact, no handler re-executed, and no
compute permit or OS process acted as the scheduler (`AGENTS.md:248-297`).

## What not to build

- No job entity, mutable job atom, process-local result registry, list/status/
  output polling service, or second result identity
  (`src-old/seon/agent/shell/leaf.clj:169-307`;
  `docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:858-869`).
- No central scheduler, dispatcher loop, per-job graph, third per-agent proc,
  executor registry, or OS process used to schedule Clojure work
  (`AGENTS.md:248-297`; `src/seon/cluster/agent.clj:247-271`).
- No automatic backgrounding based on `:io`, namespace, function name, source
  text, or a hand-maintained capability list
  (`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:170-201,249-258`).
- No synchronous deref, promise wait, sleep, or polling loop inside SCI
  `await`; it returns a lifecycle value and releases the agent turn
  (`src/seon/cluster/loop.clj:574-591`; `src/my/run.clj:40-67`).
- No retry, replay class, lease, attempt counter, or refire after interruption;
  a new request from a new form is a new effect identity (`AGENTS.md:295-297`;
  `docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:215-217`).
- No notification queue, unread/seen/acknowledged fact, stored prompt fragment,
  or side-channel delivery. The terminal recipient fact is the wake and the
  reverse run ref is answeredness (`src/seon/cluster/work.clj:612-639`;
  `docs/seon/architecture/context.md:103-107`).
- No second blob or artifact owner, whole-result heap capture, unbounded inline
  value, or full large-result read for display (`src/seon/blob.clj:22-27`;
  `docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:839-869`).
- No `babashka.process` call except inside a genuine subprocess capability
  handler, where argv, descendant cleanup, and external process evidence belong
  (`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:798-833`).

## Exit and downstream boundary

The earliest unsettled contract is the one `seon.effect` receipt schema and
settle-once transaction. Its integrated proof is open-before-dispatch plus
interruption-without-refire. In parallel after that schema settles, the blob
chunk API and launcher IO arm can advance in separate owners; wake/run/context
wiring depends on the receipt's final recipient and terminal attributes
(`docs/prds/sci-execution-runtime/research/agent-tools-design-2026-08-03.md:203-217,871-884`).

This PRD ends when the integrated graduation gate passes and the active
architecture describes the no-replay effect boundary. Scheduling, arbitrary
background evaluation, recurring work, subprocess tool design, and provider-
specific concurrency remain separate PRDs; none is a reason to widen this
slice (`docs/seon/architecture/toolkit.md:126-148`; `AGENTS.md:248-297`).
