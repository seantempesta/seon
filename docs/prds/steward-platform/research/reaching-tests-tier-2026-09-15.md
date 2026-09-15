---
type: research
status: active
date: 2026-09-15
tags: [test, runtime, steward-platform]
---

# In-process reaching tests

The reaching API, in-process check, agent wrapper, adoption facts, automatic
post-adoption hook, and `bin/test-check` are implemented. The canonical
regressions passed in default's JVM. Larger test JVM gates and the equivalent
`bin/test-fast` timing remain orchestrator-only; the request is in
`tmp/orchestrator/gate-requests/reaching-tests-tier.txt`.

**Remaining integration boundary:** a fresh ordinary development JVM does not
carry all `:test` dependencies. This verification explicitly supplied the
declared test classpath to the same JVM. The existing
[development classpath issue](../../../seon/issues/development-adoption-cannot-load-test-support.md)
now carries the reproduced evidence. No claim is made that cold automatic
canonical-fixture execution is ready without that prerequisite.

## Authorities and dependency ledger

Read AGENTS.md, seon.test, selection, bin/test, bin/_test-slot, and the
test-provenance landing note end to end; read the named reachability, runner,
and adoption sections. Applied repl, clojure-testing, data-oriented-clojure,
datahike, data-modeling and seon-flow-architecture skills.

- `seon.fn/tests-reaching` owns database reachability; no second graph walk.
- `seon.test/run` calls `runner/run-var!` and `runner/commit-results!`.
- `reference-code/clojure/src/clj/clojure/test.clj:725` applies namespace
  fixtures when running one Var, including SCI Vars.
- `seon.await/await!` owns the bounded completion wait. The test's worker
  only captures assertions; the caller commits the outcome, so a late
  completion cannot overwrite a recorded timeout.
- `development-source-refresh!` already computes changed identities. They
  are stored as refs on its convergence transaction, with submitted paths.
- `bin/test-check` reuses `seon.fresh-operator/prepl-value!`; it starts no JVM.
- Clojure's DynamicClassLoader supplies the declared `:test` source paths.
  The dependency implementation at
  `reference-code/clojure/src/clj/clojure/repl/deps.clj` delegates dependency
  resolution to tooling; this change does not duplicate that resolver.
- Selected source-bearing test namespaces reload before execution; the existing
  `seon.instrument/apply!` restores contracts. Runtime-created fixture test
  namespaces have no source resource and retain their supplied Vars.

## Behavior

`seon.test/reaching` accepts function/test names and program identity refs.
Unknown identities return `:seon.test/unknown`; known identities with no
recorded reach return `[]`. All function reach queries call
`seon.fn/tests-reaching`.

`seon.test/check` captures one program digest, branch, basis transaction, and
run identity, then uses `seon.test/run` for each selected Var. Execution and
fixtures run on bounded virtual threads in the calling JVM. The per-Var
backstop is the canonical `seon.test-support/event-backstop-seconds` (20
seconds); the whole check has the optional, schema-defaulted config fact
`:seon.test/check-time-limit-ms` (120000). The existing canonical fixture's
one base is prepared under the total bound before individual Var clocks start.
Timeouts request interruption and name the pending test or preparation stage.
Uncooperative native code cannot be forcibly terminated inside a shared JVM.

Red checks return `:seon.test/next-tier :none`. Green checks return argv vectors
for the paths-limited namespace gate and then the platform gate; they do not
launch either. Widening uses the existing `selection/widening-path?` predicate.
Direct callers can supply affected namespaces for the namespace tier; without
them the direct check selects all declared test namespaces. Hook callers defer
widening, run no tests, and name the canonical paths-limited gate. When there
is no supplied namespace set, that gate derives its own widened selection.

The convergence transaction records changed identity refs, submitted paths,
and the cluster ref. `check-adoption` queries the latest such transaction.
The hook invokes `bin/test-check` only after publication and adoption succeed;
check failure does not undo convergence. Its existing detached source worker
puts complete feedback in the publication result and hook log. The immediate
editor response still points to that result file.

## Development observations

Initial default: pid 69622, live MCP health and evaluation. Its database had
1725 test rows. `my.note/add!` existed and tests-reaching returned zero tests.
The seon.id-test JVM namespace was not loaded, despite its indexed rows.

The initial unpublished schema draft was admitted automatically before the
next edit. Changing a draft string attribute to refs then correctly refused
in-place adoption. The final ref attributes have distinct names
`:seon.test/adoption-identities` and `:seon.test/adoption-inputs`; the old
unpopulated declarations are not reused with changed semantics.

