---
type: issue
status: superseded
tags: [issue, agent]
---
# No distinction between agent "thinking" and "stuck"

## Problem

The observatory and agent system have no way to distinguish between an agent that is actively thinking (long LLM response) and one that is stuck (infinite loop, blocked on I/O, waiting for dead resource). Both look identical — a process that hasn't produced output recently.

## Impact

Operators can't tell if an agent needs intervention or just needs patience. This leads to either premature kills (wasting work) or delayed intervention (wasting time).

## Design Direction

- Track last-activity timestamps at multiple levels: LLM call start, tool execution, output emission
- Define "stuck" heuristics: no activity for N seconds at any level, subprocess CPU idle, etc.
- Surface in observatory UI: `thinking (45s)` vs `possibly stuck (3m, no activity)`

## File Refs

- `src/seon/ai/claude.clj` — agent session management
- `src/seon/web/agents.clj` — observatory UI

## Severity

friction

## Milestone

[[vision/m5-observable-system]]

## Origin

Surfaced from archive review of `docs/archive/agent-isolation/`

## Superseded (2026-06-28 audit)

Refs missing ai/claude.clj + web/agents.clj; the active pod derives stuck-detection from run-FSM derived-state.
