---
type: concept
status: draft
tags: [concept, flow]
---

# Planned: Custom Step Function Overrides

> Extracted from [[concepts/step-functions]] — aspirational content that doesn't match current code.

## Per-Namespace Step Overrides

The default `namespace-step` would expand to handle [[concepts/subscriptions]] (`:subscription-update` input), [[concepts/feeds]] (signal IDs via `:signal-select`), and standard request/reply.

A namespace can override the default by authoring a var with `{:seon.flow/step true}` metadata — the topology builder discovers it via the [[components/code-graph]] and uses it instead.

Custom step functions add domain-specific behavior: a trading namespace might react to price feed signals, a health namespace might recalculate aggregates on subscription updates. The override only replaces the transform arity — describe/init/transition can be inherited or customized.
