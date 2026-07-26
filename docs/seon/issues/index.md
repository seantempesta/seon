---
type: orchestrator
status: active
tags: [orchestrator, issue, index]
---

# Open Issues — Index

GENERATED FILE — do not hand-edit. Regenerate with `bin/issues-index`.
Lifecycle `open → resolved | superseded`; closed issues live in `archive/`.
See `README.md` for the convention.

## Blocker (53)

| Issue | Severity | Lane |
|-------|----------|------|
| [A deps-ecosystem package install cannot reach an active cluster JVM](deps-package-install-cannot-reach-a-running-claimant.md) | blocker | general |
| [Arbitrary database results collide with the error shape](arbitrary-database-results-collide-with-error-shape.md) | blocker | Core |
| [Arbitrary eval allocation lacks hard process memory containment](eval-process-isolation-memory-containment.md) | blocker | agent |
| [Capture dependencies when a lazy view unit activates](lazy-view-unit-activation-drops-read-observations.md) | blocker | UI |
| [Compiled program contains nilable value schemas](compiled-program-contains-nilable-value-schemas.md) | blocker | Core |
| [Content-pin the Inspect source dependency](inspect-source-dependency-is-not-content-pinned.md) | blocker | agent |
| [Contract predicate transitive purity awaits execution planner](contract-predicate-transitive-purity-awaits-execution-planner.md) | blocker | Core |
| [Datahike force branch does not preserve secondary root](datahike-force-branch-does-not-preserve-secondary-root.md) | blocker | Core |
| [Deliver a formless cluster JVM reply through the transcript](formless-claimant-reply-is-not-delivered-to-the-transcript.md) | blocker | agent |
| [Derive read dependencies from Datahike's parsed semantics](datahike-read-dependencies-miss-valid-query-and-pull-inputs.md) | blocker | UI |
| [Fence accepted writes before retained-head preparation](restore-intent-lacks-exclusive-writer-fence.md) | blocker | Core |
| [Freeze one turn input across provider retries](turn-retries-reread-provider-inputs.md) | blocker | agent |
| [Freeze the restore pod artifact in confirmed intent](restore-intent-does-not-freeze-client-artifact.md) | blocker | Core |
| [Full writer gate fails during runtime lane integration](full-writer-gate-fails-during-runtime-lane-integration.md) | blocker | Core |
| [Give each named cluster its own writer process](named-clusters-share-one-writer-process.md) | blocker | Core |
| [Implement browser-session navigation provenance](web-session-navigation-provenance-is-missing.md) | blocker | UI |
| [Invalidate a cached failed Datastar render after its owner reloads](datastar-feed-retains-failed-render-after-hot-reload.md) | blocker | UI |
| [JVM result surface does not implement R32 result-symbol handles](jvm-result-symbols-not-bound-r32.md) | blocker | Core |
| [Keep a running target's bootstrap artifact immutable](shared-bootstrap-output-mutates-running-artifact.md) | blocker | Core |
| [Keep database control entry independent of occupied handlers](uds-codec-capacity-can-delay-control-entry.md) | blocker | Core |
| [Keep pod republication's reusable projection inside its contract](pod-republication-passes-nil-reusable-projection.md) | blocker | agent |
| [Keep restore publication closed through completion](restore-completion-cannot-precede-admission.md) | blocker | Core |
| [Let the common Inspect pod solver address an existing agent](inspect-pod-solver-cannot-address-existing-agent.md) | blocker | agent |
| [Make the downstream runtime package self-contained](downstream-runtime-package-is-not-self-contained.md) | blocker | Core |
| [Make writer drain proof consumable by the operator](planned-restart-cannot-observe-writer-drain-result.md) | blocker | Core |
| [Plan a visible cluster JVM reply on an inspected tier](jvm-claimant-rejects-visible-reply-without-exact-execution-plan.md) | blocker | agent |
| [Pod remains ready after losing its web listener](pod-remains-ready-after-web-listener-loss.md) | blocker | UI |
| [Preserve context identity in the in-pod agent view](in-pod-agent-view-omits-context-name.md) | blocker | UI |
| [Private-function presence law is incomplete outside core indexing](private-function-presence-law-incomplete.md) | blocker | general |
| [Project a turn's rendered transaction ref as its basis transaction](turn-debug-must-project-rendered-transaction-ref.md) | blocker | agent |
| [Projected map keys are not drill paths](projected-map-keys-are-not-drill-paths.md) | blocker | UI |
| [Pull cluster JVM limits from the cluster config identity](jvm-claimant-pulls-config-with-wrong-identity.md) | blocker | agent |
| [Read-side attribute admission fails open (silent empty results, :all fallback)](read-side-attribute-admission-fails-open.md) | blocker | Core |
| [Reduce retained memory in each execution child](execution-children-retain-hundreds-of-megabytes.md) | blocker | agent |
| [Refuse execution when the run plan is not durable](jvm-driver-ignores-plan-transaction-errors.md) | blocker | agent |
| [Restore writer admin transition is unimplemented](restore-writer-admin-transition-is-unimplemented.md) | blocker | Core |
| [Retain complete model transport evidence in Inspect logs](inspect-model-transport-evidence-is-incomplete.md) | blocker | agent |
| [Retain live eval values in the owning JVM host](retain-live-eval-values-in-the-owning-jvm-host.md) | blocker | agent |
| [Retained per-agent SCI contexts are never evicted, and each shares one guard holder](retained-agent-contexts-are-never-evicted-and-share-one-holder.md) | blocker | general |
| [Score reachability from real context transitions](inspect-reachability-assumes-nonexistent-evidence.md) | blocker | agent |
| [Separate restore intent from completion identity](restore-completion-reuses-operator-intent-identity.md) | blocker | Core |
| [Sequence calls within each agent execution child](rendering-and-turns-collided-in-one-execution-child.md) | blocker | UI |
| [Share one driver for an open agent run](wake-and-replay-can-drive-the-same-open-run.md) | blocker | agent |
| [Skip the receipt reread branch after a successful eval write](successful-eval-receipt-called-state-on-nil.md) | blocker | agent |
| [Supply the execution artifact to the production container launch](container-launch-omits-execution-artifact.md) | blocker | agent |
| [The agent toolkit teaches a `:seon.db/ok?` contract `transact!` does not produce](toolkit-teaches-a-db-ok-contract-transact-does-not-produce.md) | blocker | agent |
| [The host base does not resolve the agent-facing surface (q34, W5-0 gate)](host-base-agent-surface-parity.md) | blocker | agent |
| [Toolkit current-ns wedges the agent since the cljc packaging window](toolkit-current-ns-wedges-agent-after-cljc-packaging.md) | blocker | agent |
| [Value drill has no total work bounds](value-drill-has-no-total-work-bounds.md) | blocker | UI |
| [Value-drill result literals failed boot schema admission](value-drill-result-literals-failed-boot-schema-admission.md) | blocker | Core |
| [Writer run-readiness! busy-spins when a ready source has no runtime](writer-run-readiness-busy-spins-without-runtime.md) | blocker | Core |
| [seon.agent.ctx file reads bypass the filesystem grant](agent-ctx-file-reads-bypass-fs-grant.md) | blocker | agent |
| [tools.reader executes agent source at read time, outside SCI entirely](tools-reader-evaluates-agent-source-at-read-time.md) | blocker | general |

## Friction (76)

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
| [Autocomplete datasets and scoring bypass canonical runtime projections](autocomplete-data-quality-pipeline-drift.md) | friction | agent |
| [Autocomplete worktrees contain unclassified database and model evidence](autocomplete-worktree-evidence-preservation.md) | friction | Core |
| [Bespoke reactive loops duplicate seon.reactive outside its owner](bespoke-reactive-loops-outside-seon-reactive.md) | friction | UI |
| [Bound temporal index-page work](bound-temporal-index-page-work.md) | friction | Core |
| [Branch trial tests write into the live operator state directory](branch-trial-tests-write-into-live-operator-state.md) | friction | Core |
| [Bun's rejection net loses AsyncLocalStorage scope](bun-rejection-net-loses-async-scope.md) | friction | general |
| [Calibrate cross-database writer scaling on shared file persistence](shared-file-persistence-limits-cross-database-writer-scaling.md) | friction | Core |
| [Coalesce duplicate run-open attempts in the JVM driver](agent-driver-scans-duplicate-run-open-attempts.md) | friction | agent |
| [Context block order is static](context-block-order-is-static.md) | friction | agent |
| [Cross-agent planners can reopen worker-completed steps](plan-reopen-cross-agent-authority.md) | friction | agent |
| [D13 repair merge broke the bare-babashka loadability the candidates half had](d13-merge-broke-bare-babashka-loading.md) | friction | agent |
| [Database query tuple results are hard for agents to read](database-query-tuple-shape-legibility.md) | friction | agent |
| [Datahike execute-many predicate query fails](datahike-execute-many-predicate-query-fails.md) | friction | Core |
| [Datahike queue-pressure warnings obscure the load failure](datahike-queue-pressure-warning-storm-obscures-load-failure.md) | friction | Core |
| [Dev-eval fault scope misses MCP funnels; a REPL typo crashes the pod](dev-eval-fault-scope-misses-mcp-funnels.md) | friction | general |
| [Edit-hook feedback can target a different checkout](worktree-edit-hook-checkout-drift.md) | friction | general |
| [Execution child program load omitted instrumentation](execution-child-program-load-omitted-instrumentation.md) | friction | agent |
| [Execution process proof seeds incomplete schema population](execution-process-proof-seeds-incomplete-schema-population.md) | friction | agent |
| [Execution-child native heap mislabeled as GPU dominates footprint](execution-child-gpu-allocation-dominates-footprint.md) | friction | agent |
| [Fresh boot takes 271s, re-deriving state the build already computed](fresh-boot-271s-rederives-build-computed-state.md) | friction | general |
| [Give Inspect live callers an ownership-fenced cluster lease](inspect-live-cluster-caller-drift.md) | friction | agent |
| [Give root a dedicated system layout](root-page-is-an-ordinary-agent-layout.md) | friction | UI |
| [Include new CLJS namespaces in changed-test runtime artifacts](changed-test-new-cljs-namespace-misses-runtime-file.md) | friction | Core |
| [Inspect product snapshot assumes nonexistent evidence](inspect-product-snapshot-assumes-nonexistent-evidence.md) | friction | agent |
| [Installed schema map misclassified as database error](installed-schema-map-misclassified-as-database-error.md) | friction | agent |
| [LoRA audit runner depends on a retired Shadow target and pinned checkout](lora-audit-runner-drift.md) | friction | general |
| [Make UDS frame accumulation linear](uds-fragment-accumulation-recopies-complete-prefix.md) | friction | Core |
| [Make dependency preparation deterministic under concurrent development](dependency-preparation-can-crash-inside-clojure-hashmap.md) | friction | Core |
| [Make multi-form eval order a durable database fact](multi-form-eval-order-is-not-durable.md) | friction | agent |
| [Make program indexing independent of the active schema projection](program-indexer-drops-valid-specs-outside-active-schema-projection.md) | friction | agent |
| [Make transaction retry policy available during first config reconcile](transaction-retry-policy-cannot-be-database-owned-during-first-config-reconcile.md) | friction | Core |
| [Operator trial processes leak across days](operator-trial-processes-leak-across-days.md) | friction | docs |
| [Package placement is a namespace-prefix hand list](package-placement-is-a-namespace-prefix-hand-list.md) | friction | general |
| [Persisted program error prevents agent repair](persisted-program-error-prevents-agent-repair.md) | friction | agent |
| [Pin the backward-compatibility writer below the maintained Konserve version](datahike-backward-compat-gate-writes-newer-konserve.md) | friction | Core |
| [Plan completion has no checkable verification evidence](plan-completion-verification-evidence.md) | friction | agent |
| [Plan reconcile scope can delete unseen work](plan-reconcile-scope-can-delete-unseen-work.md) | friction | agent |
| [Planner home-ns step blocks the root on a self-recipient refusal](planner-home-ns-step-blocks-on-self-recipient.md) | friction | agent |
| [Planner lacks a per-root purity projection](planner-lacks-per-root-purity-projection.md) | friction | agent |
| [Planner self-done bypasses generated terminal delivery](planner-self-done-bypasses-generated-terminal-delivery.md) | friction | agent |
| [Prepare selected git dependencies before test compilation](test-runner-does-not-prepare-selected-git-dependencies.md) | friction | Core |
| [Preserve distinct large BigInts in cardinality-many attributes](datahike-cljs-cardinality-many-collapses-large-bigints.md) | friction | Core |
| [Prevent output data from becoming a phantom callable arity](callable-contract-output-data-becomes-phantom-arity.md) | friction | agent |
| [Prove Kimi K3 completion and continuation compatibility](kimi-k3-continuation-compatibility.md) | friction | agent |
| [Reconcile issue frontmatter with the maintained lifecycle](issue-authority-frontmatter-drift-blocks-index.md) | friction | general |
| [Remove local Datahike ownership from execution children](execution-artifact-packages-local-datahike.md) | friction | agent |
| [Remove the Node module-register deprecation from CSS builds](tailwind-node-module-register-deprecation.md) | friction | UI |
| [Remove undeclared-var warnings from the self-host bootstrap build](bootstrap-analyzer-api-emits-undeclared-var-warnings.md) | friction | agent |
| [Render entity converters silently vanish on unresolved symbols](render-entity-converters-silently-vanish-on-unresolved-symbol.md) | friction | UI |
| [Restore focused agent edge-case coverage](removed-embedded-multiagent-coverage-needs-owner.md) | friction | agent |
| [Root context replaces inherited capability requirements](root-context-replaces-base-capability-requires.md) | friction | agent |
| [Root warnings block renders 146k tokens before its cap clips it](root-warnings-block-renders-146k-tokens-before-cap.md) | friction | agent |
| [Select entity-scoped feed interests in the writer](attribute-only-feed-interest-recomputes-unrelated-agent-views.md) | friction | UI |
| [Self-host `cljs.test/is` throws inside a dynamically-evaled `:test` thunk](selfhost-cljs-test-is-thunk-resolution.md) | friction | agent |
| [Seon database store ID needs a named predicate schema](seon-db-store-id-needs-named-predicate-schema.md) | friction | Core |
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
| [Unbounded runtime acquisitions exceed the negotiated frame](unbounded-runtime-acquisitions-exceed-frame.md) | friction | agent |
| [test-cljs compile failure retains a live lock owner](test-cljs-compile-failure-retains-live-lock-owner.md) | friction | general |
| [try in expression position inside a compiled ^:async fn auto-awaits](async-try-expression-iife-auto-awaits.md) | friction | agent |

## Cleanup (9)

| Issue | Severity | Lane |
|-------|----------|------|
| [Address resident agents by namespace](namespace-addressed-resident-agents.md) | cleanup | agent |
| [Bun 1.3.14 segfaults on AsyncLocalStorage.enterWith in ESM top-level continuations](bun-enterwith-toplevel-segfault.md) | cleanup | Core |
| [Inspect concurrent attributed agent messages](inspect-concurrent-agent-messages.md) | cleanup | agent |
| [Move product routes out of the static router supplement](static-routes-bypass-database-route-authority.md) | cleanup | UI |
| [Partially-Stale Reference Docs Need Updates](stale-reference-docs.md) | cleanup | docs |
| [Remove the remaining child vocabulary and tier dial](u9-surviving-child-vocabulary-and-tier-dial.md) | cleanup | agent |
| [Subagents block is implemented but not installed](subagents-block-is-implemented-but-not-installed.md) | cleanup | agent |
| [Surface recency may be recomputed globally](surface-recency-recomputed.md) | cleanup | UI |
| [Unify agent and operation AsyncLocalStorage](als-unify-tx-meta.md) | cleanup | Core |
