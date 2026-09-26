---
type: design
date: 2026-09-27
author: defn-scan-opus
status: revision 3 (Astra re-review N1-N5 of 3d48f13e8) + root retention ruling (§10), for re-review; NOT implemented (owner: understand before changing; several files are held)
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

## What a contracted defn costs today (two scopes, not to be added)

- **Direct projection calls: about 100 ms.** Astra's trace of one first contracted defn (pid 89341, archive
  `bb07fdf08`; `eval-trace-2026-09-26.md:32-45, 262-270`) measured:
  - 70.4 ms across two `load-projection` calls, of which 35 ms is the whole-population deleted-predicate scan;
  - 32.9 ms across six `projection-with-function-contract` calls;
  - `projection-build` (13.8 ms) nested inside those.
- **Broader affected work: an estimated 250-300 ms of a steady ~643 ms defn.** The later measurement
  (pid 17853, `15e6a31c4`; `landing/shrink-db-opus-2026-09-26.md:87-121`) attributes this cross-phase to three
  things:
  - candidate projection work;
  - writer acquisition and the tables it rebuilds;
  - post-commit installation and preparation.

  It is a causal estimate, not a sum of counters. Its phases include arity work and durability that stay.

My earlier 757 ms against 134 ms contrast measured two different forms, and every contract-specific stage is inside
it.

This design promises only the removal of projection churn: new projection values, their loads, and the first-use
re-derivation of projection-keyed tables. No fixed latency or saving is claimed. The §8 replay measures it on
parent and change.

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
(function-contract projection {:seon.schema/form form               ; the contract as authored
                               :seon.schema/admission admission     ; {:seon.schema.admission/source :agent|:core}
                               :seon.schema/mode :shape|:armed})
