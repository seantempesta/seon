---
type: research
status: complete
created: 2026-09-20
tags: [research, error-model, malli, schema, performance, design-review]
---

# Error composition and Malli usage review

## Decision

**Keep `[:and :seon.error/base [:map …]]` as the authored declaration; repair schema acquisition and reuse.** It expresses the intended intersection of open maps. The measured conjunction is inexpensive; replacing it with Malli's default `:merge` changes the guarantees for overlapping members. Retain one base declaration and owner-local facets, and derive storage membership once from that declaration graph. Do not introduce a dispatch stamp. Evidence: Malli `core.cljc:825–868`, `util.cljc:53–101`; Seon `schema/form.cljc:24–109`; the composition and overlap probes below. Paths beginning `core.cljc`, `util.cljc`, `registry.cljc`, or `generator.cljc` in this note are under `reference-code/malli/src/malli/`.

The much larger opportunity is to retain compiled Schema objects in the immutable projection and use Malli's caches on those objects. A stable registry containing raw forms alone does **not** make `m/validate :key value options` reuse a validator. `m/schema` constructs the object first, then `-cached` caches on that object (`core.cljc:353–361`, `:2550–2572`, `:2626–2668`). The probe measured 5.380 ms per 100 repeated keyword validations versus 0.018 ms with a retained validator; these are isolated warm-JVM measurements, not application latency estimates.

## Scope, authorities, and evidence boundary

Citation paths: unprefixed `schema.clj`, `schema/form.cljc`, `schema/internal.cljc`, `schema/datahike.clj`, `error.clj`, `instrument.clj`, `config.clj`, `render.clj`, `schedule.clj`, `program.cljc`, `db.clj`, `print.cljc`, `sci/eval.clj`, `call_preparation.clj`, and `test/accretion.clj` mean files under `src/seon/`; unprefixed `seon.*.edn` means `resources/seon/schemas/`. Research-note abbreviations mean files under `docs/prds/steward-platform/research/`; PRD abbreviations mean files under `docs/prds/steward-platform/plan/`. Line citations describe the inspected bytes, not an assurance that concurrent edits leave numbers unchanged.

Authorities: [error entities PRD](../plan/error-entities-prd-2026-09-17.md), [namespace agents plan](../plan/namespace-agents-plan-2026-09-19.md), [error conversion PRD](../plan/error-conversion-prd-2026-09-20.md), [adoption diagnosis](adoption-silence-diagnosis-2026-09-19.md), [turn-cluster boundary](kind-sweep-turn-cluster-2026-09-20.md), and [publication repair](publication-projection-repair-2026-09-19.md).

Read AGENTS.md §§2–3 end to end before reviewing code; read the error-conversion PRD end to end, the error-entities PRD §2.1 in full, namespace-agents plan §8 including D12/D13, and all three supplied diagnosis/landing notes in full. Also read the data-oriented-clojure skill and the roadmap entry. The earlier PRD correctly says `:merge` is absent from the configured defaults; that is a registry installation choice, not a Malli inability to express map composition (`docs/prds/steward-platform/plan/error-entities-prd-2026-09-17.md:20–40`; `namespace-agents-plan-2026-09-19.md:395–396`; `error-conversion-prd-2026-09-20.md:88–106`). No production edits, gate, operator command, cluster JVM, or cross-lane operation was used.

Source basis at the beginning of the review: Seon `79c95b6e36de5a39e9414fca667d288babb25c0b`, branch `steward-platform`; Malli initially `3517a3cd9271b2083780ac7be1725493905bca2e` plus the other lane's in-flight core scope patch. The scratch classpath pinned a copy of those **patched** core bytes, SHA-256 `16e12300964c46a8652967cc6fc7f9c5ddb27579199500badb7a3fe0d54e0c3d`. During review the Malli checkout advanced to `606083c5c5b388e84d169c7080af33ed3ec242ae`; this is an observation, not this lane's verification of that commit. `schema.clj` remained the source cited below. Other lanes' render, SCI, instrumentation-test, and launcher edits were preserved. Resource counts include the observed working-tree render declarations, so they are dated counts, not a fixed inventory promise.

Only scratch JVMs ran: Java 26.0.1, Clojure 1.12.5, bounded at 180 s and 120 s; both exited 0. The scripts, results, input digests, and core scope patch are retained in this note; `tmp/error-composition-review/` was removed after delivery verification. This is a Malli/design probe, **not** canonical-fixture, armed-system, storage-transaction, or live-adoption proof. The 127–193 s CPU and 320 s timeout are the supplied historical observations, not reproduced or attributed anew here (`adoption-silence-diagnosis-2026-09-19.md:55–91`; `kind-sweep-turn-cluster-2026-09-20.md:29–49`).

## Part 1 — composite error data

### Dependency ledger and actual semantics

| Mechanism | Vendored implementation | Seon seam / consequence |
|---|---|---|
| Conjunction | `core.cljc:834–865` compiles children; invokes every child validator on the **same whole value** on success. `impl/util.cljc:38–69` composes short-circuiting `and`; a false child stops validation. Explanation visits every child (`core.cljc:866–868`). | `resources/seon/schemas/seon.db.read.edn:4–17` uses base + map + a whole-value predicate. Keep that predicate when normalizing maps. |
| Open map | `core.cljc:1266–1287` makes required/optional key lookups. Extra keys are checked only with `:closed` or a default-entry schema. | Base `resources/seon/schemas/seon.error.edn:266–290` has 3 required and 11 optional members. A facet adds fields without preventing other facets on the same value. |
| Native map composition | `mu/merge` is `util.cljc:53–101`; `:merge` delegates to it via the proxy at `:382–404`, `core.cljc:2306–2338`. Defaults on overlap are **right wins**, including requiredness (`util.cljc:75–77`). | `[:merge base delta]` is equivalent to current simple facets only when overlaps preserve/strengthen constraints. A later optional field can weaken a required base field unless admission refuses it. |
| Built-ins vs utilities | `core.cljc:3018–3045` includes `:and`, `:map`, `:multi`, `:ref`, function and sequence schemas; `default-schemas` combines predicate/class/comparator/type/sequence/base sets. `mu/schemas` adds only `:merge`, `:union`, `:select-keys` (`util.cljc:402–404`). | Installing `mu/schemas` in the supplied registry is supported; no fork edit is needed to enable `:merge`. The utility namespace depends on core (`util.cljc:1–4`), not the reverse. Absence is not evidence of defective composition. |
| Multi | `core.cljc:1874–1898` builds validators for all dispatch branches and invokes one selected validator per value. | A new dispatch key adds a classification fact; one branch does not yield D13's **set of all satisfied facets**. Dispatch on an existing layer also cannot distinguish multiple facets at the same layer. |
| Keyword and explicit refs | `core.cljc:2559–2572` resolves a keyword and wraps its compiled definition in a pointer. Explicit `:ref` memoizes resolution and ties recursive validator knots (`:1968–1998`). | Repeated keyword compilation is not intrinsically a scan of every registry entry. Referenced children still compile afresh if the registry keeps producing fresh schemas. |
| Cache | `core.cljc:345–361`, `:2626–2657` retains validator/explainer state on each compiled Schema instance. Parents frequently call child `-validator` directly (`:865`, `:1271`), so object retention does not promise a global DAG-wide cache for all internal assembly. | Retain the actual root schema/validator, not merely a keyword or registry identity. |
| Registries | `registry.cljc:17–34`: simple/fast maps; fast copies all entries into a Java HashMap. `:54–65`: composite lookup probes in priority order, enumeration merges; mutable dereferences its backing state. `:81–95`: lazy caches provider **results**, default first; enumeration contains defaults plus resolved provider entries. | A lazy provider returning compiled schemas can reuse compilation. A mutable/global registry would undermine projection isolation. A lazy registry's enumeration is not Seon's complete declaration catalog; retain canonical forms for discovery. |
| Per-schema registry | `core.cljc:313–339` compiles property-registry definitions and composes their scope; refs use that scope. | Suitable for local recursive lookup-value grammar (`resources/seon/schemas/seon.db.edn:33–42`), not for copying the entire Seon population into every facet. |

