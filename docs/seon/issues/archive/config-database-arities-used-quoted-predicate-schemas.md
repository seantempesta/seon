---
type: issue
status: resolved
severity: friction
tags: [issue, schema, cljs]
---

# Config database arities used quoted predicate schemas

## Problem

The clean default-cluster boot persisted `seon.config/config-view` and
`seon.config/database-edn-cap` without `:seon.fn/spec`. Their multi-arity
metadata used `'map?`, which the runtime var index observes as the unevaluated
list `(quote map?)` rather than a pure-data Malli schema.

## Evidence

The pod logged exactly two `:malli.core/invalid-schema` warnings while indexing
those functions after the destructive default reset. A live CLJS REPL probe
confirmed that `[:cat :map]` compiles to the intended map validator without a
predicate object or quoted form. Malli's function-schema implementation in
`reference-code/malli/src/malli/core.cljc` compiles every child schema before
checking arities, so the invalid quoted child prevents the whole function
schema from being indexed.

## Owner

`seon.config` owns both public function schemas. The existing
`seon.client/var->fn-row` pure-data guard remains the one indexing boundary.

## Acceptance

- Both explicit-database arities use a pure-data registered Malli form.
- Focused config and runtime indexing tests pass.
- A rebuilt live pod indexes both functions without the two warnings.

## Resolution

Commit `9a60761f` replaced the quoted predicate children with Malli's pure-data
`:map` schema. `seon.config-test` passes 21 tests and 99 assertions. In the live
default pod, hot reload republished all 867 instrumented functions without
repeating either warning, and direct `var->fn-row` probes returned exact
`:seon.fn/spec` values for both functions.
