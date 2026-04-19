---
type: milestone
status: not-started
order: 8
---
# M8: Autonomous Namespace Agents

When this milestone is crossed, agents steward namespaces through the REPL without human orchestration. They receive typed notifications about failing tests, upstream schema changes, and consumer requests. They respond by writing functions through the eval pipeline. The system composes itself: a namespace receives a request it cannot handle, the agent is notified, it writes a handler, the handler is discovered, and next time the request is handled automatically. This is bootstrap number three.

The agent does not need Claude Code. It does not edit files. It receives Malli-specced messages through flow, evaluates forms in the REPL pipeline, and its work is validated, persisted, and tested automatically. The eval pipeline is its quality gate. The `*ctx*` atom is its world. The flow topology is its nervous system. The graph is its memory.

## The Scenario

`seon.trading.signals` has an agent. The agent is idle -- its namespace is healthy, tests pass, no notifications pending.

A developer adds a new attribute to `seon.trading.positions`: `:seon.trading.positions/risk-score`. This triggers a schema change notification. The system constructs a typed message:

```clojure
{:seon.schema/change-type    :attribute-added
 :seon.schema/namespace      "seon.trading.positions"
 :seon.schema/attribute      :seon.trading.positions/risk-score
 :seon.schema/definition     "[:double {:min 0.0 :max 1.0}]"}

```

The message routes to `seon.trading.signals` because the graph shows a dependency edge: `signals` requires functions from `positions`. The router searches for a handler in `seon.trading.signals` whose input schema matches `::schema/change-notification`. No handler exists. The smart default fires: log the notification and add it to `*ctx*` under `:seon.repl/notifications`.

The agent wakes. It sees the notification in its context. It decides this new attribute is relevant -- risk scores should affect signal confidence. It writes a function:

```clojure
(defn adjust-for-risk
  "Adjust signal confidence based on position risk score."
  {:malli/schema [:=> [:cat ::adjust-request] ::adjust-response]}
  [{::keys [signal risk-score]}]
  {::signal-type (::signal-type signal)
   ::confidence  (* (::confidence signal) (- 1.0 risk-score))})

```

The eval pipeline validates: schema present, concrete types, map-in/map-out, all referenced schemas registered. It compiles. The agent writes a test. The test passes. The agent calls `(seon/persist!)`. The function enters the graph. Other namespaces can now discover it.

Next time a risk score is available alongside a signal, the discovery mechanism finds `adjust-for-risk` as the most specific handler. No wiring. No registration. The system composed itself.

The agent also decides to handle future schema change notifications automatically:

```clojure
(defn on-schema-change
  "React to upstream schema changes affecting trading signals."
  {:malli/schema [:=> [:cat ::schema/change-notification] ::notification/ack]}
  [{:seon.schema/keys [attribute]}]
  (if (str/starts-with? (name attribute) "risk")
    {::notification/action :investigate
     ::notification/message "New risk attribute detected, reviewing impact"}
    {::notification/action :acknowledge
     ::notification/message "No impact on trading signals"}))

```

Now the router finds this handler for the next schema change notification. The smart default no longer fires -- the namespace handles it autonomously. Over time, the agent builds handlers for every notification type it encounters, and the namespace becomes increasingly self-sufficient.

## What This Requires

**Typed notification messages.** A set of Malli-specced message schemas for system events: `::schema/change-notification`, `::test/failure-notification`, `::consumer/request-notification`, `::db/change-notification`. Each has a defined payload and a default handler.

**Message routing by schema shape.** When an event occurs, the system constructs a typed message and routes it to affected namespaces. The router uses the same specificity algorithm as renderer discovery: find functions whose input schema matches the message shape, pick the most specific. If none exists, run the default. If the message requires acknowledgment and no handler exists, wake the agent.

**Agent lifecycle management.** Agents start, sleep, wake, and stop based on demand. An idle agent consumes no resources. A notification wakes it. It processes the notification, does its work, and returns to idle. The system tracks agent health: last active, notification backlog, error rate.

**Agent-fallback escalation.** When no handler exists for a message that requires acknowledgment, the system wakes the namespace agent. The agent inspects the message, decides whether to write a handler (making future messages automatic) or handle it as a one-off. This is the bridge between "no code exists yet" and "the agent will build it when needed."

