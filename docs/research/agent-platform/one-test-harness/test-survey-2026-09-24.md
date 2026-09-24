---
type: research
status: evidence — read-only survey; implementation frozen
created: 2026-09-24
scope: every test namespace in test/ (323 namespaces with a deftest, 2,162 tests): what each protects, what it exercises, how it builds its world, recorded state on default, verdict
pins: working tree of refactor/agent-platform at 8bb0eea92 (plus uncommitted lane edits in test/), analysed 2026-09-24 ~18:35Z; recorded results read from live default (MCP jvm mode, read-only) 2026-09-24 ~18:40Z
companion: redef-census-2026-09-24.md (redefinition classes, replacements, design changes)
---

# Test survey: one harness, one interface (2026-09-24)

**What exists.** One pipeline already runs a test the way it runs an agent: `seon.test/run` →
`member-result` (branch + SCI fork + custody) → `runner/run-var!` → record → release. Around that
pipeline the tests carry 92,195 lines of their own world-building: second branches, second forks,
hand-built handles, raw stores and Var redefinitions. **The principal approach** has three parts. A
test receives the member world. Capabilities (provider endpoint, environment, roots, dials) arrive
as world data. The few tests whose subject is the host (boot, processes, JVM-global arming, file
stores) are PLATFORM members in an isolated JVM, reached through the same `seon.test/run` interface.

## Numbers first

