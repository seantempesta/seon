---
title: "Dig: schema lifecycle and the owner's schemas-present-while-data-is-stored constraint"
type: research
status: evidence
---

# Dig: schema lifecycle and the owner's "schemas present while data is stored" constraint

Status: evidence only (Haiku dig, read-only). No decisions, no code edits. Measured on default (cluster `default`, JVM mode, read-only) on 2026-10-16.

Owner's idea (verbatim): "schemas must be present as long as data is stored in the database (not in history) ... you can't change something if it invalidates data ... In datomic world they do migrations ... everything stays atomic."

## 1. How a Malli schema becomes a Datahike attribute today

- Registration: `seon.schema/register!` (src/seon/schema.clj:1653) and `seon.schema/canonical-definition` (:638). Projections are immutable values: `seon.schema/declaration-projection` (:1261), `seon.schema/projection-without-schema` (:3755), `seon.schema/schema-removal-blockers` (:935, reverse-transitive dependents plus contract symbols).
- Malli to Datahike conversion: `seon.schema.datahike/attribute-declaration` (src/seon/schema/datahike.clj:152) builds `{:db/ident :db/valueType :db/cardinality :db/unique :db/index :db/isComponent :db/noHistory ...}` from the compiled storage schema (`storage-schema`, :53; `compiled-storage`, :78). `malli->datahike-schema-in` (:276) maps a list of attributes; `database-attributes-in` (:391) lists which schema keys are stored attributes; `storable-attribute-in?` (:282).
- Installation/publication: `seon.cluster/attribute-change-tx` (src/seon/cluster.clj, "THE one schema-change mechanism", ~line 700-770) decides per attribute with `attribute-adoption`: `:install`, `:converged`, `:accrete` (drop an always-updatable property, data kept), `:replace` (`attribute-retraction` drops data and attribute, then reinstalls), or retraction when no declaration remains. `declaration-changes` (cluster.clj ~line 600) applies the packaged population in place; `retire-attributes!` runs after program rows commit.
- Branch installs: `seon.turn.batch/attribute-change-tx` (src/seon/turn/batch.clj ~line 440-460) wraps the cluster mechanism for agent-admitted schemas (`schema-tx`, batch.clj ~line 475-520). `seon.turn/...` removals call `schema/projection-without-schema` then the same mechanism (src/seon/turn.clj ~line 985-1000).
- Writer validation: `seon.db/transact!` (src/seon/db.clj:6484) validates authored tx data; `write-attribute-plan` (db.clj ~4259) compiles the per-attribute validator from the projection (`m/validator` over the registry schema; `value-schema` for many). Installation of missing system attributes happens in `stamp-transaction` (db.clj ~3987-4020). Refusals: `write-attribute-error`, `invalid-write`, `write-entity-error`.
- Reads: `seon.db/q` (db.clj:2983), `pull` (:3214), `pull-many` (:3262), `since` (:3707) decode via `attribute-decoder-in` (schema/datahike.clj:1170) and refuse unknown attributes by name (`unknown-attribute-error`, db.clj:2243, `attribute-installed?` :2214).
- Does every read/write go through the checked writer? Writes: the only raw Datahike write in src/seon is `d/transact!` at db.clj:6258 inside `transact!`, plus `d/with` at db.clj:6316 (cost preview). `seon.cluster.store` maps `datahike.writing/transact!` as a measured wrapper (store.clj:186), reached from the db owner. Reads are NOT uniformly routed through `seon.db`: raw `d/datoms` appears in src/seon/merge.clj (lines 28-44, 172-301, 558), src/seon/schema.clj (2944-2985, 3212-3318), src/seon/render.clj (849-850), and `datahike` index helpers in cluster/wake.clj:212-214. Raw datom reads do not run the decoder or validator. CLAUDE.md "Use the database owner" names this exception class; it is an observed gap, not a refusal path.

## 2. Schema deleted or changed while data exists

