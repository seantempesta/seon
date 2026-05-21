---
type: issue
status: open
severity: blocking
milestone: M3
tags: [issue, schema]
---
# Functions Missing :malli/schema Metadata

## Problem

Many public functions across the codebase lack `:malli/schema` metadata and don't follow map-in/map-out. Until every public function is spec'd, the graph can't do schema-based discovery ([[vision/index]] M1). This is the single biggest blocker to the core primitive: agents discovering and using functions by their contracts.

## Where

- Codebase-wide — Gemini review consistently flags unschema'd public functions
- Hotspots: `seon.ai.*`, older `seon.health.*` and `seon.trading.*` modules slated for Phase 4 of the datahike migration

## Acceptance Criteria

- Every public function has `:malli/schema` metadata with `[:=> [:cat ...] ...]`
- All schemas validate correctly under instrumentation
- Graph ingest picks up the schemas (depends on graph-missing-schema-index being fixed)
- No regressions in existing tests

## Related

- [[components/code-graph]]
- [[components/renderer]]
