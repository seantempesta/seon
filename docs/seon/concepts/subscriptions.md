---
type: concept
status: design
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

## In the Unified Model

Each [[concepts/namespace-as-process]] would gain a `:subscription-update` input. The default [[concepts/step-functions|step function]] would handle subscription updates by merging new data into the namespace's ctx. Custom step functions could react to specific subscription changes — e.g., recalculating derived state when an upstream value changes.

Source namespaces would maintain a subscriber registry in their process state and use a standard protocol for change detection (likely comparing serialized results, since Clojure values have structural equality).

## Key Schemas

```clojure
;; Subscription descriptor (design)
[:map
 [:source :string]        ; source namespace
 [:fn :string]            ; function qualified name
 [:args [:vector :any]]   ; arguments for the function
 [:current {:optional true} :any]]  ; last known value for diffing
```
