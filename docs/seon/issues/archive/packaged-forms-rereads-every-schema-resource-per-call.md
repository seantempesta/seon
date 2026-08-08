---
type: issue
status: resolved
severity: major
tags: [issue, schema, performance]
---

# `packaged-forms` re-reads and re-merges every schema resource on every call

## Problem

`seon.schema/candidate-forms` falls through to
`seon.schema.edn/packaged-forms` whenever no projection, projection state, or
candidate overlay is bound (`src/seon/schema.clj:590-599`). That fallback is
`(::forms (resource-population default-resource))`
(`src/seon/schema/edn.clj:337-341`), which for EVERY call re-lists the schema
resource directory, re-reads all ~100 EDN files, re-merges them with the
duplicate-attribute check, and re-derives the config forms. Nothing memoizes
it.

So `schema/schema-definition` — a function whose name and docstring read like
a map lookup — is a full filesystem-and-merge pass whenever it is called
outside a projection binding. Any caller that asks per item pays that cost per
item.

## Evidence

Found 2026-08-07 by seon.env Phase 1 lane W1 while diagnosing a wedged
`seon.cluster.loop-test`. The namespace normally takes 91-161 seconds
(`tmp/test-runs/bare-run-1634.log:616`,
`tmp/test-runs/bare-verify-1710.log:615`); it was still running at 15 minutes.
A virtual-thread-aware `jcmd Thread.dump_to_file` caught the responsible
virtual thread mid-merge:

```
#81 "" virtual RUNNABLE
    at clojure.lang.PersistentHashMap.assoc
    at clojure.core$merge …
    at seon.schema.edn$merge_schema_resources …
    at seon.schema.edn$resource_population …
    at seon.schema.edn$packaged_forms …
    at seon.schema$candidate_forms …
    at seon.schema$schema_definition …
    at seon.config$registration_defaults$fn__73981.invoke(config.clj:143)
    at clojure.core$keep$fn__8695 …
```

Note the caller: `seon.config/registration-defaults` calls
`schema-definition` inside a `keep` over config keys, so ONE call to it is
`(count keys)` complete resource merges. W1's own `seon.env/members` had the
same shape and was the trigger; W1 fixed its own caller by reading the
declaration once (`204e94421`), which returned the namespace to seconds. The
underlying owner is untouched.

## Owner

`seon.schema.edn/resource-population` / `packaged-forms`, with
`seon.schema/candidate-forms`.

## Resolution 2026-08-07

Repaired by resolving the population ONCE per operation and passing it — the
shape the [hang fix](../../prds/sci-execution-runtime/research/parallel-turns-hang-cause-2026-08-07.md)
modelled at the encode seam. Measurements, the complete reasoning, and the
per-item controls are in
[declaration-population-per-item-2026-08-07.md](../../prds/sci-execution-runtime/research/declaration-population-per-item-2026-08-07.md).

Reading the tree turned up two callers worse than the one the issue named:

| Caller | Before | After |
|---|---|---|
| `seon.reconcile` identity scan (per registry key, 1,885 keys) | 21,209–25,917 ms / 286,672 resource reads | 11.4 ms / 152 reads |
| `seon.config` registration defaults (per config key) | 1,003 ms / 12,464 reads | one population per operation: `default-decisions` 24.8 ms / 152 reads |
| `seon.print/option-defaults` (per print option, on EVERY emit) | 67.9 ms / 912 reads | 11.3 ms / 152 reads |

What landed:

- `seon.schema/declaration-population` — the public name for the population in
  hand for one operation, with the cost documented on it;
- population-taking arities for the questions that are not a plain `get`
  (`identity-attr?`, `valid-candidate-value?`, `explain-candidate-value`) —
  accretion, the existing arities unchanged;
- `seon.config` threads one population from each operation's entry point
  through every helper, and the helpers now REQUIRE it, so the per-key shape
  cannot be reintroduced silently;
- `seon.print/option-defaults` and `seon.reconcile/identity-attributes` read
  one population and answer every item from it.

Deliberately NOT memoized: `resource-population` is pure on purpose and a
process-global cache of declaration facts is on the
[seon.env PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)
deletion list. Acceptance criterion 1 is therefore met per OPERATION rather
than per process; one bare `schema-definition` with nothing supplied still
costs 14.5 ms, and that floor disappears when Phase 1 supplies
`:seon.schema/projection` from the environment.

Class regression: `test/seon/schema/declaration_population_test.clj` counts
reads at the one read seam and asserts each operation performs exactly one
population whatever its item count (2 tests, 6 assertions, green). It asserts
the wanted behavior and fails on the old code by arithmetic.

Live proof at the reset boundary (own isolated operator root, cluster
`declpop2`, never the shared default): every boot layer stood, 65 effective
config keys applied by the changed code, and `compile-manifest` performs one
population (152 reads / 26.8 ms).

Still open, filed separately, both the same class at boundaries this repair
cannot reach:

- the read side —
  [db-read-decoding-resolves-declarations-per-attribute](db-read-decoding-resolves-declarations-per-attribute.md)
  (one live `config/effective` = 84,664 resource reads, all inside `db/pull`);
- the Malli predicate that cannot take the population —
  [malli-form-predicate-resolves-the-declaration-population-itself](malli-form-predicate-resolves-the-declaration-population-itself.md),
  contained at one caller here and repaired properly by the environment.
