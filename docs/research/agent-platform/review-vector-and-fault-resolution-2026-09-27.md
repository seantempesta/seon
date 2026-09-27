# Review: vector storage and fault resolution — 2026-09-27

Review only. Both designs require revision. The vector proposal loses empty values and can delete retained component identities; its claimed transparent read surface is incomplete. The fault proposal correctly deletes digest-based resolution, but its cutover closes unknown conditions and its process-death inference is stronger than the store lock guarantees.

## Basis and limits

Read AGENTS.md end to end and the data-modeling, datahike and data-oriented-clojure skills before review; also consulted the REPL/testing skills, the data-modeling guide and the turn PRD §§13–15. Reviewed vector commits `67b982219` and owner clarification `a47d88154`, and fault commit `5c01dde0a`. Source citations below are relative to Seon archive **`4dea3b95a7af7dd0bd5d96786d8dd963d1a6a015`** unless explicitly marked as a design citation. This avoids another lane's subsequent `src/seon/db.clj` edits. `vector` and `fault` below abbreviate the two supplied design document paths.

The checked-out Datahike HEAD equals that archive's gitlink: **`fe1d4e26edcc251f4471e720504b56353f4c2d09`**. Dependency citations were checked against that revision, not inferred from skill line numbers. Source inspection proves implementation seams, not installation of either proposal. No database writes, runtime code changes, tests, reloads, stops or resets were performed. Prices below are engineering estimates, including focused regression work, not measured implementation timings; overlapping prices must not be added mechanically.

Read-only health observations (`bin/seon status`; MCP `runtime_status` with root `/Users/sean/src/seon`, cluster `default`) found pid **89589**, start **2026-09-27T18:34:08.230Z**, archive **`b7e148de656032b682df7c159a1d0907d896d100`**. Readiness had no missing layers, but health was **faulted**: 30 signatures, 7 open; 72 failed tests, 68 unknown; 9 errored receipts. The cache reported 42,205,614 weighted bytes of a 512,000,000-byte bound. Reported historical slow calls included `boot/request!` 28,572 ms and `refresh-source!` maximum 28,366 ms; these are foreign findings, not timings of this review. This review does not reinterpret the design's older 30-signature snapshot as current evidence.

## 1. Vector storage

The bridge currently maps collections to many-valued attributes. The proposed cells retain native element attributes and reuse Datahike refs and component traversal. That is a defensible starting point for one encoding, but representation, ownership and logical query semantics need separate treatment.

### Dependency claims checked

| Claim | Pinned evidence and result |
|---|---|
| Many-valued storage loses sequence order and duplicates; empty collections emit nothing | `reference-code/datahike/src/datahike/db/transaction.cljc:719–779` expands members; `:787–812` adds datoms, and `:588–626` handles existing datoms. Correct. Cells can retain multiplicity because their entity ids differ, but zero cells still store nothing. |
| Native ref resolution, sweep and cascade survive when the element attribute stays a ref | `transaction.cljc:795` resolves a ref value; `:999–1015` sweeps incoming ref datoms; `:832–835` schedules component destruction. Correct. None traverses ref-looking tuple members. |
| Card-one replacement does not delete its former component | `transaction.cljc:787–812` uses the ordinary upsert path, without `retract-components`; cascade is explicitly invoked in `:999–1015`. Correct observation; replacing the whole cell is not the only correct repair (V2). |
| Homogeneous tuples have an eight-element ceiling | `transaction.cljc:1021–1032`. Correct. Heterogeneous tuples also require declared length; their all-invalid member-type bug remains at `:1038`. Seon validation must establish element types independently. |
| Transaction functions see preceding operations, not later repairs | `transaction.cljc:1055–1062,1171–1172`. Correct. Final invariants belong in `validate-report`, `:1224–1233`, called at `:1295`; tempid retries at `:845–856` prohibit assuming exactly-once effects. |
| Wildcard pull expands component cells | `reference-code/datahike/src/datahike/pull_api.cljc:346–350`. Correct, but selectors, reverse refs and temporal views do not thereby become logical vectors (V3/V4). |
| An explicit pull limit can be applied safely to the shared cells relation | Not established. `pull_api.cljc:315–323` limits datoms before decoding. A shared cells relation mixes different vector attributes; a raw limit can cut the wrong vector or positions. The pinned default is **nil**, `:16`, not 1,000. |
| Tuple or opaque-value encodings are smaller | They use fewer datoms in the design's measurements, but lose native element ref semantics. `schema.cljc:87` admitting `:db.type/any` establishes neither arbitrary-value index safety nor component behavior. |

