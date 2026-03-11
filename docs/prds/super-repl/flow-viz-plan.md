---
type: prd
status: draft
tags: [prd, flow]
---
# Flow Visualizer: Research & Plan

> **See also:** The unified flow architecture in [`docs/prds/unified-flow/design.md`](../unified-flow/design.md) describes how flow ping/datafy are used for observability across the full topology.

## 1. What Data the Flow API Exposes

### `(flow/ping g)` -- Primary introspection tool

Pings all processes, waits up to `timeout-ms` (default 1000), returns map of `pid -> status-map`.

Each status map contains (from `impl.clj` line 271-276):

```clojure
{::flow/pid      :aggregator        ; process id
 ::flow/status   :running           ; :running or :paused
 ::flow/state    {:pending-ns #{}}  ; process-local state (the step-fn state)
 ::flow/count    42                 ; number of messages processed
 ::flow/ins      {:changes <chan>}  ; input channel map (datafied)
 ::flow/outs     {:updates <chan>}} ; output channel map (datafied)
```

The `ins` and `outs` are `postwalk datafy`'d -- channels become their string representation, fns become symbols, vars become symbols.

### `(flow/ping-proc g pid)` -- Single process ping

Same as above but for one process. Returns the status map directly (not wrapped in pid key).

### `(datafy/datafy flow-object)` -- Static topology

Returns the flow config with `postwalk datafy` applied (from `impl.clj` line 87-89):

```clojure
{:procs  {pid -> {:proc step-fn-symbol :args {...} :chan-opts {...}}}
 :conns  [[[:pid1 :out] [:pid2 :in]] ...]
 :execs  {:mixed nil :io nil :compute nil}
 :chans  {:ins  {[pid cid] -> chan-str}
           :outs {[pid cid] -> chan-str}
           :error chan-str
           :report chan-str}}
```

### `report-chan` and `error-chan` -- Returned from `(flow/start g)`

- **report-chan**: Receives `::flow/report` outputs from any process transform, plus ping responses. Sliding buffer of 100.
- **error-chan**: All exceptions from any thread inside a flow. Maps with at least `::flow/ex`, plus context keys like `::flow/pid`, `::flow/state`, `::flow/status`, `::flow/count`, `::flow/cid`, `::flow/msg`.

### What we CAN extract from a running flow

| Data | Source | Real-time? |
|------|--------|-----------|
| Process status (running/paused) | `ping` | On-demand (poll) |
| Process state (arbitrary map) | `ping` | On-demand (poll) |
| Message count per process | `ping` via `::flow/count` | On-demand (poll) |
| Input/output channel refs | `ping` | On-demand (poll) |
| Connection topology | `datafy` | Static (once) |
| Errors with full context | `error-chan` | Streaming |
| Report messages | `report-chan` | Streaming |

### What we CANNOT extract (need to collect ourselves)

| Data | How to collect |
|------|----------------|
| Channel buffer sizes / queue depth | No API. Would need to wrap channels or use reflection on `ManyToManyChannel` internals. |
| Message throughput (msgs/sec) | Derive from `::flow/count` deltas between pings. |
| Latency (processing time per msg) | Instrument step-fns or wrap in timing middleware. |
| Error rate | Count errors from `error-chan` over time windows. |
| Per-connection message counts | Track in our broadcaster step-fn state. |

### Important: `core.async.flow-monitor`

