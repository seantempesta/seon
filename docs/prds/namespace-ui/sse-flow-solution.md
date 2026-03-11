---
type: prd
status: completed
tags: [prd, flow, web]
---
# SSE Live Reload: Analysis and core.async.flow Solution

## Executive Summary

After analyzing the current SSE implementation and prior research, I've identified the **actual problems** and designed a **Flow-based solution** that addresses them. The key insight is that the current issues aren't primarily about hot reload (which is already solved via `after-ns-reload` hooks), but about **three architectural gaps**:

1. **No centralized event routing** - Code changes happen, but there's no clean way to propagate them to all SSE clients
2. **No observability** - Can't introspect which clients are connected, what updates are pending
3. **No backpressure handling** - Slow clients can affect the whole system

**Recommendation:** Adopt `core.async.flow` selectively for SSE streaming infrastructure, keeping the existing hot reload pattern.

---

## 1. What's Actually Broken

### 1.1 The Investigation Trail

The prior documents show a progression of understanding:

| Document | Finding |
|----------|---------|
| `sse-live-reload-investigation.md` | Identified `def` closure capture problem |
| `sse-live-reload-fix.md` | Fixed via `after-ns-reload` hooks + `:reload-hook` config |
| `async-flow-research.md` | Recommended against Flow for agent messages (correct) |
| `flow-foundation.md` | Identified Flow's strengths for internal orchestration |

The hot reload problem **is solved**. The current `after-ns-reload` pattern works:

```clojure
;; src/seon/web/handlers.clj:131-137
(defn after-ns-reload
  "Called by clj-reload after namespace reload. Recreates SSE handlers."
  []
  (alter-var-root #'dashboard-sse
                  (constantly (sse/render-handler #'dashboard-sse-render)))
  (alter-var-root #'log-viewer-sse
                  (constantly (sse/render-handler #'log-viewer-sse-render :poll-ms 2000))))
```

### 1.2 The Remaining Gaps

But examining `src/seon/web/sse.clj` reveals deeper architectural issues:

**Gap 1: Broadcast Without Change Detection**

```clojure
;; sse.clj:183-190
(defn refresh-all!
  "Trigger a refresh for all connected SSE clients."
  [& _opts]
  (when-let [<refresh-ch @refresh-ch_]
    (a/>!! <refresh-ch :refresh-event)))
```

This broadcasts to all clients regardless of whether the change affects them. A change to `seon.trading.signals` triggers refresh for clients viewing `seon.web.agents`.

**Gap 2: No Client Lifecycle Tracking**

```clojure
;; sse.clj:145-181 - render-handler
(hk/as-channel req
  {:on-open (fn hk-on-open [ch] ...)
   :on-close (fn hk-on-close [_ch _status]
               (a/>!! <cancel :cancel)
               (a/untap (:seon.web.sse/refresh-mult req) <ch)
               (when on-close (on-close req)))})
```

No registry of connected clients. Can't answer:

- How many clients connected?
- Which pages are they viewing?
- When did they last receive an update?

**Gap 3: Polling as Primary Mechanism**

Most handlers use polling (`:poll-ms 2000`) rather than event-driven updates:

```clojure
;; src/seon/web/agents.clj:676-677
(def agents-sse
  (sse/render-handler #'agents-sse-render :poll-ms 2000))
```

This means:

- Latency: Up to 2 seconds for updates to appear
- Wasted work: Re-renders every 2s even if nothing changed
- No change-driven updates

**Gap 4: No Debouncing for Rapid Changes**

Code changes can come in bursts (e.g., agent writes multiple files quickly). Each change calls `refresh-all!`, potentially causing:

- Render storms
- Dropped updates (dropping-buffer)
- Browser DOM thrashing

---

## 2. Flow-Based Solution Design

### 2.1 Why Flow Fits Here

The prior research correctly identified that Flow is wrong for **agent message handling** (external process, opaque state). But Flow is perfect for **internal SSE orchestration** because:

