---
type: issue
status: open
severity: friction
milestone: M3
tags: [issue, schema, architecture]
---
# Duplication: clj-kondo Analysis Wrapped in 3 Namespaces

## Problem

clj-kondo analysis is wrapped independently in 3 namespaces. Each has its own integration with the same tool, leading to divergent behavior and triplicated maintenance.

## Where

- `src/seon/graph/extract.clj`
- `src/seon/graph/analyzer.clj`
- `src/seon/dev/analysis.clj`

## Acceptance Criteria

- Single canonical clj-kondo wrapper in one namespace
- All three call sites use the shared wrapper
- Consistent analysis behavior across all consumers
- Tests pass

## Related

- [[components/code-graph]]
