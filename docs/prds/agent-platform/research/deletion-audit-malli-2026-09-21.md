---
type: research
status: draft
created: 2026-09-21
tags: [deletion, malli, schema, instrumentation, call-preparation, projection, audit, steward-platform]
---

# Deletion audit — the Malli seam

Read-only audit. No JVM, no gate, no MCP evaluation. Every row was verified by
reading both sides: the Seon span and the vendored Malli/dependency seam.

Grounding read end to end:
[malli-native-bridge PRD](../../steward-platform/plan/malli-native-bridge-prd-2026-09-20.md)
(steps 1 and 2 have landed — `mr/lazy-registry` + `mr/fast-registry` at
`src/seon/schema.clj:454-503`, and `src/seon/schema/form.cljc` no longer
exists); bridge-step specs 2–5; AGENTS.md §2, §3;
`.agents/skills/data-modeling/SKILL.md`.

## 0. Headline

| | |
|---|---|
| Area size | `schema.clj` 3,904 · `schema/*` 2,168 · `instrument.clj` 1,138 · `call_preparation.clj` 1,417 · `fn/schema_shape.clj` 468 · `test/arm.clj` 254 · `error.clj` schema machinery ~380 |
| Net deletable (production) | **1,430–1,960 lines** |
| Net deletable (tests) | **430–620 lines** |
| Fork changes needed | 2, both small (`malli/error.cljc`, `malli/core.cljc` optional) |
| Population verdict | **not a deletion target** — 212 files, 3,333 keys, **0 unreferenced** |

Steps 3 (carry only), 4 (writer diet) and 5 (instrument retained contracts)
of the bridge PRD have **not** landed; the three largest deletions below are
those three steps, now with concrete spans.

## 1. Re-implementations of something Malli already does