Malli `collection-transformer` (`reference-code/malli/src/malli/transform.cljc:522–529`) coerces collection types; it does not recover missing positions or ownership. Schema-specific interceptors exist at `:403–415`. A bridge decoder is reasonable; a transformer is not a substitute for an information-preserving storage representation.

### V1 — HIGH: empty vectors and absence are indistinguishable

**Evidence.** Vector design `:165–182,199–202` removes the author attribute and stores only element cells, then reconstructs attributes by grouping cells. For both `{:p/items []}` and an omitted `:p/items`, there is no cell with that attribute. Datahike expansion at `transaction.cljc:747–758` emits no empty-collection fact. Two empty vector attributes are likewise indistinguishable. This fails the owner's vector-in/vector-out requirement, optional presence, required empty vectors, and clear-to-empty on an existing entity. No amount of decoder inference can recover a distinction never stored.

**Smallest fix.** Keep the one cell encoding and add one bridge-owned positive presence fact per parent/vector attribute, including empty vectors. For example, a set of vector attribute identities on the parent costs one datom per present vector. It is a codec fact consumed by decoding, not an authored ordinal or a second scalar encoding. Include presence in replacement, removal, validation, adoption and historical reads. An empty-vector marker cell is an alternative but complicates the exactly-one-element invariant; do not silently synthesize every optional vector as empty.

**Price.** 2–4 hours; roughly 15–30 implementation lines plus absence/empty/clear regressions; +1 datom per present vector. This is required information, not optional optimization.

### V2 — HIGH: positional replacement destroys components that remain in the vector

**Evidence.** Vector design `:176–184` retracts a changed cell, cascading its component, and compares component values without `:db/id`. Consider a component vector `[A B]` changed to `[B A]` using existing entity ids. Retracting the cell at position 0 destroys A even though the new vector retains A. Native `retractEntity` also sweeps incoming refs (`transaction.cljc:999–1015`); rebuilding from id-only maps cannot restore the destroyed entity's attributes. Rebuilding full maps instead changes identity and can lose incoming relationships. Two distinct components with equal data also compare equal after removing ids, so a requested identity replacement can be ignored. Duplicate **values** and duplicate references to the **same owned entity** are different cases; the current owned-value validator rejects multiple owners (`src/seon/db.clj:3540`, `write-owned-values-error`).

**Smallest fix.** Preserve retained component identity. At the writer, reconcile the old and desired ownership edges for the whole vector: move retained cells/positions or unlink old component edges before deleting obsolete cells; destroy only components actually removed. Native `:db/retract` does not cascade (`transaction.cljc:1078–1089`), so use that seam where an edge moves. Validate exclusive ownership in the final database. Declare whether repeating one component eid is refused under existing ownership law; equal independently owned maps must still round-trip. Normalize numeric ids, lookup refs and pulled ref maps before equality; a raw representation comparison is not semantic equality.

**Price.** 4–8 hours, approximately 30–60 lines replacing the proposed positional-only diff, plus move/equal-value/distinct-identity cases. Keeping only scalar and peer tests green would not prove this behavior.

### V3 — HIGH: the proposed query rewrite does not define transparent Datalog

