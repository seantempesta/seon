---
type: orchestrator
status: active
tags: [orchestrator, issue, index]
---

# Open Issues — Index

The owner's ranked SCHEDULE, maintained by hand. Every top-level open note
appears exactly once with its severity and one named destination (a running
lane or a named future wave). Validate with `bin/issues-index --check`: it
reads the notes plus this file and fails on a missing, duplicated, or
severity-mismatched row, a row naming a note that is no longer open, or a
blank destination. It does not generate this file.

Lifecycle `open → resolved | superseded`; closed issues live in `archive/`.
See `README.md` for the convention.


## Blocker (65)

| Issue | Severity | Lane |
|-------|----------|------|
| [Settle what arrived when a provider stream closes mid-body](a-mid-stream-provider-disconnect-discards-the-whole-turn.md) | blocker | whole-system arc repair wave |
| [One render exception stops the render proc and every page then hangs silently](a-render-exception-stops-the-cluster-render-proc-and-every-page-hangs.md) | blocker | page follow-up wave (after turn-cut lands) |
| [Name the run a history entry actually pulled](a-run-history-entry-can-name-a-different-run-than-its-form-pulled.md) | blocker | ui verification wave |
| [Settle a receipt for every recorded run form](a-runs-last-form-can-close-without-a-receipt.md) | blocker | live-drive context repair wave |
| [Stop a schema-resource edit from bricking value admission in running clusters](a-schema-resource-edit-bricks-value-admission-in-every-running-cluster.md) | blocker | seon.env Phase 3 production sweep ([PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)) |
| [Development adoption refuses a local plan registry reference](adoption-refuses-a-local-plan-registry-reference.md) | blocker | adoption follow-up wave (after turn-cut lands) |
| [Record a call edge when an agent form calls a core function](agent-form-calls-to-core-namespaces-are-not-indexed.md) | blocker | program-graph indexing wave |
| [Delete the separate agent transcript assembler](agent-html-still-uses-the-retired-transcript-assembler.md) | blocker | strict dogfood transcript-deletion wave |
| [Return attributes from an entity pull, never a sentence](an-entity-pull-returns-a-sentence-instead-of-its-attributes.md) | blocker | results-as-data rip-out |
| [Publish terminal evidence for every background binary result](background-binary-settlement-does-not-publish-required-event.md) | blocker | background settlement investigation |
| [Make bootstrap O4 wait for the causal delegation](bootstrap-o4-stops-before-causal-delegation-settles.md) | blocker | projection lane — causal episode production query |
| [Make accepted work require terminal evidence](class-accepted-work-can-end-without-terminal-evidence.md) | blocker | class-kill queue |
| [Make destructive reachability changes atomic](class-destructive-reachability-changes-are-not-atomic.md) | blocker | class-kill queue |
| [Give every loaded artifact enforced source identity](class-loaded-artifacts-lack-source-identity.md) | blocker | class-kill queue |
| [Make mutable resources carry their root and lifetime](class-mutable-resources-lack-explicit-root-and-lifetime.md) | blocker | class-kill queue |
| [Make outward values unable to bypass one total render contract](class-outward-values-bypass-total-render-contract.md) | blocker | class-kill queue |
| [Prevent one cluster from exhausting every co-hosted cluster's heap](cohosted-clusters-share-one-unbounded-agent-heap.md) | blocker | no-crash architecture design gate |
| [Boot the co-hosted second cluster without a 63 s stall or a false failure](cohosted-second-boot-is-slow-and-trips-the-silence-backstop.md) | blocker | boot velocity incident (co-hosted second boot) |
| [Debug feed completion waits fail only in a pooled worker](debug-feed-waits-fail-only-in-pooled-worker.md) | blocker | page follow-up wave (after turn-cut lands) |
| [Preserve a preview execution when its run changes the inspected graph](debug-source-execution-invalidates-its-own-preview.md) | blocker | page follow-up wave (after turn-cut lands) |
| [The default system-turn preview contains only an elision](debug-system-turn-elision-hides-generated-forms.md) | blocker | turn-cut lane (running, 2026-09-09) |
| [Default component probe timed out after development adoption](default-component-probe-times-out-after-adoption.md) | blocker | adoption follow-up wave (after turn-cut lands) |
| [Observe and claim every deletable directory](deletable-directories-have-no-claim-or-size-facts.md) | blocker | operator directory-claim governor wave |
| [The dependency class-cache prepare races concurrent JVM launches](dependency-class-cache-prepare-races-concurrent-jvm-launches.md) | blocker | operator launch-concurrency wave (found by overnight stress load) |
| [Repair development MCP error locations and status scope](dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md) | blocker | development MCP envelope repair |
| [Development adoption can mix host and SCI generations](development-adoption-can-mix-host-and-sci-generations.md) | blocker | adoption follow-up wave (after turn-cut lands) |
| [Development adoption tries to reload an unavailable test namespace](development-adoption-loads-test-namespace-off-classpath.md) | blocker | adoption follow-up wave (after turn-cut lands) |
| [Development adoption refuses cohosted clusters](development-adoption-refuses-cohosted-clusters.md) | blocker | adoption follow-up wave (after turn-cut lands) |
| [Development adoption retains old web service inputs](development-adoption-retains-old-web-service-inputs.md) | blocker | adoption follow-up wave |
| [Cut the ~42 MB of store each eval sample costs](eval-samples-cost-42mb-of-store-each.md) | blocker | eval-scale economics wave |
| [Route failover context through the captured rendered history](failover-adds-an-uncaptured-system-context-fragment.md) | blocker | strict dogfood provider-context repair |
| [Bound fault evidence and measure store growth](fault-facts-store-megabyte-evidence-inline-and-rewrite-gigabyte-leaves.md) | blocker | live-drive render repair wave |
| [The feed's first frame waits behind the cluster render pass, unbounded and silent](feed-first-frame-waits-behind-the-cluster-render-pass.md) | blocker | page follow-up wave (after turn-cut lands) |
| [Re-root the foreign-write custody fence on the environment](foreign-write-fence-reads-only-the-dynamic-var.md) | blocker | seon.env Phase 3 production sweep ([PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)) |
| [Read the function installation case count from configuration facts](function-install-case-count-is-read-from-an-absent-handle-key.md) | blocker | wave/context-fixes |
| [Return from the generated opening's second live pull](generated-opening-live-pull-does-not-return-after-help.md) | blocker | prefix-drift bootstrap performance diagnosis (held `bootstrap.clj`) |
| [Carry the agent-scoped environment into generated turn forks](generated-turn-fork-omits-the-agent-scoped-environment.md) | blocker | evolving-session implementation phases |
| [Supply the generated entry's required render output](generated-turn-omits-the-required-render-output.md) | blocker | generate-call-transition lane (held `loop.clj`) |
| [Indexed core function can be falsely marked installed](indexed-core-function-can-be-falsely-marked-installed.md) | blocker | issues sweep wave (2026-09-09) |
| [Explain the 24-second live root pull of 189 members](live-root-pull-of-189-members-takes-24-seconds.md) | blocker | render acquisition performance wave |
| [Derive a completion reply from the triggering message](message-completion-replies-from-the-wrong-agent-and-duplicates-the-trigger.md) | blocker | message delivery repair wave |
| [Make namespace removal rebuild contracted definitions only](namespace-removal-does-not-rebuild-contracted-only.md) | blocker | per-run fork context wave |
| [Answer no-forms replies with correction or re-wake](no-forms-replies-close-without-correction-or-rewake.md) | blocker | OWNER DESIGN GATE (correction vs re-wake) |
| [Bound orderly-stop completion joins](orderly-stop-completion-joins-have-no-bound.md) | blocker | wedge class-kill continuation |
| [Connect ordinary turns to the additive system-turn algorithm](ordinary-turns-do-not-use-the-additive-system-turn.md) | blocker | turn follow-up wave |
| [Plan renderer and its tests disagree during development publication](plan-renderer-arity-change-blocks-development-publication.md) | blocker | page follow-up wave (after turn-cut lands) |
| [Platform runner exceeds the configured worker checkouts](platform-worker-count-exceeds-prepared-checkouts.md) | blocker | wave/contract-gate |
| [Quote prose in printed data, never splice it](prose-renders-splice-unquoted-into-printed-data.md) | blocker | results-as-data rip-out |
| [Prevent ranged collection from deleting resurrected branch data](ranged-store-collection-can-delete-live-segments-via-branch-resurrection.md) | blocker | exclusive sweep implementation wave |
| [Registered render producers fall through to generic map rendering](registered-render-producers-fall-through-to-generic-map-rendering.md) | blocker | render-data plan S2 (the render unification rebuilds this seam) |
| [Execute generated form projections before they enter history](render-history-serializes-unexecuted-form-projections.md) | blocker | generated-episode receipt integration |
| [Fix render revision state overwriting its input atom](render-runtime-revision-overwrites-its-atom.md) | blocker | design-lab integration lane |
| [Make the schema environment an explicit argument, not an ambient binding](schema-environment-is-ambient-not-explicit.md) | blocker | test runner explicit-unhanded proof conversion |
| [Derive or explain every special SCI base binding](sci-base-context-silently-hand-lists-special-callables.md) | blocker | SCI base-context derivation wave |
| [`seon.db` reads rebuild the schema projection on every call when none is handed](seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed.md) | blocker | issues sweep wave (2026-09-09) |
| [A stale operator JVM refuses every `bin/seon init --changed` publication](stale-operator-jvm-refuses-every-changed-publication.md) | blocker | wave/publication-velocity |
| [Attribute and bound the store's one-day 69 GB growth](store-grew-to-69-gigabytes-in-one-day-of-lanes.md) | blocker | exclusive sweep implementation wave |
| [The default test gate expands an unset empty array](test-runner-empty-snapshot-paths-refuses-the-default-gate.md) | blocker | wave/test-fixture |
| [Show a run entry with its forms, not one sentence](the-agent-page-shows-a-run-as-one-sentence-and-never-its-forms.md) | blocker | session-view lane |
| [Preserve classified run refusals through transaction wrappers](transaction-refusal-wrapper-hides-agent-running-rule.md) | blocker | page follow-up wave (after turn-cut lands) |
| [Pass ordered entity ids to transcript `pull-many`](transcript-about-lookup-passes-a-set-to-pull-many.md) | blocker | live-drive render repair wave |
| [Register an empty agent namespace in its turn fork](turn-fork-omits-an-empty-agent-namespace.md) | blocker | turn-cut lane (running, 2026-09-09) |
| [Development acquisition refuses an uncontracted live function](uncontracted-live-function-blocks-development-acquisition.md) | blocker | adoption follow-up wave (after turn-cut lands) |
| [Resolve the declaration population once per admission, not once per node](value-admission-resolves-the-declaration-population-per-node.md) | blocker | seon.env Phase 3 production sweep ([PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)) |
| [Carry agent routing into the virtual-turn control](virtual-turn-control-loses-agent-routing.md) | blocker | turn follow-up wave |
| [Render walk-unit hiccup as markup, never escaped EDN text](walk-units-render-their-hiccup-as-escaped-edn-text.md) | blocker | ui verification wave |

## Friction (162)

| Issue | Severity | Lane |
|-------|----------|------|
| [A blocking realization is not bounded by admission's interrupt](a-blocking-realization-is-not-bounded-by-the-interrupt.md) | friction | issues sweep wave (2026-09-09) |
| [Stop a failed turn from waking itself through its own fault message](a-failed-turn-wakes-itself-through-its-own-fault-message.md) | friction | live-drive context repair wave |
| [A live cluster arms ten fewer contracts than it declares](a-live-cluster-arms-ten-fewer-contracts-than-it-declares.md) | friction | wave/contract-gate |
| [A new core predicate and the schema declaring it cannot be adopted in place](a-new-core-predicate-and-its-schema-cannot-be-adopted-in-place.md) | friction | issues sweep wave (2026-09-09) |
| [A program identity row pulls nil](a-program-identity-row-pulls-nil.md) | friction | issues sweep wave (2026-09-09) |
| [Name `index-step`'s predicate so its contract can be made durable](a-search-contract-predicate-cannot-be-made-durable.md) | friction | live-drive context repair wave |
| [Bound a six-word evaluation error to something near six words](a-six-word-eval-error-renders-as-two-thousand-characters.md) | friction | error-face budget wave |
| [A throwable-shaped fault keeps no inline evidence at any plausible bound](a-throwable-fault-keeps-no-inline-evidence-at-any-plausible-bound.md) | friction | issues sweep wave (2026-09-09) |
| [A turn that dies before replying still answers its wakes](a-turn-that-dies-before-replying-still-answers-its-wakes.md) | friction | turn-cut lane (running, 2026-09-09) |
| [Record the activation closure's schema keys and required attributes](activation-closure-records-no-schema-keys.md) | friction | boot velocity incident (co-hosted second boot) |
| [Preserve Inst semantics when a value is also collection-like](admit-inst-overlap-prefers-collection-shape.md) | friction | adversarial-audit fix wave |
| [Supply every declared render dependency in the agent-flow fixture](agent-flow-fixture-omits-render-interest.md) | friction | flow join-wedge diagnosis |
| [Make `clojure.pprint` available in the agent's REPL](agent-repl-cannot-require-clojure-pprint.md) | friction | live-drive context repair wave |
| [Give AI context rendering the retained-bytes render path](ai-context-bypasses-render-proc-retained-bytes.md) | friction | render context cache wave |
| [Replace recurring anonymous runtime contracts with named predicates](anonymous-runtime-contracts-have-recurred.md) | friction | contract-gate repair |
| [Hold one store ownership interval across artifact install and start](artifact-releases-the-fence-between-install-and-start.md) | friction | artifact startup wave |
| [Blob get assumes the file-store callback shape](blob-get-assumes-file-store-callback-shape.md) | friction | blob storage repair wave |
| [Blocked plan values refuse pull during AI projection](blocked-plan-values-refuse-pull-during-ai-projection.md) | friction | page follow-up wave (after turn-cut lands) |
| [Lead a boot refusal with the layer that refused and why](boot-refusal-has-no-render-producer.md) | friction | operator status-face hygiene |
| [Browser observation has no accessible window](browser-ui-observation-has-no-accessible-window.md) | friction | issues sweep wave |
| [Keep candidate program metadata independent of its parent](candidate-context-shares-parent-program-metadata.md) | friction | agent context; coordinate with turn batching |
| [Make the changed-test report readable at a glance](changed-test-report-is-one-enormous-line.md) | friction | dev-tooling face hygiene |
| [Make every durable contract predicate identifiable](class-anonymous-contracts-cannot-survive-publication.md) | friction | class-kill queue |
| [Make classification query facts instead of text and hand lists](class-classification-is-inferred-from-hand-lists.md) | friction | class-kill queue |
| [Translate dependency representations once at their boundary](class-dependency-representations-leak-past-boundaries.md) | friction | class-kill queue |
| [Make domain order come only from recorded order facts](class-domain-order-falls-through-to-strings-and-hashes.md) | friction | class-kill queue |
| [Make local updates unable to recompute global projections](class-local-updates-recompute-global-projections.md) | friction | class-kill queue |
| [Make proofs unable to pass without exercising their premise](class-proofs-pass-without-exercising-their-premise.md) | friction | class-kill queue |
| [Boot past half-edited foreign vars with a typed diagnostic](cluster-boot-instruments-in-flight-working-tree-vars.md) | friction | boot instrumentation scope design |
| [Accept cluster-ctx delegating arities under instrumentation](cluster-ctx-delegating-arities-refused-under-instrumentation.md) | friction | sci-eval-context-owner wave |
| [Refuse cluster names that collide with the store directory](cluster-named-store-collides-with-the-store-directory.md) | friction | R3 store-path decision |
| [Derive namespace context without a stored `my.*` roster](cluster-toolkit-stores-a-prefix-derived-projection.md) | friction | context derivation wave |
| [Co-host start can race the reachability sweep](cohost-start-races-the-reachability-sweep.md) | friction | wave/contract-gate |
| [Attribute and fix the ~70 s complete publication](complete-publication-takes-seventy-seconds.md) | friction | publication velocity incident |
| [Publish eval arming before testing concurrent interruption](concurrent-eval-test-calibrates-interpreted-work-to-wall-time.md) | friction | SCI eval readiness wave |
| [Give config-dial discovery one explicit authority](config-dial-discovery-has-three-authorities.md) | friction | config derivation wave |
| [Bound the parallel-only confirmation protocol exchange](confirmation-parallel-failure-blocks-reading-worker-protocol.md) | friction | test-platform follow-up |
| [Route exact context captures through the blob owner](context-capture-prompts-bypass-the-blob-splitter.md) | friction | eval-scale economics wave |
| [Make the Context MVP drive prove its semantic exit](context-mvp-drive-can-false-green-after-cross-agent-delivery.md) | friction | projection lane — causal episode recurring proof |
| [Contract evidence carries the offending argument twice, and busts its own bound](contract-evidence-carries-the-offending-argument-twice.md) | friction | page follow-up wave (after turn-cut lands) |
| [Attribute the seven-second core namespace-page derivation](core-namespace-pages-spend-seven-seconds-without-declaration-fallbacks.md) | friction | namespace-page performance wave |
| [Return `/data` without a five-second stall](data-page-takes-five-and-a-half-seconds-for-three-kilobytes.md) | friction | seon.env Phase 3 production sweep ([PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)) |
| [Render database identities in HTML instead of opaque host objects](database-values-render-as-opaque-host-objects-in-html.md) | friction | render important-schema producer wave |
| [Merge the 28 upstream Datahike commits our fork is missing](datahike-fork-is-28-commits-behind-upstream.md) | friction | upstream-delta sweep follow-up |
| [Render db diffs through fit with an html producer](db-diff-render-bypasses-print-fit-and-has-no-html.md) | friction | db-diff completion gate |
| [`seon.db-test` still expects a unique agent namespace](db-test-still-expects-a-unique-agent-namespace.md) | friction | issues sweep wave (2026-09-09) |
| [The debug page's HTML render carries no agent-scoped environment](debug-html-render-carries-no-agent-scoped-environment.md) | friction | page follow-up wave (after turn-cut lands) |
| [Make the debug left pane the exact bytes the agent received](debug-left-pane-is-not-the-exact-prompt.md) | friction | visual-QA fix wave |
| [Stop sending debug pages patches for elements they do not have](debug-pages-receive-block-patches-for-elements-they-do-not-have.md) | friction | UI watchability wave (2026-08-10 route walk) |
| [Default web request times out during partial adoption](default-web-request-times-out-during-partial-adoption.md) | friction | adoption follow-up wave (after turn-cut lands) |
| [Bound dependency-cache preparation before the test coordinator starts](dependency-cache-lock-wait-has-no-deadline.md) | friction | wave/test-fixture |
| [Dependency resolution can fail in Maven model validation](dependency-resolution-can-race-maven-model-validation.md) | friction | runner follow-up wave (concurrent classpath builds) |
| [Development adoption cannot load the canonical test support](development-adoption-cannot-load-test-support.md) | friction | adoption follow-up wave (after turn-cut lands) |
| [Development adoption drops the web server while it reloads](development-adoption-drops-the-web-server.md) | friction | adoption follow-up wave (after turn-cut lands) |
| [`doc` prints schema bodies and flattens arity alternatives — the primary teaching surface is ugly and misleading](doc-contract-lines-print-schema-bodies-and-flatten-arity-alternatives.md) | friction | page follow-up wave (after turn-cut lands) |
| [Seed drive clusters with their required plan facts](drive-one-starts-without-required-plan-facts.md) | friction | drive-1 defect wave |
| [Admit definitions after dynamically hidden namespace movement](dynamic-in-ns-cannot-persist-definition-namespace.md) | friction | per-run fork context wave |
| [Fix edit-hook kondo false positives on seon.db dynamic vars](edit-hook-kondo-false-positives-on-seon-db-dynamic-vars.md) | friction | dev-tooling-face-hygiene wave |
| [Order effect feedback by numeric facts](effect-feedback-orders-receipts-by-id.md) | friction | effect ordering follow-up |
| [Effect receipts have no render producers](effect-receipts-have-no-render-producers.md) | friction | render receipt-producer wave |
| [Reconcile the error-class catalog with declared schemas and renderers](error-class-catalog-and-renderers-disagree.md) | friction | error class contract repair |
| [Give eval episode backstops one declared owner](eval-drives-duplicate-a-four-minute-run-clock.md) | friction | eval-driver lifecycle wave |
| [One bounded log face per expected transaction refusal](expected-refusal-logs-raw-datom-error-twice.md) | friction | Datahike fork logging-seam wave |
| [Feed writer casts an absent package number](feed-writer-casts-an-absent-package-number.md) | friction | page follow-up wave (after turn-cut lands) |
| [Create the store with the write-amplification options it already has](file-store-commits-pay-five-times-the-fsyncs-they-need.md) | friction | store/perf fix lane |
| [Adopt flow's read-set control and sanctioned egress](flow-has-no-read-set-control-and-a-hand-rolled-egress.md) | friction | flow-protocol wave |
| [Pass the root :io executor to the work-launcher graph](flow-work-launcher-graph-omits-its-root-io-executor.md) | friction | seon.env Phase 3 production sweep ([PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)) |
| [Make generation dependency analysis see keywords](generation-dependency-analysis-ignores-keywords.md) | friction | evolving-session phases |
| [Give offline roster discovery a current read-only helper](give-offline-roster-discovery-a-current-read-only-helper.md) | friction | operator artifact follow-up |
| [Make the history-policy refusal test independent of machine load](history-policy-refusal-test-is-load-flaky.md) | friction | test fixture repair wave |
| [Bind first-party namespaces so value-position reads deref](host-bound-first-party-vars-break-in-value-position.md) | friction | SCI eval-context owner design gate |
| [Hyperlith pin is 23 commits behind the upstream lockstep rework](hyperlith-pin-behind-lockstep-rework.md) | friction | upstream-delta sweep after seon.env Phase 0 |
| [Lead a failed init with its cause, not the event history](init-failure-dumps-entire-prepl-event-history.md) | friction | operator status-face hygiene |
| [Derive the initial-paint census instead of hand-maintaining it](initial-paint-census-is-a-hand-maintained-count.md) | friction | render test repair |
| [Require the general printer bound for every contract headline](instrumentation-headline-unbounded-when-caps-absent.md) | friction | adversarial-audit fix wave |
| [Preserve the interrupted blob staging artifact until it can be observed](interrupted-blob-staging-leaves-no-observable-artifact.md) | friction | blob staging repair wave |
| [Isolated operator init requires source files inside its root](isolated-operator-init-requires-a-source-checkout.md) | friction | wave/operator-artifact-follow-up |
| [State a position on `:keep-history?` instead of inheriting it](keep-history-is-on-by-default-without-a-decision.md) | friction | store/perf fix lane |
| [Select the latest closed run without comparing run ids](latest-closed-run-orders-by-id-string.md) | friction | work ordering follow-up |
| [Derive predicate-owner readiness before live source publication](live-publication-has-a-hand-maintained-predicate-owner-reload.md) | friction | publication registration-provenance wave |
| [Give open map unions explicit discriminants](map-unions-have-no-explicit-discriminants.md) | friction | open-maps accretion wave |
| [Fence the MCP parent watchdog by captured process identity](mcp-parent-watchdog-can-follow-a-reused-pid.md) | friction | MCP process-lifetime repair |
| [Project an MCP value whose map keys are not keywords](mcp-projection-crashes-on-non-keyword-map-keys.md) | friction | whole-system arc repair wave |
| [MCP runtime_status lists no clusters for an explicit live root](mcp-runtime-status-lists-no-clusters-for-an-explicit-root.md) | friction | wave/dev-mcp |
| [Cut `my.background/poll`'s ~290 tokens per polled result](my-background-poll-costs-290-tokens-per-polled-result.md) | friction | capability surface repair wave |
| [Make `my.fs/write` and `my.shell/run` docstrings teach the shapes they accept](my-fs-write-docstring-hides-its-own-request-shape.md) | friction | capability surface repair wave |
| [Connect namespace alias and refer targets with refs](namespace-binding-targets-are-symbols-not-refs.md) | friction | future program-graph binding wave |
| [Give namespace and debug content a usable layout](namespace-layout-confines-most-content-to-scroll-boxes.md) | friction | agent context |
| [Serve the namespace page first byte under ten seconds](namespace-page-first-byte-exceeds-ten-seconds.md) | friction | drive-1 defect wave |
| [Render namespace pages without renderer-unavailable spam](namespace-page-repeats-renderer-unavailable.md) | friction | drive-1 defect wave |
| [Render actual namespace identities in dependency summaries](namespace-require-summary-renders-nil-identities.md) | friction | render important-schema producer wave |
| [Reject negative imports at the escape/static admission boundary](negative-import-masks-escape-static-admission.md) | friction | SCI static-admission repair wave |
| [Publish graph transitions instead of polling them in tests](observable-graph-transitions-are-polled-in-tests.md) | friction | Core |
| [One elision, one representation](one-elision-has-two-representations-in-one-context.md) | friction | results-as-data rip-out |
| [Stop two identity attributes from naming one string](one-identity-string-names-two-entities.md) | friction | config/cluster identity design gate |
| [Generate only action-demanded opening candidates](opening-generator-pushes-undemanded-candidates.md) | friction | prefix-drift demand-first generation design (held `bootstrap.clj`) |
| [Stop the opening walkthrough replicating its usage test per agent](opening-walkthrough-replicates-a-usage-test.md) | friction | evolving-session implementation phases |
| [Derive operator process identity without command substring lists](operator-classifies-processes-by-command-substrings.md) | friction | operator process-identity wave |
| [Operator down gives different process censuses for the same scratch root](operator-down-misses-a-live-scratch-jvm-from-another-checkout.md) | friction | wave/operator-process-identity |
| [Carry the operator root explicitly instead of guessing from names](operator-root-inference-guesses-from-directory-names.md) | friction | R3 store-path decision |
| [Bound `bin/seon status` instead of dumping every absent test result](operator-status-dumps-every-absent-test-result.md) | friction | wave/operator-status-face |
| [Read foreign live roots in operator status](operator-status-refuses-foreign-live-root.md) | friction | drive-1 defect wave |
| [Include non-installed operator and MCP leaves in the sink proof](output-sink-query-excludes-operator-and-mcp-scripts.md) | friction | program-index production-subject wave |
| [Derive fleet state from events, not a 20 ms ping absence](oversight-treats-a-20ms-ping-absence-as-state.md) | friction | render oversight event wave |
| [Parallel published-base acquisition can lose a filestore key](parallel-test-base-connect-can-lose-a-filestore-key.md) | friction | runner follow-up wave |
| [Classify parallel-only test failures by their shared resource](parallel-test-stress-exposes-eleven-isolation-sensitive-tests.md) | friction | parallel stress triage wave |
| [Partial hot reload leaves a live JVM running mixed old and new code](partial-hot-reload-produces-mixed-code-with-no-warning.md) | friction | general |
| [Record posh's cardinality-one pull-analysis arity defect](posh-cardinality-one-pull-analysis-has-an-arity-defect.md) | friction | upstream-delta sweep |
| [Clear the pre-rename root claims that make every status noisy](pre-rename-root-claims-are-unreadable-noise-on-every-status.md) | friction | operator lock-scope follow-up |
| [Make production docstrings describe the surviving runtime](production-docstrings-teach-deleted-semantics.md) | friction | production documentation-honesty wave |
| [Make the provider descriptor own its output-token wire key](provider-output-token-wire-key-is-hard-coded.md) | friction | AI provider protocol wave |
| [Publication's live-JVM reload hand-lists namespaces and misses dependencies](publication-reload-hand-lists-namespaces-and-misses-dependencies.md) | friction | operator launch-concurrency wave (second occurrence 2026-08-08) |
| [Report malformed root claims in the reap result](reap-dead-roots-calls-delete-recursively-with-a-nil-path.md) | friction | wave/directory-claims |
| [Tolerate entries vanishing during recursive deletion](recursive-delete-throws-when-entries-vanish-mid-walk.md) | friction | operator velocity fixes |
| [Render adversarial roots outlive their fault experiment](render-adversarial-roots-outlive-their-experiment.md) | friction | operator directory-claim governor wave |
| [Check renderer input and output on one arity](render-candidate-checks-mix-different-arities.md) | friction | agent context |
| [Render live-proof roots have no declared lifecycle owner](render-live-proof-roots-have-no-lifecycle-owner.md) | friction | operator directory-claim governor wave |
| [Skip unchanged renderer invocations in the package proc](render-package-proc-reruns-unchanged-renderers.md) | friction | render package economics wave |
| [Carry the viewing namespace through rendering](render-selection-loses-the-viewing-namespace.md) | friction | agent context |
| [Give render token budgets one config owner instead of private dials](render-token-budgets-are-private-dials-no-producer-supplies.md) | friction | context wave fix lane |
| [Derive render-walk connections without a function hand list](render-walk-maintains-a-derived-edge-hand-list.md) | friction | render connection-model wave |
| [Make the render wave's properties able to produce their failing cases](render-wave-properties-cannot-produce-their-failing-cases.md) | friction | W2 — render property premise repair |
| [Promote the 34 proven REPL-parity divergences as the print path lands](repl-parity-divergences.md) | friction | print-path implementation wave |
| [Delete bloated stores at IO pace with progress](reset-deletes-a-bloated-store-one-lstat-at-a-time.md) | friction | operator velocity fixes |
| [Invalidate retained render calls when a selected producer changes](retained-render-packages-survive-producer-replacement.md) | friction | render package economics wave |
| [Give the shared compute executor per-cluster fairness](root-compute-executor-has-no-per-cluster-fairness.md) | friction | shared-surface scheduling design gate |
| [Fit the root maintenance context to the provider budget](root-maintenance-context-exceeds-provider-budget.md) | friction | drive-1 defect wave |
| [Root's turns on a fresh dev cluster hit the context-acquisition backstop](root-turns-on-a-fresh-dev-cluster-hit-the-context-acquisition-backstop.md) | friction | turn-cut lane (running, 2026-09-09) |
| [Render run forms and receipts with strict REPL fidelity](run-renderer-narrates-forms-and-receipts.md) | friction | strict REPL display wave |
| [Resolve namespace aliases before selecting runtime lint stubs](runtime-lint-does-not-resolve-namespace-aliases.md) | friction | future runtime-lint wave |
| [Split the turn and evaluation kernels at durable boundaries](runtime-turn-and-evaluate-kernels-conflate-boundaries.md) | friction | runtime boundary refactor |
| [Construct the schedule graph test from a real environment-bearing handle](schedule-graph-test-constructs-a-handle-without-an-environment.md) | friction | schedule fixture repair |
| [Stop rebuilding gigabytes of schema state for one declaration](schema-declaration-rebuilds-four-gigabytes-per-form.md) | friction | schema projection performance wave |
| [Permit accretive schema loosenings over existing data](schema-guard-refuses-accretive-loosenings-with-data.md) | friction | schema-lifecycle wave |
| [Extract Malli map entries by shape, not position](schema-map-extraction-still-depends-on-position-two.md) | friction | schema-form extraction repair |
| [Separate declared search metadata from the process index ID](search-index-property-collides-with-process-index-id.md) | friction | cluster search wiring wave |
| [Refuse `:db.secondary/only` until a covering index exists](secondary-only-attributes-have-no-covering-index.md) | friction | schema-lifecycle wave |
| [Give `seon.db` the branch and commit reads root needs](seon-db-has-no-branch-or-commit-reads.md) | friction | `my.branch` verb wave (W-C, [PRD](../../prds/sci-execution-runtime/plan/agent-desk-and-checkout-prd-2026-08-05.md)) |
| [Give `seon.cluster.loop/settle!` a complete public contract](settle-is-public-without-a-complete-contract.md) | friction | unreadable-reply lane coordination |
| [Identify shared store growth during renderer changes](shared-store-grows-during-render-source-work.md) | friction | page follow-up wave (after turn-cut lands) |
| [Cut the 11.8 s source load back under the ten-second law](source-load-is-118s-against-the-ten-second-law.md) | friction | load-time incident |
| [Investigate the scratch config publication fingerprint collision](source-publication-fingerprint-collision-after-config-property-reordering.md) | friction | adoption follow-up wave |
| [A stale dev-dependency cache serves wrong classes silently](stale-dev-dependency-cache-serves-wrong-classes-silently.md) | friction | boot-velocity wave |
| [Name the stale clj-kondo cache entry that blocks correct code](stale-language-specific-kondo-cache-blocks-correct-code.md) | friction | dev-tooling face hygiene |
| [The stop contract rejects the stopped instance it promises to accept](stop-contract-rejects-stopped-instances.md) | friction | issues sweep wave (2026-09-09) |
| [Give storage GC the cutoff that makes it actually reclaim](storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing.md) | friction | store/perf fix lane |
| [Give system-generated messages arrival ordinals](system-generated-messages-omit-arrival-ordinals.md) | friction | message transaction-data repair |
| [Make the oversized terminal-refusal settle as one schema-valid error fact](terminal-refusal-error-fact-fails-on-oversized-data.md) | friction | settlement fix wave |
| [The database identity face cannot satisfy its own declared input](the-database-identity-face-cannot-satisfy-its-own-declared-input.md) | friction | page follow-up wave (after turn-cut lands) |
| [Wrap the debug AI pane](the-debug-ai-pane-never-wraps.md) | friction | session-view lane |
| [Make the value floor map face readable EDN](the-value-floors-map-face-is-not-readable-edn.md) | friction | results-as-data rip-out |
| [Give thinking tool continuations one faithful request shape](thinking-tool-continuations-have-no-faithful-request-shape.md) | friction | future model-continuation wave |
| [Keep interpreter-private markers out of the time-limit face](time-limit-face-exposes-interpreter-interrupt-marker.md) | friction | SCI failure-face repair wave |
| [Order transcript receipt and comment candidates by numeric facts](transcript-candidate-window-orders-receipts-and-comments-by-id.md) | friction | transcript ordering follow-up |
| [Render transcript entries as forms and actual values](transcript-renderer-encodes-entries-as-comment-forms.md) | friction | strict REPL display wave |
| [Turn consumers retain obsolete fixture and observation contracts](turn-consumer-fixtures-read-retired-result-storage.md) | friction | turn-rename lane (running, 2026-09-09) |
| [Turn evaluations bypass work submission](turn-evaluations-bypass-work-submission.md) | friction | turn follow-up wave |
| [Turn source syntax blocks default adoption](turn-source-syntax-blocks-default-adoption-2026-09-08.md) | friction | turn-cut lane (running, 2026-09-09) |
| [Two turn backstops fire and the sliding fault channel keeps the wrong one](two-turn-backstops-fire-and-the-sliding-fault-channel-keeps-the-wrong-one.md) | friction | turn-cut lane (running, 2026-09-09) |
| [Close the 2026-08-01 unlogged findings (interop policy, agent write surface, rot)](unlogged-findings-2026-08-01.md) | friction | general |
| [Clear the floor's residue, duplicate cursors, and marker hand list](value-floor-residue-duplicate-cursors-and-marker-hand-lists.md) | friction | context wave fix lane |
| [May a run refine a schema key nothing depends on?](within-run-schema-key-refinement-needs-an-owner-ruling.md) | friction | owner design ruling (schema-key immutability vs the usage guard) |

## Cleanup (17)

| Issue | Severity | Lane |
|-------|----------|------|
| [Five tests leave their worker stripped of contracts they did not restore](a-platform-test-leaves-its-worker-stripped-of-every-contract.md) | cleanup | wave/contract-gate |
| [Keep the page body from scrolling sideways on a phone](agent-pages-overflow-a-phone-viewport.md) | cleanup | visual-QA fix wave |
| [Derive callable shape documentation from executable contracts](class-documentation-restates-executable-contracts.md) | cleanup | class-kill queue |
| [Reject readerless rows and duplicate mechanisms at publication](class-readerless-duplicate-mechanisms-survive-cuts.md) | cleanup | class-kill queue |
| [Declare the config-to-request ident route instead of string-building it](config-ai-request-idents-are-derived-by-string-surgery.md) | cleanup | config application-contract cleanup wave |
| [Fix the context wave's three small honesty defects](context-wave-leaves-three-small-honesty-defects.md) | cleanup | context wave fix lane |
| [Unify :seon.db/database-value into :seon.db/db](database-value-shape-name-duplicates-the-db-key.md) | cleanup | post-gate rename wave (with kind migration) |
| [Select duplicate-identity refusal evidence deterministically](duplicate-identity-refusal-evidence-is-unordered.md) | cleanup | reconcile evidence cleanup wave |
| [Make fresh CLJC namespaces portable or name them CLJ](fresh-cljc-files-are-jvm-only.md) | cleanup | fresh portability cleanup wave |
| [Stop opaque contract generators from sharing live process objects](opaque-contract-generators-share-live-process-objects.md) | cleanup | contract-generator cleanup wave |
| [The retired form projection is still declared and selected at HEAD](retired-form-projection-still-declared-and-selected.md) | cleanup | page follow-up wave (after turn-cut lands) |
| [Delete five readerless schema rows left by completed cuts](schema-population-retains-five-readerless-rows.md) | cleanup | schema population deletion wave |
| [Carry schema source provenance as immutable admission data](schema-source-provenance-accumulates-in-a-global-atom.md) | cleanup | schema admission cleanup wave |
| [The unowned-namespace oversight line still inverts assignment](unowned-namespace-oversight-still-inverts-assignment.md) | cleanup | page follow-up wave (after turn-cut lands) |
| [Vendor parinferish under reference-code](vendor-parinferish-under-reference-code.md) | cleanup | wave/upstream-delta |
| [Commit the babashka-process AOT patch to a fork](vendored-babashka-process-carries-a-local-aot-patch.md) | cleanup | vendoring hygiene |
| [Close the remaining vendored-versus-pinned dependency drift](vendored-transit-clj-drifts-from-the-pinned-artifact.md) | cleanup | upstream-delta sweep follow-up |
