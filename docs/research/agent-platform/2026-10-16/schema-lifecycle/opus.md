---
title: "Schema lifecycle: schemas stay while data is stored (Opus plan)"
type: research
status: plan (research only, owner 2026-10-16: nothing is queued from this yet)
---

# Schemas stay while data is stored: Opus plan

This is one of two independent plans. The other is Astra's, which I have not read.
Evidence: the dig `docs/research/agent-platform/2026-10-16/digs/schema-lifecycle.md` (6fa21abd7)
and the probes in §7. Datahike is pinned at `reference-code/datahike` ee3f8ec8.

**What exists.** One schema-change mechanism, `seon.cluster/attribute-change-tx`, serves the packaged
population (`declaration-changes`), branch adoption (`branch-declaration-changes`) and agent schemas
(`seon.turn.batch/attribute-change-tx`). On any change that is not accretive, it drops data by design
(owner, 2026-09-23). That happens through `attribute-retraction` (`:replace`, retirement, a purge when the
value type changes) and `invalid-value-retraction` (a narrowed form). The publication then re-derives the files
whose rows lost data (`dropped-summary`, a second `seon.fn/index!`). Merge carries no data. Attributes install
on main later, at publication. `retire-attributes!` drops main's data there, in a separate transaction.

**Principal approach (Datomic and Datahike norms, taken whole).** Several rules apply together:

- Schema changes accrete.
- A changed storage meaning takes a new name.
- An attribute retires only when it has no current data.
- Data changes when someone writes it on purpose, in a named, once-only, contracted **norm** that runs in the
  same transaction as the schema change it accompanies.

The writer is the one judge. Nothing drops data implicitly.

## 1. The invariant

At every commit of every branch, every current datom (not history) satisfies two conditions:

- **I1 (declared):** its attribute is declared by that commit's projection.
- **I2 (valid):** its value validates under that declaration, and every entity that an identity selects
  validates against the entity schemas of that commit's projection.

A schema change therefore commits only together with the data change that keeps I1 and I2 true. Datahike already
enforces I1 for removal (`reject-schema-removal-with-current-data`, `:retract/schema`). The writer already
enforces I2 for written datoms (`seon.db/write-report-error`). The gap is I2 for **existing** datoms when a
declaration changes. That gap is the only new check.

Measured on default: I1 holds today. There are 0 installed attributes with data and no declaration, out of
1,192, checked in 0.45 ms.

## 2. Enforcement points: one writer and three callers

1. **The writer (the authority).** `seon.db/validate-write-report` already names the schema keys a report
   changed (`written-declaration-identities`). A new check, `seon.db/declaration-data-error`, sits beside
   `removed-definition-error` in `write-report-error`. For each changed key it compares the before and after
   projections and validates only what the change constrains:
   - **Stored attribute whose form or storage changed:** validate its current datoms in `:db-after` with the
     writer's own attribute plan (decoded). Cost is linear in that attribute's datoms: 0.6 ms at 17.7k and 56 ms
     at 137k (dig §5).
   - **Entity map that gained a required entry:** find the entities with its identity that lack the entry, by
     AEVT set difference. That took 1.0 ms for 7,240 `:seon.fn/fn` entities. Whole-entity revalidation of the
     same set took 510 ms and allocated 1.85 GB.
   - **Entity map that changed in a way the entry diff does not classify:** validate the whole entities. Only 4
     `:closed true` maps exist, so this case is rare.
   - **Removed key:** the attribute must have no current datoms (one AEVT seek, 0.01–0.02 ms).

   The refusal is a new variant, `:seon.schema/data-refused-error`. It carries the key, the change kind
   (`:removed`, `:narrowed`, `:required`, `:storage`), the offending count and the requery form. It holds
   references, never copies.
2. **The schema-change builder** (`seon.cluster/attribute-change-tx`). It never emits a data retraction.
   - `:install` and `:accrete` are unchanged.
   - `:replace` is deleted. A change to the value type, tuple type or uniqueness is refused with "declare a new
     key". This is Datahike's `find-invalid-schema-updates` rule, and Datomic's.
   - A retirement becomes `[:db.fn/call seon.cluster/attribute-retirement k]`. That transaction function sees
     the mid-transaction database after any norm has run. It throws the typed refusal if data remains, and
     otherwise returns `[[:db/retractEntity k]]`. Probe P5 confirms this works.
