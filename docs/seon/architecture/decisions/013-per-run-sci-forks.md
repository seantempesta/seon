---
type: decision
status: superseded
date: 2026-08-04
tags: [decision, architecture, runtime, sci]
---

# ADR-013: Per-turn SCI forks — superseded

The fresh-fork-per-turn decision is superseded by
[the agent record and turn loop PRD §14](../../../prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md).
Each agent forks the cluster base once and retains one live SCI context
across turns. Accepted base changes are interned as diffs; private defs,
atoms, and result objects retain their identity within that agent.

The private layer never enters another agent or the base. A JVM restart
loses those objects; durable functions, schemas, and tests rebuild the
program base. Evaluations retain shown text, not serialized private state.

The historical filename remains a link target, not the name of the
current mechanism. [Agent runtime](../agent-runtime.md) owns the current
context lifecycle and diagrams.