**Evidence.** Vector design `:209–222` handles a constant-attribute three-term membership clause. It does not cover `[?e ?a ?v]` with `?a` supplied through `:in`, discovery of arbitrary attributes, explicit sources, four/five-term temporal patterns, `get-else`/`get-some`/`missing?`, or reverse access. Existing Seon queries do use input-bound attributes (`src/seon/issue.clj:606–607`), so this is not a speculative grammar extension. Rule arguments are caller data, not necessarily contained in the query form. The physical author attribute moves from parent to cell; unlowered clauses therefore return a cell id or no row. The proposed membership expansion also yields members, not a vector: relational results deduplicate and do not promise order. Thus vector design `:327–330` cannot assert the same duplicate-preserving vector for an ordinary `:find ?v` membership query.

**Smallest fix.** Specify the logical contract first: pull/entity return the whole vector; membership queries retain ordinary Datalog set semantics and return the parent, with order available through a declared logical operation if needed. Lower supported patterns once using the dependency parser, per source's projection, preserving source/tx/added fields, variable bindings and negation/rule scope. Selectors in query pull expressions must be rewritten **before execution**, not only in `decode-query-result` (the current call order is `src/seon/db.clj:2495–2501`). Explicitly refuse unsupported vector query forms until implemented; do not label ordinary queries raw merely to excuse wrong answers. The 25-line estimate does not cover this contract.

**Price.** 1–2 days for the query surface and regressions; a bounded initial subset is 4–8 hours but needs an explicitly reduced contract and cannot claim every ordinary read is transparent. Hand-converting nine known joins is cheaper only by abandoning the owner's future-agent guarantee.

### V4 — HIGH: pull rewriting alone misses selectors, replay and temporal completeness

**Evidence.** `pull_api.cljc:315–365` implements aliases, limits, defaults, subpatterns and reverse refs. The proposed shared selector in vector design `:196–202` has no plan for these semantics. In particular, reverse lookup of `:p/_items` now reaches cells instead of owning parents. A pull of two differently aliased vector attributes cannot apply limits to the pooled cells relation and call the result equivalent. More directly, `src/seon/db.clj:1030–1044` replays pull and pull-many through dependency plans **without** `pull-call`, so a rewrite only in `pull-call` fails replay. `history`, `as-of` and `since` return dependency database views (`:3020–3058`), not decoded snapshots. `since` can contain a changed cell but omit its older parent link; unrestricted history can join values and links from different times. Decoding that fragment as a current complete vector is false.

**Smallest fix.** Put selector lowering and logical decoding in the common plan seam used by direct reads, query pulls and replay. Retain both logical selectors and physical dependency plans. Apply aliases/defaults/limits to the requested logical vector and preserve honest elisions/refusals; map reverse edges back to parents. Read `as-of` from that basis's declarations and complete graph. Specify history/since as change views; reconstruct a vector only with an explicit complete basis, otherwise return the declared partial/raw change form or a typed refusal. Do not fabricate a complete vector from missing edges.

**Price.** 1–2 days overlapping V3; roughly 50–100 lines across existing owners plus selector/replay/temporal cases. It invalidates the claim that the entire pull change is about 25 lines.

### V5 — HIGH: the write and caller cut is incomplete

**Evidence.** Vector design `:185–188` claims one raw retraction is the only conversion. `src/seon/turn.clj:1471–1477` emits `[:db/add receipt attribute reference]` for **both** `:seon.test.accretion/gate-tests` and `:seon.cluster.eval/faults`. Those ordinary receipt writes would now refuse. The design also distinguishes new parents by a tempid without an identity and existing parents by numeric id/identity; repeated maps naming the same tempid in one transaction require replacement semantics too. `encode-transaction-data-in` wraps transaction-function output (`src/seon/schema/datahike.clj:490–501`), so internally emitted cell attributes must be marked as encoded or they can be rejected/re-encoded by the new vector rule.

**Smallest fix.** Convert receipt-reference assertions and all operation forms in the same cut, with vectors assembled at the owner and encoded once. Define clearing the logical attribute (empty is not absence), lookup-ref parents, repeated tempids, nested updates and encoded internal operations. Sweep dynamic attribute producers as well as literal keyword uses. Keep the final validator authoritative over all admitted operation forms.

