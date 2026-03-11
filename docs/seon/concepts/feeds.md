---
type: concept
status: design
---
# Feeds

> Broadcast signals to all interested listeners. Namespaces declare what they emit; others subscribe by signal ID.

## The Pattern

Feeds are the broadcast counterpart to [[concepts/subscriptions]]. Where subscriptions are targeted push (source knows each subscriber), feeds are **broadcast** — the emitter sends a signal and all interested processes receive it, without the emitter knowing or caring who is listening.

A namespace declares its feeds via `:seon.ns/feeds` metadata: a vector of signal descriptors, each with a signal ID (keyword), a docstring, and a Malli schema for the payload. Other namespaces discover available feeds via `flow/ping` (querying process descriptions) and subscribe by declaring interest via `:signal-select` in their step function's describe arity.

The transport is core.async.flow's built-in **cast** mechanism: `flow/inject` to `[::flow/cast signal-id]` broadcasts to ALL processes that declared that signal in their `:signal-select` set. This is a first-class flow feature — no custom pub/sub needed.

Feeds are appropriate for system-wide events: "config changed", "database schema updated", "new namespace loaded", "agent connected". Unlike subscriptions (which carry specific data for specific consumers), feeds carry lightweight signals that many processes may want to react to.

## Current Implementation

The cast/signal mechanism exists in core.async.flow (see `reference-code/core.async/`). The `signal-select` pattern appears in the flow architecture docs (`docs/architecture/flow-foundation.md`) where a `status-aggregator-step` subscribes to `::agent-heartbeat` signals.

However, `:seon.ns/feeds` as a namespace-level declaration is not yet implemented. Today, namespace metadata contains `:seon.ns/dynamic?` (for ctx) and input/output specs (for [[concepts/renderer-discovery]]), but not feed declarations. The scanner (`seon.graph.extract`) would need to extract feed metadata, and the topology builder would need to wire `:signal-select` from feed declarations.

## In the Unified Model

Each [[concepts/namespace-as-process]] declares its feeds alongside its ctx spec and render functions. The topology builder reads feed declarations and automatically configures `:signal-select` for subscribing processes. Custom [[concepts/step-functions]] react to feed signals in their transform arity — the `input-id` will be the signal keyword from the cast.

Discovery happens at topology build time (static) or via `flow/ping` (dynamic). A namespace can start listening to a new feed by updating its step function's describe — hot reload via vars means this takes effect immediately.

## Key Schemas

```clojure
;; Feed declaration (design)
[:map
 [:signal-id :keyword]         ; e.g. :seon.health/workout-logged
 [:doc :string]                ; human description
 [:payload-schema :any]]       ; Malli schema for the signal payload

;; Namespace metadata
{:seon.ns/feeds [{:signal-id :seon.health/workout-logged
                  :doc "Emitted when a workout is logged"
                  :payload-schema [:map [:workout-id :uuid]]}]}
```
