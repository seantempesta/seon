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
| [An agent can OOM the pod via unbounded query and eval values](eval-memory-safety.md) | blocker | agent |

## Friction (10)

| Issue | Severity | Lane |
|-------|----------|------|
| [AI and HTML render twins may run one derivation twice](render-twin-runs-function-twice.md) | friction | UI |
| [Address-message steps have no explicit queue priority](plan-address-step-priority.md) | friction | agent |
| [Async structural functions bypass contract validation](async-contract-instrumentation-gap.md) | friction | agent |
| [Cross-agent planners can reopen worker-completed steps](plan-reopen-cross-agent-authority.md) | friction | agent |
| [Database query tuple results are hard for agents to read](database-query-tuple-shape-legibility.md) | friction | agent |
| [Embedding boot noise — 232 `:entity-id/missing` errors on fresh seed](embedding-first-write-lookup-noise.md) | friction | agent |
| [Model can ghost-echo runtime scaffolding into the transcript spine](narration-ghost-echo-not-neutralized.md) | friction | agent |
| [Plan completion has no checkable verification evidence](plan-completion-verification-evidence.md) | friction | agent |
| [Self-host `cljs.test/is` throws inside a dynamically-evaled `:test` thunk](selfhost-cljs-test-is-thunk-resolution.md) | friction | agent |
| [acme cluster has no programmatic SCI eval seam](acme-no-sci-eval-seam.md) | friction | agent |

## Cleanup (6)

| Issue | Severity | Lane |
|-------|----------|------|
| [Agent tools may silently accept unknown request keys](agent-tool-unknown-key-acceptance.md) | cleanup | agent |
| [Partially-Stale Reference Docs Need Updates](stale-reference-docs.md) | cleanup | docs |
| [Surface recency may be recomputed globally](surface-recency-recomputed.md) | cleanup | UI |
| [Unify the two AsyncLocalStorage stores; rename with-tx-context → with-tx-meta](als-unify-tx-meta.md) | cleanup | Core |
| [eval/transact on a non-primary (scratch) conn returns ok? but doesn't commit](eval-scratch-conn-no-commit.md) | cleanup | agent |
| [parse-forms entries: missing :malli/schema + bare keys](parse-forms-entry-schema-and-bare-keys.md) | cleanup | agent |
