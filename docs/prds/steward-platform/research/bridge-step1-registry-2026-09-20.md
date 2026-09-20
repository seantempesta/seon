---
type: research
status: implementation landed; cold and live proof owed
created: 2026-09-20
tags: [schema, malli, bridge, projection]
---

# Step 1: registry value, compile once, seal, carry

This commit lands the public arity retirement, every inventoried caller
conversion, and the compiled registry carried by its projection as one
coherent slice. No step-2 change or storage/validation guarantee change.
No worktree, cold gate, default adoption, restart, or stop occurred.

The owner accepted `667f6519e` (complete recorder refusal) and `d076fff47`
(evidence checkpoint), then extended this slice to the existing pass-through
facet declarations in `src/seon/error.clj`, `src/seon/sci/admit.clj`, and
`src/seon/sci/kernel.clj`. The final ruling explicitly authorizes landing
with remaining foreign reds classified below. The SCI walk allocation is
an open measurement, not a step-1 landing condition. The separate SCI
row-acquisition facet remains 1a's work; this slice does not implement it.

## Final fast evidence and boundaries

The combined HEAD-plus-owned-path snapshot at
`8b996e67e2de089df840538cd6f97aac1c8fa450`, request `c44b612d89df`,
ran the six section-7 namespaces plus `seon.error-test`,
`seon.sci.admit-test`, and `seon.sci.kernel-arm-carriage-test`:
**313 tests / 7,337 assertions / 4 failures / 24 errors**.
The canonical runner armed 1,420 loaded contracts (1,394 program-armable).
This is an execution tally, not a durable verdict: completion recording
refused at the blob publication boundary below.

| Namespace / boundary | Observed result and owner |
|---|---|
| `seon.schema-test`, `seon.schema.edn-test` | Green. Registry providers run at most once, warm acquisition makes zero compiler calls, and supplied-registry admission makes zero copies. The ambiguous-render fixture now respects the collection schema's declared minimum. |
| `seon.instrument-test` | Green. The facet census includes all four existing pass-through boundaries: error refusal/latest-fact, SCI semantic-value, and kernel failure-value. Their exact installed facet declarations now agree. |
| `seon.error-test`, `seon.sci.kernel-arm-carriage-test` | Green. |
| `seon.config-test` | 1 E: upstream refusal reaches `src/seon/db.clj:1115` (`schema-database`, called at `:1329`) because the DB owner still recognizes the retired kind at `:194`. Config's own missing-effective and missing-cap producers now carry complete base/facet data and pass. |
| `seon.db-test` | 3 F / 22 E, error-family 1a boundary. The canonical upstream error fixture at `test/seon/db_test.clj:604` now uses `db/projection-fallback`; 18 assertions expose the kind-only consumer at `src/seon/db.clj:194`. Seven additional errors reach kind-only `error-value` at `:164` through identity/history/write wrappers (`:321`, `:390`, temporal views and transact). No DB implementation was edited. |
| `seon.fn-test` | 1 E: `src/seon/fn.clj:1397` / return contract `:1403`, `declared-reference-edges`, does not declare the `:seon.schema/validation-refusal` facet (1a conversion family). The prior `fn.clj:971` missing-`:at` error disappeared after publication `8edfae1b7`; it is not a current red. |
| `seon.sci.admit-test` | 1 F was a stale `:seon.error/kind` assertion at `test/seon/sci/admit_test.clj:513`; converted to the armed input check and function identity, retaining the offending-key assertion. Follow-up evidence below. |
| Recording | `src/seon/blob.clj:271`, `with-publication!`, declares zero error facets and returns `:seon.db.write/validation-refusal` plus `:seon.test/execution-error`; the armed wrapper refuses. This is the publication/recorder pass-through boundary, outside the bounded released owners. Its nested returned-error projection reports 16,430 bytes against unchanged 16,384. No bound was raised; [the existing issue](../../../seon/issues/test-refusal-observations-overflow-in-projection-acquisition.md) records the same propagation boundary. |

