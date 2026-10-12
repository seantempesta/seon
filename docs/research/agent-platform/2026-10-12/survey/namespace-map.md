---
title: Namespace map: size, shape and churn of src and script
type: research
status: measured once at HEAD; evidence only, no design judgement
basis: HEAD 45bfab18e on refactor/agent-platform; default not queried (no eval_clj calls); churn window since 2026-10-05
method: scratch script namespace_map.py (python3, run with -I) under the session scratchpad survey/ directory, not committed. It reads every .clj/.cljc under src/ and script/, masks strings, comments, char literals and regexes, and takes top-level defn/defn- form spans as body lines. Churn and reverts: `git log --since=2026-10-05 --format=%s -- FILE` (subject prefix Revert). Tasks: `#:seon.task{:name` entries in resources/seon/tasks/NS.edn. Tagged proofs: `ns/name` tokens after `:seon/tasks` in test/ and script/. Totals checked with `cat $(find src -name '*.clj*') | grep -c -v '^\s*$'`.
---

## For root

- Size: src/ is 89,247 non-blank lines in 111 namespaces with 3,928 defn/defn-, against the about-10,000-line budget (CLAUDE.md, The goal, point 4): 8.9x. script/ is 5,956 lines in 23 namespaces; `seon.operator` alone is 2,246.
- Top 15 by dragon score (src): `seon.turn` 154.0; `seon.sci.eval` 143.6; `seon.db` 109.9; `seon.test` 100.0; `seon.fn` 73.5; `seon.schema` 67.6; `seon.cluster.agent` 64.2; `seon.test.runner` 61.8; `seon.cluster` 55.1; `seon.error` 54.1; `seon.cluster.source` 53.4; `seon.context` 51.8; `seon.task` 48.6; `seon.render.web` 47.5; `my.program` 46.1.
- Longest bodies: `seon.test/select` 435; `seon.sci.eval/evaluate` 365; `seon.test/run-request` 364; `seon.turn/step` 305; `seon.db/write-owned-values-error` 247. 123 src functions exceed 80 body lines, 12 exceed 200.
- Churn 7d (commits touching the file): `seon.turn` 88; `seon.sci.eval` 87; `seon.test` 57; `seon.db` 44; `seon.context` 43; 1,069 file-commits across src. Reverts 7d, one each: `seon.turn`, `seon.test`, `seon.test.runner`, `seon.cluster.source`, `seon.cluster.message`, `seon.task`, `my.program`, `seon.context`, `seon.cluster.branch-evidence`.
- Fan-in: `seon.error.refusal` 67; `seon.db` 66; `seon.schema` 51; `seon.schema.edn` 49; `seon.id` 33. Fan-out: `seon.turn` 35; `seon.cluster` 32; `seon.cluster.agent` 29; `seon.render.web` 28; `seon.sci.eval` 26. 19 src namespaces have no src requirer.
- Already filed: `seon.turn/step` (`a-turn-pass-derives-its-work-twice`, seon.turn.edn); `seon.sci.eval/evaluate` (`program-movement-is-checked-three-ways-per-form`, seon.sci.eval.edn); `seon.test/select` (`one-test-run-costs-seconds-around-a-147-ms-body`, seon.test.edn); `seon.db/write-owned-values-error` (`the-checked-writer-is-slow-on-a-fresh-branch`, seon.db.edn); `seon.fn/contract-findings` (`contract-findings-has-no-scope`, seon.fn.edn); `seon.schema/projection-with-declarations` (`arming-projection-is-rebuilt-per-caller-within-one-form`, seon.sci.arm.edn).
- Not filed (no task names the symbol, body lines): `seon.effect/request*` 237; `seon.schema/assert-complete-contract!` 181; `seon.schema/projection-build` 180; `seon.render.value/project` 163. 44 src functions over 80 lines have no task reference at all. Tasks: 798 entries in resources/seon/tasks, 765 map to a src or script namespace, 155 of those carry a tagged proof.

## Table

### src/ (111 namespaces, 89,247 non-blank lines, 3,928 defn/defn-)

