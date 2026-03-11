---
type: issue
status: open
severity: cleanup
---
# Dead Code: repl/graduate.clj

## Problem
`repl/graduate.clj` has no callers. The graduation concept was never connected to anything in the system.

## Where
- `src/seon/repl/graduate.clj` — no callers

## Acceptance Criteria
- File deleted
- No remaining references to the namespace
- No test failures after removal

## Related
- [[components/repl]]
