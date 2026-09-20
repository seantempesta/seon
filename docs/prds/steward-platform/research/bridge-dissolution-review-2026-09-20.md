---
type: research
status: complete
created: 2026-09-20
tags: [schema, malli, datahike, projection, design-review]
---

# Dissolving repeated work in the schema/database bridge

**Recommendation: option 2, reached through a small option-1 first slice.** Keep Seon's declaration policy and final-state writer refusal. Make retained compiled Malli schemas the executable authority of one generation value; stop reconstructing that authority at ordinary reads. Do not replace intersection with merge, remove the whole-entity validator, or start a new declaration language. These conclusions follow from the compilation probe below, the existing carried-value mechanism (`src/seon/db.clj:232`), and the dependency's final-report seam (`reference-code/datahike/src/datahike/db/transaction.cljc:1206`).

The answer to the owner's question is **yes: we create substantial work by repeatedly translating and rediscovering the same declarations**. The bridge also supplies guarantees neither dependency supplies: complete owned values, required-ref deletion refusal, and surviving program-referrer checks. Dissolve the repeated interpretation, not those guarantees (`src/seon/schema.clj:1871`; `src/seon/db.clj:3513`, `:3873`, `:3890`).

## Scope, authority, and evidence boundary

Read **AGENTS.md §§1–4 end to end**, including “Prefer dissolution to addition”; `.agents/skills/data-modeling/SKILL.md` and `.agents/skills/datahike/SKILL.md` end to end; also the data-oriented Clojure skill. Read these six named evidence notes end to end:

- `docs/prds/steward-platform/research/error-composition-review-2026-09-20.md` — especially Part 2, `:101`.
- `docs/prds/steward-platform/research/projection-compile-stall-2026-09-20.md` — measured comparison, `:100`.
- `docs/prds/steward-platform/research/adoption-silence-diagnosis-2026-09-19.md` — sampled CPU and observation limits, `:29`, `:58`.
- `docs/prds/steward-platform/research/publication-projection-repair-2026-09-19.md` — lost construction projection and repaired wrapper acquisition, `:26`, `:66`.
- `docs/prds/steward-platform/research/writer-hang-root-cause-2026-09-18.md` — inclusive phase measurements, `:128`.
- `docs/prds/steward-platform/research/entity-schema-vs-pulled-shape-2026-09-16.md` — stored entity, transaction and selected pull shapes are different grammars.

Read the three audits' bridge-relevant classes: audit A `:192–233` (lost declaration documentation and unregistered properties), B `:69–144` and `:321–362` (owned/error shape and storage distinctions), C `:166–211`, `:359–483` (render union, inert cardinality, polymorphic alias). Read the data-modeling guide's deletion/component rulings (`docs/seon/architecture/data-modeling-guide.md:62`, `:241`, `:301`) and the current roadmap entry (`docs/prds/steward-platform/plan/README.md:10`).

This is a **source review plus unarmed scratch compilation probes**, not a canonical-fixture, cluster, writer-load, adoption or gate proof. No cluster was contacted, no `bin/test` or `bin/test-fast` ran, no worktree was needed, and no other lane was operated. The only authored file is this note. Findings that would ordinarily get issue files are recorded here under the assignment's single-file restriction.

The checkout changed concurrently: initial HEAD `f0fd6dbf889d961dcf6b34ddac022d523a8aec63`, later `c50625da46c2e94783a11e392508f00d94b197a6`. `schema.clj` grew from 3,854 to **3,873 lines, still 159 top-level defn forms**. The line references below use the later observed working-tree bytes, unless expressly historical. SHA-256 prefixes from the source census: schema `0871cbf9c03829a1`, form `96f1899e23615b5e`, internal `167b637638af5f9e`, admission `e8f5c04c663f3376`, datahike bridge `e4291b3dc28dd807`, edn `56fde0c05231d877`, db `425899894f95cd2b`, instrument `8630414379c33b36`. This is a mixed, changing working tree, not a claim that one commit contains it all. Foreign changes did not prevent the probes from loading.

Dependency pins observed: Malli `606083c5c5b388e84d169c7080af33ed3ec242ae`; Datahike `e11845bac78e1241bca0766ddc07d978bd63d74a`. Probe B printed the actual Malli resource URL under `reference-code/malli`, Java **26.0.1**, Clojure **1.12.5**. Historical writer measurements used their own recorded snapshot; they are not current-tree timings.

## What the dependencies already do

| Facility | Dependency authority | Consequence for Seon |
|---|---|---|
| Registry lookup/composition/lazy provision | `reference-code/malli/src/malli/registry.cljc:17`, `:24`, `:54`, `:81` | Use these rather than another uncached provider. A registry of raw forms is **not** a registry of retained root Schema objects. Lazy enumeration includes only defaults and realized entries; retain the admitted canonical key set for complete inventories. |
| Compile, inspect and walk schema nodes | `reference-code/malli/src/malli/core.cljc:2550`, `:2581`, `:2595`, `:2611`, `:2771` | `m/schema`, `m/properties`, `m/children`, `m/walk`, `m/entries` know syntax roles. `m/entries` does not flatten an `:and`; a small Seon storage interpretation is still required. |
| Schema-owned validation/explanation caches | `reference-code/malli/src/malli/core.cljc:345`, `:2626`, `:2642` | Retain the Schema, then ask it for validators/explainers. The cached explainer core can still be enclosed in a fresh public function; function identity alone is not a cache test. Malli's cache does not promise exactly-once computation under concurrent misses. |
| Map transformations | `reference-code/malli/src/malli/util.cljc:53`, `:75`, `:238` | Reuse entry transformations, but default `mu/merge` overwrites overlapping fields. It is not conjunction. Keep `:and` semantics, including requiredness. |
| Value codecs | `reference-code/malli/src/malli/transform.cljc:387` | A compiled schema can own a value transformer. This does not supply Datahike operation expansion, lookup-ref grammar, transaction-function wrapping, or final-state validation. |
| Instrument inputs, outputs, guards and arities | `reference-code/malli/src/malli/core.cljc:2202`; `reference-code/malli/src/malli/instrument.clj:21`, `:56` | Seon already delegates ordinary call validation to `m/-instrument`. Its typed refusal policy, database-selected contracts, private-Var collection and reload-safe restoration remain Seon responsibilities. |
| Attribute types, cardinality, uniqueness, refs, indexes | `reference-code/datahike/src/datahike/schema.cljc:11`, `:69`, `:77`; `reference-code/datahike/src/datahike/db/transaction.cljc:33` | Generate native declarations; do not implement another database type checker. Malli still supplies narrower logical constraints. |
| Retraction and components | `reference-code/datahike/src/datahike/db/transaction.cljc:998` | Native deletion sweeps incoming refs and cascades components. Seon validates the resulting affected owning values; it must not implement a competing deletion mechanism. |
| Entity ensure | `reference-code/datahike/src/datahike/db/transaction.cljc:745`, `:768`, `:1132` | Explicit `:db/ensure` expands checks during operation processing. It does not automatically validate every swept referrer at the transaction's final state. It cannot replace Seon's final-report callback. |
| Atomic final refusal | `reference-code/datahike/src/datahike/db/transaction.cljc:1206`, `:1276` | Keep the supplied `:datahike/validate-report` callback: final report in, non-nil result refuses before commit. This is the dependency extension Seon needs, not a second writer. |

## Pain-point ledger

LOC below means the **inclusive owner span under review**, including contracts/comments, not a promised deletion total. Spans overlap; do not sum them. A historical incident is identified as historical; an unmeasured risk is not presented as its cause.

