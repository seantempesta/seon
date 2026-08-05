---
type: decision
status: active
date: 2026-08-04
tags: [decision, architecture, runtime, sci]
---

# ADR-013: Per-run SCI forks

## Decision

Each cluster owns one acquired base SCI `ctx`. Every run records its opening
commit and starting namespace, then evaluates in a fresh generation-aware fork
of that base. Interpreter mutation is private to the run. Cross-run sharing
happens through contracted definitions, admission, durable program facts, and
acquisition at a later run boundary—not through mutable context state.

Every function in the cluster program graph remains callable. The fork changes
mutation ownership, not callability.

## Consequences

- Concurrent runs cannot observe one another's uncommitted definitions.
- Run reproduction begins from an explicit commit and namespace.
- Acquisition refreshes the base; run creation uses the cheap SCI fork.
- There are no per-agent interpreter contexts.

## Related

- [[agent-runtime]] — run lifecycle and interpreter ownership.
- [[data-model]] — opening commit and namespace facts.
