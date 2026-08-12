---
type: research
status: active
tags: [research, issue, architecture]
---

# Issue class mining — 2026-08-11

## Result

The requested 180-note open-issue snapshot gained 4
concurrently filed blockers while mining was in progress. I read and classified
those notes too, so the final source census is 184: five
owner-identified classes, 14 additional recurring classes, and 45
honest singletons. The 14 new classes account for 111 open notes; the
five existing classes account for 28; the remaining 45
do not share a sufficiently specific construction and are not force-fit.

Four suggested families survived with sharper boundaries:

- silent-empty, ugly, opaque, noisy, and comment-shaped output are one outward
  render-contract class (N1);
- stale-JVM behavior is the loaded-artifact identity class (N3);
- shared-root contention is an instance of explicit root/resource lifetime
  ownership (N4), not a second class; and
- “boot ordering” is not one new class. Its observed notes decompose into the
  already identified clock, missing-producer, and tap-before-source classes,
  plus dependency-representation and singleton bootstrap contract defects.

This report filed 14 class issues. They are generated queue owners, not part of
the 184-note mining input; after filing, the issue corpus contains
198 open notes and each generated issue is tagged to its class.

## Scope and method

I read the complete localized authorities, then systematically read the title
and Problem section of every one of the 184 open notes. I read full
notes where the class boundary was unclear or the note contained multiple
defects. I also read the most recently added 60 archive notes (2026-08-07
through 2026-08-11) and all 25 locally dated 2026-08-11 lane summaries under
`tmp/orchestrator/*-summary.txt` (21 substantive, four in-progress
placeholders).

Classification is primary and exclusive. A class means one construction can
make all listed instances unwritable. “Singleton” is deliberate residue, not
an assertion that the issue has never occurred elsewhere. The compound
[context-wave honesty note](docs/seon/issues/context-wave-leaves-three-small-honesty-defects.md)
is therefore a singleton rather than being counted three times.

The recommendation score is
`(open issues × recurrence rate) / midpoint implementation days`, where
recurrence rate is dated member issue-note observations divided by the 16-day
window 2026-07-27 through 2026-08-11. Open and recent-archive member notes count;
lane summaries corroborate but do not inflate the numerator. This ranks
throughput, not consequence: the terminal-evidence and atomic-reachability
classes remain blockers despite lower scores.

### Lane-feedback cross-check

The 2026-08-11 lane evidence confirmed the class boundaries rather than adding
uncited score weight:

- [population rides the value](tmp/orchestrator/population-rides-the-value-summary.txt),
  [projection rides the environment](tmp/orchestrator/projection-rides-the-environment-summary.txt),
  and [walk emission truth](tmp/orchestrator/walk-emission-truth-summary.txt)
  repeated P1 at different owners;
- [isolation-sensitive tests](tmp/orchestrator/isolation-sensitive-tests-summary.txt)
  and [wake-routing conservation](tmp/orchestrator/wake-routing-conservation-summary.txt)
  confirmed P2’s event boundary;
- [fault committer first fault](tmp/orchestrator/fault-committer-first-fault-summary.txt)
  confirmed P4’s construction order;
- [pin drift gate](tmp/orchestrator/pin-drift-gate-summary.txt) supplied P5’s
  repository-wide structural proof;
- [nested faces](tmp/orchestrator/nested-faces-fix-summary.txt) and
  [W1 integration](tmp/orchestrator/w1-integration-summary.txt) confirmed N1’s
  total-render requirement; and
- [export reopen](tmp/orchestrator/export-reopen-fix-summary.txt),
  [long-tier triage](tmp/orchestrator/long-tier-triage-summary.txt), and
  [small reds](tmp/orchestrator/small-reds-summary.txt) corroborated N4, N3,
  and N2 respectively.

## The five already identified classes

These are assignments, not re-derivations.

| Class | Open | Recent recurrence | Structural kill | Cost |
|---|---:|---|---|---|
| P1 — ambient reach-sideways state | 12 | 2026-08-07, 08, 10, 11 | The immutable environment/projection rides the work or database value; APIs require it and expose no dynamic/process fallback. | 10–20 engineer-days |
| P2 — clocks stand in for observable events | 5 | 2026-08-07, 08, 10, 11 | Owners return completion/listener/readiness values; consumers register before derive and no timing absence is a verdict. | 3–10 days |
| P3 — consumer reads a key no producer writes | 7 | 2026-08-08, 10, 11 | Constructors return total schema’d values; required keys/carriers are part of the constructor output and one enumeration property. | 3–7 days |
| P4 — tap after source/resume | 2 | 2026-08-07, 10, 11 | Graph construction installs declared sinks before the source can resume; callers receive no separate unsafe sequence. | 1–3 days |
| P5 — hand-copied pins | 2 | 2026-07-31, 08-11 | Derive executable pins from all 107 gitlinks and reject stale documentation/skill claims repository-wide. The gate landed 2026-08-11; the two open mismatches still need reconciliation. | 1–2 days |

### P1 — ambient reach-sideways state

[every-background-capability-request-loses-its-connection](docs/seon/issues/every-background-capability-request-loses-its-connection.md), [flow-work-launcher-graph-omits-its-root-io-executor](docs/seon/issues/flow-work-launcher-graph-omits-its-root-io-executor.md), [foreign-write-fence-reads-only-the-dynamic-var](docs/seon/issues/foreign-write-fence-reads-only-the-dynamic-var.md), [history-policy-refusal-test-is-load-flaky](docs/seon/issues/history-policy-refusal-test-is-load-flaky.md), [instrumentation-compiles-under-one-clusters-projection](docs/seon/issues/instrumentation-compiles-under-one-clusters-projection.md), [malli-form-predicate-resolves-the-declaration-population-itself](docs/seon/issues/malli-form-predicate-resolves-the-declaration-population-itself.md), [opaque-contract-generators-share-live-process-objects](docs/seon/issues/opaque-contract-generators-share-live-process-objects.md), [schema-declaration-rebuilds-four-gigabytes-per-form](docs/seon/issues/schema-declaration-rebuilds-four-gigabytes-per-form.md), [schema-environment-is-ambient-not-explicit](docs/seon/issues/schema-environment-is-ambient-not-explicit.md), [schema-source-provenance-accumulates-in-a-global-atom](docs/seon/issues/schema-source-provenance-accumulates-in-a-global-atom.md), [shared-context-session-delta-crosses-run-attribution](docs/seon/issues/shared-context-session-delta-crosses-run-attribution.md), [value-admission-resolves-the-declaration-population-per-node](docs/seon/issues/value-admission-resolves-the-declaration-population-per-node.md).