3. **Merge (`seon.merge/merge!` and the forward `merge-main!`).** `merge-tx` already runs inside the writer.
   It gains the same builder for the schema keys in the delta, plus the norms the target has not run. Attributes
   therefore change on main in the merge's one transaction, not at a later publication.
   - **Merge branch:** `prepare-merge!` runs the delta, norms included, against main's data on the merge branch
     first. That is the rehearsal.
   - **Refusal:** if the writer refuses there, the merge branch keeps ours (main's declaration). It records one
     `:seon.unmerged/entry` for the key (base, ours and theirs digests; the e2bf7c579 mechanism unchanged) and
     carries the `data-refused` evidence beside it. The done rule and root's merge refuse it as they do any
     unmerged entry.
   - **Resolution:** someone writes the declaration again together with a norm. This is the existing "any write
     of the declaration resolves it".
   - **Merge onto main:** `merge!` runs the same norms again, as functions against main's current data, in the
     merge transaction. New data written on main since the rehearsal is therefore covered. If that data still
     refuses, the merge refuses as any red merge does. The candidate is kept.
4. **Publication (packaged files).** Attribute declarations, norms and retirements go into the final
   transaction of `seon.fn/index!`, where schema entities already land. For `:seon.program` partition
   attributes the norm is the derivation itself: the files whose entities carry a narrowed or retired
   attribute are re-derived in that same transaction. `dropped-summary`'s path selection survives as that
   selector. A `:seon.data` attribute needs an authored norm.

`branch-declaration-changes` stays accretive-only: it installs and accretes, and is never a norm. A
non-accretive change from main reaches a branch only by the forward merge, which is an explicit write.

## 3. The migration model: a norm

The model is Datahike's norm (`datahike.norm.norm`), taken whole: a name, transaction data or a transaction
function, and a once-only marker `{:tx/norm name}`. Seon makes two changes that Datahike lacks:

- the norm runs in the **same** transaction as its schema change, rather than one transaction per norm with no
  atomicity across norms;
- the norm goes through Seon's checked writer.

| Member | Schema | Meaning |
|---|---|---|
| `:seon.norm/name` | qualified keyword, identity | Datahike's `:norm`; the marker `:tx/norm` (Datahike's own attribute) records that it ran |
| `:seon.norm/tx-fn` | `:qualified-symbol` of a contracted fn `[:=> [:cat :seon.db/database-value :seon.norm/entry] :seon.store/transaction-data]` | Pure, and reads the target's data; it is never handed a connection |
| `:seon.norm/schema-keys` | `[:set :qualified-keyword]` (value edges) | The changes it accompanies; selects which norms join a schema-change transaction |
| `:seon.norm/entry` | entity map, `:seon.program/partition :seon.program` | It merges as program, carries a definition digest, and is never data |

**Where norms come from:**

- **Packaged:** `resources/seon/norms/<ns>.edn`, ingested by publication as tasks are (the file model is
  Datahike's).
- **At the REPL:** an agent defines the norm fn, then calls `my.schema/norm!`. That writes the entity in the
  same eval request's recording transaction as the registration delta that changes the schema.

**Order inside one transaction:**

1. installs and accretions;
2. `{:tx/norm n}` with `[:db.fn/call tx-fn entry]` for each norm in name order that the target has not run;
3. narrowings and retirements (`attribute-retirement`);
4. the writer's final check of I1 and I2.

**Why a function and not literal data.** Data never merges. A norm is the only form in which a branch's data
change can be replayed on main's data, which the branch never saw. That is the same reason Datomic migrations
are functions (conformity). An explicit deletion within one branch needs no norm, because it is two valid
commits: retract the data while the key is still declared, then retire the key. The same holds for
expand–contract: add a new key, copy, move the writers, retract the old data, retire the old key. A norm is
needed only for one atomic step, or for replaying on another branch's data.

**Explicit dropping stays cheap.** The 2026-09-23 "cheap drop" survives as two library norm fns that an author
names on purpose:

- `seon.norm/retract-attribute`, which is `attribute-retraction`'s data half;
- `seon.norm/retract-invalid`, which is `invalid-value-retraction`.

**Cost.** For each schema change:

- the writer check is linear in the changed attributes' datoms (≤ 56 ms at 137k; 1 ms for a required-key
  check);
- a removal adds one seek;
- the norm costs whatever data it transforms.

For each merge, the cost is the same set, keyed to the schema keys in the delta. A merge with no schema change
costs nothing extra. Nothing ever scans the whole schema: that would take 1.3 s, and nothing needs it.

## 4. What the 2026-09-23 ruling's code becomes

| Today | Becomes |
|---|---|
| `seon.cluster/invalid-value-retraction` | Leaves `attribute-change-tx`. It becomes the explicit `seon.norm/retract-invalid`, and only an author who names it runs it |
| `attribute-retraction` with purge, and `:replace` in `attribute-adoption` | Deleted. A storage-type change is refused with "new key". The data half becomes `seon.norm/retract-attribute`; the ident half becomes `attribute-retirement` |
| `dropped-attributes`, `dropped-summary`, `:seon.reconcile/dropped-datoms`, the reapply `index!` pass in `populate-source!` | Replaced by derived-path selection inside the publication's final transaction |
| `retire-attributes!`, `drop-retired-families!` | Folded into the same final transaction. A family's entities leave by an authored norm |
| Merge installs no attributes; publication installs them later | Merge carries the schema builder and the norms in its one transaction |
| Ruling row "Schema change and reset" (`docs/prds/agent-platform/plan/README.md` §7), the `declaration-changes` docstring, `seon.config` docstring (line ~707) | Amended in place to this direction. "No from-scratch boot" still holds |

