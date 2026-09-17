---
type: research
status: complete
tags: [program-graph, schema-admission, call-preparation, reset]
---

# Tier 1: prepared call counts and render targets at final write admission

The reviewed raw-arity proposal would reject `(my.message/inbox)` even
though SCI supplies its request argument. The owner accepted preparation-aware
count admission and moved required function-file provenance into the edge
retype publication. This slice implements only Tier 1; it does not change
edge storage, enable universal deletion enforcement, or delete `fn.ast`.

## Dependency ledger and authority

The named reset authorities and
`docs/prds/steward-platform/research/unbreakable-connections-2026-09-16.md`
were read end to end during this assignment. The reviewed ordering is in
`docs/prds/steward-platform/plan/reset-batch-2026-09-17.md`.

- Datahike's final report validator
  (`reference-code/datahike/src/datahike/db/transaction.cljc:1206`) sees
  effective and attempted datoms after transaction-function expansion and
  refuses the entire report before committing. Seon's existing
  `write-report-error` remains the one admission owner.
- `seon.call-preparation/plan-for` owns slot eligibility and supplied defaults;
  `prepare` owns actual placement, ambiguity and supplier invocation.
  `prepared-arities` projects compatible source-count ranges from that plan.
  Fixed arities range from declared count minus eligible slots through full
  count. Variadic calls retain their declared minimum. Count compatibility
  does not promise that arbitrary values have an unambiguous placement.
- `seon.db/arity-mismatches` is the shared report. The writer uses Datahike's
  uncapped query directly for complete caller tuples; the public read uses
  Seon's query owner. Only source counts outside declared bounds need a
  preparation snapshot and per-callee plan. An unavailable snapshot refuses
  rather than reporting an empty mismatch list.
- Render declarations are read from stored canonical schema forms, using
  `seon.schema.form/attr-form-properties`. Their function identities must
  exist in the final database. Complete source population admits canonical
  schema rows with functions in `seon.fn/index!`'s existing transaction.
  This removes the former schemas-before-render-functions publication gap.

## Regression contract

Canonical `with-database` fixtures, armed contracts, and real transaction
reports cover namespace/task target retraction naming its surviving referrer;
render target absence and same-transaction repair; an analyzer-produced
`(my.message/inbox)` source count 0 accepted, source count 2 refused with
prepared range 0–1; and callee arity-component changes with atomic caller
repair. The existing real-SCI partial-placement regression also asserts the
plan's count range includes the supported partial call.

No file requirement lands here. External stubs, the required file, G4
producing identity and the canonical fixture constructor change together in
the edge-retype group. That group still requires RESET NEEDED reporting.

## Live observation and verification

After `bin/seon status` reported restarted default PID 28164 alive, this lane
made its one permitted read-only evaluation. The inbox plan reported counts
`[0 1]`, one declared argument, and no supplier refusals in 1,382 ms. The MCP
envelope was windowed and identified digest
`29a064c3fc0614dcbe67c1a26b1a2ff07508d103da73af8ac407487f1358cf7d`;
this is a live grounding observation, not a claim that the new invariant was
exercised there. The orchestrator subsequently reported its owner-authorized
reset and default PID 33583. This lane did not reset or restart default.

Verification used: `bin/test-fast --paths` with the owned implementation,
schema and test paths, six requested namespaces (the actual render namespace
is `seon.render.entity-pairs-test`), three slots. The known cold-fixture
silence issue uses a bounded 900-second diagnostic backstop for this run.
The first run and its separate preparation run were terminated by the
orchestrator's shell cleanup before a verdict. A queued second snapshot was
superseded locally after fixing a brittle arbitrary-callee expectation. The
third combined run acquired a slot after 316 seconds, then received TERM
during JVM loading (exit 143, no test verdict). The current seven-namespace
log is `tmp/reset-tier1-prepared-fast-4.log`.
The combined run completed **189 tests, 1,832 assertions, 17 failures,
zero errors**. All failures were the known stored-versus-pulled grammar class
at `test/seon/schema_test.clj:126`. All other namespaces passed: database 51,
function 59, program 27, schedule 10, render pairs 2, preparation 16.
The stale whole-pull assertion is replaced with authored-key preservation and
installed cardinality-many vector checks. Admission and reference-target
checks remain. Group 1's derived reader validator remains unimplemented and
its issue remains open. The focused schema rerun finished green: **24 tests, 632 assertions,
zero failures, zero errors**, `tmp/reset-tier1-prepared-schema-5.log`.
Tier 1 production bytes were unchanged between these two runs. The combined
snapshot used HEAD `ca8fd63b9`; the schema rerun used `81659acd1`. Other lanes
continued landing after those snapshots; these results do not claim the
orchestrator's subsequent integration gate. These are
`bin/test-fast --paths` results, as the assignment explicitly requires;
no isolated cold gate, platform gate, or result-fact publication is claimed.

The exact implementation paths are `src/seon/db.clj`, `src/seon/fn.clj`,
`src/seon/cluster.clj`, `src/seon/call_preparation.clj`, and
`resources/seon/schemas/seon.fn.edn`. The regression paths are
`test/seon/db_test.clj`, `test/seon/fn_test.clj`,
`test/seon/call_preparation_test.clj`, and `test/seon/schema_test.clj`.
The db exception diagnostic also now supplies the already-required symbol
class, rather than the stale string under `:seon.error/exception-class`.

The report currently scans recorded tuples and declared bounds for every
nonempty final transaction; it does not keep a mutable admission cache or an
incomplete hand-maintained dependency watch set. Only tuples outside declared
bounds need a preparation snapshot and a plan per distinct callee. No latency
bound is claimed from this verification. The observed full-index regression
completed in about 112 seconds; the existing query latency regression passed.

No additional default prepl evaluation or default state-changing command was
used after the one authorized observation. The orchestrator owns live
integration and the later edge-retype reset. **Tier 1 itself needs no reset.**

Standalone lint reports one existing error, `parser.type/->Variable` at
`src/seon/db.clj:583`, tracked in
`docs/seon/issues/kondo-does-not-resolve-datalog-parser-generated-variable-constructor.md`.
There are no new lint errors in this diff. The canonical instrumented load
remains the execution proof.

Shared boundary at the final status check: `src/seon/program.cljc`,
`resources/seon/schemas/seon.program.edn`, `src/seon/schema.clj`,
`src/seon/error.clj` and `src/seon/issue.clj` still carry foreign edits. The
HEAD-plus-owned-path runs exclude those edits. Selection is clean and released.
The no-default-cluster source files and `test/seon/test_support.clj` have since
landed at `cdfc01058`, so their old holds no longer bound the next edge group.
The remaining held program/schema files do. All held hunks were preserved.
