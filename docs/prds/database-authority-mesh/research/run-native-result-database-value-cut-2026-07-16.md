---
title: Run native result and database value cut
type: research
status: complete
tags: [research, prd, database, agent, cljs]
---

# Run native result and database value cut

## Decision

Replace the ambient connection, synchronous remote reads, old database result
envelope, and duplicate run readers in `seon.agent.run` in one source cut.
Keep one `seon.agent.run` lifecycle owner, the existing run entity and refs, and
the existing `:seon.agent/run` compare-and-swap fence. Do not add a remote,
compatibility, or versioned run API.

Every operation that derives transaction data from database facts follows one
rule:

1. acquire one immutable database value;
2. perform every required read against that exact value;
3. build ordinary transaction data;
4. commit through `seon.db/transact!`; and
5. use the native transaction report's `:db-after` for any post-commit read.

Use targeted Datahike compare-and-swap operations for the facts whose observed
values authorize the write. Do not fence these lifecycle writes with the whole
database value: an unrelated commit must not make another cluster operation
retry. Datahike still serializes each database's commits, while independent
reads and independent databases remain parallel.

Success from `seon.db/transact!` is the native report containing `:db-before`,
`:db-after`, `:tx-data`, `:tempids`, and optional `:tx-meta`. Failure is a
direct map with `:seon.error/message`; there is no `:seon.db/ok?` or nested
`:seon.db/error`. `open-run!` remains the one exception that returns its
existing plain `:seon.agent.run/snapshot` projection on success because that is
its domain result, not a database envelope.

## Dependency ledger

| Dependency or owner | Selected source | Constraint used |
|---|---|---|
| Seon checkout | `7a093b1e5297c5a26da32da5535461ebccfaf252` plus the shared working tree | `seon.db` is already async and native-result-shaped; `seon.agent.run` still implements the removed local contract. |
| Maintained Datahike | `reference-code/datahike` at `a464cd887458d2572414a6ea951c477b0981fdae` | `:db.fn/cas` resolves lookup refs, compares inside the serialized transaction, and aborts the entire transaction on mismatch. |
| Public database facade | `src/seon/db.cljs:440-749` | `db/db`, reads, and `execute-many` are async; reads accept `:seon.db/db`; successful writes cache and return `:db-after`; errors are direct values. |
| Writer | `src/seon/db/writer.clj:1156-1266,1891-1925` | Receipt recovery, current-value checking, transaction preparation, Datahike commit, and report construction are authority-owned. |
| Run entity owner | `src/seon/agent/run.cljs` | Keep the run schemas, refs, bounds, watchdogs, outcome routing, and run-id fencing token in this namespace. |
| Existing read duplicate | `src/seon/derive.cljs:87-101,124-135` | `current-run` and run turn counting synchronously call the now-async facade and cannot remain remote database readers. |
| Config singleton | `src/seon/config.cljs:693-755` | Stored run policy and REPL mode are ordinary attributes on `[:seon.config/id config/cluster-config-id]`; defaults merge locally after one pull. |

Datahike's decisive implementation is
`reference-code/datahike/src/datahike/db/transaction.cljc:893-915`.
For an absent old value, CAS succeeds only when the attribute has no datom. For
a present cardinality-one value, it compares the current value and lowers the
success to the normal add path. A mismatch raises `:transact/cas` before the
transaction commits. Ref attributes resolve both old and new lookup refs.
`reference-code/datahike/test/datahike/test/transact_test.cljc:221-249`
proves absent-to-present CAS, successful replacement, and stale-value failure.

The writer does not need a new run operation. It already prepares and commits
through one Datahike connection, returns the native report, recovers ambiguous
acknowledgements by request receipt, and converts a CAS exception into the
existing direct protocol failure. Run lifecycle correctness therefore belongs
in ordinary transaction data, not a JVM-side run service.

## Retained public surface

The map request is retained for every API-like function. Adding optional
`:seon.db/db` to a map is backward-compatible source shape and makes an exact
database value explicit; it is not a second arity or API.