## 5. Phases (HEAD boots after each; each red proof turns green in its phase)

- **P0 (spec).** Amend the ruling row in README §7, the vocabulary row "norm" (now same-transaction and a
  program entity), the data-modeling guide and the `attribute-change-tx` docstring. Record the variant
  `:seon.schema/data-refused-error`.
- **P1 (writer check, red first).** Write `seon.db/declaration-data-error`.
  - Red proofs, as new rows in `test/seon/schema_in_place_test.clj`:
    - `a-narrowed-form-refuses-while-invalid-data-is-stored`;
    - `a-new-required-entry-refuses-entities-lacking-it`;
    - `a-retirement-refuses-while-data-is-stored`.
  - Converted in the same commit (they assert drops today):
    - `a-tightened-form-drops-only-the-values-it-refuses`;
    - `a-kept-vector-many-attribute-drops-only-refused-members`;
    - `a-retired-attribute-is-retracted-with-its-data`;
    - `a-non-accretive-change-replaces-the-attribute-and-drops-its-data`;
    - `a-replacement-purges-the-data-then-retracts-and-reinstalls-the-attribute`;
    - `a-required-key-retires-with-its-entity-schema-in-one-transaction`;
    - `seon.db.schema-retirement-test/retiring-an-identity-attribute-with-entities-is-admitted`.
  - Deletions: the drop paths in `attribute-change-tx` and `invalid-value-retraction`'s call. Their last
    caller is this builder.
- **P2 (norm entity and runner).** Add the schema `seon.norm.edn`, the ordering in `attribute-change-tx`,
  `attribute-retirement`, `seon.norm/retract-attribute` and `seon.norm/retract-invalid`, and `my.schema/norm!`.
  - Red proofs: `a-norm-transforms-data-in-the-schema-change-transaction` and `a-norm-runs-once-per-database`
    (the `:tx/norm` marker).
  - Proven by publishing and reloading on a branch of the live store.
- **P3 (publication in one transaction).** The schema builder, the norms and the program re-derivation go into
  `index!`'s final transaction.
  - Deletions: `dropped-summary`, `dropped-attributes`, `retire-attributes!`, `drop-retired-families!` and the
    reapply pass.
  - Red proof: `every-commit-of-a-publication-validates-its-stored-data`, which walks the publication's commits
    and runs the writer check on each.
- **P4 (merge).** `merge-tx` and `merge-main!` carry the schema builder and pending norms. A data refusal on
  the merge branch becomes an unmerged entry.
  - Red proofs, as rows in `test/seon/cluster/merge_test.clj`:
    - `a-branch-retiring-a-key-main-holds-data-for-stays-unmerged`;
    - `a-norm-carried-by-the-merge-migrates-mains-data-atomically`.
  - Deletion: the attribute install deferred to publication for merged schema keys.

## 6. Risks and open choices

- **The norm runs inside main's writer thread** (it is SCI-interpreted chain code), as `merge-tx` does today.
  An interrupt cannot stop host code there. The rehearsal on the merge branch measures its cost. The
  alternative is to compute it outside the writer and commit under Datahike's expected-head compare-and-swap
  (publication already does this). That is left for the hybrid to choose.
- **History of a retired attribute.** After retirement, `(d/datoms (d/history db) :aevt k)` refuses
  (`:transact/schema`, probe P5). "History answers what existed" then needs an `as-of` read before the
  retirement. The same applies to old and new value types mixed under one ident (probe P2), which a new key
  avoids.
- **Packaged churn.** 1,034 commits have touched `resources/seon/schemas` since 2026-09-01. Most changes hit
  `:seon.program` attributes, whose norm is derived. Changes to `:seon.data` attributes now need an authored
  norm. That cost is the intent of the ruling.

## 7. Probes (JVM mode; in-memory Datahike or read-only on default; no writes to default)

- **P1:** retracting `:db/ident` with data refuses with `{:error :retract/schema :reason :current-data}`.
- **P2:** in one transaction: retract the data, retract the ident, reinstall as `:db.type/string`, and assert
  the transformed values. Accepted. History then mixes long and string values under one ident.
- **P3:** expand–contract (install a new key, copy, retract the old data, retire the old key) in one
  transaction. Accepted.
- **P4 (default, read-only):** on default, the AEVT set difference for a required entry takes 1.0 ms (7,240
  `:seon.fn/sym`). Validating the whole `stored-entity` of each takes 510 ms and 1.85 GB (`:seon.fn/fn`), and
  87 ms for 2,737 receipts. Checking for undeclared attributes with data takes 0.45 ms over 1,192.
- **P5:**
  - `[:db.fn/call retire k]` with data remaining throws the typed refusal.
  - `{:tx/norm n}` + `[:db.fn/call norm-drop k]` + `[:db.fn/call retire k]` in one transaction retires `k`.
    `:tx/norm` must be installed first.
