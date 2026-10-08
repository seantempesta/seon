---
title: "Card RP-4: Register a schema, then use it in the same eval request"
type: reference
status: active
created: 2026-10-08
---

# RP-4 Register a schema, then use it in the same eval request

Ledger row RP-4 (alias S5-04g, group C, plus one function in group B). Ruling: "Serial REPL semantics … same-pass
`register!` then write works (`register!` commits like `transact!`)" (`review/owner-rulings.md`). Design:
`redo/pass-and-recording.md` §2 CAPTURE ("register! commits through the world writer like transact!"). Lane: Sonnet.
Cited at HEAD `e7f120672`.

## What was measured (default, agent `cards-recording-2`, 2026-10-08)

```clojure
(ns rp4probe.a (:require [seon.schema :as schema]))
(schema/register! ::item-id [:int {:description "probe"}])   ; 384 ms, 32,400 armed calls
(seon.db/transact! [{::item-id 7}])                            ; refused: "expected an installed attribute, got an undeclared attribute :rp4probe.a/item-id"
(seon.db/q '[:find ?v . :where [_ :rp4probe.a/item-id ?v]] (seon.db/db))  ; refused: uninstalled attribute
```

A second `register!` in a later eval request: 855 ms, 32,409 calls. That cost is task
`seon.schema/schema-registration-rechecks-the-whole-program-per-key` (`resources/seon/tasks/seon.schema.edn:56`), not
this row; this row must not make it worse.

## What exists

`seon.schema/register!` (`src/seon/schema.clj:1665`) admits the form and stages it in the evaluation's registration
delta (`*candidate-forms-overlay*`, `:972`, bound by `schema/call-with-registration-delta`, which
`seon.turn/evaluate-row` wraps around each form, `src/seon/turn.clj:4775`). Nothing is written until the eval request's
recording, where `seon.turn.batch/schema-tx` (`src/seon/turn/batch.clj:419-460`) turns the delta's keys into the schema
rows and Datahike attribute declarations. A `transact!` in the same eval request therefore writes against a branch with
no such attribute.

## The change

Inside an evaluation (a registration delta bound **and** `seon.batch/*world*` holding a connection),
`register!` commits its key's declaration and attribute to the world's connection through the world's writer right
after admission, exactly as a `transact!` in that form would: one transaction, named
`:seon.db.transaction/operation 'seon.schema/register!`. Later forms (and the rest of this form) see the attribute. The
delta keeps the key, so arming (`seon.sci.arm/arming-projection`, `src/seon/sci/arm.clj:17`) and the recording keep
working unchanged; the recording's `schema-tx` for that key then writes nothing new (`program/exact-replacement-tx` of
an identical row is empty and `attribute-change-tx` finds the attribute installed). Outside an evaluation (boot,
`seon.schema.edn` loading, JVM REPL) nothing changes.

## Files

- `src/seon/schema.clj` (`register!`)
- `src/seon/turn/batch.clj` (one new public function; `schema-tx` unchanged)
- `test/seon/repl_differential_p8_test.clj`

## Functions

- ADD `seon.turn.batch/commit-registration!`
  `{:malli/schema [:=> [:cat :seon.batch/world :qualified-keyword :map] [:or :seon.db/transaction-report :seon.db/transaction-refused-error]]}`
  `[world k delta]` (`delta` is the registration delta map, `{:seon.schema.delta/candidate-forms atom}`, which
  `call-with-registration-delta` already takes as a plain `:map`, `src/seon/schema.clj:3664-3671`). Body:

  ```clojure
  (let [conn (:seon.db/connection world)
        request {:seon.sci.batch/delta delta}
        tx (schema-tx request (db/db conn) #{k})]
    ((:seon.db/writer world db/transact!) conn
     (cond-> {:tx-data tx :seon.db.transaction/operation 'seon.schema/register!}
       (:seon.agent/id world) (assoc :seon.db.transaction/agent (:seon.agent/id world)))))
  ```

  `schema-tx` (private, `:419`) already builds exactly the rows and attributes the recording would.
- CHANGE `seon.schema/register!` (`:1665`), contract unchanged: after a successful `register-candidate!`, when
  `*candidate-forms-overlay*` is bound and the world has a connection, call
  `((requiring-resolve 'seon.turn.batch/commit-registration!) world k {:seon.schema.delta/candidate-forms *candidate-forms-overlay*})`, where
  `world` is `@(requiring-resolve 'seon.batch/*world*)` (resolved, not required: `seon.turn.batch` requires
  `seon.schema`). A refused commit is returned as `register!`'s answer (its error value, which the agent sees as the
  form's value); the candidate stays staged, as a refused `transact!` leaves the REPL state. Docstring sentence:
  "Inside an evaluation it also commits the declaration to my branch, so the next form can write and read the key."

## Data

```edn
;; the registration's own transaction on the agent's branch
{:seon.db.transaction/operation seon.schema/register! :seon.db.transaction/agent "cards-recording-2" …}
;; then (seon.db/transact! [{:rp4.a/item-id 7}]) commits; the query answers 7
```

## Proof

`seon.repl-differential-p8-test/register-then-transact-then-read-in-one-pass` (new, the p3 idiom):

```clojure
(let [values (all-values (call "(ns rp4.a (:require [seon.schema :as schema]))
(schema/register! ::item-id [:int {:description \"rp4\"}])
(seon.db/transact! [{::item-id 7}])
(seon.db/q '[:find ?v . :where [_ :rp4.a/item-id ?v]] (seon.db/db))" agent-id running))]
  (is (= 7 (last values))))
(is (= 7 (:seon.repl/value (reply "(seon.db/q '[:find ?v . :where [_ :rp4.a/item-id ?v]] (seon.db/db))" agent-id running))))
```

`all-values` reads every content item of the MCP result (one envelope per form), as `seon.repl-differential-test`
does for multi-form rows. The second assertion proves the recording did not undo or duplicate the registration.

## Reaching tests

`seon.repl-differential-p8-test`, `seon.schema-test`, `seon.turn-test`, `seon.cluster.host-test`
(`a-run-gates-a-definition-on-its-earlier-schema`, `test/seon/cluster/host_test.clj:359`).

## Cost

Expected from the work: one commit per `register!` in an evaluation (~41 KB + 29 KB head, 15-20 ms on default, census
§1) plus `schema-tx` for one key (follows the key and its dependents). It is an effect commit, like the agent's
`transact!`, not machinery. The existing 384-855 ms per `register!` is the task named above and must not grow: report
the probe's `register!` row before and after.

## Deleted

Nothing yet; RP-6a removes the per-form working value that the delta overlay exists to feed.

## Commit

`bin/lane commit <lane> -m "RP-4: register! inside an eval request commits its declaration through the world writer" --test seon.repl-differential-p8-test --test seon.schema-test --test seon.turn-test src/seon/schema.clj src/seon/turn/batch.clj test/seon/repl_differential_p8_test.clj`

## Dependencies

RP-3 (ledger order; `test/seon/repl_differential_p8_test.clj` is created there). `src/seon/schema.clj` is held by
led-ul2 at writing. TX-0 adds the eval request id to this commit's stamp without further change here.

## Reset needs

None.
