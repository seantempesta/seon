---
type: research
status: checkpoint; required green proof blocked
created: 2026-09-20
tags: [schema, malli, bridge, projection]
---

# Step 1: registry value, compile once, seal, carry

Step 1 is implemented in working bytes but **not landed**. The public arity
retirement and all caller conversions remain one uncommitted slice. The
required six namespaces are not green, so the orchestrator's landing
condition is not satisfied. No step-2 change, storage guarantee change,
worktree, cold gate, default adoption, restart, or stop occurred.

## Latest checkpoint — 2026-09-20

**Committed `667f6519e`, recorder producer only:** the live recording form's
catch in `src/seon/test/runner.clj:3190` preserves exception data, including
schema/member facets, drops the retired kind stamp, and supplies absent
base observations. Existing base observations win. The error-conversion
PRD was read end to end. The prescribed eight namespaces plus the recorder
loaded before and after this commit. The earlier boolean-facet admission
refusal did not recur: subsequent requests admitted. Its offending
declaration remains unknown; no speculative schema/resource repair was made.

The full six-namespace run plus SCI evaluation and prompt callers completed
**337 tests / 7,345 assertions / 15 failures / 36 errors** at snapshot HEAD
`fe1aaad68bdfa3e491e56439bbab2df9bbe5bad4`, request `eefc617a8263`.
Completion recording hit the existing 30,000 ms prepl silence backstop.
This is a raw execution tally, not a durable verdict. The held authority
paths are `src/seon/cluster/source.clj` and `script/seon/fresh_operator.clj`.
See [the existing persistence issue](../../../seon/issues/test-results-persistence-can-time-out-during-development-adoption.md).

Only config was rerun after correcting its remaining arity expectation;
other namespace inputs had not changed. That request **recorded 23 executed,
0 unchanged, 179 assertions, 0 failures, 3 errors**, run `77212b9df870`.
The arity/input expectations and fresh-process construction regression pass.
No bound was raised. The three remaining errors are the upstream-error and
config-refusal boundaries below, not those corrected expectations.

Corrections verified by these runs:

- Fresh config fully realizes the registry after acquiring predicate Vars
  through the existing `runtime-predicate` owner. Supplied bindings win;
  retained roots remain unchanged. This fixes the new construction failure
  naming `seon.shell/stdin?`; `compilable-form` retains its inspection policy.
  The fresh child completes under the unchanged 30-second bound. The earlier
  timeout and scratch live boot remain open observations in
  [the predicate issue](../../../seon/issues/scratch-boot-refuses-the-shell-stdin-predicate.md).
- Eleven prompt fixture failures were caused by this lane's missing custody:
  raw `@connection` snapshots reached the newly explicit disposition API.
  `test/seon/cluster/prompt_test.clj` now uses `db/db`; all eleven nil-projection
  errors disappear. One different prompt return-contract error remains.
- The injected fn-test diagnostic now supplies its base observations. This
  exposes the held `declared-reference-edges` return contract's missing facet;
  the separate production missing-`:at` diagnostic remains at HEAD `fn.clj:971`.
- Eight config assertions used retired kind/contract fields. Input refusals
  now assert check/function identity; the missing positional argument asserts
  actual and declared arity. The latter correction passes the recorded run.
- Incremental registry construction avoids rebuilding retained maps only to
  remove their members again. Schema declaration allocation now passes the
  existing 64 MiB bound, with no threshold change.

## Remaining boundaries and next owner decision

