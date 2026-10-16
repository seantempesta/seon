---
title: Schema lifecycle — Astra independent plan
type: research
status: proposed; independent plan, not implementation evidence
date: 2026-10-16
---

# Schema lifecycle: one valid database value per commit

The existing writer derives the final registry from a transaction's declaration entities; schema publication still performs several writes and can delete incompatible data automatically. Keep the writer and immutable database model: compose declarations, explicit data conversion and program changes into one transaction, and validate its final value.

This is the independent schema-plan-astra plan. No Opus plan was read. Production code and specifications are unchanged by this document. Phase status: P1–P6 proposed, none implemented. The owner direction of 2026-10-16 supersedes the 2026-09-23 automatic data-dropping policy; the no-reset requirement remains.

## 1. Invariant and scope

For each committed database value D, let P(D) be its program declarations, R(D) the Malli registry derived from those declarations, and S(D) the installed Datahike storage schema. Every **current** Seon datom has an attribute declared by R(D), with storage and decoding consistent with S(D); it participates in a logical attribute value satisfying that declaration. Cardinality-many members and vector cells are validated in their reconstructed collection, not mistaken for standalone collections. Every complete owning value satisfies its entity and component schemas. Surviving declaration references resolve in P(D). Datahike's implicit attributes retain Datahike's contracts.

The constraint is on the **final database**, not on the proposed schema against the old data. A transaction may deliberately retract or convert data, repair callers and required refs, and remove or change their declarations together. Removing a schema with any remaining data or declaration consumer fails the same write. A declaration with no current data and no surviving consumers may be removed. Keeping the physical attribute while deleting its Malli declaration does not satisfy the invariant.

This applies equally to an agent branch, a publication branch, the merge branch and main. Branch freedom means an agent may diverge from main, not commit invalid local data. Amend the merge spec's contrary statement that branch changes are never refused. The same final-owning-value rule continues to judge required refs, optional sweeps, components, child-only edits and repaired declaration references; no separate exception for schema changes.

Historical datoms do not prevent retirement. A retained **commit** is interpreted with that commit's program and storage declarations. Do not claim that a current `history` or `as-of` view reconstructs a historical schema automatically. In particular, physical type reuse must not erase historical values to make index comparisons work. New meanings and incompatible physical types use new attribute names; the old name can disappear from the current program once its data and consumers have moved. This plan does not permit automatic purge or destructive reuse of an old name.

The invariant is inductive: a valid starting value plus a complete final-write check yields valid subsequent values. It does not retroactively certify existing stores or already-written history. Before calling the rollout complete, validate a captured live-store basis once, report existing violations, and repair them explicitly. A whole-store audit belongs to that rollout task, never to each ordinary write.

## 2. Library model and observed implementation

Evidence starting point: [the dig](../digs/schema-lifecycle.md), committed at 6fa21abd7. Source inspection began at that HEAD. Default runs the committed archive 4f15bf3d77aa98c83380c88efa693f072215c6bd. The dependency pins inspected were Datahike ee3f8ec817e5310b3a0ea5adb8904214183568c0 and Malli dd13ff81139159778edb0a67b491eccc0baa991d.

**Datomic.** Schema changes are transactions, and incompatible current assertions prevent the change. Datomic permits conditional cardinality and uniqueness changes; it does not permit changing value type. Its temporal views use the schema associated with their current basis, so temporal query is not schema rollback. These verified details correct the dig's memory-only summary. Seon should take the transactional consistency model and use new attributes for unsupported physical changes. Datahike's accepted operations, not Datomic's complete operation set, determine what this implementation can express. [Datomic schema changes](https://docs.datomic.com/schema/schema-change.html).

**Git.** A merge holds base, ours and theirs; conflicts leave the target unmoved and retain the conflicting versions for resolution. Reuse Seon's installed unmerged entries for schema/data conflicts. A type-valid merge still needs tests, just as a clean textual merge does. [Git merge](https://git-scm.com/docs/git-merge#_true_merge).

