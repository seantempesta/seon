---
type: research
status: complete
tags: [research, issue, agent, runtime]
---

# Overnight open-issue triage — 2026-07-23

## Scope and method

The sweep read every top-level issue note under `docs/seon/issues/` that
existed at dispatch. Authorities, the generated index, and `archive/` were not
counted as open issues. No source, test, build, runtime, or live-system change
was made.

An issue was archived only when the current source plus retained commit or
behavioral evidence satisfied or superseded its acceptance contract. A landed
source change without its explicitly required live or integration proof stayed
open. Every other note is classified exactly once as a queued-unit fold, a
bounded fix candidate, or an owner contract/taste question.

## Count reconciliation

| Measure | Count |
|---|---:|
| Actual top-level issue notes before this sweep | 126 |
| `STALE-VERIFY-ARCHIVE` | 5 |
| `FOLD-INTO-UNIT` | 54 |
| `FIX-TONIGHT` | 36 |
| `NEEDS-OWNER` | 31 |
| Actual open notes after verified archives | 121 |

The anchor's “113 open, triaged” statement was thirteen below the actual
before-count and eight below the truthful after-count. The generated index was
stale in the other direction: it rendered 130 issue rows because it still
listed 20 notes already in `archive/` and omitted 16 newer top-level notes.
Those two errors netted to four extra rows. After normalization, the 121 open
notes comprise 42 blockers, 71 friction issues, and 8 cleanup issues.

## Verified archives

| Issue | Status | Resolving evidence |
|---|---|---|
| `fresh-boot-prevalidation-misclassifies-core-contracts.md` | resolved | `adc25b852`, `b6ecb55df`, and `ad8eeb582`; explicit boot provenance at `src/seon/client.cljs:1624-1644`, committed reconstruction at `src/seon/runtime/admission.cljs:209-228`, reusable-projection regression at `test/seon/client_initialization_test.cljs:224-260`, and fresh readiness recorded by `25edc8cff` / `a55419c02`. |
| `guarded-eval-door-lacks-a-bun-installation-and-config-owner.md` | resolved | `8000f5327` plus config ownership in `3d8c9a9a6`; retained holder/reset at `src/seon/host/guard.cljc:47-72`, one entry at `:210-220`, portable hostile-loop proof at `test/seon/host/guard_test.cljc:51-67`, and second-session proof at `test/seon/host/guard_context_test.clj:17-75`. |
| `host-registry-writer-fixture-defns-lack-contracts.md` | resolved | `fe4bfed0c`; complete durable fixture contract at `test/seon/host_registry_writer_test.clj:488-490`, recording/replay proof through `:519-638`, and accepted focused 40-test/247-assertion gate in `efdce2b67`. |
| `my-canvas-clj-branch-references-missing-render-canvas-fn.md` | resolved | `488f3dd5e`; one portable field-signal owner at `src/seon/render/canvas/field_signal.cljc:9-18`, with the issue's retained JVM require and twelve-namespace load evidence. |
| `nested-authored-render-hides-child-reload.md` | resolved | Superseded by the guarded host-render door in `cd7d3ebf8` on `8bd19774e`; current routing is `src/seon/agent/turn.cljs:393-447` through the seam at `src/seon/render.cljc:43,67-68`, so the former nested child-reload containment path no longer exists. |

## Folded queued units

Every file in this section now carries its owning unit in the issue note.

### U9 deletion — 23

