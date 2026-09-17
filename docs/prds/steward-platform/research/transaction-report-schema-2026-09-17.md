---
type: research
status: landed
created: 2026-09-17
tags: [seon.db, schema, contracts, datahike]
---

# Transaction report output contracts

The explicit `seon.db/transact!` arity now promises the registered transaction
report or a flat error. The elided arity promises the existing transaction
identity and four-field datoms. Private `transact-call` and `transaction-result`
carry the same contracts through the write chain. No write behavior changed.

Read end to end: AGENTS.md sections 0–5; the data-modeling, datahike,
data-oriented-clojure, clojure-testing and repl skills; the context-generation
plan README and working edge; and
[the contract-findings investigation](contract-findings-query-2026-09-17.md).
Registry search found existing `database-value`, `transaction-report`,
`transaction-result` and `transaction-datom` declarations. These were reused;
only the five-field report datom schema is new.

## Dependency ledger and declared forms

- `reference-code/datahike/src/datahike/api/specification.cljc:420` declares
  the synchronous transaction return; `api/types.cljc:69` and `:82` define
  its native Datom and report shapes.
- `reference-code/datahike/src/datahike/datom.cljc:16` defines Datom;
  `:72` counts five fields and `:111`/`:130` give their sequence/index order.
  It is indexed and seqable, but Malli's literal tuple does not admit it.
- `reference-code/datahike/src/datahike/db/transaction.cljc:1206` validates
  the final report; `:1268` materializes `:tx-data` as a vector and `:1272`
  adds the current transaction to tempids. `:68` admits numeric/string
  temporary identities, so tempid keys remain polymorphic; resolved IDs are ints.
- `reference-code/datahike/src/datahike/writer.cljc:234` batches commits;
  `:268` substitutes the committed database value in each report.
- First-party owners: `src/seon/db.clj:95` (native Datom predicate),
  `:3749` (transact-call), `:3987` (transaction-result), `:4016` (transact!).

The canonical declarations in `resources/seon/schemas/seon.db.edn:185` and
`:246`, omitting descriptions/render metadata here, are:

```clojure
:seon.db/transaction-report-datom
[:or
 [:tuple :int :keyword :seon.schema/value :int :boolean]
 [:fn {:gen/gen seon.db/transaction-report-datom-generator
       :error/message "must be a native Datahike transaction-report Datom with [integer entity, keyword attribute, value, integer transaction, boolean added]"}
  seon.db/transaction-report-datom?]]

:seon.db/transaction-report
[:map
 [:db-before :seon.db/database-value]
 [:db-after :seon.db/database-value]
 [:tx-data [:vector :seon.db/transaction-report-datom]]
 [:tempids [:map-of :seon.schema/value :int]]
 [:tx-meta {:optional true} [:map-of :keyword :seon.schema/value]]]

:seon.db/transaction-result
[:map
 [:seon.db/tx :seon.db/tx]
 [:seon.db/datoms [:vector :seon.db/transaction-datom]]]
```

The native predicate checks Datom identity and the four constrained fields;
`:seon.schema/value` deliberately admits every value. These are runtime-only,
registered non-entity shapes. Database values and native Datoms are not stored
as entity attributes. The descriptions say so explicitly.

`transact!` retains its `:function` contract with separate arities:
elided output `[:or :seon.db/transaction-result :seon.error/value]`, explicit
output `[:or :seon.db/transaction-report :seon.error/value]`.

## Measured contract-findings delta

The canonical fixture regression at `test/seon/db_test.clj:294` validates
explicit success, elided success and refused writes against its handed
projection, and queries `seon.fn/contract-findings` over that same fixture.

| seon.db finding | Before | After |
|---|---:|---:|
| bare-map | 10 | 9 |
| missing-spec | 138 | 136 |
| maybe | 3 | 3 |
| unguarded-variadic | 1 | 1 |
| total | 152 | 149 |

Removed findings: `seon.db/transact!` output `:map` (97 callers),
`seon.db/transact-call` missing contract (1 caller), and
`seon.db/transaction-result` missing contract (1 caller). The regression
asserts that none of those three subjects remains in the findings.

## Verification and boundaries

Initial baseline: 57 tests, 425 assertions, zero failures/errors. The first
literal-tuple implementation produced 52 fixture-construction errors:
`seon.db/transact-call refused return value at [:tx-data 0]: expected a tuple with 5 entries, got an instance of datahike.datom.Datom.`
The native predicate branch fixes that representation mismatch.

At isolated HEAD `cff1c3bea` plus the owned files, `bin/test-fast seon.db-test`
passed 57 tests / 430 assertions / zero failures / zero errors, with 1,153
contracts armed. The four-namespace sweep there ran 117 tests / 1,013
assertions / 9 failures / 10 errors. No diagnostic named the new output
contract. All reds were in turn/issue consumers; message and database tests
were green.

The initial shared-tree overlay demanded concurrently edited `src/seon/fn.clj`
and `test/seon/fn_test.clj`. The authorized fallback worktree had no published
graph for `--paths`, so plain fast tests ran against its HEAD plus only this
lane's changes. A later exact `--paths` run was admitted at `a16354a1f`.
The exact final command was:

```sh
bin/test-fast --paths src/seon/db.clj resources/seon/schemas/seon.db.edn test/seon/db_test.clj -- seon.db-test seon.turn-test seon.cluster.message-test seon.issue-test
```

