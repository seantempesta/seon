---
type: issue
status: open
severity: cleanup
tags: [issue, testing, storage]
---

# Autocomplete test roots depend on a framework after-hook

## Problem

The deleted autocomplete suite created a PID-scoped
`tmp/autocomplete-test-*` directory whose only cleanup was a framework
after-hook. An interrupted suite never invoked it.

## Evidence

At commit `fb5f64123^`,
`test/seon/repl/autocomplete_test.cljs:14-21` removes and recreates the fixture
directory in `use-fixtures :before` and deletes it only in `:after`. The
emergency transcript recorded dozens of these roots before deletion.

## Owner

Any fresh autocomplete/export filesystem fixture that replaces the deleted
pod suite, plus the suite process lifecycle.

## Acceptance

- The actual asynchronous fixture owns cleanup in `finally`, not only a
  framework after-hook.
- The suite exit owner records and reaps the exact root after children exit.
- Interrupted and failed focused-suite proofs leave no unclaimed
  `autocomplete-test-*` root.
