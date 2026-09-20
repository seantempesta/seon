---
type: research
status: step 2 landed; scratch boot and parity proven; cold gate owed
created: 2026-09-21
tags: [schema, malli, bridge, projection]
---

# Bridge step 2: compiled walker

## First platform gate repairs, 2026-09-22

Read the one-JVM publication redesign end to end again for this bounded
follow-up. Cold evidence is `tmp/orchestrator/gate-cd701afc2-platform.log`;
its retained root is `tmp/test-runs/run.HIBgd6`. The gate had 97 platform
tests, 6 failures and 12 errors. No default lifecycle action, worktree,
foreign session action or cold gate is used by this lane. Redesign-owned
source paths remain untouched. Every fast wall-clock below is measured by
an outer monotonic timer, including snapshot, JVM acquisition and recording.

### Class 1 — registry construction and retained blob reads

Root cause: `with-source-store` passed a forms-only map to the retained-root
bridge even though it separately constructed a complete projection for key
selection. The fixture now builds one projection and supplies it to native
declarations, attribute selection and canonical schema rows. Malli's
`registry/schema` (`reference-code/malli/src/malli/registry.cljc:97`) requires
an actual registry; the bridge remains strict, with no nil fallback.

The first fast run exposed a previously masked production error: registry
blob collection still recognized the non-temporal history error by the retired
kind key, queried the error map as a database, and failed to retain a current
blob. `seon.cluster.registry/branch-blobs` now recognizes the declared
`:seon.config/error-key` for `:seon.config.db/keep-history?`. Any other
unavailable history observation refuses collection before deletion. Datahike's
history admission is at `reference-code/datahike/src/datahike/api/impl.cljc:185`.
The existing non-temporal collection regression covers the correction.

Fast commands select `seon.cluster.registry-test`, first with the test path,
then with `src/seon/cluster/registry.clj` and
`test/seon/cluster/registry_test.clj`. First: 12 tests, 73 assertions,
1 failure, 0 errors, **118.135069333 s** (`tmp/bridge-platform-class1.log`).
Final: **12 executed, 73 assertions, 0 failures, 0 errors**, exit 0,
**114.314524375 s** (`tmp/bridge-platform-class1-fixed.log`). Maximum measured
test body **4.823160 s**; no long allowance is needed. Source require of
`seon.cluster.registry` and `seon.schema.datahike` brackets the commit.

### Class 2 — stored history and transaction refusal assertions

Root cause: the store tests asserted retired kind markers instead of the
declared error schemas. `db/history` already declares `:seon.config/error`
through `:seon.db/error-result`; its keep-history member names the refusal.
The third assertion concerns a rejected Double transaction, whose declared
schema is `:seon.db.write/validation-refusal`, not history. The tests now
validate those schemas through the carried canonical projection.

Fast command: `bin/test-fast --paths test/seon/cluster/store_test.clj -- seon.cluster.store-test`.
First run: 18 tests, 72 assertions, 0 failures, 2 errors, **124.522921542 s**
(`tmp/bridge-platform-class2.log`). Both errors were cold child JVMs exceeding
the shared 20-second event wait in existing long flock tests. Those tests now
declare 60,000 ms with their cold-start reason and use that declaration for
child readiness. Final: **18 executed, 74 assertions, 0 failures, 0 errors**,
exit 0, **108.026006375 s** (`tmp/bridge-platform-class2-fixed.log`). Child
tests took 14.595908 and 14.725255 s; all other bodies were under 0.7 s.
Source require of `seon.cluster.store` and `seon.db` brackets the commit.

### Class 3 — missing projection assertions

Root cause: the read-carriage regression expected retired kind/data members;
`seon.db/projection-fallback` already declares and produces
`:seon.schema/validation-refusal`. The regression now validates that schema,
`:seon.schema/expected-value` and the operation in `:seon.schema/refused-value`.
The actual namespace is `seon.db.declaration-population-test`.

Fast command: `bin/test-fast --paths test/seon/db/declaration_population_test.clj -- seon.db.declaration-population-test`.
First: **1 executed, 32 assertions, 0 failures, 0 errors**, **226.073580125 s**
(`tmp/bridge-platform-class3.log`). The 148.414058-second body includes first
canonical fixture construction; a thread sample located the wait at
`retrying-base/acquire-base!`, with the builder indexing canonical program
facts (`tmp/bridge-platform-class3-threads.txt`). The test now declares
180,000 ms with that reason. Updated-bound run: **1 executed, 32 assertions,
0 failures, 0 errors**, exit 0, **216.796402125 s**
(`tmp/bridge-platform-class3-bounded.log`). Source require of `seon.db`
brackets the commit. No fixture implementation or publication owner changed.

### Class 4 — custody wrapper return contracts

Root cause: the custody wrappers transparently return the callback's
missing-connection error, but their polymorphic return contracts did not
declare `:seon.schema/validation-refusal`. Both `call-with-custody` and its
`call-without-custody` caller now declare that schema alongside the arbitrary
callback result. The custody regression validates the error schema and its
expected connection member. No execution behavior changed.

Fast command: `bin/test-fast --paths src/seon/db.clj test/seon/test_support_test.clj -- seon.test-support-test`.
The custody regression passed in **0.673291 s**. The whole namespace run took
**749.105036750 s**, exit 1 (`tmp/bridge-platform-class4.log`); its sole
failure is the unchanged class 5 kind-only fixture. Independent canonical
population and isolated fixture publication dominate this namespace's cost;
the thread sample is `tmp/bridge-platform-class4-threads.txt`. The final
class 5 run below is the combined green proof. Source require of `seon.db`
brackets this class's commit.

## Final ruled slice and verification

This section supersedes the historical checkpoints below. The orchestrator
ruled option 3 after `fdfc564af`: error observations must not carry another
entity's upsert identity. The Datahike `upsert-eid` and
`validate-datom-upsert` implementation in
`reference-code/datahike/src/datahike/db/transaction.cljc:530` and `:641`
confirms that the former bootstrap errors could merge into the turn row.
The five declarations and their live producers are converted. The census
script `tmp/orchestrator/identity-in-error-schemas.clj` now reports 43 identity
attributes and zero error schemas requiring one. No live producer of the
unresolved-test-var error was found; its declaration is converted.

The compiled admission seam now refuses an error extending `:seon.error/base`
with a declared identity attribute member, naming both schema and attribute.
Required and optional members both refuse. The synthetic canonical regression
is `error-members-cannot-carry-another-entitys-upsert-identity`.
Writer selection again maps each identity to every entity schema requiring it;
`declared-row-schema` and its exception regression are deleted. The replacement
regression checks turn, function and test identities. The
`:seon.program/row-schema` property is **retained**: independent consumers remain
in `seon.program/derived-shape`, db read-target selection, schema target
derivation and fn indexing. Removing that declaration would break those owners.

**RESET NEEDED**, as explicitly authorized for these observation changes:

| Error schema | Old member | New member |
|---|---|---|
| `:seon.bootstrap/prefix-drift-error` | `:seon.turn/id` | existing `:seon.error/run`, lookup ref |
| `:seon.bootstrap/unmatched-source-error` | `:seon.turn/id` | existing `:seon.error/run`, lookup ref |
| `:seon.test.accretion/install-refused-error` | `:seon.fn/sym` | `:seon.test.accretion/function-sym`, qualified-symbol value |
| `:seon.test.runner/invalid-marker-reason-error` | `:seon.test/sym` | `:seon.test.runner/test-sym`, qualified-symbol value |
| `:seon.test.runner/unresolved-test-var-error` | `:seon.test/sym` | `:seon.test.runner/test-sym`, qualified-symbol value |

These are five schema-member changes and two new native attributes; the
domain identity attributes remain. No default lifecycle action was taken.

### Scratch boot and independent parity

