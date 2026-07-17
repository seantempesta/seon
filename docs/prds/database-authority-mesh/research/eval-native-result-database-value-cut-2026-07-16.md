---
title: Eval native result and database value cut
type: research
status: completed
tags: [research, prd, database, agent, cljs]
---

# Eval native result and database value cut

## Decision

Replace the old database envelope and ambient connection in `seon.eval` as one
behavior cut. Do not add an eval adapter, a compatibility result, or another
operation observer.

The retained contracts are:

- `seon.db/transact!` and `seon.db.id/allocate!` return a native transaction
  report on success and a direct `:seon.error/message` value on failure;
- eval-domain results remain eval-domain data: `:seon.eval/ok?` describes the
  evaluated form, while `:seon.eval/id`, `:seon.eval/status`,
  `:seon.eval/tee-recorded?`, and the retained process-local value describe the
  eval receipt;
- a database-dependent read phase captures one ordinary `:seon.db/db` value,
  sends it explicitly with every member, and uses that same value as
  `:seon.db/expected-db` when its derived transaction commits; and
- the agent form itself is not held against one old database value. A form may
  transact and then query the newly committed state. `seon.db`'s session cache
  advances from each native `:db-after`; eval does not copy that state.

Delete eval operation capture and its blob projection. It belonged to the
removed local read-replay system. Authority request evidence, explicit
`query-with-evidence` calls for diagnostics, and persisted domain/eval facts
are the retained mechanisms.

## Dependency ledger

| Dependency or owner | Selected source | Constraint used |
|---|---|---|
| Recovery order | `docs/prds/database-authority-mesh/roadmap.md` and [[system-recovery-graduation-plan-2026-07-16]] | One implementation per behavior; eval operation capture and web replayability are deleted local-replay mechanisms. |
| Duplicate-owner audit | [[duplicate-runtime-owner-audit-2026-07-16]] | Native reports/direct errors and ordinary database values are already the only public `seon.db` contracts. |
| Datahike | `reference-code/datahike` at `a464cd887458d2572414a6ea951c477b0981fdae` | `datahike.api.impl/transact!` forwards transaction data to the writer; `datahike.api.types/STransactionReport` is the map of `:db-before`, `:db-after`, `:tx-data`, `:tempids`, and optional `:tx-meta`. |
| Datahike full-head fence | `reference-code/datahike/src/datahike/writing.cljc:862-879` | `:datahike/expected-basis-t` compares with the current database before applying a transaction. Seon's `:seon.db/expected-db` is the ordinary-data protocol expression of that fence. |
| Public database facade | `src/seon/db.cljs:464-590` | Reads accept an explicit database or the current session value. Successful writes cache `:db-after`; failures are direct error maps. |
| Generated identity allocation | `src/seon/db/id.cljc:1325-1367` | Allocation already consumes a native report/direct error and enriches success with generated ids; eval must not wrap it again. |
| Eval receipt mechanics | `src/seon/eval/internal.cljs` | `start-tx-data`, `terminal-tx-data`, and `receipt-state` are pure and remain the single receipt state machine. |
| Execution child | `src/seon/execution.cljs:470-492,559-570` and `src/seon/execution/runtime.cljs:383-397` | The child already acquires the invocation database's complete current program and retains one compiler. No eval-local program database is needed. |

Datahike's result shape is not merely stylistic. Its immutable `:db-after` is
the next usable database value, and its writer fence is evaluated against the
current connection state. Rewrapping the report loses the natural state
transition and forces every consumer to invent another success protocol.

## Exact obsolete inventory

### Database envelope

The following `src/seon/eval.cljs` regions still implement the removed
`:seon.db/ok?` plus nested `:seon.db/error` contract:

- `3137-3151`: `unsafe-to-reallocate?` and
  `stale-coordinate-failure?` inspect nested errors;
- `3215-3249`: `start-eval!` converts allocator output into a second database
  success envelope;
- `3251-3461`: `record-eval!` branches on the old envelope for primary,
  settled-status, fallback, and error-stamp transactions;
