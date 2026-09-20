---
type: plan
status: launch specification; waits for step-1 landing and overlapping-path release
created: 2026-09-21
tags: [plan, schema, malli, datahike, bridge, dissolution]
---

# Bridge step 2 — compiled walker

**Guarantee:** every consumer of a schema form's navigation reads the
compiled schema; `seon.schema.form` is deleted; Datahike attributes derived
by `m/walk` equal the previous derivation for the whole canonical population
— that equality is the regression.

This is the verbatim launch assignment for step 2 of the binding
[Malli-native bridge PRD](malli-native-bridge-prd-2026-09-20.md), in the
style of its §7. Its §3 deletion budget, §4 step-2 row, §6 order and native
explanation-path ruling govern. The
[accepted dissolution review](../research/bridge-dissolution-review-2026-09-20.md)
supplies the archaeology; the
[step-1 landing/checkpoint note](../research/bridge-step1-registry-2026-09-20.md)
supplies implementation evidence, not permission to assume an uncommitted
implementation is already HEAD.

## Launch verbatim on astra low, after step 1 lands

> Implement **step 2 only: compiled walker** in /Users/sean/src/seon,
> branch steward-platform. Read this entire specification, the binding
> Malli-native bridge PRD end to end (especially §§2.2–4 and the ruling
> immediately above §7), the accepted bridge review and step-1 landing note
> end to end, AGENTS §§1–5 and lane rules 11–16, and the
> data-oriented-clojure, data-modeling, datahike, repl and clojure-testing
> skills. Read the dependency ledger below against vendored source before
> designing. The owner ordered bridge steps 1–2 before remaining wave-1
> families. This assignment does not start steps 3–5.
>
> You are not alone in the codebase. Preserve unrelated edits; never revert
> others' work. Execute this bounded assignment directly, without further
> delegation. Launch **after the coherent step-1 retirement/caller commit**,
> not merely its recorder or research commit. Refresh HEAD, its landing
> note and this caller census; consume the landed retained-root API.
> Have the orchestrator coordinate/release every overlapping path before
> editing it. A clean path is no longer protected merely because an older
> note named it. Never operate, resume, message or repair another lane's
> session.
>
> **Own** src/seon/schema.clj, src/seon/schema/internal.cljc,
> src/seon/schema/datahike.clj, deletion of src/seon/schema/form.cljc,
> every production/test caller path in the dated inventory below, and
> test/seon/schema/datahike_test.clj, test/seon/instrument_test.clj and
> test/seon/flow_test.clj for the secondary raw-navigation callers, plus
> test/seon/db_test.clj for the inert annotation removal below.
> Own resources/seon/schemas/seon.test.edn and seon.test.accretion.edn
> **only** to remove the inert :seon.db/cardinality annotations identified
> below. Own test/seon/schema/datahike_parity.edn for mechanically captured
> baseline evidence and one landing note:
> docs/prds/steward-platform/research/bridge-step2-walker-2026-09-21.md.
> Include any newly discovered caller in the same coherent conversion;
> disclose and coordinate a concurrently held path before touching it.
> Source documentation describing a retired helper changes with its owner.
>
> **Deliver this exact guarantee:** every consumer of a schema form's
> navigation reads the compiled schema; seon.schema.form is deleted;
> Datahike attributes derived by m/walk equal the previous derivation for
> the whole canonical population — that equality is the regression.
> No surviving renamed raw walker, compatibility namespace, copied parser,
> second registry service, global cache or forms-versus-projection heuristic.
>
> **Consume step 1.** Resolve canonical named roots with mr/schema from
> :seon.schema.projection/registry on the supplied projection. Do not use
> get on a Registry; do not compile a named root again in a per-attribute
> or per-value loop. The registry contains retained schemas and function
> contracts; the projection still owns canonical forms, declaration graph,
> provenance and additional derived products. Keep forms for exact authored
> EDN, durable facts and diagnostic evidence, never as a second navigation
> authority. Missing required declarations produce the existing typed
> diagnostic, never an empty successful population. Preserve step 1's
> changed-declaration/dependent-closure recompilation, retained unaffected
> roots, captured local scopes and live-Var predicate behavior.
>
> **Implement the small compiled-node storage interpreter in
> seon.schema.datahike**, using m/walk, m/type, m/properties, m/children,
> m/entries and RefSchema identity/dereference. Reuse the existing compiled
> traversal in seon.schema and seon.schema.internal for admission and entity
> navigation. Put shared entity-entry/conjunction interpretation in the
> existing internal owner, below seon.schema, so admission and the bridge
> use one policy. Pass compiled nodes/registry custody explicitly. Do not
> make internal require schema, or schema require datahike back; preserve
> the documented load-cycle boundaries and convert their delayed calls.
>
> Walk each selected entity root through aliases, explicit :ref/:schema
> wrappers and conjunctive entity/base arms. Aggregate only entries belonging
> to that entity composition. Do not collect maps in attribute values,
> component transaction unions, enum literals, generator data or properties
> as extra entity declarations. :or alternatives are not a union of required
> fields. Entry properties and schema properties are distinct. Preserve
> inherited member order, requiredness strengthening, conflict evidence and
> refusal of an optionalized required member. Compare resolved child schemas
> in their own scopes; equal printed ref names in different local registries
> are not evidence of equal meaning. Do not replace :and with mu/merge.
>
> Make wrapper/alias property precedence explicit and prove it against the
> baseline. Malli does not propagate an :and parent's properties into its
> children. Canonical storage selection still includes entity entries,
> independently persisted attribute declarations, and qualified schema-row
> properties whose own declarations are storable. Preserve the distinction
> between the current database-attributes core selection and the bridge's
> additional property attributes; callers needing the narrower set must not
> accidentally gain every storable metadata key. Keep deterministic ordering.
>
> Preserve the named :seon.db/ref token **before dereferencing** its logical
> union. It denotes native ref storage. Component compilation's ref-or-owned-
> map union must still yield native ref/component storage, never EDN string
> storage or extra attribute discoveries. Normalize the known :inst alias's
> compiled inst? type; do not guess a storage type for arbitrary predicates.
> Preserve scalar/many, symbol/qualified-symbol, identity/value uniqueness,
> index, noHistory, component target, tupleTypes, secondary-only float/double
> tuple storage, literal/enum and explicit value-type behavior. Collections
> :set/:vector/:sequential yield native unordered many; their logical shape
> remains distinct. Remove the inert cardinality annotation; no replacement
> flag. Keep deliberate heterogeneous-union EDN encoding, every refusal and
> canonical round-trip. A newly discovered mapping difference is evidence
> to resolve, not permission to accept a different database schema.
>
> **Move non-navigation behavior before deleting its old owner.** Move
> component transaction-grammar widening into the existing compilable-form /
> generation-construction owner in seon.schema. Use Malli node children and
> its reconstruction seam for schema transformations, not a postwalk that
> interprets arbitrary payload vectors as schemas. Widen once during
> construction, retain the result, preserve idempotence and authored EDN.
> The :seon.db/component-entity target and stored/transaction/pulled grammars
> retain their meanings. Property filtering/storage selection remains bridge
> policy; error inheritance, exemption/role rules and requiredness remain
> internal admission policy. Keep predicate/generator binding and durable
> encoding at their existing owners. Delete the unused primitive alias Var;
> resources/seon/schemas/malli.edn remains the :inst declaration authority.
>
> **Convert every navigation caller, including construction callers.** The
> census includes EDN config derivation before full generation construction,
> pure-data shape composition, incomplete candidate inspection and final
> database readers. Do not close those cycles by calling declaration-projection
> inside a leaf or falling back to packaged forms. Full admitted operations
> use their carried generation. During existing syntax/preparation phases,
> use the existing structural compilation facility for syntax-only inspection
> with unresolved refs opaque; it cannot answer storage, target existence or
> inherited-entity questions. Resolve those against the complete supplied
> construction registry before admission. Keep this facility in its existing
> owner, not a second service. Preserve the no-namespace-load inspection rule.
>
> In particular, derive config composites before their final full compilation;
> preserve pure-data serialization/composition (no Schema objects in durable
> data). Shape indexes may consume entry information already derived from
> compiled nodes, or be derived at materialization; do not smuggle raw form
> navigation back into compose-projection-data. Keep partial declaration
> publication's missing-reference evidence and final admission: an opaque
> syntax placeholder is never proof that a missing target exists. Prove these
> construction cases before converting the broad caller set. If the existing
> construction seam cannot support this without new cross-owner machinery,
> bring the owner three priced options before that machinery is written.
>
> **Native Malli explanations govern.** Preserve both :in and :path unchanged.
> Locate offending values and phrase messages with :in plus schema name /
> properties. Assert violated key, offending value and :in; never assert a
> literal schema :path vector. Do not add path translation or derived
> explanation schemas, and do not restore m/deref-recursive acquisition.
> List any explanation consumer converted by this slice in the landing note.
>
> **No stamp, no writer changes, no validator diet.** No new population
> attribute, metadata carrier, restart loader, branch/reset mechanism,
> publication redesign, Datahike fork edit or instrumentation replacement.
> The mechanical navigation calls in seon.db are in scope; transaction
> selection/order, callback timing, affected/attempted/effective datoms,
> complete owner assembly, before/after reach, bounds and final refusal
> guarantees are unchanged. Do not remove even an apparently duplicate
> native check. Do not turn this into error-family cleanup or a resource
> migration. Steps 3 and 4 own those decisions.
>
> **Prove on the canonical armed fixture**, using test-support/with-database,
> its complete installed population and explicitly carried projection.
> Use extra-schema only for synthetic cases; use transacted! for writes and
> the real SCI fixture for an SCI observation. No hand-rostered population,
> unarmed stand-in, mocked compiler or hand-built program entity. The exact
> acceptance cases and independent parity oracle are specified below.
>
> Capture the whole-population old result before changing its derivation,
> after step 1 lands. Then show exact discovered-key and native-declaration
> equality with the compiled m/walk result, not merely a count, sample or
> common-key intersection. Zero schemas, zero attributes or missing expected
> evidence fails. Retain one recurring regression for this equality; delete
> the obsolete production walker and do not keep a duplicate raw walker in
> the tests as its replacement. Derive population/armed counts; never assert
> the historical 52-attribute probe or 1,365 armed count as the population.
>
> Convert/load all callers before retirement. Before the coherent commit run
> this production load command, expanded for newly discovered owners:
>
> clojure -M -e "(require 'seon.schema 'seon.schema.internal
> 'seon.schema.datahike 'seon.schema.edn 'seon.agent 'seon.ai 'seon.cluster
> 'seon.cluster.source 'seon.config 'seon.db 'seon.error 'seon.fn 'seon.issue
> 'seon.maintenance 'seon.print 'seon.program 'seon.render
> 'seon.render.transcript 'seon.render.walk 'seon.render.web
> 'seon.shell.jvm 'seon.turn)"
>
> Iterate with bin/test-fast --paths <all owned changed paths, including
> the deleted file and any parity data> -- seon.schema-test
> seon.schema.datahike-test seon.schema.edn-test seon.schema-audit-test
> seon.db-test seon.owned-value-test seon.program-test seon.fn-test
> seon.error-test seon.error-class-schema-test seon.config-test
> seon.config-application-test seon.instrument-test seon.flow-test
> seon.maintenance-test seon.problems-test seon.reset-edges-test
> seon.render-coverage-test seon.cluster.turn-test
> plus affected namespaces selected from the current graph/caller inventory.
> This is a namespace selection, not a promise that every unchanged green
> member executes. Record executed/unchanged/unavailable distinctly. The
> orchestrator owns bin/test --paths and --platform, and any missing baseline
> preparation. No cold gates, --all or --full in this lane.
>
> One foreground JVM at a time, never a background test/probe, never two
> simultaneous JVMs. No worktrees. At shared-tree breakage use the fast
> HEAD-plus-owned-paths snapshot; identify the exact foreign boundary and
> continue independent work without editing that boundary. If admission
> itself refuses the snapshot, report it to the orchestrator, continue
> source/census work, and keep deletion uncommitted until callers/load are
> provable. Never publish a retirement commit that leaves a caller behind.
>
> Follow repository live verification after publication/adoption is coordinated.
> Identify whether evidence exercised a hot-reloaded Var, in-place adoption,
> or an owned scratch fork. Never stop, restart or refork default. Do not
> re-enable the owner's paused hook. No incompatible native-schema change
> is expected from an equal derivation; a reset request here requires naming
> the mismatch, not treating reset as a way to conceal it. Report any live
> or browser proof still owed explicitly.
>
> **Deliver:** one path-limited coherent retirement/caller commit; complete
> changed-path list; refreshed caller census with no executable form-namespace
> references; measured before/after LOC and compiler/registry-copy counts;
> baseline generation identity, full equality result and mismatch count;
> canonical fast tally, property/ref/component/tuple/codec evidence; exact
> load and cold/platform/live proof boundaries in the dated landing note.
> Estimate **3–5 lane-days**, excluding coordination and gate queue time:
> the measured caller surface exceeds the PRD's provisional 8–15 files.
> Stop when this step is committed, before step 3 or any storage/validation
> guarantee change. At a genuine design decision, stop before production
> changes and present exactly three concrete options: simplest viable
> constraint first/recommended, each with guarantee, cost and what is lost.

