---
type: issue
status: open
severity: cleanup
milestone: M4
tags: [issue, web, architecture]
---
# Deprecated: web/sse.clj send! Still Has Callers

## Update (2026-03-11)

Callers of `sse/send!` have already been migrated — no production code in `src/` calls the deprecated `send!` function. The function itself still exists in `web/sse.clj` (lines 359-370) marked deprecated. Only the dead function body needs removal now. No migration work remains.

## Problem

`web/sse.clj send!` is explicitly marked deprecated in its docstring, but still has callers using it. The deprecated function should either be removed (callers migrated) or un-deprecated if it's actually needed.

## Where

- `src/seon/web/sse.clj` — `send!` function marked deprecated (still present, no callers)

## Acceptance Criteria

- `send!` function removed from `web/sse.clj`
- No test failures after removal

## Related

- [[components/web-layer]]
