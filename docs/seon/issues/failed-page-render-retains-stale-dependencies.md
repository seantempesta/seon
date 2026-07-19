---
type: issue
status: open
severity: blocker
tags: [issue, web, database, flow]
---

# Widen failed page renders to all dependencies

## Problem

A failed Datastar page render retains the subscription's prior narrow
dependency set. A later transaction that would repair the page can therefore be
filtered out forever.

## Evidence

`seon.web.datastar/render-error-patch` returns a serialized error event without
`::dependencies`. `finish-render!` replaces subscription dependencies only when
the completed render contains that key. The current narrow set consequently
survives an error, and both the JVM selective interest and Bun subscription
filter may reject a disjoint repair transaction.

## Owner

The one normalized page-render completion transition in
`seon.web.datastar`. Failure is conservative dependency evidence, not a special
notification or recovery path.

## Acceptance

- Failed, canceled, timed-out, malformed, or incomplete page renders install
  the existing absorbing dependency value `:all`.
- A transaction outside the prior successful dependency set rerenders and
  repairs an already-open error page.
- A later successful render replaces `:all` with its actual Datahike-provided
  dependency union.
- Equivalent sockets share the same failed/successful transition and no second
  retry or recovery channel is introduced.