## Canonical proof and independent equality oracle

The implementation lane creates the evidence; this design lane ran no JVM.
Existing bridge tests are in `test/seon/schema/datahike_test.clj:95`, `:109`,
`:124`, `:141`, `:180`, `:197`, `:212`, `:289`, `:325`, `:445`. Extend those
class regressions rather than building another test harness.

| Proof | Required observation |
|---|---|
| Complete population parity | Before the cut, use the landed step-1 canonical armed fixture to record the exact canonical input identities/definitions, selected attribute keys and ordered native attribute maps from the old bridge. Mechanically capture the expected data in `test/seon/schema/datahike_parity.edn`, with baseline commit and input digest; it is dated evidence, not an authored schema roster. After the cut, compare the complete compiled result to it. Report both key-set differences and per-key map differences. |
| Honest recurring oracle | The parity regression also verifies its captured input identity against the fixture's current canonical population; drift fails loudly and requires an explained baseline update. Never regenerate expected output with the new function during the assertion. Preserve the capture command in the landing note/test owner. Do not compare against native schema installed by the same new derivation and call that an independent old/new proof. A baseline that cannot populate is an unavailable proof, not an empty expected table. |
| Separate representations | Compare native attribute maps including absent/present facets, tupleTypes and secondary-only flags; compare storage-selection and storable-property sets separately. Preserve canonical authored forms byte-for-byte except the explicitly removed inert cardinality property. Record both its before/after input identity and unchanged native result. |
| All active combinations | Derive coverage from the entire fixture population, then use canonical extra-schema for unrepresented combinations: value uniqueness, alias and wrapper property precedence, local shadowing, scalar and many, literals and keyword enums, same-native and heterogeneous unions, tuples, float/double secondary-only, index/noHistory, symbols, refs and components. An uncovered combination is reported, never implied by the three-root historical probe. |
| Entity composition | Real `:seon.ns/ns`, `:seon.error.occurrence/occurrence` and conjunctive `:my.fs/error`, plus alias/local-ref cases; required inherited members are retained, optional members remain optional, conflicting members and weakening requiredness refuse. Nested value maps, enum payload maps, property/generator data, union alternatives and widened component maps do not leak entries into their containing entity. |
| Component and codec parity | Accept the existing ref-or-owned-map transaction values; native ref/isComponent still derives. Real transactions cover map/add and transaction-function output, two-keyword many values, symbols, canonical EDN encoding/decoding and malformed/noncanonical/logically invalid EDN refusal. Stored entity validation and selector-derived pulled grammar remain separate; retain tuple logical validation. |
| Admission and construction | Retain missing-reference/cycle/role/exemption/error-facet refusals, config composite derivation before full construction, local recursive registries, pure-data compose/materialize, incremental declaration and partial canonical-row construction. Diagnostic malformed input remains evidence, not a requirement to successfully compile an invalid schema. |
| Generations and costs | Two disagreeing generations give independent navigation/storage answers. Replacing a leaf refreshes affected entries and contracts while held old roots keep their generation. Count named provider calls and full registry copies after acquisition; repeated navigation must not recompile the population. No scratch timing threshold or increased allocation bound. |
| Explanations and deletion | Invalid values retain native `:in` and `:path`; checks assert value/member/`:in`. Run existing final-state required-ref, swept-ref, component child-only/reparenting/ownership and program-referrer regressions without weakening them. These are preservation tests, not authority to alter the writer. |