Same branch:
- Delete of a schema key: `seon.turn/...` computes `candidate-projection` with `projection-without-schema` (which refuses only when schema or function contracts depend on the key, schema.clj:3755-3780). Then `attribute-change-tx` with nil declaration runs `attribute-retraction` (cluster.clj, ~line 570-600): one AEVT read of the attribute, `[:db/retract ...]` for every current datom, then the attribute entity. No check that data exists. Data is dropped, not refused. The docstring says so: "The data behind a replaced or retired attribute is dropped by design" (`declaration-changes`, cluster.clj ~line 600, citing owner ruling 2026-09-23).
- Change of a form: `invalid-value-retraction` (cluster.clj, ~line 800+) retracts every current value its new Malli form refuses ("Under the owner's schema-change ruling the problematic data is dropped"). So a changed form silently deletes invalid data on the branch.
- Datahike's own guard: `reject-schema-removal-with-current-data` (datahike db/transaction.cljc:150) raises `:retract/schema` when `:db/ident` is retracted while AEVT still holds datoms. Seon's `attribute-retraction` puts data retractions first in the same transaction (ops apply in order), so this guard passes by construction.
- Surviving writers: `seon.db/removed-definition-error` (db.clj:5202) refuses a transaction that retracts an attribute or schema key while a surviving program row (`:seon.fn/writes`, `:seon.schema/references`, arity refs) still names it. This checks program references, not data.
- Change in place (accretive): `accretive-property-change?` (cluster.clj ~line 500-530) lists which installed-attribute changes Datahike applies without data check (index add, doc/noHistory/isComponent, cardinality one to many unless unique, unique value/identity switch). Adding or dropping uniqueness and changing valueType are not accepted in place; they take the `:replace` path.

Across a merge:
- `seon.merge/merge!` (src/seon/merge.clj:369-402) copies cluster-entity attributes three-way by digest (`squash-merge`, merge.clj:84-145; `program/cluster-attributes` derives the partition from schemas, program.cljc:87-115). Schema declarations (`:db/ident`) are not in `merge-tx`; they travel as source files and install on main by publication (`publish!` is a later pass, merge.clj:369 docstring).
- `seon.cluster.source/unmerged` / `:seon.unmerged/entry` (commit e2bf7c579, "A conflict is unmerged data and main wins"): conflicts on cluster declarations are recorded per coordinate (base, ours, theirs digests) and refuse merge until resolved. This covers entity values, not "data on main that uses an attribute the branch removed".
- Retirement across branches: `retire-attributes!` (cluster.clj ~line 835) runs on main after the program rows commit, so the program-row check (`removed-definition-error`) sees the final program. Data on main for a removed attribute is dropped at that point; no pre-merge check of main's data exists.
- Schema entities as data: `:seon.schema/key` entities are program entities (partition declared per schema, derived-cluster-attributes); `seon.schema.admission/source` marks agent admissions (`agent-admitted?`, cluster.clj). Schema entities merge as program rows (source files, digest-keyed), not as Datahike attributes.

## 3. Datahike norms, schema flexibility, retraction rules

Norms (reference-code/datahike/src/datahike/norm/norm.clj):
- API: `ensure-norms!` (norm.clj ~line 204) reads EDN files from a folder or resource (default `migrations`), sorted by name (`read-norm-files`, :114). `transact-norms` (:142) ensures the `:tx/norm` attribute, then for each norm not yet installed (`norm-installed?`, :31) runs ONE `d/transact` of `[{:tx/norm norm} ...tx-data (tx-fn conn)]`.
- Ordering: by filename (lexicographic), per doc/norms.md and doc/schema-migration.md.
- Atomicity: each norm is atomic on its own (one transaction). Across norms there is no single transaction: a failure mid-list leaves earlier norms committed. Checksums (`update-checksums!`, `verify-checksums`, :~230-300) guard file edits, not data.
- Documented guidance (doc/schema-migration.md, reference-code/datahike): "these should always add new definitions and never change existing definitions"; to change data, "create a new schema and transact your existing data transformed again." `tx-fn` must return transactions held in memory.
- Seon usage: none. `grep` finds no `ensure-norms` / `datahike.norm` in src, test, script, bin or resources (no `resources/migrations`). CLAUDE.md line "Norms run in their listed order" is a merge-norms statement from the vocabulary table (docs/seon/architecture/vocabulary.md:29, :65 describes a norm as "a merge may carry one to convert main's entities"). Seon's nearest equivalent is `encoding-only-storage-changes-migrate-reused-entities` (resources/seon/tasks/seon.db.edn:27), which says "an encoding change ships its migration transaction (as `seon.db/vector-migration-tx` did)" and is still a task.

Schema flexibility (doc/schema.md:3, :18): chosen at database creation, cannot be changed later; `:schema-flexibility :read` skips validation. Seon configs (reference-code/datahike doc/backend-development.md:75, benchmarking) use `:write`. Seon's validation is its own writer (section 1), independent of this setting.

