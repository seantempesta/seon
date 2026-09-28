---
type: research
status: recommendation, REPL-prototyped (no src edits); folds in render-selection-opus measurements (1787d4714) and the owner's naming ruling
created: 2026-09-27
lane: malli-render-research-opus
tags: [render, malli, schema, selection, bridge]
---

# Render pairs the Malli way

**Question (owner).** Render functions are declared on schemas (`[:map {:seon.render/ai f :seon.render/html g} …]`).
Is there a Malli-native way to attach, validate and index them so the render for a value is easy and fast to find
by specificity? Is it like the vector conversion?

**Answer.** Malli already gives us the right lookup, in three parts:

- **Declaration.** Read the property on the schema, then on its type, then follow the reference chain, and compile
  the result once per schema.
- **Identification.** Malli's answer to "which schema is this value?" is a `:multi`. Its `:dispatch` reads the
  value's own declared identity, and `m/parse` returns the tag. Malli never infers a schema from a value's shape.
- **Contract.** A renderer names the schema it renders in its input, as
  `[:and :seon.render/unit [:map [:seon.render/value S]]]`. That records `S` in its arity input refs, so discovery
  and the admission check become lookups.

Lookup is not the gap. Seon's current property rule matches Malli's on 3,655 of 3,656 schemas. The gap is that
values are never tied to their schema. The prototype ties them by identity and was **40–90× faster** than today
per value. It deletes about 160 lines of `src/seon/render.clj` for about 45. Render is not an `m/transformer`
(§1).

Pinned: malli `43c6dca8`, datahike `591c825d`, HEAD `6084ae4c8`, live `default` (archive `4b0b7633c`).
Prototype: `tmp/malli_render_prototype.clj` (scratch namespace `tmp.malli-render-prototype`, JVM mode, read-only).
Owner ruling carried by root: `:seon.render/form` merges into `:seon.render/ai` (renders return REPL entries), and the
pair keeps its names while render-selection-opus retypes it to a ref. This note does not revisit either.

## 1. The transformer-shaped chain: take its lookup, not its composition

| Mechanism | Lookup order | Cite |
|---|---|---|
| `mt/transformer` | `(get-in props [method name])` / `:name/method` → `m/type-properties` → per-type table → `:default`, values through `m/eval` | `malli/transform.cljc:387-416` |
| humanize | `:error/fn`/`:error/message` in properties → type-properties → by error type → by schema type → unknown | `malli/error.cljc:177-180, 288-305` |
| generator | `(merge type-properties properties)` then `:gen/*`, then the type multimethod | `malli/generator.cljc:490-500, 383` |
| json-schema | `(merge type-properties properties)`; `:json-schema` replaces the node | `malli/json_schema.cljc:200-207` |
| compile once | `m/-cached` stores the derivation in the schema instance's cache | `malli/core.cljc:353-360` |

**References give specificity for free.**

- A registry keyword compiles to a pointer (`-pointer` → `-schema-schema {:id k}`, `core.cljc:235, 2617-2618`).
  The pointer has no properties of its own, and `m/deref` returns its target (`core.cljc:2864-2878`), exactly as
  generator and json-schema follow it (`generator.cljc:434`, `json_schema.cljc:195`).
- `:ref` is the same `RefSchema` (`core.cljc:1953-1963`).
- For conjunctions, `mu/merge` merges properties with the later arm winning (`malli/util.cljc:77, 96`).
- So for `[:and :seon.error/base [:map {…}]]`, the effective pair is the `:and`'s own, then the last arm's, then the
  base's. Seon already implements that as `internal/entity-properties` (`src/seon/schema/internal.cljc:122-128`).
- The prototype's `declared-render` also follows an alias to a non-map schema (root's point 3: it resolves
  `:my.plan/older-completions`, which is the only one of 3,656 where the two rules disagree).

**Render is not an `m/transformer`.** A transformer composes an interceptor for every node: the parent and every
child (`core.cljc:602-612`), every `:and` arm, and the first valid `:or` arm (`core.cljc:642-660`). Composed over
`[:and :seon.error/base [:map …]]`, it would run both renderers. A render picks one owner per value, and that
renderer decides how far to descend. So Seon takes two things from the transformer:

- its lookup order (properties → type properties → type table → default);
- its compile-once `-cached`.

With the owner's merge there is one face per consumer (`:seon.render/ai` for AI, `:seon.render/html` for HTML).
These are two property keys, like two transformer *names*. They are not the two *methods*, because `:decode` and
`:encode` are inverse directions.

