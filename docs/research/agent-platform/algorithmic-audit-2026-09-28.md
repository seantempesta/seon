# Algorithmic audit of the REPL path — 2026-09-28

Astra, outside source/diff review. **The largest remaining opportunities are to stop reconstructing read evidence by repeating queries, stop making a turn write validate an agent's entire history, and check render currency before rendering.** These are changes to the amount of work, not faster implementations of the same work.

## Evidence and limits

Source basis: **`90828bb935b97c5bd0b1bafc16472880017dbcea`**. All `src/...:line` references below refer to that committed archive, not another lane's moving working tree. Read-only inspection covered the requested owners and their first-party callers. Publication lives in `seon.cluster` and `seon.cluster.source`; there is no `src/seon/cluster/publication.clj` at this basis.

I inspected `git log --since='7 days ago' -- src` (1,126 commits at review time), the scoped implementation, and the relevant recent changes discussed below. This is not a claim to have independently reviewed every hunk of all 1,126 commits. No REPL, live JVM operation, provider call, test request, or test body was run. Only the explicitly requested offline lint and contracts compilation were run. Source evidence establishes execution paths and work counts; it does **not** establish their installation or current wall-clock contribution.

Owner-supplied context, not new measurements: prelude 0.5–0.7 s against 50 ms; deleted `:seon.turn/changes` 7.4 s; reach hashing 16.7 s → 1.0 s; `error/for-agent` 501 ms; debug page 18.7 s; fresh boot 150–200 s; one test request 2–5 s around a 147 ms body. Do not add the estimates below together: several findings describe nested costs in the same operation.

Dependency source inspected at the archive's pins:

- Datahike `591c825d6a47869486a56723532d6665f51ba636`: `query.cljc:891` (`resolve-ins`), `:3132` (memoized parsing), `:3204` (`collect`), `:4650` (query/cache execution); `pull_api.cljc:558,579` (pull result, compiled plan and dependency plan together).
- SCI `ead49957c1a6932ef29e5695af1cc9e2a890eed1`: `core.cljc:334` (`fork`) shares immutable environment data behind a new atom; `:740` (`namespace-state`) returns the held namespace map. Neither requires walking all program bindings.
- Malli `43c6dca8d2d8df3162b13725939954807be4a4ed`: `core.cljc:2672` (`validator`) caches the validator on a compiled schema; `:3130` (`function-schema`) is the function-contract constructor. Cached compilation does not cache validation of a value.

## Ranked findings

Ranking combines potential multiplicative reduction, frequency, and confidence in the source path. It is a proposed measurement/implementation order, not a measured profile ranking. Let `P` be program declarations/bindings, `H` retained turns/evaluations as specified per finding, `D` stored datoms, `Δ` changed inputs, and `U` rendered units. Index seeks below cost approximately `log D` plus returned datoms.

### 1. Read evidence repeats the query's work once per input binding

**Owner:** `seon.db/query-index-patterns`, `src/seon/db.clj:674`; `resolve-bindings` at `:701`; pull groups at `:769`; `pull-index-patterns` at `:789`; callers `append-query-evidence!` at `:840` and `append-pull-evidence!` at `:884`.

**Current algorithm.** `query-index-patterns` expands `:in` into `input-bindings`, then maps the entire evidence derivation over those bindings. For a pull find expression, each iteration calls `resolve-bindings` on the whole `:where`. That helper removes the original `:in` variables from `extra` and passes the **original collection-valued `:args`** to `d/q`; it does not substitute this iteration's one input member. It then walks pull dependencies for all returned entities. `distinct` removes duplicate patterns only after their production.

For example, `[:find (pull ?e [:seon.cluster.eval/id]) :in $ [?e ...] :where [?e :seon.cluster.eval/id]]` with `I` input entities and `R=I` matches requests the same full binding result `I` times. With `A` selected/nested pattern work per entity, the Seon bookkeeping is **Θ(I × R × A)**, quadratic when `R=I`, plus query-call overhead. Datahike may cache the repeated query result; that does not eliminate Seon's `mapv`, pull-index seeks and pattern construction. More complex negation/disjunction also resolves positive scopes repeatedly; legitimate dependency coverage must be retained.

The 128-pattern cap in `append-read-evidence!` (`:552`) runs **after** these complete traversals. It limits stored evidence, not acquisition cost. Separately, `pull-call` (`:2880`, call at `:2925`) invokes `append-pull-evidence!` even without a sink; that function builds patterns before the sink check inside `append-read-evidence!` discards them.

