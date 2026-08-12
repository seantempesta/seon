---
type: research
status: complete
tags: [research, database, architecture]
---

# Transact-response union boundary (2026-07-20)

This report reconciles the remaining Stage 1.6 FOLD row
[[../../../seon/issues/transact-output-schema-crashed-child-on-ordinary-error]]
with current source and the settled Stage 5 result-discriminator ruling in
[[database-result-union-boundary-2026-07-20]]. The transaction-result union is
partly landed, but the issue is not closed: the focused proof validates a
literal map against the schema without exercising the instrumented public
function, and no retained live proof shows an execution child surviving the
ordinary failure followed by a corrected completion.

The fix remains one `seon.db` contract. `transact!` has a fixed, disjoint
success shape, so it keeps returning the native compact transaction report or
the ordinary database error directly. It does not acquire the explicit outer
`:seon.result/ok?` union required for collision-capable `query`, `pull`, and
`entity` results.

## Dependency ledger

| Dependency or mechanism | Selected source | Constraint |
|---|---|---|
| Malli | `metosin/malli` 0.20.0; `reference-code/malli` at `80138076960e` | `reference-code/malli/src/malli/core.cljc:980-1038` makes `:or` accept when one child validator accepts. `:1200-1335` makes `[:map {:closed true} ...]` reject extra keys. Required error keys already make the two transaction branches disjoint; closedness additionally makes the shared error predicate exact and prevents richer forensic maps from silently entering the public contract. |
| Datahike | maintained fork at `reference-code/datahike` commit `6f2569087ed3` | `reference-code/datahike/src/datahike/db.cljc:130` defines the native `TxReport` fields; `reference-code/datahike/src/datahike/spec.cljc:49-55` confirms `db-before`, `db-after`, `tx-data`, `tempids`, and `tx-meta`. Seon's optional recovered ID fields are facade projections, not a second transaction envelope. |
| Public database facade | `src/seon/db.cljs:24-29,82-92,815-931` at observed HEAD `2becf6f0` | `::transaction-report` is already a closed fixed map and `::transact-response` is already `[:or ::transaction-report ::error]`. `transact!` resolves failures as data. Its exception catch still returns the richer open `seon.error/->map` projection, however, while the settled database error is to become closed. |
| Instrumentation | `src/seon/instrument.cljc:350-455` | An async function's resolved value is validated after Promise settlement. Invalid output is recorded as a core fault and rethrown through the Promise. A schema-only assertion cannot prove the execution path that originally killed the child. |
| Lifecycle consumer | `src/seon/agent/lifecycle.cljs:197-270` | `complete` passes the fixed transaction result through `complete-once`; an error remains ordinary data and `complete*` returns it instead of `:idle`. No lifecycle or execution schema change is needed. |
| Existing source commit | `5e3edf01` | Landed the transaction-report/error union and a literal schema-membership assertion. It did not add an instrumented failing-call regression or the live corrected-completion proof, so the issue correctly remains open. |
| Settled discriminator ruling | [[database-result-union-boundary-2026-07-20]] (`25c9fdf3`) | One closed database error and one schema-derived `db/error?` predicate discriminate fixed results. Only arbitrary collision-capable reads receive the explicit `:seon.result/ok?` outer union. |

## Landed and gap state

Already landed:

- `::transaction-report` is a closed map with the five native Datahike fields
  plus optional `:seon.db.id/eids` and recovered-commit evidence;
- `::transact-response` is an `:or` of that report and `::error`;
- both public arities of `transact!` name `::transact-response` as output;
- writer refusal, transport failure, local validation failure, and unexpected
  exception paths resolve error data rather than intentionally rejecting; and
- `test/seon/db_remote_contract_test.cljs:25-30` proves one literal error map
  is accepted by the registered union.

Still missing:

- `::error` is not closed, so today it accepts richer maps than the settled
  shared database-error predicate permits, even though its two required error
  keys keep it disjoint from an ordinary transaction report;
- the `transact!` catch returns `seon.error/->map`, which may include
  `:seon.error/raw`, stack, cause, ex-data, and other keys. Closing `::error`
  without projecting that value would recreate the original invalid-output
  fault on a different failure path;
- no focused test calls the actually instrumented `seon.db/transact!`, forces
  an ordinary writer refusal, asserts the original normalized database error
  resolves unchanged, and then proves a corrected transaction still succeeds;
- no retained live evidence calls `complete`, observes an ordinary transaction
  refusal without child exit, and then completes successfully from the same
  child; and
- the open issue has no resolution, verification hashes, or archive move.

The current literal validation test is useful contract coverage but is not the
acceptance test named by the issue. It would pass even if the public function
were uninstrumented, if its error-producing path rejected, or if a wrapper
still killed the execution child after settlement.

## Exact fixed success/error union

The final public contract is exactly:

```clojure
[:or
 [:map {:closed true}
  [:db-before :db-before]
  [:db-after :db-after]
  [:tx-data :tx-data]
  [:tempids :tempids]
  [:tx-meta :tx-meta]
  [:seon.db.id/eids {:optional true} :seon.db.id/eids]
  [:seon.db.id/recovered-commit?
   {:optional true} :seon.db.id/recovered-commit?]]
 [:map {:closed true}
  [:seon.error/message :string]
  [:seon.error/kind :keyword]
  [:seon.error/data {:optional true} :map]]]
```

