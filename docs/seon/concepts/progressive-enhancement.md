---
type: concept
status: design
tags: [concept, web]
---
# Progressive Enhancement

> Every message has a handler -- even if no one wrote one yet. Agents build specificity over time.

## The Pattern

When the system sends a message to a namespace, the router finds the most specific handler whose input schema matches the message shape. If no handler exists, a smart default runs instead. The namespace still works -- it just works generically. When an agent later implements a specific handler, the router picks it up automatically. No registration, no wiring. The schema IS the registration.

This is the same [[concepts/renderer-discovery]] specificity algorithm applied universally. Rendering was the first case. The pattern generalizes to event handling, data transformation, notifications, and any interaction where a namespace receives typed data and should respond.

### The Canonical Example

A user minimizes a namespace window in the UI:

1. The system sends a `::ui/minimize` message to the namespace.
2. The router searches for a function whose input schema matches `::ui/minimize` and whose output schema contains `::ui/response`.
3. **No handler exists** -- the namespace is new, the agent hasn't built one yet.
4. The smart default fires: shrink to an icon showing the namespace name and status dot.
5. The user still gets a working UI. The namespace still functions.
6. Later, the agent implements a minimize handler that shows a compact summary card instead.
7. Next `::ui/minimize` message: the router finds the new function (more specific match), uses it.
8. No code changed anywhere except the namespace that gained a function.

### Smart Defaults as Floor, Not Ceiling

The philosophy: **assume things will fail and have a plan in place.** Every message type has a default handler that produces a reasonable result. These defaults are not stubs or error pages -- they are functional behaviors that make the system usable from day one.

Agents progressively replace defaults with specific implementations as functionality is needed. The system never blocks on "this feature isn't built yet." It degrades gracefully and improves incrementally.

This inverts the traditional development model. Instead of "build it, then ship it," the pattern is "ship the default, then build specificity." Namespaces grow organically based on actual usage rather than upfront planning.

## Relationship to Other Concepts

- **[[concepts/renderer-discovery]]** -- The production implementation of specificity-based discovery. Progressive enhancement generalizes this pattern beyond rendering.
- **[[concepts/subscriptions]]** -- When data changes trigger function execution, the same fallback logic applies. If no subscription handler matches, the default (log or ignore) runs.
- **[[concepts/feeds]]** -- Broadcast signals use the same pattern. A namespace that hasn't implemented a signal handler gets the default behavior for that signal type.
- **[[concepts/namespace-as-process]]** -- Each namespace process has a default step function. Custom step functions are progressive enhancements of the default behavior.

## In the Unified Model

The router maintains a registry of default handlers keyed by message output schema (e.g., `::ui/response`, `::notification/ack`, `::constraint/result`). When specificity-based discovery returns no candidates, the router falls back to the default for that output type.

Default handlers are themselves registered functions with schemas -- discoverable, replaceable, and introspectable through the same graph. An agent could even override a system-wide default by providing a more specific match.

The [[vision/index|vision]] describes this as one of the system's key properties: "Write a compatible function and it's discoverable immediately. No registration ceremony."

## Key Schemas

```clojure
;; Message routing request (design)
[:map
 [:message :map]           ; the typed message to route
 [:target-ns :string]      ; destination namespace
 [:output-type :keyword]]  ; desired output schema key (e.g., :seon.render/html)

;; Router result
[:map
 [:handler :string]        ; qualified function name
 [:match-type [:enum :specific :default]]  ; how it was found
 [:result :map]]           ; handler output
```