;; => {:seon.schema/compiled <Malli function schema>
;;     :seon.schema/references [[input-refs output-refs guard-refs] ...]}   ; per arity, §4
;;    or a refusal VALUE, never retained (below); the caller adds the function identity
```

The seam is **symbol-free** (N2): no function symbol enters it, its key or its retained value. A refusal names the
requested identity only after it leaves the seam, when the caller attaches the symbol. Stored arity rows (identity,
source bindings, signature matching) stay with `program/contract-facts` (program.cljc:1024-1123). That function now
*takes* the compiled contract instead of compiling again (program.cljc:1096-1099). Supplied-default descriptors stay
with `call-preparation`, which derives `arity-facts` from the compiled value (N5.2). `seon.schema` requires neither
namespace.

**Body.**
1. **Normalize, then bind (third review).** Preserve today's order at instrument.clj:600-604:
   - `schema/compilable-form` first, which widens component children and carries generator properties
     (schema.clj:274-324);
   - then `bind-contract-predicates`, including its `:error/fn` rewrite (instrument.clj:428-457).

   The binder moves into `seon.schema` in S1 and instrument calls it. Both steps use the **same resolved bindings
   that enter the key**: the held base's construction bindings, then the contract's own inner bindings.
2. **Compile against the entry's held base (T1), never the caller's registry.**
   - `:shape` mode: `R` is the held base registry.
   - `:armed` mode: `R` is `(mr/composite-registry base-registry (mr/var-registry))`.

   The seam passes `{:registry R}` and nothing else. A host compiled-schema object inside the form keeps its own
   options (malli `schema`, core.cljc:2605-2608). Such a form is not refused; it is compiled and **bypasses
   retention** (below).
3. **Admit.** Run the one-contract validation request `validate-declarations!` builds today (schema.clj:2082-2100).
   It reuses a validation base of *immutable population products only* (compiled forms, dependencies, canonical
   keys, admissions, purity, `core-references?`), derived once per population under the §1 cache. The
   per-operation `reference-advisories` memo (schema.clj:2033-2055) stays per operation and never enters a shared
   holder (N2).
4. **Derive references.** Compute the per-arity references with the scoped walker from §4.

**Cache (N1, N2, T1).** This is the population table of §10. The outer key is the base's **construction inputs**,
not projection members a caller can overlay.

- **Construction inputs.** The shape constructor records the inputs it actually compiled the registry with,
  `:seon.schema.projection/construction-inputs`:
  `[forms schema-admissions pure-predicate-symbols construction-bindings]`. Only `projection-build` and
  `projection-with-declarations` write it. `with-predicate-functions` (schema.clj:401-414) is an overlay that
  changes the binding *member* but never this record. The overlay's extra bindings are contract-level inputs and go
  into the inner key.
- **Witness T1, read-only on pid 25122** (`witnesses` in `tmp/defn-scan-opus/scoped-refs-probe.clj`):
  - base A has `review/p` = `int?`, and the overlay rebinds it to `string?`;
  - base B is built with `string?`;
  - the member keys are equal (`true`), so the old design would share an entry; the construction keys differ
    (`false`);
  - their validators differ as Astra showed: `[true false]` against `[false true]`.

  **Fixed by** keying on construction inputs.
- **The held base.** The outer entry holds one closed schema base: the registry, its canonical object map (§4) and
  the immutable validation base. Every inner miss compiles against that held base, so an independently rebuilt
  equal population shares it instead of adding a second registry to the table (T1 retention). The base is released
  only with the outer entry.
- **S1-S3 bridge (T1).** While producers still put contracts into the projection registry, a caller's registry
  retains every stored contract. Astra found 2,405 symbol entries reachable from the tiny `[:=> [:cat :int] :int]`.
  So **compilation is uncached until S4**: the seam compiles against the caller's projection and nothing is
  retained. Consumers keep the compiled value they were handed, as today.

  From S4 the projection is shape-only by construction, and the base is exactly that registry. An input whose
  provenance cannot be established (no construction-inputs record, such as a caller-assembled projection) is
  compiled uncached. **Fixed by** caching nothing before shape-only construction exists.
- **Inner key** `[mode form admission-source inner-bindings]`, all resolved before lookup:
  - `form`: the authored contract, by value.
  - `admission-source`: `:agent` or `:core`; missing stored provenance is `:agent` (schema.clj:2869-2874).
  - `inner-bindings`: the callables for the contract's own predicate and `:error/fn` symbols that the construction
    bindings do not cover, plus overlay rebindings.
    - A Var is its own identity, so root replacement stays live.
    - `ns-unmap` plus recreation, or a branch callable, is a new object and gives a new key.
    - Captured callables are retained; their accounting is in §10.
- **Never retained:**
  - a refusal of any cause;
  - a form holding any non-EDN value (a Var, a compiled schema, a function object), which is host-only, since
    stored and agent contracts are EDN;
  - a product over the slot charge (§10).

  Bypass and inner compiles are counted by probe-local counters in the S1 measurement script, not by a cache API.
- **Nothing environmental in a product.** An inner product retains no projection, database, source row, wrapper,
  table handle or advisory memo, only the compiled contract and its reference sets.

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
| 10 | `turn/row-tx` 1258-1355 | `projection-with-function-contract` for validation | S3: the final-writer checker's early decision call (§5) with the candidate rows; unchanged in S2 |
| 11 | `sci/eval` 917-956, 2038-2074, 4802-4819 | `projection-with-function-contract`, next projection | S2: seam for compile; S3: early checker decision; `next-projection` = `projection` in S4 only |
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

## 3. Admission inputs of the projection (R7; activation moved to S4 per N5.1)

`projection-attributes` keeps `:seon.schema.admission/source` after the function range leaves (schema.clj:3063-3067).
Every program row carries it (program.cljc:1242-1243; fn.clj:3328-3329).

Probe 2026-09-27 on pid 17853: `datahike.api/with` of one entity carrying only that attribute changes
`projection-cache-key`; an unrelated attribute does not.

**Final state (S4 only).** `projection-admissions` and `declaration-content-key` read admission datoms only for
entities holding `:seon.schema/key`. The revision key keeps the attribute, so every function write is a
conservative revision miss. The miss computes the scoped content key and hits the same projection.

Two numbers are estimates until the S4 replay measures them:
- the scoped join, about 3.5k schema rows (the unscoped 40k-datom key measured about 4 ms);
- its per-write frequency.

**Not in S1-S3 (N5.1).** While contracts are still loaded, `projection-from-rows` needs function-row admissions
(schema.clj:2863-2874), and a function-admission-only change must still move the content key. S1 may land the
scoped reader as an unused helper. Both owners switch in the S4 cut together with the range and resource removal.

## 4. References: a scope-aware walker, and repairing the stored facts (N3)

**The defect.** `compiled-references` (schema.clj:64-79) receives only canonical *names*. It prunes at a matching
name inside a Malli walk whose options also see only names (core.cljc:2012-2019, 2092-2096), and `fold-walk` calls
back after the children (schema/internal.cljc:18-36). Changing only its predicate therefore cannot recover a pruned
local child, so the function is rewritten.

**The replacement** is `schema/contract-references [canonical-targets compiled]`. It is one walker with explicit
inputs.
- `canonical-targets` maps each canonical key to the object that **the registry the value was compiled against**
  answers for it.
- It is a direct recursion over Malli's own seams (`m/children`, map-entry children, `m/-ref-schema?`, `m/-ref`,
  `m/options`, `m/deref`).
- A ref is canonical only when its own scope resolves the name to that target:
  `(identical? (mr/schema (:registry (m/options node)) k) (canonical-targets k))`.
- Otherwise it follows `m/deref` under a scoped recursion guard `[scope-registry k]`. Malli's name-based
  `::walked-refs` is never used.

**The pairing rule (T2).** References are derived **once, at compile time, against the targets of the world the
contract was compiled in**, and travel with the compiled value (§1 `:seon.schema/references`). A cache hit returns
the stored sets and is never walked again against a caller's current targets.

Witness T2, read-only on pid 25122:
- a contract compiled against canonical map 1 gives `#{:review/value}` walked with its own targets;
- walked with an independently compiled equal map 2, it gives `#{}`.

