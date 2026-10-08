---
title: "Card SR-I Diagnostics get a result handle"
type: reference
status: active
created: 2026-10-08
---

# SR-I Diagnostics get a result handle

Ledger row SR-I (group C). Owner (2026-10-08 afternoon): Seon's own diagnostics are like any REPL form result: the
full value cached, the value printer's text stored. Lane: Sonnet.

## What exists
`seon.sci.eval/shown-result`'s tail (`src/seon/sci/eval.clj:3813-3817` at HEAD; the ledger's `:3790-3802` moved)
stores `:seon.cluster.eval/diagnostics` as the floor's text of `(mapv flat-diagnostic failures)`; the full maps are
dropped.

## Files
`src/seon/sci/eval.clj`, `resources/seon/schemas/seon.cluster.eval.edn`, `test/seon/repl_differential_p5_test.clj`.

## Functions
- CHANGE the diagnostics branch in `shown-result`: compute `diagnostics (mapv flat-diagnostic failures)` once; put the
  vector in the cache with `(seon.cache/put! :seon.repl/result (str evaluation-id "-d") (* 4 (tokens/estimate text)) diagnostics)`
  (SR-H's function and kind); store its text by SR-B's rule: `seon.blob/inline-or-stage`
  (`src/seon/blob.clj:217`) with `:seon.cluster.eval/diagnostics`, `:seon.cluster.eval/diagnostics-size`,
  `:seon.cluster.eval/diagnostics-blob`; the staged writes join the settlement's `:seon.blob/staged-writes` like the
  reply's.
- The handle shown beside the text is `(admit/result-handle (str evaluation-id "-d"))`
  (`src/seon/sci/admit.clj:691`): `result/e<id>-d`. Add `:seon.cluster.eval/diagnostics-handle :qualified-symbol`
  only if the reply needs it as data; otherwise the reply prints the handle from the id.
- SCHEMA: `:diagnostics-size [:int {:min 0}]`, `:diagnostics-blob :seon.blob/digest` (optional receipt members).

## Proof
`seon.repl-differential-p5-test/diagnostics-dig-to-full-errors` (new): define a render function that throws, return a
value it matches through `seon.mcp/call`; assert the reply's `:value` is the value, `:diagnostics` is text, and
evaluating `result/e<id>-d` in the next call returns a vector of maps each with `:seon.error/type`.

## Other tests reaching it
`seon.repl-differential-p5-test`, `seon.sci.eval-test`, `seon.render.value-test`.

## Cost
D: one cache put and one size comparison per evaluation with diagnostics; none otherwise.

## Deleted
Nothing.

## Commit
`bin/lane commit <lane> -m "SR-I: diagnostics get a result handle" --test seon.repl-differential-p5-test --test seon.sci.eval-test src/seon/sci/eval.clj resources/seon/schemas/seon.cluster.eval.edn test/seon/repl_differential_p5_test.clj`

## Dependencies
SR-H.

## Reset
None.