| Mechanism and class | Owner / lines occupied | This week's evidence | Dissolution and remaining guarantee |
|---|---|---|---|
| Registry enumeration used as cycle identity; dependency facility used with the wrong scope | Malli core `:1943–1951` (9); generator `:299–310` (12) | Stall note `:100–110`: 100 candidate checks **644.447 → 22.735 ms**, enumerations **2,000 → 0**; full build **1,095.672 → 722.961 ms**. | Core is already repaired in the pinned fork. Generator still enumerates the registry in its copied helper; a generator follow-up must preserve shadowed local-ref semantics. Do not claim all compile work was fixed. |
| Fresh candidate registry and fresh root per value; reimplements retained compilation | `schema.clj:1133–1168` (36), `:3478–3510` (33) | Probe A: candidate median **34.120 ms/1,000**, retained Schema **0.1515 ms/1,000** on a small synthetic conjunction. Historical config CPU **127.8–193 s** is sampled thread work, not an isolated validator duration (adoption note `:58–68`). | Carry one candidate generation; retain its root objects. Simply reusing a raw registry still parses root schemas. |
| Complete registry copied for each declaration; compiled output discarded, then compiled again | `schema/internal.cljc:338–393` (56); `schema.clj:1871–1885` (15), `:2283–2326` (44) | Probe A: **64 copies, 205,312 entries**, **17.152 ms**. Review rank 3 measured the same class; not an extrapolated full-build time. | Compile once against the supplied final registry, preserve refusal context, retain the result through declaration checks and runtime use. S declarations × S-entry copies is quadratic entry copying. |
| Raw syntax interpreter and repeated entity expansion | `schema/form.cljc:1–216` (216); `schema/datahike.clj:17–98` (82); `schema.clj:1581–1616` (36) | Review rank 9; pulled-shape study shows why one authored entity cannot be all representations. Probe A shows the no-forms walker drops an inherited field. | Delete raw **schema navigation**, use compiled node APIs, retain a small explicit storage algebra. Do not walk enum literals, property payloads or generator data as schemas. |
| Per-root dependency-path traversal over an existing dependency graph | `schema.clj:1247–1410` (164), especially local `walk-schema` around `:1331`; build advisory cache `:1888–1917` | Review rank 4: path-local visited set can revisit diamond descendants; no measured share of the stall. | Distinct `[reference, role, admission]` visitation over existing edges. Keep role-sensitive diagnostics and the canonical-cycle policy; Malli allowing recursive refs does not repeal that policy. |
| Recursive dereference rebuilds schemas before validation | `schema.clj:3519–3528` (10), `:3605–3614` (10), `:3694–3710` (17) | Probe A: median **9.799 ms/1,000** via projection-validator versus **0.03325** via retained validator; independent calls return different recursively dereferenced objects. | Use the retained compiled root's cache. Verify explanation paths before removing recursive reconstruction. The auxiliary projection cache remains useful for non-Malli products. |
| Generator and predicate binding repeated during compilation | `schema.clj:127–313` (187), especially `:226–313` | Review rank 6 found **zero generator invocations** in 1,000 Malli compiles. Seon's `requiring-resolve` is separate work; its namespace-load cost was not measured. | Bind once per generation; defer generator-only acquisition where possible without changing declared whole-value generators. Do not delete metadata as a validator optimization. |
| Several ways to acquire “the same” projection; read-time regeneration | `schema.clj:875–1076` (202), `:2414–2696` (283); `db.clj:200–288` (89) | Publication repair `:26`: raw fresh DB had **0** canonical schemas; supplied construction world had **3,208**. Repair carried that world rather than inferring it from an empty DB. | Keep acquisition at boot/publication/accepted-declaration boundaries. Ordinary readers require the carried value. Remove fallback choice/logging after callers convert. |
| Full build hidden behind a catalog/read helper | `schema.clj:3223–3227` (5), `:3387–3443` (57) | Source establishes repeated `build-projection`; writer note `:135` measured **918.978 ms** canonical-row construction, inclusive. No independent timing for entity-catalog. | Consume admitted compiled generation and its derived catalog/reference graph; canonical row construction should not readmit the world. |
| Storage inference silently changes queryable meaning | `schema/datahike.clj:124–186` (63), `:300–338` (39), `:347–380` (34) | Audit C `:166–211`: renderer symbol-or-output union became an EDN string. Probe B confirms mixed symbol/string → native string. | Separate declaration identity from rendered value; compiled storage admission must expose/refuse unintended opaque representation. Retain deliberate EDN envelopes; Malli cannot make a heterogeneous native Datahike scalar. |
| Unregistered storage-looking properties are ignored | `schema/form.cljc:9–22` (14); `schema/datahike.clj:196–212` (17), `:311–338` (28) | Audit C `:383–390`: invented `:seon.db/cardinality :many`; A `:212–233`: unregistered properties become unqueryable. Probe B: the invented property on an int still yields cardinality one. | Validate Seon storage-property declarations at admission; derive cardinality from declared collection semantics. Do not introduce another cardinality flag. Arbitrary Malli properties are allowed by Malli, so this remains explicit Seon policy. |
| Polymorphic alias launders a boundary exemption | `schema/internal.cljc:22–79` (58), `:189–324` (136) | Error review `:478`: **86 literal occurrences**, not 86 proven defects; audit C `:467–483` identifies ordinary members. | Keep genuine arbitrary-value inspection; require exemptions at actual boundaries with evidence. A compiled registry does not repair an overly broad contract. |
| Per-wrapper policy acquisition and duplicate instrumentation policy | `instrument.clj:735–818` (84), `:971–1003` (33) | Publication repair fixed default-config acquisition per wrapper. Current normal apply creates one delayed defaults value. Review rank 7's all-facets restriction is also changed in observed `:789–790` to nonempty intersection. | Keep typed errors, provenance, declared-facet semantics and reload handling around Malli's instrumenter. Do not report repaired behavior as current, or rewrite the entire wrapper to save already-deleted work. |
| Static storage plans rediscovered during each transaction; final ownership traversal dominates | `db.clj:3513–3657` (145), `:3890–3966` (77) | Writer note `:139–145`: owned validation **9,683.007 ms**; scalar/entity validators account for much less than that. | Acquire declaration-derived component targets and schema plans with the generation; retain before/after ownership discovery at the writer. Optimize measured index seeks, not by trusting submission. |
| Durable derived schema facts vs in-memory indexes | `schema.clj:3387–3443` (57), `:1947–2040`; `schema/edn.clj:66–105` (40) | Audit C renderer-property query failure; A missing property facts. | Schema references/properties materialized with canonical source are useful enforced query indexes, not automatically illegal mirrors. Keep their same-transaction derivation. Reverse edges/catalog/caches stay derived on the value; do not persist a second registry, cache-valid boolean, entity-kind stamp or cardinality assertion. |

The remaining ranked findings also matter: review rank 8 describes a constructor discarding supplied domain fields (`error-composition-review-2026-09-20.md:114`), a producer defect rather than a reason to replace Malli composition; that constructor is under concurrent repair and was not re-probed here. Rank 10 is covered by the consumer census below. Rank 12 remains visible as `[:fn clojure.core/deref]` on `call-with-projection-state` (`src/seon/schema.clj:1070`): validation invokes an effectful holder operation and tests its result truthiness. Declare the real holder predicate at that existing seam; cache reuse does not repair the contract (`docs/seon/issues/schema-projection-state-contract-invokes-deref-as-a-predicate.md:9`; `reference-code/malli/src/malli/core.cljc:1761`).

