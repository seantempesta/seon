---
title: Content identities, costs, and reusable results — independent Astra design
type: research
date: 2026-10-14
status: proposal; measurements complete; implementation proofs pending
---

# Content identities, costs, and reusable results

Seon already has a central cache, declaration and definition digests, Datahike attribute revisions, read evidence, and frozen SCI program contexts. Keep those mechanisms. Canonicalize a derived value at its producer when its inputs change; ordinary reads should use the identity already held by their database, projection, or context. Computing a fresh digest of a whole population on every read is more expensive than several of the computations it would save.

The first opportunity is duplicated database projections, followed by keeping program contexts and parsed facts resident. At this session's first detailed observation, database projections occupied 406.9 MB of a 509.1 MB cache. A later exact comparison, including source strings and admissions, found 36 entries but 9 distinct input maps. Meanwhile program contexts were evicted and rebuilt: one rebuild cost 251.14 ms and allocated 336.1 MB on the calling thread. These are present observations, not the much larger cumulative costs of an older JVM in the Gemini notes.

The simplest composition is a cheap revision-to-content alias followed by one immutable product per exact input. No new cache, identity registry, Merkle tree, cache policy, or general memoization layer is proposed. A predecessor is an optimization input only after a proof shows that deriving with it gives the same answer as deriving without it. A branch name can disappear only when the computation does not observe it; an actual comparison base, host root, or supplied agent value cannot disappear merely because it is inconvenient to share.

## Evidence boundary and authorities

Assignment: independent Astra proposal for lane `truths-astra`. Only this note is edited. No other `truths-*.md` proposal was read; no other lane was contacted. This is a design and read-only measurement slice, not an implemented cache change.

Read: `AGENTS.md`; `dig-content-keys.md`; `cache-fill.md`; `overlay-hybrid.md`; `gemini/handoff.md`; the four `gemini/perf-*.md` reports; the data-oriented Clojure, REPL, Datahike and testing skills. Claims used below were checked against the producer and caller, and the top three were measured through MCP JVM evaluation on default with explicit root and cluster. No SCI requests as root, program definitions, source reloads, cache clearing, heap dumps, or default restarts were performed.

Default reported degraded before work: existing errors and health findings, including an untested cache task, were present. The live process was PID 45705, started 07:15:22 UTC, archive `86beed937521865e6569f7e8d660f356e870457e`. The working HEAD when this path was claimed was `110d1e002e2271eea06fc64b4c67260bf614da6b`. Later working source includes other lanes' changes; a source finding is not a claim that those bytes were installed. The running archive is the authority for the measurements.

Dependency seams read:

| Dependency / pin | Seam and guarantee used | Input, recomputation, cost |
|---|---|---|
| Datahike `6d49110d336f8e21bf828d006e3a885ac1c12a34` | `datahike.db/advance-cache-context`, stored revisions in `datahike.writing`, and Seon's `seon.db/content-revisions` | Attribute revisions advance with changed attributes; missing revisions use the conservative revision. Persisted revisions can agree across forks. Reading a fixed set is proportional to that set, not all facts. A new revision need not imply different logical values. |
| Datahike, same pin | `datahike.query/source-context-unchanged?`, `datahike.db/committed-value-identity`; `seon.db/read-evidence-current?` | Connection/generation identity is useful for a fast local alias. Dependency plans and their source arguments describe actual reads, including absence. Do not confuse that local identity with the content of every result. |
| Malli `dd13ff81139159778edb0a67b491eccc0baa991d` | `malli.core/validator`, `malli.core/explainer`, `malli.core/-cached`, `malli.core/-instrument` | A held compiled schema caches its products. Compile once for the effective registry, schema and options; execute the validator on each supplied value. Instrumentation remains in place. |
| SCI `eb3290d5c5ed72133ae1947492ecd324413868ed` | `sci.core/fork`, the maintained fork's context freeze, `sci.impl.fns/in-defining-ctx`; callers in `seon.sci.eval` | Namespace structures can be shared while forks own private state. Freezing namespace maps does not make an arbitrary atom, host handle, or effectful initializer immutable. A callback without an active context still has its defining context. |
| Caffeine 3.2.4, dependency jar | `AsyncCache`, `Caffeine.maximumWeight`, through `seon.cache/lookup` | Concurrent misses share a future; the winning caller currently derives synchronously. Admission/eviction is Caffeine's. Weights are supplied at insertion. Seon's per-kind counters are its own counters, not Caffeine `recordStats`. |

History reconciled: `fbf611da5` moved the database LRUs into the central cache; `a80d93e3e` retained parser facts by source; `f8a2773be` installed the packaged-population/authored-shape digest change; `95f4aab31` indexed var-quotes; `76599e44a` changed namespace digest derivation. Those are not new phases here.

Corrections to the dig which affect this design:

- `seon.schema/projection-identity` is a fingerprint and version, not object identity. Its fingerprint uses 32-bit hashes; it is not an exact content identity.
- `seon.schema.edn/packaged-population` already deduplicates by declaration digest after its URL alias. The older 52/62-population report is not today's state.
- Attribute revisions are not inherently branch-local: the maintained Datahike fork persists them. Equal values reached through different writes may still have different revisions, which is an opportunity for sharing, not evidence of unsoundness.
- `seon.test.cache/test-input-digest` covers its declared external input roots and gitlinks. It is not a digest of every fixture, dynamic binding, clock, or database read a test could observe. The evaluator label alone is not the evaluator implementation's identity.
- `seon.schema/build-projection` bypasses the central `::projection` lookup when a reusable projection is supplied. An exact content key on the other arity does not deduplicate all database-produced projections.
- Predicate functions can retain live JVM Vars. Their current roots are not proved immutable by equal schema text.
- `seon.task/file-declarations` uses the path to derive the task namespace. Dropping it unconditionally changes meaning.

## What counts as a reusable truth

For a derivation `f`, the key describes its actual value inputs and the implementation that gives them meaning. The output includes refusals when they are deterministic data. Call ownership, occurrence counting, delivery, persistence and profiling are fresh effects around that value, not part of a reusable effect result.

Use three existing forms of identity deliberately:

1. **Held exact content:** immutable maps/sets or existing producer digests. Use the exact map when no cheap digest exists; create it at a change, not every read. Clojure's hash is a lookup accelerator with equality, never the sole identity.
2. **Read evidence:** query/selector, supplied arguments, relevant revisions and dependency plan, with positive and negative observations. Use the already-held evidence and Datahike's revision shortcut. A prior read set is a candidate for replaying a derivation, not a claim that every future execution follows the same path.
3. **Actual runtime inputs:** interpreted private callable identity, host root, SCI bindings, supplied agent or capability, explicit seed/clock/effect result. Separate immutable code from mutable state at its producer. Keep context-local computations local when these are their real inputs.

Producer semantics also matter across reloads. Existing definition/reach digests should identify a reloadable producer and its transitive code dependencies. Process-pinned library code can be implicit within a process cache; do not compute the whole library digest on every lookup. A producer change either takes its new identity or retires that producer's old kind using the installed retirement mechanism. That is different from invalidating all values after unrelated database writes.

