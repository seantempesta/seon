---
type: research
status: active
tags: [research, flow, prd]
---

# Flow Step Function Init Pattern

## Question

Should the namespace `initial-ctx` function take arguments or be zero-arg? We want to align with core.async.flow's step function initialization pattern.

## Findings

### How core.async.flow Init Works

Reading `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj` and its implementation in `flow/impl.clj`:

**The step function has 4 arities:**

| Arity | Name | Signature | Purpose |
|-------|------|-----------|---------|
| 0 | describe | `()` -> description map | Declares params, ins, outs, workload |
| 1 | init | `(arg-map)` -> initial-state | Establishes initial process state |
| 2 | transition | `(state transition)` -> state' | Handles pause/resume/stop |
| 3 | transform | `(state input-id msg)` -> [state' output-map] | Processes messages |

**The init arity always receives a map.** It is never called with zero arguments. The `arg-map` comes from two sources combined:

1. **User-supplied `:args`** from the flow config (`:procs` map entry)
2. **System-injected `::flow/pid`** added by the implementation

Here is the exact code path from `impl.clj` line 153:

```clojure
;; In start-proc (impl.clj:148-163):
(spi/start proc {:pid pid :args (assoc args ::flow/pid pid) ...})

;; In proc (impl.clj:261):
(let [... state (step args) ...]  ;; <-- init arity called with the args map
```

And `args` originates from the flow config (impl.clj line 36):

```clojure
(defn prep-proc [ret pid {:keys [proc, args, chan-opts] ...}]
  ...
  (assoc ret pid {:pid pid :proc proc ... :args args ...}))
```

Which comes from `create-flow`'s config:

```clojure
{:procs {:my-proc {:proc (flow/process #'my-step-fn)
                   :args {:some-param "value"}}}}
```

**Key insight:** The `:args` map in the flow config is the **params** mechanism. The describe arity declares what params the step expects (`:params` key), and the flow config supplies them via `:args`. The system merges `::flow/pid` into this map before passing it to init.

### The `:params` Concept

The describe arity can return a `:params` map (keyword -> docstring), which declares what configuration the step function expects at init time:

```clojure
;; From our namespace-step:
([] {:ins {...} :outs {...}
     :params {::namespace "Namespace string"
              ::queue-cap "Max pending requests (default 32)"}
     :workload :io})
```

The flow library asserts that if `:params` is declared, `:args` must be provided (impl.clj line 255):

```clojure
(assert (or (not params) args) "must provide :args if :params")
```

This is a declaration + validation pattern: the step declares what it needs, the topology config provides it, the library validates the contract.

### How Seon Uses Init Today

All four existing step functions take `args` in their init arity:

**infra-writer-step** -- receives `::connection-manager` via `:args`:

```clojure
([{::keys [connection-manager]}]
 {::connection-manager connection-manager
  ::owned-conns {}
  ::total-writes 0 ...})
```

Config wiring:

```clojure
:seon.flow/writer {:proc (flow/process #'writer/infra-writer-step)
                   :args {::writer/connection-manager connection-manager}}
```

**infra-reader-step** -- same pattern, receives `::connection-manager`.

**namespace-step** -- receives `::namespace`, `::queue-cap`, plus optional `::in-ports`/`::out-ports`:

```clojure
([{::keys [namespace queue-cap] :as args}]
 (let [ns-str (or namespace "unknown")
       cap (or queue-cap 32)
       in-ports (::in-ports args)
       out-ports (::out-ports args)]
   (cond-> {::namespace ns-str ::queue-cap cap ...}
     in-ports (assoc :clojure.core.async.flow/in-ports in-ports)
     out-ports (assoc :clojure.core.async.flow/out-ports out-ports))))
```

**reply-router-step, event-sink-step, error-sink-step** -- ignore args (`[_args]`), return hardcoded initial state. No `:params` declared in describe.

**repl-step** -- also ignores args.

Pattern: steps that need external dependencies declare `:params` and destructure from `args`. Steps that are self-contained ignore `_args` but still accept the map (the library always passes one).

### Dynamic Process Addition

**core.async.flow does NOT support dynamic process addition.** The topology is fixed at `create-flow` time. Evidence:

1. `create-flow` builds all channel maps, connection maps, and process descriptors upfront from the config (impl.clj lines 48-70).
2. `start` iterates `(vals pdescs)` and launches all processes (impl.clj line 164).
3. There is no `add-proc` or `remove-proc` in the Graph protocol (`impl/graph.clj`).
4. The only mutation path is stop + create-flow + start (full rebuild).

The Graph protocol operations are: `start`, `stop`, `pause`, `resume`, `ping`, `pause-proc`, `resume-proc`, `ping-proc`, `command-proc`, `inject`. No dynamic topology modification.

**Implication for namespace actors:** Adding a new namespace at runtime requires rebuilding the flow topology. This is consistent with the PRD's Open Question A, which already identifies this constraint. Options:

1. **Rebuild topology** on namespace add/remove (stop old flow, create-flow with new config, start). State can be preserved by snapshotting before stop and restoring after start.
2. **Pre-allocate slots** for expected namespaces at startup.
3. **Use flow for infrastructure only** (writer, reader, reply-router) and handle namespace dispatch in-process without per-namespace flow processes. The dispatch layer already runs in the Seon JVM -- it can call functions directly via `requiring-resolve` for in-process namespaces.

Option 3 is the most pragmatic: in-process namespaces do not need flow isolation (they share the JVM, there is no serialization boundary). Flow processes are only needed for external JVM namespaces, where the harness/bridge/channel machinery applies.

### The `map->step` Helper

Flow provides `map->step` (flow.clj lines 286-304) for composing step logic from separate functions:

```clojure
(flow/map->step
 {:describe (fn [] {:params {...} :ins {...} :outs {...}})
  :init     (fn [arg-map] ...)
  :transition (fn [state trans] ...)
  :transform  (fn [state input msg] ...)})
```

The `:init` key is explicitly optional: "optional, but should be provided if describe returns :params." When omitted, `map->step` generates `([arg-map] (when init (init arg-map)))` -- returning nil state.

### Special Init Return Keys

The init arity can return two special keys in the state map:

- `::flow/in-ports` -- map of cid -> core.async channel (external input sources)
- `::flow/out-ports` -- map of cid -> core.async channel (external output sinks)

These become part of the process's input/output set but are invisible to the flow's connection topology. This is how `namespace-step` injects TCP bridge channels.

Any state (init, transition, or transform) can also return `::flow/input-filter` -- a predicate of channel-id that controls which inputs are read in the next iteration.

## Recommendation: Map-Arg Initializer

The `initial-ctx` function should take a map argument, consistent with flow's init pattern.

### Why Map-Arg

1. **Alignment with flow.** Every step function's init arity receives a map. Even steps that ignore it still accept `_args`. This is the established pattern across all Seon flow step functions.

2. **Forward compatibility.** A zero-arg initializer cannot receive system-injected context (persisted state to merge, namespace metadata, configuration). A map-arg initializer can receive these without changing its signature:

    ```clojure
    ;; Today: system passes empty map
    (initial-ctx {})

    ;; Tomorrow: system passes persisted state for merge
    (initial-ctx {:seon.ctx/persisted {::screen :active ::history [...]}})

    ;; Or config from runtime registry
    (initial-ctx {:seon.runtime/config {:poll-interval-ms 5000}})
    ```

3. **Spec-driven detection still works.** The PRD's initializer detection rule (input spec lacks `::ctx`, output spec has `::ctx`) is orthogonal to whether the input map is empty or populated. The schema is:

    ```clojure
    {:malli/schema [:=> [:cat :map] ::ctx]}
    ```

    The `:map` input spec contains no `::ctx` key, so the system detects it as an initializer. The caller can pass `{}` or a map with system keys -- the function destructures what it needs.

4. **Consistency with map-in/map-out.** Every public function in Seon takes one map and returns one map. A zero-arg initializer would be the only exception.

5. **Mirrors flow's params contract.** A namespace could declare params it needs at init time (database name, config values), and the system provides them in the init map. This parallels describe's `:params` + flow config's `:args`.

### Concrete Pattern

```clojure
(defn initial-ctx
  "Returns default namespace state.
   System merges persisted state over this on resume."
  {:malli/schema [:=> [:cat :map] ::ctx]}
  [_]
  {::screen  :home
   ::history []})
```

The `_` parameter communicates that the function currently ignores the input. The system calls `(initial-ctx {})` at startup. If the function later needs system-injected data (persisted state, config), it destructures from the map without a signature change.

### What NOT to Do

Do not use `:params` in the describe arity for namespace-level initialization. `:params` is a flow-level concept (step function configuration from the topology config). The namespace `initial-ctx` function is a domain-level concept (application state initialization). These are different layers:

- **Flow init** (step function arity 1): configures the flow process (connection manager, queue cap, namespace name). Infrastructure concern.
- **Namespace init** (`initial-ctx`): creates domain state (screen, history, settings). Application concern.

The dispatch layer calls `initial-ctx` after the flow process is running, not during flow process initialization.

## Dynamic Topology Summary

core.async.flow does not support adding/removing processes at runtime. The topology is immutable after `create-flow`. For adding namespaces at runtime, the options are:

1. **Rebuild the flow** (stop, create-flow with updated config, start). Viable but has a brief outage window.
2. **In-process dispatch without flow processes.** In-process namespaces do not need flow isolation. The dispatch layer can call functions directly via `requiring-resolve`. Only external JVM namespaces need flow processes (the harness/bridge/channel machinery).
3. **Pre-allocate** process slots for expected namespaces.

Option 2 is recommended as the default path. Most namespaces will run in-process. Flow processes are reserved for external JVM isolation, where the topology is rebuilt when an agent JVM is launched or stopped (which is already an explicit lifecycle event via `start-namespace-jvm!` / `stop-namespace-jvm!`).