| Seam | Guarantee, inputs and when it runs | Cost / qualification |
|---|---|---|
| `malli.core/validator`, `malli.core/explain` | Compile against the particular registry; validate logical values; explain a failure at its producer | Validator cached on the compiled schema; reuse it outside the data loop |
| `datahike.db.transaction/validate-report` | Fork seam: final database, effective and attempted datoms; a non-nil result aborts the transaction before publication | Already used by Seon; not claimed as an upstream API |
| `seon.db/validate-write-report`, `seon.db/write-report-error` | Derive the final registry and judge the write, including transaction-function output | Presently checks attempted data and reached owning values; a schema-only change does not reach all existing holders |
| `seon.db/removed-definition-error` | Surviving consumers of removed definitions are judged on the final program | Extend the same writer judgment to current data; do not add an entry-only check |
| `datahike.db.transaction/reject-schema-removal-with-current-data` | Current AEVT existence check when removing a physical attribute | One seek; no history scan; Seon's automatic retractions currently defeat its intent |
| `seon.schema/dependent-schema-keys` | Changed keys plus reverse-transitive dependencies in an immutable registry | Use both before and after registries; proportional to reached graph |
| `seon.turn.batch/attribute-change-tx` → `seon.cluster/attribute-change-tx` | Derive storage operations for reached attributes | Existing composition point; standalone registered attributes currently fall through selection on change/removal |
| `seon.db/with` | Speculate using the checked writer's encoding and final validator | Suitable for previews; not durable publication proof |
| `datahike.writing/transact!`, `datahike.writing/merge-writer!` | Serialized writer accepts `:datahike/expected-basis-t`; checks its actual current value and applies one transaction | Installed fork seam; covers queued, not-yet-published writes as well as published commits |
| `datahike.versioning/merge!` | Applies supplied transaction data with supplied merge parents | Does not derive semantic merge or data conversion |
| `datahike.norm.norm/transact-norms` | Each named norm gets a separate transaction, ordered by the supplied list | Not a multi-norm atomic operation; Seon has no callers |
| `seon.db/vector-migration-tx` | Returns conversion transaction data composed into the writer's transaction | Atomic conversion precedent, not a general norm runner |

The upstream norm implementation is an example of named transaction data, not the runner to call. The final-report hook and expected-basis input above are maintained-fork facilities. Native Datahike transaction processing and Malli validation supply the mechanisms; no new writer, registry, scheduler or migration store is needed.

### Live probes

Both status surfaces were available. Default initially reported `:faulted`, 29 open faults, 400/401 errored receipts, 4 agent-error signatures, no missing readiness layer and no reported regression. This is a degraded baseline, not a health pass. Heap used was 1.41–1.79 GB of 4.29 GB; reported live heap 1.15 GB. No default stop, restart, reset or source load occurred.

Read-only JVM probe, explicit root/default:

```clojure
(let [db @(seon.cluster.boot/connection "default")
      a :seon.schema.admission/source
      start (System/nanoTime)
      datum (first (datahike.api/datoms db :aevt a))]
  {:attribute a :present? (boolean datum)
   :seek-ms (/ (- (System/nanoTime) start) 1e6)})
```

Result: present, **0.016959 ms**; eval 2 ms, request 42.0 ms, 8.7 MB allocated. The loaded writer, invalid-value retraction, registration check and unmerged Vars all resolved. A complete raw-value validation of the same attribute, compiling `malli.core/validator` from the carried registry and reducing its AEVT datoms, found **17,740 values, 0 invalid, 1.767833 ms**; eval 3 ms, request 43.1 ms, 13.4 MB allocated. These are single observations, not comparative performance proofs.

Scratch SCI agent `schema-plan-astra`, branch `:agent-7800036da64e`, began with `(seon.context/current-state)`. The following requests used the ordinary entrance; each registration completed before the next request. Writes targeted only the scratch agent's existing entity.