## 2. Why selection misses (measured)

- **Lookup works.** The prototype's `declared-render` resolves 542 schemas: 340 declare their own pair, 202 only
  inherit one, and all 284 `:seon.error/base` derivations are covered. It costs 3.6 ms cold for all 3,656 and
  450 ns per schema warm. render-selection-opus independently found all 717 declarations resolvable.
- **Identification fails.** `declared-producers` (`src/seon/render.clj:536-571`) has only four ways to name the
  value's schema: metadata, the calling function's satisfied return alternative, the reaching attribute, and
  `entity-owner` with full validation.
  - Probe: one generated value for each of 209 declaring schemas (131 more could not be generated).
  - Without identity, 23 (11%) select their pair. With the declared schema supplied, 199 (95%) do. The other 10
    are attribute declarations whose values are not maps.
  - The lane traced "19 of 127" to `declared-callers` skipping string-stored pairs.
- **Discovery by arity refs cannot fill the gap.** 35 of the 55 declared renderers take only `:seon.render/unit`
  (the lane counts 63 of 103), so their input refs never name the rendered schema. The lane's measure: 28 of
  1,075 pairs are discoverable.

## 3. How a value finds its schema, the Malli way

Malli's candidates, weighed:

- **`m/-infer`** (`core.cljc:243-245`) only picks a scalar type for enum children. It is irrelevant here.
- **`:or` dispatch by validators** (`core.cljc:657-660`) takes the first valid arm. Every member of an open-map
  family also satisfies its base, so for errors it lands on the base. Over the 68-arm `:seon.db/error-result` it
  cost 16.7 µs per value and reached only the base's pair.
- **`:orn` / `m/parse`** tags only the first valid arm, so it has the same limit.
- **`:multi` is Malli's answer.** Its `:dispatch` (evaluated through `m/eval`, `core.cljc:1861-1883`) maps a value
  to its arm key in O(1), and `m/parse` returns `[tag value]`.
  - Prototype: a 284-arm `:multi` dispatching on the producer's declared schema
    (`tmp.malli-render-prototype/declared-schema-of`).
  - It compiled once in 12.5 ms, validated a live `:seon.db/invalid-read-error` in 403 ns against its *specific*
    arm, and parsed its tag in 458 ns.

The dispatch keys already exist, because each value's producer declares its identity:

1. **Live errors:** `:seon.error/declared-schema` metadata, which the producer sets (for example
   `src/seon/db.clj:216`).
2. **Entities:** the single identity attribute's `:seon.program/row-schema`, plus a check that its required keys are
   present. The required keys come from `internal/entity-entries`, computed once per row. There is no full
   validation, because a committed value is not re-admitted.
   - On 81 live entities over 45 identity attributes this agreed with today's rule on 78, at 779 ns vs 28,928 ns.
   - On identity-only partial maps it made the same choice as today every time (floor 66, pair 15).
   - The other 3 are 2 schema entities and 1 cluster entity. Today's validation refuses them only because the
     property lift adds keys their row does not own.
3. **Everything else:** the known schema, meaning the root call's declared output or the attribute's declaration.
   Here the compiled `selector` dispatches disjoint arms by cached validators and drops any arm another satisfied
   arm refines (`internal/extends-schema?`, `internal.cljc:136-150`).