For `B` base entries, `F` facet entries and `P` domain predicates, successful validation of a simple conjunction performs `B + F` member lookups, two map checks, and the predicates; flattening removes one map check and may deduplicate overlapping checks. Optional entries are tested for presence; their child validator runs only when present. This is a source-derived work bound, not a claim that arbitrary `:fn` predicates are cheap. Compile cost includes reachable schema occurrences, validator assembly, and the registry's lookup implementation; a diamond of raw references can repeat subtrees. It is not inherently proportional to the number of **unrelated** definitions (`core.cljc:834–865`, `:1266–1287`, `:2559–2572`).

The historical cycle key was the entire enumerated registry (`core.cljc:1943–1950` at Malli `3517a3c`). Every explicit ref-validator paid enumeration/merge plus scope-key hashing. The pinned patch changes scope to the actual supplied registry; the synthetic candidate-registry probe recorded **zero enumerations** while validating these schemas. That verifies the local distinction; it does not prove the projection stall fixed (`core.cljc:1943–1998`; script `:registry` results).

### Seon's declaration, storage, identity, and wrapper

The raw walker is already more complete than the PRD's historical warning: `entity-map-form` resolves aliases, recursively collects **all conjunction arms**, rejects cycles, refuses differing scalar definitions, and refuses optionalizing a required member (`src/seon/schema/form.cljc:24–65`; change history `3b84644ef`). It is an intersection walker, not a union walker. `map-shape?`, `schema-properties`, and `map-entries` each call that expansion (`:67–95`); `database-attributes` calls all three for admitted maps (`:130–157`). Preserve authored references separately; the expanded shape is derived data.

`:seon.db/attributes true` on an `:and` has **no Malli storage behavior**. Seon's walker reads that property and collects the inherited map's keys. The Datahike bridge then resolves each scalar attribute's own declaration and derives value type, cardinality, uniqueness and component flags (`src/seon/schema/datahike.clj:233–276`, `:320–345`). The facet is not a new Datahike entity kind or a stored conjunction. One resource owns each scalar; the base's member declarations remain in `seon.error.edn`. This design supports both raw conjunctions and a derived flat map if the same derivation feeds storage and validation.

Three actual owners illustrate the distinction: the read facet also requires operation/target/basis and a relational predicate (`seon.db.read.edn:4–17`); contract-error adds function, check, expected shape and explanation (`seon.instrument.edn:83–98`); test-error adds test symbol and execution observation (`seon.test.edn:281–297`). The measured facet is the smaller but actual `:seon.test/unknown-error` (`seon.test.edn:232–234`), using the **full actual base**, including all optional fields and recursive Datahike ref grammar. These timings do not price the read predicate or a complete Seon projection.

D13 is structural: `facet-keys` currently requires an authored top-level `:and` extending the base, then `facets` runs all retained validators; `signature` sorts their keys into the hash (`src/seon/error.clj:1806–1840`, `:186–212`). Blindly replacing declarations by flat maps or `:merge` would make facets disappear from discovery and change recurrence identities. Any alternative must retain the authored extension relation and stable facet keys; a value must still satisfy **every constraint** in each reported facet. Storage key collection is not proof of facet satisfaction.

The wrapper compiles the named output contract and applies Malli instrumentation; it also derives permitted facet keys from the output position (`src/seon/instrument.clj:621–662`, `:735–810`; `core.cljc:2202–2220`). `:merge` is explicitly refused by `declared-result` (`instrument.clj:651–656`), so adding `mu/schemas` alone cannot make this migration correct. A derived flat **compiled** form with unchanged authored contracts avoids that migration. Two independent semantic findings in Part 2—the wrapper's stronger all-facets rule and the constructor's dropped extra fields—must not be blamed on Malli conjunction.

### Composition probe

Fresh registry per sample; 3,208 unrelated scalar definitions plus only the actual facet's supporting declarations and N named copies. The timed region compiles **all N roots and validators**; `:multi` compiles one root containing N flat branches and dispatches on the artificial `:probe/dispatch` key. The other variants accept that key as an extra; this control measures dispatch cost without claiming equivalent classification semantics. Registry creation is outside this region. Five local warmups follow a common warmup, then 11 samples; medians in milliseconds. Column medians need not add exactly. `:merge` is the declarative utility schema that invokes `mu/merge`; a second direct `mu/merge` experiment is retained below. All validators accepted an extra-key-bearing valid map and refused missing operation and mistyped facet-member values. Script and exact results are in the appendices.

| N | Declaration | `m/schema` ms | `m/validator` ms | Combined ms |
|---:|---|---:|---:|---:|
| 1 | `:and` | 0.093 | 0.056 | 0.149 |
| 1 | flat map | 0.079 | 0.061 | 0.154 |
| 1 | utility `:merge` | 0.114 | 0.084 | 0.199 |
| 1 | `:multi` | 0.088 | 0.053 | 0.139 |
| 16 | `:and` | 0.737 | 0.557 | 1.309 |
| 16 | flat map | 0.678 | 0.501 | 1.188 |
| 16 | utility `:merge` | 0.514 | 0.492 | 1.007 |
| 16 | `:multi` | 0.506 | 0.417 | 0.912 |
| 64 | `:and` | 2.018 | 1.903 | 3.924 |
| 64 | flat map | 1.910 | 1.870 | 3.849 |
| 64 | utility `:merge` | 2.006 | 2.393 | 4.581 |
| 64 | `:multi` | 1.987 | 1.846 | 3.831 |

Retained-validator timings for one valid value were 90.7 / 88.9 / 88.4 / 100.5 ns per call respectively (7 samples of 100,000 calls). These are warm microbenchmarks without allocation/GC profiling; differences of a few nanoseconds do not justify a migration. Unrelated-registry enumeration was absent after the patch. Direct programmatic `mu/merge` + validator measured 1.198 / 5.997 / 8.217 ms at N=1/16/64 in a separate less-warmed JVM; do not interpret that cross-JVM difference as an API ranking.