### P2 — clocks stand in for observable events

[cohosted-second-boot-is-slow-and-trips-the-silence-backstop](docs/seon/issues/cohosted-second-boot-is-slow-and-trips-the-silence-backstop.md), [concurrent-eval-test-calibrates-interpreted-work-to-wall-time](docs/seon/issues/concurrent-eval-test-calibrates-interpreted-work-to-wall-time.md), [eval-drives-duplicate-a-four-minute-run-clock](docs/seon/issues/eval-drives-duplicate-a-four-minute-run-clock.md), [observable-graph-transitions-are-polled-in-tests](docs/seon/issues/observable-graph-transitions-are-polled-in-tests.md), [oversight-treats-a-20ms-ping-absence-as-state](docs/seon/issues/oversight-treats-a-20ms-ping-absence-as-state.md).

### P3 — consumer reads a key no producer writes

[activation-closure-records-no-schema-keys](docs/seon/issues/activation-closure-records-no-schema-keys.md), [dynamic-in-ns-cannot-persist-definition-namespace](docs/seon/issues/dynamic-in-ns-cannot-persist-definition-namespace.md), [loop-settlement-consumer-reads-a-key-no-producer-writes](docs/seon/issues/loop-settlement-consumer-reads-a-key-no-producer-writes.md), [render-token-budgets-are-private-dials-no-producer-supplies](docs/seon/issues/render-token-budgets-are-private-dials-no-producer-supplies.md), [schedule-graph-test-constructs-a-handle-without-an-environment](docs/seon/issues/schedule-graph-test-constructs-a-handle-without-an-environment.md), [system-generated-messages-omit-arrival-ordinals](docs/seon/issues/system-generated-messages-omit-arrival-ordinals.md), [web-config-dials-ship-without-shipped-defaults](docs/seon/issues/web-config-dials-ship-without-shipped-defaults.md).

### P4 — tap after source/resume

[fault-committer-misses-the-first-injected-fault](docs/seon/issues/fault-committer-misses-the-first-injected-fault.md), [graph-construction-leaves-tap-before-resume-to-callers](docs/seon/issues/graph-construction-leaves-tap-before-resume-to-callers.md).

### P5 — hand-copied pins

[malli-vendor-is-ahead-of-pinned-dependency](docs/seon/issues/malli-vendor-is-ahead-of-pinned-dependency.md), [vendored-transit-clj-drifts-from-the-pinned-artifact](docs/seon/issues/vendored-transit-clj-drifts-from-the-pinned-artifact.md).

## Ranked new class-kill queue

| Rank | Class issue | Open closed | Observations / 16 days | Cost midpoint | Score | Structural kill |
|---:|---|---:|---:|---:|---:|---|
| 1 | [N1 — total outward render contract](docs/seon/issues/class-outward-values-bypass-total-render-contract.md) | 34 | 41 / 16 | 6 d | 14.52 | One total rendered-value/omission/error construction; required producers, bounded terminal output, counted fallback. |
| 2 | [N5 — preserve diagnostic evidence](docs/seon/issues/class-diagnostics-collapse-evidence-into-noise-or-absence.md) | 11 | 14 / 16 | 2 d | 4.81 | One evidence-complete flat constructor; typed unknown; diagnostic uses the transition’s query. |
| 3 | [N11 — reject readerless/duplicate mechanisms](docs/seon/issues/class-readerless-duplicate-mechanisms-survive-cuts.md) | 9 | 10 / 16 | 2 d | 2.81 | Publication derives reader closure and one-owner reachability; no alternate path is constructible. |
| 4 | [N2 — non-vacuous proofs](docs/seon/issues/class-proofs-pass-without-exercising-their-premise.md) | 7 | 13 / 16 | 3 d | 1.90 | Production constructors/queries supply nonempty complete subjects; every property retains a counterexample. |
| 5 | [N7 — query-derived classification](docs/seon/issues/class-classification-is-inferred-from-hand-lists.md) | 8 | 9 / 16 | 2.5 d | 1.80 | Record the missing fact, then query it; constructors accept no roster/prefix/count. |
| 6 | [N4 — explicit resource root/lifetime](docs/seon/issues/class-mutable-resources-lack-explicit-root-and-lifetime.md) | 9 | 13 / 16 | 4.5 d | 1.63 | Ownership value carries root, resource, child completions, and release; borrowers cannot reopen. |
| 7 | [N8 — fact-owned domain order](docs/seon/issues/class-domain-order-falls-through-to-strings-and-hashes.md) | 4 | 4 / 16 | 1.5 d | 0.67 | Carry numeric/tuple/transaction order through the final window; identifiers never break ties. |
| 8 | [N9 — incremental derived work](docs/seon/issues/class-local-updates-recompute-global-projections.md) | 6 | 7 / 16 | 5 d | 0.53 | Projection rides basis/generation and invalidates only the recorded dependency closure. |
| 9 | [N6 — registered contract identities](docs/seon/issues/class-anonymous-contracts-cannot-survive-publication.md) | 4 | 4 / 16 | 2 d | 0.50 | Durable contracts have no field for anonymous or unregistered schema identities. |
| 10 | [N12 — executable contract documentation](docs/seon/issues/class-documentation-restates-executable-contracts.md) | 4 | 4 / 16 | 2 d | 0.50 | Request shapes and examples render from installed schemas; prose cannot restate lifecycle/keys. |
| 11 | [N13 — dependency boundary translation](docs/seon/issues/class-dependency-representations-leak-past-boundaries.md) | 4 | 10 / 16 | 5 d | 0.50 | Translate once through maintained dependency protocols/schema; consumers cannot inspect host layout. |
| 12 | [N3 — loaded artifact identity](docs/seon/issues/class-loaded-artifacts-lack-source-identity.md) | 5 | 5 / 16 | 4 d | 0.39 | Every loaded artifact carries source/dependency digest; dependency-derived reload refuses mixed generations. |
| 13 | [N10 — total terminal evidence](docs/seon/issues/class-accepted-work-can-end-without-terminal-evidence.md) | 4 | 9 / 16 | 6 d | 0.38 | Accepted-work construction requires identity and terminal publisher; no close/interrupt without one fact. |
| 14 | [N14 — atomic reachability changes](docs/seon/issues/class-destructive-reachability-changes-are-not-atomic.md) | 2 | 5 / 16 | 5.5 d | 0.11 | Replacement and collection share atomic head/reachability owners; failure preserves the prior reachable state. |

