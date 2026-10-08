---
title: "Card RP-6c: A value def over a function retires its row"
type: reference
status: active
created: 2026-10-08
---

# RP-6c A value def over a function retires its row

Ledger row RP-6c (alias S5-04e, group B). Design: `redo/pass-and-recording.md` §2 FOLD ("value def over a fn retires
its row"). Lane: Sonnet. Cited at HEAD `e7f120672`.

## What exists
`seon.turn.batch/write-entity` (`src/seon/turn/batch.clj:241-277`) answers
`{:seon.batch/private {… :seon.batch/join :value :seon.batch/reason :not-a-function}}` for a root write whose value is
not a function or test (`:262-265`), and `derive-form`'s reducer (`:337-358`) records it as `[:private …]`. A stored
function row of the same symbol stays: after `(defn f [] 1)` in one eval request and `(def f 5)` in a later one, the
program still holds `f` as a function while SCI holds `5`, and the next acquisition reinstalls the function (the REPL
law broken: the stored program must be the final state).

## Files
- `src/seon/turn/batch.clj`
- `test/seon/repl_differential_p8_test.clj`

## Functions
- CHANGE `seon.turn.batch/derive-form` (`:304`), contract unchanged: in the `:bind` arm of the reducer, when the
  final write's `private` has `:seon.batch/reason :not-a-function`, answer `(assoc m sym [:removed])`. No stored-row
  check is needed: `removal-facts` (RP-1a) retracts nothing for an unstored subject (its `stored?` test, `:498-500`),
  and `fold-change` (`:543-561`) marks the symbol `:removed` over an entity an earlier form of the same eval request
  proposed, so `(defn f …)` then `(def f 5)` in one eval request stores nothing. The `:removed` tag already flows into
  `:seon.batch/removals` (`:362`).
- No schema change.

## Data
```edn
;; eval request 2: (def f 5) over the stored function rp6c.a/f
{:seon.batch/change {:seon.batch/removals [rp6c.a/f] :seon.batch/entities [] …}}
;; recording: [[:db/retractEntity [:seon.fn/sym rp6c.a/f]]]
```

## Proof
`seon.repl-differential-p8-test/a-value-def-over-a-function-retires-its-row` (new, p3 idiom):
```clojure
(reply "(ns rp6c.a) (defn f [] 1)" agent-id running)
(reply "(def f 5)" agent-id running)
(is (nil? (:seon.repl/value (reply "(seon.db/pull (seon.db/db) [:db/id] [:seon.fn/sym 'rp6c.a/f])" agent-id running))))
(is (= 5 (:seon.repl/value (reply "f" agent-id running))))
```
On HEAD the pull answers the row.

## Reaching tests
`seon.repl-differential-p8-test`, `seon.turn.batch-test`, `seon.turn-test`, `seon.repl-differential-test`.

## Cost
One `entid` seek per value def at the writer (`removal-facts`' `stored?`), microseconds.

## Deleted
Nothing.

## Commit
`bin/lane commit <lane> -m "RP-6c: a value def over a stored function retires its row" --test seon.repl-differential-p8-test --test seon.turn.batch-test --test seon.turn-test src/seon/turn/batch.clj test/seon/repl_differential_p8_test.clj`

## Dependencies
RP-6a (derive at the end), RP-1a (removal facts that never refuse). Group B.

## Reset needs
None.
