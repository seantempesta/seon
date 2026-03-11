---
type: issue
status: open
severity: cleanup
---
# Deprecated: web/sse.clj send! Still Has Callers

## Problem

`web/sse.clj send!` is explicitly marked deprecated in its docstring, but still has callers using it. The deprecated function should either be removed (callers migrated) or un-deprecated if it's actually needed.

## Where

- `src/seon/web/sse.clj` — `send!` function marked deprecated
- Callers need to be identified and migrated

## Acceptance Criteria

- All callers of `send!` migrated to the replacement API
- `send!` function removed
- No test failures after removal

## Related

- [[components/web-layer]]