| Namespace | File | Lines | defn | Longest 3 (body lines) | Fan-in | Fan-out | Churn 7d | Reverts 7d (all) | Tasks (proved) | Dragon score |
|---|---|--:|--:|---|--:|--:|--:|--:|--:|--:|
| `seon.db` | `src/seon/db.clj` | 5969 | 257 | `seon.db/write-owned-values-error` 247; `seon.db/query-index-patterns` 137; `seon.db/carried-projection` 106 | 66 | 10 | 44 | 0 (2) | 48 (11) | 109.9 |
| `seon.turn` | `src/seon/turn.clj` | 5641 | 206 | `seon.turn/step` 305; `seon.turn/call-turn` 219; `seon.turn/resume-turn` 202 | 9 | 35 | 88 | 1 (1) | 49 (13) | 154.0 |
| `seon.sci.eval` | `src/seon/sci/eval.clj` | 4750 | 182 | `seon.sci.eval/evaluate` 365; `seon.sci.eval/install-row!` 208; `seon.sci.eval/install-selected!` 126 | 10 | 26 | 87 | 0 (1) | 81 (21) | 143.6 |
| `seon.fn` | `src/seon/fn.clj` | 4061 | 155 | `seon.fn/contract-findings` 156; `seon.fn/index!` 150; `seon.fn/var-entity` 142 | 13 | 16 | 29 | 0 (2) | 29 (2) | 73.5 |
| `seon.schema` | `src/seon/schema.clj` | 4045 | 173 | `seon.schema/projection-with-declarations` 245; `seon.schema/assert-complete-contract!` 181; `seon.schema/projection-build` 180 | 51 | 4 | 21 | 0 (2) | 16 (2) | 67.6 |
| `seon.render.web` | `src/seon/render/web.clj` | 3402 | 147 | `seon.render.web/acquire-debug-data` 139; `seon.render.web/render-step` 132; `seon.render.web/feed` 108 | 1 | 28 | 10 | 0 (0) | 14 (5) | 47.5 |
| `seon.cluster` | `src/seon/cluster.clj` | 3390 | 114 | `seon.cluster/full-source-refresh!` 169; `seon.cluster/populate-source!` 159; `seon.cluster/development-source-refresh!` 133 | 3 | 32 | 17 | 0 (2) | 13 (1) | 55.1 |
| `seon.test` | `src/seon/test.clj` | 3012 | 79 | `seon.test/select` 435; `seon.test/run-request` 364; `seon.test/admit-run` 230 | 6 | 23 | 57 | 1 (1) | 57 (11) | 100.0 |
| `seon.error` | `src/seon/error.clj` | 2673 | 118 | `seon.error/prepare` 175; `seon.error/commit-call` 152; `seon.error/reply-value` 130 | 20 | 13 | 23 | 0 (2) | 19 (3) | 54.1 |
| `seon.test.runner` | `src/seon/test/runner.clj` | 2325 | 94 | `seon.test.runner/record-latest-tx` 183; `seon.test.runner/complete-members` 143; `seon.test.runner/restore-live-cluster-schema!` 85 | 8 | 15 | 32 | 1 (1) | 13 (3) | 61.8 |
| `seon.cluster.agent` | `src/seon/cluster/agent.clj` | 2206 | 81 | `seon.cluster.agent/acquire-context!` 207; `seon.cluster.agent/start-claimed-flow!` 107; `seon.cluster.agent/submit-source-in-projection` 90 | 5 | 29 | 37 | 0 (1) | 14 (4) | 64.2 |
| `seon.render.value` | `src/seon/render/value.clj` | 1785 | 89 | `seon.render.value/project` 163; `seon.render.value/classify` 115; `seon.render.value/make-node` 70 | 24 | 10 | 14 | 0 (0) | 12 (0) | 35.9 |
| `seon.render` | `src/seon/render.clj` | 1635 | 87 | `seon.render/render-call` 168; `seon.render/declared-projection` 83; `seon.render/invoke-selected` 50 | 8 | 15 | 7 | 0 (1) | 11 (0) | 27.6 |
| `seon.cluster.source` | `src/seon/cluster/source.clj` | 1628 | 71 | `seon.cluster.source/publish!` 163; `seon.cluster.source/accept-merge!` 111; `seon.cluster.source/prepare-merge!` 99 | 8 | 14 | 31 | 1 (1) | 24 (7) | 53.4 |
| `seon.program` | `src/seon/program.cljc` | 1563 | 58 | `seon.program/declaration-row` 80; `seon.program/contract-facts` 68; `seon.program/three-way` 58 | 13 | 8 | 17 | 0 (1) | 7 (2) | 34.6 |
| `seon.instrument` | `src/seon/instrument.clj` | 1286 | 37 | `seon.instrument/violation` 174; `seon.instrument/compiled-wrapper` 115; `seon.instrument/apply!` 107 | 5 | 14 | 12 | 0 (3) | 10 (1) | 29.2 |
| `seon.ai` | `src/seon/ai.clj` | 1256 | 67 | `seon.ai/send-request` 129; `seon.ai/stream-event` 67; `seon.ai/complete` 63 | 4 | 6 | 2 | 0 (0) | 5 (1) | 17.8 |
| `seon.render.walk` | `src/seon/render/walk.clj` | 1186 | 46 | `seon.render.walk/neighborhood` 108; `seon.render.walk/acquisition-members` 93; `seon.render.walk/acquired-tree` 60 | 1 | 15 | 5 | 0 (1) | 0 (0) | 19.6 |
| `seon.schema.datahike` | `src/seon/schema/datahike.clj` | 1088 | 68 | `seon.schema.datahike/compiled-storage` 73; `seon.schema.datahike/encode-transaction-data-in` 54; `seon.schema.datahike/attribute-declaration` 43 | 10 | 6 | 7 | 0 (0) | 5 (1) | 19.7 |
| `seon.call-preparation` | `src/seon/call_preparation.clj` | 1086 | 36 | `seon.call-preparation/prepare` 94; `seon.call-preparation/derive-snapshot` 73; `seon.call-preparation/compiled-plan` 67 | 3 | 5 | 9 | 0 (2) | 3 (0) | 22.2 |
| `seon.turn.batch` | `src/seon/turn/batch.clj` | 1075 | 58 | `seon.turn.batch/derive-form` 80; `seon.turn.batch/schema-tx` 42; `seon.turn.batch/settle` 39 | 2 | 21 | 23 | 0 (0) | 13 (0) | 35.8 |
| `seon.render.transcript` | `src/seon/render/transcript.clj` | 1056 | 55 | `seon.render.transcript/render-runtime-html` 57; `seon.render.transcript/agent-history-under` 51; `seon.render.transcript/held-entry` 43 | 3 | 13 | 9 | 0 (1) | 2 (1) | 21.0 |
| `seon.sci.kernel` | `src/seon/sci/kernel.clj` | 989 | 49 | `seon.sci.kernel/failure-value` 97; `seon.sci.kernel/invoke` 84; `seon.sci.kernel/own-arm` 43 | 13 | 6 | 8 | 0 (0) | 10 (1) | 20.3 |
| `seon.config` | `src/seon/config.clj` | 917 | 37 | `seon.config/admit-initialization-entities` 82; `seon.config/apply-compiled!` 72; `seon.config/compile-settings` 52 | 25 | 9 | 4 | 0 (1) | 5 (2) | 15.2 |
| `seon.batch` | `src/seon/batch.clj` | 911 | 54 | `seon.batch/evaluate-step` 102; `seon.batch/evaluate-batch` 68; `seon.batch/derive-step` 43 | 15 | 1 | 23 | 0 (0) | 7 (4) | 34.7 |
| `seon.effect` | `src/seon/effect.clj` | 908 | 32 | `seon.effect/request*` 237; `seon.effect/dispatch` 76; `seon.effect/settle-call` 61 | 5 | 15 | 1 | 0 (0) | 2 (0) | 16.0 |
| `seon.fn.analyzer` | `src/seon/fn/analyzer.clj` | 904 | 47 | `seon.fn.analyzer/analyze` 128; `seon.fn.analyzer/parsed-facts` 62; `seon.fn.analyzer/attributed-usages` 41 | 6 | 1 | 5 | 0 (2) | 4 (0) | 17.2 |
| `seon.flow` | `src/seon/flow.clj` | 896 | 37 | `seon.flow/var-process` 105; `seon.flow/submit!` 84; `seon.flow/stop-work-launcher!` 57 | 7 | 12 | 10 | 0 (0) | 8 (2) | 21.6 |
| `seon.cluster.message` | `src/seon/cluster/message.clj` | 881 | 41 | `seon.cluster.message/render-html` 64; `seon.cluster.message/delivery` 63; `seon.cluster.message/reply` 49 | 8 | 5 | 8 | 1 (3) | 6 (2) | 20.4 |
| `seon.task` | `src/seon/task.clj` | 859 | 59 | `seon.task/assign!` 42; `seon.task/create!` 32; `seon.task/delete-branch!` 28 | 7 | 7 | 37 | 1 (1) | 17 (8) | 48.6 |
| `my.program` | `src/my/program.clj` | 856 | 48 | `my.program/breaks` 61; `my.program/history` 54; `my.program/ns-unalias!` 48 | 2 | 11 | 34 | 1 (2) | 34 (5) | 46.1 |
| `seon.fs.jvm` | `src/seon/fs/jvm.clj` | 853 | 42 | `seon.fs.jvm/write` 63; `seon.fs.jvm/read-opened` 58; `seon.fs.jvm/glob` 49 | 2 | 3 | 1 | 0 (0) | 0 (0) | 11.1 |
| `seon.sci.reader` | `src/seon/sci/reader.cljc` | 819 | 45 | `seon.sci.reader/read-events` 78; `seon.sci.reader/read` 70; `seon.sci.reader/function-declaration` 54 | 7 | 0 | 5 | 0 (0) | 4 (0) | 15.1 |
| `seon.print` | `src/seon/print.cljc` | 807 | 47 | `seon.print/collection-work` 53; `seon.print/leaf-ops` 46; `seon.print/elision` 38 | 14 | 4 | 7 | 0 (0) | 9 (1) | 16.4 |
| `seon.cluster.boot` | `src/seon/cluster/boot.clj` | 805 | 24 | `seon.cluster.boot/request!` 175; `seon.cluster.boot/stand-boot-layers!` 116; `seon.cluster.boot/stand-cluster-runtime!` 97 | 1 | 22 | 13 | 0 (1) | 4 (1) | 25.4 |
| `seon.sci.admit` | `src/seon/sci/admit.clj` | 768 | 40 | `seon.sci.admit/open-node` 120; `seon.sci.admit/frame-advance` 56; `seon.sci.admit/project` 51 | 13 | 6 | 4 | 0 (0) | 1 (0) | 14.7 |
| `seon.schedule` | `src/seon/schedule.clj` | 742 | 33 | `seon.schedule/fire-call` 107; `seon.schedule/schedule-step` 61; `seon.schedule/fire-due!` 55 | 2 | 9 | 2 | 0 (1) | 5 (0) | 12.1 |
| `seon.cluster.registry` | `src/seon/cluster/registry.clj` | 736 | 32 | `seon.cluster.registry/referenced-blobs` 59; `seon.cluster.registry/fork!` 48; `seon.cluster.registry/branch!` 47 | 15 | 8 | 8 | 0 (0) | 8 (1) | 16.8 |
| `seon.cluster.store` | `src/seon/cluster/store.clj` | 728 | 31 | `seon.cluster.store/open-store!` 106; `seon.cluster.store/datahike-configuration` 63; `seon.cluster.store/release-store!` 38 | 6 | 6 | 8 | 0 (0) | 9 (2) | 17.9 |
| `seon.error.refusal` | `src/seon/error/refusal.clj` | 724 | 39 | `seon.error.refusal/referenced` 61; `seon.error.refusal/chain` 48; `seon.error.refusal/error` 47 | 67 | 1 | 10 | 0 (0) | 7 (1) | 18.8 |
| `seon.problems` | `src/seon/problems.clj` | 680 | 30 | `seon.problems/html-report` 82; `seon.problems/ai-prose` 58; `seon.problems/stale-vars` 42 | 2 | 12 | 11 | 0 (0) | 5 (2) | 19.9 |
| `seon.health` | `src/seon/health.clj` | 663 | 33 | `seon.health/report` 55; `seon.health/derived` 41; `seon.health/boot-comparisons` 32 | 3 | 11 | 9 | 0 (0) | 2 (0) | 17.0 |
| `seon.schema.edn` | `src/seon/schema/edn.clj` | 649 | 29 | `seon.schema.edn/admit` 108; `seon.schema.edn/read-schema-resource` 47; `seon.schema.edn/directory-resource-paths` 46 | 49 | 4 | 3 | 0 (1) | 2 (1) | 12.2 |
| `seon.profile` | `src/seon/profile.clj` | 644 | 30 | `seon.profile/measure` 70; `seon.profile/selected` 41; `seon.profile/transaction-observation` 40 | 5 | 2 | 20 | 0 (1) | 7 (2) | 28.2 |
| `seon.maintenance` | `src/seon/maintenance.clj` | 643 | 29 | `seon.maintenance/collect-store!` 81; `seon.maintenance/root-verification` 48; `seon.maintenance/dry-run-store!` 47 | 2 | 7 | 1 | 0 (0) | 6 (0) | 9.5 |
| `seon.repl` | `src/seon/repl.clj` | 574 | 28 | `seon.repl/entity-emission` 89; `seon.repl/response-entries` 68; `seon.repl/syntax-tokens` 43 | 4 | 6 | 16 | 0 (0) | 14 (1) | 24.0 |
| `seon.context` | `src/seon/context.clj` | 558 | 29 | `seon.context/current-state` 50; `seon.context/message-custody` 47; `seon.context/ended` 39 | 4 | 17 | 43 | 1 (1) | 15 (8) | 51.8 |
| `seon.sci.lookup` | `src/seon/sci/lookup.clj` | 539 | 32 | `seon.sci.lookup/docstring-parts` 39; `seon.sci.lookup/directory-value` 36; `seon.sci.lookup/agent-documentation-contract` 33 | 5 | 7 | 8 | 0 (0) | 4 (2) | 14.4 |
| `seon.cluster.wake` | `src/seon/cluster/wake.clj` | 518 | 16 | `seon.cluster.wake/route!` 114; `seon.cluster.wake/delivery` 50; `seon.cluster.wake/wake-matchers` 40 | 6 | 6 | 2 | 0 (0) | 1 (0) | 10.0 |
| `seon.schema.internal` | `src/seon/schema/internal.cljc` | 517 | 21 | `seon.schema.internal/assert-complete-schema!` 98; `seon.schema.internal/assert-error-declaration!` 71; `seon.schema.internal/assert-compilable-schema!` 58 | 15 | 0 | 2 | 0 (0) | 0 (0) | 9.6 |
| `seon.mcp` | `src/seon/mcp.clj` | 488 | 22 | `seon.mcp/jvm-run` 45; `seon.mcp/execution` 42; `seon.mcp/call` 36 | 0 | 22 | 21 | 0 (0) | 6 (2) | 27.0 |
| `seon.render.hiccup` | `src/seon/render/hiccup.clj` | 480 | 16 | `seon.render.hiccup/shorthand` 75; `seon.render.hiccup/hiccup?` 59; `seon.render.hiccup/->string` 40 | 2 | 3 | 1 | 0 (0) | 0 (0) | 7.7 |
| `seon.schema.admission` | `src/seon/schema/admission.clj` | 477 | 26 | `seon.schema.admission/admit` 81; `seon.schema.admission/house-findings` 43; `seon.schema.admission/registration-finding` 41 | 0 | 3 | 2 | 0 (0) | 0 (0) | 8.8 |
| `seon.sci.forgive` | `src/seon/sci/forgive.clj` | 457 | 22 | `seon.sci.forgive/scan` 184; `seon.sci.forgive/literal-line?` 25; `seon.sci.forgive/segments` 23 | 3 | 1 | 10 | 0 (0) | 3 (1) | 19.2 |
| `seon.edit` | `src/seon/edit.clj` | 444 | 31 | `seon.edit/exact` 51; `seon.edit/form` 50; `seon.edit/lines` 44 | 2 | 1 | 1 | 0 (0) | 0 (0) | 6.7 |
| `seon.shell.jvm` | `src/seon/shell/jvm.clj` | 436 | 17 | `seon.shell.jvm/execute` 62; `seon.shell.jvm/copy-blob-stdin!` 58; `seon.shell.jvm/await-exit` 35 | 0 | 8 | 1 | 0 (0) | 0 (0) | 6.9 |
| `seon.reconcile` | `src/seon/reconcile.cljc` | 432 | 25 | `seon.reconcile/plan-transaction-data` 95; `seon.reconcile/entity-exact-tx` 42; `seon.reconcile/reconcile!` 29 | 1 | 3 | 2 | 0 (0) | 1 (1) | 8.7 |
| `seon.web.jvm` | `src/seon/web/jvm.clj` | 431 | 27 | `seon.web.jvm/fetch` 93; `seon.web.jvm/search` 91; `seon.web.jvm/bounded-input` 35 | 0 | 4 | 1 | 0 (0) | 1 (0) | 7.6 |
| `seon.fs` | `src/seon/fs.clj` | 423 | 25 | `seon.fs/admit-destructive-path!` 75; `seon.fs/delete-recursively-impl!` 67; `seon.fs/delete-recursively!` 37 | 8 | 1 | 3 | 0 (0) | 1 (1) | 9.1 |
| `seon.fault` | `src/seon/fault.clj` | 412 | 11 | `seon.fault/record!` 175; `seon.fault/fault!` 94; `seon.fault/carried-receipt` 24 | 11 | 8 | 7 | 0 (0) | 5 (0) | 15.5 |
| `seon.blob` | `src/seon/blob.clj` | 399 | 27 | `seon.blob/stage-binary!` 63; `seon.blob/get` 25; `seon.blob/publish-staged!` 24 | 14 | 4 | 3 | 0 (0) | 0 (0) | 8.6 |
| `seon.eval.drive` | `src/seon/eval/drive.clj` | 377 | 17 | `seon.eval.drive/run-episode!` 58; `seon.eval.drive/terminal-state` 57; `seon.eval.drive/run-sample!` 57 | 0 | 8 | 3 | 0 (0) | 1 (0) | 8.2 |
| `seon.env` | `src/seon/env.clj` | 374 | 21 | `seon.env/declared-member-entities` 33; `seon.env/scope` 31; `seon.env/construct` 27 | 22 | 3 | 0 | 0 (0) | 0 (0) | 4.6 |
| `seon.ref` | `src/seon/ref.clj` | 353 | 22 | `seon.ref/resolve-name` 37; `seon.ref/section` 36; `seon.ref/parsed` 27 | 2 | 11 | 9 | 0 (0) | 4 (1) | 13.5 |
| `seon.render.ns` | `src/seon/render/ns.clj` | 307 | 30 | `seon.render.ns/agent-namespace-ai` 20; `seon.render.ns/function-ai` 17; `seon.render.ns/render-ai` 17 | 0 | 6 | 8 | 0 (2) | 2 (0) | 11.6 |
| `seon.cluster.process` | `src/seon/cluster/process.clj` | 302 | 20 | `seon.cluster.process/run-process!` 101; `seon.cluster.process/terminate-subprocess!` 34; `seon.cluster.process/start-instant-unavailable!` 16 | 8 | 1 | 0 | 0 (0) | 0 (0) | 5.5 |
| `seon.merge` | `src/seon/merge.clj` | 298 | 13 | `seon.merge/merge!` 86; `seon.merge/squash-merge` 57; `seon.merge/merge-tx` 22 | 2 | 9 | 2 | 0 (0) | 7 (2) | 7.1 |
| `seon.cache` | `src/seon/cache.clj` | 279 | 17 | `seon.cache/lookup` 39; `seon.cache/lookup-within` 34; `seon.cache/settle!` 28 | 16 | 0 | 1 | 0 (0) | 1 (0) | 4.8 |
| `seon.id` | `src/seon/id.clj` | 277 | 11 | `seon.id/structural-digest` 118; `seon.id/print-canonical!` 61; `seon.id/id` 16 | 33 | 0 | 3 | 0 (0) | 0 (0) | 8.7 |
| `seon.agent` | `src/seon/agent.clj` | 241 | 12 | `seon.agent/render-settings-html` 78; `seon.agent/effective-settings` 29; `seon.agent/identity` 22 | 1 | 4 | 2 | 0 (0) | 0 (0) | 6.4 |
| `seon.test.cache` | `src/seon/test/cache.clj` | 235 | 16 | `seon.test.cache/declared-input-roots` 24; `seon.test.cache/changed-inputs` 22; `seon.test.cache/file-input-digests` 18 | 3 | 2 | 3 | 0 (0) | 1 (0) | 5.9 |
| `seon.cluster.host` | `src/seon/cluster/host.clj` | 206 | 11 | `seon.cluster.host/host!` 37; `seon.cluster.host/home-call` 28; `seon.cluster.host/retire!` 26 | 3 | 5 | 8 | 0 (0) | 5 (4) | 11.0 |
| `seon.render.data` | `src/seon/render/data.clj` | 201 | 9 | `seon.render.data/incoming-page` 41; `seon.render.data/entity-observation` 41; `seon.render.data/at` 32 | 2 | 5 | 0 | 0 (0) | 0 (0) | 3.0 |
| `seon.cluster.branch-evidence` | `src/seon/cluster/branch_evidence.clj` | 197 | 12 | `seon.cluster.branch-evidence/branch-evidence` 35; `seon.cluster.branch-evidence/test-evidence` 32; `seon.cluster.branch-evidence/file-evidence` 25 | 0 | 7 | 6 | 1 (1) | 7 (0) | 10.8 |
| `seon.test.platform` | `src/seon/test/platform.clj` | 188 | 9 | `seon.test.platform/run-check!` 43; `seon.test.platform/record!` 42; `seon.test.platform/snapshot!` 15 | 1 | 6 | 3 | 0 (0) | 5 (2) | 6.0 |
| `my.agent` | `src/my/agent.clj` | 183 | 11 | `my.agent/retire-branch!` 41; `my.agent/create-branch!` 31; `my.agent/branch` 22 | 1 | 7 | 10 | 0 (0) | 5 (0) | 12.9 |
| `seon.render.test` | `src/seon/render/test.clj` | 175 | 14 | `seon.render.test/render-ai` 30; `seon.render.test/render-html` 25; `seon.render.test/failure-html` 21 | 1 | 5 | 2 | 0 (0) | 4 (0) | 4.5 |
| `seon.cluster.status` | `src/seon/cluster/status.clj` | 170 | 11 | `seon.cluster.status/agents` 57; `seon.cluster.status/snapshot` 43; `seon.cluster.status/cluster-row` 12 | 2 | 6 | 5 | 0 (0) | 1 (0) | 8.1 |
| `seon.render.schema` | `src/seon/render/schema.clj` | 168 | 11 | `seon.render.schema/usage` 48; `seon.render.schema/render-html` 30; `seon.render.schema/subject` 14 | 0 | 4 | 2 | 0 (1) | 0 (0) | 4.9 |
| `my.test` | `src/my/test.clj` | 163 | 4 | `my.test/check-platform` 57; `my.test/check` 34; `my.test/check-now` 27 | 0 | 5 | 9 | 0 (0) | 11 (2) | 12.1 |
| `seon.edit.jvm` | `src/seon/edit/jvm.clj` | 155 | 8 | `seon.edit.jvm/edit*` 40; `seon.edit.jvm/result` 24; `seon.edit.jvm/filesystem-refusal` 24 | 0 | 3 | 1 | 0 (0) | 0 (0) | 3.5 |
| `seon.render.var` | `src/seon/render/var.clj` | 147 | 8 | `seon.render.var/status` 42; `seon.render.var/render-html` 22; `seon.render.var/before` 20 | 0 | 5 | 2 | 0 (0) | 4 (0) | 4.5 |
| `seon.fn.signature` | `src/seon/fn/signature.cljc` | 140 | 6 | `seon.fn.signature/declaration-parts` 37; `seon.fn.signature/function-signatures` 24; `seon.fn.signature/source-signature` 23 | 2 | 0 | 0 | 0 (0) | 0 (0) | 2.3 |
| `seon.bootstrap` | `src/seon/bootstrap.clj` | 137 | 4 | `seon.bootstrap/situation` 62; `seon.bootstrap/help-value` 25; `seon.bootstrap/render-help-html` 24 | 2 | 5 | 14 | 0 (0) | 3 (1) | 16.9 |
| `seon.await` | `src/seon/await.clj` | 133 | 7 | `seon.await/await!` 48; `seon.await/await-port-operations` 30; `seon.await/diagnostic` 26 | 6 | 2 | 0 | 0 (0) | 0 (0) | 2.5 |
| `seon.contracts` | `src/seon/contracts.clj` | 128 | 6 | `seon.contracts/report` 25; `seon.contracts/task` 20; `seon.contracts/findings` 19 | 0 | 2 | 1 | 0 (0) | 0 (0) | 2.9 |
| `my.root` | `src/my/root.clj` | 122 | 8 | `my.root/merge!` 20; `my.root/merge-requests` 16; `my.root/close!` 16 | 0 | 8 | 2 | 0 (0) | 0 (0) | 3.7 |
| `seon.test.accretion` | `src/seon/test/accretion.clj` | 121 | 6 | `seon.test.accretion/render-ai` 26; `seon.test.accretion/generatable?` 23; `seon.test.accretion/render-failure` 22 | 2 | 1 | 1 | 0 (1) | 1 (0) | 2.9 |
| `seon.run` | `src/seon/run.clj` | 114 | 4 | `seon.run/walkthrough` 43; `seon.run/usage-form` 27; `seon.run/complete` 18 | 3 | 3 | 1 | 0 (2) | 0 (0) | 3.2 |
| `seon.background` | `src/seon/background.clj` | 110 | 4 | `seon.background/poll` 58; `seon.background/await` 27; `seon.background/render-ai` 10 | 1 | 4 | 0 | 0 (0) | 1 (0) | 2.5 |
| `seon.sci.arm` | `src/seon/sci/arm.clj` | 105 | 5 | `seon.sci.arm/arm!` 38; `seon.sci.arm/arming-projection` 19; `seon.sci.arm/changed-namespaces` 17 | 2 | 4 | 8 | 0 (0) | 5 (1) | 10.0 |
| `my.fs` | `src/my/fs.clj` | 102 | 5 | `my.fs/write!` 19; `my.fs/read` 17; `my.fs/glob` 16 | 0 | 4 | 2 | 0 (0) | 2 (0) | 3.5 |
| `seon.turn.world` | `src/seon/turn/world.clj` | 98 | 6 | `seon.turn.world/record-fault` 18; `seon.turn.world/with-world` 16; `seon.turn.world/world` 14 | 5 | 5 | 2 | 0 (0) | 2 (0) | 3.4 |
| `my.edit` | `src/my/edit.clj` | 96 | 3 | `my.edit/lines!` 30; `my.edit/form!` 23; `my.edit/exact!` 22 | 0 | 3 | 2 | 0 (0) | 3 (0) | 3.7 |
| `seon.render.block` | `src/seon/render/block.clj` | 84 | 1 | `seon.render.block/surface-id` 36 | 3 | 1 | 0 | 0 (0) | 0 (0) | 1.7 |
| `my.background` | `src/my/background.clj` | 71 | 3 | `my.background/await` 17; `my.background/poll` 16; `my.background/invalid-call` 9 | 1 | 4 | 2 | 0 (0) | 0 (0) | 3.1 |
| `seon.cluster.instruction` | `src/seon/cluster/instruction.clj` | 69 | 4 | `seon.cluster.instruction/toolkit-namespaces` 27; `seon.cluster.instruction/seed-entities` 8; `seon.cluster.instruction/instruction-html` 6 | 1 | 2 | 1 | 0 (0) | 1 (0) | 2.4 |
| `seon.ai.tokens` | `src/seon/ai/tokens.cljc` | 61 | 4 | `seon.ai.tokens/estimate-of-characters` 16; `seon.ai.tokens/estimate` 13; `seon.ai.tokens/estimate-chars` 11 | 9 | 0 | 1 | 0 (0) | 0 (0) | 2.0 |
| `seon.eval` | `src/seon/eval.clj` | 58 | 1 | `seon.eval/of-agent` 53 | 1 | 3 | 1 | 0 (0) | 0 (0) | 2.9 |
| `seon.sci.observe` | `src/seon/sci/observe.clj` | 58 | 1 | `seon.sci.observe/observe` 42 | 1 | 1 | 1 | 0 (0) | 0 (0) | 2.6 |
| `seon.render.route` | `src/seon/render/route.clj` | 56 | 1 | `seon.render.route/path` 20 | 8 | 0 | 0 | 0 (0) | 0 (0) | 1.1 |
| `my.task` | `src/my/task.clj` | 52 | 2 | `my.task/current` 28; `my.task/send-back!` 21 | 0 | 2 | 7 | 0 (0) | 2 (0) | 8.2 |
| `my.message` | `src/my/message.clj` | 47 | 4 | `my.message/inbox` 14; `my.message/received!` 14; `my.message/read` 11 | 1 | 1 | 4 | 0 (0) | 0 (0) | 4.8 |
| `seon.sci.batch` | `src/seon/sci/batch.clj` | 37 | 4 | `seon.sci.batch/around` 7; `seon.sci.batch/read-stage` 5; `seon.sci.batch/read-next` 5 | 2 | 1 | 2 | 0 (0) | 0 (0) | 2.5 |
| `my.shell` | `src/my/shell.clj` | 36 | 1 | `my.shell/run!` 17 | 0 | 3 | 1 | 0 (0) | 0 (0) | 1.8 |
| `my.web` | `src/my/web.clj` | 34 | 2 | `my.web/fetch` 17; `my.web/search` 16 | 0 | 2 | 0 | 0 (0) | 0 (0) | 0.8 |
| `seon.web.search` | `src/seon/web/search.clj` | 30 | 2 | `seon.web.search/organic-row` 17; `seon.web.search/organic-results` 10 | 0 | 0 | 0 | 0 (0) | 0 (0) | 0.7 |
| `seon.shell` | `src/seon/shell.clj` | 24 | 2 | `seon.shell/stdin?` 11; `seon.shell/output?` 11 | 1 | 0 | 0 | 0 (0) | 0 (0) | 0.5 |
| `my.turn` | `src/my/turn.clj` | 18 | 2 | `my.turn/wait` 11; `my.turn/usage-form` 6 | 1 | 1 | 1 | 0 (2) | 0 (0) | 1.5 |
| `seon.web.extract` | `src/seon/web/extract.clj` | 13 | 1 | `seon.web.extract/html` 9 | 1 | 0 | 0 | 0 (0) | 0 (0) | 0.4 |
| `seon.cluster.branch` | `src/seon/cluster/branch.clj` | 12 | 1 | `seon.cluster.branch/cluster-branch` 7 | 4 | 0 | 0 | 0 (0) | 0 (0) | 0.3 |

