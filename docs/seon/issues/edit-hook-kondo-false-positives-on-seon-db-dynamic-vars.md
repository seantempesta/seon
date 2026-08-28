---
type: issue
status: open
severity: friction
tags: [issue, tooling, edit-hook, clj-kondo]
---

# Edit hook blocks test edits on kondo false positives for real dynamic vars

Observed 2026-08-14 night: any Edit to `test/seon/sci/eval_test.clj` or
`test/seon/db_test.clj` is BLOCKED by the edit hook with error-level
`unresolved-var` findings for `seon.db/*conn*` (exists,
`src/seon/db.clj:73`), `seon.test-support/event-backstop-seconds`
(exists, `test/seon/test_support.clj:24`), and
`seon.db/*read-evidence-sink*`. All are real vars; the flagged lines
pre-exist the attempted edits, so the hook rejects UNRELATED additions
to those files. Likely a stale clj-kondo analysis cache for `seon.db`
and `seon.test-support` (or the hook linting with a cache that never
saw those defs). Consequence tonight: two new class regressions had to
land in fresh namespaces (`test/seon/sci/fork_isolation_test.clj`,
`test/seon/db_immutability_test.clj`) instead of beside their sibling
tests. Fix direction: rebuild/refresh the hook's kondo cache on
first-party source change, or scope error-level blocking to findings
ON THE EDITED LINES.
