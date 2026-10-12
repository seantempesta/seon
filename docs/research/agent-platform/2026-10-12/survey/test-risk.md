---
title: "Test risk survey: static scan and stored evidence at HEAD 45bfab18e"
type: research
status: evidence
basis: "HEAD 45bfab18e. Stored run tmp/central-round-head/results.edn (round at 5a715c9b9, 550 results, partial). Default not queried."
method: "scratchpad/survey/test-risk.py (static scan of test/**/*.clj*, fixtures excluded, strings and comments stripped; the stored-result parse of results.edn; the timing-line and sleep scan, output scratchpad/survey/timing.json). The scratch directory is /private/tmp/claude-501/-Users-sean-src-seon/a5acff01-0859-40f4-bc50-1424bc5205cf/scratchpad/survey/."
created: 2026-10-12
---

# Test risk survey

Static reading and stored evidence only. No test was run and no JVM was started. Counts are for test/ as checked out at HEAD.

## For root

1. Stored reds (results.edn, 550 of 2,836 tests): 76 red or error in 41 files, 25 tagged `:seon/tasks`, 51 untagged. Largest: cluster.turn-test 12 (all tagged), cluster.host-test 9 (6 tagged), cluster.merge-test 4 (all tagged), call-preparation-test 3 (none), publication-delta, publication-inputs, rehearsal, store-transact 3 each.
2. Riskiest 10 namespaces: cluster.turn-test (12 reds, 37 flake candidates); cluster.host-test (9 reds, 4 long); db-test (114 deftests, 17 flake candidates, two 5 ms per-sample budgets); sci.eval-test (12 flake, 8 timing asserts, 12 global-mutation lines); instrument-test (22 flake, 19 global-mutation lines); fn-test (14 flake, 7 global-mutation lines); cluster.merge-test (4 reds, 6 long); flow-test (11 flake, 190-450 ms window); cluster.agent-test (8 flake, 5 long, 7 global-mutation lines); repl-differential-test (1 stored red at 1,529 ms vs 1,000 ms, 2 timing asserts).
3. Likely flakes (exact deftests): seon.turn-test/an-eval-request-on-an-unchanged-program-forks-nothing (`:fork` stage <= 0.5, units inferred ms); seon.repl-differential-test/promoting-a-closure-costs-its-walk-and-the-callers-called (stored red, 1,529 ms vs 1,000 ms); seon.config-test/config-loads-the-packaged-population-in-a-fresh-jvm (9.2-9.9 s vs 5 s, central-round); seon.cluster.db-flow-test/a-prove-step-admitted-by-its-successor-opens-a-model-turn (stored red, 21,084 ms vs 5,000 ms); seon.db-test/carried-queries-stay-within-the-five-millisecond-budget and seon.db-test/handed-family-query-stays-below-five-milliseconds (every sample < 5 ms); seon.sci.eval-test/no-agent-work-writes-a-program-context (warm lookup < 0.5 ms); seon.flow-test/stop-joins-the-graph-only-after-its-procs-exit (190 <= ms <= 450); seon.cluster.process-test/a-grandchild-ignoring-term-dies-when-its-root-exits-within-grace (500 <= ms <= 2500); seon.cluster.db-flow-test/a-received-message-is-written-on-the-recipients-branch-and-rendered-in-its-context (< 30 ms warm).
4. Timing asserts: 126 lines in 66 files. Tightest: turn-test fork stage <= 0.5; sci.eval-test warm lookup < 0.5 ms; message-test < 2 ms; merge-test one-attempts-copy <= 10 ms and three-way <= 5 ms; program-test < 5 ms; db-test 5 ms and 10 ms; db-flow-test < 30 ms. All are contention-sensitive unless the test is measured on a quiet JVM.
5. Sleeps and polls: 22 `Thread/sleep` calls in deftest bodies across 18 files (cache-test:37, subprocess-test:147 and :176, effect-test:399 and :506, operator-test:215 and :220, turn-test:2904 and :3018, maintenance-test:391, turn-continue-test:121-123 and :245). 85 body lines pair a wait, poll, timeout or deadline word with a literal of three or more digits.
6. Forbidden global mutation (AGENTS and skill): 231 lines of alter-var-root, with-redefs, intern, load-file, setProperty or set-validator! across about 40 files. Heaviest: turn-test 45, instrument-test 19, sci.eval-test 12, fn-test 7, agent-test 7. 154 deftests flagged.
7. Fixture cost: `with-published-file-database` (test/seon/test_support.clj, `with-published-file-database`) republishes the whole checkout on every call with no memo (used in web.jvm-test, background-blob-test and shell.jvm-test). `boot-test` runs 11 deftests through `with-published-root!`, one operator publish each. cohost-boot-test memoizes its publish with `:once`. `with-database` (test_support.clj) is a branch pointer per call (fixture_timing_test: first <= 500 ms, later p50 <= 100 ms).
8. Long bounds: 170 deftests carry `:seon.test/long-ms`; shell.jvm-test declares 600 s on five; web.jvm-test 90 s on its whole namespace.
9. Already filed: seon.config/the-packaged-population-loads-in-a-fresh-jvm-within-its-bound; seon.flow/cold-paused-graph-construction-keeps-its-cost-bound; seon.fn (AGENTS forbids with-redefs in fn-test); seon.test (about 300 with-redefs and alter-var-root sites, seon.test.edn); seon.test-census-test (forbidden-redefinition census); seon.instrument/instrumentation-is-jvm-global; seon.test/two-tests-never-share-a-test-world (the chain-world collision, may cover the db-flow prove-step red, unverified).
10. Not filed: the fork <= 0.5 bound, the db-test 5 ms budgets, the flow-test 190-450 ms window, the process-test windows, the sleeps above, the stored promoting-a-closure and prove-step reds. Round 3 says it filed seon.db/a-write-keeps-its-cost-after-the-projection-hand-off and seon.db/a-retained-branch-fixture-writes-the-digest-its-definition-produces; grep of resources/seon/tasks finds neither.

