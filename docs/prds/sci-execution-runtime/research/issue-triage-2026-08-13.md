---
type: research
status: complete
tags: [research, issue, architecture]
---

# Open-issue triage — 2026-08-13

## Method and scope

I read both authorities end to end before triaging: the issue-campaign plan at
the close of
[the working edge](docs/prds/sci-execution-runtime/plan/unsettled.md) and
[the issue class mining report](docs/prds/sci-execution-runtime/research/issue-class-mining-2026-08-11.md),
plus the current dependency spine in
[the program plan](docs/prds/sci-execution-runtime/plan/README.md).

I then read the title and Problem section of all **210** open notes under
`docs/seon/issues/` (`archive/` excluded as closed history), read the ambiguous
ones in full, and spot-checked cited evidence against source at HEAD
(`de166c9a7`). Two systematic checks drove the staleness sweep:

1. every first-party path cited by every note was tested for existence, and
2. the mechanism named by each high-severity note was grepped at its owner.

Counts: **210 open** — 56 blockers, 137 friction, 17 cleanup.
Classification: **139 CURRENT**, **29 LIKELY-STALE**, **42 UNVERIFIED**.

I wrote no file but this one and edited no issue note or index.

### A correction to the mining report's member counts

The class issues' member lists have drifted since 2026-08-11 because members
were archived without the class note being reconciled:

| Class | Listed open members | Actually still open |
|---|---:|---:|
| [class-diagnostics-collapse-evidence-into-noise-or-absence](docs/seon/issues/class-diagnostics-collapse-evidence-into-noise-or-absence.md) (N5) | 11 | 5 |
| [class-outward-values-bypass-total-render-contract](docs/seon/issues/class-outward-values-bypass-total-render-contract.md) (N1) | 34 | 32 |
| every other class | as listed | unchanged |

N5 is more than half closed already: `a-wrong-arity-call-reports-a-missing-namespace`,
`malli-registration-errors-hide-the-offending-var`,
`nested-error-data-hides-the-throw-site-message`,
`predicate-schema-violations-humanize-to-unknown-error`,
`sci-analysis-ex-data-carries-a-symbol-nothing-reads`, and
`status-reports-a-live-mcp-proven-prepl-unreachable` are all in `archive/`.
N1 lost `collection-render-drops-209-of-210-results-without-an-elision-value`
and `object-identity-addresses-break-prompt-prefix-stability`. Closing a class
issue requires reconciling its member list first; it is a hand list today and
it is already wrong.

**P4 (tap after source/resume) appears structurally dead.** Both members are
LIKELY-STALE: `seon.flow/start-graph!` (`src/seon/flow.clj:62-85`) runs declared
`::joins` between `flow/start` and `flow/resume`, and the cluster graph installs
its error fanout through exactly that seam (`src/seon/cluster.clj:2231-2243`).
A caller can no longer construct the unsafe order. One verification pass should
close the class.

## (a) Staleness ledger

Every open issue. `CURRENT` = evidence still matches source. `LIKELY-STALE` =
named contradicting evidence. `UNVERIFIED` = evidence is a live measurement,
probe, or drive observation with no first-party source anchor cheap to check.
Issues owned by the four running lanes are marked in the reason where relevant.

