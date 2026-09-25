---
type: landing
lane: impl-shape-mirror-malli-opus
created: 2026-09-25
status: stopped at the +120 added-line condition. The source cut is complete and proven at the REPL; it is parked as a patch, not committed. Tests are not converted.
source-pin: 431a9b4ee
patch: tmp/impl-shape-mirror-malli/option-b.patch (applies cleanly to 431a9b4ee; `git apply` it, then `git rm` the four deleted files it lists)
---

# C4 option B: schema-shape mirror replaced by Malli (stopped before integration)

What exists: `seon.program` wrote every argument, return and guard contract, and every schema row's
`:seon.schema/shape`, as recursive content-addressed rows (`seon.fn.schema-shape`). Call preparation joined
them in Datalog; `seon.fn` guarded them for drift; `seon.instrument` digested them into error identity.
Option B reads the compiled contracts that the projection's Malli registry already retains
(`mr/schema registry sym`, `m/-function-schema-arities`, `m/-function-info`, `m/children`, `m/deref-all`),
matches supplied defaults by declared schema name, and deletes the codec, its rows and their guards.

## Broken or blocking, first

1. **I stopped at the added-line condition.** `git diff --numstat` counts 282 added and 1,066 deleted source
   lines. 190 of the added lines appear nowhere in the old files (non-blank, non-comment). About 60 are new
   mechanism (`argument-facts`, `arity-facts`, `contract-arities` in call_preparation.clj). The rest are
   rewrites of deleted queries into functions, plus `typed-key-facts` and `key-kind` moved into program.cljc
   (44 lines). The net source change is −784. The brief said to stop above +120, so I parked the diff and
   restored my files. Nothing is committed. Root should rule whether to integrate.
2. **Behavior change: the named-argument blind spot is fixed, and the fix widens preparation.** The old named
   path only saw schemas listed in the projection's partial `shape-index`. Under the new code the hot-path
   gate grows from 724 to 834 symbols, a strict superset. The 110 new symbols are mostly the 90 render
   functions whose `:seon.render/unit` argument declares an optional `:seon.db/db`. Five plans also gain
   entries: `seon.cluster.message/render-inbox-html`, `seon.error/commit-tx`, `seon.error/recording`,
   `seon.error/render-faults-html` and `seon.turn/gate-function-install`.
   **Risk:** `prepare` refuses the whole call when a supplier is unavailable, and it does this for optional
   entries too. That is the existing inline semantics. An SCI call to `seon.error/commit-tx` from an
   environment with no agent id would now be refused instead of passing through without the key. This needs
   an owner ruling: should an unavailable supplier for an optional entry leave the entry absent? That fix is
   about 5 lines in `prepare`.
3. **Callers outside my files still reference the deleted attributes.** They must change in the same
   integration:
   - `test/seon/sci/eval_test.clj:761` and `:1263-1271`, which read `return-schema` and `:seon.schema/shape`.
     Another lane holds `test/seon/sci/*`.
   - `test/seon/schema_audit_test.clj:25`, which checks `guard-schema`. It should read `(:guard (m/-function-info …))`.
   - `test/seon/schema_test.clj:1156,1276`.
   - `test/seon/schema/datahike_test.clj:520-529`, whose query uses the shape attributes.
   - `test/seon/fn_test.clj:698-724,808,1683-1707`.
   - `test/seon/program_test.clj`: `empty-composite-schema-shapes…`, the value half of
     `positional-and-map-entry…`, `regex-rest-tail…`, the shape clause of the generated-arity property,
     `function-contracts-compile-once…` (map-of/enum), `…local-registries` and `…redefinition-replaces…`.
   - `test/seon/fn/schema_shape_test.clj` (delete) and `test/seon/fn/shape_dependents_test.clj` (delete).
     The defect class they covered no longer exists.
   - `test/seon/fn/publication_test.clj:5,22-37,152-159`, which should compare the projection's contract.
     Its `with-redefs` of `add-contract-facts` is 3-arity again under the patch.
   - `test/seon/schema/predicate_metadata_refusal_test.clj:7,31-35`, which should use
     `instrument/expected-shape`.
   - `test/seon/call_preparation_test.clj:437-438`, whose `program-members` list the two deleted snapshot
     keys.
   - `src/seon/db.clj:4371` still lists `:seon.schema.shape/fingerprint` among the arity-verdict triggers.
     It is dead but harmless. `:4203` is a stale comment.
   - `src/seon/sci/eval.clj:3556` and `test/seon/db_test.clj:1960` pass a `:seon.program/schema-forms` key
     that the program no longer reads.