### script/ (23 namespaces, 5,956 non-blank lines, 274 defn/defn-; fan-in counts script requirers only, src requires no script namespace)

| Namespace | File | Lines | defn | Longest 3 (body lines) | Fan-in | Fan-out | Churn 7d | Reverts 7d (all) | Tasks (proved) | Dragon score |
|---|---|--:|--:|---|--:|--:|--:|--:|--:|--:|
| `seon.operator` | `script/seon/operator.clj` | 2246 | 110 | `seon.operator/move-to-head!` 109; `seon.operator/launch-child!` 92; `seon.operator/parse-argv` 87 | 11 | 3 | 21 | 0 (0) | 25 (2) | 46.2 |
| `seon.dev.markdown` | `script/seon/dev/markdown.clj` | 1096 | 53 | `seon.dev.markdown/rule-dependency-pin-current` 67; `seon.dev.markdown/validate-repository-pins` 47; `seon.dev.markdown/format-violations` 44 | 1 | 1 | 2 | 0 (0) | 1 (0) | 14.6 |
| `seon.dev.markdown-test` | `script/seon/dev/markdown_test.clj` | 526 | 5 | `seon.dev.markdown-test/authority-errors` 42; `seon.dev.markdown-test/living-prd-authorities` 14; `seon.dev.markdown-test/content-body` 3 | 0 | 2 | 2 | 0 (0) | 0 (0) | 8.3 |
| `seon.dev.docstring` | `script/seon/dev/docstring.clj` | 320 | 12 | `seon.dev.docstring/scan` 42; `seon.dev.docstring/check-source` 32; `seon.dev.docstring/check-echoes` 31 | 0 | 0 | 1 | 0 (0) | 0 (0) | 5.2 |
| `seon.dev.mcp` | `script/seon/dev/mcp.clj` | 225 | 20 | `seon.dev.mcp/call-tool` 37; `seon.dev.mcp/handle` 28; `seon.dev.mcp/sync-tools` 20 | 0 | 1 | 0 | 0 (0) | 2 (0) | 3.2 |
| `seon.operator-move-test` | `script/seon/operator_move_test.clj` | 191 | 1 | `seon.operator-move-test/git-stage-of-commit` 20 | 0 | 2 | 4 | 0 (0) | 0 (0) | 6.4 |
| `seon.program-context-weight` | `script/seon/program_context_weight.clj` | 180 | 9 | `seon.program-context-weight/walk!` 40; `seon.program-context-weight/overlay-row` 21; `seon.program-context-weight/report` 17 | 0 | 4 | 1 | 0 (0) | 0 (0) | 3.8 |
| `start-flow-benchmark` | `script/start_flow_benchmark.clj` | 176 | 6 | `start-flow-benchmark/sample!` 43; `start-flow-benchmark/graph-cost!` 24; `start-flow-benchmark/paused-graph!` 18 | 0 | 15 | 4 | 0 (0) | 0 (0) | 6.8 |
| `seon.dev.context-corpus` | `script/seon/dev/context_corpus.clj` | 157 | 12 | `seon.dev.context-corpus/score` 19; `seon.dev.context-corpus/-main` 18; `seon.dev.context-corpus/duplicates` 12 | 0 | 1 | 2 | 0 (0) | 0 (0) | 4.0 |
| `seon.dev.dependency-digest` | `script/seon/dev/dependency_digest.clj` | 150 | 9 | `seon.dev.dependency-digest/git-dependency-pins` 25; `seon.dev.dependency-digest/pins-unavailable` 24; `seon.dev.dependency-digest/configuration-digest` 18 | 3 | 0 | 0 | 0 (0) | 0 (0) | 2.1 |
| `seon.sentinel` | `script/seon/sentinel.clj` | 91 | 7 | `seon.sentinel/run` 21; `seon.sentinel/summary` 15; `seon.sentinel/eval-probe` 14 | 0 | 3 | 1 | 0 (0) | 0 (0) | 2.4 |
| `seon.operator-outcome-test` | `script/seon/operator_outcome_test.clj` | 89 | 1 | `seon.operator-outcome-test/failed-move` 16 | 0 | 2 | 1 | 0 (0) | 0 (0) | 2.3 |
| `seon.dev.harness-costs` | `script/seon/dev/harness_costs.clj` | 87 | 5 | `seon.dev.harness-costs/wrapper-overhead` 26; `seon.dev.harness-costs/measure` 15; `seon.dev.harness-costs/mean-us` 8 | 0 | 6 | 1 | 0 (0) | 0 (0) | 2.5 |
| `seon.vocabulary` | `script/seon/vocabulary.clj` | 76 | 7 | `seon.vocabulary/report` 13; `seon.vocabulary/files` 10; `seon.vocabulary/counts` 9 | 0 | 1 | 1 | 0 (0) | 1 (0) | 2.1 |
| `seon.central-round` | `script/seon/central_round.clj` | 74 | 4 | `seon.central-round/-main` 25; `seon.central-round/run-round!` 15; `seon.central-round/batch-form` 13 | 0 | 4 | 3 | 0 (0) | 0 (0) | 4.4 |
| `seon.interpreted-census` | `script/seon/interpreted_census.clj` | 58 | 3 | `seon.interpreted-census/batch-form` 22; `seon.interpreted-census/-main` 15; `seon.interpreted-census/batch-lines` 9 | 0 | 1 | 4 | 0 (0) | 0 (0) | 5.1 |
| `seon.cost-fresh` | `script/seon/cost_fresh.clj` | 52 | 3 | `seon.cost-fresh/-main` 26; `seon.cost-fresh/median` 11; `seon.cost-fresh/assert-improvement!` 6 | 0 | 1 | 1 | 0 (0) | 0 (0) | 2.2 |
| `astra-cost-handoff` | `script/astra_cost_handoff.clj` | 36 | 3 | `astra-cost-handoff/squash-costs` 12; `astra-cost-handoff/preparation` 10; `astra-cost-handoff/costs` 7 | 0 | 5 | 3 | 0 (0) | 0 (0) | 3.7 |
| `seon.cost-record2` | `script/seon/cost_record2.clj` | 36 | 1 | `seon.cost-record2/-main` 26 | 0 | 1 | 2 | 0 (0) | 0 (0) | 3.0 |
| `seon.operator-nuke-test` | `script/seon/operator_nuke_test.clj` | 31 | 0 | - | 0 | 1 | 0 | 0 (0) | 0 (0) | 0.3 |
| `seon.config-load-timing` | `script/seon/config_load_timing.clj` | 24 | 1 | `seon.config-load-timing/-main` 18 | 0 | 0 | 2 | 0 (0) | 0 (0) | 2.7 |
| `seon.cost-acquire` | `script/seon/cost_acquire.clj` | 19 | 1 | `seon.cost-acquire/-main` 11 | 0 | 1 | 1 | 0 (0) | 0 (0) | 1.5 |
| `seon.cost-record` | `script/seon/cost_record.clj` | 16 | 1 | `seon.cost-record/-main` 12 | 0 | 1 | 2 | 0 (0) | 0 (0) | 2.5 |