## Dependency ledger — read from the pinned implementation

Malli pin observed: `606083c5c5b388e84d169c7080af33ed3ec242ae`.
Every navigation claim below is grounded in
`reference-code/malli/src/malli/core.cljc`; Registry behavior additionally
names its actual registry owner. Line numbers refer to that pin.

| Mechanism already supplied | Exact source and consequence |
|---|---|
| Node type and constructor | `reference-code/malli/src/malli/core.cljc:23–28`, `:30–43`, `:2510–2515`, `:2604–2609`: `-parent`/`parent` returns the **IntoSchema constructor**, not a containing schema or inheritance edge. `m/type` asks that constructor for `-type`. Do not walk `-parent` to find entity ancestors. Object metadata `:type ::schema` is an implementation marker (`:2069`), not a Seon entity kind. |
| Schema reuse | `reference-code/malli/src/malli/core.cljc:2550–2572`: passing a Schema returns it unchanged; a named lookup may create a pointer. Use the retained `mr/schema` root (`reference-code/malli/src/malli/registry.cljc:97–105`); Registry is lookup/enumeration, not an associative map (`:11–13`). |
| Properties/options/children | `reference-code/malli/src/malli/core.cljc:2581–2602`: original properties and options, resolved schema children, entry triples `[key properties child]`. The entry parser builds those triples at `:487–518`. This replaces `first/rest/remove map?` schema navigation. Literal children remain literal data; it is not true that every child value is a Schema. |
| Entries and entry properties | `reference-code/malli/src/malli/core.cljc:2771–2798`, `:1168–1201`: `m/entries` applies to EntrySchema, exposes val wrappers carrying entry properties; `::m/walk-entry-vals` controls whether walkers see those wrappers. It does not flatten an :and. |
| Postorder walking | `reference-code/malli/src/malli/core.cljc:2611–2624`, `:401–411`: callback receives `[schema path walked-children options]`; leaf walking does not recurse into literal children. The built-in callback walker accepts nodes; select entity semantics in the fold rather than accumulating every visited map as an entity. |
| Reference identity and scope | `reference-code/malli/src/malli/core.cljc:67–69`, `:102`, `:1943–1949`, `:1964–1977`, `:2008–2015`, `:2084–2103`: explicit refs and named schema pointers have different walk controls (`::m/walk-refs`, `::m/walk-schema-refs`). Explicit-ref traversal tracks visited refs. Retain captured scopes and existing canonical-cycle admission; do not assume a bare name is a globally unique recursive scope. |
| Dereference | `reference-code/malli/src/malli/core.cljc:2818–2846`: deref follows one top-level reference; deref-all repeats; deref-recursive reconstructs and excludes explicit :ref traversal. Preserve ref identity before following the target; recursive reconstruction is not necessary for ordinary navigation. |
| Conjunction | `reference-code/malli/src/malli/core.cljc:825–869`: each child compiles and validates independently; parent properties remain on the parent. Seon still owns entity-entry aggregation/conflict policy. Default merge is replacement, not conjunction (`reference-code/malli/src/malli/util.cljc:53–89`). |
| Reconstruction | `reference-code/malli/src/malli/core.cljc:417–429`, `:2499–2508`: reuse the node's constructor, properties, children and options; `-set-children` retains an unchanged node. This is the seam for necessary component-grammar transformation, not a new raw vector interpreter. |
| Caches and explanations | `reference-code/malli/src/malli/core.cljc:345–361`, `:2626–2657`: retained Schema caches validator/explainer products; public explainer returns native errors and may allocate an outer function. No new Seon plain-validator cache or path translator. |

