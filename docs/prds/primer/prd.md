# PRD: Primer Domain

**Status:** In Progress
**Created:** 2024-12-24
**Goal:** Interactive educational experience for children, inspired by Diamond Age

---

## Vision

A server-controlled interactive story system where:
- **Ctx atom** holds all state (scenes, actions, child profile)
- **Agent** pre-computes behaviors; runtime executes them instantly
- **Specs** constrain what agent can write to ctx
- **UI** is derived from ctx data via templates
- **XTDB** provides temporal storage for checkpointing/replay

See `research/` for architectural exploration.

---

## Success Criteria

1. Primer runs as a Seon domain with its own XTDB node
2. Ctx atom drives UI rendering via Datastar SSE
3. Scene with pre-computed actions renders and responds instantly
4. Agent can update ctx (validated against specs)
5. Sessions checkpoint to XTDB, can be replayed

---

## Stages

Each stage has:
- Clear goal
- REPL verification steps
- Git checkpoint on success

### Stage 1: Namespace Skeleton (Commit)

**Goal:** Create primer namespace, wire into Seon, verify it loads.

**Files to create:**
```
src/seon/primer/
├── core.clj      ; Public API, capabilities
└── schema.clj    ; Malli schemas (empty registry for now)
```

**core.clj contents:**
```clojure
(ns seon.primer.core
  "Primer domain - interactive educational experiences.")

(defn capabilities []
  {:domain :primer
   :description "Interactive educational experiences for children"
   :status :scaffold})
```

**schema.clj contents:**
```clojure
(ns seon.primer.schema
  "Malli schemas for Primer domain."
  (:require [malli.core :as m]))

(def registry
  (atom (m/default-schemas)))
```

**REPL verification:**
```clojure
(require '[seon.primer.core :as primer])
(primer/capabilities)
;; => {:domain :primer, :description "...", :status :scaffold}
```

**Checkpoint:** `git commit -m "Add primer domain skeleton"`

---

### Stage 2: Primer XTDB Node (Commit)

**Goal:** Add Integrant component for primer-specific XTDB node.

**Modify:** `resources/system.edn`
```clojure
;; Add after :seon/xtdb-node
:seon.primer/xtdb-node
{:storage #profile {:dev   {:type :local :path "data/primer"}
                    :test  :in-memory
                    :prod  {:type :local :path "data/primer"}}}
```

**Modify:** `src/seon/system.clj`
```clojure
;; Add init-key for :seon.primer/xtdb-node
;; Can reuse same pattern as :seon/xtdb-node
(defmethod ig/init-key :seon.primer/xtdb-node
  [_ {:keys [storage]}]
  (log/info "Starting Primer XTDB node..." {:storage storage})
  ;; ... same node creation logic
  )
```

**REPL verification:**
```clojure
(reset)
(status)
;; Should show :seon.primer/xtdb-node running

;; Test the node works
(require '[xtdb.api :as xt])
(def primer-node (:seon.primer/xtdb-node state/system))
(xt/status primer-node)
;; => {:latest-completed-tx ...}
```

**Checkpoint:** `git commit -m "Add primer XTDB node component"`

---

### Stage 3: Ctx Atom + Basic Schema (Commit)

**Goal:** Define ctx atom structure, add Malli schemas for validation.

**Modify:** `src/seon/primer/schema.clj`
```clojure
;; Add schemas for:
;; - Action (what user can do)
;; - Scene (current view state)
;; - Ctx (the whole context atom)

(def Action
  [:map
   [:action/id :keyword]
   [:action/label :string]
   [:action/handler :qualified-symbol]
   [:action/args {:optional true} :map]])

(def Scene
  [:map
   [:scene/id :string]
   [:scene/template :keyword]
   [:scene/params :map]
   [:scene/actions [:vector Action]]])

(def Ctx
  [:map
   [:primer/current-scene {:optional true} Scene]
   [:primer/child-id {:optional true} :string]])
```