A temporary required `:seon.test/check-time-limit-ms` dial was also adopted
before its removal. Default retained its declaration, causing effective
configuration and MCP evaluation to return a missing-config refusal. The
final declaration is optional with a schema default of 120000 ms. The
default cluster's config fact was supplied explicitly and later set to that
same value through its existing database owner. No reset/refork was performed.

`cluster.clj` became clean before the bounded adoption edits were applied.
Concurrent hook edits subsequently appeared (immediate admission and ISO
timestamps); they are preserved and excluded from this lane's ownership.

## Measurements and evidence

Measured on default during concurrent development on 2026-09-15:

| Changed function | Reaching test count |
| --- | ---: |
| `my.note/add!` | 0 |
| `seon.turn/open-tx` | 43 |
| `seon.render.value/render-ai` | 112 |

Each subject row was present. Zero for `my.note/add!` is a graph coverage
observation, not evidence of a passing test. The two larger reach queries took
about 12.4 and 7.1 seconds respectively under concurrent load.

- First canonical regression run after correcting the fixture setup: five
  tests, 22 assertions, no failures/errors; 50926.348792 ms. Basis 536871706,
  digest `d3d34622ac68fadcaf8a0f2f29802899d9c3f7d1cab1aa0f927942f10cd7507d`.
- After adding current-test loading and arming: five tests, 22 assertions,
  no failures/errors; 77258.093958 ms. Basis 536871722, run entity 62065,
  digest `47ae320980121a83e728a5fc7dd719b66680eab4647be7d20b81a81c24e1cdfe`.
  A separate live read returned `[true true true true true]` from `verified?`.
- Final regression run, including namespace-free widened feedback: five tests,
  24 assertions, no failures/errors; 64952.400375 ms. Basis 536871733, run
  entity 62078, digest
  `9197962c991346f8721622204845ba72b92d8dbd1f61d8fe2e5b46ad2f38a2eb`.
- A real `seon.id-test/data-shape-and-explicit-length-determine-identity`
  check passed eight assertions in 9315.256541 ms; basis 536871702, run
  entity 61795, digest
  `c5157cfeacb740e100a4ac6be8b49fcec7dd5e1dec7a91f23a40c5c56da6380e`.
- The timeout regression waits on a real CountDownLatch and verifies that
  the named timeout is persisted as an error. The red regression verifies
  the assertion message, reached identity, persisted failure, and no escalation.
- **Equivalent `bin/test-fast` elapsed: not measured by this lane.** Starting
  its test JVM is explicitly forbidden. The gate request asks the orchestrator
  to measure the same regression namespace; no speedup ratio is claimed.

Exact feedback from the final five-test run:

```text
tests run 5 / passed 5 / failed 0 / elapsed 64952.400375 ms
run 'bin/test' '--paths' 'src/seon/test.clj' 'src/my/test.clj' 'test/seon/test_reaching_test.clj' '--' 'seon.test-reaching-test' then 'bin/test' '--paths' 'src/seon/test.clj' 'src/my/test.clj' 'test/seon/test_reaching_test.clj' '--platform'
```

The agent receives the structured result too, including every test result,
the basis and program digest. The
[live probe](reaching_tests_probe_2026_09_15.clj) documents the explicit
classpath preparation and runs the same `seon.test/check`.
The complete final response is preserved as
[EDN evidence](reaching-tests-result-2026-09-15.edn).

The documented agent example was also evaluated as written through SCI
evaluation mode, with call preparation supplying the connection:

```clojure
(my.test/check {:seon.test/changed ["my.note/add!"]
                :seon.test/paths ["src/my/note.clj"]})
```

Exact returned check data (2026-09-15, 6766.30775 ms):

```clojure
{:seon.test.run/basis-t 536871741
 :seon.test.run/program-digest "b6150f04267ac3f759b1296d7e179705a2ed29cf164808be177d71f44c5f6b69"
 :seon.test/elapsed-ms 6766.30775
 :seon.test/failed []
 :seon.test/next-tier [["bin/test" "--paths" "src/my/note.clj"]
                      ["bin/test" "--paths" "src/my/note.clj" "--platform"]]
 :seon.test/passed []
 :seon.test/results []
 :seon.test/tests []}
```

## Verification boundary and handoff

The lane used the running default JVM with real canonical database fixtures,
real SCI fixture acquisition, and armed contracts. Intermediate failed probes
were retained as failed facts, not recast as successful evidence. Cold fixture
preparation exposed missing test-only dependencies; fixture setup initially
omitted cluster config and was corrected with `seed-cluster!`.