Retraction and history rules (reference-code/datahike):
- `find-invalid-schema-updates` (src/datahike/schema.cljc:257-300): always allowed: `:db/doc`, `:db/noHistory`, `:db/isComponent`; `:db/index` may be added, not removed; `:db/cardinality` one to many refused when unique; `:db/unique` may be changed only if already unique and cardinality one; everything else (valueType, adding uniqueness) refused.
- `:db/noHistory` (db/utils.cljc:50, db/transaction.cljc:460, :559): datoms of such attributes are not kept in history; `keep-history?` is per attribute.
- Purge: `:db.purge/attribute` (transaction.cljc:892, :1095, :1136) removes history datoms; Seon's `attribute-retraction` uses it only when the value type changes (cluster.clj ~line 580-600).
- Datahike has no `excise` in this checkout (grep of reference-code/datahike found none).

## 4. Datomic guidance (from memory, not fetched)

Marked as well-known principles, not verified in this session. The Datahike doc cites the Datomic page <https://docs.datomic.com/cloud/schema/schema-change.html> (doc/schema-migration.md:20), which was not fetched.
- Datomic schema is data; attributes are added, not removed. Changes allowed in place are additive (new attribute, doc, index, cardinality one to many, rename via `:db/ident` alias). Changing valueType or unique on an existing attribute is not supported; the usual path is a new attribute and a migration of data.
- Data is removed by excision (`db.excise`), not by schema retraction. Retracting the attribute entity is a schema change; history of datoms stays unless excised.
- Migrations are transactions you run deliberately (conformity-style "norms"), so every database value is a consistent (schema, data) pair, which is the atomicity property the owner describes: at each point in time the data is valid for the program graph that reads it.

## 5. Cost (measured read-only on default, 2026-10-16)

Method: MCP `eval_clj` mode jvm, cluster default, with `seon.db/carried-projection` for the Malli registry. Single runs, not repeated. Attributes chosen for size: `:seon.schema.admission/source` (17,740 datoms, one, value kind), `:seon.fn/keywords` (53,575 datoms, many), `:seon.fn/calls` (137,478 datoms, many). Measurements were on the default branch (main) only; not measured on an agent branch.

| attribute | datoms | cardinality | "any datoms?" (AEVT first) | full count (AEVT) | validate every value (Malli validator, raw values) |
|---|---|---|---|---|---|
| `:seon.schema.admission/source` | 17,740 | one | 0.022 ms | 0.7 ms | 0.6 ms (0 invalid) |
| `:seon.fn/keywords` | 53,575 | many | 0.012 ms | 37.8 ms (warm-up effect, see note) | 2.7 ms (0 invalid) |
| `:seon.fn/calls` | 137,478 | many | 0.013 ms | 2.9 ms | 56.1 ms (0 invalid) |

Notes:
- The "any datoms" check is one index seek and is effectively free. "Is A used on this branch value" is `(first (d/datoms db :aevt a))`, the same test `schema-reject` uses for `:db/ident`.
- Validation is linear in the attribute's datoms (about 0.4 to 1 microsecond per value here). Whole-schema scanning is not cheap: counting all 566 populated attributes took 1,296 ms (`(count (d/datoms db :aevt a))` over every attribute). Any check must be restricted to the attribute being removed or changed, which is what `attribute-change-tx` already does (`attribute-declarations` over affected keys).
- The validator ran on raw `:v` values. Encoded attributes (EDN/vector cells, see `seon.db/attribute-decoder-in`) need decoding first; all three measured attributes reported 0 invalid, but that does not validate encoded attributes and is not evidence for them.
- Caveat on `:seon.fn/keywords`: the 37.8 ms count was measured right after a 1.3 s whole-schema scan; the other counts are lower. Treat as single warm-ish samples, not benchmarks.

## 6. CLAUDE.md laws and open tasks touching this

