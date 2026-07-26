---
type: architecture
status: active
tags: [architecture, agent, runtime, database]
---

# Agent runtime — claim-native runs, turns, and recovery

> **Target design** (present tense). Implementation state, gaps, order, and
> evidence live only in [[roadmap]].

An agent is durable database state plus replaceable compute. A trigger opens a
bounded **run**. The cluster JVM acquires that run through database CAS,
advances one persisted **turn phase** at a time, and releases custody when work
must wait or terminate. No process-local loop, promise registry, or attempt
buffer is an authority. Killing the cluster JVM loses only transient compute;
its replacement resumes from the database facts.

The runtime has one portable `.cljc` driver. Every execution tier calls that
same driver and supplies a small platform leaf for the phases it can execute.
The JVM runs one virtual thread per held claim. A virtual thread may park on
database, model, or bounded eval work without consuming a platform thread for
the life of the run.

## State is derived

The agent entity stores primitives, not a lifecycle label. `seon.derive`
projects the visible state from facts:

- `:terminated` when `:seon.agent/terminated-at` is present;
- `:paused` when the current open run has `:seon.agent.run/paused-at`;
- `:running` when the agent points to an open run; and
- `:idle` otherwise.

Run expiry is also derived. A claim is expired when the run remains open and
unpaused, has a `:seon.agent.run/process` and epoch, and its last heartbeat is
older than the configured stale interval. There is no stored `expired?`,
recovery status, or process-alive bit. Wall-clock comparison is performed
against one immutable observation of the run and the configured lease policy.

## Runs are claimable database state

A run is the bounded unit opened by a message, due schedule, or authored
interaction. It records its identity, owning agent, trigger and cause, work
limit, absolute deadline, heartbeat, status, and terminal reason. The agent's
`:seon.agent/run` ref points to the current open run and is part of every work
fence.

An authored interaction is also a run entity. Its submission transaction
records a generated interaction identity, the pinned handler symbol and source
fingerprint, schema-projected ordered arguments, subject refs, and
`:pending` status. Request provenance remains the transaction's
`:seon.db/user` and `:seon.db/process`; it is not copied onto the interaction.
Submission leaves the agent's current-run ref absent so multiple interactions
can queue as database facts. First claim atomically CASes that absent pointer
to the interaction run while acquiring claim epoch `1`. The same pointer
and epoch fence then governs every receipt and terminal transition.

Custody lives on that run:

- `:seon.agent.run/process` identifies the process record for the cluster JVM
  instance holding the run, grounded by its generation and `(pid,
  start-instant)` identity;
- `:seon.agent.run/claim-epoch` is a monotonic fencing number; and
- `:seon.agent.run/last-beat-at` is the last committed lease heartbeat.

The first acquisition CASes an absent process ref and epoch to the current
cluster JVM's process record and epoch `1`. Reacquisition of a cleanly released
run CASes the absent process ref to the current process record and increments
the observed epoch. Takeover of an expired claim CASes the observed heartbeat
and epoch before replacing the process ref and heartbeat. A live foreign claim
is not stealable.

Every run mutation leads with two assertions:

1. the agent still points to the observed run; and
2. the run still has the cluster JVM's held epoch.

The process record identifies custody for operators and archaeology; the epoch
is the authority fence. A displaced cluster JVM cannot publish late work
because its transaction fails at the transaction owner.

Heartbeat, input consumption, release, pause, close, and phase advancement all
carry the same pointer-plus-epoch fence. A clean release retracts only the
process ref and retains the monotonic epoch. Closing the run records the
terminal facts, retracts the process ref, and retracts the agent's current-run
ref in one transaction.

## The portable driver

`seon.agent.driver` owns the control algorithm:

1. acquire one immutable database value;
2. find an open, unpaused run whose next phase the leaf can execute;
3. derive the exact acquire, reacquire, renew, or takeover transaction;
4. commit it through `seon.db`;
5. execute only the phase authorized by the durable cursor;
6. commit the phase result under the run and phase fences; and
7. continue, close, or release for a clean tier handoff.

The cluster JVM advertises capabilities such as interaction, render, model
I/O, eval, and publish. Eligibility is a pure function of that set and the
persisted interaction status or turn phase. It does not route by agent
identity or keep a private queue of runs. Database interests are ephemeral
wakeups that request another scan; the scan and CAS determine authority.

Phase eligibility says who may claim; the derived execution plan says whether
the cluster JVM can run this particular work. After parsing a proposed
invocation and before entering the eval phase, the driver derives the plan at
the claim database value, verifies schema and capability coverage against its
own inventory, and provisions any permitted remote leaves. If no tier can
satisfy the execution plan, the result is one flat error value naming the
missing leaves, schemas, or unresolved edges. See [[architecture]]
§Transparent distribution.

