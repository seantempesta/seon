---
type: prd
status: ruled direction (owner 2026-09-20: decisions 1–3 in section 6); DRAFT until the astra review links every [verify] claim to reference-code/ file:line
created: 2026-09-20
tags: [prd, schema, malli, datahike, bridge, projection, dissolution]
---

# The Malli-native bridge: one compiled registry is the projection

Owner (2026-09-20): "stop fighting malli and use its internals to make
everything fast and simple. However you think we can improve our
applications and definitions to the database schema sketch it out and run
everything by astra with links to malli source so we stop reinventing the
wheel." Earlier the same day: "we should be able to specify a composite
error without causing all these problems. The errors are just data right?"

## 0. Evidence this PRD answers (read first)

| Incident (this week) | Bridge mechanism at fault | Note |
|---|---|---|
| Config validator compile burned 127–193 s CPU during adoption | `candidate-registry` `-schemas` merged 3,208 forms per ref validator (`src/seon/schema.clj:1147`); Malli's `-identify-ref-schema` enumerated it | adoption-silence-diagnosis-2026-09-19 |
| Declaring ONE new facet stalled a fast run 320 s | recursive `fold-contract-validations` → `build-projection`; `assert-compilable-schema!` ignoring the supplied registry and reallocating a full one per declaration (`schema.clj:1860–1870`, `schema/internal.cljc:359–367`) | kind-sweep-turn-cluster, error-composition-review rank 3 |
| Fix: 644 → 23 ms per 100 validations, 2,000 → 0 merges | four-line scope change in the fork + reuse of compiled registries | projection-compile-stall-2026-09-20 |
| Fresh publication derived its reference model from ZERO persisted schema rows | three sources of "the projection": packaged forms, database rows, carried value; `fn/index!` chose the wrong one | publication-projection-repair-2026-09-19 |
| Instrumentation compiled the default configuration per wrapper | per-Var policy acquisition instead of one value carried | same |
| Population commit 26–33 s on the serial writer; operator silence bound fires | whole-entity validator re-expanding owned values per transaction (`src/seon/db.clj:3506`) | writer-hang-root-cause-2026-09-18 |
| Twelve ranked Malli-usage findings | our layer re-implements registry, walker, compile pass and cache | error-composition-review-2026-09-20 Part 2 |
| Bridge size | `src/seon/schema.clj` 3,854 lines / 159 functions; helpers 2,400 lines | measured 2026-09-20 |

The composition of error data (`[:and :seon.error/base [:map …]]`) is
CORRECT and stays (review Part 1). What follows is about the machinery
around declarations, not the declarations.

## 1. The rulings this PRD obeys

- **Values carry their world** (AGENTS.md §2.1): the projection is ONE
  immutable value per cluster generation, carried by the database value and
  the environment; nothing derives it at call time.
- **One mechanism, accreted in place** (§2.5): no `schema-v2`; the bridge
  is rewritten by deleting what Malli provides and keeping what only we
  know (storage, identity, components, render pairs, deletion dial).
- **Prefer dissolution to addition**: the target is measured in lines
  deleted and in the number of places that can compile a schema (one).
- **Facts over inference** (§2.2): a declaration's storage facts are still
  database rows; what changes is that they are DERIVED from the compiled
  schema by Malli's walk, never from a hand-written form walker.
- **Unbreakable graph**: the final-state refusal at the writer survives
  every option below; only its cost moves.

## 2. Target design (sketch; each [verify] needs a Malli/Datahike file:line)

### 2.1 One registry, compiled once, is the projection

```clojure
;; Today: forms map → candidate-registry reify (merge per call) → m/schema per use
;; → separate projection-cache + with-compiled-cache → raw walker over EDN forms.

;; Target: the projection IS a Malli registry of compiled schemas.
(def projection
  {:seon.schema.projection/registry   registry      ; malli.registry/registry over {k compiled-schema} [verify: mr/registry, mr/fast-registry]
   :seon.schema.projection/generation generation    ; digest of the declaration population (already :seon.source/digest)
   :seon.schema.projection/attributes attributes})  ; Datahike attribute definitions derived ONCE by m/walk (2.2)
```

