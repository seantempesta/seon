---
title: Agent lifecycle native result and database value cut
type: research
status: complete
tags: [research, prd, database, agent, cljs]
---

# Agent lifecycle native result and database value cut

## Decision

Replace the ambient connection, synchronous remote reads, old database result
envelope, and duplicate agent-state readers in `seon.agent`,
`seon.agent.lifecycle`, and `seon.agent.internal` in one source cut. Preserve
the existing agent, run, message, parent, home, and user entities and refs.
Agent state remains derived from `:seon.agent/terminated-at`, the current open
`:seon.agent/run`, and the run's `:seon.agent.run/paused-at`; do not transact a
state attribute, cache, or lifecycle record.

The one rule for every read-derived lifecycle mutation is:

1. acquire one immutable database value;
2. acquire all authorization and transaction inputs against that value;
3. build ordinary transaction data;
4. commit through `seon.db/transact!`; and
5. continue from the native report's `:db-after`, never from `db/*conn*` or a
   newly acquired head.

Use targeted Datahike compare-and-swap for the exact facts whose observed
values authorize a mutation. Use `:seon.db/expected-db` only for a rare
multi-fact reconciliation whose correctness includes the absence of a fact
that Datahike CAS cannot assert without adding a replacement. Agent
termination is that exception: a concurrent open must not leave a run attached
to a terminated agent. Creation reconciliation and cold-start reconciliation
may use the same whole-value fence because they reconcile several facts once,
outside the hot message/run path.

Success from a database mutation is the native Datahike transaction report.
Failure is a direct map with `:seon.error/message`. Keep useful domain results:
`mint!`, `create!`, and `start!` return the existing
`{:seon.agent/id id}` projection; `wait`, `complete`, `pause`, `resume`, and
`terminate` return their existing state keyword after the mutation commits.
Those are application results, not replacement database envelopes.

Do not add `v2`, remote, compatibility, or transport-named functions. This cut
depends on the message and run cuts and must land with their native result and
explicit database-value contracts, not through temporary adapters.

## Dependency ledger

| Dependency or owner | Selected source | Constraint used |
|---|---|---|
| Seon checkout | Shared checkout at `654cd127` during the final audit | Database facade and callers are changing concurrently; this report names semantic owners rather than preserving line-number accidents. |
| Recovery ledger | `docs/prds/database-authority-mesh/roadmap.md` | One database authority and one implementation per behavior; message and run precede lifecycle consumers. |
| Agent architecture | `docs/seon/architecture/agent-runtime.md` and `docs/seon/architecture/data-model.md` | State is derived; agent birth writes entity/context/home facts; parent and run refs are retained; completion writes result data and delivers it; termination closes the open run. |
| Agent entity owner | `src/seon/agent.cljs` | Keep schemas, identity allocation, birth, start/delegate, depth policy, process hosting, and purpose mutation here. |
| Lifecycle owner | `src/seon/agent/lifecycle.cljs` | Keep the agent-facing wait/complete/pause/resume/terminate functions and their present arities here. |
| Run owner | `src/seon/agent/run.cljs` plus [[run-native-result-database-value-cut-2026-07-16]] | `current-run`, run open/close, pause/resume, and the `:seon.agent/run` fence have one owner. Lifecycle composes them; it does not copy a run service. |
| Message owner | `src/seon/agent/message.cljs` | Completion and delegation keep one message path. Its useful message projection remains domain data; old database envelopes must disappear before these callers can be final. |
| Identity allocator | `src/seon/db/id.cljc` | `allocate!` accepts an explicit `:seon.db/db`, returns the native report enriched with `::db.id/ids`, and retries only an exact generated-identity conflict. |
| Maintained Datahike | `reference-code/datahike` at `a464cd887458d2572414a6ea951c477b0981fdae` | CAS resolves lookup refs, compares inside the serialized transaction, and aborts the whole transaction on mismatch. |
| Duplicate derived readers | `src/seon/derive.cljs` | Pure `state-from-primitives` is retained. Synchronous database access and lifecycle queries are not a second remote API. |
| Runtime host projection | `src/seon/client.cljs:306-460` | Runtime advertisements and the committed feed already own process-local hosted-agent reconciliation; `agent/resumable-agent-ids!` is not another owner. |