| Issue | Evidence |
|---|---|
| `async-try-expression-iife-auto-awaits.md` | Exists only in the outgoing self-host compiler. |
| `bootstrap-analyzer-api-emits-undeclared-var-warnings.md` | Emitted by the self-host bootstrap/analyzer artifact. |
| `container-launch-omits-execution-artifact.md` | Current acceptance publishes the execution-child artifact U9 deletes. |
| `debug-feed-captures-foreign-database-reads.md` | Remaining loss crosses the outgoing child prompt boundary. |
| `downstream-runtime-package-is-not-self-contained.md` | Package contract still names the child and self-host bootstrap. |
| `eval-process-isolation-memory-containment.md` | Per-agent arbitrary-eval process topology is deleted. |
| `execution-artifact-packages-local-datahike.md` | Entire defect is in the outgoing execution artifact. |
| `execution-child-gpu-allocation-dominates-footprint.md` | Measured heap cost is specific to child startup. |
| `execution-child-program-load-omitted-instrumentation.md` | Remaining parity belongs at the guarded host door, not the old loader. |
| `execution-children-retain-hundreds-of-megabytes.md` | Retained compiler heaps belong to deleted children. |
| `execution-process-proof-seeds-incomplete-schema-population.md` | Fixture proves the outgoing real Bun-child topology. |
| `host-base-agent-surface-parity.md` | U9 census-to-zero owns symbol and capability-effect parity. |
| `inspect-product-snapshot-assumes-nonexistent-evidence.md` | Scorer currently joins child failure/replacement evidence. |
| `multi-form-eval-order-is-not-durable.md` | Position becomes claimed run-step/attempt data. |
| `namespace-addressed-resident-agents.md` | Warm-child residency and generated-DAG dispatch are cutover topology. |
| `persisted-program-error-prevents-agent-repair.md` | Remaining diagnostic is tied to self-host program preparation. |
| `plan-reopen-cross-agent-authority.md` | Process holding the run-owned capability stamping supplies the actor fence. |
| `planner-home-ns-step-blocks-on-self-recipient.md` | Generated namespace-DAG dispatch is outgoing scheduler behavior. |
| `planner-self-done-bypasses-generated-terminal-delivery.md` | Fresh terminal delivery becomes a claimed-run transition. |
| `rendering-and-turns-collided-in-one-execution-child.md` | The collision exists in the child U9 deletes. |
| `selfhost-cljs-test-is-thunk-resolution.md` | Confined to `cljs.js/eval-str`. |
| `successful-eval-receipt-called-state-on-nil.md` | Failing `eval.cljs/record-eval!` path is deleted. |
| `toolkit-current-ns-wedges-agent-after-cljc-packaging.md` | Remaining namespace-state mechanism is the deleted self-host path. |

### P3 read-side admission — 3

| Issue | Evidence |
|---|---|
| `contract-predicate-transitive-purity-awaits-execution-planner.md` | Planner walks bundles, but acquired projections still default the graph-proven predicate population empty. |
| `datahike-read-dependencies-miss-valid-query-and-pull-inputs.md` | Parser projection landed; cache/listener/schema-change and generated false-negative proof remain. |
| `read-side-attribute-admission-fails-open.md` | P3 must apply the existing query/pull extractor as a fail-closed gate. |

### R45 S-ladder — 5

| Issue | Evidence |
|---|---|
| `fresh-boot-271s-rederives-build-computed-state.md` | Projection construction is 14× faster, but latest fresh readiness remained about 314 seconds. |
| `inspect-live-cluster-caller-drift.md` | Per-sample lease must compose with release/cluster identity and apply/start. |
| `program-indexer-drops-valid-specs-outside-active-schema-projection.md` | Build rows still depend on ambient Malli state. |
| `shadow-deps-mode-declaration-drift.md` | Release pre-processing must remove duplicate build/dependency authority. |
| `shared-bootstrap-output-mutates-running-artifact.md` | Content-addressed roots landed; simultaneous default/ACME proof remains. |

### Web slice 2 — 12