| Flow Strength | SSE Need |
|---------------|----------|
| Declarative topology | Route changes to relevant clients |
| Centralized error handling | One place to handle SSE errors |
| Introspection (`ping`) | Query connected clients, pending updates |
| Status aggregation | Combine multiple rapid changes |
| Hot-swappable step functions | Upgrade logic without reconnects |

### 2.2 Proposed Flow Topology

```
                                ┌─────────────────────────────────────────┐
                                │            CODE CHANGE EVENTS           │
                                │  (from clj-reload, dev hooks, manual)   │
                                └─────────────────┬───────────────────────┘
                                                  │
                                                  ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│                              FLOW LAYER                                        │
│                                                                                │
│  ┌─────────────┐     ┌─────────────────┐     ┌──────────────────┐            │
│  │  :changes   │     │   :aggregator   │     │  :sse-broadcaster│            │
│  │ (in-port)   │────▶│  (debounce +    │────▶│  (fan-out to     │            │
│  │             │     │   namespace     │     │   connected      │            │
│  └─────────────┘     │   grouping)     │     │   clients)       │            │
│                      └─────────────────┘     └────────┬─────────┘            │
│                                                       │                       │
│  ┌─────────────┐                              ┌───────┴───────┐              │
│  │  :registry  │◀─────────────────────────────│               │              │
│  │ (client     │         (register/           │   ::flow/     │              │
│  │  tracking)  │          unregister)         │   report      │              │
│  └─────────────┘                              └───────────────┘              │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘
                                                  │
                                                  ▼
                                    ┌─────────────────────────┐
                                    │     HTTP-KIT CHANNELS   │
                                    │  (per-client SSE conn)  │
                                    └─────────────────────────┘
```

### 2.3 Flow Definition (Data)