## Table

Per test file (test/ at HEAD). Columns: deftests (static); lines; long-bound (deftests with `:seon.test/long-ms` or `:seon.test/long` metadata); timing-assert lines (`is` lines comparing an elapsed, ms, bytes or profile value); sleep lines (Thread/sleep or sleep in the file, helpers included); reds (fail or error in stored results.edn; 0 means absent from the partial run, not green); of which tagged `:seon/tasks`; flake-candidate deftests (any of: timing bound with a numeric comparison, random or first-of-query ordering, forbidden global mutation, a wait or poll with a literal). Sorted by flake candidates, then reds, then timing lines, then path.

| File | deftests | lines | long-bound | timing-assert lines | sleep lines | reds (fail+error) | of which tagged | flake-candidate deftests |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `test/seon/cluster/turn_test.clj` | 56 | 3117 | 7 | 1 | 3 | 12 | 12 | 37 |
| `test/seon/instrument_test.clj` | 60 | 1898 | 0 | 1 | 1 | 0 | 0 | 22 |
| `test/seon/db_test.clj` | 114 | 3296 | 0 | 8 | 0 | 0 | 0 | 17 |
| `test/seon/fn_test.clj` | 85 | 3296 | 1 | 0 | 0 | 0 | 0 | 14 |
| `test/seon/sci/eval_test.clj` | 87 | 2917 | 1 | 8 | 0 | 0 | 0 | 12 |
| `test/seon/flow_test.clj` | 18 | 777 | 0 | 2 | 0 | 0 | 0 | 11 |
| `test/seon/cluster/agent_test.clj` | 27 | 2159 | 5 | 3 | 0 | 0 | 0 | 8 |
| `test/seon/fn/analyzer_test.clj` | 11 | 253 | 0 | 0 | 0 | 0 | 0 | 8 |
| `test/seon/operator_test.clj` | 19 | 402 | 0 | 0 | 4 | 0 | 0 | 8 |
| `test/seon/render/value_test.clj` | 62 | 1523 | 0 | 3 | 0 | 0 | 0 | 7 |
| `test/seon/schema_test.clj` | 55 | 2009 | 1 | 3 | 0 | 0 | 0 | 7 |
| `test/seon/test_runner_test.clj` | 14 | 468 | 0 | 2 | 0 | 0 | 0 | 7 |
| `test/seon/cluster/publication_inputs_test.clj` | 10 | 237 | 1 | 0 | 0 | 3 | 0 | 6 |
| `test/seon/error_test.clj` | 73 | 1891 | 0 | 3 | 0 | 0 | 0 | 6 |
| `test/seon/fs/jvm_test.clj` | 13 | 467 | 0 | 0 | 0 | 0 | 0 | 6 |
| `test/seon/ai_test.clj` | 50 | 1257 | 0 | 0 | 1 | 1 | 0 | 5 |
| `test/seon/effect_test.clj` | 19 | 1092 | 0 | 6 | 2 | 0 | 0 | 5 |
| `test/seon/merge_test.clj` | 18 | 549 | 0 | 4 | 0 | 0 | 0 | 5 |
| `test/seon/config_test.clj` | 33 | 916 | 0 | 1 | 0 | 0 | 0 | 5 |
| `test/seon/maintenance_test.clj` | 11 | 505 | 0 | 1 | 1 | 0 | 0 | 5 |
| `test/seon/render/web_test.clj` | 63 | 2370 | 0 | 1 | 0 | 0 | 0 | 5 |
| `test/seon/cluster/boot_test.clj` | 16 | 613 | 10 | 0 | 0 | 0 | 0 | 5 |
| `test/seon/cluster/program_change_test.clj` | 12 | 399 | 0 | 0 | 0 | 0 | 0 | 5 |
| `test/seon/cluster/subprocess_test.clj` | 7 | 212 | 0 | 0 | 2 | 0 | 0 | 5 |
| `test/seon/turn_loop_test.clj` | 13 | 1050 | 0 | 0 | 0 | 0 | 0 | 5 |
| `test/seon/cluster/store_test.clj` | 25 | 915 | 0 | 2 | 0 | 1 | 1 | 4 |
| `test/seon/await_test.clj` | 4 | 95 | 0 | 2 | 0 | 0 | 0 | 4 |
| `test/seon/cache_test.clj` | 11 | 187 | 0 | 2 | 1 | 0 | 0 | 4 |
| `test/seon/dev/edit_feedback_test.clj` | 15 | 737 | 0 | 1 | 0 | 0 | 0 | 4 |
| `test/seon/turn_test.clj` | 47 | 2113 | 1 | 1 | 0 | 0 | 0 | 4 |
| `test/seon/cluster/armed_test.clj` | 7 | 510 | 0 | 0 | 0 | 0 | 0 | 4 |
| `test/seon/owned_value_test.clj` | 8 | 210 | 0 | 0 | 0 | 0 | 0 | 4 |
| `test/seon/profile_test.clj` | 9 | 287 | 0 | 0 | 0 | 0 | 0 | 4 |
| `test/seon/render_simplification_test.clj` | 19 | 956 | 0 | 0 | 0 | 0 | 0 | 4 |
| `test/seon/test_reaching_test.clj` | 28 | 803 | 0 | 0 | 0 | 0 | 0 | 4 |
| `test/seon/call_preparation_test.clj` | 31 | 1247 | 0 | 0 | 0 | 3 | 0 | 3 |
| `test/seon/cluster/db_flow_test.clj` | 19 | 1062 | 0 | 2 | 1 | 1 | 0 | 3 |
| `test/seon/request_cost_test.clj` | 20 | 363 | 2 | 5 | 1 | 0 | 0 | 3 |
| `test/seon/cluster_test.clj` | 19 | 528 | 1 | 2 | 0 | 0 | 0 | 3 |
| `test/seon/blob_test.clj` | 9 | 291 | 0 | 1 | 0 | 0 | 0 | 3 |
| `test/seon/cluster/registry_test.clj` | 19 | 843 | 0 | 1 | 0 | 0 | 0 | 3 |
| `test/seon/dev/hook_test.clj` | 3 | 158 | 0 | 1 | 1 | 0 | 0 | 3 |
| `test/seon/fault_test.clj` | 8 | 338 | 0 | 1 | 0 | 0 | 0 | 3 |
| `test/seon/test_test.clj` | 15 | 523 | 2 | 1 | 0 | 0 | 0 | 3 |
| `test/seon/fs_test.clj` | 3 | 129 | 0 | 0 | 0 | 0 | 0 | 3 |
| `test/seon/maintenance_schema_test.clj` | 6 | 357 | 0 | 0 | 0 | 0 | 0 | 3 |
| `test/seon/render/web_debug_test.clj` | 9 | 347 | 0 | 0 | 0 | 0 | 0 | 3 |
| `test/seon/schema/edn_test.clj` | 20 | 490 | 0 | 0 | 0 | 0 | 0 | 3 |
| `test/seon/schema/projection_writer_test.clj` | 8 | 242 | 0 | 0 | 0 | 0 | 0 | 3 |
| `test/seon/sci/reader_test.clj` | 35 | 847 | 0 | 0 | 0 | 0 | 0 | 3 |
| `test/seon/test/one_request_test.clj` | 12 | 459 | 8 | 0 | 0 | 0 | 0 | 3 |
| `test/seon/turn_backstop_test.clj` | 3 | 117 | 0 | 0 | 0 | 0 | 0 | 3 |
| `test/seon/cluster/store_transact_test.clj` | 8 | 207 | 0 | 0 | 0 | 3 | 0 | 2 |
| `test/seon/cluster/fault_storage_test.clj` | 2 | 219 | 0 | 0 | 0 | 2 | 0 | 2 |
| `test/seon/cluster/evaluate_sources_test.clj` | 2 | 278 | 0 | 1 | 0 | 1 | 0 | 2 |
| `test/seon/eval_alloc_test.clj` | 3 | 82 | 0 | 3 | 0 | 0 | 0 | 2 |
| `test/my/root_test.clj` | 6 | 133 | 0 | 2 | 0 | 0 | 0 | 2 |
| `test/seon/cluster/process_test.clj` | 4 | 116 | 0 | 2 | 0 | 0 | 0 | 2 |
| `test/seon/render/floor_test.clj` | 8 | 162 | 0 | 2 | 0 | 0 | 0 | 2 |
| `test/seon/mcp_entrance_test.clj` | 20 | 436 | 2 | 1 | 0 | 0 | 0 | 2 |
| `test/seon/program_test.clj` | 43 | 1526 | 0 | 1 | 0 | 0 | 0 | 2 |
| `test/seon/shell/jvm_test.clj` | 12 | 518 | 9 | 1 | 0 | 0 | 0 | 2 |
| `test/seon/test/runner_test.clj` | 16 | 546 | 0 | 1 | 0 | 0 | 0 | 2 |
| `test/seon/test_failure_facts_test.clj` | 21 | 653 | 0 | 1 | 0 | 0 | 0 | 2 |
| `test/seon/test_provenance_test.clj` | 3 | 110 | 1 | 1 | 0 | 0 | 0 | 2 |
| `test/seon/web/jvm_test.clj` | 8 | 514 | 0 | 1 | 0 | 0 | 0 | 2 |
| `test/seon/cluster/publication_adoption_test.clj` | 2 | 84 | 2 | 0 | 0 | 0 | 0 | 2 |
| `test/seon/cluster/publication_serialize_test.clj` | 3 | 66 | 0 | 0 | 0 | 0 | 0 | 2 |
| `test/seon/cluster/release_context_test.clj` | 2 | 78 | 0 | 0 | 0 | 0 | 0 | 2 |
| `test/seon/env_test.clj` | 5 | 313 | 0 | 0 | 1 | 0 | 0 | 2 |
| `test/seon/health_test.clj` | 8 | 212 | 1 | 0 | 0 | 0 | 0 | 2 |
| `test/seon/help_test.clj` | 10 | 156 | 0 | 0 | 0 | 0 | 0 | 2 |
| `test/seon/no_provider_test.clj` | 2 | 125 | 0 | 0 | 0 | 0 | 0 | 2 |
| `test/seon/problems_test.clj` | 16 | 562 | 0 | 0 | 0 | 0 | 0 | 2 |
| `test/seon/render/web_context_test.clj` | 5 | 165 | 0 | 0 | 0 | 0 | 0 | 2 |
| `test/seon/render_source_test.clj` | 5 | 337 | 0 | 0 | 0 | 0 | 0 | 2 |
| `test/seon/sci/documentation_test.clj` | 17 | 421 | 0 | 0 | 0 | 0 | 0 | 2 |
| `test/seon/test_support_test.clj` | 15 | 445 | 0 | 0 | 0 | 0 | 0 | 2 |
| `test/seon/turn_continue_test.clj` | 10 | 689 | 3 | 0 | 2 | 0 | 0 | 2 |
| `test/seon/cluster/merge_test.clj` | 11 | 573 | 6 | 0 | 0 | 4 | 4 | 1 |
| `test/seon/adoption_contract_freshness_test.clj` | 1 | 143 | 0 | 0 | 0 | 1 | 0 | 1 |
| `test/seon/adoption_diagnostic_test.clj` | 2 | 62 | 0 | 0 | 0 | 1 | 0 | 1 |
| `test/seon/background_blob_test.clj` | 1 | 174 | 0 | 0 | 0 | 1 | 0 | 1 |
| `test/seon/bounded_boundary_census_test.clj` | 4 | 335 | 0 | 0 | 0 | 1 | 0 | 1 |
| `test/seon/cluster/acquisition_test.clj` | 1 | 161 | 1 | 0 | 0 | 1 | 0 | 1 |
| `test/seon/cluster/program_restart_test.clj` | 1 | 360 | 0 | 0 | 0 | 1 | 0 | 1 |
| `test/seon/cluster/publication_findings_test.clj` | 1 | 35 | 0 | 0 | 0 | 1 | 0 | 1 |
| `test/seon/cluster/publication_host_test.clj` | 1 | 137 | 1 | 0 | 0 | 1 | 0 | 1 |
| `test/seon/cluster/uncaught_handler_test.clj` | 1 | 38 | 0 | 0 | 0 | 1 | 0 | 1 |
| `test/seon/cluster/wake_test.clj` | 17 | 754 | 0 | 0 | 0 | 1 | 0 | 1 |
| `test/seon/sci/admit_test.clj` | 17 | 596 | 0 | 5 | 0 | 0 | 0 | 1 |
| `test/seon/datahike_fork_test.clj` | 5 | 170 | 0 | 2 | 0 | 0 | 0 | 1 |
| `test/seon/repl_differential_test.clj` | 11 | 288 | 2 | 2 | 0 | 0 | 0 | 1 |
| `test/seon/sci/kernel_test.clj` | 11 | 253 | 0 | 2 | 0 | 0 | 0 | 1 |
| `test/seon/test/fixture_timing_test.clj` | 1 | 44 | 0 | 2 | 0 | 0 | 0 | 1 |
| `test/seon/test/reach_test.clj` | 5 | 123 | 0 | 2 | 0 | 0 | 0 | 1 |
| `test/seon/cluster/message_test.clj` | 22 | 941 | 0 | 1 | 0 | 0 | 0 | 1 |
| `test/seon/error_evidence_test.clj` | 2 | 86 | 0 | 1 | 0 | 0 | 0 | 1 |
| `test/seon/error_result_test.clj` | 8 | 189 | 0 | 1 | 0 | 0 | 0 | 1 |
| `test/seon/schema/datahike_test.clj` | 22 | 681 | 2 | 1 | 0 | 0 | 0 | 1 |
| `test/seon/sci/kernel_arm_carriage_test.clj` | 5 | 209 | 0 | 1 | 1 | 0 | 0 | 1 |
| `test/seon/test/declared_reference_test.clj` | 1 | 32 | 1 | 1 | 0 | 0 | 0 | 1 |
| `test/seon/test/duration_test.clj` | 2 | 68 | 0 | 1 | 0 | 0 | 0 | 1 |
| `test/seon/test/scoped_first_run_test.clj` | 1 | 51 | 0 | 1 | 0 | 0 | 0 | 1 |
| `test/seon/test_cost_test.clj` | 3 | 69 | 0 | 1 | 0 | 0 | 0 | 1 |
| `test/my/agent_test.clj` | 6 | 172 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/my/program_read_test.clj` | 7 | 94 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/ai_stream_fold_test.clj` | 19 | 432 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/blob_publication_test.clj` | 1 | 196 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/cluster/adoption_record_test.clj` | 1 | 51 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/cluster/retired_attributes_test.clj` | 1 | 37 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/cluster/source_database_test.clj` | 1 | 47 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/cluster/source_test.clj` | 8 | 414 | 2 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/cluster/status_test.clj` | 3 | 101 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/cluster/web_binding_test.clj` | 1 | 67 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/cluster/write_back_test.clj` | 9 | 533 | 8 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/competency.clj` | 1 | 338 | 0 | 0 | 1 | 0 | 0 | 1 |
| `test/seon/competency_grade_test.clj` | 2 | 152 | 2 | 0 | 1 | 0 | 0 | 1 |
| `test/seon/concurrency_independence_test.clj` | 2 | 617 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/concurrency_test.clj` | 2 | 248 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/context_cookbook_trial_test.clj` | 1 | 26 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/context_test.clj` | 17 | 374 | 1 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/dev/prepl_retention_test.clj` | 2 | 90 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/dev/publication_launch_test.clj` | 3 | 31 | 0 | 0 | 2 | 0 | 0 | 1 |
| `test/seon/dev/publication_test.clj` | 1 | 21 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/dev/source_instrumentation_test.clj` | 2 | 112 | 2 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/error/attribution_test.clj` | 4 | 184 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/error_write_timing_test.clj` | 2 | 35 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/eval/drive_test.clj` | 2 | 69 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/flow_launcher_error_join_test.clj` | 1 | 54 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/fn/publication_cache_test.clj` | 1 | 118 | 1 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/fn/publication_test.clj` | 3 | 140 | 2 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/fn/publication_toolchain_test.clj` | 1 | 31 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/gen/loop_test.clj` | 1 | 326 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/id_test.clj` | 5 | 106 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/instrument_replaced_roots_test.clj` | 1 | 26 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/loop_proof_test.clj` | 2 | 244 | 1 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/printer_elision_test.clj` | 7 | 154 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/publication_digest_test.clj` | 1 | 38 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/render/faults_test.clj` | 1 | 49 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/render/history_test.clj` | 3 | 188 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/render/transcript_test.clj` | 17 | 1275 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/render/walk_graph_test.clj` | 1 | 99 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/render/web_adoption_test.clj` | 1 | 24 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/render/web_feed_test.clj` | 1 | 27 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/render/web_performance_test.clj` | 1 | 57 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/run4_install_test.clj` | 1 | 98 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/schedule_test.clj` | 13 | 471 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/schema/admission_gate_test.clj` | 10 | 285 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/schema/admission_test.clj` | 6 | 178 | 1 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/schema/projection_acquisition_test.clj` | 5 | 144 | 2 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/schema_in_place_test.clj` | 11 | 341 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/schema_redeclare_test.clj` | 1 | 94 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/sci/eval_instrumentation_test.clj` | 1 | 75 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/sci/program_change_test.clj` | 1 | 69 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/source_census_test.clj` | 4 | 82 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/test/interpreted_fixture_test.clj` | 2 | 59 | 1 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/test/seed_preparation_test.clj` | 5 | 84 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/test/selection_test.clj` | 11 | 585 | 2 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/transact_feedback_test.clj` | 9 | 218 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/turn_boot_test.clj` | 1 | 40 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/turn_work_cost_test.clj` | 1 | 41 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/turn_work_test.clj` | 14 | 839 | 0 | 0 | 0 | 0 | 0 | 1 |
| `test/seon/cluster/host_test.clj` | 25 | 779 | 4 | 0 | 0 | 9 | 6 | 0 |
| `test/seon/cluster/publication_delta_test.clj` | 5 | 195 | 3 | 0 | 0 | 3 | 0 | 0 |
| `test/seon/cluster/rehearsal_test.clj` | 3 | 63 | 0 | 0 | 0 | 3 | 0 | 0 |
| `test/seon/classification_test.clj` | 4 | 96 | 0 | 0 | 0 | 2 | 0 | 0 |
| `test/seon/cluster/evaluate_source_test.clj` | 7 | 367 | 1 | 0 | 0 | 2 | 0 | 0 |
| `test/my/edit_test.clj` | 2 | 46 | 0 | 0 | 0 | 1 | 0 | 0 |
| `test/my/fs_test.clj` | 2 | 52 | 0 | 0 | 0 | 1 | 0 | 0 |
| `test/my/test_check_test.clj` | 7 | 154 | 0 | 0 | 0 | 1 | 1 | 0 |
| `test/my/test_test.clj` | 1 | 79 | 1 | 0 | 0 | 1 | 0 | 0 |
| `test/seon/agent_call_edges_test.clj` | 1 | 115 | 0 | 0 | 0 | 1 | 0 | 0 |
| `test/seon/analysis_identity_test.clj` | 2 | 61 | 0 | 0 | 0 | 1 | 0 | 0 |
| `test/seon/armed_calls_test.clj` | 3 | 52 | 0 | 0 | 1 | 1 | 0 | 0 |
| `test/seon/await_owner_census_test.clj` | 1 | 46 | 0 | 0 | 0 | 1 | 0 | 0 |
| `test/seon/census_test.clj` | 1 | 14 | 0 | 0 | 0 | 1 | 1 | 0 |
| `test/seon/cluster/agent_arming_test.clj` | 1 | 132 | 0 | 0 | 0 | 1 | 0 | 0 |
| `test/seon/cluster/entrance_supplier_test.clj` | 1 | 90 | 0 | 0 | 0 | 1 | 0 | 0 |
| `test/seon/cluster/fault_message_test.clj` | 3 | 129 | 0 | 0 | 0 | 1 | 0 | 0 |
| `test/seon/cluster/instruction_test.clj` | 4 | 173 | 0 | 0 | 0 | 1 | 0 | 0 |
| `test/seon/cluster/lazy_agents_test.clj` | 1 | 35 | 1 | 0 | 0 | 1 | 0 | 0 |
| `test/seon/cluster/publication_concurrency_test.clj` | 1 | 33 | 1 | 0 | 0 | 1 | 0 | 0 |
| `test/seon/cluster/reload_per_declaration_test.clj` | 9 | 172 | 7 | 0 | 0 | 1 | 0 | 0 |
| `test/seon/sci/acquisition_cost_test.clj` | 8 | 186 | 0 | 5 | 0 | 0 | 0 | 0 |
| `test/seon/task_ingestion_test.clj` | 15 | 324 | 0 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/cache_calibration_test.clj` | 3 | 86 | 0 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/context_cost_test.clj` | 1 | 34 | 0 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/dev/heap_options_test.clj` | 2 | 19 | 0 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/repl_differential_p11_test.clj` | 1 | 33 | 0 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/repl_test.clj` | 25 | 511 | 0 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/schema/register_cost_test.clj` | 1 | 27 | 0 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/store_growth_test.clj` | 1 | 66 | 0 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/task_test.clj` | 7 | 256 | 0 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/test/request_acquisition_test.clj` | 4 | 119 | 0 | 1 | 0 | 0 | 0 | 0 |
| `test/my/background_test.clj` | 3 | 98 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/my/message_test.clj` | 11 | 279 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/my/program_mutation_test.clj` | 4 | 134 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/my/program_query_test.clj` | 2 | 65 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/my/program_test.clj` | 16 | 410 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/my/task_test.clj` | 6 | 65 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/my/turn_test.clj` | 6 | 101 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/my/web_test.clj` | 2 | 26 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/resources/publication-program/seon/id_test.clj.txt` | 2 | 30 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/adoption_entities_test.clj` | 1 | 27 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/adoption_margin_test.clj` | 1 | 20 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/agent_situation_test.clj` | 2 | 76 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/ai/tokens_test.clj` | 1 | 14 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/background_test.clj` | 1 | 53 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/batch_test.clj` | 1 | 148 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/blob_error_test.clj` | 2 | 38 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/blob_threshold_test.clj` | 2 | 34 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/boot_process_attribute_test.clj` | 1 | 39 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/branch_schema_check_test.clj` | 3 | 55 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/branch_schema_test.clj` | 1 | 46 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/central_round_test.clj` | 3 | 42 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/agent_custody_test.clj` | 2 | 127 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/agent_identity_test.clj` | 3 | 147 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/agent_namespace_test.clj` | 5 | 160 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/boot_reply_test.clj` | 2 | 48 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/boot_timeline_test.clj` | 1 | 25 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/branch_evidence_test.clj` | 1 | 125 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/branch_release_retention_test.clj` | 1 | 59 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/cohost_boot_test.clj` | 1 | 133 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/def_reload_test.clj` | 1 | 31 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/derivation_keys_test.clj` | 2 | 40 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/listener_failure_test.clj` | 1 | 103 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/mcp_test.clj` | 1 | 51 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/message_assignment_test.clj` | 4 | 142 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/problem_routing_test.clj` | 1 | 124 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/program_classes_test.clj` | 1 | 63 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/publication_declared_schema_test.clj` | 1 | 37 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/publication_lock_test.clj` | 7 | 273 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/publication_report_test.clj` | 1 | 36 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/publication_reuse_test.clj` | 1 | 88 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/publication_test.clj` | 1 | 27 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/resume_artifact_routing_test.clj` | 1 | 78 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/root_seed_digest_test.clj` | 2 | 66 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/source_nochange_test.clj` | 1 | 29 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/competency_oracle_test.clj` | 6 | 80 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/competency_test.clj` | 5 | 52 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/concurrency_streams_test.clj` | 2 | 157 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/config_application_test.clj` | 4 | 252 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/config_defaults_test.clj` | 1 | 12 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/config_functions_test.clj` | 1 | 33 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/contract_coverage_test.clj` | 11 | 48 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/contracts_compile_test.clj` | 8 | 1121 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/contracts_install_test.clj` | 1 | 51 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/contracts_test.clj` | 4 | 29 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/custody_stability_test.clj` | 5 | 335 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/data_shapes_test.clj` | 10 | 419 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/db/declaration_population_test.clj` | 1 | 147 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/db/schema_retirement_test.clj` | 1 | 32 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/db_error_test.clj` | 2 | 50 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/db_immutability_test.clj` | 1 | 28 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/db_overhead_test.clj` | 5 | 140 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/db_read_your_writes_test.clj` | 1 | 22 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/db_transact_shape_test.clj` | 1 | 58 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/dev/dependency_cache_test.clj` | 5 | 358 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/dev/docstring_test.clj` | 13 | 216 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/dev/fresh_operator_export_test.clj` | 1 | 89 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/dev/mcp_bridge_test.clj` | 20 | 274 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/directory_test.clj` | 1 | 52 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/edit/jvm_test.clj` | 3 | 138 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/edit_test.clj` | 9 | 464 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/error/branch_test.clj` | 2 | 119 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/error/declared_identity_test.clj` | 1 | 32 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/error/refusal_test.clj` | 20 | 512 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/error/resolution_test.clj` | 1 | 69 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/error_storage_bytes_test.clj` | 1 | 51 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/eval_test.clj` | 1 | 45 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/fault_no_history_test.clj` | 1 | 57 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/flow_configuration_test.clj` | 2 | 112 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/flow_world_test.clj` | 1 | 45 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/fn/caller_lint_test.clj` | 1 | 24 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/fn/incremental_deletion_test.clj` | 5 | 198 | 4 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/fn/publication_signature_test.clj` | 2 | 25 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/fn/seed_admission_test.clj` | 4 | 131 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/fn/unresolved_test.clj` | 1 | 32 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/fn_core_calls_test.clj` | 1 | 30 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/html_views_test.clj` | 9 | 178 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/incremental_publication_test.clj` | 1 | 154 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/index_reconcile_test.clj` | 1 | 32 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/interpreted_census_test.clj` | 2 | 17 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/lane_test.clj` | 9 | 137 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/node_cache_test.clj` | 1 | 45 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/pass_cost_test.clj` | 3 | 59 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/pass_probe_test.clj` | 1 | 37 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/pass_waste_test.clj` | 1 | 24 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/print_census_test.clj` | 1 | 30 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/print_test.clj` | 15 | 506 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/program_replacement_test.clj` | 2 | 50 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/public_contract_test.clj` | 6 | 162 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/publication_validation_test.clj` | 2 | 60 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/read_evidence_test.clj` | 8 | 236 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/receipt_set_attributes_test.clj` | 1 | 48 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/receipt_write_carrier_test.clj` | 1 | 84 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/reconcile_retain_test.clj` | 1 | 28 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/reconcile_test.clj` | 8 | 263 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/ref_test.clj` | 18 | 169 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/refusal_grammar_test.clj` | 4 | 134 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/registry_isolation_test.clj` | 1 | 118 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render/block_test.clj` | 1 | 23 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render/call_test.clj` | 2 | 47 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render/context_nits_test.clj` | 1 | 26 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render/data_test.clj` | 5 | 145 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render/entity_pairs_test.clj` | 8 | 295 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render/hiccup_test.clj` | 19 | 272 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render/ns_test.clj` | 9 | 260 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render/page_review_test.clj` | 1 | 115 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render/page_settings_test.clj` | 2 | 82 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render/related_test.clj` | 8 | 130 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render/retained_test.clj` | 1 | 134 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render/root_pull_test.clj` | 11 | 367 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render/route_test.clj` | 3 | 65 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render/runtime_test.clj` | 1 | 89 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render/transcript_run_test.clj` | 1 | 98 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render/value_law_test.clj` | 2 | 84 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render/value_options_test.clj` | 1 | 34 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render/walk_test.clj` | 4 | 180 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render_cache_test.clj` | 2 | 116 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render_coverage_test.clj` | 6 | 528 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render_test_summary_test.clj` | 2 | 33 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/repl_differential_eval_test.clj` | 1 | 37 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/repl_differential_p1_test.clj` | 3 | 44 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/repl_differential_p2_test.clj` | 1 | 23 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/repl_differential_p3_test.clj` | 1 | 21 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/repl_differential_p4_test.clj` | 2 | 28 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/repl_differential_p5_test.clj` | 2 | 43 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/repl_differential_p6_test.clj` | 1 | 23 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/repl_differential_p7_test.clj` | 1 | 31 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/repl_differential_reader_test.clj` | 4 | 89 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/repl_grammar_test.clj` | 3 | 50 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/reset_edge_parity_test.clj` | 1 | 98 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/reset_edges_test.clj` | 14 | 329 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/returned_error_test.clj` | 1 | 58 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/run4_reader_test.clj` | 1 | 24 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/run6_db_test.clj` | 2 | 74 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/run6_stall_test.clj` | 1 | 89 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/run_test.clj` | 16 | 271 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/schema/agent_nil_contract_test.clj` | 1 | 80 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/schema/declaration_population_test.clj` | 3 | 108 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/schema/predicate_metadata_refusal_test.clj` | 2 | 75 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/schema/program_test.clj` | 4 | 106 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/schema/registry_retention_test.clj` | 2 | 47 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/schema/vector_storage_test.clj` | 14 | 699 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/schema_audit_test.clj` | 3 | 105 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/schema_reference_graph_test.clj` | 2 | 88 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/schema_retirement_test.clj` | 1 | 63 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/schema_usage_guard_test.clj` | 12 | 565 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/sci/admit/declaration_population_test.clj` | 3 | 118 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/sci/branch_execution_test.clj` | 15 | 717 | 13 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/sci/context_reads_test.clj` | 3 | 80 | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/sci/entry_gate_test.clj` | 2 | 51 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/sci/fault_policy_test.clj` | 1 | 34 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/sci/forgive_test.clj` | 14 | 257 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/sci/fork_isolation_test.clj` | 1 | 31 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/sci/lazy_acquisition_test.clj` | 2 | 72 | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/sci/lazy_member_test.clj` | 1 | 48 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/sci/library_aliases_test.clj` | 7 | 160 | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/sci/program_change_report_test.clj` | 1 | 40 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/sci/render_source_test.clj` | 3 | 135 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/sci/result_handle_test.clj` | 1 | 54 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/sci/shown_text_test.clj` | 1 | 44 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/sci/supplied_database_test.clj` | 1 | 43 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/sentinel_test.clj` | 1 | 25 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/settlement_receipt_provenance_test.clj` | 2 | 79 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/source_reconciliation_test.clj` | 1 | 73 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/start_flow_benchmark_test.clj` | 1 | 73 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/state_census_test.clj` | 1 | 24 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/supplied_documentation_test.clj` | 5 | 130 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test/accretion_test.clj` | 6 | 131 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test/admission_digest_test.clj` | 1 | 45 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test/clean_world_test.clj` | 1 | 68 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test/effect_attempt_test.clj` | 1 | 49 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test/evaluator_evidence_test.clj` | 1 | 29 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test/first_load_arming_test.clj` | 3 | 65 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test/gitlink_digest_test.clj` | 1 | 19 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test/interrupted_request_test.clj` | 5 | 219 | 5 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test/overlay_world_test.clj` | 4 | 131 | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test/platform_test.clj` | 2 | 59 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test/publication_test.clj` | 1 | 56 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test/redefined_callee_test.clj` | 3 | 84 | 3 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test_cache_test.clj` | 1 | 27 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test_census_test.clj` | 1 | 17 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test_resolution_fixture.clj` | 1 | 8 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test_resolution_test.clj` | 1 | 30 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test_runner_failure_fixture.clj` | 5 | 45 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/transaction_provenance_test.clj` | 2 | 36 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/transaction_report_test.clj` | 1 | 27 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/transaction_result_test.clj` | 1 | 42 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/turn/batch_test.clj` | 7 | 231 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/turn_disposition_test.clj` | 1 | 71 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/uncontracted_warning_test.clj` | 1 | 27 | 0 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/value_zoo_test.clj` | 1 | 95 | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/vocabulary_test.clj` | 1 | 8 | 0 | 0 | 0 | 0 | 0 | 0 |

## Notes

- Stored reds are partial. results.edn holds 550 results (94 namespaces); the other test files show 0 reds, which means not run in that stored round. The file records the round at 5a715c9b9 (head.txt). Commit 5a715c9b9 is an ancestor of HEAD 45bfab18e.
- results-bad-restart.edn in the same directory has 2,893 results, 2,604 of them errors after a refused restart. It is not used.
- central-round.md (round 3, 2319152dc) lists per-test status only for the 125 round-1 regressions; all 125 are green there. Its list of about 340 reds and errors at c53c9306e is not stored per test, so it is not in the table. red-triage.md reads 20 latest stored runs on main's database; I did not cross-check them against results.edn.
- Static counts: 2,849 deftests (central-round counted 2,836 in a stored round). Timing-assert lines are a line heuristic; a multi-line assertion whose comparison sits on another line is not counted.
- Flake categories are heuristics. "order" (208 deftests) mostly matches random-uuid or gensym names that are unique by construction, so it is low risk. "wait" (99) includes words like wait or timeout that do not poll. "global" (154) matches the forbidden forms directly; it is a rule violation, not yet a proven flake. "bound" (51) needs a timing or byte token and a numeric comparison.
- Tagged counts use a header regex that reads metadata up to the deftest name. Treated as reliable for the 76 stored reds; unverified beyond that.
- Cost evidence for fixtures is the test authors' own statements (38-55 s per publish in a throwaway JVM, metadata of web.jvm-test). Not re-measured here.
- The stored reds (repl-differential promoting-a-closure, db-flow prove-step, config packaged load) are timing or cost reds. Whether they are flakes or regressions needs a fresh-JVM run; that is not done here.
- Unverified: the units of the `:fork` stage in turn-test (inferred ms, from the `:seon.cost/stages` name).
