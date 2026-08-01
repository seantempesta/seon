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

## Blocker (10)

| Issue | Severity | Lane |
|-------|----------|------|
| [Enforce agent-authored contracts in the live ctx](agent-authored-contracts-do-not-enforce-in-the-live-ctx.md) | blocker | live-ctx contract slice |
| [Give `acquire!` per-row containment on the cold path](acquire-has-no-per-row-containment.md) | blocker | per-cluster live-graph wave |
| [Make the interpreted program graph per cluster, never process-wide](one-program-graph-is-shared-across-clusters.md) | blocker | per-cluster live-graph wave |
| [Refuse a cluster fork whose source lacks the rows population will name](new-cluster-boot-fails-on-a-stale-published-source.md) | blocker | visual-QA fix wave |
| [Make the agent page's live feed paint the page a GET already renders](agent-page-live-feed-paints-nothing.md) | blocker | visual-QA fix wave |
| [Attribute evals to the agent's assigned namespace](evals-ignore-the-agents-assigned-namespace.md) | blocker | SCI eval-context owner design gate |
| [Seed the cluster's process row before naming it as provenance](cluster-boot-refuses-its-own-process-provenance.md) | blocker | turn-loop preflight fix lane |
| [Eval-time schema and test rows have no recurring proof](eval-time-schema-and-test-rows-have-no-recurring-proof.md) | blocker | Core |
| [Remove the platform thread held by every armed agent's error fan-out](armed-agent-holds-a-platform-thread.md) | blocker | flow-protocol wave |
| [Make REPL parity fail when an expected row disappears](parity-gate-has-no-row-cardinality-sentinel.md) | blocker | adversarial-audit fix wave |

## Friction (38)

| Issue | Severity | Lane |
|-------|----------|------|
| [Point the surviving writer-port consumers at fresh advertisements](old-writer-port-consumers-survive-outside-mcp.md) | friction | adversarial-audit fix wave |
| [Make the oversized terminal-refusal settle as one schema-valid error fact](terminal-refusal-error-fact-fails-on-oversized-data.md) | friction | settlement fix wave |
| [Promote the 34 proven REPL-parity divergences as the print path lands](repl-parity-divergences.md) | friction | print-path implementation wave |
| [Close the 2026-08-01 unlogged findings (interop policy, agent write surface, rot)](unlogged-findings-2026-08-01.md) | friction | general |
| [Stop rebuilding the whole schema projection on every contracted `defn`](contracted-defn-rebuilds-the-whole-schema-projection.md) | friction | per-cluster live-graph wave |
| [Make `seon.sci.eval` hot-reloadable](sci-eval-namespace-is-not-hot-reloadable.md) | friction | Core |
| [Wire `:seon.render.value/options` so presentation decouples from admission caps](render-value-options-declared-but-unwired.md) | friction | caps-blob-print wave |
| [Give offline roster discovery a current read-only helper](give-offline-roster-discovery-a-current-read-only-helper.md) | friction | operator artifact follow-up |
| [Derive the reachable blob set from the schema and include history](blob-reachability-names-one-attribute-by-hand.md) | friction | store/GC fix lane |
| [Make the debug left pane the exact bytes the agent received](debug-left-pane-is-not-the-exact-prompt.md) | friction | visual-QA fix wave |
| [Restore the message bar to the page the agent route serves](agent-page-has-no-message-form.md) | friction | visual-QA fix wave |
| [Bind first-party namespaces so value-position reads deref](host-bound-first-party-vars-break-in-value-position.md) | friction | SCI eval-context owner design gate |
| [Create the store with the write-amplification options it already has](file-store-commits-pay-five-times-the-fsyncs-they-need.md) | friction | store/perf fix lane |
| [Let a live config apply reach an armed agent graph](armed-agent-graphs-freeze-config-dials-at-arm.md) | friction | turn-loop preflight fix lane |
| [Give `ai-prose` the ref shape the render walk actually hands it](error-render-puts-its-own-failure-in-agent-context.md) | friction | turn-loop preflight fix lane |
| [Give render token budgets one config owner instead of private dials](render-token-budgets-are-private-dials-no-producer-supplies.md) | friction | context wave fix lane |
| [Make the render wave's properties able to produce their failing cases](render-wave-properties-cannot-produce-their-failing-cases.md) | friction | context wave fix lane |
| [Derive walk family detection from identity, not declaration order](walk-family-detection-depends-on-schema-declaration-order.md) | friction | context wave fix lane |
| [Return the SCI re-arm refusal as a value and seal the guard's invariants](sci-evaluate-throws-when-a-guarded-context-is-re-armed.md) | friction | Core |
| [Clear the floor's residue, duplicate cursors, and marker hand list](value-floor-residue-duplicate-cursors-and-marker-hand-lists.md) | friction | context wave fix lane |
| [Align vendored Malli source with the pinned dependency](malli-vendor-is-ahead-of-pinned-dependency.md) | friction | general |
| [Merge the 28 upstream Datahike commits our fork is missing](datahike-fork-is-28-commits-behind-upstream.md) | friction | upstream-delta sweep follow-up |
| [Make the filestore able to execute the batch write Datahike builds](konserve-filestore-cannot-execute-the-batch-write-datahike-builds.md) | friction | store/perf fix lane |
| [State a position on `:keep-history?` instead of inheriting it](keep-history-is-on-by-default-without-a-decision.md) | friction | store/perf fix lane |
| [Give storage GC the cutoff that makes it actually reclaim](storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing.md) | friction | store/perf fix lane |
| [Refuse `:db.secondary/only` until a covering index exists](secondary-only-attributes-have-no-covering-index.md) | friction | schema-lifecycle wave |
| [Adopt flow's read-set control and sanctioned egress](flow-has-no-read-set-control-and-a-hand-rolled-egress.md) | friction | flow-protocol wave |
| [Connect namespace alias and refer targets with refs](namespace-binding-targets-are-symbols-not-refs.md) | friction | future program-graph binding wave |
| [Partial hot reload leaves a live JVM running mixed old and new code](partial-hot-reload-produces-mixed-code-with-no-warning.md) | friction | general |
| [Publish graph transitions instead of polling them in tests](observable-graph-transitions-are-polled-in-tests.md) | friction | Core |
| [Resolve namespace aliases before selecting runtime lint stubs](runtime-lint-does-not-resolve-namespace-aliases.md) | friction | future runtime-lint wave |
| [Permit accretive schema loosenings over existing data](schema-guard-refuses-accretive-loosenings-with-data.md) | friction | schema-lifecycle wave |
| [Make the Context MVP drive prove its semantic exit](context-mvp-drive-can-false-green-after-cross-agent-delivery.md) | friction | context MVP harness wave |
| [Give the work launcher's control read SPI priority or rebuild it as a var-process](work-launcher-control-alts-lacks-priority.md) | friction | flow-protocol wave |
| [Preserve Inst semantics when a value is also collection-like](admit-inst-overlap-prefers-collection-shape.md) | friction | adversarial-audit fix wave |
| [Require the general printer bound for every contract headline](instrumentation-headline-unbounded-when-caps-absent.md) | friction | adversarial-audit fix wave |
| [Make the ACME wrapper speak the fresh operator command language](acme-wrapper-speaks-deleted-operator-command-language.md) | friction | adversarial-audit fix wave |

## Cleanup (7)

| Issue | Severity | Lane |
|-------|----------|------|
| [Keep the page body from scrolling sideways on a phone](agent-pages-overflow-a-phone-viewport.md) | cleanup | visual-QA fix wave |
| [Fix the context wave's three small honesty defects](context-wave-leaves-three-small-honesty-defects.md) | cleanup | context wave fix lane |
| [Give Flow configuration dials one registration owner](flow-config-dials-have-two-registration-owners.md) | cleanup | Core |
| [Remove or implement monitor-graph's throwing command proc](monitor-graph-command-proc-throws.md) | cleanup | flow-protocol wave |
| [Delete the konserve LRU our fork allocates and never reads](datahike-allocates-a-konserve-cache-it-never-reads.md) | cleanup | store/perf fix lane |
| [Read the symbol SCI already puts in analysis ex-data](sci-analysis-ex-data-carries-a-symbol-nothing-reads.md) | cleanup | SCI eval-context owner design gate |
| [Close the remaining vendored-versus-pinned dependency drift](vendored-transit-clj-drifts-from-the-pinned-artifact.md) | cleanup | upstream-delta sweep follow-up |