The flow guide references [core.async.flow-monitor](https://github.com/clojure/core.async.flow-monitor/) as the official tool. This is a separate library. We should check if it provides additional introspection beyond `ping`/`datafy`, but for Seon we want our own integrated views anyway.

## 2. What Additional Data We Need to Collect

### A. Throughput tracking (per-process)

Wrap `ping` polling to compute deltas:

```clojure
;; Store previous counts, compute rate
{:pid :aggregator
 :count-at-t0 100
 :count-at-t1 142
 :interval-ms 1000
 :msgs-per-sec 42.0}
```

### B. Error accumulation

Drain `error-chan` in a background thread, accumulate:

```clojure
{:total-errors 3
 :recent-errors [{::flow/pid :broadcaster
                  ::flow/ex #error{...}
                  ::flow/count 99
                  :timestamp <instant>}]
 :error-rate-1m 0.05}   ;; errors per second over last minute
```

### C. Flow registry

Seon may have multiple flows (SSE flow, future domain flows). Need a registry:

```clojure
{:seon.web.sse/flow  {:flow <obj> :started-at <instant> :label "SSE Pipeline"}
 :seon.trading/flow  {:flow <obj> :started-at <instant> :label "Trading Signals"}}
```

Currently `seon.web.sse.flow/flow-state` is a private atom. We need a central registry or a way to discover all flows.

## 3. REPL Status Function Design

### `(user/flow-status)` -- All flows at a glance

```clojure
(user/flow-status)
;; =>
{:flows
 {:sse-pipeline
  {:label "SSE Pipeline"
   :status :running           ;; :running | :stopped | :error
   :uptime-ms 340000
   :processes
   {:aggregator  {:status :running
                  :count 1420
                  :msgs/sec 14.2
                  :state-summary {:pending 0 :debounce-ms 50}}
    :registry    {:status :running
                  :count 38
                  :msgs/sec 0.3
                  :state-summary {:clients 2}}
    :broadcaster {:status :running
                  :count 890
                  :msgs/sec 8.9
                  :state-summary {:broadcast-count 890}}}
   :topology {:process-count 3
              :connections [[:aggregator/updates :broadcaster/updates]]}
   :errors {:total 0
            :rate-1m 0.0
            :recent []}}}

 :alerts []}
```

### `(user/flow-status :sse-pipeline)` -- Single flow detail

Same as above but for one flow, with additional detail:

- Recent errors with stack traces
- Per-process `::flow/state` dump
- Channel list from `datafy`

### Implementation approach

New namespace `seon.flow.status` that:

1. Maintains a flow registry (atom of flow-id -> flow-obj + metadata)
2. Polls `ping` on demand (not continuously -- only when `flow-status` called)
3. Computes throughput from count deltas (keeps last-seen counts in atom)
4. Drains error-chan in background loop, accumulates recent errors

## 4. Web UI Page Design

### Route: `GET /flows` (shim) + `POST /flows` (SSE)

### Layout

```
[nav: dashboard | agents | flows | logs]

FLOWS                                          uptime: 5m 32s
-----------------------------------------------------------------
SSE Pipeline                                   ● running

  [aggregator] ---updates---> [broadcaster]
       ^
  [registry]

  PROCESS          STATUS    COUNT   RATE    STATE
  aggregator       ● run     1420    14/s    pending:0
  registry         ● run     38      <1/s    clients:2
  broadcaster      ● run     890     9/s     total:890

  ERRORS (0 in last 5m)
  (none)

-----------------------------------------------------------------
Trading Signals                                ● stopped
  ...
```

### Components to build

1. **Flow topology graph** -- Simple ASCII/box rendering in HTML. Each process is a `div` box, connections are CSS lines or SVG paths. For v1, a simple horizontal layout is fine; processes left-to-right with arrows.

2. **Process table** -- Reuse `components/table-header` and `components/table-cell`. Columns: name, status dot, count, rate, state summary.

3. **Error feed** -- Reuse `components/log-container` and `components/log-line`. Show recent errors with timestamp, pid, exception message. Expandable for full stack trace.

4. **Flow header** -- Label + status dot + uptime. Reuse `components/page-header` and `components/status-dot`.

### SSE data flow

```
[poll timer] -> (flow/ping all-flows) -> atom -> watch -> refresh-all!
[error-chan drain] -> atom -> watch -> refresh-all!
```

The SSE handler (`POST /flows`) calls a render function that reads the status atom and produces the full page HTML. Polling interval: 1 second (configurable). The `render-handler` pattern (hash-based dedup) prevents sending unchanged HTML.

### Datastar attributes needed

- `data-init="@post('/flows')"` -- establish SSE on page load
- `data-on:online__window="@post('/flows')"` -- reconnect
- `data-on-click="@post('/api/flows/:id/pause')"` -- pause/resume buttons (stretch goal)

## 5. Implementation Steps

### Step 1: Flow Registry (`seon.flow.registry`)

- Central atom mapping flow-id -> {:flow, :started-at, :label, :config}
- `register!`, `unregister!`, `list-flows`, `get-flow` functions
- Modify `seon.web.sse.flow/start!` to register itself
- **Test:** Register/unregister/list round-trip

### Step 2: Flow Status Collector (`seon.flow.status`)

- `collect-status` -- pings all registered flows, returns structured map
- Throughput calculation from count deltas (keeps previous counts in atom)
- Error accumulator -- background go-loop draining error-chans
- **Test:** Mock flow, verify status map shape with Malli schema

### Step 3: REPL Helper (`user/flow-status`)

- Wire `seon.flow.status/collect-status` into user namespace
- Pretty-print with aligned columns for REPL readability
- **Test:** Call it, verify no exceptions, verify shape

### Step 4: Web UI -- Route + Shim Page

- Add `/flows` GET/POST routes to `seon.web.routes`
- Add "flows" to nav-bar in `seon.web.html`
- Create `seon.web.flows` namespace with shim page + skeleton
- **Test:** `curl localhost:8080/flows` returns HTML

### Step 5: Web UI -- SSE Handler + Render

- Polling loop that calls `flow.status/collect-status` every 1s into atom
- Watch on atom triggers `sse/refresh-all!`
- Render function produces Hiccup from status data
- Process table, error feed, flow header
- **Test:** Start SSE flow, verify page shows process states

### Step 6: Web UI -- Topology Visualization

- Simple box+arrow layout using flex/grid CSS
- Parse `:conns` from `datafy` to build adjacency
- Processes as cards, connections as styled borders/arrows
- Color-code by status
- **Test:** Visual inspection in browser

### Step 7: Flow Controls (stretch)

- Pause/resume individual processes via POST endpoints
- Pause/resume entire flow
- Wire buttons in UI with datastar click handlers

## 6. Existing Seon Components to Reuse

| Component | From | Use for |
|-----------|------|---------|
| `components/page-header` | `seon.web.components` | Flow page header |
| `components/section-header` | `seon.web.components` | Section labels |
| `components/card` | `seon.web.components` | Process cards |
| `components/status-dot` | `seon.web.components` | Process/flow status |
| `components/table-header/cell` | `seon.web.components` | Process table |
| `components/log-line/container` | `seon.web.components` | Error feed |
| `components/empty-state` | `seon.web.components` | No flows running |
| `html/base-page` | `seon.web.html` | Page shell |
| `html/nav-bar` | `seon.web.html` | Navigation (add "flows" tab) |
| `sse/render-handler` | `seon.web.sse` | SSE streaming with hash dedup |
| `sse.flow/ping` | `seon.web.sse.flow` | Reference for how to call flow/ping |
| Phosphor Terminal theme | `input.css` | All styling via Tailwind classes |

### Key design decisions

1. **Poll, don't push** for flow status. `flow/ping` is cheap (< 1ms typically) and avoids needing to instrument every message path. 1s polling is fine for human consumption; REPL function is on-demand only.

2. **Registry pattern** over hardcoded flow references. As Seon grows domain flows (trading, health), discovery must be automatic.

3. **Malli schemas** for all status maps. The REPL function and web handler both consume the same schema-validated data.

4. **No channel buffer introspection** in v1. core.async channels don't expose buffer fullness via public API. If needed later, we can wrap channels in a monitoring layer at flow creation time.