**Create:** `src/seon/primer/state.clj`
```clojure
(ns seon.primer.state
  "Primer state management - the ctx atom."
  (:require [seon.primer.schema :as schema]
            [malli.core :as m]))

(defonce ctx (atom {}))

(defn valid-ctx? [c]
  (m/validate schema/Ctx c {:registry @schema/registry}))

(defn update-ctx! [f & args]
  (let [new-ctx (apply swap! ctx f args)]
    (when-not (valid-ctx? new-ctx)
      (throw (ex-info "Invalid ctx after update"
                      {:errors (m/explain schema/Ctx new-ctx)})))
    new-ctx))
```

**REPL verification:**
```clojure
(require '[seon.primer.state :as state])
(require '[seon.primer.schema :as schema])

;; Empty ctx is valid
(state/valid-ctx? {})
;; => true

;; Add a scene
(state/update-ctx! assoc :primer/current-scene
  {:scene/id "hello"
   :scene/template :narrative/page
   :scene/params {:text "Hello, world!"}
   :scene/actions []})

@state/ctx
;; => {:primer/current-scene {...}}

;; Invalid update should throw
(state/update-ctx! assoc :primer/current-scene "not a map")
;; => throws ExceptionInfo
```

**Checkpoint:** `git commit -m "Add primer ctx atom with schema validation"`

---

### Stage 4: Hello World Route (Commit)

**Goal:** Render primer page via Datastar, driven by ctx atom.

**Create:** `src/seon/primer/render.clj`
```clojure
(ns seon.primer.render
  "Render engine - maps ctx keys to render functions.

   The pattern: each namespaced key in ctx can have a registered
   render function. Rendering walks ctx and calls the appropriate
   function for each key that needs to be displayed."
  (:require [dev.onionpancakes.chassis.core :as h]))

;; Registry: keyword -> (fn [ctx value] hiccup)
(defonce renderers (atom {}))

(defn register! [k render-fn]
  (swap! renderers assoc k render-fn))

(defn render-key [ctx k]
  (when-let [renderer (get @renderers k)]
    (renderer ctx (get ctx k))))

;; Render all registered keys for current view
(defn render-view [ctx]
  (let [view-keys (or (:ui/view-keys ctx) [:primer/current-scene])]
    [:div#morph.primer-view
     (for [k view-keys]
       (render-key ctx k))]))
```

**Create:** `src/seon/primer/render/scene.clj`
```clojure
(ns seon.primer.render.scene
  "Scene renderer - handles :primer/current-scene"
  (:require [seon.primer.render :as r]))

(defn render-action [{:keys [action/id action/label]}]
  [:button.action-btn
   {:data-on-click (str "@post('/primer/action/" (name id) "')")}
   label])

(defn render-scene [ctx scene]
  (let [{:keys [scene/template scene/params scene/actions]} scene]
    [:div.scene {:data-template (name template)}
     ;; Background layer (z-0)
     [:div.layer.layer-bg
      (when-let [bg (:background params)]
        [:div.background {:style {:background-image (str "url(" bg ")")}}])]

     ;; Content layer (z-10)
     [:div.layer.layer-content
      (case template
        :narrative/page
        [:div.narrative
         [:p.story-text (:text params)]]

        ;; Default: just show params
        [:pre (pr-str params)])]

     ;; Actions layer (z-20)
     [:div.layer.layer-actions
      (when (seq actions)
        [:div.action-bar
         (map render-action actions)])]]))

;; Register this renderer
(r/register! :primer/current-scene render-scene)
```

