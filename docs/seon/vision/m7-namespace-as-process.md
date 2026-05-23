---
type: milestone
status: not-started
order: 7
---
# M7: Namespace as Living Process

When this milestone is crossed, every namespace is a flow process with typed inputs, a custom step function, and state that participates in the reactive surface. The default step function handles everything generically. Agents add specificity over time -- a custom step function, subscription handlers, feed signal reactions -- and each addition makes the namespace more capable without changing anything else in the system.

A namespace is still just functions, specs, and tests. That does not change. What changes is the runtime envelope: the flow process that routes messages to the namespace, manages its state, and connects it to the rest of the system. The step function is how the namespace participates. But writing a step function is optional -- the default handles request/reply, state persistence, and SSE push out of the box. [[concepts/progressive-enhancement]] is the governing principle.

## The Scenario

`seon.health.workout` starts with the default step function. It handles requests via `topology/request!`, persists ctx changes to the database, and pushes SSE updates to connected browsers. Standard behavior, no custom code.

A user starts logging workouts. The health agent notices a pattern: after every workout log, three other namespaces poll for the updated workout count. The agent decides to add a feed signal.

The agent declares a feed in namespace metadata:

```clojure
(ns seon.health.workout
  {:seon.ns/feeds [{:signal-id :seon.health/workout-logged
                    :doc "Emitted when a workout is logged"
                    :payload-schema [:map
                                     [:seon.health.workout/id :uuid]
                                     [:seon.health.workout/type :keyword]]}]})

```

The topology builder reads this metadata and configures the process's output to include this signal ID. Other namespaces declare interest via `:signal-select` in their step function's describe arity. Now when a workout is logged, the step function emits the signal via `flow/inject` to `[::flow/cast :seon.health/workout-logged]`, and all interested processes receive it. No polling.

Later, the agent writes a custom step function to handle subscription requests:

```clojure
(defn workout-step
  "Custom step for seon.health.workout."
  {:seon.flow/step true}
  ([] ;; describe
   {:ins  {:request "incoming requests"
           :subscription-update "data from subscribed sources"}
    :outs {:reply "responses" :event "observability"}
    :params {:namespace "owner namespace"}
    :workload :io})
  ([params] ;; init
   {:namespace (:namespace params)
    :subscriber-count 0})
  ([state transition] ;; transition
   (case transition ::flow/stop (dissoc state :subscribers) state))
  ([state input-id msg] ;; transform
   (case input-id
     :request (handle-request state msg)
     :subscription-update (handle-subscription state msg)
     [state nil])))

```

The topology builder discovers the var via `{:seon.flow/step true}` metadata in the graph and uses it instead of the generic `namespace-step`. Hot reload via vars means redefining this function updates behavior immediately.

## What This Requires

**Custom step function discovery.** The topology builder queries the code graph for functions with `{:seon.flow/step true}` metadata in each namespace. If found, it replaces the default `namespace-step`. The function must implement the 4-arity flow contract (describe, init, transition, transform).

**Default step function that handles everything.** The default `namespace-step` already handles request/reply and observability. It must expand to handle subscription updates (merge into ctx), feed signal forwarding (emit on cast), and state transitions (persist on stop). A namespace with no custom code still works fully.

**Subscription inputs.** Each namespace process gains a `:subscription-update` input. Sources push to it via `flow/inject`. The default step function merges subscription data into ctx. Custom step functions can react with domain logic -- recalculating derived state, triggering further emissions.

**Feed signal outputs.** Namespaces declare feeds via `:seon.ns/feeds` metadata. The topology builder reads feed declarations, configures process outputs, and wires `:signal-select` for subscribing processes. Emission is via `flow/inject` to `[::flow/cast signal-id]` -- a first-class core.async.flow feature.

**Reactive database subscriptions.** When a Datahike transaction commits, the writer process fingerprints the change (which attributes, which entity shapes). A subscription router matches fingerprints against registered function schemas. Matching functions execute, and their outputs route through flow. See [[concepts/subscriptions]] for the full pattern.