```clojure
(seon.schema/register! :schema-plan-astra/value :int)
(seon.db/transact!
 [{:db/id [:seon.agent/id "schema-plan-astra"] :schema-plan-astra/value -1}])
(seon.schema/register! :schema-plan-astra/value [:int {:min 0}])
(seon.db/pull [:seon.agent/id :schema-plan-astra/value]
              [:seon.agent/id "schema-plan-astra"])
```

The check reported one checked and one failing value, yet the subsequent pull still held **-1**. Then writing 7 and calling `(seon.schema/unregister! :schema-plan-astra/value)` left **no schema entity, an installed storage attribute, and value 7**. Evidence handles: result/e4e3b580bcbc8, result/e406e4a3efcb5, result/e6e6cc64bf4b0. Source explanation: `seon.turn.batch/schema-tx` installs standalone registrations through its `extra` declarations, while change selection uses entity-listed attributes. The final writer currently admits the resulting schema/data mismatch. This is an observed producer defect to cover in P2, not evidence that data dropping never runs.

To reach that path on the same branch:

```clojure
(seon.schema/register! :schema-plan-astra/value :int)
(seon.schema/register! :schema-plan-astra/row
 [:map {:seon.db/attributes true}
  [:schema-plan-astra/value {:optional true} :schema-plan-astra/value]])
(seon.schema/register! :schema-plan-astra/value [:int {:min 10}])
(seon.db/pull [:seon.agent/id :schema-plan-astra/value]
              [:seon.agent/id "schema-plan-astra"])
```

Now **7 disappeared**, and the stored schema form was `[:int {:min 10}]` (result/ee98145955b73, result/e3766bf798a3a). Registration requests took 294.3–363.6 ms and allocated 389.6–554.0 MB in the observed requests; scalar writes took 30–51 ms and allocated 0.2–0.3 MB inside the form. The large registration allocation is an open cost question; this plan does not claim its cause or an improvement.

After writing 20, a read-only JVM dependency experiment acquired that scratch branch through `seon.db/branch-database` and released it in `finally`. It applied `datahike.api/with` to these operations:

```clojure
;; a = :schema-plan-astra/value; e is its actual holder from AEVT
[[:db/retractEntity a]]
[[:db/retract e a 20] [:db/retractEntity a]]
```

The first refused with `{:error :retract/schema :reason :current-data :attribute a}`. The composed value had neither current data nor the storage attribute, retained historical values `#{-1 7 20}`, and left the original immutable value holding 20. Composition took **2.536917 ms**, eval 7 ms, request 42.0 ms, 31.1 MB allocated (result/e7299e7eb4138). This proves native ordered transaction composition and history retention only: raw `with` did not include Seon's final validator, declaration repair, persistence, merge, or reload.

The first version of this probe attempted an attribute-bound datom read after removing its storage declaration; Datahike returned `:transact/schema` for the unknown attribute (result/eb39ebf14b958). The corrected inspection used entity-bound EAVT and filtered the returned datoms. Keep this observation in the historical-read proof; do not infer readable retired attributes from retained bytes alone.

Cleanup requested `(my.agent/retire-branch! {:seon.store/branch :agent-7800036da64e})` on the scratch agent. It recorded admission closure and returned `:my.agent/retiring? true` (738.1 ms request); a subsequent read still found the branch. Physical deletion is pending root's existing lifecycle, not claimed complete. Preserve that branch as unresolved cleanup evidence; no foreign execution was released.

## 3. One enforcement owner, three callers

### Final writer

Extend `seon.db/validate-write-report` / `seon.db/write-report-error`, beside the existing declaration and owning-value checks. The data first contracts belong to that owner. Proposed names, to register before bodies: `seon.db/schema-change-error` takes before/after database values and the changed declaration identities and returns nil or a named `:seon.db/schema-change-error` variant extending the installed error base. The error names the schema, an affected entity identity, operation and basis; it does not copy the whole database or all failing data. Its consumers are the writer, publication preview and merge diff. Reuse installed transaction, database, declaration-identity and error schemas rather than duplicating them.

Derive affected validation work from effective declaration changes in **both** values. Include deleted identities, changed forms and schema properties, and referenced predicate definitions whose changed meaning affects validation. Existing `seon.db/written-declaration-identities` loses a deleted identity when it looks only in the after-value; do not use it unchanged for this purpose.

