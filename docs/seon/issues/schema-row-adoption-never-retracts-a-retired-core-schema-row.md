---
type: issue
status: open
severity: platform
created: 2026-09-25
tags: [issue, schema, adoption, retirement]
---

# Schema-row adoption never retracts a retired core schema row

Seen 2026-09-26 00:50Z while proving the R1 retirement (the lost-error seams) adopts into default (pid 94554). The form
is `tmp/impl-seams-r1-schema/adoption-probe.clj`: HEAD's schema forms on `d/with` against default's live db,
through `seon.db/write-error` and `seon.schema.datahike/encode-transaction-in`, in boot's order.

What happens:

- `seon.cluster/declaration-changes` retracts the four retired attributes, which carry 0 datoms, as it should
  (`retired-attributes`, `src/seon/cluster.clj:1067`).
- `schema-row-changes` (`src/seon/cluster.clj:1388`) only upserts the rows the projection still desires
  (`schema/canonical-schema-rows`). It never retracts a `:seon.schema/key` row whose key the projection no longer
  registers.
- After adoption, default keeps 14 `:seon.schema.admission/source :core` rows for schemas that no longer exist:
  `:seon.flow/fault-channel-overflow-error`, `:seon.flow/error-fanout`, `:seon.flow/commit-drop!`,
  `:seon.error/dropped-fault-count` and ten more. Each row still claims its old form as program.

Why it matters:

- The program is rows, so a retired core schema must leave the rows too.
- Leaving it breaks "retraction deletes; history answers what existed".
- Any reader of schema rows (render dispatch, prompt context, merge) sees declarations the code no longer has.

Fix at the producer: `schema-row-changes` (or `retired-attributes`' sibling) emits `[:db/retractEntity e]` for each
`:core`-admitted `:seon.schema/key` row whose key is absent from the projection's forms. Agent-admitted rows stay
with their turn's schema change, which is the same exemption `retired-attributes` makes. Regression: retire a
packaged form on a branch, adopt, and assert its row is gone.
