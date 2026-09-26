---
type: design-review
date: 2026-09-27
status: complete
verdict: REVISE
reviewed-commit: 2ef8385b0304e135bef2adf04c499700e2e0eb2b
scope: design and source review; sequential read-only JVM observations; no implementation or tests
---

# Review of derive-once, jointly with contracts-out-of-projection

**REVISE.** Content-based reuse, one bounded cache, and computing request-local inputs once are the right direction. The proposed keys do not yet identify the computations they cache. In particular, definition digests exclude graph facts; the base-context key repeats omissions established in the earlier review; an SCC digest does not identify a particular producer; and the render key omits both the face and several execution inputs. These permit false hits, not just excess recomputation. The graph's incremental algorithm and estimated cost are not established by the function-only scratch measurement.

Prefer corrected **option A first**, together with contracts-out-of-projection's removal of contracts from the projection. Do not make that removal depend on a new whole-program Merkle graph. B can follow if a complete graph derived through the existing owners demonstrates a worthwhile improvement over retained, demand-driven reach. C is not justified by the evidence. No production change is approved by this review.

## Evidence boundary

Read end to end: `CLAUDE.md`; derive-once; contracts-out-of-projection; cache-central; eval-path-audit and its review; review-eval-shrink-plan; all six September 26 shrink plans; `src/seon/cache.clj`; and the web-research note, including Clerk §3. The Clojure, REPL, and Datahike skills informed the boundary checks. Source citations below are pinned to **2ef8385b0**, unless a dependency is named. Other lanes advanced HEAD and edited source during review; their changes were neither evaluated nor changed here.

Dependency pins: Datahike `67b00071e020a7ccf310985d01b28128cdadec34`, Clerk `4c2599946256592858ca2d65e8d453f9f77489ba`, Malli `43c6dca8d2d8df3162b13725939954807be4a4ed`. Source establishes behavior of those bytes, not publication/adoption of a proposal.

`bin/seon status` reported default alive at PID **12212**, start **2026-09-26T22:05:18.940Z**, serving archive **d1f6a37fb0f56b35a0d6c5a6e7cb7453e815d9ad**. MCP `runtime_status` reported no missing readiness layers, 2 error signatures, 5 errored receipts, 30 failed tests, 9 unknown failed-test records, and 2 unowned namespaces. The suite was active; these are existing observations, not findings caused by this review. Central-cache status showed six kinds, approximately **37.22 MB estimated weighted bytes** under **512 MB**, not the design's historical five kinds.

Four sequential JVM-mode probes used explicit root `/Users/sean/src/seon`, cluster `default`, session `review-derive-once`, `read_only=true`, timeout 10,000 ms. Their host evaluation times were **5, 35, 11, and 4 ms**. They read profile cells, small metadata, declaration counts, existing reach, and one pulled row; no SCI evaluation, branch creation, transaction, test, reload, restart, new cache kind, heap walk, or graph benchmark ran. Ordinary read paths may populate their existing caches. Exact forms and observations are in the appendix.

## 1. Inventory and costs

The inventory is useful but is not a verified inventory of 23 independent repeated values. V23 is proposed, V22 is explicitly outside scope, V20 mixes derived page products with transport state, and V1/V2/V13–V16 overlap. The total column contains inclusive, cumulative costs from several observation times; it cannot be summed into savings. The largest allocation and retained-heap numbers come from older landings, not the design's current page probe.

The following checks cover the first five inventory entries and the largest additional cost claims. Live profile triples are **calls / inclusive total ms / max ms**, sampled while the existing suite ran. They are not isolated miss benchmarks or hit rates.

| Value | Source check at the reviewed commit | Independent live observation and conclusion |
|---|---|---|
| V1 projection | `schema.clj:3054–3067` still includes function specs in projection ranges; `:332–355` installs a fresh holder; `db.clj:1398–1417` implements the content/value tiers. | `load-projection` **158 / 5,517 / 112**; `content-projection` **680 / 9,079 / 144**; `carried-projection` **64,259 / 10,379 / 147**. Repeated projection work exists. The design's per-defn allocation/call counts were not reproduced. |
| V2 projection tables | `program.cljc:257–277` now retains shapes per projection; `schema/datahike.clj:77–146,260–266` contains the compilation/storage walks. This is not uniformly uncached per caller. | Later sample: `entity-maps` **2,036,052 / 1,665 / 2**; `compiled-storage` **1,143,557 / 1,350 / 13**; `storable-attribute-in?` **425,185 / 1,646 / 16**; `shapes-in` **643 / 205 / 15**. Call volume is confirmed; **166k per defn** needs a controlled before/after delta. |
| V3 declaration-content-key | `db.clj:1328–1360` walks declaration ranges and retains datoms plus transaction ids as a key. | **1,083 / 4,449 / 67**, mean about **4.11 ms** in this sample. The historical 11.7 ms is plausible evidence from its own run, not a current measured constant. |
| V4 arity base | `db.clj:3797–3869` still has the whole-program `or` query, datom content key, and two LRUs. | `arity-base` **64 / 5,594 / 1,222**; content-key **124 / 377 / 59**. Source and profile support priority. No cold miss or 92–122 MB heap measurement was repeated. |
| V5 stored reach | `db.clj:1450–1479` walks indexed names with a visited set. Its caller now has a cache at `render.clj:829–853`. | Held database: **794 symbols**, **6.466 / 6.512 / 6.707 ms** over three calls, versus the design's older 795 and 7.5–8.2 ms. Repeated owner traversal is confirmed; “unmemoized render miss” is an incomplete description of HEAD. |
| V8/V9 SCI acquisition | `sci/eval.clj:1645–1676,2782–2924,3376–3418`: loaded materialization, full digest reads, installation, and branch-scoped key inputs remain. | `derive-base-ctx` **73 / 7,925 / 265**; `base-ctx` **82 / 8,293 / 279**; `loaded-program` **83 / 423 / 53**; function digests **146 / 1,389 / 150**. These ratios do **not** measure cache hit rate. Also 18,863/71 in the design is about **266 ms**, not the earlier 235 ms mean carried into that row. |
| V10 test reach | `test/runner.clj:723–772,861–953` performs depth-dependent reads, full content reads, and holder/lineage coordination. | `reach-digests` **108 / 9,586 / 949**; `reach-facts` **26 / 7,153 / 621**. No test or fresh 20-test reach build ran. The 98-query/1,284 ms figure remains historical. |
| V18/V19 render work | `render.clj:1536–1539` already reuses fast evidence; `:1597–1598` calls `read-evidence` without result retention. `db.clj:948–955` only computes stable results/digests when requested. | `render-call` **8 / 71 / 23**, `render.value/prepare` **463 / 970 / 44**. The canonical-string cell had **24,607 / 158 / 3**, one throw; no read-result-digest cell appeared in this sample, which is not proof it can never run. The 2.3M calls/6.8 GB page cost is not a current baseline after **978bac337**. |

