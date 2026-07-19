---
type: issue
status: resolved
severity: friction
tags: [issue, reference, agent, architecture]
---

# Remove stale runtime guidance from the downstream integration reference

## Problem

The downstream integration guide duplicated provider configuration and retained
removed runtime concepts: `/agents/new`, mutable `:seon.agent/state`,
component-level operator commands, stored `my.soul` persona rows, and an
agent-facing core override example. It could direct an operator away from the
current database, route, context, and supervision owners.

## Owner

The active downstream reference and its links to the maintained provider and
extra-source authorities.

## Acceptance

- Routes and operator commands match the current application.
- Model details and drifting prices have one maintained catalog.
- Named per-agent launch variants and credential-name-only storage are clear.
- Identity files are described as live context rather than stored persona.
- Downstream code points to the maintained ACME and extra-source paths.

## Resolution

Resolved by `ffb3bd42`. The guide now links model/provider/pricing details to
`llm-adapters`, documents the database-backed named launch surface, and removes
the obsolete historical recipes and runtime vocabulary.