**Price.** 3–6 hours including the full call-site sweep and receipt-settlement regression, roughly 15–35 net implementation lines. The settlement path is a required integration dependency, not a follow-up.

### V6 — MEDIUM: measured and estimated costs omit necessary work

**Evidence.** Vector design `:176` reads every cell attached to a parent to select one attribute; `:200–205` groups and sorts but calls decoding linear. Sorting by position is O(n log n); comparing a whole vector is O(n) even for one append, before transaction/index work. The reported operation count is not the complete algorithm's work. Its cardinality replacement migration (`:300–312`) accounts for program/test regeneration and loss of observation links, but omits explicit restoration of vector-valued configuration such as filesystem roots and AI stop sequences listed at `:40`. The benchmark excludes the Seon writer and armed complete-value validation (`:64–68`).

**Smallest fix.** Price scans in terms of all parent cells and expanded component nodes, charge both logical/physical validation work, and label the timing table as dependency-only. Use sorting first; do not add a position index without a measured need. Include configuration, opaque values and required empty vectors in the dropped/rebuilt inventory. Keep the authorized discard policy explicit; a later read cannot recreate lost order/duplicates from the old set. Revise the 115-added/90-deleted estimate after V1–V5 rather than treating it as permission for implementation.

**Price.** 1–2 hours to correct the design/inventory; 2–4 hours for an armed end-to-end comparison and adoption proof run centrally. No new cache or materialized logical database is justified by the current measurements.

### Answers to §8, including read-surface inventory

**Q1 — refuse a swept element?** Yes for a surviving vector cell: silently dropping its missing element changes the stored value. Coordinated removal/replacement of that occurrence must remain legal; deleting the parent cascades only its owned values. This is an integrity rule for the bridge cell, not proof that every peer relation ought to block target deletion forever. `gate-tests` describes tests observed at evaluation (`src/seon/turn.clj:3416,3445–3448`); the durable observation should normally be test symbols, with digest/run evidence where needed, rather than a liveness ref. `src/my/program.clj:708` can delete a program entity; an old receipt should not prevent a legitimate test deletion. Make that declaration/caller change in the cut, not a decoder exemption. Fault links require the same statement-versus-observation decision at their producer. Price: 2–4 hours for the gate-test conversion and deletion regression. No claim is made that a live deletion was attempted here.

**Q2 — rewrite or nine hand conversions?** Use the one bridge lowering seam, subject to V3/V4. Hand conversion leaks storage grammar to authors and does not meet the owner requirement. The original alternatives are not equally correct, and neither price includes full semantics.

**Q3 — sequential?** Do not silently give `:sequential` set semantics or a second ordered encoding. If admitted as stored ordered data, use the same cells and document the canonical returned collection type; otherwise refuse unsupported stored sequential declarations. With zero installed instances, this is not a migration blocker. Prefer reuse of the vector encoding, roughly 1–2 hours for the bridge branch and conformance cases. Runtime-only sequential schemas need no change.

**Q4 — exactly what can still expose cells?** The following is a source-based inventory of the proposed cut, not a claim that cells are installed:

