---
type: capability
status: not-started
tags: [vision, schema]
---
# Schema-Based Function Discovery

Given an input shape and a desired output shape, find compatible functions. Composition chains are queryable as graph paths. One discovery mechanism serves all use cases -- rendering, transformation, event handling, validation. This is the vision's core primitive and the foundation for milestones M2 through M4.

## What Exists

Nothing beyond renderer discovery (see [[capabilities/renderer-discovery]]), which is a special case of this general capability. The code graph stores function metadata but not in a form that supports arbitrary schema-shape matching.

## Reactive Discovery via Data Changes

Function discovery is not only demand-driven ("I need a function that does X") but also change-driven ("data changed, what functions should react?"). When a Datalevin transaction changes attributes on entities, the system fingerprints the change (which attributes, which entity shapes) and discovers functions whose input schemas match. Those functions execute automatically.

This is the same discovery mechanism applied reactively. See [[concepts/subscriptions]] for the reactive Datalevin subscription pattern. The discovery query is identical -- "find functions whose required input keys are a subset of the available data keys" -- but the trigger is a data mutation rather than a render request or explicit call.

Combined with [[concepts/progressive-enhancement]], this means: new data flows through the system, and any namespace that has written a compatible function reacts automatically. Namespaces that haven't written handlers yet are unaffected. As agents add functions, the reactive surface grows organically.

## Gaps

- No schema-shape matching (only exact keyword lookup today)
- No composition chain discovery (A->B->C paths)
- No unified dispatch replacing per-use-case discovery code
- Graph does not store full Malli schema forms structurally
- No change-fingerprint-to-function matching for reactive discovery

## Related

- Components: [[components/code-graph]], [[components/schema-system]]
- Concepts: [[concepts/subscriptions]], [[concepts/progressive-enhancement]], [[concepts/renderer-discovery]]
- PRDs: [[prds/spec-driven-rendering/prd]]
- Issues: [[orchestrator/issues/graph-missing-schema-index]]
