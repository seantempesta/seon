---
type: reference
status: active
tags: [reference, web]
---
# Datastar Extended Patterns

**Purpose:** Advanced patterns for complex UI work
**Audience:** Claude Code agents building beyond basic dashboard
**Last Updated:** 2025-12-02

---

## Table of Contents

1. [Multi-Page Architecture](#multi-page-architecture)
2. [Authentication & Sessions](#authentication--sessions)
3. [Complex UI Components](#complex-ui-components)
4. [Performance Patterns](#performance-patterns)
5. [Multiplayer/Collaboration](#multiplayercollaboration)
6. [Testing Strategies](#testing-strategies)

---

## Multi-Page Architecture

### Question: SPA or MPA?

**Answer:** Both work, choose based on use case.

#### Option 1: Single-Page App (SPA) - One SSE Stream

**Pattern:** One SSE connection serves all routes, client-side routing.

```clojure
(defn render-app [request]
  (let [path (:uri request)
        state @app-state]
    (h/html
      [:main#morph
       [:nav
        [:a {:href "/"} "Dashboard"]
        [:a {:href "/history"} "History"]
        [:a {:href "/settings"} "Settings"]]

       (case path
         "/" (render-dashboard state)
         "/history" (render-history state)
         "/settings" (render-settings state)
         (render-404))])))

```

**Navigation:** Use Datastar signals + @post

```html
<a data-on:click="@post('/history')" href="/history">History</a>

```

**Pros:**

- Single SSE connection (efficient)
- Instant navigation (no page reload)
- Shared state across "pages"

**Cons:**

- All routes in one render function
- URL changes need custom handling
- Back button requires history API

#### Option 2: Multi-Page App (MPA) - SSE Per Page

**Pattern:** Each page opens its own SSE connection.

```clojure
;; Dashboard page
(def dashboard-sse
  (sse/render-handler
    (fn [_] (html/dashboard-content @dashboard-state))))

;; History page
(def history-sse
  (sse/render-handler
    (fn [_] (html/history-content @history-state))))

;; Routes
{:routes
 [["/" {:get handlers/dashboard-shim
        :post handlers/dashboard-sse}]
  ["/history" {:get handlers/history-shim
               :post handlers/history-sse}]]}

```

**Navigation:** Standard links, browser handles it

```html
<a href="/history">History</a>

```

**Pros:**

- Simple mental model (traditional pages)
- Browser back button works
- Each page isolated

**Cons:**

- New SSE connection per navigation
- Page reload flash (mitigated by shim pattern)

#### Our Choice: MPA

Currently using MPA pattern (one dashboard page). If we add more pages:

```clojure
;; Example: Add settings page
(defn settings-shim [_request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (html/shim-page)})  ; Same shim, different SSE endpoint

(def settings-sse
  (sse/render-handler
    (fn [_request]
      (html/settings-content @settings-state))))

```

### Tabs Within a Page

For tabs on same page, use Datastar signals (client-side only):

```clojure
(defn dashboard-with-tabs [state]
  (h/html
    [:main#morph
     {:data-signals:tab__ifmissing "stats"}  ; Default tab

     ;; Tab buttons (no server round-trip)
     [:nav
      [:button {:data-on:click "$tab = 'stats'"} "Stats"]
      [:button {:data-on:click "$tab = 'history'"} "History"]
      [:button {:data-on:click "$tab = 'logs'"} "Logs"]]

     ;; Tab content (conditionally rendered)
     [:div {:data-show "$tab === 'stats'"}
      (render-stats state)]
     [:div {:data-show "$tab === 'history'"}
      (render-history state)]
     [:div {:data-show "$tab === 'logs'"}
      (render-logs state)]]))

```

**Note:** `__ifmissing` means "only set if signal doesn't exist" - survives re-renders.

---

## Authentication & Sessions

### Session Cookies

**Pattern:** Store session ID in secure cookie, check on every request.

```clojure
(ns your-namespace
  (:require [ring.middleware.session :as session]
            [ring.middleware.session.cookie :as cookie]))

;; Middleware setup
(def app
  (-> handler
      (session/wrap-session
        {:store (cookie/cookie-store {:key "16-byte-secret-key"})
         :cookie-name "session"
         :cookie-attrs {:http-only true
                        :secure true  ; HTTPS only
                        :same-site :strict}})))

```

### Login Flow

```clojure
(defn login-handler [request]
  (let [body (parse-body (:body request))
        username (:username body)
        password (:password body)]
    (if (valid-credentials? username password)
      ;; Success - set session
      {:status 200
       :session {:user-id (find-user-id username)}
       :headers {"Content-Type" "application/json"}
       :body "{\"ok\": true}"}

      ;; Failure
      {:status 401
       :headers {"Content-Type" "application/json"}
       :body "{\"error\": \"Invalid credentials\"}"})))

```

### Protected Routes

```clojure
(defn require-auth [handler]
  (fn [request]
    (if-let [user-id (get-in request [:session :user-id])]
      ;; Authenticated - add user to request
      (handler (assoc request :user-id user-id))

      ;; Not authenticated - redirect
      {:status 302
       :headers {"Location" "/login"}})))

;; Apply to routes
{:routes
 [["/" {:get (require-auth handlers/dashboard)
        :post (require-auth handlers/dashboard-sse)}]
  ["/login" {:get handlers/login-page
             :post handlers/login-handler}]]}

```

### Per-User State

```clojure
;; Global state indexed by user-id
(defonce user-dashboards (atom {}))

(defn dashboard-sse [request]
  (let [user-id (:user-id request)]
    (sse/render-handler
      (fn [_req]
        ;; Render user-specific state
        (let [user-state (get @user-dashboards user-id)]
          (html/dashboard-content user-state))))))

```

### SSE with Authentication

SSE connections inherit session cookies automatically:

```javascript
// Browser sends cookies with POST automatically
@post('/')  // Includes session cookie

```

If session expires during SSE connection:

```clojure
(defn render-handler-with-auth [render-fn]
  (sse/render-handler
    (fn [request]
      (if-let [user-id (get-in request [:session :user-id])]
        ;; Authenticated
        (render-fn request)

        ;; Session expired - return login redirect HTML
        (h/html
          [:main#morph
           [:script "window.location.href = '/login'"]])))

```

---

## Complex UI Components

### Charts & Graphs

**Option 1: Server-side SVG**

```clojure
(defn render-chart [data]
  (h/html
    [:svg {:width 400 :height 200 :viewBox "0 0 400 200"}
     (for [[idx value] (map-indexed vector data)]
       [:rect {:x (* idx 40)
               :y (- 200 value)
               :width 30
               :height value
               :fill "#3b82f6"}])]))

```

**Pros:** No JavaScript, works everywhere
**Cons:** Limited interactivity, harder for complex charts

**Option 2: Chart.js via data-init**

```clojure
(defn render-chart-js [data]
  (let [chart-id (str "chart-" (random-uuid))
        data-json (json/write-value-as-string data)]
    (h/html
      [:div {:id chart-id :data-ignore-morph true}  ; Preserve during updates
       [:canvas {:id (str chart-id "-canvas")}]
       [:script {:data-init (str "
         const ctx = document.getElementById('" chart-id "-canvas');
         new Chart(ctx, {
           type: 'line',
           data: " data-json "
         });
       ")}]])))

```

**Note:** `data-ignore-morph` prevents Idiomorph from touching canvas during updates.

### Live Streaming Logs

**Pattern:** Append-only updates with scroll-to-bottom.

```clojure
;; State: Store last N log entries
(defonce log-entries (atom []))

(defn add-log-entry! [entry]
  (swap! log-entries conj entry)
  ;; Keep last 1000 entries
  (when (> (count @log-entries) 1000)
    (swap! log-entries subvec 1)))

;; Render
(defn render-logs [entries]
  (h/html
    [:div#log-container.log-container
     (for [[idx entry] (map-indexed vector (take-last 50 entries))]
       [:div.log-entry {:id (str "log-" idx)}
        [:span.timestamp (:timestamp entry)]
        " "
        [:span.message (:message entry)]])

     ;; Auto-scroll to bottom
     [:script "
       const el = document.getElementById('log-container');
       el.scrollTop = el.scrollHeight;
     "]]))

```

**Alternative:** Append mode with SSE

```clojure
;; Send just the new entry, append to existing
(defn append-log-entry [entry]
  (sse/send-sse-event!
    {:selector "#log-container"
     :patch-mode :append
     :elements (h/html
                 [:div.log-entry
                  [:span.timestamp (:timestamp entry)]
                  " "
                  [:span.message (:message entry)]])}))

```

### Drag & Drop

**Pattern:** Use Datastar signals to track drag state.

```clojure
(defn render-kanban [board]
  (h/html
    [:main#morph
     {:data-signals:draggedItem__ifmissing "null"}

     (for [[column-id items] board]
       [:div.column
        {:data-on:dragover.prevent ""  ; Allow drop
         :data-on:drop (str "@post('/move-item', {
           item: $draggedItem,
           column: '" column-id "'
         })")}

        (for [item items]
          [:div.item
           {:draggable true
            :data-on:dragstart (str "$draggedItem = '" (:id item) "'")}
           (:title item)])])]))

```

### Modals & Dialogs

**Pattern:** Use Datastar signals for open/close state.

```clojure
(defn render-with-modal [content]
  (h/html
    [:main#morph
     {:data-signals:modalOpen__ifmissing "false"}

     ;; Trigger button
     [:button {:data-on:click "$modalOpen = true"}
      "Open Modal"]

     ;; Modal overlay
     [:div.modal-overlay
      {:data-show "$modalOpen"
       :data-on:click.outside "$modalOpen = false"  ; Click outside to close
       :style "display: none"}  ; Initial state

      [:div.modal-content
       [:h2 "Modal Title"]
       [:p "Modal body content"]
       [:button {:data-on:click "$modalOpen = false"} "Close"]]]

     ;; Main content
     content]))

```

**CSS for modal:**

```css
.modal-overlay {
  position: fixed;
  top: 0; left: 0;
  width: 100%; height: 100%;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-content {
  background: white;
  padding: 2rem;
  border-radius: 8px;
  max-width: 500px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.2);
}

```

### Multi-Step Forms

**Pattern:** Use signals to track step, server validates on submit.

```clojure
(defn render-wizard [data]
  (h/html
    [:main#morph
     {:data-signals:step__ifmissing "1"
      :data-signals:form.name__ifmissing "''"
      :data-signals:form.email__ifmissing "''"
      :data-signals:form.date__ifmissing "''"}

     ;; Step indicator
     [:div.wizard-steps
      [:div.step {:data-class:active "$step === '1'"} "Personal Info"]
      [:div.step {:data-class:active "$step === '2'"} "Contact"]
      [:div.step {:data-class:active "$step === '3'"} "Review"]]

     ;; Step 1
     [:div {:data-show "$step === '1'"}
      [:input {:data-bind "form.name" :placeholder "Name"}]
      [:button {:data-on:click "$step = '2'"} "Next"]]

     ;; Step 2
     [:div {:data-show "$step === '2'"}
      [:input {:data-bind "form.email" :placeholder "Email"}]
      [:button {:data-on:click "$step = '1'"} "Back"]
      [:button {:data-on:click "$step = '3'"} "Next"]]

     ;; Step 3: Review
     [:div {:data-show "$step === '3'"}
      [:p "Name: " [:span {:data-text "$form.name"}]]
      [:p "Email: " [:span {:data-text "$form.email"}]]
      [:button {:data-on:click "$step = '2'"} "Back"]
      [:button {:data-on:click "@post('/submit-form')"} "Submit"]]]))

```

---

## Performance Patterns

### Pattern 1: Billion Checkboxes (Sparse State)

**From Hyperlith example:** Handle massive datasets with sparse representation.

```clojure
;; State: Only store checked items (not all items)
(defonce checked-items (atom #{}))

;; Render only visible window
(defn render-checkbox-grid [scroll-pos]
  (let [visible-range (range scroll-pos (+ scroll-pos 100))]
    (h/html
      [:main#morph
       {:data-signals:scroll__ifmissing "0"}

       ;; Scroll container
       [:div.grid
        {:data-on:scroll "$scroll = Math.floor(evt.target.scrollTop / 20)"}

        (for [i visible-range]
          [:div.checkbox-row
           [:input {:type "checkbox"
                    :checked (contains? @checked-items i)
                    :data-on:change (str "@post('/toggle/" i "')")}]
           [:label (str "Item " i)]])]])))

;; Only send changed state
(defn toggle-checkbox [id]
  (swap! checked-items
    (fn [s] (if (contains? s id)
              (disj s id)
              (conj s id)))))

```

**Key insight:** Don't render all billion items, only visible window.

### Pattern 2: Virtual Scrolling

**Pattern:** Only render visible items, update window on scroll.

```clojure
(defonce all-items (atom (vec (range 100000))))

(defn render-virtual-list [scroll-pos item-height visible-count]
  (let [start-idx (quot scroll-pos item-height)
        end-idx (+ start-idx visible-count)
        visible-items (subvec @all-items start-idx (min end-idx (count @all-items)))]
    (h/html
      [:main#morph
       {:data-signals:scrollTop__ifmissing "0"}

       [:div.list-container
        {:data-on:scroll "$scrollTop = evt.target.scrollTop"
         :style (str "height: " (* (count @all-items) item-height) "px")}

        [:div {:style (str "transform: translateY(" (* start-idx item-height) "px)")}
         (for [item visible-items]
           [:div.item {:style (str "height: " item-height "px")}
            (str "Item " item)])]]])))

```

### Pattern 3: Batched Updates (From Game of Life)

**Pattern:** Batch rapid updates, render at fixed rate.

```clojure
(defn start-game-loop! [update-fn]
  (let [running? (atom true)
        pending-updates (atom [])]

    ;; Collect updates
    (add-watch game-state :collect-updates
      (fn [_ _ _ new-state]
        (swap! pending-updates conj new-state)))

    ;; Render batched updates every 200ms
    (.start (Thread/ofVirtual)
      (fn []
        (while @running?
          (Thread/sleep 200)
          (when (seq @pending-updates)
            ;; Take last state (most recent)
            (let [latest-state (last @pending-updates)]
              (reset! pending-updates [])
              (sse/refresh-all!))))))

    ;; Cleanup
    (fn stop! [] (reset! running? false))))

```

**Key insight:** Render at fixed rate (5 FPS), not on every state change.

### Pattern 4: Work Sharing (Single Render, Broadcast to All)

**Pattern:** Render once, send to all connections.

```clojure
;; From Hyperlith source
(defn render-handler-with-caching [render-fn]
  (let [cached-view (atom nil)
        cached-hash (atom nil)]

    ;; Update cache when state changes
    (add-watch app-state :update-cache
      (fn [_ _ _ new-state]
        (let [new-view (render-fn new-state)
              new-hash (hash new-view)]
          (when (not= @cached-hash new-hash)
            (reset! cached-view new-view)
            (reset! cached-hash new-hash)))))

    ;; All connections read from cache
    (sse/render-handler
      (fn [_request]
        @cached-view))))

```

**Benefit:** 100 concurrent users = 1 render, not 100 renders.

---

## Multiplayer/Collaboration

### Pattern 1: Presence Tracking

**From Hyperlith presence_cursors example:**

```clojure
(defonce user-positions (atom {}))

;; Track user position
(defaction update-position [{:keys [sid] {:keys [x y]} :body}]
  (when (and x y)
    (swap! user-positions assoc sid [x y])))

;; Remove user on disconnect
(defn on-close-handler [{:keys [sid]}]
  (swap! user-positions dissoc sid))

;; Render all cursors
(defn render-cursors [positions]
  (h/html
    [:div.cursor-area
     (for [[sid [x y]] positions]
       [:div.cursor
        {:id (str "cursor-" sid)
         :style (str "left: " x "px; top: " y "px")}
        "🚀"])]))

;; Update on mousemove (debounced)
[:div {:data-on:mousemove__debounce.100ms
       "@post('/update-position', {x: evt.clientX, y: evt.clientY})"}]

```

### Pattern 2: Collaborative Editing

**Pattern:** Last-write-wins with server as source of truth.

```clojure
(defonce document-state (atom {:text "" :version 0}))

(defaction edit-document [{:keys [sid] {:keys [text client-version]} :body}]
  (swap! document-state
    (fn [state]
      (if (>= (:version state) client-version)
        ;; Stale edit - server wins
        state
        ;; Accept edit
        {:text text
         :version (inc (:version state))
         :last-editor sid}))))

;; All clients see latest version via SSE
(defn render-editor [state]
  (h/html
    [:main#morph
     [:textarea
      {:value (:text state)
       :data-on:input__debounce.500ms
       "@post('/edit', {text: evt.target.value, version: $version})"}]
     [:div.meta "Version: " (:version state)]]))

```

**Note:** For production, use CRDT library (Automerge, Yjs) for conflict-free merging.

### Pattern 3: Real-Time Notifications

**Pattern:** Broadcast events to all users.

```clojure
(defonce notifications (atom []))

(defn notify-all! [message]
  (swap! notifications conj
    {:id (random-uuid)
     :message message
     :timestamp (java.time.Instant/now)})
  ;; Triggers SSE refresh for all connected users
  )

(defn render-notifications [notifs]
  (h/html
    [:div#notifications
     (for [notif (take-last 5 notifs)]
       [:div.notification {:id (:id notif)}
        [:span.time (format-time (:timestamp notif))]
        " "
        [:span.message (:message notif)]])]))

```

---

## Testing Strategies

### Unit Tests: Pure Functions

```clojure
(ns ml-options.web.html-test
  (:require [clojure.test :refer [deftest is]]
            [ml-options.web.html :as html]))

(deftest format-number-test
  (is (= "1,234" (html/format-number 1234)))
  (is (= "1,000,000" (html/format-number 1000000)))
  (is (nil? (html/format-number nil))))

(deftest format-percentage-test
  (is (= "50.0%" (html/format-percentage 50)))
  (is (= "33.3%" (html/format-percentage 33.333))))

```

### Integration Tests: SSE Flow

```clojure
(ns ml-options.web.sse-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.core.async :as a]
            [ml-options.web.sse :as sse]))

(deftest refresh-all-test
  (let [refresh-mult (sse/init-sse!)
        <client-ch (a/tap refresh-mult (a/chan))]

    ;; Trigger refresh
    (sse/refresh-all!)

    ;; Client should receive event
    (is (= :refresh-event (a/<!! <client-ch)))

    ;; Cleanup
    (a/untap refresh-mult <client-ch)
    (a/close! <client-ch)))

```

### End-to-End: Browser Testing

Use Playwright/Puppeteer for browser automation:

```javascript
// tests/e2e/dashboard.test.js
test('dashboard loads and shows stats', async ({ page }) => {
  await page.goto('http://localhost:8080/');

  // Wait for SSE connection
  await page.waitForSelector('main#morph h1');

  // Verify content
  const title = await page.textContent('h1');
  expect(title).toBe('ML Options Import Dashboard');

  // Check stats loaded
  const statValue = await page.textContent('.stat-value');
  expect(statValue).toBeTruthy();
});

test('start import button works', async ({ page }) => {
  await page.goto('http://localhost:8080/');

  // Fill form
  await page.fill('#symbols', 'SPY');
  await page.fill('#start-date', '2024-01-01');
  await page.fill('#end-date', '2024-01-31');

  // Submit
  await page.click('button[type="submit"]');

  // Wait for job status to appear
  await page.waitForSelector('.badge-running');

  const status = await page.textContent('.badge-running');
  expect(status).toBe('Running');
});

```

### Performance Testing: SSE Load

```clojure
(ns ml-options.web.load-test
  (:require [clojure.core.async :as a]
            [ml-options.web.sse :as sse]))

(defn simulate-clients [n]
  (let [refresh-mult (sse/init-sse!)
        clients (repeatedly n #(a/tap refresh-mult (a/chan (a/dropping-buffer 1))))]

    ;; Trigger 100 refreshes
    (dotimes [_ 100]
      (sse/refresh-all!)
      (Thread/sleep 10))

    ;; Verify all clients received events
    (doseq [client clients]
      (is (not (nil? (a/poll! client)))))

    ;; Cleanup
    (doseq [client clients]
      (a/untap refresh-mult client)
      (a/close! client))))

(deftest load-test
  ;; Test with 1000 concurrent clients
  (time (simulate-clients 1000)))

```

---

## Summary

This document covers patterns beyond basic dashboard:

✅ **Multi-page:** SPA vs MPA trade-offs, tabs pattern
✅ **Auth:** Session cookies, protected routes, per-user state
✅ **Complex UI:** Charts, live logs, drag-drop, modals, wizards
✅ **Performance:** Virtual scrolling, batching, work sharing
✅ **Multiplayer:** Presence, collaborative editing, notifications
✅ **Testing:** Unit, integration, e2e, load testing

**Key principle:** Start simple (MPA, server-side state), add complexity only when needed. The patterns here are for when basic dashboard isn't enough.

**Next steps when building new features:**

1. Check `DATASTAR_QUICK_REF.md` for basic patterns
2. Check this doc for advanced patterns
3. Study Hyperlith examples in `/reference-code/hyperlith/examples/`
4. Ask questions if unclear - don't guess!
