---
type: research
status: complete
created: 2026-09-19
tags: [db, schema, contracts, verification]
---

# Finish the inherited database contracts and read seams

Read AGENTS.md sections 0–5, the [assignment review](review-inherited-db-contracts-slice-2026-09-19.md),
and the [slice note](db-contracts-and-read-seams-2026-09-18.md) end to end.
The inherited five groups remain intact. This finish adds the two private
contracts, requires the exact nonempty query/pull result and a positive
transaction-report shape, corrects the supplier documentation, and resolves
[the provider ID selector issue](../../../seon/issues/pull-validation-refuses-a-db-id-selector.md).

## Contract and dependency evidence

`src/seon/db.clj:1209` owns `with-declarations`. Its candidate input accepts
arbitrary values because malformed inputs must reach the existing diagnostic;
its continuation is polymorphic across query, pull, datoms and diff results.
The docstring records that boundary; the contract reuses `:seon.schema/value`
and declares the database error union. Both arities declare their callback.
`validate-pulled-result` declares the projection, optional schema key, literal
selector, database, input entity vector, and scalar/vector nullable pulled maps.

Dependency ledger: Datahike's vendored `reference-code/datahike/src/datahike/pull_api.cljc:528`
owns pull; `:541` specifies eager, input-aligned pull-many results with nil
for missing refs. First-party `src/seon/db.clj:1714` decodes these into one
map or a vector of nullable maps. No replacement pull mechanism was added.
The canonical fixture and existing Malli contract arming supply verification.

## Verification boundary

The prescribed main-tree fast overlay exited 64 before tests:

```text
Incomplete --paths overlay; add changed caller files:
src/seon/test.clj src/seon/test/runner.clj test/seon/test/runner_test.clj
```

Those paths were dirty and foreign. None was changed or included. Following
the assignment's fallback, created detached `tmp/db-contracts-finish-wt` at
`f28098ce59e4f009e85cc86fc038a1033bc1977b`, linked the checkout's vendored
`reference-code`, and applied only the five owned code/test/resource diffs.
Ran `bin/test-fast seon.db-test seon.schema-test` there, one foreground run.

Fast tally (2026-09-19T17:32:31Z): **95 tests, 3,650 assertions,
0 failures, 0 errors**. Contracts armed in `:panic` mode: 1,229 registered
and instrumented. This is fast iteration evidence, not cold/platform proof.
`git diff --check` passed. The temporary worktree and patch were removed
after the test process exited.

Read-only MCP runtime status answered for default PID 41822. A JVM probe
with `seon.schema/call-with-projection` returned `{:db/id 35835}` from
`(seon.db/pull database [:db/id] [:seon.ai.model/provider-id "openrouter"])`.
An initial probe without the supplied projection returned the expected
`:seon.schema/missing-projection`; supplying it resolved that diagnostic.
This observes already-loaded behavior, not adoption of this finish. No
lifecycle, reload, adoption, provider call, or cold gate was performed.

## Paths in this slice

- `src/seon/db.clj`
- `src/seon/schema.clj`
- `resources/seon/schemas/seon.db.edn`
- `test/seon/db_test.clj`
- `test/seon/schema_test.clj`
- `docs/prds/steward-platform/research/db-contracts-and-read-seams-2026-09-18.md`
- `docs/seon/issues/pull-validation-refuses-a-db-id-selector.md`
- `docs/prds/steward-platform/research/db-contracts-finish-2026-09-19.md`

The review remains an unchanged dated observation. Unrelated edits are preserved.

## Cold proof owed to the orchestrator

```sh
bin/test --paths src/seon/db.clj src/seon/schema.clj resources/seon/schemas/seon.db.edn test/seon/db_test.clj test/seon/schema_test.clj -- seon.db-test seon.schema-test seon.cluster-test
bin/test --platform
```

The broader review's query-source structural refinement and performance
measurements are outside the four-item finishing assignment; its existing
findings remain recorded there.
