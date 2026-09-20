---
type: plan
status: launch specification; waits for steps 1–2 and overlapping-path release
created: 2026-09-21
tags: [plan, schema, malli, datahike, bridge, projection, dissolution]
---

# Bridge step 3 — carry only; durable stamp

**Guarantee:** an ordinary database operation resolves the population stamp
at its database's schema origin and consumes that exact acquired generation.
It never rebuilds from rows, selects packaged declarations, or accepts a
different request/context generation. Publication constructs the next world
explicitly; restart acquires the stamped durable world once. Declaration
rows and their stamp advance together under the existing writer.

This is the verbatim launch assignment for step 3 of the
[Malli-native bridge PRD](malli-native-bridge-prd-2026-09-20.md), following
its §7 and the [step-2 assignment](bridge-step2-walker-spec-2026-09-21.md).
The binding [carried-projection research](../research/step3-carried-projection-2026-09-20.md)
supplies the 125-owner/32-file census, production conversion list and seven
adoption obligations. PRD §6 governs where the stamp lives. The research's
prototype cluster attribute and source-digest substitution are experimental
spellings, not the production declaration specified below.

## Launch verbatim on astra low, after step 2 lands

> Implement **step 3 only: carry only; durable stamp** in
> /Users/sean/src/seon, branch steward-platform. Read this specification,
> the bridge PRD, step-2 spec, carried-projection research and
> publication-dissolution spec end to end. Read the actual step-1 and step-2
> landing notes, AGENTS §§1–3 and lane rules 11–16, the working edge's
> 2026-09-20 ~19:35 UTC block and current reset/ownership checkpoint, and the
> data-oriented-clojure, data-modeling, datahike, repl and clojure-testing
> skills. Read the dependency ledger below against the pinned sources.
>
> You are not alone in the codebase. Preserve unrelated edits; never revert
> others' work. Execute this bounded assignment directly, without further
> delegation. Launch only after the coherent **step 1 — Registry: compile
> once, seal, carry** and **step 2 — Compiled walker** caller/retirement
> commits land. A checkpoint document or dirty implementation is not that
> landing. Consume step 1's retained named schemas/function contracts,
> fixed-input construction registry, dependent-closure recompilation,
> unaffected-object reuse and live-Var predicate behavior. Consume step 2's
> compiled entity/storage navigation, component transaction grammar and
> whole-population native-schema parity. Do not restore either retired API.
>
> **Own** the production/resource paths in the per-file instruction table,
> their discovered acquisition callers, the named regression files below,
> test/seon/test_support.clj, the affected current carrier/restart claims
> in AGENTS.md, the bridge PRD and the applicable repository skills, and
> one landing note:
> docs/prds/steward-platform/research/bridge-step3-stamp-2026-09-21.md.
> Refresh the census against the landed tree. Coordinate every concurrently
> held path with the orchestrator before editing it; historical ownership is
> not a permanent hold. Never operate, resume, message or repair another
> lane's session. Public retirement and all callers are one coherent slice.
>
> **Implement the stamp and identity contract below.** Add exactly one
> durable, non-identity attribute, :seon.source/population-digest, on the
> existing branch-local source population row. Do not put it on the cluster
> entity or in Datahike's :meta field. Its declaration and every loaded
> producer/reader land together. Publication, adoption and accepted program
> declarations identify the exact admitted population using the existing
> source lineage and seon.id owner. Ordinary data/test-result writes leave
> that identity unchanged. No all-zero fixture sentinel for this identity.
>
> **One ordinary acquisition seam: seon.db/carried-projection.** Strengthen
> it in place to return the matching acquired projection or a flat typed
> refusal, never nil. Read the durable stamp at seon.db/schema-database
> before considering runtime metadata. A retained old DB keeps its old
> compiled value; a new stamp on a with-result cannot select stale metadata.
> Remove the operator-instance-table lookup and the supplied/package/row
> fallback ladders after callers carry explicit custody. Propagate refusals;
> an error map is never a truthy projection in an or/if-let chain.
>
> The explicit loader is **seon.db/acquire-projection [database]**, at boot,
> exact-commit/branch materialization and fixture reconnect only. It returns
> a carried database value or the same typed refusal. It checks the stamp
> first, reads the exact durable inputs once, verifies identity, materializes
> through the landed step-1 constructor and attaches through the existing
> carrier. Retain projection-from-rows as its internal row-decoding owner;
> retire projection-from-database and carry-derived-projection with every
> running caller. No ordinary lookup invokes acquire-projection on a miss.
>
> **Separate admission from restoration.** Restart verifies recorded
> admitted inputs and compiles them; it does not re-admit an old population
> under the current files' new declaration policies. Publication constructs
> and admits the supplied candidate independently of loading old rows as a
> newly admissible population. Read old native facts only for reconciliation,
> lineage and refusal evidence. Preserve row parsing, duplicate/provenance
> checks and executable-binding compatibility; incompatibility is a typed
> reset/acquisition refusal, never file substitution or relaxed admission.
>
> This must dissolve the ~19:35 UTC dead-end by construction: a paused
> publisher replacing an old dial population must not traverse
> current-publication → source/database → carry-derived-projection →
> projection-from-rows → build-projection's stricter admission before it
> can admit the new, correctly labelled candidate. The regression below
> must prove that ordering, not just removal of one function name.
>
> **The writer decides.** Hand the expected base generation and admitted
> candidate to the existing transaction boundary. Check the base in the
> writer's current database, carry each progressive candidate through
> sequential declarations, and final-validate against the final candidate.
> Commit its complete effective program facts and stamp together; a refused
> or stale transaction advances neither. Replace the after-earlier-
> declaration row reconstruction with that explicit progressive value.
> Do not mutate an environment during speculative with or a transaction
> function that Datahike may retry. Install the terminal accepted generation
> only after successful settlement, once per terminal world, not per row.
>
> A candidate/base composite does not rebind compiled dependents. Reuse the
> landed dependency graph to recompile the changed declaration and reverse
> dependent closure, including contracts. Preserve unaffected roots and held
> old DBs. Candidate-first lookup alone proves only addition, not replacement.
> Selector-derived pulled schemas are operation-local products and do not
> change the stored population stamp.
>
> **Preserve publication lineage.** Extend the landed publication owner;
> do not reopen its duplicate-publisher work. Full and incremental updates
> reconcile on the current published history and preserve every existing
> evidence reference. Toolchain changes still force complete analysis in
> that lineage. The population stamp is derived from the publication's
> source/toolchain inputs and exact admitted population, never an independent
> competing source identity or a replacement for :seon.source/digest.
>
> **RESET NEEDED.** Join the orchestrator's clean-tree batch after steps
> 1–2 and the consumer sweeps: 1a's error attributes, 1d's three change
> groups, and this one new stamp. The exact inventory is below. Keep an
> explicit construction path until that batch; do not make unstamped
> ordinary values legal. Never reset/restart/refork default or re-enable
> the owner's paused hook. Verify the changed schema on your own scratch
> root and report the orchestrator's reset and cold proof still owed.
>
> **Prove all cases below on the canonical armed fixture**, using
> with-database, extra-schema only for synthetic declarations, canonical
> program-fn-row and transacted! helpers, and real SCI acquisition. No
> hand-rostered schema, mocked registry or unarmed substitute. Instrument
> actual constructor/loader seams only for counts; validate real outputs.
> Capture one DB per observation. Missing/zero population or absent evidence
> is a failed/unavailable proof, never a green result.
>
> Run the named scratch-root publication/adoption/restart proof below.
> A second connect in the same process is not restart. Record process
> identities, exact source/stamp/toolchain identities, canonical input and
> normalized compiled-form comparisons, acquisition counts and phase times.
> Normalize executable objects to resolved qualified symbols for evidence;
> unidentified objects refuse the comparison. Do not hash printed JVM
> addresses or claim normalized bytes measure retained heap. Observe the
> affected browser page separately after adoption.
>
> **No writer diet, no walker changes, no new metadata mechanism.** Keep
> complete bounded owning-value checks, before/after reach, attempted and
> effective datoms, swept survivors, final graph refusal and tuple/logical
> validation. Do not change Datahike, introduce a global digest registry,
> wrapper DB, branch metadata service, listener, scheduler or second cache.
> Do not replace instrumentation or change live-Var reload semantics.
> No historical-schema-at-t policy, serialized compiled objects, migration,
> second identity generator, new production regex or timing-bound increase.
>
> Before the coherent commit, require all changed production namespaces in
> one foreground JVM; use the command below expanded for discovered callers.
> Iterate with bin/test-fast --paths <all owned changed paths> -- <affected
> namespaces>. No cold bin/test, --all, --full, nested cold gates or baseline
> publication in the lane. The orchestrator owns cold --paths and --platform.
> Report executed, unchanged and unavailable members separately.
>
> One foreground JVM at a time; never background or overlap tests/probes.
> No worktrees. A foreign load failure is not evidence against this slice:
> use the fast HEAD-plus-owned-paths snapshot and continue independent work.
> If snapshot admission refuses, report the exact path and keep the public
> retirement uncommitted until its caller/load proof is possible. Do not
> edit a foreign hunk to pass it. Down and remove owned scratch roots only
> after their recorded processes exit; preserve unrelated roots and edits.
>
> **Deliver:** one coherent path-limited retirement/caller commit, complete
> changed-path list and refreshed census, measured deletions/additions,
> compiler/loader/policy counts, all canonical fast tallies, restart and
> candidate/lineage evidence, RESET NEEDED inventory and exact cold/platform/
> default-adoption proof still owed in the landing note. Estimate **5–7
> lane-days**, excluding coordination/reset/gate queues. Stop when committed,
> before steps 4–5. At a genuine cross-owner design decision, stop before
> production edits with exactly three priced options, simplest viable
> constraint first/recommended, each stating guarantee, cost and sacrifice.