```clojure
(ns seon.web.sse.flow
  "Flow-based SSE infrastructure for code change propagation."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [malli.core :as m]))

;;; ---------------------------------------------------------------------------
;;; Malli Schemas
;;; ---------------------------------------------------------------------------

(def ChangeEvent
  "Schema for code change events."
  [:map
   [:seon.sse/event-type [:enum :namespace-reload :file-change :manual-refresh]]
   [:seon.sse/namespace {:optional true} :symbol]
   [:seon.sse/file-path {:optional true} :string]
   [:seon.sse/timestamp inst?]])

(def ClientInfo
  "Schema for connected SSE client info."
  [:map
   [:seon.sse/client-id :uuid]
   [:seon.sse/connected-at inst?]
   [:seon.sse/page [:enum :dashboard :agents :agent-detail :logs :namespace]]
   [:seon.sse/page-params {:optional true} :map]  ; e.g., {:agent-id "a1b2"}
   [:seon.sse/http-channel :any]
   [:seon.sse/last-update-at {:optional true} inst?]])

(def AggregatedUpdate
  "Schema for debounced, aggregated updates."
  [:map
   [:seon.sse/namespaces [:set :symbol]]
   [:seon.sse/pages [:set :keyword]]
   [:seon.sse/timestamp inst?]])

;;; ---------------------------------------------------------------------------
;;; Step Functions
;;; ---------------------------------------------------------------------------

(defn aggregator-step
  "Debounce and aggregate rapid code changes.
   Groups changes by namespace, waits for quiet period before emitting."
  ([]
   {:ins {:changes "Raw code change events"}
    :outs {:updates "Aggregated updates for broadcasting"}
    :params {:debounce-ms "Quiet period before emitting (default 50ms)"}})

  ([{:keys [debounce-ms] :or {debounce-ms 50}}]
   {:pending-namespaces #{}
    :pending-pages #{}
    :last-change-ms 0
    :debounce-ms debounce-ms})

  ([state transition]
   (case transition
     ::flow/stop state  ; cleanup if needed
     state))

  ([state :changes {:seon.sse/keys [namespace] :as event}]
   (let [now (System/currentTimeMillis)
         page (namespace->page namespace)  ; derive affected page
         new-state (-> state
                       (update :pending-namespaces conj namespace)
                       (update :pending-pages conj page)
                       (assoc :last-change-ms now))]
     ;; Check if we should emit (debounce logic)
     (if (> (- now (:last-change-ms state)) (:debounce-ms state))
       ;; Quiet period passed - emit aggregated update
       [(assoc new-state :pending-namespaces #{} :pending-pages #{})
        {:updates [{:seon.sse/namespaces (:pending-namespaces new-state)
                    :seon.sse/pages (:pending-pages new-state)
                    :seon.sse/timestamp (java.time.Instant/now)}]}]
       ;; Still in burst - accumulate
       [new-state nil]))))

(defn registry-step
  "Track connected SSE clients."
  ([]
   {:ins {:register "New client connections"
          :unregister "Client disconnections"}
    :outs {:report "Client list for introspection"}
    :signal-select #{::ping-clients}})

  ([_args]
   {:clients {}})  ; client-id -> ClientInfo

  ([state transition]
   (case transition
     ::flow/stop
     (do
       ;; Close all client connections gracefully
       (doseq [[_ client] (:clients state)]
         (try
           (org.httpkit.server/close (:seon.sse/http-channel client))
           (catch Exception _)))
       state)
     state))

  ([state input msg]
   (case input
     :register
     (let [client-id (:seon.sse/client-id msg)]
       [(assoc-in state [:clients client-id] msg)
        {::flow/report [{:type :client-registered :client-id client-id}]}])

     :unregister
     (let [client-id (:seon.sse/client-id msg)]
       [(update state :clients dissoc client-id)
        {::flow/report [{:type :client-unregistered :client-id client-id}]}])

     ;; Handle ping signal - report all clients
     ::ping-clients
     [state {::flow/report [{:type :client-list
                             :clients (vals (:clients state))
                             :count (count (:clients state))}]}])))

(defn broadcaster-step
  "Fan out updates to relevant connected clients."
  ([]
   {:ins {:updates "Aggregated updates to broadcast"
          :clients "Current client registry snapshot"}
    :outs {:sent "Confirmation of sent updates"}
    :params {:registry-pid "PID of registry process for client lookup"}})

  ([{:keys [registry-pid]}]
   {:registry-pid registry-pid
    :last-broadcast-ms 0})

  ([state transition] state)

  ([state :updates {:seon.sse/keys [pages] :as update}]
   (let [;; Get current clients from registry via ping
         ;; (In practice, we'd get this via signal or shared state)
         now (System/currentTimeMillis)]
     ;; For each client viewing an affected page, trigger re-render
     ;; This is handled by the SSE connection itself - we just signal
     [(assoc state :last-broadcast-ms now)
      {:sent [{:type :broadcast-sent
               :pages pages
               :timestamp (java.time.Instant/now)}]}])))

;;; ---------------------------------------------------------------------------
;;; Flow Configuration
;;; ---------------------------------------------------------------------------

(def sse-flow-config
  "Flow configuration for SSE infrastructure."
  {:procs
   {:aggregator
    {:proc (flow/process #'aggregator-step)
     :args {:debounce-ms 50}
     :chan-opts {:changes {:buf-or-n (async/sliding-buffer 100)}}}

    :registry
    {:proc (flow/process #'registry-step)
     :chan-opts {:register {:buf-or-n 10}
                 :unregister {:buf-or-n 10}}}

    :broadcaster
    {:proc (flow/process #'broadcaster-step)
     :args {:registry-pid :registry}}}

   :conns
   [[[:aggregator :updates] [:broadcaster :updates]]]})

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defonce ^:private flow-state (atom nil))

(defn start!
  "Start the SSE flow. Returns flow handle with :report-chan and :error-chan."
  []
  (when-not @flow-state
    (let [fl (flow/create-flow sse-flow-config)
          chans (flow/start fl)]
      (reset! flow-state {:flow fl :chans chans})
      (flow/resume fl)
      chans)))

(defn stop!
  "Stop the SSE flow."
  []
  (when-let [{:keys [flow]} @flow-state]
    (flow/stop flow)
    (reset! flow-state nil)))

(defn ping
  "Introspect flow state. Returns map of process states."
  []
  (when-let [{:keys [flow]} @flow-state]
    (flow/ping flow)))

(defn emit-change!
  "Emit a code change event into the flow."
  [{:seon.sse/keys [event-type namespace file-path] :as event}]
  (when-let [{:keys [flow]} @flow-state]
    ;; Inject into the changes in-port of aggregator
    (flow/inject flow [:aggregator :changes]
                 [(assoc event :seon.sse/timestamp (java.time.Instant/now))])))

(defn register-client!
  "Register a new SSE client connection."
  [client-info]
  (when-let [{:keys [flow]} @flow-state]
    (flow/inject flow [:registry :register] [client-info])))

(defn unregister-client!
  "Unregister a disconnecting SSE client."
  [client-id]
  (when-let [{:keys [flow]} @flow-state]
    (flow/inject flow [:registry :unregister] [{:seon.sse/client-id client-id}])))

(defn connected-clients
  "Get list of currently connected clients."
  []
  (when-let [{:keys [flow]} @flow-state]
    (flow/ping-proc flow :registry)))
```

