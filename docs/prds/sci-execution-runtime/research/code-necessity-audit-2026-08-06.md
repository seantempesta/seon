---
type: research
status: active
tags: [research, architecture, deletion, testing]
---

# Independent codebase necessity audit — 2026-08-06

## Verdict

The fresh Clojure tree is large, but not mainly because deleted pod machinery
was renamed into many new namespaces. At the audited working-tree snapshot,
`src/` is 42,595 lines and `test/` is 45,042 lines: tests are 51.4% of the
87,637-line total. The bulk is concentrated in current cluster, database,
schema, SCI, Flow, render, operator, and reset-boundary proofs.

The audit did find one readerless production namespace, one substantial
test-only Flow subsystem, two compatibility calls, one reset-only migration,
one hand-maintained request roster, one orphan test fixture, and CLJS-era
tooling/assets outside `src/`. The highest-confidence cuts exceed 5,000 lines
when the unconsumed browser assets are included, but only about 1,500 of those
lines are in `src/` plus `test/`. This is cleanup worth doing, not evidence
that most of the fresh runtime is redundant.

## Authorities read

I read the following named authorities before reaching verdicts:

- the root `AGENTS.md`, in full;
- the three 2026-08-05 working-edge blocks at the top of
  `docs/prds/sci-execution-runtime/plan/unsettled.md`, in full;
- the `SESSION OUTCOME` section of
  `docs/prds/sci-execution-runtime/plan/state-of-the-program-2026-08-05.md`,
  in full;
- `docs/prds/sci-execution-runtime/plan/rename-pass-2026-08-05.md`, in full,
  to resolve which spellings the reset actually retired;
- `docs/prds/sci-execution-runtime/plan/README.md`, in full, and
  `docs/seon/architecture/architecture.md`, in full, to compare the inventory
  with the current dependency spine and intended owners; and
- the `data-oriented-clojure` skill and its required program-state reference,
  in full, before reviewing Clojure mechanisms.

The architecture still needs the database/program graph, per-agent Flow
graphs, run loop, SCI evaluation and admission, effect boundary, render/web
pipeline, operator/bootstrap, source publication, and observability owners.
Those are the areas where the line bulk actually sits. The 2026-08-05 edge
also makes the negative bar explicit: no old agent defs fact family, migration path,
preemptible sweep, pod execution tier, or parallel process-record authority.

## Method

The inventory counts every `.clj`, `.cljc`, and `.cljs` namespace below
`src/` and `test/` using physical lines. I then:

1. built static namespace reachability from clj-kondo analysis;
2. exact-searched namespace and public-symbol consumers across `src/`,
   `test/`, `script/`, `bin/`, `resources/`, config, and build files;
3. treated configured symbols, schema-declared render producers, effect
   descriptor symbols, command-line `-m` targets, and child-process fixtures
   as real entry points rather than false zero-indegree results;
4. loaded the suspicious production namespaces on the JVM;
5. directly resolved private operator targets and ran the smallest focused
   failing test for the strongest obsolete-test claim; and
6. searched for every named retired spelling or mechanism before recording a
   negative result.

The focused load of `seon.cluster.export`, `seon.cluster.curate`,
`seon.render.value`, `seon.schedule`, and `seon.cluster.instruction` succeeded.
The focused
`legacy-operator-jvm-roles-remain-visible-to-orphan-detection` test produced
six errors because `legacy-operator-arguments?` resolves to `nil`; this is the
obsolete test itself, not foreign P12 breakage.

## Total size picture

### Source areas

| Area | Namespaces/files | Lines | Share of `src/` |
|---|---:|---:|---:|
| Cross-domain `src/seon/*` owners | 27 | 20,443 | 48.0% |
| Cluster runtime (`src/seon/cluster/`) | 15 | 7,747 | 18.2% |
| Render pipeline (`src/seon/render/`) | 10 | 4,840 | 11.4% |
| SCI (`src/seon/sci/`) | 4 | 3,372 | 7.9% |
| Capability/platform leaves | 10 | 2,876 | 6.8% |
| Schema subowners | 5 | 1,968 | 4.6% |
| Function-analysis subowners | 3 | 814 | 1.9% |
| Agent-callable `my.*` functions | 7 | 535 | 1.3% |
| **Total** | **81** | **42,595** | **100%** |

The ten largest source namespaces account for 16,661 lines, or 39.1% of
all source. They are the central schema, cluster, SCI, loop, web, run, function
index, AI, Flow, and database owners. Small platform leaves are often
deliberately reached through declared symbols, so static `require` indegree
alone would misclassify them.

### Test areas

| Area | Files | Lines | Share of `test/` |
|---|---:|---:|---:|
| Cluster/runtime | 25 | 14,868 | 33.0% |
| Other integration/unit tests | 47 | 13,870 | 30.8% |
| SCI plus REPL parity | 7 | 3,905 | 8.7% |
| Development/operator tooling | 8 | 3,875 | 8.6% |
| Render | 15 | 3,804 | 8.4% |
| Flow | 4 | 2,699 | 6.0% |
| Schema | 8 | 1,704 | 3.8% |
| `my.*` | 6 | 317 | 0.7% |
| **Total** | **120** | **45,042** | **100%** |

`bin/test:121-128` discovers 110 `*_test.clj`/`*_test.cljc` files totaling
44,406 lines. Every discovered namespace contains at least one `deftest` and
all first-party requires resolve. Ten child/support namespaces total 636
lines; nine have exact consumers, while `seon.render-fixture` is orphaned.
The twenty largest test files contain 23,508 lines, or 52.2% of the test tree.

