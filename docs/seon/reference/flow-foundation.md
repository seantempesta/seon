---
type: reference
status: active
tags: [reference, flow]
---
# Architectural Analysis: core.async.flow as Seon's Foundation

**Date:** 2026-01-30
**Status:** Research Complete
**Author:** Research Agent

## Executive Summary

This document analyzes whether Rich Hickey's `core.async.flow` (January 2025) should become the foundational architecture for Seon's agent infrastructure.

**Conclusion: Selective adoption, not wholesale replacement.**

Flow's design philosophy (separating topology from logic) is valuable and should inform Seon's architecture. However, Flow cannot be the core foundation because:

1. **Seon agents ARE external processes** (Claude CLI), not Clojure functions
2. **Flow can't serialize opaque state** - Claude's context window is inaccessible
3. **Resource management isn't Flow's domain** - nREPL/XTDB per agent requires Integrant
4. **We already have solutions** - XTDB for persistence, Malli for contracts

Flow SHOULD be adopted for internal orchestration (message routing, status aggregation, SSE streaming) where its strengths apply.

---

## 1. Vision Alignment

How does Flow relate to Seon's 7-layer architecture from [[vision/index]]?

### Layer 1: Contracts & Discovery

**Flow contribution:** None
Flow has no concept of schemas or contracts. Seon's Malli registry remains the solution.

### Layer 2: Agent Isolation

**Flow contribution:** Partial
Flow processes are isolated (no shared state between step functions). But Seon needs **resource isolation** (nREPL, XTDB per agent), which Flow doesn't provide. Integrant remains the solution.

### Layer 3: Verification

**Flow contribution:** None
Flow doesn't do testing. Dev hooks + Kaocha + generative testing remain.

### Layer 4: Observability

**Flow contribution:** Strong
Flow's introspection (`ping`, `datafy`) and flow-monitor UI are excellent. These patterns should inform Seon's Observatory, even if we don't use Flow directly.

### Layer 5: Dynamic Context (The Cockpit)

**Flow contribution:** Partial
Flow's `ping` returns live process state - useful for the cockpit. BUT: Claude's context window is opaque. Flow can't help with context window management.

### Layer 6: Learning from History

**Flow contribution:** None
Flow state is in-memory. XTDB remains the solution for temporal queries.

### Layer 7: Long-term Ownership

**Flow contribution:** None
Flow processes don't "own" code. Agents as persistent namespace stewards requires custom design.

---

## 2. The Fundamental Mismatch

Flow was designed for **concurrent data processing pipelines**:

```
Source → Transform-A → Transform-B → Sink
              ↘ Aggregator ↗

```

Seon's agents are **long-running external services**:

```
Orchestrator
    │
    ├── Claude CLI (subprocess, 10-60 min lifetime)
    │       └── reads MCP → evaluates code → writes stdout
    │
    ├── nREPL Server (per agent, TCP connection)
    │       └── evaluates agent's Clojure code
    │
    └── XTDB Database (per agent, persisted)
            └── stores agent's context and history

```

**The mismatch:**

| Flow Assumption | Seon Reality |
|-----------------|--------------|
| Processes are Clojure threads | Agents are external subprocesses |
| State returns from step functions | State lives in Claude's context window |
| Channels for all I/O | stdout/stdin for Claude communication |
| Microsecond message processing | Minutes/hours of agent execution |
| Pause = stop reading channels | Pause Claude = not possible (interrupt or die) |

---

## 3. What Flow WOULD Help With

Despite the mismatch at the agent level, Flow is excellent for **internal orchestration**:

### 3.1 Message Routing Flow

```clojure
(def orchestration-flow
  {:procs
   {:api-gateway
    {:proc (process #'api-gateway-step)
     :args {:port 8080}}

    :task-router
    {:proc (process #'task-router-step)
     :chan-opts {:tasks {:buf-or-n (sliding-buffer 100)}}}

    :agent-dispatcher
    {:proc (process #'agent-dispatcher-step)
     :args {:max-concurrent-agents 10}}

    :status-aggregator
    {:proc (process #'status-aggregator-step)
     :signal-select #{::agent-status-update}}

    :observatory-sse
    {:proc (process #'observatory-sse-step)
     :args {:http-response-fn send-sse}}}

   :conns
   [[[:api-gateway :tasks] [:task-router :in]]
    [[:task-router :dispatched] [:agent-dispatcher :in]]
    [[:status-aggregator :updates] [:observatory-sse :in]]]})

```