1. Walk reverse schema dependencies before and after. Select installed attributes as well as those the final bridge derives. A stored standalone attribute cannot disappear from the work merely because an entity map does not list it.
2. For a removed attribute declaration, seek current data in the **final** value. Native physical removal already refuses residual data; the Seon check additionally covers removal of just the Malli entity. No data/history enumeration is needed to prove a current range empty.
3. For changed validation, inspect every affected current logical value. Decode old input with its old codec when constructing a norm; judge final storage using the final registry and codec. Validate collections as collections, including empty-vector presence, bounds, ordering and distinctness. Member validation alone is insufficient.
4. Extend the existing complete owning-value traversal to owners reached by schema changes, not only touched datoms. A new required field can invalidate an entity that has **no datom for that field**: seed from the affected entity schema's declared identity/selection, then traverse components and owners before and after. Deleting an entity schema while its identity still selects live entities also requires conversion or deletion. Do not let removing the selector make those entities unobservable to validation.
5. Keep the existing final reference and digest checks. A failed check commits none of the declarations, converted data or completion facts. Positive acceptance requires complete work; the first counterexample is sufficient for rejection. Never use the 200-entity `seon.db/stored-value-check` sample as proof.

Unchanged schemas add no schema-wide scans to ordinary writes. A changed shared leaf or predicate may legitimately affect many attributes; correctness follows the dependency closure rather than a hand list or a claim that every change is constant time.

### Schema publication and reload

`seon.cluster/populate-source!` currently installs storage, may drop retired families, indexes program entities, retires attributes later, and reindexes files to replace dropped facts. Replace those effects with **one composed transaction** for a schema/program transition. Existing producers continue to derive transaction data; `seon.fn/index!` must expose/reuse its existing entity derivation instead of publishing an intermediate invalid program. Instruction/config writes unrelated to this transition need not join it.

The transaction includes added storage declarations before their first data use, explicit norm output, exact schema/program replacements, and physical retirement after explicit retractions. Datahike operation order handles storage prerequisites; the final report handles semantic validity. Registering a new declaration and the function it references is atomic. A schema-only resource change still owns the complete affected population check.

Reload follows the accepted value through the existing load/instrument/record path. The schema's storage and Malli meaning must already agree when the database commit becomes visible; a later publication pass cannot repair that gap. Host-bound JVM code retains its established reload boundary, so this invariant does not falsely claim atomic replacement of JVM Vars and a database transaction. Runtime availability continues to follow the existing reload rules.

Fresh installation uses the same transition from an empty value. Resolve bootstrap schema/process prerequisites as explicit inputs to the existing producer; do not introduce a second installer or grant a lasting exemption to declaration-free databases. A publication refusing conversion leaves the prior value usable and reports its refusal; no reset and no rederive-after-loss fallback.

### Merge

`seon.cluster.source/prepare-merge!` assembles the target data plus the source's declaration delta and target-relative norm output. It previews them through the checked writer, then tests the **combined valid value**. `seon.cluster.source/accept-merge!` submits the same meaning, including physical storage changes, to main's same writer. `seon.merge/publish!` subsequently writes files; schema installation is no longer deferred to it.

Do not import the source branch's arbitrary runtime data as a substitute for conversion. A branch may be empty where main has data. When a declared partition requires some facts to merge, compose those facts with the target-relative conversion and validate the final result together.

For computations prepared outside the writer, carry the exact basis with `:datahike/expected-basis-t`. The inspected `datahike.writing/transact!` compares the serialized current value, and `datahike.writing/merge-writer!` uses it too. Declaration-digest checks alone miss a concurrent data insertion; checking a physical published head alone can miss queued writes. Reuse this dependency operation, not a new lock or synthetic schema generation counter. If main advances, keep the merge work and merge main forward explicitly; recompute conversion and tests as their inputs require. No automatic retry loop and no stale converted datom list.

## 4. What a migration means here

