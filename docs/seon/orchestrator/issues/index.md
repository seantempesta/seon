---
type: orchestrator
status: active
tags: [orchestrator, issue, index]
---

# Open Issues — Index

GENERATED FILE — do not hand-edit. Regenerate with `bin/issues-index`.
Lifecycle `open → resolved | superseded`; closed issues live in `archive/`.
See `README.md` for the convention.

## Blocker (1)

| Issue | Severity | Lane |
|-------|----------|------|
| [Graph Missing Generalized Discovery API](graph-missing-schema-index.md) | blocker | Core |

## Friction (12)

| Issue | Severity | Lane |
|-------|----------|------|
| [Coupling: graph.ingest Depends on seon.render](coupling-graph-render.md) | friction | UI |
| [Duplication: ::db-name Schema Registered 14 Times](dup-db-name-schema.md) | friction | Core |
| [Duplication: ::namespace Schema Registered 20+ Times](dup-namespace-schema.md) | friction | Core |
| [Duplication: clj-kondo Analysis Wrapped in 3 Namespaces](dup-kondo-analysis.md) | friction | Core |
| [Embedding boot noise — 232 `:entity-id/missing` errors on fresh seed](embedding-boot-entity-missing-2026-06-25.md) | friction | agent |
| [Improve 6 hook feedback messages](hook-error-hints.md) | friction | agent |
| [Self-host `cljs.test/is` throws inside a dynamically-evaled `:test` thunk](selfhost-cljs-test-is-thunk-resolution.md) | friction | agent |
| [Test-suite audit — fragile/stale/wrong tests + bug-finding (2026-06-25)](test-suite-audit-2026-06-25.md) | friction | agent |
| [Wire call graph context into Gemini review](hook-callgraph-review-context.md) | friction | agent |
| [acme cluster has no programmatic SCI eval seam](acme-no-sci-eval-seam.md) | friction | agent |
| [`ctx/install!` broken for agents with symbol-valued blocks (canvas round-trip)](ctx-install-canvas-symbol-roundtrip.md) | friction | agent |
| [bin/seon supervisor — startup/teardown race audit (2026-06-25)](supervisor-startup-race-audit-2026-06-25.md) | friction | Core |

## Cleanup (14)

| Issue | Severity | Lane |
|-------|----------|------|
| [An agent can OOM its own pod via unbounded eval results / whole-DB queries](eval-memory-safety.md) | cleanup | agent |
| [Dead Code: web/namespace.clj and ui/viewer.clj](dead-web-namespace-viewer.md) | cleanup | UI |
| [Dual code paths & complexity-debt registry — LIVE, both lanes](dual-code-paths-registry.md) | cleanup | agent |
| [Duplication: parse-form-body in Two Places](dup-parse-form-body.md) | cleanup | general |
| [Partially-Stale Reference Docs Need Updates](stale-reference-docs.md) | cleanup | docs |
| [Test Coverage Audit is Stale](test-coverage-audit-stale.md) | cleanup | docs |
| [The context/render system has no clean-build (node-test) coverage](node-test-untestable-context-system.md) | cleanup | agent |
| [Unify the two AsyncLocalStorage stores; rename with-tx-context → with-tx-meta](als-unify-tx-meta.md) | cleanup | Core |
| [[:maybe] Convention Violation in session.clj](maybe-in-session-schemas.md) | cleanup | Core |
| [acme `/agents` doc drift (FIXED) — uncovered: acme pod serves NO db-seeded routes (OPEN regression)](acme-harness-agents-route-drift.md) | cleanup | UI |
| [eval/transact on a non-primary (scratch) conn returns ok? but doesn't commit](eval-scratch-conn-no-commit.md) | cleanup | agent |
| [parse-forms entries: missing :malli/schema + bare keys](parse-forms-entry-schema-and-bare-keys.md) | cleanup | agent |
| [render/code.clj uses invented :seon.foo/* keywords as live values](example-keywords-in-render-code.md) | cleanup | Core |
| [seon.sse keyword prefix doesn't match its owning namespace](sse-keyword-namespace-mismatch.md) | cleanup | Core |
