---
type: issue
status: open
severity: friction
created: 2026-09-26
tags: [issue, fault, problems, status]
---

# A fault whose failing function is deleted stays open forever

## Evidence (default pid 13262 and 19859, HEAD 61d207c3e, 2026-09-26)

`bin/seon status` reports health `:faulted` with one open fault: signature `9e61d0ee…fe54`, operation
`seon.flow/release-admission!`, `java.lang.ClassCastException`, last at 2026-09-26T03:25:27Z (a JVM before both boots).
69229772b (08:14 local, 2026-09-26) deleted `release-admission!` and the `RefusingBuffer` deftype whose reload
redefinition caused that cast. Nothing can produce the fault again.

`seon.error/resolution` (`src/seon/error.clj:1278`) resolves a fault when its `:seon.instrument/fn` row's definition digest
changed after the latest occurrence, else by `:seon.error/resolved-tx`. A deleted function has no row, so `current` reads
nothing and the rule answers nil: open. Every fault of a deleted function therefore holds cluster health at `:faulted`
until someone calls `seon.problems/resolve!` by hand.

## Owner and acceptance

The owner is `seon.error/resolution`. Deletion is the strongest definition change: the row's retraction after the
latest occurrence resolves it (`:seon.error/resolved-by :seon.program/definition-retracted`), found through the
history view the rule already reads. A recurrence reopens it as today. One regression: record a fault naming a
function, retract the function's row, and the fault leaves `open-faults`.

Until then root can clear this one: `(seon.problems/resolve! conn "9e61d0ee4ec1adc7dde5c22760c755cf79af45e9fa03c855f1d0c2aed198fe54")`
citing 69229772b.