| Function | Retained call shape | Result after the cut |
|---|---|---|
| `effective-deadline-ms` | `({:seon.db/db database, optional :seon.agent/id})` | Promise of the integer policy value or a direct error. The database remains required because this is an explicitly pinned read. |
| `current-run` | `({:seon.agent/id id, optional :seon.db/db database})` | Promise of the open run map, `nil`, or a direct error. Omission acquires the latest cached database value once. |
| `quiescence-work!` | `()` | Promise of `{:seon.db/db database, ::current-runs [...], ::running-turns [...]}` or a direct error. |
| `turn-limit-reached?` | `(turn-count turn-limit)` | Unchanged pure boolean. |
| `deadline-passed?` | `(deadline now)` | Unchanged pure boolean. |
| `open-run!` | Existing `::open-run-request` map | Promise of the existing run projection or a direct error. |
| `close-run!` | Existing `::close-run-request` map | Promise of the native report or a direct error. |
| `renew!` | Existing `::renew-request` map | Promise of the native report or a direct error. |
| `beat!` | Existing `::beat-request` map | Promise of the native report or a direct error. |
| `pause!` | Existing `::pause-request` map | Promise of the native report or a direct error. |
| `resume!` | Existing `::resume-request` map | Promise of the native report or a direct error. |
| `close-overdue-runs!` | Existing `::close-overdue-request` map | Promise of the existing `{:seon.agent.run/closed [...]}` domain result or a direct error. |
| `stale-run-ids` | `(database now stale-ms)` | Promise of the same vector or a direct error. The arguments stay positional and fully named by the Malli schema. |
| `close-stale-runs!` | Existing `::close-stale-request` map | Promise of the existing `{:seon.agent.run/closed [...]}` domain result or a direct error. |

Delete public `snapshot`. It has no production caller, and its only use is
`open-run!` rereading the run it just created. Keep
`:seon.agent.run/snapshot` as the honest return schema and use one private pure
`select-keys` projection over the already-built run row. This preserves
`open-run!`'s result without a second reader or an extra request.

Keep `current-run` in `seon.agent.run` because this namespace owns run
lifecycle. Delete `seon.derive/current-run` after its callers move to either
the retained async function or pure projections over data they already
acquired. `seon.derive` should keep only pure transformations; it must not be a
second remote run reader.

## Exact obsolete inventory

### Ambient connection and synchronous reads

`src/seon/agent/run.cljs` still reads `db/*conn*` at lines 235, 393, 402, 436,
587, 616, 701, and 828. It synchronously consumes `db/entity` at lines 182,
211, 386, 516, 546, 574, 579, 618, 619, 665, 692, 803, 831, and 832, and
`db/query` at lines 739 and 792. Those values are now Promises.

The break is larger than missing `await`:

- `effective-deadline-ms` calls async `db/entity` and the removed synchronous
  `ctx/run-policy` assumption;
- `snapshot` and `current-run` expose synchronous reads that cannot exist
  across the process boundary;
- `open-run!`, `close-run!`, `renew!`, `pause!`, and `resume!` mix facts from
  different implicit database values;
- `stale-run-ids` performs one query plus one entity request per run; and
- both watchdog shells read more agent facts after their candidate scan.

The allocator request at line 436 passes `:seon.db/conn`. Replace it with
`:seon.db/db database`; there is no connection in the Bun process.

### Duplicate run reads

`run/snapshot` and `run/current-run` are overlapping projections.
`derive/current-run` at `src/seon/derive.cljs:87-101` is a third path, and
`derive/run-turn-count` is another synchronous database reader used by outcome
notice construction. The namespace doc at `run.cljs:18-33` explicitly teaches
the obsolete `*conn*` adapter split.

Use these single owners instead:

- `run/current-run` owns the one async application read;
- a private pure run projection owns `open-run!`'s return shape;
- the close acquisition obtains child, parent, purpose, current pointer, and
  turn count together; and
- callers that already have a compiled acquisition consume its ordinary maps
  without calling either namespace again.

`src/seon/agent/schedule.cljs:408` and the synchronous callers in
`src/seon/agent/lifecycle.cljs` must move in the same consumer cut. Tests in
`run_test.cljs`, `multiagent_test.cljs`, `ticker_test.cljs`, and
`runtime/admission_test.cljs` currently call `current-run` synchronously and
must await it. Do not preserve a synchronous adapter for them.

### Old database envelope and old execute-many result

Actual old-result branches remain at `run.cljs:388-389,438,536-540,548-551,
576-577,594,694-698`; comments and schemas repeat the same contract. Replace
every branch with direct-error classification. The classification is local and
literal: a value with a string `:seon.error/message` is an error; otherwise a
mutation result is the native report. Do not add another public `ok?` key.

`quiescence-work!` still expects an outer `::db/coordinate` and throws on
member failure. Current `db/execute-many` returns only `::db/results`, while
each query member must carry the explicit `:seon.db/db`. Acquire one database
value first, attach it to both members, return it unchanged as `:seon.db/db`,
and return a direct error rather than throwing into the client drain loop.
The protocol's per-member success/error data may be unwrapped by one private
function; it does not become a second application envelope.

