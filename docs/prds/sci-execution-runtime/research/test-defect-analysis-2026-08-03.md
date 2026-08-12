---
type: research
status: active
tags: [testing, performance]
---

# Test defect analysis, 2026-08-03

## Result

The latest complete timestamped run is the 883-test / 4,405-assertion gate in
`tmp/orchestrator/seon-db-sweep-stdout.log`. Its runner started at
2026-08-03T06:17:55Z, ended at 06:29:29Z, and the shell reported 697.50
seconds. The two lifecycle namespaces dominate by two orders of magnitude when
ranked by test count multiplied by namespace wall time:

| Rank | Namespace | Tests | Wall | Tests × seconds |
|---:|---|---:|---:|---:|
| 1 | `seon.dev.fresh-operator-test` | 26 | 237.296s | 6,169.696 |
| 2 | `seon.cluster.boot-test` | 28 | 171.541s | 4,803.157 |
| 3 | `seon.repl-parity-test` | 69 | 7.014s | 483.945 |
| 4 | `seon.sci.eval-test` | 43 | 10.153s | 436.558 |
| 5 | `seon.cluster.turn-test` | 46 | 9.003s | 414.120 |
| 6 | `seon.cluster.armed-test` | 6 | 63.027s | 378.160 |
| 7 | `seon.render.web-test` | 37 | 5.249s | 194.209 |
| 8 | `seon.sci.session-image-test` | 8 | 22.664s | 181.311 |
| 9 | `seon.reconcile-test` | 8 | 14.325s | 114.601 |
| 10 | `seon.flow-test` | 23 | 4.466s | 102.724 |
| 11 | `seon.ai-stream-fold-test` | 19 | 4.795s | 91.103 |

This is not a reason to delete process falsifiers. It is evidence that process
and boot boundaries are a different feedback tier from ordinary immutable
function and database tests. The default gate should retain fast contract
coverage and derive the omitted set from declarations on the tests themselves;
the full gate should retain every assertion and test count.

The first tiered default profile, captured under
`tmp/test-profiles/default-tier-2026-08-03`, retained 815 tests and 4,029
assertions with zero failures or errors in 200.248 seconds. It skipped 33
declared process/boot tests. A second audit added the real bootstrap drive and
TERM-resistant child-process backstop. The final profile under
`tmp/test-profiles/default-tier-final-2026-08-03` retained 813 tests / 4,018
assertions, skipped 35 tests, stayed green, and finished in 174.935 seconds.
That is a 74.9% wall-time reduction from the 697.50-second full baseline, but
it misses the one-to-two-minute aim.

The final default leaders are ordinary generated/source-publication classes
rather than the lifecycle giants: turn traces 20.577 seconds, reconciliation
14.616, SCI evaluation 12.528, oversight live boot 11.573, schema admission
publication 10.224, REPL parity 9.977, render web 9.203, and render transcript
8.620. Only the oversight case is a real boot; it is owned by the concurrently
edited render lane. The tier implementation does not disguise the other
default costs by marking non-process properties long.

The explicit full checkpoint could not be run on the same coherent census.
The concurrently edited render snapshot deletes
`seon.context-capture-test`, `seon.render-test`, `seon.render.agent-test`,
`seon.render.root-test`, and `seon.render.walk-test`, while adding
`seon.render-simplification-test`. The loaded metadata therefore contains 848
tests (813 default plus 35 long), not the required 883. The lane stopped at
that foreign boundary without running or editing around it. The last complete
full evidence remains 883 tests / 4,405 assertions / zero failures / zero
errors in 697.50 seconds.

## Defect signals and dissolving refactors

### Fresh operator

Five tests consume 228.1 of the namespace's 237.3 seconds:
initialization lifecycle (66.2s), live predicate-owner reload (53.5s), full
operator restart (46.5s), fresh-process instrumentation order (31.3s), and
source-less reset (30.6s). A shared child JVM would invalidate the class each
test proves: cold loading, destructive reset, or process restart is the
subject. The correct simplification is tiering those five vars as long while
keeping the other 21 parsing, fencing, cleanup, and command-selection tests in
the default gate. One process-bound regression remains for each lifecycle
class; the default gate does not repeatedly pay for them.

The tier audit also found a 5.683-second TERM-resistant child-process backstop.
It is long for the same reason: the process signal/grace boundary is the test's
subject, while the neighboring command-selection and identity tests remain
default.

### Cluster boot and armed boot

`seon.cluster.boot-test` contains 15 calls to its `fresh-root` helper and about
30 `cluster/start!` call sites. Most starts are legitimate boot subjects, but
each test needlessly republishes identical current source into a new physical
root before reaching that subject. The dissolving refactor is one namespace
fixture that publishes current source once, with each boot test retaining a
unique cluster branch and its own start/stop lifecycle. Tests for a corrupt
store, history creation policy, and synthetic source edits retain isolated
roots because physical-root state is their subject. Real boot vars are long;
the pure bootstrap, path, executor, digest, and source-agreement tests stay in
the default gate.

