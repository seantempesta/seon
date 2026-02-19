# PRD: Live Atom Updates
## Status: SUPERSEDED by render-pipeline — live updates handled by unified render pipeline

**Status:** Pending
**Priority:** High (Phase 5 of namespace-ui)
**Estimated Scope:** 2-3 days

---

## Goal

REPL atom change → browser update in <100ms

When a user modifies an atom in the REPL, any browser view watching that atom should update within 100ms latency. This enables true "live coding" where you see the effects of your code changes in real-time.

---

## Current State

### SSE Infrastructure (Existing)

The broadcast SSE system is fully operational:

| Component | Location | Purpose |
|-----------|----------|---------|
| `refresh-ch_` | `src/seon/web/sse.clj:17` | Atom holding core.async channel for broadcast |
| `init-sse!` | `src/seon/web/sse.clj:192-217` | Creates mult for fan-out to all connections |
| `refresh-all!` | `src/seon/web/sse.clj:183-190` | Triggers re-render for all connected clients |
| `render-handler` | `src/seon/web/sse.clj:117-181` | Hash-based change detection, streaming brotli |
| `wrap-refresh-mult` | `src/seon/web/sse.clj:229-235` | Middleware adds mult to request map |

**Current flow:**
```
State change → (swap! atom ...)
            → add-watch triggers
            → (sse/refresh-all!)
            → All SSE handlers re-render
            → Hash comparison
            → Send if changed
```

### Watch Pattern (Existing)

Two existing implementations show the pattern:

**1. Job state auto-refresh** (`src/seon/web/jobs.clj:25-29`):
```clojure
(defonce _state-watch
  (add-watch job-state :sse-auto-refresh
             (fn [_key _ref old-state new-state]
               (when (not= old-state new-state)
                 (sse/refresh-all!)))))
```

**2. Primer session auto-refresh** (`src/seon/primer/ctx.clj:182`):
```clojure
(add-watch sessions :sse-auto-refresh ...)
```

### Persisted Ctx (Existing)

The agent context system already has watchers for persistence:

| Component | Location | Purpose |
|-----------|----------|---------|
| `make-persisted-ctx` | `src/seon/agent/ctx.clj:269-389` | Creates atom with validator + persistence |
| Debounced persistence | `src/seon/agent/ctx.clj:347-363` | 1000ms debounce to XTDB |
| Schema validation | `src/seon/agent/ctx.clj:164-214` | Validates all keys are namespaced + registered |

**Key insight:** The ctx atom already has a watcher for XTDB persistence (`::persist`). We need a second watcher for SSE updates.

---

## Problem Statement

Currently, to make an atom update the browser:

1. Manually add `add-watch` with `:sse-auto-refresh` key
2. Call `sse/refresh-all!` from the watch function
3. Hope you remember to do this for every relevant atom

**Issues:**
- No central registry of watched atoms
- No debounce for rapid updates (SSE floods if many changes)
- No way to watch atoms dynamically from UI
- No cleanup when browser disconnects
- No targeting (all clients refresh, not just relevant ones)

---

## Design

### Watch Registry

A central registry tracks which atoms are being watched for SSE updates:

```clojure
(ns seon.ui.live
  (:require [seon.web.sse :as sse]))

(defonce ^:private watch-registry
  (atom {}))  ; {atom-ref -> {:watch-key keyword, :debounce-atom atom}}

(def ^:private debounce-ms 50)  ; Sub-100ms target with margin
```

**Why 50ms debounce?**
- Human perception threshold: ~100ms
- Network + render overhead: ~30-50ms
- Debounce window: 50ms gives headroom

### Core Functions

#### `watch-atom!` - Register an Atom for SSE Updates

```clojure
(defn watch-atom!
  "Watch an atom and trigger SSE refresh on changes.

   The atom will trigger `sse/refresh-all!` when its value changes,
   debounced to prevent flooding during rapid updates.

   Request keys:
     ::atom - Required. The atom to watch

   Response keys:
     ::watch-key - The watch key added (for manual removal)

   Example:
     (watch-atom! {::atom *ctx*})"
  [{::keys [atom]}]
  (let [watch-key (keyword "seon.ui.live" (str (System/identityHashCode atom)))
        debounce-future (clojure.core/atom nil)]

    (add-watch atom watch-key
      (fn [_ _ old-val new-val]
        (when (not= old-val new-val)
          ;; Cancel pending debounce
          (when-let [f @debounce-future]
            (future-cancel f))
          ;; Schedule new refresh
          (reset! debounce-future
            (future
              (Thread/sleep debounce-ms)
              (sse/refresh-all!))))))

    ;; Register for cleanup
    (swap! watch-registry assoc atom
           {:watch-key watch-key
            :debounce-atom debounce-future})

    {::watch-key watch-key}))
```

#### `unwatch-atom!` - Remove Watch