First-party idioms already present: `src/seon/schema.clj:59` walks compiled
references, `:1626` inspects compiled map shape, `:1635` reads compiled
required entries, and `src/seon/schema/internal.cljc:195` walks compiled
admission positions. These are implementation seams, not evidence that
all their callers already use compiled navigation.

## Step-1 boundary and dated source identities

Read the PRD **all sections**, both named research notes **end to end**,
`src/seon/schema/form.cljc`, `src/seon/schema/datahike.clj`, working-tree
`src/seon/schema.clj` and `src/seon/schema/internal.cljc` **end to end**;
read AGENTS §§1–3 and lane rules 11–16, the context-generation roadmap entry,
and the applicable design/testing skills. No more local AGENTS file applies
to this document. Inspected `git show HEAD:src/seon/schema.clj` explicitly
at its registry, construction, retained-root/value API and retirement seams,
as well as the working-tree diff. HEAD and working bytes are not conflated.

The census began at HEAD `2cbb2cdad9d602e264af84d12133e05f4a4d376f` and was
rechecked at `085fd1433d495d2d58ea46499324503eb3d65691` as publication landed.
This document uses the requested 2026-09-21 work-item date; observations were
made in the 2026-09-20 session. It is a dated mixed-tree source census,
not runtime evidence or a claim all observed bytes belong to one commit.

