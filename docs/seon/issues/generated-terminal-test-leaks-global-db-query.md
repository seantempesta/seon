---
type: issue
status: open
tags: [issue, test, database]
severity: blocker
---

# Generated terminal test leaks the global database query function

## Evidence

The frozen full CLJS gate at `a0fc47b2` ran 1,444 tests / 6,968 assertions
with 11 failures in `seon.db-remote-contract-test`. Every failed read returned
the literal string `"terminal-message"`, and public query arities were missing.

`my.plan-test/generated-terminal-commits-status-and-addressed-message-once`
replaces `seon.db/query` with exactly that stub but restores only `db/pull`, the
message transaction builder, and the ID allocator in its `finally`. Focused
plan tests end before another namespace observes the leak, while the complete
suite deterministically contaminates all later database tests.

## Acceptance

- The test restores `seon.db/query` in its existing `finally` on success and
  failure.
- The focused plan/generate-code gate remains green.
- The complete CLJS gate has no `"terminal-message"` database-contract
  contamination.