Laws (CLAUDE.md, Seon):
- "Validate final owning values. Include swept refs and identity-less components; refuse missing children ... Required refs refuse invalidating deletion, optional refs may sweep, components cascade. Repair surviving declaration references in the same transaction or refuse; never invent placeholder functions or tests." (Data and contracts section)
- "Every schema change is proven by publishing and reloading it on a branch of the live store ... a fresh install is the same reload onto an empty store." (How to work 5, 6; "Schema changes are incremental; no slow op escapes" in memory)
- "Retraction deletes; history answers what existed, as it was written." (Validate final owning values)
- Merge: "Conflicts stay unmerged on the merge branch" and "Norms run in their listed order." (Merge is a root task)
- Owner ruling in code (cluster.clj declaration-changes, 2026-09-23): "A schema change should not require a from scratch boot. Period." and "The data behind a replaced or retired attribute is dropped by design." This is in direct tension with the owner's new constraint and must be amended in its owning docstring if adopted.

Open tasks in resources/seon/tasks/*.edn (nearest, none names schema deletion with data):
- `encoding-only-storage-changes-migrate-reused-entities` (seon.db.edn:27): storage migration owns encoding changes; a migration transaction, not re-derivation.
- `seon.schema.edn` tasks name `seon.schema.edn/resource-population` contracts and declaration-stamp deletion (seon.schema.edn.edn:2, :4); not about data.
- `seon.merge.edn` tasks cover conflict unmerged records and publication (seon.merge.edn:2, :6, :10); the e2bf7c579 work supersedes one of them.
- No task found for "refuse schema removal while data exists on main" or for a migration (`norm`) mechanism. `grep -i migrat` in tasks hits only the storage-encoding task.

## Five facts most relevant to designing the owner's constraint

1. The current ruling is the opposite. `declaration-changes` and `invalid-value-retraction` (cluster.clj) drop data on removal, replace, and value-invalidating form change, by design (owner ruling 2026-09-23). The constraint needs an explicit ruling change in cluster.clj and in its docstrings.
2. The check the owner wants does not exist, and it is cheap. Removal goes through `projection-without-schema` (refuses only on contract dependents), then `attribute-retraction` with an AEVT read. "Any datoms for A on this branch value" is one seek (about 0.01 to 0.02 ms measured). The data-vs-program check belongs in the same transaction writer as `removed-definition-error` (db.clj:5202).
3. Validation of existing data under a new form is linear in the attribute's datoms (0.6 ms for 17.7k, 2.7 ms for 53.6k, 56 ms for 137k, raw values, measured). It must run only for the changed attribute, never over all 566 attributes (1.3 s measured), which `attribute-change-tx` already restricts. Encoded attributes need decoding first, not measured here.
4. Merge does not check data. Schema declarations travel as source files and install on main by publication; `retire-attributes!` drops main's data after program rows commit. A branch that removes an attribute main still has data for would refuse only if the constraint is added to the merge gate (`seon.cluster.source/done-refusals`, `seon.merge/merge!`) as a data check on main at merge time. Unmerged entries (e2bf7c579) give a precedent for recording and refusing.
5. Datahike gives no multi-norm atomicity; its norms are one transaction each in filename order, and Seon uses none. Atomic "data valid for the program at every point" is achievable within one transaction (Seon already batches `attribute-change-tx` with the program rows), but a migration that must transform data (new attribute from old) needs a Seon-owned transaction function (`seon.db/vector-migration-tx` precedent), not `ensure-norms!`.

## Commands and files read

- Read: src/seon/schema/datahike.clj, src/seon/schema.clj (lines 905-965, 3745-3780), src/seon/reconcile.cljc (outline only), src/seon/cluster.clj (500-900), src/seon/turn/batch.clj (425-520, 580-612), src/seon/turn.clj (985-1085), src/seon/db.clj (outline, 2206-2245, 3987-4025, 4259-4300, 4778-4815, 5195-5330, 6484-6500), src/seon/merge.clj (outline, 36-145, 369-402), src/seon/cluster/source.clj (outline, 640-670), src/seon/program.cljc (87-115, 1535-1590, 1695-1710).
- Reference code: reference-code/datahike/src/datahike/norm/norm.clj, src/datahike/schema.cljc (250-300), src/datahike/db/transaction.cljc (125-170, 290-330, 1085-1110), doc/norms.md, doc/schema-migration.md, doc/schema.md, doc/datomic_differences.md (grep).
- Measurement: MCP `mcp__seon__eval_clj` mode jvm, cluster default, read-only (no transaction). Runtime health at start: state :faulted (29 open faults, 399 errored receipts; unrelated to this dig, not investigated). Heap 1.67 GB used of 4.29 GB.
- Not done: no branch measurement; no Datomic docs fetched; no task filed (read-only).
