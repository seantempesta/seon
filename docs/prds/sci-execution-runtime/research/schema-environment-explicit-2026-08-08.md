---
type: research
status: complete
tags: [research, schema, runtime, testing, isolation]
---

# Making the schema environment explicit — 2026-08-08

Acceptance evidence for
[schema-environment-is-ambient-not-explicit](../../../seon/issues/schema-environment-is-ambient-not-explicit.md),
the blocker filed from the
[parallel isolation audit](parallel-isolation-audit-2026-08-07.md).

Read end to end before any edit, as required: that issue, the
[seon.env PRD](../plan/seon-env-prd-2026-08-07.md) complete with its running
findings log, the parallel isolation audit, and the
[2026-08-08 adversarial audit](adversarial-audit-2026-08-08.md).

## Verdict

Two of the issue's acceptance criteria are CLOSED at cause with a class
regression each. Criterion 1 is NOT closed, in both of its halves, and the
more interesting half is the one that was implemented, measured, and
REVERTED — it is blocked by a real implementation dependency outside
`seon.schema`, now filed.

| Criterion | State | Where |
|---|---|---|
| 1a. Registry facade's cluster-selecting thread-local backing | IMPLEMENTED, MEASURED, REVERTED — blocked | [instrumentation issue](../../../seon/issues/instrumentation-compiles-under-one-clusters-projection.md) |
| 1b. The four dynamic vars deleted | OPEN — Phase 3 boundary | below |
| 2. Compiled caches on the projection value | CLOSED | `37700ec64` |
| 3. Predicate resolution retains the Var | CLOSED | `f2903354a` |
| 4. Probes graduate as one regression per class | 2 of 3; the third depends on 1a | `test/seon/schema_test.clj` |

## What each criterion cost, and what refuted the first approach

### Criterion 3 — predicate resolution retains the Var

`!predicate-functions` was a process-global `{qualified-symbol function}` map
written by 37 load-time calls across `src/`. Two environments registering the same symbol
overwrote each other, last writer winning process-wide.

**Refuters checked before editing** (`tmp/schema-env/probe_predicates.clj`,
one load-only JVM at HEAD, all 18 registrar namespaces required):

```clojure
{:count 37
 :all-resolve-identically true      ; every cached fn IS its Var's root
 :mismatches []
 :privates [seon.flow/atom-reference? … seon.search/ping-map-fn?]  ; 9, all resolve
 :var-as-fn-predicate {:ok true :even true :odd false}   ; Malli accepts a Var
 :var-is-reload-correct {:before false :after true}}     ; and re-reads its root
```

So the cache was pure duplication of `requiring-resolve`, the Var is an
acceptable Malli `:fn` predicate, and retaining the Var buys reload
correctness the cached value never had.

**The refutation that changed the design.** Routing `bind-predicates` through
`requiring-resolve` made schema validation LOAD an arbitrary namespace named
in an agent-authored `[:fn ...]` form — caught immediately by the existing
assertion `"schema validation never loads an arbitrary predicate namespace"`
in `seon.schema-test`. Agents author these forms, so a loading resolver is a
way to require code by writing its name into a declaration. Binding now uses
`loaded-predicate-var`, which resolves only ALREADY-LOADED namespaces; the
loading resolver stays at the deliberate projection-build and admission sites
that already used it. The regression gained a precondition and a postcondition
asserting the namespace is unloaded before AND after the question.

`register-core-predicate!` survives as a load-time ASSERTION rather than a
registration: it resolves eagerly as the owner namespace loads, so a typo
fails there and schema compilation never has to require code lazily. That
eagerness is what makes the non-loading binder sufficient. Its 37 call sites
belong to the load-time-sentinel deletion; eight sit in `seon.flow` and
`seon.sci.admit`, which this owner may not touch.

Also deleted with the cache: `core-predicate-functions`, `snapshot-state`,
`restore-state!` (the last two existed only to save and restore it), and the
global-table scan in `canonical-definition` — a Var carries its own name, so
inverting a bound predicate back to its symbol is `(symbol var)`.

### Criterion 2 — compiled state hangs off the projection

Every projection now carries `:seon.schema.projection/compiled`, a holder
installed FRESH at each construction (`with-compiled-cache`) and never
inherited, so a projection derived by changing forms cannot answer with its
parent's compiled results. It is a runtime key, stripped from
`projection-pure-data` alongside the registry and compile-options. A
projection assembled without one still answers every question — it just
recompiles — so correctness never depends on the cache existing.

Deleted: `!identity-only-generation`, `ensure-shape-generation-for!`, and the
validator/explainer halves of `!shape-generation`. What survives of that atom
is its one honest job, memoizing the projection last BUILT from a given
packaged population for the ambient fallback, renamed `!ambient-shape-projection`
to say so. The audit had already calibrated THAT read as correct — a single
deref compared by `=`, which cannot tear — while the validator cache sharing
its slot was the race.