**Change.** First guard pull evidence acquisition before any traversal when there is no sink. Split query-static structure and the full query's pull-root bindings from per-input bound patterns: derive each distinct `[source, selector, root entity]` once, outside the input loop, and pass those held roots down. Fold patterns into the existing bounded representation as they are discovered; once the existing replay representation is required, stop discovering patterns and retain the already-computed answer's digest. Do not cap the answer or claim an incomplete pattern set is complete. Preserve negative dependencies and selector/input differences; the simple direct-bound-pull case is a small first cut.

**Target.** Ordinary positive bound pulls: `O(I + R × A)` work, not `O(I × R × A)`; no-sink evidence work `O(1)`. The initial query still pays its actual join/output cost. Datahike's evidence response already carries result/plan, but the inspected API does not expose all negative-join witness bindings; do not pretend that seam exists. A dependency change to capture those during execution would be a later, separately justified cut.

**Expected gain.** At 100 bound entities, roughly 100 traversals of the same pulled population become one; at 1,000, roughly 1,000 become one. This is a work-count estimate for the evidence portion, not a 100–1,000× end-to-end promise. A first-party trigger is `fn/runtime-analysis-batch` (`src/seon/fn.clj:943`, query at `:957`): a collection of namespaces, a pull find, and a `:where` join. The owner can measure one versus many namespaces. Conversely, `turn/latest-evaluations`' second query (`turn.clj:2144`) has no `:where`; its empty-variable branch returns the individual binding and does **not** establish this quadratic case. It also removes all discarded pattern walks on uncaptured pulls. Highest-confidence large multiplier in this review.

**Proof requested:** scale the explicit example through 1/10/100/1,000 entities under captured and uncaptured reads; count binding queries and pull-pattern entity visits. Answers and currency must agree after relevant writes, irrelevant writes, entity deletion and negative-join changes. Existing semantics: `seon.db-test/collection-bound-query-evidence-covers-every-input-attribute`, `query-and-pull-append-evidence-only-when-captured`, `component-expanded-pull-evidence-detects-a-child-only-change`.

### 2. One turn write expands and validates the complete agent-owned history

**Owner:** `seon.db/write-owned-values-error`, `src/seon/db.clj:3994`, ancestor/root selection at `:4135`, full descendant expansion at `:4142`, per-expanded-entity validation at `:4226`; `write-entity-error` at `:3871`. Model: `resources/seon/schemas/seon.agent.edn:33`, `seon.runtime.edn:3`, `seon.turn.edn:140`.

**Current algorithm.** The agent owns its runtime as a component; the runtime owns every turn; a turn owns its provider attempts. For a change to an existing turn or attempt, the writer discovers ancestors, selects the agent root, then expands **all** descendants of that root. It validates the complete root and the expanded owned entities. The per-report row/owner cache removes duplicate reads within the walk, but does not exclude unchanged sibling turns. Thus settling a small new turn has cost at least `Ω(H)` in retained turns/attempts, plus owner discovery (up to `O(H × C × log D)` for `C` populated component attributes). Across `H` successive turns this creates quadratic cumulative work. Nested full-value validation adds depth-weighted work, worst-case quadratic for a chain.

This is not primarily a broken validator: it implements the stated complete-owning-value rule. The ownership boundary makes the whole lifetime history the value being written. Evaluation entities refer to runs separately; do not conflate their count with the component turn tree.

**Change.** Make a turn an independently identified retained entity with a declared agent/runtime peer reference; derive the runtime's history from that relation. Keep bounded, genuinely owned data (a turn's attempts, an evaluation's evidence) as components. Migrate ownership and explicitly preserve required-reference, compaction and retention/deletion behavior. The writer can then validate the complete affected turn and its children without traversing sibling turns. Do not exempt history from validation or simply mark a partial parent valid.

**Target.** `O(changed turn's owned data + ancestor depth)` entities per ordinary settlement, with existing indexed owner checks; no factor for prior turns. A deliberate compaction/deletion of the whole history remains `O(H)`. A less invasive cache would need a sound subtree content identity including child changes and schema/predicate identity; hashing the whole subtree on each write merely moves the same cost.

**Expected gain.** With 1,000 similar retained turns, history expansion changes from roughly 1,000 sibling turns per settlement to one. This can affect every turn opening, attempt and closing write, not just presentation. It is a larger model change than #1 and needs migration proof, but has a durable asymptotic payoff. Measure the writer on the same one-turn mutation with histories of 1/100/1,000 before ranking its milliseconds.