**Self-referential graph.** The functions that discover other functions, route messages, manage the graph, and enforce constraints are themselves in the graph with spec'd inputs and outputs. An agent looking for "how do I query the graph?" discovers it through the same mechanism it would use for any domain query. The system uses itself.

**Cross-agent visibility.** When agent A persists a function, agent B sees it immediately via the graph. Schema changes trigger notifications to dependent namespaces. The feedback loop is closed: A writes code, B is notified, B adapts, A sees B's adaptation.

**Inter-agent messaging.** Agents communicate through typed mailboxes. Feature requests, bug reports, compatibility questions -- all are Malli-specced maps that persist in Datalevin, survive restarts, and deliver through flow. No out-of-band communication.

## What Already Exists

- [[vision/capabilities/unified-context]] -- complete. The `*ctx*` atom is the agent's state container.
- [[vision/capabilities/flow-topology]] -- complete. The routing backbone for message delivery exists.
- [[vision/capabilities/repl-eval-pipeline]] -- partial. Eval via flow works. Constraint enforcement is not built (M6).
- [[vision/capabilities/inter-agent-messaging]] -- not started. No mailbox, no message schemas, no delivery semantics.

The building blocks are in place: flow processes with step functions, ctx atoms with validation, graph queries for function discovery, Nippy serialization for cross-JVM communication. What is missing is the message schemas, the routing intelligence, the agent lifecycle, and the self-referential closure.

## How to Verify

```clojure
;; Schema change notification routes to dependent namespace
(let [p (promise)]
  (add-notification-listener! "seon.trading.signals"
                               (fn [msg] (deliver p msg)))
  ;; Add a new attribute to a dependency
  (schema/register! :seon.trading.positions/risk-score
                    [:double {:min 0.0 :max 1.0}])
  ;; Notification arrives
  (assert (= :attribute-added
             (:seon.schema/change-type (deref p 5000 nil)))))

;; Agent wakes on notification and can eval
(let [agent (get-agent "seon.trading.signals")]
  (assert (= :idle (:status agent)))
  ;; Send a notification requiring acknowledgment
  (send-notification! "seon.trading.signals"
                      {:seon.test/failure-type :upstream-break
                       :seon.test/test-name "value-test"
                       :seon.test/requires-ack true})
  ;; Agent wakes
  (Thread/sleep 2000)
  (assert (= :active (:status (get-agent "seon.trading.signals")))))

;; Handler written by agent is discovered on next message
(let [;; First message -- no handler, default fires
      r1 (route-message! "seon.trading.signals"
                          {:seon.schema/change-type :attribute-added
                           :seon.schema/attribute :foo/bar})
      _ (assert (= :default (:match-type r1)))
      ;; Agent writes a handler (simulated)
      _ (repl/eval-in! "seon.trading.signals"
                       "(defn on-schema-change ...)")
      ;; Second message -- handler found
      r2 (route-message! "seon.trading.signals"
                          {:seon.schema/change-type :attribute-added
                           :seon.schema/attribute :foo/baz})]
  (assert (= :specific (:match-type r2))))

;; Self-referential: graph query functions are themselves in the graph
(let [fns (graph/functions-with-output-key :seon.graph/query-result)]
  (assert (seq fns))
  (assert (some #(str/starts-with? % "seon.graph.query/") fns)))

```

## Dependencies

**Requires M7 (Namespace as Process)** -- agents need namespaces that receive typed messages, emit signals, and react to subscriptions. Without the reactive surface, there is nothing for agents to respond to.

**Requires M6 (Eval Pipeline)** -- agents develop through the REPL. The pipeline is the quality gate. Without constraint enforcement, agents produce unvalidated code.

**Requires M5 (Observable System)** -- agents need to see system state. The observable layer provides the rendered context that informs agent decisions.

**This is the terminal milestone.** Beyond M8, the system grows organically. Agents add capabilities based on demand. New namespaces emerge from decomposition of complex ones. The infrastructure does not change -- it composes. The [[concepts/progressive-enhancement]] pattern means the system starts simple and accumulates capability without architectural changes.

Related concepts: [[concepts/progressive-enhancement]], [[concepts/namespace-as-process]], [[concepts/subscriptions]], [[concepts/feeds]], [[concepts/namespace-stewardship]].