| File | HEAD lines / SHA-256 | Working lines / SHA-256 |
|---|---|---|
| `src/seon/schema/form.cljc` | 216 / `96f1899e23615b5e031e07766e7e58100b07066cc5e003d2877b621cfd14e6d3` | identical |
| `src/seon/schema/datahike.clj` | 635 / `e4291b3dc28dd8076e3541303f0212b82f3be79d4d5c582dc7a36d5615e925f8` | identical |
| `src/seon/schema.clj` | 3,905 / `132b98a790acf181585f2e123cd9470ffa6d413f83084e5aa00bef48230ce131` | 3,863 / `dc4842206cef2b14e372695e39ac03faa0a5eb78c303933bd3d9f3da3a811900` |
| `src/seon/schema/internal.cljc` | 452 / `167b637638af5f9e4c9d56b8a8d90b1fb3e351a14ebf8dbd4fd6a6930a3830b0` | 453 / `16d05a643f0c52329e771a13134b53478e6cc689032619df945e6c0e837ec397` |

HEAD's `projection-registry` at `src/seon/schema.clj:419` still compiles on
lookup; its build path discards/recompiles roots and its value APIs accept
forms/ambient arities. Working `projection-registry` at `:419` prepares
fixed inputs, realizes named declarations serially and exports one fast
registry. Working `declaration-projection :1190`, `build-projection :1851`,
`materialize-projection :2323`, `projection-with-schema :2732` and
`projection-with-function-contract :3151` converge there. Working
`projection-validator :3547` and `projection-explainer :3633` use
`mr/schema`; candidate value APIs are projection-only. Working
`internal/assert-compilable-schema! :337` honors supplied registry options
at `:364`, avoiding its old per-declaration copy.

Step 1 leaves the raw walker, bridge interpretation, form-based shape
catalogs and forms-only construction requests in place. Examples requiring
explicit conversion: storage-admission callbacks in schema `:1387` and
`:1939`, shape rows `:1830`, pure-data shape indexes `:2135`, pulled grammar
`:2910`, canonical row partial-population path `:3427`, canonical database
attributes `:3487`. Do not call these forms-only maps retained generations.
The checkpoint note still reported an unlanded slice with outstanding
required green/cold/live proof during this read; step 2 must recheck the
actual coherent landing, not repeat that checkpoint as current truth.

### Held-path coordination