Datahike's decisive CAS implementation is
`reference-code/datahike/src/datahike/db/transaction.cljc:893-915` and its
behavioral proof is
`reference-code/datahike/test/datahike/test/transact_test.cljc:221-249`.
Lookup refs are resolved before comparison. An old value of `nil` succeeds only
when the attribute is absent; a present cardinality-one value must equal the
expected value. A mismatch raises `:transact/cas`, aborting the entire
transaction. Ref old/new values are resolved as entity ids.

CAS is therefore a transaction fence, not a retry loop or an application
lock. An operation may reacquire and retry only when its public semantics make
that safe. It must not label every CAS failure transient: a pause that loses to
another pause, a resume that observes no pause, and work against a superseded
run are meaningful direct errors.

## One retained surface

The existing names and arities remain the public interface. Optional
`:seon.db/db` belongs only in existing map requests whose caller may pin a
database value; it does not create another arity or another database API.

| Function | Retained call shape | Result after the cut |
|---|---|---|
| `agent/create!` | Existing `::create-request` map | Promise of `{:seon.agent/id id}` or a direct error. |
| `agent/mint!` | Existing `::mint-request` map | Promise of `{:seon.agent/id id}` or a direct error. |
| `agent/ensure-initial-agent!` | Existing map request | Promise of the selected initial `{:seon.agent/id id}` or a direct error. |
| `agent/start!` | Existing optional map request | Promise of `{:seon.agent/id id}` or a direct error. Birth followed by process hosting remains one user operation. |
| `agent/delegate!` | Existing map request | Promise of the child id projection or a direct error; it keeps `start!` plus the one message owner. |
| `agent/spawn-depth` | `(database agent-id)` | Pure computation over already-acquired parent-chain data after the cut; it is not a remote reader. |
| `agent/resume!` / `agent/unhost!` | Existing process-runtime arguments | Unchanged process-local hosting owner. These names are not database pause/resume. |
| `agent/set-purpose!` | Existing target plus purpose map | Promise of `:idle` or the target's derived state after the native report, or a direct error. Delete its private `::ok?` envelope. |
| `lifecycle/wait` | `([note])` | Promise of `:idle` or a direct error. |
| `lifecycle/complete` | `([result])` and `([result result-ref])` | Promise of `:idle` or a direct error. |
| `lifecycle/pause` | `([])` and `([target-map])` | Promise of `:paused` or a direct error. |
| `lifecycle/resume` | `([])` and `([target-map])` | Promise of `:running` or a direct error; process driving starts only after the native mutation succeeds. |
| `lifecycle/terminate` | `([agent-id])` | Promise of `:terminated` or a direct error. |

`agent/resumable-agent-ids!` has no retained production ownership. Delete it.
The committed-feed listener and runtime advertisement in `seon.client` own the
host's resumable id projection. Keep the one query value only where that
listener consumes it; do not expose a second execute-many wrapper.

`agent/armable-agent-ids` must not remain a synchronous facade. The wake/run
owner should acquire the required primitives and apply the pure derived-state
rule. Delete `derive/armable-agent-ids`, `derive/resumable-agent-ids`, and the
agent wrapper once their actual consumers use the retained runtime/run
acquisition. This preserves derived state while removing duplicate database
readers.

Keep `seon.agent/message!` and `seon.agent/user-ref` only if the public toolkit
intentionally documents those names as the canonical access point. Otherwise
delete the aliases and update callers to the colocated message owner; do not
retain two equally blessed names accidentally. That decision is documentation
surface cleanup, not a transport adapter.

## Exact obsolete production inventory

### `src/seon/agent.cljs`

- `armable-agent-ids` reads `(or db @db/*conn*)` and delegates to a synchronous
  database reader (`336-347`).
- `resumable-agent-ids!` expects the removed execute-many coordinate and member
  envelopes (`357-384`). Its caller responsibility already belongs to the
  runtime advertisement listener.
- `unavailable-db-response`, `acquisition-failure`, and
  `successful-member?` preserve the old nested error vocabulary (`423-438`).
- `create!` uses an outer execute-many coordinate, sends
  `::db/expected-coordinate`, branches on `:seon.db/ok?`, and unwraps
  `:seon.db/error` (`479-530`).
- `allocate-agent!` accepts and forwards `expected-coordinate` instead of one
  explicit database value (`542-560`).
- `mint!` wraps the allocator's native result back into the old envelope
  (`564-583`).
