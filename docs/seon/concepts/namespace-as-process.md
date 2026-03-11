---
type: concept
status: design
tags: [concept, flow]
---
# Namespace as Process

> Every namespace is a core.async.flow process with its own state, step function, and message routing.

## The Pattern

In Seon, a Clojure namespace is not just a collection of functions — it is a **process** in a core.async.flow topology. Each namespace gets its own flow process that routes cross-namespace function calls, manages backpressure, and provides observability.

The process has a step function (`seon.flow.harness/namespace-step`) that handles the 4-arity flow protocol: describe, init, transition, transform. The transform multiplexes two inputs: `:seon.flow.in/request` (from `topology/request!` injection) and `:seon.flow.in/jvm-reply` (from the agent JVM via TCP). It forwards requests to the agent JVM and routes replies back through the reply-router to waiting callers.

State is the namespace's **ctx** — a validated atom managed by `seon.ctx`. Dynamic namespaces declare a `::*ctx*` spec (a Malli schema), and `seon.ns.lifecycle` creates the atom with schema enforcement, persistence, and SSE push. The ctx is injected into the namespace as a dynamic var.

The key insight is that callers never call namespace functions directly across boundaries. They call `topology/request!`, which injects into the flow, and the flow handles routing, backpressure (queue cap with overload replies), error handling, and observability. From outside, all namespaces look identical — same request/reply pattern, same message envelope format.

Hot reload works because flow processes use **vars** as step functions (`flow/process #'namespace-step`). Redefining the var updates the behavior mid-stream without stopping the flow.

## Current Implementation

The harness is implemented in `src/seon/flow/harness.clj`. Each namespace gets a process ID like `:ns/seon.health.workout`. The topology is built by `topology/build-topology!` which wires namespace steps + reply-router + event-sink + error-sink into a single flow.

What works today: cross-namespace calls via `request!`, TCP bridging to agent JVMs, overload protection (queue cap of 32), observability via `flow/ping`. The writer process (`:seon.flow/writer`) follows the same pattern for database writes.

What's missing: custom step functions per namespace (all namespaces currently use the generic `namespace-step`). The vision is that a namespace can override behavior by authoring a var with `{:seon.flow/step true}` metadata — the topology builder would discover and use it instead of the default. This would let namespaces define custom signal reactions, subscription management, and output routing.

## In the Unified Model

Every namespace becomes a full citizen in the flow topology — not just a routing target but an active participant. Custom [[concepts/step-functions]] replace the generic harness. [[concepts/subscriptions]] and [[concepts/feeds]] become standard inputs. The namespace's ctx is the process state, and the step function is its behavior.

## Key Schemas

```clojure
;; Message envelope (seon.flow.msg)
::msg/id, ::msg/type (:request/:reply/:event),
::msg/from-ns, ::msg/to-ns, ::msg/fn, ::msg/args

;; Harness state
::harness/namespace, ::harness/queue-cap,
::harness/pending, ::harness/error-count

```
