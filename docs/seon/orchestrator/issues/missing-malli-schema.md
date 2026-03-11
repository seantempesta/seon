---
type: issue
status: open
severity: blocking
---
# Functions Missing :malli/schema Metadata

## Problem
Many public functions -- especially in `ai/datalevin.clj` -- lack `:malli/schema` metadata and don't follow map-in/map-out. Until every public function is spec'd, the graph can't do schema-based discovery (VISION.md M1). This is the single biggest blocker to the core primitive: agents discovering and using functions by their contracts.

## Where
- `src/seon/ai/datalevin.clj` — most functions lack schemas
- Codebase-wide — Gemini review consistently flags unschema'd public functions

## Acceptance Criteria
- Every public function has `:malli/schema` metadata with `[:=> [:cat ...] ...]`
- All schemas validate correctly under instrumentation
- Graph ingest picks up the schemas (depends on graph-missing-schema-index being fixed)
- No regressions in existing tests

## Related
- [[components/code-graph]]
- [[components/renderer]]