- `4534-4560`: form execution is gated by the old start envelope and unwraps a
  nested error;
- `4687-4766`: program publication, result binding, repair telemetry, and
  auto-tests branch on the old record envelope;
- `4799-4857`, `5118-5133`, and `5336-5340`: special forms and the batch id
  accumulator repeat the same obsolete success check; and
- `5300-5307`: the run fence treats absence of `:seon.db/ok?` as failure, so a
  valid native report currently fences every run.

These are database transport checks. They are distinct from
`:seon.eval/ok?`, which is a durable domain fact saying whether the evaluated
form succeeded. Keep the latter.

### Ambient connection and coordinate names

- `4172-4184`: `graph-fn-names-in-ns` synchronously queries `@db/*conn*`.
- `4537-4539`: the removed operation observer receives `@db/*conn*`.
- `3267,3365-3366`, `3797`, `3909-3911`, `3994`, and `4809-4855` pass
  `::db/coordinate` and `::db/expected-coordinate` through eval acquisition
  and recording even though the retained public name is the ordinary database
  value and its write fence is `::db/expected-db`.
- `3750-3799` and the corresponding special-form acquisition around
  `3970-3999` build execute-many query members without an explicit
  `:seon.db/db`, then expect the response to manufacture an old coordinate.

There is no connection to restore. The child owns no Datahike connection or
index.

### Deleted operation capture

- `113-115`: eval operation blob schema registration;
- `3153-3213`: canonical operation serializer and blob publisher;
- `3265-3301`, `3907-3908`, and `4675-4685`: operation evidence threading;
- `4534-4562`: call to the already removed
  `db.internal/capture-operations!` and consumption of
  `::db/read-observations`; and
- `src/seon/client.cljs:772-774`: boot schema inclusion.

`seon.db.internal` has no capture owner. Commit `fbc40f48` removed it with the
embedded database and its read-observer tests. Recreating it would add a
process-local duplicate of authority request evidence and retain arbitrary
query results solely for replay/scoring.

## One ordinary database-value flow

The behavior needs one linear succession of database values, not one frozen
value for the entire wall-clock eval:

1. `seon.agent.turn/eval-parsed!` already invokes the child with the exact
   prompt database value. Preserve that argument and echo check.
2. `seon.execution.runtime/eval-batch!` passes that database value to the
   existing eval batch owner. Do not add another runtime entrypoint.
3. The run-fence transaction and `start-eval!` receive the ordinary value as
   `:seon.db/db`. On success their native report's `:db-after` is the next
   current value. On failure the direct error prevents form execution.
4. Execute and auto-await the form without an ambient connection and without
   an eval-wide pinned database context. Database functions with no explicit
   database use `seon.db`'s cached current value; therefore a transaction
   followed by a query observes the transaction. A form that intentionally
   wants one immutable snapshot already writes `(let [database (await
   (db/db))] ...)` and passes it explicitly.
5. After execution is frozen, acquire the small program facts needed to build
   the tee at one fresh `(await (db/db))`. Put that same database value in
   every execute-many member and return it unchanged with the acquired rows.
6. `record-eval!` commits the derived tee and terminal receipt with
   `:seon.db/db database` and `:seon.db/expected-db database`. A stale direct
   error causes `retry-eval-record!` to reacquire and recompile only the
   transaction. It never reruns the agent form.
7. A successful native terminal report is enriched with the existing eval
   domain fields (`:seon.eval/id`, `::tee-recorded?`, and when applicable
   `::retained-value`). The native report remains present, including its next
   `:db-after`. A direct error remains a direct error; the settled competing
   receipt case may add `:seon.eval/id`, `:seon.eval/status`, and
   `::settled?` to that error without nesting it.

This is one state transition chain. There is no separately maintained eval
database cache, connection, coordinate, or report envelope.

An eval-wide `db/with-tx-context {::db/db database}` is explicitly rejected.
It would make `(await (db/transact! ...))` followed by `(await (db/query ...))`
query the pre-transaction value because the fiber-local explicit value wins
over the session cache. That would break normal REPL semantics to make the
implementation appear snapshot-consistent.