Every active tier uses this driver from the same source. Platform leaves own
only native effects: database sessions, clocks, virtual-thread dispatch,
provider transport, SCI invocation, and publication I/O. Sync versus async
ceremony is confined to the entry expression. No tier owns a forked state
machine or translates through hand-mirrored host wrappers.

On the cluster JVM, a scan starts at most one named virtual thread per run.
That thread drives the held claim until it closes, loses the fence, or reaches
a phase the leaf cannot execute. Blocking work uses `:io`; SCI eval work uses
`:compute` platform threads under the one `:interrupt-fn`; the claim virtual
thread parks for the result.

For an interaction, the cluster JVM first CASes `:pending → :running` under the
held run fence. Only that committed receipt admits the pinned handler through
SCI with the one `:interrupt-fn` on `:compute`. Success or a flat error is
schema-projected into ordinary data and committed with
`:running → :done|:error` in the same fenced transaction that closes the run.
A replacement cluster JVM that observes `:running` records `:interrupted` and
does not replay the authored handler.

## Turns have a durable phase cursor

A turn is the durable record of one prompt/model/eval/publish cycle. Its
`:seon.agent.turn/phase` is the recovery cursor:

```text
:rendered
  → :attempt-open
  → :reply-ready
  → :evaling
  → :evaled
  → :published
```

Before a turn exists, the driver treats its phase as `:unstarted`. Rendering
creates the turn and advances it to `:rendered`. Every later transition uses
an in-transaction CAS from the observed phase to the next phase, composed with
the held run fence. The cluster JVM may repeat a read, but it cannot repeat a
committed phase transition.

The turn pins the database value used to render the prompt through its rendered
transaction and prompt blob. It links the raw reply blob, eval receipts,
provider attempt receipts, usage projections, assigned cause message, and
terminal status. Large bytes live in the one blob archive; the turn stores
small projections and refs.

The cursor divides recovery by effect boundary:

- before `:attempt-open`, no provider request has been admitted;
- at `:attempt-open`, the attempt receipt says whether external work is
  unresolved;
- at `:reply-ready`, the exact reply bytes exist;
- at `:evaling`, eval receipts distinguish unadmitted forms from running or
  terminal forms;
- at `:evaled`, all eval evidence is durable; and
- at `:published`, the turn's externally visible publication is committed.

## Attempt and eval receipts

Every provider dispatch first attaches a component attempt row with a unique
`:seon.ai.attempt/id`, ordinal, frozen non-secret request projection, deadline,
and `:seon.ai.attempt/outcome :open`. The same transaction advances the turn
from `:rendered` to `:attempt-open`.

Settlement CASes the attempt outcome from `:open` to one terminal outcome and
records bounded response identity, usage, and error evidence. A successful
response links the content-addressed reply blob and advances the turn to
`:reply-ready` atomically. A retry appends another `:open` row while retaining
the `:attempt-open` cursor. Takeover marks an abandoned open attempt
`:crashed` before retry policy decides whether new external work is allowed.
There is no in-memory attempt ledger to reconcile.

Each eval form similarly receives a durable `:running` receipt before its SCI
dispatch and a terminal update afterward. The receipt records source,
namespace, result or error projection, bounded output, and progress evidence.
If the cluster JVM dies at `:evaling` before any receipt exists, no form was
admitted and the batch may begin. If receipts exist, takeover terminalizes
still-running rows as interrupted and advances from their durable evidence.
Recovery never fabricates success and never replays an already terminal form.

Receipt transitions compose with the held run epoch and the turn phase CAS.
They are therefore both execution evidence and the write fence against stale
processes.

## SCI interruption

Every SCI invocation—agent eval, authored render, plan function, or schema
predicate—installs the one zero-argument `:interrupt-fn`. SCI calls it at every
`fn` body entrance. The invocation's `time-limit` is the only execution limit;
when it expires, `interrupt!` stops the eval uncatchably.

Malli never constructs or forks a private SCI context. The schema projection
resolves every admitted `[:fn]` symbol to its already-materialized corpus
callable before Malli compiles the schema. Predicate invocation therefore runs
in the surrounding retained `ctx`. If Malli catches SCI's interruption marker
or replaces it with a schema error, the runtime retains the fired `time-limit`
result and returns the canonical flat error value.

The runtime records `:seon.eval/fn-entries`, the number of `fn` body entrances,
as a diagnostic only. It is never a budget or control input: a large count in a
short interval identifies a spin, while a small count during a long interval
identifies work blocked in a host call. Bounded output projection is a data
boundary, not a second execution limit.

`time-limit` is a required database configuration fact selected by invocation
class. It is not a scheduling quantum. Expiry calls `interrupt!`, records an
agent fault, and returns one flat error value:

```clojure
{:seon.error/message "..."
 :seon.error/kind :timeout
 :seon.error/data
 {:seon.eval/invocation-class :agent-eval
  :seon.eval/fn-entries 123}}
```

