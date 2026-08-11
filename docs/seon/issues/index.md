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

## Blocker (38)

| Issue | Severity | Lane |
|-------|----------|------|
| [Make the render value floor total over ordinary unqualified-key maps](render-value-floor-refuses-any-map-with-unqualified-keys.md) | blocker | interface-economy W1 total-floor wave |
| [Reconcile the context architecture with append-only refresh entries](context-architecture-conflicts-with-append-only-refresh-entries.md) | blocker | self-generating context design (this session) |
| [Make pull evidence cover automatic component expansion](datahike-pull-evidence-misses-automatic-component-expansion.md) | blocker | root-pull implementation wave |
| [Publish terminal evidence for every background binary result](background-binary-settlement-does-not-publish-required-event.md) | blocker | background settlement investigation |
| [Make the first injected core fault observable at the fault committer](fault-committer-misses-the-first-injected-fault.md) | blocker | fault-facts lane |
| [Rebuild an export without reopening an already-connected branch](export-fallback-reopens-an-already-connected-branch.md) | blocker | suite-speed-tail / export owner |
| [Stop the bootstrap plan's deliberate failures from interrupting the run](bootstrap-teaching-failures-strand-every-new-agent.md) | blocker | agent context repair wave (2026-08-10 audit) |
| [Restore agent definitions without re-executing authored source](agent-definition-restore-reexecutes-authored-source.md) | blocker | env-once implementation wave |
| [Every agent prompt is a neighborhood render-walk contract violation](every-agent-prompt-is-a-neighborhood-render-walk-contract-violation.md) | blocker | live-drive context repair wave |
| [Settle a receipt for every recorded run form](a-runs-last-form-can-close-without-a-receipt.md) | blocker | live-drive context repair wave |
| [Substitute the bootstrap plan's namespace placeholder before it is evaluated](bootstrap-plan-forms-ship-unsubstituted-namespace-placeholders.md) | blocker | live-drive context repair wave |
| [Boot the co-hosted second cluster without a 63 s stall or a false failure](cohosted-second-boot-is-slow-and-trips-the-silence-backstop.md) | blocker | boot velocity incident (co-hosted second boot) |
| [Instrument each cluster under its own projection, not the anchor's](instrumentation-compiles-under-one-clusters-projection.md) | blocker | seon.env Phase 3 production sweep ([PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)) |
| [Resolve the declaration population once per admission, not once per node](value-admission-resolves-the-declaration-population-per-node.md) | blocker | seon.env Phase 3 production sweep ([PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)) |
| [Re-root the foreign-write custody fence on the environment](foreign-write-fence-reads-only-the-dynamic-var.md) | blocker | seon.env Phase 3 production sweep ([PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)) |
| [Make the schema environment an explicit argument, not an ambient binding](schema-environment-is-ambient-not-explicit.md) | blocker | environment-as-a-value design session |
| [Pass ordered entity ids to transcript `pull-many`](transcript-about-lookup-passes-a-set-to-pull-many.md) | blocker | live-drive render repair wave |
| [Observe and claim every deletable directory](deletable-directories-have-no-claim-or-size-facts.md) | blocker | operator directory-claim governor wave |
| [Prevent ranged collection from deleting resurrected branch data](ranged-store-collection-can-delete-live-segments-via-branch-resurrection.md) | blocker | exclusive sweep implementation wave |
| [Make bootstrap O4 wait for the causal delegation](bootstrap-o4-stops-before-causal-delegation-settles.md) | blocker | bootstrap delegation-drive repair wave |
| [Refuse malformed SSE data before it can change agent code](malformed-sse-data-can-change-agent-code.md) | blocker | AI provider-integrity wave |
| [Bound work submission before Flow injection can block](work-submission-can-block-before-its-time-limit.md) | blocker | Flow bounded-submission wave |
| [Cut the ~42 MB of store each eval sample costs](eval-samples-cost-42mb-of-store-each.md) | blocker | eval-scale economics wave |
| [Give `acquire!` per-row containment on the cold path](acquire-has-no-per-row-containment.md) | blocker | per-cluster live-graph wave |
| [Prevent one cluster from exhausting every co-hosted cluster's heap](cohosted-clusters-share-one-unbounded-agent-heap.md) | blocker | no-crash architecture design gate |
| [Repair development MCP error locations and status scope](dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md) | blocker | development MCP envelope repair |
| [Eight `:seon.config.web/*` dials are registered with no shipped default](web-config-dials-ship-without-shipped-defaults.md) | blocker | web config default repair |
| [Isolate session deltas from other runs' context mutations](shared-context-session-delta-crosses-run-attribution.md) | blocker | per-run fork context wave |
| [Make namespace removal rebuild contracted definitions only](namespace-removal-does-not-rebuild-contracted-only.md) | blocker | per-run fork context wave |
| [Attribute and cut the ~2.4 s a run pays between every form](a-run-pays-two-and-a-half-seconds-between-every-form.md) | blocker | development-velocity incident (run loop per-form cost) |
| [Register the inline `[:fn]` predicate that refuses every corpus projection](an-inline-fn-predicate-in-src-refuses-every-corpus-projection.md) | blocker | changed-test selector lane |
| [Stop a schema-resource edit from bricking value admission in running clusters](a-schema-resource-edit-bricks-value-admission-in-every-running-cluster.md) | blocker | seon.env Phase 3 production sweep ([PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)) |
| [Confirm the background `:io` connection fix with a real `my.shell` drive](every-background-capability-request-loses-its-connection.md) | blocker | tool-exercise lane re-run against `f3b8eabda` |
| [Reuse one HttpClient so concurrent provider calls stop closing mid-read](concurrent-provider-calls-fail-with-a-closed-response-body.md) | blocker | whole-system arc repair wave |
| [Settle what arrived when a provider stream closes mid-body](a-mid-stream-provider-disconnect-discards-the-whole-turn.md) | blocker | whole-system arc repair wave |
| [Await every worker writer before deleting its root](test-runner-cleans-a-worker-root-while-kondo-is-still-writing.md) | blocker | test runner cleanup repair wave |

## Friction (136)

| Issue | Severity | Lane |
|-------|----------|------|
| [Stop the Datahike pin statements drifting again](datahike-current-pin-statements-drifted-again.md) | friction | docs pass (this session) |
| [Record posh's cardinality-one pull-analysis arity defect](posh-cardinality-one-pull-analysis-has-an-arity-defect.md) | friction | upstream-delta sweep |
| [Read terminal failure from the settlement key its producer writes](loop-settlement-consumer-reads-a-key-no-producer-writes.md) | friction | loop settlement contract wave |
| [Derive the oversight fleet proof from the live proc roster](oversight-fleet-test-pins-a-stale-proc-roster.md) | friction | oversight test repair |
| [Give `seon.cluster.loop/settle!` a complete public contract](settle-is-public-without-a-complete-contract.md) | friction | unreadable-reply lane coordination |
| [Construct the schedule graph test from a real environment-bearing handle](schedule-graph-test-constructs-a-handle-without-an-environment.md) | friction | schedule fixture repair |
| [Render a capability namespace by its API, not by its Malli source](namespace-units-render-error-schema-boilerplate.md) | friction | agent context repair wave (2026-08-10 audit) |
| [Render database identities in HTML instead of opaque host objects](database-values-render-as-opaque-host-objects-in-html.md) | friction | render important-schema producer wave |
| [Do not tell an agent an unindexed namespace is empty](unindexed-namespaces-render-as-empty.md) | friction | agent context repair wave (2026-08-10 audit) |
| [Show a never-run agent's prospective context on its debug page](a-never-run-agents-context-cannot-be-inspected.md) | friction | agent context repair wave (2026-08-10 audit) |
| [Stop sending debug pages patches for elements they do not have](debug-pages-receive-block-patches-for-elements-they-do-not-have.md) | friction | UI watchability wave (2026-08-10 route walk) |
| [May a run refine a schema key nothing depends on?](within-run-schema-key-refinement-needs-an-owner-ruling.md) | friction | owner design ruling (schema-key immutability vs the usage guard) |
| [Stop a failed turn from waking itself through its own fault message](a-failed-turn-wakes-itself-through-its-own-fault-message.md) | friction | live-drive context repair wave |
| [Make `clojure.pprint` available in the agent's REPL](agent-repl-cannot-require-clojure-pprint.md) | friction | live-drive context repair wave |
| [Name the arity when an agent calls its own function with the wrong one](a-wrong-arity-call-reports-a-missing-namespace.md) | friction | live-drive context repair wave |
| [Project an MCP value whose map keys are not keywords](mcp-projection-crashes-on-non-keyword-map-keys.md) | friction | whole-system arc repair wave |
| [Name `index-step`'s predicate so its contract can be made durable](a-search-contract-predicate-cannot-be-made-durable.md) | friction | live-drive context repair wave |
| [Return `/data` without a five-second stall](data-page-takes-five-and-a-half-seconds-for-three-kilobytes.md) | friction | seon.env Phase 3 production sweep ([PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)) |
| [Attribute the seven-second core namespace-page derivation](core-namespace-pages-spend-seven-seconds-without-declaration-fallbacks.md) | friction | namespace-page performance wave |
| [Name the face when `semantic-value` cannot match one](an-unmatched-print-face-throws-no-matching-clause-and-names-nothing.md) | friction | error-face budget wave |
| [Cut `my.background/poll`'s ~290 tokens per polled result](my-background-poll-costs-290-tokens-per-polled-result.md) | friction | capability surface repair wave |
| [Bound a six-word evaluation error to something near six words](a-six-word-eval-error-renders-as-two-thousand-characters.md) | friction | error-face budget wave |
| [Return a UTF-8 `my.web/fetch` body as text, not integers](my-web-fetch-returns-plain-html-as-a-vector-of-integers.md) | friction | capability surface repair wave |
| [Scope the JVM operator's per-root work off the installation lock](jvm-operator-work-takes-the-installation-lock-for-one-root.md) | friction | RULED keep-serial 2026-08-08; annotated, revisit at measured four-worker contention |
| [Clear the pre-rename root claims that make every status noisy](pre-rename-root-claims-are-unreadable-noise-on-every-status.md) | friction | operator lock-scope follow-up |
| [Record the activation closure's schema keys and required attributes](activation-closure-records-no-schema-keys.md) | friction | boot velocity incident (co-hosted second boot) |
| [Lead a boot refusal with the layer that refused and why](boot-refusal-has-no-render-producer.md) | friction | operator status-face hygiene |
| [Make `my.fs/write` and `my.shell/run` docstrings teach the shapes they accept](my-fs-write-docstring-hides-its-own-request-shape.md) | friction | capability surface repair wave |
| [Make the history-policy refusal test independent of machine load](history-policy-refusal-test-is-load-flaky.md) | friction | test fixture repair wave |
| [Classify parallel-only test failures by their shared resource](parallel-test-stress-exposes-eleven-isolation-sensitive-tests.md) | friction | parallel stress triage wave |
| [Give `malli-form?` its declarations from the environment](malli-form-predicate-resolves-the-declaration-population-itself.md) | friction | seon.env Phase 3 production sweep ([PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)) |
| [Stop marker declarations from flooding the exact-reuse advisory](schema-exact-reuse-warnings-are-unreadable-at-volume.md) | friction | dev-tooling face hygiene |
| [Name the stale clj-kondo cache entry that blocks correct code](stale-language-specific-kondo-cache-blocks-correct-code.md) | friction | dev-tooling face hygiene |
| [Stop two identity attributes from naming one string](one-identity-string-names-two-entities.md) | friction | config/cluster identity design gate |
| [Give `seon.db` the branch and commit reads root needs](seon-db-has-no-branch-or-commit-reads.md) | friction | `my.branch` verb wave (W-C, [PRD](../../prds/sci-execution-runtime/plan/agent-desk-and-checkout-prd-2026-08-05.md)) |
| [Pass the root :io executor to the work-launcher graph](flow-work-launcher-graph-omits-its-root-io-executor.md) | friction | seon.env Phase 3 production sweep ([PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)) |
| [Hyperlith pin is 23 commits behind the upstream lockstep rework](hyperlith-pin-behind-lockstep-rework.md) | friction | upstream-delta sweep after seon.env Phase 0 |
| [The dependency class-cache prepare races concurrent JVM launches](dependency-class-cache-prepare-races-concurrent-jvm-launches.md) | friction | operator launch-concurrency wave (found by overnight stress load) |
| [Publication's live-JVM reload hand-lists namespaces and misses dependencies](publication-reload-hand-lists-namespaces-and-misses-dependencies.md) | friction | operator launch-concurrency wave (second occurrence 2026-08-08) |
| [Give system-generated messages arrival ordinals](system-generated-messages-omit-arrival-ordinals.md) | friction | message transaction-data repair |
| [Attribute and fix the ~70 s complete publication](complete-publication-takes-seventy-seconds.md) | friction | publication velocity incident |
| [Lead a failed init with its cause, not the event history](init-failure-dumps-entire-prepl-event-history.md) | friction | operator status-face hygiene |
| [Quiet the unreadable-external-claim flood in `bin/seon status`](status-floods-unreadable-external-claim-warnings.md) | friction | operator status-face hygiene (follow-up to gate-fix-operator) |
| [One bounded log face per expected transaction refusal](expected-refusal-logs-raw-datom-error-twice.md) | friction | Datahike fork logging-seam wave |
| [Make the changed-test report readable at a glance](changed-test-report-is-one-enormous-line.md) | friction | dev-tooling face hygiene |
| [Render live-proof roots have no declared lifecycle owner](render-live-proof-roots-have-no-lifecycle-owner.md) | friction | operator directory-claim governor wave |
| [Render adversarial roots outlive their fault experiment](render-adversarial-roots-outlive-their-experiment.md) | friction | operator directory-claim governor wave |
| [Include non-installed operator and MCP leaves in the sink proof](output-sink-query-excludes-operator-and-mcp-scripts.md) | friction | universal output floor graduation wave |
| [Stop reporting an MCP-proven live prepl as unreachable](status-reports-a-live-mcp-proven-prepl-unreachable.md) | friction | operator status-truth wave |
| [Give open map unions explicit discriminants](map-unions-have-no-explicit-discriminants.md) | friction | open-maps accretion wave |
| [Give debug pages the real live-process set](debug-pages-invent-wedged-runs.md) | friction | render liveness-evidence wave |
| [Make the AI transport taxonomy test assert its premise](ai-transport-taxonomy-test-can-run-zero-assertions.md) | friction | AI provider-integrity wave |
| [Give the SCI source-size cap a declared owner](sci-reader-hides-a-production-source-cap.md) | friction | SCI reader-limit wave |
| [Derive render-walk connections without a function hand list](render-walk-maintains-a-derived-edge-hand-list.md) | friction | render connection-model wave |
| [Skip unchanged renderer invocations in the package proc](render-package-proc-reruns-unchanged-renderers.md) | friction | render package economics wave |
| [Give AI context rendering the retained-bytes render path](ai-context-bypasses-render-proc-retained-bytes.md) | friction | render context cache wave |
| [Make the provider descriptor own its output-token wire key](provider-output-token-wire-key-is-hard-coded.md) | friction | AI provider protocol wave |
| [Give eval episode backstops one declared owner](eval-drives-duplicate-a-four-minute-run-clock.md) | friction | eval-driver lifecycle wave |
| [Replace recurring anonymous runtime contracts with named predicates](anonymous-runtime-contracts-have-recurred.md) | friction | contract-gate repair |
| [Name the offending Var in Malli registration failures](malli-registration-errors-hide-the-offending-var.md) | friction | contract-gate repair |
| [Derive operator process identity without command substring lists](operator-classifies-processes-by-command-substrings.md) | friction | operator process-identity wave |
| [Bound operator subprocess reads and waits](operator-subprocesses-have-unbounded-read-and-wait-paths.md) | friction | operator child-lifecycle wave |
| [Teach namespaced data in the bootstrap contract example](bootstrap-teaches-bare-map-keys.md) | friction | bootstrap instruction wave |
| [Delete the readerless second Datahike transaction codec](schema-datahike-keeps-a-readerless-second-codec.md) | friction | schema codec deletion wave |
| [Derive predicate-owner readiness before live source publication](live-publication-has-a-hand-maintained-predicate-owner-reload.md) | friction | publication registration-provenance wave |
| [Hold one store ownership interval across artifact install and start](artifact-releases-the-fence-between-install-and-start.md) | friction | artifact startup wave |
| [Make production docstrings describe the surviving runtime](production-docstrings-teach-deleted-semantics.md) | friction | production documentation-honesty wave |
| [Cut the 11.8 s source load back under the ten-second law](source-load-is-118s-against-the-ten-second-law.md) | friction | load-time incident |
| [Remove the deleted run lease from the AI retry proof](ai-retry-proof-still-cites-the-deleted-run-lease.md) | friction | AI retry evidence wave |
| [Derive fleet state from events, not a 20 ms ping absence](oversight-treats-a-20ms-ping-absence-as-state.md) | friction | render oversight event wave |
| [Reject negative imports at the escape/static admission boundary](negative-import-masks-escape-static-admission.md) | friction | SCI static-admission repair wave |
| [Say what a predicate schema expected instead of "unknown error"](predicate-schema-violations-humanize-to-unknown-error.md) | friction | agent-diagnostics repair |
| [Derive the initial-paint census instead of hand-maintaining it](initial-paint-census-is-a-hand-maintained-count.md) | friction | render test repair |
| [Blob get assumes the file-store callback shape](blob-get-assumes-file-store-callback-shape.md) | friction | blob storage repair wave |
| [Derive namespace context without a stored `my.*` roster](cluster-toolkit-stores-a-prefix-derived-projection.md) | friction | context derivation wave |
| [Split the turn and evaluation kernels at durable boundaries](runtime-turn-and-evaluate-kernels-conflate-boundaries.md) | friction | runtime boundary refactor |
| [Give config-dial discovery one explicit authority](config-dial-discovery-has-three-authorities.md) | friction | config derivation wave |
| [Derive changed-test ownership instead of classifying paths](changed-test-selector-classifies-hosts-by-path-prefix.md) | friction | changed-test selector repair |
| [Make the public-contract census prove its subjects exist](public-contract-census-can-pass-with-no-subjects.md) | friction | contract-gate repair |
| [Give thinking tool continuations one faithful request shape](thinking-tool-continuations-have-no-faithful-request-shape.md) | friction | future model-continuation wave |
| [Fence the MCP parent watchdog by captured process identity](mcp-parent-watchdog-can-follow-a-reused-pid.md) | friction | MCP process-lifetime repair |
| [Make the oversized terminal-refusal settle as one schema-valid error fact](terminal-refusal-error-fact-fails-on-oversized-data.md) | friction | settlement fix wave |
| [Promote the 34 proven REPL-parity divergences as the print path lands](repl-parity-divergences.md) | friction | print-path implementation wave |
| [Close the 2026-08-01 unlogged findings (interop policy, agent write surface, rot)](unlogged-findings-2026-08-01.md) | friction | general |
| [Stop rebuilding the whole schema projection on every contracted `defn`](contracted-defn-rebuilds-the-whole-schema-projection.md) | friction | per-cluster live-graph wave |
| [Stop rebuilding gigabytes of schema state for one declaration](schema-declaration-rebuilds-four-gigabytes-per-form.md) | friction | schema projection performance wave |
| [Give offline roster discovery a current read-only helper](give-offline-roster-discovery-a-current-read-only-helper.md) | friction | operator artifact follow-up |
| [Separate declared search metadata from the process index ID](search-index-property-collides-with-process-index-id.md) | friction | cluster search wiring wave |
| [Make the debug left pane the exact bytes the agent received](debug-left-pane-is-not-the-exact-prompt.md) | friction | visual-QA fix wave |
| [Bind first-party namespaces so value-position reads deref](host-bound-first-party-vars-break-in-value-position.md) | friction | SCI eval-context owner design gate |
| [Create the store with the write-amplification options it already has](file-store-commits-pay-five-times-the-fsyncs-they-need.md) | friction | store/perf fix lane |
| [Give render token budgets one config owner instead of private dials](render-token-budgets-are-private-dials-no-producer-supplies.md) | friction | context wave fix lane |
| [Make the render wave's properties able to produce their failing cases](render-wave-properties-cannot-produce-their-failing-cases.md) | friction | context wave fix lane |
| [Clear the floor's residue, duplicate cursors, and marker hand list](value-floor-residue-duplicate-cursors-and-marker-hand-lists.md) | friction | context wave fix lane |
| [Align vendored Malli source with the pinned dependency](malli-vendor-is-ahead-of-pinned-dependency.md) | friction | general |
| [Merge the 28 upstream Datahike commits our fork is missing](datahike-fork-is-28-commits-behind-upstream.md) | friction | upstream-delta sweep follow-up |
| [State a position on `:keep-history?` instead of inheriting it](keep-history-is-on-by-default-without-a-decision.md) | friction | store/perf fix lane |
| [Give storage GC the cutoff that makes it actually reclaim](storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing.md) | friction | store/perf fix lane |
| [Refuse `:db.secondary/only` until a covering index exists](secondary-only-attributes-have-no-covering-index.md) | friction | schema-lifecycle wave |
| [Adopt flow's read-set control and sanctioned egress](flow-has-no-read-set-control-and-a-hand-rolled-egress.md) | friction | flow-protocol wave |
| [Connect namespace alias and refer targets with refs](namespace-binding-targets-are-symbols-not-refs.md) | friction | future program-graph binding wave |
| [Partial hot reload leaves a live JVM running mixed old and new code](partial-hot-reload-produces-mixed-code-with-no-warning.md) | friction | general |
| [Refuse invalid database read identities instead of returning absence](database-read-admission-treats-invalid-identities-as-absence.md) | friction | database read-admission repair |
| [Make database request errors name the public operation](database-request-shape-errors-bypass-public-contracts.md) | friction | database diagnostic-output wave |
| [Publish graph transitions instead of polling them in tests](observable-graph-transitions-are-polled-in-tests.md) | friction | Core |
| [Acquire Flow test resources inside their cleanup scope](flow-monitor-test-resources-outlive-their-cleanup-scope.md) | friction | test fixture repair wave |
| [Publish eval arming before testing concurrent interruption](concurrent-eval-test-calibrates-interpreted-work-to-wall-time.md) | friction | SCI eval readiness wave |
| [Resolve namespace aliases before selecting runtime lint stubs](runtime-lint-does-not-resolve-namespace-aliases.md) | friction | future runtime-lint wave |
| [Permit accretive schema loosenings over existing data](schema-guard-refuses-accretive-loosenings-with-data.md) | friction | schema-lifecycle wave |
| [Make the Context MVP drive prove its semantic exit](context-mvp-drive-can-false-green-after-cross-agent-delivery.md) | friction | context MVP harness wave |
| [Preserve Inst semantics when a value is also collection-like](admit-inst-overlap-prefers-collection-shape.md) | friction | adversarial-audit fix wave |
| [Require the general printer bound for every contract headline](instrumentation-headline-unbounded-when-caps-absent.md) | friction | adversarial-audit fix wave |
| [Keep interpreter-private markers out of the time-limit face](time-limit-face-exposes-interpreter-interrupt-marker.md) | friction | SCI failure-face repair wave |
| [Keep contract-violation evidence as data](contract-violation-serializes-print-tree-inside-error-data.md) | friction | instrumentation error-data repair wave |
| [Preserve the throw-site message when an error carries another error](nested-error-data-hides-the-throw-site-message.md) | friction | SCI failure-face repair wave |
| [Route exact context captures through the blob owner](context-capture-prompts-bypass-the-blob-splitter.md) | friction | eval-scale economics wave |
| [Give the shared compute executor per-cluster fairness](root-compute-executor-has-no-per-cluster-fairness.md) | friction | shared-surface scheduling design gate |
| [Extract Malli map entries by shape, not position](schema-map-extraction-still-depends-on-position-two.md) | friction | schema-form extraction repair |
| [Stop encoding namespace-render results as source comments](namespace-renderer-encodes-results-as-comments.md) | friction | strict REPL display wave |
| [Render transcript entries as forms and actual values](transcript-renderer-encodes-entries-as-comment-forms.md) | friction | strict REPL display wave |
| [Make the rendered walk an ordinary REPL value](render-walk-frames-values-as-comments.md) | friction | strict REPL display wave |
| [Return walk state and failures without comment notices](render-walk-wrapper-returns-comment-notices.md) | friction | strict REPL display wave |
| [Render effect notices as ordinary values](effect-context-suffix-returns-comment-notices.md) | friction | strict REPL display wave |
| [Render run forms and receipts with strict REPL fidelity](run-renderer-narrates-forms-and-receipts.md) | friction | strict REPL display wave |
| [Keep nested map sequences structurally readable](nested-map-sequences-render-as-tables-inside-structural-values.md) | friction | print-path follow-up |
| [Effect receipts have no render producers](effect-receipts-have-no-render-producers.md) | friction | render receipt-producer wave |
| [Cluster, config, and bootstrap plan render as raw maps](cluster-config-and-bootstrap-plan-render-as-raw-maps.md) | friction | render important-schema producer wave |
| [Order transcript receipt and comment candidates by numeric facts](transcript-candidate-window-orders-receipts-and-comments-by-id.md) | friction | transcript ordering follow-up |
| [Order effect feedback by numeric facts](effect-feedback-orders-receipts-by-id.md) | friction | effect ordering follow-up |
| [Select the latest closed run without comparing run ids](latest-closed-run-orders-by-id-string.md) | friction | work ordering follow-up |
| [Admit definitions after dynamically hidden namespace movement](dynamic-in-ns-cannot-persist-definition-namespace.md) | friction | per-run fork context wave |
| [Preserve the interrupted blob staging artifact until it can be observed](interrupted-blob-staging-leaves-no-observable-artifact.md) | friction | blob staging repair wave |
| [Reconcile the error-class catalog with declared schemas and renderers](error-class-catalog-and-renderers-disagree.md) | friction | error class contract repair |
| [Make the generative loop fixture commit the run facts it asserts](generative-loop-fixture-commits-no-run-facts.md) | friction | generative loop fixture repair |

## Cleanup (13)

| Issue | Severity | Lane |
|-------|----------|------|
| [Carry schema source provenance as immutable admission data](schema-source-provenance-accumulates-in-a-global-atom.md) | cleanup | schema admission cleanup wave |
| [Make fresh CLJC namespaces portable or name them CLJ](fresh-cljc-files-are-jvm-only.md) | cleanup | fresh portability cleanup wave |
| [Select duplicate-identity refusal evidence deterministically](duplicate-identity-refusal-evidence-is-unordered.md) | cleanup | reconcile evidence cleanup wave |
| [Delete five readerless schema rows left by completed cuts](schema-population-retains-five-readerless-rows.md) | cleanup | schema population deletion wave |
| [Keep the page body from scrolling sideways on a phone](agent-pages-overflow-a-phone-viewport.md) | cleanup | visual-QA fix wave |
| [Fix the context wave's three small honesty defects](context-wave-leaves-three-small-honesty-defects.md) | cleanup | context wave fix lane |
| [Give Flow configuration dials one registration owner](flow-config-dials-have-two-registration-owners.md) | cleanup | Core |
| [Remove or implement monitor-graph's throwing command proc](monitor-graph-command-proc-throws.md) | cleanup | flow-protocol wave |
| [Delete the konserve LRU our fork allocates and never reads](datahike-allocates-a-konserve-cache-it-never-reads.md) | cleanup | store/perf fix lane |
| [Read the symbol SCI already puts in analysis ex-data](sci-analysis-ex-data-carries-a-symbol-nothing-reads.md) | cleanup | SCI eval-context owner design gate |
| [Close the remaining vendored-versus-pinned dependency drift](vendored-transit-clj-drifts-from-the-pinned-artifact.md) | cleanup | upstream-delta sweep follow-up |
| [Stop opaque contract generators from sharing live process objects](opaque-contract-generators-share-live-process-objects.md) | cleanup | contract-generator cleanup wave |
| [Declare the config-to-request ident route instead of string-building it](config-ai-request-idents-are-derived-by-string-surgery.md) | cleanup | config application-contract cleanup wave |
