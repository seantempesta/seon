---
type: issue
status: resolved
severity: blocker
tags: [issue, flow, schema]
---

# Replace bare flow callback predicates with honest contracts

## Problem

Ten converted `seon.flow` callback declarations remain bare `fn?` symbols.
They are readable Malli forms, but they have no constructible generator and do
not meet the B2 generative-honesty design for function-valued boundaries.

## Evidence

`resources/seon/schema/flow.edn:12-16,27,42,44,46-47,55` retains bare `fn?`
forms for `work-fn`, `deliver!`, `commit-fault!`, `commit-drop!`,
`read-core-error-mode`, `panic!`, `plan-step-fn`, `fix-step-fn`,
`read-sources`, and `compile-namespace-fn`.

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

- **OPEN-CURRENT.** The then-current schema EDN declared thirteen callback
  shapes as bare `fn?`, outside the
  `[:fn ...]` honesty check in `src/seon/schema/edn.clj:193-248`.

## Triage 2026-07-29

**REAL-BUT-QUEUED — Flow/schema honesty rung.** Ten bare `fn?` declarations
remain in the current resource file, but these are process-local callback
boundaries and do not block today’s live spine.

## Resolution

Re-verification confirmed ten current callbacks; the original thirteen-count
was stale because `read-facts`, `step-fn`, and `stopped!` no longer exist.
Each remaining callback now names a truthful `:=>` input/output contract.
Ignored callback returns use one closed ordinary-value union rather than
`:any`; the work callback's genuinely polymorphic result references the
existing SCI-admission value boundary.

The recurring `seon.flow-test` gate compiles each callback through the current
schema population, generates a function and its arguments at fixed seeds,
invokes the function, and validates the result against the declared output.
`bin/test seon.flow-test` ran 19 tests and 117 assertions with zero failures
or errors. Resolved in the commit that archives this note.
