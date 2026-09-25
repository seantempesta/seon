---
type: landing
status: complete
lane: fix-config-flat-opus
created: 2026-09-25
---

# Lane fix-config-flat-opus (2026-09-25): census d class 4 FLAT and the schema_test reds

The config refusals are now flat. `refuse!` builds them through the error owner's
`seon.error.refusal/diagnostic` and passes the whole cause. `::key` and `::path` are the
declared members (seon.config.edn:43 and :40), and they now sit at the top level. No
test ran here; root runs the commands below.

## Broken or unresolved first

- **Not yet flat: undeclared members in config.clj.** They stay under
  `:seon.error/data` because no schema declares them:
  - `::explanation`, at config.clj:236, 312, 327, 349, 391, 407 and 521.
  - `::missing` and `::available`, at effective-in.
  - `:seon.config/identity` and `::read-error`, at population-transaction-data.
  - `:seon.config/missing-functions`, at require-functions!.

  Making them flat needs declarations in resources/seon/schemas/seon.config.edn, which
  this lane may only read. `::explanation` is also a raw Malli explanation that holds
  schema objects. Owner decision: declare each member, or replace the explanation with
  `:seon.error/problems` in the same way `seon.instrument/violation` does.
- **schema.clj producers still nest members:**
  - `pulled-selector-refusal`, at schema.clj:3341–3346.
  - `render-contract-refusal!`, at schema.clj:2010–2024.
  - `refuse-projection-source`, at schema.clj:3243.

  The tests now read the declared flat members `:seon.schema/refused-value` and
  `:seon.schema/expected-value`, but `:seon.error/data` stays in these producers. The
  owner is src/seon/schema.clj, which this lane did not edit.
- **Owner wrong, schema_test.clj:1471, error-declared-storable.** Six error members
  alias identity schemas, so they inherit `:db.unique/identity`. The probe below lists
  them:
  - seon.message.edn:156 `:unknown-recipient :seon.agent/id`
  - seon.context.edn:79 `:selection-agent-id :seon.agent/id`
  - seon.sci.eval.edn:200 `:schema-refused :seon.schema/key`
  - seon.test.runner.edn:73 `:long-test-ns-hook :seon.ns/name`
  - seon.test.runner.edn:75 `:default-cluster-refused :seon.cluster/name`
  - seon.turn `:compaction-agent-id`

  The test holds the wanted behaviour. The fix is to declare each member by its value
  type, not by the identity schema.
- **Owner wrong, schema_test.clj:1320, one-derivation.**
  - src/seon/fn/schema_shape.clj:169 (`form-projection`) writes
    `:seon.schema.projection/predicate-functions` directly instead of calling
    `seon.schema/with-predicate-functions`.
  - src/seon/schema.clj:2393–2401 (`projection-runtime-keys`) is a hand-kept key set that
    names the same key.

  The test is right on both.
- **Not settled, schema_test.clj:1646, incremental projection.** This is the N3 skew
  candidate and the tree is now clean. RERUN it before any verdict. It was not probed.
- **contracts-plan refusal-grammar:** the token estimate is 151, over its bound of 150
  (contracts_plan_test.clj:73). This is a render or print cost, and this lane did not
  touch it.
- **Foreign file:** during this lane, the Bash hook reported that
  test/seon/cluster/agent_test.clj does not parse in the shared tree (1819:1, an
  unmatched paren). That file is another lane's work in progress. This lane did not
  touch it.
- **Status fault:** default's `runtime_status` shows the fault "JVM RESTART NEEDED:
  loaded dependencies changed (reference-code/…)". RESET NEEDED: none from this lane.

## Changes

- **src/seon/config.clj:**
  - `refuse!` builds its value with `diagnostic`, adding `:seon.error/throwable cause`
    when there is a cause. That derives `:seon.error/chain`, `:seon.error/frame` and
    `:seon.error/exception-class`.
  - The members that were nested at the 9 census sites plus :204 are now flat.
  - The empty `:seon.error/data {}` is removed.
  - `result-caps` puts `::key` and `:seon.config/missing-effective` at the top level.
    It drops `::configuration-refusal`, which only repeated `:seon.error/offending`.