**Unified namespace model.** A single component represents "a namespace as a running entity" -- combining behavior (step function), state (ctx atom), metadata (feeds, subscriptions, specs), and lifecycle (start/stop/restore). Consumers interact with one system, not the current split between harness and ctx.

**Smart defaults for all message types.** Every message that can arrive at a namespace has a default handler. The router finds the most specific handler whose input schema matches; if none exists, the default fires. Defaults are themselves registered functions -- discoverable, replaceable, introspectable.

## What Already Exists

- [[vision/capabilities/flow-topology]] -- complete. The routing backbone, request/reply, and process lifecycle are production.
- [[vision/capabilities/namespace-persistence]] -- complete. Ctx atoms persist to Datahike and restore on reload.
- [[vision/capabilities/unified-context]] -- complete. Atom + validation + SSE push. The state container is ready.
- The default step function (`namespace-step` in `harness.clj`) handles request forwarding, queue cap, overload replies, and observability. It is the foundation for expansion.
- The 4-arity step function pattern is documented and production-tested across five step functions. See [[concepts/step-functions]].

Key gaps: [[orchestrator/issues/no-custom-namespace-behavior]], [[orchestrator/issues/no-unified-namespace-model]], [[orchestrator/issues/no-live-subscriptions]], [[orchestrator/issues/no-broadcast-signals]], [[orchestrator/issues/atom-watches-bypass-flow]].

## How to Verify

```clojure
;; Custom step function is discovered and used
(let [proc-info (flow/ping (:flow @system) :ns/seon.health.workout)]
  (assert (= 'seon.health.workout/workout-step
             (:step-fn proc-info))))

;; Feed signal is received by subscribing namespace
(let [p (promise)]
  ;; Register a test subscriber
  (add-signal-listener! :seon.health/workout-logged
                        (fn [msg] (deliver p msg)))
  ;; Log a workout (triggers signal emission)
  (db/transact! :seon [{:seon.health.workout/id (random-uuid)
                         :seon.health.workout/type :squat}])
  ;; Signal arrives
  (assert (= :squat (:seon.health.workout/type (deref p 5000 nil)))))

;; Subscription delivers updated data
(let [sub-id (subscribe! {:source "seon.health.workout"
                          :fn "seon.health.workout/weekly-summary"
                          :args []})]
  ;; Log a workout (changes the summary)
  (db/transact! :seon [{:seon.health.workout/id (random-uuid)
                         :seon.health.workout/type :bench}])
  ;; Subscriber receives updated summary within 1s
  (assert (pos? (:seon.health.workout/count
                  (deref (get-subscription sub-id) 1000 nil)))))

;; Default step function still works for namespaces without custom step
(let [resp (topology/request! {:seon.flow/flow (:flow @system)
                                :seon.flow/target-ns "seon.trading.signals"
                                :seon.flow/fn "seon.trading.signals/analyze"
                                :seon.flow/args [{:seon.trading.signals/ticker "AAPL"}]})]
  (assert (= :ok (:seon.flow.msg/status resp))))

;; State changes route through flow, not atom watches
;; ctx.clj should not contain direct SSE push in watches
(assert (zero? (count (grep "send-event!" "src/seon/ctx.clj"))))

```

## Dependencies

**Requires M6 (Eval Pipeline)** -- custom step functions are authored through the REPL pipeline with constraint validation. The pipeline ensures step functions meet the 4-arity contract before accepting them.

**Requires M5 (Observable System)** -- flow-based SSE push must be unified before namespace processes can emit signals through flow. The three-way SSE split must be resolved first.

**Enables M8 (Autonomous Agents)** -- agents that steward namespaces need the namespace to be a living process that receives notifications, reacts to upstream changes, and grows capabilities. Without subscriptions, feeds, and custom step functions, there is no reactive surface for agents to interact with.

Related concepts: [[concepts/namespace-as-process]], [[concepts/step-functions]], [[concepts/subscriptions]], [[concepts/feeds]], [[concepts/progressive-enhancement]].