One held database yielded **5,389 function**, **2,305 test**, **3,548 schema**, and **472 namespace** digest rows, taking **5.108 / 3.184 / 2.937 / 0.645 ms** respectively. These narrow joins are not the complete, refusal-aware program identity operation. They omit identities lacking a digest, whereas `program/digest-map` explicitly refuses such evidence (`program.cljc:481–596`). Do not price replacing that owner using the narrow joins alone.

**Required correction:** revise the inventory to distinguish current source, installed source, historical measurements, predictions, cache hits, and miss derivations. Retain the original probe revision and instrumentation mode. Commit reproducible measurement code with implementation evidence; the uncommitted SCC scratch code cannot support a reproducible 150 ms result or the incremental guarantee. Count central-cache misses directly. Do not add inclusive totals, equate all calls with recomputation, or use a microbenchmark hit to price complete key construction.

## 2. Alias → content is a valid separation, but aliases need a proof

The correction to the older shrink plans is substantially right. `query.cljc:2582–2592` forwards a committed id into `db/advance-cache-context`; `db.cljc:423–442` advances changed attributes and the conservative revision on unsafe/schema changes. `versioning.cljc:69–100` carries exact revisions only for the same materialized commit; another commit receives a conservative identity without the old attribute map. Speculation creates fresh revision tokens (`db.cljc:444–463`). None of these is a digest of a declaration population.

However, “every rung is store-global” and the bare selected-revision alias in derive-once `:125–151` are not established dependency guarantees. The dependency's comparison explicitly requires connection/generation and conservative revision (`query.cljc:3010–3029`); shared exact-value results instead use **store identity plus commit**, dropping branch/generation (`:2684–2697`). Commit construction also includes transaction/entity maxima and metadata, not just current program rows (`writing.cljc:376–395`).

The design must supply these rules before deleting the current machinery:

- A semantic value key contains complete content, never connection, branch, wall clock, or object identity merely to distinguish where it was derived. A **name-to-content alias** is a separate concern. Prefer the dependency's existing revision-aware read reuse, or an exact **[store, commit, view]** alias before content reuse. Do not claim arbitrary revision vectors are portable across lineages without proving that stronger property.
- Always account for the conservative revision, absent revisions, and unavailable cache context. “Else conservative commit” is insufficient when a partial revision map exists alongside a changed conservative revision. Empty evidence is not evidence of empty/equal content.
- Preserve temporal semantics. An as-of view and its origin's current database can name the same origin commit but different declarations (`db.clj:1419–1428,1545–1566`). A semantic as-of point is an input, unlike wall-clock invalidation. Either read/digest the correct view directly or use a complete view alias. Detached, filtered, and speculative values need an explicit route; never fall through to a common nil/empty alias.
- Derive the digest and value from the **same captured inputs**. Alias resolution must not read one head and compilation another. Aliases, retained predecessors, keys, and values all consume the one cache budget; no writer-maintained stamp or side atom is required.

Two steps do not repair an incomplete content key. An edge-only edit can miss the graph alias and then hit the **old graph under the same program digest**. That is the central counterexample in the next sections.

## 3. Key audit: all inventory kinds

Here “population content” means the effective constructor inputs, including the relevant compiler/loaded-code identity, not an object, fingerprint, or only the schema row digest set. Dependencies should be narrowed per product where practical; a complete broader key is preferable to a false hit.