---

## 3. Integration with Existing System

### 3.1 Hook into clj-reload

The dev hook already calls `clj-reload/reload`. We add Flow notification:

```clojure
;; In user.clj or seon.dev.hook
(defn reload-with-flow-notify
  "Reload changed code and notify SSE flow."
  []
  (let [result (reload/reload {:throw false})
        loaded-ns (or (:loaded result) [])]
    ;; Notify SSE flow of changed namespaces
    (doseq [ns-sym loaded-ns]
      (seon.web.sse.flow/emit-change!
       {:seon.sse/event-type :namespace-reload
        :seon.sse/namespace ns-sym}))
    result))
```

### 3.2 Integrate with render-handler

Modify `render-handler` to register/unregister with Flow:

```clojure
(defn render-handler
  "Create an SSE handler with Flow integration."
  [render-fn & {:keys [on-open on-close page page-params poll-ms]
                :or {poll-ms nil}}]
  (fn handler [req]
    (let [client-id (java.util.UUID/randomUUID)
          ;; Tap into Flow's broadcast for this page
          <updates (async/chan (async/dropping-buffer 1))]

      (hk/as-channel req
        {:on-open
         (fn hk-on-open [ch]
           ;; Register with Flow
           (flow/register-client!
            {:seon.sse/client-id client-id
             :seon.sse/connected-at (java.time.Instant/now)
             :seon.sse/page page
             :seon.sse/page-params page-params
             :seon.sse/http-channel ch})
           ;; Start render loop...
           (when on-open (on-open req)))

         :on-close
         (fn hk-on-close [_ch _status]
           ;; Unregister from Flow
           (flow/unregister-client! client-id)
           (async/close! <updates)
           (when on-close (on-close req)))}))))
```

### 3.3 Keep after-ns-reload Pattern

The `after-ns-reload` hooks remain for handler recreation:

```clojure
;; This pattern still works and is orthogonal to Flow
(defn after-ns-reload []
  (alter-var-root #'agents-sse
    (constantly (sse/render-handler #'agents-sse-render
                  :page :agents
                  :poll-ms 2000))))
```

Flow handles **when** to trigger re-renders; `after-ns-reload` handles **what** gets re-rendered.

---

## 4. Benefits of This Design

### 4.1 Observability

```clojure
;; From REPL or Observatory
(seon.web.sse.flow/ping)
;; => {:aggregator {:seon.sse.flow/status :running
;;                  :seon.sse.flow/state {:pending-namespaces #{}
;;                                        :last-change-ms 1706621234567}}
;;     :registry {:seon.sse.flow/status :running
;;                :seon.sse.flow/state {:clients {#uuid "..." {...}
;;                                                #uuid "..." {...}}}}
;;     :broadcaster {:seon.sse.flow/status :running
;;                   :seon.sse.flow/state {:last-broadcast-ms 1706621234500}}}

(seon.web.sse.flow/connected-clients)
;; => {:seon.sse.flow/state {:clients {...}}
;;     :seon.sse.flow/count 3}
```

### 4.2 Targeted Updates

Instead of broadcasting to all clients:

```clojure
;; Old: refresh ALL clients regardless of what changed
(sse/refresh-all!)

;; New: emit change, Flow routes to relevant clients
(flow/emit-change! {:seon.sse/event-type :namespace-reload
                    :seon.sse/namespace 'seon.trading.signals})
;; Only clients viewing trading-related pages get updates
```