- Built once per publication from the declaration population: `m/schema`
  each form with `{:registry registry}` where the registry is itself the
  compiled map (a lazy or mutable registry during construction, sealed to an
  immutable `fast-registry` after) [verify: `malli.registry` `lazy-registry`,
  `mutable-registry`, `fast-registry`, `composite-registry` semantics and
  `-schemas` cost].
- Validators, explainers, generators come from the compiled schema's own
  cache (`-cached`) [verify: `malli.core/-cached`, `-create-cache`]; our
  `with-compiled-cache` / `projection-cache-value` keep ONLY values Malli
  does not own (Datahike attribute plans, write validators per entity,
  selector derivations), keyed by the projection value.
- `valid-candidate-value?` / `explain-candidate-value` take the projection
  and call `m/validate (get registry k) value` — no registry construction
  per call. The two-argument arities are deleted.
- The raw form walker (`src/seon/schema/form.cljc`) is deleted: `m/walk`,
  `m/entries`, `m/properties`, `m/children`, `m/type`, `m/deref`
  answer every question it answered [verify each: `malli.core` walk and
  entry APIs; how `:and` children are exposed; how `:ref`/keyword
  references resolve through the registry; `m/deref-recursive`].
- Composite facets stay `[:and :seon.error/base [:map …]]`; storage-attribute
  discovery walks the compiled `:and`'s children (a `:map` schema exposes
  `m/entries`); no expansion step of ours [verify: `-and-schema` children;
  `malli.util/merge` NOT used, review Part 1 says it weakens required
  fields].

### 2.2 Datahike attributes derived from compiled schemas

```clojure
;; Today: seon.schema.datahike walks raw forms to decide :db/valueType, cardinality, ref, component, index, unique.
;; Target: one m/walk over the registry, reading schema TYPE and PROPERTIES.
(m/walk schema
  (fn [schema path children options]
    (let [t (m/type schema) p (m/properties schema)] …)))   ; [verify: m/walk signature, ::m/walk-entry-vals, how properties of an :and reach the entries]
```

- The mapping table (Malli type → `:db/valueType`; `[:set X]` →
  cardinality many; `:seon.db/ref` → `:db.type/ref`; `{:seon.db/component
  true}` → `:db/isComponent`; `{:seon.db/identity true}` → `:db.unique/identity`;
  `{:seon.db/index true}` → `:db/index`; `:qualified-symbol` →
  `:db.type/symbol`) is DATA, declared once, tested once, and is the only
  place a Malli type meets a Datahike type. `:seon.db/cardinality :many`
  (invented, unregistered — audit C) is deleted.
- Storage properties are ordinary Malli schema properties (they already
  are); the registry carries them; nothing re-parses EDN to find them.
- Attribute definitions are transacted at publication as they are today
  (schema rows are facts); the difference is their derivation.
- [verify against Datahike]: `datahike.schema` attribute definition grammar,
  `:schema-flexibility :write`, how a schema change is applied to a branch
  (`datahike.api/transact` of `:db/ident` entities), which properties are
  immutable once installed (the reset rule).

### 2.3 Three sources become one