**Create:** `src/seon/primer/styles.clj`
```clojure
(ns seon.primer.styles
  "CSS styles for Primer - inline for simplicity.")

(def base-css "
/* === Layout === */
.primer-view {
  font-family: Georgia, 'Times New Roman', serif;
  max-width: 100vw;
  min-height: 100vh;
  background: #1a1a2e;
  color: #eee;
}

.scene {
  position: relative;
  width: 100%;
  min-height: 100vh;
}

/* === Layers (stacked via z-index) === */
.layer {
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
}

.layer-bg { z-index: 0; }
.layer-content { z-index: 10; display: flex; align-items: center; justify-content: center; }
.layer-actions { z-index: 20; display: flex; align-items: flex-end; justify-content: center; padding-bottom: 3rem; }

/* === Content === */
.narrative {
  max-width: 600px;
  padding: 2rem;
  text-align: center;
}

.story-text {
  font-size: 1.8rem;
  line-height: 1.8;
  text-shadow: 2px 2px 4px rgba(0,0,0,0.5);
}

/* === Actions === */
.action-bar {
  display: flex;
  gap: 1rem;
}

.action-btn {
  font-family: inherit;
  font-size: 1.2rem;
  padding: 0.75rem 1.5rem;
  background: rgba(255,255,255,0.1);
  border: 1px solid rgba(255,255,255,0.3);
  color: #fff;
  cursor: pointer;
  transition: all 0.2s;
}

.action-btn:hover {
  background: rgba(255,255,255,0.2);
  transform: translateY(-2px);
}

/* === Background === */
.background {
  width: 100%;
  height: 100%;
  background-size: cover;
  background-position: center;
  opacity: 0.6;
}
")
```

**Create:** `src/seon/primer/html.clj`
```clojure
(ns seon.primer.html
  "Primer HTML pages and SSE content."
  (:require [dev.onionpancakes.chassis.core :as h]
            [seon.primer.state :as state]
            [seon.primer.render :as render]
            [seon.primer.render.scene] ; Load to register renderer
            [seon.primer.styles :as styles]))

(defn primer-content []
  (h/html (render/render-view @state/ctx)))

(defn primer-page []
  (h/html
    [:html
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title "Primer"]
      [:script {:src "https://cdn.jsdelivr.net/npm/@sudodevnull/datastar@1.0.0-beta.1/bundles/datastar.js"
                :defer true :type "module"}]
      [:style styles/base-css]]
     [:body {:style "margin: 0; padding: 0;"}
      [:div {:data-on-load "@post('/primer')"}]
      [:main#morph [:p {:style "color: #666; text-align: center; padding: 2rem;"} "Loading..."]]]]))
```

**Create:** `src/seon/primer/handlers.clj`
```clojure
(ns seon.primer.handlers
  "HTTP handlers for Primer routes."
  (:require [seon.primer.html :as html]
            [seon.primer.state :as state]
            [seon.web.sse :as sse]))

(defn primer-page [_request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (html/primer-page)})

(def primer-sse
  (sse/render-handler
    (fn [_request]
      (html/primer-content))))
```

**Modify:** `src/seon/web/routes.clj`
```clojure
;; Add primer routes
[:get "/primer"]              primer-handlers/primer-page
[:post "/primer"]             primer-handlers/primer-sse
```

**REPL verification:**
```clojure
(reset)

;; Set up a test scene
(require '[seon.primer.state :as state])
(state/update-ctx! assoc :primer/current-scene
  {:scene/id "test-1"
   :scene/template :narrative/page
   :scene/params {:text "Once upon a time, in a land of code..."}
   :scene/actions [{:action/id :continue
                    :action/label "Continue"
                    :action/handler 'seon.primer.actions/continue}]})

;; Visit http://localhost:8080/primer
;; Should see the story text and Continue button
```

**Checkpoint:** `git commit -m "Add primer hello world route with Datastar"`

---

### Stage 5: Action Handler (Commit)

**Goal:** Handle button clicks, update ctx, see UI update via SSE.