| Issue | Evidence |
|---|---|
| `attribute-only-feed-interest-recomputes-unrelated-agent-views.md` | Entity-scoped writer interests require feed/browser cost proof. |
| `bespoke-reactive-loops-outside-seon-reactive.md` | Router, advertisement, shutdown, and settlement must converge on `seon.reactive`. |
| `datastar-feed-retains-failed-render-after-hot-reload.md` | Failed render must invalidate on the next relevant transaction. |
| `lazy-view-unit-activation-drops-read-observations.md` | Activation capture and stable-DOM convergence are web-transition acceptance. |
| `pod-remains-ready-after-web-listener-loss.md` | Listener loss and readiness withdrawal belong to web-process health. |
| `render-entity-converters-silently-vanish-on-unresolved-symbol.md` | One unresolved-symbol family must span AI, HTML, and routes. |
| `render-twin-runs-function-twice.md` | Shared render unit must derive twins once per database value. |
| `root-page-is-an-ordinary-agent-layout.md` | Dedicated system layout and browser/SSE proof are one web slice. |
| `static-routes-bypass-database-route-authority.md` | JVM router must delete the second literal route catalog. |
| `surface-recency-recomputed.md` | Recency belongs to shared render-unit dependency invalidation. |
| `value-drill-has-no-total-work-bounds.md` | Route/renderer effective limits remain in the value-browser cut. |
| `web-session-navigation-provenance-is-missing.md` | Browser-tab session model and two-tab proof are web UI work. |

### C1 codec — 4

| Issue | Evidence |
|---|---|
| `arbitrary-database-results-collide-with-error-shape.md` | Requires the one closed outer result/error union. |
| `projected-map-keys-are-not-drill-paths.md` | Remaining strict path codec belongs to boundary totality. |
| `transact-output-schema-crashed-child-on-ordinary-error.md` | Refusal-to-success same-runtime proof is a totality property. |
| `turn-debug-treated-database-error-as-entity-id.md` | Remaining structured capacity-error proof is result/error discrimination. |

### Test-simplification batch — 5

| Issue | Evidence |
|---|---|
| `compiled-program-contains-nilable-value-schemas.md` | Remaining complete-population proof must use the one paged fixture path. |
| `full-writer-gate-fails-during-runtime-lane-integration.md` | Current checkpoint still lacks one coherent green after fixture repairs. |
| `lora-audit-runner-drift.md` | Retired target, checkout pin, and scratch runner are test-path consolidation. |
| `operator-trial-processes-leak-across-days.md` | Interrupted real trial cleanup remains a fixture-lifecycle proof. |
| `removed-embedded-multiagent-coverage-needs-owner.md` | Missing cases must move under existing focused owners. |

### Single-owner queued fixes — 2

| Unit | Issue | Evidence |
|---|---|---|
| Busy-spin fix | `writer-run-readiness-busy-spins-without-runtime.md` | Ruled repair parks on missing runtime ownership and wakes on publication. |
| Poll/timeout conversion | `shadow-runtime-stops-reconnecting.md` | Reconnect proof belongs with event-driven progress and stall detection. |

No current open issue note maps directly to `schedfix/R46`; the accepted
scheduled-functions defect is tracked in the program ledger and its design
research rather than as a top-level issue note.

## Fix-tonight candidates

These remain open until implementation and their stated proof both land.

