---
type: decision
status: active
date: 2026-08-04
tags: [decision, architecture, rendering, observability]
---

# ADR-015: Entity render pairs and one value-rendering bound

## Decision

Apply [the agent record and turn loop PRD §13–§15](../../../prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md).
One entity schema declares one AI/HTML render pair. Scalars share the
entity's block; components and declared derived queries cover whole
concerns. No pair means the default attribute-map printer.

A render function chooses source from its data. Generated source is
evaluated and stored in ordinary system turns. The evaluation schema's
pair renders history through the walk, using stored shown text and the
single `seon.repl/text` grammar. No third form projection or
history-specific formatter exists.

At evaluation time the value renderer applies the profile once.
The database stores shown text, including elisions and requery forms,
plus source, out, and error. Actual result objects remain in each
agent's live SCI context. There is no separate result byte bound,
stored print node, or result blob.

HTML renders the live object without presentation clipping, and uses
shown text after restart. Query-work bounds and evaluation deadlines
remain independent from presentation.

## Consequences

Earlier prompt bytes survive data, profile, code, and JVM changes
because they are saved observations. Inspection distinguishes saved
text from a live object and reports when a requery can no longer reach
that object. Compaction is the explicit wipe and regeneration boundary.

See [UI](../ui.md), [context](../context.md), and
[observability](../observability.md).