Changing the registry provider while keeping the declarations gives a larger effect. At N=64 the candidate-style provider measured conjunction **4.717 ms**, 16,768 schema lookups; `mr/lazy-registry` measured **0.892 ms**, 82 provider resolutions. These counters cover the sample's compilation and three acceptance/refusal checks; candidate counts include built-in lookups, lazy counts include only provider misses, so their ratio is not a like-for-like operation speedup. The structural evidence is that the candidate provider recompiles each fetched form and the lazy provider retains it (`schema.clj:1134–1147`; `registry.cljc:81–95`). The scratch candidate emulates only its registry/compiler seam, without Seon's predicate binding or component widening; it is not a benchmark of the running owner.

Overlap falsification: with required integer `:probe/x` in the base and optional string `:probe/x` in the extension, `:and` rejects `{}`, whereas default `mu/merge` accepts both `{}` and `{:probe/x "x"}`. The latter is **not** accretive extension. Seon's existing refusal is intentional policy beyond generic Malli merging (`util.cljc:25–28`, `:75–101`; `schema/form.cljc:53–61`). Native `:merge` may be used after this policy is checked; default right-wins behavior must never silently substitute for it.

### Exactly three options, simplest first

| Option | Guarantee | Cost and migration | What is given up |
|---|---|---|---|
| **1. Keep authored `:and`; reuse compiled schemas — recommended.** | Exact whole-value intersection, including relational predicates; existing base-extension discovery, D13 identities, storage attributes and named output contracts remain intact. Fix compile reuse at its current owners. | **0 facet declarations change**. Measured 0.149 / 1.309 / 3.924 ms for N=1/16/64; lazy-provider conjunction at 64 was 0.892 ms. Fork scope fix plus projection-owned compiled-object acquisition, not a new error mechanism. | No automatic merged-map `m/entries` view; Seon still needs its checked structural map projection. Successful validation retains the second map check. |
| **2. Keep authored `:and`; derive one flat compiled map for compatible map arms.** | Same accepted values for checked compatible open map arms; original facet keys/reference edges remain authored. Preserve every non-map predicate as an outer conjunction and all properties at their rightful scope. The existing conflict/requiredness refusal remains admission policy. | **0 authored facet declarations change**, but compilation/expansion and equivalence proof change. Flat-map floor: 0.154 / 1.188 / 3.849 ms; normalization cost is **not included or measured**. Current census has 86 map-composition declarations to cover. | More Seon normalization code; proof obligation for overlap, predicates, explanations, transforms, and generators. General Malli `:and` is not interchangeable with a map merge. No measured reason to pay that cost now. |
| **3. Declare checked native `[:merge base delta]`, keeping predicate conjunctions outside.** | One native merged map; base keys authored once; explicit admission must refuse incompatible/weakening overlaps. Extend storage extraction, facet ancestry and wrapper output discovery before publication. | **86 composition sites in 59 resources** change; the 87th facet `:seon.test/expired` is a conjunction wrapper without a literal map and need not be rewritten. Utility compile: 0.199 / 1.007 / 4.581 ms. Install `mu/schemas` at all relevant compiler registries; update `schema/form.cljc:36–39,106`, `error.clj:1819`, `instrument.clj:651`, and shape consumers (`schema.clj:1582–1605`). | Greater migration and admission surface, changed schema/explanation structure; unguarded right-wins merging is disallowed. No demonstrated compile advantage over option 1. |

The census script applies the same ancestry condition as `facet-keys`: 3,213 forms, 87 facets in 59 resources; 86 contain an `:and` with a literal map arm. Grep cross-check: `rg -n -F ':seon.error/base' resources/seon/schemas | wc -l` = **86** and `rg -l -F ':seon.error/base' resources/seon/schemas | wc -l` = **59**. Grep counts literal references (including the base declaration), not transitive facet identities; the semantic count is the EDN census, whose script is included. A hand-written flat map is only the comparison control: copying all base memberships into each resource violates the single-base constraint. `:multi` is also a comparison control, not a fourth viable option: its single dispatch answer cannot replace the set-valued D13 operation (`error.clj:1806–1840`; `core.cljc:1896–1898`).

### What the stall lane resolves

`projection-compile-stall` owns the ref-scope fix and candidate/projection registry reuse investigation. Removing `mr/-schemas` from the core cycle key eliminates whole-registry enumeration at that seam; caching the complete enumeration eliminates repeated merge cost for actual enumeration callers. Memoizing compiled provider results eliminates another source of repeated schema construction. None of those require changing `:and` (`core.cljc:1943–1998`; `schema.clj:424–437,1134–1147`).

Those fixes do **not**, by themselves, retain a root schema across `valid-candidate-value?` calls, remove `projection-validator`'s recursive reconstruction, repair Seon's path-repeated declaration walk, change the constructor/wrapper semantics, or eliminate the generator's copied scope enumeration. The source shows separate mechanisms at those seams; the historical 320 s stack does not establish which one dominated. Cold gates and live adoption remain the owning orchestrator's proof (`schema.clj:1331–1407,1412–1422,3472–3509`; `generator.cljc:299–310`).

## Part 2 — ranked Malli usage inventory

Scope: all `malli.core`, `malli.registry`, `malli.util`, and `malli.generator` imports and compilation/validation invocations in `src/`, with `bin/` and `script/` searched for other aliases; resource declarations and the supplied audit-C findings. This is a source inventory of mechanisms and bounded probes, not a claim that every input/performance path of Seon was executed. “Bound” below is an operation-count bound unless a measured time is given. Existing issue search found the holder-predicate and map-extraction records; new findings stay in this one authorized note. No separate issue file was written.