| src span | lines | duplicates / Malli seam | recommendation | what preserves the function | risk |
|---|---:|---|---|---|---|
| `src/seon/instrument.clj:747-754` recompiles the contract per wrapper: `get` the raw form, `compilable-form`, `bind-contract-predicates`, `m/schema bound options` | ~10 | `src/seon/schema.clj:1978-1982` already did `(mr/schema registry sym)` for **every** contract, and `projection-registry` realizes each one at `:502`. The registry is a sealed `mr/fast-registry` (`reference-code/malli/src/malli/registry.cljc:17-22`) | **delete**; read `(mr/schema (:seon.schema.projection/registry projection) function-symbol)` | `m/-instrument` accepts a compiled schema (`reference-code/malli/src/malli/core.cljc:3118-3143`); the wrapper's refusal/facet/cap logic is untouched | low — this is bridge step 5 verbatim |
| `src/seon/instrument.clj:872-874` `contract-digest` — `seon.id/digest 64` over `canonical-data-string` of the whole reference closure, per armed Var | 3 + 1 metadata key | Nothing reads it. `current-wrapper?` (`:844-858`) decides staleness by comparing `:seon.instrument/definitions` maps instead | **delete** | wrapper identity already derives from `definitions` + `contract` + `policy` | none — sole readers are `test/seon/instrument_test.clj:944,968,979` |
| `src/seon/instrument.clj:604-620` `supplied-projection` — scans every argument three ways on **every instrumented call**, then falls back to `schema/handed-projection` | 17 + per-call cost | §2.1 "values carry their world": the wrapper should close over its generation at arm time, not fetch it per call | **delete**; arm once per generation, re-arm on generation change (which `apply!` already does) | `apply!` already re-arms on contract/definition change (`:844-858`); a call with a foreign projection becomes an arming decision, not a call-time scan | medium — changes which projection a cross-cluster call validates against |
| `src/seon/instrument.clj:993-995` builds a **second complete projection** (`declaration-projection (packaged-forms)`) as `bootstrap`, plus `boot-wrapper` delay `:875-878` | ~12 | A whole-population compile per arming pass, duplicating the supplied projection | **delete**; refuse when no projection is supplied (the refusal already exists at `:970-981`) | `apply!` already refuses a missing projection; removing the fallback makes the refusal total | medium |
| `src/seon/error.clj:977-1000` `schema-expectation` — a `case` table over `:vector :map :set :string :int :double :boolean :keyword :tuple :enum :and :or :fn` producing English | 24 | `me/default-errors` (`reference-code/malli/src/malli/error.cljc:44-181`) keys on **predicate symbols** (`'vector?`, `'map?`) and on `::m/missing-key`, `:enum`, `:re`, `:=>` — it has no entries for the **keyword types** Seon declares with. The function even calls `me/error-message` first and falls back | **move into the fork**: add keyword-type entries to `default-errors` in `reference-code/malli/src/malli/error.cljc`; delete the case table | `me/error-message problem {:unknown false}` then answers directly; `explain-problem` keeps its Seon-specific path/argument/fix assembly | low — the highest-leverage "stop fighting malli" move in this area |
| `src/seon/fn/schema_shape.clj:21-57` `split-schema-form` / `canonical-map-entry` / `canonical-form` — a **raw form walker** with hand-written map/vector/set/seq recursion | 37 | This is exactly what bridge step 2 deleted from `schema/form.cljc`; it survived here. Malli's own normal form is `m/form` (`reference-code/malli/src/malli/core.cljc:2574`) and its normalized AST is `m/ast` / `m/from-ast` (`:2848-2875`), which sorts nothing but is canonical by construction | **delete**; fingerprint `(m/ast compiled)` instead of a hand-canonicalized raw form | the sort-by-key of map entries is the only behaviour `m/ast` does not give; that is one `sort-by` at the fingerprint site, not a walker | medium — changes every stored shape fingerprint, i.e. RESET NEEDED |
| `src/seon/schema.clj:761-772` `byte-array?` and `sha-256` | 12 | `sha-256` is `(id/sha-256 byte-arrays)` verbatim; `byte-array?` is `(bytes? value)` | **delete**; convert the 15 call sites to `seon.id/sha-256` / `clojure.core/bytes?` | `register-core-predicate!` at `:1194` re-points at `clojure.core/bytes?` | low — mechanical, 15 files |
| `src/seon/schema/admission.clj:159-184` `form-properties` / `form-body` / `schema-children` / `schema-nodes` — a second raw form walker, reading schema files off the **filesystem** (`:71-135`) | ~80 of 443 | Duplicates the projection's own admission refusals (`register!` `src/seon/schema.clj:1545`, `assert-complete-contract!` `:1342`) and re-reads the population the projection already holds | **consolidate**: the hook (`bin/seon-hook:498,503`) should call the projection's admission, not a file-reading linter | the house-style findings that are genuinely advisory (`exact-reuse-findings`, `name-overlap-findings`) move to `register!`'s advisory channel | **high** — it is the edit hook's gate; needs its own slice |

**Not duplicated, correctly Malli-native (leave alone):** `projection-registry`
(`schema.clj:454-503`) — lazy registry over immutable definitions, sealed to
`fast-registry`, exactly as the PRD's step-1 table prescribes;
`with-compiled-cache` (`:357-385`) — stores only products Malli does not cache
(arity descriptors), and says so; `structural-registry` (`:1272-1286`) — a
`reify` over `m/default-schemas` with an opaque placeholder, which Malli has no
equivalent for.

## 2. Duplicate code paths and caches

Grep of the area: **9** distinct derivations of "the projection", **3** cache
holders, **4** digest/fingerprint schemes.

| Derivation | src span | Same thing as | Survivor |
|---|---|---|---|
| `declaration-projection` | `schema.clj:1227-1258` | the constructor | **keep** — one constructor per generation |
| `build-projection` | `schema.clj:1891-2155` | the constructor's incremental form | **keep** |
| `projection-from-rows` | `schema.clj:2546-2736` | rebuild from stored rows | **delete** (step 3) |
| `derive-projection-from-database` | `schema.clj:2771-2787` | ditto | **delete** |
| `projection-from-database` (138 callers) | `schema.clj:2788-2810` | ditto, the running-code entry | **delete**; callers read `seon.db/carried-projection` (`src/seon/db.clj:1168`) |
| `projection-rows` / `projection-admissions` | `schema.clj:2514-2545` | the row queries feeding the rebuild | **delete** with it |
| `candidate-forms` → `packaged-forms` classpath fallback | `schema.clj:1086-1100` + `:929-1086` | a fourth, ambient source | **delete** (§3 below) |
| `instrument/apply!`'s `bootstrap` | `instrument.clj:993-995` | a fifth, per-arming | **delete** |
| `test/arm.clj` `packaged-test-projection` | `src/seon/test/arm.clj:36-50` | a sixth, per test JVM | **keep** — the worker's one legitimate acquisition |