**Fixed by** the pairing rule plus the held base (§1): every product in an entry is compiled against that entry's
one base, whose targets are the ones stored with the product.

**Every caller converts in S1** (third review). Each passes the targets of the registry it compiled against:
- the preflight reference graph (schema.clj:710-742) and incremental direct references (744-765) compile against
  `reference-registry`, whose canonical targets are **reference sentinels** (686-708), so they pass those
  sentinels. There is no full compilation before the cycle check, and the scope is never recovered from a
  possibly shadowed child;
- `direct-references` (882-900) and admission (1600-1604) pass the projection registry's targets;
- function dependency derivation (2211, 2689) and `program/arity-row` (program.cljc:1033-1038) **take the
  seam-returned sets** and stop re-walking.

`compiled-references` [names] is deleted in S1.

**Prototype on the §4 cases** (`tmp/defn-scan-opus/scoped-refs-probe.clj`, read-only; 0.53 ms, 4 ms MCP event):

| case | walker result |
|---|---|
| Astra's shadow | `#{:review/b}` (the old walker gave `#{:review/a}`) |
| plain tuple with a nested map | `#{:review/a :review/m}` |
| local string alias | `#{:review/b}` |
| explicit `[:ref …]` in a map | `#{:review/b}` |
| local recursive registry | `#{:review/a}`, terminates |
| nested scopes reusing one recursive name | `#{:review/a :review/b}`, terminates |
| empty-ref contract | `#{}` |
| a function schema over three roles | `#{:review/a :review/b :review/m}` |

**Stored facts: corrected claim and new evidence (T2 migration).** Revision 3's `compare-stored` compared old and
new *recomputation* only; it was walker-compatibility evidence, not validation of stored facts. The new
`stored-facts` check (same file, read-only on pid 25122):
1. captures one database value (basis **536872032**);
2. pulls every contracted row's stored arity input, output and guard sets, with an absent many-valued set
   normalized to `#{}`;