| Rank | Verified pattern and source | Malli seam / measured or bounded cost | Fix and owner |
|---:|---|---|---|
| 1 | **Whole-registry cycle scope**: old `candidate-registry -schemas` merges defaults/forms per request (`schema.clj:1134–1147`); composite registries also merge on enumeration. | Old `core.cljc:1943–1950` enumerated per ref validator; `registry.cljc:59`. O(R × S) enumeration work for R ref constructions and S entries, plus hashing. Historical 127–193 s CPU is a sighting, not this probe's attribution. Patched probe: zero candidate enumerations. **Generator still has the copy** at `generator.cljc:299–310`. | **Existing stall lane**: core scope and registry reuse. Include generator scope/shadowed-recursion equivalence in its follow-up acceptance; otherwise a separate fork follow-up. Do not claim core-only repair fixes generator compilation. |
| 2 | **Fresh compilation at candidate validation**: `valid-candidate-value?` and explanation build a new registry every call (`schema.clj:3459–3486`). Config loops invoke it per member (`config.clj:258–281`); render's `valid-projection?` discards the projection down to forms and calls it (`render.clj:1078–1082`). | `core.cljc:2559–2572,2626–2668`: new Schema means new cache. Even reusing the same raw registry measured 5.380 ms/100, fresh registry 5.849, retained schema 0.037, retained validator 0.018. Thus “fresh reify is the only problem” is **falsified**. | **New schema/config/render integration lane**, after stall owner releases schema.clj: carry one candidate projection and retain schema/validator/explainer by key. Stall provider memoization helps but cannot rescue rebuilding the entire provider per call. |
| 3 | **Per-declaration full-registry allocation and discarded compiles**: `build-projection` calls `assert-compilable-schema!` for every form, which ignores the supplied registry and makes `fast-registry (assoc schemas k v)`; then compiles every form again (`schema.clj:1860–1870`; `schema/internal.cljc:359–367`). `materialize-projection` also discards its schema compilation results (`schema.clj:2299–2315`). | `registry.cljc:17–22` copies S entries into HashMap. S declarations imply S such copies: O(S²) entry copying before recursive compile costs. Scratch actual helper: 64 simple declarations against 3,208 forms took **35.131 ms**, shared-registry compilation **0.173 ms**; this is not a full-build estimate. | **New projection-construction lane**, coordinated with stall owner. Use the final candidate registry already handed to admission; retain compiled schemas from the validating pass. Keep exact diagnostics and invalid-declaration checks. No fork change necessary. |
| 4 | **Repeated dependency paths in contract admission**: `walk-schema` has an ancestor-path `visited` set, but no root-wide visited set; reference-advisories cache only a node's check, not traversal of its descendants (`schema.clj:1331–1379,1877–1905`). | Malli's walk can stop at named refs (`core.cljc:2003–2015`; Seon passes stop options at `schema/internal.cljc:202–205`); Seon then does its own reach traversal. Work is bounded by the number of reference **paths**, not distinct vertices; layered diamonds can have exponentially many paths. No timing or causal claim about the 320 s event. | **Existing stall lane's diagnostic scope**; if not repaired there, new declaration-walk lane. Reuse the existing canonical dependency graph and deduplicate per [reference, role, admission] while preserving emitted advisories and cycles. Do not add another graph registry. |
| 5 | **Repeated recursive dereference bypasses retained caches**: `projection-validator`/explainer rebuild through `m/deref-recursive` (`schema.clj:3500–3509,3586–3595`). Direct high-volume callers include every encoded logical slot (`schema/datahike.clj:382–386`) and pulled-result validation (`db.clj:2180–2194`). | `core.cljc:2834–2846` walks and resets children, constructing new schema objects. Probe: **12.618 ms/100** versus **0.018** retained validator. Pull-derived projection already caches by selector (`schema.clj:3016–3036`), but its validator call still rebuilds. | **New schema acquisition/consumer lane**, can join rank 2. Retain compiled root under the existing projection holder and use public `m/validator`/`m/explainer`; verify whether dereferencing is needed for explanation paths before deleting it. |
| 6 | **Generator binding does work during Seon's compile preprocessing**: `compilable-form` resolves every symbolic `:gen/gen` with `requiring-resolve` and walks forms; it also hoists predicate generators to conjunction properties (`schema.clj:258–275`). | Malli's validator does not run generation properties; `generator.cljc:458–503` consumes them only on generator acquisition. Probe: 1,000 compiles invoked the sentinel generator **0** times, plain/metadata 0.310/0.311 ms. Seon's resolution bound is one resolution per encountered symbolic generator property per traversal; first namespace load can be substantial, unmeasured here. | **New schema/generator binding lane** if repeated resolution remains after compiled-provider reuse. Preserve generator objects and whole-value generators; bind them once in the projection's existing preprocessing, not on every lookup. Do not delete production generator metadata as a supposed validator optimization. |
| 7 | **Wrapper is stricter than “satisfies one declared facet”**: actual facets outside the declared set cause refusal even when a declared open facet validates (`instrument.clj:778–790`); D12/PRD says a value satisfying none is refused (`error-conversion-prd:100–106`). | Malli open maps accept extra fields (`core.cljc:1284–1287`). Scratch two-facet example validates output A while the wrapper difference is #{B}. It also invokes all F facet validators per returned base observation (`error.clj:1830–1840`) in addition to named-output validation. | **Existing error-family owner / design decision**, not the stall lane. State whether all additional satisfied facets must be declared; do not silently relax that policy in a performance patch. If the intended guarantee is at-least-one, use the declared output's validation as authority while still deriving all facets for D13. |
| 8 | **Constructor drops supplied domain fields**: current `error/diagnostic` destructures only base/diagnostic/data members and constructs a fresh map (`error.clj:323–355`), while the conversion PRD passes domain members into that call (`error-conversion-prd:47–62`). | A missing required domain member correctly fails `-map-schema` (`core.cljc:1270–1279`); neither `:and` nor `:merge` can restore it. Source proves extra top-level fields are discarded. No running-constructor or armed-fixture proof claimed. | **Existing error-family constructor owner**, already changing the leaf seam per PRD `:74–86`. Preserve/merge owner-supplied facet data at the one constructor; verify the PRD example produces a complete facet. Independent of composition and compile stall. |
| 9 | **Raw map expansion repeats**: `database-attributes` asks map-shape, properties, entries separately; each re-expands aliases/conjunctions (`schema/form.cljc:24–95,130–149`). Compiled map-shape/required-entry readers are another semantic view (`schema.clj:1582–1605`). | `m/entries` is available for native maps; generic `:and` has children, not merged entries (`core.cljc:825–865,1267`). Scratch 64 facets plus padding: 10 storage-discovery calls **23.173 ms**, flat forms **20.227 ms**. Bounded by repeated traversal of expansion paths; no evidence this accounts for minutes. | **New declaration-projection cleanup lane**, after stall work. Derive checked map entries once per immutable declaration projection and have storage, requiredness and render readers consume that answer. Keep original contracts/predicates; do not flatten arbitrary conjunctions. |
| 10 | **Remaining direct raw compile consumers**: render nil-output test (`render.clj:1251–1256`), maintenance keys (`schedule.clj:497–504`), error correction/expected-schema rendering (`error.clj:1094–1111,1164–1173`), publication schema shape (`program.cljc:744–749`), accretion schema-row (`test/accretion.clj:40–50`). | At least one compile per reached call/row under `core.cljc:2550–2572`; accretion schema-row also copies the full forms map (`registry.cljc:17–22`) and builds a generator (`generator.cljc:496–503`). No traffic frequency measured. Rank 2's microbench gives scale only for its measured schema. | **New consumer reuse lane**, file ownership split after current render lane lands. Use projection-bound compiled schema acquisition; cache selector/key metadata where it is derived from that same immutable projection. Publication and auto-check are allowed to compile new declarations, but should share already compiled ones within a pass. |
| 11 | **Value alias loses modeling constraints where specificity is required**: audit C's 31 references were its slice, not the whole repository (`schema-audit-c-2026-09-19.md:465–480`). `seon.schema/value` is exempt `:any` (`resources/seon/schemas/seon.schema.edn:44–46`). Current EDN census finds **86 literal occurrences**, not 86 proven defects. | Malli correctly implements `:any` as `any?` (`core.cljc:812`); O(1) acceptance of nil/host objects, no field constraint. Seon's advisory logic recognizes bare-value inputs but alias expansion differs by stored/input position (`schema/internal.cljc:30–78`). Some uses really are heterogeneous, e.g. observed definition values. | **New modeling/admission lane**, extending audit C16. Review each use's domain guarantee; strengthen specific member types and require a reason at the actual polymorphic boundary. No Malli fork fix, no blanket deletion of valid polymorphism. |
| 12 | **Effectful schema predicate**: `call-with-projection-state` declares `[:fn clojure.core/deref]` (`schema.clj:1071`). Existing issue is open: `docs/seon/issues/schema-projection-state-contract-invokes-deref-as-a-predicate.md:9–27`. | `-fn-schema` invokes its predicate (`core.cljc:1761–1806`), catching predicate failure rather than proving it pure. Dereferencing a future/delay can execute or wait without a finite Malli bound. Acceptance depends on the held value's truthiness. | **Existing schema/SCI holder-contract issue owner**. Declare the actual holder predicate, covering both production holder shapes. A timeout wrapper or `:any` substitution does not fix this model. |

