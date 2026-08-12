---
type: issue
status: open
severity: friction
tags: [issue, diagnostics, error, class-kill]
---

# Prevent diagnostics from collapsing evidence into noise or absence

## Problem

Several boundaries discard the member, operation, expected shape, offending
value, cause, or evidence availability they already possess. Consumers then
report `unknown error`, invent failure from unavailable evidence, or return
ordinary absence for an invalid request.

## Evidence

Eleven open issues span 2026-07-31 through 2026-08-10:
[[a-never-run-agents-context-cannot-be-inspected]],
[[a-wrong-arity-call-reports-a-missing-namespace]],
[[database-read-admission-treats-invalid-identities-as-absence]],
[[database-request-shape-errors-bypass-public-contracts]],
[[debug-pages-invent-wedged-runs]],
[[dev-mcp-envelopes-misdirect-errors-and-sprawl-status]],
[[malli-registration-errors-hide-the-offending-var]],
[[nested-error-data-hides-the-throw-site-message]],
[[predicate-schema-violations-humanize-to-unknown-error]],
[[sci-analysis-ex-data-carries-a-symbol-nothing-reads]], and
[[status-reports-a-live-mcp-proven-prepl-unreachable]].

Recent closures show the same class on 2026-08-07, 2026-08-08, and
2026-08-10 in [[archive/render-web-tests-read-a-missed-flow-ping-as-state]],
[[archive/a-fault-notice-says-it-interrupted-run-with-no-run]], and
[[archive/concurrency-receipt-diagnostic-classifies-success-as-failure]].

## Owner

The flat error/evidence constructors and the state queries from which
diagnostics are derived.

## Acceptance

- One flat constructor requires layer, operation/member, expected shape,
  offending value or identity, cause, and evidence availability when known.
- Boundary adapters only add context; they cannot replace or drop existing
  fields.
- State diagnostics use the same query/predicate as the transition and model
  unavailable evidence as typed unknown, never absence, success, or failure.