### Proof notation for every catalogue row

Each row below states inputs **I**, today's key **K**, the correction, and an **outside / inside** perturbation. This specifies a table test, not a claim that the proposed regression already exists or passed.

For every row: derive a finite value through the production owner on a disposable branch; read it through a second branch/SCI context with equal declared inputs; compare the schema-valid result with a fresh derivation; vary the named outside input and require the same value and no derivation when retained. Perturb each member of I separately, including deletions/absence, and require selection of the new input and equality with a fresh oracle. Use a counterexample where that input is observable. It is mathematically false that every change to a true input must change the output: two different programs may both return 1. The proof must distinguish changed inputs, not demand an injective function.

Memory column below is the observed retained bytes at the initial detailed snapshot, in decimal MB, or **unheld**. This is cache weight, not measured exclusive heap. Entry formulas and calibration limits follow separately. Cost codes name the corrected key's per-call work: **H** held identity/tuple, **R(n)** n selected revisions/read dependencies, **C(n)** content traversal at production/change, **T(n)** text processing, **E(n)** equal-map comparison on a canonical miss. Except where measured below, these are complexity statements, not claimed timings. No unmeasured correction is approved as a performance improvement until its key plus lookup is cheaper than the work avoided.

## Catalogue: database and schema products

Names in the first column are fully qualified cache kinds unless explicitly marked as a non-central derivation. Exact inputs include the producer semantics described above. Options means effective options, including admission and render-validation decisions, not just whichever options a caller happened to spell.

| Derivation; memory | I; current K | Gap and corrected key; cost | Outside / inside proof |
|---|---|---|---|
| `:seon.db/projection`; 149.743 MB | Builder; final forms, contracts, admissions, exports, predicate bindings, source strings. K = builder plus per-attribute datoms including entity/transaction identities. | Histories/EIDs over-key a compiler product. Canonicalize exact final compiler inputs at `seon.schema/build-projection`, with source provenance explicitly included or attached separately. Retain revision alias. H on reads; C/E at a changed population. | Different branch, EIDs, transaction history / each population section, effective option and predicate implementation. |
| `:seon.db/value-projection`; 257.110 MB | Same final input as above. K also has an advanced form of builder, predecessor revision and written datoms. | Same final product can survive under different advance paths and both kinds. Both producers converge on the same canonical product; advance history remains derivation machinery only. H; C/E only on a change. | Different path to the same final value / changed final declaration or source string. |
| `:seon.db/projection-alias`; 0.021 MB | Scoped revision/commit/as-of identity → canonical product key. | This is an alias, not another projection. Keep cheap local aliases; alias values must not retain old giant datom-vector keys after products are evicted. H/R(fixed attributes). | New alias for same population / changed declaration revision; old alias still answers its old basis. |
| `:seon.db/builder-digest`; 0.000256 MB | Loaded builder implementation and reaching code; K = commit or revision tuple. | Keep the alias, deduplicate the resulting digest. Do not substitute database source for the actual loaded builder. H then reach work only when loaded code changes. | Unrelated fact commit / actual builder or reached code reload. |
| `:seon.db/behavior-digest`; unheld | Declaration meaning excluding doc-only text, resolved names; K = stored definition digest. | Existing content key is adequate if definition digest covers resolution. No fresh database digest per read. H. | Branch, doc-only behavior-equivalent change / body or used alias resolution. |
| `:seon.schema/projection`; 21.435 MB | Forms, contracts, effective options, predicate meanings. K = those values for the no-base arity. | The supplied-base path bypasses canonical lookup. Use the same exact semantic inputs for both paths, after existing replacement logic has assembled them. Base is not K once confluence is proven. H fast path; E on equal independently built maps. | No base versus different reusable bases / forms, contracts, admissions, flags, exports, predicate bindings. |
| `:seon.schema/declaration-projection`; 38.348 MB | Forms and predicate bindings; K = `[forms predicates]`. | Already content keyed. Reuse canonical inputs; pass held declaration identity when available, never recompute a whole-form digest per call. H; E only on reconstituted input. | Equal non-identical forms / form or predicate change. |
| `:seon.schema/declaration-predicates`; 0.017 MB | Forms and resolution of named predicate functions; K = forms. | Resolve against the explicit producer environment; host Var roots can change while forms do not. Product may hold live Vars, but cached predicate answers require their actual meaning. H plus changed binding identity. | Other namespace reload / referenced predicate redefinition, deletion or private binding. |
| `:seon.schema/population-predicate-symbols`; 0.012 MB | Forms and contracts; K = both. | Correct pure syntax input. Keep; held canonical values prevent repeated equality. H. | Branch, predicate execution result / predicate symbol in either input. |
| `:seon.schema/canonical-keys`; 0 at snapshot | Names; K = names. | Correct. Keep exact names or held names set. H. | Order when set semantics apply / add or remove name. |
| `:seon.schema.edn/packaged-digest`; 0.002 MB | Immutable archive resource contents, found through URL. K = URL. | URL is a valid immutable-source alias in an archived JVM. Keep this cheap first hop; a mutable directory URL would not suffice. H after initial read. | Different archive URL with equal bytes / actual resource byte change in a new archive. |
| `:seon.schema.edn/packaged-population`; 4.237 MB | Declared forms and config-form derivation; K = declaration digest. | Already corrected. Inspect file/provenance members before asserting equal whole returned maps across origins; forms can share while origins remain caller data. H. | Equal resources at two URLs / schema or config-form input change. |
| `:seon.schema.datahike/vector-pull`; 0.923 MB | Storage-relevant schema, cells-installed flag, selector. K = projection fingerprint/version, flag, selector. | A 32-bit fingerprint alone is not equality. Use exact storage-schema content or its held structural digest, plus flag and selector. H + selector hash; no full projection digest per pull. | Unrelated function body / vector/component schema, selector nesting, cell-install state; constructed hash collision. |
| `:seon.schema.datahike/readable-identifier`; unheld | Identifier and fixed EDN reader semantics; K = identifier. | Correct within pinned reader lifetime. H/T(identifier length). | Branch / identifier spelling and reader semantics. |
| `:seon.schema.datahike/decoded-edn`; unheld | Decoder, storage readers, attribute and encoded text; K = those values. | Already explicit, including callable readers. Preserve nil through the existing wrapper value. Attribute remains for attribute-specific error data. H/T(text hash); don't add SHA per read. | Store/branch / text, reader mapping, decoder, error attribute. |

`seon.schema/projection-of-population` attaches `:seon.schema.projection/definition-strings` after compilation. Two projections with equal forms but different source strings are not equal whole values. The first implementation should canonicalize exact inputs, including provenance where returned; splitting a shared compiled product from per-basis source annotations is a possible later deletion of duplication, not permission to discard those annotations now. Likewise, admission options can change a refusal to a success even with identical forms.