The final API checks above exercised hot-reloaded definitions; adoption has
also converged repeatedly, but a same-commit post-edit comparison is recorded
below separately. Concurrent source edits caused expected adoption retries.
No other lane's files or sessions were operated. Default retained pid 69622;
the [MCP session-loss diagnostic](../../../seon/issues/mcp-session-loss-claims-unobserved-restart.md)
is recorded separately because it asserted an unobserved restart.

Markdown hook feedback reports existing gitlink-citation failures in
`docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`.
Those foreign documentation failures are not evidence against this slice.
No lane test JVM, full suite, lifecycle reset, or scratch cluster was used.

## Convergence and automatic feedback

Implementation commit: `e9af61d87` on `steward-platform`.

A final live comparison observed both default's `:seon.source/commit-id` and
`seon.cluster.source/current` at
`6aa9bff0-a23c-5975-9f86-44459131e3c1`. The latest adoption transaction was
536871752 and carried its submitted path, `src/seon/schedule.clj`.
The hook's automatic feedback for that subsequent adoption reported zero
reaching tests in 6626.754417 ms and the two escalation commands.

The earlier coalesced batch containing this lane's final edits and concurrent
render edits converged at `6aa9bf81-db38-526f-941f-b82b3fb44f9a`, then
automatically checked seven selected tests in the same JVM: four passed,
three failed, 55572.939417 ms, `next-tier: none`. The complete
[hook result](reaching-tests-hook-result-2026-09-15.edn) preserves the failure
messages and the identities each failing test reached:

- `seon.render.retained-test/adoption-of-an-unrelated-namespace-re-renders-zero-evaluations`
- `seon.render.web-debug-test/ledger-derives-shared-data-once`
- `seon.render.web-debug-test/turn-details-use-the-loop-opening-and-exact-segments`

These failures are the exact automatic-check boundary, not an attribution of
cause. The first two include fixture transactions returning no `:db-after`;
the third reports a Juniper `:example/order` schema declaration failure.
Their owning files were concurrently edited and were not changed by this
lane. The hook correctly retained convergence and withheld escalation.

The gate request names `seon.test-reaching-test`, `seon.test-provenance-test`,
`seon.test-runner-test`, `seon.test.selection-test`, `seon.dev.hook-test`, and
`seon.source-reconciliation-test`, plus `seon.test.runner-test`, followed by
the platform tier. It also
requests the forbidden-to-lanes `bin/test-fast` comparison. The issue index
entry for the newly recorded MCP diagnostic is left to the orchestrator.

All lane shell sessions completed. The three lane-owned verification futures
completed and were removed from `user`; disposable probe logs, classpath text,
and thread dumps were removed after copying the durable evidence above.
The shared hook publication records and orchestrator gate request remain.

## Batch 3 correction — 2026-09-15

Read the reaching-tests batch report and every reconciliation failure block
in `tmp/orchestrator/gate-results/batch-3/named.log`. The retained root
`tmp/test-runs/run.wIYxON` was not operated or removed. No test JVM was started.

### Reconciliation cause and repair

The first setup transaction failed before reconciliation or adoption ran.
A direct canonical-fixture probe of the exact `before` value returned
`:seon.db/invalid-write` at `[1 :seon.schema.admission/source]`: the first
synthetic core function row lacked its required admission-source fact. The
second core function row had the same omission. The later nil identities,
missing tombstone, missing arity, and missing unrelated agent fact were
consequences of that refused setup transaction.

The fixture now declares `:seon.schema.admission/source :core` on both core
rows and prints the complete setup refusal if it fails. The agent-authored
row remains `:agent`. Production reconciliation and adoption recording did
not change; the proposed adoption-regression attribution was falsified.

The exact live regression invocation was:

```clojure
(seon.test/run
 #'seon.source-reconciliation-test/source-reconciliation-preserves-identities-and-unrelated-facts
 (seon.operator/connection "default"))
```

It ran with the default connection's projection, after evaluating the changed
test definition in the JVM. Result: **14 passes, 0 failures, 0 errors**, basis
536871777, run entity 64327, at 2026-09-15T22:15:54.826Z. The complete
[recorded result](reaching-batch3-reconciliation-2026-09-15.edn) is retained.

### Remove work from empty checks

A direct timed probe on one immutable default database measured config
reading at 2.606334 ms, empty reach at 0.057542 ms, `my.note/add!` reach at
5155.742584 ms, and provenance at 1417.585 ms under concurrent development.
The old check always captured provenance before selection and queried each
changed identity a second time for failure attribution.

`changed-reach` now carries each identity's already-derived reaching set into
selection and failure attribution. Provenance is captured only when there is
a test to run. Empty and deferred results retain the observed basis and next
tier but omit `:seon.test.run/program-digest`: no program was tested, and no
run identity or digest is manufactured. The result contract explicitly admits
that absence. Real executed tests retain the existing provenance owner.