**Proof requested:** `seon.owned-value-test/owned-values-validate-child-only-writes-and-before-owners`, `incomplete-unowned-cyclic-and-shared-components-refuse`, `complete-values-include-the-1001st-child`; plus turn compaction/retention regressions for the changed relationship. Check that per-settlement visited entities stop growing with old history.

### 3. System-turn planning renders the opening before deciding whether anything changed

**Owner:** `seon.turn/system-turn`, `src/seon/turn.clj:2346`; `declared-sources`, `:2029`; `unit-read`, `:1996`; `seon.render.walk/neighborhood`, `src/seon/render/walk.clj:698`; `render-call`, `src/seon/render.clj:1511`.

**Current algorithm.** `system-turn` first calls `declared-sources`, which acquires the neighborhood and renders each unit. Only afterward does it obtain latest evaluations, build `system-plan`, and select `:none`/`:changed` units. `declared-sources` allocates fresh captured calls/invocations but supplies neither retained calls nor retained invocations; `neighborhood` forwards that request directly to `render-call`. Existing render-cache reuse therefore has nothing to hit on this path. A fresh request does not acquire retained entries merely because it carries a SCI context.

Every no-change prelude costs `O(acquisition + Σ render-cost(unit))`, including database work such as `error/for-agent`, before selecting zero work. Data units then use `unit-read` to pull the entity/attribute again to obtain the form face although acquisition already held the value. The measured 501 ms fault read is a useful probe, not proof it remains the current bottleneck.

**Change.** Reuse the existing retained invocation/evidence mechanism for the declared opening, with complete acquisition-membership evidence so new or removed units are discovered. Check program/input/read currency before invoking producers; pass the acquired value to the form-face call. Keep the current pending-execution guard and source/form identity semantics. This should be a caller conversion, not another render cache or a second discovery system.

**Target.** No-change prelude: `O(U + retained evidence checked)` rather than `O(all producers' reads and rendering)`; changed prelude additionally pays only changed acquisition/producer costs. Broad evidence that falls back to replay is a remaining limit, addressed by #1 and precise evidence—not an excuse to skip checks.

**Expected gain.** For an unchanged opening, remove up to 100% of producer executions on this path; total savings depend on evidence-check cost. A fault concern that reads 1,000 stored occurrences would cease rereading them simply to discover unchanged output. This directly targets the 0.5–0.7 s prelude and preview/debug calls that invoke system-turn.

**Related smaller cut:** `sci.kernel/public-functions-in` (`sci/kernel.clj:251`) scans all `P` function rows to find one viewer namespace. `render/viewer` (`render.clj:669`) repeats that per root/value; `value/prepare` (`render/value.clj:1275`) already carries it to nested nodes, so **do not report a per-node whole-program scan**. Carry viewer candidates across the reply's units, or index the held snapshot by namespace: `O(U × P)` becomes one `O(P)` build per changed snapshot plus `O(U + selected namespace size)`. Do not add a registry beside the snapshot.

**Proof requested:** `seon.render.retained-test/equal-committed-database-skips-read-replay`, `adoption-of-an-unrelated-namespace-re-renders-zero-evaluations`, `seon.render.root-pull-test/supplied-root-acquisition-is-the-only-membership-read`; add a product `system-turn` no-change test asserting zero producer invocations after the opening has settled, and one changed concern asserting only its producer runs.

### 4. Requested-test reach still builds all vertices' closures, repeatedly

**Owner:** `seon.test.runner/reach-entries`, `src/seon/test/runner.clj:1004`; `reach-closures`, `:914`; `reach-entry`, `:963`; `commit-results!`, `:1852`; `record-latest-tx`, `:1577`. First-party calls also occur in `src/seon/test.clj:1399,1562,2301,2398`.

**Current algorithm.** `reach-graph` correctly acquires only the graph reachable from requested tests. But `reach-entries` then computes a bitset transitive closure for **every** reachable function vertex and schema vertex, even for one requested test. Let `V,E` describe the function graph and `K,E_k` the schema graph. Bitset propagation is approximately `O(p(E+V)ceil(V/64) + p_k(E_k+K)ceil(K/64))`, with fixed-point pass counts `p,p_k`; dense closure storage is `Θ(V²/64 + K²/64)` words, plus per-name schema bitsets. Even an acyclic graph executes a confirmation pass after the growing pass. A single root's reach needs a traversal, not all-pairs reachability.

The leaf SHA cache does not cache this work: all graph/closure/membership/fingerprint work precedes the lookup in `reach-entry`. `reach-digests` and `reach-memberships` both call `reach-entries`. `commit-results!` calls both on the same tested value. `record-latest-tx` derives memberships again whenever `tested` is available, even when carried memberships exist. `reach-declarations` (`:826`) is keyed by **commit**, so ordinary non-program writes also re-enumerate declaration attributes. Cached nodes help, but do not remove these outer traversals.