Use the established word **norm**. A norm is a declared ordinary program function with the contract `[:=> [:cat :seon.db/database-value] :seon.store/transaction-data]` (the installed transaction-data name), evaluated with the selected branch program. It is a pure transformation from a held immutable database value to deliberate operations. Instrument its contract, compile its target declarations, test representative data and feed its result to the checked writer. Returning a vector is not proof that the resulting database is valid.

The merge spec already proposes `:seon.merge/norms` as an ordered vector of symbols. Register that interface and its consumers rather than creating a new runner entity. The terminal root merge owns the list; if the chain must carry this member, amend TASK-CHAIN's owning sections and add its red proof in the same phase. Resolve each symbol and its definition digest in the merged program; norm code executes outside the serialized writer, in a scratch SCI context, without external effects. An SCI schema edit uses this same transition producer to return its explicit conversion operations into the eval request's terminal transaction. Calling `seon.db/transact!` midway through an eval request is a separate durable effect and cannot be presented as atomic with a later `seon.schema/register!` recording.

For several norms, fold them in their declared order over speculative immutable values. Include the compatible declarations each step needs, and keep every exposed speculative value valid; jointly dependent transforms are one norm/one transition. Commit the resulting operations with the schema and program **once**, with their application evidence in the same transaction. Reuse Datahike's `:tx/norm` concept, whose native attribute is a cardinality-one keyword; do not put a vector or symbol into that incompatible schema. Proposed Seon representation: `:seon.merge/applied-norms` is a set of `[:tuple :qualified-symbol :seon.program/definition-digest]` values on the existing merge transaction, declared and indexed for the merge's application lookup. This is transaction data, not a new runner entity. A previously recorded symbol with changed content requires a new norm name, rather than silently rerunning changed code under the old identity. The record is target-local data, never copied from the source branch as proof of target conversion.

A once-applied record is not evidence that future source data has been converted. Final validation still judges every application. If old data has been reintroduced, use a new declared norm or an explicit data repair; do not skip the invariant because a norm marker exists.

The simplest type change is additive: introduce the new attribute, transform old values into it, repair consumers and retract old values, then retire the old declaration when empty. This can be one transaction; large work can use several deliberate transactions while both declarations remain and each commit stays valid. Preserve stable entity ids and refs unless the norm explicitly changes them. Explicit entity deletion remains subject to required refs and ownership. No inferred deletion from a missing schema file.

Unsupported in-place physical changes use new names. Datomic's model is preferable to reimplementing attribute type mutation with purge. If preserving the same name for a new physical type is later requested, bring the owner the temporal-index limitation and a measured dependency design; do not make history loss an implementation detail.

## 5. Source deletion versus data on main

Example: at the merge base, A is declared and has no data. The source removes A; main writes a value using A. The declaration three-way can be clean while the combined database cannot be admitted.

On the merge branch retain **ours**, including A's declaration and data, and write an existing `:seon.unmerged/entry` for A with base/ours/theirs commit references and digests. Theirs is absent. Extend the entry's documented semantics to include this integrity conflict; it need not imply both declaration digests changed. Derive the schema error from the stored versions and current data, rather than storing a second conflict registry or all rejected values.

Apply only a valid composed subset of independent changes. If a changed consumer depends on the blocked schema transition, retain its old definition too and mark the dependent declaration unmerged; never commit a mixed program that the final writer would reject. Determine that set from the existing declaration dependency graph. A coupled transition with no valid independent subset simply keeps the target program on the merge branch. This is resolution work, not silent omission from the merge list.

Root resolves by keeping A, or by explicitly converting/deleting target data and consumers together with applying the intended declaration change. `my.program/resolve!` accepting ours abandons the deletion. A data-only repair does not pretend the schema deletion happened: apply the retained theirs version as well, then clear the entry through the existing resolution path. Merely clearing an entry never authorizes invalid data; the final writer still decides. Repeat the reaching tests on the resolved value and merge only then.

