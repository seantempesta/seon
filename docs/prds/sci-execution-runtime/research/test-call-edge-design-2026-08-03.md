---
type: research
status: active
tags: [research, testing, program-graph, sci]
---

# Test-to-function call-edge design

## Verdict

Use the existing `:seon.fn/calls` relation on `:seon.test/sym` entities.
Do not add `:seon.test/calls`, a test/function naming convention, a second
analyzer, or a second graph.

The schema already declares `:seon.fn/calls` as a cardinality-many set of
refs (`resources/seon/schema.edn:1987-1995`). The missing declaration is one
optional use of that attribute on the existing `:seon.test/test` entity
schema. The missing producer step is one arm in `seon.fn/var-row`: the one
clj-kondo analysis already computes a caller set for each `deftest`, but the
test arm discards it (`src/seon/fn.clj:209-241`).

The complete fact slice is therefore:

1. make `:seon.fn/calls` owned by both the function and test program-row
   shapes;
2. attach the already-computed calls to test rows;
3. preserve both function and test identities when transacting the shared
   call relation; and
4. query direct or transitive test reachability from one immutable database
   value.

This report is design and read-only probe evidence only. It makes no
production or schema edits. I read
`definition-seam-design-2026-08-02.md`,
`test-selection-spec-2026-07-27.md`, and
`runtime-impacted-tests-2026-08-02.md` end to end before settling the design.

## Dependency ledger

| Dependency or mechanism | Selected revision | Use in this design |
|---|---|---|
| clj-kondo | `reference-code/clj-kondo` at `57252e07975710aa579b24f0d1b2b1e04195caa2` | The single static analyzer. Seon requests `:var-usages` and retains `from`, `from-var`, `to`, `name`, and `arity` (`src/seon/fn/analyzer.clj:15-28,74-82,107-125`). |
| Datahike | `reference-code/datahike` at `0e8601d7f2f6` | Stores the shared cardinality-many ref and executes the consuming Datalog. A value-less `:db/retract` removes every current datom for one entity/attribute, so exact row replacement can replace rather than accumulate call sets (`reference-code/datahike/src/datahike/db/transaction.cljc:1053-1070`). |
| SCI | `reference-code/sci` at `2db3358cba91` | Candidate contexts. This pin contains COW commit `72150fd44c81`: `fork` stamps a generation (`reference-code/sci/src/sci/core.cljc:331-337`), `eval-def` copies inherited interpreted Vars before mutation (`reference-code/sci/src/sci/impl/evaluator.cljc:25-55`), and context-aware root binding does the same (`reference-code/sci/src/sci/impl/utils.cljc:356-379`). |
| Program-row owner | current `src/seon/program.cljc` | Owns declaration identity, canonical attributes, and exact replacement. Calls are currently absent from both relevant owned-attribute vectors (`src/seon/program.cljc:22-39`). |
| Static program producer | current `src/seon/fn.clj` | Runs one analysis across `src/` and `test/`, computes calls once, and emits exact source rows (`src/seon/fn.clj:199-310`). |
| Test result sink | current `src/seon/test/runner.clj` | Existing run/result/failure facts remain the sink. The edge slice does not create another runner (`src/seon/test/runner.clj:232-293,308-369`). |

The current checkout had unrelated dirty changes in all three prospective
production owners. They were treated as protected and were not modified.

## What the one analysis already contains

`seon.fn.analyzer` asks clj-kondo for both deep var definitions and var
usages. Each normalized usage retains the enclosing namespace and var
(`from`, `from-var`) and resolved callee (`to`, `name`), with `arity` present
for a call-position usage (`src/seon/fn/analyzer.clj:15-28,64-82`).

`seon.fn/call-targets-by-caller` already turns those records into
`{caller #{target}}`. It admits an edge when:

- the usage has `arity`;
- the caller is a first-party function-like definition; and
- the target is a first-party function-like definition

(`src/seon/fn.clj:199-227`). A clj-kondo `deftest` definition carries an
arglist and `:test true`, so it is already in that caller population. The
subsequent `var-row` conditional selects a test row first and drops the call
set (`src/seon/fn.clj:229-265`). No additional analysis is needed.

