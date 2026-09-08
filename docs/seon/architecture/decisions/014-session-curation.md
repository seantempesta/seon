---
type: decision
status: superseded
date: 2026-08-04
tags: [decision, architecture, runtime, curation]
---

# ADR-014: Session curation — superseded by compaction

The revision/proof/adoption mechanism is superseded by
[the agent record and turn loop PRD §14](../../../prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md).
Context grows as stored evaluations in ordinary system and agent turns.
Earlier shown text remains unchanged.

Compaction explicitly retracts the agent's evaluations. The next system
turn regenerates the opening from current record facts through the same
algorithm. There is no manual curation, revised-history adoption, or
superseding-turn path to implement.

[Agent runtime](../agent-runtime.md) owns that lifecycle;
[observability](../observability.md) states the forensic limit after
evaluations have been removed.
