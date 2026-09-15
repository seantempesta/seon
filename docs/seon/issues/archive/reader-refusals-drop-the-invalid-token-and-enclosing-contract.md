---
type: issue
status: resolved
severity: friction
tags: [issue, sci, schema, wave/schema-audit]
---

# Retain reader coordinates and enclosing contract for key corrections

## Disposition — 2026-09-15

Implementation: `a65985098`, with final wording in `1fd81b2be`; dependency selection `a24ad11b7`.

The approved Edamame extension retains the invalid token and enclosing collections; the existing Seon reader preserves them. The error owner derives the unique missing declared key. The exact run-7 canonical SCI regression passes, and the live default JVM reader/render proof produces `Fix: Use :my.plan.item/id.` with the enclosing function and its docstring example (107 estimated tokens). Dependency commit: `63373df`; parent implementation and final gates are recorded in the landing linked below.

Implementation commit and gate results: [refusal-grammar landing](../../../prds/context-generation/research/refusal-grammar-2026-09-15.md).


## Live dependency boundary — 2026-09-15

The refusal-grammar lane reproduced the exact source below through default's
JVM reader in 1,016 ms. The deepest Edamame exception data is only
`{:type :edamame/error :line 1 :column 36}`. The token and enclosing call are
absent at the dependency itself, not just dropped by Seon's projection.
`reference-code/edamame/src/edamame/impl/parser.cljc:638–661` retains the token
locally but passes no token data to `throw-reader`. A structural correction
requires extending that dependency evidence and carrying it through the
existing reader; parsing exception prose is not the fix. The assignment's
cross-owner design gate was reached before production edits. Three priced
options and exact rendered bytes are in
[the landing note](../../../prds/context-generation/research/refusal-grammar-2026-09-15.md).
This member remains open.

The 2026-09-15 run-7 source `(my.plan/current! {:my.plan/item/id "juniper/define"})`
fails before function input validation. `src/seon/sci/reader.cljc:630`
keeps source text, line, column and parse classification, but drops the parser's
invalid token and the enclosing function/map context. The error renderer can
show the shared refusal grammar and original source; it cannot truthfully name
`:my.plan.item/id` from declared argument attributes with the retained evidence.

The schema-audit assignment owns the error renderer but not reader logic.
An ownership-extension question was sent during that lane; it remains pending.
No message parsing or string-distance suggestion was introduced as a workaround.

The database write boundary now derives missing-key candidates from attributes
present in the supplied map. That is a separate, later boundary: constructing a
keyword object in a test is not proof that the exact malformed source was read.

## Acceptance

The reader retains structured token and enclosing-form evidence through its
existing error event. The error pair derives the relevant public function input
schema from the supplied program context and suggests a missing declared key by
attribute presence. The exact run-7 source must show `Fix: Use :my.plan.item/id`.
Ambiguous or unavailable context must remain explicit rather than guessed.

The recurring partial proof is
`seon.contracts-plan-test/run7-reader-refusal-uses-the-shared-grammar`;
`seon.transact-feedback-test/unknown-key-suggestion-comes-from-present-attributes`
proves the database boundary only.