Two additional source-level risks deserve explicit records here. First, `write-entity-schemas` resolves the outer form, then calls one-argument raw map helpers (`src/seon/db.clj:3218–3237`); Probe A's inherited-field omission exercises that helper grammar. This is **a verified helper counterexample and a source-linked writer risk**, not proof of a canonical entity bypass. Second, `carry-derived-projection` says it derives from the exact database but first builds packaged forms and composes a DB delta (`src/seon/db.clj:253–273`). With its present compose rules this may recover the intended effective forms; the confirmed defect is duplicate acquisition and a misleading ownership story, not a proved wrong final population.

The six bridge files occupy **6,275 lines** at the census (schema 3,873; form 216; internal 452; admission 443; datahike 635; edn 656). This count includes real domain policy and contracts. “Delete 6,275 lines because Malli exists” is unsupported. The function census at the end distinguishes replaceable mechanics from policy.

## The three sources and their selection seams

There are three **inputs**, but they should not be three competing **runtime authorities**:

1. Packaged declarations are a publication input. `schema.edn/resource-population` reads/merges resources and derives config forms (`:328`); `packaged-population` caches them (`:369`); `packaged-forms` exposes them (`:412`). Old comments claiming every call rereads all files are stale, e.g. `src/seon/env.clj:141`. Caching resources does not retain compiled schemas.
2. Canonical DB rows are the durable program. `projection-from-rows` parses rows, checks duplicate identities and admission provenance, fingerprints, and optionally reuses a projection (`schema.clj:2414`); `derive-projection-from-database` queries schemas/contracts/source (`:2643`); its public wrapper is `projection-from-database` (`:2675`). Fingerprint reuse still pays query/parse/derivation work.
3. Carried projection is the acquired executable world. `carry-projection-state` snapshots it onto DB metadata (`db.clj:232`); `carried-projection` follows the temporal origin (`:1115`, `:1168`); `env/advance-projection!` replaces the environment's projection at a non-older basis (`env.clj:106`). The state holder is mutable; the retained DB's projection reference is a snapshot.

### Constructor and transformation inventory

| Functions / source | What they construct or select |
|---|---|
| `candidate-forms`, `declaration-population`, `candidate-registry`, `declaration-projection` — `schema.clj:1017`, `:1028`, `:1133`, `:1148` | Dynamic registration overlay → bound packaged forms → state projection forms → bound projection forms → classpath fallback (the guarded population API requires custody). Minimal declaration projection has a registry/cache but not the full program policy indexes. |
| `build-projection`, `materialize-projection` — `:1806`, `:2283` | Full admission/build versus materialization of previously composed pure data. Both construct registries; neither retains all scalar compiled roots today. |
| `projection-pure-data`, `compose-projection-data`, `projection-delta`, `maintain-projection-delta` — `:2060`, `:2130`, `:2183`, `:2231` | Transportable definitions and changes; composition is not the sole runtime acquisition authority today. |
| `projection-from-rows`, `derive-projection-from-database`, `projection-from-database` — `:2414`, `:2643`, `:2675` | Durable-row acquisition, with fingerprint-based reuse. This must survive in some form at restart/branch acquisition even if the ordinary public reconstruction API disappears. |
| `projection-with-schema`, `projection-without-schema`, `projection-with-function-contract` — `:2697`, `:3072`, `:3112` | New candidate generations, including dependency and shape updates. |
| `projection-with-pulled-form-in`, `pulled-form-in` — `:3035`, `:3057` | Selector-derived result schema/projection, not a new authority for stored entities. Preserve this distinction. |
| `activate!`, `activate-projection!`, `current-projection`, `handed-projection`, `shape-projection` — `:3189–3221`, `:3616` | Admission/build, identity return and dynamic acquisition/fallback. `activate-projection!` is not another compiler. |
| `entity-catalog`, `canonical-schema-rows`, `canonical-database-attributes` — `:3223`, `:3387`, `:3445` | Hidden full-build and forms-derived storage paths. Convert these to consumers of the acquired generation. |
| `db/carry-derived-projection`, `resolve-database-value`, `connection-projection-state` — `db.clj:253`, `:275`, `:200` | DB metadata vs state vs handed projection; raw commit acquisition additionally composes packaged and persisted models. Connection acquisition still has an operator-runtime-table fallback. |
| `sci.eval/projection-state`, `advance-context-projection!`, `base-ctx`, `cluster-ctx*`, `fork-cluster-ctx` — `sci/eval.clj:168`, `:627`, `:2109`, `:2177`, `:2220` | Environment/context custody, plus additional DB reconstruction and reuse decisions. |

### Runtime and acquisition call-site census

This is a dated source census, not a new maintained roster. It covers direct calls to the constructors/readers above under `src/` plus the supplied-projection selectors read in their owners. Names group multiple lines of the same function; line anchors identify the observed seams.

| Owner / functions | Projection choice or source |
|---|---|
| `db/read-declarations` `:1175`; `arity-mismatches` `:3732`; `transact-call` `:4111` | Reads: carried then handed. Writes: connection state then carried then handed. Relation-only reads use handed custody (`:1213`). Missing authority refuses. |
| `turn/declaration-projection` `:1206`, used by `row-tx` | Carried required; if earlier declarations occurred in the same transaction, rebuild from the writer's current DB (`:1229`). This is a legitimate change of world; deleting it without carrying the candidate forward would break sequential declarations. |
| `cluster/require-candidate-value` `:681`, `resolve-bootstrap` `:709`, `require-admissible-branch!` `:1638`, `accrete-schema-population!` `:1670`, `populate-source!` `:1730`, `refresh-source!` `:2648` | Packaged forms, often bound projection preferred over declaration projection. Publication/compatibility paths must state which generation they admit. |
| `cluster/activation-requirements` `:1213`, `source-base!` `:1974`, `incremental-source-refresh!` `:2227`, `development-source-refresh!` `:2462`, `:2501`, `stand-boot-layers!` `:3634`, `:3652` | DB rebuilds, packaged input, or exact source-base reuse. Existing boot already reuses a projection for an exact fork and reconstructs an existing branch once. |
| `cluster.source/database` `:174`, `record-results-at-head!` `:423`, `record-results!` `:453`; `cluster.wake/wake-matchers` `:416` | Commit read carries via hybrid derivation; result recording and wake setup reconstruct from rows. |
| `fn/runtime-analysis-batch` `:826`, `source-rows` `:1195`, `declaration-forms` `:1249`, `contract-findings` `:1586`, `backfill-contract-facts!` `:2450`, `desired-rows` `:2536`, `reconcile-tx-in` `:2755`, `index!` `:2972`, `:3033` | Carried/handed vs packaged vs DB rebuild. Fresh-publication repair lives here: preserve construction custody before canonical rows exist. |
| `config/default-population` `:324`, `default-decisions` `:360`, `read-manifest` `:370`, `compile-settings` `:381`, `apply!` `:570`; `apply-compiled!` `:496`; `effective` `:581` | Packaged input for compilation; carried → handed → DB fallback at application; effective settings consume carried/handed. |
| `error/reader-correction` `:1075`, `refusal-data` `:1153`, `commit-call` `:1507`, `recording` `:1595`, `rendered-error-value` `:1782`, `faults-form` `:2005`, `render-faults-html` `:2048`; `schedule/execution-context` `:512` | Reconstruct projection from DB at ordinary operations. Convert acquired DB consumers to carried-only. |
| `sci.eval/evaluation-projection` `:618`, `committed-row?` `:792`, `install-row!` `:822`, `:860`, `:901`, `:937`, `documentation-contract` `:1397`, `acquire-program!` `:1662`, `evaluate` `:2636` | Context/request/carried selectors; accepted rows sometimes rebuild with reusable projection. These must distinguish new declarations from unchanged ordinary reads. |
| `render/request-projection` `:110`, `request-profile` `:122`; `render.walk/root-pull-plan` `:382`, `acquired-tree` `:490`, `root-acquisition` `:549`, `neighborhood` `:766` | Supplied projection and/or carried DB/context, with handed profile fallback. A render must not silently select a different world than its DB. |
| `reconcile/plan` `:320`; `effect/accepts-request?` `:190`; `problems/problems` `:385`; `bootstrap/next-entry` `:716` | Carried/handed selection and, for reconciliation, DB reconstruction. |
| `instrument/apply!` `:971`, `:998`; `env/declared-members` `:141`; `print/sink-result` `:341`, `node-child?` `:933` | Supplied/handed program projection versus separately acquired packaged core definitions/defaults. Process-immutable core definitions may be legitimate; document that narrower lifetime instead of pretending they are cluster-specific. |
| `ai/configured-targets` `:412`, `wire-settings` `:600`, `extra-body-request-ident` `:634`; `agent/render-settings-html` `:175`; `shell.jvm/environment-overrides` `:84`; `program/base-context-injected-symbols` `:87`, `authored-shapes` `:241` | Declaration-population or packaged forms. These are additional implicit source choices, even where they only inspect forms. |
| `test/prepare-tests!` `:1382`, `run` `:462`, `check-request` `:1836`; `test.runner/packaged-test-projection` `:1686`, `worker-command-loop!` `:2024`, `reach-cache` `:2183`, `complete-members` `:2591`, `-main` `:4532`; `test.arm/packaged-test-projection` `:38` | Packaged test construction, DB reconstruction, and carried test execution. Preserve canonical harness custody during conversion. |
| `schema.datahike` convenience arities `:47`, `:92`, `:188`, `:278`, `:292`, `:576`, `:628`; `schema.admission/admit` `:375`; `schema.edn/admit` `:565` | Packaged/default declaration fallback and current-projection vs bootstrap-full-admission choice. Convert callers before deleting convenience arities. |

