---
title: "Card RP-5: One analysis per form, supplied to derive"
type: reference
status: active
created: 2026-10-08
---

# RP-5 One analysis per form, supplied to derive

Ledger row RP-5 (alias S5-04h1, j; group B plus `src/seon/fn/analyzer.clj`). Design: `redo/pass-and-recording.md`
§2 ANALYZE and DERIVE ("per form from captured values only, supplied analysis"), §6 ("analysis, N defining forms: N
calls 13 / 62.6 / 232.5 ms → one call"). Lane: Sonnet. Cited at HEAD `e7f120672`. RP-6a then moves this one call from
per form to once per eval request; this row only changes who calls the analyzer and how often per form.

## What exists

`seon.turn.batch/derive-form` (`src/seon/turn/batch.clj:304`) calls `analyze-form` (`:226`, one
`seon.fn/runtime-analysis-batch` call) for the form, then, per definition write:
- a definition whose symbol's namespace differs from the form's (`:seon.batch/other-namespace` from `join-write`,
  `:121-158`) is analyzed a **second** time under its own namespace (`write-entity` `:258-260`);
- a nested definition runs `captured-locals` (`:160-180`): a **third** clj-kondo run, with `:locals true`, over the
  enclosing form.
So one form can cost up to 1 + (other-namespace definitions) + (nested definitions) analyzer runs.

## The change

The analysis becomes an input. A new function builds the analyzer requests for one form from its captured writes
before any analysis: the form itself (namespace `ns-before`, its captured resolver row from RP-3), plus one request per
definition write whose symbol's namespace differs from `ns-before`, carrying the definition's own source (cut from the
entry's source by the write's origin span) and that namespace's captured row. One `runtime-analysis-batch` call
analyzes all of them **with locals**, so `captured-locals` reads locals from the same analysis. `derive-form` takes the
analysis and runs no analyzer.

## Files

- `src/seon/fn/analyzer.clj` (`analyze` gains a locals option)
- `src/seon/fn.clj` (`runtime-analysis-batch` passes it)
- `src/seon/turn/batch.clj`
- `src/seon/batch.clj` (`derive-step` calls the new analyze stage)
- `resources/seon/schemas/seon.batch.edn` (request gains `:seon.batch/analyze`)
- `test/seon/repl_differential_p8_test.clj`, `test/seon/turn/batch_test.clj`

## Functions

- CHANGE `seon.fn.analyzer/analyze` (`src/seon/fn/analyzer.clj:668`): the input map accepts
  `[::locals? {:optional true} :boolean]`; when true the kondo config is
  `(assoc-in publication-config [:analysis :locals] true)`, and the output gains `::locals` and `::local-usages`
  (`(filterv jvm-entry? (mapv #(merge (location %) (select-keys % [:id :name])) (:locals analysis)))`, the same for
  `:local-usages`); output schema gains both as optional `[:vector :map]`. Publication never passes it (its cost is
  unchanged).
- CHANGE `seon.fn/runtime-analysis-batch` (private, `src/seon/fn.clj:945`): calls `analyzer/analyze` with
  `::analyzer/locals? true` (`:1032`). Contract unchanged.
- ADD `seon.turn.batch/analysis-requests`
  `{:malli/schema [:=> [:cat :seon.batch/entry [:vector :seon.batch/write] [:vector :qualified-symbol]] [:vector :map]]}`
  `[entry writes declared]`. Answers `[]` when the form has no final root `:bind` of a function or test (the
  `finals` rule of `derive-form` `:322-328`). Otherwise the first request is
  `{:namespace-name (:seon.batch/ns-before entry) :form-source (:seon.batch/source entry)
    :seon.fn/namespace-row <the :seon.batch/resolver of the form's first root write in ns-before, else absent>
    :seon.fn/declared declared}` plus `:seon.batch/expansions (form-relative-expansions entry writes)` when non-empty;
  then, for each final root write whose `(namespace sym)` differs from `(str ns-before)` and whose origin has a
  complete span, `{:namespace-name (symbol (namespace sym)) :form-source <origin span cut from the entry source by
  local line/column> :seon.fn/namespace-row (:seon.batch/resolver write) :seon.fn/declared declared
  :seon.batch/write-var (:seon.batch/var write)}`. The cut uses `line-starts` (`:53`) relative to the entry's
  `:seon.batch/line`/`:seon.batch/column`, as `form-relative-expansions` (`:204-224`) converts call spans.
- ADD `seon.turn.batch/analyze-entry`
  `{:malli/schema [:=> [:cat :seon.batch/request :seon.db/database-value :seon.batch/entry [:vector :seon.batch/write] [:vector :qualified-symbol]] [:maybe :map]]}`
  `[request working entry writes declared]` = `nil` for no requests, else
  `(let [rs (analysis-requests entry writes declared) a (#'sfn/runtime-analysis-batch working rs)] {:analysis a :requests rs :entities (analyzed-entities working rs a)})`.
- CHANGE `seon.turn.batch/derive-form` (`:304`): arity `[request state analyzed entry writes]`, where `analyzed` is
  `analyze-entry`'s answer; contract
  `[:=> [:cat :seon.batch/request :map [:maybe :map] :seon.batch/entry [:vector :seon.batch/write]] [:map [:seon.batch/change :seon.batch/change] [:seon.batch/derive-state :map]]]`.
  It no longer computes `declared` itself; `declared` moves to the caller (below). Replace the `analyzed` binding
  (`:329-331`) by the argument.
- CHANGE `seon.turn.batch/write-entity` (private, `:241`): the other-namespace row is
  `(get (:entities analyzed) [i 0 (name sym)])` where `i` is the index of the request whose `:seon.batch/write-var`
  is this write's Var (offset 0: the definition is that request's whole source); the nested-locals check is
  `(captured-locals (:analysis analyzed) span-in-synthetic-rows)` over the supplied analysis.
