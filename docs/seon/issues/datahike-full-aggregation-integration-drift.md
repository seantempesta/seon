---
type: issue
status: open
severity: friction
tags: [issue, database, flow]
---

# Datahike full aggregation has optional integration drift

## Problem

The complete maintained Datahike aggregation reaches optional integration
surfaces beyond the reactive-read and query-cache contract. Retained full-run
logs name Stratum valid-time assertions, Java bindings, API documentation,
HTTP JSON handling, purge behavior, and query-planner probes, but those logs
are dated 2026-07-16 and predate the maintained reactive/cache revision. They
do not prove that those failures reproduce now.

The two planner probes that can directly invalidate reactive query semantics
were rerun at revision `3af6e46e` in both persistent-set and hitchhiker-tree
profiles. One test initially proved its query assertions but leaked its owned
connection during deletion; the fixture now releases before deleting. Both
planner selectors pass: four tests and twelve assertions in total. The focused
weighted-LRU and query-cache profiles pass 162 tests and 990 assertions, and
the CLJS gate passes 138 tests and 951 assertions.

## Acceptance criteria

- Reproduce each remaining named integration failure with its smallest owning
  runner; do not infer current failure from the dated aggregate logs.
- Separate obsolete fixture or platform assumptions from production defects.
- Repair each owning mechanism without weakening assertions or coupling it to
  reactive cache inheritance.
- Run the complete maintained Datahike aggregation after the focused owners are
  green and record its final test and assertion totals.