This is a bare fixed-result union, not an envelope. A transaction report
cannot satisfy the closed error map and a database error cannot satisfy the
closed report. The transaction report remains the ordinary success value all
existing callers consume. The database error remains the ordinary failure
value callers classify through the one schema-derived `db/error?` predicate.

Every transaction failure constructor must project once to the closed database
error vocabulary. In particular, the catch around local invocation and
validation failures must not return `error/->map` wholesale after `::error`
becomes closed. Preserve its message, default its missing kind to
`:core-bug`, and include only an ordinary map in optional error data. Raw error
objects and forensic stack/cause fields remain inputs to `seon.error/record!`,
not fields in the public database result.

Do not restore the historical `:seon.db/ok?` plus `:seon.db/error` transaction
envelope and do not reuse the new generic `:seon.result/ok?` read union here.
Either would duplicate a discriminator around two already disjoint fixed
shapes and change every transaction caller for no collision-safety gain.

## One owner and exact files

The source-and-focused-test unit owns only:

- `src/seon/db.cljs` — close and normalize `::error`, expose/use the one
  schema-derived `db/error?`, retain the existing `::transact-response` union,
  and normalize the `transact!` catch through the same database-error
  projection as every other fixed database operation; and
- `test/seon/db_remote_contract_test.cljs` — replace the weak literal-only
  acceptance with the resolved-output instrumentation regression while
  retaining a direct closed-union schema check.

No edit is required in `seon.execution`, `seon.eval`, the wire protocol,
Datahike, agent lifecycle, or `seon.result`. The issue note is closed and moved
to `docs/seon/issues/archive/` only after focused and live proof; that durable
bookkeeping belongs to the integrating orchestrator, not a second source
owner.

## Dependency order

This FOLD row is not an independent pre-Stage-5 patch. It is the fixed-write
acceptance slice of database-result-union owner group 1:

1. freeze and acquire the `seon.db` owner after the active database/host lane
   releases it;
2. close and normalize the single `::error` schema and add the schema-derived
   `db/error?` predicate;
3. retain and prove `::transact-response` as the fixed report/error union;
4. in the same atomic facade cut, add the explicit outer result union for only
   `query`, `pull`, and `entity`, then migrate the direct callers in the owner
   groups already ordered by [[database-result-union-boundary-2026-07-20]];
5. run focused database tests before the broader caller migration gates; and
6. run this issue's live failure-survival and `complete` drill on the same
   frozen ready cluster used for the database-result-union live gate.

Doing the transaction slice before step 2 would either duplicate the error
normalizer or be invalidated immediately by closing the shared schema. Doing it
after the arbitrary-read migration would leave the original child-crash
acceptance unproved while claiming the shared database contract is complete.

## Focused falsifiers

The focused test must exercise the real output-validation seam:

1. load the registered program projection and instrument the live
   `seon.db/transact!` var through the existing instrumentation owner;
2. make the recording authority return one failed transaction response whose
   database projection is exactly the closed error map, including kind and
   optional data;
3. call the instrumented public function and assert that its Promise resolves,
   rather than rejects, to that exact normalized map;
4. assert no `:malli.core/invalid-output` fault was recorded; and
5. from the same session, submit corrected transaction data and assert the
   ordinary compact transaction report resolves with no extra outer envelope.

Add direct schema assertions that the error branch rejects a missing kind,
non-string message, and every extra key, while the report branch accepts both
the native five-field report and each optional Seon projection. An error-shaped
map with report keys must match neither branch. This proves disjointness rather
than relying on branch order.

The smallest focused runner is:

```bash
bin/test-cljs --test=seon.db-remote-contract-test
```

Run it within the coordinated frozen CLJS checkpoint because instrumentation
mutates live vars and overlapping suites are not safe.

## Live falsifier and issue exit

On one ready frozen default cluster, use a disposable managed agent execution
child and record its identity plus generation:

1. have the agent submit one deliberately invalid but harmless transaction
   whose registered-boundary failure resolves as the closed database error;
2. inspect that returned error as ordinary data and then call `complete` from
   the same execution child;
3. observe the run's committed terminal facts and ordinary `:idle` lifecycle
   result;
4. prove the execution child identity and generation did not change between
   the failed transaction and completion; and
5. inspect logs and fault datoms for zero invalid-output core faults naming
   `seon.db/transact!` and no hidden child exit.

The focused authority test, not this non-destructive live drill, owns the exact
writer-refusal case. The live gate owns the cross-boundary consequence: an
instrumented transaction failure remains inspectable data and the same child
can still execute the lifecycle completion that originally exposed the bug.

The issue closes only with the implementation commit, focused test counts,
frozen source digest, live child identity/generation, returned failure and
corrected success values, terminal datoms, and zero matching core faults
recorded in the note before it is archived. A schema validator call, a generic
agent completion smoke test, or a successful transaction alone is insufficient.

## Exit measure

The Stage 1.6 FOLD row is complete when the shared closed-error/database-result
cut retains this exact fixed transaction union, the instrumented failure and
corrected transaction pass in one focused session, the same live execution
child survives an ordinary transaction failure and then commits completion,
and the issue is archived with commit and frozen-proof evidence. Until then,
source shape is partly landed and the acceptance boundary remains open.
