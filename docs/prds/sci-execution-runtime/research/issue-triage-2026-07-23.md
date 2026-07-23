---
type: research
status: complete
tags: [research, issue, agent]
---

# Open issue triage — 2026-07-23

## Scope and method

This audit classified every actual open issue note present at the start of the
sweep against current source and the reconciled execution-runtime spine. The
directory contained 135 top-level Markdown files: 132 open issues, the
generated `index.md`, and the two authorities `AGENTS.md` and `README.md`.
The roadmap's “133 open” shorthand counted `index.md`; this table does not
misclassify that generated projection as an issue.

No source was changed and no tests or live probes were run. FIXED requires
current source or recorded commit/live evidence at the issue's acceptance
boundary. DISSOLVES names the planned deletion/replacement or acceptance unit
and remains open. REAL+INDEPENDENT carries an S/M/L estimate and owner.
UNCLEAR names the exact missing probe.

## Counts

| Class | Count |
|---|---:|
| FIXED | 20 |
| DISSOLVES | 28 |
| REAL+INDEPENDENT | 73 |
| UNCLEAR | 11 |
| **Total actual issues** | **132** |

## Top 10 REAL+INDEPENDENT queue candidates

Ordered by direct blast radius on the P4 resumable-loop spine, restart
correctness, and the U12 demo scenario:

1. `ai-context-is-not-pure-over-database-value.md` (L) — restart-safe run
   advancement cannot be byte-stable while context can vary for one database
   value; owner: `seon.agent.ctx/render-context`.
2. `datahike-read-dependencies-miss-valid-query-and-pull-inputs.md` (L) —
   false-negative dependencies can suppress the reactive wakeups the migrated
   loop and demo UI rely on; owner: Datahike dependency projection.
3. `bespoke-reactive-loops-outside-seon-reactive.md` (L) — a second
   projection/listen loop undermines the one CAS-claimable advancement
   mechanism; owner: `seon.reactive`.
4. `plan-reconcile-scope-can-delete-unseen-work.md` (L) — demo planning can
   delete work outside the reader's visible scope; owner: `my.plan`
   reconcile/document boundary.
5. `planned-restart-cannot-observe-writer-drain-result.md` (L) — restart
   cannot distinguish a completed drain from an abandoned generation; owner:
   managed-process terminal-result transport.
6. `host-session-errors-vanish-silently.md` (M) — the surviving JVM SCI host
   can lose session failures without a durable loop-visible transition; owner:
   `seon.host` session lifecycle.
7. `uds-codec-capacity-can-delay-control-entry.md` (L) — shared codec
   saturation can delay control/recovery exactly during the U12 interruption
   path; owner: UDS admission.
8. `arbitrary-database-results-collide-with-error-shape.md` (M) — ambiguous
   flat result/error discrimination sits on the portable database seam used by
   every migrated step; owner: public `seon.db` result boundary.
9. `agent-turns-lack-database-read-cost-attribution.md` (L) — the migrated
   loop cannot explain database-driven stalls in the 100-agent demo; owner:
   turn observability and read-evidence projection.
10. `datastar-feed-retains-failed-render-after-hot-reload.md` (M) — the pod's
    retained web responsibility can keep serving a stale failure after the
    underlying migrated state recovers; owner: Datastar subscription/cache
    transition.

## Full classification

