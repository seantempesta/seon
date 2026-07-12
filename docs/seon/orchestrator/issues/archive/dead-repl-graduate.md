---
type: issue
status: resolved
severity: cleanup
milestone: M5
tags: [issue, architecture]
---
# Dead Code: repl/graduate.clj

## Problem

`repl/graduate.clj` has no callers from production `src/` code. The graduation concept was never connected to anything in the running system.

## Update (2026-03-11)

File still exists. Has no production callers. However, `test/seon/repl/graduate_test.clj` does test it, and it is referenced in `docs/prds/super-repl/prd.md` as planned functionality. Issue remains open — this is unfinished feature work, not obviously dead code. Deletion would require removing the test file too.

## Where

- `src/seon/repl/graduate.clj` — no production callers
- `test/seon/repl/graduate_test.clj` — test file exists (3 tests)

## Acceptance Criteria

- File deleted
- No remaining references to the namespace
- No test failures after removal

## Related

- [[components/agent-system]]

## Status (2026-06-28 audit): valid but JVM-track is paused — defer until that track resumes.