| Today | Target |
|---|---|
| `seon.schema.edn/packaged-forms` (classpath EDN) | the publication INPUT only; never read at runtime after publication |
| `seon.schema/projection-from-database` (rebuild from schema rows) | deleted for running code; a database value CARRIES its projection (`seon.db/carried-projection`), stamped at publication/adoption with the generation digest; a value without one is a typed refusal, not a rebuild [verify: how Datahike lets us attach metadata to a db value or connection — `:meta` on the db record vs our wrapper] |
| `seon.schema/declaration-projection` (forms → registry per call) | the one constructor, called once per publication and once per candidate context (an agent's admitted declarations fork the registry: `composite-registry` of [candidate, base] [verify]) |
| the environment's projection | the same value, handed through `seon.env` |

Adoption becomes: build the registry from the population once, derive
attributes, transact schema rows and program rows, stamp the generation on
the branch, carry the value. No reader ever asks "which projection".

### 2.4 The writer's validator does less per transaction, the same in total

What must stay at the writer (final-state authority, the deletion dial,
components validated as one owning value — guide §2–§3): the final-report
check over the entities the transaction TOUCHED. What moves earlier with
the same guarantee:

- per-attribute type/cardinality/ref validity → Datahike's own schema
  validation at `:schema-flexibility :write` [verify what it checks and what
  it does not: refs to missing entities, component ownership, required keys];
- entity-map required-member validation → compiled `:map` validator from the
  registry (no re-derivation per transaction);
- owned-value expansion → one indexed seek per touched owner, not a walk
  over every component schema.

Measured target: the 74k-operation population commit's validator share
(11.4 s of 26 s applying, writer note) falls by the recompilation part;
the review lane measures the remainder before this section is binding.

### 2.5 Instrumentation

`seon.instrument/compiled-wrapper` keeps what Malli's `malli.instrument`
does not do: evidence caps, the error facet refusal, caller frames,
recording. It takes validators from the registry's compiled schemas, and its
policy/config from one value carried by the arming call (landed `5ad9ea70c`).
[verify: `malli.instrument/instrument!` semantics, `m/function-schemas`,
`m/=>` and `:malli/schema` metadata, so our wrapper composes with rather
than replaces Malli's function schema registry.]

## 3. What is deleted (the measure of success)

| Mechanism | Lines (est.) | Replaced by |
|---|---:|---|
| `src/seon/schema/form.cljc` raw walker | 216 | `m/walk`, `m/entries` |
| `candidate-registry`, `declaration-population`'s per-call resolution, the two-argument `*-candidate-value` arities | ~300 | one registry value |
| `assert-compilable-schema!`'s per-declaration registry, `fold-contract-validations` recursion | ~200 | compile once with the registry |
| `projection-from-database` for running code | ~150 | carried projection |
| `with-compiled-cache` entries Malli owns | ~100 | `-cached` |
| `:seon.db/cardinality :many` and every property Malli already expresses | — | Malli properties |

Target: `schema.clj` under 2,000 lines with one function that compiles a
schema, one that derives an attribute, one that builds a projection.

## 4. Migration order (HEAD loads at every step; each step one lane)

1. **Registry value** (compile once, seal, carry; delete per-call registry
   construction; `valid-candidate-value?` takes the projection). Proof: the
   review's probe (64 facets, N validations) plus the canonical fixture
   arming 1,365 contracts. No schema resource changes.
2. **Walker deletion**: attribute derivation by `m/walk`; `form.cljc`
   deleted; the type-mapping table as data with one table-driven regression
   (every Malli type we use × every storage property).
3. **Carried projection only**: `projection-from-database` retired for
   running code; adoption stamps the generation; a database value without a
   carried projection refuses. RESET NEEDED for the stamp attribute.
4. **Writer validator diet** (2.4), measured before/after on the population
   commit; the deletion-refusal regressions unchanged and green.
5. **Instrumentation on the registry** (2.5).

Each step ends with `bin/test --paths … -- seon.schema-test seon.db-test
seon.instrument-test seon.cluster-test` green and a live adoption of
default measured (target: complete publication well under the 30 s silence
bound without raising it).

## 5. Astra review (required before binding)

The `bridge-dissolution-review` lane (running) produces the pain-point
ledger and options; its follow-up reviews THIS document: every [verify] gets
a `reference-code/malli/src/malli/*.cljc:line` or
`reference-code/datahike/src/datahike/*.cljc:line`, every wrong claim is
corrected in place, every "Malli already does X" is proven by a probe, and
the migration order is re-costed. The review may reject a section; it may
not add a second mechanism.

## 6. Owner decisions — RULED 2026-09-20 ~10:15 UTC (question tool)

1. **Projection identity:** the published population's digest is stamped
   on the cluster branch at publication/adoption (one new attribute at the
   next reset) and carried by every database value; a value without a
   stamp is a typed refusal, never a rebuild from rows or files.
2. **Validation split:** Datahike's write-time schema check
   (`:schema-flexibility :write`) is trusted for attribute type,
   cardinality and unknown-attribute refusals; our final-report validator
   keeps only what Datahike has no notion of — required members, component
   ownership as one value, the deletion refusal, relational invariants. The
   astra review measures the delta and proves parity with the deletion
   regressions before any check of ours is removed.
3. **Order:** bridge steps 1–2 (registry value, walker deletion) land FIRST;
   the remaining wave-1 schema families (test evidence, agent/namespace/
   turn, deletion-dial sweep, relational validator) declare once on the new
   bridge afterwards. The running config/plan lane finishes as is.