| Issue | Class | Evidence | Disposition |
|---|---|---|---|
| `acme-operator-migration-drift.md` | REAL+INDEPENDENT | Live proof in note shows semantic flavor works, but stale retired-process records still require a prior `bin/acme down`. | Queue S; ACME operator adapter. |
| `acme-typeahead-worker-unavailable.md` | UNCLEAR | Worker availability is external state not established by current source. | Probe configured endpoint readiness while recording worker/model identity. |
| `agent-turns-lack-database-read-cost-attribution.md` | REAL+INDEPENDENT | `src/seon/db.cljc:543-555` exposes query evidence but no turn-level duration/resource aggregation. | Queue L; turn observability + database read-evidence projection. |
| `agents-run-settlement-used-wrong-reactive-database-key.md` | FIXED | `baa21cee`; `src/seon/web/serve.cljs:842-848` passes `:seon.reactive/db`. | Archived resolved. |
| `ai-context-is-not-pure-over-database-value.md` | REAL+INDEPENDENT | P4 does not provide byte-identical database-derived context across delay/restart. | Queue L; `seon.agent.ctx/render-context`. |
| `als-unify-tx-meta.md` | REAL+INDEPENDENT | `src/seon/db/fiber.cljs:8-18,50-64` still has separate transaction, agent, and evidence ALS scopes. | Queue M; `seon.db.fiber`. |
| `arbitrary-database-results-collide-with-error-shape.md` | REAL+INDEPENDENT | `src/seon/db.cljc:522-541,557-591` returns arbitrary successes bare alongside flat error maps. | Queue M; public `seon.db` result boundary. |
| `async-try-expression-iife-auto-awaits.md` | DISSOLVES | Owner is `src/seon/eval.cljs`, which is deleted rather than converted. | Cutover unit: per-agent child + `eval.cljs` self-host deletion. |
| `atomic-client-authority-cut-in-progress.md` | FIXED | `src/seon/db.cljc:282-301` and `src/seon/db/session.cljs:415-443` show the completed direct-session authority. | Archived superseded transient cut-state note. |
| `autocomplete-data-quality-pipeline-drift.md` | REAL+INDEPENDENT | Dataset/split/serving-projection/Inspect evidence contracts are outside P4 and cutover. | Queue L; autocomplete artifact/evaluation pipeline. |
| `autocomplete-worktree-evidence-preservation.md` | REAL+INDEPENDENT | Retained databases/exports and authority drift are independent operational research assets. | Queue M; autocomplete evidence/worktree disposition. |
| `bespoke-reactive-loops-outside-seon-reactive.md` | REAL+INDEPENDENT | `src/seon/web/router.cljs:344-416` retains a separate projection/listen refresh loop after P4. | Queue L; `seon.reactive`. |
| `bootstrap-analyzer-api-emits-undeclared-var-warnings.md` | DISSOLVES | Warning belongs to the self-host bootstrap/analyzer artifact. | Cutover unit: per-agent child + `eval.cljs` self-host deletion. |
| `bound-temporal-index-page-work.md` | REAL+INDEPENDENT | `src/seon/db.cljc:715-747` retains index paging; P4 does not alter Datahike temporal restoration cost. | Queue L; maintained Datahike temporal index paging. |
| `branch-trial-tests-write-into-live-operator-state.md` | REAL+INDEPENDENT | Trial-private process state is independent of loop and child topology. | Queue S; branch-trial harness/process-directory selection. |
| `bun-enterwith-toplevel-segfault.md` | UNCLEAR | `src/seon/db/fiber.cljs:55-59` still contains `enterWith`, contrary to anticipated deletion. | Run minimal ESM continuation on pinned Bun and trace caller reachability. |
| `bun-rejection-net-loses-async-scope.md` | REAL+INDEPENDENT | Pod retains web/LLM/scheduler duties, so detached rejection policy survives P4. | Queue M; `seon.error` + client process safety net. |
| `callable-contract-output-data-becomes-phantom-arity.md` | REAL+INDEPENDENT | Projection remains at `src/seon/agent/ctx/namespaces.cljs:1166-1234` and is separate from pure-variadic repair. | Queue S; callable-contract projection. |
| `canvas-controls-hide-pending-and-failure.md` | FIXED | `9778fa86`; `src/seon/web/reactive/transform.cljs:146-160` binds pending disabled/aria-busy state. | Archived resolved. |
| `canvas-state-returned-a-promise-as-render-data.md` | FIXED | `src/my/canvas.cljc:178-195` declares async state and awaits ordinary map data. | Archived resolved. |
| `changed-test-hooks-queue-stale-runs-behind-active-owner.md` | FIXED | `8d938d56` closed runner lifecycle/admission gaps with regression. | Archived resolved. |
| `changed-test-new-cljs-namespace-misses-runtime-file.md` | REAL+INDEPENDENT | Changed-test artifact remains a development gate after P4. | Queue M; immutable CLJS test artifact/fingerprint. |
| `clean-or-force-evidence-can-cross-or-falsely-report-absence.md` | FIXED | Bounded quiescence/containment series ending `3e3b7907`; reconciled plan says WP-S2 landed. | Archived resolved. |
| `compact-pure-variadic-contract-mislabels-logical-arities.md` | FIXED | `src/seon/agent/ctx/namespaces.cljs:1166-1184` lets logical Malli schemas own labels. | Archived resolved. |
| `compiled-program-contains-nilable-value-schemas.md` | REAL+INDEPENDENT | `src/seon/agent/home.cljs:76-78,138-140` still contains nilable value/input schemas. | Queue S; schema registration/compiled-program admission. |
| `config-apply-rebuilds-unchanged-runtime.md` | FIXED | Live proof in `7b1d7cde`; `script/seon/dev/cli.clj:450-475` uses live config operation. | Archived resolved. |
| `configured-turn-limit-masks-mode-specific-budget.md` | FIXED | `5cfc0127`; `src/seon/agent/ctx/transcript.cljs:640-653` derives active/mode policy cap. | Archived resolved. |
| `container-launch-omits-execution-artifact.md` | DISSOLVES | Acceptance publishes/launches the execution-child artifact which cutover deletes. | Cutover unit: per-agent child + `eval.cljs` self-host deletion. |
| `context-block-order-is-static.md` | REAL+INDEPENDENT | P4 does not add measured stability, epoch/hysteresis, or cache-band ordering. | Queue M; context ordering/observation projection. |
| `core-selected-render-errors-bypass-crash-policy.md` | FIXED | Note records focused proof and rebuilt live feed with repaired diagnostic/retraction. | Archived resolved. |
| `d13-merge-broke-bare-babashka-loading.md` | UNCLEAR | Repository `bb.edn` path is green; deployed bare-source support is undecided. | Owner ruling plus run outside repository `bb.edn` if supported. |
| `database-program-query-results-can-be-sets.md` | FIXED | `46fcd779`; `src/seon/execution.cljs:270-283` normalizes set/sequential collections. | Archived resolved. |
| `database-protocol-coordinate-is-incomplete.md` | FIXED | `src/seon/db/protocol.cljc:232-238` carries database name, basis, branch data, commit ID; note has live proof. | Archived resolved. |
| `database-query-tuple-shape-legibility.md` | REAL+INDEPENDENT | `src/seon/db.cljc:528-545` still gives query an `:any` result schema. | Queue S; query schemas/examples/value rendering. |
| `database-session-concurrent-open-is-not-shared.md` | REAL+INDEPENDENT | `src/seon/db/session.cljs:415-441` shares same initialization but remaining conflict/failure/close cases persist. | Queue M; `seon.db.session`. |
| `datahike-cljs-cardinality-many-collapses-large-bigints.md` | REAL+INDEPENDENT | P4 retains Datahike semantics and does not replace BigInt equality/hash/indexing. | Queue M; maintained Datahike CLJS transaction/index normalization. |
| `datahike-execute-many-predicate-query-fails.md` | REAL+INDEPENDENT | `src/seon/db.cljc:657-713` retains execute-many; predicate failure is topology-independent. | Queue M; maintained Datahike compiled query. |
| `datahike-force-branch-does-not-preserve-secondary-root.md` | REAL+INDEPENDENT | P4 does not include secondary-root preservation/lost-response convergence. | Queue L; Datahike/Proximum forced-branch publication. |
| `datahike-http-remote-connection-identity-mismatch.md` | REAL+INDEPENDENT | Seon does not use this transport; maintained dependency mismatch survives P4. | Queue M; Datahike HTTP identity boundary. |
| `datahike-read-dependencies-miss-valid-query-and-pull-inputs.md` | REAL+INDEPENDENT | `src/seon/reactive.cljs:94-126` consumes dependency evidence vulnerable to false negatives. | Queue L; Datahike parsed dependency projection. |
| `datastar-feed-retains-failed-render-after-hot-reload.md` | REAL+INDEPENDENT | Web UI remains pod-owned; `src/seon/web/datastar.cljs:393-416` retains failed render state. | Queue M; Datastar subscription/cache transition. |
| `dead-process-group-leader-blocks-safe-subtree-drain.md` | FIXED | Containment series + `5bd19361`; note records normal and abnormal live drain proofs and WP-S2 is landed. | Archived resolved. |
| `debug-feed-captures-foreign-database-reads.md` | DISSOLVES | Remaining defect is child prompt read-evidence loss through the pod-loop render boundary. | P4 loop-migration slice + cutover child/`eval.cljs` deletion. |
| `dependency-preparation-can-crash-inside-clojure-hashmap.md` | UNCLEAR | `script/seon/dev/artifact.clj:781-849` now locks prep, but intermittent cause was unproved. | Race operator/focused prep repeatedly with retained CLI logs. |
| `dev-eval-fault-scope-misses-mcp-funnels.md` | UNCLEAR | 6c9bfe83 added in-fiber settlement, but detached MCP/Shadow acceptance lacks current live proof | Probe sync throw + detached rejection under :crash; both :agent and pod ready |
| `downstream-runtime-package-is-not-self-contained.md` | DISSOLVES | Current package evidence includes execution child/self-host artifacts deleted at cutover | Post-P4 cutover/package descriptor unit |
| `ensure-database-creates-fresh-store-at-any-path.md` | REAL+INDEPENDENT | src/seon/db/registry.clj:615 and db/protocol.cljc:1445 retain ensure/open path semantics | M; seon.db.writer ensure/open |
| `eval-process-isolation-memory-containment.md` | DISSOLVES | Current arbitrary-eval topology is replaced by resumable claimed runs and child/self-host deletion | P4 loop migration + cutover U12 |
| `execution-artifact-packages-local-datahike.md` | DISSOLVES | Defect is entirely the per-agent execution artifact deleted at cutover | Post-P4 child deletion |
| `execution-child-gpu-allocation-dominates-footprint.md` | DISSOLVES | Measured IOAccelerator cost belongs only to per-agent child startup | Post-P4 child deletion |
| `execution-child-program-load-omitted-instrumentation.md` | DISSOLVES | bef42a75 repaired observed generation; remaining parity concerns outgoing child/self-host loader | Post-P4 child/eval.cljs deletion |
| `execution-children-retain-hundreds-of-megabytes.md` | DISSOLVES | Measured private compiler heaps belong only to per-agent children | Post-P4 child deletion |
| `execution-config-pull-had-one-node-budget.md` | FIXED | src/seon/execution.cljs:430-436 uses configuration-read-profile max-results; commit 46fcd779 | Archive resolved |
| `execution-process-proof-seeds-incomplete-schema-population.md` | DISSOLVES | Proof scaffolding targets outgoing execution-child process topology | Post-P4 child deletion |
| `execution-result-diagnostic-retained-invalid-map-key.md` | FIXED | src/seon/execution.cljs:263 reports invalid keys as literal :map-key; commit bef42a75 | Archive resolved |
| `generated-root-has-no-planner-retry-path.md` | DISSOLVES | Generated-root wake/retry is pod-loop scheduler state replaced by CAS-claimable resumable run steps | P4 loop migration no-wedge/U12 |
| `home-schema-references-namespace-name-before-registration.md` | REAL+INDEPENDENT | home.cljs:67-69 references :seon.ns/name while registration remains render_fns.cljs:47 | S; namespace identity schema registration |
| `host-base-agent-surface-parity.md` | DISSOLVES | Resequenced plan explicitly makes census-to-zero a post-P4 cutover gate | Post-P4 census/cutover |
| `host-session-errors-vanish-silently.md` | REAL+INDEPENDENT | JVM SCI host persists and src/seon/host.clj still owns serve-session!/accept-startup! | M; seon.host session error lifecycle |
| `inspect-concurrent-agent-messages.md` | REAL+INDEPENDENT | Requested native task remains absent under src-inspect-ai/src/seon_inspect/tasks | M; Inspect task/scorer |
| `inspect-live-cluster-caller-drift.md` | REAL+INDEPENDENT | Current note proves fail-closed callers but operator still has no ownership-fenced per-sample lease | L; operator lease + Inspect cluster.py |
| `inspect-model-transport-evidence-is-incomplete.md` | REAL+INDEPENDENT | 33fcc17b/defe85a2 settle remote evidence; local model-server artifact identity remains | M; Inspect transport/model artifact evidence |
| `inspect-pod-solver-cannot-address-existing-agent.md` | UNCLEAR | 8efd3366 and solver.py implement optional agent_id; only source-stable live completion missing | Probe finalized root row with pod_agent_id root and equal target identities |
| `inspect-product-snapshot-assumes-nonexistent-evidence.md` | REAL+INDEPENDENT | Issue concerns Inspect scorer vs /agents/run projection, not run topology | M; Inspect product snapshot projection/scorer |
| `inspect-reachability-assumes-nonexistent-evidence.md` | REAL+INDEPENDENT | Scorer still requires production context-transition evidence independent of run driver | M; Inspect reachability scorer/projection |
| `inspect-source-dependency-is-not-content-pinned.md` | REAL+INDEPENDENT | Committed-tree check remains vulnerable to uncommitted ignore-policy hiding source | M; Inspect source admission fingerprint |
| `installed-schema-map-misclassified-as-database-error.md` | REAL+INDEPENDENT | Flat error seam does not by itself prevent a successful schema map matching the error predicate | S; seon.db result/error classifier |
| `kimi-k3-continuation-compatibility.md` | UNCLEAR | Compatibility is provider-dependent and current schemas cannot substitute for a paid/live continuation | Probe retained Kimi tool-call continuation through completion |
| `lazy-view-unit-activation-drops-read-observations.md` | REAL+INDEPENDENT | Web UI remains in pod; activation observation/rebind lifecycle remains current | M; seon.web.view-unit |
| `lora-audit-runner-drift.md` | REAL+INDEPENDENT | shadow-cljs.edn retains :lora-audit and archived runbook retains /Users/sean/src/seon-pin | M; src-inspect-ai data-quality pipeline |
| `multi-form-eval-order-is-not-durable.md` | DISSOLVES | Outgoing eval-batch order must become durable resumable run-step/attempt order | P4 loop migration + eval.cljs deletion |
| `multi-source-query-cache-retains-foreign-database-values.md` | REAL+INDEPENDENT | Datahike 0070d507 landed core mechanism but note still lacks four-source/full checkpoint/perf proof | L; maintained Datahike query cache |
| `my-ns-compact-can-hide-namespace.md` | FIXED | src/my/ns.cljs:185-199 records ::compact and ctx/namespaces.cljs:228,491 consumes it; current compact! docs exact behavior | Archive resolved |
| `namespace-addressed-resident-agents.md` | DISSOLVES | Namespace resident dispatch is generated-DAG/pod-loop scheduling replaced by claimed run-state | P4 loop migration |
| `nested-authored-render-hides-child-reload.md` | REAL+INDEPENDENT | Authored render dependency/reload remains in web UI after pod demotion | M; authored render dependency projection |
| `operator-trial-processes-leak-across-days.md` | REAL+INDEPENDENT | Operator process ownership is independent of database run-state migration | M; bin/seon/seon.dev.process trial lifecycle |
| `persisted-program-error-prevents-agent-repair.md` | DISSOLVES | Defect is in eval.cljs self-host acquisition/eval; loader-door SCI path replaces it | Post-P4 eval.cljs deletion |
| `plan-address-step-priority.md` | REAL+INDEPENDENT | src/my/plan/internal.cljc remains portable owner; message-step ordering is not P4 acceptance | S; my.plan.internal queue position |
| `plan-allocation-builder-set-database-value.md` | FIXED | src/my/plan.cljc:882-900 passes db only outside transaction-builder; builder returns expected transaction data | Archive resolved |
| `plan-completion-verification-evidence.md` | REAL+INDEPENDENT | src/my/plan.cljc:1393-1416 still stamps completion without verifier/evidence receipt | L; my.plan transition + verification schema |
| `plan-reconcile-scope-can-delete-unseen-work.md` | REAL+INDEPENDENT | Portable my.plan reconcile still needs database-value and exact-owned-root deletion fence | L; my.plan document/reconcile |
| `plan-reopen-cross-agent-authority.md` | DISSOLVES | Issue names parent-owned unforgeable task capability supplied by claimed run-state advancement | P4 loop migration actor/claim acceptance |
| `planned-restart-cannot-observe-writer-drain-result.md` | REAL+INDEPENDENT | Writer hook result still is not a generation-matched operator terminal result | L; managed-process terminal-result transport |
| `planner-home-ns-step-blocks-on-self-recipient.md` | DISSOLVES | compile-namespace-dag dispatch belongs to generated-root pod-loop driver | P4 loop migration |
| `planner-self-done-bypasses-generated-terminal-delivery.md` | DISSOLVES | Generated-terminal ownership/delivery is pod-loop scheduler state replaced by claimed run steps | P4 terminal transition acceptance |
| `pod-remains-ready-after-web-listener-loss.md` | UNCLEAR | Observed loss did not reproduce at b6961bac; pod remains web owner after P4 | Fault listener after initial 200; require readiness withdrawal/termination |
| `preflight-repair-consumed-referred-macros.md` | FIXED | src/seon/eval.cljs:4084-4120 macro-invocation? excludes macros; commit eadf9671 | Archive resolved (self-host also deleted at cutover) |
| `program-indexer-drops-valid-specs-outside-active-schema-projection.md` | REAL+INDEPENDENT | src/seon/client.cljs:1428-1461 still m/schema-validates against current default registry and omits on failure | M; client var->fn-row/schema projection |
| `projected-map-keys-are-not-drill-paths.md` | DISSOLVES | src/seon/render/value.cljc:557-570,742-743 closes sampler half; route/UI proof remains | U10 value-drill graduation after cutover |
| `removed-embedded-multiagent-coverage-needs-owner.md` | REAL+INDEPENDENT | Removed embedded coverage is still absent; no reconciled spine unit restores behavioral edge coverage | M seon.agent test ownership |
| `render-entity-converters-silently-vanish-on-unresolved-symbol.md` | REAL+INDEPENDENT | src/seon/render.cljs:971-980 handles one missing-render case, but issue requires the cross-slot unresolved-symbol family | M seon.render resolution |
| `render-twin-runs-function-twice.md` | REAL+INDEPENDENT | Current render architecture still invokes AI and HTML twins independently; no P4 acceptance deduplicates derivation | M seon.render twin evaluation |
| `rendering-and-turns-collided-in-one-execution-child.md` | DISSOLVES | The collision is in the per-agent execution child | cutover deletion of per-agent children and eval.cljs self-host |
| `restore-completion-cannot-precede-admission.md` | REAL+INDEPENDENT | src/seon/client.cljs and src/seon/runtime/admission.cljs remain active; P4 does not cover restore publication ordering | L restore/admission protocol |
| `restore-completion-reuses-operator-intent-identity.md` | REAL+INDEPENDENT | Restore intent/completion identity remains a separate operator protocol concern | M restore protocol identity |
| `restore-intent-does-not-freeze-client-artifact.md` | REAL+INDEPENDENT | src/seon/dev/restore.clj remains active and P4 does not freeze restore artifacts | M restore intent |
| `restore-intent-lacks-exclusive-writer-fence.md` | REAL+INDEPENDENT | src/seon/db/transport/uds.clj writer admission remains independent of loop migration | L restore/writer fencing |
| `restore-writer-admin-transition-is-unimplemented.md` | REAL+INDEPENDENT | No reconciled spine acceptance implements writer restore administration | L writer admin transition |
| `retain-live-eval-values-in-the-owning-jvm-host.md` | REAL+INDEPENDENT | src/seon/host.clj remains the SCI host after child deletion; result locality is not a child-only defect | M host live-value retention |
| `root-context-replaces-base-capability-requires.md` | UNCLEAR | Source annotations claim additive merge landed, but the issue explicitly lacks frozen ACME persisted-edge/prompt proof | Probe a coordinated ACME rebuild, persisted require edges, prompt, and converged reapply |
| `root-page-is-an-ordinary-agent-layout.md` | REAL+INDEPENDENT | src/seon/web/datastar.cljs:1011-1022 still owns a dedicated root response but the issue's system-layout acceptance is outside P4 | M root web UI layout |
| `root-warnings-block-renders-146k-tokens-before-cap.md` | REAL+INDEPENDENT | src/seon/agent/ctx/warnings.cljs still performs its own acquisition/render; P4 does not bound pre-cap work | M warnings derivation |
| `selfhost-cljs-test-is-thunk-resolution.md` | DISSOLVES | Defect is explicitly in src/seon/eval.cljs self-host cljs.test thunk resolution | cutover deletion of eval.cljs self-host |
| `shadow-deps-mode-declaration-drift.md` | REAL+INDEPENDENT | Build declaration/tooling remains outside loop migration | S Shadow build configuration |
| `shadow-runtime-stops-reconnecting.md` | REAL+INDEPENDENT | Shadow reconnect behavior is build feedback infrastructure, not covered by P4 | M Shadow runtime |
| `shared-bootstrap-output-mutates-running-artifact.md` | REAL+INDEPENDENT | Artifact immutability remains an operator/build concern after cutover | L artifact publication |
| `shared-head-amend-can-absorb-concurrent-commit.md` | REAL+INDEPENDENT | Git shared-index/head race is independent of runtime topology | S developer Git workflow |
| `single-entity-pulls-budgeted-as-one-result-node.md` | REAL+INDEPENDENT | Database weighted-result accounting remains active in the portable db seam | M seon.db query budgeting |
| `stale-reference-docs.md` | REAL+INDEPENDENT | The named stale reference corpus is not changed by P4 | S documentation ownership |
| `static-routes-bypass-database-route-authority.md` | REAL+INDEPENDENT | src/seon/route.cljs:24 and src/seon/web/router.cljs retain a static supplement | M route authority |
| `subagents-block-is-implemented-but-not-installed.md` | REAL+INDEPENDENT | config/system.edn:480 installs only orphaned-agents for root; the ordinary direct-child block remains absent | S context manifest |
| `successful-eval-receipt-called-state-on-nil.md` | DISSOLVES | The failing owner is the eval.cljs/self-host record-eval path | cutover deletion of eval.cljs self-host |
| `surface-recency-recomputed.md` | REAL+INDEPENDENT | Reactive surface recency remains a web/render derivation concern outside P4 | M surface/reactive scheduling |
| `tailwind-node-module-register-deprecation.md` | REAL+INDEPENDENT | package.json:11-12 and script/seon/dev/artifact.clj:822 still invoke Tailwind tooling | S CSS build tooling |
| `temporal-query-work-is-not-shared.md` | REAL+INDEPENDENT | Temporal Datahike query sharing is below the portable database seam and not a loop acceptance | L Datahike query engine |
| `test-runner-does-not-prepare-selected-git-dependencies.md` | REAL+INDEPENDENT | Test dependency preparation is independent developer tooling | M test runner dependency setup |
| `toolkit-current-ns-wedges-agent-after-cljc-packaging.md` | DISSOLVES | The wedge is in self-host namespace/eval state that eval.cljs deletion removes | cutover deletion of eval.cljs self-host |
| `transact-output-schema-crashed-child-on-ordinary-error.md` | UNCLEAR | src/seon/db.cljc now has the flat error union, but issue requires an instrumented writer refusal followed by success and same-runtime survival | Probe one instrumented refused transact then corrected transact and complete |
| `transcript-decay-does-not-bound-total-context.md` | REAL+INDEPENDENT | The reconciled plan's measured prompt plateau rejects a leak but explicitly leaves a ~34k ambient floor and block caps; this issue concerns total policy | M transcript/context budgeting |
| `transcript-grouped-reads-omitted-their-database-source.md` | FIXED | src/seon/agent/ctx/transcript.cljs:992-998 associates ::db/db on every grouped member | Archived resolved |
| `turn-debug-must-project-rendered-transaction-ref.md` | REAL+INDEPENDENT | src/seon/repl/autocomplete.cljs:592-595 treats rendered-tx as the historical basis; debug projection correctness is outside P4 | S turn debug projection |
| `turn-debug-treated-database-error-as-entity-id.md` | REAL+INDEPENDENT | Debug adapter still must branch on flat database error values before pull; topology replacement does not guarantee it | S seon.agent.debug |
| `turn-retries-reread-provider-inputs.md` | DISSOLVES | P4 resumable steps require database run-state and the U12 restart drill; immutable step input subsumes the remaining byte-identity gate | loop-migration slice P4 |
| `uds-codec-capacity-can-delay-control-entry.md` | REAL+INDEPENDENT | src/seon/db/transport/uds.cljc:396-404 retains one bounded codec/admission executor | L UDS admission |
| `uds-fragment-accumulation-recopies-complete-prefix.md` | REAL+INDEPENDENT | src/seon/db/transport/uds.cljs still uses Buffer.concat in request/publication decoding | M UDS framing |
| `unbounded-runtime-acquisitions-exceed-frame.md` | DISSOLVES | The named execution acquisition dies at cutover and P4 resumable database steps must use bounded acquisitions; context consumers remain in U12 proof | loop-migration P4 plus cutover/U12 |
| `value-drill-has-no-total-work-bounds.md` | DISSOLVES | Config half landed; remaining renderer/route limit enforcement is the named post-cutover U10 drill | U10 value-drill graduation |
| `value-drill-result-literals-failed-boot-schema-admission.md` | UNCLEAR | Commit dc968c35 and focused proof repair source, but issue explicitly lacks exact-HEAD startup and real /agents/run proof | Probe exact HEAD bin/seon up then real /agents/run |
| `wake-and-replay-can-drive-the-same-open-run.md` | DISSOLVES | P4 replaces local pod loop drivers with CAS-claimable database run-state and any-process advancement | loop-migration slice P4/U12 |
| `web-session-navigation-provenance-is-missing.md` | REAL+INDEPENDENT | No seon.web.session namespace or message/turn provenance is supplied by P4 | L web session data model |
| `welcome-canvas-received-the-agent-under-the-wrong-key.md` | FIXED | src/seon/execution/runtime.cljs:527-547 uses :seon.agent/entity for canvas block and call | Archived resolved |
| `worktree-edit-hook-checkout-drift.md` | REAL+INDEPENDENT | Task startup/edit-hook checkout binding remains developer tooling outside runtime migration | S Codex/dev-tool bootstrap |