## Stamp declaration and one identity family

Declare this member in `resources/seon/schemas/seon.source.edn`, alongside
the existing non-identity digest observations at HEAD `:31–34`:

```clojure
:population-digest
[:and
 {:seon.db/identity false
  :description
  "Value: digest of the exact admitted schema, contract and predicate-source population, rooted in this row's source and toolchain identities. Required for completed population acquisition; publication/adoption and accepted declarations replace it with their population in the same committed world. It is not a ref: deleting a named declaration does not sweep this observation. Retracting its owning source row removes it; retained history/commits preserve the previous value."}
 :seon.source/digest]
```

The existing digest constraint is a 64-lowercase-hex string
(`seon.source.edn:29–30`). Explicit `:seon.db/identity false` overrides its
identity property. Native output must be **string, cardinality one, no
unique property, no component/ref, no noHistory**. Deletion behavior is
**value** under AGENTS §3, not cascade/sweep/refuse by a target ref. Absence
on a surviving completed source row refuses acquisition/final publication.
No stored nil and no second singleton identity. Require one source row;
multiple rows are an ambiguous-population refusal, not first-match selection.
Add the attribute to the existing source sealing/native-installation path
(`src/seon/cluster/source.clj:65,241`). Do not reinterpret the existing
`:seon.source/population` request schema as a new stored entity schema.

The research's `:seon.cluster/projection-digest` proposal is superseded by
PRD §§2.3/6: `resources/seon/schemas/seon.cluster.edn:1` gains **no stamp**.
The two resource paths in the research production table therefore become
one edited source resource and one explicit no-change boundary.

Use one canonical generation-input derivation in the existing schema/source
owners, with `seon.id/digest` (`src/seon/id.clj:41`) and
`schema/canonical-data-string` (`src/seon/schema.clj:714`). Its inputs are:

- the existing source input digest and toolchain identity;
- exact effective canonical schema forms and function contracts;
- admission/source provenance and program identities/source digests that
  determine predicate binding, including replacement/removal outcomes.

Canonical order must cover maps, sets and identity-keyed declarations;
exclude DB entity IDs, transaction numbers, timestamps, cache state,
compiled object addresses and the stamp itself. Preserve authored forms;
do not derive identity from normalized `m/form` output. That normalization
is a restart proof oracle only. Define the population digest as
`id/digest 64 [source-digest toolchain-digest canonical-population-inputs]`.
The inputs must be recoverable from the completed database without files.
The projection carries the same digest as ordinary in-memory data under
`:seon.source/population-digest`; no second persisted digest attribute.

An admitted candidate starts from the verified base generation and applies
its canonical declaration delta to those inputs, then uses **the same**
derivation for its complete effective population. Carry the expected base
digest separately in the existing transaction request and compare it at
the writer. The research's `[base, candidate declarations]` formula
establishes base-dependent candidate construction; its added-scalar probe
is not a second production hash of a partial overlay. Expanding the base
to its complete canonical inputs before hashing makes restart verification
possible from one completed population and avoids an unrecorded hash chain.
Identical effective inputs yield the same stamp; a stale base still refuses
even when a proposed output happens to have an already-known digest.

| Existing identity / authority at the inspected HEAD | Required relation |
|---|---|
| `src/seon/cluster/source.clj:103–146` snapshot; `:148` digest | Source digest denotes ordered source paths/file bytes. Keep that meaning and identity/upsert behavior. It is an input to the population digest; the two values are not generally equal. No change to the existing digest key's semantics. |
| `src/seon/test/cache.clj:83,92,152,188,215,230,238` | Reuse its graph/external input partition, per-file changes, pinned gitlinks, dependency/config inventory and test-input digest. Do not invent a second file inventory or copy the source digest into the test-input field. The latter remains test confidence evidence, not a replacement population stamp. |
| `src/seon/fn.clj:2254–2282,2327–2373` | Toolchain identity derives producer namespaces through recorded requires closure and the cache owner's pinned dependencies. Use the published toolchain fact in population inputs; a producer body/pin change invalidates acquisition/publication even if authored schemas are identical. No maintained producer roster. |
| `src/seon/cluster/source.clj:567,586,649–656,724,780–783` | Complete/upsert publication carries source, external-input and toolchain evidence. Attach the population stamp to the same accepted source row and verify the candidate before branch-head publication. Reused publication requires matching identities, not merely matching source file bytes. |
| `src/seon/cluster/source.clj:603–605,631–642,667–678`; `src/seon/test/cache.clj:401–459` | At this HEAD complete publication still branches from `:db` and copies evidence; cache preparation still launches its own prepare-base child. These are publication-lane retirement work, **not** the step-3 design. Consume its landed current-lineage owner and prove historical evidence references still resolve. Do not copy these old paths into stamp acquisition. |