The snapshot includes two explicitly unjudged P12 paths and one foreign
untracked test file: `src/seon/fn/schema_shape.clj` (322 lines),
`test/seon/program_test.clj` (758), and `test/seon/fs_test.clj` (46). They are
counted so the totals describe the actual working tree, but no verdict depends
on their in-flight contents.

## Per-namespace verdicts

`Keep` means the audit found a current caller, declared/dynamic entry point,
or recurring proof responsibility. `Merge candidate` means the namespace
remains necessary but a named submechanism or suite boundary should disappear.
`Delete candidate` means no surviving owner was found. `Investigate` means the
evidence supports a later boundary decision but not deletion now.

### Source namespaces

| Namespace | Lines | Verdict | Evidence |
|---|---:|---|---|
| `my.background` | 97 | keep | `src/my/background.clj:1`; declared agent function surface with paired tests |
| `my.edit` | 91 | keep | `src/my/edit.clj:1`; platform targets at `:61,75,89` |
| `my.fs` | 118 | keep | `src/my/fs.clj:1`; declared capability surface with paired tests |
| `my.message` | 90 | keep | `src/my/message.clj:1`; current message entry surface |
| `my.run` | 47 | keep | `src/my/run.clj:1`; current run function surface |
| `my.shell` | 57 | keep | `src/my/shell.clj:1`; platform target at `:55` |
| `my.web` | 35 | keep | `src/my/web.clj:1`; platform targets at `:19,33` |
| `seon.ai` | 1,209 | keep | `src/seon/ai.clj:1`; one current provider request/stream owner |
| `seon.ai.tokens` | 57 | keep | `src/seon/ai/tokens.cljc:1`; required token-estimation owner |
| `seon.artifact` | 103 | keep | `src/seon/artifact.clj:1`; artifact main built at `build.clj:160-180` |
| `seon.blob` | 335 | keep | `src/seon/blob.clj:1`; current staged-publication and permit owner |
| `seon.bootstrap` | 301 | keep | `src/seon/bootstrap.clj:1`; current bootstrap contracts |
| `seon.bootstrap-drive` | 469 | keep | `src/seon/bootstrap_drive.clj:1`; explicit `-m` entry at `:12-13` |
| `seon.cluster` | 2,355 | keep | `src/seon/cluster.clj:1`; cluster population/lifecycle owner |
| `seon.cluster.agent` | 663 | keep | `src/seon/cluster/agent.clj:1`; current per-agent graph owner |
| `seon.cluster.curate` | 350 | keep | `src/seon/cluster/curate.clj:1`; current proof/adoption owner |
| `seon.cluster.export` | 298 | delete candidate | `src/seon/cluster/export.clj:1`; no runtime, bin, script, config, or architecture reader |
| `seon.cluster.instruction` | 83 | merge candidate | `src/seon/cluster/instruction.clj:13-15`; keep current owner, delete old-ID migration |
| `seon.cluster.loop` | 1,797 | keep | `src/seon/cluster/loop.clj:1`; current per-agent run loop |
| `seon.cluster.message` | 472 | keep | `src/seon/cluster/message.clj:1`; current durable message owner |
| `seon.cluster.process` | 49 | keep | `src/seon/cluster/process.clj:1`; current process identity predicates |
| `seon.cluster.prompt` | 94 | keep | `src/seon/cluster/prompt.clj:1`; current prompt derivation owner |
| `seon.cluster.registry` | 511 | keep | `src/seon/cluster/registry.clj:1`; current store/cluster registry |
| `seon.cluster.reply` | 352 | keep | `src/seon/cluster/reply.clj:1`; current reply reader/settlement owner |
| `seon.cluster.run` | 1,414 | keep | `src/seon/cluster/run.clj:1`; widely called current run/custody owner |
| `seon.cluster.source` | 299 | keep | `src/seon/cluster/source.clj:1`; current source publication owner |
| `seon.cluster.store` | 413 | keep | `src/seon/cluster/store.clj:1`; physical store/fence owner, not a second `seon.db` |
| `seon.cluster.wake` | 258 | keep | `src/seon/cluster/wake.clj:1`; current fact-derived wake owner |
| `seon.cluster.work` | 694 | keep | `src/seon/cluster/work.clj:1`; current work selection/claims owner |
| `seon.config` | 512 | keep | `src/seon/config.clj:1`; database-derived config owner |
| `seon.context` | 138 | keep | `src/seon/context.clj:1`; current context derivation owner |
| `seon.db` | 1,143 | keep | `src/seon/db.clj:1`; one core database namespace |
| `seon.edit` | 448 | keep | `src/seon/edit.clj:1`; pure edit owner |
| `seon.edit.jvm` | 95 | keep | `src/seon/edit/jvm.clj:1`; target of `my.edit` descriptor symbols |
| `seon.effect` | 657 | keep | `src/seon/effect.clj:1`; one capability-request boundary |
| `seon.error` | 957 | keep | `src/seon/error.clj:1`; current flat-error/fault owner |
| `seon.error.refusal` | 15 | keep | `src/seon/error/refusal.clj:1`; lower owner required by db/error/SCI |
| `seon.eval.drive` | 442 | keep | `src/seon/eval/drive.clj:1`; current evaluation drive/proof owner |
| `seon.flow` | 1,184 | merge candidate | `src/seon/flow.clj:947-1184`; keep launcher/fault owner, delete prototype tail |
| `seon.fn` | 1,286 | keep | `src/seon/fn.clj:1`; one program-graph index/query owner |
| `seon.fn.analyzer` | 381 | keep | `src/seon/fn/analyzer.clj:1`; current static analysis owner |
| `seon.fn.schema-shape` | 322 | investigate (foreign P12) | `src/seon/fn/schema_shape.clj:1`; counted, not judged |
| `seon.fn.signature` | 111 | keep | `src/seon/fn/signature.cljc:1`; portable signature owner required by `seon.program` |
| `seon.fs` | 78 | keep | `src/seon/fs.clj:1`; portable filesystem boundary |
| `seon.fs.jvm` | 755 | keep | `src/seon/fs/jvm.clj:1`; current JVM filesystem implementation |
| `seon.instrument` | 416 | keep | `src/seon/instrument.clj:1`; program-graph-derived contracts |
| `seon.maintenance` | 398 | keep | `src/seon/maintenance.clj:1`; current maintenance task owner |
| `seon.operator` | 791 | keep | `src/seon/operator.clj:1`; dynamically loaded by `script/seon/fresh_operator.clj:1381` |
| `seon.oversight` | 301 | keep | `src/seon/oversight.clj:1`; current fleet observation owner |
| `seon.print` | 791 | keep | `src/seon/print.cljc:1`; one bounded structural print/fit owner |
| `seon.problems` | 615 | keep | `src/seon/problems.clj:1`; current problem fact/render owner |
| `seon.program` | 834 | keep | `src/seon/program.cljc:1`; current program-row owner |
| `seon.reconcile` | 441 | keep | `src/seon/reconcile.cljc:1`; current data reconciliation owner |
| `seon.render` | 649 | keep | `src/seon/render.clj:1`; projection selection and walk coordination |
| `seon.render.agent` | 43 | keep | `src/seon/render/agent.clj:1`; required by transcript at `src/seon/render/transcript.clj:17` |
| `seon.render.block` | 96 | keep | `src/seon/render/block.clj:1`; current identified block owner |
| `seon.render.data` | 73 | keep | `src/seon/render/data.clj:1`; current data-page projection owner |
| `seon.render.hiccup` | 513 | keep | `src/seon/render/hiccup.clj:1`; one Hiccup serialization owner |
| `seon.render.ns` | 618 | keep | `src/seon/render/ns.clj:1`; producers declared in `resources/seon/schemas/seon.ns.edn:10-11` |
| `seon.render.route` | 55 | keep | `src/seon/render/route.clj:1`; one route table |
| `seon.render.transcript` | 772 | keep | `src/seon/render/transcript.clj:1`; current transcript projections |
| `seon.render.value` | 396 | merge candidate | `src/seon/render/value.clj:13-17,329-353`; keep renderer, delete compatibility calls |
| `seon.render.walk` | 644 | keep | `src/seon/render/walk.clj:1`; current database-derived walk |
| `seon.render.web` | 1,630 | keep | `src/seon/render/web.clj:1`; current Datastar page/feed owner |
| `seon.schedule` | 749 | merge candidate | `src/seon/schedule.clj:457-487`; keep scheduler, derive duplicated field roster |
| `seon.schema` | 2,836 | keep | `src/seon/schema.clj:1`; one registry/admission bridge owner |
| `seon.schema.admission` | 418 | keep | `src/seon/schema/admission.clj:1`; hook subprocess entry at `bin/seon-hook:239-245` |
| `seon.schema.datahike` | 507 | keep | `src/seon/schema/datahike.clj:1`; current Malli/Datahike bridge |
| `seon.schema.edn` | 565 | keep | `src/seon/schema/edn.clj:1`; current schema-resource loader |
| `seon.schema.form` | 109 | keep | `src/seon/schema/form.cljc:1`; portable schema form owner |
| `seon.schema.internal` | 369 | keep | `src/seon/schema/internal.cljc:1`; lower schema owner required by `seon.schema` |
| `seon.sci.admit` | 540 | keep | `src/seon/sci/admit.clj:1`; one value admission owner |
| `seon.sci.eval` | 1,807 | keep | `src/seon/sci/eval.clj:1`; current base/fork/eval/owner of the agent's defs |
| `seon.sci.kernel` | 387 | keep | `src/seon/sci/kernel.clj:1`; lower SCI kernel owner |
| `seon.sci.reader` | 638 | keep | `src/seon/sci/reader.cljc:1`; one agent-reply reader |
| `seon.search` | 447 | keep | `src/seon/search.clj:1`; current database search owner |
| `seon.shell.jvm` | 348 | keep | `src/seon/shell/jvm.clj:1`; target of `my.shell` descriptor |
| `seon.test.runner` | 672 | keep | `src/seon/test/runner.clj:1`; executable at `bin/test:333` |
| `seon.web.extract` | 14 | keep | `src/seon/web/extract.clj:1`; dynamically invoked by `seon.web.jvm:244-249` |
| `seon.web.jvm` | 446 | keep | `src/seon/web/jvm.clj:1`; target of `my.web` descriptors |
| `seon.web.search` | 32 | keep | `src/seon/web/search.clj:1`; configured projection at `config/default.edn:269` |