| Surface | After only the proposed changes | Required disposition |
|---|---|---|
| `seon.db/pull`, `pull-many`, nested/wildcard pulls | Basic cases decode; selectors remain incomplete | Logical vector on all supported selectors; V1/V4 fixes. |
| `seon.db/entity` | Already calls wildcard `pull-call`, `src/seon/db.clj:2771–2794` | Covered by the common decoder; do **not** classify it as raw entity access. |
| Native `datahike.api/entity`, pull, q | Bypass Seon's codec | Explicit dependency/raw-storage APIs; still reachable intentionally. Owner's “ordinary reads” must identify the Seon surface. |
| Query pull expressions | Output decoder alone is too late | Lower input selectors before `q-with-evidence`, then decode once. |
| Ordinary Datalog | Input-bound/discovery/reverse clauses expose cells or disappear; membership does not preserve multiplicity | V3 contract/lowering, not a blanket raw label. |
| `as-of` passed to Seon reads | Can decode if graph and declarations come from the same basis | Test old/new schema bases across migration; no current-projection assumption. |
| `history` / `since` passed to Seon reads | Physical temporal fragments can be incomplete or mix versions | Raw/change semantics must be declared; complete reconstruction requires a named basis. |
| `seon.db/datoms`, seek/index pages; native datoms | Physical cells/positions remain; `datom->data` only decodes individual values (`src/seon/db.clj:2796–2819`) | Legitimate explicitly raw storage. A cell eid cannot be relabeled as parent without changing datom truth. |
| Transaction reports/listeners | Native `:tx-data` contains cell operations; writer delivers the whole report (`reference-code/datahike/src/datahike/writer.cljc:417–441`) | Keep native reports raw. The ordinary transaction render must label raw details or show logical changed entities through the bridge; currently it renders report datoms (`src/seon/db.clj:4955,4991`). |
| Wake matching | Matches stored `:e/:a/:v`, `src/seon/cluster/wake.clj:396–415` | Raw listener patterns may remain raw; if agent patterns mean logical parent attributes, lower them once at registration. Otherwise vector writes can stop waking the parent. |
| Read evidence and since-diff | Physical dependency plans are valid evidence, but replay bypasses `pull-call` | Track cells, presence and positions physically; retain logical requests; V4. A reorder must invalidate an ordered read. |
| `seon.db/diff` / `apply-diff` | `src/seon/db.clj:3092–3121` delegates native diff | Explicit raw database delta; same codec generation required on application. Do not decode/re-encode physical cells twice. |
| AI/HTML, schema validation, `seon.render.value/transacted` | Only as correct as their input reads | Consumer presentation cannot repair leaked cells; normalization must recognize logical vectors despite physical card-one element attributes. |
| Raw `:db/add` / `:db/retract` | A write API, not a read exception | Refusal must name the logical whole-vector operation; V5 converts first-party callers. |

**Is there a smaller correct encoding?** The dependency-only scalar alternatives are smaller, but not correct for the full requirement. EDN loses per-element refs/cascade; `[position value]` tuples do too; positions on children fail shared peer targets and duplicate occurrences. One cell per occurrence with a native element edge is the smallest demonstrated native-ref composition here. Keep it and add minimal presence information; do not claim a lower bound or invent a second logical database. A per-vector container makes presence obvious but adds an entity and links; it is a design alternative only if simpler selector/ownership code pays for that extra storage. No such measurement is provided. The immediate simplification is to preserve the existing dependency parser, writer and final validator, not replicate them in a 25-line partial query interpreter.

**Verdict: REVISE — V1 empty presence; V2 retained component identities; V3 logical query semantics; V4 common selector/replay/temporal handling; V5 complete write/caller conversion; V6 honest cost and migration inventory.**

## 2. Fault resolution

Keep the existing occurrence → resolution → open-faults → health/notification flow. Removing definition-digest resolution is correct: a changed definition says nothing about whether a condition ended. Explicit resolution and task closure are positive authority. Process scope belongs on the producer's declared error schema, provided termination is actually established.

### F1 — HIGH: a later boot proves store handoff, not process exit

**Evidence.** Boot acquires the root store before later layers (`src/seon/cluster/boot.clj:164–172`). `open-store!` canonicalizes the path and takes its exclusive sibling flock before connecting (`src/seon/cluster/store.clj:425–469`); a foreign holder is refused. `acquire-root-store!` shares that one value inside the JVM (`src/seon/cluster.clj:919–955`), and the last holder releases it (`:957–970`). A failed Datahike release retains the flock (`store.clj:525–541`). This verifies **one concurrent holder through the Seon store owner**, including canonical file-backed test fixtures (`test/seon/test_support.clj:128–146`). It is not a native Datahike interprocess guarantee for a caller bypassing that owner.