- `ensure-initial-agent!` repeats grouped-coordinate acquisition, whole-head
  coordinate fencing, and old envelope branches (`587-655`).
- `spawn-depth` calls `db/entity` while walking the parent chain (`672-697`).
  Across the authority boundary those calls are Promises and an N-hop request
  pattern.
- `spawn-child!`, `start!`, and `delegate!` branch on old database envelopes;
  `start!` also calls `spawn-depth @db/*conn*` (`718-850`).
- `set-purpose!` reads `@db/*conn*`, calls the synchronous authorization walk,
  and returns a second private `::ok?`/`::error` result (`935-970`).

### `src/seon/agent/lifecycle.cljs`

- `no-open-run-error` is an old database envelope (`49-55`).
- `wait` calls the current-run reader synchronously and branches on
  `:seon.db/ok?` (`59-75`).
- `messaged-recipient-since?` performs an implicit-head query (`78-94`).
- `complete-refusal` calls `testrun/latest-run @db/*conn*` and constructs a
  nested database error (`97-119`).
- `complete` performs separate implicit-head entity/query reads, writes the
  run result in one transaction, sends a message in another operation, closes
  the run in a third operation, and interprets all three through the old
  envelope (`123-197`).
- `pause`, `resume`, and `terminate` select `@db/*conn*`, call the synchronous
  parent-chain authorization walk, and branch on old envelopes (`200-315`).
- `terminate` writes `:seon.agent/terminated-at`, then closes the run as a
  separate mutation. A crash or competing open can expose a terminated agent
  with a live run.

### `src/seon/agent/internal.cljs` and `src/seon/derive.cljs`

`internal/no-agent-error` and `internal/unauthorized-target-error` are nested
database envelopes (`internal.cljs:14-22,56-63`). Return direct errors.
`internal/manages?` performs one remote entity request per parent edge
(`25-54`). Replace it with one private pure predicate over a recursively pulled
parent chain acquired at the operation's database value.

Retain `derive/state-from-primitives`. Delete or make pure every function in
`derive.cljs` that calls `db/entity` or `db/query`, including `current-run`,
`derive-state`, run/turn counters, armable/resumable ids, recent crash scans,
and status derivation. Their database acquisition belongs to the domain owner
that needs the projection. A namespace named `derive` must not be a hidden
second synchronous database client.

`testrun/latest-run` is another synchronous query/pull chain used by
`complete-refusal`. Its test-run owner should expose one async read accepting
the operation's explicit database value, or completion's grouped acquisition
should include the required latest test-run facts. Do not keep a synchronous
adapter.

## Database-value and concurrency semantics

### Birth: `create!`, `mint!`, and `ensure-initial-agent!`

`create!` acquires the requested agent and home facts at one database value.
If converged, it returns the id without writing. If absent or incomplete, it
builds the missing ordinary facts and commits with
`:seon.db/expected-db database`. This whole-value fence is justified because
the operation reconciles a set of facts and their absences; there is no single
authorizing datom. On a stale-database error it may reacquire once and rerun the
pure reconciliation. It must not retry validation or other transaction errors.

`mint!` passes one database value to `db.id/allocate!`. The allocator owns id
generation and exact uniqueness-conflict retry. On success, project the id
from `::db.id/ids`; on direct error, return it unchanged. Do not wrap the
native report.

`ensure-initial-agent!` acquires root, ordinary-agent history, and configured
policy at one value. It seeds only when the facts require it. Like `create!`,
this is cold-start reconciliation, so a whole-value fence plus a bounded
reacquire is clearer and safer than inventing one CAS per optional initial
fact. Initialization still transacts schemas and initial data before the pod
hosts agents; it does not replay every form or seed the same datoms per child.

### Start and delegate

Acquire the parent chain in one recursive pull at one database value and
calculate spawn depth locally. `start!` passes that same value into `mint!`.
After birth commits, it hosts the child from the native report's `:db-after`.
If hosting fails, return the process error directly; do not pretend the
already-committed child was rolled back.

`delegate!` remains composition of `start!` and one message send. The message
cut must return its useful domain projection or a direct error. If sending the
task fails after birth, return that error with the child id as ordinary
context; do not delete the child or manufacture a database failure envelope.

### Authorization