### Proposed one-generation value and path

“Compiled registry IS the projection” is a useful direction, **not literally enough data**. `malli.registry/Registry` offers lookup/enumeration, not admissions, program contract edges, storage plans or publication identity (`registry.cljc:3`). Keep **one immutable semantic value** in the existing projection map, with an immutable registry of retained Schema objects as its executable core, canonical forms for exact durable round-trip, the existing fingerprint/admission/dependency data, and storage/owned-schema plans derived once. Retain bounded, generation-local memoization only for products that Malli does not own, e.g. selector result shapes and wrapper identity. No process-global `set-default-registry!`, second registry service, or separately mutable forms table (`schema.clj:2019–2058`, `db.clj:232`).

There is an important limit to “immutable”: `runtime-predicate` deliberately retains **Vars**, so a reload changes an old schema's predicate behavior (`schema.clj:143–162`). Proposed generation semantics must explicitly distinguish immutable declarations from live host predicate roots. For an exact executable-generation guarantee, capture resolved callables during acquisition and replace them on the same adoption as wrappers/SCI; test reload behavior before changing this current contract. Do not silently promise snapshot behavior while retaining live Vars. Malli itself has mutable implementation caches; semantic immutability does not forbid those caches (`core.cljc:345`).

Proposed publication/adoption sequence, using the existing owners:

1. Publication reads packaged declarations once, binds predicate inputs, compiles/admit-checks the candidate generation once, and derives canonical datoms plus native Datahike attributes from it. An empty database receives this explicit construction world; it is never asked to invent it (`fn.clj:2972`, `:3033`; publication-repair note `:26`).
2. The serial writer validates final data against the candidate appropriate to that transaction and refuses surviving broken references. For concurrent program changes, verify the candidate's base at the writer, or derive the candidate there; a pre-read is not permission to trust a stale generation (`db.clj:3832`, `:3890`, `:4111`). Ordinary immutable transaction payload checks can be precomputed; current-head decisions cannot.
3. After successful publication, carry the candidate with the committed DB value and artifact. Exact forks reuse it. An existing branch/restart has **one durable-row loader** at acquisition; retain `projection-from-rows`' duplicate/provenance checks there. The proposal deletes ordinary `projection-from-database` calls/API, **not the ability to reconstruct a process from durable facts** (`cluster.clj:3631–3653`).
4. Development adoption reconciles program facts, loads definitions, arms contracts and acquires SCI using the selected generation, then records adoption and advances the environment. Do not turn this into a promise of atomic JVM Var replacement; current adoption is staged (`cluster.clj:2440–2525`; `env.clj:106`).
5. Acquired DB/environment/request values carry the same generation. Missing custody is a typed refusal. Historical/as-of/since semantics retain their current origin policy unless separately changed: `schema-database` follows `IHistory/-origin`, not schema facts at each historical transaction (`db.clj:1115`). A promise of historical schema interpretation requires explicit generation acquisition and a test; metadata alone does not establish it.
6. In-transaction declarations advance the candidate for subsequent declarations; the final callback validates the final world. A blanket “never derive in the writer” would break the existing preceding-declaration case (`turn.clj:1206–1230`).

What disappears: repeated raw-form registry providers, disposable compiled roots, recursive-deref validator construction, generic raw schema traversal, ordinary DB reconstruction fallbacks, classpath fallback warning/caller-stack machinery, and separately selected packaged forms in cluster-specific consumers. What stays: durable forms/provenance, declaration admissions, derived graph facts, selector-derived pull contracts, operation codec, writer refusal and generation acquisition. These deletions correspond to the ledger's exact spans, not a second runtime architecture.

## What stays at the writer, and what can move

| Check | Timing with the same guarantee | Why |
|---|---|---|
| Malli syntax, resolved declaration graph, storage mapping, component target declaration, forbidden canonical cycles, contract completeness and generator binding | Once per admitted generation; a declaration-changing transaction validates its new candidate before acceptance | Depends on immutable declarations, not domain rows (`schema.clj:1806`; `schema/internal.cljc:189`; `schema/datahike.clj:233`). |
| Compiled validators/explainers; native-attribute codec; identity→entity schema and component-attribute→child-schema plans | Acquire once for generation plus installed-storage-schema version; no per-row compile | Current attribute-plan cache already keys by installed schema (`db.clj:3898`). Extend that existing ownership, not an unrelated global cache. |
| Plain submitted literal type/shape | Early check is useful for diagnosis; final validation must still cover transaction-function output and attempted idempotent assertions | Native operations and expanded functions may bypass caller prevalidation; effective datoms alone omit assertions that changed nothing (`db.clj:3909`; Datahike transaction `:1153`, `:1206`). |
| Required fields/refs, including swept incoming refs and required-many emptiness | Final database, after native retractions and all repairs | A required ref removed by deletion must refuse; optional refs sweep. Empty-many emits no fact. An admission-only check cannot see that result (guide `:301`; transaction `:998`; `db.clj:3475`). |
| Components: full child value, ownership before/after, no orphan/cycle/multiple owner, node bound | Final writer, complete indexed EAVT/AVET traversal or an equivalent dependency-complete algorithm | Child-only edits and reparenting change roots. Pull's default 1,000-member limit cannot prove completeness (`db.clj:3513–3657`; `reference-code/datahike/src/datahike/pull_api.cljc:16`, `:315`). |
| Deletion and program graph validity, argument-count compatibility, renderer target presence | Final writer; select affected dependencies using before/after facts and attempted/effective changes | Same-transaction repairs must pass; surviving referrers must refuse. Declaration-time proof cannot cover concurrent later declarations (`db.clj:3685`, `:3746`, `:3873`, `:3930`). |
| Current generation/base, current policy bound, current identity/ref resolution | Writer authority or an explicitly verified candidate | `transact-call` currently selects projection before queuing, while validator takes its bound from final DB (`db.clj:4111`, `:3952`). Reuse must preserve this distinction and verify generation transitions. |

