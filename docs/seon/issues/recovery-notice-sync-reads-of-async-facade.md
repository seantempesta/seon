---
type: issue
status: open
severity: blocker
tags: [issue, database, pod]
---

# Recovery notice derivation read the async db facade synchronously

## Problem

`seon.runtime.recovery`'s notice half (`anchor-rows`,
`repaired-agent-runs`, `interrupted-run-turns`, `later-run?`,
`pending-notices`, and the `db/entity` anchor read) consumed the
now-asynchronous `seon.db` facade as if it were synchronous. `db/query`
and `db/entity` return Promises, so `later-run?` was
`(boolean <Promise>)` — always true — which made every recovery notice
vanish immediately, and the remaining sites sorted/kept over Promise
objects instead of rows.

## Evidence

Audit: `docs/prds/database-authority-mesh/research/cleanup-audit-duplicate-interfaces-2026-07-20.md`
§seon.runtime.recovery (former lines 553, 563, 578, 595, 640).
Regression test
`later-run-presence-is-awaited-not-a-truthy-promise` in
`test/seon/runtime/recovery_test.cljs` demonstrates the falsifier: with
no later run, the sync form returned true; the async form returns false.

## Fix

Whole notice half migrated to the `^:async`/`await` pod idiom in place:
each helper awaits its query, propagates `:seon/error` values, and
`pending-notices` is `^:async` returning
`[:or [:vector ::notice] ::db/error]`. No external caller changed —
`pending-notices` currently has no first-party call site (the root
canvas/AI-twin read model consumes it later). Tests added for notice
derivation, clearing on a later run, error passthrough, and the
`later-run?` boolean regression.
