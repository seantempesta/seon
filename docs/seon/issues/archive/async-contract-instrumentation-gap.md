---
type: issue
status: resolved
severity: friction
tags: [issue, schema, agent]
---

# Async structural functions bypass contract validation

## Resolution

`seon.instrument` now detects async identity through the original fixed and
variadic accessors and constructs Promise-aware `:=>` and `:function`
contracts through one owner. Fixed, variadic, multi-arity, and
multi-plus-variadic functions validate resolved outputs and guards. Injection,
generation refresh, reconciliation, and removal use the same mechanism.

The 95 async contracts previously excluded from the 747-contract census are no
longer removed from its denominator. Exact-minimum variadic calls use a marked
bridge on the live Malli wrapper; removal deletes that bridge and Malli's stale
in-place instrumentation marker so the target is honestly unwrapped and can be
instrumented again.

## Evidence

`tmp/test-cljs-20260715-012122-80190.log` ran the focused async matrix with 77
assertions and zero failures or errors. It covers fixed, variadic, multi-arity,
multi-plus-variadic, injection, default guard scope, resolved rejection
recording once, delta refresh, reconciliation, accessor restoration, complete
marker removal, and re-instrumentation eligibility.

The implementation is grounded in ClojureScript `1.12.145` at
`bd23d9a2475d822ea8dfd65deaa6732428b9ed25`, Shadow `3.4.10` at
`d3c04691952aa9ea33f7287ffe9a2b3109c1e510`, and Malli `0.20.0` at
`4c054bd7d042e70d60b83b9f07fb765bc103037f`. See
[[../../prds/agent-runtime-correctness/research/async-contract-exact-source-implementation-audit-2026-07-15]].
