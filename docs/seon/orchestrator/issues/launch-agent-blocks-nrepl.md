---
type: issue
status: superseded
tags: [issue, agent]
---
# launch-agent!! blocks nREPL thread on MCP abort

## Problem

When an MCP client (Claude Code) aborts mid-session, `launch-agent!!` in `seon.ai.claude` can block the nREPL thread indefinitely waiting for the subprocess to finish. The blocking `deref` on the process future has no timeout, so the nREPL thread stays stuck until the orphaned Claude process eventually exits or is killed.

## Impact

Blocks the nREPL connection, preventing further evaluations until the stuck thread is freed. In practice, the user has to kill the Seon JVM or wait for the orphaned process to time out.

## Likely Fix

Add a timeout to the blocking deref in `launch-agent!!`, and ensure the subprocess is killed when the MCP connection drops. The `seon.ai.claude.sdk` namespace already has process lifecycle management — wire the abort signal through.

## File Refs

- `src/seon/ai/claude.clj` — `launch-agent!!` function
- `src/seon/ai/claude/sdk.clj` — subprocess management

## Severity

friction

## Origin

Surfaced from archive review of `docs/archive/agent-isolation/`

## Superseded (2026-06-28 audit)

ai/claude.clj launch-agent!! nREPL blocking is JVM-paused; the active pod has no nREPL agent launch.