The final changed-input follow-up selects only `seon.config-test`,
`seon.fn-test`, and `seon.sci.admit-test`. It covers the file-based config
apply path's single projection acquisition, the private effective reader's
explicit contract, the honest upstream fn fixture, and the retired-kind
expectation correction. It does not rerun the other unchanged namespaces. This follow-up durably
recorded **101 executed / 0 unchanged / 712 assertions / 0 failures /
2 errors**, request `dc69a5a7013b`, program digest
`c8c970e3e9d17effb0cefd1d1754eaaa612eee43624ac5f8cf7d9c8d7ae1662d`.
The two errors are exactly the DB schema-database and fn declared-reference-edges
boundaries above. SCI admission is green, the file-apply regression observes
one declaration-projection acquisition, and the fresh-config child completes
in 12.9 seconds under the unchanged 30-second backstop. Recording succeeded
on this request; the earlier combined request's refusal remains its actual
outcome, not an ongoing claim about the converging publication owner.

Publication `8edfae1b7` is included automatically through HEAD. Snapshot
provenance names foreign `test/seon/test_runner_test.clj` as tested at HEAD
bytes. Later foreign edits to `script/seon/fresh_operator.clj` briefly
refused the edit hook for syntax at lines 2112–2140 and 2801–2820; the running
snapshot continued with HEAD bytes. Foreign `src/seon/operator/state.clj`
and `test/seon/dev/publication_test.clj` were also left untouched.
No claim here attributes snapshot failures to excluded checkout edits.

The recorder producer fix in `667f6519e` preserves all exception data,
including schema/member facets, drops the retired kind stamp, and supplies
absent base observations. Subsequent requests admitted; the earlier boolean
facet admission failure did not recur. Its offending declaration remains
unknown, so no speculative schema/resource change was made.

Fresh config resolves predicate Vars through the existing `runtime-predicate`
owner before full registry realization; supplied bindings win and retained
roots stay unchanged. Its fresh child passed the unchanged 30-second bound.
The earlier timeout remains an open observation in
[the predicate issue](../../../seon/issues/scratch-boot-refuses-the-shell-stdin-predicate.md).
Eleven prompt fixture custody failures were corrected by taking `db/db`
instead of raw connection dereference; a different prompt return-contract
failure remains in the earlier caller evidence.

The SCI walk measured **4,228,637,320 bytes** against its unchanged 1 GiB
assertion, with a pull-plan reuse failure; the selected-render test hit an
array-length OutOfMemoryError while printing a nested map. Attribution is
unproved. Same-JVM JFR samples include instrumentation, DB identity reads,
projection-from-rows and fingerprint work; their interval also includes
later direct/web calls, so sample weights are not the SCI counter's bytes.
The owner ruled this a recorded measurement, not a step-1 landing condition.
[The allocation issue](../../../seon/issues/guarded-public-walk-exceeds-allocation-bound.md)
retains the method, exact numbers and unresolved attribution. No bound changed.

## Failure inventory from the full caller run

The earlier full caller snapshot at HEAD
`c79167f9e89782143772c9579ae55a20b2bf20c6` completed **849 tests / 10,059
assertions / 164 failures / 180 errors**. Completion recording refused a
symbol-valued failure context where its contract expected a string.

[The complete 344-event inventory](bridge-step1-failures-2026-09-20.tsv)
records event type, test, raw log line, observed boundary path and evidence.
[The reproducer](bridge-step1-evidence-2026-09-20.py) derives it from the log;
it also summarizes the dated JFR allocation samples. Classification:

| Class | Events |
|---|---:|
| Step-1 measurement/construction corrected | 3 |
| Step-1 caller custody corrected | 11 |
| Stale retired-field expectations | 72 |
| Stale fixture declarations (symbols or retired kind attributes) | 8 |
| Database/error-conversion boundary, including 1a | 43 |
| Held fn producer boundary (fixture base subsequently corrected) | 2 |
| Error facet census | 3 |
| Pre-implementation ambiguous-error fixture failure | 1 |
| Unresolved SCI performance/render boundary | 2 |
| Other observed caller/producer boundaries, with paths; causal attribution unproved | 199 |

