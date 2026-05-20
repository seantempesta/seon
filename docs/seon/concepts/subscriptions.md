---
type: concept
status: design
tags: [concept, flow]
---
# Subscriptions

> Don't embed data — subscribe to function results. Source pushes when the answer changes.

## The Pattern

Subscriptions are a pull-turned-push mechanism. Instead of a consumer polling a data source or embedding stale data, the consumer declares interest in a function's output, and the source re-evaluates and pushes when the result changes.

A subscription descriptor contains: the **source** (which namespace/function produces the data), the **args** (parameters for that function), and the **current** value (for diffing). The subscriber registers via a subscription request through the flow. The source namespace tracks its subscribers, and whenever the underlying data changes, it re-evaluates the function with the stored args, diffs against the current value, and if changed, pushes the new result directly to the subscriber's `:subscription-update` input.

This differs from [[concepts/feeds]] (broadcast signals) in that subscriptions are **targeted** — the source knows exactly who is listening and pushes only to them. This is efficient for data that many namespaces produce but few consume (e.g., a specific health metric, a trading position summary).

The transport mechanism is direct `flow/inject` into the subscriber's `:subscription-update` input channel. No intermediate pub/sub infrastructure — just one process injecting into another process's input.

## Current Implementation

Subscriptions are not yet implemented as a first-class feature. The pattern exists conceptually in the [[components/flow-topology]] design docs (`docs/prds/unified-flow/design.md`) and is part of the unified model vision. Today, cross-namespace data sharing happens via direct `topology/request!` calls (synchronous pull) or SSE push from `seon.ctx` (which pushes all state changes to connected browser clients, not to other namespaces).

The building blocks are in place: flow processes can have arbitrary named inputs (`:subscription-update` would be one), `flow/inject` can target any process input, and the [[concepts/request-reply]] pattern provides the messaging envelope.

## Reactive Datahike Subscriptions

Beyond namespace-to-namespace subscriptions, the same pattern extends to **database change reactions**. When a Datahike transaction commits, the system can fingerprint the changed attributes and entity shapes, then find functions whose input schema matches that change shape. Those functions run automatically.

This is [[concepts/renderer-discovery]] applied to data mutations instead of rendering:

1. A transaction changes `:seon.health.workout/exercises` on an entity.
2. The system extracts the changed attribute set as a fingerprint.
3. It queries the graph for functions whose input schema requires those attributes.
4. Matching functions execute with the changed data.
5. Their outputs route through the normal flow -- updating ctx, emitting feeds, or triggering further subscriptions.

The specificity algorithm determines which functions run: a function requiring `#{:seon.health.workout/exercises :seon.health.workout/date}` is preferred over one requiring only `#{:seon.health.workout/exercises}` when both attributes changed. This prevents overly broad reactions while ensuring specific handlers fire when their full input is available.

This makes any namespace reactive to data changes without explicit wiring. An agent writes a function with the right input schema, and it automatically participates in the reactive chain. The same [[concepts/progressive-enhancement]] philosophy applies -- if no function matches a change, nothing happens (the default is silence). As agents add functions, the system becomes progressively more reactive.

## In the Unified Model

Each [[concepts/namespace-as-process]] would gain a `:subscription-update` input. The default [[concepts/step-functions|step function]] would handle subscription updates by merging new data into the namespace's ctx. Custom step functions could react to specific subscription changes -- e.g., recalculating derived state when an upstream value changes.

Source namespaces would maintain a subscriber registry in their process state and use a standard protocol for change detection (likely comparing serialized results, since Clojure values have structural equality).

For Datahike-triggered subscriptions, the writer process (`:seon.flow/writer`) would emit change fingerprints after each successful transaction. A subscription router process would match fingerprints against registered function schemas and inject results into the appropriate namespace processes.

## Key Schemas

```clojure
;; Subscription descriptor (design)
[:map
 [:source :string]        ; source namespace
 [:fn :string]            ; function qualified name
 [:args [:vector :any]]   ; arguments for the function
 [:current {:optional true} :any]]  ; last known value for diffing

;; Change fingerprint from Datahike transaction (design)
[:map
 [:changed-attrs [:set :keyword]]  ; attributes that changed
 [:entity-ids [:set :int]]         ; affected entity IDs
 [:tx-id :int]]                    ; transaction ID for ordering

```