| Issue | Severity | Status | Reason |
|---|---|---|---|
| [a-failed-turn-wakes-itself-through-its-own-fault-message](docs/seon/issues/a-failed-turn-wakes-itself-through-its-own-fault-message.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [a-mid-stream-provider-disconnect-discards-the-whole-turn](docs/seon/issues/a-mid-stream-provider-disconnect-discards-the-whole-turn.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [a-never-run-agents-context-cannot-be-inspected](docs/seon/issues/a-never-run-agents-context-cannot-be-inspected.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [a-reasoning-only-stream-burns-the-whole-time-limit](docs/seon/issues/a-reasoning-only-stream-burns-the-whole-time-limit.md) | blocker | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [a-run-pays-two-and-a-half-seconds-between-every-form](docs/seon/issues/a-run-pays-two-and-a-half-seconds-between-every-form.md) | blocker | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [a-runs-last-form-can-close-without-a-receipt](docs/seon/issues/a-runs-last-form-can-close-without-a-receipt.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [a-schema-resource-edit-bricks-value-admission-in-every-running-cluster](docs/seon/issues/a-schema-resource-edit-bricks-value-admission-in-every-running-cluster.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [a-search-contract-predicate-cannot-be-made-durable](docs/seon/issues/a-search-contract-predicate-cannot-be-made-durable.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [a-six-word-eval-error-renders-as-two-thousand-characters](docs/seon/issues/a-six-word-eval-error-renders-as-two-thousand-characters.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [acquire-has-no-per-row-containment](docs/seon/issues/acquire-has-no-per-row-containment.md) | blocker | CURRENT | `install-row` in `src/seon/sci/eval.clj:1341-1352` calls `install-row!` inside a bare `reduce` with no per-row containment; one failing agent-authored row still throws out of `acquire!`. |
| [activation-closure-records-no-schema-keys](docs/seon/issues/activation-closure-records-no-schema-keys.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [admit-inst-overlap-prefers-collection-shape](docs/seon/issues/admit-inst-overlap-prefers-collection-shape.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [agent-flow-fixture-omits-render-interest](docs/seon/issues/agent-flow-fixture-omits-render-interest.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [agent-form-calls-to-core-namespaces-are-not-indexed](docs/seon/issues/agent-form-calls-to-core-namespaces-are-not-indexed.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [agent-html-still-uses-the-retired-transcript-assembler](docs/seon/issues/agent-html-still-uses-the-retired-transcript-assembler.md) | blocker | CURRENT | `resources/seon/schemas/seon.cluster.agent.edn:7` still names `seon.render.transcript/render-session-html`. |
| [agent-pages-overflow-a-phone-viewport](docs/seon/issues/agent-pages-overflow-a-phone-viewport.md) | cleanup | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [agent-plan-has-no-declared-database-relationship](docs/seon/issues/agent-plan-has-no-declared-database-relationship.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [agent-repl-cannot-require-clojure-pprint](docs/seon/issues/agent-repl-cannot-require-clojure-pprint.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [ai-context-bypasses-render-proc-retained-bytes](docs/seon/issues/ai-context-bypasses-render-proc-retained-bytes.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [ai-retry-proof-still-cites-the-deleted-run-lease](docs/seon/issues/ai-retry-proof-still-cites-the-deleted-run-lease.md) | friction | LIKELY-STALE | Anchored on the deleted `src/seon/ai.cljc`; re-derive against `src/seon/ai.clj`. |
| [ai-transport-taxonomy-test-can-run-zero-assertions](docs/seon/issues/ai-transport-taxonomy-test-can-run-zero-assertions.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [an-inline-fn-predicate-in-src-refuses-every-corpus-projection](docs/seon/issues/an-inline-fn-predicate-in-src-refuses-every-corpus-projection.md) | blocker | LIKELY-STALE | `seon.test.selection/basis-file` is now a plain `defn-` with no `:malli/schema` (`src/seon/test/selection.clj:190-196`). |
| [an-unmatched-print-face-throws-no-matching-clause-and-names-nothing](docs/seon/issues/an-unmatched-print-face-throws-no-matching-clause-and-names-nothing.md) | friction | CURRENT | `seon.sci.admit/semantic-value` (`src/seon/sci/admit.clj:388-426`) is still a `case` with no default arm. |
| [anonymous-runtime-contracts-have-recurred](docs/seon/issues/anonymous-runtime-contracts-have-recurred.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [artifact-releases-the-fence-between-install-and-start](docs/seon/issues/artifact-releases-the-fence-between-install-and-start.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [background-binary-settlement-does-not-publish-required-event](docs/seon/issues/background-binary-settlement-does-not-publish-required-event.md) | blocker | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [background-result-wakes-have-no-run-trigger](docs/seon/issues/background-result-wakes-have-no-run-trigger.md) | blocker | CURRENT | `src/seon/cluster/run.clj:404-424` still assocs `::background-results` with no `:seon.cluster.run/trigger`. |
| [blob-get-assumes-file-store-callback-shape](docs/seon/issues/blob-get-assumes-file-store-callback-shape.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [boot-refusal-has-no-render-producer](docs/seon/issues/boot-refusal-has-no-render-producer.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [bootstrap-o4-stops-before-causal-delegation-settles](docs/seon/issues/bootstrap-o4-stops-before-causal-delegation-settles.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [bootstrap-plan-forms-ship-unsubstituted-namespace-placeholders](docs/seon/issues/bootstrap-plan-forms-ship-unsubstituted-namespace-placeholders.md) | blocker | LIKELY-STALE | No authored plan EDN and no placeholder substitution survive in `src/seon/bootstrap.clj`. |
| [bootstrap-teaches-bare-map-keys](docs/seon/issues/bootstrap-teaches-bare-map-keys.md) | friction | LIKELY-STALE | Cites the deleted `resources/seon/bootstrap.edn`. |
| [bootstrap-teaching-failures-strand-every-new-agent](docs/seon/issues/bootstrap-teaching-failures-strand-every-new-agent.md) | blocker | LIKELY-STALE | Cites the deleted `resources/seon/bootstrap.edn`; the deliberate teaching failures no longer exist as authored rows. |
| [changed-test-report-is-one-enormous-line](docs/seon/issues/changed-test-report-is-one-enormous-line.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [changed-test-selector-classifies-hosts-by-path-prefix](docs/seon/issues/changed-test-selector-classifies-hosts-by-path-prefix.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [class-accepted-work-can-end-without-terminal-evidence](docs/seon/issues/class-accepted-work-can-end-without-terminal-evidence.md) | blocker | CURRENT | Class queue owner; its member list is the worklist and is re-counted in this report. |
| [class-anonymous-contracts-cannot-survive-publication](docs/seon/issues/class-anonymous-contracts-cannot-survive-publication.md) | friction | CURRENT | Class queue owner; its member list is the worklist and is re-counted in this report. |
| [class-classification-is-inferred-from-hand-lists](docs/seon/issues/class-classification-is-inferred-from-hand-lists.md) | friction | CURRENT | Class queue owner; its member list is the worklist and is re-counted in this report. |
| [class-dependency-representations-leak-past-boundaries](docs/seon/issues/class-dependency-representations-leak-past-boundaries.md) | friction | CURRENT | Class queue owner; its member list is the worklist and is re-counted in this report. |
| [class-destructive-reachability-changes-are-not-atomic](docs/seon/issues/class-destructive-reachability-changes-are-not-atomic.md) | blocker | CURRENT | Class queue owner; its member list is the worklist and is re-counted in this report. |
| [class-diagnostics-collapse-evidence-into-noise-or-absence](docs/seon/issues/class-diagnostics-collapse-evidence-into-noise-or-absence.md) | friction | CURRENT | Class queue owner; its member list is the worklist and is re-counted in this report. |
| [class-documentation-restates-executable-contracts](docs/seon/issues/class-documentation-restates-executable-contracts.md) | cleanup | CURRENT | Class queue owner; its member list is the worklist and is re-counted in this report. |
| [class-domain-order-falls-through-to-strings-and-hashes](docs/seon/issues/class-domain-order-falls-through-to-strings-and-hashes.md) | friction | CURRENT | Class queue owner; its member list is the worklist and is re-counted in this report. |
| [class-loaded-artifacts-lack-source-identity](docs/seon/issues/class-loaded-artifacts-lack-source-identity.md) | blocker | CURRENT | Class queue owner; its member list is the worklist and is re-counted in this report. |
| [class-local-updates-recompute-global-projections](docs/seon/issues/class-local-updates-recompute-global-projections.md) | friction | CURRENT | Class queue owner; its member list is the worklist and is re-counted in this report. |
| [class-mutable-resources-lack-explicit-root-and-lifetime](docs/seon/issues/class-mutable-resources-lack-explicit-root-and-lifetime.md) | blocker | CURRENT | Class queue owner; its member list is the worklist and is re-counted in this report. |
| [class-outward-values-bypass-total-render-contract](docs/seon/issues/class-outward-values-bypass-total-render-contract.md) | blocker | CURRENT | Class queue owner; its member list is the worklist and is re-counted in this report. |
| [class-proofs-pass-without-exercising-their-premise](docs/seon/issues/class-proofs-pass-without-exercising-their-premise.md) | friction | CURRENT | Class queue owner; its member list is the worklist and is re-counted in this report. |
| [class-readerless-duplicate-mechanisms-survive-cuts](docs/seon/issues/class-readerless-duplicate-mechanisms-survive-cuts.md) | cleanup | CURRENT | Class queue owner; its member list is the worklist and is re-counted in this report. |
| [cluster-config-and-bootstrap-plan-render-as-raw-maps](docs/seon/issues/cluster-config-and-bootstrap-plan-render-as-raw-maps.md) | friction | LIKELY-STALE | One of three cited schemas (`resources/seon/schemas/seon.bootstrap.plan.edn`) is deleted; re-derive the remaining two before scheduling. |
| [cluster-toolkit-stores-a-prefix-derived-projection](docs/seon/issues/cluster-toolkit-stores-a-prefix-derived-projection.md) | friction | LIKELY-STALE | Two anchors are deleted (`src/seon/cluster/instruction.cljc`, the schema monolith). |
| [cohosted-clusters-share-one-unbounded-agent-heap](docs/seon/issues/cohosted-clusters-share-one-unbounded-agent-heap.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [cohosted-second-boot-is-slow-and-trips-the-silence-backstop](docs/seon/issues/cohosted-second-boot-is-slow-and-trips-the-silence-backstop.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [complete-publication-takes-seventy-seconds](docs/seon/issues/complete-publication-takes-seventy-seconds.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [concurrent-eval-test-calibrates-interpreted-work-to-wall-time](docs/seon/issues/concurrent-eval-test-calibrates-interpreted-work-to-wall-time.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [concurrent-provider-calls-fail-with-a-closed-response-body](docs/seon/issues/concurrent-provider-calls-fail-with-a-closed-response-body.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [config-ai-request-idents-are-derived-by-string-surgery](docs/seon/issues/config-ai-request-idents-are-derived-by-string-surgery.md) | cleanup | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [config-dial-discovery-has-three-authorities](docs/seon/issues/config-dial-discovery-has-three-authorities.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [context-capture-prompts-bypass-the-blob-splitter](docs/seon/issues/context-capture-prompts-bypass-the-blob-splitter.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [context-mvp-drive-can-false-green-after-cross-agent-delivery](docs/seon/issues/context-mvp-drive-can-false-green-after-cross-agent-delivery.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [context-wave-leaves-three-small-honesty-defects](docs/seon/issues/context-wave-leaves-three-small-honesty-defects.md) | cleanup | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [contract-violation-serializes-print-tree-inside-error-data](docs/seon/issues/contract-violation-serializes-print-tree-inside-error-data.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [contracted-defn-rebuilds-the-whole-schema-projection](docs/seon/issues/contracted-defn-rebuilds-the-whole-schema-projection.md) | friction | LIKELY-STALE | Anchored on the deleted `src/seon/schema.cljc`; measurement predates the projection-acquisition work. |
| [core-namespace-pages-spend-seven-seconds-without-declaration-fallbacks](docs/seon/issues/core-namespace-pages-spend-seven-seconds-without-declaration-fallbacks.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [data-page-takes-five-and-a-half-seconds-for-three-kilobytes](docs/seon/issues/data-page-takes-five-and-a-half-seconds-for-three-kilobytes.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [database-read-admission-treats-invalid-identities-as-absence](docs/seon/issues/database-read-admission-treats-invalid-identities-as-absence.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [database-request-shape-errors-bypass-public-contracts](docs/seon/issues/database-request-shape-errors-bypass-public-contracts.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [database-value-shape-name-duplicates-the-db-key](docs/seon/issues/database-value-shape-name-duplicates-the-db-key.md) | cleanup | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [database-values-render-as-opaque-host-objects-in-html](docs/seon/issues/database-values-render-as-opaque-host-objects-in-html.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [datahike-allocates-a-konserve-cache-it-never-reads](docs/seon/issues/datahike-allocates-a-konserve-cache-it-never-reads.md) | cleanup | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [datahike-fork-is-28-commits-behind-upstream](docs/seon/issues/datahike-fork-is-28-commits-behind-upstream.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [debug-left-pane-is-not-the-exact-prompt](docs/seon/issues/debug-left-pane-is-not-the-exact-prompt.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [debug-pages-invent-wedged-runs](docs/seon/issues/debug-pages-invent-wedged-runs.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [debug-pages-receive-block-patches-for-elements-they-do-not-have](docs/seon/issues/debug-pages-receive-block-patches-for-elements-they-do-not-have.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [deletable-directories-have-no-claim-or-size-facts](docs/seon/issues/deletable-directories-have-no-claim-or-size-facts.md) | blocker | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [dependency-class-cache-prepare-races-concurrent-jvm-launches](docs/seon/issues/dependency-class-cache-prepare-races-concurrent-jvm-launches.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [dev-mcp-envelopes-misdirect-errors-and-sprawl-status](docs/seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [duplicate-identity-refusal-evidence-is-unordered](docs/seon/issues/duplicate-identity-refusal-evidence-is-unordered.md) | cleanup | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [dynamic-in-ns-cannot-persist-definition-namespace](docs/seon/issues/dynamic-in-ns-cannot-persist-definition-namespace.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [effect-context-suffix-returns-comment-notices](docs/seon/issues/effect-context-suffix-returns-comment-notices.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [effect-feedback-orders-receipts-by-id](docs/seon/issues/effect-feedback-orders-receipts-by-id.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [effect-receipts-have-no-render-producers](docs/seon/issues/effect-receipts-have-no-render-producers.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [error-class-catalog-and-renderers-disagree](docs/seon/issues/error-class-catalog-and-renderers-disagree.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [eval-drives-duplicate-a-four-minute-run-clock](docs/seon/issues/eval-drives-duplicate-a-four-minute-run-clock.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [eval-samples-cost-42mb-of-store-each](docs/seon/issues/eval-samples-cost-42mb-of-store-each.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [every-agent-prompt-is-a-neighborhood-render-walk-contract-violation](docs/seon/issues/every-agent-prompt-is-a-neighborhood-render-walk-contract-violation.md) | blocker | LIKELY-STALE | Its named cause, `walk-refuses-an-as-of-database-value-and-empties-the-agent-context`, is archived. |
| [every-background-capability-request-loses-its-connection](docs/seon/issues/every-background-capability-request-loses-its-connection.md) | blocker | CURRENT | `src/seon/effect.clj:527-528` still reads the connection out of the `*request-context*` binding frame. |
| [expected-refusal-logs-raw-datom-error-twice](docs/seon/issues/expected-refusal-logs-raw-datom-error-twice.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [failover-adds-an-uncaptured-system-context-fragment](docs/seon/issues/failover-adds-an-uncaptured-system-context-fragment.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [fault-committer-misses-the-first-injected-fault](docs/seon/issues/fault-committer-misses-the-first-injected-fault.md) | blocker | LIKELY-STALE | The cluster graph installs its error fanout as a `start-graph!` join (`src/seon/cluster.clj:2231-2243`), before `resume`. |
| [file-store-commits-pay-five-times-the-fsyncs-they-need](docs/seon/issues/file-store-commits-pay-five-times-the-fsyncs-they-need.md) | friction | LIKELY-STALE | The note's own Problem section records that the title describes the pre-`c5c55809d` path. |
| [flow-config-dials-have-two-registration-owners](docs/seon/issues/flow-config-dials-have-two-registration-owners.md) | cleanup | LIKELY-STALE | Three anchors are deleted, including the old `seon.config.resolve` writer path. |
| [flow-has-no-read-set-control-and-a-hand-rolled-egress](docs/seon/issues/flow-has-no-read-set-control-and-a-hand-rolled-egress.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [flow-monitor-test-resources-outlive-their-cleanup-scope](docs/seon/issues/flow-monitor-test-resources-outlive-their-cleanup-scope.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [flow-work-launcher-graph-omits-its-root-io-executor](docs/seon/issues/flow-work-launcher-graph-omits-its-root-io-executor.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [foreign-write-fence-reads-only-the-dynamic-var](docs/seon/issues/foreign-write-fence-reads-only-the-dynamic-var.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [fresh-agent-created-after-boot-was-not-armed](docs/seon/issues/fresh-agent-created-after-boot-was-not-armed.md) | blocker | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [fresh-cljc-files-are-jvm-only](docs/seon/issues/fresh-cljc-files-are-jvm-only.md) | cleanup | LIKELY-STALE | 15 of the 22 cited `.cljc` files no longer exist; only 8 `.cljc` files remain under `src/`. The census must be re-derived before this is actionable. |
| [generated-opening-live-pull-does-not-return-after-help](docs/seon/issues/generated-opening-live-pull-does-not-return-after-help.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [generated-opening-still-reads-a-hand-authored-bootstrap-plan](docs/seon/issues/generated-opening-still-reads-a-hand-authored-bootstrap-plan.md) | blocker | LIKELY-STALE | `resources/seon/bootstrap.edn` is deleted; `src/seon/bootstrap.clj` generates entries (`next-entry`). W1/W2 landed. |
| [generated-turn-fork-omits-the-agent-scoped-environment](docs/seon/issues/generated-turn-fork-omits-the-agent-scoped-environment.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [generated-turn-omits-the-required-render-output](docs/seon/issues/generated-turn-omits-the-required-render-output.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [give-offline-roster-discovery-a-current-read-only-helper](docs/seon/issues/give-offline-roster-discovery-a-current-read-only-helper.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [graph-construction-leaves-tap-before-resume-to-callers](docs/seon/issues/graph-construction-leaves-tap-before-resume-to-callers.md) | blocker | LIKELY-STALE | `seon.flow/start-graph!` (`src/seon/flow.clj:62-85`) runs declared `::joins` between `flow/start` and `flow/resume`; callers cannot order it wrongly. |
| [history-policy-refusal-test-is-load-flaky](docs/seon/issues/history-policy-refusal-test-is-load-flaky.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [host-bound-first-party-vars-break-in-value-position](docs/seon/issues/host-bound-first-party-vars-break-in-value-position.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [hyperlith-pin-behind-lockstep-rework](docs/seon/issues/hyperlith-pin-behind-lockstep-rework.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [init-failure-dumps-entire-prepl-event-history](docs/seon/issues/init-failure-dumps-entire-prepl-event-history.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [initial-paint-census-is-a-hand-maintained-count](docs/seon/issues/initial-paint-census-is-a-hand-maintained-count.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [instrumentation-compiles-under-one-clusters-projection](docs/seon/issues/instrumentation-compiles-under-one-clusters-projection.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [instrumentation-headline-unbounded-when-caps-absent](docs/seon/issues/instrumentation-headline-unbounded-when-caps-absent.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [interrupted-blob-staging-leaves-no-observable-artifact](docs/seon/issues/interrupted-blob-staging-leaves-no-observable-artifact.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [jvm-operator-work-takes-the-installation-lock-for-one-root](docs/seon/issues/jvm-operator-work-takes-the-installation-lock-for-one-root.md) | friction | LIKELY-STALE | The note's own Problem section records that `with-operator-lock` already derives the lock from the selected root. |
| [keep-history-is-on-by-default-without-a-decision](docs/seon/issues/keep-history-is-on-by-default-without-a-decision.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [latest-closed-run-orders-by-id-string](docs/seon/issues/latest-closed-run-orders-by-id-string.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [live-publication-has-a-hand-maintained-predicate-owner-reload](docs/seon/issues/live-publication-has-a-hand-maintained-predicate-owner-reload.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [live-root-pull-of-189-members-takes-24-seconds](docs/seon/issues/live-root-pull-of-189-members-takes-24-seconds.md) | blocker | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [loop-settlement-consumer-reads-a-key-no-producer-writes](docs/seon/issues/loop-settlement-consumer-reads-a-key-no-producer-writes.md) | friction | LIKELY-STALE | `:seon.cluster.loop/failure` has zero readers and zero writers left in `src/`. |
| [malformed-sse-data-can-change-agent-code](docs/seon/issues/malformed-sse-data-can-change-agent-code.md) | blocker | LIKELY-STALE | An unreadable `data:` payload now returns `unreadable-stream-data` as a flat error instead of being dropped (`src/seon/ai.clj:737-750`). |
| [malli-form-predicate-resolves-the-declaration-population-itself](docs/seon/issues/malli-form-predicate-resolves-the-declaration-population-itself.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [malli-vendor-is-ahead-of-pinned-dependency](docs/seon/issues/malli-vendor-is-ahead-of-pinned-dependency.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [map-unions-have-no-explicit-discriminants](docs/seon/issues/map-unions-have-no-explicit-discriminants.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [mcp-parent-watchdog-can-follow-a-reused-pid](docs/seon/issues/mcp-parent-watchdog-can-follow-a-reused-pid.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [mcp-projection-crashes-on-non-keyword-map-keys](docs/seon/issues/mcp-projection-crashes-on-non-keyword-map-keys.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [message-completion-replies-from-the-wrong-agent-and-duplicates-the-trigger](docs/seon/issues/message-completion-replies-from-the-wrong-agent-and-duplicates-the-trigger.md) | blocker | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [monitor-graph-command-proc-throws](docs/seon/issues/monitor-graph-command-proc-throws.md) | cleanup | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [my-background-poll-costs-290-tokens-per-polled-result](docs/seon/issues/my-background-poll-costs-290-tokens-per-polled-result.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [my-fs-write-docstring-hides-its-own-request-shape](docs/seon/issues/my-fs-write-docstring-hides-its-own-request-shape.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [my-web-fetch-returns-plain-html-as-a-vector-of-integers](docs/seon/issues/my-web-fetch-returns-plain-html-as-a-vector-of-integers.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [namespace-binding-targets-are-symbols-not-refs](docs/seon/issues/namespace-binding-targets-are-symbols-not-refs.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [namespace-removal-does-not-rebuild-contracted-only](docs/seon/issues/namespace-removal-does-not-rebuild-contracted-only.md) | blocker | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [namespace-renderer-encodes-results-as-comments](docs/seon/issues/namespace-renderer-encodes-results-as-comments.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [namespace-units-render-error-schema-boilerplate](docs/seon/issues/namespace-units-render-error-schema-boilerplate.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [negative-import-masks-escape-static-admission](docs/seon/issues/negative-import-masks-escape-static-admission.md) | friction | LIKELY-STALE | The note's own Problem section records the original blocker resolved; only one downstream mismatch remains. |
| [nested-map-sequences-render-as-tables-inside-structural-values](docs/seon/issues/nested-map-sequences-render-as-tables-inside-structural-values.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [observable-graph-transitions-are-polled-in-tests](docs/seon/issues/observable-graph-transitions-are-polled-in-tests.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [one-identity-string-names-two-entities](docs/seon/issues/one-identity-string-names-two-entities.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [opaque-contract-generators-share-live-process-objects](docs/seon/issues/opaque-contract-generators-share-live-process-objects.md) | cleanup | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [opening-generator-pushes-undemanded-candidates](docs/seon/issues/opening-generator-pushes-undemanded-candidates.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [opening-walkthrough-replicates-a-usage-test](docs/seon/issues/opening-walkthrough-replicates-a-usage-test.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [operator-classifies-processes-by-command-substrings](docs/seon/issues/operator-classifies-processes-by-command-substrings.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [operator-status-refuses-its-own-readiness-result](docs/seon/issues/operator-status-refuses-its-own-readiness-result.md) | friction | LIKELY-STALE | `seon.operator/status` now declares `[:or :seon.operator/status :seon.error/value]` (`src/seon/operator.clj:83-86`). |
| [operator-subprocesses-have-unbounded-read-and-wait-paths](docs/seon/issues/operator-subprocesses-have-unbounded-read-and-wait-paths.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [output-sink-query-excludes-operator-and-mcp-scripts](docs/seon/issues/output-sink-query-excludes-operator-and-mcp-scripts.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [oversight-fleet-test-pins-a-stale-proc-roster](docs/seon/issues/oversight-fleet-test-pins-a-stale-proc-roster.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [oversight-treats-a-20ms-ping-absence-as-state](docs/seon/issues/oversight-treats-a-20ms-ping-absence-as-state.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [parallel-test-stress-exposes-eleven-isolation-sensitive-tests](docs/seon/issues/parallel-test-stress-exposes-eleven-isolation-sensitive-tests.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [partial-hot-reload-produces-mixed-code-with-no-warning](docs/seon/issues/partial-hot-reload-produces-mixed-code-with-no-warning.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [posh-cardinality-one-pull-analysis-has-an-arity-defect](docs/seon/issues/posh-cardinality-one-pull-analysis-has-an-arity-defect.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [pre-rename-root-claims-are-unreadable-noise-on-every-status](docs/seon/issues/pre-rename-root-claims-are-unreadable-noise-on-every-status.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [production-docstrings-teach-deleted-semantics](docs/seon/issues/production-docstrings-teach-deleted-semantics.md) | friction | LIKELY-STALE | 8 of 12 cited files no longer exist (the `.cljc` to `.clj` rename); the census must be re-run before it is a worklist. |
| [prose-only-model-replies-are-not-durable-facts](docs/seon/issues/prose-only-model-replies-are-not-durable-facts.md) | blocker | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [provider-output-token-wire-key-is-hard-coded](docs/seon/issues/provider-output-token-wire-key-is-hard-coded.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [public-contract-census-can-pass-with-no-subjects](docs/seon/issues/public-contract-census-can-pass-with-no-subjects.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [publication-reload-hand-lists-namespaces-and-misses-dependencies](docs/seon/issues/publication-reload-hand-lists-namespaces-and-misses-dependencies.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [ranged-store-collection-can-delete-live-segments-via-branch-resurrection](docs/seon/issues/ranged-store-collection-can-delete-live-segments-via-branch-resurrection.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [render-adversarial-roots-outlive-their-experiment](docs/seon/issues/render-adversarial-roots-outlive-their-experiment.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [render-history-serializes-unexecuted-form-projections](docs/seon/issues/render-history-serializes-unexecuted-form-projections.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [render-live-proof-roots-have-no-lifecycle-owner](docs/seon/issues/render-live-proof-roots-have-no-lifecycle-owner.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [render-package-proc-reruns-unchanged-renderers](docs/seon/issues/render-package-proc-reruns-unchanged-renderers.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [render-token-budgets-are-private-dials-no-producer-supplies](docs/seon/issues/render-token-budgets-are-private-dials-no-producer-supplies.md) | friction | CURRENT | `::token-budget` is still a private transcript key (`src/seon/render/transcript.clj:785,812,832,956`) with no config or schema producer. |
| [render-value-floor-refuses-any-map-with-unqualified-keys](docs/seon/issues/render-value-floor-refuses-any-map-with-unqualified-keys.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [render-walk-frames-values-as-comments](docs/seon/issues/render-walk-frames-values-as-comments.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [render-walk-maintains-a-derived-edge-hand-list](docs/seon/issues/render-walk-maintains-a-derived-edge-hand-list.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [render-walk-wrapper-returns-comment-notices](docs/seon/issues/render-walk-wrapper-returns-comment-notices.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [render-wave-properties-cannot-produce-their-failing-cases](docs/seon/issues/render-wave-properties-cannot-produce-their-failing-cases.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [repl-parity-divergences](docs/seon/issues/repl-parity-divergences.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [root-compute-executor-has-no-per-cluster-fairness](docs/seon/issues/root-compute-executor-has-no-per-cluster-fairness.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [run-opening-retries-storm-against-an-answered-trigger](docs/seon/issues/run-opening-retries-storm-against-an-answered-trigger.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [run-renderer-narrates-forms-and-receipts](docs/seon/issues/run-renderer-narrates-forms-and-receipts.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [runtime-lint-does-not-resolve-namespace-aliases](docs/seon/issues/runtime-lint-does-not-resolve-namespace-aliases.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [runtime-turn-and-evaluate-kernels-conflate-boundaries](docs/seon/issues/runtime-turn-and-evaluate-kernels-conflate-boundaries.md) | friction | LIKELY-STALE | Anchored on the deleted `src/seon/cluster/loop.cljc`; `loop.clj` has since been reduced twice. |
| [schedule-graph-test-constructs-a-handle-without-an-environment](docs/seon/issues/schedule-graph-test-constructs-a-handle-without-an-environment.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [schema-datahike-keeps-a-readerless-second-codec](docs/seon/issues/schema-datahike-keeps-a-readerless-second-codec.md) | friction | LIKELY-STALE | Anchored on the deleted `src/seon/schema/datahike.cljc`; the ambient encode family was already deleted on 2026-08-07. |
| [schema-declaration-rebuilds-four-gigabytes-per-form](docs/seon/issues/schema-declaration-rebuilds-four-gigabytes-per-form.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [schema-environment-is-ambient-not-explicit](docs/seon/issues/schema-environment-is-ambient-not-explicit.md) | blocker | CURRENT | `src/seon/schema.clj:1004` still holds a process-global `defonce seon-registry` and line 1027 still calls `mr/set-default-registry!`. |
| [schema-exact-reuse-warnings-are-unreadable-at-volume](docs/seon/issues/schema-exact-reuse-warnings-are-unreadable-at-volume.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [schema-guard-refuses-accretive-loosenings-with-data](docs/seon/issues/schema-guard-refuses-accretive-loosenings-with-data.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [schema-map-extraction-still-depends-on-position-two](docs/seon/issues/schema-map-extraction-still-depends-on-position-two.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [schema-population-retains-five-readerless-rows](docs/seon/issues/schema-population-retains-five-readerless-rows.md) | cleanup | LIKELY-STALE | 3 of 5 anchors are deleted files; re-derive the readerless set from the current population. |
| [schema-source-provenance-accumulates-in-a-global-atom](docs/seon/issues/schema-source-provenance-accumulates-in-a-global-atom.md) | cleanup | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [sci-base-context-silently-hand-lists-special-callables](docs/seon/issues/sci-base-context-silently-hand-lists-special-callables.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [sci-reader-hides-a-production-source-cap](docs/seon/issues/sci-reader-hides-a-production-source-cap.md) | friction | LIKELY-STALE | Anchored on the deleted `src/seon/cluster/reply.cljc`. |
| [search-index-property-collides-with-process-index-id](docs/seon/issues/search-index-property-collides-with-process-index-id.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [secondary-only-attributes-have-no-covering-index](docs/seon/issues/secondary-only-attributes-have-no-covering-index.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [seon-db-has-no-branch-or-commit-reads](docs/seon/issues/seon-db-has-no-branch-or-commit-reads.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [settle-is-public-without-a-complete-contract](docs/seon/issues/settle-is-public-without-a-complete-contract.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [shared-context-session-delta-crosses-run-attribution](docs/seon/issues/shared-context-session-delta-crosses-run-attribution.md) | blocker | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [source-load-is-118s-against-the-ten-second-law](docs/seon/issues/source-load-is-118s-against-the-ten-second-law.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [stale-language-specific-kondo-cache-blocks-correct-code](docs/seon/issues/stale-language-specific-kondo-cache-blocks-correct-code.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [status-floods-unreadable-external-claim-warnings](docs/seon/issues/status-floods-unreadable-external-claim-warnings.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing](docs/seon/issues/storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [system-generated-messages-omit-arrival-ordinals](docs/seon/issues/system-generated-messages-omit-arrival-ordinals.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [terminal-refusal-error-fact-fails-on-oversized-data](docs/seon/issues/terminal-refusal-error-fact-fails-on-oversized-data.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [test-runner-cleans-a-worker-root-while-kondo-is-still-writing](docs/seon/issues/test-runner-cleans-a-worker-root-while-kondo-is-still-writing.md) | blocker | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [thinking-tool-continuations-have-no-faithful-request-shape](docs/seon/issues/thinking-tool-continuations-have-no-faithful-request-shape.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [time-limit-face-exposes-interpreter-interrupt-marker](docs/seon/issues/time-limit-face-exposes-interpreter-interrupt-marker.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [transcript-about-lookup-passes-a-set-to-pull-many](docs/seon/issues/transcript-about-lookup-passes-a-set-to-pull-many.md) | blocker | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [transcript-candidate-window-orders-receipts-and-comments-by-id](docs/seon/issues/transcript-candidate-window-orders-receipts-and-comments-by-id.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [transcript-renderer-encodes-entries-as-comment-forms](docs/seon/issues/transcript-renderer-encodes-entries-as-comment-forms.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [unindexed-namespaces-render-as-empty](docs/seon/issues/unindexed-namespaces-render-as-empty.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [unlogged-findings-2026-08-01](docs/seon/issues/unlogged-findings-2026-08-01.md) | friction | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [unregistered-ifn-malli-schema-breaks-source-publication](docs/seon/issues/unregistered-ifn-malli-schema-breaks-source-publication.md) | blocker | LIKELY-STALE | No `:ifn` remains anywhere in `src/seon/flow.clj`; the cited declaration was rewritten. |
| [value-admission-resolves-the-declaration-population-per-node](docs/seon/issues/value-admission-resolves-the-declaration-population-per-node.md) | blocker | CURRENT | Cited first-party owners still exist at HEAD; no contradicting landing found this pass. |
| [value-floor-residue-duplicate-cursors-and-marker-hand-lists](docs/seon/issues/value-floor-residue-duplicate-cursors-and-marker-hand-lists.md) | friction | LIKELY-STALE | Two anchors (`src/seon/render/value.cljc`) are deleted; the W3 floor moved owners. |
| [vendored-transit-clj-drifts-from-the-pinned-artifact](docs/seon/issues/vendored-transit-clj-drifts-from-the-pinned-artifact.md) | cleanup | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [web-config-dials-ship-without-shipped-defaults](docs/seon/issues/web-config-dials-ship-without-shipped-defaults.md) | blocker | LIKELY-STALE | All eight dials are now decided in `config/default.edn:259-282`; the refusal premise is gone. |
| [wildcard-receipt-pull-refuses-a-stored-dependency-plan](docs/seon/issues/wildcard-receipt-pull-refuses-a-stored-dependency-plan.md) | blocker | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [within-run-schema-key-refinement-needs-an-owner-ruling](docs/seon/issues/within-run-schema-key-refinement-needs-an-owner-ruling.md) | friction | UNVERIFIED | Evidence is a live measurement, probe, or drive observation with no first-party source anchor to spot-check. |
| [work-submission-can-block-before-its-time-limit](docs/seon/issues/work-submission-can-block-before-its-time-limit.md) | blocker | LIKELY-STALE | `submit!!` now documents and implements non-blocking admission with a flat `::submission-capacity` value (`src/seon/flow.clj:775-792`). |

## (b) Top 15 root causes outside the four running lanes

Ordered by importance: platform/boot/suite breakage, then blockers on the
evolving-session spine (gate, rebirth proof, drive, phases), then class-kill
throughput, then friction. Each row names a specific causal hypothesis with a
file:line anchor and the smallest construction that makes the class unwritable.

| # | Issue | Owner and file:line hypothesis | Smallest class-killing fix shape |
|---:|---|---|---|
| 1 | [acquire-has-no-per-row-containment](docs/seon/issues/acquire-has-no-per-row-containment.md) | `src/seon/sci/eval.clj:1341-1352` — `install-row` calls `install-row!` inside a bare `reduce` with no containment; a throwing agent-authored row escapes `acquire!` and the branch cannot be acquired at all. | Acquisition returns a total value: every row yields either an installed Var or a flat refusal fact carried in the result. Nothing about acquisition throws. One regression: a poisoned agent row leaves the branch acquirable and the row's refusal queryable. **This is a rebirth-proof blocker** — rebirth is reacquisition from facts, and one bad row currently bricks it. |
| 2 | [schema-environment-is-ambient-not-explicit](docs/seon/issues/schema-environment-is-ambient-not-explicit.md) | `src/seon/schema.clj:1004` (`defonce ^:private seon-registry`) and `:1027` (`mr/set-default-registry!`) still install one process-global registry; two co-hosted clusters and two parallel tests share declarations. | The projection rides the database value it derives from; no process-wide slot exists to disagree with itself. Killing this closes the largest share of P1 and, per the 2026-08-07 precedent, dissolves the per-attribute resolution costs downstream. |
| 3 | [test-runner-cleans-a-worker-root-while-kondo-is-still-writing](docs/seon/issues/test-runner-cleans-a-worker-root-while-kondo-is-still-writing.md) | `test/seon/test_runner_test.clj:353` plus the worker-root release in `src/seon/test/runner.clj` — the root is deleted while clj-kondo still holds its cache directory open, so the changed-files gate cannot settle. | Root release waits on the child's completion value rather than on the directory being empty; the ownership value carries the child completion. Platform breakage, so it outranks the spine. |
| 4 | [every-background-capability-request-loses-its-connection](docs/seon/issues/every-background-capability-request-loses-its-connection.md) | `src/seon/effect.clj:527-528` still reads `(:seon.db/connection *request-context*)` out of a binding frame; the background path settles on another thread where the frame is absent. | The connection travels as data on the submission value, exactly as `dials` already does two lines below (`src/seon/effect.clj:534`). The half-fix is already in the file — finish it and delete the binding read. |
| 5 | [concurrent-provider-calls-fail-with-a-closed-response-body](docs/seon/issues/concurrent-provider-calls-fail-with-a-closed-response-body.md) | `src/seon/ai.clj:1185-1194` — one `with-open` reader per call over a shared client; a concurrent turn closes the body mid-read after a 2xx, so a completion the provider charged for is discarded. | Response-body custody rides the attempt value, not the shared client's lifetime; a 2xx that fails mid-read commits a partial-completion fact rather than discarding the turn. Kills two N10 members at once. |
| 6 | [a-mid-stream-provider-disconnect-discards-the-whole-turn](docs/seon/issues/a-mid-stream-provider-disconnect-discards-the-whole-turn.md) | Same owner: `src/seon/ai.clj:1185-1194` refuses the entire run on `::unparseable-body` (`:825,:831,:852,:870`), committing zero forms and zero receipts. | Every accepted request has a terminal publisher; a truncated stream settles as a partial completion with the bytes it did receive, never as nothing. |
| 7 | [a-reasoning-only-stream-burns-the-whole-time-limit](docs/seon/issues/a-reasoning-only-stream-burns-the-whole-time-limit.md) | `src/seon/ai.clj:737-770` — `reasoning_content` deltas advance the snapshot but no text delta ever arrives, so the 180 s `:seon.config.ai/timeout-ms` is spent and no usage document is recorded. | The stream's terminal condition is derived from the provider's own finish signal plus a reasoning-only detector that settles with the reasoning it captured; absence of assistant text is a typed refusal that names what did arrive. |
| 8 | [agent-form-calls-to-core-namespaces-are-not-indexed](docs/seon/issues/agent-form-calls-to-core-namespaces-are-not-indexed.md) | `src/seon/fn.clj:342,366,586,596` build `:seon.fn/calls` from the analyzer's resolved edges; an agent form's call to `seon.db/q` produces no edge even though that entity exists. | Run-form indexing uses the same edge construction as source indexing — one mechanism. Until then "which tests reach this function" and the changed-test selector are both blind to agent-authored work, which directly weakens the evolving-session history derivation. |
| 9 | [sci-base-context-silently-hand-lists-special-callables](docs/seon/issues/sci-base-context-silently-hand-lists-special-callables.md) | `src/seon/sci/eval.clj:257-264` and `:1162-1164` install `help`, `doc`, `dir` plus further special callables into `clojure.core`/`clojure.repl` from a literal map; the agent's rendered situation names only three. | Injected callables are declared facts rendered into the situation by the same query that installs them; there is no second literal map to forget. Directly on the evolving-session spine — the opening teaches what this list contains. |
| 10 | [instrumentation-compiles-under-one-clusters-projection](docs/seon/issues/instrumentation-compiles-under-one-clusters-projection.md) | Malli instrumentation alters Var roots process-wide while the operator applies it inside one cluster's `call-with-projection` (`src/seon/schema.clj:1194` pattern). N co-hosted clusters share one set of wrappers. | Same construction as #2: instrumentation is derived per database value, or it is refused when more than one cluster is hosted. Do not fix separately — it is P1's second face. |
| 11 | [a-schema-resource-edit-bricks-value-admission-in-every-running-cluster](docs/seon/issues/a-schema-resource-edit-bricks-value-admission-in-every-running-cluster.md) | `src/seon/schema.clj:132` resolves a declaration's predicate via `requiring-resolve` while `register-core-predicate!` (`:892`) runs at namespace load; the classpath half and the load half advance independently. | Loaded artifacts carry a source digest; a declaration whose predicate generation differs is a typed refusal, never a silent admission failure. This is N3's structural kill and it also removes an entire class of "works until the second cluster" surprises. |
| 12 | [ranged-store-collection-can-delete-live-segments-via-branch-resurrection](docs/seon/issues/ranged-store-collection-can-delete-live-segments-via-branch-resurrection.md) | The maintained Datahike fork: `gc_guard` covers objects a sequence *writes*, not objects `versioning/branch!` makes *reachable again*. | Replacement and collection share one atomic head/reachability owner. This is data loss, not friction — it deserves a lane despite N14's low throughput score. |
| 13 | [cohosted-clusters-share-one-unbounded-agent-heap](docs/seon/issues/cohosted-clusters-share-one-unbounded-agent-heap.md) | Clusters share one JVM heap; SCI's `time-limit` bounds interpreted entrances, not retained bytes, so one cluster can starve every sibling. | Per-cluster retained-byte accounting on the work submission value with a loud refusal, or an explicit ruling that co-hosting is single-tenant. Likely needs the owner design gate before implementation. |
| 14 | [dev-mcp-envelopes-misdirect-errors-and-sprawl-status](docs/seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md) | `eval_clj` reports the `seon.cluster/mcp-io-prepl` serving frame instead of the throw site; `mcp-projection-crashes-on-non-keyword-map-keys` is the same envelope's second face. | One evidence-complete flat constructor at the MCP boundary. This is a velocity multiplier: every lane diagnoses through this envelope, so a wrong frame costs every fix cycle. |
| 15 | [source-load-is-118s-against-the-ten-second-law](docs/seon/issues/source-load-is-118s-against-the-ten-second-law.md) and [complete-publication-takes-seventy-seconds](docs/seon/issues/complete-publication-takes-seventy-seconds.md) | `(require 'seon.artifact)` measures 11.8 s and `seon.cluster/refresh-source!` 70.2 s, both RUNNABLE in Malli var registration. | Same root as #2 and #11: registration cost scales with the whole registry per form. Fixing the schema environment is expected to move both numbers; measure before and after rather than attacking them separately. |

Two rows deliberately omitted despite blocker severity because they belong to a
running lane's owner: [wildcard-receipt-pull-refuses-a-stored-dependency-plan](docs/seon/issues/wildcard-receipt-pull-refuses-a-stored-dependency-plan.md)
and [foreign-write-fence-reads-only-the-dynamic-var](docs/seon/issues/foreign-write-fence-reads-only-the-dynamic-var.md) (`src/seon/db.clj`),
[render-value-floor-refuses-any-map-with-unqualified-keys](docs/seon/issues/render-value-floor-refuses-any-map-with-unqualified-keys.md) and
[agent-html-still-uses-the-retired-transcript-assembler](docs/seon/issues/agent-html-still-uses-the-retired-transcript-assembler.md) (`src/seon/render.clj`,
`src/seon/render/transcript.clj`), and
[render-history-serializes-unexecuted-form-projections](docs/seon/issues/render-history-serializes-unexecuted-form-projections.md)
(`src/seon/render/walk.clj`, protected for Phase 1).

## (c) Next wave — five file-disjoint lane sketches

All five are strictly disjoint from the four running lanes' owned paths
(`src/seon/cluster/{agent,work,loop,run}.clj` + `test/seon/cluster/agent_test.clj`;
`src/seon/render.clj` + `src/seon/print.cljc` + `src/seon/render/{transcript,ns,hiccup}.clj`;
`src/seon/db.clj` + `src/seon/render/web.clj` + `src/seon/error.clj`;
`src/seon/bootstrap.clj` + `src/seon/render/walk.clj`) and from each other.

### Lane A — acquisition containment and declared injections (recommended first)

- **Owns:** `src/seon/sci/eval.clj`, `src/seon/sci/admit.clj`, `test/seon/sci/`.
- **Members:** [acquire-has-no-per-row-containment](docs/seon/issues/acquire-has-no-per-row-containment.md),
  [sci-base-context-silently-hand-lists-special-callables](docs/seon/issues/sci-base-context-silently-hand-lists-special-callables.md),
  [an-unmatched-print-face-throws-no-matching-clause-and-names-nothing](docs/seon/issues/an-unmatched-print-face-throws-no-matching-clause-and-names-nothing.md),
  [host-bound-first-party-vars-break-in-value-position](docs/seon/issues/host-bound-first-party-vars-break-in-value-position.md),
  [negative-import-masks-escape-static-admission](docs/seon/issues/negative-import-masks-escape-static-admission.md),
  [value-admission-resolves-the-declaration-population-per-node](docs/seon/issues/value-admission-resolves-the-declaration-population-per-node.md).
- **Class regression:** a branch holding one unloadable agent-authored row is
  still acquirable, the row's refusal is a queryable fact, and no `case` in the
  admission grammar can throw `no matching clause`.
- **Price:** 3-4 days. **Why first:** it is the rebirth proof's prerequisite and
  the spine's step 2 cannot honestly pass while one poisoned row bricks a branch.

### Lane B — one schema environment (the P1 root kill)

- **Owns:** `src/seon/schema.clj`, `src/seon/schema/edn.clj`,
  `src/seon/schema/datahike.clj`, `src/seon/schema/internal.cljc`.
- **Members:** [schema-environment-is-ambient-not-explicit](docs/seon/issues/schema-environment-is-ambient-not-explicit.md),
  [malli-form-predicate-resolves-the-declaration-population-itself](docs/seon/issues/malli-form-predicate-resolves-the-declaration-population-itself.md),
  [schema-source-provenance-accumulates-in-a-global-atom](docs/seon/issues/schema-source-provenance-accumulates-in-a-global-atom.md),
  [schema-declaration-rebuilds-four-gigabytes-per-form](docs/seon/issues/schema-declaration-rebuilds-four-gigabytes-per-form.md),
  [instrumentation-compiles-under-one-clusters-projection](docs/seon/issues/instrumentation-compiles-under-one-clusters-projection.md),
  [a-schema-resource-edit-bricks-value-admission-in-every-running-cluster](docs/seon/issues/a-schema-resource-edit-bricks-value-admission-in-every-running-cluster.md),
  [schema-map-extraction-still-depends-on-position-two](docs/seon/issues/schema-map-extraction-still-depends-on-position-two.md).
- **Class regression:** two co-hosted clusters with divergent declarations each
  admit only their own, proven by one live cross-cluster falsifier; no
  process-global registry slot exists to be read.
- **Price:** 5-7 days. **Expected side effects:** rows 15's two performance
  numbers, and most of P1's remaining twelve members. Measure before and after.

### Lane C — provider transport totality

- **Owns:** `src/seon/ai.clj`, `src/seon/ai/tokens.cljc`,
  `resources/seon/schemas/seon.ai*.edn`, `test/seon/ai_test.clj`.
- **Members:** [concurrent-provider-calls-fail-with-a-closed-response-body](docs/seon/issues/concurrent-provider-calls-fail-with-a-closed-response-body.md),
  [a-mid-stream-provider-disconnect-discards-the-whole-turn](docs/seon/issues/a-mid-stream-provider-disconnect-discards-the-whole-turn.md),
  [a-reasoning-only-stream-burns-the-whole-time-limit](docs/seon/issues/a-reasoning-only-stream-burns-the-whole-time-limit.md),
  [failover-adds-an-uncaptured-system-context-fragment](docs/seon/issues/failover-adds-an-uncaptured-system-context-fragment.md),
  [thinking-tool-continuations-have-no-faithful-request-shape](docs/seon/issues/thinking-tool-continuations-have-no-faithful-request-shape.md),
  [provider-output-token-wire-key-is-hard-coded](docs/seon/issues/provider-output-token-wire-key-is-hard-coded.md),
  [ai-transport-taxonomy-test-can-run-zero-assertions](docs/seon/issues/ai-transport-taxonomy-test-can-run-zero-assertions.md).
- **Class regression:** every accepted provider request settles as exactly one
  durable terminal fact — completion, partial completion, or typed refusal —
  and no 2xx can end with zero facts. Kills three N10 members.
- **Price:** 2-3 days. **Why now:** the spine's step 3 is a real drive; today a
  concurrent drive discards paid completions invisibly.

### Lane D — test platform and gate velocity

- **Owns:** `src/seon/test/runner.clj`, `src/seon/test/selection.clj`,
  `src/seon/dev/changed_test.clj`, `test/seon/test_runner_test.clj`.
- **Members:** [test-runner-cleans-a-worker-root-while-kondo-is-still-writing](docs/seon/issues/test-runner-cleans-a-worker-root-while-kondo-is-still-writing.md),
  [parallel-test-stress-exposes-eleven-isolation-sensitive-tests](docs/seon/issues/parallel-test-stress-exposes-eleven-isolation-sensitive-tests.md),
  [dependency-class-cache-prepare-races-concurrent-jvm-launches](docs/seon/issues/dependency-class-cache-prepare-races-concurrent-jvm-launches.md),
  [changed-test-selector-classifies-hosts-by-path-prefix](docs/seon/issues/changed-test-selector-classifies-hosts-by-path-prefix.md),
  [changed-test-report-is-one-enormous-line](docs/seon/issues/changed-test-report-is-one-enormous-line.md),
  [stale-language-specific-kondo-cache-blocks-correct-code](docs/seon/issues/stale-language-specific-kondo-cache-blocks-correct-code.md),
  [public-contract-census-can-pass-with-no-subjects](docs/seon/issues/public-contract-census-can-pass-with-no-subjects.md).
- **Class regression:** a worker root is released only after its children
  publish completion, and the changed selector derives host impact from
  `:seon.fn/calls` rather than a path prefix.
- **Price:** 2-3 days. **Why now:** the integration gate is the spine's step 1
  and it currently cannot settle. Development velocity outranks the queue.

### Lane E — staleness reconciliation (cheap, no production owner)

- **Owns:** `docs/seon/issues/` only.
- **Work:** falsify or confirm the 29 LIKELY-STALE rows in section (a), archive
  the confirmed ones with the commit that closed them, and reconcile the class
  notes' member lists (N5 is 5 open, not 11; N1 is 32, not 34). Close P4
  outright if the `start-graph!` join seam holds under one falsifier.
- **Price:** 0.5-1 day. **Why:** three of the four running lanes and the whole
  campaign rank are computed from member counts that are measurably wrong, and
  roughly one open note in seven is describing a system that no longer exists.

## (d) Calibration — what is genuinely in good shape

Not everything here is alarm. These held up under direct source reading:

- **P4 is structurally dead.** `seon.flow/start-graph!`
  (`src/seon/flow.clj:62-85`) makes the unsafe tap-after-resume order
  unconstructible, and the only production caller uses it
  (`src/seon/cluster.clj:2231-2243`). Both member notes are stale.
- **The provider failure taxonomy is honest.** `src/seon/ai.clj:988-1000`
  reasons explicitly about whether a call cost money and refuses to fail over
  on ambiguously paid work. The transport gaps in Lane C are about
  *terminal evidence*, not about the taxonomy being sloppy — the taxonomy is
  some of the clearest reasoning in the tree.
- **Malformed stream data is now loud.** `src/seon/ai.clj:737-750` returns
  `unreadable-stream-data` as a flat error where it used to silently splice
  around the bad chunk. That was a genuine code-changing hazard and it is gone.
- **The hand-authored bootstrap is gone.** `resources/seon/bootstrap.edn` and
  `resources/seon/schemas/seon.bootstrap.plan.edn` no longer exist; W1/W2's
  generated opening replaced them, which alone retires four open notes.
- **N5's constructor worked.** Six of eleven members archived in two days after
  the 2026-08-12 landing — the class-kill strategy is producing what the
  campaign plan predicted, and that is the evidence for continuing it.
- **Config dials are decided.** `config/default.edn:259-282` now decides all
  eight `:seon.config.web/*` dials; the boot refusal that blocked every cluster
  start is closed.
- **The `.cljc` portability census is obsolete in the right direction.** Only
  eight `.cljc` files remain under `src/`; the mixed-tier residue the note
  described was resolved by renaming to `.clj` rather than by pretending.

One counter-calibration worth recording: the inline-predicate class is *not*
dead even though its cited instance is. `src/seon/flow.clj:70` declares
`[:fn clojure.core/ifn?]` inline inside `start-graph!`'s own schema — a new
instance of exactly the construction [an-inline-fn-predicate-in-src-refuses-every-corpus-projection](docs/seon/issues/an-inline-fn-predicate-in-src-refuses-every-corpus-projection.md)
described, written after that note was filed. Stale instance, live class. That
is the argument for class-kills over instance fixes in one sentence.
