---
type: issue
status: open
severity: blocker
tags: [issue, flow, schema]
---

# Replace bare flow callback predicates with honest contracts

## Problem

Thirteen converted `seon.flow` callback declarations remain bare `fn?` symbols.
They are readable Malli forms, but they have no constructible generator and do
not meet the B2 generative-honesty design for function-valued boundaries.

## Evidence

`src/seon/schema/flow.edn` retains the exact prior forms for `work-fn`,
`deliver!`, `read-facts`, `step-fn`, `stopped!`, `commit-fault!`,
`commit-drop!`, `read-core-error-mode`, `panic!`, `plan-step-fn`,
`fix-step-fn`, `read-sources`, and `compile-namespace-fn`.

The sealed schema-EDN gate checks authored `[:fn ...]` predicate forms; these
bare predicate symbols therefore remain outside that lint. The B2 research
calls for callback contracts to become function schemas or to own an explicit
runtime-boundary exception, which requires contract-owner judgment beyond a
relocation.

## Owner

The `seon.flow` function contracts and the schema admission lint.

## Acceptance

Each callback has a truthful input/output function schema and generated
functions validate, or the architecture records and enforces one computed
runtime-boundary exclusion. No bare `fn?` registration remains in schema EDN.

## Triage 2026-07-27

- **OPEN-CURRENT.** `src/seon/schema/flow.edn:12-14,17,27,41,43,45-47,52-53,57`
  still declares all thirteen callback shapes as bare `fn?`, outside the
  `[:fn ...]` honesty check in `src/seon/schema/edn.clj:193-248`.