- **test/seon/config_test.clj:**
  - The expected result-caps maps at :666 and :705 are now flat.
  - The `:seon.instrument/problem-paths` read is now flat, because instrument is
    already flat (instrument.clj:393–399).
  - apply-converts is replaced. Its `with-redefs` of `db/transact!` and
    `reconcile/plan` broke the law against Var redefinition, and it asserted a retired
    `::reconcile-refused` wrap. The new `apply-surfaces-a-refused-reconcile-as-its-flat-rule-error`
    uses a real refusal: the desired row names a function that has no program row, so
    `require-functions!` refuses inside the reconcile transaction and `transact!`
    returns that value verbatim (db.clj:4491–4493).
- **test/seon/maintenance_test.clj:320:** reads `(:seon.error/member answer)`. The
  producer is maintenance.clj:248.
- **test/seon/contracts_plan_test.clj:64:** reads `(:seon.error/problems refusal)`.
- **test/seon/schema_test.clj:**

  | Line | Verdict | Change |
  |---|---|---|
  | :446 | UPDATE | Reads `:seon.schema/refused-value`, a member of `:seon.schema/validation-refusal`. |
  | :981 | UPDATE | Reads `:seon.schema/expected-value` and `:seon.schema/refused-value`. Drops the member read, which the producer never set. |
  | :1505 | UPDATE | Reads the flat `:seon.error/member` and `:seon.schema/refused-value`. |
  | :1176 | UPDATE (retired assumption) | The synthetic ns row now carries `:seon.program/definition-digest`, required since f27b96c19. |
  | :1288 | UPDATE (retired assumption) | `argument-validators` now takes a complete `:seon.call-preparation/snapshot`. The test passes `empty-snapshot` plus its projection. |
  | :1320 | OWNER WRONG | See above. |
  | :1471 | OWNER WRONG | See above. |
  | :1646 | RERUN | Not settled. |

- **Net lines:** +53/−69 across the five files, before this note.

## Held lanes: the exact expected-shape change

- **test/seon/sci/eval_test.clj** (working-tree line numbers; the HEAD numbers are in
  parentheses):
  - :1323–1324 (HEAD :1368–1369): read `(:seon.instrument/arity failure)` and
    `(:seon.instrument/arglists failure)`.
  - :611 (HEAD :608–611): read `(::instrument/arglists instrumentation-read)`, not
    `(:seon.error/data instrumentation-read)`.
  - HEAD :739, a-configless-database, if the test survives: read
    `(:seon.config/key caps)`, not `(:seon.config/key (:seon.error/data caps))`.
- **test/seon/render_source_test.clj:210:** the producer, render.clj:1332, sets
  `:seon.render/refused-member :seon.render.value/root` and no `:seon.error/member`.
  Assert `(= :seon.render.value/root (:seon.render/refused-member failure))`, or delete
  the duplicate assertion.

## Evidence

- **Constructor probe** (JVM, read-only). A config rule observation with a thrown cause
  goes through `diagnostic`. It validates against `:seon.config/rule-error` both before
  and after. The keys after are `:seon.config/path`, `:seon.error/chain`, `:frame` and
  `:exception-class`. It took 2 ms.
- **1471 probe:** the storable closure over default's projection gives the six
  `[schema attribute :db.unique/identity]` rows listed above. It took 79 ms.
- **1288 probe:** 25 of the 25 contracted functions refuse
  `argument-validators ... refused _current at [:seon.call-preparation/supplied-defaults]`.
  It took 1,276 ms, which is over one second. That time is the probe's 25 plan reads
  over default's full graph, not the change. The call was made only for diagnosis.
- **Lint:** clj-kondo reports 0 errors on the five files. The warnings were there
  before this lane.
- **Before/after timing:** none. No hot path changed. `refuse!` runs only on refusal.

## Root runs (serially)

```
bin/test-check default --ns seon.config-test
bin/test-check default --test seon.maintenance-test/last-collection-answers-when-a-root-was-collected-and-what-it-reclaimed
bin/test-check default --ns seon.contracts-plan-test
bin/test-check default --ns seon.schema-test
```

Expected results:
- config-test: green for the four FLAT members. The other reds (with-redefs, WORLD :613,
  a-refused-read :725) belong to other classes.
- schema-test: 446, 981, 1176, 1288 and 1505 turn green. 1320 and 1471 stay red until
  their owners are fixed. 1646 needs its rerun verdict.
