---
type: decision
status: abandoned
date: 2026-01-15
tags: [decision, architecture, archive, flow]
---

# ADR-005: Selective adoption of core.async.flow

This decision is superseded. The active CLJS pod has no core.async topology,
Flow process registry, or second subscriber bus. Agent execution is a data FSM
fold; database commits and bounded replay are the one update channel; the web UI
uses the one render-unit and Datastar feed engine.

The removed design is recoverable from Git at
`runtime-reliability-pre-refactor-2026-07-13`. See [[agent-runtime]], [[ui]],
and [[architecture/decisions/008-database-protocol]].