The final validator should consume compiled plans, not rebuild the language. However, the proposed reduction in validation **scope** requires a proof: select every potentially invalidated entity/owner/referrer, including retractions and absent identities. For example, `arity-mismatches-with` currently runs for any nonempty affected set (`db.clj:3935`); restricting it to declared dependency changes is plausible, but only after enumerating all inputs to prepared arities. Likewise, `owners-of` performs AVET lookup for every installed component attribute for each uncached entity/phase (`db.clj:3567`). Its shape is O(C × visited entities) index requests, not necessarily O(C × entities × all datoms). Acquire static C/targets once; measure empty seeks and successful edges before selecting a new indexed traversal. No second owner table is justified by these samples.

### Measured writer share and bounded delta

Historical detailed run, **not rerun here**, from `writer-hang-root-cause-2026-09-18.md:128–151`:

| Quantity | ms / share |
|---|---:|
| Population transaction application, inclusive of final validator | 26,014.335 |
| Population commit, separately measured | 7,254.573 |
| Application + commit | **33,268.908** |
| Final validator | **11,438.038 = 43.97% of application; 34.38% of application+commit** |
| Owned-value checks, inclusive | 9,683.007 |
| Entity-value calls, within owned checks | 46,620 calls / 326.518 |
| Entity-validator calls, within owned checks | 32,668 calls / 650.491 |
| Owned residual, subtraction only | **8,705.998**; not separately timed owner traversal |
| Arity / deletion / renderer checks | 688.018 / 237.255 / 41.173 |
| Entire publication / all nine final validators | 86,534.362 / 16,994.679 (**19.64%**) |

The population used 1,304,168 attempted and 419,043 effective datoms over 32,669 affected entities; the later adoption note observed a **74,366-operation** commit and writer activity lasting roughly **26–33 s** (`adoption-silence-diagnosis-2026-09-19.md:35–43`). Those are different runs and different units; do not divide one by the other. The lost-listener-completion defect is also separate from this completing slow run (writer note `:156`).

**Delta estimate, explicitly a scenario:** removing every measured entity-validator call would save at most **650.491 ms (1.96%)** of application+commit in that historical run, and removing every final check would cap savings at **11.438 s (34.38%)** but violate the mission. A **25–50% reduction of owned-check inclusive time**, if measured traversal improvements achieve it, saves **2.42–4.84 s (7.28–14.55%)** of application+commit. This is a planning range, not a performance prediction. Registry reuse can separately improve caller build/config/arming work; it cannot claim this ownership residual. No current population transaction was timed in this read-only review.

## Exactly three next shapes

Costs are engineering estimates, not measured diffs. LOC ranges include replacements across named owner spans and **exclude test additions**; file counts include test/authority edits but not a wholesale resource conversion. Lane-days mean one focused engineer's work, excluding queue time and slow integration. Options are alternatives, not additive budgets. Each keeps the final-report callback and the unbreakable program-graph guarantee. The owner should approve the cross-owner target before production work, as AGENTS §2.5 requires.

| Option | Guarantee | Estimated cost and what is given up |
|---|---|---|
| **1. Keep the layer; apply ranked fixes (simplest viable constraint)** | Existing projection API and durable declarations remain. Retain compiled roots, one registry per candidate, use Malli schema-owned caches, deduplicate dependency walks; replace the raw schema walker with a small compiled-node storage interpretation. Writer refusal unchanged. | **Delete 250–500 LOC; add 150–350; 5–8 files; 2–4 lane-days.** Owners: schema, form, internal, datahike, selected config/render callers and tests. Gives up solving all source-choice ambiguity: DB reconstruction and fallback arities remain until another slice. Lowest integration risk; changing raw traversal still needs semantic equivalence for conjunctions/refs/property payloads. |
| **2. Compiled Malli schemas are the projection's executable core — RECOMMENDED** | One admitted semantic generation, retained compiled roots, compiled-derived native storage plans; DB/environment carry it. One acquisition loader from durable rows; no ordinary reconstruction/fallback. Final writer accepts only the relevant generation/final data and refuses a broken graph. | **Delete 900–1,500 LOC; add 350–700; 12–20 files; 5–9 lane-days.** Includes option-1 owner spans plus DB/env/cluster/source/SCI/fn/config and ordinary consumers in the census. Gives up “fetch whichever world is available” convenience and opaque raw-map projection construction. Risks: custody gaps, declaration transactions, predicate reload behavior, restart/temporal policy, stale queued candidates. All are explicit acceptance cases, not reasons to keep hidden fallback. |
| **3. Malli-native declaration registration; storage properties; no EDN pre-expansion** | Fully qualified native Malli declarations registered once through an explicit `malli.registry` value; compiled traversal derives Datahike schema. Preserve durable canonical forms/provenance and writer refusal. Never mutate a global default registry. | **Delete 1,400–2,200 LOC; add 500–1,000; 20–35 code/test/authority files plus only resources whose semantics change; 10–18 lane-days.** Owners include `schema/edn.clj:207–326`, config composites `:66–105`, registration/admission/source import/export. Gives up Seon-specific compile preprocessing and implicit packaged registration. The namespaced-map notation is already native EDN, not a custom short-key language. Generated config composites and component storage/value distinctions still need an owner; moving them into compiled constructors is not deleting the requirement. Highest migration risk with little additional evidence of benefit. |

These are not three ways of deleting all schema code. Option 3's declarations are already largely Malli EDN; the change removes Seon's resource preparation/registration conventions, chiefly config-form derivation and compile preprocessing. `read-schema-resource` already uses ordinary `clojure.edn/read`; `#:seon.schema{...}` is native reader syntax. There is no custom key-expansion engine to delete (`schema/edn.clj:207–254`; `resources/seon/schemas/seon.schema.edn:1`). It cannot delete stable schema identities, program graph facts, configuration derivation or persisted source needed at restart (`schema/edn.clj:207`, `:305`, `:66`; `schema.clj:3387`). Malli accepts arbitrary properties; simply writing them in native declarations does not validate their storage meaning.

**HEAD-loading migration order, per option:**

- **1:** first retain compiled objects within the current projection/cache API and make admission use its supplied registry; then convert candidate validation/explanation consumers; then replace raw navigation and every caller in one coherent slice. Keep a public Var until all callers are converted; remove the old walker only with its complete caller conversion. Probe intersection, local refs, properties and explanation equivalence before that removal (`schema.clj:3519`; `form.cljc:24`; `db.clj:3218`).
- **2:** land option 1's first slice; establish explicit acquisition at boot/publication/restart using existing environment and DB carriers; convert ordinary consumer groups to carried-only; advance candidate generation through declaration transactions and verify final callback custody; then remove public reconstruction/fallback arities and their warning machinery in the same commit as the last callers. Convert compiled storage interpretation next if not already done. Decide and test host predicate lifetime before claiming exact executable snapshots. No transitional second registry service (`env.clj:106`; `turn.clj:1206`; `db.clj:3952`).
- **3:** first converge custody/compiled ownership as in option 2; expose an import that normalizes both existing resource input and native declarations into that **same** generation; convert each declaration and its consumers together, checking identical canonical graph/storage output; delete the superseded preprocessing/registration path only after every caller is converted. Preserve ordinary EDN reading and duplicate/provenance checks; a syntax-only rewrite of namespaced maps is unnecessary. The temporary input adapter is not a second registry. Storage type changes require the orchestrator's batched reset, not data migration (`schema/edn.clj:207`, `datahike.clj:233`).