## Catalogue: program derivation, files and tasks

| Derivation; memory | I; current K | Gap and corrected key; cost | Outside / inside proof |
|---|---|---|---|
| `:seon.program/authored-shapes`; 0.732 MB | Packaged declarations and shape derivation; K = declaration digest. | B4 installed. Keep. H. | Archive/branch without declaration change / declaration affecting shape. |
| `:seon.program/program-attributes`; 0.022 MB | Forms and schema admissions; K = both. | Already content. Hold canonical input identity. H. | Function source / program partition or admission. |
| `:seon.program/cluster-attributes`; unheld | Forms and schema admissions; K = both. | Same reasoning, separate result meaning. H. | Function source / cluster partition or admission. |
| `:seon.program/source-resolver-names`; 1.459 MB | Spelled tokens in source, then requested qualifier/symbol candidates. K = digest of source and both candidate sets. | Digest and parsing repeat when only candidate sets change. Cache parsed names by source; intersect with supplied sets. Reuse analyzer parse when it already holds the same facts. H/T(source hash) plus intersection; measure before replacement. | Filename or unrelated aliases / source token or matching candidate set. |
| `:seon.fn/population-targets`; 0.050 MB | Forms and invoked attributes; K = both. | Correct content; canonical inputs avoid equality work. H. | Branch, docs / relevant schema target or attribute. |
| `:seon.fn.analyzer/parsed-facts`; 16.970 MB | Source text and pinned parser semantics; K = text. Filename attached after. | Already correct and fast; do not add filename or fresh SHA. Eviction pressure, not a missing key, is today's measured problem. H for held string, T for first hash/equal independent string. | Same text at another filename / syntax, quoted symbol, task map or require form. |
| `:seon.cluster/derivation-world`; 1.821 MB | Forms, dependency relations, row shapes; K = those values. | Whole-world product legitimately reads the world. Keep one per content; downstream files should use only reached parts. H at use; C(world) only at change. | Runtime facts / form, dependency edge or row shape. |
| `:seon.cluster/file-derivation-key`; 4.716 MB | File code, analyzer configuration/inputs, start schemas and their reached forms. K also includes entire world key. | An unrelated schema changes this lookup even when selected inputs agree. Derive reached content from the held world, then key the file product by those inputs. Existing cross-file analysis gaps prevent declaring the artifact cache sound yet. H plus C(reached inputs) on change. | Unreached schema / reached schema, code, analyzer config, external dependency or start set. |
| `:seon.cluster/file-schema-keys`; 2.192 MB | File's derived declaration entities; K = relative path and derivation digest. | Digest can name semantic keys; retain path for any path-sensitive declaration meaning. No blanket path removal. H. | Archive root / declared schema key or path-derived namespace. |
| `:seon.cluster/branch-attribute-changes`; 0.000304 MB | Installed schema and desired declarations; K = both. | Correct. Branch admission is applied outside this product; retain that separation. H. | Branch label / storage schema or declaration diff. |
| `:seon.ref/document`; 1.028 MB | Text and document parser semantics; K = SHA-256 of UTF-8 text. | Correct, but text is digested on each lookup. Carry source digest where supplied, otherwise compare cost of direct text key. H if held, T(text) today; no measured win claimed. | File path / headings, anchors or reference spans. |
| `:seon.task/file-declarations`; 4.148 MB | Forms, path-derived namespace and file text; K = forms, path, text. | Path is a true input. Normalize only an irrelevant absolute checkout prefix, not the namespace-bearing path. H with canonical forms; T(text). | Same relative file in another checkout / relative path, task text or validating forms. |
| `:seon.task/task-basis`; 0.449 MB | Task-schema forms, selected task facts and names; K = forms, task-content, names. | Structurally content keyed. Verify task-content covers every graph/reference query, including missing targets. H plus C(selected task facts) at production. | Unrelated evaluation / named task, proof, next edge, referenced or absent target. |
| `:seon.task/tasks-tx`; 0.001536 MB | Forms, existing task facts, file declarations and comparison basis; K = all four. | Cache proposed transaction DATA only. Writer authorization/current basis and application are fresh. Read coverage of graph/ref checks needs proof before widening reuse. H once inputs held. | Unrelated runtime fact / desired task, old task fact, graph or relevant basis. |
| `:seon.config/effective`; 0.122 MB | Cluster's config facts, carried schema and declared defaults; K = commit, cluster, declaration digest. | Commit is safe but overbroad. Use actual config read evidence + carried/default declaration identity; keep cluster when it selects config. R(config reads), then H; measure against current sub-ms derivation first. | Message write / dial value, omitted default, new dial, carried schema or selected cluster. |

The already-recorded cross-file analysis tasks cited by the source (`aca1b25fa16a`, `50a6a9afcb64`, `52875b443ef0`) are reasons to prove the missing input at its producer, not to install a cache keyed by an incomplete artifact digest. Passing a whole-world stamp forever also does not solve the targeted recomputation problem.

## Catalogue: SCI and test evidence

