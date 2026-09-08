---
type: research
status: blocked
date: 2026-09-08
tags: [research, verification, test]
---

# Issues sweep stopped at the protected plan-renderer boundary

The lane stopped under the assignment's final instruction: “If another lane's
in-flight breakage blocks your gate, STOP and report the exact boundary.” No
production code, tests, or issue lifecycle statuses were changed. This is an
incomplete sweep, not a claim that the open inventory has been reviewed.

## Authorities and scope

The supplied AGENTS.md was read end to end. The on-disk combined authority read
was output-truncated, so it is not claimed as a complete independent reread.
The PRD's §10 and §13–§15 and issues/README.md were read completely; the first
combined full-PRD read was truncated. Issue inventory body reads also exceeded
the tool output limit. Only the individually verified complete reads below
are claimed; every other note explicitly remains unreviewed or incompletely
read. No delegation was used.

## Exact gate boundary

Bare `bin/test` exited 1 while preparing its shared published base, before
running any tests. Its snapshot base was
`e9e15a585727adfec7b781d28f5e3eca7035e8c9`, root `tmp/test-runs/run.c49nRe`,
runner PID 30249. Four blocking `:invalid-arity` findings named
`test/my/plan_test.clj` lines 73, 196, 230, and 440:

```text
my.plan/render-plan-html is called with 2 args but expects 1
```

The snapshot's `src/my/plan.clj:1165` defines the new renderer; the shared
working-tree diff confirms the in-flight change to one component-view argument.
The assignment gives `src/my/*` to record-render. These are the same four
locations already recorded in
[the existing issue](../../../seon/issues/plan-renderer-arity-change-blocks-development-publication.md).
The extracted, deduplicated findings are preserved in
[the gate evidence](issues-sweep-gate-errors-2026-09-08.json).

No explicit subject gate or `--platform` ran after this failure. Neither
`--all` nor `--full` ran. The final stop instruction was applied instead of
the earlier generic worktree fallback. No other lane was messaged, resumed,
or edited. Both shell sessions started by this lane have exited.

## Live observations

- MCP `eval_clj` with only the required `code: "(+ 1 1)"` argument selected
  default and returned value 2 in 3 ms, cluster-state alive.
- `bin/seon status` exited 1 naming
  `data/store/237ada66-c145-594e-a86e-322e2e79f065.ksv.c6909a50-960d-4ab1-84a1-eba03abadc3f.new`.
  Cause was not diagnosed; this is not a healthy-status claim.
- HTTP `http://127.0.0.1:7994` returned zero bytes and no HTTP status before
  curl's 20-second backstop (20.006298 s, exit 28). Browser paint is unverified.
- No live runner held the lane's test root after exit, as checked in the
  process table. Compact error evidence was preserved before removing that
  disposable root. No worktree or background shell remains from this lane.

## Dated inventory dispositions

This is a point-in-time inventory, not a second execution schedule. The owner
index remains unchanged. Known candidate aliases not present in this top-level
inventory were not independently searched in the archive before the stop.

