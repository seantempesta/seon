---
type: research
status: active
tags: [research, architecture]
---

# Retiring "pod": complete site inventory and migration plan

## Ruling

The owner retired the noun "pod" (2026-07-20). It was leftover naming from
the wasm pod-host era and never a seam name: the shadow build is `:client`,
the entry namespace is `seon.client`, and the running unit agents live in is
the cluster. Unified mapping:

| Old use of "pod" | New term | Rationale |
|---|---|---|
| the running unit (database + processes + agents) | **cluster** | already legislated |
| the supervised CLJS process (`watcher`/`writer`/`pod`) | **client** | the code's own seam name (`:client` build, `seon.client/-main`) |
| `:seon.dev.process/pod`, `pod-id` | `:seon.dev.process/client`, `client-id` | operator process identity |
| `bin/seon logs pod` | `bin/seon logs client` | operator surface |
| `pod.js` artifact | `client.js` | build output follows build id |
| `logs/pod-events.log` (`seon.log`) | `logs/client-events.log` | log ownership follows process name |
| `tmp/seon-pod/`, `data/seon-pod/` run stores | `seon-client` | plus `.gitignore` row |
| `acme.pod` downstream ns | `acme.client` | same seam |
| `src-inspect-ai` `pod_api`, solver/catalog prose | `client_api` / cluster prose | per-meaning mapping above |
| `docs/seon/pod/` directory | fold into `docs/seon/architecture/` | one doc, `REPL-WORKFLOW.md` |
| `pod-host/` (wasm era tree) | unchanged for now | separate owner decision; frozen |
| RunPod vendor tokens — `RunPod`, `runpod`, `RUNPOD_*` (incl. `RUNPOD_API_KEY` and the process.clj:121 env-passthrough prefix `"RUNPOD_"`), `api.runpod.ai`, `runpod-root`, `RunPodEndpoint`, the `"runpod"` endpoint-mode string, and the `runpod/flash` container base | **untouched** | these name a third-party GPU vendor (the DiffusionGemma backend), not the Seon process; renaming them silently breaks credential passthrough and the vendor URL with no test coverage until the next paid GPU run |

`:seon.dev.process/pod` is NOT a pure code identity anywhere in this plan:
it has persisted forms on disk and in the database that the rename cannot
cross. Their dispositions:

| Persisted form | Where | Disposition |
|---|---|---|
| on-disk process records (`pod.edn`) | `tmp/seon-operator/processes/`, acme's process dir, any `branch-processes/<cluster>/processes/` dirs from `script/seon/dev/branch.clj` | **verified-empty at freeze** — quiesced with the PRE-rename operator (see Freeze protocol); process records are resolved by id name only (script/seon/dev/process.clj state-file), so no post-rename operator command can see or stop a pre-rename `pod` record |
| restore intents (`::restore/consumer-generations` keyed by process id) | database + disk, read by `restore_state.clj:1222-1311` | **verified-absent at freeze** — at the freeze base, prove no retained restore intent exists (`retained-intent` returns nil / restore status clean); if one exists, complete or abort it BEFORE the rename. The intent and process-record schemas enum-lock ids, so old-keyword data read post-rename throws validation — verify-empty is strictly better than a read-side migration; do not add one |
| release-manifest member keys (`:seon.release.member/pod "runtime/pod.js"`) | every package built pre-rename (`tmp/package-v*`; v10..v14 exist), baked by `release.clj:52-123` | **regenerate-and-reprove** — pre-rename packages are invalidated; step 1's gate must regenerate one package and prove readback/verify with the renamed member key; any downstream consumer of a pre-rename package repackages rather than mixing operator generations |

Scope per owner: active source and build/config files, this branch's PRDs
(`runtime-reliability`, `database-authority-mesh`), `docs/seon/`, skills, and
`src-inspect-ai`. Dated research in other PRD folders and archived issues are
the historical record and stay as written.

## Freeze protocol

The rename is one orchestrator-owned atomic unit. Entry gate, in order:

1. Enumerate active lanes: this session's subagents (must all be returned
   and reviewed) AND separately launched Codex tasks. Identify Codex tasks
   through the app thread list by checkout and purpose, and request a
   coherent commit or explicit path handoff via thread message — do not
   infer ownership from `git status`. Additionally enumerate every entry of
   `git worktree list` on a branch (a computed rule, never a hand-kept
   list) and record a disposition for each: disposable, merge-before-rename,
   or translate-after. For the pinned `seon-stable` worktree (branch
   `repl-autosuggest/stable`, planned cherry-pick merge-back at cutover):
   either sequence the rename AFTER the repl-autosuggest cutover
   cherry-pick, or record the rename's completing commit range in that
   lane's anchor doc
   (`docs/prds/repl-autosuggest/root-cause-fixes-2026-07-13.md`) as the
   mandatory translation point — noting `acme/src/acme/pod.cljs` is both
   dirty there and renamed here, so its cherry-picks will conflict on both
   path and identifiers.
2. Receive an explicit ack (thread reply or completed commit) from every
   lane that owns files inside the rename scope, with a deadline: a lane
   that has not acked by the deadline is escalated to the owner instead of
   pausing the freeze indefinitely; the rename does not start around an
   unresolved lane.
