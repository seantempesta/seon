---
type: issue
status: resolved
severity: blocker
tags: [issue, test, database, flow]
---

# Restore CLJS global stubs before completing async tests

## Problem

Three `seon.agent.ctx.driver-test` cases called the cljs.test completion
callback before their Promise `.finally` restored global database function
stubs. The next namespace could capture `"page acquisition failed"` as its
supposed original `seon.db/execute-many`, causing a deterministic cascade
through message, remote database, and portable contract assertions.

## Evidence

Before commit `1fbbc7b8e`, the full suite reported five failures in
`seon.agent.message-test`, nine in `seon.db-remote-contract-test`, and four in
`seon.db.portable-test`. The remote request recorder remained empty and every
result carried the exact `"page acquisition failed"` value installed by
`seon.agent.ctx.driver-test/agent-view-direct-acquisition-error-short-circuits`.

Commit `1fbbc7b8e` moves `done` after restoration in each owning `.finally`.
Focused driver, message, remote, and portable checks pass 32, 9, 12, and 62
assertions respectively. Two full unchanged runs then report zero failures.

## Owner

Each asynchronous test that mutates a global Var owns restoration before
cljs.test is allowed to start the next test.

## Acceptance

The driver tests complete only after restoring `seon.db/execute-many` and
`seon.agent.message/recent`; the following message and remote-contract tests
observe their own stubs; and two full runs have the same zero-failure
inventory.
