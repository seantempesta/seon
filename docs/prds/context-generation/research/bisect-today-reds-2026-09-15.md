---
type: research
status: active
tags: [test, schema, runtime]
---

# September 15 four-namespace failure investigation

## Final result

The fixture repair (`6dc70f30a`) and rendering/admission repair (`ee8d54dca`)
are committed on `steward-platform`. The required four-namespace isolated
gate passed **103 tests / 248 assertions**; the separate platform gate
passed **86 tests / 542 assertions**. Both exited 0 with **zero failures
and zero errors**. The platform snapshot had no differences from committed
HEAD `ee8d54dca` and reused the four-namespace gate's exact content digest.

The canonical platform selection reported 53 skipped long tests; no `--all`
or `--full` run is claimed. The live default-cluster projection limitation
and the protected test-provenance boundary are recorded below. Neither
contaminated the isolated gates.

Platform log SHA-256:
`df25a63eca1d5a35e6f80308ab9c67fe0fddf4267d5ade44599984f46cd0e1ff`.
Its final test completed at 20:20:25Z. Every manually launched subprocess
was reaped; successful test roots were removed by the gate. The two retained
scratch roots and `tmp/bisect-fix` were deleted after checking for live
holders. `tmp/bisect-wt` is absent. No foreign root or session was cleaned.

## Authorized repair, September 15

The owner released the cluster, instrumentation, database, and SCI evaluation
paths after the initial investigation. The repair preserves the still-protected
test runner and test-support files. `git pull --ff-only` reported up to date;
the orchestrator's database-carry change subsequently landed as `f68b79e01`.
The app restart interrupted the agent, not a test verdict; the working edits
were reread before continuing.

### Classes and owning seams

1. **Authored fixtures omitted required declaration facts and ignored setup
   refusals.** Effect/search declarations now supply admission provenance and
   namespace refs. Effect turns supply their agent and opening transaction;
   config rows come from `config/compile-manifest`, including the manifest
   digest. Fixture transactions refuse immediately on unsuccessful setup.
   `capability-fixtures-install-complete-program-declarations` verifies both
   installers' stored provenance and callable owner facts; the existing real
   dispatch, settlement, and incremental-index tests remain the behavioral
   checks. Search's literal field roster is replaced by a declaration-change
   regression, proving both addition of the new mode and removal of the old.
2. **Observation fixtures read retired result serialization and stdout APIs.**
   The parity fixture reads exact `:seon.eval/shown` and carries its database;
   MCP fixtures evaluate real SCI forms. Documentation checks inspect public
   program documentation data and typed unavailable values. They no longer
   treat empty stdout as proof. The MCP shown-text regression checks exact
   byte equality and absence of a duplicated live result; scalar SCI results
   must not produce result blobs.
3. **MCP ordinary values bypassed the shared presentation authority, and
   semantic decoding erased elision coordinates.** `mcp-project` calls
   `seon.render.value/prepare` with its explicit MCP profile. The renderer
   retains supplied requery identities; semantic decoding preserves complete
   elision values and map-member elisions. The requery reads the artifact
   through its selected cluster connection, not an invalid entity lookup on
   a blob digest. The artifact regression evaluates the advertised requery
   and compares it with the complete original value.
4. **The admission contract refused its producer's explicit unbounded mode.**
   The one request schema permits an empty caps map only when unbounded mode
   is explicitly true; bounded admission still requires its declared limits.
   `missing-artifacts-have-a-real-marker-under-armed-admission` checks a real
   reason/byte marker and independently checks bounded refusal.
5. **The structural renderer erased record identity by treating every map as
   an ordinary map.** Record nodes now keep their face and declared name,
   sharing the admission owner's SCI/JVM name derivation. Parity B9 retains
   its exact record-name-and-fields expectation.

### Four-namespace gate

The required `bin/test --paths` invocation with all nine code/schema/test
paths and all four namespaces passed **103 tests / 248 assertions,
0 failures / 0 errors**, exit 0, at 20:10:49Z. Snapshot basis `6dc70f30a`,
content digest `8b385a2dbb6d3e24f106dda301e8d68ef9700f9267e7a6eceb5a2fee95271425`.
The core repair issues are resolved and archived; the broader parity-divergence
issue remains open for its separate, explicitly recorded language differences.
The rendering slice landed as `ee8d54dca` before the platform-only gate.

Exact code/schema/test path scope for the final gates:

```sh
paths=(
  resources/seon/schemas/seon.sci.admit.edn
  resources/seon/schemas/seon.print.edn
  src/seon/render/value.clj
  src/seon/sci/admit.clj
  src/seon/cluster.clj
  test/seon/effect_test.clj
  test/seon/search_test.clj
  test/seon/repl_parity_test.clj
  test/seon/cluster/mcp_test.clj
)
SEON_TEST_WORKERS=1 SEON_TEST_SLOTS=1 bin/test --paths "${paths[@]}" -- \
  seon.cluster.mcp-test seon.effect-test seon.repl-parity-test seon.search-test
SEON_TEST_WORKERS=1 SEON_TEST_SLOTS=1 bin/test --paths "${paths[@]}" --platform
```

The successful fixture log's SHA-256 is
`58e121232c0ba6ec3279a89f4cee9d86399302986ee3d83ed6536e6ab65368ff`;
the four-namespace log's is
`2b45dd5d586ba815e829798eab573eb9efbd1367f85190ffcb656f546a25854f`.

Documentation touched in the repair is this landing note, the four resolved
issue files under `docs/seon/issues/archive/` (fixture provenance, ordinary
MCP rendering, missing-marker admission, and structural record naming),
`docs/seon/issues/repl-parity-divergences.md`, and
`docs/seon/issues/partial-hot-reload-produces-mixed-code-with-no-warning.md`.
The original paths of the three pre-existing resolved issues were moved to
the archive. The issue index and all protected files were left untouched.

### Fixture gate

`SEON_TEST_WORKERS=1 SEON_TEST_SLOTS=1 bin/test --paths test/seon/effect_test.clj test/seon/search_test.clj -- seon.effect-test seon.search-test`

The final fixture gate completed at 19:58:43Z: **19 tests / 102 assertions,
0 failures / 0 errors**, exit 0. Snapshot basis `caddf111b`, content digest
`50c977272bacb7e2a67dbb9ac80edae54e4fdd1bd0f4eb8a09d4029de0211f69`.
An earlier isolated fixture gate reported 3 failures / 0 errors in the new
search test's incomplete schema update; the final test changes the declared
attribute through `:db/add`. All effect tests passed both isolated runs.
The fixture slice landed as `6dc70f30a` before the rendering gate.

The Markdown edit hook's broad scan reported nine citation errors in the
foreign `docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`
(repository commit cited as a dependency gitlink revision). That file was
not edited by this lane; the diagnostic does not describe these source or
test changes.

### Repair probes

A non-writing live `seon.db/write-error` probe of the completed turn returned
nil (valid). Adding the old config fixture returned `:seon.db/invalid-write`
at `[0 :seon.config/applied-manifest-digest]`. This isolates the remaining
effect setup refusal from executor behavior. The desired config row is now
derived by its existing compiler rather than inventing a digest.

Serial iteration results so far:

| Snapshot | Namespaces | Tests / assertions | Failures / errors |
|---|---|---|---|
| First repair | effect, parity, search | 87 / 139 | 30 / 3 |
| Second repair | all four | 102 / 211 | 28 / 4 |
| Third repair | all four | 103 / 242 | 6 / 0 |

The second run passed record rendering and real SCI MCP shown-text cases.
It exposed the incomplete config row, semantic elision loss, and the test's
incorrect assumption that a panic-mode admission refusal returns normally.
These are iteration evidence, not the final isolated gate.

The third run passed MCP and parity completely. Three effect assertions
identified a missing evaluation identity row still required by the live
read-evidence path; the fixture now creates it through
`turn/receipt-start-tx`. Three search assertions identified an invalid test
mode (`:exact`); the declaration-change test now uses the admitted `:symbol`
mode. Neither fix weakens the production contract.

At 19:45Z, default PID 69622 still answered MCP, but its value projector
returned `:seon.config/missing-effective` for `default` instead of the
missing-marker probe's value. This live-verification limitation is recorded
in [the existing reload issue](../../../seon/issues/partial-hot-reload-produces-mixed-code-with-no-warning.md).
It is not attributed to a particular concurrent edit. The isolated test
snapshot excludes the concurrent config changes; the lane did not restart
or refork default.

An explicit stdout observation through the same live MCP connection returned
`#:probe{:value #:seon.sci.admit{:reason :over-bound, :bytes 123}, :node? true}`
in 3 ms. Thus the adopted missing-marker producer works in that JVM; the
final MCP value face remains unavailable because of its config observation.
This is not a claim that default's complete publication has converged.