**Create:** `src/seon/primer/actions.clj`
```clojure
(ns seon.primer.actions
  "Action handlers that mutate ctx."
  (:require [seon.primer.state :as state]
            [seon.web.sse :as sse]))

(defn handle-action [action-id]
  ;; Look up action in current scene
  (let [scene (:primer/current-scene @state/ctx)
        actions (:scene/actions scene)
        action (first (filter #(= (:action/id %) action-id) actions))]
    (when action
      ;; For now, just update to a new scene
      (state/update-ctx! assoc :primer/current-scene
        {:scene/id "scene-2"
         :scene/template :narrative/page
         :scene/params {:text "You continued the story! The adventure unfolds..."}
         :scene/actions [{:action/id :back
                          :action/label "Go back"
                          :action/handler 'seon.primer.actions/back}]})
      ;; Trigger SSE refresh
      (sse/refresh-all!))))
```

**Add to handlers.clj:**
```clojure
(defn action-handler [request]
  (let [action-id (keyword (get-in request [:path-params :action-id]))]
    (actions/handle-action action-id)
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body "{\"ok\": true}"}))
```

**Add route:**
```clojure
[:post "/primer/action/:action-id"] primer-handlers/action-handler
```

**REPL verification:**
```clojure
(reset)

;; Reset to initial scene
(require '[seon.primer.state :as state])
(state/update-ctx! assoc :primer/current-scene
  {:scene/id "test-1"
   :scene/template :narrative/page
   :scene/params {:text "The beginning..."}
   :scene/actions [{:action/id :continue
                    :action/label "Continue"
                    :action/handler 'seon.primer.actions/continue}]})

;; Open http://localhost:8080/primer
;; Click "Continue" button
;; Page should update without refresh (SSE)
```

**Checkpoint:** `git commit -m "Add primer action handling with SSE updates"`

---

### Stage 6: Wire ctx Watch for Auto-Refresh (Commit)

**Goal:** State changes automatically trigger SSE refresh (no manual refresh-all! calls).

**Modify:** `src/seon/primer/state.clj`
```clojure
;; Add watch that triggers SSE refresh on ctx change
(defonce _ctx-watch
  (add-watch ctx :sse-auto-refresh
    (fn [_ _ old-val new-val]
      (when (not= old-val new-val)
        (require 'seon.web.sse)
        ((resolve 'seon.web.sse/refresh-all!))))))
```

**Remove** manual `sse/refresh-all!` from actions.clj.

**REPL verification:**
```clojure
(reset)

;; Manually update ctx from REPL
(require '[seon.primer.state :as state])
(state/update-ctx! assoc :primer/current-scene
  {:scene/id "repl-update"
   :scene/template :narrative/page
   :scene/params {:text "Updated from REPL!"}
   :scene/actions []})

;; Browser should update automatically (SSE)
```

**Checkpoint:** `git commit -m "Add ctx watch for automatic SSE refresh"`

---

### Stage 7: Ctx API with Auto-Persistence (Commit)

**Goal:** Replace single ctx atom with multi-session ctx API. Background thread auto-syncs to XTDB. Non-serializable values warn but don't fail.

**Design principles:**
- Atom = permissive, fast, full Clojure power
- XTDB sync = automatic, background thread, best-effort
- Non-serializable keys = warn in logs, skip in persistence
- Agent learns which keys are runtime-only vs persistent

**Replace:** `src/seon/primer/state.clj` → `src/seon/primer/ctx.clj`