| Kind(s) | Assessment and required correction |
|---|---|
| V1 forms/base projection | **Incomplete.** Forms and predicate closures omit schema admission, pure-predicate declarations, exports, compile/validation options, and builder identity. The constructor reads these at `schema.clj:2129–2198,2261–2290,2328–2339`; admission is excluded from definition digests. The forms alias also needs predicate/loaded-builder changes, not only schema-family revisions. Cover effective predicate bindings, including explicit overrides and non-row host predicates. |
| V1′ compiled contract | **Incomplete.** `[forms-digest, contract-form-digest]` omits admission policy and compile mode (ordinary versus composite var registry), predicate bindings named only by the contract, and compiler identity. A returned wrapper containing the symbol/refusal must name the symbol too, or attach that caller-specific data outside a shared compiled value. `instrument.clj:592–608` demonstrates the second registry mode; `schema/internal.cljc:329–347` reads admission. |
| V2, V13–V16 | **Not a mechanical key swap.** Some tables genuinely depend on forms, but all 37 sites cannot become `[forms-digest, k]` unchanged. `call_preparation.clj:159–169` caches all contract arities; `schema.clj:3683–3691` caches a contract by symbol; `test.clj:406` and call-preparation's snapshot holder retain further memos. Classify/convert every site. V15 currently reads contracts and loaded namespace availability (`schema.clj:2808–2840`); moving resolution is a separate semantic cut. V16 reads installed DB schema, which must not silently become the authored Malli population. |
| V3 | Deletion is appropriate **after** the complete population input owner handles provenance and temporal views. The datom-copy key currently covers more than forms alone (`db.clj:1328–1360`). |
| V4 | Removing the whole-program base is promising. It needs the indexed caller route, old/new ownership, and supplied-default/schema impacts described below. It does not need V23. |
| V5–V7 | A producer closure is useful only after its edges and external leaves are complete. Preserve the actual loaded builder's identity, mixed/failed adoption behavior, and foreign-store handling (`db.clj:1494–1543`). The branch's `load-projection` row is not necessarily the code executing compilation. Do not delete `loaded-builder` merely because a graph returns a hash for that name. |
| V8 base SCI context | **Incomplete; earlier review still applies.** Use loaded host identity plus complete acquisition content: declaration identities and relevant metadata, namespace bindings, tests, calls and declared-invocation inputs, projection/contract compilation inputs, arming config, supplied-default facts, and recorder semantics. `sci/eval.clj:1333–1371,2808–2924` and `call_preparation.clj:111–119` read them. A sorted list of override digests also needs names and explicit deletions; deleting a loaded row cannot collapse to “no overrides.” Prefer a complete content value over an extra diff encoding. |
| V9 loaded program | **Not safe as the current return value keyed by bare commit.** `loaded-program` returns the caller's database or attaches its projection (`sci/eval.clj:1645–1655`); attached materialization carries connection context (`versioning.cljc:69–100`). Cache detached/store-owned immutable materialization and digest content by **[store, loaded commit]**; attach receiving projection/custody afterward. Account for resource retention. |
| V10 test reach | **Incomplete and semantically different from selection.** Current reach fingerprints include references, subjects, keyword/schema dependencies, and missing-leaf evidence (`test/runner.clj:690–713,723–772,826–860`). Selection uses the fn owner's call/invocation/subject rules. One edge interpretation cannot silently replace both. A complete graph may expose multiple relations/views while preserving these owners' semantics. |
| V11 changed definitions | Reuse `program/digest-map` and its classified identity families, explicit refusal, and symmetric additions/removals. `keys(diff d1 d2)` is pseudocode, not an installed interface; define the comparison. Its definition-change answer is distinct from graph-analysis-only changes. |
| V12 program provenance/revisions | **Conflated.** `runner/program-digest` deliberately identifies the tested commit (`test/runner.clj:1039–1070`); member reuse is a different digest. A graph-content key can optimize derivation without changing durable tested provenance or writer conflict checking into graph equality. |
| V17 effective config | The content key needs both the selected row's effective content and the declared config population/derivation. `config.clj:870–917` selects/validates using that population. The alias must identify which row is selected. Fold once per request first; a new cache may be unnecessary. |
| V18 render face | **Incomplete.** Include producer identity, face/output mode, effective profile content, selected argument, projection/contract/default inputs used, and every relevant viewing-world input. Existing evidence includes namespace, caps, source-output intent, and private callable (`render.clj:864–899`). Profile id alone is valid only if immutable and content-derived. A shared SCC digest is not producer identity. Inline/private callables and identity-bearing objects cannot enter a global cache solely by a stored program hash. |
| V19 read evidence | Dropping retained results was already implemented at render sites. Keep dependency evidence, including source/view and conservative/unknown state; a static “reaches a DB read” bit is not proof of no other reads or effects. Revisions can conservatively request a rerender but are not content digests and do not guarantee equal-content cross-branch face sharing. |
| V20 page state | Delete duplicate derivations after V18's replacement is complete. Preserve stream/tab last-sent state at its proc owner. A transport observation and a recomputable render are different values. |
| V21 history entry | `[evaluation-id, profile]` is not a demonstrated complete key. History assembly reads nested namespace/run data, errors/diagnostics, and fallback basis (`transcript.clj:254–295,798–844`). Compaction/retraction and different branch observations matter. Cache the immutable receipt/emission content actually consumed; preserve exact saved shown text. Read basis and terminal settlement basis are not interchangeable facts. |
| V22 analyzer | Out of scope as stated. Do not silently substitute V23 for analyzer identity: analyzer version/config, row-shape derivation, external dependency content, and file bytes still need their existing authority. |
| V23 graph | **Incomplete.** Key graph-relevant row/edge/evidence content, not only definition digests. Use classified program identities, including namespaces where consumed. Include the graph derivation's own loaded-code identity. Details below. |