The final category is deliberately an observation rather than an invented
root-cause claim. It includes old program fixtures, maintenance result
contracts, publication/boot assertions, missing error base observations,
and turn settlement contracts. No broad error-kind sweep or storage-policy
repair was smuggled into the mechanical caller conversion. Earlier
root-pull test expectations also name distance/caps cache keys whereas the
unchanged owner keys by schema identity and selector
(`src/seon/render/walk.clj:376`). The inventory retains every failure,
including those whose cause remains unproved.

## Implementation and generation evidence

`src/seon/schema.clj:419` owns one fixed-input lazy construction registry,
serial canonical realization and final fast table. Build, materialize,
declaration and incremental paths converge there. Changed declarations and
their dependent schema/function closure recompile; unaffected roots retain
identity. Candidate/base overlays never rebind compiled roots. Predicate
Vars preserve live reload behavior; property-local registries remain local.

`src/seon/schema/internal.cljc` admits against the supplied complete registry
without copying all forms for every declaration. Projection validator and
explainer acquisition use `mr/schema` and Malli's schema-owned caches.
The existing holder retains only additional derived products, not duplicate
plain validators/explainers. No new metadata or public registry family.

`candidate-validator` and `candidate-explainer` are retired;
the sole fn-test caller uses its fixture projection. Both value APIs accept
only `[projection schema-key value]`. Production callers use existing
DB/request custody or construct once per operation. Maintenance's result
projection and turn disposition receive the generation explicitly, with
all discovered callers converted in this same commit. Render's
existing default profile is delayed to break the config/generator/render
load cycle exposed by complete realization.

The canonical armed regressions pass for simultaneous disagreeing
generations, changed leaf plus dependent map/function, missing-reference
refusal, local-ref shadowing, open conjunction requiredness, component
acceptance, native explanation information, and fresh/derived holder
isolation. Their implementations live in `test/seon/schema_test.clj`;
the SCI allocation measurement lives in `test/seon/sci/eval_test.clj`.

Converted explanation-path consumers:

- `src/seon/instrument.clj:303`: described-union selection no longer changes
  Malli's schema path.
- `src/seon/instrument.clj:332`: argument naming uses the first value-path
  position and the schema's `:catn` child name.
- `src/seon/instrument.clj:710`: structured schema/value locations preserve
  the respective native paths in the existing ordered observation encoding.

The owner's ruling accepted after `55076261e` governs: both `:in` and `:path`
are preserved, offending-value lookup uses `:in`, and regressions assert the
value, violated leaf schema and value path. No translation layer, derived
explanation schema, or literal native schema-path-vector expectation.

## Compiler and allocation measurements

Before: canonical snapshot `0e84ccb540467f0d921140fc5666f77db6d37e59`,
`run.UquFTL`, 3,228 forms / 1,418 contracts, 1,390 armed loaded contracts /
1,365 program-armable. The baseline contains the measurement test only,
no implementation changes; it was interrupted after the probe (exit 143).

| Measurement | Before | Latest after |
|---|---:|---:|
| Ten validator/explainer acquisitions, non-Schema `m/schema` calls, including first use | 48 | 5 (3 named lookups) |
| Ten further acquisitions after Malli cache population | Not separately measured | 0 compiler calls |
| Full registry copies during acquisitions | 0 | 0 |
| Ten supplied-registry admissions, copied entries | 32,280 (source-derived) | 0 (measured) |
| Whole before schema-admission loop, copied entries | 10,419,984 (source-derived N²) | No per-admission copies |
| Full generation providers | Not measured | 4,694; maximum 1 call each |
| Final sealed table | Not measured | 4,842 entries, one deliberate copy |
| SCI schema declaration bytes, prior implementation iteration vs latest | 67,456,856 | 44,510,808 |
| SCI function declaration bytes, latest | — | 9,289,792 |

The source-derived counts follow baseline `internal.cljc:364–366`
`fast-registry (assoc schemas k v)`, called once per form by baseline
`schema.clj:1901–1906`; Malli `registry.cljc:17–22` copies into its HashMap.
They are copied-entry counts, **not heap-byte measurements**. The SCI
67,456,856-byte figure is an earlier implementation iteration, not the
pre-change baseline. Both latest SCI declarations pass the unchanged
67,108,864-byte limit.