At every implementation checkpoint: one coherent path-limited slice; prove owned namespaces require successfully before commit; canonical armed fast iteration, then the orchestrator's cold named gate and platform proof. The final acquisition/custody change additionally requires live in-place adoption, fresh fork, restart and final-refusal observations. These are future acceptance requirements, not claims from this scratch review.

## Recommendation and first bounded lane

Choose **option 2**, but authorize only its first bounded implementation slice initially: **retain the compiled schemas already produced by projection construction**. This directly extends ranks 2, 3 and 5 of `error-composition-review-2026-09-20.md:107–115` and the verified core scope repair; it does not compete with the writer owner.

Proposed lane ownership: `src/seon/schema.clj`, `src/seon/schema/internal.cljc`, `test/seon/schema_test.clj`, and its landing note; coordinate only after the current holders release those dirty files. Use the existing projection-owned holder to retain Schema objects; avoid a new durable attribute or public projection family. Make `assert-compilable-schema!` use a supplied complete candidate registry without copying the population, preserve its missing-reference diagnostics, and let build/materialize retain the validated roots. Make projection validator/explainer use those roots and Malli's caches. Preserve explanation meaning; if recursive dereference currently changes it, expose that exact discrepancy rather than silently changing the public shape (`schema.clj:1871–1885`, `:2283`, `:3519`, `:3605`; `internal.cljc:338`).

Estimate **0.5–1.5 lane-days**, **20–60 LOC removed, 30–100 added**, plus focused tests. No constructor retirement, storage schema change, cluster reset or writer scope reduction in this slice. Future consumer conversion makes the net deletion larger; do not claim the first slice completes option 2.

Acceptance: canonical-fixture tests verify valid and invalid declarations, missing refs, local registry shadowing, open conjunction requiredness, diagnostic paths, distinct simultaneous projections, and new-generation behavior without mutating an old projection. Instrument the construction seam to verify full-registry copies are independent of declaration count and repeated validation does not compile roots again. Count operations rather than enforce the scratch timing as a CI threshold. The lane runs `bin/test-fast --paths <owned files> -- seon.schema-test` and an owned-namespace load check; the orchestrator supplies cold/platform and adoption proof. This review made no such test changes or runs.

A following writer slice should time owner seek count/hits, assembly, and validator execution separately on the canonical population before changing validation reach. The existing evidence favors improving traversal and reducing repeated declaration work; it does not support dropping final-state refusal.

## Reproducible scratch probes

Both successful scripts ran serially via Python `subprocess.run(['clojure','-M','-e', code], capture_output=True, text=True, timeout=120)` (Probe B used 90 seconds). No cluster, test harness, file-backed store or fixture population was created. The 3,208-form synthetic population is **only a compilation workload**, not a stand-in for Seon's canonical database fixture. Probe A wall time was **10.684 s**, Probe B **12.002 s**, each exit 0. Warmup precedes five timings; shared-machine/JIT effects remain visible. No statistical population claim is made.

Probe A, exact evaluated script:

```clojure
(require '[malli.core :as m] '[malli.registry :as mr] '[malli.util :as mu]
         '[seon.schema :as s] '[seon.schema.internal :as si] '[seon.schema.form :as sf])
(let [forms (into {:probe/base [:map [:probe/x :int]]
                  :probe/child [:and :probe/base [:map [:probe/y :string]]]}
                 (for [n (range 3206)] [(keyword "probe" (str "s" n)) :int]))
      registry (mr/composite-registry (m/default-schemas) (mr/fast-registry forms))
      compiled (m/schema :probe/child {:registry registry})
      validator (m/validator compiled)
      projection (s/declaration-projection forms)
      value {:probe/x 1 :probe/y "y"}
      elapsed (fn [f n] (let [start (System/nanoTime)] (dotimes [_ n] (f)) (/ (- (System/nanoTime) start) 1e6)))
      paths {:candidate #(s/valid-candidate-value? forms :probe/child value)
             :shared-raw #(m/validate :probe/child value {:registry registry})
             :projection-validator #((s/projection-validator projection :probe/child) value)
             :retained-schema #(m/validate compiled value)
             :retained-validator #(validator value)}]
  (println :forms (count forms))
  (doseq [[_ f] paths] (elapsed f 100))
  (doseq [[k f] (sort-by key paths)] (println k :ms-per-1000 (vec (repeatedly 5 #(elapsed f 1000)))))
  (let [original mr/fast-registry calls (atom 0) members (atom 0)]
    (with-redefs [mr/fast-registry (fn [x] (swap! calls inc) (swap! members + (count x)) (original x))]
      (println :helper64-ms (elapsed #(si/assert-compilable-schema! forms :probe/base (get forms :probe/base) {:registry registry}) 64)))
    (println :fast-registry-calls @calls :members-copied @members))
  (println :retains-schema (identical? compiled (m/schema compiled)))
  (println :retains-validator (identical? validator (m/validator compiled)))
  (println :deref-recursive-retains (identical? (m/deref-recursive compiled) (m/deref-recursive compiled)))
  (println :and-entries (m/entries compiled))
  (println :raw-entries-with-forms (sf/map-entries forms :probe/child))
  (println :raw-entries-no-forms (sf/map-entries (get forms :probe/child)))
  (println :merge-required-overwritten (m/validate (mu/merge [:map [:probe/x :int]] [:map [:probe/x {:optional true} :string]]) {}))
  (println :and-required-kept (m/validate [:and [:map [:probe/x :int]] [:map [:probe/x {:optional true} :string]]] {})))
(shutdown-agents)
```

```text
:forms 3208
:candidate :ms-per-1000 [64.205584 37.825625 34.120125 31.50975 30.974459]
:projection-validator :ms-per-1000 [15.479625 9.79925 8.533083 10.082542 7.918]
:retained-schema :ms-per-1000 [0.326125 0.168875 0.1515 0.056833 0.0905]
:retained-validator :ms-per-1000 [0.073541 0.05375 0.03325 0.033083 0.032875]
:shared-raw :ms-per-1000 [1.739833 1.713917 1.759125 1.553292 1.564959]
:helper64-ms 17.151833
:fast-registry-calls 64 :members-copied 205312
:retains-schema true
:retains-validator true
:deref-recursive-retains false
:and-entries nil
:raw-entries-with-forms [[:probe/x :int] [:probe/y :string]]
:raw-entries-no-forms [[:probe/y :string]]
:merge-required-overwritten true
:and-required-kept false
```

Probe B, corrected successful script (an initial authoring error supplied three arguments to the two-argument attribute compiler; it was corrected after reading `datahike.clj:233`, and its temporary error report was removed; no Seon load failure):

```clojure
(require '[malli.core :as m] '[malli.registry :as mr] '[seon.schema.datahike :as sd])
(let [forms {:probe/a [:int {:seon.db/cardinality :many}]
             :probe/b [:set :int]
             :probe/render [:or :qualified-symbol :string]}
      p {:seon.schema.projection/forms forms}]
  (println :mixed-render-type (sd/form->datahike-value-type-in p (:probe/render forms)))
  (println :invented-cardinality (sd/malli->datahike-attr-in p :probe/a))
  (println :collection-cardinality (sd/malli->datahike-attr-in p :probe/b)))
(let [calls (atom 0)
      registry (mr/lazy-registry (m/default-schemas)
                 (fn [k r] (when (= k :probe/entity)
                             (swap! calls inc)
                             (m/schema [:map [:probe/x :int]] {:registry r}))))
      a (m/schema :probe/entity {:registry registry})
      b (m/schema :probe/entity {:registry registry})]
  (println :lazy-provider-calls @calls :same-root (identical? a b)
           :same-deref (identical? (m/deref a) (m/deref b))))
(println :java (System/getProperty "java.version") :clojure (clojure-version)
         :malli-url (str (clojure.java.io/resource "malli/core.cljc")))
(shutdown-agents)
```

