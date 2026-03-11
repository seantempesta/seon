---
type: issue
status: open
severity: cleanup
---
# Dead Code: agent/helpers.clj

## Problem

Every function in `agent/helpers.clj` throws "not yet migrated." Zero callers anywhere in the codebase. The file is dead weight.

## Where

- `src/seon/agent/helpers.clj` — all functions throw on call

## Acceptance Criteria

- File deleted
- No remaining references to the namespace
- No test failures after removal

## Related

- [[components/flow-topology]]
