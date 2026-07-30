---
type: orchestrator
status: active
tags: [orchestrator, issue, index]
---

# Open Issues — Index

GENERATED FILE — do not hand-edit. Regenerate with `bin/issues-index`.
Lifecycle `open → resolved | superseded`; closed issues live in `archive/`.
See `README.md` for the convention.

## Blocker (6)

| Issue | Severity | Lane |
|-------|----------|------|
| [Finish deleting the old operator classpath from retained tooling](finish-deleting-the-old-operator-classpath-from-retained-tooling.md) | blocker | general |
| [Refuse incremental publication after an unreported source edit](current-src-incremental-can-bless-unreported-edits.md) | blocker | Core |
| [Make program graph render declarations resolvable](program-graph-render-declarations-name-absent-functions.md) | blocker | Core |
| [Priming indexes with the live JVM's loaded code and records a digest that lies](priming-indexes-with-the-live-jvms-loaded-code.md) | blocker | general |
| [Register the generic render value schema before instrumentation](fresh-operator-instrumentation-cannot-resolve-render-value-schema.md) | blocker | Core |
| [Refuse error-level clj-kondo findings during repository indexing](repository-indexing-admits-clj-kondo-errors.md) | blocker | Core |

## Friction (8)

| Issue | Severity | Lane |
|-------|----------|------|
| [Align vendored Malli source with the pinned dependency](malli-vendor-is-ahead-of-pinned-dependency.md) | friction | general |
| [Eval-time schema and test rows have no recurring proof](eval-time-schema-and-test-rows-have-no-recurring-proof.md) | friction | general |
| [Name database-value and transaction-data contracts](database-and-transaction-boundaries-use-anonymous-any-contracts.md) | friction | Core |
| [Partial hot reload leaves a live JVM running mixed old and new code](partial-hot-reload-produces-mixed-code-with-no-warning.md) | friction | general |
| [Publish graph transitions instead of polling them in tests](observable-graph-transitions-are-polled-in-tests.md) | friction | Core |
| [Remove deleted CLJS tools from the development MCP](development-mcp-advertises-deleted-cljs-tools.md) | friction | general |
| [The issues-index checker disagrees with the schedule convention](issues-index-checker-disagrees-with-the-schedule-convention.md) | friction | general |
| [`seon.cluster/reset!` shadows `clojure.core/reset!`](cluster-reset-shadows-clojure-core-reset.md) | friction | general |

## Cleanup (4)

| Issue | Severity | Lane |
|-------|----------|------|
| [Give Flow configuration dials one registration owner](flow-config-dials-have-two-registration-owners.md) | cleanup | Core |
| [Remove the deleted REPL launcher from cluster docstrings](fresh-cluster-docstrings-teach-deleted-bin-repl.md) | cleanup | general |
| [Retire the pod-era loadable-skills component description](loadable-skills-component-describes-deleted-pod-importer.md) | cleanup | agent |
| [Unify the nested-data walk shared by admission and rendering](value-admission-render-walk-overlap.md) | cleanup | general |
