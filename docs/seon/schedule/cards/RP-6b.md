---
title: "Card RP-6b: Delete the per-form apply stage; a refused fact never marks a form failed"
type: reference
status: active
created: 2026-10-08
---

# RP-6b Delete the per-form apply stage; a refused fact never marks a form failed

Ledger row RP-6b (alias S5-04d, group B). Design: `redo/pass-and-recording.md` §1 ("`entry-evaluation` stamps
`"facts not proposed: …"` on a successful value"), §5 (Admission deletions). Lane: Sonnet. Cited at HEAD
`e7f120672`. The ledger's proof `a-successful-form-is-never-marked-failed` lands here, where the stamp goes.

## What was measured (default, agent `cards-recording`, 2026-10-08)

This historical probe predates FK-SCI. To repeat it after that fork change, run `(ns-unalias *ns* 's)` before the second `require`.

```clojure
(ns rp3probe.a (:require [clojure.string :as s]))
(defn g "first" [] (s/join [1]))      ; value #'rp3probe.a/g, yet its reply carries
                                      ; :diagnostics [{… :seon.error/message "Not admitted to the program: … The stored
                                      ; definition digest of [:seon.fn/sym rp3probe.a/g] is not the one its definition produces …"}]
(require '[clojure.set :as s])        ; the alias retarget later in the same eval request
```

The form succeeded; its program facts were refused at the writer (the digest was computed under the form's row and
checked under the final one: RP-8 fixes that cause), and the refusal is stamped on the form as its error.

## Files

- `src/seon/batch.clj`
- `src/seon/turn/batch.clj`
- `src/seon/turn.clj` (`not-admitted`)
- `resources/seon/schemas/seon.batch.edn`
- `test/seon/repl_differential_p8_test.clj`

## Functions

- DELETE `seon.batch/apply-pending` (private, `src/seon/batch.clj:513`; no caller after RP-6a1) and the folding key
  `:seon.batch/pending`.
- CHANGE `seon.turn.batch/program-request` (`src/seon/turn/batch.clj:866`): drop `:seon.batch/facts form-facts`
  and `:seon.batch/apply apply-facts` from the stage map. In `seon.batch.edn` `:request` delete
  `[:seon.batch/facts …]` and `[:seon.batch/apply …]` (`:250-253`). `form-facts` and `apply-facts` stay as functions:
  `program-facts` (`:599`) and `revalidate` (`:696`, deleted by RP-7) still call them.
- CHANGE `seon.turn.batch/entry-evaluation` (private, `:626`): delete the `facts-error` arm (`:639`); a form whose
  facts were refused keeps its value. Its refusal is delivered beside the value: add
  `facts-error (update :seon.cluster.eval/diagnostics #(str/join "\n" (remove nil? [% (render.value/core-text [facts-error])])))`,
  the shape `seon.turn/with-diagnostic` (`src/seon/turn.clj:3307-3315`) writes until RP-12 stores diagnostics as data.
  The `"nil"` default arm loses its `(nil? facts-error)` condition. Require `seon.render.value` as `render.value` if
  `turn/batch.clj` does not yet (`rg -n 'render.value' src/seon/turn/batch.clj`).
- CHANGE `seon.turn/not-admitted` (private, `src/seon/turn.clj:3317`): it no longer sets
  `:seon.cluster.eval/error`; it only delivers the refusal with `with-diagnostic`. Its message keeps "Not admitted to
  the program …" (RP-7 deletes the function with its last caller).
- CHANGE `seon.turn.batch/read-only?` (`:810-817`) if it reads `:seon.batch/facts-error` to decide anything about
  success: keep its meaning (a form with refused facts is not read-only), no change in behaviour.

## Data

```edn
;; the evaluation row of the successful defn whose facts were refused
{:seon.cluster.eval/ordinal 1 :seon.eval/shown "#'rp6b.a/g"
 :seon.cluster.eval/diagnostics "[{… :seon.error/message \"Not admitted to the program: …\"}]"}   ; no :seon.cluster.eval/error
```

## Proof

`seon.repl-differential-p8-test/a-successful-form-is-never-marked-failed` (new). Probed on default (agent
`cards-recording-3`): `(defn bad-contract {:malli/schema [:=> [:cat :no.such/schema] :int]} [x] x)` evaluates to the
Var while its derive throws `:malli.core/invalid-schema` (`seon.schema/contract-product`, `src/seon/schema.clj:4170`),
and the reply carries a ~30-frame "Not admitted to the program" diagnostic; the stored row's
`:seon.cluster.eval/error` holds that message (`not-admitted`). This refusal does not depend on RP-8. Proof: evaluate
`"(ns rp6b.a) (defn bad-contract {:malli/schema [:=> [:cat :no.such/schema] :int]} [x] x) (bad-contract 3)"`; assert
the `defn` envelope has `:seon.repl/value` and no `:seon.repl/error`, `(bad-contract 3)` answers `3` (P4: a malformed
contract keeps the function), and the evaluation row pulled by the defn's handle
(`[:seon.cluster.eval/id <handle without "result/e">]`) has no `:seon.cluster.eval/error`. After RP-6a1 the derive
throw is a batch diagnostic; after this row nothing marks the form failed.

## Reaching tests

`seon.repl-differential-p8-test`, `seon.turn-test`, `seon.turn.batch-test`, `seon.cluster.host-test`,
`seon.cluster.evaluate-source-test`, `seon.repl-differential-p4-test` (a malformed contract keeps the function: its
assertions on `:error` absence must stay green).

## Cost

None expected beyond deletion.

## Deleted

`apply-pending`, two request stages and their schema members, the "facts not proposed" stamp, `not-admitted`'s error
assignment.

## Commit

`bin/lane commit <lane> -m "RP-6b: the per-form apply stage is gone; refused facts sit beside a successful value, never as its error" --test seon.repl-differential-p8-test --test seon.turn-test --test seon.turn.batch-test --test seon.cluster.host-test --test seon.cluster.evaluate-source-test --test seon.repl-differential-p4-test src/seon/batch.clj src/seon/turn/batch.clj src/seon/turn.clj resources/seon/schemas/seon.batch.edn test/seon/repl_differential_p8_test.clj`

## Dependencies

RP-6a (both slices). Group B.

## Reset needs

None.