The `:interrupt-fn` is cleared in `finally`, so a retained `ctx` cannot leak
one invocation's interruption state into the next.

The `time-limit` follows [[laws]]: a configuration fact with a schema,
docstring, unit, and calibration provenance; a default far above measured
legitimate work; loud firing; no runtime numeric fallback.

## Lease-aware recovery

Recovery is ordinary claim acquisition against current facts, not a cold-boot
cluster repair sweep.

- A live claim remains untouched.
- A cleanly released open run is reacquired at the next epoch.
- An expired foreign claim is taken over only through the heartbeat-and-epoch
  CAS.
- The phase cursor and receipts determine the next safe step.
- A stale holder's later mutation fails the same run fence used during normal
  execution.

Startup reconstructs program declarations and registers database interests,
then the normal run scan offers open work. It does not close every
apparently running turn, clear process registries, or infer failure merely
because a different process now hosts the cluster.

Pause is a fenced release: it banks remaining wall-clock time, records
`:paused-at`, and retracts `:seon.agent.run/process`. Resume clears the pause
facts, extends the deadline from the banked duration, and leaves the run
available for ordinary reacquisition at a new epoch. Termination and explicit
close use the same fence and terminal transaction as normal completion.

## Bounds and truthful stopping

A run has separate work and wall-clock bounds. Reply evaluation in `:batch`
mode counts completed turns; `:first-form` counts attempted forms independently
of whether the provider transport streams bytes. The absolute deadline bounds
the whole run, while `time-limit` bounds each SCI invocation. Provider attempts
have their own frozen transport deadline and durable receipt.

No-progress is derived from trailing committed eval observations. Repeated
equivalent no-progress turns close cleanly rather than spinning. A bound,
deadline, interrupted eval, exhausted provider policy, or failed fence cannot
masquerade as successful completion. The terminal reason and receipts preserve
which boundary fired.

## Triggering, schedules, and orchestration

Creation transacts one complete idle agent: identity, optional run defaults,
home namespace and requirements, context components, purpose, and parent ref.
Creation does not start hidden execution. A message or due schedule opens a run
through the single writer; concurrent open attempts race on the same agent-run
pointer and only one wins.

One scheduler derives due work from schedule facts and opens runs. It does not
own agent execution or heartbeat state. Messages arriving during an open run
become explicit consumed-input edges and may extend the run's work window
without opening a second run.

The root agent is both the `/` system-view owner and the lifecycle
orchestrator. Its protected functions create, pause, resume, terminate, and
message agents by transacting the same facts. Roles are capability sets, not
stored kinds. `:seon.agent/parent` forms the orchestration tree; depth and
concurrency limits are enforced at the lifecycle transaction boundary.

## Program reconstruction

Authored functions, schemas, tests, namespace declarations, and require edges
are database program facts. The cluster JVM reconstructs the admitted program
from those facts, topologically loads declarations, and installs
instrumentation from the same graph. A source change updates the shared
program; it does not create a per-agent copy.

Process replacement reloads declarations and current retained context. It does
not replay scratch evals, provider calls, filesystem effects, Promises, sockets,
or handles. Receipts and effect classes decide recovery; declaration loading is
the only program reconstruction.

## Isolation and process ownership

The cluster JVM executes agent code, owns transactions, and serves the
committed-transaction feed for its store. The web-render JVM evaluates only
trusted pure projections over acquired database values. Disposable leaf
runtimes run packages and selected platform workers, not the agent driver. See
[[architecture]] for the complete topology.

One cluster has one cluster JVM because Datahike's `:self` writer permits one
writer process per store. Scale by adding isolated clusters, never by adding
processes to one store. `:seon.agent.run/process` records transient run custody
without becoming part of the durable agent identity.

## What the database gives us

- single-writer CAS establishes one winner for a claim or phase;
- immutable database values make each decision reproducible;
- temporal history preserves claim, heartbeat, cursor, and receipt
  archaeology;
- refs connect agents, runs, turns, attempts, evals, messages, and blobs;
- replacement compute resumes from facts rather than process memory; and
- bitemporality enables historical prompt and policy reconstruction.

Seon adds no external lease service, heartbeat service, workflow queue,
promise registry, or recovery log. The run facts are the lease and fence; the
turn phase and receipts are the workflow and recovery record.

## See also

- [[architecture]] — process topology and the portable capability seam.
- [[data-model]] — run, turn, attempt, eval, and error attributes.
- [[observability]] — receipt forensics and temporal claim archaeology.
- [[ui]] — pure database-value renders and the independent web-render process.
- [[toolkit]] — effect classes and `:seon.capability/op-id`.
- [[laws]] — circuit-breaker and one-mechanism laws.
- [[roadmap]] — implementation state and evidence.
