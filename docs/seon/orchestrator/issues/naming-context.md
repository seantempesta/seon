---
type: issue
status: open
severity: friction
tags: [issue, architecture]
---
# Naming Conflict: "context" Means 4 Different Things

## Problem

"context" is used for 4 unrelated concepts. A developer reading "context" cannot know which system is meant without checking the namespace:

1. `ctx.clj` -- namespace state atoms
2. `graph/context.clj` -- AI text generation
3. `dev/context.clj` -- edit tracking
4. `repl/context.clj` -- REPL wrapper

## Where

- `src/seon/ctx.clj`
- `src/seon/graph/context.clj`
- `src/seon/dev/context.clj`
- `src/seon/repl/context.clj`

## Acceptance Criteria

- Each concept has a distinct, unambiguous name
- Renaming is applied consistently across the codebase (callers, docs, tests)
- No two namespaces use "context" for different concepts

## Related

- [[components/context]]
- [[components/code-graph]]
