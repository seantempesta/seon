---
type: issue
status: open
severity: blocker
tags: [issue, agent, pod, web]
---

# Sequence calls within each agent execution child

## Problem

An agent's turn, context renderers, and Datastar canvas renderers all use that
agent's one execution child. The child intentionally runs one invocation at a
time, but independent callers reach the host concurrently. The host rejects
the later call as a core bug, allowing normal UI rendering to close an agent
run before it opens a turn.

## Evidence

After receipt, canvas, and transcript repairs reloaded, a fresh real task
opened and closed with zero turns: `The agent already has an active
invocation.` Five live Datastar feeds were also using selected renderers through
the same per-agent child. `invoke-plans!` sequences calls inside one batch, but
separate host calls had no shared per-agent ordering.

## Owner

`seon.execution.host/invoke!` is the common seam for compiled turns and
selected renderer calls.

## Acceptance

- Calls for one agent execute in request order without an active-invocation
  refusal.
- Calls for different agents continue concurrently.
- A queued call's absolute deadline still resolves and never kills its active
  predecessor.
- Host tests prove same-agent ordering and existing cross-agent isolation.
- A real agent turn completes while Datastar feeds are open.
