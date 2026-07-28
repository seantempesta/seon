---
type: architecture
status: active
tags: [architecture, agent, runtime, database]
---

# Agent runtime — claim-native runs, turns, and recovery

> **Target design** (present tense). Implementation state, gaps, order, and
> evidence live only in [[roadmap]].

An agent is durable database state plus replaceable compute. A trigger opens a
bounded **run**. The cluster JVM acquires that run through Datahike's
`:db.fn/cas`, which is reserved for facts two processes race to win exactly
once: plan freeze from absent to digest, and run claim from no process to the
process record together with a claim-epoch increment. Opening and claiming
happen before prompt derivation or a paid model call, so the agent-busy fence
exists before money leaves the database boundary. No process-local loop,
promise registry, or attempt buffer is an authority.

The runtime has one run-loop proc. Platform leaves supply only native transport
work. The proc derives work from database facts on each wake; it never treats
the wake payload as work or keeps a private queue.

## State is derived

The agent entity stores primitives, not a lifecycle label. An agent pointing to
an open run is running; an agent with no open run is idle. Interruption,
answeredness, and current work are queries over runs, receipts, messages, and
transaction metadata. There is no stored `running?`, `answered?`, recovery
status, or process-alive bit.

Pause, resume, terminate, and start are not disposition values. They require a
separate agent-lifecycle entity and do not exist until that domain exists.

## Runs are claimable database state

A run is the bounded unit opened by a message, due schedule, or authored
interaction. It records its identity, owning agent, claim, frozen plan,
receipts, and terminal reason. The agent's current-run ref points to the open
run and is part of every work fence.

The run's **why** is transaction metadata, not an attribute copied onto the
run. The run-opening transaction carries `:seon.db/trigger`, a ref to the
message that caused it. “Has this trigger been answered?” is therefore the
query “does a run-opening transaction point at it?” alongside the usual
`:seon.db/user` and `:seon.db/process` provenance.

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
- `:seon.agent.run/claim-epoch` is a monotonic fencing number.

The first acquisition CASes an absent process ref and epoch to the current
cluster JVM's process record and epoch `1`. Reacquisition of a cleanly released
run CASes the absent process ref to the current process record and increments
the observed epoch. Takeover CASes the observed process and epoch before
replacement. A live foreign claim is not stealable.

Every run mutation leads with two assertions:

1. the agent still points to the observed run; and
2. the run still has the cluster JVM's held epoch.

The process record identifies custody for operators and archaeology; the epoch
is the authority fence. A displaced cluster JVM cannot publish late work
because its transaction fails at the transaction owner.

Receipt settlement, release, interruption, and close all carry the same
pointer-plus-epoch fence. A clean release retracts only the process ref and
retains the monotonic epoch. Closing the run records the terminal facts,
retracts the process ref, and retracts the agent's current-run ref in one
transaction.

### Why fencing remains

The database serializes every durable transition. Seon does not add a general
lock service, distributed queue, or second coordinator. The exact coordination
residue is only what database serialization cannot make disappear:

- **money in flight** — a model request occurs outside the database, so the run
  is opened and claimed before the call. A second trigger observes the busy
  agent instead of spending a second call;
- **crash custody** — the process ref records which process owns work that may
  die between transactions, so boot can derive dead custody from facts plus the
  live-process set; and
- **the epoch** — after replacement, an old activity can still finish late.
  Its observed epoch must fail before it can publish into the replacement's
  run.

Without the first fence, serial commits can still follow two concurrent paid
calls. Without the process ref, crash recovery has no fact to bury. Without the
epoch, a stale pre-crash result can commit after replacement. Everything else
is already serialized by the database.

## The run loop

`seon.cluster.loop` owns the control algorithm:

1. receive a coalesced wake that means only “look”;
2. acquire one immutable database value;
3. derive the next open, call, resume, close, or interruption value from facts;
4. open and claim before any paid model work;
5. execute only the work described by that value;
6. commit receipts and dispositions under the run pointer and epoch fence; and
7. close, release for a later wake, or settle interrupted wreckage.

The loop does not route by agent identity or keep a private queue of runs.
Database interests are ephemeral wakeups that request another derivation; the
query and transaction transition determine authority.

