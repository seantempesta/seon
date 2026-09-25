---
type: issue
status: open
severity: friction
created: 2026-09-25
tags: [issue, db, turn, performance, agent-platform]
---

# system-plan scans since-views for read-evidence changes on every open

`seon.turn/system-plan` (src/seon/turn.clj:1976) calls `db/read-evidence-changes`
(src/seon/db.clj:1045), and that calls `index-pattern-change` (db.clj:1028-1041) for each
retained pattern. Each call runs `d/datoms` over a since-view, and Datahike filters that
index by transaction (`temporal-datom-filter`, datahike db.cljc:176). The index is not
narrowed, so the cost follows the size of the branch's index rather than the number of
changes.

Measured with 100 ms thread samples of a batched turn fixture branched off default (lane
fix-turn-hang-opus, pid 23282): 45 of 366 samples (about 4.5 s over two opening passes) were
in `index-pattern-change` under `read-evidence-changes` ← `system-plan` ← `system-turn`.

Cost target: proportional to the transactions since the basis. Use the transaction log, or
Datahike's revision per attribute (the `:datahike.cache/attribute-revisions` that
`dependency-revision` already reads, db.clj:867), before scanning datoms.
