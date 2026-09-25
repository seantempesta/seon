---
type: issue
status: open
severity: performance
created: 2026-09-26
tags: [issue, db, writer, arity]
---

# report-arity-comparison costs hundreds of ms per function-row write

`seon.db/report-arity-comparison` spends 287 ms of one function-row write (fast-harness design measurement) and 648 ms of a
fixture's 1.09 s program-row write (write-back S5 landing). Canonical fixture writes cost 110–150 ms and a function-row write
~427 ms, so this one check sets how many writes a 5 s test can hold. Earlier sighting: db.clj arity check rebuilds its
whole-program input on a branch's first write (1.2 s). Owner fix: the comparison follows the changed rows only (cost follows
changed inputs), reusing the projection/arity derivation keyed by content.