**Benefits:**

- Explicit topology (visible in data, not hidden in code)
- Centralized error handling via `:error-chan`
- Introspection via `ping` for debugging
- Hot reload of step functions

### 3.2 External Adapter Pattern

```
┌──────────────────────────────────────────────────────────┐
│                     FLOW LAYER                           │
│  (message routing, aggregation, SSE streaming)           │
│                                                          │
│  :task-router → :agent-dispatcher → :status-aggregator   │
│                      ↓          ↑                        │
│              agent-commands  agent-events                │
└───────────────────────┬──────────┬───────────────────────┘
                        │          │
                        ↓          ↑
┌───────────────────────┴──────────┴───────────────────────┐
│                   ADAPTER LAYER                          │
│  (external process management, resource lifecycle)       │
│                                                          │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐      │
│  │ Adapter-A1B2│  │ Adapter-C3D4│  │ Adapter-E5F6│      │
│  │ Claude CLI  │  │ Claude CLI  │  │ Claude CLI  │      │
│  │ nREPL 7889  │  │ nREPL 7890  │  │ nREPL 7891  │      │
│  │ XTDB agent1 │  │ XTDB agent2 │  │ XTDB agent3 │      │
│  └─────────────┘  └─────────────┘  └─────────────┘      │
└──────────────────────────────────────────────────────────┘

```

Each adapter:

- Owns a Claude CLI subprocess
- Has a reader thread bridging stdout → channel
- Registers with the agent registry
- Publishes events to Flow layer via channel

---

## 4. Agent Lifecycle via Flow (Detailed Design)

If we adopt Flow for orchestration, here's how agent lifecycle would work:

### 4.1 Agent Dispatcher Step Function

```clojure
(defn agent-dispatcher-step
  "Manages agent lifecycle commands"
  ([]
   {:ins {:commands "spawn/stop/status commands"}
    :outs {:events "agent lifecycle events"
           :errors "failed operations"}
    :params {:max-agents "concurrent limit"}})

  ([{:keys [max-agents]}]
   {:agents {}       ; session-id → adapter
    :pending []      ; tasks waiting for slots
    :max-agents max-agents})

  ([state transition]
   (case transition
     ::flow/stop
     (do
       ;; Gracefully stop all agents
       (doseq [[_ adapter] (:agents state)]
         ((.close! adapter)))
       state)
     state))

  ([state :commands {:keys [command session-id] :as cmd}]
   (case command
     :spawn
     (if (< (count (:agents state)) (:max-agents state))
       (let [adapter (create-adapter! cmd)  ; Creates subprocess, nREPL, etc.
             new-state (assoc-in state [:agents session-id] adapter)]
         [new-state {:events [{:type :agent-spawned
                               :session-id session-id}]}])
       ;; Queue if at capacity
       [(update state :pending conj cmd) nil])

     :stop
     (when-let [adapter (get-in state [:agents session-id])]
       ((.close! adapter))
       [(update state :agents dissoc session-id)
        {:events [{:type :agent-stopped
                   :session-id session-id}]}])

     :status
     [state {:events [{:type :status-response
                       :agents (keys (:agents state))
                       :pending (count (:pending state))}]}])))

```

### 4.2 Status Aggregator Step Function

```clojure
(defn status-aggregator-step
  "Aggregates agent status updates for Observatory"
  ([]
   {:ins {:agent-events "events from agents"}
    :outs {:updates "aggregated status for SSE"}
    :signal-select #{::agent-heartbeat}})

  ([_args]
   {:last-status {}})  ; session-id → last known status

  ([state _transition] state)

  ([state in-id msg]
   (case in-id
     :agent-events
     (let [{:keys [session-id type]} msg
           new-state (assoc-in state [:last-status session-id] msg)]
       [new-state {:updates [msg]}])

     ::agent-heartbeat
     ;; Broadcast heartbeats arrive here
     [state {:updates [{:type :heartbeat
                        :active-agents (count (:last-status state))}]}])))

```

