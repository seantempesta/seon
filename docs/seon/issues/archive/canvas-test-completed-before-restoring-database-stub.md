---
type: issue
status: resolved
severity: friction
tags: [issue, cljs, database]
---

# Canvas test completed before restoring its database stub

## Problem

The asynchronous canvas-state test called its `done` continuation before its
`.finally` restored the globally replaced `seon.db/pull` var. The next test
namespace could start against the canvas stub.

## Evidence

The complete ClojureScript gate reported six unrelated database-contract
failures. Four pulls returned the canvas fixture's `{:my.demo/count 3}`, the
two-argument `pull` arity was absent from that stub, and a listener observed a
request from the overlapping prior test.

## Owner

`my.canvas-test/state-awaits-the-remote-pull-before-returning-data` restores its
stub before signaling test completion.

## Acceptance

The complete ClojureScript suite runs sequentially without leaked database
functions or requests.

## Resolution

Commit `671e1777` moves `done` into `.finally` after restoring `seon.db/pull`.
The same race later appeared in
`top-level-core-failure-loads-the-database-crash-policy-only-on-error`: it
called `done` before `.finally` restored `seon.error/record!`, causing the next
render-policy test to observe the stub and fail only in the complete suite.
The regression was repaired at the same owner by restoring all three globals
before calling `done`.
The complete gate passes 1,152 tests and 5,118 assertions with zero failures or
errors.