3. derives the same sets with the scoped walker over that value's projection.

Result: **2,358 rows; 2,358 equal, 0 differing, 0 missing compiled schemas, 0 missing arity roots.** The
comparison took 9 ms after the query.

Equal sets do not prove that no row uses a shadowing registry. A shadow can yield the same set, and then its facts
are correct anyway.

**S3's gate** is this check, committed as a reproducible test-support check. It:
- runs against the final candidate population;
- **refuses** missing compiled or arity evidence;
- derives canonical facts through `program/contract-facts`, fed by the seam, not the probe walker;
- repairs differences through the canonical writer before indexed selection becomes authoritative.

Old/new walker agreement stays as separate compatibility evidence.

**Integrity going forward.**
- The final writer (§5) compares a changed contract's canonical stored arity fields with
  `program/contract-facts` of the seam value. The compared fields are those `with-contract-facts` writes (arity,
  min, max, argument-count, order, arguments, input, output and guard refs, error-declared schemas); they are never
  `call-preparation` descriptors (N4).
- A contracted function with missing facts is refused.
- Changes to descendants only (arity or argument children added or retracted) reach their owning function through
  the writer's existing before/after owning-root discovery (db.clj:4482-4487), with no second owner index.

**Query.** Unchanged from revision 2 (three reference branches plus owner and symbol joins; 554 symbols in 8 ms
observed). It is priced as indexed lookups, plus joins, plus the selected contracts' seam compiles, and the plan is
inspected in S3. `?key` covers the changed, removed and admission-changed schema keys plus their reverse closure in
both the before and the candidate projections.

## 5. One final-writer checker (R5, N4)

The checker is `seon.db/program-relation-error`, called from `write-report-error` (db.clj:4403-4479) on
`:db-after`.

**Selection.** Every trigger below is resolved to owning functions and schemas in both the before and after values.
It runs when the report asserts or retracts any of:
- `:seon.fn/spec`;
- `:seon.schema.admission/source` **on a function row or on a schema row** (N4: a core→agent change can make an
  unchanged contract fail completeness or purity, as `function-admission-changes` does today,
  schema.clj:2673-2678);
- any `:seon.fn/arities` root or descendant fact (§4);
- a schema row (`:seon.schema/key`, `:seon.schema/form`).

An unrelated data write selects nothing, and the regression counts that.

**What it runs.**
1. Admit every selected function's **surviving** contract through the seam against `:db-after`'s shape projection,
   and compare the stored facts (§4).
2. Select the contracts affected by schema changes (§4 query), and admit them the same way.
3. Recheck renderer coherence (`render-contract-observation`, reading both compiled sides at schema.clj:1892-1908)
   for:
   - every schema whose `:seon.render/*` relation was edited or removed;
   - every renderer whose contract changed, was removed or **was selected in step 2** (a renderer taking
     `:review/input` can stop accepting its unchanged declaring schema when only `:review/input` changes);
   - every declaring schema in the transitive closure of the changed schemas.

No callback enters the shape constructor.

**Early entrances** (`turn/row-tx`, SCI admission, `fn` publication and index) call the same pure decision function
with explicit candidate inputs: the candidate rows and the candidate shape projection. They are added in **S3**,
never earlier. The final checker never re-enters a checked `db/with` of its own report.

**Composition with derive-once** (review, "Composition"). This checker is the one program↔schema relation
authority. Derive-once slice 5's call-arity gate composes into or beside it within the same final gate; no second
acceptance path is added.

## 6. Slices (HEAD loads after each; producers and acquisition change last, R6, N5)

