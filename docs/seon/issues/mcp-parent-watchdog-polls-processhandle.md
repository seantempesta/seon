---
type: issue
status: open
severity: cleanup
tags: [issue, tooling, operator]
---

# Observe MCP parent exit instead of polling it

## Problem

The MCP bridge wakes a future every five seconds to poll whether its parent
process is alive. Parent exit is directly observable through
`ProcessHandle.onExit`; the fixed sleep is an unjustified clock standing in for
an event the JDK already publishes.

## Evidence

- `script/seon/dev/mcp.clj:763-781` captures a PID, sleeps 5,000 ms, resolves a
  new handle, polls liveness, and repeats forever.
- `script/seon/fresh_operator.clj:1168-1171`, `:1226`, and `:1727` already use
  `ProcessHandle.onExit` in this checkout.
- No MCP test owns parent-exit behavior; the literal appears only in the
  implementation.

## Owner

The source-independent MCP process lifecycle.

## Acceptance

- MCP exit is driven by the captured parent's completion event, preserving the
  correct process identity rather than periodically resolving a PID.
- There is no periodic sleep or tuned liveness interval.
- A child-process test proves the bridge exits after its parent and stays alive
  while that exact parent remains alive.