Crucially, `boot/stop!` releases the store at `src/seon/cluster/boot.clj:573–579` and returns without terminating the JVM (`:590`). `store/release-store!` explicitly allows the same process to reopen (`:532–533`). `ensure-cluster-entity!` is branch-local and asserts a process row only when its identity is absent (`src/seon/cluster.clj:3273–3295`); it is not an acquisition epoch. A valid sequence is A releases while its JVM remains alive, B boots/releases, A reacquires: the newest process-id assertion can still be B. A process-scoped fault from live A can then be classified as ended. Memory pressure is specifically a JVM condition, so store release is insufficient.

The single-holder law is sound; the extra inference in fault design `:112–113` is not. A frozen/offline branch can miss a newer boot and conservatively stay open. That is different from proving death. Synthetic boot/config provenance and missing process rows must remain unknown. Require a nonempty occurrence set and propagate query/schema errors before applying “every occurrence ended”; vacuous truth must never resolve an unknown fault.

**Smallest fix and priced choices.** Keep resolution pure over explicit evidence. Choose one of exactly these alternatives before cross-owner implementation:

1. **Recommended, conservative first cut:** resolve these conditions through existing `resolve!` only after root verifies the exact `(pid, start-instant)` exited, while retaining declared scope for subsequent automation. Guarantee: no inferred death; cost 1–2 hours for design/guard cases plus operator verification per incident; sacrifice automatic process closure initially.
2. Record positive process-ended evidence through the existing process/lifecycle owner after observed exit; the new holder can record a verified prior exit. `resolution` reads that fact. Guarantee: automatic closure where exit is evidenced, unknown stays open; estimated 0.5–1 day plus a root-run two-process regression; sacrifice a small lifecycle schema/writer addition and no offline inference without evidence.
3. Define a distinct **store-ownership scope** with an actual acquisition generation, not the process-id assertion. Guarantee: closure at ended ownership, not ended process; estimated 1–2 days across boot/provenance plus tests; sacrifice applicability to JVM memory-pressure faults. This does not satisfy process scope by renaming it.

Do not add polling, a liveness cache, or a fault-schema hand list. The proposed `resolved-tx latest` is also not the transaction proving termination: return the actual end-evidence transaction if that field retains its existing meaning; notification opening can reuse its integer identity.

### F2 — HIGH: Q3's blanket cutover resolves conditions explicitly marked unknown

**Evidence.** Fault design `:151–153` labels four signatures “unknown” / “no recurrence since”, but `:278–282` includes all 11 digest-only signatures in `resolve!`. It also resolves c2268b2f even though `:143` says the agent is currently parked. Giving future occurrences a better schema does not produce a replacement occurrence for an already parked agent. The resulting “6 open” count is therefore not a correctness target. Some old signatures demonstrably share a defect class with an open successor (`:154–156`); that is triage evidence, not proof that every other condition ended.

**Smallest fix.** Replace the blanket pass with an explicit per-signature disposition: known fixed with evidence; process actually ended with evidence; duplicate deliberately superseded by a still-open, tasked record; or unresolved/unknown and linked to a task. Call `resolve!` only for the first three after root rules on the evidence. Attach the old sibling signatures to their defect task where appropriate. Keep the parked current condition open until its condition ends or is explicitly addressed. Record cutover decisions in the landing evidence. Do not copy the old digest answer merely to preserve a quiet dashboard.

**Price.** 30–90 minutes of root triage, with no new resolution mechanism. The claimed 15 writes under one second prices only execution, not correctness, and is not independently measured here. Leaving unknown faults open is the safe zero-write initial migration. Resetting discards the cited evidence and is not a repair for this rule.

### F3 — MEDIUM: property semantics are valid; admission and cost accounting are unfinished

**Evidence.** `seon.schema.internal/entity-properties` takes a **compiled schema** and merges entity-map/root properties (`src/seon/schema/internal.cljc:122–128`). This is a legitimate schema property, not a roster. The resolution must resolve the declared-schema key through the supplied database projection first; passing a keyword directly is not that API. The named scope enum in fault design `:193` by itself does not validate a Malli property. Error schema admission currently checks declared error shape/storage and render properties (`src/seon/schema/internal.cljc:260–326`), with no scope check. Absence can conservatively mean no automatic process closure; an unavailable/malformed declaration must remain a diagnostic/unknown, not be silently reclassified.