```clojure
(defn unwatch-atom!
  "Stop watching an atom for SSE updates.

   Request keys:
     ::atom - Required. The atom to unwatch"
  [{::keys [atom]}]
  (when-let [{:keys [watch-key debounce-atom]} (get @watch-registry atom)]
    ;; Cancel pending refresh
    (when-let [f @debounce-atom]
      (future-cancel f))
    ;; Remove watch
    (remove-watch atom watch-key)
    ;; Cleanup registry
    (swap! watch-registry dissoc atom)
    {::removed true}))
```

#### `watching?` - Check Status

```clojure
(defn watching?
  "Check if an atom is currently watched.

   Request keys:
     ::atom - Required. The atom to check"
  [{::keys [atom]}]
  (contains? @watch-registry atom))
```

#### `watched-atoms` - List All

```clojure
(defn watched-atoms
  "Return list of all atoms currently watched for SSE updates."
  []
  (keys @watch-registry))
```

### Integration with Persisted Ctx

Modify `make-persisted-ctx` to optionally auto-register for SSE:

```clojure
;; In make-request schema, add:
[::watch-sse {:optional true} :boolean]

;; In make-persisted-ctx:
(when watch-sse
  (live/watch-atom! {::live/atom ctx-atom}))
```

### Namespace View Integration

When rendering a namespace view that contains atoms, automatically watch them:

```clojure
;; In seon.ns.routes namespace handler
(defn render-namespace-view [ns-sym]
  (let [atoms (introspect-atoms ns-sym)]
    ;; Watch all atoms when rendering
    (doseq [a atoms]
      (live/watch-atom! {::live/atom a}))
    ;; Render the view
    (render-atoms atoms)))
```

**Cleanup consideration:** When do we unwatch? Options:
1. Never (leak but atoms are finite)
2. On SSE disconnect (requires connection tracking)
3. On session end (for agent ctx atoms)

Recommendation: Start with option 1 (never), add cleanup if memory becomes issue.

---

## Implementation Phases

### Phase 0: Create Namespace + Core Functions

**Files to create:**
- `src/seon/ui/live.clj`

**Tasks:**
1. Create namespace with schema registrations
2. Implement `watch-atom!` with debounce
3. Implement `unwatch-atom!`
4. Implement `watching?` and `watched-atoms`
5. Add tests

**Test criteria:**
```clojure
;; Create test atom
(def *test* (atom {:value 0}))

;; Watch it
(live/watch-atom! {::live/atom *test*})

;; Verify registered
(live/watching? {::live/atom *test*}) ; => true

;; Change triggers refresh
(swap! *test* assoc :value 1)
;; => sse/refresh-all! called after 50ms debounce

;; Unwatch
(live/unwatch-atom! {::live/atom *test*})
(live/watching? {::live/atom *test*}) ; => false
```

### Phase 1: Visual Indicator

**Files to modify:**
- `src/seon/web/components.clj` (or create)

**Tasks:**
1. Add "● live" indicator component
2. Pulsing animation on updates
3. Tooltip showing watch status

```clojure
(defn live-indicator
  "Visual indicator for live-updating content."
  [{:keys [active?]}]
  [:span {:class (str "inline-flex items-center gap-1 text-xs "
                      (if active? "text-green-500" "text-text-400"))}
   [:span {:class (when active? "animate-pulse")} "●"]
   "live"])
```

**Test criteria:**
- Atom view shows "● live" indicator
- Indicator pulses green when data updates
- Indicator grayed when not watching

### Phase 2: Namespace View Integration

**Files to modify:**
- `src/seon/ns/routes.clj` - Add watch on render
- `src/seon/ns/view.clj` - Atom rendering with live indicator

**Tasks:**
1. Detect atoms in namespace view
2. Auto-watch when rendering
3. Show live indicator per atom
4. Render atom value with expand/collapse

**Test criteria:**
```
1. Navigate to /ns/seon.some-namespace
2. See atoms listed with "● live" indicator
3. In REPL: (swap! *atom* assoc :new-key "value")
4. Browser updates within 100ms
```

### Phase 3: Targeted Updates (Optional Enhancement)

Instead of refreshing ALL clients, target specific watchers.

**Files to modify:**
- `src/seon/web/sse.clj` - Add connection registry
- `src/seon/ui/live.clj` - Add selector-based targeting

**Pattern from Datastar SDK:**
```clojure
;; Store per-atom connections
(defonce atom-connections (atom {}))  ; {atom-ref -> #{sse-gen ...}}

;; Push to specific element
(defn push-atom-update! [atom-ref new-val]
  (let [selector (str "#atom-" (System/identityHashCode atom-ref))
        html (render-atom-value new-val)]
    (doseq [sse (get @atom-connections atom-ref)]
      (d*/patch-elements! sse html {d*/selector selector}))))
```

**Test criteria:**
- Two browser tabs, one watching atom A, one watching atom B
- Update atom A → only first tab refreshes
- Update atom B → only second tab refreshes

---

## Debounce Strategy

### Why Debounce?

Rapid atom updates (e.g., looping `swap!`) would flood SSE without debounce:

```clojure
;; Without debounce: 1000 SSE events in ~1ms
(dotimes [i 1000]
  (swap! *ctx* assoc :counter i))

;; With 50ms debounce: 1 SSE event after final update
```

### Debounce Implementation

Use `future` + `future-cancel` for simple debounce:

```clojure
(let [pending-refresh (atom nil)]
  (fn debounced-refresh []
    (when-let [f @pending-refresh]
      (future-cancel f))
    (reset! pending-refresh
      (future
        (Thread/sleep 50)
        (sse/refresh-all!)))))
```

### Alternative: core.async debounce

For more sophisticated use cases:

```clojure
(require '[clojure.core.async :as a])

(defn debounce-ch [in-ch ms]
  (let [out-ch (a/chan)]
    (a/go-loop [timeout-ch nil]
      (let [[val port] (a/alts! (if timeout-ch
                                  [in-ch timeout-ch]
                                  [in-ch]))]
        (cond
          (nil? val) (a/close! out-ch)
          (= port in-ch) (recur (a/timeout ms))
          (= port timeout-ch) (do (a/>! out-ch :emit)
                                   (recur nil)))))
    out-ch))
```

**Recommendation:** Start with `future`-based approach. Switch to core.async if needed.

---

## Code References

| File | Line | Purpose |
|------|------|---------|
| `src/seon/web/sse.clj` | 17 | `refresh-ch_` defonce |
| `src/seon/web/sse.clj` | 117-181 | `render-handler` with hash detection |
| `src/seon/web/sse.clj` | 183-190 | `refresh-all!` broadcast |
| `src/seon/web/jobs.clj` | 25-29 | `add-watch` pattern |
| `src/seon/agent/ctx.clj` | 347-363 | Existing watcher for persistence |
| `src/seon/agent/ctx.clj` | 269-389 | `make-persisted-ctx` full implementation |

---

## Test Criteria

### Unit Tests

```clojure
(ns seon.ui.live-test
  (:require [clojure.test :refer :all]
            [seon.ui.live :as live]))

(deftest watch-atom-test
  (let [*test* (atom {:value 0})
        refresh-called (atom 0)]
    ;; Mock sse/refresh-all!
    (with-redefs [seon.web.sse/refresh-all! #(swap! refresh-called inc)]
      ;; Watch
      (live/watch-atom! {::live/atom *test*})
      (is (live/watching? {::live/atom *test*}))

      ;; Trigger change
      (swap! *test* assoc :value 1)
      (Thread/sleep 60)  ; Wait for debounce
      (is (= 1 @refresh-called))

      ;; Multiple rapid changes = one refresh
      (dotimes [i 10]
        (swap! *test* assoc :value i))
      (Thread/sleep 60)
      (is (= 2 @refresh-called))

      ;; Unwatch
      (live/unwatch-atom! {::live/atom *test*})
      (is (not (live/watching? {::live/atom *test*})))

      ;; No more refreshes
      (swap! *test* assoc :value 999)
      (Thread/sleep 60)
      (is (= 2 @refresh-called)))))
```

### Integration Test (Manual)

```
1. Start server: ./bin/run
2. Open http://localhost:8080/ns/seon.web.jobs
3. Observe job-state atom displayed with "● live" indicator
4. In REPL: (swap! seon.web.jobs/job-state assoc :test 123)
5. Browser should update within 100ms
6. Verify update appears without page refresh
```

### Latency Benchmark

```clojure
(defn measure-latency []
  (let [start (System/nanoTime)
        done (promise)]
    ;; Set up listener for SSE event
    (add-watch latency-atom ::measure
      (fn [_ _ _ _]
        (deliver done (- (System/nanoTime) start))))
    ;; Trigger change
    (swap! latency-atom inc)
    ;; Wait for result
    (let [latency-ns @done]
      (remove-watch latency-atom ::measure)
      (/ latency-ns 1e6))))  ; Return milliseconds

;; Target: < 100ms
```

---

## Dependencies

- `seon.web.sse` - Existing SSE infrastructure
- `seon.schema` - For request/response validation
- No external dependencies needed

---

## Open Questions

1. **Cleanup strategy** - When to unwatch atoms? On disconnect? Session end? Never?
2. **Targeted updates** - Worth implementing for Phase 3? Or overkill for single-user system?
3. **Memory pressure** - Will registry grow unboundedly? Should we track + prune stale entries?

---

## Success Criteria

| Metric | Target |
|--------|--------|
| Latency | < 100ms from swap! to browser update |
| Debounce | Rapid updates coalesced (no flooding) |
| Visibility | "● live" indicator shows watching status |
| Cleanup | No watch leaks after session end |

---

## References

- Phase 5 in `docs/prds/namespace-ui/prd.md:1688-1748`
- Live widgets research: `docs/prds/namespace-ui/research/live-widgets-research.md`
- SSE live reload: `docs/prds/namespace-ui/sse-live-reload-investigation.md`
- Datastar quick reference: `docs/reference/datastar-quick-reference.md`