| Issue | Current evidence | Value / risk |
|---|---|---|
| `acme-operator-migration-drift.md` | `fe5e289b9` reaps current graph members, not provably dead retired IDs. | Medium / medium process safety. |
| `ai-context-is-not-pure-over-database-value.md` | Source and cross-process regression landed; live restart byte comparison remains. | High / low, proof-only. |
| `branch-trial-tests-write-into-live-operator-state.md` | Trial state still nests under the live source process directory. | High shared-state safety / low-medium. |
| `bun-rejection-net-loses-async-scope.md` | Process rejection net lacks immutable config/dev provenance. | Very high reliability / medium concurrency. |
| `callable-contract-output-data-becomes-phantom-arity.md` | Source/index repair landed; clean ACME database/card proof remains. | Medium / low, proof-only. |
| `changed-test-new-cljs-namespace-misses-runtime-file.md` | Fingerprint covers source but not selected runtime-module membership. | High gate trust / medium build-path. |
| `database-session-concurrent-open-is-not-shared.md` | Promise sharing exists; failure fanout, conflicting selection, and close-during-open coverage remain. | High resilience / low. |
| `datahike-execute-many-predicate-query-fails.md` | Portable `execute-many` parity remains wrong for predicate queries. | Medium / medium dependency. |
| `datahike-force-branch-does-not-preserve-secondary-root.md` | Dependency repair selected; cold file restore/cutover proof remains. | Blocker value / medium operational. |
| `dependency-preparation-can-crash-inside-clojure-hashmap.md` | Checkout lock exists; concurrent falsifier and retained external-failure diagnostic remain. | Medium gate trust / low code, potentially slow proof. |
| `dev-eval-fault-scope-misses-mcp-funnels.md` | Returned-Promise path fixed; detached rejection remains. | Very high reliability / medium; same mechanism as Bun rejection net. |
| `ensure-database-creates-fresh-store-at-any-path.md` | Writer still ensures before enforcing explicit path/open intent. | Blocker / medium writer risk. |
| `home-schema-references-namespace-name-before-registration.md` | `home.cljc` references `:seon.ns/name`; sole registration remains in `ctx/render_fns.cljc`. | High cold-load determinism / low. |
| `host-session-errors-vanish-silently.md` | Session exceptions can still collapse into close-only behavior. | High diagnostic/recovery / low-medium protocol. |
| `inspect-pod-solver-cannot-address-existing-agent.md` | `8efd3366` implements it; one source-frozen root-row proof remains. | Medium / very low, proof-only. |
| `inspect-reachability-assumes-nonexistent-evidence.md` | Production fields and focused tests exist; one live row remains. | Medium / very low, proof-only. |
| `inspect-source-dependency-is-not-content-pinned.md` | Mutable `.gitignore` can still alter source-admission classification. | High evidence integrity / low-medium. |
| `installed-schema-map-misclassified-as-database-error.md` | String-message guard and regression landed; live same-agent retry remains. | High / very low, proof-only. |
| `multi-source-query-cache-retains-foreign-database-values.md` | Core dependency fix exists; four-source, full-checkpoint, and one-source perf proof remain. | High memory safety / low. |
| `plan-address-step-priority.md` | Portable owner still permits message-address rows to displace authored work. | Medium / medium semantics. |
| `plan-reconcile-scope-can-delete-unseen-work.md` | Reconcile still lacks the document database value and exact-root fence. | Very high data safety / high. |
| `planned-restart-cannot-observe-writer-drain-result.md` | Generation-matched capture exists; exact default restart/live-loss proof remains. | High / medium coordinated proof. |
| `planner-lacks-per-root-purity-projection.md` | Regex classifier remains at `src/seon/host/context.clj:975` with four consumers. | High architecture / medium. |
| `private-function-presence-law-incomplete.md` | Two producers store false and generic namespace render still shows private rows. | High R39 correctness / medium migration. |
| `retain-live-eval-values-in-the-owning-jvm-host.md` | Retention exists; two-tier page/retire live falsifier remains. | High U9 value / low source risk. |
| `root-context-replaces-base-capability-requires.md` | Additive composition landed; frozen ACME edge/prompt/idempotence proof remains. | High / low, proof-only. |
| `root-warnings-block-renders-146k-tokens-before-cap.md` | Warning owner still acquires and renders full affected tables before clipping. | High recurring cost / medium. |
| `seon-db-store-id-needs-named-predicate-schema.md` | Store ID remains a bounded vector containing `:any`. | Medium schema quality / low. |
| `shared-head-amend-can-absorb-concurrent-commit.md` | Instructions prohibit history changes generally but do not name amend or prove the harness guard. | Medium collaboration safety / low. |
| `single-entity-pulls-budgeted-as-one-result-node.md` | `my.plan` fixed; two literal-one siblings remain in restore and web brand reads. | Medium admission correctness / low. |
| `stale-reference-docs.md` | Both named documents still contain dead paths or Datalevin-era text. | Low-medium / very low, docs-only. |
| `test-runner-does-not-prepare-selected-git-dependencies.md` | Shared prep exists; stale-output fixture plus fresh writer/ACME proof remain. | Medium gate trust / medium. |
| `turn-debug-must-project-rendered-transaction-ref.md` | `bab671364` fixed source/regression; frozen `/agents/run` integer proof remains. | Blocker closure / very low. |
| `uds-codec-capacity-can-delay-control-entry.md` | One bounded executor still owns both codec work and handler admission. | Very high recovery / high concurrency. |
| `uds-fragment-accumulation-recopies-complete-prefix.md` | Linear parser and regression landed; explicit live fragmentation/event-loop proof remains. | Medium performance / low, proof-only. |
| `value-drill-result-literals-failed-boot-schema-admission.md` | `dc968c35` fixed source/focused proof; exact-HEAD startup and real run remain. | Blocker closure / low, proof-only. |