Live evidence the rebuild path is a dead end is already recorded in the PRD
(2026-09-20, `ea98f6b52`): with publication paused, `bin/seon init` derived its
projection from the OLD `current-src` rows through `projection-from-rows` and
refused a rule the stored population satisfied.

**Caches.** Three holders: Malli's per-schema `-create-cache` atoms
(`reference-code/malli/src/malli/core.cljc:345-361`, ~30 construction sites),
Seon's `:seon.schema.projection/compiled` atom (`schema.clj:357-385`), and
`seon.schema.edn/packaged-population-cache` (`schema/edn.clj:361`). The first
two are correctly layered and documented. The third is a process-global
`defonce` atom with a `forget-packaged-population!` escape hatch (`:378`) — a
mutable global the generation should carry; it survives only because the
classpath fallback exists.

**Digests.** Four: `seon.id/id`/`digest` (the ruled one, `src/seon/id.clj:29-46`);
`schema/canonical-data-string` + `portable-string-hash` (`.hashCode`, 32-bit)
composed by XOR into `projection-fingerprint` (`schema.clj:695-816`);
`schema_shape/fingerprint` (SHA-256 over the same canonical string, `:65-72`);
`instrument`'s dead `contract-digest` (`:872`). The 32-bit XOR fingerprint has a
real algorithmic job the PRD names — `replace-fingerprint-entry` (`:808-816`)
makes reuse O(changed) instead of O(population) — so it survives as a **reuse
aid**, not as an identity. Its `.hashCode` width over 3,333 keys plus contracts
should be raised to a `seon.id` truncation in the same slice; a 32-bit collision
here silently reuses the wrong compiled generation, which is this project's
named failure class (absence of signal read as health).

## 3. O(program) work where O(edit) or O(1) exists

| Step | src span | Why it is O(program) | The seam that already does it |
|---|---|---|---|
| **The classpath fallback and its diagnostic** | `schema.clj:929-1100` (**171 lines**) | Reaching it reads and merges every schema resource on the classpath — the note records 1,886 reaches = 286,672 file reads, 26 s. The whole 158-line apparatus (stack-frame walking, `clojure.basis` parsing, canonical-directory containment, decade counting) exists **to make the wrong path loud instead of impossible** | §2.1: the caller carries its projection. Replace `warn-classpath-fallback!` with `error.refusal/diagnostic` — the same shape `refuse-projection-source` (`:2737-2770`) already uses. A typed refusal naming the caller is strictly better evidence than a counted stderr line, and it is 5 lines |
| **`assert-config-display!`** | `schema.clj:1206-1225` | `(doseq [[attribute definition] forms] … (structural-schema definition))` — a full `m/schema` compile of **every one of 3,333 forms** on every `declaration-projection` and every `build-projection` where `reuse-declarations?` is false, to check one property on the **96** declared dials | The property is on the compiled node the registry built anyway: `(m/properties (mr/schema registry k))`. Better: the check is per-declaration and belongs in `register!` (`:1545`), making it O(edit). This is the measurable half of "config compiled 35 times" |
| **Argument structure re-derived from stored mirror rows** | `call_preparation.clj:604-748` (**145 lines of Datalog**) + `argument-validators` `:642-650` | 8 queries over `:seon.fn.arity/*`, `:seon.fn.argument/*` and `:seon.schema.shape/*` reconstruct arities, positional slots, rest?, map entries and requiredness — then `m/validator` on a schema re-compiled from a database entity, per plan | All of it is on the retained compiled contract: `m/-function-schema-arities` + `m/-function-info` → `:input` (`core.cljc:3118-3143`); `m/children` for slots; `m/entries` for keys and `:optional` (`core.cljc:2771-2798`); `m/validator` off the retained Schema (`:2626-2640`). The **supplier** rows (`:seon.call-preparation/*`) are genuine facts and stay |
| **`contract-digest` per armed Var** | `instrument.clj:872-874` | `canonical-data-string` over the whole reference closure, then SHA-256, for **every** armed Var — and nothing reads the result | delete |
| **`supplied-projection` per call** | `instrument.clj:604-620` | argument scan × 3 lookups on every instrumented call in the system | close over the generation at arm time |
| **`projection-from-database` per acquisition** | `schema.clj:2788` (138 callers) | rebuilds and recompiles the population from rows | `seon.db/carried-projection` (`src/seon/db.clj:1168`) — the value already carries it |

