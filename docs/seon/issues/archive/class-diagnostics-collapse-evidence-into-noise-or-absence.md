---
type: issue
status: resolved
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
| `a-never-run-agents-context-cannot-be-inspected` | Resolved from the compiled root acquisition and history query; empty is evidence, unavailable is typed unknown | `seon.render.web-test/a-never-run-agents-debug-context-is-labeled-prospective` |
| `a-wrong-arity-call-reports-a-missing-namespace` | Converted from its program-graph arglist query | `seon.instrument-test/a-sci-only-arity-miss-names-its-program-graph-arglists` |
| `database-read-admission-treats-invalid-identities-as-absence` | Resolved from installed schema evidence, including the `IHistory/-origin` chain for temporal database values | `seon.db-test/invalid-read-identities-are-diagnostics-never-absence` and `temporal-database-identities-use-the-origin-schema` |

The 2026-08-13 complete run found one stale consumer of the old absence
semantics. `seon.cluster.prompt-test/prompt-is-derived-append-only-repl-history`
queried the deleted `:seon.cluster.agent/blocks` attribute and treated an empty
result as proof that creation stored no presentation blocks. N5 correctly
returns `:seon.db/invalid-read` with diagnostic cause
`:seon.db/attribute-not-installed`; the regression now asserts that typed
evidence instead of collapsing an unknown member into absence.
| `database-request-shape-errors-bypass-public-contracts` | Resolved at the public database boundary; parsed request evidence names the invoked operation and bad member | `seon.db-test/malformed-public-database-requests-name-the-public-operation` |
| `debug-pages-invent-wedged-runs` | Resolved from the service's observed process set; unavailable observation is typed unknown, never `#{}` | `seon.render.web-test/debug-pages-distinguish-held-live-and-dead-runs` |
| `dev-mcp-envelopes-misdirect-errors-and-sprawl-status` | JVM exception construction converted; the independently reopened problems contract remains in that member outside N5 | `seon.cluster.mcp-test/jvm-exceptions-retain-the-root-location-and-flat-error` |
| `malli-registration-errors-hide-the-offending-var` | Converted from the Var-local Malli registration operation | `seon.instrument-test/registration-failure-names-the-var-and-authored-contract` |
| `nested-error-data-hides-the-throw-site-message` | Converted at `seon.sci.kernel/failure-value` | `seon.sci.eval-test/nested-refusal-keeps-the-throw-site-message-as-structured-evidence` |
| `predicate-schema-violations-humanize-to-unknown-error` | Converted; predicate declarations are query-checked and humanization never invents unknown prose | `seon.schema-test/every-predicate-schema-declares-what-it-accepts` |
| `sci-analysis-ex-data-carries-a-symbol-nothing-reads` | Converted directly from SCI's `:sci.impl/symbol` evidence | `seon.sci.eval-test/analysis-failure-exposes-scis-unresolved-symbol-as-data` |
| `status-reports-a-live-mcp-proven-prepl-unreachable` | Converted from the same reachable-JVM observation used by status | `seon.dev.fresh-operator-test/reachable-prepl-without-roster-evidence-is-not-called-unreachable` |

The four deferred members landed in `d1c2828c9`, `e77ee306f`, and
`fee09f551`. Focused direct regressions are green. The named namespace gates
were run rather than the known-red full suite. `seon.db-test` no longer has
the temporal history/as-of/since failures introduced by N5; its remaining
registration-delta and native-report admission failures are attributed below
so they remain evidence rather than being reported as N5 absence.

## Focused gate attribution — 2026-08-12

- `seon.db-test/edn-backed-reads-return-distinguishable-logical-values` and
  `invalid-edn-backed-storage-is-a-flat-read-error` fail while constructing
  fixture schema because `::ai-declaration` is unavailable to
  `seon.schema.datahike/malli->datahike-*`, before a `seon.db` read executes.
- `seon.db-test/return-map-queries-preserve-ordering-and-limit` fails at the
  same fixture boundary for `::row-id`.
- `seon.db-test/nested-native-reports-admit-bounded-reference-identities`
  enters `seon.sci.admit` and receives the in-flight native-report projection
  rather than the expected bounded database-value identity. The
  acquire-containment lane owns `src/seon/sci/admit.clj`.
- `bin/test seon.ai-test` covers
  `no-history-gauges-retain-current-and-drop-superseded-values`, the acceptance
  witness that queries an installed identity through a history database value.
- `bin/test seon.render.web-test` covers both newly resolved debug-route
  members.

## Overnight fixture follow-up — 2026-08-13

The three attributed `seon.db-test` failures were stale fixture construction,
not read regressions. Their registration delta correctly retained the
synthetic declarations, but the tests asked the canonical JVM-only
`malli->datahike-*` entry points to derive those attributes and then handed
database operations the fixture database's production projection. The fixture
now builds one immutable projection from its complete delta, uses the bridge's
explicit `-in` entry points, and hands that same projection to every synthetic
transaction and read. The three direct test vars pass; the focused namespace
gate is the recurring proof.

The two `seon.cluster.store-transact-test` codec failures were the same class:
their synthetic `::mixed-value` registration was retained in a delta while
the fixture called the canonical bridge entry point and handed transactions a
different projection. That fixture now owns and hands one complete projection
through derivation, encoding, and decoding.
