---
type: concept
status: draft
tags: [concept, flow]
---

# Planned: Namespace as Process (Unified Model)

> Extracted from [[concepts/namespace-as-process]] — aspirational content that doesn't match current code.

## Custom Step Functions Per Namespace

All namespaces currently use the generic `namespace-step`. The vision is that a namespace can override behavior by authoring a var with `{:seon.flow/step true}` metadata — the topology builder would discover it via the [[components/code-graph]] and use it instead of the default.

This would let namespaces define custom signal reactions, subscription management, and output routing.

## The Unified Model

Every namespace becomes a full citizen in the flow topology — not just a routing target but an active participant. Custom [[concepts/step-functions]] replace the generic harness. [[concepts/subscriptions]] and [[concepts/feeds]] become standard inputs. The namespace's ctx is the process state, and the step function is its behavior.

## Schema-Driven Dispatch

In the planned model, the dispatch layer is Malli's `m/decode` with `mt/default-value-transformer`. Schema properties (`:default/fn`) on registered specs provide values when keys are missing. Functions are pure data-in/data-out — no atoms inside functions, no dynamic var injection. The atom is the agent's REPL workspace; Datahike is the source of truth.

Session restore works via decode: non-serializable keys are naturally absent from Datahike, and `:default/fn` fires to rebuild them from available data.
