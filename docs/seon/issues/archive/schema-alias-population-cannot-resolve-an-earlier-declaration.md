---
type: issue
status: resolved
severity: friction
tags: [issue, schema, testing]
---

# Resolve schema aliases within one admitted declaration set

## Problem

The supported-AST property cannot admit a three-step alias chain because the
middle alias cannot resolve the base declaration supplied in the same test
population.

## Evidence

The bare 2026-08-05 gate failed
`seon.schema.datahike-test/supported-ast-wrappers-and-aliases-have-one-declaration`
with the shrunk refusal:

```text
Schema population refused :seon.schema.datahike-test/alias-middle
(unresolved-reference).
definition: :seon.schema.datahike-test/alias-base
```

The focused reproduction at pre-rename commit `401fd300e` failed with the
same alias identities and unresolved-reference data. The test.check failure
then prints the complete throwable twice—under both `:result` and
`:result-data`—which makes the otherwise concise refusal span several
kilobytes.

## Owner

The dependency-aware declaration admission in `seon.schema.edn/admit` and the
property's declaration-set fixture.

## Root cause

Incremental admission started every `schema/register!` from the unchanged
database-derived active projection. An earlier registration was present in the
candidate forms but not that projection, so compiling the middle alias could
not see its base. Malli alias resolution itself was sound: building the same
complete declaration population succeeded.

The repaired property then exposed the adjacent dependency-scanner case for
Seon's unqualified bootstrap schema `:inst`. Malli registry references must be
qualified, so the scanner must compile `:inst` from its actual registered form
rather than manufacture the invalid form `[:ref :inst]`.

## Resolution

Resolved by `163c3ce28`. Runtime admission now adds a missing staged reference
before retrying its dependent declaration, while retaining the proportional
`projection-with-schema` path and its reverse-dependent validation. Cycles
still enter the complete-candidate cycle refusal.

The projection reference registry now uses lazy reference markers only for
qualified declarations and uses actual registered forms for unqualified
bootstrap declarations. The existing generative property remains the one
regression for both the alias chain and supported `:inst` wrappers.

The shared `assert-check!` helper was independently responsible for the ugly
failure face: test.check placed the same Throwable under `:result` and
`:result-data`, and `clojure.test` rendered it again as the assertion's actual
value. The helper now retains the complete shrunk counterexample and one
Throwable while removing those duplicate faces.

## Proof

- `bin/test seon.schema.datahike-test`: 5 tests, 13 assertions, green.
- The seven schema namespaces together: 58 tests, 262 assertions, green.
- `bin/test seon.test-support-test`: 6 tests, 18 assertions, green.
- A direct captured-report probe retained `:smallest [:minimal]`, omitted
  duplicate `:result-data`, and reported `actual: false`.