```clojure
(ns seon.primer.ctx
  "Ctx management - multi-session atom with auto XTDB persistence.

  The atom is permissive - store anything. Background sync to XTDB
  skips non-serializable values with warnings. Agent code should
  regenerate runtime-only data on load."
  (:require [taoensso.timbre :as log]
            [seon.db.node :as db]
            [xtdb.api :as xt]))

;; All sessions in memory: {session-id -> ctx-map}
(defonce sessions (atom {}))

;; Reference to primer XTDB node (set on system start)
(defonce ^:private primer-node (atom nil))

(defn init!
  "Initialize ctx system with XTDB node. Called by Integrant."
  [node]
  (reset! primer-node node)
  (log/info "Ctx system initialized"))

;;; === Core API (atom-based, instant) ===

(defn get
  "Get ctx for session. Returns nil if not found."
  [session-id]
  (clojure.core/get @sessions session-id))

(defn get-in
  "Get nested value from session ctx."
  [session-id path]
  (clojure.core/get-in @sessions (cons session-id path)))

(defn update!
  "Update session ctx. Creates session if doesn't exist.
  Returns updated ctx."
  [session-id f & args]
  (let [result (apply swap! sessions update session-id f args)]
    (clojure.core/get result session-id)))

(defn update-in!
  "Update nested value in session ctx."
  [session-id path f & args]
  (apply swap! sessions update-in (cons session-id path) f args)
  (get session-id))

(defn assoc!
  "Set key in session ctx."
  [session-id k v]
  (update! session-id assoc k v))

(defn dissoc!
  "Remove key from session ctx."
  [session-id k]
  (update! session-id dissoc k))

;;; === Session Lifecycle ===

(defn create!
  "Create new session with initial data."
  [session-id initial-data]
  (swap! sessions assoc session-id
         (merge {:session/id session-id
                 :session/created-at (java.time.Instant/now)}
                initial-data))
  (get session-id))

(defn destroy!
  "Remove session from memory."
  [session-id]
  (swap! sessions dissoc session-id))

;;; === Persistence (background, best-effort) ===

(defn- serializable?
  "Check if value can be serialized to XTDB (EDN-compatible)."
  [v]
  (try
    (pr-str v)
    true
    (catch Exception _ false)))

(defn- filter-serializable
  "Filter ctx to only serializable keys. Warns on skipped keys."
  [ctx]
  (reduce-kv
    (fn [acc k v]
      (if (serializable? v)
        (assoc acc k v)
        (do
          (log/warn "Skipping non-serializable key in ctx persistence"
                    {:key k :type (type v)})
          acc)))
    {}
    ctx))

(defn checkpoint!
  "Save session to XTDB. Skips non-serializable values with warning."
  [session-id]
  (when-let [ctx (get session-id)]
    (let [persistable (filter-serializable ctx)]
      (xt/submit-tx @primer-node
        [[:put-docs :primer/sessions
          (assoc persistable
                 :xt/id session-id
                 :session/checkpointed-at (java.time.Instant/now))]])
      (log/debug "Checkpointed session" {:session-id session-id}))))

(defn checkpoint-all!
  "Checkpoint all active sessions."
  []
  (doseq [session-id (keys @sessions)]
    (checkpoint! session-id)))

;;; === Recovery (load from XTDB) ===

(defn load!
  "Load session from XTDB into atom. Returns ctx or nil."
  [session-id]
  (when-let [ctx (xt/entity (xt/db @primer-node) session-id)]
    (swap! sessions assoc session-id ctx)
    ctx))

(defn load-at!
  "Load historical session state into atom."
  [session-id as-of-instant]
  (when-let [ctx (xt/entity (xt/db @primer-node as-of-instant) session-id)]
    (swap! sessions assoc session-id ctx)
    ctx))

;;; === Temporal Queries (read-only, doesn't affect atom) ===

(defn at
  "Get session ctx at point in time. Doesn't modify atom."
  [session-id as-of-instant]
  (xt/entity (xt/db @primer-node as-of-instant) session-id))

(defn history
  "Get checkpoint history for session."
  [session-id]
  (xt/entity-history (xt/db @primer-node) session-id :desc))

;;; === Background Auto-Sync ===

(defonce ^:private sync-running (atom false))

(defn start-auto-sync!
  "Start background thread that checkpoints all sessions periodically."
  [interval-ms]
  (reset! sync-running true)
  (future
    (while @sync-running
      (try
        (Thread/sleep interval-ms)
        (checkpoint-all!)
      (catch Exception e
        (log/error e "Error in ctx auto-sync"))))))

(defn stop-auto-sync! []
  (reset! sync-running false))

;;; === SSE Integration ===

(defonce ^:private _sessions-watch
  (add-watch sessions :sse-auto-refresh
    (fn [_ _ old-val new-val]
      (when (not= old-val new-val)
        (require 'seon.web.sse)
        ((resolve 'seon.web.sse/refresh-all!))))))
```