**Change.** First return both digests and memberships from one held reach-entry result and carry it through completion/recording; only missing or untrusted evidence should derive again. For one requested test, use the graph traversal already performed by `reach-graph`: its nodes/schema nodes are precisely that root's reach. Preserve the existing sorted leaf digest format. For a small batch, root-specific DFS is the simple `O(T(V+E+K+E_k))` alternative with `O(V+K)` working memory per root; choose it before materializing all vertices' closures.

If large batches still dominate, condense strongly connected components once and reuse the DAG. SCC construction is `O(V+E)`; a DAG/Merkle digest can be near-linear in graph input plus sorting, but is a **digest-format/semantics change**, requiring old evidence to miss. Do not call explicit memberships for thousands of roots linear: output size `Σ|reach(test)|` is an unavoidable lower bound. Cache declaration indexes by the revisions/content of attributes actually read, including definitions, schema forms and identities, not every data commit.

**Expected gain.** Two identical reach constructions at completion become one immediately; in the writer fallback case there can be a third. One-test requests eliminate thousands of unused per-vertex closures. Source costs at `:1004` report 951 ms for all 2,378 tests even with digests cached, whereas `:936` reports the already-landed hashing improvement: 1.0 s/1.24 GB unarmed and about 0.63 s additional armed validation for all digests. These are prior measurements, not fresh ones. Preserve the leaf-hashing win; do not propose it again or attribute the entire 2–5 s request overhead to this finding.

**Proof requested:** run existing `seon.test.selection-test/definition-content-agrees-across-exploratory-branches`, `named-selection-reuses-green-members-by-reachable-content`, and `selection-derives-bases-obligations-and-exact-symbol-reach` in that namespace centrally. Add chain, diamond, cycle, missing declaration, unrelated data commit, and one-test-versus-bulk comparisons of exact memberships and digest equality; count `reach-entries` calls per completion.

### 5. A retained agent context still walks all bindings on each acquisition

**Owner:** `seon.sci.eval/fork-for-turn`, `src/seon/sci/eval.clj:3376`; `regenerate-agent-context!`, `:3230`; `base-bindings`, `:3199`; caller `seon.cluster.agent/acquire-context!`, `src/seon/cluster/agent.clj:1160`.

**Current algorithm.** Whenever an `agent-ctx` exists, `fork-for-turn` unconditionally regenerates it. Regeneration snapshots every base binding, traverses the entering namespace state to identify private Vars, forks the base, and traverses namespace state again to carry non-Var entries. `acquire-once!` shares concurrent demand only; a later acquisition invokes the callback again. Thus an unchanged base still costs `Θ(P + private bindings)` per acquisition; over `Q` evaluations/turn acquisitions this is `Θ(QP)`. The `acquire!` program-identity fast path does not bypass this later work.

**Change.** Retain the identity of the base program actually incorporated into the agent context and return that context unchanged when the base's program, required resolver/config state and loaded host identity still agree. Advance the evaluation world separately as it already does. On change, use the existing regeneration first; a later cut can diff changed namespace maps and carry only affected bindings. Preserve private Vars, imports, aliases, `require`d namespaces, unavailable-execution recovery and host adoption semantics—commit equality alone is insufficient.

**Target.** Unchanged context `O(1)` identity checks plus explicitly changed world/config work, independent of `P`; actual replacement initially remains `O(P + private bindings)`. SCI's pinned `fork` is cheap; Seon's surrounding reconstruction is the cost being removed.

**Expected gain.** Thousands of binding inspections/metadata copies become zero per unchanged acquisition. Likely relevant to both turn prelude and a short test's acquisition overhead. No milliseconds or total-request multiplier established here. This proposal concerns continuing contexts, **not** reviving the shared-loaded-base experiment `e522324c2` reverted by `a8773c8fb`.

**Proof requested:** `seon.sci.eval-test/database-provenance-regenerates-the-base-and-reverts-for-retained-forks`, `a-context-definition-survives-its-symbols-first-install`, `a-namespace-an-agent-requires-keeps-its-vars-at-the-next-turn`, `an-unavailable-execution-starts-its-next-run-from-the-branch-head`; add unchanged acquisition with a binding-visit counter and changed-base/private-closure correctness.

### 6. Latest-read selection repeatedly sorts and parses the whole transcript