```text
:mixed-render-type :db.type/string
:invented-cardinality #:db{:ident :probe/a, :valueType :db.type/long, :cardinality :db.cardinality/one}
:collection-cardinality #:db{:ident :probe/b, :valueType :db.type/long, :cardinality :db.cardinality/many}
:lazy-provider-calls 1 :same-root false :same-deref true
:java 26.0.1 :clojure 1.12.5 :malli-url file:/Users/sean/src/seon/reference-code/malli/src/malli/core.cljc
```

Thus a lazy compiled provider can reuse its resolved object, but asking `m/schema` by keyword still gives distinct outer roots. Acquire the root once. The cardinality and union probes verify dependency-facing semantics, not the current population of renderer declarations being changed by another lane.

## Function inventory: what overlaps Malli, and what does not

The following dated inventory covers the 159 top-level `defn` forms in `src/seon/schema.clj`. **M** marks compilation/navigation/cache mechanics that overlap Malli facilities and should shrink; **G** marks generation construction/custody that should converge (not a built-in Malli replacement); **P** marks Seon policy, durable identity/graph, domain inspection or API behavior that Malli does not supply wholesale. An M function can contain needed diagnostic adaptation; the label is not an instruction to delete its entire body. The ledger above identifies the exact problematic subspans. Function starts were obtained with the source census below, not a hand-maintained roster.

Final census source SHA-256 prefix `c8972b4f2b46ebac`; concurrent contract metadata edits preserved all function starts and the 159/3,873 counts.