| Derivation; memory | I; current K | Gap and corrected key; cost | Outside / inside proof |
|---|---|---|---|
| `:seon.sci.eval/loaded-digests`; 0 at snapshot | Loaded function declarations; K = store and loaded commit. | Valid retrieval alias, not canonical content. Share the resulting symbol→digest map by held population identity if it measurably helps. Do not enumerate all functions per acquisition. H, C(functions) only on loaded change. | Other facts/branch / loaded declaration digest. |
| `:seon.sci.eval/overlay-plan`; 0 | Actual loaded program, branch declarations, namespaces, tests and dependency closure; K = store, program revisions, loaded commit. | Keep alias; canonical product by held definitions/reach, with loaded implementation explicit. Use predecessor change data after overlay-impl's proof. R(program attributes), C(changed reach). | Branch label/unrelated write / declaration, namespace resolution, test or loaded host implementation. |
| `:seon.sci.eval/program-key`; 0 | Program revisions, relevant wrapper dials, actual loaded program. K = store, revisions, dial revisions, optional supplied-loaded commit. | Good cheap alias to content. Normalize supplied and derived loaded identity consistently; no whole projection hash per read. R(selected attributes); content-key ~1.079 ms measured on miss path. | Supplied versus resolved same loaded value / actual loaded value, program or wrapper dial. |
| `:seon.sci.eval/program-context`; 0 initially, 15.179 MB after probe | Loaded host program plus logical overlay plan, projection delta, wrapper dials; immutable derivation environment. K = base `[store loaded-commit]`, optionally plan digest, projection delta, dials. | Branch-independent already within a base. Do not erase base until host roots and private mutable values are separated. Predecessor must be semantically irrelevant; ambient connection must be absent at derivation. Share immutable program structures, fork private state. H on alias hit; R/C on change. | Branch, thread binding, derivation predecessor / changed callee, init, namespace, contract, dial or loaded host root. Include raw-thread callback and `defonce` cases from overlay-hybrid. |
| `:seon.sci.eval/refusal-recording`; unheld | An occurrence's production and delivery target; K = program,target. | This is effect deduplication, not a pure truth. Eviction must not invent another occurrence. Durable production identity belongs to error/overlay owner; share diagnostic value separately. H existing production id, writer effect once. | Cache eviction/unrelated program edit / new actual production or recipient delivery. |
| `:seon.test.runner/green`; unheld | Test definition, current reach, explicit/observed inputs, actual evaluator semantics. K = test symbol, reach digest, input digest, evaluator label. | B1 owns reuse. Change to reusable completed RESULT, red included, after filling input/evaluator gaps. A branch is not an input unless test observes it. H held digests + R(actual reads); no whole-program key. | Sibling branch with equal inputs / each reached definition, schema, fixture, task obligation, read, config, external input and evaluator implementation. |
| `:seon.test.runner/reach-attribute`; unheld | Attribute, shape, attribute contents. K = attribute, shape, revision. | Persisted revision is useful cross-fork identity. Keep fast alias; equal re-written contents may canonicalize at the derived map, not by hashing database facts on every selection. R(1); C(attribute contents) on miss. | Unrelated attribute / requested attribute fact, removal or shape. |
| `:seon.test.runner/reach-attribute-head`; unheld | Owner's current acceleration basis; K = owner,attribute,shape. | A latest pointer is not timeless truth. Keep it local; moving it must not evict another branch's valid canonical attribute product. H. | Another owner's advance / this owner's basis advance. |
| `:seon.test.runner/reach-changed-entities`; unheld | Attribute and resolved before/after revisions/bases; K = attribute,before,after. | History is meaningful to a diff. Preserve actual comparison bases; revision-only equivalence requires exact lineage guarantee. H plus delta traversal on miss. | Other owner pointing at same bases / before, after, attribute, retraction. |
| `:seon.test.runner/reach-node`; unheld | Declaration's relevant edges and behavior leaf; K = declaration map plus leaf. | Safe possible over-keying. Keep until evidence justifies selecting fewer declared members. H/cached map hash. | Branch and irrelevant metadata proven irrelevant / call, schema, task edge or behavior leaf. |
| `:seon.test.runner/reach-schema-node`; unheld | Schema key and form; K = both. | Correct syntax input. H. | Branch / schema form, including referenced schema. |
| `:seon.test.runner/reach-graph`; unheld | Selected tests and relevant declaration edges; K = revisions,selected-test set. | Cheap revision alias; canonical graph inputs are nodes/edges and selection. Share maps already built, not new whole-graph digests on reads. R then H. | Unrelated runtime write / edge or selected test. |
| `:seon.test.runner/reach-index`; unheld | Selected tests, nodes, schema leaves; K = those values. | Already content keyed. Weight shared graph once where actually retained; no duplicate value copy. H/E on independent equal graph. | Revision history / node, selected tests or schema leaf. |
| `:seon.test.runner/reach-index-by-revision`; unheld | Graph content AND covered test set. K = revisions. | Partially expanded coverage is not one complete immutable result at revisions alone. Include coverage in semantic input, or keep this entry explicitly an alias to immutable indexes with known coverage. R + H. | More unrelated tests elsewhere / requested coverage, edge or leaf. |
| `:seon.test.runner/reach-head`; unheld | Owner's current basis and index alias; K = owner. | Local pointer, not globally shared result. Keep canonical indexes independently. H. | Other branch / owner's new head. |
| `:seon.test.runner/reaching`; unheld | Graph, target, whole/selected test scope; K = revisions,target,optional test set. | Correct distinctions; canonical graph identity can replace overbroad revision alias only after it is held. H then graph walk on miss. | Runtime facts / target, graph edge or selected scope. |
| `:seon.test/edge-attributes`; unheld | Program schemas' test/reference edges; K = program revisions. | Alias over exact relevant schema product; use held forms/shape identity. H/R. | Function body / edge-declaring schema. |
| `:seon.test/destructive-reach`; unheld | Edge schemas and current classification dependencies; K = program and selected dynamic revisions. | Prove completeness, including conservative revisions and absent attributes. Reuse `seon.db/content-revisions`; do not hand-select an incomplete cache-context map. R then held graph identity. | Unrelated message / destructive annotation, edge or dynamic referenced declaration. |
| `:seon.test/host`; unheld | Test's host-bound reach and evaluator capabilities; K = destructive-reach key,test symbol. | Retain exact reach/capability semantics, not just source equality. Deterministic refusal may be retained once inputs complete. H. | Branch / host-bound declaration, macro or evaluator capability. |
| `:seon.test/commit-facts`; unheld | Commit's actual branch/parents; K = commit id. | Commit IS the input. Keep store identity if commit ids are scoped by implementation. Do not replace history with equal program contents. H. | Current branch head / different commit or parent. |
| `:seon.test/changes`; unheld | Resolved comparison base, candidate declaration contents and cluster semantics; working K = cluster,base,revisions. | Branch removal is sound only if base is resolved before keying. `changed-identities` can choose a fork base; that choice is a true input. B1 owns this row. R then H. | Branch label with same resolved base / different base or changed declaration. |
| `:seon.cluster.agent/basis-valid`; unheld | Task obligations/reaching program of the named function; K = commit,symbol. | Commit is conservative. Use the task/reach inputs already held if measured worthwhile, not a fresh graph read just to avoid a tiny lookup. H/R. | Unrelated recording / task obligation or reached definition. |

The owner's example holds for the same test program run over the same inputs. Same top-level function source alone does not establish those inputs. It may call a different callee, dereference a different Var, receive a different default, or query a different branch value. Declaring these at the producer strengthens general machinery rather than introducing a test-specific exemption.

## Catalogue: renders, observations and test tooling

