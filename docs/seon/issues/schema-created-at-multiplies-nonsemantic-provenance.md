---
type: issue
status: open
severity: friction
tags: [issue, database, schema, performance]
---

# Delete schema-row creation clocks from retained snapshots

## Problem

Every canonical schema row stores `:seon.schema/created-at`, even though the
instant does not define schema identity or content and transaction metadata
already owns database provenance. `seon.schema/canonical-schema-rows` requires
and writes it (`src/seon/schema.clj:2180-2202`), while
`seon.cluster/schema-row-changes` reads and preserves it
(`src/seon/cluster.clj:258-262,314-332`). The non-semantic wall clock is
multiplied through every later retained database snapshot.

## Evidence

The exact-total allocation in
`docs/prds/sci-execution-runtime/research/store-census-2026-08-02.md` ranks
`:seon.schema/created-at` first among 208 attribute/origin rows:

- 187,360,394 B total / 946,265 B per sample;
- 95,580,379 B in temporal indexes;
- 183,968,946 B retained by immutable snapshots; and
- only 3,391,448 B reachable from ending heads.

This is inherited fork-parent data, not agent-authored payload, but it is the
largest contributor to the physical per-sample delta because fused roots carry
it forward.

## Owner

`seon.schema/canonical-schema-rows` owns the row shape;
`seon.cluster/schema-row-changes` owns source-population reconciliation.

## Acceptance

- Remove `:seon.schema/created-at` from the global schema, canonical rows,
  reconciliation, renderers, and tests.
- Use transaction metadata for provenance; do not replace the field with
  another entity clock.
- Refork a private eval root and demonstrate the attribute is absent.
- Repeat the attribute census and report the measured store saving. If a
  staged migration is required, `:db/noHistory` may stop new temporal entries
  but is not the finished design.