### Suspicions verified or falsified; cache ownership

**Per-wrapper config compilation is repaired on the normal JVM arming path.** `apply!` creates one delayed defaults value before its Var loop and passes a policy (`instrument.clj:983–1003`); `compiled-wrapper` consumes it and no longer calls `config/defaults` (`:735–762`). This agrees with `5ad9ea70c` and the supplied landing note (`publication-projection-repair-2026-09-19.md:64–96`). Residual: the short `wrap-interpreted` arity supplies `{}` and the longer arity falls back to defaults when the evidence cap is absent (`instrument.clj:477–519`). Normal SCI installation supplies the acquired cap (`sci/eval.clj:674–689`). Cost is zero fallback compiles when provided, at most one defaults acquisition per deficient wrap request. Preserve this distinction; do not report the historical bug as still universal.

**Wrapper validators compile once per retained wrapper key, not once per invocation.** `compiled-wrapper` is inside `projection-cache-value` keyed by function/authored/original/policy (`instrument.clj:735–740`), and its Malli call builds input/output validators before returning the function (`core.cljc:2202–2208`). `arm-var!` may call the accessor per invocation but hits the projection's holder (`instrument.clj:876–890`). A new supplied projection or changed key justifiably compiles anew. Bootstrap compilation is separately delayed. The all-facet value scan remains real runtime work (rank 7).

**There are two levels of cache, with different ownership—not inherently two caches for one thing.** Malli caches operations on a compiled Schema; Seon's holder keeps values belonging to an immutable projection, including compiled function arities, facet lists, write validators, wrapper functions and selector derivations (`schema.clj:321–380`; `db.clj:3247–3251`; `error.clj:1806–1837`). Retain that projection ownership. The avoidable duplication is rebuilding schemas and caching separate validators/explainers in multiple consumers. A single compiled-schema acquisition per projection/key lets Malli's own `:validator`/`:explainer` caches do their job. `cached-compiler-in!` also compiles before its swap, so concurrent misses can duplicate finite work (`schema.clj:3684–3690`), whereas `projection-cache-value` installs/selects a delay before forcing (`:373–384`). Reuse the latter seam; do not add a process-global registry or key a cache only by schema name.

`mr/lazy-registry` is useful but not a drop-in complete catalog: it exposes only realized provider values plus defaults on enumeration (`registry.cljc:81–95`). Nor is repeated `m/validator :key` guaranteed to reuse a root validator when the provider returns a compiled object: keyword resolution may still create a fresh pointer (`core.cljc:2570–2571`). Retain the resolved compiled root itself (or its validator) in the existing projection holder. The scope key must distinguish local shadowing and changed immutable projections; a mutable registry with a stable identity is not a safe replacement for Seon's immutable-world contract.

### Exhaustive direct-call classification for the searched production surface

The command `rg -n '\(m/(validate|explain|schema|validator|explainer|function-schema|deref-recursive)\b' src --glob '!schema.clj'` returned 22 lines, including two `schema?` inspection matches. Grouping all of them prevents a grep hit from being mislabeled as a defect:

| Calls | Classification and bound |
|---|---|
| `instrument.clj:293,704,754` | Explain already compiled contracts at refusal time; wrapper compilation is cached. No per-success-call compile defect. |
| `db.clj:3251,3268` | Write validator is cached; error explanation compiles on refusal. Retaining one compiled form would share both, but no unmeasured hot-path claim. |
| `error.clj:47,947,1095,1169` | First is a generator object created at namespace load; second validates an already compiled child. Last two recompile diagnostic schemas (rank 10). |
| `render.clj:1253`; `schedule.clj:503` | Raw contract/schema compile per reached request (rank 10). |
| `program.cljc:580,672,747` | `580` is only `schema?`. Function/schema facts compile at publication; compile-on-new-input is legitimate, reuse within the same projection/pass is the opportunity (`:672`, `:747`). |
| `sci/eval.clj:1404` | Documentation contract compile is inside `projection-cache-value` (`:1401–1405`); not per documentation request after first use. |
| `call_preparation.clj:579` | Argument validators are installed in the derived arity plan (`:801–810`); `plan` is cached (`:901`). Not compiled on each prepared call. Supplier value validators similarly acquired once in the snapshot (`:400–418`). |
| `render/ns.clj:146` | Structural reference discovery compiles against an opaque-reference registry, not the full application definitions (`:125–155`). Bounded display closure cap 40 (`:123`). Its catch returns partial/empty refs on invalid syntax; keep “unavailable” distinct from no refs in a future UI-owner cleanup. |
| `schema/internal.cljc:366` | Full-map allocation per compile admission, rank 3. |
| `test/accretion.clj:48,109,159` | Schema-row generatability recompiles per row (rank 10); explanation receives compiled output; auto-check builds a function schema/generators before its generated-case loop (`:159–179`), not each case. |
| `fn/schema_shape.clj:85` | `schema?` inspection, not compilation. |

The indirect consumers in `schema/datahike.clj:384`, `db.clj:2193`, and `render.clj:1081` are more important than several direct grep hits. `print.cljc:923–938` deliberately retains one shipped node-face validator in a delay; it is not the same per-call compile defect. Its assumption that that definition is immutable across live edits is explicit there and was not live-probed in this assignment.

## Suggested ownership and verification

Keep core ref scope, candidate/projection provider reuse, and the `fold-contract-validations` stall with **projection-compile-stall**. Generator scope is the same fork class; record it for that owner's continuation rather than editing in parallel. After that owner releases schema.clj, one schema acquisition lane can address ranks 2, 3 and 5 together: compiled-schema ownership once, existing caller caches simplified. A separate modeling/error owner decides ranks 7, 8 and 11; existing holder issue owns rank 12. Rank 9 is lower priority given its measured size. These are proposed assignments, not lane launches.

Future production acceptance must use canonical armed fixtures and prove: same accepted/rejected maps; conflicting/optionalized base members refuse; read relational predicates still run; base/facet storage attributes match; D13 keys/hash remain stable for identical observations; named outputs refuse incomplete/undeclared failures under the settled wrapper rule; local recursive registries and shadowed names do not share the wrong validator; a changed projection does not reuse its predecessor's schema; repeated acquisition reuses compilation; generator recursion still terminates. These obligations follow the cited owners, not a new handwritten error classifier. The scratch timings below establish design scale only.

## Appendix A — full scratch composition script

Executed as `tmp/error-composition-review/probe.clj`; retained verbatim below.