| Derivation; memory | I; current K | Gap and corrected key; cost | Outside / inside proof |
|---|---|---|---|
| `:seon.render/call-reach`; unheld | Selected renderer and stored call-edge facts. K includes connection/generation, conservative and two attribute revisions, or temporal point. | Committed content revisions can share across forks; temporal views retain their actual as-of source/time semantics. R(2), then H. | Equal edges on sibling fork / renderer or call edge, temporal point affecting edges. |
| `:seon.render/invocation`; unheld | Selected callable, output face, supplied/defaulted arguments, selection evidence, reached code/schema and captured reads. K = branch scope plus 32-bit hashes of evidence. | Current full-evidence comparison prevents treating a hash collision as equality, but overwrites/branch scope block sharing. Key exact evidence/held digests and actual private callable; keep read dependency values. No branch scope unless observed. H + R(read evidence), no whole projection hash per render. | Branch label with equal observations / callable, face, argument, profile, agent/capability actually supplied, code, schema, positive or negative read. |
| `:seon.health/reads`; 0.282 MB | Discovered read set for a named producer; K = name. | This is a hint, not all future truth. Producer identity and conditional read coverage matter. Keep prior evidence to derive a candidate; result owns complete evidence. H. | Other consumer / code change or new control-flow read. |
| `:seon.health/derived`; 3.136 MB | Named derivation, actual code and captured DB inputs; K = loaded identity plus selected local revisions or commit. | Use read evidence and `seon.db/content-revisions` including conservative changes. Ambient derivation closure must be explicit code/input. R(read dependencies). | Unrelated fact and other code / observed fact, missing fact becoming present, producer or supplied option. |
| `:seon.health/replaced-roots`; 0.002 MB | Actual host roots and source files; K = global Var revision,files. | Host observation genuinely changes without database source changing. Narrow to held roots/files only if measurement justifies; do not identify live roots by source text alone. R(roots) at observation. | Unrelated Var / touched root or file. |
| `:seon.health/untested`; 0.100 MB | Test/program facts used by underlying derivation; outer K = committed identity. | Outer commit repeats after unrelated writes. Reuse the inner evidence-defined value; pending/deadline response is a fresh observation, not cached timeless status. R + H. | Evaluation recording / test evidence or definition. |
| `:seon.health/problems`; 0.0028 MB | Problem inputs and requested assembly semantics; outer K = committed identity. | Same distinction between immutable assembly and live freshness/pending status. R + H. | Unrelated commit / problem evidence, producer or assembly option. |
| `:seon.error.refusal/authored-namespace`; 0.0042 MB | Namespace, class resource, classpath basis, current loaded-namespace existence. K = namespace only. | `classify-namespace` also reads class-name and `find-ns`. Cache immutable classpath classification by actual inputs; evaluate the live sourceless-namespace part from current state. H plus live lookup, not TTL/retry. | Unrelated namespace / class-name resource or sourceless namespace creation/removal. |
| `:seon.contracts-compile-test/read-source`; test-only | Text and parser semantics; K = text. | Correct process-local pure input. Keep; charged value is 24×text length plus entry. H/T. | File name / source form. |
| `:seon.contracts-compile-test/schema-resources`; test-only | Paths, texts, schema derivation; K = path/text pairs. | Returned resources contain paths; preserve provenance. Share merged population separately only if useful. 50,000×resource count plus entry today. H/T, produced once per capture. | Checkout prefix only for normalized semantic population / resource text, relative path or resource set. |

## Repeated work outside central kinds

These rows complete the dig's non-cache derivations and the repeated stages in its referenced performance evidence. Some are better fixed by passing a held value than by storing another value.

| Derivation / owner | Actual inputs; current mechanism | Smallest composition, cost and memory | Outside / inside proof |
|---|---|---|---|
| `seon.schema/projection-cache-value`, contract products | Projection registry/predicate semantics, form, compilation mode/options. Per-projection core.cache map, products indexed by form. | Malli owns compiled validator/explainer caching. Share one canonical projection first. Dynamic arbitrary products must be centrally weighed or have a measured finite owning footprint; the current mutable map can grow after insertion. H; compile only on changed schema/option. | Another equal projection / transitive registry reference, predicate meaning or mode. |
| Other projection-local derivations: shapes, storage plans, default plans, render descriptors, identity descriptors, reference selectors | Relevant schema declarations/admissions and explicit selector or mode; keyed locally on the projection. | Hold once in the canonical projection or a central kind keyed by its held content and explicit parameter. Finite schema-wide products can be built/weighed once. Do not multiply caches per caller. H; no added copy for a caller already holding it. | Function-body-only change / relevant declaration, admission, selector or option. |
| `seon.call-preparation/snapshot` | Program rows/default definitions, projection, read attributes; per-projection LRU by revision then commit; returned basis added afterward. | Separate reusable derived members from `checked-through-t` of the current database. Central cache owns retention; revision alias and exact read evidence identify members. No ambient snapshot-read set asserted universally complete. H/R; weigh member graph and keys. | Unrelated commit / binding/default definition, schema, negative read; returned basis must still advance. |
| `seon.db/definition-digest-of`, `seon.program/definition-digest` | Canonical declaration, used resolver context and row shapes. Recomputed during writer/index operations. | Compute at the admitted producer, pass the result and held shapes, reuse stored digest only when all owned inputs agree. No new digest cache needed for caller-held values. C(changed declaration). | Unused namespace alias/doc excluded by chosen meaning / body, used alias, contract or canonical-shape rule. |
| `seon.db/namespace-digests-tx`, `seon.db/write-digest-error` | Changed namespace/declarations and their current resolver meaning. | Preserve producer digest and reuse held declarations within one transaction; installed namespace-digest change already addresses part. A stored digest does not cover arbitrary non-digest metadata. O(changed/reached declarations), no per-call persistent copy. | Unrelated namespace / used resolver binding or declaration digest input. |
| `seon.fn/analyzed-artifacts` / kondo result | Source, analyzer/library/config versions, classpath dependency analysis, macros/signatures and cross-file resolution. | Existing parser facts are only one component. Carry captured analysis through publication; add artifact caching only after all cross-file inputs resolve. C(changed files+reached dependencies). Result memory requires measurement; parser weight is not artifact weight. | Checkout/branch with equal resolution / macro, signature, classpath/config, alias, source or relevant schema. |
| `seon.cluster/full-source-refresh!`, rehearsal publication | Captured file contents, existing published artifacts, schema world and analyzer inputs. Recursive call may discard already-derived artifacts. | Pass already-captured files/artifacts into the subsequent derivation. Reuse library dependency analysis. This removes recomputation without inventing a second publication path. O(changed ∪ newly stale), memory one captured set per operation. | Same final file population reached in one or two rounds / added/deleted file or reached schema. |
| `seon.fn/reconcile-tx-in`, collection normalization | Before facts, desired canonical entities, owned attributes, held projection/shapes. | Pass held projection to storage conversion; derive each desired/stored shape once per comparison. Proposed tx can share; applying it remains writer work. O(touched entity data); no additional lasting cache. | Irrelevant facts / owned attr, vector-vs-set storage, retraction or relevant shape. |
| `seon.db/write-owned-values-error`, write attribute/component plans | Schema/component plan plus actual before/after owning values and tx provenance. | Hold schema plan once; traverse ownership at the writer on the report's bases. Same schema does not imply same ownership. Do not omit validation or infer roots solely from an unproved identity rule. O(touched owning values); schema plan bounded by declarations. | Unrelated entity / required ref, component ownership, final value, deletion or provenance. |
| Transaction-report validation / `malli.core/-instrument` | Compiled contract and actual immutable report with its predicates/options. | Reuse compiled Malli product, keep required validation. A cache of whole report answers costs a traversal/hash and can retain large reports. No such cache proposed without measured net gain and pure predicates. | Caller identity / invalid datom or changed predicate meaning. |
| Context units / `seon.context/current-state` | Agent/task, selected units, per-unit render inputs and actual DB read evidence, profile, printer meaning. Reassembled at requests. | Reuse render-unit data at content/read identity; assemble current attachments/order/basis fresh. Never cache a whole context by program alone. H/R per selected unit; weigh output refs, not repeated source/database copies. | Unrelated fact / task, message, answered basis, unit value, renderer/profile/printer. |
| Value printing / AI versus HTML emission | Value, face, print profile, live-result reference and printer implementation. | Request only required face; share face-independent node data where already held. Render output still passes through the one value printer. H only for held inputs; no digest of a huge value just to avoid printing. | Other consumer's unused face / requested face, budget, value or elision target. |
| Request default/config preparation | Same effective configuration and explicit evaluation bindings; callers repeatedly look up it. | Reuse `seon.config/effective` product; fill each function's defaults from that evaluation's actual bindings. O(missing declared bindings); no cache keyed by function alone. | Unrelated caller / supplied argument, checked-out connection, profile or agent. |
| Evaluation recording and call settlement | Evaluated forms/values, occurrence, agent, answered basis, profiling and effects. | Not a reusable truth. Keep synchronous recording through the one path. Share input derivations only. New evaluation must remain a new observation even when its pure subresults repeat. | No claim of same transaction across requests / any new actual occurrence. |

