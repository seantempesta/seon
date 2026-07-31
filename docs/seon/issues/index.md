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

## Blocker (8)

| Issue | Severity | Lane |
|-------|----------|------|
| [Attribute evals to the agent's assigned namespace](evals-ignore-the-agents-assigned-namespace.md) | blocker | SCI eval-context owner design gate |
| [Seed the cluster's process row before naming it as provenance](cluster-boot-refuses-its-own-process-provenance.md) | blocker | turn-loop preflight fix lane |
| [Stop the render walk silently dropping entities outside registered families](render-walk-silently-drops-entities-outside-registered-families.md) | blocker | context wave fix lane |
| [Mark value-floor truncation in band instead of printing a false shape](value-floor-truncates-in-band-without-a-placeholder.md) | blocker | context wave fix lane |
| [Carry the message content on transcript decline entries](transcript-decline-entries-drop-the-message-content.md) | blocker | context wave fix lane |
| [Settle one prose owner for messages and eval receipts](transcript-is-a-second-renderer-for-messages-and-receipts.md) | blocker | context wave fix lane |
| [Eval-time schema and test rows have no recurring proof](eval-time-schema-and-test-rows-have-no-recurring-proof.md) | blocker | Core |
| [Register the generic render value schema before instrumentation](fresh-operator-instrumentation-cannot-resolve-render-value-schema.md) | blocker | Core |

## Friction (15)

| Issue | Severity | Lane |
|-------|----------|------|
| [Bind first-party namespaces so value-position reads deref](host-bound-first-party-vars-break-in-value-position.md) | friction | SCI eval-context owner design gate |
| [Let a live config apply reach an armed agent graph](armed-agent-graphs-freeze-config-dials-at-arm.md) | friction | turn-loop preflight fix lane |
| [Give `ai-prose` the ref shape the render walk actually hands it](error-render-puts-its-own-failure-in-agent-context.md) | friction | turn-loop preflight fix lane |
| [Give render token budgets one config owner instead of private dials](render-token-budgets-are-private-dials-no-producer-supplies.md) | friction | context wave fix lane |
| [Make the render wave's properties able to produce their failing cases](render-wave-properties-cannot-produce-their-failing-cases.md) | friction | context wave fix lane |
| [Derive walk family detection from identity, not declaration order](walk-family-detection-depends-on-schema-declaration-order.md) | friction | context wave fix lane |
| [Return the SCI re-arm refusal as a value and seal the guard's invariants](sci-evaluate-throws-when-a-guarded-context-is-re-armed.md) | friction | Core |
| [Clear the floor's residue, duplicate cursors, and marker hand list](value-floor-residue-duplicate-cursors-and-marker-hand-lists.md) | friction | context wave fix lane |
| [Align vendored Malli source with the pinned dependency](malli-vendor-is-ahead-of-pinned-dependency.md) | friction | general |
| [Connect namespace alias and refer targets with refs](namespace-binding-targets-are-symbols-not-refs.md) | friction | future program-graph binding wave |
| [Partial hot reload leaves a live JVM running mixed old and new code](partial-hot-reload-produces-mixed-code-with-no-warning.md) | friction | general |
| [Publish graph transitions instead of polling them in tests](observable-graph-transitions-are-polled-in-tests.md) | friction | Core |
| [Resolve namespace aliases before selecting runtime lint stubs](runtime-lint-does-not-resolve-namespace-aliases.md) | friction | future runtime-lint wave |
| [Permit accretive schema loosenings over existing data](schema-guard-refuses-accretive-loosenings-with-data.md) | friction | schema-lifecycle wave |
| [Give the work launcher's control read SPI priority or rebuild it as a var-process](work-launcher-control-alts-lacks-priority.md) | friction | flow-protocol wave |

## Cleanup (3)

| Issue | Severity | Lane |
|-------|----------|------|
| [Fix the context wave's three small honesty defects](context-wave-leaves-three-small-honesty-defects.md) | cleanup | context wave fix lane |
| [Give Flow configuration dials one registration owner](flow-config-dials-have-two-registration-owners.md) | cleanup | Core |
| [Remove or implement monitor-graph's throwing command proc](monitor-graph-command-proc-throws.md) | cleanup | flow-protocol wave |
