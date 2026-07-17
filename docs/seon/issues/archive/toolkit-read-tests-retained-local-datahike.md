---
type: issue
status: resolved
tags:
  - testing
  - database
  - toolkit
---

# Toolkit read tests retained local Datahike

## Failure

The `my.data` and `my.ns` tests still opened pod-local in-memory Datahike
connections, assigned the removed `seon.db/*conn*`, and called removed local
provenance helpers. In addition, `my.ns/functions` synchronously consumed the
Promises returned by the authority-facing `seon.db/query` and `seon.db/pull`.

## Resolution

The tests now supply ordinary query and pull results at the public `seon.db`
seam and explicitly await toolkit reads. `my.ns/functions` is one asynchronous
function over the existing request and response shapes; it awaits both reads
against the same optional database value. No local database or compatibility
path remains.

## Evidence

- `bin/test-cljs --test=my.data-test --test=my.ns-test`: 10 tests, 54
  assertions, zero warnings, failures, or errors under Bun.