## Red and error results

Retain a completed red at exactly the same complete key at which a completed green is retained. Red reuse returns red and continues to block completion/merge. It does not search for an older green at a less specific key, reinterpret red as unknown, or skip a changed reached input. Unknown is the absence of current completed evidence, not a third reusable success.

The current `seon.test.runner/retain-greens!` admits only terminated positive-pass runs with zero failures/errors and retains a small run reference. The replacement must preserve the completed result's counts, assertion/failure data references, actual reached definitions and input evidence. A test with zero assertions, an interrupted execution, a recording failure or incomplete input evidence cannot acquire a green merely through cache lookup. Deterministic assertion failure or a deterministic returned error can be shared. A timeout, missing remote result, mutable external service or uncaptured clock is not proved the same experiment by unchanged test source.

The cache already retains non-nil error maps unless the caller rejects them. `seon.cache/settle!` does not retain thrown exceptions, nil or `retain?` false results; concurrent waiters can see the same in-flight failure. Keep infrastructure failures as occurrence outcomes at the entrant, rather than turning every exception into a permanent negative cache. Where a derivation's declared result is an immutable error value with complete inputs, retain that value just like success. Error occurrence counting, timestamps, delivery and actor ownership remain fresh and durable at their owners.

One test table for B1 should cover: sibling branch equal inputs and green; equal inputs and assertion red; deterministic returned error; changed reached callee; changed fixture/read; changed external input root; missing/negative read becoming present; task obligation change; evaluator implementation change; interrupted/no-recording case; and cache eviction followed by correct recomputation. Reuse must avoid executing the body but still supply honest current evidence to `seon.cluster.source/done-refusals`. Do not merge runtime test observations as program entities to achieve sharing.

## Measured costs, repeat rates and memory

All times here are my measurements from default through `seon.profile/measure`, except the explicitly dated heap calibration below. Allocation is calling-thread allocation, not retained heap. The shared JVM was active; profile call/read counters are JVM-wide and can include other work. These observations select experiments; they are not quiet parent/change performance proofs. No implementation gain is claimed.

Initial detailed snapshot, result `e4bf32c64c312`: 509,086,614 weighed bytes out of 512,000,000. Natural hit fractions are cumulative cache hit/(hit+miss), not a direct count of identical logical inputs: `present` deliberately counts neither, and eviction can cause repeated misses. A miss also need not mean a previously unseen value.

| Selected row | Hits / misses / evictions | Natural hit fraction | Cumulative derive ms | Weighed bytes |
|---|---:|---:|---:|---:|
| Database projection pair | 12 / 44 / 6 | 21.4% | 1,623 | 406,852,928 |
| Program contexts | 2 / 4 / 4 | 33.3% | 2,485 | 0 at this snapshot |
| Parsed facts | 127 / 582 / 538 | 17.9% | 2,143 | 16,970,020 |

These three balance current retention cost and miss cost. `source-resolver-names` is a close fourth in cumulative derivation (2,347 ms; 12,745 hits/10,529 misses, 1.46 MB retained). Declaration/schema projection construction is part of the first row's path, not an additional independent saving to sum with it. Older Gemini totals rank different operations on a different JVM; they do not establish today's repeat rate.

### 1. Database projection: duplicate retained products and key cost

Result `e990adbe65832`, database with 3,758 forms and 3,862 contracts:

| Operation | Repeats | Total ms | Per call ms | Allocated bytes/call |
|---|---:|---:|---:|---:|
| `seon.db/projection-cache-key` | 100 | 0.13 | 0.0013 | 1,026 |
| `seon.db/declaration-content-key` | 10 | 13.00 | 1.300 | 1,523,769 |
| `seon.db/carried-projection`, held database | 100 | 1.16 | 0.0116 | 11,360 |
| `seon.id/digest` of `[forms contracts]`, 64 chars | 5 | 56.37 | 11.274 | 5,581,584 |

Result `e26d047924ccd`: `seon.schema/load-projection` against the same database and reusable projection returned the identical object, but still took 7.64 ms and 8,933,008 allocated bytes. A fresh full-population digest on every read is therefore rejected: its 11.27 ms costs more than even this unchanged-input loader, and nearly a thousand times the held lookup.

Result `eae0284439c4e`: 37 retained entries collapsed to 12 equal groups of forms/contracts/admissions/exports/predicate bindings. That first test omitted source strings. Corrected result `e36d8dda18d00`, including definition strings: 36 entries, 9 groups; duplicate group sizes 5, 2, 16, 5, 2, 2, 2, all containing non-identical projections. The cache moved between probes. This proves duplicate exact input populations, not equality of every runtime member or 75% exclusive heap saving. The diagnostic itself took 74 ms and about 205 MB request allocation; never put this full-cache grouping on a production read path.

Result `ef03964d0a5a1`: constructing/hashing the seven-member input map over held component maps 100 times cost 0.08 ms and 132,824 bytes total. Twenty comparisons with an equal retained input map cost 27.61 ms and 72,644,504 bytes, about 1.381 ms / 3.63 MB each. Component hashes were already warm; this is not a cold population-hash benchmark. Exact structural canonicalization is plausible at a change, not every ordinary lookup. The implementation proof must include the cold and equal-nonidentical cases, and the end-to-end transaction/read path.

Today's database weigher charges 2,850 bytes per form plus the central 256-byte entry: 10,710,556 bytes for 3,758 forms. It does not explicitly count 3,862 contracts or its giant datom-vector key. That number is neither the exclusive size of an additional shared projection nor a proof of total retained heap.

### 2. SCI program context: expensive miss, already-cheap retained hit

Result `e3deb0d73b96d`: one `seon.sci.eval/program-context` call cost 237.46 ms / 400,751,064 allocated bytes; ten subsequent calls cost 1.24 ms / 1,509,224 bytes total (0.124 ms / 150.9 KB each). Cache counters went from 2 hits/4 misses to 12 hits/6 misses: the first call derived a base and an overlay, then ten requests reused them. Controlled repeat fraction was 10/11 requests; it is separate from the natural 33.3% cache hit fraction.