- **S1, additive (schema.clj, instrument.clj for the moved binder, program.cljc, db.clj, config dial resource + default, `:measure` alias and script if decided).**
  - Adds the symbol-free seam (§1) with **retention disabled until S4** (T1 bridge): the table code, the eligibility
    rule and their regressions land here, but lookups compile uncached.
  - Adds the `:seon.schema.projection/construction-inputs` record, written by the two constructors.
  - Adds `schema/contract-references` (§4) and converts **every** `compiled-references` caller with its explicit
    targets: sentinels for the reference graph, seam-returned sets for program and function dependencies. The old
    walker is deleted.
  - Moves `bind-contract-predicates` into `seon.schema` and converts its instrument caller.
  - Adds `db/function-contract-row` and the scoped admission reader as an **unused** helper.
  - Adds `program/contract-facts`' compiled-contract argument; its current callers pass the registry entry.
  - Regressions:
    - seam equals registry compile for every stored contract;
    - N1's two-binding witness gives different keys;
    - T1: an overlay and an equal rebuilt population get different construction keys;
    - T2: a hit returns the stored reference sets and never re-walks against foreign targets;
    - the T3 concurrency set (§10);
    - overlays sharing a holder; same-Var root replacement vs `ns-unmap`/recreate; distinct branch callables;
    - an armed form holding a Var or compiled schema is not cached;
    - `:error/fn` parity; agent/core admission parity;
    - a refusal is not retained;
    - deleted predicates in a historical contract keep today's diagnostic;
    - the §4 walker table plus input, output and guard roles, unions and alias chains;
    - retention under many versions, **large forms and several distinct populations** stays within the configured
      budget. This is measured by the §10 tool, and the slot charge and eligibility constants are committed with
      their results.