The first fixture gate was stopped before test execution after discovering
the default multiworker pool. Its processes exited. Subsequent isolated
gates explicitly set `SEON_TEST_WORKERS=1 SEON_TEST_SLOTS=1`; only one test
invocation is active at a time.
The four-namespace gate reports incomplete `:seon.test` rows for the
macro-generated parity group and runs it through the canonical serial worker
alongside the single pool worker. This is the existing test-provenance
boundary, not an extra test invocation or a modified runner. All protected
runner/test-support paths remain untouched by this lane.

## Assignment and verification boundary

Investigate `seon.cluster.mcp-test`, `seon.effect-test`,
`seon.repl-parity-test`, and `seon.search-test` at the requested commit
boundaries. Run one JVM at a time in `tmp/bisect-wt`, with
`SEON_TEST_SLOTS=1` and the shared `reference-code` symlink. Preserve the
concurrent edits. The assignment explicitly protects `src/seon/instrument.clj`,
`src/seon/cluster.clj`, `src/seon/test.clj`, `src/seon/test/runner.clj`, and
`src/seon/db.clj`; a root cause in those files is a stop boundary.

Read the context-generation plan README and working edge end to end, and
the data-oriented Clojure, testing, and REPL skills. `bin/seon status`
reported default alive, PID 23729; MCP JVM evaluation of `(+ 1 1)` returned
2 in 3 ms. No default restart or adoption was performed by this assignment.

## Method

The command at each historical commit is:

```sh
SEON_TEST_SLOTS=1 bin/test-fast seon.cluster.mcp-test seon.effect-test seon.repl-parity-test seon.search-test
```

Git initially populated empty submodule directories in the worktree.
The first invocation failed before initialization because the Datastar local
dependency was absent. Removed only those empty directories with `rmdir`
and installed the requested symlink; this attempt supplies no test tally.

## Dependency ledger and initial probes

- Malli guards receive `[args value]`, including the original arguments:
  `reference-code/malli/src/malli/core.cljc:2219`. The first-party query
  guard consumes that pair in `src/seon/db.clj`, `query-call-valid?`.
- Admission's one producer is `src/seon/sci/admit.clj`, `admit*` and
  `admit-walk`; an explicitly unbounded request bypasses the storage
  bound. Its schema is `resources/seon/schemas/seon.sci.admit.edn`.
- The MCP projection owner is `src/seon/cluster.clj`, `mcp-project`;
  `src/seon/render/value.clj`, `artifact`, re-admits missing markers.

Live JVM probe (current adopted definitions, not a historical snapshot):

```clojure
(seon.render.value/artifact
 {:seon.sci.admit/reason :over-bound :seon.sci.admit/bytes 100})
```

The nested `admit-value` refuses the empty `:seon.sci.admit/caps` map:
`expected the required key :seon.config.eval.result/max-bytes with a map,
got a map missing :seon.config.eval.result/max-bytes`.
This establishes the fallback's schema/producer mismatch, independently
of the historical test tallies still being collected.

## Commit measurements

| Commit | Tests / assertions | MCP fail/error | Effect fail/error | Parity fail/error | Search fail/error | Total fail/error |
|---|---|---|---|---|---|---|
| `38c49a1db` (`6acd8818e^`) | 101 / 200 | 10 / 4 | 29 / 4 | 13 / 0 | 1 / 1 | 53 / 9 |
| `6acd8818e` | 101 / 200 | 10 / 4 | 29 / 4 | 13 / 0 | 1 / 1 | 53 / 9 |
| `806659e06` (`be4e3fe00^`) | 101 / 200 | 10 / 4 | 29 / 4 | 13 / 0 | 1 / 1 | 53 / 9 |
| `be4e3fe00` | 101 / 200 | 10 / 4 | 29 / 4 | 13 / 0 | 1 / 1 | 53 / 9 |

The pre-WIP run began reporting tests at 18:56:40Z and finished at
19:01:53Z. Each reported namespace total in the assignment (14, 33, 13,
2) equals this run's failures plus errors. All four namespaces are already
red before the WIP checkpoint. This is not evidence of a single new
September 15 regression.

The WIP run finished at 19:08Z. Comparing the multisets of failed/erroring
test names yields no additions or removals. In both snapshots, MCP plus
effects alone total **39 failures / 8 errors**, exactly the pure-HEAD
reproduction quoted in the assignment. This falsifies the WIP commit as
the origin of that failure set, not merely its aggregate count.