### Raw clj-kondo evidence from three real test namespaces

This load-only JVM probe analyzed the three production namespaces and their
three real test namespaces together:

```clojure
(require '[seon.fn.analyzer :as analyzer])

(analyzer/analyze
 {:seon.fn.analyzer/paths
  ["src/seon/db.clj"
   "src/seon/fn.clj"
   "src/seon/program.cljc"
   "test/seon/db_test.clj"
   "test/seon/fn_test.clj"
   "test/seon/program_test.clj"]})
```

Filtering only the three chosen `deftest` callers and the three first-party
target namespaces produced this actual normalized output on 2026-08-03:

```clojure
[#::analyzer{:from seon.db-test
             :from-var edn-backed-reads-return-distinguishable-logical-values
             :to seon.db :name transact! :arity 2 :row 49}
 #::analyzer{:from seon.db-test
             :from-var edn-backed-reads-return-distinguishable-logical-values
             :to seon.db :name q :arity 2 :row 60}
 #::analyzer{:from seon.db-test
             :from-var edn-backed-reads-return-distinguishable-logical-values
             :to seon.db :name pull :arity 3 :row 68}
 #::analyzer{:from seon.db-test
             :from-var edn-backed-reads-return-distinguishable-logical-values
             :to seon.db :name entity :arity 2 :row 73}
 #::analyzer{:from seon.db-test
             :from-var edn-backed-reads-return-distinguishable-logical-values
             :to seon.db :name datoms :arity 3 :row 96}
 #::analyzer{:from seon.fn-test
             :from-var static-index-preserves-the-jvm-program-row-contract
             :to seon.fn :name rows :arity 1 :row 42}
 #::analyzer{:from seon.program-test
             :from-var function-contract-redefinition-replaces-component-facts-exactly
             :to seon.program :name exact-replacement-tx :arity 2 :row 138}]
```

These are the exact fields consumed today by
`call-targets-by-caller`; the proposed test edge is a projection of these
records, not a new inference.

### Required `db_test.clj` probe

The decisive probe called the current private derivation itself, rather than
reimplementing its filter:

```clojure
(require '[seon.fn :as seon.fn]
         '[seon.fn.analyzer :as analyzer])

(let [analysis
      (analyzer/analyze
       {:seon.fn.analyzer/paths
        ["src/seon/db.clj" "test/seon/db_test.clj"]})
      first-party
      (#'seon.fn/first-party-function-symbols analysis)
      calls
      (#'seon.fn/call-targets-by-caller analysis first-party)]
  ;; select seon.db-test callers, then their seon.db targets
  ...)
```

Actual output:

```clojure
{:first-party-function-count 63
 :db-test-callers-with-edges 13
 :seon.db-functions-called
 [seon.db/as-of
  seon.db/datoms
  seon.db/db
  seon.db/entity
  seon.db/history
  seon.db/pull
  seon.db/pull-many
  seon.db/q
  seon.db/since
  seon.db/transact!]
 :representative-test-edges
 {"seon.db-test/edn-backed-reads-return-distinguishable-logical-values"
  #{"seon.db/pull" "seon.db/transact!" "seon.db/entity"
    "seon.db/datoms" "seon.db/q"}
  "seon.db-test/explicit-and-current-database-forms-are-equivalent"
  #{"seon.db/pull" "seon.db/pull-many" "seon.db/q" "seon.db/db"}
  "seon.db-test/temporal-reads-use-explicit-and-ambient-database-values"
  #{"seon.db/transact!" "seon.db/history" "seon.db/q"
    "seon.db/since" "seon.db/as-of"}}}
```

All thirteen `deftest`s in `db_test.clj` already have at least one admitted
first-party call edge under the current derivation.

## Exact schema and row design

### Reuse the existing attribute

The leaf attribute does not change:

```clojure
:seon.fn/calls [:set :seon.db/ref]
```

The bridge derives:

```clojure
{:db/ident :seon.fn/calls
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/many}
```

There is no uniqueness, component ownership, or stored ordering. The ref
targets the existing `:seon.fn/sym` identity entity. The set/cardinality-many
model is correct because call membership has no duplicates or order. The
bridge derives ref type and cardinality from the declared Malli form
(`src/seon/schema/datahike.clj:172-188,209-244`).

