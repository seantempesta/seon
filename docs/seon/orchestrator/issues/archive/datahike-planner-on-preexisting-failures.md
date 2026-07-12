---
type: issue
status: resolved
tags: [issue, database]
---

# Datahike fork: 2 pre-existing failures when the planner is globally ON

Found 2026-07-02 during the collect-field fix verification (base-vs-fix
comparison proved both exist at `e6d196d5`, before the fix — not regressions).

## Symptom

`DATAHIKE_QUERY_PLANNER=true clojure -M:test -m kaocha.runner clj-pss` in
`reference-code/datahike`:

- FAIL `datahike.test.attribute_refs.query-rules-test/test-rules` — "Rule
  context is isolated from outer context" returns `#{}` under the planner.
- ERROR `datahike.test.query-fns-test/test-issue-180` — "Cannot resolve
  predicate in cross-component post-filter: ?pred" (name captured 2026-07-03).

## Root causes (both in the planner's cross-component Cartesian split,

`src/datahike/query.cljc`)

1. **Disjoint component with no find/post-filter vars collapsed the merge.**
   A disconnected where-component contributing no find or post-filter vars
   (e.g. `[?e _ _]` next to a rule on other vars) got an EMPTY `:find` for
   its recursive sub-query, which returned zero tuples — and
   `cartesian-merge` returns `#{}` when any component is empty. Legacy
   `-collect` IGNORES rels whose attrs don't intersect the collected
   symbols (matching or not), so the planner now runs every component
   (non-contributing ones get a witness var so their `:find` parses — they
   must still RUN, because legacy raises "Insufficient bindings" for e.g.
   `not` over truly unbound vars) and excludes the ignored ones from the
   merge. The plain `query-rules-test` variant never caught this because it
   queries a raw datom VECTOR, which is planner-ineligible; only the
   attr-refs variant (a real DB) exercised the split.
2. **Var-valued predicate in a cross-component post-filter threw.**
   `eval-post-filter` resolved the fn position only as a global symbol;
   `[(?pred ?a)]` with `[_ :pred ?pred]` (issue-180 shape) threw. It now
   reads the predicate per-tuple from the wide tuples (which already carry
   every post-filter var), mirroring legacy context resolution.

Both were VERDICT: real planner bugs (silent-`#{}` / throw class), not
wrong upstream tests.

## Status

RESOLVED 2026-07-03. Fix on the fork branch
`fix/planner-rule-wildcard-attr-and-var-pred` (`c9a2704c` fix, `eedde719`
changelog, `fa03b0fa` run-ignored-components refinement), ff-merged to
`sync-upstream` (= `fa03b0fa`) and pushed. Regression tests pin both shapes
(`test-disjoint-component-dropped`, `test-var-valued-predicate-post-filter`
in `test/datahike/test/query_planner_test.clj`). Fork suites: default
628/2750 0F, planner-ON 628/2759 0F/0E, CLJS node-test 12/67 0F/0E. Seon
deps bumped in both sites + submodule pointer (`35fdd6f7`); wire-server +
cljs-watch + pod rebuilt/restarted on the new sha. LIVE-PROVEN on the pod:
the disjoint-gate rule shape returns the correct rows (old code returned
`#{}`), and the F0 collect-field shape still returns the correct row in
both clause orders.