The task design is well grounded: `task-delta` derives owned attributes from `:seon.task/declaration`, compares revision basis, and retracts the row on deletion (`src/seon/issue.clj:618–636`). Adding a non-identity signature set to entry/declaration/task schemas fits that writer. The proposed history join on both fault-link and task-id retractions is positive closure evidence; dropping a link alone does not qualify. Requiring no current linked task conservatively keeps multi-task work open. History disabled or unavailable must not masquerade as closure.

**Smallest fix.** Add a small scope-property admission check at the existing error-schema authority, preserve inherited properties and reject malformed explicit scope. Read compiled schema and closure facts from the same database basis. Document nonempty occurrence/error propagation guards. Price batched reader derivation separately from the single-signature writer call; a batched simulated probe is not measured production `resolution` (`fault:220–236`). Include producer changes and task reconciliation/notification tests in the total.

**Price.** 2–4 hours for admission and reader semantics (about 10–20 lines plus cases). The complete fault rule, excluding F1's lifecycle choice and root triage, is plausibly 0.5–1 day; the +16-lines clause estimate is not a complete delivery estimate.

### Answers to Q1–Q3

| Question | Ruling | Corrected price / sacrifice |
|---|---|---|
| Q1 task → signature values in task EDN | **Accept (a).** The task is the reviewed defect authority; signatures are observed values, not refs/identities on the task. | 3 schema lines is only declaration cost. Allow 2–4 hours for revision reconciliation, closure history, recurrence and multi-task cases. Each sibling must be named; signature churn remains an upstream defect. The +8-line imperative `link!` alternative omits its writer validation/durability and loses reset persistence. Function-task inference is too coarse to be equivalent. |
| Q2 any open fault means faulted | **Accept (a).** Task assignment explains ownership, not health. Unknown scope/process evidence stays open or explicitly unknown. | Resolution policy adds no branch, but per-fault task attachment and `untasked-fault-count` still need schema/render/status work. Allow 1–2 hours within the overall cut. Reject (b)'s green-over-known-defect semantics regardless of its small line count. |
| Q3 cutover | **Revise (a), using F2; (b) is the conservative initial state.** | 30–90 minutes root evidence triage; actual writes measured separately. Preserve unresolved conditions. Reject reset as a resolution strategy. |

**Verdict: REVISE — F1 prove process exit rather than infer it from handoff; F2 evidence-based cutover; F3 validate/read declared scope and price complete integration. Accept removal of digest resolution, task-owned value links, and faulted health while any fault remains open.**

## Root's focused verification list

This documentation change needs no behavior tests. No test request was started. For implementation, proposed test names below are **new acceptance requests**, not claims that those Vars are installed.

Vector owner: `seon.schema.vector-storage-test/vectors-round-trip-through-ordinary-reads` (scalar, tuple, EDN union, peer and owned elements, duplicates, multiple attributes, aliases); `/empty-vectors-are-distinct-from-absence`; `/component-reordering-preserves-retained-identities`; `/vector-peer-deletion-requires-coordinated-repair`; `/logical-vector-queries-preserve-parent-and-source-semantics`; `/vector-replay-observes-reordering`; `/temporal-vector-reads-do-not-invent-completeness`; `/receipt-vector-writes-use-the-bridge`; `/vector-adoption-validates-elements-and-restores-config`. Include existing affected namespaces from vector §7 in one root-owned integration batch, not a test round per finding.