---

## 5. State Serialization Analysis

### Can Flow Enable Agent Hibernation?

**Short answer: No, because Claude's state is opaque.**

Flow step functions return explicit state, enabling:

1. `(ping flow)` → capture all process states
2. Serialize to disk (EDN/Transit)
3. On restart: restore state in init

**BUT:** This only serializes OUR state, not Claude's.

Claude's "state" is:

- The conversation history (we can serialize via XTDB)
- The parsed context window (we cannot access)
- Internal reasoning state (completely opaque)

### What We CAN Serialize

| Component | Can Serialize? | Location |
|-----------|----------------|----------|
| Orchestrator state | Yes | Flow ping → EDN |
| Task queue | Yes | Flow process state |
| Agent registry | Yes | Atom → EDN |
| XTDB data | Yes | Already persisted |
| nREPL session | No | JVM-level state |
| Claude context | No | Opaque to us |

### Hibernation Workarounds

**Option A: Soft Resume**

1. Agent hits context limit
2. Summarize progress to XTDB
3. Kill Claude CLI
4. Start new Claude CLI
5. Replay message history + summary as context
6. Agent continues with "memory" of what happened

**Option B: Task Handoff**

1. Agent completes subtask
2. Writes progress summary
3. New agent picks up next subtask
4. Clean boundaries, less context baggage

Neither requires Flow - these are prompt engineering patterns.

---

## 6. External System Adapters (API Protocols)

Flow could standardize how Seon integrates with external systems:

### 6.1 Example: GitHub Adapter

```clojure
(defn github-adapter-step
  "Rate-limited GitHub API access"
  ([]
   {:ins {:requests "GitHub API requests"}
    :outs {:responses "API responses"
           :rate-limited "requests that hit rate limit"}
    :params {:token "GitHub PAT"
             :rate-limit "requests per minute"}})

  ([{:keys [token rate-limit]}]
   {:token token
    :rate-limit rate-limit
    :request-times []  ; Sliding window for rate limiting
    })

  ([state _transition] state)

  ([{:keys [token rate-limit request-times] :as state} :requests req]
   (let [now (System/currentTimeMillis)
         recent (filter #(> % (- now 60000)) request-times)]
     (if (< (count recent) rate-limit)
       ;; Make request
       (let [response (http/request (assoc req :oauth-token token))]
         [(assoc state :request-times (conj recent now))
          {:responses [response]}])
       ;; Rate limited
       [state {:rate-limited [req]}]))))

```

### 6.2 Benefits of Flow for External APIs

- **Centralized rate limiting** - enforced in step function
- **Request queuing** - buffer configuration via chan-opts
- **Error handling** - failures go to error-chan
- **Introspection** - ping shows pending requests, rate limit status

---

## 7. Migration Path

If we adopt Flow selectively, here's an incremental approach:

### Phase 1: Observatory SSE (Low Risk)

Replace current SSE implementation with Flow-based streaming:

- Single process: SSE sender
- Benefits: cleaner shutdown, better error handling

### Phase 2: Status Aggregation (Medium Risk)

Add Flow layer between agent registry and Observatory:

- Processes: status-aggregator, sse-sender
- Benefits: decoupled UI updates, ping for debugging

### Phase 3: Task Routing (Higher Risk)

Route incoming tasks through Flow:

- Processes: api-gateway, task-router, agent-dispatcher
- Benefits: explicit topology, rate limiting, priority queues

### Phase 4: External Adapters (Optional)

Wrap external APIs (GitHub, Slack, etc.) in Flow adapters:

- Benefits: standardized rate limiting, caching, error handling

---

## 8. Risks and Gaps

### 8.1 Risks of Adopting Flow

| Risk | Mitigation |
|------|------------|
| Learning curve | Start with single-process flows |
| Debugging complexity | Use flow-monitor, extensive logging |
| Performance overhead | Benchmark before committing |
| Alpha status | "Names and other details are in flux" |

### 8.2 Gaps Flow Doesn't Fill

