---
type: design
date: 2026-09-27
author: defn-scan-opus
status: for Astra review; NOT implemented (owner: understand before changing; several files are held)
ruling: root 2026-09-27, option 1 of docs/research/agent-platform/landing/defn-scan-opus-2026-09-26.md
task: 12913e5cd6b0 a-function-contract-changes-the-schema-projection (resources/seon/tasks/seon.schema.edn; subject mark on seon.schema/projection-ranges lands with S1)
---

# Function contracts leave the schema projection

**What exists.** The projection (`seon.schema`) is built from two row ranges: schema rows and function-contract rows
(`schema.clj:3057` `projection-ranges`). A contract is compiled into the same lazy registry as the data shapes
(`projection-registry`, schema.clj:524-580, with `m/function-schema` for a symbol identity). The projection also
indexes `function-dependencies` / `reverse-function-dependencies` and fingerprints `:function-contracts` and
`:function-admissions`.

**The data flow today.**
1. A contracted defn writes `:seon.fn/spec`.
2. `seon.db/projection-cache-key` (db.clj:1303), which is keyed on `schema/projection-attributes`, changes.
3. `carried-projection` misses and runs `load-projection`.
4. A new projection value is built, with an empty `:compiled` holder.
5. Every derivation keyed on the projection walks the ~3,550 forms again.

**Measured on 2026-09-26** (pid 89341, quiet machine):

| form | p50 | allocated | armed calls |
|---|---:|---:|---:|
| plain `(defn f [x] (inc x))` | 134 ms | 157 MB | 26k |
| the same defn with `{:malli/schema [:=> [:cat :int] :int]}` | 757 ms | 1.21 GB | 190k |

**Principal approach.** The projection is the data shapes and nothing else. A contract is a value that is compiled on
demand *against* the unchanged projection, through Malli's own `m/schema` with the projection's registry, and cached
per contract.

- The readers take the contract form from the function row or the Var they already hold.
- A schema change finds the contracts it could break with one index query and compiles only those.

What disappears:
- the function half of the projection: the registry entries, the two dependency indices, two fingerprint members,
  the contract range and its parse, and the function branch of `validate-declarations!`;
- `projection-with-function-contract`.

## 1. The compile seam and its cache key

One new function in `seon.schema` (about 25 lines):

```clojure
(function-contract projection function-symbol form admission)
;; => {:seon.schema/function-symbol sym :seon.schema/form form
;;     :seon.schema/compiled <m/function-schema> :seon.schema/arities [...]}
;;    or the declared :seon.schema/validation-refusal
```

**Body.** Four steps, all reusing code that exists today:
1. `compilable-form form (predicate-functions-in projection)`. This is today's `bound-forms` for one definition,
   extended with `runtime-predicate` for predicates the population does not bind; that is what
   `instrument/bind-contract-predicates` does today.
2. `m/function-schema` with `{:registry (:seon.schema.projection/registry projection)}`. These are exactly the
   options `projection-registry` passes its lazy scope (schema.clj:560-565).
3. `refuse-unresolved-reference!` on failure.
4. The contract-validation request that `validate-declarations!` builds today for one symbol (schema.clj:2082-2100).
   Its inputs are the base map (forms, compiled-schemas, dependencies, registry, canonical keys, schema admissions,
   pure predicate symbols, predicate bindings), plus the contract's own admission.

**Cache.** The cache is `schema/projection-cache-value` on the projection's `:compiled` holder, under the key
`[::function-contract function-symbol form admission-source]`.

The key is complete by construction. The holder belongs to one projection value (`with-compiled-cache`, installed
fresh at every construction and never inherited), and every other input of the compile is a member of that value:
- forms and registry;
- predicate bindings (`:seon.schema.projection/predicate-functions`);
- schema admissions;
- pure predicate symbols;
- artifact exports.

Those are exactly the members `build-projection`'s constructor memo keys on (schema.clj:2340
`[forms function-contracts options]`, where the options carry admissions, exports, pure predicates and predicate
bindings). They are not a fingerprint. The key names the rest:
- the form: the authored EDN, compared by value;
- the admission source: the one role-sensitive rule (`internal/assert-complete-schema!` for an agent's nilable
  output).

A predicate added by `runtime-predicate` resolves to its Var, so redefining the predicate takes effect, and the Var
object itself is fixed for the process.

**Why the holder and not the process cache.** The ruling said `seon.cache` keyed by the form and the population
identity. The projection holder is that key: one holder per population value. Because a defn no longer changes the
projection, the holder persists across defns and every later lookup hits.

