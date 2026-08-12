---
type: decision
status: active
date: 2026-08-04
tags: [decision, architecture, runtime, sci]
---

# ADR-013: Per-turn SCI forks

## Decision

Each cluster owns one acquired program-only base SCI `ctx`. Every turn evaluates
in a fresh generation-aware fork of that base and rehydrates only the selected
agent's defs. Interpreter mutation is private to the turn. Cross-turn sharing
happens through contracted definitions, admission, durable program facts, and
the agent's defs—not through shared mutable interpreter state.

Every function in the cluster program graph remains callable. The fork changes
mutation ownership, not callability.

## Consequences

- Concurrent turns cannot observe one another's uncommitted definitions.
- Run reproduction begins from an explicit commit and namespace.
- Acquisition refreshes the base; each turn uses the cheap SCI fork.
- There are no per-agent interpreter contexts.

## Related

- [[agent-runtime]] — run lifecycle and interpreter ownership.
- [[data-model]] — opening commit and namespace facts.
