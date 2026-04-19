---
type: issue
status: resolved
severity: friction
milestone: M3
tags: [issue, web, schema, architecture]
---
# Coupling: graph.ingest Depends on seon.render

## Resolution

**Resolved.** `src/seon/graph/ingest.clj` has no dependency on `seon.render`. Grepping the ns form and all requires in `graph/ingest.clj` shows no mention of `seon.render`. The coupling no longer exists. Verified by triage 2026-03-11.

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