### Test and support namespaces

Unless a row says otherwise, `bin/test:121-128` discovers the namespace and
the audit verified at least one `deftest`. A support/child verdict names its
entry-point role rather than pretending the test runner requires it directly.

| Namespace | Lines | Verdict | Evidence |
|---|---:|---|---|
| `my.background-test` | 52 | keep | `test/my/background_test.clj:1`; recurring test |
| `my.edit-test` | 33 | keep | `test/my/edit_test.clj:1`; recurring test |
| `my.fs-test` | 44 | keep | `test/my/fs_test.clj:1`; recurring test |
| `my.message-test` | 105 | keep | `test/my/message_test.clj:1`; recurring test |
| `my.run-test` | 48 | keep | `test/my/run_test.clj:1`; recurring test |
| `my.web-test` | 35 | keep | `test/my/web_test.clj:1`; recurring test |
| `seon.ai.tokens-test` | 10 | keep | `test/seon/ai/tokens_test.clj:1`; recurring test |
| `seon.ai-stream-fold-test` | 404 | keep | `test/seon/ai_stream_fold_test.clj:1`; surviving streaming fold proof |
| `seon.ai-test` | 877 | keep | `test/seon/ai_test.clj:1`; provider/request/stream proofs |
| `seon.background-blob-test` | 164 | keep | `test/seon/background_blob_test.clj:1`; recurring integration proof |
| `seon.background-test` | 86 | keep | `test/seon/background_test.clj:1`; recurring test |
| `seon.blob-publication-test` | 183 | keep | `test/seon/blob_publication_test.clj:1`; current permit/publication proof |
| `seon.blob-settlement-test` | 67 | keep | `test/seon/blob_settlement_test.clj:1`; current settlement proof |
| `seon.blob-test` | 225 | keep | `test/seon/blob_test.clj:1`; recurring test |
| `seon.blob-threshold-test` | 71 | keep | `test/seon/blob_threshold_test.clj:1`; recurring test |
| `seon.bootstrap-drive-test` | 59 | keep | `test/seon/bootstrap_drive_test.clj:1`; recurring drive proof |
| `seon.bootstrap-test` | 238 | keep | `test/seon/bootstrap_test.clj:1`; recurring test |
| `seon.cluster.agent-namespace-test` | 94 | keep | `test/seon/cluster/agent_namespace_test.clj:1`; current namespace ownership proof |
| `seon.cluster.agent-test` | 1,679 | keep | `test/seon/cluster/agent_test.clj:1`; current per-agent graph proof |
| `seon.cluster.armed-test` | 589 | keep | `test/seon/cluster/armed_test.clj:1`; live armed/reset boundary |
| `seon.cluster.boot-test` | 1,307 | keep | `test/seon/cluster/boot_test.clj:1`; boot sequence proof |
| `seon.cluster.curate-test` | 173 | keep | `test/seon/cluster/curate_test.clj:1`; current proof/adoption tests |
| `seon.cluster.export-test` | 216 | delete candidate | `test/seon/cluster/export_test.clj:1`; only reader of readerless export namespace |
| `seon.cluster.instruction-test` | 172 | merge candidate | `test/seon/cluster/instruction_test.clj:83-105`; keep current proofs, delete migration case |
| `seon.cluster.loop-test` | 1,293 | keep | `test/seon/cluster/loop_test.clj:1`; current run-loop proof |
| `seon.cluster.mcp-test` | 354 | keep | `test/seon/cluster/mcp_test.clj:1`; current MCP entry proof |
| `seon.cluster.message-assignment-test` | 177 | keep | `test/seon/cluster/message_assignment_test.clj:1`; recurring assignment proof |
| `seon.cluster.message-test` | 650 | keep | `test/seon/cluster/message_test.clj:1`; current message proof |
| `seon.cluster.problem-routing-test` | 259 | keep | `test/seon/cluster/problem_routing_test.clj:1`; current problem route proof |
| `seon.cluster.program-restart-test` | 379 | keep | `test/seon/cluster/program_restart_test.clj:1`; subprocess restart proof |
| `seon.cluster.prompt-test` | 248 | keep | `test/seon/cluster/prompt_test.clj:1`; current prompt proof |
| `seon.cluster.registry-test` | 489 | keep | `test/seon/cluster/registry_test.clj:1`; registry/store proof |
| `seon.cluster.reply-test` | 246 | keep | `test/seon/cluster/reply_test.clj:1`; reply reader proof |
| `seon.cluster.resume-artifact-routing-test` | 101 | keep | `test/seon/cluster/resume_artifact_routing_test.clj:1`; resume routing proof |
| `seon.cluster.run-test` | 1,212 | keep | `test/seon/cluster/run_test.clj:1`; current custody transition proof |
| `seon.cluster.source-test` | 352 | keep | `test/seon/cluster/source_test.clj:1`; source publication proof |
| `seon.cluster.store-child` | 27 | keep | `test/seon/cluster/store_child.clj:1`; subprocess child entry point |
| `seon.cluster.store-test` | 531 | keep | `test/seon/cluster/store_test.clj:1`; physical store/fence proof |
| `seon.cluster.store-transact-test` | 179 | merge candidate | `test/seon/cluster/store_transact_test.clj:1-24`; live db/error proofs under superseded wrapper-suite name |
| `seon.cluster.turn-test` | 3,165 | keep | `test/seon/cluster/turn_test.clj:1`; current end-to-end turn integration proof |
| `seon.cluster.wake-test` | 393 | keep | `test/seon/cluster/wake_test.clj:1`; fact-derived wake proof |
| `seon.cluster.work-test` | 583 | keep | `test/seon/cluster/work_test.clj:1`; current work selection proof |
| `seon.concurrency-independence-test` | 527 | keep | `test/seon/concurrency_independence_test.clj:1`; long cross-cluster proof |
| `seon.concurrency-streams-test` | 149 | keep | `test/seon/concurrency_streams_test.clj:1`; long stream independence proof |
| `seon.config-application-test` | 348 | keep | `test/seon/config_application_test.clj:1`; config reconciliation proof |
| `seon.config-test` | 395 | keep | `test/seon/config_test.clj:1`; recurring config proof |
| `seon.custody-stability-test` | 265 | keep | `test/seon/custody_stability_test.clj:1`; custody stability proof |
| `seon.datahike-fork-test` | 50 | keep | `test/seon/datahike_fork_test.clj:1`; maintained fork acceptance proof |
| `seon.db-test` | 547 | keep | `test/seon/db_test.clj:1`; one database namespace proof |
| `seon.dev.changed-test-test` | 82 | investigate | `test/seon/dev/changed_test_test.clj:16-35`; pins split selector, unification needs proof |
| `seon.dev.dependency-cache-test` | 209 | keep | `test/seon/dev/dependency_cache_test.clj:1`; current dependency cache proof |
| `seon.dev.docstring-test` | 209 | keep | `test/seon/dev/docstring_test.clj:1`; recurring development check |
| `seon.dev.edit-feedback-test` | 361 | investigate | `test/seon/dev/edit_feedback_test.clj:343-361`; pins split selector, unification needs proof |
| `seon.dev.fresh-operator-test` | 1,805 | merge candidate | `test/seon/dev/fresh_operator_test.clj:527-541,635-674`; keep suite, delete unresolved private-target cases |
| `seon.dev.issues-test` | 66 | keep | `test/seon/dev/issues_test.clj:1`; issue-index validator proof |
| `seon.dev.markdown-test` | 466 | keep | `test/seon/dev/markdown_test.clj:1`; keep linter, delete inert one-rule owner |
| `seon.dev.mcp-bridge-test` | 677 | keep | `test/seon/dev/mcp_bridge_test.clj:1`; current MCP bridge proof |
| `seon.edit.jvm-test` | 134 | keep | `test/seon/edit/jvm_test.clj:1`; platform edit proof |
| `seon.edit-test` | 152 | keep | `test/seon/edit_test.clj:1`; pure edit proof |
| `seon.effect-test` | 285 | keep | `test/seon/effect_test.clj:1`; one capability boundary proof |
| `seon.error-class-schema-test` | 173 | keep | `test/seon/error_class_schema_test.clj:1`; current error-class contract proof |
| `seon.error-test` | 683 | keep | `test/seon/error_test.clj:1`; flat-error/fault proof |
| `seon.eval.drive-test` | 49 | keep | `test/seon/eval/drive_test.clj:1`; drive proof |
| `seon.flow.kill-child` | 44 | keep | `test/seon/flow/kill_child.clj:1`; subprocess child entry point |
| `seon.flow.loop-test` | 707 | delete candidate | `test/seon/flow/loop_test.clj:1-15`; self-contained fake-agent prototype |
| `seon.flow-configuration-test` | 89 | keep | `test/seon/flow_configuration_test.clj:1`; current Flow config proof |
| `seon.flow-test` | 1,859 | merge candidate | `test/seon/flow_test.clj:127-208,1464-1537`; keep live Flow proofs, delete prototype readers |
| `seon.fn.analyzer-test` | 232 | keep | `test/seon/fn/analyzer_test.clj:1`; analyzer proof |
| `seon.fn-test` | 961 | keep | `test/seon/fn_test.clj:1`; program-graph index/query proof |
| `seon.fs.jvm-test` | 349 | keep | `test/seon/fs/jvm_test.clj:1`; platform filesystem proof |
| `seon.fs-test` | 46 | investigate (foreign untracked) | `test/seon/fs_test.clj:1`; counted, not judged |
| `seon.gen.loop-test` | 580 | keep | `test/seon/gen/loop_test.clj:1`; uses current cluster/run owners, unlike fake Flow loop |
| `seon.instrument-test` | 452 | keep | `test/seon/instrument_test.clj:1`; derived instrumentation proof |
| `seon.maintenance-schema-test` | 210 | keep | `test/seon/maintenance_schema_test.clj:1`; maintenance contract proof |
| `seon.maintenance-test` | 331 | keep | `test/seon/maintenance_test.clj:1`; current maintenance proof |
| `seon.operator-test` | 700 | keep | `test/seon/operator_test.clj:1`; current source operator proof |
| `seon.oversight-test` | 149 | keep | `test/seon/oversight_test.clj:1`; current observation proof |
| `seon.print-test` | 271 | keep | `test/seon/print_test.clj:1`; structural print/fit proof |
| `seon.problems-test` | 453 | keep | `test/seon/problems_test.clj:1`; problem fact/render proof |
| `seon.program-test` | 758 | investigate (foreign P12) | `test/seon/program_test.clj:1`; counted, not judged |
| `seon.public-contract-test` | 111 | keep | `test/seon/public_contract_test.clj:1`; public schema census proof |
| `seon.reconcile-test` | 274 | keep | `test/seon/reconcile_test.clj:1`; data reconciliation proof |
| `seon.render.block-test` | 22 | keep | `test/seon/render/block_test.clj:1`; block identity proof |
| `seon.render.data-test` | 59 | keep | `test/seon/render/data_test.clj:1`; data projection proof |
| `seon.render.hiccup-test` | 273 | keep | `test/seon/render/hiccup_test.clj:1`; Hiccup serialization proof |
| `seon.render.ns-test` | 389 | keep | `test/seon/render/ns_test.clj:1`; namespace renderer proof |
| `seon.render.route-test` | 66 | keep | `test/seon/render/route_test.clj:1`; one route table proof |
| `seon.render.transcript-test` | 822 | keep | `test/seon/render/transcript_test.clj:1`; transcript projection proof |
| `seon.render.value-options-test` | 29 | keep | `test/seon/render/value_options_test.clj:1`; profile/options proof |
| `seon.render.value-test` | 205 | keep | `test/seon/render/value_test.clj:1`; value projection proof |
| `seon.render.web-test` | 1,279 | keep | `test/seon/render/web_test.clj:1`; Datastar page/feed proof |
| `seon.render-coverage-test` | 273 | keep | `test/seon/render_coverage_test.clj:1`; declared producer coverage proof |
| `seon.render-fixture` | 21 | delete candidate | `test/seon/render_fixture.clj:1-21`; undiscovered orphan with deleted consumer |
| `seon.render-simplification.fixture-a` | 7 | keep | `test/seon/render_simplification/fixture_a.clj:1`; exact symbol fixture |
| `seon.render-simplification.fixture-ambiguous` | 13 | keep | `test/seon/render_simplification/fixture_ambiguous.clj:1`; exact symbol fixture |
| `seon.render-simplification.fixture-b` | 14 | keep | `test/seon/render_simplification/fixture_b.clj:1`; exact symbol fixture |
| `seon.render-simplification-test` | 332 | keep | `test/seon/render_simplification_test.clj:1`; current simplification proof |
| `seon.repl-parity-test` | 965 | keep | `test/seon/repl_parity_test.clj:1`; stock REPL parity proof |
| `seon.schedule-test` | 197 | keep | `test/seon/schedule_test.clj:1`; current schedule proof |
| `seon.schema.admission-gate-test` | 210 | keep | `test/seon/schema/admission_gate_test.clj:1`; hook boundary proof |
| `seon.schema.admission-test` | 153 | keep | `test/seon/schema/admission_test.clj:1`; admission executable proof |
| `seon.schema.datahike-test` | 162 | keep | `test/seon/schema/datahike_test.clj:1`; bridge proof |
| `seon.schema.edn-test` | 389 | keep | `test/seon/schema/edn_test.clj:1`; schema resource loader proof |
| `seon.schema.edn-test-fixture` | 20 | keep | `test/seon/schema/edn_test_fixture.clj:1`; exact predicate-owner fixture |
| `seon.schema.program-test` | 136 | keep | `test/seon/schema/program_test.clj:1`; program-row schema proof |
| `seon.schema-test` | 233 | keep | `test/seon/schema_test.clj:1`; registry/admission proof |
| `seon.schema-usage-guard-test` | 401 | keep | `test/seon/schema_usage_guard_test.clj:1`; contract usage guard |
| `seon.sci.admit-test` | 388 | keep | `test/seon/sci/admit_test.clj:1`; value admission proof |
| `seon.sci.defs-child` | 147 | keep | `test/seon/sci/defs_child.clj:1`; subprocess agent defs entry point |
| `seon.sci.defs-test` | 225 | keep | `test/seon/sci/defs_test.clj:1`; two-world proof of the agent's defs |
| `seon.sci.eval-instrumentation-test` | 123 | keep | `test/seon/sci/eval_instrumentation_test.clj:1`; long instrumentation proof |
| `seon.sci.eval-test` | 1,520 | keep | `test/seon/sci/eval_test.clj:1`; current base/fork/eval proof |
| `seon.sci.reader-test` | 537 | keep | `test/seon/sci/reader_test.clj:1`; agent-reply reader proof |
| `seon.search-test` | 178 | keep | `test/seon/search_test.clj:1`; database search proof |
| `seon.shell.jvm-test` | 322 | keep | `test/seon/shell/jvm_test.clj:1`; platform shell proof |
| `seon.test-runner-failure-fixture` | 37 | keep | `test/seon/test_runner_failure_fixture.clj:1`; runner subprocess fixture |
| `seon.test-runner-test` | 269 | keep | `test/seon/test_runner_test.clj:1`; test executable proof |
| `seon.test-support` | 306 | keep | `test/seon/test_support.clj:1`; shared fresh-database fixture owner |
| `seon.test-support-test` | 168 | keep | `test/seon/test_support_test.clj:1`; fixture-owner proof |
| `seon.web.jvm-test` | 375 | keep | `test/seon/web/jvm_test.clj:1`; platform web proof |