## 4. Tests

Census: 12 primary files, **160 deftests, ~4,772 lines**.

| Group | Members | Classification |
|---|---|---|
| **Benchmarks as deftests** — `registry-generation-operation-counts` (`test/seon/schema_test.clj:41`), `full-projection-fingerprint-dispatch-timing` (`:305`), `declaration-manifest-projection-build-cost` (`:1468`), `hot-host-facet-check-measurement` (`test/seon/instrument_test.clj:1195`), `hot-sci-facet-check-measurement` (`:1223`) | 5 | **deletable (~150 lines)**. They `println` numbers and assert nothing about the bound; three of them `with-redefs` `m/schema` or a private fingerprint Var. The owner law puts measurement in the committed script with its clock row, not in a deftest that cannot fail on regression |
| **Pinned to deleted mechanisms** — `contract-digest` assertions (`instrument_test.clj:944-945,968-969,979`); `cold-arming-derives-wrapper-identity-without-a-predicate-binding` (`:960`, redefs `#'schema/*projection*`, `#'schema/*projection-state*`, `#'seon.test.arm/packaged-test-projection`); `applying-without-a-handed-projection-refuses-before-collection` (`:1091`, redefs `#'schema/handed-projection`, `#'instrument/collect-contracts!`) | 3 tests + 5 assertions | **deletable with their mechanism (~90 lines)** |
| **Duplicate class B — "a refusal names the offending argument"** — `instrument_test.clj:280,612,644,655,687,721,770,1339,1357,1425`; `refusal_grammar_test.clj:13,29,44`; `schema_test.clj:806` | 14 | **collapse to 2** (one per surface: host wrapper, SCI). ~250 lines. The class is real; fourteen instances of it is the inventory, not coverage |
| **Duplicate class A — "arming is idempotent"** — `instrument_test.clj:471,866,878,913,960,1064,1117,1285`; `registry_isolation_test.clj:51` | 9 | **collapse to 3** (idempotent, re-arm on contract change, re-arm on referenced-declaration change). ~180 lines |
| **Duplicate class C — "the population resolves once"** — `schema/edn_test.clj:77,107,122,185`; `schema/declaration_population_test.clj:63,82`; `schema/datahike_test.clj:441` | 7 | **delete 5**; when the classpath fallback is a refusal, "resolved once" is unconstructable and needs no test. ~120 lines |
| **Pinned to private helpers** — 20 tests redef or `ns-resolve` a private Var (full list in the census; heaviest: `schema_test.clj:1265` reaching into three namespaces' privates; `schema/edn_test.clj` 8 tests redefining `#'schema.edn/resource-population` and friends) | 20 | **rewrite, not delete** — each is a behavioural claim asserted against an implementation detail, so the mechanism cannot be deleted without the test failing for the wrong reason |
| **`:seon.test/long` in this area (6 of 251 repo-wide)** | | |
| `schema/admission_test.clj:126` `source-publication-records-core-on-every-row` — 300,000 ms, "Complete cold publication verifies admission on the full program population." | | **algorithm defect**: the O(program) step is `assert-config-display!` + whole-population `structural-schema` compile at `schema.clj:1206`, plus the complete-publication path. Seam: per-declaration admission in `register!` |
| `schema/datahike_test.clj:243` — 25,000 ms, 80 generated storage cases | | **genuinely long**: 80 × (admit direct + wrapped + aliased) against the canonical population is real generative work |
| `schema/datahike_test.clj:360` — 120,000 ms, three renderer contracts through the real codec | | **algorithm defect**: the cost is the publication path, not the three contracts |
| `schema/projection_acquisition_test.clj:37` — 10,000 ms, "Compares indexed acquisition with the original whole-population Datalog joins" | | **deletable with `projection-from-rows`** — it exists to prove the rebuild path is correct |
| `schema/projection_acquisition_test.clj:94` — 10,000 ms, four projections | | **algorithm defect**: four full population compiles; one carried generation makes three of them unnecessary |
| `schema_redeclare_test.clj:72` — 300,000 ms, isolated JVM publish/boot/seed/adopt/seed | | **genuinely long**: a real cold boot, which the owner law exempts |

## 5. What this area genuinely provides, and how it survives

| Function | Survives as |
|---|---|
| Every function, private included, carries a Malli contract and is armed | Unchanged. `collect-contracts!` (`instrument.clj:898-908`) walks `ns-interns`; only the contract **source** changes from a re-compile to `(mr/schema registry sym)` |
| A refusal names the layer, member, expectation and offending value | Unchanged. `error/diagnostic` (`error.clj:304`) and `explain-problem` (`:1020`) stay; only `schema-expectation`'s case table moves into the fork's `default-errors` |
| The schema population is declared once, in resources, and queryable | Unchanged and healthy: 212 files, 3,333 keys, 249 entity maps, 96 dials, **0 unreferenced keys**. Nothing here is a deletion target |
| Datahike attributes derive from Malli declarations | Unchanged — `schema/datahike.clj` already navigates compiled nodes (bridge step 2 landed; `schema/form.cljc` is gone) |
| Call preparation supplies a function's declared-and-absent arguments | Unchanged at the seam (`supply` `:1099`, `prepare` `:1277`, `hook` `:1371`). Supplier rows stay facts; only the **argument-structure half** stops being re-derived from mirror rows |
| One immutable compiled generation per cluster | Strengthened: it becomes the only source, carried on the database value (`seon.db/carry-projection-state`, `src/seon/db.clj:232-253`) |
| A missing declaration refuses loudly | Strengthened: the classpath fallback's stderr warning becomes a typed refusal |

## 6. Fork changes needed

| Fork | Change | Replaces |
|---|---|---|
| `reference-code/malli/src/malli/error.cljc:44-181` | add keyword-type entries to `default-errors` (`:vector`, `:map`, `:set`, `:int`, `:double`, `:string`, `:boolean`, `:keyword`, `:qualified-keyword`, `:symbol`, `:qualified-symbol`, `:nil`, `:tuple`, `:and`, `:or`, `:fn`) — the table already has `:enum`, `:re`, `:=>`, so this is accretion in its own idiom | `src/seon/error.clj:977-1000` (24 lines) |
| `reference-code/malli/src/malli/generator.cljc:299-310` | the generator's copied ref-cycle helper still enumerates the registry on each call, where `core.cljc:1943-1950` no longer does | not a Seon deletion; it is the remaining O(registry) path the PRD flags as separate work. **Unverified by measurement here** |

## 7. What could not be verified

- **No runtime measurement.** Every O(program) claim is structural (a `doseq`
  over `forms`, a per-call scan, a per-Var digest), read from source. No
  before/after timing was taken; this audit ran no JVM by instruction.
- **`schema/admission.clj`'s overlap with `register!`'s refusals** was not
  enumerated finding-by-finding. It is the edit hook's gate
  (`bin/seon-hook:498,503`); consolidating it needs its own inventory.
- **Whether `m/ast` preserves every property Seon's `canonical-form`
  normalizes** (specifically `{:closed false}` and `{:optional false}` dropping
  at `schema_shape.clj:17,33`) was not proven; that normalization may need to
  move into the fingerprint site rather than disappear.
- **The 138 `projection-from-database` callers** were counted, not classified.
  Some may be legitimate bootstrap acquisitions rather than running-code
  rebuilds; step 3's seam census (125 owner spans, 32 files) is the authority.
- **Test line estimates** are from deftest boundaries in the census, not from
  deleting and recounting.