## Ranked top-10 dispatch list

The ordering favors core data/recovery safety, already-ruled contracts, and
small owners that do not overlap the reserved C1/deadline/P6 work.

1. `ensure-database-creates-fresh-store-at-any-path.md` — prevent silent
   wrong-store creation at the writer choke point.
2. `bun-rejection-net-loses-async-scope.md` plus
   `dev-eval-fault-scope-misses-mcp-funnels.md` — one detached-rejection
   process-net repair closes both notes.
3. `host-session-errors-vanish-silently.md` — emit one typed session error
   frame and one durable/logged fault instead of unexplained EOF.
4. `private-function-presence-law-incomplete.md` — finish R39 at the two
   producers and generic namespace renderer.
5. `home-schema-references-namespace-name-before-registration.md` — move the
   namespace identity declaration to its dependency-neutral owner.
6. `root-warnings-block-renders-146k-tokens-before-cap.md` — bound work before
   full warning-table materialization.
7. `changed-test-new-cljs-namespace-misses-runtime-file.md` — make runtime
   artifact membership an admission fact, not a source-fingerprint inference.
8. `multi-source-query-cache-retains-foreign-database-values.md` — finish the
   four-source and allocation proof around the landed dependency repair.
9. `turn-debug-must-project-rendered-transaction-ref.md` plus
   `value-drill-result-literals-failed-boot-schema-admission.md` — close two
   source-fixed blockers in the first post-freeze live drive.
10. `branch-trial-tests-write-into-live-operator-state.md` — isolate trial
    process state before more interrupted operator runs accumulate evidence.

`plan-reconcile-scope-can-delete-unseen-work.md` and
`uds-codec-capacity-can-delay-control-entry.md` are higher raw severity than
several entries above, but their data-deletion and concurrency blast radii make
them deliberate specialist dispatches, not filler lanes.

## Needs-owner morning items