Latest fixture population: 3,241 schemas / 1,448 contracts; the synthetic
replacement-generation probe adds four schemas and one contract, producing
4,694 providers. Earlier after runs measured **4,667 providers, max one**,
then 4,676/max one and the accepted **4,678/max one** as HEAD's population
grew. Those remain dated observations, not constants asserted by tests.
Warm acquisition and supplied-registry admission both copy zero entries.

## Proof and proof still owed

The prescribed eight-namespace load exited **0 immediately before this
coherent implementation commit**. The same command is repeated after commit
as the final HEAD load check. The recorder-only and prior evidence commits also passed
their prescribed loads; none substitutes for this implementation's proof.

Cold six-namespace and affected-caller gates, platform proof, and the
post-change live proof remain owed to the orchestrator. No post-change hot
reload, development adoption, scratch fork, or browser paint is claimed.
Initial read-only default observations below are pre-change only. This lane
must not adopt or restart default. The combined fast run is not a durable verdict; the final focused request
is recorded but is not green. Neither substitutes for the cold gate.

## Authority and inherited state

Read the binding [Malli bridge PRD](../plan/malli-native-bridge-prd-2026-09-20.md)
and [accepted review](bridge-dissolution-review-2026-09-20.md) end to end;
AGENTS sections 1–5 and lane rules 11–16; all five requested skills
(data-oriented-clojure, data-modeling, datahike, repl, clojure-testing);
and [step 3's Candidate and holder semantics](step3-carried-projection-2026-09-20.md#candidate-and-holder-semantics).
Read the context-generation roadmap entry and the steward-platform roadmap.

Branch: `steward-platform`. Observed HEAD:
`718a18e8ff74a35d5293fbfdacdcaadfc9899ced`. Shared-tree foreign edits were
preserved. `default` pid 24777 was alive; the hook's `:current-source` was
already disabled. No restart, adoption, stop, worktree, or new cluster.

`runtime_status` returned `:seon.dev.mcp/projection-failed` instead of health
evidence. Recorded the observation on the existing
[MCP issue](../../../seon/issues/mcp-exception-projection-is-opaque-after-the-kind-removal.md).
Read-only JVM evaluation returning string data succeeded. The running
projection contained 3,228 forms, and `mr/schema` for `:seon.agent/id` did
not return a Malli Schema. This is live pre-change evidence, not a canonical
fixture measurement or adoption-freshness proof.

## Dependency ledger (read during design)

| Seam | Vendored source | Used guarantee |
|---|---|---|
| Provider and fast registry | `reference-code/malli/src/malli/registry.cljc:17`, `:81`, `:97` | Fixed inputs, serial realization, final table; lookup through `mr/schema`. |
| Schema-owned caches | `reference-code/malli/src/malli/core.cljc:345`, `:2626`, `:2642` | Retained schemas own validator/explainer products. |
| Captured reference scope | `reference-code/malli/src/malli/core.cljc:1943`, `:1968` | A replacement recompiles dependent roots; overlays cannot rebind them. |
| Named pointer | `reference-code/malli/src/malli/core.cljc:2550` | A keyword lookup can add a pointer; native paths are the ruled surface. |
| Recursive dereference | `reference-code/malli/src/malli/core.cljc:2834` | Reconstructs walked nodes; removed from ordinary value use. |

## Complete changed paths

Includes the recorder commit and all code, regression and evidence
paths; excludes foreign checkout edits. This is a dated inventory.
The initial accepted `55076261e` also changed
`docs/seon/issues/mcp-exception-projection-is-opaque-after-the-kind-removal.md`;
that already-landed observation is unchanged in this final overlay.

```text
src/seon/config.clj
src/seon/instrument.clj
src/seon/maintenance.clj
src/seon/render.clj
src/seon/schedule.clj
src/seon/schema.clj
src/seon/schema/internal.cljc
test/my/background_test.clj
test/my/edit_test.clj
test/my/fs_test.clj
test/my/message_test.clj
test/my/note_test.clj
test/my/plan_test.clj
test/my/turn_test.clj
test/my/web_test.clj
test/seon/ai_test.clj
test/seon/blob_test.clj
test/seon/bootstrap_test.clj
test/seon/config_test.clj
test/seon/db_test.clj
test/seon/error_test.clj
test/seon/fn_test.clj
test/seon/instrument_test.clj
test/seon/maintenance_schema_test.clj
test/seon/maintenance_test.clj
test/seon/problems_test.clj
test/seon/program_test.clj
test/seon/render/data_test.clj
test/seon/render/hiccup_test.clj
test/seon/render/root_pull_test.clj
test/seon/render/web_test.clj
test/seon/schema/edn_test.clj
test/seon/schema/program_test.clj
test/seon/schema_test.clj
test/seon/sci/documentation_test.clj
test/seon/sci/eval_test.clj
docs/prds/steward-platform/research/bridge-step1-registry-2026-09-20.md
src/seon/cluster.clj
src/seon/cluster/prompt.clj
src/seon/turn.clj
test/seon/cluster/armed_test.clj
test/seon/cluster/boot_test.clj
test/seon/cluster/cohost_boot_test.clj
test/seon/cluster/message_test.clj
test/seon/cluster/problem_routing_test.clj
test/seon/cluster/store_test.clj
test/seon/turn_test.clj
test/seon/turn_work_test.clj
test/seon/test/accretion_test.clj
test/seon/turn_loop_test.clj
docs/seon/issues/default-web-request-times-out-during-partial-adoption.md
docs/seon/issues/fast-admission-refuses-on-live-error-facet-schema.md
src/seon/test/runner.clj
test/seon/cluster/prompt_test.clj
docs/seon/issues/scratch-boot-refuses-the-shell-stdin-predicate.md
docs/prds/steward-platform/research/bridge-step1-evidence-2026-09-20.py
docs/prds/steward-platform/research/bridge-step1-failures-2026-09-20.tsv
docs/seon/issues/guarded-public-walk-exceeds-allocation-bound.md
src/seon/error.clj
src/seon/sci/admit.clj
src/seon/sci/kernel.clj
test/seon/sci/admit_test.clj
```

## Exact commands owed to the orchestrator

```bash
bin/test --paths \
  src/seon/config.clj \
  src/seon/instrument.clj \
  src/seon/maintenance.clj \
  src/seon/render.clj \
  src/seon/schedule.clj \
  src/seon/schema.clj \
  src/seon/schema/internal.cljc \
  test/my/background_test.clj \
  test/my/edit_test.clj \
  test/my/fs_test.clj \
  test/my/message_test.clj \
  test/my/note_test.clj \
  test/my/plan_test.clj \
  test/my/turn_test.clj \
  test/my/web_test.clj \
  test/seon/ai_test.clj \
  test/seon/blob_test.clj \
  test/seon/bootstrap_test.clj \
  test/seon/config_test.clj \
  test/seon/db_test.clj \
  test/seon/error_test.clj \
  test/seon/fn_test.clj \
  test/seon/instrument_test.clj \
  test/seon/maintenance_schema_test.clj \
  test/seon/maintenance_test.clj \
  test/seon/problems_test.clj \
  test/seon/program_test.clj \
  test/seon/render/data_test.clj \
  test/seon/render/hiccup_test.clj \
  test/seon/render/root_pull_test.clj \
  test/seon/render/web_test.clj \
  test/seon/schema/edn_test.clj \
  test/seon/schema/program_test.clj \
  test/seon/schema_test.clj \
  test/seon/sci/documentation_test.clj \
  test/seon/sci/eval_test.clj \
  docs/prds/steward-platform/research/bridge-step1-registry-2026-09-20.md \
  src/seon/cluster.clj \
  src/seon/cluster/prompt.clj \
  src/seon/turn.clj \
  test/seon/cluster/armed_test.clj \
  test/seon/cluster/boot_test.clj \
  test/seon/cluster/cohost_boot_test.clj \
  test/seon/cluster/message_test.clj \
  test/seon/cluster/problem_routing_test.clj \
  test/seon/cluster/store_test.clj \
  test/seon/turn_test.clj \
  test/seon/turn_work_test.clj \
  test/seon/test/accretion_test.clj \
  test/seon/turn_loop_test.clj \
  docs/seon/issues/default-web-request-times-out-during-partial-adoption.md \
  docs/seon/issues/fast-admission-refuses-on-live-error-facet-schema.md \
  src/seon/test/runner.clj \
  test/seon/cluster/prompt_test.clj \
  docs/seon/issues/scratch-boot-refuses-the-shell-stdin-predicate.md \
  docs/prds/steward-platform/research/bridge-step1-evidence-2026-09-20.py \
  docs/prds/steward-platform/research/bridge-step1-failures-2026-09-20.tsv \
  docs/seon/issues/guarded-public-walk-exceeds-allocation-bound.md \
  src/seon/error.clj \
  src/seon/sci/admit.clj \
  src/seon/sci/kernel.clj \
  test/seon/sci/admit_test.clj \
  -- seon.schema-test seon.schema.edn-test seon.config-test \
  seon.instrument-test seon.db-test seon.fn-test \
  my.background-test \
  my.edit-test \
  my.fs-test \
  my.message-test \
  my.note-test \
  my.plan-test \
  my.turn-test \
  my.web-test \
  seon.ai-test \
  seon.blob-test \
  seon.bootstrap-test \
  seon.error-test \
  seon.maintenance-schema-test \
  seon.maintenance-test \
  seon.problems-test \
  seon.program-test \
  seon.render.data-test \
  seon.render.hiccup-test \
  seon.render.root-pull-test \
  seon.render.web-test \
  seon.schema.program-test \
  seon.sci.documentation-test \
  seon.sci.eval-test \
  seon.cluster.armed-test \
  seon.cluster.boot-test \
  seon.cluster.cohost-boot-test \
  seon.cluster.message-test \
  seon.cluster.problem-routing-test \
  seon.cluster.store-test \
  seon.turn-test \
  seon.turn-work-test \
  seon.test.accretion-test \
  seon.turn-loop-test \
  seon.schedule-test \
  seon.cluster.prompt-test \
  seon.sci.admit-test \
  seon.sci.kernel-arm-carriage-test
bin/test --platform
clojure -M -e "(require 'seon.schema 'seon.schema.internal 'seon.config 'seon.maintenance 'seon.cluster 'seon.turn 'seon.render 'seon.cluster.prompt)"
```

Cold commands are recorded, not executed by this lane. The equivalent fast
invocations use `bin/test-fast --paths` with the complete owned list above.
The latest combined run selected the six required namespaces plus
`seon.error-test`, `seon.sci.admit-test`, and
`seon.sci.kernel-arm-carriage-test`; the changed-input follow-up selected
`seon.config-test seon.fn-test seon.sci.admit-test` only.

## Raw evidence identities

| Log | SHA-256 |
|---|---|
| `tmp/bridge-step1-before.log` | `ab389e8eac0164e7bbe5d109c432c61e63fe180507b3dda091d2d37b6dffd808` |
| `tmp/bridge-step1-after-corrected.log` | `ea8251e7b12627e62dd68d5e320046d4a2989edda0e1b0baaf34b0471689e4b5` |
| `tmp/bridge-step1-recorder-fixed-fast.log` | `f13af297484a68762f37e53d70017b2bb46a6c7beccc2d53e4c7c88cacb279e1` |
| `tmp/bridge-step1-predicate-custody-fast.log` | `facf564193c143d8ba7a9fc67fd2e0621ebee8860f4bf27d6e83370ba979b3a1` |
| `tmp/bridge-step1-config-arity-fast.log` | `54f01c07937ce9f710d34f556dcb671c182029d9e5d2e5f9f785e936d043f759` |

JFR recording SHA-256: `6b70d0b00749108f4857fdb76c85fda5891d9f4c7a1abea04a959b719db3ebb1`.

Final execution log identities:

| Log | SHA-256 |
|---|---|
| `tmp/bridge-step1-landing-fast.log` | `6218cc88eea82321ddb3bcdcd570f470c8e36fe89854bcd9948a2a110c4b6181` |
| `tmp/bridge-step1-final-followup-fast.log` | `ab6a63d5a6dad026e867cb0d64ec7daac008e3b4017eba7c1bc93bc46d2c7600` |