The required sequence completed on `tmp/bridge-step2-root`: `init`, `init s2`,
`start s2`, status and MCP runtime observation, then `down` and root deletion.
Publication commit `6ab0084b-e085-5597-9a09-83f315f574a2`, digest
`f83eb2608414c78b522b23912484f2f249157804e19081bece3f7423196f12dc`,
was forked by `s2`. PID 22508 reached readiness in 29,123 ms. All observed flow
procs replied; runtime problem counts were empty. The actual startup turn
`12a2b18544e6` exists, and its identity selects only `:seon.turn/turn`.
The first fork preflight exceeded 20,000 ms at 20,434 ms; its retry passed
in 18,166 ms. No bound or runtime owner was changed.

The [reproducible capture](bridge-step2-parity-capture-2026-09-21.clj) loads
the old implementation from pinned Git commit `22a1a0567` into temporary
probe namespaces in that same scratch JVM. It captures the full canonical
population: **3,372 schemas, 1,209 attributes/native declarations, zero
ordered-key or native-map mismatches**. Input digest:
`99715b5035858f4fc2900996af92b99541f4a4635b19a0ef8b7d832f5345ad0b`.
The original **1,012 attributes** also compare exactly, keys and native maps.
Historical definitions are materialized for this equality, not re-admitted
under today's new error-identity prohibition. The retained regression carries
both complete independent captures; no production or test copy of the raw
walker survives. The direct synthetic admission probe named
`:probe.bridge/observation` and `:seon.turn/id` for both optionality settings.

Logs: `tmp/bridge-step2-scratch-init.log`, `scratch-fork-retry.log`,
`scratch-start.log`, `scratch-status.log`, and `scratch-down.log` under the
same `tmp/bridge-step2-` prefix. Down confirmed the store flock free, and PID
22508 was absent before deleting the owned root. This proves a fresh owned
fork, not hot reload or default adoption.

### Fast evidence and each prior failure

The last completed recorded construction request remains `adf54170ac72`:
72 executed, 0 unchanged, 11,012 assertions, 12 failures, 2 errors.
Disposition of all fourteen observations:

| Observations | Disposition |
|---|---|
| 2 parity input failures | Recaptured the full current input with the pinned old mapper; live exact equality above. |
| 5 direct identity-member failures | Five conversions above; zero-hit census, admission refusal and successful actual startup turn. |
| 4 alias uniqueness failures | [Existing mapping class](../../../seon/issues/error-observation-aliases-inherit-native-uniqueness.md); preserved old native parity, not weakened assertions. |
| 1 predicate diagnostic failure | [Reopened predicate class](../../../seon/issues/archive/predicate-schema-violations-humanize-to-unknown-error.md), held effect result-validator declaration. |
| 1 schema-audit missing-projection error | Fixed fixture acquisition from bare deref to `db/db`; final rerun unavailable. |
| 1 renderer contract error | [Exact fixture issue](../../../seon/issues/renderer-codec-fixture-loses-a-declared-contract.md); analyzed/stored spec assertions added. Corresponding direct production publication passed in s2; recorded fixture rerun owed. |

The interrupted broad run's three turn errors are covered by the identity
repair; its other two blocks belong to the
[reply return-contract issue](../../../seon/issues/reply-sources-return-contract-disagrees-with-vector-results.md).
No completed tally is claimed for that interrupted run.

Latest fast request `801ecf27ddec`, log `tmp/bridge-step2-five-values.log`,
armed 1,479 contracts (1,475 program-armable), then executed **zero tests**.
The **new snapshot JVM 19992**, in `seon.test.runner/record-snapshot!`, refuses
published base `e8cb1a8c76cfe6b393cf4b1a167ff815b1dbd56ef90d15c2373fa7fa53635411`:
its old `:seon.bootstrap/prefix-drift-error` still requires `:seon.turn/id`.
This is not the working population, and not an old store-holder JVM's check.
There is no durable tally. Refreshing that published base belongs to the
publication/gate owner; the orchestrator explicitly ruled it nonblocking for
landing. Earlier attempts in `identity-conversion.log` and
`identity-admission.log` failed before tests while developing the guard; both
owned guard defects were corrected before the successful scratch publication.

The shell handlers continue to pass their existing context's generation
explicitly downstream. No request metadata carrier or protocol change was
introduced. The handler protocol's explicit environment argument remains the
effect owner's Phase-3 item; the handler inventory appears below.

### Owner's timing addition

Read the entire
[one-JVM redesign](../plan/one-jvm-publication-redesign-2026-09-22.md)
on resumption. Bridge does not implement its operator, analysis or runner
slices. Tests without a declared long allowance are subject to the ruled
5,000 ms limit. Latest available per-test BEGIN/END timings identify four
exceptions among the touched tests:

| Test | Measured ms | Declared bound ms |
|---|---:|---:|
| `my.test-test/an-agents-own-test-reaches-its-cluster-through-the-elided-arity` | 102,309 | 300,000, existing SCI acquisition reason |
| `seon.ai-stream-fold-test/settled-reasoning-reuses-the-eval-result-inline-blob-split` | 65,264 | 90,000, isolated file store and provider settlement |
| `seon.schema.datahike-test/agent-authored-render-symbols-cross-the-transaction-function-codec` | 99,638 | 120,000, canonical fixture and three analyzed renderer publications |
| `seon.schema.datahike-test/supported-ast-wrappers-and-aliases-have-one-declaration` | 15,243 | 25,000, 80 generated canonical admission cases |

The last three now carry both `:seon.test/long` reasons and `:seon.test/long-ms`.
All other measured latest test bodies were under five seconds. Unexecuted
members have no claimed timing proof. The redesign's runner-wide default
enforcement remains with its owner; this slice adds no alternate timer.

Historical fast wall-clock evidence was not uniformly captured by an outer
timer. The retained log creation-to-final-write intervals are listed below
as **log windows**, not invented process-exit measurements. Logs reused across
attempts cannot establish a run wall-clock. The final run added an explicit
monotonic subprocess timer; its exact result is recorded separately below.

| Fast log under `tmp/bridge-step2-` | Log window seconds |
|---|---:|
| `caller-proof.log` | 21.628 |
| `caller-proof-repaired.log` | 25.610 |
| `compiled-iteration.log` | 31.216 |
| `compiled-parity.log` | 198.424 |
| `complete-overlay.log` | 272.984 |
| `components.log` | 179.216 |
| `construction.log` | 682.408 |
| `construction-final.log` | unavailable: reused across attempts |
| `five-values.log` | 112.741 |
| `identity-admission.log` | 64.775 |
| `identity-conversion.log` | 62.582 |
| `keyword-proof.log` | 199.563 |
| `morning-iteration.log` | 134.167 |
| `native-proof.log` | 129.124 |
| `navigation-counts.log` | unavailable: reused across attempts |
| `optional-landed.log` | 30.771 |
| `optional.log` | 33.551 |
| `parity-proof.log` | 368.526 |
| `retirement-construction.log` | 208.677 |
| `retirement-fast.log` | 226.729, interrupted |
| `shapes.log` | 591.435 |

Final timed fast request **`f14ad0d780ef`**: **101.804390875 seconds** wall-clock,
exit 1, six requested namespaces, zero executed, no durable tally. Snapshot
JVM **27074** again refused the old published base at `record-snapshot!`, naming
`:seon.bootstrap/prefix-drift-error` and `:seon.turn/id`. Its HEAD was
`21d12034331a10875f305a1ef8fe51ce1ea2dd32`; log and timer are
`tmp/bridge-step2-final-fast.log` and `tmp/bridge-step2-final-fast-time.json`.
This ran after the long-test metadata additions. No unchanged namespace was
rerun after that refusal. The scratch proof above preceded only these test
metadata and documentation edits; its production implementation is unchanged.

