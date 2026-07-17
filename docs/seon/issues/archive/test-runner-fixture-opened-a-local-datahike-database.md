---
type: issue
status: resolved
tags:
  - testing
  - database
  - runtime
---

# Test runner fixture opened a local Datahike database

## Failure

The runner self-test still created a pod-local in-memory Datahike connection,
assigned removed `seon.db/*conn*`, and passed `:seon.db/conn` into the test
runner. Those APIs disappeared with the single JVM authority cut, producing
eight compile warnings and invalid runtime calls under Bun.

## Resolution

`seon.test.runner` records through the ambient authority session only. Its
focused self-test now replaces that public asynchronous write at the boundary,
inspects the single transaction request, and returns ordinary transaction or
error data. Empty selections produce no transaction and therefore no invented
transaction report. The runner's response schema accepts either the ordinary
transaction report or ordinary database error value.

## Evidence

- `bin/test-cljs --test=seon.test.runner-test`: 12 tests, 47 assertions, zero
  warnings, failures, or errors under Bun.
