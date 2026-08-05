---
type: decision
status: active
date: 2026-08-04
tags: [decision, architecture, rendering, observability]
---

# ADR-015: Two universal output projections with one fit owner

## Decision

Every consumer-visible text value crosses `:seon.render/ai`; every semantic web
UI value crosses `:seon.render/html`. Static authored bytes and transport
framing are not third projections. Important schemas declare named producers,
and the generic floor is the total fallback.

Reference admission retains only identity facts. Producer selection recurses
from that identity; the selected producer owns semantic rendering.
`seon.print/fit` alone applies a database-derived consumer profile. Values that
do not fit contain ordinary elision values with path, counts, offset, profile,
and requery identity or refusal. Program facts declare external sinks and
projection boundaries so output completeness is queryable.

## Consequences

- Tools, errors, logs, status, prompts, and pages share producer and fitting
  semantics.
- Consumers select profiles; producers do not invent local truncation rules.
- Elision is structured data, not punctuation.
- Output audits derive crossings from the program graph.

## Related

- [[ui]] — projection resolution, profiles, and the output floor.
- [[observability]] — bounded output evidence.
- [[data-model]] — producer and output-path facts.