There is also an unresolved lifecycle issue in V8. The current base retains a recorder callable; replacing it with an invented “recorder id” is not a demonstrated content identity. Either inject recording and other receiving-world services after sharing the pure base, or retain an explicit owner-backed distinction until that separation lands. Do not share stateful initializers/private roots because their source hashes agree. The earlier review's old file-derived branch, late host namespace availability, failed adoption, and per-world arming regressions remain requirements (`review-eval-shrink-plan-2026-09-26.md`, “Actual acquisition inputs” through “Sharing a base”).

## 4. Graph correctness and existing owners

### Definition identity is not graph identity

`program.cljc:373–387` explicitly excludes calls, references, keywords, writes, call arities, derived arity components, host-bound status, analysis evidence, and admission source from definition digests. The live pure-row probe returned **equal digests** after changing calls, host-bound status, and admission source. This was not a transaction or a claim that the altered analysis is admissible; it demonstrates what the digest function distinguishes.

Consequently V23's alias may notice `:seon.fn/calls`, but its content lookup and digest-map diff do not. A repaired/retracted call edge with unchanged authored source can reuse the old graph or skip updating that row. The same defect applies to future contract-fact repairs. Namespaces are missing from the displayed graph identity/alias inventory despite being acquisition and render-evidence inputs. The live probe found 472 namespace digest rows.

**Required fix:** retain existing declaration identities and graph facts as separate inputs. Reuse `program/digest-map`'s family classification and missing-evidence behavior rather than a new function/test/schema-only query. Extend the existing fn edge owner to expose complete immutable edge data if needed; do not copy its rules into `program-graph`.

### One graph does not mean one undifferentiated edge relation

Existing authorities already separate meanings:

- `fn/call-target` includes macro usage and passed function values (`fn.clj:314–320`).
- Declared invocation targets can come from data attributes, refs, or symbols (`fn.clj:1714–1733,1777–1786`). A target change need not change a function definition digest.
- `fn/reverse-closure` uses call and declared-invocation edges; test selection is a distinct bounded call/subject rule (`fn.clj:1882–1955`). Replacing selection with transitive reverse reach changes that policy.
- Test reuse currently also considers named references and schema-key dependencies (`test/runner.clj:690–713,826–860`). Do not drop them merely because selection excludes quoted symbols.
- Contract references already have a Malli-based producer: `schema/compiled-references` uses Malli's walker and respects local registries (`schema.clj:64–79`); `program/arity-row` stores input/output/guard refs (`program.cljc:1033–1071`). Schema dependencies already live in the projection (`schema.clj:2160–2224`). Reuse these instead of another keyword/form walker.

The proposed graph is acceptable only as a retained derivation of these owners, with explicit relation semantics and matching consumer conversions. Keeping `fn`'s selection/closure, runner's reach interpretation, and a third independent graph interpretation would duplicate authority rather than remove it.

### Retractions, cycles, and external leaves

The three incremental steps at derive-once `:184–188` do not specify a correct update algorithm:

1. Include added, changed, **removed**, and evidence-only changed nodes/edges. Discover affected owners from both database values, including component-only contract changes and swept refs. Preserve removed names for reverse selection. A missing declared digest/analysis fact is an unknown, not an empty leaf.
2. Remove old forward and reverse edges before installing replacements. Use the union of relevant **old and new reverse reach**. Looking only at the new graph loses consumers affected by a removed edge or target. An arbitrary retained predecessor requires a complete content diff, not a since-window that assumes ancestry.
3. Adding an edge can merge SCCs; removing an edge can split one. Reusing the previous SCC ordering and rehashing reverse-reached rows alone does not prove either case. Start with a full deterministic SCC recomputation on a graph change unless a measured need justifies a dependency-backed incremental algorithm. State its O(V+E) cost honestly.
4. The proposed SCC hash is shared by all members: the design itself reports 5,348 distinct closures for 5,385 functions. Two mutually recursive renderers can produce different output for equal inputs yet share that hash. Every per-function cache must include the function identity; alternatively derive a member digest from `[identity, SCC-digest]`. Include graph-relevant internal topology and labeled outgoing targets, not only a bag of reachable hashes whose leaves omit edge facts.
5. Do not discard targets outside the function-row set. Distinguish a declared missing/deleted target from a known external implementation. Track macro expansion dependencies, loaded host implementations/classpath content, and declared dynamic targets. Unsupported host overrides still refuse by name (`sci/eval.clj:1691–1737`). Unknown dynamic/effect dependencies disable reuse or produce typed unknown evidence; they do not establish purity.

Clerk supports Merkle composition, not this completeness proof. Its block hash includes form and **defined Vars** (`clerk/analyzer.clj:625–645`), graph hashes are topological (`:652–661`), external dependencies use jar/git/source identities (`:543–565`), and redefinitions/no-cache inheritance are handled explicitly (`:252–265,496–505`). It even exposes missing dependency hashes to a callback (`:628–637`). Copying only the hash expression does not inherit these guarantees or provide incremental SCC maintenance.

### Bootstrap and single-flight recursion

