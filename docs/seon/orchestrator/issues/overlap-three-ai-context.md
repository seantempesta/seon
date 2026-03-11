---
type: issue
status: open
severity: friction
milestone: M3
tags: [issue, architecture]
---
# Overlap: Three AI Context Builders

## Problem

Three namespaces each build AI context text differently with no shared interface:

1. `render/code.clj` -- code-focused context
2. `repl/context.clj` -- REPL context wrapper
3. `graph/context.clj` -- graph-query-based context

Consumers must know which variant to use, and improvements to context building must be applied in multiple places.

## Where

- `src/seon/render/code.clj`
- `src/seon/repl/context.clj`
- `src/seon/graph/context.clj`

## Acceptance Criteria

- Single AI context builder or a shared interface that all three implement
- Consumers have one entry point for context generation
- Context quality is at least as good as the best current variant
- Tests pass

## Related

- [[components/code-graph]]
- [[components/agent-system]]
