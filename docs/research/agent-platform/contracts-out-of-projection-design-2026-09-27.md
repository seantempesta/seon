---
type: design
date: 2026-09-27
author: defn-scan-opus
status: revision 2, for re-review; NOT implemented (owner: understand before changing; several files are held)
ruling: root 2026-09-27, option 1 of docs/research/agent-platform/landing/defn-scan-opus-2026-09-26.md
review: docs/research/agent-platform/review-contracts-out-of-projection-2026-09-27.md (Astra, REVISE, R1-R7), all addressed below
task: 12913e5cd6b0 a-function-contract-changes-the-schema-projection (resources/seon/tasks/seon.schema.edn; the subject mark on seon.schema/projection-ranges lands with S1)
---

# Function contracts leave the schema projection

## What exists

The projection (`seon.schema`) is built from two row ranges, schema rows and function-contract rows
(`schema.clj:3057` `projection-ranges`; `resources/seon/schemas/seon.schema.edn:58-63` declares both in
`:seon.schema/projection-input`).

A contract is compiled into the same lazy registry as the data shapes (`projection-registry`, schema.clj:524-581,
using `m/function-schema` for a symbol identity). The projection also holds:
- `function-dependencies` and `reverse-function-dependencies` indices;
- fingerprint members for `:function-contracts` and `:function-admissions`.

The final writer's projection acquisition (`db.clj:4481-4503` `validate-write-report` → `carried-projection` →
`load-projection`) is where contracts are compiled and where renderer coherence is checked
(`schema.clj:2998-3013`, 2763-2773).

## What a contracted defn costs today (honest version)

Astra's installed trace of one first contracted defn (`eval-trace-2026-09-26.md` lines 32-38, 262-264, 330-334)
attributes about **100 ms** to projection work:
- **70 ms** in two `load-projection` calls, of which 35 ms is the whole-population deleted-predicate scan;
- **33 ms** in six `projection-with-function-contract` calls;
- `projection-build` itself is 13.8 ms.

These costs overlap each other.

My earlier contrast measured two different forms on pid 89341, p50 134 ms against 757 ms, 157 MB against 1.21 GB,
and 26k against 190k armed calls. That contrast is **not** the projection's cost: it also contains every
contract-specific stage (arming, contract facts, arity comparison, gates). The design therefore promises only this:
- the projection work above is removed;
- first-use table re-derivations are removed when a new projection would have been built (up to about 105 ms of
  first calls measured 2026-09-26: `declared-attributes` 43, `observation-selector` 30, `program-attributes` 9.6,
  `write-entity-schemas` 7.2, `write-attribute-plans` 5.0, `contract-arities` 4.5);
- all totals are measured by the S4 replay (§8), never subtracted.

## Principal approach

The projection holds the data shapes and nothing else. One pure seam compiles an explicitly supplied contract
against an explicitly supplied shape projection, and admits it. Its compiled value is passed on to arming,
supplied-default planning and contract-fact derivation. Contract products that need reuse live in the central
bounded cache (`seon.cache`) under a complete key of resolved inputs.

One final-writer checker owns the relations between programs and schemas:
- contract compile and admission for changed contracts;
- contracts affected by schema changes;
- renderer coherence;
- arity-fact integrity.

Early admission entrances call the same checker with candidate values.

What disappears:
- the function half of the projection (registry entries, dependency indices, fingerprint members, the contract
  range, its row parsing and resource member);
- `projection-with-function-contract`;
- the per-projection deleted-predicate walk over contracts.

## 1. The seam: `seon.schema/function-contract`

```clojure
(function-contract projection {:seon.schema/function-symbol sym
                               :seon.schema/form form               ; canonical EDN, as stored in :seon.fn/spec
                               :seon.schema/admission admission     ; {:seon.schema.admission/source :agent|:core}
                               :seon.schema/mode :shape|:armed})
;; => {:seon.schema/compiled <Malli function schema> :seon.schema/arities <arity facts>
;;     :seon.schema/references <per-arity input/output/guard refs>}
;;    or :seon.schema/validation-refusal
```

**Body.** Steps 1-3 are today's code, moved:
1. Resolve predicates. Today's instrument transformation `bind-contract-predicates` (instrument.clj:428-457, which
   includes `:error/fn` overrides) becomes the seam's own binding step. It uses `predicate-functions-in projection`
   first, then `runtime-predicate` for symbols the population does not bind. `compilable-form` alone does not
   perform the `:error/fn` rewrite (review R3.4), so it is not used by itself.
