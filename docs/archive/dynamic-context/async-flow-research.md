# Research: core.async.flow for Agent Message Architecture

**Date:** 2026-01-30
**Status:** Complete
**Author:** Research Agent

## Executive Summary

Rich Hickey's `core.async.flow` (released January 2025) provides a declarative framework for building dataflow systems on top of core.async channels. After thorough analysis, **I recommend NOT adopting flow for our agent message architecture**, but there are valuable patterns we can borrow.

### Key Findings

1. **Flow solves topology complexity**, not our backpressure problem
2. Our current sliding-buffer fix is actually **the right solution**
3. Flow's introspection (`ping`, `datafy`) could improve our observability
4. The **configuration-as-data** pattern is worth adopting selectively

---

## What is core.async.flow?

Flow provides two abstractions:

1. **Processes** - Threads of activity with defined lifecycle (paused → running → stopped)
2. **Flows** - Directed graphs of processes connected via channels

The key insight is **separation of concerns**:

```
┌─────────────────────────────────────────────────────────────┐
│                    YOUR RESPONSIBILITY                      │
├─────────────────────────────────────────────────────────────┤
│  step-fns: (state, input, msg) → [state', {out → [msgs]}]  │
│  flow-def: {:procs {...} :conns [...]}                      │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                    FLOW's RESPONSIBILITY                    │
├─────────────────────────────────────────────────────────────┤
│  • Channel creation and wiring                              │
│  • Thread lifecycle and pool management                     │
│  • Error propagation to centralized error-chan              │
│  • Pause/resume/stop coordination                           │
│  • Introspection via ping and datafy                        │
└─────────────────────────────────────────────────────────────┘

```

### Step Functions (4 arities)

```clojure
;; Arity 0: describe - what ins/outs/params does this process need?
(defn my-step
  ([] {:ins {:in "input"} :outs {:out "output"} :params {:threshold "max value"}})

  ;; Arity 1: init - setup initial state from args
  ([args] {:threshold (:threshold args) :count 0})

  ;; Arity 2: transition - handle lifecycle changes (pause/resume/stop)
  ([state transition]
   (case transition
     ::flow/stop (do (cleanup-resources state) state)
     state))

  ;; Arity 3: transform - process a message
  ([state input msg]
   (let [new-count (inc (:count state))]
     [{:count new-count}                           ; new state
      {:out [(process-msg msg)]}])))               ; output map

```

### Flow Definition (data-driven topology)

```clojure
{:procs {:reader  {:proc (process #'reader-step)
                   :args {:source some-chan}}
         :parser  {:proc (process #'parser-step)
                   :chan-opts {:in {:buf-or-n 100}}}  ; <-- buffer config!
         :writer  {:proc (process #'writer-step)
                   :args {:sink output-chan}}}

 :conns [[[:reader :out] [:parser :in]]
         [[:parser :out] [:writer :in]]]}

```

---

## How Flow Handles Backpressure

This is the critical question for our use case. After reading the source code:

### Buffer Configuration (chan-opts)

```clojure
;; From impl.clj:101-109
make-chan (fn [[[pid cid]{:keys [buf-or-n xform]}]]
            (if xform
              (async/chan buf-or-n xform ...)
              (async/chan (or buf-or-n 10))))  ; DEFAULT IS 10!

```

**Flow's default buffer size is 10 messages**. You configure buffers per-channel via `chan-opts`:

```clojure
{:procs {:my-proc {:proc (process #'my-step)
                   :chan-opts {:in {:buf-or-n (sliding-buffer 1000)}}}}}

```

### Output Writing with Priority Control

```clojure
;; From impl.clj:230-237 - send-outputs
(let [[v c] (async/alts!!
             [control [outc (first msgs)]]
             :priority true)]  ; <-- control channel takes priority
  ...)

```

Flow uses `alts!!` with priority on the control channel. If output channels are full, the process **blocks** until either:
- Space becomes available in the output buffer
- A control message arrives (pause/stop)

**There is no automatic dropping or sliding behavior.** You must configure buffers explicitly.

### Broadcast Signals Use Sliding Buffer

```clojure
;; From impl.clj:98-99
;; Broadcast signals are conveyed to a process via a channel with an
;; async/sliding-buffer of size 100
castees (reduce (fn [ret {:keys [pid signal-select]}]
                  (assoc ret pid {:select signal-select
                                  :chan (async/chan (async/sliding-buffer 100))}))
                {} (vals pdescs))

```

