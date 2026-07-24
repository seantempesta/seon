---
type: issue
status: open
severity: major
tags: [issue, runtime, test]
---

# Runtime admission stale-basis test retains the CLJS lock

## Problem

The automatic widened `bin/test-cljs` gate reaches
`seon.runtime.admission-test/stale-basis-repairs-from-history-delta-and-publishes-that-generation`,
then fails because the fixture has replaced `seon.db/db` with a value whose
zero-arity function slot is absent. The async test calls `done` more than once
and Bun stops advancing while the parent script retains `tmp/test-cljs.lock`.

R52 terminated the hook through the parent `bin/test-cljs` process after more
than four minutes without a new test. The script's trap reaped its Bun child;
the exact R52 namespaces then completed normally.

## Evidence

- Failure:
  `TypeError: seon.db.db.cljs$core$IFn$_invoke$arity$0 is not a function`
- Runner warning: `Async test called done more than one time.`
- Exact R52 gate after cleanup: 22 tests, 103 assertions, zero failures/errors.

## Acceptance

- The stale-basis fixture supplies the same callable `seon.db/db` contract as
  production or scopes its replacement so no later async continuation sees a
  non-callable value.
- The test owns exactly one terminal `done` call.
- Full `bin/test-cljs` finishes, reports the failure or success, and releases
  `tmp/test-cljs.lock` without operator intervention.