### Add one optional connection to test entities

The only `resources/seon/schema.edn` addition is:

```clojure
:seon.test/test
[:map {:seon.db/entity true}
 [:seon.test/sym :seon.test/sym]
 [:seon.schema.admission/source :seon.schema.admission/source]
 [:seon.test/ns {:optional true} :seon.test/ns]
 [:seon.test/source {:optional true} :seon.test/source]
 [:seon.fn/calls {:optional true} :seon.fn/calls]]
```

The field is optional because Datahike represents an empty cardinality-many
set as absence. A test row with no admitted direct first-party call therefore
has no `:seon.fn/calls` datom.

### Keep test identity separate

A test does not become a `:seon.fn/sym` row and does not gain function source,
arglist, contract, AST, or workload attributes. It remains identified by
`:seon.test/sym`, with its existing namespace and exact source, and gains one
outgoing connection shared with function rows.

That distinction matters:

- `:seon.test/sym` says the caller is a runnable test root;
- `:seon.fn/calls` says which function entities it directly reaches; and
- `:seon.fn/sym` on an edge target identifies the called function.

The relation is shared; the caller identity remains explicit and queryable.

## Production blast radius

### `src/seon/program.cljc`

Add `:seon.fn/calls` to both owned-attribute vectors:

```clojure
:seon.fn/sym   ... :seon.fn/calls ...
:seon.test/sym ... :seon.fn/calls ...
```

Owning it only on tests would preserve the current function-only escape hatch
and leave two row models. Calls are part of the exact declaration row in both
cases. Once owned, `canonical-row` retains the relation and
`changed-attributes` sees it (`src/seon/program.cljc:337-359,405-417`).

`exact-replacement-tx` already handles it correctly: for a changed
non-component attribute it emits `[:db/retract entity attribute]` before the
desired row (`src/seon/program.cljc:419-438`). Datahike defines the missing
value as a search of `[entity attribute]` and retracts every matching datom,
so a changed call set is replaced exactly rather than accumulated
(`reference-code/datahike/src/datahike/db/transaction.cljc:1059-1069`).

No new program identity family or component row is required.

### `src/seon/fn.clj`

Four local changes:

1. The `::analyzer/test` arm of `var-row` becomes a `cond->` and attaches the
   same sorted lookup refs as the function arm when its caller has edges.
2. `artifact` becomes `(mapv program/canonical-row rows)`. Delete the special
   re-association of `:seon.fn/calls`; ownership now preserves it honestly
   (`src/seon/fn.clj:294-302`).
3. Keep `declaration-bases` free of `:seon.fn/calls`, so all target function
   identities exist before lookup refs resolve (`src/seon/fn.clj:752-770`).
4. Build each later call row with its actual program identity, either
   `:seon.fn/sym` or `:seon.test/sym`, plus `:seon.fn/calls`. The current
   `select-keys [:seon.fn/sym :seon.fn/calls]` silently drops a test identity
   and must become identity-derived (`src/seon/fn.clj:753-759`).

`analysis-rows-by-file` already computes `calls-by-caller` once for all
namespace and var definitions (`src/seon/fn.clj:279-292`).

The incremental planner needs no new branch. Because the bridge identifies
`:seon.fn/calls` as cardinality-many, any changed call set already selects a
full rebuild rather than the scalar-upsert path. That conservative behavior is
the existing no-stale-cardinality-many rule.

### `resources/seon/schema.edn`

Add the optional `:seon.fn/calls` entry to `:seon.test/test`. Do not register a
new leaf attribute. The installed Datahike schema for `:seon.fn/calls` already
exists, so this is an entity-shape accretion and program-row ownership change,
not a second physical relation.

### Tests required by the implementation slice

The smallest recurring proof belongs in `test/seon/fn_test.clj` and
`test/seon/program_test.clj`:

- a real analyzed `deftest` row carries exact first-party lookup refs;
- a test call-set change selects a full rebuild;
- canonical function and test rows both retain `:seon.fn/calls`;
- exact replacement retracts an old many-ref set before adding the new set;
- index population transacts test call rows only after both caller and target
  identities exist; and
