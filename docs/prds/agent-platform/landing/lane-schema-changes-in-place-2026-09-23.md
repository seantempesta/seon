---
type: landing
status: landed-on-refactor-agent-platform
created: 2026-09-23
tags: [agent-platform, schema, adoption]
---

# Schema changes adopt in place

Owner rulings 2026-09-23: "A schema change should not require a from scratch boot. Period.", "We don't give a shit about the data so if we remove all the data behind a schema change it should update right?", and README §7 row "Schema change and reset".

## Change

`src/seon/cluster.clj`, schema-population region. `declaration-changes` now returns transaction data and never refuses. One classification, `attribute-adoption`, answers `:install`, `:converged`, `:accrete` or `:replace` per declared attribute. `declaration-changes` and `dropped-attributes` both use it.

- **accrete** (`accretive-property-change?`, Datahike's own rule, `reference-code/datahike/src/datahike/schema.cljc:257`): the declaration is transacted. A dropped `:db/doc`/`:db/noHistory`/`:db/isComponent` is retracted from the attribute entity first, because an upsert cannot drop a property (`transaction.cljc:306` retracts it without a data check). A unique cardinality-one attribute may switch `:db.unique/value` and `:db.unique/identity` (`schema.cljc:270`). ADDING uniqueness is refused by Datahike, so it replaces. The coordinator's note said Datahike allows it; the source says otherwise.
- **replace** (`:db/valueType`, `:db/tupleType`, dropped `:db/unique`/`:db/index`): `attribute-retraction` removes the data, then `[:db/retractEntity attr]`, then the new declaration, all in ONE transaction. Datahike applies ops in order, and the current-data check (`transaction.cljc:137`) reads only current AEVT.
- **retired** (`retired-attributes`): an installed attribute counts as retired only when no projection form names its key. Datahike implicit attributes and attributes whose `:seon.schema/key` row was admitted by an agent are excluded. It is retracted the same way.
- **1.3e**: `src/seon/db.clj` `removed-definition-error` now treats a retracted attribute entity (`:db/ident`) as a removed identity. A surviving `:seon.fn/writes` referrer refuses the whole transaction by name. A replaced attribute keeps its ident, so it does not trigger this refusal.
- **Deleted**: `incompatible-declaration-message` (the `bin/seon init --force` refork advice), `require-admissible-branch!` and its boot call.
- **Converged reopen**: `declared-attributes` memoizes the projection's Datahike declarations on the projection's own cache (`seon.schema/projection-cache-value`). No second cache and no stamp. The per-attribute diff first compares the maps with `=`.
- **Reapply**: `populate-source!` reads `dropped-summary` before the population transacts. The summary gives the dropped datom count and the `:seon.fn/file`/`:seon.lint/file` paths of the program rows carrying the data. `index!` then runs for exactly those paths against the post-drop database, so every row missing a dropped value is reasserted. The count appears in the progress line `schema population complete; dropped N datoms`.

### Deviation from the §7 wording: purge, not per-datom retract

The plan text says `[:db/retract e a v]` per datom. On a temporal store (`:keep-history? true`, every Seon store) that leaves old-type history datoms behind. The first write of the new type to the same entity then throws `ClassCastException: Long cannot be cast to String` in Datahike's temporal EAVT comparator (`datahike.datom/compare-value`, via `cmp-temporal-datoms-eavt-quick`; run `243e9c9f3aee`). `attribute-retraction` therefore uses Datahike's own `[:db.purge/attribute e a]` (`transaction.cljc:1095`) for every entity that history names. That removes current and historical datoms and cascades components. It never retracts the entity. A non-temporal store keeps per-datom `[:db/retract e a v]`, with component values retracted as entities. The plan row should say "purge".

## Commits

- `1819cdcd3` db.clj: 1.3e for `:db/ident`. It also fixes a HEAD defect from `6bf3bde78`: `write-owned-values-error`'s contract declared plans as `[:map-of :keyword :map]`, but Datahike's `:schema` also has entity-id keys. Every armed write therefore refused, and the refusal itself failed canonicalizing `[:fn clojure.core/volatile?]`. Evidence: run `fe25fdc6cd82`-era runs, `tmp/schema-in-place-fast-2.log`.
- `8ce91fbaa` cluster.clj: in-place adoption. This commit also carried a foreign `boot.clj` hunk by mistake.
- `04ceaf4ad` returns that foreign `boot.clj` hunk (the stale-head refusal) to its lane's working tree.
- `c692a5c11` selects idents before sorting the retirement candidates. Mixed Long/Keyword `:schema` keys threw. This was reproduced by a disposable memory-store probe. The commit message says the default boot also reached it, which is not verified: the default log has no such exception.
- `3f273fe7c` purge, one classification, memoized declarations, reapply wiring, and `test/seon/schema_in_place_test.clj`.

## Proof

**Recorded regression: `bin/test-fast --paths src/seon/cluster.clj test/seon/schema_in_place_test.clj -- seon.schema-in-place-test`, run `d19c6cbc41a3`: 8 tests, 31 assertions, 0 failures.** Log: `tmp/schema-in-place-fast-12.log`. Each test runs on a fresh memory store populated by the real owner (`accrete-schema-population!` with the packaged projection, armed contracts, the real `seon.db` writer and validator). The canonical fixture was not used, because the published base predates HEAD's partition validator (`docs/seon/issues/test-fast-runs-on-a-published-base-older-than-heads-schema-validator.md`, run `fe25fdc6cd82`). The regressions:

| class | test | ms |
|---|---|---|
| add | a-missing-declaration-is-added-in-place | 2,651 |
| accretive (index added, noHistory dropped, unique switched), data kept | an-accretive-change-keeps-its-data | 2,187 |
| non-accretive with data (string→long), other attributes kept, new type writable | a-non-accretive-change-replaces-the-attribute-and-drops-its-data | 2,197 |
| purge-then-reinstall tx shape, history empty, rewrite same entity | a-replacement-purges-the-data-then-retracts-and-reinstalls-the-attribute | 2,300 |
| retirement with data | a-retired-attribute-is-retracted-with-its-data | 2,354 |
| retirement refused by a surviving writer: names `schema.in-place/write!` and `:seon.fn/writes`, basis unchanged, data kept | a-retirement-refuses-while-a-program-row-writes-the-attribute | 2,229 |
| converged reopen issues no transaction | an-adopted-branch-reopens-with-no-declaration-change | 1,954 |
| dropped count and carrying file | a-drop-names-the-files-whose-program-rows-carried-it | 4,143 |

About 2 s of each test is fixture cost: installing 1,211 attributes into an empty store (`first-population-ms` 2,398–3,737 below).

**Per-class timing through the owner** (disposable probe `tmp/schema_in_place_probe.clj`, run in the test-fast JVM; 1,000 entities, `tmp/schema-in-place-fast-13.log`, `-14.log`):

| operation | ms |
|---|---|
| converged reopen (whole `accrete-schema-population!`) | 15.6 / 17.3 |
| converged diff only (`declaration-changes`, 5 runs, held projection) | 3.07–3.70 (`-10.log`) |
| add one attribute | 127–234 |
| accretive (add index, 1,000 datoms backfilled) | 227 |
| replace with 1,000 datoms (then write 42 → `[42]`) | 983–991 |
| retire with 1,000 datoms | 966–1,003 |
| replace, derive on a NEW projection (declared-attributes miss) | 113 |
| replace, raw `d/with` of the same tx (purge included) | 100 |
| replace, raw `d/with` purges only | 21 |
| 10,000-datom replace transact (earlier probe, `-10.log`) | 30 |

Converged cost before this change was two full derivations per reopen: `require-admissible-branch!` plus `accrete`. Each ran `canonical-database-attributes` (19.6 ms in default, 80 ms in the test JVM) and the bridge (2.2 ms), with no memo. There is no direct before-run of the deleted code.

**Contract-referenced member retirement** (`docs/seon/issues/retiring-a-contract-referenced-schema-member-refuses-as-raw-malli.md`) does not live at this owner. `seon.schema/declaration-projection` over the packaged forms minus `:seon.source/publish-result` builds fine (55 ms, default JVM, read-only). The raw Malli refusal therefore comes from a later function-contract compile in the publication or reload path (candidate `seon.fn/incremental-projection` → `schema/build-projection` with function contracts, `src/seon/fn.clj:2812`). That frame is not verified.

## Boundaries and residue

- **Reapply wiring is not exercised by any recorded run.** `dropped-summary` is proven by the regression. The `populate-source!` → `index!` call for those paths needs a scratch publication, and this lane may not boot from zero; default is not to be adopted. It runs only when dropped datoms sit on file-derived program rows.
- **Writer cost per purged entity**: about 0.9 ms per entity in Seon's report validation (991 ms vs raw 100 ms for 1,000 entities). This is proportional but a large constant, so 100k datoms would be about 90 s. The owner is `src/seon/db.clj` (projection-writer lane). It is not measured above 1,000.
- **Plan §7 row wording** ("[:db/retract e a v] per datom") should read purge. The plan is the orchestrator's.
- **Head guard** (publication-lock P1-2): skipped per coordinator.
- **`test/seon/cluster_test.clj`** (held by realities-commit-4): three tests call the deleted three-arity `declaration-changes` and expect refusals: `a-dropped-storage-property-refuses-reopening-the-branch-in-place`, `an-added-index-adopts-in-place-instead-of-forcing-a-refork` (still passes only after the `map?` filter) and `an-incompatible-declaration-refuses-naming-the-changed-property`. They are now red at HEAD. The conversion patch is `tmp/orchestrator/schema-in-place-cluster-test.patch`. `seon.schema-in-place-test` covers the same classes.
- **`:seon.reconcile/dropped-datoms`**: the count is not on the reconcile result, because declaring the key needs `resources/seon/schemas/seon.reconcile.edn` (unheld; not this lane's path). `dropped-summary` returns it as `:seon.db/datom-count`.
- **turn.clj `schema-attribute-change-tx`** (the agent schema path) still refuses while current data exists (`assert-schema-data-unused!`). That is a parallel mechanism to `attribute-retraction`, held by projection-writer.
- No from-zero boot and no default mutation. RESET NEEDED: no.

## Follow-up (2026-09-23, commit `f11e00e76`)

The lane now holds cluster.clj, turn.clj, cluster_test.clj and seon.reconcile.edn.

- **One mechanism.** `seon.cluster/attribute-change-tx` is now public and is the only schema-change path. `declaration-changes` calls it, and so does `seon.turn/row-tx`, through `schema-attribute-change-tx` (resolved at the call, because `seon.cluster` requires `seon.turn`).
  - The turn's refusal while data exists is deleted: `assert-schema-data-unused!` and `current-schema-data-attributes`.
  - When a new projection is handed in, a kept attribute also drops the current values its new form refuses (`invalid-value-retraction`). Example: `[:string]` → `[:string {:min 3}]` drops "h" and keeps "hello".
  - A constraint that exists only on an entity member (`[child [:int {:min 1}]]` inside an entity map) is NOT checked by value. The attribute's own form is unchanged, so a violating value survives until its entity is next written.
- **Purge narrowed.** `attribute-retraction` purges only when the reinstalled declaration changes the stored type (`:db/valueType`/tuple properties). A retirement, or a replacement that keeps the type, retracts current datoms, and history keeps what existed. This keeps `retracted-data-allows-removal-and-historical-rows-restore-validation` true.
- **Dropped count.** `:seon.reconcile/dropped-datoms` (`[:int {:min 1}]`, not an attribute) is an optional member of `:seon.reconcile/result`. `populate-source!` sets it when data was dropped.
  - Incremental proof on a store populated with the OLD forms: `declaration-changes` against the new projection returns `[]` (118–169 ms, mostly the new projection's memo miss). Adoption took 13.9–16.9 ms and the basis did not move. A result carrying the member validates, and 0 is refused.
- **Tests.**
  - `cluster_test.clj`: the conversion patch is applied.
  - `schema_usage_guard_test.clj` (unheld; converted as instructed): `nonidentical-change-drops-direct-and-transitive-current-data`, `entity-schema-change-adopts-in-place-with-child-data`, and the first block of `one-decision-path-answers-every-schema-form-change`.
  - `seon.schema-in-place-test` gained `a-tightened-form-drops-only-the-values-it-refuses`.

### Verification boundary

- **Recorded run `80bbf84c5fb6`: 9/9 green.** Command: `bin/test --fast` (a HEAD copy run from scratch, because the working tree has deleted `bin/test-fast` and `bin/_test-slot`, and its `seon.test.cache` no longer has `source-inputs`).
- **Final state: 10 tests (9 regressions + probe), 34 assertions, green, but UNRECORDED.** The recording authority had gone away ("The live process has no held operator store"; default has no clusters). The run used a disposable HEAD+owned snapshot `tmp/sip-snap` and `clojure -M:test -m tmp.sip-runner`, which arms contracts through `seon.test.arm/initialize-contracts!` (12.5 s) and runs clojure.test. Wall 70 s. The snapshot was removed afterwards.
- **`seon.cluster-test` and `seon.schema-usage-guard-test` did not execute.** At HEAD, every canonical-fixture test refuses outside `seon.test/run`: "The canonical fixture needs an executing test handle" (run `-16.log`). The turn routing therefore has no executed proof.
- **File re-derivation is still unproven. BLOCKED.** It needs a store whose program rows are file-derived AND whose schema rows exist:
  - a population-only memory store has no carried projection (`seon.fn/analyzed-files` refuses "requires a carried schema projection");
  - writing its schema rows refuses, because the render functions are absent;
  - the only published base is 78 commits old and fails the partition validator;
  - default's store is flock-held.

  The unblocking step is the orchestrator's `bin/test --prepare-head-base`. After it, a canonical-fixture branch runs `tmp/schema_in_place_reapply_probe.clj` unchanged.
- **Observed defect.** Calling `seon.fn/index!` with a nil progress argument produces a contract refusal, and rendering that refusal throws "A durable Malli definition contains an unnamed callable" instead of naming the member (probe run `b41bf6a019a5`). This is the same class as the `[:fn clojure.core/volatile?]` rendering failure. Its owner is `seon.schema/canonical-definition` (projection-writer lane).
