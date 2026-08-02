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

## Blocker (7)

| Issue | Severity | Lane |
|-------|----------|------|
| [Make superseded PRD runbooks fail closed](docs-rot-superseded-prd-runbooks-still-sequence-current-work.md) | blocker | doc-rot fix wave |
| [Delete the writer build that still packages the old host server](writer-build-still-packages-the-deleted-host-server.md) | blocker | great-deletion build wave |
| [Make archived PRD runbooks fail closed as historical](docs-rot-archived-prd-runbooks-remain-active-authorities.md) | blocker | doc-rot fix wave |
| [Give `acquire!` per-row containment on the cold path](acquire-has-no-per-row-containment.md) | blocker | per-cluster live-graph wave |
| [Refuse a cluster fork whose source lacks the rows population will name](new-cluster-boot-fails-on-a-stale-published-source.md) | blocker | visual-QA fix wave |
| [Attribute evals to the agent's assigned namespace](evals-ignore-the-agents-assigned-namespace.md) | blocker | SCI eval-context owner design gate |
| [Remove the platform thread held by every armed agent's error fan-out](armed-agent-holds-a-platform-thread.md) | blocker | flow-protocol wave |

## Friction (48)

| Issue | Severity | Lane |
|-------|----------|------|
| [Remove the deleted run lease from the AI retry proof](ai-retry-proof-still-cites-the-deleted-run-lease.md) | friction | AI retry evidence wave |
| [Derive fleet state from events, not a 20 ms ping absence](oversight-treats-a-20ms-ping-absence-as-state.md) | friction | render oversight event wave |
| [Reject negative imports at the escape/static admission boundary](negative-import-masks-escape-static-admission.md) | friction | SCI static-admission repair wave |
| [Blob get assumes the file-store callback shape](blob-get-assumes-file-store-callback-shape.md) | friction | blob storage repair wave |
| [Delete Flow prototype procs beside the live agent graphs](flow-prototype-procs-survive-beside-the-live-agent-graphs.md) | friction | Flow prototype deletion wave |
| [Delete static render blocks left by the one-walk cutover](static-render-blocks-survive-the-one-walk-cutover.md) | friction | render deletion wave |
| [Preserve render resolution and feed failure evidence](render-resolution-and-feed-swallow-failures.md) | friction | render error-evidence wave |
| [Keep session-image refusal evidence as facts, not derived prose](session-image-stores-derived-unrestorable-prose.md) | friction | session-image evidence wave |
| [Derive namespace context without a stored `my.*` roster](cluster-toolkit-stores-a-prefix-derived-projection.md) | friction | context derivation wave |
| [Split the turn and evaluation kernels at durable boundaries](runtime-turn-and-evaluate-kernels-conflate-boundaries.md) | friction | runtime boundary refactor |
| [Give config-dial discovery one explicit authority](config-dial-discovery-has-three-authorities.md) | friction | config derivation wave |
| [Derive changed-test ownership instead of classifying paths](changed-test-selector-classifies-hosts-by-path-prefix.md) | friction | changed-test selector repair |
| [Make the public-contract census prove its subjects exist](public-contract-census-can-pass-with-no-subjects.md) | friction | contract-gate repair |
| [Generate fresh Flow contract values](flow-generators-reuse-one-mutable-sample.md) | friction | contract-generator repair |
| [Await changed-test process exits instead of polling clocks](changed-test-process-cleanup-polls-observable-exit.md) | friction | changed-test process repair |
| [Give thinking tool continuations one faithful request shape](thinking-tool-continuations-have-no-faithful-request-shape.md) | friction | future model-continuation wave |
| [Fence the MCP parent watchdog by captured process identity](mcp-parent-watchdog-can-follow-a-reused-pid.md) | friction | MCP process-lifetime repair |
| [Give MCP frame provenance the program graph's source-root authority](mcp-frame-provenance-duplicates-the-program-source-root-roster.md) | friction | MCP provenance repair |
| [Point the surviving writer-port consumers at fresh advertisements](old-writer-port-consumers-survive-outside-mcp.md) | friction | adversarial-audit fix wave |
| [Give the elision marker its count and identity](elided-marker-carries-no-count-or-identity.md) | friction | print-path follow-up |
| [Make the oversized terminal-refusal settle as one schema-valid error fact](terminal-refusal-error-fact-fails-on-oversized-data.md) | friction | settlement fix wave |
| [Promote the 34 proven REPL-parity divergences as the print path lands](repl-parity-divergences.md) | friction | print-path implementation wave |
| [Close the 2026-08-01 unlogged findings (interop policy, agent write surface, rot)](unlogged-findings-2026-08-01.md) | friction | general |
| [Stop rebuilding the whole schema projection on every contracted `defn`](contracted-defn-rebuilds-the-whole-schema-projection.md) | friction | per-cluster live-graph wave |
| [Give offline roster discovery a current read-only helper](give-offline-roster-discovery-a-current-read-only-helper.md) | friction | operator artifact follow-up |
| [Make the debug left pane the exact bytes the agent received](debug-left-pane-is-not-the-exact-prompt.md) | friction | visual-QA fix wave |
| [Bind first-party namespaces so value-position reads deref](host-bound-first-party-vars-break-in-value-position.md) | friction | SCI eval-context owner design gate |
| [Create the store with the write-amplification options it already has](file-store-commits-pay-five-times-the-fsyncs-they-need.md) | friction | store/perf fix lane |
| [Give `ai-prose` the ref shape the render walk actually hands it](error-render-puts-its-own-failure-in-agent-context.md) | friction | turn-loop preflight fix lane |
| [Give render token budgets one config owner instead of private dials](render-token-budgets-are-private-dials-no-producer-supplies.md) | friction | context wave fix lane |
| [Make the render wave's properties able to produce their failing cases](render-wave-properties-cannot-produce-their-failing-cases.md) | friction | context wave fix lane |
| [Return the SCI re-arm refusal as a value and seal the guard's invariants](sci-evaluate-throws-when-a-guarded-context-is-re-armed.md) | friction | Core |
| [Clear the floor's residue, duplicate cursors, and marker hand list](value-floor-residue-duplicate-cursors-and-marker-hand-lists.md) | friction | context wave fix lane |
| [Align vendored Malli source with the pinned dependency](malli-vendor-is-ahead-of-pinned-dependency.md) | friction | general |
| [Merge the 28 upstream Datahike commits our fork is missing](datahike-fork-is-28-commits-behind-upstream.md) | friction | upstream-delta sweep follow-up |
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

## Cleanup (10)

| Issue | Severity | Lane |
|-------|----------|------|
| [Delete five readerless schema rows left by completed cuts](schema-population-retains-five-readerless-rows.md) | cleanup | schema population deletion wave |
| [Delete or expose the readerless cluster export surface](cluster-export-is-implemented-without-a-runtime-reader.md) | cleanup | store capability deletion wave |
| [Delete root operator files with no live reader](old-detach-helper-and-operator-alias-have-no-live-reader.md) | cleanup | rot deletion wave |
| [Keep the page body from scrolling sideways on a phone](agent-pages-overflow-a-phone-viewport.md) | cleanup | visual-QA fix wave |
| [Fix the context wave's three small honesty defects](context-wave-leaves-three-small-honesty-defects.md) | cleanup | context wave fix lane |
| [Give Flow configuration dials one registration owner](flow-config-dials-have-two-registration-owners.md) | cleanup | Core |
| [Remove or implement monitor-graph's throwing command proc](monitor-graph-command-proc-throws.md) | cleanup | flow-protocol wave |
| [Delete the konserve LRU our fork allocates and never reads](datahike-allocates-a-konserve-cache-it-never-reads.md) | cleanup | store/perf fix lane |
| [Read the symbol SCI already puts in analysis ex-data](sci-analysis-ex-data-carries-a-symbol-nothing-reads.md) | cleanup | SCI eval-context owner design gate |
| [Close the remaining vendored-versus-pinned dependency drift](vendored-transit-clj-drifts-from-the-pinned-artifact.md) | cleanup | upstream-delta sweep follow-up |