The identical process applies to narrowing a schema, changing a referenced leaf, or adding required entity members when main's data differs. A typed operation failure during preview leaves main unchanged and its evidence on the kept merge work; it does not create an invalid committed merge branch just to display the error.

## 6. Cost model

Let K be changed schema/predicate identities, G their reached dependency edges, A the affected installed attributes, N the affected datoms, O the complete affected owning values and their component datoms, M the explicit conversion operations, and D the declaration delta.

| Operation | Work required |
|---|---|
| Ordinary data write | Existing touched-data/owner validation; no schema-population scan |
| Remove a data-free attribute | Dependency closure plus one AEVT seek per affected attribute; final reference checks |
| Change validation/ownership | O(K + G + N + O), with indexed seeks and shared owner visits; a failure may stop early |
| Change only supported storage metadata | Dependency's storage work plus any changed semantic ownership validation; index backfill may be O(N) |
| Convert | Norm's declared input reads + O(M) output/encoding, then affected final validation; memory proportional to held operations and reached values |
| Merge | Existing three-way/test selection + O(D + G + N + O + M); no whole-program/data scan for an unrelated declaration change |
| Unchanged reload | Existing content reuse; no existing-data validation scan |

The dig's 0.01–0.02 ms seek and 0.6–56 ms raw validation samples, and this plan's 0.016959 ms / 1.767833 ms samples, support indexed selection. They do not price complete owning-value reconstruction or encoded data. The dig's 1.3 s all-attribute count is a reason not to scan all attributes per change. A small schema referenced everywhere can require broad validation; state that proportionality honestly.

Preview and commit can both validate; these are bounded repetitions at distinct authority points, not free reuse. Do not add a cache of acceptance keyed only by schema digest: data may change. Measure the complete request and allocations on parent and change with `seon.profile/measure`, including publication and merge. Use the configured work/time/memory bounds; never report a sampled or interrupted scan as success. Work over one second names its producer and proportionality; over ten seconds needs the owner's explicit authorization and an issue. Large conversion uses the additive multi-transaction approach above.

## 7. Phases, proofs and deletions

Each phase is a loadable commit window with file ownership and its own red proof. The named new tests below are **proposed**, not existing Vars or green evidence. Establish their reds before implementation; create contracts before bodies. Root's hybrid can split a phase further only at an independently loadable boundary. No phase depends on uncommitted work in another file.

### P1 — Make publication one transaction

Owners: `seon.cluster/populate-source!`, `seon.cluster/accrete-schema-population!`, `seon.fn/index!` and its existing transaction derivation, their publication tests. Extract transaction derivation in place and compose schema entities, storage declarations and repaired program entities into one checked write. Preserve current data semantics in this preparatory phase, but remove intermediate commits from a single transition: the existing family-drop and reapply effects become operations in the same composition, not surrounding writes. This gives P2 a caller that can actually offer the final value it judges.

Red proof proposed as `seon.schema-in-place-test/publication-changes-schema-and-program-in-one-transaction`: register a renderer and its schema, then retire a required attribute with its schema and repaired consumer; inspect effective datoms at the transaction boundary, and make a final validation failure prove none changed. Publish/reload on a live-store branch and exercise empty-store initialization through the same producer. Keep existing `seon.schema-in-place-test/a-required-key-retires-with-its-entity-schema-in-one-transaction` coverage, converting its fixture if necessary.

Delete `seon.cluster/retire-attributes!` with its last call in `seon.cluster/populate-source!`, moving retirement operations into the composed transaction. Convert the `retire?`/publish-schema-entities choreography in every caller in this same window. Amend `docs/seon/specs/merge.md` and the publication sections of `docs/prds/agent-platform/plan/lane-b1-one-publication-path.md` to describe the installed transaction boundary. HEAD boots because initialization and existing reload callers change together; no new lifecycle schema yet.

### P2 — Make declarations preserve data unless explicitly changed

