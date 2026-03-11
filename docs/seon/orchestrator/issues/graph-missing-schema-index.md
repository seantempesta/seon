---
type: issue
status: open
severity: blocking
milestone: M3
tags: [issue, schema]
---
# Graph Doesn't Index Function Schemas

## Problem

The code graph indexes function names, arglists, docstrings, and dependencies -- but not input/output Malli schemas. Without this, schema-based discovery ([[vision/index]] M2) is impossible. The schema data is available at runtime via `malli.core/function-schemas` but the ingest pipeline doesn't capture it.

## Where

- `src/seon/graph/ingest.clj` — ingest pipeline lacks schema extraction
- `malli.core/function-schemas` — runtime source of schema data

## Acceptance Criteria

- Graph ingest extracts and stores `:malli/schema` metadata for each function
- Schema data is queryable via `graph/query` (e.g., "find functions that accept `:seon.trading/position`")
- Schema-based discovery works end-to-end
- Ingest tests cover schema extraction

## Related

- [[components/code-graph]]