Coordination note: the admission lane's in-flight `src/seon/sci/admit.clj`
resolves ONE `declaration-projection` per admission and passes it to
`identity-only-projection-in`. That work is complementary and was built on,
not duplicated: their change stops asking per node, this one makes the
descriptors compiled from a given projection reusable across admissions
instead of being recomputed whenever a second projection was seen in between.

### Criterion 1a — the registry facade: implemented, measured, reverted

Malli's process-global default resolves through `active-forms`, which selects
a population through the four thread-local dynamic vars. The process-global
default therefore answers differently depending on which thread asks, and on
a hop it falls back silently to the packaged population.

The fix the criterion asks for — one arm, the packaged bootstrap population —
was implemented, with the supporting memo it needs (the population costs 152
resource reads per question and the default is consulted on essentially every
`m/schema` during loading), and with the thread-hop regression graduated from
the probe. Under it:

- `seon.schema-test`, `seon.schema.edn-test`, `seon.schema.datahike-test`:
  green, 34 tests / 124 assertions;
- `seon.cluster.cohost-boot-test`: green, 16 assertions — TWO REAL CLUSTERS
  booted in one JVM with instrumentation live, each holding its own
  projection state;
- the 178-test consumer run: one genuine failure.

That one failure is a real implementation dependency, not test rot, so the
change was reverted rather than landed:

```
ERROR in (seon.instrument-test/applying-uses-the-acquired-projection-without-publishing-it)
clojure.lang.ExceptionInfo: :malli.core/register-function-schema
  at malli.core$_register_function_schema_BANG_ (core.cljc:3068)
  at malli.instrument$_collect_BANG_ (instrument.clj:50)
```

`malli.instrument/-collect!` reads a Var's `:malli/schema` and registers it
through `m/-register-function-schema!`, which resolves against Malli's
DEFAULT registry. That is the only way `seon.instrument` currently sees a
contract a cluster declared and the packaged resources do not — so the
cluster-selecting behavior of the facade is load-bearing for instrumentation,
and `seon.schema` cannot repair it: its only choices are answering wrongly on
a thread hop or refusing a caller with no other way to ask.

Worth naming because it makes the class bigger than the audit found:
`m/-register-function-schema!` ALSO writes into `malli.core/-function-schemas*`,
one process-wide atom keyed by namespace and symbol. Two co-hosted clusters
declaring the same function contract differently overwrite each other there
too. Appended to the existing owner's note rather than filed as a second:
[instrumentation-compiles-under-one-clusters-projection](../../../seon/issues/instrumentation-compiles-under-one-clusters-projection.md),
with the reverted change recorded in the facade's own comment so it can be
re-applied as that issue's falsifier.

The graduated thread-hop regression was removed with the revert rather than
left passing vacuously. Its non-vacuity was measured first, so it can be
restored verbatim: rebuilding the old resolution rule over the same dynamic
var, under the same `call-with-forms` binding, answers
`[:= "a foreign environment"]` where the restricted facade answers the
packaged reference.

## The Phase 3 boundary this lane did NOT cross

`*candidate-forms-overlay*`, `*projection*`, `*projection-state*`, and
`*packaged-forms*` are still present, and deleting them is not a `seon.schema`
change. Evidence:

- `call-with-forms` / `call-with-projection` / `call-with-projection-state`
  have roughly 25 call sites, in `seon.cluster`, `seon.sci.eval`, `seon.db`,
  `seon.config`, `seon.reconcile`, `seon.error`, `seon.schema.edn`, and the
  test bracket — several of them owned by lanes editing those files tonight.
- The mechanism that replaces them is the call-preparation hook, which the
  adversarial audit found LANDED BUT UNUSED (`rg -n "call-preparation" src/
  test/` returned nothing at `8e65e484c`). Until it is consumed, the dynamic
  vars are what fills declared-and-absent arguments; deleting them first
  removes the answer without supplying the replacement.
- `malli-form?` is the sharpest instance and is already filed
  ([malli-form-predicate-resolves-the-declaration-population-itself](../../../seon/issues/malli-form-predicate-resolves-the-declaration-population-itself.md)):
  it is a REGISTERED PREDICATE, so Malli invokes it with one argument and it
  cannot be handed a projection. `ask-declarations` supplies both halves by
  binding `*packaged-forms*` around it. That binding is load-bearing today and
  its removal is exactly what the environment-carried projection is for.

