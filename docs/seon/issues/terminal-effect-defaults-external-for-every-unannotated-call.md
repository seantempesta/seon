---
type: issue
status: open
tags: [program-graph, purity, edge, severity/blocker]
---

# Terminal effect defaults to `:external` for every unannotated call target

## Observed

`seon.program.edge/canonical-terminal` (src/seon/program/edge.cljc:143-152)
assigns a terminal's effect with `(get effects target :external)`. The
`::edge/effects` map it reads is built by
`seon.host.eval/namespace-resolution` (src/seon/host/eval.clj:94-99), which
keeps only entries whose var carries `:seon.capability/effect` metadata
(`source-effect`, src/seon/host/eval.clj:47-52). Only Seon capability
wrappers carry that metadata; `rg` finds no `:seon.capability/effect`
annotation on `clojure.core`.

The fallback resolution built at the tee is worse: `seon.host.record`
passes `::edge/effects {}` literally (src/seon/host/record.clj:452).

## Consequence

`(defn add [a b] (+ a b))` tees a terminal `clojure.core/+` with
`::edge/effect :external` and `::edge/required-bindings #{"clojure.core/+"}`
(the `:pure` branch at edge.cljc:149 is unreachable for it). Any transitive
purity fold over the stored graph therefore reports `:external` for every
function that calls anything at all.

This is the substrate two open issues depend on:

- `contract-predicate-transitive-purity-awaits-execution-planner.md`
- `planner-lacks-per-root-purity-projection.md`

Neither can be closed by folding the current edges, and
`:seon.schema/pure-predicate-symbols` (src/seon/schema.cljc:559, defaulted
`#{}` at :582 and :872, no producer in `src/`) stays empty regardless.

## Acceptance

A function whose call graph reaches only value-returning `clojure.core`
vars folds to `:pure` without any hand-maintained set of core symbols
(hand lists are banned). The rule that establishes core purity must be
computed — from the corpus, provenance, or the interpreter's own var
metadata — and `canonical-terminal`'s `:external` default must then mean
"genuinely unknown", not "unannotated".

## Owner

`src/seon/program/edge.cljc` (the default) and `src/seon/host/eval.clj`
(the effects map). Found during the derived-placement design review,
2026-07-25.
