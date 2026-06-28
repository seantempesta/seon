---
type: issue
status: superseded
tags: [issue, web, agent]
---
# Observatory: upgrade from polling to true SSE append streaming

## Problem

The agent observatory polls at 1s intervals instead of using true SSE append streaming. This adds unnecessary latency to the monitoring UI.

## Design (from archive)

`docs/archive/agent-observatory/streaming-research.md` contains a complete implementation plan:

- `mode append` instead of full re-render
- `sliding-buffer(50)` for backpressure
- 50-100ms batch windows
- Message IDs for reconnection replay

## File Refs

- `src/seon/web/agents.clj` — current polling implementation
- `docs/archive/agent-observatory/streaming-research.md` — ready design

## Acceptance Criteria

- Agent log updates appear in browser within 100ms (not 1s)
- Reconnection replays missed messages
- No increased server load

## Severity

friction

## Milestone

[[vision/m5-observable-system]]

## Superseded (2026-06-28 audit)

Refs the gone web/agents.clj; the active observatory is the /world Datastar SSE stream.
