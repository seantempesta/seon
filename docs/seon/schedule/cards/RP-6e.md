---
title: "Card RP-6e: A function with a malformed contract is stored with its contract as data"
type: reference
status: active
created: 2026-10-08
---

# RP-6e A function with a malformed contract is stored with its contract as data

New row (found while carding RP-6b; group B). CLAUDE.md: "Preserve malformed authored contracts as data; never arm them
or substitute stale wrappers." Redesign §2: "A malformed authored contract is stored as data (P4)." P4 (`0667ee448`)
made the call side keep the function callable; the storage side still drops the definition. Lane: Sonnet. Cited at HEAD
`e7f120672`.

## What was measured (default, agent `cards-recording-3`, 2026-10-08)
`(defn bad-contract {:malli/schema [:=> [:cat :no.such/schema] :int]} [x] x)` answers the Var, and its derive throws
`:malli.core/invalid-schema` from `seon.schema/contract-product` (`src/seon/schema.clj:4170`) through
`seon.program/contract-facts` → `seon.fn/contract-facts-row` (`src/seon/fn.clj:3147`) → `seon.turn.batch/write-entity`
(`src/seon/turn/batch.clj:273`). The form's facts are refused whole ("Not admitted to the program", a ~30-frame
diagnostic): the function is not stored at all, so it is lost at the next reacquisition.

## The rule (decided here)
A function whose contract cannot be parsed into contract facts is stored as a program row **without** contract facts
(no `:seon.fn/arities` from the contract), keeping its authored `:seon.fn/spec` text as data; the form gets one finding
`:seon.program.finding/malformed-contract` naming the symbol and Malli's error type. Merge refuses it (merge.md
integrity: contracts), never the agent's branch.

## Files
- `resources/seon/schemas/seon.program.finding.edn`
- `src/seon/turn/batch.clj` (`write-entity`)
- `test/seon/repl_differential_p8_test.clj`

## Schema
```edn
:malformed-contract [:and :seon.program.finding/base
                     [:map [:seon.program.finding/type [:= :seon.program.finding/malformed-contract]]
                      [:seon.batch/sym :qualified-symbol]
                      [:seon.error/type :qualified-keyword]]]
```
plus its `:any` entry.

## Functions
- CHANGE `seon.turn.batch/write-entity` (private, `:241`), contract unchanged except the answer map may also carry
  `:seon.batch/finding`: wrap the `sfn/contract-facts-row` call (`:270-274`) so that an `ExceptionInfo` whose
  `(:type (ex-data e))` is a Malli schema error (`:malli.core/invalid-schema`, or any keyword in the `malli.core`
  namespace) answers `{:seon.batch/row (assoc row :seon.fn/spec (pr-str contract)) :seon.batch/finding {…malformed-contract… :seon.error/type (:type (ex-data e))}}`.
  This is a deliberate translation that names its own type (CLAUDE.md's second allowed catch); any other throw
  propagates as today. `pr-str` of the contract form here is the existing storage of `:seon.fn/spec` (a string
  attribute), unchanged.
- CHANGE `seon.turn.batch/derive-form`: collect `:seon.batch/finding` of each by-var answer into the change's
  `:seon.batch/findings` (RP-6d's key).

## Proof
`seon.repl-differential-p8-test/a-malformed-contract-is-stored-as-data` (new):
`"(ns rp6e.a) (defn bad-contract {:malli/schema [:=> [:cat :no.such/schema] :int]} [x] x) (bad-contract 3)"` →
`(bad-contract 3)` answers `3`; the defn's envelope has a finding of type `:seon.program.finding/malformed-contract`;
in the next eval request `(:seon.fn/spec (seon.db/pull (seon.db/db) [:seon.fn/spec] [:seon.fn/sym 'rp6e.a/bad-contract]))`
is the authored form's text, and the row has no `:seon.fn/arities`.

## Reaching tests
`seon.repl-differential-p8-test`, `seon.repl-differential-p4-test`, `seon.turn.batch-test`, `seon.fn-test`.

## Cost
None on the ordinary path.

## Deleted
The ~30-frame "Not admitted" diagnostic for this case.

## Commit
`bin/lane commit <lane> -m "RP-6e: a function with a malformed contract is stored with its contract as data and a finding" --test seon.repl-differential-p8-test --test seon.repl-differential-p4-test --test seon.turn.batch-test resources/seon/schemas/seon.program.finding.edn src/seon/turn/batch.clj test/seon/repl_differential_p8_test.clj`

## Dependencies
RP-6d (the change's findings key). Group B.

## Reset needs
None.
