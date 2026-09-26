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

## Cause (verified 2026-09-26, lane fix-datahike-history-query-opus)

Not a clause-order or bound-input defect, and not introduced by `620721d6`/`f4614c9d`; upstream main
(`249930ac`) has the same code. When an upstream relation binds a scan's entity, the executor seeks
per bound entity instead of reading the planned slice. The planned slice for
`[?run :seon.turn/id "defs-run-b"]` is AVET, whose bounds carry the value, so `build-ground-filter`
(`reference-code/datahike/src/datahike/query/execute.cljc:358` at `4157916b`) omits the value check;
the probe-driven seek then drops it. On a temporal value `execute-group-direct` takes the probe
whenever the probe set is smaller than the estimate (`:1943-1944`) and its entity seek even reads the
AVET index with entity bounds (`:1962`), slicing the whole attribute; a current value takes it past the
2,500x break-even (`:584`, `:215`). An inclusive range pushdown (`[(>= ?n 5)]`) is lost the same way.
Minimal reproduction: 10 entities `:run/kind "x"` (indexed), one `"y"` with a receipt;
`[?r :rcpt/run ?run] [?run :run/kind "x"]` on history answers the `"y"` receipt.

## Fix

Fork `f3a3cb59`: `slice-bounds-filter` re-applies the planned slice's own bounds on probe-driven reads,
and the temporal entity seek reads EAVT. Regression
`datahike.test.query-planner-test/test-probe-driven-read-keeps-the-planned-value-bounds` (14 of 21
assertions red at `4157916b`, green at `f3a3cb59`). Close after root restarts default and
`seon.turn-test/settlement-refuses-a-divergent-program-change-since-opening` passes. Reordering
turn.clj's clauses would have hidden an engine defect every other query can hit.
