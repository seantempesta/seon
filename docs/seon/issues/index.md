---
type: orchestrator
status: active
tags: [orchestrator, issue, index]
---

# Open Issues — Index

GENERATED FILE — do not hand-edit. Regenerate with `bin/issues-index`.
Lifecycle `open → resolved | superseded`; closed issues live in `archive/`.
See `README.md` for the convention.

## Blocker (6)

| Issue | Severity | Lane |
|-------|----------|------|
| [Arbitrary eval allocation lacks hard process memory containment](eval-process-isolation-memory-containment.md) | blocker | agent |
| [Capture dependencies when a lazy view unit activates](lazy-view-unit-activation-drops-read-observations.md) | blocker | UI |
| [Carry one complete database coordinate through the protocol](database-protocol-coordinate-is-incomplete.md) | blocker | Core |
| [Database receipts bypass the canonical schema candidate](database-receipt-schema-bypasses-candidate.md) | blocker | Core |
| [Implement browser-session navigation provenance](web-session-navigation-provenance-is-missing.md) | blocker | UI |
| [Make the downstream runtime package self-contained](downstream-runtime-package-is-not-self-contained.md) | blocker | Core |

## Friction (30)

| Issue | Severity | Lane |
|-------|----------|------|
| [ACME cannot migrate safely through the current operator](acme-operator-migration-drift.md) | friction | UI |
| [AI and HTML render twins may run one derivation twice](render-twin-runs-function-twice.md) | friction | UI |
| [AI context is not pure over its database value](ai-context-is-not-pure-over-database-value.md) | friction | agent |
| [Address-message steps can displace authored plan work](plan-address-step-priority.md) | friction | agent |
| [Async structural functions bypass contract validation](async-contract-instrumentation-gap.md) | friction | agent |
| [Autocomplete datasets and scoring bypass canonical runtime projections](autocomplete-data-quality-pipeline-drift.md) | friction | agent |
| [Autocomplete worktrees contain unclassified database and model evidence](autocomplete-worktree-evidence-preservation.md) | friction | Core |
| [Canvas controls hide pending and handler failure](canvas-controls-hide-pending-and-failure.md) | friction | UI |
| [Config apply rebuilds an unchanged runtime](config-apply-rebuilds-unchanged-runtime.md) | friction | general |
| [Content-pin the Inspect source dependency](inspect-source-dependency-is-not-content-pinned.md) | friction | agent |
| [Context block order is static](context-block-order-is-static.md) | friction | agent |
| [Cross-agent planners can reopen worker-completed steps](plan-reopen-cross-agent-authority.md) | friction | agent |
| [Database query tuple results are hard for agents to read](database-query-tuple-shape-legibility.md) | friction | agent |
| [Edit-hook feedback can target a different checkout](worktree-edit-hook-checkout-drift.md) | friction | general |
| [Embedding boot noise — 232 `:entity-id/missing` errors on fresh seed](embedding-first-write-lookup-noise.md) | friction | agent |
| [Focused tests expose recovery schema load-order coupling](focused-test-schema-load-order.md) | friction | Core |
| [Give root a dedicated system layout](root-page-is-an-ordinary-agent-layout.md) | friction | UI |
| [Idle transcript misreports the mode-specific work budget](configured-turn-limit-masks-mode-specific-budget.md) | friction | agent |
| [Include new CLJS namespaces in changed-test runtime artifacts](changed-test-new-cljs-namespace-misses-runtime-file.md) | friction | Core |
| [Inspect live callers use retired cluster lifecycle contracts](inspect-live-cluster-caller-drift.md) | friction | agent |
| [Keep Datahike system attributes out of the domain navigator](database-browser-misclassifies-datahike-system-attributes.md) | friction | UI |
| [LoRA audit runner depends on a retired Shadow target and pinned checkout](lora-audit-runner-drift.md) | friction | general |
| [Model can ghost-echo runtime scaffolding into the transcript spine](narration-ghost-echo-not-neutralized.md) | friction | agent |
| [Plan completion has no checkable verification evidence](plan-completion-verification-evidence.md) | friction | agent |
| [Remove the Node module-register deprecation from CSS builds](tailwind-node-module-register-deprecation.md) | friction | UI |
| [Remove undeclared-var warnings from the self-host bootstrap build](bootstrap-analyzer-api-emits-undeclared-var-warnings.md) | friction | agent |
| [Self-host `cljs.test/is` throws inside a dynamically-evaled `:test` thunk](selfhost-cljs-test-is-thunk-resolution.md) | friction | agent |
| [Shadow deps-mode declarations imply inactive build paths](shadow-deps-mode-declaration-drift.md) | friction | docs |
| [Thread one database value through debug and data feeds](debug-feed-captures-foreign-database-reads.md) | friction | UI |
| [Transcript decay does not bound total context](transcript-decay-does-not-bound-total-context.md) | friction | agent |

## Cleanup (8)

| Issue | Severity | Lane |
|-------|----------|------|
| [Agent tools may silently accept unknown request keys](agent-tool-unknown-key-acceptance.md) | cleanup | agent |
| [Deprecated skills and context functions remain eligible for program indexing](deprecated-skill-render-functions-indexed.md) | cleanup | agent |
| [Partially-Stale Reference Docs Need Updates](stale-reference-docs.md) | cleanup | docs |
| [Subagents block is implemented but not installed](subagents-block-is-implemented-but-not-installed.md) | cleanup | agent |
| [Surface recency may be recomputed globally](surface-recency-recomputed.md) | cleanup | UI |
| [Unify the two AsyncLocalStorage stores; rename with-tx-context → with-tx-meta](als-unify-tx-meta.md) | cleanup | Core |
| [eval/transact on a non-primary (scratch) conn returns ok? but doesn't commit](eval-scratch-conn-no-commit.md) | cleanup | agent |
| [parse-forms entries: missing :malli/schema + bare keys](parse-forms-entry-schema-and-bare-keys.md) | cleanup | agent |