Reusing owners reveals a dependency cycle that the design must resolve before slice 2: `program/digest-map` obtains `carried-projection` (`program.cljc:497–507`), while the proposed projection lookup obtains the graph for its builder/predicate closures. Full contract/schema edges themselves require projection/compile facts. `seon.cache/lookup` explicitly waits forever on a recursive lookup of its own pending key (`cache.clj:134–137`). Content-addressing does not make this dependency graph acyclic.

Declare an acyclic input acquisition order using the existing raw declaration/read owners and supplied construction projection at genesis. Pass captured projection/input facts into the owning derivations where required; do not solve the cycle with a second registry, special sentinel graph, or writer-stamped metadata. A new graph owner must not make every cold read acquire the graph recursively.

## 5. Costs, memory, rendering, and the smaller writer algorithm

**Graph cost.** Even the displayed algorithm costs O(program rows) for its digest query/diff before reverse-reach work. Hashing a changed node costs its incident dependency data, not a universal 7 µs. SCC repair, namespace/test/schema/contract edges, declared data-driven targets, external identities, alias construction, and compiler identity are absent from the 8 ms + 0.2–15 ms estimate. “Cold once per JVM” is incorrect for a bounded cache: after eviction or a different complete key it derives again. Median reverse reach in the old function-only graph is a measurement, not a complexity guarantee for the combined graph.

**Memory.** The 7 MB number is an estimate, not retained-heap evidence. Functions + tests + schemas total **11,234 nodes** in the original sample, so digests **and** closures require about **22,468 entries**, before namespaces, SCC maps, forward/reverse sets, keys, aliases, and transient construction. The design's 11.2k-entry arithmetic appears to cover the function-only measurement rather than the proposed full graph. Structural sharing can lower marginal retained size, but cannot prove a fixed discount. The existing cache has one global weighted budget, with no “2–4 graphs” guarantee (`cache.clj:54–75,194–214`). Weigh the key and retained value, including referenced compiled population/registry state, using calibrated counts; measure allocation and marginal retained heap separately. Do not use `cache/retained` to search every cache entry on an ordinary lookup (`cache.clj:177–192`).

**Render reuse.** The missing face/producer/input fields in V18 must be fixed before deleting retained-call evidence. The actual set of DB reads can depend on the input, selected renderer, supplied defaults, and viewing SCI world. A “reaches db/q” graph bit neither discovers those dependencies nor covers indirect/custom reads, non-DB effects, or mutable closed-over values. Define how the first computation records its actual dependency set, how a lookup selects evidence for the current sources/views, and how unknown/stale evidence prevents a false hit. If evidence changes, a new complete key or direct recomputation is required; mutating the value under an incomplete supposedly immutable content key is not derive-once.

Caching generated AI render forms is also different from caching their evaluated results and recorded transcript. The same form can resolve differently in another viewing world. Preserve read-only evaluation, recording, per-viewer bindings, and exact historical `:seon.eval/shown`; do not globally share an evaluation side effect or rewrite a prior observation. The eval-path review's totality and receiving-world requirements still apply.

The **0.2 ms for 170 lookups** prices only an armed cache hit, not selection, argument normalization, content equality/hash, input reads, dependency validation, assembly, or serialization. Equal non-identical large Clojure values can require structural equality. Attribute revisions also invalidate all readers of that attribute when an unrelated entity changes; they do not prove that only 2–5 page units rerender. Keep ≤25 ms as a target, split the actual request phases, and measure parent/change against the same page and program. A warm request can have misses after legitimate eviction or concurrent work; a JVM-wide counter panel should report evidence rather than assert that zero misses is guaranteed. Status collection/HTML size is part of its cost.

**Arity writer.** Replacing the global base with report-local checks can precede the graph. The concrete route is AVET on indexed **`:seon.fn/calls`**, then EAVT call-arity tuples for those callers, filtering the requested callee. A tuple's first member is not an independently indexed attribute; `:seon.fn/call-arities` is `[symbol count]` and explicitly names calls as its reach index (`resources/seon/schemas/seon.fn.edn:76–90`). Cover test callers, removed/renamed symbols, and arity component ownership in before and after, as the current report owner does (`db.clj:3911–3922`).

It must also cover schema and supplied-default changes without a direct call/arity edit. Current writer admission triggers for those families (`db.clj:4459–4466`), and prepared bounds depend on suppliable slots (`:3969–4069`). The old base retains all candidate mismatches, including calls made admissible by defaults; dropping it must retain the ability to reconsider them when a default disappears. Preserve or explicitly revise report coverage counts. Price this as incident callers/tuples plus affected preparation dependencies, not the graph's **transitive** median reverse reach of 26.

## 6. Composition with contracts-out-of-projection

The two designs agree that a contracted defn must not rebuild unchanged data-shape tables. They conflict on the mechanism:

- Derive-once `:229–237` retains a `:contracts` map inside the projection and a combined forms/contracts identity.
- Contracts-out §1 and §4 remove that map and the function registry/dependency half entirely, but propose the current per-projection holder, or a process key containing the registry object.

Choose the **contracts-out ownership model** and the **central-cache discipline**. The compile seam is Malli's existing `m/function-schema` (`malli/core.cljc:3130–3134`) through `compilable-form` and the current completeness/admission owner. Keep the base projection independent of ordinary function contracts. Compile a contract against explicit population/predicate/options inputs; keep it in `seon.cache` under their complete content identity. Do not introduce the intermediate assoc-contract projection or a contracts-set digest. An ordinary map assoc does not incrementally maintain a canonical sorted set hash, in any event.

