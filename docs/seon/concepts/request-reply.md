---
type: concept
status: production
tags: [concept, flow]
---
# Request-Reply

> Promise-based blocking cross-namespace calls through flow. One pattern for all cross-boundary communication.

## The Pattern

Request-reply is the universal pattern for cross-boundary calls in Seon. Whether calling a function in another namespace, writing to the database, or evaluating code on an agent JVM, the mechanism is identical:

1. **Register promise** — `request!` creates a promise and stores it in a global `pending-promises` atom, keyed by a random UUID request ID
2. **Inject into flow** — the request message envelope is injected into the target process's input via `flow/inject`
3. **Process handles** — the target's [[concepts/step-functions|step function]] transform receives the message, does work, and emits a reply on its `:seon.flow.out/reply` output
4. **Reply-router delivers** — the `reply-router-step` receives the reply, looks up the promise by request ID, and delivers it
5. **Caller receives** — `request!` derefs the promise with a timeout, returns the value on success, throws on error/timeout

This is the **only** cross-boundary call pattern. The step function in step 3 is the only thing that varies — namespace calls forward to agent JVMs via TCP, database writes execute `d/transact!`, REPL evals send to nREPL. The envelope format, promise management, and delivery are always the same.

Error handling has three layers: flow infrastructure errors (transform throws, caught by flow, process continues), application errors (reply with error status, delivered to caller via promise), and timeout errors (promise never delivered, `request!` cleans up and throws).

## Current Implementation

Working in production. Key files:

- `src/seon/flow/topology.clj` — `request!` is the blocking entry point. `reply-router-step` delivers promises. `pending-promises` is the global atom. `build-topology!` wires everything together.
- `src/seon/flow/harness.clj` — `namespace-step` handles the namespace-specific routing (forward to agent JVM, correlate replies).
- `src/seon/flow/msg.clj` — defines the wire format for message envelopes (`::msg/id`, `::msg/type`, `::msg/status`, `::msg/fn`, `::msg/args`, etc.).

The `request!` function accepts:

```clojure
{::flow       flow-object
 ::target-ns  "seon.health.workout"
 ::fn         "seon.health.workout/log-workout!"
 ::args       [{:seon.health.workout/type :squat}]
 ::timeout-ms 10000
 ::from-ns    "orchestrator"
 ::trace-id   #uuid "..."}
```

Reply statuses: `:ok` (value returned), `:error` (function threw), `:overload` (queue full), `:timeout` (never replied), `:not-found` (function doesn't exist).

The REPL system (`seon.repl/eval-via-flow!`) uses the same pattern — injects into the `:seon.flow/repl` process, waits for promise delivery.

## In the Unified Model

Request-reply remains the backbone. [[concepts/subscriptions]] build on top of it (the initial subscription is a request; updates are injected directly). [[concepts/feeds]] use a different mechanism (broadcast cast, no reply expected). But for any call that needs a response, request-reply is the pattern.

## Key Schemas

```clojure
;; Request envelope
{::msg/id         uuid
 ::msg/version    1
 ::msg/type       :request
 ::msg/from-ns    "orchestrator"
 ::msg/to-ns      "seon.health.workout"
 ::msg/fn         "seon.health.workout/log-workout!"
 ::msg/args        [arg1 arg2]
 ::msg/created-at  instant}

;; Reply envelope
{::msg/id         uuid          ; same as request
 ::msg/type       :reply
 ::msg/status     :ok           ; or :error, :overload
 ::msg/value      result        ; on :ok
 ::msg/error-message "..."      ; on :error
 ::msg/duration-ms 42}
```