The resulting explicit boot namespace gate retained 28 tests / 133 assertions
with zero failures or errors and fell from 171.541 seconds to about 71.7
seconds runner wall. The reduction comes from one lazily populated root, not
fewer boot assertions; tests still own unique branches, starts, stops, and
isolated physical roots when root mutation is the subject.

`seon.cluster.armed-test` states that every test boots a real cluster because
fixture-vs-live wiring is the failure class. There is no honest fast
substitute. The whole namespace is one declared long boundary, with its six
tests retaining distinct failure classes: root arming, cluster context
isolation, no model call, boot-time wake conservation, refusal recovery, and
fault commitment.

### REPL parity

The 69-test density exposed a fixture defect in the preceding speed incident:
an immutable database value was rebuilt for every case. Moving that fixture to
`:once` reduced the namespace from 185.2 seconds to 7.0 seconds in the current
full run without reducing a test or assertion. The remaining cases are cheap
reader/evaluator parity examples. No long marker or second runner belongs
here. Future growth should prefer one generated parity relation over another
row of point examples when it covers the same reader class.

### SCI evaluation and cluster turns

The 43 SCI tests and 46 turn tests are dense but fast. Their density is a
design tripwire: interrupt handling, candidate-context isolation, admission,
and terminal settlement must each stay owned by one kernel/transaction rather
than accumulating caller-specific fences. The dissolving refactor is one
property per invariant class at those choke points, with example tests kept
only for distinct call shapes. They remain in the default gate because ten and
nine seconds respectively buy broad agent-runtime coverage.

### Session images and other live consumers

One session-image test accounts for 19.7 of 22.7 seconds because two fresh JVMs
are its persistence falsifier. Mark that var long and keep the seven in-process
codec/acquisition tests default. The same rule applies to the one live restart
test, the live instrumented-turn test, and the two live config-application
tests: the process/boot boundary is declared at the var or namespace, while
their pure ledgers remain fast. Their design choke points are respectively the
session-image codec, program acquisition, guarded evaluation, and effective
configuration read.

### Render web

Thirty-seven tests in 5.2 seconds are not a speed incident, but the cluster is
a design signal. Route recognition belongs to the one route table, and package
revision/equality belongs to the render pipeline; one structural regression
per route or delivery invariant should replace repeated page scenarios. The
renderer implementation lane owns those files, so this lane makes no edit.

### Reconciliation and flow

Reconciliation's generated convergence test consumes 10.8 of 14.3 seconds and
already represents one broad invariant rather than point-test inflation. Flow's
23 tests finish in 4.5 seconds. Both stay default. The simplification test is
whether new cases extend the existing convergence or proc-lifecycle property,
not whether their current test count can be reduced mechanically.

### AI stream load

The latest full run attributes 1.462 seconds of namespace load and 4.795
seconds of test time to `seon.ai-stream-fold-test`. A fresh JVM load probe
attributed 1.099 seconds to `seon.db`, 0.415 seconds to `seon.ai`, 0.714
seconds to `seon.cluster.loop`, and 0.598 seconds to `seon.test-support` before
the test namespace itself loaded in 0.023 seconds. The direct cause is mixed
ownership: two attempt-persistence tests resolve private
`seon.cluster.loop/record-attempt!`, forcing the complete run-loop owner into a
transport-fold suite. The dissolving refactor is to move those two tests to
`seon.cluster.loop-test`; the AI stream namespace then owns only fold and HTTP
transport classes. That test file currently contains another lane's render
cutover edit, so this lane records the cause rather than overlapping it.

## Dependency ledger

- Clojure 1.12.5: `reference-code/clojure/src/clj/clojure/test.clj:711-779`
  establishes test-var metadata, fixture grouping, namespace events, and
  summary counters.
- Datahike `0e8601d7f2f6`:
  `reference-code/datahike`, supplying isolated branch heads and writers.
- clj-kondo `57252e07975710aa579b24f0d1b2b1e04195caa2`:
  `reference-code/clj-kondo`, invoked once per test JVM by the shared source
  manifest.
- First-party owners: `src/seon/test/runner.clj`, `bin/test`,
  `seon.test-support/with-database`, `seon.cluster/refresh-source!`, and the
  test namespaces named above.

## Acceptance

- Bare `bin/test` derives and excludes only tests carrying
  `:seon.test/long` on the var or namespace and prints every skipped symbol,
  its reason, and `bin/test --full`.
- Explicit namespace selection, `bin/test --full`, and
  `SEON_TEST_FULL=1 bin/test` run every selected test.
- The changed-test selector remains unchanged because its explicit namespace
  invocation is a full selection.
- The default tier targets about one to two minutes without reducing ordinary
  function/database coverage. The measured result is 174.935 seconds; closing
  the remaining gap requires dissolving the default leaders above, not
  misclassifying them as process coverage.
- The explicit full tier is designed to retain all selected metadata. Its
  required 883-test / 4,405-assertion rerun is blocked by the exact foreign
  render test-census delta recorded above.