2. Compile with `m/function-schema form {:registry R}` (malli core.cljc:3130-3134). **The accepted option set is
   exactly `{:registry R}`**:
   - `:shape` mode: `R` is the projection registry;
   - `:armed` mode: `R` is `(mr/composite-registry projection-registry (mr/var-registry))`, as instrument.clj:607-609
     does today.

   No other Malli option is accepted (`::disable-sci`, `::sci-options`, `::function-checker`,
   core.cljc:2145-2173, 2934-2956); a caller supplying one is refused by name.
3. Admit. This is the one-symbol validation request `validate-declarations!` builds today (schema.clj:2082-2100)
   with the admission from the caller. It reuses a population-level validation base derived once per projection
   (`projection-cache-value ::contract-validation-base`, holding forms, compiled schemas, dependencies, canonical
   keys, admissions, purity and `core-references?`). It never rebuilds that base per contract (R7).
4. Derive arity facts (`call-preparation/arity-facts`) and references (§4) from the compiled value once. The
   callers pass these on; `program/contract-facts` stops compiling again (program.cljc:1096-1099).

**Cache.** The cache is `seon.cache/lookup ::function-contract key weigh derive`; it is bounded, LRU, and weighted
under the one byte dial. The weight is `(* 8192 (count arities))`, a stated estimate to be measured in S1.