| Required namespace | Latest result boundary |
|---|---|
| `seon.schema-test` | One baseline failure: `declared-reference-maps-accept-the-pull-reference-grammar`, `:seon.render/ambiguous-error`, `:in [:seon.render/candidates]`. The pre-implementation snapshot also fails it. |
| `seon.schema.edn-test` | No failures/errors in the latest combined run. |
| `seon.config-test` | Recorded 0 F / 3 E: `src/seon/config.clj:586` hands the incomplete upstream map to `db/pull`; missing-cluster refusals from `effective` fail their declared output or `result-caps` input (`:seon.config.agent/turn-completion-backstop-ms` is the first reported success-arm requirement). |
| `seon.instrument-test` | Three facet-census failures: `src/seon/sci/admit.clj` semantic-value and `src/seon/error.clj` refusal/latest-fact do not declare 23 installed render/MCP/process facets. The assertion is retained. |
| `seon.db-test` | 22 errors at the database/error-conversion boundary. The 15 upstream-map reader errors originate in `test/seon/db_test.clj:631` (the orchestrator's 1a family); remaining history, identity and write-return refusals exercise `src/seon/db.clj`. No database policy was changed. |
| `seon.fn-test` | Two errors in held `src/seon/fn.clj`: missing base `:at` at HEAD `:971`, and `declared-reference-edges` returning a base error without a declared facet. Dirty checkout fixes are excluded by the snapshot. |

The held production path that prevents completing the required fn proof is
**`src/seon/fn.clj`**. Other held paths remain untouched:
`src/seon/cluster/source.clj`, `src/seon/test/cache.clj`,
`src/seon/test/selection.clj`, `bin/test`, `bin/seon-hook`, and
`script/seon/fresh_operator.clj`. Snapshot provenance explicitly says source,
fn and `test/seon/cluster/source_test.clj` use HEAD bytes. These observations
never attribute a failure to excluded dirty bytes.

The SCI walk also records **4,228,637,320 bytes** against its unchanged 1 GiB
bound and fails its pull-plan reuse assertion. The selected-render test
hits an array-length OutOfMemoryError while printing a nested map. Their
causes are not proven by this lane's baseline. JFR samples from the same
foreground JVM identify instrumentation wrapper selection, DB identity
acquisition, projection-from-rows and fingerprint work. The sample interval
includes the direct/web calls after the SCI evaluation, so sample weights
are not the SCI counter's measured bytes. These are unresolved boundaries,
not falsely classified as foreign fixes. See
[the allocation issue](../../../seon/issues/guarded-public-walk-exceeds-allocation-bound.md).
Ordinary reconstruction retirement is step 3; this lane did not change it.

Three priced next steps:

1. **Coordinate the held fn and 1a/error-census repairs, then resume this
   slice (recommended).** Preserves the six-namespace green condition;
   costs those owners' integration checkpoint and one changed-input fast
   pass. The orchestrator can supply the coordinated baseline for the walk.
2. Release the relevant producer owners and expand this assignment to their
   error-conversion slice. Costs a broader producer/resource/consumer review
   plus affected tests; still needs coordination of the held publication files.
3. Authorize a provisional implementation commit with these named reds and
   allocation proof owed. Costs no immediate repair, but explicitly relaxes
   the latest landing condition and does not constitute a completed proof.

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

`candidate-validator` and `candidate-explainer` are retired in working bytes;
the sole fn-test caller uses its fixture projection. Both value APIs accept
only `[projection schema-key value]`. Production callers use existing
DB/request custody or construct once per operation. Maintenance's result
projection and turn disposition receive the generation explicitly, with
all discovered callers converted in the same pending slice. Render's
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
| Full generation providers | Not measured | 4,678; maximum 1 call each |
| Final sealed table | Not measured | 4,826 entries, one deliberate copy |
| SCI schema declaration bytes, prior implementation iteration vs latest | 67,456,856 | 44,510,808 |
| SCI function declaration bytes, latest | — | 9,289,792 |

The source-derived counts follow baseline `internal.cljc:364–366`
`fast-registry (assoc schemas k v)`, called once per form by baseline
`schema.clj:1901–1906`; Malli `registry.cljc:17–22` copies into its HashMap.
They are copied-entry counts, **not heap-byte measurements**. The SCI
67,456,856-byte figure is an earlier implementation iteration, not the
pre-change baseline. Both latest SCI declarations pass the unchanged
67,108,864-byte limit.

Latest fixture population: 3,237 schemas / 1,436 contracts; the synthetic
replacement-generation probe adds four schemas and one contract, producing
4,678 providers. Earlier after runs measured **4,667 providers, max one**
and then 4,676/max one as HEAD's canonical population grew. Those remain
valid dated observations, not constants asserted by tests. Warm acquisition
and supplied-registry admission both copy zero entries.

## Proof still owed

The prescribed load command is run before/after the recorder-only commit;
a final shared-tree load is recorded below. It does not substitute for the
unlanded coherent slice's HEAD proof. Required six-namespace green, affected
caller proof, and the orchestrator's cold/platform gates remain owed.
The final prescribed eight-namespace shared-tree load exited 0. HEAD had
advanced to `4d2f2d1ca` when inspected after that load; working implementation
changes were present, so this is not a clean-HEAD implementation proof.
`git diff --check` passed. Both fast sessions and the load JVM exited;
no lane JVM or scratch root remains active.
No post-change hot reload, in-place development adoption, owned scratch fork,
or browser paint is claimed. Initial read-only default observations below
are pre-change only. The orchestrator owns the reset-boundary live proof;
this lane must not adopt or restart default.

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

Includes the recorder commit and all pending code, regression and evidence
paths; excludes foreign checkout edits. This is a dated inventory.
The initial accepted `55076261e` also changed
`docs/seon/issues/mcp-exception-projection-is-opaque-after-the-kind-removal.md`;
that already-landed observation is unchanged in this pending overlay.

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
  seon.cluster.prompt-test
bin/test --platform
clojure -M -e "(require 'seon.schema 'seon.schema.internal 'seon.config 'seon.maintenance 'seon.cluster 'seon.turn 'seon.render 'seon.cluster.prompt)"
```

Cold commands are recorded, not executed by this lane. The equivalent fast
invocations use `bin/test-fast --paths` with the owned list above; the latest
combined run selected the six required namespaces plus `seon.sci.eval-test`
and `seon.cluster.prompt-test`. The final config-only follow-up changed only
a config test input and reran no unchanged namespace.

## Raw evidence identities

| Log | SHA-256 |
|---|---|
| `tmp/bridge-step1-before.log` | `ab389e8eac0164e7bbe5d109c432c61e63fe180507b3dda091d2d37b6dffd808` |
| `tmp/bridge-step1-after-corrected.log` | `ea8251e7b12627e62dd68d5e320046d4a2989edda0e1b0baaf34b0471689e4b5` |
| `tmp/bridge-step1-recorder-fixed-fast.log` | `f13af297484a68762f37e53d70017b2bb46a6c7beccc2d53e4c7c88cacb279e1` |
| `tmp/bridge-step1-predicate-custody-fast.log` | `facf564193c143d8ba7a9fc67fd2e0621ebee8860f4bf27d6e83370ba979b3a1` |
| `tmp/bridge-step1-config-arity-fast.log` | `54f01c07937ce9f710d34f556dcb671c182029d9e5d2e5f9f785e936d043f759` |

JFR recording SHA-256: `6b70d0b00749108f4857fdb76c85fda5891d9f4c7a1abea04a959b719db3ebb1`.