**Owner:** `seon.turn/latest-evaluations`, `src/seon/turn.clj:2098`; `source-key`, `:1989`; `read-only-evaluation?`, `:2158`; `system-plan`, `:2214`.

**Current algorithm.** Every system turn pulls all `H` evaluation rows (evidence only as refs, a useful landed improvement), sorts them, decodes shown values, and parses each source **twice** to produce the same key for lookup and association. Planning parses sources again for deduplication, filtering and mapping. `read-only-evaluation?` parses the retained source and performs receipt/effect existence queries; a retained source can pass through that predicate twice. With `S` total source/shown text read, planning costs `O(H log H + S)` each time, plus kept-read evidence and per-source queries. Over an append-only session this creates roughly quadratic cumulative transcript processing.

**Change.** First compute each source key once and carry the read classification through the plan fold. Reuse parsed identity by `[namespace, exact source, reader/resolution identity]` in `seon.cache`; do not key by text alone if namespace resolution can change. Keep an incremental latest-by-source fold keyed by agent/session history content and prior basis, applying newly settled evaluations in order. Rebuild on compaction, retraction, changed old rows or uncertain lineage. Batch receipt/effect membership reads for the kept evaluation IDs. Historical shown bytes remain immutable, and change/diff reconstruction still folds in order.

**Target.** Cold reconstruction `O(H log H + S)`; ordinary append `O(ΔH log ΔH + new source bytes + distinct retained reads checked)`. A cache hit must not build its key by pulling/parsing the entire history. The first small cut halves source parsing in `latest-evaluations` without new infrastructure.

**Expected gain.** The source's old example is 146 evaluations collapsed to 23 retained reads (`:2098`). For that shape, selection can stop rereading 123 superseded evaluations every turn; the immediate key change cuts 292 parses to 146 before incrementalization. Benefits grow with session age. The already-landed 3.8 s → 0.5 s evidence-decoding claim in its docstring is historical, not a remaining promised saving.

**Proof requested:** `seon.turn-test/generated-read-evidence-rejects-turn-activity`, `a-forms-read-evidence-is-only-what-the-form-read`, and `generated-system-runs-grow-only-after-their-settled-prefix`; add identical reads across namespaces, legacy shown diffs, compaction/retraction, and append-only scaling.

### 7. Per-file publication reuse still derives every file's reuse key

**Owner:** `seon.cluster/full-source-refresh!`, `src/seon/cluster.clj:2096`; `stored-derivation-schema-keys`, `:2033`; `derivation-keys`, `:2050`; call at `:2202`. Supporting projection path: `seon.schema/projection-with-declarations`, `src/seon/schema.clj:2610`; `declaration-changes`, `:2437`; `projection-registry`, `:590`.

**Current algorithm.** Even on a one-file partial refresh, the code reads stored keyword/spec facts across the program, EDN-parses all stored specs, constructs schema dependencies per file, and computes derivation keys for **all** `derivation-inputs`. Each file traverses its schema closure. For `F` files, `B` total contract bytes and schema graph `S,E_s`, this is `O(P + B + Σ_f closure(f))`, worst `O(P+B+F(S+E_s))`, before concluding most files are reusable. Hashing schema forms once per call helps, but the whole call happens again next publication.

Projection recompilation is narrowed to affected declarations, but its callers still compare whole before/after declaration maps; `projection-registry` scans all forms/contracts to find those not retained and seals a complete table. A one-declaration change can therefore still incur `O(P)` surrounding work despite recompiling one declaration. Distinguish compilation saved from discovery/copying still performed.

**Change.** Cache file→schema dependencies by each file's admitted content/analysis identity and maintain the inverse schema→files relation. Compare changed schema definitions and walk their existing reverse dependency index to select impacted files; changed analyzer/kondo inputs may legitimately select all files. Derive keys only for changed files and their actual dependency consumers. Pass the already-known declaration delta into the projection replacement path rather than rediscovering it from full populations; use persistent maps of retained compiled objects. No second analyzer or publication authority.

**Target.** Stable toolchain/schema, one-file edit: `O(changed file analysis + changed dependency edges + affected files' closures)`, not a scan/parse of the program's specs. Full bootstrap and global toolchain changes remain proportional to all input. A complete serialized source seal may still cost `O(F)`; separate it from redoing all schema/spec work.

**Expected gain.** Hundreds of untouched file closures and thousands of contract parses disappear per local edit. This does not establish a speedup for a truly from-scratch boot, which has no prior derivations to reuse. Measure warm one-file publication separately from cold analysis, store construction, loading and arming. The 150–200 s boot number cannot honestly be allocated to this path from source alone.

