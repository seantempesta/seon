---
type: issue
status: open
severity: blocker
created: 2026-09-26
tags: [issue, datahike, query, history, turn, correctness]
---

# A history query drops a bound input depending on clause order

`seon.turn/declaration-written-by-run?` (`src/seon/turn.clj:1145`) asks the history database whether a
declaration and a receipt of `run-id` were asserted in one transaction. On default (8e217a254, Datahike fork
`reference-code/datahike` at 4157916b), that query answers the receipt of ANOTHER run. The bound input
`?run-id` no longer constrains the last clause. So `declaration-diverged-since-open?` reads every concurrent
change as the run's own write, and settlement admits a divergent program change it must refuse
(`::program-row-changed-after-open`).

## Evidence (isolated branch of default, 2026-09-26 ~02:00Z)

Two runs a and b open, then run a settles `my.defs.shared/scratch`. In the query below, only run a has a
receipt.

```clojure
;; history db, inputs :seon.fn/sym 'my.defs.shared/scratch "defs-run-b"
[:find ?receipt . :in $ ?a ?v ?run-id :where
 [?declaration ?a ?v] [?declaration _ _ ?tx true]
 [?receipt :seon.eval/shown _ ?tx] [?receipt :seon.cluster.eval/run ?run] [?run :seon.turn/id ?run-id]]
;; => 38380 (run a's receipt), from seon.db/q and from raw datahike.api/q alike
;; the same clauses reordered (run clause first) => nil, the correct answer
;; unbound :find ?receipt ?run-id => #{[38377 "defs-run-a" …]}
```

Red it causes: `seon.turn-test/settlement-refuses-a-divergent-program-change-since-opening` (`:1546`/`:1550`).
The divergent row commits and replaces the source. On the round-1 JVM (2c9d6aa3b) the same REPL
probe refused it.

## Owner

The Datahike fork's planner/executor (`datahike.query.execute/execute-fused-scan-rel`; the fork's
`620721d6`/`f4614c9d` changed variable-attribute scans). Fix the engine, add its regression in the fork, then
rerun the turn-test member. Reordering turn.clj's clauses would hide an engine defect every other query
can hit.