The “open closed” column is the exact current member set that the class issue
links. Closing a class issue requires closing or reclassifying every one of
those member notes; it is not an umbrella that hides unfinished instances.

## Complete 184-note classification ledger

Each row cites the member issue that supports its primary membership claim.

| Open issue | Primary class |
|---|---|
| [a-failed-turn-wakes-itself-through-its-own-fault-message](docs/seon/issues/a-failed-turn-wakes-itself-through-its-own-fault-message.md) | singleton |
| [a-mid-stream-provider-disconnect-discards-the-whole-turn](docs/seon/issues/a-mid-stream-provider-disconnect-discards-the-whole-turn.md) | N10 — accepted work lacks terminal evidence |
| [a-never-run-agents-context-cannot-be-inspected](docs/seon/issues/a-never-run-agents-context-cannot-be-inspected.md) | N5 — diagnostic evidence collapses |
| [a-run-pays-two-and-a-half-seconds-between-every-form](docs/seon/issues/a-run-pays-two-and-a-half-seconds-between-every-form.md) | N9 — local update recomputes global projection |
| [a-runs-last-form-can-close-without-a-receipt](docs/seon/issues/a-runs-last-form-can-close-without-a-receipt.md) | N10 — accepted work lacks terminal evidence |
| [a-schema-resource-edit-bricks-value-admission-in-every-running-cluster](docs/seon/issues/a-schema-resource-edit-bricks-value-admission-in-every-running-cluster.md) | N3 — loaded artifact lacks source identity |
| [a-search-contract-predicate-cannot-be-made-durable](docs/seon/issues/a-search-contract-predicate-cannot-be-made-durable.md) | N6 — contract schema lacks registered identity |
| [a-six-word-eval-error-renders-as-two-thousand-characters](docs/seon/issues/a-six-word-eval-error-renders-as-two-thousand-characters.md) | N1 — outward value bypasses total render contract |
| [a-wrong-arity-call-reports-a-missing-namespace](docs/seon/issues/a-wrong-arity-call-reports-a-missing-namespace.md) | N5 — diagnostic evidence collapses |
| [acquire-has-no-per-row-containment](docs/seon/issues/acquire-has-no-per-row-containment.md) | singleton |
| [activation-closure-records-no-schema-keys](docs/seon/issues/activation-closure-records-no-schema-keys.md) | P3 — consumer reads a key no producer writes |
| [admit-inst-overlap-prefers-collection-shape](docs/seon/issues/admit-inst-overlap-prefers-collection-shape.md) | N13 — dependency representation leaks inward |
| [agent-form-calls-to-core-namespaces-are-not-indexed](docs/seon/issues/agent-form-calls-to-core-namespaces-are-not-indexed.md) | N7 — classification comes from a hand list |
| [agent-pages-overflow-a-phone-viewport](docs/seon/issues/agent-pages-overflow-a-phone-viewport.md) | N1 — outward value bypasses total render contract |
| [agent-repl-cannot-require-clojure-pprint](docs/seon/issues/agent-repl-cannot-require-clojure-pprint.md) | singleton |
| [ai-context-bypasses-render-proc-retained-bytes](docs/seon/issues/ai-context-bypasses-render-proc-retained-bytes.md) | N9 — local update recomputes global projection |
| [ai-retry-proof-still-cites-the-deleted-run-lease](docs/seon/issues/ai-retry-proof-still-cites-the-deleted-run-lease.md) | N12 — documentation restates executable contract |
| [ai-transport-taxonomy-test-can-run-zero-assertions](docs/seon/issues/ai-transport-taxonomy-test-can-run-zero-assertions.md) | N2 — proof can pass without premise |
| [an-inline-fn-predicate-in-src-refuses-every-corpus-projection](docs/seon/issues/an-inline-fn-predicate-in-src-refuses-every-corpus-projection.md) | N6 — contract schema lacks registered identity |
| [an-unmatched-print-face-throws-no-matching-clause-and-names-nothing](docs/seon/issues/an-unmatched-print-face-throws-no-matching-clause-and-names-nothing.md) | N1 — outward value bypasses total render contract |
| [anonymous-runtime-contracts-have-recurred](docs/seon/issues/anonymous-runtime-contracts-have-recurred.md) | N6 — contract schema lacks registered identity |
| [artifact-releases-the-fence-between-install-and-start](docs/seon/issues/artifact-releases-the-fence-between-install-and-start.md) | N4 — mutable resource lacks root/lifetime |
| [background-binary-settlement-does-not-publish-required-event](docs/seon/issues/background-binary-settlement-does-not-publish-required-event.md) | N10 — accepted work lacks terminal evidence |
| [blob-get-assumes-file-store-callback-shape](docs/seon/issues/blob-get-assumes-file-store-callback-shape.md) | N13 — dependency representation leaks inward |
| [boot-refusal-has-no-render-producer](docs/seon/issues/boot-refusal-has-no-render-producer.md) | N1 — outward value bypasses total render contract |
| [bootstrap-o4-stops-before-causal-delegation-settles](docs/seon/issues/bootstrap-o4-stops-before-causal-delegation-settles.md) | N2 — proof can pass without premise |
| [bootstrap-plan-forms-ship-unsubstituted-namespace-placeholders](docs/seon/issues/bootstrap-plan-forms-ship-unsubstituted-namespace-placeholders.md) | singleton |
| [bootstrap-teaches-bare-map-keys](docs/seon/issues/bootstrap-teaches-bare-map-keys.md) | N12 — documentation restates executable contract |
| [bootstrap-teaching-failures-strand-every-new-agent](docs/seon/issues/bootstrap-teaching-failures-strand-every-new-agent.md) | singleton |
| [changed-test-report-is-one-enormous-line](docs/seon/issues/changed-test-report-is-one-enormous-line.md) | N1 — outward value bypasses total render contract |
| [changed-test-selector-classifies-hosts-by-path-prefix](docs/seon/issues/changed-test-selector-classifies-hosts-by-path-prefix.md) | N7 — classification comes from a hand list |
| [cluster-config-and-bootstrap-plan-render-as-raw-maps](docs/seon/issues/cluster-config-and-bootstrap-plan-render-as-raw-maps.md) | N1 — outward value bypasses total render contract |
| [cluster-toolkit-stores-a-prefix-derived-projection](docs/seon/issues/cluster-toolkit-stores-a-prefix-derived-projection.md) | N7 — classification comes from a hand list |
| [cohosted-clusters-share-one-unbounded-agent-heap](docs/seon/issues/cohosted-clusters-share-one-unbounded-agent-heap.md) | singleton |
| [cohosted-second-boot-is-slow-and-trips-the-silence-backstop](docs/seon/issues/cohosted-second-boot-is-slow-and-trips-the-silence-backstop.md) | P2 — clocks stand in for observable events |
| [collection-render-drops-209-of-210-results-without-an-elision-value](docs/seon/issues/collection-render-drops-209-of-210-results-without-an-elision-value.md) | N1 — outward value bypasses total render contract |
| [complete-publication-takes-seventy-seconds](docs/seon/issues/complete-publication-takes-seventy-seconds.md) | N9 — local update recomputes global projection |
| [concurrent-eval-test-calibrates-interpreted-work-to-wall-time](docs/seon/issues/concurrent-eval-test-calibrates-interpreted-work-to-wall-time.md) | P2 — clocks stand in for observable events |
| [concurrent-provider-calls-fail-with-a-closed-response-body](docs/seon/issues/concurrent-provider-calls-fail-with-a-closed-response-body.md) | N4 — mutable resource lacks root/lifetime |
| [config-ai-request-idents-are-derived-by-string-surgery](docs/seon/issues/config-ai-request-idents-are-derived-by-string-surgery.md) | N7 — classification comes from a hand list |
| [config-dial-discovery-has-three-authorities](docs/seon/issues/config-dial-discovery-has-three-authorities.md) | N7 — classification comes from a hand list |
| [context-capture-prompts-bypass-the-blob-splitter](docs/seon/issues/context-capture-prompts-bypass-the-blob-splitter.md) | N11 — readerless/duplicate mechanism survives |
| [context-mvp-drive-can-false-green-after-cross-agent-delivery](docs/seon/issues/context-mvp-drive-can-false-green-after-cross-agent-delivery.md) | N2 — proof can pass without premise |
| [context-wave-leaves-three-small-honesty-defects](docs/seon/issues/context-wave-leaves-three-small-honesty-defects.md) | singleton |
| [contract-violation-serializes-print-tree-inside-error-data](docs/seon/issues/contract-violation-serializes-print-tree-inside-error-data.md) | N1 — outward value bypasses total render contract |
| [contracted-defn-rebuilds-the-whole-schema-projection](docs/seon/issues/contracted-defn-rebuilds-the-whole-schema-projection.md) | N9 — local update recomputes global projection |
| [core-namespace-pages-spend-seven-seconds-without-declaration-fallbacks](docs/seon/issues/core-namespace-pages-spend-seven-seconds-without-declaration-fallbacks.md) | N9 — local update recomputes global projection |
| [data-page-takes-five-and-a-half-seconds-for-three-kilobytes](docs/seon/issues/data-page-takes-five-and-a-half-seconds-for-three-kilobytes.md) | singleton |
| [database-read-admission-treats-invalid-identities-as-absence](docs/seon/issues/database-read-admission-treats-invalid-identities-as-absence.md) | N5 — diagnostic evidence collapses |
| [database-request-shape-errors-bypass-public-contracts](docs/seon/issues/database-request-shape-errors-bypass-public-contracts.md) | N5 — diagnostic evidence collapses |
| [database-values-render-as-opaque-host-objects-in-html](docs/seon/issues/database-values-render-as-opaque-host-objects-in-html.md) | N1 — outward value bypasses total render contract |
| [datahike-allocates-a-konserve-cache-it-never-reads](docs/seon/issues/datahike-allocates-a-konserve-cache-it-never-reads.md) | N11 — readerless/duplicate mechanism survives |
| [datahike-fork-is-28-commits-behind-upstream](docs/seon/issues/datahike-fork-is-28-commits-behind-upstream.md) | singleton |
| [debug-left-pane-is-not-the-exact-prompt](docs/seon/issues/debug-left-pane-is-not-the-exact-prompt.md) | N1 — outward value bypasses total render contract |
| [debug-pages-invent-wedged-runs](docs/seon/issues/debug-pages-invent-wedged-runs.md) | N5 — diagnostic evidence collapses |
| [debug-pages-receive-block-patches-for-elements-they-do-not-have](docs/seon/issues/debug-pages-receive-block-patches-for-elements-they-do-not-have.md) | N1 — outward value bypasses total render contract |
| [deletable-directories-have-no-claim-or-size-facts](docs/seon/issues/deletable-directories-have-no-claim-or-size-facts.md) | N4 — mutable resource lacks root/lifetime |
| [dependency-class-cache-prepare-races-concurrent-jvm-launches](docs/seon/issues/dependency-class-cache-prepare-races-concurrent-jvm-launches.md) | N4 — mutable resource lacks root/lifetime |
| [dev-mcp-envelopes-misdirect-errors-and-sprawl-status](docs/seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md) | N5 — diagnostic evidence collapses |
| [duplicate-identity-refusal-evidence-is-unordered](docs/seon/issues/duplicate-identity-refusal-evidence-is-unordered.md) | N8 — domain order falls through to strings/hashes |
| [dynamic-in-ns-cannot-persist-definition-namespace](docs/seon/issues/dynamic-in-ns-cannot-persist-definition-namespace.md) | P3 — consumer reads a key no producer writes |
| [effect-context-suffix-returns-comment-notices](docs/seon/issues/effect-context-suffix-returns-comment-notices.md) | N1 — outward value bypasses total render contract |
| [effect-feedback-orders-receipts-by-id](docs/seon/issues/effect-feedback-orders-receipts-by-id.md) | N8 — domain order falls through to strings/hashes |
| [effect-receipts-have-no-render-producers](docs/seon/issues/effect-receipts-have-no-render-producers.md) | N1 — outward value bypasses total render contract |
| [error-class-catalog-and-renderers-disagree](docs/seon/issues/error-class-catalog-and-renderers-disagree.md) | N11 — readerless/duplicate mechanism survives |
| [eval-drives-duplicate-a-four-minute-run-clock](docs/seon/issues/eval-drives-duplicate-a-four-minute-run-clock.md) | P2 — clocks stand in for observable events |
| [eval-samples-cost-42mb-of-store-each](docs/seon/issues/eval-samples-cost-42mb-of-store-each.md) | singleton |
| [every-agent-prompt-is-a-neighborhood-render-walk-contract-violation](docs/seon/issues/every-agent-prompt-is-a-neighborhood-render-walk-contract-violation.md) | N1 — outward value bypasses total render contract |
| [every-background-capability-request-loses-its-connection](docs/seon/issues/every-background-capability-request-loses-its-connection.md) | P1 — ambient reach-sideways state |
| [expected-refusal-logs-raw-datom-error-twice](docs/seon/issues/expected-refusal-logs-raw-datom-error-twice.md) | N1 — outward value bypasses total render contract |
| [fault-committer-misses-the-first-injected-fault](docs/seon/issues/fault-committer-misses-the-first-injected-fault.md) | P4 — tap after source/resume |
| [file-store-commits-pay-five-times-the-fsyncs-they-need](docs/seon/issues/file-store-commits-pay-five-times-the-fsyncs-they-need.md) | singleton |
| [flow-config-dials-have-two-registration-owners](docs/seon/issues/flow-config-dials-have-two-registration-owners.md) | N11 — readerless/duplicate mechanism survives |
| [flow-has-no-read-set-control-and-a-hand-rolled-egress](docs/seon/issues/flow-has-no-read-set-control-and-a-hand-rolled-egress.md) | N11 — readerless/duplicate mechanism survives |
| [flow-monitor-test-resources-outlive-their-cleanup-scope](docs/seon/issues/flow-monitor-test-resources-outlive-their-cleanup-scope.md) | N4 — mutable resource lacks root/lifetime |
| [flow-work-launcher-graph-omits-its-root-io-executor](docs/seon/issues/flow-work-launcher-graph-omits-its-root-io-executor.md) | P1 — ambient reach-sideways state |
| [foreign-write-fence-reads-only-the-dynamic-var](docs/seon/issues/foreign-write-fence-reads-only-the-dynamic-var.md) | P1 — ambient reach-sideways state |
| [fresh-cljc-files-are-jvm-only](docs/seon/issues/fresh-cljc-files-are-jvm-only.md) | singleton |
| [give-offline-roster-discovery-a-current-read-only-helper](docs/seon/issues/give-offline-roster-discovery-a-current-read-only-helper.md) | singleton |
| [graph-construction-leaves-tap-before-resume-to-callers](docs/seon/issues/graph-construction-leaves-tap-before-resume-to-callers.md) | P4 — tap after source/resume |
| [history-policy-refusal-test-is-load-flaky](docs/seon/issues/history-policy-refusal-test-is-load-flaky.md) | P1 — ambient reach-sideways state |
| [host-bound-first-party-vars-break-in-value-position](docs/seon/issues/host-bound-first-party-vars-break-in-value-position.md) | singleton |
| [hyperlith-pin-behind-lockstep-rework](docs/seon/issues/hyperlith-pin-behind-lockstep-rework.md) | singleton |
| [init-failure-dumps-entire-prepl-event-history](docs/seon/issues/init-failure-dumps-entire-prepl-event-history.md) | N1 — outward value bypasses total render contract |
| [initial-paint-census-is-a-hand-maintained-count](docs/seon/issues/initial-paint-census-is-a-hand-maintained-count.md) | N7 — classification comes from a hand list |
| [instrumentation-compiles-under-one-clusters-projection](docs/seon/issues/instrumentation-compiles-under-one-clusters-projection.md) | P1 — ambient reach-sideways state |
| [instrumentation-headline-unbounded-when-caps-absent](docs/seon/issues/instrumentation-headline-unbounded-when-caps-absent.md) | N1 — outward value bypasses total render contract |
| [interrupted-blob-staging-leaves-no-observable-artifact](docs/seon/issues/interrupted-blob-staging-leaves-no-observable-artifact.md) | N10 — accepted work lacks terminal evidence |
| [jvm-operator-work-takes-the-installation-lock-for-one-root](docs/seon/issues/jvm-operator-work-takes-the-installation-lock-for-one-root.md) | N4 — mutable resource lacks root/lifetime |
| [keep-history-is-on-by-default-without-a-decision](docs/seon/issues/keep-history-is-on-by-default-without-a-decision.md) | singleton |
| [latest-closed-run-orders-by-id-string](docs/seon/issues/latest-closed-run-orders-by-id-string.md) | N8 — domain order falls through to strings/hashes |
| [live-publication-has-a-hand-maintained-predicate-owner-reload](docs/seon/issues/live-publication-has-a-hand-maintained-predicate-owner-reload.md) | N3 — loaded artifact lacks source identity |
| [loop-settlement-consumer-reads-a-key-no-producer-writes](docs/seon/issues/loop-settlement-consumer-reads-a-key-no-producer-writes.md) | P3 — consumer reads a key no producer writes |
| [malformed-sse-data-can-change-agent-code](docs/seon/issues/malformed-sse-data-can-change-agent-code.md) | singleton |
| [malli-form-predicate-resolves-the-declaration-population-itself](docs/seon/issues/malli-form-predicate-resolves-the-declaration-population-itself.md) | P1 — ambient reach-sideways state |
| [malli-registration-errors-hide-the-offending-var](docs/seon/issues/malli-registration-errors-hide-the-offending-var.md) | N5 — diagnostic evidence collapses |
| [malli-vendor-is-ahead-of-pinned-dependency](docs/seon/issues/malli-vendor-is-ahead-of-pinned-dependency.md) | P5 — hand-copied pins |
| [map-unions-have-no-explicit-discriminants](docs/seon/issues/map-unions-have-no-explicit-discriminants.md) | singleton |
| [mcp-parent-watchdog-can-follow-a-reused-pid](docs/seon/issues/mcp-parent-watchdog-can-follow-a-reused-pid.md) | singleton |
| [mcp-projection-crashes-on-non-keyword-map-keys](docs/seon/issues/mcp-projection-crashes-on-non-keyword-map-keys.md) | N1 — outward value bypasses total render contract |
| [message-completion-replies-from-the-wrong-agent-and-duplicates-the-trigger](docs/seon/issues/message-completion-replies-from-the-wrong-agent-and-duplicates-the-trigger.md) | singleton |
| [monitor-graph-command-proc-throws](docs/seon/issues/monitor-graph-command-proc-throws.md) | N11 — readerless/duplicate mechanism survives |
| [my-background-poll-costs-290-tokens-per-polled-result](docs/seon/issues/my-background-poll-costs-290-tokens-per-polled-result.md) | N1 — outward value bypasses total render contract |
| [my-fs-write-docstring-hides-its-own-request-shape](docs/seon/issues/my-fs-write-docstring-hides-its-own-request-shape.md) | N12 — documentation restates executable contract |
| [my-web-fetch-returns-plain-html-as-a-vector-of-integers](docs/seon/issues/my-web-fetch-returns-plain-html-as-a-vector-of-integers.md) | N1 — outward value bypasses total render contract |
| [namespace-binding-targets-are-symbols-not-refs](docs/seon/issues/namespace-binding-targets-are-symbols-not-refs.md) | singleton |
| [namespace-removal-does-not-rebuild-contracted-only](docs/seon/issues/namespace-removal-does-not-rebuild-contracted-only.md) | singleton |
| [namespace-renderer-encodes-results-as-comments](docs/seon/issues/namespace-renderer-encodes-results-as-comments.md) | N1 — outward value bypasses total render contract |
| [namespace-units-render-error-schema-boilerplate](docs/seon/issues/namespace-units-render-error-schema-boilerplate.md) | N1 — outward value bypasses total render contract |
| [negative-import-masks-escape-static-admission](docs/seon/issues/negative-import-masks-escape-static-admission.md) | singleton |
| [nested-error-data-hides-the-throw-site-message](docs/seon/issues/nested-error-data-hides-the-throw-site-message.md) | N5 — diagnostic evidence collapses |
| [nested-map-sequences-render-as-tables-inside-structural-values](docs/seon/issues/nested-map-sequences-render-as-tables-inside-structural-values.md) | N1 — outward value bypasses total render contract |
| [object-identity-addresses-break-prompt-prefix-stability](docs/seon/issues/object-identity-addresses-break-prompt-prefix-stability.md) | N1 — outward value bypasses total render contract |
| [observable-graph-transitions-are-polled-in-tests](docs/seon/issues/observable-graph-transitions-are-polled-in-tests.md) | P2 — clocks stand in for observable events |
| [one-identity-string-names-two-entities](docs/seon/issues/one-identity-string-names-two-entities.md) | singleton |
| [opaque-contract-generators-share-live-process-objects](docs/seon/issues/opaque-contract-generators-share-live-process-objects.md) | P1 — ambient reach-sideways state |
| [operator-classifies-processes-by-command-substrings](docs/seon/issues/operator-classifies-processes-by-command-substrings.md) | N7 — classification comes from a hand list |
| [operator-subprocesses-have-unbounded-read-and-wait-paths](docs/seon/issues/operator-subprocesses-have-unbounded-read-and-wait-paths.md) | singleton |
| [output-sink-query-excludes-operator-and-mcp-scripts](docs/seon/issues/output-sink-query-excludes-operator-and-mcp-scripts.md) | N2 — proof can pass without premise |
| [oversight-fleet-test-pins-a-stale-proc-roster](docs/seon/issues/oversight-fleet-test-pins-a-stale-proc-roster.md) | N2 — proof can pass without premise |
| [oversight-treats-a-20ms-ping-absence-as-state](docs/seon/issues/oversight-treats-a-20ms-ping-absence-as-state.md) | P2 — clocks stand in for observable events |
| [parallel-test-stress-exposes-eleven-isolation-sensitive-tests](docs/seon/issues/parallel-test-stress-exposes-eleven-isolation-sensitive-tests.md) | singleton |
| [partial-hot-reload-produces-mixed-code-with-no-warning](docs/seon/issues/partial-hot-reload-produces-mixed-code-with-no-warning.md) | N3 — loaded artifact lacks source identity |
| [posh-cardinality-one-pull-analysis-has-an-arity-defect](docs/seon/issues/posh-cardinality-one-pull-analysis-has-an-arity-defect.md) | singleton |
| [pre-rename-root-claims-are-unreadable-noise-on-every-status](docs/seon/issues/pre-rename-root-claims-are-unreadable-noise-on-every-status.md) | N1 — outward value bypasses total render contract |
| [predicate-schema-violations-humanize-to-unknown-error](docs/seon/issues/predicate-schema-violations-humanize-to-unknown-error.md) | N5 — diagnostic evidence collapses |
| [production-docstrings-teach-deleted-semantics](docs/seon/issues/production-docstrings-teach-deleted-semantics.md) | N12 — documentation restates executable contract |
| [provider-descriptor-overwrites-per-agent-credential-selection](docs/seon/issues/provider-descriptor-overwrites-per-agent-credential-selection.md) | singleton |
| [provider-output-token-wire-key-is-hard-coded](docs/seon/issues/provider-output-token-wire-key-is-hard-coded.md) | singleton |
| [public-contract-census-can-pass-with-no-subjects](docs/seon/issues/public-contract-census-can-pass-with-no-subjects.md) | N2 — proof can pass without premise |
| [publication-reload-hand-lists-namespaces-and-misses-dependencies](docs/seon/issues/publication-reload-hand-lists-namespaces-and-misses-dependencies.md) | N3 — loaded artifact lacks source identity |
| [ranged-store-collection-can-delete-live-segments-via-branch-resurrection](docs/seon/issues/ranged-store-collection-can-delete-live-segments-via-branch-resurrection.md) | N14 — destructive reachability change is non-atomic |
| [render-adversarial-roots-outlive-their-experiment](docs/seon/issues/render-adversarial-roots-outlive-their-experiment.md) | N4 — mutable resource lacks root/lifetime |
| [render-live-proof-roots-have-no-lifecycle-owner](docs/seon/issues/render-live-proof-roots-have-no-lifecycle-owner.md) | N4 — mutable resource lacks root/lifetime |
| [render-package-proc-reruns-unchanged-renderers](docs/seon/issues/render-package-proc-reruns-unchanged-renderers.md) | N9 — local update recomputes global projection |
| [render-token-budgets-are-private-dials-no-producer-supplies](docs/seon/issues/render-token-budgets-are-private-dials-no-producer-supplies.md) | P3 — consumer reads a key no producer writes |
| [render-value-floor-refuses-any-map-with-unqualified-keys](docs/seon/issues/render-value-floor-refuses-any-map-with-unqualified-keys.md) | N1 — outward value bypasses total render contract |
| [render-walk-frames-values-as-comments](docs/seon/issues/render-walk-frames-values-as-comments.md) | N1 — outward value bypasses total render contract |
| [render-walk-maintains-a-derived-edge-hand-list](docs/seon/issues/render-walk-maintains-a-derived-edge-hand-list.md) | N7 — classification comes from a hand list |
| [render-walk-wrapper-returns-comment-notices](docs/seon/issues/render-walk-wrapper-returns-comment-notices.md) | N1 — outward value bypasses total render contract |
| [render-wave-properties-cannot-produce-their-failing-cases](docs/seon/issues/render-wave-properties-cannot-produce-their-failing-cases.md) | N2 — proof can pass without premise |
| [repl-parity-divergences](docs/seon/issues/repl-parity-divergences.md) | singleton |
| [root-compute-executor-has-no-per-cluster-fairness](docs/seon/issues/root-compute-executor-has-no-per-cluster-fairness.md) | singleton |
| [run-renderer-narrates-forms-and-receipts](docs/seon/issues/run-renderer-narrates-forms-and-receipts.md) | N1 — outward value bypasses total render contract |
| [runtime-lint-does-not-resolve-namespace-aliases](docs/seon/issues/runtime-lint-does-not-resolve-namespace-aliases.md) | singleton |
| [runtime-turn-and-evaluate-kernels-conflate-boundaries](docs/seon/issues/runtime-turn-and-evaluate-kernels-conflate-boundaries.md) | singleton |
| [schedule-graph-test-constructs-a-handle-without-an-environment](docs/seon/issues/schedule-graph-test-constructs-a-handle-without-an-environment.md) | P3 — consumer reads a key no producer writes |
| [schema-datahike-keeps-a-readerless-second-codec](docs/seon/issues/schema-datahike-keeps-a-readerless-second-codec.md) | N11 — readerless/duplicate mechanism survives |
| [schema-declaration-rebuilds-four-gigabytes-per-form](docs/seon/issues/schema-declaration-rebuilds-four-gigabytes-per-form.md) | P1 — ambient reach-sideways state |
| [schema-environment-is-ambient-not-explicit](docs/seon/issues/schema-environment-is-ambient-not-explicit.md) | P1 — ambient reach-sideways state |
| [schema-exact-reuse-warnings-are-unreadable-at-volume](docs/seon/issues/schema-exact-reuse-warnings-are-unreadable-at-volume.md) | N1 — outward value bypasses total render contract |
| [schema-guard-refuses-accretive-loosenings-with-data](docs/seon/issues/schema-guard-refuses-accretive-loosenings-with-data.md) | singleton |
| [schema-map-extraction-still-depends-on-position-two](docs/seon/issues/schema-map-extraction-still-depends-on-position-two.md) | N13 — dependency representation leaks inward |
| [schema-population-retains-five-readerless-rows](docs/seon/issues/schema-population-retains-five-readerless-rows.md) | N11 — readerless/duplicate mechanism survives |
| [schema-source-provenance-accumulates-in-a-global-atom](docs/seon/issues/schema-source-provenance-accumulates-in-a-global-atom.md) | P1 — ambient reach-sideways state |
| [sci-analysis-ex-data-carries-a-symbol-nothing-reads](docs/seon/issues/sci-analysis-ex-data-carries-a-symbol-nothing-reads.md) | N5 — diagnostic evidence collapses |
| [sci-reader-hides-a-production-source-cap](docs/seon/issues/sci-reader-hides-a-production-source-cap.md) | singleton |
| [search-index-property-collides-with-process-index-id](docs/seon/issues/search-index-property-collides-with-process-index-id.md) | singleton |
| [secondary-only-attributes-have-no-covering-index](docs/seon/issues/secondary-only-attributes-have-no-covering-index.md) | singleton |
| [seon-db-has-no-branch-or-commit-reads](docs/seon/issues/seon-db-has-no-branch-or-commit-reads.md) | singleton |
| [settle-is-public-without-a-complete-contract](docs/seon/issues/settle-is-public-without-a-complete-contract.md) | singleton |
| [shared-context-session-delta-crosses-run-attribution](docs/seon/issues/shared-context-session-delta-crosses-run-attribution.md) | P1 — ambient reach-sideways state |
| [source-load-is-118s-against-the-ten-second-law](docs/seon/issues/source-load-is-118s-against-the-ten-second-law.md) | singleton |
| [stale-language-specific-kondo-cache-blocks-correct-code](docs/seon/issues/stale-language-specific-kondo-cache-blocks-correct-code.md) | N3 — loaded artifact lacks source identity |
| [status-floods-unreadable-external-claim-warnings](docs/seon/issues/status-floods-unreadable-external-claim-warnings.md) | N1 — outward value bypasses total render contract |
| [status-reports-a-live-mcp-proven-prepl-unreachable](docs/seon/issues/status-reports-a-live-mcp-proven-prepl-unreachable.md) | N5 — diagnostic evidence collapses |
| [storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing](docs/seon/issues/storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing.md) | N14 — destructive reachability change is non-atomic |
| [system-generated-messages-omit-arrival-ordinals](docs/seon/issues/system-generated-messages-omit-arrival-ordinals.md) | P3 — consumer reads a key no producer writes |
| [terminal-refusal-error-fact-fails-on-oversized-data](docs/seon/issues/terminal-refusal-error-fact-fails-on-oversized-data.md) | singleton |
| [test-runner-cleans-a-worker-root-while-kondo-is-still-writing](docs/seon/issues/test-runner-cleans-a-worker-root-while-kondo-is-still-writing.md) | N4 — mutable resource lacks root/lifetime |
| [thinking-tool-continuations-have-no-faithful-request-shape](docs/seon/issues/thinking-tool-continuations-have-no-faithful-request-shape.md) | singleton |
| [time-limit-face-exposes-interpreter-interrupt-marker](docs/seon/issues/time-limit-face-exposes-interpreter-interrupt-marker.md) | N1 — outward value bypasses total render contract |
| [transcript-about-lookup-passes-a-set-to-pull-many](docs/seon/issues/transcript-about-lookup-passes-a-set-to-pull-many.md) | N13 — dependency representation leaks inward |
| [transcript-candidate-window-orders-receipts-and-comments-by-id](docs/seon/issues/transcript-candidate-window-orders-receipts-and-comments-by-id.md) | N8 — domain order falls through to strings/hashes |
| [transcript-renderer-encodes-entries-as-comment-forms](docs/seon/issues/transcript-renderer-encodes-entries-as-comment-forms.md) | N1 — outward value bypasses total render contract |
| [unindexed-namespaces-render-as-empty](docs/seon/issues/unindexed-namespaces-render-as-empty.md) | N1 — outward value bypasses total render contract |
| [unlogged-findings-2026-08-01](docs/seon/issues/unlogged-findings-2026-08-01.md) | singleton |
| [unregistered-ifn-malli-schema-breaks-source-publication](docs/seon/issues/unregistered-ifn-malli-schema-breaks-source-publication.md) | N6 — contract schema lacks registered identity |
| [value-admission-resolves-the-declaration-population-per-node](docs/seon/issues/value-admission-resolves-the-declaration-population-per-node.md) | P1 — ambient reach-sideways state |
| [value-floor-residue-duplicate-cursors-and-marker-hand-lists](docs/seon/issues/value-floor-residue-duplicate-cursors-and-marker-hand-lists.md) | N11 — readerless/duplicate mechanism survives |
| [vendored-transit-clj-drifts-from-the-pinned-artifact](docs/seon/issues/vendored-transit-clj-drifts-from-the-pinned-artifact.md) | P5 — hand-copied pins |
| [web-config-dials-ship-without-shipped-defaults](docs/seon/issues/web-config-dials-ship-without-shipped-defaults.md) | P3 — consumer reads a key no producer writes |
| [within-run-schema-key-refinement-needs-an-owner-ruling](docs/seon/issues/within-run-schema-key-refinement-needs-an-owner-ruling.md) | singleton |
| [work-submission-can-block-before-its-time-limit](docs/seon/issues/work-submission-can-block-before-its-time-limit.md) | singleton |