It completed with exit 1: **117 tests / 1,013 assertions / 9 failures /
10 errors**, matching the isolated sweep. All 57 database tests and all
message tests completed without a failure/error. Arming reported 1,153
registered and instrumented contracts in panic mode. The final query again
reported 149 seon.db findings and an empty targeted finding vector.
The run used snapshot `tmp/test-runs/run.q1rz6a`; the launcher removed it on
exit. The log is `tmp/transaction-report-final-fast.log`, SHA-256
`6a6730379e6c0795e0bc31a79c320152b47116e816bfd9ba41022156f52d5a16`.
The complete red inventory below is durable even after scratch cleanup.
The isolated worktree was removed after confirming its runner had exited
and its owned diff matched the main tree byte for byte.

Touched paths: `src/seon/db.clj`, `resources/seon/schemas/seon.db.edn`,
`test/seon/db_test.clj`, this note, and the existing
`docs/seon/issues/turn-consumer-fixtures-read-retired-result-storage.md`.
`git diff --check` passed for these paths. Markdown hook feedback reported
33 existing cross-document citation errors, beginning with obsolete dependency
gitlink citations in `docs/prds/context-generation/research/agents-md-audit-2026-09-15.md:226`;
those unrelated documents were not edited.

No cold gate or platform proof is claimed; those remain the orchestrator's
integration boundary. MCP runtime tools were absent, as already tracked by
[the existing open tool issue](../../../seon/issues/seon-mcp-tools-absent-in-codex-lane-again.md).
No manual operation was performed on default. Live adoption was not verified.

## Caller findings

Callers refused by the new transaction-output contract: **none observed**.
The following diagnostics instead name existing input contracts or stale
assertions. They are findings for the consumer owners, with no foreign fix
included. The earlier statement that all reds were symbol migration was too
broad: the issue render assertion is separately tracked in
[the issue-render note](../../../seon/issues/the-issue-ai-render-no-longer-teaches-its-requery-form.md).

| Test/call site | Red count | First diagnostic or failed expectation |
|---|---:|---|
| `test/seon/turn_test.clj:1406`, settlement-refuses-a-divergent-program-change-since-opening | 3 errors | `seon.turn/receipt-settle-tx refused request at [:seon.program/row :seon.ns/name]: expected the required key :seon.ns/name with a symbol, got a map missing :seon.ns/name.` |
| `test/seon/turn_test.clj:1432`, same test | 1 failure | `expected` source `(defn ^{:malli/schema [:=> [:cat] :int]} scratch [] 1)`, actual `nil` |
| `test/seon/turn_test.clj:755`, test-first-subjects-resolve-when-the-function-arrives | 1 error | `seon.turn/receipt-settle-tx refused request at [:seon.program/row :seon.ns/name]: expected the required key :seon.ns/name with a symbol, got a map missing :seon.ns/name.` |
| `test/seon/turn_test.clj:821`, batch-settlement-preserves-declaration-order | 1 error | `seon.turn/receipt-settle-batch-tx refused requests at [0 :seon.program/row :seon.ns/name]: expected the required key :seon.ns/name with a symbol, got a map missing :seon.ns/name.` |
| `test/seon/turn_test.clj:1214`, settlement-mints-rows-for-unindexed-call-targets | 1 failure | `publication supplies the macro identity`; `(:db/id macro-row)` is nil |
| `test/seon/turn_test.clj:1215`, same test | 1 failure | `(string? (:seon.fn/source macro-row))` is false (nil) |
| `test/seon/turn_test.clj:1216`, same test | 1 failure | `(true? (:seon.fn/macro? macro-row))` is false (nil) |
| `test/seon/turn_test.clj:1231`, same test | 1 failure | `the evaluation records its resolved call against the published identity`; expected string-identity row, actual nil |
| `test/seon/turn_test.clj:1244`, same test | 1 failure | `an unresolvable mention is not a call edge`; actual `#{[missing.target/nope]}` |
| `test/seon/turn_test.clj:1258`, same test | 1 error | `seon.turn/receipt-settle-tx refused request at [:seon.program/row :seon.ns/requires [:seon.ns/name unindexed.required]]: expected a symbol, got a lookup-ref vector.` |
| `test/seon/turn_test.clj:49`, issue-budget-counts-a-call-that-closes-before-the-provider | 1 error | `Fixture write was refused at the write: seon.db/transact! refused transaction data at [0 :seon.issue/detector 1]: expected a namespaced symbol, got a string.` |
| `test/seon/issue_test.clj:196`, an-archived-note-reports-unresolved-tokens-without-a-refusal | 1 failure | expected string `"seon.db/pull"`, actual symbol `seon.db/pull` |
| `test/seon/issue_test.clj:272`, issue-worker-opening-links-its-issue | 1 error | `Fixture write was refused at the write: seon.db/transact! refused transaction data at [0 :seon.issue/tests 0 1]: expected a namespaced symbol, got a string.` |
| `test/seon/issue_test.clj:336`, detector-only-issue-starts-and-settles-from-its-subject | 1 error | `seon.issue/generate! refused argument 0 (0-based) at [:seon.issue/detector]: expected a namespaced symbol, got a string.` |
| `test/seon/issue_test.clj:209`, a-token-naming-two-identities-is-reported-never-guessed | 1 error | `Fixture write was refused at the write: seon.db/transact! refused transaction data at [0 :seon.issue/id]: expected a string, got a symbol seon.db/aligned-query-arguments.` |
| `test/seon/issue_test.clj:35`, indexed-issues-replace-facts-and-retract-removed-notes | 1 failure | expected string test identity, actual qualified symbol |
| `test/seon/issue_test.clj:65`, same test | 1 failure | expected `(my.issue/status` in `"Issue probe-member: still open.\nMember"` |
