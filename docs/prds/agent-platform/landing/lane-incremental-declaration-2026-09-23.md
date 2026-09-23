---
type: evidence
status: committed (fix schedule #24r)
created: 2026-09-23
---

# Lane incremental-declaration (fix schedule #24r), 2026-09-23

Opus 5.5 lane. Owned: `src/seon/schema.clj`, `test/seon/schema_test.clj`, this
note, and the new issue note
`docs/seon/issues/projection-replacement-generations-retain-replaced-compiled-schemas.md`.

## Defect

A new declaration re-derived the whole projection: `load-projection` always
ran `build-projection` over every form and contract (default, pre-move pid
9104: 424-459 ms over 3,350 schemas + 1,925 contracts; armed pid 24492:
944 ms median). README §1 asks for updating only changed declarations and
their dependency closure.

## Seams used

Malli `25710a67` (`reference-code/malli/src/malli/registry.cljc`):
`lazy-registry` (:81-95) compiles a definition when it is first looked up and
keeps it in `cache*`. `composite-registry` (:54-59) is the lookup order.
`fast-registry` (:17-22) seals the table and returns its Clojure map from
`-schemas`. `seon.schema/projection-registry` already accepted a `retained`
map of compiled objects and compiled only the others. What was missing was the
set of changed declarations and every schema or contract that refers to them,
plus a replacement of the indexes.

## Change (commit `1625fb9bc`)

- `projection-with-declarations` (private, one mechanism). The changed
  declarations are the forms, contracts and admissions that differ from the
  base. Because unchanged members are the same objects, the comparison is
  mostly `identical?`. The reverse closure comes from `dependent-schema-keys`
  over the replaced reverse edges plus `function-dependents-of`. Everything
  outside the closure is kept as Malli's own object. The closure is validated
  by the same validator as the whole build. Schema and function dependency
  edges, shape rows, `required-by-key`, `shape-index` and `catalog`
  (`shape-projections-with`) and the fingerprint (`fingerprint-with`) are each
  replaced one entry at a time.
- Predicate bindings match the population's exactly. When a replaced or
  removed definition was the only one naming a bound symbol, one population
  scan runs to decide whether to drop it; otherwise no scan runs.
- A new cycle is refused with the same path a whole build reports: the search
  reruns from every root, and this costs time only when a cycle is found. A
  changed unqualified (bootstrap) key goes through the whole build.
- `validate-declarations!` was extracted from `build-projection`. It is one
  batch sharing one reference walk per declaration and role. Before this, the
  replacement path used a per-identity walk with no sharing: an agent
  readmission of `:seon.test/error` took 19,213 ms, and now takes 41 ms.
- `build-projection` handed a complete projection
  (`incremental-base?`) derives from it. `projection-with-schema`,
  `projection-without-schema` and `projection-with-function-contract` are now
  calls to that derivation, and their bespoke bodies are deleted. Also deleted:
  `validate-one-contract!`, `schemas-rendered-by` and
  `predicate-functions-with`. `direct-reference-keys-in` takes the refusing
  operation as an argument.
- `load-projection` has a second arity, `[db base]`. `projection-from-rows`
  reuses the base's parsed form when a row's text equals the text the base
  was read from. That text is `:seon.schema.projection/definition-strings`, a
  runtime member listed in `projection-runtime-keys`. Only
  `projection-from-rows` writes it, and every other constructor drops it, so it
  can never describe forms it was not read from. A complete base no longer
  triggers the full fingerprint (96 ms) just to test for equality: an
  unchanged population returns the base itself.

## Evidence

Probe JVM (`clojure -M:test` with a socket REPL, pid of this lane's own
shell). The inputs are default's rows (pre-move pid 9104), exported read-only
to `tmp/lane-incdecl/rows.edn`: 3,350 schemas, 1,925 contracts, 4,874 source
rows. The canonical fixture requires a runner handle, so
`an-incremental-projection-equals-its-full-build` ran with `with-database`
replaced in the probe JVM only. The replacement is an in-memory Datahike
database holding those rows (`tmp/lane-incdecl/harness.clj`, `run-test.clj`).

1. Regression `seon.schema-test/an-incremental-projection-equals-its-full-build`
   gave pass 12, fail 0, error 0, in 2,301 ms. It checks that an unchanged
   population is its own derivation (`identical?`). It then checks that each
   of the following equals the whole build: a removed leaf, the new
   declaration back from that removal, a replaced referenced declaration with
   two dependents, a replaced contract, and an agent readmission. It checks
   that an undeclared reference and a cycle refuse with the whole build's
   message. It checks that a committed declaration loaded with
   `load-projection db prior` equals `load-projection db`. Against parent
   schema.clj the same test fails: pass 9, fail 1, error 1.
2. Random single-declaration probe (`tmp/lane-incdecl/f8.clj`, seed 20260923).
   45 cases: 8 new leaves, 8 removed leaves, 12 replaced keys (1-35
   dependents), 8 replaced contracts, 4 removed contracts, 1 added contract,
   4 agent readmissions. All 45 are `=` to the whole build on every member,
   with compiled forms compared by `m/form`. Derived 4-42 ms against whole
   builds of 201-390 ms.
3. Refusal parity (`f10.clj`): an undeclared reference, a cycle, a removed
   referenced key and an undeclared contract reference each refuse with the
   same message from both paths.
4. Surrounding tests (diagnostic harness, same replacement): all of
   `seon.schema-test` plus `schema-usage-guard`, `program`,
   `cluster.registry`, `error.refusal` and `schema.datahike` tests, 82 vars in
   total. Outcomes equal the parent's apart from the two new passes. The
   harness itself errors in many fixture-dependent tests, identically on
   parent and own. Two `schema.datahike-test` vars flip with harness state
   (fresh connection versus reused), independent of the code.
5. Contracts touched or added compile against the packaged projection
   (`schema/build-projection (schema.edn/packaged-forms)`, `contracts.clj`).
   All 16 compile: `direct-reference-keys-in`, `declaration-changes`,
   `set-changes`, `fingerprint-with`, `shape-index-attributes`,
   `shape-projections-with`, `incremental-base?`, `population-options`,
   `projection-with-declarations`, `validate-declarations!`, `load-projection`,
   `projection-with-schema`, `projection-without-schema`,
   `projection-with-function-contract`, `projection-from-rows`,
   `build-projection`.

### Hot path: parent versus own, same probe, same JVM (`timing.clj`, median of 5)

| operation | parent `6e3fe5cce` | own `1625fb9bc` |
|---|---|---|
| `build-projection`, whole population | 322 ms | 288 ms |
| `load-projection db-with-new-declaration` | 357 ms | 322 ms |
| `load-projection db-with-new-declaration prior` | (no arity) | 36 ms |
| `projection-with-schema`, new declaration | 18.5 ms | 6.5 ms |
| `projection-with-function-contract` | 11.5 ms | 11.9 ms |
| `projection-without-schema`, leaf | 268 ms | 5.4 ms |

On the armed default (new pid 24492, parent code, read-only probe):
`load-projection` of a new declaration took 944 ms, `projection-with-schema`
21 ms and `projection-with-function-contract` 12 ms. Own code cannot run
there until it is adopted. Default must not be redefined.

The derived `load-projection` breakdown (36 ms) is: rows 10 ms, admissions
3 ms, and 22 ms for the per-row parse-or-reuse and map passes plus the
derivation. That part is proportional to the rows read (10,149), because
Datahike names no per-identity difference against an arbitrary base.

Memory (`mem.clj`, `mem2.clj`): a whole build retains 16.3 MB. Each
replacement generation retains about 190 KB more, the same on parent and own
(filed in the issue note above).

## Needed outside this lane

`seon.db` (`src/seon/db.clj`, free in the ledger, not mine) still calls
`(schema/load-projection database)` at a content miss (`content-projection`,
db.clj:1325-1335). To make the committed-declaration path proportional it
should pass a base, for example any realized entry of its own
`projection-cache`:
`(schema/load-projection database (or (some->> @projection-cache vals (filter realized?) first deref) {}))`.
Any base gives the same result, and a near base makes it fast. Until then,
`transact!` of a declaration still derives the whole projection. After
adoption, the canonical proof is
`bin/test-check --test seon.schema-test/an-incremental-projection-equals-its-full-build`
and `bin/test-check --ns seon.schema-test`. Neither has run: default runs
parent code.

## TIMINGS (operations over 1 s)

| operation | wall ms | justification / status |
|---|---|---|
| probe JVM start + `require seon.schema` | 24,717 | DEFECT >10 s, existing class `docs/seon/issues/a-focused-test-jvm-spends-thirty-seconds-before-its-first-test.md` (whole-tree compile) |
| first packaged `build-projection` in that JVM | 15,963 | DEFECT >10 s, same class (cold JIT and first compile of every schema); warm 240 ms |
| 82-var diagnostic harness run | 42,161 | DEFECT >10 s as one invocation; it is 82 test bodies with about 60 whole builds, a diagnostic only, never a product operation |
| `seon.schema-test` harness run (sum of bodies) | 15,992 | same, one namespace of bodies |
| regression `an-incremental-projection-equals-its-full-build` | 2,301 | six whole builds (about 300 ms each) to compare against, one of which also loads |
| harness Datahike database build | 422-1,220 | transacts 10,149 rows |
| replacement-chain memory probe (50 generations) | 890 | 50 derivations at about 18 ms |
| parent `readmit-agent` before the shared validator (first draft) | 19,213 | fixed in this commit (41 ms) |

Cache: none added. The derivation reuses Malli's compiled objects held by the
supplied projection, and the text reuse is the projection's own input.

## Limits

- The canonical runner has not executed the regression. Default runs parent
  code, and this lane may not adopt or redefine it.
- The live `transact!` path is unchanged until `seon.db` passes a base (above).
- A replacement chain's memory grows about 190 KB per generation (issue note).

RESET NEEDED: no.