Owners: `seon.db/validate-write-report`, `seon.db/write-report-error`, `seon.db/write-owned-values-error`, `seon.cluster/attribute-change-tx`, `seon.turn.batch/attribute-change-tx` and `seon.turn.batch/schema-tx`, relevant error/schema resources and tests. Install the final-state invariant with complete producer contracts and affected selection. Replace destructive planning in the same commit: otherwise the validator would accept a database whose data had already been silently erased.

Convert `seon.schema-usage-guard-test/nonidentical-change-drops-direct-and-transitive-current-data` to refusal with unchanged data/declaration for direct and transitive cases. Convert `seon.schema-in-place-test/a-tightened-form-drops-only-the-values-it-refuses` and `seon.schema-in-place-test/a-kept-vector-many-attribute-drops-only-refused-members` to preservation/refusal tests. Table rows include standalone registration/unregistration from the exact live probe, raw schema-entity retraction, encoded values, collection bounds, an invalid value beyond the old sample limit, new required members absent on untouched entities, schema predicates, identity removal and required ref repair. Add a property over bounded transitions: accepted writes leave all generated owning values valid; rejected writes leave the captured basis and data unchanged.

Delete `seon.cluster/invalid-value-retraction` with the `invalid` closure/calls in `seon.cluster/attribute-change-tx`. Reduce `seon.cluster/attribute-retraction` to explicit declaration removal, removing its data sweep, component deletion and purge branch; remove `seon.cluster/storage-type-properties` if that leaves no caller. Replace unsupported physical mutation with the new-name norm path. Delete `seon.cluster/drop-retired-families!` with its last publication caller. Delete `seon.cluster/dropped-attributes` and `seon.cluster/dropped-summary` with the publication's last reapply/reindex consumer, and `seon.schema-in-place-test/a-drop-names-the-files-whose-program-entities-carried-it` with that retired behavior. Convert type replacement/purge tests to refusal/history preservation; convert the private-plan assertion in `seon.cluster-test/a-value-type-change-replaces-the-attribute-in-place` through the public entrance.

Amend the 2026-09-23 **current ruling text** in `docs/prds/agent-platform/plan/README.md#7-decisions-and-proof-gates`, the affected cluster docstrings, `docs/seon/architecture/data-modeling-guide.md` and `docs/seon/specs/merge.md`. Preserve historical quotations in dated evidence. Remove the claim that code must recover dropped data; retain no-reset reload. HEAD boots from its unchanged valid population; a real schema delta that needs conversion is now correctly refused until a norm is supplied. Rehearse against the live store, not a fresh-only fixture.

### P3 — Compose declared norms

Owners: the existing schema/publication transaction producer, `seon.merge`, norm request/application schemas, and `docs/seon/specs/merge.md` norms section. Register the ordered norm input and application evidence, resolve the declared function at its digest, evaluate it against a held basis, compose its output with declarations, and commit once. Use the installed expected-basis input. Amend TASK-CHAIN before adding any chain-owned norm member or clause; add that member's red proof in this window. Do not implement another norm runner.

Proposed proof `seon.schema-in-place-test/a-norm-converts-data-with-its-declarations`: old string attribute → new integer attribute with stable holder identities and old history retained; invalid transformed output leaves all facts and the norm record unchanged. Table cases cover explicit deletion, changed required refs, vector order, two ordered norms, repeat application and a changed function under an old norm name. Prove one successful durable transaction and incremental reload; the target reader returns the transformed logical value. Replace the spec's program-then-norm sequencing and sample-based schema check. Retire `seon.db/encoding-only-storage-changes-migrate-reused-entities` only if this phase's proof actually covers its encoding-change case. Existing vector conversion remains until an equivalent caller conversion proves it unnecessary.

### P4 — Validate the merge's target-relative transition

Owners: `seon.cluster.source/prepare-merge!`, `seon.cluster.source/accept-merge!`, `seon.merge/merge!`, `seon.merge/publish!`, and merge tests. Carry storage operations and norm output with the program replacements into the tested merge branch and accepted main transaction. Convert the last caller relying on publication to install merged schema; delete that deferred installation behavior in this commit. Publication still owns file write-back and JVM reload.

