---
type: report
status: measured
created: 2026-09-21
tags: [agent-platform, boot, operator]
---

# B1b per-file source inventory

Generated from paths in commits `26143d5f2`, `076827cc9`, `f49187619` plus the final
owned source/test edits. Counts are actual file lines against launch `995155f1e`;
zero means absent. Documentation/instructions are excluded from source-size totals.
The implementation also updated the landing/evidence documents, existing issue
notes, and narrow instruction examples; the orchestrator owns final instructions.

| File | Before | After | Delta |
|---|---:|---:|---:|
| `bin/seon` | 26 | 26 | +0 |
| `bin/seon-hook` | 1987 | 1987 | +0 |
| `bin/test-check` | 53 | 53 | +0 |
| `config/default.edn` | 639 | 646 | +7 |
| `resources/seon/schemas/seon.config.operator.edn` | 2 | 4 | +2 |
| `resources/seon/schemas/seon.effect.edn` | 483 | 534 | +51 |
| `resources/seon/schemas/seon.maintenance.result.edn` | 249 | 88 | -161 |
| `resources/seon/schemas/seon.operator.claim.edn` | 58 | 0 | -58 |
| `resources/seon/schemas/seon.operator.edn` | 151 | 236 | +85 |
| `resources/seon/schemas/seon.operator.lock.edn` | 6 | 0 | -6 |
| `resources/seon/schemas/seon.operator.process-census.edn` | 70 | 0 | -70 |
| `resources/seon/schemas/seon.operator.process-record.edn` | 22 | 0 | -22 |
| `resources/seon/schemas/seon.operator.reap.edn` | 67 | 0 | -67 |
| `resources/seon/schemas/seon.operator.state.edn` | 41 | 37 | -4 |
| `script/seon/dev/changed_test.clj` | 379 | 379 | +0 |
| `script/seon/dev/clj_kondo.clj` | 96 | 96 | +0 |
| `script/seon/dev/issues.clj` | 41 | 41 | +0 |
| `script/seon/dev/markdown.clj` | 1266 | 1266 | +0 |
| `script/seon/dev/markdown_test.clj` | 591 | 591 | +0 |
| `script/seon/dev/mcp.clj` | 984 | 861 | -123 |
| `script/seon/dev/state.clj` | 37 | 72 | +35 |
| `script/seon/fresh_operator.clj` | 3727 | 0 | -3727 |
| `script/seon/operator.clj` | 0 | 387 | +387 |
| `src/seon/artifact.clj` | 107 | 108 | +1 |
| `src/seon/bootstrap_drive.clj` | 490 | 491 | +1 |
| `src/seon/cluster.clj` | 3593 | 3129 | -464 |
| `src/seon/cluster/boot.clj` | 0 | 447 | +447 |
| `src/seon/cluster/export.clj` | 342 | 342 | +0 |
| `src/seon/cluster/process.clj` | 63 | 315 | +252 |
| `src/seon/cluster/store.clj` | 577 | 592 | +15 |
| `src/seon/db.clj` | 4638 | 4638 | +0 |
| `src/seon/eval/drive.clj` | 449 | 450 | +1 |
| `src/seon/fs.clj` | 356 | 409 | +53 |
| `src/seon/maintenance.clj` | 560 | 1016 | +456 |
| `src/seon/operator.clj` | 1219 | 0 | -1219 |
| `src/seon/operator/state.clj` | 1627 | 0 | -1627 |
| `src/seon/schedule.clj` | 879 | 869 | -10 |
| `src/seon/test/cache.clj` | 572 | 572 | +0 |
| `src/seon/test/runner.clj` | 4985 | 4985 | +0 |
| `test/seon/adoption_contract_freshness_test.clj` | 146 | 147 | +1 |
| `test/seon/adoption_diagnostic_test.clj` | 56 | 56 | +0 |
| `test/seon/adoption_margin_test.clj` | 69 | 19 | -50 |
| `test/seon/bounded_boundary_census_test.clj` | 378 | 338 | -40 |
| `test/seon/classification_test.clj` | 125 | 92 | -33 |
| `test/seon/cluster/armed_test.clj` | 486 | 487 | +1 |
| `test/seon/cluster/boot_drill_child.clj` | 0 | 60 | +60 |
| `test/seon/cluster/boot_test.clj` | 1837 | 434 | -1403 |
| `test/seon/cluster/bootstrap_resume_child.clj` | 39 | 40 | +1 |
| `test/seon/cluster/cohost_boot_test.clj` | 149 | 142 | -7 |
| `test/seon/cluster/mcp_test.clj` | 575 | 576 | +1 |
| `test/seon/cluster/program_restart_test.clj` | 376 | 377 | +1 |
| `test/seon/cluster/publication_adoption_test.clj` | 82 | 83 | +1 |
| `test/seon/cluster/publication_host_test.clj` | 143 | 137 | -6 |
| `test/seon/cluster/turn_test.clj` | 3729 | 3729 | +0 |
| `test/seon/concurrency_independence_test.clj` | 629 | 630 | +1 |
| `test/seon/config_application_test.clj` | 254 | 255 | +1 |
| `test/seon/custody_stability_test.clj` | 317 | 317 | +0 |
| `test/seon/db_test.clj` | 2291 | 2291 | +0 |
| `test/seon/dev/changed_test_test.clj` | 164 | 192 | +28 |
| `test/seon/dev/clj_kondo_test.clj` | 40 | 40 | +0 |
| `test/seon/dev/dependency_cache_test.clj` | 303 | 303 | +0 |
| `test/seon/dev/edit_feedback_test.clj` | 785 | 785 | +0 |
| `test/seon/dev/fresh_operator_export_test.clj` | 91 | 92 | +1 |
| `test/seon/dev/fresh_operator_reset_test.clj` | 752 | 0 | -752 |
| `test/seon/dev/fresh_operator_test.clj` | 2206 | 0 | -2206 |
| `test/seon/dev/hook_test.clj` | 167 | 149 | -18 |
| `test/seon/dev/mcp_bridge_test.clj` | 777 | 777 | +0 |
| `test/seon/dev/publication_launch_test.clj` | 11 | 11 | +0 |
| `test/seon/dev/publication_test.clj` | 83 | 34 | -49 |
| `test/seon/dev/source_instrumentation_test.clj` | 112 | 112 | +0 |
| `test/seon/fn_test.clj` | 3014 | 3014 | +0 |
| `test/seon/maintenance_schema_test.clj` | 384 | 356 | -28 |
| `test/seon/maintenance_test.clj` | 589 | 429 | -160 |
| `test/seon/operator_test.clj` | 1441 | 0 | -1441 |
| `test/seon/oversight_test.clj` | 204 | 205 | +1 |
| `test/seon/predicate_publication_test.clj` | 103 | 104 | +1 |
| `test/seon/schedule_test.clj` | 413 | 413 | +0 |
| `test/seon/schema_redeclare_test.clj` | 94 | 95 | +1 |
| `test/seon/sci/eval_instrumentation_test.clj` | 75 | 76 | +1 |
| `test/seon/test/runner_test.clj` | 1342 | 1343 | +1 |
| `test/seon/test_cache_test.clj` | 103 | 103 | +0 |
| `test/seon/test_runner_test.clj` | 1632 | 1632 | +0 |
| `test/seon/transact_feedback_probe.clj` | 55 | 55 | +0 |
