---
type: issue
status: open
severity: blocker
created: 2026-09-17
tags: [issue, reset, query, performance]
---

# The carried query ratio fails after symbol edge retyping

On the isolated `reset-batch` branch, fast 26 ran 125 tests and 900 assertions.
Its sole failure is
`seon.db-test/ten-carried-queries-stay-within-twice-raw-query-cost`:
ten raw queries took 481,540 ns and ten armed Seon queries took 1,178,209 ns.
The exact measured query was warmed on both paths. The separate existing
absolute five-millisecond check passes. No threshold has been relaxed.

This query now reads the schema-reference keyword value directly, without the
old join through schema entity refs. The result is correct. The measurement
establishes a failed relative-cost contract; it does not establish a projection
rebuild or attribute the cost to another lane. The previous projection-rebuild
class was resolved separately in `b80f78a7c`.

The owner has been asked which contract governs: retain the absolute budget
and report the ratio (recommended, about 15 minutes); optimize the wrapper to
retain the two-times-raw ratio (about 1–3 hours); or retain this failure as a
landing boundary. The branch is not ready for merge or reset while this is
unresolved. The canonical test and the reset plan retain the reproducible
query and exact measurements; no default mutation or cold gate was used.