Proposed proof `seon.merge-test/schema-and-target-conversion-merge-atomically`: source has no values, target has values, norm runs on target values, result has one consistent schema/program/data transaction; deliberate norm failure leaves target and completion facts unchanged. Add an injected concurrent target data write after preparation, including a queued writer change: `:datahike/expected-basis-t` rejects stale operations, and an explicit forward merge incorporates the new datum before retesting. Do not substitute declaration-digest equality. A schema change without a norm stops cleanly here even before P5 adds its richer resolution presentation. Amend merge spec §2, §4 and the schema-evolution case; amend TASK-CHAIN §2.6 only for changed merge semantics.

### P5 — Retain schema/data conflicts as unmerged work

Owners: `seon.cluster.source/unmerged-tx`, `seon.cluster.source/unmerged`, `seon.cluster.source/unmerged-refusal`, `seon.cluster.source/prepare-merge!`, `my.program/resolve!`, unmerged schemas/render consumers. Extend the existing entry semantics and use dependency-derived conflict identities. Preserve a valid merge branch with ours while root resolves. Do not add a second conflict family.

Proposed proof `seon.merge-test/schema-deletion-with-target-data-stays-unmerged`: empty source deletes, target inserts, target is unchanged, merge branch keeps ours with base/ours/theirs entry; explicit norm or deletion plus repaired consumers applies theirs, clears the entry, reruns tests and merges. Include keeping ours explicitly, narrowing, dependent consumer changes, and a data repair without applying the schema change (entry remains). Update vocabulary's unmerged definition and merge spec §3/§6; if resolution changes done-rule behavior, amend TASK-CHAIN with its red proof in this same phase. Keep ordinary declaration-conflict tests green. HEAD boots because new entry semantics are optional additions to existing data and all consumers change together.

### P6 — Establish the invariant on the installed population

Owners: publication/reload integration proofs and the durable measurement script. On disposable branches of a captured live-store commit, audit the baseline, apply P2 refusal cases and P3 conversion, publish, reload, instrument and repeat reads through SCI. Verify main and unrelated branches retain their own declarations. Verify old commit reads and the retired-attribute history case; no automatic purge. Empty-store initialization is the same reload path, separately observed.

Proposed proof `seon.schema-in-place-test/reload-preserves-the-schema-data-invariant` asserts the old-reader/old-value and new-reader/new-value pairs with current positive test evidence. Measure end-to-end empty removal, narrowing, encoded conversion, required-member change, no-change reload and target-data merge on parent/change; include allocations and the affected-data counts. The rollout audit may be broad once; its violations need explicit conversion tasks, not silent acceptance. Run narrowed var selections on the candidate, root's broad tiers centrally. Update phase status in the accepted owning plan with each commit. No new status ledger or recovery mechanism.

## 8. Review limits and delivery checks

No implementation, full schema publication, merge or reload was proven in this planning lane. The probes establish current failure modes and the dependency transaction seam. They do not establish that all current data is valid, that complete validation is sub-second for every workload, or that the proposed norm interface is installed.

The observed standalone selection failure is covered by P2's named proof; the registration allocation is retained here for P6's measured diagnosis. This docs-only assignment did not mutate the task index or file production task entities. Existing surrounding test failures were not rerun or labeled green. The relevant runtime schema test already names `seon.turn/the-turn-settlement-and-schema-tests-are-red`; use individual proof Vars when implementing rather than treating its entire namespace as a clean baseline.

Required checkout lint: `clj-kondo --lint src script test --cache false` completed in **7,984 ms: 0 errors, 1,805 warnings**. It returned the warning exit status. Foreign work observed at start includes render code/schema/tests, `src/seon/sci/eval.clj`, task files and dependency checkout changes; none belongs to this document's diff. The docs-only commit uses `bin/lane commit schema-plan-astra` for this path alone. `bin/lane` classifies documentation outside `seon.test.cache/input-roots`, so it has no reaching test or candidate JVM to run; no throwaway test pass is claimed. Source/test line delta is zero. The plan and exact probes above are the durable evidence; live result handles are follow-up aids only.
