---
type: issue
status: open
severity: correctness
tags: [issue, web, effect, admission]
---

# Web effect search drops provider credits

## Problem

The public `my.web/search` effect path drops the provider's
`:my.web/credits` field even though the direct `seon.web.jvm/search` path
projects it correctly.

## Evidence

The 2026-08-05 rename-pass Unit 6 focused gate ran both cases in the same
`seon.web.jvm-test` process. The direct projection assertion at
`test/seon/web/jvm_test.clj:313` passed with one credit. The public effect
assertion at `test/seon/web/jvm_test.clj:359` received nil instead of one.
The remaining receipt assertions passed, including equality between the
returned value and `:seon.effect/result-edn`, so the admitted value was stored
consistently but had already lost the field.

This is independent of Unit 6's mechanical
`effect/*context*` to `effect/*request-context*` Var rename: the binding and
all reads changed together, and the other 68 shell, web, and background-effect
assertions passed.

## Owner

The `seon.effect` result-admission boundary and the declared `my.web/search`
result shape.

## Acceptance

- Direct and public effect search return the same declared result keys.
- `:my.web/credits` survives admission and the stored effect result.
- The focused `seon.web.jvm-test` namespace is green.