At `e36afd36268f5`, neither context remained retained. Result `ed28d9e90dc9d` rebuilt one at 251.14 ms / 336,100,904 allocated bytes. Ten warm calls then took 44.94 ms / 15,105,128 bytes total during concurrent activity; report this variability rather than selecting only the favorable first run. The request's whole-JVM allocation window exceeded 1 GB while its own thread allocation was about 391 MB, so unrelated activity was material.

The same probe measured full `seon.sci.eval/program-content-key` ten times: 10.79 ms / 2,445,528 bytes total, or 1.079 ms / 244.6 KB each. This is cheaper than a 251 ms miss, but substantially dearer than the earlier 0.124 ms warm call. Keep the revision alias so the full key is computed only when relevant inputs change.

Retained entries immediately afterward: base 15,125,740 weighed bytes for 6,872 functions (about 2,201 bytes/function including source, a ratio rather than an independent calibration); overlay 53,248 bytes, zero interpreted functions in this main-program sample. Both include the entry charge. This does not measure a changed agent branch's per-interpreted-row heap. Current coefficients are 1,280 bytes/host function plus source length, 3,712/interpreted function, 112/own namespace entry, and a 51,200-byte overlay base. The overlay lane's changed-row measurements remain a separate required proof.

### 3. Parser facts: right key, useful reuse, eviction pressure

Result `eefaf22027c64` used the loaded `seon/fn/analyzer.clj` resource, 50,837 characters. First lookup after eviction: 7.18 ms / 16,867,688 bytes. Twenty lookups of the same text under another filename: 0.10 ms / 100,536 bytes total, or 0.005 ms / 5.0 KB each. Direct `seon.fn.analyzer/parse-facts` three times: 20.58 ms / 50,562,912 bytes, or 6.86 ms / 16.85 MB each. Twenty fresh structural digests of `[text]`: 4.22 ms / 231,992 bytes, or 0.211 ms each.

The retained-weight increase was 313,150 bytes for this text. Cache counters advanced by 20 hits and 1 miss, a controlled repeat fraction of 95.2%. Today's natural hit fraction was much lower because the cache had 538 evictions for this kind. Adding a digest on every lookup would multiply the present hit cost by about 42 before any parsing was saved. Keep the text key; reduce duplicate heavyweight products first. The input filename belongs on the cheap result wrapper, as installed.

### Memory policy and its proof

Keep the configured 512 MB budget and Caffeine's admission/eviction. Do not reserve arbitrary percentages or increase memory to hide duplicate ownership. Share canonical inputs and immutable products; do not retain a whole database, SCI custody, or giant value merely to identify it.

`seon.cache` adds 256 bytes per entry and charges the value, not arbitrary key graphs. Therefore a small alias with a giant datom-vector key is not actually a 256-byte retention claim. Keys that keep content alive must be included in a measured owning weight or replaced with an already-held compact identity. An alias should hold a compact content identity; evicting the product must let its exclusive graph go. Canonicalizing duplicate products precedes trying to optimize the last few bytes of a key.

The existing `bench/seon/cache_kind_measurement.edn` is evidence with a date, not a fresh measurement: 2026-09-27 packaged population 1,336,008 bytes/3,545 forms; declaration projection 8,915,728 bytes/3,549 forms; marginal schema projection 10,096,784 bytes, with full-contract variants around 19.5–19.9 MB. The 2026-10-09 reach measurements explicitly distinguish graph union from repeated leaves. Dividing today's estimated cache bytes by entry count cannot validate a weigher against heap; the old cache-fill arithmetic does not establish a 20% accuracy bound.

Shared-structure weights need an eviction proof. An overlay can retain the base's graph after Caffeine evicts the base entry. Charging only the overlay delta then undercounts what remains reachable. Likewise projection-local mutable caches can grow after the immutable projection's insertion weight was set. Prefer conservatively inclusive weights until isolated graph measurements justify a smaller owning charge. That may overcharge shared nodes and evict sooner; it is honest, simple, and uses the existing cache. Do not add a reference-counting ownership service merely to perfect accounting.

For each top-row implementation: measure the object graph union before/after adding an equal product, a one-row-changed product, and after eviction of each parent/alias while children remain. Use the existing context-weight measurement tooling in the throwaway process, not attach/GC operations against default. Recalibrate both value and key retention, include bounded mutable growth or move it into centrally weighed products, and measure misses on the same workload at the fixed budget. Evict by Caffeine's policy after correct identity and honest weights, not by manual invalidation of valid old content.

## Phases, ownership and red proofs

Phase status: **all proposals below unimplemented by this lane**. Each implementation starts by making its named proof red against its parent, then uses a quiet parent/change `seon.profile/measure` on the same end-to-end probe. Existing green tests are retained; a proposed test name below is explicitly new until written. Cost assertions include key construction and lookup, not just the body saved. Memory acceptance is measured graph retention and repeated request cost, not line counts or cache-hit counts alone.

Correctness dependencies precede speed: overlay ambient-input correction and B1 input evidence stay with their owners. The performance order below is based on present memory pressure and observed miss cost; lower rows with no present benchmark stay investigations, not speculative implementation assignments.