**Update Integrant wiring** in `src/seon/system.clj`:
```clojure
;; In :seon.primer/xtdb-node init-key, after creating node:
(require 'seon.primer.ctx)
((resolve 'seon.primer.ctx/init!) node)
((resolve 'seon.primer.ctx/start-auto-sync!) 5000)  ; 5 second sync
```

**Update render/handlers** to use new API:
```clojure
;; Old:
@state/ctx

;; New:
(ctx/get "default")  ; or session-id from request
```

**REPL verification:**
```clojure
(reset)

(require '[seon.primer.ctx :as ctx])

;; Create a session
(ctx/create! "test-session" {:primer/current-scene
  {:scene/id "hello"
   :scene/template :narrative/page
   :scene/params {:text "Hello, Primer!"}
   :scene/actions []}})

;; Read it
(ctx/get "test-session")
;; => {:session/id "test-session", :primer/current-scene {...}, ...}

;; Update it (UI should refresh)
(ctx/assoc! "test-session" :primer/current-scene
  {:scene/id "updated"
   :scene/template :narrative/page
   :scene/params {:text "Updated via REPL!"}
   :scene/actions []})

;; Store something non-serializable (should warn, not fail)
(ctx/assoc! "test-session" :runtime/my-fn (fn [x] x))
;; => logs warning about skipping :runtime/my-fn

;; Manual checkpoint
(ctx/checkpoint! "test-session")

;; Check it persisted (non-serializable key skipped)
(ctx/at "test-session" (java.time.Instant/now))
;; => has :primer/current-scene but NOT :runtime/my-fn

;; Time travel
(ctx/history "test-session")
;; => list of historical states
```

**Checkpoint:** `git commit -m "Add ctx API with multi-session and auto-persistence"`

---

## Implementation Summary

**Stages 1-7 Complete** (as of 2024-12-24)

### What's Working
- Primer domain skeleton with capabilities
- Separate XTDB node for primer data
- Multi-session ctx API with auto-persistence (5s background sync)
- Render registry pattern (ctx key → render function)
- SSE auto-refresh on ctx changes
- Basic scene template rendering
- Demo scenes in actions.clj

### Known Issues (Need Browser Testing)
- **Button clicks not firing** - User sees content but actions don't work
- Route matching was fixed but may still have issues
- Need end-to-end verification with actual browser

### Files Structure
```
src/seon/primer/
├── core.clj           # Domain capabilities
├── schema.clj         # Malli schemas
├── ctx.clj            # Multi-session ctx API + XTDB sync
├── render.clj         # Render registry
├── render/scene.clj   # Scene renderer
├── styles.clj         # CSS
├── html.clj           # Datastar page + SSE content
├── handlers.clj       # HTTP handlers
└── actions.clj        # Action handlers + demo scenes
```

---

## Research Documents

- `research/architecture-vision.md` - Core loop and scene structure
- `research/template-system.md` - Template vocabulary
- `research/state-machine.md` - Transition and checkpoint patterns
- `research/ctx-as-os.md` - The ctx atom as OS pattern
- `research/seon-architecture-research.md` - Existing Seon patterns

---

## Open Questions

1. **Visual layer** - CSS positioning vs Canvas vs Three.js?
2. **Voice integration** - Gemini Live API?
3. **Image generation** - Pipeline and caching?
4. **Child profiles** - Multi-user support?

*Address after Stage 6 is working.*