Fault owner: new `seon.problems-test/a-changed-definition-does-not-resolve-a-fault`; `/closing-its-task-resolves-a-fault` (include link removal, multiple tasks, recurrence and no-history); `/an-unknown-process-keeps-a-process-scoped-fault-open`; `/a-process-scoped-fault-closes-only-after-evidenced-exit`; and `/declared-fault-scope-is-validated-and-inherited`. Keep existing `seon.problems-test/a-resolved-fault-stays-closed-until-it-recurs` and `seon.error.branch-test/a-fault-is-delivered-and-shown-open-once-per-opening`. Root should select `seon.cluster.mcp-test` for status delivery/count integration and add task fault-link round-trip/revision coverage in the task reconciliation owner. For F1, extend **existing** `seon.cluster.boot-test/stop-instance` with the release-without-process-exit counterexample, and retain `/concurrent-start` for actual foreign-holder exclusion; root alone runs these platform tests.

## Landing checks and foreign boundary

Only this document is owned by this review. No source, test, resource, dependency or other lane's session was edited. Foreign working-tree changes seen during review (including later `src/seon/cluster.clj` and `src/seon/render/value.clj` changes): `.gitmodules`, staged `reference-code/CLM`, untracked material under `reference-code/http-kit`, `resources/seon/tasks/seon.dev.mcp.edn`, later `src/seon/db.clj`, a deleted scratch patch, and untracked `docs/prds/next-turn/` / `src-clm/`. None was included in the archive or commit. Their owners were not messaged or resumed.

Verification archive: `tmp/review-vector-fault-kmzjtnic`, initially from the source-review HEAD above; after other lanes committed, refreshed from **`aa545fe2175664febc94aef66a2f1f0e6a0304bf`**, with dependency sources extracted at gitlinks and the required nested babashka/fs gitlink. Both bases were committed HEAD when captured; source citations remain pinned to the review base. Existing dependency class directories were linked for read access, not rebuilt. The review document is the only overlay. This is not a Git worktree.

- `clj-kondo --lint src script test --cache false`: **0 errors, 948 warnings**, initially 6.55 s wall, 968,278,016 bytes maximum RSS; final HEAD-plus-document archive **7.25 s**, **1,012,088,832 bytes maximum RSS**, again 0 errors and 948 warnings (warning exit code 2). The user's zero-error requirement passes; a zero-warning/zero-finding claim would be false.
- Static `seon.contracts-compile-test/check`, extended to `src script test`: **10 existing script findings**, 32.65 s wall, 1,314,504,704 bytes maximum RSS. Foreign boundary: `script/seon/dev/docstring.clj:285,318,334,360` and `script/seon/dev/markdown.clj:1020,1050,1090,1124,1170,1215`; contracts use source-local schema vars/forms that this packaged-projection checker cannot resolve. This is not attributed to the doc and was not “fixed” outside the assignment. The existing issue `docs/seon/issues/development-adoption-cannot-compile-docstring-var-contracts.md` records the docstring class; no issue-index changes were made.
- Canonical static contracts scope (`src test`, matching `test/seon/contracts_compile_test.clj:346–348`): **0 findings**, initially 22.02 s wall, 1,304,821,760 bytes maximum RSS; final HEAD-plus-document archive **0 findings**, **40.62 s wall**, **1,386,790,912 bytes maximum RSS**. These are full static-check JVM startup/load plus whole-source-population costs, not per-contract or live ordinary-operation latency. This calls the checker directly, not `clojure.test`, `seon.test/run` or `bin/test`.

No installation, adoption, arming, runtime vector behavior or runtime fault-rule change is claimed. Net source/test/resource lines: **0**. The archive logs, including unresolved extended-script check evidence, are retained under the owned scratch path; the findings and exact commands/results are recorded here so the review survives chat.

The canonical checker invocation was `clojure -J-Xmx2g -Sdeps '{:paths ["src" "resources" "test"]}' -M:dev -e` with this form (extended run adds `"script"` to `source-files`):

```clojure
(require 'seon.contracts-compile-test)
(let [findings
      (seon.contracts-compile-test/check
       {:seon.contracts-compile-test/root "."
        :seon.contracts-compile-test/paths
        (seon.contracts-compile-test/source-files ["src" "test"])})]
  (prn {:findings (mapv #(select-keys % [:filename :row :message]) findings)
        :count (count findings)})
  (shutdown-agents)
  (System/exit (if (empty? findings) 0 1)))
```