Full and incremental publication of the same complete inputs must produce
the same stamp. A toolchain-only change changes the stamp. An exact fork
inherits it. An ordinary data or result-recording transaction preserves it.
Admission/source changes that affect interpretation change it even if the
printed schema forms are unchanged. A retained old database never selects
the current source row from another branch or a newer environment.

## Acquisition, temporal origin and transaction contract

**Ordinary:** `seon.db/carried-projection [database]` resolves
`schema-database` and its stamp with native indexed reads inside `seon.db`
(avoid recursively invoking a declaration-decoding read to discover its
own declarations). Match that digest to the already-carried immutable
projection. An optional explicit arity `[database projection]` validates a
supplied acquired value against that same stamp; it is not fallback to a
different generation. Context/request consumers call this seam for each
supplied world and refuse disagreement. DB-less rendering, relation-only
queries and explicit construction retain their genuinely supplied projection;
they must not fabricate a DB/stamp or consult a global selector.

**Explicit restoration:** `seon.db/acquire-projection [database]` is the
sole DB-to-acquired-DB entry. It first resolves the stamp. If the value
already carries that generation it returns it unchanged. Otherwise it
decodes durable rows once through `schema/projection-from-rows`, verifies
the complete canonical identity, materializes the generation and carries it.
Keep row decoding/admission provenance and compiled construction in schema;
keep stamp/origin/native DB access and attachment in db. No new require cycle.
The acquired source/base environment owns and reuses this returned value;
do not repeatedly materialize the same commit at each consumer and call
each repetition a new boot. Release materialized native DB resources at
their existing owning scope after users finish.

**Construction:** the zero-to-one publisher/test-base constructor supplies
its complete admitted projection explicitly to native schema, canonical
rows, indexing, config and the existing writer request. Its private build
may be incomplete until sealing; it is not exposed as an ordinary acquired
branch. No `if missing stamp then build` in public reads, and no ambient
handed projection authorizes arbitrary unstamped DBs. An empty DB's missing
stamp is legal only inside this explicit construction operation; ordinary
acquisition of the very same value refuses. Seal and verify before exposing
the branch. Do not expose a partial adoption with a stamp claiming facts
which its transaction has not installed.