## Ranked top-ten deletion candidates

Savings are physical-line estimates, not promises that all behavior in a
merge candidate disappears. The ranking favors confidence first, then size.

| Rank | Candidate | Estimated saving | Confidence and boundary |
|---:|---|---:|---|
| 1 | Unconsumed Scittle/debug/highlight browser assets | 3,576 lines / 1,031,359 bytes | Confirmed: current HTML emits only `datastar.js`; exact-name searches find no consumer. |
| 2 | Fake-agent Flow prototype closure | at least 945 lines | Confirmed: `src/seon/flow.clj:947-1184` (238) plus all of `test/seon/flow/loop_test.clj` (707); schema rows and prototype-only sections of `flow_test` add more. |
| 3 | Readerless cluster export | 531 lines | Strong: 298 source + 216 test + 17 schema lines; owner must either expose it or delete the closure. |
| 4 | Feedback gates reading deleted owners | about 180 lines | Confirmed: `bin/seon-hook:998-1065` and the floor-duplication closure at `script/seon/dev/markdown.clj:598-699,705-713,739`. |
| 5 | Split writer/operator changed-test partitions | 80–120 lines | Investigate/merge: identical `bin/test` wrappers can run twice; prove one affected namespace vector first. |
| 6 | Dead/private operator helpers and unresolved tests | about 70 lines | Confirmed source or failing tests; preserve command-level process cleanup proofs. |
| 7 | CLJS test/lint discovery | about 70 lines | Confirmed unreachable: about 26 lines in `test_roots` plus 44 in `bin/lint`; no `.cljs` source/test exists. |
| 8 | Reset-only old instruction retraction | about 53 lines | Confirmed: old-ID roster, retraction pass, and manufactured migration case. |
| 9 | Two `seon.render.value` compatibility calls | about 30 lines | Confirmed one caller each; repoint to `seon.render` and `seon.print`. |
| 10 | Orphan `seon.render-fixture` | 21 lines | Confirmed: undiscovered and its only consumer was deleted. |

