---
type: issue
status: open
severity: friction
tags: [issue, database, flow]
---

# Datahike full aggregation has optional integration drift

## Problem

The complete maintained Datahike aggregation reaches optional integration
surfaces beyond the reactive-read and query-cache contract. The 2026-07-19 run
reported failures in Stratum virtual-thread assertions, Java bindings,
`test-transact!-docs`, HTTP server JSON handling, purge entity behavior, and a
query-planner probe. These failures are independent of the focused cache gate:
the weighted-LRU and query-cache profiles pass 162 tests and 990 assertions,
and the CLJS gate passes 138 tests and 951 assertions.

## Acceptance criteria

- Reproduce each named integration failure with its smallest owning runner.
- Separate obsolete fixture or platform assumptions from production defects.
- Repair each owning mechanism without weakening assertions or coupling it to
  reactive cache inheritance.
- Run the complete maintained Datahike aggregation after the focused owners are
  green and record its final test and assertion totals.