The [publication spec](publication-dissolution-spec-2026-09-20.md#owned-paths)
assigns `src/seon/cluster/source.clj`, the publication region of
`src/seon/cluster.clj`, `src/seon/fn.clj` analysis seams,
`src/seon/test/cache.clj`, `bin/test`, `bin/seon-hook`,
`script/seon/fresh_operator.clj`, source/finding resources and their tests.
Observed publication work also touched fn analyzer/signature and test
selection files. The actual walker overlaps are **cluster/source.clj,
cluster.clj and fn.clj**; the other publication paths are not granted to
this lane. The step-1 note additionally named test/selection and the
operator/recorder boundary as held verification surfaces.

At the initial census fn/source were dirty. At `085fd1433`, publication's
commits had made fn.clj and cluster/source.clj clean; cluster.clj and the
step-1 schema/caller slice remained dirty. Thus the historical hold is not
a permanent prohibition. Recheck status and coordinate the remaining
publication work before launch; step 1's full caller landing and path
release remain prerequisites. Never edit a foreign in-flight hunk to make
a load or fast run pass.

## Raw-walker function census and disposition

Inclusive spans below include contracts/comments and separating blank lines.
**N** = navigation supplied by compiled schemas, with any listed domain
policy retained at its owner; **P** = non-navigation behavior moved intact;
**D** = dead at this census. There are **13 top-level functions**, three
data Vars, and five named local functions. Anonymous callbacks belong to
their enclosing function spans. All external callers follow in the next
table; the internal calls here complete the namespace's caller census.

| Definition in `src/seon/schema/form.cljc` | Class / replacement | Calls inside that file |
|---|---|---|
| `primitive-schema-forms :5–8` | D: no source/test caller; :inst already declared in malli.edn. Delete. | none |
| `database-attribute-properties :9–16` | P: persistence-facet selection belongs in datahike's attribute derivation. | `database-attributes :153` |
| `attr-form-properties :17–23` | N: m/properties on the selected compiled node; preserve explicit inheritance policy separately. | `:64,94,95,119,138` |
| `entity-map-form :24–66` | N + policy: compiled composition/entries in internal; retain ordering/conflict/requiredness rules. | `:73,82,93` |
| locals `collect :28`, `entries :42`, `optional? :44`, `scalar :46` | N: reference/type/children navigation and entry properties; refusal/merge policy stays internal. | collect `:34,38,39,49`; entries `:63,65`; optional? `:56,61`; scalar `:48,55` |
| `map-shape? :67–74` | N: compiled entity composition; preserve non-map outcome. | self `:72`; database-attributes `:140` |
| `map-entries :75–85` | N: compiled entry triples + retained conjunction policy. | self `:80`; database-attributes `:149` |
| `schema-properties :86–96` | N: explicit compiled parent/map property interpretation. | self `:91`; database-attributes `:142` |
| `extends-schema? :97–110`; local `extends? :101` | N: only alias/conjunction/ref extension edges, never arbitrary keyword payloads. | extends? self `:105,107`, root `:109`; no other local caller |
| `namespaced-properties :111–120` | N + P: read compiled properties; qualified/non-nil filtering stays with property-fact/bridge owner. | property-attributes `:127` |
| `property-attributes :121–129` | N + P: derive qualified property key set from selected compiled roots, then bridge storability. | no local caller |
| `database-attributes :130–158` | N + P: m/walk-derived storage selection in datahike, distinct from extra storable metadata properties. | no local caller |
| `component-entity-key :159–162` | P: move target identity with component preparation in schema; keep the declared target. | `:168,170` |
| `widened-component-child :163–171` | P: idempotent ref-or-owned-map transaction grammar, reconstructed from compiled children. | widen-component-children `:197` |
| `widen-component-children :172–201` | P: move to existing schema compilation owner; raw payload postwalk disappears. | no local caller |
| `enum-members :202–211` | N: m/type :enum and literal m/children; non-enum still empty. | no local caller |
| `nilable-value-schema? :212–216` | N: m/type :maybe on the appropriate construction node; keep admission policy and diagnostics. | no local caller |

## Dated external caller inventory

Inventory searches used `rg`, then inspected alias-qualified matches and
their enclosing definitions. **37 files** mention the namespace: the
retired file, **22 production consumers and 14 test consumers**. In this
table every call is N except schema's widening call (P); filtering and
storage/admission decisions at N callers remain domain policy. No external
call in this table is presumed dead. Line lists identify every occurrence;
requires and the two source docstrings are removed with the conversion.

| Caller path | Navigation calls / location and responsibility |
|---|---|
| `src/seon/agent.clj` | attr-form-properties `:189,193,203` — settings labels/defaults/help. |
| `src/seon/ai.clj` | attr-form-properties `:360,573` — request attributes/config properties. |
| `src/seon/cluster.clj` | map-entries `:1258`; attr-form-properties `:1265` — activation requirements. Coordinate publication/step-1 ownership. |
| `src/seon/cluster/source.clj` | attr-form-properties `:524` — result-recording schema metadata; publication overlap. |
| `src/seon/config.clj` | attr-form-properties `:170,193` — dial selection/defaults. |
| `src/seon/db.clj` | attr-form-properties `:2124,3535,3762,3983,4145`; map-entries `:2884,3245`; map-shape? `:3237`; schema-properties `:3238` — pulled target, row identity, write schema selection, owned targets, render targets, retention and transaction attribute plan. Navigation only; writer policy unchanged. |
| `src/seon/error.clj` | database-attributes `:254,1672`; map-entries `:261,1513,1543,1676`; attr-form-properties `:262,1682`; extends-schema? `:1805` — observation storage/selection and facet discovery. |
| `src/seon/fn.clj` | attr-form-properties `:556,765`; database-attributes `:1632` — function-value refs, interpreter bindings, contract audit; publication overlap. |
| `src/seon/issue.clj` | attr-form-properties `:169` — citation metadata parsed from persisted schema rows; carry the same database's generation. |
| `src/seon/maintenance.clj` | namespaced-properties `:26`; attr-form-properties `:375` — result projection and attention rules. |
| `src/seon/print.cljc` | attr-form-properties `:349` — declared value projection. |
| `src/seon/program.cljc` | attr-form-properties `:90,137`; map-shape? `:148`; map-entries `:151`; schema-properties `:163` — injected symbols and declared program ownership. |
| `src/seon/render.clj` | map-entries `:375`; attr-form-properties `:403` — render selection; preserve forms emitted as output. |
| `src/seon/render/transcript.clj` | attr-form-properties `:1497` — emission label. |
| `src/seon/render/walk.clj` | schema-properties `:97`; attr-form-properties `:715` — declared concerns/acquisition. |
| `src/seon/render/web.clj` | map-entries `:690`; attr-form-properties `:705,1259,1264,1266,1679` — units, descriptions, block metadata, debug acquisition. |
| `src/seon/schema.clj` | P widening `:313` (doc link `:239`); attr-form-properties `:341,1174,1615,1833,2903,2969,3661`; enum-members `:1496`; schema-properties `:1832`; map-entries `:1838,2043,2149,2876` — construction, policy, catalogs and pulled shapes. |
| `src/seon/schema/datahike.clj` | attr-form-properties `:157,247,357`; nilable-value-schema? `:171`; namespaced-properties `:318`; property-attributes `:333`; database-attributes `:336` — storage/codec interpretation. |
| `src/seon/schema/edn.clj` | attr-form-properties `:36,46,49,114` — config-dial discovery/composites/defaults before final compilation; construction coordination is mandatory. |
| `src/seon/schema/internal.cljc` | extends-schema? `:125`; map-entries `:126,128,154,303,322`; schema-properties `:133,175`; attr-form-properties `:157,291`; map-shape? `:165,299`; nilable-value-schema? `:412` — admission, identity and required-entry policy. |
| `src/seon/shell/jvm.clj` | attr-form-properties `:90` — environment override declarations. |
| `src/seon/turn.clj` | database-attributes `:999`; map-entries `:3190` — changed schema attributes and committed attribute selection. |
| `test/seon/cluster/turn_test.clj` | enum-members `:3012`. |
| `test/seon/config_application_test.clj` | attr-form-properties `:112`. |
| `test/seon/config_test.clj` | attr-form-properties `:44`. |
| `test/seon/error_class_schema_test.clj` | namespaced-properties `:26`. |
| `test/seon/error_test.clj` | attr-form-properties `:1270`; map-entries `:1272`. |
| `test/seon/maintenance_test.clj` | schema-properties `:61`. |
| `test/seon/owned_value_test.clj` | attr-form-properties `:105,108`. |
| `test/seon/problems_test.clj` | schema-properties `:196,208`; map-entries `:200`. |
| `test/seon/program_test.clj` | attr-form-properties `:900,1002`; map-entries `:964,992`. |
| `test/seon/render_coverage_test.clj` | schema-properties `:50`. |
| `test/seon/reset_edges_test.clj` | schema-properties `:119`; map-entries `:122`; attr-form-properties `:124`. |
| `test/seon/schema/edn_test.clj` | attr-form-properties `:30,427,446`; map-entries `:41,400,451,459`. |
| `test/seon/schema_audit_test.clj` | database-attributes `:31`. |
| `test/seon/schema_test.clj` | attr-form-properties `:333,1474`; map-shape? `:374`; map-entries `:375,1425,1426,1473,1481`; schema-properties `:380,1465`; database-attributes `:1455`; extends-schema? `:1466`. |

### Secondary navigation and construction callers

Deleting only the form namespace leaves the parallel raw interpreter in
datahike.clj. Its **ten navigation functions** must disappear or become
compiled-node operations with all callers converted: form-children `:17`,
resolve-malli-form-in `:26`, resolve-malli-form `:47`, form-head `:71`,
resolve-datahike-form-in `:82`, resolve-datahike-form `:92`,
form->cardinality `:196`, form->cardinality-in `:206`,
form->child-form `:214`, form->child-form-in `:225`.
Their internal callers are datahike.clj `:43,52,87–89,97,128–133,145,162,
192,200–202,208–210,219–222,227–230,246–270,353–365`.
The value-type interpreter `:124`/`:188`, attribute mapping `:233`, selection
`:311–345` and EDN decision `:347` retain their domain behavior on compiled
inputs; codec traversal of **transaction values** at `:371–635` is not schema
navigation and is not deletion budget.

| External secondary caller | Conversion |
|---|---|
| `src/seon/schema.clj:50–57,2964,3006` | Delayed resolution of private cardinality/child helpers and pulled-entry calls; replace together, preserve logical versus native cardinality and emitted pull-result forms. |
| `src/seon/db.clj:2125,3236,3323,3330,3369–3370,3450,3455,3459,3984` | Alias/ref inspection, scalar/many normalization and compiled attribute plans. Preserve writer assembly and validation. |
| `test/seon/schema_test.clj:310,314,319–321,330–334,386` | Reference/child/value-generation inspection and ref storage assertion. |
| `test/seon/instrument_test.clj:998` | Resolved schema assertion. |
| `test/seon/flow_test.clj:904` | Var-metadata assertion names resolve-datahike-form-in; convert before retirement. |
| `src/seon/schema/internal.cljc:22–79,95–103,143–152` | permissive-positions' raw traversal, guarded-predicate-symbol's raw extraction, error admission's local boolean-form? walker. Convert navigation to compiled node/type/children while retaining authored-role/exemption and boolean-facet policy. |
| `src/seon/fn.clj:1690`; `test/seon/schema_audit_test.clj:35,64,79,84` | permissive-positions callers must supply the correct construction/compiled scope; preserve audit findings and justified-slot evidence. |
| `src/seon/schema/internal.cljc:293–304` | map-identity-entry-key has no source/test caller at this census. Delete the dead helper rather than port it. |
| `src/seon/schema.clj:1700–1760` | Raw function-arities / arity-render-input-form navigation feeding render-contract-observation: use retained function-schema arities and compiled children, preserve render compatibility policy and authored output evidence. |
| `resources/seon/schemas/seon.test.edn:38`; `resources/seon/schemas/seon.test.accretion.edn:64`; `test/seon/db_test.clj:55,57` | Remove inert :seon.db/cardinality :many; collection shape already determines many. Preserve equal native output. |

Refresh searches before any public retirement (tooling regexes, not
production regexes):

```bash
rg -n 'seon\.schema\.form' src test script bin resources
rg -n '\b(schema\.form|form)/(attr-form-properties|map-shape\?|map-entries|schema-properties|extends-schema\?|namespaced-properties|property-attributes|database-attributes|widen-component-children|enum-members|nilable-value-schema\?)' src test
rg -n 'primitive-schema-forms|component-entity-key|widened-component-child|map-identity-entry-key' src test
rg -n 'form-children|form-head|resolve-malli-form|resolve-datahike-form|form->child-form|form->cardinality|form->datahike-value-type|permissive-positions' src test script bin resources
rg -n ':seon.db/cardinality' src resources test
git show HEAD:src/seon/schema.clj
git diff -- src/seon/schema.clj src/seon/schema/internal.cljc
```

Historical research excerpts naming the deleted namespace remain archaeology;
they are not executable callers. Search namespace requires, qualified symbols,
Var quotes and delayed resolution as well as ordinary calls. Preserve
`:seon.schema/form` datoms: that attribute is not the namespace being deleted.

## Measured deletion budget and estimate

These are **physical LOC measured now**, including contracts and comments,
not a claim that all policy in a touched span disappears. The following
spans do not overlap; replacements outside them and mechanical callers are
not counted twice.

| Current owner span | LOC | Disposition |
|---|---:|---|
| `src/seon/schema/form.cljc:1–216` | 216 | Entire file deleted after policy moves and callers convert. This is the exact file-deletion obligation. |
| `src/seon/schema/datahike.clj:17–55,71–98,196–232` | 104 | Raw alias/head/child/cardinality navigation shells; preserve their required semantic operations via compiled APIs, not compatibility parsers. |
| `src/seon/schema/datahike.clj:124–194,233–276,311–338,347–370` | 167 | Mixed navigation/storage policy rewritten smaller; mapping/codec/refusal semantics retained. |
| `src/seon/schema/internal.cljc:22–79` | 58 | Raw policy walker replaced by compiled traversal; policy findings retained. |
| **Measured owner surface** | **545** | 216 mandatory file deletion + 329 navigation/mixed owner LOC under review; not 545 guaranteed net deleted lines. |

Separately, the unused internal identity helper occupies `:293–304` (12
lines); small predicate/render-contract navigation and caller edits are
additional measured-at-landing opportunities. Do not count the complete
635-line bridge as disposable or delete the compiled render compatibility
policy merely because the older review marked a neighboring span.

PRD §4 budget: **350–600 gross deletions, 180–350 additions**, excluding
tests, initially **2–4 lane-days / 8–15 files**. The present census requires
37 direct files plus four additional test files, two annotation resources,
parity data and the landing note: **45 planned paths**, before newly found
caller-interface propagation. Revised scheduling estimate: **3–5 lane-days**
for construction proof, conversion, canonical parity and load/caller
verification. The original deletion/addition range remains a planning range,
not permission to drop domain contracts to hit a number. Record actual
`git diff --numstat` and moved behavior at landing.

## Design-lane verification boundary

Only this document is authored by this lane. No source/test/resource edits,
JVM launches, runtime calls, worktrees, cluster operations or gates were
performed. The narrow no-worktree/no-JVM assignment governs over the generic
fallback paragraph. The artifact records source evidence and future proof
obligations; it does not claim the new walker exists or parity has passed.
Foreign step-1 and publication changes were read and preserved. Document
diff, path/line, inventory and whitespace checks are this lane's proof;
commit only this document path and stop.
