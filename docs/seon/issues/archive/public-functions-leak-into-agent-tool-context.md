---
type: issue
status: resolved
severity: friction
tags: [issue, agent, capability, schema]
---

# Public implementation functions leak into agent tool context

## Problem

Required compact namespace cards render every public Clojure function as an
agent capability. Public visibility is needed by first-party callers and is
not an agent-facing policy, so internal runtime, database, and schema operations
enter prompts beside real tools.

## Evidence

The live ACME projection for `metal-hairs-lose` advertises 36 `seon.db`
functions and 24 `seon.schema` functions, including boot, provenance, listener,
ambient-scope, schema-projection, registry-reset, snapshot, and rollback
operations. The complete measurement is in
`docs/prds/agentic-tool-refinement/research/namespace-surface-audit-2026-07-15.md`.

## Owner

The analyzer-backed `:seon.fn` program graph owns function facts.
`seon.agent.ctx.namespaces` and every function-menu consumer must derive agent
eligibility from that one fact rather than from a local symbol list.

## Acceptance

- Agent-facing eligibility is colocated function metadata persisted as one
  optional `:seon.fn` fact by boot indexing and eval tee paths.
- Required compact cards and menus show eligible functions with complete
  contracts and omit implementation functions without deleting them from the
  program graph.
- Current namespace full source remains complete.
- Function schema closure and positive domain/entity schemas remain complete,
  deterministic, and database-derived.
- No renderer blocklist, namespace-specific exception map, or second registry
  is introduced.

## Resolution

Resolved on the runtime-reliability branch. Colocated positive metadata now
round-trips through analyzer indexing, boot reconciliation, and eval tee
redefinition. Namespace cards and function menus query the same program fact.
Exact protected inventories cover `seon.db` and `seon.schema`, including
positive and negative eligibility, so a public implementation function cannot
enter compact tool context merely by being public. Focused proof: 66 tests,
319 assertions, zero failures. The rebuilt ACME database retains 1,034 indexed
functions and 114 positive tool facts; the exact agent render drops the
namespace block from 22,106 to 20,406 tokens while omitting the named internal
operations and retaining the named capabilities.

The complete CLJS checkpoint also found one synthetic typeahead menu fixture
that still modeled public visibility as sufficient eligibility. Its seeded
`my.plan/done!` row now carries the same positive agent-facing fact as a real
offered function; the focused failed-offer suppression behavior again receives
the expected menu offer without weakening the production filter.
The complete isolated CLJS checkpoint passed 1,330 tests and 6,344 assertions.