After evaluating the candidate forms through MCP JVM mode and re-arming with
default's projection, representative results were read completely:

- `changed-reach` and `reaching` on the reconciliation test identity returned
  that exact singleton, preserving its failure attribution.
- Direct `check-in-process` with no changed identities: 2.938583 ms.
- Public `check` on the same docs-only request: **1.80625 ms**; a prior
  adopted-definition probe measured 4.683834 ms.
- The coalesced hook adoption at `6aa9c3e8-fcb9-5173-b980-2a9e68453762`
  printed `tests run 0 / passed 0 / failed 0 / elapsed 3.718125 ms`.

The exact complete public result was:

```clojure
{:seon.test.run/basis-t 536871794
 :seon.test/elapsed-ms 1.80625
 :seon.test/failed []
 :seon.test/next-tier [["bin/test" "--paths" "docs/README.md"]
                      ["bin/test" "--paths" "docs/README.md" "--platform"]]
 :seon.test/passed [] :seon.test/results [] :seon.test/tests []}
```

The new regression removes the source seal from its canonical fixture,
positively verifies that provenance is unavailable, and checks that an empty
check still returns no tests, no digest, and no stored run. It avoids timing
thresholds and mocks: absence of the seal makes unnecessary provenance work
observable as a failure.

### Exact post-adoption boundary

The mandatory candidate-first REPL instruction arrived after these source
edits. The candidate forms `changed-reach`, `reaching`, `check-in-process`,
and `check` were then explicitly evaluated via MCP and exercised as above;
no subsequent production edit was made. The covering deftests were run
individually through `seon.test/run` and stored their results.

After coalesced adoption, the new empty-check regression and the same
reconciliation regression both returned **0 passes, 0 failures, 1 error**
before their bodies could run. The canonical base population now refuses its
schema transaction with `:seon.schema/missing-projection`. An independent
`(seon.test-support/with-database (fn [_] :fixture-ready))` probe reproduced
that exact boundary. This does not refute the earlier 14-assertion result or
establish a cause in another lane's edits. It prevents claiming a final green
regression rerun in this live JVM. Full results:
[empty check](reaching-batch3-empty-2026-09-15.edn),
[reconciliation rerun](reaching-batch3-postadopt-2026-09-15.edn).
The [fixture issue](../../../seon/issues/canonical-fixture-population-missing-carried-projection.md)
names the independent evidence. Concurrent fixture/runner changes were not
edited, reloaded, reverted, or included in this slice.

The gate request was replaced with only `seon.source-reconciliation-test`
and `seon.test-reaching-test`. The existing schema file also received foreign
fixture-observation additions during this slice; the commit includes only
this lane's optional-digest hunk, preserving those additions uncommitted.

## Resumed verification — 2026-09-15

Commit `558d5614a` already contains the empty-check optimization as well as
the fixture repair. Reverified default without a lifecycle operation or test
JVM. The docs-only request returned zero tests, zero failures, no digest,
and **1.856625 ms** elapsed at basis 536871835. The enclosing MCP form took
4266 ms, including its explicit projection acquisition; that is not the
check's measured elapsed time.

The requested two namespaces then passed through `seon.test/check` in the
same default JVM: **7 tests, 45 assertions, 0 failures, 0 errors**, elapsed
32181.538875 ms. The schema path widened selection to exactly the two supplied
namespaces. All seven results link to run entity 64448, basis 536871835,
at 2026-09-15T22:31:01.163Z, digest
`34fde7951ed260bba753fd5b7b30621e9bf08ad41034beb1af4867a101e1106a`.
The complete unelided response was read from the saved EDN and is committed
as [resumed run evidence](reaching-tests-resumed-result-2026-09-15.edn).

The exact check request, under the default connection's projection, was:

```clojure
(seon.test/check
 {:seon.db/connection (seon.operator/connection "default")
  :seon.test/changed [[:seon.ns/name 'seon.source-reconciliation-test]
                      [:seon.ns/name 'seon.test-reaching-test]]
  :seon.test/paths ["src/seon/test.clj" "resources/seon/schemas/seon.test.edn"
                    "test/seon/source_reconciliation_test.clj"
                    "test/seon/test_reaching_test.clj"]
  :seon.test/namespaces ['seon.source-reconciliation-test 'seon.test-reaching-test]})
```

The earlier live fixture refusal is no longer reproduced by this run. This
lane made no fixture-owner changes during the resumed verification and does
not attribute its recovery. No further production change was needed. The
gate request remains byte-for-byte the same two namespace lines.