A process-cache key would have to name the registry object or restate the six population members above.
- Restating the members is the incomplete-key risk Astra rejected.
- Naming the registry object would pin old registries (MBs of compiled schemas each) in the byte budget after their
  projection is gone.

Branches with an equal population already share one projection value, because `carried-projection` keys content
(db.clj:1545-1640). So the holder is shared across branches too.

- If Astra prefers `seon.cache`: key `[::function-contract (:seon.schema.projection/registry projection)
  (predicate-functions-in projection) (:seon.schema.projection/schema-admissions projection)
  (:seon.schema.projection/pure-predicate-symbols projection) form admission-source]`, with weight
  `(* 4096 arity-count)`. It is complete for the same reason, and it pays the retention described above.

**Variant compile options.** `instrument/compiled-wrapper` and `contract-definitions` compile with
`(mr/composite-registry registry (mr/var-registry))` (instrument.clj:607-609, 706-709). That is a second option set,
so it is a second key: `[::function-contract-armed sym form admission-source]`, or a third element naming the
options. It is never the same key with different options.

**Reading a contract form by symbol.** `seon.program/declared-contract [database function-symbol]` answers
`:seon.fn/spec` read as EDN. It is one `:avet :seon.fn/sym` datom plus one `:eavt` read. The parse is cached with
`seon.cache/lookup ::contract-form spec-string` (content key: the string). It lives in `seon.program` rather than
`seon.schema`, because schema reads no program rows.

## 2. The 15 external readers: what each switches to

Line numbers are at HEAD `a8487b321`.

