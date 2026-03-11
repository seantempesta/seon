---
type: issue
status: open
severity: friction
---
# Coupling: graph.ingest Depends on seon.render

## Problem

The ingest pipeline (which scans code) has a dependency on the rendering system. Ingest should not need to know about rendering -- it's a code analysis tool, not a presentation layer.

## Where

- `src/seon/graph/ingest.clj` — depends on `seon.render`

## Acceptance Criteria

- `graph/ingest.clj` has no dependency on `seon.render`
- Whatever render-related data ingest was extracting is either moved to render's responsibility or extracted without the dependency
- Ingest tests pass without render loaded

## Related

- [[components/code-graph]]
- [[components/renderer]]