## One immutable database value per read-derived write

### `open-run!`

Acquire one database value. In one `execute-many`, pull the existing agent and
the config singleton at that value. Merge `config/default-run-policy` with the
stored config, derive the turn limit and deadline, and build the run row once.
Pass the same value to `db.id/allocate!` under `:seon.db/db`.

Keep the absent-pointer operation exactly as Datahike CAS:

```clojure
[:db.fn/cas [:seon.agent/id id]
 :seon.agent/run nil [:seon.agent.run/id run-id]]
```

Two concurrent opens may read the same idle database value, but the writer
serializes their transactions and only one absent-pointer CAS commits. The
loser receives a direct CAS error and leaves no run row. On success, return the
plain projection of the run row already passed to the allocator; do not query
it again.

### `close-run!`

Acquire one database value and one pull/query result containing the run id,
run status, agent id, the agent's current run id, child parent/purpose data,
and turn count. Determine ownership from that one result.

The owned close keeps the existing exact ordering: run-pointer CAS, close row,
pointer retract. The unowned close writes only the close row and never touches
the current pointer. Submit with `:seon.db/db database`. On success, construct
and send outcome notices from `:db-after`, never from a newly acquired head or
an ambient connection. A notice failure remains logged and does not rewrite a
successful close report.

### `renew!`

Acquire one database value and pull the run, agent override, and config
singleton together. Keep the run-pointer fence. Replace the map overwrite of
the observed turn limit with a mutating CAS:

```clojure
[:db.fn/cas [:seon.agent.run/id run-id]
 :seon.agent.run/turn-limit old-turn-limit (inc old-turn-limit)]
```

Then add the new deadline in the same transaction. This prevents two
simultaneous renewals from both reading `n` and committing `n + 1`; one wins
and the stale one returns a direct CAS error. The caller may retry by invoking
the same public operation again, which acquires the new database value.

### `beat!`

No pre-read is needed. Keep one transaction containing the existing leading
run-pointer fence and heartbeat add. The facade supplies the current database
value internally, and the result is the native report or direct CAS error.

### `pause!`

Acquire one database value and pull the run deadline. Compute
`remaining-ms` once. Commit, in order:

1. the run-pointer fence;
2. an assertion CAS that the observed deadline is unchanged;
3. absent-to-`now` CAS on `:seon.agent.run/paused-at`; and
4. the computed `:seon.agent.run/remaining-ms`.

The second concurrent pause loses instead of silently replacing the first
pause instant or its banked budget.

### `resume!`

Acquire one database value and pull `paused-at` and `remaining-ms`. If either
is absent, return a direct user-input error. Do not resurrect the current
fallback to a freshly read policy; a pause written by this one system always
writes both facts.

Commit the run-pointer fence, assertion CAS operations for the observed
`paused-at` and `remaining-ms`, the new deadline, and retractions of both
pause facts in one transaction. `db/cas-assert` is the correct assertion form
for a value that will then be retracted; Datahike CAS itself is not used to
write `nil`.

### Watchdogs

`close-overdue-runs!` acquires one database value and runs one query returning
the candidate run ids and deadlines. `stale-run-ids` runs one query returning
run id, agent id, started time, and the optional heartbeat; it must not perform
one entity read per result. Both candidate scans are deterministic functions
of the explicit `now` and their one immutable database value.

Each candidate is then closed through `close-run!`. Each close is a separate
read-derived write and therefore acquires its own current database value before
building its fenced transaction. The initial scan is candidate selection, not
authority to retract a pointer after earlier closes have advanced the head.
Record the stale-run fault from the agent id already returned by the scan; do
not reread the run and agent.

## Entity and ref model retained

No stored shape changes are required. A run remains identified by
`:seon.agent.run/id`, connects back to its agent through
`:seon.agent.run/agent`, and is selected as current through the agent's
`:seon.agent/run` ref. Status remains the existing `:open`/`:closed` fact;
paused state remains the presence of `:seon.agent.run/paused-at`; agent state
remains derived. Do not add a run kind, lease entity, lock row, writer queue,
or process-local registry.

The run id remains the fencing token. Every work transaction still leads with
the exact `:seon.agent/run` CAS. The new attribute CAS operations strengthen
read-derived updates; they do not replace that ownership fence.

## Test replacement

### Retain and port in focused CLJS tests

