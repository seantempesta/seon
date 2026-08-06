---
type: issue
status: resolved
severity: blocker
tags: [issue, context, database, runtime]
---

# Read the opening `AsOfDB` basis through Datahike's database interface

## Evidence

The opening-database landing `0b3ff2d65` correctly passes
`run/opening-db`—a Datahike `AsOfDB`—through prompt derivation. A direct
three-pass `seon.cluster.turn-test` fixture probe then reaches
`seon.context/capture-tx` and fails at `src/seon/context.clj:165`:

```text
Cannot invoke "java.lang.Number.doubleValue()" because "x" is null
```

The captured `:seon.db/db` is an `AsOfDB` with `:origin` and `:time-point`.
Datahike's maintained source establishes that the record does not expose a
top-level `:max-tx` map entry
(`reference-code/datahike/src/datahike/db.cljc:567-618`). The capture owner
currently calculates `(long (:max-tx db))`, so the pre-provider capture
crashes before the turn can settle.

This was independently reproduced after the database positional-query repair
`fa6017a24`: `seon.db-test` was green, while `seon.cluster.turn-test` still
failed at this exact boundary.

## Owner

The `seon.context/capture-tx` basis derivation and its composition with
`seon.cluster.run/opening-db`. Use Datahike's database-value interface and
vocabulary; do not special-case an `AsOfDB` by class name.

## Acceptance

- A capture over `run/opening-db` records the opening basis transaction.
- `seon.cluster.turn-test` passes completely with opening-database prompts.
- Current, as-of, since, and history database values use the same basis-read
  mechanism without a map-shape branch.