4. **Error identity changes once.** `:seon.error/expected-shape` and `:seon.instrument.explanation/expected-shape`
   are now `(seon.id/digest 64 [(schema/canonical-definition (m/form schema) predicates)])`, the authored form
   with predicates read back to their names. A changed referenced definition no longer changes the identity
   of a schema that references it by name. That matches "match by declared name".
5. **One-time data churn.** Every arity and argument row loses its shape refs, and schema rows lose
   `:seon.schema/shape`. `:seon.schema/shape` was not in `definition-digest-excluded-attributes`, so
   schema-row definition digests may move once. Test evidence reuse for those rows then re-runs. RESET NEEDED:
   no. The rows adopt incrementally, and stale shape datoms become unreferenced.

## Design (as parked)

- `call_preparation.clj`: `contract-arities` builds `{sym [{order min argument-count variadic? output
  arguments}]}` once per projection. It reads Malli's retained compiled function schemas and holds the
  result with `schema/projection-cache-value`, so it derives without stamps. Each argument records its
  `:seon.call-preparation/schema-key` (the authored name) and, for a map (inline or named, through
  `m/deref-all`), its `[key optional? value-form]` entries.
- `prepared-symbols`, `coherent-supplier`, `plan-for` (arities, positional slots, entries, required keys,
  argument validators) and `supplied-map-entries` all read that index.
- The snapshot's read set adds `schema/projection-attributes`, so a contract change re-derives the snapshot.
  `adopt` drops plans when a derivation is new (compared by the identity of the prepared-symbols set), not
  only when the row basis changes.