### 4.3 Debouncing Built-In

The aggregator step handles burst changes:

```
t=0ms:   Edit seon/trading/signals.clj   → aggregator accumulates
t=10ms:  Edit seon/trading/execution.clj → aggregator accumulates
t=20ms:  Edit seon/trading/risk.clj      → aggregator accumulates
t=70ms:  (50ms quiet period passed)      → emit single aggregated update
```

### 4.4 Error Centralization

All SSE errors go to one place:

```clojure
(let [{:keys [error-chan]} (flow/start!)]
  (async/go-loop []
    (when-let [err (async/<! error-chan)]
      (log/error "SSE flow error" err)
      (recur))))
```

### 4.5 Clean Shutdown

Flow's `:transition` handles cleanup:

```clojure
([state ::flow/stop]
 ;; Close all HTTP-kit channels gracefully
 (doseq [[_ client] (:clients state)]
   (hk/close (:seon.sse/http-channel client)))
 state)
```

---

## 5. Implementation Plan

### Phase 1: Foundation (Low Risk)

1. Create `src/seon/web/sse/flow.clj` with schemas and step functions
2. Add Flow to deps.edn: `org.clojure/core.async {:mvn/version "1.7.713"}`
3. Write tests for step functions in isolation
4. **No changes to existing SSE code yet**

### Phase 2: Integration (Medium Risk)

1. Initialize Flow in Integrant system (alongside existing SSE)
2. Hook clj-reload to emit Flow events
3. Add client registration to `render-handler` (optional path)
4. Verify with Observatory UI

### Phase 3: Migration (Higher Risk)

1. Replace `refresh-all!` calls with Flow `emit-change!`
2. Add page-based filtering to broadcaster
3. Remove polling fallback where Flow-driven updates work
4. Performance testing

### Phase 4: Observability (Optional)

1. Add `/api/sse/status` endpoint exposing `flow/ping`
2. Add SSE metrics to Observatory
3. Consider flow-monitor integration for debugging

---

## 6. Constraints and Limitations

### What Flow Doesn't Solve

| Limitation | Mitigation |
|------------|------------|
| Can't serialize client HTTP channels | Clients reconnect on server restart |
| Flow state is in-memory | Acceptable for SSE (ephemeral connections) |
| Learning curve for step functions | Start simple, add complexity as needed |
| Alpha API status | Core patterns are stable; be ready to adapt |

### What We Keep

| Component | Reason |
|-----------|--------|
| `after-ns-reload` hooks | Still needed for handler recreation |
| http-kit `as-channel` | Flow routes events, http-kit handles HTTP |
| Polling fallback | Graceful degradation for clients that miss events |
| Hash-based change detection | Prevents unnecessary DOM updates |

---

## 7. Success Criteria

| Metric | Target | How to Measure |
|--------|--------|----------------|
| Update latency | <100ms | Instrument Flow pipeline |
| Client lifecycle | Clean | No orphaned connections in `flow/ping` |
| Observability | Full | `connected-clients` returns accurate data |
| No dropped updates | Zero | Error-chan stays empty under load |
| Debounce effectiveness | 1 update per burst | Count broadcasts vs changes |

---

## 8. Conclusion

The SSE live reload issue is **already solved** by the `after-ns-reload` hooks. What remains is **architectural improvement**: better event routing, observability, and debouncing.

`core.async.flow` is the right tool for this because:

1. It's designed for internal event routing (not external I/O)
2. Its introspection enables the observability we need
3. Step functions are hot-reloadable (matching our pattern)
4. Declarative topology makes the data flow explicit

**Recommended action:** Implement Phase 1 (foundation) and verify in isolation before touching production SSE code. The investment pays off in debuggability and cleaner architecture.

---

## References

- `src/seon/web/sse.clj` - Current SSE implementation
- `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj` - Flow source
- `docs/prds/namespace-ui/sse-live-reload-fix.md` - Prior fix
- `docs/architecture/flow-foundation.md` - Architecture analysis