Pause, resume, terminate, and purpose mutation acquire target identity, the
target's recursive parent chain, termination marker, current run, and needed
run primitives together at one database value. Authorization is then a pure
walk of the pulled maps. This is one authority request regardless of hierarchy
depth and every subsequent mutation uses the same observed facts.

Authorization alone is not a write fence. Each mutation still carries the
targeted run/attribute CAS described below, so an authorized caller cannot
silently overwrite a newer lifecycle transition.

### Wait

Acquire the current open run through the retained async `run/current-run`.
Call `run/close-run!` with reason `:waited` and the same database value as
specified by the run cut. Return `:idle` only after the native close report.
No open run is a direct lifecycle error.

### Pause and resume

After one authorization acquisition, call the retained run owner with the
explicit database value. The run owner keeps the current-run pointer CAS and
the exact pause/resume attribute CAS operations defined in the run report.
Lifecycle returns `:paused` or `:running` after the native report. `resume`
invokes `loop/drive-run!` only after commit; admission failure is a direct
error, not a fake database failure.

### Complete

Acquire in one grouped read: the caller agent, current open run, parent or user
recipient, messages from this run's start time, and the latest relevant test
run. Run completion refusal is a pure decision over that one result.

Keep the existing externally meaningful order: persist the result on the run,
send the result through the one message owner, then close the run with reason
`:completed`. Every read-derived run mutation carries the current-run fence.
Use each native report's `:db-after` as the next database value. A message
failure leaves the result inspectable and the run open so completion can be
retried; a close failure after delivery is idempotently recoverable by checking
the persisted run result and message facts at a fresh database value. Do not
hide this workflow behind a cross-process pseudo-transaction and do not create
a second delivery queue.

The completion cut cannot be certified before message idempotency and direct
result semantics are settled. It must reuse the message identity/receipt that
the message cut chooses; otherwise a retry can duplicate the parent delivery.

### Terminate

Termination is the one rare lifecycle operation that needs a whole-database
fence in addition to targeted CAS. Acquire the target, recursive parent chain,
termination marker, and current run at one database value. Build one
transaction containing:

1. `[:db.fn/cas [:seon.agent/id id] :seon.agent/terminated-at nil now]`;
2. when a current run exists, the existing run-pointer CAS asserting that run;
3. that run's ordinary closed row with reason `:terminated`; and
4. retraction of `:seon.agent/run`.

Submit it with `:seon.db/expected-db database`. The whole-value fence covers
the otherwise unassertable case where the acquired agent had no run but a
concurrent opener attached one. If the fence is stale, reacquire and rebuild;
then either close the newly observed run in the same transaction or observe an
already terminated agent. This is not a hot-path lock, and unrelated write
contention costs only a rare termination retry.

Do not copy the run-close rules into an independent lifecycle service. Factor
the existing pure run-close transaction-data builder so termination and
`run/close-run!` use the same internal owner. This is internal composition, not
a new agent-facing API. After the transaction commits, unhost the process.
Process shutdown failure is reported separately from the durable terminated
state; it cannot roll back the database.

### Purpose mutation

Acquire and authorize once. Fence the observed old purpose with
`:db.fn/cas` when present, or `nil` when absent, and transact the new purpose.
Return the target's state derived from `:db-after`, or simply the native report
if the documented public contract does not require a state keyword. Delete the
private `::ok?` result either way; there is one success vocabulary.

## Tests to port and tests to delete

### Port behavior

Use the database-authority fixture or focused async facade fakes. Retain these
behaviors from `test/seon/agent_lifecycle_test.cljs`:

- wait closes the current run and returns idle; no run is a direct error;
- complete refusal, result/ref persistence, one parent/user delivery, retry
  idempotency, and final run close;
- self and managed-target pause/resume/terminate authorization;
- unauthorized and unknown-target direct errors;
- pause/resume exact run fencing and post-commit process driving;
- termination atomically records termination and closes/retracts the observed
  run, including a concurrent-open retry;
- derived idle/running/paused/terminated state from ordinary facts;
- initial-agent convergence, historical ordinary-agent suppression, create
  convergence, identity allocation, default policy, and purpose persistence;
  and
- a child is born once, keeps its parent/home refs, and is hostable after the
  birth report.