- Deleted from call_preparation.clj: `supplier-shape-query`, `supplier-return-arms-query`,
  `schema-fingerprint-query`, `by-fingerprint`, `prepared-positional-query`, `prepared-entry-query`,
  `named-entry-facts` (C3's duplicate named path), `prepared-named-entry-query`, `error-fingerprints-query`,
  `arity-query`, `positional-query`, `argument-shape-query`, `argument-validators`, `map-entry-query`,
  `required-map-entry-query`, `named-map-entry-query` and `required-named-map-entry-query`. Also deleted:
  the snapshot keys `:seon.schema.shape/{supplied,required}-map-entries` and the candidate key
  `:seon.call-preparation/shape`, with its schema.
- `program.cljc`: `argument-row` and `arity-row` stop writing `:seon.fn.argument/{schema,rest-tail-schema,
  rest-element-schema}` and `:seon.fn.arity/{input,return,guard}-schema`. `with-contract-facts` stops
  writing `:seon.schema/shape`. `rest-element-schema` and the `schema-forms` threading are gone.
  `key-kind` and `typed-key-facts` move in, with the fingerprint value unchanged.
- `fn.clj`: `drifted-schema-keys`, `shape-dependent-rows`, `prepare-forms`, `assert-consistent!` and the
  4-arity of `add-contract-facts` are deleted. The backfill test checks only `argument-count`. The
  dependent-schema closure for canonical schema rows is kept.
- `instrument.clj`: `expected-shape` (7 lines).
- Deleted files: `src/seon/fn/schema_shape.clj` (479 lines) and `seon.schema.shape{,.entry,.child}.edn`
  (129 lines). Schema members are removed from `seon.fn.arity.edn`, `seon.fn.argument.edn`,
  `seon.schema.edn` and `seon.db.edn`'s error list.
- The data-modeling skill, the data-modeling guide and the call-preparation docstring no longer claim
  shape rows.

## Evidence (default JVM, read-only, scratch namespaces removed)

Probe scripts are in `tmp/impl-shape-mirror-malli/{probe1,probe2,scratch_cp,compare,scratch_program}.clj`.
The new code was evaluated in the scratch namespaces `lane-shape-cp-scratch` and
`lane-shape-program-scratch`, whose forms were read from the edited files. No default Var was redefined.

| Consumer | Old | New | Equal |
|---|---|---|---|
| `contract-arities`, the whole projection (2,174 contracts) | n/a | 6.1 ms, once per projection | n/a |
| supplied defaults, validators, refusals, basis-t | 4 / 4 / 0 / t | 4 / 4 / 0 / t | yes |
| prepared-symbols | 724 | 834 | superset; the 110 extra are all blind-spot named maps |
| per-symbol reads (positional, key-filtered entries, required), 724 symbols | 5 queries each | index | positional and required: 724/724. Entries: 5 diffs, all blind spot |
| `plan-for`, 724 symbols (validators counted, entries order-normalized) | | | 719 equal. 5 differ by added blind-spot entries only. 32 differed only in entry order: old was set order, new is declaration order |
| `supplied-map-entries`, 834 symbols | | | 719 equal. 114 were old-empty and are new-found. 1 is extended (`gate-function-install`) |
| `program/contract-facts`, all 2,170 contracted rows | shapes included | | 2,170/2,170 equal after removing the six shape attributes |
| `expected-shape` | `normalized-form` | canonical-definition digest | identity changes by design; predicate `:fn` with a generator round-trips |

Timings (warm; old code armed, new code unarmed in scratch, so the ratios overstate somewhat):

| Operation | Old | New |
|---|---|---|
| `derive-snapshot` | 186 ms | 17.0 ms |
| `plan-for` × 50 | 242 ms | 2.2 ms |
| `contract-facts` × 2,170 | 3,200 ms | 128 ms |
| `expected-shape` × 4 | 0.65 ms | 0.45 ms |

There is no hot-path regression. The hook's pass-through path is unchanged: one set lookup.

## Changed paths, lines, commits

The parked patch changes: `src/seon/{call_preparation.clj, fn.clj, instrument.clj, program.cljc}`, the
deleted `src/seon/fn/schema_shape.clj`, `resources/seon/schemas/seon.{call-preparation,db,fn.argument,
fn.arity,schema}.edn`, the deleted `seon.schema.shape{,.entry,.child}.edn`,
`.agents/skills/data-modeling/SKILL.md` and `docs/seon/architecture/data-modeling-guide.md`.

- Source: +282 / −1,066 physical lines (net −784); 190 lines are new text.
- Schema resources: +5 / −158.
- Tests: not yet converted. The estimate is about −600 across the deletions listed under Broken 3.

Committed: this note only.

## Root runs (after integration and test conversion)

- `bin/test-check default --ns seon.call-preparation-test`
- `bin/test-check default --ns seon.program-test`
- `bin/test-check default --ns seon.fn.publication-test`
- `bin/test-check default --ns seon.fn-test`
- `bin/test-check default --ns seon.schema.datahike-test`
- `bin/test-check default --ns seon.schema-test`
- `bin/test-check default --ns seon.schema-audit-test`
- `bin/test-check default --ns seon.schema.predicate-metadata-refusal-test`
- `bin/test-check default --ns seon.instrument-test`
- `bin/test-check default --ns seon.sci.eval-test`
- `bin/test-check default --ns seon.error-test`
- `bin/test-check default --ns seon.db-test`

## Verification limits

- No test ran, per the LOAD RULE.
- The patched namespaces were never loaded as themselves. Evaluation used scratch copies of their forms,
  unarmed.
- Schema adoption on a live branch is unproven.
- The in-turn case is unexercised: a function defined mid-turn is now seen through the database's carried
  projection, which is DB-faithful, and not through the context's projection.
- A `lane-shape-cp-scratch/contract-arities` entry stays in default's current projection cache holder until
  that projection is replaced.

## Adoption fix (after 0bd3a38b4 refused on default's live store)

**Symptom.** `bin/seon init --dev default --changed …` refused with "Lookup ref attribute should be marked as
:db/unique: [:seon.schema.shape.child/id …]" (`tmp/adopt-4.txt`).

**Producer, found at the REPL on default's live value.**
- The population transaction (`seon.cluster/declaration-changes` → `attribute-change-tx` →
  `attribute-retraction`) already retires by entity id: `[:db/retract e a v]` or `[:db/retractEntity child]`
  for each datom, then `[:db/retractEntity attribute]`. It is 89,573 operations and 94,253 datoms, and
  `d/with` accepts it.
- The lookup ref comes from the writer's final check, `seon.db/write-deletion-error` (db.clj:4160). For every
  identity datom of an affected entity in `db-before`, it calls `db.utils/entid` on `[a v]` in `db-after`.
  A retired attribute is not unique there, so Datahike throws (`datahike/db/utils.cljc:124`).
- Reproduced by calling `write-deletion-error` on that report.

**Fix (db.clj, 6 lines).** An identity whose attribute is no longer an identity in `db-after` counts as
removed, and it is never resolved as a lookup ref. `removed-definition-error` then checks referrers only for
`:seon.fn/sym`, `:seon.ns/name`, `:seon.schema/key` and `:db/ident`, so retired shape identities carry no
obligations.

**Proof.**
- The whole `write-report-error` body was evaluated in `seon.db` with the fixed function as a local binding;
  no Var was redefined. On default's real retirement report it returns nil, meaning admitted, in 5.08 s.
  Of that, `write-owned-values-error` takes 4.1 s over the 94,253 retracted datoms. It is a one-time
  retirement, and the cost is proportional to the changed datoms.
- The new regression `test/seon/db/schema_retirement_test.clj` retires a unique attribute that has a row, on
  a branch of the test's live database. The old validator throws exactly this error; the fixed body
  admits it in 28 ms.
- Not proven: the later publication steps (schema-row and program-row republication) through
  `seon.db/transact!`. My raw `d/with` cannot stand in for them because it skips seon.db's value encoding.
  Root's adoption run is the proof for those steps.