| Issue | Question | Recommendation |
|---|---|---|
| `acme-typeahead-worker-unavailable.md` | Authorize external worker identity/readiness work now? | Only when another scored ACME run is wanted; require endpoint origin, model/implementation digest, and readiness first. |
| `agent-turns-lack-database-read-cost-attribution.md` | Is turn-level database cost graduation-critical? | Defer until cutover, then land pull evidence, durable aggregation, dial, and debug waterfall together. |
| `als-unify-tx-meta.md` | Collapse operation carriers before or after U9? | After U9; keep invocation-local read evidence distinct. |
| `autocomplete-data-quality-pipeline-drift.md` | Resume autocomplete training in this program? | Keep paused; later authorize one canonical export/Inspect unit. |
| `autocomplete-worktree-evidence-preservation.md` | Approve maintenance windows and durable destinations for legacy databases? | Preserve, quiesce, promote content-addressably, read back, then authorize deletion separately. |
| `bound-temporal-index-page-work.md` | Prioritize dependency-native temporal paging now? | Defer until a live workload exercises it. |
| `bun-enterwith-toplevel-segfault.md` | Retain any `enterWith` contract? | Eliminate the sole caller during carrier unification rather than depend on a Bun fix. |
| `context-block-order-is-static.md` | Make adaptive cache-gradient ordering current? | Retain static ordering until Inspect evidence supplies priors and hysteresis. |
| `d13-merge-broke-bare-babashka-loading.md` | Is source loading without repository `bb.edn` supported? | No; declare `bb.edn` the contract and correct stale prose. |
| `database-query-tuple-shape-legibility.md` | Change values or improve examples/rendering? | Keep values exact; add schema-driven compact examples for each result shape. |
| `datahike-cljs-cardinality-many-collapses-large-bigints.md` | Guarantee native BigInt beyond safe integer range? | Yes, but schedule a grounded Datahike equality/hash repair after the spine. |
| `datahike-http-remote-connection-identity-mismatch.md` | Is the unused HTTP transport maintained? | Declare unsupported unless a consumer is named; otherwise fix one canonical identity. |
| `generated-root-has-no-planner-retry-path.md` | Retry planner assignment or immediately close blocked? | Retry once, then record an addressed `:blocked` terminal. |
| `inspect-concurrent-agent-messages.md` | Retain this evaluation after cutover? | Yes, rewritten against ordinary processes holding runs and run after U9. |
| `inspect-model-transport-evidence-is-incomplete.md` | Is local-model comparison a graduation claim? | Defer until a maintained immutable local worker exists. |
| `kimi-k3-continuation-compatibility.md` | Is provider-native tool continuation supported? | Claim only single-response compatibility until product-required. |
| `plan-completion-verification-evidence.md` | Which verifier/evidence forms may close a step? | Start with schema-owned verifiers plus immutable database/eval refs and CAS; never prose self-attestation. |
| `restore-completion-cannot-precede-admission.md` | Authorize a destructive source-frozen restore lifecycle proof? | One coordinated restore proof unit after checkpoint. |
| `restore-completion-reuses-operator-intent-identity.md` | May that unit also prove intent/completion ID separation? | Yes; keep it in the same restore window. |
| `restore-intent-does-not-freeze-client-artifact.md` | Run the crash/source-edit intent falsifier? | Include it in the single coordinated restore proof. |
| `restore-intent-lacks-exclusive-writer-fence.md` | Authorize accepted-write/abort/replan/crash/undo proof? | Same restore coordinator, not a piecemeal patch. |
| `restore-writer-admin-transition-is-unimplemented.md` | Preserve every versioned secondary through force? | Preserve the contract; queue a dedicated Datahike/Proximum unit. |
| `shared-file-persistence-limits-cross-database-writer-scaling.md` | Calibrate shared-device or isolated-device deployment? | Use measured per-device envelopes; reject the guessed threshold. |
| `subagents-block-is-implemented-but-not-installed.md` | Install the compact child block now? | Keep dormant until solo graduation, then select with Inspect evidence. |
| `tailwind-node-module-register-deprecation.md` | Is Node 26 supported now? | Retain Node 26 and take the smallest upstream Tailwind fix; do not suppress warnings globally. |
| `temporal-query-work-is-not-shared.md` | What perf/retention threshold graduates the cache? | Derive it from the expected historical-query workload first. |
| `transcript-decay-does-not-bound-total-context.md` | Which measured Inspect schedule becomes policy? | Keep the mechanically bounded current scheme until the plateau evaluation selects a winner. |
| `turn-retries-reread-provider-inputs.md` | Which permitted unit owns frozen-step byte identity? | Add it to U9 cluster JVM cutover proof rather than patching the outgoing retry path. |
| `unbounded-runtime-acquisitions-exceed-frame.md` | Is the old 64-KiB synthetic floor still required? | Supersede it with the supported negotiated ceiling and assign surviving context proof to U9/R45. |
| `wake-and-replay-can-drive-the-same-open-run.md` | Should U9 own the no-double-driver gate? | Yes; make one claimed driver across restart a U9 graduation condition. |
| `worktree-edit-hook-checkout-drift.md` | Support dynamic rerooting or task-start binding? | Declare task-start binding; keep mid-task worktree moves unsupported until hooks reroot atomically. |

## Graduation relationship

The earliest unsettled program contract remains the frozen checkpoint break
chain, not any triage candidate. The dependency-ready portfolio after green is
U9, P3, schedfix/R46, test simplification, busy-spin, and the source-disjoint
fix list above. Final graduation remains the U12-scale live system proof after
the ordered cutover and P-ladder complete; reducing the open count does not
replace that gate.
