---
title: "DIAL-8: merge comparison bounds are dials (absorbs DIAL-13)"
type: reference
status: ready after dials-16gb
created: 2026-10-08
---

# DIAL-8: Merge comparison bounds are dials (absorbs DIAL-13)

Ledger rows DIAL-8 and DIAL-13 (one card: the same reader). Source: `dials/dials.md` rows D17 and D26. Model: Sonnet.

The program comparison that `merge!` and `my.program/diff` share, `seon.cluster.source/compare-branch`
(`src/seon/cluster/source.clj:824`), hardcodes its bounds: `{:seon.program/max-datoms 10000}` and the merge-base
search bound `10000` (`:836-837`). It hands them to `seon.program/digest-map` (`src/seon/program.cljc:545`), whose
AEVT paging hardcodes `:limit 200` and `:max-result-weight 10000000` per page (`:579-584`; the only `seon.db/index-page`
caller in `src`). The page bounds limit one read's memory; `max-datoms` limits the whole comparison.

## Files

- `resources/seon/schemas/seon.config.db.edn`
- `src/seon/cluster/source.clj`, `src/seon/program.cljc`, `src/my/program.clj`
- `resources/seon/schemas/seon.program.edn` (the digest-map request map)
- `test/seon/cluster/source_test.clj`

## Schema (`seon.config.db.edn`)

```clojure
:merge-max-datoms [:int {:min 1 :seon.config/dial true :seon.config/default 10000
                         :description "Index datoms a program comparison (merge, diff) reads before it refuses with its bound."}]
:index-page-size [:int {:min 1 :seon.config/dial true :seon.config/default 200
                        :description "Datoms per index page a whole-range program read takes at a time."}]
:index-page-max-weight [:int {:min 1 :seon.config/dial true :seon.config/default 10000000
                              :description "The decoded weight one index page may reach before it refuses."}]
```

The digest-map request (`[:map [:seon.program/max-datoms …] [:seon.program/identities {:optional true} …]]`,
`program.cljc:557-558`) gains optional `[:seon.config.db/index-page-size …]` and `[:seon.config.db/index-page-max-weight …]`;
declare them in `seon.program.edn` where `:seon.program/max-datoms` is declared.

## Functions

- `seon.program/digest-map`: page with `(:seon.config.db/index-page-size request)` and
  `(:seon.config.db/index-page-max-weight request)`; both required by the time it pages (the caller supplies them).
- `seon.cluster.source/compare-branch` `[held-store candidate head]` → `[held-store candidate head bounds]`, contract
  `[:=> [:cat :seon.store/store :seon.store/branch :seon.db/database-value [:map [:seon.config.db/merge-max-datoms :seon.config.db/merge-max-datoms] [:seon.config.db/index-page-size :seon.config.db/index-page-size] [:seon.config.db/index-page-max-weight :seon.config.db/index-page-max-weight]]] :map]`:
  `bound` becomes `{:seon.program/max-datoms (:seon.config.db/merge-max-datoms bounds) …the two page keys}` and
  `merge-base`'s `10000` becomes `(:seon.config.db/merge-max-datoms bounds)`.
- Its two callers (`source.clj:882`, `my/program.clj:960`) pass `(select-keys effective [...the three keys])`, where
  `effective` is `(seon.config/effective database cluster-name)` (`config.clj:919`) read once by the caller; read how
  each caller names its cluster (both hold a store and a connection) and use that.

## Proof

New `seon.cluster.source-test/merge-comparison-bounds-come-from-config`: on a test branch with a few declarations,
`compare-branch` with `:seon.config.db/merge-max-datoms 1` answers the bound refusal (`:seon.program/read-bound` in
`:maps`), and with the defaults answers the comparison. Existing `seon.cluster.source-test` and `my.program-test`
diff tests stay green.

`--test seon.cluster.source-test --test my.program-test --test seon.config-defaults-test`

## Cost

D: three map lookups per comparison.

## Commit

`bin/lane commit <lane> -m "DIAL-8: merge comparison bounds and index page bounds are dials" --test seon.cluster.source-test --test my.program-test --test seon.config-defaults-test resources/seon/schemas/seon.config.db.edn resources/seon/schemas/seon.program.edn src/seon/cluster/source.clj src/seon/program.cljc src/my/program.clj test/seon/cluster/source_test.clj`

## Dependencies

dials-16gb holds `seon.config.db.edn`; led-nk1 holds `src/my/program.clj` and `seon.program.edn`; the MA rows rewrite
`source.clj` (land before them or inside the first). DIAL-13 is merged here.
