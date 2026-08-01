---
type: issue
status: open
severity: friction
tags: [issue, tooling, mcp, operator]
---

# Fence the MCP parent watchdog by captured process identity

## Problem

The MCP watchdog captures a `ProcessHandle`, but this runtime's non-child
`ProcessHandle.onExit` implementation registers its reaper by PID without the
captured start time. If the parent exits and its PID is reused before the
reaper's first liveness sample, the MCP child follows the replacement process
and remains orphaned until that unrelated process exits.

## Evidence

- `script/seon/dev/mcp.clj:873-886` captures the parent handle, then calls its
  `onExit` future. The ordinary parent-exit integration test at
  `test/seon/dev/mcp_bridge_test.clj:442-528` confirms the no-reuse case.
- The exact runtime is OpenJDK 26.0.1. `javap -p -c
  java.lang.ProcessHandleImpl` shows that a handle stores both `pid` and
  `startTime`, and `isAlive` compares both, but `onExit` calls
  `completion(pid, false)` without `startTime`.
- `javap -p -c 'java.lang.ProcessHandleImpl$1'` shows the non-child fallback:
  `waitForProcessExit0(pid, false)` returns the polling path, whose first
  `isAlive0(pid)` result becomes the baseline start time. It detects only a
  later change from that baseline. The MCP process cannot `waitpid` its own
  parent, so this is the relevant path.
- Therefore a PID already reused before that first `isAlive0` sample is
  indistinguishable from the original parent to the future. This is a direct
  inference from the installed runtime implementation; forcing OS PID reuse
  was not attempted during the frozen gate.

## Owner

The MCP server lifetime contract with the process that launched it.

## Acceptance

- MCP termination is driven by an event tied to the launcher's full process
  identity or transport lifetime, not a PID-only non-child reaper.
- A deterministic test substitutes a handle/reaper seam where the original
  identity is dead and the PID is live with a different start time; the MCP
  exit action still fires.
- The ordinary parent-exit test remains event-driven and contains no polling
  deadline as production logic.