```clojure
(require '[clojure.edn :as edn] '[clojure.java.io :as io]
         '[clojure.walk :as walk] '[malli.core :as m]
         '[malli.util :as mu] '[malli.registry :as mr]
         '[malli.generator :as mg])
(load-file "tmp/error-composition-review/form.cljc")
(require '[seon.schema.form :as sf])
(defn sha [s]
  (format "%064x" (java.math.BigInteger. 1
    (.digest (java.security.MessageDigest/getInstance "SHA-256")
             (.getBytes s "UTF-8")))))
(def paths ["resources/seon/schemas/seon.error.edn"
            "resources/seon/schemas/seon.db.edn"
            "resources/seon/schemas/seon.test.edn"])
(def inputs (into {} (map (fn [p] [p (slurp p)]) paths)))
(def forms (apply merge (map edn/read-string (vals inputs))))
(def base (get forms :seon.error/base))
(def facet (get forms :seon.test/unknown-error))
(assert (= facet [:and :seon.error/base [:map [:seon.test/unknown :seon.test/unknown]]]))
(def delta (last facet))
(def flat (into base (rest delta)))
(def support
  (loop [pending [:seon.error/base :seon.test/unknown] seen #{}]
    (if-let [k (peek pending)]
      (if (seen k) (recur (pop pending) seen)
        (recur (into (pop pending)
                     (filter #(and (keyword? %) (contains? forms %))
                             (tree-seq coll? seq (get forms k))))
               (conj seen k)))
      (select-keys forms seen))))
(def defaults (merge (m/default-schemas) (mu/schemas) {:inst 'inst?}))
(def value {:seon.error/at (java.util.Date. 0)
            :seon.error/layer :probe/read :seon.error/operation 'probe/read
            :seon.test/unknown "missing" :probe/extra 1 :probe/dispatch 0})
(def calls (atom {}))
(defn registry [kind fs]
  (let [d (mr/fast-registry defaults)]
    (case kind
      :simple (mr/simple-registry (merge defaults fs))
      :candidate (reify mr/Registry
                   (-schema [this k]
                     (swap! calls update :lookups (fnil inc 0))
                     (or (mr/-schema d k)
                         (when-let [f (get fs k)] (m/schema f {:registry this}))))
                   (-schemas [_]
                     (swap! calls update :enumerations (fnil inc 0))
                     (merge defaults fs)))
      :lazy (mr/lazy-registry d
              (fn [k r]
                (swap! calls update :provider (fnil inc 0))
                (when-let [f (get fs k)] (m/schema f {:registry r})))))))
(defn named [i] (keyword "probe.facet" (str "f" i)))
(defn population [kind n]
  (merge support
    (into {} (for [i (range 3208)] [(keyword "probe.padding" (str "p" i)) :string]))
    (into {} (for [i (range n)]
      [(named i) (case kind :and facet :flat flat
                          :merge [:merge :seon.error/base delta]
                          :multi flat)]))
    (when (= kind :multi)
      {:probe/all (into [:multi {:dispatch :probe/dispatch}]
                       (for [i (range n)] [i (named i)]))})))
(defn median [xs] (nth (vec (sort xs)) (quot (count xs) 2)))
(defn ms [f] (let [t (System/nanoTime)] (f) (/ (- (System/nanoTime) t) 1e6)))
(defn sample [registry-kind kind n]
  (let [fs (population kind n)
        r (registry registry-kind fs)
        roots (if (= kind :multi) [:probe/all] (mapv named (range n)))
        t (System/nanoTime)
        ss (mapv #(m/schema % {:registry r}) roots)
        t2 (System/nanoTime)
        vs (mapv m/validator ss)
        t3 (System/nanoTime)]
    (assert (every? #(% value) vs))
    (assert (every? #(not (% (dissoc value :seon.error/operation))) vs))
    (assert (every? #(not (% (assoc value :seon.test/unknown 9))) vs))
    {:schema-ms (/ (- t2 t) 1e6) :validator-ms (/ (- t3 t2) 1e6)
     :total-ms (/ (- t3 t) 1e6)}))
(println :environment {:java (System/getProperty "java.version")
 :clojure (clojure-version) :core-url (str (io/resource "malli/core.cljc"))
 :core-sha (sha (slurp (io/resource "malli/core.cljc")))
 :form-sha (sha (slurp "tmp/error-composition-review/form.cljc"))
 :inputs (into {} (map (fn [[p s]] [p (sha s)]) inputs))})
(println :actual-base base :actual-facet facet)
(dotimes [_ 40] (doseq [kind [:and :flat :merge :multi]] (sample :simple kind 16)))
(doseq [n [1 16 64] kind [:and :flat :merge :multi]]
  (dotimes [_ 5] (sample :simple kind n))
  (let [xs (repeatedly 11 #(sample :simple kind n))]
    (println :compile n kind
      (into {} (for [k [:schema-ms :validator-ms :total-ms]]
                 [k (median (map k xs))])))))
(doseq [rk [:candidate :lazy] kind [:and :flat :merge :multi]]
  (dotimes [_ 3] (sample rk kind 64))
  (let [xs (doall (repeatedly 7 #(sample rk kind 64)))]
    (reset! calls {})
    (sample rk kind 64)
    (println :registry rk kind {:total-ms (median (map :total-ms xs)) :calls @calls})))
(doseq [kind [:and :flat :merge :multi]]
  (let [fs (population kind 64) r (registry :simple fs)
        root (if (= kind :multi) :probe/all (named 0))
        v (m/validator (m/schema root {:registry r}))]
    (dotimes [_ 20000] (v value))
    (println :validate kind :ns-per-call
      (* 10 (median (repeatedly 7 #(ms (fn [] (dotimes [_ 100000] (v value))))))))))
(let [fs (population :and 1) r (registry :candidate fs)
      s (m/schema (named 0) {:registry r}) v (m/validator s)
      cases {:raw-shared #(m/validate (named 0) value {:registry r})
             :raw-fresh #(m/validate (named 0) value {:registry (registry :candidate fs)})
             :schema-shared #(m/validate s value)
             :validator-shared #(v value)
             :recursive-deref #((m/validator (m/deref-recursive (named 0) {:registry r})) value)}]
  (println :cache-identical {:same-schema (identical? (m/validator s) (m/validator s))
                            :same-key (identical? (m/validator (named 0) {:registry r})
                                                  (m/validator (named 0) {:registry r}))})
  (doseq [[k f] (sort-by key cases)]
    (dotimes [_ 20] (assert (f)))
    (reset! calls {})
    (f)
    (let [counts @calls]
      (println :cache k :ms-per-100
        (median (repeatedly 7 #(ms (fn [] (dotimes [_ 100] (assert (f)))))))
        :calls-per-use counts))))
(let [a [:map [:probe/x :int]]
      b [:map [:probe/x {:optional true} :string]]]
  (println :overlap {:and-admits-empty (m/validate [:and a b] {})
                    :merge-admits-empty (m/validate (mu/merge a b) {})
                    :merge-admits-string (m/validate (mu/merge a b) {:probe/x "x"})}))
(let [fs (population :and 64)
      flat-fs (population :flat 64)
      f #(sf/database-attributes fs)
      ff #(sf/database-attributes flat-fs)]
  (assert (= (f) (ff)))
  (println :walker {:and-ms-per-10 (median (repeatedly 7 #(ms (fn [] (dotimes [_ 10] (f))))))
                    :flat-ms-per-10 (median (repeatedly 7 #(ms (fn [] (dotimes [_ 10] (ff))))))}))
(let [hits (atom 0)
      props {:gen/gen (fn [] (swap! hits inc)) :gen/elements [1 2]}
      with-props [:int props]]
  (dotimes [_ 1000] (assert ((m/validator (m/schema with-props)) 1)))
  (println :generator {:invocations @hits
    :plain-ms-per-1000 (median (repeatedly 7 #(ms (fn [] (dotimes [_ 1000] (m/validator (m/schema :int)))))))
    :props-ms-per-1000 (median (repeatedly 7 #(ms (fn [] (dotimes [_ 1000] (m/validator (m/schema with-props)))))))}))
(let [files (sort-by str (filter #(.endsWith (str %) ".edn")
                         (file-seq (io/file "resources/seon/schemas"))))
      pairs (mapv (fn [f] [f (edn/read-string (slurp f))]) files)
      all (apply merge (map second pairs))
      facets (for [[f fs] pairs [k v] fs
                   :when (and (vector? v) (= :and (first v))
                              (sf/extends-schema? all v :seon.error/base))] [(str f) k])]
  (println :census {:forms (count all) :facets (count facets)
                    :resources (count (set (map first facets)))})
  (println :facet-inventory (vec (sort-by (comp str second) facets))))
(shutdown-agents)
```