- Pure `turn-limit-reached?` and `deadline-passed?` behavior.
- Exact `close-tx-data` ordering: owned close leads with CAS and retracts;
  unowned close never retracts.
- `open-run!` returns the same plain run projection and direct missing-agent or
  CAS errors.
- Every read-derived write calls `db/db` once, passes that identical value to
  every read/member and mutation, and uses no ambient state.
- `renew!`, `pause!`, and `resume!` emit the exact targeted CAS transaction
  data from their acquired facts.
- `close-run!` supplies `:db-after` to the outcome acquisition.
- `quiescence-work!` puts the same database value in both members, returns it
  unchanged, preserves sorted result vectors, and returns direct errors.
- Watchdog scans use one query with no per-result entity calls and still route
  each mutation through `close-run!`.

Use async facade fakes for these owner tests. Their purpose is to prove request
shape, one-value threading, pure transaction construction, and direct-result
classification without rebuilding Datahike in the pod.

### Move to focused writer/Datahike integration proof

- Two simultaneous opens: one native success, one direct CAS error, exactly
  one open run and one current pointer.
- A stale beat, renew, pause, resume, or owned close commits no datom and never
  moves the newer run's pointer.
- Two simultaneous renewals cannot lose an increment.
- Two simultaneous pauses cannot replace the first pause budget.
- A repeated resume cannot manufacture a new deadline.
- An ambiguous acknowledgement recovers the committed lifecycle transaction
  by its existing receipt rather than running it twice.

These tests belong in `bin/test-writer` because the semantics under test are
the actual serialized Datahike CAS and authority receipt path, not a local
ClojureScript replica.

### Delete rather than port

Delete focused test setup and assertions that depend on:

- `datahike.api` or a pod-local connection;
- `client/open-agent-conn!`, root `set!` of `db/*conn*`, or direct
  `d/transact!` fixture mutation;
- synchronous `db/entity`, `db/query`, `current-run`, or `stale-run-ids`;
- `:seon.db/ok?`, nested `:seon.db/error`, `::db/coordinate`, or
  `:seon.db/conn`; and
- a hand-built `local-quiescence-work` as a second implementation.

Recovery, lifecycle, ticker, and multiagent tests should call the same async
public functions and seed through the authority fixture. Do not preserve the
old test-only database system to keep their current syntax.

## Shortest falsifiers

1. Source reachability: `run.cljs` and its focused tests contain no
   `db/*conn*`, `:seon.db/conn`, `:seon.db/ok?`, nested `:seon.db/error`,
   synchronous remote result, or old coordinate result.
2. Single owner: no production caller invokes `run/snapshot` or
   `derive/current-run`; there is one async `run/current-run` and one private
   pure run projection.
3. One database value: an instrumented fake proves exactly one `db/db` call per
   read-derived write and `identical?` database values in every downstream
   read/member/mutation request.
4. Atomic open: two concurrent opens produce one run projection, one direct
   CAS error, one open run, and one pointer.
5. Lost authority: a stale lifecycle transaction returns a direct error and
   changes neither run facts nor the current pointer.
6. Lost update: concurrent renew and pause tests prove targeted attribute CAS,
   not last-write-wins map overwrites.
7. Post-commit consistency: outcome construction reads exactly the successful
   close report's `:db-after`.
8. Coarse scans: each watchdog candidate pass performs one query, no entity
   request per row, and each resulting close is independently fenced.
9. Quiescence: both execute-many members carry the same database value, the
   returned map carries that value, and a member failure is a direct error
   rather than an uncaught exception.
10. Focused CLJS owner tests and focused writer CAS/receipt tests pass before
    any broader consumer gate.

## Ordered implementation boundary

The smallest coherent implementation is not `run.cljs` alone because its
synchronous API is already consumed synchronously. The source-freeze cut is:

1. replace the run owner and its focused tests;
2. migrate the direct `current-run`, `effective-deadline-ms`, quiescence, and
   watchdog callers to await the retained functions;
3. remove `derive/current-run` and update callers that already have ordinary
   acquired run data;
4. make `seon.agent.message/message!` native-result-shaped before outcome
   notification branches on its result; and
5. add the real writer CAS/receipt proofs, then run the focused CLJS and writer
   gates.

If message migration is not yet integrated, it is a dependency of this cut,
not a reason to add a temporary result adapter. If a broader lifecycle or
derived-status consumer is not ready, keep the source checkpoint unbuilt until
the whole direct caller set is coherent. One implementation and one database
result contract are the acceptance condition.