| measure | value |
|---|---:|
| test namespaces (files with ≥1 `deftest`/`defspec`) / all files in `test/` | 323 / 342 |
| tests (`deftest` 2,161 + `defspec` 1; default holds 2,163 source-bearing test rows, all `:core`) | **2,162** |
| lines in test namespaces / in all of `test/` / in `test_support.clj` | 92,195 / 95,229 / 850 |
| recorded on default: green / red / no admitted execution | **308 / 489 / 1,365** |
| **verdict HARNESS** (fits the one-world harness as-is or after a named conversion) | **1,913** (59,989 body lines) |
| — as-is: no second world, no redefinition, no hand-built world | 907 |
| — only conversion is H1 (single `with-database` → the member's world, scripted) | 726 |
| — needs at least one harder conversion (H2–H8) | 280 |
| **verdict PLATFORM** (subject is the host: boot, processes, JVM-global arming, file store, published root, tooling) | **230** (7,707 body lines) in 57 namespaces; 45 namespaces are PLATFORM-majority |
| **verdict DELETE** (retirement guards, test-machinery tests, measurements, duplicates) | **19** (387 body lines) |
| namespace docstrings still saying "DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27)" | 10 (false current claims) |

The owner's hypothesis checks out on these numbers. After deletion, 89% of tests (1,913) fit one
world. The PLATFORM remainder is 10.6% (230 tests). It goes through the same `seon.test/run` request
with `:platform` membership.

## How verdicts were derived

The inventory is scripted. clj-kondo analysis (`:var-definitions`, resolved `:var-usages` with
`:from-var`) supplies per-test helper and owner calls. The walk follows one level into same-file
private helpers. The redefinition census contributes per-test redef classes, and rewrite-clj supplies
deftest metadata. Recorded state comes from `seon.test/recorded-result` for every symbol on default.
The verdict rule, in order:

1. **DELETE** when the body was read and it proves only a retirement, tests machinery the harness
   deletes, is a measurement rather than a behaviour, or is subsumed (list below).
2. **PLATFORM** when the test or its local helpers call `seon.cluster.boot/start!`, a process owner
   (`babashka.process`, `seon.cluster.process`), the file-backed published root
   (`seon.cluster.source-test`), operator/dev tooling in `script/` (`seon.operator`, `seon.dev.*`,
   `dev-cache`), JVM-global arming (`instrument/apply!`, `restore!`, `remove!`, `alter-var-root`,
   `preserving-instrumentation-state`), development reload, or `konserve.filestore`. A namespace can
   also be PLATFORM because its subject is the OS or file store itself: `cluster/store_test`,
   `cluster/export_test`, `blob_publication_test`, `shell/jvm_test`, `cluster/process_test`,
   `instrument_test`, `profile_test`, `registry_isolation_test`, `instrument_replaced_roots_test`,
   `render/web_adoption_test`, `schema_redeclare_test`, `dev/edit_feedback_test`, `dev/mcp_bridge_test`,
   `dev/prepl_retention_test`, `cluster/publication_delta_test`.
3. Otherwise **HARNESS**, with the conversion classes its body needs.

I read every namespace docstring and every test name. I read bodies only for the delete candidates,
the helpers in `test_support.clj` and the provider seams. **A HARNESS verdict says the test fits; it
does not prove the test is needed.** Only the 19 deletions are claimed as unneeded. Subsumption among
the overlapping turn suites is UNVERIFIED: `seon/turn_test` 37, `cluster/turn_test` 60, `turn_loop_test`
26, `turn_work_test` 13, `turn_continue_test` 9, `loop_proof_test` 4 and `gen/loop_test` 3, 152 tests
in all. Finding the duplicates needs a body-level read per behaviour class.

## Conversion classes (HARNESS tests; a test may need several)

| class | tests | what changes | owner change |
|---|---:|---|---|
| H1 member world | 809 | drop the single `with-database` / `fork-cluster-ctx` wrapper; the body gets the member's connection and ctx | none (scripted sweep; `test_support` loses ≈200 lines) |
| H2 sibling world | 23 | keep an ≈8-line `with-database` for a genuinely second world (two clusters, two branches) | none |
| H3 external capability | 89 | the AI provider becomes a loopback endpoint in config with `:seon.config.ai/no-auth`; credentials, resource roots and cache roots become world values | 6 small owner changes (census §design changes) |
| H4 failure injection | 55 | a real refusing input through the real writer or an unreadable database value; resource faults use real conditions | none for db refusals; an open question for release faults |
| H5 timing / bound | 10 | retry and backstop dials in config; `binary-threshold` becomes a dial | 2 small changes |
| H6 spy | 92 | "derives once" → the cache owner's miss count, otherwise delete; argument capture → assert the durable facts | UNVERIFIED how many owners expose miss counts |
| H7 behaviour redef | 26 | build the state through canonical writers (turn situations, readiness, analysis findings via the prebuilt-manifest seam) | `seon.cluster.prompt/prompt` takes the rendered context |
| H8 hand-built world | 30 | `d/create-database`/`d/connect`/`sci/init`/`store/open-branch!` → the member world, or `:seon.test-support/fresh-store?` for store-global subjects | move `with-fresh-database` to the store owner |

`cluster-handle` (58 enclosing vars) and `environment` (50) are also hand-built worlds. They are
counted in the census, not in H8, because they come through `test_support`. Both are deleted once the
member's handle and environment are the world.

## PLATFORM (230 tests), by primary reason

| reason | tests | examples |
|---|---:|---|
| subject is the host itself (namespace ruling) | 64 | `instrument_test` 23, `cluster/store_test` 17, `shell/jvm_test` 10, `cluster/export_test` 5, `publication_delta_test` 4, `profile_test` 4, `blob_publication_test` 1 |
| JVM-global arming | 50 | `instrument_test` 29, `sci/eval_test` 5, and one test each in 16 namespaces (`db_test`, `error_test`, `context_test`, `core_functions_test`, `cluster/agent_test` `hot-reload-var-test`, `cluster/mcp_test`, …) |
| operator / dev tooling (`script/`) | 45 | `dev/mcp_bridge_test` 25, `cluster/boot_test` 7, `dev/dependency_cache_test` 4, `dev/prepl_retention_test` 2, one each in 7 more |
| processes | 33 | `dev/edit_feedback_test` 12, `dev/changed_test_test` 6, `cluster/process_test` 3, `test/selection_test` 3, `cluster/boot_test` 2, one each in 7 more |
| cluster boot | 18 | `cluster/armed_test` 6, `cluster/boot_test` 2, `config_application_test` 2, one each in 8 more (`cohost_boot_test`, `program_restart_test`, `oversight_test` `a-booted-cluster-tells-its-live-fleet-story`, …) |
| file-backed published root | 18 | `publication_lock_test` 6, `source_test` 3, `source_lineage_test` 3, one each in 6 more |
| file store (`konserve.filestore`) | 2 | `export_test` 1, `store_test` 1 |

## DELETE (19 tests, 387 body lines)

| namespace | test | lines | reason |
|---|---|---:|---|
| bootstrap_test.clj | `authored-plan-machinery-is-deleted` | 5 | retirement guard; git is the archive |
| dev/mcp_bridge_test.clj | `deleted-list-sessions-name-is-an-unknown-tool` | 11 | duplicate of deleted-tool-name-is-an-unknown-tool |
| error/refusal_test.clj | `facade-is-retired-and-reader-refusal-is-a-literal` | 8 | retirement guard + spy on a constructor |
| error_write_timing_test.clj | `one-error-write-has-a-measured-preparation-validation-and-commit` | 31 | measurement harness (with-redefs-fn timing roots) |
| instrument_test.clj | `hot-host-declared-schema-check-measurement` | 27 | measurement, not behaviour: move to a committed script |
| instrument_test.clj | `hot-sci-declared-schema-check-measurement` | 30 | measurement: move to a committed script |
| instrument_test.clj | `instrumentation-observations-do-not-carry-legacy-class-stamps` | 14 | retirement guard over retired keys |
| render/root_pull_test.clj | `cold-root-pull-records-an-informational-latency-sample` | 17 | informational latency sample, no behaviour |
| render/value_test.clj | `profile-fit-supersedes-legacy-print-cuts-with-values` | 7 | subsumed by ai-values-never-wrap-at-a-display-width |
| schema_test.clj | `registry-generation-operation-counts` | 60 | operation-count measurement over Malli internals |
| schema_test.clj | `full-projection-fingerprint-dispatch-timing` | 20 | measurement: move to a committed script |
| schema_test.clj | `declaration-manifest-projection-build-cost` | 12 | measurement: move to a committed script |
| sci/reader_test.clj | `evaluator-no-longer-owns-a-second-reader-surface` | 11 | source-site census of which files read; implementation detail |
| test/fixture_timing_test.clj | `fixture-acquisition-is-a-pointer-branch-off-the-executing-commit` | 30 | tests with-database's second branch, which the member world replaces |
| test/runner_test.clj | `a-committed-retraction-survives-the-restore-and-is-named-a-committed-change` | 38 | tests restore-live-cluster-schema!, deleted after shrink-db D |
| test/runner_test.clj | `a-registration-that-committed-nothing-is-restored-away-and-named-drift` | 27 | tests restore-live-cluster-schema! |
| test/runner_test.clj | `a-run-that-touched-no-declaration-reports-nothing` | 21 | tests restore-live-cluster-schema! |
| test_reaching_test.clj | `in-process-results-detect-worker-global-drift` | 14 | tests the drift detector (alter-var-root over seon.id/digest) |
| test_runner_test.clj | `the-drift-snapshot-names-the-running-clusters` | 4 | tests runner/ambient-snapshot, deleted with the drift trio |

Whole-namespace deletion: `test/fixture_timing_test.clj` (its one test). Its test and the three
restore-family tests in `test/runner_test.clj` die with their machinery: `with-database`'s second
branch, `restore-live-cluster-schema!` and `ambient-snapshot` (testing-2026-09-24.md rows 1–2). The three
restore-family deletions wait for shrink-db row D; the drift and fixture-timing ones can go now. One stale claim found: testing-2026-09-24.md reports
`seon.test.arm` as 254 lines with zero callers, but `src/seon/test/` at this tree holds only
`accretion.clj`, `cache.clj` and `runner.clj`, so that deletion has already landed.

## Per-test exceptions (verdict differs from its namespace's majority)

| namespace | test | verdict | signal |
|---|---|---|---|
| adoption_diagnostic_test.clj | row-refusal-describes-the-cause-without-copying-the-projection | HARNESS |  |
| cluster/agent_test.clj | hot-reload-var-test | PLATFORM | jvm-arming |
| cluster/boot_test.clj | an-uncaught-throwable-without-its-world-is-printed-and-never-stored-elsewhere | HARNESS |  |
| cluster/mcp_test.clj | only-an-evaluation-that-changed-loaded-code-drops-cached-pages | PLATFORM | jvm-arming |
| cluster/publication_lock_test.clj | stale-basis-is-refused-by-the-transaction-writer | HARNESS |  |
| cluster/source_test.clj | source-tombstone-provenance-does-not-prevent-live-removal | HARNESS |  |
| cluster/source_test.clj | publication-input-digests-include-unreported-edits-and-deletions | HARNESS |  |
| concurrency_independence_test.clj | receipt-diagnostic-selects-only-present-failure-facts | HARNESS |  |
| config_application_test.clj | every-config-entry-has-an-honest-application-contract | HARNESS |  |
| config_application_test.clj | an-unread-config-attribute-is-detected-as-a-dead-dial | HARNESS |  |
| db_test.clj | instrumented-wildcard-pull-keeps-unparsed-database-fields-ordinary | PLATFORM | jvm-arming |
| error/refusal_test.clj | armed-refusal-returns-every-declared-family-without-refusing | PLATFORM | jvm-arming |
| error_test.clj | a-contract-violations-fault-keeps-the-value-that-broke-it | PLATFORM | jvm-arming |
| oversight_test.clj | a-booted-cluster-tells-its-live-fleet-story | PLATFORM | boot |
| reset_edges_test.clj | source-publication-refuses-an-outside-caller-and-keeps-its-head | PLATFORM | published-root |
| sci/documentation_test.clj | a-contract-mistake-carries-the-same-documentation-as-doc | PLATFORM | jvm-arming |
| sci/eval_test.clj | instrumented-generated-form-does-not-advise-an-absent-program-row | PLATFORM | jvm-arming |
| sci/eval_test.clj | the-evaluator-remains-live-after-its-namespace-reloads | PLATFORM | jvm-arming |
| sci/eval_test.clj | compiled-runtime-roots-cannot-be-redefined-by-agent-code | PLATFORM | jvm-arming |
| sci/eval_test.clj | sci-fork-preserves-compiled-var-hot-reload | PLATFORM | jvm-arming |
| sci/eval_test.clj | acquisition-uses-the-effective-config-projection-when-instrumented | PLATFORM | jvm-arming |
| test/selection_test.clj | changed-inputs-are-decided-by-content-not-modification-time | PLATFORM | process |
| test/selection_test.clj | a-symlinked-input-is-digested-through-the-link-never-traversed | PLATFORM | process |
| test/selection_test.clj | external-input-identity-includes-gitlinks-but-not-program-edits | PLATFORM | process |
| test_runner_test.clj | dependency-source-digest-does-not-name-the-checkout | PLATFORM | tooling |
| test_support_test.clj | an-instrumentation-test-restores-the-entering-contracts-on-failure | PLATFORM | jvm-arming |

## Every namespace

Columns: tests; file lines; recorded green/red/none on default; verdict counts HARNESS/PLATFORM/DELETE;
conversion classes over its HARNESS tests; the behaviour it protects (the namespace docstring's first
sentence, else *italic* = its first test's name); the first-party owners it requires (up to five).

| namespace | T | lines | rec g/r/n | verdict H/P/D | conversions | protects (ns doc, else first test) | exercises (first-party owners) |
|---|---:|---:|---|---|---|---|---|
| my/agent_test.clj | 2 | 56 | 0/2/0 | 2/0/0 | H1×2 | _settings are one owned component with a derived render pair_ | agent, db, schema |
| my/background_test.clj | 3 | 92 | 3/0/0 | 3/0/0 | H1×1 | _background error renderers cover every declared declared schema_ | background, db, schema |
| my/edit_test.clj | 2 | 45 | 2/0/0 | 2/0/0 | H1×1 | _form operation relation is structural and open_ | db, edit, schema |
| my/examples_fixture.clj | 1 | 10 | 0/0/1 | 1/0/0 |  | One statically indexed test for the agent API's executable examples. |  |
| my/examples_test.clj | 1 | 189 | 0/0/1 | 1/0/0 | H1×1 | _public docstring examples run in the canonical agent context_ | bootstrap, cluster.agent, cluster.instruction, config, db |
| my/fs_test.clj | 2 | 51 | 2/0/0 | 2/0/0 | H1×1 | _relationship predicates require one declared arm_ | db, fs, schema |
| my/message_test.clj | 10 | 225 | 8/1/1 | 10/0/0 | H1×1 | Message transaction inputs and durable agent-facing writes. | my.message, cluster.message, db, run, schema |
| my/note_test.clj | 4 | 105 | 0/4/0 | 4/0/0 |  | The minimal durable current-note home and its rebirth property. | db, note, schema |
| my/plan_api_test.clj | 2 | 96 | 0/2/0 | 2/0/0 | H1×2 | _plan writes use one request map and return the changed item_ | cluster.agent, config, db, id, plan |
| my/plan_test.clj | 22 | 609 | 0/22/0 | 22/0/0 | H1×1 H4×1 | The agent-owned plan component tree, its derivation, and its two projections. | config, db, env, error, plan |
| my/program_mutation_test.clj | 3 | 113 | 0/3/0 | 3/0/0 | H1×3 | _retraction refuses before context mutation and removes clean declarations_ | my.program, config, db, sci.eval |
| my/program_query_test.clj | 2 | 62 | 0/2/0 | 2/0/0 | H1×2 | _predicate arguments are bindings not nested clojure evaluations_ | db |
| my/program_test.clj | 5 | 178 | 0/5/0 | 5/0/0 | H1×5 | _program reads name the referrers and propose without writing_ | my.program, config, db, env, error |
| my/test_test.clj | 1 | 78 | 0/0/1 | 1/0/0 | H1×1 | Agent-authored tests resolve their admitted source with their cluster's custody. | config, db, env, id, program |
| my/turn_test.clj | 6 | 133 | 1/5/0 | 6/0/0 | H1×2 | Sealed acceptance draft for the two dispositions (N3, package 1). | config, db, render, run, schema |
| my/web_test.clj | 2 | 25 | 0/2/0 | 2/0/0 |  | _requests remain open and rigorous_ | my.web, schema |
| adoption_contract_freshness_test.clj | 1 | 147 | 0/1/0 | 0/1/0 |  | Real source adoption, isolated from the shared worker's loaded program. | cluster, cluster.boot, cluster.process, config, db |
| adoption_diagnostic_test.clj | 2 | 59 | 0/0/2 | 1/1/0 |  | _row refusal describes the cause without copying the projection_ | cluster, operator, sci.eval |
| adoption_margin_test.clj | 1 | 19 | 0/0/1 | 1/0/0 |  | _closed publication observer refuses at the next phase_ | cluster, cluster.process |
| adoption_rows_test.clj | 1 | 32 | 0/1/0 | 1/0/0 |  | _adoption identities carry no nil member_ | cluster, fn, program |
| agent_call_edges_test.clj | 1 | 120 | 0/1/0 | 1/0/0 | H1×1 | _admitted declarations retain call dependencies across turn order_ | cluster, cluster.agent, config, db, eval |
| agent_situation_test.clj | 2 | 67 | 1/1/0 | 2/0/0 | H1×1 | The live help situation and its declared agent render. | bootstrap, cluster.agent, db, env |
| ai/tokens_test.clj | 6 | 124 | 0/0/6 | 6/0/0 |  | Recurring acceptance for the one token sizer and its calibration. | ai.tokens |
| ai_stream_fold_test.clj | 19 | 426 | 0/9/10 | 19/0/0 | H1×2 | Streamed replies: the fold, the wire, and the two exercise blocks. | ai, blob, config, db, schema |
| ai_test.clj | 56 | 1475 | 1/11/44 | 56/0/0 | H1×12 H3×11 H6×1 | Sealed acceptance draft for the model seam (N3, C10). | ai, cluster.agent, cluster.reply, config, db |
| await_owner_census_test.clj | 1 | 46 | 0/0/1 | 1/0/0 | H1×1 | Program-graph census for the single bounded-await owner. | db |
| await_test.clj | 4 | 94 | 0/0/4 | 4/0/0 |  | _port operations share one declared deadline_ | await, db, schema |
| background_blob_test.clj | 1 | 173 | 0/0/1 | 1/0/0 |  | _background binary results remain exact across the inline threshold_ | blob, config, db, effect, flow |
| background_test.clj | 1 | 51 | 0/0/1 | 1/0/0 | H1×1 | _terminal background results open one result only run_ | config, db, turn |
| blob_error_test.clj | 2 | 37 | 0/0/2 | 2/0/0 | H1×2 | _publication preserves a refused root transaction_ | blob, schema |
| blob_publication_test.clj | 1 | 195 | 0/0/1 | 0/1/0 |  | Deterministic two-sided publication/collection orderings on a file store. | blob, cluster, cluster.registry, cluster.store, db |
| blob_test.clj | 7 | 231 | 0/0/7 | 7/0/0 | H1×1 H5×1 H7×1 H8×1 | Content-addressed blob behavior on Seon's real file store. | blob, cluster.registry, cluster.store, db, schema |
| blob_threshold_test.clj | 1 | 16 | 0/0/1 | 1/0/0 | H1×1 | Settlement preserves shown text independently of the blob threshold. | turn |
| boot_process_attribute_test.clj | 1 | 38 | 0/0/1 | 1/0/0 | H1×1 | _canonical population retains transaction process provenance_ | cluster, cluster.instruction, db, schema, schema.datahike |
| bootstrap_test.clj | 9 | 470 | 3/6/0 | 8/0/1 | H1×8 H7×1 | The live-fact opening generator and its agent-creation seam. | bootstrap, cluster, cluster.agent, config, db |
| bounded_boundary_census_test.clj | 4 | 336 | 0/0/4 | 4/0/0 |  | Recurring source census for bounded locks and foreign subprocesses. |  |
| call_preparation_test.clj | 23 | 1007 | 9/13/1 | 23/0/0 | H1×20 H2×3 H8×1 | Recurring proof for P17 S1: declared supplied defaults, the plan derived from program facts, and the cluster-l | call-preparation, db, env, error, plan |
| classification_test.clj | 4 | 92 | 2/2/0 | 4/0/0 | H1×4 | Classification follows declared facts when names and membership disagree. | ai, cluster.instruction, cluster.process, config, db |
| cluster/acquisition_test.clj | 1 | 183 | 0/0/1 | 0/1/0 |  | _acquisition owns only its isolated resources_ | cluster, cluster.agent, cluster.registry, cluster.store, config |
| cluster/adoption_exclusion_test.clj | 1 | 60 | 1/0/0 | 1/0/0 |  | No development adoption reloads or arms under a running test member: members hold `seon.cluster/call-excluding | cluster, db |
| cluster/adoption_record_test.clj | 1 | 64 | 0/0/1 | 1/0/0 | H1×1 | An adoption's schema, program and issue rows are ONE transaction (`seon.cluster/adopt-rows!`); the adoption re | cluster, cluster.registry, cluster.source, db, issue |
| cluster/agent_arming_test.clj | 2 | 236 | 0/0/2 | 2/0/0 | H1×2 | THE CLASS: a created agent whose absence of execution reads as health. | cluster, cluster.agent, cluster.wake, db, env |
| cluster/agent_identity_test.clj | 3 | 140 | 0/2/1 | 3/0/0 |  | _identity renders from current database facts_ | agent, cluster.agent, config, db, env |
| cluster/agent_namespace_test.clj | 5 | 158 | 0/5/0 | 5/0/0 | H1×5 | Namespace assignment is a non-unique ref; stewardship is the namespace's own `:seon.ns/steward` fact: creation | cluster.agent, db, problems |
| cluster/agent_test.clj | 22 | 1963 | 0/1/21 | 21/1/0 | H3×5 H4×3 H5×1 H6×2 H7×1 | The F1 sealed suite: agents are flows (seeds 2026072811-2026072820). | ai, bootstrap, cluster, cluster.agent, cluster.prompt |
| cluster/armed_test.clj | 6 | 484 | 0/0/6 | 0/6/0 |  | The armed layers of a booted cluster: root, the loop, the fault path. | ai, bootstrap, cluster, cluster.agent, cluster.boot |
| cluster/boot_reply_test.clj | 2 | 47 | 0/0/2 | 2/0/0 |  | A request's refusal crosses the prepl as bounded text from the value renderer's projection-free core. | cluster.boot, cluster.store |
| cluster/boot_test.clj | 12 | 524 | 0/1/11 | 1/11/0 |  | Real client/store exclusion drills. | cluster, cluster.boot, cluster.process, cluster.registry, cluster.store |
| cluster/branch_release_retention_test.clj | 1 | 60 | 0/0/1 | 1/0/0 |  | A released branch connection's node cache is collectable: no Seon cache keeps it. | cluster.agent, config, db, sci.eval, sci.kernel |
| cluster/cohost_boot_test.clj | 1 | 139 | 0/0/1 | 0/1/0 |  | Two sovereign clusters in ONE JVM, with instrumentation live. | cluster, cluster.boot, config, instrument, schema |
| cluster/core_error_mode_test.clj | 1 | 23 | 0/1/0 | 1/0/0 |  | An unreadable core-error dial panics; it never selects record mode. | cluster, config, db, fault |
| cluster/def_reload_test.clj | 1 | 30 | 0/0/1 | 1/0/0 | H1×1 | _an as alias is no load edge_ | db, fn, id |
| cluster/entrance_supplier_test.clj | 1 | 89 | 0/0/1 | 1/0/0 |  | _supplied context acquires and releases an isolated evaluation_ | my.program, cluster.agent, cluster.registry, config, db |
| cluster/evaluate_sources_test.clj | 2 | 282 | 0/2/0 | 2/0/0 | H1×2 H4×1 H6×2 | _ordered evaluation retains one explicit basis without publication_ | agent, blob, cluster.agent, config, db |
| cluster/export_test.clj | 6 | 279 | 0/0/6 | 0/6/0 |  | Sealed acceptance for the export path — clone plus re-identify (B2). | cluster.export, cluster.registry, cluster.store, db, schema |
| cluster/fault_message_test.clj | 1 | 34 | 0/1/0 | 1/0/0 | H1×1 | _a recording refusal still prints the source fault_ | cluster, config, fault, flow |
| cluster/fault_storage_test.clj | 2 | 242 | 0/0/2 | 2/0/0 | H8×2 | The fault path keeps bulky evidence in the content-addressed store. | blob, cluster, cluster.registry, cluster.store, config |
| cluster/instruction_test.clj | 4 | 171 | 1/3/0 | 4/0/0 | H1×4 | Computed cluster context membership and idempotent entity initialization. | cluster, cluster.instruction, db |
| cluster/lazy_agents_test.clj | 1 | 34 | 0/0/1 | 1/0/0 | H1×1 | _idle agents wait for work but schedule owners need their timers_ | cluster.agent, cluster.message, config, db, schedule |
| cluster/listener_failure_test.clj | 1 | 88 | 0/1/0 | 1/0/0 | H1×1 | N3: a failed Datahike listener hand-off is one stored fault on the captured cluster world, never recursive, an | blob, cluster.store, config, db, env |
| cluster/mcp_test.clj | 14 | 578 | 1/2/11 | 13/1/0 | H1×3 H4×2 H6×1 H7×1 | The MCP surface shares Seon's admitted print-node value chain. | cluster, cluster.boot, config, db, env |
| cluster/merge_test.clj | 2 | 117 | 0/0/2 | 2/0/0 | H8×2 | Prepare, gate and named accept of a candidate branch's replacements (`seon.cluster.source/prepare-merge!`, `ac | cluster.agent, cluster.registry, cluster.source, db, id |
| cluster/message_assignment_test.clj | 4 | 141 | 0/0/4 | 4/0/0 |  | Subject tokens, sender classification and assignment correlation are independent. | cluster.message, cluster.wake, db, problems |
| cluster/message_test.clj | 20 | 850 | 0/0/20 | 20/0/0 | H8×1 | Delivery: the driver half of `my.message`, against real facts. | cluster.message, cluster.wake, config, db, id |
| cluster/problem_routing_test.clj | 4 | 247 | 0/4/0 | 4/0/0 |  | Owner routing and stored evaluation settlement. | cluster.message, db, print, problems, schema |
| cluster/process_test.clj | 3 | 92 | 0/0/3 | 0/3/0 |  | The subprocess termination owner: TERM, grace, verified force, awaited exit. | cluster.process |
| cluster/program_restart_test.clj | 1 | 377 | 0/0/1 | 0/1/0 |  | Live restart proof for database-backed SCI program acquisition. | ai, bootstrap, cluster, cluster.boot, cluster.message |
| cluster/prompt_test.clj | 16 | 574 | 4/12/0 | 16/0/0 | H1×1 H7×3 H8×1 | Recurring acceptance for the prompt's append-only REPL history. | ai.tokens, blob, cluster, cluster.agent, cluster.prompt |
| cluster/publication_adoption_test.clj | 2 | 83 | 0/0/2 | 0/2/0 |  | _failed loaded definition scope restores arming_ | cluster, cluster.boot, cluster.registry, cluster.source, db |
| cluster/publication_concurrency_test.clj | 1 | 32 | 0/0/1 | 0/1/0 |  | _concurrent publications return one committed head_ | cluster.source |
| cluster/publication_declared_schema_test.clj | 1 | 36 | 0/0/1 | 0/1/0 |  | _publication admits optional in memory offending members_ | cluster.source, id, schema |
| cluster/publication_delta_test.clj | 5 | 188 | 0/0/5 | 0/5/0 |  | _transaction report identities select only changed program rows_ | cluster, db, fn, id, instrument |
| cluster/publication_export_test.clj | 1 | 50 | 0/0/1 | 1/0/0 |  | _export identifies the committed program without a live cluster_ | cluster, cluster.source, cluster.store, db, fn |
| cluster/publication_findings_test.clj | 1 | 34 | 0/0/1 | 1/0/0 |  | _publication reports finding count and delta without a roster_ | cluster, fn, fn.analyzer |
| cluster/publication_host_test.clj | 1 | 137 | 0/0/1 | 0/1/0 |  | _live init keeps the host process and launches no jvm_ | cluster, cluster.boot, cluster.process, cluster.registry, cluster.source |
| cluster/publication_inputs_test.clj | 12 | 303 | 0/0/12 | 12/0/0 | H1×3 H6×3 | _changed paths hash only the named files and use recorded pins_ | cluster, cluster.source, cluster.store, db, fn |
| cluster/publication_lock_test.clj | 7 | 272 | 0/0/7 | 1/6/0 | H1×1 | _concurrent publication refuses the stale head without a monitor wait_ | cluster, cluster.registry, cluster.source, cluster.store, db |
| cluster/publication_notes_test.clj | 2 | 58 | 0/0/2 | 2/0/0 | H1×2 | _source only publication does not read issue notes_ | cluster.source, db, issue |
| cluster/publication_report_test.clj | 1 | 35 | 0/0/1 | 1/0/0 | H1×1 | _writer reports and their composed datoms name the same changes_ | db, fn, program |
| cluster/publication_reuse_test.clj | 1 | 87 | 0/0/1 | 0/1/0 |  | _recorded result resolves after one file publication_ | cluster, cluster.source, cluster.store, db, fn |
| cluster/publication_serialize_test.clj | 3 | 65 | 0/1/2 | 3/0/0 |  | A publication publishes the paths it names from the bytes it loads, or refuses. | cluster, db, fs |
| cluster/publication_test.clj | 1 | 26 | 0/0/1 | 1/0/0 | H1×1 | _previous file artifacts derive from published facts_ | db, fn, fs |
| cluster/registry_test.clj | 14 | 563 | 0/0/14 | 14/0/0 | H1×1 H4×1 H8×9 | Sealed acceptance for the branch-lifecycle registry (B2). | blob, cluster.registry, cluster.store, config, db |
| cluster/release_context_test.clj | 2 | 75 | 0/0/2 | 2/0/0 | H4×2 | _a failing connection release still unlinks and forgets the context_ | cluster.agent, cluster.registry, cluster.store, db |
| cluster/reload_per_declaration_test.clj | 8 | 152 | 0/0/8 | 8/0/0 |  | _ordinary defn reloads only its namespace_ | cluster, db, fn, id |
| cluster/reply_test.clj | 18 | 461 | 0/0/18 | 18/0/0 |  | Sealed acceptance draft for reply splitting (N3, C4). | cluster.reply, schema, sci.reader |
| cluster/resume_artifact_routing_test.clj | 1 | 87 | 0/1/0 | 1/0/0 | H1×1 | X2 keeps interrupted process history out of namespace-owner routing. | cluster.message, db, problems, turn |
| cluster/retired_attributes_test.clj | 1 | 36 | 0/0/1 | 1/0/0 | H8×1 | Retirement reads only attribute idents from Datahike's installed schema. | cluster |
| cluster/root_seed_digest_test.clj | 2 | 65 | 0/1/1 | 2/0/0 | H1×2 | _root seed declarations come from source_ | cluster, db, program, render.value, sci.reader |
| cluster/save_gate_test.clj | 3 | 143 | 1/0/2 | 3/0/0 |  | The save gate: a change reaches its target only when the tests reaching it pass on the target's candidate bran | cluster, cluster.registry, db, id |
| cluster/source_database_test.clj | 1 | 46 | 0/0/1 | 1/0/0 | H1×1 | _source commit database carries its own projection_ | cluster.source, cluster.store, db, fn |
| cluster/source_lineage_test.clj | 3 | 216 | 0/0/3 | 0/3/0 |  | Publication preserves history and the expected branch head. | cluster.registry, cluster.source, cluster.store, db, fs |
| cluster/source_nochange_test.clj | 1 | 30 | 0/0/1 | 0/1/0 |  | _unchanged digest keeps the published commit without a transaction_ | cluster.source, db |
| cluster/source_test.clj | 5 | 281 | 2/1/2 | 2/3/0 | H1×1 | _digest is stable and refuses an absent root_ | cluster, cluster.registry, cluster.source, cluster.store, db |
| cluster/status_test.clj | 3 | 101 | 0/0/3 | 3/0/0 | H1×3 | _accounting joins runtime turns and preserves unknown cost_ | blob, cluster.status, db, schema |
| cluster/store_test.clj | 18 | 686 | 0/0/18 | 0/18/0 |  | Sealed acceptance for the store rung (B1). | cluster.store, db, schema |
| cluster/store_transact_test.clj | 8 | 203 | 0/0/8 | 8/0/0 |  | Sealed acceptance draft for the transaction wrapper (N3, C5-C6). | db, error, schema, schema.datahike |
| cluster/turn_test.clj | 60 | 3822 | 0/59/1 | 60/0/0 | H1×1 H3×53 H4×7 H5×1 H6×10 H7×1 | Turn integration on canonical databases and real SCI contexts. | ai, cluster, cluster.agent, cluster.message, cluster.prompt |
| cluster/uncaught_handler_test.clj | 1 | 38 | 0/0/1 | 1/0/0 | H1×1 | The process uncaught handler stores a failure in the world it carries. | config, db, env |
| cluster/wake_test.clj | 17 | 746 | 8/9/0 | 17/0/0 | H1×6 | The wake, through the ONE listener that survives: `route!`. | cluster.wake, config, db, error, schema |
| cluster/web_binding_test.clj | 1 | 65 | 0/0/1 | 1/0/0 | H1×1 | _every web binding publishes its actual address_ | cluster, config, render.web |
| cluster_test.clj | 13 | 405 | 1/3/9 | 13/0/0 | H1×10 H4×1 | Cluster reconciliation invariants that own no cluster process. | cluster, cluster.boot, config, db, error |
| concurrency_independence_test.clj | 2 | 632 | 0/0/2 | 1/1/0 | H1×1 | Fact-space proof that many agents independently fold on one branch. | ai, bootstrap, cluster, cluster.agent, cluster.boot |
| concurrency_streams_test.clj | 2 | 157 | 0/0/2 | 2/0/0 | H1×2 | Deterministic collision constructions retained from the 2026-08-04 shared-cluster boundary verification. | cluster.agent, cluster.message, config, db, render.transcript |
| concurrency_test.clj | 2 | 248 | 0/0/2 | 2/0/0 | H2×1 | Concurrent source turns through real per-agent graphs on two branches. | cluster, cluster.agent, cluster.wake, config, db |
| config_application_test.clj | 4 | 255 | 0/0/4 | 2/2/0 | H1×1 | Standing proof that every registered config entry reaches a runtime consumer. | ai, cluster, cluster.boot, config, db |
| config_defaults_test.clj | 1 | 11 | 0/0/1 | 1/0/0 |  | _shipped defaults equal explicit manifest compilation_ | config |
| config_functions_test.clj | 1 | 32 | 0/0/1 | 1/0/0 | H1×1 | _manifest functions must exist before reconciliation_ | config, db |
| config_test.clj | 24 | 711 | 0/0/24 | 24/0/0 | H1×12 H2×1 H3×1 H4×1 H6×3 H7×1 | Acceptance proofs for the one manifest compiler and config apply. | agent, cluster.agent, config, db, reconcile |
| context_capture_history_test.clj | 1 | 56 | 0/0/1 | 1/0/0 | H1×1 | _retracted capture retains the exact prompt in history_ | cluster.agent, context, db, turn |
| context_capture_test.clj | 1 | 59 | 0/0/1 | 1/0/0 | H1×1 | _context contributions reference existing evaluations_ | context, db |
| context_cookbook_trial_test.clj | 1 | 25 | 0/0/1 | 1/0/0 | H1×1 | _a provider refusal is not a comprehension score_ |  |
| context_selection_test.clj | 2 | 173 | 0/2/0 | 2/0/0 | H1×2 | _selection references terminal evaluations in writer decided order_ | context, db |
| context_test.clj | 1 | 24 | 0/0/1 | 0/1/0 |  | Contract proof for context derivation between runs. | context, db, instrument |
| contracts_compile_test.clj | 2 | 395 | 2/0/0 | 2/0/0 |  | Every declared `:malli/schema` compiles against the packaged projection. | error.refusal, fn, fn.analyzer, schema, schema.edn |
| contracts_install_test.clj | 1 | 50 | 0/0/1 | 1/0/0 |  | _function literal contract is refused before printing or installing_ | db, sci.eval |
| contracts_plan_test.clj | 2 | 99 | 0/0/2 | 2/0/0 |  | _installed contract refusal names the run5 failing coordinate_ | ai.tokens, db, schema |
| core_functions_test.clj | 1 | 91 | 0/1/0 | 0/1/0 |  | _auto check checks the function instead of refusing its own arguments_ | config, db, program, sci.eval, sci.kernel |
| custody_stability_test.clj | 5 | 315 | 0/0/5 | 5/0/0 | H1×3 H2×2 | Standing database checks for the current custody-returning surface. | config, db, schema, sci.eval |
| data_shapes_test.clj | 9 | 424 | 4/5/0 | 9/0/0 | H1×9 | _raw plan forms preserve the component and use transaction time_ | ai, cluster.agent, cluster.message, config, db |
| datahike_fork_test.clj | 2 | 94 | 0/0/2 | 2/0/0 | H1×1 | Seon-owned acceptance checks for behavior maintained in its Datahike fork. | db, schema, turn |
| db/declaration_population_test.clj | 1 | 151 | 0/0/1 | 1/0/0 | H1×1 H4×1 | Carried values decode normally; missing input never rebuilds declarations. | db, env, schema, schema.edn, sci.admit |
| db_error_test.clj | 1 | 24 | 0/0/1 | 1/0/0 | H1×1 | _unclassified writer exceptions return a complete write refusal_ | db, schema |
| db_immutability_test.clj | 1 | 27 | 0/0/1 | 1/0/0 | H1×1 | The other half of the simultaneity law: every agent turn holds one immutable database value, so a concurrent t | db |
| db_test.clj | 76 | 2434 | 60/15/1 | 75/1/0 | H1×60 H2×2 H5×1 H6×6 H8×3 | _supplied database pulls carry the projection across unbound reads_ | cluster, cluster.agent, config, db, env |
| db_transact_shape_test.clj | 1 | 59 | 0/0/1 | 1/0/0 | H1×1 | Regression for the one native `seon.db/transact!` return shape. | config, db, env, sci.eval |
| dev/changed_test_test.clj | 6 | 189 | 0/0/6 | 0/6/0 |  | _changed paths are handed to the one gate selector_ | cluster.process |
| dev/clj_kondo_test.clj | 1 | 40 | 0/0/1 | 0/1/0 |  | _incomplete dependency cache is repopulated before analysis_ | cluster.process, dev.clj-kondo |
| dev/dependency_cache_test.clj | 5 | 356 | 0/0/5 | 0/5/0 |  | _refresh preserves a recorded jvms exact cache directory_ | cluster.process, dev.state |
| dev/docstring_test.clj | 13 | 217 | 0/0/13 | 13/0/0 |  | _compliant first line test_ | dev.docstring |
| dev/edit_feedback_test.clj | 13 | 682 | 0/0/13 | 0/13/0 |  | _pre edit blocks reconstructed error level findings_ | cluster.process |
| dev/fresh_operator_export_test.clj | 1 | 92 | 0/0/1 | 0/1/0 |  | _export verb produces an openable queryable store_ | cluster.process, cluster.registry, cluster.store, db, operator |
| dev/hook_test.clj | 2 | 101 | 0/0/2 | 0/2/0 |  | _one request returns terminal evidence and pause sends nothing_ | cluster.process, operator |
| dev/issues_test.clj | 1 | 9 | 0/0/1 | 1/0/0 |  | _issue cli reports absence and refusals_ | dev.issues |
| dev/mcp_bridge_test.clj | 26 | 725 | 0/0/26 | 0/25/1 |  | _bridge requires no application namespace_ | cluster, config |
| dev/prepl_retention_test.clj | 2 | 89 | 0/0/2 | 0/2/0 |  | A prepl session keeps no evaluation result once the result is returned, and answers every form exactly once ev |  |
| dev/publication_launch_test.clj | 1 | 11 | 0/0/1 | 0/1/0 |  | _launch loads the cluster without an inlined input inventory_ | operator |
| dev/publication_test.clj | 2 | 34 | 0/0/2 | 0/2/0 |  | _publication forwards output and returns terminal value_ | cluster.process, operator |
| dev/source_instrumentation_test.clj | 2 | 112 | 0/0/2 | 0/2/0 |  | _source publication restores contracts after early reload failure_ | cluster, cluster.process, config, db, env |
| directory_test.clj | 1 | 53 | 0/0/1 | 1/0/0 | H1×1 | _an empty directory observes later schema declarations_ | cluster.agent, config, db, sci.eval |
| edit/jvm_test.clj | 3 | 137 | 0/0/3 | 3/0/0 |  | _stale digest refuses before every transform_ | config, edit.jvm, fs |
| edit_test.clj | 9 | 464 | 0/0/9 | 9/0/0 | H1×3 | _form edit preserves every unselected byte_ | my.edit, config, db, edit, effect |
| effect_test.clj | 17 | 1032 | 0/0/17 | 17/0/0 | H1×16 H2×1 H4×1 H5×1 | _capability fixtures install complete program declarations_ | my.fs, config, db, effect, error |
| env_test.clj | 5 | 381 | 0/0/5 | 5/0/0 | H8×1 | The environment-isolation class regressions. | db, effect, env, flow, schema |
| error/branch_test.clj | 2 | 108 | 0/0/2 | 2/0/0 | H1×1 H2×1 | Errors are recorded on the branch where they happen and read back per branch by basis window (language-require | config, db, error, problems, schema |
| error/refusal_test.clj | 9 | 220 | 0/0/9 | 7/1/1 |  | Cause-chain readers preserve producer errors and ordinary exception data. | cluster.reply, config, db, error, error.refusal |
| error_evidence_test.clj | 2 | 89 | 2/0/0 | 2/0/0 | H1×2 | A capped fault keeps its WHOLE evidence in the blob tier: the stored `data-edn` is only a presentation, and th | blob, config, db, env, fault |
| error_recording_test.clj | 1 | 52 | 0/1/0 | 1/0/0 | H1×1 H4×1 | _root first turn records its producers declaration_ | cluster, db, turn |
| error_result_test.clj | 4 | 160 | 0/0/4 | 4/0/0 | H1×4 | _a canonical result declarations are installed_ | blob, config, db, error, id |
| error_test.clj | 48 | 1422 | 22/26/0 | 47/1/0 | H1×14 H6×1 | Error identity, normalization and recording behavior. | cluster.status, config, db, error, error.refusal |
| error_write_timing_test.clj | 4 | 182 | 0/0/4 | 3/0/1 | H1×3 H6×1 | _one error write has a measured preparation validation and commit_ | db, error, sci.eval, turn |
| eval/drive_test.clj | 2 | 64 | 1/1/0 | 2/0/0 | H1×2 | Regressions for the fact-space episode grader and its transcript. | db, eval.drive, schema |
| eval_test.clj | 1 | 53 | 0/0/1 | 1/0/0 | H1×1 | _history orders turn transactions before ordinals and keeps unfinished forms_ | db, eval |
| fault_no_history_test.clj | 1 | 56 | 0/0/1 | 1/0/0 | H1×1 | _fault occurrence churn drops superseded history_ | db, schema, schema.datahike |
| fault_test.clj | 4 | 197 | 2/2/0 | 4/0/0 | H1×4 H8×1 | One regression per class of the one core-fault route (`seon.fault/fault!`, AGENTS.md "The error policy"), each | blob, cluster.agent, cluster.branch, cluster.registry, cluster.store |
| flow_configuration_test.clj | 2 | 132 | 0/0/2 | 2/0/0 | H1×1 | _proc construction refuses the mixed scaling cliff_ | cluster, cluster.agent, flow |
| flow_launcher_error_join_test.clj | 1 | 53 | 0/0/1 | 1/0/0 | H1×1 | The work launcher's failed submissions reach the one core-fault route. | db, flow |
| flow_test.clj | 25 | 1767 | 21/3/1 | 25/0/0 | H1×5 H2×1 H6×1 H8×1 | _graph construction joins every declared tap before resume_ | config, db, fault, flow, schema |
| fn/analyzer_test.clj | 7 | 231 | 0/0/7 | 7/0/0 | H3×1 | _publication findings depend on declarations not inferred return types_ | fn.analyzer, schema |
| fn/caller_lint_test.clj | 1 | 18 | 0/0/1 | 1/0/0 |  | Caller files are relinted only when a callee's signature changes (B1 §2a step 9). | fn |
| fn/incremental_deletion_test.clj | 5 | 197 | 0/0/5 | 5/0/0 | H1×1 | _a deletion recomputes its unchanged callers edges_ | db, fn, id, program, schema |
| fn/publication_cache_test.clj | 1 | 115 | 0/0/1 | 1/0/0 | H2×1 H3×1 | _lint direct callers after committed declaration changes_ | db, fn |
| fn/publication_signature_test.clj | 2 | 24 | 0/0/2 | 2/0/0 |  | Declaration metadata follows Clojure's grammar and excludes executable bodies. | fn.signature |
| fn/publication_test.clj | 3 | 160 | 0/0/3 | 3/0/0 | H1×3 H6×1 | _literal constructor keys are not declared function attributes_ | db, fn, fn.analyzer, fn.schema-shape, schema |
| fn/publication_toolchain_test.clj | 1 | 30 | 0/0/1 | 1/0/0 |  | Declared test inputs include the analyzer configuration. | fn.analyzer |
| fn/schema_shape_test.clj | 7 | 191 | 0/0/7 | 7/0/0 | H1×3 | _canonical fixture shape strings stay small_ | call-preparation, db, fn.schema-shape, schema |
| fn/unresolved_test.clj | 1 | 31 | 0/0/1 | 1/0/0 | H1×1 | _unresolved calls require an indexed callee namespace_ | db, fn |
| fn_core_calls_test.clj | 1 | 29 | 0/0/1 | 1/0/0 | H1×1 | _core call census is non vacuous_ | db |
| fn_test.clj | 68 | 3015 | 26/41/1 | 68/0/0 | H1×36 H4×3 H6×6 H7×2 H8×1 | _analyze form refuses an unresolvable namespace reference_ | my.program, cluster, cluster.source, cluster.store, db |
| fs/jvm_test.clj | 11 | 423 | 0/0/11 | 11/0/0 | H3×1 H5×3 | _reads bytes without decoding or character counts_ | my.fs, blob, config, db, fs |
| fs_test.clj | 3 | 130 | 0/0/3 | 3/0/0 | H3×1 | Recurring proof for the one recursive filesystem deletion owner. | fs |
| gen/loop_test.clj | 3 | 516 | 0/0/3 | 3/0/0 | H3×2 H4×1 | Whole-turn execution over a real cluster, with only provider text supplied. | ai, cluster, cluster.agent, config, db |
| help_test.clj | 1 | 138 | 0/0/1 | 1/0/0 | H1×1 H6×1 | _help is first stored data and tracks its code version_ | bootstrap, cluster, cluster.agent, config, db |
| help_trial_test.clj | 2 | 111 | 0/1/1 | 2/0/0 | H1×2 | _preflight requires the generated opening and unpolluted fixture_ | cluster, cluster.agent, config, db, flow |
| html_views_test.clj | 11 | 209 | 0/0/11 | 11/0/0 | H1×11 | User-facing render pairs, on the canonical database with frozen AI bytes. | agent, bootstrap, cluster.agent, cluster.message, config |
| id_test.clj | 3 | 42 | 3/0/0 | 3/0/0 |  | _data shape and explicit length determine identity_ | id |
| incremental_publication_test.clj | 1 | 162 | 0/0/1 | 1/0/0 | H3×1 | Publication input ownership through the real source refresh boundary. | cluster, cluster.source, cluster.store, config, db |
| instrument_replaced_roots_test.clj | 1 | 25 | 0/0/1 | 0/1/0 |  | A Var's source recorded by path, relative or absolute, is its own compile. | instrument |
| instrument_test.clj | 55 | 1703 | 0/0/55 | 0/52/3 |  | Instrumentation: that it catches, that it is idempotent, and that a re-`defn` silently undoes it. | ai.tokens, cluster, config, db, dev.docstring |
| issue/detect_test.clj | 5 | 202 | 0/0/5 | 5/0/0 | H1×5 | The two first-task standards: a public function declares a contract, and a test reaches it. | db, fn, issue.detect |
| issue_deletion_test.clj | 2 | 92 | 0/0/2 | 2/0/0 | H1×2 | _removed notes retract entities and components through both writers_ | cluster.agent, db, issue |
| issue_generate_test.clj | 5 | 195 | 0/0/5 | 5/0/0 | H1×5 | _generate is idempotent per detector and subject_ | db, id, issue, issue.detect |
| issue_head_guard_test.clj | 1 | 51 | 0/0/1 | 0/1/0 |  | _stale issue adoption refuses at the writer_ | cluster.registry, cluster.source, cluster.store, db, issue |
| issue_settlement_test.clj | 2 | 342 | 0/2/0 | 2/0/0 | H1×2 | _issue settlement runs tests and derives completion_ | cluster, cluster.agent, cluster.wake, config, db |
| issue_test.clj | 11 | 502 | 0/0/11 | 11/0/0 | H1×10 H2×1 | _indexed issues replace facts and retract removed notes_ | cluster, cluster.agent, db, eval, flow |
| loop_proof_test.clj | 4 | 941 | 0/1/3 | 4/0/0 | H1×4 | _running fixture settles its seeded wake_ | ai, bootstrap, cluster, cluster.agent, cluster.wake |
| maintenance_schema_test.clj | 6 | 356 | 0/1/5 | 6/0/0 | H1×4 | Schema and initialization proofs for turn-free maintenance receipts. | db, maintenance, schedule, schema, schema.datahike |
| maintenance_test.clj | 9 | 473 | 0/0/9 | 9/0/0 | H1×5 | _collection results project branch maps to components_ | db, maintenance, schema, schema.datahike, schema.internal |
| mcp_test.clj | 1 | 125 | 0/0/1 | 1/0/0 | H1×1 H4×1 H6×1 | Total outward preparation through the real MCP PREPL. | cluster, config, db, env, id |
| namespace_agent_loop_test.clj | 1 | 104 | 0/0/1 | 1/0/0 | H1×1 | The first namespace-agent loop, slice 1: one issue worker started on its candidate branch C runs there, armed  | cluster, cluster.agent, cluster.wake, db, eval |
| no_provider_test.clj | 2 | 123 | 0/0/2 | 2/0/0 | H1×2 | _terminal provider refusal records one fault and defers reopening_ | ai, cluster.agent, config, db, turn |
| oversight_test.clj | 7 | 361 | 0/0/7 | 6/1/0 | H1×2 | The fleet story over real booted Flow graphs and a real root page. | bootstrap, cluster, cluster.agent, cluster.boot, db |
| owned_value_test.clj | 8 | 192 | 0/0/8 | 8/0/0 | H1×1 H6×1 | _owned values validate child only writes and before owners_ | db, schema, schema.datahike, schema.edn, sci.eval |
| plan_completion_test.clj | 1 | 71 | 0/0/1 | 1/0/0 | H1×1 | _query completion is decided by the writing database_ | db, plan |
| plan_test.clj | 1 | 45 | 0/0/1 | 1/0/0 | H1×1 | _retracting a prerequisite does not make its dependent ready_ | cluster.agent, db, plan |
| print_test.clj | 18 | 614 | 0/0/18 | 18/0/0 |  | Generative laws for the one admitted-value emitter. | ai.tokens, config, print, render, render.hiccup |
| problems_test.clj | 16 | 511 | 0/0/16 | 16/0/0 | H1×1 H6×1 | What `problems` says, and — harder and more important — what it does not say. | config, db, error, problems, render.hiccup |
| profile_test.clj | 5 | 175 | 0/0/5 | 0/5/0 |  | The armed wrapper's cumulative timing cells and their reads. | config, instrument, profile, schema |
| program_test.clj | 36 | 1510 | 8/28/0 | 36/0/0 | H1×10 H2×1 H3×1 H7×1 | Recurring proof for the one build/runtime declaration contract. | cluster, cluster.agent, db, eval, fn |
| public_contract_test.clj | 6 | 161 | 0/0/6 | 6/0/0 |  | The fresh source tree has no public function outside Malli collection. | cluster, cluster.store, flow, fn.analyzer, sci.admit |
| publication_digest_test.clj | 1 | 37 | 0/0/1 | 1/0/0 |  | _issue notes do not identify the program_ | cluster, cluster.source |
| publication_validation_test.clj | 2 | 39 | 0/0/2 | 2/0/0 | H1×2 H6×1 | _documentation writes do not check unrelated arities or renderers_ | db |
| read_evidence_test.clj | 3 | 137 | 0/0/3 | 3/0/0 | H1×1 | _another recipients message keeps junipers inbox current_ | config, db, sci.eval |
| receipt_write_carrier_test.clj | 1 | 88 | 0/0/1 | 1/0/0 | H1×1 | Regression for receipt provenance on agent-authored database writes. | config, db, env, sci.eval, turn |
| reconcile_retain_test.clj | 1 | 27 | 0/0/1 | 1/0/0 | H1×1 | Config reconciliation never retracts a config row another cluster references. | db |
| reconcile_test.clj | 9 | 302 | 0/0/9 | 9/0/0 | H4×1 H6×1 | Sealed acceptance draft for provenance-scoped exact reconciliation. | cluster, config, db, reconcile, schema.edn |
| refusal_grammar_test.clj | 3 | 101 | 0/0/3 | 3/0/0 | H1×1 | _reference expectations describe the schema not the failure category_ | config, db, error, instrument, schema |
| registry_isolation_test.clj | 1 | 101 | 0/0/1 | 0/1/0 |  | Two canonical cluster branches share host wrappers, never declarations. | config, db, env, instrument, schema |
| render/block_test.clj | 1 | 22 | 0/0/1 | 1/0/0 |  | The surviving stable render address and bounded structural floors. | render.block |
| render/call_test.clj | 2 | 59 | 0/0/2 | 2/0/0 | H1×2 | _declared producer refusals stop context rendering_ | config, db, render |
| render/context_nits_test.clj | 1 | 51 | 0/0/1 | 1/0/0 | H1×1 | _generated runtime and notes reads return useful values_ | config, db, note, render.transcript, sci.eval |
| render/data_test.clj | 5 | 144 | 0/0/5 | 5/0/0 | H1×2 | The shared routed-floor cursor: total parsing and `get-in` selection. | db, render.data, schema |
| render/entity_pairs_test.clj | 3 | 149 | 0/3/0 | 3/0/0 | H1×1 | _function entity pair is selected through the issue walk_ | config, db, render, render.hiccup, render.ns |
| render/episode_test.clj | 1 | 50 | 0/0/1 | 1/0/0 | H1×1 | _saved shown text is a settled evaluation without a print node_ | eval, render.walk, turn |
| render/faults_test.clj | 2 | 87 | 0/0/2 | 2/0/0 | H1×2 | _pulled fault concern uses the entity pair_ | cluster, cluster.agent, config, db, error |
| render/floor_test.clj | 3 | 88 | 0/0/3 | 3/0/0 |  | The floor shows any Throwable as bounded text, whatever its ex-data holds. | render.value |
| render/hiccup_test.clj | 19 | 272 | 0/0/19 | 19/0/0 |  | Sealed acceptance draft for the ONE hiccup serializer. | render.hiccup, schema |
| render/history_test.clj | 6 | 307 | 4/2/0 | 6/0/0 | H1×3 | Class regressions for self-generating, append-only REPL history. | config, db, render, render.ns, render.transcript |
| render/ns_test.clj | 9 | 546 | 0/0/9 | 9/0/0 | H1×7 | _identity reads stewardship without an agent cluster attribute_ | ai.tokens, cluster.agent, config, db, print |
| render/page_review_test.clj | 1 | 113 | 0/1/0 | 1/0/0 | H1×1 | _turn headers and all evaluations have one place each_ | db, eval, render.transcript |
| render/page_settings_test.clj | 2 | 160 | 0/0/2 | 2/0/0 | H1×2 | _settings without overrides use the declared pair_ | cluster.agent, config, db, plan, render |
| render/retained_test.clj | 2 | 198 | 0/0/2 | 2/0/0 | H2×1 H6×2 | _equal committed database skips read replay_ | config, db, render, sci.eval, sci.kernel |
| render/root_pull_test.clj | 14 | 465 | 5/9/0 | 13/0/1 | H1×13 H6×4 H7×1 | Class regressions for schema-derived root acquisition and membership. | config, db, render.walk, render.web, schema |
| render/route_test.clj | 3 | 66 | 0/0/3 | 3/0/0 |  | _every route name reverses and round trips_ | render.route |
| render/runtime_test.clj | 1 | 87 | 0/0/1 | 1/0/0 | H1×1 | The runtime HTML pair over the canonical database population. | blob, db, render.hiccup, render.transcript |
| render/transcript_run_test.clj | 1 | 100 | 0/0/1 | 1/0/0 | H1×1 | _render run selects only the requested run_ | config, db, render, render.transcript, render.value |
| render/transcript_test.clj | 20 | 1466 | 18/1/1 | 20/0/0 | H1×18 H2×1 | _absent bootstrap trigger does not pin another agents message_ | blob, bootstrap, cluster.agent, config, db |
| render/value_options_test.clj | 1 | 33 | 0/0/1 | 1/0/0 | H1×1 | Database-backed options at the routed structural-value boundary. | config, render.web |
| render/value_test.clj | 38 | 990 | 0/0/38 | 37/0/1 | H1×17 | The render floor is one adapter over the sealed print emitter. | ai.tokens, cluster.agent, config, db, error |
| render/walk_test.clj | 4 | 191 | 0/0/4 | 4/0/0 | H1×4 H3×1 H6×1 | Class regressions for the bounded neighbourhood traversal. | config, db, render.walk, schema, schema.edn |
| render/web_adoption_test.clj | 1 | 23 | 0/0/1 | 0/1/0 |  | A listening server follows a redefined handler without rebinding. | render.web |
| render/web_context_test.clj | 5 | 252 | 0/0/5 | 5/0/0 | H6×5 | Callers derive with shared evidence while the tab delta proc is paused. | cluster, config, db, render, render.walk |
| render/web_debug_test.clj | 17 | 1071 | 2/14/1 | 17/0/0 | H1×13 H6×2 | Schema-authored block metadata and honest unavailable dependencies. | cluster, cluster.agent, cluster.prompt, config, db |
| render/web_feed_test.clj | 1 | 26 | 0/0/1 | 1/0/0 | H5×1 | First paint is independent of the cluster render proc. |  |
| render/web_performance_test.clj | 1 | 57 | 0/0/1 | 1/0/0 | H1×1 H6×1 | _one pass derives one profile for every page unit_ | config, render, render.walk, render.web |
| render/web_test.clj | 61 | 2469 | 5/56/0 | 61/0/0 | H1×11 H3×2 H4×7 H6×7 H7×5 | The page on a real socket. | agent, ai.tokens, blob, cluster, cluster.agent |
| render_cache_test.clj | 1 | 99 | 0/0/1 | 1/0/0 |  | The render cache belongs to the branch a context derives in. | cluster.agent, db, env, render, render.web |
| render_coverage_test.clj | 7 | 579 | 0/0/7 | 7/0/0 | H1×7 H7×1 | Focused coverage for important root-runtime and effect receipt faces. | ai.tokens, cluster, cluster.agent, config, db |
| render_simplification_test.clj | 25 | 1088 | 10/15/0 | 25/0/0 | H1×24 H4×3 H6×3 H7×2 | Behavioral gates for ruling #50's minimal render model. | cluster, cluster.agent, config, db, fn |
| render_source_test.clj | 5 | 326 | 0/0/5 | 5/0/0 | H1×4 H4×2 H6×2 | _preview cache is memory only and invalidates on read changes_ | blob, cluster.agent, config, db, render |
| repl_grammar_test.clj | 4 | 125 | 3/1/0 | 4/0/0 | H1×1 | _shown value diffs are deterministic readable snapshot paths_ | bootstrap, cluster, cluster.agent, config, db |
| repl_test.clj | 16 | 317 | 16/0/0 | 16/0/0 |  | The REPL response grammar: what an agent reads back for one form. | repl, sci.admit |
| rereads_panel_test.clj | 1 | 75 | 0/0/1 | 1/0/0 |  | _problems count every silent refresh from history_ | cluster.agent, db, eval, render.transcript, repl |
| rereads_test.clj | 3 | 194 | 0/1/2 | 3/0/0 | H1×1 | _stale read with equal value refreshes evidence without emitting_ | cluster, cluster.agent, config, db, eval |
| reset_edge_parity_test.clj | 1 | 97 | 0/0/1 | 1/0/0 | H1×1 | Equal-population comparison of reference and indexed-symbol reverse walks. | db, schema, schema.datahike, schema.edn, sci.eval |
| reset_edges_test.clj | 16 | 375 | 1/14/1 | 15/1/0 | H1×15 | _named edges refuse deletion until the final callers are repaired_ | agent, cluster.agent, cluster.source, config, db |
| returned_error_test.clj | 1 | 62 | 0/0/1 | 1/0/0 | H1×1 | _returned refusal is a schema first error with unchanged history_ | config, db, repl, sci.eval |
| run4_install_test.clj | 1 | 96 | 0/0/1 | 1/0/0 | H1×1 | _refused run4 definition never enters the retained context_ | cluster, cluster.agent, config, db, eval |
| run4_reader_test.clj | 1 | 35 | 0/0/1 | 1/0/0 | H1×1 | _run4 reader errors name the reply and do not imply retained input_ | config, sci.eval |
| run6_db_test.clj | 2 | 76 | 0/0/2 | 2/0/0 | H1×2 | _query contract refuses run6 and count mismatches_ | db, schema |
| run6_stall_test.clj | 1 | 98 | 0/1/0 | 1/0/0 | H1×1 | _an open session provider refusal is the first problem_ | config, db, error, render, render.transcript |
| schedule_test.clj | 13 | 457 | 0/9/4 | 13/0/0 | H1×11 | _firing requires the callers cluster execution context_ | cluster, cluster.agent, cluster.wake, config, db |
| schema/admission_gate_test.clj | 10 | 287 | 10/0/0 | 10/0/0 |  | _open maps cannot regress in declarations or contract admission_ | schema, schema.admission, schema.datahike, schema.edn |
| schema/admission_test.clj | 6 | 177 | 0/0/6 | 6/0/0 |  | _natural name tokens remain admission behavior_ | cluster, cluster.source, cluster.store, db, program |
| schema/agent_nil_contract_test.clj | 1 | 85 | 1/0/0 | 1/0/0 |  | Agent-entered function contracts treat `:nil` as the exact singleton type Malli declares (`-nil-schema`, refer | note, schema |
| schema/datahike_test.clj | 14 | 621 | 0/1/13 | 14/0/0 | H1×9 H6×2 | _error members cannot carry another entitys upsert identity_ | db, env, schema, schema.datahike, schema.internal |
| schema/declaration_population_test.clj | 3 | 117 | 0/0/3 | 3/0/0 | H3×1 | The class regression for per-item declaration resolution. | config, print, schema, schema.edn |
| schema/edn_test.clj | 19 | 497 | 0/0/19 | 19/0/0 | H1×3 H3×3 | Sealed acceptance for the schema-EDN loader and the one admission gate (B2 wave). | config, db, schema, schema.edn, schema.internal |
| schema/predicate_metadata_refusal_test.clj | 1 | 66 | 0/0/1 | 1/0/0 | H1×1 | _named metadata predicates survive loaded refusal_ | config, error, fn.schema-shape, instrument, schema |
| schema/program_test.clj | 4 | 108 | 0/0/4 | 4/0/0 |  | _rows have one canonical persisted shape_ | program, schema, schema.edn |
| schema/projection_acquisition_test.clj | 5 | 143 | 0/0/5 | 5/0/0 | H1×5 H6×1 | _sorted declarations and indexed rows preserve registry identities_ | db, schema, schema.datahike |
| schema/projection_writer_test.clj | 8 | 260 | 0/0/8 | 8/0/0 | H1×7 H6×5 H7×1 | _ordered declarations use their database and abort together_ | cluster.agent, db, env, schema, turn |
| schema/registry_retention_test.clj | 1 | 36 | 0/0/1 | 1/0/0 |  | A projection's lifetime never extends the world of a callable armed under it. | config, instrument, schema |
| schema_audit_test.clj | 2 | 92 | 0/0/2 | 2/0/0 | H1×1 | Graph-derived permissive contract inventory. | db, schema, schema.datahike, schema.internal |
| schema_encoding_test.clj | 1 | 21 | 0/0/1 | 1/0/0 |  | _canonical encoding retains published bytes_ | schema |
| schema_in_place_test.clj | 9 | 274 | 9/0/0 | 9/0/0 |  | Every schema difference adopts in place on the branch's connection. | cluster, db, fn, program, schema |
| schema_redeclare_test.clj | 1 | 95 | 0/0/1 | 0/1/0 |  | _seeded schema families survive development adoption_ | cluster, cluster.boot, cluster.process, db, env |
| schema_reference_graph_test.clj | 2 | 87 | 0/0/2 | 2/0/0 | H1×2 | Database proofs for the canonical schema reference graph. | db, schema, schema.edn |
| schema_retirement_test.clj | 1 | 64 | 1/0/0 | 1/0/0 | H1×1 | _schema retirement keeps source write obligations_ | db, program |
| schema_test.clj | 46 | 1926 | 1/0/45 | 43/0/3 | H1×18 H6×3 | Regression proofs for the canonical schema registration boundary. | call-preparation, config, db, error.refusal, instrument |
| schema_usage_guard_test.clj | 12 | 564 | 1/11/0 | 12/0/0 |  | Schema replacement/removal safety at the terminal transaction boundary. | db, env, schema, schema.datahike, turn |
| sci/acquisition_attributes_test.clj | 1 | 28 | 0/0/1 | 1/0/0 |  | _colliding fingerprints keep each projections acquisition attributes_ | schema, sci.eval |
| sci/admit/declaration_population_test.clj | 3 | 127 | 0/0/3 | 3/0/0 |  | The class regression for per-node declaration resolution at the admission seam. | config, schema, schema.edn, sci.admit |
| sci/admit_test.clj | 14 | 539 | 0/0/14 | 14/0/0 | H1×1 | Sealed acceptance draft for value admission (N3's one new mechanism). | config, print, schema, sci.admit |
| sci/branch_execution_test.clj | 6 | 339 | 0/0/6 | 6/0/0 | H1×6 | _branch definitions and unchanged callers share one context_ | config, db, fn, id, schema |
| sci/documentation_test.clj | 9 | 307 | 1/8/0 | 8/1/0 | H1×8 | _a named private override documents the jvm boundary_ | config, db, program, schema, sci.eval |
| sci/entry_gate_test.clj | 5 | 110 | 0/0/5 | 5/0/0 | H1×5 | Entry into an agent's SCI context: every program namespace carries its reader context, a contracted definition | config, db, sci.eval |
| sci/eval_instrumentation_test.clj | 1 | 76 | 0/0/1 | 0/1/0 |  | Armed-instrumentation regression for database program acquisition. | ai, cluster, cluster.boot, db, instrument |
| sci/eval_test.clj | 74 | 2487 | 0/0/74 | 69/5/0 | H1×29 H2×2 H4×3 H6×4 | Acceptance for the guarded eval (N3, C7). | blob, call-preparation, cluster.agent, config, db |
| sci/fault_policy_test.clj | 1 | 33 | 0/0/1 | 1/0/0 | H1×1 | _an unconfigured cluster refuses arming instead of defaulting its fault policy_ | db, instrument, schema, sci.eval |
| sci/fork_isolation_test.clj | 1 | 30 | 0/0/1 | 1/0/0 | H1×1 | The isolation law agents rely on to work simultaneously. |  |
| sci/kernel_arm_carriage_test.clj | 6 | 233 | 0/0/6 | 6/0/0 |  | The arm travels with the work — one class regression. | schema, sci.eval, sci.kernel |
| sci/lazy_acquisition_test.clj | 2 | 72 | 0/0/2 | 2/0/0 | H1×2 | _cluster program is acquired on first use and reused for that database_ | config, db, sci.eval, sci.kernel |
| sci/reader_test.clj | 29 | 765 | 0/0/29 | 28/0/1 | H7×1 H8×1 | _fences are whitespace between reader forms_ | schema, sci.reader |
| sci/shown_text_test.clj | 1 | 62 | 0/0/1 | 1/0/0 | H1×1 | _a plan item result shows data instead of its block render source_ | cluster.agent, config, render, repl, sci.eval |
| sci/supplied_database_test.clj | 1 | 47 | 0/0/1 | 1/0/0 | H1×1 | _own record reads supply the same database and agent as explicit calls_ | cluster.agent, config, db, sci.eval |
| settlement_receipt_provenance_test.clj | 1 | 43 | 0/0/1 | 1/0/0 | H1×1 | _receipt created by the write is resolved before provenance_ | db, turn |
| shell/jvm_test.clj | 10 | 463 | 0/1/9 | 0/10/0 |  | _environment overrides use the supplied generation_ | my.shell, blob, config, db, effect |
| source_reconciliation_test.clj | 1 | 82 | 0/0/1 | 1/0/0 | H1×1 | _source reconciliation preserves identities and unrelated facts_ | cluster.source, db, fn |
| store_growth_test.clj | 1 | 66 | 0/0/1 | 1/0/0 | H1×1 | _one listened write produces one commit and no fault write_ | cluster.wake, db, fs |
| supplied_documentation_test.clj | 4 | 98 | 0/0/4 | 4/0/0 | H1×4 | _docs separate runtime keys and preserve callable examples_ | my.message, config, db, effect, env |
| test/accretion_test.clj | 10 | 429 | 0/0/10 | 10/0/0 | H1×5 | _refused install feedback is complete grouped and renderable_ | config, db, fn, schema, schema.edn |
| test/admission_digest_test.clj | 1 | 42 | 0/0/1 | 1/0/0 | H1×1 | _an admission confirms the tested digest without deriving the program_ | db |
| test/declared_reference_test.clj | 1 | 29 | 0/0/1 | 1/0/0 | H1×1 | _indexed declared references preserve rule results_ | db, fn |
| test/duration_test.clj | 2 | 67 | 0/0/2 | 2/0/0 |  | _duration bound requires both the allowance and its reason_ | id |
| test/fixture_timing_test.clj | 1 | 43 | 0/0/1 | 0/0/1 |  | The canonical fixture is a branch through the agent entrance, never a copy. | cluster.registry, db |
| test/gitlink_digest_test.clj | 1 | 18 | 0/0/1 | 1/0/0 |  | A gitlink input carries the declared file-digest shape into publication. | schema.edn |
| test/interrupted_request_test.clj | 3 | 120 | 2/0/1 | 3/0/0 | H1×3 | A request that throws leaves no member open, and open test evidence makes only its own problem family unknown. | db, problems |
| test/one_request_test.clj | 9 | 288 | 0/0/9 | 9/0/0 | H1×6 | A test is an isolated agent for one body: `seon.test/run` acquires each member's branch through the agent entr | cluster.registry, config, db, program, sci.eval |
| test/publication_test.clj | 1 | 55 | 0/0/1 | 1/0/0 |  | _publication preserves every admitted member_ | cluster.source, cluster.store, db, id |
| test/reach_test.clj | 3 | 67 | 0/0/3 | 3/0/0 | H1×3 H4×2 | _program digest uses the databases program shapes_ | db, program |
| test/runner_test.clj | 8 | 296 | 1/7/0 | 5/0/3 | H8×1 | _assertion report uses bounded value renderer_ | db, env, fn, id, program |
| test/selection_test.clj | 9 | 529 | 0/0/9 | 6/3/0 | H1×2 H2×1 H6×1 | The class regression for the default tier's selector. | config, db, fn, id, program |
| test_cache_test.clj | 1 | 26 | 0/0/1 | 1/0/0 |  | The cache shares declared inputs and worker sizing with publication. |  |
| test_failure_facts_test.clj | 17 | 533 | 0/0/17 | 17/0/0 | H1×16 | _one failing is becomes one failure entity_ | blob, db, id, problems, program |
| test_provenance_test.clj | 3 | 109 | 0/0/3 | 3/0/0 | H1×3 H4×1 | _runs are distinct immutable and linked atomically_ | db |
| test_reaching_test.clj | 16 | 481 | 0/0/16 | 15/0/1 | H1×12 | _reach digests follow only changed closures_ | config, db, fn, id, instrument |
| test_runner_failure_fixture.clj | 5 | 44 | 0/0/5 | 5/0/0 |  | Selected explicitly by runner tests; excluded from the bare gate by its declared `:seon.test/fixture` reason. |  |
| test_runner_test.clj | 12 | 387 | 11/1/0 | 10/1/1 | H1×4 H8×1 | Capture and latest-result facts owned by the in-process test runner. | cluster.agent, db, dev.dependency-digest, error, operator.runtime |
| test_support_test.clj | 12 | 358 | 0/0/12 | 11/1/0 | H1×5 H2×2 | _environment retains the database projection_ | cluster, config, db, instrument, schema |
| test_test.clj | 6 | 297 | 0/0/6 | 6/0/0 | H1×6 H4×1 | Run admission uses the canonical writer and preserves selected obligations. | config, db, id, program, schema |
| transact_feedback_test.clj | 8 | 202 | 0/0/8 | 8/0/0 | H1×8 H8×1 | _raw write maps select only their asserted required identity_ | cluster.agent, db, schema, sci.eval, turn |
| transaction_result_test.clj | 1 | 39 | 0/0/1 | 1/0/0 | H1×1 | _agent transactions return resolved changes and preserve refusals_ | config, db |
| turn_backstop_test.clj | 3 | 128 | 0/0/3 | 3/0/0 | H1×3 | _cancelled completion observer releases every existing waiter_ | ai, cluster, cluster.agent, schema, turn |
| turn_boot_test.clj | 2 | 107 | 0/0/2 | 2/0/0 | H1×2 | _listened datom delivers under armed contracts_ | cluster, cluster.agent, cluster.wake, db, error |
| turn_continue_test.clj | 9 | 233 | 0/0/9 | 9/0/0 |  | _read results arrive before done_ | ai, cluster, cluster.agent, config, db |
| turn_disposition_test.clj | 1 | 72 | 0/0/1 | 1/0/0 | H1×1 | A refused disposition settles as the form's own complete evaluation. | db, turn |
| turn_error_test.clj | 1 | 21 | 0/0/1 | 1/0/0 | H1×1 | _system turn preserves missing agent as a declared refusal_ | schema, turn |
| turn_loop_test.clj | 26 | 1699 | 0/0/26 | 26/0/0 | H1×9 H2×1 H3×2 H4×5 H5×1 H6×5 H7×1 H8×1 | Sealed acceptance draft for the run loop (N3, C9). | ai, blob, cluster.agent, cluster.message, cluster.prompt |
| turn_test.clj | 37 | 2417 | 3/0/34 | 37/0/0 | H1×19 H4×2 H7×2 | _attempt model identity survives descriptor retraction_ | cluster, cluster.agent, cluster.registry, config, db |
| turn_work_cost_test.clj | 1 | 40 | 0/0/1 | 1/0/0 | H4×1 | _generated state agrees with the writer_ | ai, db, turn |
| turn_work_test.clj | 13 | 804 | 0/0/13 | 13/0/0 |  | Acceptance tests for the agent's next-work derivation. | agent, db, error, schema, turn |
| web/jvm_test.clj | 7 | 489 | 0/0/7 | 7/0/0 | H3×2 | _oversized bodies spill byte exactly through the blob tier_ | my.web, blob, config, db, effect |

## Recorded state and cost of reading it

`seon.test.runner/latest-results` over all 2,162 symbols refused the whole batch with "Recorded run
coverage is unavailable." (189 ms). The per-symbol reads through `seon.test/recorded-result` took
19.9 s in total, about 9.2 ms each. The profile attached to that evaluation named concurrent turn
work by other lanes, so the over-one-second figure is not attributable to the reads alone. The batch
refusal is a defect candidate for the `seon.test.runner` owner: one unavailable member hides every
other member's evidence. No issue was filed, because this assignment writes no other file. Red
results were recorded between 16:54Z and 17:39Z on a branch that concurrent lanes were changing.
361 of the 489 reds carry no failure report text. Of the rest, 45 are fixture writes or setups refused at the
writer (one sampled: a missing required `:seon.agent/branch`). A red here is not attributed to its test.

## Limits

Verdicts are scripted from resolved call signals, one level of same-file helpers and the redef census,
plus a reading of every namespace docstring and test name. Bodies were read for delete candidates only;
one candidate (`canonical-analysis-rejects-obsolete-cache-authorities`) proved to be a real behaviour
test and was kept. Signals miss calls made through a helper in another test namespace, so some HARNESS
verdicts may hide a platform need. The "protects" column is derived text, not an assessment. HEAD moved
to 85a7214ed during the survey (+102/−9 lines in two test files, not recounted). No test was run; no
file other than this survey and the redef census was written.