As requested, historical first-party revisions share the main checkout's
dependencies rather than initializing each commit's submodule revisions.
Observed dependency HEADs: Datahike `cdcb5792db8bd599487f099437265d18a31164a5`,
Malli `3517a3cd9271b2083780ac7be1725493905bca2e`,
SCI `fcbd8862800e638dc0f8f5521111f999279cbcd2`, and
core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`.

## Root-cause evidence

### Effect setup refuses before dispatch

`test/seon/effect_test.clj`, `install-capability!` and `install-arm-probe!`,
transact schema rows without `:seon.schema.admission/source`. Both ignore
the transaction result. The schema has required that member since
`06f4ebc4b8` (August 3); `26ec13420` (September 9) introduced authored
transaction validation. A non-writing live JVM `seon.db/write-error` probe
of the exact capability setup returned `:seon.db/invalid-write` at
`[0 :seon.schema.admission/source]`. The capability remains absent;
`effect/request!` returns `:seon.effect/undeclared-owner`. The missing
handler values, zero invocation counts, and missing completion events
follow from that refusal. This is not a shared executor failure.

### Search passes refused setup as a transaction report

`test/seon/search_test.clj:132` inserts a function without admission
provenance. A non-writing live probe returns the same missing-source
refusal. `search/apply-report!` extracts nil `:db-before`/`:db-after` from
that refusal and passes nil to `document-specs`'s query. The September 15
guard names the bad input; it does not make the refused fixture valid.
The other failure compares the query with a stale literal field set.

### MCP and parity retain older expectations

`9248692d6` (September 8) removed the MCP-local `print/fit` call, leaving
`projected-node print-node` in protected `src/seon/cluster.clj`. The
pre-WIP nested-bulk test emits 551,392 bytes versus the 8,192-byte
expectation. Any production repair must respect the one value-renderer
projection authority; restoring the deleted clipping seam is not a fix.

`ff9507c1b` (September 8) moved evaluation output to saved text and changed
the MCP SCI branch to recognize that text plus the record. The original
text key was `:seon.eval/value`; `ae0e54841` (September 9) renamed it to
`:seon.eval/shown`.
The MCP fixture still synthesizes an admission envelope without shown
text, so it takes the ordinary-value branch and stores the entire
envelope. `test/seon/repl_parity_test.clj:48-74` likewise still reads and
decodes `:seon.cluster.eval/result-edn`. These source-history attributions
are distinct from the measured pre-WIP tally; neither old September 8
commit has been tested by this assignment.

## Initial diagnosis outcome and stop boundary (19:19Z)

All four requested boundary snapshots produced **identical failed/erroring
test-name multisets**, not just equal totals. The last run completed at
19:19Z. Neither the WIP checkpoint nor Part A introduced this failure set.
No separate run of `7e35df213` or `a2bca009c` was necessary: the requested
endpoints surrounding them show no change. This does not claim an exhaustive
earliest-introduction bisect of every September 15 commit. The older
September 7–9 attributions above are source-history evidence, and are
explicitly not measured before/after test results.

The production MCP root cause reaches protected `src/seon/cluster.clj`,
`mcp-project`, specifically the unchanged full print node at line 384 and
its direct semantic decode at line 410. Followed the assignment's explicit
stop boundary: **no production or test implementation was changed**.
The fixture repairs and admission fallback remain open in the issue notes;
this is a diagnosis landing, not a fixed or green result. No repair
`bin/test --paths` or `--platform` gate is claimed.

Recurring subjects and remaining work are recorded in:

- [Fixture declaration provenance](../../../seon/issues/archive/test-program-rows-omit-admission-provenance.md).
- [Ordinary MCP value rendering](../../../seon/issues/archive/mcp-ordinary-values-bypass-the-value-renderer.md).
- [Missing-marker admission](../../../seon/issues/archive/missing-artifact-marker-refuses-its-own-admission-contract.md).
- [REPL parity observations](../../../seon/issues/repl-parity-divergences.md).

[Machine-readable evidence](bisect-today-reds-2026-09-15.json) preserves
the four summaries, per-namespace counts, each failing test's occurrence
count, and SHA-256 of each full log. The committed
[summarizer](bisect_today_reds_2026_09_15.py) reproduces that extraction.
The unchanged canonical invocation is recorded above. All test subprocesses
exited before cleanup; the worktree and this assignment's scratch logs were
removed. No other lane's files, sessions, or scratch roots were operated.