**Timings** (warm, per value; prototype `render-for` vs today's `declared-producers` on the same values):

| Values | Prototype | Today | Agreement |
|---|---|---|---|
| live errors | 204 ns | 3,638 ns | 2/2 |
| stored fault entities | 654 ns | 27,302 ns | 37/37 |
| entities | 779 ns | 28,928 ns | 78/81 |

A stored fault's `:seon.error/declared-schema` *attribute* names its occurrences' schema, not the fault entity's.
Identification must read the metadata, never that attribute: reading the attribute chose the wrong pair for 3 of
37.

## 4. Renderer contracts should name their input type: yes

`[:and :seon.render/unit [:map [:seon.render/value :seon.error/base]]]` compiles in the live projection. It records
`#{:seon.error/base :seon.render/unit}` through `seon.schema/contract-references` (`src/seon/schema.clj:73-110`),
accepts a unit holding a live error, and refuses a unit holding `{:x 1}`.

**What it buys:**

- The schema property stays the one authority for *which* renderer a value selects.
- The input type makes that declaration checkable once, at the writer. A declared pair whose renderer's input refs
  do not include the schema or one of its ancestors is refused at schema admission, instead of being tested per
  value.
- The namespace stage becomes an input-ref lookup plus one cached validator. Today it runs
  `function-accepts-and-returns-in?` per candidate per value (`render.clj:672-698`).

**How to adopt it:**

- Name one reusable unit per rendered family rather than inline `[:and …]` in every contract. Malli has no
  parametric schemas.
- Convert by accretion: each renderer as it is touched, and every new renderer at merge.
- Verify at the REPL that schema admission accepts an entry `[:seon.render/value S]` against the registered
  `[:any]` declaration of `:seon.render/value` (unverified here).

## 5. Storage (the vector lesson), and one guard for the retype

The vector seam is the bridge's `compiled-storage` fold (`src/seon/schema/datahike.clj:83-150`). The attribute's
declared Malli form chooses storage there (`:seon.db/ref` → `:db.type/ref`, `:92-93`), and the codec sits at the
existing encode and decode seams (`:993-1030`). Today the pair is `[:or :qualified-symbol :string]`, stored through
the EDN string codec (`:128-135`), and it already reads back as a symbol.

The lane's retype to a ref works: 681 refs, 0 unresolved, "which schemas render with f" in 0.02 ms. Datahike
resolves the lookup ref `[:seon.fn/sym s]` natively on write (`datahike/db/transaction.cljc:795`). Two requirements
for that slice:

1. **Store the effective pair, not the root property.** `storable-properties-in` (`datahike.clj:274-291`) reads only
   `m/properties` of the root, so pairs declared on an `:and` arm are lost (root's point 2). Write
   `(declared-render compiled target)` instead.
2. **Guard the explicit-value stage. This is a live defect today.**
   - The property lift puts the schema's pair onto the schema entity under the same key that means "this value's
     own override". `explicit-value` (`render.clj:614-620`) then returns `[:seon.render/ai seon.background/render-ai]`
     for the entity `[:seon.schema/key :my.background/invalid-call-error]`. The schema entity itself renders with
     the error renderer, and this affects all 340 declaring schema entities.
   - After the retype, a pulled schema entity carries a ref, which is an entity map. Unless `explicit-value` admits
     only override shapes, it would treat that map as inline rendered data.
   - Override shapes are a qualified symbol, a function, AI entries, or Hiccup. A stored declaration and a live
     override are then distinguishable by type, which strings and symbols never allowed.
   - Add one regression: a pulled schema entity renders by its own row's pair.

## 6. What the render-selection lane builds

**Selection.** Change `render.clj` only. `:explicit-value` (with the §5 guard), `:namespace` and `:floor` stay.
Replace `declared-producers` with the prototype's order:

1. `value-schema`: the declared-schema metadata, else the single identity attribute's row schema when its required
   keys are present.
2. Else the known schema's compiled `selector`.
3. Else the floor.

Read the pair with `declared-render`, compiled once per schema. Put it in `schema/projection-cache-value`, the
one-cache law's owner, rather than Malli's `-cached`.

Delete:

| Lines | What |
|---|---|
| `render.clj:310-362` (53) | `entity-owners`, `entity-satisfied?`, `entity-owner` |
| `:364-436` (73) | `named-alternatives`, `canonical-name`, `refined-names`, `most-specific`, `output-alternatives`, `satisfied-alternatives` |
| `:536-571` (36) | `declared-producers` |

About 160 lines are replaced by about 45. Contracts go on `declared-render`, `selector` and `value-schema`.
Regressions:

- an inherited error pair;
- an alias to a non-map schema;
- identity with a partial map;
- a schema entity;
- `:or` specificity;
- a fault entity next to its occurrence's declared schema.

**Storage.** Land the retype with the §5 requirements: store the effective pair and guard `explicit-value`.

**Contracts.** Convert renderer inputs to named units by accretion (§4). Add the admission check that a declared
pair's renderer accepts the declaring schema's unit.

**Do not build a parent-ref ancestry index.** Ancestry is Malli's own deref/`:and` chain, compiled once for the
whole projection, so there is nothing to store or walk. Declaring `:seon.error/value` as a `:multi` on
`declared-schema-of` is the Malli-native next step for contracts. It gives exact error alternatives at 403 ns, but
it tightens every one of the 136 functions that return that value, so it is a separate decision for root.

One finding from this lane's own probe: generating one value for each of 340 schemas took 116.6 s, and 131 schemas
could not be generated. That was a disposable probe, not an ordinary operation, but the schemas that cannot be
generated are a gap in test coverage.
