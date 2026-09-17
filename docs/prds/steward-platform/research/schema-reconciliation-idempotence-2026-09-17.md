---
type: research
status: open
created: 2026-09-17
tags: [schema, datahike, reconciliation]
---

# Schema reconciliation idempotence

## Observation

`a-canonical-database-is-the-production-source-population` advanced a
canonical connection from `536870919` to `536870920` on its second schema
population pass (`tmp/orchestrator/fast-base-repro.log:18-24`). The pass was
not a changed schema population: Datahike received a transaction and advanced
`:max-tx` even though its effective `:tx-data` was empty. `transact-tx-data`
always increments `:max-tx` after an empty input sequence
(`reference-code/datahike/src/datahike/db/transaction.cljc:1218-1280`), and
the writer commits that report (`reference-code/datahike/src/datahike/writer.cljc:234-273`).

The sharpened regression now listens for the reconciliation report and
asserts that a converged population submits no report at all
(`test/seon/test_support_test.clj:291-303`). Thus the exact effective datoms
carried by the bad second transaction are `[]`; the defect is that the
reconciler handed Datahike an idempotent desired row.

## Cause and repair

Canonical schema rows emitted `:seon.schema/references` as raw schema-key
keywords. That field is a reference relation, while stored rows read it as
schema entities. `seon.cluster/schema-row-changes` only selects and normalizes
such values when the desired value is a set of `[:seon.schema/key key]` lookup
refs (`src/seon/cluster.clj:1088-1169`). A raw-key desired row therefore
failed the read comparison yet reasserted facts Datahike already held.

`seon.schema/canonical-schema-rows` now emits those declared edges in the
transaction grammar, as lookup refs (`src/seon/schema.clj:3058-3063`). The
existing reconciliation owner can select the referenced rows and compare the
same canonical lookup-ref values, producing no transaction once converged.

## Verification boundary

The required command was attempted:

```text
bin/test-fast --paths test/seon/test_support_test.clj src/seon/schema.clj -- seon.test-support-test seon.schema-test
```

The shared-tree overlay correctly refused its concurrently edited foreign
caller `test/seon/cluster/source_test.clj`. An isolated HEAD worktree was
created and the owned diff applied, but its fast overlay then refused because
no published program graph exists, naming the orchestrator-only
`bin/test --prepare-head-base` action. This lane does not run that cold
preparation or edit the foreign caller. Fast tally: no tests admitted; cold
proof remains owed after the orchestrator publishes the HEAD base.
