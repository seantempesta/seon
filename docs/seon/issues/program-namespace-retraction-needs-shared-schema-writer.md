---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, program-graph, schema, wave/program-retraction]
---

# Namespace retraction needs the existing schema writer seam

`my.program/remove-ns!` can retract a namespace and its function/test rows
in one transaction. A namespace owning schema declarations must also use
the existing schema attribute reconciliation. Removing just the declaration
entities would leave Datahike attribute declarations behind.

The owner exists at `src/seon/turn.clj`: `row-tx`'s deletion arm calls
`assert-schema-data-unused!`, `schema/projection-without-schema`, and
`schema-attribute-change-tx`. These helpers are private and the file has
concurrent acquisition edits. `seon.schema/unregister!` only stages a
registration delta for later settlement; it is not an immediate writer.

The program operation conservatively returns a flat declaration refusal
with affected subjects and a computed plan when schema keys are in its
namespace set. It changes neither facts nor contexts. This is an explicit
implementation boundary, not a claim that unused schemas have referrers.

Expose the existing deletion transaction-data calculation with its complete
contract, make both callers use it, and retain the operation's actual
`retractEntity` semantics. Do not copy the schema diff into `my.program`.
Prove an unused schema and its Datahike attribute are both removed, while a
schema with live data refuses without changing either SCI context.