| Note | Disposition at stop |
|---|---|
| [a-blocking-realization-is-not-bounded-by-the-interrupt](../../../seon/issues/a-blocking-realization-is-not-bounded-by-the-interrupt.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [a-failed-turn-wakes-itself-through-its-own-fault-message](../../../seon/issues/a-failed-turn-wakes-itself-through-its-own-fault-message.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [a-live-cluster-arms-ten-fewer-contracts-than-it-declares](../../../seon/issues/a-live-cluster-arms-ten-fewer-contracts-than-it-declares.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [a-mid-stream-provider-disconnect-discards-the-whole-turn](../../../seon/issues/a-mid-stream-provider-disconnect-discards-the-whole-turn.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [a-missing-value-loses-the-class-it-could-not-serialize](../../../seon/issues/a-missing-value-loses-the-class-it-could-not-serialize.md) | Read completely; §15 supersedes the proposed serialization marker. Left open because the mandatory gate stop preceded archival. |
| [a-new-core-predicate-and-its-schema-cannot-be-adopted-in-place](../../../seon/issues/a-new-core-predicate-and-its-schema-cannot-be-adopted-in-place.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [a-platform-test-leaves-its-worker-stripped-of-every-contract](../../../seon/issues/a-platform-test-leaves-its-worker-stripped-of-every-contract.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [a-program-identity-row-pulls-nil](../../../seon/issues/a-program-identity-row-pulls-nil.md) | Read completely; needs a live writer/population reproduction. Not reproduced or changed. |
| [a-render-exception-stops-the-cluster-render-proc-and-every-page-hangs](../../../seon/issues/a-render-exception-stops-the-cluster-render-proc-and-every-page-hangs.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [a-run-history-entry-can-name-a-different-run-than-its-form-pulled](../../../seon/issues/a-run-history-entry-can-name-a-different-run-than-its-form-pulled.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [a-runs-last-form-can-close-without-a-receipt](../../../seon/issues/a-runs-last-form-can-close-without-a-receipt.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [a-schema-resource-edit-bricks-value-admission-in-every-running-cluster](../../../seon/issues/a-schema-resource-edit-bricks-value-admission-in-every-running-cluster.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [a-search-contract-predicate-cannot-be-made-durable](../../../seon/issues/a-search-contract-predicate-cannot-be-made-durable.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [a-six-word-eval-error-renders-as-two-thousand-characters](../../../seon/issues/a-six-word-eval-error-renders-as-two-thousand-characters.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [a-stored-result-blob-has-no-reader-at-the-render-boundary](../../../seon/issues/a-stored-result-blob-has-no-reader-at-the-render-boundary.md) | Read completely; §15 deletes result blobs. Left open because the mandatory gate stop preceded archival. |
| [a-throwable-fault-keeps-no-inline-evidence-at-any-plausible-bound](../../../seon/issues/a-throwable-fault-keeps-no-inline-evidence-at-any-plausible-bound.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [a-turn-that-dies-before-replying-still-answers-its-wakes](../../../seon/issues/a-turn-that-dies-before-replying-still-answers-its-wakes.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [activation-closure-records-no-schema-keys](../../../seon/issues/activation-closure-records-no-schema-keys.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [admit-inst-overlap-prefers-collection-shape](../../../seon/issues/admit-inst-overlap-prefers-collection-shape.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [agent-flow-fixture-omits-render-interest](../../../seon/issues/agent-flow-fixture-omits-render-interest.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [agent-form-calls-to-core-namespaces-are-not-indexed](../../../seon/issues/agent-form-calls-to-core-namespaces-are-not-indexed.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [agent-html-still-uses-the-retired-transcript-assembler](../../../seon/issues/agent-html-still-uses-the-retired-transcript-assembler.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [agent-pages-overflow-a-phone-viewport](../../../seon/issues/agent-pages-overflow-a-phone-viewport.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [agent-repl-cannot-require-clojure-pprint](../../../seon/issues/agent-repl-cannot-require-clojure-pprint.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [ai-context-bypasses-render-proc-retained-bytes](../../../seon/issues/ai-context-bypasses-render-proc-retained-bytes.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [an-entity-pull-returns-a-sentence-instead-of-its-attributes](../../../seon/issues/an-entity-pull-returns-a-sentence-instead-of-its-attributes.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [an-inbox-entry-carries-a-nil-for-content-history-deliberately-discards](../../../seon/issues/an-inbox-entry-carries-a-nil-for-content-history-deliberately-discards.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [an-unmatched-print-face-throws-no-matching-clause-and-names-nothing](../../../seon/issues/an-unmatched-print-face-throws-no-matching-clause-and-names-nothing.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [anonymous-runtime-contracts-have-recurred](../../../seon/issues/anonymous-runtime-contracts-have-recurred.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [artifact-releases-the-fence-between-install-and-start](../../../seon/issues/artifact-releases-the-fence-between-install-and-start.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [background-binary-settlement-does-not-publish-required-event](../../../seon/issues/background-binary-settlement-does-not-publish-required-event.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [background-result-wakes-have-no-run-trigger](../../../seon/issues/background-result-wakes-have-no-run-trigger.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [bin-test-shared-base-compiles-other-lanes-half-edits](../../../seon/issues/bin-test-shared-base-compiles-other-lanes-half-edits.md) | Read completely; gate reproduced this class. Protected runner; left open. |
| [blob-get-assumes-file-store-callback-shape](../../../seon/issues/blob-get-assumes-file-store-callback-shape.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [boot-refusal-has-no-render-producer](../../../seon/issues/boot-refusal-has-no-render-producer.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [bootstrap-o4-stops-before-causal-delegation-settles](../../../seon/issues/bootstrap-o4-stops-before-causal-delegation-settles.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [bulk-tier-coordinator-wedges-on-a-worker-that-never-answers](../../../seon/issues/bulk-tier-coordinator-wedges-on-a-worker-that-never-answers.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [candidate-context-shares-parent-program-metadata](../../../seon/issues/candidate-context-shares-parent-program-metadata.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [changed-test-report-is-one-enormous-line](../../../seon/issues/changed-test-report-is-one-enormous-line.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [changed-test-selector-classifies-hosts-by-path-prefix](../../../seon/issues/changed-test-selector-classifies-hosts-by-path-prefix.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [class-accepted-work-can-end-without-terminal-evidence](../../../seon/issues/class-accepted-work-can-end-without-terminal-evidence.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [class-anonymous-contracts-cannot-survive-publication](../../../seon/issues/class-anonymous-contracts-cannot-survive-publication.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [class-classification-is-inferred-from-hand-lists](../../../seon/issues/class-classification-is-inferred-from-hand-lists.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [class-dependency-representations-leak-past-boundaries](../../../seon/issues/class-dependency-representations-leak-past-boundaries.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [class-destructive-reachability-changes-are-not-atomic](../../../seon/issues/class-destructive-reachability-changes-are-not-atomic.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [class-documentation-restates-executable-contracts](../../../seon/issues/class-documentation-restates-executable-contracts.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [class-domain-order-falls-through-to-strings-and-hashes](../../../seon/issues/class-domain-order-falls-through-to-strings-and-hashes.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [class-loaded-artifacts-lack-source-identity](../../../seon/issues/class-loaded-artifacts-lack-source-identity.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [class-local-updates-recompute-global-projections](../../../seon/issues/class-local-updates-recompute-global-projections.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [class-mutable-resources-lack-explicit-root-and-lifetime](../../../seon/issues/class-mutable-resources-lack-explicit-root-and-lifetime.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [class-outward-values-bypass-total-render-contract](../../../seon/issues/class-outward-values-bypass-total-render-contract.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [class-proofs-pass-without-exercising-their-premise](../../../seon/issues/class-proofs-pass-without-exercising-their-premise.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [class-readerless-duplicate-mechanisms-survive-cuts](../../../seon/issues/class-readerless-duplicate-mechanisms-survive-cuts.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [cluster-boot-instruments-in-flight-working-tree-vars](../../../seon/issues/cluster-boot-instruments-in-flight-working-tree-vars.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [cluster-ctx-delegating-arities-refused-under-instrumentation](../../../seon/issues/cluster-ctx-delegating-arities-refused-under-instrumentation.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [cluster-named-store-collides-with-the-store-directory](../../../seon/issues/cluster-named-store-collides-with-the-store-directory.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [cluster-toolkit-stores-a-prefix-derived-projection](../../../seon/issues/cluster-toolkit-stores-a-prefix-derived-projection.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [cohosted-clusters-share-one-unbounded-agent-heap](../../../seon/issues/cohosted-clusters-share-one-unbounded-agent-heap.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [cohosted-second-boot-is-slow-and-trips-the-silence-backstop](../../../seon/issues/cohosted-second-boot-is-slow-and-trips-the-silence-backstop.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [complete-publication-takes-seventy-seconds](../../../seon/issues/complete-publication-takes-seventy-seconds.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [concurrent-eval-test-calibrates-interpreted-work-to-wall-time](../../../seon/issues/concurrent-eval-test-calibrates-interpreted-work-to-wall-time.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [config-ai-request-idents-are-derived-by-string-surgery](../../../seon/issues/config-ai-request-idents-are-derived-by-string-surgery.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [config-dial-discovery-has-three-authorities](../../../seon/issues/config-dial-discovery-has-three-authorities.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [confirmation-parallel-failure-blocks-reading-worker-protocol](../../../seon/issues/confirmation-parallel-failure-blocks-reading-worker-protocol.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [context-capture-prompts-bypass-the-blob-splitter](../../../seon/issues/context-capture-prompts-bypass-the-blob-splitter.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [context-mvp-drive-can-false-green-after-cross-agent-delivery](../../../seon/issues/context-mvp-drive-can-false-green-after-cross-agent-delivery.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [context-wave-leaves-three-small-honesty-defects](../../../seon/issues/context-wave-leaves-three-small-honesty-defects.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [contract-evidence-carries-the-offending-argument-twice](../../../seon/issues/contract-evidence-carries-the-offending-argument-twice.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [core-namespace-pages-spend-seven-seconds-without-declaration-fallbacks](../../../seon/issues/core-namespace-pages-spend-seven-seconds-without-declaration-fallbacks.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [data-page-takes-five-and-a-half-seconds-for-three-kilobytes](../../../seon/issues/data-page-takes-five-and-a-half-seconds-for-three-kilobytes.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [database-value-shape-name-duplicates-the-db-key](../../../seon/issues/database-value-shape-name-duplicates-the-db-key.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [database-values-render-as-opaque-host-objects-in-html](../../../seon/issues/database-values-render-as-opaque-host-objects-in-html.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [datahike-allocates-a-konserve-cache-it-never-reads](../../../seon/issues/datahike-allocates-a-konserve-cache-it-never-reads.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [datahike-fork-is-28-commits-behind-upstream](../../../seon/issues/datahike-fork-is-28-commits-behind-upstream.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [db-diff-render-bypasses-print-fit-and-has-no-html](../../../seon/issues/db-diff-render-bypasses-print-fit-and-has-no-html.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [db-test-still-expects-a-unique-agent-namespace](../../../seon/issues/db-test-still-expects-a-unique-agent-namespace.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [debug-html-render-carries-no-agent-scoped-environment](../../../seon/issues/debug-html-render-carries-no-agent-scoped-environment.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [debug-left-pane-is-not-the-exact-prompt](../../../seon/issues/debug-left-pane-is-not-the-exact-prompt.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [debug-pages-receive-block-patches-for-elements-they-do-not-have](../../../seon/issues/debug-pages-receive-block-patches-for-elements-they-do-not-have.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [debug-source-execution-invalidates-its-own-preview](../../../seon/issues/debug-source-execution-invalidates-its-own-preview.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [deletable-directories-have-no-claim-or-size-facts](../../../seon/issues/deletable-directories-have-no-claim-or-size-facts.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [dependency-class-cache-prepare-races-concurrent-jvm-launches](../../../seon/issues/dependency-class-cache-prepare-races-concurrent-jvm-launches.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [design-lab-prd-mixes-observation-with-unsettled-design](../../../seon/issues/design-lab-prd-mixes-observation-with-unsettled-design.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [dev-mcp-envelopes-misdirect-errors-and-sprawl-status](../../../seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [doc-contract-lines-print-schema-bodies-and-flatten-arity-alternatives](../../../seon/issues/doc-contract-lines-print-schema-bodies-and-flatten-arity-alternatives.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [drive-one-starts-without-required-plan-facts](../../../seon/issues/drive-one-starts-without-required-plan-facts.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [duplicate-identity-refusal-evidence-is-unordered](../../../seon/issues/duplicate-identity-refusal-evidence-is-unordered.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [dynamic-in-ns-cannot-persist-definition-namespace](../../../seon/issues/dynamic-in-ns-cannot-persist-definition-namespace.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [edit-hook-kondo-false-positives-on-seon-db-dynamic-vars](../../../seon/issues/edit-hook-kondo-false-positives-on-seon-db-dynamic-vars.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [edit-hook-lints-deleted-path-during-issue-archive-rename](../../../seon/issues/edit-hook-lints-deleted-path-during-issue-archive-rename.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [effect-context-suffix-returns-comment-notices](../../../seon/issues/effect-context-suffix-returns-comment-notices.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [effect-feedback-orders-receipts-by-id](../../../seon/issues/effect-feedback-orders-receipts-by-id.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [effect-receipts-have-no-render-producers](../../../seon/issues/effect-receipts-have-no-render-producers.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [error-class-catalog-and-renderers-disagree](../../../seon/issues/error-class-catalog-and-renderers-disagree.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [eval-drives-duplicate-a-four-minute-run-clock](../../../seon/issues/eval-drives-duplicate-a-four-minute-run-clock.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [eval-samples-cost-42mb-of-store-each](../../../seon/issues/eval-samples-cost-42mb-of-store-each.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [expected-refusal-logs-raw-datom-error-twice](../../../seon/issues/expected-refusal-logs-raw-datom-error-twice.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [failover-adds-an-uncaptured-system-context-fragment](../../../seon/issues/failover-adds-an-uncaptured-system-context-fragment.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [fault-facts-store-megabyte-evidence-inline-and-rewrite-gigabyte-leaves](../../../seon/issues/fault-facts-store-megabyte-evidence-inline-and-rewrite-gigabyte-leaves.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [file-store-commits-pay-five-times-the-fsyncs-they-need](../../../seon/issues/file-store-commits-pay-five-times-the-fsyncs-they-need.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [fit-splits-fenced-projected-forms-by-character](../../../seon/issues/fit-splits-fenced-projected-forms-by-character.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [flow-has-no-read-set-control-and-a-hand-rolled-egress](../../../seon/issues/flow-has-no-read-set-control-and-a-hand-rolled-egress.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [flow-monitor-test-resources-outlive-their-cleanup-scope](../../../seon/issues/flow-monitor-test-resources-outlive-their-cleanup-scope.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [flow-work-launcher-graph-omits-its-root-io-executor](../../../seon/issues/flow-work-launcher-graph-omits-its-root-io-executor.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [foreign-write-fence-reads-only-the-dynamic-var](../../../seon/issues/foreign-write-fence-reads-only-the-dynamic-var.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [fresh-cljc-files-are-jvm-only](../../../seon/issues/fresh-cljc-files-are-jvm-only.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [function-install-case-count-is-read-from-an-absent-handle-key](../../../seon/issues/function-install-case-count-is-read-from-an-absent-handle-key.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [generated-opening-live-pull-does-not-return-after-help](../../../seon/issues/generated-opening-live-pull-does-not-return-after-help.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [generated-turn-fork-omits-the-agent-scoped-environment](../../../seon/issues/generated-turn-fork-omits-the-agent-scoped-environment.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [generated-turn-omits-the-required-render-output](../../../seon/issues/generated-turn-omits-the-required-render-output.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [generation-dependency-analysis-ignores-keywords](../../../seon/issues/generation-dependency-analysis-ignores-keywords.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [give-offline-roster-discovery-a-current-read-only-helper](../../../seon/issues/give-offline-roster-discovery-a-current-read-only-helper.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [history-policy-refusal-test-is-load-flaky](../../../seon/issues/history-policy-refusal-test-is-load-flaky.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [host-bound-first-party-vars-break-in-value-position](../../../seon/issues/host-bound-first-party-vars-break-in-value-position.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [hyperlith-pin-behind-lockstep-rework](../../../seon/issues/hyperlith-pin-behind-lockstep-rework.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [indexed-core-function-can-be-falsely-marked-installed](../../../seon/issues/indexed-core-function-can-be-falsely-marked-installed.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [init-failure-dumps-entire-prepl-event-history](../../../seon/issues/init-failure-dumps-entire-prepl-event-history.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [init-red-indexed-test-reaches-script-side-markdown](../../../seon/issues/init-red-indexed-test-reaches-script-side-markdown.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [initial-paint-census-is-a-hand-maintained-count](../../../seon/issues/initial-paint-census-is-a-hand-maintained-count.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [instrumentation-compiles-under-one-clusters-projection](../../../seon/issues/instrumentation-compiles-under-one-clusters-projection.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [instrumentation-headline-unbounded-when-caps-absent](../../../seon/issues/instrumentation-headline-unbounded-when-caps-absent.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [interrupted-blob-staging-leaves-no-observable-artifact](../../../seon/issues/interrupted-blob-staging-leaves-no-observable-artifact.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [keep-history-is-on-by-default-without-a-decision](../../../seon/issues/keep-history-is-on-by-default-without-a-decision.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [latest-closed-run-orders-by-id-string](../../../seon/issues/latest-closed-run-orders-by-id-string.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [live-publication-has-a-hand-maintained-predicate-owner-reload](../../../seon/issues/live-publication-has-a-hand-maintained-predicate-owner-reload.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [live-root-pull-of-189-members-takes-24-seconds](../../../seon/issues/live-root-pull-of-189-members-takes-24-seconds.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [maintenance-message-ids-embed-printed-edn](../../../seon/issues/maintenance-message-ids-embed-printed-edn.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [malli-vendor-is-ahead-of-pinned-dependency](../../../seon/issues/malli-vendor-is-ahead-of-pinned-dependency.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [map-unions-have-no-explicit-discriminants](../../../seon/issues/map-unions-have-no-explicit-discriminants.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [mcp-parent-watchdog-can-follow-a-reused-pid](../../../seon/issues/mcp-parent-watchdog-can-follow-a-reused-pid.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [mcp-projection-crashes-on-non-keyword-map-keys](../../../seon/issues/mcp-projection-crashes-on-non-keyword-map-keys.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [mcp-runtime-status-lists-no-clusters-for-an-explicit-root](../../../seon/issues/mcp-runtime-status-lists-no-clusters-for-an-explicit-root.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [message-completion-replies-from-the-wrong-agent-and-duplicates-the-trigger](../../../seon/issues/message-completion-replies-from-the-wrong-agent-and-duplicates-the-trigger.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [monitor-graph-command-proc-throws](../../../seon/issues/monitor-graph-command-proc-throws.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [my-background-poll-costs-290-tokens-per-polled-result](../../../seon/issues/my-background-poll-costs-290-tokens-per-polled-result.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [my-fs-write-docstring-hides-its-own-request-shape](../../../seon/issues/my-fs-write-docstring-hides-its-own-request-shape.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [namespace-binding-targets-are-symbols-not-refs](../../../seon/issues/namespace-binding-targets-are-symbols-not-refs.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [namespace-layout-confines-most-content-to-scroll-boxes](../../../seon/issues/namespace-layout-confines-most-content-to-scroll-boxes.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [namespace-page-first-byte-exceeds-ten-seconds](../../../seon/issues/namespace-page-first-byte-exceeds-ten-seconds.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [namespace-page-repeats-renderer-unavailable](../../../seon/issues/namespace-page-repeats-renderer-unavailable.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [namespace-removal-does-not-rebuild-contracted-only](../../../seon/issues/namespace-removal-does-not-rebuild-contracted-only.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [namespace-require-summary-renders-nil-identities](../../../seon/issues/namespace-require-summary-renders-nil-identities.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [negative-import-masks-escape-static-admission](../../../seon/issues/negative-import-masks-escape-static-admission.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [no-forms-replies-close-without-correction-or-rewake](../../../seon/issues/no-forms-replies-close-without-correction-or-rewake.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [observable-graph-transitions-are-polled-in-tests](../../../seon/issues/observable-graph-transitions-are-polled-in-tests.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [one-elision-has-two-representations-in-one-context](../../../seon/issues/one-elision-has-two-representations-in-one-context.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [one-identity-string-names-two-entities](../../../seon/issues/one-identity-string-names-two-entities.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [opaque-contract-generators-share-live-process-objects](../../../seon/issues/opaque-contract-generators-share-live-process-objects.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [opening-generator-pushes-undemanded-candidates](../../../seon/issues/opening-generator-pushes-undemanded-candidates.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [opening-walkthrough-replicates-a-usage-test](../../../seon/issues/opening-walkthrough-replicates-a-usage-test.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [operator-classifies-processes-by-command-substrings](../../../seon/issues/operator-classifies-processes-by-command-substrings.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [operator-root-inference-guesses-from-directory-names](../../../seon/issues/operator-root-inference-guesses-from-directory-names.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [operator-status-dumps-every-absent-test-result](../../../seon/issues/operator-status-dumps-every-absent-test-result.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [operator-status-refuses-foreign-live-root](../../../seon/issues/operator-status-refuses-foreign-live-root.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [orderly-stop-completion-joins-have-no-bound](../../../seon/issues/orderly-stop-completion-joins-have-no-bound.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [output-sink-query-excludes-operator-and-mcp-scripts](../../../seon/issues/output-sink-query-excludes-operator-and-mcp-scripts.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [oversight-treats-a-20ms-ping-absence-as-state](../../../seon/issues/oversight-treats-a-20ms-ping-absence-as-state.md) | Read completely; runtime/render ownership needs verification. Not changed. |
| [parallel-test-stress-exposes-eleven-isolation-sensitive-tests](../../../seon/issues/parallel-test-stress-exposes-eleven-isolation-sensitive-tests.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [partial-hot-reload-produces-mixed-code-with-no-warning](../../../seon/issues/partial-hot-reload-produces-mixed-code-with-no-warning.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [plan-renderer-arity-change-blocks-development-publication](../../../seon/issues/plan-renderer-arity-change-blocks-development-publication.md) | Read completely; reproduced in bare gate at four caller locations. Protected my.plan; evidence appended. |
| [posh-cardinality-one-pull-analysis-has-an-arity-defect](../../../seon/issues/posh-cardinality-one-pull-analysis-has-an-arity-defect.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [pre-rename-root-claims-are-unreadable-noise-on-every-status](../../../seon/issues/pre-rename-root-claims-are-unreadable-noise-on-every-status.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [production-docstrings-teach-deleted-semantics](../../../seon/issues/production-docstrings-teach-deleted-semantics.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [prose-renders-splice-unquoted-into-printed-data](../../../seon/issues/prose-renders-splice-unquoted-into-printed-data.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [provider-output-token-wire-key-is-hard-coded](../../../seon/issues/provider-output-token-wire-key-is-hard-coded.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [publication-reload-hand-lists-namespaces-and-misses-dependencies](../../../seon/issues/publication-reload-hand-lists-namespaces-and-misses-dependencies.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [ranged-store-collection-can-delete-live-segments-via-branch-resurrection](../../../seon/issues/ranged-store-collection-can-delete-live-segments-via-branch-resurrection.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [reap-dead-roots-calls-delete-recursively-with-a-nil-path](../../../seon/issues/reap-dead-roots-calls-delete-recursively-with-a-nil-path.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [recursive-delete-throws-when-entries-vanish-mid-walk](../../../seon/issues/recursive-delete-throws-when-entries-vanish-mid-walk.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [registered-render-producers-fall-through-to-generic-map-rendering](../../../seon/issues/registered-render-producers-fall-through-to-generic-map-rendering.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [render-adversarial-roots-outlive-their-experiment](../../../seon/issues/render-adversarial-roots-outlive-their-experiment.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [render-candidate-checks-mix-different-arities](../../../seon/issues/render-candidate-checks-mix-different-arities.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [render-history-serializes-unexecuted-form-projections](../../../seon/issues/render-history-serializes-unexecuted-form-projections.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [render-live-proof-roots-have-no-lifecycle-owner](../../../seon/issues/render-live-proof-roots-have-no-lifecycle-owner.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [render-package-proc-reruns-unchanged-renderers](../../../seon/issues/render-package-proc-reruns-unchanged-renderers.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [render-runtime-revision-overwrites-its-atom](../../../seon/issues/render-runtime-revision-overwrites-its-atom.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [render-selection-loses-the-viewing-namespace](../../../seon/issues/render-selection-loses-the-viewing-namespace.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [render-token-budgets-are-private-dials-no-producer-supplies](../../../seon/issues/render-token-budgets-are-private-dials-no-producer-supplies.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [render-walk-maintains-a-derived-edge-hand-list](../../../seon/issues/render-walk-maintains-a-derived-edge-hand-list.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [render-wave-properties-cannot-produce-their-failing-cases](../../../seon/issues/render-wave-properties-cannot-produce-their-failing-cases.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [repl-parity-divergences](../../../seon/issues/repl-parity-divergences.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [repl-skill-omits-required-instrumentation-request](../../../seon/issues/repl-skill-omits-required-instrumentation-request.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [reset-deletes-a-bloated-store-one-lstat-at-a-time](../../../seon/issues/reset-deletes-a-bloated-store-one-lstat-at-a-time.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [retained-render-packages-survive-producer-replacement](../../../seon/issues/retained-render-packages-survive-producer-replacement.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [retired-form-projection-still-declared-and-selected](../../../seon/issues/retired-form-projection-still-declared-and-selected.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [root-compute-executor-has-no-per-cluster-fairness](../../../seon/issues/root-compute-executor-has-no-per-cluster-fairness.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [root-maintenance-context-exceeds-provider-budget](../../../seon/issues/root-maintenance-context-exceeds-provider-budget.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [root-turns-on-a-fresh-dev-cluster-hit-the-context-acquisition-backstop](../../../seon/issues/root-turns-on-a-fresh-dev-cluster-hit-the-context-acquisition-backstop.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [run-renderer-narrates-forms-and-receipts](../../../seon/issues/run-renderer-narrates-forms-and-receipts.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [runtime-lint-does-not-resolve-namespace-aliases](../../../seon/issues/runtime-lint-does-not-resolve-namespace-aliases.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [runtime-turn-and-evaluate-kernels-conflate-boundaries](../../../seon/issues/runtime-turn-and-evaluate-kernels-conflate-boundaries.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [schedule-graph-test-constructs-a-handle-without-an-environment](../../../seon/issues/schedule-graph-test-constructs-a-handle-without-an-environment.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [schema-declaration-rebuilds-four-gigabytes-per-form](../../../seon/issues/schema-declaration-rebuilds-four-gigabytes-per-form.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [schema-environment-is-ambient-not-explicit](../../../seon/issues/schema-environment-is-ambient-not-explicit.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [schema-guard-refuses-accretive-loosenings-with-data](../../../seon/issues/schema-guard-refuses-accretive-loosenings-with-data.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [schema-map-extraction-still-depends-on-position-two](../../../seon/issues/schema-map-extraction-still-depends-on-position-two.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [schema-population-retains-five-readerless-rows](../../../seon/issues/schema-population-retains-five-readerless-rows.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [schema-source-provenance-accumulates-in-a-global-atom](../../../seon/issues/schema-source-provenance-accumulates-in-a-global-atom.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [sci-base-context-silently-hand-lists-special-callables](../../../seon/issues/sci-base-context-silently-hand-lists-special-callables.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [search-index-property-collides-with-process-index-id](../../../seon/issues/search-index-property-collides-with-process-index-id.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [secondary-only-attributes-have-no-covering-index](../../../seon/issues/secondary-only-attributes-have-no-covering-index.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [seon-db-has-no-branch-or-commit-reads](../../../seon/issues/seon-db-has-no-branch-or-commit-reads.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed](../../../seon/issues/seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [settle-is-public-without-a-complete-contract](../../../seon/issues/settle-is-public-without-a-complete-contract.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [shared-context-session-delta-crosses-run-attribution](../../../seon/issues/shared-context-session-delta-crosses-run-attribution.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [shared-store-grows-during-render-source-work](../../../seon/issues/shared-store-grows-during-render-source-work.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [source-load-is-118s-against-the-ten-second-law](../../../seon/issues/source-load-is-118s-against-the-ten-second-law.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [stale-dev-dependency-cache-serves-wrong-classes-silently](../../../seon/issues/stale-dev-dependency-cache-serves-wrong-classes-silently.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [stale-language-specific-kondo-cache-blocks-correct-code](../../../seon/issues/stale-language-specific-kondo-cache-blocks-correct-code.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [stale-operator-jvm-refuses-every-changed-publication](../../../seon/issues/stale-operator-jvm-refuses-every-changed-publication.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing](../../../seon/issues/storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [store-grew-to-69-gigabytes-in-one-day-of-lanes](../../../seon/issues/store-grew-to-69-gigabytes-in-one-day-of-lanes.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [system-generated-messages-omit-arrival-ordinals](../../../seon/issues/system-generated-messages-omit-arrival-ordinals.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [terminal-refusal-error-fact-fails-on-oversized-data](../../../seon/issues/terminal-refusal-error-fact-fails-on-oversized-data.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [test-runner-empty-snapshot-paths-refuses-the-default-gate](../../../seon/issues/test-runner-empty-snapshot-paths-refuses-the-default-gate.md) | Read completely; this invocation passed that boundary. Protected runner; not closed on one observation. |
| [the-agent-page-shows-a-run-as-one-sentence-and-never-its-forms](../../../seon/issues/the-agent-page-shows-a-run-as-one-sentence-and-never-its-forms.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [the-data-route-has-no-presentation-bound-for-a-string](../../../seon/issues/the-data-route-has-no-presentation-bound-for-a-string.md) | Read completely; /data owner is protected seon.render.web. Left open. |
| [the-database-identity-face-cannot-satisfy-its-own-declared-input](../../../seon/issues/the-database-identity-face-cannot-satisfy-its-own-declared-input.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [the-debug-ai-pane-never-wraps](../../../seon/issues/the-debug-ai-pane-never-wraps.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [the-value-floors-map-face-is-not-readable-edn](../../../seon/issues/the-value-floors-map-face-is-not-readable-edn.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [thinking-tool-continuations-have-no-faithful-request-shape](../../../seon/issues/thinking-tool-continuations-have-no-faithful-request-shape.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [time-limit-face-exposes-interpreter-interrupt-marker](../../../seon/issues/time-limit-face-exposes-interpreter-interrupt-marker.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [transaction-refusal-wrapper-hides-agent-running-rule](../../../seon/issues/transaction-refusal-wrapper-hides-agent-running-rule.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [transcript-about-lookup-passes-a-set-to-pull-many](../../../seon/issues/transcript-about-lookup-passes-a-set-to-pull-many.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [transcript-candidate-window-orders-receipts-and-comments-by-id](../../../seon/issues/transcript-candidate-window-orders-receipts-and-comments-by-id.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [transcript-renderer-encodes-entries-as-comment-forms](../../../seon/issues/transcript-renderer-encodes-entries-as-comment-forms.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [turn-fork-omits-an-empty-agent-namespace](../../../seon/issues/turn-fork-omits-an-empty-agent-namespace.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [two-turn-backstops-fire-and-the-sliding-fault-channel-keeps-the-wrong-one](../../../seon/issues/two-turn-backstops-fire-and-the-sliding-fault-channel-keeps-the-wrong-one.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [uncontracted-live-function-blocks-development-acquisition](../../../seon/issues/uncontracted-live-function-blocks-development-acquisition.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [unlogged-findings-2026-08-01](../../../seon/issues/unlogged-findings-2026-08-01.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [unowned-namespace-oversight-still-inverts-assignment](../../../seon/issues/unowned-namespace-oversight-still-inverts-assignment.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [value-admission-resolves-the-declaration-population-per-node](../../../seon/issues/value-admission-resolves-the-declaration-population-per-node.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [value-floor-residue-duplicate-cursors-and-marker-hand-lists](../../../seon/issues/value-floor-residue-duplicate-cursors-and-marker-hand-lists.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [vendor-parinferish-under-reference-code](../../../seon/issues/vendor-parinferish-under-reference-code.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [vendored-babashka-process-carries-a-local-aot-patch](../../../seon/issues/vendored-babashka-process-carries-a-local-aot-patch.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [vendored-transit-clj-drifts-from-the-pinned-artifact](../../../seon/issues/vendored-transit-clj-drifts-from-the-pinned-artifact.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [walk-units-render-their-hiccup-as-escaped-edn-text](../../../seon/issues/walk-units-render-their-hiccup-as-escaped-edn-text.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
| [within-run-schema-key-refinement-needs-an-owner-ruling](../../../seon/issues/within-run-schema-key-refinement-needs-an-owner-ruling.md) | Unreviewed or incomplete body read; unchanged after mandatory gate stop. |
