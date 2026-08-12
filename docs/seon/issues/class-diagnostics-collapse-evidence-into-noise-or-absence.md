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

## N5 disposition — 2026-08-12

`seon.error/diagnostic` is the one evidence-complete constructor. It always
materializes layer, operation, member, expected value, offending value, cause,
evidence availability, and evidence under `:seon.error/data`; an unavailable
observation is the typed value `:seon.error/unknown`. Additional boundary data
is merged before those fields and therefore cannot replace or omit them.

The eleven members were dispositioned as follows:

| Member | Disposition | Recurring proof |
|---|---|---|
| `a-never-run-agents-context-cannot-be-inspected` | Deferred at the protected compiled-pull/history seam; exact edit is recorded in the member | `seon.render.web-test` prospective-without-capture case |
| `a-wrong-arity-call-reports-a-missing-namespace` | Converted from its program-graph arglist query | `seon.instrument-test/a-sci-only-arity-miss-names-its-program-graph-arglists` |
| `database-read-admission-treats-invalid-identities-as-absence` | Deferred at protected `seon.db`; exact edit is recorded in the member | focused `seon.db-test` invalid-identity cases |
| `database-request-shape-errors-bypass-public-contracts` | Instrumentation face converted; public database classification deferred at protected `seon.db` | focused `seon.db-test` malformed request cases |
| `debug-pages-invent-wedged-runs` | Deferred at protected `seon.render.web`; exact edit is recorded in the member | focused `seon.render.web-test` held live/dead cases |
| `dev-mcp-envelopes-misdirect-errors-and-sprawl-status` | JVM exception construction converted; the independently reopened problems contract remains in that member outside N5 | `seon.cluster.mcp-test/jvm-exceptions-retain-the-root-location-and-flat-error` |
| `malli-registration-errors-hide-the-offending-var` | Converted from the Var-local Malli registration operation | `seon.instrument-test/registration-failure-names-the-var-and-authored-contract` |
| `nested-error-data-hides-the-throw-site-message` | Converted at `seon.sci.kernel/failure-value` | `seon.sci.eval-test/nested-refusal-keeps-the-throw-site-message-as-structured-evidence` |
| `predicate-schema-violations-humanize-to-unknown-error` | Converted; predicate declarations are query-checked and humanization never invents unknown prose | `seon.schema-test/every-predicate-schema-declares-what-it-accepts` |
| `sci-analysis-ex-data-carries-a-symbol-nothing-reads` | Converted directly from SCI's `:sci.impl/symbol` evidence | `seon.sci.eval-test/analysis-failure-exposes-scis-unresolved-symbol-as-data` |
| `status-reports-a-live-mcp-proven-prepl-unreachable` | Converted from the same reachable-JVM observation used by status | `seon.dev.fresh-operator-test/reachable-prepl-without-roster-evidence-is-not-called-unreachable` |

The owner-directed `bin/test --changed` acceptance gate is deferred until the
compiled-pull-plan fix lands. Only focused namespace runs are admissible in the
meantime.