| # | site | reads today | switches to |
|---|---|---|---|
| 1 | instrument.clj:596-609 `compiled-wrapper` | `(get function-contracts sym authored)`, then the registry entry, else its own bound compile | `authored` (the Var's `:malli/schema`, which `arm-var!` already has), compiled by `schema/function-contract` with the armed option set. Deletes the retained/bound fork (about 10 lines). |
| 2 | instrument.clj:729 `current-wrapper?` | projection contract or authored | `authored`: the wrapper compares its recorded definitions with the ones this contract closes over. |
| 3 | instrument.clj:769 `arm-var!` | same | `authored` |
| 4 | call_preparation.clj:159-169 `contract-arities` (a table over every contract) | registry per symbol | Per symbol: `[::contract-arities sym]` = `arity-facts` of `(function-contract projection sym (program/declared-contract database sym) …)`. Its two callers already name the symbol: `snapshot` (:300, which needs the suppliers' arities, so the table becomes a `keep` over the supplier symbols) and `planned-arities` (:619). The whole-table walk disappears. |
| 5 | render.clj:476 `head-function` | contract present? | `(program/declared-contract database sym)` present. render holds the database in its request. |
| 6 | render.clj:890, 915 `call-cache-evidence` | `select-keys projection [forms function-contracts]` | `forms` only, plus `::contract` = the selected function's form. The retained-call evidence names what the call used. |
| 7 | render/web.clj:965 `debug-renderer-contract` | `[:seon.render/projection :function-contracts producer]` in the call entry | `::contract` from #6 |
| 8 | test.clj:2154-2155 `arm-first-loads!` | two projections' whole contract maps compared over the fresh namespaces | `:seon.fn/spec` datoms of the fresh namespaces' functions in both databases (`:avet :seon.fn/ns` per fresh namespace), proportional to the fresh namespaces |
| 9 | fn.clj:3264-3281 `incremental-projection` (publication) | merges the prior and authored contracts into `build-projection` | Builds from forms only. Each authored contract is admitted with `function-contract` against that projection, and a refusal is the row's refusal, as today. |
| 10 | fn.clj:3089-3104 index projection | `build-projection schema-forms function-contracts` | `build-projection schema-forms`, plus #9's per-contract admission. The contract-facts step (`program/with-contract-facts`) takes `function-contract`'s compiled value. |
| 11 | turn.clj:1350 `row-tx` | `projection-with-function-contract current-projection …` for validation | `function-contract current-projection sym form admission`, a refusal or nothing. `current-projection` is unchanged. |
| 12 | turn.clj:1274 `row-tx` | `projection-without-schema` (uses `reverse-function-dependencies`) | Unchanged call; its blockers come from §3. |
| 13 | sci/eval.clj:943 `definition-row` | `projection-with-function-contract` (validation) | `function-contract` |
| 14 | sci/eval.clj:2057, 4808 `install-evaluated-rows!`, `install-candidate-function!` | `next-projection (projection-with-function-contract …)` | `function-contract` for admission. `next-projection` = `projection`, and `install-function-contract!` (:1347) is unchanged apart from receiving the same projection. |
| 15 | sci/eval.clj:2053, 3814, 4744 | `projection-without-schema` | Unchanged call; blockers from §3. |

Indirect readers through schema.clj's public API change their answers' source, not their callers:
- `function-matching-outputs-in`, `function-accepts-in?`, `function-returns-in?`, `function-accepts-and-returns-in?`
  (render.clj:279, 525-526, 676, 1239; effect.clj:175) read `function-arities-in`, which switches in §4. These
  public functions gain a `database` argument, or a supplied contract form, because the projection no longer holds
  the form. Their 7 call sites all hold a database; that is a mechanical sweep.
- render.clj:416 `output-alternatives` (registry by symbol) switches to `function-contract`.
- error.clj:1750 `declared-output-validators` already compiles the authored form against the projection and needs
  no change. It is the pattern this design generalizes.

## 3. The schema-change refusal

Today a schema change recompiles `function-dependents-of` its reverse closure (schema.clj:2388, 2665-2670), and
`schema-removal-blockers` (schema.clj:929-950) reads `:function-dependencies`.

**After.** One query over stored contract facts. `program/with-contract-facts` writes each arity's direct canonical
references as indexed sets: `:seon.fn.arity/input-refs`, `:output-refs` and `:guard-refs`
(seon.fn.arity.edn:2-10, all `:seon.db/index true`; producer program.cljc:1035-1037, `schema/compiled-references`).

```clojure
[:find [?sym ...] :in $ [?key ...]
 :where (or [?arity :seon.fn.arity/input-refs ?key]
            [?arity :seon.fn.arity/output-refs ?key]
            [?arity :seon.fn.arity/guard-refs ?key])
        [?fn :seon.fn/arities ?arity] [?fn :seon.fn/sym ?sym]]
```

- `?key` ranges over the changed and removed keys plus their reverse schema closure
  (`dependent-schema-keys`, which the projection keeps).
- Cost: one AVET probe per key, plus the referencing functions.
- For each symbol found, `function-contract` compiles against the *candidate* projection. The first refusal is the
  refusal, as today.
- `schema-removal-blockers` takes the database and answers its function blockers from the same query.

This moves the refusal from the projection builder to its two write-path callers: `seon.turn/row-tx` and the
sci/eval admission, which both hold the database.

**Open item for Astra.** Are contract facts written for every contracted function, including file-indexed core
functions? `fn.clj` indexing calls `with-contract-facts`; this must be verified at the REPL in slice S3 before the
index replaces the projection's dependency map. If some rows lack facts, S3 stops.

## 4. schema.clj: what is deleted or changed

Line numbers are at HEAD. Sizes are counted from source, ±20%.

| region | change | lines |
|---|---|---:|
| 3057-3061 `projection-ranges` | drop `:function-contract-rows`. `projection-attributes` (3063) then loses `:seon.fn/sym` and `:seon.fn/spec`. | −1 |
| 2796-2809 `projection-admissions` | only `:seon.schema/key` | −1 |
| 2845-2940 `projection-from-rows` | contract parse (the second `parse-rows`, identity checks), `function-admissions`, contract strings | −35 |
| 2811-2840 `deleted-predicate-functions` | forms only; plus walking only re-read forms (the prototype in `tmp/defn-scan-opus/projection-derivation-mine.patch`) | −3 / +15 |
| 524-580 `projection-registry` | the `contracts` parameter and the symbol branch of the lazy scope | −10 |
| 843-870, 2344-2350 fingerprint | `:function-contracts` and `:function-admissions` members; the version goes 3 → 4 (one rebuild per store) | −6 |
| 2005-2105 `validate-declarations!` | the function-symbol request branch (2082-2100) moves into `function-contract` | −20 / (+moved) |
| 2126-2295 `projection-build` | `compiled-function-contracts`, `function-dependencies`, `reverse-function-dependencies`, the `:function-*` members | −35 |
| 2549-2780 `projection-with-declarations` | `changed-fns`, `removed-fns`, function admissions, `affected-fns`, function dependency maintenance, the function keys in the candidate | −55 |
| 2513 `incremental-projection-keys` | the function index keys | −3 |
| 2388 `function-dependents-of` | deleted | −8 |
| 929-950 `schema-removal-blockers` | function blockers from §3's query (database argument) | −8 / +6 |
| 1883-1960 render contracts (`render-contract-observation`, `assert-render-contracts!`) | the renderer's contract comes from a supplied `:seon.schema/contract-of` (sym → form); callers hold rows or the database | −4 / +6 |
| 3436-3450 `projection-with-function-contract` | deleted (callers in §2) | −15 |
| 3686-3694 `function-arities-in` | `compiled-function-arities` of `function-contract` | ±0 |
| new `function-contract` | §1 | +25 |

**schema.clj net:** about −200 / +55. **Elsewhere:**
- instrument: −10;
- call_preparation: −3 / +4;
- program.cljc: +12 (`declared-contract`);
- the 15 readers: about ±30, mostly one-line swaps.

**Total:** roughly −250 / +100. The added lines are paid for by the deleted function half of the projection.

## 5. Slices (HEAD loads after each), each with its regression

- **S1, additive (schema.clj, program.cljc).**
  - Adds `schema/function-contract` and `program/declared-contract`. Nothing calls them yet.
  - Regression `a-contract-compiles-against-the-projection-as-the-registry-did`: for every contract in a built
    projection, the compiled value's arity facts equal the registry entry's.
  - Regression: an unbound predicate compiles to its Var.
  - Regression: the agent-nilable-output refusal matches `validate-declarations!`'s refusal.
- **S2, readers (§2 #1-#11, #13-#14; owners: instrument, call_preparation, render, web, test, fn, turn, sci/eval).**
  - Every reader switches to S1's seam. The projection still holds contracts, so answers are unchanged.
  - Regression per reader class: the reader answers for a contract **absent from the projection**. The test builds
    the projection without the function and hands the form. This proves the reader no longer reads the projection.
  - Existing suites: `seon.instrument-test`, `seon.call-preparation-test`, `seon.render-test`,
    `seon.cluster.program-change-test`.
- **S3, the refusal (schema.clj, turn.clj, sci/eval.clj).**
  - `schema-removal-blockers` and the replacement's contract recheck read §3's query.
  - REPL first: every stored contracted function has arity facts (§3 open item).
  - Regression: changing or removing a schema that a stored contract references refuses and names the function.
    `seon.schema-test` has the projection-based versions, which are rewritten over a database world built by the
    canonical writers.
- **S4, the cut (schema.clj, fingerprint v4).**
  - Drops the contract range and the function half (§4). `db.clj`'s `projection-attributes` shrinks by
    construction, with no db.clj edit.
  - Class regression `a-function-contract-never-changes-the-projection`: after a contracted defn through the
    agent's path, `(identical? (db/carried-projection before) (db/carried-projection after))` holds, and
    `load-projection` did not run (profile growth 0).
  - `an-incremental-projection-equals-its-full-build` is kept over forms only.
  - Root runs `seon.schema-test`, `seon.program-test`, `seon.cluster.evaluate-source-test` and `seon.fn-test` after
    the restart.

Each slice commits alone. S2 touches held files, so it goes to their owners as patches, or waits for them.

## 6. Expected cost per contracted defn after S4

**Removed:**
- `load-projection`: 1.75 per defn × 36-50 ms and about 94 MB;
- `content-projection` keys: 5.75 per defn;
- first-call table re-derivations: up to about 105 ms per new projection (measured 2026-09-26:
  `declared-attributes` 43 ms, `observation-selector` 30, `program-attributes` 9.6, `write-entity-schemas` 7.2,
  `write-attribute-plans` 5.0, `contract-arities` 4.5, and others).

**Remaining work that belongs to the contract:**
- one `function-contract` compile (a single `m/function-schema` over a retained registry, well under 1 ms for
  `[:=> [:cat :int] :int]`);
- `with-contract-facts`;
- arming (`wrap-interpreted`);
- the arity comparison (`report-arity-comparison`, about 77 ms per defn measured; a separate task if it stays).

**Expected:**
- about 150-250 ms p50, against 757 ms today and 134 ms for a plain defn;
- about 200 MB, against 1.21 GB;
- about 30-40k armed calls, against 190k;
- `source-family?` 0 per defn after `9b6df1ff4` (the shapes are cached on the unchanged projection).

These are projections from the measured components; the S4 probe (`tmp/defn-scan-opus/probe.clj`, `sym-counts`) is
the number that counts.

## 7. Risks and what this gives up

- **The projection no longer answers "which functions break if this schema changes".** It becomes §3's query at the
  two write paths.
  - Risk: a writer that changes schemas without going through them.
  - Mitigation: `seon.db`'s write-report validation already judges schema-row writes by the installed population
    (db.clj:4480 `validate-write-report`). S3 adds the contract recheck there if Astra finds a third path.
- **Render-contract assertion at build time needs the renderers' contracts.** Build callers without a database (the
  boot/index path, fn.clj:3103) pass `:seon.schema/contract-of` built from their rows, which fn.clj already reads.
- **Fingerprint v4 forces one rebuild per store at adoption.** This is expected, and it happens once.