The key is `[mode form admission-source population bindings var-schemas]`:
- `form`: the canonical EDN, compared by value.
- `admission-source`: `:agent`/`:core`. It is the only function-admission field the checker reads (schema.clj:1470-1650).
- `population`: `[(:seon.schema.projection/forms p) (:seon.schema.projection/schema-admissions p)
  (:seon.schema.projection/pure-predicate-symbols p)]`. These are the shape constructor's own inputs
  (build-projection's memo, schema.clj:2340), compared by value. Maps unchanged across projections keep their
  identity, so equality short-circuits, and each new map hashes once.
- `bindings`: the **resolved** predicate bindings the form names, a sorted map from symbol to callable, including
  `:error/fn` targets. A Var is its own identity; redefining its root takes effect live, which is the declared
  semantics.
  - `ns-unmap` and recreation give a new Var object, so the key changes (R3.2).
  - An explicit branch callable is a distinct object, so the key is distinct.
  - An unresolved symbol is never cached: the compile refuses and nothing is retained.
- `var-schemas`: `:armed` mode only. For every symbol in the form that the var-registry resolves, its dereferenced
  schema *value*, so a Var holding `:int` then `:string` yields two keys (R3.3). If a form names a Var-schema whose
  value is not EDN (a compiled schema object), the compile is **left uncached** and the counter
  `:seon.schema/uncached-var-schema` is shown in `seon.cache` status.

The projection holder is not used. Overlays made by `with-predicate-functions` share one holder with different
bindings (schema.clj:401-414; instrument_test.clj:1530-1539), and the holder is unbounded (schema.clj:332-381). This
closes R3.1 and the R3 retention finding.

Missing stored provenance keeps today's default, `:agent` (schema.clj:2869-2874). A malformed stored form is a
typed read refusal distinct from absence (R3).

**Reading a stored contract.** `seon.db/function-contract-row [database sym]` returns
`{:form :admission}`, `nil` when absent, or `db/read-refusal`:
- it is one `:avet :seon.fn/sym` read, then the entity's `:seon.fn/spec` and `:seon.schema.admission/source`;
- the parse is cached by spec string (`seon.cache/lookup ::contract-form`);
- it lives in `seon.db`, the database owner (review R6), and keeps the existing late-resolve direction toward
  `seon.program` (db.clj:4273-4281).

The pure seam in `seon.schema` takes no database.

## 2. Every reader, and what each switches to (line numbers at `d1f6a37fb`)

| # | reader | today | after |
|---|---|---|---|
| 1 | `instrument/compiled-wrapper` 596-609 | projection contract or authored; registry entry or its own bound compile | `authored` (Var `:malli/schema`) → `function-contract … :armed`. Deletes the retained/bound fork. |
| 2 | `instrument/current-wrapper?` 725-735 | projection contract | `authored` |
| 3 | `instrument/arm-var!` 769 | projection contract | `authored`; the compiled value from #1 is handed to `arm-plan` (#5) |
| 4 | **`instrument/apply!` 865-883** (R1) | admits a changed candidate only if `(mr/schema registry sym)` | admits a candidate whose authored `:malli/schema` compiles through the seam; a refusal leaves it on its original root, with today's diagnostic. Disarming a function with no admitted contract is kept. |
| 5 | **`call-preparation/arm-plan` 757-776** (R1) | registry entry by symbol | takes the **compiled contract** being armed (new argument), so a new SCI candidate is planned before any row exists |
| 6 | `call-preparation/contract-arities` 159-169, `snapshot` 300, `planned-arities` 619 | a whole table over the projection's contracts | `snapshot`: the suppliers' contracts via `db/function-contract-row` + seam, keyed per supplier by the seam's key. `planned-arities` takes the compiled contract. The table is deleted. |
| 7 | `call-preparation/snapshot-read-attributes` 359-378 (R2) | inherits `:seon.fn/spec` through `schema/projection-attributes` | declares its own contract reads: `:seon.fn/spec` and `:seon.schema.admission/source` join the explicit read set, so a supplier's spec-only change misses the revision key (403-419) and the report reuse (484-505) |
| 8 | **`fn/contract-findings` 2076-2109, 2190-2202** (R1) | compiled registry entry by symbol, holding the spec string | the held spec → seam |
| 9 | `fn` index 3078-3143, publication 3246-3277 | `build-projection forms contracts` | stays unchanged until S4; in S4, shapes only, with each authored contract admitted through the seam and its facts from the seam value |
| 10 | `turn/row-tx` 1258-1355 | `projection-with-function-contract` for validation | the final-writer checker's early call (§5) with the candidate rows |
| 11 | `sci/eval` 917-956, 2038-2074, 4802-4819 | `projection-with-function-contract`, next projection | seam and early checker; `next-projection` = `projection` in S4 only |
| 12 | `render/head-function` 471-477 | contract presence in the projection | `db/function-contract-row` presence; render holds the database in its request |
| 13 | `render/output-alternatives` 411-424 (R2) | `[::output-alternatives sym]` from the registry | from the compiled contract; the cache is the seam's (its key contains the form), with no symbol-only key |
| 14 | `render/call-cache-evidence` 863-919, `render/web` 960-969 | projection `:function-contracts` | the selected contract's form recorded in the evidence |
| 15 | `test/arm-first-loads!` 2132-2158 | whole contract maps of two projections | `:seon.fn/spec` datoms of the fresh namespaces' functions in both databases |
| 16 | public queries `function-matching-outputs-in`, `function-accepts-in?`, `function-returns-in?`, `function-accepts-and-returns-in?` (schema.clj:3683-3752) | `[::function-arities sym]` from the registry | take a compiled contract (or `nil`), with no symbol-only key (R2). Six call sites: render.clj:279, 525, 526, 676, 1239; effect.clj:175. `source-producer?` (render.clj:272-281) holds only projection, symbol and arguments, so the contract is threaded from its callers, which hold the request's database (R1). |
| 17 | `error/declared-output-validators` 1749-1772 | the explicit authored form | unchanged (already the pattern) |

Inside `schema.clj`, the whole dependency chain changes in S4 (§7): registry 524-581, fingerprint and population
options 2341-2349 and 2530-2544, predicate binding 2105-2121, validation and build 2002-2291, replacement
2510-2774, row parsing and deleted predicates 2793-3023, the three replacement APIs 3110-3133 and 3401-3448,
renderer coherence 1880-1965 (moved to §5), and cached arities 3683-3752 (#16).

## 3. Admission inputs of the projection (R7)

`projection-attributes` keeps `:seon.schema.admission/source` after the function range leaves (schema.clj:3063-3067).
Every program row carries it (program.cljc:1242-1243; fn.clj:3328-3329).

Probe 2026-09-27 on pid 17853: `datahike.api/with` of one entity carrying only that attribute changes
`projection-cache-key`; an unrelated attribute does not. So every new function row makes the revision key miss
until acquisition is scoped. (My 2026-09-26 plain-defn run counted 0 loads; I did not establish why. The S4 replay
counts it directly.)

**Fix, in db.clj and schema.clj.** `projection-admissions` and `declaration-content-key` read admission datoms only
for entities holding `:seon.schema/key`: a join over the schema rows, about 3.5k. The revision key still includes
the attribute, so every admission write is a *conservative revision miss*. The miss then computes the
schema-scoped content key (measured today at about 4 ms for the unscoped 40k datoms; the scoped read is smaller)
and hits the same projection. A real schema-admission change changes the scoped content and derives.

Expensive loads and cheap misses are counted separately in §8. A dedicated schema-admission attribute would make
the revision key exact. That is a later option priced in §9, not required.

## 4. References for the refusal query: repair the walker first (R4)

`compiled-references` (schema.clj:64-79) stops at a ref whose *name* is canonical without proving that it resolves
to the canonical declaration. Astra's counterexample, `[:schema {:registry {:review/a :review/b}} :review/a]`,
yields `#{:review/a}` although its meaning is `:review/b`. Malli composes local registries ahead of the supplied one
(core.cljc:2545-2554, registry.cljc:54-59).

**Fix at the owner.** A ref is recorded as canonical only when its scope resolves the name to the canonical
declaration: its `m/options` registry answers the same schema object as the canonical registry. Otherwise the walk
follows it. This is one predicate in the existing fold.

**Integrity.**
- Astra verified all 2,355 stored contracts' arity reference sets against recomputation at basis 536871395:
  0 missing, 0 mismatches, 407 ms.
- Going forward, the final writer (§5) recomputes a changed contract's arity facts from the seam value. A
  transaction whose supplied facts differ is refused, so facts cannot drift from specs. Missing facts on a
  contracted function are refused the same way.
- Backfill (fn.clj:3188-3198) adds the same equality check.

**Query.** Three reference-attribute branches with owner and symbol joins, deduplicated:

```clojure
[:find [?sym ...] :in $ [?key ...]
 :where (or [?a :seon.fn.arity/input-refs ?key] [?a :seon.fn.arity/output-refs ?key]
            [?a :seon.fn.arity/guard-refs ?key])
        [?f :seon.fn/arities ?a] [?f :seon.fn/sym ?sym]]
```

- Observed: 554 symbols for `:seon.db/database-value`, 8 ms.
- Priced as indexed reference lookups, plus joins, plus the affected contracts' compile through the seam. The
  installed plan is inspected in S3 before any tighter bound is stated.
- `?key`: the changed, removed and admission-changed schema keys (schema.clj:2670-2677), plus their reverse closure
  in **both** the before and the candidate shape projections.

## 5. One final-writer checker (R5)

The checker is `seon.db/program-relation-error`, called from `write-report-error` (db.clj:4403-4479) on
`:db-after`. It runs **only** when the report touches `:seon.fn/spec`, `:seon.fn/arities` facts, schema rows or
schema admissions; an unrelated data write performs none of it. It:
1. admits every changed contract through the seam against `:db-after`'s shape projection, and checks the arity-fact
   equality from §4;
2. selects the contracts affected by schema changes with §4's query and admits the **surviving** candidate contracts
   from final rows, so a repair or removal in the same transaction passes;
3. checks renderer coherence for every relation that changed on either side:
   - a schema whose `:seon.render/*` names a renderer;
   - a renderer whose contract changed or was removed;
   - `render-contract-observation` moves here from the cached shape constructor, with no callback in the
     constructor's options (R5).

Early entrances call the same function with candidate values: `turn/row-tx`, `sci/eval` admission, and
`fn` publication/index. There is one refusal policy.

## 6. Slices (HEAD loads after each; producers change last, R6)

- **S1, additive (schema.clj, db.clj, program.cljc).**
  - Adds the seam (§1), `db/function-contract-row`, the reference-walker fix (§4), and the scoped admission read
    (§3). Nothing else calls them.
  - Regressions:
    - the seam equals the registry compile for every stored contract (arity facts and references);
    - R3: overlays sharing a holder; same-Var root replacement vs `ns-unmap`/recreate; distinct branch bindings;
      armed Var-schema `:int`→`:string`; `:error/fn` parity; agent/core admission parity; deleted predicates in a
      historical contract keep today's diagnostic; many versions under one projection stay within the configured
      budget;
    - R4: input, output and guard refs, nested maps and unions, alias chains, local aliases, **the shadowing
      example**, local recursive registries, empty-ref contracts.
- **S2, every consumer (§2 #1-#17; owners: instrument, call_preparation, render, web, effect, test, fn, turn,
  sci/eval).**
  - Producers are unchanged, so the projection still holds contracts.
  - Regressions:
    - R1: from a projection with no function entries, incremental host arming still rejects bad input, a new SCI
      candidate receives omitted defaults before persistence, and contract findings inspect its declared slots;
      removal and disarming of a function without a contract are kept;
    - R2: same projection and symbol with two specs; absent then present; two branches with equal schemas and
      different contracts; historical reads after a newer lookup; a supplier spec-only change turning coherent to
      incoherent, refused by both snapshot refresh and `report-snapshot`; a default-row-only change re-plans
      without recompiling the contract.
- **S3, final-writer checker (§5; db.clj, turn.clj, sci/eval.clj, fn.clj).**
  - Early callers switch to it. The projection-side function checks stay as well, so both refuse identically
    (parity test).
  - Regressions:
    - a direct admitted write and a publication or merge schema change;
    - an invalid new spec without a schema edit;
    - a renderer spec that stops accepting its declared shape, and renderer contract removal;
    - same-transaction schema-plus-contract repair and removal; missing or stale arity facts;
    - an unrelated data write runs no check (counted).
- **S4, the cut: producers, range, APIs and resource together (schema.clj,
  `resources/seon/schemas/seon.schema.edn:58-63` `:function-contract-rows`, fn.clj index/publication, sci/eval
  next-projection, fingerprint v4).**
  - Every test naming removed APIs or maps converts in the same cut: split acquisition and nil-contract tests,
    publication, SCI, instrumentation, render coverage, and test-accretion fixtures.
  - Class regression `a-function-contract-never-changes-the-projection`, through the agent path:
    - define a fresh contracted symbol, replace its contract, change only its body, delete it, then repeat on a
      branch sharing the population;
    - each step leaves `carried-projection` identical;
    - counts `load-projection` = 0, content-key computations (cheap misses) reported, projection-keyed table
      derivations = 0, and seam compiles = 1 per new form per mode.
  - Root runs `seon.schema-test`, `seon.program-test`, `seon.instrument-test`, `seon.call-preparation-test`,
    `seon.render-test`, `seon.cluster.evaluate-source-test`, `seon.fn-test` and `seon.cluster.program-change-test`
    after the restart.

## 7. Size (recount after revision)

These are estimates from source, ±25%. The final count comes with the diffs.

| part | lines |
|---|---:|
| schema.clj function half: registry, indices, fingerprint, validation branch, replacement, row parsing, three replacement APIs, cached arities | −200 |
| schema.clj seam, references fix, scoped admissions | +70 |
| db.clj `function-contract-row`, `program-relation-error` (the renderer coherence and replacement recheck move here from schema.clj), scoped content key | +90 / −10 |
| instrument (#1-#4) | −15 / +10 |
| call_preparation (#5-#7) | −10 / +15 |
| fn, render, web, effect, test, turn, sci/eval (#8-#16) | ±40 |
| resource member | −2 |

**Net:** about −275 / +225. Most of the added lines are the final-writer checker, which replaces checks that
today run implicitly inside projection acquisition.

## 8. Expected benefit and the measurement that decides it (R7)

**Removed per contracted defn:**
- the projection work the installed trace measured (about 100 ms: loads 70, of which predicate scans 35; contract
  projection calls 33);
- first-use re-derivations of projection-keyed tables whenever a new projection would have been built.

**Added:** one seam compile per new contract form and mode, well under 1 ms for `[:=> [:cat :int] :int]` (to be
measured in S1), and the scoped content-key computation on a conservative revision miss (about 4 ms or less).

No end-to-end latency, allocation or call-count target is claimed. The S4 replay records:
- warm p50;
- allocation;
- armed calls;
- loads versus cheap misses;
- table derivations;
- seam compiles;
- retained memory under repeated versions.

It is recorded on parent and change with the same probe (`tmp/defn-scan-opus/probe.clj`) and workload.

## 9. What this gives up, and later options

- **The projection no longer answers which functions a schema change breaks.** The final-writer checker (§5)
  answers it with the query (§4) on every write that can break a contract.
- **Schema admission shares one attribute with program rows.** Every function write is a conservative revision miss
  costing a scoped content key. A dedicated schema-admission attribute would make it exact; that is a
  schema-resource change across the program-row writers, about half a day, and is not required for correctness.
- **Fingerprint v4 forces one rebuild per store at adoption.**