**Errors:** return `:seon.schema/validation-refusal` built through the
existing diagnostic owner, with at/layer/operation, diagnostic member
`:seon.source/population-digest`, expected stamped/acquired population,
`:seon.schema/expected-value :seon.schema/projection` and
`:seon.schema/refused-value` carrying the actual acquisition input/evidence
(the raw refusal's required members, `resources/seon/schemas/seon.schema.edn:173–179`),
bounded offending/evidence data (branch/commit/basis and observed/expected
digests when available) and these distinct `:seon.error/diagnostic-cause`
values: `:seon.schema/missing-population-stamp`,
`:seon.schema/ambiguous-population-stamp`,
`:seon.schema/unacquired-population`,
`:seon.schema/population-digest-mismatch`, and
`:seon.schema/stale-population-base`. The missing-stamp operation is
`'seon.db/carried-projection` (or `'seon.db/acquire-projection` at restoration).
These are diagnostic values, not five new attributes or a revived kind
discriminator. Do not require the missing projection to build its refusal.
Refresh the output unions and every caller when removing nil; preserve
the landed raw-diagnostic versus stored-error boundary.

The stale-metadata counterexample is mandatory: Datahike `with` can return
a new stamp while `(meta db)` still contains the old projection by identity.
Read the origin stamp first. If no matching acquired projection was supplied,
return unacquired-population; never silently use the old one. History/as-of/
since use their origin's current schema rule. A since view containing no
stamp and a history view containing several old stamp datoms are not missing
or ambiguous when the origin has exactly one current stamp. To interpret an
older world, explicitly acquire that older commit; do not introduce temporal
schema-at-t selection in this slice.

Declaration order and publication are separate from JVM adoption completion.
The database transaction's accepted stamp says which durable program it holds.
The existing adoption-success commit marker is recorded only after loaded
definitions, config, instrumentation and SCI acquisition succeed
(`src/seon/cluster.clj:2472` and following adoption sequence). No promise of
atomic Var replacement. A failed later stage cannot claim successful adoption;
retry uses the already committed stamped world without rebuilding it at each
stage. Predicate identity covers declaration/source inputs; live named Vars
still change roots on reload as ruled by step 1.

## Per-file implementation instructions — research table applied to HEAD

The following table expands **every row** of the research's “Exact production
changes required by step 3.” The owner census below supplies all 125 old→HEAD
anchors, including propagation-only seams. Locations here are committed
`dc1efaf3c9f4c367e4e7112de34b723ffde1a150`, including the now-landed
step-1 commit; rebase the anchors after step 2 lands. A listed owner that already carries correctly
requires verification, not a gratuitous rewrite.

Rechecked at HEAD `cab89b9e763406be9698eada3c9b6d16d79a998c`: only the
working-edge document changed after that source commit; every cited source
anchor and measured span remains current. The final source census therefore
includes step 1's actual landing, not its earlier working-tree implementation.

| Owned path / current anchor | Exact instruction |
|---|---|
| `resources/seon/schemas/seon.source.edn:29–34` | Add the declaration above; extend source sealing/request/return contracts only where they actually carry it. Assert native non-identity one/string output. |
| `resources/seon/schemas/seon.cluster.edn:1` | Research-table correction: no cluster stamp or new cluster identity. Read-only unless a landed caller contract directly requires an explicit projection parameter. |
| `src/seon/db.clj:200,218,232,253,275,1115,1153,1178,1185,1252,3742,3962,4121` | Strengthen carrier/acquisition/origin selection; delete hybrid carry-derived-projection and operator-table discovery. All decoding, arity/write and final-report paths use the exact generation. Keep relation-only reads valid. Expected-base and final-candidate checks join the existing writer callback; no check removal. |
| `src/seon/schema.clj:1053,1064,1089,1096,1104,1113,1190,1851,2323,2449,2678,2710,2732,3074,3111,3151,3250,3256,3401` | Decode durable inputs once; separate recorded-population materialization from new admission. Retire the public running reconstruction façade. Reuse steps 1–2 construction/delta owners; remove ambient fallback selection after conversions. Preserve selector-local products, exact provenance, duplicates, predicate bindings and dependent closure. |
| `src/seon/cluster/source.clj:65,167,241,434,510,586,724` | Exact commit acquisition through acquire-projection once per owned materialization; same-source-row stamp for complete/upsert seals; preserve lineage and expected-head refusal. Result recording uses the acquired value and leaves generation unchanged. Consume the publication lane's completed owner. |
| `src/seon/cluster.clj:716,728,1245,1647,1684,1741,1990,2234,2472,2643,3617` | Bootstrap/population uses one explicit construction generation throughout indexing and storage derivation. Fork/restart acquires once. Complete/incremental adoption carries the admitted candidate into load/config/arming/SCI and records success at the existing end. Remove old-source re-admission prerequisite. |
| `src/seon/fn.clj:811,1174,1243,1600,2620,2756,2975,3141` | Indexing and reconciliation accept the construction/acquired generation explicitly. Delete carried→rows and request→packaged choices; reference flattening never learns schema from an empty DB. Preserve landed toolchain identity and analysis invalidation. |
| `src/seon/sci/eval.clj:618,775,810,1394,1662,2095,2199,2584` | Replace context-first selection with the DB stamp seam; verify request/context worlds. Base/context acquisition consumes one generation; install terminal accepted rows under it without row-by-row rebuild. Preserve source-match and superseded-row checks. |
| `src/seon/turn.clj:1207,1232,3444,4910` | Replace declarations-preceding row reload with the progressively admitted candidate; later declarations see prior changes and final validation sees the terminal population. Carry expected base into the writer and accepted candidate into SCI installation. |
| `src/seon/env.clj:80,105,139` | Existing environment/state carries generation and stamp; newer basis alone never approves mismatch. Keep process-lifetime core grammar as explicit pre-population construction. |
| `src/seon/cluster/agent.clj:540`; `src/seon/cluster.clj:291,622,3055,3235,3533` | Verify submitted/effect/fault/flow observations carry the DB's matching environment projection. Propagation only; no additional acquisition per message/proc. |
| `src/seon/flow.clj:1096`; `src/seon/effect.clj:188,430` | Carry the supplied acquired environment; no global lookup or generation chosen by thread. |
| `src/seon/config.clj:317,349,364,378,487,544,573` | Keep manifest/default construction explicit; running apply/effective consumes acquired DB generation and refuses mismatch. Do not recompile defaults on every wrapper/value. |
| `src/seon/instrument.clj:618,929,972` | Consume one generation/policy per arming operation; delete default-package selection when a running generation is required. Step 5 owns remaining retained-contract optimization within the existing wrapper. |
| `script/seon/fresh_operator.clj:1900,1916` | Generated effective/instrument forms must obtain a stamped DB with the connection's matching carrier. Raw dereference does not copy atom metadata. Coordinate the publication lane; no operator lifecycle redesign. |
| `src/seon/error.clj:1109,1155,1534,1643,1819,2072,2117` | Replace row-scanning declaration reads with the shared stamp seam. Missing generation remains a complete raw typed diagnostic; no empty schema table and no recursive refusal-render acquisition. |
| `src/seon/schedule.clj:510`; `src/seon/cluster/wake.clj:402` | Execution/wake setup receives the acquired generation once. Remove row reconstruction without changing schedules/listener semantics. |
| `src/seon/reconcile.cljc:305`; `src/seon/problems.clj:371` | Reconciliation/problem reads use the supplied stamped value; preserve missing-input evidence and existing final graph logic. |
| `src/seon/render.clj:89,114,341,1011` | Replace DB→request→context fallback with DB-authoritative verification; DB-less rendering requires explicit custody. Return typed absence and preserve HTML/AI render policy. |
| `src/seon/render/walk.clj:376,483,535,756` | Delete current-projection fallback; pass acquisition through the existing walk request. This is custody conversion, not a walker algorithm change. |
| `src/seon/render/web.clj:679,2187,2320,2468,3193,3397,3537` | Carry the selected page/request generation into all web/debug/data reads; no acquisition from files or rows during paint. |
| `src/seon/bootstrap.clj:710`; `src/seon/agent.clj:159` | Opening/settings consume the selected acquired generation. The explicit initial constructor remains distinct from running reads. |
| `src/seon/ai.clj:410,596,632`; `src/seon/shell/jvm.clj:78` | Feed forms/properties from the supplied environment generation to existing config/request consumers. No implicit declaration-population choice. |
| `src/seon/program.cljc:82,218`; `src/seon/print.cljc:339,923` | Replace implicit declaration selection with explicit generation; acquire fixed core printing grammar at its declared construction lifetime. Keep emitted authored forms and rendering bounds unchanged. |
| `src/seon/schema/admission.clj:375`; `src/seon/schema/edn.clj:412,565` | Admission names explicit base/candidate; packaged-forms remains publication/bootstrap input. No running fallback from missing base to full package build. |
| `src/seon/schema/datahike.clj:14,47,92,188,278,292,576,628` | These are pre-step-2 census anchors. Consume its landed compiled APIs and convert any surviving package convenience arities to explicit projection arguments; no raw-walker revival or mapping change. |
| `src/seon/test.clj:541,1475,1545,1997` | Test execution/preparation receives the acquired cluster value; no running row reconstruction. Preserve shared selection/recording authority. |
| `src/seon/test/runner.clj:1388,1683,1918,1989,2181,2573,4679,4687` | Worker boot restores once; commands/readers retain that generation. Arming, restoration and recorder agree with the stamped fixture/published DB. Do not launch a nested cold gate. |
| `src/seon/test/arm.clj:35,242`; `src/seon/test/fast.clj:52` | Canonical harness construction acquires one complete generation and passes it through the same arming path. No second test-only compiler. |
| `test/seon/test_support.clj:145,351,388,939,949,1064` | Seal the canonical base with a real population digest, reconnect through the sole loader, preserve fixture world on restore and pass its projection to program-row helpers. Distinct extras cannot collide through the historical zero source sentinel. |
| `src/seon/test/cache.clj:188,215,230,238,401` | Identity integration only: reuse landed publication inputs/toolchain/lineage; manifests reference the actual stamped program. No new cache key family or duplicate publisher. |
| `AGENTS.md:172–291,294–315,371–420`; bridge PRD §2.3; `.agents/skills/datahike/SKILL.md`, `repl/SKILL.md`, `clojure-testing/SKILL.md`, `data-oriented-clojure/SKILL.md` | Update only claims invalidated by this implementation, with verified landed file:line. Preserve historical research as dated evidence. Do not edit the working edge or issue index owned by the orchestrator. |

Runtime projections/requests whose declared shape changes also require their
existing schema owners: `resources/seon/schemas/seon.schema.edn`,
`seon.db.edn`, `seon.env.edn`, and
`seon.sci.eval.edn`. These are in-memory contracts, not authorization for
more stored stamp attributes. Refresh the registry/caller search before
adding keys; prefer the one existing source population digest key in every
carrier. Any newly discovered caller is part of the same retirement slice.

## Canonical regressions and named live proof

Own the existing regression files `test/seon/schema_test.clj`,
`test/seon/db_test.clj`, `test/seon/cluster/source_test.clj`,
`test/seon/cluster/boot_test.clj`, `test/seon/cluster/turn_test.clj`,
`test/seon/turn_test.clj`, `test/seon/sci/eval_test.clj`,
`test/seon/config_test.clj`, `test/seon/instrument_test.clj`,
`test/seon/fn_test.clj`, `test/seon/test_support_test.clj`,
`test/seon/test_cache_test.clj`, `test/seon/test_runner_test.clj`, and
`test/seon/render/call_test.clj`; extend the appropriate existing class regression
instead of making a parallel harness. Refresh ownership before editing.

Each research adoption obligation has a recurring canonical proof and, where
process/paint behavior matters, the named scratch-root proof
**step3-stamped-publication-adoption-restart**. The historical candidate-row OOM proves no
durable candidate guarantee; the real writer regression below is required.

| Research obligation | Required proof and observation |
|---|---|
| 1. Fresh publication | Canonical fixture publication regression starts the real population constructor with zero canonical rows and an explicit complete construction generation. Assert exact reference/component native attributes, expected schema/program identities and nonzero complete population; stamp matches constructed inputs. An ordinary acquisition of that pre-stamp value must refuse. A green return or count alone is insufficient. |
| 1. Paused publication / stricter admission | Canonical source regression retains an older admitted population lacking a subsequently required display label, supplies a new compliant candidate under the stricter rule, then publishes through the real current-lineage owner. Assert the old generation is not newly admitted or hybridized, the candidate is admitted and installed, and a noncompliant new candidate still refuses. Repeat in the named scratch-root proof with publication paused/explicitly invoked, not default's hook. |
| 2. Complete and incremental adoption | Canonical boot/source regression retains old DB P, publishes Q, adopts, and checks source inputs, population stamp, carried environment, wrapper contract behavior and real SCI behavior all identify Q. P still resolves its original schema. Compare full/incremental effective inputs and stamp exactly. Named scratch proof observes the changed page separately after successful adoption. |
| 2. Publication lineage / toolchain | Canonical source/cache regression records a test result, publishes one changed input on the same lineage, and resolves/reuses the original evidence/selection transaction. Cover body-only, interface, removal and toolchain producer/pin changes under the publication owner's existing equality oracle. A toolchain change forces complete analysis and a new population identity; no fresh database history or new publisher. |
| 3. Config/acquisition cost | Count the real row-loader, named constructor/provider, full-registry copies and policy acquisition through population, arming, accepted-row installation and first render. Ordinary reads/install loops make zero row-loader calls; each construction/restoration generation and arming policy is acquired once at its boundary. Repeated same-generation operations reuse retained roots. Record publication/config/application/commit time separately; no timing thresholds or raised bounds. |
| 4. Absence/mismatch | Canonical DB regression covers missing attribute/stamp, zero/multiple source rows, stamped-but-unacquired raw DB, changed-stamp with-result retaining old metadata, explicit request/context mismatch and verified supplied match. Assert the exact typed diagnostic cause and offending/expected evidence, no calls to loader or packaged input, and no accidental truthy-error projection. |
| 4. Temporal/origin and retention | Canonical fixture checks seon.db/db, raw Datahike db, with, history, as-of, since at the origin basis, exact fork and commit-as-db. Stamp is resolved at origin; since can contain zero stamp datoms and history multiple past stamps. Held P remains P when state advances to Q; a raw materialization needs explicit acquisition. Release materialized DB resources. |
| 5. Restart / sovereign branch | Canonical file-backed boot regression plus named scratch proof: a real process replacement starts with no compiled metadata, restores exactly the stamped durable inputs once, compares exact canonical forms and normalized compiled-form digest before/after, then performs repeated reads with acquisition count unchanged. Change packaged input separately; an older sovereign branch restores its own declarations or returns a precise incompatible-executable-binding refusal, never adopts the package. |
| 5. Predicate binding | Canonical real-SCI/JVM regression changes a named predicate's program source/admission and proves population invalidation; separately replace its live Var root and observe preserved reload behavior. Do not infer executable equality merely from normalized form bytes or a stable symbol. |
| 6. Sequential candidate ordering | Canonical turn/writer regression adds a schema then defines a dependent contract/entity in the same transaction; also replace a leaf and later use the replacement. Assert final facts/stamp and SCI installation reflect the final world; dependents recompile and unaffected roots retain identity. Retain old-world roots for comparison. No mocked transaction or scalar-only composite proof. |
| 6. Writer authority and atomic refusal | Canonical writer regression queues/adjoins a candidate against P, advances the actual base, then attempts settlement: stale base refuses in the writer. Invalid later declaration, deletion with surviving referrer and final invariant failure leave rows/stamp/environment unchanged. Valid same-transaction repairs succeed. Repeated redefinition installs only terminal rows. Use bounded events, not sleeps. |
| 6. Durable candidate restart | Canonical file-backed declaration regression commits a genuine schema/function replacement through Seon's writer, closes the connection/process, restores that candidate from durable complete facts, and verifies the same stamp and dependent behavior. This specifically replaces the research's failed 6-GiB persisted-candidate probe. |
| 7. Armed gates and preservation | Run affected named fast suites with the canonical fixture and real instrumentation; retain existing deletion/component/tuple/relational checks unchanged. Orchestrator supplies cold --paths and --platform after the batch. Record unavailable proofs explicitly; research counts (3,228 forms / 1,368 contracts) are not roster constants. |

For **step3-stamped-publication-adoption-restart**, use only an owned root,
for example `tmp/bridge-step3-root`, cluster `bridge-step3`. Publish and fork
through `bin/seon --root …`; seed the canonical Juniper fixture when the
page/adoption proof requires it. Use root/cluster-qualified MCP, reporting
tool unavailability immediately; never hand-roll a prepl sender. Save bounded
identity/counter/equality evidence before down; verify old `(pid,start-instant)`
is gone and flock released; start a fresh process on that same root; collect
the after evidence and named page observation. Also exercise exact commit
materialization and a sovereign older branch while newer packaged input exists.
Down the owned root and remove it after exit verification. Commit reusable
proof code at the existing test owner; preserve measured outputs/commands in
the landing note, not solely in disposable tmp files.

Production load command, expanded to actual changed owners after conversion:

```bash
clojure -M -e "(require 'seon.schema 'seon.schema.edn 'seon.schema.admission 'seon.schema.datahike 'seon.db 'seon.cluster.source 'seon.cluster 'seon.fn 'seon.sci.eval 'seon.turn 'seon.env 'seon.cluster.agent 'seon.flow 'seon.effect 'seon.config 'seon.instrument 'seon.error 'seon.schedule 'seon.cluster.wake 'seon.reconcile 'seon.problems 'seon.render 'seon.render.walk 'seon.render.web 'seon.bootstrap 'seon.agent 'seon.ai 'seon.shell.jvm 'seon.program 'seon.print 'seon.test 'seon.test.runner 'seon.test.arm 'seon.test.fast 'seon.test.cache)"
```

Run serial fast requests with **all** owned changed paths in each overlay:

```bash
bin/test-fast --paths <all owned changed paths> -- seon.schema-test seon.db-test seon.cluster.source-test seon.cluster.boot-test seon.cluster.turn-test seon.turn-test seon.sci.eval-test
bin/test-fast --paths <all owned changed paths> -- seon.config-test seon.instrument-test seon.fn-test seon.test-support-test seon.test-cache-test seon.test-runner-test seon.render.call-test
```

Add graph-selected affected callers. The platform-marked child-gate runner
integration namespace remains orchestrator-only. These commands are future
implementation obligations, not design-lane executions.

## Reset batch and held-path coordination

**RESET NEEDED: `:seon.source/population-digest` added; no migration.** The
working edge's 2026-09-21 ~02:00 UTC charter at
`docs/prds/steward-platform/plan/unsettled.md:5204–5207` batches this with
1a and 1d after steps 1–2 and sweeps, from a clean tree, followed by platform
and Juniper. The earlier ~19:35 reset does not establish that a future stamp
exists. Refresh the actual pending batch at implementation landing.

- 1a's consolidated inventory
  ([error-family note](../research/error-family-1a-2026-09-19.md):768–781):
  retire `:seon.error/unclassified`, `:seon.error/refusal`,
  `:seon.instrument/contract-violated`, `:seon.instrument/registration-failed`,
  `:seon.error/kind`, `:seon.error/class`; existing root layer/operation become
  required. Later raw observation keys are transient, not extra reset attrs.
- 1d's “three” denotes three change groups, **four attribute names** in its
  [final reset accounting](../research/config-plan-family-1d-2026-09-20.md):153–163:
  `:my.plan.item/needs` many refs → identity strings;
  `:seon.ai.attempt/model` retired; false overlays removed under both
  `:seon.config.agent/show-all-settings` and
  `:seon.config.ai/retain-reasoning`. Do not omit one Boolean or invent a
  third scalar name to match the shorthand.

Step 1 holds schema/caller/test files; step 2 then holds schema/db/fn/render
navigation. Publication holds `src/seon/cluster/source.clj`, publication
regions of `cluster.clj`, `fn.clj`, `src/seon/test/cache.clj`,
`script/seon/fresh_operator.clj`, source resources and tests. 1a may hold
error/schema/SCI/tests; 1d and subsequent sweeps may hold config/turn/render.
These are coordination intersections, not permanent exclusions. Check current
status and landing notes. Step 1 landed during this design at `dc1efaf3c`; its paths must still be
checked for new holders. The step-3 launch waits for the publication owner
to supply the ruled lineage/toolchain behavior wherever these paths overlap;
do not absorb its remaining publisher/lifecycle work.

## Dependency ledger — actual carrier and commit semantics

Datahike pin inspected: `e11845bac78e1241bca0766ddc07d978bd63d74a`.

Malli pin inspected: `606083c5c5b388e84d169c7080af33ed3ec242ae`.

Read these seams before implementation; none calls for a fork change.

| Mechanism | File:line and consequence |
|---|---|
| Ordinary record metadata | `reference-code/datahike/src/datahike/db.cljc:96–108,198–230,307`: DB is a record; transient/persistent index updates do not intentionally clear its Clojure metadata. The record's field named `meta` is separate. Metadata survival does not prove generation correctness. |
| Temporal origin | `reference-code/datahike/src/datahike/db.cljc:501,544,567,609,633,675`: History/AsOf/Since retain origin-db and expose it through IHistory. `src/seon/db.clj:1115` already follows this chain. Preserve that authority. |
| Connection metadata/dereference | `reference-code/datahike/src/datahike/connector.cljc:38–46,82–104`: IMeta delegates to wrapped atom metadata; streaming dereference returns its DB, non-streaming dereference reconstructs stored data. conn-from-db creates listener metadata. Neither path promises propagation of Seon's acquired snapshot. |
| Exact commit and branch materialization | `reference-code/datahike/src/datahike/versioning.cljc:469–490,491–497,499–510`: commit-as-db/branch-as-db call stored->db; attached query-cache context is not Seon's compiled projection. release-materialized-db releases owned native resources. `src/seon/cluster/source.clj:167` is the first-party exact-commit owner. |
| Durable DB representation | `reference-code/datahike/src/datahike/writing.cljc:161–179,231–280`: persist the field :meta and index roots; reconstruct from empty-db and stored fields. Runtime Clojure metadata/compiled closures do not survive restart. |
| Branch head ordering | `reference-code/datahike/src/datahike/writing.cljc:480–548`: referenced nodes/schema and immutable commit precede mutable branch head. This is persistence ordering, not universal multi-key atomicity. Stamp datoms belong inside the committed DB. |
| Serial writer authority | `reference-code/datahike/src/datahike/writer.cljc:130–218`: operation runs on writer-local old; accepted report advances to db-after; a refused transaction recurs with old. No pre-queue base check can substitute for this seam. |
| Committed reports | `reference-code/datahike/src/datahike/writer.cljc:234–273,394–427`: writer can batch; reports receive committed batch db-after. Promise settlement/listener notification does not make each report's after-value a per-transaction final-validator boundary. Final candidate checking belongs before commit. Do not add a stamp listener. |
| Transaction function/final validator | `reference-code/datahike/src/datahike/db/transaction.cljc:844–855,1153–1154,1206–1216,1257–1276`: transaction functions see mid-transaction DB and may retry; final callback sees attempted/effective data and final DB; non-nil refusal aborts. Reuse it for base/candidate/stamp agreement. |
| Retained compiled scopes | `reference-code/malli/src/malli/core.cljc:1952–1977,2550–2573`; `reference-code/malli/src/malli/registry.cljc:17–22,54–59,97–104`: compiled objects keep captured scopes; composite lookup precedence does not rebuild them. Consume step 1's dependent closure and mr/schema lookup. |

## Measured deletion budget and estimate

Physical inclusive owner spans at the committed census HEAD, including
contracts/comments/blank separators; no JVM measurement was made here.
Do not count retained restart decoding as a deletion or raw walker work
already owed by step 2 as step-3 savings.

| Owner span at HEAD | LOC | Disposition |
|---|---:|---|
| `src/seon/db.clj:253–274` carry-derived-projection | 22 | Delete packaged+persisted hybrid construction entirely. |
| `src/seon/schema.clj:2449–2643` projection-from-rows | 195 | Retain parsing/duplicate/provenance behavior in the explicit loader; remove ordinary call reach and separate restoration from new admission. Not 195 deleted LOC. |
| `src/seon/schema.clj:2644–2677` refuse-projection-source | 34 | Preserve equivalent typed invalid-input evidence at acquisition; consolidate only if the shared refusal covers it. |
| `src/seon/schema.clj:2678–2709,2710–2731` derive/projection-from-database | 54 | Retire running reconstruction API; retain necessary row queries inside the single explicit loader. |
| **Current rebuild path surface** | **305** | 22 + 195 + 34 + 54; mixed deletion/movement, not a 305-line removal promise. |
| `src/seon/render.clj:89–113` request-projection | 25 | First fallback chain: DB → request → context. Replace selection precedence with stamp agreement; retain DB-less explicit rendering. |
| `src/seon/sci/eval.clj:618–626` evaluation-projection | 9 | Second fallback chain: context → request → DB. Replace with the same acquisition authority. |
| **Two opposing fallback owners** | **34** | Full owner bodies measured; their actual or expressions occupy 6 + 5 = 11 lines. No claim all contract/doc lines disappear. |
| `src/seon/db.clj:200–215,275–289` | 31 | Remove operator lookup and implicit handed construction fallback; preserve connection metadata acquisition/error normalization. |
| `src/seon/schema.clj:1053–1063,1064–1088,3256–3263,3401–3408` | 52 | Additional ambient selection surfaces; preserve explicit candidate/registration behavior, remove package/runtime ambiguity. |

The nonoverlapping measured review surface is **422 LOC**; retained row
decoding dominates it. Mechanical caller simplification, SCI per-row rebuilds
and turn candidate propagation are additional, measured at landing. PRD §4's
**300–550 gross deletions / 180–350 additions, 3–5 lane-days / 12–20 files**
was provisional. The research already spans 32 production files; supplemental
consumers, operator/cache, resources, harness and regressions exceed that file
estimate. Budget **5–7 lane-days** for custody conversion, identity/restoration,
writer/candidate proof and restart/adoption, plus orchestrator queues. Keep the
PRD LOC ranges as planning ranges, not correctness targets; report actual
numstat and retained/moved behavior. Do not delete final checks to meet them.

## Dated 125-owner census — research anchors re-verified against HEAD

The following is a point-in-time conversion record, not a maintained runtime
roster. The research's **125 owner spans / 32 files / 180 lexical occurrences**
and P/R/C classification describe its snapshot. This table resolves those
same named owners at the committed census HEAD; it does not claim the moved
tree has exactly 180 occurrences. P = construction/acquisition, R = running
consumer/propagation, C = candidate; ★ preserves the research's adoption mark.
**121 owners moved; four retained their research line.** All changed line
numbers are shown explicitly. Re-run the full lexical and
caller search after steps 1–2, including delayed resolution and Var quotes.

```bash
rg -n 'projection-from-database|projection-from-rows|carry-derived-projection|carried-projection|declaration-projection|call-with-projection-state|packaged-forms' src test script
rg -n 'declaration-population|handed-projection|current-projection|registered-schemas|evaluation-projection|request-projection|advance-projection!' src test script
rg -n 'population-digest|projection-digest|toolchain-digest|source/digest' resources/seon/schemas src/seon/cluster/source.clj src/seon/test/cache.clj
```

Classify construction/candidate consumers separately before removing an API.
After conversion, prove ordinary read reach cannot invoke the row loader or
packaged constructor. A zero grep count obtained by renaming that mechanism
does not satisfy the recurring count/behavior regressions above.

| File / owner | Research → HEAD line | Class |
|---|---|---|
| `src/seon/agent.clj` `render-settings-html` | 179 → 159 | R |
| `src/seon/bootstrap.clj` `next-entry` | 718 → 710 | R |
| `src/seon/cluster/agent.clj` `submit-source!` | 555 → 540 | R |
| `src/seon/cluster/source.clj` `database` | 174 → 167 | P ★ |
| `src/seon/cluster/source.clj` `record-results-at-head!` | 425 → 434 | R |
| `src/seon/cluster/source.clj` `record-results!` | 465 → 510 | R |
| `src/seon/cluster/wake.clj` `wake-matchers` | 416 → 402 | R |
| `src/seon/cluster.clj` `mcp-effective` | 299 → 291 | R |
| `src/seon/cluster.clj` `mcp-runtime-observation` | 604 → 622 | R |
| `src/seon/cluster.clj` `require-candidate-value` | 681 → 716 | P |
| `src/seon/cluster.clj` `resolve-bootstrap` | 709 → 728 | P |
| `src/seon/cluster.clj` `activation-requirements` | 1213 → 1245 | P ★ |
| `src/seon/cluster.clj` `require-admissible-branch!` | 1638 → 1647 | P ★ |
| `src/seon/cluster.clj` `accrete-schema-population!` | 1670 → 1684 | P ★ |
| `src/seon/cluster.clj` `populate-source!` | 1730 → 1741 | P ★ |
| `src/seon/cluster.clj` `source-base!` | 1974 → 1990 | P ★ |
| `src/seon/cluster.clj` `incremental-source-refresh!` | 2227 → 2234 | P ★ |
| `src/seon/cluster.clj` `development-source-refresh!` | 2462 → 2472 | P ★ |
| `src/seon/cluster.clj` `refresh-source!` | 2647 → 2643 | P ★ |
| `src/seon/cluster.clj` `commit-fault!` | 3049 → 3055 | R |
| `src/seon/cluster.clj` `projection-executor` | 3212 → 3235 | R |
| `src/seon/cluster.clj` `stand-cluster-runtime!` | 3500 → 3533 | R |
| `src/seon/cluster.clj` `stand-boot-layers!` | 3634 → 3617 | P |
| `src/seon/config.clj` `default-population` | 324 → 317 | P |
| `src/seon/config.clj` `default-decisions` | 360 → 349 | P |
| `src/seon/config.clj` `read-manifest` | 370 → 364 | P |
| `src/seon/config.clj` `compile-settings` | 381 → 378 | P |
| `src/seon/config.clj` `apply-compiled!` | 496 → 487 | R ★ |
| `src/seon/config.clj` `apply!` | 570 → 544 | R |
| `src/seon/config.clj` `effective` | 581 → 573 | R ★ |
| `src/seon/db.clj` `carry-derived-projection` | 253 → 253 (unchanged) | P ★ |
| `src/seon/db.clj` `carried-projection` | 1168 → 1178 | R ★ |
| `src/seon/db.clj` `read-declarations` | 1189 → 1185 | R ★ |
| `src/seon/db.clj` `ask-declarations` | 1255 → 1252 | R |
| `src/seon/db.clj` `arity-mismatches` | 3743 → 3742 | R |
| `src/seon/db.clj` `write-report-validator` | 3958 → 3962 | R ★ |
| `src/seon/db.clj` `transact-call` | 4124 → 4121 | R ★ |
| `src/seon/effect.clj` `accepts-request?` | 190 → 188 | R |
| `src/seon/effect.clj` `with-request-context` | 459 → 430 | R |
| `src/seon/env.clj` `declared-members` | 151 → 139 | R |
| `src/seon/error.clj` `reader-correction` | 1075 → 1109 | R |
| `src/seon/error.clj` `refusal-data` | 1153 → 1155 | R |
| `src/seon/error.clj` `commit-call` | 1507 → 1534 | R |
| `src/seon/error.clj` `recording` | 1595 → 1643 | R |
| `src/seon/error.clj` `rendered-error-value` | 1782 → 1819 | R |
| `src/seon/error.clj` `faults-form` | 2005 → 2072 | R |
| `src/seon/error.clj` `render-faults-html` | 2048 → 2117 | R |
| `src/seon/flow.clj` `projection-executor` | 1106 → 1096 | R |
| `src/seon/fn.clj` `runtime-analysis-batch` | 826 → 811 | R ★ |
| `src/seon/fn.clj` `source-rows` | 1195 → 1174 | R ★ |
| `src/seon/fn.clj` `declaration-forms` | 1249 → 1243 | P ★ |
| `src/seon/fn.clj` `contract-findings` | 1586 → 1600 | R ★ |
| `src/seon/fn.clj` `backfill-contract-facts!` | 2450 → 2620 | P |
| `src/seon/fn.clj` `desired-rows` | 2536 → 2756 | P ★ |
| `src/seon/fn.clj` `reconcile-tx-in` | 2755 → 2975 | P ★ |
| `src/seon/fn.clj` `index!` | 2982 → 3141 | P ★ |
| `src/seon/instrument.clj` `apply!` | 997 → 929 | R ★ |
| `src/seon/print.cljc` `shipped-option-defaults` | 341 → 339 | R |
| `src/seon/print.cljc` `node-face-validator*` | 932 → 923 | R |
| `src/seon/problems.clj` `problems` | 385 → 371 | R |
| `src/seon/program.cljc` `authored-shapes` | 241 → 218 | R |
| `src/seon/reconcile.cljc` `plan` | 320 → 305 | R |
| `src/seon/render/walk.clj` `root-pull-plan` | 382 → 376 | R |
| `src/seon/render/walk.clj` `acquired-tree` | 490 → 483 | R |
| `src/seon/render/walk.clj` `root-acquisition` | 549 → 535 | R |
| `src/seon/render/walk.clj` `neighborhood` | 766 → 756 | R |
| `src/seon/render/web.clj` `declared-entity-units` | 683 → 679 | R |
| `src/seon/render/web.clj` `current-page` | 2197 → 2187 | R |
| `src/seon/render/web.clj` `render-pass` | 2333 → 2320 | R |
| `src/seon/render/web.clj` `derive-context!` | 2474 → 2468 | R |
| `src/seon/render/web.clj` `debug-response` | 3264 → 3193 | R |
| `src/seon/render/web.clj` `data-response` | 3459 → 3397 | R |
| `src/seon/render/web.clj` `handler` | 3562 → 3537 | R |
| `src/seon/render.clj` `request-projection` | 110 → 89 | R |
| `src/seon/render.clj` `request-profile` | 121 → 114 | R |
| `src/seon/render.clj` `schema-producers` | 343 → 341 | R |
| `src/seon/render.clj` `invoke-selected` | 1035 → 1011 | R |
| `src/seon/schedule.clj` `execution-context` | 512 → 510 | R |
| `src/seon/schema/admission.clj` `admit` | 395 → 375 | C |
| `src/seon/schema/datahike.clj` `packaged-forms` | 14 → 14 (unchanged) | R |
| `src/seon/schema/datahike.clj` `resolve-malli-form` | 53 → 47 | R |
| `src/seon/schema/datahike.clj` `resolve-datahike-form` | 98 → 92 | R |
| `src/seon/schema/datahike.clj` `form->datahike-value-type` | 193 → 188 | R |
| `src/seon/schema/datahike.clj` `malli->datahike-attr` | 283 → 278 | R |
| `src/seon/schema/datahike.clj` `malli->datahike-schema` | 297 → 292 | R |
| `src/seon/schema/datahike.clj` `encode-transaction` | 589 → 576 | R |
| `src/seon/schema/datahike.clj` `decode-attribute-value` | 635 → 628 | R |
| `src/seon/schema/edn.clj` `packaged-forms` | 412 → 412 (unchanged) | P |
| `src/seon/schema.clj` `schema-edn-packaged-forms` | 41 → 40 | P |
| `src/seon/schema.clj` `*packaged-forms*` | 821 → 857 | R |
| `src/seon/schema.clj` `packaged-forms` | 1013 → 1049 | P |
| `src/seon/schema.clj` `candidate-forms` | 1020 → 1053 | C |
| `src/seon/schema.clj` `declaration-population` | 1042 → 1064 | C |
| `src/seon/schema.clj` `call-with-forms` | 1057 → 1089 | C ★ |
| `src/seon/schema.clj` `call-with-projection` | 1060 → 1096 | R ★ |
| `src/seon/schema.clj` `call-with-projection-state` | 1068 → 1104 | R ★ |
| `src/seon/schema.clj` `declaration-projection` | 1148 → 1190 | C ★ |
| `src/seon/schema.clj` `projection-from-database` | 2675 → 2710 | C ★ |
| `src/seon/sci/eval.clj` `evaluation-projection` | 623 → 618 | R |
| `src/seon/sci/eval.clj` `committed-row?` | 792 → 775 | C |
| `src/seon/sci/eval.clj` `install-row!` | 822 → 810 | C ★ |
| `src/seon/sci/eval.clj` `documentation-contract` | 1397 → 1394 | R |
| `src/seon/sci/eval.clj` `acquire-program!` | 1676 → 1662 | C ★ |
| `src/seon/sci/eval.clj` `base-ctx` | 2109 → 2095 | C ★ |
| `src/seon/sci/eval.clj` `fork-cluster-ctx` | 2220 → 2199 | C ★ |
| `src/seon/sci/eval.clj` `evaluate` | 2636 → 2584 | R |
| `src/seon/test/arm.clj` `packaged-test-projection` | 38 → 35 | P |
| `src/seon/test/arm.clj` `initialize-contracts!` | 249 → 242 | P |
| `src/seon/test/fast.clj` `-main` | 35 → 52 | P |
| `src/seon/test/runner.clj` `restore-live-cluster-schema!` | 1445 → 1388 | P |
| `src/seon/test/runner.clj` `packaged-test-projection` | 1686 → 1683 | P |
| `src/seon/test/runner.clj` `serve-worker-commands!` | 1935 → 1918 | R |
| `src/seon/test/runner.clj` `worker-command-loop!` | 1996 → 1989 | P |
| `src/seon/test/runner.clj` `reach-cache` | 2183 → 2181 | R |
| `src/seon/test/runner.clj` `complete-members` | 2591 → 2573 | R |
| `src/seon/test/runner.clj` `coordinator-main!` | 4494 → 4679 | P |
| `src/seon/test/runner.clj` `-main` | 4532 → 4687 | P |
| `src/seon/test.clj` `run` | 461 → 541 | R |
| `src/seon/test.clj` `resolve-test` | 1453 → 1475 | R |
| `src/seon/test.clj` `prepare-tests!` | 1482 → 1545 | R |
| `src/seon/test.clj` `check-request` | 1936 → 1997 | R |
| `src/seon/turn.clj` `declaration-projection` | 1207 → 1207 (unchanged) | C |
| `src/seon/turn.clj` `row-tx` | 1244 → 1232 | C |
| `src/seon/turn.clj` `evaluation-terminal-data` | 3473 → 3444 | R |
| `src/seon/turn.clj` `turn` | 4980 → 4910 | R |

## Design-lane verification boundary

Read the bridge PRD, step-2 spec, carried-projection research and publication
dissolution spec **end to end**; read the named working-edge block, AGENTS
sections and lane rules, roadmap entry and Datahike/data-oriented/data-modeling
skills. Read the dependency seams above and committed source owners; no
runtime inference is presented as new probe evidence. Observations use the
requested 2026-09-21 work-item date within the 2026-09-20 session.

Only this document is authored. No source/test/resource edits, JVM launches,
MCP evaluations, cluster operations, test gates or worktrees. The task's
specific no-worktree rule governs over its generic shared-tree fallback.
At the read-only census, a shell edit hook reported foreign syntax errors in
`script/seon/fresh_operator.clj:2112–2140,2801–2820` (mismatched delimiters and
an unsupported binding form). Those bytes were not edited by this lane and
were not loaded; committed-source inspection continued. This is a foreign
verification boundary, not an attribution of a runtime failure.

Documentation path/line, census, link and whitespace checks are the design
proof. All canonical regressions, source loading, batched reset, cold/platform
and live restart/adoption/paint remain implementation/orchestrator obligations.
Commit only this document path and stop.