### Functions over 80 body lines (123 in src, 3 in script)

| Function | Body lines | Named by a task |
|---|--:|---|
| `seon.test/select` | 435 | yes |
| `seon.sci.eval/evaluate` | 365 | yes |
| `seon.test/run-request` | 364 | yes |
| `seon.turn/step` | 305 | yes |
| `seon.db/write-owned-values-error` | 247 | yes |
| `seon.schema/projection-with-declarations` | 245 | yes |
| `seon.effect/request*` | 237 | no |
| `seon.test/admit-run` | 230 | yes |
| `seon.turn/call-turn` | 219 | yes |
| `seon.sci.eval/install-row!` | 208 | yes |
| `seon.cluster.agent/acquire-context!` | 207 | yes |
| `seon.turn/resume-turn` | 202 | yes |
| `seon.sci.forgive/scan` | 184 | yes |
| `seon.test.runner/record-latest-tx` | 183 | yes |
| `seon.schema/assert-complete-contract!` | 181 | no |
| `seon.schema/projection-build` | 180 | no |
| `seon.error/prepare` | 175 | yes |
| `seon.cluster.boot/request!` | 175 | yes |
| `seon.fault/record!` | 175 | yes |
| `seon.instrument/violation` | 174 | yes |
| `seon.cluster/full-source-refresh!` | 169 | yes |
| `seon.render/render-call` | 168 | yes |
| `seon.render.value/project` | 163 | no |
| `seon.cluster.source/publish!` | 163 | yes |
| `seon.cluster/populate-source!` | 159 | yes |
| `seon.fn/contract-findings` | 156 | yes |
| `seon.error/commit-call` | 152 | yes |
| `seon.fn/index!` | 150 | yes |
| `seon.test/bounded-result` | 147 | yes |
| `seon.turn/record-attempt!` | 146 | yes |
| `seon.test.runner/complete-members` | 143 | no |
| `seon.fn/var-entity` | 142 | yes |
| `seon.render.web/acquire-debug-data` | 139 | no |
| `seon.db/query-index-patterns` | 137 | yes |
| `seon.turn/evaluate-sources` | 134 | yes |
| `seon.cluster/development-source-refresh!` | 133 | yes |
| `seon.render.web/render-step` | 132 | yes |
| `seon.schema/canonical-definition` | 130 | yes |
| `seon.error/reply-value` | 130 | yes |
| `seon.ai/send-request` | 129 | no |
| `seon.fn.analyzer/analyze` | 128 | yes |
| `seon.sci.eval/install-selected!` | 126 | yes |
| `seon.turn/row-tx` | 123 | yes |
| `seon.sci.eval/acquire-program!` | 120 | yes |
| `seon.sci.admit/open-node` | 120 | no |
| `seon.turn/settle-batch-refusal!` | 118 | yes |
| `seon.id/structural-digest` | 118 | yes |
| `seon.schema/pulled-attribute-entry` | 116 | no |
| `seon.test/member-result` | 116 | yes |
| `seon.cluster.boot/stand-boot-layers!` | 116 | no |
| `seon.render.value/classify` | 115 | no |
| `seon.instrument/compiled-wrapper` | 115 | yes |
| `seon.cluster.wake/route!` | 114 | yes |
| `seon.test/clean-request-handle` | 113 | yes |
| `seon.turn/evaluation-terminal-data` | 112 | no |
| `seon.error/recording` | 112 | yes |
| `seon.cluster.source/accept-merge!` | 111 | yes |
| `seon.fn/plan-file-change` | 108 | no |
| `seon.render.web/feed` | 108 | no |
| `seon.render.walk/neighborhood` | 108 | no |
| `seon.schema.edn/admit` | 108 | no |
| `seon.turn/settle-batch!` | 107 | yes |
| `seon.sci.eval/regenerate-agent-context!` | 107 | yes |
| `seon.cluster.agent/start-claimed-flow!` | 107 | yes |
| `seon.instrument/apply!` | 107 | yes |
| `seon.schedule/fire-call` | 107 | no |
| `seon.db/carried-projection` | 106 | yes |
| `seon.cluster/start-flows!` | 106 | yes |
| `seon.cluster.store/open-store!` | 106 | no |
| `seon.sci.eval/shown-result` | 105 | yes |
| `seon.flow/var-process` | 105 | yes |
| `seon.render.web/render-pass` | 104 | no |
| `seon.render.web/debug-response` | 102 | no |
| `seon.cluster/development-namespaces` | 102 | no |
| `seon.batch/evaluate-step` | 102 | yes |
| `seon.fn/compile-index-transaction` | 101 | no |
| `seon.cluster.process/run-process!` | 101 | no |
| `seon.fn/runtime-analysis-batch` | 100 | yes |
| `seon.schema/validate-declarations!` | 100 | no |
| `seon.cluster.source/prepare-merge!` | 99 | yes |
| `seon.schema.internal/assert-complete-schema!` | 98 | no |
| `seon.sci.eval/install-evaluated-rows!` | 97 | yes |
| `seon.sci.kernel/failure-value` | 97 | yes |
| `seon.cluster.boot/stand-cluster-runtime!` | 97 | no |
| `seon.fn/assert-capability-contracts!` | 96 | no |
| `seon.cluster.source/proven-file` | 95 | yes |
| `seon.reconcile/plan-transaction-data` | 95 | yes |
| `seon.turn/render-ai` | 94 | no |
| `seon.fn/published-index-entities` | 94 | no |
| `seon.cluster/stop-flows!` | 94 | yes |
| `seon.call-preparation/prepare` | 94 | yes |
| `seon.fault/fault!` | 94 | yes |
| `seon.render.walk/acquisition-members` | 93 | no |
| `seon.web.jvm/fetch` | 93 | yes |
| `seon.web.jvm/search` | 91 | no |
| `seon.cluster.agent/submit-source-in-projection` | 90 | yes |
| `seon.render.web/page-result` | 89 | no |
| `seon.repl/entity-emission` | 89 | yes |
| `seon.cluster.agent/db<->flow` | 88 | yes |
| `seon.sci.eval/build-base-ctx` | 87 | yes |
| `seon.turn/next-agent-work` | 86 | yes |
| `seon.render.web/data-response` | 86 | no |
| `seon.merge/merge!` | 86 | no |
| `seon.render.web/render-source-call` | 85 | no |
| `seon.render.web/derive-page` | 85 | yes |
| `seon.test/resolve-test` | 85 | yes |
| `seon.test.runner/restore-live-cluster-schema!` | 85 | yes |
| `seon.test.runner/prepare-failures!` | 85 | no |
| `seon.sci.kernel/invoke` | 84 | yes |
| `seon.flow/submit!` | 84 | no |
| `seon.sci.eval/interpret-overlay!` | 83 | yes |
| `seon.fn/backfill-contract-facts!` | 83 | no |
| `seon.render/declared-projection` | 83 | no |
| `seon.sci.eval/host-namespace!` | 82 | yes |
| `seon.config/admit-initialization-entities` | 82 | no |
| `seon.problems/html-report` | 82 | yes |
| `seon.turn/evaluation-facts` | 81 | no |
| `seon.sci.eval/fork-cluster-ctx` | 81 | yes |
| `seon.fn/reconcile-tx-in` | 81 | yes |
| `seon.schema/compilable-form` | 81 | no |
| `seon.cluster.boot/start!` | 81 | yes |
| `seon.maintenance/collect-store!` | 81 | no |
| `seon.schema.admission/admit` | 81 | no |
| `seon.operator/move-to-head!` (script) | 109 | no |
| `seon.operator/launch-child!` (script) | 92 | yes |
| `seon.operator/parse-argv` (script) | 87 | yes |