**Proof requested:** existing `seon.fn.publication-cache-test/lint-direct-callers-after-committed-declaration-changes`, `seon.schema-test/an-incremental-projection-equals-its-full-build`, `seon.schema.projection-writer-test/committed-reads-derive-once-per-declaration-population`; add one-file edit counters, schema dependency addition/removal, cross-file signature changes and analyzer-pin change. Preserve the explicit cross-file dependencies noted in `derivation-keys`' docstring.

### 8. The incremental arity gate scans whole-program datoms to construct its cache key

**Owner:** `seon.db/arity-content-key`, `src/seon/db.clj:4431`; `arity-base`, `:4441`; `report-arity-comparison`, `:4495`, final-key seed at `:4568`.

**Current algorithm.** The actual comparison uses changed caller/callee indexes. However, a speculative `db-before` takes `by-content`, assembling the whole arity datom key. The writer's values are commonly speculative (the function documents this). After updating changed edges, the gate builds another whole `db-after` key to seed its cache. Hence even a one-function change pays `Θ(A)` in program arity datoms before/after, where `A` is unrelated to changed edges. Committed-value hits avoid the first scan, not the changed final-key scan.

**Change.** Use the pinned Datahike attribute-revision identity for exactly the arity inputs (plus required codec/judging identity), following `carried-projection`'s existing speculative-value discipline. Carry the computed before/after base through those immutable keys. On a new lineage or unknown basis, derive/hash content once to establish equivalence; never trust a speculative revision as proof of durability. Do not put a candidate cache on a connection or add a transaction stamp.

**Target.** Known lineage: key cost proportional to the fixed input-attribute set, then `O(changed edges + affected bounds)` updates. Cold/unknown values retain `O(A)` content discovery. Preserve first-population behavior where the judging projection differs from `db-before`.

**Expected gain.** `arity-base` documents ~78,000 datoms/114 ms just to form the content key. Two such scans are on the order of 0.2 s of avoidable work under similar conditions, **not measured here**. See the existing issue [report arity comparison cost](../../seon/issues/report-arity-comparison-costs-hundreds-of-ms-per-function-row-write.md); this is its remaining key-construction component, not a claim that incremental comparison never landed.

**Proof requested:** `seon.db-test/arity-components-and-callers-are-checked-in-the-final-state`, `a-report-touching-no-function-reuses-the-committed-arity-comparison`, plus new-branch first write, speculative same-basis/different-content values, and first-population cases. Count arity key datoms visited, not only comparisons.

### 9. Repeated EDN reads parse and print identical stored values again

**Owner:** `seon.schema.datahike/decode-attribute-value-in`, `src/seon/schema/datahike.clj:1102`; readers `seon.db/decode-attribute-value`, `src/seon/db.clj:1884`, and query/pull decode paths.

**Current algorithm.** Every EDN slot read runs `edn/read-string`, then `storage-string` over the decoded value to verify canonical bytes. A value of size `L`, read `R` times, pays `Θ(RL)` parse/serialization work and allocation even when unchanged. Sorting canonical map/set data can add comparison/sort cost. The same dependency plans, contracts and schemas recur across turn planning, pulls and publication. Datahike query-result caching precedes this bridge and does not cache its logical decoded value.

**Change.** Cache successful canonical decode by stored string and codec implementation/content identity in the existing `seon.cache`, weighing both key and decoded value. The cache returns the immutable decoded data. Keep slot-specific refusal metadata outside the shared successful value; do not cache a malformed result as valid. Read semantics already say committed data is decoded as written, not revalidated against today's write schema, so a whole-projection key would unnecessarily destroy reuse on unrelated schema changes.

**Target.** One `O(L)` decode/canonical check per distinct codec+text value, subsequent lookups without reparsing/reserializing. Equality/hash of a newly allocated text key can still cost `O(L)`; this is not a guaranteed constant-time end-to-end read. Batch interning/reusing the held stored string avoids claiming a free content key.

**Expected gain.** For a plan read 100 times, remove 99 parse/print cycles; total gain depends on string-key and Datahike costs. Fix #1 and #6 first, since eliminating the reads is better than caching their decode. Recent `53ab87bac` removed read-time schema admission; it did not remove this round trip.

**Proof requested:** existing vector/codec tests in `seon.schema.vector-storage-test`, malformed/noncanonical storage refusal cases, old committed plan under a changed schema, and repeated identical text under distinct attributes/projections. Count parser calls and allocated bytes; retain only bounded cache entries.

## What the recent diffs have already fixed