- **S2, every consumer (§2 #1-#9, #12-#17; owners: instrument, call_preparation, render, web, effect, test, fn).**
  - Producers, loader admission and acquisition are unchanged.
  - The early checker calls (#10, #11) are **not** in S2.
  - R1/R2 regressions as in revision 2.
- **S3, facts and the final checker (db.clj, turn.clj, sci/eval.clj, fn.clj).**
  - Gate: the §4 stored-fact check, captured on one value and run against the final candidate population. It
    refuses missing evidence, derives facts through `program/contract-facts`, and repairs through the canonical
    writer.
  - Install `program-relation-error` and switch the early entrances to its decision function. The projection-side
    function checks stay as well, and a parity test proves both refuse identically.
  - Regressions: §5 selection, including a function-row admission change with its contract unchanged,
    descendant-only fact edits, and a renderer made incoherent by a referenced-shape change; plus revision 2's list.
- **S4, the cut (schema.clj, db.clj acquisition, `resources/seon/schemas/seon.schema.edn:58-63`, fn.clj
  index/publication, sci/eval next-projection, fingerprint v4).**
  - Drops the producers and the contract range, activates schema-scoped admission acquisition, and removes the
    resource member, the retired APIs and the tests naming them, together.
  - Enables retention: the base is the shape-only registry.
  - Class regression `a-function-contract-never-changes-the-projection` with the §8 replay.
  - Root's test list is as in revision 2.

## 7. Size (recount after revision)

These are estimates from source, ±25%. The final count comes with the diffs.

| part | lines |
|---|---:|
| schema.clj function half: registry, indices, fingerprint, validation branch, replacement, row parsing, three replacement APIs, cached arities | −200 |
| schema.clj seam, construction-inputs record, walker with explicit targets (callers converted), scoped admissions | +95 |
| schema.clj population table: holder, eligibility rule, dial input | +45 |
| config dial (schema resource + default) | +8 |
| measurement script + alias (if option 1 of the §10 tool decision) | +120 script, +1 alias |
| db.clj `function-contract-row`, `program-relation-error` (the renderer coherence and replacement recheck move here from schema.clj), scoped content key | +90 / −10 |
| instrument (#1-#4) | −15 / +10 |
| call_preparation (#5-#7) | −10 / +15 |
| fn, render, web, effect, test, turn, sci/eval (#8-#16) | ±40 |
| resource member | −2 |

**Net (source, excluding the measurement script):** about −275 / +300. Most of the added lines are the final-writer checker, which replaces checks that
today run implicitly inside projection acquisition.

## 8. Expected benefit and the measurement that decides it (R7)

What is removed is projection churn: new projection values, their loads, and first-use table re-derivations. The
evidence scopes are in "What a contracted defn costs today": about 100 ms of direct calls, and an estimated
250-300 ms of broader affected work. They are not added.

**Added:**
- one seam compile per new form and mode, estimated below 1 ms for `[:=> [:cat :int] :int]` and measured in S1;
- the scoped content key on each conservative revision miss, an estimate.

**The S4 replay** runs through the agent path on parent and change, with the same probe
(`tmp/defn-scan-opus/probe.clj`). It covers:
- separate first-new-symbol and steady-new-symbol samples;
- contract replacement, body-only change, delete, and a branch sharing the population.

It records:
- stage timing and warm p50;
- allocation and armed calls;
- `load-projection` count, cheap content-key misses and table misses;
- seam compiles per mode;
- retained memory under repeated versions.

## 9. What this gives up, and later options

- **The projection no longer answers which functions a schema change breaks.** The final-writer checker (§5)
  answers it with the query (§4) on every write that can break a contract.
- **Schema admission shares one attribute with program rows.** Every function write is a conservative revision miss
  costing a scoped content key. A dedicated schema-admission attribute would make it exact; that is a
  schema-resource change across the program-row writers, about half a day, and is not required for correctness.
- **Fingerprint v4 forces one rebuild per store at adoption.**

## 10. Retention of compiled contracts (N2) — root ruling 2026-09-27: one bounded table per population

**Ruling (root, option 1).** One `seon.cache` entry per construction-input key (§1) holds that population's base and
a contract table bounded by a config dial (default 512). The entry is charged the population weight plus the dial
times a per-slot charge measured in S1, with a 16 KB ceiling to verify. The population and its contracts are evicted
together.

**Ownership and concurrency (T3).**
1. **Holder.** The outer value is `{::base … ::table (atom <core.cache LRU, threshold = dial>)}`.
   - A caller acquires the entry with `seon.cache/lookup`, which coalesces outer construction only
     (cache.clj:148-175), and releases the handle when the operation returns.
   - Products hold no reference back to the holder.
   - The holder is never placed on a projection, wrapper or other long-lived value.
2. **Inner lookup.**
   - A hit is `(swap! table cache/hit k)`. It updates LRU recency, which core.cache's wrapped `lookup` does not do
     (wrapped.clj:25-34).
   - A miss compiles **outside** the swap against the acquired entry's base. Only an eligible product (not a
     refusal, not a bypass form, within the slot charge) is then published with one `swap!`: if the key is present
     it is a hit, otherwise `cache/miss`.
   - `lookup-or-miss` is not used, because it has no retention predicate (wrapped.clj:38-70).
   - Independent concurrent misses on one key may compile twice. That is allowed and counted; single-flight is added
     only if a measurement warrants it.
3. **Eviction during a compile.** A completion publishes into the table of the entry it acquired (E1), never into a
   fresh lookup. If E1 was evicted, that insert lands in an unreachable table and is collected with it. A later
   caller builds E2 from its own miss.

   Eviction removes the **cache's** root only. Installed wrappers and plans keep their own compiled values
   (instrument.clj:608-625), and a function that is still executing is never invalidated. The weak-reference
   regression first releases those owners and waits for Caffeine maintenance (`cleanUp`). It never demands an
   immediate GC.
4. **Dial lifetime.**
   - The dial value is **part of the outer key** and is read once at the acquisition owner: the cluster's config
     facts at arming or acquisition. It is passed explicitly into the seam; the seam reads no config.
   - A dial change builds new entries under the new policy and charge. Old entries age out under the outer LRU, and
     no table grows under its old weight.
   - Clusters sharing a population with different dial values hold separate entries, each charged.

**Slot sizing and eligibility (T4).** This needs a decision on the measurement tool (below).
- **Tool.** clj-memory-meter 0.3.0, which is JOL-based and already in `~/.m2` but not on seon's classpath. It runs
  from a committed script in a `:measure` alias JVM against a copied store. The alternatives are below.
- **Object boundary.**
  - **Population root, measured once:** the key, base registry, canonical targets, validation base and holder
    overhead, over populations with different form sizes and bindings. Objects shared with the projection's own
    `::projection` entry are reported separately; two outer entries do not charge shared objects "exactly once".
  - **Incremental slot:** `measure([base table-with-k-slots]) − measure([base table-with-0-slots])`, divided by k.
    It covers the inner key and authored form, local registries, bindings and captures, the compiled contract,
    reference sets and LRU bookkeeping, with the held base excluded because it is the same object graph.
  - **After use:** measured again after forcing the validators and explainers the consumers use (Malli's lazy
    caches, core.cljc:345-359).
  - **Corpus:** multi-arity, local-recursive, large-literal, local-registry and branch-closure contracts.
- **Runtime eligibility (executable).** A conservative size rule fitted from those measurements:
  `estimate = a × printed-EDN-length(form) + b × arity-count + c × local-registry-entries`, with the fitted
  constants committed beside the script. A product whose estimate exceeds the slot charge is returned uncached.
  Computing it costs one `pr-str` of the form per miss, never per hit, and that time is measured.
- **Reported separately:** estimate, observed retained size, safety margin, bypass rate, measurement time, compile
  time. If real contracts exceed 16 KB they bypass, as ruled; the ceiling is never raised silently.
- **Regressions (S1):**
  - same-key concurrent misses;
  - distinct-key pressure at the threshold;
  - refusal, bypass and oversize are not retained, even transiently;
  - eviction during an inner compile;
  - a dial change;
  - many versions, large forms and several populations measured as retained roots after the probe references are
    dropped.

**Needs a decision: the retained-size measurement tool (T4).**
1. **Recommended: clj-memory-meter in a `:measure` alias.**
   - It runs in its own measurement JVM over a copied store, from a committed script. It needs
     `-Djdk.attach.allowAttachSelf`.
   - Guarantee: exact object-graph sizes with shared-graph subtraction.
   - Cost: 1 alias line, about 120 script lines, and one isolated JVM run per measurement. This is a declared
     measurement JVM, not a test or probe JVM on default.
   - Sacrifice: a new dev-only dependency.
2. **Heap histogram differences on default.**
   - `jcmd GC.class_histogram` before and after inserting N contracts into a scratch table.
   - Cost: no dependency; about 60 script lines.
   - Sacrifice: each histogram forces a full GC pause on the shared default; resolution is per class, not per graph;
     shared-object attribution is approximate.
3. **Estimate only.**
   - Fix the slot charge at the 16 KB ceiling and use the printed-length rule with conservative constants from the
     existing projection weight calibration.
   - Cost: no measurement.
   - Sacrifice: no retained-memory evidence, so the guarantee rests on an estimate. Astra's T4 would stay open.

## 11. Composition with derive-once (`derive-once-design-2026-09-26.md`)

This design's shape-only projection boundary replaces derive-once's contracts map attached to the base, and its
`[forms-digest contracts-digest]` projection identity.

- Derive-once's `assoc-in [:contracts sym]` recipe is retired, not shipped beside this design.
- Its table sharing and bounded-cache migration still apply to the remaining shape products.
- Its `[forms-digest contract-form-digest]` contract key is replaced by §1's resolved key (mode, admission,
  population bindings, Var-schema bypass).
- Corrected contract-reference facts (§4) are the prerequisite for derive-once's contract→schema graph edges.
- Root's joint order (open-items) places this design as steps 2-5 after the finished local fixes. Savings from the
  overlapping cuts are measured once, not added.

