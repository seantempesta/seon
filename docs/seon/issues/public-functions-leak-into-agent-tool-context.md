---
type: issue
status: open
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