- no edge is derived from a test/function naming relationship.

This report does not add those tests because the lane is research-only.

## Migration and publication cost

This is additive at the schema level and a complete re-projection at the
program level.

- No entity identity changes. Existing refs to `:seon.test/sym` remain valid.
- No data conversion or child-entity migration exists.
- A complete `current-src` scratch publication re-analyzes `src/` and `test/`
  once and emits the new relation.
- Existing sovereign clusters do not migrate in place. They keep their older
  program until the operator explicitly reforks them from the newly published
  commit.
- Incremental publication of a changed test call set correctly falls back to
  a full rebuild because the changed attribute is cardinality-many.

Legacy rows without the attribute are not rewritten in place. On a freshly
published branch, absence means the one static analyzer admitted no direct
first-party call-position edges for that test. On an older branch, the schema
and source publication predate this contract; callers must not compare its
absence with a new branch as if the bases were equivalent.

## Consuming Datalog

The same attribute on function and test callers requires the caller identity
in each rule. These rules derive transitive exercise without storing a reverse
edge:

```clojure
(def test-call-rules
  '[[(function-reaches ?caller ?target)
     [?caller :seon.fn/sym]
     [?caller :seon.fn/calls ?target]]

    [(function-reaches ?caller ?target)
     [?caller :seon.fn/sym]
     [?caller :seon.fn/calls ?next]
     (function-reaches ?next ?target)]

    [(test-exercises ?test ?target)
     [?test :seon.test/sym]
     [?test :seon.fn/calls ?target]]

    [(test-exercises ?test ?target)
     [?test :seon.test/sym]
     [?test :seon.fn/calls ?entry]
     (function-reaches ?entry ?target)]])
```

### Tests exercising function `F`

This returns direct and transitive test roots:

```clojure
[:find [?test-symbol ...]
 :in $ % ?function-symbol
 :where
 [?target :seon.fn/sym ?function-symbol]
 (test-exercises ?test ?target)
 [?test :seon.test/sym ?test-symbol]]
```

Use the changed function's stored string identity, for example
`"seon.db/q"`, as `?function-symbol`.

### Functions with zero direct test edges

This is the exact structural audit requested; it does not count indirect
tests:

```clojure
[:find [?function-symbol ...]
 :where
 [?function :seon.fn/sym ?function-symbol]
 (not-join [?function]
   [?test :seon.test/sym]
   [?test :seon.fn/calls ?function])]
```

### Functions with zero exercising tests

This is the more useful coverage projection because it includes tests that
enter through a wrapper or helper:

```clojure
[:find [?function-symbol ...]
 :in $ %
 :where
 [?function :seon.fn/sym ?function-symbol]
 (not-join [?function]
   (test-exercises ?test ?function))]
```

### Executable query probe

A focused in-memory Datahike probe installed three function rows, one function
edge, one direct test edge, and one indirect test edge through the existing
`:seon.fn/calls` schema. The queries above returned:

```clojure
{:tests-exercising-target
 ["probe-test/indirect" "probe-test/direct"]
 :probe-functions-with-zero-direct-test-edges
 ["probe/untested"]
 :probe-functions-with-zero-exercising-tests
 ["probe/untested"]}
```

The probe used `seon.test-support/with-database`, added no production schema,
and exited zero. It proves the shared-attribute query shape against the pinned
Datahike fork; it does not stand in for the recurring implementation tests.

## Definition-seam consumption

For a redefinition of function `F`, the test-before-install sequence is:

1. Capture one immutable current database value and the cluster's live SCI
   context.
2. Create `candidate-ctx = (sci/fork cluster-ctx)`. COW generation ownership
   makes both interpreted redefinition and later contract root binding private
   to the candidate.
3. Evaluate and instrument the candidate definition in `candidate-ctx`. The
   real cluster context and database still contain the prior accepted
   definition.
4. Query the captured database value with `test-exercises` for `F`. Sort and
   deduplicate the returned `:seon.test/sym` strings before execution; database
   set iteration never determines order.