The holder/object-key justification in contracts-out is not an exception to `CLAUDE.md`'s central-cache law. Conversely, derive-once's narrower forms key does not solve the completeness objection. Explicit predicates supplied as opaque closures are not automatically equal because their symbols match. Separate caller/world-bound execution from shareable compilation, or do not retain that case until its effective identity is known. A stable Var sees a later root, but this alone does not establish unchanged admission, contract facts, predicate world, or wrapper behavior.

Fix a migration trap in contracts-out §2 #4: once the projection stops changing on contract edits, `[::contract-arities sym]` becomes stale unless it includes the current contract's complete compile key. The same applies to `function-arities-in`, output alternatives, supplier snapshots, render matching, and any negative “no contract” cache. Switching the source of a read without switching its memo identity is not sufficient.

The live query found **zero stored functions with a spec but no arity component**. This resolves only the narrow current-population count, not completeness/currentness of all reference facts. Before using indexed input/output/guard refs as the sole reverse contract index, prove agreement with compiled Malli references and current authored/analysis evidence, including direct predicates, local registries, removed refs, zero-reference contracts, publication, and same-transaction repairs. Empty cardinality-many is not positive analysis evidence.

Schema-change admission must remain at the **final writer boundary**, not just `row-tx` and SCI admission. `validate-write-report` derives the candidate population from final `:db-after` (`db.clj:4481–4503`). Removing contracts from that build removes its old contract validation effect. Add the replacement affected-contract check there before the cut, preserving all entrances, before/after refs, and a transaction that changes a schema and repairs/deletes its dependent contracts together. Keep early entry checks for feedback; they cannot replace writer authority. Publication/boot callers with supplied rows need the same owner and explicit candidate inputs.

This also revises eval-path D5: the target is one contract compile/admission owner against the appropriate world, not one contract-bearing projection advance. The eval-path redesign is not entirely orthogonal: it shares admission/arming/settlement seams. Coordinate these conversions with its reviewed ordered-world requirements; do not remove candidate-world checks, rollback, or value-preserving diagnostics as a cache optimization.

## 7. Recommended joint slice order and regressions

Every implementation cut must load on its own, convert all callers/schemas it affects, and retain the old behavior until the replacement owns it. A namespace or API existing in a document is not an installed seam. These are **future regression requirements**, not tests run by this review.

| Order | Loadable cut | Required behavioral evidence |
|---|---|---|
| 0 | Revise the complete input/alias matrix, graph semantics, cache dependency order, and reproducible premise probes. Reconcile the two designs in their owning sections. | Equal content across stores/branches where semantically shareable; conservative/partial/absent revisions; temporal/speculative values; edge-only changes; missing digest/analysis. Record complete key cost, not only lookup time. |
| 1 | Independent small costs: shrink-db P1 split query, P4 folds, shrink-turn P1 deferred speculative application, request profile/config fold. Credit already-landed render P1/P2; do not redo them. | Same arity verdicts for function/test callers; before/after owned-child validation; the next form sees preceding declarations; the final form avoids unused `with`; supplied/absent profiles keep their verdict. No graph prerequisite. |
| 2 | Contracts-out S1: additive contract/form read seam, with a **complete central-cache compile key** and explicit compile mode. Keep current projection consumers. | Compiled arity/reference/validation parity with current registry; agent/core admission differences; unresolved/deleted predicates; ordinary/composite registry modes; equal fresh population reuse; failed compilation not cached as success. |
| 3 | Contracts-out S2: convert every direct and indirect reader, **including its cache identity**, to the supplied/current form. Keep the old projection contract fields until no consumer uses them. | A contract absent from the projection works; changing/removing it while forms remain equal updates arming, call preparation, render compatibility, and errors. Candidate and receiving worlds retain their own custody, faults, and effective defaults. |
| 4 | Contracts-out S3: shared affected-contract check and reverse-ref query at final-report writer plus entry/publication callers. Establish current contract-fact evidence first. | Schema change/removal refuses with the dependent function named; same-transaction repair/deletion succeeds; component-only/ref removal and direct DB writes cannot bypass validation; missing/stale analysis is unknown. |
| 5 | Contracts-out S4: remove contract ranges/map/registry/dependency indices. Convert only genuinely form-derived tables to complete population keys in the central cache; remove their obsolete holders/tier consumers in the same cut. | A contracted defn preserves the data-shape projection and table derivations, while changed contract readers update. Admission/predicate/options/builder changes miss correctly. Incremental forms projection equals full construction, including temporal and genesis paths. No half-converted contract table survives. |
| 6 | Corrected shrink-eval F1/F2: store-owned loaded materialization and digest reuse. | Same store/commit hits; different stores and caller projections do not cross; releasing a branch does not pin its connection; unavailable/failed adoption is retryable and explicit. |
| 7 | Corrected acquisition key, all identity/listener/refusal-recording consumers, and cache migration together. Resolve the earlier review's branch semantics, not only its memo. | Equal complete content shares derivation across receipt-only changes; config/default/analysis/ns/test/deletion changes alter behavior; old file-derived branch runs its own supported code; host override refuses; private atoms/results do not leak; late host load and concurrent adoption remain correct. Remove registered callbacks through their lifecycle, not just source definitions. |
| 8 | Optional report-local arity replacement, independent of V23, after its smaller query/index probe. | Caller/callee additions, removals, test edges, component-only bounds, supplied-default and schema changes, same-transaction repairs; verdict/coverage parity with the surviving writer contract; cost proportional to affected tuples/dependencies. |
| 9 | **Conditional B:** derive a complete immutable graph through existing program/fn/schema owners; initially full SCC recomputation on graph change. Convert one consumer, then delete that consumer's old derivation. | Cold versus incremental equality under edge-only repairs, deletion/re-addition, SCC merge/split, self/mutual recursion, contract/predicate/schema/test edges, declared dynamic targets, missing external evidence; different members of one SCC never collide as producers. Prove acyclic cache loading and retention after eviction. |
| 10 | Convert test reach/selection only after graph semantics are proven; retire holder/BFS paths in the same cut. Preserve tested commit provenance and current positive proof. | Current selection policy and reach-digest behavior, removed targets, contract-only/schema-only changes, unknown/red evidence, equal-content branches, changed test inputs, and writer conflict. Do not change selection to all transitive reverse reach by accident. |
| 11 | Complete render-face cache and all agent/MCP/page consumers; only then delete retained-call threading, page derivation holders, and invalidation. | Same producer/input with different face/profile/viewing namespace/defaults differs correctly; transitive code/DB read changes miss; unrelated content remains reusable where evidence proves it; private/inline/effectful renderers stay correct; nil/false/diagnostics survive; AI forms are evaluated/recorded in the right world and historical text is unchanged. |
| 12 | Page assembly and optional counter panel; retain transport state in its proc. | Parent/change cold/warm/post-turn timings split into walk, selection/key/evidence, render, assembly/serialize; memory after repeated views and eviction; browser paint/SSE behavior separately. No test that demands zero misses despite permitted eviction/concurrency. |