## Implementation card

### 1. Make native result classification explicit and local

Add one private predicate in `seon.eval` whose only rule is
`(string? (:seon.error/message value))`. Use its negation for native report
success. Do not expose another `ok?` attribute.

Update `unsafe-to-reallocate?` and the stale-fence predicate to read
`:seon.error/data` directly. Preserve the existing protocol status and
`:seon.db.protocol/error-kind` classifiers; only remove the obsolete nesting
and rename the stale helper in database-value terms.

### 2. Return native reports from receipt writes

- `start-eval!`: pass optional `:seon.db/db` into `db.id/allocate!`; on success
  return the allocator report associated with the generated `:seon.eval/id`.
- `record-eval!`: branch on direct error versus report. Associate eval-domain
  fields onto the successful report rather than constructing
  `{:seon.db/ok? true ...}`.
- Status-read failure: return that direct error associated with the eval id.
- Settled late completion: preserve the primary direct error and associate
  the authoritative terminal status and `::settled?`; never fabricate a
  database error envelope.
- Transcript-without-tee fallback: use the native fallback report. The
  separate `:seon.eval/record-error` stamp succeeds when it returns a report;
  log a direct error otherwise.

Do not change `eval.internal/start-tx-data`, `terminal-tx-data`, or
`receipt-state`.

### 3. Replace coordinate fencing with database-value fencing

Change both eval and special-REPL-form acquisition helpers to accept one
ordinary database value, attach it to every query member, and return that same
value under `:seon.db/db`. Replace every `::db/expected-coordinate` field with
`::db/expected-db`.

The record retry loop is unchanged conceptually: stale database value means
reacquire rows, rebuild pure tx-data, retry the record write. The already-run
form and its prepared result remain frozen.

### 4. Delete the ambient repair query

Delete `graph-fn-names-in-ns` and its call from
`repair-candidate-names`. The execution child installs the complete program
before calling eval. Authored functions therefore already appear in the same
compiler analyzer definitions and live namespace members that the repair
candidate function reads. A third synchronous database candidate source is a
duplicate, not a feature.

The shortest regression test uses a misspelled qualified authored function
loaded by `prepare-eval-program!` and proves the existing analyzer/live-member
sources still produce the suggestion.

### 5. Delete operation capture completely

Delete the eval attribute, canonical serializer, blob publication, frozen-map
field, capture wrapper, and client boot attribute listed above. Run the form
only inside the existing print `AsyncLocalStorage` scope. That scope is
process-local output routing and remains useful; it is not a database owner.

Delete
`seon.eval.promise-ergonomics-test/eval-hands-awaited-database-operations-to-the-recorder`.
It asserts a mechanism that no longer exists. Do not port it to another
observer.

The same implementation commit must reconcile the now-stale target paragraph
in `docs/seon/architecture/observability.md:281-287` and archive or rewrite
`docs/seon/issues/database-workflow-scorer-lacks-query-result-evidence.md`.
The newer recovery decision explicitly deletes these fields. Leaving the older
target and open issue unchanged would immediately teach the duplicate system
again.

### 6. Convert callers without changing the eval domain

Every current `(:seon.db/ok? recorded)` becomes "not a direct error." The
batch id accumulator, result binding, publication, fix telemetry, auto-test
selection, and committed special-form callback continue to depend on a
successfully recorded eval. They do not gain new result names.

The run-fence is lost only when its transaction returns a direct error. A
native report is success. Keep the returned batch shape
`:seon.eval/ids`, `:seon.eval/n-ok`, `:seon.eval/n-fail`, and optional
`:seon.eval/fenced?` unchanged.

Preserve the six-argument public positional `eval-batch!` arity during this
cut. Replace its existing seventh `authored-sources` argument with one private
namespaced request map containing the already acquired authored sources and
the invocation's `:seon.db/db`; only `seon.execution.runtime` uses that arity.
The six-argument arity obtains the current database once and delegates to the
same implementation. Do not create `eval-batch-v2`, an eighth arity, or a
parallel request function. `start-eval!` and `record-eval!` remain one
namespaced request map in/out.

