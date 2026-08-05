---
type: issue
status: resolved
severity: friction
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

The later bare 2026-08-05 gate reproduced the same exact var and value at
`test/seon/web/jvm_test.clj:359`:

```text
expected: (= 1 (:my.web/credits result))
  actual: (not (= 1 nil))
```

The direct provider-shape test in the same namespace remained green. This
keeps the attribution at the public effect result-admission boundary rather
than the provider transport or the rename pass.

## Owner

The `seon.effect` result-admission boundary and the declared `my.web/search`
result shape.

## Acceptance

- Direct and public effect search return the same declared result keys.
- `:my.web/credits` survives admission and the stored effect result.
- The focused `seon.web.jvm-test` namespace is green.

## Resolution — 2026-08-05

The failure was a test-fixture config defect, not result admission. The test
transacted only its web-specific settings, but `seon.config/effective` requires
the complete effective config row. It therefore handed the web handler a flat
`:seon.config/missing-effective` error value; the assertion then read the
absent `:my.web/credits` key from that error and incorrectly attributed the nil
to admission.

Commit `60b052341` fixed the root by merging `seon.config/defaults` into the
test's effective config row before applying the local provider overrides. The
same test now exercises the real search result through `my.web/search`, the one
effect admission pass, and receipt settlement. Its returned value contains
`:my.web/credits 1` and equals the EDN read from
`:seon.effect/result-edn`.

The focused `seon.web.jvm-test` gate passed 6 tests / 32 assertions with zero
failures or errors at `60b052341`. This includes both the direct provider-shape
test and `public-search-settles-one-receipt-with-provider-credits`.
