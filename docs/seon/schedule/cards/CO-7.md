---
title: "Card CO-7: A schema's predicate is the check, not an armed call"
type: reference
status: active
created: 2026-10-08
---

# CO-7 A schema's predicate is the check, not an armed call

Pattern P2 (merged.md D4; Opus I1/F1). Lane: Opus (arming). Owner question Q2 (profile cell) in merged.md §4.

## What exists
`seon.schema/runtime-predicate` resolves a predicate symbol in a schema to its Var, so every schema check of a
database, connection, environment or report datom is an armed call (input, output, profile timer). Per `:ping`
window: `database-value?` 8,008 calls, `transaction-report-datom?` 7,679, `connection-object?` 1,272,
`environment?` 967. Wrapped 10 k calls 1.46 ms / 1.76 MB vs original 0.16 ms / 88 B (Astra P).

## Change
`runtime-predicate` returns a function that reads the Var's root at call time and calls
`malli.instrument/-f->original` of it, so a redefinition still takes effect and direct calls stay armed.

## Proof
`seon.schema-test/a-schema-predicate-calls-the-unarmed-predicate` (new): validating `:seon.db/database-value` 100
times: `(calls-of m 'seon.db/database-value?)` 0; a direct call counts 1; after redefining a fixture predicate the
compiled schema sees the new definition.

## Cost
Expected: a schema check costs the predicate (~0.01 µs). Parent ~25% of a `:ping`'s armed calls.

## Commit
`bin/lane commit <lane> -m "CO-7: a schema's predicate is the check, not an armed call" --test seon.schema-test --test seon.instrument-test src/seon/schema.clj test/seon/schema_test.clj`

## Dependencies
CO-0; owner Q2.