| Function | Source start | Class |
|---|---|---|
| `direct-references*` | `src/seon/schema.clj:59` | P |
| `reference-cycle` | `src/seon/schema.clj:79` | P |
| `assert-acyclic-references!` | `src/seon/schema.clj:106` | P |
| `predicate-symbols-in` | `src/seon/schema.clj:127` | M |
| `runtime-predicate` | `src/seon/schema.clj:143` | G |
| `loaded-predicate-var` | `src/seon/schema.clj:164` | G |
| `converged-predicate-var` | `src/seon/schema.clj:182` | G |
| `compilable-form` | `src/seon/schema.clj:226` | M |
| `compiled-function-arities` | `src/seon/schema.clj:314` | M |
| `with-compiled-cache` | `src/seon/schema.clj:320` | G |
| `projection-cache` | `src/seon/schema.clj:354` | G |
| `projection-cache-value` | `src/seon/schema.clj:364` | G |
| `predicate-functions-in` | `src/seon/schema.clj:385` | G |
| `with-predicate-functions` | `src/seon/schema.clj:405` | G |
| `bound-forms` | `src/seon/schema.clj:420` | G |
| `projection-registry` | `src/seon/schema.clj:423` | M |
| `canonical-definition` | `src/seon/schema.clj:438` | P |
| `reference-registry` | `src/seon/schema.clj:544` | G |
| `canonical-reference-graph` | `src/seon/schema.clj:568` | G |
| `reference-candidate-keys` | `src/seon/schema.clj:602` | P |
| `direct-reference-keys-in` | `src/seon/schema.clj:611` | P |
| `portable-string-hash` | `src/seon/schema.clj:622` | P |
| `canonical-data-fingerprint` | `src/seon/schema.clj:625` | P |
| `framed` | `src/seon/schema.clj:631` | P |
| `canonical-coll-string` | `src/seon/schema.clj:634` | P |
| `canonical-value-string` | `src/seon/schema.clj:637` | P |
| `canonical-data-string` | `src/seon/schema.clj:678` | P |
| `byte-array?` | `src/seon/schema.clj:688` | P |
| `sha-256` | `src/seon/schema.clj:694` | P |
| `projection-fingerprint` | `src/seon/schema.clj:701` | P |
| `replace-fingerprint-entry` | `src/seon/schema.clj:735` | P |
| `direct-references` | `src/seon/schema.clj:745` | P |
| `dependent-schema-keys` | `src/seon/schema.clj:765` | P |
| `schema-removal-blockers` | `src/seon/schema.clj:789` | P |
| `admission-from-asserting-transaction` | `src/seon/schema.clj:824` | P |
| `frame-namespace` | `src/seon/schema.clj:875` | G |
| `canonical-directory` | `src/seon/schema.clj:899` | G |
| `frame-source-path` | `src/seon/schema.clj:926` | G |
| `first-party-frame?` | `src/seon/schema.clj:940` | G |
| `frame-description` | `src/seon/schema.clj:948` | G |
| `fallback-caller` | `src/seon/schema.clj:952` | G |
| `decade?` | `src/seon/schema.clj:983` | G |
| `warn-classpath-fallback!` | `src/seon/schema.clj:993` | G |
| `packaged-forms` | `src/seon/schema.clj:1013` | G |
| `candidate-forms` | `src/seon/schema.clj:1017` | G |
| `declaration-population` | `src/seon/schema.clj:1028` | G |
| `call-with-forms` | `src/seon/schema.clj:1053` | G |
| `call-with-projection` | `src/seon/schema.clj:1060` | G |
| `call-with-projection-state` | `src/seon/schema.clj:1068` | G |
| `active-projection` | `src/seon/schema.clj:1077` | G |
| `register-core-predicate!` | `src/seon/schema.clj:1080` | P |
| `core-predicate-registered?` | `src/seon/schema.clj:1115` | P |
| `update-candidate-forms!` | `src/seon/schema.clj:1123` | G |
| `candidate-registry` | `src/seon/schema.clj:1133` | M |
| `declaration-projection` | `src/seon/schema.clj:1148` | G |
| `malli-form?` | `src/seon/schema.clj:1202` | M |
| `pull-selector?` | `src/seon/schema.clj:1222` | P |
| `assert-complete-contract!` | `src/seon/schema.clj:1247` | P |
| `fold-contract-validations` | `src/seon/schema.clj:1411` | P |
| `identity-attr?` | `src/seon/schema.clj:1423` | P |
| `enum-members` | `src/seon/schema.clj:1442` | M |
| `register!` | `src/seon/schema.clj:1453` | G |
| `unregister!` | `src/seon/schema.clj:1520` | G |
| `contribute-candidate-forms!` | `src/seon/schema.clj:1539` | G |
| `form-string` | `src/seon/schema.clj:1546` | P |
| `render-declarations-in` | `src/seon/schema.clj:1562` | P |
| `map-shaped-schema?` | `src/seon/schema.clj:1581` | M |
| `required-map-entries` | `src/seon/schema.clj:1590` | M |
| `map-schema-accepts-schema?` | `src/seon/schema.clj:1608` | P |
| `schema-accepts-schema?` | `src/seon/schema.clj:1617` | P |
| `function-arities` | `src/seon/schema.clj:1655` | M |
| `arity-render-input-form` | `src/seon/schema.clj:1662` | P |
| `render-contract-observation` | `src/seon/schema.clj:1674` | P |
| `render-contract-refusal!` | `src/seon/schema.clj:1723` | P |
| `assert-render-contracts!` | `src/seon/schema.clj:1762` | P |
| `schemas-rendered-by` | `src/seon/schema.clj:1775` | P |
| `shape-row-in` | `src/seon/schema.clj:1785` | P |
| `build-projection` | `src/seon/schema.clj:1806` | G |
| `projection-pure-data` | `src/seon/schema.clj:2060` | G |
| `projection-fingerprint-from-data` | `src/seon/schema.clj:2066` | G |
| `reusable-projection-fingerprint` | `src/seon/schema.clj:2077` | G |
| `reverse-dependencies` | `src/seon/schema.clj:2084` | G |
| `shape-projections` | `src/seon/schema.clj:2095` | G |
| `compose-projection-data` | `src/seon/schema.clj:2130` | G |
| `projection-delta` | `src/seon/schema.clj:2183` | G |
| `maintain-projection-delta` | `src/seon/schema.clj:2231` | G |
| `materialize-projection` | `src/seon/schema.clj:2283` | G |
| `predicate-functions-with` | `src/seon/schema.clj:2328` | G |
| `validate-one-contract!` | `src/seon/schema.clj:2343` | G |
| `replace-reverse-dependencies` | `src/seon/schema.clj:2378` | G |
| `replace-shape-rows` | `src/seon/schema.clj:2395` | G |
| `function-dependents-of` | `src/seon/schema.clj:2405` | G |
| `projection-from-rows` | `src/seon/schema.clj:2414` | G |
| `refuse-projection-source` | `src/seon/schema.clj:2609` | G |
| `derive-projection-from-database` | `src/seon/schema.clj:2643` | G |
| `projection-from-database` | `src/seon/schema.clj:2675` | G |
| `projection-with-schema` | `src/seon/schema.clj:2697` | G |
| `pulled-schema-key` | `src/seon/schema.clj:2797` | P |
| `pulled-selector-refusal` | `src/seon/schema.clj:2804` | P |
| `entity-entry-map` | `src/seon/schema.clj:2828` | P |
| `reverse-target-schema` | `src/seon/schema.clj:2840` | P |
| `selector-target-schema` | `src/seon/schema.clj:2853` | P |
| `pulled-attribute-entry` | `src/seon/schema.clj:2871` | P |
| `pulled-form-from-spec` | `src/seon/schema.clj:2986` | P |
| `projection-with-pulled-form-in` | `src/seon/schema.clj:3035` | G |
| `pulled-form-in` | `src/seon/schema.clj:3057` | P |
| `projection-without-schema` | `src/seon/schema.clj:3072` | G |
| `projection-with-function-contract` | `src/seon/schema.clj:3112` | G |
| `activate-projection!` | `src/seon/schema.clj:3189` | G |
| `activate!` | `src/seon/schema.clj:3198` | G |
| `current-projection` | `src/seon/schema.clj:3210` | G |
| `handed-projection` | `src/seon/schema.clj:3216` | G |
| `entity-catalog` | `src/seon/schema.clj:3223` | G |
| `current-keys` | `src/seon/schema.clj:3229` | G |
| `snapshot` | `src/seon/schema.clj:3238` | G |
| `begin-registration-delta` | `src/seon/schema.clj:3246` | G |
| `delta-over` | `src/seon/schema.clj:3265` | G |
| `call-with-registration-delta` | `src/seon/schema.clj:3273` | G |
| `changed-candidate-keys` | `src/seon/schema.clj:3292` | G |
| `changed-keys` | `src/seon/schema.clj:3300` | G |
| `registration-delta-form` | `src/seon/schema.clj:3308` | G |
| `commit-registration-delta!` | `src/seon/schema.clj:3317` | G |
| `restore!` | `src/seon/schema.clj:3324` | G |
| `register-all!` | `src/seon/schema.clj:3336` | G |
| `registered-schemas` | `src/seon/schema.clj:3361` | G |
| `dependency-first-schema-keys` | `src/seon/schema.clj:3368` | P |
| `canonical-schema-rows` | `src/seon/schema.clj:3387` | G |
| `canonical-database-attributes` | `src/seon/schema.clj:3445` | G |
| `registered?` | `src/seon/schema.clj:3460` | G |
| `schema-definition` | `src/seon/schema.clj:3466` | G |
| `valid-candidate-value?` | `src/seon/schema.clj:3478` | M |
| `explain-candidate-value` | `src/seon/schema.clj:3495` | M |
| `projection-validator` | `src/seon/schema.clj:3519` | M |
| `function-arities-in` | `src/seon/schema.clj:3530` | P |
| `function-matching-outputs-in` | `src/seon/schema.clj:3544` | P |
| `function-accepts-in?` | `src/seon/schema.clj:3559` | P |
| `function-returns-in?` | `src/seon/schema.clj:3574` | P |
| `function-accepts-and-returns-in?` | `src/seon/schema.clj:3590` | P |
| `projection-explainer` | `src/seon/schema.clj:3605` | M |
| `shape-projection` | `src/seon/schema.clj:3616` | G |
| `identity-only-descriptors-in` | `src/seon/schema.clj:3623` | P |
| `identity-only-descriptors` | `src/seon/schema.clj:3659` | G |
| `identity-only-projection-in` | `src/seon/schema.clj:3675` | P |
| `identity-only-projection` | `src/seon/schema.clj:3688` | G |
| `cached-compiler-in!` | `src/seon/schema.clj:3694` | M |
| `shape-rank` | `src/seon/schema.clj:3711` | P |
| `complete-present-attrs` | `src/seon/schema.clj:3715` | P |
| `diagnostic-present-attrs` | `src/seon/schema.clj:3719` | P |
| `diagnostic-schema-keys` | `src/seon/schema.clj:3727` | P |
| `candidate-shapes-in` | `src/seon/schema.clj:3754` | P |
| `candidate-shapes` | `src/seon/schema.clj:3766` | G |
| `matching-shapes-in` | `src/seon/schema.clj:3778` | P |
| `matching-shapes` | `src/seon/schema.clj:3800` | G |
| `explain-shape-in` | `src/seon/schema.clj:3810` | P |
| `explain-shape` | `src/seon/schema.clj:3824` | G |
| `candidate-validator` | `src/seon/schema.clj:3835` | G |
| `candidate-explainer` | `src/seon/schema.clj:3845` | G |
| `schemas-in-namespace` | `src/seon/schema.clj:3855` | G |
| `clear-all!` | `src/seon/schema.clj:3869` | G |

Census categories: **M: 15**, **G: 85**, **P: 59**. These are review judgments over function responsibilities, not measured removable-function counts. For example `fold-contract-validations` delegates parallel admission checks and has no direct Malli replacement; `assert-complete-contract!` enforces Seon roles and exemptions even after its traversal is simplified. `direct-references*` already uses `m/walk`; keep that idiom instead of replacing it with a raw reader. Pull-selector/schema derivation, graph deletion blockers, fingerprints and open-map render compatibility remain domain policy (`schema.clj:59`, `:789`, `:1222`, `:1247`, `:1411`, `:2797`).

Reproduce the lexical census (not a production parser or runtime roster):

```python
from pathlib import Path
import re
source = Path('src/seon/schema.clj').read_text()
entries = []
for line_no, line in enumerate(source.splitlines(), 1):
    match = re.match(r'^\(defn-?(?:\s+\^\S+)*\s+(\S+)', line)
    if match:
        entries.append((line_no, match.group(1)))
print(len(source.splitlines()), len(entries))
print(*entries, sep='\n')
```

Read-only landing boundary: the successful probes loaded their dependency and bridge namespaces, but they did not arm contracts or transact canonical data. There is no new writer-performance measurement, cold proof, live adoption proof or claim of production correctness here. No production source, resource, test, issue index, runtime or foreign session was changed.
