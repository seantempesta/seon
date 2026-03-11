---
type: capability
status: complete
tags: [vision, agent]
---
# MCP Server Resilience

The MCP server (the bridge between Claude and the running system) stays responsive even when evals hang or initialization is slow. Async dispatch, cancellation of stuck evaluations, and non-blocking init ensure the control channel never deadlocks.

## What Exists

- Response queue atom with async future dispatch
- Running-eval atom tracks active evaluations
- Cancel-running-future for hung evals
- Main loop is non-blocking
- Orchestrator session initialization runs in a future

## Gaps

None.

## Related

- Components: [[components/dev-tools]]
- PRDs: [[prds/mcp-resilience/prd]]