| Gap | Current Solution | Notes |
|-----|------------------|-------|
| External process mgmt | JVM Process API | Flow is for threads |
| Resource lifecycle | Integrant | nREPL, XTDB per agent |
| Persistence | XTDB | Flow state is in-memory |
| Context management | MCP protocol | Claude's window is opaque |
| Schema discovery | Malli registry | Flow has no contracts |
| Generative testing | Kaocha + Malli | Flow doesn't test |

### 8.3 What We'd Build Anyway

Even with Flow, we still need:

- Claude CLI adapter (subprocess management)
- nREPL session wrapper (per-agent REPLs)
- XTDB integration (persistence)
- MCP protocol handler (tool calls)
- Schema introspection (function discovery)

Flow handles internal message routing. Everything else remains custom.

---

## 9. Key Questions Answered

### Q1: Can an agent's ENTIRE state be captured by a step function's state map?

**No.** Claude's context window is the agent's "real" state, and it's opaque to us. We can only capture:

- Our orchestration state (task queue, agent registry)
- Conversation history (via XTDB)
- Tool call history (via XTDB)

### Q2: Can we serialize a paused flow to disk and restore it later?

**Partially.** Flow process state from `ping` can be serialized. But:

- Channels can't be serialized
- Running threads can't be frozen
- External processes (Claude CLI) can't be checkpointed

We'd need to rebuild the flow and restore only the data state.

### Q3: How would Claude CLI processes fit into flow topology?

**As external adapters.** Each Claude CLI gets a wrapper that:

- Manages the subprocess lifecycle
- Bridges stdout/stdin to channels
- Registers with Flow via in-ports/out-ports
- Publishes events to the Flow layer

The subprocess itself is outside Flow's control.

### Q4: Could this enable true agent hibernation and restoration?

**Not true hibernation.** Claude's context is lost when the process dies.

**Best we can do:**

- Soft resume via message history replay
- Task handoff via progress summaries
- XTDB-based "memory" across agent lifetimes

### Q5: What's the performance overhead vs raw channels?

**Minimal for Seon's use case.** Flow adds:

- Control channel checking per message (alts!! priority)
- State management in loop
- ping response handling

For microsecond message processing, this matters. For agents that run for minutes, it's negligible.

---

## 10. Recommendations

### Adopt: Flow Patterns

Even if we don't use Flow directly, adopt its patterns:

- **Explicit topology** - Define process connections as data
- **Step function separation** - describe/init/transition/transform
- **Introspection** - ping-style live state queries
- **Centralized errors** - Single error handling location

### Adopt Selectively: Flow Library

Use Flow for internal orchestration where it excels:

- Status aggregation
- SSE streaming
- External API adapters (rate limiting, caching)
- Task routing (priority, queuing)

### Don't Use: Flow for Agent Core

Keep the current architecture for agent lifecycle:

- Integrant for resource management (nREPL, XTDB)
- Direct subprocess management for Claude CLI
- Atoms for agent registry (simple, debuggable)
- XTDB for persistence (already works well)

### Investigate: Flow Monitor for Observatory

The flow-monitor UI could inspire Observatory improvements:

- Real-time flow visualization
- Process state inspection
- Pause/resume controls

---

## 11. Conclusion

**core.async.flow is not the right foundation for Seon's entire architecture.**

The fundamental mismatch is that Flow is designed for Clojure threads processing messages, while Seon's agents are long-running external processes with opaque state.

**However, Flow's patterns are valuable:**

1. **Separation of topology from logic** - Express process connections as data
2. **Explicit state management** - Return state from transforms
3. **Introspection** - Live state queries for debugging
4. **Centralized coordination** - Control channel for lifecycle

**Recommended approach:**

- Use Flow for **internal orchestration** (routing, aggregation, SSE)
- Keep current approach for **agent lifecycle** (subprocess, Integrant)
- Adopt Flow **patterns** even where we don't use the library
- Continue using **XTDB** for persistence, **Malli** for contracts

The 7-layer vision doesn't require Flow. But Flow's philosophy of declarative dataflow aligns with Clojure's data-oriented design and should inform how we build the layers.