Phase eligibility says who may claim; the derived execution plan says whether
the cluster JVM can run this particular work. After parsing a proposed
invocation and before entering the eval phase, the run loop derives the plan at
the claim database value, verifies schema and capability coverage against its
own inventory, and provisions any permitted remote leaves. If no tier can
satisfy the execution plan, the result is one flat error value naming the
missing leaves, schemas, or unresolved edges. See [[architecture]]
§Transparent distribution.

The cluster JVM uses `core.async.flow` as its one scheduling substrate. The run
loop is a proc behind a `flow.spi/ProcLauncher` so it can select over Flow
control and a bounded database-interest wake channel. Its `step-fn`, `conns`,
and bounded workload-class channels live in the `graph-def`; current status
and metrics reach `flow-monitor` through the ordinary report channel and ping
surface. Database facts, never Flow channels, remain the durable work record.

Each workload class uses core.async's `executor-for :io` or
`executor-for :compute`. The eval seam runs on a `:compute` platform thread,
arms the one `:interrupt-fn`, and holds its admitted permit until settlement.
The run loop reduces over the frozen form plan: the accumulator is the current
basis, initialized from the plan transaction report's `:db-after`; after each
form, that form's transaction report supplies the next basis through
`:db-after`.

For an interaction, the cluster JVM first CASes `:pending → :running` under the
held run fence. Only that committed receipt admits the pinned handler through
SCI with the one `:interrupt-fn` on `:compute`. Success or a flat error is
schema-projected into ordinary data and committed with
`:running → :done|:error` in the same fenced transaction that closes the run.
A replacement cluster JVM that observes `:running` records `:interrupted` and
does not replay the authored handler.

## Plans and receipts

One model reply freezes into an ordered form plan exactly once. The run loop
reduces that plan in order, carrying the prior transaction report's `:db-after`
as the next form's database value. Every form receives a durable running receipt
before SCI dispatch and one terminal receipt afterward. Terminal settlement and
the interpreted disposition commit together under the run pointer and epoch
fence, so no process can publish half a form outcome.

The plan and receipts are the only execution cursor. A terminal receipt is
never re-executed. A running receipt left by a dead process becomes
interrupted, and an absent receipt means no durable result is claimed.
Recovery never fabricates success or infers a result from process memory.

Provider calls have their own attempt facts. Each row records the chosen
descriptor, role, non-secret request projection, transport-phase evidence,
outcome, response identity, usage, and present error fact. There is no
in-memory attempt ledger and no generic turn-phase machine beside these facts.

### Provider failover stops at the no-retry boundary

Hosted providers are descriptor rows selecting one of the two wire cores,
`:openai-compat` or `:anthropic`. A primary descriptor may have one backup
defined as overrides; choosing a backup never introduces a provider-specific
branch in the run loop.

Disposition is a pure function of the failure and transport-phase evidence:
whether the request was transmitted, whether a response started, and whether
output was observed. A connect-class failure that proves nothing was
transmitted may fail over immediately. Once transmission may have occurred,
another paid call would be a retry and is forbidden, including an ordinary
timeout. With no backup, only evidence proving the call was not transmitted may
enter configured backoff; a backup makes that backoff schedule empty.

When failover is allowed, the primary attempt and its normalized error fact
commit before the backup call. The backup's context receives the primary
error's AI projection, so it knows that it is the backup and why the primary
failed. The backup is one explicitly justified attempt, not a replay of an
ambiguous paid request.

## SCI interruption

Every SCI invocation—agent eval, authored render, plan function, or schema
predicate—installs the one zero-argument `:interrupt-fn`. SCI calls it at every
`fn` body entrance. The invocation's `time-limit` is the only execution limit;
when it expires, `interrupt!` stops the eval uncatchably.

Malli never constructs or forks a private SCI context. The schema projection
resolves every admitted `[:fn]` symbol to its already-materialized
program-graph callable before Malli compiles the schema. Predicate invocation
therefore runs
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

## Interrupted, then adapt