The coherent commit uses an explicitly path-limited index. In `cluster.clj`,
only the previously staged bridge conversions are included; the foreign
unstaged `:seon.source/progress! report-source-progress!` addition to
`build-manifest` is preserved outside the commit. The shared working tree's
require command passed with that additive hunk present. No foreign source
was edited, and no worktree was created.

### Final retirement measurements

No executable `seon.schema.form`, retired alias resolver, raw child helper or
raw cardinality helper remains in src/test/script/bin/resources. Relative to
the step-1 owner baseline: form **216 → 0**, datahike **635 → 555**, internal
**453 → 520**, schema **3,863 → 3,852** physical lines. Admission added the
identity rule; moved component widening, shared entity policy and all caller
conversions remain in their existing owners. Relative to launch HEAD, the
four owner files show 238 insertions and 779 deletions (including the raw
file's 216-line deletion); these are not claimed as all navigational code.

## Final path-limited cold command owed

The orchestrator must refresh the published base for the new observation
attributes before this cold proof. These 96 explicit paths are the bridge
slice; the staged caller conversion in cluster.clj must not absorb the
concurrent publication-progress hunk. The 57 namespaces include the spec
selection, propagated callers, and the newly converted error producers.
Neither command was run by this lane.

```bash
bin/test --paths \
  docs/prds/steward-platform/research/bridge-step2-parity-capture-2026-09-21.clj \
  docs/prds/steward-platform/research/bridge-step2-walker-2026-09-21.md \
  docs/seon/issues/archive/predicate-schema-violations-humanize-to-unknown-error.md \
  docs/seon/issues/error-observation-aliases-inherit-native-uniqueness.md \
  docs/seon/issues/inherited-error-facets-select-ordinary-identity-write-schemas.md \
  docs/seon/issues/renderer-codec-fixture-loses-a-declared-contract.md \
  docs/seon/issues/reply-sources-return-contract-disagrees-with-vector-results.md \
  resources/seon/schemas/seon.bootstrap.edn \
  resources/seon/schemas/seon.test.accretion.edn \
  resources/seon/schemas/seon.test.edn \
  resources/seon/schemas/seon.test.runner.edn \
  src/seon/agent.clj \
  src/seon/ai.clj \
  src/seon/bootstrap.clj \
  src/seon/cluster.clj \
  src/seon/cluster/source.clj \
  src/seon/config.clj \
  src/seon/db.clj \
  src/seon/error.clj \
  src/seon/fn.clj \
  src/seon/issue.clj \
  src/seon/maintenance.clj \
  src/seon/print.cljc \
  src/seon/program.cljc \
  src/seon/reconcile.cljc \
  src/seon/render.clj \
  src/seon/render/transcript.clj \
  src/seon/render/walk.clj \
  src/seon/render/web.clj \
  src/seon/schema.clj \
  src/seon/schema/datahike.clj \
  src/seon/schema/form.cljc \
  src/seon/schema/internal.cljc \
  src/seon/sci/eval.clj \
  src/seon/shell/jvm.clj \
  src/seon/test/accretion.clj \
  src/seon/test/runner.clj \
  src/seon/turn.clj \
  test/my/test_test.clj \
  test/seon/ai_stream_fold_test.clj \
  test/seon/ai_test.clj \
  test/seon/bootstrap_drive_test.clj \
  test/seon/classification_test.clj \
  test/seon/cluster/agent_test.clj \
  test/seon/cluster/armed_test.clj \
  test/seon/cluster/boot_test.clj \
  test/seon/cluster/mcp_test.clj \
  test/seon/cluster/message_test.clj \
  test/seon/cluster/program_restart_test.clj \
  test/seon/cluster/publication_facet_test.clj \
  test/seon/cluster/registry_test.clj \
  test/seon/cluster/source_test.clj \
  test/seon/cluster/store_transact_test.clj \
  test/seon/cluster/turn_test.clj \
  test/seon/cluster/wake_test.clj \
  test/seon/cluster_test.clj \
  test/seon/concurrency_independence_test.clj \
  test/seon/config_application_test.clj \
  test/seon/config_test.clj \
  test/seon/db_test.clj \
  test/seon/error_class_schema_test.clj \
  test/seon/error_test.clj \
  test/seon/flow_test.clj \
  test/seon/fn_test.clj \
  test/seon/gen/loop_test.clj \
  test/seon/html_views_test.clj \
  test/seon/instrument_test.clj \
  test/seon/loop_proof_test.clj \
  test/seon/maintenance_schema_test.clj \
  test/seon/maintenance_test.clj \
  test/seon/owned_value_test.clj \
  test/seon/problems_test.clj \
  test/seon/program_test.clj \
  test/seon/render_coverage_test.clj \
  test/seon/reset_edges_test.clj \
  test/seon/schema/admission_test.clj \
  test/seon/schema/datahike_parity.edn \
  test/seon/schema/datahike_test.clj \
  test/seon/schema/declaration_population_test.clj \
  test/seon/schema/edn_test.clj \
  test/seon/schema/program_test.clj \
  test/seon/schema_audit_test.clj \
  test/seon/schema_test.clj \
  test/seon/schema_usage_guard_test.clj \
  test/seon/sci/eval_instrumentation_test.clj \
  test/seon/sci/eval_test.clj \
  test/seon/shell/jvm_test.clj \
  test/seon/test/runner_test.clj \
  test/seon/test/selection_test.clj \
  test/seon/test_failure_facts_test.clj \
  test/seon/test_support.clj \
  test/seon/test_support_test.clj \
  test/seon/test_test.clj \
  test/seon/turn_backstop_test.clj \
  test/seon/turn_continue_test.clj \
  test/seon/turn_loop_test.clj \
  -- \
  my.test-test \
  seon.ai-stream-fold-test \
  seon.ai-test \
  seon.bootstrap-drive-test \
  seon.classification-test \
  seon.cluster-test \
  seon.cluster.agent-test \
  seon.cluster.armed-test \
  seon.cluster.boot-test \
  seon.cluster.mcp-test \
  seon.cluster.message-test \
  seon.cluster.program-restart-test \
  seon.cluster.publication-facet-test \
  seon.cluster.registry-test \
  seon.cluster.source-test \
  seon.cluster.store-transact-test \
  seon.cluster.turn-test \
  seon.cluster.wake-test \
  seon.concurrency-independence-test \
  seon.config-application-test \
  seon.config-test \
  seon.db-test \
  seon.error-class-schema-test \
  seon.error-test \
  seon.flow-test \
  seon.fn-test \
  seon.gen.loop-test \
  seon.html-views-test \
  seon.instrument-test \
  seon.loop-proof-test \
  seon.maintenance-schema-test \
  seon.maintenance-test \
  seon.owned-value-test \
  seon.problems-test \
  seon.program-test \
  seon.render-coverage-test \
  seon.reset-edges-test \
  seon.schema-audit-test \
  seon.schema-test \
  seon.schema-usage-guard-test \
  seon.schema.admission-test \
  seon.schema.datahike-test \
  seon.schema.declaration-population-test \
  seon.schema.edn-test \
  seon.schema.program-test \
  seon.sci.eval-instrumentation-test \
  seon.sci.eval-test \
  seon.shell.jvm-test \
  seon.test-failure-facts-test \
  seon.test-support-test \
  seon.test-test \
  seon.test.accretion-test \
  seon.test.runner-test \
  seon.test.selection-test \
  seon.turn-backstop-test \
  seon.turn-continue-test \
  seon.turn-loop-test
bin/test --platform
```

## Historical decision: write-schema selection after compiled inheritance

This section supersedes the historical checkpoints below. The complete
specification, binding PRD, accepted review and step-1 note were read end
to end. All callers are converted in the working tree; the raw file is
removed there, but the coherent retirement commit is **not landed**.
The formerly held error/test-support tests were clean before conversion.
The cluster guard/export commits were re-read and their landed behavior
preserved. No other lane's session or default was operated.

The 55-namespace snapshot at `acb39cb6372919d94f409ea61c1c4e07526fc35f`
loaded and armed 1,507 contracts (1,474 program-armable), selecting 969 tests.
It completed 78 test bodies and began a 79th before this lane stopped its
own JVM 7478 at the new decision. Five error blocks were printed; no full
or durable tally exists. Raw evidence: `tmp/bridge-step2-retirement-fast.log`.
Three blocks reject ordinary turns for missing error fields; two blocks
are `seon.cluster.reply/sources` return-contract refusals, observed but not
attributed to this conversion without a baseline proof.

**New decision:** the compiled `write-entity-schemas` selection inherits
`:seon.db/attributes` through `:seon.error/base`. The old writer's
one-argument raw inspection did not follow that reference. Bootstrap error
facets requiring `:seon.turn/id` therefore become ordinary turn write schemas,
refusing a valid turn for missing `:seon.error/at`. The accepted
`:seon.program/row-schema` adaptation remains intact but does not cover
`:seon.turn/id`. This is recorded in
[the writer-selection issue](../../../seon/issues/inherited-error-facets-select-ordinary-identity-write-schemas.md).
The spec forbids changing writer selection guarantees in this slice without
a ruling. No repair of this policy has been applied.

1. **Recommended — preserve explicit write declarations using compiled
   nodes.** Retain the old writer's storage-declaration selection, including
   inline composition, while compiled schemas still validate the complete
   selected entity. Guarantee: navigation changes without broadening the
   writer's selected schemas. Cost: 2–4 hours and a canonical regression.
   Give up: inherited storage membership alone selecting a write schema.
2. **Declare row schemas on every identity.** Extend the accepted explicit
   row-schema rule to turn, agent and other domain identities. Guarantee:
   each identity names its write schema. Cost: about one lane-day plus
   resource-owner coordination. Give up: the current resource scope and
   implicit multiple-schema selection for those identities.
3. **Separate observed identity tokens in error facets.** Introduce the
   observation attributes and convert those producers with their owners.
   Guarantee: observing a turn identity cannot select its write schema.
   Cost: 1–2 lane-days across owners. Give up: landing bridge step 2 before
   that error-family conversion.

The Var-contract arming refusal had an owned cause: syntax preparation
rejected Var schema references before the existing Malli var-registry could
resolve them. Structural preparation now preserves Vars as opaque references;
a canonical regression exercises their round trip. The broad snapshot
successfully armed the previously refusing development linter.

The preceding four-namespace request `2a6628354fa8` completed 72 tests,
2,580 assertions, 9 failures and 4 errors. Its snapshot JVM 2031 called
`seon.test.runner/record-snapshot!` -> `record-persistent-results!` ->
`seon.cluster.store/open-store!`; the check refused `:held-elsewhere` for
`/Users/sean/src/seon/data/store`. This was the snapshot recorder trying to
acquire the published store, not a refusal from the store-holder's old JVM.
Publication/gate owners retain that follow-up. Log:
`tmp/bridge-step2-construction-final.log` (this resumed log supersedes the
older same-named scratch log whose historical digest remains below).

Corrections since that request: independent old-bridge recapture after the
new call-preparation declarations; explicit projection throughout
`program/declaration-row`; current declaration reads in the transaction-codec
fixture; retained roots in canonical test loops; the declared
`:seon.fn.arity/guard-schema` attribute in the graph audit; a complete
synthetic config dial. The held effect resource still declares
`:seon.effect/result-validator` without `:error/message`; the existing
predicate-diagnostic regression names it. Its owner was notified through
the orchestrator question; this lane did not edit that resource.

### Retirement measurements and proof handoff

Executable census (`src`, `test`, `script`, Clojure files): zero
`seon.schema.form` references after removing the temporary capture writer.
The raw namespace deletion and every caller conversion remain one pending
commit. Original 1,012-attribute evidence is retained under
`:seon.bridge.parity/initial-baseline`, alongside the refreshed independent
capture. No expected native declaration was generated by the compiled fold.
The refreshed capture loads `22a1a0567:src/seon/schema/datahike.clj` under a
private temporary namespace while the old raw helper was still present,
then runs the same canonical capture described below; no duplicate walker
remains in source or tests.

Physical owner LOC against step 1 `dc1efaf3c`:

| Owner | Before | Working tree |
|---|---:|---:|
| schema/form.cljc | 216 | 0 |
| schema/datahike.clj | 635 | 537 |
| schema/internal.cljc | 453 | 520 |
| schema.clj | 3,863 | 3,848 |
| Total | 5,167 | 4,905 |

Net reduction: 262 physical lines, including the earlier additive checkpoint.
The measured earlier warmed navigation result remains zero compiler calls,
zero registry constructions and zero copied entries over three complete
passes. Final construction tally/load evidence is recorded below when the
current request ends. No cold gate, platform proof, live adoption or browser
proof was run. These remain orchestrator-owned after the writer ruling.

Complete owned path list and exact eventual cold command (do not run until
the decision is implemented and the coherent retirement lands):

```sh
bin/test --paths \
  docs/prds/steward-platform/research/bridge-step2-walker-2026-09-21.md \
  docs/seon/issues/inherited-error-facets-select-ordinary-identity-write-schemas.md \
  resources/seon/schemas/seon.test.accretion.edn \
  resources/seon/schemas/seon.test.edn \
  src/seon/agent.clj \
  src/seon/ai.clj \
  src/seon/cluster.clj \
  src/seon/cluster/source.clj \
  src/seon/config.clj \
  src/seon/db.clj \
  src/seon/error.clj \
  src/seon/fn.clj \
  src/seon/issue.clj \
  src/seon/maintenance.clj \
  src/seon/print.cljc \
  src/seon/program.cljc \
  src/seon/reconcile.cljc \
  src/seon/render.clj \
  src/seon/render/transcript.clj \
  src/seon/render/walk.clj \
  src/seon/render/web.clj \
  src/seon/schema.clj \
  src/seon/schema/datahike.clj \
  src/seon/schema/form.cljc \
  src/seon/schema/internal.cljc \
  src/seon/sci/eval.clj \
  src/seon/shell/jvm.clj \
  src/seon/turn.clj \
  test/my/test_test.clj \
  test/seon/ai_stream_fold_test.clj \
  test/seon/ai_test.clj \
  test/seon/bootstrap_drive_test.clj \
  test/seon/classification_test.clj \
  test/seon/cluster/agent_test.clj \
  test/seon/cluster/armed_test.clj \
  test/seon/cluster/boot_test.clj \
  test/seon/cluster/mcp_test.clj \
  test/seon/cluster/message_test.clj \
  test/seon/cluster/program_restart_test.clj \
  test/seon/cluster/registry_test.clj \
  test/seon/cluster/source_test.clj \
  test/seon/cluster/store_transact_test.clj \
  test/seon/cluster/turn_test.clj \
  test/seon/cluster/wake_test.clj \
  test/seon/cluster_test.clj \
  test/seon/concurrency_independence_test.clj \
  test/seon/config_application_test.clj \
  test/seon/config_test.clj \
  test/seon/db_test.clj \
  test/seon/error_class_schema_test.clj \
  test/seon/error_test.clj \
  test/seon/flow_test.clj \
  test/seon/fn_test.clj \
  test/seon/gen/loop_test.clj \
  test/seon/html_views_test.clj \
  test/seon/instrument_test.clj \
  test/seon/loop_proof_test.clj \
  test/seon/maintenance_schema_test.clj \
  test/seon/maintenance_test.clj \
  test/seon/owned_value_test.clj \
  test/seon/problems_test.clj \
  test/seon/program_test.clj \
  test/seon/render_coverage_test.clj \
  test/seon/reset_edges_test.clj \
  test/seon/schema/admission_test.clj \
  test/seon/schema/datahike_parity.edn \
  test/seon/schema/datahike_test.clj \
  test/seon/schema/declaration_population_test.clj \
  test/seon/schema/edn_test.clj \
  test/seon/schema/program_test.clj \
  test/seon/schema_audit_test.clj \
  test/seon/schema_test.clj \
  test/seon/schema_usage_guard_test.clj \
  test/seon/sci/eval_instrumentation_test.clj \
  test/seon/sci/eval_test.clj \
  test/seon/shell/jvm_test.clj \
  test/seon/test/runner_test.clj \
  test/seon/test/selection_test.clj \
  test/seon/test_failure_facts_test.clj \
  test/seon/test_support.clj \
  test/seon/test_support_test.clj \
  test/seon/test_test.clj \
  test/seon/turn_backstop_test.clj \
  test/seon/turn_continue_test.clj \
  test/seon/turn_loop_test.clj \
  -- \
  my.test-test \
  seon.ai-stream-fold-test \
  seon.ai-test \
  seon.bootstrap-drive-test \
  seon.classification-test \
  seon.cluster-test \
  seon.cluster.agent-test \
  seon.cluster.armed-test \
  seon.cluster.boot-test \
  seon.cluster.mcp-test \
  seon.cluster.message-test \
  seon.cluster.program-restart-test \
  seon.cluster.registry-test \
  seon.cluster.source-test \
  seon.cluster.store-transact-test \
  seon.cluster.turn-test \
  seon.cluster.wake-test \
  seon.concurrency-independence-test \
  seon.config-application-test \
  seon.config-test \
  seon.db-test \
  seon.error-class-schema-test \
  seon.error-test \
  seon.flow-test \
  seon.fn-test \
  seon.gen.loop-test \
  seon.html-views-test \
  seon.instrument-test \
  seon.loop-proof-test \
  seon.maintenance-schema-test \
  seon.maintenance-test \
  seon.owned-value-test \
  seon.problems-test \
  seon.program-test \
  seon.render-coverage-test \
  seon.reset-edges-test \
  seon.schema-audit-test \
  seon.schema-test \
  seon.schema-usage-guard-test \
  seon.schema.admission-test \
  seon.schema.datahike-test \
  seon.schema.declaration-population-test \
  seon.schema.edn-test \
  seon.schema.program-test \
  seon.sci.eval-instrumentation-test \
  seon.sci.eval-test \
  seon.shell.jvm-test \
  seon.test-failure-facts-test \
  seon.test-support-test \
  seon.test-test \
  seon.test.runner-test \
  seon.test.selection-test \
  seon.turn-backstop-test \
  seon.turn-continue-test \
  seon.turn-loop-test
bin/test --platform
```

### Latest completed construction evidence

Run `adf54170ac72`, snapshot JVM 10151, recorded **72 executed, 0 unchanged,
11,012 assertions, 12 failures and 2 errors**. Recording succeeded this time;
the earlier held-store refusal is not a current recorder claim. Log:
`tmp/bridge-step2-retirement-construction.log`.

The compiled result has **3,370 forms and 1,207 attributes**, with **zero
missing/extra attribute keys and zero per-key native map differences**,
including ordered output, core selection and property selection. The original
1,012-attribute capture also matches. The two parity failures are input
identity only: the gate owner's newly landed `:seon.test.runner/report-options`
changed the forms/digest after the independent capture. Captured input:
`fcf3bcc28f9e42fd0175ca10efe83e0337b063d1566e5a3ba411fa29e89d6eff`;
current input:
`7a2b2424c95c24e5456d22705ac697ec5632d056caeba4d4c7605aca75e23754`.
This is not a green whole-population regression; its frozen input must be
recaptured independently when the population settles.

Three complete navigation passes measured **0 compiler calls, 0 registry
constructions, 0 copied entries**. The dependent-generation regression
observed 4,905 named providers, maximum one call, with 5,053 sealed entries;
all its retention/recompilation assertions passed. The optional unstorable
facet, Var-reference round trip, pure-data materialization, local recursive
component preparation, native refs/tuples and ordinary codec cases passed.

Remaining failures: nine unique-identity observations in the whole error
facet closure, the held effect predicate's missing diagnostic, and the two
input-drift assertions above. The renderer transaction-codec error still
reports an unavailable renderer contract despite explicit writer derivation;
the earlier attribution to fixture projection staleness was not sufficient
and is withdrawn. This remains an unresolved fixture/contract observation,
not a proven mapping difference. The graph-audit error passed a bare
`@connection` without its projection; that caller now uses `db/db`, but
this final one-line correction has not been rerun.

No production retirement commit is made while the writer decision is open.
The expanded specification require command (including `seon.reconcile`)
passed with exit 0 after deletion in `tmp/bridge-step2-retirement-load.log`.
`git diff HEAD --check` is clean. The pending production/resource diff is
664 additions and 1,269 deletions against current HEAD; the step-1 owner
LOC comparison above also includes the earlier construction checkpoint.
The source/test tree is preserved for continuation, with every caller and
the raw helper deletion still in one pending slice. Only this decision note
and its issue are committed at the stop.

## Historical resumed verification after the usage-limit pause

The orchestrator restored the shelved edits and ruled handler option 1.
`seon.shell.jvm/run` reads the generation from its existing effect context's
environment, then passes it explicitly to `execute` and
`environment-overrides`. No request metadata carrier or handler protocol was
added. The explicit handler environment argument remains the effect owner's
Phase-3 item; `seon.shell.jvm/run` is the handler touched by this slice.

The restored `seon.db/write-entity-schemas` adaptation is retained: an
identity declaring `:seon.program/row-schema` selects only that write schema.
This is the orchestrator's authorized merge adaptation, grounded in
`2d0e9b17e` and the working edge's 2026-09-21 ~20:50 UTC ruling.

Fast recording is available again. Earlier live-recorder refusals below
are historical and do not describe these resumed requests:

| Request | Executed / unchanged | Assertions | Failures / errors | Observation |
|---|---:|---:|---:|---|
| `5f0f46e8ea2b` | 13 / 0 | 24 | 0 / 8 | Incomplete owned overlay omitted converted AI callers. |
| `c414a8b8f8ad` | 71 / 0 | 1,504 | 0 / 31 | Remaining threaded AI caller and synthetic syntax compilation corrected. |
| `6abad4087b6c` | 14 / 0 | 25 | 0 / 9 | `storable-properties-in` incorrectly required qualified schema keys; canonical `:inst` exposed it. Contract corrected to keyword. |
| `4dc80938f3ac` | 14 / 0 | 64 | 6 / 1 | Fixture built; old input baseline drift and transaction-codec return refusal remained. |

The last request also captured the old bridge from commit `22a1a0567`
against its current complete canonical population, independently of the
compiled implementation. `datahike_parity.edn` retains the original
1,012-attribute capture under `:seon.bridge.parity/initial-baseline`; the
recurring regression checks that original population as well as current
input identity and complete native declarations. The refreshed result is
not yet claimed green here.

Held callers awaiting release are `test/seon/error_test.clj` and
`test/seon/test_support_test.clj`. Foreign cluster guard/export hunks were
left untouched and subsequently landed. A transient hook refusal named
unmatched parentheses in foreign `src/seon/call_preparation.clj:512,1332`;
the snapshot run continued and the hook subsequently admitted commands.
No foreign session, default operation, worktree or cold gate was used.

The following checkpoint sections retain dated historical observations;
their pending decisions and inventories are superseded by the ruling above.

### Latest caller and proof boundary

The four-namespace request in `tmp/bridge-step2-parity-proof.log` completed
the bridge namespace but was terminated during the reference-grammar test.
A sample from that same JVM (89160) showed `build-projection` inside its
per-reference loop. That conversion error is corrected: the test now uses
retained roots, including the fixture's generator-bound generation. The
interrupted request has **no completed or durable tally**. Within its
completed bridge namespace, the original **1,012 native declarations**
matched exactly. Current population: **3,355 forms / 1,196 attributes**;
the seven differences from the refreshed capture are newly published
`:seon.program` attributes. Navigation measured **0 compiler calls,
0 registry constructions, 0 copied entries** across three complete passes.

The codec refusal's actual cause was a stale fixture projection after its
own declaration writes, not a storage mapping difference. The fixture now
uses `env/advance-projection!` after each checked transaction, as the
existing declaration fixture does. That correction awaits execution.

The broader 51-namespace attempt stopped **before tests** at
`test/seon/cluster/store_transact_test.clj:35`: HEAD `fd93f709a` declares
required `:seon.call-preparation/candidates` with nested-vector type in
`resources/seon/schemas/seon.call-preparation.edn`. The bridge correctly
refuses it. This is the recorded foreign issue
[unstorable candidates](../../../seon/issues/a-call-preparation-facet-requires-unstorable-candidates.md),
also named in the steward working edge's 2026-09-22 ~10:15 entry. Its
in-flight declaration repair was not included in this lane's overlay.
No recorder refusal is attributed to this attempt: execution never began.

The final construction census also converted `program/declaration-row`
and every source/test caller to an explicit projection. Schema-row
materialization now extends that supplied generation and selects only the
new row; it no longer rebuilds from ambient registered forms. The shell
generation regression and the converted direct handler fixtures are ready
but not yet executed. The settings renderer likewise uses its unit/database
projection and returns the existing typed missing-projection diagnostic.

The expanded specification require command, including `seon.reconcile`,
exited **0** in `tmp/bridge-step2-caller-load-stable.log`. An earlier load
raced this lane's edit of `agent.clj`; its reader failure is not attributed
to a foreign lane. No source edits overlapped the successful load.
The owned-file clj-kondo pass reports no error-level findings.

The public retirement remains uncommitted because foreign staged edits
hold `test/seon/error_test.clj` (raw navigation at lines 1304–1306 and its
require) and `test/seon/test_support_test.clj` (forms-only argument at line
272). Release was requested while other work continued. The raw file is
still present; no retirement commit leaves those callers behind. The
temporary capture test was removed. The frozen expected data retains both
the initial and refreshed independent old-bridge captures; current input
drift must still be recaptured after the foreign declaration admits.

No final deletion-budget, completed caller tally, cold/platform verdict or
live adoption is claimed. The coherent deletion/caller commit, final load,
current-population equality and landing status remain outstanding.

## Resumed after publication release

The owner released fn, cluster/source, cluster and turn after publication
`3ac00fb8e`, `fa1ff1dbe`, `6dae626e0`, `ef70b0dc5`. Their path status was
clean at the resumed census. The historical held-path observations below
describe the earlier checkpoint, not a current hold.

The steward-platform working edge's 11:05, 14:35 and 16:00 rulings require
optional unstorable members to remain in memory and be omitted from native
storage selection. Required unstorable members still refuse naming the key.
Admission and both old/compiled storage selections now apply this rule.
The canonical-population regression checks a producer carrying an arbitrary
object, canonical row construction, omission from both selections and the
required-member refusal. The existing whole-facet audit follows the same
optional-member rule.

The first resumed fast request `5e8c66c72579` at snapshot HEAD
`cade2f346f893299b9c03500d498ed298525a063` loaded and armed 1,472 contracts
(1,469 program-armable), then refused admission before executing tests.
Its external recording authority still executed the live store-holder's old check refusing
optional `:seon.error/offending` on
`:seon.test.runner/invalid-marker-reason-error`. Raw evidence is
`tmp/bridge-step2-optional.log`. Executed: zero; assertions: unavailable;
durable tally: unavailable. This is why the optional fix lands with the
additive construction checkpoint before the retirement commit. No public
helper is removed in this checkpoint. The new regression is not yet proven.
Foreign checkout callers explicitly excluded from that snapshot were
`src/seon/sci/admit.clj`, `test/seon/sci/admit_test.clj` and
`test/seon/env_test.clj`; their HEAD bytes were used.

## Handler custody decision after the first commit

The optional-member and additive construction checkpoint landed as
`22a1a0567723ddeeb26d476ab0b199b78757f815`. Production loads passed before
and after that commit (`tmp/bridge-step2-precommit-load.log` and
`tmp/bridge-step2-postcommit-load.log`). No public helper was retired in it.
The second fast request, `6173f1f457ce`, tested that HEAD and again refused
before executing tests; raw evidence is `tmp/bridge-step2-optional-landed.log`.
The recording seam in `src/seon/test/runner.clj` delegates persistent results
through `fresh-operator/live-root-value!`: this is a loaded live-JVM boundary,
not evidence that the new source check failed. Acquiring the commit there
belongs to the orchestrator. This lane did not operate default.

At refreshed HEAD `bd817b01d5534b085c836370e12edfef2c2748e8`, the shell
consumer exposes a new scope decision:

- `src/seon/shell/jvm.clj:86` obtains declaration forms inside
  `environment-overrides`; its handler at `:482` receives only request and
  effective configuration. Neither declares a supplied generation.
- `src/seon/effect.clj:663` owns the request database and context. Foreground
  dispatch at `:462` and background dispatch at `:811` call the same two-arg
  handler. The generation is available in this owner's existing context.
- `src/seon/effect.clj:451` explicitly assigns replacing the dynamic context
  with handler environment arguments to seon.env Phase 3.
- Effect admission already recognizes `:seon.schema/projection` on requests
  (`:190`), but neither dispatch branch currently attaches it to handler
  input. Carrying it there is a possible protocol change, not a missing
  bridge lookup. The step-2 specification forbids a new metadata carrier.

No effect protocol or shell implementation has been changed for this
decision. Exactly three options, with estimates incremental to the remaining
bridge conversion/proof work:

1. **Recommended: constrain step 2 to the existing effect context.** Acquire
   its carried generation once at the shell handler boundary and pass that
   explicitly through `execute` to `environment-overrides`. Guarantee:
   compiled navigation uses the request's generation, with no new registry,
   carrier or handler protocol. Cost: roughly half a lane-day including
   foreground/background and disagreeing-generation regressions. Give up:
   eliminating the existing dynamic-context read in this slice; approve
   that explicit scope exception and leave its retirement in Phase 3.
2. **Move explicit handler environment arguments into this slice.** Change
   the common foreground/background dispatcher, every handler contract and
   caller together. Guarantee: handlers receive custody as an ordinary
   argument. Cost: approximately 1–2 additional lane-days plus effect-owner
   coordination and capability integration proof. Give up: the bounded
   step-2 scope and Phase 3's current ownership of this transition.
3. **Authorize projection on dispatched request maps.** At the existing
   effect owner, attach the captured projection to both handler inputs using
   the existing qualified key; keep persistence/external encoding separate.
   Guarantee: explicit generation custody without changing handler arity.
   Cost: approximately half to one lane-day for contracts and both dispatch
   regressions. Give up: the no-new-metadata-carrier constraint for this
   boundary; request values now also carry execution custody.

The retirement remains uncommitted. The raw walker still exists and the
refreshed namespace-reference census has 22 executable paths, including the
walker itself and the newly discovered `test/seon/sci/eval_test.clj` caller.
Remaining callers and tests are not converted. The working tree changes
public bridge APIs and is a partial implementation, not a landing candidate.
The earlier 1,012-attribute equality is construction-checkpoint evidence;
it is not a green result for this later conversion or today's population.
No cold/platform/live proof, final parity or deletion-budget claim is made.

Owned uncommitted source/test paths at this decision (foreign changes are
excluded; no foreign file or session was modified):

```text
src/seon/agent.clj
src/seon/cluster.clj
src/seon/cluster/source.clj
src/seon/config.clj
src/seon/db.clj
src/seon/error.clj
src/seon/fn.clj
src/seon/issue.clj
src/seon/print.cljc
src/seon/reconcile.cljc
src/seon/render.clj
src/seon/render/transcript.clj
src/seon/render/walk.clj
src/seon/render/web.clj
src/seon/schema.clj
src/seon/schema/datahike.clj
src/seon/schema/internal.cljc
src/seon/turn.clj
test/seon/cluster/turn_test.clj
test/seon/schema/datahike_test.clj
test/seon/schema_audit_test.clj
test/seon/schema_test.clj
```

The expanded production require command in the launch specification, plus
`seon.reconcile`, exits zero on the current working tree; evidence is
`tmp/bridge-step2-decision-load.log`. That is a namespace load, not test or
runtime behavior proof. Before a retirement commit, remaining work includes
converting the convenience-API test callers, removing the stale private
`compiled-attribute` Var reference in the navigation-cost test, preserving
the old bridge's complete refusal grammar and tuple-child checks, updating
the independent parity baseline for explained population drift, and fixing
the canonical-fixture refusals recorded below. The optional-member test
currently proves canonical-row construction, not an executed publication.
The required-member refusal and the actual publication still need executed
canonical evidence. Review the newly converted boundary fallbacks before
landing; they do not authorize leaf generation acquisition.

These paths pass `git diff --check`. No worktree, cold gate, background JVM,
second concurrent lane JVM, default mutation or hook re-enable was used.

## Historical initial grounding and held-caller checkpoint

Baseline HEAD was `dc1efaf3c`; `fb4dfee98` is its parent. Read the step-2
launch specification, binding Malli-native bridge PRD, accepted dissolution
review, step-1 landing note, and the four schema owners end to end. Read the
five required skills and the context-generation roadmap entry. The supplied
AGENTS sections and lane rules govern this bounded assignment.

The refreshed executable namespace census still has 37 paths: the raw
walker, 22 production consumers and 14 test consumers. Required held
callers remain in `src/seon/cluster/source.clj:524` and
`src/seon/fn.clj:556,765,1632,1690`. They are not edited. The cluster's
activation requirements at `src/seon/cluster.clj:1258,1265` are outside
the stated population/refresh region; any edit still requires a fresh
path-status check. No helper can be retired until all callers convert
in one commit.

## Inherited live boundary

Read-only `bin/seon status` reports default PID 24777 alive. MCP
`runtime_status` at 2026-09-20 06:29:34 UTC returns a diagnostic instead
of health: `seon.problems/problems` refuses its return at
`[:seon.problems/error-signatures 0 :seon.error/at]`, because the signature
lacks required `:seon.error/at`. This is the existing status refusal class
tracked in [the runtime-status issue](../../../seon/issues/runtime-status-refuses-error-occurrence-count.md),
with a different offending member; no causal attribution is claimed.
Unlike the earlier opaque MCP projection failure, the current envelope
preserves the exception message. No default mutation, reload, adoption,
stop, restart or refork was performed.

A read-only JVM probe of a two-map conjunction returned
`{:type :and, :entries nil, :children [:map :map]}` in 1 ms. This confirms
the vendored seam: `m/entries` does not aggregate conjunctions. It is not
an armed fixture or a post-change live proof.

## Dependency and construction grounding

Read Malli's Schema/RefSchema protocols, registry implementation, child and
entry parsing, walk callbacks, reconstruction, conjunction, reference scopes,
and public navigation/cache APIs at the pinned source ranges in the spec.
`m/-set-children` reuses unchanged nodes; `m/parent` is the constructor,
not an inheritance relation. Explicit and named references have different
walk controls. Literal children are not necessarily schemas.

Existing construction boundaries: config composites precede complete
compilation (`schema/edn.clj:66`); `compose-projection-data` computes shape
indexes as pure data (`schema.clj:2170`); incomplete canonical-row input
retains missing-reference evidence (`schema.clj:3420`). The structural
registry is currently private in `schema.clj:1228`. Admission's existing
forms-only bridge requests must gain the complete construction registry.
The initial source read preceded production edits. Provisional compiled
composition and storage folds now live in the existing owners; the old
bridge remains active until construction/parity proof and caller conversion.

## Independent old-bridge capture

The first fast snapshot was taken at advancing HEAD
`3267839b0d6b665e784acc280745475b7e3220f9`. The four schema owners have no
diff from `dc1efaf3c` at that commit. Request `f04fd216d8bd` records program
digest `7ee71727d4ae594018748fc69a8f6c1c06a85f871cb94a846f17f428ee2bcb58`.
It armed 1,398 contracts (1,395 program-armable), then recorded 11 executed,
0 unchanged, 28 assertions, 1 failure and 3 errors. This is not green.
The full writer exception expanded to over 55,000 tool-output tokens;
subsequent execution output is redirected to project-local logs so the
evidence can be read selectively. This is unreadable diagnostic output,
not a reason to change any presentation bound in this slice.

The baseline capture itself completed on `support/with-database` and its
explicitly handed projection: 3,241 forms, 969 core attributes, 42 storable
property attributes, 1,011 selected attributes and 1,011 ordered native
declarations. Input SHA-256:
`bbe66c60af03d83b2d258c4237dae407ced14e579de0e5b58a4cd5ee1e6b8ab9`.
The mechanically written `test/seon/schema/datahike_parity.edn` retains
the full forms and outputs, not a sample or intersection.

Capture command:

```sh
bin/test-fast --paths test/seon/schema/datahike_test.clj \
  docs/prds/steward-platform/research/bridge-step2-walker-2026-09-21.md \
  -- seon.schema.datahike-test
```

The initial `canonical-population-native-parity` test derived `forms` from
`schema/handed-projection` inside `support/with-database`; called old
`schema.form/database-attributes`, filtered old
`schema.form/property-attributes` through `storable-attribute-in?`, then
called old `database-attributes-in` and `malli->datahike-schema-in`. It
wrote those complete values with unlimited EDN print length/depth and
`schema/sha-256` of UTF-8 `schema/canonical-data-string forms`. The capture
writer has been removed from the regression; it now reads the frozen data,
checks input identity, and compares both key-set differences and every
native map plus ordering. Only capture provenance was corrected afterward
to the actual snapshot HEAD; no expected declaration was regenerated.

Observed baseline refusal boundaries include `seon.program/declaration-row`
returning a row missing `:seon.ns/name` from `seon.turn:1270`, and the
old `registered-shape-round-trips-through-datahike` fixture submitting an
identity-less unowned row. These are observations before changing production
behavior, not assertions that another checkout edit caused them.

First provisional compiled run (`tmp/bridge-step2-compiled-parity.log`)
did not execute tests: new `entity-maps` used bare `:set` in its input
contract, and canonical arming refused `:malli.core/child-error`. Corrected
to a set of compiled schemas. No parity claim derives from that run.

## Construction iterations (not landing proof)

`tmp/bridge-step2-construction.log`: 66 tests, 5,514 assertions, one failure,
four errors. Config syntax inspection and scoped entity composition passed.
The provisional storage fold overflowed while following recursive refs;
the next version tracks registry scope plus ref identity, following Malli's
own `-identify-ref-schema` distinction (`core.cljc:1943`). This snapshot
preceded that correction. Its result recording refused, so these are printed
execution counts, not durable recorded results.

`tmp/bridge-step2-components.log`: 67 tests, 5,563 assertions, seven failures,
three errors. Component preparation now uses structural Malli nodes and
reconstruction, including locally registered recursive declarations;
the new local-recursion/literal-payload/idempotence regression passed.
The compiled parity fold completed: every one of the original 1,011 native
declarations matched. The sole map difference was newly added
`:seon.sci.eval/row-member`; HEAD `2a59e5e11` added its declaration and error
entity, making 3,243 forms and 1,012 selected attributes. Input digest changed
to `9064a6781019325a4d697b05e03f6c30b8564875313f2074e7087b719e742e8a`.
This is input drift, not a passed whole-population assertion. A subsequent
one-time capture uses the still-active old bridge to obtain the complete
new expected output independently; it does not use the compiled fold.

Both runs failed recording through `seon.blob/with-publication!`, which
reported undeclared `#{:seon.db.write/validation-refusal
:seon.test/execution-error}`. This is the existing class in
[test refusal observations](../../../seon/issues/test-refusal-observations-overflow-in-projection-acquisition.md).
No recorder, blob, error, or held publication path was changed to bypass it.

Shape indexes are being moved to materialization from retained compiled
roots. Pure composition keeps rows and dependency facts and carries no
Schema objects; it no longer reinterprets forms to discover optional-only
entity entries. This is the specification's materialization option, not a
new persisted metadata carrier. The serialization/materialization proof is
pending in `tmp/bridge-step2-shapes.log`.

That run completed 68 tests with 5,568 assertions, seven failures and three
errors; result recording remained unavailable. The pure-data composition
and optional-only index materialization regression passed. Its old-bridge
recapture is request `a5d2fbb2eca1`, snapshot HEAD
`2a59e5e11788fd249e675949115dc189d95fe388`, program digest
`b097548ee1583ab170f2becd9573907cba951fa161b5949a529ef1ba42c2d09e`.
The frozen parity file now contains that independently captured population.
Compared with the first capture, the exact input change is two new
`:seon.sci.eval` declarations (`row-member`, `row-acquisition-error`) plus
the new facet in `:seon.db/error-result`; no declaration was removed.
The old bridge added only the one native string attribute `row-member`.
No expected value was produced by the compiled fold. The temporary capture
writer was removed again before the next run.

The final construction iteration includes scoped recursive entity comparison
and the compiled error-inheritance inspection. It remains a construction
checkpoint, not the complete step-2 acceptance suite.

## Held construction boundary

The explicit launch hold still governs. An intermediate `git status --short`
reported clean bytes for `src/seon/fn.clj` and `src/seon/cluster/source.clj`;
the final census now reports both modified by their owner. No release was
received, and neither path was edited here.

`src/seon/fn.clj:2827` (`desired-rows`) selects changed declarations and at
`:2836` passes only those forms to `schema/canonical-schema-rows`.
`src/seon/fn.clj:1243` acquires forms, not the retained construction registry;
`:3233` also supplies those forms to `program/shapes-in`. The schema-row
helper currently catches missing references and falls back to a forms-only
map (`schema.clj:3493`). Removing that fallback requires converting this
held caller to carry the complete construction generation while preserving
the selected rows and their external-reference evidence. Syntax placeholders
cannot establish storable property declarations. No replacement leaf
population acquisition or second registry was introduced to bypass the hold.

The specification orders that partial-construction proof before broad caller
conversion. Thus that conversion and the helper retirement remain dependent
on this held path. `src/seon/cluster/source.clj:524` independently still uses
the retiring property helper in result recording. The remaining direct
namespace references are deliberately intact; there is no retirement commit
and no claim of completed step 2. The held callers have not been edited.

## Verified construction checkpoint

`tmp/bridge-step2-construction-final.log` executed 68 tests / 5,569 assertions,
one failure / three errors. The complete parity regression passed over 3,243
forms and 1,012 attributes with **zero key differences and zero per-key native
map differences**, including ordering, core selection and storable-property
selection. Config syntax/no-load, local recursive component widening,
idempotence/literal preservation, scoped recursive entity comparison,
pure-data materialization and retained-generation regressions passed.
The remaining failure was the old assertion that acquisition must read
resource files; it is replaced by direct compiled-navigation operation
counts in the last bridge-only iteration. The three baseline fixture errors
remain tracked in [the fixture class](../../../seon/issues/fixtures-that-ignore-a-refused-transaction-read-absence-as-behaviour.md).
Recording still refused through `seon.blob/with-publication!`; executed counts
are printed evidence, durable executed/unchanged tally is unavailable.

The existing generation test measured 4,715 named providers with maximum
one invocation, 4,863 sealed entries, and warmed acquisition at zero compiles
and zero copied registry entries. Those counts include synthetic generation
subjects; the fixture itself carried 3,243 schemas and 1,467 function contracts.

Frozen parity-file SHA-256:
`70341704c2a651205fd61473934f6777138d54c5b472fbed30b92e06432d71d3`.
Final construction log SHA-256:
`5efca10b7501de431c6ac7ae161676b52fd5256c4bcb1c1c041bf2065cd645ec`.

The refreshed raw-namespace census is **36 executable paths**. Only config
preparation and selected construction navigation have converted; the old
production bridge is still active and `schema/form.cljc` still exists.
This checkpoint does not claim the retirement/deletion budget, complete
caller conversion, the full requested namespace selection, cold/platform
proof or live post-adoption proof. No commit, worktree, default mutation,
cold gate, nested gate, second lane JVM or hook re-enable was performed.

Owned changed paths at this checkpoint:

- `src/seon/schema.clj`
- `src/seon/schema/internal.cljc`
- `src/seon/schema/datahike.clj`
- `src/seon/schema/edn.clj`
- `test/seon/schema_test.clj`
- `test/seon/schema/datahike_test.clj`
- `test/seon/schema/datahike_parity.edn`
- `test/seon/schema/edn_test.clj`
- `docs/seon/issues/runtime-status-refuses-error-occurrence-count.md`
- `docs/seon/issues/fixtures-that-ignore-a-refused-transaction-read-absence-as-behaviour.md`
- this note.

`git diff --check` passes on these owned tracked paths. The Markdown hook
reports a foreign stale Datahike pin in
`docs/prds/steward-platform/plan/wave-3a-task-family-spec-2026-09-21.md`;
the design lane's document was not edited.

The final bridge-only request, `tmp/bridge-step2-navigation-counts.log`,
waited at least 960 seconds for the two shared JVM slots. At the explicit
held-caller stop, only this lane's queued launcher PID 83852 was sent TERM;
its trap removed `tmp/test-runs/run.JMkS6t`, and both launcher 83852 and
child 84230 were verified absent. Exit 143. **No JVM or tests executed for
this last request.** The newly substituted
`compiled-storage-navigation-reuses-retained-roots` cost regression is
therefore unverified; no compiled-storage zero-cost claim is made. Other
lanes' processes were untouched. The temporary recapture file was removed
after its data and provenance were preserved in the frozen parity file.

Current, uncommitted source diff: 345 added / 77 deleted physical lines across
the four schema owners. Tests: 176 added / 36 deleted lines, excluding the
mechanically captured parity data. These are checkpoint counts, not the
retirement budget: the 216-line raw walker remains. No public helper has
been deleted, and no partial caller/retirement commit was made.

Pending measurement command (same source inputs, bridge namespace only):

```sh
bin/test-fast --paths \
  src/seon/schema.clj src/seon/schema/internal.cljc \
  src/seon/schema/datahike.clj src/seon/schema/edn.clj \
  test/seon/schema_test.clj test/seon/schema/datahike_test.clj \
  test/seon/schema/datahike_parity.edn test/seon/schema/edn_test.clj \
  docs/prds/steward-platform/research/bridge-step2-walker-2026-09-21.md \
  docs/seon/issues/runtime-status-refuses-error-occurrence-count.md \
  docs/seon/issues/fixtures-that-ignore-a-refused-transaction-read-absence-as-behaviour.md \
  -- seon.schema.datahike-test
```
