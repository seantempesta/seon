---
type: orchestrator
status: active
tags: [orchestrator, issue, index]
---

# Open Issues — Index

The owner's ranked SCHEDULE, maintained by hand. Every top-level open note
appears exactly once with its severity and one named destination (a running
lane or a named future wave). Validate with `bin/issues-index --check`: it
reads the notes plus this file and fails on a missing, duplicated, or
severity-mismatched row, a row naming a note that is no longer open, or a
blank destination. It does not generate this file.

Lifecycle `open → resolved | superseded`; closed issues live in `archive/`.
See `README.md` for the convention.

## Blocker (2)

| Issue | Severity | Lane |
|-------|----------|------|
| [Eval-time schema and test rows have no recurring proof](eval-time-schema-and-test-rows-have-no-recurring-proof.md) | blocker | Core |
| [Register the generic render value schema before instrumentation](fresh-operator-instrumentation-cannot-resolve-render-value-schema.md) | blocker | Core |

## Friction (6)

| Issue | Severity | Lane |
|-------|----------|------|
| [Align vendored Malli source with the pinned dependency](malli-vendor-is-ahead-of-pinned-dependency.md) | friction | general |
| [Partial hot reload leaves a live JVM running mixed old and new code](partial-hot-reload-produces-mixed-code-with-no-warning.md) | friction | general |
| [Publish graph transitions instead of polling them in tests](observable-graph-transitions-are-polled-in-tests.md) | friction | Core |
| [Resolve namespace aliases before selecting runtime lint stubs](runtime-lint-does-not-resolve-namespace-aliases.md) | friction | future runtime-lint wave |
| [Permit accretive schema loosenings over existing data](schema-guard-refuses-accretive-loosenings-with-data.md) | friction | schema-lifecycle wave |
| [Give the work launcher's control read SPI priority or rebuild it as a var-process](work-launcher-control-alts-lacks-priority.md) | friction | flow-protocol wave |

## Cleanup (3)

| Issue | Severity | Lane |
|-------|----------|------|
| [Give Flow configuration dials one registration owner](flow-config-dials-have-two-registration-owners.md) | cleanup | Core |
| [Unify the nested-data walk shared by admission and rendering](value-admission-render-walk-overlap.md) | cleanup | general |
| [Remove or implement monitor-graph's throwing command proc](monitor-graph-command-proc-throws.md) | cleanup | flow-protocol wave |
