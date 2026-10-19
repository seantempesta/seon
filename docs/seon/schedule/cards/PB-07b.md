---
title: "PB-07b: schema-only derivations follow the schemas, not the projection"
type: reference
status: ready after its deps
created: 2026-10-08
---

# PB-07b: Schema-only derivations follow the schemas, not the projection

Ledger row PB-07b (groups F/E; waste row W-9). Task `seon.schema/schema-only-derivations-are-rederived-per-projection`
(`resources/seon/tasks/seon.schema.edn:72`). Source: `defn-why/defn-why.md` findings 3 and 10. Model: Sonnet.

`seon.schema.internal/projection-cache-value` (`src/seon/schema.clj:432`) holds each derivation by the projection value, and
every contracted `defn` builds a new projection, so a derivation that reads only schema forms is recomputed after each
definition: the pass after a `defn` costs 94-97 MB and 57 k armed calls against 55 MB and 8 k ordinary. The pattern
that fixes one of them already landed (`65cf8c26d`): `seon.program/program-attributes` (`src/seon/program.cljc:66-83`)
keeps its per-projection holder and holds its answer in `seon.cache` under `[forms admissions]`, which a contract-only
write leaves equal (and the same map objects, so the key's hash is cached).

Split in two lettered slices by file owner.

## PB-07b1: the helper and the error owner's derivations

Files: `src/seon/schema.clj`, `src/seon/program.cljc`, `src/seon/error.clj`, `test/seon/schema_test.clj`.

- ADD `seon.schema/schema-cache-value`
  `{:malli/schema [:=> [:catn [:projection :map] [:cache-key :seon.schema/cache-key] [:weigh [:fn clojure.core/ifn?]] [:derive [:fn clojure.core/ifn?]]] :seon.schema/value]}`
  `[projection cache-key weigh derive-fn]`, docstring "A derivation that reads only `projection`'s schema forms and
  admissions, held per projection and shared across projections with equal forms and admissions.": exactly
  `program-attributes`' body generalized:
  `(projection-cache-value projection cache-key #(seon.cache/lookup cache-key [(:seon.schema.projection/forms projection) (:seon.schema.projection/schema-admissions projection)] weigh derive-fn))`.
  (`seon.cache` is already required by `program.cljc`; check `schema.clj` can require it without a cycle: `seon.cache`
  must not require `seon.schema`. If it does, put the helper in `program.cljc`'s namespace instead and say so.)
- CHANGE `seon.program/program-attributes` to call the helper (same behaviour).
- CHANGE `seon.error/declared-schema-keys` (`error.clj:1722`) and `seon.error/observation-selector` (`:1602`): replace
  `schema/projection-cache-value` with `schema/schema-cache-value`, weight `#(* 64 (count %))`. Both read only
  `:seon.schema.projection/forms` and the registry built from them (read each body and confirm: no
  `:seon.schema.projection/function-contracts`).
- Proof `seon.schema-test/equal-schema-forms-share-schema-only-derivations`: two projections that differ only by one
  function contract (`schema/projection-with-function-contract`, as `seon.program-test/equal-schema-forms-share-one-program-attribute-derivation`
  does) answer `identical?` values from `declared-schema-keys` and `observation-selector`.
- `--test seon.schema-test --test seon.program-test --test seon.error-test`
- Commit: `bin/lane commit <lane> -m "PB-07b1: schema-only derivations are held by schema forms" --test seon.schema-test --test seon.program-test --test seon.error-test src/seon/schema.clj src/seon/program.cljc src/seon/error.clj test/seon/schema_test.clj`

## PB-07b2: the program shapes

Files: `src/seon/db.clj`, `test/seon/db_test.clj`.

- `::program-shapes` (`db.clj:5246`, `:5286`, `:5631`, all `#(@program-shapes-in projection)`) use
  `schema/schema-cache-value` after confirming `seon.program/shapes-in` reads only schema forms (2.1 MB per new
  projection today).
- Proof: `seon.db-test/program-shapes-survive-a-contract-only-projection` (the same two-projection shape, `identical?`).
- Commit: `bin/lane commit <lane> -m "PB-07b2: program shapes are held by schema forms" --test seon.db-test src/seon/db.clj test/seon/db_test.clj`

## Not here

The per-attribute storage predicates in `schema/datahike.clj:415-425,598-618` are cheap per call (one attribute
each); measure before converting. PB-13 removes the per-contract configuration read (`install-function-contract!`).

## Cost

Expected: the pass after a contract-only `defn` costs what an ordinary pass costs plus the definition's own work.
Measure with `seon.pass-probe/window` on parent and change after each slice; report both.

## Dependencies

PB-07a (`error.clj`, smaller) first. `schema.clj` is held by led-ul2, `program.cljc`'s neighbour schema by led-nk1,
`db.clj` by led-cacheb: PB-07b1 after led-ul2 releases, PB-07b2 after led-cacheb.
