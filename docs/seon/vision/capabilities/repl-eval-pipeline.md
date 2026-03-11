---
type: capability
status: partial
tags: [vision, flow]
---
# REPL Eval Pipeline

Agents eval forms through a pipeline that validates, persists to Datalevin with versioning, and updates the code graph. Form history and current-forms queries work. The pipeline does not yet enforce the vision's constraints (schema presence, no `:any`, map-in/map-out) before accepting forms.

## What Exists

- `eval-form!` evaluates via flow, stores in Datalevin with versioning, updates code index
- Form history and current-forms queries work
- Eval routed through flow topology like all cross-boundary calls

## Gaps

- No constraint enforcement before accepting forms (schema presence, concrete types, map-in/map-out)
- Graduation (`repl/graduate.clj`) has no callers -- dead code
- Filesystem is still the source of truth, not the graph

## Related

- Components: [[components/dev-tools]], [[components/code-graph]]
- PRDs: [[prds/super-repl/prd]], [[prds/agent-repl-interface/prd]]
- Issues: [[orchestrator/issues/dead-repl-graduate]]
