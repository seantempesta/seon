---
title: "Card CS-01b: The block shows proofs and their status"
type: reference
status: active
created: 2026-10-08
---

# CS-01b: The block shows proofs and their status

Depends on: CS-01a (led-cs01a: `seon.context/current-state [database agent-id]`, `:seon.context/current-state`,
`current-state-ai`). Group D. Model: Sonnet.

## Files

- `src/seon/context.clj`
- `resources/seon/schemas/seon.context.edn`
- `test/seon/context_test.clj`

## Functions

Add to `seon.context` (below `current-state-ai`):

```clojure
(defn- proof-status
  "A proof's status from its latest result and its current reach digest."
  {:malli/schema [:=> [:cat [:or :nil :seon.test/result :seon.test/result-unknown] [:or :nil :seon.test/reach-digest]]
                  :seon.context/proof-status]}
  [result current] ...)
```

Rule, in order: no result, or the result has `:seon.error/at` (a `result-unknown`), or `current` is nil, or
`current` ≠ `(:seon.test.member/reach-digest result)` (the key `seon.test/verified?` compares, `test.clj:1603`) →
`:pending`; pass-count > 0 and fail-count + error-count = 0 → `:green`; else `:red`. No `:running`.

```clojure
(defn proofs
  "The proofs section for the task `sym` on `database` (the agent's branch): counts first, then each proof."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/sym]
                  [:or :seon.context/proofs :seon.error/value]]}
  [database sym] ...)
```

Body: `(seon.task/task-edges database sym)` (`task.clj:321`) → `:seon.task/proofs` (a set of test symbols); if
empty answer `nil` (the section is absent). Then ONE `seon.test.runner/reach-digests database (vec proofs)`
(`test/runner.clj:1098`) and ONE `seon.test.runner/latest-results database (vec proofs)` (`test/runner.clj:2125`),
indexed by `:seon.test/sym`; ONE `db/q` for sources:
`[:find ?s ?src :in $ [?s ...] :where [?t :seon.test/sym ?s] [?t :seon.test/source ?src]]`.
A red proof's data is the FIRST of its result's `:seon.test/failures` mapped to
`{:test sym :expected (:seon.test.failure/expected f) :actual (:seon.test.failure/actual f) :message (:seon.test.failure/message f)}`
(each key present only when the failure has it). Any read returning `:seon.error/at` is returned whole.

Change `current-state`: when the held task is pulled, `(assoc :seon.context/proofs (proofs database (:seon.task/sym held)))`
when non-nil, reading `database` (the agent's branch value, where its tests record), not `home`.
Change `current-state-ai`: unchanged (proofs pass through).

## Schemas (`seon.context.edn`, inside the `#:seon.context` map)

```clojure
:proof-status [:enum {:description "A proof's status by the basis rule: no result for the program it now reaches is pending."} :green :red :pending]
:proof-failure [:map {:description "A red proof's first failing report as data."}
                [:test :seon.test/sym] [:expected {:optional true} :string]
                [:actual {:optional true} :string] [:message {:optional true} :string]]
:proof [:map [:seon.test/sym :seon.test/sym] [:seon.test/source :seon.test/source]
        [:seon.context/proof-status :seon.context/proof-status]
        [:seon.context/proof-failure {:optional true} :seon.context/proof-failure]]
:proofs [:map {:description "Counts by status, then each proof in symbol order."}
         [:seon.context/counts [:map-of :seon.context/proof-status [:int {:min 1}]]]
         [:seon.context/proof-list [:vector :seon.context/proof]]]
```

Add `[:seon.context/proofs {:optional true} :seon.context/proofs]` to `:current-state` and `:current-state-view`.
(`:test`, `:expected`, `:actual`, `:message` are the spec's own unqualified keys for this map, so a red proof reads as
`clojure.test` reports do.)

## Returned shape

```clojure
{:seon.context/task {:seon.task/sym tq.cs/shape :seon.task/intent "…"}
 :seon.context/proofs {:seon.context/counts {:green 1 :red 1}
                       :seon.context/proof-list
                       [{:seon.test/sym tq.cs-test/area :seon.test/source "(deftest area …)" :seon.context/proof-status :green}
                        {:seon.test/sym tq.cs-test/shape :seon.test/source "(deftest shape …)" :seon.context/proof-status :red
                         :seon.context/proof-failure {:test tq.cs-test/shape :expected "(= 4 (f 2))" :actual "(not (= 4 5))" :message "doubles"}}]}
 :seon.context/turns {:my.agent/turns-left 7 :seon.task/status :working}}
```

## Proofs

`seon.context-test/a-red-proof-shows-expected-actual-and-message-as-data`: in `with-running-agent`, create task
`tq.cs/shape` and assign it (as the CS-01a test does); through `seon.mcp/call` `eval_clj` as the agent, evaluate
`(ns tq.cs) (defn f [x] (inc x))` and `(ns tq.cs-test (:require [clojure.test :refer [deftest is]] [tq.cs])) (deftest ^{:seon/tasks #{'tq.cs/shape}} shape (is (= 4 (tq.cs/f 2)) "doubles"))`,
then `(my.test/check {})`. Read the block (`block` helper). Assert: `(get-in b [:seon.context/proofs :seon.context/counts])`
= `{:red 1}`; the proof's `:seon.context/proof-failure` has `:message "doubles"` and `:expected` containing `"(= 4"`.

`seon.context-test/a-proof-result-before-the-latest-reached-definition-is-pending`: continue from a green proof
(define `f` as `(* 2 x)`, check, assert `:green`), then redefine `f` as `(* 3 x)` through `eval_clj` without a check;
assert the status is `:pending` and the counts are `{:pending 1}`.

`--test seon.context-test seon.task-ingestion-test`.

## Cost

Proportional to the task's proofs: one edges query, one reach-digest batch (cached by the shared reach cache), one
latest-results batch, one source query. No program-wide work. Measure with `seon.pass-probe/window` around one block
read (code I); report parent vs change.

## Deleted

Nothing.

## Commit

`bin/lane commit cs01b -m "CS-01b: the block shows proofs and their status" --test seon.context-test seon.task-ingestion-test src/seon/context.clj resources/seon/schemas/seon.context.edn test/seon/context_test.clj`
