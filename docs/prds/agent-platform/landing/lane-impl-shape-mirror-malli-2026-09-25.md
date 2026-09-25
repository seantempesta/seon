---
type: landing
lane: impl-shape-mirror-malli-opus
created: 2026-09-25
status: landed as one loadable slice after root approved the overage (net −784 source) and ruled on optional entries. Tests are converted but not run (LOAD RULE); root runs the list below.
source-pin: 431a9b4ee
---

# C4 option B: schema-shape mirror replaced by Malli (stopped before integration)

What exists: `seon.program` wrote every argument, return and guard contract, and every schema row's
`:seon.schema/shape`, as recursive content-addressed rows (`seon.fn.schema-shape`). Call preparation joined
them in Datalog; `seon.fn` guarded them for drift; `seon.instrument` digested them into error identity.
Option B reads the compiled contracts that the projection's Malli registry already retains
(`mr/schema registry sym`, `m/-function-schema-arities`, `m/-function-info`, `m/children`, `m/deref-all`),
matches supplied defaults by declared schema name, and deletes the codec, its rows and their guards.

## Broken or blocking, first

0. **Landed after approval.** Root ruled on 2026-09-25 (a) and (b):
   (a) An optional map entry whose supplier is unavailable stays absent: the call runs without the key.
   Required entries still refuse by name. `prepare` returns an `absent` sentinel from `value-for` for a
   slot marked `:seon.call-preparation/optional? true`. `plan-for` marks those slots, and the value is
   declared in `seon.call-preparation.edn` on `:slot`. The regression is
   `an-unavailable-optional-entry-stays-absent-and-a-required-one-refuses`.
   (b) The `:seon.error/expected-shape` identity change is accepted (item 4).
   The contract index's internal keys are `::argument-schema`, `::map-entries`, `::arity-arguments` and
   `::arity-output`. The first draft's `::schema` and `::entries` collided with the declared
   `:seon.call-preparation/schema` and `/entries`.
1. **(Historical) I stopped at the added-line condition.** `git diff --numstat` counts 282 added and 1,066 deleted source
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
   The risk that `prepare` refused whole calls for optional entries is resolved by ruling (a).
3. **Converted in the same commit, under root's grant.** Remaining: the stale comment at `src/seon/db.clj:4203`
   (outside the grant), and the ignored `:seon.program/schema-forms` key still passed at
   `src/seon/sci/eval.clj:3556` and `test/seon/db_test.clj:1960`. The census bound in `program_test`
   `program-partition-selects…` was lowered from ≥12 to ≥9 because the three shape attributes left the
   census. The original list follows:
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
- Tests: two files deleted (`fn/schema_shape_test.clj`, `fn/shape_dependents_test.clj`) and ten converted.
  The regression was added.

Committed: this note (f2adbfa00), then the source, schema, test and doc slice (see git log).

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
- `bin/test-check default --ns seon.render-test` (the 90 render functions now receive `:seon.db/db` when it is omitted)

## Verification limits

- No test ran, per the LOAD RULE.
- The patched namespaces were never loaded as themselves. Evaluation used scratch copies of their forms,
  unarmed.
- Schema adoption on a live branch is unproven.
- The in-turn case is unexercised: a function defined mid-turn is now seen through the database's carried
  projection, which is DB-faithful, and not through the context's projection.
- A `lane-shape-cp-scratch/contract-arities` entry stays in default's current projection cache holder until
  that projection is replaced.