Not ranked by line saving but still important: the five-key maintenance roster
is only six source lines, yet it can silently discard tomorrow's declared
request field. The stale Clojure-testing skill is similarly small but affects
every test-writing lane that loads it.

## Confirmed findings and owners

### 1. Delete the fake-agent Flow subsystem, not `seon.flow`

`src/seon/flow.clj:947-1184` openly labels the tail's values and procs fake,
prototype, fixture, or simulated. `test/seon/flow/loop_test.clj:1-15` declares
its own raw prototype database schema, and its four tests exercise that private
system rather than the production agent graph. `source-enumerator-proc` and
`indexer-proc` have no reader; planner/owner lineage has only this suite;
simulated eval/mailbox readers are confined to Flow tests.

The live launcher, bounded submission, capacity observation, fault committer,
and error fan-out in the first 946 lines remain necessary. This is a tail cut
plus exact schema/test readers, already owned by
[the Flow prototype issue](docs/seon/issues/flow-prototype-procs-survive-beside-the-live-agent-graphs.md).

### 2. Delete or deliberately expose `seon.cluster.export`

`seon.cluster.export` loads, but no runtime source, `bin/`, `script/`, config,
build, or current architecture entry point calls it. Its public effectful
functions at `src/seon/cluster/export.clj:241-298` require a live store handle
ordinary agent data cannot supply. Only its dedicated tests and schemas read
the surface. This is not analogous to low-indegree `seon.cluster.curate`, whose
proof/adopt role is current and referenced by the session-curation program.

