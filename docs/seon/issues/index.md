---
type: orchestrator
status: active
tags: [orchestrator, issue, index]
---

# Open Issues — Index

GENERATED FILE — do not hand-edit. Regenerate with `bin/issues-index`.
Lifecycle `open → resolved | superseded`; closed issues live in `archive/`.
See `README.md` for the convention.

## Blocker (46)

| Issue | Severity | Lane |
|-------|----------|------|
| [Accept Datahike collections when building the execution program](database-program-query-results-can-be-sets.md) | blocker | agent |
| [Agent-run settlement used wrong reactive database key](agents-run-settlement-used-wrong-reactive-database-key.md) | blocker | UI |
| [Arbitrary database results collide with the error shape](arbitrary-database-results-collide-with-error-shape.md) | blocker | Core |
| [Arbitrary eval allocation lacks hard process memory containment](eval-process-isolation-memory-containment.md) | blocker | agent |
| [Bind clean-or-force evidence to one exact managed generation](clean-or-force-evidence-can-cross-or-falsely-report-absence.md) | blocker | Core |
| [Capture dependencies when a lazy view unit activates](lazy-view-unit-activation-drops-read-observations.md) | blocker | UI |
| [Carry one complete database coordinate through the protocol](database-protocol-coordinate-is-incomplete.md) | blocker | Core |
| [Changed-test hooks can queue stale runs behind an active owner](changed-test-hooks-queue-stale-runs-behind-active-owner.md) | blocker | Core |
| [Compiled program contains nilable value schemas](compiled-program-contains-nilable-value-schemas.md) | blocker | Core |
| [Content-pin the Inspect source dependency](inspect-source-dependency-is-not-content-pinned.md) | blocker | agent |
| [Datahike force branch does not preserve secondary root](datahike-force-branch-does-not-preserve-secondary-root.md) | blocker | Core |
| [Derive read dependencies from Datahike's parsed semantics](datahike-read-dependencies-miss-valid-query-and-pull-inputs.md) | blocker | UI |
| [Fence accepted writes before retained-head preparation](restore-intent-lacks-exclusive-writer-fence.md) | blocker | Core |
| [Freeze one turn input across provider retries](turn-retries-reread-provider-inputs.md) | blocker | agent |
| [Freeze the restore pod artifact in confirmed intent](restore-intent-does-not-freeze-client-artifact.md) | blocker | Core |
| [Give the execution configuration pull retained-node headroom](execution-config-pull-had-one-node-budget.md) | blocker | agent |
| [Implement browser-session navigation provenance](web-session-navigation-provenance-is-missing.md) | blocker | UI |
| [Invalidate a cached failed Datastar render after its owner reloads](datastar-feed-retains-failed-render-after-hot-reload.md) | blocker | UI |
| [Keep a running target's bootstrap artifact immutable](shared-bootstrap-output-mutates-running-artifact.md) | blocker | Core |
| [Keep a stable owner until the pod execution subtree drains](dead-process-group-leader-blocks-safe-subtree-drain.md) | blocker | Core |
| [Keep database control entry independent of occupied handlers](uds-codec-capacity-can-delay-control-entry.md) | blocker | Core |
| [Keep restore publication closed through completion](restore-completion-cannot-precede-admission.md) | blocker | Core |
| [Key multi-source query sharing by every database value](multi-source-query-cache-retains-foreign-database-values.md) | blocker | Core |
| [Let the common Inspect pod solver address an existing agent](inspect-pod-solver-cannot-address-existing-agent.md) | blocker | agent |
| [Make the downstream runtime package self-contained](downstream-runtime-package-is-not-self-contained.md) | blocker | Core |
| [Make writer drain proof consumable by the operator](planned-restart-cannot-observe-writer-drain-result.md) | blocker | Core |
| [Pass a valid config singleton to final agent evidence](final-agent-evidence-pulled-a-partial-config-without-identity.md) | blocker | UI |
| [Pass the agent entity through the renderer's system-input key](welcome-canvas-received-the-agent-under-the-wrong-key.md) | blocker | UI |
| [Pod remains ready after losing its web listener](pod-remains-ready-after-web-listener-loss.md) | blocker | UI |
| [Project a turn's rendered transaction ref as its basis transaction](turn-debug-must-project-rendered-transaction-ref.md) | blocker | agent |
| [Projected map keys are not drill paths](projected-map-keys-are-not-drill-paths.md) | blocker | UI |
| [Put the database value in every transcript query member](transcript-grouped-reads-omitted-their-database-source.md) | blocker | agent |
| [Reduce retained memory in each execution child](execution-children-retain-hundreds-of-megabytes.md) | blocker | agent |
| [Restore writer admin transition is unimplemented](restore-writer-admin-transition-is-unimplemented.md) | blocker | Core |
| [Retain complete model transport evidence in Inspect logs](inspect-model-transport-evidence-is-incomplete.md) | blocker | agent |
| [Retain live eval values in the owning JVM host](retain-live-eval-values-in-the-owning-jvm-host.md) | blocker | agent |
| [Score reachability from real context transitions](inspect-reachability-assumes-nonexistent-evidence.md) | blocker | agent |
| [Separate restore intent from completion identity](restore-completion-reuses-operator-intent-identity.md) | blocker | Core |
| [Sequence calls within each agent execution child](rendering-and-turns-collided-in-one-execution-child.md) | blocker | UI |
| [Share one driver for an open agent run](wake-and-replay-can-drive-the-same-open-run.md) | blocker | agent |
| [Skip the receipt reread branch after a successful eval write](successful-eval-receipt-called-state-on-nil.md) | blocker | agent |
| [Supply the execution artifact to the production container launch](container-launch-omits-execution-artifact.md) | blocker | agent |
| [Toolkit current-ns wedges the agent since the cljc packaging window](toolkit-current-ns-wedges-agent-after-cljc-packaging.md) | blocker | agent |
| [Generated terminal test leaks the global database query function](generated-terminal-test-leaks-global-db-query.md) | blocker | test, database |
| [Full writer host fixtures lack the required value-sampling policy](full-writer-host-fixtures-lack-value-sampling-policy.md) | blocker | test, rendering |
| [Value drill has no total work bounds](value-drill-has-no-total-work-bounds.md) | blocker | UI |
| [Value-drill result literals failed boot schema admission](value-drill-result-literals-failed-boot-schema-admission.md) | blocker | Core |
| [ensure-database silently creates a fresh store at any requested path](ensure-database-creates-fresh-store-at-any-path.md) | blocker | Core |

## Friction (74)

| Issue | Severity | Lane |
|-------|----------|------|
| [A failed planner run leaves a generated root open with no re-drive](generated-root-has-no-planner-retry-path.md) | friction | agent |
| [ACME cannot migrate safely through the current operator](acme-operator-migration-drift.md) | friction | UI |
| [ACME typeahead worker is unavailable during live Inspect runs](acme-typeahead-worker-unavailable.md) | friction | agent |
| [AI and HTML render twins may run one derivation twice](render-twin-runs-function-twice.md) | friction | UI |
| [AI context is not pure over its database value](ai-context-is-not-pure-over-database-value.md) | friction | agent |
| [Address-message steps can displace authored plan work](plan-address-step-priority.md) | friction | agent |
| [Agent turns lack database read-cost attribution](agent-turns-lack-database-read-cost-attribution.md) | friction | agent |
| [Align Datahike HTTP remote connection identity](datahike-http-remote-connection-identity-mismatch.md) | friction | Core |
| [Atomic client authority cut is in progress](atomic-client-authority-cut-in-progress.md) | friction | Core |
| [Autocomplete datasets and scoring bypass canonical runtime projections](autocomplete-data-quality-pipeline-drift.md) | friction | agent |
| [Autocomplete worktrees contain unclassified database and model evidence](autocomplete-worktree-evidence-preservation.md) | friction | Core |
| [Bespoke reactive loops duplicate seon.reactive outside its owner](bespoke-reactive-loops-outside-seon-reactive.md) | friction | UI |
| [Bound temporal index-page work](bound-temporal-index-page-work.md) | friction | Core |
| [Branch trial tests write into the live operator state directory](branch-trial-tests-write-into-live-operator-state.md) | friction | Core |
| [Bun's rejection net loses AsyncLocalStorage scope](bun-rejection-net-loses-async-scope.md) | friction | general |
| [Canvas controls hide pending and handler failure](canvas-controls-hide-pending-and-failure.md) | friction | UI |
| [Canvas state returned a Promise as render data](canvas-state-returned-a-promise-as-render-data.md) | friction | UI |
| [Config apply rebuilds an unchanged runtime](config-apply-rebuilds-unchanged-runtime.md) | friction | general |
| [Context block order is static](context-block-order-is-static.md) | friction | agent |
| [Core selected render errors bypass crash policy](core-selected-render-errors-bypass-crash-policy.md) | friction | UI |
| [Cross-agent planners can reopen worker-completed steps](plan-reopen-cross-agent-authority.md) | friction | agent |
| [Database query tuple results are hard for agents to read](database-query-tuple-shape-legibility.md) | friction | agent |
| [Datahike execute-many predicate query fails](datahike-execute-many-predicate-query-fails.md) | friction | Core |
| [Dev-eval fault scope misses MCP funnels; a REPL typo crashes the pod](dev-eval-fault-scope-misses-mcp-funnels.md) | friction | general |
| [Edit-hook feedback can target a different checkout](worktree-edit-hook-checkout-drift.md) | friction | general |
| [Execution child program load omitted instrumentation](execution-child-program-load-omitted-instrumentation.md) | friction | agent |
| [Execution process proof seeds incomplete schema population](execution-process-proof-seeds-incomplete-schema-population.md) | friction | agent |
| [Execution result diagnostic retained invalid map key](execution-result-diagnostic-retained-invalid-map-key.md) | friction | agent |
| [Execution-child native heap mislabeled as GPU dominates footprint](execution-child-gpu-allocation-dominates-footprint.md) | friction | agent |
| [Give Inspect live callers an ownership-fenced cluster lease](inspect-live-cluster-caller-drift.md) | friction | agent |
| [Give root a dedicated system layout](root-page-is-an-ordinary-agent-layout.md) | friction | UI |
| [Host session errors vanish silently](host-session-errors-vanish-silently.md) | friction | agent |
| [Idle transcript misreports the mode-specific work budget](configured-turn-limit-masks-mode-specific-budget.md) | friction | agent |
| [Include new CLJS namespaces in changed-test runtime artifacts](changed-test-new-cljs-namespace-misses-runtime-file.md) | friction | Core |
| [Inspect product snapshot assumes nonexistent evidence](inspect-product-snapshot-assumes-nonexistent-evidence.md) | friction | agent |
| [Installed schema map misclassified as database error](installed-schema-map-misclassified-as-database-error.md) | friction | agent |
| [LoRA audit runner depends on a retired Shadow target and pinned checkout](lora-audit-runner-drift.md) | friction | general |
| [Make UDS frame accumulation linear](uds-fragment-accumulation-recopies-complete-prefix.md) | friction | Core |
| [Make dependency preparation deterministic under concurrent development](dependency-preparation-can-crash-inside-clojure-hashmap.md) | friction | Core |
| [Make multi-form eval order a durable database fact](multi-form-eval-order-is-not-durable.md) | friction | agent |
| [Make program indexing independent of the active schema projection](program-indexer-drops-valid-specs-outside-active-schema-projection.md) | friction | agent |
| [Nested authored render hides child reload](nested-authored-render-hides-child-reload.md) | friction | UI |
| [Operator trial processes leak across days](operator-trial-processes-leak-across-days.md) | friction | docs |
| [Persisted program error prevents agent repair](persisted-program-error-prevents-agent-repair.md) | friction | agent |
| [Plan allocation builder set database value](plan-allocation-builder-set-database-value.md) | friction | agent |
| [Plan completion has no checkable verification evidence](plan-completion-verification-evidence.md) | friction | agent |
| [Plan reconcile scope can delete unseen work](plan-reconcile-scope-can-delete-unseen-work.md) | friction | agent |
| [Preflight repair consumed referred macros](preflight-repair-consumed-referred-macros.md) | friction | agent |
| [Prepare selected git dependencies before test compilation](test-runner-does-not-prepare-selected-git-dependencies.md) | friction | Core |
| [Preserve distinct large BigInts in cardinality-many attributes](datahike-cljs-cardinality-many-collapses-large-bigints.md) | friction | Core |
| [Prevent output data from becoming a phantom callable arity](callable-contract-output-data-becomes-phantom-arity.md) | friction | agent |
| [Prove Kimi K3 completion and continuation compatibility](kimi-k3-continuation-compatibility.md) | friction | agent |
| [Register namespace identity before home schemas reference it](home-schema-references-namespace-name-before-registration.md) | friction | Core |
| [Remove local Datahike ownership from execution children](execution-artifact-packages-local-datahike.md) | friction | agent |
| [Remove the Node module-register deprecation from CSS builds](tailwind-node-module-register-deprecation.md) | friction | UI |
| [Remove undeclared-var warnings from the self-host bootstrap build](bootstrap-analyzer-api-emits-undeclared-var-warnings.md) | friction | agent |
| [Render entity converters silently vanish on unresolved symbols](render-entity-converters-silently-vanish-on-unresolved-symbol.md) | friction | UI |
| [Render logical Malli arities for pure-variadic functions](compact-pure-variadic-contract-mislabels-logical-arities.md) | friction | agent |
| [Restore focused agent edge-case coverage](removed-embedded-multiagent-coverage-needs-owner.md) | friction | agent |
| [Root context replaces inherited capability requirements](root-context-replaces-base-capability-requires.md) | friction | agent |
| [Root warnings block renders 146k tokens before its cap clips it](root-warnings-block-renders-146k-tokens-before-cap.md) | friction | agent |
| [Self-host `cljs.test/is` throws inside a dynamically-evaled `:test` thunk](selfhost-cljs-test-is-thunk-resolution.md) | friction | agent |
| [Shadow deps-mode declarations imply inactive build paths](shadow-deps-mode-declaration-drift.md) | friction | docs |
| [Shadow runtime stops reconnecting](shadow-runtime-stops-reconnecting.md) | friction | docs |
| [Share concurrent database session opening](database-session-concurrent-open-is-not-shared.md) | friction | Core |
| [Share exact temporal query work in Datahike](temporal-query-work-is-not-shared.md) | friction | Core |
| [Shared-HEAD amend can absorb a concurrent commit](shared-head-amend-can-absorb-concurrent-commit.md) | friction | agent |
| [Single-entity pulls budgeted as one result node](single-entity-pulls-budgeted-as-one-result-node.md) | friction | agent |
| [Thread one database value through debug and data feeds](debug-feed-captures-foreign-database-reads.md) | friction | UI |
| [Transact output schema crashed child on ordinary error](transact-output-schema-crashed-child-on-ordinary-error.md) | friction | agent |
| [Transcript decay does not bound total context](transcript-decay-does-not-bound-total-context.md) | friction | agent |
| [Turn debug treated a database error as an entity id](turn-debug-treated-database-error-as-entity-id.md) | friction | Core |
| [`my.ns/compact!` can hide the selected namespace](my-ns-compact-can-hide-namespace.md) | friction | agent |
| [try in expression position inside a compiled ^:async fn auto-awaits](async-try-expression-iife-auto-awaits.md) | friction | agent |

## Cleanup (8)

| Issue | Severity | Lane |
|-------|----------|------|
| [Address resident agents by namespace](namespace-addressed-resident-agents.md) | cleanup | agent |
| [Bun 1.3.14 segfaults on AsyncLocalStorage.enterWith in ESM top-level continuations](bun-enterwith-toplevel-segfault.md) | cleanup | Core |
| [Inspect concurrent attributed agent messages](inspect-concurrent-agent-messages.md) | cleanup | agent |
| [Move product routes out of the static router supplement](static-routes-bypass-database-route-authority.md) | cleanup | UI |
| [Partially-Stale Reference Docs Need Updates](stale-reference-docs.md) | cleanup | docs |
| [Subagents block is implemented but not installed](subagents-block-is-implemented-but-not-installed.md) | cleanup | agent |
| [Surface recency may be recomputed globally](surface-recency-recomputed.md) | cleanup | UI |
| [Unify agent and operation AsyncLocalStorage](als-unify-tx-meta.md) | cleanup | Core |
