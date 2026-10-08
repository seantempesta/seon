---
title: "Card CO-9: A read resolves its projection once"
type: reference
status: active
created: 2026-10-08
---

# CO-9 A read resolves its projection once

Pattern P2 (Opus S7, Astra B1). Lane: Opus (read path, boot-critical).

## What exists
`seon.db/carried-projection` and `projection-cache-key` run 510 times per `:ping` window and 79 times inside one
`seon.db/pull`; a tiny `pull` makes 39 armed calls (14 `database-value?`, 3 `schema-database`); `pull` 36 µs vs
Datahike 8 µs.

## Change
`with-declarations` resolves the projection and declarations once per read entrance and passes them to its
internal transforms as arguments (fully contracted narrow inputs), instead of each helper re-resolving them from
the database value.

## Proof
Extend `seon.db-overhead-test/query-preparation-has-bounded-armed-work` with a one-attribute `pull`:
`(calls-of m 'seon.db/carried-projection)` 1 and total armed calls ≤ 10; value and read evidence unchanged.

## Cost
Expected within 2-3× of Datahike's call. Parent 36 µs / 39 calls per `pull`.

## Commit
`bin/lane commit <lane> -m "CO-9: a read resolves its projection once" --test seon.db-overhead-test --test seon.db-test src/seon/db.clj test/seon/db_overhead_test.clj`

## Dependencies
CO-0; `src/seon/db.clj` order after rip-context and lane datoms-decode release it.