The decision remains explicit exposure or deletion; tests alone do not make an
effectful subsystem live. Evidence was refreshed in
[the cluster export issue](docs/seon/issues/cluster-export-is-implemented-without-a-runtime-reader.md).

### 3. Delete reset-only instruction migration

`src/seon/cluster/instruction.clj:13-15` stores four old instruction IDs,
`src/seon/cluster.clj:719-745` retracts them at population, and
`test/seon/cluster/instruction_test.clj:83-105` manufactures old data to keep
the migration green. The rename pass required reset, no migration, and no
parallel old spellings. Sovereign old clusters are not rewritten by current
population. This is tracked in
[the instruction migration issue](docs/seon/issues/instruction-population-retains-reset-only-migration.md).

### 4. Repoint the two compatibility calls

`seon.render.value/transacted` has one caller in `seon.error` and delegates by
`requiring-resolve` to `seon.render/transacted`.
`seon.render.value/print-node-window` has one caller in `seon.cluster` and
constructs a private `legacy-window` profile with a magic token budget. Both
callers can reach the real owner without a new cycle or require. The cut is
tracked in
[the render compatibility issue](docs/seon/issues/render-value-retains-compatibility-callers.md).

### 5. Derive maintenance request fields

`src/seon/schedule.clj:457-487` selects a five-key `maintenance-dials` vector
even though `resources/seon/schemas/seon.maintenance.request.edn:42-87`
declares the request. This is a hand list over already-declared facts: a new
field can validate in the registry and vanish at execution. The owner is
[the maintenance request issue](docs/seon/issues/maintenance-execution-duplicates-declared-request-fields.md).