Broadcast signals (out-of-band messages) use `sliding-buffer 100` by default.

---

## Flow's Introspection Capabilities

### Ping (Live State)

```clojure
;; Query process state
(flow/ping my-flow)
;; => {:reader {::flow/pid :reader
;;              ::flow/status :running
;;              ::flow/state {:count 42}
;;              ::flow/count 1523
;;              ::flow/ins {...}
;;              ::flow/outs {...}}
;;     :parser {...}
;;     :writer {...}}

;; Ping single process
(flow/ping-proc my-flow :parser)

```

The ping response includes:
- Current status (`:paused`, `:running`)
- Process state (your custom map)
- Message count
- Channel info for ins/outs

### Datafy (Static Structure)

```clojure
(datafy my-flow)
;; => {:procs {:reader {:proc symbol, :args {...}}
;;             :parser {...}}
;;     :conns [[[:reader :out] [:parser :in]] ...]
;;     :chans {:ins {...} :outs {...} :error #chan :report #chan}}

```

### Flow Monitor (UI Tool)

The [flow-monitor](https://github.com/clojure/core.async.flow-monitor) provides:
- Web UI at configurable port
- Real-time flow visualization as SVG
- Process state inspection
- Pause/resume controls
- Data injection for testing

```clojure
(require '[clojure.core.async.flow-monitor :as monitor])
(def server (monitor/start-server {:flow my-flow :port 9876}))
;; Visit http://localhost:9876/index.html

```

---

## Comparison to Our Current Architecture

### Our Agent Message Flow

```clojure
;; From seon.ai.claude:688-689
messages-ch (chan (async/sliding-buffer 1000))  ;; Producer: reader loop
result-ch (chan 1)                               ;; Single result

;; Reader loop (lines 714-790)
(with-open [rdr (io/reader stdout)]
  (loop []
    (when-let [line (.readLine rdr)]
      ;; Parse, persist to XTDB, send to channel
      (async/>!! messages-ch msg)
      (when (= msg-type "result")
        (async/>!! result-ch msg))
      (recur))))

```

### Problem Analysis

| Issue | Our Situation | Flow's Answer |
|-------|---------------|---------------|
| **Buffer filling** | Fixed with `sliding-buffer 1000` | Same - configure `chan-opts` |
| **Backpressure strategy** | Drop old messages | Same - explicit buffer choice |
| **No introspection** | Only status-atom | `ping` for state, `datafy` for structure |
| **Configuration in code** | Hardcoded buffers | Data-driven `:chan-opts` |
| **Error handling** | try/catch + logging | Centralized error-chan |
| **Topology hidden** | Emerges from control flow | Explicit `:conns` graph |

### What Flow Would NOT Help

1. **Claude CLI Process Management** - Flow manages threads, not external processes
2. **XTDB Persistence** - Our persistence layer is outside any flow
3. **MCP Protocol** - External I/O, not suited for step functions
4. **Single-Producer/Single-Consumer** - Flow is for complex topologies

### What Flow WOULD Help

1. **Multi-agent coordination** - If we had many agents communicating
2. **Pipeline processing** - Message transformation chains
3. **Introspection** - Better visibility into channel state
4. **Configuration** - Centralized buffer/thread settings

---

## Detailed Recommendations

### 1. Keep Current Architecture for Agent Messages

Our pattern is fundamentally **point-to-point I/O** with a single external process:

```
Claude CLI → stdout → Reader Thread → messages-ch → Consumers
                                    → result-ch → Blocking Caller
                                    → XTDB → Persistence

```

Flow is designed for **complex internal topologies** where:
- Multiple processes communicate via channels
- Topology can be described as a directed graph
- Step functions are pure data transformations

Our reader thread does I/O (reading stdout, XTDB writes), which violates Flow's separation principle.

### 2. The sliding-buffer Fix is Correct

Flow's documentation confirms our approach:

> "Broadcast signals are conveyed to a process via a channel with an async/sliding-buffer of size 100"

When the consumer might be slower than the producer (UI observers, orchestrator blocking), sliding-buffer is the right choice. The only question is buffer size:

- **100** - Flow's default for broadcasts (too small for us)
- **1000** - Our current choice (reasonable for long-running agents)
- **∞** - Unbounded growth (dangerous)

### 3. Adopt Introspection Patterns

We should add `ping`-style introspection to our agent registry:

```clojure
;; Proposed addition to seon.ai.agent
(defn ping-agent
  "Return live state of agent including channel info."
  [{::keys [session-id]}]
  (when-let [handle (get @agent-registry session-id)]
    (let [messages-ch (::messages-ch handle)
          status-atom (::status-atom handle)]
      {::session-id session-id
       ::status @status-atom
       ::messages-buffer-count (count (.-buf messages-ch))  ; reflect into channel
       ::last-activity-at @(::last-activity-at handle)})))

```

### 4. Consider Data-Driven Buffer Configuration

Instead of hardcoding `(sliding-buffer 1000)`, make it configurable:

```clojure
;; config.edn
{:seon.ai.claude/messages-buffer {:type :sliding :size 1000}
 :seon.ai.claude/result-buffer {:type :fixed :size 1}}

;; In launch-agent!
(let [buf-config (get config ::messages-buffer {:type :sliding :size 1000})
      messages-ch (chan (make-buffer buf-config))]
  ...)

```

### 5. Don't Adopt Flow for Simple Use Cases

Flow adds complexity that isn't justified for:
- Single producer → single/few consumers
- External I/O boundaries (process stdout, HTTP)
- Simple request/response patterns

Flow shines for:
- Event processing pipelines
- Multi-stage transformations
- Fan-out/fan-in patterns
- Complex internal communication

---

## Time-Travel Debugging

The announcement mentioned time-travel debugging. After investigation, this is **not a core flow feature** but rather enabled by:

1. **State snapshots** - The step function returns explicit state each transform
2. **Message replay** - Inject historical messages via `flow/inject`
3. **Pause/resume** - Freeze flow, inspect state, continue

To replay history:

```clojure
(flow/pause my-flow)
(let [state (flow/ping-proc my-flow :my-proc)]
  ;; Inspect state
  ...)
(flow/inject my-flow [:my-proc :in] [historical-msg-1 historical-msg-2])
(flow/resume my-flow)

```

This is more "replay capability" than true time-travel. For actual time-travel, you'd need to persist all messages (which we already do in XTDB!) and rebuild state.

---

## Potential Future Use: Agent Orchestration Flow

If Seon grows to coordinate many agents as a dataflow system, Flow could model it:

```clojure
(def orchestration-flow
  {:procs {:task-queue    {:proc (process #'task-queue-step)
                           :args {:db-node xtdb-node}}
           :agent-spawner {:proc (process #'agent-spawner-step)
                           :chan-opts {:in {:buf-or-n (sliding-buffer 100)}}}
           :result-collector {:proc (process #'result-collector-step)}
           :reporter      {:proc (process #'reporter-step)}}

   :conns [[[:task-queue :new-tasks]    [:agent-spawner :in]]
           [[:agent-spawner :spawned]   [:result-collector :agents]]
           [[:result-collector :done]   [:task-queue :completed]]
           [[:result-collector :report] [:reporter :in]]]})

```

But this is premature optimization. Our current single-orchestrator model works.

---

## Summary

| Aspect | Recommendation | Rationale |
|--------|----------------|-----------|
| **Adopt flow for agent messages** | ❌ No | Single producer, external I/O, simple pattern |
| **Keep sliding-buffer** | ✅ Yes | Correct backpressure strategy |
| **Add ping-style introspection** | ✅ Yes | Valuable for observability |
| **Make buffers configurable** | ⚠️ Maybe | Nice-to-have, not urgent |
| **Adopt for orchestration** | ⏳ Later | When we have multi-agent coordination |

### Key Takeaway

**The 100-message blocking bug was a buffer sizing issue, not an architectural flaw.** Our fix (`sliding-buffer 1000`) is exactly what Flow would recommend. The value of Flow is in its **separation of topology from logic**, which matters more for complex internal systems than for external I/O boundaries.

---

## References

- [Announcement](https://clojure.org/news/2025/04/28/async_flow)
- [Flow Documentation](https://clojure.github.io/core.async/flow.html)
- [API Reference](https://clojure.github.io/core.async/clojure.core.async.flow.html)
- [Flow Monitor](https://github.com/clojure/core.async.flow-monitor)
- [Source: flow.clj](https://github.com/clojure/core.async/blob/master/src/main/clojure/clojure/core/async/flow.clj)
- [Source: impl.clj](https://github.com/clojure/core.async/blob/master/src/main/clojure/clojure/core/async/flow/impl.clj)
