---
title: "Card RP-9: A nested definition is saved from its own span; one closing over a local is live-only with a note"
type: reference
status: active
created: 2026-10-08
---

# RP-9 A nested definition is saved from its own span; one closing over a local is live-only with a note

Ledger row RP-9 (P9, group B). Owner answer (2026-10-08 evening): "a `defn` nested inside another form is saved from
its own source span (SCI's write hook plus the analyzer's span) like any definition, unless it closes over a local,
which stays live-only with a short note to define it at top level." Lane: Sonnet. Cited at HEAD `e7f120672`.

## What was measured (default, agent `cards-recording-3`, 2026-10-08)
```clojure
(ns rp9probe.a)
(do (println "effect") (defn inner [] 7))   ; stored: {:seon.fn/source "(defn inner [] 7)"}  (already its own span)
(let [k 2] (defn closes [] k))              ; not stored, and the reply says nothing
```
The first half of the ruling already holds (`seon.turn.batch/write-entity` takes the nested row by its own offset,
`src/seon/turn/batch.clj:252-261`); this row adds the guard test. The second half is silent today:
`write-entity` answers `{:seon.batch/private {… :seon.batch/reason :captures-locals :seon.batch/captured #{k}}}`
(`:266-268`) and nothing reaches the agent.

## Files
- `resources/seon/schemas/seon.program.finding.edn` (`:live-only`)
- `src/seon/turn/batch.clj`
- `test/seon/repl_differential_p8_test.clj`

## Schema
```edn
:live-only [:and :seon.program.finding/base
            [:map [:seon.program.finding/type [:= :seon.program.finding/live-only]]
             [:seon.batch/sym :qualified-symbol]
             [:seon.batch/captured [:set :symbol]]
             [:seon.error/fix :string]]]
```
`:seon.error/fix` is the short note, fixed text: `"Define it at top level to keep it."` (agent-facing message rule:
one short fixed message, data first).

## Functions
- CHANGE `seon.turn.batch/derive-form` (`:304`): for each by-var answer whose private reason is `:captures-locals`,
  add the `:live-only` finding (sym, captured locals, the fixed fix) to the change's `:seon.batch/findings`.

## Proof
`seon.repl-differential-p8-test/a-nested-defn-is-saved-from-its-own-span` and
`…/a-defn-closing-over-a-local-is-live-only-with-a-note` (new):
- `"(ns rp9.a) (def hits (atom 0)) (do (swap! hits inc) (defn inner [] 7))"`: pull `[:seon.fn/source]` of
  `rp9.a/inner` = `"(defn inner [] 7)"` (no `swap!` in it: reacquiring the program runs no enclosing effect).
- `"(ns rp9.b) (let [k 2] (defn closes [] k)) (closes)"`: `(closes)` answers `2`; the `let` form's envelope has one
  `:seon.program.finding/live-only` finding with `:seon.batch/captured #{k}`; `rp9.b/closes` has no row.

## Reaching tests
`seon.repl-differential-p8-test`, `seon.turn.batch-test`, `seon.cluster.host-test`.

## Cost
None beyond the finding.

## Deleted
Nothing.

## Commit
`bin/lane commit <lane> -m "RP-9: a definition closing over a local is live-only with a finding; nested definitions keep their own span" --test seon.repl-differential-p8-test --test seon.turn.batch-test --test seon.cluster.host-test resources/seon/schemas/seon.program.finding.edn src/seon/turn/batch.clj test/seon/repl_differential_p8_test.clj`

## Dependencies
RP-6a (findings reach the reply), RP-6d (the change's findings key). Group B.

## Reset needs
None.
