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

## Resumed sweep after accepted `ce83344ce`

The resumed scan found no remaining dynamic string-built identity constructor in
the nine files. Eight follow-on expectation lines were corrected: one in
`test_reaching_test.clj` (CLI argv text), three in `issue/detect_test.clj`
(assertion messages), one in `issue_generate_test.clj` (qualified-symbol
expectation), one in `render/ns_test.clj` (the local name can itself be a symbol),
and two in `render_coverage_test.clj` (text matching). The other four files have
zero additional changes. The original per-file counts above describe `ce83344ce`.

The `constructor-slice` boundary was preserved: no error-construction line was
edited, and no such line blocked a symbol site. The existing diagnostic-member
and diagnostic-operation reads in `issue_test.clj` and `render_coverage_test.clj`
remain unchanged. No production or shared-helper file was edited.

The single regression now has an observed before/after pair. Temporarily restoring
only the old `with-test` expression `(str namespace-name "/probe")` made
`fixture-built-test-symbol-is-qualified-at-the-write` fail at line 107:
`(qualified-symbol? written)` received nil because the string lookup did not
resolve the indexed symbol row. This is run `8b8149ead030`, execution output in
`tmp/string-symbols-regression-before.log`, 30 tests / 150 assertions / 46 failures
/ 15 errors. Recording failed, so this is execution evidence only. The temporary
change was restored before the final run; no second regression was added.

The initial resumed nine-namespace run (`e198974920fa`,
`tmp/string-symbols-resumed.log`) completed 94 tests / 666 assertions / 211 failures
/ 50 errors, then refused recording with `Malformed PREPL result`. A literal
search for the old refusal also matches historical issue prose in the transaction
report printed by `indexed-issues-replace-facts-and-retract-removed-notes`; that
text is not a current contract exception. Tests and historical content were not
weakened or filtered to suppress that match.

Final nine-namespace execution: run `6fc6afb6ad5c`, HEAD plus the nine owned paths,
log `tmp/string-symbols-final.log`. All 94 tests completed: 719 assertions, 238
failures, 46 errors. The single symbol regression passed (BEGIN/END at log lines
329–330, no assertion failure). No current exception reports `expected a
namespaced symbol, got a string`, and no remaining exception casts a Symbol to
String/CharSequence. Historical issue prose still contains the literal refusal.
The recording authority again refused the result (`Snapshot results were not
recorded`, `Malformed PREPL result`), so run identity is available but a durable
recorded tally is not. These are execution counts, not recorded green evidence.

The requested nine-namespace green remains unmet: the full run reaches separate
fixture admission (required analyzed-source digest and effect capability), test
selection/custody, renderer, issue-fixture, and result-recording failures. Repairing
those would extend this bounded C2 sweep. No contract, diagnostic construction,
or assertion was weakened to make those failures disappear. All launched test
JVMs exited before the next JVM was admitted. Logs remain in repository `tmp/` as
evidence; no worktree was created and no default operation was issued.

Final load check required all nine namespaces in one JVM and exited 0 with
`:loaded`; output is `tmp/string-symbols-final-load.log`. Constructor-slice edits
were present in the shared tree by this check, while the preceding fast run used
HEAD plus only this lane's paths. No claim is made that it tested those foreign
edits. The shared helper became dirty in that foreign cut and was left untouched.
