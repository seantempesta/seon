---
type: issue
status: open
severity: cleanup
tags: [issue, web, architecture]
---
# Dead Code: render/example.clj

## Problem

`render/example.clj` has no callers and no tests. Likely a prototype that was never wired into the system.

## Where

- `src/seon/render/example.clj` — no callers, no tests

## Acceptance Criteria

- File deleted
- No remaining references to the namespace
- No test failures after removal

## Related

- [[components/renderer]]