## Focused test disposition

### Keep unchanged as pure tests

- receipt request schema closure;
- `start-tx-data` component shape;
- terminal CAS transaction data; and
- historical `receipt-state` derivation.

These tests in `test/seon/eval/receipt_test.cljs` do not require Datahike or a
connection.

### Port to the authority/native-result contract

- failed terminal status read never enters transcript fallback: stub
  `db/transact!` with a direct CAS error and `db/pull` with a direct read error;
- run fence without a local connection: a direct error fences, a native report
  does not;
- one terminal transition and late transition refusal: exercise the controlled
  authority session, or keep Datahike CAS engine semantics in the paired writer
  test and assert only the CLJS facade result here;
- interrupted receipt makes a late recorder settle without fallback: assert a
  direct error carrying the authoritative terminal status;
- failed start allocation never executes its form and the next form receives a
  receipt: inject a direct allocator error, not an envelope; and
- stale program-record fence reacquires program inputs but does not execute the
  form twice.

### Delete rather than port

- the operation-capture test in `promise_ergonomics_test.cljs`;
- blob canonicalization/persistence assertions whose only subject is
  `:seon.eval/database-operations-blob`; and
- any receipt fixture assertion whose only subject is local `db/*conn*`,
  `open-agent-conn!`, synchronous `db/query`/`db/entity`, or
  `:seon.db/ok?`.

The remaining eval behavior suites (`auto_refer`, `preflight_repair`,
`print_capture`, `promise_ergonomics`, `prose_demote`, `repair_batch`,
`repl_forms`, `require`, and `result_var`) currently share local connection
fixtures. Port them behavior by behavior to a controlled authority session;
do not recreate a test-only embedded database facade. Their compiler, parser,
repair, print, Promise, namespace, and result-var assertions remain valuable.

## Shortest falsifier and dependency order

The first falsifier is a source and stub gate, not a lifecycle run:

1. require zero matches in `src/seon/eval.cljs` for `db/*conn*`,
   `capture-operations!`, `::db/read-observations`, `:seon.db/ok?`, nested
   `:seon.db/error`, `::db/coordinate`, and `::db/expected-coordinate`;
2. stub one successful allocator/transaction with a native report and one
   failure with a direct error, proving start, terminal record, run fence, and
   batch-id accumulation classify both correctly;
3. force one stale `:seon.db/expected-db` response and prove acquisition runs
   twice while the form side-effect runs once; and
4. run a form that transacts a fact and then queries it without an explicit
   database, proving eval did not pin the pre-transaction value.

Implementation order:

1. native result predicates and receipt results;
2. direct-error caller conversion including the run fence;
3. explicit database values and `:seon.db/expected-db` in eval and special-form
   acquisition;
4. ambient repair-query deletion;
5. operation-capture/schema/blob deletion;
6. focused receipt, retry, Promise, and repair tests; then
7. architecture/issue reconciliation and the maintained client compile.

The focused gate should select only the receipt, Promise ergonomics, repair,
and execution-runtime tests until these fixtures no longer construct a local
database. The full CLJS gate belongs at the later coordinated recovery
checkpoint.

## Blocking semantic questions

There is no blocking Datahike semantic question. Native report shape,
full-head fencing, and the need to advance through `:db-after` are explicit in
the selected source.

One product evidence question remains, but it does not block eval recovery:
the old database-workflow scorer expected retained per-eval query result blobs.
The recovery directive rejects that local replay owner. If a future scorer
must prove a particular answer came from a query, specify an explicit bounded
authority diagnostic operation or consume authority request evidence. Do not
silently revive eval-wide capture while implementing this cut.

## Exit measure

The cut is complete when one child can run a fenced batch, persist running and
terminal eval receipts, transact then query within a form, publish an accepted
program delta, bind `result/<id>`, and survive a stale record fence using only
native reports/direct errors and ordinary database values. Production eval
source contains no ambient connection, coordinate wrapper, operation capture,
or database success envelope.