Steps 2–5 do not require steps 9–12. Steps 6–7 depend on complete acquisition inputs, not necessarily Merkle closures. Mechanical dead-code slices can remain independent when file ownership permits. Scope and line estimates must account for every consumer conversion; the design currently offers clock rows, not adequate per-slice behavioral regressions. Root retains platform/bulk verification ownership after implementation.

## 8. Options and required decisions

Exactly the three offered options, repriced as design estimates rather than established schedules:

| Option | Guarantee after the required fixes | Cost estimate and sacrifice | Recommendation |
|---|---|---|---|
| **A: complete keys and existing owners** | Correct content reuse; unchanged shape tables survive ordinary contract edits; receiving-world inputs remain explicit. Dependency-aware reads and local folds avoid much repeated work without a new global graph. | The design's **~2 days** is a lower-bound planning estimate until complete keys/consumer conversion are sized. Broader program keys may recompute retained reach/render derivations after unrelated program changes. | **Recommended first**, joined with contracts-out S1–S4 and independent local cost fixes. Measure that remaining cost before choosing B. |
| **B: A plus shared graph/closures** | Finer reuse is possible once complete labeled edges, external leaves, SCC/member identity, and deletion/update semantics are proved. It cannot promise only changed page units under coarse attribute revisions. | **~5 days** and **+220 lines** are not substantiated for the missing semantics. Price the full SCC/graph/input acquisition and migration after premise proof; retain one global budget and measured weights. | Conditional follow-up. Reuse/extend the existing edge and digest owners; do not add an independent authority. |
| **C: persisted derivations** | Can reuse some surviving pure artifacts after restart if complete identities and durable formats agree. It cannot guarantee a warm first page, preserved live results, or no new-content misses. | At least the stated **B + ~2 days**, plus writer/publication/format/GC proof. Adds persistent derived state and serialized writer work to save an unverified restart cost. | Do not choose now. No measured need justifies this state or its stronger guarantee. |

“B is the only option where cost follows changed inputs” is too strong. The smaller composition is to remove contracts from the projection, fold request-local inputs, retain complete products at their existing owners, and use demand-driven indexed reach. It improves the observed common path without requiring all graph closures at every first read. A global graph may still win for many consumers, but that choice requires measurement of the complete competing algorithms.

Required fixes before acceptance: **(1)** complete keys and safe aliases; **(2)** acquisition input/lifecycle fixes from the earlier review; **(3)** one owner for each edge meaning and graph identity covering analysis changes; **(4)** deletion/SCC/external-dependency semantics; **(5)** an acyclic cache/bootstrap path; **(6)** the contracts-out/central-cache reconciliation with final-writer checks; **(7)** concrete slice regressions and corrected current costs/memory. The existing skill descriptions of base-context “program identity” omit the connection-scoped limitation and have stale context line references, as already recorded in the earlier review. Correct them with that implementation; this assignment does not authorize editing those files.

## Appendix: exact read-only evidence

All forms used the MCP arguments recorded above. Complete returned envelopes were inspected. The two profile responses marked their rendered results retrievable; all figures used here appeared in the returned values. Saved response digests were `84fbecaa6f20fa839cd8e8e269f6cb64ebbe877611da28d4b2197fac70bf6aed` and `83962c09ae4d86540e34be73e20c79da44c667b24a2b4fc8e51e88dc2ca0c61c`. These runtime handles supplement the durable forms/numbers below, not replace them.

Probe 1, **5 ms**, cumulative profiles and installed Var locations:

```clojure
(let [wanted '#{seon.schema/load-projection seon.db/content-projection
                seon.db/carried-projection seon.db/arity-base seon.db/arity-content-key
                seon.sci.eval/base-ctx seon.sci.eval/derive-base-ctx
                seon.sci.eval/acquire-program! seon.sci.eval/loaded-program
                seon.sci.eval/function-digests seon.test.runner/reach-digests
                seon.test.runner/reach-facts seon.db/stored-call-reach
                seon.db/read-result-digest seon.schema/canonical-value-string
                seon.render/render-call seon.render.value/prepare}]
  {:profiles (mapv seon.profile/observation
                   (filter #(contains? wanted (:seon.profile/sym %))
                           (seon.profile/cells)))
   :vars (into {} (map (fn [s]
                        [s (when-let [v (find-var s)]
                             (select-keys (meta v) [:file :line]))]) wanted))})
```

The installed locations matched the cited principal seams: `load-projection` 3069, `arity-base` 3838, `stored-call-reach` 1463, `derive-base-ctx` 3376, `base-ctx` 3439, `reach-facts` 723, and `reach-digests` 955. Acquire-program profile was **73 / 7,784 / 263**. All cells quoted from this probe had zero throws except canonical-value-string's one. Locations/profile digests do not establish that a proposed API is installed.

Probe 2, **35 ms**, held-basis read timings:

```clojure
(let [database @(seon.cluster.boot/connection "default")]
  {:basis (seon.db/basis-t database)
   :reach
   (mapv (fn [_]
           (let [start (System/nanoTime)
                 value (seon.db/stored-call-reach
                        database 'seon.render.transcript/render-ai)]
             {:count (count value) :ms (/ (- (System/nanoTime) start) 1e6)}))
         (range 3))
   :digests
   (mapv (fn [attribute]
           (let [start (System/nanoTime)
                 rows (seon.db/q
                       '[:find ?name ?digest :in $ ?attribute
                         :where [?e ?attribute ?name]
                                [?e :seon.program/definition-digest ?digest]]
                       database attribute)]
             {:attribute attribute :count (when (coll? rows) (count rows))
              :refusal? (seon.db/read-refusal? rows)
              :ms (/ (- (System/nanoTime) start) 1e6)}))
         [:seon.fn/sym :seon.test/sym :seon.schema/key :seon.ns/name])
   :cache-context
   (select-keys (:cache-context database)
                [:datahike.cache/commit-id :datahike.cache/conservative-revision
                 :datahike.cache/committed?])})
```

Basis **536871484**, commit **6ab842da-3f80-5a0c-b613-97b1139bb6e2**, committed true; no conservative-revision key in the selected current context. All four queries returned non-refusals. Counts/times are in §1. This says nothing about cross-lineage revision portability.

Probe 3, **11 ms**, pure digest counterexample and current contract-component coverage:

```clojure
(let [database @(seon.cluster.boot/connection "default")
      row (seon.db/pull database
                       [:seon.fn/sym :seon.fn/source :seon.fn/spec :seon.fn/calls
                        :seon.fn/host-bound? :seon.schema.admission/source]
                       [:seon.fn/sym 'seon.cache/lookup])
      changed (assoc row :seon.fn/calls #{'review.missing/target}
                         :seon.fn/host-bound? (not (:seon.fn/host-bound? row))
                         :seon.schema.admission/source :agent)
      missing (seon.db/q '[:find [?sym ...]
                          :where [?e :seon.fn/sym ?sym] [?e :seon.fn/spec]
                                 (not [?e :seon.fn/arities])] database)]
  {:read-refused? (seon.db/read-refusal? row)
   :equal-definition-digests?
   (= (seon.program/definition-digest row) (seon.program/definition-digest changed))
   :changed-members [:seon.fn/calls :seon.fn/host-bound? :seon.schema.admission/source]
   :contracts-without-arity-components
   (if (seon.db/read-refusal? missing) missing
       {:count (count missing) :sample (vec (take 8 (sort missing)))})})
```

Result: `:read-refused? false`, `:equal-definition-digests? true`, missing-component count **0**, sample `[]`. No transformed value was stored, and no contract reference freshness claim follows from that count.

Probe 4, **4 ms**, later cumulative table profiles:

```clojure
(let [wanted '#{seon.db/declaration-content-key seon.db/value-projection
                seon.program/shapes-in seon.schema.internal/entity-maps
                seon.schema.datahike/storable-attribute-in?
                seon.schema.datahike/compiled-storage
                seon.error/observation-selector seon.config/effective}]
  (mapv #(select-keys (seon.profile/observation %)
                     [:seon.profile/sym :seon.profile/calls :seon.profile/total-ms
                      :seon.profile/max-ms :seon.profile/throws])
        (filter #(contains? wanted (:seon.profile/sym %)) (seon.profile/cells))))
```

All eight cells had zero throws. Additional triples: `value-projection` **16,574 / 12,477 / 252**, `observation-selector` **160 / 1,156 / 66**, `config/effective` **1,041 / 1,212 / 37**. Do not combine this later sample with probe 1 as one synchronous profile or infer per-defn/page counts.

Only this review file is authored. Production/test line delta: **0**. No runtime memory or proposed-algorithm measurement is claimed; 7 MB, incremental closure costs, page targets, and implementation line/day estimates remain unverified. No foreign lane file/session was modified, no worktree or temporary JVM was created, and no approval/hook was bypassed.