The 45 singleton rows include unresolved owner rulings,
dependency-specific defects, performance findings without a shared
construction, compound notes, and broad umbrella audits. They are deliberately
excluded from class scores.

## Calibration: what is genuinely in good shape

The recent archive shows several narrow classes that are now structurally dead,
even though broader neighboring classes remain open:

- **Copied current-pin statements:** on 2026-08-11 the pin gate derived all 107
  gitlinks, checked tracked documentation and skills, and repaired 607 stale
  claims. [The closed recurrence](docs/seon/issues/archive/datahike-current-pin-statements-drifted-again.md)
  now has a repository-wide falsifier. Actual vendored/artifact divergence
  remains separately open under P5.
- **Force-refork destroy-then-fail:** the 2026-08-11 closure
  [preserves the old branch on injected failure](docs/seon/issues/archive/force-refork-can-destroy-the-branch-then-fail-silently-leaving-no-cluster.md)
  through expected-head atomic replacement. This does not close N14’s GC/blob
  reachability members.
- **Unreadable reply with no trace:** the 2026-08-10 closure
  [commits source plus ordinal-zero receipt](docs/seon/issues/archive/an-unreadable-reply-closes-a-run-with-no-forms-and-no-trace.md);
  recovery and the next prompt query the same durable refusal.
- **Operator-root lock isolation:** the 2026-08-07 finding
  [that an isolated root locked the shared repository root](docs/seon/issues/archive/an-isolated-operator-root-locks-the-shared-repository-root.md)
  is closed by deriving the lock from the selected root and a cross-root live
  proof.
- **Automatic component pull evidence:** the 2026-08-11 dependency-boundary
  finding [is closed at the maintained Datahike fork](docs/seon/issues/archive/datahike-pull-evidence-misses-automatic-component-expansion.md)
  with dependency and Seon regressions.
- **Fact-only restoring the agent's defs:** the 2026-08-11 closure
  [restores definitions and atom state without authored-source evaluation](docs/seon/issues/archive/agent-definition-restore-reexecutes-authored-source.md),
  making that re-effect path absent.
- **Render-walk identity selection:** the 2026-08-11 closure
  [replaced raw-EID hand lists](docs/seon/issues/archive/render-walk-spells-declared-identities-as-raw-eids.md)
  with the exact-basis identity-attribute query; the live proof found no numeric
  identity residue or declaration fallback.

These are calibrated narrow wins. They do not imply that the broader ambient,
render, resource, or classification classes are finished.
