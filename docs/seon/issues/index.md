---
type: orchestrator
status: active
tags: [orchestrator, issue, index]
---

# Open Issues — Index

GENERATED FILE — do not hand-edit. Regenerate with `bin/issues-index`.
Lifecycle `open → resolved | superseded`; closed issues live in `archive/`.
See `README.md` for the convention.

## Blocker (14)

| Issue | Severity | Lane |
|-------|----------|------|
| [Arbitrary eval allocation lacks hard process memory containment](eval-process-isolation-memory-containment.md) | blocker | agent |
| [Bind clean-or-force evidence to one exact managed generation](clean-or-force-evidence-can-cross-or-falsely-report-absence.md) | blocker | Core |
| [Capture dependencies when a lazy view unit activates](lazy-view-unit-activation-drops-read-observations.md) | blocker | UI |
| [Carry one complete database coordinate through the protocol](database-protocol-coordinate-is-incomplete.md) | blocker | Core |
| [Changed-test hooks can queue stale runs behind an active owner](changed-test-hooks-queue-stale-runs-behind-active-owner.md) | blocker | Core |
| [Content-pin the Inspect source dependency](inspect-source-dependency-is-not-content-pinned.md) | blocker | agent |
| [Enforce the eval render cap for failed diagnostics](failed-eval-diagnostics-bypass-render-cap.md) | blocker | agent |
| [Implement browser-session navigation provenance](web-session-navigation-provenance-is-missing.md) | blocker | UI |
| [Keep a running target's bootstrap artifact immutable](shared-bootstrap-output-mutates-running-artifact.md) | blocker | Core |
| [Keep a stable owner until the pod execution subtree drains](dead-process-group-leader-blocks-safe-subtree-drain.md) | blocker | Core |
| [Make the downstream runtime package self-contained](downstream-runtime-package-is-not-self-contained.md) | blocker | Core |
| [Make writer drain proof consumable by the operator](planned-restart-cannot-observe-writer-drain-result.md) | blocker | Core |
| [Prove database workflow answers from retained query evidence](database-workflow-scorer-lacks-query-result-evidence.md) | blocker | agent |
| [Retain complete model transport evidence in Inspect logs](inspect-model-transport-evidence-is-incomplete.md) | blocker | agent |

## Friction (33)

| Issue | Severity | Lane |
|-------|----------|------|
| [ACME cannot migrate safely through the current operator](acme-operator-migration-drift.md) | friction | UI |
| [ACME typeahead worker is unavailable during live Inspect runs](acme-typeahead-worker-unavailable.md) | friction | agent |
| [AI and HTML render twins may run one derivation twice](render-twin-runs-function-twice.md) | friction | UI |
| [AI context is not pure over its database value](ai-context-is-not-pure-over-database-value.md) | friction | agent |
| [Address-message steps can displace authored plan work](plan-address-step-priority.md) | friction | agent |
| [Autocomplete datasets and scoring bypass canonical runtime projections](autocomplete-data-quality-pipeline-drift.md) | friction | agent |
| [Autocomplete worktrees contain unclassified database and model evidence](autocomplete-worktree-evidence-preservation.md) | friction | Core |
| [Canvas controls hide pending and handler failure](canvas-controls-hide-pending-and-failure.md) | friction | UI |
| [Config apply rebuilds an unchanged runtime](config-apply-rebuilds-unchanged-runtime.md) | friction | general |
| [Context block order is static](context-block-order-is-static.md) | friction | agent |
| [Cross-agent planners can reopen worker-completed steps](plan-reopen-cross-agent-authority.md) | friction | agent |
| [Database query tuple results are hard for agents to read](database-query-tuple-shape-legibility.md) | friction | agent |
| [Edit-hook feedback can target a different checkout](worktree-edit-hook-checkout-drift.md) | friction | general |
| [Embedding boot noise — 232 `:entity-id/missing` errors on fresh seed](embedding-first-write-lookup-noise.md) | friction | agent |
| [Give Inspect live callers an ownership-fenced cluster lease](inspect-live-cluster-caller-drift.md) | friction | agent |
| [Give root a dedicated system layout](root-page-is-an-ordinary-agent-layout.md) | friction | UI |
| [Idle transcript misreports the mode-specific work budget](configured-turn-limit-masks-mode-specific-budget.md) | friction | agent |
| [Include new CLJS namespaces in changed-test runtime artifacts](changed-test-new-cljs-namespace-misses-runtime-file.md) | friction | Core |
| [LoRA audit runner depends on a retired Shadow target and pinned checkout](lora-audit-runner-drift.md) | friction | general |
| [Make multi-form eval order a durable database fact](multi-form-eval-order-is-not-durable.md) | friction | agent |
| [Make schema hot reload atomic](hot-reload-schema-import-can-partially-fail.md) | friction | agent |
| [Model can ghost-echo runtime scaffolding into the transcript spine](narration-ghost-echo-not-neutralized.md) | friction | agent |
| [Plan completion has no checkable verification evidence](plan-completion-verification-evidence.md) | friction | agent |
| [Plan reconcile scope can delete unseen work](plan-reconcile-scope-can-delete-unseen-work.md) | friction | agent |
| [Prepare selected git dependencies before test compilation](test-runner-does-not-prepare-selected-git-dependencies.md) | friction | Core |
| [Preserve distinct large BigInts in cardinality-many attributes](datahike-cljs-cardinality-many-collapses-large-bigints.md) | friction | Core |
| [Remove the Node module-register deprecation from CSS builds](tailwind-node-module-register-deprecation.md) | friction | UI |
| [Remove undeclared-var warnings from the self-host bootstrap build](bootstrap-analyzer-api-emits-undeclared-var-warnings.md) | friction | agent |
| [Render logical Malli arities for pure-variadic functions](compact-pure-variadic-contract-mislabels-logical-arities.md) | friction | agent |
| [Self-host `cljs.test/is` throws inside a dynamically-evaled `:test` thunk](selfhost-cljs-test-is-thunk-resolution.md) | friction | agent |
| [Shadow deps-mode declarations imply inactive build paths](shadow-deps-mode-declaration-drift.md) | friction | docs |
| [Thread one database value through debug and data feeds](debug-feed-captures-foreign-database-reads.md) | friction | UI |
| [Transcript decay does not bound total context](transcript-decay-does-not-bound-total-context.md) | friction | agent |

## Cleanup (9)

| Issue | Severity | Lane |
|-------|----------|------|
| [Agent tools may silently accept unknown request keys](agent-tool-unknown-key-acceptance.md) | cleanup | agent |
| [Deprecated skills and context functions remain eligible for program indexing](deprecated-skill-render-functions-indexed.md) | cleanup | agent |
| [Make the preflight repair test declare its schema dependencies](preflight-repair-focused-selector-relies-on-ambient-schemas.md) | cleanup | agent |
| [Partially-Stale Reference Docs Need Updates](stale-reference-docs.md) | cleanup | docs |
| [Subagents block is implemented but not installed](subagents-block-is-implemented-but-not-installed.md) | cleanup | agent |
| [Surface recency may be recomputed globally](surface-recency-recomputed.md) | cleanup | UI |
| [Unify the two AsyncLocalStorage stores; rename with-tx-context → with-tx-meta](als-unify-tx-meta.md) | cleanup | Core |
| [eval/transact on a non-primary (scratch) conn returns ok? but doesn't commit](eval-scratch-conn-no-commit.md) | cleanup | agent |
| [parse-forms entries: missing :malli/schema + bare keys](parse-forms-entry-schema-and-bare-keys.md) | cleanup | agent |