## Appendix B — full scratch audit script

Executed as `tmp/error-composition-review/audit-probe.clj`.

```clojure
(require '[clojure.edn :as edn] '[clojure.java.io :as io]
         '[malli.core :as m] '[malli.util :as mu] '[malli.registry :as mr])
(load-file "tmp/error-composition-review/form.cljc")
(load-file "tmp/error-composition-review/internal.cljc")
(defn median [xs] (nth (vec (sort xs)) (quot (count xs) 2)))
(defn ms [f] (let [t (System/nanoTime)] (f) (/ (- (System/nanoTime) t) 1e6)))
(let [forms (into {} (for [i (range 3208)] [(keyword "probe" (str "s" i)) :string]))
      r (mr/composite-registry (m/default-schemas) (mr/fast-registry forms))]
  (doseq [n [1 16 64]]
    (let [ks (take n (sort (keys forms)))
          current #(doseq [k ks] (seon.schema.internal/assert-compilable-schema! forms k :string {:registry r}))
          reused #(doseq [k ks] (m/schema k {:registry r}))]
      (dotimes [_ 3] (current) (reused))
      (println :compile-admission n
        {:current-ms (median (repeatedly 9 #(ms current)))
         :reuse-ms (median (repeatedly 9 #(ms reused)))}))))
(let [read-resource #(edn/read-string (slurp (str "resources/seon/schemas/" % ".edn")))
      fs (merge (read-resource "seon.error") (read-resource "seon.db") (read-resource "seon.test"))
      base (:seon.error/base fs) delta (last (:seon.test/unknown-error fs))
      r (mr/simple-registry (merge (m/default-schemas) (mu/schemas) {:inst 'inst?} fs))
      run #(do (m/validator (mu/merge base delta {:registry r})) nil)]
  (doseq [n [1 16 64]]
    (dotimes [_ 10] (run))
    (println :direct-util-merge n :total-ms
      (median (repeatedly 9 #(ms (fn [] (dotimes [_ n] (run)))))))))
(let [base [:map [:probe/at :int]]
      a [:and base [:map [:probe/a :string]]]
      b [:and base [:map [:probe/b :string]]]
      x {:probe/at 1 :probe/a "a" :probe/b "b"}
      actual (into #{} (keep (fn [[k s]] (when (m/validate s x) k))) {:probe/a a :probe/b b})]
  (println :open-facets {:malli-output-a (m/validate a x)
                         :satisfied actual
                         :wrapper-difference (clojure.set/difference actual #{:probe/a})}))
(let [files (sort-by str (filter #(.endsWith (str %) ".edn")
                         (file-seq (io/file "resources/seon/schemas"))))
      pairs (mapv (fn [f] [f (edn/read-string (slurp f))]) files)
      all (apply merge (map second pairs))
      facets (for [[f fs] pairs [k v] fs
                   :when (and (vector? v) (= :and (first v))
                              (seon.schema.form/extends-schema? all v :seon.error/base))] [f k v])
      map-arms (fn [v]
                 (let [hits (atom 0)]
                   (clojure.walk/postwalk
                    (fn [x] (when (and (vector? x) (= :and (first x))
                                        (some #(and (vector? %) (= :map (first %))) (rest x)))
                              (swap! hits inc)) x) v) @hits))]
  (println :migration {:facets (count facets) :map-compositions (reduce + (map #(map-arms (nth % 2)) facets))
                       :no-map-facets (vec (for [[_ k v] facets :when (zero? (map-arms v))] k))})
  (println :value-alias {:literal-occurrences
    (reduce + (for [[_ fs] pairs]
       (let [hits (atom 0)]
         (clojure.walk/postwalk (fn [v] (when (= v :seon.schema/value) (swap! hits inc)) v) fs) @hits)))})
  (println :facet-files (vec (sort (set (map (comp str first) facets))))))
(shutdown-agents)
```

## Appendix C — invocation, version pin, and exact measurements

Scratch setup copied the in-flight core source and the two pure Seon inspection namespaces; no application owner was loaded. These commands describe the executed setup:

```sh
mkdir -p tmp/error-composition-review/vendor/malli
cp reference-code/malli/src/malli/core.cljc tmp/error-composition-review/vendor/malli/core.cljc
cp src/seon/schema/form.cljc tmp/error-composition-review/form.cljc
cp src/seon/schema/internal.cljc tmp/error-composition-review/internal.cljc
```

The two JVM invocations used this bounded Python harness (run separately, with the script bodies above):

```python
import subprocess
jobs = [
    ("probe.clj", "results.edn", 180,
     '(load-file "tmp/error-composition-review/probe.clj")'),
    ("audit-probe.clj", "audit-results.edn", 120,
     '(require (quote clojure.set) (quote clojure.walk)) '
     '(load-file "tmp/error-composition-review/audit-probe.clj")'),
]
for script, output, bound, expression in jobs:
    with open("tmp/error-composition-review/" + output, "w") as stream:
        result = subprocess.run(
            ["clojure", "-Sdeps",
             '{:paths ["tmp/error-composition-review/vendor" "src" "resources"]}',
             "-M", "-e", expression],
            stdout=stream, stderr=subprocess.STDOUT, timeout=bound)
        print(script, result.returncode)
```

Both exit codes were 0. The pinned core copy differs from `3517a3cd9271b2083780ac7be1725493905bca2e` by exactly this already-existing foreign patch; this lane did not apply it to the fork:

```diff
--- malli/core.cljc at 3517a3c
+++ pinned probe core.cljc
@@ -1944,6 +1944,5 @@
-  ;; TODO mr/-schemas doesn't seem right, making defn private for now.
-  ;; e.g., we only care about property registry entries, not schema constructors.
-  ;; a better approach might be to accumulate a 'seen' map from name => ?schema
-  ;; that we add to every time we deref a ref, and if we expand the same name again
-  ;; with the same seen map, it's a cycle.
-  {:scope (-> schema -options -registry mr/-schemas)
+  ;; Registry instances carry the lookup scope, including property registries.
+  ;; Enumerating and hashing every declaration here makes each ref pay for the
+  ;; entire registry. Keep the actual scope that resolves this ref instead.
+  ;; Keep raw map registries too: -registry would wrap those afresh per call.
+  {:scope (or (-> schema -options :registry) default-registry)
```