## Notes

- Budget: CLAUDE.md (AGENTS.md is its symlink), The goal, point 4, says the core aims at about 10,000 lines. The measured src total is 89,247 non-blank lines. CLAUDE.md, How to work, point 3 adds that line counts are a codebase budget, not a per-change ledger.
- Line counts are non-blank lines, matching `grep -c -v '^\s*$'` over src/ (89,247) and script/ (5,956). Body lines of a function are the span of its top-level defn/defn- form, including docstring, signature and closing paren. A form whose closing paren shares a line with other code counts to that line.
- Fan-in and fan-out come from tokens in each `ns` form matched against the project's namespace names. Calls to `require` inside bodies, and `requiring-resolve`, are not counted. Fan-out counts src and script namespaces together. Namespaces declared with reader metadata (`(ns ^{:seon.ns/context-relevant? ...} name)`, 12 files) are parsed by name after the metadata; an earlier parser bug collapsed 11 of them into one record and was fixed before these numbers.
- Churn is `git log` on the current branch refactor/agent-platform only, not all branches. Reverts count subjects that start with the word Revert, which is literal: `Revert verification:` and `Revert the night's fences` are included, though they are not reverts of a single commit. The all-time count is in parentheses.
- Tagged proofs are approximate: a token `ns/name` that follows `:seon/tasks` up to the next form opener counts as tagging. Whether a tagged test is green is not checked here.
- Orphan task files: 33 of 798 entries sit in 15 files named for no src or script namespace (for example seon.competency 9, seon.test-support 6, seon.contracts-compile-test 2). They are excluded from the table counts.
- Surprises: `seon.error.refusal` has 67 src requirers from 724 lines, more than `seon.db` (66) from 5,969 lines. `seon.context` is 558 lines with 43 commits in 7 days, the densest churn of any file over 500 lines. `seon.operator` in script/ is the largest script namespace at 2,246 lines with 25 tasks, only 2 of them proved, and its churn is 21.
- Working tree: src/ and script/ equal HEAD at measurement. resources/seon/tasks/*.edn and test/ carry uncommitted edits from other lanes (git status shows M on several, e.g. seon.turn.edn, seon.schema.edn, test/my/root_test.clj), so task and tagged-proof counts are working-tree values and may move before the commit.
- Scope limits: this is shape and churn only. Costs, behaviour and whether a function should exist are not judged here. The top-15 is src only, per the score definition; script is in its own table.
- Verification: the src and script non-blank totals match grep exactly. The seon.turn/step span (5660 to 5964) was checked against the file end; it is the last form in src/seon/turn.clj.
