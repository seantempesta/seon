---
type: issue
status: open
severity: cleanup
---
# Dead Code: Scratch Files in src/

## Problem
`hook_test_scratch.clj` and `dev/hook_test_ns.clj` are scratch/test files that landed in `src/` instead of `tmp/`. They don't belong in the source tree.

## Where
- `src/seon/hook_test_scratch.clj`
- `src/seon/dev/hook_test_ns.clj`

## Acceptance Criteria
- Both files deleted from `src/`
- No remaining references in the codebase
- If any test value exists, move to `tmp/` or `test/` instead

## Related
- [[components/dev-tools]]