These are credited improvements, not new findings:

| Change | What it removed | Remaining boundary examined here |
|---|---|---|
| `37cdfa4e6` | Unconsumed `:seon.turn/changes`; commit records preview plan 7,953 → 92 ms | Never revive it to implement #6 |
| `2367d8ba0`, `8361d97a2` | Printing entire closures for reach SHA; leaf-byte hashing | #4 is closure construction/repetition before the hash cache |
| `6996cd574`, `a3c1bcf81`, `9ac21b93f` | Full evidence decoding for all history, uncached as-of reach, repeated vector pull lowering, oversized stored patterns | #1: pattern work still precedes cap; #6: history still sorted/parsed |
| `18ce1b1a4`, `71af4f638` | Multiple vector read parses/decoders and repeated vector-write matching | Do not replace the now-linear matching; #9 concerns EDN slot text |
| `1625fb9bc`, `76c8e8b85`, `cd769af69` | Full recompilation and lost speculative/committed projection reuse | #7/#8 separate expensive key discovery from saved derivation |
| `caa379797` | Reanalysis of files whose derivation key is unchanged | #7 follows the cost of producing those keys |
| `5f2aa93f4`, `88289e006` | Reacquisition on ordinary non-program writes; other private-def discovery scans | #5 names the surviving unconditional turn-fork regeneration |
| `7749eb1c7`, `bb47fd6d1` | Added per-world call counts and moved product turns onto the one batch path | Use those counts; do not invent another profiler |
| `17fd9768e` (landed during review) | One preview fork for all changed reads; commit records nine forks costing 526 ms | #5 is the remaining continuing-context regeneration, not per-preview-read forks |
| `5d3d3cc08` | Coarse/stale read-currency logic; uses lineage and changed-pattern witnesses | Preserve these semantics in #1/#3/#6 |

`fn/gate-sets` now has two meanings (`src/seon/fn.clj:2003`): map arity selects tests using one combined frontier; positional arity computes conservative per-seed reverse closures for destructive isolation. The latter can repeat overlapping walks (`gate-sets-in:1877`), but I did not rank it as the ordinary selection bottleneck. `declared-callers:1797` already batches attribute reads, and `reverse-closure:1963` batches frontiers. Avoid optimizing the wrong caller.

The value printer is also substantially bounded: `held-order` sorts only maps/sets of at most 64 entries (`render/value.clj:400`), `seq-cells:359` traverses a bounded prefix, `prepare:1231` selects one tree and emits both faces, and `print/value.clj:54` is the small bounded floor. I found no basis to claim every value print traverses the whole value or that historical HTML must be rerendered on every GET. The web owner already retains calls (`render/web.clj:2491`); the missing caller conversion in #3 is specific.

## Systemic cause and two prevention rules

The recurring defect is **incremental inner work wrapped in non-incremental discovery**. The project often caches the final expensive product after rebuilding a whole input/key, carries a world but then rediscovers something it already contains, or puts a complete old population into a per-item helper contract. Each owner looks locally reasonable; their composition multiplies work. Examples here: per-input evidence × all query results; per-root validation × lifetime component history; per-file key × schema closure; per-test digest × all-vertex closures; per-turn regeneration × all bindings.

Contract overhead amplifies the shape: a cached Malli validator still traverses its input. `closure-digest` receives whole `nodes` and `schemas` maps for each test, and its own documented armed overhead is 0.63 s across the batch. This is not a reason to disable contracts. Pass the compact data the function actually consumes, compile once with Malli's existing cache, and validate at the correct owning boundary. `db/db`/`carried-projection` repetition likewise needs call counts and miss counts before another cache: many hits are already cheap, while their miss/key paths can be large.

1. **Require a work-count budget on each hot operation, tied to growing input.** Record `(input size, changed size, output size, calls, unique content keys, derivation misses, nodes/datoms visited, allocated bytes)` beside phase elapsed time. Every nested loop states which dimension it adds. A fixed-input/fixed-change run with 10× unrelated program/history should not perform 10× discovery unless the returned value actually grows. For a cache proposal, measure key construction separately from hit/miss work. For ownership changes, name the complete value whose validation grows.
2. **Expose the existing per-reply counts, and include overhead stages without corrupting code evidence.** `seon.profile/timed` already records `[symbol digest]` counts in the evaluation world's sink (`profile.clj:145`); `batch/executed` and `batch/calls` expose them (`batch.clj:80,87`). Produce a bounded report of repeated calls, initially flagging >100 entries per evaluation as a diagnostic, with workload-normalized expectations for legitimate row loops. Add stage-owned cost counters for acquire/prelude/evidence/render/record: `batch/form-world:234` deliberately separates user-code evidence from machinery, so the user-form sink alone misses this audit's worst repetitions. Keep that semantic separation. Report inclusive times as inclusive; do not subtract overlapping totals or use JVM-global deltas as precise concurrent-agent attribution. Flag one pure derivation called repeatedly with one key, or a no-change phase invoking any producer, more strongly than a large raw count.