### 6. Delete the orphan render fixture

`test/seon/render_fixture.clj:1-21` says `test/seon/render_test.clj` consumes it,
but that file was deleted in `67bd2f216`. Exact first-party search finds only
the fixture's own namespace declaration, and its filename is not discoverable
by `bin/test`. This is tracked in
[the orphan fixture issue](docs/seon/issues/render-fixture-outlived-its-router-test.md).

### 7. Finish deleting CLJS-era browser and test tooling

Eight unreferenced public assets total 3,576 lines. The largest is
`resources/public/js/scittle.js` at 1,945 lines and 888,214 bytes; its browser
evaluator has no current owner. `script/seon/dev/test_roots.clj` still builds a
dead CLJS discovery chain, `bin/lint` still handles sibling `.cljs` files, and
one operator test still asks a deleted classifier about Shadow CLJS roles.
These share one deletion boundary in
[the CLJS residue issue](docs/seon/issues/cljs-era-assets-and-test-discovery-survived-deletion.md).

### 8. Remove private operator residue instead of restoring it

`script/seon/fresh_operator.clj:131-133,202-206` contains two private functions
with zero callers. `require-readable-process-records!` has only a private test
reader. More importantly, tests at
`test/seon/dev/fresh_operator_test.clj:527-541,635-674` call two private
functions already deleted from production. The focused legacy-role test failed
six times because its target is absent; exact search also finds no
`terminate-observed-process!` definition. Current command-level process claims
and cleanup remain necessary. The refreshed owner is
[the operator private-helper issue](docs/seon/issues/operator-private-helpers-have-only-test-readers.md).

