---
title: "VEC-10: measure the printed-EDN codec for mixed unions"
type: reference
status: ready
created: 2026-10-08
---

# VEC-10: Measure the printed-EDN codec for mixed unions (diagnosis)

Ledger row VEC-10 (group E). Source: `vectors/vectors.md` §4 item 4. Model: Sonnet. No source change.

Attributes whose schema is a heterogeneous union are stored as printed EDN strings by the schema bridge
(`src/seon/schema/datahike.clj:129-137`) and decoded on every read (`:1125-1140`; the decoded values are the
`seon.cache` kind `:seon.schema.datahike/decoded-edn`). That is a stored printed copy, against "no printing to store a
copy"; vectors.md defers a fork value type until its cost is measured.

## Work (JVM-mode `eval_clj` on default, read-only, connection `(seon.cluster.boot/connection "default")`)

1. List the installed attributes the bridge stores this way (read the predicate at `datahike.clj:129-137` and apply it
   to the carried projection's storable attributes).
2. Per attribute: datom count, total and max string bytes, and the decode cost per read (time 1,000
   `edn/read-string`s of real values, warm; and the `decoded-edn` kind's hits and misses from `seon.cache/status`).
3. Name the fix per attribute: a narrower schema (most unions may be `[:or :string :keyword]`-like and storable as
   one type), a blob (large, read whole), or keep; the fork value type only if many remain (it needs VEC-9's total
   order first).

## Output

`docs/research/agent-platform/2026-10-08/vectors/union-codec.md` with the table and the forms used.

## Commit

`bin/lane commit <lane> -m "VEC-10: measure the printed-EDN union codec" docs/research/agent-platform/2026-10-08/vectors/union-codec.md`

## Dependencies

None.