5. Resolve and run exactly those test roots in `candidate-ctx`, so every call
   to `F` sees the candidate Var. Capture ordinary `clojure.test` results and
   use the existing test run/result/failure fact shapes.
6. Produce a verdict before touching the real context:
   - pass: discard the candidate, then take the normal terminal transaction
     and real-context install path once;
   - fail or interruption: discard the candidate and return the flat failure
     value; nothing needs rollback because nothing reached the real context or
     database.

For a brand-new function identity there can be no old incoming ref to that
identity, so the old-test query is honestly empty. Contract-derived generative
checking for a new function is a separate downstream input to the same verdict.

The call-edge slice does not add a second test runner. The current
`seon.test.runner/run!` selects host namespaces rather than individual SCI
test Vars (`src/seon/test/runner.clj:232-293`); the definition-seam
implementation must extend that owner or invoke its capture/result machinery
for selected candidate Vars. That execution work consumes this fact but does
not change its schema.

## Scope and open decisions

### Settled for this slice

- **Attribute:** shared `:seon.fn/calls`, not `:seon.test/calls`.
- **Target:** ref to the existing `:seon.fn/sym` entity.
- **Cardinality:** many, set semantics, non-component.
- **Producer:** the existing clj-kondo analysis and
  `call-targets-by-caller`.
- **Selection:** derive reverse/transitive reachability; store no reverse edge.
- **Migration:** full current-source publication and explicit cluster refork,
  not in-place synchronization.

No owner ruling is required for those decisions; they follow the active
program model and the explicit one-analysis constraint.

### Honest limitation, not a second edge design

The current producer admits only usages carrying clj-kondo `arity`. It does
not claim dynamic coverage or every higher-order use. The probe itself exposed
the distinction: `seon.program/row-identity` used as a function value in
`fn_test.clj` had no `arity` and therefore was not admitted, while the direct
`seon.fn/rows` call was.

Accordingly:

- “zero direct test edges” means zero recorded, statically resolved,
  first-party call-position edges;
- it must not be rendered as proof that a function is untested; and
- a future soundness/widening slice may over-approximate mentioned Vars or add
  explicit completeness/uncertainty facts, but it should still publish into
  this one `:seon.fn/calls` graph rather than add a parallel relation.

That precision policy is downstream of the requested missing-fact slice. The
schema and query design above do not need an owner decision to land.

## Implementation evidence

The slice landed at `093670eff` after the owner ruled that linkage is derived
plus an explicit override. The implementation reuses `:seon.fn/calls` on test
rows and adds the optional `:seon.test/subject` ref; it adds no analyzer and no
reverse edge. `seon.fn/tests-reaching` queries direct and transitive callers
from one immutable database value, seeding the same reachability from an
explicit subject.

Focused gates passed:

- `seon.fn-test seon.program-test`: 24 tests, 150 assertions;
- `seon.schema.edn-test seon.schema.datahike-test`: 19 tests, 57 assertions;
- the `src/seon/db.clj` plus `test/seon/db_test.clj` source probe found 16 test
  rows and derived call sets for all 16; and
- the recurring database falsifier proves exact analyzed callees, distinct
  same-name function/test identities, direct and transitive selection, and a
  subject-only schema property.

`bin/seon init` published digest
`28fef5435ab8357419447145c6c8401117ef62b94ee14c09c3afb72260259379`.
A fresh isolated operator root then forked and started cluster `proof` from
that digest. Its source census had grown from the earlier 794-row zero-edge
baseline to 867 test rows during the concurrent landing wave. Of those, 817
rows carried `:seon.fn/calls`, 50 did not, and the database contained 3,410
distinct test-to-function pairs.

The 50-row residue was classified from clj-kondo's structured caller, target,
and arity fields rather than source text or names:

- 39 tests have only core or dependency calls, so no first-party target can be
  recorded; and
- 11 reference a first-party function outside call position, the documented
  higher-order/Var-reference limitation of the admitted edge.

The live override falsifier transacted `proof.subject/schema-property` with a
`:seon.test/subject` ref to `proof.subject/target` and no `:seon.fn/calls`.
`seon.fn/tests-reaching` returned the test for that target, proving the
explicit fact reaches the same selection boundary without inventing a call.