3. Quiesce both clusters under PRE-rename code: `bin/seon down` and
   `bin/acme down`. Verify absence evidence, not just exit 0: `bin/seon
   status` reports nothing running; `tmp/seon-operator/processes/` (and
   acme's process dir, plus any `branch-processes/<cluster>/processes/`
   dirs) contain no `pod.edn` or restore-admin record; ports 7890/7891
   (and acme's 7980/7981) are unbound; no `locks/` residue remains; no
   retained restore intent exists (per the persisted-forms table above).
   Record this evidence with the freeze base commit hash. Rationale:
   process records are resolved by id name only, so restart cannot cross
   the identity rename.
4. `git status` clean except acked handoffs (gitignored paths never count
   against "clean"); all three suites green at the freeze base commit;
   record that commit hash in this plan.
5. Execute the four steps below without interleaving other work; each step
   commits path-limited and reruns its gate before the next.
6. Release: announce the completing commit range on the same threads,
   then other lanes rebase/continue.

Abort rule: any non-rename commit landing mid-freeze from an unacked
source stops the unit; reconcile, re-green, then restart by re-running
gates 3-4 — re-verifying quiesce/absence evidence and RE-RECORDING the
freeze-base commit (never resume against a superseded base) — before
continuing from the last completed step. Recovery from any mid-freeze
failure restarts from `bin/seon down` executed under whatever code state
is actually on disk at that moment.

## Execution order

This is a cross-cutting rename: one orchestrator-owned atomic unit per the
shared-tree rule, executed only when no other lane holds edits in the
affected files, with a coordinated freeze around the build-artifact rename.

1. **Code identities** (`src/`, `script/`, `bin/`, `config/`, `test/`,
   `shadow-cljs.edn`, `bb.edn`, `.mcp.json`): rename identifiers, process
   ids, artifact names, log paths. Gate: full `bin/test-cljs`,
   `bin/test-writer`, `bin/seon test operator`, then `bin/seon up` from the
   proven-clean quiesced state (never `restart` — restart cannot cross the
   identity rename) and a live `bin/seon status`/web-UI proof (the operator
   must supervise `watcher → writer → client`); one MCP `eval_cljs`
   round-trip against the renamed cluster after restarting the MCP client
   (already-running clients do not reload stdio server definitions);
   regenerate one release package and prove readback/verify with the
   renamed member key (persisted-forms table). Vendor tripwire: `rg -c
   'RUNPOD_API_KEY|api\.runpod\.ai' src/seon/ai/diffusiongemma.cljs
   config/system.edn script/seon/dev/process.clj` must return the
   pre-freeze counts, proving the vendor surface is byte-identical without
   a paid GPU run.
2. **Downstream + eval harness** (`acme/`, `src-inspect-ai/`): rename
   `acme.pod`, `pod_api`, solver/catalog/test prose; prove with the acme
   cluster started via `bin/acme up` from its quiesced state (not
   `restart`) and one smoke eval.
3. **Living docs** (`AGENTS.md`, `docs/seon/architecture|components|
   reference|vision|concepts`, `docs/seon/process-management.md`, skills,
   this branch's two PRD roadmaps): rewrite prose to the mapping; fold
   `docs/seon/pod/REPL-WORKFLOW.md` into the architecture tree.
4. **Sweep** (vendor-excluded): `rg -in pod --glob '!pod-host/**'` over the
   scoped tree, piped through `rg -vi 'runpod'`, must return only
   deliberate historical citations. A residual hit matching `runpod` is
   never a rename target — it names the third-party GPU vendor per the
   frozen mapping row.

Renamed-file gotchas recorded during inventory: `logs/pod-events.log` is
rotated (`.1` sibling); `data/seon-pod/` appears in `.gitignore` twice —
both duplicate rows flip to `data/seon-client` in the same path-limited
step-1 commit so the step-4 sweep and `.gitignore` agree; the old
`tmp/seon-pod/` and `data/seon-pod/` run stores and
`logs/pod-events.log(.1)` are **abandoned**, never migrated (recreated as
`seon-client`/`client-events.log` on first `up`; step 1 may delete them);
the `seon-skills` corpus and generated `.claude/skills` adapters must be
resynced with `bin/seon skills sync` after step 3, never hand-edited.

## Complete in-scope site inventory (occurrences per file)

Generated 2026-07-20 by `rg -in pod` over active source, this branch's
PRDs, and `docs/seon/`. Historical PRDs and archives are out of scope.
Corrected 2026-07-20 (adversarial review): RunPod vendor tokens are
excluded per the frozen mapping row — vendor-only files
(`src/seon/ai/diffusiongemma.cljs` 20→0,
`src-inspect-ai/src/seon_inspect/worker_endpoints.py` 10→0,
`test/seon/ai/diffusiongemma_test.cljs` 4→0, `tasks/skill_lift.py`,
`tasks/e1_spec_fn.py`, `tasks/ladder_lift.py`) are dropped below, and
mixed files are shrunk to their non-vendor counts (`config/system.edn`
7→5, `script/seon/dev/process.clj` 33→32, `src/seon/ai.cljs` 2→1,
`docs/seon/reference/llm-adapters.md` 8→6).

```
 138 test/seon/dev/process_test.clj
 110 docs/prds/database-authority-mesh/roadmap.md
 109 docs/prds/runtime-reliability/roadmap.md
  77 src-inspect-ai/tests/test_solver.py
  76 src-inspect-ai/src/seon_inspect/solver.py
  58 test/seon/dev/branch_test.clj
  49 docs/prds/runtime-reliability/research/overnight-integrated-graduation-plan-2026-07-18.md
  47 src-inspect-ai/src/seon_inspect/catalog.py
  40 script/seon/dev/branch.clj
  39 docs/seon/components/acme-harness.md
  38 src-inspect-ai/tests/test_catalog.py
  38 src-inspect-ai/src/seon_inspect/swebench_arm.py
  34 script/seon/dev/restore_state.clj
  33 test/seon/dev/restore_test.clj
  32 script/seon/dev/process.clj
  31 docs/prds/runtime-reliability/research/seon-cli-lifecycle-audit-2026-07-13.md
  28 src-inspect-ai/tests/test_product_scenarios.py
  26 test/seon/dev/cli_test.clj
  26 src-inspect-ai/tests/test_frozen_tool_rows.py
  24 docs/seon/issues/archive/dual-code-paths-registry.md
  23 src/seon/client.cljs
  23 docs/prds/runtime-reliability/research/issues-audit-2026-06-28.md
  22 src-inspect-ai/src/seon_inspect/scorecard.py
  21 src/seon/web/serve.cljs
  21 src-inspect-ai/src/seon_inspect/tb_agent.py
  21 docs/prds/database-authority-mesh/research/datahike-resource-lifetime-2026-07-15.md
  20 src-inspect-ai/src/seon_inspect/product_scenarios.py
  20 shadow-cljs.edn
  20 docs/seon/issues/archive/seon-port-non-namespaced.md
  20 docs/prds/database-authority-mesh/research/cleanup-audit-logging-errors-2026-07-20.md
  20 bin/oracle-server
  19 test/seon/dev/artifact_test.clj
  19 src-inspect-ai/src/seon_inspect/cluster.py
  18 script/seon/dev/mcp.clj
  18 docs/seon/vision/biggest-ideas-2026-05-23.md
  18 docs/seon/lineage/milestone-prior-work.md
  17 script/seon/dev/changed_test.clj
  17 docs/seon/components/capability-gates.md
  16 src/seon/log.cljs
  16 docs/seon/pod/REPL-WORKFLOW.md
  16 docs/prds/database-authority-mesh/research/2026-07-18-private-memory.md
  15 test/seon/dev/release_test.clj
  15 src-inspect-ai/src/seon_inspect/tasks/milestone_lift.py
  15 src-inspect-ai/src/seon_inspect/planning.py
  15 docs/seon/issues/inspect-live-cluster-caller-drift.md
  15 docs/seon/issues/archive/operator-interruption-can-orphan-managed-process.md
  15 docs/seon/issues/archive/inspect-capability-solvers-score-infrastructure-closes.md
  15 docs/seon/issues/acme-operator-migration-drift.md
  15 docs/prds/runtime-reliability/research/jvm-archive-boundary-2026-07-13.md
  15 docs/prds/runtime-reliability/research/inspect-graduation-readiness-2026-07-19.md
  14 test/seon/dev/runtime_id_test.cljs
  14 src/seon/eval.cljs
  14 src-inspect-ai/tests/test_milestone.py
  14 docs/seon/issues/archive/acme-cluster-reset-process-namespace.md
  14 docs/prds/runtime-reliability/research/phase-0-default-pod-live-baseline-2026-07-12.md
  14 AGENTS.md
  13 src-inspect-ai/src/seon_inspect/tb2_agent.py
  13 src-inspect-ai/src/seon_inspect/tasks/long_term_planning.py
  13 src-inspect-ai/src/seon_inspect/milestone.py
  13 src-inspect-ai/src/seon_inspect/config.py
  13 docs/seon/issues/pod-database-session-capacity-was-smaller-than-real-feed-concurrency.md
  13 docs/seon/issues/dead-process-group-leader-blocks-safe-subtree-drain.md
  13 docs/prds/runtime-reliability/research/phase-1-baseline-2026-07-13.md
  12 src-inspect-ai/tests/test_reachability.py
  12 src-inspect-ai/src/seon_inspect/tasks/product_scenarios.py
  12 seon-skills/clojurescript/SKILL.md
  12 script/seon/dev/cli.clj
  12 docs/seon/issues/restore-intent-lacks-exclusive-writer-fence.md
  12 docs/seon/issues/archive/acme-harness-agents-route-drift.md
  12 docs/seon/architecture/observability.md
  12 docs/seon/architecture/agent-runtime.md
  12 docs/prds/runtime-reliability/research/runtime-state-atom-audit-2026-07-13.md
  12 docs/prds/database-authority-mesh/research/async-consumer-migration-audit-2026-07-16.md
  12 .agents/skills/clojurescript/SKILL.md
  11 docs/seon/vision/index.md
  11 docs/seon/issues/archive/supervisor-startup-race-audit-2026-06-25.md
  11 docs/prds/runtime-reliability/research/unified-clj-cljs-cljc-test-feedback-2026-07-14.md
  11 docs/prds/runtime-reliability/research/legacy-lane-retirement-audit-2026-07-14.md
  11 docs/prds/database-authority-mesh/research/single-owner-duplication-audit-2026-07-16.md
  11 docs/prds/database-authority-mesh/research/duplicate-runtime-owner-audit-2026-07-16.md
  10 src/seon/config.cljs
  10 src-inspect-ai/tests/test_scorecard.py
  10 docs/seon/process-management.md
  10 docs/seon/issues/multi-form-eval-order-is-not-durable.md
  10 docs/seon/issues/archive/tx-feed-pump-timeouts.md
  10 docs/seon/issues/archive/datahike-query-clause-order-empty-results.md
  10 docs/prds/database-authority-mesh/research/final-one-mechanism-source-audit-2026-07-18.md
   9 src/seon/worker_eval.cljs
   9 src/seon/embed.cljs
   9 src-inspect-ai/src/seon_inspect/bfcl_adapter.py
   9 script/seon/dev/release.clj
   9 docs/seon/issues/shared-bootstrap-output-mutates-running-artifact.md
   9 docs/seon/issues/inspect-pod-solver-cannot-address-existing-agent.md
   9 docs/seon/issues/core-selected-render-errors-bypass-crash-policy.md
   9 docs/seon/issues/archive/acme-starts-a-second-jvm-writer.md
   9 docs/prds/runtime-reliability/research/deterministic-core-fault-boundary-audit-2026-07-19.md
   9 docs/prds/database-authority-mesh/research/final-package-downstream-graduation-audit-2026-07-18.md
   9 .agents/skills/clojure-testing/SKILL.md
   8 test/seon/dev/cluster_test.clj
   8 src/seon/embed.clj
   8 src-inspect-ai/tests/test_tb_agent.py
   8 src-inspect-ai/tests/test_planning.py
   8 src-inspect-ai/src/seon_inspect/typeahead_corpus.py
   8 script/seon/dev/cluster.clj
   6 docs/seon/reference/llm-adapters.md
   8 docs/seon/issues/pod-remains-ready-after-web-listener-loss.md
   8 docs/seon/issues/execution-children-retain-hundreds-of-megabytes.md
   8 docs/seon/issues/clean-or-force-evidence-can-cross-or-falsely-report-absence.md
   8 docs/seon/issues/archive/hot-reload-schema-import-can-partially-fail.md
   8 docs/prds/runtime-reliability/research/retained-head-restore-transition-audit-2026-07-15.md
   8 docs/prds/runtime-reliability/research/repo-rough-edges-2026-06-28.md
   8 docs/prds/runtime-reliability/research/inspect-product-scenarios-2026-07-19.md
   8 docs/prds/runtime-reliability/research/active-cljs-pod-mutable-runtime-census-2026-07-12.md
   8 docs/prds/database-authority-mesh/research/remaining-authority-only-consumer-deletion-inventory-2026-07-16.md
   8 docs/prds/database-authority-mesh/research/atomic-client-cold-start-replacement-plan-2026-07-16.md
   8 bin/seon-hook
   7 src-inspect-ai/tests/test_swebench_arm.py
   7 docs/seon/vision/full-scope-synthesis-2026-05-23.md
   7 docs/seon/issues/shadow-runtime-stops-reconnecting.md
   7 docs/seon/issues/archive/pod-quiesce-validates-coordinate-after-schema-detach.md
   7 docs/seon/issues/archive/pod-does-not-reconnect-after-writer-replacement.md
   7 docs/seon/issues/archive/operator-up-cannot-recover-an-unexpected-writer-exit.md
   7 docs/seon/issues/archive/inspect-solver-overrides-database-run-deadline.md
   7 docs/seon/components/extra-src.md
   7 docs/prds/runtime-reliability/research/worktree-evidence-preservation-manifest-2026-07-14.md
   7 docs/prds/runtime-reliability/research/test-impact-selection-and-runner-audit-2026-07-14.md
   7 docs/prds/runtime-reliability/research/legacy-acme-archive-readback-runbook-2026-07-14.md
   7 docs/prds/runtime-reliability/research/eval-query-memory-graduation-audit-2026-07-15.md
   7 docs/prds/runtime-reliability/research/dependency-shadow-mcp-acme-audit-2026-07-14.md
   7 docs/prds/runtime-reliability/research/architecture-performance-current-2026-07-19.md
   7 docs/prds/database-authority-mesh/research/shadow-bun-runtime-internals-2026-07-16.md
   7 docs/prds/database-authority-mesh/research/final-product-runtime-graduation-audit-2026-07-18.md
   5 config/system.edn
   7 bin/acme
   6 src/seon/agent/web/internal.cljs
   6 src/seon/agent/ctx.cljs
   6 src-inspect-ai/tests/test_bfcl_adapter.py
   6 src-inspect-ai/tests/test_admitted_run.py
   6 seon-skills/datahike/SKILL.md
   6 docs/seon/lineage/concepts-and-origins.md
   6 docs/seon/issues/restore-intent-does-not-freeze-client-artifact.md
   6 docs/seon/issues/config-apply-rebuilds-unchanged-runtime.md
   6 docs/seon/issues/archive/reload-core-fault-policy-bypass.md
   6 docs/seon/issues/archive/hot-reload-rejects-database-event-and-forces-pod-shutdown.md
   6 docs/prds/runtime-reliability/research/cleanup-audit-config-startup-2026-07-20.md
   6 docs/prds/runtime-reliability/research/browser-datastar-graduation-matrix-2026-07-19.md
   6 docs/prds/runtime-reliability/AGENTS.md
   6 docs/prds/database-authority-mesh/research/unit-8-authored-source-loading-seam-2026-07-16.md
   6 acme/README.md
   6 .agents/skills/datahike/SKILL.md
   5 test/seon/dev/config_test.clj
   5 src/seon/test/runner.cljs
   5 src/seon/AGENTS.md
   5 src-inspect-ai/src/seon_inspect/tasks/frozen_tool_rows.py
   5 src-inspect-ai/src/seon_inspect/bench_common.py
   5 docs/seon/vision/prior-art-credits-2026-05-23.md
   5 docs/seon/issues/eval-process-isolation-memory-containment.md
   5 docs/seon/issues/duplicate-allowed-domains-schema-crashes-hot-reload.md
   5 docs/seon/issues/container-launch-omits-execution-artifact.md
   5 docs/seon/issues/archive/uds-session-close-discards-cause.md
   5 docs/seon/issues/archive/production-pod-cannot-resolve-compiled-vars.md
   5 docs/seon/issues/archive/pod-hot-reload-retains-cljs-heap.md
   5 docs/seon/issues/archive/orchestration-wrapper-dropped-child-recovery-evidence.md
   5 docs/seon/issues/archive/instrumentation-collect-clean-build-empty.md
   5 docs/seon/issues/archive/changed-test-full-widening-parity.md
   5 docs/seon/components/agent-reply-segmenter.md
   5 docs/seon/architecture/architecture.md
   5 docs/prds/database-authority-mesh/research/system-recovery-graduation-plan-2026-07-16.md
   5 docs/prds/database-authority-mesh/research/render-context-single-owner-cut-2026-07-16.md
   5 bin/test-cljs
   5 .agents/skills/datastar-web-ui/SKILL.md
   5 .agents/skills/browser-automation/SKILL.md
   4 test/seon/web/serve_test.cljs
   4 test/seon/log_test.cljs
   4 src/seon/web/router.cljs
   4 src/seon/repair/candidates.cljs
   4 src/seon/agent/shell/internal.cljs
   4 src/seon/agent/shell.cljs
   4 src-inspect-ai/src/seon_inspect/reachability.py
   4 src-inspect-ai/src/seon_inspect/freeze.py
   4 seon-skills/data-oriented-clojure/SKILL.md
   4 docs/seon/reference/third-party-setup.md
   4 docs/seon/reference/async-ui-patterns.md
   4 docs/seon/issues/restore-completion-cannot-precede-admission.md
   4 docs/seon/issues/downstream-runtime-package-is-not-self-contained.md
   4 docs/seon/issues/archive/status-requires-the-up-environment-to-recognize-healthy-processes.md
   4 docs/seon/issues/archive/sci-bounding-fallback-plan-block.md
   4 docs/seon/issues/archive/restart-cannot-reuse-a-stopped-watchers-client-manifest.md
   4 docs/seon/issues/archive/managed-pod-readiness-parent-missing.md
   4 docs/seon/issues/archive/frozen-tool-fixture-uses-retired-database-wrappers.md
   4 docs/seon/issues/archive/eval-memory-safety.md
   4 docs/seon/issues/archive/downstream-rebuild-tried-to-stop-shared-writer.md
   4 docs/seon/issues/archive/downstream-functions-missing-from-production-execution-child.md
   4 docs/seon/issues/archive/completed-intermediate-turn-reset-execution-crash-breaker.md
   4 docs/seon/issues/archive/bun-only-pod-was-launched-with-node.md
   4 docs/seon/issues/archive/branch-close-misread-remote-connection-id-and-release-result.md
   4 docs/seon/issues/archive/agents-als-tests-fail-under-mcp.md
   4 docs/seon/issues/archive/agent-filesystem-edit-published-malformed-clojure.md
   4 docs/seon/issues/archive/acme-shadow-command-config-isolation.md
   4 docs/prds/runtime-reliability/research/token-reporting-surface-audit-2026-07-12.md
   4 docs/prds/runtime-reliability/research/root-view-presence-crash-batch-audit-2026-07-13.md
   4 docs/prds/runtime-reliability/research/architecture-target-drift-audit-2026-07-14.md
   4 docs/prds/database-authority-mesh/research/source-grounded-research-tasks-2026-07-15.md
   4 docs/prds/database-authority-mesh/research/cleanup-audit-duplicate-interfaces-2026-07-20.md
   4 deps.edn
   4 config/minimal.edn
   4 acme/src/acme/pod.cljs
   4 .agents/skills/data-oriented-clojure/SKILL.md
   3 test/seon/runtime/recovery_test.cljs
   3 src/seon/web/AGENTS.md
   3 src/seon/ui/html.cljc
   3 src/seon/render/canvas.cljs
   3 src/seon/platform.cljs
   3 src/seon/error.cljs
   3 src/seon/ai/openai_compat.cljs
   3 src/seon/agent/web.cljs
   3 src/seon/agent/search/internal.cljs
   3 src/seon/agent/run.cljs
   3 src/seon/agent.cljs
   3 src-inspect-ai/src/seon_inspect/tasks/namespace_reachability.py
   3 src-inspect-ai/src/seon_inspect/offline_proof.py
   3 seon-skills/datahike/references/datahike-internals.md
   3 docs/seon/reference/separate-jvm-exploration.md
   3 docs/seon/issues/selfhost-cljs-test-is-thunk-resolution.md
   3 docs/seon/issues/persisted-program-error-prevents-agent-repair.md
   3 docs/seon/issues/inspect-source-dependency-is-not-content-pinned.md
   3 docs/seon/issues/archive/transcript-policy-default-read-stale-projection.md
   3 docs/seon/issues/archive/ticker-core-fault-policy-bypass.md
   3 docs/seon/issues/archive/render-full-attribute-was-absent-from-bootstrap-schema.md
   3 docs/seon/issues/archive/remote-stale-basis-error-key-drift.md
   3 docs/seon/issues/archive/pod-runtime-lacked-non-autonomous-launch-and-complete-inverse.md
   3 docs/seon/issues/archive/operator-read-only-filesystem-grant-was-unlocked.md
   3 docs/seon/issues/archive/node-test-untestable-context-system.md
   3 docs/seon/issues/archive/datahike-query-stats-fixture-leaked-connections.md
   3 docs/seon/issues/archive/config-database-arities-used-quoted-predicate-schemas.md
   3 docs/seon/issues/archive/branch-restart-stop-open-gap.md
   3 docs/seon/issues/archive/acme-no-sci-eval-seam.md
   3 docs/seon/components/web-brand.md
   3 docs/prds/runtime-reliability/system-audit-2026-07-12.md
   3 docs/prds/runtime-reliability/research/test-runtime-trim-design-2026-07-12.md
   3 docs/prds/runtime-reliability/research/live-inspect-contract-audit-2026-07-19.md
   3 docs/prds/runtime-reliability/research/dependency-shadow-mcp-acme-post-integration-audit-2026-07-14.md
   3 docs/prds/runtime-reliability/research/config-coherence-audit-2026-06-28.md
   3 docs/prds/runtime-reliability/provenance-and-lifecycle-design.md
   3 docs/prds/database-authority-mesh/research/client-single-lifecycle-audit-2026-07-17.md
   3 docs/prds/database-authority-mesh/research/cleanup-audit-jvm-residue-2026-07-20.md
   3 docs/prds/database-authority-mesh/research/agent-lifecycle-native-result-database-value-cut-2026-07-16.md
   3 .agents/skills/seon-context-config/SKILL.md
   3 .agents/skills/datahike/references/datahike-internals.md
   2 test/seon/ui/html_test.cljc
   2 test/seon/render/value_test.cljs
   2 test/seon/render/canvas_test.cljs
   2 test/seon/launch_test.cljs
   2 test/seon/index_core_test.cljs
   2 test/seon/error_record_test.cljs
   2 test/seon/embed_test.cljs
   2 test/seon/config_test.cljs
   2 test/seon/agent/search_test.cljs
   2 src/seon/warn.cljs
   2 src/seon/route.cljs
   2 src/seon/retry.cljs
   2 src/seon/repl/internal.cljc
   2 src/seon/repl.cljs
   2 src/seon/launch.cljc
   2 src/seon/instrument.cljc
   2 src/seon/eval/bootstrap_cache.cljs
   2 src/seon/diffusion/oracle.cljs
   2 src/seon/diffusion/grammar.cljc
   2 src/seon/dev/runtime_id.cljc
   2 src/seon/dev/restore/schema.cljc
   2 src/seon/dev/markdown.clj
   2 src/seon/ai/AGENTS.md
   1 src/seon/ai.cljs
   2 src/seon/agent/ctx/transcript.cljs
   2 src-inspect-ai/tests/test_cluster.py
   2 src-inspect-ai/src/seon_inspect/generators.py
   2 seon-skills/datahike/references/data-modeling.md
   2 script/seon/dev/artifact.clj
   2 docs/seon/reference/third-party-integration.md
   2 docs/seon/issues/lazy-view-unit-activation-drops-read-observations.md
   2 docs/seon/issues/index.md
   2 docs/seon/issues/changed-test-new-cljs-namespace-misses-runtime-file.md
   2 docs/seon/issues/autocomplete-worktree-evidence-preservation.md
   2 docs/seon/issues/archive/test-suite-audit-2026-06-25.md
   2 docs/seon/issues/archive/shared-writer-cluster-did-not-select-fresh-config.md
   2 docs/seon/issues/archive/replica-used-ambient-route-for-branch-writes-and-feed.md
   2 docs/seon/issues/archive/repeated-session-initialization-retained-database-reference.md
   2 docs/seon/issues/archive/public-pull-map-used-transport-field-names.md
   2 docs/seon/issues/archive/production-execution-child-bootstrap-misses-goog.md
   2 docs/seon/issues/archive/post-commit-program-publication-leaves-admission-open.md
   2 docs/seon/issues/archive/plan-html-renderer-does-not-match-render-interface.md
   2 docs/seon/issues/archive/namespace-summary-misses-cold-schema-and-index-publication.md
   2 docs/seon/issues/archive/legacy-containment-record-wedges-clean-down.md
   2 docs/seon/issues/archive/instrumented-query-lost-one-argument-accessor.md
   2 docs/seon/issues/archive/inspect-database-scorer-assumes-nonexistent-evidence.md
   2 docs/seon/issues/archive/fresh-database-initialization-ignored-entity-schema-attributes.md
   2 docs/seon/issues/archive/execution-invocation-contained-non-eager-value.md
   2 docs/seon/issues/archive/execution-host-was-not-configured-at-runtime-start.md
   2 docs/seon/issues/archive/context-loop-regression-sweep-2026-06-25.md
   2 docs/seon/issues/archive/context-derived-not-stored.md
   2 docs/seon/issues/archive/context-budget-fn-head-lean.md
   2 docs/seon/issues/archive/config-apply-instrumentation-rejected-two-member-vectors.md
   2 docs/seon/issues/archive/composition-door-omits-effective-timeout-evidence.md
   2 docs/seon/issues/archive/cluster-reset-reused-stale-artifacts.md
   2 docs/seon/issues/archive/changed-test-reference-repository-widening.md
   2 docs/seon/issues/archive/changed-test-hook-drops-build-inputs.md
   2 docs/seon/issues/archive/agents-run-does-not-host-explicit-durable-agent.md
   2 docs/seon/issues/ai-context-is-not-pure-over-database-value.md
   2 docs/seon/components/testing.md
   2 docs/seon/components/embedding-retrieval.md
   2 docs/seon/components/database.md
   2 docs/seon/architecture/toolkit.md
   2 docs/seon/architecture/library-grounding.md
   2 docs/seon/architecture/decisions/006-separate-jvm.md
   2 docs/seon/architecture/archive/jvm-main-app.md
   2 docs/seon/_dashboard.md
   2 docs/prds/runtime-reliability/research/surface-vocabulary-and-dead-ui-path-audit-2026-07-13.md
   2 docs/prds/runtime-reliability/research/research-localization-classification-2026-07-14.md
   2 docs/prds/runtime-reliability/research/issue-authority-and-startup-triage-audit-2026-07-14.md
   2 docs/prds/runtime-reliability/research/cljs-test-suite-speed-and-quality-audit-2026-07-12.md
   2 docs/prds/database-authority-mesh/research/schema-bootstrap-order-2026-07-16.md
   2 docs/prds/database-authority-mesh/research/run-native-result-database-value-cut-2026-07-16.md
   2 docs/prds/database-authority-mesh/research/plan-single-owner-audit-2026-07-17.md
   2 docs/prds/database-authority-mesh/research/persistent-bun-session-atomic-replacement-inventory-2026-07-16.md
   2 docs/prds/database-authority-mesh/research/parallel-behavior-unification-audit-2026-07-17.md
   2 docs/prds/database-authority-mesh/research/multidatabase-session-lifetime-2026-07-16.md
   2 docs/prds/database-authority-mesh/research/message-idempotent-delivery-seam-2026-07-16.md
   2 docs/prds/database-authority-mesh/research/execution-artifact-database-dependency-seam-2026-07-16.md
   2 docs/prds/database-authority-mesh/research/datastar-bun-authority-interface-audit-2026-07-17.md
   2 docs/prds/database-authority-mesh/research/cljs-cache-change-gates-2026-07-15.md
   2 docs/prds/database-authority-mesh/research/atomic-replica-publisher-deletion-audit-2026-07-16.md
   2 bin/test-parser
   2 acme/src/acme/context.cljs
   2 acme/deps.edn
   2 .agents/skills/datastar-web-ui/references/design-principles.md
   2 .agents/skills/datahike/references/data-modeling.md
   1 test/seon/test_seed.cljs
   1 test/seon/test/runner_timeout_test.cljs
   1 test/seon/test/runner_timeout_probes.cljs
   1 test/seon/repl/internal_test.cljc
   1 test/seon/repair_test.cljc
   1 test/seon/repair_candidates_test.cljs
   1 test/seon/execution_test.cljs
   1 test/seon/eval/race_timeout_test.cljs
   1 test/seon/eval/memory_safety_test.cljs
   1 test/seon/dev/mcp_test.clj
   1 test/seon/dev/changed_test_test.clj
   1 test/seon/db/restore_admin_test.clj
   1 test/seon/client_advertisement_test.cljs
   1 test/seon/analyzer_info_test.cljs
   1 test/seon/agent/shell_test.cljs
   1 test/seon/agent/fs_test.cljs
   1 src/seon/worker_validator.cljs
   1 src/seon/web/datastar.cljs
   1 src/seon/web/brand.cljs
   1 src/seon/runtime/lifecycle.cljc
   1 src/seon/repair.cljc
   1 src/seon/render/value.cljs
   1 src/seon/indexing.clj
   1 src/seon/diffusion/retrieval.cljs
   1 src/seon/dev/restore.clj
   1 src/seon/db/restore/schema.cljc
   1 src/seon/db/program.clj
   1 src/seon/db.cljs
   1 src/seon/client/schema.cljc
   1 src/seon/ai/typeahead.cljs
   1 src/seon/ai/anthropic.cljs
   1 src/seon/agent/schedule.cljs
   1 src/seon/agent/loop.cljs
   1 src/seon/agent/home.cljs
   1 src/seon/agent/fs/internal.cljs
   1 src/seon/agent/ctx/warnings.cljs
   1 src/seon/agent/ctx/typeahead_steps.cljs
   1 src/seon/agent/AGENTS.md
   1 src/my/plan/internal.cljs
   1 src/my/blob.cljs
   1 src-inspect-ai/tests/test_typeahead_replay.py
   1 src-inspect-ai/tests/test_tool_generators.py
   1 src-inspect-ai/tests/test_tb2_agent.py
   1 src-inspect-ai/tests/test_oracle_scorers.py
   1 src-inspect-ai/tests/test_branch_lease.py
   1 src-inspect-ai/src/seon_inspect/tool_rows.py
   1 src-inspect-ai/src/seon_inspect/tasks/typeahead_replay.py
   1 src-inspect-ai/src/seon_inspect/tasks/swe_bench_seon.py
   1 src-inspect-ai/src/seon_inspect/oracle_scorers.py
   1 src-inspect-ai/src/seon_inspect/__init__.py
   1 seon-skills/datahike/references/querying.md
   1 seon-skills/data-modeling/SKILL.md
   1 script/seon/dev/config.clj
   1 docs/seon/vision/prior-art-agents-and-evolution-2026-05-23.md
   1 docs/seon/vision/m1-reliable-runtime.md
   1 docs/seon/vision/capabilities/repl-eval-pipeline.md
   1 docs/seon/vision/capabilities/database-platform.md
   1 docs/seon/issues/wake-and-replay-can-drive-the-same-open-run.md
   1 docs/seon/issues/uds-fragment-accumulation-recopies-complete-prefix.md
   1 docs/seon/issues/test-runner-does-not-prepare-selected-git-dependencies.md
   1 docs/seon/issues/tailwind-node-module-register-deprecation.md
   1 docs/seon/issues/single-entity-pulls-budgeted-as-one-result-node.md
   1 docs/seon/issues/rendering-and-turns-collided-in-one-execution-child.md
   1 docs/seon/issues/preflight-repair-consumed-referred-macros.md
   1 docs/seon/issues/planned-restart-cannot-observe-writer-drain-result.md
   1 docs/seon/issues/message-wake-attaches-catch-to-the-handler-function.md
   1 docs/seon/issues/kimi-k3-continuation-compatibility.md
   1 docs/seon/issues/inspect-product-snapshot-assumes-nonexistent-evidence.md
   1 docs/seon/issues/inspect-model-transport-evidence-is-incomplete.md
   1 docs/seon/issues/human-message-renews-stale-open-run.md
   1 docs/seon/issues/execution-result-diagnostic-retained-invalid-map-key.md
   1 docs/seon/issues/embedding-first-write-lookup-noise.md
   1 docs/seon/issues/datastar-feed-retains-failed-render-after-hot-reload.md
   1 docs/seon/issues/changed-test-hooks-queue-stale-runs-behind-active-owner.md
   1 docs/seon/issues/bootstrap-analyzer-api-emits-undeclared-var-warnings.md
   1 docs/seon/issues/atomic-client-authority-cut-in-progress.md
   1 docs/seon/issues/archive/watchdog-query-result-schema-rejected-datahike-set.md
   1 docs/seon/issues/archive/warnings-misfire-core-schemas.md
   1 docs/seon/issues/archive/undo-target-is-not-bound-to-retained-completion.md
   1 docs/seon/issues/archive/toolkit-read-tests-retained-local-datahike.md
   1 docs/seon/issues/archive/test-runner-fixture-opened-a-local-datahike-database.md
   1 docs/seon/issues/archive/state-three-mechanisms.md
   1 docs/seon/issues/archive/startup-namespace-repair-assumed-home-entity.md
   1 docs/seon/issues/archive/simple-explicit-completion-consumed-ten-agent-turns.md
   1 docs/seon/issues/archive/scanner-missing-as-alias.md
   1 docs/seon/issues/archive/repeated-namespace-reentry-lost-cold-aliases.md
   1 docs/seon/issues/archive/reactive-failed-render-recorded-fault-feedback-loop.md
   1 docs/seon/issues/archive/program-initialization-treated-protocol-schemas-as-datahike-attributes.md
   1 docs/seon/issues/archive/overlap-three-sse-push.md
   1 docs/seon/issues/archive/overlap-three-rendering.md
   1 docs/seon/issues/archive/overlap-three-ai-context.md
   1 docs/seon/issues/archive/orphan-keyword-namespaces.md
   1 docs/seon/issues/archive/ordinary-eval-statement-context-dropped-result.md
   1 docs/seon/issues/archive/operator-path-identity-is-codex-task-specific.md
   1 docs/seon/issues/archive/no-unified-namespace-model.md
   1 docs/seon/issues/archive/no-live-subscriptions.md
   1 docs/seon/issues/archive/no-custom-namespace-behavior.md
   1 docs/seon/issues/archive/no-agent-stuck-detection.md
   1 docs/seon/issues/archive/namespaces-block-schema-publication-regressed.md
   1 docs/seon/issues/archive/namespace-render-test-retained-removed-renderer.md
   1 docs/seon/issues/archive/missing-malli-schema.md
   1 docs/seon/issues/archive/mcp-shadow-flavor-discovery-gap.md
   1 docs/seon/issues/archive/mcp-default-cljs-session-retains-replaced-shadow-port.md
   1 docs/seon/issues/archive/live-tile-nil-entity-render-failed.md
   1 docs/seon/issues/archive/live-data-and-debug-views-exposed-schema-boundary-drift.md
   1 docs/seon/issues/archive/lint-hook-jvm-oom-2026-06-09.md
   1 docs/seon/issues/archive/lifecycle-coupling-bottleneck.md
   1 docs/seon/issues/archive/launch-agent-blocks-nrepl.md
   1 docs/seon/issues/archive/interrupted-writer-test-can-outlive-its-runner.md
   1 docs/seon/issues/archive/instrumentation-coverage-schema-was-not-indexed.md
   1 docs/seon/issues/archive/incomplete-multi-arity-contract-corrupts-runtime-wrapper.md
   1 docs/seon/issues/archive/hot-reload-spec-projection-stale.md
   1 docs/seon/issues/archive/flow-pool-integrant-surgical-2026-06-09.md
   1 docs/seon/issues/archive/execution-package-omitted-agent-toolkit.md
   1 docs/seon/issues/archive/execution-invocation-during-child-retirement-is-rejected.md
   1 docs/seon/issues/archive/eval-form-namespace-mismatch.md
   1 docs/seon/issues/archive/development-mcp-integration-drift.md
   1 docs/seon/issues/archive/dev-eval-top-frame-misclassified-core.md
   1 docs/seon/issues/archive/db-ops-any-returns.md
   1 docs/seon/issues/archive/datahike-planner-on-preexisting-failures.md
   1 docs/seon/issues/archive/database-receipt-schema-bypasses-candidate.md
   1 docs/seon/issues/archive/database-protocol-adr-described-removed-replica.md
   1 docs/seon/issues/archive/concurrent-read-observed-partial-reconnect.md
   1 docs/seon/issues/archive/client-paren-balancer-vs-parse-forms.md
   1 docs/seon/issues/archive/changed-test-omitted-program-source-artifact-identity.md
   1 docs/seon/issues/archive/changed-test-manifest-does-not-converge.md
   1 docs/seon/issues/archive/changed-test-hook-backlog-is-not-coalesced.md
   1 docs/seon/issues/archive/canvas-history-used-query-flag-instead-of-database-value.md
   1 docs/seon/issues/archive/blob-storage-view-integrity.md
   1 docs/seon/issues/archive/atom-watches-bypass-flow.md
   1 docs/seon/issues/archive/ai-env-test-fixture-omitted-recognized-vars.md
   1 docs/seon/issues/archive/agents-run-timeout-bypasses-run-policy.md
   1 docs/seon/issues/archive/agent-view-query-budgets-masked-database-errors.md
   1 docs/seon/issues/archive/agent-pool-sigkill-cycle.md
   1 docs/seon/issues/archive/agent-birth-used-root-count-as-pull-result-budget.md
   1 docs/seon/issues/acme-typeahead-worker-unavailable.md
   1 docs/seon/concepts/code-as-data-runtime.md
   1 docs/seon/components/web-ui.md
   1 docs/seon/components/namespaces-render.md
   1 docs/seon/components/loadable-skills.md
   1 docs/seon/architecture/ui.md
   1 docs/seon/architecture/decisions/007-runtime-instrumentation.md
   1 docs/seon/architecture/decisions/005-flow-adoption.md
   1 docs/seon/architecture/data-model.md
   1 docs/prds/runtime-reliability/research/schema-generation-lifecycle-audit-2026-07-15.md
   1 docs/prds/runtime-reliability/research/runtime-reconstruction-and-replay-boundary-2026-07-12.md
   1 docs/prds/runtime-reliability/research/remaining-worktree-semantic-integration-audit-2026-07-14.md
   1 docs/prds/runtime-reliability/research/reference-source-integrity-audit-2026-07-14.md
   1 docs/prds/runtime-reliability/research/automatic-test-feedback-infrastructure-audit-2026-07-14.md
   1 docs/prds/database-authority-mesh/research/remaining-prompt-block-acquisition-cuts-2026-07-16.md
   1 docs/prds/database-authority-mesh/research/message-native-result-database-value-cut-2026-07-16.md
   1 docs/prds/database-authority-mesh/research/membership-quiescence-single-owner-audit-2026-07-17.md
   1 docs/prds/database-authority-mesh/research/database-value-protocol-cut-2026-07-16.md
   1 docs/prds/database-authority-mesh/research/compiled-child-prompt-owner-2026-07-16.md
   1 docs/prds/database-authority-mesh/research/cleanup-audit-vocabulary-2026-07-20.md
   1 docs/prds/database-authority-mesh/research/bun-child-supervision-seam-2026-07-16.md
   1 docs/prds/database-authority-mesh/research/authority-heavy-class-proof-plan-2026-07-15.md
   1 docs/prds/database-authority-mesh/research/async-db-facade-source-audit-2026-07-16.md
   1 docs/prds/database-authority-mesh/AGENTS.md
   1 config/minimal-stream.edn
   1 config/minimal-plan.edn
   1 acme/src/acme/overrides.cljs
   1 acme/gym/scenarios/pure-mean.edn
   1 acme/gym/scenarios/celsius-killgate.edn
   1 acme/gym/diffusion_gym.bb
   1 .agents/skills/datahike/references/querying.md
   1 .agents/skills/data-modeling/SKILL.md

```
