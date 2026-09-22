---
type: landing
status: implemented; focused proof red outside this defect class
created: 2026-09-22
tags: [agent-platform, tests, symbols]
---

# String test symbols — landing evidence

## Boundary

This lane changed test expectations only. It did not widen a production contract,
did not operate `default`, and did not touch the `error-facets` or `turn-shapes`
owned paths. The shared `test/seon/test_support.clj` remained clean and unchanged.
The MCP `runtime_status`/`eval_clj` surfaces were unavailable in this session, so
no live-cluster claim is made.

The authoritative rule is that `:seon.test/sym`, `:seon.fn/sym`, detector inputs,
`tests-reaching` inputs, `:seon.fn/calls`, and call-arity callees are native
qualified symbols. Dynamic fixture names now use `(symbol (str namespace-name)
"name")`; fixed identities use quoted symbols. String conversion remains only at
presentation and command-vector boundaries.

## Changed expectation sites

Counts below are changed expectation lines (one line may contain several related
literal values):

| File | Sites |
|---|---:|
| `test/seon/test_reaching_test.clj` | 25 |
| `test/seon/test_failure_facts_test.clj` | 25 |
| `test/seon/bootstrap_test.clj` | 8 |
| `test/seon/test_provenance_test.clj` | 5 |
| `test/seon/issue_test.clj` | 5 |
| `test/seon/issue_generate_test.clj` | 4 |
| `test/seon/render/ns_test.clj` | 4 |
| `test/seon/issue/detect_test.clj` | 2 |
| `test/seon/render_coverage_test.clj` | 1 |

One regression, `fixture-built-test-symbol-is-qualified-at-the-write`, reads the
fixture-created row back from the database and asserts that its `:seon.test/sym`
is a qualified symbol.

## Evidence

- Before: `bin/test-fast --paths test/seon/test_reaching_test.clj --
  seon.test-reaching-test`, run `ba464e9857e9`, recorded 29 executed / 99
  assertions / 33 failures / 25 errors. It reproduced `expected a namespaced
  symbol, got a string` at the fixture write, `reach-digest`, `tests-reaching`,
  `host`, and `:seon.test/changed` boundaries.
- After, partial: the nine-namespace run was interrupted after its first namespace
  to correct direct-symbol `:seon.fn/calls` and presentation assertions. Before
  interruption, `fixture-built-test-symbol-is-qualified-at-the-write` passed and
  the original string-symbol refusal was absent from the observed output.
- After, focused: run `378a205ab3e8` recorded 19 executed / 80 assertions / 50
  failures / 19 errors for `seon.test-failure-facts-test` and
  `seon.test-provenance-test`. No `expected a namespaced symbol, got a string`
  remained. The run was red on the separate canonical-fixture defect: synthetic
  program rows lack required `:seon.program/analyzed-source-digest`; it also saw
  pre-existing recorded run rows from the published fixture.
- Load: one JVM required all nine owned namespaces successfully and printed
  `:loaded`.
- Static residual scan found no string literal or `(str ns "/name")` feeding the
  owned symbol attributes, detector inputs, `tests-reaching`, or `:seon.fn/calls`.

The exact requested nine-namespace green is therefore not claimed. Its remaining
red boundary is fixture admission/selection outside C2, not a string-symbol
refusal. The orchestrator still owns the cold gate and platform proof.

## Foreign shared-tree boundary

During the lane, unrelated edits remained in the two pre-existing dirty docs and
untracked artifacts. Concurrent lanes also changed `src/seon/instrument.clj`,
`src/seon/turn.clj`, `test/seon/instrument_test.clj`, and turn/reread tests. None
of those paths is included in this lane's commit or evidence snapshot.