- CHANGE `seon.turn.batch/captured-locals` (`:160`): arity `[analysis [r c er ec]]` over the supplied
  analysis's `::analyzer/locals` and `::analyzer/local-usages` (synthetic coordinates, so the caller passes the
  definition's analyzer span unchanged); contract
  `[:=> [:cat :map [:tuple int? int? int? int?]] [:set :symbol]]`. Its own `kondo/run!` is deleted.
- DELETE `seon.turn.batch/analyze-form` (`:226`) and the `clj-kondo.core` require if nothing else uses it
  (`rg -n 'kondo/' src/seon/turn/batch.clj`).
- ADD the derive-state rule as `seon.turn.batch/declared-symbols`
  `{:malli/schema [:=> [:cat :seon.sci.eval/ctx [:vector :qualified-symbol] [:vector :seon.batch/write]] [:vector :qualified-symbol]]}`
  `[ctx declared writes]`: the body of `derive-form`'s `declared` binding (`:308-318`), unchanged.
- CHANGE `seon.batch/derive-step` (private, `src/seon/batch.clj:543`): before `(:seon.batch/derive request)`, compute
  `declared` with `(:seon.batch/declared request)` and `analyzed` with `(:seon.batch/analyze request)`; pass both
  (`analyzed` as the third argument, the updated `declared` into the derive state). The two stages are request values:
  `seon.turn.batch/program-request` (`:866`) and its siblings set
  `:seon.batch/declared #'declared-symbols :seon.batch/analyze #'analyze-entry`.
- `seon.batch.edn` `:request`: add `[:seon.batch/analyze {:optional true} :seon.batch/stage]` and
  `[:seon.batch/declared {:optional true} :seon.batch/stage]` beside `:seon.batch/derive` (use the schema the
  existing stage keys use: `rg -n ':seon.batch/derive ' resources/seon/schemas/seon.batch.edn`).

## Data

```edn
;; analysis-requests for "(do (defn f [] 1) (defn other.ns/g [] 2))" in rp5.a
[{:namespace-name rp5.a :form-source "(do (defn f [] 1) (defn other.ns/g [] 2))" :seon.fn/namespace-row {…} :seon.fn/declared [rp5.a/f other.ns/g]}
 {:namespace-name other.ns :form-source "(defn other.ns/g [] 2)" :seon.fn/namespace-row {…} :seon.fn/declared […] :seon.batch/write-var #'other.ns/g}]
```

Clojure refuses `(defn other.ns/g …)` ("Can't create defs outside of current ns"), so the other-namespace case
arises from `(in-ns 'other.ns)` inside one form, as in the proof.

## Proof

`seon.repl-differential-p8-test/nested-and-cross-namespace-definitions-analyze-once` (new). Through `seon.mcp/call`
with the agent's profile counters: evaluate
`"(ns rp5.a) (let [k 2] (defn closes [] k)) (do (in-ns 'rp5.b) (clojure.core/defn other [] 3) (in-ns 'rp5.a))"`; then
in JVM mode read the clj-kondo invocation count before and after with
`(reduce + (keep #(when (= 'seon.fn.analyzer/invoke-kondo (:seon.profile/sym %)) (:seon.profile/calls (seon.profile/observation %))) (seon.profile/cells)))`
(`seon.profile/cells` `src/seon/profile.clj:327`, `observation` `:395`; `invoke-kondo` is armed: default's health
names it) and assert it grew by exactly 2 (one per defining form; the `ns` form defines nothing). Assert data too: `rp5.b/other` is a stored row (`seon.db/pull … [:seon.fn/sym 'rp5.b/other]`), and
`rp5.a/closes` is not stored (it captures `k`: `:captures-locals`, until RP-9 changes the rule). On HEAD it grows
by 3 (the other-namespace definition is analyzed twice) and `captured-locals` makes a fourth, uncounted kondo run
(`kondo/run!` directly), which makes this a proof. The count is read in the check JVM `bin/lane commit` boots, where
no other agent analyzes concurrently.

## Reaching tests

`seon.repl-differential-p8-test`, `seon.turn.batch-test`, `seon.turn-test`, `seon.fn-test`, `seon.cluster.host-test`
(`a-definition-whose-body-expands-macros-persists` `:158`), `seon.fn.analyzer-test` (`test/seon/fn/analyzer_test.clj`).

## Cost

Expected from the work: one clj-kondo run per defining form, its text proportional to the form plus its other-namespace
definitions; `:locals true` adds the form's locals to the output (small). Measured parent (redesign §6): analysis N
calls of 13 / 62.6 / 232.5 ms at N = 1 / 5 / 20; this row removes the extra runs per form; RP-6a makes it one call per
eval request (11.5 / 13.9 / 16.3 ms). Report the O probe's `defn` and five-`defn` rows.

## Deleted

`analyze-form`, `captured-locals`' own kondo run, the second analysis per other-namespace definition.

## Commit

`bin/lane commit <lane> -m "RP-5: one analysis per form, supplied to derive; locals and other-namespace definitions in the same call" --test seon.repl-differential-p8-test --test seon.turn.batch-test --test seon.turn-test --test seon.fn-test --test seon.cluster.host-test src/seon/fn/analyzer.clj src/seon/fn.clj src/seon/turn/batch.clj src/seon/batch.clj resources/seon/schemas/seon.batch.edn test/seon/repl_differential_p8_test.clj test/seon/turn/batch_test.clj`

## Dependencies

RP-2, RP-3 (the captured resolver row). EN-8a in the ledger (one RECORD owner) is not needed for correctness here.
`src/seon/fn.clj` is held by led-nk9 at writing.

## Reset needs

None.