### 9. Delete feedback checks that silently observe nothing

`bin/seon-hook:998-1065,1138-1141` still reads `logs/pod.log`, `SEON_CONFIG`,
and a file manifest for a core-fault gate with no current marker producer.
`script/seon/dev/markdown.clj:598-713,739` reads deleted
`src/seon/agent/ctx.cljs` and converts absence to an empty comparison set.
Both paths report clean when their evidence owner is gone. Current line
evidence is in
[the dev-feedback issue](docs/seon/issues/dev-feedback-gates-observe-deleted-owners.md).

### 10. Repair the stale Clojure-testing skill

`.agents/skills/clojure-testing/SKILL.md:18-23` teaches absent `:cljs`,
`:writer`, and `:writer-test` aliases and cites line ranges beyond the current
end of `deps.edn`. This recurrence is tracked separately because skill errors
propagate to every lane that loads them:
[the Clojure-testing skill issue](docs/seon/issues/clojure-testing-skill-again-teaches-deleted-aliases.md).

## Merge and investigate candidates not yet proved deletable

- `test/seon/cluster/store_transact_test.clj:1-24` is named and documented as
  a sealed transaction-wrapper draft, but its tests now exercise
  `seon.db/transact!`, `seon.error/refusal`, and the Datahike codec. The
  behaviors are current. Merge them into their owning suites only if the move
  reduces scaffolding; do not delete the assertions wholesale.
- `script/seon/dev/test_roots.clj:101-136` and
  `script/seon/dev/changed_test.clj:179-220,341-353,382-392` retain writer vs
  operator partitions that can launch identical `bin/test` wrappers twice.
  Tests at `test/seon/dev/changed_test_test.clj:16-35` and
  `test/seon/dev/edit_feedback_test.clj:343-361` pin the split. A one-vector,
  one-JVM design looks simpler, but the command ownership boundary needs a
  focused proof before this becomes a confirmed deletion.
- `script/seon/fresh_operator.clj:171-191` and
  `resources/seon/operator/state.clj:321-329` expose overlapping write/delete
  helpers over the same claim location. This is a merge candidate, not a
  second process-record authority; the storage location is already singular.

## Explicit residue checks

Exact search over `src/`, `test/`, `script/`, `bin/`, `resources/seon/`, and
config found zero live occurrences of:

- `:seon.code.*` or `session-image`;
- `:seon.store/branch-connection`;
- `preemptible` or `preemptible-sweep`;
- `seon.agent.run` or `:seon.agent.run/*`; and
- any `.cljs` file below `src/` or `test/`.

The rename specification did not include a `seon.cluster.run` →
`seon.agent.run` rename. `seon.cluster.run` is the current, broadly consumed
owner; the absence of `seon.agent.run` is therefore a clean result, not a
failed conversion.

The legacy `data/clusters/processes` directory is not a live reader. The
negative regression at `test/seon/dev/fresh_operator_test.clj:686-707` is
useful because it proves that path is ignored; it does not preserve a second
authority.

## Calibration — what is genuinely in good shape

- Every apparent zero-indegree source leaf except `seon.cluster.export` had a
  verified schema, config, descriptor, build, script, or command entry point.
  `seon.artifact`, `seon.operator`, JVM capability leaves, declared render
  producers, the schema admission executable, and the test runner are not
  dead just because another source namespace does not `require` them.
- The two-world agent defs is coherent across `seon.sci.eval`, `seon.cluster.loop`,
  `seon.cluster.run`, `resources/seon/schemas/seon.def.edn`, and its child/live
  tests. No `:seon.code.*` or session-image compatibility path survived.
- The exclusive sweep design is singular: `src/seon/cluster.clj:1918-1939`
  takes the roster permit, `src/seon/blob.clj:243-259` holds a publication
  permit through commit, and the maintained Datahike sweep holds the exclusive
  permit. `test/seon/blob_publication_test.clj:75-165` covers both queued
  directions and crash collection. No preemptible-sweep residue remains.
- Small namespaces such as `seon.error.refusal`, `seon.web.extract`, and the
  JVM capability leaves encode real lower ownership or platform boundaries.
  Merging them for line-count aesthetics would blur dependency seams without
  removing behavior.
- Nine of ten nondiscovered test support namespaces have exact consumers as
  child JVM entry points or symbol-literal fixtures. The audit did not confuse
  an entry point with an orphan.
- Large cluster, armed, boot, turn, concurrency, SCI, REPL, web, and operator
  suites exercise reset/process/database/Flow boundaries that unit fixtures do
  not cover. Their size deserves future simplification pressure, but current
  reachability and proof responsibility support `keep`, not speculative
  deletion.

## Audit boundary

No production source or test was edited. The P12-owned
`src/seon/fn/schema_shape.clj` and `test/seon/program_test.clj` were counted and
left unjudged. The untracked foreign `test/seon/fs_test.clj` was likewise
counted without a finding. No foreign in-flight breakage blocked the inventory,
JVM namespace load, exact searches, or issue/report validation.
