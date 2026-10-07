# persist-tasks: runtime tasks on default before the owner-approved reset

Source: `runtime-tasks-before-reset.edn` (122 runtime tasks, `:seon.task/created-by` set; the first 90 read at HEAD 419345455, 32 more filed by research lanes before the reset). KEEP rows were appended unchanged (intent, must-read, evidence) to `resources/seon/tasks/<ns>.edn` with no `:id`; ingestion derives handle and symbol. Fix status was judged against commits since 42e569d9d (landed diffs and the probes named in the reason, not full test runs).

| sym | verdict | reason | file |
|---|---|---|---|
| `my.agent/a-refused-retire-leaves-the-agent-admission-closed` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/my.agent.edn |
| `my.auditchange/faults of lint2` | DROP | auto `faults of` task (owner ruled no auto tasks; error records reset) | - |
| `my.fs/archive-file-reads-have-no-explicit-source-world` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/my.fs.edn |
| `my.fs/faults of read` | DROP | auto `faults of` task (owner ruled no auto tasks; error records reset) | - |
| `my.fs/faults of write!` | DROP | auto `faults of` task (owner ruled no auto tasks; error records reset) | - |
| `my.program/faults of diff` | DROP | auto `faults of` task (owner ruled no auto tasks; error records reset) | - |
| `my.program/source-repeats-the-docstring` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/my.program.edn |
| `my.shell/faults of run!` | DROP | auto `faults of` task (owner ruled no auto tasks; error records reset) | - |
| `my.task/open-offers-no-authored-tasks` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/my.task.edn |
| `my.task/open-takes-over-a-second-validating-query-variables` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/my.task.edn |
| `refusal-cause.probe/faults of bad-err` | DROP | auto `faults of` task (owner ruled no auto tasks; error records reset) | - |
| `scratch.add/add-one-returns-its-argument-plus-one` | DROP | probe residue (scratch.add, scratch.add2) | - |
| `scratch.add2/add-one-returns-its-argument-plus-one` | DROP | probe residue (scratch.add, scratch.add2) | - |
| `scratch.error-delivery/faults of probe-bad` | DROP | auto `faults of` task (owner ruled no auto tasks; error records reset) | - |
| `scratch.sd-probe/faults of measure` | DROP | auto `faults of` task (owner ruled no auto tasks; error records reset) | - |
| `seon.ai/the-shipped-stop-sequence-cuts-replies-that-contain-it` | DROP | fixed by 8c8256467 (shipped stop is now the REPL result marker `user=>`, not `#:seon.repl`); 722f110df records the cut is undetectable from DeepSeek's response | - |
| `seon.batch/an-earlier-redefinition-of-a-host-function-fails-as-a-core-fault` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.batch.edn |
| `seon.cluster.agent/interrupted? re-walks the cause chain` | KEEP | real defect; name rewritten to a valid symbol: `interrupted-rewalks-the-cause-chain` | resources/seon/tasks/seon.cluster.agent.edn |
| `seon.cluster.agent/reply-size-is-measured-by-printing-the-sources` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.cluster.agent.edn |
| `seon.cluster.boot/faults of memory-pressure-crossed!` | DROP | auto `faults of` task (owner ruled no auto tasks; error records reset) | - |
| `seon.cluster.prompt/merge 02b3f1f75a77` | DROP | auto merge task | - |
| `seon.cluster.registry/faults of retire-branch!` | DROP | auto `faults of` task (owner ruled no auto tasks; error records reset) | - |
| `seon.cluster.source/index-issues-drops-every-opened-date-in-a-git-less-archive` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.cluster.source.edn |
| `seon.cluster.store/the-store-index-roots-carry-no-subtree-count` | DROP | duplicate of `seon.cluster.store/index-roots-lose-their-subtree-counts` (same defect); its growth-notes evidence merged into that entry | - |
| `seon.contracts-compile-test/edit-hook-refuses-a-lawful-throw-of-a-registered-error` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.contracts-compile-test.edn |
| `seon.contracts-compile-test/empty-input-calls-analyzer-with-a-refused-empty-source-map` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.contracts-compile-test.edn |
| `seon.db/a-probe-below-the-seek-break-even-scans-the-attribute` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.db.edn |
| `seon.db/a-variable-attribute-rule-query-reads-942-nodes-for-no-rows` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.db.edn |
| `seon.db/an-agent-transact-reports-the-runs-open-datoms` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.db.edn |
| `seon.db/an-identity-join-scans-the-whole-ref-attribute` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.db.edn |
| `seon.db/an-or-clause-joined-to-a-pattern-costs-a-scan-per-binding` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.db.edn |
| `seon.db/faults of q` | DROP | auto `faults of` task (owner ruled no auto tasks; error records reset) | - |
| `seon.db/faults of transact!` | DROP | auto `faults of` task (owner ruled no auto tasks; error records reset) | - |
| `seon.db/fork-datahike-suite-has-thirteen-reds` | DROP | fixed in fork 6f3b61f6 (dh-fix notes, the thirteen reds table) | - |
| `seon.db/fork-optimistic-tx-report-test-races-its-listener` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.db.edn |
| `seon.db/fork-planner-regression-bench-never-finishes` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.db.edn |
| `seon.db/fused-joins-with-a-predicate-answer-unprojected-tuples` | DROP | fixed in fork 49e681db (fused joins project attached predicates) | - |
| `seon.db/history-joins-drop-matching-datoms` | DROP | fixed in fork 77c1228e (dh-fix notes section 2) | - |
| `seon.db/history-joins-ignore-shared-added-flags` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.db.edn |
| `seon.db/planned-recursive-rules-on-history-disagree-with-the-base-engine` | DROP | fixed in fork 84790323 (dh-fix notes section 1) | - |
| `seon.db/planner-estimates-load-every-node-of-the-counted-range` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.db.edn |
| `seon.db/q-hands-a-non-query-map-to-refused-read` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.db.edn |
| `seon.db/query-plans-are-reused-across-database-sizes` | DROP | fixed in fork 5fcfc6a7 (size class in the plan cache key; dh-fix notes section 3) | - |
| `seon.db/recursive-rule-range-predicates-throw` | DROP | fixed in fork f3d01c16 (variable comparisons kept out of literal bounds; regression bound-range-predicates-preserve-answers) | - |
| `seon.db/rule-arguments-resolve-lookup-shaped-data-as-entities` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.db.edn |
| `seon.dev.mcp/large-source-request-fails-in-the-bridge` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.dev.mcp.edn |
| `seon.dev.mcp/the-timeout-remedy-names-a-parameter-eval-clj-does-not-declare` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.dev.mcp.edn |
| `seon.error/a-fault-without-an-operation-is-recorded-under-normalize` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.error.edn |
| `seon.error/an-error-render-drops-the-members-that-say-what-is-missing` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.error.edn |
| `seon.error/fault-task-closing-has-no-writer-left` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.error.edn |
| `seon.error.refusal/a-deep-unchanged-child-under-a-rebuilt-parent-overflows-the-stack` | DROP | fixed by 31a53349a: `seon.error.refusal/references` on a 100,000-deep child beside a place returned data on default | - |
| `seon.error.refusal/closing-a-rebuilt-container-hashes-opaque-leaves` | DROP | fixed by 31a53349a: a promise beside a self-containing ArrayList and a lazy seq beside a place return data on default | - |
| `seon.error.refusal/identity-folding-assumes-stable-projectors` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.error.refusal.edn |
| `seon.error.refusal/the-error-data-walk-allocates-a-kilobyte-per-collection-node` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.error.refusal.edn |
| `seon.eval/of-agent-default-selector-refuses-on-roots-stored-read-plans` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.eval.edn |
| `seon.eval/of-agent-default-selector-refuses-on-stored-read-plans` | DROP | duplicate of `seon.eval/of-agent-default-selector-refuses-on-roots-stored-read-plans` (same refusal, same call) | - |
| `seon.fault/a-delivered-fault-names-the-normalizer-not-its-boundary` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.fault.edn |
| `seon.fn/backticked-docstring-references-have-no-index` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.fn.edn |
| `seon.fn.analyzer/fork-source-lint-misreads-local-macro-bindings` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.fn.analyzer.edn |
| `seon.mcp/jvm-mode-replies-omit-time-calls-and-location` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.mcp.edn |
| `seon.operator/a-candidate-that-babashka-cannot-load-passes-the-check` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.operator.edn |
| `seon.print/a-dig-form-through-a-list-index-reads-nil` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.print.edn |
| `seon.print/multiline-literals-hide-line-breaks-from-layout` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.print.edn |
| `seon.problems/an-agents-own-form-errors-are-assigned-to-another-agent` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.problems.edn |
| `seon.profile/begin-validates-every-cell-with-armed-predicates` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.profile.edn |
| `seon.program/sci-scratch-definitions-refuse-their-produced-digest` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.program.edn |
| `seon.render/faults of unknown` | DROP | auto `faults of` task (owner ruled no auto tasks; error records reset) | - |
| `seon.render.ns/a-doc-line-is-the-first-physical-line-not-the-first-sentence` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.render.ns.edn |
| `seon.render.ns/function-source-lines-print-several-per-line` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.render.ns.edn |
| `seon.render.test/a-test-render-omits-what-the-test-asserts` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.render.test.edn |
| `seon.render.transcript/delivered-errors-carry-no-result-handle` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.render.transcript.edn |
| `seon.render.value/cuts-cost-more-than-they-hide-across-siblings` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.render.value.edn |
| `seon.render.value/failed-selection-escapes-the-profile-budget` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.render.value.edn |
| `seon.render.value/printing-thirty-two-numbers-allocates-megabytes` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.render.value.edn |
| `seon.render.value/renderer-tests-assume-retired-text-layout` | DROP | fixed by 94120771d (both named tests rewritten to assert data) | - |
| `seon.render.value/source-display-changes-two-functions-without-contracts` | DROP | fixed: `seon.print/leaf-ops` and `value-test/agent-unit` carry contracts (ffb788f52, 94120771d) | - |
| `seon.render.value/string-admission-compares-raw-and-escaped-cost` | DROP | fixed by ffb788f52 (escaped strings admitted by escaped cost) | - |
| `seon.render.value/string-fill-arms-a-call-for-each-character` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.render.value.edn |
| `seon.render.web/the-built-stylesheet-is-stale-against-its-source` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.render.web.edn |
| `seon.repl/a-def-bound-to-an-error-value-shows-only-the-var` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.repl.edn |
| `seon.repl/a-ref-from-another-branch-prints-with-the-wrong-identity` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.repl.edn |
| `seon.repl/a-selected-render-arrives-as-an-escaped-string` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.repl.edn |
| `seon.repl/every-def-repeats-the-same-persistence-note` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.repl.edn |
| `seon.schema/contract-check-on-three-files-exceeds-one-second` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.schema.edn |
| `seon.schema.edn/faults of unreadable-file!` | DROP | auto `faults of` task (owner ruled no auto tasks; error records reset) | - |
| `seon.sci.arm/a-def-without-line-metadata-refuses-its-origin` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.sci.arm.edn |
| `seon.sci.arm/faults of write-event` | DROP | auto `faults of` task (owner ruled no auto tasks; error records reset) | - |
| `seon.sci.eval/a-private-function-def-disappears-between-mcp-evaluations` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.sci.eval.edn |
| `seon.sci.eval/a-stack-overflow-in-the-error-path-reaches-shown-failure-as-nil` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.sci.eval.edn |
| `seon.sci.eval/dependency-namespaces-have-no-doc-or-dir-in-an-agents-repl` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.sci.eval.edn |
| `seon.sci.eval/diagnostics-dig-forms-point-into-the-value` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.sci.eval.edn |
| `seon.sci.eval/dir-shows-each-uncontracted-def-as-two-timestamped-errors` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.sci.eval.edn |
| `seon.sci.eval/loading-source-arms-tens-of-thousands-of-calls` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.sci.eval.edn |
| `seon.sci.eval/run-test-var-throws-on-an-sci-test-var` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.sci.eval.edn |
| `seon.sci.forgive/a-prompt-marker-is-stripped-and-its-namespace-ignored` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.sci.forgive.edn |
| `seon.sci.forgive/a-prose-repair-replays-the-whole-reply` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.sci.forgive.edn |
| `seon.sci.forgive/prose-after-the-last-form-is-dropped` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.sci.forgive.edn |
| `seon.sci.forgive/structural-trials-refuse-var-quote-anonymous-fn-deref-regex-and-syntax-quote` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.sci.forgive.edn |
| `seon.sci.kernel/an-agent-exception-has-no-error-type` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.sci.kernel.edn |
| `seon.sci.kernel/faults of adopt-arm` | DROP | auto `faults of` task (owner ruled no auto tasks; error records reset) | - |
| `seon.sci.reader/prose-in-a-reply-is-evaluated-as-symbols` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.sci.reader.edn |
| `seon.task/task-text-references-are-never-resolved` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.task.edn |
| `seon.test/a-check-answer-shows-digests-the-agent-cannot-use` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.test.edn |
| `seon.test/a-stale-stored-definition-digest-refuses-every-test-world` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.test.edn |
| `seon.test/an-agent-branch-sees-none-of-its-clusters-test-evidence` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.test.edn |
| `seon.test-support/code-request-frames-an-empty-source-every-form-is-refused` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.test-support.edn |
| `seon.test-support/evaluate-answers-nil-for-a-refused-form` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.test-support.edn |
| `seon.test.runner/a-test-error-shows-runner-frames-not-the-throw-site` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.test.runner.edn |
| `seon.turn/a-deferred-wake-is-answered-by-silence` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.turn.edn |
| `seon.turn/a-markdown-heading-in-a-reply-is-read-as-code` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.turn.edn |
| `seon.turn/a-message-from-root-never-refills-the-turn-bound` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.turn.edn |
| `seon.turn/a-prose-only-reply-evaluates-its-first-word` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.turn.edn |
| `seon.turn/faults of record-write-refusal-fault!` | DROP | auto `faults of` task (owner ruled no auto tasks; error records reset) | - |
| `seon.turn/faults of show-row` | DROP | auto `faults of` task (owner ruled no auto tasks; error records reset) | - |
| `seon.turn/generated-opening-reads-fail-when-evaluated-as-written` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.turn.edn |
| `seon.turn/generated-render-sources-are-read-as-model-replies` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.turn.edn |
| `seon.turn.batch/every-form-is-analyzed-because-its-result-handle-is-a-binding` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.turn.batch.edn |
| `seon.turn.batch/tests-read-forms-through-a-stage-no-entrant-uses` | KEEP | real defect or work item filed by a lane or root | resources/seon/tasks/seon.turn.batch.edn |
| `user/faults of bad3` | DROP | auto `faults of` task (owner ruled no auto tasks; error records reset) | - |
| `user/faults of bad4` | DROP | auto `faults of` task (owner ruled no auto tasks; error records reset) | - |
| `user/work of lane-ots` | DROP | auto `work of` task | - |
| `user/work of review-astra-batch-b` | DROP | auto `work of` task | - |

## Counts

122 read: 83 KEEP, 39 DROP (23 auto `faults of`/`merge`/`work of`, 2 probe residue, 2 duplicates, 12 fixed since 42e569d9d).

## Design check

The assumption held: no kept task shared a name with a file entry; one shared a defect (subtree counts) and was merged. Awkward: my first write touched the files before `bin/lane claim`, which refuses unclaimed changes, so I restored HEAD copies of my own edits, claimed, and re-applied. After the reset a regeneration script rebuilt the appended entries from the dump. One name (`interrupted? re-walks the cause chain`) was not a symbol name and was renamed. Fix judgments rest on landed diffs plus two probes on default (`seon.error.refusal/references` accepts a 100,000-deep child and a cyclic ArrayList beside a promise); `seon.error.refusal/the-error-data-walk-allocates-a-kilobyte-per-collection-node` was re-measured at about 1,800 bytes per node and stays KEEP. `seon.batch/an-earlier-redefinition-of-a-host-function-fails-as-a-core-fault` stays KEEP: df8f65379 only checks redefinition within the batch.
