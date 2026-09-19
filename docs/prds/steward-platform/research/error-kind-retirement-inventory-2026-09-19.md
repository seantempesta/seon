---
type: research
status: active
tags: [error, schema, inventory]
---

# Error kind retirement inventory — 2026-09-19

Point-in-time source census at HEAD `8cf23ea4f3ddb632b16802500ce97439297075f1`, including current shared working-tree bytes. No listed foreign file was edited. Regenerate after the reset conversion; these are source sites, not installed facts.

Scope: `src/`, `test/`, `resources/`, `script/`, `bin/`, `config/`. Search: `rg --json ":seon\.error/(kind|class)|:seon\.error/keys[^\n]*\bkind\b"` plus the short `:kind`/`:class` declarations in namespaced `seon.error*.edn` maps. Counts below are matching lines and literal matches; multiple sites on one line are retained together.

Replacements obey the [error PRD](../plan/error-entities-prd-2026-09-17.md) §§2–5: preserve concrete evidence, validate all promised facets, never copy the old kind into a new discriminator. D12 retires the general predicate: each branch checks the required members of the specific error schema declared by its callee; a declared base boundary checks `:seon.error/at`, `:seon.error/layer`, and `:seon.error/operation`. Constructor conversion must acquire real observation time, responsible layer and operation. Unknown evidence stays explicitly unavailable.

| Area | Matching lines | Literal matches | Files |
|---|---:|---:|---:|
| src | 982 | 1000 | 85 |
| test | 962 | 968 | 163 |
| resources | 362 | 373 | 69 |
| script | 47 | 48 | 5 |
| bin | 4 | 4 | 1 |
| config | 0 | 0 | 0 |

Total: **2357 matching lines; 2393 matches; 323 files**.

## File ownership and counts

| File | Lines | Ownership |
|---|---:|---|
| `bin/seon-hook` | 4 | Mechanical follow-up |
| `resources/seon/schemas/my.background.edn` | 3 | Mechanical follow-up |
| `resources/seon/schemas/my.edit.edn` | 6 | Mechanical follow-up |
| `resources/seon/schemas/my.fs.edn` | 16 | Mechanical follow-up |
| `resources/seon/schemas/my.message.edn` | 6 | Mechanical follow-up |
| `resources/seon/schemas/my.note.edn` | 5 | Mechanical follow-up |
| `resources/seon/schemas/my.plan.edn` | 13 | Mechanical follow-up |
| `resources/seon/schemas/my.shell.edn` | 5 | Mechanical follow-up |
| `resources/seon/schemas/my.turn.edn` | 3 | Mechanical follow-up |
| `resources/seon/schemas/my.web.edn` | 11 | Mechanical follow-up |
| `resources/seon/schemas/seon.agent.edn` | 6 | Mechanical follow-up |
| `resources/seon/schemas/seon.ai.edn` | 20 | Mechanical follow-up |
| `resources/seon/schemas/seon.artifact.edn` | 1 | Mechanical follow-up |
| `resources/seon/schemas/seon.blob.edn` | 5 | Mechanical follow-up |
| `resources/seon/schemas/seon.boot.edn` | 1 | Mechanical follow-up |
| `resources/seon/schemas/seon.bootstrap.edn` | 2 | Mechanical follow-up |
| `resources/seon/schemas/seon.cluster.eval.edn` | 4 | Mechanical follow-up |
| `resources/seon/schemas/seon.cluster.export.edn` | 5 | Mechanical follow-up |
| `resources/seon/schemas/seon.cluster.process.edn` | 1 | Mechanical follow-up |
| `resources/seon/schemas/seon.cluster.prompt.edn` | 5 | Mechanical follow-up |
| `resources/seon/schemas/seon.cluster.registry.edn` | 8 | Mechanical follow-up |
| `resources/seon/schemas/seon.cluster.reply.edn` | 3 | Mechanical follow-up |
| `resources/seon/schemas/seon.cluster.source.edn` | 7 | Mechanical follow-up |
| `resources/seon/schemas/seon.cluster.store.edn` | 6 | Mechanical follow-up |
| `resources/seon/schemas/seon.cluster.wake.edn` | 1 | Mechanical follow-up |
| `resources/seon/schemas/seon.config.edn` | 7 | Mechanical follow-up |
| `resources/seon/schemas/seon.context.capture.edn` | 2 | Mechanical follow-up |
| `resources/seon/schemas/seon.context.contribution.edn` | 2 | Mechanical follow-up |
| `resources/seon/schemas/seon.context.edn` | 1 | Mechanical follow-up |
| `resources/seon/schemas/seon.db.edn` | 5 | HELD — read only |
| `resources/seon/schemas/seon.dev.mcp.artifact.edn` | 1 | Mechanical follow-up |
| `resources/seon/schemas/seon.dev.mcp.edn` | 5 | Mechanical follow-up |
| `resources/seon/schemas/seon.effect.edn` | 3 | Mechanical follow-up |
| `resources/seon/schemas/seon.env.edn` | 8 | Mechanical follow-up |
| `resources/seon/schemas/seon.error.edn` | 7 | 1a |
| `resources/seon/schemas/seon.eval.drive.edn` | 2 | Mechanical follow-up |
| `resources/seon/schemas/seon.flow.edn` | 6 | Mechanical follow-up |
| `resources/seon/schemas/seon.fn.binding.edn` | 1 | Mechanical follow-up |
| `resources/seon/schemas/seon.fn.edn` | 13 | Mechanical follow-up |
| `resources/seon/schemas/seon.instrument.edn` | 2 | 1a |
| `resources/seon/schemas/seon.maintenance.result.edn` | 3 | Mechanical follow-up |
| `resources/seon/schemas/seon.message.edn` | 5 | Mechanical follow-up |
| `resources/seon/schemas/seon.operator.collect.edn` | 1 | Mechanical follow-up |
| `resources/seon/schemas/seon.operator.edn` | 6 | Mechanical follow-up |
| `resources/seon/schemas/seon.print.edn` | 1 | Mechanical follow-up |
| `resources/seon/schemas/seon.problems.edn` | 5 | Mechanical follow-up |
| `resources/seon/schemas/seon.program.edn` | 4 | Mechanical follow-up |
| `resources/seon/schemas/seon.reconcile.edn` | 6 | Mechanical follow-up |
| `resources/seon/schemas/seon.render.data.edn` | 1 | Mechanical follow-up |
| `resources/seon/schemas/seon.render.edn` | 5 | Mechanical follow-up |
| `resources/seon/schemas/seon.render.hiccup.edn` | 1 | Mechanical follow-up |
| `resources/seon/schemas/seon.render.value.edn` | 3 | Mechanical follow-up |
| `resources/seon/schemas/seon.render.walk.edn` | 3 | Mechanical follow-up |
| `resources/seon/schemas/seon.render.web.edn` | 9 | Mechanical follow-up |
| `resources/seon/schemas/seon.schedule.edn` | 7 | Mechanical follow-up |
| `resources/seon/schemas/seon.schema.datahike.edn` | 10 | Mechanical follow-up |
| `resources/seon/schemas/seon.schema.edn` | 25 | Mechanical follow-up |
| `resources/seon/schemas/seon.schema.edn.edn` | 8 | Mechanical follow-up |
| `resources/seon/schemas/seon.schema.shape.edn` | 3 | Mechanical follow-up |
| `resources/seon/schemas/seon.sci.admit.edn` | 1 | Mechanical follow-up |
| `resources/seon/schemas/seon.sci.eval.edn` | 10 | Mechanical follow-up |
| `resources/seon/schemas/seon.sci.kernel.edn` | 7 | Mechanical follow-up |
| `resources/seon/schemas/seon.sci.reader.edn` | 5 | Mechanical follow-up |
| `resources/seon/schemas/seon.search.edn` | 2 | Mechanical follow-up |
| `resources/seon/schemas/seon.test.accretion.edn` | 2 | Mechanical follow-up |
| `resources/seon/schemas/seon.test.edn` | 2 | Mechanical follow-up |
| `resources/seon/schemas/seon.test.run.edn` | 2 | Mechanical follow-up |
| `resources/seon/schemas/seon.test.runner.edn` | 10 | Mechanical follow-up |
| `resources/seon/schemas/seon.turn.edn` | 2 | Mechanical follow-up |
| `resources/seon/schemas/seon.turn.loop.edn` | 5 | Mechanical follow-up |
| `script/seon/dev/changed_test.clj` | 3 | Mechanical follow-up |
| `script/seon/dev/dependency_digest.clj` | 1 | Mechanical follow-up |
| `script/seon/dev/issues.clj` | 1 | Mechanical follow-up |
| `script/seon/dev/mcp.clj` | 1 | Mechanical follow-up |
| `script/seon/fresh_operator.clj` | 41 | Mechanical follow-up |
| `src/my/background.clj` | 1 | Mechanical follow-up |
| `src/my/program.clj` | 8 | Mechanical follow-up |
| `src/my/test.clj` | 2 | Mechanical follow-up |
| `src/seon/agent.clj` | 13 | Mechanical follow-up |
| `src/seon/ai.clj` | 26 | Mechanical follow-up |
| `src/seon/artifact.clj` | 1 | Mechanical follow-up |
| `src/seon/await.clj` | 1 | Mechanical follow-up |
| `src/seon/background.clj` | 4 | Mechanical follow-up |
| `src/seon/blob.clj` | 5 | Mechanical follow-up |
| `src/seon/bootstrap.clj` | 12 | Mechanical follow-up |
| `src/seon/call_preparation.clj` | 2 | Mechanical follow-up |
| `src/seon/cluster.clj` | 33 | Mechanical follow-up |
| `src/seon/cluster/agent.clj` | 17 | Mechanical follow-up |
| `src/seon/cluster/export.clj` | 3 | Mechanical follow-up |
| `src/seon/cluster/message.clj` | 17 | Mechanical follow-up |
| `src/seon/cluster/process.clj` | 1 | Mechanical follow-up |
| `src/seon/cluster/prompt.clj` | 10 | Mechanical follow-up |
| `src/seon/cluster/registry.clj` | 7 | Mechanical follow-up |
| `src/seon/cluster/reply.clj` | 3 | Mechanical follow-up |
| `src/seon/cluster/source.clj` | 8 | Mechanical follow-up |
| `src/seon/cluster/status.clj` | 1 | Mechanical follow-up |
| `src/seon/cluster/store.clj` | 2 | Mechanical follow-up |
| `src/seon/cluster/wake.clj` | 5 | Mechanical follow-up |
| `src/seon/config.clj` | 8 | Mechanical follow-up |
| `src/seon/context.clj` | 9 | Mechanical follow-up |
| `src/seon/db.clj` | 30 | HELD — read only |
| `src/seon/edit.clj` | 9 | Mechanical follow-up |
| `src/seon/edit/jvm.clj` | 6 | Mechanical follow-up |
| `src/seon/effect.clj` | 12 | Mechanical follow-up |
| `src/seon/env.clj` | 10 | Mechanical follow-up |
| `src/seon/error.clj` | 29 | 1a |
| `src/seon/error/refusal.clj` | 2 | Mechanical follow-up |
| `src/seon/eval.clj` | 3 | Mechanical follow-up |
| `src/seon/eval/drive.clj` | 4 | Mechanical follow-up |
| `src/seon/flow.clj` | 10 | Mechanical follow-up |
| `src/seon/fn.clj` | 25 | Mechanical follow-up |
| `src/seon/fn/analyzer.clj` | 1 | Mechanical follow-up |
| `src/seon/fn/schema_shape.clj` | 3 | Mechanical follow-up |
| `src/seon/fn/signature.cljc` | 1 | Mechanical follow-up |
| `src/seon/fs.clj` | 1 | Mechanical follow-up |
| `src/seon/fs/jvm.clj` | 2 | Mechanical follow-up |
| `src/seon/instrument.clj` | 15 | 1a |
| `src/seon/issue.clj` | 15 | Mechanical follow-up |
| `src/seon/issue/detect.clj` | 8 | Mechanical follow-up |
| `src/seon/issue/opening.clj` | 2 | Mechanical follow-up |
| `src/seon/maintenance.clj` | 8 | Mechanical follow-up |
| `src/seon/note.clj` | 3 | Mechanical follow-up |
| `src/seon/operator.clj` | 12 | Mechanical follow-up |
| `src/seon/operator/state.clj` | 15 | Mechanical follow-up |
| `src/seon/plan.clj` | 5 | Mechanical follow-up |
| `src/seon/print.cljc` | 3 | Mechanical follow-up |
| `src/seon/problems.clj` | 10 | Mechanical follow-up |
| `src/seon/program.cljc` | 11 | Mechanical follow-up |
| `src/seon/reconcile.cljc` | 3 | Mechanical follow-up |
| `src/seon/render.clj` | 37 | Mechanical follow-up |
| `src/seon/render/data.clj` | 8 | Mechanical follow-up |
| `src/seon/render/hiccup.clj` | 5 | Mechanical follow-up |
| `src/seon/render/lint.clj` | 5 | Mechanical follow-up |
| `src/seon/render/ns.clj` | 1 | Mechanical follow-up |
| `src/seon/render/test.clj` | 2 | Mechanical follow-up |
| `src/seon/render/transcript.clj` | 50 | Mechanical follow-up |
| `src/seon/render/value.clj` | 5 | Mechanical follow-up |
| `src/seon/render/walk.clj` | 10 | Mechanical follow-up |
| `src/seon/render/web.clj` | 38 | Mechanical follow-up |
| `src/seon/repl.clj` | 1 | Mechanical follow-up |
| `src/seon/run.clj` | 3 | Mechanical follow-up |
| `src/seon/schedule.clj` | 10 | Mechanical follow-up |
| `src/seon/schema.clj` | 33 | HELD — read only |
| `src/seon/schema/datahike.clj` | 7 | Mechanical follow-up |
| `src/seon/schema/edn.clj` | 12 | Mechanical follow-up |
| `src/seon/schema/internal.cljc` | 4 | Mechanical follow-up |
| `src/seon/sci/admit.clj` | 4 | Mechanical follow-up |
| `src/seon/sci/eval.clj` | 39 | Mechanical follow-up |
| `src/seon/sci/kernel.clj` | 8 | Mechanical follow-up |
| `src/seon/sci/reader.cljc` | 2 | Mechanical follow-up |
| `src/seon/search.clj` | 2 | Mechanical follow-up |
| `src/seon/shell/jvm.clj` | 17 | Mechanical follow-up |
| `src/seon/test.clj` | 57 | HELD — read only |
| `src/seon/test/accretion.clj` | 8 | Mechanical follow-up |
| `src/seon/test/arm.clj` | 7 | Mechanical follow-up |
| `src/seon/test/bounds.clj` | 2 | Mechanical follow-up |
| `src/seon/test/fast.clj` | 1 | Mechanical follow-up |
| `src/seon/test/runner.clj` | 77 | HELD — read only |
| `src/seon/test/selection.clj` | 1 | HELD — read only |
| `src/seon/turn.clj` | 69 | Mechanical follow-up |
| `test/my/background_test.clj` | 1 | Mechanical follow-up |
| `test/my/examples_test.clj` | 4 | Mechanical follow-up |
| `test/my/message_test.clj` | 2 | Mechanical follow-up |
| `test/my/note_test.clj` | 1 | Mechanical follow-up |
| `test/my/plan_test.clj` | 8 | Mechanical follow-up |
| `test/my/program_mutation_test.clj` | 6 | Mechanical follow-up |
| `test/my/program_test.clj` | 6 | Mechanical follow-up |
| `test/my/turn_test.clj` | 1 | Mechanical follow-up |
| `test/seon/adoption_diagnostic_test.clj` | 2 | Mechanical follow-up |
| `test/seon/adoption_margin_test.clj` | 2 | Mechanical follow-up |
| `test/seon/adoption_rows_test.clj` | 3 | Mechanical follow-up |
| `test/seon/agent_call_edges_test.clj` | 3 | Mechanical follow-up |
| `test/seon/agent_situation_test.clj` | 1 | Mechanical follow-up |
| `test/seon/ai_stream_fold_test.clj` | 9 | Mechanical follow-up |
| `test/seon/ai_test.clj` | 25 | Mechanical follow-up |
| `test/seon/await_test.clj` | 3 | Mechanical follow-up |
| `test/seon/bootstrap_test.clj` | 3 | Mechanical follow-up |
| `test/seon/bounded_boundary_census_test.clj` | 4 | Mechanical follow-up |
| `test/seon/call_preparation_test.clj` | 10 | Mechanical follow-up |
| `test/seon/classification_test.clj` | 2 | Mechanical follow-up |
| `test/seon/cluster/agent_arming_test.clj` | 2 | Mechanical follow-up |
| `test/seon/cluster/agent_identity_test.clj` | 1 | Mechanical follow-up |
| `test/seon/cluster/agent_namespace_test.clj` | 1 | Mechanical follow-up |
| `test/seon/cluster/agent_test.clj` | 10 | Mechanical follow-up |
| `test/seon/cluster/armed_test.clj` | 7 | Mechanical follow-up |
| `test/seon/cluster/boot_test.clj` | 5 | Mechanical follow-up |
| `test/seon/cluster/evaluate_sources_test.clj` | 5 | Mechanical follow-up |
| `test/seon/cluster/instruction_test.clj` | 2 | Mechanical follow-up |
| `test/seon/cluster/mcp_test.clj` | 5 | Mechanical follow-up |
| `test/seon/cluster/message_assignment_test.clj` | 1 | Mechanical follow-up |
| `test/seon/cluster/message_test.clj` | 4 | Mechanical follow-up |
| `test/seon/cluster/problem_routing_test.clj` | 10 | Mechanical follow-up |
| `test/seon/cluster/program_restart_test.clj` | 2 | Mechanical follow-up |
| `test/seon/cluster/prompt_test.clj` | 7 | Mechanical follow-up |
| `test/seon/cluster/registry_test.clj` | 1 | Mechanical follow-up |
| `test/seon/cluster/reply_test.clj` | 8 | Mechanical follow-up |
| `test/seon/cluster/resume_artifact_routing_test.clj` | 3 | Mechanical follow-up |
| `test/seon/cluster/source_test.clj` | 4 | Mechanical follow-up |
| `test/seon/cluster/status_test.clj` | 4 | Mechanical follow-up |
| `test/seon/cluster/store_test.clj` | 3 | Mechanical follow-up |
| `test/seon/cluster/store_transact_test.clj` | 7 | Mechanical follow-up |
| `test/seon/cluster/turn_test.clj` | 35 | Mechanical follow-up |
| `test/seon/cluster/wake_test.clj` | 7 | Mechanical follow-up |
| `test/seon/cluster_test.clj` | 9 | Mechanical follow-up |
| `test/seon/concurrency_independence_test.clj` | 5 | Mechanical follow-up |
| `test/seon/concurrency_streams_test.clj` | 2 | Mechanical follow-up |
| `test/seon/concurrency_test.clj` | 2 | Mechanical follow-up |
| `test/seon/config_test.clj` | 12 | Mechanical follow-up |
| `test/seon/context_blocks_fixture.clj` | 2 | Mechanical follow-up |
| `test/seon/context_capture_test.clj` | 2 | Mechanical follow-up |
| `test/seon/context_selection_test.clj` | 5 | Mechanical follow-up |
| `test/seon/contracts_fixture.clj` | 1 | Mechanical follow-up |
| `test/seon/contracts_install_test.clj` | 3 | Mechanical follow-up |
| `test/seon/contracts_plan_test.clj` | 5 | Mechanical follow-up |
| `test/seon/custody_stability_test.clj` | 1 | Mechanical follow-up |
| `test/seon/data_shapes_test.clj` | 5 | Mechanical follow-up |
| `test/seon/db/declaration_population_test.clj` | 3 | Mechanical follow-up |
| `test/seon/db_test.clj` | 39 | HELD — read only |
| `test/seon/dev/changed_test_test.clj` | 3 | Mechanical follow-up |
| `test/seon/dev/dependency_cache_test.clj` | 1 | Mechanical follow-up |
| `test/seon/dev/edit_feedback_test.clj` | 1 | Mechanical follow-up |
| `test/seon/dev/fresh_operator_reset_test.clj` | 3 | Mechanical follow-up |
| `test/seon/dev/fresh_operator_test.clj` | 12 | Mechanical follow-up |
| `test/seon/dev/hook_test.clj` | 2 | Mechanical follow-up |
| `test/seon/edit/jvm_test.clj` | 2 | Mechanical follow-up |
| `test/seon/edit_test.clj` | 11 | Mechanical follow-up |
| `test/seon/effect_test.clj` | 6 | Mechanical follow-up |
| `test/seon/env_test.clj` | 6 | Mechanical follow-up |
| `test/seon/error_class_schema_test.clj` | 2 | Mechanical follow-up |
| `test/seon/error_test.clj` | 46 | 1a |
| `test/seon/flow_configuration_test.clj` | 1 | Mechanical follow-up |
| `test/seon/flow_test.clj` | 9 | Mechanical follow-up |
| `test/seon/fn_test.clj` | 28 | Mechanical follow-up |
| `test/seon/fs/jvm_test.clj` | 9 | Mechanical follow-up |
| `test/seon/help_test.clj` | 2 | Mechanical follow-up |
| `test/seon/help_trial_test.clj` | 1 | Mechanical follow-up |
| `test/seon/html_views_test.clj` | 1 | Mechanical follow-up |
| `test/seon/instrument_test.clj` | 23 | 1a |
| `test/seon/issue/detect_test.clj` | 4 | Mechanical follow-up |
| `test/seon/issue_deletion_test.clj` | 1 | Mechanical follow-up |
| `test/seon/issue_generate_test.clj` | 4 | Mechanical follow-up |
| `test/seon/issue_settlement_test.clj` | 8 | Mechanical follow-up |
| `test/seon/issue_test.clj` | 19 | Mechanical follow-up |
| `test/seon/loop_proof_test.clj` | 12 | Mechanical follow-up |
| `test/seon/maintenance_schema_test.clj` | 4 | Mechanical follow-up |
| `test/seon/maintenance_test.clj` | 6 | Mechanical follow-up |
| `test/seon/mcp_test.clj` | 5 | Mechanical follow-up |
| `test/seon/no_provider_test.clj` | 3 | Mechanical follow-up |
| `test/seon/operator_test.clj` | 25 | Mechanical follow-up |
| `test/seon/owned_value_test.clj` | 1 | Mechanical follow-up |
| `test/seon/plan_completion_test.clj` | 2 | Mechanical follow-up |
| `test/seon/problems_test.clj` | 11 | Mechanical follow-up |
| `test/seon/program_test.clj` | 8 | Mechanical follow-up |
| `test/seon/public_contract_test.clj` | 3 | Mechanical follow-up |
| `test/seon/read_evidence_test.clj` | 6 | Mechanical follow-up |
| `test/seon/reconcile_test.clj` | 2 | Mechanical follow-up |
| `test/seon/refusal_grammar_test.clj` | 2 | Mechanical follow-up |
| `test/seon/registry_isolation_test.clj` | 1 | Mechanical follow-up |
| `test/seon/render/data_test.clj` | 6 | Mechanical follow-up |
| `test/seon/render/faults_test.clj` | 2 | Mechanical follow-up |
| `test/seon/render/hiccup_test.clj` | 1 | Mechanical follow-up |
| `test/seon/render/history_test.clj` | 1 | Mechanical follow-up |
| `test/seon/render/lint_test.clj` | 1 | Mechanical follow-up |
| `test/seon/render/ns_test.clj` | 2 | Mechanical follow-up |
| `test/seon/render/page_review_test.clj` | 2 | Mechanical follow-up |
| `test/seon/render/retained_test.clj` | 4 | Mechanical follow-up |
| `test/seon/render/root_pull_test.clj` | 2 | Mechanical follow-up |
| `test/seon/render/runtime_test.clj` | 1 | Mechanical follow-up |
| `test/seon/render/transcript_run_test.clj` | 1 | Mechanical follow-up |
| `test/seon/render/transcript_test.clj` | 6 | Mechanical follow-up |
| `test/seon/render/value_test.clj` | 5 | Mechanical follow-up |
| `test/seon/render/walk_test.clj` | 4 | Mechanical follow-up |
| `test/seon/render/web_debug_test.clj` | 10 | Mechanical follow-up |
| `test/seon/render/web_test.clj` | 7 | Mechanical follow-up |
| `test/seon/render_coverage_test.clj` | 5 | Mechanical follow-up |
| `test/seon/render_simplification_test.clj` | 8 | Mechanical follow-up |
| `test/seon/render_source_test.clj` | 4 | Mechanical follow-up |
| `test/seon/repl_grammar_test.clj` | 1 | Mechanical follow-up |
| `test/seon/repl_parity_test.clj` | 6 | Mechanical follow-up |
| `test/seon/repl_test.clj` | 2 | Mechanical follow-up |
| `test/seon/rereads_test.clj` | 3 | Mechanical follow-up |
| `test/seon/reset_edges_test.clj` | 8 | Mechanical follow-up |
| `test/seon/returned_error_test.clj` | 1 | Mechanical follow-up |
| `test/seon/run4_install_test.clj` | 1 | Mechanical follow-up |
| `test/seon/run4_reader_test.clj` | 2 | Mechanical follow-up |
| `test/seon/run6_db_test.clj` | 3 | Mechanical follow-up |
| `test/seon/run6_stall_test.clj` | 2 | Mechanical follow-up |
| `test/seon/schedule_test.clj` | 7 | Mechanical follow-up |
| `test/seon/schema/admission_test.clj` | 1 | Mechanical follow-up |
| `test/seon/schema/datahike_test.clj` | 5 | Mechanical follow-up |
| `test/seon/schema/declaration_population_test.clj` | 1 | Mechanical follow-up |
| `test/seon/schema/edn_test.clj` | 1 | Mechanical follow-up |
| `test/seon/schema/program_test.clj` | 1 | Mechanical follow-up |
| `test/seon/schema_test.clj` | 9 | HELD — read only |
| `test/seon/schema_usage_guard_test.clj` | 2 | Mechanical follow-up |
| `test/seon/sci/admit_test.clj` | 2 | Mechanical follow-up |
| `test/seon/sci/documentation_test.clj` | 7 | Mechanical follow-up |
| `test/seon/sci/eval_instrumentation_test.clj` | 1 | Mechanical follow-up |
| `test/seon/sci/eval_test.clj` | 31 | Mechanical follow-up |
| `test/seon/sci/kernel_arm_carriage_test.clj` | 1 | Mechanical follow-up |
| `test/seon/sci/reader_test.clj` | 14 | Mechanical follow-up |
| `test/seon/sci/shown_text_test.clj` | 1 | Mechanical follow-up |
| `test/seon/shell/jvm_test.clj` | 7 | Mechanical follow-up |
| `test/seon/source_reconciliation_test.clj` | 3 | Mechanical follow-up |
| `test/seon/supplied_documentation_test.clj` | 4 | Mechanical follow-up |
| `test/seon/test/check_request_test.clj` | 2 | Mechanical follow-up |
| `test/seon/test/runner_test.clj` | 12 | HELD — read only |
| `test/seon/test/selection_test.clj` | 10 | HELD — read only |
| `test/seon/test_failure_facts_test.clj` | 3 | Mechanical follow-up |
| `test/seon/test_preparation_test.clj` | 1 | Mechanical follow-up |
| `test/seon/test_provenance_test.clj` | 1 | Mechanical follow-up |
| `test/seon/test_reaching_test.clj` | 11 | Mechanical follow-up |
| `test/seon/test_runner_failure_fixture.clj` | 1 | Mechanical follow-up |
| `test/seon/test_runner_test.clj` | 26 | Mechanical follow-up |
| `test/seon/test_support.clj` | 8 | Mechanical follow-up |
| `test/seon/test_support_test.clj` | 9 | Mechanical follow-up |
| `test/seon/test_test.clj` | 8 | Mechanical follow-up |
| `test/seon/transact_feedback_test.clj` | 4 | Mechanical follow-up |
| `test/seon/transaction_result_test.clj` | 1 | Mechanical follow-up |
| `test/seon/turn_continue_test.clj` | 2 | Mechanical follow-up |
| `test/seon/turn_loop_test.clj` | 17 | Mechanical follow-up |
| `test/seon/turn_test.clj` | 23 | Mechanical follow-up |
| `test/seon/turn_work_test.clj` | 2 | Mechanical follow-up |

## Exact sites and required conversion

Replacement actions (each site below names its action):

- **R1:** Replace constructor stamp with observed at/layer/operation and the PRD owner facet’s required payload. Keep cause/evidence; update this producer’s output contract.
- **R2:** Under D12, inspect the required members of the producer’s exact declared error facet or union; preserve the original value. A callee still declaring generic `:seon.error/value` is a step-6 contract site, not permission to infer its errors.
- **R3:** Remove class metadata/stamp; retain substantive members and extend the declared base/facet. Delete boolean-only class schemas per PRD §3; update their references.
- **R4:** Remove kind declaration/member. Domain result contracts enumerate exact facets; generic inspection may alias base. Occurrence storage uses the existing owning relation.
- **R5:** Remove kind projection/selection and carry base plus relevant facet evidence. Update callers that depended on the selected kind; recognition checks only the specific boundary’s declared required members (D12).
- **R6:** Rewrite the explanation around base recognition and the actual observation; remove the kind-based claim.
- **R7:** Remove kind destructuring and route consumers by base/facets or concrete observation fields; update every use in this function.
- **R8:** Construct a valid base plus the producer’s declared facets; replace kind assertion/branch with facet validation and the specific operation or evidence assertion.

A line with several uses must convert each use; excerpts are source context, not replacement code. Schema producer ownership and required payloads are specified in the PRD literal manifest. Tests must assert the replacement facet plus the concrete evidence that distinguished the old case, rather than only the disappearance of a marker.

### bin/seon-hook

| Line | Source | Replacement |
|---:|---|---|
| 1478 | `(or (:seon.error/kind result)` | R1 |
| 1503 | `(str (pr-str (:seon.error/kind failure)) " " message` | R1 |
| 1543 | `{:seon.error/kind :seon.hook/publication-result-unavailable` | R1 |
| 1549 | `(if (and (zero? (:exit result)) (not (:seon.error/kind terminal)))` | R2 |

### resources/seon/schemas/my.background.edn

| Line | Source | Replacement |
|---:|---|---|
| 23 | `:invalid-call-error [:map {:seon.error/class true :seon.render/ai seon.background/render-ai :seon.render/html seon.background/render-html :error/message "must mark an invalid background call"} [:my.background/invalid-call :my.background/invalid-call] [:seon.error/message :seon.error/message]]` | R3 |
| 25 | `:invalid-result-error [:map {:seon.error/class true :seon.render/ai seon.background/render-ai :seon.render/html seon.background/render-html :error/message "must mark an invalid background result"} [:my.background/invalid-result :my.background/invalid-result] [:seon.error/message :seon.error/message]]` | R3 |
| 27 | `:missing-result-error [:map {:seon.error/class true :seon.render/ai seon.background/render-ai :seon.render/html seon.background/render-html :error/message "must mark a background call with no result"} [:my.background/missing-result :my.background/missing-result] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/my.edit.edn

| Line | Source | Replacement |
|---:|---|---|
| 64 | `:my.edit/no-match-error [:map {:seon.error/class true :seon.render/ai seon.error/edit-prose :seon.render/html seon.error/render-html :error/message "must identify the path with no matching edit target"} [:my.edit/no-match :my.edit/no-match] [:seon.error/message :seon.error/message]]` | R3 |
| 66 | `:my.edit/ambiguous-match-error [:map {:seon.error/class true :seon.render/ai seon.error/edit-prose :seon.render/html seon.error/render-html :error/message "must identify the path with ambiguous edit targets"} [:my.edit/ambiguous-match :my.edit/ambiguous-match] [:seon.error/message :seon.error/message]]` | R3 |
| 68 | `:my.edit/parse-refused-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark edited source refused by the Clojure reader"} [:my.edit/parse-refused :my.edit/parse-refused] [:seon.error/message :seon.error/message]]` | R3 |
| 70 | `:my.edit/lossless-check-failed-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark edited source that failed the lossless check"} [:my.edit/lossless-check-failed :my.edit/lossless-check-failed] [:seon.error/message :seon.error/message]]` | R3 |
| 72 | `:my.edit/stale-source-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the path whose source is stale"} [:my.edit/stale-source :my.edit/stale-source] [:seon.error/message :seon.error/message]]` | R3 |
| 74 | `:my.edit/not-utf8-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the path whose source is not strict UTF-8"} [:my.edit/not-utf8 :my.edit/not-utf8] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/my.fs.edn

| Line | Source | Replacement |
|---:|---|---|
| 121 | `:not-found-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the absent filesystem path"} [:my.fs/not-found :my.fs/not-found] [:seon.error/message :seon.error/message]]` | R3 |
| 123 | `:not-directory-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the path that is not a directory"} [:my.fs/not-directory :my.fs/not-directory] [:seon.error/message :seon.error/message]]` | R3 |
| 125 | `:not-regular-file-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the path that is not a regular file"} [:my.fs/not-regular-file :my.fs/not-regular-file] [:seon.error/message :seon.error/message]]` | R3 |
| 127 | `:already-exists-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the path that already exists"} [:my.fs/already-exists :my.fs/already-exists] [:seon.error/message :seon.error/message]]` | R3 |
| 129 | `:path-refused-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the refused filesystem path"} [:my.fs/path-refused :my.fs/path-refused] [:seon.error/message :seon.error/message]]` | R3 |
| 131 | `:read-failed-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the path whose read failed"} [:my.fs/read-failed :my.fs/read-failed] [:seon.error/message :seon.error/message]]` | R3 |
| 133 | `:write-failed-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the path whose write failed"} [:my.fs/write-failed :my.fs/write-failed] [:seon.error/message :seon.error/message]]` | R3 |
| 135 | `:read-limit-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the path whose read exceeded its limit, the size observed, and the ceiling"} [:my.fs/read-limit :my.fs/read-limit] [:seon.error/message :seon.error/message]]` | R3 |
| 137 | `:write-limit-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the path whose write exceeded its limit"} [:my.fs/write-limit :my.fs/write-limit] [:seon.error/message :seon.error/message]]` | R3 |
| 139 | `:stale-digest-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the path whose digest is stale"} [:my.fs/stale-digest :my.fs/stale-digest] [:seon.error/message :seon.error/message]]` | R3 |
| 141 | `:changed-during-read-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the path that changed during its read"} [:my.fs/changed-during-read :my.fs/changed-during-read] [:seon.error/message :seon.error/message]]` | R3 |
| 143 | `:invalid-utf8-window-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the path whose requested window is not UTF-8"} [:my.fs/invalid-utf8-window :my.fs/invalid-utf8-window] [:seon.error/message :seon.error/message]]` | R3 |
| 145 | `:atomic-write-unsupported-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the path where atomic replacement is unavailable"} [:my.fs/atomic-write-unsupported :my.fs/atomic-write-unsupported] [:seon.error/message :seon.error/message]]` | R3 |
| 147 | `:glob-failed-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the path whose glob expansion failed"} [:my.fs/glob-failed :my.fs/glob-failed] [:seon.error/message :seon.error/message]]` | R3 |
| 149 | `:invalid-glob-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the path whose glob expression is invalid"} [:my.fs/invalid-glob :my.fs/invalid-glob] [:seon.error/message :seon.error/message]]` | R3 |
| 151 | `:blob-unavailable-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the unavailable blob digest"} [:my.fs/blob-unavailable :my.fs/blob-unavailable] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/my.message.edn

| Line | Source | Replacement |
|---:|---|---|
| 59 | `:my.message/no-recipient-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a message request with no recipient"} [:my.message/no-recipient :my.message/no-recipient] [:seon.error/message :seon.error/message]]` | R3 |
| 61 | `:my.message/no-content-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a message request with no content"} [:my.message/no-content :my.message/no-content] [:seon.error/message :seon.error/message]]` | R3 |
| 63 | `:my.message/no-about-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a message with an invalid subject token"} [:my.message/no-about :my.message/no-about] [:seon.error/message :seon.error/message]]` | R3 |
| 65 | `:my.message/no-assignment-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a declination with no assignment identity"} [:my.message/no-assignment :my.message/no-assignment] [:seon.error/message :seon.error/message]]` | R3 |
| 67 | `:my.message/no-reason-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a declination with no reason"} [:my.message/no-reason :my.message/no-reason] [:seon.error/message :seon.error/message]]` | R3 |
| 69 | `:my.message/not-found-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the message that does not exist"} [:my.message/not-found :my.message/not-found] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/my.note.edn

| Line | Source | Replacement |
|---:|---|---|
| 25 | `:agent-not-found-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the agent that does not exist"} [:my.note/agent-not-found :my.note/agent-not-found] [:seon.error/message :seon.error/message]],` | R3 |
| 27 | `:identity-owned-by-another-agent-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the note owned by another agent"} [:my.note/identity-owned-by-another-agent :my.note/identity-owned-by-another-agent] [:seon.error/message :seon.error/message]],` | R3 |
| 29 | `:about-not-found-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the note subject that does not exist"} [:my.note/about-not-found :my.note/about-not-found] [:seon.error/message :seon.error/message]],` | R3 |
| 31 | `:not-found-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the current note that does not exist"} [:my.note/not-found :my.note/not-found] [:seon.error/message :seon.error/message]],` | R3 |
| 33 | `:not-owned-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the note not owned by the caller"} [:my.note/not-owned :my.note/not-owned] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/my.plan.edn

| Line | Source | Replacement |
|---:|---|---|
| 223 | `:agent-not-found-error [:map {:seon.error/class true, :seon.render/ai seon.error/render-ai, :seon.render/html seon.error/render-html, :error/message "must mark a plan request whose agent does not exist"} [:my.plan/agent-not-found :my.plan/agent-not-found] [:seon.error/message :seon.error/message]],` | R3 |
| 225 | `:dependency-cycle-error [:map {:seon.error/class true, :seon.render/ai seon.error/render-ai, :seon.render/html seon.error/render-html, :error/message "must mark plan dependencies that form a cycle"} [:my.plan/dependency-cycle :my.plan/dependency-cycle] [:seon.error/message :seon.error/message]],` | R3 |
| 227 | `:dependency-not-found-error [:map {:seon.error/class true, :seon.render/ai seon.error/render-ai, :seon.render/html seon.error/render-html, :error/message "must mark a plan dependency that does not exist"} [:my.plan/dependency-not-found :my.plan/dependency-not-found] [:seon.error/message :seon.error/message]],` | R3 |
| 229 | `:duplicate-identity-error [:map {:seon.error/class true, :seon.render/ai seon.error/render-ai, :seon.render/html seon.error/render-html, :error/message "must mark an identity repeated in one authored plan"} [:my.plan/duplicate-identity :my.plan/duplicate-identity] [:seon.error/message :seon.error/message]],` | R3 |
| 231 | `:duplicate-position-error [:map {:seon.error/class true, :seon.render/ai seon.error/render-ai, :seon.render/html seon.error/render-html, :error/message "must mark two sibling steps claiming one position"} [:my.plan/duplicate-position :my.plan/duplicate-position] [:seon.error/message :seon.error/message]],` | R3 |
| 233 | `:foreign-identity-error [:map {:seon.error/class true, :seon.render/ai seon.error/render-ai, :seon.render/html seon.error/render-html, :error/message "must mark an authored identity owned by another agent"} [:my.plan/foreign-identity :my.plan/foreign-identity] [:seon.error/message :seon.error/message]],` | R3 |
| 235 | `:identity-exists-error [:map {:seon.error/class true, :seon.render/ai seon.error/render-ai, :seon.render/html seon.error/render-html, :error/message "must mark a new plan step identity that already exists"} [:my.plan/identity-exists :my.plan/identity-exists] [:seon.error/message :seon.error/message]],` | R3 |
| 237 | `:item-reference-not-found-error [:map {:seon.error/class true, :seon.render/ai seon.error/render-ai, :seon.render/html seon.error/render-html, :error/message "must mark a referenced plan step that does not exist"} [:my.plan/item-reference-not-found :my.plan/item-reference-not-found] [:seon.error/message :seon.error/message]],` | R3 |
| 239 | `:item-reference-not-owned-error [:map {:seon.error/class true, :seon.render/ai seon.error/render-ai, :seon.render/html seon.error/render-html, :error/message "must mark a referenced plan step outside this agent's plan"} [:my.plan/item-reference-not-owned :my.plan/item-reference-not-owned] [:seon.error/message :seon.error/message]],` | R3 |
| 241 | `:not-found-error [:map {:seon.error/class true, :seon.render/ai seon.error/render-ai, :seon.render/html seon.error/render-html, :error/message "must mark a plan step that does not exist"} [:my.plan/not-found :my.plan/not-found] [:seon.error/message :seon.error/message]],` | R3 |
| 243 | `:not-owned-error [:map {:seon.error/class true, :seon.render/ai seon.error/render-ai, :seon.render/html seon.error/render-html, :error/message "must mark a plan step outside this agent's plan"} [:my.plan/not-owned :my.plan/not-owned] [:seon.error/message :seon.error/message]],` | R3 |
| 245 | `:subject-not-found-error [:map {:seon.error/class true, :seon.render/ai seon.error/render-ai, :seon.render/html seon.error/render-html, :error/message "must mark a plan subject that does not exist"} [:my.plan/subject-not-found :my.plan/subject-not-found] [:seon.error/message :seon.error/message]],` | R3 |
| 247 | `:unusable-current-step-error [:map {:seon.error/class true, :seon.render/ai seon.error/render-ai, :seon.render/html seon.error/render-html, :error/message "must mark a current step that is not an open step of this plan"} [:my.plan/unusable-current-step :my.plan/unusable-current-step] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/my.shell.edn

| Line | Source | Replacement |
|---:|---|---|
| 36 | `:my.shell/blob-unavailable-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :my.shell/blob-unavailable class marker"} [:my.shell/blob-unavailable :my.shell/blob-unavailable] [:seon.error/message :seon.error/message]]` | R3 |
| 38 | `:my.shell/stdin-limit-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :my.shell/stdin-limit class marker"} [:my.shell/stdin-limit :my.shell/stdin-limit] [:seon.error/message :seon.error/message]]` | R3 |
| 40 | `:my.shell/cwd-refused-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a refused child working directory"} [:my.shell/cwd-refused :my.shell/cwd-refused] [:seon.error/message :seon.error/message]]` | R3 |
| 42 | `:my.shell/time-limit-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a foreign process stopped by its time limit"} [:my.shell/time-limit :my.shell/time-limit] [:seon.error/message :seon.error/message]]` | R3 |
| 44 | `:my.shell/start-failed-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a foreign process that could not start"} [:my.shell/start-failed :my.shell/start-failed] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/my.turn.edn

| Line | Source | Replacement |
|---:|---|---|
| 34 | `:blank-note-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a run wait whose note is blank"} [:my.turn/blank-note :my.turn/blank-note] [:seon.error/message :seon.error/message]]` | R3 |
| 36 | `:blank-result-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a run completion whose result is blank"} [:my.turn/blank-result :my.turn/blank-result] [:seon.error/message :seon.error/message]]` | R3 |
| 38 | `:usage-walkthrough-absent-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark my.turn when its usage walkthrough is absent"} [:my.turn/usage-walkthrough-absent :my.turn/usage-walkthrough-absent] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/my.web.edn

| Line | Source | Replacement |
|---:|---|---|
| 39 | `{:seon.error/class true` | R3 |
| 51 | `{:seon.error/class true` | R3 |
| 62 | `{:seon.error/class true` | R3 |
| 72 | `{:seon.error/class true` | R3 |
| 82 | `{:seon.error/class true` | R3 |
| 96 | `{:seon.error/class true` | R3 |
| 106 | `{:seon.error/class true` | R3 |
| 117 | `{:seon.error/class true` | R3 |
| 144 | `{:seon.error/class true` | R3 |
| 155 | `{:seon.error/class true` | R3 |
| 166 | `{:seon.error/class true` | R3 |

### resources/seon/schemas/seon.agent.edn

| Line | Source | Replacement |
|---:|---|---|
| 145 | `:no-such-agent-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the requested agent that does not exist"} [:seon.agent/no-such-agent :seon.agent/no-such-agent] [:seon.error/message :seon.error/message]]` | R3 |
| 147 | `:turn-completion-backstop-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the agent whose turn-completion backstop fired"} [:seon.agent/turn-completion-backstop :seon.agent/turn-completion-backstop] [:seon.error/message :seon.error/message]]` | R3 |
| 149 | `:turn-completion-undeliverable-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the agent whose turn completion was undeliverable"} [:seon.agent/turn-completion-undeliverable :seon.agent/turn-completion-undeliverable] [:seon.error/message :seon.error/message]]` | R3 |
| 151 | `:creation-incomplete-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify an incompletely created agent"} [:seon.agent/creation-incomplete :seon.agent/creation-incomplete] [:seon.error/message :seon.error/message]]` | R3 |
| 153 | `:armer-quiescence-undeliverable-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark an armer that could not publish quiescence"} [:seon.agent/armer-quiescence-undeliverable :seon.agent/armer-quiescence-undeliverable] [:seon.error/message :seon.error/message]]` | R3 |
| 155 | `:supervision-not-committed-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark first-agent supervision that did not commit"} [:seon.agent/supervision-not-committed :seon.agent/supervision-not-committed] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.ai.edn

| Line | Source | Replacement |
|---:|---|---|
| 6 | `{:seon.error/class true,` | R3 |
| 16 | `{:seon.error/class true,` | R3 |
| 26 | `{:seon.error/class true,` | R3 |
| 38 | `{:seon.error/class true,` | R3 |
| 56 | `{:seon.error/class true,` | R3 |
| 65 | `{:seon.error/class true,` | R3 |
| 74 | `{:seon.error/class true,` | R3 |
| 100 | `{:seon.error/class true,` | R3 |
| 112 | `{:seon.error/class true,` | R3 |
| 127 | `{:seon.error/class true,` | R3 |
| 141 | `{:seon.error/class true,` | R3 |
| 163 | `{:seon.error/class true,` | R3 |
| 247 | `{:seon.error/class true,` | R3 |
| 277 | `{:seon.error/class true,` | R3 |
| 341 | `{:seon.error/class true,` | R3 |
| 432 | `{:seon.error/class true,` | R3 |
| 442 | `{:seon.error/class true,` | R3 |
| 451 | `{:seon.error/class true,` | R3 |
| 470 | `{:seon.error/class true,` | R3 |
| 480 | `{:seon.error/class true,` | R3 |

### resources/seon/schemas/seon.artifact.edn

| Line | Source | Replacement |
|---:|---|---|
| 2 | `:seon.artifact/refused-error [:and {:seon.error/class true :seon.error/refusal true :seon.error/refusal-shape :seon.error/refusal-value :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :gen/schema [:map [:seon.artifact/refused :seon.artifact/refused] [:seon.error/message :seon.error/message]] :error/message "must mark a refused artifact transition"} :seon.error/refusal-value [:map [:seon.artifact/refused :seon.artifact/refused]]]` | R3 |

### resources/seon/schemas/seon.blob.edn

| Line | Source | Replacement |
|---:|---|---|
| 32 | `:invalid-threshold-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the invalid blob-inline threshold"} [:seon.blob/invalid-threshold :seon.blob/invalid-threshold] [:seon.error/message :seon.error/message]]` | R3 |
| 34 | `:store-root-absent-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the blob store with no process root"} [:seon.blob/store-root-absent :seon.blob/store-root-absent] [:seon.error/message :seon.error/message]]` | R3 |
| 36 | `:stored-content-mismatch-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify stored blob content that mismatches its digest or size"} [:seon.blob/stored-content-mismatch :seon.blob/stored-content-mismatch] [:seon.error/message :seon.error/message]]` | R3 |
| 38 | `:input-stalled-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify a blob input stream that made no progress"} [:seon.blob/input-stalled :seon.blob/input-stalled] [:seon.error/message :seon.error/message]]` | R3 |
| 40 | `:content-digest-mismatch-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify blob content that does not match its digest"} [:seon.blob/content-digest-mismatch :seon.blob/content-digest-mismatch] [:seon.error/message :seon.error/message]]}` | R3 |

### resources/seon/schemas/seon.boot.edn

| Line | Source | Replacement |
|---:|---|---|
| 156 | `:refused-error [:and {:seon.error/class true :seon.error/refusal true :seon.error/refusal-shape :seon.error/refusal-value :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :gen/schema [:map [:seon.boot/refused :seon.boot/refused] [:seon.error/message :seon.error/message]] :error/message "must mark a refused boot transition"} :seon.error/refusal-value [:map [:seon.boot/refused :seon.boot/refused]]]` | R3 |

### resources/seon/schemas/seon.bootstrap.edn

| Line | Source | Replacement |
|---:|---|---|
| 4 | `{:seon.error/class true` | R3 |
| 15 | `{:seon.error/class true` | R3 |

### resources/seon/schemas/seon.cluster.eval.edn

| Line | Source | Replacement |
|---:|---|---|
| 77 | `[:seon.error/kind` | R4 |
| 79 | `:seon.error/kind]` | R4 |
| 139 | `[:seon.error/kind` | R4 |
| 141 | `:seon.error/kind]` | R4 |

### resources/seon/schemas/seon.cluster.export.edn

| Line | Source | Replacement |
|---:|---|---|
| 3 | `:seon.cluster.export/clone-unsupported-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the branch whose store cannot be cloned"} [:seon.cluster.export/clone-unsupported :seon.cluster.export/clone-unsupported] [:seon.error/message :seon.error/message]]` | R3 |
| 5 | `:seon.cluster.export/export-exists-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the export path that already exists"} [:seon.cluster.export/export-exists :seon.cluster.export/export-exists] [:seon.error/message :seon.error/message]]` | R3 |
| 7 | `:seon.cluster.export/genesis-incomplete-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the export with incomplete genesis data"} [:seon.cluster.export/genesis-incomplete :seon.cluster.export/genesis-incomplete] [:seon.error/message :seon.error/message]]` | R3 |
| 9 | `:seon.cluster.export/no-branch-head-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the branch with no branch head"} [:seon.cluster.export/no-branch-head :seon.cluster.export/no-branch-head] [:seon.error/message :seon.error/message]]` | R3 |
| 11 | `:seon.cluster.export/refused-error [:and {:seon.error/class true :seon.error/refusal true :seon.error/refusal-shape :seon.error/refusal-value :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :gen/schema [:map [:seon.cluster.export/refused :seon.cluster.export/refused] [:seon.error/message :seon.error/message]] :error/message "must mark a refused cluster-export transition"} :seon.error/refusal-value [:map [:seon.cluster.export/refused :seon.cluster.export/refused]]]}` | R3 |

### resources/seon/schemas/seon.cluster.process.edn

| Line | Source | Replacement |
|---:|---|---|
| 8 | `:start-instant-unavailable-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the process whose start instant is unavailable"} [:seon.cluster.process/start-instant-unavailable :seon.cluster.process/start-instant-unavailable] [:seon.error/message :seon.error/message]]}` | R3 |

### resources/seon/schemas/seon.cluster.prompt.edn

| Line | Source | Replacement |
|---:|---|---|
| 30 | `:no-trigger-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a prompt request with no trigger"} [:seon.cluster.prompt/no-trigger :seon.cluster.prompt/no-trigger] [:seon.error/message :seon.error/message]]` | R3 |
| 32 | `:refused-error [:and {:seon.error/class true :seon.error/refusal true :seon.error/refusal-shape :seon.error/refusal-value :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :gen/schema [:map [:seon.cluster.prompt/refused :seon.cluster.prompt/refused] [:seon.error/message :seon.error/message]] :error/message "must identify the refused prompt rule"} :seon.error/refusal-value [:map [:seon.cluster.prompt/refused :seon.cluster.prompt/refused]]]` | R3 |
| 34 | `:missing-cluster-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the agent whose prompt has no cluster configuration"} [:seon.cluster.prompt/missing-cluster :seon.cluster.prompt/missing-cluster] [:seon.error/message :seon.error/message]]` | R3 |
| 36 | `:budget-exceeded-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the prompt token budget that was exceeded"} [:seon.cluster.prompt/budget-exceeded :seon.cluster.prompt/budget-exceeded] [:seon.error/message :seon.error/message]]` | R3 |
| 38 | `:seon.cluster.prompt/missing-config-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the agent whose effective prompt configuration is absent"} [:seon.cluster.prompt/missing-config :seon.cluster.prompt/missing-config] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.cluster.registry.edn

| Line | Source | Replacement |
|---:|---|---|
| 53 | `:cannot-retire-main-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the main cluster that cannot be retired"} [:seon.cluster.registry/cannot-retire-main :seon.cluster.registry/cannot-retire-main] [:seon.error/message :seon.error/message]]` | R3 |
| 55 | `:cluster-connected-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the connected cluster that cannot be changed"} [:seon.cluster.registry/cluster-connected :seon.cluster.registry/cluster-connected] [:seon.error/message :seon.error/message]]` | R3 |
| 57 | `:source-absent-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the cluster whose source branch is absent"} [:seon.cluster.registry/source-absent :seon.cluster.registry/source-absent] [:seon.error/message :seon.error/message]]` | R3 |
| 59 | `:refused-error [:and {:seon.error/class true :seon.error/refusal true :seon.error/refusal-shape :seon.error/refusal-value :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :gen/schema [:map [:seon.cluster.registry/refused :seon.cluster.registry/refused] [:seon.error/message :seon.error/message]] :error/message "must identify the refused cluster-registry rule"} :seon.error/refusal-value [:map [:seon.cluster.registry/refused :seon.cluster.registry/refused]]]` | R3 |
| 61 | `:seon.cluster.registry/branch-head-absent-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.cluster.registry/branch-head-absent class marker"} [:seon.cluster.registry/branch-head-absent :seon.cluster.registry/branch-head-absent] [:seon.error/message :seon.error/message]]` | R3 |
| 63 | `:seon.cluster.registry/candidate-file-absent-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.cluster.registry/candidate-file-absent class marker"} [:seon.cluster.registry/candidate-file-absent :seon.cluster.registry/candidate-file-absent] [:seon.error/message :seon.error/message]]` | R3 |
| 65 | `:seon.cluster.registry/dry-run-barrier-absent-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.cluster.registry/dry-run-barrier-absent class marker"} [:seon.cluster.registry/dry-run-barrier-absent :seon.cluster.registry/dry-run-barrier-absent] [:seon.error/message :seon.error/message]]` | R3 |
| 67 | `:seon.cluster.registry/dry-run-complete-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.cluster.registry/dry-run-complete class marker"} [:seon.cluster.registry/dry-run-complete :seon.cluster.registry/dry-run-complete] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.cluster.reply.edn

| Line | Source | Replacement |
|---:|---|---|
| 14 | `:refused-tag-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the tagged literal refused by the reply reader"} [:seon.cluster.reply/refused-tag :seon.cluster.reply/refused-tag] [:seon.error/message :seon.error/message]]` | R3 |
| 16 | `:unreadable-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the reply text that the reader could not parse"} [:seon.cluster.reply/unreadable :seon.cluster.reply/unreadable] [:seon.error/message :seon.error/message]]` | R3 |
| 18 | `:no-forms-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark an agent reply containing no forms"} [:seon.cluster.reply/no-forms :seon.cluster.reply/no-forms] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.cluster.source.edn

| Line | Source | Replacement |
|---:|---|---|
| 3 | `:seon.cluster.source/root-absent-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the absent source root"} [:seon.cluster.source/root-absent :seon.cluster.source/root-absent] [:seon.error/message :seon.error/message]]` | R3 |
| 5 | `:seon.cluster.source/invalid-source-seal-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the invalid source seal"} [:seon.cluster.source/invalid-source-seal :seon.cluster.source/invalid-source-seal] [:seon.error/message :seon.error/message]]` | R3 |
| 7 | `:seon.cluster.source/populate-unresolvable-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark source population whose dependencies cannot resolve"} [:seon.cluster.source/populate-unresolvable :seon.cluster.source/populate-unresolvable] [:seon.error/message :seon.error/message]]` | R3 |
| 9 | `:seon.cluster.source/publish-readback-failed-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the published commit that did not read back"} [:seon.cluster.source/publish-readback-failed :seon.cluster.source/publish-readback-failed] [:seon.error/message :seon.error/message]]` | R3 |
| 11 | `:seon.cluster.source/stale-publication-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the stale source publication commit"} [:seon.cluster.source/stale-publication :seon.cluster.source/stale-publication] [:seon.error/message :seon.error/message]]` | R3 |
| 13 | `:seon.cluster.source/unsafe-incremental-rows-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the unsafe incremental source-row count"} [:seon.cluster.source/unsafe-incremental-rows :seon.cluster.source/unsafe-incremental-rows] [:seon.error/message :seon.error/message]]` | R3 |
| 15 | `:seon.cluster.source/refused-error [:and {:seon.error/class true :seon.error/refusal true :seon.error/refusal-shape :seon.error/refusal-value :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :gen/schema [:map [:seon.cluster.source/refused :seon.cluster.source/refused] [:seon.error/message :seon.error/message]] :error/message "must mark a refused source-publication transition"} :seon.error/refusal-value [:map [:seon.cluster.source/refused :seon.cluster.source/refused]]]` | R3 |

### resources/seon/schemas/seon.cluster.store.edn

| Line | Source | Replacement |
|---:|---|---|
| 2 | `:seon.cluster.store/branch-absent-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the absent database branch"} [:seon.cluster.store/branch-absent :seon.cluster.store/branch-absent] [:seon.error/message :seon.error/message]]` | R3 |
| 4 | `:seon.cluster.store/branch-already-open-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the database branch already open in this process"} [:seon.cluster.store/branch-already-open :seon.cluster.store/branch-already-open] [:seon.error/message :seon.error/message]]` | R3 |
| 7 | `:seon.cluster.store/held-elsewhere-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the database branch held by another process"} [:seon.cluster.store/held-elsewhere :seon.cluster.store/held-elsewhere] [:seon.error/message :seon.error/message]]` | R3 |
| 9 | `:seon.cluster.store/initialization-incomplete-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the database branch with incomplete initialization"} [:seon.cluster.store/initialization-incomplete :seon.cluster.store/initialization-incomplete] [:seon.error/message :seon.error/message]]` | R3 |
| 11 | `:seon.cluster.store/refused-error [:and {:seon.error/class true :seon.error/refusal true :seon.error/refusal-shape :seon.error/refusal-value :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :gen/schema [:map [:seon.cluster.store/refused :seon.cluster.store/refused] [:seon.error/message :seon.error/message]] :error/message "must mark a refused cluster-store transition"} :seon.error/refusal-value [:map [:seon.cluster.store/refused :seon.cluster.store/refused]]]` | R3 |
| 13 | `:seon.cluster.store/file-lock-generator-failed-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the file whose fresh lock could not be acquired"} [:seon.cluster.store/file-lock-generator-failed :seon.cluster.store/file-lock-generator-failed] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.cluster.wake.edn

| Line | Source | Replacement |
|---:|---|---|
| 37 | `:undeliverable-wake-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the agent whose wake could not be delivered"} [:seon.cluster.wake/undeliverable-wake :seon.cluster.wake/undeliverable-wake] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.config.edn

| Line | Source | Replacement |
|---:|---|---|
| 43 | `:missing-effective-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the cluster whose effective configuration facts are absent"} [:seon.config/missing-effective :seon.config/missing-effective] [:seon.error/message :seon.error/message]]` | R3 |
| 68 | `:refused-error [:and {:seon.error/class true :seon.error/refusal true :seon.error/refusal-shape :seon.error/refusal-value :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :gen/schema [:map [:seon.config/refused :seon.config/refused] [:seon.error/message :seon.error/message]] :error/message "must identify the refused configuration rule"} :seon.error/refusal-value [:map [:seon.config/refused :seon.config/refused]]]` | R3 |
| 70 | `:manifest-unreadable-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the configuration manifest that could not be read"} [:seon.config/manifest-unreadable :seon.config/manifest-unreadable] [:seon.error/message :seon.error/message]]` | R3 |
| 72 | `:reconcile-refused-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the rule that refused configuration reconciliation"} [:seon.config/reconcile-refused :seon.config/reconcile-refused] [:seon.error/message :seon.error/message]]` | R3 |
| 74 | `:required-absent-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the required configuration key that is absent"} [:seon.config/required-absent :seon.config/required-absent] [:seon.error/message :seon.error/message]]` | R3 |
| 76 | `:unknown-key-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the unknown configuration key"} [:seon.config/unknown-key :seon.config/unknown-key] [:seon.error/message :seon.error/message]]` | R3 |
| 78 | `:seon.config/missing-result-cap-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.config/missing-result-cap class marker"} [:seon.config/missing-result-cap :seon.config/missing-result-cap] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.context.capture.edn

| Line | Source | Replacement |
|---:|---|---|
| 27 | `[:seon.error/kind` | R4 |
| 29 | `:seon.error/kind]` | R4 |

### resources/seon/schemas/seon.context.contribution.edn

| Line | Source | Replacement |
|---:|---|---|
| 23 | `[:seon.error/kind` | R4 |
| 25 | `:seon.error/kind]` | R4 |

### resources/seon/schemas/seon.context.edn

| Line | Source | Replacement |
|---:|---|---|
| 81 | `{:seon.error/class true` | R3 |

### resources/seon/schemas/seon.db.edn

| Line | Source | Replacement |
|---:|---|---|
| 322 | `:transaction-refused-error [:map {:seon.error/class true :seon.render/ai seon.db/render-rejection-ai :seon.render/html seon.db/render-rejection-html :error/message "must mark a database transaction that was refused"} [:seon.db/transaction-refused :seon.db/transaction-refused] [:seon.error/message :seon.error/message]]` | R3 |
| 324 | `:transaction-outcome-unknown-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a database transaction whose outcome is unknown"} [:seon.db/transaction-outcome-unknown :seon.db/transaction-outcome-unknown] [:seon.error/message :seon.error/message]]` | R3 |
| 326 | `:seon.db/invalid-read-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.db/invalid-read class marker"} [:seon.db/invalid-read :seon.db/invalid-read] [:seon.error/message :seon.error/message]]` | R3 |
| 328 | `:seon.db/invalid-request-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.db/invalid-request class marker"} [:seon.db/invalid-request :seon.db/invalid-request] [:seon.error/message :seon.error/message]]` | R3 |
| 330 | `:seon.db/diff-refused-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.db/diff-refused class marker"} [:seon.db/diff-refused :seon.db/diff-refused] [:seon.error/message :seon.error/message]]}` | R3 |

### resources/seon/schemas/seon.dev.mcp.artifact.edn

| Line | Source | Replacement |
|---:|---|---|
| 9 | `:root-not-committed-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the durable MCP artifact root that did not commit"} [:seon.dev.mcp.artifact/root-not-committed :seon.dev.mcp.artifact/root-not-committed] [:seon.error/message :seon.error/message]]}` | R3 |

### resources/seon/schemas/seon.dev.mcp.edn

| Line | Source | Replacement |
|---:|---|---|
| 2 | `:seon.dev.mcp/cluster-degraded-error [:map {:seon.error/class true :seon.render/ai seon.error/mcp-prose :seon.render/html seon.error/render-html :error/message "must identify the cluster whose MCP surface is degraded"} [:seon.dev.mcp/cluster-degraded :seon.dev.mcp/cluster-degraded] [:seon.error/message :seon.error/message]]` | R3 |
| 4 | `:seon.dev.mcp/value-not-found-error [:map {:seon.error/class true :seon.render/ai seon.error/mcp-prose :seon.render/html seon.error/render-html :error/message "must identify the value digest that MCP could not find"} [:seon.dev.mcp/value-not-found :seon.dev.mcp/value-not-found] [:seon.error/message :seon.error/message]]` | R3 |
| 6 | `:seon.dev.mcp/remainder-not-retrievable-error [:map {:seon.error/class true :seon.render/ai seon.error/mcp-prose :seon.render/html seon.error/render-html :error/message "must identify the value whose clipped remainder cannot be retrieved"} [:seon.dev.mcp/remainder-not-retrievable :seon.dev.mcp/remainder-not-retrievable] [:seon.error/message :seon.error/message]]` | R3 |
| 8 | `:seon.dev.mcp/nil-deref-error [:map {:seon.error/class true :seon.render/ai seon.error/mcp-prose :seon.render/html seon.error/render-html :error/message "must mark a development evaluation that dereferenced nil"} [:seon.dev.mcp/nil-deref :seon.dev.mcp/nil-deref] [:seon.error/message :seon.error/message]]` | R3 |
| 10 | `:seon.dev.mcp/jvm-exception-error [:map {:seon.error/class true :seon.render/ai seon.error/mcp-prose :seon.render/html seon.error/render-html :error/message "must mark a development evaluation that threw on the JVM"} [:seon.dev.mcp/jvm-exception :seon.dev.mcp/jvm-exception] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.effect.edn

| Line | Source | Replacement |
|---:|---|---|
| 164 | `:seon.effect/already-recorded-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.effect/already-recorded class marker"} [:seon.effect/already-recorded :seon.effect/already-recorded] [:seon.error/message :seon.error/message]]` | R3 |
| 166 | `:seon.effect/already-settled-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.effect/already-settled class marker"} [:seon.effect/already-settled :seon.effect/already-settled] [:seon.error/message :seon.error/message]]` | R3 |
| 168 | `:seon.effect/missing-receipt-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.effect/missing-receipt class marker"} [:seon.effect/missing-receipt :seon.effect/missing-receipt] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.env.edn

| Line | Source | Replacement |
|---:|---|---|
| 84 | `:seon.env/absent-environment-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.env/absent-environment class marker"} [:seon.env/absent-environment :seon.env/absent-environment] [:seon.error/message :seon.error/message]]` | R3 |
| 86 | `:seon.env/agent-id-absent-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.env/agent-id-absent class marker"} [:seon.env/agent-id-absent :seon.env/agent-id-absent] [:seon.error/message :seon.error/message]]` | R3 |
| 88 | `:seon.env/incomplete-environment-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.env/incomplete-environment class marker"} [:seon.env/incomplete-environment :seon.env/incomplete-environment] [:seon.error/message :seon.error/message]]` | R3 |
| 90 | `:seon.env/invalid-environment-replacement-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.env/invalid-environment-replacement class marker"} [:seon.env/invalid-environment-replacement :seon.env/invalid-environment-replacement] [:seon.error/message :seon.error/message]]` | R3 |
| 92 | `:seon.env/invalid-environment-state-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.env/invalid-environment-state class marker"} [:seon.env/invalid-environment-state :seon.env/invalid-environment-state] [:seon.error/message :seon.error/message]]` | R3 |
| 94 | `:seon.env/invalid-member-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.env/invalid-member class marker"} [:seon.env/invalid-member :seon.env/invalid-member] [:seon.error/message :seon.error/message]]` | R3 |
| 96 | `:seon.env/schema-absent-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.env/schema-absent class marker"} [:seon.env/schema-absent :seon.env/schema-absent] [:seon.error/message :seon.error/message]]` | R3 |
| 98 | `:seon.env/unscopable-member-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.env/unscopable-member class marker"} [:seon.env/unscopable-member :seon.env/unscopable-member] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.error.edn

| Line | Source | Replacement |
|---:|---|---|
| 35 | `:class [:= true],` | R3 |
| 39 | `[:seon.error/kind :seon.error/kind]` | R4 |
| 113 | `[:seon.error/kind :seon.error/kind]` | R4 |
| 194 | `[:seon.error/kind :seon.error/kind]` | R4 |
| 230 | `{:seon.error/class true,` | R3 |
| 240 | `[:seon.error/kind :seon.error/kind]` | R4 |
| 268 | `:kind :keyword,` | R4 |

### resources/seon/schemas/seon.eval.drive.edn

| Line | Source | Replacement |
|---:|---|---|
| 48 | `[:seon.error/kind :seon.error/kind]` | R4 |
| 57 | `:seon.eval.drive/absent-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark an absent evaluation-drive result"} [:seon.eval.drive/absent :seon.eval.drive/absent] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.flow.edn

| Line | Source | Replacement |
|---:|---|---|
| 196 | `:submission-capacity-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the proc whose submission capacity was exhausted"} [:seon.flow/submission-capacity :seon.flow/submission-capacity] [:seon.error/message :seon.error/message]]` | R3 |
| 198 | `:launcher-stopped-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the proc whose launcher has stopped"} [:seon.flow/launcher-stopped :seon.flow/launcher-stopped] [:seon.error/message :seon.error/message]]` | R3 |
| 200 | `:time-limit-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the proc whose compute time limit was reached"} [:seon.flow/time-limit :seon.flow/time-limit] [:seon.error/message :seon.error/message]]` | R3 |
| 202 | `:configuration-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark an invalid flow configuration"} [:seon.flow/configuration :seon.flow/configuration] [:seon.error/message :seon.error/message]]` | R3 |
| 204 | `:timeout-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the proc whose observable completion timed out"} [:seon.flow/timeout :seon.flow/timeout] [:seon.error/message :seon.error/message]]` | R3 |
| 206 | `:seon.flow/fault-channel-overflow-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.flow/fault-channel-overflow class marker"} [:seon.flow/fault-channel-overflow :seon.flow/fault-channel-overflow] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.fn.binding.edn

| Line | Source | Replacement |
|---:|---|---|
| 23 | `:seon.fn.binding/unsupported-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.fn.binding/unsupported class marker"} [:seon.fn.binding/unsupported :seon.fn.binding/unsupported] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.fn.edn

| Line | Source | Replacement |
|---:|---|---|
| 187 | `:index-transaction-refused-error [:map {:seon.error/class true :seon.render/ai seon.error/index-refusal-prose :seon.render/html seon.error/render-html :error/message "must identify the indexing phase whose transaction was refused"} [:seon.fn/index-transaction-refused :seon.fn/index-transaction-refused] [:seon.error/message :seon.error/message]]` | R3 |
| 189 | `:source-checkout-required-error [:map {:seon.error/class true :seon.render/ai seon.error/index-refusal-prose :seon.render/html seon.error/render-html :error/message "must identify the resource that requires a source checkout"} [:seon.fn/source-checkout-required :seon.fn/source-checkout-required] [:seon.error/message :seon.error/message]]` | R3 |
| 191 | `:source-span-absent-error [:map {:seon.error/class true :seon.render/ai seon.error/index-refusal-prose :seon.render/html seon.error/render-html :error/message "must mark a declaration with no exact source span"} [:seon.fn/source-span-absent :seon.fn/source-span-absent] [:seon.error/message :seon.error/message]]` | R3 |
| 193 | `:capability-graph-malformed-error [:map {:seon.error/class true :seon.render/ai seon.error/index-refusal-prose :seon.render/html seon.error/render-html :error/message "must identify the malformed capability-graph rule"} [:seon.fn/capability-graph-malformed :seon.fn/capability-graph-malformed] [:seon.error/message :seon.error/message]]` | R3 |
| 195 | `:analysis-failed-error [:map {:seon.error/class true :seon.render/ai seon.error/index-refusal-prose :seon.render/html seon.error/render-html :error/message "must mark static analysis with blocking findings"} [:seon.fn/analysis-failed :seon.fn/analysis-failed] [:seon.error/message :seon.error/message]]` | R3 |
| 197 | `:source-file-invalid-error [:map {:seon.error/class true :seon.render/ai seon.error/index-refusal-prose :seon.render/html seon.error/render-html :error/message "must identify the invalid or duplicate source file path"} [:seon.fn/source-file-invalid :seon.fn/source-file-invalid] [:seon.error/message :seon.error/message]]` | R3 |
| 199 | `:manifest-absent-error [:map {:seon.error/class true :seon.render/ai seon.error/index-refusal-prose :seon.render/html seon.error/render-html :error/message "must mark an indexing request with neither manifest nor source roots"} [:seon.fn/manifest-absent :seon.fn/manifest-absent] [:seon.error/message :seon.error/message]]` | R3 |
| 201 | `:duplicate-program-identity-error [:map {:seon.error/class true :seon.render/ai seon.error/index-refusal-prose :seon.render/html seon.error/render-html :error/message "must mark duplicate program identities"} [:seon.fn/duplicate-program-identity :seon.fn/duplicate-program-identity] [:seon.error/message :seon.error/message]]` | R3 |
| 203 | `:population-incomplete-error [:map {:seon.error/class true :seon.render/ai seon.error/index-refusal-prose :seon.render/html seon.error/render-html :error/message "must identify the program population with no rows"} [:seon.fn/population-incomplete :seon.fn/population-incomplete] [:seon.error/message :seon.error/message]]` | R3 |
| 205 | `:schema-declaration-invalid-error [:map {:seon.error/class true :seon.render/ai seon.error/index-refusal-prose :seon.render/html seon.error/render-html :error/message "must identify the declaration that is not a Malli schema"} [:seon.fn/schema-declaration-invalid :seon.fn/schema-declaration-invalid] [:seon.error/message :seon.error/message]]` | R3 |
| 207 | `:scratch-not-fresh-error [:map {:seon.error/class true :seon.render/ai seon.error/index-refusal-prose :seon.render/html seon.error/render-html :error/message "must identify the program entity already present on a source scratch branch"} [:seon.fn/scratch-not-fresh :seon.fn/scratch-not-fresh] [:seon.error/message :seon.error/message]]` | R3 |
| 209 | `:seon.fn/index-refused-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.fn/index-refused class marker"} [:seon.fn/index-refused :seon.fn/index-refused] [:seon.error/message :seon.error/message]]` | R3 |
| 211 | `:seon.fn/signature-refused-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.fn/signature-refused class marker"} [:seon.fn/signature-refused :seon.fn/signature-refused] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.instrument.edn

| Line | Source | Replacement |
|---:|---|---|
| 42 | `:contract-violated-error [:map {:seon.error/class true :seon.render/ai seon.error/instrumentation-prose :seon.render/html seon.error/render-html :error/message "must identify the function whose declared contract was violated"} [:seon.instrument/contract-violated :seon.instrument/contract-violated] [:seon.error/message :seon.error/message]]` | R3 |
| 44 | `:seon.instrument/registration-failed-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.instrument/registration-failed class marker"} [:seon.instrument/registration-failed :seon.instrument/registration-failed] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.maintenance.result.edn

| Line | Source | Replacement |
|---:|---|---|
| 97 | `[:seon.error/kind` | R4 |
| 99 | `:seon.error/kind]` | R4 |
| 154 | `[:seon.error/kind :seon.error/kind]` | R4 |

### resources/seon/schemas/seon.message.edn

| Line | Source | Replacement |
|---:|---|---|
| 3 | `{:seon.error/class true,` | R3 |
| 54 | `{:seon.error/class true,` | R3 |
| 134 | `{:seon.error/class true,` | R3 |
| 144 | `{:seon.error/class true,` | R3 |
| 159 | `{:seon.error/class true,` | R3 |

### resources/seon/schemas/seon.operator.collect.edn

| Line | Source | Replacement |
|---:|---|---|
| 13 | `:unrecognized-option-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must name the unrecognized collection option key"} [:seon.operator.collect/unrecognized-option :seon.operator.collect/unrecognized-option] [:seon.operator.collect/option-key :seon.operator.collect/option-key] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.operator.edn

| Line | Source | Replacement |
|---:|---|---|
| 3 | `:low-disk-space-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the managed root with low disk space"} [:seon.operator/low-disk-space :seon.operator/low-disk-space] [:seon.error/message :seon.error/message]],` | R3 |
| 119 | `:failed-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark an operator command that failed"} [:seon.operator/failed :seon.operator/failed] [:seon.error/message :seon.error/message]]` | R3 |
| 121 | `:seon.operator/cluster-cleanup-incomplete-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.operator/cluster-cleanup-incomplete class marker"} [:seon.operator/cluster-cleanup-incomplete :seon.operator/cluster-cleanup-incomplete] [:seon.error/message :seon.error/message]]` | R3 |
| 123 | `:seon.operator/collection-incomplete-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.operator/collection-incomplete class marker"} [:seon.operator/collection-incomplete :seon.operator/collection-incomplete] [:seon.error/message :seon.error/message]]` | R3 |
| 125 | `:seon.operator/process-census-incomplete-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.operator/process-census-incomplete class marker"} [:seon.operator/process-census-incomplete :seon.operator/process-census-incomplete] [:seon.error/message :seon.error/message]]` | R3 |
| 127 | `:seon.operator/reap-incomplete-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.operator/reap-incomplete class marker"} [:seon.operator/reap-incomplete :seon.operator/reap-incomplete] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.print.edn

| Line | Source | Replacement |
|---:|---|---|
| 396 | `:unknown-face-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the requested print face that is not declared"} [:seon.print/unknown-face :seon.print/unknown-face] [:seon.error/message :seon.error/message]]}` | R3 |

### resources/seon/schemas/seon.problems.edn

| Line | Source | Replacement |
|---:|---|---|
| 13 | `[:seon.error/kind :seon.error/kind]` | R4 |
| 26 | `[:seon.error/kind :seon.error/kind]` | R4 |
| 30 | `{:seon.error/class true,` | R3 |
| 42 | `{:seon.error/class true,` | R3 |
| 120 | `[:seon.error/kind :seon.error/kind]` | R4 |

### resources/seon/schemas/seon.program.edn

| Line | Source | Replacement |
|---:|---|---|
| 108 | `[:map {:seon.error/class true` | R3 |
| 116 | `[:map {:seon.error/class true` | R3 |
| 223 | `[:map {:seon.error/class true` | R3 |
| 231 | `[:map {:seon.error/class true` | R3 |

### resources/seon/schemas/seon.reconcile.edn

| Line | Source | Replacement |
|---:|---|---|
| 23 | `:refused-error [:and {:seon.error/class true :seon.error/refusal true :seon.error/refusal-shape :seon.error/refusal-value :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :gen/schema [:map [:seon.reconcile/refused :seon.reconcile/refused] [:seon.error/message :seon.error/message]] :error/message "must identify the refused reconciliation rule"} :seon.error/refusal-value [:map [:seon.reconcile/refused :seon.reconcile/refused]]]` | R3 |
| 25 | `:no-identity-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a reconciled row with no identity attribute"} [:seon.reconcile/no-identity :seon.reconcile/no-identity] [:seon.error/message :seon.error/message]]` | R3 |
| 27 | `:two-identities-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a reconciled row with two identity attributes"} [:seon.reconcile/two-identities :seon.reconcile/two-identities] [:seon.error/message :seon.error/message]]` | R3 |
| 29 | `:duplicate-identity-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark duplicate identities in a reconciled population"} [:seon.reconcile/duplicate-identity :seon.reconcile/duplicate-identity] [:seon.error/message :seon.error/message]]` | R3 |
| 31 | `:identity-outside-scope-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a reconciled identity outside the declared scope"} [:seon.reconcile/identity-outside-scope :seon.reconcile/identity-outside-scope] [:seon.error/message :seon.error/message]]` | R3 |
| 33 | `:seon.reconcile/missing-declarations-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.reconcile/missing-declarations class marker"} [:seon.reconcile/missing-declarations :seon.reconcile/missing-declarations] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.render.data.edn

| Line | Source | Replacement |
|---:|---|---|
| 31 | `:no-such-path-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a requested nested value path that does not exist"} [:seon.render.data/no-such-path :seon.render.data/no-such-path] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.render.edn

| Line | Source | Replacement |
|---:|---|---|
| 144 | `{:seon.error/class true,` | R3 |
| 159 | `:seon.error/kind]` | R4 |
| 239 | `:ambiguous-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a value matching more than one declared render producer"} [:seon.render/ambiguous :seon.render/ambiguous] [:seon.error/message :seon.error/message]]` | R3 |
| 241 | `:invalid-output-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the render projection whose producer returned invalid output"} [:seon.render/invalid-output :seon.render/invalid-output] [:seon.error/message :seon.error/message]]` | R3 |
| 243 | `:seon.render/walk-failed-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.render/walk-failed class marker"} [:seon.render/walk-failed :seon.render/walk-failed] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.render.hiccup.edn

| Line | Source | Replacement |
|---:|---|---|
| 3 | `:seon.render.hiccup/unparseable-tag-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the Hiccup tag that could not be parsed"} [:seon.render.hiccup/unparseable-tag :seon.render.hiccup/unparseable-tag] [:seon.error/message :seon.error/message]]}` | R3 |

### resources/seon/schemas/seon.render.value.edn

| Line | Source | Replacement |
|---:|---|---|
| 52 | `:window-failed-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the blob whose display window could not be read"} [:seon.render.value/window-failed :seon.render.value/window-failed] [:seon.error/message :seon.error/message]]` | R3 |
| 54 | `:window-realization-failed-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark an ordinary value whose display window could not be realized"} [:seon.render.value/window-realization-failed :seon.render.value/window-realization-failed] [:seon.error/message :seon.error/message]]` | R3 |
| 56 | `:seon.render.value/missing-root-identity-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.render.value/missing-root-identity class marker"} [:seon.render.value/missing-root-identity :seon.render.value/missing-root-identity] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.render.walk.edn

| Line | Source | Replacement |
|---:|---|---|
| 94 | `:elided-error [:map {:seon.error/class true :seon.render/ai seon.error/elision-prose :seon.render/html seon.error/elision-html :error/message "must mark a bounded render walk that elided additional content"} [:seon.render.walk/elided :seon.render.walk/elided] [:seon.error/message :seon.error/message]]` | R3 |
| 96 | `:no-such-entity-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a render walk whose requested entity does not exist"} [:seon.render.walk/no-such-entity :seon.render.walk/no-such-entity] [:seon.error/message :seon.error/message]]` | R3 |
| 98 | `:connections-failed-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a render walk whose connection discovery failed"} [:seon.render.walk/connections-failed :seon.render.walk/connections-failed] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.render.web.edn

| Line | Source | Replacement |
|---:|---|---|
| 2 | `:prospective-context-unavailable-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a prospective context that cannot be observed"} [:seon.render.web/prospective-context-unavailable :seon.render.web/prospective-context-unavailable] [:seon.error/message :seon.error/message]]` | R3 |
| 4 | `:live-processes-unavailable-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark live process state that cannot be observed"} [:seon.render.web/live-processes-unavailable :seon.render.web/live-processes-unavailable] [:seon.error/message :seon.error/message]]` | R3 |
| 145 | `:missing-port-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the absent web-server port"} [:seon.render.web/missing-port :seon.render.web/missing-port] [:seon.error/message :seon.error/message]]` | R3 |
| 147 | `:owner-not-ensured-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a namespace page whose owner was not ensured"} [:seon.render.web/owner-not-ensured :seon.render.web/owner-not-ensured] [:seon.error/message :seon.error/message]]` | R3 |
| 150 | `[:map {:seon.error/class true :seon.render/ai seon.error/render-ai` | R3 |
| 156 | `:value-not-found-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the requested rendered value that was not found"} [:seon.render.web/value-not-found :seon.render.web/value-not-found] [:seon.error/message :seon.error/message]]` | R3 |
| 158 | `:value-unreadable-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the rendered value whose blob is unreadable"} [:seon.render.web/value-unreadable :seon.render.web/value-unreadable] [:seon.error/message :seon.error/message]]` | R3 |
| 161 | `[:map {:seon.error/class true` | R3 |
| 169 | `[:map {:seon.error/class true` | R3 |

### resources/seon/schemas/seon.schedule.edn

| Line | Source | Replacement |
|---:|---|---|
| 42 | `:seon.schedule/incomplete-task-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.schedule/incomplete-task class marker"} [:seon.schedule/incomplete-task :seon.schedule/incomplete-task] [:seon.error/message :seon.error/message]]` | R3 |
| 44 | `:seon.schedule/invalid-fire-id-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.schedule/invalid-fire-id class marker"} [:seon.schedule/invalid-fire-id :seon.schedule/invalid-fire-id] [:seon.error/message :seon.error/message]]` | R3 |
| 46 | `:seon.schedule/invalid-task-owner-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.schedule/invalid-task-owner class marker"} [:seon.schedule/invalid-task-owner :seon.schedule/invalid-task-owner] [:seon.error/message :seon.error/message]]` | R3 |
| 48 | `:seon.schedule/invalid-terminal-arm-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.schedule/invalid-terminal-arm class marker"} [:seon.schedule/invalid-terminal-arm :seon.schedule/invalid-terminal-arm] [:seon.error/message :seon.error/message]]` | R3 |
| 50 | `:seon.schedule/missing-execution-handle-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.schedule/missing-execution-handle class marker"} [:seon.schedule/missing-execution-handle :seon.schedule/missing-execution-handle] [:seon.error/message :seon.error/message]]` | R3 |
| 52 | `:seon.schedule/missing-receipt-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.schedule/missing-receipt class marker"} [:seon.schedule/missing-receipt :seon.schedule/missing-receipt] [:seon.error/message :seon.error/message]]` | R3 |
| 54 | `:seon.schedule/unresolved-handler-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.schedule/unresolved-handler class marker"} [:seon.schedule/unresolved-handler :seon.schedule/unresolved-handler] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.schema.datahike.edn

| Line | Source | Replacement |
|---:|---|---|
| 2 | `:seon.schema.datahike/literal-not-storable-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a Malli literal with no native Datahike value type"} [:seon.schema.datahike/literal-not-storable :seon.schema.datahike/literal-not-storable] [:seon.error/message :seon.error/message]]` | R3 |
| 4 | `:seon.schema.datahike/enum-not-storable-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a Malli enum whose members are not storable keywords"} [:seon.schema.datahike/enum-not-storable :seon.schema.datahike/enum-not-storable] [:seon.error/message :seon.error/message]]` | R3 |
| 6 | `:seon.schema.datahike/nilable-attribute-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify a stored attribute whose schema permits nil"} [:seon.schema.datahike/nilable-attribute :seon.schema.datahike/nilable-attribute] [:seon.error/message :seon.error/message]]` | R3 |
| 8 | `:seon.schema.datahike/value-type-unavailable-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a Malli form with no Datahike value type"} [:seon.schema.datahike/value-type-unavailable :seon.schema.datahike/value-type-unavailable] [:seon.error/message :seon.error/message]]` | R3 |
| 10 | `:seon.schema.datahike/attribute-absent-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify an attribute with no registered schema"} [:seon.schema.datahike/attribute-absent :seon.schema.datahike/attribute-absent] [:seon.error/message :seon.error/message]]` | R3 |
| 12 | `:seon.schema.datahike/invalid-secondary-attribute-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify a secondary-only attribute that is not float-valued"} [:seon.schema.datahike/invalid-secondary-attribute :seon.schema.datahike/invalid-secondary-attribute] [:seon.error/message :seon.error/message]]` | R3 |
| 14 | `:seon.schema.datahike/schema-invalid-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify an attribute whose derived Datahike schema is invalid"} [:seon.schema.datahike/schema-invalid :seon.schema.datahike/schema-invalid] [:seon.error/message :seon.error/message]]` | R3 |
| 16 | `:seon.schema.datahike/storage-not-string-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify an EDN-backed attribute without string storage"} [:seon.schema.datahike/storage-not-string :seon.schema.datahike/storage-not-string] [:seon.error/message :seon.error/message]]` | R3 |
| 18 | `:seon.schema.datahike/malformed-edn-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify an EDN-backed attribute containing unreadable data"} [:seon.schema.datahike/malformed-edn :seon.schema.datahike/malformed-edn] [:seon.error/message :seon.error/message]]` | R3 |
| 20 | `:seon.schema.datahike/noncanonical-edn-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify an EDN-backed attribute containing a noncanonical value"} [:seon.schema.datahike/noncanonical-edn :seon.schema.datahike/noncanonical-edn] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.schema.edn

| Line | Source | Replacement |
|---:|---|---|
| 94 | `:invalid-identity-projection-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark an invalid identity-only projection function"} [:seon.schema/invalid-identity-projection :seon.schema/invalid-identity-projection] [:seon.error/message :seon.error/message]]` | R3 |
| 115 | `:cyclic-reference-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the schema participating in a cyclic reference"} [:seon.schema/cyclic-reference :seon.schema/cyclic-reference] [:seon.error/message :seon.error/message]]` | R3 |
| 117 | `:unresolved-predicate-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the predicate with no admitted callable"} [:seon.schema/unresolved-predicate :seon.schema/unresolved-predicate] [:seon.error/message :seon.error/message]]` | R3 |
| 119 | `:noncanonical-definition-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify a durable schema definition that is not canonical EDN"} [:seon.schema/noncanonical-definition :seon.schema/noncanonical-definition] [:seon.error/message :seon.error/message]]` | R3 |
| 121 | `:noncanonical-projection-data-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark noncanonical data in a schema projection fingerprint"} [:seon.schema/noncanonical-projection-data :seon.schema/noncanonical-projection-data] [:seon.error/message :seon.error/message]]` | R3 |
| 123 | `:unproved-predicate-purity-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the predicate whose purity is not proved"} [:seon.schema/unproved-predicate-purity :seon.schema/unproved-predicate-purity] [:seon.error/message :seon.error/message]]` | R3 |
| 125 | `:unreadable-form-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the schema whose authored form is unreadable"} [:seon.schema/unreadable-form :seon.schema/unreadable-form] [:seon.error/message :seon.error/message]]` | R3 |
| 127 | `:non-round-tripping-form-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the schema whose form does not round-trip as EDN"} [:seon.schema/non-round-tripping-form :seon.schema/non-round-tripping-form] [:seon.error/message :seon.error/message]]` | R3 |
| 129 | `:unregister-outside-delta-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the schema unregistration requested outside an evaluation delta"} [:seon.schema/unregister-outside-delta :seon.schema/unregister-outside-delta] [:seon.error/message :seon.error/message]]` | R3 |
| 131 | `:malformed-projection-row-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a committed projection row with an invalid shape"} [:seon.schema/malformed-projection-row :seon.schema/malformed-projection-row] [:seon.error/message :seon.error/message]]` | R3 |
| 133 | `:malformed-projection-form-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark an unreadable form in a committed projection row"} [:seon.schema/malformed-projection-form :seon.schema/malformed-projection-form] [:seon.error/message :seon.error/message]]` | R3 |
| 135 | `:duplicate-projection-row-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the duplicated committed projection identity"} [:seon.schema/duplicate-projection-row :seon.schema/duplicate-projection-row] [:seon.error/message :seon.error/message]]` | R3 |
| 137 | `:malformed-projection-identity-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a committed projection identity with an invalid shape"} [:seon.schema/malformed-projection-identity :seon.schema/malformed-projection-identity] [:seon.error/message :seon.error/message]]` | R3 |
| 139 | `:malformed-artifact-export-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a malformed export in a committed artifact"} [:seon.schema/malformed-artifact-export :seon.schema/malformed-artifact-export] [:seon.error/message :seon.error/message]]` | R3 |
| 141 | `:schema-in-use-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the schema still referenced by installed contracts"} [:seon.schema/schema-in-use :seon.schema/schema-in-use] [:seon.error/message :seon.error/message]]` | R3 |
| 143 | `:unknown-shape-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the unknown projected map schema"} [:seon.schema/unknown-shape :seon.schema/unknown-shape] [:seon.error/message :seon.error/message]]` | R3 |
| 145 | `:undefined-contract-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the function whose contract references an undefined schema"} [:seon.schema/undefined-contract :seon.schema/undefined-contract] [:seon.error/message :seon.error/message]]` | R3 |
| 147 | `:incomplete-predicate-contract-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the predicate contract missing a complete callable declaration"} [:seon.schema/incomplete-predicate-contract :seon.schema/incomplete-predicate-contract] [:seon.error/message :seon.error/message]]` | R3 |
| 149 | `:nilable-map-value-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the map schema that permits a stored nil value"} [:seon.schema/nilable-map-value :seon.schema/nilable-map-value] [:seon.error/message :seon.error/message]]` | R3 |
| 151 | `:nilable-return-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the function whose durable return contract permits nil"} [:seon.schema/nilable-return :seon.schema/nilable-return] [:seon.error/message :seon.error/message]]` | R3 |
| 153 | `:invalid-schema-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the definition that is not a valid Malli schema"} [:seon.schema/invalid-schema :seon.schema/invalid-schema] [:seon.error/message :seon.error/message]]` | R3 |
| 155 | `:nilable-value-schema-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify a durable value schema that permits nil"} [:seon.schema/nilable-value-schema :seon.schema/nilable-value-schema] [:seon.error/message :seon.error/message]]` | R3 |
| 157 | `:single-segment-namespace-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the single-segment namespace that cannot own durable data"} [:seon.schema/single-segment-namespace :seon.schema/single-segment-namespace] [:seon.error/message :seon.error/message]]` | R3 |
| 159 | `:seon.schema/missing-projection-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.schema/missing-projection class marker"} [:seon.schema/missing-projection :seon.schema/missing-projection] [:seon.error/message :seon.error/message]]` | R3 |
| 161 | `:seon.schema/render-contract-incoherent-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.schema/render-contract-incoherent class marker"} [:seon.schema/render-contract-incoherent :seon.schema/render-contract-incoherent] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.schema.edn.edn

| Line | Source | Replacement |
|---:|---|---|
| 11 | `:seon.schema.edn/unreadable-file-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the unreadable schema resource"} [:seon.schema.edn/unreadable-file :seon.schema.edn/unreadable-file] [:seon.error/message :seon.error/message]]` | R3 |
| 13 | `:seon.schema.edn/duplicate-attribute-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the attribute declared by more than one resource"} [:seon.schema.edn/duplicate-attribute :seon.schema.edn/duplicate-attribute] [:seon.error/message :seon.error/message]]` | R3 |
| 15 | `:seon.schema.edn/not-a-map-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the schema resource whose root is not a map"} [:seon.schema.edn/not-a-map :seon.schema.edn/not-a-map] [:seon.error/message :seon.error/message]]` | R3 |
| 17 | `:seon.schema.edn/unsafe-namespace-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify an attribute in an unsafe schema namespace"} [:seon.schema.edn/unsafe-namespace :seon.schema.edn/unsafe-namespace] [:seon.error/message :seon.error/message]]` | R3 |
| 19 | `:seon.schema.edn/misplaced-attribute-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the attribute declared outside its flat namespace file"} [:seon.schema.edn/misplaced-attribute :seon.schema.edn/misplaced-attribute] [:seon.error/message :seon.error/message]]` | R3 |
| 21 | `:seon.schema.edn/dishonest-generator-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the predicate schema with a dishonest generator"} [:seon.schema.edn/dishonest-generator :seon.schema.edn/dishonest-generator] [:seon.error/message :seon.error/message]]` | R3 |
| 23 | `:seon.schema.edn/unregistered-predicate-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the predicate that is not registered"} [:seon.schema.edn/unregistered-predicate :seon.schema.edn/unregistered-predicate] [:seon.error/message :seon.error/message]]` | R3 |
| 25 | `:seon.schema.edn/unresolved-reference-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the schema reference that cannot be resolved"} [:seon.schema.edn/unresolved-reference :seon.schema.edn/unresolved-reference] [:seon.error/message :seon.error/message]]}` | R3 |

### resources/seon/schemas/seon.schema.shape.edn

| Line | Source | Replacement |
|---:|---|---|
| 33 | `:seon.schema.shape/fingerprint-collision-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.schema.shape/fingerprint-collision class marker"} [:seon.schema.shape/fingerprint-collision :seon.schema.shape/fingerprint-collision] [:seon.error/message :seon.error/message]]` | R3 |
| 35 | `:seon.schema.shape/noncanonical-compiled-form-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.schema.shape/noncanonical-compiled-form class marker"} [:seon.schema.shape/noncanonical-compiled-form :seon.schema.shape/noncanonical-compiled-form] [:seon.error/message :seon.error/message]]` | R3 |
| 37 | `:seon.schema.shape/unsupported-map-key-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.schema.shape/unsupported-map-key class marker"} [:seon.schema.shape/unsupported-map-key :seon.schema.shape/unsupported-map-key] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.sci.admit.edn

| Line | Source | Replacement |
|---:|---|---|
| 81 | `:projection-failed-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a value that could not be projected into admitted data"} [:seon.sci.admit/projection-failed :seon.sci.admit/projection-failed] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.sci.eval.edn

| Line | Source | Replacement |
|---:|---|---|
| 54 | `{:seon.error/class true,` | R3 |
| 64 | `{:seon.error/class true,` | R3 |
| 75 | `{:seon.error/class true,` | R3 |
| 95 | `{:seon.error/class true,` | R3 |
| 105 | `{:seon.error/class true,` | R3 |
| 118 | `{:seon.error/class true,` | R3 |
| 191 | `{:seon.error/class true,` | R3 |
| 237 | `{:seon.error/class true,` | R3 |
| 250 | `{:seon.error/class true,` | R3 |
| 266 | `{:seon.error/class true,` | R3 |

### resources/seon/schemas/seon.sci.kernel.edn

| Line | Source | Replacement |
|---:|---|---|
| 13 | `:already-armed-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a different SCI context presented to an active arm"} [:seon.sci.kernel/already-armed :seon.sci.kernel/already-armed] [:seon.error/message :seon.error/message]]` | R3 |
| 15 | `:missing-function-installer-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark an SCI context with no function installer"} [:seon.sci.kernel/missing-function-installer :seon.sci.kernel/missing-function-installer] [:seon.error/message :seon.error/message]]` | R3 |
| 17 | `:missing-interrupt-guard-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark an SCI context with no interrupt function"} [:seon.sci.kernel/missing-interrupt-guard :seon.sci.kernel/missing-interrupt-guard] [:seon.error/message :seon.error/message]]` | R3 |
| 19 | `:unresolved-invocation-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the function that could not be resolved for invocation"} [:seon.sci.kernel/unresolved-invocation :seon.sci.kernel/unresolved-invocation] [:seon.error/message :seon.error/message]]` | R3 |
| 21 | `:failure-admission-failed-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark a kernel failure that could not be admitted as data"} [:seon.sci.kernel/failure-admission-failed :seon.sci.kernel/failure-admission-failed] [:seon.error/message :seon.error/message]]` | R3 |
| 23 | `:time-limit-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the invocation cut by its time limit"} [:seon.sci.kernel/time-limit :seon.sci.kernel/time-limit] [:seon.error/message :seon.error/message]]` | R3 |
| 25 | `:invocation-failed-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the invocation that failed before returning a value"} [:seon.sci.kernel/invocation-failed :seon.sci.kernel/invocation-failed] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.sci.reader.edn

| Line | Source | Replacement |
|---:|---|---|
| 14 | `:seon.sci.reader/fabricated-response-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify a fabricated REPL response in agent source"} [:seon.sci.reader/fabricated-response :seon.sci.reader/fabricated-response] [:seon.error/message :seon.error/message]]` | R3 |
| 16 | `:seon.sci.reader/unreadable-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark unreadable Clojure source"} [:seon.sci.reader/unreadable :seon.sci.reader/unreadable] [:seon.error/message :seon.error/message]]` | R3 |
| 18 | `:seon.sci.reader/oversize-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark Clojure source that exceeds its reader bound"} [:seon.sci.reader/oversize :seon.sci.reader/oversize] [:seon.error/message :seon.error/message]]` | R3 |
| 20 | `:seon.sci.reader/refused-tag-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the reader tag that was refused"} [:seon.sci.reader/refused-tag :seon.sci.reader/refused-tag] [:seon.error/message :seon.error/message]]` | R3 |
| 22 | `:seon.sci.reader/keyword-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must mark an invalid keyword read from agent source"} [:seon.sci.reader/keyword :seon.sci.reader/keyword] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.search.edn

| Line | Source | Replacement |
|---:|---|---|
| 47 | `{:seon.error/class true` | R3 |
| 56 | `:seon.search/missing-resource-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.search/missing-resource class marker"} [:seon.search/missing-resource :seon.search/missing-resource] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.test.accretion.edn

| Line | Source | Replacement |
|---:|---|---|
| 101 | `{:seon.error/class true` | R3 |
| 107 | `[:seon.error/kind :seon.error/kind]` | R4 |

### resources/seon/schemas/seon.test.edn

| Line | Source | Replacement |
|---:|---|---|
| 50 | `{:seon.error/class true` | R3 |
| 240 | `[:map {:seon.error/class true :seon.render/ai seon.error/render-ai` | R3 |

### resources/seon/schemas/seon.test.run.edn

| Line | Source | Replacement |
|---:|---|---|
| 53 | `[:map {:seon.error/class true` | R3 |
| 61 | `[:map {:seon.error/class true` | R3 |

### resources/seon/schemas/seon.test.runner.edn

| Line | Source | Replacement |
|---:|---|---|
| 64 | `:invalid-silence-seconds-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the invalid test-runner silence bound"} [:seon.test.runner/invalid-silence-seconds :seon.test.runner/invalid-silence-seconds] [:seon.error/message :seon.error/message]]` | R3 |
| 66 | `:invalid-long-reason-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the invalid long-test reason"} [:seon.test.runner/invalid-long-reason :seon.test.runner/invalid-long-reason] [:seon.error/message :seon.error/message]]` | R3 |
| 68 | `:long-test-ns-hook-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the namespace whose long-test hook failed"} [:seon.test.runner/long-test-ns-hook :seon.test.runner/long-test-ns-hook] [:seon.error/message :seon.error/message]]` | R3 |
| 70 | `:default-cluster-refused-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the default cluster refused by the test runner"} [:seon.test.runner/default-cluster-refused :seon.test.runner/default-cluster-refused] [:seon.error/message :seon.error/message]]` | R3 |
| 72 | `:invalid-selection-mode-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must identify the invalid test selection mode"} [:seon.test.runner/invalid-selection-mode :seon.test.runner/invalid-selection-mode] [:seon.error/message :seon.error/message]]` | R3 |
| 74 | `:seon.test.runner/invalid-marker-reason-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.test.runner/invalid-marker-reason class marker"} [:seon.test.runner/invalid-marker-reason :seon.test.runner/invalid-marker-reason] [:seon.error/message :seon.error/message]]` | R3 |
| 76 | `:seon.test.runner/process-tree-exit-backstop-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.test.runner/process-tree-exit-backstop class marker"} [:seon.test.runner/process-tree-exit-backstop :seon.test.runner/process-tree-exit-backstop] [:seon.error/message :seon.error/message]]` | R3 |
| 78 | `:seon.test.runner/unknown-worker-command-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.test.runner/unknown-worker-command class marker"} [:seon.test.runner/unknown-worker-command :seon.test.runner/unknown-worker-command] [:seon.error/message :seon.error/message]]` | R3 |
| 80 | `:seon.test.runner/unresolved-test-var-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.test.runner/unresolved-test-var class marker"} [:seon.test.runner/unresolved-test-var :seon.test.runner/unresolved-test-var] [:seon.error/message :seon.error/message]]` | R3 |
| 82 | `:seon.test.runner/worker-launch-failure-error [:map {:seon.error/class true :seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html :error/message "must carry the :seon.test.runner/worker-launch-failure class marker"} [:seon.test.runner/worker-launch-failure :seon.test.runner/worker-launch-failure] [:seon.error/message :seon.error/message]]` | R3 |

### resources/seon/schemas/seon.turn.edn

| Line | Source | Replacement |
|---:|---|---|
| 32 | `{:seon.error/class true,` | R3 |
| 166 | `{:seon.error/class true,` | R3 |

### resources/seon/schemas/seon.turn.loop.edn

| Line | Source | Replacement |
|---:|---|---|
| 73 | `[:seon.error/kind {:optional true} :seon.error/kind]` | R4 |
| 90 | `{:seon.error/class true,` | R3 |
| 144 | `{:seon.error/class true,` | R3 |
| 158 | `{:seon.error/class true,` | R3 |
| 186 | `{:seon.error/class true,` | R3 |

### script/seon/dev/changed_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 166 | `(:seon.error/kind (ex-data error))` | R2 |
| 167 | `(assoc :seon.error/kind (:seon.error/kind (ex-data error))` | R1 |
| 212 | `(:seon.error/kind (ex-data failure)))` | R2 |

### script/seon/dev/dependency_digest.clj

| Line | Source | Replacement |
|---:|---|---|
| 107 | `{:seon.error/kind :seon.dev-cache/dependency-pins-unavailable` | R1 |

### script/seon/dev/issues.clj

| Line | Source | Replacement |
|---:|---|---|
| 24 | `(not (:seon.error/kind result)) (empty? (:seon.issue/refusals result)))` | R1 |

### script/seon/dev/mcp.clj

| Line | Source | Replacement |
|---:|---|---|
| 616 | `{:seon.error/kind :seon.dev.mcp/cluster-degraded` | R1 |

### script/seon/fresh_operator.clj

| Line | Source | Replacement |
|---:|---|---|
| 50 | `{:seon.error/kind` | R5 |
| 87 | `{:seon.error/kind` | R5 |
| 935 | `{:seon.error/kind :seon.error/unknown` | R1 |
| 982 | `{:seon.error/kind :seon.error/unknown` | R1 |
| 996 | `{:seon.error/kind :seon.error/unknown` | R1 |
| 1007 | `{:seon.error/kind :seon.error/unknown` | R1 |
| 1016 | `(:seon.error/kind observation)` | R2 |
| 1020 | `{:seon.error/kind :seon.error/unknown` | R1 |
| 1094 | `{:seon.error/kind :seon.error/unknown` | R1 |
| 1176 | `{:seon.error/kind :seon.fresh-operator/prepl-reply-unreadable` | R1 |
| 1824 | `{:seon.error/kind` | R5 |
| 1867 | `{:seon.error/kind` | R5 |
| 1886 | `{:seon.error/kind` | R5 |
| 1912 | `(if (:seon.error/kind dials#)` | R2 |
| 1928 | `(if (:seon.error/kind dials#)` | R2 |
| 1975 | `(if (= :sweep-in-progress (:seon.error/kind data#))` | R2 |
| 1984 | `:seon.error/kind :sweep-in-progress` | R1 |
| 2018 | `(:seon.error/kind ~instance))` | R2 |
| 2033 | `(when (:seon.error/kind rotation#)` | R2 |
| 2049 | `(:seon.error/kind failure-data#)}` | R2 |
| 2051 | `(:seon.error/kind failure-data#))` | R2 |
| 2085 | `(if (= :sweep-in-progress (:seon.error/kind ~instance))` | R2 |
| 2158 | `{:seon.error/kind` | R5 |
| 2186 | `{:seon.error/kind` | R5 |
| 2194 | `{:seon.error/kind` | R5 |
| 2290 | `{:seon.error/kind` | R5 |
| 2309 | `{:seon.error/kind` | R5 |
| 2326 | `(assoc :seon.error/kind` | R5 |
| 2331 | `{:seon.error/kind` | R5 |
| 2341 | `{:seon.error/kind` | R5 |
| 2423 | `(:seon.error/kind start-result))` | R2 |
| 2679 | `{:seon.error/kind :seon.fresh-operator/publication-failed` | R1 |
| 2753 | `(if (:seon.error/kind loader#)` | R2 |
| 2863 | `{:seon.error/kind :seon.fresh-operator/live-advertisement-unavailable` | R1 |
| 2875 | `_ (when (:seon.error/kind result)` | R2 |
| 2924 | `{:seon.error/kind` | R5 |
| 2925 | `(or (:seon.error/kind (ex-data error))` | R1 |
| 3088 | `{:seon.error/kind :seon.error/unknown` | R1 |
| 3138 | `(if (:seon.error/kind test-statuses)` | R2 |
| 3670 | `:seon.error/kind` | R5 |
| 3671 | `(or (:seon.error/kind (ex-data error))` | R1 |

### src/my/background.clj

| Line | Source | Replacement |
|---:|---|---|
| 11 | `{:seon.error/kind ::invalid-call` | R1 |

### src/my/program.clj

| Line | Source | Replacement |
|---:|---|---|
| 16 | `(if (:seon.error/kind value)` | R2 |
| 23 | `(if (:seon.error/kind (ex-data failure))` | R2 |
| 26 | `{:seon.error/kind :seon.program/read-refused` | R1 |
| 58 | `{:seon.error/kind :seon.program/not-found` | R1 |
| 359 | `{:seon.error/kind :seon.program/declaration-refused` | R1 |
| 387 | `{:seon.error/kind :seon.program/declaration-refused` | R1 |
| 574 | `(and report (not (:seon.error/kind report))))` | R1 |
| 577 | `{:seon.error/kind :seon.program/declaration-refused` | R1 |

### src/my/test.clj

| Line | Source | Replacement |
|---:|---|---|
| 22 | `(if (:seon.error/kind environment)` | R2 |
| 52 | `(if (:seon.error/kind symbols#)` | R2 |

### src/seon/agent.clj

| Line | Source | Replacement |
|---:|---|---|
| 21 | `(:seon.error/kind row) row` | R2 |
| 24 | `:seon.error/kind :seon.agent/no-such-agent` | R1 |
| 41 | `(:seon.error/kind row) row` | R2 |
| 44 | `:seon.error/kind :seon.agent/no-such-agent` | R1 |
| 60 | `(:seon.error/kind row) (throw (ex-info (:seon.error/message row) row))` | R2 |
| 63 | `{:seon.error/kind :seon.agent/no-such-agent` | R1 |
| 88 | `{:seon.error/kind :seon.agent/no-such-agent` | R1 |
| 92 | `_ (when (:seon.error/kind attributes)` | R2 |
| 113 | `(if (:seon.error/kind result)` | R2 |
| 128 | `refusal (some #(when (:seon.error/kind %) %)` | R2 |
| 132 | `(nil? defaults) {:seon.error/kind :seon.config/required-absent` | R2 |
| 180 | `(:seon.error/kind attributes) attributes` | R2 |
| 181 | `(:seon.error/kind defaults) defaults` | R2 |

### src/seon/ai.clj

| Line | Source | Replacement |
|---:|---|---|
| 330 | `(:seon.error/kind row) row` | R2 |
| 335 | `:seon.error/kind :seon.schema/unknown-shape` | R1 |
| 345 | `(if (:seon.error/kind attributes)` | R2 |
| 349 | `(if (:seon.error/kind row)` | R2 |
| 650 | `{:seon.error/kind ::invalid-extra-body` | R1 |
| 658 | `{:seon.error/kind ::invalid-extra-body` | R1 |
| 693 | `(if (:seon.error/kind extra)` | R2 |
| 701 | `{:seon.error/kind ::extra-body-conflict` | R1 |
| 723 | `{:seon.error/kind ::unparseable-body` | R1 |
| 797 | `(if (:seon.error/kind document)` | R2 |
| 854 | `(if (:seon.error/kind next-snapshot)` | R2 |
| 888 | `{:seon.error/kind ::unparseable-body` | R1 |
| 895 | `{:seon.error/kind ::unparseable-body` | R1 |
| 905 | `{:seon.error/kind ::reasoning-without-answer` | R1 |
| 917 | `{:seon.error/kind ::token-starvation` | R1 |
| 934 | `{:seon.error/kind ::unparseable-body` | R1 |
| 954 | `{:seon.error/kind ::unparseable-body` | R1 |
| 1189 | `{:seon.error/kind ::stream-truncated` | R1 |
| 1276 | `(:seon.error/kind snapshot) snapshot` | R2 |
| 1376 | `(if (:seon.error/kind completion)` | R2 |
| 1395 | `{:seon.error/kind ::unparseable-body` | R1 |
| 1411 | `{:seon.error/kind ::provider-error` | R1 |
| 1432 | `{:seon.error/kind ::timeout` | R1 |
| 1445 | `{:seon.error/kind ::transport-failure` | R1 |
| 1482 | `(:seon.error/kind body)` | R2 |
| 1494 | `{:seon.error/kind ::no-credential` | R1 |

### src/seon/artifact.clj

| Line | Source | Replacement |
|---:|---|---|
| 16 | `:seon.error/kind ::refused` | R1 |

### src/seon/await.clj

| Line | Source | Replacement |
|---:|---|---|
| 44 | `{:seon.error/kind cause` | R1 |

### src/seon/background.clj

| Line | Source | Replacement |
|---:|---|---|
| 46 | `{:seon.error/kind :my.background/invalid-result` | R1 |
| 72 | `{:seon.error/kind :my.background/missing-result` | R1 |
| 88 | `(if (or (:seon.error/kind descriptor)` | R2 |
| 93 | `(if (:seon.error/kind wait-value)` | R2 |

### src/seon/blob.clj

| Line | Source | Replacement |
|---:|---|---|
| 73 | `{:seon.error/kind :core-bug` | R1 |
| 87 | `{:seon.error/kind :core-bug` | R1 |
| 133 | `{:seon.error/kind :core-bug` | R1 |
| 238 | `{:seon.error/kind :core-bug` | R1 |
| 364 | `{:seon.error/kind :core-bug` | R1 |

### src/seon/bootstrap.clj

| Line | Source | Replacement |
|---:|---|---|
| 117 | `:seon.error/kind :seon.agent/no-such-agent` | R1 |
| 379 | `(:seon.error/kind budget) budget` | R2 |
| 383 | `:seon.error/kind :seon.config/required-absent` | R1 |
| 425 | `(if (:seon.error/kind lookup)` | R2 |
| 577 | `(:seon.error/kind root)` | R2 |
| 582 | `:seon.error/kind ::root-acquisition-empty` | R1 |
| 594 | `subjects (when-not (:seon.error/kind budget)` | R2 |
| 597 | `(:seon.error/kind budget) budget` | R2 |
| 598 | `(:seon.error/kind subjects) subjects` | R2 |
| 654 | `(if (:seon.error/kind pull)` | R2 |
| 682 | `:seon.error/kind ::prefix-drift` | R1 |
| 703 | `:seon.error/kind ::prefix-drift` | R1 |

### src/seon/call_preparation.clj

| Line | Source | Replacement |
|---:|---|---|
| 112 | `:seon.error/kind kind` | R1 |
| 119 | `(keyword? (:seon.error/kind value))` | R2 |

### src/seon/cluster.clj

| Line | Source | Replacement |
|---:|---|---|
| 110 | `{:seon.error/kind ::source-observer-closed` | R1 |
| 184 | `(if (:seon.error/kind unit)` | R2 |
| 303 | `{:seon.error/kind ::mcp-missing-projection` | R1 |
| 326 | `kind (or (get-in cause-entry [:data :seon.error/kind])` | R2 |
| 336 | `:seon.error/kind kind` | R1 |
| 355 | `{:seon.error/kind :seon.dev.mcp/projection-failed` | R1 |
| 369 | `caps (when-not (:seon.error/kind effective)` | R2 |
| 372 | `(:seon.error/kind effective)` | R2 |
| 375 | `(:seon.error/kind caps)` | R2 |
| 447 | `(when (:seon.error/kind result)` | R2 |
| 451 | `{:seon.error/kind :core-bug` | R1 |
| 545 | `(if (:seon.error/kind effective)` | R2 |
| 572 | `{:seon.error/kind :seon.dev.mcp/value-not-found` | R1 |
| 576 | `{:seon.error/kind :seon.dev.mcp/remainder-not-retrievable` | R1 |
| 618 | `{:seon.error/kind :seon.boot/refused` | R1 |
| 702 | `Refuses (throws ex-info {:seon.error/kind :seon.boot/refused ...}) when a` | R2 |
| 877 | `{:seon.error/kind :seon.operator/low-disk-space` | R1 |
| 1283 | `(:seon.error/kind result)` | R2 |
| 1837 | `{:seon.error/kind ::source-refresh-acquisition-timeout` | R1 |
| 2102 | `(when (:seon.error/kind digest)` | R2 |
| 2108 | `(when (:seon.error/kind files)` | R2 |
| 2262 | `(if (and (= :seon.fn/index-refused (:seon.error/kind (ex-data failure)))` | R2 |
| 2367 | `(when (:seon.error/kind edges)` | R2 |
| 2388 | `_ (when (:seon.error/kind effective)` | R2 |
| 2542 | `_ (when (:seon.error/kind effective)` | R2 |
| 2556 | `(when (or (:seon.error/kind result)` | R2 |
| 2721 | `(when (:seon.error/kind result)` | R2 |
| 2844 | `(if (:seon.error/kind transaction-result)` | R2 |
| 2873 | `{:seon.error/kind :seon.agent/creation-incomplete` | R1 |
| 3428 | `{:seon.error/kind` | R5 |
| 3436 | `{:seon.error/kind` | R5 |
| 3580 | `_ (when-let [kind (:seon.error/kind start-permit)]` | R1 |
| 3765 | `{:seon.error/kind :seon.boot/refused` | R1 |

### src/seon/cluster/agent.clj

| Line | Source | Replacement |
|---:|---|---|
| 146 | `(when (:seon.error/kind steward)` | R2 |
| 213 | `(if (:seon.error/kind agent-data)` | R2 |
| 264 | `(if (:seon.error/kind database)` | R2 |
| 270 | `(if (:seon.error/kind agent-data)` | R2 |
| 298 | `(if (:seon.error/kind agents)` | R2 |
| 332 | `(:seon.error/kind rendered)` | R2 |
| 361 | `(when (and situation (not (:seon.error/kind situation)))` | R2 |
| 419 | `(if (:seon.error/kind ids) [] (vec (sort ids)))))` | R2 |
| 579 | `(:seon.error/kind namespace-name) namespace-name` | R2 |
| 583 | `{:seon.error/kind :seon.agent/no-such-agent` | R1 |
| 599 | `(if (:seon.error/kind sources)` | R2 |
| 614 | `(if (:seon.error/kind outcome)` | R2 |
| 622 | `{:seon.error/kind :seon.agent/source-submission-undeliverable` | R1 |
| 693 | `{:seon.error/kind :seon.agent/missing-context-state` | R1 |
| 739 | `{:seon.error/kind :seon.agent/no-such-agent` | R1 |
| 1020 | `(when (:seon.error/kind result)` | R2 |
| 1024 | `{:seon.error/kind :seon.agent/supervision-not-committed` | R1 |

### src/seon/cluster/export.clj

| Line | Source | Replacement |
|---:|---|---|
| 46 | `\`{:seon.error/kind ::refused ::rule <which>}\`, matching B0/B1` | R1 |
| 99 | `:seon.error/kind ::refused` | R1 |
| 213 | `(:seon.error/kind (ex-data failure)))` | R2 |

### src/seon/cluster/message.clj

| Line | Source | Replacement |
|---:|---|---|
| 157 | `{:seon.error/kind :seon.message/unknown-recipient :seon.message/unknown-recipient id` | R1 |
| 160 | `{:seon.error/kind :seon.message/blank-content :seon.message/blank-content true` | R1 |
| 163 | `{:seon.error/kind :seon.message/content-too-large` | R1 |
| 188 | `:seon.error/values [{:seon.error/kind :seon.message/no-limit :seon.message/no-limit true` | R1 |
| 192 | `:seon.error/values [{:seon.error/kind :seon.message/chain-limit :seon.message/chain-limit max-chain` | R1 |
| 201 | `{:seon.error/kind :seon.message/unknown-recipient` | R1 |
| 241 | `(when-not (:seon.error/kind result)` | R2 |
| 304 | `(if (:seon.error/kind unit)` | R2 |
| 488 | `(and (map? value) (keyword? (:seon.error/kind value))))` | R2 |
| 587 | `{:seon.error/kind :my.message/not-found` | R1 |
| 603 | `{:seon.error/kind :my.message/no-recipient` | R1 |
| 609 | `{:seon.error/kind :my.message/no-content` | R1 |
| 616 | `{:seon.error/kind :my.message/no-about` | R1 |
| 652 | `{:seon.error/kind :seon.message/no-limit` | R1 |
| 703 | `{:seon.error/kind :my.message/no-recipient` | R1 |
| 709 | `{:seon.error/kind :my.message/no-assignment` | R1 |
| 715 | `{:seon.error/kind :my.message/no-reason` | R1 |

### src/seon/cluster/process.clj

| Line | Source | Replacement |
|---:|---|---|
| 25 | `{:seon.error/kind :seon.cluster.process/start-instant-unavailable` | R1 |

### src/seon/cluster/prompt.clj

| Line | Source | Replacement |
|---:|---|---|
| 48 | `(:seon.error/kind cluster-name)` | R2 |
| 52 | `{:seon.error/kind ::missing-cluster` | R1 |
| 62 | `:seon.error/kind ::missing-config` | R1 |
| 88 | `(sort-by (juxt second first) (if (:seon.error/kind rows) [] rows)))` | R2 |
| 130 | `(if (:seon.error/kind settings)` | R2 |
| 139 | `{:seon.error/kind ::refused` | R1 |
| 331 | `{:seon.error/kind ::capture-mismatch` | R1 |
| 343 | `acquired (if (:seon.error/kind profile) profile` | R2 |
| 350 | `(:seon.error/kind acquired)` | R2 |
| 400 | `(if (:seon.error/kind settings)` | R2 |

### src/seon/cluster/registry.clj

| Line | Source | Replacement |
|---:|---|---|
| 40 | `\`{:seon.error/kind ::refused ::rule <which>}\`, matching B0/B1` | R1 |
| 85 | `:seon.error/kind ::refused` | R1 |
| 359 | `history-db (if (:seon.error/kind history-view)` | R2 |
| 396 | `{:seon.error/kind` | R5 |
| 465 | `{:seon.error/kind` | R5 |
| 511 | `{:seon.error/kind` | R5 |
| 533 | `{:seon.error/kind` | R5 |

### src/seon/cluster/reply.clj

| Line | Source | Replacement |
|---:|---|---|
| 51 | `{:seon.error/kind kind` | R1 |
| 336 | `(:seon.error/kind admission-events))` | R2 |
| 357 | `(if (= :seon.sci.reader/refused-tag (:seon.error/kind events))` | R2 |

### src/seon/cluster/source.clj

| Line | Source | Replacement |
|---:|---|---|
| 85 | `:seon.error/kind ::refused` | R1 |
| 91 | `(when (:seon.error/kind result)` | R2 |
| 184 | `(when (:seon.error/kind report)` | R2 |
| 319 | `(when (:seon.error/kind rows)` | R2 |
| 353 | `(when (:seon.error/kind rows)` | R2 |
| 428 | `(if (:seon.error/kind result)` | R2 |
| 467 | `{:seon.error/kind ::recording-expired` | R1 |
| 499 | `{:seon.error/kind :seon.test/input-evidence-unavailable` | R1 |

### src/seon/cluster/status.clj

| Line | Source | Replacement |
|---:|---|---|
| 14 | `{:seon.error/kind :seon.cluster.status/unavailable` | R1 |

### src/seon/cluster/store.clj

| Line | Source | Replacement |
|---:|---|---|
| 114 | `{:seon.error/kind :core-bug` | R1 |
| 208 | `:seon.error/kind ::refused` | R1 |

### src/seon/cluster/wake.clj

| Line | Source | Replacement |
|---:|---|---|
| 240 | `{:seon.error/kind ::no-listened-attributes` | R1 |
| 247 | `{:seon.error/kind ::no-inside-attributes` | R1 |
| 255 | `{:seon.error/kind ::unindexed-listened-attribute` | R1 |
| 280 | `{:seon.error/kind ::no-arming-attributes` | R1 |
| 394 | `{:seon.error/kind ::undeliverable-wake` | R1 |

### src/seon/config.clj

| Line | Source | Replacement |
|---:|---|---|
| 128 | `(when (and (:seon.error/kind effective)` | R2 |
| 138 | `{:seon.error/kind ::missing-result-cap` | R1 |
| 207 | `(merge {:seon.error/kind ::refused` | R1 |
| 461 | `(when (:seon.error/kind pulled)` | R2 |
| 536 | `(if (:seon.error/kind transaction-result)` | R2 |
| 540 | `(when (:seon.error/kind result)` | R2 |
| 593 | `(if (:seon.error/kind row)` | R2 |
| 610 | `:seon.error/kind ::missing-effective` | R1 |

### src/seon/context.clj

| Line | Source | Replacement |
|---:|---|---|
| 66 | `{:seon.error/kind ::selection-refused` | R1 |
| 97 | `(:seon.error/kind agent-data) agent-data` | R2 |
| 114 | `(if (:seon.error/kind value)` | R2 |
| 335 | `(:seon.error/kind contribution) contribution` | R2 |
| 336 | `(:seon.error/kind agent-data) agent-data` | R2 |
| 337 | `(:seon.error/kind refreshed) refreshed` | R2 |
| 464 | `" (" (:seon.error/kind unit) ")"))]` | R6 |
| 487 | `(assoc :seon.error/kind (:seon.error/kind failure)` | R1 |
| 533 | `(assoc :seon.error/kind (:seon.error/kind failure)` | R1 |

### src/seon/db.clj

| Line | Source | Replacement |
|---:|---|---|
| 168 | `:seon.error/kind kind` | R1 |
| 178 | `(if (= :seon.schema/missing-projection (:seon.error/kind (ex-data error)))` | R2 |
| 191 | `(keyword? (:seon.error/kind value))` | R2 |
| 979 | `{:seon.error/kind ::unknown-read-operation` | R1 |
| 1157 | `{:seon.error/kind :seon.schema/missing-projection` | R1 |
| 1188 | `{:seon.error/kind ::unreadable-declarations` | R1 |
| 1347 | `{:seon.error/kind ::invalid-read` | R1 |
| 1378 | `{:seon.error/kind ::invalid-read` | R1 |
| 1392 | `{:seon.error/kind ::invalid-read` | R1 |
| 1457 | `{:seon.error/kind ::invalid-read` | R1 |
| 1548 | `{:seon.error/kind ::invalid-read` | R1 |
| 1825 | `{:seon.error/kind ::invalid-read` | R1 |
| 1881 | `{:seon.error/kind ::invalid-read` | R1 |
| 1986 | `{:seon.error/kind ::invalid-read` | R1 |
| 2117 | `{:seon.error/kind ::disagreeing-pull-schema` | R1 |
| 2181 | `{:seon.error/kind ::invalid-pulled-result` | R1 |
| 2268 | `{:seon.error/kind ::invalid-read` | R1 |
| 2701 | `:seon.error/kind ::diff-refused` | R1 |
| 3183 | `{:seon.error/kind ::rejected` | R1 |
| 3257 | `{:seon.error/kind ::invalid-write` | R1 |
| 3651 | `(if (:seon.error/kind rows)` | R2 |
| 3751 | `{:seon.error/kind ::invalid-write` | R1 |
| 3804 | `{:seon.error/kind ::invalid-write` | R1 |
| 3845 | `{:seon.error/kind ::invalid-write` | R1 |
| 3924 | `{:seon.error/kind ::invalid-write` | R1 |
| 4029 | `{:seon.error/kind :seon.db/retention-refused` | R1 |
| 4159 | `{:seon.error/kind ::write-bound-exceeded` | R1 |
| 4199 | `(some? (:seon.error/kind data))` | R2 |
| 4208 | `{:seon.error/kind :seon.db/unknown-failure` | R1 |
| 4225 | `{:seon.error/kind ::invalid-request` | R1 |

### src/seon/edit.clj

| Line | Source | Replacement |
|---:|---|---|
| 12 | `:seon.error/kind kind` | R1 |
| 148 | `(if (:seon.error/kind parsed)` | R2 |
| 150 | `:seon.error/kind error-kind` | R1 |
| 166 | `(not (:seon.error/kind` | R5 |
| 262 | `(if (or (:seon.error/kind reparsed)` | R2 |
| 284 | `(if (:seon.error/kind replacement)` | R2 |
| 292 | `(if (:seon.error/kind candidate)` | R2 |
| 310 | `(if (:seon.error/kind parsed)` | R2 |
| 322 | `(if (:seon.error/kind dispatch)` | R2 |

### src/seon/edit/jvm.clj

| Line | Source | Replacement |
|---:|---|---|
| 10 | `:seon.error/kind marker` | R1 |
| 24 | `(case (:seon.error/kind result)` | R2 |
| 90 | `(if (and (keyword? (:seon.error/kind classified))` | R2 |
| 101 | `(if (:seon.error/kind before)` | R2 |
| 109 | `(if (:seon.error/kind transformed)` | R2 |
| 120 | `(if (:seon.error/kind written)` | R2 |

### src/seon/effect.clj

| Line | Source | Replacement |
|---:|---|---|
| 171 | `:seon.error/kind kind` | R1 |
| 262 | `{:seon.error/kind :seon.effect/already-recorded` | R1 |
| 299 | `(and declaration (not (:seon.error/kind declaration)))` | R1 |
| 314 | `{:seon.error/kind :seon.effect/missing-receipt :seon.effect/missing-receipt true}))` | R1 |
| 320 | `{:seon.error/kind :seon.effect/already-settled :seon.effect/already-settled true}))` | R1 |
| 369 | `{:seon.error/kind :seon.effect/missing-receipt :seon.effect/missing-receipt true}))` | R1 |
| 375 | `{:seon.error/kind :seon.effect/already-settled :seon.effect/already-settled true}))` | R1 |
| 495 | `(when (:seon.error/kind result)` | R2 |
| 557 | `(when (and (map? result) (nil? (:seon.error/kind result)))` | R2 |
| 723 | `(:seon.error/kind background-limit)` | R2 |
| 780 | `(if (:seon.error/kind opened)` | R2 |
| 783 | `(if (:seon.error/kind` | R2 |

### src/seon/env.clj

| Line | Source | Replacement |
|---:|---|---|
| 88 | `{:seon.error/kind ::invalid-environment-state` | R1 |
| 101 | `{:seon.error/kind ::invalid-environment-replacement` | R1 |
| 157 | `{:seon.error/kind ::schema-absent :seon.env/schema-absent true})))` | R1 |
| 196 | `{:seon.error/kind ::incomplete-environment` | R1 |
| 207 | `{:seon.error/kind ::invalid-member` | R1 |
| 268 | `{:seon.error/kind ::absent-environment` | R1 |
| 272 | `{:seon.error/kind ::unscopable-member` | R1 |
| 315 | `{:seon.error/kind ::agent-id-absent` | R1 |
| 327 | `{:seon.error/kind ::absent-environment` | R1 |
| 381 | `{:seon.error/kind ::invalid-environment-state` | R1 |

### src/seon/error.clj

| Line | Source | Replacement |
|---:|---|---|
| 63 | `KINDS ARE THE KEYWORDS THE SITES ALREADY CARRY. \`:seon.error/kind\` is` | R5 |
| 65 | `a flat value's own \`:seon.error/kind\`, and it is never invented here.` | R5 |
| 69 | `\`:seon.error/kind\` in the corpus is a fact. A \`[:enum]\` would be a` | R5 |
| 261 | `(or (when (map? source) (:seon.error/kind source))` | R2 |
| 262 | `(when failure (:seon.error/kind (refusal failure)))` | R2 |
| 317 | `(cond-> {:seon.error/kind error-kind}` | R1 |
| 332 | `#{:seon.error/kind :seon.error/message :seon.error/data` | R1 |
| 355 | `[:seon.error/kind :seon.error/kind]` | R1 |
| 366 | `[{:seon.error/keys [kind message data` | R7 |
| 375 | `{:seon.error/kind (or kind unclassified)` | R1 |
| 446 | `[:seon.error/kind` | R5 |
| 556 | `(:seon.error/kind error-value))` | R2 |
| 700 | `:seon.error/kind error-kind` | R1 |
| 752 | `flow report (all three shapes), a map carrying \`:seon.error/kind\` is a` | R5 |
| 801 | `{:seon.error/kind (:seon.error/kind fact)` | R1 |
| 822 | `(:seon.error/kind fact))` | R2 |
| 826 | `:seon.error/kind (:seon.error/kind fact)` | R1 |
| 853 | `", kind " (:seon.error/kind fact)` | R6 |
| 1254 | `{:seon.error/keys [id kind message proc op run process signature]} fact` | R7 |
| 1385 | `{:seon.error/keys [id at kind message process signature` | R7 |
| 1583 | `:seon.error/kind :seon.instrument/fn :seon.error/frame` | R1 |
| 1633 | `:seon.error/kind (:seon.error/kind fact)}]` | R1 |
| 1741 | `(true? (:seon.error/class` | R3 |
| 1808 | `(when-let [kind (:seon.error/kind value)]` | R1 |
| 1809 | `[:seon.error/kind kind])))` | R1 |
| 1877 | `[:p {:class "seon-kicker"} (some-> (:seon.error/kind value) name)]` | R1 |
| 1922 | `(when-not (:seon.error/kind row)` | R2 |
| 1930 | `{:seon.error/kind :seon.render/unavailable` | R1 |
| 2016 | `(:seon.error/kind row) [row]` | R2 |

### src/seon/error/refusal.clj

| Line | Source | Replacement |
|---:|---|---|
| 51 | `(if (some? (:seon.error/kind data))` | R2 |
| 55 | `(str (:seon.error/kind data))))` | R1 |

### src/seon/eval.clj

| Line | Source | Replacement |
|---:|---|---|
| 33 | `(:seon.error/kind agent-row) agent-row` | R2 |
| 36 | `{:seon.error/kind ::agent-not-found` | R1 |
| 61 | `(if (:seon.error/kind rows)` | R2 |

### src/seon/eval/drive.clj

| Line | Source | Replacement |
|---:|---|---|
| 95 | `(when (:seon.error/kind result)` | R2 |
| 154 | `[(get-else $ ?receipt :seon.error/kind :seon.eval.drive/absent)` | R1 |
| 165 | `:seon.error/kind error-kind` | R1 |
| 209 | `[:seon.error/kind` | R5 |

### src/seon/flow.clj

| Line | Source | Replacement |
|---:|---|---|
| 273 | `{:seon.error/kind ::submission-capacity` | R1 |
| 602 | `{:seon.error/kind :configuration` | R1 |
| 724 | `{:seon.error/kind ::launcher-stopped` | R1 |
| 758 | `(:seon.error/kind drained-result) drained-result` | R2 |
| 759 | `(:seon.error/kind stopped-result) stopped-result` | R2 |
| 802 | `{:seon.error/kind ::launcher-stopped` | R1 |
| 813 | `{:seon.error/kind ::launcher-stopped` | R1 |
| 837 | `{:seon.error/kind :configuration` | R1 |
| 928 | `{:seon.error/kind ::fault-channel-overflow` | R1 |
| 1084 | `{:seon.error/kind ::unsupported-command` | R1 |

### src/seon/fn.clj

| Line | Source | Replacement |
|---:|---|---|
| 46 | `(when (:seon.error/kind result)` | R2 |
| 157 | `(merge {:seon.error/kind ::index-refused` | R1 |
| 192 | `{:seon.error/kind ::index-refused` | R1 |
| 196 | `{:seon.error/kind ::index-refused` | R1 |
| 611 | `{:seon.error/kind ::index-refused` | R1 |
| 972 | `{:seon.error/kind ::namespace-unresolvable` | R1 |
| 987 | `refusal (some #(when (:seon.error/kind %) %) resolved)]` | R2 |
| 1033 | `(if (:seon.error/kind result) result (first result))))` | R2 |
| 1100 | `{:seon.error/kind ::index-refused` | R1 |
| 1224 | `declaration (when-not (:seon.error/kind found) found)` | R2 |
| 1475 | `(or (when (:seon.error/kind rows) rows)` | R2 |
| 1476 | `(when (:seon.error/kind stale) stale)` | R2 |
| 1577 | `(when (:seon.error/kind function-symbols) function-symbols)` | R2 |
| 1578 | `(when (:seon.error/kind specs) specs)` | R2 |
| 1579 | `(when (:seon.error/kind caller-counts) caller-counts)` | R2 |
| 1947 | `(merge {:seon.error/kind ::index-refused` | R1 |
| 2040 | `{:seon.error/kind ::index-refused` | R1 |
| 2127 | `{:seon.error/kind ::index-refused` | R1 |
| 2330 | `{:seon.error/kind ::index-refused :seon.fn/index-refused true}))))))` | R1 |
| 2341 | `{:seon.error/kind ::index-refused` | R1 |
| 2352 | `{:seon.error/kind ::index-refused` | R1 |
| 2540 | `{:seon.error/kind ::index-refused` | R1 |
| 2578 | `{:seon.error/kind ::index-refused` | R1 |
| 2655 | `{:seon.error/kind ::index-refused` | R1 |
| 2905 | `{:seon.error/kind ::index-refused` | R1 |

### src/seon/fn/analyzer.clj

| Line | Source | Replacement |
|---:|---|---|
| 400 | `{:seon.error/kind ::analysis-refused})))` | R1 |

### src/seon/fn/schema_shape.clj

| Line | Source | Replacement |
|---:|---|---|
| 131 | `{:seon.error/kind` | R5 |
| 168 | `{:seon.error/kind :seon.schema.shape/unsupported-map-key` | R1 |
| 329 | `{:seon.error/kind :seon.schema.shape/fingerprint-collision` | R1 |

### src/seon/fn/signature.cljc

| Line | Source | Replacement |
|---:|---|---|
| 11 | `(merge {:seon.error/kind :seon.fn/signature-refused` | R1 |

### src/seon/fs.clj

| Line | Source | Replacement |
|---:|---|---|
| 20 | `(assoc data :seon.error/kind :seon.cluster.store/refused` | R1 |

### src/seon/fs/jvm.clj

| Line | Source | Replacement |
|---:|---|---|
| 38 | `:seon.error/kind marker` | R1 |
| 51 | `(if (and (keyword? (:seon.error/kind classified))` | R2 |

### src/seon/instrument.clj

| Line | Source | Replacement |
|---:|---|---|
| 99 | `(keyword? (:seon.error/kind value))` | R2 |
| 241 | `{:seon.error/kind ::contract-violated` | R1 |
| 270 | `(when-not (:seon.error/kind entries)` | R2 |
| 402 | `{:seon.error/kind (if (some #(and (= :malli.core/missing-key (:type %))` | R2 |
| 439 | `;; \`minimal-violation\` gave the interrupt a \`:seon.error/kind\` of` | R6 |
| 492 | `{:seon.error/kind :seon.schema/unresolved-predicate` | R1 |
| 523 | `{:seon.error/kind ::missing-recorder` | R1 |
| 528 | `(when (:seon.error/kind caps)` | R2 |
| 604 | `{:seon.error/kind ::error-facet-analysis-unavailable` | R1 |
| 618 | `{:seon.error/kind ::error-facet-analysis-unavailable` | R1 |
| 755 | `{:seon.error/kind ::undeclared-error` | R1 |
| 885 | `{:seon.error/kind ::missing-recorder` | R1 |
| 898 | `{:seon.error/kind ::invalid-mode` | R1 |
| 915 | `{:seon.error/kind ::missing-projection` | R1 |
| 950 | `{:seon.error/kind ::registration-failed` | R1 |

### src/seon/issue.clj

| Line | Source | Replacement |
|---:|---|---|
| 76 | `{:seon.error/kind :seon.issue/git-unbounded :seon.issue/path (str root)})))` | R1 |
| 123 | `(throw (ex-info "Issue directory is absent." {:seon.error/kind :seon.issue/notes-absent :seon.issue/path (str directory)})))` | R1 |
| 175 | `{:seon.error/kind :seon.issue/citations-undeclared})))` | R1 |
| 454 | `(if (:seon.error/kind result)` | R2 |
| 468 | `(throw (ex-info message {:seon.error/kind reason :seon.error/message message})))` | R1 |
| 524 | `_ (when (:seon.error/kind subjects)` | R2 |
| 607 | `(:seon.error/kind delta) delta` | R2 |
| 610 | `(if (:seon.error/kind report)` | R2 |
| 668 | `(:seon.error/kind row) row` | R2 |
| 670 | `{:seon.error/kind :seon.issue/not-found :seon.error/message (str "No current issue " issue-id)}` | R1 |
| 818 | `(if (:seon.error/kind rows)` | R2 |
| 850 | `(when (:seon.error/kind result)` | R2 |
| 1035 | `(if (:seon.error/kind report) report` | R2 |
| 1074 | `(if (:seon.error/kind report) report` | R2 |
| 1110 | `(if (:seon.error/kind report) report` | R2 |

### src/seon/issue/detect.clj

| Line | Source | Replacement |
|---:|---|---|
| 98 | `(if (:seon.error/kind spans)` | R2 |
| 114 | `(if (:seon.error/kind rows) rows (set rows))))` | R2 |
| 134 | `(if (:seon.error/kind rows) rows (set rows))))` | R2 |
| 155 | `(if (:seon.error/kind rows) rows (sort rows))))` | R2 |
| 196 | `(or (some #(when (:seon.error/kind %) %) [subjects documented shared])` | R2 |
| 246 | `(or (some #(when (:seon.error/kind %) %)` | R2 |
| 304 | `(or (some #(when (:seon.error/kind %) %) [subjects shared basis-t])` | R2 |
| 307 | `(if (:seon.error/kind gates)` | R2 |

### src/seon/issue/opening.clj

| Line | Source | Replacement |
|---:|---|---|
| 207 | `{:seon.error/kind :seon.issue/not-found` | R1 |
| 237 | `{:seon.error/kind :seon.issue/not-found` | R1 |

### src/seon/maintenance.clj

| Line | Source | Replacement |
|---:|---|---|
| 15 | `:seon.error/kind kind` | R1 |
| 109 | `(:seon.error/kind error)` | R2 |
| 110 | `(assoc :seon.error/kind (:seon.error/kind error))` | R1 |
| 209 | `(if (:seon.error/kind collection)` | R2 |
| 210 | `(select-keys collection [:seon.error/kind :seon.error/message])` | R1 |
| 296 | `(if (:seon.error/kind database)` | R2 |
| 341 | `{:seon.error/kind ::root-never-collected` | R1 |
| 368 | `(if (:seon.error/kind database)` | R2 |

### src/seon/note.clj

| Line | Source | Replacement |
|---:|---|---|
| 32 | `(and (map? value) (keyword? (:seon.error/kind value))))` | R2 |
| 128 | `(if (:seon.error/kind pulled)` | R2 |
| 157 | `:seon.error/kind marker` | R1 |

### src/seon/operator.clj

| Line | Source | Replacement |
|---:|---|---|
| 39 | `kind (or (:seon.error/kind data) ::failed)]` | R1 |
| 42 | `:seon.error/kind kind` | R1 |
| 57 | `(keyword? (:seon.error/kind value))` | R2 |
| 106 | `:seon.error/kind kind` | R1 |
| 249 | `{:seon.error/kind :seon.operator/process-census-incomplete` | R1 |
| 466 | `{:seon.error/kind :seon.operator/reap-incomplete` | R1 |
| 556 | `{:seon.error/kind` | R5 |
| 622 | `{:seon.error/kind` | R5 |
| 818 | `{:seon.error/kind :seon.operator/collection-incomplete` | R1 |
| 964 | `(:seon.error/kind (ex-data failure)))` | R2 |
| 969 | `(:seon.error/kind (ex-data failure)))` | R2 |
| 1016 | `{:seon.error/kind :seon.operator.collect/unrecognized-option` | R1 |

### src/seon/operator/state.clj

| Line | Source | Replacement |
|---:|---|---|
| 90 | `{:seon.error/kind` | R5 |
| 128 | `{:seon.error/kind :seon.operator.subprocess/deadline-exceeded` | R1 |
| 212 | `{:seon.error/kind :seon.operator/process-survived-sigkill` | R1 |
| 444 | `{:seon.error/kind :seon.operator/lock-bound-undeclared` | R1 |
| 539 | `{:seon.error/kind :seon.operator/lock-hold-timeout` | R1 |
| 629 | `{:seon.error/kind :seon.operator/lock-holder-inconsistent` | R1 |
| 638 | `{:seon.error/kind :seon.operator/lock-acquisition-timeout` | R1 |
| 706 | `{:seon.error/kind` | R5 |
| 719 | `{:seon.error/kind` | R5 |
| 832 | `{:seon.error/kind :seon.operator/unreadable-claim` | R1 |
| 848 | `{:seon.error/kind :seon.operator/unreadable-claim` | R1 |
| 893 | `{:seon.error/kind :seon.operator/process-claim-mismatch` | R1 |
| 915 | `{:seon.error/kind :seon.operator/process-remained-alive` | R1 |
| 1046 | `{:seon.error/kind` | R5 |
| 1346 | `{:seon.error/kind :seon.operator/undeclared-managed-root` | R1 |

### src/seon/plan.clj

| Line | Source | Replacement |
|---:|---|---|
| 94 | `:seon.error/kind kind` | R1 |
| 101 | `(if (:seon.error/kind data)` | R2 |
| 365 | `:seon.error/kind :my.plan/agent-not-found` | R1 |
| 412 | `:seon.error/kind :my.plan/not-found` | R1 |
| 1309 | `" — " (pr-str (:seon.error/kind value)) ": "` | R6 |

### src/seon/print.cljc

| Line | Source | Replacement |
|---:|---|---|
| 738 | `{:seon.error/kind ::object-without-class` | R1 |
| 785 | `{:seon.error/kind ::unknown-face` | R1 |
| 1035 | `{:seon.error/kind ::elision-without-requery-coordinates` | R1 |

### src/seon/problems.clj

| Line | Source | Replacement |
|---:|---|---|
| 120 | `:seon.error/kind (:seon.error/kind row)` | R1 |
| 153 | `[?receipt :seon.error/kind ?kind]` | R1 |
| 167 | `:seon.error/kind kind` | R1 |
| 205 | `kind (or (:seon.error/kind admitted)` | R1 |
| 220 | `:seon.error/kind kind` | R1 |
| 354 | `(if (:seon.error/kind ids) ids` | R2 |
| 406 | `(if (:seon.error/kind tests) tests found))))` | R2 |
| 495 | `(row "kind" (:seon.error/kind entry)` | R1 |
| 511 | `"kind" (:seon.error/kind entry)` | R6 |
| 601 | `" kind=" (:seon.error/kind entry)` | R6 |

### src/seon/program.cljc

| Line | Source | Replacement |
|---:|---|---|
| 61 | `(if (:seon.error/kind history)` | R2 |
| 77 | `(if (:seon.error/kind result)` | R2 |
| 108 | `(merge {:seon.error/kind :seon.program/declaration-refused` | R1 |
| 282 | `:seon.error/kind :seon.program/no-declaration-at` | R1 |
| 443 | `{:seon.error/kind :seon.fn.binding/unsupported` | R1 |
| 453 | `{:seon.error/kind :seon.fn.binding/unsupported` | R1 |
| 500 | `{:seon.error/kind :seon.fn.binding/unsupported` | R1 |
| 543 | `{:seon.error/kind :seon.fn/signature-refused` | R1 |
| 565 | `{:seon.error/kind :seon.fn/signature-refused` | R1 |
| 619 | `{:seon.error/kind :seon.fn/signature-refused` | R1 |
| 693 | `{:seon.error/kind :seon.fn/signature-refused` | R1 |

### src/seon/reconcile.cljc

| Line | Source | Replacement |
|---:|---|---|
| 53 | `(merge {:seon.error/kind ::refused` | R1 |
| 64 | `{:seon.error/kind ::missing-declarations :seon.reconcile/missing-declarations true})))` | R1 |
| 445 | `(if (:seon.error/kind result)` | R2 |

### src/seon/render.clj

| Line | Source | Replacement |
|---:|---|---|
| 74 | `(:seon.error/kind effective))` | R2 |
| 109 | `(when (and database (not (:seon.error/kind database)))` | R2 |
| 133 | `(if (:seon.error/kind effective)` | R2 |
| 138 | `{:seon.error/kind ::missing-projection` | R1 |
| 308 | `{:seon.error/kind ::ambiguous` | R1 |
| 574 | `(if (:seon.error/kind profile)` | R2 |
| 603 | `(if (:seon.error/kind profile)` | R2 |
| 698 | `(if (:seon.error/kind rows)` | R2 |
| 895 | `(qualified-keyword? (:seon.error/kind failure))` | R2 |
| 896 | `(assoc :seon.render.unknown/refusal (:seon.error/kind failure))` | R1 |
| 908 | `\`:seon.error/kind\` and the throwable's class ride along when the boundary` | R2 |
| 918 | `:seon.error/kind ::unknown` | R1 |
| 1055 | `(or (:seon.error/kind value)` | R1 |
| 1141 | `(or (not (:seon.error/kind selected))` | R1 |
| 1142 | `(= ::ambiguous (:seon.error/kind selected)))` | R2 |
| 1148 | `(if (= ::ambiguous (:seon.error/kind selected))` | R2 |
| 1153 | `(:seon.error/kind selected) (bounded-error-node request selected)` | R2 |
| 1167 | `(if (:seon.error/kind rendered)` | R2 |
| 1211 | `(if (:seon.error/kind selected)` | R2 |
| 1229 | `(if (or declared-absence? (string? rendered) (:seon.error/kind rendered)` | R2 |
| 1232 | `{:seon.error/kind ::invalid-ai-output` | R1 |
| 1238 | `(if (or declared-absence? (:seon.error/kind rendered)` | R2 |
| 1241 | `{:seon.error/kind ::invalid-html-output` | R1 |
| 1249 | `{:seon.error/kind ::invalid-form-output` | R1 |
| 1269 | `(if (:seon.error/kind profile)` | R2 |
| 1274 | `(if (:seon.error/kind selected)` | R2 |
| 1287 | `(if (:seon.error/kind profile)` | R2 |
| 1293 | `(if (:seon.error/kind selected)` | R2 |
| 1310 | `{:seon.error/kind ::missing-source-provenance` | R1 |
| 1346 | `(if (:seon.error/kind form)` | R2 |
| 1356 | `(if (:seon.error/kind profile)` | R2 |
| 1364 | `{:seon.error/kind ::invalid-form-output` | R1 |
| 1383 | `(if (:seon.error/kind profile)` | R2 |
| 1425 | `{:seon.error/kind ::candidate-not-applicable` | R1 |
| 1437 | `(if (:seon.error/kind selected)` | R2 |
| 1584 | `(if (:seon.error/kind basis) basis` | R2 |
| 1658 | `{:seon.error/kind ::walk-failed` | R1 |

### src/seon/render/data.clj

| Line | Source | Replacement |
|---:|---|---|
| 68 | `{:seon.error/kind ::no-such-path` | R1 |
| 86 | `(if (:seon.error/kind pulled)` | R2 |
| 89 | `(if (:seon.error/kind selected)` | R2 |
| 94 | `{:seon.error/kind kind :seon.error/message message})` | R1 |
| 113 | `(if (:seon.error/kind page)` | R2 |
| 139 | `(if (:seon.error/kind page)` | R2 |
| 171 | `(:seon.error/kind snapshot) snapshot` | R2 |
| 172 | `(:seon.error/kind found) found` | R2 |

### src/seon/render/hiccup.clj

| Line | Source | Replacement |
|---:|---|---|
| 275 | `{:seon.error/kind ::unparseable-tag` | R1 |
| 298 | `{:seon.error/kind ::unparseable-tag` | R1 |
| 314 | `{:seon.error/kind ::unparseable-tag` | R1 |
| 425 | `{:seon.error/kind ::unparseable-tag` | R1 |
| 428 | `(if (:seon.error/kind parsed)` | R2 |

### src/seon/render/lint.clj

| Line | Source | Replacement |
|---:|---|---|
| 92 | `(if (:seon.error/kind parsed) "" (:seon.render.hiccup/tag parsed))))` | R2 |
| 125 | `shorthand-classes (if (:seon.error/kind parsed)` | R2 |
| 145 | `(when-not (:seon.error/kind parsed)` | R2 |
| 222 | `{:seon.error/kind ::absent-element` | R1 |
| 519 | `(if (:seon.error/kind rendered)` | R2 |

### src/seon/render/ns.clj

| Line | Source | Replacement |
|---:|---|---|
| 56 | `(and (map? value) (keyword? (:seon.error/kind value))))` | R2 |

### src/seon/render/test.clj

| Line | Source | Replacement |
|---:|---|---|
| 23 | `(if (:seon.error/kind stored) (assoc entity :seon.test/reach-unknown (:seon.error/message stored))` | R2 |
| 115 | `(if (:seon.error/kind changed) [:p (:seon.error/message changed)]` | R2 |

### src/seon/render/transcript.clj

| Line | Source | Replacement |
|---:|---|---|
| 63 | `:seon.error/kind` | R5 |
| 281 | `::error-kind (:seon.error/kind receipt)` | R1 |
| 693 | `(or supplied-agent-id (when-not (:seon.error/kind queried) queried))` | R2 |
| 694 | `::selected-run-error (when (:seon.error/kind queried) queried)}))` | R2 |
| 699 | `{:seon.error/kind ::selected-run-unavailable` | R1 |
| 814 | `(if (:seon.error/kind row)` | R2 |
| 821 | `[:dt "Evaluations"] [:dd (if (:seon.error/kind evaluations)` | R2 |
| 883 | `(not (:seon.error/kind cluster-name)))` | R1 |
| 885 | `(if (and (map? effective) (nil? (:seon.error/kind effective)))` | R2 |
| 915 | `(if (:seon.error/kind rows)` | R2 |
| 924 | `(when-not (:seon.error/kind row)` | R2 |
| 968 | `(if (:seon.error/kind derived)` | R2 |
| 1015 | `{:seon.error/kind :seon.db/not-found` | R1 |
| 1030 | `(if (:seon.error/kind agent-id) agent-id` | R2 |
| 1127 | `{:seon.ai.attempt/error [:seon.error/kind]}]}])` | R5 |
| 1136 | `(if (:seon.error/kind rows) rows` | R2 |
| 1234 | `::detail (str "stalled: " (or (:seon.error/kind failure) "unknown provider refusal")` | R1 |
| 1345 | `(:seon.error/kind rows) [:p {:class "seon-emission-error"} (:seon.error/message rows)]` | R2 |
| 1369 | `(if (= :seon.instrument/contract-violated (:seon.error/kind refusal))` | R2 |
| 1413 | `(if (:seon.error/kind acquired)` | R2 |
| 1528 | `(if (or (nil? fitted) (:seon.error/kind fitted))` | R2 |
| 1574 | `(if (:seon.error/kind html)` | R2 |
| 1594 | `(if (:seon.error/kind refusal) refusal (throw failure)))))]` | R2 |
| 1597 | `(if (:seon.error/kind composed)` | R2 |
| 1603 | `(if (:seon.error/kind composed)` | R2 |
| 1630 | `(if (:seon.error/kind refusal) refusal (throw failure)))))` | R2 |
| 1649 | `(:seon.error/kind rows)` | R2 |
| 1653 | `(:seon.error/kind acquired)` | R2 |
| 1689 | `:seon.cluster.eval/output :seon.error/kind` | R5 |
| 1702 | `(if (:seon.error/kind acquired) acquired` | R2 |
| 1759 | `(if-let [failure (some #(when (:seon.error/kind %) %) [intervals steps messages definitions])]` | R2 |
| 1779 | `(:seon.error/kind effects) {::details (:seon.error/message effects)}` | R2 |
| 1791 | `(if (:seon.error/kind rows) rows` | R2 |
| 1889 | `(if (:seon.error/kind calibration)` | R2 |
| 1930 | `(if (:seon.error/kind acquired)` | R2 |
| 1952 | `(cond (= :seon.sci.reader/fabricated-response (some-> (:seon.error/kind saved) keyword))` | R2 |
| 1968 | `(filter #(= :seon.sci.reader/fabricated-response (some-> (:seon.error/kind %) keyword)) evaluations))))` | R2 |
| 2023 | `(if (:seon.error/kind messages)` | R2 |
| 2043 | `frame (when (and opening (not (:seon.error/kind opening)))` | R2 |
| 2107 | `(when-not (:seon.error/kind basis)` | R2 |
| 2115 | `::steps (count steps) ::plan-unavailable (boolean (:seon.error/kind plan))` | R1 |
| 2151 | `(when-not (:seon.error/kind refreshes)` | R2 |
| 2153 | `::unknown (if (:seon.error/kind refreshes) 1 0))` | R2 |
| 2313 | `evaluations (if (:seon.error/kind acquired) acquired evaluations)` | R2 |
| 2317 | `problems (when-not (or (:seon.error/kind rows) (:seon.error/kind evaluations))` | R2 |
| 2323 | `(when-not (:seon.error/kind evaluations) (ledger-strip request rows evaluations selected problems))]` | R2 |
| 2326 | `(:seon.error/kind evaluations) [:p (:seon.error/message evaluations)]` | R2 |
| 2363 | `row (if (:seon.error/kind agent-id) agent-id` | R2 |
| 2371 | `(:seon.error/kind row) row` | R2 |
| 2374 | `{:seon.error/kind :seon.db/not-found` | R1 |

### src/seon/render/value.clj

| Line | Source | Replacement |
|---:|---|---|
| 196 | `{:seon.error/kind ::missing-root-identity` | R1 |
| 287 | `{:seon.error/kind :seon.render.value/window-failed` | R1 |
| 493 | `:seon.print/value :seon.error/kind}` | R5 |
| 539 | `(if (:seon.error/kind id)` | R2 |
| 637 | `(if (:seon.error/kind projection)` | R2 |

### src/seon/render/walk.clj

| Line | Source | Replacement |
|---:|---|---|
| 166 | `(if (:seon.error/kind pulled)` | R2 |
| 226 | `:seon.error/kind ::elided` | R1 |
| 400 | `(if (:seon.error/kind requirers)` | R2 |
| 418 | `(not (:seon.error/kind (:seon.render.call/output previous)))` | R1 |
| 446 | `(if (:seon.error/kind reverse-values)` | R2 |
| 660 | `:seon.error/kind ::elided` | R1 |
| 750 | `:seon.error/kind ::no-such-entity` | R1 |
| 778 | `failure (when (:seon.error/kind rendered) rendered)` | R2 |
| 946 | `(if (:seon.error/kind evaluations)` | R2 |
| 955 | `(if (:seon.error/kind rendered)` | R2 |

### src/seon/render/web.clj

| Line | Source | Replacement |
|---:|---|---|
| 489 | `(:seon.error/kind fleet-output)` | R2 |
| 495 | `(not (:seon.error/kind fleet-output))` | R1 |
| 621 | `{:seon.error/kind :seon.render.walk/elided` | R1 |
| 698 | `:seon.error/kind kind` | R1 |
| 716 | `:seon.error/kind ::function-unavailable` | R1 |
| 753 | `(if (:seon.error/kind value)` | R2 |
| 888 | `(when-not (:seon.error/kind observation)` | R2 |
| 997 | `(:seon.error/kind rendered) (debug-value-html rendered)` | R2 |
| 1077 | `(:seon.error/kind selection) (debug-value-html selection)` | R2 |
| 1078 | `(:seon.error/kind selected) (debug-value-html selected)` | R2 |
| 1089 | `(when (and (not (:seon.error/kind selection))` | R2 |
| 1351 | `(if (:seon.error/kind acquisition)` | R2 |
| 1439 | `(not (:seon.error/kind evaluated))` | R1 |
| 1442 | `(if (:seon.error/kind preview)` | R2 |
| 1461 | `(not (:seon.error/kind preview)) (merge preview)` | R1 |
| 1466 | `(not (:seon.error/kind preview))` | R1 |
| 1619 | `(:seon.error/kind first-observation))` | R2 |
| 1657 | `(if (:seon.error/kind acquisition)` | R2 |
| 1669 | `[unit (if (:seon.error/kind pulled)` | R2 |
| 1707 | `(if (:seon.error/kind related-values)` | R2 |
| 1783 | `{:seon.error/kind ::render-value-missing` | R1 |
| 2417 | `(if (:seon.error/kind result) result {:seon.db/db (db/db connection)})))` | R2 |
| 2469 | `(:seon.error/kind entries)` | R2 |
| 2548 | `{:seon.error/kind ::missing-port` | R1 |
| 2676 | `(if (:seon.error/kind result)` | R2 |
| 2751 | `(when (:seon.error/kind written)` | R2 |
| 2758 | `(when (:seon.error/kind packages)` | R2 |
| 2764 | `{"seon.error/kind" (str (:seon.error/kind packages))` | R1 |
| 2781 | `(when (:seon.error/kind written)` | R2 |
| 2907 | `(not (:seon.error/kind result))` | R1 |
| 2910 | `(= :seon.db/unknown-failure (:seon.error/kind result))` | R2 |
| 2989 | `(if (:seon.error/kind result)` | R2 |
| 2992 | `{:seon.error/kind ::owner-not-ensured` | R1 |
| 3267 | `:seon.error/kind ::invalid-context-action` | R1 |
| 3269 | `(if (:seon.error/kind result)` | R2 |
| 3300 | `{:seon.error/kind :seon.render.web/value-not-found` | R1 |
| 3304 | `{:seon.error/kind :seon.render.web/value-unreadable` | R1 |
| 3500 | `(when (:seon.error/kind result)` | R2 |

### src/seon/repl.clj

| Line | Source | Replacement |
|---:|---|---|
| 489 | `(:seon.error/kind live) [:pre (pr-str live)]` | R2 |

### src/seon/run.clj

| Line | Source | Replacement |
|---:|---|---|
| 104 | `{:seon.error/kind :my.turn/usage-walkthrough-absent` | R1 |
| 125 | `{:seon.error/kind :my.turn/blank-note` | R1 |
| 144 | `{:seon.error/kind :my.turn/blank-result` | R1 |

### src/seon/schedule.clj

| Line | Source | Replacement |
|---:|---|---|
| 243 | `{:seon.error/kind ::task-without-creation-instant` | R1 |
| 357 | `{:seon.error/kind ::invalid-fire-id` | R1 |
| 363 | `{:seon.error/kind ::incomplete-task` | R1 |
| 373 | `{:seon.error/kind ::invalid-task-owner` | R1 |
| 431 | `{:seon.error/kind ::missing-receipt` | R1 |
| 456 | `{:seon.error/kind ::invalid-terminal-arm` | R1 |
| 484 | `(when (:seon.error/kind result)` | R2 |
| 487 | `:seon.error/kind refusal-kind` | R1 |
| 494 | `(keyword? (:seon.error/kind value))` | R2 |
| 585 | `{:seon.error/kind ::unresolved-handler` | R1 |

### src/seon/schema.clj

| Line | Source | Replacement |
|---:|---|---|
| 126 | `:seon.error/kind :user-input})))))` | R1 |
| 303 | `:seon.error/kind :user-input}` | R1 |
| 467 | `:seon.error/kind :core-bug}))))` | R1 |
| 511 | `:seon.error/kind :core-bug})))` | R1 |
| 542 | `:seon.error/kind :core-bug})))` | R1 |
| 676 | `:seon.error/kind :core-bug :seon.schema/noncanonical-projection-data true}))))` | R1 |
| 1049 | `{:seon.error/kind ::missing-projection` | R1 |
| 1113 | `:seon.error/kind :core-bug}))))` | R1 |
| 1132 | `:seon.error/kind :user-input}))))` | R1 |
| 1320 | `:seon.error/kind :user-input}))))` | R1 |
| 1496 | `:seon.error/kind :user-input :seon.schema/unreadable-form k}` | R1 |
| 1506 | `:seon.error/kind :user-input :seon.schema/non-round-tripping-form k}))))` | R1 |
| 1536 | `:seon.error/kind :user-input :seon.schema/unregister-outside-delta k})))` | R1 |
| 1731 | `{:seon.error/kind :seon.schema/render-contract-incoherent` | R1 |
| 2452 | `:seon.error/kind :core-bug :seon.schema/malformed-projection-row true})))` | R1 |
| 2461 | `:seon.error/kind :core-bug :seon.schema/malformed-projection-form true})))` | R1 |
| 2469 | `:seon.error/kind :core-bug})))` | R1 |
| 2486 | `:seon.error/kind :core-bug :seon.schema/malformed-projection-identity true}))))` | R1 |
| 2501 | `:seon.error/kind :core-bug :seon.schema/malformed-projection-identity true}))))` | R1 |
| 2507 | `:seon.error/kind :core-bug :seon.schema/malformed-projection-identity true}))))` | R1 |
| 2517 | `:seon.error/kind :core-bug :seon.schema/malformed-projection-row true})))` | R1 |
| 2528 | `:seon.error/kind :core-bug :seon.schema/malformed-projection-row true})))` | R1 |
| 2536 | `:seon.error/kind :core-bug})))` | R1 |
| 2555 | `:seon.error/kind :core-bug :seon.schema/malformed-artifact-export true}))))` | R1 |
| 2562 | `:seon.error/kind :core-bug :seon.schema/malformed-artifact-export true})))))` | R1 |
| 2616 | `{:seon.error/kind :seon.schema/invalid-projection-source` | R1 |
| 2797 | `{:seon.error/kind :seon.schema/unsupported-pull-selector` | R1 |
| 3049 | `(if (and (map? projected) (:seon.error/kind projected))` | R2 |
| 3075 | `:seon.error/kind :user-input))))` | R1 |
| 3601 | `{:seon.error/kind ::missing-projection` | R1 |
| 3622 | `:seon.error/kind :core-bug})))` | R1 |
| 3632 | `:seon.error/kind :core-bug})))` | R1 |
| 3800 | `:seon.error/kind :core-bug :seon.schema/unknown-shape schema-key})))` | R1 |

### src/seon/schema/datahike.clj

| Line | Source | Replacement |
|---:|---|---|
| 143 | `:seon.error/kind :user-input})))` | R1 |
| 154 | `:seon.error/kind :user-input})))` | R1 |
| 177 | `:seon.error/kind :user-input}))` | R1 |
| 186 | `:seon.error/kind :user-input}))))))` | R1 |
| 245 | `:seon.error/kind :user-input})))` | R1 |
| 259 | `:seon.error/kind :user-input})))` | R1 |
| 380 | `:seon.error/kind :user-input})))` | R1 |

### src/seon/schema/edn.clj

| Line | Source | Replacement |
|---:|---|---|
| 135 | `:seon.error/kind :user-input})))` | R1 |
| 187 | `:seon.error/kind :user-input})))]` | R1 |
| 195 | `:seon.error/kind :user-input})))` | R1 |
| 225 | `:seon.error/kind :user-input}` | R1 |
| 233 | `:seon.error/kind :user-input}` | R1 |
| 242 | `:seon.error/kind :user-input}` | R1 |
| 251 | `:seon.error/kind :user-input})))` | R1 |
| 276 | `:seon.error/kind :user-input})))` | R1 |
| 291 | `:seon.error/kind :user-input})))` | R1 |
| 303 | `:seon.error/kind :user-input}))))))` | R1 |
| 321 | `:seon.error/kind :user-input}))))` | R1 |
| 483 | `:seon.error/kind :user-input}` | R1 |

### src/seon/schema/internal.cljc

| Line | Source | Replacement |
|---:|---|---|
| 92 | `:seon.error/kind :user-input}` | R1 |
| 387 | `:seon.error/kind   :user-input :seon.schema/invalid-schema k}` | R1 |
| 426 | `:seon.error/kind   :user-input :seon.schema/nilable-value-schema k})))))` | R1 |
| 451 | `:seon.error/kind   :user-input})))))` | R1 |

### src/seon/sci/admit.clj

| Line | Source | Replacement |
|---:|---|---|
| 132 | `{:seon.error/kind over-bound-marker` | R1 |
| 419 | `{:seon.error/kind ::projection-failed` | R1 |
| 688 | `{:seon.error/kind ::missing-bound` | R1 |
| 716 | `{:seon.error/kind ::missing-cap` | R1 |

### src/seon/sci/eval.clj

| Line | Source | Replacement |
|---:|---|---|
| 203 | `{:seon.error/kind ::acquisition-refused` | R1 |
| 509 | `{:seon.error/kind :seon.cluster.reply/no-forms` | R1 |
| 515 | `{:seon.error/kind ::reader-event-count` | R1 |
| 654 | `{:seon.error/kind :seon.config/required-absent` | R1 |
| 703 | `{:seon.error/kind ::missing-function-row` | R1 |
| 844 | `{:seon.error/kind ::install-source-mismatch` | R1 |
| 922 | `{:seon.error/kind ::install-delete-mismatch` | R1 |
| 1013 | `parents (when-not (or (:seon.error/kind components) (:seon.error/kind touched))` | R2 |
| 1021 | `(when-not (:seon.error/kind incoming)` | R2 |
| 1033 | `(when (and parents (not (:seon.error/kind entities)) (not (:seon.error/kind changed)))` | R2 |
| 1037 | `(and (not (:seon.error/kind old)) (not (:seon.error/kind current))` | R1 |
| 1115 | `(when (and (not-any? :seon.error/kind installed)` | R2 |
| 1217 | `{:seon.error/kind ::namespace-unloadable` | R1 |
| 1238 | `{:seon.error/kind ::namespace-unloadable` | R1 |
| 1351 | `:seon.error/kind :seon.sci.eval/documentation-unavailable` | R1 |
| 1367 | `{:seon.error/kind :seon.sci.eval/declaration-absent` | R1 |
| 1390 | `(when (:seon.error/kind definitions)` | R2 |
| 1422 | `(if (:seon.error/kind entries)` | R2 |
| 1447 | `(if (:seon.error/kind overrides)` | R2 |
| 1473 | `(:seon.error/kind namespace-row) namespace-row` | R2 |
| 1474 | `(:seon.error/kind functions) functions` | R2 |
| 1507 | `(:seon.error/kind row) row` | R2 |
| 1508 | `(:seon.error/kind overrides) overrides` | R2 |
| 1515 | `(:seon.error/kind row) row` | R2 |
| 1576 | `cause-kind (:seon.error/kind failure-data)` | R1 |
| 1581 | `{:seon.error/kind ::acquisition-refused` | R1 |
| 1607 | `(:seon.error/kind refusal)` | R2 |
| 1628 | `effective (if (:seon.error/kind read-effective)` | R2 |
| 1657 | `(:seon.error/kind outcome)` | R2 |
| 1825 | `{:seon.error/kind ::namespace-binding-cycle` | R1 |
| 1846 | `(if (:seon.error/kind installed)` | R2 |
| 2277 | `{:seon.error/kind ::schema-refused` | R1 |
| 2291 | `{:seon.error/kind ::schema-refused` | R1 |
| 2393 | `carrying \`:seon.error/kind\` whose \`:seon.error/message\` is not a string.` | R5 |
| 2408 | `(some? (:seon.error/kind value)) (str (:seon.error/kind value))` | R2 |
| 2416 | `(let [function-name (when (= :seon.instrument/contract-violated (:seon.error/kind value))` | R2 |
| 2434 | `shown (if (:seon.error/kind projection) projection` | R2 |
| 2440 | `(:seon.error/kind value)` | R2 |
| 3084 | `(if (:seon.error/kind results) results (first results))))` | R2 |

### src/seon/sci/kernel.clj

| Line | Source | Replacement |
|---:|---|---|
| 174 | `{:seon.error/kind ::missing-function-installer` | R1 |
| 299 | `{:seon.error/kind ::missing-interrupt-guard` | R1 |
| 313 | `{:seon.error/kind ::already-armed` | R1 |
| 525 | `\`:seon.error/kind\` and gains this boundary's evidence; everything else` | R5 |
| 572 | `(if (:seon.error/kind existing)` | R2 |
| 588 | `:seon.error/kind kind` | R1 |
| 659 | `{:seon.error/kind ::unresolved-invocation` | R1 |
| 707 | `{:seon.error/kind ::failure-admission-failed` | R1 |

### src/seon/sci/reader.cljc

| Line | Source | Replacement |
|---:|---|---|
| 14 | `:seon.error/kind kind` | R1 |
| 881 | `[:seon.error/kind :keyword]` | R1 |

### src/seon/search.clj

| Line | Source | Replacement |
|---:|---|---|
| 117 | `{:seon.error/kind ::handle-absent` | R1 |
| 558 | `{:seon.error/kind ::missing-resource :seon.search/missing-resource true})))` | R1 |

### src/seon/shell/jvm.clj

| Line | Source | Replacement |
|---:|---|---|
| 30 | `:seon.error/kind kind` | R1 |
| 37 | `(if (and (keyword? (:seon.error/kind classified))` | R2 |
| 57 | `(:seon.error/kind stat)` | R2 |
| 61 | `:my.shell/filesystem-error (:seon.error/kind stat)})` | R1 |
| 131 | `(if (:seon.error/kind terminal)` | R2 |
| 153 | `{:seon.error/kind :my.shell/stdin-limit` | R1 |
| 167 | `{:seon.error/kind :my.shell/stdin-limit` | R1 |
| 181 | `{:seon.error/kind :my.shell/blob-unavailable` | R1 |
| 188 | `{:seon.error/kind :my.shell/blob-unavailable` | R1 |
| 198 | `{:seon.error/kind :my.shell/blob-unavailable` | R1 |
| 322 | `(if (:seon.error/kind stdout)` | R2 |
| 325 | `(if (:seon.error/kind stderr)` | R2 |
| 358 | `(:seon.error/kind evidence) evidence` | R2 |
| 359 | `(:seon.error/kind input) input` | R2 |
| 381 | `(:seon.error/kind evidence) evidence` | R2 |
| 382 | `(:seon.error/kind input) input` | R2 |
| 407 | `(if (:seon.error/kind cwd)` | R2 |

### src/seon/test.clj

| Line | Source | Replacement |
|---:|---|---|
| 20 | `{:seon.error/kind ::unknown :seon.test/unknown (str input)` | R1 |
| 64 | `(:seon.error/kind row) row` | R2 |
| 77 | `(if (:seon.error/kind events) events` | R2 |
| 101 | `(if (:seon.error/kind changed) changed` | R2 |
| 114 | `{:seon.error/kind :seon.test/classpath-incompatible` | R1 |
| 172 | `(if (:seon.error/kind result)` | R2 |
| 244 | `(:seon.error/kind rows) rows` | R2 |
| 261 | `(if (:seon.error/kind owners)` | R2 |
| 266 | `(if (:seon.error/kind tests)` | R2 |
| 287 | `(when (:seon.error/kind row)` | R2 |
| 327 | `(if (:seon.error/kind owners)` | R2 |
| 331 | `(:seon.error/kind row) row` | R2 |
| 341 | `(if (:seon.error/kind reach)` | R2 |
| 355 | `(:seon.error/kind report)` | R2 |
| 384 | `(:seon.error/kind report) (assoc report :seon.test/next-tier :none)` | R2 |
| 392 | `{:seon.error/kind ::destructive-in-process` | R1 |
| 423 | `(if (:seon.error/kind provenance)` | R2 |
| 433 | `(if (:seon.error/kind database)` | R2 |
| 453 | `(:seon.error/kind provenance) provenance` | R2 |
| 466 | `result (if (or (:seon.error/kind result) (empty? drifted))` | R2 |
| 474 | `(if (:seon.error/kind result)` | R2 |
| 484 | `(if (:seon.error/kind committed) committed (first committed)))))))))` | R2 |
| 510 | `(if (:seon.error/kind database)` | R2 |
| 520 | `resolved (if (:seon.error/kind prepared)` | R2 |
| 525 | `(:seon.error/kind provenance) provenance` | R2 |
| 526 | `(:seon.error/kind resolved) resolved` | R2 |
| 541 | `{:seon.error/kind kind :seon.error/message message` | R1 |
| 888 | `{:seon.error/kind kind` | R1 |
| 1063 | `(if (:seon.error/kind result) result (get result test-symbol))))` | R2 |
| 1070 | `eligible (when-not (:seon.error/kind rows)` | R2 |
| 1077 | `(:seon.error/kind rows) rows` | R2 |
| 1078 | `(:seon.error/kind digests) digests` | R2 |
| 1101 | `(if (:seon.error/kind symbols) symbols` | R2 |
| 1123 | `{:seon.error/kind kind :seon.error/message message` | R1 |
| 1144 | `(:seon.error/kind row) row` | R2 |
| 1154 | `(:seon.error/kind wanted) wanted` | R2 |
| 1155 | `(:seon.error/kind actual) actual` | R2 |
| 1172 | `{:seon.error/kind :seon.test.runner/not-runnable` | R1 |
| 1190 | `(if (:seon.error/kind ctx)` | R2 |
| 1273 | `(:seon.error/kind owners) owners` | R2 |
| 1277 | `(:seon.error/kind reach) reach` | R2 |
| 1335 | `(if (:seon.error/kind prepared)` | R2 |
| 1357 | `(if (:seon.error/kind test-var)` | R2 |
| 1371 | `green? (and (not (:seon.error/kind outcome))` | R1 |
| 1377 | `(not (:seon.error/kind outcome)) (update :seon.test/results conj outcome)` | R1 |
| 1384 | `(if (:seon.error/kind outcome) (:seon.error/message outcome)` | R2 |
| 1459 | `(:seon.error/kind effective) (assoc effective :seon.test/next-tier :none)` | R2 |
| 1482 | `(if (:seon.error/kind result)` | R2 |
| 1516 | `(:seon.error/kind transaction) transaction` | R2 |
| 1517 | `(:seon.error/kind adoption) adoption` | R2 |
| 1534 | `(if (:seon.error/kind result)` | R2 |
| 1592 | `(:seon.error/kind effective) effective` | R2 |
| 1607 | `(:seon.error/kind prepared) prepared` | R2 |
| 1617 | `(if (:seon.error/kind result)` | R2 |
| 1676 | `(:seon.error/kind row) row` | R2 |
| 1677 | `(:seon.error/kind digest) digest` | R2 |
| 1698 | `(if (:seon.error/kind result) result (boolean result)))))` | R2 |

### src/seon/test/accretion.clj

| Line | Source | Replacement |
|---:|---|---|
| 72 | `{:seon.error/kind ::non-data-contract` | R1 |
| 105 | `_ (when (:seon.error/kind invocation)` | R2 |
| 265 | `:seon.error/kind]))` | R5 |
| 271 | `:seon.error/kind])` | R5 |
| 328 | `:seon.error/kind :seon.test.accretion/install-refused` | R1 |
| 344 | `(if (:seon.error/kind actual)` | R2 |
| 345 | `(str (:seon.error/kind actual) " — " (:seon.error/message actual))` | R1 |
| 360 | `[(str (:seon.error/kind unit) "\n" (:seon.error/message unit))` | R1 |

### src/seon/test/arm.clj

| Line | Source | Replacement |
|---:|---|---|
| 77 | `{:seon.error/kind :seon.test.runner/unreadable-program-source` | R1 |
| 87 | `{:seon.error/kind :seon.test.runner/program-source-declares-no-namespace` | R1 |
| 122 | `{:seon.error/kind :seon.test.runner/program-source-root-unresolved` | R1 |
| 136 | `{:seon.error/kind :seon.test.runner/program-declares-no-namespaces` | R1 |
| 159 | `(when (:seon.error/kind caps)` | R2 |
| 194 | `(when (:seon.error/kind applied)` | R2 |
| 200 | `{:seon.error/kind :seon.test.runner/instrumentation-unavailable})))` | R1 |

### src/seon/test/bounds.clj

| Line | Source | Replacement |
|---:|---|---|
| 36 | `{:seon.error/kind ::unauthorized-override})))` | R1 |
| 41 | `{:seon.error/kind ::invalid-override})))` | R1 |

### src/seon/test/fast.clj

| Line | Source | Replacement |
|---:|---|---|
| 28 | `{:seon.error/kind ::missing-namespaces})))` | R1 |

### src/seon/test/runner.clj

| Line | Source | Replacement |
|---:|---|---|
| 628 | `{:seon.error/kind ::not-runnable` | R1 |
| 694 | `(if (:seon.error/kind results) results (first results)))))` | R2 |
| 720 | `{:seon.error/kind ::invalid-marker-reason` | R1 |
| 763 | `{:seon.error/kind ::test-source-root-undeclared` | R1 |
| 799 | `{:seon.error/kind ::no-bare-namespaces` | R1 |
| 850 | `{:seon.error/kind ::platform-declaration-drift` | R1 |
| 903 | `{:seon.error/kind ::long-declaration-drift` | R1 |
| 923 | `{:seon.error/kind ::missing-fixture-observation` | R1 |
| 1066 | `{:seon.error/kind ::missing-destructive-owners})))` | R1 |
| 1124 | `{:seon.error/kind ::destructive-platform-test` | R1 |
| 1185 | `{:seon.error/kind ::missing-fixture-observation` | R1 |
| 1210 | `{:seon.error/kind :seon.test/namespace-hook-requires-complete-selection` | R1 |
| 1320 | `{:seon.error/kind ::mixed-host-and-sci-task` | R1 |
| 1353 | `:seon.error/kind ::fixture-base-unavailable` | R1 |
| 1425 | `(if (or (nil? connection) (:seon.error/kind database))` | R2 |
| 1599 | `_ (when-let [failure (first (filter :seon.error/kind test-vars))]` | R2 |
| 1606 | `_ (when (:seon.error/kind results)` | R2 |
| 1717 | `{:seon.error/kind ::unreadable-program-source` | R1 |
| 1727 | `{:seon.error/kind ::program-source-declares-no-namespace` | R1 |
| 1762 | `{:seon.error/kind ::program-source-root-unresolved` | R1 |
| 1776 | `{:seon.error/kind ::program-declares-no-namespaces` | R1 |
| 1834 | `{:seon.error/kind ::re-arm-failed` | R1 |
| 1978 | `{:seon.error/kind ::unknown-worker-command` | R1 |
| 1993 | `(when (:seon.error/kind base)` | R2 |
| 2101 | `_ (when (:seon.error/kind ids) (throw (ex-info "Reach identities unavailable." ids)))` | R2 |
| 2108 | `_ (when (:seon.error/kind pulled) (throw (ex-info "Reach rows unavailable." pulled)))` | R2 |
| 2206 | `{:seon.error/kind :seon.test/unknown :seon.test/unknown "reach digest"` | R1 |
| 2215 | `(if (:seon.error/kind entries) entries` | R2 |
| 2224 | `(if (:seon.error/kind entries) entries` | R2 |
| 2239 | `_ (when (:seon.error/kind seals)` | R2 |
| 2253 | `_ (when (:seon.error/kind entities)` | R2 |
| 2258 | `(when (:seon.error/kind rows)` | R2 |
| 2271 | `{:seon.error/kind :seon.test.run/unavailable` | R1 |
| 2283 | `(if (:seon.error/kind digest) digest` | R2 |
| 2307 | `{:seon.error/kind kind` | R1 |
| 2319 | `(when (:seon.error/kind value)` | R2 |
| 2467 | `_ (when (:seon.error/kind threshold)` | R2 |
| 2720 | `reaches (if (:seon.error/kind derived-reaches)` | R2 |
| 2755 | `(when (:seon.error/kind previous)` | R2 |
| 2759 | `{:seon.error/kind :seon.test.run/immutable` | R1 |
| 2776 | `{:seon.error/kind ::test-definition-absent` | R1 |
| 2941 | `(if (:seon.error/kind (ex-data failure))` | R2 |
| 2944 | `{:seon.error/kind :seon.test/population-unknown` | R1 |
| 2991 | `(if (:seon.error/kind database)` | R2 |
| 2996 | `(if (:seon.error/kind transaction-report)` | R2 |
| 3010 | `(or (first (filter :seon.error/kind recorded)) recorded)))))` | R2 |
| 3056 | `{:seon.error/kind ::default-cluster-refused` | R1 |
| 3103 | `{:seon.error/kind ::staged-completion-unreadable` | R1 |
| 3110 | `{:seon.error/kind ::staged-completion-unreadable` | R1 |
| 3117 | `{:seon.error/kind ::staged-completion-unreadable` | R1 |
| 3130 | `(if (:seon.error/kind completion)` | R2 |
| 3167 | `{:seon.error/kind` | R5 |
| 3172 | `{:seon.error/kind` | R5 |
| 3173 | `(or (:seon.error/kind (ex-data failure#))` | R1 |
| 3210 | `(:seon.error/kind result) result` | R2 |
| 3212 | `:else {:seon.error/kind ::persistent-results-recording-failed` | R1 |
| 3217 | `(cond-> {:seon.error/kind` | R5 |
| 3218 | `(or (:seon.error/kind data)` | R1 |
| 3242 | `(str (:seon.error/kind failure))` | R1 |
| 3269 | `{:seon.error/kind (if (= "-" cluster-name)` | R2 |
| 3419 | `:seon.error/kind ::worker-retired` | R1 |
| 3453 | `:seon.error/kind ::worker-write-failure` | R1 |
| 3470 | `:seon.error/kind (if (= :re-arming @phase)` | R2 |
| 3483 | `:seon.error/kind ::worker-exchange-bound` | R1 |
| 3491 | `:seon.error/kind ::worker-exchange-interrupted` | R1 |
| 3498 | `:seon.error/kind ::worker-exchange-failed` | R1 |
| 3516 | `{:seon.error/kind :seon.test/classpath-unavailable})))` | R1 |
| 3541 | `{:seon.error/kind ::worker-launch-failure` | R1 |
| 3569 | `:seon.error/kind ::worker-launch-failure` | R1 |
| 3570 | `::underlying-failure-kind (:seon.error/kind ready)` | R1 |
| 3649 | `{:seon.error/kind ::process-tree-exit-backstop` | R1 |
| 3903 | `underlying-kind (:seon.error/kind (ex-data failure))` | R1 |
| 3911 | `:seon.error/kind failure-kind` | R1 |
| 4107 | `(str "kind=" (:seon.error/kind exchange))]` | R1 |
| 4134 | `"kind=" (:seon.error/kind failure))))))` | R6 |
| 4272 | `{:seon.error/kind ::invalid-selection-mode` | R1 |
| 4508 | `(when (:seon.error/kind captured)` | R2 |

### src/seon/test/selection.clj

| Line | Source | Replacement |
|---:|---|---|
| 236 | `{:seon.error/kind ::invalid-basis` | R1 |

### src/seon/turn.clj

| Line | Source | Replacement |
|---:|---|---|
| 99 | `kind (or (:seon.error/kind (:seon.sci.admit/value evaluation))` | R1 |
| 100 | `(:seon.error/kind form-problem))]` | R2 |
| 118 | `kind (assoc :seon.error/kind kind)` | R1 |
| 284 | `{:seon.error/kind ::missing-opening-datom` | R1 |
| 295 | `;;; - THROWS ex-info {:seon.error/kind :seon.turn/refused, ...}` | R6 |
| 308 | `{:seon.error/kind ::refused` | R1 |
| 376 | `(if (:seon.error/kind row)` | R2 |
| 1023 | `:seon.error/kind :user-input})))))` | R1 |
| 1352 | `:seon.error/kind` | R5 |
| 1616 | `[:seon.error/kind {:optional true} :seon.error/kind]` | R1 |
| 1913 | `(if (:seon.error/kind parsed)` | R2 |
| 1922 | `(if (:seon.error/kind sources) sources` | R2 |
| 2061 | `{:seon.error/kind ::generated-read-depends-on-turns` | R1 |
| 2089 | `{:seon.error/kind ::agent-namespace-missing` | R1 |
| 2099 | `(:seon.error/kind declared) declared` | R2 |
| 2146 | `(or (some #(when (:seon.error/kind %) %) previews)` | R2 |
| 2234 | `(if (:seon.error/kind report) report` | R2 |
| 2268 | `{:seon.error/kind ::compaction-refused` | R1 |
| 3099 | `(= ::reply/no-forms (:seon.error/kind parsed)) parsed` | R2 |
| 3192 | `_ (when (:seon.error/kind analysis)` | R2 |
| 3278 | `{:seon.error/kind :seon.flow/time-limit` | R1 |
| 3382 | `(merge {:seon.error/kind :seon.turn.loop/phase-failed` | R1 |
| 3559 | `(if (:seon.error/kind outcome)` | R2 |
| 3599 | `:seon.error/kind (:seon.error/kind value)}))]` | R1 |
| 3643 | `:seon.error/kind (:seon.error/kind value))))` | R1 |
| 3659 | `(when (:seon.error/kind outcome)` | R2 |
| 3662 | `{:seon.error/kind :seon.turn.loop/terminal-refusal-settlement-refused` | R1 |
| 3700 | `(if (:seon.error/kind prepared)` | R2 |
| 3717 | `(if-not (:seon.error/kind outcome)` | R2 |
| 3724 | `(when (:seon.error/kind refused)` | R2 |
| 3727 | `{:seon.error/kind :seon.turn.loop/terminal-refusal-settlement-refused` | R1 |
| 3780 | `(:seon.error/kind completion))` | R2 |
| 3962 | `(if (:seon.error/kind outcome)` | R2 |
| 4072 | `outcome (if (:seon.error/kind refreshed)` | R2 |
| 4079 | `(:seon.error/kind outcome)` | R2 |
| 4201 | `no-forms? (= ::reply/no-forms (:seon.error/kind prepared))` | R2 |
| 4229 | `(:seon.error/kind outcome) (fail! outcome true)` | R2 |
| 4230 | `(and (:seon.error/kind prepared) (not no-forms?)) (fail! prepared)` | R1 |
| 4286 | `(if (:seon.error/kind rendered)` | R2 |
| 4290 | `:seon.db/db (if (:seon.error/kind prompt-db)` | R2 |
| 4304 | `(:seon.error/kind captured)` | R2 |
| 4311 | `(:seon.error/kind rendered)` | R2 |
| 4331 | `failure (when (:seon.error/kind completion) completion)` | R2 |
| 4360 | `(and (:seon.error/kind fact) (nil? (:seon.error/id fact))) (fail! fact)` | R2 |
| 4415 | `(if (:seon.error/kind closed)` | R2 |
| 4444 | `{:seon.error/kind :seon.turn/invalid-disposition` | R1 |
| 4528 | `(if (:seon.error/kind evaluation)` | R2 |
| 4532 | `:seon.error/kind (:seon.error/kind evaluation)}` | R1 |
| 4588 | `(if (:seon.error/kind forked)` | R2 |
| 4623 | `(if-let [failure (some #(when (:seon.error/kind %) %)` | R2 |
| 4671 | `(:seon.error/kind evaluated) evaluated` | R2 |
| 4674 | `(if (:seon.error/kind analyzed)` | R2 |
| 4728 | `(and problem (not (:seon.error/kind problem)))` | R1 |
| 4735 | `(if (or (:seon.error/kind outcome)` | R2 |
| 4739 | `(when (:seon.error/kind outcome) outcome)))` | R2 |
| 4767 | `(if (:seon.error/kind outcome)` | R2 |
| 4796 | `(if (:seon.error/kind declared)` | R2 |
| 4800 | `(:seon.error/kind entry)` | R2 |
| 4818 | `(if (:seon.error/kind terminal)` | R2 |
| 4850 | `(if (:seon.error/kind appended)` | R2 |
| 4907 | `(:seon.error/kind refusal)` | R2 |
| 4931 | `(if (:seon.error/kind refreshed)` | R2 |
| 4954 | `{:seon.error/kind :seon.agent/turn-completion-backstop` | R1 |
| 5058 | `{:seon.error/kind :seon.config/required-absent` | R1 |
| 5072 | `{:seon.error/kind :seon.turn.loop/write-refusals-exhausted` | R1 |
| 5086 | `:seon.error/diagnostic-offending (:seon.error/kind refusal)` | R1 |
| 5093 | `:seon.error/kind (:seon.error/kind refusal)}` | R1 |
| 5155 | `{:seon.error/kind :seon.agent/turn-completion-backstop` | R1 |
| 5340 | `{:seon.error/kind :seon.agent/turn-completion-undeliverable` | R1 |

### test/my/background_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 37 | `(:seon.error/kind` | R8 |

### test/my/examples_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 53 | `(is (not (:seon.error/kind` | R8 |
| 136 | `(is (= :seon.instrument/contract-violated (:seon.error/kind value)) (pr-str failed))` | R8 |
| 144 | `(get-in failed [:seon.sci.admit/value :seon.error/kind]))` | R8 |
| 170 | `(is (nil? (get-in result [:seon.sci.admit/value :seon.error/kind]))` | R8 |

### test/my/message_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 137 | `(is (= :seon.message/unknown-recipient (:seon.error/kind missing)))` | R8 |
| 200 | `(is (keyword? (:seon.error/kind value)) source)` | R8 |

### test/my/note_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 70 | `(is (= :my.note/not-found (:seon.error/kind missing)))` | R8 |

### test/my/plan_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 91 | `(is (= :my.plan/agent-not-found (:seon.error/kind refusal))` | R8 |
| 105 | `(is (not (contains? empty-plan :seon.error/kind))))` | R8 |
| 200 | `(:seon.error/kind (plan/start! "prepare" connection "bob"))))` | R8 |
| 209 | `(:seon.error/kind (plan/start! "prepare" connection "alice"))))` | R8 |
| 342 | `(:seon.error/kind` | R8 |
| 352 | `(:seon.error/kind` | R8 |
| 366 | `(:seon.error/kind` | R8 |
| 374 | `(:seon.error/kind` | R8 |

### test/my/program_mutation_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 21 | `(is (= :seon.program/declaration-refused (:seon.error/kind refused)))` | R8 |
| 37 | `(get-in result [:seon.sci.admit/value :seon.error/kind]))` | R8 |
| 44 | `(is (not (:seon.error/kind removed)) (pr-str removed))` | R8 |
| 72 | `(is (= :seon.program/declaration-refused (:seon.error/kind value)) (pr-str result))` | R8 |
| 100 | `(is (not (:seon.error/kind result)) (pr-str result))` | R8 |
| 108 | `(is (not (:seon.error/kind result)) (pr-str result))` | R8 |

### test/my/program_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 27 | `(is (not (:seon.error/kind report)) (pr-str report))` | R8 |
| 48 | `(is (= :seon.program/not-found (:seon.error/kind missing)))` | R8 |
| 59 | `(is (not (:seon.error/kind key-report)) (pr-str key-report))` | R8 |
| 94 | `(is (not (:seon.error/kind snapshot)) (pr-str snapshot))` | R8 |
| 98 | `(is (not (:seon.error/kind metadata-snapshot)) (pr-str metadata-snapshot))` | R8 |
| 107 | `(is (not (:seon.error/kind result)) (pr-str result))` | R8 |

### test/my/turn_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 64 | `(is (keyword? (:seon.error/kind value)) source)` | R8 |

### test/seon/adoption_diagnostic_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 16 | `{:seon.error/kind ::missing-namespace` | R8 |
| 41 | `{:seon.error/kind ::missing-namespace` | R8 |

### test/seon/adoption_margin_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 33 | `(is (= :seon.operator/lock-hold-timeout (:seon.error/kind failure)))` | R8 |
| 68 | `(is (= :seon.cluster/source-observer-closed (:seon.error/kind failure)))` | R8 |

### test/seon/adoption_rows_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 71 | `[?error :seon.error/kind` | R8 |
| 78 | `[?e :seon.error/kind :seon.sci.eval/acquisition-refused]]` | R8 |
| 83 | `(pr-str (mapv #(select-keys % [:seon.error/message :seon.error/kind])` | R8 |

### test/seon/agent_call_edges_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 59 | `(when (or (:seon.error/kind entries) (seq failures))` | R8 |
| 62 | `(if (:seon.error/kind entries) entries (mapv #(select-keys % [:seon.cluster.eval/source :seon.cluster.eval/error :seon.eval/shown]) failures))}))))` | R8 |
| 76 | `(when (:seon.error/kind created) (throw (ex-info "Probe agent refused" created))))` | R8 |

### test/seon/agent_situation_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 65 | `(:seon.error/kind` | R8 |

### test/seon/ai_stream_fold_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 135 | `(is (= :seon.ai/unparseable-body (:seon.error/kind result))` | R8 |
| 147 | `(is (= :seon.ai/unparseable-body (:seon.error/kind result)))` | R8 |
| 166 | `(is (= :seon.ai/unparseable-body (:seon.error/kind completion)))` | R8 |
| 173 | `(is (= :seon.ai/unparseable-body (:seon.error/kind completion)))` | R8 |
| 251 | `(is (= :seon.ai/unparseable-body (:seon.error/kind completion)))` | R8 |
| 271 | `(is (nil? (:seon.error/kind completion)))` | R8 |
| 313 | `(is (= :seon.ai/unparseable-body (:seon.error/kind completion)))))))` | R8 |
| 320 | `(is (= :seon.ai/provider-error (:seon.error/kind completion)))` | R8 |
| 342 | `(is (= :seon.ai/provider-error (:seon.error/kind failure)))` | R8 |

### test/seon/ai_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 41 | `(and (map? value) (keyword? (:seon.error/kind value))` | R8 |
| 363 | `(is (= :seon.ai/no-credential (:seon.error/kind outcome)))` | R8 |
| 665 | `(is (nil? (:seon.error/kind created)) (pr-str created))` | R8 |
| 666 | `(when (:seon.error/kind created)` | R8 |
| 714 | `(get-in result [:seon.sci.admit/value :seon.error/kind])) id))))))` | R8 |
| 738 | `(is (= :seon.ai/extra-body-conflict (:seon.error/kind failure)))` | R8 |
| 743 | `(:seon.error/kind` | R8 |
| 754 | `(is (= :seon.ai/extra-body-conflict (:seon.error/kind outcome)))` | R8 |
| 792 | `(is (= :seon.ai/unparseable-body (:seon.error/kind outcome)))))))` | R8 |
| 802 | `(:seon.error/kind (reply/sources (:seon.ai/text completion)))))))` | R8 |
| 808 | `(is (= expected (:seon.error/kind completion)))` | R8 |
| 847 | `(:seon.error/kind failure)))` | R8 |
| 902 | `(:seon.error/kind failure)))` | R8 |
| 958 | `(is (nil? (:seon.error/kind completion))` | R8 |
| 962 | `(is (= :seon.ai/stream-truncated (:seon.error/kind truncation))` | R8 |
| 977 | `(is (= :seon.ai/stream-truncated (:seon.error/kind completion))` | R8 |
| 993 | `(is (= :seon.ai/stream-truncated (:seon.error/kind failure)))` | R8 |
| 1037 | `(is (= :seon.ai/no-credential (:seon.error/kind outcome)))` | R8 |
| 1069 | `(:seon.error/kind outcome)))))` | R8 |
| 1082 | `(:seon.error/kind outcome)))` | R8 |
| 1238 | `(is (nil? (:seon.error/kind completion)))` | R8 |
| 1239 | `(is (= :seon.ai/stream-truncated (:seon.error/kind truncation)))` | R8 |
| 1361 | `{:seon.error/kind kind` | R8 |
| 1476 | `(is (= :seon.ai/transport-failure (:seon.error/kind value))` | R8 |
| 1500 | `(is (= :seon.ai/no-credential (:seon.error/kind value)))` | R8 |

### test/seon/await_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 46 | `(is (= ::await/backstop-fired (:seon.error/kind result)))` | R8 |
| 70 | `(is (= ::await/backstop-fired (:seon.error/kind result)))` | R8 |
| 88 | `(is (= ::await/completion-closed (:seon.error/kind result)))` | R8 |

### test/seon/bootstrap_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 396 | `(is (= :seon.config/required-absent (:seon.error/kind result)))` | R8 |
| 406 | `{:seon.error/kind :seon.db/invalid-read` | R8 |
| 426 | `(is (= expected-kind (:seon.error/kind result)) label)` | R8 |

### test/seon/bounded_boundary_census_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 57 | `{:seon.error/kind` | R8 |
| 285 | `{:seon.error/kind` | R8 |
| 329 | `(:seon.error/kind (ex-data absent-error))))` | R8 |
| 337 | `(:seon.error/kind (ex-data error)))))))))` | R8 |

### test/seon/call_preparation_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 271 | `(:seon.error/kind refusal)))` | R8 |
| 300 | `(:seon.error/kind refusal)))` | R8 |
| 424 | `(is (= :seon.db/unsupplied-custody (:seon.error/kind refusal)))` | R8 |
| 493 | `(:seon.error/kind refusal)))` | R8 |
| 505 | `(:seon.error/kind refusal)))` | R8 |
| 553 | `(:seon.error/kind refusal)))` | R8 |
| 598 | `(is (= :seon.call-preparation/unavailable (:seon.error/kind refusal)))` | R8 |
| 628 | `(:seon.error/kind result)))` | R8 |
| 700 | `(:seon.error/kind` | R8 |
| 741 | `(is (= :my.plan/agent-not-found (:seon.error/kind omitted)))` | R8 |

### test/seon/classification_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 67 | `(is (not (:seon.error/kind acquired)) (pr-str acquired))` | R8 |
| 91 | `(is (not (:seon.error/kind report)) (pr-str report)))` | R8 |

### test/seon/cluster/agent_arming_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 135 | `(is (nil? (:seon.error/kind created)) (pr-str created))` | R8 |
| 193 | `(is (nil? (:seon.error/kind started)) (pr-str started))` | R8 |

### test/seon/cluster/agent_identity_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 58 | `(is (:seon.error/kind database-error))` | R8 |

### test/seon/cluster/agent_namespace_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 81 | `(is (nil? (:seon.error/kind result))` | R8 |

### test/seon/cluster/agent_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 583 | `{:seon.error/kind :seon.cluster.prompt/refused` | R8 |
| 601 | `[?error :seon.error/kind :seon.cluster.prompt/refused]]` | R8 |
| 899 | `{:seon.error/kind ::provider-released` | R8 |
| 939 | `(:seon.error/kind (ex-data failure))))` | R8 |
| 1005 | `(:seon.error/kind (ex-data failure))))` | R8 |
| 1061 | `(:seon.error/kind (ex-data (::flow/ex fault)))))` | R8 |
| 1121 | `(is (= :seon.turn.loop/phase-failed (:seon.error/kind diagnostic)))` | R8 |
| 1124 | `(:seon.error/kind (edn/read-string (:seon.eval/shown evaluation))))` | R8 |
| 1761 | `(select-keys [:seon.error/kind :seon.error/message]))}` | R8 |
| 1765 | `{:seon.error/kind ::routing-watch-closed})))` | R8 |

### test/seon/cluster/armed_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 237 | `(:seon.error/kind` | R8 |
| 354 | `{:seon.error/kind ::injected})))]` | R8 |
| 359 | `(is (= ::injected (:seon.error/kind fact)))` | R8 |
| 429 | `(:seon.error/kind %))` | R8 |
| 452 | `{:seon.error/kind ::first-cluster-proc-fault}))` | R8 |
| 470 | `(:seon.error/kind %))` | R8 |
| 474 | `(is (= ::first-cluster-proc-fault (:seon.error/kind fact)))` | R8 |

### test/seon/cluster/boot_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 261 | `(is (= :seon.boot/refused (:seon.error/kind outer-data)))` | R8 |
| 751 | `(is (nil? (:seon.error/kind` | R8 |
| 753 | `{:seon.error/kind ::transaction-stuck})))` | R8 |
| 1741 | `{:seon.error/kind ::child-exited-before-derivation` | R8 |
| 1783 | `:where [_ :seon.error/kind ?kind]]` | R8 |

### test/seon/cluster/evaluate_sources_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 137 | `(is (nil? (:seon.error/kind` | R8 |
| 142 | `(is (nil? (:seon.error/kind` | R8 |
| 148 | `_refusal (is (:seon.error/kind` | R8 |
| 150 | `_close (is (nil? (:seon.error/kind` | R8 |
| 171 | `(is (nil? (:seon.error/kind committed)) (pr-str (select-keys committed [:seon.error/kind :seon.error/message :seon.turn/refused])))` | R8 |

### test/seon/cluster/instruction_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 156 | `(is (nil? (:seon.error/kind` | R8 |
| 161 | `(is (nil? (:seon.error/kind` | R8 |

### test/seon/cluster/mcp_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 32 | `(is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))` | R8 |
| 296 | `(is (= :seon.dev.mcp/nil-deref (:seon.error/kind face)))` | R8 |
| 351 | `(if (:seon.error/kind effective)` | R8 |
| 360 | `(is (not (contains? result :seon.error/kind)))))` | R8 |
| 481 | `(:seon.error/kind` | R8 |

### test/seon/cluster/message_assignment_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 27 | `:seon.error/kind :seon.sci.eval/evaluation-failed` | R8 |

### test/seon/cluster/message_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 188 | `(let [failure {:seon.error/kind ::read-failed` | R8 |
| 475 | `(mapv :seon.error/kind (:seon.error/values delivery)))` | R8 |
| 642 | `:kinds (mapv :seon.error/kind` | R8 |
| 837 | `(mapv :seon.error/kind results)))` | R8 |

### test/seon/cluster/problem_routing_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 85 | `{:seon.error/kind :seon.sci.eval/evaluation-failed` | R8 |
| 89 | `(pr-str {:seon.error/kind :seon.sci.eval/evaluation-failed` | R8 |
| 140 | `(pr-str {:seon.error/kind :probe/self-owned-red})` | R8 |
| 142 | `:seon.error/kind :probe/self-owned-red})` | R8 |
| 204 | `(pr-str {:seon.error/kind :probe/red})` | R8 |
| 206 | `:seon.error/kind :probe/red})` | R8 |
| 208 | `(pr-str {:seon.error/kind :probe/red})` | R8 |
| 210 | `:seon.error/kind :probe/red})` | R8 |
| 213 | `(pr-str {:seon.error/kind :probe/red})` | R8 |
| 215 | `:seon.error/kind :probe/red})])` | R8 |

### test/seon/cluster/program_restart_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 44 | `{:seon.error/kind ::commit-timeout` | R8 |
| 251 | `(:seon.error/kind` | R8 |

### test/seon/cluster/prompt_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 51 | `(when (:seon.error/kind result)` | R8 |
| 151 | `(is (not (:seon.error/kind result)) (pr-str result)))` | R8 |
| 186 | `(is (not (:seon.error/kind result)) (pr-str result))` | R8 |
| 187 | `(is (not (:seon.error/kind rendered)) (pr-str rendered))` | R8 |
| 438 | `(is (not (:seon.error/kind result)) (pr-str result))))` | R8 |
| 506 | `(is (nil? (:seon.error/kind replayed))` | R8 |
| 517 | `(:seon.error/kind drifted)))` | R8 |

### test/seon/cluster/registry_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 324 | `{:seon.error/kind ::injected})]` | R8 |

### test/seon/cluster/reply_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 53 | `(is (= :seon.cluster.reply/no-forms (:seon.error/kind failure)))` | R8 |
| 178 | `(is (= :seon.cluster.reply/no-forms (:seon.error/kind result)))` | R8 |
| 311 | `(:seon.error/kind (reply/sources "Only prose here."))))))` | R8 |
| 334 | `(is (= :seon.sci.reader/unreadable (:seon.error/kind refused)))` | R8 |
| 340 | `(is (= :seon.sci.reader/unreadable (:seon.error/kind refused)))))` | R8 |
| 346 | `(:seon.error/kind refused)))))` | R8 |
| 351 | `(:seon.error/kind (sources "   \n\n  "))))))` | R8 |
| 415 | `(is (= :seon.cluster.reply/no-forms (:seon.error/kind result)))` | R8 |

### test/seon/cluster/resume_artifact_routing_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 17 | `{:seon.error/kind :seon.sci.eval/evaluation-failed` | R8 |
| 21 | `(pr-str {:seon.error/kind :seon.sci.eval/evaluation-failed` | R8 |
| 77 | `:seon.error/kind` | R8 |

### test/seon/cluster/source_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 202 | `(:seon.error/kind second-result)))` | R8 |
| 322 | `{:seon.error/kind :seon.db/invalid-transaction` | R8 |
| 329 | `[:seon.source/transaction-result :seon.error/kind])))` | R8 |
| 411 | `(is (= :seon.db/invalid-write (:seon.error/kind refused))` | R8 |

### test/seon/cluster/status_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 36 | `(is (not (:seon.error/kind result)) (pr-str result)))` | R8 |
| 46 | `(get-in row [:seon.cluster.status/provider-cost-usd :seon.error/kind]))))` | R8 |
| 62 | `(is (not (:seon.error/kind report)) (pr-str report))` | R8 |
| 102 | `(get-in observation [:seon.cluster.status/store-bytes :seon.error/kind])))` | R8 |

### test/seon/cluster/store_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 222 | `(:seon.error/kind` | R8 |
| 285 | `(:seon.error/kind (db/history @connection))))` | R8 |
| 329 | `(is (= :seon.db/rejected (:seon.error/kind outcome)))` | R8 |

### test/seon/cluster/store_transact_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 64 | `(let [buried (wrap (ex-info "refused" {:seon.error/kind ::refused` | R8 |
| 100 | `{:seon.error/kind :seon.turn/refused` | R8 |
| 111 | `(is (nil? (:seon.error/kind outcome)))))))` | R8 |
| 119 | `(is (= :seon.turn/refused (:seon.error/kind outcome))` | R8 |
| 132 | `(is (= :seon.db/rejected (:seon.error/kind outcome)))` | R8 |
| 180 | `(is (= :user-input (:seon.error/kind outcome)))` | R8 |
| 195 | `(is (= :user-input (:seon.error/kind failure)))` | R8 |

### test/seon/cluster/turn_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 100 | `(when (:seon.error/kind rows)` | R8 |
| 209 | `(when (:seon.error/kind result)` | R8 |
| 213 | `(when (:seon.error/kind result)` | R8 |
| 222 | `(when (:seon.error/kind result)` | R8 |
| 405 | `(is (nil? (:seon.error/kind configured)))` | R8 |
| 725 | `{:seon.error/kind :seon.db/rejected` | R8 |
| 812 | `(when (:seon.error/kind seed)` | R8 |
| 846 | `{:seon.error/kind :seon.db/rejected` | R8 |
| 873 | `(:seon.error/kind (first evaluations))))` | R8 |
| 875 | `(:seon.error/kind` | R8 |
| 1221 | `{:seon.error/kind :seon.db/rejected` | R8 |
| 1434 | `[:seon.error/kind` | R8 |
| 1438 | `[?evaluation :seon.error/kind` | R8 |
| 1490 | `{:seon.error/kind :seon.db/rejected` | R8 |
| 1602 | `(fn [_] {:seon.error/kind :seon.ai/no-credential` | R8 |
| 1635 | `(is (nil? (:seon.error/kind seeded))` | R8 |
| 1800 | `(is (= error-kind (:seon.error/kind evaluation)))` | R8 |
| 1913 | `(is (empty? (db/q '[:find ?e :where [?e :seon.error/kind _]]` | R8 |
| 1955 | `(when (:seon.error/kind report)` | R8 |
| 1962 | `{:seon.error/kind kind` | R8 |
| 2109 | `{:seon.error/kind :seon.ai/stream-truncated` | R8 |
| 2172 | `failure {:seon.error/kind :seon.ai/token-starvation` | R8 |
| 2202 | `(is (= :seon.ai/token-starvation (:seon.error/kind error-fact))` | R8 |
| 2213 | `{:seon.error/kind :seon.ai/stream-truncated` | R8 |
| 2238 | `(is (= :seon.ai/stream-truncated (:seon.error/kind error-fact)))` | R8 |
| 2475 | `(when (:seon.error/kind report)` | R8 |
| 2582 | `(is (= :seon.message/unknown-recipient (:seon.error/kind @actual-value)))` | R8 |
| 2728 | `(is (nil? (:seon.error/kind (recover-cut-run! connection run-id))))` | R8 |
| 2997 | `(:seon.error/kind` | R8 |
| 3119 | `{:seon.error/kind :seon.bootstrap/root-acquisition-empty` | R8 |
| 3157 | `failure {:seon.error/kind` | R8 |
| 3262 | `refusal {:seon.error/kind :seon.cluster.prompt/refused` | R8 |
| 3282 | `[?e :seon.error/kind ?kind]]` | R8 |
| 3712 | `(is (nil? (:seon.error/kind seeded)) (pr-str (:seon.error/kind seeded)))` | R8 |
| 3713 | `(when (:seon.error/kind seeded) (throw (ex-info "Budget fixture refused" seeded)))` | R8 |

### test/seon/cluster/wake_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 138 | `(is (not (:seon.error/kind` | R8 |
| 151 | `(is (nil? (:seon.error/kind result)) (pr-str result))` | R8 |
| 420 | `(:seon.error/kind` | R8 |
| 430 | `(:seon.error/kind refusal))` | R8 |
| 448 | `{:seon.error/kind :seon.instrument/contract-violated` | R8 |
| 618 | `(:seon.error/kind (ex-data fault)))` | R8 |
| 643 | `(:seon.error/kind (ex-data fault))))` | R8 |

### test/seon/cluster_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 140 | `{:seon.error/kind :seon.cluster-test/probe` | R8 |
| 156 | `(is (= :seon.instrument/contract-violated (:seon.error/kind refusal))` | R8 |
| 202 | `(is (= :seon.boot/refused (:seon.error/kind refusal))` | R8 |
| 300 | `(is (= :seon.boot/refused (:seon.error/kind refusal))` | R8 |
| 347 | `{:seon.error/kind :seon.boot/refused` | R8 |
| 362 | `[(:seon.error/kind (ex-data analysis-failure))` | R8 |
| 368 | `(phase-of (ex-info "unrelated" {:seon.error/kind :seon.fn/index-refused}))])` | R8 |
| 384 | `(is (= :seon.boot/refused (:seon.error/kind surviving))` | R8 |
| 408 | `(is (= :seon.boot/refused (:seon.error/kind refusal))` | R8 |

### test/seon/concurrency_independence_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 177 | `(is (not (:seon.error/kind result))` | R8 |
| 219 | `(is (not (:seon.error/kind result))` | R8 |
| 310 | `:seon.error/kind` | R8 |
| 598 | `:seon.error/kind :user-input}` | R8 |
| 605 | `[run-id 2 :seon.error/kind :user-input]` | R8 |

### test/seon/concurrency_streams_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 80 | `refusals (filterv :seon.error/kind results)` | R8 |
| 91 | `(:seon.error/kind (first refusals))))` | R8 |

### test/seon/concurrency_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 58 | `(is (not (:seon.error/kind result)))` | R8 |
| 92 | `(when (:seon.error/kind created)` | R8 |

### test/seon/config_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 264 | `(let [flat-error {:seon.error/kind :seon.db/rejected` | R8 |
| 273 | `(is (= :seon.config/refused (:seon.error/kind result)))` | R8 |
| 286 | `(is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))` | R8 |
| 419 | `(is (= :seon.instrument/contract-violated (:seon.error/kind data)))` | R8 |
| 508 | `(is (:seon.error/kind refused))` | R8 |
| 520 | `(let [refusal {:seon.error/kind :seon.config/required-absent` | R8 |
| 524 | `(is (= ::config/missing-result-cap (:seon.error/kind result)))` | R8 |
| 559 | `(:seon.error/kind (config/result-caps missing-beta))))` | R8 |
| 561 | `(:seon.error/kind (config/result-caps missing-alpha))))` | R8 |
| 580 | `refusal {:seon.error/kind :seon.db/invalid-read` | R8 |
| 583 | `(is (= :seon.db/invalid-read (:seon.error/kind result))` | R8 |
| 585 | `(is (not= ::config/missing-effective (:seon.error/kind result))` | R8 |

### test/seon/context_blocks_fixture.clj

| Line | Source | Replacement |
|---:|---|---|
| 140 | `(when (:seon.error/kind result)` | R8 |
| 344 | `[:seon.turn/id :seon.error/kind :seon.error/message]))))))` | R8 |

### test/seon/context_capture_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 24 | `_ (is (nil? (:seon.error/kind seed)))` | R8 |
| 48 | `(is (nil? (:seon.error/kind committed)))` | R8 |

### test/seon/context_selection_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 59 | `_ (is (nil? (:seon.error/kind seed)))` | R8 |
| 69 | `(is (nil? (:seon.error/kind committed)))` | R8 |
| 125 | `(is (nil? (:seon.error/kind` | R8 |
| 127 | `(is (nil? (:seon.error/kind` | R8 |
| 210 | `(is (nil? (:seon.error/kind committed)))` | R8 |

### test/seon/contracts_fixture.clj

| Line | Source | Replacement |
|---:|---|---|
| 51 | `(when (:seon.error/kind value)` | R8 |

### test/seon/contracts_install_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 30 | `(is (= :seon.test.accretion/non-data-contract (:seon.error/kind value)))` | R8 |
| 45 | `(get-in rejected [:seon.sci.admit/value :seon.error/kind])))` | R8 |
| 54 | `(get-in result [:seon.sci.admit/value :seon.error/kind]))` | R8 |

### test/seon/contracts_plan_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 13 | `(is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))` | R8 |
| 28 | `(is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))` | R8 |
| 44 | `(is (= :seon.sci.reader/unreadable (:seon.error/kind refusal)))` | R8 |
| 55 | `(is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))` | R8 |
| 65 | `(is (= :seon.sci.eval/evaluation-failed (:seon.error/kind refusal)))` | R8 |

### test/seon/custody_stability_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 314 | `[:seon.sci.admit/value :seon.error/kind])))` | R8 |

### test/seon/data_shapes_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 215 | `(is (not (:seon.error/kind created)) (pr-str created))` | R8 |
| 216 | `(is (not (:seon.error/kind opened)) (pr-str opened)))` | R8 |
| 232 | `(is (not (:seon.error/kind changed)) (pr-str changed))` | R8 |
| 391 | `(is (not (:seon.error/kind created)) (pr-str created))` | R8 |
| 392 | `(is (not (:seon.error/kind changed)) (pr-str changed))` | R8 |

### test/seon/db/declaration_population_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 71 | `(and (not (:seon.error/kind supplied)) (= supplied carried))))` | R8 |
| 122 | `(is (= :seon.schema/missing-projection (:seon.error/kind failure)))` | R8 |
| 136 | `:error (:seon.error/kind report)}))` | R8 |

### test/seon/db_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 182 | `(is (= :seon.schema/missing-projection (:seon.error/kind rows)))` | R8 |
| 324 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal)))` | R8 |
| 331 | `(let [refusal {:seon.error/kind :seon.turn/refused` | R8 |
| 357 | `(is (= :seon.turn/refused (:seon.error/kind result)))` | R8 |
| 449 | `(is (= :seon.db/invalid-read (:seon.error/kind result)))` | R8 |
| 592 | `(:seon.error/kind result)))` | R8 |
| 599 | `(let [upstream {:seon.error/kind :seon.db-test/upstream` | R8 |
| 906 | `(:seon.error/kind answer)))` | R8 |
| 1064 | `(is (nil? (:seon.error/kind accepted))` | R8 |
| 1117 | `(is (= :seon.db/rejected (:seon.error/kind rejected)))` | R8 |
| 1160 | `(is (= :seon.db/rejected (:seon.error/kind rejected)))` | R8 |
| 1216 | `(:seon.error/kind refusal)))` | R8 |
| 1347 | `(:seon.error/kind report))` | R8 |
| 1508 | `(:seon.error/kind result)))` | R8 |
| 1515 | `(:seon.error/kind (db/history))))))` | R8 |
| 1536 | `(is (= :seon.db/invalid-read (:seon.error/kind result)))` | R8 |
| 1546 | `(is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))` | R8 |
| 1567 | `(is (= :seon.db/invalid-read (:seon.error/kind unknown-attribute)))` | R8 |
| 1585 | `(is (= :seon.db/invalid-read (:seon.error/kind result)))` | R8 |
| 1624 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal)))` | R8 |
| 1647 | `(is (= :seon.db/invalid-read (:seon.error/kind result)))` | R8 |
| 1678 | `(is (not= :seon.db/invalid-read (:seon.error/kind installed))` | R8 |
| 1680 | `(is (= :seon.db/invalid-read (:seon.error/kind uninstalled)))` | R8 |
| 1702 | `(:seon.error/kind result)))` | R8 |
| 1726 | `(:seon.error/kind refusal)))` | R8 |
| 1768 | `(is (nil? (:seon.error/kind explicit))` | R8 |
| 1770 | `(is (nil? (:seon.error/kind elided))` | R8 |
| 1772 | `(is (= :seon.db/foreign-connection (:seon.error/kind refused)))` | R8 |
| 1831 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal)) (pr-str refusal))` | R8 |
| 1846 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal)))` | R8 |
| 1857 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal))` | R8 |
| 1897 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal)) (pr-str refusal))` | R8 |
| 1940 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal)) (pr-str refusal))` | R8 |
| 1983 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal)) (pr-str refusal))` | R8 |
| 2008 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal)) (pr-str refusal))` | R8 |
| 2021 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal)) (pr-str refusal))` | R8 |
| 2155 | `(is (= :seon.db/unreadable-declarations (:seon.error/kind refusal)))` | R8 |
| 2182 | `(is (= :seon.db/unknown-read-operation (:seon.error/kind unknown)))` | R8 |
| 2246 | `(is (= :seon.db/invalid-pulled-result (:seon.error/kind refusal))` | R8 |

### test/seon/dev/changed_test_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 117 | `(:seon.error/kind data)))` | R8 |
| 136 | `(:seon.error/kind (ex-data failure))))))` | R8 |
| 153 | `(:seon.error/kind (ex-data failure))))` | R8 |

### test/seon/dev/dependency_cache_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 275 | `(:seon.error/kind refusal)))` | R8 |

### test/seon/dev/edit_feedback_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 50 | `failure {:seon.error/kind :publication-failed` | R8 |

### test/seon/dev/fresh_operator_reset_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 67 | `(is (= :seon.operator/lock-acquisition-timeout (:seon.error/kind refusal)))` | R8 |
| 389 | `(if (:seon.error/kind test-var#)` | R8 |
| 399 | `[:seon.error/kind :seon.test/sym` | R8 |

### test/seon/dev/fresh_operator_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 1042 | `:seon.error/kind])))` | R8 |
| 1080 | `:seon.error/kind])))` | R8 |
| 1636 | `{:seon.error/kind :seon.boot/refused` | R8 |
| 1640 | `{:seon.error/kind :sweep-in-progress` | R8 |
| 1652 | `(is (= :sweep-in-progress (:seon.error/kind refusal)))` | R8 |
| 1754 | `:data {:seon.error/kind :seon.instrument/contract-violated` | R8 |
| 1758 | `:data {:seon.error/kind :seon.instrument/contract-violated` | R8 |
| 1800 | `(:seon.error/kind data)))` | R8 |
| 1802 | `(is (= {:seon.error/kind :seon.instrument/contract-violated` | R8 |
| 1843 | `(is (= :seon.fresh-operator/prepl-exception (:seon.error/kind data)))` | R8 |
| 2062 | `(:seon.error/kind refusal))` | R8 |
| 2207 | `(:seon.error/kind (:seon.dev.fresh-operator-test/data outcome)))` | R8 |

### test/seon/dev/hook_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 59 | `(is (= :seon.fresh-operator/publication-failed (:seon.error/kind failure)))` | R8 |
| 126 | `(catch Exception e (:seon.error/kind (ex-data e))))` | R8 |

### test/seon/edit/jvm_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 85 | `(:seon.error/kind ((handler) request (policy root)))))` | R8 |
| 138 | `(:seon.error/kind %))` | R8 |

### test/seon/edit_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 86 | `(is (= :my.edit/ambiguous-match (:seon.error/kind ambiguous)))` | R8 |
| 104 | `(is (= :my.edit/parse-refused (:seon.error/kind result)))` | R8 |
| 126 | `(is (= :my.edit/no-match (:seon.error/kind result)))` | R8 |
| 144 | `(is (= :my.edit/ambiguous-match (:seon.error/kind ambiguous)))` | R8 |
| 149 | `(is (= :my.edit/no-match (:seon.error/kind absent)))))` | R8 |
| 171 | `(is (= :my.edit/no-match (:seon.error/kind refused)))` | R8 |
| 337 | `(is (nil? (:seon.error/kind edited)) (pr-str edited))` | R8 |
| 361 | `(is (nil? (:seon.error/kind comment-edit)) (pr-str comment-edit))` | R8 |
| 376 | `(is (= :seon.program/no-declaration-at (:seon.error/kind refusal)))` | R8 |
| 414 | `(is (nil? (:seon.error/kind result)) (pr-str result))` | R8 |
| 456 | `(is (= :my.fs/path-refused (:seon.error/kind refused))` | R8 |

### test/seon/effect_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 588 | `(:seon.error/kind result))` | R8 |
| 670 | `(:seon.error/kind second-result)))` | R8 |
| 681 | `(is (= :seon.effect/invalid-request (:seon.error/kind result)))` | R8 |
| 704 | `(is (= :seon.effect/request-too-large (:seon.error/kind result))` | R8 |
| 739 | `(is (= :seon.effect/interrupted (:seon.error/kind result)))` | R8 |
| 873 | `(is (nil? (:seon.error/kind written)) (pr-str written))` | R8 |

### test/seon/env_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 30 | `(is (= :seon.env/incomplete-environment (:seon.error/kind refusal)))` | R8 |
| 67 | `(:seon.error/kind` | R8 |
| 336 | `(is (= :seon.env/absent-environment (:seon.error/kind refusal)))` | R8 |
| 348 | `(is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))` | R8 |
| 366 | `(is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))` | R8 |
| 379 | `(is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))` | R8 |

### test/seon/error_class_schema_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 30 | `(true? (:seon.error/class (class-properties form))))` | R3 |
| 87 | `(comp (filter #(true? (:seon.error/class %)))` | R3 |

### test/seon/error_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 133 | `(commit-request {:seon.error/kind :seon.error/unclassified` | R8 |
| 145 | `(let [request (commit-request {:seon.error/kind :seon.flow/fault-channel-overflow` | R8 |
| 155 | `:where [?e :seon.error/kind :seon.flow/fault-channel-overflow]` | R8 |
| 206 | `{:seon.error/kind :seon.error-test/invalid-call` | R8 |
| 218 | `{:seon.error/kind :seon.error-test/unavailable` | R8 |
| 272 | `:seon.error/kind :my.fs/stale-digest` | R8 |
| 275 | `:seon.error/kind :my.fs/invalid-utf8-window` | R8 |
| 278 | `:seon.error/kind :seon.cluster.reply/refused-tag` | R8 |
| 281 | `:seon.error/kind :seon.db/unknown-failure` | R8 |
| 284 | `:seon.error/kind :seon.instrument/contract-violated` | R8 |
| 287 | `:seon.error/kind :seon.render.walk/elided` | R8 |
| 290 | `:seon.error/kind :seon.ai/stream-truncated` | R8 |
| 293 | `:seon.error/kind :seon.cluster.reply/unreadable` | R8 |
| 296 | `:seon.error/kind :seon.turn.loop/phase-failed` | R8 |
| 299 | `:seon.error/kind :seon.turn.loop/lint-rejected` | R8 |
| 302 | `:seon.error/kind :seon.operator/collection-incomplete` | R8 |
| 309 | `(:seon.error/kind value)))))))` | R8 |
| 368 | `{:seon.error/kind kind` | R8 |
| 377 | `(ex-info message {:seon.error/kind kind}))` | R8 |
| 393 | `(cond-> {:seon.error/kind kind :seon.error/message message}` | R8 |
| 446 | `(keyword? (:seon.error/kind fact))` | R8 |
| 487 | `(let [small (error/normalize (request {:seon.error/kind :seon.ai/timeout` | R8 |
| 490 | `(request {:seon.error/kind :seon.ai/timeout` | R8 |
| 499 | `(let [source {:seon.error/kind :seon.error-test/classified` | R8 |
| 514 | `(is (= (select-keys source [:seon.error/kind` | R8 |
| 519 | `(select-keys retained [:seon.error/kind` | R8 |
| 560 | `(is (= :seon.instrument/contract-violated (:seon.error/kind fact))` | R8 |
| 587 | `{:seon.error/kind :seon.instrument/contract-violated` | R8 |
| 620 | `[:seon.error/id :seon.error/kind :seon.error/message` | R8 |
| 629 | `(assoc (request {:seon.error/kind :seon.error-test/near-limit` | R8 |
| 635 | `(assoc (request {:seon.error/kind :seon.error-test/short` | R8 |
| 654 | `fact (error/normalize (request {:seon.error/kind :seon.db/rejected` | R8 |
| 687 | `(let [fact (error/normalize (request {:seon.error/kind :seon.ai/no-credential` | R8 |
| 690 | `(is (= :seon.ai/no-credential (:seon.error/kind fact)))` | R8 |
| 710 | `(is (= :seon.turn/not-the-holder (:seon.error/kind fact))` | R8 |
| 716 | `(is (= :seon.error/unclassified (:seon.error/kind fact))` | R8 |
| 721 | `(let [with (error/normalize (request {:seon.error/kind :seon.db/rejected` | R8 |
| 725 | `without (error/normalize (request {:seon.error/kind :seon.db/rejected` | R8 |
| 741 | `(request {:seon.error/kind :seon.db/rejected` | R8 |
| 750 | `(error/normalize (request {:seon.error/kind kind` | R8 |
| 785 | `(= (:seon.error/kind fact) (:seon.error/kind notice))` | R8 |
| 800 | `(let [violation {:seon.error/kind :seon.instrument/contract-violated` | R8 |
| 926 | `(is (= (:seon.error/kind fact) (:seon.error/kind value)))` | R8 |
| 1006 | `(is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))` | R8 |
| 1029 | `{:seon.error/kind :seon.turn/not-the-holder` | R8 |
| 1266 | `(is (= :seon.db/invalid-write (:seon.error/kind result)) (pr-str result))` | R8 |

### test/seon/flow_configuration_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 47 | `(is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))` | R8 |

### test/seon/flow_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 59 | `{:seon.error/kind ::earliest-resume-fault})))` | R8 |
| 87 | `(:seon.error/kind (ex-data (::flow/ex fault))))` | R8 |
| 251 | `[?error :seon.error/kind :seon.flow/fault-channel-overflow]` | R8 |
| 262 | `[?error :seon.error/kind :seon.flow/fault-channel-overflow]` | R8 |
| 427 | `(get-in refused [::sut/value :seon.error/kind]))))` | R8 |
| 449 | `(is (= :seon.await/backstop-fired (:seon.error/kind result)))` | R8 |
| 577 | `(get-in result [::sut/value :seon.error/kind])))` | R8 |
| 794 | `(is (= ::sut/unsupported-command (:seon.error/kind result)))` | R8 |
| 868 | `{:seon.error/kind :seon.instrument/contract-violated` | R8 |

### test/seon/fn_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 67 | `(:seon.error/kind result)))` | R8 |
| 612 | `(is (nil? (:seon.error/kind settlement))` | R8 |
| 614 | `[:seon.error/kind :seon.error/message` | R8 |
| 835 | `(is (= :seon.fn/index-refused (:seon.error/kind (ex-data failure))))` | R8 |
| 901 | `(:seon.error/kind (ex-data failure)))))))` | R8 |
| 1146 | `{:seon.error/kind :seon.db/invalid-transaction}` | R8 |
| 1148 | `(is (= :seon.db/invalid-transaction (:seon.error/kind failure)))` | R8 |
| 1161 | `(is (= :seon.db/invalid-write (:seon.error/kind (ex-data failure))))` | R8 |
| 1176 | `(:seon.error/kind` | R8 |
| 1181 | `(:seon.error/kind` | R8 |
| 1196 | `"  {:seon.error/kind :sample.keys/refused\n"` | R6 |
| 1203 | `"  (is (= :sample.keys/refused (:seon.error/kind (refuse \"why\")))))\n")]` | R6 |
| 1212 | `:seon.error/kind :seon.error/message}` | R8 |
| 1225 | `(is (= #{:sample.keys/refused :seon.error/kind}` | R8 |
| 1255 | `:seon.error/kind)))` | R8 |
| 1557 | `:seon.error/kind :seon.db/invalid-read` | R8 |
| 1567 | `{:seon.fn/sym (quote sample.shape/many)}}) :seon.error/kind)` | R8 |
| 1690 | `(is (= :seon.fn/index-refused (:seon.error/kind (ex-data failure))))` | R8 |
| 1950 | `(is (:db-after report) (pr-str (select-keys report [:seon.error/kind :seon.error/message])))` | R8 |
| 1974 | `:seon.error/kind :seon.db/invalid-read` | R8 |
| 2155 | `(pr-str (select-keys report [:seon.error/kind` | R8 |
| 2175 | `(is (:db-after report) (pr-str (select-keys report [:seon.error/kind :seon.error/message])))` | R8 |
| 2327 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal)) (pr-str refusal))` | R8 |
| 2371 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal))` | R8 |
| 2581 | `(is (= :seon.fn/index-refused (:seon.error/kind data))` | R8 |
| 2849 | `(pr-str (select-keys report [:seon.error/kind` | R8 |
| 2858 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal))` | R8 |
| 2973 | `(is (= :seon.fn/index-refused (:seon.error/kind refusal)))` | R8 |

### test/seon/fs/jvm_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 125 | `(is (nil? (:seon.error/kind tail))` | R8 |
| 147 | `(is (= :my.fs/read-limit (:seon.error/kind refusal))` | R8 |
| 177 | `(:seon.error/kind text-result)))` | R8 |
| 198 | `(is (= :my.fs/changed-during-read (:seon.error/kind result)))))))` | R8 |
| 225 | `(:seon.error/kind ((handler 'read) request config)))))` | R8 |
| 228 | `(:seon.error/kind` | R8 |
| 316 | `{:seon.error/kind :my.fs/write-failed` | R8 |
| 327 | `(is (= :my.fs/write-failed (:seon.error/kind result)))` | R8 |
| 365 | `(:seon.error/kind %))` | R8 |

### test/seon/help_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 56 | `(is (nil? (:seon.error/kind opening)) (pr-str opening))` | R8 |
| 124 | `(is (not (:seon.error/kind (turn/system-turn request))))` | R8 |

### test/seon/help_trial_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 66 | `(is (nil? (:seon.error/kind report)) (pr-str report)))` | R8 |

### test/seon/html_views_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 139 | `(let [fault {:seon.error/kind :example/failed :seon.error/message "Connection lost"}]` | R8 |

### test/seon/instrument_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 85 | `(is (= :seon.instrument/invalid-mode (:seon.error/kind result)))` | R8 |
| 109 | `(is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))` | R8 |
| 267 | `(:seon.error/kind (ex-data failure)))` | R8 |
| 283 | `(is (= :seon.instrument/contract-violated (:seon.error/kind data))` | R8 |
| 329 | `(:seon.error/kind (ex-data failure))))` | R8 |
| 334 | `(:seon.error/kind` | R8 |
| 442 | `(is (= :seon.instrument/missing-recorder (:seon.error/kind missing)))` | R8 |
| 443 | `(is (= :seon.instrument/missing-recorder (:seon.error/kind missing-base))` | R8 |
| 472 | `(is (= :seon.instrument/contract-violated (:seon.error/kind failure)))` | R8 |
| 516 | `;; throwable's own \`:seon.error/kind\` (\`src/seon/sci/kernel.clj:486\`), and` | R6 |
| 543 | `(:seon.error/kind (ex-data thrown)))` | R8 |
| 545 | `(is (= :seon.sci.eval/time-limit (:seon.error/kind outcome))` | R8 |
| 638 | `(is (= ::instrument/contract-violated (:seon.error/kind violation)))` | R8 |
| 644 | `inner {:seon.error/kind :seon.db/missing-connection-binding` | R8 |
| 660 | `(:seon.error/kind` | R8 |
| 923 | `(is (= :seon.instrument/contract-violated (:seon.error/kind (ex-data failure))))` | R8 |
| 1021 | `(:seon.error/kind (ex-data failure))))` | R8 |
| 1130 | `(:seon.error/kind result)))` | R8 |
| 1143 | `(is (= :seon.instrument/missing-recorder (:seon.error/kind applied)))` | R8 |
| 1175 | `original-error {:seon.error/kind ::failed-read` | R8 |
| 1192 | `(is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))` | R8 |
| 1197 | `(is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))` | R8 |
| 1300 | `(:seon.error/kind (ex-data failure))))))))` | R8 |

### test/seon/issue/detect_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 72 | `(clojure.test/is (nil? (:seon.error/kind report)) (pr-str report))` | R8 |
| 78 | `(clojure.test/is (nil? (:seon.error/kind subjects)) (pr-str subjects))` | R8 |
| 151 | `_ (clojure.test/is (nil? (:seon.error/kind report)) (pr-str report))` | R8 |
| 185 | `_ (clojure.test/is (nil? (:seon.error/kind report)) (pr-str report))` | R8 |

### test/seon/issue_deletion_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 89 | `(is (= :seon.db/retention-refused (:seon.error/kind result)) (pr-str result))` | R8 |

### test/seon/issue_generate_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 47 | `(clojure.test/is (nil? (:seon.error/kind report)) (pr-str report))` | R8 |
| 73 | `(clojure.test/is (nil? (:seon.error/kind first-run)) (pr-str first-run))` | R8 |
| 136 | `(clojure.test/is (= :seon.issue/subject-without-identity (:seon.error/kind result))` | R8 |
| 145 | `(clojure.test/is (= :seon.issue/detector-unknown (:seon.error/kind unknown))` | R8 |

### test/seon/issue_settlement_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 38 | `row (when-not (:seon.error/kind analysis) (second (first analysis)))]` | R8 |
| 43 | `(when (:seon.error/kind report) (throw (ex-info "Issue test admission transaction failed." report)))` | R8 |
| 83 | `(is (nil? (:seon.error/kind report)) (pr-str report))))` | R8 |
| 201 | `(is (nil? (:seon.error/kind resumed)) (pr-str resumed))` | R8 |
| 310 | `(is (= :seon.db/retention-refused (:seon.error/kind result)) (pr-str result))` | R8 |
| 317 | `(:seon.error/kind (write worker [[:db/retract issue :seon.issue/tests a]]))))` | R8 |
| 321 | `(:seon.error/kind (write creator [[:db/retract issue :seon.issue/tests b]]))))` | R8 |
| 324 | `(:seon.error/kind` | R8 |

### test/seon/issue_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 53 | `(clojure.test/is (nil? (:seon.error/kind report)) (pr-str report))` | R8 |
| 96 | `(clojure.test/is (nil? (:seon.error/kind indexed)) (pr-str indexed))` | R8 |
| 97 | `(clojure.test/is (nil? (:seon.error/kind first-adoption)) (pr-str first-adoption))` | R8 |
| 107 | `(clojure.test/is (nil? (:seon.error/kind again)) (pr-str again))` | R8 |
| 117 | `(clojure.test/is (nil? (:seon.error/kind changed)) (pr-str changed))` | R8 |
| 118 | `(clojure.test/is (nil? (:seon.error/kind adopted)) (pr-str adopted))` | R8 |
| 174 | `(clojure.test/is (nil? (:seon.error/kind again)) (pr-str again))` | R8 |
| 192 | `(clojure.test/is (nil? (:seon.error/kind report)) (pr-str report))` | R8 |
| 242 | `(clojure.test/is (nil? (:seon.error/kind report)) (pr-str report))` | R8 |
| 246 | `(clojure.test/is (nil? (:seon.error/kind again)) (pr-str again))` | R8 |
| 284 | `(clojure.test/is (nil? (:seon.error/kind started)) (pr-str started))` | R8 |
| 347 | `(clojure.test/is (nil? (:seon.error/kind generated)) (pr-str generated))` | R8 |
| 357 | `(clojure.test/is (nil? (:seon.error/kind started)) (pr-str started))` | R8 |
| 396 | `(clojure.test/is (= :seon.issue/not-a-test (:seon.error/kind (seon.issue/add! (assoc request :seon.issue/title "Invalid success ref" :seon.issue/tests #{[:seon.agent/id "issue-author"]})))))` | R8 |
| 398 | `(clojure.test/is (= :seon.issue/no-tests (:seon.error/kind refused)))` | R8 |
| 401 | `(clojure.test/is (nil? (:seon.error/kind (seon.issue/tests! {:seon.db/connection c :seon.agent/id "issue-author"` | R8 |
| 409 | `(clojure.test/is (nil? (:seon.error/kind started)) (pr-str started))` | R8 |
| 415 | `(clojure.test/is (= :seon.issue/already-started (:seon.error/kind (seon.issue/start! start))))` | R8 |
| 418 | `(:seon.error/kind (seon.issue/tests! {:seon.db/connection c :seon.agent/id "issue-author"` | R8 |

### test/seon/loop_proof_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 208 | `(is (nil? (:seon.error/kind` | R8 |
| 220 | `(is (= :seon.agent/turn-completion-backstop (:seon.error/kind data)))` | R8 |
| 307 | `(is (nil? (:seon.error/kind` | R8 |
| 309 | `(is (nil? (:seon.error/kind` | R8 |
| 313 | `_ (is (= :seon.ai/no-credential (:seon.error/kind failure)))` | R8 |
| 325 | `[?e :seon.error/kind ?kind]] @connection))))` | R8 |
| 355 | `_ (is (nil? (:seon.error/kind configured)) (pr-str configured))` | R8 |
| 645 | `_ (is (nil? (:seon.error/kind settled)) (pr-str settled))` | R8 |
| 661 | `(is (nil? (:seon.error/kind refresh)) (pr-str refresh))` | R8 |
| 698 | `_ (is (nil? (:seon.error/kind written)) (pr-str written))` | R8 |
| 735 | `(let [_ (is (nil? (:seon.error/kind` | R8 |
| 806 | `(is (nil? (:seon.error/kind result)) (pr-str result))))))` | R8 |

### test/seon/maintenance_schema_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 237 | `(is (nil? (:seon.error/kind first-result)))` | R8 |
| 298 | `{:seon.error/kind :seon.operator/collection-incomplete` | R8 |
| 376 | `{:seon.error/kind :seon.operator/collection-incomplete` | R8 |
| 385 | `(get-in stored [:seon.maintenance.result/cluster-cleanup-collection :seon.error/kind])))` | R8 |

### test/seon/maintenance_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 49 | `[{:seon.error/kind :seon.operator/unreadable-claim` | R8 |
| 206 | `{:seon.error/kind :seon.error/not-yet` | R8 |
| 240 | `[?collection :seon.error/kind ?kind]` | R8 |
| 446 | `(let [refusal {:seon.error/kind :seon.operator/collection-incomplete` | R8 |
| 466 | `[?collection :seon.error/kind ?kind]` | R8 |
| 533 | `(:seon.error/kind answer)))` | R8 |

### test/seon/mcp_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 77 | `(is (= :seon.dev.mcp/jvm-exception (:seon.error/kind result)))` | R8 |
| 86 | `(:seon.error/kind (value/prepare request))))` | R8 |
| 99 | `:seon.error/kind ::probe :seon.error/message "probe"})]` | R8 |
| 110 | `(:seon.error/kind %)))` | R8 |
| 126 | `(is (= :seon.dev.mcp/projection-failed (:seon.error/kind result))))))` | R8 |

### test/seon/no_provider_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 58 | `[_ :seon.error/kind ?kind]] @connection)))` | R8 |
| 113 | `(is (not (:seon.error/kind result)) (pr-str result)))` | R8 |
| 123 | `(pr-str (db/q '[:find [(pull ?error [:seon.error/kind :seon.error/message]) ...]` | R8 |

### test/seon/operator_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 82 | `(:seon.error/kind (ex-data failure))))` | R8 |
| 117 | `(:seon.error/kind data)))` | R8 |
| 156 | `(:seon.error/kind data)))` | R8 |
| 172 | `(:seon.error/kind (ex-data failure)))` | R8 |
| 201 | `(is (= :seon.instrument/contract-violated (:seon.error/kind refusal))` | R8 |
| 217 | `(is (= :seon.boot/refused (:seon.error/kind result)))` | R8 |
| 249 | `(:seon.error/kind result)))` | R8 |
| 305 | `(:seon.error/kind` | R8 |
| 378 | `(:seon.error/kind` | R8 |
| 489 | `(is (nil? (:seon.error/kind result))` | R8 |
| 528 | `(:seon.error/kind (ex-data failure))))` | R8 |
| 564 | `(:seon.error/kind result)))` | R8 |
| 589 | `(or (ex-data error) {:seon.error/kind ::threw}))))` | R8 |
| 630 | `(:seon.error/kind outcome))` | R8 |
| 874 | `{:seon.error/kind :seon.operator/unreadable-claim` | R8 |
| 889 | `(:seon.error/kind result)))` | R8 |
| 900 | `failure-data {:seon.error/kind :seon.boot/refused` | R8 |
| 911 | `(is (= :seon.boot/refused (:seon.error/kind result)))` | R8 |
| 1293 | `(is (nil? (:seon.error/kind result))` | R8 |
| 1338 | `(:seon.error/kind result)))` | R8 |
| 1353 | `(is (nil? (:seon.error/kind result)))` | R8 |
| 1390 | `(:seon.error/kind result)))` | R8 |
| 1412 | `(is (nil? (:seon.error/kind result)))` | R8 |
| 1438 | `(:seon.error/kind (ex-data failure))))` | R8 |
| 1444 | `(:seon.error/kind (ex-data failure))))` | R8 |

### test/seon/owned_value_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 34 | `(is (= :seon.db/invalid-write (:seon.error/kind result)) (pr-str result))` | R8 |

### test/seon/plan_completion_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 34 | `(is (= :my.plan/done-query-unsatisfied (:seon.error/kind failure)) (pr-str failure))` | R8 |
| 70 | `(is (= :my.plan/done-query-failed (:seon.error/kind failure)) (pr-str failure))` | R8 |

### test/seon/problems_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 76 | `{:seon.error/source (ex-info "boom" {:seon.error/kind kind})` | R8 |
| 103 | `{:seon.error/source {:seon.error/kind :seon.ai/provider-error` | R8 |
| 120 | `:seon.error/kind :seon.sci.eval/evaluation-failed` | R8 |
| 158 | `:seon.error/kind` | R8 |
| 237 | `(:seon.error/kind` | R8 |
| 253 | `(:seon.error/kind` | R8 |
| 315 | `(is (= :seon.db/rejected (:seon.error/kind entry)))` | R8 |
| 363 | `(is (= :seon.db/invalid-write (:seon.error/kind result)))` | R8 |
| 385 | `(is (= :seon.sci.eval/evaluation-failed (:seon.error/kind entry)))` | R8 |
| 436 | `(:seon.error/kind %)) signatures))]` | R8 |
| 486 | `(is (= :seon.schema/missing-projection (:seon.error/kind result)))` | R8 |

### test/seon/program_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 536 | `(is (= :seon.db/invalid-write (:seon.error/kind result)) (pr-str result))` | R8 |
| 626 | `(is (= :seon.turn/refused (:seon.error/kind data)))` | R8 |
| 735 | `(is (= :seon.program/declaration-refused (:seon.error/kind data)))` | R8 |
| 745 | `(:seon.error/kind data)))` | R8 |
| 792 | `:seon.schema/form "[:map {:seon.error/class true}]"` | R3 |
| 794 | `:seon.error/class true` | R3 |
| 803 | `"(seon.schema/register! ::error [:map {:seon.error/class true :seon.render/ai sample/render-ai} [:seon.error/message :seon.error/message]])")` | R3 |
| 805 | `(is (= true (:seon.error/class row)))` | R3 |

### test/seon/public_contract_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 49 | `:seon.error/kind` | R8 |
| 55 | `:seon.error/kind ::unidentified-public-function` | R8 |
| 85 | `(:seon.error/kind (ex-data failure))))` | R8 |

### test/seon/read_evidence_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 12 | `(is (not (:seon.error/kind` | R8 |
| 40 | `(is (nil? (:seon.error/kind evaluation)) (pr-str evaluation))` | R8 |
| 49 | `(is (not (:seon.error/kind` | R8 |
| 76 | `(is (not (:seon.error/kind` | R8 |
| 114 | `(is (not (:seon.error/kind` | R8 |
| 121 | `(is (not (:seon.error/kind` | R8 |

### test/seon/reconcile_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 122 | `(let [refusal {:seon.error/kind :seon.db/rejected` | R8 |
| 135 | `(:seon.error/kind (db/history @connection)))` | R8 |

### test/seon/refusal_grammar_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 56 | `{:seon.error/kind :seon.db/invalid-read` | R8 |
| 73 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal))` | R8 |

### test/seon/registry_isolation_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 48 | `(is (not (:seon.error/kind result)) (pr-str (:seon.error/kind result)))` | R8 |

### test/seon/render/data_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 59 | `(is (= :seon.render.data/no-such-path (:seon.error/kind refused))))` | R8 |
| 97 | `(:seon.error/kind (data/entity-observation` | R8 |
| 100 | `(:seon.error/kind (data/entity-observation` | R8 |
| 107 | `(:seon.error/kind (data/entity-observation` | R8 |
| 128 | `(is (:seon.error/kind` | R8 |
| 130 | `(is (:seon.error/kind` | R8 |

### test/seon/render/faults_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 27 | `{:seon.error/source {:seon.error/kind :seon.instrument/contract-violated` | R8 |
| 76 | `(is (not (:seon.error/kind result)) (pr-str result))` | R8 |

### test/seon/render/hiccup_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 110 | `(doseq [refused [{:seon.error/kind :a/b} [1 2 3] #{:a}]]` | R8 |

### test/seon/render/history_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 188 | `{:seon.error/kind :seon.render/failure` | R8 |

### test/seon/render/lint_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 58 | `(is (= :seon.render.lint/absent-element (:seon.error/kind refusal)))` | R8 |

### test/seon/render/ns_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 131 | `(is (= :seon.db/invalid-read (:seon.error/kind ai)))` | R8 |
| 352 | `[:map {:seon.error/class true` | R3 |

### test/seon/render/page_review_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 78 | `(is (:seon.error/kind (transcript/render-runtime-ai {:seon.db/db @connection})))` | R8 |
| 109 | `(is (:seon.error/kind (evaluation/of-agent @connection "absent"))))))` | R8 |

### test/seon/render/retained_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 23 | `(is (:db-after report) (pr-str (select-keys report [:seon.error/kind :seon.error/message])))))` | R8 |
| 29 | `(is (:db-after report) (pr-str (select-keys report [:seon.error/kind :seon.error/message]))))` | R8 |
| 88 | `(is (:db-after report) (pr-str (select-keys report [:seon.error/kind :seon.error/message]))))` | R8 |
| 104 | `(is (= :seon.render/invalid-ai-output (:seon.error/kind (render/render-call (request))))` | R8 |

### test/seon/render/root_pull_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 147 | `(is (or (vector? result) (:seon.error/kind result))` | R8 |
| 486 | `(is (not (:seon.error/kind tx)) (pr-str tx))` | R8 |

### test/seon/render/runtime_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 61 | `(:seon.error/kind (transcript/render-runtime-html` | R8 |

### test/seon/render/transcript_run_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 97 | `(:seon.error/kind missing)))` | R8 |

### test/seon/render/transcript_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 74 | `(:seon.error/kind refusal)))` | R8 |
| 388 | `:seon.error/kind :seon.sci.eval/refused` | R8 |
| 933 | `(pr-str {:seon.error/kind :generated/refusal})` | R8 |
| 935 | `:seon.error/kind :generated/refusal` | R8 |
| 1311 | `(is (nil? (:seon.error/kind committed))` | R8 |
| 1312 | `(pr-str (select-keys committed [:seon.error/kind` | R8 |

### test/seon/render/value_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 82 | `(is (nil? (:seon.error/kind report)) (pr-str report))` | R8 |
| 83 | `(is (nil? (:seon.error/kind installed)) (pr-str installed))` | R8 |
| 84 | `(is (nil? (:seon.error/kind completed)) (pr-str completed))` | R8 |
| 428 | `(mapv :seon.error/kind results)))` | R8 |
| 563 | `(:seon.error/kind (:seon.render.value/window window))))` | R8 |

### test/seon/render/walk_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 41 | `(is (not (:seon.error/kind result)) (pr-str result))))` | R8 |
| 105 | `(:seon.error/kind (:seon.error/value %)))` | R8 |
| 127 | `(filter #(= ::walk/elided (:seon.error/kind %)))` | R8 |
| 188 | `:seon.error/kind]))` | R8 |

### test/seon/render/web_debug_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 258 | `:seon.error/kind :seon.sci.reader/fabricated-response}` | R8 |
| 276 | `{:seon.error/source {:seon.error/kind :seon.debug/panel-fixture` | R8 |
| 397 | `(is (nil? (:seon.error/kind (render/acquire-context! request)))` | R8 |
| 405 | `(is (nil? (:seon.error/kind changed)))` | R8 |
| 453 | `(is (nil? (:seon.error/kind html)) (pr-str html))` | R8 |
| 587 | `{:seon.error/source {:seon.error/kind ::fixture` | R8 |
| 663 | `(let [refusal {:seon.error/kind :seon.config/missing-effective` | R8 |
| 956 | `(is (nil? (:seon.error/kind rendered)) (pr-str rendered))` | R8 |
| 1026 | `(is (nil? (:seon.error/kind composed)) (pr-str composed))` | R8 |
| 1054 | `(is (nil? (:seon.error/kind added)) (pr-str added))` | R8 |

### test/seon/render/web_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 104 | `(is (= :seon.await/backstop-fired (:seon.error/kind result)))` | R8 |
| 1982 | `[[{:seon.error/kind :seon.db/rejected` | R8 |
| 1985 | `[{:seon.error/kind :seon.db/unknown-failure` | R8 |
| 2000 | `{:seon.error/kind :seon.db/rejected` | R8 |
| 2004 | `(is (= :seon.db/rejected (:seon.error/kind result)))` | R8 |
| 2203 | `{:seon.error/kind :seon.instrument/contract-violated` | R8 |
| 2229 | `(ex-info "unrelated typed failure" {:seon.error/kind ::unrelated})]]` | R8 |

### test/seon/render_coverage_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 85 | `(is (nil? (:seon.error/kind report)) (pr-str report))` | R8 |
| 144 | `(is (= ::render/missing-projection (:seon.error/kind result)))` | R8 |
| 207 | `(is (nil? (:seon.error/kind settled)) (pr-str settled))` | R8 |
| 380 | `(is (not (:seon.error/kind face))` | R8 |
| 503 | `(is (= :seon.render/unknown (:seon.error/kind refused)))` | R8 |

### test/seon/render_simplification_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 99 | `(:seon.error/kind (render-ai request))))))))))` | R8 |
| 320 | `(is (= ::config/missing-effective (:seon.error/kind result)))` | R8 |
| 588 | `(:seon.error/kind without-owner-function))))))))` | R8 |
| 612 | `(is (= :seon.render/ambiguous (:seon.error/kind result)))` | R8 |
| 614 | `(:seon.error/kind` | R8 |
| 625 | `[:seon.error/value :seon.error/kind])))` | R8 |
| 743 | `(get-in (first capped) [:seon.error/value :seon.error/kind])))` | R8 |
| 961 | `(let [failure {:seon.error/kind :render.test/broken` | R8 |

### test/seon/render_source_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 187 | `(is (:seon.error/kind` | R8 |
| 189 | `(is (:seon.error/kind` | R8 |
| 199 | `(:seon.error/kind failure)))` | R8 |
| 299 | `(is (= :seon.render.web/owner-not-ensured (:seon.error/kind refusal)))` | R8 |

### test/seon/repl_grammar_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 85 | `(is (not (:seon.error/kind opened)) (pr-str opened))` | R8 |

### test/seon/repl_parity_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 543 | `(:seon.error/kind (:value result)))))` | R8 |
| 559 | `(mapv #(get-in % [:value :seon.error/kind]) results))))` | R8 |
| 564 | `(:seon.error/kind (:value result)))))` | R8 |
| 599 | `[(:seon.error/kind value)` | R8 |
| 866 | `;; Asking the vector for \`:seon.error/kind\` got nil and compared it to the` | R6 |
| 876 | `(:seon.error/kind (first errors)))))` | R8 |

### test/seon/repl_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 234 | `(is (nil? (repl/render-ai {:seon.error/kind :seon.render/refused` | R8 |
| 237 | `(is (nil? (repl/render-html {:seon.error/kind :seon.render/refused` | R8 |

### test/seon/rereads_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 82 | `(is (nil? (:seon.error/kind preview)) (pr-str preview))` | R8 |
| 87 | `(is (nil? (:seon.error/kind result)) (pr-str result))` | R8 |
| 173 | `(is (nil? (:seon.error/kind result)) (pr-str result))` | R8 |

### test/seon/reset_edges_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 34 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal)) (pr-str refusal))` | R8 |
| 56 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal)) (pr-str refusal))` | R8 |
| 84 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal)) (pr-str refusal))` | R8 |
| 153 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal)) (pr-str refusal))` | R8 |
| 177 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal)) (pr-str result))` | R8 |
| 243 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal)))` | R8 |
| 247 | `(is (nil? (:seon.error/kind result)) (pr-str result)))` | R8 |
| 367 | `(is (= :seon.db/invalid-write (:seon.error/kind refusal)) (pr-str refusal))` | R8 |

### test/seon/returned_error_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 37 | `(is (= :seon.db/invalid-write (:seon.error/kind diagnostic)) (pr-str result))` | R8 |

### test/seon/run4_install_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 71 | `(is (= :seon.test.accretion/install-refused (:seon.error/kind result)) (pr-str result))` | R8 |

### test/seon/run4_reader_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 26 | `(get-in delimiter [:seon.sci.admit/value :seon.error/kind])))` | R8 |
| 34 | `(get-in comments [:seon.sci.admit/value :seon.error/kind])))` | R8 |

### test/seon/run6_db_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 24 | `(is (= :seon.instrument/contract-violated (:seon.error/kind failure)))` | R8 |
| 34 | `(:seon.error/kind (support/refusal-data call))))))` | R8 |
| 68 | `(:seon.error/kind (support/refusal-data call))))))))))` | R8 |

### test/seon/run6_stall_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 14 | `(when (:seon.error/kind result)` | R8 |
| 39 | `{:seon.error/source {:seon.error/kind :seon.ai/unparseable-body` | R8 |

### test/seon/schedule_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 26 | `(is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))` | R8 |
| 52 | `(is (not (:seon.error/kind result)) (pr-str result))` | R8 |
| 89 | `{:seon.error/kind :seon.schedule-test/returned-error` | R8 |
| 96 | `{:seon.error/kind :seon.schedule-test/thrown-failure})))` | R8 |
| 141 | `(is (not (:seon.error/kind result)) (pr-str result))` | R8 |
| 183 | `(is (not (:seon.error/kind` | R8 |
| 385 | `:where [_ :seon.error/kind ?kind]]` | R8 |

### test/seon/schema/admission_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 96 | `(:seon.error/kind (db/history @connection)))` | R8 |

### test/seon/schema/datahike_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 125 | `(let [forms {:seon.error/class [:= true]` | R3 |
| 129 | `[:map {:seon.error/class true` | R3 |
| 135 | `projection :seon.error/class))` | R3 |
| 138 | `(is (contains? attributes :seon.error/class))` | R3 |
| 193 | `(= :user-input (:seon.error/kind data)))))` | R8 |

### test/seon/schema/declaration_population_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 105 | `(:seon.error/kind (ex-data failure))))` | R8 |

### test/seon/schema/edn_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 333 | `(= :user-input (:seon.error/kind refusal)))))` | R8 |

### test/seon/schema/program_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 88 | `:seon.error/kind :seon.eval.drive/absent` | R8 |

### test/seon/schema_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 307 | `(:seon.error/kind refused)))` | R8 |
| 653 | `(is (= :user-input (:seon.error/kind data)))` | R8 |
| 729 | `[:map {:seon.error/class true` | R3 |
| 733 | `forms {:seon.error/class [:= true]` | R3 |
| 738 | `(is (= true (:seon.error/class row)))` | R3 |
| 754 | `[:and {:seon.error/class true` | R3 |
| 815 | `(:seon.error/kind mismatch-data)))` | R8 |
| 1293 | `(let [poisoned {:seon.error/kind :seon.db/invalid-read` | R8 |
| 1301 | `(is (= :seon.schema/invalid-projection-source (:seon.error/kind outcome)))` | R8 |

### test/seon/schema_usage_guard_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 53 | `(if (:seon.error/kind result)` | R8 |
| 388 | `(get-in refusal [:error :seon.error/kind])))` | R8 |

### test/seon/sci/admit_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 382 | `(is (= :seon.sci.admit/projection-failed (:seon.error/kind data))` | R8 |
| 513 | `(is (some? (:seon.error/kind refusal)))` | R8 |

### test/seon/sci/documentation_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 35 | `(:seon.error/kind` | R8 |
| 48 | `(is (not (:seon.error/kind doc)))` | R8 |
| 95 | `(get-in missing [:seon.sci.admit/value :seon.error/kind])))` | R8 |
| 97 | `(get-in missing-ns [:seon.sci.admit/value :seon.error/kind])))` | R8 |
| 263 | `(is (= :seon.instrument/contract-violated (:seon.error/kind value)) (pr-str failed))` | R8 |
| 294 | `(is (not (:seon.error/kind doc)))` | R8 |
| 300 | `(is (= :seon.sci.eval/declaration-absent (:seon.error/kind value)) label)` | R8 |

### test/seon/sci/eval_instrumentation_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 67 | `[?error :seon.error/kind` | R8 |

### test/seon/sci/eval_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 104 | `(is (= :seon.sci.eval/missing-function-row (:seon.error/kind failure)))` | R8 |
| 352 | `(get-in evaluation [:seon.sci.admit/value :seon.error/kind]))))))))))` | R8 |
| 431 | `(is (nil? (:seon.error/kind report)) (pr-str report)))))))))` | R8 |
| 439 | `(:seon.error/kind (:seon.sci.admit/value evaluation))))))` | R8 |
| 604 | `(:seon.error/kind refusal))` | R8 |
| 737 | `(is (= :seon.config/missing-result-cap (:seon.error/kind caps))` | R8 |
| 751 | `(is (= :seon.instrument/missing-recorder (:seon.error/kind refusal)))` | R8 |
| 909 | `(is (= :seon.sci.eval/namespace-unloadable (:seon.error/kind data)))` | R8 |
| 1123 | `value {:seon.error/kind :seon.sci.eval/time-limit` | R8 |
| 1177 | `(is (= :seon.schema/missing-projection (:seon.error/kind refusal)))` | R8 |
| 1337 | `(is (= :seon.instrument/contract-violated (:seon.error/kind failure)))` | R8 |
| 1440 | `(:seon.error/kind (:seon.sci.admit/value evaluation))))` | R8 |
| 1467 | `(:seon.error/kind (:seon.sci.admit/value evaluation)))` | R8 |
| 1571 | `[?error :seon.error/kind :seon.sci.eval/acquisition-refused]]` | R8 |
| 1624 | `(:seon.error/kind failure))` | R8 |
| 1794 | `(get-in unbound [:seon.sci.admit/value :seon.error/kind])))` | R8 |
| 1809 | `(get-in rejected [:seon.sci.admit/value :seon.error/kind])))` | R8 |
| 1819 | `(get-in unbound-after [:seon.sci.admit/value :seon.error/kind]))` | R8 |
| 2202 | `(= :seon.render/unknown (:seon.error/kind deadline-result))` | R8 |
| 2214 | `(:seon.error/kind (:seon.sci.admit/value evaluation)))` | R8 |
| 2226 | `(:seon.error/kind (:seon.sci.admit/value forked)))))` | R8 |
| 2248 | `(:seon.error/kind evaluated-throw)))` | R8 |
| 2250 | `(:seon.error/kind invoked-throw))` | R8 |
| 2270 | `(is (= :seon.sci.eval/time-limit (:seon.error/kind evaluated-cut)))` | R8 |
| 2271 | `(is (= :seon.sci.kernel/time-limit (:seon.error/kind invoked-cut)))` | R8 |
| 2291 | `{:seon.error/kind :probe/inner` | R8 |
| 2299 | `(is (= :probe/inner (:seon.error/kind failure)))` | R8 |
| 2333 | `(is (= :seon.sci.eval/reader-event-count (:seon.error/kind evaluated))` | R8 |
| 2336 | `(:seon.error/kind invoked))` | R8 |
| 2400 | `;; \`:seon.error/kind\` whose \`:seon.error/message\` is not a string. Reading` | R6 |
| 2412 | `(str "{:seon.error/kind :probe/refused"` | R8 |

### test/seon/sci/kernel_arm_carriage_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 144 | `(is (= :seon.sci.kernel/already-armed (:seon.error/kind data)))` | R8 |

### test/seon/sci/reader_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 38 | `(contains? value :seon.error/kind))` | R8 |
| 188 | `(:seon.error/kind refused)))` | R8 |
| 203 | `(:seon.error/kind (events "#foo/bar 1")))))` | R8 |
| 210 | `(:seon.error/kind result)))` | R8 |
| 229 | `(:seon.error/kind (events "#=(+ 20 22)"))))` | R8 |
| 231 | `(:seon.error/kind (events "#foo/bar {:x 1}"))))` | R8 |
| 257 | `(:seon.error/kind (first-read-error (events "::str/word"))))))` | R8 |
| 277 | `(is (= :seon.sci.reader/unreadable (:seon.error/kind result)))` | R8 |
| 303 | `(:seon.error/kind failure))` | R8 |
| 317 | `(:seon.error/kind result)))` | R8 |
| 328 | `(:seon.error/kind result)))` | R8 |
| 343 | `(get-in result [0 :seon.sci.reader/error :seon.error/kind])))` | R8 |
| 358 | `(is (= :seon.sci.reader/refused-tag (:seon.error/kind result)))` | R8 |
| 367 | `:seon.error/kind]))))))))))` | R8 |

### test/seon/sci/shown_text_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 14 | `(when (:seon.error/kind result)` | R8 |

### test/seon/shell/jvm_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 66 | `(when (:seon.error/kind configured)` | R8 |
| 153 | `(is (= :seon.await/backstop-fired (:seon.error/kind result)))` | R8 |
| 273 | `(is (nil? (:seon.error/kind result)))))))))` | R8 |
| 286 | `(is (= :my.shell/cwd-refused (:seon.error/kind result)))` | R8 |
| 310 | `_ (is (not (:seon.error/kind configured)) (pr-str configured))` | R8 |
| 341 | `(is (= :my.shell/time-limit (:seon.error/kind result)))` | R8 |
| 396 | `(is (= :my.shell/time-limit (:seon.error/kind result)))` | R8 |

### test/seon/source_reconciliation_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 32 | `_ (is (not (:seon.error/kind setup)) (pr-str setup))` | R8 |
| 58 | `(is (not (:seon.error/kind report)) (pr-str report))` | R8 |
| 80 | `(is (not (:seon.error/kind result)))` | R8 |

### test/seon/supplied_documentation_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 48 | `(is (nil? (get-in example [:seon.sci.admit/value :seon.error/kind]))` | R8 |
| 58 | `(is (= :seon.instrument/contract-violated (:seon.error/kind value)))` | R8 |
| 70 | `(is (= :seon.instrument/contract-violated (:seon.error/kind value)))` | R8 |
| 90 | `(is (= :seon.instrument/missing-supplied-key (:seon.error/kind failure)))` | R8 |

### test/seon/test/check_request_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 17 | `(is (:seon.error/kind missing))` | R8 |
| 24 | `(is (nil? (:seon.error/kind result)) (pr-str result))` | R8 |

### test/seon/test/runner_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 41 | `(is (= expected (:seon.error/kind refusal)))` | R8 |
| 267 | `(:seon.error/kind (db/transact! connection [[:db.fn/call runner/claim-member` | R8 |
| 272 | `(:seon.error/kind` | R8 |
| 285 | `(is (= :seon.test/claim-replaced (:seon.error/kind (runner/commit-results! connection late))))` | R8 |
| 291 | `(:seon.error/kind` | R8 |
| 350 | `(is (= :seon.test/claim-conflict (:seon.error/kind (refused (request run other))))` | R8 |
| 352 | `(is (= :seon.test/claim-conflict (:seon.error/kind (refused (request other-run parent))))` | R8 |
| 355 | `(:seon.error/kind (refused (update (request run other) :seon.db.process/pid inc)))))` | R8 |
| 357 | `(:seon.error/kind (refused (assoc (request run other) :seon.test.member/claimed-at deadline)))))` | R8 |
| 359 | `(:seon.error/kind (refused (assoc (request run other) :seon.test.run/deadline` | R8 |
| 501 | `(:seon.error/kind (ex-data refusal)))))` | R8 |
| 1122 | `(:seon.error/kind (ex-data refusal))))))` | R8 |

### test/seon/test/selection_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 101 | `(:seon.error/kind (select-request (dissoc request :seon.test.run/cluster)))))` | R8 |
| 103 | `(:seon.error/kind (select-request (assoc request :seon.test.run/cluster [:seon.cluster/name "missing"])))))` | R8 |
| 105 | `(:seon.error/kind (select-request (assoc request :seon.test.run/change-basis-t` | R8 |
| 119 | `{:seon.db/invalid-read true :seon.error/kind :seon.db/invalid-read` | R8 |
| 127 | `:seon.error/diagnostic-evidence {}}) :seon.error/kind)]` | R8 |
| 159 | `(:seon.error/kind` | R8 |
| 187 | `(:seon.error/kind (select-request (assoc request :seon.db/db missing-member)))))` | R8 |
| 189 | `(:seon.error/kind (select-request (assoc request :seon.db/db missing-inputs)))))` | R8 |
| 191 | `(:seon.error/kind (select-request (assoc request :seon.db/db missing-analysis))))))))))` | R8 |
| 339 | `(is (= ::selection/invalid-basis (:seon.error/kind refusal)))` | R8 |

### test/seon/test_failure_facts_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 43 | `(when (:seon.error/kind tx)` | R8 |
| 297 | `(is (= :seon.test/unknown (:seon.error/kind (sut/changed-since-green (db/db connection) s))))` | R8 |
| 345 | `(is (= :seon.test/unknown (:seon.error/kind (sut/changed-since-green (db/db connection) s))))` | R8 |

### test/seon/test_preparation_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 22 | `(is (nil? (:seon.error/kind @base)))` | R8 |

### test/seon/test_provenance_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 95 | `(let [refused {:seon.error/kind :seon.db/invalid-query` | R8 |

### test/seon/test_reaching_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 57 | `(:seon.error/kind` | R8 |
| 223 | `row (when-not (:seon.error/kind analysis) (second (first analysis)))]` | R8 |
| 255 | `(is (= :seon.test.runner/fixture-base-unavailable (:seon.error/kind observation)))` | R8 |
| 382 | `(is (:seon.error/kind (runner/provenance (db/db connection))))` | R8 |
| 480 | `(is (= :seon.test/destructive-in-process (:seon.error/kind result)) (pr-str result))` | R8 |
| 571 | `(is (= :seon.test/unknown (:seon.error/kind derived)) (pr-str derived))` | R8 |
| 573 | `(is (= :seon.test/unknown (:seon.error/kind (sut/host after "seon.id-test/anything")))` | R8 |
| 575 | `(is (:seon.error/kind (#'sut/destructive-refusal` | R8 |
| 601 | `(is (= :seon.test/unknown (:seon.error/kind report)) (pr-str report))` | R8 |
| 610 | `(is (= :seon.test/unknown (:seon.error/kind result)) (pr-str result))` | R8 |
| 803 | `(is (nil? (:seon.error/kind result))` | R8 |

### test/seon/test_runner_failure_fixture.clj

| Line | Source | Replacement |
|---:|---|---|
| 22 | `{:seon.error/kind error-class` | R8 |

### test/seon/test_runner_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 339 | `:seon.error/kind])))` | R8 |
| 353 | `(:seon.error/kind (ex-data failure)))` | R8 |
| 404 | `(:seon.error/kind` | R8 |
| 576 | `{:seon.error/kind` | R8 |
| 593 | `(:seon.error/kind failure)))` | R8 |
| 616 | `(is (= ::runner/worker-exited (:seon.error/kind result)))` | R8 |
| 853 | `{:seon.error/kind` | R8 |
| 906 | `(is (= :seon.test.runner/test-definition-absent (:seon.error/kind recorded)))` | R8 |
| 1035 | `row (when-not (:seon.error/kind analysis)` | R8 |
| 1061 | `(:seon.error/kind refusal)))))` | R8 |
| 1693 | `(:seon.error/kind refusal)))` | R8 |
| 2200 | `(:seon.error/kind refusal))` | R8 |
| 2215 | `(:seon.error/kind refusal)))` | R8 |
| 2228 | `(:seon.error/kind` | R8 |
| 2372 | `{:seon.error/kind ::runner/worker-exited}}]` | R8 |
| 2390 | `{:seon.error/kind ::runner/worker-exited` | R8 |
| 2401 | `{:seon.error/kind ::runner/worker-retired` | R8 |
| 2747 | `{:seon.error/kind :seon.fresh-operator/prepl-exception` | R8 |
| 2750 | `{:seon.error/kind :seon.instrument/contract-violated}` | R8 |
| 2757 | `(is (= :seon.fresh-operator/prepl-exception (:seon.error/kind failure)))` | R8 |
| 2760 | `(is (= {:seon.error/kind :seon.instrument/contract-violated}` | R8 |
| 2772 | `(fn [] {:seon.error/kind ::probe` | R8 |
| 2824 | `(:seon.error/kind (runner/commit-results!` | R8 |
| 2841 | `(:seon.error/kind missing)))` | R8 |
| 2851 | `(:seon.error/kind truncated)))` | R8 |
| 2857 | `(:seon.error/kind not-a-map)))` | R8 |

### test/seon/test_support.clj

| Line | Source | Replacement |
|---:|---|---|
| 82 | `{:seon.error/kind ::published-base-clone-failed` | R8 |
| 290 | `(when (:seon.error/kind result)` | R8 |
| 292 | `(:seon.error/kind result) ": "` | R8 |
| 328 | `(nil? (:seon.error/kind report))` | R8 |
| 516 | `{:seon.error/kind ::database-base-unavailable` | R8 |
| 530 | `(if (:seon.error/kind result)` | R8 |
| 556 | `(remove :seon.error/kind))` | R8 |
| 897 | `(if (and (map? result) (keyword? (:seon.error/kind result)))` | R8 |

### test/seon/test_support_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 42 | `(is (= :seon.test-support/database-base-unavailable (:seon.error/kind @base)))` | R8 |
| 66 | `(is (nil? (:seon.error/kind constructed))` | R8 |
| 67 | `(pr-str (select-keys constructed [:seon.error/kind :seon.error/message])))` | R8 |
| 89 | `(is (= :seon.db/invalid-write (:seon.error/kind failure)))` | R8 |
| 123 | `(is (= :seon.db/invalid-write (:seon.error/kind (ex-data failure))))` | R8 |
| 217 | `::result-error (:seon.error/kind result)` | R8 |
| 395 | `(is (= {:seon.error/kind ::flat-refusal}` | R8 |
| 397 | `(constantly {:seon.error/kind ::flat-refusal}))))` | R8 |
| 532 | `(is (:seon.error/kind refusal)` | R8 |

### test/seon/test_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 37 | `(:seon.error/kind` | R8 |
| 85 | `(is (= :seon.test/program-mismatch (:seon.error/kind (sut/resolve-test request))))` | R8 |
| 135 | `(:seon.error/kind` | R8 |
| 174 | `(is (:seon.error/kind refused) (pr-str refused))` | R8 |
| 215 | `(is (= :seon.db/invalid-read (:seon.error/kind read-refusal)))` | R8 |
| 223 | `(is (= :seon.test.run/immutable (:seon.error/kind refused)) (pr-str refused))` | R8 |
| 282 | `(is (= :seon.test.run/immutable (:seon.error/kind refused)) (pr-str refused))` | R8 |
| 302 | `(is (= :seon.test/program-mismatch (:seon.error/kind refused)) (pr-str refused))` | R8 |

### test/seon/transact_feedback_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 50 | `(is (= :seon.db/invalid-write (:seon.error/kind refused)))` | R8 |
| 54 | `(is (= :seon.db/invalid-write (:seon.error/kind wrong)))` | R8 |
| 63 | `(is (= :seon.db/invalid-write (:seon.error/kind result)) (pr-str result))` | R8 |
| 160 | `(is (nil? (:seon.error/kind result)) (pr-str result))` | R8 |

### test/seon/transaction_result_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 38 | `(is (= :seon.db/invalid-write (:seon.error/kind refused)) (pr-str refused))` | R8 |

### test/seon/turn_continue_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 84 | `(is (nil? (:seon.error/kind` | R8 |
| 221 | `(is (= :seon.ai/unparseable-body (:seon.error/kind refusal)))` | R8 |

### test/seon/turn_loop_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 109 | `(let [failure {:seon.error/kind :provider/refused` | R8 |
| 367 | `failures [{:seon.error/kind :failure/one}` | R8 |
| 368 | `{:seon.error/kind :failure/two}]` | R8 |
| 381 | `[[:error/tx (:seon.error/kind failure)]])}` | R8 |
| 594 | `refusal {:seon.error/kind :seon.cluster.prompt/budget-exceeded` | R8 |
| 643 | `(is (= (:seon.error/kind refusal) (:seon.error/kind capture)))` | R8 |
| 800 | `unpaid {:seon.error/kind :seon.ai/transport-failure` | R8 |
| 837 | `(is (nil? (:seon.error/kind opening)) (pr-str opening)))` | R8 |
| 964 | `{:seon.error/kind :seon.turn.phase/prompt` | R8 |
| 1049 | `{:seon.error/message "boom" :seon.error/kind :x}` | R8 |
| 1253 | `:seon.eval/shown "{:seon.error/kind :x}"}]))))` | R8 |
| 1406 | `(:seon.error/kind gate-refusal)` | R8 |
| 1407 | `(get-in terminal [:seon.error/value :seon.error/kind])` | R8 |
| 1408 | `(:seon.error/kind stored-value)))` | R8 |
| 1546 | `(is (nil? (:seon.error/kind` | R8 |
| 1571 | `(get-in terminal [:seon.error/value :seon.error/kind]))` | R8 |
| 1667 | `(:seon.error/kind (:refused-outcome settled)))` | R8 |

### test/seon/turn_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 31 | `(when (:seon.error/kind result) (throw (ex-info (pr-str result) result)))` | R8 |
| 120 | `(is (= :seon.turn/generated-read-depends-on-turns (:seon.error/kind fault))` | R8 |
| 216 | `(is (some? (:seon.error/kind result)) (pr-str result))` | R8 |
| 284 | `refusal {:seon.error/kind :seon.turn/refused` | R8 |
| 298 | `:where [?error :seon.error/kind ?kind]] database))]` | R8 |
| 304 | `(is (nil? (:seon.error/kind (:seon.turn.loop/outcome settlement)))` | R8 |
| 349 | `:where [?error :seon.error/kind :seon.turn/refused]]` | R8 |
| 408 | `turn-id (when-not (:seon.error/kind result)` | R8 |
| 444 | `(is (nil? (:seon.error/kind seeded)) (pr-str seeded))` | R8 |
| 491 | `(:seon.error/kind (evaluation/of-agent database "absent")))))` | R8 |
| 500 | `(is (nil? (:seon.error/kind` | R8 |
| 583 | `(is (nil? (:seon.error/kind opening)) (pr-str opening))` | R8 |
| 846 | `(is (nil? (:seon.error/kind result)) (pr-str result))` | R8 |
| 869 | `(if (:seon.error/kind result)` | R8 |
| 1202 | `(is (:seon.error/kind (turn/opening-db @connection "absent-run")))` | R8 |
| 1306 | `(is (not (:seon.error/kind result))` | R8 |
| 1323 | `(is (not (:seon.error/kind result))` | R8 |
| 1352 | `(is (not (:seon.error/kind result))` | R8 |
| 1383 | `(is (not (:seon.error/kind result)) (pr-str result))` | R8 |
| 1431 | `"{:seon.error/kind :x}"` | R6 |
| 2061 | `(cond-> {:seon.error/source {:seon.error/kind kind :seon.error/message message}` | R8 |
| 2155 | `(:seon.error/kind (:seon.turn.loop/parked parked))))` | R8 |
| 2164 | `(:seon.error/kind (ex-data (:clojure.core.async.flow/ex fault)))))` | R8 |

### test/seon/turn_work_test.clj

| Line | Source | Replacement |
|---:|---|---|
| 44 | `{:seon.error/kind :seon.turn.loop/lint-rejected` | R8 |
| 612 | `:seon.error/kind :seon.ai/no-credential` | R8 |

## Class declaration retirement, read as EDN

The complete registry contains **338 marked schemas in 65 resources**. Audit B’s lexical slice has **52 marked schemas in 13 resources** and **34 literal `[:= true]` declarations**. The raw text has 35 `[:= true]` hits: the extra hit is the inline `:seon.db.diff/removed?` constraint at `resources/seon/schemas/seon.db.diff.edn:9`, not an error-class declaration. Do not delete that member as part of this error-marker retirement. Required boolean markers and all true-valued declarations are counted separately. The `:seon.error/class` property declaration and unused refusal marker also require retirement.

The table resolves aliases and inherited maps to identify each marked schema’s actual members. Delete boolean-only schemas and their marker attributes. Retain substantive payload semantics, extending base and applying the explicit PRD §3 remappings. The common contracts are not a license to rename the old kind.

| Resource | Class schema | Boolean attributes to retire | Payloads to retain or remap per PRD |
|---|---|---|---|
| `resources/seon/schemas/my.background.edn` | `:my.background/invalid-call-error` | `:my.background/invalid-call` | — |
| `resources/seon/schemas/my.background.edn` | `:my.background/invalid-result-error` | `:my.background/invalid-result` | — |
| `resources/seon/schemas/my.background.edn` | `:my.background/missing-result-error` | `:my.background/missing-result` | — |
| `resources/seon/schemas/my.edit.edn` | `:my.edit/ambiguous-match-error` | — | `:my.edit/ambiguous-match` |
| `resources/seon/schemas/my.edit.edn` | `:my.edit/lossless-check-failed-error` | `:my.edit/lossless-check-failed` | — |
| `resources/seon/schemas/my.edit.edn` | `:my.edit/no-match-error` | — | `:my.edit/no-match` |
| `resources/seon/schemas/my.edit.edn` | `:my.edit/not-utf8-error` | — | `:my.edit/not-utf8` |
| `resources/seon/schemas/my.edit.edn` | `:my.edit/parse-refused-error` | `:my.edit/parse-refused` | — |
| `resources/seon/schemas/my.edit.edn` | `:my.edit/stale-source-error` | — | `:my.edit/stale-source` |
| `resources/seon/schemas/my.fs.edn` | `:my.fs/already-exists-error` | — | `:my.fs/already-exists` |
| `resources/seon/schemas/my.fs.edn` | `:my.fs/atomic-write-unsupported-error` | `:my.fs/atomic-write-unsupported` | — |
| `resources/seon/schemas/my.fs.edn` | `:my.fs/blob-unavailable-error` | — | `:my.fs/blob-unavailable` |
| `resources/seon/schemas/my.fs.edn` | `:my.fs/changed-during-read-error` | — | `:my.fs/changed-during-read` |
| `resources/seon/schemas/my.fs.edn` | `:my.fs/glob-failed-error` | — | `:my.fs/glob-failed` |
| `resources/seon/schemas/my.fs.edn` | `:my.fs/invalid-glob-error` | — | `:my.fs/invalid-glob` |
| `resources/seon/schemas/my.fs.edn` | `:my.fs/invalid-utf8-window-error` | — | `:my.fs/invalid-utf8-window` |
| `resources/seon/schemas/my.fs.edn` | `:my.fs/not-directory-error` | — | `:my.fs/not-directory` |
| `resources/seon/schemas/my.fs.edn` | `:my.fs/not-found-error` | — | `:my.fs/not-found` |
| `resources/seon/schemas/my.fs.edn` | `:my.fs/not-regular-file-error` | — | `:my.fs/not-regular-file` |
| `resources/seon/schemas/my.fs.edn` | `:my.fs/path-refused-error` | — | `:my.fs/path-refused` |
| `resources/seon/schemas/my.fs.edn` | `:my.fs/read-failed-error` | `:my.fs/read-failed` | — |
| `resources/seon/schemas/my.fs.edn` | `:my.fs/read-limit-error` | — | `:my.fs/read-limit` |
| `resources/seon/schemas/my.fs.edn` | `:my.fs/stale-digest-error` | — | `:my.fs/stale-digest` |
| `resources/seon/schemas/my.fs.edn` | `:my.fs/write-failed-error` | `:my.fs/write-failed` | — |
| `resources/seon/schemas/my.fs.edn` | `:my.fs/write-limit-error` | — | `:my.fs/write-limit` |
| `resources/seon/schemas/my.message.edn` | `:my.message/no-about-error` | `:my.message/no-about` | — |
| `resources/seon/schemas/my.message.edn` | `:my.message/no-assignment-error` | `:my.message/no-assignment` | — |
| `resources/seon/schemas/my.message.edn` | `:my.message/no-content-error` | `:my.message/no-content` | — |
| `resources/seon/schemas/my.message.edn` | `:my.message/no-reason-error` | `:my.message/no-reason` | — |
| `resources/seon/schemas/my.message.edn` | `:my.message/no-recipient-error` | `:my.message/no-recipient` | — |
| `resources/seon/schemas/my.message.edn` | `:my.message/not-found-error` | — | `:my.message/not-found` |
| `resources/seon/schemas/my.note.edn` | `:my.note/about-not-found-error` | — | `:my.note/about-not-found` |
| `resources/seon/schemas/my.note.edn` | `:my.note/agent-not-found-error` | — | `:my.note/agent-not-found` |
| `resources/seon/schemas/my.note.edn` | `:my.note/identity-owned-by-another-agent-error` | — | `:my.note/identity-owned-by-another-agent` |
| `resources/seon/schemas/my.note.edn` | `:my.note/not-found-error` | — | `:my.note/not-found` |
| `resources/seon/schemas/my.note.edn` | `:my.note/not-owned-error` | — | `:my.note/not-owned` |
| `resources/seon/schemas/my.plan.edn` | `:my.plan/agent-not-found-error` | `:my.plan/agent-not-found` | — |
| `resources/seon/schemas/my.plan.edn` | `:my.plan/dependency-cycle-error` | `:my.plan/dependency-cycle` | — |
| `resources/seon/schemas/my.plan.edn` | `:my.plan/dependency-not-found-error` | `:my.plan/dependency-not-found` | — |
| `resources/seon/schemas/my.plan.edn` | `:my.plan/duplicate-identity-error` | `:my.plan/duplicate-identity` | — |
| `resources/seon/schemas/my.plan.edn` | `:my.plan/duplicate-position-error` | `:my.plan/duplicate-position` | — |
| `resources/seon/schemas/my.plan.edn` | `:my.plan/foreign-identity-error` | `:my.plan/foreign-identity` | — |
| `resources/seon/schemas/my.plan.edn` | `:my.plan/identity-exists-error` | `:my.plan/identity-exists` | — |
| `resources/seon/schemas/my.plan.edn` | `:my.plan/item-reference-not-found-error` | `:my.plan/item-reference-not-found` | — |
| `resources/seon/schemas/my.plan.edn` | `:my.plan/item-reference-not-owned-error` | `:my.plan/item-reference-not-owned` | — |
| `resources/seon/schemas/my.plan.edn` | `:my.plan/not-found-error` | `:my.plan/not-found` | — |
| `resources/seon/schemas/my.plan.edn` | `:my.plan/not-owned-error` | `:my.plan/not-owned` | — |
| `resources/seon/schemas/my.plan.edn` | `:my.plan/subject-not-found-error` | `:my.plan/subject-not-found` | — |
| `resources/seon/schemas/my.plan.edn` | `:my.plan/unusable-current-step-error` | `:my.plan/unusable-current-step` | — |
| `resources/seon/schemas/my.shell.edn` | `:my.shell/blob-unavailable-error` | `:my.shell/blob-unavailable` | — |
| `resources/seon/schemas/my.shell.edn` | `:my.shell/cwd-refused-error` | `:my.shell/cwd-refused` | — |
| `resources/seon/schemas/my.shell.edn` | `:my.shell/start-failed-error` | `:my.shell/start-failed` | — |
| `resources/seon/schemas/my.shell.edn` | `:my.shell/stdin-limit-error` | `:my.shell/stdin-limit` | — |
| `resources/seon/schemas/my.shell.edn` | `:my.shell/time-limit-error` | `:my.shell/time-limit` | — |
| `resources/seon/schemas/my.turn.edn` | `:my.turn/blank-note-error` | `:my.turn/blank-note` | — |
| `resources/seon/schemas/my.turn.edn` | `:my.turn/blank-result-error` | `:my.turn/blank-result` | — |
| `resources/seon/schemas/my.turn.edn` | `:my.turn/usage-walkthrough-absent-error` | `:my.turn/usage-walkthrough-absent` | — |
| `resources/seon/schemas/my.web.edn` | `:my.web/invalid-url-error` | `:my.web/invalid-url` | `:my.web/url` |
| `resources/seon/schemas/my.web.edn` | `:my.web/missing-location-error` | `:my.web/missing-location` | `:my.web/url`, `:my.web/status` |
| `resources/seon/schemas/my.web.edn` | `:my.web/no-credential-error` | `:my.web/no-credential` | `:my.web/query` |
| `resources/seon/schemas/my.web.edn` | `:my.web/projection-failed-error` | `:my.web/projection-failed` | `:my.web/query` |
| `resources/seon/schemas/my.web.edn` | `:my.web/provider-failed-error` | `:my.web/provider-failed` | `:my.web/query`, `:my.web/status` |
| `resources/seon/schemas/my.web.edn` | `:my.web/redirect-limit-error` | `:my.web/redirect-limit` | `:my.web/url` |
| `resources/seon/schemas/my.web.edn` | `:my.web/redirect-loop-error` | `:my.web/redirect-loop` | `:my.web/url` |
| `resources/seon/schemas/my.web.edn` | `:my.web/response-limit-error` | `:my.web/response-limit` | `:my.web/url` |
| `resources/seon/schemas/my.web.edn` | `:my.web/timeout-error` | `:my.web/timeout` | `:my.web/url`, `:my.web/query` |
| `resources/seon/schemas/my.web.edn` | `:my.web/transport-failed-error` | `:my.web/transport-failed` | `:my.web/url`, `:my.web/query` |
| `resources/seon/schemas/my.web.edn` | `:my.web/unparseable-response-error` | `:my.web/unparseable-response` | `:my.web/query` |
| `resources/seon/schemas/seon.agent.edn` | `:seon.agent/armer-quiescence-undeliverable-error` | `:seon.agent/armer-quiescence-undeliverable` | — |
| `resources/seon/schemas/seon.agent.edn` | `:seon.agent/creation-incomplete-error` | — | `:seon.agent/creation-incomplete` |
| `resources/seon/schemas/seon.agent.edn` | `:seon.agent/no-such-agent-error` | — | `:seon.agent/no-such-agent` |
| `resources/seon/schemas/seon.agent.edn` | `:seon.agent/supervision-not-committed-error` | `:seon.agent/supervision-not-committed` | — |
| `resources/seon/schemas/seon.agent.edn` | `:seon.agent/turn-completion-backstop-error` | — | `:seon.agent/turn-completion-backstop` |
| `resources/seon/schemas/seon.agent.edn` | `:seon.agent/turn-completion-undeliverable-error` | — | `:seon.agent/turn-completion-undeliverable` |
| `resources/seon/schemas/seon.ai.edn` | `:seon.ai/authentication-failure-error` | — | `:seon.ai/authentication-failure` |
| `resources/seon/schemas/seon.ai.edn` | `:seon.ai/authorization-failure-error` | — | `:seon.ai/authorization-failure` |
| `resources/seon/schemas/seon.ai.edn` | `:seon.ai/credential-failure-error` | `:seon.ai/credential-failure` | — |
| `resources/seon/schemas/seon.ai.edn` | `:seon.ai/extra-body-conflict-error` | `:seon.ai/extra-body-conflict` | — |
| `resources/seon/schemas/seon.ai.edn` | `:seon.ai/invalid-extra-body-error` | `:seon.ai/invalid-extra-body` | — |
| `resources/seon/schemas/seon.ai.edn` | `:seon.ai/model-failure-error` | `:seon.ai/model-failure` | — |
| `resources/seon/schemas/seon.ai.edn` | `:seon.ai/no-credential-error` | `:seon.ai/no-credential` | — |
| `resources/seon/schemas/seon.ai.edn` | `:seon.ai/provider-error-error` | — | `:seon.ai/provider-error` |
| `resources/seon/schemas/seon.ai.edn` | `:seon.ai/provider-server-failure-error` | — | `:seon.ai/provider-server-failure` |
| `resources/seon/schemas/seon.ai.edn` | `:seon.ai/rate-limited-error` | — | `:seon.ai/rate-limited` |
| `resources/seon/schemas/seon.ai.edn` | `:seon.ai/reasoning-without-answer-error` | `:seon.ai/reasoning-without-answer` | — |
| `resources/seon/schemas/seon.ai.edn` | `:seon.ai/request-failure-error` | — | `:seon.ai/request-failure` |
| `resources/seon/schemas/seon.ai.edn` | `:seon.ai/response-failure-error` | `:seon.ai/response-failure` | — |
| `resources/seon/schemas/seon.ai.edn` | `:seon.ai/stream-truncated-error` | `:seon.ai/stream-truncated` | — |
| `resources/seon/schemas/seon.ai.edn` | `:seon.ai/timeout-error` | — | `:seon.ai/timeout` |
| `resources/seon/schemas/seon.ai.edn` | `:seon.ai/token-starvation-error` | `:seon.ai/token-starvation` | — |
| `resources/seon/schemas/seon.ai.edn` | `:seon.ai/transport-before-send-failure-error` | `:seon.ai/transport-before-send-failure` | — |
| `resources/seon/schemas/seon.ai.edn` | `:seon.ai/transport-failure-error` | — | `:seon.ai/transport-failure` |
| `resources/seon/schemas/seon.ai.edn` | `:seon.ai/transport-outcome-unknown-error` | — | `:seon.ai/transport-outcome-unknown` |
| `resources/seon/schemas/seon.ai.edn` | `:seon.ai/unparseable-body-error` | `:seon.ai/unparseable-body` | — |
| `resources/seon/schemas/seon.artifact.edn` | `:seon.artifact/refused-error` | `:seon.artifact/refused` | `:seon.turn/rule`, `:seon.turn/transition` |
| `resources/seon/schemas/seon.blob.edn` | `:seon.blob/content-digest-mismatch-error` | — | `:seon.blob/content-digest-mismatch` |
| `resources/seon/schemas/seon.blob.edn` | `:seon.blob/input-stalled-error` | — | `:seon.blob/input-stalled` |
| `resources/seon/schemas/seon.blob.edn` | `:seon.blob/invalid-threshold-error` | — | `:seon.blob/invalid-threshold` |
| `resources/seon/schemas/seon.blob.edn` | `:seon.blob/store-root-absent-error` | — | `:seon.blob/store-root-absent` |
| `resources/seon/schemas/seon.blob.edn` | `:seon.blob/stored-content-mismatch-error` | — | `:seon.blob/stored-content-mismatch` |
| `resources/seon/schemas/seon.boot.edn` | `:seon.boot/refused-error` | `:seon.boot/refused` | `:seon.turn/rule`, `:seon.turn/transition` |
| `resources/seon/schemas/seon.bootstrap.edn` | `:seon.bootstrap/prefix-drift-error` | `:seon.bootstrap/prefix-drift` | — |
| `resources/seon/schemas/seon.bootstrap.edn` | `:seon.bootstrap/root-acquisition-empty-error` | `:seon.bootstrap/root-acquisition-empty` | — |
| `resources/seon/schemas/seon.cluster.export.edn` | `:seon.cluster.export/clone-unsupported-error` | — | `:seon.cluster.export/clone-unsupported` |
| `resources/seon/schemas/seon.cluster.export.edn` | `:seon.cluster.export/export-exists-error` | — | `:seon.cluster.export/export-exists` |
| `resources/seon/schemas/seon.cluster.export.edn` | `:seon.cluster.export/genesis-incomplete-error` | — | `:seon.cluster.export/genesis-incomplete` |
| `resources/seon/schemas/seon.cluster.export.edn` | `:seon.cluster.export/no-branch-head-error` | — | `:seon.cluster.export/no-branch-head` |
| `resources/seon/schemas/seon.cluster.export.edn` | `:seon.cluster.export/refused-error` | — | `:seon.turn/rule`, `:seon.turn/transition`, `:seon.cluster.export/refused` |
| `resources/seon/schemas/seon.cluster.process.edn` | `:seon.cluster.process/start-instant-unavailable-error` | — | `:seon.cluster.process/start-instant-unavailable` |
| `resources/seon/schemas/seon.cluster.prompt.edn` | `:seon.cluster.prompt/budget-exceeded-error` | — | `:seon.cluster.prompt/budget-exceeded` |
| `resources/seon/schemas/seon.cluster.prompt.edn` | `:seon.cluster.prompt/missing-cluster-error` | — | `:seon.cluster.prompt/missing-cluster` |
| `resources/seon/schemas/seon.cluster.prompt.edn` | `:seon.cluster.prompt/missing-config-error` | — | `:seon.cluster.prompt/missing-config` |
| `resources/seon/schemas/seon.cluster.prompt.edn` | `:seon.cluster.prompt/no-trigger-error` | `:seon.cluster.prompt/no-trigger` | — |
| `resources/seon/schemas/seon.cluster.prompt.edn` | `:seon.cluster.prompt/refused-error` | — | `:seon.turn/rule`, `:seon.turn/transition`, `:seon.cluster.prompt/refused` |
| `resources/seon/schemas/seon.cluster.registry.edn` | `:seon.cluster.registry/branch-head-absent-error` | `:seon.cluster.registry/branch-head-absent` | — |
| `resources/seon/schemas/seon.cluster.registry.edn` | `:seon.cluster.registry/candidate-file-absent-error` | `:seon.cluster.registry/candidate-file-absent` | — |
| `resources/seon/schemas/seon.cluster.registry.edn` | `:seon.cluster.registry/cannot-retire-main-error` | — | `:seon.cluster.registry/cannot-retire-main` |
| `resources/seon/schemas/seon.cluster.registry.edn` | `:seon.cluster.registry/cluster-connected-error` | — | `:seon.cluster.registry/cluster-connected` |
| `resources/seon/schemas/seon.cluster.registry.edn` | `:seon.cluster.registry/dry-run-barrier-absent-error` | `:seon.cluster.registry/dry-run-barrier-absent` | — |
| `resources/seon/schemas/seon.cluster.registry.edn` | `:seon.cluster.registry/dry-run-complete-error` | `:seon.cluster.registry/dry-run-complete` | — |
| `resources/seon/schemas/seon.cluster.registry.edn` | `:seon.cluster.registry/refused-error` | — | `:seon.turn/rule`, `:seon.turn/transition`, `:seon.cluster.registry/refused` |
| `resources/seon/schemas/seon.cluster.registry.edn` | `:seon.cluster.registry/source-absent-error` | — | `:seon.cluster.registry/source-absent` |
| `resources/seon/schemas/seon.cluster.reply.edn` | `:seon.cluster.reply/no-forms-error` | `:seon.cluster.reply/no-forms` | — |
| `resources/seon/schemas/seon.cluster.reply.edn` | `:seon.cluster.reply/refused-tag-error` | — | `:seon.cluster.reply/refused-tag` |
| `resources/seon/schemas/seon.cluster.reply.edn` | `:seon.cluster.reply/unreadable-error` | — | `:seon.cluster.reply/unreadable` |
| `resources/seon/schemas/seon.cluster.source.edn` | `:seon.cluster.source/invalid-source-seal-error` | — | `:seon.cluster.source/invalid-source-seal` |
| `resources/seon/schemas/seon.cluster.source.edn` | `:seon.cluster.source/populate-unresolvable-error` | — | `:seon.cluster.source/populate-unresolvable` |
| `resources/seon/schemas/seon.cluster.source.edn` | `:seon.cluster.source/publish-readback-failed-error` | — | `:seon.cluster.source/publish-readback-failed` |
| `resources/seon/schemas/seon.cluster.source.edn` | `:seon.cluster.source/refused-error` | — | `:seon.turn/rule`, `:seon.turn/transition`, `:seon.cluster.source/refused` |
| `resources/seon/schemas/seon.cluster.source.edn` | `:seon.cluster.source/root-absent-error` | — | `:seon.cluster.source/root-absent` |
| `resources/seon/schemas/seon.cluster.source.edn` | `:seon.cluster.source/stale-publication-error` | — | `:seon.cluster.source/stale-publication` |
| `resources/seon/schemas/seon.cluster.source.edn` | `:seon.cluster.source/unsafe-incremental-rows-error` | `:seon.cluster.source/unsafe-incremental-rows` | — |
| `resources/seon/schemas/seon.cluster.store.edn` | `:seon.cluster.store/branch-absent-error` | — | `:seon.cluster.store/branch-absent` |
| `resources/seon/schemas/seon.cluster.store.edn` | `:seon.cluster.store/branch-already-open-error` | — | `:seon.cluster.store/branch-already-open` |
| `resources/seon/schemas/seon.cluster.store.edn` | `:seon.cluster.store/file-lock-generator-failed-error` | — | `:seon.cluster.store/file-lock-generator-failed` |
| `resources/seon/schemas/seon.cluster.store.edn` | `:seon.cluster.store/held-elsewhere-error` | — | `:seon.cluster.store/held-elsewhere` |
| `resources/seon/schemas/seon.cluster.store.edn` | `:seon.cluster.store/initialization-incomplete-error` | — | `:seon.cluster.store/initialization-incomplete` |
| `resources/seon/schemas/seon.cluster.store.edn` | `:seon.cluster.store/refused-error` | — | `:seon.turn/rule`, `:seon.turn/transition`, `:seon.cluster.store/refused` |
| `resources/seon/schemas/seon.cluster.wake.edn` | `:seon.cluster.wake/undeliverable-wake-error` | — | `:seon.cluster.wake/undeliverable-wake` |
| `resources/seon/schemas/seon.config.edn` | `:seon.config/manifest-unreadable-error` | — | `:seon.config/manifest-unreadable` |
| `resources/seon/schemas/seon.config.edn` | `:seon.config/missing-effective-error` | — | `:seon.config/missing-effective` |
| `resources/seon/schemas/seon.config.edn` | `:seon.config/missing-result-cap-error` | `:seon.config/missing-result-cap` | — |
| `resources/seon/schemas/seon.config.edn` | `:seon.config/reconcile-refused-error` | — | `:seon.config/reconcile-refused` |
| `resources/seon/schemas/seon.config.edn` | `:seon.config/refused-error` | — | `:seon.turn/rule`, `:seon.turn/transition`, `:seon.config/refused` |
| `resources/seon/schemas/seon.config.edn` | `:seon.config/required-absent-error` | — | `:seon.config/required-absent` |
| `resources/seon/schemas/seon.config.edn` | `:seon.config/unknown-key-error` | — | `:seon.config/unknown-key` |
| `resources/seon/schemas/seon.context.edn` | `:seon.context/selection-refused-error` | — | `:seon.turn/rule`, `:seon.turn/transition`, `:seon.context/selection-refused` |
| `resources/seon/schemas/seon.db.edn` | `:seon.db/diff-refused-error` | `:seon.db/diff-refused` | — |
| `resources/seon/schemas/seon.db.edn` | `:seon.db/invalid-read-error` | `:seon.db/invalid-read` | — |
| `resources/seon/schemas/seon.db.edn` | `:seon.db/invalid-request-error` | `:seon.db/invalid-request` | — |
| `resources/seon/schemas/seon.db.edn` | `:seon.db/transaction-outcome-unknown-error` | `:seon.db/transaction-outcome-unknown` | — |
| `resources/seon/schemas/seon.db.edn` | `:seon.db/transaction-refused-error` | `:seon.db/transaction-refused` | — |
| `resources/seon/schemas/seon.dev.mcp.artifact.edn` | `:seon.dev.mcp.artifact/root-not-committed-error` | — | `:seon.dev.mcp.artifact/root-not-committed` |
| `resources/seon/schemas/seon.dev.mcp.edn` | `:seon.dev.mcp/cluster-degraded-error` | — | `:seon.dev.mcp/cluster-degraded` |
| `resources/seon/schemas/seon.dev.mcp.edn` | `:seon.dev.mcp/jvm-exception-error` | `:seon.dev.mcp/jvm-exception` | — |
| `resources/seon/schemas/seon.dev.mcp.edn` | `:seon.dev.mcp/nil-deref-error` | `:seon.dev.mcp/nil-deref` | — |
| `resources/seon/schemas/seon.dev.mcp.edn` | `:seon.dev.mcp/remainder-not-retrievable-error` | — | `:seon.dev.mcp/remainder-not-retrievable` |
| `resources/seon/schemas/seon.dev.mcp.edn` | `:seon.dev.mcp/value-not-found-error` | — | `:seon.dev.mcp/value-not-found` |
| `resources/seon/schemas/seon.effect.edn` | `:seon.effect/already-recorded-error` | `:seon.effect/already-recorded` | — |
| `resources/seon/schemas/seon.effect.edn` | `:seon.effect/already-settled-error` | `:seon.effect/already-settled` | — |
| `resources/seon/schemas/seon.effect.edn` | `:seon.effect/missing-receipt-error` | `:seon.effect/missing-receipt` | — |
| `resources/seon/schemas/seon.env.edn` | `:seon.env/absent-environment-error` | `:seon.env/absent-environment` | — |
| `resources/seon/schemas/seon.env.edn` | `:seon.env/agent-id-absent-error` | `:seon.env/agent-id-absent` | — |
| `resources/seon/schemas/seon.env.edn` | `:seon.env/incomplete-environment-error` | `:seon.env/incomplete-environment` | — |
| `resources/seon/schemas/seon.env.edn` | `:seon.env/invalid-environment-replacement-error` | `:seon.env/invalid-environment-replacement` | — |
| `resources/seon/schemas/seon.env.edn` | `:seon.env/invalid-environment-state-error` | `:seon.env/invalid-environment-state` | — |
| `resources/seon/schemas/seon.env.edn` | `:seon.env/invalid-member-error` | `:seon.env/invalid-member` | — |
| `resources/seon/schemas/seon.env.edn` | `:seon.env/schema-absent-error` | `:seon.env/schema-absent` | — |
| `resources/seon/schemas/seon.env.edn` | `:seon.env/unscopable-member-error` | `:seon.env/unscopable-member` | — |
| `resources/seon/schemas/seon.error.edn` | `:seon.error/unclassified-error` | `:seon.error/unclassified` | — |
| `resources/seon/schemas/seon.eval.drive.edn` | `:seon.eval.drive/absent-error` | `:seon.eval.drive/absent` | — |
| `resources/seon/schemas/seon.flow.edn` | `:seon.flow/configuration-error` | `:seon.flow/configuration` | — |
| `resources/seon/schemas/seon.flow.edn` | `:seon.flow/fault-channel-overflow-error` | `:seon.flow/fault-channel-overflow` | — |
| `resources/seon/schemas/seon.flow.edn` | `:seon.flow/launcher-stopped-error` | — | `:seon.flow/launcher-stopped` |
| `resources/seon/schemas/seon.flow.edn` | `:seon.flow/submission-capacity-error` | — | `:seon.flow/submission-capacity` |
| `resources/seon/schemas/seon.flow.edn` | `:seon.flow/time-limit-error` | — | `:seon.flow/time-limit` |
| `resources/seon/schemas/seon.flow.edn` | `:seon.flow/timeout-error` | — | `:seon.flow/timeout` |
| `resources/seon/schemas/seon.fn.binding.edn` | `:seon.fn.binding/unsupported-error` | `:seon.fn.binding/unsupported` | — |
| `resources/seon/schemas/seon.fn.edn` | `:seon.fn/analysis-failed-error` | `:seon.fn/analysis-failed` | — |
| `resources/seon/schemas/seon.fn.edn` | `:seon.fn/capability-graph-malformed-error` | — | `:seon.fn/capability-graph-malformed` |
| `resources/seon/schemas/seon.fn.edn` | `:seon.fn/duplicate-program-identity-error` | `:seon.fn/duplicate-program-identity` | — |
| `resources/seon/schemas/seon.fn.edn` | `:seon.fn/index-refused-error` | `:seon.fn/index-refused` | — |
| `resources/seon/schemas/seon.fn.edn` | `:seon.fn/index-transaction-refused-error` | — | `:seon.fn/index-transaction-refused` |
| `resources/seon/schemas/seon.fn.edn` | `:seon.fn/manifest-absent-error` | `:seon.fn/manifest-absent` | — |
| `resources/seon/schemas/seon.fn.edn` | `:seon.fn/population-incomplete-error` | — | `:seon.fn/population-incomplete` |
| `resources/seon/schemas/seon.fn.edn` | `:seon.fn/schema-declaration-invalid-error` | — | `:seon.fn/schema-declaration-invalid` |
| `resources/seon/schemas/seon.fn.edn` | `:seon.fn/scratch-not-fresh-error` | — | `:seon.fn/scratch-not-fresh` |
| `resources/seon/schemas/seon.fn.edn` | `:seon.fn/signature-refused-error` | `:seon.fn/signature-refused` | — |
| `resources/seon/schemas/seon.fn.edn` | `:seon.fn/source-checkout-required-error` | — | `:seon.fn/source-checkout-required` |
| `resources/seon/schemas/seon.fn.edn` | `:seon.fn/source-file-invalid-error` | — | `:seon.fn/source-file-invalid` |
| `resources/seon/schemas/seon.fn.edn` | `:seon.fn/source-span-absent-error` | `:seon.fn/source-span-absent` | — |
| `resources/seon/schemas/seon.instrument.edn` | `:seon.instrument/contract-violated-error` | — | `:seon.instrument/contract-violated` |
| `resources/seon/schemas/seon.instrument.edn` | `:seon.instrument/registration-failed-error` | `:seon.instrument/registration-failed` | — |
| `resources/seon/schemas/seon.message.edn` | `:seon.message/blank-content-error` | `:seon.message/blank-content` | — |
| `resources/seon/schemas/seon.message.edn` | `:seon.message/chain-limit-error` | — | `:seon.message/chain-limit` |
| `resources/seon/schemas/seon.message.edn` | `:seon.message/content-too-large-error` | — | `:seon.message/content-too-large` |
| `resources/seon/schemas/seon.message.edn` | `:seon.message/no-limit-error` | `:seon.message/no-limit` | — |
| `resources/seon/schemas/seon.message.edn` | `:seon.message/unknown-recipient-error` | — | `:seon.message/unknown-recipient` |
| `resources/seon/schemas/seon.operator.collect.edn` | `:seon.operator.collect/unrecognized-option-error` | `:seon.operator.collect/unrecognized-option` | `:seon.operator.collect/option-key` |
| `resources/seon/schemas/seon.operator.edn` | `:seon.operator/cluster-cleanup-incomplete-error` | `:seon.operator/cluster-cleanup-incomplete` | — |
| `resources/seon/schemas/seon.operator.edn` | `:seon.operator/collection-incomplete-error` | `:seon.operator/collection-incomplete` | — |
| `resources/seon/schemas/seon.operator.edn` | `:seon.operator/failed-error` | `:seon.operator/failed` | — |
| `resources/seon/schemas/seon.operator.edn` | `:seon.operator/low-disk-space-error` | — | `:seon.operator/low-disk-space` |
| `resources/seon/schemas/seon.operator.edn` | `:seon.operator/process-census-incomplete-error` | `:seon.operator/process-census-incomplete` | — |
| `resources/seon/schemas/seon.operator.edn` | `:seon.operator/reap-incomplete-error` | `:seon.operator/reap-incomplete` | — |
| `resources/seon/schemas/seon.print.edn` | `:seon.print/unknown-face-error` | — | `:seon.print/unknown-face` |
| `resources/seon/schemas/seon.problems.edn` | `:seon.problems/evaluation-failed-error` | `:seon.problems/evaluation-failed` | — |
| `resources/seon/schemas/seon.problems.edn` | `:seon.problems/unbound-var-error` | — | `:seon.problems/unbound-var` |
| `resources/seon/schemas/seon.program.edn` | `:seon.program/declaration-refused-error` | `:seon.program/declaration-refused` | — |
| `resources/seon/schemas/seon.program.edn` | `:seon.program/no-declaration-at-error` | `:seon.program/no-declaration-at` | — |
| `resources/seon/schemas/seon.program.edn` | `:seon.program/not-found-error` | — | `:seon.program/not-found` |
| `resources/seon/schemas/seon.program.edn` | `:seon.program/read-refused-error` | `:seon.program/read-refused` | — |
| `resources/seon/schemas/seon.reconcile.edn` | `:seon.reconcile/duplicate-identity-error` | `:seon.reconcile/duplicate-identity` | — |
| `resources/seon/schemas/seon.reconcile.edn` | `:seon.reconcile/identity-outside-scope-error` | `:seon.reconcile/identity-outside-scope` | — |
| `resources/seon/schemas/seon.reconcile.edn` | `:seon.reconcile/missing-declarations-error` | `:seon.reconcile/missing-declarations` | — |
| `resources/seon/schemas/seon.reconcile.edn` | `:seon.reconcile/no-identity-error` | `:seon.reconcile/no-identity` | — |
| `resources/seon/schemas/seon.reconcile.edn` | `:seon.reconcile/refused-error` | — | `:seon.turn/rule`, `:seon.turn/transition`, `:seon.reconcile/refused` |
| `resources/seon/schemas/seon.reconcile.edn` | `:seon.reconcile/two-identities-error` | `:seon.reconcile/two-identities` | — |
| `resources/seon/schemas/seon.render.data.edn` | `:seon.render.data/no-such-path-error` | `:seon.render.data/no-such-path` | — |
| `resources/seon/schemas/seon.render.edn` | `:seon.render/ambiguous-error` | `:seon.render/ambiguous` | — |
| `resources/seon/schemas/seon.render.edn` | `:seon.render/invalid-output-error` | — | `:seon.render/invalid-output` |
| `resources/seon/schemas/seon.render.edn` | `:seon.render/unknown` | — | `:seon.render.unknown/reason`, `:seon.render.unknown/producer`, `:seon.render.unknown/output`, `:seon.render.unknown/refusal`, `:seon.render.unknown/throwable`, `:seon.render.unknown/call` |
| `resources/seon/schemas/seon.render.edn` | `:seon.render/walk-failed-error` | `:seon.render/walk-failed` | — |
| `resources/seon/schemas/seon.render.hiccup.edn` | `:seon.render.hiccup/unparseable-tag-error` | — | `:seon.render.hiccup/unparseable-tag` |
| `resources/seon/schemas/seon.render.value.edn` | `:seon.render.value/missing-root-identity-error` | `:seon.render.value/missing-root-identity` | — |
| `resources/seon/schemas/seon.render.value.edn` | `:seon.render.value/window-failed-error` | — | `:seon.render.value/window-failed` |
| `resources/seon/schemas/seon.render.value.edn` | `:seon.render.value/window-realization-failed-error` | `:seon.render.value/window-realization-failed` | — |
| `resources/seon/schemas/seon.render.walk.edn` | `:seon.render.walk/connections-failed-error` | `:seon.render.walk/connections-failed` | — |
| `resources/seon/schemas/seon.render.walk.edn` | `:seon.render.walk/elided-error` | `:seon.render.walk/elided` | — |
| `resources/seon/schemas/seon.render.walk.edn` | `:seon.render.walk/no-such-entity-error` | `:seon.render.walk/no-such-entity` | — |
| `resources/seon/schemas/seon.render.web.edn` | `:seon.render.web/function-unavailable-error` | — | `:seon.render.web/function-unavailable` |
| `resources/seon/schemas/seon.render.web.edn` | `:seon.render.web/invalid-context-action-error` | `:seon.render.web/invalid-context-action` | — |
| `resources/seon/schemas/seon.render.web.edn` | `:seon.render.web/live-processes-unavailable-error` | `:seon.render.web/live-processes-unavailable` | — |
| `resources/seon/schemas/seon.render.web.edn` | `:seon.render.web/missing-port-error` | — | `:seon.render.web/missing-port` |
| `resources/seon/schemas/seon.render.web.edn` | `:seon.render.web/owner-not-ensured-error` | `:seon.render.web/owner-not-ensured` | — |
| `resources/seon/schemas/seon.render.web.edn` | `:seon.render.web/preview-unavailable-error` | `:seon.render.web/preview-unavailable` | — |
| `resources/seon/schemas/seon.render.web.edn` | `:seon.render.web/prospective-context-unavailable-error` | `:seon.render.web/prospective-context-unavailable` | — |
| `resources/seon/schemas/seon.render.web.edn` | `:seon.render.web/value-not-found-error` | — | `:seon.render.web/value-not-found` |
| `resources/seon/schemas/seon.render.web.edn` | `:seon.render.web/value-unreadable-error` | — | `:seon.render.web/value-unreadable` |
| `resources/seon/schemas/seon.schedule.edn` | `:seon.schedule/incomplete-task-error` | `:seon.schedule/incomplete-task` | — |
| `resources/seon/schemas/seon.schedule.edn` | `:seon.schedule/invalid-fire-id-error` | `:seon.schedule/invalid-fire-id` | — |
| `resources/seon/schemas/seon.schedule.edn` | `:seon.schedule/invalid-task-owner-error` | `:seon.schedule/invalid-task-owner` | — |
| `resources/seon/schemas/seon.schedule.edn` | `:seon.schedule/invalid-terminal-arm-error` | `:seon.schedule/invalid-terminal-arm` | — |
| `resources/seon/schemas/seon.schedule.edn` | `:seon.schedule/missing-execution-handle-error` | `:seon.schedule/missing-execution-handle` | — |
| `resources/seon/schemas/seon.schedule.edn` | `:seon.schedule/missing-receipt-error` | `:seon.schedule/missing-receipt` | — |
| `resources/seon/schemas/seon.schedule.edn` | `:seon.schedule/unresolved-handler-error` | `:seon.schedule/unresolved-handler` | — |
| `resources/seon/schemas/seon.schema.datahike.edn` | `:seon.schema.datahike/attribute-absent-error` | — | `:seon.schema.datahike/attribute-absent` |
| `resources/seon/schemas/seon.schema.datahike.edn` | `:seon.schema.datahike/enum-not-storable-error` | `:seon.schema.datahike/enum-not-storable` | — |
| `resources/seon/schemas/seon.schema.datahike.edn` | `:seon.schema.datahike/invalid-secondary-attribute-error` | — | `:seon.schema.datahike/invalid-secondary-attribute` |
| `resources/seon/schemas/seon.schema.datahike.edn` | `:seon.schema.datahike/literal-not-storable-error` | `:seon.schema.datahike/literal-not-storable` | — |
| `resources/seon/schemas/seon.schema.datahike.edn` | `:seon.schema.datahike/malformed-edn-error` | — | `:seon.schema.datahike/malformed-edn` |
| `resources/seon/schemas/seon.schema.datahike.edn` | `:seon.schema.datahike/nilable-attribute-error` | — | `:seon.schema.datahike/nilable-attribute` |
| `resources/seon/schemas/seon.schema.datahike.edn` | `:seon.schema.datahike/noncanonical-edn-error` | — | `:seon.schema.datahike/noncanonical-edn` |
| `resources/seon/schemas/seon.schema.datahike.edn` | `:seon.schema.datahike/schema-invalid-error` | — | `:seon.schema.datahike/schema-invalid` |
| `resources/seon/schemas/seon.schema.datahike.edn` | `:seon.schema.datahike/storage-not-string-error` | — | `:seon.schema.datahike/storage-not-string` |
| `resources/seon/schemas/seon.schema.datahike.edn` | `:seon.schema.datahike/value-type-unavailable-error` | `:seon.schema.datahike/value-type-unavailable` | — |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/cyclic-reference-error` | — | `:seon.schema/cyclic-reference` |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/duplicate-projection-row-error` | — | `:seon.schema/duplicate-projection-row` |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/incomplete-predicate-contract-error` | — | `:seon.schema/incomplete-predicate-contract` |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/invalid-identity-projection-error` | `:seon.schema/invalid-identity-projection` | — |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/invalid-schema-error` | — | `:seon.schema/invalid-schema` |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/malformed-artifact-export-error` | `:seon.schema/malformed-artifact-export` | — |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/malformed-projection-form-error` | `:seon.schema/malformed-projection-form` | — |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/malformed-projection-identity-error` | `:seon.schema/malformed-projection-identity` | — |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/malformed-projection-row-error` | `:seon.schema/malformed-projection-row` | — |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/missing-projection-error` | `:seon.schema/missing-projection` | — |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/nilable-map-value-error` | — | `:seon.schema/nilable-map-value` |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/nilable-return-error` | — | `:seon.schema/nilable-return` |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/nilable-value-schema-error` | — | `:seon.schema/nilable-value-schema` |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/non-round-tripping-form-error` | — | `:seon.schema/non-round-tripping-form` |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/noncanonical-definition-error` | — | `:seon.schema/noncanonical-definition` |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/noncanonical-projection-data-error` | `:seon.schema/noncanonical-projection-data` | — |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/render-contract-incoherent-error` | `:seon.schema/render-contract-incoherent` | — |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/schema-in-use-error` | — | `:seon.schema/schema-in-use` |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/single-segment-namespace-error` | — | `:seon.schema/single-segment-namespace` |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/undefined-contract-error` | — | `:seon.schema/undefined-contract` |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/unknown-shape-error` | — | `:seon.schema/unknown-shape` |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/unproved-predicate-purity-error` | — | `:seon.schema/unproved-predicate-purity` |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/unreadable-form-error` | — | `:seon.schema/unreadable-form` |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/unregister-outside-delta-error` | — | `:seon.schema/unregister-outside-delta` |
| `resources/seon/schemas/seon.schema.edn` | `:seon.schema/unresolved-predicate-error` | — | `:seon.schema/unresolved-predicate` |
| `resources/seon/schemas/seon.schema.edn.edn` | `:seon.schema.edn/dishonest-generator-error` | — | `:seon.schema.edn/dishonest-generator` |
| `resources/seon/schemas/seon.schema.edn.edn` | `:seon.schema.edn/duplicate-attribute-error` | — | `:seon.schema.edn/duplicate-attribute` |
| `resources/seon/schemas/seon.schema.edn.edn` | `:seon.schema.edn/misplaced-attribute-error` | — | `:seon.schema.edn/misplaced-attribute` |
| `resources/seon/schemas/seon.schema.edn.edn` | `:seon.schema.edn/not-a-map-error` | — | `:seon.schema.edn/not-a-map` |
| `resources/seon/schemas/seon.schema.edn.edn` | `:seon.schema.edn/unreadable-file-error` | — | `:seon.schema.edn/unreadable-file` |
| `resources/seon/schemas/seon.schema.edn.edn` | `:seon.schema.edn/unregistered-predicate-error` | — | `:seon.schema.edn/unregistered-predicate` |
| `resources/seon/schemas/seon.schema.edn.edn` | `:seon.schema.edn/unresolved-reference-error` | — | `:seon.schema.edn/unresolved-reference` |
| `resources/seon/schemas/seon.schema.edn.edn` | `:seon.schema.edn/unsafe-namespace-error` | — | `:seon.schema.edn/unsafe-namespace` |
| `resources/seon/schemas/seon.schema.shape.edn` | `:seon.schema.shape/fingerprint-collision-error` | `:seon.schema.shape/fingerprint-collision` | — |
| `resources/seon/schemas/seon.schema.shape.edn` | `:seon.schema.shape/noncanonical-compiled-form-error` | `:seon.schema.shape/noncanonical-compiled-form` | — |
| `resources/seon/schemas/seon.schema.shape.edn` | `:seon.schema.shape/unsupported-map-key-error` | `:seon.schema.shape/unsupported-map-key` | — |
| `resources/seon/schemas/seon.sci.admit.edn` | `:seon.sci.admit/projection-failed-error` | `:seon.sci.admit/projection-failed` | — |
| `resources/seon/schemas/seon.sci.eval.edn` | `:seon.sci.eval/acquisition-refused-error` | `:seon.sci.eval/acquisition-refused` | — |
| `resources/seon/schemas/seon.sci.eval.edn` | `:seon.sci.eval/documentation-unavailable-error` | — | `:seon.sci.eval/documentation-unavailable` |
| `resources/seon/schemas/seon.sci.eval.edn` | `:seon.sci.eval/evaluation-failed-error` | `:seon.sci.eval/evaluation-failed` | — |
| `resources/seon/schemas/seon.sci.eval.edn` | `:seon.sci.eval/install-mismatch-error` | `:seon.sci.eval/install-mismatch` | — |
| `resources/seon/schemas/seon.sci.eval.edn` | `:seon.sci.eval/missing-function-row-error` | — | `:seon.sci.eval/missing-function-row` |
| `resources/seon/schemas/seon.sci.eval.edn` | `:seon.sci.eval/namespace-binding-cycle-error` | `:seon.sci.eval/namespace-binding-cycle` | — |
| `resources/seon/schemas/seon.sci.eval.edn` | `:seon.sci.eval/namespace-unloadable-error` | `:seon.sci.eval/namespace-unloadable` | — |
| `resources/seon/schemas/seon.sci.eval.edn` | `:seon.sci.eval/reader-event-count-error` | — | `:seon.sci.eval/reader-event-count` |
| `resources/seon/schemas/seon.sci.eval.edn` | `:seon.sci.eval/schema-refused-error` | — | `:seon.sci.eval/schema-refused` |
| `resources/seon/schemas/seon.sci.eval.edn` | `:seon.sci.eval/time-limit-error` | — | `:seon.sci.eval/time-limit` |
| `resources/seon/schemas/seon.sci.kernel.edn` | `:seon.sci.kernel/already-armed-error` | `:seon.sci.kernel/already-armed` | — |
| `resources/seon/schemas/seon.sci.kernel.edn` | `:seon.sci.kernel/failure-admission-failed-error` | `:seon.sci.kernel/failure-admission-failed` | — |
| `resources/seon/schemas/seon.sci.kernel.edn` | `:seon.sci.kernel/invocation-failed-error` | — | `:seon.sci.kernel/invocation-failed` |
| `resources/seon/schemas/seon.sci.kernel.edn` | `:seon.sci.kernel/missing-function-installer-error` | — | `:seon.sci.kernel/missing-function-installer` |
| `resources/seon/schemas/seon.sci.kernel.edn` | `:seon.sci.kernel/missing-interrupt-guard-error` | `:seon.sci.kernel/missing-interrupt-guard` | — |
| `resources/seon/schemas/seon.sci.kernel.edn` | `:seon.sci.kernel/time-limit-error` | — | `:seon.sci.kernel/time-limit` |
| `resources/seon/schemas/seon.sci.kernel.edn` | `:seon.sci.kernel/unresolved-invocation-error` | — | `:seon.sci.kernel/unresolved-invocation` |
| `resources/seon/schemas/seon.sci.reader.edn` | `:seon.sci.reader/fabricated-response-error` | `:seon.sci.reader/fabricated-response` | — |
| `resources/seon/schemas/seon.sci.reader.edn` | `:seon.sci.reader/keyword-error` | `:seon.sci.reader/keyword` | — |
| `resources/seon/schemas/seon.sci.reader.edn` | `:seon.sci.reader/oversize-error` | `:seon.sci.reader/oversize` | — |
| `resources/seon/schemas/seon.sci.reader.edn` | `:seon.sci.reader/refused-tag-error` | — | `:seon.sci.reader/refused-tag` |
| `resources/seon/schemas/seon.sci.reader.edn` | `:seon.sci.reader/unreadable-error` | `:seon.sci.reader/unreadable` | — |
| `resources/seon/schemas/seon.search.edn` | `:seon.search/missing-resource-error` | `:seon.search/missing-resource` | — |
| `resources/seon/schemas/seon.search.edn` | `:seon.search/unavailable-error` | `:seon.search/unavailable` | — |
| `resources/seon/schemas/seon.test.accretion.edn` | `:seon.test.accretion/install-refused-error` | `:seon.test.accretion/install-refused` | `:seon.error/kind`, `:seon.test.accretion/arguments`, `:seon.test.accretion/expected`, `:seon.test.accretion/actual`, `:seon.fn/sym`, `:seon.test.accretion/orientation`, `:seon.test.accretion/test-count`, `:seon.test.accretion/test-pass-count`, `:seon.test.accretion/test-fail-count`, `:seon.test.accretion/failure-groups`, `:seon.test.accretion/advisories`, `:seon.test.accretion/auto-check`, `:seon.test.accretion/install?` |
| `resources/seon/schemas/seon.test.edn` | `:seon.test/not-runnable-error` | — | `:seon.test/not-runnable` |
| `resources/seon/schemas/seon.test.edn` | `:seon.test/unknown-error` | — | `:seon.test/unknown` |
| `resources/seon/schemas/seon.test.run.edn` | `:seon.test.run/immutable-error` | — | `:seon.test.run/immutable` |
| `resources/seon/schemas/seon.test.run.edn` | `:seon.test.run/unavailable-error` | `:seon.test.run/unavailable` | — |
| `resources/seon/schemas/seon.test.runner.edn` | `:seon.test.runner/default-cluster-refused-error` | — | `:seon.test.runner/default-cluster-refused` |
| `resources/seon/schemas/seon.test.runner.edn` | `:seon.test.runner/invalid-long-reason-error` | — | `:seon.test.runner/invalid-long-reason` |
| `resources/seon/schemas/seon.test.runner.edn` | `:seon.test.runner/invalid-marker-reason-error` | `:seon.test.runner/invalid-marker-reason` | — |
| `resources/seon/schemas/seon.test.runner.edn` | `:seon.test.runner/invalid-selection-mode-error` | — | `:seon.test.runner/invalid-selection-mode` |
| `resources/seon/schemas/seon.test.runner.edn` | `:seon.test.runner/invalid-silence-seconds-error` | — | `:seon.test.runner/invalid-silence-seconds` |
| `resources/seon/schemas/seon.test.runner.edn` | `:seon.test.runner/long-test-ns-hook-error` | — | `:seon.test.runner/long-test-ns-hook` |
| `resources/seon/schemas/seon.test.runner.edn` | `:seon.test.runner/process-tree-exit-backstop-error` | `:seon.test.runner/process-tree-exit-backstop` | — |
| `resources/seon/schemas/seon.test.runner.edn` | `:seon.test.runner/unknown-worker-command-error` | `:seon.test.runner/unknown-worker-command` | — |
| `resources/seon/schemas/seon.test.runner.edn` | `:seon.test.runner/unresolved-test-var-error` | `:seon.test.runner/unresolved-test-var` | — |
| `resources/seon/schemas/seon.test.runner.edn` | `:seon.test.runner/worker-launch-failure-error` | `:seon.test.runner/worker-launch-failure` | — |
| `resources/seon/schemas/seon.turn.edn` | `:seon.turn/missing-opening-datom-error` | — | `:seon.turn/missing-opening-datom` |
| `resources/seon/schemas/seon.turn.edn` | `:seon.turn/refused-error` | — | `:seon.turn/rule`, `:seon.turn/transition`, `:seon.turn/refused` |
| `resources/seon/schemas/seon.turn.loop.edn` | `:seon.turn.loop/lint-rejected-error` | `:seon.turn.loop/lint-rejected` | — |
| `resources/seon/schemas/seon.turn.loop.edn` | `:seon.turn.loop/phase-failed-error` | `:seon.turn.loop/phase-failed` | — |
| `resources/seon/schemas/seon.turn.loop.edn` | `:seon.turn.loop/prompt-failed-error` | — | `:seon.turn.loop/prompt-failed` |
| `resources/seon/schemas/seon.turn.loop.edn` | `:seon.turn.loop/terminal-refusal-settlement-refused-error` | — | `:seon.turn.loop/terminal-refusal-settlement-refused` |

Literal true declarations in audit B’s slice: `:seon.db/diff-refused`, `:seon.db/invalid-request`, `:seon.db/invalid-read`, `:seon.db/transaction-outcome-unknown`, `:seon.db/transaction-refused`, `:seon.dev.mcp/nil-deref`, `:seon.dev.mcp/jvm-exception`, `:seon.effect/already-recorded`, `:seon.effect/already-settled`, `:seon.effect/missing-receipt`, `:seon.env/agent-id-absent`, `:seon.env/absent-environment`, `:seon.env/invalid-environment-state`, `:seon.env/invalid-member`, `:seon.env/schema-absent`, `:seon.env/invalid-environment-replacement`, `:seon.env/unscopable-member`, `:seon.env/incomplete-environment`, `:seon.error/class`, `:seon.error/refusal`, `:seon.error/unclassified`, `:seon.eval.drive/absent`, `:seon.flow/configuration`, `:seon.flow/fault-channel-overflow`, `:seon.fn.binding/unsupported`, `:seon.fn/source-span-absent`, `:seon.fn/analysis-failed`, `:seon.fn/duplicate-program-identity`, `:seon.fn/index-refused`, `:seon.fn/signature-refused`, `:seon.fn/manifest-absent`, `:seon.instrument/registration-failed`, `:seon.message/no-limit`, `:seon.message/blank-content`.

## Recognition caller migration boundary

Original nine-copy census, superseded by D12: the instrument copy is deleted. The eight external owners below must dissolve their helpers into pure required-member checks at each declared boundary. No validator acquisition or message-only fallback belongs in recognition; generic callee output declarations are explicit step-6 dependencies.

| File | Private predicate | Required replacement |
|---|---|---|
| `src/seon/db.clj` | `error-value?` | Delete the private predicate. Inline the required-member checks for each callee’s declared facet/union, without acquiring a projection (D12); list generic callee contracts as step-6 sites. |
| `src/seon/operator.clj` | `error-value?` | Delete the private predicate. Inline the required-member checks for each callee’s declared facet/union, without acquiring a projection (D12); list generic callee contracts as step-6 sites. |
| `src/seon/plan.clj` | `error-value?` | Delete the private predicate. Inline the required-member checks for each callee’s declared facet/union, without acquiring a projection (D12); list generic callee contracts as step-6 sites. |
| `src/seon/call_preparation.clj` | `error-value?` | Delete the private predicate. Inline the required-member checks for each callee’s declared facet/union, without acquiring a projection (D12); list generic callee contracts as step-6 sites. |
| `src/seon/note.clj` | `error-value?` | Delete the private predicate. Inline the required-member checks for each callee’s declared facet/union, without acquiring a projection (D12); list generic callee contracts as step-6 sites. |
| `src/seon/cluster/message.clj` | `error-value?` | Delete the private predicate. Inline the required-member checks for each callee’s declared facet/union, without acquiring a projection (D12); list generic callee contracts as step-6 sites. |
| `src/seon/render/ns.clj` | `error-value?` | Delete the private predicate. Inline the required-member checks for each callee’s declared facet/union, without acquiring a projection (D12); list generic callee contracts as step-6 sites. |
| `src/seon/instrument.clj` | `flat-error-value?` | Delete the private predicate. Inline the required-member checks for each callee’s declared facet/union, without acquiring a projection (D12); list generic callee contracts as step-6 sites. |
| `src/seon/schedule.clj` | `flat-error?` | Delete the private predicate. Inline the required-member checks for each callee’s declared facet/union, without acquiring a projection (D12); list generic callee contracts as step-6 sites. |

Cause-chain preservation also needs `src/seon/error/refusal.clj`, `src/seon/sci/kernel.clj`, and the filesystem/shell/edit failure readers to recognize the same base rather than kind. Those are follow-up paths, outside this assignment’s owned files.

## D12 follow-up: remove recognition by general predicate

Owner D12 supersedes every earlier replacement instruction that calls
`seon.error/error?`. Do not introduce a replacement general predicate. At each
site below, inspect the callee's output contract and test the required members
of its declared error facet (or the base's at/layer/operation members where the
boundary passes arbitrary declared errors). Convert generic callee outputs
first. Non-owned sites are the mechanical follow-up lane's input; deletion of
the public Var must share a loadable publication with these callers.

The following is an exact dated source/test inventory from `rg -n`, including
private copies whose bodies currently delegate to the public Var.

```text
test/my/plan_test.clj:73:        (is (error/error? failure) (pr-str failure))
test/my/plan_test.clj:440:        (is (error/error? refusal) (pr-str refusal))
src/seon/note.clj:30:(defn- error-value?
src/seon/render/ns.clj:54:(defn- error-value?
test/seon/fn_test.clj:2017:         (is (error/error? result))
src/seon/call_preparation.clj:116:(defn- error-value?
test/seon/error_test.clj:164:      (is (true? (error/error? {:my.fs/not-found "tmp/absent"
test/seon/error_test.clj:166:      (is (false? (error/error? {:seon.error/message "Only a message."})))
test/seon/error_test.clj:167:      (is (false? (error/error? :not-a-map))))))
test/seon/error_test.clj:171:    (is (true? (error/error? {:seon.error/message "Reader refusal."})))
test/seon/error_test.clj:172:    (is (false? (error/error? {})))
test/seon/error_test.clj:173:    (is (false? (error/error? "Reader refusal.")))))
test/seon/error_test.clj:307:        (is (true? (error/error? value))
src/seon/cluster.clj:1101:    (if (error/error? read-result)
src/seon/cluster.clj:1540:    (if (error/error? schema-read)
src/seon/cluster.clj:1546:        (if (error/error? symbol-read)
src/seon/cluster.clj:1594:    (when (error/error? missing)
src/seon/cluster.clj:1673:            (when (error/error? process-rows)
src/seon/cluster.clj:2685:        (if (error/error? open-runs)
src/seon/cluster.clj:3485:           _ (when (error/error? recovery)
src/seon/plan.clj:85:(defn- error-value?
src/seon/plan.clj:87:  (error/error? value))
src/seon/plan.clj:108:  (if (error/error? result)
src/seon/plan.clj:151:    (if (error/error? entity) entity (:db/id entity))))
src/seon/plan.clj:178:      (error/error? subject) (read-result! subject)
test/seon/ai_test.clj:40:(defn- error? [value]
test/seon/cluster_test.clj:26:        (is (error/error? refusal) (pr-str refusal))
test/seon/cluster_test.clj:41:        (is (error/error? refusal) (pr-str refusal))
test/seon/cluster_test.clj:57:        (is (error/error? refusal) (pr-str refusal))
test/seon/cluster_test.clj:417:        (is (error/error? (:seon.boot/result offense))
test/seon/turn_test.clj:908:        (is (error/error? refusal) (pr-str refusal))
test/seon/turn_test.clj:926:        (is (error/error? refusal) (pr-str refusal))
test/seon/turn_test.clj:954:        (is (error/error? refusal) (pr-str refusal))
src/seon/db.clj:188:(defn- error-value?
test/seon/cluster/reply_test.clj:43:(defn- error? [value]
src/seon/instrument.clj:96:(defn- flat-error-value?
src/seon/turn.clj:334:      (error/error? turn)
src/seon/turn.clj:1131:    (if (error/error? history)
src/seon/turn.clj:1143:        (if (error/error? receipt)
src/seon/turn.clj:1161:      (when (error/error? opening-database)
src/seon/turn.clj:1166:        (if (error/error? opening-existing)
src/seon/turn.clj:1174:              (if (error/error? written?)
src/seon/turn.clj:2669:    (or (some #(when (error/error? %) %) [issue-t replies closed])
src/seon/turn.clj:2684:    (if (error/error? issue-budget)
src/seon/turn.clj:2700:    (or (some #(when (error/error? %) %) [limit spent])
src/seon/turn.clj:2722:      (error/error? limit) limit
src/seon/turn.clj:2726:        (if (error/error? declarations-refusal)
src/seon/turn.clj:2730:              (error/error? spent) spent
src/seon/turn.clj:2738:                    (if (error/error? since)
src/seon/turn.clj:2752:                (if (error/error? failed-attempt)
src/seon/turn.clj:2870:        (if (error/error? remaining)
src/seon/turn.clj:2883:        (if (error/error? deferred)
src/seon/fn.clj:1387:              refusal (some #(when (error/error? %) %) [calls references subjects])]
src/seon/fn.clj:1417:        refusal (some #(when (error/error? %) %)
src/seon/fn.clj:1434:                    (if (error/error? selected)
src/seon/fn.clj:1497:    (if (error/error? result) result (get result function-symbol))))
src/seon/fn.clj:2693:                          (if (error/error? normalized)
src/seon/fn.clj:2698:              (if (error/error? row)
src/seon/fn.clj:2710:                       (if (error/error? normalized)
src/seon/fn.clj:2744:          (if (error/error? current)
src/seon/fn.clj:2752:                  refusal (some #(when (error/error? %) %)
src/seon/fn.clj:2807:              (if (error/error? pulled)
src/seon/fn.clj:2811:            (if (error/error? entity)
src/seon/fn.clj:2827:                                   (if (error/error? portable-member)
src/seon/fn.clj:2838:                     (if (error/error? portable)
src/seon/fn.clj:2851:          (if (error/error? entity-ids)
src/seon/fn.clj:2857:                        (if (error/error? portable)
src/seon/fn.clj:2861:              (if (error/error? attribute-rows)
src/seon/fn.clj:2891:     (if (error/error? rows)
src/seon/fn.clj:2935:                              (if (error/error? tx-data)
src/seon/operator.clj:54:(defn- error-value?
src/seon/error.clj:1785:(defn error?
src/seon/cluster/message.clj:486:(defn- error-value?
src/seon/test.clj:556:  (if (error/error? result)
src/seon/test.clj:809:      (if (error/error? (ex-data failure)) (ex-data failure) (throw failure)))))
src/seon/test.clj:826:           (cond (error/error? members) (reduced members)
src/seon/test.clj:837:             (cond (error/error? known) (reduced known)
src/seon/test.clj:853:    (if (error/error? seeds) seeds
src/seon/test.clj:863:    (if (error/error? selected) selected
src/seon/test.clj:865:        (if (error/error? provenance) provenance
src/seon/test.clj:911:    (when (error/error? ids)
src/seon/test.clj:915:             (when (or (error/error? row)
src/seon/test.clj:982:      (when (error/error? read-result)
src/seon/test.clj:992:    (when (or (error/error? digest)
src/seon/test.clj:1037:            _ (when (error/error? runs)
src/seon/test.clj:1043:                                  _ (when (error/error? candidate)
src/seon/test.clj:1215:        tests (when (and (not (error/error? file-symbols))
src/seon/test.clj:1220:      (error/error? file-symbols) file-symbols
src/seon/test.clj:1221:      (error/error? tests) tests
src/seon/test.clj:1242:        effective (when-not (error/error? selection) (config/effective database cluster))
src/seon/test.clj:1246:        selected (if (error/error? selection) selection
src/seon/test.clj:1248:        deferred (when-not (error/error? selected)
src/seon/test.clj:1268:        destructive (when-not (error/error? selected)
src/seon/test.clj:1284:        runnable (if (or (error/error? selected) (error/error? destructive)
src/seon/test.clj:1291:        admitted (when (and (not (error/error? selected))
src/seon/test.clj:1292:                            (not (error/error? destructive))
src/seon/test.clj:1293:                            (not (error/error? effective)))
src/seon/test.clj:1296:      (error/error? effective) effective
src/seon/test.clj:1297:      (error/error? admitted) admitted
src/seon/test.clj:1298:      (error/error? provenance) provenance
src/seon/test.clj:1299:      (error/error? selected) selected
src/seon/test.clj:1300:      (error/error? destructive) (assoc destructive :seon.test/next-tier :none)
src/seon/test.clj:1457:      (error/error? custody) custody
test/seon/sci/reader_test.clj:36:(defn- error?
```

Predicate inventory: **104 matching lines in 21 files**.

### Step-6 generic result declarations and consumers

Each generic schema reference below needs inspection in its enclosing contract:
replace result-position references by the exact possible facets, retaining
input schemas only when they honestly describe the boundary's inputs. A map
that happens to have a message is not evidence of a declared error.

```text
src/seon/sci/kernel.clj:541:    [:or :seon.error/value :seon.error/base
src/seon/schema.clj:2613:    :seon.error/value]}
src/seon/schema.clj:2794:    :seon.error/value]}
src/seon/test/runner.clj:2212:                  [:or :seon.test/reach-digests :seon.error/value]]}
src/seon/test/runner.clj:2221:                  [:or :seon.test/reaches :seon.error/value]]}
src/seon/test/runner.clj:2234:                  [:or :seon.test.run/program-digest :seon.error/value]]}
src/seon/test/runner.clj:2280:                  [:or :seon.test.run/provenance :seon.error/value]]}
src/seon/test/runner.clj:2293:                  [:or :seon.test.run/admission :seon.error/value
src/seon/test/runner.clj:2978:    [:or :seon.test/results :seon.error/value]]}
src/seon/test/runner.clj:3048:                  [:or :seon.test/results :seon.error/value]]}
src/seon/test/runner.clj:3266:  {:malli/schema [:=> [:cat :seon.boot/cluster-name] :seon.error/value]}
src/seon/test.clj:59:                   :seon.error/value]]}
src/seon/test.clj:107:                  [:or :seon.test/class-loader :seon.error/value]]}
src/seon/test.clj:236:                  [:or [:map-of :seon.fn/sym :seon.fn/destroys] :seon.error/value]]}
src/seon/test.clj:324:                  [:or :seon.test/host-report :seon.error/value]]}
src/seon/test.clj:416:    [:=> [:cat :seon.test/var :seon.db/connection] [:or :seon.test/result :seon.error/value]]
src/seon/test.clj:419:     [:or :seon.test/result :seon.error/value]]]}
src/seon/test.clj:507:                  [:or :seon.test/result :seon.error/value]]}
src/seon/test.clj:538:                  :seon.error/value]}
src/seon/test.clj:620:                  [:or :seon.test.selection/result :seon.error/value
src/seon/test.clj:814:                  [:or [:set :qualified-symbol] :seon.error/value
src/seon/test.clj:849:                  [:or [:vector :seon.test/sym] :seon.error/value
src/seon/test.clj:859:                  [:or :seon.test.run/admission :seon.error/value
src/seon/test.clj:878:                  [:or :seon.test.run/admission :seon.error/value
src/seon/test.clj:1060:                  [:or :seon.test/reach-digest :seon.error/value]]}
src/seon/test.clj:1095:     [:or [:vector :seon.test/sym] :seon.error/value]]
src/seon/test.clj:1097:     [:or [:vector :seon.test/sym] :seon.error/value]]]}
src/seon/test.clj:1117:                  [:or :seon.test/var :seon.error/value]]}
src/seon/test.clj:1204:                  [:or :seon.test.run/admission :seon.error/value
src/seon/test.clj:1449:                  [:or :seon.test/check-result :seon.error/value
src/seon/test.clj:1501:                  [:or :seon.test/check-result :seon.error/value
src/seon/test.clj:1529:  {:malli/schema [:=> [:cat [:or :seon.test/check-result :seon.error/value]] :string]}
src/seon/test.clj:1667:    [:=> [:cat :seon.db/database-value :seon.test/sym] [:or :boolean :seon.error/value]]
src/seon/test.clj:1669:     [:or :boolean :seon.error/value]]]}
src/seon/db.clj:236:   [:=> [:cat [:or :seon.db/database-value :seon.error/value]
src/seon/db.clj:238:    [:or :seon.db/database-value :seon.error/value]]}
src/seon/db.clj:323:   [:=> [:cat [:or :seon.db/connection :seon.error/value]]
src/seon/db.clj:324:    [:or :seon.db/connection-identity :seon.error/value]]}
src/seon/db.clj:392:   [:=> [:cat [:or :seon.db/database-value :seon.error/value]]
src/seon/db.clj:393:    [:or :seon.db/database-value-identity :seon.error/value]]}
src/seon/db.clj:418:   [:=> [:cat [:or :seon.db/database-value :seon.error/value]]
src/seon/db.clj:419:    [:or :int :seon.error/value]]}
src/seon/db.clj:1059:  {:malli/schema [:=> [:cat [:or :seon.db/database-value :seon.error/value]
src/seon/db.clj:1152:  {:malli/schema [:=> [:cat :qualified-symbol] :seon.error/value]}
src/seon/db.clj:1735:     [:or :seon.db/database-value :seon.error/value]]
src/seon/db.clj:1736:    [:=> [:cat [:or :seon.db/connection :seon.error/value]]
src/seon/db.clj:1737:     [:or :seon.db/database-value :seon.error/value]]]}
src/seon/db.clj:1773:    [:or :seon.db/database-value :seon.error/value]]}
src/seon/db.clj:1796:    [:or :seon.db/connection :seon.error/value]]}
src/seon/db.clj:1925:   [:=> [:catn [:seon.db/query-or-database [:or :seon.db/database-value :seon.error/value :seon.db/query :seon.db/query-args]] [:seon.db/arguments [:* {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Datahike Datalog bindings carry arbitrary values. The function guard derives input count and database source positions from the parsed query.", :gen/elements [[]]} :seon.schema/value]]] [:or :seon.schema/value :seon.db/error-result] [:fn #:error{:message "The supplied arguments must match the query's :in (default [$]); every source input must be a database value. Use (seon.db/q query input ...) with $ elided, or (seon.db/q database query input ...) with the database first.", :fn seon.db/query-guard-message} seon.db/query-call-valid?]]}
src/seon/db.clj:2095:    [:or :nil :seon.schema/registry-key :seon.error/value]]}
src/seon/db.clj:2336:          [:or :seon.db/database-value :seon.error/value
src/seon/db.clj:2342:     [:cat [:or :seon.db/database-value :seon.error/value]
src/seon/db.clj:2386:      [:or :seon.db/database-value :seon.error/value
src/seon/db.clj:2393:     [:cat [:or :seon.db/database-value :seon.error/value]
src/seon/db.clj:2445:    [:=> [:cat [:or :seon.db/database-value :seon.error/value]
src/seon/db.clj:2538:   [:=> [:cat [:or :seon.db/database-value :seon.error/value :seon.db/index-lookup :keyword] [:* {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Datahike index components include arbitrary attribute values. The function guard checks index, component count and argument-map exclusivity.", :gen/elements [[]]} :seon.schema/value]] [:or :seon.db/datoms :seon.db/error-result] [:fn #:error{:message "Use (seon.db/datoms index & components) or (seon.db/datoms database index & components); an index argument map takes no trailing arguments, and an index has at most four components."} seon.db/datoms-call-valid?]]}
src/seon/db.clj:2552:    [:=> [:catn [::database [:or ::database-value :seon.error/value]]
src/seon/db.clj:2619:    [:=> [:cat [:or :seon.db/database-value :seon.error/value]]
src/seon/db.clj:2632:    [:=> [:cat [:or :seon.db/database-value :seon.error/value]]
src/seon/db.clj:2649:    [:=> [:cat [:or :seon.db/database-value :seon.error/value]]
src/seon/db.clj:2662:    [:=> [:cat [:or :seon.db/database-value :seon.error/value]
src/seon/db.clj:2676:    [:=> [:cat [:or :seon.db/database-value :seon.error/value]
src/seon/db.clj:3821:                  [:maybe :seon.error/value]]}
src/seon/db.clj:4082:   [:=> [:cat [:or :seon.db/connection :seon.error/value]
src/seon/db.clj:4389:    [:=> [:cat [:or :seon.db/connection :seon.error/value] :seon.store/transaction]
```

### Retirement publication prerequisites (D12 continuation)

The public predicate has 75 production call sites in the current shared-tree
bytes: `src/seon/fn.clj` (18), `src/seon/turn.clj` (16),
`src/seon/cluster.clj` (7), `src/seon/plan.clj` (4), and
`src/seon/test.clj` (30). These are outside lane 1a's owned paths. At the
2026-09-19 continuation check, only `src/seon/test.clj` in that list was dirty;
the other four are outside scope, not described as held. Convert these calls
before deleting the public Var in a loadable publication.

The nine private recognition owners are `src/seon/db.clj:188`,
`src/seon/operator.clj:54`, `src/seon/plan.clj:85`,
`src/seon/call_preparation.clj:116`, `src/seon/note.clj:30`,
`src/seon/cluster/message.clj:486`, `src/seon/render/ns.clj:54`,
`src/seon/instrument.clj:96`, and `src/seon/schedule.clj:491`.
`flat-error?` in schedule is included here in addition to the search above.

Six required schema members outside ownership still name the definition
`:seon.error/kind`: `seon.problems.edn:13,26,120`, `seon.eval.drive.edn:48`,
`seon.test.accretion.edn:107`, `seon.maintenance.result.edn:154` (all under
`resources/seon/schemas/`). Remove those required members and convert their
readers before removing the shared attribute definition. This is a schema
reference dependency, not permission to retain kind as the target design.

Changing `seon.error/diagnostic` to produce base observations also requires
the callers' output contracts to name their promised facets: the armed wrapper
correctly refuses base observations through a legacy `:seon.error/value`
output. Merely retaining the old kind alongside the base cannot repair that
contract mismatch. The source inventory above owns those replacement sites.

Accuracy boundaries outside ownership: D4's cause-chain recognition is
`src/seon/error/refusal.clj` plus `src/seon/sci/kernel.clj:572`; D5 covers the
listed database-consumer output contracts; D6's reporter exception evidence
belongs to `src/seon/test/runner.clj`. The additional SCI arity-parity defect
is the copy at acquisition in `src/seon/sci/eval.clj`, per that note's final
addendum. Their generic outputs must be converted together with their
recognition sites.

## Measured marker consumer dependency — 1a continuation

`src/seon/sci/eval.clj:882` and `:1867` read
`:seon.instrument/registration-failed` in exception data. After the owned
stamp deletion, the canonical `a-sovereign-sci-fork-acquires-its-own-recorder`
regression falsifies the guarantee that base construction refuses an unarmed
SCI definition: the catch permits fallback instead. These are actual marker
reads, beyond the kind/class-property search above. Replace both with required
members of the producer's declared `:seon.instrument/registration-error`:
`:seon.error/at`, `:seon.error/layer`, `:seon.error/operation`,
`:seon.instrument/fn`, `:seon.instrument/registration-observation`. Use pure
value checks; no general predicate or projection acquisition. The owned
`wrap-interpreted` failure constructor now supplies the complete facet.
This file is outside 1a ownership and was not edited.

## Measured output-contract dependency — 1a continuation

`src/seon/schema.clj:1069`, `call-with-projection-state`, returns its callback's
value with a polymorphic `:any` declaration but no declared error facets.
After `instrument/apply!` returns a complete registration observation, the
armed wrapper correctly refuses this pass-through:

```text
seon.schema/call-with-projection-state returned undeclared error facets #{:seon.instrument/registration-error}.
```

This was observed by
`seon.instrument-test/applying-without-a-handed-projection-refuses-before-collection`.
The bootstrap facet validators now work without a program shape catalog; the
new refusal identifies the next actual contract owner. The follow-up must
declare the callback result's allowed error union at this polymorphic
continuation boundary. Do not add an output-validation bypass or a general
error predicate. `schema.clj` remains read-only for 1a.

### Continuation census

The same `rg --json` search against shared working bytes after `a7f013251`
reports **2,348 matching lines / 2,384 matches / 322 files** (this raw search
excludes the separately enumerated short namespaced `:kind` and `:class`
definitions): src **979/997/85**, test **961/967/163**, resources
**357/368/68**, script **47/48/5**, bin **4/4/1**, config **0/0/0**.
The original complete per-file/per-line inventory above remains the dated
conversion input; removed owned declarations and the new measured consumers
are specified in the continuation sections. No foreign file was edited.

## D12/D13 retirement handoff (d549c42a6, 2026-09-19)

This dated census supersedes owned-path pending statuses above. Current raw
kind/class search: **2240 matching lines / 2266 literal matches / 317 files**.
Owned source/resources/tests have zero matches. `seon.error/error?` is deleted,
not retained as a compatibility predicate. Its remaining **75 production calls on 74
lines across five files** follow. Ten additional test calls are listed below.
`src/seon/test.clj` is held by test-selector-a1;
all external files stay read-only for 1a.

For each any-error branch below, use the value-only expression
`(let [observation VALUE] (and (map? observation) (inst? (:seon.error/at observation)) (qualified-keyword? (:seon.error/layer observation)) (qualified-symbol? (:seon.error/operation observation))))`.
VALUE is the existing call argument shown at each site. No projection lookup,
fetch or replacement general predicate. A domain-specific branch instead tests
that boundary's declared facet members. Before this mechanical conversion,
replace the callee's generic `:seon.error/value` output with its exact facet
union; when that declaration is unavailable, return a complete typed boundary
refusal naming the callee and unavailable output declaration, not a guessed
classification. These are step-6 dependencies, not permission to treat the
old kind-only values as valid base observations.

| Exact site | Existing expression; replacement target |
|---|---|
| `src/seon/plan.clj:87` | `(error/error? value))` → Delete this private predicate; inline each caller’s declared error members. |
| `src/seon/plan.clj:108` | `(if (error/error? result)` → Inline declared base members on each existing argument. |
| `src/seon/plan.clj:151` | `(if (error/error? entity) entity (:db/id entity))))` → Inline declared base members on each existing argument. |
| `src/seon/plan.clj:178` | `(error/error? subject) (read-result! subject)` → Inline declared base members on each existing argument. |
| `src/seon/cluster.clj:1101` | `(if (error/error? read-result)` → Inline declared base members on each existing argument. |
| `src/seon/cluster.clj:1540` | `(if (error/error? schema-read)` → Inline declared base members on each existing argument. |
| `src/seon/cluster.clj:1546` | `(if (error/error? symbol-read)` → Inline declared base members on each existing argument. |
| `src/seon/cluster.clj:1594` | `(when (error/error? missing)` → Inline declared base members on each existing argument. |
| `src/seon/cluster.clj:1673` | `(when (error/error? process-rows)` → Inline declared base members on each existing argument. |
| `src/seon/cluster.clj:2685` | `(if (error/error? open-runs)` → Inline declared base members on each existing argument. |
| `src/seon/cluster.clj:3485` | `_ (when (error/error? recovery)` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:557` | `(if (error/error? result)` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:910` | `(if (error/error? (ex-data failure)) (ex-data failure) (throw failure)))))` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:927` | `(cond (error/error? members) (reduced members)` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:938` | `(cond (error/error? known) (reduced known)` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:954` | `(if (error/error? seeds) seeds` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:964` | `(if (error/error? selected) selected` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:966` | `(if (error/error? provenance) provenance` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1012` | `(when (error/error? ids)` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1016` | `(when (or (error/error? row)` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1083` | `(when (error/error? read-result)` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1093` | `(when (or (error/error? digest)` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1138` | `_ (when (error/error? runs)` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1144` | `_ (when (error/error? candidate)` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1316` | `seeds (when-not (error/error? file-symbols)` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1318` | `(if (error/error? file-symbols) file-symbols` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1337` | `effective (when-not (error/error? selection) (config/effective database cluster))` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1341` | `selected (if (error/error? selection) selection` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1343` | `deferred (when-not (error/error? selected)` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1363` | `destructive (when-not (error/error? selected)` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1379` | `runnable (if (or (error/error? selected) (error/error? destructive)` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1386` | `admitted (when (and (not (error/error? selected))` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1387` | `(not (error/error? destructive))` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1388` | `(not (error/error? effective)))` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1391` | `(error/error? effective) effective` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1392` | `(error/error? admitted) admitted` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1393` | `(error/error? provenance) provenance` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1394` | `(error/error? selected) selected` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1395` | `(error/error? destructive) (assoc destructive :seon.test/next-tier :none)` → Inline declared base members on each existing argument. |
| `src/seon/test.clj:1552` | `(error/error? custody) custody` → Inline declared base members on each existing argument. |
| `src/seon/turn.clj:334` | `(error/error? turn)` → Inline declared base members on each existing argument. |
| `src/seon/turn.clj:1131` | `(if (error/error? history)` → Inline declared base members on each existing argument. |
| `src/seon/turn.clj:1143` | `(if (error/error? receipt)` → Inline declared base members on each existing argument. |
| `src/seon/turn.clj:1161` | `(when (error/error? opening-database)` → Inline declared base members on each existing argument. |
| `src/seon/turn.clj:1166` | `(if (error/error? opening-existing)` → Inline declared base members on each existing argument. |
| `src/seon/turn.clj:1174` | `(if (error/error? written?)` → Inline declared base members on each existing argument. |
| `src/seon/turn.clj:2669` | `(or (some #(when (error/error? %) %) [issue-t replies closed])` → Inline declared base members on each existing argument. |
| `src/seon/turn.clj:2684` | `(if (error/error? issue-budget)` → Inline declared base members on each existing argument. |
| `src/seon/turn.clj:2700` | `(or (some #(when (error/error? %) %) [limit spent])` → Inline declared base members on each existing argument. |
| `src/seon/turn.clj:2722` | `(error/error? limit) limit` → Inline declared base members on each existing argument. |
| `src/seon/turn.clj:2726` | `(if (error/error? declarations-refusal)` → Inline declared base members on each existing argument. |
| `src/seon/turn.clj:2730` | `(error/error? spent) spent` → Inline declared base members on each existing argument. |
| `src/seon/turn.clj:2738` | `(if (error/error? since)` → Inline declared base members on each existing argument. |
| `src/seon/turn.clj:2752` | `(if (error/error? failed-attempt)` → Inline declared base members on each existing argument. |
| `src/seon/turn.clj:2870` | `(if (error/error? remaining)` → Inline declared base members on each existing argument. |
| `src/seon/turn.clj:2883` | `(if (error/error? deferred)` → Inline declared base members on each existing argument. |
| `src/seon/fn.clj:1387` | `refusal (some #(when (error/error? %) %) [calls references subjects])]` → Inline declared base members on each existing argument. |
| `src/seon/fn.clj:1417` | `refusal (some #(when (error/error? %) %)` → Inline declared base members on each existing argument. |
| `src/seon/fn.clj:1434` | `(if (error/error? selected)` → Inline declared base members on each existing argument. |
| `src/seon/fn.clj:1497` | `(if (error/error? result) result (get result function-symbol))))` → Inline declared base members on each existing argument. |
| `src/seon/fn.clj:2693` | `(if (error/error? normalized)` → Inline declared base members on each existing argument. |
| `src/seon/fn.clj:2698` | `(if (error/error? row)` → Inline declared base members on each existing argument. |
| `src/seon/fn.clj:2710` | `(if (error/error? normalized)` → Inline declared base members on each existing argument. |
| `src/seon/fn.clj:2744` | `(if (error/error? current)` → Inline declared base members on each existing argument. |
| `src/seon/fn.clj:2752` | `refusal (some #(when (error/error? %) %)` → Inline declared base members on each existing argument. |
| `src/seon/fn.clj:2807` | `(if (error/error? pulled)` → Inline declared base members on each existing argument. |
| `src/seon/fn.clj:2811` | `(if (error/error? entity)` → Inline declared base members on each existing argument. |
| `src/seon/fn.clj:2827` | `(if (error/error? portable-member)` → Inline declared base members on each existing argument. |
| `src/seon/fn.clj:2838` | `(if (error/error? portable)` → Inline declared base members on each existing argument. |
| `src/seon/fn.clj:2851` | `(if (error/error? entity-ids)` → Inline declared base members on each existing argument. |
| `src/seon/fn.clj:2857` | `(if (error/error? portable)` → Inline declared base members on each existing argument. |
| `src/seon/fn.clj:2861` | `(if (error/error? attribute-rows)` → Inline declared base members on each existing argument. |
| `src/seon/fn.clj:2891` | `(if (error/error? rows)` → Inline declared base members on each existing argument. |
| `src/seon/fn.clj:2935` | `(if (error/error? tx-data)` → Inline declared base members on each existing argument. |

External test assertions also retire the public predicate. Each site must
assert the complete declared error schema using the canonical fixture's handed
projection, or its required members when the boundary handles the base:

- `test/my/plan_test.clj:73` — assert the plan writer's declared failure schema.
- `test/my/plan_test.clj:440` — assert the plan writer's declared refusal schema.
- `test/seon/cluster_test.clj:31` — assert the boot boundary's declared refusal schema.
- `test/seon/cluster_test.clj:46` — assert the boot boundary's declared refusal schema.
- `test/seon/cluster_test.clj:62` — assert the boot boundary's declared refusal schema.
- `test/seon/cluster_test.clj:422` — assert the schema of the `:seon.boot/result` observation.
- `test/seon/turn_test.clj:908` — assert the turn boundary's declared refusal schema.
- `test/seon/turn_test.clj:926` — assert the turn boundary's declared refusal schema.
- `test/seon/turn_test.clj:954` — assert the turn boundary's declared refusal schema.
- `test/seon/fn_test.clj:2017` — assert the indexing boundary's declared error schema.

The six external required kind members must be removed and replaced by the
base's required at/layer/operation members (or by an explicit `:and` with
`:seon.error/base`), preserving each enclosing boundary's additional required
evidence. This is the exact current list:

- `resources/seon/schemas/seon.problems.edn:13` — replace the required kind member with the base requirements; declare the enclosing boundary’s actual facet union.
- `resources/seon/schemas/seon.problems.edn:26` — replace the required kind member with the base requirements; declare the enclosing boundary’s actual facet union.
- `resources/seon/schemas/seon.problems.edn:120` — replace the required kind member with the base requirements; declare the enclosing boundary’s actual facet union.
- `resources/seon/schemas/seon.eval.drive.edn:48` — replace the required kind member with the base requirements; declare the enclosing boundary’s actual facet union.
- `resources/seon/schemas/seon.maintenance.result.edn:154` — replace the required kind member with the base requirements; declare the enclosing boundary’s actual facet union.
- `resources/seon/schemas/seon.test.accretion.edn:107` — replace the required kind member with the base requirements; declare the enclosing boundary’s actual facet union.

### Constructor inputs changed in the owned cut

`diagnostic` now constructs exactly `:seon.error/base`: callers supply real
`:seon.error/at`, qualified `:seon.error/layer`, and qualified-symbol
`:seon.error/operation`. The diagnostic-operation field remains evidence and
cannot substitute for these required members. Owners then add their facet's
required evidence AFTER base construction and declare that facet in their
outputs. Concrete facet evidence left only on the diagnostic constructor
request would be dropped: move it to the owner's composed return value. The following
external call lines each need those three inputs; their old kind argument is
deleted, never copied into a replacement tag:

- `src/my/background.clj:15` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/background.clj:16` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/background.clj:17` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/background.clj:18` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/config.clj:431` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:25` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:29` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:30` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:31` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:32` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:33` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:34` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:35` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:57` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:61` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:62` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:63` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:64` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:65` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:66` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:67` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:358` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:362` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:363` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:364` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:365` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:366` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:367` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:368` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:386` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:391` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:392` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:393` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:394` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:395` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:396` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:397` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:452` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:576` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:580` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:581` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:582` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:583` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:584` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:585` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/my/program.clj:586` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/flow.clj:738` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/flow.clj:739` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/flow.clj:740` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/flow.clj:741` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/flow.clj:742` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/flow.clj:743` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/flow.clj:1083` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/flow.clj:1086` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/flow.clj:1087` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/flow.clj:1088` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/flow.clj:1089` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/flow.clj:1090` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/flow.clj:1091` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/flow.clj:1092` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/my/examples_test.clj:140` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/my/program_mutation_test.clj:73` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/eval.clj:1216` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/eval.clj:1219` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/eval.clj:1220` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/eval.clj:1222` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/eval.clj:1223` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/eval.clj:1224` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/eval.clj:1225` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/eval.clj:1226` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/eval.clj:1580` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/eval.clj:1585` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/eval.clj:1586` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/eval.clj:1587` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/eval.clj:1588` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/eval.clj:1589` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/eval.clj:1590` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/eval.clj:1592` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/eval.clj:1594` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/render_source_test.clj:201` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/effect.clj:483` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/effect.clj:484` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/effect.clj:485` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/effect.clj:488` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/effect.clj:489` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/effect.clj:490` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/admit.clj:693` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/admit.clj:719` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/kernel.clj:583` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/kernel.clj:597` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/kernel.clj:598` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/kernel.clj:599` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/kernel.clj:600` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/kernel.clj:601` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/kernel.clj:603` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/sci/kernel.clj:605` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test_support_test.clj:364` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/eval.clj:35` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/eval.clj:38` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/eval.clj:39` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/eval.clj:40` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/eval.clj:41` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/eval.clj:42` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/eval.clj:43` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/eval.clj:44` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2060` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2063` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2064` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2065` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2066` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2067` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2068` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2069` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2088` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2091` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2092` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2093` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2094` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2095` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2096` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2097` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2267` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2270` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2271` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2272` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2273` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2274` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2275` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:2276` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:4952` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:4971` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:4972` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:4973` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:4974` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:4975` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:4976` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:4977` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:5070` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:5082` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:5083` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:5084` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:5085` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:5086` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:5087` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/turn.clj:5089` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:137` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:141` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:142` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:143` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:144` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:146` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:147` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:148` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:916` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:923` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:924` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:926` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:927` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:928` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:930` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:931` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:1313` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:1314` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:1315` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:1316` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:1318` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:1320` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:1321` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:1424` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:1428` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:1429` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:1430` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:1432` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:1433` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:1434` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render.clj:1435` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2312` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2315` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2316` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2317` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2318` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2319` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2320` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2321` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2764` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2767` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2768` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2769` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2770` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2771` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2772` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2773` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2949` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2952` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2953` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2954` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2955` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2956` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2957` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:2958` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:3274` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:3280` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:3281` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:3282` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:3283` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:3286` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:3287` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/runner.clj:3288` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/await_test.clj:8` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/await_test.clj:9` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/await_test.clj:10` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/await_test.clj:11` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/await_test.clj:12` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/await_test.clj:13` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/await_test.clj:49` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/await_test.clj:53` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/await_test.clj:74` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/await_test.clj:78` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/await_test.clj:91` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/await.clj:34` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/await.clj:35` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/await.clj:40` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/await.clj:41` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/await.clj:49` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/await.clj:50` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/render_coverage_test.clj:148` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test/accretion.clj:108` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster_test.clj:163` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster_test.clj:173` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster_test.clj:355` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster_test.clj:368` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster_test.clj:394` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test/runner_test.clj:45` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test/runner_test.clj:48` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test/runner_test.clj:49` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/operator_test.clj:204` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/fn.clj:158` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/fn.clj:177` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/fn.clj:180` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/fn.clj:202` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/fn.clj:215` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/fn.clj:971` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/fn.clj:977` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/fn.clj:978` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/fn.clj:979` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/fn.clj:980` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/fn.clj:981` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/fn.clj:982` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/fn.clj:983` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:698` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:702` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:703` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:704` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:705` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:707` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:709` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:710` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:1014` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:1017` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:1018` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:1019` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:1020` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:1021` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:1022` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:1023` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:2373` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:2376` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:2377` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:2378` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:2379` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:2380` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:2381` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/transcript.clj:2382` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test/selection_test.clj:133` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test/selection_test.clj:136` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test/selection_test.clj:137` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test/selection_test.clj:138` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test/selection_test.clj:139` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test/selection_test.clj:140` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test/selection_test.clj:141` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test/selection_test.clj:142` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/lint.clj:221` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/lint.clj:225` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/lint.clj:226` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/lint.clj:227` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/lint.clj:228` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/lint.clj:229` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/lint.clj:230` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/lint.clj:231` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/render/web_test.clj:107` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/render/web_test.clj:2202` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/render/web_test.clj:2205` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/render/web_test.clj:2206` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/render/web_test.clj:2207` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/render/web_test.clj:2208` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/render/web_test.clj:2209` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/render/web_test.clj:2210` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/render/web_test.clj:2211` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/web.clj:696` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/web.clj:700` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/web.clj:701` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/web.clj:702` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/web.clj:703` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/web.clj:704` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/web.clj:705` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/web.clj:706` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/web.clj:2668` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/web.clj:2669` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/web.clj:2670` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/web.clj:2671` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/web.clj:2672` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/web.clj:2673` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/web.clj:2694` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/web.clj:2695` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/web.clj:2696` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/web.clj:2697` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/web.clj:2698` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/render/web.clj:2699` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/render/transcript_run_test.clj:100` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/shell/jvm.clj:122` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/shell/jvm.clj:123` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/shell/jvm.clj:124` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/shell/jvm.clj:125` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/shell/jvm.clj:126` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/shell/jvm.clj:127` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/render/lint_test.clj:61` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:51` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:983` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:984` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:985` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:986` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:987` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:988` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:989` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1191` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1192` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1193` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1194` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1195` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1196` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1197` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1351` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1352` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1353` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1354` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1357` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1358` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1359` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1382` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1383` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1384` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1385` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1386` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1387` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1388` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1397` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1398` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1399` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1400` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1401` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1403` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1406` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1460` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1461` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1462` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1463` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1465` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1466` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1467` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1827` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1828` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1829` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1830` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1831` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1832` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1833` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1884` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1885` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1886` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1887` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1888` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1889` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1890` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1989` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1990` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1991` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1992` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1994` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1995` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:1996` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2122` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2123` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2124` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2125` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2126` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2127` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2128` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2186` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2187` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2188` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2189` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2191` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2192` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2193` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2275` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2276` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2277` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2278` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2279` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2280` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2281` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2703` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2704` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2705` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2706` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2707` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2708` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:2709` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3188` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3267` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3268` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3269` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3270` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3271` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3272` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3274` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3400` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3501` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3754` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3755` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3756` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3757` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3758` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3759` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3760` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3807` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3808` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3809` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3810` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3811` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3812` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3813` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3848` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3849` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3850` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3851` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3852` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3853` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3854` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3927` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3928` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3929` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3930` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3931` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3932` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:3933` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:4165` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:4166` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:4167` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:4168` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:4170` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:4174` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:4175` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:4228` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:4229` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:4230` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:4231` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:4233` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:4234` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/db.clj:4235` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster.clj:302` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster.clj:306` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster.clj:307` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster.clj:308` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster.clj:309` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster.clj:311` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster.clj:312` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster.clj:313` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster.clj:334` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster.clj:338` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster.clj:339` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster.clj:340` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster.clj:341` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster.clj:342` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster.clj:343` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster.clj:344` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster.clj:358` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster.clj:645` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster.clj:646` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster.clj:2570` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/context.clj:65` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/context.clj:68` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/context.clj:69` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/context.clj:70` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/context.clj:71` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/context.clj:73` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/context.clj:74` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/context.clj:75` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:42` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:1736` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:1737` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:1739` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:1740` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:1741` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:1742` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:1743` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:2619` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:2620` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:2621` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:2622` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:2623` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:2624` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:2625` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:2799` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:2800` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:2801` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:2802` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:2803` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:2804` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/schema.clj:2805` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/sci/eval_test.clj:906` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/sci/eval_test.clj:908` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/sci/eval_test.clj:1631` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/sci/eval_test.clj:1815` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/sci/eval_test.clj:2307` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/sci/eval_test.clj:2315` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster/agent_test.clj:1009` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster/agent_test.clj:1065` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/agent.clj:582` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/agent.clj:586` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/agent.clj:587` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/agent.clj:588` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/agent.clj:589` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/agent.clj:590` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/agent.clj:591` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/agent.clj:592` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/agent.clj:621` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/agent.clj:625` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/agent.clj:626` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/agent.clj:627` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/agent.clj:629` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/agent.clj:630` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/agent.clj:631` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/agent.clj:633` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/env_test.clj:350` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/env_test.clj:368` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/env_test.clj:381` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/source.clj:466` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/source.clj:469` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/source.clj:470` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/source.clj:471` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/source.clj:472` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/source.clj:473` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/source.clj:474` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/source.clj:475` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/source.clj:498` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/source.clj:501` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/source.clj:502` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/source.clj:503` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/source.clj:504` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/source.clj:505` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/source.clj:506` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/cluster/source.clj:507` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:289` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:290` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:291` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:292` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:293` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:294` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:295` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:296` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:1483` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:1486` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:1489` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:1492` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:1548` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:1573` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:1576` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:1579` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:1589` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:1592` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:1598` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:1684` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:1708` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:1711` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:1729` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:1738` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:2157` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:2158` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:2183` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:2185` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/db_test.clj:2249` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/shell/jvm_test.clj:156` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/call_preparation_test.clj:495` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/call_preparation_test.clj:498` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/call_preparation_test.clj:507` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:114` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:117` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:118` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:119` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:120` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:121` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:122` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:123` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:167` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:168` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:169` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:170` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:171` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:172` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:390` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:404` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:405` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:406` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:407` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:408` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:409` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:410` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:541` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:543` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:544` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:545` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:546` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:547` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:548` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:549` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:988` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:991` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:992` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:993` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:994` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:995` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:996` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:997` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:1223` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:1225` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:1226` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:1227` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:1228` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:1229` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:1230` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:1231` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:1571` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:1572` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:1573` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:1574` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:1575` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:1576` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:1733` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:1734` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:1735` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:1736` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:1737` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/test.clj:1738` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/maintenance.clj:340` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/maintenance.clj:344` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/maintenance.clj:345` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/maintenance.clj:346` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/maintenance.clj:347` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/maintenance.clj:348` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/maintenance.clj:349` — supply the base three inputs at the observing boundary and declare its returned facet.
- `src/seon/maintenance.clj:350` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/flow_test.clj:452` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/flow_test.clj:456` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/flow_test.clj:800` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/refusal_grammar_test.clj:55` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/refusal_grammar_test.clj:58` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/refusal_grammar_test.clj:59` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/refusal_grammar_test.clj:60` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/refusal_grammar_test.clj:61` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/refusal_grammar_test.clj:62` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/refusal_grammar_test.clj:63` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/refusal_grammar_test.clj:64` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/refusal_grammar_test.clj:89` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test_test.clj:226` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test_test.clj:229` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster/mcp_test.clj:233` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster/mcp_test.clj:234` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster/mcp_test.clj:235` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster/mcp_test.clj:236` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster/mcp_test.clj:238` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster/mcp_test.clj:240` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster/mcp_test.clj:241` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster/mcp_test.clj:243` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster/mcp_test.clj:247` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster/mcp_test.clj:248` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster/mcp_test.clj:249` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster/mcp_test.clj:250` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster/mcp_test.clj:251` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster/mcp_test.clj:252` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster/mcp_test.clj:253` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/cluster/mcp_test.clj:254` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/flow_configuration_test.clj:49` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/maintenance_test.clj:536` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/maintenance_test.clj:539` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/schema_test.clj:311` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/schema_test.clj:818` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/schema_test.clj:821` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/schema_test.clj:824` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/schema_test.clj:827` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/schema_test.clj:830` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/schema_test.clj:834` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/schema_test.clj:1303` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/schema_test.clj:1305` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/dev/dependency_cache_test.clj:278` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/dev/dependency_cache_test.clj:281` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/dev/dependency_cache_test.clj:284` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/dev/dependency_cache_test.clj:287` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/fn_test.clj:71` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/fn_test.clj:1555` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/fn_test.clj:1560` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/fn_test.clj:1561` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/fn_test.clj:1562` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/fn_test.clj:1563` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/fn_test.clj:1564` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/fn_test.clj:1565` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/fn_test.clj:1566` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/fn_test.clj:1972` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/fn_test.clj:1976` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/fn_test.clj:1977` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/fn_test.clj:1978` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/fn_test.clj:1979` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/fn_test.clj:1980` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/fn_test.clj:1981` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/fn_test.clj:1982` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/fn_test.clj:2584` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/fn_test.clj:2587` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/fn_test.clj:2862` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/transact_feedback_test.clj:71` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/dev/fresh_operator_test.clj:228` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test_support.clj:515` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test_support.clj:520` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test_support.clj:521` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test_support.clj:522` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test_support.clj:523` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test_support.clj:524` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test_support.clj:526` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/test_support.clj:528` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/turn_test.clj:122` — supply the base three inputs at the observing boundary and declare its returned facet.
- `test/seon/turn_test.clj:124` — supply the base three inputs at the observing boundary and declare its returned facet.

Direct normalization requests require `:seon.schema/projection`:

- `src/seon/cluster.clj:3058` — put the supplied database's acquired projection on the committer request before `error/prepare`.
- `test/seon/run6_stall_test.clj:38` — use the canonical fixture's handed projection.
- `test/seon/render/web_debug_test.clj:586` — use the canonical fixture's handed projection.

`seon.call-preparation/supplied-map-entries` still declares generic errors;
`instrument/supplied-entry-problems` now accepts its declared vector or throws
a complete registration observation naming that unresolved owner contract.
`seon.config/result-caps` likewise owes complete config observations. The
existing SCI stamp checks and schema/call-with-projection-state output union
remain the previously measured external boundaries. The error recorder no
longer consumes the recurrence-limit dial; its external config declaration can
be retired by its owner when other consumers are converted.
