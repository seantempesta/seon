---
title: "UL-3a: Delete `seon.instrument/remove!`"
type: reference
status: ready
created: 2026-10-08
---

# UL-3a: Delete `seon.instrument/remove!`

Ledger row UL-3a (group F). Source: `reinvention/reinvention-opus.md` item 2. Model: Sonnet. No owner decision: the
function does nothing but count.

`seon.instrument/remove!` (`src/seon/instrument.clj:1177-1181`) is a leftover of "unarm on teardown": its body is
`(count (instrumented))` and its docstring says cluster teardown cannot remove host contracts. No `src` caller remains
(`rg "instrument/remove!" src` is empty). Five test sites call or stub it.

UL-3b (delete `state`, `replaced-definitions`, `restore!` and `seon.test-support/preserving-instrumentation-state`) is
**not** this card: eight tests alter global Vars inside that scope (`instrument_test.clj:70,826`,
`error/refusal_test.clj:34`, `test_support_test.clj:211`, `test_reaching_test.clj:299`,
`cluster/publication_delta_test.clj:110`, `render/web_adoption_test.clj:9`, `sci/eval_test.clj:463`), and each must
first arm only its own Vars or move to the platform tier; UL-3b waits on that split.

## Files

- `src/seon/instrument.clj`
- `test/seon/instrument_test.clj`
- `test/seon/dev/source_instrumentation_test.clj`
- `test/seon/registry_isolation_test.clj`
- `resources/seon/tasks/seon.instrument.edn` (only the intent text naming `#'seon.instrument/remove!`, line 23)

## Functions

Delete `seon.instrument/remove!` (`instrument.clj:1177-1181`, contract `[:=> [:cat] :seon.instrument/instrumented]`).
Nothing is added.

## Callers converted

- `test/seon/instrument_test.clj:669-670` and `:695-696`: drop `(instrument/remove!)` from the two `finally` forms
  (it changed nothing; the `remove-ns` in the second stays).
- `test/seon/instrument_test.clj:1326-1333` (`cluster-policy-and-removal-preserve-the-jvm-wrappers`): delete the two
  `(is (= (count before) (instrument/remove!)))` lines; keep the other assertions; rename the test
  `cluster-policy-preserves-the-jvm-wrappers`.
- `test/seon/dev/source_instrumentation_test.clj:56-57`: drop the `#'instrument/remove!` stub pair from the
  `with-redefs-fn` map and drop `:unstrument` from that test's expected event vector (read the assertion below line
  60 and remove exactly that element).
- `test/seon/registry_isolation_test.clj:110-112`: delete the `doseq` that calls `instrument/remove!` under each
  context's projection state; the following assertions still hold (they assert nothing changed).
- `resources/seon/tasks/seon.instrument.edn:23`: remove `#'seon.instrument/remove!` from the list in the intent text.

## Proof

No new test (a deletion). The converted tests stay green, and D: `(ns-resolve 'seon.instrument 'remove!)` is nil.

`--test seon.instrument-test seon.dev.source-instrumentation-test seon.registry-isolation-test`

## Cost

D: none at runtime; −5 source lines, about −8 test lines.

## Commit

`bin/lane commit <lane> -m "UL-3a: delete seon.instrument/remove!, a count with no caller" --test seon.instrument-test seon.dev.source-instrumentation-test seon.registry-isolation-test src/seon/instrument.clj test/seon/instrument_test.clj test/seon/dev/source_instrumentation_test.clj test/seon/registry_isolation_test.clj resources/seon/tasks/seon.instrument.edn`

## Dependencies

None. No lane holds these files at `3f69939e4`.