The recommendation for the sweep lane: convert `call-with-projection-state`'s
consumers first (they are the cluster-selecting ones and therefore the ones
the audit's probe indicted), leave `*packaged-forms*` for last because the
registered-predicate seam depends on it, and land the deletion in the same
change as the hook consumption, exactly as the flow lane was required to land
`bound-fn*` deletion with the environment merge.

## Regressions, one per class

Two landed, in `test/seon/schema_test.clj`, both named for the class rather
than the symptom. The third (registry thread-hop) is written and measured
non-vacuous but is held with criterion 1a, since it cannot pass until
instrumentation stops depending on the defect:

- `two-projections-never-exchange-a-compiled-validator` — the probe's
  two-thread alternation at 2,000 iterations per side, plus the structural
  facts underneath it (distinct holders, absent from the pure data).
- `one-predicate-symbol-cannot-name-two-environments-callables` — includes
  the arm that reproduces `probe_predicate_function_cache` exactly: a second
  registration of one symbol, which now REFUSES instead of quietly
  retargeting the symbol process-wide.

The three probe files under `tmp/isolation-probes/` are deleted, per the
audit's own graduation rule — including the registry probe, whose class is
now carried by the instrumentation issue's acceptance criteria and by the
re-appliable change recorded in the facade's comment. The four probes
belonging to other classes
(`probe_work_launcher_binding`, `probe_parallel_environment_declarations`,
`probe_branch_fork_parallel`, `probe_sci_fork_parallel`) are left for their
owners.

## Proofs run

Held per the charter: no full-suite run.

| Suite | Result |
|---|---|
| `seon.schema-test` | 13 tests / 60 assertions, 0 failures |
| `seon.schema-test` + `seon.instrument-test` after the revert | 29 / 130, 0 failures |
| `seon.schema.edn-test`, `seon.schema.datahike-test` | green, 33 / 120 combined |
| `seon.cluster.cohost-boot-test` | 16 assertions, 0 failures — two real clusters, one JVM, instrumentation live |
| consumer run: error, print, instrument, config, reconcile, db, fn, sci.eval, sci.admit | 178 / 851; the two failures are accounted for below |
| `seon.sci.admit-test`, `seon.sci.admit.declaration-population-test`, `seon.error-test`, `seon.print-test` | 47 / 190, 0 failures |
| `seon.db.declaration-population-test`, `seon.reconcile-test`, `seon.config-test`, `seon.fn-test` | green within the 75 / 363 run below |

`seon.db-test/nested-native-reports-admit-reference-identities-not-database-walks`
is FOREIGN, from the admission lane's uncommitted work. Its baseline is built
with `with-redefs [schema/identity-only-projection (constantly nil)]`, and
that lane's change routes admission through `identity-only-projection-in`
instead, so the redef no longer disables anything and the "walked" and
"admitted" artifacts come out identical (2559 bytes each). Identity admission
still WORKS — the assertions above the size comparison, on the projected
`:db-before`/`:db-after` keys, pass. That lane owns updating its own baseline.

A second error is FOREIGN and pre-existing:
`seon.program-test/changed-runtime-redeclaration-builds-a-real-replacement`
fails with `program-row-changed-after-open` from
`src/seon/cluster/run.clj:998`. The test calls `row-tx` with an empty request,
so `opening-db` has no run id, `opening-existing` is nil, and the
concurrent-definition check refuses. That is the area of `f2e1dd476`
("Refuse definitions stale against run opening") and matches the issue note
deleted from the working tree at session start,
`context-capture-cannot-read-opening-as-of-basis`. Nothing in this lane's diff
touches run opening, digests, or `program/canonical-row`.

## Ugly output and token observations

Per the standing order.

1. **`mcp__seon__runtime_status` returns a 60-frame stack trace as a
   cluster's `:seon.dev.mcp/runtime` value.** On the shared `default` cluster
   the status projection itself threw
   (`Predicate seon.flow/step-var? has no admitted callable in the corpus
   projection`, through `identity-only-projection` → `build-projection`), and
   the tool rendered the entire `:trace` vector into the caller's context —
   roughly 4 KB of JVM frames where the health census should be. A status
   call that cannot project one cluster should say so in one line and keep
   the census readable. The underlying condition is stale-JVM weather (that
   process loaded `seon.flow` before `step-var?` existed), but the RENDERING
   is a defect: this is the first tool an agent calls to orient itself, and it
   answered with a stack trace instead of a status.
2. **The declaration-population fallback warning still lands in test
   transcripts** — `seon.schema: DECLARATION POPULATION FALLBACK ×1 —
   seon.schema-test (schema_test.clj:59)` and similar from `seon.config-test`
   and `seon.db`. It is short and decade-gated now, which is a real
   improvement, but it appears in runs where nothing is wrong, which trains
   readers to skip it. The repair remains removing the fallbacks.
3. **`bin/test` prints a BEGIN and an END line per test.** For a 75-test run
   that is 150 lines of scaffolding around 3 lines of result. Reading a result
   means `tail`-ing past it every time; a lane that pipes the run into its own
   context pays for all of it.
