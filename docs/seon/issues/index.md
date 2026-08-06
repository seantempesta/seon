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

## Blocker (19)

| Issue | Severity | Lane |
|-------|----------|------|
| [Make init publication emit progress events past the silence backstop](init-publication-silent-beyond-backstop.md) | blocker | gate-fix-operator lane (running) |
| [Observe and claim every deletable directory](deletable-directories-have-no-claim-or-size-facts.md) | blocker | operator directory-claim governor wave |
| [Prevent ranged collection from deleting resurrected branch data](ranged-store-collection-can-delete-live-segments-via-branch-resurrection.md) | blocker | exclusive sweep implementation wave |
| [Make bootstrap O4 wait for the causal delegation](bootstrap-o4-stops-before-causal-delegation-settles.md) | blocker | bootstrap delegation-drive repair wave |
| [Refuse malformed SSE data before it can change agent code](malformed-sse-data-can-change-agent-code.md) | blocker | AI provider-integrity wave |
| [Bound work submission before Flow injection can block](work-submission-can-block-before-its-time-limit.md) | blocker | Flow bounded-submission wave |
| [Cut the ~42 MB of store each eval sample costs](eval-samples-cost-42mb-of-store-each.md) | blocker | eval-scale economics wave |
| [Give `acquire!` per-row containment on the cold path](acquire-has-no-per-row-containment.md) | blocker | per-cluster live-graph wave |
| [Refuse a cluster fork whose source lacks the rows population will name](new-cluster-boot-fails-on-a-stale-published-source.md) | blocker | visual-QA fix wave |
| [Prevent one cluster from exhausting every co-hosted cluster's heap](cohosted-clusters-share-one-unbounded-agent-heap.md) | blocker | no-crash architecture design gate |
| [`seon.db/q` silently returns nil when its db argument is an error value](seon-db-q-returns-nil-on-error-value-db-argument.md) | blocker | database error-value repair |
| [Repair development MCP error locations and status scope](dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md) | blocker | development MCP envelope repair |
| [Eight `:seon.config.web/*` dials are registered with no shipped default](web-config-dials-ship-without-shipped-defaults.md) | blocker | web config default repair |
| [Isolate session deltas from other runs' context mutations](shared-context-session-delta-crosses-run-attribution.md) | blocker | per-run fork context wave |
| [Make concurrent definition receipts agree with the durable program row](concurrent-definition-receipts-can-diverge-from-durable-program-row.md) | blocker | per-run fork context wave |
| [Let evaluation errors settle their triage receipt](eval-errors-cannot-settle-triage-receipts.md) | blocker | receipt settlement repair |
| [Refuse the losing concurrent divergent schema declaration](concurrent-divergent-schema-declarations-falsely-both-succeed.md) | blocker | schema collision admission wave |
| [Make namespace removal rebuild contracted definitions only](namespace-removal-does-not-rebuild-contracted-only.md) | blocker | per-run fork context wave |
| [Install maintenance result attributes on a fresh cluster](fresh-maintenance-result-attributes-are-not-installed.md) | blocker | maintenance schema-install repair |

## Friction (95)

| Issue | Severity | Lane |
|-------|----------|------|
| [Render live-proof roots have no declared lifecycle owner](render-live-proof-roots-have-no-lifecycle-owner.md) | friction | operator directory-claim governor wave |
| [Render adversarial roots outlive their fault experiment](render-adversarial-roots-outlive-their-experiment.md) | friction | operator directory-claim governor wave |
| [Include non-installed operator and MCP leaves in the sink proof](output-sink-query-excludes-operator-and-mcp-scripts.md) | friction | universal output floor graduation wave |
| [`bin/seon init` reports only `✗ Read timed out` while the operation succeeds](seon-init-reports-read-timed-out-while-succeeding.md) | friction | operator integration wave |
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
| [Generate fresh Flow contract values](flow-generators-reuse-one-mutable-sample.md) | friction | contract-generator repair |
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
| [Preserve the Datahike refusal kind in the flat error value](transaction-refusal-drops-datahike-kind.md) | friction | database rejection projection repair |
| [Reconcile the error-class catalog with declared schemas and renderers](error-class-catalog-and-renderers-disagree.md) | friction | error class contract repair |
| [Make the generative loop fixture commit the run facts it asserts](generative-loop-fixture-commits-no-run-facts.md) | friction | generative loop fixture repair |
| [Give `seon.search/index-step` its public contract](search-index-step-has-no-public-contract.md) | friction | search public contract repair |
| [Resolve schema aliases within one admitted declaration set](schema-alias-population-cannot-resolve-an-earlier-declaration.md) | friction | schema alias admission repair |

## Cleanup (12)

| Issue | Severity | Lane |
|-------|----------|------|
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