Exact final composition output follows; the long per-facet inventory line is omitted because the census and full regenerating script are retained. Output is Clojure `println` text, not a claimed EDN serialization.

```text
:environment {:java 26.0.1, :clojure 1.12.5, :core-url file:/Users/sean/src/seon/tmp/error-composition-review/vendor/malli/core.cljc, :core-sha 16e12300964c46a8652967cc6fc7f9c5ddb27579199500badb7a3fe0d54e0c3d, :form-sha 96f1899e23615b5e031e07766e7e58100b07066cc5e003d2877b621cfd14e6d3, :inputs {resources/seon/schemas/seon.error.edn 300d60c5dbf5affd761f501dc69a9cf403c82b85739748b4b203a32fec230f0a, resources/seon/schemas/seon.db.edn 9b6a30e6905d9ead2ee79271b4951f386056cfe9ccc85b40a22b1cff647978e8, resources/seon/schemas/seon.test.edn 31e0f25967971309a0c6fcee38a31f9af0fb8d45afb2aa1cd2a9e37ad457166f}}
:actual-base [:map #:seon.db{:attributes true} [:seon.error/at :seon.error/at] [:seon.error/layer :seon.error/layer] [:seon.error/operation :seon.error/operation] [:seon.error/message {:optional true} :seon.error/message] [:seon.error/member {:optional true} :seon.error/member] [:seon.error/expected-key {:optional true} :seon.error/expected-key] [:seon.error/expected-shape {:optional true} :seon.error/expected-shape] [:seon.error/location {:optional true} :seon.error/location] [:seon.error/offending-projection {:optional true} :seon.error/offending-projection] [:seon.error/evidence-items {:optional true} :seon.error/evidence-items] [:seon.error/evidence-unavailable {:optional true} :seon.error/evidence-unavailable] [:seon.error/cause {:optional true} :seon.error/cause] [:seon.error/fix {:optional true} :seon.error/fix] [:seon.error/basis {:optional true} :seon.error/basis]] :actual-facet [:and :seon.error/base [:map [:seon.test/unknown :seon.test/unknown]]]
:compile 1 :and {:schema-ms 0.092583, :validator-ms 0.05625, :total-ms 0.148833}
:compile 1 :flat {:schema-ms 0.078916, :validator-ms 0.060917, :total-ms 0.154292}
:compile 1 :merge {:schema-ms 0.11425, :validator-ms 0.08425, :total-ms 0.199333}
:compile 1 :multi {:schema-ms 0.088166, :validator-ms 0.052666, :total-ms 0.138791}
:compile 16 :and {:schema-ms 0.737042, :validator-ms 0.556833, :total-ms 1.308791}
:compile 16 :flat {:schema-ms 0.67825, :validator-ms 0.500625, :total-ms 1.187667}
:compile 16 :merge {:schema-ms 0.513875, :validator-ms 0.492166, :total-ms 1.006541}
:compile 16 :multi {:schema-ms 0.505834, :validator-ms 0.417333, :total-ms 0.911791}
:compile 64 :and {:schema-ms 2.018333, :validator-ms 1.90325, :total-ms 3.923917}
:compile 64 :flat {:schema-ms 1.909708, :validator-ms 1.87, :total-ms 3.848916}
:compile 64 :merge {:schema-ms 2.005584, :validator-ms 2.3925, :total-ms 4.581167}
:compile 64 :multi {:schema-ms 1.986625, :validator-ms 1.845542, :total-ms 3.831083}
:registry :candidate :and {:total-ms 4.716708, :calls {:lookups 16768}}
:registry :candidate :flat {:total-ms 3.7595, :calls {:lookups 16576}}
:registry :candidate :merge {:total-ms 4.286708, :calls {:lookups 16832}}
:registry :candidate :multi {:total-ms 3.77125, :calls {:lookups 16578}}
:registry :lazy :and {:total-ms 0.891583, :calls {:provider 82}}
:registry :lazy :flat {:total-ms 1.103417, :calls {:provider 81}}
:registry :lazy :merge {:total-ms 1.169834, :calls {:provider 82}}
:registry :lazy :multi {:total-ms 1.205209, :calls {:provider 82}}
:validate :and :ns-per-call 90.74208999999999
:validate :flat :ns-per-call 88.92333
:validate :merge :ns-per-call 88.36957999999998
:validate :multi :ns-per-call 100.47792
:cache-identical {:same-schema true, :same-key false}
:cache :raw-fresh :ms-per-100 5.8485 :calls-per-use {:lookups 262}
:cache :raw-shared :ms-per-100 5.379875 :calls-per-use {:lookups 262}
:cache :recursive-deref :ms-per-100 12.617833 :calls-per-use {:lookups 262}
:cache :schema-shared :ms-per-100 0.036667 :calls-per-use {}
:cache :validator-shared :ms-per-100 0.017542 :calls-per-use {}
:overlap {:and-admits-empty false, :merge-admits-empty true, :merge-admits-string true}
:walker {:and-ms-per-10 23.1725, :flat-ms-per-10 20.226791}
:generator {:invocations 0, :plain-ms-per-1000 0.310167, :props-ms-per-1000 0.311333}
:census {:forms 3213, :facets 87, :resources 59}
```

Exact supplementary audit output (the repeated file inventory is omitted):

```text
:compile-admission 1 {:current-ms 2.048583, :reuse-ms 0.019042}
:compile-admission 16 {:current-ms 8.716083, :reuse-ms 0.050167}
:compile-admission 64 {:current-ms 35.131166, :reuse-ms 0.172791}
:direct-util-merge 1 :total-ms 1.198125
:direct-util-merge 16 :total-ms 5.996875
:direct-util-merge 64 :total-ms 8.217416
:open-facets {:malli-output-a true, :satisfied #{:probe/b :probe/a}, :wrapper-difference #{:probe/b}}
:migration {:facets 87, :map-compositions 86, :no-map-facets [:seon.test/expired]}
:value-alias {:literal-occurrences 86}
```

The final script SHA-256 values are `45993da44e7b09e42bd5d5133206f4e68c660efc21e974689becff867f28f67f` (composition) and `a40cabc7b015dd183a7cdcdf7043d91b3e660051a109f27ecf3a025694034bd0` (audit). The copied pure admission helper has SHA-256 `0546e3504b58b3515db1026d4a4acb479d9df3174f936d683aaed871b5ae7c30`; base/facet resource and walker digests are in the environment output. No core schema/validator result was obtained from a cluster.

The supplementary helper comparison uses the actual `seon.schema.internal/assert-compilable-schema!` loaded from its copied source, with a synthetic scalar population. The compared reuse path preserves the same valid scalar checks, but this is not proof of identical diagnostics for every invalid form. The generator sentinel deliberately is not a production generator: its sole purpose is to detect invocation during `m/schema`/`m/validator`; generation itself was not called in that check. Whole-value generator behavior still requires the owner's real generator regressions.
