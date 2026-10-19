---
title: "DIAL-18: Operator bounds are dials"
type: reference
status: waits on dials-16gb, DIAL-1
created: 2026-10-08
---

# DIAL-18: Operator bounds are dials

Ledger row DIAL-18 (group O). Source: `dials/dials.md` D13-D15. Model: Sonnet. Host-bound (the Babashka operator,
`script/`): throwaway check.

## Sites (verified at `1fcdbf47a`; `script/seon/operator.clj` is dirty, held by dials-16gb)

- `runtime-probe-bound-ms` 10000 (`script/seon/operator.clj:~479-481`) and `dependency-fill-bound-ms` 300000
  (`:~482-486`).
- `command!` bounds passed as literals: 30000 (`:830`, `:851`, `:926`, `:962`, `:1068`, `:1499`), 120000
  (`:837`, `:839`), 300000 (`:949`).

## Files

- `resources/seon/schemas/seon.config.operator.edn`
- `script/seon/operator.clj`
- `script/seon/operator_outcome_test.clj` or `test/seon/operator_test.clj` (where the operator's pure functions
  are tested today)

## Schema (in `seon.config.operator.edn`)

```clojure
:git-bound-ms [:int {:min 1 :seon.config/dial true :seon.config/default 30000 :description "Bound on one git metadata command (rev-parse, ls-tree)."}]
:archive-bound-ms [:int {:min 1 :seon.config/dial true :seon.config/default 120000 :description "Bound on writing or extracting one commit archive."}]
:dependency-bound-ms [:int {:min 1 :seon.config/dial true :seon.config/default 300000 :description "Bound on resolving and preparing dependencies (clojure -X:deps prep; the dependency class fill measured 111 s)."}]
:runtime-probe-bound-ms [:int {:min 1 :seon.config/dial true :seon.config/default 10000 :description "Bound on the java runtime probe (measured 28 ms)."}]
```

`dependency-fill-bound-ms` and the `prep` bound are the same kind of work: one dial (`:dependency-bound-ms`).

## Functions

The operator reads its configuration at bootstrap (DIAL-0 added the heap dial read; DIAL-1 makes it one Aero file):
read the four from the same map and pass them where the literals are. `command!` keeps its bound argument; its
callers pass the map's value. Delete the two `def`s. (The program class cache and its two dials went with lane class-cache, 2026-10-19.)

## Proof

New `seon.operator-test/operator-bounds-come-from-config`: the operator's bootstrap configuration read over a
fixture file setting `:seon.config.operator/git-bound-ms 1` answers 1 for that key and the declared defaults for
the other five (the pure read; the commands themselves are not run).

`--test seon.operator-test --test seon.config-defaults-test`

## Cost

D: none.

## Commit

`bin/lane commit <lane> -m "DIAL-18: operator bounds are dials" --test seon.operator-test --test seon.config-defaults-test resources/seon/schemas/seon.config.operator.edn script/seon/operator.clj test/seon/operator_test.clj`

## Dependencies

DIAL-0 (dials-16gb: `operator.clj`, `seon.config.operator.edn`), DIAL-1 (the one Aero file the operator reads).
