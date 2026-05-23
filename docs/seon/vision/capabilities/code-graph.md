---
type: capability
status: partial
tags: [vision, schema]
---
# Queryable Code Graph

The system stores functions, schemas, call edges, and namespace dependencies as queryable data in Datahike. This is the foundation for the vision's core primitive: given a data shape, find all functions that accept or produce it. The graph works today for renderer discovery; full schema-based composition discovery is not yet built.

## What Exists

- Graph stores functions, specs, call edges, namespace dependencies in Datahike
- `functions-with-output-key` powers renderer discovery
- Spec linking via `:malli/schema` metadata works for registered spec keywords

## Gaps

- Functions with inline or anonymous schemas get no spec link in the graph
- Only the first keyword from `[:cat ...]` is extracted as input spec
- Full Malli schema form is not stored structurally (only keyword references)
- M2 (schema-based function discovery for arbitrary composition) not implemented

## Related

- Components: [[components/code-graph]]
- PRDs: [[prds/graph-cleanup/prd]], [[prds/spec-driven-rendering/prd]]
- Issues: [[orchestrator/issues/graph-missing-schema-index]]