Prefer deterministic work-count regressions at 1/10/100/1,000 inputs over only five-second timeout tests. Root should use the real armed path and injected worlds, not `with-redefs` or a parallel test-side implementation. No new profiler, graph registry, reader or test runner is needed.

## Landing and verification

**Broken/foreign boundary first:** the extended contracts compile check reported 10 pre-existing failures, all in the archived `script/seon/dev/docstring.clj` (four contracts: `check-source:285`, `check-file:318`, `format-findings:334`, `scan:360`) and `script/seon/dev/markdown.clj` (six: `parse:1020`, `validate:1050`, `validate-file:1090`, `validate-repository-pins:1124`, `format-violations:1170`, `fix:1215`). They refer to local schema symbols/Vars which the packaged contract compiler does not resolve. No finding named `src` or `test`. The doc does not alter those files; overall extended compilation is **not green**.

- Owned change: this document only. No implementation, schema, test or issue-index edits. No source/test line delta, no runtime adoption claim.
- Shared-tree edits seen at capture: `src/seon/{db,turn}.clj`, `src/seon/render/web.clj`, `src/seon/schema/datahike.clj`, their resources/tests, and other unrelated work. Subsequent review-time commits changed MCP/repl/oversight/render-coverage tests and shared the system-preview fork (`17fd9768e`, diff inspected); the citations remain pinned to the archive. Nothing was restored, staged wholesale or modified outside the owned document and disposable review directory.
- Offline evidence: `tmp/astra-algorithmic-audit-20260928/{basis.txt,verification-basis.txt,recent-src-log.txt,lint.log,contracts.log,verification-lint.log,verification-contracts.log,contracts-check.clj}`. The findings and exact failure summary are preserved here even if that temporary evidence is later removed.
- Archive command: `git archive 90828bb935b97c5bd0b1bafc16472880017dbcea | tar -x -C <snapshot>`. Dependency sources were archived at gitlink pins. Shared dependency class caches were linked read-only by use; the two Java class directories correspond to the checked Datahike/http-kit pins. No shared cache was rebuilt. Unavailable nested Babashka reference-only gitlinks were skipped; none was a required local dependency of the check.
- Lint in the archive: `clj-kondo --cache false --lint src script test`: **0 errors, 1,731 warnings, 6,480 ms**. Warnings are not a zero-findings pass.
- Contracts: `seon.contracts-compile-test/check` over `(source-files ["src" "script" "test"])`, using the archive's packaged projection: **497 files, 10 findings, 5,374 ms check body**; separate JVM wall time **10.06 s** including acquisition. This explicitly requested static compile check ran no test bodies. The full command used `clojure -Sdeps '{:paths ["target/dev-dependency-classes/77d37b9354b9913ba2b3e90e0868a46a03e6f2ecec4499b865cd25874417f65a" "src" "resources"]}' -M:dev:test ../contracts-check.clj`. Initial classpath preparation refused missing archived Java class directories; linking the existing matching dependency outputs resolved that setup boundary.
- Pre-commit refresh: a fresh `git archive` of **`de6f4fef44a5fec0f33de510d5068aef2462b537`**, plus only this document, passed the same zero-error lint criterion: **0 errors, 1,731 warnings, 6,832 ms** (exit 2 for warnings). The contracts check again covered **497 files**, with the **same 10 script-only findings**, **4,625.654 ms** check body, **11.60 s** separate-JVM wall time (exit 1). Dependency pins were unchanged. These corpus-wide checks exceed the ordinary-operation target because they deliberately read all source/contracts; they are not a runtime latency pass. Source citations retain the original audit basis.
- Runtime timing, heap/retained memory, browser paint, publication, adoption and armed behavioral proof: **not measured in this review**, by instruction. Estimates above are source-derived and must not be reported as measured savings.

This documentation-only commit requires no runtime test request. For implementation, root owns the focused exact existing tests named under each finding, plus the proposed work-count regressions (not yet installed). Prioritize #1's collection-bound pull proof, #2's growing-history settlement, and #3's unchanged system turn before spending another round optimizing rendering layout or SHA internals.
