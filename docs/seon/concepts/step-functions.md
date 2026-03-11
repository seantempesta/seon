---
type: concept
status: production
tags: [concept, flow]
---
# Step Functions

> The 4-arity pattern: describe, init, transition, transform. Every flow process is a step function.

## The Pattern

A step function is core.async.flow's unit of behavior. It is a single Clojure function with four arities that define a process's complete lifecycle:

1. **Describe** (0-arity): Returns a map declaring inputs, outputs, params, and workload type. This is the process's contract — what it receives, what it produces, and what it needs to start.

2. **Init** (1-arity, receives params): Returns the initial state map. Called once when the process starts. Can also return `::flow/in-ports` and `::flow/out-ports` for external channel bridging (e.g., TCP connections).

3. **Transition** (2-arity, `[state transition]`): Handles lifecycle events — `::flow/stop`, `::flow/pause`, `::flow/resume`. Returns new state. Used for cleanup or mode changes.

4. **Transform** (3-arity, `[state input-id msg]`): The main loop. Called for each message. `input-id` identifies which input the message came from. Returns `[new-state output-map]` where output-map is `{output-id [messages]}`. This is where all business logic lives.

The critical property is that **state is explicit and returned** — no hidden mutation. The flow runtime manages the loop, threading state through successive transform calls. This makes processes introspectable via `flow/ping` (returns current state) and debuggable (state is just data).

Because `flow/process` accepts a **var** (not a value), redefining the function at the REPL updates behavior mid-stream. The next message will be processed by the new transform. No restart needed.

## Current Implementation

Several step functions are implemented and working:

- `seon.flow.harness/namespace-step` — the default step for all namespace processes. Routes requests to agent JVMs via TCP, tracks pending count for backpressure, emits observability events. See `src/seon/flow/harness.clj`.
- `seon.flow.topology/reply-router-step` — delivers reply envelopes to waiting callers via promises. Receives on `:seon.flow.in/reply`, looks up promise by `::msg/id`, delivers. See `src/seon/flow/topology.clj`.
- `seon.flow.topology/event-sink-step` — collects observability events in a bounded vector. Terminal sink, no outputs.
- `seon.flow.topology/error-sink-step` — collects error replies in a bounded vector. Terminal sink, no outputs.
- `seon.db.datalevin/writer` (partial) — handles database write requests.

The default `namespace-step` handles: request forwarding with queue cap, overload replies, JVM reply correlation, error reporting, and observability events. It dispatches on `input-id` to differentiate flow requests from TCP replies.

## In the Unified Model

The default step function expands to handle [[concepts/subscriptions]] (`:subscription-update` input), [[concepts/feeds]] (signal IDs via `:signal-select`), and standard request/reply. A namespace can override the default by authoring a var with `{:seon.flow/step true}` metadata — the topology builder discovers it via the [[components/code-graph]] and uses it instead.

Custom step functions add domain-specific behavior: a trading namespace might react to price feed signals, a health namespace might recalculate aggregates on subscription updates. The override only replaces the transform arity — describe/init/transition can be inherited or customized.

## Key Schemas

```clojure
;; Describe return shape
{:ins  {:input-name "description"}
 :outs {:output-name "description"}
 :params {:param-name "description"}
 :workload :io}  ; or :compute

;; Transform return shape
[new-state {:output-id [msg1 msg2]}]
;; or
[new-state nil]  ; no output
```