Port from `test/seon/agent/multiagent_test.cljs` only the entity/ref,
parent-chain, spawn-depth, delegation, and authorization behaviors. Port the
admission cases in `test/seon/runtime/admission_test.cljs` to direct errors and
Promises. Keep the `agent/create!` consumer in `test/seon/state_test.cljs`, but
move it to the authority fixture rather than preserving a local Datahike
connection.

### Delete mechanics

Delete tests whose only assertion preserves:

- `client/open-agent-conn!`, root `set!` of `db/*conn*`, or direct
  `datahike.api/transact!` setup;
- synchronous `db/entity`, `db/query`, current-run, state, armable, or
  resumable reads;
- `::db/coordinate`, `::db/expected-coordinate`, or coordinate ordering in
  application results;
- `:seon.db/ok?`, nested `:seon.db/error`, or the private purpose `::ok?`;
- the standalone `agent/resumable-agent-ids!` wrapper; or
- sequential terminate-write then run-close as an accepted intermediate
  state.

Do not port the local replica harness merely to make the tests look familiar.
The replacement proof is the same public CLJS function talking through the
authority facade and consuming ordinary database values/native reports.

## Ordered implementation boundary

1. Land the message native-result/idempotency cut and the run cut. Their
   reports are inputs, not compatibility layers.
2. Make the remaining needed `derive` logic pure; add grouped lifecycle
   acquisition data and a pure parent-chain authorization predicate.
3. Cut birth (`create!`, `mint!`, `ensure-initial-agent!`) to explicit database
   values, native allocator results, and bounded reconciliation.
4. Cut start/delegate and delete synchronous spawn-depth reads.
5. Cut wait/pause/resume against the retained run owner.
6. Cut complete against the settled message and run semantics.
7. Cut atomic terminate and purpose mutation; delete old internal envelopes.
8. Delete obsolete armable/resumable/derive readers and port consumers/tests.
9. Run the focused lifecycle gate, then the full CLJS gate, then prove one
   default cluster birth → delegate → pause → resume → complete and a second
   birth → terminate through the live authority.

## Shortest falsifiers

Run these before broad suites; each disproves one architectural claim quickly.

1. **Native result:** fake `db/transact!` with a report containing `:db-after`;
   every lifecycle function succeeds without any `:seon.db/ok?` key. Return a
   direct `:seon.error/message` and prove it is returned unchanged.
2. **One database value:** make the latest session database advance between
   acquisition members. Prove one lifecycle operation sends the same explicit
   database value to every read and its mutation.
3. **One authorization request:** use a three-level parent chain and count
   facade calls. Managed-target authorization performs one grouped pull, not
   one request per ancestor.
4. **Targeted pause race:** launch two pauses on the same run. Exactly one
   commits; the loser is a direct CAS error and cannot replace paused-at or
   remaining budget.
5. **Resume fence:** supersede the run after acquisition. Resume fails its
   run-pointer CAS and never starts `drive-run!`.
6. **Termination/open race:** acquire termination while the agent is idle,
   commit an open before termination, and prove expected-db rejects the stale
   termination. Reacquisition closes that run and writes termination in one
   report.
7. **Completion retry:** fail result delivery after persisting the run result,
   retry, and prove one delivery and one closed run rather than duplicate
   messages.
8. **Birth convergence:** run two creates for the same requested id and two
   initial-agent reconcilers. The final database has one agent identity, one
   home relation, and no duplicate initial datoms.
9. **No second reader:** production scan of the cut namespaces finds no
   `db/*conn*`, `::db/coordinate`, `::db/expected-coordinate`,
   `:seon.db/ok?`, nested `:seon.db/error`, synchronous `db/entity`/`db/query`,
   or public `resumable-agent-ids!`.
10. **Live proof:** in one cluster, start and delegate children concurrently,
    pause/resume one, complete one, terminate another, and observe the same
    entity/ref and derived-state facts from the writer and pod. Restart the pod
    and prove terminated agents stay unhosted while eligible agents reconcile
    from the committed feed.

## Graduation gate

This cut graduates only when the old connection/envelope/coordinate paths are
deleted rather than bypassed; all agent lifecycle reads are async authority
reads over explicit immutable database values; derived state has one pure
definition; birth is convergent; pause/resume/termination are correctly
fenced; completion is retry-safe through the one message path; focused and
full CLJS tests pass; and the live default-cluster lifecycle survives a pod
restart without a local replica, compatibility API, or second runtime owner.