| Phase; one derivation | Owner / dependency | Red proof and cost proof | Deletes or replaces |
|---|---|---|---|
| 1. Canonical compiled schema projection | Database caches from `fbf611da5` remain; **proj-content-key** appeared as an active claim during this session. Root schedules after/with that owner; this lane touches none of its files. | New table in schema/db tests: two transaction histories and reusable bases yielding the same complete inputs retain one compiled product; change each input section and compare to a fresh build. Include source strings, admissions, predicate reload and fingerprint collision. `seon.profile/measure` repeated unrelated transaction + `carried-projection` on parent/change; key/lookup cheaper than avoided load; unchanged lookup stays near its existing 0.012 ms scale. Measure union memory for equal and one-row-different products. | Duplicate canonical products under history-based and advanced keys; supplied-base bypass of canonical product reuse. Keep cheap basis aliases. |
| 2. Incremental SCI overlay derivation | **overlay-impl**, after its ambient connection proof. Follow `overlay-hybrid.md`, not a competing implementation. | Existing planned `seon.sci.acquisition-cost-test/a-change-reinterprets-only-its-dependent-declarations`, behavior table with raw-thread callback/defonce/refusal; compare predecessor/cold derivation. Request ms and allocation on parent/change, not install-call count alone. Add parent-eviction retention measurement to the context-weight proof. | Whole-plan reinterpretation of unchanged rows. No removal of actual host/private inputs from base identity. |
| 3. Completed test result reuse, including red | **b1-test-reuse** owns runner/test schemas and branch proof. Coordinate only through root after its slice; no edits or messages by this lane. | New rows in its branch-reuse table for equal-input red/error and every hidden input listed above. Zero body execution on a retained completed result, red remains red at done. Measure second branch's complete prove request on same fixture; read/key cost less than rerunning. Record result-reference bytes and bounded failure evidence. | Green-only retention and branch-restricted lookup where branch is not observed; never removes current-positive-proof semantics. |
| 4. Parser facts retention, already installed | **perf-analyzer** owns parse/analysis files. No new parser-key change requested. | Existing parse-once proof plus equal text/different filename and central-cache pressure workload. Measure complete repeated analysis after phase 1: installed 5 µs lookup versus ~6.86 ms parse sample. Only change further if repeated analysis still parses unchanged inputs. | Remaining second parse only where confirmed by owner; fresh per-call digest proposal is rejected. |
| 5. Source resolver token derivation | After perf-analyzer releases its dependencies; program owner supplies schemas. | New table: one source with two candidate sets, same parsed token product and correct different intersections; redefinition of used alias changes definition digest, unused alias does not. Measure actual namespace entity derivation and per-key allocation on parent/change. | Repeated structural SHA and reparsing due only to candidate-set changes. Drop phase if installed parser facts already answer it cheaply. |
| 6. File derivation key | After perf-analyzer; root assigns cluster owner. | New table: unrelated schema leaves file derivation identity and artifact unchanged; reached schema, macro/signature/config/path input changes it. First resolve cited cross-file input gaps. Measure one ordinary publication, including all key work. | Whole-world key for a file's already-selected dependencies. No second analyzer/publication path. |
| 7. Render invocation result | Render owner; only after complete input/read proof. | New table: equal values on sibling branches share output; private callable, selected face, agent argument and negative read changes recompute. Deliberate 32-bit collision compares correctly without replacing another content's product. Measure complete render request versus current evidence-check cost. | Branch-only partition and hash-only candidate slot, retaining actual observed inputs. |
| 8. Effective config | Config owner, only if new measurement shows payoff. | Existing effective-config test gains unrelated write versus changed/default/new dial and schema rows. Measure trivial request end to end; reject a key that scans more than the current derivation costs. | Commit-only canonical product; keep cheap held basis alias where useful. |
| 9. Rehearsal continuation | Publication owner, after file derivation work. | New test: source+schema change producing a second stale set preserves first-set artifacts and boot/reload matches a full derivation. Measure complete rehearsal on parent/change, with captured inputs equal. | Recursive loss of already-captured/derived work. |

Other catalogue gaps (error namespace classification, reach coverage identity, projection fingerprint identity, health conditional reads and marginal weighing) need narrow producer regressions before a phase is assigned. They are source-audit findings, not all reproduced live defects. They must not become blanket guards, fallback caches or revision invalidation. The implementation task should carry the concrete counterexample before root commits to a design.

## Reproducible forms and verification

These are bounded JVM probes; pass `agent=root`, `cluster=default`, `mode=jvm`. They intentionally return measurements and small summaries, not full projections. Exact result handles above identify the recorded runs; the useful forms and returned scalar data live here rather than only in ignored scratch files.

```clojure
;; Measurement helper is local, not a platform definition.
(let [run (fn [n f]
            (assoc (select-keys
                     (seon.profile/measure #(dotimes [_ n] (f)))
                     [:seon.profile/ms :seon.profile/alloc-bytes
                      :seon.profile/calls :seon.profile/reads]) :n n))
      database (seon.db/db (seon.cluster.boot/connection "default"))
      p (seon.db/carried-projection database)]
  {:revision (run 100 #(#'seon.db/projection-cache-key database))
   :datoms (run 10 #(#'seon.db/declaration-content-key database))
   :held (run 100 #(seon.db/carried-projection database))
   :full-digest
   (run 5 #(seon.id/digest
             64 [(:seon.schema.projection/forms p)
                 (:seon.schema.projection/function-contracts p)]))})

(let [fields [:seon.schema.projection/forms
              :seon.schema.projection/function-contracts
              :seon.schema.projection/schema-admissions
              :seon.schema.projection/function-admissions
              :seon.schema.projection/artifact-exports
              :seon.schema.projection/predicate-functions
              :seon.schema.projection/definition-strings]
      entries (vec (mapcat (fn [kind]
                            (map (fn [[key p]] [kind key p])
                                 (seon.cache/retained kind)))
                          [:seon.db/projection :seon.db/value-projection]))
      groups (vals (group-by #(select-keys (nth % 2) fields) entries))]
  {:entries (count entries) :full-input-groups (count groups)
   :duplicate-groups
   (mapv (fn [xs]
           {:n (count xs)
            :identical (every? #(identical? (nth (first xs) 2) (nth % 2)) xs)})
         (filter #(> (count %) 1) groups))})

;; Use the same local run helper for these thunks; inputs are captured once.
;; Parser: text is the archived, loaded resource, not the dirty checkout.
(let [text (slurp (clojure.java.io/resource "seon/fn/analyzer.clj"))]
  (select-keys
    (seon.profile/measure
      #(dotimes [_ 20]
         (#'seon.fn.analyzer/parsed-facts "another-name.clj" text)))
    [:seon.profile/ms :seon.profile/alloc-bytes]))

(let [database (seon.db/db (seon.cluster.boot/connection "default"))
      p (seon.db/carried-projection database)
      loaded (#'seon.sci.eval/loaded-program database)
      plan (#'seon.sci.eval/program-changes database loaded p)]
  {:key (select-keys
          (seon.profile/measure
            #(dotimes [_ 10]
               (#'seon.sci.eval/program-content-key database loaded plan)))
          [:seon.profile/ms :seon.profile/alloc-bytes])
   :context (select-keys
              (seon.profile/measure #(seon.sci.eval/program-context database))
              [:seon.profile/ms :seon.profile/alloc-bytes])})
```

Author probe errors are not product regressions: two unmatched-delimiter requests (`e7d903af028c7`, `e07845b259bed`) and an incorrect digest argument (`e34fe3103f870`) were corrected. An initial structural-key measurement selected unqualified profile keys and returned no metrics (`e691a578b92a3`); only the corrected namespaced-key result is used. The erroneous digest request exposed a very large rendered source argument; this note does not claim clean error presentation. No probe result is a substitute for the implementation red proofs.

Pre-commit lint: `clj-kondo --lint src script test --cache false`, 8,846 ms, **0 errors**, 1,680 warnings. This scanned the shared working tree; it did not repair or include foreign edits. This note changes no source/test declarations, so no behavioral tests reach it. The requested throwaway archive check will name the cache mechanism's existing concurrency, byte-budget and presence-probe tests as smoke evidence, not as proof of the proposed phases.

Foreign boundary: active claims included B1 (`seon.test`, runner, schemas/tests), overlay-impl (`seon.sci.eval` and its tests), perf-analyzer, proj-content-key, s1c-1 and s3d-2. Their uncommitted source/schema/test changes are excluded from this lane's candidate. The candidate is HEAD plus this note; no broad suite or foreign-red triage is undertaken here. Final commit/check outcome is reported with the lane's commit rather than asserted in advance.