Recovery is fact-driven and never an automatic retry path. At boot, the cluster
compares run custody with the live-process set and transactionally releases
dead custody regardless of the abandoned lease's remaining wall time. The
normal loop then derives the resulting wreckage and buries its own dead:
running receipts become interrupted, the run closes as interrupted, and the
facts retain exactly where durable progress stopped.

A process death after claim but before plan freeze loses that model call. The
loop does not call the model again, refire an effect, or synthesize the missing
reply. The triggering message remains answered by its original run-opening
transaction; the run records interruption. On the agent's next real trigger or
manual nudge, its prompt derives one interruption warning from those facts and
the agent adapts. Recovery itself does not manufacture that next trigger.

Committed terminal receipts remain untouched. A stale pre-crash activity that
finishes later fails the same pointer-and-epoch fence used during normal
execution. Boot recovery and loop settlement are idempotent transactions, so a
second crash during recovery merely leaves facts for the next boot to derive
again.

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
Creation does not start a process or spend model tokens.

Messages are delivery. A message row points at its recipient through the one
wake attribute. Committing it offers a coalesced wake; the wake carries no
payload and the loop re-derives unanswered triggers from the new database
value. There is no inbox cursor, acknowledgement flag, or side-channel
delivery. Agent-facing `my.message` functions produce these ordinary durable
message facts through the guarded effect owner.

Answeredness is transaction history. Opening a run attaches the triggering
message ref as `:seon.db/trigger` on that transaction. A message is unanswered
exactly while no run-opening transaction points at it. Crash recovery never
changes that answer by copying a flag onto either entity.

The run loop interprets exactly two pure `my.run` disposition values:

- `my.run/complete` closes the run with the reply text; and
- `my.run/wait` releases custody with a note explaining what is awaited, so a
  later delivery can wake the same run.

They transact nothing themselves. A blank or wrong-shaped argument is a flat
error value, not a throw. Start, pause, resume, and terminate are deliberately
absent.

One schedule proc derives due work from schedule facts and opens runs through
the same Flow graph and transaction transitions. Its `step-fn` does not own
agent execution or custody state. Root and subagents use the same message
delivery and wake mechanism; roles are capability sets, not stored kinds.
`:seon.agent/parent` remains the orchestration connection.

## Program reconstruction

Authored functions, schemas, tests, namespace declarations, and require edges
are database program facts. The cluster JVM reconstructs the admitted program
from those facts, topologically loads declarations, and installs
instrumentation from the same graph. A source change updates the shared
program; it does not create a per-agent copy.

Process replacement reloads declarations and current retained context. It does
not replay scratch evals, provider calls, filesystem effects, Promises, sockets,
or handles. Facts and receipts decide recovery; declaration loading is the only
program reconstruction.

## Isolation and process ownership

The cluster JVM executes agent code, owns transactions and the
committed-transaction feed for its store, evaluates renders through
`seon.sci.eval/evaluate`, and serves its own web UI. Disposable leaf runtimes
run packages and selected platform workers, not the run loop. See
[[architecture]] for the complete topology.

One cluster has one cluster JVM because Datahike's `:self` writer permits one
writer process per store. Scale by adding isolated clusters, never by adding
processes to one store. `:seon.agent.run/process` records transient run custody
without becoming part of the durable agent identity.

## What the database gives us

- single-writer transactions establish one winner for a run-opening or claim;
- immutable database values make each decision reproducible;
- temporal history preserves trigger, claim, epoch, and receipt archaeology;
- refs connect agents, runs, turns, attempts, evals, messages, and blobs;
- replacement compute derives interruption and remaining work from facts; and
- bitemporality enables historical prompt and policy reconstruction.

Seon adds no external lock service, workflow queue, promise registry, or
recovery log. The run process ref and epoch are custody and fence; plans and
receipts are the execution record.

## See also

- [[architecture]] — process topology and the portable capability seam.
- [[data-model]] — run, turn, attempt, eval, and error attributes.
- [[observability]] — receipt forensics and temporal claim archaeology.
- [[ui]] — the in-process render flow and pure database-value renders.
- [[toolkit]] — the flat `my.*` surface and `seon.effect`.
- [[laws]] — circuit-breaker and one-mechanism laws.
- [[roadmap]] — implementation state and evidence.
