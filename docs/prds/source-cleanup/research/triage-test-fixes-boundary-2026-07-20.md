---
type: research
status: complete
tags: [research, prd, architecture, testing]
---

# Folded test-fixes boundary (2026-07-20)

The two remaining test-hygiene rows in
[[issues-triage-2026-07-20]] are stale ledger entries, not remaining Stage 5
implementation. Both defective fixtures were deleted with their superseded
runtime mechanisms, both issue notes are already resolved and archived, and
HEAD retains focused replacement coverage that does not recreate either
global-corpus assumption.

Do not restore either deleted integration harness or schedule a test-hygiene
source lane for these rows. The source-cleanup ledger should reconcile the two
FOLD rows as already closed by deletion plus maintained replacement coverage.

## Dependency ledger

| Dependency or mechanism | Selected revision | Current evidence |
|---|---|---|
| ClojureScript test runner | Repository `bin/test-cljs` at observed HEAD `8eec9e15` | Focused selectors use ordinary `cljs.test`; no alternate runner or live-pod test execution is required. |
| Atomic eval receipt | `src/seon/eval.cljs` and `test/seon/eval/receipt_test.cljs` at observed HEAD `8eec9e15` | The maintained receipt regression supplies its own `tee-row`, captures the one receipt transaction, and asserts that exact row at transaction position 2. It never queries the complete `:seon.schema/key` population. |
| Repair preflight eligibility | `src/seon/eval.cljs` and `test/seon/eval/repair_batch_test.cljs` at observed HEAD `8eec9e15` | The maintained focused regression initializes the real bootstrap compile state and calls the pure `preflight-eligible?` seam. It opens no database and depends on no process-global schema registrations. |
| Retired pod-wide replay fixture | deletion commit `2884c41b` | `test/seon/eval/record_eval_tee_test.cljs` was deleted with pod-wide program replay. The commit is an ancestor of HEAD and the path remains absent. |
| Retired embedded preflight fixture | deletion commit `97654066` | `test/seon/eval/preflight_repair_test.cljs` was deleted during operation-owned runtime configuration unification. The commit is an ancestor of HEAD and the path remains absent. |
| Issue reconciliation | `3a0dbd31` | Both issue notes were marked resolved and moved under `docs/seon/issues/archive/`; the reconciliation commit is retained by HEAD. |

No external library behavior is in dispute. This boundary concerns fixture
scope and repository history, so the first-party deletion diffs and maintained
tests are the authoritative sources.

## Schema-tee singleton row

### Original defect

The deleted
`test/seon/eval/record_eval_tee_test.cljs` selector
`data-ns-schema-tee-lands-both-rows-and-upserts-the-ns` queried every schema
entity matching `:seon.schema/key` and compared the result to the singleton
`#{[:probe.dom/dur-secs :probe.dom]}`. Any ordinary boot or sibling fixture
schema made that assertion fail even when the owned tee row was correct.

### Current disposition

[[../../../seon/issues/archive/eval-schema-tee-test-assumes-empty-schema-corpus]]
correctly records this as resolved. Commit `2884c41b` removed the entire
1,924-line pod-wide replay harness rather than carrying its embedded-database
assumptions into the execution-child architecture. Current `receipt_test.cljs`
tests the surviving contract at the smaller owner: it passes one literal
function tee row to the receipt writer and asserts that the exact row shares
the receipt transaction. Searches of maintained tests find neither the old
`:probe.dom/dur-secs` fixture nor another singleton assertion over the global
schema corpus.

This is stronger test isolation than editing the deleted query to add an input
filter. Restoring a database-wide tee integration test would restore the
retired pod recording mechanism and duplicate the receipt owner's proof.

### Smallest falsifier

At the next coordinated frozen CLJS checkpoint, run:

```bash
bin/test-cljs --test=seon.eval.receipt-test
```

The decisive assertion is that the supplied `tee-row` is present in the one
CAS-fenced receipt transaction. A future test that compares an unfiltered
query over all `:seon.schema/key` facts to a singleton reopens the issue; a
suite-wide schema count does not strengthen this contract.

## Preflight ambient-schema row

### Original defect

The deleted `test/seon/eval/preflight_repair_test.cljs` opened a fresh database
through `client/open-agent-conn!` but relied on unrelated namespaces to have
registered provenance and `:seon.repair/*` attributes in the process-global
schema registry. A focused selector could therefore fail during fixture setup
before exercising preflight admission or repair.

### Current disposition

[[../../../seon/issues/archive/preflight-repair-focused-selector-relies-on-ambient-schemas]]
also correctly records this as resolved. Commit `97654066` removed that fresh
embedded-database harness with the operation-context cut. The maintained
`preflight-skips-referred-macro-invocations` test in
`test/seon/eval/repair_batch_test.cljs` now initializes the real bootstrap
compile state and exercises the pure eligibility boundary directly. Its only
inputs are the resolved test configuration, compile state, source, and
starting namespace; it neither reads nor mutates the schema registry or a
database connection.

The broader repair transformations remain covered by `test/seon/repair_test.cljc`
and `test/seon/repair_candidates_test.cljs`. Reintroducing database persistence
only to prove preflight eligibility would conflate two boundaries and recreate
the ambient dependency the issue rejected.

### Smallest falsifier

At the same coordinated frozen CLJS checkpoint, run:

```bash
bin/test-cljs --test=seon.eval.repair-batch-test
```

The selector must pass when run alone from a fresh test process. It must not
require `seon.agent`, `seon.client`, `seon.db`, `seon.db.id`, or any fixture
schema-registration namespace. If maintained preflight coverage again needs a
database, that new test must register every schema it writes in its own
fixture; it must not rely on namespace load order.

## Ownership and concurrency ruling

There is no source or test implementation to assign independently of U4. Both
rows are already closed, their retired paths are absent, and their maintained
owners are outside U4's active turn/AI work. A documentation-only ledger
reconciliation is safe at any time, but running focused or full CLJS tests must
still wait for the orchestrator's coordinated source freeze: a green result
against a moving shared tree is not graduation evidence.

If a future regression falsifies either maintained selector, the smallest
owner sets are:

- schema tee: `test/seon/eval/receipt_test.cljs`, with
  `src/seon/eval.cljs` read-only unless the exact receipt transaction is wrong;
  and
- preflight eligibility: `test/seon/eval/repair_batch_test.cljs`, with
  `src/seon/eval.cljs` read-only unless the pure eligibility result is wrong.

Neither case authorizes restoring `record_eval_tee_test.cljs`,
`preflight_repair_test.cljs`, a fresh embedded database harness, or a second
test runner.

## Exit measure

The two triage rows require no new implementation commit. Reconcile them in
the active ledger as stale/already closed, citing deletion commits `2884c41b`
and `97654066`, archive reconciliation `3a0dbd31`, and the current focused
owners above. Their next proof is the ordinary final frozen CLJS checkpoint,
not a separate Stage 5 work unit.
